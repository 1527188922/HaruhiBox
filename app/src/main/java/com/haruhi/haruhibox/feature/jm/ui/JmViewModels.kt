package com.haruhi.haruhibox.feature.jm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmRecentAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.core.network.toUserMessage
import com.haruhi.haruhibox.core.settings.SecretStore
import com.haruhi.haruhibox.core.settings.SettingKeys
import com.haruhi.haruhibox.feature.jm.data.FavoriteWriteResult
import com.haruhi.haruhibox.feature.jm.data.JmAlbumCleaner
import com.haruhi.haruhibox.feature.jm.data.JmDownloadManager
import com.haruhi.haruhibox.feature.jm.data.JmExportFormat
import com.haruhi.haruhibox.feature.jm.data.JmExportManager
import com.haruhi.haruhibox.feature.jm.data.JmReadingRepository
import com.haruhi.haruhibox.feature.jm.data.JmRepository
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchResult
import com.haruhi.haruhibox.feature.jm.data.ReadingPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// ======================================================================
// 书库
// ======================================================================

/** 书库的筛选条件。`null` 表示"全部"。 */
data class LibraryFilter(
    val keyword: String = "",
    val downloadedOnly: Boolean? = null,
    val collectedOnly: Boolean? = null,
    val favoriteId: Long? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
) {
    val isActive: Boolean
        get() = keyword.isNotBlank() || downloadedOnly != null || collectedOnly != null ||
            favoriteId != null || tags.isNotEmpty() || author != null

    /** 生效条件数量，UI 用来显示"已筛选 N 项"。 */
    val activeCount: Int
        get() = listOf(
            keyword.isNotBlank(),
            downloadedOnly != null,
            collectedOnly != null,
            favoriteId != null,
            tags.isNotEmpty(),
            author != null,
        ).count { it }
}

