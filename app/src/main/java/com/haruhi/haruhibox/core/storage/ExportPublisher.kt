package com.haruhi.haruhibox.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.File

/**
 * 把导出成品交给系统（计划 §8「**导出 zip/pdf 走 MediaStore（Downloads）或 SAF**」）。
 *
 * 为什么要经过这一层：成品先落在应用私有的 `files/library/export/`（打包过程中的中转），
 * 完成后必须搬到用户能找到的地方，否则"导出"等于没导出。
 *
 * 按系统版本分两条路：
 * - **API 29+**：`MediaStore.Downloads`，**不需要任何权限**，
 *   落到 `Download/HaruHiBox/`，系统文件管理器里直接能看到；
 * - **API 26～28**：MediaStore 的 Downloads 集合还不存在，写入公共 Downloads 需要
 *   `WRITE_EXTERNAL_STORAGE` 运行时权限。本 App 不主动申请存储权限（计划 §9.1
 *   只要求"首次需要后台任务时"申请通知权限），所以这一档退到应用的外部私有目录
 *   `Android/data/<pkg>/files/Download/`，并把**确切位置**告诉用户。
 *
 * 无论走哪条路，都返回一段可以直接展示的"在哪里"文案。
 */
interface ExportPublisher {
    data class Published(
        /** 系统里的文件名。 */
        val displayName: String,
        /** 给用户看的位置说明（"下载/HaruHiBox"之类）。 */
        val location: String,
        /** 能拿到内容 Uri 的话带上（用于"打开文件"）。 */
        val uri: Uri?,
    )

    fun publish(source: File, displayName: String, mimeType: String): Published
}

/** 真机实现（见接口注释里的版本分支）。 */
class AndroidExportPublisher(private val context: Context) : ExportPublisher {

    override fun publish(source: File, displayName: String, mimeType: String): ExportPublisher.Published =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(source, displayName, mimeType)
        } else {
            publishToExternalDir(source, displayName)
        }

    /**
     * API 29+ 才有 `MediaStore.Downloads` 集合；调用点已经用 `SDK_INT` 分支挡住，
     * 这里再标一次是为了让 lint 能跨方法看到这个前提。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(
        source: File,
        displayName: String,
        mimeType: String,
    ): ExportPublisher.Published {
        val resolver = context.contentResolver
        // 同名文件会被系统自动改名（(1) 后缀），所以直接插一条新的
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建文件（MediaStore 返回空）")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入下载目录")

            // 写完才对外可见，避免文件管理器看到半成品
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        Timber.tag("export").i("已导出到下载目录：%s", displayName)
        return ExportPublisher.Published(
            displayName = displayName,
            location = "下载/$SUBFOLDER",
            uri = uri,
        )
    }

    private fun publishToExternalDir(source: File, displayName: String): ExportPublisher.Published {
        // 老系统不申请存储权限，退到应用外部私有目录；位置在文案里说清楚
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val target = File(dir, displayName)
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        Timber.tag("export").i("已导出（应用目录）：%s", target.absolutePath)
        return ExportPublisher.Published(
            displayName = displayName,
            location = "Android/data/${context.packageName}/files/Download",
            uri = null,
        )
    }

    companion object {
        /** 下载目录下的子目录名，避免和用户自己的文件混在一起。 */
        const val SUBFOLDER = "HaruHiBox"
    }
}
