package dev.drillbalancer.recorder

import dev.drillbalancer.BuildConfig
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.protocol.TakeRequest
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.recorder.net.LanLink
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamRecord
import dev.drillbalancer.recorder.stream.StreamStats
import dev.drillbalancer.recorder.stream.StreamStatsTracker
import dev.drillbalancer.upload.UploadTestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.time.Instant

/**
 * Opt-in end-to-end check against a running simulator (firmware/tools/sim, README "Against
 * the device simulator"): skipped unless DB_SIM_URL names its web panel, e.g.
 *
 *     ./run.sh --no-ble --rec-host 127.0.0.1           # in firmware/tools/sim
 *     DB_SIM_URL=http://127.0.0.1:8765 ./gradlew testDebugUnitTest --tests '*SimulatorEndToEndTest' --rerun
 *
 * (--rerun: Gradle sees neither the simulator nor a plan file's content, below.)
 * Without Bluetooth the panel's API stands in for rec_ctrl and rec_state ([SimApiSession]);
 * the stream is the simulator's real TCP server, joined with LanLink. One take of a drilling
 * scenario is recorded through RecordingController and its journal must decode without
 * loss. DB_SIM_OUT (a folder) keeps the journal for dbdata.dbrec.
 *
 * With DB_SIM_SCENARIOS too (a JSON file of [SimTake]s), [recordsTheScenarioTakes] records
 * one session per entry instead, with its own [SessionSetup] (the app's type, sanitized as the
 * setup screen does) and sim commands at given take times, ended by STOP, the device's own END
 * (max_s) or a dropped client; it is skipped without that file. Optional outputs:
 * - DB_SIM_STORE_OUT: each take's store folder <id>/{stream.dbr, take.json}, as the phone holds
 *   it before finalizing;
 * - DB_SIM_TRUTH_OUT: the simulator's ground truth of each take, <id>.json (/api/rec/truth,
 *   the content of its state/rec_truth_<take>.json);
 * - DB_SIM_FINALIZE_OUT: a copy of each take finalized and auto-labelled as the app does
 *   (RecordingStore with AutoLabeler), laid out as in the data repo
 *   (recordings/YYYY/YYYY-MM/<id>/) for `python -m dbdata.validate`.
 *
 *     DB_SIM_URL=http://127.0.0.1:8765 DB_SIM_SCENARIOS=takes.json DB_SIM_STORE_OUT=/tmp/store \
 *       ./gradlew testDebugUnitTest --tests '*SimulatorEndToEndTest.recordsTheScenarioTakes' --rerun
 *
 * Its takes are made under the install id [SIM_INSTALL] (recording ids ..._simt_xxxx), and each
 * session starts in a minute of its own, so no two get the same session id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SimulatorEndToEndTest {
    @Test
    fun recordsATakeFromTheSimulator() {
        val url = env("DB_SIM_URL")
        assumeTrue("DB_SIM_URL not set: the simulator check is opt-in", url != null)
        val sim = SimApiSession(url!!.trimEnd('/'))
        val root = Files.createTempDirectory("dbsim-e2e").toFile()
        val store = RecordingStore(File(root, "recordings"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            sim.awake()      // a simulator idle for 3 min sleeps: no recorder until it wakes
            val c = RecordingController(sim, store, StreamLinkFactory { LanLink(it) }, { "k7q2e2etestabcde" }, AppInfo("e2e", "jvm", 0), scope)
            c.open(SessionSetup(material = "wood/softwood", thicknessMm = 38.0, bitType = "brad_point", bitDiameterMm = 8.0), Secure.DIRECT)
            c.await(20_000) { it.phase == RecordPhase.READY }
            sim.cmd("""{"cmd":"scenario","mode":"drill","holes":1,"outcome":"breakthrough","seed":7}""")
            c.startTake(Secure.DIRECT)
            c.await(10_000) { it.phase == RecordPhase.RECORDING && it.live.elapsedS >= TAKE_S }
            val live = c.state.value.live
            c.stopTake()
            val ended = c.await(10_000) { it.phase == RecordPhase.READY && it.takes == 1 }
            c.close()
            c.await(10_000) { it.phase == RecordPhase.DONE }

            val take = ended.lastTake!!
            assertTrue("END received", take.complete)
            val journal = store.read(take.recordingId)!!
            assertEquals(JournalState.COMPLETE, journal.state)
            assertEquals("user", journal.endReason)
            println("e2e: live $live")
            val bytes = File(store.dir(take.recordingId), RecordingStore.STREAM).readBytes()
            val (records, t) = assertLossless("e2e", bytes, journal.takeId)
            assertEquals(RecReason.USER.wire, (records.last() as StreamRecord.End).reason)
            assertTrue("IMU at ~448 Hz", t.imuSamples > TAKE_S * 300)
            assertTrue("audio at 16 kHz", t.audioFrames > TAKE_S * 12_000)
            env("DB_SIM_OUT")?.let { out ->
                val dir = File(out).apply { mkdirs() }
                File(store.dir(take.recordingId), RecordingStore.STREAM).copyTo(File(dir, "${take.recordingId}.dbrec"), overwrite = true)
                File(store.dir(take.recordingId), RecordingStore.JOURNAL).copyTo(File(dir, "${take.recordingId}.take.json"), overwrite = true)
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun recordsTheScenarioTakes() {
        val url = env("DB_SIM_URL")
        val planFile = env("DB_SIM_SCENARIOS")
        assumeTrue("DB_SIM_URL and DB_SIM_SCENARIOS not set: the scenario takes are opt-in", url != null && planFile != null)
        val plan = Json.decodeFromString(ListSerializer(SimTake.serializer()), File(planFile!!).readText())
        assertTrue("$planFile lists no takes", plan.isNotEmpty())
        val storeOut = env("DB_SIM_STORE_OUT")?.let(::File)
        val truthOut = env("DB_SIM_TRUTH_OUT")?.let(::File)
        val finalizeOut = env("DB_SIM_FINALIZE_OUT")?.let(::File)
        val sim = SimApiSession(url!!.trimEnd('/'))
        val root = Files.createTempDirectory("dbsim-takes").toFile()
        val store = RecordingStore(File(root, "recordings"))
        val app = AppInfo(BuildConfig.VERSION_NAME, "dbsim (JVM test)", 0)
        val ids = ArrayList<String>()
        try {
            var lastSession: String? = null
            for (t in plan) {
                // A session id is the minute and the install: each session gets a minute of its own.
                while (RecordingIds.sessionId(Instant.now(), SIM_INSTALL) == lastSession) Thread.sleep(500)
                val id = recordPlannedTake(sim, store, app, t)
                val journal = store.read(id)!!
                lastSession = journal.sessionId
                ids += id
                truthOut?.let { dir -> File(dir.apply { mkdirs() }, "$id.json").writeText(sim.truth(journal.takeId) + "\n") }
                storeOut?.let { dir -> store.dir(id).copyRecursively(File(dir, id), overwrite = true) }
            }
            finalizeOut?.let { out -> finalizeCopies(store, ids, File(root, "check"), out) }
        } finally {
            root.deleteRecursively()
        }
    }

    /** One session of [t]: open, the take with its sim commands, its end, close; returns the recording id. */
    private fun recordPlannedTake(sim: SimApiSession, store: RecordingStore, app: AppInfo, t: SimTake): String {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            sim.awake()
            sim.cmd("""{"cmd":"scenario","stop":true}""")
            val c = RecordingController(sim, store, StreamLinkFactory { LanLink(it) }, { SIM_INSTALL }, app, scope)
            c.open(t.setup.sanitized(), Secure.DIRECT)
            c.await(20_000) { it.phase == RecordPhase.READY }
            c.startTake(Secure.DIRECT)
            c.await(10_000) { it.phase == RecordPhase.RECORDING && it.live.elapsedS > 0 }
            for (step in t.steps.sortedBy { it.atS }) {
                c.await(((step.atS + 10) * 1000).toLong()) { it.phase == RecordPhase.RECORDING && it.live.elapsedS >= step.atS }
                sim.cmd(step.cmd.toString())
            }
            if (t.end != SimTake.End.DEVICE) {
                c.await(((t.takeS + 10) * 1000).toLong()) { it.phase == RecordPhase.RECORDING && it.live.elapsedS >= t.takeS }
            }
            when (t.end) {
                SimTake.End.STOP -> c.stopTake()
                SimTake.End.CLIENT_DROP -> sim.cmd("""{"cmd":"rec_client_drop"}""")
                SimTake.End.DEVICE -> Unit     // the device's END (max_s)
            }
            val ended = c.await(((t.takeS + 30) * 1000).toLong()) { it.phase == RecordPhase.READY && it.takes == 1 }
            sim.cmd("""{"cmd":"scenario","stop":true}""")
            c.close()
            c.await(10_000) { it.phase == RecordPhase.DONE }

            val take = ended.lastTake!!
            val journal = store.read(take.recordingId)!!
            val (state, reason) = when (t.end) {
                SimTake.End.STOP -> JournalState.COMPLETE to "user"
                SimTake.End.DEVICE -> JournalState.COMPLETE to "max_s"
                SimTake.End.CLIENT_DROP -> JournalState.INTERRUPTED to "client_lost"
            }
            assertEquals("${t.name}: journal state", state, journal.state)
            assertEquals("${t.name}: end reason", reason, journal.endReason)
            assertEquals(t.setup.sanitized(), journal.setup)
            println("take ${t.name}: ${take.recordingId} session ${journal.sessionId}, ${journal.take.profile}, ${journal.state} ${journal.endReason}")
            assertLossless("take ${t.name}", File(store.dir(take.recordingId), RecordingStore.STREAM).readBytes(), journal.takeId)
            return take.recordingId
        } finally {
            scope.cancel()
        }
    }

    /**
     * [bytes] (a take's stream) decoded as the app does, asserting that nothing was lost and that
     * it is take [takeId]'s; [what] names it in the messages. Returns the records and their stats.
     */
    private fun assertLossless(what: String, bytes: ByteArray, takeId: Long): Pair<List<StreamRecord>, StreamStats> {
        val (records, decode) = StreamDecoder.decodeAll(bytes, 1500)
        val st = StreamStatsTracker().also { tr -> records.forEach(tr::add) }.stats
        println("$what: ${bytes.size} bytes, ${st.elapsedUs / 1e6} s, $decode, ${st.records} records, ${st.imuSamples} IMU samples, " +
            "${st.audioFrames} audio frames, imu ${st.imuRateHz} Hz, audio ${st.audioRateHz} Hz")
        assertEquals("$what: no bytes skipped", 0L, decode.skippedBytes)
        assertEquals("$what: no unknown or malformed records", 0L, decode.unknown + decode.malformed)
        assertEquals("$what: no record lost", 0L, st.missingRecords)
        assertEquals("$what: no audio lost", 0L, st.audioGapFrames)
        assertTrue("$what: starts with SESSION", records.first() is StreamRecord.Session)
        assertEquals("$what: take id", takeId, (records.first() as StreamRecord.Session).takeId)
        return records to st
    }

    /** The environment variable [name], null when unset or blank. */
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    /**
     * Each of [ids] copied to [work] and finalized there as the app does (RecordingStore with
     * the AutoLabeler and the app's detector parameters); the dataset files then go to [out] as
     * the data repo has them.
     */
    private fun finalizeCopies(store: RecordingStore, ids: List<String>, work: File, out: File) {
        val check = RecordingStore(work, labeler = AutoLabeler({ TestParams.loaded }) { dir, e -> throw AssertionError("labelling ${dir.name} failed", e) })
        for (id in ids) {
            store.dir(id).copyRecursively(check.dir(id))
            assertTrue("$id finalized", check.finalize(id) != null)
            assertEquals("$id: finalize error", null, check.read(id)!!.finalizeError)
            UploadTestData.exportToRepo(check.dir(id), out)
        }
    }

    private fun RecordingController.await(ms: Long, pred: (RecordingState) -> Boolean): RecordingState = runBlocking {
        try {
            withTimeout(ms) { state.first(pred) }
        } catch (e: Exception) {
            throw AssertionError("still ${state.value}", e)
        }
    }

    private companion object {
        const val TAKE_S = 6.0

        /** The install id of the scenario takes: their recording ids read ..._simt_xxxx. */
        const val SIM_INSTALL = "simtakesrecorded"
    }
}