data class LibraryUiState(
    val albums: List<AlbumCard> = emptyList(),
    val total: Int = 0,
    val filter: LibraryFilter = LibraryFilter(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** 还有没有下一页（连续加载用）。 */
    val hasMore: Boolean = false,
    /**
     * 「继续阅读」卡片（计划 §9 第 4 条：**有阅读记录时**显示，没有就不显示）。
     */
    val continueReading: ContinueReadingCard? = null,
    /** 批量"更改收藏夹"要用的收藏夹列表。 */
    val favorites: List<JmFavoriteEntity> = emptyList(),
    /** 批量操作的一次性提示。 */
    val message: String? = null,
)

/** 书库顶部"继续阅读"卡片的数据。 */
data class ContinueReadingCard(
    val albumId: Long,
    val albumTitle: String,
    val chapterTitle: String,
    /** 位置是否已经落到某一页（只有章节号时说明刚进过这一话）。 */
    val hasPage: Boolean,
) {
    val subtitle: String
        get() = if (hasPage) "继续阅读 · $chapterTitle" else "开始阅读 · $chapterTitle"
}

/** 书库卡片。`downloadedChapters`/`totalChapters` 用于"状态优先展示下载章节数"。 */
data class AlbumCard(
    val album: JmAlbumEntity,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val localSizeBytes: Long,
) {
    val displayName: String get() = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
}

class JmLibraryViewModel(
    private val repo: JmRepository,
    private val reading: JmReadingRepository,
    private val cleaner: JmAlbumCleaner,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val paging = MutableStateFlow(PagingState())
    private val extra = MutableStateFlow(ExtraState())

    private data class PagingState(val loadingMore: Boolean = false, val error: String? = null, val page: Int = 0)

    private data class ExtraState(
        val summaries: Map<Long, ChapterStat> = emptyMap(),
        val loadingStat: Boolean = false,
        val continueReading: ContinueReadingCard? = null,
        val favorites: List<JmFavoriteEntity> = emptyList(),
        /** 批量操作的一次性提示。 */
        val message: String? = null,
    )

    private data class ChapterStat(val total: Int, val downloaded: Int, val size: Long)

    val uiState: StateFlow<LibraryUiState> = combine(
        filter.flatMapLatest { f ->
            repo.observeLibrary(
                keyword = f.keyword.takeIf { it.isNotBlank() },
                collected = f.collectedOnly,
                favoriteId = f.favoriteId,
                tags = f.tags,
                author = f.author,
                limit = PAGE_SIZE,
                offset = 0,
            )
        },
        filter.flatMapLatest { f ->
            repo.observeLibraryCount(
                keyword = f.keyword.takeIf { it.isNotBlank() },
                collected = f.collectedOnly,
                favoriteId = f.favoriteId,
                tags = f.tags,
                author = f.author,
            )
        },
        filter,
        paging,
        extra,
    ) { albums, total, f, p, e ->
        val cards = albums.map { album ->
            val stat = e.summaries[album.id]
            AlbumCard(
                album = album,
                totalChapters = stat?.total ?: 0,
                downloadedChapters = stat?.downloaded ?: 0,
                localSizeBytes = stat?.size ?: 0L,
            )
        }.let { list ->
            // "已下载 / 未下载"筛选要在拿到章节统计后再过滤
            when (f.downloadedOnly) {
                true -> list.filter { it.downloadedChapters > 0 }
                false -> list.filter { it.downloadedChapters == 0 }
                null -> list
            }
        }
        LibraryUiState(
            albums = cards,
            total = total,
            filter = f,
            loading = e.loadingStat && e.summaries.isEmpty(),
            loadingMore = p.loadingMore,
            error = p.error,
            hasMore = albums.size >= PAGE_SIZE,
            continueReading = e.continueReading,
            favorites = e.favorites,
            message = e.message,
        )
    }.catch { e ->
        Timber.tag("jm").w(e, "书库查询失败")
        emit(LibraryUiState(error = e.toUserMessage(), loading = false))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    init {
        refreshStats()
        // 「继续阅读」跟着阅读位置走：读完一本回到书库，卡片要立刻变成那一本
        viewModelScope.launch {
            reading.observeLastRead()
                .catch { e -> Timber.tag("jm").w(e, "观察阅读位置失败") }
                .collect { position ->
                    extra.update { it.copy(continueReading = resolveContinueReading(position)) }
                }
        }
        // 批量"更改收藏夹"要用收藏夹列表，跟着数据库走
        viewModelScope.launch {
            repo.observeFavorites()
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹失败") }
                .collect { favorites -> extra.update { it.copy(favorites = favorites) } }
        }
    }

    /**
     * 把阅读位置翻译成卡片内容。
     *
     * 位置可能指向一本**已经不在书库**的本子（用户移除了记录但没删文件），
     * 这时退回最近浏览表取标题；两处都没有就干脆不显示卡片。
     */
    private suspend fun resolveContinueReading(position: ReadingPosition?): ContinueReadingCard? {
        val chapterId = position?.chapterId ?: return null
        val album = runCatching { repo.findAlbum(position.albumId) }.getOrNull()
            ?: runCatching { reading.albumForDisplay(position.albumId)?.toAlbumEntityForDisplay() }.getOrNull()
            ?: return null
        val chapterTitle = runCatching { reading.chapterFolderName(position.albumId, chapterId) }
            .getOrNull()
            ?: chapterId.toString()
        return ContinueReadingCard(
            albumId = position.albumId,
            albumTitle = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}",
            chapterTitle = chapterTitle,
            hasPage = !position.imageFile.isNullOrBlank(),
        )
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) {
        filter.update(transform)
        // 筛选变化后章节统计要跟着重算（下载筛选用得到）
        refreshStats()
    }

    fun clearFilter() = updateFilter { LibraryFilter() }

    /** 拉取每个本子的章节数 / 已下载章节数 / 占用。列表不大（分页 60 条）时够用。 */
    fun refreshStats() {
        viewModelScope.launch {
            extra.update { it.copy(loadingStat = true) }
            runCatching {
                val albums = repo.observeLibrary(
                    keyword = filter.value.keyword.takeIf { it.isNotBlank() },
                    collected = filter.value.collectedOnly,
                    favoriteId = filter.value.favoriteId,
                    tags = filter.value.tags,
                    author = filter.value.author,
                    limit = PAGE_SIZE,
                    offset = 0,
                ).first()
                val stats = albums.associate { album ->
                    album.id to buildStat(album)
                }
                // 用 update 而不是整体替换：continueReading 由另一条流维护，不能被统计覆盖掉
                extra.update { it.copy(summaries = stats, loadingStat = false) }
            }.onFailure { e ->
                Timber.tag("jm").w(e, "统计章节与占用失败")
                extra.update { it.copy(loadingStat = false) }
            }
        }
    }

    /**
     * 回到书库时刷新统计。
     *
     * 下载完成后章节数会变，"已下载 x/y 话"必须跟着变（否则用户会以为没下成功）。
     */
    fun onScreenResumed() = refreshStats()

    private suspend fun buildStat(album: JmAlbumEntity): ChapterStat = withContext(Dispatchers.IO) {
        val chapters = repo.chaptersOf(album)
        var downloaded = 0
        var totalImages = 0
        chapters.forEach { chapter ->
            val images = repo.imagesOf(album.id, chapter.chapterId)
            totalImages += images.size
            if (images.isNotEmpty()) {
                val folder = repo.chapterFolderName(chapter)
                val have = repo.downloadedImageCount(
                    album.albumFolderName,
                    folder,
                    images.map { it.imageFile },
                )
                if (have >= images.size) downloaded++
            }
        }
        // 没有任何章节记录时，用 album.images 的数量近似"总章节数"是不可靠的；
        // 这里只在有章节数据时给出统计（0 表示"未刷新过详情"）。
        ChapterStat(
            total = chapters.size,
            downloaded = downloaded,
            size = repo.localSize(album.albumFolderName),
        )
    }

    // ------------------------------------------------------------------
    // 多选批量操作（用户要求第 1、2 点）
    // ------------------------------------------------------------------

    /** 批量删除本地文件：图片 + 导出成品，**不动记录与封面**。 */
    fun deleteFiles(ids: List<Long>) {
        viewModelScope.launch {
            val albums = albumsByIds(ids)
            runCatching { cleaner.deleteFiles(albums) }
                .onSuccess { result ->
                    postMessage(
                        buildString {
                            append("已删除 ${albums.size} 本的本地文件")
                            if (result.removedExports > 0) append("（含 ${result.removedExports} 个导出文件）")
                        },
                    )
                }
                .onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    /** 批量从书库移除：记录 + 图片 + 成品 + 封面全清。 */
    fun removeFromLibrary(ids: List<Long>) {
        viewModelScope.launch {
            val albums = albumsByIds(ids)
            runCatching { cleaner.removeFromLibrary(albums) }
                .onSuccess { result ->
                    postMessage(
                        buildString {
                            append("已移除 ${albums.size} 本")
                            if (result.freedBytes > 0) append("，释放 ${formatBytes(result.freedBytes)}")
                        },
                    )
                }
                .onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    /** 批量更改收藏夹：从原夹移出、加入目标夹（"移动"而不是"多属于一个"）。 */
    fun moveToFavorite(ids: List<Long>, favoriteId: Long) {
        viewModelScope.launch {
            runCatching {
                ids.forEach { id ->
                    repo.favoriteIdsOf(id).forEach { old ->
                        if (old != favoriteId) repo.removeFromFavorite(id, old)
                    }
                    repo.addToFavorite(id, favoriteId)
                }
            }.onSuccess {
                val name = uiState.value.favorites.firstOrNull { it.id == favoriteId }?.name
                postMessage(if (name != null) "已移动到「$name」" else "已移动收藏夹")
            }.onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    fun consumeMessage() = extra.update { it.copy(message = null) }

    private fun postMessage(text: String) = extra.update { it.copy(message = text) }

    private suspend fun albumsByIds(ids: List<Long>): List<JmAlbumEntity> =
        ids.mapNotNull { runCatching { repo.findAlbum(it) }.getOrNull() }

    fun setCollected(albumId: Long, collected: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (collected) repo.addToFavorite(albumId) else repo.uncollect(albumId)
            }.onFailure { Timber.tag("jm").w(it, "修改收藏状态失败") }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
    }
}

// ======================================================================
// 在线搜索（发现）
// ======================================================================

data class OnlineSearchUiState(
    val keyword: String = "",
    val sort: JmSearchSort = JmSearchSort.DEFAULT,
    val result: OnlineSearchResult? = null,
    val history: List<JmSearchHistoryEntity> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** 一次性提示（例如"已加入书库"）。 */
    val message: String? = null,
    /** 历史未开启时提示"搜索历史已关闭"。 */
    val historyEnabled: Boolean = true,
    /**
     * **本次结果在 UI 上已展示的条数**。
     *
     * 用户反馈第 5 点：接口一页返回 80 条，一次性全部渲染会同时发起 80 个封面请求。
     * 所以首屏只渲染 [INITIAL_VISIBLE_COUNT] 条，滚动到底部时追加一屏；
     * 本页 80 条全部展示完之后，才显示"加载下一页"按钮。
     */
    val visibleCount: Int = INITIAL_VISIBLE_COUNT,
) {
    /** 本次结果里还剩多少条没展示。 */
    val remainingCount: Int
        get() = ((result?.items?.size ?: 0) - visibleCount).coerceAtLeast(0)

    /** 本页是否已经全部展示完（决定"加载下一页"按钮是否出现）。 */
    val allVisible: Boolean get() = remainingCount == 0

    /**
     * 滚动到某个位置时是否应该继续追加。
     *
     * 抽成纯函数是为了能单测——Compose 里的滚动触发在这台设备上没法自动验证，
     * 但"判定条件对不对"是可以测的（[shouldRevealMore] 的边界）。
     *
     * @param lastVisibleIndex `listState.layoutInfo` 里最后一个可见项的 index；
     *        列表还没有布局时传 -1
     */
    fun shouldRevealMore(lastVisibleIndex: Int): Boolean =
        remainingCount > 0 && lastVisibleIndex >= visibleItemsCount - REVEAL_AHEAD

    /** 当前批次的条数。 */
    val visibleItemsCount: Int get() = minOf(visibleCount, result?.items?.size ?: 0)

    companion object {
        /** 首屏渲染条数 / 每次追加条数。 */
        const val INITIAL_VISIBLE_COUNT = 10

        /** 距离底部还有几条时就开始追加，滚动更顺。 */
        const val REVEAL_AHEAD = 2
    }
}

class JmOnlineSearchViewModel(private val repo: JmRepository) : ViewModel() {

    private val _state = MutableStateFlow(OnlineSearchUiState())
    val state: StateFlow<OnlineSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repo.observeSearchHistory().collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
    }

    fun onKeywordChange(value: String) = _state.update { it.copy(keyword = value) }

    fun onSortChange(sort: JmSearchSort) {
        _state.update { it.copy(sort = sort) }
        if (_state.value.keyword.isNotBlank()) search()
    }

    /** 从第一页开始搜索。 */
    fun search() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(error = "请输入搜索关键字") }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    error = null,
                    result = null,
                    message = null,
                    visibleCount = OnlineSearchUiState.INITIAL_VISIBLE_COUNT,
                )
            }
            // 纯数字关键词：同时查搜索与详情，把详情包装成第一条（用户要求第 3 点）
            runCatching { repo.searchOnlineByExactId(keyword, _state.value.sort, page = 1) }
                .onSuccess { result -> _state.update { it.copy(result = result, searching = false) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(searching = false, error = e.toUserMessage()) }
                }
        }
    }

    /**
     * 追加展示一屏（**不发网络请求**）。
     *
     * 与 [loadMore] 的区别：这个是"把本页已拿到的结果多渲染一些"，
     * 用户反馈第 5 点要求的"下滑继续追加显示"。
     */
    fun showMoreItems() {
        _state.update {
            if (it.remainingCount <= 0) {
                it
            } else {
                it.copy(visibleCount = it.visibleCount + OnlineSearchUiState.INITIAL_VISIBLE_COUNT)
            }
        }
    }

    /**
     * 加载下一页（**发网络请求**）。
     *
     * UI 上只在本页全部展示完之后才出现按钮（用户反馈第 5 点）。
     */
    fun loadMore() {
        val current = _state.value.result ?: return
        if (_state.value.loadingMore || current.page >= current.totalPages) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            runCatching { repo.searchOnline(current.query, current.sort, page = current.page + 1) }
                .onSuccess { next ->
                    _state.update { s ->
                        val merged = s.result?.copy(
                            page = next.page,
                            items = s.result.items + next.items.filterNot { new ->
                                s.result.items.any { it.id == new.id }
                            },
                        ) ?: next
                        s.copy(result = merged, loadingMore = false)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(loadingMore = false, error = e.toUserMessage()) }
                }
        }
    }

    /** 点历史记录：直接回显快照，不再请求 JM。 */
    fun openHistory(history: JmSearchHistoryEntity) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    error = null,
                    keyword = history.keyword,
                    // 换了一批结果，展示条数从头开始
                    visibleCount = OnlineSearchUiState.INITIAL_VISIBLE_COUNT,
                )
            }
            val result = repo.searchHistorySnapshot(history.id)
            _state.update {
                it.copy(
                    searching = false,
                    result = result,
                    error = if (result == null) "这条搜索记录的结果已丢失，请重新搜索" else null,
                    sort = JmSearchSort.fromCode(history.sort),
                )
            }
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { runCatching { repo.deleteSearchHistory(id) } }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { repo.clearSearchHistory() } }
    }

    /** 加入书库（不下载）。已入库时是幂等操作，UI 只提示不报错。 */
    fun addToLibrary(item: OnlineSearchItem) {
        viewModelScope.launch {
            runCatching { repo.addToLibrary(item.id) }
                .onSuccess {
                    markLocal(item.id)
                    _state.update { it.copy(message = "已加入书库") }
                }
                .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun markLocal(albumId: Long) {
        _state.update { s ->
            val result = s.result ?: return@update s
            s.copy(
                result = result.copy(
                    items = result.items.map {
                        if (it.id == albumId) it.copy(existsLocal = true) else it
                    },
                ),
            )
        }
    }
}

// ======================================================================
// 收藏夹
// ======================================================================

/**
 * 收藏夹列表的排序方式（用户要求：三种，全部降序，默认"创建时间"）。
 *
 * 「默认收藏夹始终置顶」不在这里表达，而是由 [sortFavorites] 统一保证——
 * 排序方式换来换去，默认夹的位置都不动。
 */
enum class FavoriteSort(val label: String) {
    CREATED_DESC("创建时间"),
    ALBUM_COUNT_DESC("漫画数量"),
    LAST_ADDED_DESC("最近加入"),
    ;

    companion object {
        val DEFAULT = CREATED_DESC
    }
}

data class FavoritesUiState(
    val favorites: List<FavoriteRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val sort: FavoriteSort = FavoriteSort.DEFAULT,
) {
    data class FavoriteRow(
        val id: Long,
        val name: String,
        val albumCount: Int,
        val isDefault: Boolean,
        /** 创建时间（"创建时间"排序用）。 */
        val createTime: String? = null,
        /** 夹内最近一次加入漫画的时间；空夹为 null。 */
        val lastAddedTime: String? = null,
        /** 夹内最近加入的本子 id，列表拿它当封面；空夹为 null。 */
        val latestAlbumId: Long? = null,
    ) {
        val isEmpty: Boolean get() = albumCount == 0
    }
}

/**
 * 收藏夹列表的排序。
 *
 * 规则（用户要求）：
 * 1. **默认收藏夹永远置顶**，与排序方式无关；
 * 2. 其余按所选方式**降序**：创建时间 / 漫画数量 / 最近加入漫画的时间；
 * 3. 同值时按 id 兜底，保证顺序稳定（不会因为重组而跳动）。
 *
 * 时间字段是 `yyyy-MM-dd HH:mm:ss` 文本，字典序即时间序；`null` 当空串处理，
 * 降序时自然排到最后（例如还没放过东西的夹子）。
 */
fun sortFavorites(
    rows: List<FavoritesUiState.FavoriteRow>,
    sort: FavoriteSort,
): List<FavoritesUiState.FavoriteRow> {
    val comparator = when (sort) {
        FavoriteSort.CREATED_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.createTime.orEmpty() }
        FavoriteSort.ALBUM_COUNT_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.albumCount }
        FavoriteSort.LAST_ADDED_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.lastAddedTime.orEmpty() }
    }
    return rows.sortedWith(
        compareByDescending<FavoritesUiState.FavoriteRow> { it.isDefault }
            .then(comparator)
            .thenBy { it.id },
    )
}

