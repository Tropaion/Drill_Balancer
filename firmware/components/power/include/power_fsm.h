#pragma once

// Activity state machine: ACTIVE -> DIM -> SLEEP (-> OFF). Pure C,
// host-tested. power.c applies the states to the hardware.
//
// Rules:
// - Any activity returns to ACTIVE (from DIM). Activity is a new
//   power_inputs_t.last_activity_ms stamp (or a power_fsm_activity() call);
//   a stamp older than power_fsm_init() is not activity.
// - Idle >= dim_after_ms: DIM. Idle >= sleep_after_ms: SLEEP.
// - Vibration (power_inputs_t.vibrating, a running drill) counts as activity
//   only up to vib_hold_ms after the last real activity: each activity arms
//   the hold, and a vibration credit does not re-arm it. So vibration alone
//   keeps the device ACTIVE at most vib_hold_ms after the last touch, BLE
//   write, key press or movement; it dims and sleeps dim_after_ms and
//   sleep_after_ms after that. A stamp older than power_fsm_init() (the
//   boot, also a wake on motion) does not arm it.
// - Never SLEEP while an OTA update or a calibration runs, while BLE is
//   connected, or while USB power is present (DIM is still allowed).
// - Battery valid, not charging, no USB power and percent < off_battery_pct:
//   OFF at once. On USB the cell is not drained even while the charger
//   pauses, so vbus_present alone keeps the device on. The battery inputs
//   are ignored for battery_settle_ms after power_fsm_init(), so one bogus
//   first fuel-gauge reading cannot switch the device off.
// - SLEEP and OFF are terminal for this FSM (the device resets on wake).

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    POWER_ACTIVE = 0,
    POWER_DIM,
    POWER_SLEEP,
    POWER_OFF,
} power_state_t;

typedef struct {
    uint32_t dim_after_ms;
    uint32_t sleep_after_ms;
    uint8_t off_battery_pct;     // 5
    uint32_t battery_settle_ms;  // from power_fsm_init()
    uint32_t vib_hold_ms;        // 0 = vibration never counts
} power_fsm_config_t;

typedef struct {
    uint32_t last_activity_ms;   // app_last_activity_ms()
    bool vibrating;              // app_snapshot_t.vibrating
    bool battery_valid;
    uint8_t battery_pct;
    bool charging;
    bool vbus_present;
    bool ble_connected;
    bool ota_active;
    bool calibrating;
} power_inputs_t;

typedef struct {
    power_fsm_config_t cfg;
    power_state_t state;
    uint32_t last_activity_ms;
    uint32_t init_ms;
    bool battery_armed;          // battery_settle_ms has passed
    bool input_seen;             // seen_activity_ms is set
    uint32_t seen_activity_ms;   // last power_inputs_t.last_activity_ms
    bool hold_armed;             // vibration counts (until vib_hold_ms after hold_arm_ms)
    uint32_t hold_arm_ms;        // the last real activity
} power_fsm_t;

void power_fsm_init(power_fsm_t *f, const power_fsm_config_t *cfg, uint32_t now_ms);
void power_fsm_set_config(power_fsm_t *f, const power_fsm_config_t *cfg);
// Real activity: also arms the vibration hold.
void power_fsm_activity(power_fsm_t *f, uint32_t now_ms);
power_state_t power_fsm_update(power_fsm_t *f, const power_inputs_t *in, uint32_t now_ms);

// Idle time the FSM works with: 0 for an activity stamped after now_ms,
// clamped to about 12 days so it never wraps. Activity reaches it through
// power_fsm_update() or power_fsm_activity().
uint32_t power_fsm_idle_ms(power_fsm_t *f, uint32_t now_ms);

#ifdef __cplusplus
}
#endif
