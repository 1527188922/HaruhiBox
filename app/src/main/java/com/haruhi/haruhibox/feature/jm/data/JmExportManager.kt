package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.codec.ArchiveWriter
import com.haruhi.haruhibox.core.codec.PdfWriter
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.network.toUserMessage
import com.haruhi.haruhibox.core.storage.ExportPublisher
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
/** 导出任务的参数快照（`t_download_task.payload`）。**绝不放密码**（计划 §D.2 第 6 条）。 */
@Serializable
data class JmExportPayload(
    val albumId: Long,
    /** 本子目录名：章节与图片都在它下面。 */
    val folderName: String,
    /** `zip` 或 `pdf`，决定产物类型。 */
    val format: String,
) {
    fun encode(): String = JmJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String?): JmExportPayload? {
            if (json.isNullOrBlank()) return null
            return runCatching { JmJson.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}

/** 导出格式。 */
enum class JmExportFormat(val taskType: DownloadType, val extension: String, val mimeType: String, val label: String) {
    ZIP(DownloadType.JM_EXPORT_ZIP, "zip", "application/zip", "ZIP"),
    PDF(DownloadType.JM_EXPORT_PDF, "pdf", "application/pdf", "PDF"),
}

/**
 * 导出编排（计划 §10 P4）：
 * 「zip（AES 密码 / 不加密两种）；pdf（密码 / 不加密、书签、权限、页尺寸）；
 *  导出前的密码框交互；导出进度进入当前模式任务页，不强制跳转」。
 *
 * ## 为什么复用下载任务表
 *
 * `t_download_task` 的 `type` 里本来就有 `JM_EXPORT_ZIP` / `JM_EXPORT_PDF`，
 * 任务页、角标、暂停/取消/重试都是现成的。导出与下载的区别只有两点，都在这里体现：
 * - **不能续做**：中途取消就整份重来（计划 §9 第 9 条「导出不支持中途续做时仅提供取消」），
 *   所以不提供"继续"，只提供取消 + 重新生成；
 * - **要落库到系统目录**：成品先写在 `files/library/export/`，完成后交给 [ExportPublisher]。
 *
 * ## 密码不进数据库
 *
 * 密码存在内存里（[passwords]），只活在本次进程内。理由：
 * 任务参数会进数据库与日志（计划 §D.2 第 6 条），凭据绝不能写进去。
 * 进程被杀 = 导出任务也不会恢复（本来就不能续做），密码随之消失是正确行为。
 */
class JmExportManager(
    private val repo: JmRepository,
    private val tasks: DownloadTaskRepository,
    private val dirs: LibraryDirs,
    private val archiveWriter: ArchiveWriter,
    private val pdfWriter: PdfWriter,
    private val publisher: ExportPublisher,
    private val scope: CoroutineScope,
    private val onTaskStarted: () -> Unit = {},
) {

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** taskId → 导出密码（`null` = 用户选了"不设密码"）。只在内存里。 */
    private val passwords = ConcurrentHashMap<Long, String>()

    // ------------------------------------------------------------------
    // 缺图预检
    // ------------------------------------------------------------------

    /**
     * 导出前的检查：**缺图时不静默导出残缺内容**（计划 §9 第 7 条）。
     *
     * 返回每一话的"应有多少张 / 实际有多少张"，缺的那些列出来给用户，
     * 由用户决定先补下载还是直接导出（首版选择"先补下载"）。
     */
    data class Precheck(
        val totalImages: Int,
        val readyImages: Int,
        val missingChapters: List<MissingChapter>,
    ) {
        val isComplete: Boolean get() = missingChapters.isEmpty()
        val missingImages: Int get() = totalImages - readyImages
    }

    data class MissingChapter(val title: String, val missing: Int, val total: Int)

    suspend fun precheck(album: JmAlbumEntity): Precheck = withContext(Dispatchers.IO) {
        val albumId = album.id
        val folder = album.albumFolderName ?: return@withContext Precheck(0, 0, emptyList())
        val chapters = repo.chaptersOf(album)
        var total = 0
        var ready = 0
        val missing = mutableListOf<MissingChapter>()
        for (chapter in chapters) {
            val images = repo.imagesOf(albumId, chapter.chapterId).map { it.imageFile }
            if (images.isEmpty()) continue
            val have = repo.downloadedImageCount(folder, repo.chapterFolderName(chapter), images)
            total += images.size
            ready += have
            if (have < images.size) {
                missing += MissingChapter(chapter.title, images.size - have, images.size)
            }
        }
        Precheck(total, ready, missing)
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交一个导出任务。
     *
     * @param password `null`/空串表示"不设密码"
     */
    suspend fun submit(
        album: JmAlbumEntity,
        format: JmExportFormat,
        password: String?,
    ): DownloadTaskRepository.SubmitResult {
        val folderName = album.albumFolderName
            ?: error("本子目录名缺失，无法导出")
        // 文件名用**本子目录名**（`<清洗后的标题>_JM<aid>`），与参考实现一致：
        // 它已经做过文件名清洗、也天然带上 JM 号，不会出现同名覆盖
        val displayName = "$folderName.${format.extension}"

        val existing = tasks.activeTask(format.taskType, album.id.toString())
        if (existing != null) {
            // 同一本同一格式只允许一个活跃导出：重复提交直接复用，不排第二份
            return DownloadTaskRepository.SubmitResult.AlreadyRunning(existing.id, existing.status)
        }

        val payload = JmExportPayload(album.id, folderName, format.name.lowercase())
        val result = tasks.submit(
            type = format.taskType,
            bizKey = album.id.toString(),
            displayName = displayName,
            payload = payload.encode(),
            totalChapters = 0,
        )
        if (result is DownloadTaskRepository.SubmitResult.Created) {
            passwords[result.taskId] = password.orEmpty()
            start(result.taskId)
        }
        return result
    }

    fun cancel(taskId: Long) {
        scope.launch {
            runCatching { tasks.markCancelled(taskId) }
            jobs[taskId]?.cancel()
            passwords.remove(taskId)
        }
    }

    // ------------------------------------------------------------------
    // 成品查询 / 删除（打开至 / 发送至 / 删除文件都用得上）
    // ------------------------------------------------------------------

    /** 该本子这一种格式的成品文件名。 */
    fun outputNameOf(album: JmAlbumEntity, format: JmExportFormat): String? =
        album.albumFolderName?.let { "$it.${format.extension}" }

    /**
     * 这个成品现在还在不在（导出确认框用它提示"会覆盖已有文件"）。
     *
     * MediaStore 查询是 IO，所以放到 IO 线程上。
     */
    suspend fun outputExists(album: JmAlbumEntity, format: JmExportFormat): Boolean =
        withContext(Dispatchers.IO) {
            val name = outputNameOf(album, format) ?: return@withContext false
            publisher.exists(name)
        }

    suspend fun outputUri(displayName: String): android.net.Uri? = withContext(Dispatchers.IO) {
        publisher.uriOf(displayName)
    }

    /** 删除某一个成品文件（任务页的「删除文件」）。 */
    suspend fun deleteOutput(displayName: String): Boolean = withContext(Dispatchers.IO) {
        publisher.delete(displayName)
    }

    /**
     * 删除该本子的导出成品（zip + pdf）。
     *
     * "删除本地文件"与"从书库移除"都要连导出成品一起清掉（用户要求第 6、7 点）——
     * 成品在系统下载目录里，不在本子目录下，所以必须单独删。
     *
     * @return 删掉的文件个数
     */
    suspend fun deleteOutputs(album: JmAlbumEntity): Int = withContext(Dispatchers.IO) {
        JmExportFormat.entries.count { format ->
            val name = outputNameOf(album, format) ?: return@count false
            publisher.exists(name) && publisher.delete(name)
        }
    }

    private fun start(taskId: Long) {
        if (jobs[taskId]?.isActive == true) return
        onTaskStarted()
        jobs[taskId] = scope.launch {
            try {
                runTask(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 被取消/暂停时状态已经写好了，不能覆盖成"失败"
                if (tasks.findById(taskId)?.status?.isRunnable != true) {
                    Timber.tag("export").i("导出任务 %d 已停止（非失败）", taskId)
                } else {
                    Timber.tag("export").e(e, "导出任务 %d 失败", taskId)
                    runCatching { tasks.markFailed(taskId, e.toUserMessage()) }
                }
            } finally {
                jobs.remove(taskId)
                passwords.remove(taskId)
            }
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private suspend fun runTask(taskId: Long) {
        val task = tasks.findById(taskId) ?: return
        if (!task.status.isRunnable) return
        // 导出是"不能续做"的长任务，取消只靠这个 Job：写 PDF 的循环每张图问一次
        val job = currentCoroutineContext()[Job]

        val payload = JmExportPayload.decode(task.payload)
        if (payload == null) {
            tasks.markFailed(taskId, "导出参数已损坏，请重新发起导出")
            return
        }
        val format = JmExportFormat.entries.firstOrNull { it.name.equals(payload.format, true) }
        if (format == null) {
            tasks.markFailed(taskId, "不认识的导出格式")
            return
        }

        val albumDir = dirs.albumDir(payload.folderName)
        if (!albumDir.isDirectory) {
            tasks.markFailed(taskId, "本地没有这本漫画的图片，请先下载")
            return
        }

        tasks.markRunning(taskId)
        tasks.updateProgress(taskId, stageText = "导出${format.label}")

        val password = passwords[taskId].orEmpty()
        val out = File(dirs.export, task.displayName ?: "${payload.folderName}.${format.extension}")
        // 中转文件先清掉，避免上一次的残留被当成结果
        if (out.exists()) out.delete()

        val published = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                when (format) {
                    JmExportFormat.ZIP -> exportZip(taskId, albumDir, out, password) { job?.isActive != false }
                    JmExportFormat.PDF -> exportPdf(taskId, albumDir, out, password) { job?.isActive != false }
                }
            }.getOrElse { e ->
                out.delete()
                throw e
            }
        }

        // 成品交给系统目录，然后清掉私有目录里的中转文件
        val ready = withContext(Dispatchers.IO) {
            publisher.publish(out, out.name, format.mimeType)
        }
        out.delete()

        if (!isRunnable(taskId)) return
        tasks.updateProgress(taskId, stageText = "已保存到 ${ready.location}")
        tasks.markSuccess(taskId)
    }

    private fun exportZip(
        taskId: Long,
        albumDir: File,
        out: File,
        password: String,
        isActive: () -> Boolean,
    ) {
        val result = archiveWriter.albumToZip(albumDir, out, password.ifEmpty { null }) { done, total, chapter ->
            if (!isActive()) throw CancellationException("导出已取消")
            scope.launch {
                tasks.updateProgress(
                    id = taskId,
                    stageText = "打包 $chapter",
                    doneImages = done,
                    totalImages = total,
                )
            }
        }
        Timber.tag("export").i("zip 完成：%d 个条目，加密=%s", result.entryCount, result.encrypted)
    }

    private fun exportPdf(
        taskId: Long,
        albumDir: File,
        out: File,
        password: String,
        isActive: () -> Boolean,
    ) {
        val result = pdfWriter.albumToPdf(
            albumDir = albumDir,
            out = out,
            password = password.ifEmpty { null },
            onProgress = { done, total, chapter ->
                scope.launch {
                    tasks.updateProgress(
                        id = taskId,
                        stageText = "生成 PDF $chapter",
                        doneImages = done,
                        totalImages = total,
                    )
                }
            },
            isCancelled = { !isActive() },
        )
        Timber.tag("export").i(
            "pdf 完成：%d 页 / %d 话（切片 %d 张），加密=%s",
            result.pageCount,
            result.chapterCount,
            result.slicedImageCount,
            result.encrypted,
        )
    }

    private suspend fun isRunnable(taskId: Long): Boolean =
        tasks.findById(taskId)?.status?.isRunnable == true
}
