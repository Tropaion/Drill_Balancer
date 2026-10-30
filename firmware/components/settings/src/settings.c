#include <float.h>
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "cJSON.h"
#include "settings.h"

_Static_assert(offsetof(settings_t, dot_invert) == offsetof(settings_t, ble_enabled) + 1,
               "dot_invert must stay in the padding after ble_enabled");
_Static_assert(offsetof(settings_t, yaw_stale_s) == offsetof(settings_t, ble_enabled) + 2,
               "adding dot_invert must not move the stored layout");

// Mount calibration may correct at most this much: more means the device is
// not seated on the drill properly. Shared with level_mount_cal_solve().
#define MAX_MOUNT_OFFSET_DEG SETTINGS_MAX_MOUNT_OFFSET_DEG
#define UNIT_TOLERANCE 1e-3f

static const char *const MODE_NAMES[LEVEL_MODE_COUNT] = {
    [LEVEL_MODE_WALL] = "wall",
    [LEVEL_MODE_FLOOR] = "floor",
    [LEVEL_MODE_ANGLE] = "angle",
};

settings_t settings_defaults(void)
{
    return (settings_t){
        .tolerance_deg = 0.5f,
        .near_deg = 2.0f,
        .target_deg = 45.0f,
        .mode = LEVEL_MODE_WALL,
        .brightness_pct = 100,
        .dim_brightness_pct = 20,
        .dim_after_s = 30,
        .sleep_after_s = 120,
        .off_after_min = 60,
        .ble_enabled = true,
        .dot_invert = false,
        .yaw_stale_s = 60,
        .mount_cal_valid = false,
        .drill_axis = level_default_config().drill_axis,   // nominal -Z
        .accel_cal_valid = false,
        .accel_cal = accel_cal_identity(),
    };
}

// Inclusive range check that also rejects NaN.
static bool in_range(float v, float lo, float hi)
{
    return v >= lo && v <= hi;
}

static bool vec_finite(vec3_t v)
{
    return isfinite(v.x) && isfinite(v.y) && isfinite(v.z);
}

static bool accel_cal_ok(const accel_cal_t *c)
{
    if (!vec_finite(c->bias) || !vec_finite(c->scale)) {
        return false;
    }
    const float b[3] = {c->bias.x, c->bias.y, c->bias.z};
    const float g[3] = {c->scale.x, c->scale.y, c->scale.z};
    for (int i = 0; i < 3; i++) {
        if (!in_range(b[i], -ACCEL_CAL_MAX_BIAS_G, ACCEL_CAL_MAX_BIAS_G) ||
            !in_range(g[i], ACCEL_CAL_MIN_SCALE, ACCEL_CAL_MAX_SCALE)) {
            return false;
        }
    }
    return true;
}

bool settings_validate(const settings_t *s)
{
    if (!in_range(s->tolerance_deg, SETTINGS_TOL_MIN_DEG, SETTINGS_TOL_MAX_DEG) ||
        !in_range(s->near_deg, s->tolerance_deg, SETTINGS_NEAR_MAX_DEG) ||
        !in_range(s->target_deg, -SETTINGS_TARGET_MAX_DEG, SETTINGS_TARGET_MAX_DEG) ||
        (unsigned)s->mode >= LEVEL_MODE_COUNT) {
        return false;
    }
    if (s->brightness_pct < SETTINGS_BRIGHT_MIN_PCT ||
        s->brightness_pct > SETTINGS_BRIGHT_MAX_PCT ||
        s->dim_brightness_pct < SETTINGS_DIM_BRIGHT_MIN_PCT ||
        s->dim_brightness_pct > s->brightness_pct) {
        return false;
    }
    if (s->dim_after_s < SETTINGS_DIM_MIN_S || s->dim_after_s > SETTINGS_DIM_MAX_S ||
        s->sleep_after_s < s->dim_after_s || s->sleep_after_s > SETTINGS_SLEEP_MAX_S ||
        s->off_after_min > SETTINGS_OFF_MAX_MIN ||
        s->yaw_stale_s < SETTINGS_STALE_MIN_S || s->yaw_stale_s > SETTINGS_STALE_MAX_S) {
        return false;
    }
    // The calibration fields are checked even when not marked valid, so a
    // stored blob can never carry garbage that a later flag flip would use.
    if (!vec_finite(s->drill_axis) ||
        !in_range(vec3_norm(s->drill_axis), 1.0f - UNIT_TOLERANCE, 1.0f + UNIT_TOLERANCE) ||
        -s->drill_axis.z < cosf(LEVEL_DEG2RAD(MAX_MOUNT_OFFSET_DEG))) {
        return false;
    }
    return accel_cal_ok(&s->accel_cal);
}

