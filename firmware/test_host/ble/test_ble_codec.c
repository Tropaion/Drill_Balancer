#include <math.h>
#include <stddef.h>
#include <string.h>
#include "unity.h"
#include "ble_codec.h"

void setUp(void) {}
void tearDown(void) {}

// The Android app and tools/ble/db_client.py decode these exact layouts.
_Static_assert(sizeof(db_angle_packet_t) == 14, "angle packet is 14 bytes on the wire");
_Static_assert(sizeof(db_status_packet_t) == 10, "status packet is 10 bytes on the wire");
_Static_assert(offsetof(db_angle_packet_t, elevation_cdeg) == 4, "angle layout");
_Static_assert(offsetof(db_angle_packet_t, deviation_cdeg) == 12, "angle layout");
_Static_assert(offsetof(db_status_packet_t, battery_mv) == 2, "status layout");
_Static_assert(offsetof(db_status_packet_t, cal_last_result) == 9, "status layout");

static app_snapshot_t snap_default(void)
{
    app_snapshot_t s;
    memset(&s, 0, sizeof(s));
    s.imu_ok = true;
    s.q = quat_identity();
    s.reading_ms = 1000;
    s.reading.mode = LEVEL_MODE_WALL;
    s.reading.status = LEVEL_STATUS_OFF;
    s.cal.kind = APP_CAL_IDLE;
    s.cal.last_result = -1;
    return s;
}

// ---- centi-degrees ---------------------------------------------------------------

static void test_cdeg_rounds_to_nearest(void)
{
    TEST_ASSERT_EQUAL_INT16(0, ble_codec_cdeg(0.0f));
    TEST_ASSERT_EQUAL_INT16(1234, ble_codec_cdeg(12.34f));
    TEST_ASSERT_EQUAL_INT16(-1234, ble_codec_cdeg(-12.34f));
    TEST_ASSERT_EQUAL_INT16(1, ble_codec_cdeg(0.006f));
    TEST_ASSERT_EQUAL_INT16(-1, ble_codec_cdeg(-0.006f));
    TEST_ASSERT_EQUAL_INT16(0, ble_codec_cdeg(0.004f));
    TEST_ASSERT_EQUAL_INT16(18000, ble_codec_cdeg(180.0f));
    TEST_ASSERT_EQUAL_INT16(-18000, ble_codec_cdeg(-180.0f));
}

static void test_cdeg_saturates_at_int16_limits(void)
{
    TEST_ASSERT_EQUAL_INT16(32767, ble_codec_cdeg(327.67f));
    TEST_ASSERT_EQUAL_INT16(32767, ble_codec_cdeg(327.68f));
    TEST_ASSERT_EQUAL_INT16(32767, ble_codec_cdeg(1000.0f));
    TEST_ASSERT_EQUAL_INT16(32767, ble_codec_cdeg(1e30f));
    TEST_ASSERT_EQUAL_INT16(32767, ble_codec_cdeg(INFINITY));
    TEST_ASSERT_EQUAL_INT16(-32768, ble_codec_cdeg(-327.68f));
    TEST_ASSERT_EQUAL_INT16(-32768, ble_codec_cdeg(-327.69f));
    TEST_ASSERT_EQUAL_INT16(-32768, ble_codec_cdeg(-1e30f));
    TEST_ASSERT_EQUAL_INT16(-32768, ble_codec_cdeg(-INFINITY));
}

static void test_cdeg_nan_is_zero(void)
{
    TEST_ASSERT_EQUAL_INT16(0, ble_codec_cdeg(NAN));
    TEST_ASSERT_EQUAL_INT16(0, ble_codec_cdeg(-NAN));
}

// ---- angle packet ------------------------------------------------------------------

static void test_angle_packet_fields(void)
{
    app_snapshot_t s = snap_default();
    s.reading.mode = LEVEL_MODE_ANGLE;
    s.reading.elevation_deg = 45.5f;
    s.reading.yaw_deg = -3.21f;
    s.reading.bubble_x_deg = 1.0f;
    s.reading.bubble_y_deg = -2.0f;
    s.reading.deviation_deg = 0.5f;

    db_angle_packet_t p;
    ble_codec_angle(&s, 42, 1100, &p);
    TEST_ASSERT_EQUAL_UINT8(1, p.version);
    TEST_ASSERT_EQUAL_UINT8(LEVEL_MODE_ANGLE, p.mode);
    TEST_ASSERT_EQUAL_UINT8(42, p.seq);
    TEST_ASSERT_EQUAL_INT16(4550, p.elevation_cdeg);
    TEST_ASSERT_EQUAL_INT16(-321, p.yaw_cdeg);
    TEST_ASSERT_EQUAL_INT16(100, p.bubble_x_cdeg);
    TEST_ASSERT_EQUAL_INT16(-200, p.bubble_y_cdeg);
    TEST_ASSERT_EQUAL_INT16(50, p.deviation_cdeg);
}

