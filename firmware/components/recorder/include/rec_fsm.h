#pragma once

// Recorder session/take state machine. Pure C, host-tested; recorder.c
// feeds it commands (BLE rec_ctrl, the UI) and events (Wi-Fi, TCP, BLE,
// IMU, ticks) and carries out the actions it returns.
//
//   IDLE -> AP_STARTING -> WAIT_CLIENT -> READY <-> RECORDING -> DRAINING
//        -> READY ... -> CLOSING -> IDLE
//
// A session (soft-AP up, one Android approval dialog) holds many takes; a
// take is one recording, SESSION ... END on the stream.
//
// Commands (an error leaves everything unchanged):
//   OPEN        IDLE: start the AP. Refused (STATE) while OTA or a
//               calibration runs, and after SHUTDOWN. In a session: nothing
//               to do (OK). CLOSING: STATE.
//   CLOSE       ends the take (USER) and the session; OK when there is none.
//   TAKE_START  READY only: profile < REC_PROFILE_COUNT, mic_mask within
//               REC_MIC_ALL (0 = no audio), gain_db <= REC_GAIN_MAX_DB,
//               max_s <= REC_TAKE_MAX_S (0 = REC_TAKE_DEFAULT_S), else
//               BAD_ARG; battery below REC_BATT_START_PCT without USB:
//               BATTERY; OTA or a calibration running: STATE.
//   STOP        RECORDING: end the take (USER). READY, DRAINING, or once a
//               take ran since OPEN: nothing to do (a stop racing the take's
//               own end, e.g. CLIENT_LOST, the BLE grace or SHUTDOWN, is no
//               error). Otherwise STATE.
//   MARK        RECORDING only: an EVENT MARK.
// Events:
//   AP_UP / AP_FAIL   AP_STARTING -> WAIT_CLIENT / CLOSING (AP_FAIL).
//   HELLO_OK          WAIT_CLIENT -> READY.
//   CLIENT_LOST       the take stops (CLIENT_LOST) without END: its sources
//                     stop, then the ring is reset; back to WAIT_CLIENT.
//   BLE_LOST / BACK   the take goes on (EVENTs). Without BLE for
//                     REC_BLE_GRACE_MS the take stops and the session
//                     closes (BLE_LOST).
//   IMU_LOST / BACK   EVENTs; audio goes on. Lost for REC_IMU_LOST_MS during
//                     a take: it stops (IMU). The IMU driver keeps the
//                     profile across its re-init, so nothing is re-applied;
//                     the chip clock restarts there (REC_IMU_EPOCH).
//   DRAINED           the ring became empty (DRAINING: first the take's
//                     data, then its END). A take that stops while
//                     rec_env_t.ring_empty gets END at once and is done
//                     draining (READY, or CLOSING after a CLOSE).
//   AP_DOWN           CLOSING -> IDLE.
//   SHUTDOWN          sleep / power-off / reboot: a running take stops
//                     (SHUTDOWN), what was not sent yet is flushed and END
//                     queued, so it goes out within STOP_AP's 200 ms; then
//                     the AP goes down. In every state: OPEN is refused from
//                     then on (STATE) until rec_fsm_init(), the device is
//                     going down.
//   TICK              timeouts: AP start, waiting for a client, READY without
//                     a take, max_s, battery below REC_BATT_STOP_PCT without
//                     USB (BATTERY), the IMU and BLE timers, the drain limit,
//                     closing.

#include <stdbool.h>
#include <stdint.h>
#include "rec_stream.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    REC_STATE_IDLE = 0,
    REC_STATE_AP_STARTING,
    REC_STATE_WAIT_CLIENT,
    REC_STATE_READY,
    REC_STATE_RECORDING,
    REC_STATE_DRAINING,
    REC_STATE_CLOSING,
    REC_STATE_COUNT,
} rec_state_t;

#define REC_TAKE_DEFAULT_S     300
#define REC_TAKE_MAX_S         1200      // the dataset's 20 min cap
#define REC_GAIN_MAX_DB        37        // ES7210 PGA, 0..37.5 dB
#define REC_MARK_TEXT_MAX      40
#define REC_BATT_START_PCT     15        // no new take below, without USB
#define REC_BATT_STOP_PCT      8         // the take stops below, without USB
#define REC_AP_START_MS        10000u
#define REC_WAIT_CLIENT_MS     120000u
#define REC_READY_IDLE_MS      (15u * 60u * 1000u)
#define REC_DRAIN_MS           2000u
#define REC_CLOSE_MS           5000u
#define REC_BLE_GRACE_MS       120000u
#define REC_IMU_LOST_MS        5000u

typedef enum {
    REC_CMD_OPEN = 1,
    REC_CMD_CLOSE,
    REC_CMD_TAKE_START,
    REC_CMD_STOP,
    REC_CMD_MARK,
} rec_cmd_op_t;

typedef struct {
    rec_cmd_op_t op;
    // TAKE_START
    uint8_t profile;            // REC_PROFILE_*
    uint8_t mic_mask;           // REC_MIC_*
    uint8_t gain_db;
    uint16_t max_s;
    uint32_t take_id;
    // MARK
    uint16_t mark_code;
    char text[REC_MARK_TEXT_MAX + 1];
} rec_cmd_t;

