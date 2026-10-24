#pragma once

#include "level_math.h"

#define AXIS_X ((vec3_t){1.0f, 0.0f, 0.0f})
#define AXIS_Y ((vec3_t){0.0f, 1.0f, 0.0f})
#define AXIS_Z ((vec3_t){0.0f, 0.0f, 1.0f})

quat_t rot_deg(vec3_t axis, float deg);

// Drill held for a horizontal hole: board Y up, bit (-Z) pointing north.
quat_t pose_wall(void);

// What an ideal accelerometer reads at rest in orientation q.
vec3_t accel_at_rest(quat_t q);

void assert_vec3_within(float delta, vec3_t expected, vec3_t actual);
