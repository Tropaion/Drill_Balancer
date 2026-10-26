#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "recorder.h"
#include "rec_glue.h"

#include "bootloader_random.h"
#include "esp_app_desc.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "imu.h"
#include "mbedtls/sha256.h"
#include "nvs.h"
#include "settings_store.h"

static const char *TAG = "rec";

_Static_assert(APP_REC_STATE_IDLE == REC_STATE_IDLE && APP_REC_STATE_READY == REC_STATE_READY &&
               APP_REC_STATE_RECORDING == REC_STATE_RECORDING &&
               APP_REC_STATE_DRAINING == REC_STATE_DRAINING, "app_state rec states");
_Static_assert(REC_PROFILE_LEVEL == IMU_PROFILE_LEVEL && REC_PROFILE_RAW897 == IMU_PROFILE_RAW897 &&
               REC_PROFILE_COUNT == IMU_PROFILE_COUNT, "rec profiles");
_Static_assert(REC_IMU_FRAME_LEN == IMU_FRAME_BYTES, "IMU frame");

// The FSM task: short steps only (the SESSION JSON with cJSON on the heap
// is the longest); Wi-Fi comes up and goes down in rec_tx (rec_wifi.c), so
// a command is answered within milliseconds. Same priority as the control
// task, which only waits for it in recorder_shutdown(); below the sensor
// task and LVGL.
// TODO(hw): check uxTaskGetStackHighWaterMark() after a session with a
// take (SESSION, END) and a close (bring-up step 26).
#define REC_TASK_PRIO      5
#define REC_TASK_STACK     4096
#define REC_QUEUE_LEN      24
#define REC_TICK_MS        100u     // the FSM tick timer (rec_fsm.h TICK)
#define REC_LEVEL_MS       100u     // LEVEL records, 10 Hz
#define REC_STATS_MS       1000u    // STATS records, 1 Hz
#define REC_RING_SIZE      (4u * 1024u * 1024u)   // ~58 s of Wi-Fi stall
#define REC_STOP_AP_MS     200u     // STOP_AP: what is queued goes out first
// recorder_post(): the rec task takes a command within this (it never
// blocks for long); one it has taken is waited for up to REC_CLAIMED_MS.
#define REC_POST_WAIT_MS   500u
#define REC_CLAIMED_MS     2000u
// recorder_shutdown(): the Wi-Fi stop at its slowest, and a little more.
#define REC_SHUTDOWN_MS    (REC_WIFI_STOP_MAX_MS(REC_STOP_AP_MS) + 500u)
#define BOARD_ID_NVS_NS    "recorder"
#define BOARD_ID_NVS_KEY   "salt"
#define BOARD_ID_SALT_LEN  16

typedef enum {
    MSG_CMD = 0,
    MSG_INPUT,
    MSG_TICK,
    MSG_SHUTDOWN,
} msg_kind_t;

typedef struct {
    uint8_t kind;               // msg_kind_t
    uint8_t input;              // MSG_INPUT: rec_input_kind_t
    bool reply;                 // MSG_CMD from recorder_post()
    uint32_t req;               // MSG_CMD: the request; HELLO_OK: the client
    rec_cmd_t cmd;
} rec_msg_t;

static bool s_inited;
static QueueHandle_t s_queue;
static TaskHandle_t s_task;
static esp_timer_handle_t s_tick;
static recorder_hooks_t s_hooks;
static volatile recorder_level_fn s_level_fn;

// The FSM and what rec_state shows: written by the rec task under s_lock,
// read by recorder_refresh() (BLE) with a try-lock.
static SemaphoreHandle_t s_lock;
static rec_fsm_t s_fsm;
static rec_wifi_info_t s_wifi;
static bool s_wifi_up;              // AP_UP: s_wifi holds the AP's credentials
static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;
static bool s_notify;               // REC_ACT_NOTIFY since recorder_take_notify()
static volatile bool s_draining;    // REC_STATE_DRAINING, for rec_glue_sent()

// The ring: allocated at OPEN in PSRAM, freed back once the session is
// closed and Wi-Fi is down. Its lock is recursive, so END can read the seq
// it will get and push under one lock. s_buf and the ring's set-up change
// under s_lock too (publish_locked() reads them).
static SemaphoreHandle_t s_ring_mutex;
static rec_ring_t s_ring;
static uint8_t *s_buf;
static rec_ring_stats_t s_ring_last;    // of the freed ring, for rec_state in IDLE

// The take's sources, guarded by s_src_lock. A producer checks its gate
// and pushes under the lock, so once an action has closed the gate nothing
// of that source follows (rec_fsm.h action order).
static SemaphoreHandle_t s_src_lock;
static struct {
    bool tap;                       // IMU, LEVEL, STATS, VIB and TOUCH records flow
    uint8_t profile;                // the take's (rec_fsm_profile() while tap)
    rec_imu_tap_t imu;
    rec_rate_t imu_rate;
    bool audio_on;
    uint32_t audio_gen;             // the capture rec_audio_start() was given
    uint32_t audio_frames, audio_overflows, audio_clipped;
    rec_rate_t audio_rate;
} s_src;
// Record bodies, each used by one task, in PSRAM (recorder_init()).
typedef struct {
    int16_t imu[REC_IMU_MAX_FRAMES][6];                     // sensor task
    char tail[REC_MAX_PAYLOAD - sizeof(rec_session_t)];     // rec: SESSION JSON
} scratch_t;
static scratch_t *s_scratch;