/**
 * 收藏夹列表。
 *
 * 数量与封面来自 [JmRepository.observeFavoritesWithCount] 的 **Flow**：
 * 之前用一次性查询，收藏之后回到收藏页数量不更新（用户反馈第 1 点）。
 * 改成 Flow 后，关联表任何变动（包括封面对应的"最近加入的那本"）都会自动重发。
 */
class JmFavoritesViewModel(private val repo: JmRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 默认收藏夹必须存在（新建/删除/改名都依赖它做兜底）
            runCatching { repo.ensureDefaultFavorite() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }

            repo.observeFavoritesWithCount()
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹失败") }
                .collect { list ->
                    _state.update { current ->
                        current.copy(
                            favorites = list.map {
                                FavoritesUiState.FavoriteRow(
                                    id = it.id,
                                    name = it.name,
                                    albumCount = it.albumCount,
                                    isDefault = it.isDefault,
                                    createTime = it.createTime,
                                    lastAddedTime = it.lastAddedTime,
                                    latestAlbumId = it.latestAlbumId,
                                )
                            },
                            loading = false,
                        )
                    }
                }
        }
    }

    /** 切换排序方式（只影响展示顺序，不写库）。 */
    fun setSort(sort: FavoriteSort) = _state.update { it.copy(sort = sort) }

    fun create(name: String) {
        viewModelScope.launch {
            val result = runCatching { repo.createFavorite(name) }
                .getOrDefault(FavoriteWriteResult.NameBlank)
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已创建「${name.trim()}」"
                        FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了"
                        FavoriteWriteResult.NameBlank -> "请输入收藏夹名称"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹已存在"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                    },
                )
            }
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            val result = runCatching { repo.renameFavorite(id, newName) }
                .getOrDefault(FavoriteWriteResult.NotFound)
            // 每种失败原因分开提示（用户反馈第 7 点：撞名被说成了"默认收藏夹不支持重命名"）
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已重命名为「${newName.trim()}」"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹不支持重命名"
                        FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了，换个名字"
                        FavoriteWriteResult.NameBlank -> "名称不能为空"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                    },
                )
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val result = runCatching { repo.deleteFavorite(id) }
                .getOrDefault(FavoriteWriteResult.NotFound)
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已删除收藏夹（书库记录与本地文件都保留）"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹不支持删除"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                        else -> "删除失败"
                    },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

