package dev.drillbalancer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.Command
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val address = "F4:12:FA:00:1A:2B"

    @Test
    fun shellFollowsTheConnection() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession(connected = false)
        val vm = SessionViewModel(fake)
        assertFalse(vm.shell.value.showMain)
        fake.connect(address)
        val s = vm.shell.value
        assertTrue(s.showMain)
        assertEquals("DrillBalancer-1A2B", s.deviceName)
        assertTrue(s.bonded)
        assertEquals(87, s.batteryPct)
        fake.setBattery(42, charging = true)
        assertEquals(42, vm.shell.value.batteryPct)
        assertTrue(vm.shell.value.charging)
    }

    @Test
    fun linkLossKeepsTheScreensWhileReconnecting() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = SessionViewModel(fake)
        val link = DeviceError.Link(DisconnectReason.LINK_LOSS)
        fake.dropLink(link, reconnecting = true)
        assertTrue(vm.shell.value.showMain)
        assertEquals(DisconnectReason.LINK_LOSS, vm.shell.value.reconnecting)
        assertNull(vm.shell.value.batteryPct)
        // An attempt (Connecting) that fails again keeps holding.
        fake.connect(address)
        fake.dropLink(link, reconnecting = true)
        assertTrue(vm.shell.value.showMain)
        fake.connect(address)
        assertNull(vm.shell.value.reconnecting)
        assertTrue(vm.shell.value.showMain)
    }

    @Test
    fun holdEndsWhenTheSessionGivesUp() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = SessionViewModel(fake)
        val reboot = DeviceError.Link(DisconnectReason.REBOOT)
        fake.dropLink(reboot, reconnecting = true)
        advanceTimeBy(120_000)
        assertTrue(vm.shell.value.showMain)
        fake.dropLink(reboot, reconnecting = false)
        assertFalse(vm.shell.value.showMain)
        assertNull(vm.shell.value.reconnecting)
    }

    @Test
    fun screensOfAnEndedSessionAreCleared() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = SessionViewModel(fake)
        val first = vm.screens
        val screen = ViewModelProvider.create(first, viewModelFactory { initializer { Probe() } })[Probe::class]
        // Held while reconnecting: same screens.
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        fake.connect(address)
        assertSame(first, vm.screens)
        assertFalse(screen.cleared)
        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))
        assertTrue(screen.cleared)
        assertNotSame(first, vm.screens)
    }

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun expectedEndsGoToTheConnectScreen() = runTest(main.dispatcher) {
        for (reason in listOf(DisconnectReason.SLEEP, DisconnectReason.FACTORY_RESET, DisconnectReason.BLE_DISABLED)) {
            val fake = FakeDeviceSession()
            val vm = SessionViewModel(fake)
            fake.dropLink(DeviceError.Link(reason))
            assertFalse(reason.name, vm.shell.value.showMain)
        }
    }

    @Test
    fun stopReconnectingDisconnects() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = SessionViewModel(fake)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS))
        vm.disconnect()
        assertFalse(vm.shell.value.showMain)
    }

    @Test
    fun pairingIsCancelledWhenTheLinkDrops() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession(bonded = false)
        val vm = SessionViewModel(fake)
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { vm.gate.secure { fake.sendCommand(Command.Zero) } } }
        assertEquals(PairingPrompt.Stage.EXPLAIN, vm.gate.prompt.value?.stage)
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS))
        assertNull(vm.gate.prompt.value)
        assertTrue(outcome!!.exceptionOrNull() is PairingCancelled)
    }

    @Test
    fun deviceAnswerAsksForPairingAgain() = runTest(main.dispatcher) {
        // The phone thinks it is bonded, but the device answers 0x05: pair, then retry once.
        val fake = FakeDeviceSession(bonded = true)
        val vm = SessionViewModel(fake)
        fake.failNext = DeviceError.Att(0x05)
        var done = false
        backgroundScope.launch {
            vm.gate.secure { fake.sendCommand(Command.Zero) }
            done = true
        }
        assertEquals(PairingPrompt.Stage.EXPLAIN, vm.gate.prompt.value?.stage)
        vm.gate.pair()
        assertTrue(done)
        assertEquals(listOf<Command>(Command.Zero), fake.commands)
    }

    @Test
    fun staleBondShowsTheBluetoothSettingsHelp() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = SessionViewModel(fake)
        fake.failNext = DeviceError.StaleBond()
        backgroundScope.launch { runCatching { vm.gate.secure { fake.sendCommand(Command.Zero) } } }
        assertEquals(PairingPrompt.Stage.STALE_BOND, vm.gate.prompt.value?.stage)
        vm.gate.cancel()
        assertNull(vm.gate.prompt.value)
        assertTrue(fake.commands.isEmpty())
    }
}
