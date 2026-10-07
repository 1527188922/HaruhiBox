package com.haruhi.haruhibox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.haruhibox.core.database.entity.DownloadTaskEntity
import com.haruhi.haruhibox.core.database.entity.DownloadType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 持久化下载任务的语义测试（计划 §11.2「持久化任务」验收项）：
 * 同 `bizKey` 去重、杀进程后的恢复、章节计数器不回退、完成历史裁剪。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadTaskDaoTest {

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
    fun `only one active task per type and bizKey`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "480854", status = DownloadStatus.RUNNING))

        val active = dao.activeTask(DownloadType.JM_ALBUM, "480854")
        assertThat(active).isNotNull()
        assertThat(active!!.status).isEqualTo(DownloadStatus.RUNNING)
    }

    @Test
    fun `a finished task does not block a new one for the same bizKey`() = runTest {
        val dao = db.downloadTaskDao()
        val finished = dao.insert(task(bizKey = "480854", status = DownloadStatus.SUCCESS))

        assertThat(dao.activeTask(DownloadType.JM_ALBUM, "480854")).isNull()

        // 重新下载同一本：允许新建活跃任务
        dao.insert(task(bizKey = "480854", status = DownloadStatus.QUEUED))
        val active = dao.activeTask(DownloadType.JM_ALBUM, "480854")
        assertThat(active).isNotNull()
        assertThat(active!!.id).isNotEqualTo(finished)
    }

    @Test
    fun `different types with the same bizKey are independent`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "480854", type = DownloadType.JM_ALBUM, status = DownloadStatus.RUNNING))

        assertThat(dao.activeTask(DownloadType.JM_EXPORT_ZIP, "480854")).isNull()
        assertThat(dao.activeTask(DownloadType.JM_ALBUM, "480854")).isNotNull()
    }

    @Test
    fun `bili bizKey is bvid_cid so multi part videos do not collide`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "BV1xx411c7mD_111", type = DownloadType.BILI_VIDEO, status = DownloadStatus.RUNNING))
        dao.insert(task(bizKey = "BV1xx411c7mD_222", type = DownloadType.BILI_VIDEO, status = DownloadStatus.RUNNING))

        assertThat(dao.activeTask(DownloadType.BILI_VIDEO, "BV1xx411c7mD_111")).isNotNull()
        assertThat(dao.activeTask(DownloadType.BILI_VIDEO, "BV1xx411c7mD_222")).isNotNull()
        assertThat(dao.activeTasks()).hasSize(2)
    }

    @Test
    fun `interrupted tasks are requeued but paused and cancelled are untouched`() = runTest {
        val dao = db.downloadTaskDao()
        val running = dao.insert(task(bizKey = "a", status = DownloadStatus.RUNNING))
        val waiting = dao.insert(task(bizKey = "b", status = DownloadStatus.WAITING_NETWORK))
        val paused = dao.insert(task(bizKey = "c", status = DownloadStatus.PAUSED))
        val cancelled = dao.insert(task(bizKey = "d", status = DownloadStatus.CANCELLED))
        val success = dao.insert(task(bizKey = "e", status = DownloadStatus.SUCCESS))

        val requeued = dao.requeueInterrupted(now = 1_000L)

        assertThat(requeued).isEqualTo(2)
        assertThat(dao.findById(running)!!.status).isEqualTo(DownloadStatus.QUEUED)
        assertThat(dao.findById(waiting)!!.status).isEqualTo(DownloadStatus.QUEUED)
        // 暂停等用户点恢复；取消**不复活**（§D.3）
        assertThat(dao.findById(paused)!!.status).isEqualTo(DownloadStatus.PAUSED)
        assertThat(dao.findById(cancelled)!!.status).isEqualTo(DownloadStatus.CANCELLED)
        assertThat(dao.findById(success)!!.status).isEqualTo(DownloadStatus.SUCCESS)
    }

    @Test
    fun `chapter progress update is idempotent and never implicitly resets`() = runTest {
        val dao = db.downloadTaskDao()
        val taskId = dao.insert(task(bizKey = "1", status = DownloadStatus.RUNNING))
        dao.upsertChapters(
            listOf(
                DownloadTaskChapterEntity(taskId = taskId, chapterId = 10, sort = 1, totalImages = 20),
                DownloadTaskChapterEntity(taskId = taskId, chapterId = 11, sort = 2, totalImages = 30),
            ),
        )

        dao.updateChapterProgress(taskId, 10, doneImages = 12, status = DownloadStatus.RUNNING, message = null, now = 1)
        var chapters = dao.chapters(taskId)
        assertThat(chapters.first { it.chapterId == 10L }.doneImages).isEqualTo(12)

        // 恢复时按磁盘重算后写回同样的值：结果不变（不重复累加）
        dao.updateChapterProgress(taskId, 10, doneImages = 12, status = DownloadStatus.RUNNING, message = null, now = 2)
        chapters = dao.chapters(taskId)
        assertThat(chapters.first { it.chapterId == 10L }.doneImages).isEqualTo(12)

        // 继续下载推进到 20
        dao.updateChapterProgress(taskId, 10, doneImages = 20, status = DownloadStatus.SUCCESS, message = null, now = 3)
        chapters = dao.chapters(taskId)
        assertThat(chapters.first { it.chapterId == 10L }.doneImages).isEqualTo(20)
        assertThat(chapters.first { it.chapterId == 10L }.status).isEqualTo(DownloadStatus.SUCCESS)
    }

    @Test
    fun `upserting the same chapter twice does not duplicate rows`() = runTest {
        val dao = db.downloadTaskDao()
        val taskId = dao.insert(task(bizKey = "1", status = DownloadStatus.RUNNING))
        val chapter = DownloadTaskChapterEntity(taskId = taskId, chapterId = 10, sort = 1, totalImages = 20)

        dao.upsertChapters(listOf(chapter))
        dao.upsertChapters(listOf(chapter.copy(totalImages = 25)))

        val chapters = dao.chapters(taskId)
        assertThat(chapters).hasSize(1)
        assertThat(chapters.first().totalImages).isEqualTo(25)
    }

    @Test
    fun `active and finished lists are separated and failures are not active`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "running", status = DownloadStatus.RUNNING))
        dao.insert(task(bizKey = "queued", status = DownloadStatus.QUEUED))
        dao.insert(task(bizKey = "failed", status = DownloadStatus.FAIL))
        dao.insert(task(bizKey = "done", status = DownloadStatus.SUCCESS))

        val active = dao.observeActive().first()
        assertThat(active.map { it.bizKey }).containsExactly("running", "queued")
        // 失败不算"正在运行"（§9 第 9 条）
        assertThat(active.none { it.status == DownloadStatus.FAIL }).isTrue()

        val finished = dao.observeFinished(limit = 10).first()
        assertThat(finished.map { it.bizKey }).containsExactly("failed", "done")
    }

    @Test
    fun `badge counts only queued and running and tracks failures separately`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "running", status = DownloadStatus.RUNNING))
        dao.insert(task(bizKey = "queued", status = DownloadStatus.QUEUED))
        dao.insert(task(bizKey = "paused", status = DownloadStatus.PAUSED))
        dao.insert(task(bizKey = "failed1", status = DownloadStatus.FAIL))
        dao.insert(task(bizKey = "failed2", status = DownloadStatus.FAIL))

        assertThat(dao.observeBadgeCount().first()).isEqualTo(2)
        assertThat(dao.observeFailedCount().first()).isEqualTo(2)
    }

    @Test
    fun `finished history is trimmed to the newest N entries`() = runTest {
        val dao = db.downloadTaskDao()
        repeat(25) { index ->
            val id = dao.insert(task(bizKey = "done$index", status = DownloadStatus.SUCCESS))
            dao.finish(id, DownloadStatus.SUCCESS, null, endTime = 1_000L + index)
        }
        dao.insert(task(bizKey = "running", status = DownloadStatus.RUNNING))

        val removed = dao.trimFinished(keep = 20)

        assertThat(removed).isEqualTo(5)
        assertThat(dao.observeFinished(limit = 100).first()).hasSize(20)
        // 活跃任务不参与裁剪
        assertThat(dao.activeTasks()).hasSize(1)
    }

    @Test
    fun `clearFinished keeps active tasks`() = runTest {
        val dao = db.downloadTaskDao()
        dao.insert(task(bizKey = "running", status = DownloadStatus.RUNNING))
        dao.insert(task(bizKey = "done", status = DownloadStatus.SUCCESS))
        dao.insert(task(bizKey = "failed", status = DownloadStatus.FAIL))

        assertThat(dao.clearFinished()).isEqualTo(2)
        assertThat(dao.activeTasks()).hasSize(1)
    }

    @Test
    fun `payload json round trips through the entity`() = runTest {
        val dao = db.downloadTaskDao()
        val payload = """{"aid":480854,"chapterIds":[1,2,3],"startChapter":1}"""
        val id = dao.insert(task(bizKey = "480854", status = DownloadStatus.QUEUED, payload = payload))

        assertThat(dao.findById(id)!!.payload).isEqualTo(payload)
    }

    private fun task(
        bizKey: String,
        type: DownloadType = DownloadType.JM_ALBUM,
        status: DownloadStatus = DownloadStatus.QUEUED,
        payload: String? = null,
    ) = DownloadTaskEntity(type = type, bizKey = bizKey, status = status, payload = payload)
}
