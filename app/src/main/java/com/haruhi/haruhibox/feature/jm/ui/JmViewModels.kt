package com.haruhi.haruhibox.feature.jm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.feature.jm.data.JmRepository
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

/** 书库卡片。`downloadedChapters`/`totalChapters` 用于"状态优先展示下载章节数"。 */
data class AlbumCard(
    val album: JmAlbumEntity,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val localSizeBytes: Long,
) {
    val displayName: String get() = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
}

class JmLibraryViewModel(private val repo: JmRepository) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val paging = MutableStateFlow(PagingState())
    private val extra = MutableStateFlow(ExtraState())

    private data class PagingState(val loadingMore: Boolean = false, val error: String? = null, val page: Int = 0)

    private data class ExtraState(
        val summaries: Map<Long, ChapterStat> = emptyMap(),
        val loadingStat: Boolean = false,
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
                extra.value = ExtraState(summaries = stats, loadingStat = false)
            }.onFailure { e ->
                Timber.tag("jm").w(e, "统计章节与占用失败")
                extra.update { it.copy(loadingStat = false) }
            }
        }
    }

    private suspend fun buildStat(album: JmAlbumEntity): ChapterStat {
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
        return ChapterStat(
            total = chapters.size,
            downloaded = downloaded,
            size = repo.localSize(album.albumFolderName),
        )
    }

    fun deleteAlbums(ids: List<Long>) {
        viewModelScope.launch {
            runCatching { repo.deleteAlbums(ids) }
                .onFailure { Timber.tag("jm").w(it, "删除记录失败") }
            refreshStats()
        }
    }

    fun deleteAlbumFiles(album: JmAlbumEntity) {
        viewModelScope.launch {
            runCatching { repo.deleteAlbumFiles(album.albumFolderName) }
                .onFailure { Timber.tag("jm").w(it, "删除文件失败") }
            refreshStats()
        }
    }

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
    /** 历史未开启时提示"搜索历史已关闭"。 */
    val historyEnabled: Boolean = true,
)

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
            _state.update { it.copy(searching = true, error = null, result = null) }
            runCatching { repo.searchOnline(keyword, _state.value.sort, page = 1) }
                .onSuccess { result -> _state.update { it.copy(result = result, searching = false) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(searching = false, error = e.toUserMessage()) }
                }
        }
    }

    /**
     * 加载下一页。
     *
     * 计划 §9 第 4 条要求"分页在 UI 表现为连续加载及失败重试"，
     * 所以这里把新一页追加到已有结果上，失败只设置 error 不清空已有数据。
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
            _state.update { it.copy(searching = true, error = null, keyword = history.keyword) }
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

    /** 入库（不下载）。返回是否成功，供 UI 提示。 */
    fun addToLibrary(item: OnlineSearchItem) {
        viewModelScope.launch {
            runCatching { repo.fetchAndSaveAlbum(item.id) }
                .onSuccess { markLocal(item.id) }
                .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
        }
    }

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

data class FavoritesUiState(
    val favorites: List<FavoriteRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
) {
    data class FavoriteRow(
        val id: Long,
        val name: String,
        val albumCount: Int,
        val isDefault: Boolean,
    )
}

class JmFavoritesViewModel(private val repo: JmRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            runCatching {
                repo.ensureDefaultFavorite()
                repo.listFavoritesWithCount()
            }.onSuccess { list ->
                _state.value = FavoritesUiState(
                    favorites = list.map {
                        FavoritesUiState.FavoriteRow(
                            id = it.id,
                            name = it.name,
                            albumCount = it.albumCount,
                            isDefault = it.isDefault,
                        )
                    },
                    loading = false,
                )
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            val created = runCatching { repo.createFavorite(name) }.getOrNull()
            _state.update {
                it.copy(message = if (created == null) "收藏夹名称已存在或为空" else "已创建「${created.name}」")
            }
            reload()
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            val ok = runCatching { repo.renameFavorite(id, newName) }.getOrDefault(false)
            // 默认收藏夹不允许改名（参考实现语义）
            _state.update { it.copy(message = if (ok) "已重命名" else "默认收藏夹不支持重命名") }
            reload()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val ok = runCatching { repo.deleteFavorite(id) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) "已删除收藏夹（不影响书库记录与本地文件）" else "默认收藏夹不支持删除")
            }
            reload()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
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
    /** 离线/刷新失败时的提示；**不清空本地数据**（计划 §9 第 7 条）。 */
    val refreshError: String? = null,
    val message: String? = null,
    /** 章节倒序（正序/倒序切换）。 */
    val reversed: Boolean = false,
    val descriptionExpanded: Boolean = false,
    val allFavorites: List<JmFavoriteEntity> = emptyList(),
)