// ======================================================================
// 最近浏览
// ======================================================================

data class RecentUiState(
    val albums: List<AlbumCard> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && albums.isEmpty()
}

/**
 * 最近浏览：按浏览时间倒序（用户要求）。
 *
 * 只展示、不统计章节：这张表的用途是"快速回到刚看过的本子"，
 * 每次进来都为 60 条记录查一次章节会明显拖慢页面。
 */
class JmRecentViewModel(private val repo: JmRepository) : ViewModel() {

    val state: StateFlow<RecentUiState> = repo.observeRecentAlbums()
        .map { rows ->
            RecentUiState(
                albums = rows.map { row ->
                    AlbumCard(
                        album = row.toAlbumEntityForDisplay(),
                        totalChapters = 0,
                        downloadedChapters = 0,
                        localSizeBytes = 0L,
                    )
                },
                loading = false,
            )
        }
        .catch { e ->
            Timber.tag("jm").w(e, "最近浏览查询失败")
            emit(RecentUiState(loading = false, error = e.toUserMessage()))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecentUiState(),
        )

    fun remove(albumId: Long) {
        viewModelScope.launch { runCatching { repo.removeRecent(albumId) } }
    }

    fun clearAll() {
        viewModelScope.launch { runCatching { repo.clearRecent() } }
    }
}

