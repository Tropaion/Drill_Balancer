#pragma once

// Pure C part of the BLE service: packet building from app_state snapshots,
// command parsing and the ATT error mapping. No NimBLE or ESP-IDF
// dependencies, host-tested in test_host/ble.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "app_state.h"
#include "ble_protocol.h"
#include "rec_fsm.h"
#include "settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// Standard ATT error code (Core spec Vol 3 Part F 3.4.1.1); the device code
// checks it against NimBLE's BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN.
#define BLE_CODEC_ATT_INVALID_LEN 0x0D

// A reading older than this is reported without DB_ANGLE_FLAG_IMU_OK: the
// sensor task has stopped publishing. Without IMU_OK the reading's own flags
// (target, yaw, still) are left out too.
#define BLE_CODEC_READING_MAX_AGE_MS 500u

// Link policy (ble_protocol.h):
// - Only an authenticated link with a GATT write in the last
//   BLE_CODEC_AWAKE_AFTER_WRITE_MS holds the device out of deep sleep.
//   Streaming without writes still works while the user handles the device
//   (motion and touch keep it awake), but a forgotten or hostile connection
//   does not drain the battery.
// - A link that is neither authenticated nor subscribed to anything, and not
//   pairing, is dropped after BLE_CODEC_IDLE_UNAUTH_MS, so a scanner app or a
//   squatter cannot hold the only connection slot for nothing.
#define BLE_CODEC_AWAKE_AFTER_WRITE_MS (10u * 60u * 1000u)
#define BLE_CODEC_IDLE_UNAUTH_MS       (60u * 1000u)

// Degrees to centi-degrees, rounded, saturated to int16. NaN gives 0.
int16_t ble_codec_cdeg(float deg);

void ble_codec_angle(const app_snapshot_t *s, uint8_t seq, uint32_t now_ms,
                     db_angle_packet_t *out);

void ble_codec_status(const app_snapshot_t *s, db_status_packet_t *out);

// Battery Level (0x2A19) and the status packet's battery_pct: the gauge
// percent, 0 while the reading is invalid.
uint8_t ble_codec_battery_pct(const app_battery_t *b);

// True if b differs from a in anything a client should hear about at once.
// battery_mv is left out: it jitters by a few mV and goes out with the 1 s
// heartbeat anyway.
bool ble_codec_status_changed(const db_status_packet_t *a, const db_status_packet_t *b);

// The link policy above. Times are app_now_ms() stamps; the differences are
// signed, so a stamp taken just after now_ms (by another task) is not a huge
// age.
bool ble_codec_link_holds_awake(bool trusted, uint32_t last_write_ms, uint32_t now_ms);
bool ble_codec_link_idle_unauth(bool trusted, bool pairing, bool any_sub, uint32_t since_ms,
                                uint32_t now_ms);

// Parses a command characteristic write. Returns 0 and fills *id / *arg, or
// the ATT error to answer with (BLE_CODEC_ATT_INVALID_LEN or DB_ATT_ERR_*).
uint8_t ble_codec_parse_cmd(const uint8_t *data, size_t len, app_cmd_id_t *id, int32_t *arg);

// ATT error for a settings_apply_json() result (0 for OK).
uint8_t ble_codec_settings_att_err(settings_json_result_t r);

// Parses a rec_ctrl write into *cmd. Returns 0, or the ATT error to answer
// with: BLE_CODEC_ATT_INVALID_LEN for a wrong length, DB_ATT_ERR_UNKNOWN_CMD,
// DB_ATT_ERR_BAD_ARG for a MARK text with control characters or broken
// UTF-8. The TAKE_START ranges are checked by the recorder (rec_fsm_step(),
// its result through ble_codec_rec_att_err()).
uint8_t ble_codec_parse_rec(const uint8_t *data, size_t len, rec_cmd_t *cmd);

// ATT error for a recorder command result (0 for REC_OK).
uint8_t ble_codec_rec_att_err(rec_err_t err);

// The rec_state packet. Address, port, SSID, passphrase and token only while
// the session can be joined (WAIT_CLIENT .. DRAINING), zero otherwise.
void ble_codec_rec_state(const app_rec_status_t *r, db_rec_state_packet_t *out);

// True if b differs from a in anything a client should hear about at once.
// The counters that move during a take (elapsed_s, sent_kib, dropped,
// ring_pct) are left out; they go out with the next notification or read.
bool ble_codec_rec_state_changed(const db_rec_state_packet_t *a,
                                 const db_rec_state_packet_t *b);

#ifdef __cplusplus
}
#endif
