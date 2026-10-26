package dev.drillbalancer.device

import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.LevelStatus
import dev.drillbalancer.protocol.MountCalResult
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaStatus
import dev.drillbalancer.protocol.SensorCalResult
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.ui.firmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler time
class FakeDeviceSessionTest {
    private suspend fun expectError(block: suspend () -> Unit): DeviceError {
        try {
            block()
        } catch (e: DeviceError) {
            return e
        }
        fail("expected a DeviceError")
        throw AssertionError()
    }

    @Test
    fun publishesStateOnConstruction() {
        val s = FakeDeviceSession()
        assertEquals(ConnectionState.Connected("F4:12:FA:00:1A:2B", "DrillBalancer-1A2B", true), s.connection.value)
        assertEquals(LevelStatus.ON_TARGET, s.angle.value!!.status)
        assertEquals(87, s.batteryPct.value)
        assertEquals("0.1.0", s.info.value!!.firmwareRevision)
        assertEquals(DeviceSettings.DEFAULTS, s.settings.value)
        assertEquals(CalKind.IDLE, s.status.value!!.calKind)

        val off = FakeDeviceSession(connected = false)
        assertEquals(ConnectionState.Disconnected(), off.connection.value)
        assertEquals(null, off.angle.value)
    }

    @Test
    fun connectAndSecureOperationsNeedBond() = runTest {
        val s = FakeDeviceSession(connected = false, bonded = false)
        assertTrue(expectError { s.readSettings() } is DeviceError.NotConnected)
        s.connect("AA:BB:CC:DD:EE:FF")
        assertEquals(ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "DrillBalancer-1A2B", false), s.connection.value)
        assertEquals(DeviceSettings.DEFAULTS, s.readSettings())   // reads are open
        val e = expectError { s.sendCommand(Command.Zero) } as DeviceError.Att
        assertTrue(e.needsPairing)
        s.ensureBonded()
        assertEquals(true, (s.connection.value as ConnectionState.Connected).bonded)
        s.sendCommand(Command.Zero)
        assertEquals(listOf<Command>(Command.Zero), s.commands)
    }

    @Test
    fun pairingFailure() = runTest {
        val s = FakeDeviceSession(bonded = false)
        s.pairingSucceeds = false
        assertTrue(expectError { s.ensureBonded() } is DeviceError.PairingFailed)
    }

    @Test
    fun zeroAndModes() = runTest {
        val s = FakeDeviceSession()
        s.setOrientation(5f, yawDeg = 10f)
        assertEquals(LevelStatus.OFF, s.angle.value!!.status)
        s.sendCommand(Command.Zero)
        val a = s.angle.value!!
        assertTrue(a.zeroSet && a.yawValid)
        assertEquals(0f, a.elevationDeg, 0f)
        assertEquals(LevelStatus.ON_TARGET, a.status)
        s.sendCommand(Command.SetMode(LevelMode.ANGLE))   // shares the zero with WALL
        assertTrue(s.angle.value!!.zeroSet)
        assertEquals(LevelMode.ANGLE, s.settings.value!!.mode)
        assertEquals(-45f, s.angle.value!!.deviationDeg, 0.01f)
        s.sendCommand(Command.SetMode(LevelMode.FLOOR))   // other family: zero cleared
        assertFalse(s.angle.value!!.zeroSet)
        s.sendCommand(Command.Zero)
        s.sendCommand(Command.ClearZero)
        assertFalse(s.angle.value!!.zeroSet)
    }

    @Test
    fun settingsWriteAppliesOnlyChangedKeys() = runTest {
        val s = FakeDeviceSession()
        val base = s.readSettings()
        // Meanwhile the device changed the mode.
        s.sendCommand(Command.SetMode(LevelMode.FLOOR))
        val stored = s.writeSettings(SettingsUpdate.diff(base, base.copy(brightnessPct = 50))).settings
        assertEquals(50, stored.brightnessPct)
        assertEquals(LevelMode.FLOOR, stored.mode)
        assertEquals(stored, s.settings.value)

        // A write that breaks a cross-field rule with the stored values: ATT 0x82.
        val e = expectError { s.writeSettings(SettingsUpdate.diff(stored, stored.copy(dimBrightnessPct = 60))) }
        assertEquals(0x82, (e as DeviceError.Att).code)
        assertEquals(stored, s.settings.value)
    }

    @Test
    fun mountCalibration() = runTest {
        val s = FakeDeviceSession()
        s.sendCommand(Command.MountCalStart)
        assertEquals(CalKind.MOUNT, s.status.value!!.calKind)
        assertTrue(s.status.value!!.calibrating)
        s.sendCommand(Command.MountCalFinish)
        val st = s.status.value!!
        assertEquals(CalKind.IDLE, st.calKind)
        assertEquals(CalResult.Mount(MountCalResult.OK), st.lastResult(CalKind.MOUNT))
        assertTrue(st.mountCalValid)
        assertTrue(s.settings.value!!.cal.mount)

        s.mountCalResult = MountCalResult.TOO_LITTLE_ROLL
        s.sendCommand(Command.MountCalStart)
        s.sendCommand(Command.MountCalFinish)
        assertEquals(CalResult.Mount(MountCalResult.TOO_LITTLE_ROLL), s.status.value!!.lastResult(CalKind.MOUNT))
    }

    @Test
    fun sensorCalibrationResetsMountCalibration() = runTest {
        val s = FakeDeviceSession()
        val sim = launch { s.simulate() }
        s.sendCommand(Command.AccelCalStart)
        s.sendCommand(Command.AccelCalFinish)   // nothing captured yet
        assertEquals(CalResult.Sensor(SensorCalResult.POOR_COVERAGE), s.status.value!!.lastResult(CalKind.ACCEL))

        s.sendCommand(Command.MountCalStart)
        s.sendCommand(Command.MountCalFinish)
        s.sendCommand(Command.AccelCalStart)
        testScheduler.advanceTimeBy(6100)       // one side per second
        assertEquals(CoverageDir.entries.toSet(), s.status.value!!.coverage)
        s.sendCommand(Command.AccelCalFinish)
        val st = s.status.value!!
        assertEquals(CalResult.SensorOkMountReset, st.lastResult(CalKind.ACCEL))
        assertTrue(st.accelCalValid)
        assertFalse(st.mountCalValid)
        sim.cancel()
    }

    @Test
    fun simulationCountsMountSamplesAndWobbles() = runTest {
        val s = FakeDeviceSession()
        val sim = launch { s.simulate() }
        s.sendCommand(Command.MountCalStart)
        val seq0 = s.angle.value!!.seq
        testScheduler.advanceTimeBy(1050)
        assertEquals(50, s.status.value!!.calSamples)
        assertEquals((seq0 + 10) and 0xFF, s.angle.value!!.seq)
        s.sendCommand(Command.CalCancel)
        assertEquals(CalResult.None, s.status.value!!.lastResult(CalKind.MOUNT))
        sim.cancel()
    }

    @Test
    fun powerCommandsDropTheLink() = runTest {
        val s = FakeDeviceSession()
        s.sendCommand(Command.MountCalStart)
        s.sendCommand(Command.Sleep)
        val c = s.connection.value as ConnectionState.Disconnected
        assertEquals(DisconnectReason.SLEEP, (c.error as DeviceError.Link).reason)
        assertTrue(expectError { s.sendCommand(Command.Zero) } is DeviceError.NotConnected)
        s.connect("F4:12:FA:00:1A:2B")
        assertEquals(CalKind.IDLE, s.status.value!!.calKind)   // a BLE calibration ends with the link
        assertEquals(CalResult.None, s.status.value!!.lastResult(CalKind.MOUNT))
    }

    @Test
    fun factoryResetClearsSettingsAndBond() = runTest {
        val s = FakeDeviceSession(settings = DeviceSettings.DEFAULTS.copy(brightnessPct = 40))
        s.sendCommand(Command.FactoryReset)
        assertTrue(s.connection.value is ConnectionState.Disconnected)
        s.connect("F4:12:FA:00:1A:2B")
        assertEquals(DeviceSettings.DEFAULTS, s.readSettings())
        assertEquals(false, (s.connection.value as ConnectionState.Connected).bonded)
    }

    @Test
    fun disablingBleStoresThenDropsTheLink() = runTest {
        val s = FakeDeviceSession()
        val base = s.readSettings()
        val written = s.writeSettings(SettingsUpdate.diff(base, base.copy(bleEnabled = false))).settings
        assertEquals(false, written.bleEnabled)
        val c = s.connection.value as ConnectionState.Disconnected
        assertEquals(DisconnectReason.BLE_DISABLED, (c.error as DeviceError.Link).reason)
    }

    @Test
    fun failNextThrowsOnce() = runTest {
        val s = FakeDeviceSession()
        s.failNext = DeviceError.Att(0x86)
        assertEquals(0x86, (expectError { s.sendCommand(Command.Zero) } as DeviceError.Att).code)
        s.sendCommand(Command.Zero)
    }

    @Test
    fun otaProgressThenReboot() = runTest {
        val s = FakeDeviceSession()
        val events = s.startOta(firmware("0.2.0")).toList()
        assertEquals(OtaProgress.Phase.STARTING, events.first().phase)
        assertEquals(OtaProgress.Phase.DONE, events.last().phase)
        assertEquals(100, events.last().percent)
        assertTrue(s.connection.value is ConnectionState.Disconnected)
        s.connect("F4:12:FA:00:1A:2B")
        assertEquals("0.2.0", s.info.value!!.firmwareRevision)
    }

    @Test
    fun otaFailure() = runTest {
        val s = FakeDeviceSession()
        s.otaFailure = OtaStatus.HASH_MISMATCH
        try {
            s.startOta(firmware("0.2.0")).toList()
            fail()
        } catch (e: OtaException) {
            assertEquals(OtaStatus.HASH_MISMATCH, e.status)
        }
        assertTrue(s.connection.value is ConnectionState.Connected)
    }
}
