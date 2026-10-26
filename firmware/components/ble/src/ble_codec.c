#include <math.h>
#include <string.h>
#include "ble_codec.h"

_Static_assert(sizeof(db_angle_packet_t) == 14, "angle packet is 14 bytes on the wire");
// Also means no padding, which ble_codec_status_changed() relies on.
_Static_assert(sizeof(db_status_packet_t) == 10, "status packet is 10 bytes on the wire");
_Static_assert(sizeof(db_rec_state_packet_t) == 86, "rec_state packet is 86 bytes on the wire");

// The BLE contract mirrors the recorder's values.
_Static_assert(DB_REC_OP_OPEN == REC_CMD_OPEN && DB_REC_OP_CLOSE == REC_CMD_CLOSE &&
               DB_REC_OP_TAKE_START == REC_CMD_TAKE_START && DB_REC_OP_STOP == REC_CMD_STOP &&
               DB_REC_OP_MARK == REC_CMD_MARK, "rec_ctrl ops");
_Static_assert(DB_REC_STATE_IDLE == REC_STATE_IDLE &&
               DB_REC_STATE_AP_STARTING == REC_STATE_AP_STARTING &&
               DB_REC_STATE_WAIT_CLIENT == REC_STATE_WAIT_CLIENT &&
               DB_REC_STATE_READY == REC_STATE_READY &&
               DB_REC_STATE_RECORDING == REC_STATE_RECORDING &&
               DB_REC_STATE_DRAINING == REC_STATE_DRAINING &&
               DB_REC_STATE_CLOSING == REC_STATE_CLOSING && REC_STATE_COUNT == 7,
               "rec states");
_Static_assert(DB_REC_REASON_NONE == REC_REASON_NONE && DB_REC_REASON_USER == REC_REASON_USER &&
               DB_REC_REASON_MAX_S == REC_REASON_MAX_S &&
               DB_REC_REASON_CLIENT_LOST == REC_REASON_CLIENT_LOST &&
               DB_REC_REASON_BLE_LOST == REC_REASON_BLE_LOST &&
               DB_REC_REASON_BATTERY == REC_REASON_BATTERY && DB_REC_REASON_IMU == REC_REASON_IMU &&
               DB_REC_REASON_SHUTDOWN == REC_REASON_SHUTDOWN &&
               DB_REC_REASON_AP_FAIL == REC_REASON_AP_FAIL &&
               DB_REC_REASON_NO_CLIENT == REC_REASON_NO_CLIENT &&
               DB_REC_REASON_IDLE == REC_REASON_IDLE, "rec reasons");
_Static_assert(DB_REC_PROFILE_LEVEL == REC_PROFILE_LEVEL &&
               DB_REC_PROFILE_RAW448 == REC_PROFILE_RAW448 &&
               DB_REC_PROFILE_RAW448_16G == REC_PROFILE_RAW448_16G &&
               DB_REC_PROFILE_RAW897 == REC_PROFILE_RAW897, "rec profiles");
_Static_assert(DB_REC_MIC_1 == REC_MIC_1 && DB_REC_MIC_2 == REC_MIC_2, "rec microphones");
_Static_assert(DB_REC_FLAG_NO_AP == APP_REC_FLAG_NO_AP && DB_REC_FLAG_CLIENT == APP_REC_FLAG_CLIENT &&
               DB_REC_FLAG_AUDIO == APP_REC_FLAG_AUDIO &&
               DB_REC_FLAG_IMU_LOST == APP_REC_FLAG_IMU_LOST &&
               DB_REC_FLAG_BATTERY_LOW == APP_REC_FLAG_BATTERY_LOW, "rec flags");
_Static_assert(DB_REC_MARK_TEXT_MAX == REC_MARK_TEXT_MAX, "MARK text");
_Static_assert(DB_REC_MAX_S_DEFAULT == REC_TAKE_DEFAULT_S && DB_REC_MAX_S_LIMIT == REC_TAKE_MAX_S &&
               DB_REC_GAIN_MAX_DB == REC_GAIN_MAX_DB, "take limits");
