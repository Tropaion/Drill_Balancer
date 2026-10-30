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
        .steady_tau_s = 0.25f,
        .bias_max_rad_s = 0.4f,
        .bias_match_rad_s = 0.035f,
        .relearn_after_s = 20.0f,
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

// Still means steady: gyro and accel stay near their own recent means
// (whatever the gyro offset; a tilt faster than about 7 deg/s moves the
// accel too far), |a| is near 1 g and |gyro| below bias_max_rad_s. Once a
// bias is learned the gyro's recent mean must also match it within
// bias_match_rad_s, so a slow, even turn about the vertical is not learned;
// steady but off it for relearn_after_s (a bias learned from such a turn at
// power-up), the bias is learned anew.
static void update_bias(fusion_t *f, vec3_t gyro, vec3_t accel, float accel_dev_g, float dt)
{
    const fusion_config_t *cfg = &f->cfg;
    // Squared: no sqrt in the 448 Hz path for threshold tests.
    float lim2 = cfg->still_gyro_rad_s * cfg->still_gyro_rad_s;
    float alim2 = cfg->still_accel_g * cfg->still_accel_g;
    if (!f->primed) {
        f->gyro_mean = gyro;
        f->accel_mean = accel;
        f->primed = true;
    }
    float k = level_rc_alpha(cfg->steady_tau_s, dt);
    vec3_t dm = vec3_sub(gyro, f->gyro_mean);
    vec3_t da = vec3_sub(accel, f->accel_mean);
    f->gyro_mean = vec3_add(f->gyro_mean, vec3_scale(dm, k));
    f->accel_mean = vec3_add(f->accel_mean, vec3_scale(da, k));
    bool steady = vec3_dot(dm, dm) < lim2 && accel_dev_g < cfg->still_accel_g &&
                  vec3_dot(da, da) < alim2 &&
                  vec3_dot(gyro, gyro) < cfg->bias_max_rad_s * cfg->bias_max_rad_s;
    f->steady_s = steady ? f->steady_s + dt : 0.0f;

    vec3_t d = vec3_sub(gyro, f->gyro_bias);
    vec3_t dmean = vec3_sub(f->gyro_mean, f->gyro_bias);
    bool matches = vec3_dot(dmean, dmean) < cfg->bias_match_rad_s * cfg->bias_match_rad_s;
    bool valid = fusion_bias_valid(f);
    if (!matches && valid && f->steady_s >= cfg->relearn_after_s) {
        f->bias_learn_s = 0.0f;
        valid = false;
    }
    bool still = steady && (matches || !valid);

    f->still_s = still ? f->still_s + dt : 0.0f;
    if (f->still_s < cfg->still_time_s) {
        return;
    }

    // A running mean over this still period, then an exponential average with
    // bias_tau_s. Restarting the mean with every still period follows the
    // bias within about a second after the device was moved: on the board it
    // differs by 0.016 rad/s between lying flat and standing upright.
    f->bias_learn_s += dt;
    float period_s = f->still_s - cfg->still_time_s + dt;
    float alpha = dt / fminf(fminf(f->bias_learn_s, period_s), cfg->bias_tau_s);
    f->gyro_bias = vec3_add(f->gyro_bias, vec3_scale(d, alpha));
}

float fusion_update(fusion_t *f, vec3_t gyro_rad_s, vec3_t accel_g, float dt_s)
{
    float a_norm = vec3_norm(accel_g);
    float dev = fabsf(a_norm - 1.0f);

    update_bias(f, gyro_rad_s, accel_g, dev, dt_s);
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
