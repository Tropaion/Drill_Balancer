package dev.drillbalancer.upload

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.AudioMute
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UploadWorker through TestListenableWorkerBuilder against the in-memory GitHub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UploadWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gh = FakeGitHub()
    private val labeler = UploadTestData.labeler()
    private val store by lazy { UploadTestData.store(tmp.root, labeler) }
    private val prefs = FakeUploadPrefs()
    private val tokens = FakeTokenStore(FakeGitHub.TOKEN)
    private val scheduler = FakeScheduler()
    private val deps by lazy { UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t -> HttpGitHubApi(o, n, t, gh.baseUrl) }) }
    private val progress: MutableList<Int> = Collections.synchronizedList(ArrayList())
    private val foreground: MutableList<ForegroundInfo> = Collections.synchronizedList(ArrayList())

    @After
    fun close() = gh.close()

    private fun worker(id: String, attempt: Int = 0, deps: UploadDeps = this.deps, foregroundAllowed: Boolean = true, onWaiting: () -> Unit = {}): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_ID to id))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    UploadWorker(appContext, workerParameters, deps)
            })
            .setProgressUpdater { _, _, data ->
                if (data.getBoolean(UploadWorker.KEY_WAITING, false)) onWaiting()
                if (!data.getBoolean(UploadWorker.KEY_WAITING, false) && data.keyValueMap.isNotEmpty()) progress += data.getInt(UploadWorker.KEY_PCT, -1)
                CallbackToFutureAdapter.getFuture { it.set(null) }
            }
            .setForegroundUpdater { _, _, info ->
                // Android 12+ refuses a foreground service started from the background
                if (!foregroundAllowed) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
                foreground += info
                CallbackToFutureAdapter.getFuture { it.set(null) }
            }
            .build()

    private fun run(id: String, attempt: Int = 0, deps: UploadDeps = this.deps, foregroundAllowed: Boolean = true): ListenableWorker.Result =
        runBlocking { worker(id, attempt, deps, foregroundAllowed).doWork() }

    /** An upload that ended without a retry and with [message] (in upload.json too): never Result.failure, so an upload appended after it still runs. */
    private fun ended(message: String) = ListenableWorker.Result.success(workDataOf(UploadWorker.KEY_ERROR to message))

    private val ListenableWorker.Result.ended: Boolean get() = this is ListenableWorker.Result.Success && outputData.getString(UploadWorker.KEY_ERROR) != null

    private fun stream(id: String) = File(store.dir(id), RecordingStore.STREAM)
    private fun error(id: String) = UploadRecord.read(store.dir(id))?.error
    private fun kind(id: String) = UploadRecord.read(store.dir(id))?.failure

    @Test
    fun anUploadDeletesTheJournalAndKeepsTheDatasetFiles() {
        val id = UploadTestData.reviewed(store, labeler)
        assertTrue(stream(id).isFile)
        val changes = store.changes.value
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertFalse(stream(id).exists())
        for (f in listOf(Finalizer.META, Finalizer.EVENTS, Finalizer.AUDIO)) assertTrue(f, File(store.dir(id), f).isFile)
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
        assertTrue("the list reloads", store.changes.value > changes)
        assertEquals(UploadStage.Done, UploadQueue.stage("reviewed", LocalUpload.of(store.dir(id), prefs.flow.value.repo), null, false))
        // progress up to 99 % while the blobs go, a foreground notification of type dataSync
        assertTrue(progress.toString(), progress.isNotEmpty() && progress.all { it in 0..99 } && progress.last() == 99)
        assertTrue(foreground.isNotEmpty())
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.first().foregroundServiceType)
        assertEquals("uploads", foreground.first().notification.channelId)
        // the review can still be edited and saved: the dataset files are enough
        UploadTestData.save(store, labeler, id)
    }

    /**
     * The progress notification at most once a second (Android sheds a package's notification
     * updates beyond 5 a second), and always the final state: on a clock that stands still, the
     * first percent, at most one held back per second of the upload, and the last.
     */
    @Test
    fun theProgressNotificationIsThrottled() {
        val still = UploadDeps(store, prefs, tokens, scheduler, api = deps.api, clock = { 0L })
        val id = UploadTestData.reviewed(store, labeler)
        assertEquals(ListenableWorker.Result.success(), run(id, deps = still))
        val shown = foreground.map { it.notification.extras }.filter { !it.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) }.map { it.getInt(Notification.EXTRA_PROGRESS) }
        assertTrue(progress.toString(), progress.size > 2)
        assertEquals(progress.first(), shown.first())
        assertEquals(99, shown.last())
        assertTrue(shown.toString(), shown.size < progress.size && shown.zipWithNext().all { (a, b) -> a < b })
    }

    /** One a second; one held back is shown when the second is over (a pause leaves no older percent shown); the final at once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theThrottleShowsTheLatestPercentAfterAPause() = runTest {
        val shown = ArrayList<Int>()
        val t = UploadNotifications.Throttle(backgroundScope, { testScheduler.currentTime }) { shown += it }
        t.offer(40)
        assertEquals(listOf(40), shown)
        advanceTimeBy(300)
        t.offer(50)
        advanceTimeBy(100)
        t.offer(60)                     // 50 and 60 within the second: 60 is held back
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(40), shown)
        advanceTimeBy(100)              // the second is over (1000 ms), the callbacks paused
        runCurrent()
        assertEquals(listOf(40, 60), shown)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals("nothing more without an offer", listOf(40, 60), shown)
        t.offer(70)                     // a second after 60: at once
        assertEquals(listOf(40, 60, 70), shown)
        t.offer(80)
        t.offer(99, final = true)       // the final at once, the held-back 80 dropped
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(40, 60, 70, 99), shown)
        t.offer(99, final = true)       // each percent once
        assertEquals(listOf(40, 60, 70, 99), shown)
        // cancelled (the upload ended another way): one held back never comes
        val after = ArrayList<Int>()
        val c = UploadNotifications.Throttle(backgroundScope, { testScheduler.currentTime }) { after += it }
        c.offer(10)
        c.offer(20)
        c.cancel()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(10), after)
    }

    @Test
    fun keepLocalCopiesKeepsTheJournal() {
        prefs.flow.value = prefs.flow.value.copy(keepLocal = true)
        val id = UploadTestData.reviewed(store, labeler)
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertTrue(stream(id).isFile)
    }

    @Test
    fun withoutATokenItFailsWithTheReason() {
        val id = UploadTestData.reviewed(store, labeler)
        runBlocking { tokens.remove() }
        val r = run(id)
        assertEquals(ended(UploadQueue.NO_TOKEN), r)
        assertEquals(UploadQueue.NO_TOKEN, error(id))
        assertEquals(FailureKind.NO_TOKEN, kind(id))
        assertEquals(UploadStage.Failed(UploadQueue.NO_TOKEN, FailureKind.NO_TOKEN), UploadQueue.stage("reviewed", LocalUpload.of(store.dir(id), prefs.flow.value.repo), null, false))
        assertTrue(gh.requests.isEmpty())
        assertTrue(stream(id).isFile)
    }

    @Test
    fun aRejectedTokenStopsTheQueue() {
        val a = UploadTestData.reviewed(store, labeler, "rich")
        val b = UploadTestData.reviewed(store, labeler, "golden")
        runBlocking { tokens.set("fake_token_wrong_000000000000000000") }
        assertEquals(ended(UploadQueue.TOKEN_REJECTED), run(a))
        assertEquals(UploadQueue.TOKEN_REJECTED, error(a))
        assertEquals(FailureKind.TOKEN_REJECTED, kind(a))
        assertTrue(tokens.flow.value.rejected)
        assertEquals(1, scheduler.cancels)
        // the next one does not try: no request, the same reason
        val asked = gh.requests.size
        assertEquals(ended(UploadQueue.TOKEN_REJECTED), run(b))
        assertEquals(asked, gh.requests.size)
        assertEquals(UploadQueue.TOKEN_REJECTED, error(b))
        // a new token: it goes again
        runBlocking { tokens.set(FakeGitHub.TOKEN) }
        assertEquals(ListenableWorker.Result.success(), run(b))
    }

    @Test
    fun networkErrorsAreRetriedThenFail() {
        val id = UploadTestData.reviewed(store, labeler)
        gh.intercept = { r ->
            if (r.method == "POST") MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else null
        }
        assertEquals(ListenableWorker.Result.retry(), run(id, attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), run(id, attempt = UploadWorker.MAX_ATTEMPTS - 2))
        assertEquals("no error shown while it retries", null, error(id))
        assertTrue(run(id, attempt = UploadWorker.MAX_ATTEMPTS - 1).ended)
        assertTrue(error(id)!!, error(id)!!.startsWith("No connection to GitHub after ${UploadWorker.MAX_ATTEMPTS} tries"))
        assertEquals(FailureKind.NETWORK, kind(id))
        // GitHub's own trouble the same way
        gh.intercept = { r -> if (r.method == "POST") FakeGitHub.error(502, "Bad gateway") else null }
        assertEquals(ListenableWorker.Result.retry(), run(id, attempt = 1))
        gh.intercept = null
        assertEquals(ListenableWorker.Result.success(), run(id, attempt = 2))
        assertEquals(null, error(id))
        assertEquals(null, kind(id))
    }

    @Test
    fun aShortRateLimitIsWaitedOut() {
        val id = UploadTestData.reviewed(store, labeler)
        var limited = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && limited++ == 0) FakeGitHub.error(429, "slow down", "Retry-After" to "0") else null
        }
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun aLongRateLimitWaitsInTheQueue() {
        prefs.flow.value = prefs.flow.value.copy(wifiOnly = false)
        val id = UploadTestData.reviewed(store, labeler)
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs")) {
                FakeGitHub.error(403, "API rate limit exceeded", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${System.currentTimeMillis() / 1000 + 3600}")
            } else {
                null
            }
        }
        assertEquals(ListenableWorker.Result.success(), run(id))
        val e = scheduler.enqueued.single()
        assertEquals(id, e.id)
        assertFalse(e.wifiOnly)
        assertTrue("${e.delayMs}", e.delayMs in 3_590_000L..3_602_000L)
        assertEquals(null, error(id))
        assertFalse(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
    }

    @Test
    fun aMissingRepositoryFails() {
        prefs.flow.value = prefs.flow.value.copy(repo = "Tropaion/Nothing_Here")
        val id = UploadTestData.reviewed(store, labeler)
        assertTrue(run(id).ended)
        assertTrue(error(id)!!, error(id)!!.startsWith("Repo not found or no access"))
        assertEquals(FailureKind.NOT_FOUND, kind(id))
        assertTrue(stream(id).isFile)
    }

    /**
     * The settings are read after the wait for the upload before it (minutes, for a backlog):
     * mute switched on meanwhile mutes this upload.
     */
    @Test
    fun theSettingsAreTheOnesAfterTheWait() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val waiting = CountDownLatch(1)
        runBlocking { UploadWorker.UPLOADS.lock() }                      // an upload before this one
        var result: ListenableWorker.Result? = null
        val t = try {
            val w = worker(id, onWaiting = { waiting.countDown() })
            Thread { result = runBlocking { w.doWork() } }.apply { start() }.also {
                assertTrue(waiting.await(30, TimeUnit.SECONDS))
                prefs.flow.value = prefs.flow.value.copy(muteAudio = true)
            }
        } finally {
            UploadWorker.UPLOADS.unlock()
        }
        t.join(30_000)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(AudioMute.mutedSpans(Finalizer.readJson(File(store.dir(id), Finalizer.META))).isNotEmpty())
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.AUDIO).readBytes()), gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.AUDIO}"])
    }

    @Test
    fun anUnreviewedRecordingIsLeftAlone() {
        val j = FinalizerFixtures.install(store, "golden")
        store.finalize(j.recordingId)
        assertEquals(ListenableWorker.Result.success(), run(j.recordingId))
        assertTrue(gh.requests.isEmpty())
        assertTrue(stream(j.recordingId).isFile)
    }

    @Test
    fun muteAudioMutesBeforeTheUpload() {
        prefs.flow.value = prefs.flow.value.copy(muteAudio = true)
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        assertEquals(ListenableWorker.Result.success(), run(id))
        val meta = Finalizer.readJson(File(store.dir(id), Finalizer.META))!!
        assertTrue(AudioMute.mutedSpans(meta).isNotEmpty())
        val remote = gh.files()["${UploadRecord.repoPath(id)}/${Finalizer.AUDIO}"]
        assertEquals(FakeGitHub.gitSha(File(store.dir(id), Finalizer.AUDIO).readBytes()), remote)
    }

    @Test
    fun theTokenIsInNoFile() {
        val id = UploadTestData.reviewed(store, labeler)
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/commits")) FakeGitHub.error(422, "no ${FakeGitHub.TOKEN}") else null }
        assertTrue(run(id).ended)
        assertFalse(FakeGitHub.TOKEN in error(id)!!)
        assertEquals(emptyList<File>(), UploadTestData.filesHolding(tmp.root, FakeGitHub.TOKEN))
    }

    /**
     * A 401 for the token an upload started with, after the user set a new one meanwhile: the new
     * token is not marked rejected, the queue is not cancelled, and the upload goes on with it.
     */
    @Test
    fun aTokenReplacedDuringAnUploadIsNotMarkedRejected() {
        val id = UploadTestData.reviewed(store, labeler)
        runBlocking { tokens.set("fake_token_revoked_on_github_00000000") }       // GitHub no longer takes it
        var replaced = false
        gh.intercept = { _ ->
            if (!replaced) {
                replaced = true
                runBlocking { tokens.set(FakeGitHub.TOKEN) }                     // pasted while the first request is in flight
            }
            null
        }
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertFalse(tokens.flow.value.rejected)
        assertEquals(0, scheduler.cancels)
        assertNull(error(id))
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
    }

    /** The keystore failing once is no "No upload token": the upload is retried, and only the last try says what failed. */
    @Test
    fun anUnreadableTokenIsRetried() {
        val id = UploadTestData.reviewed(store, labeler)
        tokens.unreadable = true
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertNull(error(id))
        assertTrue(gh.requests.isEmpty())
        assertEquals(ended(UploadWorker.TOKEN_UNREADABLE), run(id, attempt = UploadWorker.MAX_ATTEMPTS - 1))
        tokens.unreadable = false
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
    }

    /**
     * GitHubApi calls of each upload, in order, tagged by the upload (A, B, ...); [hold] stops A's
     * first blob, [holdPatch] its branch update once GitHub took it; [onAbort]: the upload is stopped.
     */
    private class Calls(
        private val inner: GitHubApi,
        private val tag: String,
        private val log: MutableList<String>,
        private val hold: (() -> Unit)?,
        private val holdPatch: () -> Unit = {},
        private val onAbort: () -> Unit = {},
    ) : GitHubApi {
        private fun <T> note(what: String, f: () -> T): T {
            log += "$tag $what"
            return f()
        }

        override fun repo() = note("repo") { inner.repo() }
        override fun headCommit(branch: String) = note("ref") { inner.headCommit(branch) }
        override fun commitTree(commit: String) = note("commit") { inner.commitTree(commit) }
        override fun listFolder(path: String, ref: String) = note("folder") { inner.listFolder(path, ref) }
        override fun fileExists(path: String, ref: String?) = note("exists") { inner.fileExists(path, ref) }
        override fun createBlob(content: BlobContent, sent: (Long) -> Unit) = note("blob") {
            hold?.invoke()
            inner.createBlob(content, sent)
        }
        override fun createTree(baseTree: String, entries: List<TreeEntry>) = note("tree") { inner.createTree(baseTree, entries) }
        override fun createCommit(message: String, tree: String, parents: List<String>) = note("commit") { inner.createCommit(message, tree, parents) }
        override fun updateRef(branch: String, commit: String) = note("patch") { inner.updateRef(branch, commit).also { holdPatch() } }
        override fun abort() {
            log += "$tag abort"
            inner.abort()
            onAbort()
        }
    }

    /**
     * Upload now on a backlog starts a worker per recording: they upload one at a time (GitHub's
     * secondary rate limit, the branch's 422s), the second waiting (Queued) while the first sends.
     */
    @Test
    fun uploadsRunOneAtATime() {
        val a = UploadTestData.reviewed(store, labeler, "rich")
        val b = UploadTestData.reviewed(store, labeler, "golden")
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        var made = 0
        val waiting: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        val d = UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t ->
            val tag = if (made++ == 0) "A" else "B"
            val hold = if (tag == "A") ({ if (held.count > 0) { held.countDown(); release.await(30, TimeUnit.SECONDS) } }) else null
            Calls(HttpGitHubApi(o, n, t, gh.baseUrl), tag, log, hold)
        })
        val results = Collections.synchronizedMap(HashMap<String, ListenableWorker.Result>())
        val first = Thread { results[a] = run(a, deps = d) }.apply { start() }
        assertTrue(held.await(30, TimeUnit.SECONDS))
        val wb = TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_ID to b))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = UploadWorker(appContext, workerParameters, d)
            })
            .setProgressUpdater { _, _, data ->
                waiting += data.getBoolean(UploadWorker.KEY_WAITING, false)
                CallbackToFutureAdapter.getFuture { it.set(null) }
            }
            .setForegroundUpdater { _, _, _ -> CallbackToFutureAdapter.getFuture { it.set(null) } }
            .build()
        val second = Thread { results[b] = runBlocking { wb.doWork() } }.apply { start() }
        Thread.sleep(700)
        assertEquals("B asks GitHub nothing while A uploads", emptyList<String>(), log.filter { it.startsWith("B") })
        assertEquals("B says it waits", listOf(true), waiting.toList())
        release.countDown()
        first.join(30_000)
        second.join(30_000)
        assertEquals(ListenableWorker.Result.success(), results[a])
        assertEquals(ListenableWorker.Result.success(), results[b])
        val lastA = log.indexOfLast { it.startsWith("A") }
        val firstB = log.indexOfFirst { it.startsWith("B") }
        assertTrue(log.toString(), lastA < firstB)
        assertTrue(LocalUpload.of(store.dir(a), prefs.flow.value.repo).done && LocalUpload.of(store.dir(b), prefs.flow.value.repo).done)
    }

    /**
     * Started in the background (Android 12+ refuses the foreground service): an upload with more
     * to send than a background run may waits for the app (Queued, nothing sent), and the app's
     * start enqueues it; a small one goes as plain work.
     */
    @Test
    fun withoutItsForegroundServiceABigUploadWaitsForTheApp() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val small = UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t -> HttpGitHubApi(o, n, t, gh.baseUrl) }, backgroundMaxBytes = 1000)
        assertEquals(ListenableWorker.Result.success(), run(id, deps = small, foregroundAllowed = false))
        assertTrue(gh.blobPosts.isEmpty())
        val local = LocalUpload.of(store.dir(id), prefs.flow.value.repo)
        assertTrue(local.waiting && !local.done && local.error == null)
        assertEquals(UploadStage.Queued, UploadQueue.stage("reviewed", local, null, false))
        assertTrue(stream(id).isFile)
        // the app comes into view: it is enqueued
        val scope = CoroutineScope(SupervisorJob())
        UploadQueue(store, scheduler, prefs, tokens, scope, Dispatchers.Unconfined).appVisible()
        val end = System.currentTimeMillis() + 10_000
        while (scheduler.enqueued.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        scope.cancel()
        assertEquals(listOf(FakeScheduler.Enqueued(id, true, 0)), scheduler.enqueued)
        // with the foreground service (the app in view) it goes, and waits no more
        assertEquals(ListenableWorker.Result.success(), run(id, deps = small))
        assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).let { it.done && !it.waiting })
        // a recording within the background limit goes without the foreground service
        val b = UploadTestData.reviewed(store, labeler, "golden")
        assertEquals(ListenableWorker.Result.success(), run(b, foregroundAllowed = false))
        assertTrue(LocalUpload.of(store.dir(b), prefs.flow.value.repo).done)
    }

    // ---- Delete take (UploadQueue.delete) ----

    private fun queue(scope: CoroutineScope) = UploadQueue(store, scheduler, prefs, tokens, scope, Dispatchers.IO)

    private fun inRepo(id: String) = gh.files().filterKeys { it.startsWith(UploadRecord.repoPath(id) + "/") }

    /** A take that was never uploaded: its folder goes for good, its uploads are dropped, and a worker that runs anyway sends nothing. */
    @Test
    fun deletingATakeNotUploadedRemovesItForGood() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        assertTrue(queue(this).delete(id))
        assertEquals(listOf(id), scheduler.cancelled.toList())
        assertFalse(store.dir(id).exists())
        assertTrue(store.list().none { it.journal.recordingId == id })
        // an upload of it enqueued again meanwhile (a rate limit's, Upload now's): it finds no recording
        assertTrue(run(id).ended)
        assertTrue(gh.requests.toString(), gh.requests.isEmpty())
        assertFalse("no folder made again", store.dir(id).exists())
        assertTrue(inRepo(id).isEmpty())
    }

    /**
     * An uploaded take, saved again since (the label update not sent yet): LocalUpload says the
     * data repo holds an older copy (RepoCopy.OLDER); Delete take removes the phone's copy only, the data repo keeps the
     * upload it has, and the label update is never sent.
     */
    @Test
    fun deletingAnUploadedTakeKeepsTheDataReposCopy() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        assertEquals(ListenableWorker.Result.success(), run(id))
        val repo = prefs.flow.value.repo
        assertEquals(RepoCopy.CURRENT, LocalUpload.of(store.dir(id), repo).repoCopy)
        UploadTestData.save(store, labeler, id)
        assertEquals(RepoCopy.OLDER, LocalUpload.of(store.dir(id), repo).repoCopy)
        assertFalse(LocalUpload.of(store.dir(id), repo).done)
        // another repository holds nothing of it; it is in the one set before (Delete take says so)
        assertEquals(RepoCopy.ELSEWHERE, LocalUpload.of(store.dir(id), "someone/else").repoCopy)
        val kept = inRepo(id)
        assertTrue(kept.isNotEmpty())
        queue(this).enqueue(id)                                  // the label update, waiting
        assertTrue(queue(this).delete(id))
        assertEquals(listOf(id), scheduler.cancelled.toList())
        assertFalse(store.dir(id).exists())
        assertTrue(run(id).ended)                               // it ran anyway: nothing sent
        assertEquals(kept, inRepo(id))
    }

    /** Queued (waiting for its turn or the network): the work is cancelled, nothing of it is uploaded afterwards. */
    @Test
    fun deletingAQueuedUploadCancelsIt() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val q = queue(this)
        q.enqueue(id)
        scheduler.activeFlow.value = mapOf(id to ActiveUpload.Queued)
        assertEquals(id, scheduler.enqueued.single().id)
        assertTrue(q.delete(id))
        assertEquals(listOf(id), scheduler.cancelled.toList())
        assertFalse(store.dir(id).exists())
        assertTrue(run(id).ended)
        assertTrue(gh.requests.isEmpty())
        assertTrue(inRepo(id).isEmpty())
        assertFalse(store.dir(id).exists())
    }

    /**
     * The worker for [id] whose first blob waits for [release] once [held] (its files open), on
     * [log]; [stops]: a stop (the delete's) cuts that wait, as it cuts a request in flight.
     */
    private fun heldDeps(log: MutableList<String>, held: CountDownLatch, release: CountDownLatch, stops: Boolean = true) =
        UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t ->
            val hold = { if (held.count > 0) { held.countDown(); release.await(30, TimeUnit.SECONDS) } }
            Calls(HttpGitHubApi(o, n, t, gh.baseUrl), "A", log, hold, onAbort = { if (stops) release.countDown() })
        })

    /**
     * Delete take while that take uploads (its first blob in flight, its files open): the upload
     * is stopped (its request cut, none after it), the delete waits for it to end and then
     * removes the folder: nothing is committed, no upload.json made again; its work is dropped,
     * and the run ends as done (the take is gone), never as a network failure.
     */
    @Test
    fun deletingDuringItsUploadStopsItFirst() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val upload = scope.async { worker(id, deps = heldDeps(log, held, release)).doWork() }
            assertTrue(held.await(30, TimeUnit.SECONDS))
            assertTrue(withTimeout(30_000) { withContext(Dispatchers.IO) { queue(scope).delete(id) } })
            assertTrue(log.toString(), "A abort" in log)
            assertEquals("stopped, the take gone: nothing to retry", ListenableWorker.Result.success(), withTimeout(30_000) { upload.await() })
            assertEquals(listOf(id), scheduler.cancelled.toList())
            assertFalse(store.dir(id).exists())
            assertTrue(log.toString(), log.none { it == "A patch" || it == "A tree" })
            assertTrue(inRepo(id).isEmpty())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    /**
     * An upload that does not stop at once (GitHub took its commit, the answer is on its way):
     * the delete waits for it, and never removes the files it reads; it does not wait for
     * another take's upload. The commit stays in the data repo.
     */
    @Test
    fun aDeleteWaitsForThatTakesUploadOnly() = runBlocking {
        val a = UploadTestData.reviewed(store, labeler, "rich")
        val b = UploadTestData.reviewed(store, labeler, "golden")
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deps = UploadDeps(store, prefs, tokens, scheduler, api = { o, n, t ->
            Calls(HttpGitHubApi(o, n, t, gh.baseUrl), "A", log, null, holdPatch = { held.countDown(); release.await(30, TimeUnit.SECONDS) })
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val upload = scope.async { worker(a, deps = deps).doWork() }
            assertTrue(held.await(30, TimeUnit.SECONDS))
            assertTrue("B at once, while A uploads", withTimeout(10_000) { withContext(Dispatchers.IO) { queue(scope).delete(b) } })
            assertFalse(store.dir(b).exists())
            val deleting = scope.async { queue(scope).delete(a) }
            Thread.sleep(300)
            assertFalse("it waits for A's upload", deleting.isCompleted)
            assertTrue(log.toString(), "A abort" in log)
            assertTrue(File(store.dir(a), Finalizer.META).isFile)
            release.countDown()
            assertEquals(ListenableWorker.Result.success(), withTimeout(30_000) { upload.await() })
            assertTrue(withTimeout(30_000) { deleting.await() })
            assertFalse(store.dir(a).exists())
            assertTrue("A's commit is in the data repo", inRepo(a).isNotEmpty())
            assertEquals(listOf(b, a), scheduler.cancelled.toList())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    /**
     * A delete that fails (the folder cannot be moved) while the take uploads: the upload it
     * stopped goes on in the same run once the delete ended (no attempt used, no backoff: on
     * WorkManager's last attempt it is not written as a network failure), never dropped nor
     * enqueued again; the take stays.
     */
    @Test
    fun aDeleteThatFailsLeavesTheUploadAsItWas() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        File(store.root, RecordingStore.TRASH).writeText("not a folder")          // the move fails
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val last = worker(id, attempt = UploadWorker.MAX_ATTEMPTS - 1, deps = heldDeps(log, held, release))
            val upload = scope.async { last.doWork() }
            assertTrue(held.await(30, TimeUnit.SECONDS))
            val failed = runCatching { withTimeout(30_000) { withContext(Dispatchers.IO) { queue(scope).delete(id) } } }.exceptionOrNull()
            assertTrue(failed.toString(), failed is IOException)
            assertEquals("it goes on and uploads it", ListenableWorker.Result.success(), withTimeout(30_000) { upload.await() })
            assertTrue(log.toString(), "A abort" in log && "A patch" in log)
            assertTrue("nothing dropped", scheduler.cancelled.isEmpty())
            assertTrue("nothing enqueued again", scheduler.enqueued.isEmpty())
            assertTrue(File(store.dir(id), Finalizer.META).isFile)
            assertNull("no failure written", error(id))
            assertTrue(LocalUpload.of(store.dir(id), prefs.flow.value.repo).done)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    /**
     * Back while the delete waits for the take's upload to stop (the review is cleared): the
     * delete is not cut short, it ends, so its outcome is always seen: the take gone, its work
     * dropped, nothing enqueued again.
     */
    @Test
    fun aDeleteWhoseCallerIsCancelledStillEnds() = runBlocking {
        val id = UploadTestData.reviewed(store, labeler)
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val upload = scope.async { worker(id, deps = heldDeps(log, held, release, stops = false)).doWork() }
            assertTrue(held.await(30, TimeUnit.SECONDS))
            var result: Boolean? = null
            val caller = scope.launch { withContext(Dispatchers.IO) { result = queue(scope).delete(id) } }
            Thread.sleep(300)
            assertTrue("it waits for the upload", caller.isActive && store.dir(id).isDirectory)
            caller.cancel()
            release.countDown()
            withTimeout(30_000) { caller.join() }
            withTimeout(30_000) { upload.await() }
            assertEquals(true, result)
            assertFalse(store.dir(id).exists())
            assertEquals(listOf(id), scheduler.cancelled.toList())
            assertTrue(scheduler.enqueued.isEmpty())
            assertTrue(inRepo(id).isEmpty())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
