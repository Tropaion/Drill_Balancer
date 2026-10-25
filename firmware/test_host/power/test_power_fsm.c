#include <stdint.h>
#include "unity.h"
#include "power_fsm.h"

#define DIM_MS   30000u
#define SLEEP_MS 120000u

static power_fsm_t f;
static power_fsm_config_t cfg;
static power_inputs_t in;

void setUp(void)
{
    cfg = (power_fsm_config_t){
        .dim_after_ms = DIM_MS,
        .sleep_after_ms = SLEEP_MS,
        .off_battery_pct = 5,
    };
    // Healthy battery, nothing holding the device awake.
    in = (power_inputs_t){.battery_valid = true, .battery_pct = 80};
    power_fsm_init(&f, &cfg, 1000);
}

void tearDown(void) {}

static void test_starts_active(void)
{
    TEST_ASSERT_EQUAL(POWER_ACTIVE, f.state);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000));
}

static void test_dims_exactly_at_threshold(void)
{
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + DIM_MS - 1));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
}

static void test_sleeps_exactly_at_threshold(void)
{
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + SLEEP_MS - 1));
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS));
}

static void test_sleeps_without_passing_through_dim_update(void)
{
    // A late first update must not stop at DIM.
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS + 5000));
}

static void test_activity_from_dim_returns_to_active(void)
{
    uint32_t t = 1000 + DIM_MS;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t));
    power_fsm_activity(&f, t + 10);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, f.state);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t + 10));
    // The idle timer restarted at the activity.
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t + 10 + DIM_MS - 1));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t + 10 + DIM_MS));
}

static void test_activity_while_active_restarts_timer(void)
{
    power_fsm_activity(&f, 1000 + DIM_MS - 1);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + DIM_MS));
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + 2 * DIM_MS - 2));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + 2 * DIM_MS - 1));
}

// Each inhibitor: DIM still happens, SLEEP waits until it clears.
static void check_inhibitor(bool *flag)
{
    *flag = true;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + SLEEP_MS));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + 10 * SLEEP_MS));
    *flag = false;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + 10 * SLEEP_MS + 1));
}

static void test_ota_blocks_sleep(void) { check_inhibitor(&in.ota_active); }
static void test_calibration_blocks_sleep(void) { check_inhibitor(&in.calibrating); }
static void test_ble_connection_blocks_sleep(void) { check_inhibitor(&in.ble_connected); }
static void test_vbus_blocks_sleep(void) { check_inhibitor(&in.vbus_present); }

static void test_activity_during_inhibit_returns_to_active(void)
{
    in.ble_connected = true;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + SLEEP_MS));
    power_fsm_activity(&f, 1000 + SLEEP_MS);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + SLEEP_MS + 1));
}

static void test_low_battery_turns_off_at_once(void)
{
    in.battery_pct = 4;
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1001));
}

static void test_battery_at_threshold_stays_on(void)
{
    in.battery_pct = 5;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
}

static void test_low_battery_overrides_inhibitors_and_dim(void)
{
    in.ota_active = true;
    in.ble_connected = true;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
    in.battery_pct = 0;
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1000 + DIM_MS + 1));
}

static void test_low_battery_while_charging_stays_on(void)
{
    in.battery_pct = 1;
    in.charging = true;
    // vbus_present stays false: charging alone must keep the device on.
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
}

static void test_low_battery_on_usb_not_charging_stays_on(void)
{
    // The charger pauses (e.g. done, or a thermal/timer stop) while USB still
    // powers the system: the cell is not being drained, so no power-off.
    in.battery_pct = 1;
    in.charging = false;
    in.vbus_present = true;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
}

static void test_low_battery_after_unplug_turns_off(void)
{
    in.battery_pct = 1;
    in.vbus_present = true;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
    in.vbus_present = false;
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1002));
}

static void test_low_battery_with_invalid_reading_stays_on(void)
{
    in.battery_pct = 0;
    in.battery_valid = false;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
}

static void test_off_threshold_zero_never_turns_off(void)
{
    cfg.off_battery_pct = 0;
    power_fsm_set_config(&f, &cfg);
    in.battery_pct = 0;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
}

static void test_sleep_is_terminal(void)
{
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS));
    power_fsm_activity(&f, 1000 + SLEEP_MS + 1);
    TEST_ASSERT_EQUAL(POWER_SLEEP, f.state);
    in.vbus_present = true;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS + 2));
    in.battery_pct = 0;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS + 3));
}

