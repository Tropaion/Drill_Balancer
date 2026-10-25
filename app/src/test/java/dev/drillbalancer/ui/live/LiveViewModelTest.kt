package dev.drillbalancer.ui.live

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.PairingPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class LiveViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.setUp(bonded: Boolean = true): Triple<FakeDeviceSession, PairingGate, LiveViewModel> {
        val fake = FakeDeviceSession(bonded = bonded)
        val gate = PairingGate(fake, backgroundScope)
        return Triple(fake, gate, LiveViewModel(fake, gate))
    }

    @Test
    fun followsTheDeviceMode() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        assertEquals(LevelMode.WALL, vm.state.value.mode)
        assertTrue(vm.state.value.hasAngle)
        // Changed elsewhere (device screen, settings): the tab follows the packets.
        fake.sendCommand(Command.SetMode(LevelMode.ANGLE))
        assertEquals(LevelMode.ANGLE, vm.state.value.mode)
    }

    @Test
    fun modeTabSendsSetMode() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.setMode(LevelMode.FLOOR)
        assertEquals(listOf<Command>(Command.SetMode(LevelMode.FLOOR)), fake.commands)
        assertEquals(LevelMode.FLOOR, vm.state.value.mode)
        assertEquals(LevelMode.FLOOR, vm.angle.value!!.mode)
        // Tapping the selected tab sends nothing.
        vm.setMode(LevelMode.FLOOR)
        assertEquals(1, fake.commands.size)
    }

    @Test
    fun pendingModeFallsBackWhenTheDeviceRejects() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.failNext = DeviceError.Att(AttError.BUSY.code)
        vm.setMode(LevelMode.ANGLE)
        assertEquals(LevelMode.WALL, vm.state.value.mode)
        assertEquals(AttError.BUSY.message, vm.state.value.message?.text)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun modeChangeWaitsForPairing() = runTest(main.dispatcher) {
        val (fake, gate, vm) = setUp(bonded = false)
        vm.setMode(LevelMode.FLOOR)
        // Nothing sent yet; the tab shows the request while the pairing screen explains.
        assertEquals(PairingPrompt.Stage.EXPLAIN, gate.prompt.value?.stage)
        assertTrue(fake.commands.isEmpty())
        assertEquals(LevelMode.FLOOR, vm.state.value.mode)
        gate.pair()
        assertNull(gate.prompt.value)
        assertEquals(listOf<Command>(Command.SetMode(LevelMode.FLOOR)), fake.commands)
        assertEquals(LevelMode.FLOOR, vm.state.value.mode)
    }

    @Test
    fun cancelledPairingDropsTheModeChange() = runTest(main.dispatcher) {
        val (fake, gate, vm) = setUp(bonded = false)
        vm.setMode(LevelMode.ANGLE)
        gate.cancel()
        assertTrue(fake.commands.isEmpty())
        assertEquals(LevelMode.WALL, vm.state.value.mode)
        assertNull(vm.state.value.message)   // backing out is not an error
    }

    @Test
    fun zeroAndClearZero() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        assertFalse(vm.state.value.zeroSet)
        fake.setOrientation(3f, yawDeg = 10f)
        vm.zero()
        assertTrue(vm.state.value.zeroSet)
        assertTrue(vm.state.value.yawValid)
        assertEquals(0f, vm.angle.value!!.elevationDeg)
        vm.clearZero()
        assertFalse(vm.state.value.zeroSet)
        assertEquals(listOf(Command.Zero, Command.ClearZero), fake.commands)
    }

    @Test
    fun imuLossAndSettingsReachTheLayout() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.setImuOk(false)
        assertFalse(vm.state.value.imuOk)
        assertNull(vm.angle.value!!.status)
        fake.setImuOk(true)
        assertTrue(vm.state.value.imuOk)
        assertEquals(45f, vm.state.value.targetDeg)
    }

    @Test
    fun aReadingWithoutNewPacketsGoesStale() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        assertTrue(vm.state.value.hasAngle)
        advanceTimeBy(900)
        runCurrent()
        fake.setOrientation(1f)   // a new packet starts the clock again
        advanceTimeBy(900)
        runCurrent()
        assertNotNull(vm.angle.value)
        // The link is still up but nothing came for a second: no old ON TARGET.
        advanceTimeBy(200)
        runCurrent()
        assertNull(vm.angle.value)
        assertFalse(vm.state.value.hasAngle)
        fake.setOrientation(2f)
        assertTrue(vm.state.value.hasAngle)
        assertEquals(2f, vm.angle.value!!.elevationDeg)
    }

    @Test
    fun waitsWhileReconnecting() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        assertFalse(vm.state.value.hasAngle)
        assertNull(vm.angle.value)
        fake.connect("F4:12:FA:00:1A:2B")
        assertTrue(vm.state.value.hasAngle)
    }

    @Test
    fun layoutStateIgnoresAngleOnlyChanges() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        val before = vm.state.value
        // 10 Hz packets with new numbers but the same flags must not rebuild the layout state.
        fake.setOrientation(5f)
        fake.setOrientation(6f)
        assertTrue(before === vm.state.value)
    }
}
