package dev.drillbalancer.ui.training.upload

import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.training.MemoryPrefs
import dev.drillbalancer.ui.training.TrainingDeps
import dev.drillbalancer.ui.training.TrainingListViewModel
import dev.drillbalancer.ui.training.UploadSummary
import dev.drillbalancer.ui.training.label
import dev.drillbalancer.ui.training.review.ReviewViewModel
import dev.drillbalancer.upload.ActiveUpload
import dev.drillbalancer.upload.BlobContent
import dev.drillbalancer.upload.FailureKind
import dev.drillbalancer.upload.FakeGitHub
import dev.drillbalancer.upload.FakeScheduler
import dev.drillbalancer.upload.FakeTokenStore
import dev.drillbalancer.upload.FakeUploadPrefs
import dev.drillbalancer.upload.GitHash
import dev.drillbalancer.upload.GitHubApi
import dev.drillbalancer.upload.GitHubException
import dev.drillbalancer.upload.RepoInfo
import dev.drillbalancer.upload.TreeEntry
import dev.drillbalancer.upload.UploadCounts
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadRecord
import dev.drillbalancer.upload.UploadServices
import dev.drillbalancer.upload.UploadStage
import dev.drillbalancer.upload.UploadTestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

/** A GitHubApi for Test connection: the repository [info] (or [fail]), the template there or not. */
private class StubApi(val info: RepoInfo? = RepoInfo("Tropaion/Drill_Balancer_Data", true, true, 2048, "main"), val template: Boolean = true, val fail: Exception? = null) : GitHubApi {
    override fun repo(): RepoInfo = fail?.let { throw it } ?: info!!
    override fun headCommit(branch: String) = error("not here")
    override fun commitTree(commit: String) = error("not here")
    override fun listFolder(path: String, ref: String) = error("not here")
    override fun fileExists(path: String, ref: String?) = template.also { check(path == "schema/meta.v1.schema.json") }
    override fun createBlob(content: BlobContent, sent: (Long) -> Unit) = error("not here")
    override fun createTree(baseTree: String, entries: List<TreeEntry>) = error("not here")
    override fun createCommit(message: String, tree: String, parents: List<String>) = error("not here")
    override fun updateRef(branch: String, commit: String) = error("not here")
}