// ======================================================================
// 漫画详情
// ======================================================================

data class ChapterRow(
    val chapterId: Long,
    val title: String,
    val sortIndex: Int,
    val totalImages: Int,
    val downloadedImages: Int,
) {
    val isDownloaded: Boolean get() = totalImages > 0 && downloadedImages >= totalImages
    val isPartial: Boolean get() = downloadedImages in 1 until totalImages
    val isMissing: Boolean get() = downloadedImages == 0

    /** "已下载 / 部分下载 12/45 / 未下载"（计划 §9 第 7 条）。 */
    val statusText: String
        get() = when {
            isDownloaded -> "已下载"
            isPartial -> "部分下载 $downloadedImages/$totalImages"
            else -> "未下载"
        }
}

data class AlbumDetailUiState(
    val album: JmAlbumEntity? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val localSizeBytes: Long = 0,
    val favoriteIds: List<Long> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /**
     * 数据来源：true = 已在书库；false = 只在最近浏览里（或刚从网络拿到）。
     * 决定详情页显示「加入书库」还是「已在书库」。
     */
    val inLibrary: Boolean = false,
    /** 离线/刷新失败时的提示；**不清空本地数据**（计划 §9 第 7 条）。 */
    val refreshError: String? = null,
    val message: String? = null,
    /** 章节倒序（正序/倒序切换）。 */
    val reversed: Boolean = false,
    val descriptionExpanded: Boolean = false,
    val allFavorites: List<JmFavoriteEntity> = emptyList(),
    /**
     * **已经在下载队列里**的章节 id（用户要求第 3 点）。
     *
     * 取这个本子当前活跃任务的章节明细：排队、下载中、暂停、等待网络、失败的都算
     * ——"已经在队列里"本身就是不让重复提交的理由，与它跑到哪一步无关。
     */
    val queuedChapterIds: Set<Long> = emptySet(),

    // ---------------- 导出（P4） ----------------

    /** 用户点了哪个导出（决定密码框的标题与默认密码）。 */
    val exportFormat: JmExportFormat? = null,
    /** 该格式的默认密码（来自设置/凭据存储），密码框预填它。 */
    val exportDefaultPassword: String = "",
    /**
     * 目标文件（系统下载目录里的同名 zip/pdf）**已经存在**。
     *
     * 再次生成会直接覆盖，所以在密码框里用醒目颜色提醒一句（用户要求第 5 点）。
     */
    val exportTargetExists: Boolean = false,
    /** 缺图预检结果；非 null 时展示"缺图"对话框而不是密码框。 */
    val exportPrecheck: JmExportManager.Precheck? = null,

    /**
     * 「从书库移除」已经**删干净**了，页面可以退出了。
     *
     * 删除是异步的（图片目录 + 下载目录里的成品 + 封面缓存 + 五张表），
     * 点完立刻返回会把还在跑的协程一起取消，所以由这个标志通知上层再 pop。
     */
    val removedFromLibrary: Boolean = false,
) {
    /**
     * 下载面板的默认勾选：**没下完、也不在队列里**的章节。
     *
     * 已下载的与已在队列里的都不默认勾选，避免用户点一下就把重复的活又排一遍。
     */
    val defaultDownloadSelection: Set<Long>
        get() = chapters
            .filter { !it.isDownloaded && it.chapterId !in queuedChapterIds }
            .map { it.chapterId }
            .toSet()
}

/**
 * 漫画详情。
 *
 * ## 数据读取顺序（用户要求第 4 点）
 *
 * 1. **先读本地**：书库表 → 最近浏览表；任一命中就直接渲染，不发网络请求；
 * 2. 都没有才请求 `/album`，成功后写入最近浏览表（[JmRepository.fetchAlbumDetail]）；
 * 3. 「刷新」按钮：请求成功后**更新书库记录（若已入库）与最近浏览记录，但不改浏览时间**。
 *
 * ## 浏览时间（用户要求第 3、4 点）
 *
 * 进入详情页时调 [JmRepository.touchRecent]，**无论从搜索、书库还是收藏夹进来**都走这里，
 * 所以三种入口的浏览时间都会更新；刷新不会再动它。
 *
 * ## 点详情 ≠ 加入书库（用户要求第 2 点）
 *
 * 构造时只 [JmRepository.touchRecent]，不写 `t_jm_album`。
 * 入书库只发生在用户点「加入书库」时。
 */