_Static_assert(sizeof(((app_rec_status_t *)0)->ssid) ==
                   sizeof(((db_rec_state_packet_t *)0)->ssid) + 1 &&
               sizeof(((app_rec_status_t *)0)->pass) ==
                   sizeof(((db_rec_state_packet_t *)0)->pass) + 1 &&
               sizeof(((app_rec_status_t *)0)->token) ==
                   sizeof(((db_rec_state_packet_t *)0)->token) &&
               sizeof(((db_rec_state_packet_t *)0)->token) == REC_HELLO_TOKEN_LEN,
               "rec_state strings");

#define PACKET_VERSION 1u

int16_t ble_codec_cdeg(float deg)
{
    if (isnan(deg)) {
        return 0;
    }
    // Clamp in float first: converting an out-of-range float to an integer
    // is undefined behaviour.
    float c = roundf(deg * 100.0f);
    if (c >= 32767.0f) {
        return INT16_MAX;
    }
    if (c <= -32768.0f) {
        return INT16_MIN;
    }
    return (int16_t)c;
}

void ble_codec_angle(const app_snapshot_t *s, uint8_t seq, uint32_t now_ms,
                     db_angle_packet_t *out)
{
    const level_reading_t *r = &s->reading;
    // Signed age: a reading published just after now_ms was taken counts as
    // fresh, and 32-bit wrap is harmless.
    int32_t age_ms = (int32_t)(now_ms - s->reading_ms);
    bool fresh = age_ms <= (int32_t)BLE_CODEC_READING_MAX_AGE_MS;

    // Without a running IMU and a fresh reading, the reading says nothing
    // (the no-IMU reading is all zero, and status 0 is ON_TARGET), so none
    // of its flags go out; a client must not show green for it.
    bool valid = s->imu_ok && fresh;

    uint8_t flags = 0;
    if (valid) {
        flags |= DB_ANGLE_FLAG_IMU_OK;
        if (r->status == LEVEL_STATUS_ON_TARGET) {
            flags |= DB_ANGLE_FLAG_ON_TARGET;
        } else if (r->status == LEVEL_STATUS_NEAR) {
            flags |= DB_ANGLE_FLAG_NEAR;
        }
        if (r->yaw_valid) {
            flags |= DB_ANGLE_FLAG_YAW_VALID;
        }
        if (r->yaw_stale) {
            flags |= DB_ANGLE_FLAG_YAW_STALE;
        }
        if (s->still) {
            flags |= DB_ANGLE_FLAG_STILL;
        }
    }
    if (s->zero_set) {
        flags |= DB_ANGLE_FLAG_ZERO_SET;
    }

    *out = (db_angle_packet_t){
        .version = PACKET_VERSION,
        .mode = (uint8_t)r->mode,
        .flags = flags,
        .seq = seq,
        .elevation_cdeg = ble_codec_cdeg(r->elevation_deg),
        .yaw_cdeg = ble_codec_cdeg(r->yaw_deg),
        .bubble_x_cdeg = ble_codec_cdeg(r->bubble_x_deg),
        .bubble_y_cdeg = ble_codec_cdeg(r->bubble_y_deg),
        .deviation_cdeg = ble_codec_cdeg(r->deviation_deg),
    };
}

void ble_codec_level(const app_snapshot_t *s, uint32_t now_ms, rec_level_t *out)
{
    db_angle_packet_t a;
    ble_codec_angle(s, 0, now_ms, &a);
    *out = (rec_level_t){
        .mode = a.mode,
        .flags = a.flags,
        .elevation_cdeg = a.elevation_cdeg,
        .yaw_cdeg = a.yaw_cdeg,
        .bubble_x_cdeg = a.bubble_x_cdeg,
        .bubble_y_cdeg = a.bubble_y_cdeg,
        .deviation_cdeg = a.deviation_cdeg,
    };
    if (s->zero_set) {
        out->zero_elevation_cdeg = ble_codec_cdeg(s->zero_deg[0]);
        out->zero_bubble_x_cdeg = ble_codec_cdeg(s->zero_deg[1]);
        out->zero_bubble_y_cdeg = ble_codec_cdeg(s->zero_deg[2]);
    }
}

