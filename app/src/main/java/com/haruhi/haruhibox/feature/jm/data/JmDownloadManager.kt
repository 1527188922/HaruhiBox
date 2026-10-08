package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.codec.ImageDownloadTransform
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.download.ParallelImageDownloader
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.network.NetworkState
import com.haruhi.haruhibox.core.network.toUserMessage
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 下载执行器需要的"环境"。
 *
 * 做成接口而不是直接读设置，有两个原因：
 * 1. **每次调用都重新读**——计划 §9.2 要求"线程数、线路、网络策略对新任务或下次重试生效"，
 *    所以这里全是 `suspend` 函数，而不是构造时的一张快照；
 * 2. 可以单测：注入一个假的网络状态就能验证"移动网络下进等待而不是失败"。
 */
interface JmDownloadEnvironment {
    /** 同时下载的图片数（已解析，保证 >= 1）。 */
    suspend fun concurrency(): Int

    /** 图片线路域名。 */
    suspend fun imageDomain(): String

    /**
     * 当前策略下，这个网络是否允许开始新的传输。
     *
     * @param metered 目标网络是否计费（移动网络、按流量计费的 Wi-Fi）
     */
    suspend fun allowsTransfer(metered: Boolean): Boolean

    /** 网络状况，用于等待恢复。 */
    val networkState: StateFlow<NetworkState>
}

