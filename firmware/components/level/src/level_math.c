#include <math.h>
#include "level_math.h"

float vec3_dot(vec3_t a, vec3_t b)
{
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

vec3_t vec3_cross(vec3_t a, vec3_t b)
{
    return (vec3_t){
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    };
}

vec3_t vec3_scale(vec3_t a, float s)
{
    return (vec3_t){a.x * s, a.y * s, a.z * s};
}

vec3_t vec3_add(vec3_t a, vec3_t b)
{
    return (vec3_t){a.x + b.x, a.y + b.y, a.z + b.z};
}

vec3_t vec3_sub(vec3_t a, vec3_t b)
{
    return (vec3_t){a.x - b.x, a.y - b.y, a.z - b.z};
}

bool vec3_equal(vec3_t a, vec3_t b)
{
    return a.x == b.x && a.y == b.y && a.z == b.z;
}

float vec3_norm(vec3_t a)
{
    return sqrtf(vec3_dot(a, a));
}

vec3_t vec3_normalize(vec3_t a)
{
    float n = vec3_norm(a);
    if (n <= 0.0f) {
        return (vec3_t){0};
    }
    return vec3_scale(a, 1.0f / n);
}

quat_t quat_identity(void)
{
    return (quat_t){1.0f, 0.0f, 0.0f, 0.0f};
}

quat_t quat_mul(quat_t a, quat_t b)
{
    return (quat_t){
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    };
}

quat_t quat_normalize(quat_t q)
{
    float n = sqrtf(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
    if (n <= 0.0f) {
        return quat_identity();
    }
    float inv = 1.0f / n;
    return (quat_t){q.w * inv, q.x * inv, q.y * inv, q.z * inv};
}

quat_t quat_from_axis_angle(vec3_t axis, float angle_rad)
{
    vec3_t u = vec3_normalize(axis);
    float s = sinf(0.5f * angle_rad);
    return (quat_t){cosf(0.5f * angle_rad), u.x * s, u.y * s, u.z * s};
}

vec3_t quat_rotate(quat_t q, vec3_t v)
{
    // v' = v + 2w (u x v) + 2 u x (u x v), u = vector part of q
    vec3_t u = {q.x, q.y, q.z};
    vec3_t t = vec3_scale(vec3_cross(u, v), 2.0f);
    return vec3_add(vec3_add(v, vec3_scale(t, q.w)), vec3_cross(u, t));
}

vec3_t quat_rotate_inv(quat_t q, vec3_t v)
{
    return quat_rotate((quat_t){q.w, -q.x, -q.y, -q.z}, v);
}

float wrap_deg(float deg)
{
    deg = fmodf(deg, 360.0f);
    if (deg > 180.0f) {
        deg -= 360.0f;
    } else if (deg <= -180.0f) {
        deg += 360.0f;
    }
    return deg;
}

float level_rc_tau_s(float corner_hz)
{
    return corner_hz > 0.0f ? 1.0f / (LEVEL_TWO_PI * corner_hz) : 0.0f;
}

float level_rc_alpha(float tau_s, float dt_s)
{
    if (!(dt_s > 0.0f)) {
        dt_s = 0.0f;   // also NaN: the filter holds
    }
    float alpha = dt_s / (tau_s + dt_s);
    if (!(alpha <= 1.0f)) {
        alpha = 1.0f;   // corner <= 0 and dt 0: 0/0
    }
    return alpha;
}
