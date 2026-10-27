package dev.drillbalancer.ui.training

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.JournalStats
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingEntry
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.Secure
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.TakeJournal
import dev.drillbalancer.recorder.TakeSettings
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.upload.UploadStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.ZoneOffset

/** SetupPrefs in memory. */
internal class MemoryPrefs(initial: SessionSetup = SessionSetup()) : SetupPrefs {
    override val setup = MutableStateFlow(initial)
    override suspend fun save(setup: SessionSetup) {
        this.setup.value = setup
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)   // android.util.Log in the controller
@Config(sdk = [37])
class TrainingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.controller(fake: FakeDeviceSession) = RecordingController(
        fake, RecordingStore(File(tmp.root, "rec")),
        StreamLinkFactory { throw LinkException("no network in this test", LinkException.Kind.CONNECT) },
        { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), backgroundScope, io = main.dispatcher,
    )

    @Test
    fun setupKnowsWhyItCannotStart() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = TrainingSetupViewModel(MemoryPrefs(), fake, controller(fake), PairingGate(fake, backgroundScope))
        assertNull(vm.state.value.blocker)
        assertTrue(vm.state.value.loaded)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS))
        assertEquals("Connect to the DrillBalancer to record.", vm.state.value.blocker)
        assertFalse(vm.start())
        fake.recorderSupported = false
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals("The device's firmware has no recorder. Update its firmware.", vm.state.value.blocker)
    }

    @Test
    fun startRemembersTheSetupAndOpensTheSession() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val prefs = MemoryPrefs(SessionSetup(material = "metal/steel", drillModel = "Bosch GSR 18V-55"))
        val c = controller(fake)
        val vm = TrainingSetupViewModel(prefs, fake, c, PairingGate(fake, backgroundScope))
        assertEquals("metal/steel", vm.state.value.setup.material)
        vm.change(vm.state.value.setup.copy(mode = "hammer", notes = "n".repeat(300), drillType = "drill", motor = "other", motorOther = "m".repeat(50)))
        assertTrue("a mode contradicting the tool does not stop Start", vm.start())
        assertEquals("hammer", prefs.setup.value.mode)
        assertEquals(listOf("drill", "other", "m".repeat(40)), prefs.setup.value.let { listOf(it.drillType, it.motor, it.motorOther) })
        assertEquals(200, prefs.setup.value.notes.length)
        // OPEN went out; the stream link fails here (no network in this test).
        assertEquals(listOf("read", "open"), fake.recorderLog.take(2))
        assertEquals(RecordPhase.FAILED, c.state.value.phase)
        assertEquals("no network in this test", c.state.value.error!!.text)
    }

    @Test
    fun theRecordScreenShowsTheSessionsOwnSetup() = runTest(main.dispatcher) {
        // Not the remembered setup (saved without waiting; at activity scope offline, the
        // first session's): the one the controller's session records.
        val fake = FakeDeviceSession()
        val c = controller(fake)
        val vm = RecordViewModel(c, null)
        assertEquals("", vm.state.value.title)
        c.open(SessionSetup(material = "wood/softwood", thicknessMm = 38.0), Secure.DIRECT)
        assertEquals("Wood · softwood 38 mm · drill", vm.state.value.title)
        c.dismiss()
        c.open(SessionSetup(material = "metal/steel", mode = "hammer"), Secure.DIRECT)
        advanceUntilIdle()
        assertEquals("Metal · steel · hammer", vm.state.value.title)
    }

    /** A recording changed by the finalizer, a saved review or a delete (store.changes) reloads the list. */
    @Test
    fun theListReloadsWhenARecordingChanges() = runTest(main.dispatcher) {
        val store = RecordingStore(File(tmp.root, "rec"))
        val c = RecordingController(
            FakeDeviceSession(), store, StreamLinkFactory { throw LinkException("no network in this test", LinkException.Kind.CONNECT) },
            { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), backgroundScope, io = main.dispatcher,
        )
        val vm = TrainingListViewModel(store, c, null, io = main.dispatcher)
        advanceUntilIdle()
        assertEquals(listOf<String>(), vm.state.value.rows.map { it.id })
        val j = TakeJournal(
            recordingId = "20261026T143012Z_k7q2_9f3a", sessionId = "20261026T1430_k7q2", takeId = 7, reviewer = "k7q2",
            state = JournalState.COMPLETE, startedUtc = "2026-10-26T14:30:12.418Z", setup = SessionSetup(),
            take = TakeSettings("RAW448", 3, 6, 300), app = AppInfo("0.1.0", "t", 37), link = "lan",
        )
        store.create(j, 1_000) { 0L }.close()
        advanceUntilIdle()
        assertEquals("not reloaded by itself", 0, vm.state.value.rows.size)
        store.changed(j.recordingId)
        advanceUntilIdle()
        assertEquals(listOf(j.recordingId), vm.state.value.rows.map { it.id })
        // a take deleted from its review (RecordingStore.remove): its row goes
        assertTrue(store.remove(j.recordingId))
        advanceUntilIdle()
        assertEquals(listOf<String>(), vm.state.value.rows.map { it.id })
    }

    @Test
    fun theToolInOneLine() {
        assertEquals("", toolLine(SessionSetup()))
        assertEquals(
            "Drill/driver · Brushless · Bosch GSR 18V-55",
            toolLine(SessionSetup(drillType = "drill", motor = "brushless", drillModel = " Bosch GSR 18V-55 ")),
        )
        assertEquals("Corded (AC)", toolLine(SessionSetup(motor = "universal_ac")))
        // "other" without its text is unknown in meta.session (Finalizer.sessionLabels): left out too
        assertEquals("Magnetic drill · Makita", toolLine(SessionSetup(drillType = "other", drillTypeOther = "Magnetic drill", motor = "other", drillModel = "Makita")))
        assertEquals("Air", toolLine(SessionSetup(drillType = "other", drillTypeOther = "  ", motor = "other", motorOther = "Air")))
    }

    @Test
    fun listRows() {
        fun entry(
            id: String, state: JournalState, bytes: Long, review: String? = null, s: SessionSetup = SessionSetup(), storageError: String? = null,
        ) = RecordingEntry(
            TakeJournal(
                recordingId = id, sessionId = "s", takeId = 1, reviewer = "k7q2", state = state, startedUtc = "2026-10-26T14:30:12.418Z",
                setup = s, take = TakeSettings("RAW448", 3, 6, 300), app = AppInfo("0.1.0", "t", 37), link = "ap",
                stats = JournalStats(deviceUs = 42_300_000),
            ),
            bytes, bytes, review, storageError,
        )
        val rows = recordingRows(
            listOf(
                entry("a", JournalState.COMPLETE, 1_950_000, s = SessionSetup(material = "wood/softwood", thicknessMm = 38.0)),
                entry("b", JournalState.INTERRUPTED, 512, s = SessionSetup(material = "other", materialOther = "Cork", mode = "hammer", thicknessMm = 12.5)),
                entry("c", JournalState.RECORDING, 0),
                entry("d", JournalState.COMPLETE, 0, review = "reviewed"),
                entry("e", JournalState.INTERRUPTED, 0, review = "auto_only"),
                // the phone full: its dataset files could not be written, or its journal not closed
                entry("f", JournalState.COMPLETE, 0, storageError = "No space left on device"),
                entry("g", JournalState.RECORDING, 0, storageError = "No space left on device"),
            ),
            ZoneOffset.UTC,
        )
        assertEquals("Wood · softwood 38 mm · drill", rows[0].title)
        assertEquals("26 Oct 14:30 · 0:42 · 1.9 MB", rows[0].detail)
        assertEquals("Cork 12.5 mm · hammer", rows[1].title)
        assertEquals("Unknown · drill", rows[2].title)
        assertEquals(
            listOf(
                RecordingStage.NEEDS_REVIEW, RecordingStage.INTERRUPTED, RecordingStage.RECORDING, RecordingStage.SAVED, RecordingStage.NEEDS_REVIEW,
                RecordingStage.INTERRUPTED, RecordingStage.INTERRUPTED,
            ),
            rows.map { it.stage },
        )
        // every recording not being recorded opens: the review waits for its files, or says why
        // there are none (with Try again after a storage error)
        assertEquals(listOf(true, true, false, true, true, true, true), rows.map { it.canOpen })
        assertTrue(rows.all { it.upload == UploadStage.None })
    }
}
