#include "rec_fsm.h"

// Times are app_now_ms() stamps; unsigned differences survive the wrap.
static bool passed(uint32_t now, uint32_t since, uint32_t ms)
{
    return now - since >= ms;
}

static void enter(rec_fsm_t *f, rec_state_t s, uint32_t now)
{
    f->state = s;
    f->since_ms = now;
}

void rec_fsm_init(rec_fsm_t *f, uint32_t now_ms)
{
    *f = (rec_fsm_t){.state = REC_STATE_IDLE, .since_ms = now_ms};
}

bool rec_fsm_session_open(const rec_fsm_t *f)
{
    return f->state != REC_STATE_IDLE;
}

uint8_t rec_fsm_profile(const rec_fsm_t *f)
{
    return f->state == REC_STATE_RECORDING ? f->profile : REC_PROFILE_LEVEL;
}

uint16_t rec_fsm_elapsed_s(const rec_fsm_t *f, uint32_t now_ms)
{
    if (!f->take_valid) {
        return 0;
    }
    uint32_t end = f->state == REC_STATE_RECORDING ? now_ms : f->take_end_ms;
    uint32_t s = (end - f->take_start_ms) / 1000u;
    return s > UINT16_MAX ? UINT16_MAX : (uint16_t)s;
}

static bool low_battery(const rec_env_t *e, uint8_t below_pct)
{
    return e->battery_valid && !e->vbus && e->battery_pct < below_pct;
}

bool rec_fsm_battery_blocks_take(const rec_env_t *env)
{
    return low_battery(env, REC_BATT_START_PCT);
}

static bool in_session(rec_state_t s)
{
    return s != REC_STATE_IDLE && s != REC_STATE_CLOSING;
}

// Every take end: the take's sources stop (audio, and the request for LEVEL
// stops the IMU tap) before the caller's actions on what is queued (reset,
// flush, END), which come later in bit order.
static uint32_t end_take_sources(rec_fsm_t *f, uint8_t reason, uint32_t now)
{
    f->reason = reason;
    f->take_end_ms = now;
    return (f->mic_mask ? REC_ACT_AUDIO_OFF : 0u) | REC_ACT_SET_PROFILE;
}

static uint32_t close_session(rec_fsm_t *f, uint8_t reason, uint32_t now)
{
    f->reason = reason;
    enter(f, REC_STATE_CLOSING, now);
    return REC_ACT_STOP_AP | REC_ACT_NOTIFY;
}

static uint32_t finish_drain(rec_fsm_t *f, uint32_t now)
{
    if (f->close_pending) {
        return close_session(f, f->reason, now);
    }
    enter(f, REC_STATE_READY, now);
    return REC_ACT_NOTIFY;
}

// RECORDING -> DRAINING: END follows once the ring has drained. An empty
// ring gives no DRAINED: END goes in at once and the drain is over.
static uint32_t stop_take(rec_fsm_t *f, uint8_t reason, bool close, const rec_env_t *env,
                          uint32_t now)
{
    uint32_t a = end_take_sources(f, reason, now);
    f->end_emitted = false;
    f->close_pending = close;
    enter(f, REC_STATE_DRAINING, now);
    if (env->ring_empty) {
        f->end_emitted = true;
        return a | REC_ACT_EMIT_END | finish_drain(f, now);
    }
    return a | REC_ACT_NOTIFY;
}

// The stream client is gone: whatever is queued cannot reach it, and a
// running take ends without END (the app keeps what it received).
static uint32_t client_lost(rec_fsm_t *f, uint32_t now)
{
    uint32_t a = REC_ACT_RING_RESET | REC_ACT_NOTIFY;
    if (f->state == REC_STATE_RECORDING) {
        a |= end_take_sources(f, REC_REASON_CLIENT_LOST, now);
    }
    if (f->state == REC_STATE_DRAINING && f->close_pending) {
        return a | close_session(f, REC_REASON_CLIENT_LOST, now);
    }
    f->reason = REC_REASON_CLIENT_LOST;
    enter(f, REC_STATE_WAIT_CLIENT, now);
    return a;
}

static rec_err_t check_take(const rec_cmd_t *c, const rec_env_t *env)
{
    if (c->profile >= REC_PROFILE_COUNT || (c->mic_mask & ~REC_MIC_ALL) != 0 ||
        c->gain_db > REC_GAIN_MAX_DB || c->max_s > REC_TAKE_MAX_S) {
        return REC_ERR_BAD_ARG;
    }
    if (rec_fsm_battery_blocks_take(env)) {
        return REC_ERR_BATTERY;
    }
    if (env->ota_active || env->calibrating) {
        return REC_ERR_STATE;
    }
    return REC_OK;
}

