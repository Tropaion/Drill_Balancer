#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "unity.h"
#include "accel_cal.h"
#include "ui_logic.h"

#define DEG "\xC2\xB0"

static settings_t s;
static char buf[32];

void setUp(void)
{
    s = settings_defaults();
}

void tearDown(void) {}

static void test_fmt_deg_rounds_and_never_shows_minus_zero(void)
{
    ui_fmt_deg(buf, sizeof(buf), -12.34f, false);
    TEST_ASSERT_EQUAL_STRING("-12.3" DEG, buf);
    ui_fmt_deg(buf, sizeof(buf), 0.96f, false);
    TEST_ASSERT_EQUAL_STRING("1.0" DEG, buf);
    ui_fmt_deg(buf, sizeof(buf), -0.04f, false);
    TEST_ASSERT_EQUAL_STRING("0.0" DEG, buf);
    ui_fmt_deg(buf, sizeof(buf), -0.04f, true);
    TEST_ASSERT_EQUAL_STRING("0.0" DEG, buf);
    ui_fmt_deg(buf, sizeof(buf), 0.05f, true);
    TEST_ASSERT_EQUAL_STRING("+0.1" DEG, buf);
    ui_fmt_deg(buf, sizeof(buf), -90.0f, true);
    TEST_ASSERT_EQUAL_STRING("-90.0" DEG, buf);
}

static void test_fmt_fixed_decimals_saturation_and_nan(void)
{
    ui_fmt_fixed(buf, sizeof(buf), 0.05f, 2, false, NULL);
    TEST_ASSERT_EQUAL_STRING("0.05", buf);
    ui_fmt_fixed(buf, sizeof(buf), 7.0f, 0, true, " s");
    TEST_ASSERT_EQUAL_STRING("+7 s", buf);
    ui_fmt_fixed(buf, sizeof(buf), 1.005f, 3, false, "");
    TEST_ASSERT_EQUAL_STRING("1.005", buf);
    ui_fmt_fixed(buf, sizeof(buf), -1e9f, 1, false, NULL);
    TEST_ASSERT_EQUAL_STRING("-9999.0", buf);
    ui_fmt_fixed(buf, sizeof(buf), NAN, 1, false, DEG);
    TEST_ASSERT_EQUAL_STRING("--" DEG, buf);
    ui_fmt_fixed(buf, sizeof(buf), INFINITY, 1, true, NULL);
    TEST_ASSERT_EQUAL_STRING("--", buf);
}

static void test_fmt_truncates_safely(void)
{
    char small[4];
    memset(small, 'x', sizeof(small));
    ui_fmt_deg(small, sizeof(small), -12.3f, false);
    TEST_ASSERT_EQUAL('\0', small[3]);
    ui_fmt_deg(small, 0, 1.0f, false);   // must not write
    TEST_ASSERT_EQUAL('\0', small[3]);
}

static void test_wall_arrow(void)
{
    level_reading_t r = {.elevation_deg = 3.0f, .status = LEVEL_STATUS_OFF};
    TEST_ASSERT_EQUAL(1, ui_wall_arrow(&r));
    r.elevation_deg = -0.6f;
    r.status = LEVEL_STATUS_NEAR;
    TEST_ASSERT_EQUAL(-1, ui_wall_arrow(&r));
    r.status = LEVEL_STATUS_ON_TARGET;
    TEST_ASSERT_EQUAL(0, ui_wall_arrow(&r));
    // Off target but it would read "0.0": no arrow either (tolerance below 0.05).
    r.status = LEVEL_STATUS_NEAR;
    r.elevation_deg = -0.04f;
    TEST_ASSERT_EQUAL(0, ui_wall_arrow(&r));
}

