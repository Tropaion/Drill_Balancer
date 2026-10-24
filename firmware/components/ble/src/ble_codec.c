#include <math.h>
#include <string.h>
#include "ble_codec.h"

_Static_assert(sizeof(db_angle_packet_t) == 14, "angle packet is 14 bytes on the wire");
// Also means no padding, which ble_codec_status_changed() relies on.
_Static_assert(sizeof(db_status_packet_t) == 10, "status packet is 10 bytes on the wire");

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
