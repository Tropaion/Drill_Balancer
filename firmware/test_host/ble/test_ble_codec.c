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
_Static_assert(sizeof(db_rec_state_packet_t) == 86, "rec_state packet is 86 bytes on the wire");
_Static_assert(offsetof(db_rec_state_packet_t, take_id) == 4, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, sent_kib) == 12, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, ring_pct) == 20, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, port) == 24, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, ip) == 26, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, ssid) == 30, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, pass) == 46, "rec_state layout");
_Static_assert(offsetof(db_rec_state_packet_t, token) == 70, "rec_state layout");

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

// The recorder's LEVEL record carries the angle packet's fields and the zero
// of the same publish (the snapshot's).
static void test_level_record(void)
{
    app_snapshot_t s = snap_default();
    s.reading.mode = LEVEL_MODE_WALL;
    s.reading.status = LEVEL_STATUS_NEAR;
    s.reading.elevation_deg = -1.25f;
    s.reading.yaw_deg = 2.5f;
    s.reading.deviation_deg = 1.25f;
    s.zero_set = true;
    s.zero_deg[0] = 3.21f;
    s.zero_deg[1] = -0.5f;
    s.zero_deg[2] = 40000.0f;
    db_angle_packet_t a;
    ble_codec_angle(&s, 0, 1000, &a);
    rec_level_t l;
    memset(&l, 0xA5, sizeof(l));
    ble_codec_level(&s, 1000, &l);
    TEST_ASSERT_EQUAL_UINT64(0, l.host_us);   // the recorder sets it
    TEST_ASSERT_EQUAL_UINT8(a.mode, l.mode);
    TEST_ASSERT_EQUAL_UINT8(a.flags, l.flags);
    TEST_ASSERT_TRUE(l.flags & DB_ANGLE_FLAG_ZERO_SET);
    TEST_ASSERT_EQUAL_INT16(a.elevation_cdeg, l.elevation_cdeg);
    TEST_ASSERT_EQUAL_INT16(-125, l.elevation_cdeg);
    TEST_ASSERT_EQUAL_INT16(a.yaw_cdeg, l.yaw_cdeg);
    TEST_ASSERT_EQUAL_INT16(a.bubble_x_cdeg, l.bubble_x_cdeg);
    TEST_ASSERT_EQUAL_INT16(a.bubble_y_cdeg, l.bubble_y_cdeg);
    TEST_ASSERT_EQUAL_INT16(a.deviation_cdeg, l.deviation_cdeg);
    TEST_ASSERT_EQUAL_INT16(321, l.zero_elevation_cdeg);
    TEST_ASSERT_EQUAL_INT16(-50, l.zero_bubble_x_cdeg);
    TEST_ASSERT_EQUAL_INT16(INT16_MAX, l.zero_bubble_y_cdeg);
    // No zero: zero fields 0 and no ZERO_SET, whatever zero_deg holds.
    s.zero_set = false;
    ble_codec_level(&s, 1000, &l);
    TEST_ASSERT_FALSE(l.flags & DB_ANGLE_FLAG_ZERO_SET);
    TEST_ASSERT_EQUAL_INT16(0, l.zero_elevation_cdeg);
    TEST_ASSERT_EQUAL_INT16(0, l.zero_bubble_x_cdeg);
    TEST_ASSERT_EQUAL_INT16(0, l.zero_bubble_y_cdeg);
}

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