static void test_ring_fraction(void)
{
    TEST_ASSERT_EQUAL_FLOAT(1.0f, ui_ring_fraction(0.0f, 2.0f));
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 0.5f, ui_ring_fraction(-3.0f, 2.0f));
    TEST_ASSERT_EQUAL_FLOAT(0.0f, ui_ring_fraction(6.0f, 2.0f));
    TEST_ASSERT_EQUAL_FLOAT(0.0f, ui_ring_fraction(60.0f, 2.0f));
    TEST_ASSERT_EQUAL_FLOAT(0.0f, ui_ring_fraction(NAN, 2.0f));
    // A broken near band must not divide by zero.
    float f = ui_ring_fraction(0.1f, 0.0f);
    TEST_ASSERT_TRUE(f >= 0.0f && f <= 1.0f);
}

static void test_bubble_scale_and_axes(void)
{
    int32_t x, y;
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 110.0f / 3.0f / 2.0f, ui_bubble_ppd(2.0f, 110.0f));
    // near_deg along +X lands at a third of the radius, to the right.
    ui_bubble_offset(2.0f, 0.0f, 2.0f, 120.0f, 15.0f, &x, &y);
    TEST_ASSERT_EQUAL_INT32(40, x);
    TEST_ASSERT_EQUAL_INT32(0, y);
    // Board +Y is screen up, LVGL y grows downwards.
    ui_bubble_offset(0.0f, 1.0f, 2.0f, 120.0f, 15.0f, &x, &y);
    TEST_ASSERT_EQUAL_INT32(0, x);
    TEST_ASSERT_EQUAL_INT32(-20, y);
}

static void test_bubble_clamped_inside_view_keeping_direction(void)
{
    int32_t x, y;
    ui_bubble_offset(30.0f, -40.0f, 2.0f, 120.0f, 20.0f, &x, &y);
    float d = sqrtf((float)(x * x + y * y));
    TEST_ASSERT_FLOAT_WITHIN(1.0f, 100.0f, d);
    // direction 3:4 kept (x right, y down for negative board Y)
    TEST_ASSERT_INT32_WITHIN(1, 60, x);
    TEST_ASSERT_INT32_WITHIN(1, 80, y);
    ui_bubble_offset(NAN, 1.0f, 2.0f, 120.0f, 20.0f, &x, &y);
    TEST_ASSERT_EQUAL_INT32(0, x);
    TEST_ASSERT_EQUAL_INT32(0, y);
}

static void test_yaw_offset(void)
{
    TEST_ASSERT_EQUAL_INT32(40, ui_yaw_offset(2.0f, 2.0f, 120));
    TEST_ASSERT_EQUAL_INT32(-20, ui_yaw_offset(-1.0f, 2.0f, 120));
    TEST_ASSERT_EQUAL_INT32(120, ui_yaw_offset(50.0f, 2.0f, 120));
    TEST_ASSERT_EQUAL_INT32(-120, ui_yaw_offset(-50.0f, 2.0f, 120));
    TEST_ASSERT_EQUAL_INT32(0, ui_yaw_offset(NAN, 2.0f, 120));
}

static void test_burnin_offsets_bounded_and_moving(void)
{
    int32_t x0, y0;
    ui_burnin_offset(0, 4, &x0, &y0);
    TEST_ASSERT_EQUAL_INT32(0, x0);
    TEST_ASSERT_EQUAL_INT32(0, y0);
    int distinct = 0;
    int32_t px = x0, py = y0;
    for (uint32_t i = 1; i < 40; i++) {
        int32_t dx, dy;
        ui_burnin_offset(i, 4, &dx, &dy);
        TEST_ASSERT_TRUE(abs(dx) <= 4 && abs(dy) <= 4);
        if (dx != px || dy != py) {
            distinct++;
        }
        px = dx;
        py = dy;
    }
    TEST_ASSERT_TRUE(distinct >= 30);
    // wraps without a jump at the counter overflow
    int32_t dx, dy;
    ui_burnin_offset(UINT32_MAX, 4, &dx, &dy);
    TEST_ASSERT_TRUE(abs(dx) <= 4 && abs(dy) <= 4);
}

