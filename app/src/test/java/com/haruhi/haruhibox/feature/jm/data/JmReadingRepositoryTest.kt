package com.haruhi.haruhibox.feature.jm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读位置仓库的语义验收（计划 §9 第 8 条）：
 * 「阅读位置自动持久化（本子、章节、页/图片标识、页内位置），重启后可继续」。
 *
 * 关键点：
 * - 位置用**图片文件名**定位，不用页码（章节重新拉清单时页数可能变）；
 * - 「继续阅读」入口取的是**最近一次读过位置的本子**。
 *
 * > 图片"在不在磁盘上、要不要联网取"已经移到 `JmImageStore`，由
 * > `JmImageStoreTest` 覆盖；这个类现在只关心位置与清单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmReadingRepositoryTest {

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var reading: JmReadingRepository

    private val albumId = 480854L
    private val chapterId = 123456L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        reading = JmReadingRepository(
            positionDao = db.playbackPositionDao(),
            chapterDao = db.jmChapterDao(),
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
}