uint8_t ble_codec_battery_pct(const app_battery_t *b)
{
    // Zero, not a leftover value, when the gauge reading is invalid.
    return b->valid ? b->percent : 0;
}

void ble_codec_status(const app_snapshot_t *s, db_status_packet_t *out)
{
    const app_battery_t *b = &s->battery;
    uint8_t flags = 0;
    if (b->valid) {
        flags |= DB_STATUS_FLAG_BATTERY_VALID;
    }
    if (b->charging) {
        flags |= DB_STATUS_FLAG_CHARGING;
    }
    if (b->charge_done) {
        flags |= DB_STATUS_FLAG_CHARGE_DONE;
    }
    if (b->vbus_present) {
        flags |= DB_STATUS_FLAG_VBUS;
    }
    if (s->mount_cal_valid) {
        flags |= DB_STATUS_FLAG_MOUNT_CAL;
    }
    if (s->accel_cal_valid) {
        flags |= DB_STATUS_FLAG_ACCEL_CAL;
    }
    if (s->cal.kind != APP_CAL_IDLE) {
        flags |= DB_STATUS_FLAG_CALIBRATING;
    }
    if (s->rec.state != REC_STATE_IDLE) {
        flags |= DB_STATUS_FLAG_RECORDING;
    }

    *out = (db_status_packet_t){
        .version = PACKET_VERSION,
        .battery_pct = ble_codec_battery_pct(b),
        .battery_mv = b->valid ? b->voltage_mv : 0,
        .flags = flags,
        .power_state = (uint8_t)s->power,
        .cal_kind = (uint8_t)s->cal.kind,
        .cal_samples = s->cal.samples > 255 ? 255 : (uint8_t)s->cal.samples,
        .cal_coverage = s->cal.accel_coverage,
        .cal_last_result = s->cal.last_result,
    };
}

bool ble_codec_status_changed(const db_status_packet_t *a, const db_status_packet_t *b)
{
    // Packed, so a byte compare covers every field, including new ones.
    db_status_packet_t x = *a, y = *b;
    x.battery_mv = 0;
    y.battery_mv = 0;
    return memcmp(&x, &y, sizeof(x)) != 0;
}

bool ble_codec_link_holds_awake(bool trusted, uint32_t last_write_ms, uint32_t now_ms)
{
    return trusted && (int32_t)(now_ms - last_write_ms) < (int32_t)BLE_CODEC_AWAKE_AFTER_WRITE_MS;
}

bool ble_codec_link_idle_unauth(bool trusted, bool pairing, bool any_sub, uint32_t since_ms,
                                uint32_t now_ms)
{
    return !trusted && !pairing && !any_sub &&
           (int32_t)(now_ms - since_ms) >= (int32_t)BLE_CODEC_IDLE_UNAUTH_MS;
}

