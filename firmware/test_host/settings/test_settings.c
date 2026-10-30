#include <math.h>
#include <string.h>
#include "cJSON.h"
#include "unity.h"
#include "settings.h"

static settings_t s;

void setUp(void)
{
    s = settings_defaults();
}

void tearDown(void) {}

static settings_json_result_t apply(const char *json)
{
    return settings_apply_json(&s, json, strlen(json));
}

// Applies json, expects err and that s did not change.
static void expect_rejected(settings_json_result_t err, const char *json)
{
    settings_t before;
    memcpy(&before, &s, sizeof(s));
    TEST_ASSERT_EQUAL_MESSAGE(err, apply(json), json);
    TEST_ASSERT_EQUAL_MEMORY_MESSAGE(&before, &s, sizeof(s), json);
}

static void test_defaults(void)
{
    TEST_ASSERT_TRUE(settings_validate(&s));
    TEST_ASSERT_EQUAL_FLOAT(0.5f, s.tolerance_deg);
    TEST_ASSERT_EQUAL_FLOAT(2.0f, s.near_deg);
    TEST_ASSERT_EQUAL_FLOAT(45.0f, s.target_deg);
    TEST_ASSERT_EQUAL(LEVEL_MODE_WALL, s.mode);
    TEST_ASSERT_EQUAL_UINT8(100, s.brightness_pct);
    TEST_ASSERT_EQUAL_UINT8(20, s.dim_brightness_pct);
    TEST_ASSERT_EQUAL_UINT16(30, s.dim_after_s);
    TEST_ASSERT_EQUAL_UINT16(120, s.sleep_after_s);
    TEST_ASSERT_EQUAL_UINT16(60, s.off_after_min);
    TEST_ASSERT_TRUE(s.ble_enabled);
    TEST_ASSERT_FALSE(s.dot_invert);
    TEST_ASSERT_EQUAL_UINT16(60, s.yaw_stale_s);
    TEST_ASSERT_FALSE(s.mount_cal_valid);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, s.drill_axis.x);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, s.drill_axis.y);
    TEST_ASSERT_EQUAL_FLOAT(-1.0f, s.drill_axis.z);
    TEST_ASSERT_FALSE(s.accel_cal_valid);
    TEST_ASSERT_EQUAL_FLOAT(1.0f, s.accel_cal.scale.x);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, s.accel_cal.bias.y);
}

static void test_level_config(void)
{
    s.tolerance_deg = 0.3f;
    s.near_deg = 1.5f;
    s.target_deg = -30.0f;
    s.yaw_stale_s = 90;
    s.drill_axis = vec3_normalize((vec3_t){0.1f, 0.0f, -1.0f});
    level_config_t c = settings_level_config(&s);
    TEST_ASSERT_EQUAL_FLOAT(0.3f, c.tolerance_deg);
    TEST_ASSERT_EQUAL_FLOAT(1.5f, c.near_deg);
    TEST_ASSERT_EQUAL_FLOAT(-30.0f, c.target_deg);
    TEST_ASSERT_EQUAL_UINT32(90000, c.yaw_stale_ms);
    // Not calibrated: the measured axis is ignored.
    TEST_ASSERT_EQUAL_FLOAT(0.0f, c.drill_axis.x);
    TEST_ASSERT_EQUAL_FLOAT(-1.0f, c.drill_axis.z);

    s.mount_cal_valid = true;
    c = settings_level_config(&s);
    TEST_ASSERT_EQUAL_FLOAT(s.drill_axis.x, c.drill_axis.x);
    TEST_ASSERT_EQUAL_FLOAT(s.drill_axis.z, c.drill_axis.z);
}

