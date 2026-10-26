package dev.drillbalancer.recorder

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.RecOpcode
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.recorder.net.LanLink
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.LinkPhase
import dev.drillbalancer.recorder.net.StreamLink
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamRecord
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.PairingPrompt
import dev.drillbalancer.ui.training.asSecure
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import java.net.Socket

/**
 * The engine against FakeDeviceSession (rec_ctrl / rec_state) and a real TCP stream from
 * FakeStreamServer on 127.0.0.1, in real time with short timeouts. Robolectric only for
 * android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RecordingControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fake = FakeDeviceSession()
    private val server = FakeStreamServer(fake)
    /** What escaped the controller: in the app, an uncaught exception ends the process. */
    private val escaped = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped += e })
    private val timing = RecorderTiming(
        apStartMs = 3_000, readyMs = 3_000, pollMs = 50, openRetries = 2, takeStartSeenMs = 500, endWaitMs = 1_000,
        closeWaitMs = 2_000, readTimeoutMs = 100, stallWarnMs = 300, stallFailMs = 800, reconnectAttempts = 3,
        reconnectDelayMs = 100, publishMs = 50, minFreeBytes = 0,
    )
    private val store by lazy { RecordingStore(File(tmp.root, "recordings")) }
    private val setup = SessionSetup(material = "wood/softwood", mode = "drill", bitType = "brad_point", bitDiameterMm = 8.0)

    private fun controller(
        links: StreamLinkFactory = StreamLinkFactory { LanLink(it) },
        t: RecorderTiming = timing,
        store: RecordingStore = this.store,
        clock: RecorderClock = RecorderClock.SYSTEM,
    ) = RecordingController(fake, store, links, { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "robolectric", 37), scope, timing = t, clock = clock)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        assertEquals("escaped the controller", emptyList<Throwable>(), escaped.toList())
    }

    private fun RecordingController.await(timeoutMs: Long = 5_000, what: String = "", pred: (RecordingState) -> Boolean): RecordingState =
        runBlocking {
            try {
                withTimeout(timeoutMs) { state.first(pred) }
            } catch (e: Exception) {
                throw AssertionError("$what: still ${state.value}", e)
            }
        }

    private fun RecordingController.ready(secure: Secure = Secure.DIRECT): RecordingController {
        open(setup, secure)
        await(what = "ready") { it.phase == RecordPhase.READY }
        return this
    }

    private fun RecordingController.record(ms: Long = 400): String {
        startTake(Secure.DIRECT)
        val id = await(what = "recording") { it.phase == RecordPhase.RECORDING && it.recordingId != null }.recordingId!!
        await(what = "data") { it.live.elapsedS * 1000 >= ms }
        return id
    }

    private fun journalBytes(id: String) = File(store.dir(id), RecordingStore.STREAM).readBytes()

    @Test
    fun takesOfOneSessionEachGetTheirJournal() {
        val c = controller().ready()
        assertEquals(RecordPhase.READY, c.state.value.phase)
        assertTrue(c.state.value.lan)
        val first = c.record()
        val live = c.state.value.live
        assertTrue("sparkline", live.sparkline.isNotEmpty())
        assertNotNull("Mic 1 level", live.levels.mic1Db)
        assertTrue("rate ${live.kbPerS}", live.kbPerS > 0f)
        c.stopTake()
        val done = c.await(what = "first take ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertTrue(done.lastTake!!.complete)
        assertEquals(first, done.lastTake!!.recordingId)

        val second = c.record(200)
        c.stopTake()
        c.await(what = "second take ended") { it.phase == RecordPhase.READY && it.takes == 2 }
        c.close()
        c.await(what = "closed") { it.phase == RecordPhase.DONE }

        // Each journal is exactly what the device sent for its take, SESSION to END.
        for ((i, id) in listOf(first, second).withIndex()) {
            val bytes = journalBytes(id)
            assertArrayEquals("take $i", server.sent[i], bytes)
            val (recs, st) = StreamDecoder.decodeAll(bytes)
            assertTrue(recs.first() is StreamRecord.Session)
            assertTrue(recs.last() is StreamRecord.End)
            assertEquals(0L, st.skippedBytes)
            val j = store.read(id)!!
            assertEquals(JournalState.COMPLETE, j.state)
            assertEquals("user", j.endReason)
            assertEquals(0L, j.stats!!.missingRecords)
            assertEquals(bytes.size.toLong(), j.bytes)
            assertEquals((recs.first() as StreamRecord.Session).takeId, j.takeId)
            assertEquals("lan", j.link)
            assertEquals("k7q2", j.reviewer)
            assertTrue(RecordingIds.RECORDING_ID.matches(id))
            assertEquals(setup, j.setup)
        }
        assertEquals(store.read(first)!!.sessionId, store.read(second)!!.sessionId)
        assertEquals("read", fake.recorderLog.first())
        assertEquals(listOf("open", "take_start", "stop", "take_start", "stop", "close"), fake.recorderLog.filter { it != "read" })
        assertEquals(1, server.hellos)
        assertEquals(RecState.IDLE, fake.recorderState.state)
    }

    @Test
    fun pairingDuringStartThenTheSessionOpens() {
        val unbonded = FakeDeviceSession(bonded = false)
        FakeStreamServer(unbonded).use {
            val gate = PairingGate(unbonded, scope)
            val c = RecordingController(unbonded, store, { LanLink(it) }, { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), scope, timing = timing)
            c.open(setup, gate.asSecure())
            runBlocking { withTimeout(3_000) { gate.prompt.first { it?.stage == PairingPrompt.Stage.EXPLAIN } } }
            assertEquals(RecordPhase.STARTING_DEVICE, c.state.value.phase)
            gate.pair()
            c.await(what = "ready after pairing") { it.phase == RecordPhase.READY }
            // The bond's change re-reads rec_state too (the link came back authenticated).
            assertEquals("read", unbonded.recorderLog.first())
            assertEquals(listOf("open"), unbonded.recorderLog.filter { it != "read" })
        }
    }

    @Test
    fun cancelledPairingLeavesNothingOpen() {
        val unbonded = FakeDeviceSession(bonded = false)
        val gate = PairingGate(unbonded, scope)
        val c = RecordingController(unbonded, store, { LanLink(it) }, { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), scope, timing = timing)
        c.open(setup, gate.asSecure())
        runBlocking { withTimeout(3_000) { gate.prompt.first { it != null } } }
        gate.cancel()
        c.await(what = "idle") { it.phase == RecordPhase.IDLE }
        assertTrue(unbonded.recorderLog.isEmpty())
    }

    @Test
    fun declinedWifiApprovalFailsThenRetryWorks() {
        fake.recorderEndpoint = server.endpoint.copy(noAp = false)
        val phases = java.util.concurrent.CopyOnWriteArrayList<RecordPhase>()
        var attempts = 0
        val links = StreamLinkFactory { ep ->
            if (attempts++ == 0) {
                object : StreamLink {
                    override val viaAp = true
                    override suspend fun connect(onPhase: (LinkPhase) -> Unit): Socket {
                        onPhase(LinkPhase.JOINING_WIFI)
                        onPhase(LinkPhase.WAITING_APPROVAL)
                        kotlinx.coroutines.delay(200)   // the dialog is up a while (the state collector sees it)
                        throw LinkException("declined", LinkException.Kind.DECLINED)
                    }

                    override fun close() = Unit
                }
            } else {
                LanLink(ep.copy(noAp = true))
            }
        }
        val c = controller(links)
        val watch = scope.launch { c.state.collect { phases += it.phase } }
        c.open(setup, Secure.DIRECT)
        val failed = c.await(what = "failed") { it.phase == RecordPhase.FAILED }
        assertEquals("declined", failed.error!!.text)
        assertTrue(phases.toString(), RecordPhase.WAITING_APPROVAL in phases)
        assertEquals("DB-1a2b-k7q2", failed.ssid)
        // The device kept its session: Retry joins again without a new OPEN.
        assertEquals(RecState.WAIT_CLIENT, fake.recorderState.state)
        c.retry(Secure.DIRECT)
        c.await(what = "ready after retry") { it.phase == RecordPhase.READY }
        assertEquals(1, fake.recorderLog.count { it == "open" })
        watch.cancel()
    }

    @Test
    fun bleDropWhileRecordingKeepsTheTake() {
        val c = controller().ready()
        val id = c.record(200)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        c.await(what = "ble down") { !it.bleLinked }
        val before = c.state.value.live.bytes
        c.await(what = "data without BLE") { it.live.bytes > before + 10_000 }
        assertEquals(RecordPhase.RECORDING, c.state.value.phase)
        val reads = fake.recorderLog.count { it == "read" }
        runBlocking { fake.connect("F4:12:FA:00:1A:2B") }
        c.await(what = "ble back") { it.bleLinked }
        runBlocking { withTimeout(2_000) { while (fake.recorderLog.count { it == "read" } <= reads) kotlinx.coroutines.delay(20) } }
        c.stopTake()
        c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
    }

    @Test
    fun stopWithoutBleClosesTheStreamAndReconnects() {
        val c = controller().ready()
        val id = c.record(200)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        c.await(what = "ble down") { !it.bleLinked }
        c.stopTake()
        val back = c.await(what = "ready again") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertFalse(back.lastTake!!.complete)
        val j = store.read(id)!!
        assertEquals(JournalState.INTERRUPTED, j.state)
        assertEquals("client_lost", j.endReason)
        assertEquals("Without Bluetooth the take was stopped by closing the connection; it was kept as interrupted.", back.notice!!.text)
        assertEquals(2, server.hellos)
        // What arrived is a prefix of what was sent.
        val got = journalBytes(id)
        assertArrayEquals(server.sent[0].copyOf(got.size), got)
    }

    @Test
    fun clientDropCutsTheTakeAndReconnects() {
        val c = controller().ready()
        val id = c.record(300)
        server.dropClient()
        val back = c.await(what = "reconnected") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertTrue(back.notice!!.error)
        assertEquals(JournalState.INTERRUPTED, store.read(id)!!.state)
        assertEquals("client_lost", store.read(id)!!.endReason)
        assertEquals(2, server.hellos)
        // The next take works on the new connection.
        c.record(100)
        c.stopTake()
        c.await(what = "second take") { it.phase == RecordPhase.READY && it.takes == 2 && it.lastTake!!.complete }
    }

    @Test
    fun stopWithoutEndTimesOut() {
        server.endOnStop = false
        val c = controller().ready()
        val id = c.record(200)
        c.stopTake()
        c.await(what = "stopping") { it.phase == RecordPhase.STOPPING }
        val back = c.await(3_000, "ready after the END timeout") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertFalse(back.lastTake!!.complete)
        val j = store.read(id)!!
        assertEquals(JournalState.INTERRUPTED, j.state)
        assertTrue(j.note!!, j.note!!.startsWith("no END"))
        assertEquals(2, server.hellos)
    }

    @Test
    fun aDropWhileWaitingForEndIsALostClientAtOnce() {
        // The stream drops after STOP, before END: the take is cut as client_lost right
        // away, not after the END timeout as "no END" (the drop's handling waits behind Stop).
        server.endOnStop = false
        val c = controller(t = timing.copy(endWaitMs = 5_000)).ready()
        val id = c.record(200)
        c.stopTake()
        c.await(what = "stopping") { it.phase == RecordPhase.STOPPING }
        server.dropClient()
        val back = c.await(3_000, "ready again before the END timeout") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals("The connection to the device dropped. The take was cut short and kept as interrupted.", back.notice!!.text)
        val j = store.read(id)!!
        assertEquals(JournalState.INTERRUPTED, j.state)
        assertEquals("client_lost", j.endReason)
        assertFalse(j.note!!, j.note!!.startsWith("no END"))
        assertEquals(2, server.hellos)
    }

    @Test
    fun aJournalThatCannotBeWrittenWhileStoppingEndsTheTake() {
        server.endOnStop = false
        val failNext = AtomicBoolean(false)
        val failing = RecordingStore(File(tmp.root, "failing"), syncFile = { if (failNext.getAndSet(false)) throw IOException("disk full") else it.fd.sync() })
        val c = controller(t = timing.copy(syncMs = 50, endWaitMs = 5_000), store = failing).ready()
        val id = c.record(200)
        c.stopTake()
        c.await(what = "stopping") { it.phase == RecordPhase.STOPPING }
        failNext.set(true)
        val s = c.await(3_000, "ready, not stopping for ever") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertTrue(s.notice!!.text, s.notice!!.error && "could not store" in s.notice!!.text)
        val j = failing.read(id)!!
        assertEquals(JournalState.INTERRUPTED, j.state)
        assertTrue(j.note!!, j.note!!.startsWith("the phone could not store it"))
    }

    @Test
    fun aMarkTheDeviceWouldRefuseIsNotSent() {
        val c = controller().ready()
        c.record(100)
        assertFalse(c.mark(1, "x".repeat(RecOpcode.MARK_TEXT_MAX + 1)))
        assertFalse(c.mark(1, "tab\there"))
        assertFalse(c.mark(0x1_0000))
        assertTrue(c.mark(1, "breakthrough"))
        runBlocking { withTimeout(2_000) { while ("mark" !in fake.recorderLog) kotlinx.coroutines.delay(20) } }
        Thread.sleep(100)
        assertEquals(RecordPhase.RECORDING, c.state.value.phase)
        assertEquals(1, fake.recorderLog.count { it == "mark" })
    }

    @Test
    fun anUnexpectedErrorFailsTheSessionNotTheApp() {
        // The install id (DataStore) cannot be read at the second take: no exception may
        // escape (in the app it would end the process mid-session).
        val calls = AtomicInteger()
        val c = RecordingController(
            fake, store, { LanLink(it) }, { if (calls.getAndIncrement() == 0) "k7q2abcdefghijkl" else throw IOException("DataStore broke") },
            AppInfo("0.1.0", "robolectric", 37), scope, timing = timing,
        ).ready()
        c.startTake(Secure.DIRECT)
        val f = c.await(what = "failed") { it.phase == RecordPhase.FAILED }
        assertTrue(f.error!!.text, "DataStore broke" in f.error!!.text)
        // The device still has the session: leaving the FAILED panel closes it.
        c.dismiss()
        c.await(what = "closed") { it.phase == RecordPhase.DONE }
        assertTrue("close" in fake.recorderLog)
    }

    @Test
    fun readyOnlyOnceTheDeviceTookTheNewConnection() {
        // The device lets the dropped connection go 1 s late. Until then rec_state still says
        // READY with a client (the old one): only HELLO_OK on the new connection says it was
        // taken, and a take started then goes to it.
        server.clientLostDelayMs = 1_000
        val c = controller().ready()
        server.dropClient()
        c.await(what = "reconnecting") { it.phase == RecordPhase.CONNECTING }
        val readyAtHellos = CopyOnWriteArrayList<Int>()
        val watch = scope.launch { c.state.map { it.phase }.distinctUntilChanged().collect { if (it == RecordPhase.READY) readyAtHellos += server.hellos } }
        c.await(4_000, "ready again") { it.phase == RecordPhase.READY }
        Thread.sleep(100)
        watch.cancel()
        assertEquals("READY only on the new connection", listOf(2), readyAtHellos.toList())
        assertEquals(RecState.READY, fake.recorderState.state)
        val id = c.record(100)
        c.stopTake()
        c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
        assertEquals(1, fake.recorderLog.count { it == "take_start" })
    }

    @Test
    fun aConnectionTheDeviceNeverTakesIsNoSession() {
        // A device that accepts the TCP connection and the HELLO but sends no HELLO_OK.
        server.sendHelloOk = false
        val c = controller(t = timing.copy(readyMs = 500))
        c.open(setup, Secure.DIRECT)
        val f = c.await(what = "failed") { it.phase == RecordPhase.FAILED }
        assertEquals("The recorder did not answer", f.error!!.title)
    }

    @Test
    fun aStalledStreamIsDroppedAndReopened() {
        val c = controller().ready()
        val id = c.record(200)
        server.stalled = true
        c.await(what = "stalled shown") { it.stalled }
        c.await(3_000, what = "reconnected") { it.phase == RecordPhase.READY && it.takes == 1 }
        server.stalled = false
        assertTrue(store.read(id)!!.note!!.contains("stalled"))
    }

    @Test
    fun theDeviceEndsATakeByItself() {
        val c = controller().ready()
        c.record(200)
        server.endTake(RecReason.MAX_S.wire)
        val s = c.await(what = "ready") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals("The device ended the take: the time limit was reached.", s.notice!!.text)
        assertEquals("max_s", store.read(s.lastTake!!.recordingId)!!.endReason)
    }

    @Test
    fun refusedTakeStartLeavesNoFolder() {
        val c = controller().ready()
        fake.failRecorder("take_start", DeviceError.Att(AttError.REC_BATTERY.code))
        c.startTake(Secure.DIRECT)
        val s = c.await(what = "back to ready") { it.phase == RecordPhase.READY && it.notice != null }
        assertEquals(AttError.REC_BATTERY.message, s.notice!!.text)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun tooLittleFreeSpace() {
        val c = controller(t = timing.copy(minFreeBytes = Long.MAX_VALUE)).ready()
        c.startTake(Secure.DIRECT)
        val s = c.await(what = "notice") { it.notice != null }
        assertTrue(s.notice!!.text, s.notice!!.error && "free on the phone" in s.notice!!.text)
        assertEquals(RecordPhase.READY, s.phase)
        assertFalse("take_start" in fake.recorderLog)
    }

    @Test
    fun apFailureAndNoRecorder() {
        fake.recorderAutoAp = false
        val c = controller()
        c.open(setup, Secure.DIRECT)
        c.await(what = "starting") { it.phase == RecordPhase.STARTING_DEVICE }
        fake.recorderApFailed()
        val f = c.await(what = "failed") { it.phase == RecordPhase.FAILED }
        assertEquals("The device could not start its Wi-Fi", f.error!!.title)
        c.dismiss()
        c.await(what = "idle") { it.phase == RecordPhase.IDLE }

        fake.recorderSupported = false
        runBlocking { fake.connect("F4:12:FA:00:1A:2B") }
        c.open(setup, Secure.DIRECT)
        val n = c.await(what = "no recorder") { it.phase == RecordPhase.FAILED }
        assertEquals(DeviceError.NoRecorder().message, n.error!!.text)
    }

    @Test
    fun aBleReconnectWhileTheWifiStartsKeepsTheSession() {
        fake.recorderAutoAp = false
        val c = controller()
        c.open(setup, Secure.DIRECT)
        c.await(what = "starting") { it.phase == RecordPhase.STARTING_DEVICE }
        runBlocking { withTimeout(2_000) { while ("open" !in fake.recorderLog) kotlinx.coroutines.delay(20) } }
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        c.await(what = "ble down") { !it.bleLinked }
        // The new link's rec_state is NOT_READ (IDLE, unread) until a secured read gets through.
        fake.failRecorder("read", DeviceError.Timeout("recorder state read"))
        runBlocking { fake.connect("F4:12:FA:00:1A:2B") }
        c.await(what = "ble back") { it.bleLinked }
        fake.recorderApUp()
        c.await(what = "ready") { it.phase == RecordPhase.READY }
        assertEquals(null, c.state.value.error)
    }

    @Test
    fun closeCancelsTheReconnectsAfterADrop() {
        val c = controller(t = timing.copy(reconnectAttempts = 50, reconnectDelayMs = 300)).ready()
        val id = c.record(200)
        server.acceptClients = false
        server.dropClient()
        c.await(what = "take cut") { it.takes == 1 }
        Thread.sleep(400)   // reconnecting, the device turns every attempt away
        assertTrue(c.state.value.phase.active)
        c.close()
        c.await(2_000, "done without the remaining reconnects") { it.phase == RecordPhase.DONE }
        assertTrue("close" in fake.recorderLog)
        assertEquals(JournalState.INTERRUPTED, store.read(id)!!.state)
    }

    @Test
    fun aTakeStartedLongAfterTheLastDataIsNoStall() {
        val c = controller().ready()
        Thread.sleep(1_200)   // longer than stallFailMs: the stream is quiet between takes
        // TAKE_START is answered after 300 ms, its SESSION comes 300 ms after that.
        fake.recorderReplyMs = 300
        server.startDelayMs = 300
        val id = c.record(400)
        fake.recorderReplyMs = 0
        c.stopTake()
        val s = c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertTrue(s.lastTake!!.endText, s.lastTake!!.complete)
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
        assertEquals(1, server.hellos)
    }

    @Test
    fun theJournalSyncsOnTheMonotonicClock() {
        val syncs = AtomicInteger()
        val counted = RecordingStore(File(tmp.root, "counted"), syncFile = { syncs.incrementAndGet(); it.fd.sync() })
        // The wall clock stands still (as after it was set back): the syncs go on all the same.
        val frozen = object : RecorderClock {
            override fun elapsedMs() = System.nanoTime() / 1_000_000
            override fun now(): Instant = Instant.parse("2026-10-26T14:30:00Z")
        }
        val c = controller(t = timing.copy(syncMs = 100), store = counted, clock = frozen).ready()
        c.record(700)
        assertTrue("syncs during the take: ${syncs.get()}", syncs.get() >= 3)
    }

    @Test
    fun aReconnectWaitsUntilTheDeviceTookTheNewConnection() {
        // The device notices the dropped connection 1 s late; rec_state says RECORDING until then.
        server.clientLostDelayMs = 1_000
        val c = controller().ready()
        val id = c.record(200)
        server.stalled = true
        c.await(4_000, "reconnected") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals("the device is ready too", RecState.READY, fake.recorderState.state)
        server.stalled = false
        assertTrue(store.read(id)!!.note!!.contains("stalled"))
        val next = c.record(100)
        assertTrue(next != id)
        assertEquals(2, fake.recorderLog.count { it == "take_start" })
    }

    @Test
    fun skippedBytesOfATakeAreInItsJournal() {
        val c = controller().ready()
        val id = c.record(100)
        server.garbage = ByteArray(37) { 0x55 }
        c.await(what = "more data") { it.live.elapsedS >= 0.4 }
        c.stopTake()
        c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        val (_, st) = StreamDecoder.decodeAll(journalBytes(id))
        assertEquals(37L, st.skippedBytes)
        assertEquals(37L, store.read(id)!!.stats!!.skippedBytes)
    }

    @Test
    fun startedUtcIsWhenTheTakeBegan() {
        val c = controller().ready()
        // A slow answer (or a pairing prompt) between TAKE_START and the take's start.
        fake.recorderReplyMs = 1_200
        val before = Instant.now()
        val id = c.record(100)
        fake.recorderReplyMs = 0
        val started = Instant.parse(store.read(id)!!.startedUtc)
        assertTrue("started $started, TAKE_START at $before", !started.isBefore(before.plusMillis(1_100)))
        c.stopTake()
        c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertEquals(started, Instant.parse(store.read(id)!!.startedUtc))
    }

    @Test
    fun aTakeStartWhoseAnswerWasLostRunsAllTheSame() {
        val c = controller().ready()
        fake.loseRecorderAnswer("take_start", DeviceError.Timeout("take_start"))
        val id = c.record(300)
        assertEquals(RecordPhase.RECORDING, c.state.value.phase)
        assertEquals(null, c.state.value.notice)
        c.stopTake()
        val s = c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        assertTrue(s.lastTake!!.complete)
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
    }

    @Test
    fun aWrongTokenIsRefused() {
        fake.recorderEndpoint = server.endpoint.copy(token = ByteArray(16) { 1 })
        val c = controller()
        c.open(setup, Secure.DIRECT)
        val f = c.await(what = "failed") { it.phase == RecordPhase.FAILED }
        assertEquals("The recorder refused the connection", f.error!!.title)
        assertEquals(1, server.badHellos)
        assertEquals(0, server.hellos)
    }

    @Test
    fun theDeviceEndsTheSessionByItself() {
        // The BLE grace ran out: END of the take, rec_state IDLE, the stream's FIN; the device takes no client after it.
        server.closeAfterEnd = true
        val c = controller().ready()
        val id = c.record(200)
        server.endTake(RecReason.BLE_LOST.wire)
        val s = c.await(what = "done") { it.phase == RecordPhase.DONE }
        assertEquals("The device ended the session: Bluetooth was gone for 2 minutes.", s.notice!!.text)
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
        assertEquals("ble_lost", store.read(id)!!.endReason)
        assertEquals(1, server.hellos)
        assertFalse("close" in fake.recorderLog)
    }

    @Test
    fun anOrphanTakeIsStoppedAndItsSessionReused() {
        // The app died during a take: the device still has the session and the take.
        runBlocking { fake.openRecorderSession() }
        fake.setRecorderState { it.copy(state = RecState.RECORDING) }
        val c = controller().ready()
        assertEquals(listOf("open", "stop"), fake.recorderLog.filter { it != "read" })
        c.record(100)
    }

    @Test
    fun openIsRetriedWhileTheLastSessionGoesDown() {
        fake.failRecorder("open", DeviceError.Att(AttError.REC_STATE.code))
        controller().ready()
        assertEquals(listOf("open"), fake.recorderLog.filter { it != "read" })
    }

    @Test
    fun aClosingSessionIsWaitedOutBeforeOpen() {
        fake.setRecorderState { it.copy(state = RecState.CLOSING) }
        thread {
            Thread.sleep(300)
            fake.recorderSessionEnded()
        }
        controller().ready()
        assertEquals(listOf("open"), fake.recorderLog.filter { it != "read" })
    }

    @Test
    fun closeKeepsTheNetworkUntilTheDevicesFin() {
        server.drainMs = 400
        var closedNs = 0L
        val links = StreamLinkFactory { ep ->
            val l = LanLink(ep)
            object : StreamLink by l {
                override fun close() {
                    if (closedNs == 0L) closedNs = System.nanoTime()
                    l.close()
                }
            }
        }
        val c = controller(links).ready()
        c.close()
        c.await(what = "done") { it.phase == RecordPhase.DONE }
        assertTrue("FIN sent", server.finSentNs > 0)
        assertTrue("network released ${(server.finSentNs - closedNs) / 1_000_000} ms before the FIN", closedNs >= server.finSentNs)
    }

    @Test
    fun bytesAfterEndStayOutOfTheJournal() {
        server.afterEnd = TestStream.stats(999, TestStream.T0, 0)
        val c = controller().ready()
        val id = c.record(200)
        c.stopTake()
        c.await(what = "ended") { it.phase == RecordPhase.READY && it.takes == 1 }
        val bytes = journalBytes(id)
        assertArrayEquals(server.sent[0], bytes)
        assertTrue(StreamDecoder.decodeAll(bytes).first.last() is StreamRecord.End)
    }

    @Test
    fun closeWhileRecordingStopsTheTakeFirst() {
        val c = controller().ready()
        val id = c.record(200)
        c.close()
        c.await(what = "done") { it.phase == RecordPhase.DONE }
        assertEquals(JournalState.COMPLETE, store.read(id)!!.state)
        assertEquals(listOf("stop", "close"), fake.recorderLog.filter { it == "stop" || it == "close" })
        assertEquals(RecState.IDLE, fake.recorderState.state)
    }
}
