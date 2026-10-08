package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.database.JmChapterImageDao
import com.haruhi.haruhibox.core.database.JmRecentAlbumDao
import com.haruhi.haruhibox.core.database.PlaybackPositionDao
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.PlaybackPositionEntity
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * 阅读位置与"这一话能不能离线读"的查询（计划 §9 第 8 条）。
 *
 * ## 位置用什么定位
 *
 * `本子 + 章节 + 图片文件名 + 页内比例`。**用文件名而不是页码**：
 * 章节重新拉取清单时页数可能变（JM 会补页），页码会错位，文件名不会。
 *
 * ## 为什么单独一个仓库
 *
 * [JmRepository] 已经承担了在线接口、入库、收藏、书库筛选；
 * 阅读位置属于"阅读器"这条线，混进去只会让它更难读。
 * 两者的边界很清楚：**这个类不联网**。
 */
class JmReadingRepository(
    private val positionDao: PlaybackPositionDao,
    private val chapterDao: JmChapterImageDao,
    private val recentDao: JmRecentAlbumDao,
    private val dirs: LibraryDirs,
) {

    // ------------------------------------------------------------------
    // 位置读写
    // ------------------------------------------------------------------

    fun observePosition(albumId: Long): Flow<ReadingPosition?> =
        positionDao.observe(KIND, albumId.toString()).map { it?.toReadingPosition() }

    suspend fun positionOf(albumId: Long): ReadingPosition? =
        positionDao.find(KIND, albumId.toString())?.toReadingPosition()

    /**
     * 保存阅读位置。
     *
     * `offsetRatio` 是**页内**滚动比例（长图一屏放不下时用），不是整章比例——
     * 整章比例会随着"读到第几张"一起变，两者混在一起恢复时必然错位。
     */
    suspend fun savePosition(
        albumId: Long,
        chapterId: Long,
        imageFile: String?,
        imageIndex: Int,
        offsetRatio: Float,
    ) {
        positionDao.upsert(
            PlaybackPositionEntity(
                kind = KIND,
                bizKey = albumId.toString(),
                chapterId = chapterId,
                imageFile = imageFile,
                // JM 用 position 存图片序号（0 起），恢复时不必再按文件名反查
                position = imageIndex.toLong(),
                offsetRatio = offsetRatio.coerceIn(0f, 1f),
                updateTime = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearPosition(albumId: Long) = positionDao.delete(KIND, albumId.toString())

    // ------------------------------------------------------------------
    // 目录与文件
    // ------------------------------------------------------------------

    /** 某一话的章节文件夹名。章节清单为空时返回 null（还没拉过清单）。 */
    suspend fun chapterFolderName(albumId: Long, chapterId: Long): String? {
        // 单话本子：章节 id 就是本子 id，目录名固定「第1话」。
        // 这条规则与 JmRepository.chaptersOf 一致——否则"下载写到哪个目录"和
        // "阅读器去哪个目录找图"会对不上。
        if (chapterId == albumId) return JmRepository.SINGLE_CHAPTER_TITLE
        val first = chapterDao.images(albumId, chapterId).firstOrNull() ?: return null
        return first.chapterTitle?.takeIf { it.isNotBlank() }
            ?: first.chapterName?.takeIf { it.isNotBlank() }
            ?: chapterId.toString()
    }

    /** 某一话已入库的图片清单（不管有没有下载）。 */
    suspend fun imagesOf(albumId: Long, chapterId: Long): List<JmChapterImageEntity> =
        chapterDao.images(albumId, chapterId)

    fun observeImages(albumId: Long, chapterId: Long): Flow<List<JmChapterImageEntity>> =
        chapterDao.observeImages(albumId, chapterId)

    /**
     * 书库页"继续阅读"卡片的数据源：**最近一次读过位置的本子**。
     *
     * 返回 `null` 表示还没有任何阅读记录（书库页就不显示卡片，§9 第 4 条
     * "有阅读记录时显示紧凑的继续阅读卡片"）。
     */
    fun observeLastRead(): Flow<ReadingPosition?> =
        positionDao.observeLastForKind(KIND).map { it?.toReadingPosition() }

    /** 最近浏览表里取本子信息（书库可能没有这本，例如只在最近浏览里读过）。 */
    suspend fun albumForDisplay(albumId: Long) = recentDao.findById(albumId)

    private fun PlaybackPositionEntity.toReadingPosition() = ReadingPosition(
        albumId = bizKey.toLongOrNull() ?: 0L,
        chapterId = chapterId,
        imageFile = imageFile,
        imageIndex = position.toInt(),
        offsetRatio = offsetRatio,
        updatedAt = updateTime,
    )

    companion object {
        /** `t_playback_position.kind`：JM 漫画。 */
        const val KIND = "JM"
    }
}

/**
 * 一次阅读位置快照。
 *
 * [imageFile] 为 null 表示"只记到章节"（例如刚进章节就退出），
 * 阅读器会从这一话的第一张开始。
 */
data class ReadingPosition(
    val albumId: Long,
    val chapterId: Long?,
    val imageFile: String?,
    /** 图片序号（0 起）。与 [imageFile] 冗余，方便快速恢复。 */
    val imageIndex: Int,
    /** 页内滚动比例（长图一屏放不下时用）。 */
    val offsetRatio: Float,
    val updatedAt: Long,
) {
    /** 位置是否可用（没有章节号的位置只能当"读过这本"用）。 */
    val isUsable: Boolean get() = chapterId != null
}