/**
 * One entry of DB_SIM_SCENARIOS: a session with [setup], one take in it. [steps] are panel
 * commands (POST /api/cmd bodies, e.g. a `scenario`) sent once the take has run [SimStep.atS]
 * seconds of device time. [end]: "stop" (STOP at [takeS]), "client_drop" (the panel drops the
 * stream client at [takeS]: the take is kept as interrupted, client_lost) or "device" (the
 * device ends it by itself, e.g. setup.max_s; [takeS] is then how long that may take).
 */
@Serializable
data class SimTake(
    val name: String,
    val setup: SessionSetup,
    val steps: List<SimStep> = emptyList(),
    @SerialName("take_s") val takeS: Double,
    val end: End = End.STOP,
) {
    @Serializable
    enum class End {
        @SerialName("stop") STOP,
        @SerialName("client_drop") CLIENT_DROP,
        @SerialName("device") DEVICE,
    }
}

@Serializable
data class SimStep(@SerialName("at_s") val atS: Double, val cmd: JsonObject)

/**
 * The simulator's recorder through its panel API (no Bluetooth): rec_ctrl writes are its
 * `rec` command, rec_state comes from /api/state (snap.rec and snap.recorder.session). The
 * rest of the device is a FakeDeviceSession.
 */
class SimApiSession(private val url: String, private val base: FakeDeviceSession = FakeDeviceSession()) : DeviceSession by base {
    private val json = Json { ignoreUnknownKeys = true }
    private val _recorder = MutableStateFlow<RecorderStatus?>(RecorderStatus.NOT_READ)
    override val recorder: StateFlow<RecorderStatus?> = _recorder

