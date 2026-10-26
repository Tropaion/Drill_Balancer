// Host mirror of the firmware's ESP glue. Each section follows one source
// file (named in its header) with the same constants and the same order of
// operations; the logic itself stays in the firmware modules linked in.
// FreeRTOS tasks, locks and timers are gone: the Python side calls the
// "task" functions one at a time on its own clock.

#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "accel_cal.h"
#include "app_state.h"
#include "ble_codec.h"
#include "ble_protocol.h"
#include "cJSON.h"
#include "fusion.h"
#include "imu_frame.h"
#include "level.h"
#include "mbedtls/sha256.h"
#include "ota_proto.h"
#include "pmu.h"
#include "pmu_events.h"
#include "power_fsm.h"
#include "rec_fsm.h"
#include "rec_ring.h"
#include "rec_stream.h"
#include "rec_ts.h"
#include "settings.h"
#include "sim_audio.h"
#include "sim_core.h"
#include "sim_rand.h"
#include "turn.h"
#include "ui_logic.h"
#include "vibration.h"

// ---- log ring (stands in for ESP_LOGx) -------------------------------------------

#define LOG_LINES 128
#define LOG_LEN   200

static char s_log[LOG_LINES][LOG_LEN];
static unsigned s_log_head, s_log_count;

__attribute__((format(printf, 2, 3)))
static void sim_log(char level, const char *fmt, ...)
{
    char *line = s_log[(s_log_head + s_log_count) % LOG_LINES];
    if (s_log_count == LOG_LINES) {
        s_log_head = (s_log_head + 1u) % LOG_LINES;   // drop the oldest
    } else {
        s_log_count++;
    }
    line[0] = level;
    line[1] = ' ';
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line + 2, LOG_LEN - 2, fmt, ap);
    va_end(ap);
}

#define LOGI(...) sim_log('I', __VA_ARGS__)
#define LOGW(...) sim_log('W', __VA_ARGS__)
#define LOGE(...) sim_log('E', __VA_ARGS__)

// Next log line ("I text", "W text", "E text"). Returns its length, 0 if none.
int sim_log_pop(char *buf, int cap)
{
    if (s_log_count == 0 || cap <= 0) {
        return 0;
    }
    snprintf(buf, (size_t)cap, "%s", s_log[s_log_head]);
    s_log_head = (s_log_head + 1u) % LOG_LINES;
    s_log_count--;
    return (int)strlen(buf);
}

// ---- core state ------------------------------------------------------------------

static uint32_t s_now;          // app_now_ms(): the latest now_ms passed in
static int s_run = SIM_STATE_OFF;
static uint32_t s_events;
static uint32_t s_faults;
static int s_busy_next;   // command writes still to answer with BUSY

void sim_fault_busy(int n)
{
    s_busy_next = n < 0 ? 0 : n;
}

static bool running(uint32_t now_ms)
{
    s_now = now_ms;
    return s_run == SIM_STATE_RUNNING;
}

static void halt(int state, uint32_t ev)
{
    s_run = state;
    s_events |= ev;
}

uint32_t sim_take_events(void)
{
    uint32_t e = s_events;
    s_events = 0;
    return e;
}

int sim_state(void)
{
    return s_run;
}

void sim_set_faults(uint32_t faults)
{
    s_faults = faults;
}

// ---- app_state.c -----------------------------------------------------------------

#define CMD_QUEUE_LEN 16

static app_snapshot_t s_state;
static uint32_t s_last_activity_ms;
static app_cmd_t s_cmds[CMD_QUEUE_LEN];
static unsigned s_cmd_head, s_cmd_count;

uint32_t app_now_ms(void)
{
    return s_now;
}

void app_state_init(void)
{
    s_state = (app_snapshot_t){
        .q = quat_identity(),
        .cal = {.kind = APP_CAL_IDLE, .last_result = -1},
    };
    s_cmd_head = s_cmd_count = 0;
    s_last_activity_ms = app_now_ms();
}

void app_state_get(app_snapshot_t *out)
{
    *out = s_state;
}

void app_state_set_motion(bool imu_ok, quat_t q, bool still, bool vibrating, bool zero_set,
                          const level_reading_t *reading, uint32_t now_ms)
{
    s_state.imu_ok = imu_ok;
    s_state.q = q;
    s_state.still = still;
    s_state.vibrating = vibrating;
    s_state.zero_set = zero_set;
    s_state.reading = *reading;
    s_state.reading_ms = now_ms;
}

void app_state_set_battery(const app_battery_t *battery)
{
    s_state.battery = *battery;
}

void app_state_set_ble(bool enabled, bool connected)
{
    s_state.ble_enabled = enabled;
    s_state.ble_connected = connected;
}

void app_state_set_ble_holds_awake(bool holds_awake)
{
    s_state.ble_holds_awake = holds_awake;
}

void app_state_set_passkey(bool pending, uint32_t passkey)
{
    s_state.passkey_pending = pending;
    s_state.passkey = passkey;
}

void app_state_set_ota(bool active, uint8_t percent)
{
    s_state.ota_active = active;
    s_state.ota_percent = percent;
    s_events |= SIM_EV_OTA;
}

void app_state_set_power(app_power_t power)
{
    s_state.power = power;
}

void app_state_set_cal(const app_cal_status_t *cal)
{
    s_state.cal = *cal;
    s_events |= SIM_EV_CAL;
}

void app_state_set_cal_valid(bool mount_cal_valid, bool accel_cal_valid)
{
    s_state.mount_cal_valid = mount_cal_valid;
    s_state.accel_cal_valid = accel_cal_valid;
}

bool app_post_cmd(app_cmd_id_t id, int32_t arg)
{
    if (s_cmd_count == CMD_QUEUE_LEN) {
        return false;
    }
    s_cmds[(s_cmd_head + s_cmd_count) % CMD_QUEUE_LEN] = (app_cmd_t){.id = id, .arg = arg};
    s_cmd_count++;
    return true;
}

static bool receive_cmd(app_cmd_t *out)
{
    if (s_cmd_count == 0) {
        return false;
    }
    *out = s_cmds[s_cmd_head];
    s_cmd_head = (s_cmd_head + 1u) % CMD_QUEUE_LEN;
    s_cmd_count--;
    return true;
}

void app_note_activity(void)
{
    s_last_activity_ms = app_now_ms();
}

uint32_t app_last_activity_ms(void)
{
    return s_last_activity_ms;
}

int sim_post_cmd(int id, int arg)
{
    return app_post_cmd((app_cmd_id_t)id, arg) ? 1 : 0;
}

void sim_note_activity(uint32_t now_ms)
{
    if (running(now_ms)) {
        app_note_activity();
    }
}

// ---- settings_store.c: a JSON state file instead of NVS --------------------------

#define PATH_MAX_LEN 1024
#define VERSION_LEN 32   // esp_app_desc_t.version

static char s_state_path[PATH_MAX_LEN];
static char s_ota_path[PATH_MAX_LEN];
static char s_default_version[VERSION_LEN + 1];
static char s_fw_ota[VERSION_LEN + 1];       // version an OTA installed ("" none)
static char s_fw_pending[VERSION_LEN + 1];   // written by an OTA, runs after the reboot
static settings_t s_current;

static cJSON *vec_json(vec3_t v)
{
    const double d[3] = {v.x, v.y, v.z};
    return cJSON_CreateDoubleArray(d, 3);
}

static bool vec_from_json(const cJSON *a, vec3_t *out)
{
    if (!cJSON_IsArray(a) || cJSON_GetArraySize(a) != 3) {
        return false;
    }
    float f[3];
    for (int i = 0; i < 3; i++) {
        const cJSON *it = cJSON_GetArrayItem(a, i);
        if (!cJSON_IsNumber(it)) {
            return false;
        }
        f[i] = (float)it->valuedouble;
    }
    *out = (vec3_t){f[0], f[1], f[2]};
    return true;
}

static void copy_str(char *dst, size_t len, const char *src)
{
    snprintf(dst, len, "%s", src ? src : "");
}

// The whole "flash": settings with calibration, and the OTA versions.
// Written to a temporary file and renamed, so a crash never leaves half a file.
static int state_save(const settings_t *s)
{
    char json[SETTINGS_JSON_MAX];
    if (settings_to_json(s, json, sizeof(json)) < 0) {
        return -1;
    }
    cJSON *root = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "settings", cJSON_Parse(json));
    cJSON_AddBoolToObject(root, "mount_cal_valid", s->mount_cal_valid);
    cJSON_AddItemToObject(root, "drill_axis", vec_json(s->drill_axis));
    cJSON_AddBoolToObject(root, "accel_cal_valid", s->accel_cal_valid);
    cJSON_AddItemToObject(root, "accel_bias", vec_json(s->accel_cal.bias));
    cJSON_AddItemToObject(root, "accel_scale", vec_json(s->accel_cal.scale));
    if (s_fw_ota[0]) {
        cJSON_AddStringToObject(root, "fw_version", s_fw_ota);
    }
    if (s_fw_pending[0]) {
        cJSON_AddStringToObject(root, "fw_pending", s_fw_pending);
    }
    char *text = cJSON_Print(root);
    cJSON_Delete(root);
    if (text == NULL) {
        return -1;
    }
    char tmp[PATH_MAX_LEN + 8];
    snprintf(tmp, sizeof(tmp), "%s.tmp", s_state_path);
    FILE *f = fopen(tmp, "w");
    int rc = -1;
    if (f != NULL) {
        bool ok = fputs(text, f) >= 0 && fputc('\n', f) != EOF;
        ok = fclose(f) == 0 && ok;
        rc = ok && rename(tmp, s_state_path) == 0 ? 0 : -1;
    }
    free(text);
    if (rc != 0) {
        LOGE("storing state to %s failed", s_state_path);
    }
    return rc;
}

static char *read_file(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        return NULL;
    }
    char *buf = NULL;
    if (fseek(f, 0, SEEK_END) == 0) {
        long n = ftell(f);
        if (n >= 0 && n < 65536 && fseek(f, 0, SEEK_SET) == 0) {
            buf = malloc((size_t)n + 1);
            if (buf && fread(buf, 1, (size_t)n, f) == (size_t)n) {
                buf[n] = '\0';
            } else {
                free(buf);
                buf = NULL;
            }
        }
    }
    fclose(f);
    return buf;
}

// load() of settings_store.c: defaults unless the stored state is usable.
static void state_load(settings_t *out)
{
    *out = settings_defaults();
    s_fw_ota[0] = s_fw_pending[0] = '\0';
    char *text = read_file(s_state_path);
    if (text == NULL) {
        LOGI("no stored settings, using defaults");
        return;
    }
    cJSON *root = cJSON_Parse(text);
    free(text);
    if (!cJSON_IsObject(root)) {
        LOGW("stored state unreadable, using defaults");
        cJSON_Delete(root);
        return;
    }
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(root, "fw_version");
    if (cJSON_IsString(v)) {
        copy_str(s_fw_ota, sizeof(s_fw_ota), v->valuestring);
    }
    v = cJSON_GetObjectItemCaseSensitive(root, "fw_pending");
    if (cJSON_IsString(v)) {
        copy_str(s_fw_pending, sizeof(s_fw_pending), v->valuestring);
    }

    settings_t t = settings_defaults();
    char *sj = cJSON_PrintUnformatted(cJSON_GetObjectItemCaseSensitive(root, "settings"));
    bool ok = sj != NULL && settings_apply_json(&t, sj, strlen(sj)) == SETTINGS_JSON_OK;
    free(sj);
    ok = ok && vec_from_json(cJSON_GetObjectItemCaseSensitive(root, "drill_axis"), &t.drill_axis) &&
         vec_from_json(cJSON_GetObjectItemCaseSensitive(root, "accel_bias"), &t.accel_cal.bias) &&
         vec_from_json(cJSON_GetObjectItemCaseSensitive(root, "accel_scale"), &t.accel_cal.scale);
    t.mount_cal_valid = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(root, "mount_cal_valid"));
    t.accel_cal_valid = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(root, "accel_cal_valid"));
    cJSON_Delete(root);
    if (!ok || !settings_validate(&t)) {
        LOGW("stored settings out of range, using defaults");
        return;
    }
    *out = t;
    LOGI("settings loaded (mount cal %s, accel cal %s)", out->mount_cal_valid ? "yes" : "no",
         out->accel_cal_valid ? "yes" : "no");
}

static int store(const settings_t *s)
{
    if (s_faults & SIM_FAULT_STORE) {
        LOGE("storing settings: ESP_FAIL (injected)");
        return -1;
    }
    return state_save(s);
}

void settings_get(settings_t *out)
{
    *out = s_current;
}

// settings_update() with the same rules; returns 0, 1 (ESP_ERR_INVALID_ARG)
// or -1 (store failed).
#define ERR_INVALID_ARG 1
typedef bool (*settings_update_fn)(settings_t *s, void *ctx);

static int settings_update(settings_update_fn fn, void *ctx)
{
    settings_t next = s_current;
    bool changed = fn(&next, ctx) && !settings_equal(&next, &s_current);
    int err = 0;
    if (changed) {
        err = settings_validate(&next) ? store(&next) : ERR_INVALID_ARG;
        if (err == 0) {
            s_current = next;
        }
    }
    if (changed && err == 0 && !app_post_cmd(APP_CMD_SETTINGS_CHANGED, 0)) {
        LOGW("command queue full, settings change not announced");
    }
    return err;
}

static bool reset_to_defaults(settings_t *s, void *ctx)
{
    (void)ctx;
    *s = settings_defaults();
    return true;
}

static int settings_reset(void)
{
    return settings_update(reset_to_defaults, NULL);
}

// ---- imu.c: the QMI8658 and the driver's FIFO read -------------------------------

// The time a FIFO read keeps the chip in read mode, in which every sample
// that arrives is lost (datasheet 8.7): the CTRL1 / CTRL9 handshake, then 12
// bytes a frame at 400 kHz (9 clocks a byte), with some jitter from the
// shared bus. It gives the ~12 % imu_frame.h expects at 448 Hz.
#define CHIP_READ_OVERHEAD_US 270.0f
#define CHIP_READ_JITTER_US   120.0f      // +- uniform
#define CHIP_FRAME_US         270.0f
#define CHIP_CONFIGURE_MS     180u         // configure(): reset and gyro start-up