/** The list's upload stages, Save into the queue, and the upload settings screen's ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UploadViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val labeler = UploadTestData.labeler()
    private val store by lazy { UploadTestData.store(tmp.root, labeler) }
    private val prefs = FakeUploadPrefs()
    private val tokens = FakeTokenStore(FakeGitHub.TOKEN)
    private val scheduler = FakeScheduler()
    private var api: GitHubApi = StubApi()

    private fun TestScope.services() = UploadServices(UploadQueue(store, scheduler, prefs, tokens, backgroundScope, main.dispatcher), prefs, tokens) { _, _, _ -> api }

    private fun TestScope.controller() = RecordingController(
        dev.drillbalancer.device.FakeDeviceSession(), store, StreamLinkFactory { throw LinkException("no network", LinkException.Kind.CONNECT) },
        { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), backgroundScope, io = main.dispatcher,
    )

    private fun done(id: String) {
        val meta = File(store.dir(id), Finalizer.META)
        UploadRecord.write(store.dir(id), UploadRecord(repo = prefs.flow.value.repo, branch = "main", path = UploadRecord.repoPath(id), commit = "c1", committed = mapOf(Finalizer.META to GitHash.blobSha(meta))))
    }

    private fun failed(id: String, why: String, kind: FailureKind? = FailureKind.NOT_FOUND) =
        UploadRecord.write(store.dir(id), UploadRecord(repo = prefs.flow.value.repo, branch = "main", path = UploadRecord.repoPath(id), error = why, errorKind = kind?.key))

    @Test
    fun theListShowsEachRecordingsUpload() = runTest(main.dispatcher) {
        val a = UploadTestData.reviewed(store, labeler, "rich")           // uploaded
        val b = UploadTestData.reviewed(store, labeler, "golden")         // failed
        val c = FinalizerFixtures.install(store, "rich_cut").recordingId  // not reviewed
        store.recover()
        done(a)
        failed(b, "Repo not found or no access: check it.")
        val s = services()
        val vm = TrainingListViewModel(store, controller(), null, io = main.dispatcher, uploads = s)
        advanceUntilIdle()
        fun stages() = vm.state.value.rows.associate { it.id to it.upload }
        assertEquals(mapOf(a to UploadStage.Done, b to UploadStage.Failed("Repo not found or no access: check it.", FailureKind.NOT_FOUND), c to UploadStage.None), stages())
        assertEquals(listOf("Uploaded", "Upload failed", "Not uploaded"), listOf(a, b, c).map { stages().getValue(it).label })
        assertEquals(UploadSummary("Tropaion/Drill_Balancer_Data", tokenSet = true, tokenRejected = false, wifiOnly = true), vm.state.value.upload)
        assertEquals("Tropaion/Drill_Balancer_Data · Wi-Fi only", vm.state.value.upload!!.text)

        // the queue's own states come first
        scheduler.activeFlow.value = mapOf(b to ActiveUpload.Running(42), c to ActiveUpload.Queued)
        assertEquals(UploadStage.Uploading(42), stages()[b])
        assertEquals("Uploading 42 %", stages().getValue(b).label)
        assertEquals(UploadStage.Queued, stages()[c])
        scheduler.activeFlow.value = emptyMap()

        // Retry sends it again, the conflict check kept: not over a copy the data repo changed
        vm.retryUpload(b)
        advanceUntilIdle()
        assertEquals(listOf(FakeScheduler.Enqueued(b, true, 0)), scheduler.enqueued)
        // after a conflict (the row says Retry overwrites the data repo's labels) it does
        failed(b, "Not uploaded: the data repo's copy was changed since this phone uploaded it.", FailureKind.CONFLICT)
        vm.reload()
        advanceUntilIdle()
        scheduler.enqueued.clear()
        vm.retryUpload(b)
        advanceUntilIdle()
        assertEquals(listOf(FakeScheduler.Enqueued(b, true, 0, overwrite = true)), scheduler.enqueued)
        // an upload.json from before error kinds: a plain Retry
        failed(b, "Not uploaded: the data repo's copy was changed since this phone uploaded it.", null)
        vm.reload()
        advanceUntilIdle()
        scheduler.enqueued.clear()
        vm.retryUpload(b)
        advanceUntilIdle()
        assertEquals(listOf(FakeScheduler.Enqueued(b, true, 0)), scheduler.enqueued)

        // a rejected token: every reviewed one not uploaded says so
        tokens.reject(tokens.id)
        assertEquals(UploadStage.Failed(UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED), stages()[b])
        assertEquals(UploadStage.Done, stages()[a])
        assertEquals(UploadStage.None, stages()[c])
        assertEquals("Tropaion/Drill_Balancer_Data · token rejected", vm.state.value.upload!!.text)
        tokens.remove()
        assertEquals("Tropaion/Drill_Balancer_Data · no token set", vm.state.value.upload!!.text)

        // a new Save of the uploaded one: not done any more (the list reloads on store.changes)
        UploadTestData.save(store, labeler, a)
        advanceUntilIdle()
        assertEquals(UploadStage.None, stages()[a])
    }

    @Test
    fun aListWithoutUploadsHasNoUploadLine() = runTest(main.dispatcher) {
        val vm = TrainingListViewModel(store, controller(), null, io = main.dispatcher)
        advanceUntilIdle()
        assertNull(vm.state.value.upload)
    }

    @Test
    fun aSavedReviewIsEnqueued() = runTest(main.dispatcher) {
        val j = FinalizerFixtures.install(store, "rich")
        store.finalize(j.recordingId)
        val deps = TrainingDeps(controller(), store, MemoryPrefs(), labeler, services())
        val vm = ReviewViewModel(j.recordingId, deps.review, store, io = main.dispatcher)
        advanceUntilIdle()
        assertTrue(scheduler.enqueued.isEmpty())
        vm.acceptAll()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals(listOf(FakeScheduler.Enqueued(j.recordingId, true, 0)), scheduler.enqueued)
    }

    private fun TestScope.settings(): UploadSettingsViewModel = UploadSettingsViewModel(services(), store, main.dispatcher).also { advanceUntilIdle() }

    @Test
    fun aTokenIsSetReplacedAndRemoved() = runTest(main.dispatcher) {
        tokens.remove()
        val a = UploadTestData.reviewed(store, labeler, "rich")
        val vm = settings()
        assertFalse(vm.state.value.token.set)
        vm.openTokenDialog()
        assertTrue(vm.state.value.tokenDialog)
        vm.setToken("nope")
        assertTrue(vm.state.value.tokenError!!.startsWith("That is not a GitHub token"))
        assertTrue(tokens.sets.isEmpty())
        vm.setToken(FakeGitHub.TOKEN)
        advanceUntilIdle()
        assertEquals(listOf(FakeGitHub.TOKEN), tokens.sets)
        assertTrue(vm.state.value.token.set)
        assertFalse(vm.state.value.tokenDialog)
        // the state never holds the token
        assertFalse(FakeGitHub.TOKEN in vm.state.value.toString())
        // a new token sends the reviewed recordings not uploaded yet
        assertEquals(listOf(FakeScheduler.Enqueued(a, true, 0)), scheduler.enqueued)
        assertEquals("1 recording is queued for upload.", vm.state.value.message!!.text)
        vm.removeToken()
        advanceUntilIdle()
        assertFalse(vm.state.value.token.set)
        assertNull(tokens.value)
    }

    @Test
    fun theRepositoryAndTheOptions() = runTest(main.dispatcher) {
        val vm = settings()
        assertEquals("Tropaion/Drill_Balancer_Data", vm.state.value.repoText)
        assertFalse(vm.state.value.repoChanged)
        vm.editRepo("not a repo")
        assertTrue(vm.state.value.repoChanged)
        vm.saveRepo()
        assertEquals("Write it as owner/name, for example Tropaion/Drill_Balancer_Data.", vm.state.value.repoError)
        assertEquals("Tropaion/Drill_Balancer_Data", prefs.flow.value.repo)
        vm.editRepo("me/data_2027")
        vm.saveRepo()
        advanceUntilIdle()
        assertEquals("me/data_2027", prefs.flow.value.repo)
        assertFalse(vm.state.value.repoChanged)

        vm.setKeepLocal(true)
        vm.setMuteAudio(true)
        advanceUntilIdle()
        assertTrue(prefs.flow.value.keepLocal && prefs.flow.value.muteAudio)
        // Wi-Fi only switched off: every one under the new rule, the running one (and the one
        // appended after it) replaced too, so none waits for Wi-Fi
        scheduler.activeFlow.value = mapOf("20261026T143012Z_k7q2_9f3a" to ActiveUpload.Queued, "20261027T091500Z_m3xa_0b7z" to ActiveUpload.Running(5))
        vm.setWifiOnly(false)
        advanceUntilIdle()
        assertFalse(prefs.flow.value.wifiOnly)
        assertEquals(
            listOf(
                FakeScheduler.Enqueued("20261026T143012Z_k7q2_9f3a", false, 0, restart = true),
                FakeScheduler.Enqueued("20261027T091500Z_m3xa_0b7z", false, 0, restart = true),
            ),
            scheduler.enqueued,
        )
        // switched on: every one, the running one stopped and replaced too (none goes on over mobile data)
        scheduler.enqueued.clear()
        vm.setWifiOnly(true)
        advanceUntilIdle()
        assertEquals(
            listOf(
                FakeScheduler.Enqueued("20261026T143012Z_k7q2_9f3a", true, 0, restart = true),
                FakeScheduler.Enqueued("20261027T091500Z_m3xa_0b7z", true, 0, restart = true),
            ),
            scheduler.enqueued,
        )
    }

    @Test
    fun testConnection() = runTest(main.dispatcher) {
        val vm = settings()
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(
            ConnectionTest.Done(listOf("Found Tropaion/Drill_Balancer_Data.", "It is private.", "You may write to it.", "The dataset template is there."), emptyList()),
            vm.state.value.test,
        )
        api = StubApi(RepoInfo("Tropaion/Drill_Balancer_Data", isPrivate = false, canPush = false, sizeKb = 3L shl 20, defaultBranch = "main"), template = false)
        vm.testConnection()
        advanceUntilIdle()
        val w = (vm.state.value.test as ConnectionTest.Done).warnings
        assertEquals(4, w.size)
        assertTrue(w[0].startsWith("It is public"))
        assertTrue(w[1].startsWith("No write access"))
        assertTrue(w[2].startsWith("No dataset template"))
        assertTrue(w[3].startsWith("It holds 3072 MB"))

        api = StubApi(fail = GitHubException.NotFound("Not Found"))
        vm.testConnection()
        advanceUntilIdle()
        assertTrue((vm.state.value.test as ConnectionTest.Failed).message.startsWith("Repo not found or no access"))
        api = StubApi(fail = IOException("no route"))
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTest.Failed("No connection to GitHub. Check the network, then test again."), vm.state.value.test)
        api = StubApi(fail = GitHubException.Unauthorized("Bad credentials"))
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTest.Failed(UploadSettingsViewModel.TEST_TOKEN_REJECTED, tokenRejected = true), vm.state.value.test)
        assertTrue("a rejected token is marked", vm.state.value.token.rejected)
        tokens.remove()
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTest.Failed("Set a token first."), vm.state.value.test)
    }

    @Test
    fun theQueueCountsAndUploadNow() = runTest(main.dispatcher) {
        val a = UploadTestData.reviewed(store, labeler, "rich")
        val b = UploadTestData.reviewed(store, labeler, "golden")
        FinalizerFixtures.install(store, "rich_cut")
        store.recover()
        done(a)
        val vm = settings()
        assertEquals(UploadCounts(done = 1, notQueued = 1, needsReview = 1), vm.state.value.counts)
        assertTrue(vm.state.value.canUploadNow)
        vm.uploadNow()
        advanceUntilIdle()
        assertEquals(listOf(FakeScheduler.Enqueued(b, true, 0)), scheduler.enqueued)
        assertEquals("1 recording is queued for upload.", vm.state.value.message!!.text)
        scheduler.activeFlow.value = mapOf(b to ActiveUpload.Queued)
        advanceUntilIdle()
        assertEquals(UploadCounts(queued = 1, done = 1, needsReview = 1), vm.state.value.counts)
        failed(b, "x")
        scheduler.activeFlow.value = emptyMap()
        advanceUntilIdle()
        assertEquals(UploadCounts(failed = 1, done = 1, needsReview = 1), vm.state.value.counts)
        done(b)
        vm.uploadNow()
        advanceUntilIdle()
        assertEquals("Nothing to upload: every reviewed recording is uploaded.", vm.state.value.message!!.text)
        assertEquals(UploadCounts(done = 2, needsReview = 1), vm.state.value.counts)
        assertFalse("nothing waits to be sent", vm.state.value.canUploadNow)
        assertEquals(RecordingStore.AUTO_ONLY, store.list().first { it.journal.recordingId.endsWith("1c8y") }.reviewStatus)
    }

    /** Before the queue is counted (or when counting fails) the counts are unknown: Upload now stays on. */
    @Test
    fun uploadNowIsOnUntilTheCountsSayNothingWaits() = runTest(main.dispatcher) {
        UploadTestData.reviewed(store, labeler, "rich")
        // the count's storage reads never finish: the settings are there, the counts are not
        val stuck = StandardTestDispatcher(TestCoroutineScheduler())
        val vm = UploadSettingsViewModel(services(), store, stuck)
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.counts)
        assertFalse(vm.state.value.nothingToSend)
        assertTrue(vm.state.value.canUploadNow)
    }

    /** Test connection's words: a 403 in plain words (not GitHub's status line), a 5xx, a keystore that failed. */
    @Test
    fun testConnectionSaysWhatToDo() = runTest(main.dispatcher) {
        val vm = settings()
        api = StubApi(fail = GitHubException.Forbidden("Resource not accessible by personal access token"))
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(
            ConnectionTest.Failed("GitHub refused: the token may not read or write this repository. Give it Contents read and write."),
            vm.state.value.test,
        )
        api = StubApi(fail = GitHubException.Server(502, "Bad gateway"))
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTest.Failed("GitHub had a problem (HTTP 502). Test again later."), vm.state.value.test)
        api = StubApi(fail = IOException("GitHub's answer is not JSON"))
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTest.Failed("No connection to GitHub. Check the network, then test again."), vm.state.value.test)
        tokens.unreadable = true
        vm.testConnection()
        advanceUntilIdle()
        assertTrue((vm.state.value.test as ConnectionTest.Failed).message.startsWith("The phone could not read the token"))
    }

    /**
     * A token marked rejected that GitHub answers again (the 401 has gone away): the mark is
     * cleared and the recordings not uploaded go. Upload now is off while a token is rejected.
     */
    @Test
    fun aRejectedTokenThatWorksAgainIsCleared() = runTest(main.dispatcher) {
        val a = UploadTestData.reviewed(store, labeler, "rich")
        tokens.reject(tokens.id)
        val vm = settings()
        assertTrue(vm.state.value.token.rejected)
        assertFalse(vm.state.value.canUploadNow)
        vm.uploadNow()
        advanceUntilIdle()
        assertEquals("GitHub rejected the token: replace it first.", vm.state.value.message!!.text)
        assertTrue(scheduler.enqueued.isEmpty())
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.state.value.test is ConnectionTest.Done)
        assertFalse(vm.state.value.token.rejected)
        assertTrue(vm.state.value.canUploadNow)
        assertEquals(listOf(FakeScheduler.Enqueued(a, true, 0)), scheduler.enqueued)
        assertEquals("GitHub accepts the token again. 1 recording is queued for upload.", vm.state.value.message!!.text)
    }

    /** Test connection asks about the saved repository: not while the field holds an unsaved one. */
    @Test
    fun testConnectionWaitsForTheRepositoryToBeSaved() = runTest(main.dispatcher) {
        val vm = settings()
        assertTrue(vm.state.value.canTest)
        vm.editRepo("me/other")
        assertFalse(vm.state.value.canTest)
        vm.saveRepo()
        advanceUntilIdle()
        assertTrue(vm.state.value.canTest)
        assertFalse(UploadSettingsUiState().canTest)            // not loaded yet
    }
}
