package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// BleDeviceSession.writeSettings after the config write succeeded.
class StoredSettingsTest {
    private val base = DeviceSettings.DEFAULTS
    private val update = SettingsUpdate.diff(base, base.copy(toleranceDeg = 0.3f))

    @Test
    fun readBackWins() = runTest {
        val device = base.copy(toleranceDeg = 0.3f, nearDeg = 2f)
        assertEquals(device, storedSettings(update, base) { device })
    }

    @Test
    fun failedReadBackStillReportsTheStoredWrite() = runTest {
        // The device stored the keys; a timed-out read must not make the save look failed.
        val known = base.copy(nearDeg = 2f)
        val stored = storedSettings(update, known) { throw DeviceError.Timeout("read") }
        assertEquals(known.copy(toleranceDeg = 0.3f), stored)
        assertEquals(base.copy(toleranceDeg = 0.3f), storedSettings(update, null) { throw DeviceError.NotConnected() })
    }
}