static void test_battery_level_and_banner(void)
{
    TEST_ASSERT_EQUAL(0, ui_battery_level(0));
    TEST_ASSERT_EQUAL(0, ui_battery_level(9));
    TEST_ASSERT_EQUAL(4, ui_battery_level(100));
    TEST_ASSERT_EQUAL(4, ui_battery_level(255));
    for (int p = 1; p <= 100; p++) {
        TEST_ASSERT_TRUE(ui_battery_level((uint8_t)p) >= ui_battery_level((uint8_t)(p - 1)));
    }
    app_battery_t b = {.valid = true, .percent = 9};
    TEST_ASSERT_TRUE(ui_low_battery(&b));
    b.percent = UI_LOW_BATTERY_PCT;
    TEST_ASSERT_FALSE(ui_low_battery(&b));
    b.percent = 3;
    b.charging = true;
    TEST_ASSERT_FALSE(ui_low_battery(&b));
    b.charging = false;
    b.vbus_present = true;
    TEST_ASSERT_FALSE(ui_low_battery(&b));
    b = (app_battery_t){.valid = false, .percent = 0};
    TEST_ASSERT_FALSE(ui_low_battery(&b));
}

static void test_tolerance_steps_and_limits(void)
{
    s.tolerance_deg = 0.95f;
    TEST_ASSERT_TRUE(ui_settings_step(&s, UI_FIELD_TOLERANCE, +1));
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.0f, s.tolerance_deg);
    ui_settings_step(&s, UI_FIELD_TOLERANCE, +1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.25f, s.tolerance_deg);
    ui_settings_step(&s, UI_FIELD_TOLERANCE, -1);
    ui_settings_step(&s, UI_FIELD_TOLERANCE, -1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.95f, s.tolerance_deg);
    // An off-grid value from BLE snaps to the next grid value.
    s.tolerance_deg = 0.33f;
    ui_settings_step(&s, UI_FIELD_TOLERANCE, +1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.35f, s.tolerance_deg);
    s.tolerance_deg = 0.33f;
    ui_settings_step(&s, UI_FIELD_TOLERANCE, -1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.30f, s.tolerance_deg);
    // Lower limit: no change and false.
    s.tolerance_deg = 0.05f;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_TOLERANCE, -1));
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.05f, s.tolerance_deg);
    s.tolerance_deg = 5.0f;
    s.near_deg = 5.0f;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_TOLERANCE, +1));
}

static void test_tolerance_above_near_pushes_near(void)
{
    s.tolerance_deg = 1.0f;
    s.near_deg = 1.0f;
    ui_settings_step(&s, UI_FIELD_TOLERANCE, +1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.25f, s.tolerance_deg);
    TEST_ASSERT_TRUE(s.near_deg >= s.tolerance_deg);
    TEST_ASSERT_TRUE(settings_validate(&s));
}

static void test_near_never_below_tolerance(void)
{
    s.tolerance_deg = 0.5f;
    s.near_deg = 0.6f;
    ui_settings_step(&s, UI_FIELD_NEAR, -1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.5f, s.near_deg);
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_NEAR, -1));
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.5f, s.near_deg);
    s.near_deg = 20.0f;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_NEAR, +1));
}

static void test_target_steps_whole_degrees_within_range(void)
{
    s.target_deg = 89.0f;
    ui_settings_step(&s, UI_FIELD_TARGET, +1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 90.0f, s.target_deg);
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_TARGET, +1));
    s.target_deg = -0.5f;
    ui_settings_step(&s, UI_FIELD_TARGET, -1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, -1.0f, s.target_deg);
    s.target_deg = -0.5f;
    ui_settings_step(&s, UI_FIELD_TARGET, +1);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 0.0f, s.target_deg);
    s.target_deg = -90.0f;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_TARGET, -1));
}