// rec task only
static uint32_t s_last_level_ms, s_last_stats_ms;
// What rec_tx was asked for (rec_wifi.c): ON from START_AP, STOPPING from
// STOP_AP until its AP_DOWN. The ring stays and no new OPEN is taken until
// it is OFF again, also when the FSM reached IDLE by its CLOSING timeout.
typedef enum {
    WIFI_OFF = 0,
    WIFI_ON,
    WIFI_STOPPING,
} wifi_req_t;
static wifi_req_t s_wifi_req;
static bool s_down_pending;         // STOP_AP with Wi-Fi never asked for: AP_DOWN now
static bool s_shutdown_waiting;     // MSG_SHUTDOWN answers once Wi-Fi is OFF
static uint8_t s_board_id[4];
static char s_fw[REC_FW_LEN];

// recorder_post(): one caller waits at a time. The rec task claims the
// request before it carries it out; one the caller gave up on before that
// is dropped, so a BUSY answer always means it was not done. Under s_mux.
static SemaphoreHandle_t s_post_lock;
static SemaphoreHandle_t s_reply;
static uint32_t s_req_seq;          // numbers the requests
static uint32_t s_req;              // the request a caller waits for, 0 none
static bool s_req_claimed;          // the rec task carries s_req out
static rec_err_t s_reply_err;
static SemaphoreHandle_t s_shutdown_done;

// At most one MSG_TICK and one DRAINED wait in the queue. The rec task never
// blocks for long, but it runs below the sensor task, LVGL and rec_mic, and
// rec_tx posts DRAINED every time it empties the ring while a take drains:
// a backlog of either would only repeat itself, and must not push out the
// inputs that matter (CLIENT_LOST, HELLO_OK).
static volatile bool s_tick_queued;
static volatile bool s_drained_queued;

static void lock(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
}

static void unlock(void)
{
    xSemaphoreGive(s_lock);
}

static void src_lock(void)
{
    xSemaphoreTake(s_src_lock, portMAX_DELAY);
}

static void src_unlock(void)
{
    xSemaphoreGive(s_src_lock);
}

static void ring_lock(void *ctx)
{
    xSemaphoreTakeRecursive((SemaphoreHandle_t)ctx, portMAX_DELAY);
}

static void ring_unlock(void *ctx)
{
    xSemaphoreGiveRecursive((SemaphoreHandle_t)ctx);
}

static uint64_t host_us(void)
{
    return (uint64_t)esp_timer_get_time();
}

static void ring_stats(rec_ring_stats_t *st)
{
    if (s_buf) {
        rec_ring_stats(&s_ring, st);
    } else {
        *st = s_ring_last;
    }
}

// Producers: s_buf is set at OPEN and freed in IDLE, after every gate closed.
static bool push(uint8_t type, uint8_t ver, const rec_part_t *parts, size_t nparts)
{
    if (!s_buf) {
        return false;
    }
    bool ok = rec_ring_push(&s_ring, type, ver, parts, nparts, NULL);
    rec_wifi_kick();
    return ok;
}

static void push_event(uint16_t code, uint16_t arg, const char *text)
{
    uint8_t e[REC_EVENT_MAX];
    const rec_part_t p = {e, rec_event_payload(e, host_us(), code, arg, text)};
    push(REC_T_EVENT, REC_V_EVENT, &p, 1);
}

// ---- board id ------------------------------------------------------------------

// meta.device.board_id: SHA-256 over a random salt kept in NVS and the
// eFuse MAC, first 4 bytes. Stable across boots and firmware updates, and
// the MAC cannot be recovered from it (the salt never leaves the device).
// Without NVS the salt is random per boot (logged).
// recorder_init() runs before Wi-Fi or BT, when esp_fill_random() is only
// pseudo-random (ESP-IDF random.rst): the SAR ADC entropy source is on for
// the salt. Nothing uses the ADC, and the RF is off until ble_service_init().
static void new_salt(uint8_t *salt, size_t len)
{
    bootloader_random_enable();
    esp_fill_random(salt, len);
    bootloader_random_disable();
}

static void make_board_id(void)
{
    uint8_t salt[BOARD_ID_SALT_LEN];
    bool stored = false;
    nvs_handle_t h;
    if (nvs_open(BOARD_ID_NVS_NS, NVS_READWRITE, &h) == ESP_OK) {
        size_t len = sizeof(salt);
        if (nvs_get_blob(h, BOARD_ID_NVS_KEY, salt, &len) == ESP_OK && len == sizeof(salt)) {
            stored = true;
        } else {
            new_salt(salt, sizeof(salt));
            stored = nvs_set_blob(h, BOARD_ID_NVS_KEY, salt, sizeof(salt)) == ESP_OK &&
                     nvs_commit(h) == ESP_OK;
        }
        nvs_close(h);
    }
    if (!stored) {
        new_salt(salt, sizeof(salt));
        ESP_LOGW(TAG, "board id salt not stored: the board id changes with every boot");
    }
    uint8_t mac[6] = {0};
    esp_efuse_mac_get_default(mac);
    uint8_t in[BOARD_ID_SALT_LEN + sizeof(mac)];
    memcpy(in, salt, sizeof(salt));
    memcpy(in + sizeof(salt), mac, sizeof(mac));
    uint8_t out[32];
    mbedtls_sha256(in, sizeof(in), out, 0);
    memcpy(s_board_id, out, sizeof(s_board_id));
    memset(in, 0, sizeof(in));
    memset(salt, 0, sizeof(salt));
}