bool settings_equal(const settings_t *a, const settings_t *b)
{
    return a->tolerance_deg == b->tolerance_deg && a->near_deg == b->near_deg &&
           a->target_deg == b->target_deg && a->mode == b->mode &&
           a->brightness_pct == b->brightness_pct &&
           a->dim_brightness_pct == b->dim_brightness_pct && a->dim_after_s == b->dim_after_s &&
           a->sleep_after_s == b->sleep_after_s && a->off_after_min == b->off_after_min &&
           a->ble_enabled == b->ble_enabled && a->dot_invert == b->dot_invert &&
           a->yaw_stale_s == b->yaw_stale_s &&
           a->mount_cal_valid == b->mount_cal_valid && vec3_equal(a->drill_axis, b->drill_axis) &&
           a->accel_cal_valid == b->accel_cal_valid &&
           accel_cal_equal(&a->accel_cal, &b->accel_cal);
}

void settings_fixup(settings_t *s)
{
    if (s->near_deg < s->tolerance_deg) {
        s->near_deg = s->tolerance_deg;
    }
    if (s->dim_brightness_pct > s->brightness_pct) {
        s->dim_brightness_pct = s->brightness_pct;
    }
    if (s->sleep_after_s < s->dim_after_s) {
        s->sleep_after_s = s->dim_after_s;
    }
}

level_config_t settings_level_config(const settings_t *s)
{
    level_config_t c = level_default_config();   // drill axis: nominal -Z
    if (s->mount_cal_valid) {
        c.drill_axis = s->drill_axis;
    }
    c.tolerance_deg = s->tolerance_deg;
    c.near_deg = s->near_deg;
    c.target_deg = s->target_deg;
    c.yaw_stale_ms = (uint32_t)s->yaw_stale_s * 1000u;
    return c;
}

// Shortest of %.6g and %.9g that reads back as the same float, so the JSON
// stays readable ("0.5", not "0.500000000") and still round-trips exactly.
static void format_float(char *buf, size_t len, float v)
{
    if (!isfinite(v)) {
        snprintf(buf, len, "null");   // JSON has no NaN/inf
        return;
    }
    snprintf(buf, len, "%.6g", (double)v);
    if (strtof(buf, NULL) != v) {
        snprintf(buf, len, "%.9g", (double)v);
    }
}

int settings_to_json(const settings_t *s, char *buf, size_t buf_len)
{
    if (buf == NULL || buf_len == 0) {
        return -1;
    }
    char tol[24], near[24], target[24];
    format_float(tol, sizeof(tol), s->tolerance_deg);
    format_float(near, sizeof(near), s->near_deg);
    format_float(target, sizeof(target), s->target_deg);
    const char *mode = (unsigned)s->mode < LEVEL_MODE_COUNT ? MODE_NAMES[s->mode] : "wall";

    int n = snprintf(buf, buf_len,
                     "{\"tol\":%s,\"near\":%s,\"target\":%s,\"mode\":\"%s\","
                     "\"bright\":%u,\"dim_bright\":%u,\"dim_s\":%u,\"sleep_s\":%u,"
                     "\"off_min\":%u,\"ble\":%s,\"stale_s\":%u,\"dot_inv\":%s,"
                     "\"cal\":{\"mount\":%s,\"accel\":%s},\"ver\":%d}",
                     tol, near, target, mode,
                     (unsigned)s->brightness_pct, (unsigned)s->dim_brightness_pct,
                     (unsigned)s->dim_after_s, (unsigned)s->sleep_after_s,
                     (unsigned)s->off_after_min, s->ble_enabled ? "true" : "false",
                     (unsigned)s->yaw_stale_s, s->dot_invert ? "true" : "false",
                     s->mount_cal_valid ? "true" : "false",
                     s->accel_cal_valid ? "true" : "false",
                     SETTINGS_SCHEMA_VERSION);
    if (n < 0 || (size_t)n >= buf_len) {
        buf[0] = '\0';
        return -1;
    }
    return n;
}

