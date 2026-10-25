package dev.drillbalancer.protocol

import java.util.UUID

/** GATT services and characteristics of the device (ble_protocol.h, ble_service.c). */
object Uuids {
    // DB_UUID(x): x in bytes 2..3 of the base d71b0000-8a3e-4f6c-b5d2-1c9e0a7f4b63.
    const val DB_SVC_MAIN = 0x0001
    const val DB_CHR_ANGLE = 0x0002
    const val DB_CHR_CONFIG = 0x0003
    const val DB_CHR_COMMAND = 0x0004
    const val DB_CHR_STATUS = 0x0005
    const val DB_SVC_OTA = 0x0100
    const val DB_CHR_OTA_CTRL = 0x0101
    const val DB_CHR_OTA_DATA = 0x0102

    fun db(id: Int): UUID = UUID.fromString("d71b${hex4(id)}-8a3e-4f6c-b5d2-1c9e0a7f4b63")

    /** A 16-bit SIG UUID on the Bluetooth base UUID. */
    fun sig(id: Int): UUID = UUID.fromString("0000${hex4(id)}-0000-1000-8000-00805f9b34fb")

    private fun hex4(id: Int) = id.toString(16).padStart(4, '0')

    val SVC_MAIN: UUID = db(DB_SVC_MAIN)
    val CHR_ANGLE: UUID = db(DB_CHR_ANGLE)        // notify, AnglePacket, 10 Hz
    val CHR_CONFIG: UUID = db(DB_CHR_CONFIG)      // read (long), write (long): settings JSON
    val CHR_COMMAND: UUID = db(DB_CHR_COMMAND)    // write: Command
    val CHR_STATUS: UUID = db(DB_CHR_STATUS)      // read, notify: StatusPacket
    val SVC_OTA: UUID = db(DB_SVC_OTA)
    val CHR_OTA_CTRL: UUID = db(DB_CHR_OTA_CTRL)  // write, indicate
    val CHR_OTA_DATA: UUID = db(DB_CHR_OTA_DATA)  // write without response

    val SVC_DEVICE_INFO: UUID = sig(0x180A)
    val CHR_MANUFACTURER: UUID = sig(0x2A29)
    val CHR_MODEL: UUID = sig(0x2A24)
    val CHR_FIRMWARE_REV: UUID = sig(0x2A26)
    val SVC_BATTERY: UUID = sig(0x180F)
    val CHR_BATTERY_LEVEL: UUID = sig(0x2A19)     // read, notify: u8 percent

    /** Advertised name: this prefix plus the last two MAC bytes in hex. */
    const val NAME_PREFIX = "DrillBalancer-"
}
