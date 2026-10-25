#include "control.h"
#include "app_state.h"
#include "ble_service.h"
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "pmu.h"
#include "power.h"
#include "power_fsm.h"
#include "sensor_task.h"
#include "settings_store.h"
#include "ui.h"

static const char *TAG = "control";

// Below the sensor task (10) and the LVGL task (6): nothing here is time
// critical, and it blocks on NVS writes and I2C.
#define CONTROL_TASK_PRIO  5
// settings_update (NVS), ble_service_shutdown, the sleep/power-off paths and
// accel_cal_solve's 6x6 system (~0.5 KB with the collector copy).
// TODO(hw): check uxTaskGetStackHighWaterMark() after a calibration finish
// and a factory reset.
#define CONTROL_TASK_STACK 6144

#define CMD_TIMEOUT_MS       200
#define PMU_STATUS_PERIOD_MS 500
#define PMU_EVENTS_PERIOD_MS 200

// The FSM turns the device off below this charge (power_fsm.h).
#define OFF_BATTERY_PCT 5
// Low-battery power-off is only armed this long after the control task
// starts, so one bogus first fuel-gauge reading cannot switch the device off
// right away (power_fsm_config_t.battery_settle_ms).
// TODO(hw): check what the AXP2101 gauge reports in the first seconds after
// a cold start (and after a battery swap) before trusting it sooner.
#define BATTERY_SETTLE_MS 5000

// PWR key events this soon after control_start() are dropped (still counted
// as activity): the press that switched the device on may raise a short
// press on its release, or a long press if held past IRQLEVEL (1.5 s from
// the start of the press), and either would undo the power-on. A deliberate
// press in this window is lost. The AXP2101 turns on after ONLEVEL (eFuse,
// 128 ms .. 2 s) and the firmware needs a moment more, so 2 s covers a press
// released or held up to roughly when the screen lights up.
// TODO(hw): power on with presses of about 0.5 s, 1.2 s and 2 s and check
// that the device stays on; check what ONLEVEL this board's eFuse has.
#define PWRKEY_BOOT_IGNORE_MS 2000

// Lets a BLE write response or the last log line out before restarting.
#define REBOOT_DELAY_MS 200

// A calibration nobody finishes would block deep sleep forever: it is
// cancelled after this much idle time beyond sleep_after. Rolling the drill
// and turning the device between poses count as activity, so a calibration
// in progress is never cut off.
#define CAL_IDLE_GRACE_MS (5u * 60u * 1000u)

// A running drill held still (sensor_task.c, vibration.h) counts as activity
// for this long after the last real activity, so the screen does not dim in
// the middle of a hole. Moving the drill to the next hole (a turn, turn.h,
// also while the motor runs) or a tap re-arms it. Vibration alone (a van, a
// toolbox, a bench next to a machine) therefore keeps the device awake at
// most this much longer per real activity: at an assumed 100-150 mA ACTIVE,
// 5-7.5 mAh. A vehicle turning a corner turns the device as well, so a drive
// with a turn every few minutes can keep it awake (bring-up step 14).
#define VIB_HOLD_MS (3u * 60u * 1000u)

// --- control task only ---
static settings_t s_settings;
static bool s_display_ok;
static power_fsm_t s_fsm;
static power_state_t s_power = POWER_ACTIVE;
static int s_brightness = -1;   // last value sent, -1 = unknown
static uint32_t s_last_status_ms;
static uint32_t s_last_events_ms;
static bool s_cal_from_ble;   // the last calibration started was started over BLE
static uint32_t s_start_ms;   // control_start(), for PWRKEY_BOOT_IGNORE_MS

static power_fsm_config_t fsm_config(const settings_t *s)
{
    return (power_fsm_config_t){
        .dim_after_ms = (uint32_t)s->dim_after_s * 1000u,
        .sleep_after_ms = (uint32_t)s->sleep_after_s * 1000u,
        .off_battery_pct = OFF_BATTERY_PCT,
        .battery_settle_ms = BATTERY_SETTLE_MS,
        .vib_hold_ms = VIB_HOLD_MS,
    };
}