static uint32_t s_rng = 0x2545F491u;       // sim_rand.h's generator: sim_seed()

// The chip: what the driver cannot see directly.
static struct {
    imu_profile_t profile;      // configured (s_profile of imu.c)
    bool busy;                  // configure() runs: the sensor task is blocked
    uint32_t busy_until;
    uint32_t ts;                // TIMESTAMP of the newest sample, 24 bit
    uint8_t fifo[IMU_FIFO_MAX_FRAMES][IMU_FRAME_BYTES];
    size_t head, count;
    float read_mode;            // sample periods still to come in read mode
} s_chip;

// imu.c statics
static imu_gap_t s_gap;
static imu_rate_t s_rate;
static imu_sample_t s_last;
static bool s_have_last;
static uint32_t s_epoch;          // configure() runs (imu_raw_info_t.epoch)
static uint8_t s_fifo_buf[IMU_FIFO_MAX_FRAMES * IMU_FRAME_BYTES];

// imu.c IMU_AXIS_MAP (identity until checked on the board, so the chip
// frame below is the board frame).
static const imu_axis_map_t IMU_AXIS_MAP = {.x = {0, 1}, .y = {1, 1}, .z = {2, 1}};

static const imu_profile_info_t *profile(void)
{
    return imu_profile_info(s_chip.profile);
}

static float odr_hz(void)
{
    return (float)profile()->odr_mhz * 1e-3f;
}

float sim_imu_odr_hz(void)
{
    return odr_hz();
}

// configure(): the reset restarts TIMESTAMP (TODO(hw) in imu.h: from 0) and
// empties the FIFO; the gap and ODR bookkeeping start over in a new epoch.
// wait: the sensor task is blocked for CHIP_CONFIGURE_MS (not at boot, where
// imu_init() runs before the task).
static void configure(uint32_t now, bool wait)
{
    s_chip.busy = wait;
    s_chip.busy_until = now + CHIP_CONFIGURE_MS;
    s_chip.ts = 0;
    s_chip.head = s_chip.count = 0;
    s_chip.read_mode = 0.0f;
    s_gap = (imu_gap_t){0};
    imu_rate_init(&s_rate, 1.0f / odr_hz());
    s_have_last = false;
    s_epoch++;
}

// imu_sample_period_s() (imu.h declares the driver's own).
static float drv_sample_period_s(void)
{
    return s_rate.period_s;
}

// The chip's ADC: one value in LSB, rounded and clipped to 16 bits.
static void put_lsb(uint8_t *p, float v, float lsb_per_unit)
{
    float x = roundf(v * lsb_per_unit);
    int16_t r = x >= 32767.0f ? 32767 : x <= -32768.0f ? -32768 : (int16_t)x;
    p[0] = (uint8_t)((uint16_t)r & 0xFFu);
    p[1] = (uint8_t)((uint16_t)r >> 8);
}

// Samples arriving at the chip: TIMESTAMP counts each, the FIFO (stream
// mode, the oldest goes when full) keeps those not lost to a read.
static void chip_feed(const float *v, int n)
{
    const imu_profile_info_t *p = profile();
    for (int i = 0; i < n; i++, v += 6) {
        if (s_chip.busy) {
            continue;           // the FIFO is reset when configure() ends
        }
        s_chip.ts = (s_chip.ts + 1u) & IMU_TS_MASK;
        if (s_chip.read_mode >= 1.0f) {
            s_chip.read_mode -= 1.0f;
            continue;
        }
        if (s_chip.count == IMU_FIFO_MAX_FRAMES) {
            s_chip.head = (s_chip.head + 1u) % IMU_FIFO_MAX_FRAMES;
            s_chip.count--;
        }
        uint8_t *f = s_chip.fifo[(s_chip.head + s_chip.count) % IMU_FIFO_MAX_FRAMES];
        for (int k = 0; k < 3; k++) {
            put_lsb(&f[2 * k], v[k], (float)p->accel_lsb_per_g);
            put_lsb(&f[6 + 2 * k], LEVEL_RAD2DEG(v[3 + k]), (float)p->gyro_lsb_per_dps);
        }
        s_chip.count++;
    }
}

// imu_read_fifo_ex() (read_fifo_locked()): the level read (TIMESTAMP and
// count from one instant), the gap plan, the burst read, the raw runs,
// decoding and gap filling.
static void drv_read_fifo(imu_sample_t *out, size_t max, size_t *n_out, imu_raw_info_t *raw,
                          int64_t now_us)
{
    uint32_t ts = s_chip.ts;
    size_t frames = s_chip.count;
    imu_rate_update(&s_rate, ts, now_us);

    imu_gap_plan_t plan = imu_gap_plan(&s_gap, ts, frames, s_rate.measured, max);
    size_t n = plan.n_read;
    size_t fill = plan.n_fill;
    for (size_t i = 0; i < n; i++) {
        memcpy(&s_fifo_buf[i * IMU_FRAME_BYTES],
               s_chip.fifo[(s_chip.head + i) % IMU_FIFO_MAX_FRAMES], IMU_FRAME_BYTES);
    }
    if (n > 0) {
        s_chip.head = (s_chip.head + n) % IMU_FIFO_MAX_FRAMES;
        s_chip.count -= n;
        float us = CHIP_READ_OVERHEAD_US + CHIP_READ_JITTER_US * sim_urand(&s_rng) +
                   CHIP_FRAME_US * (float)n;
        s_chip.read_mode += us * 1e-6f * odr_hz();
    }
    *raw = (imu_raw_info_t){0};
    imu_raw_runs(&plan, raw->run);
    raw->frames = s_fifo_buf;
    raw->n = (uint16_t)n;
    raw->map = &IMU_AXIS_MAP;
    raw->chip_ts = ts;
    raw->host_us = now_us;
    raw->epoch = s_epoch;

    const imu_profile_info_t *p = profile();
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        imu_sample_t s;
        imu_decode_frame(&IMU_AXIS_MAP, p, &s_fifo_buf[i * IMU_FRAME_BYTES], &s);
        if (i == plan.insert_at && fill > 0) {
            imu_fill_gap(s_have_last ? &s_last : &s, &s, fill, &out[o]);
            o += fill;
        }
        out[o++] = s;
    }
    if (o > 0) {
        s_last = out[o - 1];
        s_have_last = true;
    }
    imu_gap_commit(&s_gap, ts, frames, n);
    *n_out = o;
}

// ---- sensor_task.c ---------------------------------------------------------------

#define FIFO_BATCH          64
#define NO_IMU_PUBLISH_MS   200
#define IMU_LOST_MS         200
#define IMU_RETRY_MS        2000

static bool s_sensor_running;
static fusion_config_t s_fusion_cfg;
static fusion_t s_fusion;
static bool s_fusion_ready;
static level_t s_level;
static bool s_acal_valid;
static accel_cal_t s_acal;
static app_cal_status_t s_cal;
static bool s_collecting;
static level_mount_collector_t s_mount;
static accel_cal_collector_t s_collector;
// sensor_task() locals
static bool s_imu_ok;
static uint32_t s_last_retry, s_last_pub, s_last_data;
static imu_sample_t s_buf[FIFO_BATCH];
static vib_detector_t s_vib;
static turn_detector_t s_turn;
static imu_profile_t s_profile_req;   // sensor_request_imu_profile(), next pass
static int s_profile_lag;             // sim_fault_profile_lag()
static bool s_vib_running;            // what the recorder was told (vib_note())

// The recorder's hooks in the sensor task (recorder.c, below).
static void recorder_imu_block(const imu_raw_info_t *raw);
static void recorder_imu_state(bool ok);
static void recorder_vib(bool running);

// sensor_request_imu_profile(): taken at the next pass.
static void sensor_request_imu_profile(imu_profile_t p)
{
    s_profile_req = p;
}

void sim_fault_profile_lag(int passes)
{
    s_profile_lag = passes < 0 ? 0 : passes;
}

static void publish_cal_locked(void)
{
    app_state_set_cal(&s_cal);
}

static void accel_pose_kept_locked(void)
{
    vec3_t mean = s_collector.poses[s_collector.n - 1];
    s_cal.samples = s_collector.n;
    s_cal.accel_coverage = accel_cal_coverage(&s_collector);
    LOGI("accel pose %u kept (%.3f %.3f %.3f g), coverage 0x%02x", (unsigned)s_collector.n,
         (double)mean.x, (double)mean.y, (double)mean.z, (unsigned)s_cal.accel_coverage);
    publish_cal_locked();
}

static bool process_batch_locked(const imu_sample_t *smp, size_t n, float dt)
{
    bool turning = false;
    bool acal = s_collecting && s_cal.kind == APP_CAL_ACCEL;
    for (size_t i = 0; i < n; i++) {
        vec3_t raw = smp[i].accel_g;
        vec3_t a = s_acal_valid ? accel_cal_apply(&s_acal, raw) : raw;
        if (!s_fusion_ready) {
            fusion_init(&s_fusion, &s_fusion_cfg, a);
            s_fusion_ready = true;
            level_reset_heading(&s_level);
            turn_reset(&s_turn);
            continue;
        }
        float rate = fusion_update(&s_fusion, smp[i].gyro_rad_s, a, dt);
        turning |= turn_add_sample(&s_turn, vec3_sub(smp[i].gyro_rad_s, s_fusion.gyro_bias), dt);
        if (acal && accel_cal_collector_add_sample(&s_collector, raw, rate, dt)) {
            accel_pose_kept_locked();
        }
    }
    return turning;
}

static void mount_add_locked(uint32_t now)
{
    if (!s_collecting || s_cal.kind != APP_CAL_MOUNT ||
        !level_mount_collector_add(&s_mount, fusion_up_body(&s_fusion), now)) {
        return;
    }
    s_cal.samples = (uint16_t)s_mount.fit.n;
    if (s_mount.fit.n % 10u == 0) {
        publish_cal_locked();
    }
}

// The recorder hears the motor start and stop (VIB_START / VIB_STOP): the
// detector's RUNNING against everything else. A reset (vib_restart(), its
// state UNKNOWN) of a running detector is a stop, and the QUIET it decides
// after a reset is none, so every VIB_START of a take has its VIB_STOP.
static void vib_note(bool running)
{
    if (running != s_vib_running) {
        s_vib_running = running;
        recorder_vib(running);
    }
}

// vib_reset() where the sensor task restarts what integrates the samples
// (a profile switch, the IMU lost).
static void vib_restart(void)
{
    vib_reset(&s_vib);
    vib_note(false);
}

static void publish_no_imu(uint32_t now)
{
    level_reading_t r = {.mode = s_level.mode, .status = LEVEL_STATUS_OFF,
                         .yaw_status = LEVEL_STATUS_OFF};
    app_state_set_motion(false, quat_identity(), false, false, s_level.zero_set, &r, now);
}

static void sensor_start(const settings_t *s, bool imu_ok)
{
    s_fusion_cfg = fusion_default_config();
    s_fusion_ready = false;
    vib_config_t vib_cfg = vib_default_config();
    vib_init(&s_vib, &vib_cfg);
    s_vib_running = false;
    turn_config_t turn_cfg = turn_default_config();
    turn_init(&s_turn, &turn_cfg);
    level_config_t cfg = settings_level_config(s);
    level_init(&s_level, &cfg);
    level_set_mode(&s_level, s->mode);
    s_acal_valid = s->accel_cal_valid;
    s_acal = s->accel_cal;
    s_cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_result = APP_CAL_RESULT_NONE,
                               .last_kind = APP_CAL_IDLE};
    s_collecting = false;
    publish_cal_locked();

    // A boot starts the chip in LEVEL (imu_init() before the task), and a
    // fault injected before it is gone (as the busy-command fault).
    s_chip.profile = s_profile_req = IMU_PROFILE_LEVEL;
    s_profile_lag = 0;
    s_epoch = 0;
    configure(app_now_ms(), false);
    s_imu_ok = imu_ok;
    s_last_retry = app_now_ms();
    s_last_pub = 0;
    s_last_data = s_last_retry;
    s_sensor_running = true;
}

void sim_sensor_pass(uint32_t now_ms, const float *samples, int n, float dt, int imu_present)
{
    (void)dt;   // the driver measures the period (imu_sample_period_s())
    if (!running(now_ms) || !s_sensor_running) {
        return;
    }
    uint32_t now = now_ms;
    n = n < 0 ? 0 : n > SIM_MAX_PASS_SAMPLES ? SIM_MAX_PASS_SAMPLES : n;
    if (imu_present) {
        chip_feed(samples, n);   // the chip runs whatever the task does
    }

    if (!s_imu_ok) {
        if (now - s_last_retry >= IMU_RETRY_MS) {
            s_last_retry = now;
            if (imu_present) {
                // imu_init(): the profile the driver had (and any request since).
                s_chip.profile = s_profile_req;
                configure(now, true);
                LOGI("IMU running");
                s_imu_ok = true;
                s_last_data = now;
                recorder_imu_state(true);
                return;
            }
            LOGW("IMU init failed: ESP_ERR_TIMEOUT");
        }
        if (now - s_last_pub >= NO_IMU_PUBLISH_MS) {
            s_last_pub = now;
            publish_no_imu(now);
        }
        return;
    }

    if (s_chip.busy) {
        // Blocked in configure(); the next pass reads again.
        if ((int32_t)(now - s_chip.busy_until) >= 0) {
            s_chip.busy = false;
            s_last_data = now;
        }
        return;
    }
    if (s_profile_req != s_chip.profile && s_profile_lag > 0) {
        s_profile_lag--;        // a pass that was past its check reads first
    } else if (s_profile_req != s_chip.profile) {
        // imu_set_profile(): the new profile restarts what integrates the
        // samples, as the lost-IMU path does.
        s_chip.profile = s_profile_req;
        configure(now, true);
        LOGI("profile %s, %.1f Hz", profile()->name, (double)odr_hz());
        s_fusion_ready = false;
        vib_restart();
        s_last_data = now;
        return;
    }

    size_t got = 0;
    imu_raw_info_t raw = {0};
    if (imu_present) {
        drv_read_fifo(s_buf, FIFO_BATCH, &got, &raw, (int64_t)now * 1000);
    }
    if (got > 0) {
        s_last_data = now;
    } else if ((uint32_t)(now - s_last_data) >= IMU_LOST_MS) {
        LOGE("IMU lost: no new samples");
        s_imu_ok = false;
        s_last_retry = now;
        s_fusion_ready = false;
        vib_restart();
        s_last_pub = now;
        publish_no_imu(now);
        recorder_imu_state(false);
        return;
    }
    recorder_imu_block(&raw);   // the recorder's tap, right after the read
    if (got == 0) {
        return;
    }

    float dt_s = drv_sample_period_s();
    level_reading_t reading = {0};
    quat_t q = quat_identity();
    bool still = false;
    bool zero_set = false;

    bool turning = process_batch_locked(s_buf, got, dt_s);
    bool ready = s_fusion_ready;
    if (ready) {
        q = s_fusion.q;
        still = fusion_is_still(&s_fusion);
        reading = level_compute(&s_level, q, now);
        zero_set = s_level.zero_set;
        mount_add_locked(now);
    }

    vib_state_t was = vib_state(&s_vib);
    for (size_t i = 0; i < got; i++) {
        // The ESP_LOGD duty and turn rate line (and its s_win_max_rate) is left out.
        vib_add_sample(&s_vib, s_buf[i].accel_g, dt_s);
    }
    vib_state_t vib = vib_state(&s_vib);
    if (vib != was) {
        LOGI("vibration %s, duty %d %%", vib == VIB_RUNNING ? "running" : "quiet",
             (int)(s_vib.last_duty * 100.0f + 0.5f));
        vib_note(vib == VIB_RUNNING);
    }
    if (turning) {
        app_note_activity();
    }
    if (ready) {
        app_state_set_motion(true, q, still, vib == VIB_RUNNING, zero_set, &reading, now);
    }
}

