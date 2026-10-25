package dev.drillbalancer.ui.calibration

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.protocol.MountCalResult
import dev.drillbalancer.protocol.SensorCalResult
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.setUp(): Pair<FakeDeviceSession, CalibrationViewModel> {
        val fake = FakeDeviceSession()
        // Live motion: 5 mount samples per tick, one more sensor side per second
        backgroundScope.launch { fake.simulate() }
        return fake to CalibrationViewModel(fake, PairingGate(fake, backgroundScope))
    }

    @Test
    fun sensorCalibrationFirstPointsToTheMount() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        assertTrue(vm.state.value.known)
        assertFalse(vm.state.value.sensorDone)
        vm.startSensor()
        assertEquals(CalKind.ACCEL, vm.state.value.running)
        advanceTimeBy(3_050)
        assertEquals(3, vm.state.value.coverage.size)
        assertFalse(vm.state.value.coverageComplete)
        advanceTimeBy(3_000)
        assertTrue(vm.state.value.coverageComplete)
        assertEquals(CoverageDir.entries.toSet(), vm.state.value.coverage)
        vm.finish()
        val s = vm.state.value
        assertEquals(CalKind.IDLE, s.running)
        assertFalse(s.finishing)
        assertEquals(FinishedCal(CalKind.ACCEL, CalResult.Sensor(SensorCalResult.OK)), s.finished)
        assertEquals("Sensor calibrated. Now run the mount calibration.", s.finished!!.result.message)
        assertTrue(s.sensorDone)
        assertEquals(listOf(Command.AccelCalStart, Command.AccelCalFinish), fake.commands)
    }

    @Test
    fun sensorCalibrationResetsAnExistingMountCalibration() = runTest(main.dispatcher) {
        val (_, vm) = setUp()
        vm.startMount()
        assertEquals(CalKind.MOUNT, vm.state.value.running)
        advanceTimeBy(1_050)
        assertEquals(50, vm.state.value.samples)
        vm.finish()
        assertEquals(FinishedCal(CalKind.MOUNT, CalResult.Mount(MountCalResult.OK)), vm.state.value.finished)
        assertTrue(vm.state.value.mountDone)

        // Starting the next one clears the old result.
        vm.startSensor()
        assertNull(vm.state.value.finished)
        advanceTimeBy(6_050)
        vm.finish()
        assertEquals(FinishedCal(CalKind.ACCEL, CalResult.SensorOkMountReset), vm.state.value.finished)
        assertFalse(vm.state.value.mountDone)
        vm.done()
        assertNull(vm.state.value.finished)
    }

    @Test
    fun mountFailureIsReported() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        fake.mountCalResult = MountCalResult.TOO_LITTLE_ROLL
        vm.startMount()
        advanceTimeBy(500)
        vm.finish()
        val f = vm.state.value.finished!!
        assertFalse(f.result.success)
        assertEquals(MountCalResult.TOO_LITTLE_ROLL.message, f.result.message)
    }

    @Test
    fun finishingEarlyGivesPoorCoverage() = runTest(main.dispatcher) {
        val (_, vm) = setUp()
        vm.startSensor()
        advanceTimeBy(2_050)
        vm.finish()
        assertEquals(CalResult.Sensor(SensorCalResult.POOR_COVERAGE), vm.state.value.finished!!.result)
    }

    @Test
    fun cancelShowsNoResult() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        vm.startMount()
        advanceTimeBy(300)
        vm.cancel()
        assertEquals(CalKind.IDLE, vm.state.value.running)
        assertNull(vm.state.value.finished)
        assertEquals(Command.CalCancel, fake.commands.last())
    }

    @Test
    fun linkDropCancelsTheCalibration() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        vm.startSensor()
        advanceTimeBy(1_050)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        // The status is gone with the link: nothing known while reconnecting.
        assertFalse(vm.state.value.known)
        assertEquals(CalKind.IDLE, vm.state.value.running)
        assertNull(vm.state.value.message)
        // The reconnected device reports IDLE: the device cancelled the run, and says so.
        fake.connect("F4:12:FA:00:1A:2B")
        val s = vm.state.value
        assertTrue(s.known)
        assertEquals(CalKind.IDLE, s.running)
        assertNull(s.finished)
        assertEquals(CalibrationViewModel.CANCELLED_BY_DROP, s.message?.text)
        // Only once.
        advanceTimeBy(2_000)
        vm.dismissMessage()
        advanceTimeBy(2_000)
        assertNull(vm.state.value.message)
    }

    @Test
    fun calibrationStartedOnTheDeviceSurvivesADrop() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        fake.startCalOnDevice(CalKind.MOUNT)
        assertEquals(CalKind.MOUNT, vm.state.value.running)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals(CalKind.MOUNT, vm.state.value.running)
        assertNull(vm.state.value.message)
    }

    @Test
    fun busyDeviceKeepsFinishAvailable() = runTest(main.dispatcher) {
        val (fake, vm) = setUp()
        vm.startMount()
        fake.failNext = DeviceError.Att(0x86)
        vm.finish()
        val s = vm.state.value
        assertFalse(s.finishing)
        assertEquals(CalKind.MOUNT, s.running)
        assertEquals("The device is busy. Try again.", s.message?.text)
    }
}