static void test_angle_packet_little_endian_bytes(void)
{
    app_snapshot_t s = snap_default();
    s.reading.elevation_deg = -1.0f;   // -100 = 0xFF9C
    s.reading.deviation_deg = 2.56f;   // 256 = 0x0100
    db_angle_packet_t p;
    ble_codec_angle(&s, 0, 1000, &p);
    const uint8_t *b = (const uint8_t *)&p;
    TEST_ASSERT_EQUAL_HEX8(0x9C, b[4]);
    TEST_ASSERT_EQUAL_HEX8(0xFF, b[5]);
    TEST_ASSERT_EQUAL_HEX8(0x00, b[12]);
    TEST_ASSERT_EQUAL_HEX8(0x01, b[13]);
}

static void test_angle_flags_status(void)
{
    app_snapshot_t s = snap_default();
    db_angle_packet_t p;

    s.reading.status = LEVEL_STATUS_ON_TARGET;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_ON_TARGET);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_NEAR);

    s.reading.status = LEVEL_STATUS_NEAR;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_ON_TARGET);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_NEAR);

    s.reading.status = LEVEL_STATUS_OFF;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_FALSE(p.flags & (DB_ANGLE_FLAG_ON_TARGET | DB_ANGLE_FLAG_NEAR));
}

static void test_angle_flags_yaw_still_imu(void)
{
    app_snapshot_t s = snap_default();
    db_angle_packet_t p;

    s.still = false;
    s.reading.yaw_valid = false;
    s.reading.yaw_stale = false;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_EQUAL_HEX8(DB_ANGLE_FLAG_IMU_OK, p.flags);

    s.still = true;
    s.reading.yaw_valid = true;
    s.reading.yaw_stale = true;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_STILL);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_YAW_VALID);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_YAW_STALE);

    s.imu_ok = false;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_IMU_OK);
}

static void test_angle_zero_flag_follows_snapshot(void)
{
    app_snapshot_t s = snap_default();
    db_angle_packet_t p;

    // A zero taken with the bit vertical: no yaw, but the zero is set.
    s.zero_set = true;
    s.reading.yaw_valid = false;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_ZERO_SET);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_YAW_VALID);

    s.zero_set = false;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_ZERO_SET);
}

static void test_angle_stale_reading_clears_imu_ok(void)
{
    app_snapshot_t s = snap_default();
    s.reading_ms = 1000;
    db_angle_packet_t p;

    ble_codec_angle(&s, 0, 1000 + BLE_CODEC_READING_MAX_AGE_MS, &p);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_IMU_OK);
    ble_codec_angle(&s, 0, 1000 + BLE_CODEC_READING_MAX_AGE_MS + 1, &p);
    TEST_ASSERT_FALSE(p.flags & DB_ANGLE_FLAG_IMU_OK);
}

static void test_angle_age_survives_clock_wrap(void)
{
    app_snapshot_t s = snap_default();
    s.reading_ms = 0xFFFFFF00u;
    db_angle_packet_t p;
    ble_codec_angle(&s, 0, 0x00000010u, &p);   // 0x110 = 272 ms later
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_IMU_OK);
}

static void test_angle_reading_slightly_in_future_is_fresh(void)
{
    // The sensor task may publish between our app_now_ms() and app_state_get().
    app_snapshot_t s = snap_default();
    s.reading_ms = 1005;
    db_angle_packet_t p;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_TRUE(p.flags & DB_ANGLE_FLAG_IMU_OK);
}

static void test_angle_no_imu_reading_is_not_on_target(void)
{
    // What the sensor task publishes while the IMU is missing: a zeroed
    // reading with a fresh timestamp.
    app_snapshot_t s;
    memset(&s, 0, sizeof(s));
    s.reading_ms = 1000;
    s.zero_set = true;
    db_angle_packet_t p;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_EQUAL_HEX8(DB_ANGLE_FLAG_ZERO_SET, p.flags);
}

