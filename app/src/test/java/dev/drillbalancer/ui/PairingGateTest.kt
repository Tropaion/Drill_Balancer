package dev.drillbalancer.ui

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.Command
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingGateTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun staleBondAfterCancelShowsNothing() = runTest(main.dispatcher) {
        // The probe write fails only after the user closed the waiting screen.
        val probe = CompletableDeferred<Unit>()
        val fake = FakeDeviceSession(bonded = false)
        val session = object : DeviceSession by fake {
            override suspend fun ensureBonded() {
                probe.await()
                throw DeviceError.StaleBond()
            }
        }
        val gate = PairingGate(session, backgroundScope)
        backgroundScope.launch { gate.attempt({}) { session.sendCommand(Command.Zero) } }
        assertEquals(PairingPrompt.Stage.EXPLAIN, gate.prompt.value?.stage)
        gate.pair()
        assertEquals(PairingPrompt.Stage.PAIRING, gate.prompt.value?.stage)
        gate.cancel()
        probe.complete(Unit)
        // No screen whose "Pair again" has nothing left to pair for.
        assertNull(gate.prompt.value)
    }

    @Test
    fun linkLostWhilePairingShowsPairingFailed() = runTest(main.dispatcher) {
        // Android drops the link when its pairing dialog is cancelled: the Zero that waited
        // for the pairing must not vanish without a word.
        val bonding = CompletableDeferred<Unit>()
        val fake = FakeDeviceSession(bonded = false)
        val session = object : DeviceSession by fake {
            override suspend fun ensureBonded() = bonding.await()
        }
        val gate = PairingGate(session, backgroundScope)
        val errors = mutableListOf<String>()
        val result = backgroundScope.async { gate.attempt({ errors += it.text }) { session.sendCommand(Command.Zero) } }
        gate.pair()
        fake.dropLink(DeviceError.Link(DisconnectReason.OTHER), reconnecting = true)
        assertNull(result.await())
        assertNull(gate.prompt.value)
        assertEquals(listOf(DeviceError.PairingFailed().message), errors)
    }

    @Test
    fun linkLostBeforePairIsTappedShowsNothing() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession(bonded = false)
        val gate = PairingGate(fake, backgroundScope)
        val errors = mutableListOf<String>()
        val result = backgroundScope.async { gate.attempt({ errors += it.text }) { fake.sendCommand(Command.Zero) } }
        assertEquals(PairingPrompt.Stage.EXPLAIN, gate.prompt.value?.stage)
        fake.dropLink(DeviceError.Link(DisconnectReason.OTHER))
        assertNull(result.await())
        assertNull(gate.prompt.value)
        assertTrue(errors.isEmpty())   // the link's own error is shown by the session bar
    }
}
