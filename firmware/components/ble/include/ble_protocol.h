#pragma once

// DrillBalancer BLE GATT protocol. This header is the contract with the
// Android app; keep it in sync with app/.
//
// All multi-byte integers are little-endian. Angles are centi-degrees (int16).
//
// Services:
//   Device Information 0x180A: manufacturer, model, firmware revision
//   Battery 0x180F: battery level 0x2A19 (read, notify)
//   DrillBalancer  DB_UUID(0x0001)
//     angle    DB_UUID(0x0002)  notify           db_angle_packet_t, 10 Hz
//     config   DB_UUID(0x0003)  read, write      settings JSON (settings.h)
//     command  DB_UUID(0x0004)  write            u8 DB_CMD_* | args
//     status   DB_UUID(0x0005)  read, notify     db_status_packet_t, on change / 1 Hz
//   OTA            DB_UUID(0x0100)
//     ota_ctrl DB_UUID(0x0101)  write, indicate  see ota_proto.h
//     ota_data DB_UUID(0x0102)  write w/o resp.  see ota_proto.h
//
// Security: LE Secure Connections with MITM (the device shows a 6-digit
// passkey, IO capability DisplayOnly) and bonding. Reads of angle/status and
// the standard services are open; config writes, command and both OTA
// characteristics require an encrypted, authenticated link with a 16-byte
// key (0x0C insufficient key size otherwise). Only such bonds are kept: a
// Just Works pairing (peer without input) is not stored, never evicts an
// existing bond, and does not replace one on a repeated pairing.
//
// Link policy: a link that is neither authenticated nor subscribed to any
// notification/indication is dropped after 60 s. Only an authenticated link
// with a GATT write in the last 10 minutes keeps the device out of deep
// sleep; any other link is closed when the device sleeps.
//
// Advertising: name "DrillBalancer-XXXX" (last 2 MAC bytes, hex), the
// DrillBalancer service UUID in the scan response.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 128-bit base d71b0000-8a3e-4f6c-b5d2-1c9e0a7f4b63; DB_UUID(x) puts x in
// bytes 2..3 (the "0000" after d71b).
#define DB_UUID_STR(x) "d71b" x "-8a3e-4f6c-b5d2-1c9e0a7f4b63"
// NimBLE BLE_UUID128_INIT byte order (little-endian, last byte first).
#define DB_UUID128_INIT(lo, hi) \
    BLE_UUID128_INIT(0x63, 0x4b, 0x7f, 0x0a, 0x9e, 0x1c, 0xd2, 0xb5, \
                     0x6c, 0x4f, 0x3e, 0x8a, (lo), (hi), 0x1b, 0xd7)

#define DB_SVC_MAIN       0x0001
#define DB_CHR_ANGLE      0x0002
#define DB_CHR_CONFIG     0x0003
#define DB_CHR_COMMAND    0x0004
#define DB_CHR_STATUS     0x0005
#define DB_SVC_OTA        0x0100
#define DB_CHR_OTA_CTRL   0x0101
#define DB_CHR_OTA_DATA   0x0102

#define DB_ANGLE_FLAG_ON_TARGET  (1u << 0)
#define DB_ANGLE_FLAG_NEAR       (1u << 1)
#define DB_ANGLE_FLAG_YAW_VALID  (1u << 2)
#define DB_ANGLE_FLAG_YAW_STALE  (1u << 3)
#define DB_ANGLE_FLAG_ZERO_SET   (1u << 4)   // a zero is set (any mode; also with the bit
                                             // vertical, where YAW_VALID stays clear)
#define DB_ANGLE_FLAG_STILL      (1u << 5)
#define DB_ANGLE_FLAG_IMU_OK     (1u << 6)
// Without IMU_OK (IMU missing, lost, or no fresh reading) only ZERO_SET can
// be set: ON_TARGET, NEAR, YAW_VALID, YAW_STALE and STILL stay clear, and
// the angles are meaningless (zero, or the last values).

typedef struct __attribute__((packed)) {
    uint8_t version;          // 1
    uint8_t mode;             // level_mode_t
    uint8_t flags;            // DB_ANGLE_FLAG_*
    uint8_t seq;              // increments per notification
    int16_t elevation_cdeg;
    int16_t yaw_cdeg;
    int16_t bubble_x_cdeg;
    int16_t bubble_y_cdeg;
    int16_t deviation_cdeg;
} db_angle_packet_t;          // 14 bytes