static void test_angle_stale_reading_drops_reading_flags(void)
{
    app_snapshot_t s = snap_default();
    s.reading.status = LEVEL_STATUS_ON_TARGET;
    s.reading.yaw_valid = true;
    s.reading.yaw_stale = true;
    s.still = true;
    db_angle_packet_t p;

    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_EQUAL_HEX8(DB_ANGLE_FLAG_IMU_OK | DB_ANGLE_FLAG_ON_TARGET |
                           DB_ANGLE_FLAG_YAW_VALID | DB_ANGLE_FLAG_YAW_STALE |
                           DB_ANGLE_FLAG_STILL, p.flags);

    ble_codec_angle(&s, 0, 1000 + BLE_CODEC_READING_MAX_AGE_MS + 1, &p);
    TEST_ASSERT_EQUAL_HEX8(0, p.flags);

    s.reading.status = LEVEL_STATUS_NEAR;
    s.imu_ok = false;
    ble_codec_angle(&s, 0, 1000, &p);
    TEST_ASSERT_EQUAL_HEX8(0, p.flags);
}

// ---- status packet -----------------------------------------------------------------

static void test_status_packet_fields(void)
{
    app_snapshot_t s = snap_default();
    s.battery = (app_battery_t){.valid = true, .percent = 87, .voltage_mv = 4012,
                                .charging = true, .charge_done = false, .vbus_present = true};
    s.power = APP_POWER_DIM;
    s.cal = (app_cal_status_t){.kind = APP_CAL_ACCEL, .samples = 5, .accel_coverage = 0x2B,
                               .last_result = 2, .last_kind = APP_CAL_MOUNT};

    s.mount_cal_valid = true;

    db_status_packet_t p;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(1, p.version);
    TEST_ASSERT_EQUAL_UINT8(87, p.battery_pct);
    TEST_ASSERT_EQUAL_UINT16(4012, p.battery_mv);
    TEST_ASSERT_EQUAL_HEX8(DB_STATUS_FLAG_BATTERY_VALID | DB_STATUS_FLAG_CHARGING |
                               DB_STATUS_FLAG_VBUS | DB_STATUS_FLAG_MOUNT_CAL |
                               DB_STATUS_FLAG_CALIBRATING,
                           p.flags);
    TEST_ASSERT_EQUAL_UINT8(APP_POWER_DIM, p.power_state);
    TEST_ASSERT_EQUAL_UINT8(APP_CAL_ACCEL, p.cal_kind);
    TEST_ASSERT_EQUAL_UINT8(5, p.cal_samples);
    TEST_ASSERT_EQUAL_HEX8(0x2B, p.cal_coverage);
    TEST_ASSERT_EQUAL_INT8(2, p.cal_last_result);
}

static void test_status_flags_cal_valid_and_charge_done(void)
{
    app_snapshot_t s = snap_default();
    s.battery.valid = false;
    s.battery.charge_done = true;
    s.accel_cal_valid = true;
    db_status_packet_t p;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_HEX8(DB_STATUS_FLAG_CHARGE_DONE | DB_STATUS_FLAG_ACCEL_CAL, p.flags);
    TEST_ASSERT_EQUAL_INT8(-1, p.cal_last_result);
    TEST_ASSERT_EQUAL_UINT8(APP_CAL_IDLE, p.cal_kind);
}

static void test_status_invalid_battery_reports_zero(void)
{
    // A stale percent from an invalid gauge reading must not look real.
    app_snapshot_t s = snap_default();
    s.battery.valid = false;
    s.battery.percent = 55;
    s.battery.voltage_mv = 3900;
    db_status_packet_t p;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(0, p.battery_pct);
    TEST_ASSERT_EQUAL_UINT16(0, p.battery_mv);
}

static void test_battery_pct_follows_validity(void)
{
    // One value for the status packet, the 0x2A19 read and its notify. The
    // 0..100 range is pmu_decode_status()'s job (test_pmu_events).
    app_battery_t b = {.valid = true, .percent = 100};
    TEST_ASSERT_EQUAL_UINT8(100, ble_codec_battery_pct(&b));
    b.percent = 0;
    TEST_ASSERT_EQUAL_UINT8(0, ble_codec_battery_pct(&b));
    b = (app_battery_t){.valid = false, .percent = 42};
    TEST_ASSERT_EQUAL_UINT8(0, ble_codec_battery_pct(&b));

    app_snapshot_t s = snap_default();
    s.battery = (app_battery_t){.valid = true, .percent = 63};
    db_status_packet_t p;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(ble_codec_battery_pct(&s.battery), p.battery_pct);
}