static void sensor_stop(void)
{
    s_sensor_running = false;
}

static bool same_accel_cal(bool valid_a, const accel_cal_t *a, bool valid_b, const accel_cal_t *b)
{
    return valid_a == valid_b && (!valid_a || accel_cal_equal(a, b));
}

static void sensor_apply_settings(const settings_t *s)
{
    level_config_t cfg = settings_level_config(s);
    if (level_set_config(&s_level, &cfg)) {
        LOGI("drill axis changed, zero cleared");
    }
    if (s_level.zero_set &&
        !same_accel_cal(s_acal_valid, &s_acal, s->accel_cal_valid, &s->accel_cal)) {
        LOGI("sensor calibration changed, zero cleared");
        level_clear_zero(&s_level);
    }
    level_mode_t from = s_level.mode;
    if (level_set_mode(&s_level, s->mode)) {
        LOGI("mode %d -> %d, zero cleared", (int)from, (int)s->mode);
    }
    s_acal_valid = s->accel_cal_valid;
    s_acal = s->accel_cal;
}

static bool sensor_zero(void)
{
    bool ok = s_fusion_ready;
    if (ok) {
        level_zero(&s_level, s_fusion.q, app_now_ms());
    }
    return ok;
}

static void sensor_clear_zero(void)
{
    level_clear_zero(&s_level);
}

static void sensor_cal_start(app_cal_kind_t kind)
{
    if (kind != APP_CAL_MOUNT && kind != APP_CAL_ACCEL) {
        return;
    }
    level_mount_collector_reset(&s_mount, app_now_ms());
    accel_cal_collector_reset(&s_collector);
    s_cal.kind = kind;
    s_cal.samples = 0;
    s_cal.accel_coverage = 0;
    s_collecting = true;
    publish_cal_locked();
}

static void sensor_cal_cancel(void)
{
    if (s_cal.kind != APP_CAL_IDLE) {
        s_cal.last_kind = s_cal.kind;
        s_cal.last_result = APP_CAL_RESULT_NONE;
        s_cal.kind = APP_CAL_IDLE;
        s_collecting = false;
        publish_cal_locked();
    }
}

static bool sensor_mount_cal_finish(level_cal_result_t *result, vec3_t *drill_axis)
{
    bool ok = s_collecting && s_cal.kind == APP_CAL_MOUNT;
    level_mount_cal_t data = s_mount.fit;
    if (ok) {
        s_collecting = false;
        publish_cal_locked();
        *result = level_mount_cal_solve(&data, SETTINGS_MAX_MOUNT_OFFSET_DEG, drill_axis);
    }
    return ok;
}

static bool sensor_accel_cal_finish(accel_cal_result_t *result, accel_cal_t *cal)
{
    bool ok = s_collecting && s_cal.kind == APP_CAL_ACCEL;
    accel_cal_collector_t data = s_collector;
    if (ok) {
        s_collecting = false;
        *result = accel_cal_solve(&data, cal);
    }
    return ok;
}

static void sensor_cal_done(app_cal_kind_t kind, int result)
{
    if (s_cal.kind == kind && !s_collecting) {
        s_cal.kind = APP_CAL_IDLE;
        s_cal.last_kind = kind;
        s_cal.last_result = (int8_t)result;
        publish_cal_locked();
    }
}

// ---- ble_service.c (the parts that are not NimBLE) -------------------------------

#define DIS_MANUFACTURER "DrillBalancer"
#define DIS_MODEL        "DrillBalancer (ESP32-S3-Touch-AMOLED-1.75)"
#define STATUS_HEARTBEAT_MS 1000u
#define WRITE_BUF_LEN 512u
#define ATT_ERR_UNLIKELY 0x0Eu   // BLE_ATT_ERR_UNLIKELY

static bool s_ble_enabled;
static bool s_connected;
static uint8_t s_subs;
static bool s_link_trusted;
static bool s_pairing;
static uint32_t s_link_since_ms;
static uint32_t s_last_write_ms;
static bool s_ota_ok;
// notify timer
static bool s_tick_reset;
static bool s_status_resend;
static uint8_t s_angle_seq;
static bool s_status_sent;
static db_status_packet_t s_status_last;
static uint32_t s_status_last_ms;
static int s_bat_last;
static bool s_fast_params;
static bool s_holds_awake;
static bool s_kick_posted;
// config_read(): JSON captured at offset 0 for the read blobs of a long read
static char s_cfg_json[SETTINGS_JSON_MAX];
static int s_cfg_len = -1;   // -1: rebuild on the next read

static void ota_on_disconnect(void);
static void ota_set_mtu(uint16_t mtu);
// recorder.c (below): the link it survives, and rec_state's subscription.
static void recorder_ble(bool up);
static void recorder_resend(void);
static bool recorder_session_open(void);

const char *sim_dis_string(int which)
{
    return which == 0 ? DIS_MANUFACTURER : DIS_MODEL;
}

int sim_ble_enabled(void)
{
    return s_ble_enabled;
}

static void ble_service_init(bool enabled)
{
    // A boot zeroes the statics of ble_service.c.
    s_tick_reset = s_status_resend = s_status_sent = false;
    s_angle_seq = 0;
    s_status_last = (db_status_packet_t){0};
    s_status_last_ms = 0;
    s_bat_last = 0;
    s_fast_params = s_holds_awake = s_kick_posted = false;
    s_link_since_ms = s_last_write_ms = 0;
    s_ble_enabled = enabled;
    s_connected = false;
    s_subs = 0;
    s_link_trusted = s_pairing = false;
    s_cfg_len = -1;
    s_ota_ok = true;   // the partition table has ota_0/ota_1
    app_state_set_ble(enabled, false);
    LOGI("init, %s", enabled ? "enabled" : "disabled");
}

static void ble_service_set_enabled(bool enabled)
{
    if (enabled == s_ble_enabled) {
        return;
    }
    s_ble_enabled = enabled;
    s_events |= SIM_EV_BLE_ENABLED;   // the BLE layer stops/starts advertising, drops the link
    app_state_set_ble(s_ble_enabled, s_connected);
}

static void link_reset(bool connected)
{
    s_connected = connected;
    s_subs = 0;
    app_state_set_passkey(false, 0);
    s_cfg_len = -1;
    s_link_trusted = false;
    s_pairing = false;
    app_state_set_ble_holds_awake(false);
}

static void ble_service_shutdown(void)
{
    if (s_connected) {
        LOGI("disconnected, reason 0x16");
        if (s_ota_ok) {
            ota_on_disconnect();
        }
    }
    link_reset(false);
    app_state_set_ble(false, false);
    LOGI("stopped");
}

int sim_link_connect(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return 1;
    }
    link_reset(true);
    s_link_since_ms = app_now_ms();
    s_last_write_ms = s_link_since_ms - BLE_CODEC_AWAKE_AFTER_WRITE_MS;
    LOGI("connected");
    if (!s_ble_enabled) {
        return 1;
    }
    app_state_set_ble(true, true);
    app_note_activity();
    ota_set_mtu(23);   // until the MTU exchange
    s_tick_reset = true;
    return 0;
}

void sim_link_disconnect(uint32_t now_ms)
{
    if (!running(now_ms) || !s_connected) {
        return;
    }
    LOGI("disconnected");
    link_reset(false);
    if (s_ota_ok) {
        ota_on_disconnect();
    }
    app_state_set_ble(s_ble_enabled, false);
    if (!app_post_cmd(APP_CMD_CAL_CANCEL, APP_CMD_ARG_FROM_BLE)) {
        LOGW("command queue full, BLE calibration not cancelled");
    }
    recorder_ble(false);   // a take goes on for REC_BLE_GRACE_MS
}

void sim_link_passkey(uint32_t passkey, uint32_t now_ms)
{
    if (!running(now_ms)) {
        return;
    }
    app_state_set_passkey(true, passkey);
    s_pairing = true;
    LOGI("pairing: showing passkey");
}

void sim_link_enc_change(int status, int trusted, uint32_t now_ms)
{
    if (!running(now_ms)) {
        return;
    }
    app_state_set_passkey(false, 0);
    s_pairing = false;
    LOGI("encryption %s (%d)", status == 0 ? "on" : "failed", status);
    if (status != 0) {
        s_link_since_ms = app_now_ms();
        return;
    }
    if (trusted) {
        s_link_trusted = true;
        s_last_write_ms = app_now_ms();
        app_note_activity();
        recorder_ble(true);   // the app (an authenticated link) is back
        return;
    }
    s_link_since_ms = app_now_ms();
}

void sim_link_subscribe(int sub_bit, int on)
{
    uint8_t bit = (uint8_t)sub_bit;
    s_subs = on ? (uint8_t)(s_subs | bit) : (uint8_t)(s_subs & ~bit);
    if (bit == SIM_SUB_STATUS || bit == SIM_SUB_BAT) {
        s_status_resend = true;
    }
    if (bit == SIM_SUB_REC) {
        recorder_resend();
    }
}

int sim_link_trusted(void)
{
    return s_link_trusted;
}

uint32_t sim_notify_tick(uint32_t now_ms, uint8_t *angle_out, uint8_t *status_out,
                         uint8_t *bat_out)
{
    if (!running(now_ms)) {
        return 0;
    }
    if (s_tick_reset) {
        s_tick_reset = false;
        s_fast_params = false;
        s_status_resend = true;
        s_holds_awake = false;
        s_kick_posted = false;
    }
    if (s_status_resend) {
        s_status_resend = false;
        s_status_sent = false;
        s_bat_last = -1;
    }
    if (!s_connected) {
        return 0;
    }

    uint32_t tx = 0;
    app_snapshot_t snap;
    app_state_get(&snap);
    uint32_t now = app_now_ms();

    // link_policy()
    bool holds = ble_codec_link_holds_awake(s_link_trusted, s_last_write_ms, now);
    if (holds != s_holds_awake) {
        s_holds_awake = holds;
        app_state_set_ble_holds_awake(holds);
    }
    if (!s_kick_posted &&
        ble_codec_link_idle_unauth(s_link_trusted, s_pairing, s_subs != 0, s_link_since_ms, now)) {
        s_kick_posted = true;
        LOGW("dropping idle unauthenticated link");
        tx |= SIM_TX_KICK;
    }

    if ((s_subs & SIM_SUB_ANGLE) && !snap.ota_active) {
        db_angle_packet_t a;
        ble_codec_angle(&snap, s_angle_seq, now, &a);
        memcpy(angle_out, &a, sizeof(a));
        s_angle_seq++;
        tx |= SIM_TX_ANGLE;
    }

    if (s_subs & SIM_SUB_STATUS) {
        db_status_packet_t st;
        ble_codec_status(&snap, &st);
        if (!s_status_sent || ble_codec_status_changed(&s_status_last, &st) ||
            now - s_status_last_ms >= STATUS_HEARTBEAT_MS) {
            memcpy(status_out, &st, sizeof(st));
            s_status_last = st;
            s_status_last_ms = now;
            s_status_sent = true;
            tx |= SIM_TX_STATUS;
        }
    }

    uint8_t pct = ble_codec_battery_pct(&snap.battery);
    if ((s_subs & SIM_SUB_BAT) && snap.battery.valid && (int)pct != s_bat_last) {
        *bat_out = pct;
        s_bat_last = pct;
        tx |= SIM_TX_BATTERY;
    }

    if (snap.ota_active != s_fast_params) {
        s_fast_params = snap.ota_active;
        tx |= s_fast_params ? SIM_TX_CONN_FAST : SIM_TX_CONN_SLOW;
    }
    return tx;
}

// gatt_access() for writes: every write is activity, OTA chunks included.
static bool gatt_write_begin(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return false;
    }
    app_note_activity();
    s_last_write_ms = app_now_ms();
    return true;
}

static bool flatten_ok(int len)
{
    return len > 0 && (unsigned)len <= WRITE_BUF_LEN;
}

int sim_config_read(int offset, char *buf, int cap)
{
    // Captured at offset 0 and reused for the read blobs that follow, so a
    // long read sees one consistent JSON document.
    if (offset == 0 || s_cfg_len < 0) {
        settings_t s;
        settings_get(&s);
        s_cfg_len = settings_to_json(&s, s_cfg_json, sizeof(s_cfg_json));
        if (s_cfg_len < 0) {
            LOGE("settings JSON does not fit");
            return -1;
        }
    }
    if (cap < s_cfg_len) {
        return -1;
    }
    memcpy(buf, s_cfg_json, (size_t)s_cfg_len);
    return s_cfg_len;
}

int sim_settings_json(char *buf, int cap)
{
    if (cap <= 0) {
        return -1;
    }
    settings_t s;
    settings_get(&s);
    return settings_to_json(&s, buf, (size_t)cap);
}