static void test_off_is_terminal(void)
{
    in.battery_pct = 2;
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1001));
    in.battery_pct = 90;
    in.charging = true;
    power_fsm_activity(&f, 1002);
    TEST_ASSERT_EQUAL(POWER_OFF, f.state);
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1003));
}

static void test_set_config_shorter_timeouts_take_effect(void)
{
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + 10000));
    cfg.dim_after_ms = 5000;
    cfg.sleep_after_ms = 20000;
    power_fsm_set_config(&f, &cfg);
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + 10000));
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + 20000));
}

static void test_set_config_keeps_idle_time(void)
{
    // Changing the config is not activity: the idle time keeps counting.
    power_fsm_set_config(&f, &cfg);
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
}

static void test_set_config_longer_dim_undims(void)
{
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
    cfg.dim_after_ms = 2 * DIM_MS;
    power_fsm_set_config(&f, &cfg);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + DIM_MS + 1));
}

static void test_set_config_changes_off_threshold(void)
{
    in.battery_pct = 8;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1001));
    cfg.off_battery_pct = 10;
    power_fsm_set_config(&f, &cfg);
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1002));
}

static void test_now_wraparound(void)
{
    // Last activity 10 s before the uint32 millisecond counter wraps.
    const uint32_t t0 = UINT32_MAX - 9999u;
    power_fsm_init(&f, &cfg, t0);
    in.last_activity_ms = t0;   // the stamp the FSM started with, not activity
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t0 + 5000u));
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 100u));       // 10.1 s idle
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t0 + DIM_MS - 1u));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t0 + DIM_MS));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t0 + SLEEP_MS - 1u));
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, t0 + SLEEP_MS));
}

static void test_activity_across_wraparound(void)
{
    const uint32_t t0 = UINT32_MAX - 99u;
    power_fsm_init(&f, &cfg, t0);
    in.last_activity_ms = t0;   // the stamp the FSM started with, not activity
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t0 + DIM_MS));
    power_fsm_activity(&f, t0 + DIM_MS);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t0 + DIM_MS + 1u));
}

static void test_activity_timestamp_slightly_ahead_is_not_huge_idle(void)
{
    // Another task may stamp activity a little after the caller read now_ms.
    power_fsm_activity(&f, 50000);
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 49990));
}

static void test_inhibited_dim_survives_counter_wrap(void)
{
    // On USB for more than 49.7 days without a touch: still DIM, never a
    // spurious ACTIVE when the idle time would wrap.
    in.vbus_present = true;
    uint32_t t = 1000;
    for (int hour = 1; hour <= 51 * 24; hour++) {
        t += 3600u * 1000u;
        TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t));
    }
    in.vbus_present = false;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, t + 1u));
}

// ---- activity from the inputs ------------------------------------------------------

static void test_input_activity_stamp_change_is_activity(void)
{
    uint32_t t = 1000 + DIM_MS;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t));
    in.last_activity_ms = t + 5;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t + 10));
    // The idle time counts from the stamp, not from the update that saw it.
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t + 5 + DIM_MS - 1));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t + 5 + DIM_MS));
}

static void test_input_activity_same_stamp_counts_once(void)
{
    in.last_activity_ms = 2000;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 2000));
    // Unchanged: the device dims and sleeps on schedule.
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 2000 + DIM_MS));
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 2000 + SLEEP_MS));
}

static void test_input_activity_before_init_is_not_activity(void)
{
    // app_state's stamp from boot, before the FSM started at 1000.
    in.last_activity_ms = 400;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + DIM_MS - 1));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1000 + DIM_MS));
}

static void test_input_activity_first_stamp_after_init_counts(void)
{
    in.last_activity_ms = 1500;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + DIM_MS));
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 1500 + DIM_MS));
}

static void test_input_activity_after_sleep_is_ignored(void)
{
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS));
    in.last_activity_ms = 1000 + SLEEP_MS + 1;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 1000 + SLEEP_MS + 2));
}

static void test_input_activity_stamp_wraps(void)
{
    const uint32_t t0 = UINT32_MAX - 99u;
    power_fsm_init(&f, &cfg, t0);
    in.last_activity_ms = t0;
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t0 + DIM_MS));
    in.last_activity_ms = t0 + DIM_MS;   // past the wrap
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t0 + DIM_MS + 1u));
}

// ---- vibration hold ----------------------------------------------------------------

#define HOLD_MS  180000u   // control.c VIB_HOLD_MS
#define STEP_MS  200u      // control task period