static void test_status_recording_flag(void)
{
    app_snapshot_t s = snap_default();
    db_status_packet_t p;
    ble_codec_status(&s, &p);
    TEST_ASSERT_FALSE(p.flags & DB_STATUS_FLAG_RECORDING);
    // Any open session, not only a running take.
    for (uint8_t st = REC_STATE_AP_STARTING; st < REC_STATE_COUNT; st++) {
        s.rec.state = st;
        ble_codec_status(&s, &p);
        TEST_ASSERT_EQUAL_HEX8(DB_STATUS_FLAG_RECORDING, p.flags);
    }
    TEST_ASSERT_EQUAL_HEX8(0x80, DB_STATUS_FLAG_RECORDING);
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

// A calibration start is refused at the write while an OTA update or a
// recording session has the device (app_excl_t); everything else, and a
// start while nobody or a calibration has it, goes to the queue.
static void test_cmd_cal_start_refused_while_the_device_is_taken(void)
{
    for (int id = APP_CMD_ZERO; id <= APP_CMD_FACTORY_RESET; id++) {
        bool start = id == APP_CMD_MOUNT_CAL_START || id == APP_CMD_ACCEL_CAL_START;
        for (uint8_t ex = APP_EXCL_NONE; ex <= APP_EXCL_REC; ex++) {
            bool refused = start && (ex == APP_EXCL_OTA || ex == APP_EXCL_REC);
            TEST_ASSERT_EQUAL_HEX8(refused ? DB_ATT_ERR_BUSY : 0,
                                   ble_codec_cmd_excl_err((app_cmd_id_t)id, ex));
        }
    }
}


// ---- recorder ------------------------------------------------------------------------

static void test_rec_simple_ops(void)
{
    static const struct {
        uint8_t op;
        rec_cmd_op_t want;
    } ops[] = {{DB_REC_OP_OPEN, REC_CMD_OPEN}, {DB_REC_OP_CLOSE, REC_CMD_CLOSE},
               {DB_REC_OP_STOP, REC_CMD_STOP}};
    for (size_t i = 0; i < 3; i++) {
        rec_cmd_t c;
        memset(&c, 0x55, sizeof(c));
        TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(&ops[i].op, 1, &c));
        TEST_ASSERT_EQUAL(ops[i].want, c.op);
        TEST_ASSERT_EQUAL_UINT32(0, c.take_id);
        TEST_ASSERT_EQUAL_STRING("", c.text);
        const uint8_t two[2] = {ops[i].op, 0};
        TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, ble_codec_parse_rec(two, 2, &c));
    }
}

static void test_rec_take_start(void)
{
    // profile 2, mics 3, 6 dB, max_s 1200 (0x04B0), take 0x12345678
    const uint8_t w[DB_REC_TAKE_START_LEN] = {DB_REC_OP_TAKE_START, 2, 3, 6, 0xB0, 0x04,
                                              0x78, 0x56, 0x34, 0x12};
    rec_cmd_t c;
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, sizeof(w), &c));
    TEST_ASSERT_EQUAL(REC_CMD_TAKE_START, c.op);
    TEST_ASSERT_EQUAL_UINT8(DB_REC_PROFILE_RAW448_16G, c.profile);
    TEST_ASSERT_EQUAL_UINT8(DB_REC_MIC_1 | DB_REC_MIC_2, c.mic_mask);
    TEST_ASSERT_EQUAL_UINT8(6, c.gain_db);
    TEST_ASSERT_EQUAL_UINT16(1200, c.max_s);
    TEST_ASSERT_EQUAL_HEX32(0x12345678, c.take_id);
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, ble_codec_parse_rec(w, 9, &c));
    uint8_t longer[11];
    memcpy(longer, w, sizeof(w));
    longer[10] = 0;
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, ble_codec_parse_rec(longer, 11, &c));
    // Ranges are the recorder's: an odd profile still parses.
    longer[1] = 200;
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(longer, 10, &c));
    TEST_ASSERT_EQUAL_UINT8(200, c.profile);
}

static void test_rec_mark(void)
{
    uint8_t w[3 + DB_REC_MARK_TEXT_MAX + 1] = {DB_REC_OP_MARK, 0xEF, 0xBE};
    rec_cmd_t c;
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, 3, &c));
    TEST_ASSERT_EQUAL(REC_CMD_MARK, c.op);
    TEST_ASSERT_EQUAL_HEX16(0xBEEF, c.mark_code);
    TEST_ASSERT_EQUAL_STRING("", c.text);

    memcpy(&w[3], "hole 1 \xE2\x80\x93 oak", 14);
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, 17, &c));
    TEST_ASSERT_EQUAL_STRING("hole 1 \xE2\x80\x93 oak", c.text);

    memset(&w[3], 'x', DB_REC_MARK_TEXT_MAX + 1);
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, 3 + DB_REC_MARK_TEXT_MAX, &c));
    TEST_ASSERT_EQUAL_UINT(DB_REC_MARK_TEXT_MAX, strlen(c.text));
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN,
                           ble_codec_parse_rec(w, 4 + DB_REC_MARK_TEXT_MAX, &c));
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, ble_codec_parse_rec(w, 2, &c));
}

