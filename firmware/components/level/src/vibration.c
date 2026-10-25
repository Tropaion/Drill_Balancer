#include "vibration.h"

// A window also closes after this many samples (2.3 s at 448 Hz), so a
// sample period of 0 cannot hold the state forever.
#define WINDOW_MAX_SAMPLES 1024u

#define TWO_PI 6.2831853f

vib_config_t vib_default_config(void)
{
    // TODO(hw): tune hp_hz and amp_g on a running drill held still (bring-up
    // step 14). The motor tones are far above the on-chip accel LPF (11.9 Hz,
    // imu.c CTRL5); what reaches us is the band between the high-pass and
    // that LPF, so both must be retuned whenever the LPF changes. In the
    // simulator, which has no LPF, 0.05 g is the edge of detection.
    return (vib_config_t){
        .hp_hz = 6.0f,      // walking (about 2 Hz) is cut about 10x
        .amp_g = 0.05f,
        .duty = 0.5f,
        .window_s = 0.5f,
        .on_windows = 3,    // 1.5 s of shaking
        .quiet_windows = 4, // 2 s of calm
    };
}

void vib_init(vib_detector_t *d, const vib_config_t *cfg)
{
    *d = (vib_detector_t){
        .cfg = *cfg,
        .tau_s = cfg->hp_hz > 0.0f ? 1.0f / (TWO_PI * cfg->hp_hz) : 0.0f,
        .state = VIB_UNKNOWN,
    };
}

void vib_reset(vib_detector_t *d)
{
    vib_config_t cfg = d->cfg;
    vib_init(d, &cfg);
}

static void close_window(vib_detector_t *d)
{
    const vib_config_t *cfg = &d->cfg;
    d->last_duty = (float)d->win_above / (float)d->win_n;
    d->last_calm = (float)d->win_above < cfg->duty * (float)d->win_n;
    if (!d->last_calm) {
        d->run_calm = 0;
        if (d->run_shaking < UINT8_MAX) {
            d->run_shaking++;
        }
    } else {
        d->run_shaking = 0;
        if (d->run_calm < UINT8_MAX) {
            d->run_calm++;
        }
    }
    if (d->run_shaking >= cfg->on_windows) {
        d->state = VIB_RUNNING;
    } else if (d->run_calm >= cfg->quiet_windows) {
        d->state = VIB_QUIET;
    }
    d->win_s = 0.0f;
    d->win_n = 0;
    d->win_above = 0;
}

bool vib_add_sample(vib_detector_t *d, vec3_t accel_g, float dt_s)
{
    if (!(dt_s > 0.0f)) {
        dt_s = 0.0f;   // also NaN: the filter holds, the sample cap closes the window
    }
    if (!d->primed) {
        // No start-up step: the first sample is the low-pass state.
        d->primed = true;
        d->m1 = accel_g;
        d->m2 = (vec3_t){0.0f, 0.0f, 0.0f};
    }
    float alpha = dt_s / (d->tau_s + dt_s);
    if (!(alpha <= 1.0f)) {
        alpha = 1.0f;   // hp_hz <= 0 and dt 0: 0/0
    }
    vec3_t h1 = vec3_sub(accel_g, d->m1);
    d->m1 = vec3_add(d->m1, vec3_scale(h1, alpha));
    vec3_t h2 = vec3_sub(h1, d->m2);
    d->m2 = vec3_add(d->m2, vec3_scale(h2, alpha));

    // Squared: no sqrt in the 448 Hz path for a threshold test.
    float amp = d->cfg.amp_g;
    d->win_n++;
    d->above = vec3_dot(h2, h2) > amp * amp;
    if (d->above) {
        d->win_above++;
    }
    d->win_s += dt_s;
    if (d->win_s >= d->cfg.window_s || d->win_n >= WINDOW_MAX_SAMPLES) {
        close_window(d);
        return true;
    }
    return false;
}

vib_state_t vib_state(const vib_detector_t *d)
{
    return d->state;
}

bool vib_calm(const vib_detector_t *d)
{
    return d->last_calm &&
           (d->win_n == 0 || (float)d->win_above < d->cfg.duty * (float)d->win_n);
}