#define DB_STATUS_FLAG_BATTERY_VALID (1u << 0)
#define DB_STATUS_FLAG_CHARGING      (1u << 1)
#define DB_STATUS_FLAG_CHARGE_DONE   (1u << 2)
#define DB_STATUS_FLAG_VBUS          (1u << 3)
#define DB_STATUS_FLAG_MOUNT_CAL     (1u << 4)
#define DB_STATUS_FLAG_ACCEL_CAL     (1u << 5)
#define DB_STATUS_FLAG_CALIBRATING   (1u << 6)

typedef struct __attribute__((packed)) {
    uint8_t version;          // 1
    uint8_t battery_pct;
    uint16_t battery_mv;
    uint8_t flags;            // DB_STATUS_FLAG_*
    uint8_t power_state;      // app_power_t
    uint8_t cal_kind;         // app_cal_kind_t
    uint8_t cal_samples;      // saturates at 255
    uint8_t cal_coverage;     // ACCEL_CAL_DIR_* bits
    int8_t cal_last_result;   // level_cal_result_t / accel_cal_result_t (>= 0),
                              // -1 none or cancelled, -2 solved but not saved,
                              // -3 sensor cal OK and saved, mount cal reset
                              // (redo it); 0 for a sensor cal means there was
                              // no mount cal to reset
} db_status_packet_t;         // 10 bytes

// command characteristic: u8 opcode, then args
#define DB_CMD_ZERO             0x01
#define DB_CMD_CLEAR_ZERO       0x02
#define DB_CMD_SET_MODE         0x03   // u8 mode
// A calibration started over BLE is cancelled when the link drops. Any
// calibration is cancelled after a long idle time (sleep_s + 5 min).
#define DB_CMD_MOUNT_CAL_START  0x10
#define DB_CMD_MOUNT_CAL_FINISH 0x11
#define DB_CMD_ACCEL_CAL_START  0x12
#define DB_CMD_ACCEL_CAL_FINISH 0x13
#define DB_CMD_CAL_CANCEL       0x14
#define DB_CMD_SLEEP            0x20
#define DB_CMD_POWER_OFF        0x21
#define DB_CMD_REBOOT           0x22
#define DB_CMD_FACTORY_RESET    0x23   // requires u8 0xA5 as confirmation; also
                                       // deletes all bonds and drops the link
#define DB_CMD_FACTORY_RESET_CONFIRM 0xA5

// Write errors. Besides the standard ATT codes (0x05 insufficient
// authentication / 0x0F insufficient encryption: pair first; 0x0D invalid
// length: a command with missing or extra bytes, an empty or oversized
// config or ota_ctrl write), writes fail with these application errors:
#define DB_ATT_ERR_JSON_PARSE   0x80   // config: not a JSON object
#define DB_ATT_ERR_JSON_TYPE    0x81   // config: a known key has the wrong type
#define DB_ATT_ERR_JSON_RANGE   0x82   // config: a value or combination is out of range
#define DB_ATT_ERR_STORE        0x83   // config: saving to flash failed, nothing changed
#define DB_ATT_ERR_UNKNOWN_CMD  0x84   // command: unknown opcode
#define DB_ATT_ERR_BAD_ARG      0x85   // command: bad mode, or FACTORY_RESET without 0xA5
#define DB_ATT_ERR_BUSY         0x86   // command: queue full, try again
#define DB_ATT_ERR_UNAVAILABLE  0x87   // ota_ctrl / ota_data: OTA not available
// Notes: config reads are open and consistent across a long read (the JSON
// is captured at offset 0). A config write is applied atomically onto the
// stored settings, so it never undoes a calibration stored at the same time.
// OTA: the START reply's max_chunk is for this connection (ATT MTU - 5, 18
// until the central exchanges the MTU) and holds for the whole transfer. Long (queued) config writes are supported; the
// JSON is applied once on execute. A config or command write is answered
// after the change was stored / queued.

#ifdef __cplusplus
}
#endif