static void test_status_samples_saturate(void)
{
    app_snapshot_t s = snap_default();
    db_status_packet_t p;

    s.cal.samples = 255;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(255, p.cal_samples);
    s.cal.samples = 256;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(255, p.cal_samples);
    s.cal.samples = 60000;
    ble_codec_status(&s, &p);
    TEST_ASSERT_EQUAL_UINT8(255, p.cal_samples);
}

static void test_status_changed_ignores_only_millivolts(void)
{
    app_snapshot_t s = snap_default();
    s.battery.valid = true;
    s.battery.percent = 50;
    s.battery.voltage_mv = 3800;
    db_status_packet_t a, b;
    ble_codec_status(&s, &a);

    b = a;
    TEST_ASSERT_FALSE(ble_codec_status_changed(&a, &b));
    b.battery_mv = 3801;
    TEST_ASSERT_FALSE(ble_codec_status_changed(&a, &b));

    const size_t offs[] = {
        offsetof(db_status_packet_t, version),     offsetof(db_status_packet_t, battery_pct),
        offsetof(db_status_packet_t, flags),       offsetof(db_status_packet_t, power_state),
        offsetof(db_status_packet_t, cal_kind),    offsetof(db_status_packet_t, cal_samples),
        offsetof(db_status_packet_t, cal_coverage), offsetof(db_status_packet_t, cal_last_result),
    };
    for (size_t i = 0; i < sizeof(offs) / sizeof(offs[0]); i++) {
        b = a;
        ((uint8_t *)&b)[offs[i]] ^= 0x01;
        TEST_ASSERT_TRUE_MESSAGE(ble_codec_status_changed(&a, &b), "field change not detected");
    }
}

static void test_status_changed_sees_every_byte_but_millivolts(void)
{
    // Every byte outside battery_mv counts, so a field added to the packet
    // is covered without touching ble_codec_status_changed().
    db_status_packet_t a, b;
    memset(&a, 0, sizeof(a));
    for (size_t i = 0; i < sizeof(a); i++) {
        b = a;
        ((uint8_t *)&b)[i] = 0x80;
        bool mv = i >= offsetof(db_status_packet_t, battery_mv) &&
                  i < offsetof(db_status_packet_t, battery_mv) + sizeof(a.battery_mv);
        TEST_ASSERT_EQUAL(!mv, ble_codec_status_changed(&a, &b));
    }
}

// ---- link policy -------------------------------------------------------------------

static void test_link_holds_awake_only_trusted_and_recent(void)
{
    const uint32_t w = 5000;
    TEST_ASSERT_TRUE(ble_codec_link_holds_awake(true, w, w));
    TEST_ASSERT_TRUE(ble_codec_link_holds_awake(true, w, w + BLE_CODEC_AWAKE_AFTER_WRITE_MS - 1));
    TEST_ASSERT_FALSE(ble_codec_link_holds_awake(true, w, w + BLE_CODEC_AWAKE_AFTER_WRITE_MS));
    TEST_ASSERT_FALSE(ble_codec_link_holds_awake(false, w, w));
    // A write stamped by the host task just after now_ms was read.
    TEST_ASSERT_TRUE(ble_codec_link_holds_awake(true, w + 3, w));
    // Across the 32-bit wrap.
    TEST_ASSERT_TRUE(ble_codec_link_holds_awake(true, 0xFFFFFF00u, 0x100u));
    // On connect the last write is set this far back: not holding yet.
    TEST_ASSERT_FALSE(ble_codec_link_holds_awake(true, w - BLE_CODEC_AWAKE_AFTER_WRITE_MS, w));
}

