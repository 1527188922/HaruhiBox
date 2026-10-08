package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "把一本漫画从这台设备上彻底删掉"的编排。
 *
 * 一本漫画在本机有**四处**痕迹，缺一处就会留下"删不干净"的观感：
 *
 * | 位置 | 内容 | 谁负责 |
 * |---|---|---|
 * | `files/library/jm/<本子目录>` | 章节图片 | [JmRepository.deleteAlbumFiles] |
 * | 系统下载目录 `Download/HaruHiBox` | 导出的 zip / pdf | [JmExportManager.deleteOutputs] |
 * | Coil 磁盘缓存 `files/covers` | 封面 | [CoverCache.evict] |
 * | 数据库五张表 | 书库 / 章节清单 / 收藏关联 / 最近浏览 / 阅读位置 | [JmRepository.deleteAlbums] |
 *
 * 两个入口的差别只有"删不删数据库记录"：
 * - **删除本地文件**：只清前三处，保留书库记录（用户要求第 6 点）；
 * - **从书库移除**：四处全清（用户要求第 7 点）。
 *
 * 封面缓存是 Coil 的磁盘缓存（按 URL 哈希存文件），所以只能按 key 逐条失效，
 * 不能"删掉某个文件"——这也是它单独抽一个接口的原因。
 */
interface CoverCache {
    /** 让这张封面从内存与磁盘缓存里失效；下次显示会重新联网取。 */
    fun evict(albumId: Long)
}

/** 删除结果，用于给用户一句准确的提示。 */
data class AlbumCleanup(
    val freedBytes: Long,
    val removedExports: Int,
    val removedRecords: Boolean,
)

/**
 * 删除编排。
 *
 * @param cancelActiveTasks 从书库移除时要先停掉还在跑的任务，
 *        否则下载/导出会继续往刚被删掉的目录里写东西。
 */
class JmAlbumCleaner(
    private val repo: JmRepository,
    private val exports: JmExportManager,
    private val tasks: com.haruhi.haruhibox.core.download.DownloadTaskRepository,
    private val coverCache: CoverCache,
) {

    /** 只删本地文件：图片 + 导出成品 + 封面缓存，**保留数据库记录**。 */
    suspend fun deleteFiles(albums: List<JmAlbumEntity>): AlbumCleanup = withContext(Dispatchers.IO) {
        var freed = 0L
        var removedExports = 0
        albums.forEach { album ->
            freed += runCatching { repo.deleteAlbumFiles(album.albumFolderName) }
                .onFailure { Timber.tag("jm").w(it, "删除本子目录失败：%s", album.albumFolderName) }
                .getOrDefault(0L)
            removedExports += exports.deleteOutputs(album)
        }
        AlbumCleanup(freed, removedExports, removedRecords = false)
    }

    /** 从书库移除：**记录 + 文件 + 成品 + 封面**全清。 */
    suspend fun removeFromLibrary(albums: List<JmAlbumEntity>): AlbumCleanup {
        val ids = albums.map { it.id }
        cancelActiveTasks(ids)
        val files = deleteFiles(albums)
        albums.forEach { coverCache.evict(it.id) }
        runCatching { repo.deleteAlbums(ids) }
            .onFailure { Timber.tag("jm").w(it, "删除书库记录失败：%s", ids) }
        return files.copy(removedRecords = true)
    }

    private suspend fun cancelActiveTasks(albumIds: List<Long>) {
        albumIds.forEach { id ->
            runCatching {
                val active = tasks.activeTask(DownloadType.JM_ALBUM, id.toString())
                if (active != null && active.status != DownloadStatus.PAUSED) {
                    tasks.markCancelled(active.id)
                }
            }
        }
    }
}