uint8_t ble_codec_parse_cmd(const uint8_t *data, size_t len, app_cmd_id_t *id, int32_t *arg)
{
    if (len == 0) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }

    app_cmd_id_t cmd;
    int32_t value = 0;
    size_t want_len = 1;

    switch (data[0]) {
    case DB_CMD_ZERO:             cmd = APP_CMD_ZERO; break;
    case DB_CMD_CLEAR_ZERO:       cmd = APP_CMD_CLEAR_ZERO; break;
    // Marked as BLE-started: cancelled if the link drops (control.c).
    case DB_CMD_MOUNT_CAL_START:  cmd = APP_CMD_MOUNT_CAL_START; value = APP_CMD_ARG_FROM_BLE; break;
    case DB_CMD_MOUNT_CAL_FINISH: cmd = APP_CMD_MOUNT_CAL_FINISH; break;
    case DB_CMD_ACCEL_CAL_START:  cmd = APP_CMD_ACCEL_CAL_START; value = APP_CMD_ARG_FROM_BLE; break;
    case DB_CMD_ACCEL_CAL_FINISH: cmd = APP_CMD_ACCEL_CAL_FINISH; break;
    case DB_CMD_CAL_CANCEL:       cmd = APP_CMD_CAL_CANCEL; break;
    case DB_CMD_SLEEP:            cmd = APP_CMD_SLEEP; break;
    case DB_CMD_POWER_OFF:        cmd = APP_CMD_POWER_OFF; break;
    case DB_CMD_REBOOT:           cmd = APP_CMD_REBOOT; break;
    case DB_CMD_SET_MODE:
        cmd = APP_CMD_SET_MODE;
        want_len = 2;
        break;
    case DB_CMD_FACTORY_RESET:
        cmd = APP_CMD_FACTORY_RESET;
        want_len = 2;
        break;
    default:
        return DB_ATT_ERR_UNKNOWN_CMD;
    }

    if (len != want_len) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    if (cmd == APP_CMD_SET_MODE) {
        if (data[1] >= LEVEL_MODE_COUNT) {
            return DB_ATT_ERR_BAD_ARG;
        }
        value = data[1];
    } else if (cmd == APP_CMD_FACTORY_RESET && data[1] != DB_CMD_FACTORY_RESET_CONFIRM) {
        return DB_ATT_ERR_BAD_ARG;
    }

    *id = cmd;
    *arg = value;
    return 0;
}

uint8_t ble_codec_cmd_excl_err(app_cmd_id_t id, uint8_t excl)
{
    bool cal_start = id == APP_CMD_MOUNT_CAL_START || id == APP_CMD_ACCEL_CAL_START;
    return cal_start && app_excl_blocks_cal(excl) ? DB_ATT_ERR_BUSY : 0;
}

uint8_t ble_codec_settings_att_err(settings_json_result_t r)
{
    switch (r) {
    case SETTINGS_JSON_OK:          return 0;
    case SETTINGS_JSON_PARSE_ERROR: return DB_ATT_ERR_JSON_PARSE;
    case SETTINGS_JSON_TYPE_ERROR:  return DB_ATT_ERR_JSON_TYPE;
    case SETTINGS_JSON_RANGE_ERROR: return DB_ATT_ERR_JSON_RANGE;
    }
    return DB_ATT_ERR_JSON_PARSE;
}

// Printable UTF-8: well-formed (RFC 3629: no overlong forms, surrogates or
// code points above U+10FFFF) and no C0/C1 controls or DEL; the text ends up
// in the dataset's CSV files and is decoded strictly there.
static bool mark_text_ok(const uint8_t *p, size_t n)
{
    size_t i = 0;
    while (i < n) {
        uint8_t c = p[i++];
        size_t more;
        uint8_t lo = 0x80, hi = 0xBF;   // range of the second byte
        if (c < 0x80) {
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
            continue;
        } else if (c == 0xC2) {
            more = 1;
            lo = 0xA0;                  // C2 80..9F: the C1 controls
        } else if (c >= 0xC3 && c <= 0xDF) {
            more = 1;
        } else if ((c & 0xF0) == 0xE0) {
            more = 2;
            lo = c == 0xE0 ? 0xA0 : 0x80;
            hi = c == 0xED ? 0x9F : 0xBF;
        } else if (c >= 0xF0 && c <= 0xF4) {
            more = 3;
            lo = c == 0xF0 ? 0x90 : 0x80;
            hi = c == 0xF4 ? 0x8F : 0xBF;
        } else {
            return false;       // a stray continuation byte, C0/C1, or above U+10FFFF
        }
        if (n - i < more || p[i] < lo || p[i] > hi) {
            return false;
        }
        for (i++, more--; more > 0; more--) {
            if ((p[i++] & 0xC0) != 0x80) {
                return false;
            }
        }
    }
    return true;
}