static void apply_brightness(void)
{
    if (!s_display_ok || (s_power != POWER_ACTIVE && s_power != POWER_DIM)) {
        return;
    }
    int pct = s_power == POWER_DIM ? s_settings.dim_brightness_pct : s_settings.brightness_pct;
    if (pct == s_brightness) {
        return;
    }
    esp_err_t err = power_set_brightness((uint8_t)pct);
    if (err == ESP_OK) {
        s_brightness = pct;
    } else {
        ESP_LOGW(TAG, "brightness %d%%: %s", pct, esp_err_to_name(err));
    }
}

static app_power_t to_app_power(power_state_t st)
{
    switch (st) {
    case POWER_DIM:
        return APP_POWER_DIM;
    case POWER_SLEEP:
        return APP_POWER_SLEEP;
    case POWER_OFF:
        return APP_POWER_OFF;
    default:
        return APP_POWER_ACTIVE;
    }
}

// Sleep and power-off: publish the state, store a settings edit still inside
// the UI's save debounce (display_off() takes the display lock for good, so
// the UI's save timer would never run; a no-op without a UI), then stop the
// sensor task and BLE, which would otherwise still use the IMU and the radio.
static void prepare_shutdown(app_power_t p, const char *what)
{
    ESP_LOGI(TAG, "%s", what);
    app_state_set_power(p);
    ui_flush_pending_settings();
    sensor_stop();
    ble_service_shutdown();
}

// Neither returns.
static void enter_sleep(void)
{
    prepare_shutdown(APP_POWER_SLEEP, "deep sleep");
    power_enter_deep_sleep((uint32_t)s_settings.off_after_min * 60u);
}

static void enter_off(void)
{
    prepare_shutdown(APP_POWER_OFF, "power off");
    power_off();
}

// --- commands ---
//
// Settings changes go through settings_update(), so a BLE config write or a
// UI save running at the same time cannot overwrite the fields stored here.
// The sensor picks a change up from the SETTINGS_CHANGED reload, so it never
// runs on a value that is not stored.

typedef struct {
    bool step;      // arg is a step (+1 / -1), not a mode
    int32_t arg;
} mode_change_t;

static bool apply_mode(settings_t *s, void *ctx)
{
    const mode_change_t *c = ctx;
    const int32_t n = LEVEL_MODE_COUNT;
    int32_t m = c->step ? ((int32_t)s->mode + c->arg % n + n) % n : c->arg;
    if (m < 0 || m >= n) {
        return false;
    }
    s->mode = (level_mode_t)m;   // stored only if it differs
    return true;
}

static void change_mode(bool step, int32_t arg)
{
    mode_change_t c = {.step = step, .arg = arg};
    esp_err_t err = settings_update(apply_mode, &c);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "mode not changed: %s", esp_err_to_name(err));
    }
}

static void start_cal(app_cal_kind_t kind, int32_t arg)
{
    s_cal_from_ble = arg == APP_CMD_ARG_FROM_BLE;
    sensor_cal_start(kind);
}

// APP_CMD_CAL_CANCEL. With APP_CMD_ARG_FROM_BLE it comes from the BLE
// service on a disconnect: only a calibration that client started is left
// without anyone to finish it. Cancelling while idle does nothing.
static void cancel_cal(int32_t arg)
{
    if (arg == APP_CMD_ARG_FROM_BLE) {
        if (!s_cal_from_ble) {
            return;
        }
        app_snapshot_t snap;
        app_state_get(&snap);
        if (snap.cal.kind != APP_CAL_IDLE) {
            ESP_LOGW(TAG, "calibration cancelled: BLE client gone");
        }
    }
    sensor_cal_cancel();
    s_cal_from_ble = false;
}

static bool apply_mount_cal(settings_t *s, void *ctx)
{
    s->drill_axis = *(const vec3_t *)ctx;
    s->mount_cal_valid = true;
    return true;
}