typedef enum {
    REC_IN_CMD = 0,
    REC_IN_AP_UP,
    REC_IN_AP_FAIL,
    REC_IN_HELLO_OK,
    REC_IN_CLIENT_LOST,
    REC_IN_BLE_LOST,
    REC_IN_BLE_BACK,
    REC_IN_IMU_LOST,
    REC_IN_IMU_BACK,
    REC_IN_DRAINED,
    REC_IN_AP_DOWN,
    REC_IN_SHUTDOWN,
    REC_IN_TICK,
    REC_IN_COUNT,
} rec_input_kind_t;

typedef struct {
    rec_input_kind_t kind;
    const rec_cmd_t *cmd;       // REC_IN_CMD
} rec_input_t;

// What else runs on the device, passed with every input.
typedef struct {
    bool battery_valid;
    uint8_t battery_pct;
    bool vbus;                  // USB power present
    bool ota_active;
    bool calibrating;
    bool ring_empty;            // nothing queued (rec_ring_stats used == 0)
} rec_env_t;

// Command results; recorder.c also uses RADIO (the AP or the ring could not
// be set up at OPEN). ble_codec maps them to DB_ATT_ERR_*.
typedef enum {
    REC_OK = 0,
    REC_ERR_STATE,              // not in this state, or OTA / calibration runs
    REC_ERR_BAD_ARG,
    REC_ERR_BATTERY,
    REC_ERR_RADIO,
} rec_err_t;

// Actions, carried out in bit order: SESSION goes into the ring before the
// first data of the take; at a take end its sources stop before the ring is
// reset or flushed and before END, so nothing of the take follows them.
#define REC_ACT_AUDIO_OFF    (1u << 0)
#define REC_ACT_EMIT_SESSION (1u << 1)   // rec_ring_begin_take(), then SESSION
#define REC_ACT_SET_PROFILE  (1u << 2)   // request rec_fsm_profile(); the take's
                                         // IMU records start once it is in effect
                                         // (EVENT PROFILE first, rec_stream.h). At a
                                         // take end (LEVEL) the take's IMU, LEVEL and
                                         // STATS records stop with the request.
#define REC_ACT_RING_RESET   (1u << 3)   // drop everything queued (rec_ring_reset)
#define REC_ACT_RING_FLUSH   (1u << 4)   // drop what was not started (rec_ring_flush)
#define REC_ACT_AUDIO_ON     (1u << 5)   // with the take's mic_mask and gain_db
#define REC_ACT_EMIT_EVENT   (1u << 6)   // EVENT rec_out_t.event_code / event_arg
                                         // (MARK: text from the command)
#define REC_ACT_EMIT_END     (1u << 7)   // END with rec_fsm_t.reason
#define REC_ACT_START_AP     (1u << 8)
#define REC_ACT_STOP_AP      (1u << 9)   // after sending what is queued (<= 200 ms)
#define REC_ACT_NOTIFY       (1u << 10)  // rec_state changed

typedef struct {
    uint32_t actions;           // REC_ACT_*
    rec_err_t err;              // REC_IN_CMD
    uint16_t event_code;        // REC_EV_*, with REC_ACT_EMIT_EVENT
    uint16_t event_arg;
} rec_out_t;

typedef struct {
    rec_state_t state;
    uint8_t reason;             // REC_REASON_* of the last take or session end
    uint32_t since_ms;          // entered state
    // the current or last take of the session
    bool take_valid;
    uint32_t take_id;
    uint8_t profile;
    uint8_t mic_mask;
    uint8_t gain_db;
    uint16_t max_s;
    uint32_t take_start_ms;
    uint32_t take_end_ms;
    // DRAINING
    bool end_emitted;           // END queued, waiting for it to go out
    bool close_pending;         // the session closes after the drain
    bool ble_lost;
    uint32_t ble_lost_ms;
    bool imu_lost;
    uint32_t imu_lost_ms;
    bool shut;                  // SHUTDOWN seen: no OPEN until rec_fsm_init()
} rec_fsm_t;

void rec_fsm_init(rec_fsm_t *f, uint32_t now_ms);
rec_out_t rec_fsm_step(rec_fsm_t *f, const rec_input_t *in, const rec_env_t *env,
                       uint32_t now_ms);

// A session is open (not IDLE): blocks sleep, OTA and calibration.
bool rec_fsm_session_open(const rec_fsm_t *f);
// What REC_ACT_SET_PROFILE asks for: the take's profile while RECORDING,
// else LEVEL.
uint8_t rec_fsm_profile(const rec_fsm_t *f);
// Seconds of the current take, or of the last one after it ended.
uint16_t rec_fsm_elapsed_s(const rec_fsm_t *f, uint32_t now_ms);
// The battery refuses a TAKE_START (REC_ERR_BATTERY): below
// REC_BATT_START_PCT without USB. Only env's battery fields are read
// (rec_state's APP_REC_FLAG_BATTERY_LOW).
bool rec_fsm_battery_blocks_take(const rec_env_t *env);

#ifdef __cplusplus
}
#endif