static cJSON *vec_array(vec3_t v)
{
    const float f[3] = {v.x, v.y, v.z};
    return cJSON_CreateFloatArray(f, 3);
}

int settings_session_json(const settings_t *s, const vec3_t *gyro_bias_rad_s, char *out,
                          size_t cap)
{
    char sj[SETTINGS_JSON_MAX];
    if (settings_to_json(s, sj, sizeof(sj)) < 0) {
        return 0;
    }
    cJSON *root = cJSON_CreateObject();
    if (!root) {
        return 0;
    }
    cJSON_AddItemToObject(root, "settings", cJSON_Parse(sj));
    cJSON *cal = cJSON_AddObjectToObject(root, "cal");
    cJSON *acc = cJSON_AddObjectToObject(cal, "accel");
    cJSON_AddBoolToObject(acc, "valid", s->accel_cal_valid);
    cJSON_AddItemToObject(acc, "bias_g", vec_array(s->accel_cal.bias));
    cJSON_AddItemToObject(acc, "scale", vec_array(s->accel_cal.scale));
    cJSON *mnt = cJSON_AddObjectToObject(cal, "mount");
    cJSON_AddBoolToObject(mnt, "valid", s->mount_cal_valid);
    cJSON_AddItemToObject(mnt, "drill_axis", vec_array(s->drill_axis));
    cJSON_AddItemToObject(cal, "gyro_bias_rad_s",
                          gyro_bias_rad_s ? vec_array(*gyro_bias_rad_s) : cJSON_CreateNull());
    char *text = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    size_t n = text ? strlen(text) : 0;
    if (n >= cap) {
        n = 0;
    }
    memcpy(out, text ? text : "", n);
    cJSON_free(text);
    return (int)n;
}

// The readers below leave *dst alone when the key is absent.

static settings_json_result_t read_float(const cJSON *obj, const char *key, float *dst)
{
    const cJSON *it = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (it == NULL) {
        return SETTINGS_JSON_OK;
    }
    if (!cJSON_IsNumber(it) || !isfinite(it->valuedouble)) {
        return SETTINGS_JSON_TYPE_ERROR;
    }
    // Converting a double beyond FLT_MAX to float is undefined behaviour.
    if (fabs(it->valuedouble) > (double)FLT_MAX) {
        return SETTINGS_JSON_RANGE_ERROR;
    }
    *dst = (float)it->valuedouble;
    return SETTINGS_JSON_OK;
}

static settings_json_result_t read_uint(const cJSON *obj, const char *key, unsigned max,
                                        unsigned *dst)
{
    const cJSON *it = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (it == NULL) {
        return SETTINGS_JSON_OK;
    }
    double v = it->valuedouble;
    if (!cJSON_IsNumber(it) || !isfinite(v) || floor(v) != v) {
        return SETTINGS_JSON_TYPE_ERROR;
    }
    // Checked before narrowing, so 356 can't wrap to a valid 100.
    if (v < 0.0 || v > (double)max) {
        return SETTINGS_JSON_RANGE_ERROR;
    }
    *dst = (unsigned)v;
    return SETTINGS_JSON_OK;
}