static void start_take(rec_fsm_t *f, const rec_cmd_t *c, uint32_t now, rec_out_t *o)
{
    f->take_valid = true;
    f->take_id = c->take_id;
    f->profile = c->profile;
    f->mic_mask = c->mic_mask;
    f->gain_db = c->gain_db;
    f->max_s = c->max_s ? c->max_s : REC_TAKE_DEFAULT_S;
    f->take_start_ms = now;
    f->reason = REC_REASON_NONE;
    enter(f, REC_STATE_RECORDING, now);
    o->actions = REC_ACT_EMIT_SESSION | REC_ACT_SET_PROFILE |
                 (c->mic_mask ? REC_ACT_AUDIO_ON : 0u) | REC_ACT_NOTIFY;
    if (f->imu_lost) {
        // The take's IMU timer starts now, and the data says why it is empty.
        f->imu_lost_ms = now;
        o->actions |= REC_ACT_EMIT_EVENT;
        o->event_code = REC_EV_IMU_LOST;
    }
}

static rec_out_t command(rec_fsm_t *f, const rec_cmd_t *c, const rec_env_t *env, uint32_t now)
{
    rec_out_t o = {0};
    if (!c) {
        o.err = REC_ERR_BAD_ARG;
        return o;
    }
    switch (c->op) {
    case REC_CMD_OPEN:
        if (f->state == REC_STATE_CLOSING) {
            o.err = REC_ERR_STATE;
        } else if (f->state == REC_STATE_IDLE) {
            if (env->ota_active || env->calibrating || f->shut) {
                o.err = REC_ERR_STATE;
                break;
            }
            f->reason = REC_REASON_NONE;
            f->take_valid = false;
            f->ble_lost = false;
            enter(f, REC_STATE_AP_STARTING, now);
            o.actions = REC_ACT_START_AP | REC_ACT_NOTIFY;
        }
        break;
    case REC_CMD_CLOSE:
        if (f->state == REC_STATE_RECORDING) {
            o.actions = stop_take(f, REC_REASON_USER, true, env, now);
        } else if (f->state == REC_STATE_DRAINING) {
            f->close_pending = true;
        } else if (in_session(f->state)) {
            o.actions = close_session(f, REC_REASON_USER, now);
        }
        break;
    case REC_CMD_TAKE_START:
        if (f->state != REC_STATE_READY) {
            o.err = REC_ERR_STATE;
            break;
        }
        o.err = check_take(c, env);
        if (o.err == REC_OK) {
            start_take(f, c, now, &o);
        }
        break;
    case REC_CMD_STOP:
        if (f->state == REC_STATE_RECORDING) {
            o.actions = stop_take(f, REC_REASON_USER, false, env, now);
        } else if (f->state != REC_STATE_READY && f->state != REC_STATE_DRAINING &&
                   !f->take_valid) {
            o.err = REC_ERR_STATE;
        }
        break;
    case REC_CMD_MARK:
        if (f->state != REC_STATE_RECORDING) {
            o.err = REC_ERR_STATE;
            break;
        }
        o.actions = REC_ACT_EMIT_EVENT;
        o.event_code = REC_EV_MARK;
        o.event_arg = c->mark_code;
        break;
    default:
        o.err = REC_ERR_BAD_ARG;
        break;
    }
    return o;
}

static uint32_t tick(rec_fsm_t *f, const rec_env_t *env, uint32_t now)
{
    bool ble_gone = f->ble_lost && passed(now, f->ble_lost_ms, REC_BLE_GRACE_MS);
    switch (f->state) {
    case REC_STATE_AP_STARTING:
    case REC_STATE_WAIT_CLIENT:
    case REC_STATE_READY: {
        if (ble_gone) {
            return close_session(f, REC_REASON_BLE_LOST, now);
        }
        static const uint32_t LIMIT_MS[] = {
            [REC_STATE_AP_STARTING] = REC_AP_START_MS,
            [REC_STATE_WAIT_CLIENT] = REC_WAIT_CLIENT_MS,
            [REC_STATE_READY] = REC_READY_IDLE_MS,
        };
        static const uint8_t REASON[] = {
            [REC_STATE_AP_STARTING] = REC_REASON_AP_FAIL,
            [REC_STATE_WAIT_CLIENT] = REC_REASON_NO_CLIENT,
            [REC_STATE_READY] = REC_REASON_IDLE,
        };
        if (passed(now, f->since_ms, LIMIT_MS[f->state])) {
            return close_session(f, REASON[f->state], now);
        }
        return 0;
    }
    case REC_STATE_RECORDING:
        if (low_battery(env, REC_BATT_STOP_PCT)) {
            return stop_take(f, REC_REASON_BATTERY, false, env, now);
        }
        if (f->imu_lost && passed(now, f->imu_lost_ms, REC_IMU_LOST_MS)) {
            return stop_take(f, REC_REASON_IMU, false, env, now);
        }
        if (ble_gone) {
            return stop_take(f, REC_REASON_BLE_LOST, true, env, now);
        }
        if (passed(now, f->take_start_ms, (uint32_t)f->max_s * 1000u)) {
            return stop_take(f, REC_REASON_MAX_S, false, env, now);
        }
        return 0;
    case REC_STATE_DRAINING:
        if (ble_gone) {
            f->close_pending = true;
        }
        if (!passed(now, f->since_ms, REC_DRAIN_MS)) {
            return 0;
        }
        if (f->end_emitted) {
            // Only END is left: it goes out if the client wakes up.
            return finish_drain(f, now);
        }
        f->end_emitted = true;
        return REC_ACT_RING_FLUSH | REC_ACT_EMIT_END | finish_drain(f, now);
    case REC_STATE_CLOSING:
        if (passed(now, f->since_ms, REC_CLOSE_MS)) {
            enter(f, REC_STATE_IDLE, now);
            return REC_ACT_NOTIFY;
        }
        return 0;
    default:
        return 0;
    }
}