typedef struct {
    const char *json;
    size_t len;
    settings_json_result_t result;
} json_write_t;

static bool apply_json(settings_t *s, void *ctx)
{
    json_write_t *w = ctx;
    w->result = settings_apply_json(s, w->json, w->len);
    return w->result == SETTINGS_JSON_OK;
}

int sim_config_write(const uint8_t *data, int len, uint32_t now_ms)
{
    if (!gatt_write_begin(now_ms)) {
        return ATT_ERR_UNLIKELY;
    }
    if (!flatten_ok(len)) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    json_write_t w = {.json = (const char *)data, .len = (size_t)len, .result = SETTINGS_JSON_OK};
    int err = settings_update(apply_json, &w);
    uint8_t att = ble_codec_settings_att_err(w.result);
    if (att != 0) {
        LOGW("config write rejected: 0x%02x", att);
        return att;
    }
    s_cfg_len = -1;
    if (err == ERR_INVALID_ARG) {
        return DB_ATT_ERR_JSON_RANGE;
    }
    if (err != 0) {
        LOGE("settings_update: ESP_FAIL");
        return DB_ATT_ERR_STORE;
    }
    return 0;
}

int sim_ui_settings_edit(const char *json, int len, uint32_t now_ms)
{
    if (!running(now_ms) || len <= 0) {
        return -1;
    }
    app_note_activity();   // a touch on the settings screen
    json_write_t w = {.json = json, .len = (size_t)len, .result = SETTINGS_JSON_OK};
    int err = settings_update(apply_json, &w);
    if (w.result != SETTINGS_JSON_OK) {
        return w.result == SETTINGS_JSON_RANGE_ERROR ? ERR_INVALID_ARG : -1;
    }
    if (err != 0) {
        LOGW("settings_update failed: %s", err == ERR_INVALID_ARG ? "ESP_ERR_INVALID_ARG" : "ESP_FAIL");
    }
    return err < 0 ? -2 : err;
}

int sim_command_write(const uint8_t *data, int len, uint32_t now_ms)
{
    if (!gatt_write_begin(now_ms)) {
        return ATT_ERR_UNLIKELY;
    }
    if (!flatten_ok(len)) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    app_cmd_id_t id;
    int32_t arg;
    uint8_t att = ble_codec_parse_cmd(data, (size_t)len, &id, &arg);
    if (att != 0) {
        LOGW("command 0x%02x rejected: 0x%02x", data[0], att);
        return att;
    }
    if (s_busy_next > 0) {   // injected: as if the queue were full
        s_busy_next--;
        LOGW("command 0x%02x: queue full (injected)", data[0]);
        return DB_ATT_ERR_BUSY;
    }
    if (!app_post_cmd(id, arg)) {
        return DB_ATT_ERR_BUSY;
    }
    LOGI("command 0x%02x", data[0]);
    return 0;
}

void sim_angle_packet(uint8_t seq, uint32_t now_ms, uint8_t *out14)
{
    db_angle_packet_t a;
    ble_codec_angle(&s_state, seq, now_ms, &a);
    memcpy(out14, &a, sizeof(a));
}

void sim_status_packet(uint8_t *out10)
{
    db_status_packet_t p;
    ble_codec_status(&s_state, &p);
    memcpy(out10, &p, sizeof(p));
}

int sim_battery_pct(void)
{
    return ble_codec_battery_pct(&s_state.battery);
}

// ---- ota_update.c: a file instead of the OTA partition ---------------------------

#define DEFAULT_MTU 23u
#define ACK_EVERY 16u
#define TIMEOUT_MS 10000u
#define OTA_PARTITION_SIZE (6u * 1024u * 1024u)   // partitions.csv: ota_0/ota_1 6M

// esp_image_header_t (24) + esp_image_segment_header_t (8), then esp_app_desc_t
// (256): magic_word, secure_version, reserv1[2], version[32], project_name[32], ...
#define DESC_OFFSET 32u
#define DESC_LEN 256u
#define HEAD_LEN (DESC_OFFSET + DESC_LEN)
#define APP_DESC_MAGIC_WORD 0xABCD5432u
#define DESC_VERSION_OFFSET 16u
#define DESC_PROJECT_OFFSET 48u
#define PROJECT_NAME "drillbalancer"   // esp_app_get_description()->project_name

#define REPLY_RING 32u

typedef struct {
    FILE *f;
    bool open;
    uint8_t head[HEAD_LEN];
    size_t head_len;
    bool desc_ok;
    char version[VERSION_LEN + 1];
} sink_ctx_t;

typedef struct {
    mbedtls_sha256_context ctx;
    bool live;
} hash_ctx_t;

static ota_proto_t s_proto;
static sink_ctx_t s_sink;
static hash_ctx_t s_hash;
static bool s_pub_active;
static uint8_t s_pub_percent;
static struct {
    uint8_t len;
    uint8_t data[8];
} s_replies[REPLY_RING];
static unsigned s_reply_head, s_reply_count;

static void part_path(char *buf, size_t len)
{
    snprintf(buf, len, "%s.part", s_ota_path);
}

static void sink_abort(void *ctx)
{
    sink_ctx_t *s = ctx;
    if (s->open) {
        fclose(s->f);
        s->open = false;
        char p[PATH_MAX_LEN + 8];
        part_path(p, sizeof(p));
        remove(p);
    }
}

static int sink_begin(void *ctx, uint32_t image_size)
{
    sink_ctx_t *s = ctx;
    sink_abort(s);
    if (recorder_session_open()) {
        // OTA, calibration and a recording session exclude each other.
        LOGE("esp_ota_begin: refused, a recording session is open");
        return -1;
    }
    s->head_len = 0;
    s->desc_ok = false;
    char p[PATH_MAX_LEN + 8];
    part_path(p, sizeof(p));
    s->f = fopen(p, "wb");
    if (s->f == NULL) {
        LOGE("esp_ota_begin: cannot open %s", p);
        return -1;
    }
    s->open = true;
    LOGI("receiving %lu bytes into %s", (unsigned long)image_size, p);
    return 0;
}

static bool desc_acceptable(sink_ctx_t *s)
{
    const uint8_t *d = s->head + DESC_OFFSET;
    uint32_t magic = (uint32_t)d[0] | (uint32_t)d[1] << 8 | (uint32_t)d[2] << 16 |
                     (uint32_t)d[3] << 24;
    if (magic != APP_DESC_MAGIC_WORD) {
        LOGE("image has no app descriptor");
        return false;
    }
    char project[33] = {0};
    memcpy(project, d + DESC_PROJECT_OFFSET, 32);
    if (strncmp(project, PROJECT_NAME, 32) != 0) {
        LOGE("image is for project '%s', not '%s'", project, PROJECT_NAME);
        return false;
    }
    memset(s->version, 0, sizeof(s->version));
    memcpy(s->version, d + DESC_VERSION_OFFSET, VERSION_LEN);
    LOGI("new image version '%s'", s->version);
    return true;
}

static int sink_write(void *ctx, const uint8_t *data, size_t len)
{
    sink_ctx_t *s = ctx;
    if (!s->open) {
        return -1;
    }
    if (!s->desc_ok) {
        size_t n = HEAD_LEN - s->head_len;
        if (n > len) {
            n = len;
        }
        memcpy(s->head + s->head_len, data, n);
        s->head_len += n;
        if (s->head_len == HEAD_LEN) {
            if (!desc_acceptable(s)) {
                return -1;
            }
            s->desc_ok = true;
        }
    }
    if ((s_faults & SIM_FAULT_FLASH) || fwrite(data, 1, len, s->f) != len) {
        LOGE("esp_ota_write: ESP_FAIL");
        return -1;
    }
    return 0;
}

// What esp_ota_end() checks through esp_image_verify(): the header, the
// segment layout, the XOR checksum and the appended SHA-256.
#define IMAGE_MAGIC 0xE9u
#define IMAGE_HEADER_LEN 24u
#define IMAGE_CHIP_ESP32S3 9u
#define IMAGE_MAX_SEGMENTS 16u
#define IMAGE_CHECKSUM_SEED 0xEFu

static bool image_valid(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        return false;
    }
    bool ok = false;
    uint8_t hdr[IMAGE_HEADER_LEN];
    mbedtls_sha256_context sha;
    mbedtls_sha256_init(&sha);
    (void)mbedtls_sha256_starts(&sha, 0);
    if (fread(hdr, 1, sizeof(hdr), f) != sizeof(hdr) || hdr[0] != IMAGE_MAGIC ||
        hdr[1] == 0 || hdr[1] > IMAGE_MAX_SEGMENTS ||
        (uint16_t)(hdr[12] | hdr[13] << 8) != IMAGE_CHIP_ESP32S3) {
        LOGE("esp_ota_end: not an ESP32-S3 app image");
        goto out;
    }
    (void)mbedtls_sha256_update(&sha, hdr, sizeof(hdr));
    uint8_t csum = IMAGE_CHECKSUM_SEED;
    long pos = IMAGE_HEADER_LEN;
    for (unsigned i = 0; i < hdr[1]; i++) {
        uint8_t seg[8];
        if (fread(seg, 1, sizeof(seg), f) != sizeof(seg)) {
            LOGE("esp_ota_end: image truncated in segment %u", i);
            goto out;
        }
        (void)mbedtls_sha256_update(&sha, seg, sizeof(seg));
        uint32_t len = (uint32_t)seg[4] | (uint32_t)seg[5] << 8 | (uint32_t)seg[6] << 16 |
                       (uint32_t)seg[7] << 24;
        if (len > OTA_PARTITION_SIZE) {
            LOGE("esp_ota_end: segment %u of %lu bytes", i, (unsigned long)len);
            goto out;
        }
        pos += 8 + (long)len;
        uint8_t buf[4096];
        while (len > 0) {
            size_t n = len < sizeof(buf) ? len : sizeof(buf);
            if (fread(buf, 1, n, f) != n) {
                LOGE("esp_ota_end: image truncated in segment %u", i);
                goto out;
            }
            (void)mbedtls_sha256_update(&sha, buf, n);
            for (size_t k = 0; k < n; k++) {
                csum ^= buf[k];
            }
            len -= (uint32_t)n;
        }
    }
    // Zero padding up to the checksum byte, which ends a 16-byte block.
    uint8_t tail[16];
    size_t pad = (size_t)(15 - pos % 16);
    if (fread(tail, 1, pad + 1, f) != pad + 1) {
        LOGE("esp_ota_end: image truncated before the checksum");
        goto out;
    }
    (void)mbedtls_sha256_update(&sha, tail, pad + 1);
    if (tail[pad] != csum) {
        LOGE("esp_ota_end: checksum 0x%02x, expected 0x%02x", tail[pad], csum);
        goto out;
    }
    if (hdr[23]) {   // hash_appended
        uint8_t want[32], got[32];
        (void)mbedtls_sha256_finish(&sha, got);
        if (fread(want, 1, sizeof(want), f) != sizeof(want) || memcmp(want, got, 32) != 0) {
            LOGE("esp_ota_end: image SHA-256 mismatch");
            goto out;
        }
    }
    ok = true;
out:
    mbedtls_sha256_free(&sha);
    fclose(f);
    return ok;
}

static int sink_finish(void *ctx)
{
    sink_ctx_t *s = ctx;
    if (!s->open) {
        return -1;
    }
    if (!s->desc_ok) {
        LOGE("image too short for an app descriptor");
        return -1;
    }
    s->open = false;
    char p[PATH_MAX_LEN + 8];
    part_path(p, sizeof(p));
    if (fclose(s->f) != 0 || !image_valid(p) || rename(p, s_ota_path) != 0) {
        LOGE("esp_ota_end: ESP_FAIL");
        remove(p);
        return -1;
    }
    // esp_ota_set_boot_partition(): the next boot runs the new image.
    copy_str(s_fw_pending, sizeof(s_fw_pending), s->version);
    if (state_save(&s_current) != 0) {
        return -1;
    }
    LOGI("update written to %s, reboot pending", s_ota_path);
    return 0;
}

static const ota_sink_t SINK = {sink_begin, sink_write, sink_finish, sink_abort};

static void hash_start(void *ctx)
{
    hash_ctx_t *h = ctx;
    if (h->live) {
        mbedtls_sha256_free(&h->ctx);
    }
    mbedtls_sha256_init(&h->ctx);
    (void)mbedtls_sha256_starts(&h->ctx, 0);
    h->live = true;
}

static void hash_update(void *ctx, const uint8_t *data, size_t len)
{
    hash_ctx_t *h = ctx;
    (void)mbedtls_sha256_update(&h->ctx, data, len);
}

static void hash_finish(void *ctx, uint8_t out[32])
{
    hash_ctx_t *h = ctx;
    if (mbedtls_sha256_finish(&h->ctx, out) != 0) {
        memset(out, 0, 32);
    }
    mbedtls_sha256_free(&h->ctx);
    h->live = false;
}

static const ota_hash_t HASH = {hash_start, hash_update, hash_finish};

// ota_reply_fn: queued here, the BLE layer sends them as indications (one
// in flight, its own 8-entry queue as in ble_service.c).
static void ota_reply(void *ctx, const uint8_t *msg, size_t len)
{
    (void)ctx;
    if (len == 0 || len > 8 || s_reply_count == REPLY_RING) {
        LOGE("ota reply of %u bytes dropped", (unsigned)len);
        return;
    }
    unsigned i = (s_reply_head + s_reply_count) % REPLY_RING;
    s_replies[i].len = (uint8_t)len;
    memcpy(s_replies[i].data, msg, len);
    s_reply_count++;
}

int sim_ota_reply_pop(uint8_t *buf)
{
    if (s_reply_count == 0) {
        return 0;
    }
    int len = s_replies[s_reply_head].len;
    memcpy(buf, s_replies[s_reply_head].data, (size_t)len);
    s_reply_head = (s_reply_head + 1u) % REPLY_RING;
    s_reply_count--;
    return len;
}

static uint16_t chunk_for_mtu(uint16_t mtu)
{
    return (uint16_t)((mtu < DEFAULT_MTU ? DEFAULT_MTU : mtu) - OTA_DATA_OVERHEAD);
}

static void sync_outputs(void)
{
    bool active = ota_proto_state(&s_proto) == OTA_STATE_RECEIVING;
    uint8_t percent = ota_proto_percent(&s_proto);
    if (active != s_pub_active || percent != s_pub_percent) {
        s_pub_active = active;
        s_pub_percent = percent;
        app_state_set_ota(active, percent);
    }
}

