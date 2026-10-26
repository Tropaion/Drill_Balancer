#pragma once

// Inside the recorder component: what rec_wifi.c and rec_audio.c need from
// recorder.c, and their own interfaces. ESP glue, device only.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"
#include "rec_fsm.h"
#include "rec_ring.h"
#include "rec_util.h"

// ---- recorder.c ----------------------------------------------------------------

// An FSM input from another task (the sensor task, the NimBLE host, the
// Wi-Fi event loop, rec_tx). Waits at most 50 ms for room in the queue,
// which ticks and DRAINED cannot fill (one of each waits at most, however
// long the rec task is held off); an input that still finds it full is lost
// (logged).
void rec_glue_input(rec_input_kind_t kind);

// rec_tx: the end of a rec_wifi_start() (AP_FAIL) or rec_wifi_stop()
// (AP_DOWN). Waits for room in the queue as long as it takes (the rec task
// never blocks for long): the rec task's Wi-Fi bookkeeping must not lose it.
void rec_glue_wifi(rec_input_kind_t kind);

// rec_tx: HELLO_OK of the client rec_wifi.c numbered `client`. The rec task
// ignores it once that client is gone (rec_wifi_is_client()).
void rec_glue_hello(uint32_t client);

// rec_tx emptied the ring: moves a draining take on (DRAINED).
void rec_glue_sent(void);

// rec_mic: one DMA block of capture gen (from rec_audio_start()), already
// the take's channels (rec_audio_select() with the capture's mic_mask): n
// samples for `frames` frames, `clipped` of them at full scale; its index
// since that capture's start, the blocks lost right before it, and the
// esp_timer when it completed. An AUDIO record while the take records audio
// from that capture; a block of an older one (read while the next take
// started) is dropped.
void rec_glue_audio_block(uint32_t gen, const int16_t *samples, size_t n, size_t frames,
                          uint32_t clipped, uint32_t block, uint32_t lost_before, int64_t host_us);

// rec_mic: capture gen could not start (no ES7210, I2C or I2S error). The
// take goes on without audio: rec_state drops APP_REC_FLAG_AUDIO, END and
// STATS count no audio frames.
void rec_glue_audio_failed(uint32_t gen);

// ---- rec_wifi.c: soft-AP, TCP server, sender -------------------------------------

typedef struct {
    char ssid[REC_SSID_LEN + 1];
    char pass[REC_PASS_LEN + 1];
    uint8_t token[REC_HELLO_TOKEN_LEN];
    uint8_t ip[4];
    uint16_t port;
    uint8_t channel;
} rec_wifi_info_t;

// The rec_tx task (idle until rec_wifi_start()); ring is the recorder's.
esp_err_t rec_wifi_init(rec_ring_t *ring);

// Both return at once: rec_tx carries them out in order, so neither the rec
// task nor the NimBLE host waiting on its answer waits for Wi-Fi (seconds).
// Every rec_wifi_start() is followed by one rec_wifi_stop(), also after
// AP_FAIL, and the next start only comes after that stop's AP_DOWN.
//
// rec_wifi_start(): brings the AP up with new credentials and, once it
// runs, the stream server. AP_UP (rec_wifi_info() has the credentials
// then) and CLIENT_LOST arrive through rec_glue_input(), HELLO_OK through
// rec_glue_hello(); a failure is AP_FAIL through rec_glue_wifi(), with
// nothing left running.
void rec_wifi_start(void);

// rec_wifi_stop(): the sender gets up to drain_ms to empty the ring; then
// the stream ends (FIN after what lwIP still holds, END among it) and the
// client gets up to REC_WIFI_LINGER_MS to take it and close its side,
// before the client and the server are closed and Wi-Fi is torn down
// completely (esp_wifi_deinit, the netif destroyed). AP_DOWN through
// rec_glue_wifi() when done, within REC_WIFI_STOP_MAX_MS(drain_ms) of the
// call (a start still running included).
void rec_wifi_stop(uint32_t drain_ms);
#define REC_WIFI_LINGER_MS 1000u
// esp_wifi_init() .. esp_wifi_start(), or esp_wifi_stop() .. the netif
// destroyed. TODO(hw): measure both (the soft-AP up / down log lines,
// bring-up step 36) and keep this above the worst.
#define REC_WIFI_SETUP_MS  1500u
// The drain, one send() blocked to its SO_SNDTIMEO (500 ms), the linger,
// and a start still running before the teardown.
#define REC_WIFI_STOP_MAX_MS(drain_ms) \
    ((drain_ms) + 500u + REC_WIFI_LINGER_MS + 2u * REC_WIFI_SETUP_MS)

// The credentials of the AP rec_wifi_start() brought up (zero before).
void rec_wifi_info(rec_wifi_info_t *out);

// A stream client passed HELLO and is connected.
bool rec_wifi_client(void);

// Whether `client` (rec_glue_hello()) is the one connected now.
bool rec_wifi_is_client(uint32_t client);

// The FSM took the client (HELLO_OK -> READY): rec_tx starts sending the
// ring to it, unless it is gone already. A client it loses stops that, and
// a newly accepted one waits for its own call, so it never gets what the
// ring held before CLIENT_LOST's RING_RESET.
void rec_wifi_serve(uint32_t client);

// New data in the ring: wakes the sender once a send()'s worth is queued
// (it polls every 20 ms anyway), so it sends a few MSS at a time instead
// of every small record on its own. Any task.
void rec_wifi_kick(void);

// END was just pushed (call it with the ring lock held, so the sender has
// not taken it yet): the stream up to it goes out without waiting for
// Nagle (TCP_NODELAY until the sender is past it).
void rec_wifi_push(void);

// ---- rec_audio.c: I2S RX + ES7210 ------------------------------------------------

// The rec_mic task (idle until rec_audio_start()).
esp_err_t rec_audio_init(void);

// Asynchronous: the capture task powers the microphones up with this mask
// and gain and streams blocks of capture gen to rec_glue_audio_block()
// until stopped (rec_glue_audio_failed() if they do not start, or the
// start does not reach the capture task, called from here then: not from
// inside a producer gate). The block index starts at 0.
void rec_audio_start(uint8_t mic_mask, uint8_t gain_db, uint32_t gen);
void rec_audio_stop(void);
