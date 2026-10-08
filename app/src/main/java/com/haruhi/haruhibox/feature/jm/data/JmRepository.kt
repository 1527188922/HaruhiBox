package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.database.JmAlbumDao
import com.haruhi.haruhibox.core.database.JmChapterImageDao
import com.haruhi.haruhibox.core.database.JmFavoriteDao
import com.haruhi.haruhibox.core.database.JmRecentAlbumDao
import com.haruhi.haruhibox.core.database.JmSearchHistoryDao
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmRecentAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.core.storage.FileNaming
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmAlbumDto
import com.haruhi.haruhibox.feature.jm.api.JmApiClient
import com.haruhi.haruhibox.feature.jm.api.JmChapterDto
import com.haruhi.haruhibox.feature.jm.api.JmJson
import com.haruhi.haruhibox.feature.jm.api.JmSearchItemDto
import com.haruhi.haruhibox.feature.jm.api.JmSeriesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JM 仓库：在线数据 ↔ 本地库。
 *
 * 语义逐条对齐参考实现 `JmcomicSqliteServiceImpl`，这三条最容易写错，单独说明：
 *
 * 1. **入库是"全量覆盖但保留本地收藏"**。参考实现 `saveOrUpdateAlbum` 的做法是
 *    `deleteById` + `insert`，并在覆盖前把旧的 `collected` 带过来
 *    （它注释写明："collected 是本地收藏标记，JM 响应里没有这个字段…否则会丢掉收藏状态"）。
 *    本工程用 `insert(IGNORE)` / `update` 达到同样的效果，但**不删除行**——
 *    行里还有本地字段，删了再插会连带丢掉。
 *
 * 2. **章节列表的权威来源是 `album.series`**。参考实现 `listChapters` 先看
 *    `album.series` 是否为空：非空就用它；为空才回落到章节图片表。
 *    **不能只从已下载的图片反推章节**（计划 §D.1 的高优先级项），
 *    否则没下载过的本子在详情页会显示"没有章节"。
 *
 * 3. **章节图片入库是"整章替换"**。参考实现先按 `(albumId, chapterId)` 删除再插入，
 *    并把图片按 `sortImageFiles` 排序后写 `imageSort = i + 1`（从 1 开始，不是 0）。
 *
 * 另外：`chapterTitle` 取 `series.title`（参考实现 `toChapterImageEntities`），
 * 但 `Series.title` 在 `/album` 响应里通常为空、由 service 层补 `第{sort}话`。
 * 所以入库时也要做同样的补全，否则章节文件夹名会退化成 chapterId
 * （`getChapterFolderName` 的顺序是 title → name → id）。
 */
