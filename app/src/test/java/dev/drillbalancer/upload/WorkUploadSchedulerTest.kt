package dev.drillbalancer.upload

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.label.LabelEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WorkUploadScheduler on WorkManager's test runtime: the requests, REPLACE while waiting (keeping an overwrite and a wait), one more upload after a running one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class WorkUploadSchedulerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gh = FakeGitHub()
    private val labeler = UploadTestData.labeler()
    private val store by lazy { UploadTestData.store(tmp.root, labeler) }
    private val prefs = FakeUploadPrefs()
    private val tokens = FakeTokenStore(FakeGitHub.TOKEN)
    private lateinit var scheduler: WorkUploadScheduler
    private val deps by lazy { UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t -> HttpGitHubApi(o, n, t, gh.baseUrl) }) }
    private val wm get() = WorkManager.getInstance(context)

    @Before
    fun init() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                UploadWorker(appContext, workerParameters, deps)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).setMinimumLoggingLevel(Log.INFO).build(),
        )
        scheduler = WorkUploadScheduler(context)
    }

    @After
    fun close() = gh.close()

    private fun infos(id: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(WorkUploadScheduler.name(id)).get()

    private fun await(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) throw AssertionError("still not: $what")
            Thread.sleep(20)
        }
    }

    private fun meetConstraints(work: UUID) = WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work)

    @Test
    fun theRequest() {
        val wifi = WorkUploadScheduler.request("20261026T143012Z_k7q2_9f3a", wifiOnly = true).workSpec
        assertEquals(NetworkType.UNMETERED, wifi.constraints.requiredNetworkType)
        assertTrue(wifi.expedited)
        assertEquals(BackoffPolicy.EXPONENTIAL, wifi.backoffPolicy)
        assertEquals(30_000L, wifi.backoffDelayDuration)
        assertEquals("20261026T143012Z_k7q2_9f3a", wifi.input.getString(UploadWorker.KEY_ID))
        assertEquals(UploadWorker::class.java.name, wifi.workerClassName)
        val any = WorkUploadScheduler.request("20261026T143012Z_k7q2_9f3a", wifiOnly = false, delayMs = 90_000).workSpec
        assertEquals(NetworkType.CONNECTED, any.constraints.requiredNetworkType)
        assertFalse("a delayed one cannot be expedited", any.expedited)
        assertEquals(90_000L, any.initialDelay)
        assertTrue(
            WorkUploadScheduler.request("20261026T143012Z_k7q2_9f3a", true).tags.containsAll(setOf(WorkUploadScheduler.TAG, "rec:20261026T143012Z_k7q2_9f3a")),
        )
        // the overwrite and the wait as tags too (what a REPLACE carries over)
        assertFalse(wifi.input.getBoolean(UploadWorker.KEY_OVERWRITE, true))
        assertEquals(null, WorkUploadScheduler.notBefore(WorkUploadScheduler.request("20261026T143012Z_k7q2_9f3a", true).tags))
        val retry = WorkUploadScheduler.request("20261026T143012Z_k7q2_9f3a", wifiOnly = true, delayMs = 90_000, overwrite = true, nowMs = 1_000)
        assertTrue(retry.workSpec.input.getBoolean(UploadWorker.KEY_OVERWRITE, false))
        assertTrue(WorkUploadScheduler.OVERWRITE in retry.tags)
        assertEquals(91_000L, WorkUploadScheduler.notBefore(retry.tags))
    }

    /**
     * A waiting upload replaced (Upload now, a Save, Wi-Fi only changed) keeps what it had: the
     * user's Retry over a conflict stays an overwrite, and a rate limit's wait is not cut short.
     */
    @Test
    fun aReplacedUploadKeepsItsOverwriteAndItsWait() = runBlocking {
        val id = "20261026T143012Z_k7q2_9f3a"
        scheduler.enqueue(id, wifiOnly = true, overwrite = true)
        scheduler.enqueue(id, wifiOnly = false)
        val retried = infos(id).single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, retried.constraints.requiredNetworkType)
        assertTrue("still an overwrite", WorkUploadScheduler.OVERWRITE in retried.tags)

        scheduler.enqueue(id, wifiOnly = false, delayMs = 3_600_000)          // a rate limit
        scheduler.enqueue(id, wifiOnly = true)                                // Upload now
        val live = infos(id).single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, live.constraints.requiredNetworkType)
        assertTrue("${live.initialDelayMillis}", live.initialDelayMillis in 3_590_000L..3_600_000L)
        assertTrue(WorkUploadScheduler.OVERWRITE in live.tags)
        // a wait that is over is not kept
        val old = WorkUploadScheduler.request(id, wifiOnly = true, delayMs = 1_000, nowMs = System.currentTimeMillis() - 60_000)
        wm.enqueueUniqueWork(WorkUploadScheduler.name(id), androidx.work.ExistingWorkPolicy.REPLACE, old).result.get()
        scheduler.enqueue(id, wifiOnly = true)
        assertEquals(0L, infos(id).single { !it.state.isFinished }.initialDelayMillis)
    }

    @Test
    fun aWaitingUploadIsReplaced() = runBlocking {
        val id = "20261026T143012Z_k7q2_9f3a"
        scheduler.enqueue(id, wifiOnly = true)
        scheduler.enqueue(id, wifiOnly = false)
        val live = infos(id).filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertEquals(WorkInfo.State.ENQUEUED, live.single().state)
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
        assertEquals(mapOf(id to ActiveUpload.Queued), scheduler.active.first())
        scheduler.cancelAll()
        assertTrue(infos(id).all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(emptyMap<String, ActiveUpload>(), scheduler.active.first())
    }

    /**
     * The end to end case: an upload runs (held at its second blob), the review is saved again
     * meanwhile and enqueued; the new upload runs after the first and commits the new labels.
     */
    @Test
    fun aSaveDuringAnUploadIsUploadedAfterIt() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        var posts = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && ++posts == 2) {
                held.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            null
        }
        scheduler.enqueue(id, wifiOnly = true)
        val first = infos(id).single()
        meetConstraints(first.id)
        assertTrue("the upload runs", held.await(30, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, infos(id).single { it.id == first.id }.state)
        assertTrue(scheduler.active.first()[id] is ActiveUpload.Running)

        UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }   // the Save does not wait for it
        scheduler.enqueue(id, wifiOnly = true)
        val second = infos(id).single { it.id != first.id }
        assertEquals("after the running one", WorkInfo.State.BLOCKED, second.state)

        release.countDown()
        await("the first upload ends") { infos(id).single { it.id == first.id }.state == WorkInfo.State.SUCCEEDED }
        await("the second is enqueued") { infos(id).single { it.id == second.id }.state == WorkInfo.State.ENQUEUED }
        meetConstraints(second.id)
        await("the second upload ends") { infos(id).single { it.id == second.id }.state == WorkInfo.State.SUCCEEDED }

        val local = Finalizer.readJson(File(store.dir(id), Finalizer.META))
        assertEquals(listOf("Update labels of $id"), gh.history().map { it.message }.filter { it.startsWith("Update labels") })
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.META).readBytes()), gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.META}"])
        assertTrue(local != null && LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
    }

    /**
     * A Save during an upload that then fails for good (here a 404 on its blob): the upload
     * appended for that Save still runs and commits the new state (a Result.failure would fail it
     * unrun, WorkManager's rule for a chain).
     */
    @Test
    fun aSaveDuringAnUploadThatFailsIsStillUploaded() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        var posts = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && ++posts == 2) {
                held.countDown()
                release.await(30, TimeUnit.SECONDS)
                FakeGitHub.error(404, "Not Found")
            } else {
                null
            }
        }
        scheduler.enqueue(id, wifiOnly = true)
        val first = infos(id).single()
        meetConstraints(first.id)
        assertTrue(held.await(30, TimeUnit.SECONDS))
        UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }
        scheduler.enqueue(id, wifiOnly = true)
        val second = infos(id).single { it.id != first.id }
        assertEquals(WorkInfo.State.BLOCKED, second.state)
        release.countDown()
        await("the first upload ends") { infos(id).single { it.id == first.id }.state.isFinished }
        assertTrue(UploadRecord.read(store.dir(id))!!.error!!.startsWith("Repo not found"))
        await("the second is enqueued, not failed") { infos(id).single { it.id == second.id }.state == WorkInfo.State.ENQUEUED }
        meetConstraints(second.id)
        await("the second upload ends") { infos(id).single { it.id == second.id }.state == WorkInfo.State.SUCCEEDED }
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.META).readBytes()), gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.META}"])
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
        assertEquals(null, UploadRecord.read(store.dir(id))!!.error)
    }

    /**
     * Wi-Fi only switched on while an upload runs over mobile data with another appended after
     * it: both are replaced by one that waits for Wi-Fi (the running one is stopped), so none goes
     * on over mobile data; it then resumes and commits.
     */
    @Test
    fun wifiOnlySwitchedOnStopsTheUploadsOverMobileData() = runBlocking {
        prefs.flow.value = prefs.flow.value.copy(wifiOnly = false)
        val id = UploadTestData.reviewed(store, labeler)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        var posts = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && ++posts == 2) {
                held.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            null
        }
        scheduler.enqueue(id, wifiOnly = false)
        val first = infos(id).single()
        meetConstraints(first.id)
        assertTrue(held.await(30, TimeUnit.SECONDS))
        UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }
        scheduler.enqueue(id, wifiOnly = false)
        assertEquals(NetworkType.CONNECTED, infos(id).single { it.state == WorkInfo.State.BLOCKED }.constraints.requiredNetworkType)

        prefs.flow.value = prefs.flow.value.copy(wifiOnly = true)
        val scope = CoroutineScope(SupervisorJob())
        try {
            UploadQueue(store, scheduler, prefs, tokens, scope).requeue()
        } finally {
            scope.cancel()
        }
        // REPLACE cancels it and drops it from the unique work
        await("the running one is stopped") { wm.getWorkInfoById(first.id).get()?.state.let { it == null || it == WorkInfo.State.CANCELLED } }
        val live = infos(id).filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertEquals(NetworkType.UNMETERED, live.single().constraints.requiredNetworkType)
        release.countDown()
        meetConstraints(live.single().id)
        await("the new one ends") { infos(id).single { it.id == live.single().id }.state == WorkInfo.State.SUCCEEDED }
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.META).readBytes()), gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.META}"])
    }

    /**
     * Wi-Fi only switched off while an upload runs on Wi-Fi with another appended after it (a
     * Save): both are replaced by one for any network (the running one is stopped), so neither
     * waits for Wi-Fi; it then resumes and commits.
     */
    @Test
    fun wifiOnlySwitchedOffLetsTheUploadsGoOverAnyNetwork() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        var posts = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && ++posts == 2) {
                held.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            null
        }
        scheduler.enqueue(id, wifiOnly = true)
        val first = infos(id).single()
        meetConstraints(first.id)
        assertTrue(held.await(30, TimeUnit.SECONDS))
        UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }
        scheduler.enqueue(id, wifiOnly = true)
        assertEquals(NetworkType.UNMETERED, infos(id).single { it.state == WorkInfo.State.BLOCKED }.constraints.requiredNetworkType)

        prefs.flow.value = prefs.flow.value.copy(wifiOnly = false)
        val scope = CoroutineScope(SupervisorJob())
        try {
            UploadQueue(store, scheduler, prefs, tokens, scope).requeue()
        } finally {
            scope.cancel()
        }
        await("the running one is stopped") { wm.getWorkInfoById(first.id).get()?.state.let { it == null || it == WorkInfo.State.CANCELLED } }
        val live = infos(id).filter { !it.state.isFinished }
        assertEquals(1, live.size)
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
        release.countDown()
        meetConstraints(live.single().id)
        await("the new one ends") { infos(id).single { it.id == live.single().id }.state == WorkInfo.State.SUCCEEDED }
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.META).readBytes()), gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.META}"])
    }

    /** A worker that waits for the upload before it (one at a time) is listed as queued, not uploading. */
    @Test
    fun aWorkerWaitingForTheOneBeforeItIsQueued() {
        val tags = setOf(WorkUploadScheduler.TAG, "rec:20261026T143012Z_k7q2_9f3a")
        fun running(progress: androidx.work.Data) = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, tags, androidx.work.Data.EMPTY, progress)
        assertEquals(
            mapOf("20261026T143012Z_k7q2_9f3a" to ActiveUpload.Queued),
            WorkUploadScheduler.activeOf(listOf(running(androidx.work.workDataOf(UploadWorker.KEY_WAITING to true)))),
        )
        assertEquals(
            mapOf("20261026T143012Z_k7q2_9f3a" to ActiveUpload.Running(12)),
            WorkUploadScheduler.activeOf(listOf(running(androidx.work.workDataOf(UploadWorker.KEY_PCT to 12)))),
        )
    }
}
