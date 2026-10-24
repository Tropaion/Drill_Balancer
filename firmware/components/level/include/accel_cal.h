#pragma once

// Six-position accelerometer calibration (offset and gain per axis).
//
// The user holds the device still with each side facing up in turn, like a
// dice. Each still pose gives one averaged accelerometer vector; a perfect
// sensor would put all of them on the unit sphere. An axis-aligned ellipsoid
// least-squares fit through the poses gives bias and scale:
//
//     corrected = (raw - bias) * scale        (per axis)
//
// Pure C, host-tested.

#include <stdbool.h>
#include <stdint.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    vec3_t bias;    // g
    vec3_t scale;   // dimensionless, ~1
} accel_cal_t;

accel_cal_t accel_cal_identity(void);
vec3_t accel_cal_apply(const accel_cal_t *cal, vec3_t raw_g);
// Same bias and scale (vec3_equal).
bool accel_cal_equal(const accel_cal_t *a, const accel_cal_t *b);

// Directions of "up" in the board frame, for the six target poses.
#define ACCEL_CAL_DIR_POS_X (1u << 0)
#define ACCEL_CAL_DIR_NEG_X (1u << 1)
#define ACCEL_CAL_DIR_POS_Y (1u << 2)
#define ACCEL_CAL_DIR_NEG_Y (1u << 3)
#define ACCEL_CAL_DIR_POS_Z (1u << 4)
#define ACCEL_CAL_DIR_NEG_Z (1u << 5)
#define ACCEL_CAL_DIR_ALL   0x3Fu

#define ACCEL_CAL_MAX_POSES 24
#define ACCEL_CAL_MIN_POSE_SEPARATION_DEG 20.0f
#define ACCEL_CAL_COVERAGE_CONE_DEG 30.0f   // a pose counts for a direction within this angle
#define ACCEL_CAL_MAX_BIAS_G 0.2f
#define ACCEL_CAL_MIN_SCALE 0.9f
#define ACCEL_CAL_MAX_SCALE 1.1f

// Stillness test of accel_cal_collector_add_sample(). fusion_is_still() also
// needs ||a| - 1| below 0.03 g, which an uncalibrated sensor can miss in some
// poses, so the collector uses its own test on the raw data: every axis
// within ACCEL_CAL_MAX_RANGE_G over the window (noise with the 12 Hz LPF is
// a few mg peak to peak) and the gyro calm.
#define ACCEL_CAL_WINDOW_S 0.5f
#define ACCEL_CAL_MAX_RANGE_G 0.02f
// TODO(hw): assumes the uncorrected QMI8658 gyro offset stays below about
// 0.1 rad/s (5.7 dps) in poses where fusion has not learned the bias yet;
// check that every pose of the sensor calibration is accepted.
#define ACCEL_CAL_MAX_GYRO_RAD_S 0.1f

typedef struct {
    vec3_t poses[ACCEL_CAL_MAX_POSES];  // raw averaged accel, g
    uint8_t n;
    // Stillness window of accel_cal_collector_add_sample().
    uint32_t win_n;
    vec3_t win_sum, win_min, win_max;
} accel_cal_collector_t;

// Clears the poses and the stillness window.
void accel_cal_collector_reset(accel_cal_collector_t *c);

// Offers one still, averaged raw accelerometer vector. It is kept if it is at
// least ACCEL_CAL_MIN_POSE_SEPARATION_DEG (by direction) from every pose kept
// so far and there is room. Returns true if kept.
bool accel_cal_collector_offer(accel_cal_collector_t *c, vec3_t mean_raw_g);

// One raw sample (gyro_rate: |gyro - bias|, rad/s; dt: sample period, s)
// into the stillness window. A window that stays still for
// ACCEL_CAL_WINDOW_S is offered as one pose (its mean); later windows of the
// same pose are rejected by the separation rule. Returns true if a pose was
// kept: it is then c->poses[c->n - 1].
bool accel_cal_collector_add_sample(accel_cal_collector_t *c, vec3_t raw_g, float gyro_rate,
                                    float dt);

// ACCEL_CAL_DIR_* bits of the target directions that have a pose within
// ACCEL_CAL_COVERAGE_CONE_DEG.
uint8_t accel_cal_coverage(const accel_cal_collector_t *c);

typedef enum {
    ACCEL_CAL_OK = 0,
    ACCEL_CAL_POOR_COVERAGE,   // not all six directions captured
    ACCEL_CAL_FIT_FAILED,      // singular / non-positive fit
    ACCEL_CAL_OUT_OF_RANGE,    // bias or scale outside the limits above
} accel_cal_result_t;

accel_cal_result_t accel_cal_solve(const accel_cal_collector_t *c, accel_cal_t *out);

#ifdef __cplusplus
}
#endif