static void finish_mount_cal(void)
{
    level_cal_result_t res;
    vec3_t axis;
    if (!sensor_mount_cal_finish(&res, &axis)) {
        ESP_LOGW(TAG, "mount calibration finish without start");
        return;
    }
    int result = (int)res;
    if (res == LEVEL_CAL_OK) {
        esp_err_t err = settings_update(apply_mount_cal, &axis);
        ESP_LOGI(TAG, "mount calibrated: axis %.4f %.4f %.4f", (double)axis.x, (double)axis.y,
                 (double)axis.z);
        if (err != ESP_OK) {
            // The old axis stays in use; the UI says so instead of "OK".
            ESP_LOGE(TAG, "mount calibration not stored: %s", esp_err_to_name(err));
            result = APP_CAL_RESULT_NOT_STORED;
        }
    } else {
        ESP_LOGW(TAG, "mount calibration failed: %d", result);
    }
    sensor_cal_done(APP_CAL_MOUNT, result);
}

typedef struct {
    accel_cal_t cal;
    bool mount_dropped;   // out: a mount calibration was reset
} accel_store_t;

static bool apply_accel_cal(settings_t *s, void *ctx)
{
    accel_store_t *a = ctx;
    s->accel_cal = a->cal;
    s->accel_cal_valid = true;
    // The mount calibration was fitted to accelerometer data with the old
    // correction, so its axis is off by the change: back to the nominal -Z
    // until it is redone (the result APP_CAL_RESULT_OK_MOUNT_DROPPED makes
    // the UI ask for that).
    a->mount_dropped = s->mount_cal_valid;
    s->mount_cal_valid = false;
    s->drill_axis = level_default_config().drill_axis;
    return true;
}

static void finish_accel_cal(void)
{
    accel_cal_result_t res;
    accel_store_t a = {0};
    if (!sensor_accel_cal_finish(&res, &a.cal)) {
        ESP_LOGW(TAG, "sensor calibration finish without start");
        return;
    }
    int result = (int)res;
    if (res == ACCEL_CAL_OK) {
        esp_err_t err = settings_update(apply_accel_cal, &a);
        const accel_cal_t *cal = &a.cal;
        ESP_LOGI(TAG, "accel calibrated: bias %.4f %.4f %.4f g, scale %.4f %.4f %.4f",
                 (double)cal->bias.x, (double)cal->bias.y, (double)cal->bias.z,
                 (double)cal->scale.x, (double)cal->scale.y, (double)cal->scale.z);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "accel calibration not stored: %s", esp_err_to_name(err));
            result = APP_CAL_RESULT_NOT_STORED;
        } else if (a.mount_dropped) {
            ESP_LOGW(TAG, "mount calibration reset: redo it with the new sensor calibration");
            result = APP_CAL_RESULT_OK_MOUNT_DROPPED;   // the UI asks for the redo
        }
    } else {
        ESP_LOGW(TAG, "sensor calibration failed: %d", result);
    }
    sensor_cal_done(APP_CAL_ACCEL, result);
}

static void reload_settings(void)
{
    settings_get(&s_settings);
    sensor_apply_settings(&s_settings);
    app_state_set_cal_valid(s_settings.mount_cal_valid, s_settings.accel_cal_valid);
    power_fsm_config_t cfg = fsm_config(&s_settings);
    power_fsm_set_config(&s_fsm, &cfg);
    apply_brightness();
    esp_err_t err = ble_service_set_enabled(s_settings.ble_enabled);   // no-op if unchanged
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "BLE %s: %s", s_settings.ble_enabled ? "on" : "off", esp_err_to_name(err));
    }
}