// ---- rec_state -----------------------------------------------------------------

// app_state_set_rec(), with s_lock held.
static void publish_locked(void)
{
    const rec_fsm_t *f = &s_fsm;
    app_snapshot_t snap;
    app_state_get(&snap);
    const app_battery_t *b = &snap.battery;
    const rec_env_t env = {.battery_valid = b->valid, .battery_pct = b->percent,
                           .vbus = b->vbus_present};
    rec_ring_stats_t st;
    ring_stats(&st);
    app_rec_status_t r = {
        .state = (uint8_t)f->state,
        .reason = f->reason,
        .flags = (uint8_t)((rec_wifi_client() ? APP_REC_FLAG_CLIENT : 0u) |
                           (s_src.audio_on ? APP_REC_FLAG_AUDIO : 0u) |
                           (f->imu_lost ? APP_REC_FLAG_IMU_LOST : 0u) |
                           (rec_fsm_battery_blocks_take(&env) ? APP_REC_FLAG_BATTERY_LOW : 0u)),
        .sent_bytes = st.sent_bytes,
        .dropped = st.dropped_records,
        .ring_pct = rec_ring_pct(st.used, st.cap),
    };
    if (f->take_valid) {
        r.profile = f->profile;
        r.take_id = f->take_id;
        r.elapsed_s = rec_fsm_elapsed_s(f, app_now_ms());
        r.max_s = f->max_s;
    }
    if (f->state != REC_STATE_IDLE && s_wifi_up) {
        r.channel = s_wifi.channel;
        r.port = s_wifi.port;
        memcpy(r.ip, s_wifi.ip, sizeof(r.ip));
        snprintf(r.ssid, sizeof(r.ssid), "%s", s_wifi.ssid);
        snprintf(r.pass, sizeof(r.pass), "%s", s_wifi.pass);
        memcpy(r.token, s_wifi.token, sizeof(r.token));
    }
    app_state_set_rec(&r);
}

void recorder_refresh(void)
{
    if (!s_inited || xSemaphoreTake(s_lock, 0) != pdTRUE) {
        return;
    }
    publish_locked();
    unlock();
}

bool recorder_take_notify(void)
{
    taskENTER_CRITICAL(&s_mux);
    bool n = s_notify;
    s_notify = false;
    taskEXIT_CRITICAL(&s_mux);
    return n;
}

void recorder_set_level_encoder(recorder_level_fn fn)
{
    s_level_fn = fn;
}

// ---- actions -------------------------------------------------------------------

// The SESSION record's JSON tail: settings and calibration in effect.
static int session_json(char *out, size_t cap)
{
    settings_t s;
    settings_get(&s);
    float gb[3];
    bool have = s_hooks.gyro_bias && s_hooks.gyro_bias(gb);
    const vec3_t bias = {gb[0], gb[1], gb[2]};
    return settings_session_json(&s, have ? &bias : NULL, out, cap);
}

static void act_emit_session(void)
{
    const rec_fsm_t *f = &s_fsm;
    const imu_profile_info_t *p = imu_profile_info((imu_profile_t)f->profile);
    const imu_axis_map_t *map = imu_axis_map();
    uint8_t ch = rec_audio_channels(f->mic_mask);
    rec_session_t s = {
        .take_id = f->take_id,
        .start_us = host_us(),
        .profile = f->profile,
        .ctrl2 = p->ctrl2,
        .ctrl3 = p->ctrl3,
        .ctrl5 = p->ctrl5,
        .odr_mhz = p->odr_mhz,
        .accel_lsb_per_g = p->accel_lsb_per_g,
        .gyro_lsb_per_dps = p->gyro_lsb_per_dps,
        .axis = {{map->x.src, map->x.sign}, {map->y.src, map->y.sign}, {map->z.src, map->z.sign}},
        .audio_rate_hz = ch ? REC_AUDIO_RATE_HZ : 0u,
        .audio_channels = ch,
        .audio_bits = REC_AUDIO_BITS,
        .mic_mask = f->mic_mask,
        .gain_db = f->gain_db,
        .audio_block_frames = REC_AUDIO_BLOCK_FRAMES,
        .max_s = f->max_s,
    };
    memcpy(s.board_id, s_board_id, sizeof(s.board_id));
    memcpy(s.fw, s_fw, sizeof(s.fw));
    char *tail = s_scratch->tail;
    const rec_part_t parts[] = {{&s, sizeof(s)},
                                {tail, (size_t)session_json(tail, sizeof(s_scratch->tail))}};

    // The take's counters and its sources start over; no source runs yet
    // (SET_PROFILE and AUDIO_ON come later in bit order).
    src_lock();
    rec_ring_begin_take(&s_ring);
    push(REC_T_SESSION, REC_V_SESSION, parts, 2);
    s_src.imu = (rec_imu_tap_t){0};
    s_src.audio_frames = s_src.audio_overflows = s_src.audio_clipped = 0;
    rec_rate_reset(&s_src.imu_rate);
    rec_rate_reset(&s_src.audio_rate);
    src_unlock();
    s_last_level_ms = s_last_stats_ms = app_now_ms();
    ESP_LOGI(TAG, "take %lu, %s, mics 0x%x, %u dB, max %u s", (unsigned long)f->take_id, p->name,
             (unsigned)f->mic_mask, (unsigned)f->gain_db, (unsigned)f->max_s);
}

