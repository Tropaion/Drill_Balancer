#pragma once

// libdbsim: the firmware's hardware-independent code (level, fusion,
// accel_cal, settings, ota_proto, ble_codec, power_fsm, ui_logic, imu_frame,
// pmu_events) behind a small ctypes-friendly API, with the ESP glue
// (sensor_task.c, control.c, ble_service.c, ota_update.c, settings_store.c)
// mirrored in sim_core.c.
//
// Single-threaded: the Python side calls everything from one thread. Time is
// passed in as now_ms (milliseconds since "boot", like app_now_ms()), so a
// test can run the device on an accelerated clock.
//
// Persistence: one JSON state file stands in for NVS (settings including
// calibration, and the firmware version an OTA installed). Bonds are not
// kept here; BlueZ keeps them.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// sim_take_events() bits.
#define SIM_EV_SLEEP        0x01u   // entered deep sleep (core halted)
#define SIM_EV_POWER_OFF    0x02u   // powered off (core halted)
#define SIM_EV_REBOOT       0x04u   // esp_restart() (core halted)
#define SIM_EV_CLEAR_BONDS  0x08u   // factory reset: forget all bonds, drop the link
#define SIM_EV_BLE_ENABLED  0x10u   // settings switched BLE on or off (sim_ble_enabled())
#define SIM_EV_SETTINGS     0x20u   // settings were stored and reloaded
#define SIM_EV_CAL          0x40u   // calibration status published
#define SIM_EV_OTA          0x80u   // OTA active / percent changed

// sim_notify_tick() result bits.
#define SIM_TX_ANGLE        0x01u   // angle notification in angle_out (14 bytes)
#define SIM_TX_STATUS       0x02u   // status notification in status_out (10 bytes)
#define SIM_TX_BATTERY      0x04u   // battery level notification in bat_out (1 byte)
#define SIM_TX_KICK         0x08u   // drop this idle unauthenticated link
#define SIM_TX_CONN_FAST    0x10u   // ask for fast connection parameters (OTA)
#define SIM_TX_CONN_SLOW    0x20u   // back to the relaxed parameters

// sim_link_subscribe() bits, as s_subs in ble_service.c.
#define SIM_SUB_ANGLE       0x01u
#define SIM_SUB_STATUS      0x02u
#define SIM_SUB_BAT         0x04u
#define SIM_SUB_OTA         0x08u

// sim_set_faults() bits.
#define SIM_FAULT_STORE     0x01u   // settings store (NVS) writes fail
#define SIM_FAULT_FLASH     0x02u   // OTA flash writes fail

// Core states (sim_state()).
#define SIM_STATE_RUNNING   0
#define SIM_STATE_SLEEP     1
#define SIM_STATE_OFF       2
#define SIM_STATE_REBOOT    3

// Boots the device: loads the state file (defaults if missing or invalid),
// starts the sensor (imu_present: imu_init() succeeds), BLE and control
// "tasks". default_version is the running image's version unless an OTA
// installed another one (kept in the state file). Returns 0, or -1 if a
// path is too long.
int sim_init(const char *state_path, const char *ota_image_path, const char *default_version,
             uint32_t now_ms, int imu_present);

int sim_state(void);
const char *sim_fw_version(void);
// Device Information strings: 0 manufacturer, 1 model.
const char *sim_dis_string(int which);
int sim_ble_enabled(void);
uint32_t sim_off_after_s(void);   // deep sleep before power-off, 0 = never

// Events since the last call (SIM_EV_*).
uint32_t sim_take_events(void);

// Next line of the firmware log ("I text", "W text", "E text"; the ESP_LOGx
// calls of the mirrored glue). Returns its length, 0 if none.
int sim_log_pop(char *buf, int cap);

// The whole app_state snapshot plus derived UI values as JSON. Returns the
// length, or -1 if cap is too small.
int sim_snapshot_json(char *buf, int cap);