static void test_link_idle_unauth(void)
{
    const uint32_t t = 1000;
    const uint32_t late = t + BLE_CODEC_IDLE_UNAUTH_MS;
    TEST_ASSERT_FALSE(ble_codec_link_idle_unauth(false, false, false, t, late - 1));
    TEST_ASSERT_TRUE(ble_codec_link_idle_unauth(false, false, false, t, late));
    // Any of these keeps the link.
    TEST_ASSERT_FALSE(ble_codec_link_idle_unauth(true, false, false, t, late));
    TEST_ASSERT_FALSE(ble_codec_link_idle_unauth(false, true, false, t, late));
    TEST_ASSERT_FALSE(ble_codec_link_idle_unauth(false, false, true, t, late));
    // A security change stamped just after now_ms is not a huge idle time.
    TEST_ASSERT_FALSE(ble_codec_link_idle_unauth(false, false, false, t + 5, t));
    TEST_ASSERT_TRUE(ble_codec_link_idle_unauth(false, false, false, 0xFFFFFF00u,
                                                0xFFFFFF00u + BLE_CODEC_IDLE_UNAUTH_MS));
}

// ---- commands ----------------------------------------------------------------------

static void expect_cmd(const uint8_t *data, size_t len, app_cmd_id_t want_id, int32_t want_arg)
{
    app_cmd_id_t id = (app_cmd_id_t)-1;
    int32_t arg = -999;
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_cmd(data, len, &id, &arg));
    TEST_ASSERT_EQUAL(want_id, id);
    TEST_ASSERT_EQUAL_INT32(want_arg, arg);
}

static uint8_t parse_err(const uint8_t *data, size_t len)
{
    app_cmd_id_t id;
    int32_t arg;
    return ble_codec_parse_cmd(data, len, &id, &arg);
}

static void test_cmd_simple_opcodes(void)
{
    const struct {
        uint8_t op;
        app_cmd_id_t id;
        int32_t arg;
    } map[] = {
        {DB_CMD_ZERO, APP_CMD_ZERO, 0},
        {DB_CMD_CLEAR_ZERO, APP_CMD_CLEAR_ZERO, 0},
        // Calibrations started over BLE are marked, so the control task can
        // cancel them when the link drops.
        {DB_CMD_MOUNT_CAL_START, APP_CMD_MOUNT_CAL_START, APP_CMD_ARG_FROM_BLE},
        {DB_CMD_MOUNT_CAL_FINISH, APP_CMD_MOUNT_CAL_FINISH, 0},
        {DB_CMD_ACCEL_CAL_START, APP_CMD_ACCEL_CAL_START, APP_CMD_ARG_FROM_BLE},
        {DB_CMD_ACCEL_CAL_FINISH, APP_CMD_ACCEL_CAL_FINISH, 0},
        {DB_CMD_CAL_CANCEL, APP_CMD_CAL_CANCEL, 0},
        {DB_CMD_SLEEP, APP_CMD_SLEEP, 0},
        {DB_CMD_POWER_OFF, APP_CMD_POWER_OFF, 0},
        {DB_CMD_REBOOT, APP_CMD_REBOOT, 0},
    };
    for (size_t i = 0; i < sizeof(map) / sizeof(map[0]); i++) {
        uint8_t b[2] = {map[i].op, 0};
        expect_cmd(b, 1, map[i].id, map[i].arg);
        // Extra bytes are rejected: a newer client's argument must not be
        // silently ignored.
        TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(b, 2));
    }
}

static void test_cmd_set_mode(void)
{
    for (uint8_t m = 0; m < LEVEL_MODE_COUNT; m++) {
        const uint8_t b[] = {DB_CMD_SET_MODE, m};
        expect_cmd(b, 2, APP_CMD_SET_MODE, m);
    }
    const uint8_t bad[] = {DB_CMD_SET_MODE, LEVEL_MODE_COUNT};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_BAD_ARG, parse_err(bad, 2));
    const uint8_t bad2[] = {DB_CMD_SET_MODE, 0xFF};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_BAD_ARG, parse_err(bad2, 2));
    const uint8_t no_arg[] = {DB_CMD_SET_MODE};
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(no_arg, 1));
    const uint8_t extra[] = {DB_CMD_SET_MODE, 0, 0};
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(extra, 3));
}

static void test_cmd_factory_reset_needs_confirmation(void)
{
    const uint8_t ok[] = {DB_CMD_FACTORY_RESET, 0xA5};
    expect_cmd(ok, 2, APP_CMD_FACTORY_RESET, 0);

    const uint8_t no_confirm[] = {DB_CMD_FACTORY_RESET};
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(no_confirm, 1));
    const uint8_t wrong[] = {DB_CMD_FACTORY_RESET, 0x5A};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_BAD_ARG, parse_err(wrong, 2));
    const uint8_t extra[] = {DB_CMD_FACTORY_RESET, 0xA5, 0x00};
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(extra, 3));
}

