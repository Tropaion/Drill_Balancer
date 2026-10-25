package dev.drillbalancer.ui.device

import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun showsInfoAndBattery() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = DeviceViewModel(fake, PairingGate(fake, backgroundScope))
        val s = vm.state.value
        assertEquals("DrillBalancer-1A2B", s.name)
        assertEquals("0.1.0", s.info!!.firmwareRevision)
        assertEquals(87, s.status!!.batteryPct)
        assertTrue(s.bonded)
    }

    @Test
    fun noStaleStatusWhileReconnecting() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = DeviceViewModel(fake, PairingGate(fake, backgroundScope))
        fake.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        assertNull(vm.state.value.status)
        assertNull(vm.state.value.batteryPct)
        fake.connect("F4:12:FA:00:1A:2B")
        assertEquals(87, vm.state.value.status!!.batteryPct)
    }

    @Test
    fun factoryResetEndsTheLinkWithTheBondHint() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = DeviceViewModel(fake, PairingGate(fake, backgroundScope))
        vm.send(Command.FactoryReset)
        assertEquals(listOf<Command>(Command.FactoryReset), fake.commands)
        val error = (fake.connection.value as ConnectionState.Disconnected).error as DeviceError.Link
        assertEquals(DisconnectReason.FACTORY_RESET, error.reason)
    }

    @Test
    fun commandErrorsAreShown() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val vm = DeviceViewModel(fake, PairingGate(fake, backgroundScope))
        fake.failNext = DeviceError.Att(0x86)
        vm.send(Command.Reboot)
        assertEquals("The device is busy. Try again.", vm.state.value.message?.text)
        vm.dismissMessage()
        assertEquals(null, vm.state.value.message)
    }
}
