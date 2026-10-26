package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.Command
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.protocol.SettingsUpdate
import dev.drillbalancer.protocol.hexByte
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver

/** What a failed request was doing: it decides which device error codes it can carry. */
enum class GattOp(val label: String, val secure: Boolean) {
    CONNECT("connect", false),
    READ("read", false),
    SUBSCRIBE("subscribe", false),
    CONFIG_WRITE("settings write", true),
    COMMAND("command", true),
    OTA("firmware update", true),
    PAIRING("pairing", true),
}

/**
 * Turns the status of a failed Nordic request into a DeviceError. Pure, so the mapping is
 * unit-tested; the BLE glue only supplies whether the link is still up and whether the
 * phone holds a bond for the device.
 *
 * The status is overloaded: an ATT error from the device (ble_protocol.h), an Android stack
 * error (133 GATT_ERROR, 137 GATT_AUTH_FAIL), a negative FailCallback.REASON_*, or, when the
 * link dropped during the request, the HCI disconnect reason. Android's own GATT_* names
 * for 0x80..0x87 (GATT_NO_RESOURCES .. GATT_ILLEGAL_PARAMETER) collide with the device's
 * application codes, so those are only trusted for the operation that can produce them,
 * and 0x85 (GATT_ERROR, the device's BAD_ARG) not even there.
 */
object GattErrors {
    const val GATT_INSUFFICIENT_AUTHORIZATION = 0x08
    const val GATT_ERROR = 0x85
    const val GATT_AUTH_FAIL = 0x89

    // HCI disconnect reasons meaning the stored keys did not work.
    const val HCI_AUTH_FAILURE = 0x05
    const val HCI_PIN_OR_KEY_MISSING = 0x06
    const val HCI_MIC_FAILURE = 0x3D

    private val AUTH_CODES = setOf(
        AttError.INSUFFICIENT_AUTHENTICATION.code,
        AttError.INSUFFICIENT_ENCRYPTION.code,
        AttError.INSUFFICIENT_KEY_SIZE.code,
        GATT_INSUFFICIENT_AUTHORIZATION,
        GATT_AUTH_FAIL,
    )
    private val KEY_FAILURES = setOf(HCI_AUTH_FAILURE, HCI_PIN_OR_KEY_MISSING, HCI_MIC_FAILURE, GATT_AUTH_FAIL)

    fun requestError(status: Int, op: GattOp, linkUp: Boolean, phoneBonded: Boolean): DeviceError = when {
        status == FailCallback.REASON_BLUETOOTH_DISABLED -> DeviceError.Link(DisconnectReason.BLUETOOTH_OFF)
        status == FailCallback.REASON_DEVICE_NOT_SUPPORTED -> DeviceError.Link(DisconnectReason.NOT_SUPPORTED)
        // Nordic's code for writeCharacteristic() and friends returning false on a live link
        // (the stack is busy), and for requests still queued when the device's services
        // changed. The session checks optional characteristics first: never sent.
        status == FailCallback.REASON_NULL_ATTRIBUTE ->
            DeviceError.Link("The phone's Bluetooth stack refused the ${op.label}. Try again.", sent = false)
        // 133 and friends, or the 15 s timeout: the device was not there.
        op == GattOp.CONNECT ->
            DeviceError.Link("Could not connect. Is the device switched on and in range?", DisconnectReason.LINK_LOSS)
        status == FailCallback.REASON_TIMEOUT -> DeviceError.Timeout(op.label)
        // The bond broadcast failed the request (Nordic's only -4 here; no reliable writes):
        // pairing was cancelled or the code was wrong.
        status == FailCallback.REASON_REQUEST_FAILED -> DeviceError.PairingFailed()
        // Stored keys rejected, either answered on the link or as the reason it dropped. An
        // ATT answer needs a live link; 0x08 as a drop reason is a plain connection timeout.
        op.secure && phoneBonded && (if (linkUp) status in AUTH_CODES else status in KEY_FAILURES) ->
            DeviceError.StaleBond(dropped = !linkUp)
        status < 0 || !linkUp -> DeviceError.Link("The connection was lost during the ${op.label}.", DisconnectReason.LINK_LOSS)
        status == GATT_AUTH_FAIL -> DeviceError.PairingFailed()
        status in AUTH_CODES -> DeviceError.Att(
            if (status == GATT_INSUFFICIENT_AUTHORIZATION) AttError.INSUFFICIENT_AUTHENTICATION.code else status,
        )
        status in 0x80..0x83 && op == GattOp.CONFIG_WRITE -> DeviceError.Att(status)
        // 0x85 is also the device's BAD_ARG, but the app only sends arguments the firmware
        // knows (an older firmware aside), so Android's common GATT_ERROR is far likelier.
        status == GATT_ERROR && op == GattOp.COMMAND -> DeviceError.Link(
            "The command failed (Bluetooth error ${hexByte(GATT_ERROR)}). Try again; if it keeps failing, update the device's firmware.",
        )
        (status == AttError.UNKNOWN_CMD.code || status == AttError.BUSY.code) && op == GattOp.COMMAND -> DeviceError.Att(status)
        status == AttError.UNAVAILABLE.code && op == GattOp.OTA -> DeviceError.Att(status)
        status in 0x01..0x7F -> DeviceError.Att(status)   // standard ATT error from the device
        else -> DeviceError.Link("Bluetooth error ${hexByte(status)} during the ${op.label}.")
    }