static void test_validate_ranges(void)
{
    settings_t d = settings_defaults();
#define CHECK(expr, ok) do { s = d; expr; TEST_ASSERT_EQUAL_MESSAGE(ok, settings_validate(&s), #expr); } while (0)
    CHECK(s.tolerance_deg = 0.05f, true);
    CHECK(s.tolerance_deg = 0.04f, false);
    CHECK((s.tolerance_deg = 5.0f, s.near_deg = 5.0f), true);
    CHECK((s.tolerance_deg = 5.1f, s.near_deg = 6.0f), false);
    CHECK(s.tolerance_deg = NAN, false);
    CHECK(s.near_deg = 0.4f, false);           // < tol
    CHECK(s.near_deg = 0.5f, true);            // == tol
    CHECK(s.near_deg = 20.0f, true);
    CHECK(s.near_deg = 20.5f, false);
    CHECK(s.target_deg = -90.0f, true);
    CHECK(s.target_deg = 90.5f, false);
    CHECK(s.target_deg = INFINITY, false);
    CHECK(s.mode = LEVEL_MODE_COUNT, false);
    CHECK((s.brightness_pct = 10, s.dim_brightness_pct = 10), true);
    CHECK((s.brightness_pct = 9, s.dim_brightness_pct = 5), false);
    CHECK(s.brightness_pct = 101, false);
    CHECK(s.dim_brightness_pct = 0, false);
    CHECK(s.dim_brightness_pct = 100, true);   // == bright
    CHECK((s.brightness_pct = 50, s.dim_brightness_pct = 51), false);
    CHECK(s.dim_after_s = 4, false);
    CHECK((s.dim_after_s = 600, s.sleep_after_s = 600), true);
    CHECK(s.dim_after_s = 601, false);
    CHECK(s.sleep_after_s = 29, false);        // < dim_s
    CHECK(s.sleep_after_s = 30, true);
    CHECK(s.sleep_after_s = 3600, true);
    CHECK(s.sleep_after_s = 3601, false);
    CHECK(s.off_after_min = 0, true);
    CHECK(s.off_after_min = 1440, true);
    CHECK(s.off_after_min = 1441, false);
    CHECK(s.yaw_stale_s = 9, false);
    CHECK(s.yaw_stale_s = 600, true);
    CHECK(s.yaw_stale_s = 601, false);
    // Drill axis: unit length, within 15 degrees of -Z.
    CHECK((s.mount_cal_valid = true, s.drill_axis = vec3_normalize((vec3_t){0.25f, 0.0f, -1.0f})), true);
    CHECK((s.mount_cal_valid = true, s.drill_axis = vec3_normalize((vec3_t){0.3f, 0.0f, -1.0f})), false);
    CHECK((s.mount_cal_valid = true, s.drill_axis = (vec3_t){0.0f, 0.0f, -1.1f}), false);
    CHECK((s.mount_cal_valid = true, s.drill_axis = (vec3_t){0.0f, 0.0f, 1.0f}), false);
    // Accel calibration within accel_cal.h limits.
    CHECK((s.accel_cal_valid = true, s.accel_cal.bias.x = 0.2f, s.accel_cal.scale.z = 1.1f), true);
    CHECK((s.accel_cal_valid = true, s.accel_cal.bias.x = 0.21f), false);
    CHECK((s.accel_cal_valid = true, s.accel_cal.scale.y = 0.89f), false);
    CHECK((s.accel_cal_valid = true, s.accel_cal.scale.y = NAN), false);
#undef CHECK
}