class JmAlbumDetailViewModel(
    private val repo: JmRepository,
    private val albumId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAlbum(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察本子失败") }
                .collect { album ->
                    _state.update { it.copy(album = album) }
                    if (album != null) refreshLocalStats()
                }
        }
        viewModelScope.launch {
            repo.observeFavorites().collect { favorites ->
                _state.update { it.copy(allFavorites = favorites) }
            }
        }
        refresh(initial = true)
    }

    /**
     * 在线刷新详情。
     *
     * **本地优先**：先展示已有的本地章节，联网只作为增强；
     * 失败时把原因放进 [AlbumDetailUiState.refreshError]，不丢本地数据。
     */
    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, refreshError = null, loading = initial && it.album == null) }
            runCatching { repo.fetchAndSaveAlbum(albumId) }
                .onSuccess { album ->
                    _state.update { it.copy(refreshing = false, loading = false, album = album) }
                    refreshLocalStats()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update {
                        it.copy(
                            refreshing = false,
                            loading = false,
                            // 离线时不阻断本地阅读，只提示
                            refreshError = e.toUserMessage(),
                        )
                    }
                }
        }
    }

    private suspend fun refreshLocalStats() {
        val album = repo.findAlbum(albumId) ?: return
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
        _state.update {
            it.copy(
                chapters = if (it.reversed) rows.reversed() else rows,
                totalChapters = rows.size,
                downloadedChapters = rows.count { row -> row.isDownloaded },
                localSizeBytes = repo.localSize(album.albumFolderName),
                favoriteIds = repo.favoriteIdsOf(albumId),
            )
        }
    }

    fun toggleReversed() {
        _state.update { it.copy(reversed = !it.reversed, chapters = it.chapters.reversed()) }
    }

    fun toggleDescription() = _state.update { it.copy(descriptionExpanded = !it.descriptionExpanded) }

    fun toggleCollected() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val nowCollected = album.collected == true
            runCatching {
                if (nowCollected) repo.uncollect(albumId) else repo.addToFavorite(albumId)
            }.onSuccess {
                _state.update { it.copy(message = if (nowCollected) "已取消收藏" else "已加入收藏") }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun addToFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching { repo.addToFavorite(albumId, favoriteId) }
                .onSuccess { refreshLocalStats() }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun removeFromFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeFromFavorite(albumId, favoriteId) }
                .onSuccess { refreshLocalStats() }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun createFavoriteAndAdd(name: String) {
        viewModelScope.launch {
            val created = runCatching { repo.createFavorite(name) }.getOrNull()
            if (created == null) {
                _state.update { it.copy(message = "收藏夹名称已存在或为空") }
                return@launch
            }
            repo.addToFavorite(albumId, created.id)
            refreshLocalStats()
        }
    }

    /** 入库章节图片清单（供阅读器判断缺图）。下载由 P3 的任务页驱动。 */
    fun ensureChapterImages(chapterId: Long) {
        viewModelScope.launch {
            runCatching { repo.fetchAndSaveChapter(albumId, chapterId) }
                .onSuccess { count ->
                    _state.update { it.copy(message = "已获取章节图片清单（$count 张）") }
                    refreshLocalStats()
                }
                .onFailure { e ->
                    _state.update { it.copy(message = e.toUserMessage()) }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun deleteAlbumFiles() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            runCatching { repo.deleteAlbumFiles(album.albumFolderName) }
            refreshLocalStats()
            _state.update { it.copy(message = "已删除本地文件") }
        }
    }

    fun deleteAlbumRecord() {
        viewModelScope.launch {
            runCatching { repo.deleteAlbums(listOf(albumId)) }
            _state.update { it.copy(message = "已删除书库记录") }
        }
    }
}

/** 把异常翻译成可理解文案（计划 §9.1「失败重试不清空已有数据」+ 错误要能看懂）。 */
fun Throwable.toUserMessage(): String = when (this) {
    is NetworkError.Timeout -> "网络超时，请检查网络后重试"
    is NetworkError.Unreachable -> "连不上服务器，请检查网络或代理设置"
    is NetworkError.Tls -> "HTTPS 证书校验失败，可能是线路被劫持"
    is NetworkError.HttpStatus -> "服务器返回 $code，稍后再试或更换线路"
    is NetworkError.Malformed -> "返回内容异常（$detail）"
    is NetworkError.Business -> message ?: "操作失败"
    is NetworkError.Cancelled -> "已取消"
    else -> message ?: javaClass.simpleName
}
