package dev.drillbalancer.upload

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.work.testing.WorkManagerTestInitHelper
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.ui.training.upload.UploadSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator

/**
 * The token in nothing the app writes: a recognisable fake token, kept in a real TokenVault
 * (DataStore file under the app's files dir, AES-GCM with a software key), then uploads through
 * WorkManager and the worker that meet every kind of GitHub error, each echoing the token in
 * its message (and a 2xx page that is not JSON holding it), and Test connection on the same
 * answers. Afterwards nothing holds the token (nor its base64): no file under the app's data
 * and cache dirs or the recordings, no log line or logged exception (Robolectric's ShadowLog),
 * nothing on stdout/stderr, no WorkManager output or progress data, no upload.json error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UploadTokenLeakTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    // per scenario: a repository of its own and a store of its own (the fixtures share their ids)
    private var gh = FakeGitHub(token = CANARY)
    private val labeler = UploadTestData.labeler()
    private lateinit var store: RecordingStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "datastore/upload.preferences_pb") } }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault by lazy { TokenVault(dataStore, GcmCipher { key }) }
    private val prefs by lazy { UploadSettingsStore(dataStore) }
    private lateinit var scheduler: WorkUploadScheduler
    private val deps get() = UploadDeps(store, prefs, vault, scheduler, api = { o, n, t -> HttpGitHubApi(o, n, t, gh.baseUrl) })
    private val wm get() = WorkManager.getInstance(context)
    private val out = ByteArrayOutputStream()
    private val stdout = System.out
    private val stderr = System.err

    @Before
    fun init() {
        ShadowLog.clear()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                UploadWorker(appContext, workerParameters, deps)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).setMinimumLoggingLevel(Log.VERBOSE).build(),
        )
        scheduler = WorkUploadScheduler(context)
        val tee = PrintStream(out, true)
        System.setOut(PrintStream(TeeStream(stdout, tee), true))
        System.setErr(PrintStream(TeeStream(stderr, tee), true))
    }

    @After
    fun close() {
        System.setOut(stdout)
        System.setErr(stderr)
        gh.close()
        scope.cancel()
    }

    private class TeeStream(val a: java.io.OutputStream, val b: java.io.OutputStream) : java.io.OutputStream() {
        override fun write(v: Int) {
            a.write(v)
            b.write(v)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            a.write(buf, off, len)
            b.write(buf, off, len)
        }
    }

    private fun infos(id: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(WorkUploadScheduler.name(id)).get()

    // Runs [id]'s upload through WorkManager (retries and uploads appended after it too, a few
    // rounds), then cancels what is left; returns its work as it ended.
    private fun upload(id: String): List<WorkInfo> = runBlocking {
        scheduler.enqueue(id, wifiOnly = false)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        repeat(6) {
            val live = infos(id).filter { !it.state.isFinished }
            if (live.isEmpty()) return@repeat
            val before = live.associate { it.id to it.runAttemptCount }
            for (w in live) {
                driver.setAllConstraintsMet(w.id)
                runCatching { driver.setInitialDelayMet(w.id) }
            }
            val end = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < end) {
                val settled = infos(id).all { w ->
                    w.state.isFinished || w.state == WorkInfo.State.BLOCKED ||
                        (w.state == WorkInfo.State.ENQUEUED && (w.id !in before || w.runAttemptCount > before.getValue(w.id)))
                }
                if (settled) break
                Thread.sleep(20)
            }
        }
        val all = infos(id)
        wm.cancelAllWork().result.get()
        all
    }

    private fun answer(code: Int, vararg headers: Pair<String, String>): (mockwebserver3.RecordedRequest) -> MockResponse? = { r ->
        if (r.url.encodedPath.endsWith("/git/commits") || r.url.encodedPath.endsWith("/git/ref/heads/main") && code in setOf(401, 404, 409)) {
            FakeGitHub.error(code, "refused for Bearer $CANARY (token $CANARY)", *headers)
        } else {
            null
        }
    }

    @Test
    fun theTokenIsInNothingWritten() {
        runBlocking { vault.set(CANARY) }
        val scenarios: List<Pair<String, ((mockwebserver3.RecordedRequest) -> MockResponse?)?>> = listOf(
            "ok" to null,
            "401" to answer(401),
            "403" to answer(403),
            "403 rate" to answer(403, "Retry-After" to "120"),
            "404" to answer(404),
            "409" to answer(409),
            "422" to answer(422),
            "429" to answer(429, "Retry-After" to "120"),
            "500" to answer(500),
            "not json" to { r ->
                if (r.url.encodedPath.endsWith("/git/trees")) MockResponse.Builder().code(200).body("<html>login $CANARY</html>").build() else null
            },
            "dropped" to { r ->
                if (r.url.encodedPath.endsWith("/git/blobs")) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else null
            },
        )
        val outputs = ArrayList<String>()
        for ((name, intercept) in scenarios) {
            gh.close()
            gh = FakeGitHub(token = CANARY)
            store = UploadTestData.store(File(tmp.root, name.replace(' ', '_')), labeler)
            runBlocking { vault.set(CANARY) }             // a 401 marked it rejected: a new set clears that
            val id = UploadTestData.reviewed(store, labeler)
            gh.intercept = intercept
            for (w in upload(id)) {
                outputs += "$name: ${w.state} out=${w.outputData.keyValueMap} progress=${w.progress.keyValueMap} tags=${w.tags}"
            }
            // its last try (a retry's end: the message upload.json keeps)
            runBlocking { vault.set(CANARY) }
            val last = runBlocking {
                TestListenableWorkerBuilder<UploadWorker>(context)
                    .setInputData(workDataOf(UploadWorker.KEY_ID to id))
                    .setRunAttemptCount(UploadWorker.MAX_ATTEMPTS - 1)
                    .setWorkerFactory(object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                            UploadWorker(appContext, workerParameters, deps)
                    })
                    .build().doWork()
            }
            outputs += "$name: last=$last"
            UploadRecord.read(store.dir(id))?.let { outputs += "$name: upload.json error=${it.error}" }
            // Test connection on the same answers
            val r = UploadSettingsViewModel.test(HttpGitHubApi("Tropaion", "Drill_Balancer_Data", CANARY, gh.baseUrl))
            outputs += "$name: test=$r"
            gh.intercept = { req -> if (req.url.encodedPath.endsWith("/Drill_Balancer_Data")) FakeGitHub.error(401, "Bad $CANARY") else null }
            outputs += "$name: test401=${UploadSettingsViewModel.test(HttpGitHubApi("Tropaion", "Drill_Balancer_Data", CANARY, gh.baseUrl))}"
            gh.intercept = null
        }
        stdout.println(outputs.joinToString("\n"))
        // the uploads really met the errors (not all a success), and the good one went
        assertTrue(outputs.joinToString("\n"), outputs.any { it.startsWith("ok: SUCCEEDED out={}") })
        assertTrue(outputs.joinToString("\n"), outputs.count { "upload.json error=" in it && !it.endsWith("error=null") } == 8)
        assertTrue(outputs.joinToString("\n"), outputs.count { it.contains("test401=Failed(message=GitHub rejected the token") } == scenarios.size)

        val needles = listOf(CANARY, Base64.getEncoder().encodeToString(CANARY.toByteArray()), CANARY.takeLast(16))
        val roots = listOfNotNull(tmp.root, context.filesDir.parentFile, context.cacheDir, context.filesDir, context.noBackupFilesDir).distinct()
        val files = roots.flatMap { r -> needles.flatMap { UploadTestData.filesHolding(r, it) } }.distinct()
        assertEquals("files holding the token", emptyList<File>(), files)
        assertTrue("the vault file was scanned", File(context.filesDir, "datastore/upload.preferences_pb").isFile)
        assertTrue(roots.sumOf { r -> r.walkTopDown().count { it.isFile } } > 10)

        val logs = ShadowLog.getLogs().map { "${it.tag}: ${it.msg} ${it.throwable?.let { t -> Log.getStackTraceString(t) + t }.orEmpty()}" }
        assertTrue("logs captured", logs.isNotEmpty())
        for (n in needles) {
            assertEquals("log lines holding the token", emptyList<String>(), logs.filter { n in it })
            assertEquals("WorkManager data holding the token", emptyList<String>(), outputs.filter { n in it })
            assertTrue("stdout/stderr hold the token", n !in out.toString(Charsets.UTF_8))
        }
    }

    private companion object {
        val CANARY = "leakcanary_" + UUID.randomUUID().toString().replace("-", "") + "_Zq"
    }
}
