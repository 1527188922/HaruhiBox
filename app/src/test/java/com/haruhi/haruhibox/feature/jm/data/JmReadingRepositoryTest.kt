package com.haruhi.haruhibox.feature.jm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * 阅读位置仓库的语义验收（计划 §9 第 8 条）：
 * 「阅读位置自动持久化（本子、章节、页/图片标识、页内位置），重启后可继续」。
 *
 * 关键点：
 * - 位置用**图片文件名**定位，不用页码（章节重新拉清单时页数可能变）；
 * - 「继续阅读」入口取的是**最近一次读过位置的本子**；
 * - 磁盘上只有"存在且非空"的图片才算可读（0 字节残留不算，与下载层判定一致）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmReadingRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var reading: JmReadingRepository

    private val albumId = 480854L
    private val chapterId = 123456L
    private val folderName = "测试本子_JM480854"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        reading = JmReadingRepository(
            positionDao = db.playbackPositionDao(),
            chapterDao = db.jmChapterDao(),
            recentDao = db.jmRecentAlbumDao(),
            dirs = dirs,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `saving a position keeps chapter image and offset ratio`() = runTest {
        reading.savePosition(albumId, chapterId, "00012.webp", imageIndex = 11, offsetRatio = 0.42f)

        val position = reading.positionOf(albumId)
        assertThat(position).isNotNull()
        assertThat(position!!.chapterId).isEqualTo(chapterId)
        assertThat(position.imageFile).isEqualTo("00012.webp")
        assertThat(position.imageIndex).isEqualTo(11)
        assertThat(position.offsetRatio).isWithin(0.001f).of(0.42f)
    }

    @Test
    fun `saving twice updates the same row instead of adding one`() = runTest {
        reading.savePosition(albumId, chapterId, "00001.webp", 0, 0f)
        reading.savePosition(albumId, chapterId, "00020.webp", 19, 0.9f)

        val position = reading.positionOf(albumId)
        assertThat(position!!.imageFile).isEqualTo("00020.webp")
        // 位置表是"每本一条"，不能随阅读而增长
        assertThat(db.playbackPositionDao().observeRecent(JmReadingRepository.KIND, 10).first()).hasSize(1)
    }

    @Test
    fun `offset ratio is clamped to zero through one`() = runTest {
        reading.savePosition(albumId, chapterId, "00001.webp", 0, 3.5f)
        assertThat(reading.positionOf(albumId)!!.offsetRatio).isEqualTo(1f)

        reading.savePosition(albumId, chapterId, "00001.webp", 0, -2f)
        assertThat(reading.positionOf(albumId)!!.offsetRatio).isEqualTo(0f)
    }

    @Test
    fun `last read returns the most recently updated album`() = runTest {
        reading.savePosition(albumId, chapterId, "00001.webp", 0, 0f)
        // 时间戳是毫秒，测试里连续写入会落在同一毫秒，所以显式隔开
        Thread.sleep(5)
        reading.savePosition(999L, 888L, "00002.webp", 1, 0f)

        val last = reading.observeLastRead().first()
        assertThat(last!!.albumId).isEqualTo(999L)
        assertThat(last.chapterId).isEqualTo(888L)
    }

    @Test
    fun `clearing a position removes the continue reading entry`() = runTest {
        reading.savePosition(albumId, chapterId, "00001.webp", 0, 0f)
        reading.clearPosition(albumId)

        assertThat(reading.positionOf(albumId)).isNull()
        assertThat(reading.observeLastRead().first()).isNull()
    }

    @Test
    fun `readable images list only files that exist and are not empty`() = runTest {
        db.jmChapterDao().replaceChapter(
            albumId,
            chapterId,
            listOf("00001.webp", "00002.webp", "00003.webp").mapIndexed { index, name ->
                JmChapterImageEntity(
                    albumId = albumId,
                    chapterId = chapterId,
                    chapterTitle = "第1话",
                    imageFile = name,
                    imageSort = index + 1,
                )
            },
        )
        val chapterDir = dirs.chapterDir(folderName, "第1话")
        chapterDir.mkdirs()
        File(chapterDir, "00001.webp").writeBytes(byteArrayOf(1, 2, 3))
        // 0 字节残留：必须重新下载，不算"可读"
        File(chapterDir, "00002.webp").writeBytes(ByteArray(0))

        val images = reading.readableImages(album(), chapterId)

        assertThat(images.map { it.name }).containsExactly("00001.webp")
    }

    @Test
    fun `chapter folder name follows title then name then id`() = runTest {
        assertThat(reading.chapterFolderName(albumId, chapterId)).isNull()

        db.jmChapterDao().replaceChapter(
            albumId,
            chapterId,
            listOf(
                JmChapterImageEntity(
                    albumId = albumId,
                    chapterId = chapterId,
                    chapterTitle = null,
                    chapterName = null,
                    imageFile = "00001.webp",
                    imageSort = 1,
                ),
            ),
        )
        assertThat(reading.chapterFolderName(albumId, chapterId)).isEqualTo(chapterId.toString())
    }

    private fun album() = JmAlbumEntity(
        id = albumId,
        name = "测试本子",
        albumFolderName = folderName,
    )
}
