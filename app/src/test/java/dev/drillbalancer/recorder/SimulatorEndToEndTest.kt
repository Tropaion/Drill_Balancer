package dev.drillbalancer.recorder

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.protocol.TakeRequest
import dev.drillbalancer.recorder.net.LanLink
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamRecord
import dev.drillbalancer.recorder.stream.StreamStatsTracker
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

/**
 * Opt-in end-to-end check against a running simulator (firmware/tools/sim, README "Against
 * the device simulator"): skipped unless DB_SIM_URL names its web panel, e.g.
 *
 *     ./run.sh --no-ble --rec-host 127.0.0.1           # in firmware/tools/sim
 *     DB_SIM_URL=http://127.0.0.1:8765 ./gradlew testDebugUnitTest --tests '*SimulatorEndToEndTest'
 *
 * Without Bluetooth the panel's API stands in for rec_ctrl and rec_state ([SimApiSession]);
 * the stream is the simulator's real TCP server, joined with LanLink. One take of a drilling
 * scenario is recorded through RecordingController and its journal must decode without
 * loss. DB_SIM_OUT (a folder) keeps the journal for dbdata.dbrec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SimulatorEndToEndTest {
    @Test
    fun recordsATakeFromTheSimulator() {
        val url = System.getenv("DB_SIM_URL")
        assumeTrue("DB_SIM_URL not set: the simulator check is opt-in", !url.isNullOrBlank())
        val sim = SimApiSession(url!!.trimEnd('/'))
        val root = Files.createTempDirectory("dbsim-e2e").toFile()
        val store = RecordingStore(File(root, "recordings"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
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
            val bytes = File(store.dir(take.recordingId), RecordingStore.STREAM).readBytes()
            val (records, decode) = StreamDecoder.decodeAll(bytes, 1500)
            val t = StreamStatsTracker().also { tr -> records.forEach(tr::add) }.stats
            println("e2e: ${bytes.size} bytes, $decode, ${t.records} records, ${t.imuSamples} IMU samples, " +
                "${t.audioFrames} audio frames, imu ${t.imuRateHz} Hz, audio ${t.audioRateHz} Hz, live $live")
            assertEquals("no bytes skipped", 0L, decode.skippedBytes)
            assertEquals("no unknown or malformed records", 0L, decode.unknown + decode.malformed)
            assertEquals("no record lost", 0L, t.missingRecords)
            assertEquals("no audio lost", 0L, t.audioGapFrames)
            assertTrue(records.first() is StreamRecord.Session)
            assertEquals(journal.takeId, (records.first() as StreamRecord.Session).takeId)
            assertEquals(RecReason.USER.wire, (records.last() as StreamRecord.End).reason)
            assertTrue("IMU at ~448 Hz", t.imuSamples > TAKE_S * 300)
            assertTrue("audio at 16 kHz", t.audioFrames > TAKE_S * 12_000)
            System.getenv("DB_SIM_OUT")?.takeIf { it.isNotBlank() }?.let { out ->
                val dir = File(out).apply { mkdirs() }
                File(store.dir(take.recordingId), RecordingStore.STREAM).copyTo(File(dir, "${take.recordingId}.dbrec"), overwrite = true)
                File(store.dir(take.recordingId), RecordingStore.JOURNAL).copyTo(File(dir, "${take.recordingId}.take.json"), overwrite = true)
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
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
    }
}

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
