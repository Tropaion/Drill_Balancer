#include <math.h>
#include "fusion.h"

// Bias learning has to run this long before the strict stillness threshold
// (relative to the learned bias) takes over.
#define BIAS_VALID_AFTER_S 1.0f

fusion_config_t fusion_default_config(void)
{
    return (fusion_config_t){
        .kp = 1.0f,
        .accel_trust_g = 0.05f,
        .accel_reject_g = 0.25f,
        .still_gyro_rad_s = 0.02f,
        .still_gyro_initial_rad_s = 0.1f,
        .still_accel_g = 0.03f,
        .still_time_s = 0.5f,
        .bias_tau_s = 5.0f,
    };
}

void fusion_init(fusion_t *f, const fusion_config_t *cfg, vec3_t accel_g)
{
    *f = (fusion_t){.cfg = *cfg, .q = quat_identity()};

    // Shortest rotation that takes the measured up vector onto world Z.
    vec3_t up = vec3_normalize(accel_g);
    if (vec3_norm(up) == 0.0f) {
        return;
    }
    float d = up.z;
    if (d < -0.9999f) {
        f->q = (quat_t){0.0f, 1.0f, 0.0f, 0.0f};
        return;
    }
    vec3_t c = vec3_cross(up, (vec3_t){0.0f, 0.0f, 1.0f});
    f->q = quat_normalize((quat_t){1.0f + d, c.x, c.y, c.z});
}

// 1 while the accelerometer magnitude is close to 1 g, fading to 0 when the
// device is being shaken or pushed.
static float accel_weight(const fusion_config_t *cfg, float dev_g)
{
    if (dev_g <= cfg->accel_trust_g) {
        return 1.0f;
    }
    if (dev_g >= cfg->accel_reject_g) {
        return 0.0f;
    }
    return (cfg->accel_reject_g - dev_g) / (cfg->accel_reject_g - cfg->accel_trust_g);
}

bool fusion_bias_valid(const fusion_t *f)
{
    return f->bias_learn_s >= BIAS_VALID_AFTER_S;
}

static void update_bias(fusion_t *f, vec3_t gyro, float accel_dev_g, float dt)
{
    const fusion_config_t *cfg = &f->cfg;
    float limit = fusion_bias_valid(f) ? cfg->still_gyro_rad_s : cfg->still_gyro_initial_rad_s;
    vec3_t d = vec3_sub(gyro, f->gyro_bias);
    // Squared: no sqrt in the 448 Hz path for a threshold test.
    bool still = vec3_dot(d, d) < limit * limit && accel_dev_g < cfg->still_accel_g;

    f->still_s = still ? f->still_s + dt : 0.0f;
    if (f->still_s < cfg->still_time_s) {
        return;
    }

    // Running mean at first, then an exponential average with bias_tau_s.
    f->bias_learn_s += dt;
    float alpha = dt / fminf(f->bias_learn_s, cfg->bias_tau_s);
    f->gyro_bias = vec3_add(f->gyro_bias, vec3_scale(d, alpha));
}

float fusion_update(fusion_t *f, vec3_t gyro_rad_s, vec3_t accel_g, float dt_s)
{
    float a_norm = vec3_norm(accel_g);
    float dev = fabsf(a_norm - 1.0f);

    update_bias(f, gyro_rad_s, dev, dt_s);
    vec3_t w = vec3_sub(gyro_rad_s, f->gyro_bias);
    float gyro_rate = vec3_norm(w);

    // Mahony: steer the estimated up vector towards the measured one.
    float k = f->cfg.kp * accel_weight(&f->cfg, dev);
    float rate = gyro_rate;
    if (k > 0.0f && a_norm > 0.0f) {
        vec3_t e = vec3_cross(vec3_scale(accel_g, 1.0f / a_norm), fusion_up_body(f));
        w = vec3_add(w, vec3_scale(e, k));
        rate = vec3_norm(w);
    }

    if (rate > 0.0f) {
        // quat_from_axis_angle(w, rate * dt_s) without normalising w again.
        float half = 0.5f * rate * dt_s;
        float s = sinf(half) / rate;
        quat_t dq = {cosf(half), w.x * s, w.y * s, w.z * s};
        f->q = quat_normalize(quat_mul(f->q, dq));
    }
    return gyro_rate;
}

vec3_t fusion_up_body(const fusion_t *f)
{
    return quat_rotate_inv(f->q, (vec3_t){0.0f, 0.0f, 1.0f});
}

bool fusion_is_still(const fusion_t *f)
{
    return f->still_s >= f->cfg.still_time_s;
}
