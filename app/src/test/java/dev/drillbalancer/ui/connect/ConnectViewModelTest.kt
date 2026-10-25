package dev.drillbalancer.ui.connect

import dev.drillbalancer.ble.ScanException
import dev.drillbalancer.ble.ScannedDevice
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val a = ScannedDevice("F4:12:FA:00:1A:2B", "DrillBalancer-1A2B", -60, bonded = true, lastSeenMs = 0)
    private val b = ScannedDevice("F4:12:FA:00:3C:4D", "DrillBalancer-3C4D", -80, bonded = false, lastSeenMs = 0)

    /** A scanner that stays open, like DeviceScanner, and counts its starts. */
    private class Scanner(val results: MutableStateFlow<List<ScannedDevice>> = MutableStateFlow(emptyList())) {
        var starts = 0
        fun scan(): Flow<List<ScannedDevice>> = flow {
            starts++
            results.collect { emit(it) }
        }
    }

    @Test
    fun listsDevicesWithTheLastUsedFirst() = runTest(main.dispatcher) {
        val scanner = Scanner()
        val vm = ConnectViewModel(FakeDeviceSession(connected = false), scanner::scan, flowOf(a.address))
        vm.startScan()
        assertTrue(vm.state.value.scanning)
        scanner.results.value = listOf(b, a)
        val s = vm.state.value
        assertEquals(a, s.lastDevice)
        assertEquals(listOf(b), s.others)
        vm.stopScan()
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun connectSucceeds() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession(connected = false)
        val vm = ConnectViewModel(fake, Scanner()::scan, flowOf(null))
        vm.connect(b.address)
        assertEquals(ConnectionState.Connected(b.address, "DrillBalancer-1A2B", true), fake.connection.value)
        assertNull(vm.state.value.connecting)
        assertNull(vm.state.value.message)
    }

    @Test
    fun failedConnectShowsWhyAndScansAgain() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession(connected = false)
        val scanner = Scanner()
        val vm = ConnectViewModel(fake, scanner::scan, flowOf(null))
        vm.startScan()
        fake.failNext = DeviceError.Link(DisconnectReason.LINK_LOSS)
        vm.connect(a.address)
        val s = vm.state.value
        assertEquals(UiMessage(DisconnectReason.LINK_LOSS.message), s.message)
        assertFalse(s.removeBond)
        assertTrue(s.scanning)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun linkEndShowsTheReason() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = ConnectViewModel(fake, Scanner()::scan, flowOf(null))
        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))
        assertEquals(UiMessage(DisconnectReason.SLEEP.message, UiMessage.Kind.INFO), vm.state.value.message)
        vm.dismissMessage()
        assertNull(vm.state.value.message)

        fake.connect(a.address)
        fake.dropLink(DeviceError.Link(DisconnectReason.FACTORY_RESET))
        assertTrue(vm.state.value.removeBond)
        assertEquals(UiMessage.Kind.WARNING, vm.state.value.message?.kind)
    }

    @Test
    fun scanErrorsAreShown() = runTest(main.dispatcher) {
        val vm = ConnectViewModel(
            FakeDeviceSession(connected = false),
            { flow { throw ScanException("Scanning was started too often.") } },
            flowOf(null),
        )
        vm.startScan()
        assertEquals("Scanning was started too often.", vm.state.value.scanError)
        assertFalse(vm.state.value.scanning)
    }
}
