#pragma once

// Turn detector on the bias-corrected gyro, for the power policy: turning
// the device is user activity, a running motor is not.
//
// Every gyro sample goes through a 2nd-order low-pass (two cascaded
// first-order stages); the device is turning while the filtered rate is
// above rate_rad_s. Handling (picking up, carrying, repositioning, rolling)
// is a rotation sustained over a tenth of a second or more and passes. A
// running motor shakes the gyro at tens to hundreds of Hz with close to no
// net angle, which the low-pass averages out. So a turn counts whatever the
// accelerometer does, and vibration cannot pass for one.
//
// Pure C, host-tested.

#include <stdbool.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    float lp_hz;            // low-pass corner (2nd order)
    float rate_rad_s;       // |low-passed rate| that counts as turning
} turn_config_t;

typedef struct {
    turn_config_t cfg;
    float tau_s;            // low-pass time constant
    vec3_t m1, m2;          // low-pass states (rad/s)
    float rate;             // |low-passed rate| after the last sample, for the log
} turn_detector_t;

turn_config_t turn_default_config(void);

// Starts at rest.
void turn_init(turn_detector_t *d, const turn_config_t *cfg);

// Back to rest with the same config, e.g. after an IMU restart. From rest,
// not from the first sample: one sample of motor noise is no turn.
void turn_reset(turn_detector_t *d);

// One bias-corrected gyro sample (rad/s) and its period. Returns true while
// the device is turning.
bool turn_add_sample(turn_detector_t *d, vec3_t rate_rad_s, float dt_s);

#ifdef __cplusplus
}
#endif
