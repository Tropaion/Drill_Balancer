#pragma once

#include <stdint.h>
#include "level_math.h"

#define AXIS_X ((vec3_t){1.0f, 0.0f, 0.0f})
#define AXIS_Y ((vec3_t){0.0f, 1.0f, 0.0f})
#define AXIS_Z ((vec3_t){0.0f, 0.0f, 1.0f})

#define TEST_IMU_DT (1.0f / 448.4f)   // imu.c IMU_ODR_HZ
#define TEST_SAMPLES(seconds) ((int)((seconds) / TEST_IMU_DT + 0.5f))

quat_t rot_deg(vec3_t axis, float deg);

// Drill held for a horizontal hole: board Y up, bit (-Z) pointing north.
quat_t pose_wall(void);

// What an ideal accelerometer reads at rest in orientation q.
vec3_t accel_at_rest(quat_t q);

void assert_vec3_within(float delta, vec3_t expected, vec3_t actual);

// Deterministic noise: test_noise() is uniform in [-1, 1], test_gauss() the
// sum of three of them (standard deviation 1, bounded at 3). Seed in setUp.
void test_noise_seed(uint32_t seed);
float test_noise(void);
float test_gauss(void);
// test_gauss() * sigma on each axis.
vec3_t test_gauss_vec3(float sigma);
