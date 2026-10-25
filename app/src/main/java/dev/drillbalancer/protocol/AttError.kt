package dev.drillbalancer.protocol

/**
 * ATT errors the device answers writes with (ble_protocol.h): the standard codes it uses
 * and its application codes DB_ATT_ERR_* (named after them).
 */
enum class AttError(val code: Int, val message: String, val needsPairing: Boolean = false) {
    INSUFFICIENT_AUTHENTICATION(
        0x05, "Not paired. Pair with the device and enter the 6-digit code shown on its screen.", true,
    ),
    INSUFFICIENT_KEY_SIZE(
        0x0C, "The pairing is not secure enough. Remove the device in the phone's Bluetooth settings and pair again.", true,
    ),
    INVALID_LENGTH(0x0D, "The device rejected the request (invalid length)."),
    INSUFFICIENT_ENCRYPTION(
        0x0F, "Not paired. Pair with the device and enter the 6-digit code shown on its screen.", true,
    ),
    JSON_PARSE(0x80, "The device could not read the settings."),
    JSON_TYPE(0x81, "A setting has the wrong type."),
    JSON_RANGE(0x82, "A setting is out of range or conflicts with another setting on the device."),
    STORE(0x83, "The device could not save the settings. Nothing was changed."),
    UNKNOWN_CMD(0x84, "The device does not know this command. Update its firmware."),
    BAD_ARG(0x85, "The device rejected the command's argument."),
    BUSY(0x86, "The device is busy. Try again."),
    UNAVAILABLE(0x87, "Firmware update is not available on the device.");

    companion object {
        fun fromCode(code: Int): AttError? = entries.firstOrNull { it.code == code }

        fun messageFor(code: Int): String =
            fromCode(code)?.message ?: "Bluetooth error 0x${code.toString(16).padStart(2, '0').uppercase()}."
    }
}