static void hold_on(void)
{
    cfg.vib_hold_ms = HOLD_MS;
    power_fsm_init(&f, &cfg, 1000);
}

typedef struct {
    uint32_t dim_at, sleep_at;   // first update in that state, 0 = none
    bool reactivated;            // ACTIVE again after DIM
} timeline_t;

// Control task passes every STEP_MS in [from, to]; stops at SLEEP or OFF.
static timeline_t run(uint32_t from, uint32_t to)
{
    timeline_t tl = {0};
    for (uint32_t t = from; (int32_t)(to - t) >= 0; t += STEP_MS) {
        power_state_t st = power_fsm_update(&f, &in, t);
        if (st == POWER_DIM && !tl.dim_at) {
            tl.dim_at = t;
        }
        if (st == POWER_ACTIVE && tl.dim_at) {
            tl.reactivated = true;
        }
        if (st == POWER_SLEEP || st == POWER_OFF) {
            tl.sleep_at = t;
            break;
        }
    }
    return tl;
}

static void test_vibration_ignored_when_hold_disabled(void)
{
    in.last_activity_ms = 2000;
    in.vibrating = true;
    timeline_t tl = run(2000, 2000 + 2 * SLEEP_MS);
    TEST_ASSERT_EQUAL_UINT32(2000 + DIM_MS, tl.dim_at);
    TEST_ASSERT_EQUAL_UINT32(2000 + SLEEP_MS, tl.sleep_at);
}

static void test_vibration_without_activity_since_init_never_counts(void)
{
    // A wake on motion is a reboot: app_state's boot stamp is older than the
    // FSM's init, so nothing arms the hold, however long it vibrates.
    hold_on();
    in.last_activity_ms = 400;
    in.vibrating = true;
    timeline_t tl = run(1000, 1000 + HOLD_MS + SLEEP_MS);
    TEST_ASSERT_FALSE(f.hold_armed);
    TEST_ASSERT_EQUAL_UINT32(1000 + DIM_MS, tl.dim_at);
    TEST_ASSERT_EQUAL_UINT32(1000 + SLEEP_MS, tl.sleep_at);
}

static void test_vibration_holds_until_cap(void)
{
    hold_on();
    uint32_t t = 2000;
    in.last_activity_ms = t;
    in.vibrating = true;
    // Credited up to and including held == HOLD_MS, then idle from there.
    timeline_t tl = run(t, t + HOLD_MS + 2 * SLEEP_MS);
    TEST_ASSERT_EQUAL_UINT32(t + HOLD_MS + DIM_MS, tl.dim_at);
    TEST_ASSERT_EQUAL_UINT32(t + HOLD_MS + SLEEP_MS, tl.sleep_at);
    TEST_ASSERT_FALSE(tl.reactivated);
}

static void test_credit_does_not_rearm(void)
{
    // Vibration that stops and starts again (an intermittent machine, a
    // stop in traffic) gets no new hold: the cap counts from the activity.
    hold_on();
    uint32_t t = 2000;
    in.last_activity_ms = t;
    in.vibrating = true;
    run(t, t + 60000);
    in.vibrating = false;
    timeline_t tl = run(t + 60200, t + 60000 + DIM_MS + 1000);
    TEST_ASSERT_EQUAL_UINT32(t + 60000 + DIM_MS, tl.dim_at);
    in.vibrating = true;   // back to ACTIVE: still inside the hold
    tl = run(t + 60000 + DIM_MS + 1200, t + HOLD_MS + 2 * SLEEP_MS);
    TEST_ASSERT_EQUAL_UINT32(t + HOLD_MS + DIM_MS, tl.dim_at);
    TEST_ASSERT_EQUAL_UINT32(t + HOLD_MS + SLEEP_MS, tl.sleep_at);
}

static void test_new_activity_rearms(void)
{
    hold_on();
    in.vibrating = true;
    in.last_activity_ms = 2000;
    run(2000, 100000);
    uint32_t t2 = 100000 + 50;   // moving to the next hole
    in.last_activity_ms = t2;
    timeline_t tl = run(100200, t2 + HOLD_MS + 2 * SLEEP_MS);
    // The last credit is the last pass inside the new hold, 50 ms before it ends.
    TEST_ASSERT_EQUAL_UINT32(t2 + HOLD_MS - 50 + DIM_MS, tl.dim_at);
    TEST_ASSERT_EQUAL_UINT32(t2 + HOLD_MS - 50 + SLEEP_MS, tl.sleep_at);
}