static settings_json_result_t read_u8(const cJSON *obj, const char *key, uint8_t *dst)
{
    unsigned v = *dst;
    settings_json_result_t r = read_uint(obj, key, UINT8_MAX, &v);
    *dst = (uint8_t)v;
    return r;
}

static settings_json_result_t read_u16(const cJSON *obj, const char *key, uint16_t *dst)
{
    unsigned v = *dst;
    settings_json_result_t r = read_uint(obj, key, UINT16_MAX, &v);
    *dst = (uint16_t)v;
    return r;
}

static settings_json_result_t read_bool(const cJSON *obj, const char *key, bool *dst)
{
    const cJSON *it = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (it == NULL) {
        return SETTINGS_JSON_OK;
    }
    if (!cJSON_IsBool(it)) {
        return SETTINGS_JSON_TYPE_ERROR;
    }
    *dst = cJSON_IsTrue(it);
    return SETTINGS_JSON_OK;
}

static settings_json_result_t read_mode(const cJSON *obj, const char *key, level_mode_t *dst)
{
    const cJSON *it = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (it == NULL) {
        return SETTINGS_JSON_OK;
    }
    if (!cJSON_IsString(it) || it->valuestring == NULL) {
        return SETTINGS_JSON_TYPE_ERROR;
    }
    for (int m = 0; m < LEVEL_MODE_COUNT; m++) {
        if (strcmp(it->valuestring, MODE_NAMES[m]) == 0) {
            *dst = (level_mode_t)m;
            return SETTINGS_JSON_OK;
        }
    }
    return SETTINGS_JSON_RANGE_ERROR;
}

// Only whitespace, or the NUL of a C string sent with its terminator, may
// follow the object.
static bool only_padding(const char *p, const char *end)
{
    for (; p < end; p++) {
        if (*p != ' ' && *p != '\t' && *p != '\r' && *p != '\n' && *p != '\0') {
            return false;
        }
    }
    return true;
}

static settings_json_result_t apply_object(settings_t *t, const cJSON *o)
{
    settings_json_result_t r;
#define READ(call) do { if ((r = (call)) != SETTINGS_JSON_OK) return r; } while (0)
    READ(read_float(o, "tol", &t->tolerance_deg));
    READ(read_float(o, "near", &t->near_deg));
    READ(read_float(o, "target", &t->target_deg));
    READ(read_mode(o, "mode", &t->mode));
    READ(read_u8(o, "bright", &t->brightness_pct));
    READ(read_u8(o, "dim_bright", &t->dim_brightness_pct));
    READ(read_u16(o, "dim_s", &t->dim_after_s));
    READ(read_u16(o, "sleep_s", &t->sleep_after_s));
    READ(read_u16(o, "off_min", &t->off_after_min));
    READ(read_bool(o, "ble", &t->ble_enabled));
    READ(read_u16(o, "stale_s", &t->yaw_stale_s));
    READ(read_bool(o, "dot_inv", &t->dot_invert));
#undef READ
    return settings_validate(t) ? SETTINGS_JSON_OK : SETTINGS_JSON_RANGE_ERROR;
}

settings_json_result_t settings_apply_json(settings_t *s, const char *json, size_t len)
{
    if (json == NULL || len == 0) {
        return SETTINGS_JSON_PARSE_ERROR;
    }
    const char *end = NULL;
    cJSON *root = cJSON_ParseWithLengthOpts(json, len, &end, false);
    if (root == NULL) {
        return SETTINGS_JSON_PARSE_ERROR;
    }
    settings_json_result_t r = SETTINGS_JSON_PARSE_ERROR;
    if (cJSON_IsObject(root) && end != NULL && only_padding(end, json + len)) {
        // Work on a copy so that a failing key leaves *s as it was, even if
        // earlier keys in the same write were fine.
        settings_t tmp = *s;
        r = apply_object(&tmp, root);
        if (r == SETTINGS_JSON_OK) {
            *s = tmp;
        }
    }
    cJSON_Delete(root);
    return r;
}
