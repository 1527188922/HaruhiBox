package com.haruhi.haruhibox.core.download

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.test.runTest
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载引擎验收（计划 §11.2「下载引擎：本地起一个 HTTP 服务模拟 Range 分片、超时、断流」，
 * 以及 §D.1「仅凭文件存在认定成功、Range 条件不完整」的补强项）。
 *
 * 关键用例对应计划里点名的风险：
 * - B站 CDN 是否支持 Range → [does not support range - server ignores Range and we restart from zero]；
 * - 断点续传 → [resumes from the existing part file and only fetches the missing bytes]；
 * - 原子发布与损坏文件 → [incomplete download is reported as failure and part file is kept for resume]。
 */
class FileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dirs: LibraryDirs
    private lateinit var downloader: FileDownloader

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        downloader = FileDownloader(
            client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build(),
            dirs = dirs,
            maxAttempts = 2,
            retryDelayMs = 10L,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `downloads a whole file and publishes it atomically`() = runTest {
        server.enqueue(bodyResponse(200, payload))
        val target = File(dirs.bili, "video.mp4")

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 中转文件必须被清理
        assertThat(partOf(target).exists()).isFalse()
    }

    @Test
    fun `resumes from the existing part file and only fetches the missing bytes`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val part = partOf(target)
        val alreadyDownloaded = 16 * 1024
        part.parentFile!!.mkdirs()
        part.writeBytes(payload.copyOfRange(0, alreadyDownloaded))

        // 服务端返回 206，并且只回剩下的一段
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes $alreadyDownloaded-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload.copyOfRange(alreadyDownloaded, payload.size)))
                .build(),
        )

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        val request: RecordedRequest = server.takeRequest()
        assertThat(request.headers["Range"]).isEqualTo("bytes=$alreadyDownloaded-")

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        // 拼接后的字节必须与原始内容完全一致
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `does not support range - server ignores Range and we restart from zero`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val part = partOf(target)
        // 本地已有半个文件，但服务端不支持 Range（返回 200 + 完整内容）
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1024) { 0x7F })

        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        // 必须是从头写的完整文件，而不是"半个旧文件 + 完整新文件"
        assertThat(target.length()).isEqualTo(payload.size.toLong())
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `skips download when the target already exists and is non empty`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        target.parentFile!!.mkdirs()
        target.writeBytes(payload)

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Skipped::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `truncated download is rejected when the caller knows the expected size`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val half = payload.copyOfRange(0, payload.size / 2)
        // 服务端用 chunked 声明 64KB 却只给 32KB（模拟断流），两次尝试都断
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(half), payload.size)
                    .build(),
            )
        }

        val outcome = downloader.download(
            urlProvider = { server.url("/video.mp4").toString() },
            target = target,
            // B站接口会给出 durl 的 size，传进来就能识别断流。
            // **这是防"静默截断"的唯一可靠手段**：服务端没给 Content-Length 时，
            // 客户端无法凭空判断少收的字节。
            expectedTotal = payload.size.toLong(),
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        // 不能把不完整的文件当成成品发布
        assertThat(target.exists()).isFalse()
        // 保留 .part 供下次续传
        val part = partOf(target)
        assertThat(part.exists()).isTrue()
        assertThat(part.length()).isLessThan(payload.size.toLong())
    }

    @Test
    fun `without any size information the engine accepts what the server sent`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val half = payload.copyOfRange(0, payload.size / 2)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .chunkedBody(Buffer().write(half), payload.size)
                .build(),
        )

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        // 文档化这个约束：既没有 Content-Length 也没有调用方给的 size 时，
        // "完整"的判定只能退化为"文件非空"。调用方（B站/JM 仓库）必须尽量给出 size。
        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.length()).isEqualTo(half.size.toLong())
    }

    @Test
    fun `http error code fails fast without publishing`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = downloader.download({ server.url("/missing.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error).isInstanceOf(NetworkError.HttpStatus::class.java)
        assertThat(target.exists()).isFalse()
        // 4xx 不重试
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `server error is retried and url is re-resolved each attempt`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(bodyResponse(200, payload))
        var resolveCount = 0

        val outcome = downloader.download(
            urlProvider = {
                resolveCount++
                server.url("/video.mp4").toString()
            },
            target = target,
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 每次尝试都重新解析直链（B站直链会过期）
        assertThat(resolveCount).isEqualTo(2)
    }

    @Test
    fun `range 416 drops the local offset and restarts from scratch`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val part = partOf(target)
        // 本地偏移比资源还长（例如直链换成了另一个更短的资源）
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(payload.size + 4096))

        server.enqueue(
            MockResponse.Builder()
                .code(416)
                .setHeader("Content-Range", "bytes */${payload.size}")
                .build(),
        )
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 第二次请求必须**不带** Range（已经放弃旧偏移）
        server.takeRequest()
        assertThat(server.takeRequest().headers["Range"]).isNull()
    }

    @Test
    fun `content range start mismatch triggers a clean restart`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        val part = partOf(target)
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(2048))

        // 请求 bytes=2048-，服务端却从 0 开始给（区间对不上）
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload))
                .build(),
        )
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `content range parsing handles the documented forms`() {
        assertThat(FileDownloader.parseRangeStart("bytes 123-456/789")).isEqualTo(123L)
        assertThat(FileDownloader.parseRangeTotal("bytes 123-456/789")).isEqualTo(789L)
        assertThat(FileDownloader.parseRangeTotal("bytes 123-456/*")).isNull()
        assertThat(FileDownloader.parseRangeStart("garbage")).isNull()
        assertThat(FileDownloader.parseRangeTotal(null)).isNull()
    }

    @Test
    fun `completedBytes and isComplete reflect disk state for progress recovery`() {
        val target = File(dirs.bili, "video.mp4")
        target.parentFile!!.mkdirs()
        assertThat(downloader.completedBytes(target)).isEqualTo(0L)
        assertThat(downloader.isComplete(target, payload.size.toLong())).isFalse()

        target.writeBytes(payload.copyOfRange(0, 100))
        assertThat(downloader.completedBytes(target)).isEqualTo(100L)

        target.writeBytes(payload)
        assertThat(downloader.isComplete(target, payload.size.toLong())).isTrue()
        // 长度未知时只要求"文件非空"
        assertThat(downloader.isComplete(target, 0L)).isTrue()
    }

    private fun partOf(target: File) = File(target.parentFile, target.name + FileDownloader.PART_SUFFIX)

    /** 带显式 Content-Length 的响应，用于模拟"声明长度与实际长度不一致"。 */
    private fun bodyResponse(code: Int, bytes: ByteArray) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Length", bytes.size.toString())
        .body(Buffer().write(bytes))
        .build()
}