static uint32_t shutdown(rec_fsm_t *f, uint32_t now)
{
    f->shut = true;     // the device is going down: no new session
    switch (f->state) {
    case REC_STATE_IDLE:
    case REC_STATE_CLOSING:
        return 0;
    // STOP_AP gives the queue 200 ms: what was not sent yet is flushed, so
    // END is not stuck behind it.
    case REC_STATE_RECORDING: {
        uint32_t a = end_take_sources(f, REC_REASON_SHUTDOWN, now) | REC_ACT_RING_FLUSH |
                     REC_ACT_EMIT_END;
        f->end_emitted = true;
        return a | close_session(f, REC_REASON_SHUTDOWN, now);
    }
    case REC_STATE_DRAINING: {
        // The take already ended; its END keeps that reason.
        uint32_t a = f->end_emitted ? 0u : REC_ACT_RING_FLUSH | REC_ACT_EMIT_END;
        f->end_emitted = true;
        return a | close_session(f, f->reason, now);
    }
    default:
        return close_session(f, REC_REASON_SHUTDOWN, now);
    }
}

rec_out_t rec_fsm_step(rec_fsm_t *f, const rec_input_t *in, const rec_env_t *env,
                       uint32_t now_ms)
{
    rec_out_t o = {0};
    bool rec = f->state == REC_STATE_RECORDING;
    switch (in->kind) {
    case REC_IN_CMD:
        return command(f, in->cmd, env, now_ms);
    case REC_IN_AP_UP:
        if (f->state == REC_STATE_AP_STARTING) {
            enter(f, REC_STATE_WAIT_CLIENT, now_ms);
            o.actions = REC_ACT_NOTIFY;
        }
        break;
    case REC_IN_AP_FAIL:
        if (f->state == REC_STATE_AP_STARTING) {
            o.actions = close_session(f, REC_REASON_AP_FAIL, now_ms);
        }
        break;
    case REC_IN_HELLO_OK:
        if (f->state == REC_STATE_WAIT_CLIENT) {
            enter(f, REC_STATE_READY, now_ms);
            o.actions = REC_ACT_NOTIFY;
        }
        break;
    case REC_IN_CLIENT_LOST:
        if (f->state == REC_STATE_READY || rec || f->state == REC_STATE_DRAINING) {
            o.actions = client_lost(f, now_ms);
        }
        break;
    case REC_IN_BLE_LOST:
        if (in_session(f->state) && !f->ble_lost) {
            f->ble_lost = true;
            f->ble_lost_ms = now_ms;
            if (rec) {
                o.actions = REC_ACT_EMIT_EVENT;
                o.event_code = REC_EV_BLE_LOST;
            }
        }
        break;
    case REC_IN_BLE_BACK:
        if (f->ble_lost) {
            f->ble_lost = false;
            if (rec) {
                o.actions = REC_ACT_EMIT_EVENT;
                o.event_code = REC_EV_BLE_BACK;
            }
        }
        break;
    case REC_IN_IMU_LOST:
    case REC_IN_IMU_BACK: {
        // Tracked in every state, so a take started without an IMU knows.
        bool lost = in->kind == REC_IN_IMU_LOST;
        if (f->imu_lost == lost) {
            break;
        }
        f->imu_lost = lost;
        f->imu_lost_ms = now_ms;
        if (rec) {
            o.actions = REC_ACT_EMIT_EVENT;
            o.event_code = lost ? REC_EV_IMU_LOST : REC_EV_IMU_BACK;
        }
        if (in_session(f->state)) {
            o.actions |= REC_ACT_NOTIFY;     // rec_state shows it
        }
        break;
    }
    case REC_IN_DRAINED:
        if (f->state == REC_STATE_DRAINING) {
            if (!f->end_emitted) {
                f->end_emitted = true;
                o.actions = REC_ACT_EMIT_END;
            } else {
                o.actions = finish_drain(f, now_ms);
            }
        }
        break;
    case REC_IN_AP_DOWN:
        if (f->state == REC_STATE_CLOSING) {
            enter(f, REC_STATE_IDLE, now_ms);
            o.actions = REC_ACT_NOTIFY;
        }
        break;
    case REC_IN_SHUTDOWN:
        o.actions = shutdown(f, now_ms);
        break;
    case REC_IN_TICK:
        o.actions = tick(f, env, now_ms);
        break;
    default:
        break;
    }
    return o;
}
