#include <math.h>
#include "lpf.h"

#define SQRT2_F 1.41421356f

lpf_biquad_t lpf_butter2(float fc_hz, float fs_hz)
{
    float max = 0.45f * fs_hz;
    float fc = fc_hz > max ? max : fc_hz;
    // Pre-warped so the -3 dB point lands on fc after the bilinear transform.
    float k = tanf(0.5f * LEVEL_TWO_PI * fc / fs_hz);
    float kk = k * k;
    float q_inv = SQRT2_F;          // 1 / Q, Q = 1 / sqrt(2)
    float norm = 1.0f / (1.0f + k * q_inv + kk);
    float b0 = kk * norm;
    return (lpf_biquad_t){
        .b0 = b0,
        .b1 = 2.0f * b0,
        .b2 = b0,
        .a1 = 2.0f * (kk - 1.0f) * norm,
        .a2 = (1.0f - k * q_inv + kk) * norm,
    };
}

float lpf_gain(const lpf_biquad_t *c, float f_hz, float fs_hz)
{
    // |H(e^jw)| with z^-1 = cos w - j sin w.
    float w = LEVEL_TWO_PI * f_hz / fs_hz;
    float c1 = cosf(w), s1 = sinf(w), c2 = cosf(2.0f * w), s2 = sinf(2.0f * w);
    float nr = c->b0 + c->b1 * c1 + c->b2 * c2;
    float ni = -(c->b1 * s1 + c->b2 * s2);
    float dr = 1.0f + c->a1 * c1 + c->a2 * c2;
    float di = -(c->a1 * s1 + c->a2 * s2);
    return sqrtf((nr * nr + ni * ni) / (dr * dr + di * di));
}

void lpf_vec3_init(lpf_vec3_t *f, float fc_hz, float fs_hz)
{
    *f = (lpf_vec3_t){.c = lpf_butter2(fc_hz, fs_hz)};
}

// Transposed direct form II on one axis.
static float step1(const lpf_biquad_t *c, float x, float *s1, float *s2)
{
    float y = c->b0 * x + *s1;
    *s1 = c->b1 * x - c->a1 * y + *s2;
    *s2 = c->b2 * x - c->a2 * y;
    return y;
}

// The steady state of a constant input x (DC gain 1, so y = x).
static void prime1(const lpf_biquad_t *c, float x, float *s1, float *s2)
{
    *s2 = (c->b2 - c->a2) * x;
    *s1 = (c->b1 - c->a1) * x + *s2;
}

void lpf_vec3_prime(lpf_vec3_t *f, vec3_t x)
{
    prime1(&f->c, x.x, &f->s1.x, &f->s2.x);
    prime1(&f->c, x.y, &f->s1.y, &f->s2.y);
    prime1(&f->c, x.z, &f->s1.z, &f->s2.z);
    f->primed = true;
}

vec3_t lpf_vec3_step(lpf_vec3_t *f, vec3_t x)
{
    if (!f->primed) {
        lpf_vec3_prime(f, x);
    }
    return (vec3_t){
        step1(&f->c, x.x, &f->s1.x, &f->s2.x),
        step1(&f->c, x.y, &f->s1.y, &f->s2.y),
        step1(&f->c, x.z, &f->s1.z, &f->s2.z),
    };
}

void lpf_imu_init(lpf_imu_t *f, float fs_hz)
{
    lpf_vec3_init(&f->accel, LPF_LEVEL_ACCEL_HZ, fs_hz);
    lpf_vec3_init(&f->gyro, LPF_LEVEL_GYRO_HZ, fs_hz);
}

void lpf_imu_restart(lpf_imu_t *f, float fs_hz, vec3_t accel_g, vec3_t gyro_rad_s)
{
    lpf_imu_init(f, fs_hz);
    lpf_vec3_prime(&f->accel, accel_g);
    lpf_vec3_prime(&f->gyro, gyro_rad_s);
}

void lpf_imu_step(lpf_imu_t *f, vec3_t *accel_g, vec3_t *gyro_rad_s)
{
    *accel_g = lpf_vec3_step(&f->accel, *accel_g);
    *gyro_rad_s = lpf_vec3_step(&f->gyro, *gyro_rad_s);
}

void lpf_feed_reset(lpf_feed_t *fd, float fs_hz)
{
    lpf_imu_init(&fd->f, fs_hz);
    fd->last_valid = false;
}

void lpf_feed_switch(lpf_feed_t *fd, float fs_hz)
{
    if (fd->last_valid) {
        lpf_imu_restart(&fd->f, fs_hz, fd->last_accel_g, fd->last_gyro_rad_s);
    } else {
        lpf_imu_init(&fd->f, fs_hz);
    }
}

void lpf_feed_step(lpf_feed_t *fd, bool filter, vec3_t *accel_g, vec3_t *gyro_rad_s)
{
    if (filter) {
        lpf_imu_step(&fd->f, accel_g, gyro_rad_s);
    }
    fd->last_accel_g = *accel_g;
    fd->last_gyro_rad_s = *gyro_rad_s;
    fd->last_valid = true;
}
