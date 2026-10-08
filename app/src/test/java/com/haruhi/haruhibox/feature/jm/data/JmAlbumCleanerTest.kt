package com.haruhi.haruhibox.feature.jm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.codec.ArchiveWriter
import com.haruhi.haruhibox.core.codec.PdfWriter
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.storage.ExportPublisher
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "删除一本漫画"的验收（用户要求第 6、7 点）。
 *
 * 两个入口的差别只有**删不删数据库记录**，所以这里把四处痕迹（图片 / 导出成品 / 封面缓存 /
 * 数据库五张表）分开断言，任何一处漏删都会挂。
 *
 * 另外验证"从书库移除"会先取消还在跑的任务——否则下载会把文件又写回刚删掉的目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmAlbumCleanerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repo: JmRepository
    private lateinit var tasks: DownloadTaskRepository
    private lateinit var publisher: FakePublisher
    private lateinit var covers: RecordingCoverCache
    private lateinit var cleaner: JmAlbumCleaner
    private lateinit var scope: CoroutineScope

    private val albumId = 480854L
    private val folderName = "测试本子_JM480854"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repo = JmRepository(
            api = JmApiClient(client = OkHttpClient(), config = { error("删除不联网") }),
            albumDao = db.jmAlbumDao(),
            chapterDao = db.jmChapterDao(),
            favoriteDao = db.jmFavoriteDao(),
            recentDao = db.jmRecentAlbumDao(),
            searchHistoryDao = db.jmSearchHistoryDao(),
            dirs = dirs,
            positionDao = db.playbackPositionDao(),
        )
        tasks = DownloadTaskRepository(dao = db.downloadTaskDao(), dirs = dirs)
        publisher = FakePublisher(temp.newFolder("downloads"))
        covers = RecordingCoverCache()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleaner = JmAlbumCleaner(
            repo = repo,
            exports = JmExportManager(
                repo = repo,
                tasks = tasks,
                dirs = dirs,
                archiveWriter = ArchiveWriter(),
                pdfWriter = PdfWriter(),
                publisher = publisher,
                scope = scope,
            ),
            tasks = tasks,
            coverCache = covers,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `deleting files wipes images and exports but keeps the library record`() {
        seedAlbum()
        seedChapter()
        publish("$folderName.zip")

        val result = runBlocking { cleaner.deleteFiles(listOf(album())) }

        assertThat(File(dirs.albumDir(folderName), "第1话").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isFalse()
        assertThat(result.removedExports).isEqualTo(1)
        assertThat(result.removedRecords).isFalse()
        assertThat(result.freedBytes).isGreaterThan(0L)
        // 书库记录、章节清单、最近浏览都要还在
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNotNull()
        assertThat(runBlocking { repo.chaptersOf(album()) }).hasSize(1)
        assertThat(runBlocking { repo.localAlbumDetail(albumId) }!!.fromLibrary).isTrue()
        // 封面**不删**：只删文件时列表里还要显示封面
        assertThat(covers.evicted).isEmpty()
    }

    @Test
    fun `removing from library also wipes records favorites recent and the cover`() {
        seedAlbum()
        seedChapter()
        publish("$folderName.zip")
        publish("$folderName.pdf")
        runBlocking {
            repo.addToFavorite(albumId)
            repo.touchRecent(albumId)
        }
        assertThat(runBlocking { repo.favoriteIdsOf(albumId) }).isNotEmpty()

        val result = runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        assertThat(result.removedRecords).isTrue()
        assertThat(result.removedExports).isEqualTo(2)
        assertThat(dirs.albumDir(folderName).exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.pdf").exists()).isFalse()
        assertThat(covers.evicted).containsExactly(albumId)

        // 五张表都不能留痕：书库、章节清单、收藏关联、最近浏览、阅读位置
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNull()
        assertThat(runBlocking { db.jmChapterDao().chapterList(albumId) }).isEmpty()
        assertThat(runBlocking { repo.favoriteIdsOf(albumId) }).isEmpty()
        assertThat(runBlocking { repo.localAlbumDetail(albumId) }).isNull()
        assertThat(runBlocking { db.playbackPositionDao().find(READING_KIND, albumId.toString()) }).isNull()
    }

    @Test
    fun `removing from library cancels the running download task first`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        // 任务被打成已取消（不是删掉记录：任务页还要能说明"为什么停了"）
        assertThat(runBlocking { tasks.findById(taskId) }!!.status).isEqualTo(DownloadStatus.CANCELLED)
    }

    @Test
    fun `deleting files leaves a running task alone`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.deleteFiles(listOf(album())) }

        // "删除本地文件"不删记录：任务照跑，用户可能是想重下这一本
        assertThat(runBlocking { tasks.findById(taskId) }!!.status).isEqualTo(DownloadStatus.RUNNING)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 与 [JmRepository.READING_KIND] 一致：阅读位置表用 "JM" 当业务类型。 */
    private val READING_KIND = "JM"

    private fun album() = JmAlbumEntity(id = albumId, name = "测试本子", albumFolderName = folderName)

    private fun seedAlbum() {
        runBlocking { db.jmAlbumDao().insertIgnore(album()) }
    }

    private fun seedChapter() {
        runBlocking {
            db.jmChapterDao().replaceChapter(
                albumId,
                1L,
                listOf(
                    JmChapterImageEntity(
                        albumId = albumId,
                        chapterId = 1L,
                        chapterTitle = "第1话",
                        chapterName = "第1话",
                        imageFile = "00001.webp",
                        imageSort = 1,
                    ),
                ),
            )
        }
        val dir = dirs.chapterDir(folderName, "第1话").apply { mkdirs() }
        File(dir, "00001.webp").writeBytes(ByteArray(64) { 7 })
    }

    /** 直接在"下载目录"里造一个成品，等价于"之前导出过"。 */
    private fun publish(name: String) {
        File(publisher.dir, name).writeBytes(ByteArray(16))
    }

    private class RecordingCoverCache : CoverCache {
        val evicted = mutableListOf<Long>()
        override fun evict(albumId: Long) {
            evicted += albumId
        }
    }

    private class FakePublisher(val dir: File) : ExportPublisher {
        override fun publish(source: File, displayName: String, mimeType: String): ExportPublisher.Published {
            source.copyTo(File(dir, displayName), overwrite = true)
            return ExportPublisher.Published(displayName, "下载/HaruHiBox", null)
        }

        override fun exists(displayName: String): Boolean = File(dir, displayName).isFile

        override fun uriOf(displayName: String): android.net.Uri? = null

        override fun delete(displayName: String): Boolean = File(dir, displayName).delete()
    }
}