/** 下载任务的参数快照（`t_download_task.payload`）。**不放凭据**（计划 §D.2 第 6 条）。 */
@Serializable
data class JmDownloadPayload(
    val albumId: Long,
    val chapterIds: List<Long> = emptyList(),
) {
    fun encode(): String = JmJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String?): JmDownloadPayload? {
            if (json.isNullOrBlank()) return null
            return runCatching { JmJson.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}

/**
 * JM 章节图片下载的编排器（计划 §10 P3 的核心）。
 *
 * ## 它负责什么
 *
 * - **任务生命周期**：提交（含去重）、开始、暂停、恢复、取消、重试；
 * - **执行顺序**：任务 → 章节 → 图片。每章先保证"图片清单已入库"
 *   （没有就现场拉一次 `/chapter` 并持久化，§9 第 7 条），再取 `scramble_id`
 *   构造解扰钩子，然后走并发图片下载；
 * - **进度持久化**：每张图完成写一次章节计数，每章结束重算任务级数字；
 * - **网络策略**：每个安全检查点问一次闸门，不允许时进 `WAITING_NETWORK`
 *   并**挂起等待**网络恢复（不是失败），恢复后自动继续（§9.5「不偷偷开始大下载」）；
 * - **恢复**：交给 [DownloadTaskRepository.recoverInterrupted]，本类只负责"恢复之后接着跑"。
 *
 * ## 它不负责什么
 *
 * 不做解扰算法（[ImageDownloadTransform]）、不做 HTTP（`FileDownloader`）、
 * 不做通知（`DownloadForegroundService` 订阅任务表渲染）。
 * 这样"谁在下载"只有一个答案。
 *
 * ## 防死循环（对齐参考实现）
 *
 * 参考实现 `downloadChapter` 的规则是"**本次需下载数与上次相同就终止**"。
 * 这里的表达更直接，而且有两层：
 * - 章节内：每轮先数"磁盘上还缺几张"，**缺图数没有下降就结束**，最多 [MAX_CHAPTER_ROUNDS] 轮；
 * - 任务内：一轮下来没有任何章节完成（剩余章节数没减少）就整体停止。
 *
 * 所以不可能无限重试，也不会因为一次网络抖动把整本直接判死。
 */
class JmDownloadManager(
    private val repo: JmRepository,
    private val tasks: DownloadTaskRepository,
    private val downloader: ParallelImageDownloader,
    private val dirs: LibraryDirs,
    private val environment: JmDownloadEnvironment,
    private val scope: CoroutineScope,
    /** 有任务开始执行时回调（用来拉起前台服务与常驻通知）。 */
    private val onTaskStarted: () -> Unit = {},
) {

    private val jobs = ConcurrentHashMap<Long, Job>()

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交一本漫画的章节下载。
     *
     * 同一本子已有活跃任务时**不新建**：把新选的章节**追加**进那个任务
     * （计划 §9 第 7 条要求"重复提交显示已有任务"，但"我选了第 5 话却没下"
     * 是更糟的体验），并唤醒它继续跑。
     */
    suspend fun submit(
        album: JmAlbumEntity,
        chapters: List<ChapterSummary>,
    ): DownloadTaskRepository.SubmitResult {
        require(chapters.isNotEmpty()) { "至少要选一章" }
        val bizKey = album.id.toString()
        val payload = JmDownloadPayload(album.id, chapters.map { it.chapterId })
        val specs = chapters.map { chapter ->
            DownloadTaskRepository.ChapterSpec(
                chapterId = chapter.chapterId,
                // 章节文件夹名就是 title（`chapterFolderName` 的规则：title 优先且永不为空）
                chapterTitle = repo.chapterFolderName(chapter),
                sort = chapter.sort?.toIntOrNull() ?: 0,
                totalImages = 0,
            )
        }
        val displayName = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"

        val existing = tasks.activeTask(DownloadType.JM_ALBUM, bizKey)
        if (existing != null) {
            appendChapters(existing.id, specs)
            // 用户此刻明确要求下载，所以暂停中的任务也接着跑
            if (existing.status == DownloadStatus.PAUSED) {
                tasks.markQueued(existing.id)
            }
            start(existing.id)
            return DownloadTaskRepository.SubmitResult.AlreadyRunning(existing.id, existing.status)
        }

        val result = tasks.submit(
            type = DownloadType.JM_ALBUM,
            bizKey = bizKey,
            displayName = displayName,
            payload = payload.encode(),
            totalChapters = chapters.size,
        )
        if (result is DownloadTaskRepository.SubmitResult.Created) {
            tasks.upsertChapters(result.taskId, specs)
            tasks.recomputeTaskTotals(result.taskId)
            start(result.taskId)
        }
        return result
    }

    /** 追加章节到已有任务（已存在的章节不覆盖，避免把进度清零）。 */
    private suspend fun appendChapters(taskId: Long, specs: List<DownloadTaskRepository.ChapterSpec>) {
        val known = tasks.chapterList(taskId).map { it.chapterId }.toSet()
        val missing = specs.filterNot { it.chapterId in known }
        if (missing.isEmpty()) return
        tasks.upsertChapters(taskId, missing)
        tasks.recomputeTaskTotals(taskId)
        Timber.tag("dl").i("任务 %d 追加 %d 个章节", taskId, missing.size)
    }

    // ------------------------------------------------------------------
    // 控制
    // ------------------------------------------------------------------

    fun isRunning(taskId: Long): Boolean = jobs[taskId]?.isActive == true

    /** 开始（或继续）执行一个任务。已经在跑就什么都不做。 */
    fun start(taskId: Long) {
        if (isRunning(taskId)) return
        onTaskStarted()
        jobs[taskId] = scope.launch {
            try {
                runTask(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("dl").e(e, "任务 %d 执行失败", taskId)
                runCatching { tasks.markFailed(taskId, e.toUserMessage()) }
            } finally {
                jobs.remove(taskId)
            }
        }
    }

    /** 启动恢复流程之后调用：把打回队列的任务接着跑完。 */
    fun resumeQueued(taskIds: List<Long>) {
        taskIds.forEach { start(it) }
    }

    /** 暂停：先落库再取消协程，保证"暂停"一定赢过正在跑的那一轮。 */
    fun pause(taskId: Long) {
        scope.launch {
            runCatching { tasks.markPaused(taskId) }
            jobs[taskId]?.cancel()
        }
    }

    /** 恢复暂停的任务。 */
    fun resume(taskId: Long) {
        scope.launch {
            val task = tasks.findById(taskId) ?: return@launch
            if (task.status != DownloadStatus.PAUSED) return@launch
            tasks.requeueUnfinishedChapters(taskId)
            tasks.markQueued(taskId)
            tasks.recomputeTaskTotals(taskId)
            start(taskId)
        }
    }

    /** 取消：**不自动复活**（§D.3）。已下载的图片保留。 */
    fun cancel(taskId: Long) {
        scope.launch {
            runCatching { tasks.markCancelled(taskId) }
            jobs[taskId]?.cancel()
        }
    }

    /** 重试失败/取消的任务：只重跑没跑完的部分。 */
    fun retry(taskId: Long) {
        scope.launch {
            val task = tasks.findById(taskId) ?: return@launch
            if (task.status.isActive) return@launch
            tasks.requeueUnfinishedChapters(taskId)
            tasks.markQueued(taskId)
            tasks.recomputeTaskTotals(taskId)
            start(taskId)
        }
    }

    /**
     * 把任务里**尚未完成**的章节重新排队，并按磁盘现状重算进度。
     *
     * 用于进程被杀之后的恢复：已经下好的图片留在磁盘上，
     * 重跑时会被"文件存在即跳过"规则跳掉，只补缺口。
     */
    suspend fun requeueForResume(taskId: Long) {
        tasks.requeueUnfinishedChapters(taskId)
        tasks.recomputeTaskTotals(taskId)
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private suspend fun runTask(taskId: Long) {
        val task = tasks.findById(taskId) ?: return
        if (!task.status.isRunnable) {
            Timber.tag("dl").d("任务 %d 状态为 %s，不执行", taskId, task.status)
            return
        }

        val payload = JmDownloadPayload.decode(task.payload)
        if (payload == null) {
            tasks.markFailed(taskId, "任务参数已损坏，请删除后重新下载")
            return
        }
        val album = repo.findAlbum(payload.albumId)
        val folderName = album?.albumFolderName
        if (album == null || folderName.isNullOrBlank()) {
            tasks.markFailed(taskId, "这本漫画不在书库或目录名缺失，请重新加入书库后再下载")
            return
        }

        tasks.markRunning(taskId)
        tasks.updateProgress(taskId, stageText = STAGE_DOWNLOADING)
        tasks.recomputeTaskTotals(taskId)

        var previousRemaining = Int.MAX_VALUE
        while (true) {
            coroutineContext.ensureActive()
            if (!isRunnable(taskId)) return

            val pending = tasks.chapterList(taskId).filter { it.status != DownloadStatus.SUCCESS }
            if (pending.isEmpty()) break
            // 一轮下来没有任何章节完成 -> 再跑也是白跑（对齐参考实现的"需下载数不变就终止"）
            if (pending.size >= previousRemaining) {
                Timber.tag("dl").w("任务 %d 没有进展，停止重试（剩 %d 话）", taskId, pending.size)
                break
            }
            previousRemaining = pending.size

            for (row in pending) {
                coroutineContext.ensureActive()
                if (!isRunnable(taskId)) return
                downloadChapter(taskId, payload.albumId, folderName, row)
                tasks.recomputeTaskTotals(taskId)
            }
        }

        if (!isRunnable(taskId)) return

        val rows = tasks.chapterList(taskId)
        val unfinished = rows.filter { it.status != DownloadStatus.SUCCESS }
        if (unfinished.isEmpty()) {
            tasks.markSuccess(taskId)
        } else {
            val doneImages = rows.sumOf { it.doneImages }
            val totalImages = rows.sumOf { it.totalImages }
            tasks.markFailed(
                taskId,
                "${unfinished.size} 话没有下完（$doneImages/$totalImages 张），可点重试补下",
            )
        }
    }

    private suspend fun downloadChapter(
        taskId: Long,
        albumId: Long,
        albumFolder: String,
        row: DownloadTaskChapterEntity,
    ) {
        val chapterId = row.chapterId
        awaitNetworkPermission(taskId)
        if (!isRunnable(taskId)) return

        tasks.updateChapterProgress(taskId, chapterId, row.doneImages, DownloadStatus.RUNNING)

        val images = ensureImageList(albumId, chapterId)
        if (images.isEmpty()) {
            tasks.updateChapterProgress(
                taskId = taskId,
                chapterId = chapterId,
                doneImages = 0,
                status = DownloadStatus.FAIL,
                message = "没有拿到这一话的图片清单",
                allowRegression = true,
            )
            return
        }
        tasks.setChapterTotal(taskId, chapterId, images.size)

        val folder = row.chapterTitle?.takeIf { it.isNotBlank() } ?: chapterId.toString()
        val chapterDir = dirs.chapterDir(albumFolder, folder)
        val scrambleId = runCatching { repo.scrambleIdOf(chapterId) }
            .getOrDefault(JmDefaults.FALLBACK_SCRAMBLE_ID)
        // 解扰不可逆：一话共用一个 transform，保证同一张图只处理一次
        val transform = ImageDownloadTransform(scrambleId = scrambleId, chapterId = chapterId)

        val concurrency = environment.concurrency()
        val missing = { images.filterNot { isUsable(File(chapterDir, it)) } }
        var previousMissing = Int.MAX_VALUE
        var round = 0

        while (round < MAX_CHAPTER_ROUNDS) {
            coroutineContext.ensureActive()
            if (!isRunnable(taskId)) return
            val pending = missing()
            if (pending.isEmpty()) break
            // 缺图数没有下降 -> 这一话下不动了，交给用户点重试
            if (pending.size >= previousMissing) break
            previousMissing = pending.size
            round++

            awaitNetworkPermission(taskId)
            if (!isRunnable(taskId)) return

            val domain = environment.imageDomain()
            val providers: Map<String, suspend () -> String> = pending.associateWith { name ->
                suspend { JmDefaults.buildImgUrl(chapterId, name, domain) }
            }
            // 磁盘上已有的张数 + 本次成功的张数 = 这一话的完成数
            val doneCounter = AtomicInteger(images.size - pending.size)
            val result = downloader.downloadImages(
                images = providers,
                chapterDir = chapterDir,
                concurrency = concurrency,
                transform = transform::transform,
                onFileDone = { _, success ->
                    if (success) {
                        val done = doneCounter.incrementAndGet()
                        // 计划 §4.5：进度精确到"第 N 话 x/y 张"，每张图落库一次
                        tasks.updateChapterProgress(taskId, chapterId, done, DownloadStatus.RUNNING)
                    }
                },
            )
            if (result.cancelled) return
        }

        // 以磁盘现状为准做最终判定（允许回退：文件被删或损坏时进度要能纠正，§D.1）
        val remaining = missing().size
        val done = images.size - remaining
        tasks.updateChapterProgress(
            taskId = taskId,
            chapterId = chapterId,
            doneImages = done,
            status = if (remaining == 0) DownloadStatus.SUCCESS else DownloadStatus.FAIL,
            message = if (remaining == 0) null else "缺 $remaining 张，可点重试补下",
            allowRegression = true,
        )
    }

    /** 图片清单：本地有就用本地的，没有就现场拉一次 `/chapter` 并入库。 */
    private suspend fun ensureImageList(albumId: Long, chapterId: Long): List<String> {
        val local = repo.imagesOf(albumId, chapterId)
        if (local.isNotEmpty()) return local.map { it.imageFile }
        runCatching { repo.fetchAndSaveChapter(albumId, chapterId) }
            .onFailure { Timber.tag("dl").w(it, "拉取章节图片清单失败 chapterId=%d", chapterId) }
        return repo.imagesOf(albumId, chapterId).map { it.imageFile }
    }

    // ------------------------------------------------------------------
    // 网络闸门
    // ------------------------------------------------------------------

    /**
     * 等到网络条件允许为止。
     *
     * **不允许时进 `WAITING_NETWORK` 并挂起**，而不是抛错：
     * 计划 §9 第 9 条要求"等待网络"是一个独立状态，且不算失败。
     * 网络恢复（或用户在设置里放开策略）后这里自动返回，任务接着跑。
     */
    private suspend fun awaitNetworkPermission(taskId: Long) {
        val now = environment.networkState.value
        if (now.online && environment.allowsTransfer(now.metered)) return

        tasks.markWaitingNetwork(taskId, WAITING_REASON)
        environment.networkState.first { state ->
            state.online && environment.allowsTransfer(state.metered)
        }
        if (!isRunnable(taskId)) return
        tasks.markQueued(taskId)
        tasks.markRunning(taskId)
        tasks.updateProgress(taskId, stageText = STAGE_DOWNLOADING)
    }

    /**
     * 任务是否还能继续跑。
     *
     * **`PAUSED` 不算**：暂停之后即使网络刚好恢复，也不能自己把状态改回运行中。
     */
    private suspend fun isRunnable(taskId: Long): Boolean =
        tasks.findById(taskId)?.status?.isRunnable == true

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    companion object {
        /** 同一话最多重跑几轮（每轮都必须有进展，否则立刻结束）。 */
        const val MAX_CHAPTER_ROUNDS = 3

        /** 并发数的兜底值（设置里选"自动"时用它）。 */
        const val AUTO_CONCURRENCY = 4

        const val WAITING_REASON = "当前网络不允许下载，已暂停传输"

        /** 任务的阶段文案，任务页与通知共用。 */
        const val STAGE_DOWNLOADING = "下载图片"
    }
}
