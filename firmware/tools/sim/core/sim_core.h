#pragma once

// libdbsim: the firmware's hardware-independent code (level, fusion,
// accel_cal, settings, ota_proto, ble_codec, power_fsm, ui_logic, imu_frame,
// pmu_events, the recorder's rec_stream / rec_ring / rec_fsm / rec_ts)
// behind a small ctypes-friendly API, with the ESP glue (sensor_task.c,
// imu.c's FIFO handling, control.c, ble_service.c, ota_update.c,
// settings_store.c, and the recorder's recorder.c / rec_audio.c /
// rec_wifi.c) mirrored in sim_core.c. sim_audio.c makes the microphones'
// sound (physics, not firmware).
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
#define SIM_EV_REC_AP       0x100u  // the stream endpoint was asked up or down (START_AP /
                                    // STOP_AP: sim_rec_ap_wanted())
#define SIM_EV_REC_AUDIO    0x200u  // the microphones went on or off (sim_rec_audio_on())
#define SIM_EV_REC_TAKE     0x400u  // a take started or ended (snapshot "rec")

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
#define SIM_SUB_REC         0x10u   // rec_state notifications

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
// For a new device, before sim_init(): the seed of the simulated randomness
// (the FIFO read timing, the microphones' noise; the microphones' default
// sensitivity too), the SESSION record's board_id (a salted hash of the chip
// id) and git revision (REC_FW_GIT, may be "").
void sim_seed(uint32_t seed);
void sim_set_identity(const uint8_t *board_id4, const char *fw_git);

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
// One 20 ms poll pass. The QMI8658 is simulated in front of the driver's
// FIFO code (imu.c read_fifo_locked): samples go into the chip's 128-frame
// FIFO in the active profile's scales and ranges (clipped like the ADC),
// TIMESTAMP counts every one of them, and those that arrive while a FIFO
// read is in progress are lost (datasheet 8.7: ~12 % at 448 Hz, ~20 % at
// 897 Hz). The real imu_gap_plan() / imu_fill_gap() / imu_raw_runs() fill
// them in for the level pipeline and give the recorder its timestamp runs. A
// profile change or an IMU re-init resets the chip: no samples for 180 ms,
// TIMESTAMP from 0, a new epoch.
//
// samples: what arrived since the last pass at sim_imu_odr_hz() (at most
// SIM_MAX_PASS_SAMPLES), ax ay az [g] gx gy gz [rad/s] each, oldest first;
// dt is their period (the driver measures its own). imu_present = 0: the
// chip does not answer (no samples, reads fail; a re-init fails).
#define SIM_MAX_PASS_SAMPLES 256
void sim_sensor_pass(uint32_t now_ms, const float *samples, int n, float dt, int imu_present);
// The sample rate of the profile the chip runs (hand sim_sensor_pass() that
// many samples per second).
float sim_imu_odr_hz(void);
// The next n passes read before they take a requested profile, as a pass
// already under way when the recorder asks for one does on the board.
void sim_fault_profile_lag(int passes);
// The chip's LPFs (CTRL5) in the model: off by default, so the vibration
// detector sees the motor tones under LEVEL too (vibration.c); on, a profile
// with its LPFs enabled (LEVEL) filters every sample with lpf.h's stand-in
// before the 16-bit converter, as the chip does before its output. Cleared
// by sim_init().
void sim_chip_lpf(int on);

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

// ---- recorder (recorder.c, rec_wifi.c, rec_audio.c) ------------------------------
// The session/take FSM, the stream ring and the producers run as on the
// board. The Python side plays the network: it provides the endpoint when
// START_AP asks for it (a TCP server on the LAN, announced with
// DB_REC_FLAG_NO_AP, or a hotspot) and moves the stream from sim_rec_pop()
// to the client.

// rec_ctrl write (ble_service.c): 0 or the ATT error.
int sim_rec_ctrl_write(const uint8_t *data, int len, uint32_t now_ms);
// rec_state read: the db_rec_state_packet_t (86 bytes).
void sim_rec_state_read(uint8_t *out86);
// The 100 ms notify timer for rec_state: 1 and the packet in out86 when a
// notification goes out now (to an authenticated, subscribed link only):
// after a change or the FSM's NOTIFY, and once a second during a take.
int sim_rec_notify(uint32_t now_ms, uint8_t *out86);

