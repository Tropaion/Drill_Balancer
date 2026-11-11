#pragma once

// Drill angles from the fused orientation: the view the device picks, zero,
// tolerance and mount calibration.
//
// The drill bit points along drill_axis in the board frame. With a perfect
// mount that is -Z (the device sits on the back of the drill, screen towards
// the user). Mount calibration replaces it with the measured axis.

#include <stdbool.h>
#include <stdint.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    LEVEL_MODE_WALL = 0,  // the crosshair view: elevation plus relative yaw
    LEVEL_MODE_FLOOR,     // the bubble view: 2D bubble on the board face
    LEVEL_MODE_ANGLE,     // reserved, never used (kept so the values stay put)
    LEVEL_MODE_COUNT
} level_mode_t;

// The view follows the bit's |elevation| (mount calibration included, zero
// not): the bubble from LEVEL_VIEW_FLOOR_DEG up, the crosshair below
// LEVEL_VIEW_WALL_DEG, and in between the current view stays, so vibration
// around a threshold doesn't make it flicker.
#define LEVEL_VIEW_FLOOR_DEG 55.0f   // |elevation| at or above: the bubble
#define LEVEL_VIEW_WALL_DEG  35.0f   // |elevation| below: the crosshair
#define LEVEL_VIEW_FIRST_DEG 45.0f   // with no view yet: the bubble at or above

typedef enum {
    LEVEL_STATUS_ON_TARGET = 0,  // |error| <= tolerance (green)
    LEVEL_STATUS_NEAR,           // |error| <= near band (amber)
    LEVEL_STATUS_OFF,            // further away (white)
} level_status_t;

typedef struct {
    vec3_t drill_axis;       // unit vector, board frame
    float tolerance_deg;
    float near_deg;
    uint32_t yaw_stale_ms;   // yaw is flagged stale this long after zero
} level_config_t;

typedef struct {
    level_config_t cfg;
    level_mode_t mode;       // the view in use
    bool view_set;           // false until level_update_view() has picked one
    bool zero_set;
    float zero_elevation_deg;
    float zero_heading_deg;
    bool zero_heading_valid;  // false if zeroed with the bit near vertical
    uint32_t zero_time_ms;
} level_t;

typedef struct {
    level_mode_t mode;
    float elevation_deg;     // bit above horizontal is positive (relative after zero)
    float yaw_deg;           // turned right since zero is positive
    float bubble_x_deg;      // board face tilt from level along the screen X axis
    float bubble_y_deg;      // board face tilt from level along the screen Y axis
    float deviation_deg;     // the error the view judges against tolerance
    level_status_t status;   // status of deviation_deg
    level_status_t yaw_status;
    bool yaw_valid;          // zero set and drill not close to vertical
    bool yaw_stale;          // zeroed longer than yaw_stale_ms ago
} level_reading_t;

level_config_t level_default_config(void);

// Starts in LEVEL_MODE_WALL with no view picked yet and no zero.
void level_init(level_t *lv, const level_config_t *cfg);

// Picks the view for the pose q (see LEVEL_VIEW_*) into lv->mode; true if it
// changed. Only with a fresh orientation. A set zero is kept across a bubble trip.
bool level_update_view(level_t *lv, quat_t q);

// True where the zero applies: every view but the bubble (so the reserved
// ANGLE acts as the crosshair).
bool level_view_takes_zero(level_mode_t view);

// Replaces the config. The zero angles were measured about the old drill
// axis, so a new axis clears the zero; the other fields keep it. Returns
// true if a zero was cleared.
bool level_set_config(level_t *lv, const level_config_t *cfg);

// Captures the current orientation as the reference (elevation and yaw are
// then relative to it). Refused (false) where level_view_takes_zero() is false.
bool level_zero(level_t *lv, quat_t q, uint32_t now_ms);
void level_clear_zero(level_t *lv);

// The zero in effect as out = {elevation, 0, 0} (degrees; the two bubble
// slots stay for the LEVEL record layout), returned; NULL without one. For
// app_state_set_motion().
const float *level_zero_of(const level_t *lv, float out[3]);

// The fusion restarted at yaw 0, so the zero heading belongs to the old yaw
// frame. The elevation zero is gravity based and stays valid; yaw reads
// invalid until the next zero.
void level_reset_heading(level_t *lv);

// Reading for lv->mode. The crosshair judges the elevation of the drill axis
// (relative after a zero), the bubble the tilt of the board face (absolute).
level_reading_t level_compute(const level_t *lv, quat_t q, uint32_t now_ms);

level_status_t level_status_for(const level_config_t *cfg, float error_deg);

// Mount calibration. With the bit in a horizontal or tilted hole, the user
// rolls the drill back and forth about the bit. The drill axis stays fixed
// in the world, so the board-frame "up" samples lie on a cone around it; the
// axis is the normal of the plane fitted through the samples.
typedef struct {
    uint32_t n;
    vec3_t sum;
    float sxx, sxy, sxz, syy, syz, szz;
} level_mount_cal_t;

typedef enum {
    LEVEL_CAL_OK = 0,
    LEVEL_CAL_TOO_FEW_SAMPLES,
    LEVEL_CAL_TOO_LITTLE_ROLL,   // roll range too small, or the hole is vertical
    LEVEL_CAL_NOT_ON_CONE,       // samples don't fit a cone: the bit moved
    LEVEL_CAL_OFFSET_TOO_LARGE,  // axis further than max_offset_deg from -Z
} level_cal_result_t;

void level_mount_cal_reset(level_mount_cal_t *cal);
void level_mount_cal_add(level_mount_cal_t *cal, vec3_t up_body);
// On LEVEL_CAL_OK writes the drill axis (board frame, unit length).
level_cal_result_t level_mount_cal_solve(const level_mount_cal_t *cal, float max_offset_deg,
                                         vec3_t *drill_axis_out);

// Feeds a level_mount_cal_t from the running sensor, one "up" sample per
// call (the sensor task calls it at 50 Hz). The first
// LEVEL_MOUNT_CAL_SETTLE_MS after the start and the last LEVEL_MOUNT_CAL_TAIL
// samples before the finish are dropped, since both taps (and settling the
// bit) move the drill axis: the tail is held back in a small ring and only
// enters the fit once it is older than that. The fit stops at
// LEVEL_MOUNT_CAL_MAX_SAMPLES (2 minutes at 50 Hz: plenty for a calibration,
// and the float sums stay accurate).
#define LEVEL_MOUNT_CAL_SETTLE_MS   1000u
#define LEVEL_MOUNT_CAL_TAIL        25u      // 0.5 s at 50 Hz
#define LEVEL_MOUNT_CAL_MAX_SAMPLES 6000u

typedef struct {
    level_mount_cal_t fit;
    uint32_t start_ms;
    vec3_t tail[LEVEL_MOUNT_CAL_TAIL];
    uint32_t tail_n;             // samples held back (<= LEVEL_MOUNT_CAL_TAIL)
    uint32_t tail_head;          // oldest
} level_mount_collector_t;

void level_mount_collector_reset(level_mount_collector_t *c, uint32_t start_ms);
// Returns true if a (held-back) sample entered c->fit.
bool level_mount_collector_add(level_mount_collector_t *c, vec3_t up_body, uint32_t now_ms);

#ifdef __cplusplus
}
#endif