static uint32_t le32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

uint8_t ble_codec_parse_rec(const uint8_t *data, size_t len, rec_cmd_t *cmd)
{
    if (len == 0) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    rec_cmd_t c = {0};
    switch (data[0]) {
    case DB_REC_OP_OPEN:
    case DB_REC_OP_CLOSE:
    case DB_REC_OP_STOP:
        if (len != 1) {
            return BLE_CODEC_ATT_INVALID_LEN;
        }
        c.op = (rec_cmd_op_t)data[0];
        break;
    case DB_REC_OP_TAKE_START:
        if (len != DB_REC_TAKE_START_LEN) {
            return BLE_CODEC_ATT_INVALID_LEN;
        }
        c.op = REC_CMD_TAKE_START;
        c.profile = data[1];
        c.mic_mask = data[2];
        c.gain_db = data[3];
        c.max_s = (uint16_t)(data[4] | (data[5] << 8));
        c.take_id = le32(&data[6]);
        break;
    case DB_REC_OP_MARK:
        if (len < 3 || len > 3 + DB_REC_MARK_TEXT_MAX) {
            return BLE_CODEC_ATT_INVALID_LEN;
        }
        if (!mark_text_ok(&data[3], len - 3)) {
            return DB_ATT_ERR_BAD_ARG;
        }
        c.op = REC_CMD_MARK;
        c.mark_code = (uint16_t)(data[1] | (data[2] << 8));
        memcpy(c.text, &data[3], len - 3);
        break;
    default:
        return DB_ATT_ERR_UNKNOWN_CMD;
    }
    *cmd = c;
    return 0;
}

uint8_t ble_codec_rec_att_err(rec_err_t err)
{
    switch (err) {
    case REC_OK:          return 0;
    case REC_ERR_STATE:   return DB_ATT_ERR_REC_STATE;
    case REC_ERR_BAD_ARG: return DB_ATT_ERR_BAD_ARG;
    case REC_ERR_BATTERY: return DB_ATT_ERR_REC_BATTERY;
    case REC_ERR_RADIO:   return DB_ATT_ERR_REC_RADIO;
    }
    return DB_ATT_ERR_REC_STATE;
}

// Copies a NUL-terminated string into a NUL-padded field of n bytes.
static void put_str(char *dst, const char *src, size_t n)
{
    memcpy(dst, src, strnlen(src, n));
}

void ble_codec_rec_state(const app_rec_status_t *r, db_rec_state_packet_t *out)
{
    uint64_t kib = r->sent_bytes >> 10;
    *out = (db_rec_state_packet_t){
        .version = PACKET_VERSION,
        .state = r->state,
        .reason = r->reason,
        .flags = r->flags,
        .take_id = r->take_id,
        .elapsed_s = r->elapsed_s,
        .max_s = r->max_s,
        .sent_kib = kib > UINT32_MAX ? UINT32_MAX : (uint32_t)kib,
        .dropped = r->dropped,
        .ring_pct = r->ring_pct,
        .profile = r->profile,
        .channel = r->channel,
    };
    // The credentials only while they lead somewhere.
    if (r->state >= REC_STATE_WAIT_CLIENT && r->state <= REC_STATE_DRAINING) {
        out->port = r->port;
        memcpy(out->ip, r->ip, sizeof(out->ip));
        put_str(out->ssid, r->ssid, sizeof(out->ssid));
        put_str(out->pass, r->pass, sizeof(out->pass));
        memcpy(out->token, r->token, sizeof(out->token));
    }
}

bool ble_codec_rec_state_changed(const db_rec_state_packet_t *a,
                                 const db_rec_state_packet_t *b)
{
    db_rec_state_packet_t x = *a, y = *b;
    x.elapsed_s = y.elapsed_s = 0;
    x.sent_kib = y.sent_kib = 0;
    x.dropped = y.dropped = 0;
    x.ring_pct = y.ring_pct = 0;
    return memcmp(&x, &y, sizeof(x)) != 0;
}
