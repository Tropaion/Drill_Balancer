#pragma once

// User settings and calibration: defaults, range checks and the JSON form
// used by the BLE config characteristic. Pure C (cJSON), host-tested.
// Persistence is in settings_store.h.
//
// JSON keys (all optional on write; a write is a partial update). The
// ranges are the SETTINGS_*_MIN/MAX limits below:
//   "tol"        float  deg   0.05 .. 5      tolerance (green)
//   "near"       float  deg   tol .. 20      near band (amber)
//   "target"     float  deg   -90 .. 90      elevation target, angle mode
//   "mode"       string       "wall" | "floor" | "angle"
//   "bright"     int    %     10 .. 100      active brightness
//   "dim_bright" int    %     1 .. bright    dimmed brightness
//   "dim_s"      int    s     5 .. 600       idle time before dimming
//   "sleep_s"    int    s     dim_s .. 3600  idle time before deep sleep
//   "off_min"    int    min   0 .. 1440      deep sleep before power-off (0 = never)
//   "ble"        bool                        BLE on/off
//   "stale_s"    int    s     10 .. 600      yaw stale warning
// Read-only on output (ignored on write):
//   "cal": {"mount": bool, "accel": bool}
//   "ver": int  settings schema version

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "level.h"
#include "accel_cal.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SETTINGS_SCHEMA_VERSION 1
#define SETTINGS_JSON_MAX 384   // > one ATT MTU: BLE clients use a long read

// Field limits (inclusive), checked by settings_validate(). The settings
// screen steps within the same limits. near_deg has no own minimum (it is
// tolerance_deg), sleep_after_s none either (dim_after_s), and the upper
// bound of dim_brightness_pct is brightness_pct.
#define SETTINGS_TOL_MIN_DEG 0.05f
#define SETTINGS_TOL_MAX_DEG 5.0f
#define SETTINGS_NEAR_MAX_DEG 20.0f
#define SETTINGS_TARGET_MAX_DEG 90.0f      // target is -max .. max
#define SETTINGS_BRIGHT_MIN_PCT 10
#define SETTINGS_BRIGHT_MAX_PCT 100
#define SETTINGS_DIM_BRIGHT_MIN_PCT 1
#define SETTINGS_DIM_MIN_S 5
#define SETTINGS_DIM_MAX_S 600
#define SETTINGS_SLEEP_MAX_S 3600
#define SETTINGS_OFF_MAX_MIN 1440
#define SETTINGS_STALE_MIN_S 10
#define SETTINGS_STALE_MAX_S 600

// Largest drill-axis offset from -Z that settings_validate() accepts. Pass
// the same value to level_mount_cal_solve(), or a successful mount
// calibration could not be stored.
#define SETTINGS_MAX_MOUNT_OFFSET_DEG 15.0f

typedef struct {
    float tolerance_deg;
    float near_deg;
    float target_deg;
    level_mode_t mode;

    uint8_t brightness_pct;
    uint8_t dim_brightness_pct;
    uint16_t dim_after_s;
    uint16_t sleep_after_s;
    uint16_t off_after_min;
    bool ble_enabled;
    uint16_t yaw_stale_s;

    bool mount_cal_valid;
    vec3_t drill_axis;           // used when mount_cal_valid
    bool accel_cal_valid;
    accel_cal_t accel_cal;       // used when accel_cal_valid
} settings_t;

settings_t settings_defaults(void);

// True if every field is within its range (see the table above) and the
// cross-field rules hold (near >= tol, dim_bright <= bright, sleep >= dim,
// unit drill axis within 15 deg of -Z, accel cal within accel_cal.h limits).
bool settings_validate(const settings_t *s);

// Field by field (settings_t has padding, so no memcmp). Floats compare
// with ==, so a value that only round-trips differently is a change.
bool settings_equal(const settings_t *a, const settings_t *b);

// Pushes the dependent field of each cross-field rule (near up to tol,
// dim_bright down to bright, sleep up to dim), so settings whose fields are
// each in range also pass settings_validate(). Fields already consistent
// are left alone.
void settings_fixup(settings_t *s);

// The level configuration these settings imply.
level_config_t settings_level_config(const settings_t *s);

// Writes compact JSON (no whitespace) with all keys. Returns the length
// without the terminator, or -1 if buf is too small.
int settings_to_json(const settings_t *s, char *buf, size_t buf_len);

typedef enum {
    SETTINGS_JSON_OK = 0,
    SETTINGS_JSON_PARSE_ERROR,   // not a JSON object
    SETTINGS_JSON_TYPE_ERROR,    // a known key has the wrong type
    SETTINGS_JSON_RANGE_ERROR,   // result fails settings_validate()
} settings_json_result_t;

// Applies the keys present in json onto *s. Unknown keys are ignored. On any
// error *s is left unchanged. json need not be NUL-terminated.
settings_json_result_t settings_apply_json(settings_t *s, const char *json, size_t len);

#ifdef __cplusplus
}
#endif