// The named limits are the exact inclusive bounds settings_validate() uses
// (the settings screen steps within them).
static void test_validate_uses_named_limits(void)
{
    settings_t d = settings_defaults();
#define CHECK(expr, ok) do { s = d; expr; TEST_ASSERT_EQUAL_MESSAGE(ok, settings_validate(&s), #expr); } while (0)
    CHECK(s.tolerance_deg = SETTINGS_TOL_MIN_DEG, true);
    CHECK(s.tolerance_deg = nextafterf(SETTINGS_TOL_MIN_DEG, 0.0f), false);
    CHECK((s.tolerance_deg = SETTINGS_TOL_MAX_DEG, s.near_deg = SETTINGS_TOL_MAX_DEG), true);
    CHECK((s.tolerance_deg = nextafterf(SETTINGS_TOL_MAX_DEG, 99.0f), s.near_deg = 10.0f), false);
    CHECK(s.near_deg = SETTINGS_NEAR_MAX_DEG, true);
    CHECK(s.near_deg = nextafterf(SETTINGS_NEAR_MAX_DEG, 99.0f), false);
    CHECK(s.target_deg = SETTINGS_TARGET_MAX_DEG, true);
    CHECK(s.target_deg = -SETTINGS_TARGET_MAX_DEG, true);
    CHECK(s.target_deg = nextafterf(SETTINGS_TARGET_MAX_DEG, 999.0f), false);
    CHECK(s.target_deg = nextafterf(-SETTINGS_TARGET_MAX_DEG, -999.0f), false);
    CHECK((s.brightness_pct = SETTINGS_BRIGHT_MIN_PCT, s.dim_brightness_pct = 1), true);
    CHECK((s.brightness_pct = SETTINGS_BRIGHT_MIN_PCT - 1, s.dim_brightness_pct = 1), false);
    CHECK(s.brightness_pct = SETTINGS_BRIGHT_MAX_PCT, true);
    CHECK(s.brightness_pct = SETTINGS_BRIGHT_MAX_PCT + 1, false);
    CHECK(s.dim_brightness_pct = SETTINGS_DIM_BRIGHT_MIN_PCT, true);
    CHECK(s.dim_brightness_pct = SETTINGS_DIM_BRIGHT_MIN_PCT - 1, false);
    CHECK(s.dim_after_s = SETTINGS_DIM_MIN_S, true);
    CHECK(s.dim_after_s = SETTINGS_DIM_MIN_S - 1, false);
    CHECK((s.dim_after_s = SETTINGS_DIM_MAX_S, s.sleep_after_s = SETTINGS_DIM_MAX_S), true);
    CHECK((s.dim_after_s = SETTINGS_DIM_MAX_S + 1, s.sleep_after_s = SETTINGS_DIM_MAX_S + 1),
          false);
    CHECK(s.sleep_after_s = SETTINGS_SLEEP_MAX_S, true);
    CHECK(s.sleep_after_s = SETTINGS_SLEEP_MAX_S + 1, false);
    CHECK(s.off_after_min = SETTINGS_OFF_MAX_MIN, true);
    CHECK(s.off_after_min = SETTINGS_OFF_MAX_MIN + 1, false);
    CHECK(s.yaw_stale_s = SETTINGS_STALE_MIN_S, true);
    CHECK(s.yaw_stale_s = SETTINGS_STALE_MIN_S - 1, false);
    CHECK(s.yaw_stale_s = SETTINGS_STALE_MAX_S, true);
    CHECK(s.yaw_stale_s = SETTINGS_STALE_MAX_S + 1, false);
#undef CHECK
}

static void test_fixup_repairs_cross_field_rules(void)
{
    s.tolerance_deg = 3.0f;
    s.near_deg = 1.0f;
    s.brightness_pct = 30;
    s.dim_brightness_pct = 50;
    s.dim_after_s = 300;
    s.sleep_after_s = 120;
    TEST_ASSERT_FALSE(settings_validate(&s));
    settings_fixup(&s);
    // The dependent field moves, the one it depends on stays.
    TEST_ASSERT_EQUAL_FLOAT(3.0f, s.tolerance_deg);
    TEST_ASSERT_EQUAL_FLOAT(3.0f, s.near_deg);
    TEST_ASSERT_EQUAL_UINT8(30, s.brightness_pct);
    TEST_ASSERT_EQUAL_UINT8(30, s.dim_brightness_pct);
    TEST_ASSERT_EQUAL_UINT16(300, s.dim_after_s);
    TEST_ASSERT_EQUAL_UINT16(300, s.sleep_after_s);
    TEST_ASSERT_TRUE(settings_validate(&s));

    // Consistent settings are left alone.
    s = settings_defaults();
    settings_t before = s;
    settings_fixup(&s);
    TEST_ASSERT_EQUAL_MEMORY(&before, &s, sizeof(s));
}

