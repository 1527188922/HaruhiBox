package com.haruhi.haruhibox

import android.app.Application
import android.content.Context
import com.haruhi.haruhibox.core.di.AppContainer
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
        Timber.i(
            "HaruhiBox 启动 version=%s debug=%s library=%s",
            BuildConfig.VERSION_NAME,
            BuildConfig.DEBUG,
            container.libraryDirs.root.absolutePath,
        )
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
