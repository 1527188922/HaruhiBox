package com.haruhi.haruhibox.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `t_jm_album`：**漫画基础数据表**（永久缓存）。
 *
 * 在线 `/album` 拿到的详情都落在这里。它**不表达**"在不在书库"，
 * 也**不表达**"看没看过"：
 * - 书库成员关系在 [JmLibraryEntity]（`t_jm_library`），只存 `album_id` 与入库时间；
 * - 最近预览在 [JmRecentAlbumEntity]（`t_jm_recent_album`），只存 `id` 与浏览时间。
 *
 * 两张关联表都通过 `id` 引用本表，列表/详情渲染时与本表拼接。所以
 * **清空最近浏览、移出书库都不会删除这里的任何数据**——这样"请求漫画详情接口"
 * 与"加入最近预览"才能真正解耦，同时保留下载页、阅读器、精确匹配搜索
 * 依赖的本地详情缓存。
 *
 * 字段只保留界面/逻辑真正使用到的列（其余对齐参考实现但本工程没用到的一律去掉）。
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
    @ColumnInfo(name = "description") val description: String? = null,
    /** 章节列表的权威来源（JSON 字符串，见 `JmRepository.chaptersOf`）。 */
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    /**
     * 本地收藏标记（重新拉取 JM 数据不覆盖）。
     *
     * **注意**：不要给这个字段设默认值，否则更新已有记录时会把收藏状态重置。
     * 它属于"书库语义"：移出书库时会被清零（与旧行为一致——旧实现直接删行）。
     */
    @ColumnInfo(name = "collected") val collected: Boolean? = null,
)

/**
 * `t_jm_library`：**书库成员表**。只存"这本在书库里"与首次入库时间。
 *
 * 为什么不把成员关系放在基础表上：基础表是永久缓存，而"移出书库"要能把这本
 * 从书库列表里去掉——成员关系必须可删、且删掉后基础数据仍在。
 * 书库列表渲染时与 [JmAlbumEntity] 按 `album_id` 拼接。
 *
 * `create_time` 是**第一次入库时间**，书库默认排序用它（刷新详情不改变它）。
 */
@Entity(tableName = "t_jm_library")
data class JmLibraryEntity(
    @PrimaryKey @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
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

/**
 * `t_jm_recent_album`：**最近预览**。只记录"进过详情页/阅读页"这一事实与浏览时间。
 *
 * 内容字段全部去掉：渲染时与 [JmAlbumEntity]（基础数据表）按 `id` 拼接，
 * 所以条目不会因为"有没有联网数据"而分叉，接口数据也只存一份。
 *
 * 写入**只发生在进入详情页或阅读页时**（`JmRepository.touchRecent`）：
 * 拉取 `/album` 接口本身不写这张表（用户要求：请求接口 ≠ 加入最近预览）。
 */
@Entity(
    tableName = "t_jm_recent_album",
    indices = [Index(value = ["viewed_time"])],
)
data class JmRecentAlbumEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    /** 浏览时间（毫秒）。**只在点进详情页/阅读页时更新**；刷新详情不改它。 */
    @ColumnInfo(name = "viewed_time") val viewedTime: Long = System.currentTimeMillis(),
)