class JmAlbumDetailViewModel(
    private val repo: JmRepository,
    private val downloads: JmDownloadManager,
    private val exports: JmExportManager,
    private val tasks: DownloadTaskRepository,
    private val secrets: SecretStore,
    private val cleaner: JmAlbumCleaner,
    private val albumId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    init {
        // 1) 记一次浏览（三种入口共用这一条路径）
        viewModelScope.launch {
            runCatching { repo.touchRecent(albumId) }
                .onFailure { Timber.tag("jm").w(it, "记录最近浏览失败") }
        }

        // 2) 本地优先：有本地数据就先渲染
        viewModelScope.launch {
            val local = runCatching { repo.localAlbumDetail(albumId) }.getOrNull()
            if (local == null) {
                // 本地没有 -> 必须联网
                loadFromNetwork(initial = true)
            } else {
                _state.update {
                    it.copy(album = local.album, inLibrary = local.fromLibrary, loading = false)
                }
                refreshLocalStats()
            }
        }

        viewModelScope.launch {
            repo.observeFavorites().collect { favorites ->
                _state.update { it.copy(allFavorites = favorites) }
            }
        }

        // 3) 盯着这个本子的活跃任务：面板里要把"已在队列"的章节标出来且不可选，
        //    下载完成后也要立刻把状态清掉（用户要求第 3 点）
        viewModelScope.launch {
            tasks.observeQueuedChapters(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察下载队列失败") }
                .collect { queued ->
                    _state.update { it.copy(queuedChapterIds = queued) }
                }
        }

        // 4) 收藏状态跟着数据库走（用户反馈第 1、3 点）：
        //    "在收藏夹面板里全部移出"或"在阅读器里点了收藏"之后回到详情页，
        //    必须立刻看到正确的按钮状态。之前只在页面加载时读一次，所以看不到变化。
        viewModelScope.launch {
            repo.observeAlbum(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察本子失败") }
                .collect { album ->
                    if (album != null) {
                        _state.update { it.copy(album = album, inLibrary = true) }
                    }
                }
        }
        viewModelScope.launch {
            repo.observeFavoriteIdsOf(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹关联失败") }
                .collect { ids ->
                    _state.update { it.copy(favoriteIds = ids) }
                }
        }
    }

    /**
     * 刷新详情。
     *
     * 与"进入页面"的区别：这里**不改浏览时间**（[JmRepository.saveToRecent] 保留原值）。
     */
    fun refresh() {
        viewModelScope.launch { loadFromNetwork(initial = false) }
    }

    private suspend fun loadFromNetwork(initial: Boolean) {
        val inLibrary = _state.value.inLibrary || runCatching { repo.findAlbum(albumId) }.getOrNull() != null
        _state.update {
            it.copy(refreshing = true, refreshError = null, loading = initial && it.album == null)
        }
        runCatching {
            // 已在书库就一并更新书库记录；只算最近浏览的话不写书库
            repo.fetchAlbumDetail(albumId, saveToLibrary = inLibrary)
        }
            .onSuccess { dto ->
                val album = if (inLibrary) {
                    repo.findAlbum(albumId)
                } else {
                    // 未入库：用最近浏览里的副本渲染（字段一致）
                    repo.findRecent(albumId)?.toAlbumEntityForDisplay()
                }
                _state.update {
                    it.copy(refreshing = false, loading = false, album = album ?: it.album, inLibrary = inLibrary)
                }
                refreshLocalStats()
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        refreshing = false,
                        loading = false,
                        // 离线时不阻断本地阅读，只提示（计划 §9 第 7 条）
                        refreshError = e.toUserMessage(),
                    )
                }
            }
    }

    /** 加入书库（用户要求第 2 点：只有点这里才真正入书库）。 */
    fun addToLibrary() {
        viewModelScope.launch {
            runCatching { ensureInLibrary() }
                .onSuccess { album ->
                    _state.update { it.copy(album = album, inLibrary = true, message = "已加入书库") }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    /**
     * 确保本子已在书库，返回库里的实体。
     *
     * **不请求网络**：详情页手里已经有完整数据（本地副本或刚拉到的在线数据），
     * 直接落库即可。之前这里调 `repo.addToLibrary(albumId)` 会再拉一次 `/album`，
     * 于是"从未收藏过的漫画点收藏"要等一次网络往返，看起来卡一下；
     * 取消再收藏时本地已有记录就直接落库，所以很快（用户反馈的正是这个现象）。
     */
    private suspend fun ensureInLibrary(): JmAlbumEntity {
        val current = _state.value.album
        val library = if (current != null) {
            repo.addToLibrary(current)
        } else {
            repo.addToLibrary(albumId)
        }
        _state.update { it.copy(album = library, inLibrary = true) }
        return library
    }

    /** 回到这个页面时重算本地章节状态（下载完成后数字必须跟着变）。 */
    fun refreshLocalStats() {
        viewModelScope.launch { loadLocalStats() }
    }

    /**
     * 重算章节状态与本地占用。
     *
     * **必须切到 IO**：`downloadedImageCount` 要逐张看文件在不在，`localSize` 要递归遍历
     * 整个本子目录。放在主线程就是"进详情页卡一下"（用户反馈第 9 点）。
     */
    private suspend fun loadLocalStats() {
        val album = _state.value.album ?: return
        val (rows, localSize, favoriteIds) = withContext(Dispatchers.IO) {
            val chapters = repo.chaptersOf(album)
            val rows = chapters.sortedBy { it.sort?.toIntOrNull() ?: Int.MAX_VALUE }.map { chapter ->
                val images = repo.imagesOf(album.id, chapter.chapterId)
                val folder = repo.chapterFolderName(chapter)
                val have = repo.downloadedImageCount(
                    album.albumFolderName,
                    folder,
                    images.map { it.imageFile },
                )
                ChapterRow(
                    chapterId = chapter.chapterId,
                    title = chapter.title,
                    sortIndex = chapter.sort?.toIntOrNull() ?: 0,
                    totalImages = images.size,
                    downloadedImages = have,
                )
            }
            Triple(rows, repo.localSize(album.albumFolderName), repo.favoriteIdsOf(albumId))
        }
        _state.update {
            it.copy(
                chapters = if (it.reversed) rows.reversed() else rows,
                totalChapters = rows.size,
                downloadedChapters = rows.count { row -> row.isDownloaded },
                localSizeBytes = localSize,
                favoriteIds = favoriteIds,
            )
        }
    }

    fun toggleReversed() {
        _state.update { it.copy(reversed = !it.reversed, chapters = it.chapters.reversed()) }
    }

    fun toggleDescription() = _state.update { it.copy(descriptionExpanded = !it.descriptionExpanded) }

    /** 收藏/取消收藏。未入库时会先入库（收藏隐含入库，参考实现的 `collectAlbums` 语义）。 */
    fun toggleCollected() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val nowCollected = album.collected == true
            runCatching {
                if (nowCollected) {
                    repo.uncollect(albumId)
                } else {
                    if (!_state.value.inLibrary) ensureInLibrary()
                    repo.addToFavorite(albumId)
                }
            }.onSuccess {
                val refreshed = repo.findAlbum(albumId)
                _state.update {
                    it.copy(
                        album = refreshed ?: it.album,
                        inLibrary = refreshed != null,
                        message = if (nowCollected) "已取消收藏" else "已加入收藏",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    /** 移动到指定收藏夹（用户要求第 6 点：支持修改收藏夹）。 */
    fun moveToFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching {
                if (!_state.value.inLibrary) ensureInLibrary()
                // 先移出其它夹再入目标夹，语义是"移动到"而不是"同时属于多个"
                repo.favoriteIdsOf(albumId).forEach { old ->
                    if (old != favoriteId) repo.removeFromFavorite(albumId, old)
                }
                repo.addToFavorite(albumId, favoriteId)
            }.onSuccess {
                val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                _state.update {
                    it.copy(
                        album = repo.findAlbum(albumId) ?: it.album,
                        inLibrary = true,
                        message = if (name != null) "已移动到「$name」" else "已移动收藏夹",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun addToFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching {
                if (!_state.value.inLibrary) ensureInLibrary()
                repo.addToFavorite(albumId, favoriteId)
            }.onSuccess {
                val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                _state.update {
                    it.copy(
                        album = repo.findAlbum(albumId) ?: it.album,
                        inLibrary = true,
                        message = if (name != null) "已加入「$name」" else "已加入收藏夹",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun removeFromFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeFromFavorite(albumId, favoriteId) }
                .onSuccess {
                    val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                    _state.update {
                        it.copy(message = if (name != null) "已移出「$name」" else "已移出收藏夹")
                    }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun createFavoriteAndAdd(name: String) {
        viewModelScope.launch {
            val result = runCatching { repo.createFavorite(name) }
                .getOrDefault(FavoriteWriteResult.NameBlank)
            if (result != FavoriteWriteResult.Ok) {
                _state.update {
                    it.copy(
                        message = when (result) {
                            FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了"
                            FavoriteWriteResult.NameBlank -> "请输入收藏夹名称"
                            else -> "创建收藏夹失败"
                        },
                    )
                }
                return@launch
            }
            val created = repo.listFavorites().firstOrNull { it.name == name.trim() } ?: return@launch
            if (!_state.value.inLibrary) runCatching { ensureInLibrary() }
            repo.addToFavorite(albumId, created.id)
            _state.update {
                it.copy(album = repo.findAlbum(albumId) ?: it.album, inLibrary = true)
            }
            refreshLocalStats()
        }
    }

    /**
     * 下载选中的章节（计划 §9 第 7 条）。
     *
     * 下载的前置是"本子有书库记录"——目录名由书库记录决定，所以这里先确保入库，
     * 用户不必先点一次「加入书库」再回来下载。
     *
     * 已在队列里的章节会被过滤掉：面板里已经不让选，这里再兜一次，
     * 避免别处（例如阅读器的"补下缺图"）误传进来。
     */
    fun downloadChapters(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) {
            _state.update { it.copy(message = "请先选择要下载的章节") }
            return
        }
        viewModelScope.launch {
            val current = _state.value
            val usable = chapterIds.filterNot { it in current.queuedChapterIds }
            val selected = current.chapters.filter { it.chapterId in usable }
            if (selected.isEmpty()) {
                _state.update { it.copy(message = "选中的章节都已经在下载队列里了") }
                return@launch
            }
            runCatching {
                val album = ensureInLibrary()
                val summaries = repo.chaptersOf(album).filter { it.chapterId in usable }
                downloads.submit(album, summaries)
            }.onSuccess { result ->
                val text = when (result) {
                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入下载队列（${selected.size} 话），可在「任务」里查看进度"

                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有下载任务，已把选中的 ${selected.size} 话加进去"
                }
                _state.update { it.copy(message = text) }
                refreshLocalStats()
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    /** 删除某一话的本地文件（保留章节清单与任务记录）。 */
    fun deleteChapterFiles(chapterId: Long) {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val folder = album.albumFolderName
            if (folder.isNullOrBlank()) return@launch
            val chapter = _state.value.chapters.firstOrNull { it.chapterId == chapterId } ?: return@launch
            runCatching { repo.deleteChapterFiles(folder, chapter.title) }
                .onSuccess { freed ->
                    _state.update { it.copy(message = "已删除这一话的本地文件（${formatBytes(freed)}）") }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------
    // 导出（P4）
    // ------------------------------------------------------------------

    /**
     * 用户点了「导出 ZIP / PDF」。
     *
     * 先做**缺图预检**：缺图时不静默导出残缺内容（计划 §9 第 7 条），
     * 而是列出缺哪些话、缺多少张，让用户先去补下载。
     */
    fun startExport(format: JmExportFormat) {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val precheck = runCatching { exports.precheck(album) }
                .getOrElse { e ->
                    _state.update { it.copy(message = e.toUserMessage()) }
                    return@launch
                }
            if (!precheck.isComplete) {
                _state.update { it.copy(exportPrecheck = precheck) }
                return@launch
            }
            // 同名成品已存在时提醒"会覆盖"（用户要求第 5 点）
            val exists = runCatching { exports.outputExists(album, format) }.getOrDefault(false)
            _state.update {
                it.copy(
                    exportFormat = format,
                    exportDefaultPassword = defaultPasswordFor(format),
                    exportTargetExists = exists,
                )
            }
        }
    }

    /** 缺图对话框里点「先补下载」：把缺图的那些话排进下载队列。 */
    fun downloadMissingChapters() {
        val album = _state.value.album ?: return
        val missing = _state.value.exportPrecheck?.missingChapters?.map { it.title }.orEmpty()
        _state.update { it.copy(exportPrecheck = null) }
        viewModelScope.launch {
            val chapters = runCatching { repo.chaptersOf(album) }
                .getOrDefault(emptyList())
                .filter { it.title in missing }
            if (chapters.isEmpty()) return@launch
            runCatching {
                val library = ensureInLibrary()
                downloads.submit(library, chapters)
            }.onSuccess {
                _state.update { it.copy(message = "已把缺图的 ${chapters.size} 话加入下载队列") }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun dismissExportPrecheck() = _state.update { it.copy(exportPrecheck = null) }

    /** 密码框里点「确认」（密码留空等同「不设密码」）。 */
    fun confirmExport(password: String) {
        val format = _state.value.exportFormat ?: return
        _state.update { it.copy(exportFormat = null, exportTargetExists = false) }
        viewModelScope.launch {
            runCatching {
                val library = ensureInLibrary()
                exports.submit(library, format, password.trim().ifEmpty { null })
            }.onSuccess { result ->
                val text = when (result) {
                    is DownloadTaskRepository.SubmitResult.Created ->
                        "已开始导出 ${format.label}，可在「任务」里看进度"

                    is DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已经有一个在跑的 ${format.label} 导出"
                }
                _state.update { it.copy(message = text) }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    /** 密码框里点「不设密码」。 */
    fun exportWithoutPassword() = confirmExport("")

    fun cancelExport() = _state.update { it.copy(exportFormat = null, exportTargetExists = false) }

    /** 默认密码来自凭据存储；没设过就用参考实现的默认值。 */
    private fun defaultPasswordFor(format: JmExportFormat): String {
        val key = when (format) {
            JmExportFormat.ZIP -> SettingKeys.SECRET_JM_ZIP_PASSWORD
            JmExportFormat.PDF -> SettingKeys.SECRET_JM_PDF_PASSWORD
        }
        return secrets.get(key)?.takeIf { it.isNotEmpty() } ?: JmDefaults.DEFAULT_EXPORT_PASSWORD
    }

    /**
     * 「删除本地文件」：图片 + 导出成品 + 封面缓存，**保留书库记录**（用户要求第 6 点）。
     *
     * 导出成品放在系统下载目录，容易漏删，所以统一交给 [JmAlbumCleaner] 编排。
     */
    fun deleteAlbumFiles() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val result = runCatching { cleaner.deleteFiles(listOf(album)) }.getOrNull()
            refreshLocalStats()
            val text = if (result != null && result.removedExports > 0) {
                "已删除本地文件（含 ${result.removedExports} 个导出成品）"
            } else {
                "已删除本地文件"
            }
            _state.update { it.copy(message = text) }
        }
    }

    /**
     * 「从书库移除」：记录 + 文件 + 成品 + 封面全清（用户要求第 7 点）。
     *
     * 与「删除本地文件」的差别只在于**要不要连记录一起删**；
     * 最近浏览也会一起清掉——不然"移除了"却还在最近列表里，看着像没删掉。
     */
    fun removeFromLibrary() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val result = runCatching { cleaner.removeFromLibrary(listOf(album)) }.getOrNull()
            refreshLocalStats()
            val suffix = if (result != null && result.removedExports > 0) {
                "（含 ${result.removedExports} 个导出成品）"
            } else {
                ""
            }
            _state.update {
                it.copy(
                    inLibrary = false,
                    removedFromLibrary = true,
                    message = "已从书库移除，本地文件与浏览记录一起删掉了$suffix",
                )
            }
        }
    }
}

/**
 * 最近浏览记录 → 展示用的书库实体。
 *
 * 两张表字段一致，所以详情页与列表可以复用同一套渲染逻辑；
 * `collected` 在最近浏览里没有语义，留 null。
 */
internal fun JmRecentAlbumEntity.toAlbumEntityForDisplay() = JmAlbumEntity(
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
    collected = null,
    isAids = isAids,
    price = price,
    purchased = purchased,
    raw = raw,
    createTime = createTime,
    modifyTime = modifyTime,
)

/** 把异常翻译成可理解文案：实现已移到 `core.network`（下载执行器也要用），这里只做转发注释。 */
// 详见 core/network/UserMessage.kt

