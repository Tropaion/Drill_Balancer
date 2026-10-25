#pragma once

// Small 3D vector and quaternion helpers for the level and fusion code.
// Pure C, no ESP-IDF dependencies, so it also builds in test_host.

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LEVEL_DEG2RAD(d) ((d) * 0.017453292519943295f)
#define LEVEL_RAD2DEG(r) ((r) * 57.29577951308232f)
#define LEVEL_TWO_PI 6.2831853f

typedef struct {
    float x, y, z;
} vec3_t;

// Unit quaternion. Rotates body-frame vectors into the world frame
// (world Z is up).
typedef struct {
    float w, x, y, z;
} quat_t;

float vec3_dot(vec3_t a, vec3_t b);
vec3_t vec3_cross(vec3_t a, vec3_t b);
vec3_t vec3_scale(vec3_t a, float s);
vec3_t vec3_add(vec3_t a, vec3_t b);
vec3_t vec3_sub(vec3_t a, vec3_t b);
float vec3_norm(vec3_t a);
// Returns the zero vector for zero-length input.
vec3_t vec3_normalize(vec3_t a);
// Exact field-wise compare (0.0 == -0.0; NaN is never equal).
bool vec3_equal(vec3_t a, vec3_t b);

quat_t quat_identity(void);
quat_t quat_mul(quat_t a, quat_t b);
quat_t quat_normalize(quat_t q);
quat_t quat_from_axis_angle(vec3_t axis, float angle_rad);
// Rotates a body-frame vector into the world frame.
vec3_t quat_rotate(quat_t q, vec3_t v);
// Rotates a world-frame vector into the body frame.
vec3_t quat_rotate_inv(quat_t q, vec3_t v);

// Wraps an angle in degrees into (-180, 180].
float wrap_deg(float deg);

// First-order RC stage, state += alpha * (in - state). The time constant for
// a corner frequency; 0 (no filtering) for corner_hz <= 0.
float level_rc_tau_s(float corner_hz);
// The gain for one sample of period dt_s, in [0, 1]. dt_s <= 0 or NaN holds
// the state (0); tau_s 0 with dt_s 0 passes the input through (1).
float level_rc_alpha(float tau_s, float dt_s);

#ifdef __cplusplus
}
#endif
