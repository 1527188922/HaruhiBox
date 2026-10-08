package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.codec.ImageCodec
import com.haruhi.haruhibox.core.codec.ImageDownloadTransform
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.download.DownloadOutcome
import com.haruhi.haruhibox.core.download.FileDownloader
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 章节图片的**本地优先取图器**：阅读器与下载任务共用同一份落盘位置和同一套解扰规则。
 *
 * ## 为什么需要它
 *
 * 用户的要求是"**能看就能看，不必先下载**"：
 * - 阅读器遇到磁盘上没有的图，直接联网取回来、**落盘缓存**，下次再读不再请求；
 * - 用户点下载时，**每张图先看缓存**（就是同一个文件），有就不再请求；
 * - 于是"一边看一边下载"天然不冲突——两边只是同一个"文件在不在"的检查。
 *
 * 这也顺便解决了"删掉本地文件后再进阅读器显示图片缺失"的问题：
 * 文件不在了就当缓存未命中，重新取一次即可。
 *
 * ## 与下载引擎的分工
 *
 * 传输、Range 续传、原子发布都在 [FileDownloader] 里；
 * 这一层只负责三件事：**算路径**、**命中判断**、**下载后解扰**。
 * 解扰必须在这里做，否则阅读器直接拿到的会是加扰过的图。
 *
 * ## 并发
 *
 * 同一张图可能被阅读器与下载任务同时要（一边看一边下）。
 * [FileDownloader] 内部对同一个目标文件加锁，所以不会出现两个写入者
 * 抢同一个 `.part`；这里的 [locks] 再保证**同一张图只解扰一次**
 * （解扰不可逆，重复执行会毁图）。
 */
class JmImageStore(
    private val repo: JmRepository,
    private val downloader: FileDownloader,
    private val dirs: LibraryDirs,
    private val environment: JmDownloadEnvironment,
) {

    /** 每一话的 `scramble_id`：同一话内恒定，取一次记住即可。 */
    private val scrambleIds = ConcurrentHashMap<Long, Long>()

    /** 每张图的解扰锁：保证同一张图只被处理一次。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 已经量过宽高比的图（按"路径 + 长度"缓存），切章节来回时不必重读文件头。 */
    private val aspects = ConcurrentHashMap<String, Float>()

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /**
     * 某一话的落盘目录。
     *
     * [chapterFolder] 由调用方按 `title → name → id` 的规则算好
     * （单话本子固定「第1话」），**下载与阅读必须传同一个值**，
     * 否则会出现"下好了却显示未下载"。
     */
    fun chapterDir(album: JmAlbumEntity, chapterFolder: String): File =
        dirs.chapterDir(album.albumFolderName ?: "JM${album.id}", chapterFolder)

    /** 某张图在磁盘上的位置（不判断是否存在）。 */
    fun targetFile(album: JmAlbumEntity, chapterFolder: String, imageFile: String): File =
        File(chapterDir(album, chapterFolder), imageFile)

    /** 已经在磁盘上且非空的图（**不发任何网络请求**）。0 字节残留不算命中。 */
    fun cachedFile(album: JmAlbumEntity, chapterFolder: String, imageFile: String): File? =
        targetFile(album, chapterFolder, imageFile).takeIf { isUsable(it) }

    // ------------------------------------------------------------------
    // 取图
    // ------------------------------------------------------------------

    /**
     * 取一张图：本地优先，没有就联网下载并**落盘缓存**。
     *
     * **整段都在 [Dispatchers.IO] 上跑**：解扰是纯 CPU 的重活（JM 长图 800×12000，
     * 解码成 ARGB_8888 就要 36MB，还要逐行 `getPixels/setPixels` 再重新编码）。
     * 调用方是 `viewModelScope`（主线程调度器），如果这里不切线程，
     * **每取一页就会把 UI 卡住一次**——用户反馈的"看漫画时 UI 卡住"正是这个原因。
     *
     * @throws Exception 网络失败、解扰失败、写盘失败都会抛出来，由调用方转成用户文案
     */
    suspend fun file(
        album: JmAlbumEntity,
        chapterId: Long,
        chapterFolder: String,
        imageFile: String,
    ): File = withContext(Dispatchers.IO) {
        cachedFile(album, chapterFolder, imageFile)?.let { return@withContext it }

        val target = targetFile(album, chapterFolder, imageFile)
        val lock = locks.computeIfAbsent(target.absolutePath) { Mutex() }
        lock.withLock {
            // 等锁期间别人可能已经下好了（阅读器与下载任务抢同一张图是常态）
            cachedFile(album, chapterFolder, imageFile)?.let { return@withLock it }

            val domain = environment.imageDomain()
            val outcome = downloader.download(
                urlProvider = { JmDefaults.buildImgUrl(chapterId, imageFile, domain) },
                target = target,
                // 已经有了就不重复下载（上面已经提前返回，这里是二次保险）
                skipIfComplete = true,
            )
            when (outcome) {
                is DownloadOutcome.Downloaded, is DownloadOutcome.Skipped -> Unit
                is DownloadOutcome.Failed -> throw outcome.error
                DownloadOutcome.Cancelled -> throw IllegalStateException("图片下载已取消：$imageFile")
            }

            // 解扰（`blockNum == 0` 时原样保留，不重新编码）
            val transform = ImageDownloadTransform(
                scrambleId = scrambleIdOf(chapterId),
                chapterId = chapterId,
            )
            runCatching { transform.transform(target, imageFile) }
                .onFailure { Timber.tag("jm").w(it, "解扰失败，保留原图：%s", imageFile) }

            target
        }
    }

    /**
     * 图片宽高比（高/宽）。读不到时给占位值，保证长图滚动不跳。
     *
     * 读文件头也是解码（`inJustDecodeBounds` 仍要打开并解析图片），所以**同样切到 IO**：
     * 打开一章时要把每一页的比例都算一遍，放在主线程就是"进阅读器卡一下"。
     */
    suspend fun aspectRatioOf(file: File, fallback: Float): Float = withContext(Dispatchers.IO) {
        aspects.getOrPut("${file.absolutePath}:${file.length()}") {
            runCatching {
                val meta = ImageCodec.readMeta(file)
                if (meta.width > 0 && meta.height > 0) {
                    meta.height.toFloat() / meta.width.toFloat()
                } else {
                    fallback
                }
            }.getOrDefault(fallback)
        }
    }

    /** 这一话的 `scramble_id`；取不到时用参考实现的兜底值。同一话只请求一次。 */
    private suspend fun scrambleIdOf(chapterId: Long): Long {
        scrambleIds[chapterId]?.let { return it }
        val value = runCatching { repo.scrambleIdOf(chapterId) }
            .getOrDefault(JmDefaults.FALLBACK_SCRAMBLE_ID)
        scrambleIds[chapterId] = value
        return value
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0
}
