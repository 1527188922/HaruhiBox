package com.haruhi.haruhibox.feature.jm.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskEntity
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import org.junit.Test

/**
 * P3 的展示逻辑单元测试（任务页 + 阅读器 + 详情页主按钮）。
 *
 * 这些文案是计划里点名的验收项：
 * - §9 第 9 条：「明确区分排队、执行、暂停、等待网络及待处理失败，**不将失败算作正在运行**」；
 * - §10 P3：「进度显示能精确到"第 N 章 x/y 张"」；
 * - §9 第 7 条：主按钮按状态显示"继续阅读 / 开始阅读 / 下载首章"，
 *   下载面板**默认勾选未完成章节**。
 *
 * 抽成纯函数来测是有意的：Compose 的交互测试需要设备亮屏解锁（见 `JmDisplayLogicTest` 的说明），
 * 而"文案与判定条件对不对"完全可以在这里锁死。
 */
class JmReaderAndTaskLogicTest {

    // ---------------- 任务状态 ----------------

    @Test
    fun `every task status has its own label and failure is never running`() {
        assertThat(label(DownloadStatus.QUEUED)).isEqualTo("排队中")
        assertThat(label(DownloadStatus.PAUSED)).isEqualTo("已暂停")
        assertThat(label(DownloadStatus.WAITING_NETWORK)).isEqualTo("等待网络")
        assertThat(label(DownloadStatus.SUCCESS)).isEqualTo("已完成")
        assertThat(label(DownloadStatus.FAIL)).isEqualTo("未完成")
        assertThat(label(DownloadStatus.CANCELLED)).isEqualTo("已取消")
        // 运行中显示的是当前阶段
        assertThat(label(DownloadStatus.RUNNING, stage = "下载图片")).isEqualTo("下载图片")
    }

    @Test
    fun `progress combines chapter and image counts`() {
        val task = task(totalChapters = 12, doneChapters = 2, totalImages = 240, doneImages = 18)
        // 「第 N 章 x/y 张」两个粒度都要给出来
        assertThat(taskProgressText(task)).isEqualTo("2/12 话 · 18/240 张")
    }

    @Test
    fun `progress falls back to bytes for video tasks and to a placeholder when empty`() {
        val video = task(totalBytes = 20L * 1024 * 1024, downloadedBytes = 5L * 1024 * 1024)
        assertThat(taskProgressText(video)).contains("5.0 MB")

        assertThat(taskProgressText(task())).isEqualTo("准备中")
    }

    @Test
    fun `waiting network and failure explain themselves`() {
        val waiting = task(status = DownloadStatus.WAITING_NETWORK, message = null)
        assertThat(taskDetailText(waiting)).isNotNull()

        val failed = task(status = DownloadStatus.FAIL, message = "3 话没有下完")
        assertThat(taskDetailText(failed)).isEqualTo("3 话没有下完")

        // 正常进行中不需要额外解释
        assertThat(taskDetailText(task(status = DownloadStatus.QUEUED))).isNull()
    }

    @Test
    fun `task row offers the actions that make sense for its status`() {
        val running = TaskRow(task(status = DownloadStatus.RUNNING))
        assertThat(running.canPause).isTrue()
        assertThat(running.canCancel).isTrue()
        assertThat(running.canRetry).isFalse()

        val paused = TaskRow(task(status = DownloadStatus.PAUSED))
        assertThat(paused.canResume).isTrue()
        assertThat(paused.canPause).isFalse()

        val failed = TaskRow(task(status = DownloadStatus.FAIL))
        assertThat(failed.canRetry).isTrue()
        assertThat(failed.canCancel).isFalse()

        val done = TaskRow(task(status = DownloadStatus.SUCCESS))
        assertThat(done.canRetry).isFalse()
        assertThat(done.canCancel).isFalse()
    }

    @Test
    fun `task name falls back to the jm id when there is no display name`() {
        assertThat(TaskRow(task(displayName = null)).displayName).isEqualTo("JM480854")
        assertThat(TaskRow(task(displayName = "测试本子")).displayName).isEqualTo("测试本子")
    }

