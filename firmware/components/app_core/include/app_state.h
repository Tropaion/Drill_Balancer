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
    uint8_t refused;        // counts the starts the control task refused
                            // (app_excl_acquire() failed), so a screen that
                            // posted one sees whether it was its own
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

// The recorder's state for the BLE rec_state characteristic, the status flag
// and the UI. state and reason mirror rec_state_t / REC_REASON_* (rec_fsm.h,
// rec_stream.h) without depending on them. While no session is open state
// is 0 (idle) and the connection fields are zero, but reason and the take's
// fields keep the last take and why it or the session ended (rec_fsm_t keeps
// them), so an app that reconnects after the BLE grace can show it.
// rec_state_t values the UI and the power policy look at.
#define APP_REC_STATE_IDLE      0
#define APP_REC_STATE_READY     3
#define APP_REC_STATE_RECORDING 4
#define APP_REC_STATE_DRAINING  5
// app_rec_status_t.flags: DB_REC_FLAG_* (ble_protocol.h, ble_codec.c checks).
#define APP_REC_FLAG_NO_AP       (1u << 0)
#define APP_REC_FLAG_CLIENT      (1u << 1)
#define APP_REC_FLAG_AUDIO       (1u << 2)
#define APP_REC_FLAG_IMU_LOST    (1u << 3)
#define APP_REC_FLAG_BATTERY_LOW (1u << 4)

typedef struct {
    uint8_t state;               // rec_state_t, 0 = idle
    uint8_t reason;              // REC_REASON_* of the last take or session end
    uint8_t flags;               // APP_REC_FLAG_*
    uint8_t profile;             // REC_PROFILE_* of the take
    uint32_t take_id;
    uint16_t elapsed_s;
    uint16_t max_s;
    uint64_t sent_bytes;         // stream bytes sent this session
    uint32_t dropped;            // records dropped this take
    uint8_t ring_pct;
    uint8_t channel;             // Wi-Fi channel, 0 without AP
    uint16_t port;
    uint8_t ip[4];
    char ssid[17];               // NUL-terminated
    char pass[25];
    uint8_t token[16];
} app_rec_status_t;

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
    float zero_deg[3];           // that zero (elevation, bubble x, bubble y),
                                 // the one reading is relative to; 0 without

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

    // recorder
    app_rec_status_t rec;

    // app_excl_acquire(): who has the device to itself
    uint8_t excl;                // app_excl_t
} app_snapshot_t;

// Calibration, OTA and a recorder session exclude each other: each takes
// the device with app_excl_acquire() before it starts (an atomic test and
// set) and gives it back with app_excl_release() once it is over. A
// calibration is taken by the control task at its start and given back
// when its published status goes idle (app_state_set_cal(), whichever way
// it ended); OTA from sink_begin() (ota_update.c) until the transfer ends;
// the recorder at OPEN until the session is closed and Wi-Fi is down
// (recorder.c). The screens and the BLE command write look at
// app_snapshot_t.excl to refuse a calibration start at once
// (app_excl_blocks_cal()).
typedef enum {
    APP_EXCL_NONE = 0,
    APP_EXCL_CAL,
    APP_EXCL_OTA,
    APP_EXCL_REC,
} app_excl_t;

// Takes the device for `who` if nobody has it (or `who` already does).
// Returns the owner afterwards: `who` on success, else the one holding it.
app_excl_t app_excl_acquire(app_excl_t who);
// Gives it back if `who` has it; nothing otherwise.
void app_excl_release(app_excl_t who);

// A calibration start is refused while OTA or a recording session has the
// device (excl: app_snapshot_t.excl).
static inline bool app_excl_blocks_cal(uint8_t excl)
{
    return excl == APP_EXCL_OTA || excl == APP_EXCL_REC;
}

// Who has the device, for the log lines of a refusal.
static inline const char *app_excl_what(uint8_t excl)
{
    switch (excl) {
    case APP_EXCL_CAL:
        return "a calibration runs";
    case APP_EXCL_OTA:
        return "an update runs";
    case APP_EXCL_REC:
        return "a recording session is open";
    default:
        return "nothing runs";
    }
}

void app_state_init(void);
void app_state_get(app_snapshot_t *out);

// The zero (zero_deg: elevation, bubble x, bubble y; NULL without one) is
// published with every motion update, so a snapshot never pairs it with
// another reading.
void app_state_set_motion(bool imu_ok, quat_t q, bool still, bool vibrating,
                          const float *zero_deg, const level_reading_t *reading,
                          uint32_t now_ms);
void app_state_set_battery(const app_battery_t *battery);
void app_state_set_ble(bool enabled, bool connected);
void app_state_set_ble_holds_awake(bool holds_awake);
void app_state_set_passkey(bool pending, uint32_t passkey);
void app_state_set_ota(bool active, uint8_t percent);
void app_state_set_power(app_power_t power);
// The sensor task's calibration status; kind going idle also gives the
// device back if the calibration had it (APP_EXCL_CAL).
void app_state_set_cal(const app_cal_status_t *cal);
// Published by the control task whenever the settings are loaded or reloaded.
void app_state_set_cal_valid(bool mount_cal_valid, bool accel_cal_valid);
// Published by the recorder (recorder.c) after every FSM step and on
// recorder_refresh().
void app_state_set_rec(const app_rec_status_t *rec);

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
