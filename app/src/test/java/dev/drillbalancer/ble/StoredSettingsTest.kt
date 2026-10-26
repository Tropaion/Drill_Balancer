package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.SettingsWrite
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// BleDeviceSession.writeSettings after the config write succeeded.
class StoredSettingsTest {
    private val base = DeviceSettings.DEFAULTS
    private val update = SettingsUpdate.diff(base, base.copy(toleranceDeg = 0.3f))

    @Test
    fun readBackWins() = runTest {
        val device = base.copy(toleranceDeg = 0.3f, nearDeg = 2f)
        assertEquals(SettingsWrite(device), storedSettings(update, base) { device })
    }

    @Test
    fun failedReadBackStillReportsTheStoredWrite() = runTest {
        // The device stored the keys; a lost or unsent read must not make the save look failed.
        val known = base.copy(nearDeg = 2f)
        val stored = storedSettings(update, known) { throw DeviceError.Timeout("read") }
        assertEquals(SettingsWrite(known.copy(toleranceDeg = 0.3f)), stored)
        assertEquals(SettingsWrite(base.copy(toleranceDeg = 0.3f)), storedSettings(update, null) { throw DeviceError.NotConnected() })
        assertEquals(null, storedSettings(update, known) { throw DeviceError.Link("lost") }.readBackError)
    }

    @Test
    fun unreadableReadBackIsNotHidden() = runTest {
        // A firmware bug, not a lost answer: still a stored write (the session keeps it), but
        // the problem comes with it instead of made-up values looking fine.
        for (error in listOf(DeviceError.InvalidData("The device sent unreadable settings"), DeviceError.Att(0x0E))) {
            val stored = storedSettings(update, base) { throw error }
            assertEquals(base.copy(toleranceDeg = 0.3f), stored.settings)
            assertTrue(stored.toString(), stored.readBackError === error)
        }
    }
}