    // ---------------- 详情页的下载面板 ----------------

    @Test
    fun `download sheet preselects only the unfinished chapters`() {
        val state = AlbumDetailUiState(
            album = album(),
            chapters = listOf(
                chapter(1, downloaded = 10, total = 10),
                chapter(2, downloaded = 3, total = 10),
                chapter(3, downloaded = 0, total = 10),
            ),
        )
        assertThat(state.defaultDownloadSelection).containsExactly(2L, 3L)
    }

    /**
     * 用户要求第 3 点：**已经在下载队列里的章节**不再默认勾选，
     * 面板里也要用另一种颜色标出来、点不动。
     */
    @Test
    fun `chapters already in the queue are not preselected`() {
        val state = AlbumDetailUiState(
            album = album(),
            chapters = listOf(
                chapter(1, downloaded = 0, total = 10),
                chapter(2, downloaded = 0, total = 10),
                chapter(3, downloaded = 0, total = 10),
            ),
            queuedChapterIds = setOf(1L, 3L),
        )
        assertThat(state.defaultDownloadSelection).containsExactly(2L)
    }

    // ---------------- 底栏切换方向（用户要求第 1 点） ----------------

    @Test
    fun `switching to a tab on the right slides in from the right`() {
        assertThat(tabShift("jm/library", "jm/recent")).isEqualTo(1)
        assertThat(tabShift("jm/library", "jm/tasks")).isEqualTo(4)
        assertThat(tabShift("jm/discover", "jm/favorites")).isEqualTo(1)
    }

    @Test
    fun `switching to a tab on the left slides in from the left`() {
        assertThat(tabShift("jm/tasks", "jm/favorites")).isEqualTo(-1)
        assertThat(tabShift("jm/favorites", "jm/library")).isEqualTo(-3)
    }

    @Test
    fun `entering a detail page keeps the push direction`() {
        // 有一端不是底栏 tab（详情页/阅读器）时按正数处理，也就是"从右侧进入"
        assertThat(tabShift("jm/library", "jm/album/{albumId}")).isEqualTo(1)
        assertThat(tabShift("jm/album/{albumId}", "jm/library")).isEqualTo(1)
    }

    // ---------------- 阅读器 ----------------

    @Test
    fun `reader exposes chapter navigation and missing page count`() {
        val state = readerState(chapterIndex = 1, chapterCount = 3, pages = listOf(true, false, true))
        assertThat(state.hasPreviousChapter).isTrue()
        assertThat(state.hasNextChapter).isTrue()
        assertThat(state.missingCount).isEqualTo(1)
        assertThat(state.progressLabel).isEqualTo("3 页 · 缺 1 页")
    }

    @Test
    fun `reader knows when it is at either end of the chapter list`() {
        val first = readerState(chapterIndex = 0, chapterCount = 3, pages = listOf(true))
        assertThat(first.hasPreviousChapter).isFalse()
        assertThat(first.hasNextChapter).isTrue()

        val last = readerState(chapterIndex = 2, chapterCount = 3, pages = listOf(true))
        assertThat(last.hasPreviousChapter).isTrue()
        assertThat(last.hasNextChapter).isFalse()
    }

    @Test
    fun `reader reports all pages missing instead of silently skipping them`() {
        val state = readerState(chapterIndex = 0, chapterCount = 1, pages = listOf(false, false))
        assertThat(state.allMissing).isTrue()
        assertThat(state.progressLabel).isEqualTo("2 页 · 缺 2 页")
    }

    @Test
    fun `reader defaults to vertical continuous with left to right pages`() {
        val prefs = ReaderPreferences()
        assertThat(prefs.mode).isEqualTo(ReadingMode.VERTICAL)
        assertThat(prefs.direction).isEqualTo(ReadingDirection.LTR)
        assertThat(prefs.keepScreenOn).isTrue()
        assertThat(prefs.layoutDirection).isEqualTo(androidx.compose.ui.unit.LayoutDirection.Ltr)
    }