static void test_rec_mark_text_must_be_printable_utf8(void)
{
    // The length from the literal, so an embedded NUL counts.
#define B(lit) {lit, sizeof(lit) - 1}
    static const struct {
        const char *s;
        size_t n;
    } bad[] = {
        B("a\nb"), B("tab\t"), B("nul\x00x"), B("del\x7F"), B("\x80"), B("\xC3"), B("\xC3x"),
        B("\xC0\x80"), B("\xE2\x80"), B("\xF5\x80\x80\x80"), B("\xFF"),
        // C1 controls (U+0080, NEL U+0085, U+009F), overlong 3- and 4-byte
        // forms, a UTF-16 surrogate, above U+10FFFF
        B("\xC2\x80"), B("\xC2\x85"), B("\xC2\x9F"), B("\xE0\x80\x80"), B("\xE0\x9F\xBF"),
        B("\xF0\x80\x80\x80"), B("\xF0\x8F\xBF\xBF"), B("\xED\xA0\x80"), B("\xED\xBF\xBF"),
        B("\xF4\x90\x80\x80"),
    };
#undef B
    uint8_t w[3 + DB_REC_MARK_TEXT_MAX] = {DB_REC_OP_MARK, 1, 0};
    rec_cmd_t c = {.op = REC_CMD_OPEN, .take_id = 9};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        memcpy(&w[3], bad[i].s, bad[i].n);
        TEST_ASSERT_EQUAL_HEX8_MESSAGE(DB_ATT_ERR_BAD_ARG, ble_codec_parse_rec(w, 3 + bad[i].n, &c),
                                       bad[i].s);
    }
    // Nothing was written.
    TEST_ASSERT_EQUAL(REC_CMD_OPEN, c.op);
    TEST_ASSERT_EQUAL_UINT32(9, c.take_id);
    // Two-, three- and four-byte characters are fine.
    memcpy(&w[3], "\xC3\xA4\xE2\x82\xAC\xF0\x9F\x94\xA9", 9);
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, 12, &c));
    // The edges of each range: U+00A0, U+0800, U+D7FF, U+E000, U+10000,
    // U+10FFFF.
    memcpy(&w[3], "\xC2\xA0\xE0\xA0\x80\xED\x9F\xBF\xEE\x80\x80\xF0\x90\x80\x80"
                  "\xF4\x8F\xBF\xBF", 19);
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_parse_rec(w, 22, &c));
}

static void test_rec_unknown_and_empty(void)
{
    rec_cmd_t c;
    TEST_ASSERT_EQUAL_HEX8(BLE_CODEC_ATT_INVALID_LEN, ble_codec_parse_rec(NULL, 0, &c));
    const uint8_t u[] = {0x00, 0x06, 0xFF};
    for (size_t i = 0; i < sizeof(u); i++) {
        TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_UNKNOWN_CMD, ble_codec_parse_rec(&u[i], 1, &c));
    }
}

static void test_rec_att_errors(void)
{
    TEST_ASSERT_EQUAL_HEX8(0, ble_codec_rec_att_err(REC_OK));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_REC_STATE, ble_codec_rec_att_err(REC_ERR_STATE));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_BAD_ARG, ble_codec_rec_att_err(REC_ERR_BAD_ARG));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_REC_BATTERY, ble_codec_rec_att_err(REC_ERR_BATTERY));
    TEST_ASSERT_EQUAL_HEX8(DB_ATT_ERR_REC_RADIO, ble_codec_rec_att_err(REC_ERR_RADIO));
    TEST_ASSERT_NOT_EQUAL(0, ble_codec_rec_att_err((rec_err_t)99));
    TEST_ASSERT_EQUAL_HEX8(0x88, DB_ATT_ERR_REC_STATE);
    TEST_ASSERT_EQUAL_HEX8(0x89, DB_ATT_ERR_REC_RADIO);
    TEST_ASSERT_EQUAL_HEX8(0x8A, DB_ATT_ERR_REC_BATTERY);
}