    override suspend fun refreshRecorder(): RecorderStatus {
        val state = withContext(Dispatchers.IO) { http("GET", "/api/state", null) }
        val snap = json.parseToJsonElement(state).jsonObject["snap"]!!.jsonObject
        val rec = snap["rec"]!!.jsonObject
        val ses = snap["recorder"]?.jsonObject?.get("session") as? JsonObject
        val st = RecorderStatus.NOT_READ.copy(
            read = true,
            state = RecState.fromWire(rec["state"]!!.jsonPrimitive.int)!!,
            reasonCode = rec["reason"]!!.jsonPrimitive.int,
            flags = rec["flags"]!!.jsonPrimitive.int,
            takeId = rec["take_id"]!!.jsonPrimitive.long,
            elapsedS = rec["elapsed_s"]!!.jsonPrimitive.int,
            maxS = rec["max_s"]!!.jsonPrimitive.int,
            profileCode = rec["profile"]!!.jsonPrimitive.int,
            port = ses?.get("port")?.jsonPrimitive?.int ?: 0,
            ip = ses?.get("ip")?.jsonPrimitive?.content.orEmpty(),
            ssid = ses?.get("ssid")?.jsonPrimitive?.content.orEmpty(),
            token = ses?.get("token")?.jsonPrimitive?.content ?: "0".repeat(32),
        )
        _recorder.value = st
        return st
    }

