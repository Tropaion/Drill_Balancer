package dev.drillbalancer.device

import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.protocol.StatusPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One DrillBalancer link as the UI sees it. The BLE layer implements it; FakeDeviceSession
 * (test sources) stands in for it in tests.
 *
 * Reads (angle, status, battery, info, settings) work on any link. Settings writes,
 * commands and OTA need the bonded, authenticated link: without it they fail with
 * DeviceError.Att (0x05 / 0x0F), so call [ensureBonded] first.
 *
 * The suspend functions throw [DeviceError]; [startOta]'s flow also throws
 * dev.drillbalancer.protocol.OtaException.
 */
interface DeviceSession {
    val connection: StateFlow<ConnectionState>

    /** Latest angle notification (10 Hz); null until the first one. */
    val angle: StateFlow<AnglePacket?>

    /** Latest status (read on connect, then notified); null until known. */
    val status: StateFlow<StatusPacket?>

    /** Battery Level 0x2A19 (0 while the gauge reading is invalid); null until known. */
    val batteryPct: StateFlow<Int?>

    /** Device Information, read on connect. */
    val info: StateFlow<DeviceInfo?>

    /** Last settings read or written; null until read. */
    val settings: StateFlow<DeviceSettings?>

    /** Connects (and subscribes to angle, status and battery); returns once connected. */
    suspend fun connect(address: String)

    suspend fun disconnect()

    /**
     * Pairs if the link is not bonded yet: the device shows a 6-digit passkey, the user
     * types it into the system pairing dialog. Returns once the link is encrypted and
     * authenticated; throws DeviceError.PairingFailed otherwise, or DeviceError.StaleBond
     * if the phone's bond no longer works (the device was factory-reset).
     */
    suspend fun ensureBonded()

    /** Reads the config characteristic (long read) and updates [settings]. */
    suspend fun readSettings(): DeviceSettings

    /**
     * Writes only the changed keys, then reads the settings back (they also update
     * [settings]); if only that read fails, the result is the update applied to the last
     * known settings. ATT 0x82 if the result breaks a rule with the device's current values.
     */
    suspend fun writeSettings(update: SettingsUpdate): DeviceSettings

    /** Returns once the device has queued the command (ATT 0x86: busy, try again). */
    suspend fun sendCommand(command: Command)

    /**
     * Cold: collecting it runs the update (OtaClient) and emits progress; it completes
     * after FINISH succeeded, then the device reboots and the link drops. Cancelling the
     * collection aborts the transfer on the device.
     */
    fun startOta(image: FirmwareImage): Flow<OtaProgress>
}

sealed interface ConnectionState {
    /**
     * Not connected; [error] says why the last link ended or failed, null if on purpose.
     * [reconnecting]: the session will try to get the link back by itself (attempts show
     * as Connecting); false once it gives up.
     */
    data class Disconnected(val error: DeviceError? = null, val reconnecting: Boolean = false) : ConnectionState

    data class Connecting(val address: String) : ConnectionState

    data class Connected(val address: String, val name: String?, val bonded: Boolean) : ConnectionState
}

/** Device Information service 0x180A. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmwareRevision: String,   // PROJECT_VER of the running image, e.g. "0.1.0"
)

sealed class DeviceError(message: String) : Exception(message) {
    /** The device answered with an ATT error (ble_protocol.h). */
    class Att(val code: Int) : DeviceError(AttError.messageFor(code)) {
        val error: AttError? get() = AttError.fromCode(code)
        val needsPairing: Boolean get() = error?.needsPairing == true
    }

    class NotConnected : DeviceError("Not connected to the device.")

    class PairingFailed(message: String = "Pairing failed. Check the 6-digit code on the device's screen and try again.") :
        DeviceError(message)

    /**
     * The phone still has a bond the device no longer knows (it was factory-reset):
     * encryption fails. Remove the device in the phone's Bluetooth settings, then pair again.
     */
    class StaleBond : DeviceError(
        "The device no longer knows this phone. Remove it in the phone's Bluetooth settings, then pair again.",
    )

    /** BLUETOOTH_CONNECT (API 31+) was not granted or was revoked. */
    class MissingPermission : DeviceError("The app may not use Bluetooth. Allow \"Nearby devices\" in its settings.")

    class Timeout(what: String) : DeviceError("The device did not answer ($what).")

    /** The link failed or dropped (GATT error, out of range, device asleep); [reason] says how. */
    class Link(message: String, val reason: DisconnectReason = DisconnectReason.OTHER) : DeviceError(message) {
        constructor(reason: DisconnectReason) : this(reason.message, reason)
    }

    /** The device sent something that does not decode. */
    class InvalidData(message: String) : DeviceError(message)
}

/** Why a link ended; the expected ones follow a command, a settings change or an update. */
enum class DisconnectReason(val message: String) {
    LINK_LOSS("Lost the connection. Is the device switched on and in range?"),
    DEVICE_CLOSED("The device closed the connection. It may have gone to sleep: wake it, then connect again."),
    NOT_SUPPORTED("This is not a DrillBalancer, or its firmware is too old for the app."),
    BLUETOOTH_OFF("Bluetooth was turned off."),
    SLEEP("The device went to sleep."),
    POWER_OFF("The device powered off."),
    REBOOT("The device is rebooting."),
    FIRMWARE_UPDATED("The device is rebooting into the new firmware."),
    FACTORY_RESET(
        "Factory reset: the device forgot this phone. Remove it in the phone's Bluetooth settings, then pair again.",
    ),
    BLE_DISABLED("Bluetooth was switched off in the device's settings. Turn it on again on the device."),
    OTHER("The connection dropped."),
}
