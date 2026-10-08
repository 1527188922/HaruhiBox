package com.haruhi.haruhibox.feature.jm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmAlbumDto
import com.haruhi.haruhibox.feature.jm.api.JmApiClient
import com.haruhi.haruhibox.feature.jm.api.JmSeriesDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 最近浏览 + "点详情不入库" + 收藏夹数量刷新的验收测试。
 *
 * 对应用户提出的改动：
 * - 第 1 点：收藏之后收藏页数量要更新（[favorite count flow emits after collecting]）；
 * - 第 2 点：点详情不等于加入书库（[touching recent does not add to the library]）；
 * - 第 3、4 点：最近浏览按浏览时间倒序、刷新不改浏览时间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmRecentAndFavoriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repo: JmRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repo = JmRepository(
            api = JmApiClient(client = OkHttpClient(), config = { error("本测试不发起网络请求") }),
            albumDao = db.jmAlbumDao(),
            chapterDao = db.jmChapterDao(),
            favoriteDao = db.jmFavoriteDao(),
            recentDao = db.jmRecentAlbumDao(),
            searchHistoryDao = db.jmSearchHistoryDao(),
            dirs = dirs,
        )
    }

    @After
    fun tearDown() = db.close()

    // ---------------- 第 2 点：点详情不入库 ----------------

    @Test
    fun `touching recent does not add to the library`() = runTest {
        repo.touchRecent(480854L)

        // 书库必须还是空的
        assertThat(repo.findAlbum(480854L)).isNull()
        assertThat(db.jmAlbumDao().all()).isEmpty()
        // 但最近浏览里已经有了
        assertThat(repo.findRecent(480854L)).isNotNull()
    }

    @Test
    fun `saveToRecent does not add to the library either`() = runTest {
        // 模拟"进入详情页后联网拿到数据"：只写最近浏览，不写书库
        repo.saveToRecent(albumFixture(480854, "只看不入库"))

        assertThat(repo.findAlbum(480854L)).isNull()
        assertThat(repo.findRecent(480854L)!!.name).isEqualTo("只看不入库")
    }

    @Test
    fun `addToLibrary writes both the library and recent`() = runTest {
        // addToLibrary 会先请求网络，这里直接验证 saveAlbum + saveToRecent 的组合语义
        repo.saveToRecent(albumFixture(480854, "本子"))
        repo.saveAlbum(albumFixture(480854, "本子"))

        assertThat(repo.findAlbum(480854L)).isNotNull()
        assertThat(repo.findRecent(480854L)).isNotNull()
    }

    // ---------------- 第 3、4 点：最近浏览 ----------------

    @Test
    fun `recent list is ordered by viewed time descending`() = runTest {
        repo.saveToRecent(albumFixture(1L, "第一个"))
        repo.saveToRecent(albumFixture(2L, "第二个"))
        repo.saveToRecent(albumFixture(3L, "第三个"))

        // viewedTime 用 System.currentTimeMillis()，同一毫秒内可能相等，所以显式错开
        db.jmRecentAlbumDao().touch(1L, 1_000L)
        db.jmRecentAlbumDao().touch(2L, 2_000L)
        db.jmRecentAlbumDao().touch(3L, 3_000L)

        val order = repo.observeRecentAlbums().first().map { it.id }
        assertThat(order).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun `re-entering updates the viewed time and keeps a single row`() = runTest {
        repo.touchRecent(480854L)
        db.jmRecentAlbumDao().touch(480854L, 1_000L)
        assertThat(repo.findRecent(480854L)!!.viewedTime).isEqualTo(1_000L)

        // 再次进入：只更新时间，不新增行
        repo.touchRecent(480854L)

        assertThat(repo.observeRecentAlbums().first()).hasSize(1)
        assertThat(repo.findRecent(480854L)!!.viewedTime).isGreaterThan(1_000L)
    }

    @Test
    fun `refresh updates recent fields but keeps the viewed time`() = runTest {
        repo.saveToRecent(albumFixture(480854, "旧标题"))
        val viewedAt = repo.findRecent(480854L)!!.viewedTime

        // 模拟点刷新：拿到新的在线数据
        repo.saveToRecent(albumFixture(480854, "新标题"))

        val recent = repo.findRecent(480854L)!!
        assertThat(recent.name).isEqualTo("新标题")
        // **浏览时间不变**（用户要求第 4 点：浏览时间只在点进详情页时更新）
        assertThat(recent.viewedTime).isEqualTo(viewedAt)
    }

    @Test
    fun `refresh does not create a library row for an album that is not in the library`() = runTest {
        repo.saveToRecent(albumFixture(480854, "只看不入库"))
        repo.saveToRecent(albumFixture(480854, "刷新后"))

        assertThat(repo.findAlbum(480854L)).isNull()
    }

    @Test
    fun `recent album carries the same fields as the library album`() = runTest {
        val dto = albumFixture(480854, "完整字段")
        repo.saveToRecent(dto)
        repo.saveAlbum(dto)

        val library = repo.findAlbum(480854L)!!
        val recent = repo.findRecent(480854L)!!

        assertThat(recent.name).isEqualTo(library.name)
        assertThat(recent.albumFolderName).isEqualTo(library.albumFolderName)
        assertThat(recent.series).isEqualTo(library.series)
        assertThat(recent.tags).isEqualTo(library.tags)
        assertThat(recent.author).isEqualTo(library.author)
        assertThat(recent.description).isEqualTo(library.description)
    }

    @Test
    fun `local detail prefers the library over recent`() = runTest {
        repo.saveToRecent(albumFixture(480854, "最近浏览版本"))
        repo.saveAlbum(albumFixture(480854, "书库版本"))

        val local = repo.localAlbumDetail(480854L)!!
        assertThat(local.fromLibrary).isTrue()
        assertThat(local.album.name).isEqualTo("书库版本")
    }

    @Test
    fun `local detail falls back to recent when not in the library`() = runTest {
        repo.saveToRecent(albumFixture(480854, "只在最近浏览"))

        val local = repo.localAlbumDetail(480854L)!!
        assertThat(local.fromLibrary).isFalse()
        assertThat(local.album.name).isEqualTo("只在最近浏览")
        // 最近浏览里没有收藏语义
        assertThat(local.album.collected).isNull()
    }

    @Test
    fun `local detail returns null when neither table has the album`() = runTest {
        assertThat(repo.localAlbumDetail(999L)).isNull()
    }

    @Test
    fun `recent can be cleared without touching the library`() = runTest {
        repo.saveAlbum(albumFixture(480854, "在书库"))
        repo.saveToRecent(albumFixture(480854, "在书库"))

        val cleared = repo.clearRecent()

        assertThat(cleared).isEqualTo(1)
        assertThat(repo.findRecent(480854L)).isNull()
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    // ---------------- 第 1 点：收藏夹数量实时更新 ----------------

    @Test
    fun `favorite count flow emits after collecting`() = runTest {
        repo.ensureDefaultFavorite()
        val defaultId = repo.listFavorites().first().id

        // 首次读取：0 本
        assertThat(repo.observeFavoritesWithCount().first().first().albumCount).isEqualTo(0)

        repo.saveAlbum(albumFixture(480854, "本子"))
        repo.addToFavorite(480854L, defaultId)

        // 收藏之后同一个 Flow 必须给出新的数量（之前用一次性查询，这里是 0 导致不刷新）
        assertThat(repo.observeFavoritesWithCount().first().first().albumCount).isEqualTo(1)
    }

    @Test
    fun `favorite count flow emits after removing and deleting`() = runTest {
        repo.saveAlbum(albumFixture(480854, "本子"))
        val defaultId = repo.ensureDefaultFavorite().id
        val secondId = repo.createFavorite("第二夹")!!.id

        repo.addToFavorite(480854L, defaultId)
        repo.addToFavorite(480854L, secondId)
        var counts = repo.observeFavoritesWithCount().first().associate { it.id to it.albumCount }
        assertThat(counts[defaultId]).isEqualTo(1)
        assertThat(counts[secondId]).isEqualTo(1)

        // 移出后数量下降
        repo.removeFromFavorite(480854L, secondId)
        counts = repo.observeFavoritesWithCount().first().associate { it.id to it.albumCount }
        assertThat(counts[secondId]).isEqualTo(0)
        assertThat(counts[defaultId]).isEqualTo(1)

        // 删除收藏夹后它本身消失，书库记录仍在
        repo.deleteFavorite(secondId)
        val remaining = repo.observeFavoritesWithCount().first()
        assertThat(remaining.map { it.id }).containsExactly(defaultId)
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    @Test
    fun `favorite summary marks the default folder`() = runTest {
        repo.ensureDefaultFavorite()
        repo.createFavorite("普通夹")

        val summaries = repo.observeFavoritesWithCount().first()
        assertThat(summaries.first { it.name == "默认收藏夹" }.isDefault).isTrue()
        assertThat(summaries.first { it.name == "普通夹" }.isDefault).isFalse()
    }

    // ------------------------------------------------------------------

    private fun albumFixture(id: Long, name: String) = JmAlbumDto(
        id = id,
        name = name,
        description = "简介",
        series = listOf(JmSeriesDto(id = "${id}01", name = "第1话", sort = "1", title = "")),
        seriesId = "12345",
        author = listOf("作者A"),
        tags = listOf("标签一"),
    )
}
