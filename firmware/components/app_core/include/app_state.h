#pragma once

// Shared application state and the command queue.
//
// Producers (sensor task, PMU poller, BLE, OTA, power) write their part with
// one setter each; consumers (UI, BLE notifications) take a consistent copy
// with app_state_get(). The UI and BLE never act on the sensor or settings
// directly: they post commands, which the control task in main executes.
//
// All functions are thread-safe and non-blocking (except app_receive_cmd).

#include <stdbool.h>
#include <stdint.h>
#include "level.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool valid;
    uint8_t percent;        // fuel gauge 0..100 (pmu_decode_status() clamps it)
    uint16_t voltage_mv;
    bool charging;
    bool charge_done;
    bool vbus_present;      // USB-C plugged in
} app_battery_t;

typedef enum {
    APP_CAL_IDLE = 0,
    APP_CAL_MOUNT,          // collecting level_mount_cal samples
    APP_CAL_ACCEL,          // collecting accel_cal poses
} app_cal_kind_t;

typedef struct {
    app_cal_kind_t kind;
    uint16_t samples;       // mount: samples so far; accel: poses kept
    uint8_t accel_coverage; // accel: ACCEL_CAL_DIR_* bits captured
    int8_t last_result;     // result of the last finish (level_cal_result_t or
                            // accel_cal_result_t, >= 0), or APP_CAL_RESULT_*
    app_cal_kind_t last_kind;
} app_cal_status_t;

// last_result values besides the level/accel result codes.
#define APP_CAL_RESULT_NONE        (-1)   // none yet, or cancelled
#define APP_CAL_RESULT_NOT_STORED  (-2)   // solved OK, but saving it failed:
                                          // the old calibration stays in use
#define APP_CAL_RESULT_OK_MOUNT_DROPPED (-3)   // sensor calibration solved and
                                               // saved, and the mount calibration
                                               // it invalidated was reset

// Mirrors power_state_t (power_fsm.h) without depending on it.
typedef enum {
    APP_POWER_ACTIVE = 0,
    APP_POWER_DIM,
    APP_POWER_SLEEP,
    APP_POWER_OFF,
} app_power_t;

typedef struct {
    // motion (sensor task)
    bool imu_ok;
    quat_t q;                    // fused orientation, body -> world
    bool still;
    bool vibrating;              // a running motor (vibration.h VIB_RUNNING):
                                 // counts for the power FSM's vibration hold
    level_reading_t reading;     // for the current mode
    uint32_t reading_ms;
    bool zero_set;               // a zero is set (also with the bit vertical,
                                 // where reading.yaw_valid stays false)

    app_battery_t battery;

    // calibration state of the stored settings (control task)
    bool mount_cal_valid;
    bool accel_cal_valid;

    // BLE
    bool ble_enabled;
    bool ble_connected;          // any link (status icon)
    bool ble_holds_awake;        // an encrypted, authenticated link with
                                 // recent GATT writes: blocks deep sleep
    bool passkey_pending;        // show passkey on screen while pairing
    uint32_t passkey;            // 6 digits

    // OTA
    bool ota_active;
    uint8_t ota_percent;

    app_power_t power;
    app_cal_status_t cal;
} app_snapshot_t;

void app_state_init(void);
void app_state_get(app_snapshot_t *out);

// zero_set is published with every motion update, so a snapshot never pairs
// it with another reading.
void app_state_set_motion(bool imu_ok, quat_t q, bool still, bool vibrating, bool zero_set,
                          const level_reading_t *reading, uint32_t now_ms);
void app_state_set_battery(const app_battery_t *battery);
void app_state_set_ble(bool enabled, bool connected);
void app_state_set_ble_holds_awake(bool holds_awake);
void app_state_set_passkey(bool pending, uint32_t passkey);
void app_state_set_ota(bool active, uint8_t percent);
void app_state_set_power(app_power_t power);
void app_state_set_cal(const app_cal_status_t *cal);
// Published by the control task whenever the settings are loaded or reloaded.
void app_state_set_cal_valid(bool mount_cal_valid, bool accel_cal_valid);

typedef enum {
    APP_CMD_ZERO = 0,           // zero on the current orientation
    APP_CMD_CLEAR_ZERO,
    APP_CMD_SET_MODE,           // arg = level_mode_t
    APP_CMD_NEXT_MODE,          // arg = +1 / -1
    APP_CMD_MOUNT_CAL_START,    // arg = APP_CMD_ARG_FROM_BLE or 0 (UI)
    APP_CMD_MOUNT_CAL_FINISH,   // solve, store on success
    APP_CMD_ACCEL_CAL_START,    // arg as for MOUNT_CAL_START
    APP_CMD_ACCEL_CAL_FINISH,
    APP_CMD_CAL_CANCEL,         // arg = APP_CMD_ARG_FROM_BLE: link dropped, see below
    APP_CMD_SETTINGS_CHANGED,   // settings were saved; reload derived state
    APP_CMD_SLEEP,              // go to deep sleep now (e.g. PWR short press)
    APP_CMD_POWER_OFF,
    APP_CMD_REBOOT,
    APP_CMD_FACTORY_RESET,      // settings and calibration back to defaults
} app_cmd_id_t;

typedef struct {
    app_cmd_id_t id;
    int32_t arg;
} app_cmd_t;

// arg of the calibration START commands: started by a BLE client. Such a
// calibration is cancelled when the link drops, since nobody is left to
// finish it: the BLE service then posts APP_CMD_CAL_CANCEL with this arg,
// which cancels only a calibration that was started over BLE.
#define APP_CMD_ARG_FROM_BLE 1

// From any task. Returns false if the queue is full.
bool app_post_cmd(app_cmd_id_t id, int32_t arg);
// Control task only.
bool app_receive_cmd(app_cmd_t *out, uint32_t timeout_ms);

// User activity that should keep the device awake (touch, BLE traffic,
// significant motion). It also arms the power FSM's vibration hold, so it
// must not be called for anything vibration alone can produce. Cheap; call
// as often as needed.
void app_note_activity(void);
uint32_t app_last_activity_ms(void);

// Milliseconds since boot (esp_timer based), shared time base for all modules.
uint32_t app_now_ms(void);

#ifdef __cplusplus
}
#endif