// ---- sensor task -----------------------------------------------------------------
// One 20 ms poll pass: n samples (ax ay az [g], gx gy gz [rad/s] each, oldest
// first, at most 64), dt the sample period. n = 0 is an empty or failed FIFO
// read. imu_present says whether a re-init of a lost IMU would succeed. The
// values pass through the chip's 16-bit FIFO format and imu_decode_frame().
void sim_sensor_pass(uint32_t now_ms, const float *samples, int n, float dt, int imu_present);

// ---- control task ----------------------------------------------------------------
// One pass of the control loop: queued commands, PMU poll, power policy.
void sim_control_step(uint32_t now_ms);
// app_post_cmd(): APP_CMD_* id and arg. Returns 1, or 0 if the queue is full.
int sim_post_cmd(int id, int arg);
// Touch and other user activity.
void sim_note_activity(uint32_t now_ms);
// The battery and charger as the AXP2101 sees them; they become its status
// registers (decoded by pmu_decode_status(), polled every 500 ms) and IRQs
// (VBUS, charge start/done, SOC below 10 % / 5 %: pmu_events_from_irq()).
void sim_set_battery(int valid, int percent, int voltage_mv, int charging, int charge_done,
                     int vbus);
// PWR key: short press (sleep) or long press (power off).
void sim_pwrkey(int long_press);
// The power state (app_power_t) the control task last published.
int sim_power(void);

// ---- BLE service -----------------------------------------------------------------
// GATT writes return 0 or the ATT error code; each counts as activity.
// Config read (ble_service.c config_read): the JSON is captured at offset 0
// (or after a link reset or config write) and reused for the blobs of a long
// read. Returns the whole captured value (the caller applies the offset), or
// -1 if it does not fit.
int sim_config_read(int offset, char *buf, int cap);
// The stored settings as JSON, without touching that capture.
int sim_settings_json(char *buf, int cap);
int sim_config_write(const uint8_t *data, int len, uint32_t now_ms);
// A settings change made on the device's settings screen (ui_settings.c
// save_now): a JSON patch merged with settings_update(), counted as a touch.
// Returns 0, 1 (rule broken, nothing stored), -1 (bad JSON, not running) or
// -2 (store failed).
int sim_ui_settings_edit(const char *json, int len, uint32_t now_ms);
int sim_command_write(const uint8_t *data, int len, uint32_t now_ms);
int sim_ota_ctrl_write(const uint8_t *data, int len, uint32_t now_ms);
int sim_ota_data_write(const uint8_t *data, int len, uint32_t now_ms);
// Next queued ota_ctrl indication (at most 8 bytes). Returns its length, 0 if none.
int sim_ota_reply_pop(uint8_t *buf);
void sim_ota_set_mtu(int mtu);
// ota_update's 1 s timer while a transfer runs.
void sim_ota_tick(uint32_t now_ms);
int sim_ota_reboot_pending(void);

// Link events, as the GAP handlers of ble_service.c. sim_link_connect()
// returns 1 if the link must be terminated at once (BLE disabled).
int sim_link_connect(uint32_t now_ms);
void sim_link_disconnect(uint32_t now_ms);
// Passkey shown (pairing started) / encryption change. trusted: encrypted,
// authenticated (MITM) and a 16-byte key.
void sim_link_passkey(uint32_t passkey, uint32_t now_ms);
void sim_link_enc_change(int status, int trusted, uint32_t now_ms);
void sim_link_subscribe(int sub_bit, int on);
int sim_link_trusted(void);
// The 100 ms notify timer. Returns SIM_TX_* bits; the buffers hold what to send.
uint32_t sim_notify_tick(uint32_t now_ms, uint8_t *angle_out, uint8_t *status_out,
                         uint8_t *bat_out);

// Packets for reads (status, battery level) and checks.
void sim_angle_packet(uint8_t seq, uint32_t now_ms, uint8_t *out14);
void sim_status_packet(uint8_t *out10);
int sim_battery_pct(void);

void sim_set_faults(uint32_t faults);
// The next n valid command writes get DB_ATT_ERR_BUSY (as a full queue would).
void sim_fault_busy(int n);

// ui_logic.c texts: 0 mode title (code = mode), 1 mount result, 2 sensor
// cal result (code = last_result).
const char *sim_ui_text(int which, int code);

#ifdef __cplusplus
}
#endif
