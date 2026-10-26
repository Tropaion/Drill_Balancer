#include "power_fsm.h"

// Idle times are clamped to this (about 12 days). It is far above any real
// timeout (settings allow at most an hour) but keeps now - last_activity
// from ever wrapping, so a device left on USB for 50 days does not suddenly
// look freshly active. It also lets a difference above 2^31 be told apart as
// an activity stamped slightly after the caller read now_ms.
#define IDLE_CLAMP_MS 0x40000000u

void power_fsm_init(power_fsm_t *f, const power_fsm_config_t *cfg, uint32_t now_ms)
{
    *f = (power_fsm_t){
        .cfg = *cfg,
        .state = POWER_ACTIVE,
        .last_activity_ms = now_ms,
        .init_ms = now_ms,
    };
}

void power_fsm_set_config(power_fsm_t *f, const power_fsm_config_t *cfg)
{
    // Not activity: the idle time keeps counting under the new timeouts.
    f->cfg = *cfg;
}

static void note_activity(power_fsm_t *f, uint32_t now_ms)
{
    f->last_activity_ms = now_ms;
    f->state = POWER_ACTIVE;
}

void power_fsm_activity(power_fsm_t *f, uint32_t now_ms)
{
    if (f->state == POWER_SLEEP || f->state == POWER_OFF) {
        return;
    }
    note_activity(f, now_ms);
    f->hold_armed = true;
    f->hold_arm_ms = now_ms;
}

// Vibration counts as activity up to vib_hold_ms after the last real
// activity. The credit does not re-arm the hold, so last_activity_ms never
// gets past hold_arm_ms + vib_hold_ms: vibration alone cannot keep the
// device awake.
static void vibration_hold(power_fsm_t *f, bool vibrating, uint32_t now_ms)
{
    if (!f->hold_armed) {
        return;
    }
    uint32_t held = now_ms - f->hold_arm_ms;
    if (held < 0x80000000u && held > f->cfg.vib_hold_ms) {
        // Latched until the next real activity, so the 49.7 day wrap of
        // now_ms cannot bring it back.
        f->hold_armed = false;
    } else if (vibrating && f->cfg.vib_hold_ms > 0 &&
               (int32_t)(now_ms - f->last_activity_ms) > 0) {
        // Also for held >= 2^31 (armed by a stamp slightly after now_ms, so
        // just now); the signed check never moves last_activity_ms back.
        note_activity(f, now_ms);
    }
}

uint32_t power_fsm_idle_ms(power_fsm_t *f, uint32_t now_ms)
{
    // Unsigned subtraction stays correct across the 49.7 day wrap of now_ms.
    uint32_t idle = now_ms - f->last_activity_ms;
    if (idle >= 0x80000000u) {
        return 0;               // activity is newer than now_ms
    }
    if (idle > IDLE_CLAMP_MS) {
        f->last_activity_ms = now_ms - IDLE_CLAMP_MS;
        idle = IDLE_CLAMP_MS;
    }
    return idle;
}

power_state_t power_fsm_update(power_fsm_t *f, const power_inputs_t *in, uint32_t now_ms)
{
    if (f->state == POWER_SLEEP || f->state == POWER_OFF) {
        return f->state;
    }

    // The activity stamp only says when, so a change is what counts. The
    // first stamp is only activity if it is newer than the init (signed: the
    // two are close together).
    if (!f->input_seen || in->last_activity_ms != f->seen_activity_ms) {
        bool first = !f->input_seen;
        f->input_seen = true;
        f->seen_activity_ms = in->last_activity_ms;
        if (!first || (int32_t)(in->last_activity_ms - f->init_ms) > 0) {
            power_fsm_activity(f, in->last_activity_ms);
        }
    }

    // Latched: after the 49.7 day wrap of now_ms the gauge stays trusted.
    if (!f->battery_armed && now_ms - f->init_ms >= f->cfg.battery_settle_ms) {
        f->battery_armed = true;
    }

    // A deeply discharged cell must not be drained further, whatever runs.
    // USB power feeds the system even when the charger pauses, so the cell
    // is only drained without it.
    if (f->battery_armed && in->battery_valid && !in->charging && !in->vbus_present &&
        in->battery_pct < f->cfg.off_battery_pct) {
        f->state = POWER_OFF;
        return f->state;
    }

    vibration_hold(f, in->vibrating, now_ms);

    uint32_t idle = power_fsm_idle_ms(f, now_ms);
    bool sleep_blocked = in->ota_active || in->calibrating || in->recording ||
                         in->ble_connected || in->vbus_present;

    if (idle >= f->cfg.sleep_after_ms) {
        // Blocked sleep still dims, even with dim_after_ms > sleep_after_ms.
        f->state = sleep_blocked ? POWER_DIM : POWER_SLEEP;
    } else if (idle >= f->cfg.dim_after_ms) {
        f->state = POWER_DIM;
    } else {
        // Only reachable from DIM when set_config lengthened dim_after_ms.
        f->state = POWER_ACTIVE;
    }
    return f->state;
}