static void ota_update_init(void)
{
    if (s_sink.open) {
        sink_abort(&s_sink);
    }
    if (s_hash.live) {
        mbedtls_sha256_free(&s_hash.ctx);
        s_hash.live = false;
    }
    s_reply_head = s_reply_count = 0;
    s_pub_active = false;
    s_pub_percent = 0;
    ota_proto_config_t cfg = {
        .max_image_size = OTA_PARTITION_SIZE,
        .max_chunk = chunk_for_mtu(DEFAULT_MTU),
        .ack_every = ACK_EVERY,
        .timeout_ms = TIMEOUT_MS,
    };
    ota_proto_init(&s_proto, &cfg, &SINK, &s_sink, &HASH, &s_hash, ota_reply, NULL);
}

static void ota_set_mtu(uint16_t mtu)
{
    ota_proto_set_max_chunk(&s_proto, chunk_for_mtu(mtu));
}

void sim_ota_set_mtu(int mtu)
{
    ota_set_mtu((uint16_t)(mtu < 0 ? 0 : mtu > 0xFFFF ? 0xFFFF : mtu));
}

static void ota_on_disconnect(void)
{
    if (ota_proto_state(&s_proto) == OTA_STATE_RECEIVING) {
        LOGW("disconnected during transfer, aborted");
    }
    ota_proto_on_disconnect(&s_proto);
    sync_outputs();
}

void sim_ota_tick(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return;
    }
    bool was_active = ota_proto_state(&s_proto) == OTA_STATE_RECEIVING;
    ota_proto_tick(&s_proto, app_now_ms());
    if (was_active && ota_proto_state(&s_proto) != OTA_STATE_RECEIVING) {
        LOGW("transfer timed out");
    }
    sync_outputs();
}

static int ota_write(bool ctrl, const uint8_t *data, int len, uint32_t now_ms)
{
    if (!gatt_write_begin(now_ms)) {
        return ATT_ERR_UNLIKELY;
    }
    if (!s_ota_ok) {
        return DB_ATT_ERR_UNAVAILABLE;
    }
    if (!flatten_ok(len)) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    if (ctrl) {
        ota_proto_on_ctrl(&s_proto, data, (size_t)len, app_now_ms());
    } else {
        ota_proto_on_data(&s_proto, data, (size_t)len, app_now_ms());
    }
    sync_outputs();
    return 0;
}

int sim_ota_ctrl_write(const uint8_t *data, int len, uint32_t now_ms)
{
    return ota_write(true, data, len, now_ms);
}

int sim_ota_data_write(const uint8_t *data, int len, uint32_t now_ms)
{
    return ota_write(false, data, len, now_ms);
}

int sim_ota_reboot_pending(void)
{
    return ota_proto_state(&s_proto) == OTA_STATE_DONE;
}

// ---- recorder.c, rec_wifi.c, rec_audio.c -----------------------------------------
// The ESP glue around the pure recorder (rec_fsm, rec_ring, rec_stream,
// rec_ts) as the firmware design has it (milestone 4): the FSM's actions run
// in bit order, every producer pushes whole records into the ring, the
// sender drains it. The soft-AP and its TCP server are the Python side's
// endpoint (sim_rec_net()), the I2S capture is sim_audio_block().

#define REC_LEVEL_MS        100u    // LEVEL records, 10 Hz
#define REC_STATS_MS        1000u   // STATS records, 1 Hz
#define REC_HEARTBEAT_MS    1000u   // rec_state notification during a take
#define REC_AUDIO_BITS      16

typedef struct {
    rec_fsm_t fsm;
    rec_ring_t ring;
    uint8_t *buf;                   // allocated at OPEN, freed back in IDLE
    uint32_t ring_size;
    rec_ring_stats_t last;          // of the freed ring, for rec_state in IDLE
    // rec_wifi.c: the endpoint
    bool ap_wanted;
    bool client;
    bool no_ap;
    uint8_t ip[4];
    uint16_t port;
    uint8_t channel;
    char ssid[17];
    char pass[25];
    uint8_t token[REC_HELLO_TOKEN_LEN];
    // the take's sources
    bool tap;                       // IMU, LEVEL and STATS records flow
    bool imu_started;               // EVENT PROFILE sent for imu_epoch
    uint32_t imu_epoch;
    bool epoch_flag;                // the next IMU record follows a chip reset
    rec_ts_ext_t ts_ext;
    bool have_next;
    uint32_t next_ts;               // extended TIMESTAMP after the last run
    uint32_t imu_frames, imu_lost;
    rec_rate_t imu_rate, audio_rate;
    bool audio_on;
    uint8_t mic_mask, gain_db;
    uint64_t audio_index;
    uint32_t audio_frames, audio_overflows, audio_clipped;
    uint32_t last_level_ms, last_stats_ms;
    // rec_state notifications
    bool notify;
    bool sent_valid;
    db_rec_state_packet_t sent;
    uint32_t sent_ms;
} rec_glue_t;

static rec_glue_t s_rec = {.ring_size = SIM_REC_RING_DEFAULT};

static uint8_t s_board_id[4];
static char s_fw_git[REC_FW_LEN];

void sim_set_identity(const uint8_t *board_id4, const char *fw_git)
{
    if (board_id4) {
        memcpy(s_board_id, board_id4, sizeof(s_board_id));
    }
    copy_str(s_fw_git, sizeof(s_fw_git), fw_git);
}

void sim_seed(uint32_t seed)
{
    s_rng = seed ? seed : 0x2545F491u;
    sim_audio_reset(seed * 2654435761u + 1u);
}

void sim_set_mic_gain(int mic, float db)
{
    sim_audio_set_mic_gain(mic, db);
}

void sim_rec_set_ring_size(uint32_t bytes)
{
    s_rec.ring_size = bytes < 4096u ? 4096u : bytes;
}

void sim_rec_set_endpoint(int no_ap, const uint8_t *ip4, int port, int channel, const char *ssid,
                          const char *pass)
{
    s_rec.no_ap = no_ap != 0;
    if (ip4) {
        memcpy(s_rec.ip, ip4, sizeof(s_rec.ip));
    }
    s_rec.port = (uint16_t)(port < 0 ? 0 : port > 0xFFFF ? 0xFFFF : port);
    s_rec.channel = (uint8_t)(channel < 0 ? 0 : channel > 14 ? 14 : channel);
    copy_str(s_rec.ssid, sizeof(s_rec.ssid), no_ap ? "" : ssid);
    copy_str(s_rec.pass, sizeof(s_rec.pass), no_ap ? "" : pass);
}

int sim_rec_ap_wanted(void)
{
    return s_rec.ap_wanted;
}

int sim_rec_audio_on(void)
{
    return s_rec.audio_on;
}

static uint64_t host_us(void)
{
    return (uint64_t)app_now_ms() * 1000u;   // esp_timer_get_time()
}

static void ring_stats(rec_ring_stats_t *st)
{
    if (s_rec.buf) {
        rec_ring_stats(&s_rec.ring, st);
    } else {
        *st = s_rec.last;
    }
}

static bool rec_push(uint8_t type, uint8_t ver, const rec_part_t *parts, size_t nparts)
{
    return s_rec.buf && rec_ring_push(&s_rec.ring, type, ver, parts, nparts, NULL);
}

static void rec_event(uint16_t code, uint16_t arg, const char *text)
{
    uint8_t e[REC_EVENT_MAX];
    const rec_part_t p = {e, rec_event_payload(e, host_us(), code, arg, text)};
    rec_push(REC_T_EVENT, REC_V_EVENT, &p, 1);
}

// app_state_set_rec() (app_state.c gains it in milestone 4).
static void recorder_publish(void)
{
    const rec_fsm_t *f = &s_rec.fsm;
    const app_battery_t *b = &s_state.battery;
    rec_ring_stats_t st;
    ring_stats(&st);
    app_rec_status_t r = {
        .state = (uint8_t)f->state,
        .reason = f->reason,
        .flags = (uint8_t)((s_rec.client ? DB_REC_FLAG_CLIENT : 0u) |
                           (s_rec.audio_on ? DB_REC_FLAG_AUDIO : 0u) |
                           (f->imu_lost ? DB_REC_FLAG_IMU_LOST : 0u) |
                           (b->valid && !b->vbus_present && b->percent < REC_BATT_START_PCT
                                ? DB_REC_FLAG_BATTERY_LOW : 0u)),
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
    if (f->state != REC_STATE_IDLE) {
        r.flags |= s_rec.no_ap ? DB_REC_FLAG_NO_AP : 0u;
        r.channel = s_rec.no_ap ? 0 : s_rec.channel;
        r.port = s_rec.port;
        memcpy(r.ip, s_rec.ip, sizeof(r.ip));
        copy_str(r.ssid, sizeof(r.ssid), s_rec.ssid);
        copy_str(r.pass, sizeof(r.pass), s_rec.pass);
        memcpy(r.token, s_rec.token, sizeof(r.token));
    }
    s_state.rec = r;
}

// The SESSION record's JSON tail: settings and calibration in effect.
static int session_json(char *out, size_t cap)
{
    char sj[SETTINGS_JSON_MAX];
    if (settings_to_json(&s_current, sj, sizeof(sj)) < 0) {
        return 0;
    }
    cJSON *root = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "settings", cJSON_Parse(sj));
    cJSON *cal = cJSON_AddObjectToObject(root, "cal");
    cJSON *acc = cJSON_AddObjectToObject(cal, "accel");
    cJSON_AddBoolToObject(acc, "valid", s_current.accel_cal_valid);
    cJSON_AddItemToObject(acc, "bias_g", vec_json(s_current.accel_cal.bias));
    cJSON_AddItemToObject(acc, "scale", vec_json(s_current.accel_cal.scale));
    cJSON *mnt = cJSON_AddObjectToObject(cal, "mount");
    cJSON_AddBoolToObject(mnt, "valid", s_current.mount_cal_valid);
    cJSON_AddItemToObject(mnt, "drill_axis", vec_json(s_current.drill_axis));
    bool bias = s_fusion_ready && fusion_bias_valid(&s_fusion);
    cJSON_AddItemToObject(cal, "gyro_bias_rad_s",
                          bias ? vec_json(s_fusion.gyro_bias) : cJSON_CreateNull());
    char *text = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    size_t n = text ? strlen(text) : 0;
    if (n >= cap) {
        n = 0;
    }
    memcpy(out, text ? text : "", n);
    free(text);
    return (int)n;
}

static void act_emit_session(void)
{
    const rec_fsm_t *f = &s_rec.fsm;
    const imu_profile_info_t *p = imu_profile_info((imu_profile_t)f->profile);
    uint8_t ch = (uint8_t)(((f->mic_mask & REC_MIC_1) ? 1u : 0u) + ((f->mic_mask & REC_MIC_2) ? 1u : 0u));
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
        .axis = {{IMU_AXIS_MAP.x.src, IMU_AXIS_MAP.x.sign},
                 {IMU_AXIS_MAP.y.src, IMU_AXIS_MAP.y.sign},
                 {IMU_AXIS_MAP.z.src, IMU_AXIS_MAP.z.sign}},
        .audio_rate_hz = ch ? SIM_AUDIO_RATE : 0u,
        .audio_channels = ch,
        .audio_bits = REC_AUDIO_BITS,
        .mic_mask = f->mic_mask,
        .gain_db = f->gain_db,
        .audio_block_frames = SIM_AUDIO_BLOCK,
        .max_s = f->max_s,
    };
    memcpy(s.board_id, s_board_id, sizeof(s.board_id));
    rec_fw_text(s.fw, sim_fw_version(), s_fw_git);
    char tail[REC_MAX_PAYLOAD - sizeof(rec_session_t)];
    const rec_part_t parts[] = {{&s, sizeof(s)}, {tail, (size_t)session_json(tail, sizeof(tail))}};
    rec_ring_begin_take(&s_rec.ring);
    rec_push(REC_T_SESSION, REC_V_SESSION, parts, 2);

    // The take's counters and its sources start over.
    s_rec.imu_started = s_rec.epoch_flag = s_rec.have_next = false;
    s_rec.ts_ext = (rec_ts_ext_t){0};
    s_rec.imu_frames = s_rec.imu_lost = 0;
    s_rec.audio_frames = s_rec.audio_overflows = s_rec.audio_clipped = 0;
    rec_rate_reset(&s_rec.imu_rate);
    rec_rate_reset(&s_rec.audio_rate);
    s_rec.last_level_ms = s_rec.last_stats_ms = app_now_ms();
    LOGI("recorder: take %lu, %s, mics 0x%x, %u dB, max %u s", (unsigned long)f->take_id, p->name,
         (unsigned)f->mic_mask, (unsigned)f->gain_db, (unsigned)f->max_s);
}

// The take's profile at its start, LEVEL at its end: the IMU records start
// once it is in effect (recorder_imu_block), and stop at once at the end,
// with LEVEL and STATS.
static void act_set_profile(void)
{
    sensor_request_imu_profile((imu_profile_t)rec_fsm_profile(&s_rec.fsm));
    bool take = s_rec.fsm.state == REC_STATE_RECORDING;
    if (s_rec.tap && !take) {
        LOGI("recorder: take %lu ended (reason %u)", (unsigned long)s_rec.fsm.take_id,
             (unsigned)s_rec.fsm.reason);
    }
    s_rec.tap = take;
    s_events |= SIM_EV_REC_TAKE;
}

static void act_audio(bool on)
{
    // rec_audio.c: the I2S channel and the ES7210 only during a take.
    s_rec.audio_on = on;
    if (on) {
        s_rec.mic_mask = s_rec.fsm.mic_mask;
        s_rec.gain_db = s_rec.fsm.gain_db;
        s_rec.audio_index = 0;
    }
    s_events |= SIM_EV_REC_AUDIO;
}

static void act_emit_end(void)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    const rec_end_t e = {
        .host_us = host_us(),
        .reason = s_rec.fsm.reason,
        .records = st.next_seq + 1u,     // END gets next_seq (one producer at a time here)
        .dropped_records = st.dropped_records,
        .dropped_bytes = st.dropped_bytes,
        .imu_frames = s_rec.imu_frames,
        .imu_lost = s_rec.imu_lost,
        .audio_frames = s_rec.audio_frames,
        .audio_overflows = s_rec.audio_overflows,
    };
    const rec_part_t p = {&e, sizeof(e)};
    rec_push(REC_T_END, REC_V_END, &p, 1);
}