// The take's profile at its start, LEVEL at its end: the IMU records start
// once it is in effect (recorder_imu_block), and stop at once at the end,
// with LEVEL, STATS, VIB and TOUCH.
static void act_set_profile(void)
{
    uint8_t p = rec_fsm_profile(&s_fsm);
    bool take = s_fsm.state == REC_STATE_RECORDING;
    src_lock();
    bool was = s_src.tap;
    s_src.tap = take;
    s_src.profile = s_fsm.profile;
    src_unlock();
    if (s_hooks.request_profile) {
        s_hooks.request_profile(p);
    }
    if (was && !take) {
        ESP_LOGI(TAG, "take %lu ended (reason %u)", (unsigned long)s_fsm.take_id,
                 (unsigned)s_fsm.reason);
    }
}

// rec_audio.c: the I2S channel and the ES7210 only during a take. The gate
// closes at once; the capture task powers down on its own time. Each start
// is a new capture: a block of the previous one still being read when the
// next take starts is dropped (rec_glue_audio_block).
static void act_audio(bool on)
{
    src_lock();
    s_src.audio_on = on;
    uint32_t gen = on ? ++s_src.audio_gen : s_src.audio_gen;
    src_unlock();
    if (on) {
        rec_audio_start(s_fsm.mic_mask, s_fsm.gain_db, gen);
    } else {
        rec_audio_stop();
    }
}

static void act_emit_end(void)
{
    // Every source of the take has stopped: END gets next_seq, read and
    // pushed under one ring lock (taken after s_src_lock, as every producer
    // does).
    src_lock();
    ring_lock(s_ring_mutex);
    rec_ring_stats_t st;
    ring_stats(&st);
    const rec_end_t e = {
        .host_us = host_us(),
        .reason = s_fsm.reason,
        .records = st.next_seq + 1u,
        .dropped_records = st.dropped_records,
        .dropped_bytes = st.dropped_bytes,
        .imu_frames = s_src.imu.frames,
        .imu_lost = s_src.imu.lost,
        .audio_frames = s_src.audio_frames,
        .audio_overflows = s_src.audio_overflows,
    };
    const rec_part_t p = {&e, sizeof(e)};
    push(REC_T_END, REC_V_END, &p, 1);
    rec_wifi_push();            // END goes out at once, not behind Nagle
    ring_unlock(s_ring_mutex);
    src_unlock();
}

static void act_start_ap(void)
{
    // Whole for the session; PSRAM (the internal RAM is for Wi-Fi and DMA).
    uint8_t *buf = s_buf ? s_buf : heap_caps_malloc(REC_RING_SIZE, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!buf) {
        ESP_LOGE(TAG, "no PSRAM for the %u KiB stream ring", (unsigned)(REC_RING_SIZE / 1024u));
        return;
    }
    lock();     // publish_locked() reads the ring (recorder_refresh(), other tasks)
    rec_ring_init(&s_ring, buf, REC_RING_SIZE, ring_lock, ring_unlock, s_ring_mutex);
    s_buf = buf;
    unlock();
    // rec_tx brings the AP up; AP_UP or AP_FAIL follow.
    rec_wifi_start();
    s_wifi_req = WIFI_ON;
    ESP_LOGI(TAG, "session open, %u KiB stream ring", (unsigned)(REC_RING_SIZE / 1024u));
}

// rec_tx drains, ends the stream and tears Wi-Fi down; AP_DOWN follows.
static void act_stop_ap(void)
{
    ESP_LOGI(TAG, "session closing (reason %u)", (unsigned)s_fsm.reason);
    if (s_wifi_req == WIFI_ON) {
        rec_wifi_stop(REC_STOP_AP_MS);
        s_wifi_req = WIFI_STOPPING;
    } else if (s_wifi_req == WIFI_OFF) {
        s_down_pending = true;  // never asked for (no ring): down already
    }
}

static void recorder_free(void)
{
    esp_timer_stop(s_tick);     // ESP_ERR_INVALID_STATE if not running
    if (s_buf) {
        lock();
        rec_ring_stats(&s_ring, &s_ring_last);
        s_ring_last.used = 0;
        uint8_t *b = s_buf;
        s_buf = NULL;
        unlock();
        heap_caps_free(b);
    }
}

static void set_notify(void)
{
    taskENTER_CRITICAL(&s_mux);
    s_notify = true;
    taskEXIT_CRITICAL(&s_mux);
}

