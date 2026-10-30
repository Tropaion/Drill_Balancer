#pragma once

// 6-axis orientation filter (Mahony) with gyro bias learning while still.
//
// Board frame: X right, Y up along the screen, Z out of the screen towards
// the user. Accelerometer in g, gyro in rad/s.

#include <stdbool.h>
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    float kp;                 // accelerometer correction gain
    float accel_trust_g;      // full accel trust while ||a| - 1| is below this
    float accel_reject_g;     // no accel correction above this
    float still_gyro_rad_s;   // max |gyro - its recent mean| when still
    float bias_match_rad_s;   // max |recent mean - bias| once a bias is learned
                              // (the board's bias moves 0.016 between poses)
    float steady_tau_s;       // time constant of the gyro's and accel's recent means
    float bias_max_rad_s;     // a steady gyro above this is a turn, never a bias
    float relearn_after_s;    // steady this long but off the bias: learn anew
    float still_accel_g;      // max ||a| - 1| and |a - its recent mean| when still
    float still_time_s;       // stillness needed before bias learning starts
    float bias_tau_s;         // time constant of the bias average
} fusion_config_t;

typedef struct {
    fusion_config_t cfg;
    quat_t q;                 // body -> world
    vec3_t gyro_bias;         // rad/s
    float bias_learn_s;       // total time spent learning the bias
    float still_s;            // how long the device has been still
    vec3_t gyro_mean;         // recent mean of the raw gyro (steady_tau_s)
    vec3_t accel_mean;        // recent mean of the accel (steady_tau_s)
    bool primed;              // the means hold the first sample
    float steady_s;           // how long the device has been steady (fusion.c)
} fusion_t;

fusion_config_t fusion_default_config(void);

// Starts with the tilt taken from one accelerometer sample and yaw 0.
void fusion_init(fusion_t *f, const fusion_config_t *cfg, vec3_t accel_g);

// Returns the bias-corrected gyro rate |gyro - bias| (rad/s, after this
// step's bias update), for the caller's activity and stillness checks.
float fusion_update(fusion_t *f, vec3_t gyro_rad_s, vec3_t accel_g, float dt_s);

// World "up" expressed in the board frame (what a perfect, vibration-free
// accelerometer would read at rest).
vec3_t fusion_up_body(const fusion_t *f);

bool fusion_is_still(const fusion_t *f);

// The bias has been learned long enough for the strict stillness threshold.
bool fusion_bias_valid(const fusion_t *f);

#ifdef __cplusplus
}
#endif
