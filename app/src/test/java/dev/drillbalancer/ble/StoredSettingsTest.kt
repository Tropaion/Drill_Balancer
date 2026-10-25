package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun unreadableReadBackIsNotHidden() = runTest {
        // A firmware bug, not a lost answer: the save must not look fine with made-up values.
        try {
            storedSettings(update, base) { throw DeviceError.InvalidData("The device sent unreadable settings") }
            fail("the unreadable read-back should have been reported")
        } catch (e: DeviceError) {
            assertTrue(e.toString(), e is DeviceError.InvalidData)
        }
    }
}