// The FSM's actions, in bit order (rec_fsm.h).
static void execute(const rec_out_t *o, const rec_cmd_t *cmd)
{
    uint32_t a = o->actions;
    if (a & REC_ACT_AUDIO_OFF) {
        act_audio(false);
    }
    if (a & REC_ACT_EMIT_SESSION) {
        act_emit_session();
    }
    if (a & REC_ACT_SET_PROFILE) {
        act_set_profile();
    }
    if ((a & REC_ACT_RING_RESET) && s_buf) {
        rec_ring_reset(&s_ring);
    }
    if ((a & REC_ACT_RING_FLUSH) && s_buf) {
        rec_ring_flush(&s_ring);
    }
    if (a & REC_ACT_AUDIO_ON) {
        act_audio(true);
    }
    if (a & REC_ACT_EMIT_EVENT) {
        bool mark = o->event_code == REC_EV_MARK && cmd;
        push_event(o->event_code, o->event_arg, mark ? cmd->text : NULL);
    }
    if (a & REC_ACT_EMIT_END) {
        act_emit_end();
    }
    if (a & REC_ACT_START_AP) {
        esp_timer_stop(s_tick);
        esp_timer_start_periodic(s_tick, (uint64_t)REC_TICK_MS * 1000u);
        act_start_ap();
    }
    if (a & REC_ACT_STOP_AP) {
        act_stop_ap();
    }
    if (a & REC_ACT_NOTIFY) {
        set_notify();
    }
    // The session is over once the FSM is IDLE and Wi-Fi is down: the ring
    // goes, and OTA or a calibration may have the device.
    if (s_fsm.state == REC_STATE_IDLE && s_wifi_req == WIFI_OFF) {
        recorder_free();
        app_excl_release(APP_EXCL_REC);
    }
    s_draining = s_fsm.state == REC_STATE_DRAINING;
    lock();
    publish_locked();
    unlock();
}

static rec_out_t step(rec_input_kind_t kind, const rec_cmd_t *cmd)
{
    app_snapshot_t snap;
    app_state_get(&snap);
    rec_ring_stats_t st;
    ring_stats(&st);
    // OTA, calibration and a session exclude each other (app_excl_t): an
    // OPEN takes the device for the session (given back in execute()).
    app_excl_t owner = (app_excl_t)snap.excl;
    if (kind == REC_IN_CMD && cmd && cmd->op == REC_CMD_OPEN && s_fsm.state == REC_STATE_IDLE) {
        owner = app_excl_acquire(APP_EXCL_REC);
    }
    const app_battery_t *b = &snap.battery;
    const rec_env_t env = {
        .battery_valid = b->valid,
        .battery_pct = b->percent,
        .vbus = b->vbus_present,
        .ota_active = owner == APP_EXCL_OTA,
        .calibrating = owner == APP_EXCL_CAL,
        .ring_empty = st.used == 0,
    };
    const rec_input_t in = {.kind = kind, .cmd = cmd};
    lock();
    rec_out_t o = rec_fsm_step(&s_fsm, &in, &env, app_now_ms());
    unlock();
    execute(&o, cmd);
    return o;
}

// The sender saw the ring empty, or the tick: a draining take moves on.
static void check_drained(void)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    if (s_fsm.state == REC_STATE_DRAINING && st.used == 0) {
        step(REC_IN_DRAINED, NULL);
    }
}

static void emit_level(void)
{
    recorder_level_fn fn = s_level_fn;
    if (!fn) {
        return;
    }
    // The angles and the zero they are relative to come from one publish.
    app_snapshot_t snap;
    app_state_get(&snap);
    rec_level_t l = {0};
    fn(&snap, app_now_ms(), &l);
    l.host_us = host_us();
    const rec_part_t p = {&l, sizeof(l)};
    src_lock();
    if (s_src.tap) {
        push(REC_T_LEVEL, REC_V_LEVEL, &p, 1);
    }
    src_unlock();
}

static void emit_stats(void)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    app_snapshot_t snap;
    app_state_get(&snap);
    const app_battery_t *b = &snap.battery;
    src_lock();
    const rec_stats_t s = {
        .host_us = host_us(),
        .imu_frames = s_src.imu.frames,
        .imu_lost = s_src.imu.lost,
        .imu_period_ns = rec_rate_period_ns(&s_src.imu_rate),
        .audio_frames = s_src.audio_frames,
        .audio_overflows = s_src.audio_overflows,
        .audio_rate_mhz = rec_rate_mhz(&s_src.audio_rate),
        .audio_clipped = s_src.audio_clipped,
        .ring_high_water = (uint32_t)st.high_water,
        .ring_size = (uint32_t)st.cap,
        .dropped_records = st.dropped_records,
        .dropped_bytes = st.dropped_bytes,
        .battery_mv = b->voltage_mv,
        .battery_pct = b->percent,
        .power_flags = (uint8_t)((b->valid ? REC_STATS_BATTERY_VALID : 0u) |
                                 (b->vbus_present ? REC_STATS_VBUS : 0u) |
                                 (b->charging ? REC_STATS_CHARGING : 0u)),
    };
    const rec_part_t p = {&s, sizeof(s)};
    if (s_src.tap) {
        push(REC_T_STATS, REC_V_STATS, &p, 1);
    }
    src_unlock();
}

static void tick(void)
{
    step(REC_IN_TICK, NULL);
    uint32_t now = app_now_ms();
    bool tap = s_fsm.state == REC_STATE_RECORDING;
    if (tap && now - s_last_level_ms >= REC_LEVEL_MS) {
        s_last_level_ms += REC_LEVEL_MS;
        emit_level();
    }
    if (tap && now - s_last_stats_ms >= REC_STATS_MS) {
        s_last_stats_ms += REC_STATS_MS;
        emit_stats();
    }
    check_drained();
}