static void test_to_json_defaults(void)
{
    char buf[SETTINGS_JSON_MAX];
    int n = settings_to_json(&s, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN(0, n);
    TEST_ASSERT_EQUAL_INT((int)strlen(buf), n);
    TEST_ASSERT_EQUAL_STRING("{\"tol\":0.5,\"near\":2,\"target\":45,\"mode\":\"wall\",\"bright\":100,"
                             "\"dim_bright\":20,\"dim_s\":30,\"sleep_s\":120,\"off_min\":60,"
                             "\"ble\":true,\"stale_s\":60,\"dot_inv\":false,"
                             "\"cal\":{\"mount\":false,\"accel\":false},\"ver\":1}",
                             buf);
}

static void test_to_json_buffer_too_small(void)
{
    char buf[SETTINGS_JSON_MAX];
    int n = settings_to_json(&s, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN(0, n);
    // Exactly enough for the terminator works; one less does not.
    TEST_ASSERT_EQUAL_INT(n, settings_to_json(&s, buf, (size_t)n + 1));
    TEST_ASSERT_EQUAL_INT(-1, settings_to_json(&s, buf, (size_t)n));
    TEST_ASSERT_EQUAL_INT(-1, settings_to_json(&s, buf, 0));
}

static void test_worst_case_json_fits(void)
{
    // Values that need the most digits.
    s.tolerance_deg = 0.0512345678f;
    s.near_deg = 19.987654f;
    s.target_deg = -89.123456f;
    s.mode = LEVEL_MODE_FLOOR;
    s.dim_after_s = 600;
    s.sleep_after_s = 3600;
    s.off_after_min = 1440;
    s.yaw_stale_s = 600;
    s.mount_cal_valid = true;
    s.accel_cal_valid = true;
    s.ble_enabled = false;
    s.dot_invert = true;
    TEST_ASSERT_TRUE(settings_validate(&s));
    char buf[SETTINGS_JSON_MAX];
    TEST_ASSERT_GREATER_THAN(0, settings_to_json(&s, buf, sizeof(buf)));
}

static const cJSON *vec_of(const cJSON *obj, const char *key)
{
    const cJSON *a = cJSON_GetObjectItemCaseSensitive(obj, key);
    TEST_ASSERT_TRUE_MESSAGE(cJSON_IsArray(a) && cJSON_GetArraySize(a) == 3, key);
    return a;
}

// The SESSION tail the dataset contract reads (dbdata), on the device and
// in the simulator.
static void test_session_json_keys(void)
{
    s.accel_cal_valid = true;
    s.accel_cal.bias = (vec3_t){0.01f, -0.02f, 0.03f};
    const vec3_t gb = {0.001f, 0.002f, -0.003f};
    char out[600];
    int n = settings_session_json(&s, &gb, out, sizeof(out));
    TEST_ASSERT_GREATER_THAN(0, n);
    cJSON *root = cJSON_ParseWithLength(out, (size_t)n);
    TEST_ASSERT_NOT_NULL(root);
    TEST_ASSERT_EQUAL_INT(2, cJSON_GetArraySize(root));
    char sj[SETTINGS_JSON_MAX];
    settings_to_json(&s, sj, sizeof(sj));
    char *settings = cJSON_PrintUnformatted(cJSON_GetObjectItemCaseSensitive(root, "settings"));
    TEST_ASSERT_EQUAL_STRING(sj, settings);
    cJSON_free(settings);
    const cJSON *cal = cJSON_GetObjectItemCaseSensitive(root, "cal");
    TEST_ASSERT_EQUAL_INT(3, cJSON_GetArraySize(cal));
    const cJSON *acc = cJSON_GetObjectItemCaseSensitive(cal, "accel");
    TEST_ASSERT_TRUE(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(acc, "valid")));
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, -0.02f,
                             (float)cJSON_GetArrayItem(vec_of(acc, "bias_g"), 1)->valuedouble);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, s.accel_cal.scale.z,
                             (float)cJSON_GetArrayItem(vec_of(acc, "scale"), 2)->valuedouble);
    const cJSON *mnt = cJSON_GetObjectItemCaseSensitive(cal, "mount");
    TEST_ASSERT_TRUE(cJSON_IsFalse(cJSON_GetObjectItemCaseSensitive(mnt, "valid")));
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, s.drill_axis.z,
                             (float)cJSON_GetArrayItem(vec_of(mnt, "drill_axis"), 2)->valuedouble);
    TEST_ASSERT_FLOAT_WITHIN(1e-7f, -0.003f,
                             (float)cJSON_GetArrayItem(vec_of(cal, "gyro_bias_rad_s"), 2)->valuedouble);
    cJSON_Delete(root);

    // No gyro bias learnt yet: null. Too small a buffer: nothing.
    n = settings_session_json(&s, NULL, out, sizeof(out));
    root = cJSON_ParseWithLength(out, (size_t)n);
    cal = cJSON_GetObjectItemCaseSensitive(root, "cal");
    TEST_ASSERT_TRUE(cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(cal, "gyro_bias_rad_s")));
    cJSON_Delete(root);
    TEST_ASSERT_EQUAL_INT(0, settings_session_json(&s, NULL, out, (size_t)n));
    TEST_ASSERT_EQUAL_INT(n, settings_session_json(&s, NULL, out, (size_t)n + 1));
}

