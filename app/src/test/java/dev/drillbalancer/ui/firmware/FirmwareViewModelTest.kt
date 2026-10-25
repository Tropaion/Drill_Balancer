package dev.drillbalancer.ui.firmware

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaStatus
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.PairingPrompt
import dev.drillbalancer.ui.firmware.FirmwareUiState.Phase
import dev.drillbalancer.ui.firmwareBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.setUp(bonded: Boolean = true): Triple<FakeDeviceSession, PairingGate, FirmwareViewModel> {
        val fake = FakeDeviceSession(bonded = bonded).apply { otaStepMs = 100 }
        val gate = PairingGate(fake, backgroundScope)
        return Triple(fake, gate, FirmwareViewModel(fake, gate))
    }

    @Test
    fun checksThePickedFile() = runTest(main.dispatcher) {
        val (_, _, vm) = setUp()
        assertEquals("0.1.0", vm.state.value.deviceVersion)
        vm.onFile("blink.bin", FirmwareImage.parse(firmwareBytes(project = "blink")))
        assertEquals("The image is for project 'blink', not DrillBalancer.", vm.state.value.fileError)
        assertFalse(vm.state.value.canStart)
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        assertNull(vm.state.value.fileError)
        assertEquals("0.2.0", vm.state.value.image!!.version)
        assertTrue(vm.state.value.canStart)
        assertFalse(vm.state.value.sameVersion)
    }

    @Test
    fun progressThenRebootThenNewVersion() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        assertEquals(Phase.TRANSFERRING, vm.state.value.phase)
        advanceTimeBy(450)
        val p = vm.state.value.progress!!
        assertEquals(OtaProgress.Phase.SENDING, p.phase)
        assertEquals(39, p.percent)   // 4 of 10 steps, rounded down
        assertNotNull(vm.state.value.etaSeconds)
        assertFalse(vm.state.value.canPick)
        // Sent, verified; the device reboots and the link drops (FIRMWARE_UPDATED).
        advanceTimeBy(1_000)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
        // The session reconnects and reads the new revision.
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals("0.2.0", vm.state.value.newVersion)
        assertEquals("0.2.0", vm.state.value.deviceVersion)
    }

    @Test
    fun noStoppingOnceTheDeviceVerifies() = runTest(main.dispatcher) {
        val (_, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        assertTrue(vm.state.value.canCancel)
        advanceTimeBy(1_050)
        // FINISH is out: the device installs the image whatever the app does now.
        assertEquals(OtaProgress.Phase.VERIFYING, vm.state.value.progress!!.phase)
        assertFalse(vm.state.value.canCancel)
        vm.cancel()
        assertEquals(Phase.TRANSFERRING, vm.state.value.phase)
        advanceTimeBy(100)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
    }

    @Test
    fun slowReconnectStillShowsTheNewVersion() = runTest(main.dispatcher) {
        // Four connect attempts timed out (15 s each): the session is back after 100 s,
        // still within its reconnect schedule.
        val (fake, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(1_450)
        advanceTimeBy(100_000)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals("0.2.0", vm.state.value.newVersion)
    }

    @Test
    fun unansweredFinishIsDecidedByTheReconnect() = runTest(main.dispatcher) {
        // The link dropped while the device verified: no failure, the version tells.
        val (fake, _, vm) = setUp()
        fake.otaReplyLost = true
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(1_450)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
        assertTrue(vm.state.value.unconfirmed)
        assertNull(vm.state.value.error)
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals("0.2.0", vm.state.value.newVersion)
    }

    @Test
    fun unansweredFinishWithTheOldFirmwareBackFails() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.otaReplyLost = true
        fake.otaFailure = OtaStatus.HASH_MISMATCH   // the device did not install it
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(1_450)
        fake.connect("F4:12:FA:00:1A:2B")
        val s = vm.state.value
        assertEquals(Phase.FAILED, s.phase)
        assertTrue(s.error!!.text, "firmware 0.1.0" in s.error.text)
        assertTrue(s.canStart)
    }

    @Test
    fun unansweredFinishWithoutReconnectIsNotConfirmed() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.otaReplyLost = true
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(1_450)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS))   // the session gave up
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertNull(vm.state.value.newVersion)
        assertTrue(vm.state.value.unconfirmed)
    }

    @Test
    fun givenUpReconnectEndsTheUpdateAtOnce() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(1_450)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
        fake.dropLink(DeviceError.Link(DisconnectReason.FIRMWARE_UPDATED))
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertNull(vm.state.value.newVersion)
    }

    @Test
    fun cancelAborts() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(350)
        vm.cancel()
        assertEquals(Phase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        // Nothing installed, still connected, ready to try again
        assertEquals("0.1.0", fake.info.value!!.firmwareRevision)
        assertTrue(vm.state.value.canStart)
    }

    @Test
    fun restartWaitsForTheStoppedTransfer() = runTest(main.dispatcher) {
        // The stopped transfer still sends ABORT and then turns indications off; a new one
        // that subscribed meanwhile would lose its START reply.
        val (fake, _, vm) = setUp()
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceTimeBy(350)
        val cleanup = CompletableDeferred<Unit>()
        fake.otaCleanup = cleanup
        vm.cancel()
        assertTrue(vm.state.value.canStart)
        vm.start()
        assertEquals(Phase.TRANSFERRING, vm.state.value.phase)
        assertEquals(1, fake.otaStarts)
        // Stopped again while waiting, and started once more: still after the first one.
        vm.cancel()
        vm.start()
        assertEquals(1, fake.otaStarts)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(2, fake.otaStarts)
        advanceTimeBy(1_450)
        assertEquals(Phase.REBOOTING, vm.state.value.phase)
    }

    @Test
    fun deviceErrorsShowTheStatusName() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.otaFailure = OtaStatus.HASH_MISMATCH
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(Phase.FAILED, s.phase)
        assertEquals("Update failed (HASH_MISMATCH)", s.error!!.title)
        assertTrue(s.error.text, OtaStatus.HASH_MISMATCH.message in s.error.text)
        assertTrue(s.canStart)   // try again
    }

    @Test
    fun updateNeedsPairing() = runTest(main.dispatcher) {
        val (_, gate, vm) = setUp(bonded = false)
        vm.onFile("drillbalancer.bin", FirmwareImage.parse(firmwareBytes("0.2.0")))
        vm.start()
        assertEquals(PairingPrompt.Stage.EXPLAIN, gate.prompt.value?.stage)
        gate.cancel()
        assertEquals(Phase.IDLE, vm.state.value.phase)
        vm.start()
        gate.pair()
        advanceTimeBy(250)
        assertEquals(OtaProgress.Phase.SENDING, vm.state.value.progress?.phase)
    }

    @Test
    fun otaErrorTextAvoidsRepeats() {
        val e = otaError(dev.drillbalancer.protocol.OtaException("START rejected: BAD_STATE", OtaStatus.BAD_STATE))
        assertEquals("Update failed (BAD_STATE)", e.title)
        assertEquals("START rejected: BAD_STATE\n${OtaStatus.BAD_STATE.message}", e.text)
        assertEquals("Update failed", otaError(dev.drillbalancer.protocol.OtaException("Timed out waiting for the device.")).title)
    }
}