static void test_power_fsm_activity_arms(void)
{
    hold_on();
    power_fsm_activity(&f, 2000);
    TEST_ASSERT_TRUE(f.hold_armed);
    in.vibrating = true;
    timeline_t tl = run(2000, 2000 + HOLD_MS + SLEEP_MS);
    TEST_ASSERT_EQUAL_UINT32(2000 + HOLD_MS + SLEEP_MS, tl.sleep_at);
}

static void test_vibration_from_dim_returns_active(void)
{
    // The motor starts after the screen dimmed, within the hold.
    hold_on();
    in.last_activity_ms = 2000;
    timeline_t tl = run(2000, 2000 + DIM_MS);
    TEST_ASSERT_EQUAL_UINT32(2000 + DIM_MS, tl.dim_at);
    in.vibrating = true;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 2000 + DIM_MS + STEP_MS));
    TEST_ASSERT_EQUAL_UINT32(0, power_fsm_idle_ms(&f, 2000 + DIM_MS + STEP_MS));
}

static void test_expired_hold_latched_across_wrap(void)
{
    // On USB (no sleep) for 49.7 days: when now_ms comes round to just
    // after the activity again, the old hold does not count.
    hold_on();
    in.vbus_present = true;
    in.last_activity_ms = 2000;
    run(2000, 2000 + HOLD_MS + STEP_MS);
    TEST_ASSERT_FALSE(f.hold_armed);
    in.vibrating = true;
    uint32_t t = 2000 + HOLD_MS + STEP_MS;
    for (int hour = 1; hour <= 1192; hour++) {
        t += 3600u * 1000u;
        TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t));
    }
    // 2^32 ms after the pass just after the activity: now_ms - hold_arm_ms
    // is 200 again.
    TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, 2000 + STEP_MS));
    TEST_ASSERT_FALSE(f.hold_armed);
}

static void test_stamp_after_now_counts_as_just_armed(void)
{
    // The stamp was taken after the control task read now_ms.
    hold_on();
    in.last_activity_ms = 2010;
    in.vibrating = true;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 2000));
    TEST_ASSERT_TRUE(f.hold_armed);
    TEST_ASSERT_EQUAL_UINT32(2010, f.last_activity_ms);   // not moved back
    timeline_t tl = run(2200, 2010 + HOLD_MS + SLEEP_MS + STEP_MS);
    TEST_ASSERT_EQUAL_UINT32(2000 + HOLD_MS + DIM_MS, tl.dim_at);   // last credit at 182000
}

static void test_low_battery_off_while_vibration_credited(void)
{
    hold_on();   // battery_settle_ms 0
    in.last_activity_ms = 2000;
    in.vibrating = true;
    run(2000, 60000);
    in.battery_pct = 4;
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 60200));
}

static void test_sleep_blocked_still_dims_after_cap(void)
{
    // USB or a phone in use block sleep, not dimming: vibration keeps the
    // screen bright only up to the cap.
    bool *blocks[] = {&in.vbus_present, &in.ble_connected};
    for (int i = 0; i < 2; i++) {
        setUp();
        hold_on();
        *blocks[i] = true;
        in.last_activity_ms = 2000;
        in.vibrating = true;
        timeline_t tl = run(2000, 2000 + 3600u * 1000u);
        TEST_ASSERT_EQUAL_UINT32(2000 + HOLD_MS + DIM_MS, tl.dim_at);
        TEST_ASSERT_EQUAL_UINT32(0, tl.sleep_at);
        TEST_ASSERT_FALSE(tl.reactivated);
    }
}

static void test_vibration_after_sleep_is_ignored(void)
{
    hold_on();
    in.last_activity_ms = 2000;
    run(2000, 2000 + SLEEP_MS);
    TEST_ASSERT_EQUAL(POWER_SLEEP, f.state);
    in.vibrating = true;
    TEST_ASSERT_EQUAL(POWER_SLEEP, power_fsm_update(&f, &in, 2000 + SLEEP_MS + STEP_MS));
}