static void test_round_trip(void)
{
    s.tolerance_deg = 0.25f;
    s.near_deg = 1.0f / 3.0f;   // not exact in a short decimal
    s.target_deg = -12.5f;
    s.mode = LEVEL_MODE_ANGLE;
    s.brightness_pct = 80;
    s.dim_brightness_pct = 5;
    s.dim_after_s = 45;
    s.sleep_after_s = 900;
    s.off_after_min = 0;
    s.ble_enabled = false;
    s.dot_invert = true;
    s.yaw_stale_s = 120;
    char buf[SETTINGS_JSON_MAX];
    int n = settings_to_json(&s, buf, sizeof(buf));
    TEST_ASSERT_GREATER_THAN(0, n);

    settings_t want = s;
    s = settings_defaults();
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, settings_apply_json(&s, buf, (size_t)n));
    // Floats round trip exactly.
    TEST_ASSERT_EQUAL_MEMORY(&want.tolerance_deg, &s.tolerance_deg, sizeof(float));
    TEST_ASSERT_EQUAL_MEMORY(&want.near_deg, &s.near_deg, sizeof(float));
    TEST_ASSERT_EQUAL_MEMORY(&want.target_deg, &s.target_deg, sizeof(float));
    TEST_ASSERT_EQUAL(want.mode, s.mode);
    TEST_ASSERT_EQUAL_UINT8(want.brightness_pct, s.brightness_pct);
    TEST_ASSERT_EQUAL_UINT8(want.dim_brightness_pct, s.dim_brightness_pct);
    TEST_ASSERT_EQUAL_UINT16(want.dim_after_s, s.dim_after_s);
    TEST_ASSERT_EQUAL_UINT16(want.sleep_after_s, s.sleep_after_s);
    TEST_ASSERT_EQUAL_UINT16(want.off_after_min, s.off_after_min);
    TEST_ASSERT_EQUAL(want.ble_enabled, s.ble_enabled);
    TEST_ASSERT_EQUAL(want.dot_invert, s.dot_invert);
    TEST_ASSERT_EQUAL_UINT16(want.yaw_stale_s, s.yaw_stale_s);
}

static void test_mode_strings(void)
{
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"mode\":\"floor\"}"));
    TEST_ASSERT_EQUAL(LEVEL_MODE_FLOOR, s.mode);
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"mode\":\"angle\"}"));
    TEST_ASSERT_EQUAL(LEVEL_MODE_ANGLE, s.mode);
    char buf[SETTINGS_JSON_MAX];
    TEST_ASSERT_GREATER_THAN(0, settings_to_json(&s, buf, sizeof(buf)));
    TEST_ASSERT_NOT_NULL(strstr(buf, "\"mode\":\"angle\""));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"mode\":\"wall\"}"));
    TEST_ASSERT_EQUAL(LEVEL_MODE_WALL, s.mode);
}

static void test_partial_update(void)
{
    settings_t before = s;
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"bright\":70,\"ble\":false}"));
    TEST_ASSERT_EQUAL_UINT8(70, s.brightness_pct);
    TEST_ASSERT_FALSE(s.ble_enabled);
    // Everything else is untouched.
    TEST_ASSERT_EQUAL_FLOAT(before.tolerance_deg, s.tolerance_deg);
    TEST_ASSERT_EQUAL_UINT8(before.dim_brightness_pct, s.dim_brightness_pct);
    TEST_ASSERT_EQUAL_UINT16(before.sleep_after_s, s.sleep_after_s);
    TEST_ASSERT_EQUAL(before.mode, s.mode);
}

static void test_cross_field_update_in_one_write(void)
{
    // tol 3 alone would exceed near 2; together with near it is valid.
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"tol\":3}");
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"tol\":3,\"near\":4}"));
    TEST_ASSERT_EQUAL_FLOAT(3.0f, s.tolerance_deg);
    TEST_ASSERT_EQUAL_FLOAT(4.0f, s.near_deg);
}

static void test_unknown_and_read_only_keys_ignored(void)
{
    s.mount_cal_valid = false;
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK,
                      apply("{\"foo\":[1,2],\"cal\":{\"mount\":true,\"accel\":true},\"ver\":99,"
                            "\"bright\":50}"));
    TEST_ASSERT_EQUAL_UINT8(50, s.brightness_pct);
    TEST_ASSERT_FALSE(s.mount_cal_valid);
    TEST_ASSERT_FALSE(s.accel_cal_valid);
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{}"));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply(" \n{ \"bright\" : 60 }\n "));
    TEST_ASSERT_EQUAL_UINT8(60, s.brightness_pct);
}