static app_rec_status_t rec_status(uint8_t state)
{
    app_rec_status_t r = {
        .state = state,
        .reason = REC_REASON_MAX_S,
        .flags = DB_REC_FLAG_CLIENT | DB_REC_FLAG_AUDIO,
        .profile = REC_PROFILE_RAW448,
        .take_id = 0xA1B2C3D4,
        .elapsed_s = 83,
        .max_s = 300,
        .sent_bytes = 5u * 1024u * 1024u + 1023u,
        .dropped = 7,
        .ring_pct = 12,
        .channel = 6,
        .port = 47100,
        .ip = {192, 168, 4, 1},
        .ssid = "DB-1a2b-k7q2",
        .pass = "abcdefghijklmnopqrst",
    };
    for (int i = 0; i < 16; i++) {
        r.token[i] = (uint8_t)(0xF0 + i);
    }
    return r;
}

static void test_rec_state_packet(void)
{
    app_rec_status_t r = rec_status(REC_STATE_RECORDING);
    db_rec_state_packet_t p;
    memset(&p, 0x55, sizeof(p));
    ble_codec_rec_state(&r, &p);
    TEST_ASSERT_EQUAL_UINT8(1, p.version);
    TEST_ASSERT_EQUAL_UINT8(DB_REC_STATE_RECORDING, p.state);
    TEST_ASSERT_EQUAL_UINT8(DB_REC_REASON_MAX_S, p.reason);
    TEST_ASSERT_EQUAL_HEX8(DB_REC_FLAG_CLIENT | DB_REC_FLAG_AUDIO, p.flags);
    TEST_ASSERT_EQUAL_HEX32(0xA1B2C3D4, p.take_id);
    TEST_ASSERT_EQUAL_UINT16(83, p.elapsed_s);
    TEST_ASSERT_EQUAL_UINT16(300, p.max_s);
    TEST_ASSERT_EQUAL_UINT32(5u * 1024u, p.sent_kib);
    TEST_ASSERT_EQUAL_UINT32(7, p.dropped);
    TEST_ASSERT_EQUAL_UINT8(12, p.ring_pct);
    TEST_ASSERT_EQUAL_UINT8(DB_REC_PROFILE_RAW448, p.profile);
    TEST_ASSERT_EQUAL_UINT8(6, p.channel);
    TEST_ASSERT_EQUAL_UINT8(0, p.reserved);

    const uint8_t *b = (const uint8_t *)&p;
    // port 47100 = 0xB7FC, then 192.168.4.1 in order
    const uint8_t net[6] = {0xFC, 0xB7, 192, 168, 4, 1};
    TEST_ASSERT_EQUAL_HEX8_ARRAY(net, &b[24], 6);
    // NUL-padded strings
    uint8_t ssid[16] = "DB-1a2b-k7q2";
    TEST_ASSERT_EQUAL_HEX8_ARRAY(ssid, &b[30], 16);
    uint8_t pass[24] = "abcdefghijklmnopqrst";
    TEST_ASSERT_EQUAL_HEX8_ARRAY(pass, &b[46], 24);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(r.token, &b[70], 16);
}

static void test_rec_state_full_length_strings(void)
{
    app_rec_status_t r = rec_status(REC_STATE_WAIT_CLIENT);
    memset(r.ssid, 'S', 16);
    r.ssid[16] = 0;
    memset(r.pass, 'P', 24);
    r.pass[24] = 0;
    db_rec_state_packet_t p;
    ble_codec_rec_state(&r, &p);
    // No NUL inside the field, and nothing spills into the next one.
    TEST_ASSERT_EACH_EQUAL_HEX8('S', p.ssid, 16);
    TEST_ASSERT_EACH_EQUAL_HEX8('P', p.pass, 24);
    TEST_ASSERT_EQUAL_HEX8(0xF0, p.token[0]);
}

