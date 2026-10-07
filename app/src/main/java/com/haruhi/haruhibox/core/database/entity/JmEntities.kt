package com.haruhi.haruhibox.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `t_jm_album`：JM 本子。主键 `id` = JM 号（**非自增**）。
 *
 * 字段对应当前 SQLite 表（`JmAlbumSqlite`）。字符串时间戳保持参考实现的格式，
 * 不做类型转换，避免迁移时出现语义差异。
 */
@Entity(
    tableName = "t_jm_album",
    indices = [
        Index(value = ["collected"]),
        Index(value = ["name"]),
    ],
)
data class JmAlbumEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "album_folder_name") val albumFolderName: String? = null,
    @ColumnInfo(name = "images") val images: String? = null,
    @ColumnInfo(name = "add_time") val addTime: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "total_views") val totalViews: String? = null,
    @ColumnInfo(name = "likes") val likes: String? = null,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "comment_total") val commentTotal: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "works") val works: String? = null,
    @ColumnInfo(name = "actors") val actors: String? = null,
    @ColumnInfo(name = "related_list") val relatedList: String? = null,
    @ColumnInfo(name = "liked") val liked: Boolean? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean? = null,
    /**
     * 本地收藏标记（仅存本库；重新拉取 JM 数据不覆盖）。
     *
     * **注意**：不要给这个字段设默认值，否则更新已有记录时会把收藏状态重置
     * （参考实现 `JmAlbumSqlite` 的注释明确写了这一点）。
     */
    @ColumnInfo(name = "collected") val collected: Boolean? = null,
    @ColumnInfo(name = "is_aids") val isAids: Boolean? = null,
    @ColumnInfo(name = "price") val price: String? = null,
    @ColumnInfo(name = "purchased") val purchased: String? = null,
    @ColumnInfo(name = "raw") val raw: String? = null,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "modify_time") val modifyTime: String? = null,
)

/**
 * `t_jm_chapter_image`：本子章节的图片清单（一章一图一行）。
 *
 * 这张表是"章节清单"的**权威来源**：详情页的章节列表、图片清单都来自它，
 * 而不是从"已下载的图片文件"反推（计划 §D.1 的高优先级项：
 * 「必须有稳定的章节图片清单，才能准确判断漏图」）。
 *
 * 参考实现对该表有专门的自定义查询（`JmChapterImageSqliteMapper.xml` 的
 * `selectChapterList` / `selectImages`），移植时照抄 SQL 语义——
 * 前者是 `GROUP BY chapter_id … MIN(id) … ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)`。
 */
@Entity(
    tableName = "t_jm_chapter_image",
    indices = [
        Index(value = ["album_id", "chapter_id"]),
        Index(value = ["album_id", "chapter_id", "image_sort"]),
    ],
)
data class JmChapterImageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_sort") val chapterSort: String? = null,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String? = null,
    @ColumnInfo(name = "chapter_name") val chapterName: String? = null,
    @ColumnInfo(name = "chapter_add_time") val chapterAddTime: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "liked") val liked: Boolean? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean? = null,
    /** 接口返回的原始文件名，同时就是磁盘上的文件名。 */
    @ColumnInfo(name = "image_file") val imageFile: String,
    @ColumnInfo(name = "image_sort") val imageSort: Int? = null,
)

/**
 * `t_jm_favorite`：收藏夹（文件夹）。`name` 唯一。
 *
 * 默认收藏夹名 `默认收藏夹` 由初始化流程写入，且不允许删除/重命名。
 */
@Entity(
    tableName = "t_jm_favorite",
    indices = [Index(value = ["name"], unique = true)],
)
data class JmFavoriteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** 展示排序，值越小越靠前 */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "modify_time") val modifyTime: String? = null,
)

/**
 * `t_jm_favorite_album`：收藏夹 ↔ 本子的多对多关联。
 * 唯一索引 `(favorite_id, album_id)` 保证同一收藏夹内不重复。
 */
@Entity(
    tableName = "t_jm_favorite_album",
    indices = [Index(value = ["favorite_id", "album_id"], unique = true)],
)
data class JmFavoriteAlbumEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "favorite_id") val favoriteId: Long,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
)