static void act_start_ap(void)
{
    // Allocated at OPEN (4 MB of PSRAM on the board), whole for the session.
    if (!s_rec.buf) {
        s_rec.buf = malloc(s_rec.ring_size);
    }
    if (!s_rec.buf) {
        LOGE("recorder: no memory for the stream ring");
        return;
    }
    rec_ring_init(&s_rec.ring, s_rec.buf, s_rec.ring_size, NULL, NULL, NULL);
    memset(s_rec.token, 0, sizeof(s_rec.token));
    s_rec.client = false;
    s_rec.ap_wanted = true;
    s_events |= SIM_EV_REC_AP;
    LOGI("recorder: session open, %lu KiB stream ring", (unsigned long)(s_rec.ring_size / 1024u));
}

static void act_stop_ap(void)
{
    s_rec.ap_wanted = false;
    s_events |= SIM_EV_REC_AP;
    LOGI("recorder: session closing (reason %u)", (unsigned)s_rec.fsm.reason);
}

static void recorder_free(void)
{
    if (s_rec.buf) {
        rec_ring_stats(&s_rec.ring, &s_rec.last);
        s_rec.last.used = 0;
        free(s_rec.buf);
        s_rec.buf = NULL;
    }
    s_rec.ap_wanted = s_rec.client = false;
}

// The FSM's actions, in bit order (rec_fsm.h).
static void recorder_execute(const rec_out_t *o, const rec_cmd_t *cmd)
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
    if ((a & REC_ACT_RING_RESET) && s_rec.buf) {
        rec_ring_reset(&s_rec.ring);
    }
    if ((a & REC_ACT_RING_FLUSH) && s_rec.buf) {
        rec_ring_flush(&s_rec.ring);
    }
    if (a & REC_ACT_AUDIO_ON) {
        act_audio(true);
    }
    if (a & REC_ACT_EMIT_EVENT) {
        bool mark = o->event_code == REC_EV_MARK && cmd;
        rec_event(o->event_code, o->event_arg, mark ? cmd->text : NULL);
    }
    if (a & REC_ACT_EMIT_END) {
        act_emit_end();
    }
    if (a & REC_ACT_START_AP) {
        act_start_ap();
    }
    if (a & REC_ACT_STOP_AP) {
        act_stop_ap();
    }
    if (a & REC_ACT_NOTIFY) {
        s_rec.notify = true;
    }
    if (s_rec.fsm.state == REC_STATE_IDLE) {
        recorder_free();
    }
    recorder_publish();
}

static rec_err_t recorder_input(rec_input_kind_t kind, const rec_cmd_t *cmd)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    const app_battery_t *b = &s_state.battery;
    const rec_env_t env = {
        .battery_valid = b->valid,
        .battery_pct = b->percent,
        .vbus = b->vbus_present,
        .ota_active = s_state.ota_active,
        .calibrating = s_state.cal.kind != APP_CAL_IDLE,
        .ring_empty = st.used == 0,
    };
    const rec_input_t in = {.kind = kind, .cmd = cmd};
    rec_out_t o = rec_fsm_step(&s_rec.fsm, &in, &env, app_now_ms());
    recorder_execute(&o, cmd);
    return o.err;
}

// The sender saw the ring empty.
static void recorder_check_drained(void)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    if (s_rec.fsm.state == REC_STATE_DRAINING && st.used == 0) {
        recorder_input(REC_IN_DRAINED, NULL);
    }
}

static void recorder_init(bool imu_ok)
{
    recorder_free();
    uint32_t size = s_rec.ring_size;
    s_rec = (rec_glue_t){.ring_size = size};
    rec_fsm_init(&s_rec.fsm, app_now_ms());
    if (!imu_ok) {
        recorder_input(REC_IN_IMU_LOST, NULL);
    }
    recorder_publish();
}

// recorder_shutdown(): sleep, power-off and reboot end a take with END,
// best effort (STOP_AP gives the queue 200 ms).
static void recorder_shutdown(void)
{
    if (rec_fsm_session_open(&s_rec.fsm)) {
        LOGI("recorder: shutdown");
        recorder_input(REC_IN_SHUTDOWN, NULL);
    }
}

static void recorder_ble(bool up)
{
    recorder_input(up ? REC_IN_BLE_BACK : REC_IN_BLE_LOST, NULL);
}

static void recorder_resend(void)
{
    s_rec.sent_valid = false;
}

static bool recorder_session_open(void)
{
    return rec_fsm_session_open(&s_rec.fsm);
}

static void recorder_imu_state(bool ok)
{
    recorder_input(ok ? REC_IN_IMU_BACK : REC_IN_IMU_LOST, NULL);
}

static void recorder_vib(bool on)
{
    if (s_rec.tap) {
        rec_event(on ? REC_EV_VIB_START : REC_EV_VIB_STOP, 0, NULL);
    }
}

// recorder_imu_block(): the sensor tap, a non-blocking copy of one FIFO
// read's raw frames, one IMU record per timestamp run.
static void recorder_imu_block(const imu_raw_info_t *raw)
{
    imu_profile_t want = (imu_profile_t)s_rec.fsm.profile;
    if (!s_rec.tap || raw->n == 0 || s_chip.profile != want || s_profile_req != want) {
        return;     // no take, or its profile is not in effect yet
    }
    if (!s_rec.imu_started || raw->epoch != s_rec.imu_epoch) {
        // A new chip clock: the take's first read, or the first after a reset.
        s_rec.epoch_flag = s_rec.imu_started;
        s_rec.imu_started = true;
        s_rec.imu_epoch = raw->epoch;
        s_rec.ts_ext = (rec_ts_ext_t){0};
        s_rec.have_next = false;
        rec_event(REC_EV_PROFILE, want, NULL);
    }
    uint32_t chip = rec_ts_extend(&s_rec.ts_ext, raw->chip_ts);
    for (int k = 0; k < 2; k++) {
        const imu_raw_run_t *run = &raw->run[k];
        if (run->count == 0) {
            continue;
        }
        uint32_t first = rec_ts_near(chip, run->ts_first);
        uint16_t flags = run->discontinuous ? REC_IMU_DISCONT : 0u;
        if (s_rec.epoch_flag) {
            flags |= REC_IMU_EPOCH | REC_IMU_DISCONT;
            s_rec.epoch_flag = false;
        }
        if (s_rec.have_next && !(flags & REC_IMU_DISCONT)) {
            s_rec.imu_lost += first - s_rec.next_ts;
        }
        s_rec.next_ts = first + run->count;
        s_rec.have_next = true;
        int16_t frames[IMU_FIFO_MAX_FRAMES][6];
        for (uint16_t i = 0; i < run->count; i++) {
            imu_map_frame_i16(raw->map, &raw->frames[(size_t)(run->first + i) * IMU_FRAME_BYTES],
                              frames[i]);
        }
        const rec_imu_t h = {.ts_first = first, .chip_ts = chip, .host_us = (uint64_t)raw->host_us,
                             .n = run->count, .flags = flags};
        const rec_part_t p[] = {{&h, sizeof(h)}, {frames, (size_t)run->count * REC_IMU_FRAME_LEN}};
        rec_push(REC_T_IMU, REC_V_IMU, p, 2);
        s_rec.imu_frames += run->count;
    }
    rec_rate_update(&s_rec.imu_rate, raw->epoch, chip, raw->host_us);
}

static void emit_level(void)
{
    db_angle_packet_t a;
    ble_codec_angle(&s_state, 0, app_now_ms(), &a);
    bool z = s_level.zero_set;
    const rec_level_t l = {
        .host_us = host_us(),
        .mode = a.mode,
        .flags = a.flags,
        .elevation_cdeg = a.elevation_cdeg,
        .yaw_cdeg = a.yaw_cdeg,
        .bubble_x_cdeg = a.bubble_x_cdeg,
        .bubble_y_cdeg = a.bubble_y_cdeg,
        .deviation_cdeg = a.deviation_cdeg,
        .zero_elevation_cdeg = z ? ble_codec_cdeg(s_level.zero_elevation_deg) : 0,
        .zero_bubble_x_cdeg = z ? ble_codec_cdeg(s_level.zero_bubble_x_deg) : 0,
        .zero_bubble_y_cdeg = z ? ble_codec_cdeg(s_level.zero_bubble_y_deg) : 0,
    };
    const rec_part_t p = {&l, sizeof(l)};
    rec_push(REC_T_LEVEL, REC_V_LEVEL, &p, 1);
}

static void emit_stats(void)
{
    rec_ring_stats_t st;
    ring_stats(&st);
    const app_battery_t *b = &s_state.battery;
    const rec_stats_t s = {
        .host_us = host_us(),
        .imu_frames = s_rec.imu_frames,
        .imu_lost = s_rec.imu_lost,
        .imu_period_ns = rec_rate_period_ns(&s_rec.imu_rate),
        .audio_frames = s_rec.audio_frames,
        .audio_overflows = s_rec.audio_overflows,
        .audio_rate_mhz = rec_rate_mhz(&s_rec.audio_rate),
        .audio_clipped = s_rec.audio_clipped,
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
    rec_push(REC_T_STATS, REC_V_STATS, &p, 1);
}

void sim_rec_tick(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return;
    }
    recorder_input(REC_IN_TICK, NULL);
    uint32_t now = app_now_ms();
    if (s_rec.tap && now - s_rec.last_level_ms >= REC_LEVEL_MS) {
        s_rec.last_level_ms += REC_LEVEL_MS;
        emit_level();
    }
    if (s_rec.tap && now - s_rec.last_stats_ms >= REC_STATS_MS) {
        s_rec.last_stats_ms += REC_STATS_MS;
        emit_stats();
    }
    recorder_check_drained();
    recorder_publish();
}

int sim_rec_pop(uint8_t *buf, int cap)
{
    if (!s_rec.buf || cap <= 0) {
        return 0;
    }
    size_t got = 0;
    while (got < (size_t)cap) {
        const uint8_t *d;
        size_t n = rec_ring_peek(&s_rec.ring, &d, (size_t)cap - got);
        if (n == 0) {
            break;
        }
        memcpy(&buf[got], d, n);
        rec_ring_consume(&s_rec.ring, n);
        got += n;
    }
    if (s_run == SIM_STATE_RUNNING) {
        recorder_check_drained();
    }
    return (int)got;
}

static bool token_ok(const uint8_t *t)
{
    uint8_t d = 0;
    for (size_t i = 0; i < REC_HELLO_TOKEN_LEN; i++) {
        d |= (uint8_t)(t[i] ^ s_rec.token[i]);
    }
    return d == 0;
}

int sim_rec_net(int kind, const uint8_t *data, int len, uint32_t now_ms)
{
    if (!running(now_ms)) {
        return 0;
    }
    switch (kind) {
    case SIM_NET_UP:
        if (!s_rec.ap_wanted || !data || len != REC_HELLO_TOKEN_LEN) {
            return 0;
        }
        memcpy(s_rec.token, data, sizeof(s_rec.token));
        LOGI("recorder: endpoint %u.%u.%u.%u:%u%s", s_rec.ip[0], s_rec.ip[1], s_rec.ip[2],
             s_rec.ip[3], (unsigned)s_rec.port, s_rec.no_ap ? " (LAN, no AP)" : "");
        recorder_input(REC_IN_AP_UP, NULL);
        return 1;
    case SIM_NET_FAIL:
        LOGE("recorder: endpoint failed");
        recorder_input(REC_IN_AP_FAIL, NULL);
        return 1;
    case SIM_NET_HELLO: {
        bool ok = s_rec.fsm.state == REC_STATE_WAIT_CLIENT && !s_rec.client && data &&
                  len == REC_HELLO_LEN && token_ok(data) &&
                  (data[16] | (data[17] << 8)) == REC_STREAM_VERSION;
        if (!ok) {
            LOGW("recorder: stream client refused");
            return 0;
        }
        s_rec.client = true;
        LOGI("recorder: stream client connected");
        recorder_input(REC_IN_HELLO_OK, NULL);
        return 1;
    }
    case SIM_NET_CLIENT_LOST:
        if (!s_rec.client) {
            return 0;
        }
        s_rec.client = false;
        LOGW("recorder: stream client lost");
        recorder_input(REC_IN_CLIENT_LOST, NULL);
        return 1;
    case SIM_NET_DOWN:
        s_rec.client = false;
        recorder_input(REC_IN_AP_DOWN, NULL);
        return 1;
    default:
        return 0;
    }
}

int sim_rec_ctrl_write(const uint8_t *data, int len, uint32_t now_ms)
{
    if (!gatt_write_begin(now_ms)) {
        return ATT_ERR_UNLIKELY;
    }
    if (!flatten_ok(len)) {
        return BLE_CODEC_ATT_INVALID_LEN;
    }
    rec_cmd_t cmd;
    uint8_t att = ble_codec_parse_rec(data, (size_t)len, &cmd);
    if (att != 0) {
        LOGW("rec_ctrl 0x%02x rejected: 0x%02x", data[0], att);
        return att;
    }
    rec_err_t err = recorder_input(REC_IN_CMD, &cmd);
    if (err == REC_OK && cmd.op == REC_CMD_OPEN && s_rec.fsm.state == REC_STATE_AP_STARTING &&
        !s_rec.buf) {
        err = REC_ERR_RADIO;    // the ring could not be allocated
        recorder_input(REC_IN_AP_FAIL, NULL);
    }
    att = ble_codec_rec_att_err(err);
    if (att != 0) {
        LOGW("rec_ctrl 0x%02x refused: 0x%02x", data[0], att);
    } else {
        LOGI("rec_ctrl 0x%02x", data[0]);
    }
    return att;
}

void sim_rec_state_read(uint8_t *out86)
{
    db_rec_state_packet_t p;
    ble_codec_rec_state(&s_state.rec, &p);
    memcpy(out86, &p, sizeof(p));
}

