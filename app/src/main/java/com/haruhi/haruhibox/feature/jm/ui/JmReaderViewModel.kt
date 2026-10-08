package com.haruhi.haruhibox.feature.jm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.haruhibox.core.codec.ImageCodec
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.network.toUserMessage
import com.haruhi.haruhibox.core.settings.SettingDefaults
import com.haruhi.haruhibox.core.settings.SettingKeys
import com.haruhi.haruhibox.core.settings.SettingsRepository
import com.haruhi.haruhibox.feature.jm.data.ChapterSummary
import com.haruhi.haruhibox.feature.jm.data.JmDownloadManager
import com.haruhi.haruhibox.feature.jm.data.JmReadingRepository
import com.haruhi.haruhibox.feature.jm.data.JmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读器里的一页。
 *
 * [file] 为 null 表示**这一页在磁盘上不存在**：阅读器必须显示具体页码与重试，
 * 不能静默跳过（计划 §9 第 7/8 条："缺图显示具体页码及重试"、"不静默跳页"）。
 */
data class ReaderPage(
    val index: Int,
    val imageFile: String,
    val file: File?,
    /** 高/宽。缺图时用 [DEFAULT_ASPECT] 占位，保证长图占位稳定。 */
    val aspectRatio: Float,
) {
    val available: Boolean get() = file != null

    companion object {
        /** 缺图与还未测量时的占位比例（约 A4 竖版），避免布局跳动。 */
        const val DEFAULT_ASPECT = 1.4f
    }
}

data class ReaderUiState(
    val album: JmAlbumEntity? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val chapterIndex: Int = -1,
    val pages: List<ReaderPage> = emptyList(),
    /** 打开这一话时要跳到的页（来自上次的阅读位置）。 */
    val startPage: Int = 0,
    val startOffsetRatio: Float = 0f,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val controlsVisible: Boolean = true,
    val showChapterPicker: Boolean = false,
    val showSettings: Boolean = false,
) {
    val currentChapter: ChapterSummary? get() = chapters.getOrNull(chapterIndex)
    val chapterTitle: String get() = currentChapter?.title ?: "阅读"
    val hasPreviousChapter: Boolean get() = chapterIndex > 0
    val hasNextChapter: Boolean get() = chapterIndex in 0 until chapters.lastIndex
    val missingCount: Int get() = pages.count { !it.available }
    val allMissing: Boolean get() = pages.isNotEmpty() && pages.all { !it.available }

    /** 进度文案："3/24 页"，有缺图时把缺图数也说清楚。 */
    val progressLabel: String
        get() = buildString {
            append("${pages.size} 页")
            if (missingCount > 0) append(" · 缺 $missingCount 页")
        }
}

/**
 * 阅读器（计划 §9 第 8 条）。
 *
 * ## 位置持久化
 *
 * 用 `本子 + 章节 + 图片文件名 + 页内比例` 记录（见 [JmReadingRepository]）。
 * 页面每次翻页都会回报当前位置，这里**去抖 400ms** 后落库：
 * 长图连续滚动时每帧都写库既浪费又会让 Room 排队。
 *
 * ## 缺图
 *
 * 页面列表来自 `t_jm_chapter_image`（权威清单），**磁盘上没有的就显示为缺失页**，
 * 位置照常保留。提供 [retryPage] 重新读盘（刚下载完的图不用退出重进）。
 */
