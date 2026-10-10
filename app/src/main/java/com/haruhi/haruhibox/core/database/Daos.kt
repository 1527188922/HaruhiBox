package com.haruhi.haruhibox.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.haruhi.haruhibox.core.database.entity.BilibiliVideoEntity
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.haruhibox.core.database.entity.DownloadTaskEntity
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmRecentAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.database.entity.PlaybackPositionEntity
import kotlinx.coroutines.flow.Flow

// ======================================================================
// JM 本子
// ======================================================================

@Dao
interface JmAlbumDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(album: JmAlbumEntity): Long

    @Update
    suspend fun update(album: JmAlbumEntity)

    @Query("SELECT * FROM t_jm_album WHERE id = :id")
    suspend fun findById(id: Long): JmAlbumEntity?

    @Query("SELECT * FROM t_jm_album WHERE id = :id")
    fun observeById(id: Long): Flow<JmAlbumEntity?>

    @Query("SELECT COUNT(*) FROM t_jm_album WHERE id IN (:ids)")
    suspend fun countExisting(ids: List<Long>): Int

    @Query("SELECT id FROM t_jm_album WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    /**
     * 书库列表（含筛选与排序）。
     *
     * **排序：入库时间倒序**（用户要求第 2 点）——`create_time` 是本子第一次进书库的时间，
     * 之后刷新详情只会改 `modify_time`，所以用 `create_time` 才是"入库顺序"。
     * 时间戳是 `yyyy-MM-dd HH:mm:ss` 文本，字典序即时间序；同一秒内用 id 兜底。
     *
     * **指定收藏夹时改按"加入这个夹的时间"倒序**（用户要求第 4 点）：
     * 夹内的顺序应该是"最近加进来的在最前"，和书库的入库顺序是两回事，
     * 所以第一个排序键取关联表 `t_jm_favorite_album.create_time`（用相关子查询取当前夹的关联时间）。
     *
     * 筛选条件全部用 `(:param IS NULL OR ...)` 形式，避免为每个组合写一条 SQL。
     *
     * **标签多选是 OR 语义**，与参考实现 `applyTagFilter` 一致：
     * `tags` 列存的是 JSON 数组字符串，所以用 `INSTR(tags, '"标签"') > 0` 做子串匹配。
     * 参考实现用 `wrapper.and(... or ...)` 拼动态 OR；Room 不支持动态 SQL，
     * 这里用子查询 + `HAVING SUM(...) > 0` 表达同一语义（列表为空时条件恒假，
     * 所以调用方必须传空列表表示"不筛选"）。
     * 要求标签本身不含 `"` 与 `,`，否则会破坏匹配（JM 的标签不含这两种字符）。
     */
    @Query(
        """
        SELECT * FROM t_jm_album
        WHERE (:keyword IS NULL OR name LIKE '%' || :keyword || '%'
               OR author LIKE '%' || :keyword || '%'
               OR tags LIKE '%' || :keyword || '%'
               OR CAST(id AS TEXT) = :keyword)
          AND (:onlyCollected IS NULL OR collected = :onlyCollected)
          AND (:author IS NULL OR author LIKE '%' || :author || '%')
          AND (:tagsCsv = '' OR EXISTS (
                SELECT 1 FROM (
                    SELECT (INSTR(tags, '"' || value || '"') > 0) AS hit
                    FROM (SELECT TRIM(value) AS value FROM (
                        WITH RECURSIVE split(value, rest) AS (
                            SELECT '', :tagsCsv || ','
                            UNION ALL
                            SELECT SUBSTR(rest, 0, INSTR(rest, ',')),
                                   SUBSTR(rest, INSTR(rest, ',') + 1)
                            FROM split WHERE rest <> ''
                        ) SELECT value FROM split WHERE value <> ''
                    ))
                ) WHERE hit = 1))
          AND (:favoriteId IS NULL OR id IN (
                SELECT album_id FROM t_jm_favorite_album WHERE favorite_id = :favoriteId))
        ORDER BY
            CASE WHEN :favoriteId IS NULL THEN NULL ELSE (
                SELECT fa.create_time FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = :favoriteId AND fa.album_id = t_jm_album.id
            ) END DESC,
            COALESCE(create_time, '') DESC,
            id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLibrary(
        keyword: String?,
        onlyCollected: Boolean?,
        author: String?,
        tagsCsv: String,
        favoriteId: Long?,
        limit: Int,
        offset: Int,
    ): Flow<List<JmAlbumEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM t_jm_album
        WHERE (:keyword IS NULL OR name LIKE '%' || :keyword || '%'
               OR author LIKE '%' || :keyword || '%'
               OR tags LIKE '%' || :keyword || '%'
               OR CAST(id AS TEXT) = :keyword)
          AND (:onlyCollected IS NULL OR collected = :onlyCollected)
          AND (:author IS NULL OR author LIKE '%' || :author || '%')
          AND (:tagsCsv = '' OR EXISTS (
                SELECT 1 FROM (
                    SELECT (INSTR(tags, '"' || value || '"') > 0) AS hit
                    FROM (SELECT TRIM(value) AS value FROM (
                        WITH RECURSIVE split(value, rest) AS (
                            SELECT '', :tagsCsv || ','
                            UNION ALL
                            SELECT SUBSTR(rest, 0, INSTR(rest, ',')),
                                   SUBSTR(rest, INSTR(rest, ',') + 1)
                            FROM split WHERE rest <> ''
                        ) SELECT value FROM split WHERE value <> ''
                    ))
                ) WHERE hit = 1))
          AND (:favoriteId IS NULL OR id IN (
                SELECT album_id FROM t_jm_favorite_album WHERE favorite_id = :favoriteId))
        """,
    )
    fun observeLibraryCount(
        keyword: String?,
        onlyCollected: Boolean?,
        author: String?,
        tagsCsv: String,
        favoriteId: Long?,
    ): Flow<Int>

    /** 详情页的收藏开关。**只改这一个字段**，不动其它（避免覆盖已拉取的在线数据）。 */
    @Query("UPDATE t_jm_album SET collected = :collected, modify_time = :modifyTime WHERE id = :id")
    suspend fun setCollected(id: Long, collected: Boolean, modifyTime: String)

    @Query("DELETE FROM t_jm_album WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM t_jm_album WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM t_jm_album")
    suspend fun all(): List<JmAlbumEntity>
}