static void handle_cmd(const app_cmd_t *cmd)
{
    switch (cmd->id) {
    case APP_CMD_ZERO:
        if (!sensor_zero()) {
            ESP_LOGW(TAG, "zero ignored: no orientation");
        }
        break;
    case APP_CMD_CLEAR_ZERO:
        sensor_clear_zero();
        break;
    case APP_CMD_SET_MODE:
        change_mode(false, cmd->arg);
        break;
    case APP_CMD_NEXT_MODE:
        change_mode(true, cmd->arg);
        break;
    case APP_CMD_MOUNT_CAL_START:
        start_cal(APP_CAL_MOUNT, cmd->arg);
        break;
    case APP_CMD_MOUNT_CAL_FINISH:
        finish_mount_cal();
        break;
    case APP_CMD_ACCEL_CAL_START:
        start_cal(APP_CAL_ACCEL, cmd->arg);
        break;
    case APP_CMD_ACCEL_CAL_FINISH:
        finish_accel_cal();
        break;
    case APP_CMD_CAL_CANCEL:
        cancel_cal(cmd->arg);
        break;
    case APP_CMD_SETTINGS_CHANGED:
        reload_settings();
        break;
    case APP_CMD_SLEEP:
        enter_sleep();
        break;
    case APP_CMD_POWER_OFF:
        enter_off();
        break;
    case APP_CMD_REBOOT:
        ESP_LOGI(TAG, "reboot");
        ui_flush_pending_settings();   // as in prepare_shutdown(); BLE stays up for the reply
        vTaskDelay(pdMS_TO_TICKS(REBOOT_DELAY_MS));
        esp_restart();
        break;
    case APP_CMD_FACTORY_RESET: {
        ESP_LOGW(TAG, "factory reset");
        cancel_cal(0);
        sensor_clear_zero();
        esp_err_t err = settings_reset();   // posts SETTINGS_CHANGED unless already at defaults
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "factory reset: %s", esp_err_to_name(err));
        }
        // Bonds too: a lost, sold or lent phone must not keep its access to
        // config, commands and OTA. Drops the current link.
        err = ble_service_clear_bonds();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "factory reset: bonds not cleared: %s", esp_err_to_name(err));
        }
        break;
    }
    default:
        ESP_LOGW(TAG, "unknown command %d", (int)cmd->id);
        break;
    }
}

// --- PMU and power ---

static void poll_pmu(uint32_t now)
{
    if (now - s_last_events_ms >= PMU_EVENTS_PERIOD_MS) {
        s_last_events_ms = now;
        uint32_t ev = pmu_poll_events();
        if (ev & (PMU_EVT_VBUS_IN | PMU_EVT_VBUS_OUT | PMU_EVT_PWRKEY_SHORT |
                  PMU_EVT_PWRKEY_LONG)) {
            app_note_activity();   // plugging, unplugging and the key are deliberate
        }
        if ((ev & (PMU_EVT_PWRKEY_SHORT | PMU_EVT_PWRKEY_LONG)) &&
            (uint32_t)(now - s_start_ms) < PWRKEY_BOOT_IGNORE_MS) {
            ESP_LOGI(TAG, "PWR key event 0x%08lx ignored: power-on press", (unsigned long)ev);
            ev &= ~(uint32_t)(PMU_EVT_PWRKEY_SHORT | PMU_EVT_PWRKEY_LONG);
        }
        if (ev & PMU_EVT_PWRKEY_SHORT) {
            app_post_cmd(APP_CMD_SLEEP, 0);
        }
        if (ev & PMU_EVT_PWRKEY_LONG) {
            // The AXP2101 cuts power by itself at 4 s; shut down cleanly first.
            app_post_cmd(APP_CMD_POWER_OFF, 0);
        }
        if (ev & PMU_EVT_BATTERY_LOW) {
            ESP_LOGW(TAG, "battery low");
        }
        if (ev & (PMU_EVT_VBUS_IN | PMU_EVT_VBUS_OUT | PMU_EVT_CHARGE_START |
                  PMU_EVT_CHARGE_DONE)) {
            s_last_status_ms = now - PMU_STATUS_PERIOD_MS;   // show the change at once
        }
    }

    if (now - s_last_status_ms >= PMU_STATUS_PERIOD_MS) {
        s_last_status_ms = now;
        pmu_status_t st;
        app_battery_t b = {0};
        if (pmu_get_status(&st) == ESP_OK) {
            // pmu_decode_status() has already ruled out implausible voltages
            // and clamped the percent.
            b = (app_battery_t){
                .valid = st.battery_present,
                .percent = st.percent,
                .voltage_mv = st.voltage_mv,
                .charging = st.charging,
                .charge_done = st.charge_done,
                .vbus_present = st.vbus_present,
            };
        }
        app_state_set_battery(&b);
    }
}