static void test_parse_errors_leave_settings_unchanged(void)
{
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "{\"bright\":50");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "[1,2]");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "50");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "null");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "{\"bright\":50} x");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "{\"bright\":50}{\"bright\":60}");
    expect_rejected(SETTINGS_JSON_PARSE_ERROR, "{bright:50}");
    TEST_ASSERT_EQUAL(SETTINGS_JSON_PARSE_ERROR, settings_apply_json(&s, NULL, 0));
}

static void test_type_errors_leave_settings_unchanged(void)
{
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"tol\":\"1\"}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"tol\":null}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"near\":true}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"mode\":1}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"bright\":\"50\"}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"bright\":50.5}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"sleep_s\":1e-3}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"ble\":1}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"ble\":\"true\"}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"stale_s\":[60]}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"dot_inv\":1}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"dot_inv\":\"true\"}");
    // Overflows to infinity when parsed.
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"target\":1e999}");
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"dim_s\":-1e999}");
    // A valid key before the bad one is not applied either.
    expect_rejected(SETTINGS_JSON_TYPE_ERROR, "{\"bright\":50,\"ble\":0}");
}

static void test_range_errors_leave_settings_unchanged(void)
{
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"tol\":0.04}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"tol\":1e300}");   // inf as a float
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"near\":0.4}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"target\":91}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"mode\":\"ceiling\"}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"mode\":\"\"}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"bright\":9}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"bright\":356}");  // 100 if truncated to u8
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"bright\":-1}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"dim_bright\":21,\"bright\":20}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"dim_s\":4}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"sleep_s\":29}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"sleep_s\":65656}");  // 120 if truncated to u16
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"off_min\":1441}");
    expect_rejected(SETTINGS_JSON_RANGE_ERROR, "{\"stale_s\":9}");
}

static void test_boundaries_through_json(void)
{
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"tol\":0.05}"));
    TEST_ASSERT_EQUAL_FLOAT(0.05f, s.tolerance_deg);
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"tol\":5,\"near\":5}"));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"near\":20,\"target\":-90}"));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"bright\":10,\"dim_bright\":10}"));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"dim_s\":600,\"sleep_s\":600}"));
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"off_min\":0,\"stale_s\":10}"));
    // Integral values written as floats are integers.
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, apply("{\"bright\":5e1,\"dim_bright\":20.0}"));
    TEST_ASSERT_EQUAL_UINT8(50, s.brightness_pct);
    TEST_ASSERT_TRUE(settings_validate(&s));
}

static void test_input_not_nul_terminated(void)
{
    // Only the first len bytes count; what follows must not be read as JSON.
    const char buf[] = "{\"bright\":42}{\"bright\":7}";
    size_t len = strlen("{\"bright\":42}");
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, settings_apply_json(&s, buf, len));
    TEST_ASSERT_EQUAL_UINT8(42, s.brightness_pct);

    // A number cut by len is the shorter number.
    char raw[16];
    memcpy(raw, "{\"bright\":5}", 12);
    memset(raw + 12, '9', sizeof(raw) - 12);   // no terminator anywhere
    TEST_ASSERT_EQUAL(SETTINGS_JSON_RANGE_ERROR, settings_apply_json(&s, raw, 12));
    memcpy(raw, "{\"bright\":99", 12);
    TEST_ASSERT_EQUAL(SETTINGS_JSON_PARSE_ERROR, settings_apply_json(&s, raw, 12));
    TEST_ASSERT_EQUAL_UINT8(42, s.brightness_pct);

    // A trailing NUL counted in len (C string sent with its terminator) is fine.
    const char z[] = "{\"bright\":43}";
    TEST_ASSERT_EQUAL(SETTINGS_JSON_OK, settings_apply_json(&s, z, sizeof(z)));
    TEST_ASSERT_EQUAL_UINT8(43, s.brightness_pct);
}

// ---- equality ----------------------------------------------------------------------

