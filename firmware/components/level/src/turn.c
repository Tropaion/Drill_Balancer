#include "turn.h"

turn_config_t turn_default_config(void)
{
    // TODO(hw): check on a running drill held still, also while the motor
    // starts and stops, that the low-passed rate stays below rate_rad_s, and
    // that carrying and brisk repositioning pass it (bring-up step 14). The
    // on-chip gyro LPF (24 Hz, imu.c CTRL5) is in front of this one.
    return (turn_config_t){
        .lp_hz = 3.0f,      // 6 Hz (the vibration high-pass) is cut 5x
        .rate_rad_s = 0.3f, // about 17 deg/s; a drill on a bench stays far below
    };
}

void turn_init(turn_detector_t *d, const turn_config_t *cfg)
{
    *d = (turn_detector_t){
        .cfg = *cfg,
        .tau_s = level_rc_tau_s(cfg->lp_hz),
    };
}

void turn_reset(turn_detector_t *d)
{
    turn_config_t cfg = d->cfg;
    turn_init(d, &cfg);
}

bool turn_add_sample(turn_detector_t *d, vec3_t rate_rad_s, float dt_s)
{
    float alpha = level_rc_alpha(d->tau_s, dt_s);
    d->m1 = vec3_add(d->m1, vec3_scale(vec3_sub(rate_rad_s, d->m1), alpha));
    d->m2 = vec3_add(d->m2, vec3_scale(vec3_sub(d->m1, d->m2), alpha));
    d->rate = vec3_norm(d->m2);
    return d->rate > d->cfg.rate_rad_s;
}