// The battery bound as a test: random vibration and random activity stamps,
// nothing blocking sleep. The device is never ACTIVE later than DIM_MS, nor
// awake later than SLEEP_MS, after hold expiry (the last real activity plus
// HOLD_MS), and it is asleep one pass after that.
static void test_vibration_bound_property(void)
{
    uint32_t rnd = 1;
    for (int seed = 0; seed < 200; seed++) {
        setUp();
        rnd = rnd * 1664525u + 1013904223u;
        // Some runs start just before the 49.7 day wrap of now_ms.
        uint32_t t0 = seed % 4 == 0 ? 0xFFF00000u + (rnd >> 12) : rnd >> 4;
        cfg.vib_hold_ms = HOLD_MS;
        power_fsm_init(&f, &cfg, t0);
        in.last_activity_ms = t0 - 100;   // the boot stamp
        uint32_t real = t0;               // last real activity (or the init)
        bool slept = false;
        for (uint32_t k = 1; k < 9000 && !slept; k++) {   // 30 min
            uint32_t t = t0 + k * STEP_MS;
            rnd = rnd * 1664525u + 1013904223u;
            if ((rnd >> 16) % 20 == 0) {
                in.vibrating = !in.vibrating;
            }
            if ((rnd >> 8) % 1000 < 2 && k < 3000) {
                in.last_activity_ms = t - (rnd >> 24) % STEP_MS;   // since the last pass
                real = in.last_activity_ms;
            }
            power_state_t st = power_fsm_update(&f, &in, t);
            int32_t since = (int32_t)(t - real);
            if (st == POWER_ACTIVE) {
                TEST_ASSERT_TRUE(since < (int32_t)(HOLD_MS + DIM_MS));
            }
            if (st != POWER_SLEEP) {
                TEST_ASSERT_TRUE(since < (int32_t)(HOLD_MS + SLEEP_MS));
            }
            slept = st == POWER_SLEEP;
        }
        TEST_ASSERT_TRUE(slept);
    }
}

// ---- battery settle time -----------------------------------------------------------

static void test_low_battery_ignored_until_settled(void)
{
    cfg.battery_settle_ms = 5000;
    power_fsm_init(&f, &cfg, 1000);
    in.battery_pct = 0;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000));
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + 4999));
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 1000 + 5000));
}

static void test_bogus_first_reading_does_not_turn_off(void)
{
    cfg.battery_settle_ms = 5000;
    power_fsm_init(&f, &cfg, 1000);
    in.battery_pct = 0;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1200));
    in.battery_pct = 80;   // the gauge has settled
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + 5000));
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 1000 + 6000));
}

static void test_battery_settle_counts_from_init_not_boot(void)
{
    cfg.battery_settle_ms = 5000;
    power_fsm_init(&f, &cfg, 100000);   // a late start: uptime is already past 5 s
    in.battery_pct = 1;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 100001));
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 105000));
}

static void test_battery_settle_survives_counter_wrap(void)
{
    // Armed once: when now_ms wraps back to just after init_ms, the gauge
    // stays trusted.
    cfg.battery_settle_ms = 5000;
    power_fsm_init(&f, &cfg, 1000);
    in.vbus_present = true;   // on USB: stays on with an empty cell
    in.battery_pct = 1;
    uint32_t t = 1000 + 5000;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, t));
    for (int hour = 1; hour <= 1193; hour++) {   // 49.7 days
        t += 3600u * 1000u;
        TEST_ASSERT_EQUAL(POWER_DIM, power_fsm_update(&f, &in, t));
    }
    in.vbus_present = false;
    // Past the wrap: now_ms - init_ms is 1000 again.
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 2000));
}

static void test_set_config_keeps_settle_start(void)
{
    cfg.battery_settle_ms = 5000;
    power_fsm_init(&f, &cfg, 1000);
    power_fsm_set_config(&f, &cfg);
    in.battery_pct = 1;
    TEST_ASSERT_EQUAL(POWER_ACTIVE, power_fsm_update(&f, &in, 5999));
    TEST_ASSERT_EQUAL(POWER_OFF, power_fsm_update(&f, &in, 6000));
}

// ---- idle time ---------------------------------------------------------------------

static void test_idle_ms_follows_activity(void)
{
    TEST_ASSERT_EQUAL_UINT32(0, power_fsm_idle_ms(&f, 1000));
    TEST_ASSERT_EQUAL_UINT32(SLEEP_MS, power_fsm_idle_ms(&f, 1000 + SLEEP_MS));
    in.last_activity_ms = 5000;
    power_fsm_update(&f, &in, 5000);
    TEST_ASSERT_EQUAL_UINT32(1234, power_fsm_idle_ms(&f, 6234));
    power_fsm_activity(&f, 7000);
    TEST_ASSERT_EQUAL_UINT32(1, power_fsm_idle_ms(&f, 7001));
}

