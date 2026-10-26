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
//   Recorder       DB_UUID(0x0200)  training-data recording
//     rec_ctrl  DB_UUID(0x0201) write            u8 DB_REC_OP_* | args
//     rec_state DB_UUID(0x0202) read, notify     db_rec_state_packet_t, on change
//
// Security: LE Secure Connections with MITM (the device shows a 6-digit
// passkey, IO capability DisplayOnly) and bonding. Reads of angle/status and
// the standard services are open; config writes, command, both OTA
// characteristics and both recorder characteristics require an encrypted,
// authenticated link with a 16-byte key (0x0C insufficient key size
// otherwise). rec_state carries the Wi-Fi credentials and the stream token,
// so it is notified to authenticated links only. Only such bonds are kept:
// a Just Works pairing (peer without input) is not stored, never evicts an
// existing bond, and does not replace one on a repeated pairing.
//
// Recording: OPEN starts a session (a Wi-Fi soft-AP, or with
// DB_REC_FLAG_NO_AP a stream server on the LAN), rec_state then says
// WAIT_CLIENT with the SSID, passphrase, address and token. The client
// joins, connects to ip:port and sends HELLO (rec_stream.h, the stream
// format); rec_state goes READY. TAKE_START ... STOP records one take; a
// session holds many. CLOSE ends it. OTA, calibration and a session
// exclude each other, and an open session keeps the device out of deep
// sleep. A take survives a BLE drop for 120 s.
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
#define DB_SVC_REC        0x0200
#define DB_CHR_REC_CTRL   0x0201
#define DB_CHR_REC_STATE  0x0202

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
#define DB_STATUS_FLAG_RECORDING     (1u << 7)   // a recorder session is open

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

// rec_ctrl characteristic: u8 op, then args. Answered once the recorder
// has taken the op (or refused it, see the DB_ATT_ERR_REC_* codes).
#define DB_REC_OP_OPEN       0x01   // start a session
#define DB_REC_OP_CLOSE      0x02   // end the take (if any) and the session
#define DB_REC_OP_TAKE_START 0x03   // u8 profile, u8 mic_mask, u8 gain_db, u16 max_s, u32 take_id
#define DB_REC_OP_STOP       0x04   // end the take
#define DB_REC_OP_MARK       0x05   // u16 code, text (UTF-8, <= DB_REC_MARK_TEXT_MAX bytes,
                                    // no control characters): an EVENT MARK in the stream
#define DB_REC_TAKE_START_LEN 10    // with the op byte
#define DB_REC_MARK_TEXT_MAX  40
// TAKE_START arguments: profile DB_REC_PROFILE_*; mic_mask DB_REC_MIC_* (0 =
// no audio); gain_db 0 .. DB_REC_GAIN_MAX_DB; max_s 0 (= DB_REC_MAX_S_DEFAULT)
// .. DB_REC_MAX_S_LIMIT seconds.
#define DB_REC_GAIN_MAX_DB        37
#define DB_REC_MAX_S_DEFAULT      300
#define DB_REC_MAX_S_LIMIT        1200
#define DB_REC_PROFILE_LEVEL      0  // the level pipeline's (on-chip LPF on)
#define DB_REC_PROFILE_RAW448     1  // 448.4 Hz, LPF off, +-4 g
#define DB_REC_PROFILE_RAW448_16G 2  // 448.4 Hz, LPF off, +-16 g (hammer drilling)
#define DB_REC_PROFILE_RAW897     3  // 896.8 Hz, LPF off, +-4 g, experimental
#define DB_REC_MIC_1              (1u << 0)
#define DB_REC_MIC_2              (1u << 1)

#define DB_REC_STATE_IDLE        0
#define DB_REC_STATE_AP_STARTING 1
#define DB_REC_STATE_WAIT_CLIENT 2   // AP up, credentials valid: join and connect
#define DB_REC_STATE_READY       3   // stream client connected
#define DB_REC_STATE_RECORDING   4
#define DB_REC_STATE_DRAINING    5   // take stopped, the rest and END go out
#define DB_REC_STATE_CLOSING     6

// Why the last take or session ended (rec_stream.h REC_REASON_*).
#define DB_REC_REASON_NONE        0
#define DB_REC_REASON_USER        1
#define DB_REC_REASON_MAX_S       2
#define DB_REC_REASON_CLIENT_LOST 3
#define DB_REC_REASON_BLE_LOST    4
#define DB_REC_REASON_BATTERY     5
#define DB_REC_REASON_IMU         6
#define DB_REC_REASON_SHUTDOWN    7
#define DB_REC_REASON_AP_FAIL     8
#define DB_REC_REASON_NO_CLIENT   9
#define DB_REC_REASON_IDLE        10

#define DB_REC_FLAG_NO_AP       (1u << 0)   // no soft-AP: connect over the current network
#define DB_REC_FLAG_CLIENT      (1u << 1)   // a stream client is connected
#define DB_REC_FLAG_AUDIO       (1u << 2)   // the microphones are recording
#define DB_REC_FLAG_IMU_LOST    (1u << 3)
#define DB_REC_FLAG_BATTERY_LOW (1u << 4)   // below the take-start limit without USB

typedef struct __attribute__((packed)) {
    uint8_t version;          // 1
    uint8_t state;            // DB_REC_STATE_*
    uint8_t reason;           // DB_REC_REASON_*
    uint8_t flags;            // DB_REC_FLAG_*
    uint32_t take_id;         // current or last take of the session
    uint16_t elapsed_s;       // of that take
    uint16_t max_s;
    uint32_t sent_kib;        // stream data sent this session
    uint32_t dropped;         // records dropped this take (stream buffer full)
    uint8_t ring_pct;         // stream buffer fill
    uint8_t profile;          // DB_REC_PROFILE_* of the take
    uint8_t channel;          // Wi-Fi channel, 0 without AP
    uint8_t reserved;
    uint16_t port;            // stream server
    uint8_t ip[4];            // its IPv4 address, first octet first
    char ssid[16];            // NUL-padded; ssid, pass and token are zero
    char pass[24];            // (and ip, port) unless the session is reachable:
    uint8_t token[16];        // WAIT_CLIENT .. DRAINING; ssid/pass empty with NO_AP
} db_rec_state_packet_t;      // 86 bytes

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
#define DB_ATT_ERR_REC_STATE    0x88   // rec_ctrl: not in this recorder state, or an OTA
                                       // update or a calibration runs
#define DB_ATT_ERR_REC_RADIO    0x89   // rec_ctrl OPEN: Wi-Fi or the stream buffer
                                       // could not be set up
#define DB_ATT_ERR_REC_BATTERY  0x8A   // rec_ctrl TAKE_START: battery below 15 % without USB
// rec_ctrl also answers 0x84 (unknown op), 0x85 (TAKE_START or MARK argument
// out of range) and 0x86 (queue full).
// Android names 0x88..0x8A too (GATT_PENDING, GATT_AUTH_FAIL, GATT_MORE): a
// client maps them for rec_ctrl writes before any rule for its own stack
// codes, or REC_RADIO reads as a failed pairing.
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