// Builds a copy of src field by field on top of garbage, so the padding
// bytes differ from src's.
static settings_t copy_fields_over_garbage(const settings_t *src)
{
    settings_t d;
    memset(&d, 0x5A, sizeof(d));
    d.tolerance_deg = src->tolerance_deg;
    d.near_deg = src->near_deg;
    d.target_deg = src->target_deg;
    d.mode = src->mode;
    d.brightness_pct = src->brightness_pct;
    d.dim_brightness_pct = src->dim_brightness_pct;
    d.dim_after_s = src->dim_after_s;
    d.sleep_after_s = src->sleep_after_s;
    d.off_after_min = src->off_after_min;
    d.ble_enabled = src->ble_enabled;
    d.dot_invert = src->dot_invert;
    d.yaw_stale_s = src->yaw_stale_s;
    d.mount_cal_valid = src->mount_cal_valid;
    d.drill_axis = src->drill_axis;
    d.accel_cal_valid = src->accel_cal_valid;
    d.accel_cal = src->accel_cal;
    return d;
}

static void test_equal_ignores_padding(void)
{
    settings_t a = settings_defaults();
    settings_t b = copy_fields_over_garbage(&a);
    TEST_ASSERT_TRUE(settings_equal(&a, &b));
    TEST_ASSERT_TRUE(settings_equal(&b, &a));
}

static void test_equal_sees_every_field(void)
{
    const settings_t a = settings_defaults();
    settings_t b;
#define CHECK_FIELD(stmt)                              \
    do {                                               \
        b = a;                                         \
        stmt;                                          \
        TEST_ASSERT_FALSE_MESSAGE(settings_equal(&a, &b), #stmt); \
    } while (0)
    CHECK_FIELD(b.tolerance_deg += 0.01f);
    CHECK_FIELD(b.near_deg += 0.01f);
    CHECK_FIELD(b.target_deg += 1.0f);
    CHECK_FIELD(b.mode = LEVEL_MODE_ANGLE);
    CHECK_FIELD(b.brightness_pct--);
    CHECK_FIELD(b.dim_brightness_pct++);
    CHECK_FIELD(b.dim_after_s++);
    CHECK_FIELD(b.sleep_after_s++);
    CHECK_FIELD(b.off_after_min++);
    CHECK_FIELD(b.ble_enabled = !b.ble_enabled);
    CHECK_FIELD(b.dot_invert = !b.dot_invert);
    CHECK_FIELD(b.yaw_stale_s++);
    CHECK_FIELD(b.mount_cal_valid = !b.mount_cal_valid);
    CHECK_FIELD(b.drill_axis.x = 0.01f);
    CHECK_FIELD(b.drill_axis.y = 0.01f);
    CHECK_FIELD(b.drill_axis.z = -0.99f);
    CHECK_FIELD(b.accel_cal_valid = !b.accel_cal_valid);
    CHECK_FIELD(b.accel_cal.bias.x = 0.01f);
    CHECK_FIELD(b.accel_cal.bias.y = 0.01f);
    CHECK_FIELD(b.accel_cal.bias.z = 0.01f);
    CHECK_FIELD(b.accel_cal.scale.x = 1.01f);
    CHECK_FIELD(b.accel_cal.scale.y = 1.01f);
    CHECK_FIELD(b.accel_cal.scale.z = 1.01f);
#undef CHECK_FIELD
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_defaults);
    RUN_TEST(test_level_config);
    RUN_TEST(test_validate_ranges);
    RUN_TEST(test_validate_uses_named_limits);
    RUN_TEST(test_fixup_repairs_cross_field_rules);
    RUN_TEST(test_to_json_defaults);
    RUN_TEST(test_to_json_buffer_too_small);
    RUN_TEST(test_worst_case_json_fits);
    RUN_TEST(test_session_json_keys);
    RUN_TEST(test_round_trip);
    RUN_TEST(test_mode_strings);
    RUN_TEST(test_partial_update);
    RUN_TEST(test_cross_field_update_in_one_write);
    RUN_TEST(test_unknown_and_read_only_keys_ignored);
    RUN_TEST(test_parse_errors_leave_settings_unchanged);
    RUN_TEST(test_type_errors_leave_settings_unchanged);
    RUN_TEST(test_range_errors_leave_settings_unchanged);
    RUN_TEST(test_boundaries_through_json);
    RUN_TEST(test_input_not_nul_terminated);
    RUN_TEST(test_equal_ignores_padding);
    RUN_TEST(test_equal_sees_every_field);
    return UNITY_END();
}
