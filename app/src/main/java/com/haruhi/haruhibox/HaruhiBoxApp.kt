package com.haruhi.haruhibox

import android.app.Application
import android.content.Context
import com.haruhi.haruhibox.core.di.AppContainer
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.notification.DownloadNotifications
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。
 *
 * 职责（P0/P1 基线，全部是壳级别的准备工作）：
 * 1. 初始化日志（Timber）＋ **崩溃日志本地留存**（计划 §8「崩溃日志本地留存」）；
 * 2. 创建依赖注入容器（[AppContainer]）；
 * 3. 建好本地目录。
 *
 * 这里**不做**权限申请、不做首次引导（计划 §9 第 1 条：开屏只有漫画/视频两个入口）。
 */
class HaruhiBoxApp : Application() {

    /** 依赖注入容器。Activity / ViewModel 通过 [from] 取。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installLogging()
        container = AppContainer(this)
        DownloadNotifications.ensureChannel(this)
        recoverInterruptedDownloads()
        Timber.i(
            "HaruhiBox 启动 version=%s debug=%s library=%s",
            BuildConfig.VERSION_NAME,
            BuildConfig.DEBUG,
            container.libraryDirs.root.absolutePath,
        )
    }

    /**
     * 启动时恢复被中断的下载任务，并**接着把它们跑完**。
     *
     * 计划 §10 P1/P3 的验收边界：「杀掉进程再启动，任务列表和进度能从数据库恢复
     * （**RUNNING 任务按磁盘现状重算进度后继续**）」、「下载到一半杀进程 →
     * 重启后从断点继续，已完成图片不重下」。
     *
     * 恢复规则（§D.3 的故障验收矩阵，由 [DownloadTaskRepository.recoverInterrupted] 实现）：
     * - `RUNNING` / `WAITING_NETWORK` → 按磁盘现状重算进度后打回 `QUEUED`，这里随即继续执行；
     * - `PAUSED` 保持暂停，等用户点恢复；
     * - `CANCELLED` **不复活**。
     *
     * 章节清单从数据库取（`t_jm_chapter_image` 是权威来源），不需要联网。
     */
    private fun recoverInterruptedDownloads() {
        container.appScope.launch {
            runCatching {
                val recovered = container.downloadTasks.recoverInterrupted { task ->
                    val albumId = task.bizKey.toLongOrNull() ?: return@recoverInterrupted emptyList()
                    val album = container.database.jmAlbumDao().findById(albumId)
                        ?: return@recoverInterrupted emptyList()
                    val folderName = album.albumFolderName
                        ?: return@recoverInterrupted emptyList()

                    val chapters = container.database.jmChapterDao().chapterList(albumId)
                    chapters.map { chapter ->
                        val chapterFolder = chapter.chapterTitle
                            ?: chapter.chapterName
                            ?: chapter.chapterId.toString()
                        val imageFiles = container.database.jmChapterDao()
                            .images(albumId, chapter.chapterId)
                            .map { it.imageFile }
                        DownloadTaskRepository.ChapterRediscovery(
                            chapterId = chapter.chapterId,
                            chapterDir = container.libraryDirs.chapterDir(folderName, chapterFolder),
                            imageFiles = imageFiles,
                        )
                    }
                }
                if (recovered.isEmpty()) return@launch
                Timber.i("恢复 %d 个被中断的下载任务", recovered.size)
                // 只能恢复 JM 任务：B站下载在 P5 接，这里按类型过滤，避免把别的任务喂给 JM 执行器
                val jmTaskIds = recovered
                    .filter { it.type == com.haruhi.haruhibox.core.database.entity.DownloadType.JM_ALBUM }
                    .map { it.id }
                jmTaskIds.forEach { container.jmDownloads.requeueForResume(it) }
                container.jmDownloads.resumeQueued(jmTaskIds)
            }.onFailure { Timber.w(it, "恢复中断任务失败") }
        }
    }

    private fun installLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 线上与调试都落一份崩溃日志：用户报问题时可以直接导出这个文件
        val crashDir = File(filesDir, "crash")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(crashDir, thread, throwable) }
                .onFailure { Timber.w(it, "写崩溃日志失败") }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(crashDir: File, thread: Thread, throwable: Throwable) {
        crashDir.mkdirs()
        // 只保留最近 20 个崩溃文件，避免无限增长
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES - 1)
            ?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(crashDir, "crash-$timestamp.txt")
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        file.writeText(
            buildString {
                appendLine("time: $timestamp")
                appendLine("thread: ${thread.name}")
                appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("debug: ${BuildConfig.DEBUG}")
                appendLine()
                append(stackTrace)
            },
            Charsets.UTF_8,
        )
    }

    companion object {
        private const val MAX_CRASH_FILES = 20

        /** 从任意 [Context] 取容器。 */
        fun from(context: Context): AppContainer =
            (context.applicationContext as HaruhiBoxApp).container
    }
}