class JmRepository(
    private val api: JmApiClient,
    private val albumDao: JmAlbumDao,
    private val chapterDao: JmChapterImageDao,
    private val favoriteDao: JmFavoriteDao,
    private val recentDao: JmRecentAlbumDao,
    private val searchHistoryDao: JmSearchHistoryDao,
    private val dirs: LibraryDirs,
    private val searchHistoryLimit: () -> Int = { JmDefaults.SEARCH_HISTORY_LIMIT },
    private val saveSearchHistory: () -> Boolean = { true },
    private val albumNameMaxBytes: () -> Int = { FileNaming.DEFAULT_ALBUM_NAME_MAX_BYTES },
) {

    // ------------------------------------------------------------------
    // 在线搜索
    // ------------------------------------------------------------------

    /**
     * 在线搜索。结果里每条都带 `existsLocal`（是否已入库），
     * 对应参考实现 `onlineSearch` 的"补上是否已入库标识"。
     *
     * 搜完会记一条搜索历史（可在设置里关闭）。
     */
    suspend fun searchOnline(
        query: String,
        sort: JmSearchSort = JmSearchSort.DEFAULT,
        page: Int = 1,
    ): OnlineSearchResult {
        val resp = api.search(query, sort, page)
        val items = resp.content.orEmpty()
        val ids = items.mapNotNull { it.id?.toLongOrNull() }
        val localIds = if (ids.isEmpty()) emptySet() else albumDao.existingIds(ids).toSet()

        val total = resp.total?.toLongOrNull() ?: items.size.toLong()
        val pageSize = JmDefaults.SEARCH_PAGE_SIZE
        val result = OnlineSearchResult(
            query = query.trim(),
            sort = sort,
            page = page,
            total = total,
            pageSize = pageSize,
            totalPages = ((total + pageSize - 1) / pageSize).toInt().coerceAtLeast(1),
            items = items.mapNotNull { it.toItem(localIds) },
        )
        recordSearchHistory(result)
        return result
    }

    private suspend fun recordSearchHistory(result: OnlineSearchResult) {
        if (!saveSearchHistory()) return
        val limit = searchHistoryLimit()
        if (limit <= 0) return
        runCatching {
            val keyword = result.query
            val sortCode = result.sort.code
            val existing = searchHistoryDao.findSameCondition(keyword, sortCode, result.page)
            val itemsJson = JmJson.encodeToString(
                ListSerializer(JmSearchItemDto.serializer()),
                result.items.map { it.raw },
            )
            val now = System.currentTimeMillis()
            if (existing != null) {
                // 相同条件只刷新时间，避免同一条件反复搜索刷屏（参考实现语义）
                searchHistoryDao.refresh(existing.id, now, result.total, result.items.size, itemsJson)
            } else {
                searchHistoryDao.insert(
                    JmSearchHistoryEntity(
                        keyword = keyword,
                        sort = sortCode,
                        page = result.page,
                        total = result.total,
                        resultCount = result.items.size,
                        items = itemsJson,
                    ),
                )
            }
            searchHistoryDao.trim(limit)
        }.onFailure { Timber.tag("jm").w(it, "保存搜索历史失败（不影响搜索）") }
    }

    fun observeSearchHistory(limit: Int = searchHistoryLimit()): Flow<List<JmSearchHistoryEntity>> =
        searchHistoryDao.observeRecent(limit)

    suspend fun deleteSearchHistory(id: Long) = searchHistoryDao.deleteById(id)

    suspend fun clearSearchHistory(): Int = searchHistoryDao.clear()

    suspend fun searchHistoryCount(): Int = searchHistoryDao.count()

    /**
     * 点历史记录时直接回显快照，不再请求 JM（参考实现就是这么做的），
     * 但"是否已入库"按**当前**库状态重新计算，避免显示过期状态。
     */
    suspend fun searchHistorySnapshot(id: Long): OnlineSearchResult? {
        val row = searchHistoryDao.findById(id) ?: return null
        val raw = row.items?.takeIf { it.isNotBlank() }?.let { json ->
            runCatching {
                JmJson.decodeFromString(ListSerializer(JmSearchItemDto.serializer()), json)
            }.getOrNull()
        }.orEmpty()
        val ids = raw.mapNotNull { it.id?.toLongOrNull() }
        val localIds = if (ids.isEmpty()) emptySet() else albumDao.existingIds(ids).toSet()
        return OnlineSearchResult(
            query = row.keyword,
            sort = JmSearchSort.fromCode(row.sort),
            page = row.page,
            total = row.total,
            pageSize = JmDefaults.SEARCH_PAGE_SIZE,
            totalPages = row.page,
            items = raw.mapNotNull { it.toItem(localIds) },
            fromHistory = true,
        )
    }

    // ------------------------------------------------------------------
    // 本子入库、最近浏览与详情
    // ------------------------------------------------------------------

    /**
     * 点进详情页时调用：**只更新最近浏览的浏览时间**，不写书库。
     *
     * 用户明确要求「点详情不等于加入书库」，所以入书库只发生在
     * [addToLibrary] / 搜索结果里的「加入书库」按钮。
     *
     * 三种进入路径（搜索、书库、收藏夹）都会走这里，所以浏览时间一致更新。
     * 记录还不存在时先建一条骨架，等详情数据回来再补全（[saveToRecent]）。
     */
    suspend fun touchRecent(aid: Long) {
        val now = System.currentTimeMillis()
        val existing = recentDao.findById(aid)
        if (existing == null) {
            // 骨架行：只有 id 与浏览时间，字段随后由 saveToRecent 补齐
            recentDao.upsert(
                JmRecentAlbumEntity(
                    id = aid,
                    albumFolderName = FileNaming.albumFolderName(null, aid.toString(), albumNameMaxBytes()),
                    createTime = nowText(),
                    modifyTime = nowText(),
                    viewedTime = now,
                ),
            )
        } else {
            recentDao.touch(aid, now)
        }
    }

    /** 把在线详情写进最近浏览表，**保留原有的浏览时间**（刷新按钮用）。 */
    suspend fun saveToRecent(dto: JmAlbumDto): JmRecentAlbumEntity {
        val existing = recentDao.findById(dto.id)
        val now = System.currentTimeMillis()
        val entity = toRecentEntity(dto).copy(
            viewedTime = existing?.viewedTime ?: now,
            createTime = existing?.createTime ?: nowText(),
            modifyTime = nowText(),
        )
        recentDao.upsert(entity)
        return entity
    }

    /** 最近浏览列表（按浏览时间倒序）。 */
    fun observeRecentAlbums(limit: Int = 60, offset: Int = 0): Flow<List<JmRecentAlbumEntity>> =
        recentDao.observeRecent(limit, offset)

    fun observeRecentCount(): Flow<Int> = recentDao.observeCount()

    suspend fun findRecent(aid: Long): JmRecentAlbumEntity? = recentDao.findById(aid)

    suspend fun removeRecent(aid: Long) = recentDao.delete(aid)

    suspend fun clearRecent(): Int = recentDao.clear()

    /**
     * 详情页数据来源：**先本地（书库 → 最近浏览），都没有才请求网络**。
     *
     * `null` 表示两处都没有，调用方需要走一次网络。
     */
    suspend fun localAlbumDetail(aid: Long): LocalAlbumDetail? {
        albumDao.findById(aid)?.let { return LocalAlbumDetail(it, fromLibrary = true) }
        recentDao.findById(aid)?.let { return LocalAlbumDetail(it.toAlbumEntity(), fromLibrary = false) }
        return null
    }

    /**
     * 拉取本子详情。
     *
     * @param saveToLibrary 是否写入书库。点详情页时为 false（只进最近浏览）；
     *        用户点「加入书库」或搜索结果点「加入书库」时为 true。
     *
     * 无论哪种情况都会写最近浏览表（用户要求"同时将请求结果存入最近浏览表"）。
     */
    suspend fun fetchAlbumDetail(aid: Long, saveToLibrary: Boolean): JmAlbumDto {
        val dto = api.album(aid)
        saveToRecent(dto)
        if (saveToLibrary) saveAlbum(dto)
        return dto
    }

    /** 把本子加入书库（书库页的「加入书库」按钮 / 搜索结果按钮）。 */
    suspend fun addToLibrary(aid: Long): JmAlbumEntity {
        val dto = api.album(aid)
        saveToRecent(dto)
        return saveAlbum(dto)
    }

    /** 把接口返回的本子写库（不请求网络）。 */
    suspend fun saveAlbum(dto: JmAlbumDto): JmAlbumEntity {
        val now = nowText()
        val incoming = toAlbumEntity(dto)

        val existing = albumDao.findById(dto.id)
        if (existing == null) {
            albumDao.insertIgnore(incoming.copy(createTime = now, modifyTime = now))
        } else {
            // **保留本地的 collected 与 createTime**（参考实现的 delete+insert 也做同样的事）
            albumDao.update(
                incoming.copy(
                    collected = existing.collected,
                    createTime = existing.createTime ?: now,
                    modifyTime = now,
                ),
            )
        }
        return albumDao.findById(dto.id) ?: error("本子入库失败：${dto.id}")
    }

    /** 接口 DTO → 书库实体。 */
    private fun toAlbumEntity(dto: JmAlbumDto) = JmAlbumEntity(
        id = dto.id,
        name = dto.name,
        albumFolderName = FileNaming.albumFolderName(dto.name, dto.id.toString(), albumNameMaxBytes()),
        images = encodeStringList(dto.images),
        addTime = dto.addTime,
        description = dto.description,
        totalViews = dto.totalViews,
        likes = dto.likes,
        // series 原样存 JSON 字符串：详情页的章节列表以它为准（见类注释第 2 条）
        series = dto.series?.let { JmJson.encodeToString(ListSerializer(JmSeriesDto.serializer()), it) },
        seriesId = dto.seriesId,
        commentTotal = dto.commentTotal,
        author = encodeStringList(dto.author),
        tags = encodeStringList(dto.tags),
        works = encodeStringList(dto.works),
        actors = encodeStringList(dto.actors),
        relatedList = dto.relatedList?.let {
            JmJson.encodeToString(
                ListSerializer(com.haruhi.haruhibox.feature.jm.api.JmRelatedDto.serializer()),
                it,
            )
        },
        liked = dto.liked,
        isFavorite = dto.isFavorite,
        isAids = dto.isAids,
        price = dto.price,
        purchased = dto.purchased,
    )

    /** 接口 DTO → 最近浏览实体（字段与书库实体一致，只是多一个浏览时间）。 */
    private fun toRecentEntity(dto: JmAlbumDto) = JmRecentAlbumEntity(
        id = dto.id,
        name = dto.name,
        albumFolderName = FileNaming.albumFolderName(dto.name, dto.id.toString(), albumNameMaxBytes()),
        images = encodeStringList(dto.images),
        addTime = dto.addTime,
        description = dto.description,
        totalViews = dto.totalViews,
        likes = dto.likes,
        series = dto.series?.let { JmJson.encodeToString(ListSerializer(JmSeriesDto.serializer()), it) },
        seriesId = dto.seriesId,
        commentTotal = dto.commentTotal,
        author = encodeStringList(dto.author),
        tags = encodeStringList(dto.tags),
        works = encodeStringList(dto.works),
        actors = encodeStringList(dto.actors),
        relatedList = dto.relatedList?.let {
            JmJson.encodeToString(
                ListSerializer(com.haruhi.haruhibox.feature.jm.api.JmRelatedDto.serializer()),
                it,
            )
        },
        liked = dto.liked,
        isFavorite = dto.isFavorite,
        isAids = dto.isAids,
        price = dto.price,
        purchased = dto.purchased,
    )

    /** 拉取章节详情（图片清单）并入库。返回入库后的图片数。 */
    suspend fun fetchAndSaveChapter(albumId: Long, chapterId: Long): Int {
        val album = albumDao.findById(albumId)
        val chapter = api.chapter(chapterId)
        saveChapterImages(albumId, chapter, album?.let { seriesOf(it, chapterId) })
        return chapterDao.imageCount(albumId, chapterId)
    }

    /** 只拉章节详情，不入库（调用方决定何时写库）。 */
    suspend fun fetchChapter(chapterId: Long): JmChapterDto = api.chapter(chapterId)

    /** 从本子已存的 `series` JSON 里找出某一章对应的条目。 */
    fun seriesOf(album: JmAlbumEntity, chapterId: Long): JmSeriesDto? =
        album.series
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { json ->
                runCatching {
                    JmJson.decodeFromString(ListSerializer(JmSeriesDto.serializer()), json)
                }.getOrNull()
            }
            ?.firstOrNull { it.id == chapterId.toString() }

    /** 某章的 `scramble_id`（解扰块数的输入），异常时返回兜底值。 */
    suspend fun scrambleIdOf(chapterId: Long): Long = api.scrambleId(chapterId)

    suspend fun findAlbum(aid: Long): JmAlbumEntity? = albumDao.findById(aid)

    fun observeAlbum(aid: Long): Flow<JmAlbumEntity?> = albumDao.observeById(aid)

    /**
     * 章节图片清单入库（整章替换）。
     *
     * 排序与 `imageSort` 起始值照抄参考实现：先 `sortImageFiles` 排序，
     * 再写 `imageSort = index + 1`。
     */
    suspend fun saveChapterImages(albumId: Long, chapter: JmChapterDto, series: JmSeriesDto? = null) {
        val rawImages = chapter.images.orEmpty()
        if (rawImages.isEmpty()) {
            Timber.tag("jm").d("章节无图片，跳过入库 chapterId=%d", chapter.id)
            return
        }
        val sorted = FileNaming.sortImageFiles(rawImages)
        val title = chapterTitleOf(series, chapter)
        val entities = sorted.mapIndexed { index, image ->
            JmChapterImageEntity(
                albumId = albumId,
                chapterId = chapter.id,
                chapterSort = series?.sort,
                chapterTitle = title,
                // 参考实现：有 series 时用 series.name，否则用 chapter.name
                chapterName = if (series != null) series.name else chapter.name,
                chapterAddTime = chapter.addTime,
                seriesId = chapter.seriesId,
                liked = chapter.liked,
                isFavorite = chapter.isFavorite,
                imageFile = image,
                imageSort = index + 1,
            )
        }
        chapterDao.replaceChapter(albumId, chapter.id, entities)
    }

    /** 章节展示名：`series.title` → `第{sort}话` → `chapter.name` → chapterId。 */
    private fun chapterTitleOf(series: JmSeriesDto?, chapter: JmChapterDto): String {
        val fromSeries = series?.title?.takeIf { it.isNotBlank() }
            ?: series?.sort?.takeIf { it.isNotBlank() }?.let { "第${it}话" }
        if (fromSeries != null) return fromSeries
        return chapter.name?.takeIf { it.isNotBlank() } ?: chapter.id.toString()
    }

    /**
     * 详情页的章节列表。
     *
     * 优先用 `album.series`（在线数据），为空时回落到章节图片表（本地已下载的章节），
     * 与参考实现 `listChapters` 的分支一致。
     */
    suspend fun chaptersOf(album: JmAlbumEntity): List<ChapterSummary> {
        val series = album.series
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { json ->
                runCatching {
                    JmJson.decodeFromString(ListSerializer(JmSeriesDto.serializer()), json)
                }.getOrNull()
            }
        if (!series.isNullOrEmpty()) {
            return series
                .filter { !it.id.isNullOrBlank() }
                .map { dto ->
                    val chapterId = dto.id!!.toLongOrNull() ?: return@map null
                    ChapterSummary(
                        chapterId = chapterId,
                        sort = dto.sort,
                        name = dto.name,
                        title = dto.title?.takeIf { it.isNotBlank() }
                            ?: dto.sort?.takeIf { it.isNotBlank() }?.let { "第${it}话" }
                            ?: dto.name
                            ?: chapterId.toString(),
                    )
                }
                .filterNotNull()
        }

        return chapterDao.chapterList(album.id).map { row ->
            ChapterSummary(
                chapterId = row.chapterId,
                sort = row.chapterSort,
                name = row.chapterName,
                title = row.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: row.chapterName?.takeIf { it.isNotBlank() }
                    ?: row.chapterId.toString(),
            )
        }
    }

    /** 某章的图片清单（本地已入库的）。 */
    suspend fun imagesOf(albumId: Long, chapterId: Long): List<JmChapterImageEntity> =
        chapterDao.images(albumId, chapterId)

    fun observeImages(albumId: Long, chapterId: Long): Flow<List<JmChapterImageEntity>> =
        chapterDao.observeImages(albumId, chapterId)

    /**
     * 统计某章在磁盘上已有的图片数（"已下载 / 部分下载 / 未下载"的判定依据）。
     *
     * 判定规则与下载层一致：**文件存在且非空**才算数
     * （0 字节残留文件必须重新下载，见 `DownloadTaskRepositoryTest` 的用例）。
     */
    fun downloadedImageCount(albumFolderName: String?, chapterFolder: String, imageFiles: List<String>): Int {
        if (albumFolderName.isNullOrBlank()) return 0
        val dir = dirs.chapterDir(albumFolderName, chapterFolder)
        if (!dir.isDirectory) return 0
        return imageFiles.count { name -> File(dir, name).let { it.isFile && it.length() > 0 } }
    }

    /** 章节目录名：`title` → `name` → `chapterId`（对齐参考实现 `getChapterFolderName`）。 */
    fun chapterFolderName(summary: ChapterSummary): String =
        summary.title.takeIf { it.isNotBlank() }
            ?: summary.name?.takeIf { it.isNotBlank() }
            ?: summary.chapterId.toString()

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    fun observeFavorites(): Flow<List<JmFavoriteEntity>> = favoriteDao.observeAll()

    suspend fun listFavorites(): List<JmFavoriteEntity> = favoriteDao.all()

    /**
     * 收藏夹列表 + 本子数量。**返回 Flow**：关联表任何变动都会重新发射，
     * 收藏之后收藏页的数量会立刻更新（用户反馈的第 1 点）。
     */
    fun observeFavoritesWithCount(): Flow<List<FavoriteSummary>> =
        favoriteDao.observeFavoritesWithCount().map { rows ->
            rows.map { row ->
                FavoriteSummary(
                    id = row.id,
                    name = row.name,
                    sortOrder = row.sortOrder,
                    albumCount = row.albumCount,
                    isDefault = row.name == DEFAULT_FAVORITE_NAME,
                )
            }
        }

    /** 确保默认收藏夹存在（首次使用时创建，不允许删除/重命名）。 */
    suspend fun ensureDefaultFavorite(): JmFavoriteEntity {
        favoriteDao.findByName(DEFAULT_FAVORITE_NAME)?.let { return it }
        val id = favoriteDao.insert(
            JmFavoriteEntity(name = DEFAULT_FAVORITE_NAME, sortOrder = 0, createTime = nowText()),
        )
        return favoriteDao.findByName(DEFAULT_FAVORITE_NAME)
            ?: error("创建默认收藏夹失败(id=$id)")
    }

    /** 新建收藏夹。重名时返回 null（`name` 上有唯一索引）。 */
    suspend fun createFavorite(name: String): JmFavoriteEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = favoriteDao.insert(JmFavoriteEntity(name = trimmed, sortOrder = nextSortOrder(), createTime = nowText()))
        if (id <= 0) return null
        return favoriteDao.findByName(trimmed)
    }

    private suspend fun nextSortOrder(): Int =
        (favoriteDao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1

    /** 重命名。默认收藏夹不允许改名（参考实现直接抛业务异常）。 */
    suspend fun renameFavorite(id: Long, newName: String): Boolean {
        val favorite = favoriteDao.all().firstOrNull { it.id == id } ?: return false
        if (favorite.name == DEFAULT_FAVORITE_NAME) return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        favoriteDao.rename(id, trimmed, nowText())
        return true
    }

    /**
     * 删除收藏夹：只删夹与关联，**不动书库记录和本地文件**（计划 §9 第 6 条）。
     * 默认收藏夹不允许删除。
     */
    suspend fun deleteFavorite(id: Long): Boolean {
        val favorite = favoriteDao.all().firstOrNull { it.id == id } ?: return false
        if (favorite.name == DEFAULT_FAVORITE_NAME) return false
        favoriteDao.deleteWithLinks(id)
        return true
    }

    /**
     * 收藏：把本子加入收藏夹并置 `collected = true`。
     *
     * 未指定收藏夹时回落到默认收藏夹（参考实现 `addAlbumsToFavorite`）。
     * 幂等：重复收藏同一夹不会产生重复关联（唯一索引 + IGNORE）。
     */
    suspend fun addToFavorite(albumId: Long, favoriteId: Long? = null): Long {
        val target = favoriteId ?: ensureDefaultFavorite().id
        favoriteDao.link(
            JmFavoriteAlbumEntity(favoriteId = target, albumId = albumId, createTime = nowText()),
        )
        albumDao.setCollected(albumId, true, nowText())
        return target
    }

    /** 移出收藏夹：只删关联；其它收藏夹里还有的话 `collected` 保持 true。 */
    suspend fun removeFromFavorite(albumId: Long, favoriteId: Long) {
        favoriteDao.unlink(favoriteId, albumId)
        val remaining = favoriteDao.favoriteIdsOf(albumId)
        albumDao.setCollected(albumId, remaining.isNotEmpty(), nowText())
    }

    /** 取消收藏：移出所有收藏夹。 */
    suspend fun uncollect(albumId: Long) {
        favoriteDao.deleteLinksOfAlbum(albumId)
        albumDao.setCollected(albumId, false, nowText())
    }

    suspend fun favoriteIdsOf(albumId: Long): List<Long> = favoriteDao.favoriteIdsOf(albumId)

    suspend fun setCollected(albumId: Long, collected: Boolean) =
        albumDao.setCollected(albumId, collected, nowText())

    // ------------------------------------------------------------------
    // 书库
    // ------------------------------------------------------------------

    /**
     * 书库列表（含筛选与排序）。
     *
     * 语义对齐参考实现 `searchAlbums`：
     * - `tags` 的多选是 **OR**（命中所选标签之一即可），用 `INSTR(tags, "\"tag\"") > 0`
     *   做子串匹配——因为 `tags` 列存的是 JSON 数组字符串；
     * - **指定收藏夹时不再叠加 `collected` 条件**（收藏夹本身已隐含"已收藏"）；
     * - 默认按 `createTime` 倒序；本工程把收藏置顶，更符合"内容优先"的入口语义。
     */
    fun observeLibrary(
        keyword: String? = null,
        collected: Boolean? = null,
        author: String? = null,
        tags: List<String> = emptyList(),
        favoriteId: Long? = null,
        limit: Int = 60,
        offset: Int = 0,
    ): Flow<List<JmAlbumEntity>> = albumDao.observeLibrary(
        keyword = keyword?.takeIf { it.isNotBlank() },
        // 指定收藏夹时不再叠加 collected（参考实现语义）
        onlyCollected = if (favoriteId != null) null else collected,
        author = author?.takeIf { it.isNotBlank() },
        tagsCsv = tagsCsv(tags),
        favoriteId = favoriteId,
        limit = limit,
        offset = offset,
    )

    fun observeLibraryCount(
        keyword: String? = null,
        collected: Boolean? = null,
        author: String? = null,
        tags: List<String> = emptyList(),
        favoriteId: Long? = null,
    ): Flow<Int> = albumDao.observeLibraryCount(
        keyword = keyword?.takeIf { it.isNotBlank() },
        onlyCollected = if (favoriteId != null) null else collected,
        author = author?.takeIf { it.isNotBlank() },
        tagsCsv = tagsCsv(tags),
        favoriteId = favoriteId,
    )

    /** 标签列表 → DAO 需要的 CSV。空串表示"不筛选"。 */
    private fun tagsCsv(tags: List<String>): String =
        tags.map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('"') && !it.contains(',') }
            .distinct()
            .joinToString(",")

    /** 软删除：只删库记录，不动本地文件（计划 §9.1「删除确认明确区分」）。 */
    suspend fun deleteAlbums(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            favoriteDao.deleteLinksOfAlbum(id)
            chapterDao.deleteByAlbum(id)
        }
        albumDao.deleteByIds(ids)
    }

    /** 删除本地文件（本子目录），**保留库记录**。 */
    suspend fun deleteAlbumFiles(albumFolderName: String?): Long {
        if (albumFolderName.isNullOrBlank()) return 0L
        val dir = dirs.albumDir(albumFolderName)
        val freed = dirs.sizeOf(dir)
        dir.deleteRecursively()
        return freed
    }

    /** 本子本地占用（字节）。 */
    fun localSize(albumFolderName: String?): Long =
        albumFolderName?.takeIf { it.isNotBlank() }?.let { dirs.sizeOf(dirs.albumDir(it)) } ?: 0L

    /** 列表用：把最近浏览记录当作书库实体来渲染（字段一致，所以直接映射）。 */
    private fun JmRecentAlbumEntity.toAlbumEntity() = JmAlbumEntity(
        id = id,
        name = name,
        albumFolderName = albumFolderName,
        images = images,
        addTime = addTime,
        description = description,
        totalViews = totalViews,
        likes = likes,
        series = series,
        seriesId = seriesId,
        commentTotal = commentTotal,
        author = author,
        tags = tags,
        works = works,
        actors = actors,
        relatedList = relatedList,
        liked = liked,
        isFavorite = isFavorite,
        // 最近浏览里没有收藏语义，留空
        collected = null,
        isAids = isAids,
        price = price,
        purchased = purchased,
        raw = raw,
        createTime = createTime,
        modifyTime = modifyTime,
    )

    private fun nowText(): String = TEXT_FORMAT.format(LocalDateTime.now())

    private fun encodeStringList(list: List<String>?): String? =
        list?.let { JmJson.encodeToString(ListSerializer(String.serializer()), it) }

    companion object {
        const val DEFAULT_FAVORITE_NAME = "默认收藏夹"

        private val TEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** 在线搜索的一条结果。 */
data class OnlineSearchItem(
    val id: Long,
    val name: String?,
    val author: String?,
    val description: String?,
    val coverUrl: String,
    val tags: List<String>,
    val updateAt: Long?,
    val existsLocal: Boolean,
    val raw: JmSearchItemDto,
)

/** 在线搜索的一页结果。 */
data class OnlineSearchResult(
    val query: String,
    val sort: JmSearchSort,
    val page: Int,
    val total: Long,
    val pageSize: Int,
    val totalPages: Int,
    val items: List<OnlineSearchItem>,
    /** 是否来自历史快照（来自历史时不再请求 JM）。 */
    val fromHistory: Boolean = false,
)

/** 详情页的章节条目。 */
data class ChapterSummary(
    val chapterId: Long,
    val sort: String?,
    val name: String?,
    val title: String,
)

/**
 * 详情页的本地数据。
 *
 * [fromLibrary] 区分数据来源：
 * - true：来自书库表，详情页显示"已在书库"；
 * - false：只来自最近浏览表，详情页显示「加入书库」按钮。
 */
data class LocalAlbumDetail(
    val album: JmAlbumEntity,
    val fromLibrary: Boolean,
)

/** 收藏夹 + 本子数量。 */
data class FavoriteSummary(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val albumCount: Int,
    val isDefault: Boolean,
)

private fun JmSearchItemDto.toItem(localIds: Set<Long>): OnlineSearchItem? {
    val jmId = id?.toLongOrNull() ?: return null
    return OnlineSearchItem(
        id = jmId,
        name = name,
        author = author,
        description = description,
        // 封面走图片线路：`https://{cdn}/media/albums/{aid}.jpg`
        coverUrl = JmDefaults.buildCoverUrl(jmId),
        tags = listOfNotNull(category?.title, categorySub?.title).filter { it.isNotBlank() },
        updateAt = updateAt,
        existsLocal = jmId in localIds,
        raw = this,
    )
}
