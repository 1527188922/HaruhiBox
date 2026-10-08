package com.haruhi.haruhibox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JM 相关 Dao 的语义测试：分页、排序、唯一索引冲突、删除语义、标签筛选。
 *
 * 这些语义直接决定书库页/详情页/收藏页的数据正确性，属于 P1/P2 的验收项
 * （计划 §11.2「数据库：Room 迁移测试 + Dao 语义测试（分页/排序/唯一索引冲突）」）。
 *
 * 注意标签参数是 `tagsCsv`（逗号分隔）不是单个标签：多选标签是 **OR** 语义，
 * 对应参考实现 `applyTagFilter` 的动态 OR 拼接。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmDaoTest {

    private lateinit var db: HaruhiBoxDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `library pagination returns stable slices ordered by collected then time`() = runTest {
        val dao = db.jmAlbumDao()
        // 5 本：id 1..5，收藏的是 2 和 4
        (1L..5L).forEach { id ->
            dao.insertIgnore(
                JmAlbumEntity(
                    id = id,
                    name = "本子$id",
                    collected = id % 2 == 0L,
                    modifyTime = "2026-01-0$id",
                ),
            )
        }

        val firstPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 0).first()
        val secondPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 2).first()
        val thirdPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 4).first()

        assertThat(firstPage.map { it.id }).containsExactly(4L, 2L).inOrder()
        assertThat(secondPage.map { it.id }).containsExactly(5L, 3L).inOrder()
        assertThat(thirdPage.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `library count matches the same filters`() = runTest {
        val dao = db.jmAlbumDao()
        dao.insertIgnore(JmAlbumEntity(id = 1, name = "海贼王", author = "尾田", collected = true))
        dao.insertIgnore(JmAlbumEntity(id = 2, name = "火影", author = "岸本", collected = false))
        dao.insertIgnore(JmAlbumEntity(id = 3, name = "海贼王外传", author = "尾田", collected = false))

        assertThat(dao.observeLibraryCount(null, null, null, "", null).first()).isEqualTo(3)
        assertThat(dao.observeLibraryCount("海贼", null, null, "", null).first()).isEqualTo(2)
        assertThat(dao.observeLibraryCount(null, true, null, "", null).first()).isEqualTo(1)
        assertThat(dao.observeLibraryCount(null, null, "尾田", "", null).first()).isEqualTo(2)
    }

    @Test
    fun `keyword search also matches the jm id`() = runTest {
        val dao = db.jmAlbumDao()
        dao.insertIgnore(JmAlbumEntity(id = 480854, name = "标题"))
        dao.insertIgnore(JmAlbumEntity(id = 123, name = "别的"))

        val hit = dao.observeLibrary("480854", null, null, "", null, 10, 0).first()
        assertThat(hit.map { it.id }).containsExactly(480854L)
    }

    @Test
    fun `tag filter matches any of the selected tags`() = runTest {
        val dao = db.jmAlbumDao()
        // tags 列存的是 JSON 数组字符串（与参考实现一致）
        dao.insertIgnore(JmAlbumEntity(id = 1, name = "A", tags = """["标签一","标签二"]"""))
        dao.insertIgnore(JmAlbumEntity(id = 2, name = "B", tags = """["标签三"]"""))
        dao.insertIgnore(JmAlbumEntity(id = 3, name = "C", tags = null))

        // 单个标签
        assertThat(dao.observeLibrary(null, null, null, "标签一", null, 10, 0).first().map { it.id })
            .containsExactly(1L)
        // 多选是 OR
        assertThat(dao.observeLibrary(null, null, null, "标签一,标签三", null, 10, 0).first().map { it.id })
            .containsExactly(1L, 2L)
        // 空串表示不筛选
        assertThat(dao.observeLibrary(null, null, null, "", null, 10, 0).first()).hasSize(3)
        // 子串不能误命中（"标签" 不等于 "标签一"）
        assertThat(dao.observeLibrary(null, null, null, "标签", null, 10, 0).first()).isEmpty()
    }

    @Test
    fun `inserting the same album id twice keeps the first row`() = runTest {
        val dao = db.jmAlbumDao()
        dao.insertIgnore(JmAlbumEntity(id = 1, name = "原始", collected = true))
        // IGNORE 策略：重新拉取在线数据不能把本地收藏标记重置成默认值
        dao.insertIgnore(JmAlbumEntity(id = 1, name = "覆盖", collected = false))

        val album = dao.findById(1)!!
        assertThat(album.name).isEqualTo("原始")
        assertThat(album.collected).isTrue()
    }

    @Test
    fun `setCollected only touches the collected column`() = runTest {
        val dao = db.jmAlbumDao()
        dao.insertIgnore(JmAlbumEntity(id = 1, name = "标题", description = "简介", collected = false))

        dao.setCollected(1, true, "2026-01-01")

        val album = dao.findById(1)!!
        assertThat(album.collected).isTrue()
        assertThat(album.name).isEqualTo("标题")
        assertThat(album.description).isEqualTo("简介")
    }

    @Test
    fun `chapter list groups images by chapter and orders by chapter sort then id`() = runTest {
        val dao = db.jmChapterDao()
        for (sort in listOf("2", "1", "10")) {
            val chapterId = sort.toLong()
            dao.insertAll(
                (1..3).map { index ->
                    JmChapterImageEntity(
                        albumId = 100,
                        chapterId = chapterId,
                        chapterSort = sort,
                        chapterTitle = "第${sort}话",
                        imageFile = "%05d.webp".format(index),
                        imageSort = index,
                    )
                },
            )
        }

        val chapters = dao.chapterList(100)
        // ORDER BY CAST(chapter_sort AS INTEGER)：1, 2, 10（不是字符串序）
        assertThat(chapters.map { it.chapterSort }).containsExactly("1", "2", "10").inOrder()
        assertThat(chapters).hasSize(3)
    }

    @Test
    fun `images are returned in image sort order`() = runTest {
        val dao = db.jmChapterDao()
        dao.insertAll(
            listOf(3, 1, 2).map { index ->
                JmChapterImageEntity(
                    albumId = 100,
                    chapterId = 1,
                    imageFile = "%05d.webp".format(index),
                    imageSort = index,
                )
            },
        )

        val images = dao.images(100, 1)
        assertThat(images.map { it.imageFile })
            .containsExactly("00001.webp", "00002.webp", "00003.webp").inOrder()
    }

    @Test
    fun `replacing a chapter drops images the server no longer returns`() = runTest {
        val dao = db.jmChapterDao()
        dao.replaceChapter(
            100,
            1,
            listOf("00001.webp", "00002.webp", "00003.webp").map {
                JmChapterImageEntity(albumId = 100, chapterId = 1, imageFile = it, imageSort = 0)
            },
        )
        assertThat(dao.images(100, 1)).hasSize(3)

        // 服务端这次只返回 2 张：本地清单必须跟着收缩，否则详情页会多算图片数
        dao.replaceChapter(
            100,
            1,
            listOf("00001.webp", "00002.webp").map {
                JmChapterImageEntity(albumId = 100, chapterId = 1, imageFile = it, imageSort = 0)
            },
        )
        assertThat(dao.images(100, 1).map { it.imageFile })
            .containsExactly("00001.webp", "00002.webp")
    }

    @Test
    fun `favorite name is unique`() = runTest {
        val dao = db.jmFavoriteDao()
        val first = dao.insert(JmFavoriteEntity(name = "默认收藏夹", sortOrder = 0))
        val second = dao.insert(JmFavoriteEntity(name = "默认收藏夹", sortOrder = 1))

        assertThat(first).isGreaterThan(0)
        // IGNORE 策略：重名插入被丢弃，返回 -1
        assertThat(second).isEqualTo(-1)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `linking the same album to a favorite twice is a no-op`() = runTest {
        val favoriteDao = db.jmFavoriteDao()
        val favoriteId = favoriteDao.insert(JmFavoriteEntity(name = "夹子"))

        val first = favoriteDao.link(JmFavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))
        val second = favoriteDao.link(JmFavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))

        assertThat(first).isGreaterThan(0)
        assertThat(second).isEqualTo(-1) // 唯一索引 (favorite_id, album_id)
        assertThat(favoriteDao.observeAlbumCount(favoriteId).first()).isEqualTo(1)
        assertThat(favoriteDao.albumCount(favoriteId)).isEqualTo(1)
    }

    @Test
    fun `deleting a favorite removes links but keeps the album record`() = runTest {
        val albumDao = db.jmAlbumDao()
        val favoriteDao = db.jmFavoriteDao()
        albumDao.insertIgnore(JmAlbumEntity(id = 1, name = "本子"))
        val favoriteId = favoriteDao.insert(JmFavoriteEntity(name = "夹子"))
        favoriteDao.link(JmFavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))

        favoriteDao.deleteWithLinks(favoriteId)

        assertThat(favoriteDao.all()).isEmpty()
        assertThat(favoriteDao.favoriteIdsOf(1)).isEmpty()
        // 书库记录与文件不受影响（§9 第 6 条）
        assertThat(albumDao.findById(1)).isNotNull()
    }

    @Test
    fun `library can be filtered by favorite folder`() = runTest {
        val albumDao = db.jmAlbumDao()
        val favoriteDao = db.jmFavoriteDao()
        albumDao.insertIgnore(JmAlbumEntity(id = 1, name = "A"))
        albumDao.insertIgnore(JmAlbumEntity(id = 2, name = "B"))
        val favoriteId = favoriteDao.insert(JmFavoriteEntity(name = "夹子"))
        favoriteDao.link(JmFavoriteAlbumEntity(favoriteId = favoriteId, albumId = 2))

        val inFolder = albumDao.observeLibrary(null, null, null, "", favoriteId, 10, 0).first()
        assertThat(inFolder.map { it.id }).containsExactly(2L)
    }
}