// rec_ctrl and the UI. OPEN answers once the ring is there; Wi-Fi comes up
// in rec_tx meanwhile (AP_UP or AP_FAIL then).
static rec_err_t command(const rec_cmd_t *cmd)
{
    if (cmd->op == REC_CMD_OPEN && s_fsm.state == REC_STATE_IDLE && s_wifi_req != WIFI_OFF) {
        // The last session's Wi-Fi is still going down (its CLOSING timed
        // out first): its ring and rec_tx are not free yet.
        return REC_ERR_STATE;
    }
    rec_err_t err = step(REC_IN_CMD, cmd).err;
    if (err == REC_OK && cmd->op == REC_CMD_OPEN && s_fsm.state == REC_STATE_AP_STARTING &&
        !s_buf) {
        err = REC_ERR_RADIO;    // the ring could not be allocated
        step(REC_IN_AP_FAIL, NULL);
    }
    return err;
}

// rec_tx brought the AP up: rec_state shows how to join it.
static void ap_up(void)
{
    if (s_wifi_req != WIFI_ON) {
        return;     // stopped meanwhile
    }
    rec_wifi_info_t info;
    rec_wifi_info(&info);
    lock();
    s_wifi = info;
    s_wifi_up = true;
    unlock();
    step(REC_IN_AP_UP, NULL);
    // TODO(hw): the free internal heap with the AP up (bring-up step 34);
    // Wi-Fi and lwIP take what PSRAM cannot hold.
    ESP_LOGI(TAG, "AP up: internal heap %u B free, largest block %u B",
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL));
}

// rec_tx finished a rec_wifi_stop(), or the stop needed none.
static void ap_down(void)
{
    s_wifi_req = WIFI_OFF;
    lock();
    s_wifi_up = false;
    memset(&s_wifi, 0, sizeof(s_wifi));
    unlock();
    step(REC_IN_AP_DOWN, NULL);     // also frees what an IDLE FSM left
}

static void reply(uint32_t req, rec_err_t err)
{
    bool give = false;
    taskENTER_CRITICAL(&s_mux);
    if (req == s_req) {
        s_reply_err = err;
        give = true;
    }
    taskEXIT_CRITICAL(&s_mux);
    if (give) {
        xSemaphoreGive(s_reply);
    }
}

// A command recorder_post() waits for is claimed first: false if its
// caller has given up (it answered BUSY), and then it is not carried out.
static bool claim(uint32_t req)
{
    taskENTER_CRITICAL(&s_mux);
    bool live = req == s_req;
    if (live) {
        s_req_claimed = true;
    }
    taskEXIT_CRITICAL(&s_mux);
    return live;
}

// A HELLO_OK of a client that is gone since (its CLIENT_LOST behind it in
// the queue) must not take the FSM to READY, or the next client would get
// the ring before that CLIENT_LOST's RING_RESET.
static void hello_ok(uint32_t client)
{
    if (!rec_wifi_is_client(client)) {
        ESP_LOGI(TAG, "HELLO_OK of a gone client ignored");
        return;
    }
    bool waiting = s_fsm.state == REC_STATE_WAIT_CLIENT;
    step(REC_IN_HELLO_OK, NULL);
    if (waiting && s_fsm.state == REC_STATE_READY) {
        rec_wifi_serve(client);     // nothing if it went meanwhile
    }
}

static void rec_task(void *arg)
{
    (void)arg;
    for (;;) {
        rec_msg_t m;
        if (xQueueReceive(s_queue, &m, portMAX_DELAY) != pdTRUE) {
            continue;
        }
        switch (m.kind) {
        case MSG_CMD: {
            if (m.reply && !claim(m.req)) {
                ESP_LOGW(TAG, "command %d dropped: its caller gave up", (int)m.cmd.op);
                break;
            }
            rec_err_t err = command(&m.cmd);
            if (m.reply) {
                reply(m.req, err);
            }
            if (err != REC_OK) {
                ESP_LOGW(TAG, "command %d refused: %d", (int)m.cmd.op, (int)err);
            }
            break;
        }
        case MSG_INPUT:
            switch (m.input) {
            case REC_IN_DRAINED:
                s_drained_queued = false;
                check_drained();    // the ring may have filled again (END)
                break;
            case REC_IN_HELLO_OK:
                hello_ok(m.req);
                break;
            case REC_IN_AP_UP:
                ap_up();
                break;
            case REC_IN_AP_DOWN:
                ap_down();
                break;
            default:
                step((rec_input_kind_t)m.input, NULL);
                break;
            }
            break;
        case MSG_TICK:
            s_tick_queued = false;
            tick();
            break;
        case MSG_SHUTDOWN:
            // In every state: the FSM takes no OPEN from now on. A session
            // ends with END and STOP_AP; the answer waits for AP_DOWN.
            ESP_LOGI(TAG, "shutdown");
            step(REC_IN_SHUTDOWN, NULL);
            s_shutdown_waiting = true;
            break;
        default:
            break;
        }
        if (s_down_pending) {
            s_down_pending = false;
            ap_down();
        }
        if (s_shutdown_waiting && s_wifi_req == WIFI_OFF) {
            s_shutdown_waiting = false;
            xSemaphoreGive(s_shutdown_done);
        }
    }
}

static void tick_cb(void *arg)
{
    (void)arg;
    if (s_tick_queued) {
        return;     // the rec task is busy: one tick catches up
    }
    s_tick_queued = true;
    rec_msg_t m = {.kind = MSG_TICK};
    if (xQueueSend(s_queue, &m, 0) != pdTRUE) {
        s_tick_queued = false;
    }
}

// ---- API -----------------------------------------------------------------------