// The endpoint rec_state announces from the next SIM_NET_UP on: no_ap (the
// LAN, empty SSID), its IPv4 address (first octet first), port, Wi-Fi
// channel and, with an AP, SSID (<= 16 bytes) and passphrase (<= 24 bytes).
void sim_rec_set_endpoint(int no_ap, const uint8_t *ip4, int port, int channel, const char *ssid,
                          const char *pass);
// The stream ring's size for the next OPEN (the board has 4 MB of PSRAM;
// the simulator's is smaller, so a stall fills it in seconds).
#define SIM_REC_RING_DEFAULT (512u * 1024u)
void sim_rec_set_ring_size(uint32_t bytes);

// Network events (rec_wifi.c and the TCP server):
#define SIM_NET_UP          0   // endpoint ready (AP up, server listening); data = the
                                // session token (16 bytes)
#define SIM_NET_FAIL        1   // it could not be set up
#define SIM_NET_HELLO       2   // a client's first REC_HELLO_LEN bytes; 1 = accepted,
                                // 0 = wrong token or version, or not waiting (close it)
#define SIM_NET_CLIENT_LOST 3   // the client is gone (TCP closed, a send blocked > 10 s);
                                // the socket is closed first
#define SIM_NET_DOWN        4   // the endpoint is down after STOP_AP
int sim_rec_net(int kind, const uint8_t *data, int len, uint32_t now_ms);
// START_AP .. STOP_AP: the endpoint should be up. The microphones run.
int sim_rec_ap_wanted(void);
int sim_rec_audio_on(void);

// The recorder task's timer (every 100 ms): FSM timeouts, LEVEL (10 Hz) and
// STATS (1 Hz) records, the drain.
void sim_rec_tick(uint32_t now_ms);
// The sender: up to cap stream bytes (whole records or a part of one; the
// rest follows). Works while the core is halted, so what a shutdown queued
// still goes out.
int sim_rec_pop(uint8_t *buf, int cap);

// A touch on the screen: during a take an EVENT TOUCH, and the UI ignores
// it. Returns 1 if it was taken that way.
int sim_rec_note_touch(uint32_t now_ms);
// The 2 s long-press on the REC pill: stops the take. 0 or the ATT error.
int sim_rec_ui_stop(uint32_t now_ms);

// The microphones, one I2S DMA block of SIM_AUDIO_BLOCK frames (rec_audio.c
// on_recv): the motor state m becomes sound (sim_audio.c), the ES7210 PGA
// (the take's gain_db) and its 16-bit ADC turn it into samples, an AUDIO
// record carries them. Call every 16 ms while sim_rec_audio_on(); now_ms is
// the end of the block. Returns the frames, 0 with the microphones off.
#define SIM_AUDIO_RATE  16000
#define SIM_AUDIO_BLOCK 256
#define SIM_MAX_IMPACTS 8
typedef struct {
    float f0_hz[2];             // motor fundamental at the block start / end, 0 = off
    float hum[2];               // its harmonics' level (full scale at 0 dB PGA gain)
    float noise[2];             // cutting noise (1-6 kHz) level
    float ambient;              // room noise level
    int32_t n_impacts;          // impact bursts starting in this block:
    float impact_s[SIM_MAX_IMPACTS];       // onset, seconds after the block start
    float impact_amp[SIM_MAX_IMPACTS];     // amplitude, full scale
    float impact_tau_s[SIM_MAX_IMPACTS];   // decay time constant, s
} sim_motor_t;
int sim_audio_block(uint32_t now_ms, const sim_motor_t *m);
// Microphone sensitivity (1 or 2), dB: the capsule and how the sound reaches
// it (c_mic_gain), before the ES7210.
void sim_set_mic_gain(int mic, float db);

#ifdef __cplusplus
}
#endif