static void test_brightness_drags_dim_brightness_down(void)
{
    s.brightness_pct = 30;
    s.dim_brightness_pct = 25;
    ui_settings_step(&s, UI_FIELD_BRIGHTNESS, -1);
    TEST_ASSERT_EQUAL_UINT8(20, s.brightness_pct);
    TEST_ASSERT_EQUAL_UINT8(20, s.dim_brightness_pct);
    ui_settings_step(&s, UI_FIELD_BRIGHTNESS, -1);
    TEST_ASSERT_EQUAL_UINT8(10, s.brightness_pct);
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_BRIGHTNESS, -1));
    TEST_ASSERT_TRUE(settings_validate(&s));
    s.brightness_pct = 100;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_BRIGHTNESS, +1));
}

static void test_times_use_presets_and_keep_sleep_after_dim(void)
{
    s.dim_after_s = 600;
    s.sleep_after_s = 600;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_DIM_S, +1));
    // sleep cannot go below dim even between presets
    s.dim_after_s = 45;
    s.sleep_after_s = 60;
    ui_settings_step(&s, UI_FIELD_SLEEP_S, -1);
    TEST_ASSERT_EQUAL_UINT16(45, s.sleep_after_s);
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_SLEEP_S, -1));
    // raising dim past sleep pushes sleep
    s.dim_after_s = 300;
    s.sleep_after_s = 300;
    ui_settings_step(&s, UI_FIELD_DIM_S, +1);
    TEST_ASSERT_EQUAL_UINT16(600, s.dim_after_s);
    TEST_ASSERT_EQUAL_UINT16(600, s.sleep_after_s);
    TEST_ASSERT_TRUE(settings_validate(&s));
    // off time: 0 = never is the bottom
    s.off_after_min = 15;
    ui_settings_step(&s, UI_FIELD_OFF_MIN, -1);
    TEST_ASSERT_EQUAL_UINT16(0, s.off_after_min);
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_OFF_MIN, -1));
    s.off_after_min = 1440;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_OFF_MIN, +1));
    // an off-preset value moves to the neighbouring preset
    s.off_after_min = 100;
    ui_settings_step(&s, UI_FIELD_OFF_MIN, +1);
    TEST_ASSERT_EQUAL_UINT16(120, s.off_after_min);
    s.sleep_after_s = 3600;
    TEST_ASSERT_FALSE(ui_settings_step(&s, UI_FIELD_SLEEP_S, +1));
}

static void test_random_steps_always_validate(void)
{
    srand(1234);
    for (int i = 0; i < 20000; i++) {
        ui_field_t f = (ui_field_t)(rand() % UI_FIELD_COUNT);
        int dir = (rand() & 1) ? 1 : -1;
        ui_settings_step(&s, f, dir);
        if (!settings_validate(&s)) {
            char msg[64];
            snprintf(msg, sizeof(msg), "step %d field %d dir %d", i, (int)f, dir);
            TEST_FAIL_MESSAGE(msg);
        }
    }
}

// Steps field f in direction dir until it stops changing.
static void step_to_end(ui_field_t f, int dir)
{
    for (int i = 0; i < 1000 && ui_settings_step(&s, f, dir); i++) {
    }
}