// An FSM input for the rec task, waiting up to `wait` for room in the queue.
static void post_input(rec_input_kind_t kind, uint32_t req, TickType_t wait)
{
    if (!s_inited) {
        return;
    }
    rec_msg_t m = {.kind = MSG_INPUT, .input = (uint8_t)kind, .req = req};
    if (xQueueSend(s_queue, &m, wait) != pdTRUE) {
        ESP_LOGE(TAG, "queue full, input %d lost", (int)kind);
    }
}

void rec_glue_input(rec_input_kind_t kind)
{
    post_input(kind, 0, pdMS_TO_TICKS(50));
}

void rec_glue_wifi(rec_input_kind_t kind)
{
    // The rec task never blocks for long, so this wait ends; losing an
    // AP_DOWN would keep the ring and refuse every OPEN.
    post_input(kind, 0, portMAX_DELAY);
}

void rec_glue_hello(uint32_t client)
{
    post_input(REC_IN_HELLO_OK, client, pdMS_TO_TICKS(50));
}

void rec_glue_sent(void)
{
    if (s_draining && !s_drained_queued) {
        s_drained_queued = true;
        rec_msg_t m = {.kind = MSG_INPUT, .input = REC_IN_DRAINED};
        if (xQueueSend(s_queue, &m, 0) != pdTRUE) {
            s_drained_queued = false;
        }
    }
}

bool recorder_post(const rec_cmd_t *cmd, rec_err_t *err)
{
    if (!s_inited || !cmd || xTaskGetCurrentTaskHandle() == s_task) {
        return false;
    }
    if (xSemaphoreTake(s_post_lock, pdMS_TO_TICKS(REC_POST_WAIT_MS)) != pdTRUE) {
        return false;
    }
    taskENTER_CRITICAL(&s_mux);
    if (++s_req_seq == 0) {
        s_req_seq = 1;              // 0 is "none"
    }
    uint32_t req = s_req_seq;
    s_req = req;
    s_req_claimed = false;
    taskEXIT_CRITICAL(&s_mux);
    xSemaphoreTake(s_reply, 0);    // a stale answer
    rec_msg_t m = {.kind = MSG_CMD, .reply = true, .req = req, .cmd = *cmd};
    bool ok = xQueueSend(s_queue, &m, 0) == pdTRUE &&
              xSemaphoreTake(s_reply, pdMS_TO_TICKS(REC_POST_WAIT_MS)) == pdTRUE;
    if (!ok) {
        // Not answered in time: given up unless the rec task has taken it
        // already, then its answer is a few milliseconds away.
        taskENTER_CRITICAL(&s_mux);
        bool claimed = s_req_claimed;
        if (!claimed) {
            s_req = 0;              // the rec task drops it (claim())
        }
        taskEXIT_CRITICAL(&s_mux);
        ok = claimed && xSemaphoreTake(s_reply, pdMS_TO_TICKS(REC_CLAIMED_MS)) == pdTRUE;
        if (claimed && !ok) {
            ESP_LOGE(TAG, "command %d taken but not answered", (int)cmd->op);
        }
    }
    taskENTER_CRITICAL(&s_mux);
    if (ok) {
        *err = s_reply_err;
    }
    s_req = 0;                      // an answer after this is nobody's
    taskEXIT_CRITICAL(&s_mux);
    xSemaphoreGive(s_post_lock);
    return ok;
}

bool recorder_send(const rec_cmd_t *cmd)
{
    if (!s_inited || !cmd) {
        return false;
    }
    rec_msg_t m = {.kind = MSG_CMD, .cmd = *cmd};
    return xQueueSend(s_queue, &m, 0) == pdTRUE;
}

void recorder_note_imu(bool ok)
{
    rec_glue_input(ok ? REC_IN_IMU_BACK : REC_IN_IMU_LOST);
}

void recorder_note_ble(bool up)
{
    rec_glue_input(up ? REC_IN_BLE_BACK : REC_IN_BLE_LOST);
}

// An EVENT of the take's tap (RECORDING); false, and nothing, without one.
static bool tap_event(uint16_t code)
{
    if (!s_inited) {
        return false;
    }
    src_lock();
    bool tap = s_src.tap;
    if (tap) {
        push_event(code, 0, NULL);
    }
    src_unlock();
    return tap;
}

void recorder_note_vib(bool running)
{
    tap_event(running ? REC_EV_VIB_START : REC_EV_VIB_STOP);
}

bool recorder_note_touch(void)
{
    return tap_event(REC_EV_TOUCH);
}

void recorder_shutdown(void)
{
    if (!s_inited) {
        return;
    }
    xSemaphoreTake(s_shutdown_done, 0);
    rec_msg_t m = {.kind = MSG_SHUTDOWN};
    int64_t t0 = esp_timer_get_time();
    if (xQueueSendToFront(s_queue, &m, pdMS_TO_TICKS(100)) != pdTRUE ||
        xSemaphoreTake(s_shutdown_done, pdMS_TO_TICKS(REC_SHUTDOWN_MS)) != pdTRUE) {
        ESP_LOGE(TAG, "shutdown did not finish within %u ms", (unsigned)REC_SHUTDOWN_MS);
        return;
    }
    ESP_LOGI(TAG, "shut down in %u ms", (unsigned)((esp_timer_get_time() - t0) / 1000));
}