    @Test
    fun `right to left only affects horizontal paging`() {
        val rtlVertical = ReaderPreferences(mode = ReadingMode.VERTICAL, direction = ReadingDirection.RTL)
        assertThat(rtlVertical.layoutDirection).isEqualTo(androidx.compose.ui.unit.LayoutDirection.Ltr)

        val rtlHorizontal = ReaderPreferences(mode = ReadingMode.HORIZONTAL, direction = ReadingDirection.RTL)
        assertThat(rtlHorizontal.layoutDirection).isEqualTo(androidx.compose.ui.unit.LayoutDirection.Rtl)
    }

    @Test
    fun `reading preferences round trip through their stored codes`() {
        ReadingMode.entries.forEach { mode ->
            assertThat(ReadingMode.fromCode(mode.toCode())).isEqualTo(mode)
        }
        ReadingDirection.entries.forEach { direction ->
            assertThat(ReadingDirection.fromCode(direction.toCode())).isEqualTo(direction)
        }
        ReadingBackground.entries.forEach { background ->
            assertThat(ReadingBackground.fromCode(background.toCode())).isEqualTo(background)
        }
    }

    @Test
    fun `unknown stored codes fall back to the documented defaults`() {
        assertThat(ReadingMode.fromCode(99)).isEqualTo(ReadingMode.VERTICAL)
        assertThat(ReadingDirection.fromCode(99)).isEqualTo(ReadingDirection.LTR)
        assertThat(ReadingBackground.fromCode(99)).isEqualTo(ReadingBackground.THEME)
    }

    /** 界面上的选项文案就是计划 §9.3 的那几个词，不允许出现内部枚举名。 */
    @Test
    fun `reading option labels are the user facing wording`() {
        assertThat(ReadingMode.entries.map { it.label }).containsExactly("纵向连续", "横向分页")
        assertThat(ReadingBackground.entries.map { it.label })
            .containsExactly("跟随主题", "浅色", "深色", "护眼暖色")
        assertThat(ReadingDirection.entries.map { it.label }).containsExactly("从左到右", "从右到左")
    }

    // ---------------- 工具 ----------------

    private fun label(status: DownloadStatus, stage: String? = null) =
        taskStatusLabel(task(status = status, stage = stage))

    private fun task(
        status: DownloadStatus = DownloadStatus.QUEUED,
        stage: String? = null,
        message: String? = null,
        displayName: String? = "测试本子_JM480854",
        totalChapters: Int = 0,
        doneChapters: Int = 0,
        totalImages: Int = 0,
        doneImages: Int = 0,
        totalBytes: Long = 0,
        downloadedBytes: Long = 0,
    ) = DownloadTaskEntity(
        id = 1,
        type = com.haruhi.haruhibox.core.database.entity.DownloadType.JM_ALBUM,
        bizKey = "480854",
        status = status,
        totalChapters = totalChapters,
        doneChapters = doneChapters,
        totalImages = totalImages,
        doneImages = doneImages,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        stageText = stage,
        message = message,
        displayName = displayName,
    )

    private fun album() = JmAlbumEntity(id = 480854, name = "测试本子", albumFolderName = "测试本子_JM480854")

    private fun chapter(id: Long, downloaded: Int, total: Int) =
        ChapterRow(
            chapterId = id,
            title = "第${id}话",
            sortIndex = id.toInt(),
            totalImages = total,
            downloadedImages = downloaded,
        )

    private fun readerState(chapterIndex: Int, chapterCount: Int, pages: List<Boolean>) = ReaderUiState(
        album = album(),
        chapters = (1..chapterCount).map {
            com.haruhi.haruhibox.feature.jm.data.ChapterSummary(
                chapterId = it.toLong(),
                sort = it.toString(),
                name = null,
                title = "第${it}话",
            )
        },
        chapterIndex = chapterIndex,
        pages = pages.mapIndexed { index, available ->
            ReaderPage(
                index = index,
                imageFile = "%05d.webp".format(index + 1),
                file = if (available) java.io.File("/tmp/${index + 1}.webp") else null,
                aspectRatio = ReaderPage.DEFAULT_ASPECT,
            )
        },
    )
}
