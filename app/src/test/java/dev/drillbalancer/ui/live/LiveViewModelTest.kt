package dev.drillbalancer.ui.live

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.components.title
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

    private fun TestScope.setUp(): Pair<FakeDeviceSession, LiveViewModel> {
        val fake = FakeDeviceSession()
        return fake to LiveViewModel(fake, PairingGate(fake, backgroundScope))
    }

    @Test
    fun followsTheDevicesView() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        assertEquals(LevelMode.WALL, vm.state.value.mode)
        assertTrue(vm.state.value.hasAngle)
        // The device picks the view from how it sits; the app only follows its packets.
        fake.setView(LevelMode.FLOOR)
        assertEquals(LevelMode.FLOOR, vm.state.value.mode)
        fake.setView(LevelMode.WALL)
        assertEquals(LevelMode.WALL, vm.state.value.mode)
        assertTrue(fake.commands.isEmpty())
    }

    @Test
    fun angleFromOldFirmwareShowsTheWallView() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        // Firmware from before the one level screen still reports its angle mode.
        fake.setView(LevelMode.ANGLE)
        val s = vm.state.value
        assertEquals(LevelMode.ANGLE, s.mode)
        assertEquals("Wall", s.mode.title)
        // Zero is enabled and works as on the wall view (LiveScreenshotTest shows the screen).
        assertTrue(s.hasAngle)
        assertFalse(s.busy)
        vm.zero()
        assertEquals(listOf<Command>(Command.Zero), fake.commands)
        assertTrue(vm.state.value.zeroSet)
        assertTrue(vm.state.value.yawValid)
    }

    @Test
    fun zeroAndClearZero() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
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
        val (fake, vm) = setUp()
        fake.setImuOk(false)
        assertFalse(vm.state.value.imuOk)
        assertNull(vm.angle.value!!.status)
        fake.setImuOk(true)
        assertTrue(vm.state.value.imuOk)
    }

    @Test
    fun aReadingWithoutNewPacketsGoesStale() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
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
        val (fake, vm) = setUp()
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        assertFalse(vm.state.value.hasAngle)
        assertNull(vm.angle.value)
        fake.connect("F4:12:FA:00:1A:2B")
        assertTrue(vm.state.value.hasAngle)
    }

    @Test
    fun followsTheDevicesInvertDot() = runTest(main.dispatcher) {
        val (_, plain) = setUp()
        assertFalse(plain.state.value.dotInvert)
        val fake = FakeDeviceSession(settings = DeviceSettings.DEFAULTS.copy(dotInvert = true))
        val vm = LiveViewModel(fake, PairingGate(fake, backgroundScope))
        fake.readSettings()
        assertTrue(vm.state.value.dotInvert)
    }

    @Test
    fun layoutStateIgnoresAngleOnlyChanges() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        val before = vm.state.value
        // 10 Hz packets with new numbers but the same flags must not rebuild the layout state.
        fake.setOrientation(5f)
        fake.setOrientation(6f)
        assertTrue(before === vm.state.value)
    }
}