int sim_rec_notify(uint32_t now_ms, uint8_t *out86)
{
    if (!running(now_ms)) {
        return 0;
    }
    bool pending = s_rec.notify;
    s_rec.notify = false;
    // It carries the credentials: authenticated links only.
    if (!s_connected || !s_link_trusted || !(s_subs & SIM_SUB_REC)) {
        s_rec.sent_valid = false;
        return 0;
    }
    // Computed when it is sent: the heartbeat's elapsed_s is the current
    // second, not the one of the last publish.
    recorder_publish();
    db_rec_state_packet_t p;
    ble_codec_rec_state(&s_state.rec, &p);
    uint32_t now = app_now_ms();
    bool take = p.state == REC_STATE_RECORDING || p.state == REC_STATE_DRAINING;
    if (!pending && s_rec.sent_valid && !ble_codec_rec_state_changed(&s_rec.sent, &p) &&
        !(take && now - s_rec.sent_ms >= REC_HEARTBEAT_MS)) {
        return 0;
    }
    s_rec.sent = p;
    s_rec.sent_valid = true;
    s_rec.sent_ms = now;
    memcpy(out86, &p, sizeof(p));
    return 1;
}

int sim_rec_note_touch(uint32_t now_ms)
{
    if (!running(now_ms) || s_rec.fsm.state != REC_STATE_RECORDING) {
        return 0;
    }
    rec_event(REC_EV_TOUCH, 0, NULL);
    return 1;
}

int sim_rec_ui_stop(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return ATT_ERR_UNLIKELY;
    }
    const rec_cmd_t c = {.op = REC_CMD_STOP};
    return ble_codec_rec_att_err(recorder_input(REC_IN_CMD, &c));
}

int sim_audio_block(uint32_t now_ms, const sim_motor_t *m)
{
    if (!running(now_ms) || !s_rec.audio_on || !m) {
        return 0;
    }
    float f[SIM_AUDIO_BLOCK * 2];
    int16_t rec[SIM_AUDIO_BLOCK * 2];
    sim_audio_synth(m, SIM_AUDIO_BLOCK, f);
    // The ES7210 PGA, then the 16-bit converter.
    float gain = powf(10.0f, (float)s_rec.gain_db / 20.0f) * 32767.0f;
    bool both = (s_rec.mic_mask & REC_MIC_ALL) == REC_MIC_ALL;
    int only = (s_rec.mic_mask & REC_MIC_1) ? 0 : 1;
    size_t k = 0;
    for (int i = 0; i < SIM_AUDIO_BLOCK * 2; i++) {
        float v = roundf(f[i] * gain);
        int16_t pcm = v >= 32767.0f ? 32767 : v <= -32768.0f ? -32768 : (int16_t)v;
        if (both || i % 2 == only) {
            rec[k++] = pcm;
            s_rec.audio_clipped += (pcm >= 32767 || pcm <= -32767) ? 1u : 0u;
        }
    }
    const rec_audio_t h = {.sample_index = s_rec.audio_index, .host_us = host_us(),
                           .n = SIM_AUDIO_BLOCK, .flags = 0};
    const rec_part_t p[] = {{&h, sizeof(h)}, {rec, k * sizeof(int16_t)}};
    rec_push(REC_T_AUDIO, REC_V_AUDIO, p, 2);
    s_rec.audio_index += SIM_AUDIO_BLOCK;
    s_rec.audio_frames += SIM_AUDIO_BLOCK;
    rec_rate_update(&s_rec.audio_rate, 0, s_rec.audio_index, (int64_t)h.host_us);
    return SIM_AUDIO_BLOCK;
}

// ---- control.c -------------------------------------------------------------------

#define PMU_STATUS_PERIOD_MS 500
#define PMU_EVENTS_PERIOD_MS 200
#define OFF_BATTERY_PCT 5
#define BATTERY_SETTLE_MS 5000
#define PWRKEY_BOOT_IGNORE_MS 2000
#define CAL_IDLE_GRACE_MS (5u * 60u * 1000u)
#define VIB_HOLD_MS (3u * 60u * 1000u)

// The AXP2101 registers pmu.cpp reads (datasheet 6.13.2): REG 00 VBUS good,
// REG 01 battery current direction and charger state, and the SOC IRQ
// levels pmu_init() sets (REG 1A).
#define PMU_STATUS1_VBUS_GOOD    (1u << 5)
#define PMU_STATUS2_DIR_CHARGE   (1u << 5)
#define PMU_STATUS2_CHG_DONE     0x4u
#define PMU_LOW_BAT_WARN_PCT     10
#define PMU_LOW_BAT_SHUTDOWN_PCT 5

static settings_t s_settings;
static power_fsm_t s_fsm;
static power_state_t s_power = POWER_ACTIVE;
static int s_brightness = -1;
static uint32_t s_last_status_ms;
static uint32_t s_last_events_ms;
static bool s_cal_from_ble;
static uint32_t s_start_ms;
// The AXP2101 as pmu_get_status() reads it (REG 00, REG 01, the gauge, the
// battery ADC) and its IRQ status (REG 48..4A); survives a reboot.
static struct {
    uint8_t status1, status2;
    int percent;
    uint16_t vbat_mv;
} s_pmu;
static uint8_t s_pmu_irq[3];

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
    if (s_power != POWER_ACTIVE && s_power != POWER_DIM) {
        return;
    }
    int pct = s_power == POWER_DIM ? s_settings.dim_brightness_pct : s_settings.brightness_pct;
    s_brightness = pct;
}

int sim_power(void)
{
    return (int)s_state.power;
}