static void test_cmd_unknown_and_empty(void)
{
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, parse_err(NULL, 0));
    const uint8_t b0[] = {0x00};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_UNKNOWN_CMD, parse_err(b0, 1));
    const uint8_t b1[] = {0x24};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_UNKNOWN_CMD, parse_err(b1, 1));
    const uint8_t b2[] = {0xFF, 1, 2};
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_UNKNOWN_CMD, parse_err(b2, 3));
}

static void test_cmd_error_leaves_outputs_alone(void)
{
    app_cmd_id_t id = APP_CMD_REBOOT;
    int32_t arg = 77;
    const uint8_t bad[] = {DB_CMD_SET_MODE, 9};
    TEST_ASSERT_NOT_EQUAL(0, ble_codec_parse_cmd(bad, 2, &id, &arg));
    TEST_ASSERT_EQUAL(APP_CMD_REBOOT, id);
    TEST_ASSERT_EQUAL_INT32(77, arg);
}

// ---- settings errors ---------------------------------------------------------------

static void test_settings_att_errors(void)
{
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_settings_att_err(SETTINGS_JSON_OK));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_JSON_PARSE, ble_codec_settings_att_err(SETTINGS_JSON_PARSE_ERROR));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_JSON_TYPE, ble_codec_settings_att_err(SETTINGS_JSON_TYPE_ERROR));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_JSON_RANGE, ble_codec_settings_att_err(SETTINGS_JSON_RANGE_ERROR));
    // An unexpected value must not map to success.
    TEST_ASSERT_NOT_EQUAL(0, ble_codec_settings_att_err((settings_json_result_t)99));
}

static void test_att_codes_are_application_range(void)
{
    const uint8_t codes[] = {DB_ATT_ERR_JSON_PARSE, DB_ATT_ERR_JSON_TYPE, DB_ATT_ERR_JSON_RANGE,
                             DB_ATT_ERR_STORE,      DB_ATT_ERR_UNKNOWN_CMD, DB_ATT_ERR_BAD_ARG,
                             DB_ATT_ERR_BUSY,       DB_ATT_ERR_UNAVAILABLE};
    for (size_t i = 0; i < sizeof(codes); i++) {
        TEST_ASSERT_TRUE(codes[i] >= 0x80 && codes[i] <= 0x9F);
        for (size_t j = 0; j < i; j++) {
            TEST_ASSERT_NOT_EQUAL(codes[i], codes[j]);
        }
    }
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_cdeg_rounds_to_nearest);
    RUN_TEST(test_cdeg_saturates_at_int16_limits);
    RUN_TEST(test_cdeg_nan_is_zero);
    RUN_TEST(test_angle_packet_fields);
    RUN_TEST(test_angle_packet_little_endian_bytes);
    RUN_TEST(test_angle_flags_status);
    RUN_TEST(test_angle_flags_yaw_still_imu);
    RUN_TEST(test_angle_zero_flag_follows_snapshot);
    RUN_TEST(test_angle_stale_reading_clears_imu_ok);
    RUN_TEST(test_angle_age_survives_clock_wrap);
    RUN_TEST(test_angle_reading_slightly_in_future_is_fresh);
    RUN_TEST(test_angle_no_imu_reading_is_not_on_target);
    RUN_TEST(test_angle_stale_reading_drops_reading_flags);
    RUN_TEST(test_status_packet_fields);
    RUN_TEST(test_status_flags_cal_valid_and_charge_done);
    RUN_TEST(test_status_invalid_battery_reports_zero);
    RUN_TEST(test_battery_pct_follows_validity);
    RUN_TEST(test_status_samples_saturate);
    RUN_TEST(test_status_changed_ignores_only_millivolts);
    RUN_TEST(test_status_changed_sees_every_byte_but_millivolts);
    RUN_TEST(test_link_holds_awake_only_trusted_and_recent);
    RUN_TEST(test_link_idle_unauth);
    RUN_TEST(test_cmd_simple_opcodes);
    RUN_TEST(test_cmd_set_mode);
    RUN_TEST(test_cmd_factory_reset_needs_confirmation);
    RUN_TEST(test_cmd_unknown_and_empty);
    RUN_TEST(test_cmd_error_leaves_outputs_alone);
    RUN_TEST(test_settings_att_errors);
    RUN_TEST(test_att_codes_are_application_range);
    return UNITY_END();
}