static void test_rec_state_hides_credentials_when_not_joinable(void)
{
    static const uint8_t hidden[] = {REC_STATE_IDLE, REC_STATE_AP_STARTING, REC_STATE_CLOSING};
    static const uint8_t shown[] = {REC_STATE_WAIT_CLIENT, REC_STATE_READY, REC_STATE_RECORDING,
                                    REC_STATE_DRAINING};
    uint8_t zero[56] = {0};
    for (size_t i = 0; i < sizeof(hidden); i++) {
        app_rec_status_t r = rec_status(hidden[i]);
        db_rec_state_packet_t p;
        ble_codec_rec_state(&r, &p);
        // port, ip, ssid, pass, token: bytes 24..85
        TEST_ASSERT_EQUAL_HEX8_ARRAY(zero, &((const uint8_t *)&p)[24], 56);
        TEST_ASSERT_EQUAL_HEX8_ARRAY(zero, &((const uint8_t *)&p)[80], 6);
        // The take's numbers stay, and why it or the session ended.
        TEST_ASSERT_EQUAL_HEX32(0xA1B2C3D4, p.take_id);
        TEST_ASSERT_EQUAL_UINT8(DB_REC_REASON_MAX_S, p.reason);
        TEST_ASSERT_EQUAL_UINT16(83, p.elapsed_s);
    }
    for (size_t i = 0; i < sizeof(shown); i++) {
        app_rec_status_t r = rec_status(shown[i]);
        db_rec_state_packet_t p;
        ble_codec_rec_state(&r, &p);
        TEST_ASSERT_EQUAL_UINT16(47100, p.port);
        TEST_ASSERT_EQUAL_HEX8(0xFF, p.token[15]);
    }
}

static void test_rec_state_sent_kib_saturates(void)
{
    app_rec_status_t r = rec_status(REC_STATE_READY);
    r.sent_bytes = UINT64_MAX;
    db_rec_state_packet_t p;
    ble_codec_rec_state(&r, &p);
    TEST_ASSERT_EQUAL_UINT32(UINT32_MAX, p.sent_kib);
}

static void test_rec_state_changed(void)
{
    app_rec_status_t r = rec_status(REC_STATE_RECORDING);
    db_rec_state_packet_t a, b;
    ble_codec_rec_state(&r, &a);
    b = a;
    TEST_ASSERT_FALSE(ble_codec_rec_state_changed(&a, &b));
    // The running counters alone are no news.
    b.elapsed_s++;
    b.sent_kib += 100;
    b.dropped++;
    b.ring_pct = 90;
    TEST_ASSERT_FALSE(ble_codec_rec_state_changed(&a, &b));
    // Every other byte is.
    for (size_t i = 0; i < sizeof(a); i++) {
        if ((i >= 8 && i < 10) || (i >= 12 && i < 21)) {
            continue;
        }
        b = a;
        ((uint8_t *)&b)[i] ^= 1;
        TEST_ASSERT_TRUE_MESSAGE(ble_codec_rec_state_changed(&a, &b), "byte");
    }
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
                             DB_ATT_ERR_BUSY,       DB_ATT_ERR_UNAVAILABLE, DB_ATT_ERR_REC_STATE,
                             DB_ATT_ERR_REC_RADIO,  DB_ATT_ERR_REC_BATTERY};
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
    RUN_TEST(test_level_record);
    RUN_TEST(test_status_packet_fields);
    RUN_TEST(test_status_flags_cal_valid_and_charge_done);
    RUN_TEST(test_status_recording_flag);
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
    RUN_TEST(test_cmd_cal_start_refused_while_the_device_is_taken);
    RUN_TEST(test_rec_simple_ops);
    RUN_TEST(test_rec_take_start);
    RUN_TEST(test_rec_mark);
    RUN_TEST(test_rec_mark_text_must_be_printable_utf8);
    RUN_TEST(test_rec_unknown_and_empty);
    RUN_TEST(test_rec_att_errors);
    RUN_TEST(test_rec_state_packet);
    RUN_TEST(test_rec_state_full_length_strings);
    RUN_TEST(test_rec_state_hides_credentials_when_not_joinable);
    RUN_TEST(test_rec_state_sent_kib_saturates);
    RUN_TEST(test_rec_state_changed);
    RUN_TEST(test_settings_att_errors);
    RUN_TEST(test_att_codes_are_application_range);
    return UNITY_END();
}