    /** How far a request got that failed with an error from [requestError] or the session. */
    enum class Delivery {
        /** Never went on air: no link, the phone's stack refused it, no permission, no such characteristic. */
        NOT_SENT,

        /** The device answered it with an error (ATT error, stale bond, failed pairing) or with unreadable data. */
        ANSWERED,

        /** Went on air and its answer was lost: the link dropped (whatever reason it gave) or no answer in time. */
        LOST,
    }

    /**
     * Besides a drop or Bluetooth going off, Android can end a request in flight with 133
     * (GATT_ERROR) as the link drops, before the session sees the drop, and other stack codes
     * are no surer: a Link error that went out is LOST, and so are unknown exceptions.
     */
    fun delivery(error: Throwable): Delivery = when (error) {
        is DeviceError.Link ->
            if (!error.sent || error.reason == DisconnectReason.NOT_SUPPORTED) Delivery.NOT_SENT else Delivery.LOST
        is DeviceError.Timeout -> Delivery.LOST
        is DeviceError.StaleBond -> if (error.dropped) Delivery.LOST else Delivery.ANSWERED
        is DeviceError.NotConnected, is DeviceError.MissingPermission -> Delivery.NOT_SENT
        is DeviceError.Att, is DeviceError.PairingFailed, is DeviceError.InvalidData -> Delivery.ANSWERED
        else -> Delivery.LOST
    }

    /** Whether a write that failed with [error] may still have reached the device and done its work. */
    fun mayHaveArrived(error: Throwable): Boolean = delivery(error) == Delivery.LOST

    /** Whether the device answered the request that failed with [error], so its answer is worth showing. */
    fun answered(error: Throwable): Boolean = delivery(error) == Delivery.ANSWERED

    /**
     * A failed connect. [keysLost]: the device asked to pair although the phone holds a bond
     * for it, so it no longer has its half (factory reset). Android 15+ then shows its own
     * "pair again" dialog and holds GATT back until the connect, or a request of the set-up
     * after it, times out or the link drops, which would otherwise read as "not in range".
     */
    fun connectError(error: Throwable, keysLost: Boolean): Throwable {
        val heldBack = error is DeviceError.Link && error.reason == DisconnectReason.LINK_LOSS ||
            error is DeviceError.Timeout || error is DeviceError.NotConnected
        return if (keysLost && heldBack) DeviceError.StaleBond() else error
    }

    /**
     * The error a session that ended by itself reports (ConnectionObserver.REASON_*).
     * [expected] is the drop the last command, settings write or update announced;
     * [bluetoothOn] whether the phone's adapter is still enabled.
     */
    fun disconnectError(reason: Int, expected: DisconnectReason?, bluetoothOn: Boolean): DeviceError.Link = when {
        expected != null -> DeviceError.Link(expected)
        // The phone's own stack ended the link (our own closes never get here). That is the
        // adapter turning off, but also a bond removed in the settings or Android's ATT
        // timeout, which are worth a retry.
        reason == ConnectionObserver.REASON_TERMINATE_LOCAL_HOST ->
            DeviceError.Link(if (bluetoothOn) DisconnectReason.OTHER else DisconnectReason.BLUETOOTH_OFF)
        reason == ConnectionObserver.REASON_TERMINATE_PEER_USER -> DeviceError.Link(DisconnectReason.DEVICE_CLOSED)
        // UNSUPPORTED_CONFIGURATION: Nordic's name for a supervision timeout once the PHY
        // went to 2M before service discovery, which the device asks for on every connect.
        reason == ConnectionObserver.REASON_LINK_LOSS || reason == ConnectionObserver.REASON_TIMEOUT ||
            reason == ConnectionObserver.REASON_UNSUPPORTED_CONFIGURATION ->
            DeviceError.Link(DisconnectReason.LINK_LOSS)
        reason == ConnectionObserver.REASON_NOT_SUPPORTED -> DeviceError.Link(DisconnectReason.NOT_SUPPORTED)
        else -> DeviceError.Link(DisconnectReason.OTHER)
    }
}

/** The drop that follows a successful [command], if the device closes the link for it. */
fun expectedDrop(command: Command): DisconnectReason? = when (command) {
    Command.Sleep -> DisconnectReason.SLEEP
    Command.PowerOff -> DisconnectReason.POWER_OFF
    Command.Reboot -> DisconnectReason.REBOOT
    Command.FactoryReset -> DisconnectReason.FACTORY_RESET
    else -> null
}

/** ble=false: the device stores it, answers the write, then closes the link. */
fun expectedDrop(update: SettingsUpdate): DisconnectReason? =
    if (SettingsField.BLE in update.fields && !update.values.bleEnabled) DisconnectReason.BLE_DISABLED else null