// ======================================================================
// JM 章节图片
// ======================================================================

@Dao
interface JmChapterImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<JmChapterImageEntity>)

    /**
     * 章节列表。**照抄参考实现 `JmChapterImageSqliteMapper.xml` 的 `selectChapterList`**：
     * 按 `chapter_id` 分组，取 `MIN(id)` / `MIN(image_file)` / `MIN(image_sort)` 作为代表行，
     * 排序是 `CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)`。
     *
     * 之所以照抄：详情页要展示"本地已缓存的章节"（§9 第 7 条），
     * 而这张表里一章有几十行图片，必须分组后才能当章节列表用。
     */
    @Query(
        """
        SELECT MIN(id) AS id,
               album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite,
               MIN(image_file) AS image_file,
               MIN(image_sort) AS image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId
        GROUP BY album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
                 chapter_add_time, series_id, liked, is_favorite
        ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)
        """,
    )
    fun observeChapterList(albumId: Long): Flow<List<JmChapterImageEntity>>

    @Query(
        """
        SELECT MIN(id) AS id,
               album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite,
               MIN(image_file) AS image_file,
               MIN(image_sort) AS image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId
        GROUP BY album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
                 chapter_add_time, series_id, liked, is_favorite
        ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)
        """,
    )
    suspend fun chapterList(albumId: Long): List<JmChapterImageEntity>

    /** 某章的图片清单，按 `image_sort, id` 升序（照抄 `selectImages`）。 */
    @Query(
        """
        SELECT id, album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, image_file, image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId
        ORDER BY image_sort, id
        """,
    )
    fun observeImages(albumId: Long, chapterId: Long): Flow<List<JmChapterImageEntity>>

    @Query(
        """
        SELECT id, album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, image_file, image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId
        ORDER BY image_sort, id
        """,
    )
    suspend fun images(albumId: Long, chapterId: Long): List<JmChapterImageEntity>

    @Query("SELECT COUNT(*) FROM t_jm_chapter_image WHERE album_id = :albumId AND chapter_id = :chapterId")
    suspend fun imageCount(albumId: Long, chapterId: Long): Int

    @Query("SELECT chapter_id FROM t_jm_chapter_image WHERE album_id = :albumId GROUP BY chapter_id")
    suspend fun chapterIds(albumId: Long): List<Long>

    @Query("DELETE FROM t_jm_chapter_image WHERE album_id = :albumId AND chapter_id = :chapterId")
    suspend fun deleteChapter(albumId: Long, chapterId: Long)

    @Query("DELETE FROM t_jm_chapter_image WHERE album_id = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    /**
     * 章节图片入库：先删掉该章旧行再插新行。
     *
     * 为什么整章替换而不是 upsert：接口返回的图片清单是**权威快照**，
     * 逐行 upsert 会让"服务端已删除的图片"永远留在库里，导致详情页显示的图片数比实际多。
     * 整章替换保证本地清单与接口一致（§D.1：「必须有稳定的章节图片清单，才能准确判断漏图」）。
     */
    @Transaction
    suspend fun replaceChapter(albumId: Long, chapterId: Long, images: List<JmChapterImageEntity>) {
        deleteChapter(albumId, chapterId)
        insertAll(images)
    }
}