    override suspend fun openRecorderSession() = rec("""{"cmd":"rec","op":"open"}""")
    override suspend fun closeRecorderSession() = rec("""{"cmd":"rec","op":"close"}""")
    override suspend fun stopTake() = rec("""{"cmd":"rec","op":"stop"}""")
    override suspend fun startTake(request: TakeRequest) = rec(
        """{"cmd":"rec","op":"take_start","profile":"${request.profile.name}","mic_mask":${request.micMask},""" +
            """"gain_db":${request.gainDb},"max_s":${request.maxS},"take_id":${request.takeId}}""",
    )
    override suspend fun mark(code: Int, text: String) = rec("""{"cmd":"rec","op":"mark","code":$code,"text":${JsonPrimitive(text)}}""")

    fun cmd(body: String) {
        http("POST", "/api/cmd", body)
    }

    /**
     * The device running: out of sleep (it sleeps 3 min after the last activity when no
     * session holds it) by a motion nudge, which reboots it, and switched on with a short PWR
     * press when it is off (the off timer, an hour of sleep; motion does not wake it then);
     * waited for up to 30 s.
     */
    fun awake() {
        val until = System.currentTimeMillis() + 30_000
        var woken = false
        while (true) {
            val snap = json.parseToJsonElement(http("GET", "/api/state", null)).jsonObject["snap"]!!.jsonObject
            val st = snap["device_state"]?.jsonPrimitive?.content
            if (st == "running" && snap["rec"] != null) return
            if (!woken && (st == "sleep" || st == "off")) {
                cmd(if (st == "off") """{"cmd":"pwrkey"}""" else """{"cmd":"nudge"}""")
                woken = true
            }
            if (System.currentTimeMillis() > until) throw AssertionError("the simulated device is $st")
            Thread.sleep(250)
        }
    }

    /** The ground truth of the finished take [takeId] (/api/rec/truth), waited for up to 15 s. */
    fun truth(takeId: Long): String {
        val until = System.currentTimeMillis() + 15_000
        while (true) {
            val text = try {
                http("GET", "/api/rec/truth", null)
            } catch (_: IOException) {
                null
            }
            val o = text?.let { json.parseToJsonElement(it).jsonObject }
            if (o != null && o["take_id"]?.jsonPrimitive?.long == takeId && o["running"]?.jsonPrimitive?.content == "false") return text
            if (System.currentTimeMillis() > until) throw AssertionError("no ground truth of take $takeId: $text")
            Thread.sleep(250)
        }
    }

    private suspend fun rec(body: String) {
        withContext(Dispatchers.IO) { cmd(body) }
        refreshRecorder()
    }

    /** The body, or DeviceError.Att with the ATT code the panel reports for a refused write. */
    private fun http(method: String, path: String, body: String?): String {
        val c = URL(url + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3_000
        c.readTimeout = 5_000
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        if (code >= 400) {
            Regex("""-> .*?0x([0-9A-Fa-f]{2})""").find(text)?.let { throw DeviceError.Att(it.groupValues[1].toInt(16)) }
            throw IOException("$method $path: $code $text")
        }
        return text
    }
}
