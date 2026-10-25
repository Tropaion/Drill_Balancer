package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.protocol.SettingsUpdate
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattErrorsTest {
    private fun err(status: Int, op: GattOp, linkUp: Boolean = true, bonded: Boolean = false) =
        GattErrors.requestError(status, op, linkUp, bonded)

    private fun attCode(e: DeviceError) = (e as DeviceError.Att).code

    @Test
    fun applicationCodesOnlyForTheirCharacteristic() {
        for (code in 0x80..0x83) assertEquals(code, attCode(err(code, GattOp.CONFIG_WRITE, bonded = true)))
        for (code in 0x84..0x86) assertEquals(code, attCode(err(code, GattOp.COMMAND, bonded = true)))
        assertEquals(0x87, attCode(err(0x87, GattOp.OTA, bonded = true)))

        // 0x85 is also Android's GATT_ERROR: on a read it is the phone's error, not BAD_ARG.
        val read = err(GattErrors.GATT_ERROR, GattOp.READ)
        assertTrue(read is DeviceError.Link)
        assertTrue(read.message!!.contains("0x85"))
        assertTrue(err(0x86, GattOp.CONFIG_WRITE, bonded = true) is DeviceError.Link)
        assertTrue(err(0x82, GattOp.COMMAND, bonded = true) is DeviceError.Link)
    }

    @Test
    fun busyIsRetryableAtt() {
        val e = err(AttError.BUSY.code, GattOp.COMMAND, bonded = true) as DeviceError.Att
        assertEquals(AttError.BUSY, e.error)
    }

    @Test
    fun standardAttCodesPassThrough() {
        assertEquals(0x0D, attCode(err(0x0D, GattOp.CONFIG_WRITE, bonded = true)))
        assertEquals(0x03, attCode(err(0x03, GattOp.COMMAND, bonded = true)))
    }

    @Test
    fun authErrorsWithoutBondAskForPairing() {
        for (code in listOf(0x05, 0x0F, 0x0C)) {
            val e = err(code, GattOp.COMMAND) as DeviceError.Att
            assertEquals(code, e.code)
            assertTrue(e.needsPairing)
        }
        // Android's "insufficient authorization" reads as the device's 0x05.
        assertEquals(0x05, attCode(err(GattErrors.GATT_INSUFFICIENT_AUTHORIZATION, GattOp.CONFIG_WRITE)))
        assertTrue(err(GattErrors.GATT_AUTH_FAIL, GattOp.PAIRING) is DeviceError.PairingFailed)
    }

    @Test
    fun authErrorsWithPhoneBondMeanStaleBond() {
        for (code in listOf(0x05, 0x0F, 0x0C, GattErrors.GATT_AUTH_FAIL)) {
            assertTrue(err(code, GattOp.CONFIG_WRITE, bonded = true) is DeviceError.StaleBond)
        }
        // Encryption with the old keys failed and took the link down (status 5 / 6 / 0x3D).
        for (code in listOf(GattErrors.HCI_AUTH_FAILURE, GattErrors.HCI_PIN_OR_KEY_MISSING, GattErrors.HCI_MIC_FAILURE)) {
            assertTrue(err(code, GattOp.PAIRING, linkUp = false, bonded = true) is DeviceError.StaleBond)
        }
        // An open read never needs the keys.
        assertTrue(err(0x05, GattOp.READ, linkUp = false, bonded = true) is DeviceError.Link)
    }

    @Test
    fun ordinaryDropDuringSecureWriteIsNotStaleBond() {
        // 0x08 is "insufficient authorization" as an ATT answer, but HCI "connection
        // timeout" as the reason a link dropped: walking out of range mid-update.
        val drop = err(GattErrors.GATT_INSUFFICIENT_AUTHORIZATION, GattOp.OTA, linkUp = false, bonded = true)
        assertEquals(DisconnectReason.LINK_LOSS, (drop as DeviceError.Link).reason)
        // The HCI key failures only mean anything as a drop reason.
        assertTrue(err(GattErrors.HCI_PIN_OR_KEY_MISSING, GattOp.COMMAND, bonded = true) !is DeviceError.StaleBond)
    }

    @Test
    fun cancelledPairingDialog() {
        assertTrue(err(FailCallback.REASON_REQUEST_FAILED, GattOp.PAIRING) is DeviceError.PairingFailed)
        assertTrue(err(FailCallback.REASON_REQUEST_FAILED, GattOp.READ) is DeviceError.Link)
    }

    @Test
    fun lostLinkAndLocalFailures() {
        val lost = err(FailCallback.REASON_DEVICE_DISCONNECTED, GattOp.COMMAND, linkUp = false, bonded = true)
        assertEquals(DisconnectReason.LINK_LOSS, (lost as DeviceError.Link).reason)
        // A link dropping mid-request reports its HCI reason, which must not read as ATT.
        assertTrue(err(0x13, GattOp.CONFIG_WRITE, linkUp = false, bonded = true) is DeviceError.Link)
        assertTrue(err(0x08, GattOp.READ, linkUp = false) is DeviceError.Link)

        assertTrue(err(FailCallback.REASON_TIMEOUT, GattOp.READ) is DeviceError.Timeout)
        assertEquals(
            DisconnectReason.BLUETOOTH_OFF,
            (err(FailCallback.REASON_BLUETOOTH_DISABLED, GattOp.READ) as DeviceError.Link).reason,
        )
        assertEquals(
            DisconnectReason.NOT_SUPPORTED,
            (err(FailCallback.REASON_DEVICE_NOT_SUPPORTED, GattOp.CONNECT, linkUp = false) as DeviceError.Link).reason,
        )
    }

    @Test
    fun connectFailures() {
        for (status in listOf(133, 147, FailCallback.REASON_TIMEOUT, FailCallback.REASON_DEVICE_DISCONNECTED)) {
            val e = err(status, GattOp.CONNECT, linkUp = false) as DeviceError.Link
            assertEquals(DisconnectReason.LINK_LOSS, e.reason)
            assertTrue(e.message!!.startsWith("Could not connect"))
        }
    }

    @Test
    fun disconnectReasons() {
        fun reason(r: Int, expected: DisconnectReason? = null, on: Boolean = true) =
            GattErrors.disconnectError(r, expected, on).reason

        assertEquals(DisconnectReason.DEVICE_CLOSED, reason(ConnectionObserver.REASON_TERMINATE_PEER_USER))
        assertEquals(DisconnectReason.LINK_LOSS, reason(ConnectionObserver.REASON_LINK_LOSS))
        assertEquals(DisconnectReason.LINK_LOSS, reason(ConnectionObserver.REASON_TIMEOUT))
        // A supervision timeout after the device's early switch to 2M PHY (Nordic's
        // earlyPhyLe2MRequest workaround reports it this way).
        assertEquals(DisconnectReason.LINK_LOSS, reason(ConnectionObserver.REASON_UNSUPPORTED_CONFIGURATION))
        // The phone ended the link: Bluetooth off only if the adapter really is off. With it
        // on, it was e.g. the bond removed in the settings, and the app retries.
        assertEquals(DisconnectReason.BLUETOOTH_OFF, reason(ConnectionObserver.REASON_TERMINATE_LOCAL_HOST, on = false))
        assertEquals(DisconnectReason.OTHER, reason(ConnectionObserver.REASON_TERMINATE_LOCAL_HOST, on = true))
        assertTrue(ReconnectPolicy.retries(reason(ConnectionObserver.REASON_TERMINATE_LOCAL_HOST, on = true)))
        assertEquals(DisconnectReason.NOT_SUPPORTED, reason(ConnectionObserver.REASON_NOT_SUPPORTED))
        assertEquals(DisconnectReason.OTHER, reason(ConnectionObserver.REASON_UNKNOWN))
        assertEquals(DisconnectReason.OTHER, reason(ConnectionObserver.REASON_SUCCESS))
        // An announced drop wins over however the radio reports it.
        assertEquals(
            DisconnectReason.FIRMWARE_UPDATED,
            reason(ConnectionObserver.REASON_TIMEOUT, DisconnectReason.FIRMWARE_UPDATED),
        )
        assertEquals(
            DisconnectReason.FACTORY_RESET.message,
            GattErrors.disconnectError(ConnectionObserver.REASON_TERMINATE_PEER_USER, DisconnectReason.FACTORY_RESET, true).message,
        )
    }

    @Test
    fun expectedDrops() {
        assertEquals(DisconnectReason.SLEEP, expectedDrop(Command.Sleep))
        assertEquals(DisconnectReason.POWER_OFF, expectedDrop(Command.PowerOff))
        assertEquals(DisconnectReason.REBOOT, expectedDrop(Command.Reboot))
        assertEquals(DisconnectReason.FACTORY_RESET, expectedDrop(Command.FactoryReset))
        assertNull(expectedDrop(Command.Zero))
        assertNull(expectedDrop(Command.SetMode(LevelMode.FLOOR)))

        val base = DeviceSettings.DEFAULTS
        assertEquals(DisconnectReason.BLE_DISABLED, expectedDrop(SettingsUpdate.diff(base, base.copy(bleEnabled = false))))
        assertNull(expectedDrop(SettingsUpdate.diff(base, base.copy(toleranceDeg = 0.3f))))
        assertNull(expectedDrop(SettingsUpdate.diff(base.copy(bleEnabled = false), base)))
    }
}