// The steppers reach exactly the settings.h limits, so widening a limit there
// cannot leave values the screen can never set.
static void test_steppers_reach_every_limit(void)
{
    step_to_end(UI_FIELD_TOLERANCE, -1);
    TEST_ASSERT_EQUAL_FLOAT(SETTINGS_TOL_MIN_DEG, s.tolerance_deg);
    step_to_end(UI_FIELD_NEAR, +1);
    TEST_ASSERT_EQUAL_FLOAT(SETTINGS_NEAR_MAX_DEG, s.near_deg);
    step_to_end(UI_FIELD_TOLERANCE, +1);
    TEST_ASSERT_EQUAL_FLOAT(SETTINGS_TOL_MAX_DEG, s.tolerance_deg);
    step_to_end(UI_FIELD_TARGET, +1);
    TEST_ASSERT_EQUAL_FLOAT(SETTINGS_TARGET_MAX_DEG, s.target_deg);
    step_to_end(UI_FIELD_TARGET, -1);
    TEST_ASSERT_EQUAL_FLOAT(-SETTINGS_TARGET_MAX_DEG, s.target_deg);
    step_to_end(UI_FIELD_BRIGHTNESS, -1);
    TEST_ASSERT_EQUAL_UINT8(SETTINGS_BRIGHT_MIN_PCT, s.brightness_pct);
    step_to_end(UI_FIELD_BRIGHTNESS, +1);
    TEST_ASSERT_EQUAL_UINT8(SETTINGS_BRIGHT_MAX_PCT, s.brightness_pct);
    step_to_end(UI_FIELD_DIM_S, -1);
    TEST_ASSERT_EQUAL_UINT16(SETTINGS_DIM_MIN_S, s.dim_after_s);
    step_to_end(UI_FIELD_SLEEP_S, +1);
    TEST_ASSERT_EQUAL_UINT16(SETTINGS_SLEEP_MAX_S, s.sleep_after_s);
    step_to_end(UI_FIELD_DIM_S, +1);
    TEST_ASSERT_EQUAL_UINT16(SETTINGS_DIM_MAX_S, s.dim_after_s);
    step_to_end(UI_FIELD_OFF_MIN, +1);
    TEST_ASSERT_EQUAL_UINT16(SETTINGS_OFF_MAX_MIN, s.off_after_min);
    step_to_end(UI_FIELD_OFF_MIN, -1);
    TEST_ASSERT_EQUAL_UINT16(0, s.off_after_min);
    TEST_ASSERT_TRUE(settings_validate(&s));
}

