#pragma once

// Training-data recorder: the ESP glue around the pure parts (rec_fsm,
// rec_ring, rec_stream, rec_ts, rec_util). See rec_fsm.h for the session and
// take rules and rec_stream.h for the stream.
//
// Tasks:
//   rec      (prio 5)          the FSM: commands, Wi-Fi / TCP / BLE / IMU
//                              events, a 100 ms tick (timeouts, LEVEL 10 Hz,
//                              STATS 1 Hz), and the actions it returns, in
//                              bit order; short steps only, so a command is
//                              answered within milliseconds
//   rec_tx   (prio 4, core 0)  rec_wifi.c: Wi-Fi up and down (the slow part,
//                              asked for by START_AP / STOP_AP), the TCP
//                              server, HELLO, the sender
//   rec_mic  (prio 9, core 1)  rec_audio.c: I2S capture, only during a take
// A session holds the device (app_excl_acquire(), OTA and calibrations
// are refused meanwhile) from OPEN until it is closed and Wi-Fi is down.
// Producers push whole records into the 4 MB PSRAM ring: the sensor task
// (IMU through recorder_imu_block(), VIB events), rec_mic (AUDIO), the UI
// (TOUCH events) and the rec task (SESSION, EVENT, LEVEL, STATS, END). A
// take's sources are gated: once an action stops one (AUDIO_OFF, the LEVEL
// request at a take end), nothing more of it reaches the ring.
//
// The glue mirrors tools/sim/core/sim_core.c ("recorder.c, rec_wifi.c,
// rec_audio.c"); keep the two in sync.

#include <stdbool.h>
#include <stdint.h>
#include "app_state.h"
#include "esp_err.h"
#include "rec_fsm.h"
#include "rec_stream.h"

#ifdef __cplusplus
extern "C" {
#endif

struct imu_raw_info;            // imu.h imu_raw_info_t

// What the recorder needs from the sensor task (main/sensor_task.c).
typedef struct {
    // Takes effect at the next pass (imu_set_profile() there).
    void (*request_profile)(uint8_t profile);
    // The fusion's gyro bias for the SESSION record; false while unknown.
    bool (*gyro_bias)(float out_rad_s[3]);
} recorder_hooks_t;

// A LEVEL record but host_us from a snapshot: the BLE angle packet's fields
// as a notification at now_ms would carry them, and the zero those angles
// are relative to (app_snapshot_t zero_deg, published with them). The BLE
// service registers its codec here. Without one the LEVEL records stay out.
typedef void (*recorder_level_fn)(const app_snapshot_t *s, uint32_t now_ms, rec_level_t *out);
void recorder_set_level_encoder(recorder_level_fn fn);

// Before sensor_start(): the sensor task reports the IMU from its first
// pass on. imu_ok is imu_init()'s result. hooks is copied.
esp_err_t recorder_init(bool imu_ok, const recorder_hooks_t *hooks);

// A rec_ctrl command, waiting for the FSM's answer (*err), usually a few
// milliseconds (at most about 0.5 s for the queue, 2 s once it is taken).
// False if the recorder did not take it in time or is not running (the BLE
// layer answers DB_ATT_ERR_BUSY): then it is not carried out, not later
// either. Never from the rec task.
bool recorder_post(const rec_cmd_t *cmd, rec_err_t *err);

// The same without waiting, for the UI (the long-press STOP). False if the
// queue is full.
bool recorder_send(const rec_cmd_t *cmd);

// The sensor task's tap, right after each FIFO read: a non-blocking copy of
// the raw frames into IMU records, one per timestamp run, while a take
// runs in its profile (nothing before that profile is in effect: EVENT
// PROFILE first).
void recorder_imu_block(const struct imu_raw_info *raw);

// The sensor task: the IMU lost / back (re-initialised), and the vibration
// detector's RUNNING against everything else (a VIB_START / VIB_STOP during
// a take; the caller sends a stop when it resets a running detector).
void recorder_note_imu(bool ok);
void recorder_note_vib(bool running);

// The BLE service: any disconnect, and an authenticated link (again).
void recorder_note_ble(bool up);

// A touch on the screen: an EVENT TOUCH while a take records. True then.
bool recorder_note_touch(void);

// Sleep, power-off, reboot and the low-battery off: a running take ends
// with END (STOP_AP gives the queue 200 ms, then the client up to
// REC_WIFI_LINGER_MS to take what lwIP holds), then the AP goes down; no
// session opens after this until the next boot (the FSM refuses OPEN).
// Blocks until Wi-Fi is down: typically well under a second, bounded by
// REC_WIFI_STOP_MAX_MS (rec_glue.h, about 5 s). Call it first, while the
// producers still run.
void recorder_shutdown(void);

// app_state's rec status computed now (elapsed_s of this second), for the
// rec_state read and notification. Does not wait for the rec task (a
// try-lock: while it steps the FSM the last publish stays), only for the
// short ring and app_state locks.
void recorder_refresh(void);

// Whether the FSM asked for a rec_state notification (REC_ACT_NOTIFY) since
// the last call.
bool recorder_take_notify(void);

#ifdef __cplusplus
}
#endif