uint32_t sim_off_after_s(void)
{
    return (uint32_t)s_settings.off_after_min * 60u;
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

static void prepare_shutdown(app_power_t p, const char *what)
{
    LOGI("%s", what);
    recorder_shutdown();   // first: END while the producers still run
    app_state_set_power(p);
    sensor_stop();
    ble_service_shutdown();
}

static void enter_sleep(void)
{
    prepare_shutdown(APP_POWER_SLEEP, "deep sleep");
    halt(SIM_STATE_SLEEP, SIM_EV_SLEEP);
}

static void enter_off(void)
{
    prepare_shutdown(APP_POWER_OFF, "power off");
    halt(SIM_STATE_OFF, SIM_EV_POWER_OFF);
}

typedef struct {
    bool step;
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
    s->mode = (level_mode_t)m;
    return true;
}

static void change_mode(bool step, int32_t arg)
{
    mode_change_t c = {.step = step, .arg = arg};
    if (settings_update(apply_mode, &c) != 0) {
        LOGW("mode not changed");
    }
}

static void start_cal(app_cal_kind_t kind, int32_t arg)
{
    if (recorder_session_open()) {
        // OTA, calibration and a recording session exclude each other.
        LOGW("calibration refused: a recording session is open");
        return;
    }
    s_cal_from_ble = arg == APP_CMD_ARG_FROM_BLE;
    sensor_cal_start(kind);
}

static void cancel_cal(int32_t arg)
{
    if (arg == APP_CMD_ARG_FROM_BLE) {
        if (!s_cal_from_ble) {
            return;
        }
        if (s_state.cal.kind != APP_CAL_IDLE) {
            LOGW("calibration cancelled: BLE client gone");
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
        LOGW("mount calibration finish without start");
        return;
    }
    int result = (int)res;
    if (res == LEVEL_CAL_OK) {
        int err = settings_update(apply_mount_cal, &axis);
        LOGI("mount calibrated: axis %.4f %.4f %.4f", (double)axis.x, (double)axis.y,
             (double)axis.z);
        if (err != 0) {
            LOGE("mount calibration not stored");
            result = APP_CAL_RESULT_NOT_STORED;
        }
    } else {
        LOGW("mount calibration failed: %d", result);
    }
    sensor_cal_done(APP_CAL_MOUNT, result);
}

typedef struct {
    accel_cal_t cal;
    bool mount_dropped;
} accel_store_t;

static bool apply_accel_cal(settings_t *s, void *ctx)
{
    accel_store_t *a = ctx;
    s->accel_cal = a->cal;
    s->accel_cal_valid = true;
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
        LOGW("sensor calibration finish without start");
        return;
    }
    int result = (int)res;
    if (res == ACCEL_CAL_OK) {
        int err = settings_update(apply_accel_cal, &a);
        const accel_cal_t *cal = &a.cal;
        LOGI("accel calibrated: bias %.4f %.4f %.4f g, scale %.4f %.4f %.4f",
             (double)cal->bias.x, (double)cal->bias.y, (double)cal->bias.z,
             (double)cal->scale.x, (double)cal->scale.y, (double)cal->scale.z);
        if (err != 0) {
            LOGE("accel calibration not stored");
            result = APP_CAL_RESULT_NOT_STORED;
        } else if (a.mount_dropped) {
            LOGW("mount calibration reset: redo it with the new sensor calibration");
            result = APP_CAL_RESULT_OK_MOUNT_DROPPED;
        }
    } else {
        LOGW("sensor calibration failed: %d", result);
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
    ble_service_set_enabled(s_settings.ble_enabled);
    s_events |= SIM_EV_SETTINGS;
}

static void handle_cmd(const app_cmd_t *cmd)
{
    switch (cmd->id) {
    case APP_CMD_ZERO:
        if (!sensor_zero()) {
            LOGW("zero ignored: no orientation");
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
        LOGI("reboot");
        recorder_shutdown();
        halt(SIM_STATE_REBOOT, SIM_EV_REBOOT);
        break;
    case APP_CMD_FACTORY_RESET:
        LOGW("factory reset");
        cancel_cal(0);
        sensor_clear_zero();
        if (settings_reset() != 0) {
            LOGE("factory reset: settings not reset");
        }
        s_events |= SIM_EV_CLEAR_BONDS;   // ble_service_clear_bonds(): drops the link
        break;
    default:
        LOGW("unknown command %d", (int)cmd->id);
        break;
    }
}

void sim_set_battery(int valid, int percent, int voltage_mv, int charging, int charge_done,
                     int vbus)
{
    uint8_t st1 = (uint8_t)((valid ? PMU_STATUS1_BAT_PRESENT : 0u) |
                            (vbus ? PMU_STATUS1_VBUS_GOOD : 0u));
    uint8_t st2 = (uint8_t)((charging ? PMU_STATUS2_DIR_CHARGE : 0u) |
                            (charge_done ? PMU_STATUS2_CHG_DONE : 0u));
    int pct = valid ? percent : -1;   // pmu.cpp: the gauge is read only with a battery
    // The IRQs the change raises (PMU_IRQ*_ENABLED).
    if ((st1 ^ s_pmu.status1) & PMU_STATUS1_VBUS_GOOD) {
        s_pmu_irq[1] |= vbus ? PMU_IRQ1_VBUS_INSERT : PMU_IRQ1_VBUS_REMOVE;
    }
    if (charging && !(s_pmu.status2 & PMU_STATUS2_DIR_CHARGE)) {
        s_pmu_irq[2] |= PMU_IRQ2_CHARGE_START;
    }
    if (charge_done && !(s_pmu.status2 & PMU_STATUS2_CHG_DONE)) {
        s_pmu_irq[2] |= PMU_IRQ2_CHARGE_DONE;
    }
    if (pct >= 0 && pct < PMU_LOW_BAT_WARN_PCT && s_pmu.percent >= PMU_LOW_BAT_WARN_PCT) {
        s_pmu_irq[0] |= PMU_IRQ0_SOC_WARN;
    }
    if (pct >= 0 && pct < PMU_LOW_BAT_SHUTDOWN_PCT && s_pmu.percent >= PMU_LOW_BAT_SHUTDOWN_PCT) {
        s_pmu_irq[0] |= PMU_IRQ0_SOC_SHUTDOWN;
    }
    s_pmu.status1 = st1;
    s_pmu.status2 = st2;
    s_pmu.percent = pct;
    s_pmu.vbat_mv = (uint16_t)(voltage_mv < 0 ? 0 : voltage_mv > 0x1FFF ? 0x1FFF : voltage_mv);
}

void sim_pwrkey(int long_press)
{
    s_pmu_irq[1] |= long_press ? PMU_IRQ1_PWRKEY_LONG : PMU_IRQ1_PWRKEY_SHORT;
}

// pmu.h: reads and clears the IRQ status.
uint32_t pmu_poll_events(void)
{
    uint32_t ev = pmu_events_from_irq(s_pmu_irq);
    memset(s_pmu_irq, 0, sizeof(s_pmu_irq));
    return ev;
}

static void poll_pmu(uint32_t now)
{
    if (now - s_last_events_ms >= PMU_EVENTS_PERIOD_MS) {
        s_last_events_ms = now;
        uint32_t ev = pmu_poll_events();
        if (ev & (PMU_EVT_VBUS_IN | PMU_EVT_VBUS_OUT | PMU_EVT_PWRKEY_SHORT |
                  PMU_EVT_PWRKEY_LONG)) {
            app_note_activity();
        }
        if ((ev & (PMU_EVT_PWRKEY_SHORT | PMU_EVT_PWRKEY_LONG)) &&
            (uint32_t)(now - s_start_ms) < PWRKEY_BOOT_IGNORE_MS) {
            LOGI("PWR key event 0x%08lx ignored: power-on press", (unsigned long)ev);
            ev &= ~(uint32_t)(PMU_EVT_PWRKEY_SHORT | PMU_EVT_PWRKEY_LONG);
        }
        if (ev & PMU_EVT_PWRKEY_SHORT) {
            app_post_cmd(APP_CMD_SLEEP, 0);
        }
        if (ev & PMU_EVT_PWRKEY_LONG) {
            app_post_cmd(APP_CMD_POWER_OFF, 0);
        }
        if (ev & PMU_EVT_BATTERY_LOW) {
            LOGW("battery low");
        }
        if (ev & (PMU_EVT_VBUS_IN | PMU_EVT_VBUS_OUT | PMU_EVT_CHARGE_START |
                  PMU_EVT_CHARGE_DONE)) {
            s_last_status_ms = now - PMU_STATUS_PERIOD_MS;
        }
    }
    if (now - s_last_status_ms >= PMU_STATUS_PERIOD_MS) {
        s_last_status_ms = now;
        pmu_status_t st;
        pmu_decode_status(s_pmu.status1, s_pmu.status2, s_pmu.percent, s_pmu.vbat_mv, &st);
        app_battery_t b = {
            .valid = st.battery_present,
            .percent = st.percent,
            .voltage_mv = st.voltage_mv,
            .charging = st.charging,
            .charge_done = st.charge_done,
            .vbus_present = st.vbus_present,
        };
        app_state_set_battery(&b);
    }
}

static void check_abandoned_cal(const app_snapshot_t *snap, uint32_t now)
{
    if (snap->cal.kind == APP_CAL_IDLE) {
        return;
    }
    if (power_fsm_idle_ms(&s_fsm, now) > s_fsm.cfg.sleep_after_ms + CAL_IDLE_GRACE_MS) {
        LOGW("calibration cancelled: idle");
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
        .ble_connected = snap.ble_holds_awake,
        .ota_active = snap.ota_active,
        .calibrating = snap.cal.kind != APP_CAL_IDLE,
        .recording = recorder_session_open(),
    };
    power_state_t st = power_fsm_update(&s_fsm, &in, now);
    check_abandoned_cal(&snap, now);
    if (st != s_power) {
        LOGI("power state %d -> %d", (int)s_power, (int)st);
        s_power = st;
        app_state_set_power(to_app_power(st));
    }
    switch (st) {
    case POWER_SLEEP:
        enter_sleep();
        break;
    case POWER_OFF:
        LOGW("battery %u%%: power off", (unsigned)b->percent);
        enter_off();
        break;
    default:
        apply_brightness();
        break;
    }
}

void sim_control_step(uint32_t now_ms)
{
    if (!running(now_ms)) {
        return;
    }
    // The control task handles one command per pass and loops at once while
    // the queue has more.
    do {
        app_cmd_t cmd;
        if (receive_cmd(&cmd)) {
            handle_cmd(&cmd);
        }
        if (s_run != SIM_STATE_RUNNING) {
            return;
        }
        poll_pmu(now_ms);
        update_power(now_ms);
    } while (s_run == SIM_STATE_RUNNING && s_cmd_count > 0);
}

static void control_start(const settings_t *s)
{
    s_settings = *s;
    app_state_set_cal_valid(s->mount_cal_valid, s->accel_cal_valid);
    uint32_t now = app_now_ms();
    s_start_ms = now;
    power_fsm_config_t cfg = fsm_config(s);
    power_fsm_init(&s_fsm, &cfg, now);
    s_power = POWER_ACTIVE;
    s_last_status_ms = now - PMU_STATUS_PERIOD_MS;
    s_last_events_ms = now - PMU_EVENTS_PERIOD_MS;
    memset(s_pmu_irq, 0, sizeof(s_pmu_irq));   // pmu_init() clears the IRQ status
    s_cal_from_ble = false;
    app_state_set_power(APP_POWER_ACTIVE);
}

// ---- app_main.c ------------------------------------------------------------------

int sim_init(const char *state_path, const char *ota_image_path, const char *default_version,
             uint32_t now_ms, int imu_present)
{
    if (state_path == NULL || ota_image_path == NULL ||
        strlen(state_path) >= PATH_MAX_LEN || strlen(ota_image_path) >= PATH_MAX_LEN) {
        return -1;
    }
    s_now = now_ms;
    s_events = 0;
    s_busy_next = 0;
    copy_str(s_state_path, sizeof(s_state_path), state_path);
    copy_str(s_ota_path, sizeof(s_ota_path), ota_image_path);
    copy_str(s_default_version, sizeof(s_default_version), default_version);

    app_state_init();
    state_load(&s_current);
    // First boot after an OTA: the new image runs (no self-test failure to
    // roll back from in the simulator).
    if (s_fw_pending[0]) {
        copy_str(s_fw_ota, sizeof(s_fw_ota), s_fw_pending);
        s_fw_pending[0] = '\0';
        state_save(&s_current);
    }
    LOGI("DrillBalancer v%s", sim_fw_version());
    s_brightness = s_current.brightness_pct;   // power_set_brightness() at boot
    sensor_start(&s_current, imu_present != 0);
    ota_update_init();
    ble_service_init(s_current.ble_enabled);
    recorder_init(imu_present != 0);
    control_start(&s_current);
    s_run = SIM_STATE_RUNNING;
    return 0;
}

const char *sim_fw_version(void)
{
    return s_fw_ota[0] ? s_fw_ota : s_default_version;
}

// ---- snapshot --------------------------------------------------------------------

typedef struct {
    char *buf;
    size_t cap, len;
    bool overflow;
} out_t;

__attribute__((format(printf, 2, 3)))
static void put(out_t *o, const char *fmt, ...)
{
    if (o->overflow) {
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(o->buf + o->len, o->cap - o->len, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= o->cap - o->len) {
        o->overflow = true;
        return;
    }
    o->len += (size_t)n;
}

static void put_float(out_t *o, const char *key, float v)
{
    if (isfinite(v)) {
        put(o, "\"%s\":%.7g,", key, (double)v);
    } else {
        put(o, "\"%s\":null,", key);
    }
}

static void put_vec(out_t *o, const char *key, vec3_t v)
{
    put(o, "\"%s\":[%.7g,%.7g,%.7g],", key, (double)v.x, (double)v.y, (double)v.z);
}

static void put_text(out_t *o, const char *s)
{
    put(o, "\"");
    for (; s && *s; s++) {
        if (*s == '"' || *s == '\\') {
            put(o, "\\%c", *s);
        } else if ((unsigned char)*s < 0x20) {
            put(o, "\\u%04x", (unsigned)*s);
        } else {
            put(o, "%c", *s);
        }
    }
    put(o, "\",");
}

static void put_str(out_t *o, const char *key, const char *s)
{
    put(o, "\"%s\":", key);
    put_text(o, s);
}

#define B(x) ((x) ? "true" : "false")

// Drops the trailing comma of the last member before a closing bracket.
static void close_obj(out_t *o, char c)
{
    if (!o->overflow && o->len > 0 && o->buf[o->len - 1] == ',') {
        o->len--;
    }
    put(o, "%c,", c);
}

int sim_snapshot_json(char *buf, int cap)
{
    if (cap <= 0) {
        return -1;
    }
    out_t o = {.buf = buf, .cap = (size_t)cap};
    const app_snapshot_t *s = &s_state;
    const level_reading_t *r = &s->reading;
    static const char *const STATES[] = {"running", "sleep", "off", "reboot"};

    put(&o, "{");
    put(&o, "\"now_ms\":%lu,", (unsigned long)s_now);
    put_str(&o, "state", STATES[s_run]);
    put_str(&o, "fw_version", sim_fw_version());
    put(&o, "\"imu_ok\":%s,\"still\":%s,\"vibrating\":%s,\"zero_set\":%s,\"reading_ms\":%lu,",
        B(s->imu_ok), B(s->still), B(s->vibrating), B(s->zero_set), (unsigned long)s->reading_ms);
    put(&o, "\"q\":[%.7g,%.7g,%.7g,%.7g],", (double)s->q.w, (double)s->q.x, (double)s->q.y,
        (double)s->q.z);
    put(&o, "\"reading\":{\"mode\":%d,", (int)r->mode);
    put_float(&o, "elevation", r->elevation_deg);
    put_float(&o, "yaw", r->yaw_deg);
    put_float(&o, "bubble_x", r->bubble_x_deg);
    put_float(&o, "bubble_y", r->bubble_y_deg);
    put_float(&o, "deviation", r->deviation_deg);
    put(&o, "\"status\":%d,\"yaw_status\":%d,\"yaw_valid\":%s,\"yaw_stale\":%s", (int)r->status,
        (int)r->yaw_status, B(r->yaw_valid), B(r->yaw_stale));
    close_obj(&o, '}');
    put(&o, "\"battery\":{\"valid\":%s,\"percent\":%u,\"voltage_mv\":%u,\"charging\":%s,"
            "\"charge_done\":%s,\"vbus\":%s},",
        B(s->battery.valid), (unsigned)s->battery.percent, (unsigned)s->battery.voltage_mv,
        B(s->battery.charging), B(s->battery.charge_done), B(s->battery.vbus_present));
    put(&o, "\"mount_cal_valid\":%s,\"accel_cal_valid\":%s,", B(s->mount_cal_valid),
        B(s->accel_cal_valid));
    put(&o, "\"ble_enabled\":%s,\"ble_connected\":%s,\"ble_holds_awake\":%s,", B(s->ble_enabled),
        B(s->ble_connected), B(s->ble_holds_awake));
    put(&o, "\"passkey_pending\":%s,\"passkey\":%lu,", B(s->passkey_pending),
        (unsigned long)s->passkey);
    put(&o, "\"ota_active\":%s,\"ota_percent\":%u,\"ota_state\":%d,", B(s->ota_active),
        (unsigned)s->ota_percent, (int)ota_proto_state(&s_proto));
    put(&o, "\"power\":%d,\"brightness\":%d,", (int)s->power, s_brightness);
    put(&o, "\"cal\":{\"kind\":%d,\"samples\":%u,\"coverage\":%u,\"last_result\":%d,"
            "\"last_kind\":%d},",
        (int)s->cal.kind, (unsigned)s->cal.samples, (unsigned)s->cal.accel_coverage,
        (int)s->cal.last_result, (int)s->cal.last_kind);
    put(&o, "\"link\":{\"connected\":%s,\"trusted\":%s,\"pairing\":%s,\"subs\":%u},",
        B(s_connected), B(s_link_trusted), B(s_pairing), (unsigned)s_subs);

    // The recorder: rec_state's fields plus what only the device knows.
    const app_rec_status_t *rc = &s->rec;
    rec_ring_stats_t rs;
    ring_stats(&rs);
    put(&o, "\"rec\":{\"state\":%u,\"reason\":%u,\"flags\":%u,\"take_id\":%lu,\"elapsed_s\":%u,"
            "\"max_s\":%u,\"profile\":%u,\"take_valid\":%s,\"tap\":%s,\"ap_wanted\":%s,"
            "\"client\":%s,\"audio_on\":%s,\"mic_mask\":%u,\"gain_db\":%u,",
        (unsigned)rc->state, (unsigned)rc->reason, (unsigned)rc->flags, (unsigned long)rc->take_id,
        (unsigned)rc->elapsed_s, (unsigned)rc->max_s, (unsigned)rc->profile,
        B(s_rec.fsm.take_valid), B(s_rec.tap), B(s_rec.ap_wanted), B(s_rec.client),
        B(s_rec.audio_on), (unsigned)s_rec.fsm.mic_mask, (unsigned)s_rec.fsm.gain_db);
    put(&o, "\"sent_bytes\":%llu,\"dropped\":%lu,\"dropped_bytes\":%lu,\"ring_used\":%lu,"
            "\"ring_size\":%lu,\"ring_pct\":%u,\"high_water\":%lu,\"records\":%lu,",
        (unsigned long long)rs.sent_bytes, (unsigned long)rs.dropped_records,
        (unsigned long)rs.dropped_bytes, (unsigned long)rs.used,
        (unsigned long)(s_rec.buf ? rs.cap : s_rec.ring_size), (unsigned)rc->ring_pct,
        (unsigned long)rs.high_water, (unsigned long)rs.next_seq);
    put(&o, "\"imu_frames\":%lu,\"imu_lost\":%lu,\"imu_period_ns\":%lu,\"audio_frames\":%lu,"
            "\"audio_clipped\":%lu,\"audio_rate_mhz\":%lu,\"imu_profile\":%d,\"imu_epoch\":%lu,"
            "\"mic_gain_db\":[%.2f,%.2f]},",
        (unsigned long)s_rec.imu_frames, (unsigned long)s_rec.imu_lost,
        (unsigned long)rec_rate_period_ns(&s_rec.imu_rate), (unsigned long)s_rec.audio_frames,
        (unsigned long)s_rec.audio_clipped, (unsigned long)rec_rate_mhz(&s_rec.audio_rate),
        (int)s_chip.profile, (unsigned long)s_epoch, (double)sim_audio_mic_gain_db(1),
        (double)sim_audio_mic_gain_db(2));

    char json[SETTINGS_JSON_MAX];
    if (settings_to_json(&s_current, json, sizeof(json)) > 0) {
        put(&o, "\"settings\":%s,", json);
    }
    put_vec(&o, "drill_axis", s_current.drill_axis);
    put_vec(&o, "accel_bias", s_current.accel_cal.bias);
    put_vec(&o, "accel_scale", s_current.accel_cal.scale);
    put(&o, "\"fusion\":{\"ready\":%s,", B(s_fusion_ready));
    put_vec(&o, "gyro_bias", s_fusion.gyro_bias);
    put(&o, "\"bias_valid\":%s},", B(fusion_bias_valid(&s_fusion)));

    // What the round screen derives from the snapshot (ui_logic.c).
    put(&o, "\"ui\":{");
    put_float(&o, "ring", ui_ring_fraction(r->deviation_deg, s_current.near_deg));
    put(&o, "\"wall_arrow\":%d,\"tenths\":%ld,\"battery_level\":%d,\"low_battery\":%s,",
        ui_wall_arrow(r), (long)ui_tenths(r->elevation_deg), ui_battery_level(s->battery.percent),
        B(ui_low_battery(&s->battery)));
    put(&o, "\"yaw_tenths\":%ld,", (long)ui_tenths(r->yaw_deg));
    put_str(&o, "mode_title", ui_mode_title(r->mode));
    // The settings list's value texts, in ui_field_t order.
    put(&o, "\"settings_text\":[");
    for (int f = 0; f < UI_FIELD_COUNT; f++) {
        char t[32];
        ui_settings_format(&s_current, (ui_field_t)f, t, sizeof(t));
        put_text(&o, t);
    }
    close_obj(&o, ']');
    int res = s->cal.last_result;
    const char *text = s->cal.last_kind == APP_CAL_MOUNT ? ui_mount_result_text(res)
                       : s->cal.last_kind == APP_CAL_ACCEL ? ui_accel_result_text(res) : "";
    put_str(&o, "cal_result_text", text);
    put(&o, "\"cal_result_known\":%s,\"cal_result_ok\":%s", B(ui_cal_result_known(res)),
        B(ui_cal_result_ok(res)));
    close_obj(&o, '}');
    close_obj(&o, '}');
    if (o.overflow) {
        return -1;
    }
    o.buf[--o.len] = '\0';   // the comma after the outer object
    return (int)o.len;
}

const char *sim_ui_text(int which, int code)
{
    switch (which) {
    case 0:
        return ui_mode_title((level_mode_t)code);
    case 1:
        return ui_mount_result_text(code);
    case 2:
        return ui_accel_result_text(code);
    default:
        return "";
    }
}