class JmReaderViewModel(
    private val repo: JmRepository,
    private val reading: JmReadingRepository,
    private val settings: SettingsRepository,
    private val downloads: JmDownloadManager,
    private val albumId: Long,
    private val startChapterId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 已经量过的图片比例，切章节来回时不必重读文件头。 */
    private val aspectCache = ConcurrentHashMap<String, Float>()

    private var loadJob: Job? = null
    private var positionJob: Job? = null

    init {
        viewModelScope.launch {
            settings.observeReaderPreferences().collect { prefs ->
                _state.update { it.copy(preferences = prefs) }
            }
        }
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        val detail = runCatching { repo.localAlbumDetail(albumId) }.getOrNull()
        val album = detail?.album
        if (album == null) {
            _state.update { it.copy(loading = false, error = "本地没有这本漫画的数据，请先联网打开详情页") }
            return
        }
        val chapters = runCatching { repo.chaptersOf(album) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            _state.update {
                it.copy(album = album, loading = false, error = "还没有章节数据，请在详情页刷新")
            }
            return
        }

        val position = runCatching { reading.positionOf(albumId) }.getOrNull()
        val index = when {
            startChapterId != null -> chapters.indexOfFirst { it.chapterId == startChapterId }
            position?.chapterId != null -> chapters.indexOfFirst { it.chapterId == position.chapterId }
            else -> -1
        }.let { if (it >= 0) it else 0 }

        _state.update { it.copy(album = album, chapters = chapters) }
        // 手动指定章节（从详情页点进来）时从头读；否则续读上次的位置
        openChapter(index, restorePosition = startChapterId == null)
    }

    /**
     * 打开某一话。
     *
     * @param restorePosition 是否把上次读到的页作为起始页；从章节列表手动切换时为 false
     */
    private fun openChapter(index: Int, restorePosition: Boolean) {
        val chapters = _state.value.chapters
        val chapter = chapters.getOrNull(index) ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, pages = emptyList()) }
            val album = _state.value.album ?: return@launch
            val position = if (restorePosition) {
                runCatching { reading.positionOf(albumId) }.getOrNull()
                    ?.takeIf { it.chapterId == chapter.chapterId }
            } else {
                null
            }

            val entities = runCatching { reading.imagesOf(albumId, chapter.chapterId) }
                .getOrDefault(emptyList())
            if (entities.isEmpty()) {
                _state.update {
                    it.copy(
                        chapterIndex = index,
                        pages = emptyList(),
                        loading = false,
                        // 未下载的章节要有明确出口，而不是空白页（§9 第 7 条）
                        message = "这一话还没有图片清单，可以先下载这一话",
                    )
                }
                return@launch
            }

            val available = runCatching { reading.readableImages(album, chapter.chapterId) }
                .getOrDefault(emptyList())
                .associateBy { it.name }
            val pages = withContext(Dispatchers.IO) {
                entities.mapIndexed { pageIndex, entity ->
                    val file = available[entity.imageFile]
                    ReaderPage(
                        index = pageIndex,
                        imageFile = entity.imageFile,
                        file = file,
                        aspectRatio = file?.let { aspectOf(it) } ?: ReaderPage.DEFAULT_ASPECT,
                    )
                }
            }

            val startPage = position?.imageFile
                ?.let { name -> pages.indexOfFirst { it.imageFile == name } }
                ?.takeIf { it >= 0 }
                ?: position?.imageIndex?.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                ?: 0

            _state.update {
                it.copy(
                    chapterIndex = index,
                    pages = pages,
                    startPage = if (restorePosition) startPage else 0,
                    startOffsetRatio = if (restorePosition) position?.offsetRatio ?: 0f else 0f,
                    loading = false,
                    error = null,
                )
            }
        }
    }

    private fun aspectOf(file: File): Float = aspectCache.getOrPut(file.name + file.length()) {
        runCatching {
            val meta = ImageCodec.readMeta(file)
            if (meta.width > 0 && meta.height > 0) {
                meta.height.toFloat() / meta.width.toFloat()
            } else {
                ReaderPage.DEFAULT_ASPECT
            }
        }.getOrDefault(ReaderPage.DEFAULT_ASPECT)
    }

    // ------------------------------------------------------------------
    // 导航
    // ------------------------------------------------------------------

    fun openChapterAt(index: Int) {
        _state.update { it.copy(showChapterPicker = false) }
        openChapter(index, restorePosition = false)
    }

    fun previousChapter() {
        val index = _state.value.chapterIndex
        if (index > 0) openChapter(index - 1, restorePosition = false)
    }

    fun nextChapter() {
        val index = _state.value.chapterIndex
        if (index in 0 until _state.value.chapters.lastIndex) openChapter(index + 1, restorePosition = false)
    }

    /** 重新读盘：刚下载完的图不用退出重进（"缺图显示具体页码及重试"）。 */
    fun retryPage(pageIndex: Int) {
        val page = _state.value.pages.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val chapterId = _state.value.currentChapter?.chapterId ?: return@launch
            val files = withContext(Dispatchers.IO) {
                runCatching { reading.readableImages(album, chapterId) }.getOrDefault(emptyList())
            }
            val file = files.firstOrNull { it.name == page.imageFile }
            if (file == null) {
                _state.update { it.copy(message = "这一页还没下载好") }
                return@launch
            }
            _state.update { current ->
                current.copy(
                    pages = current.pages.map {
                        if (it.index == pageIndex) it.copy(file = file, aspectRatio = aspectOf(file)) else it
                    },
                    message = null,
                )
            }
        }
    }

    /**
     * 下载当前这一话（缺图时的出口）。
     *
     * 会自动把本子加入书库——下载的前提是它有一条书库记录（目录名从那里来）。
     */
    fun downloadCurrentChapter() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val chapter = _state.value.currentChapter ?: return@launch
            runCatching {
                val library = repo.addToLibrary(album)
                downloads.submit(library, listOf(chapter))
            }.onSuccess { result ->
                val text = when (result) {
                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入下载队列：${chapter.title}"

                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有下载任务，已把「${chapter.title}」加进去"
                }
                _state.update { it.copy(message = text) }
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 位置
    // ------------------------------------------------------------------

    /**
     * 上报当前位置（翻页/滚动时调用）。
     *
     * 去抖后落库：正在快速滑动时不需要每一帧都写。
     */
    fun onPositionChanged(pageIndex: Int, offsetRatio: Float) {
        val chapterId = _state.value.currentChapter?.chapterId ?: return
        val page = _state.value.pages.getOrNull(pageIndex) ?: return
        pendingPosition = PendingPosition(chapterId, page.imageFile, pageIndex, offsetRatio)
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            delay(POSITION_DEBOUNCE_MS)
            val pending = pendingPosition ?: return@launch
            runCatching {
                reading.savePosition(
                    albumId = albumId,
                    chapterId = pending.chapterId,
                    imageFile = pending.imageFile,
                    imageIndex = pending.pageIndex,
                    offsetRatio = pending.offsetRatio,
                )
            }.onFailure { Timber.tag("jm").w(it, "保存阅读位置失败") }
        }
    }

    /** 立刻落库（退出阅读器时调用，避免去抖窗口里的位置丢掉）。 */
    fun flushPosition() {
        val pending = pendingPosition ?: return
        positionJob?.cancel()
        viewModelScope.launch {
            runCatching {
                reading.savePosition(
                    albumId = albumId,
                    chapterId = pending.chapterId,
                    imageFile = pending.imageFile,
                    imageIndex = pending.pageIndex,
                    offsetRatio = pending.offsetRatio,
                )
            }
        }
    }

    private var pendingPosition: PendingPosition? = null

    private data class PendingPosition(
        val chapterId: Long,
        val imageFile: String,
        val pageIndex: Int,
        val offsetRatio: Float,
    )

    // ------------------------------------------------------------------
    // 控件与偏好
    // ------------------------------------------------------------------

    fun toggleControls() = _state.update { it.copy(controlsVisible = !it.controlsVisible) }

    fun hideControls() = _state.update { it.copy(controlsVisible = false) }

    fun showChapterPicker() = _state.update { it.copy(showChapterPicker = true, controlsVisible = true) }

    fun showSettings() = _state.update { it.copy(showSettings = true, controlsVisible = true) }

    fun dismissSheets() = _state.update { it.copy(showChapterPicker = false, showSettings = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.JM_READING_MODE, mode.toCode()) }
    }

    fun setReadingDirection(direction: ReadingDirection) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.JM_READING_DIRECTION, direction.toCode()) }
    }

    fun setBackground(background: ReadingBackground) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.JM_READING_BACKGROUND, background.toCode()) }
    }

    fun setBrightnessFollowSystem(follow: Boolean) {
        viewModelScope.launch {
            settings.putJmInt(
                SettingKeys.JM_BRIGHTNESS_MODE,
                if (follow) {
                    SettingDefaults.JM_BRIGHTNESS_FOLLOW_SYSTEM
                } else {
                    SettingDefaults.JM_BRIGHTNESS_CUSTOM
                },
            )
        }
    }

    fun setBrightness(value: Float) {
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.JM_BRIGHTNESS_VALUE, (value * 100).toInt().coerceIn(1, 100))
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settings.putJmBoolean(SettingKeys.JM_KEEP_SCREEN_ON, enabled) }
    }

    companion object {
        /** 位置落库的去抖时长。 */
        const val POSITION_DEBOUNCE_MS = 400L
    }
}