static void test_format_fields(void)
{
    ui_settings_format(&s, UI_FIELD_TOLERANCE, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("0.50" DEG, buf);
    ui_settings_format(&s, UI_FIELD_TARGET, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("45.0" DEG, buf);
    ui_settings_format(&s, UI_FIELD_BRIGHTNESS, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("100 %", buf);
    s.dim_after_s = 45;
    ui_settings_format(&s, UI_FIELD_DIM_S, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("45 s", buf);
    s.sleep_after_s = 300;
    ui_settings_format(&s, UI_FIELD_SLEEP_S, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("5 min", buf);
    s.sleep_after_s = 3600;
    ui_settings_format(&s, UI_FIELD_SLEEP_S, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("1 h", buf);
    s.sleep_after_s = 90;
    ui_settings_format(&s, UI_FIELD_SLEEP_S, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("90 s", buf);
    s.off_after_min = 0;
    ui_settings_format(&s, UI_FIELD_OFF_MIN, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("Never", buf);
    s.off_after_min = 1440;
    ui_settings_format(&s, UI_FIELD_OFF_MIN, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("24 h", buf);
    s.off_after_min = 30;
    ui_settings_format(&s, UI_FIELD_OFF_MIN, buf, sizeof(buf));
    TEST_ASSERT_EQUAL_STRING("30 min", buf);
}

static void test_merge_keeps_calibration_and_mode(void)
{
    settings_t edit = settings_defaults();
    edit.tolerance_deg = 1.0f;
    edit.ble_enabled = !edit.ble_enabled;
    edit.off_after_min = 0;

    settings_t loaded = settings_defaults();
    settings_t cur = settings_defaults();
    cur.mode = LEVEL_MODE_FLOOR;
    cur.mount_cal_valid = true;
    cur.drill_axis = (vec3_t){0.0f, 0.1f, -0.99498744f};
    ui_settings_merge(&cur, &loaded, &edit);

    TEST_ASSERT_EQUAL_FLOAT(1.0f, cur.tolerance_deg);
    TEST_ASSERT_EQUAL(edit.ble_enabled, cur.ble_enabled);
    TEST_ASSERT_EQUAL_UINT16(0, cur.off_after_min);
    TEST_ASSERT_EQUAL(LEVEL_MODE_FLOOR, cur.mode);
    TEST_ASSERT_TRUE(cur.mount_cal_valid);
    TEST_ASSERT_EQUAL_FLOAT(0.1f, cur.drill_axis.y);
    TEST_ASSERT_TRUE(settings_validate(&cur));
}

static void test_merge_keeps_screen_fields_changed_elsewhere(void)
{
    // The screen loaded the defaults; the user changed the tolerance while
    // a phone stored a new brightness and turned BLE off.
    settings_t loaded = settings_defaults();
    settings_t edit = loaded;
    edit.tolerance_deg = 1.0f;

    settings_t cur = loaded;
    cur.brightness_pct = 50;
    cur.ble_enabled = false;
    cur.dim_after_s = 60;
    ui_settings_merge(&cur, &loaded, &edit);

    TEST_ASSERT_EQUAL_FLOAT(1.0f, cur.tolerance_deg);
    TEST_ASSERT_EQUAL_UINT8(50, cur.brightness_pct);
    TEST_ASSERT_FALSE(cur.ble_enabled);
    TEST_ASSERT_EQUAL_UINT16(60, cur.dim_after_s);
    TEST_ASSERT_EQUAL_FLOAT(loaded.near_deg, cur.near_deg);
    TEST_ASSERT_TRUE(settings_validate(&cur));
}

static void test_merge_carries_fields_moved_by_fixup(void)
{
    // Stepping the tolerance above near pushed near up with it
    // (settings_fixup in ui_settings_step); that change is the user's too.
    settings_t loaded = settings_defaults();
    settings_t edit = loaded;
    edit.tolerance_deg = 3.0f;
    settings_fixup(&edit);
    TEST_ASSERT_EQUAL_FLOAT(3.0f, edit.near_deg);

    settings_t cur = loaded;
    ui_settings_merge(&cur, &loaded, &edit);
    TEST_ASSERT_EQUAL_FLOAT(3.0f, cur.tolerance_deg);
    TEST_ASSERT_EQUAL_FLOAT(3.0f, cur.near_deg);
}

static void test_merge_repairs_cross_field_rules(void)
{
    // A phone raised the dim brightness meanwhile; the user lowered the
    // brightness below it.
    settings_t loaded = settings_defaults();
    settings_t edit = loaded;
    edit.brightness_pct = 30;
    settings_t cur = loaded;
    cur.dim_brightness_pct = 40;
    ui_settings_merge(&cur, &loaded, &edit);
    TEST_ASSERT_EQUAL_UINT8(30, cur.brightness_pct);
    TEST_ASSERT_EQUAL_UINT8(30, cur.dim_brightness_pct);
    TEST_ASSERT_TRUE(settings_validate(&cur));
}

static void test_texts_cover_every_result(void)
{
    TEST_ASSERT_EQUAL_STRING("WALL", ui_mode_title(LEVEL_MODE_WALL));
    TEST_ASSERT_EQUAL_STRING("FLOOR / CEILING", ui_mode_title(LEVEL_MODE_FLOOR));
    TEST_ASSERT_EQUAL_STRING("ANGLE", ui_mode_title(LEVEL_MODE_ANGLE));
    TEST_ASSERT_NOT_NULL(ui_mode_title(LEVEL_MODE_COUNT));

    const char *unknown = ui_mount_result_text(99);
    TEST_ASSERT_NOT_NULL(unknown);
    for (int r = LEVEL_CAL_OK; r <= LEVEL_CAL_OFFSET_TOO_LARGE; r++) {
        TEST_ASSERT_NOT_NULL(ui_mount_result_text(r));
        TEST_ASSERT_NOT_EQUAL(unknown, ui_mount_result_text(r));
    }
    unknown = ui_accel_result_text(-1);
    for (int r = ACCEL_CAL_OK; r <= ACCEL_CAL_OUT_OF_RANGE; r++) {
        TEST_ASSERT_NOT_NULL(ui_accel_result_text(r));
        TEST_ASSERT_NOT_EQUAL(unknown, ui_accel_result_text(r));
    }
}

static void test_not_stored_result_has_its_own_text(void)
{
    TEST_ASSERT_NOT_EQUAL(ui_mount_result_text(99), ui_mount_result_text(APP_CAL_RESULT_NOT_STORED));
    TEST_ASSERT_NOT_EQUAL(ui_accel_result_text(99), ui_accel_result_text(APP_CAL_RESULT_NOT_STORED));
    TEST_ASSERT_NOT_NULL(strstr(ui_mount_result_text(APP_CAL_RESULT_NOT_STORED), "not saved"));
    TEST_ASSERT_NOT_NULL(strstr(ui_accel_result_text(APP_CAL_RESULT_NOT_STORED), "not saved"));

    TEST_ASSERT_FALSE(ui_cal_result_known(APP_CAL_RESULT_NONE));
    TEST_ASSERT_TRUE(ui_cal_result_known(APP_CAL_RESULT_NOT_STORED));
    TEST_ASSERT_TRUE(ui_cal_result_known(LEVEL_CAL_OK));
    TEST_ASSERT_TRUE(ui_cal_result_known(LEVEL_CAL_NOT_ON_CONE));
    TEST_ASSERT_TRUE(ui_cal_result_ok(LEVEL_CAL_OK));
    TEST_ASSERT_FALSE(ui_cal_result_ok(APP_CAL_RESULT_NOT_STORED));
    TEST_ASSERT_FALSE(ui_cal_result_ok(APP_CAL_RESULT_NONE));
}

static void test_sensor_cal_ok_asks_for_mount_cal(void)
{
    // A new sensor calibration invalidates the mount calibration: redo it
    // only if one was reset, else it is simply the next step.
    const char *dropped = ui_accel_result_text(APP_CAL_RESULT_OK_MOUNT_DROPPED);
    const char *first = ui_accel_result_text(ACCEL_CAL_OK);
    TEST_ASSERT_NOT_NULL(strstr(dropped, "redo"));
    TEST_ASSERT_NOT_NULL(strstr(first, "mount"));
    TEST_ASSERT_NULL(strstr(first, "redo"));
    TEST_ASSERT_TRUE(ui_cal_result_known(APP_CAL_RESULT_OK_MOUNT_DROPPED));
    TEST_ASSERT_TRUE(ui_cal_result_ok(APP_CAL_RESULT_OK_MOUNT_DROPPED));
    TEST_ASSERT_TRUE(ui_cal_result_ok(ACCEL_CAL_OK));
}

// Corner of a row of width w and height h centred dy below the centre.
static float corner_r(int32_t dy, int32_t h, int32_t w)
{
    float y = (float)abs(dy) + (float)h / 2.0f;
    float x = (float)w / 2.0f;
    return sqrtf(x * x + y * y);
}

static void test_round_row_centre_keeps_nominal_width(void)
{
    int32_t w;
    uint8_t opa;
    ui_round_row(0, 92, 330, 240, 225, &w, &opa);
    TEST_ASSERT_EQUAL_INT32(330, w);
    TEST_ASSERT_EQUAL_UINT8(255, opa);
}

static void test_round_row_neighbour_narrows_inside_circle(void)
{
    int32_t w;
    uint8_t opa;
    // The rows next to the centred one (92 px rows, 30 px gap).
    ui_round_row(-122, 92, 330, 240, 225, &w, &opa);
    TEST_ASSERT_LESS_THAN_INT32(330, w);
    TEST_ASSERT_GREATER_OR_EQUAL_INT32(240, w);
    TEST_ASSERT_TRUE(corner_r(-122, 92, w) <= 225.0f);
    TEST_ASSERT_EQUAL_UINT8(255, opa);
}

static void test_round_row_far_rows_fade_never_below_min(void)
{
    int32_t prev_w = 1000;
    int prev_opa = 256;
    for (int32_t dy = 0; dy <= 260; dy += 5) {
        int32_t w;
        uint8_t opa;
        ui_round_row(dy, 92, 330, 240, 225, &w, &opa);
        TEST_ASSERT_TRUE(w >= 240 && w <= 330);
        TEST_ASSERT_TRUE(w <= prev_w);          // narrows monotonically
        TEST_ASSERT_TRUE(opa <= prev_opa);      // fades monotonically
        // Opaque only while the corners are inside the circle.
        if (opa == 255) {
            TEST_ASSERT_TRUE(corner_r(dy, 92, w) <= 225.5f);
        }
        prev_w = w;
        prev_opa = opa;
    }
    int32_t w;
    uint8_t opa;
    ui_round_row(240, 92, 330, 240, 225, &w, &opa);
    TEST_ASSERT_TRUE(opa <= UI_ROUND_MIN_OPA);
    TEST_ASSERT_TRUE(opa > 0);   // still hints that the list goes on
}

static void test_round_row_fixed_width_only_fades(void)
{
    int32_t w;
    uint8_t opa;
    ui_round_row(150, 24, 200, 200, 225, &w, &opa);
    TEST_ASSERT_EQUAL_INT32(200, w);
    TEST_ASSERT_EQUAL_UINT8(255, opa);
    ui_round_row(220, 24, 200, 200, 225, &w, &opa);
    TEST_ASSERT_EQUAL_INT32(200, w);
    TEST_ASSERT_LESS_THAN_UINT8(255, opa);
}

static void test_poses_cover_all_six_directions_once(void)
{
    uint8_t bits = 0;
    for (int i = 0; i < UI_POSE_COUNT; i++) {
        TEST_ASSERT_EQUAL(0, bits & ui_poses[i].bit);
        bits |= ui_poses[i].bit;
        TEST_ASSERT_NOT_NULL(ui_poses[i].label);
    }
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_ALL, bits);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_fmt_deg_rounds_and_never_shows_minus_zero);
    RUN_TEST(test_fmt_fixed_decimals_saturation_and_nan);
    RUN_TEST(test_fmt_truncates_safely);
    RUN_TEST(test_wall_arrow);
    RUN_TEST(test_ring_fraction);
    RUN_TEST(test_bubble_scale_and_axes);
    RUN_TEST(test_bubble_clamped_inside_view_keeping_direction);
    RUN_TEST(test_yaw_offset);
    RUN_TEST(test_burnin_offsets_bounded_and_moving);
    RUN_TEST(test_battery_level_and_banner);
    RUN_TEST(test_tolerance_steps_and_limits);
    RUN_TEST(test_tolerance_above_near_pushes_near);
    RUN_TEST(test_near_never_below_tolerance);
    RUN_TEST(test_target_steps_whole_degrees_within_range);
    RUN_TEST(test_brightness_drags_dim_brightness_down);
    RUN_TEST(test_times_use_presets_and_keep_sleep_after_dim);
    RUN_TEST(test_random_steps_always_validate);
    RUN_TEST(test_steppers_reach_every_limit);
    RUN_TEST(test_format_fields);
    RUN_TEST(test_merge_keeps_calibration_and_mode);
    RUN_TEST(test_merge_keeps_screen_fields_changed_elsewhere);
    RUN_TEST(test_merge_carries_fields_moved_by_fixup);
    RUN_TEST(test_merge_repairs_cross_field_rules);
    RUN_TEST(test_texts_cover_every_result);
    RUN_TEST(test_not_stored_result_has_its_own_text);
    RUN_TEST(test_sensor_cal_ok_asks_for_mount_cal);
    RUN_TEST(test_round_row_centre_keeps_nominal_width);
    RUN_TEST(test_round_row_neighbour_narrows_inside_circle);
    RUN_TEST(test_round_row_far_rows_fade_never_below_min);
    RUN_TEST(test_round_row_fixed_width_only_fades);
    RUN_TEST(test_poses_cover_all_six_directions_once);
    return UNITY_END();
}