// The sensor tap: one IMU record per timestamp run of the read.
void recorder_imu_block(const struct imu_raw_info *raw)
{
    if (!s_inited || !raw || raw->n == 0) {
        return;
    }
    src_lock();
    uint8_t want = s_src.profile;
    if (!s_src.tap || (uint8_t)imu_get_profile() != want) {
        src_unlock();
        return;     // no take, or its profile is not in effect yet
    }
    uint32_t chip;
    if (rec_imu_tap_read(&s_src.imu, raw->epoch, raw->chip_ts, &chip)) {
        // A new chip clock: the take's first read, or the first after a reset.
        push_event(REC_EV_PROFILE, want, NULL);
    }
    for (int k = 0; k < 2; k++) {
        const imu_raw_run_t *run = &raw->run[k];
        if (run->count == 0) {
            continue;
        }
        rec_imu_t h;
        uint16_t count = rec_imu_tap_run(&s_src.imu, chip, run->ts_first, run->count,
                                         run->discontinuous, raw->host_us, &h);
        for (uint16_t i = 0; i < count; i++) {
            imu_map_frame_i16(raw->map, &raw->frames[(size_t)(run->first + i) * IMU_FRAME_BYTES],
                              s_scratch->imu[i]);
        }
        const rec_part_t p[] = {{&h, sizeof(h)},
                                {s_scratch->imu, (size_t)count * REC_IMU_FRAME_LEN}};
        push(REC_T_IMU, REC_V_IMU, p, 2);
    }
    rec_rate_update(&s_src.imu_rate, raw->epoch, chip, raw->host_us);
    src_unlock();
}

void rec_glue_audio_block(uint32_t gen, const int16_t *samples, size_t n, size_t frames,
                          uint32_t clipped, uint32_t block, uint32_t lost_before, int64_t host)
{
    src_lock();
    if (s_src.audio_on && gen == s_src.audio_gen) {
        uint64_t index = (uint64_t)block * frames;
        const rec_audio_t h = {.sample_index = index, .host_us = (uint64_t)host,
                               .n = (uint16_t)frames,
                               .flags = lost_before ? REC_AUDIO_OVF_BEFORE : 0u};
        const rec_part_t p[] = {{&h, sizeof(h)}, {samples, n * sizeof(int16_t)}};
        push(REC_T_AUDIO, REC_V_AUDIO, p, 2);
        s_src.audio_frames += (uint32_t)frames;
        s_src.audio_overflows += lost_before;
        s_src.audio_clipped += clipped;
        rec_rate_update(&s_src.audio_rate, 0, index + frames, host);
    }
    src_unlock();
}

void rec_glue_audio_failed(uint32_t gen)
{
    // rec_state loses APP_REC_FLAG_AUDIO (the BLE timer's refresh notifies
    // the change); END and STATS keep audio_frames at 0, so the take reads
    // as one without audio, not as silence. Also when the start never
    // reached rec_mic (rec_audio_start()).
    src_lock();
    if (s_src.audio_on && gen == s_src.audio_gen) {
        s_src.audio_on = false;
        ESP_LOGW(TAG, "the take goes on without audio");
    }
    src_unlock();
}

esp_err_t recorder_init(bool imu_ok, const recorder_hooks_t *hooks)
{
    if (s_inited) {
        return ESP_ERR_INVALID_STATE;
    }
    if (hooks) {
        s_hooks = *hooks;
    }
    s_lock = xSemaphoreCreateMutex();
    s_src_lock = xSemaphoreCreateMutex();
    s_ring_mutex = xSemaphoreCreateRecursiveMutex();
    s_post_lock = xSemaphoreCreateMutex();
    s_reply = xSemaphoreCreateBinary();
    s_shutdown_done = xSemaphoreCreateBinary();
    s_queue = xQueueCreate(REC_QUEUE_LEN, sizeof(rec_msg_t));
    s_scratch = heap_caps_malloc(sizeof(*s_scratch), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!s_scratch || !s_lock || !s_src_lock || !s_ring_mutex || !s_post_lock || !s_reply ||
        !s_shutdown_done || !s_queue) {
        return ESP_ERR_NO_MEM;
    }
    const esp_timer_create_args_t ta = {.callback = tick_cb, .name = "rec_tick"};
    ESP_RETURN_ON_ERROR(esp_timer_create(&ta, &s_tick), TAG, "tick timer");

    make_board_id();
    rec_fw_text(s_fw, esp_app_get_description()->version, REC_FW_GIT);
    rec_fsm_init(&s_fsm, app_now_ms());
    if (!imu_ok) {
        // Tracked in IDLE too (no actions there), so a take knows.
        const rec_input_t in = {.kind = REC_IN_IMU_LOST};
        const rec_env_t env = {0};
        rec_fsm_step(&s_fsm, &in, &env, app_now_ms());
    }

    ESP_RETURN_ON_ERROR(rec_wifi_init(&s_ring), TAG, "rec_tx");
    ESP_RETURN_ON_ERROR(rec_audio_init(), TAG, "rec_mic");
    if (xTaskCreate(rec_task, "rec", REC_TASK_STACK, NULL, REC_TASK_PRIO, &s_task) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    s_inited = true;
    lock();
    publish_locked();
    unlock();
    ESP_LOGI(TAG, "ready, board id %02x%02x%02x%02x, firmware %s", s_board_id[0], s_board_id[1],
             s_board_id[2], s_board_id[3], s_fw);
    return ESP_OK;
}
