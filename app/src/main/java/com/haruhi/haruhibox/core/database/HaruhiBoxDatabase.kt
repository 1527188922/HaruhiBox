package com.haruhi.haruhibox.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.haruhi.haruhibox.core.database.entity.BilibiliVideoEntity
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.haruhibox.core.database.entity.DownloadTaskEntity
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.database.entity.PlaybackPositionEntity

/**
 * 应用数据库。
 *
 * 表清单（业务表 5 张 + Android 侧新增 4 张）：
 * - `t_jm_album` / `t_jm_chapter_image` / `t_jm_favorite` / `t_jm_favorite_album`
 * - `t_bilibili_video`
 * - `t_download_task` / `t_download_task_chapter`（持久化任务，§4.5）
 * - `t_playback_position`（阅读/播放位置，§4.5 末条）
 * - `t_jm_search_history`（在线搜索历史，§2.4 第 1 条）
 *
 * 迁移策略：**唯一权威是 `@Database(version)` + 手写 `Migration`**，
 * 不使用 `fallbackToDestructiveMigration`——用户的下载记录与漫画库是真实资产，
 * 一次 destructive 迁移就是数据全丢。
 */
@Database(
    entities = [
        JmAlbumEntity::class,
        JmChapterImageEntity::class,
        JmFavoriteEntity::class,
        JmFavoriteAlbumEntity::class,
        BilibiliVideoEntity::class,
        DownloadTaskEntity::class,
        DownloadTaskChapterEntity::class,
        PlaybackPositionEntity::class,
        JmSearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class HaruhiBoxDatabase : RoomDatabase() {

    abstract fun jmAlbumDao(): JmAlbumDao
    abstract fun jmChapterDao(): JmChapterImageDao
    abstract fun jmFavoriteDao(): JmFavoriteDao
    abstract fun biliVideoDao(): BilibiliVideoDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun jmSearchHistoryDao(): JmSearchHistoryDao

    companion object {
        const val NAME = "haruhibox.db"

        fun create(context: Context): HaruhiBoxDatabase =
            Room.databaseBuilder(context.applicationContext, HaruhiBoxDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** Room 的类型转换器。枚举以 `name` 存储，便于以后重排枚举顺序而不破坏旧数据。 */
class DatabaseConverters {

    @TypeConverter
    fun downloadStatusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToDownloadStatus(value: String): DownloadStatus =
        DownloadStatus.entries.firstOrNull { it.name == value } ?: DownloadStatus.FAIL

    @TypeConverter
    fun downloadTypeToString(value: DownloadType): String = value.name

    @TypeConverter
    fun stringToDownloadType(value: String): DownloadType =
        DownloadType.entries.firstOrNull { it.name == value }
            ?: error("未知的下载任务类型：$value")

    /** 列表统一用换行分隔（与 JM 的 `images` 字段、参考实现的 `saveOrUpdateChapterImages` 一致）。 */
    @TypeConverter
    fun listToString(value: List<String>?): String? = value?.joinToString("\n")

    @TypeConverter
    fun stringToList(value: String?): List<String>? =
        value?.takeIf { it.isNotEmpty() }?.split("\n")
}

/** 迁移集合。v1 是初始版本，暂时为空；每次加字段/表都在这里补一条。 */
object Migrations {
    val ALL: Array<androidx.room.migration.Migration> = emptyArray()
}
