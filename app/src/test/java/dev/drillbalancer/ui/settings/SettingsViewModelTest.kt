package dev.drillbalancer.ui.settings

import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.PairingPrompt
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.setUp(bonded: Boolean = true): Triple<FakeDeviceSession, PairingGate, SettingsViewModel> {
        val fake = FakeDeviceSession(bonded = bonded)
        val gate = PairingGate(fake, backgroundScope)
        return Triple(fake, gate, SettingsViewModel(fake, gate))
    }

    @Test
    fun loadsTheDeviceSettings() = runTest(main.dispatcher) {
        val (_, _, vm) = setUp()
        val s = vm.state.value
        assertEquals(DeviceSettings.DEFAULTS, s.base)
        assertEquals(DeviceSettings.DEFAULTS, s.edited)
        assertFalse(s.canSave)
        assertFalse(s.loading)
    }

    @Test
    fun saveSendsOnlyTheChangedKeys() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.step(SettingsField.TOL, -1)          // 0.50 -> 0.45
        vm.step(SettingsField.SLEEP_S, 1)       // 120 s -> 180 s
        assertEquals(setOf(SettingsField.TOL, SettingsField.SLEEP_S), vm.state.value.changed)
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertEquals("""{"tol":0.45,"sleep_s":180}""", fake.updates.single().toJson())
        val s = vm.state.value
        assertEquals(0.45f, s.base!!.toleranceDeg)
        assertTrue(s.changed.isEmpty())
        assertEquals(UiMessage.Kind.SUCCESS, s.message?.kind)
        // The next edit clears the stale "Saved."
        vm.step(SettingsField.TOL, 1)
        assertNull(vm.state.value.message)
    }

    @Test
    fun crossFieldRulesMoveTheDependentField() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        // tol above near: near follows, and both are sent (else the device answers 0x82)
        vm.set(SettingsField.TOL, 3f)
        assertEquals(3f, vm.state.value.edited!!.nearDeg)
        // brightness below dim brightness: dim brightness follows down
        vm.set(SettingsField.BRIGHT, 10f)
        assertEquals(10, vm.state.value.edited!!.dimBrightnessPct)
        assertTrue(vm.state.value.invalid.isEmpty())
        vm.save()
        assertEquals("""{"tol":3,"near":3,"bright":10,"dim_bright":10}""", fake.updates.single().toJson())
    }

    @Test
    fun editsSurviveChangesFromElsewhere() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.step(SettingsField.TARGET, 1)        // 45.0 -> 46.0
        fake.sendCommand(Command.SetMode(LevelMode.FLOOR))
        val s = vm.state.value
        assertEquals(LevelMode.FLOOR, s.base!!.mode)
        assertEquals(LevelMode.FLOOR, s.edited!!.mode)
        assertEquals(46f, s.edited.targetDeg)
        assertEquals(setOf(SettingsField.TARGET), s.changed)
        vm.save()
        assertEquals("""{"target":46}""", fake.updates.single().toJson())
    }

    @Test
    fun rejectedSaveShowsTheDeviceError() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.step(SettingsField.NEAR, 1)
        fake.failNext = DeviceError.Att(AttError.JSON_RANGE.code)
        vm.save()
        val s = vm.state.value
        assertEquals(AttError.JSON_RANGE.message, s.message?.text)
        assertEquals(UiMessage.Kind.ERROR, s.message?.kind)
        assertEquals(setOf(SettingsField.NEAR), s.changed)   // the edit is kept for another try
        assertFalse(s.saving)
    }

    @Test
    fun saveWaitsForPairing() = runTest(main.dispatcher) {
        val (fake, gate, vm) = setUp(bonded = false)
        vm.setBle(true)
        vm.step(SettingsField.BRIGHT, -1)
        vm.save()
        assertEquals(PairingPrompt.Stage.EXPLAIN, gate.prompt.value?.stage)
        assertTrue(fake.updates.isEmpty())
        gate.pair()
        assertEquals("""{"bright":90}""", fake.updates.single().toJson())
        assertEquals(90, vm.state.value.base!!.brightnessPct)
    }

    @Test
    fun failedPairingCanBeRetried() = runTest(main.dispatcher) {
        val (fake, gate, vm) = setUp(bonded = false)
        fake.pairingSucceeds = false
        vm.step(SettingsField.DIM_S, 1)
        vm.save()
        gate.pair()
        assertEquals(PairingPrompt.Stage.FAILED, gate.prompt.value?.stage)
        fake.pairingSucceeds = true
        gate.pair()
        assertNull(gate.prompt.value)
        assertEquals("""{"dim_s":45}""", fake.updates.single().toJson())
    }

    @Test
    fun discardAndReload() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.setMode(LevelMode.ANGLE)
        vm.discard()
        assertTrue(vm.state.value.changed.isEmpty())
        // Changed in the device's own menu (not notified): a reload shows it and keeps the edits.
        fake.changeOnDevice { it.copy(brightnessPct = 50) }
        vm.setBle(false)
        vm.reload()
        assertEquals(50, vm.state.value.base!!.brightnessPct)
        assertEquals(50, vm.state.value.edited!!.brightnessPct)
        assertEquals(false, vm.state.value.edited!!.bleEnabled)
        assertEquals(setOf(SettingsField.BLE), vm.state.value.changed)
        vm.discard()
        assertEquals(true, vm.state.value.edited!!.bleEnabled)
    }

    @Test
    fun conflictWithAChangeOnTheDeviceIsResolvedByTheNextSave() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        // tol raised on the device; the app still has 0.5, so near 0.8 looks valid.
        fake.changeOnDevice { it.copy(toleranceDeg = 1f) }
        vm.set(SettingsField.NEAR, 0.8f)
        vm.save()
        // The device rejects {"near":0.8} (near < tol). The app reads again: the edit stays,
        // moved up to the device's tolerance, and the message says so.
        val s = vm.state.value
        assertEquals(
            "Not saved: the settings were changed on the device, so the app read them again. " +
                "To fit the device's values, Near band (amber) is now 1.00°. Check your changes and tap Save again.",
            s.message?.text,
        )
        assertEquals(1f, s.base!!.toleranceDeg)
        assertEquals(1f, s.edited!!.nearDeg)
        assertEquals(setOf(SettingsField.NEAR), s.changed)
        vm.save()
        assertEquals("""{"near":1}""", fake.updates.single().toJson())
    }

    @Test
    fun conflictThatLeavesNothingToSaveSaysSo() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        // tol and near set to 3 on the device; the app still has 0.5 / 2.0.
        fake.changeOnDevice { it.copy(toleranceDeg = 3f, nearDeg = 3f) }
        vm.set(SettingsField.NEAR, 2.5f)
        vm.save()
        // {"near":2.5} is rejected; after the read the edit is moved up to 3, which the
        // device already has: no "out of range" left on screen with nothing to fix.
        val s = vm.state.value
        assertEquals(
            "Not saved: the settings were changed on the device, so the app read them again. " +
                "To fit the device's values, Near band (amber) is now 3.00°. Nothing is left to save.",
            s.message?.text,
        )
        assertEquals(3f, s.edited!!.nearDeg)
        assertTrue(s.changed.isEmpty())
        assertFalse(s.loading)
        assertTrue(fake.updates.isEmpty())
    }

    @Test
    fun conflictNamesTheFieldTheRuleMoved() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        // tol 2.5 is fine against the app's near 3, but near was lowered on the device.
        fake.changeOnDevice { it.copy(nearDeg = 3f) }
        vm.reload()
        fake.changeOnDevice { it.copy(nearDeg = 2f) }
        vm.set(SettingsField.TOL, 2.5f)
        vm.save()
        val s = vm.state.value
        assertEquals(
            "Not saved: the settings were changed on the device, so the app read them again. " +
                "To fit the device's values, Near band (amber) is now 2.50°. Check your changes and tap Save again.",
            s.message?.text,
        )
        assertEquals(setOf(SettingsField.TOL, SettingsField.NEAR), s.changed)
        vm.save()
        assertEquals("""{"tol":2.5,"near":2.5}""", fake.updates.single().toJson())
    }

    @Test
    fun editsDuringTheFirstReadAreKept() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val read = CompletableDeferred<Unit>()
        val session = object : DeviceSession by fake {
            override suspend fun readSettings(): DeviceSettings {
                read.await()
                return fake.readSettings()
            }
        }
        val vm = SettingsViewModel(session, PairingGate(session, backgroundScope))
        assertTrue(vm.state.value.loading)
        vm.step(SettingsField.TOL, -1)
        read.complete(Unit)
        assertFalse(vm.state.value.loading)
        assertEquals(setOf(SettingsField.TOL), vm.state.value.changed)
    }

    @Test
    fun editsDuringASaveAreKept() = runTest(main.dispatcher) {
        val fake = FakeDeviceSession()
        val write = CompletableDeferred<Unit>()
        val session = object : DeviceSession by fake {
            override suspend fun writeSettings(update: SettingsUpdate): DeviceSettings {
                write.await()
                return fake.writeSettings(update)
            }
        }
        val vm = SettingsViewModel(session, PairingGate(session, backgroundScope))
        vm.step(SettingsField.TOL, -1)
        vm.save()
        assertTrue(vm.state.value.saving)
        vm.setMode(LevelMode.ANGLE)
        write.complete(Unit)
        val s = vm.state.value
        assertEquals(0.45f, s.base!!.toleranceDeg)
        assertEquals(LevelMode.ANGLE, s.edited!!.mode)
        assertEquals(setOf(SettingsField.MODE), s.changed)
    }

    @Test
    fun turningBleOffSavesThenTheLinkDrops() = runTest(main.dispatcher) {
        val (fake, _, vm) = setUp()
        vm.setBle(false)
        vm.save()
        assertEquals("""{"ble":false}""", fake.updates.single().toJson())
        val c = fake.connection.value as ConnectionState.Disconnected
        assertEquals(DisconnectReason.BLE_DISABLED, (c.error as DeviceError.Link).reason)
    }
}