// ======================================================================
// JM 收藏夹
// ======================================================================

@Dao
interface JmFavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: JmFavoriteEntity): Long

    @Query("SELECT * FROM t_jm_favorite ORDER BY sort_order, id")
    fun observeAll(): Flow<List<JmFavoriteEntity>>

    @Query("SELECT * FROM t_jm_favorite ORDER BY sort_order, id")
    suspend fun all(): List<JmFavoriteEntity>

    @Query("SELECT * FROM t_jm_favorite WHERE name = :name")
    suspend fun findByName(name: String): JmFavoriteEntity?

    @Query("SELECT COUNT(*) FROM t_jm_favorite")
    suspend fun count(): Int

    @Query("UPDATE t_jm_favorite SET name = :name, modify_time = :modifyTime WHERE id = :id")
    suspend fun rename(id: Long, name: String, modifyTime: String)

    @Query("UPDATE t_jm_favorite SET sort_order = :sortOrder, modify_time = :modifyTime WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, modifyTime: String)

    /** 删除收藏夹：只删夹与关联，**不动**书库记录和本地文件（§9 第 6 条）。 */
    @Transaction
    suspend fun deleteWithLinks(id: Long) {
        deleteLinksOf(id)
        deleteById(id)
    }

    @Query("DELETE FROM t_jm_favorite WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: JmFavoriteAlbumEntity): Long

    @Query("DELETE FROM t_jm_favorite_album WHERE favorite_id = :favoriteId AND album_id = :albumId")
    suspend fun unlink(favoriteId: Long, albumId: Long)

    @Query("DELETE FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    suspend fun deleteLinksOf(favoriteId: Long)

    @Query("DELETE FROM t_jm_favorite_album WHERE album_id = :albumId")
    suspend fun deleteLinksOfAlbum(albumId: Long)

    @Query("SELECT favorite_id FROM t_jm_favorite_album WHERE album_id = :albumId")
    suspend fun favoriteIdsOf(albumId: Long): List<Long>

    /**
     * 观察一个本子所属的收藏夹（详情页的收藏夹面板与"收藏"按钮靠它保持同步）。
     *
     * 之前只在页面加载时查一次，于是"在阅读器里收藏 → 返回详情页"看到的还是旧状态；
     * 改成 Flow 之后，无论谁改了关联表，界面都会跟着变。
     */
    @Query("SELECT favorite_id FROM t_jm_favorite_album WHERE album_id = :albumId")
    fun observeFavoriteIdsOf(albumId: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    suspend fun albumCount(favoriteId: Long): Int

    @Query("SELECT COUNT(*) FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    fun observeAlbumCount(favoriteId: Long): Flow<Int>

    /**
     * 收藏夹列表 + 每个夹的本子数量，**一次查询**拿到。
     *
     * 之前收藏页给每行分别 `observeAlbumCount` 再用 `first()` 取一次值，
     * 结果收藏之后数量不刷新（用户反馈的第 1 点）。
     * 用一条 LEFT JOIN 的 Flow 查询取代 N 次单发查询：
     * 关联表任何变动都会重新发射，UI 自然跟着更新。
     */
    @Query(
        """
        SELECT f.id AS id, f.name AS name, f.sort_order AS sortOrder,
               (SELECT COUNT(*) FROM t_jm_favorite_album fa WHERE fa.favorite_id = f.id) AS albumCount,
               f.create_time AS createTime, f.modify_time AS modifyTime,
               (SELECT MAX(fa.create_time) FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = f.id) AS lastAddedTime,
               (SELECT fa.album_id FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = f.id
                ORDER BY COALESCE(fa.create_time, '') DESC, fa.id DESC LIMIT 1) AS latestAlbumId
        FROM t_jm_favorite f
        ORDER BY f.sort_order, f.id
        """,
    )
    fun observeFavoritesWithCount(): Flow<List<FavoriteWithCount>>
}

/**
 * 收藏夹 + 本子数量 + 封面来源。
 *
 * `sortOrder` / `createTime` 等是 SQL 里 `AS` 出来的别名
 * （`@ColumnInfo` 不生效于查询投影，所以这里用显式的 `AS`）。
 *
 * [latestAlbumId] 是**最近加入这个收藏夹的那本漫画**，列表用它当封面
 * （用户要求：收藏夹要有封面，空夹给占位）。
 */
data class FavoriteWithCount(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val albumCount: Int,
    val createTime: String?,
    val modifyTime: String?,
    /** 夹内最近一次"加入"的时间；空夹为 null。 */
    val lastAddedTime: String?,
    /** 夹内最近加入的本子 id；空夹为 null。 */
    val latestAlbumId: Long?,
)

// ======================================================================
// JM 最近浏览
// ======================================================================

/**
 * 最近浏览。凡是点进过详情页的本子都进这张表，
 * 再次进入只刷新 `viewed_time`（`upsert` + 显式带新时间）。
 */
@Dao
interface JmRecentAlbumDao {

    /**
     * 写入/更新一条最近浏览。
     *
     * `REPLACE` 语义在这里是安全的：这张表**没有需要保留的本地字段**
     * （不像 `t_jm_album` 有 `collected`），整行覆盖正是想要的行为。
     *
     * 但**调用方必须保证"读—改—写"是串行的**：`viewed_time` 需要"读旧值再保留"，
     * 与另一个并发写入交错时会被回写成旧值（详见 `JmRepository.touchRecent`
     * 的注释与 `recentWriteLock`）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: JmRecentAlbumEntity)

    @Query("SELECT * FROM t_jm_recent_album WHERE id = :id")
    suspend fun findById(id: Long): JmRecentAlbumEntity?

    @Query("SELECT * FROM t_jm_recent_album WHERE id = :id")
    fun observeById(id: Long): Flow<JmRecentAlbumEntity?>

    /** 按浏览时间倒序（用户要求：最近浏览降序）。 */
    @Query("SELECT * FROM t_jm_recent_album ORDER BY viewed_time DESC LIMIT :limit OFFSET :offset")
    fun observeRecent(limit: Int, offset: Int = 0): Flow<List<JmRecentAlbumEntity>>

    @Query("SELECT COUNT(*) FROM t_jm_recent_album")
    fun observeCount(): Flow<Int>

    /** 全部 id；"清空最近浏览"前先取一份，用来判断哪些封面还能删。 */
    @Query("SELECT id FROM t_jm_recent_album")
    suspend fun allIds(): List<Long>

    /** 只更新浏览时间（进入详情页时调用），不动其它字段。 */
    @Query("UPDATE t_jm_recent_album SET viewed_time = :viewedTime WHERE id = :id")
    suspend fun touch(id: Long, viewedTime: Long)

    /** 删除一条。 */
    @Query("DELETE FROM t_jm_recent_album WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM t_jm_recent_album")
    suspend fun clear(): Int
}

// ======================================================================
// B站视频
// ======================================================================

@Dao
interface BilibiliVideoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(video: BilibiliVideoEntity): Long

    @Update
    suspend fun update(video: BilibiliVideoEntity)

    @Query("SELECT * FROM t_bilibili_video WHERE bvid = :bvid AND cid IS :cid")
    suspend fun find(bvid: String, cid: Long?): BilibiliVideoEntity?

    @Query("SELECT * FROM t_bilibili_video WHERE id = :id")
    suspend fun findById(id: Long): BilibiliVideoEntity?

    @Query("SELECT * FROM t_bilibili_video WHERE id = :id")
    fun observeById(id: Long): Flow<BilibiliVideoEntity?>

    /**
     * 视频库列表。筛选/排序/分页语义对齐参考实现 `BilibiliVideoService`：
     * 默认"最近添加在前"（§9 第 10 条）。
     *
     * 注意列名是 snake_case（`owner_name` / `create_time`），`desc` 是保留字要用反引号。
     */
    @Query(
        """
        SELECT * FROM t_bilibili_video
        WHERE (:keyword IS NULL OR title LIKE '%' || :keyword || '%'
               OR `desc` LIKE '%' || :keyword || '%'
               OR tag LIKE '%' || :keyword || '%'
               OR bvid = :keyword
               OR CAST(avid AS TEXT) = :keyword)
          AND (:ownerName IS NULL OR owner_name = :ownerName)
          AND (:tag IS NULL OR tag LIKE '%' || :tag || '%')
          AND (:minDuration IS NULL OR duration >= :minDuration)
          AND (:maxDuration IS NULL OR duration <= :maxDuration)
        ORDER BY
            CASE WHEN :sortBy = 'title' THEN title END ASC,
            CASE WHEN :sortBy = 'durationAsc' THEN duration END ASC,
            CASE WHEN :sortBy = 'durationDesc' THEN duration END DESC,
            CASE WHEN :sortBy = 'pubdate' THEN pubdate END DESC,
            create_time DESC,
            id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLibrary(
        keyword: String?,
        ownerName: String?,
        tag: String?,
        minDuration: Long?,
        maxDuration: Long?,
        sortBy: String,
        limit: Int,
        offset: Int,
    ): Flow<List<BilibiliVideoEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM t_bilibili_video
        WHERE (:keyword IS NULL OR title LIKE '%' || :keyword || '%'
               OR `desc` LIKE '%' || :keyword || '%'
               OR tag LIKE '%' || :keyword || '%'
               OR bvid = :keyword
               OR CAST(avid AS TEXT) = :keyword)
          AND (:ownerName IS NULL OR owner_name = :ownerName)
          AND (:tag IS NULL OR tag LIKE '%' || :tag || '%')
          AND (:minDuration IS NULL OR duration >= :minDuration)
          AND (:maxDuration IS NULL OR duration <= :maxDuration)
        """,
    )
    fun observeLibraryCount(
        keyword: String?,
        ownerName: String?,
        tag: String?,
        minDuration: Long?,
        maxDuration: Long?,
    ): Flow<Int>

    @Query("SELECT * FROM t_bilibili_video WHERE bvid = :bvid ORDER BY cid")
    suspend fun byBvid(bvid: String): List<BilibiliVideoEntity>

    @Query("UPDATE t_bilibili_video SET play_url_raw = :raw, update_time = :updateTime WHERE bvid = :bvid AND cid IS :cid")
    suspend fun updatePlayUrlRaw(bvid: String, cid: Long?, raw: String, updateTime: String)

    @Query("DELETE FROM t_bilibili_video WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM t_bilibili_video WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT DISTINCT tag FROM t_bilibili_video WHERE tag IS NOT NULL AND tag != '' ORDER BY tag")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT DISTINCT owner_name FROM t_bilibili_video WHERE owner_name IS NOT NULL AND owner_name != '' ORDER BY owner_name")
    fun observeAllOwners(): Flow<List<String>>
}

// ======================================================================
// 持久化下载任务
// ======================================================================

@Dao
interface DownloadTaskDao {

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM t_download_task WHERE id = :id")
    suspend fun findById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM t_download_task WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadTaskEntity?>

    /** **去重规则**：同一 `(type, bizKey)` 只允许一个活跃任务。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE type = :type AND bizKey = :bizKey
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC LIMIT 1
        """,
    )
    suspend fun activeTask(type: DownloadType, bizKey: String): DownloadTaskEntity?

    /**
     * 观察某个业务对象的**活跃任务**（详情页靠它判断"哪些章节已经在队列里"）。
     *
     * 去重规则保证同一 `(type, bizKey)` 最多一个活跃任务，所以取一行就够。
     */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE type = :type AND bizKey = :bizKey
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC LIMIT 1
        """,
    )
    fun observeActiveTask(type: DownloadType, bizKey: String): Flow<DownloadTaskEntity?>

    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id
        """,
    )
    suspend fun activeTasks(): List<DownloadTaskEntity>

    /** 任务页"进行中"分组：不包含失败（§9 第 9 条）。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC
        """,
    )
    fun observeActive(): Flow<List<DownloadTaskEntity>>

    /** 任务页"已完成"分组：成功 + 失败 + 取消，最近在前。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
        ORDER BY COALESCE(endTime, updateTime) DESC
        LIMIT :limit
        """,
    )
    fun observeFinished(limit: Int): Flow<List<DownloadTaskEntity>>

    /**
     * 底部角标：**排队 + 执行中 + 等待网络**。
     *
     * 计划 §9 第 9 条说"角标仅统计排队/执行任务，失败另用提示点"——这里的取舍是：
     * 「等待网络」也算进角标。它不是失败（不计入 [observeFailedCount]），
     * 而是一个**已经提交、正卡在网络上**的任务：不亮角标的话，用户点完下载、
     * 回到浏览页以后就再也没有任何线索（模拟器实测就是这个现象）。
     * 失败与取消仍然只走 [observeFailedCount]。
     */
    @Query("SELECT COUNT(*) FROM t_download_task WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_NETWORK')")
    fun observeBadgeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM t_download_task WHERE status = 'FAIL'")
    fun observeFailedCount(): Flow<Int>

    @Query("UPDATE t_download_task SET status = :status, updateTime = :updateTime WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, updateTime: Long)

    @Query(
        """
        UPDATE t_download_task
        SET status = :status, message = :message, endTime = :endTime, updateTime = :endTime
        WHERE id = :id
        """,
    )
    suspend fun finish(id: Long, status: DownloadStatus, message: String?, endTime: Long)

    /**
     * 应用被杀死后重启时的恢复：把所有 `RUNNING` / `WAITING_NETWORK` 任务打回 `QUEUED`。
     *
     * **不动 `PAUSED`**（等用户点恢复）与 `CANCELLED`（不复活，§D.3）。
     * 进度字段先按磁盘现状由仓库层重算后再调用本方法。
     */
    @Query(
        """
        UPDATE t_download_task
        SET status = 'QUEUED', updateTime = :now
        WHERE status IN ('RUNNING', 'WAITING_NETWORK')
        """,
    )
    suspend fun requeueInterrupted(now: Long): Int

    @Query("DELETE FROM t_download_task WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')")
    suspend fun clearFinished(): Int

    @Query("DELETE FROM t_download_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 完成历史保留最近 N 条（B站是 20 条，§3.3）。 */
    @Query(
        """
        DELETE FROM t_download_task
        WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
          AND id NOT IN (
            SELECT id FROM t_download_task
            WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
            ORDER BY COALESCE(endTime, updateTime) DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun trimFinished(keep: Int): Int

    // ---------- 章节明细 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<DownloadTaskChapterEntity>)

    @Query("SELECT * FROM t_download_task_chapter WHERE taskId = :taskId ORDER BY sort, id")
    fun observeChapters(taskId: Long): Flow<List<DownloadTaskChapterEntity>>

    @Query("SELECT * FROM t_download_task_chapter WHERE taskId = :taskId ORDER BY sort, id")
    suspend fun chapters(taskId: Long): List<DownloadTaskChapterEntity>

    @Query(
        """
        UPDATE t_download_task_chapter
        SET doneImages = :doneImages, status = :status, message = :message, updateTime = :now
        WHERE taskId = :taskId AND chapterId = :chapterId
        """,
    )
    suspend fun updateChapterProgress(
        taskId: Long,
        chapterId: Long,
        doneImages: Int,
        status: DownloadStatus,
        message: String?,
        now: Long,
    )

    /**
     * 写入某章应有的图片数。
     *
     * 提交任务时往往还不知道章节里有多少张图（图片清单要拉 `/chapter` 才知道），
     * 所以 `totalImages` 先写 0，拿到清单后再补。**单独一条 UPDATE**
     * 而不是重跑 `upsertChapters`：后者是 REPLACE，会把已完成的进度清掉。
     */
    @Query(
        """
        UPDATE t_download_task_chapter
        SET totalImages = :totalImages, updateTime = :now
        WHERE taskId = :taskId AND chapterId = :chapterId
        """,
    )
    suspend fun setChapterTotal(taskId: Long, chapterId: Long, totalImages: Int, now: Long)

    /**
     * 重试 / 恢复前把章节打回队列。
     *
     * 覆盖 `FAIL`、`CANCELLED`，以及被取消时**卡在 `RUNNING` 的章节**
     * （任务被取消时正在跑的那一章就是这种状态，不重置的话它会永远显示"下载中"）。
     * **不动 `doneImages`**：磁盘上已有的图片会被跳过，重算由仓库层做。
     */
    @Query(
        """
        UPDATE t_download_task_chapter
        SET status = 'QUEUED', message = NULL, updateTime = :now
        WHERE taskId = :taskId AND status IN ('FAIL', 'CANCELLED', 'RUNNING')
        """,
    )
    suspend fun requeueUnfinishedChapters(taskId: Long, now: Long): Int

    @Query("DELETE FROM t_download_task_chapter WHERE taskId = :taskId")
    suspend fun deleteChapters(taskId: Long)
}

// ======================================================================
// 阅读 / 播放位置
// ======================================================================

@Dao
interface PlaybackPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    suspend fun find(kind: String, bizKey: String): PlaybackPositionEntity?

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    fun observe(kind: String, bizKey: String): Flow<PlaybackPositionEntity?>

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind ORDER BY updateTime DESC LIMIT 1")
    fun observeLastForKind(kind: String): Flow<PlaybackPositionEntity?>

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind ORDER BY updateTime DESC LIMIT :limit")
    fun observeRecent(kind: String, limit: Int): Flow<List<PlaybackPositionEntity>>

    /** 清空播放记录：只清位置，**不删视频文件**（§9.4）。 */
    @Query("DELETE FROM t_playback_position WHERE kind = :kind")
    suspend fun clearKind(kind: String): Int

    @Query("DELETE FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    suspend fun delete(kind: String, bizKey: String)
}

// ======================================================================
// JM 在线搜索历史
// ======================================================================

@Dao
interface JmSearchHistoryDao {

    @Insert
    suspend fun insert(history: JmSearchHistoryEntity): Long

    @Query("SELECT * FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<JmSearchHistoryEntity>>

    @Query("SELECT * FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<JmSearchHistoryEntity>

    @Query("SELECT * FROM t_jm_search_history WHERE id = :id")
    suspend fun findById(id: Long): JmSearchHistoryEntity?

    /** 相同条件（关键字 + 排序 + 页码）命中时刷新时间而不是新增，避免历史被同一条件刷屏。 */
    @Query(
        """
        SELECT * FROM t_jm_search_history
        WHERE keyword = :keyword AND sort = :sort AND page = :page
        ORDER BY updateTime DESC LIMIT 1
        """,
    )
    suspend fun findSameCondition(keyword: String, sort: String, page: Int): JmSearchHistoryEntity?

    @Query("UPDATE t_jm_search_history SET updateTime = :now, total = :total, resultCount = :resultCount, items = :items WHERE id = :id")
    suspend fun refresh(id: Long, now: Long, total: Long, resultCount: Int, items: String?)

    @Query("DELETE FROM t_jm_search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM t_jm_search_history")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM t_jm_search_history")
    suspend fun count(): Int

    /** 保留最近 N 条，删除超额的最旧记录（§9.3「减少数量保存时清除超额最旧记录」）。 */
    @Query(
        """
        DELETE FROM t_jm_search_history
        WHERE id NOT IN (
            SELECT id FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int): Int
}
