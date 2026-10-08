package com.haruhi.haruhibox.feature.jm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.download.FileDownloader
import com.haruhi.haruhibox.core.network.NetworkState
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * "本地优先 + 取回来就缓存"的验收（用户要求第 2、4 点）：
 *
 * - 本地已有 → **一个网络请求都不发**；
 * - 本地没有 → 取回来、落盘，**第二次不再请求**；
 * - 删掉本地文件后 → 重新取（不再显示"图片缺失"）；
 * - 取回来的图与下载走的是同一个目录/同一套解扰规则，所以"一边看一边下载"不冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var store: JmImageStore

    private val album = JmAlbumEntity(
        id = 480854,
        name = "测试本子",
        albumFolderName = "测试本子_JM480854",
    )
    private val chapterId = 123456L
    private val folder = "第1话"
    private val imageFile = "00001.webp"

    private val payload = ByteArray(512) { (it % 97).toByte() }
    private val requests = ConcurrentHashMap<String, AtomicInteger>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path.endsWith("/chapter_view_template")) {
                    requests.computeIfAbsent("template") { AtomicInteger() }.incrementAndGet()
                    // 远大于章节号 -> 块数 0 -> 图片原样落盘（不重新编码）
                    return MockResponse.Builder()
                        .code(200)
                        .body(Buffer().writeUtf8("var scramble_id = 999999999;"))
                        .build()
                }
                val name = path.substringAfterLast('/')
                requests.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                return MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Length", payload.size.toString())
                    .body(Buffer().write(payload))
                    .build()
            }
        }
        server.start()

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()

        val repo = JmRepository(
            api = JmApiClient(
                client = client,
                config = { error("取图链路不读网络设置") },
                apiDomainProvider = { server.hostName },
            ),
            albumDao = db.jmAlbumDao(),
            chapterDao = db.jmChapterDao(),
            favoriteDao = db.jmFavoriteDao(),
            recentDao = db.jmRecentAlbumDao(),
            searchHistoryDao = db.jmSearchHistoryDao(),
            dirs = dirs,
        )
        store = JmImageStore(
            repo = repo,
            downloader = FileDownloader(client = client, dirs = dirs, maxAttempts = 1, retryDelayMs = 10L),
            dirs = dirs,
            environment = FakeEnvironment(),
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `a file that is not on disk is not a cache hit`() {
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()

        // 0 字节残留不算命中（与下载层的判定一致）
        val target = store.targetFile(album, folder, imageFile)
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(0))
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()
    }

    @Test
    fun `reading a page on demand downloads it once and caches it`() {
        val first = runBlocking { store.file(album, chapterId, folder, imageFile) }

        assertThat(first.readBytes()).isEqualTo(payload)
        assertThat(store.cachedFile(album, folder, imageFile)).isEqualTo(first)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)

        // 第二次（例如退出阅读器再进来）**一个请求都不发**
        val second = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(second).isEqualTo(first)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)
    }

    @Test
    fun `a cached page never touches the network`() {
        val target = store.targetFile(album, folder, imageFile)
        target.parentFile!!.mkdirs()
        target.writeBytes(payload)

        val file = runBlocking { store.file(album, chapterId, folder, imageFile) }

        assertThat(file).isEqualTo(target)
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** 用户要求第 4 点：删掉本地文件后再看，应该重新取回来而不是报"图片缺失"。 */
    @Test
    fun `deleting the local file makes the next read fetch it again`() {
        val target = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(target.exists()).isTrue()

        target.delete()
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()

        val again = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(again.readBytes()).isEqualTo(payload)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(2)
    }

    /** 块数为 0 时原样保留（下载与阅读走同一套规则，验收项）。 */
    @Test
    fun `block num zero keeps the downloaded bytes untouched`() {
        val file = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(file.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `scramble id is fetched once per chapter`() {
        runBlocking {
            store.file(album, chapterId, folder, "00001.webp")
            store.file(album, chapterId, folder, "00002.webp")
        }
        assertThat(requests.getValue("template").get()).isEqualTo(1)
    }

    @Test
    fun `the chapter directory matches the download layout`() {
        val dir = store.chapterDir(album, folder)
        assertThat(dir.name).isEqualTo(folder)
        assertThat(dir.parentFile!!.name).isEqualTo("测试本子_JM480854")
        assertThat(File(dirs.jm, "测试本子_JM480854")).isEqualTo(dir.parentFile)
    }

    private class FakeEnvironment : JmDownloadEnvironment {
        override suspend fun concurrency(): Int = 2
        override suspend fun imageDomain(): String = "cdn.test"
        override suspend fun allowsTransfer(metered: Boolean): Boolean = true
        override val networkState: StateFlow<NetworkState> =
            MutableStateFlow(NetworkState(online = true, metered = false))
    }
}
