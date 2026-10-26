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

private class MemoryPrefs(initial: SessionSetup = SessionSetup()) : SetupPrefs {
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
        vm.change(vm.state.value.setup.copy(mode = "hammer", notes = "n".repeat(300)))
        assertTrue(vm.start())
        assertEquals("hammer", prefs.setup.value.mode)
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

    @Test
    fun listRows() {
        fun entry(id: String, state: JournalState, bytes: Long, finalized: Boolean = false, s: SessionSetup = SessionSetup()) = RecordingEntry(
            TakeJournal(
                recordingId = id, sessionId = "s", takeId = 1, reviewer = "k7q2", state = state, startedUtc = "2026-10-26T14:30:12.418Z",
                setup = s, take = TakeSettings("RAW448", 3, 6, 300), app = AppInfo("0.1.0", "t", 37), link = "ap",
                stats = JournalStats(deviceUs = 42_300_000),
            ),
            bytes, finalized,
        )
        val rows = recordingRows(
            listOf(
                entry("a", JournalState.COMPLETE, 1_950_000, s = SessionSetup(material = "wood/softwood", thicknessMm = 38.0)),
                entry("b", JournalState.INTERRUPTED, 512, s = SessionSetup(material = "other", materialOther = "Cork", mode = "hammer", thicknessMm = 12.5)),
                entry("c", JournalState.RECORDING, 0),
                entry("d", JournalState.COMPLETE, 0, finalized = true),
            ),
            ZoneOffset.UTC,
        )
        assertEquals("Wood · softwood 38 mm · drill", rows[0].title)
        assertEquals("26 Oct 14:30 · 0:42 · 1.9 MB", rows[0].detail)
        assertEquals("Cork 12.5 mm · hammer", rows[1].title)
        assertEquals("Unknown · drill", rows[2].title)
        assertEquals(
            listOf(LocalStage.NEEDS_REVIEW, LocalStage.INTERRUPTED, LocalStage.RECORDING, LocalStage.SAVED),
            rows.map { it.stage },
        )
        assertTrue(rows.all { it.upload == UploadStage.NONE })
    }
}