static void test_idle_ms_future_stamp_is_zero(void)
{
    power_fsm_activity(&f, 50000);
    TEST_ASSERT_EQUAL_UINT32(0, power_fsm_idle_ms(&f, 49990));
}

static void test_idle_ms_clamps_instead_of_wrapping(void)
{
    // 30 days without activity: a signed difference would have gone
    // negative after 24.8 days; this stays large.
    uint32_t t = 1000;
    uint32_t last = 0;
    for (int day = 1; day <= 30; day++) {
        t += 24u * 3600u * 1000u;
        uint32_t idle = power_fsm_idle_ms(&f, t);
        TEST_ASSERT_TRUE(idle >= last);
        TEST_ASSERT_TRUE(idle > 10u * SLEEP_MS);
        last = idle;
    }
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_starts_active);
    RUN_TEST(test_dims_exactly_at_threshold);
    RUN_TEST(test_sleeps_exactly_at_threshold);
    RUN_TEST(test_sleeps_without_passing_through_dim_update);
    RUN_TEST(test_activity_from_dim_returns_to_active);
    RUN_TEST(test_activity_while_active_restarts_timer);
    RUN_TEST(test_ota_blocks_sleep);
    RUN_TEST(test_calibration_blocks_sleep);
    RUN_TEST(test_ble_connection_blocks_sleep);
    RUN_TEST(test_vbus_blocks_sleep);
    RUN_TEST(test_activity_during_inhibit_returns_to_active);
    RUN_TEST(test_low_battery_turns_off_at_once);
    RUN_TEST(test_battery_at_threshold_stays_on);
    RUN_TEST(test_low_battery_overrides_inhibitors_and_dim);
    RUN_TEST(test_low_battery_while_charging_stays_on);
    RUN_TEST(test_low_battery_on_usb_not_charging_stays_on);
    RUN_TEST(test_low_battery_after_unplug_turns_off);
    RUN_TEST(test_low_battery_with_invalid_reading_stays_on);
    RUN_TEST(test_off_threshold_zero_never_turns_off);
    RUN_TEST(test_sleep_is_terminal);
    RUN_TEST(test_off_is_terminal);
    RUN_TEST(test_set_config_shorter_timeouts_take_effect);
    RUN_TEST(test_set_config_keeps_idle_time);
    RUN_TEST(test_set_config_longer_dim_undims);
    RUN_TEST(test_set_config_changes_off_threshold);
    RUN_TEST(test_now_wraparound);
    RUN_TEST(test_activity_across_wraparound);
    RUN_TEST(test_activity_timestamp_slightly_ahead_is_not_huge_idle);
    RUN_TEST(test_inhibited_dim_survives_counter_wrap);
    RUN_TEST(test_input_activity_stamp_change_is_activity);
    RUN_TEST(test_input_activity_same_stamp_counts_once);
    RUN_TEST(test_input_activity_before_init_is_not_activity);
    RUN_TEST(test_input_activity_first_stamp_after_init_counts);
    RUN_TEST(test_input_activity_after_sleep_is_ignored);
    RUN_TEST(test_input_activity_stamp_wraps);
    RUN_TEST(test_vibration_ignored_when_hold_disabled);
    RUN_TEST(test_vibration_without_activity_since_init_never_counts);
    RUN_TEST(test_vibration_holds_until_cap);
    RUN_TEST(test_credit_does_not_rearm);
    RUN_TEST(test_new_activity_rearms);
    RUN_TEST(test_power_fsm_activity_arms);
    RUN_TEST(test_vibration_from_dim_returns_active);
    RUN_TEST(test_expired_hold_latched_across_wrap);
    RUN_TEST(test_stamp_after_now_counts_as_just_armed);
    RUN_TEST(test_low_battery_off_while_vibration_credited);
    RUN_TEST(test_sleep_blocked_still_dims_after_cap);
    RUN_TEST(test_vibration_after_sleep_is_ignored);
    RUN_TEST(test_vibration_bound_property);
    RUN_TEST(test_low_battery_ignored_until_settled);
    RUN_TEST(test_bogus_first_reading_does_not_turn_off);
    RUN_TEST(test_battery_settle_counts_from_init_not_boot);
    RUN_TEST(test_battery_settle_survives_counter_wrap);
    RUN_TEST(test_set_config_keeps_settle_start);
    RUN_TEST(test_idle_ms_follows_activity);
    RUN_TEST(test_idle_ms_future_stamp_is_zero);
    RUN_TEST(test_idle_ms_clamps_instead_of_wrapping);
    return UNITY_END();
}