/**
 * 阅读偏好流：任一设置变化都会重新发射。
 *
 * 用 `combine` 而不是逐项收集，是为了让"改亮度"和"改背景"走同一条路径，
 * 界面只订阅一个状态。
 */
fun SettingsRepository.observeReaderPreferences(): Flow<ReaderPreferences> {
    val mode = jmInt(SettingKeys.JM_READING_MODE, SettingDefaults.JM_READING_MODE_VERTICAL)
    val direction = jmInt(SettingKeys.JM_READING_DIRECTION, SettingDefaults.JM_READING_DIRECTION_LTR)
    val background = jmInt(SettingKeys.JM_READING_BACKGROUND, SettingDefaults.JM_READING_BACKGROUND_THEME)
    val brightnessMode = jmInt(SettingKeys.JM_BRIGHTNESS_MODE, SettingDefaults.JM_BRIGHTNESS_FOLLOW_SYSTEM)
    val brightnessValue = jmInt(SettingKeys.JM_BRIGHTNESS_VALUE, SettingDefaults.JM_BRIGHTNESS_VALUE_DEFAULT)
    val keepScreenOn = jmBoolean(SettingKeys.JM_KEEP_SCREEN_ON, SettingDefaults.JM_KEEP_SCREEN_ON)
    return combine(mode, direction, background, brightnessMode, brightnessValue) { m, d, b, bm, bv ->
        ReaderPreferences(
            mode = ReadingMode.fromCode(m),
            direction = ReadingDirection.fromCode(d),
            background = ReadingBackground.fromCode(b),
            brightnessFollowSystem = bm == SettingDefaults.JM_BRIGHTNESS_FOLLOW_SYSTEM,
            brightnessValue = bv / 100f,
        )
    }.combine(keepScreenOn) { prefs, keep -> prefs.copy(keepScreenOn = keep) }
}