// A calibration blocks deep sleep, so one that nobody finishes is cancelled
// after a long idle time (the same idle time the power FSM sleeps on). One
// started over BLE is also cancelled when that client disconnects
// (cancel_cal()).
static void check_abandoned_cal(const app_snapshot_t *snap, uint32_t now)
{
    if (snap->cal.kind == APP_CAL_IDLE) {
        return;
    }
    if (power_fsm_idle_ms(&s_fsm, now) > s_fsm.cfg.sleep_after_ms + CAL_IDLE_GRACE_MS) {
        ESP_LOGW(TAG, "calibration cancelled: idle");
        sensor_cal_cancel();
        s_cal_from_ble = false;
    }
}

static void update_power(uint32_t now)
{
    app_snapshot_t snap;
    app_state_get(&snap);
    const app_battery_t *b = &snap.battery;
    power_inputs_t in = {
        .last_activity_ms = app_last_activity_ms(),
        .vibrating = snap.vibrating,
        .battery_valid = b->valid,
        .battery_pct = b->percent,
        .charging = b->charging,
        .vbus_present = b->vbus_present,
        // Only an authenticated link that was used recently: a scanner app
        // or a squatter that connects and does nothing must not keep the
        // device out of deep sleep (ble_service.c).
        .ble_connected = snap.ble_holds_awake,
        .ota_active = snap.ota_active,
        .calibrating = snap.cal.kind != APP_CAL_IDLE,
    };
    power_state_t st = power_fsm_update(&s_fsm, &in, now);
    // After the update, which has taken in the latest activity. A cancel
    // lifts the sleep block on the next pass.
    check_abandoned_cal(&snap, now);
    if (st != s_power) {
        ESP_LOGI(TAG, "power state %d -> %d", (int)s_power, (int)st);
        s_power = st;
        app_state_set_power(to_app_power(st));
    }

    switch (st) {
    case POWER_SLEEP:
        enter_sleep();
        break;
    case POWER_OFF:
        ESP_LOGW(TAG, "battery %u%%: power off", (unsigned)b->percent);
        enter_off();
        break;
    default:
        apply_brightness();
        break;
    }
}

static void control_task(void *arg)
{
    (void)arg;
    for (;;) {
        app_cmd_t cmd;
        if (app_receive_cmd(&cmd, CMD_TIMEOUT_MS)) {
            handle_cmd(&cmd);
        }
        uint32_t now = app_now_ms();
        poll_pmu(now);
        update_power(now);
    }
}

esp_err_t control_start(const settings_t *s, bool display_ok)
{
    s_settings = *s;
    s_display_ok = display_ok;
    app_state_set_cal_valid(s->mount_cal_valid, s->accel_cal_valid);

    uint32_t now = app_now_ms();
    s_start_ms = now;
    power_fsm_config_t cfg = fsm_config(s);
    power_fsm_init(&s_fsm, &cfg, now);
    s_last_status_ms = now - PMU_STATUS_PERIOD_MS;   // first poll at once
    s_last_events_ms = now - PMU_EVENTS_PERIOD_MS;
    app_state_set_power(APP_POWER_ACTIVE);

    BaseType_t ok = xTaskCreate(control_task, "control", CONTROL_TASK_STACK, NULL,
                                CONTROL_TASK_PRIO, NULL);
    return ok == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}
