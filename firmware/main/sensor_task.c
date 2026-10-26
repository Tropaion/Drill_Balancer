#include <math.h>
#include "sensor_task.h"
#include "bsp/esp-bsp.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "fusion.h"
#include "imu.h"
#include "lpf.h"
#include "recorder.h"
#include "turn.h"
#include "vibration.h"

static const char *TAG = "sensor";

// Above the LVGL task (6) and the control task (5), so a long frame or an
// NVS write never starves the FIFO; below the NimBLE host, the BT controller
// and esp_timer, whose deadlines are tighter. Pinned to core 1, away from
// NimBLE (core 0), which also keeps the FPU context on one core.
#define SENSOR_TASK_PRIO  10
#define SENSOR_TASK_CORE  1
// Fusion and level are small; the ESP_LOGI of a kept pose is the deepest
// path. TODO(hw): check uxTaskGetStackHighWaterMark() after a sensor
// calibration and an IMU re-init.
#define SENSOR_TASK_STACK 4096

// About 9 samples per pass at 448 Hz, far inside the 128-sample (285 ms)
// FIFO, so a late pass loses nothing. Every pass publishes to app_state and
// feeds the mount calibration (50 Hz).
#define POLL_PERIOD_MS     20
#define FIFO_BATCH         64
// While the IMU is missing, imu_ok = false is still re-published at this
// rate so readers see a fresh timestamp.
#define NO_IMU_PUBLISH_MS  200

// No new samples for this long (failed reads, or reads that stay empty: a
// FIFO left in read mode, or a chip that reset itself and stopped sampling)
// means the IMU is lost, so a frozen orientation is never published as a
// fresh reading. A lost IMU is re-initialised every IMU_RETRY_MS.
#define IMU_LOST_MS    200
#define IMU_RETRY_MS   2000

#define STOP_TIMEOUT_MS 1000

static SemaphoreHandle_t s_lock;
static StaticSemaphore_t s_lock_buf;
static SemaphoreHandle_t s_stopped;
static StaticSemaphore_t s_stopped_buf;
static TaskHandle_t s_task;
static volatile bool s_stop;

// --- guarded by s_lock ---
static fusion_config_t s_fusion_cfg;
static fusion_t s_fusion;
static bool s_fusion_ready;   // fusion_init() done since the IMU (re)started
static level_t s_level;
static bool s_acal_valid;
static accel_cal_t s_acal;

static app_cal_status_t s_cal;   // what app_state shows
static bool s_collecting;        // false between finish and done
static level_mount_collector_t s_mount;
static accel_cal_collector_t s_collector;

// sensor_request_imu_profile(): any task writes it, the sensor task applies
// it at its next pass (imu_profile_t, one byte).
static volatile uint8_t s_profile_req = IMU_PROFILE_LEVEL;

// --- sensor task only ---
static imu_sample_t s_buf[FIFO_BATCH];
// Under a RAW profile (chip LPFs off) everything but the recorder's tap
// gets this copy of s_buf through the LEVEL profile's LPFs (lpf.h).
static imu_sample_t s_filt[FIFO_BATCH];
static lpf_feed_t s_lpf;
static vib_detector_t s_vib;
// What the recorder was told (vib_note()).
static bool s_vib_running;
// Turning the device (turn.h) is user activity for the power policy, a
// running motor shaking the gyro is not, so it never re-arms the power FSM's
// vibration hold. Restarts at rest with the fusion.
static turn_detector_t s_turn;
// Largest low-passed turn rate in the open vibration window, for the debug
// log. Raised per batch, so it can include up to one batch (20 ms) after
// the window closes.
static float s_win_max_rate;

static void lock(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
}

static void unlock(void)
{
    xSemaphoreGive(s_lock);
}

// Callers hold s_lock, so the published status follows the same order as
// the changes.
static void publish_cal_locked(void)
{
    app_state_set_cal(&s_cal);
}

static void accel_pose_kept_locked(void)
{
    vec3_t mean = s_collector.poses[s_collector.n - 1];
    s_cal.samples = s_collector.n;
    s_cal.accel_coverage = accel_cal_coverage(&s_collector);
    ESP_LOGI(TAG, "accel pose %u kept (%.3f %.3f %.3f g), coverage 0x%02x",
             (unsigned)s_collector.n, (double)mean.x, (double)mean.y, (double)mean.z,
             (unsigned)s_cal.accel_coverage);
    publish_cal_locked();
}

// Runs one FIFO batch through calibration, fusion and the turn detector.
// Returns whether the device was turned in it.
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
            level_reset_heading(&s_level);   // fusion_init() starts at yaw 0
            turn_reset(&s_turn);
            continue;
        }
        float rate = fusion_update(&s_fusion, smp[i].gyro_rad_s, a, dt);
        turning |= turn_add_sample(&s_turn, vec3_sub(smp[i].gyro_rad_s, s_fusion.gyro_bias), dt);
        s_win_max_rate = fmaxf(s_win_max_rate, s_turn.rate);
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
    // UI and BLE only need a few updates a second.
    if (s_mount.fit.n % 10u == 0) {
        publish_cal_locked();
    }
}

// The recorder hears the motor start and stop (VIB_START / VIB_STOP): the
// detector's RUNNING against everything else. A reset (restart_pipeline(), its
// state UNKNOWN) of a running detector is a stop, and the QUIET it decides
// after a reset is none, so every VIB_START of a take has its VIB_STOP.
static void vib_note(bool running)
{
    if (running != s_vib_running) {
        s_vib_running = running;
        recorder_note_vib(running);
    }
}

static float profile_odr_hz(void)
{
    return (float)imu_profile_info(imu_get_profile())->odr_mhz * 1e-3f;
}

// The filters that integrate the samples start over: the IMU lost or back
// (seconds without samples). sensor_zero() fails until fusion has its
// first sample again, and the WALL heading zero is gone with the yaw.
static void restart_pipeline(void)
{
    lock();
    s_fusion_ready = false;
    unlock();
    vib_reset(&s_vib);
    vib_note(false);
    s_win_max_rate = 0.0f;   // the open window is dropped with it
    lpf_feed_reset(&s_lpf, profile_odr_hz());
}

// A profile switch (every RAW take starts and ends with one): fusion, the
// level zeros (also the WALL heading), the detectors and the collectors go
// through it; only the decode scale (imu.c) and, under a RAW profile, the
// software LPF change. The ~180 ms of the chip's reset are a gap in the
// samples they go across (a turn within it is missed, as in a lost read).
// The LPF gets the new rate's coefficients and goes on from the last
// sample fusion got, so a switch to RAW is no step either.
static void profile_switched(void)
{
    lpf_feed_switch(&s_lpf, profile_odr_hz());
}

// The samples fusion and the detectors get: as read under LEVEL (the chip
// filters), through the LEVEL LPFs otherwise. s_buf stays raw.
static const imu_sample_t *level_samples(size_t n)
{
    bool filter = imu_get_profile() != IMU_PROFILE_LEVEL;
    imu_sample_t *out = filter ? s_filt : s_buf;
    for (size_t i = 0; i < n; i++) {
        if (filter) {
            s_filt[i] = s_buf[i];
        }
        lpf_feed_step(&s_lpf, filter, &out[i].accel_g, &out[i].gyro_rad_s);
    }
    return out;
}

static void publish_no_imu(uint32_t now)
{
    lock();
    // Status 0 is ON_TARGET, so an all-zero reading would read as green.
    level_reading_t r = {.mode = s_level.mode, .status = LEVEL_STATUS_OFF,
                         .yaw_status = LEVEL_STATUS_OFF};
    float z[3];
    const float *zero = level_zero_of(&s_level, z);
    unlock();
    app_state_set_motion(false, quat_identity(), false, false, zero, &r, now);
}

static bool try_imu_init(void)
{
    // In the profile requested last, also while the IMU was gone.
    esp_err_t err = imu_init(bsp_i2c_get_handle(), (imu_profile_t)s_profile_req);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "IMU init failed: %s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "IMU running");
    return true;
}

static void sensor_task(void *arg)
{
    bool imu_ok = (bool)(uintptr_t)arg;   // imu_init() result from sensor_start()
    uint32_t last_retry = app_now_ms();
    uint32_t last_pub = 0;             // last imu_ok = false publish
    uint32_t last_data = last_retry;   // last read that returned samples
    TickType_t wake = xTaskGetTickCount();

    while (!s_stop) {
        xTaskDelayUntil(&wake, pdMS_TO_TICKS(POLL_PERIOD_MS));
        uint32_t now = app_now_ms();

        if (!imu_ok) {
            if (now - last_retry >= IMU_RETRY_MS) {
                last_retry = now;
                if (try_imu_init()) {
                    imu_ok = true;
                    restart_pipeline();   // the profile may be another now
                    recorder_note_imu(true);
                    last_data = app_now_ms();
                    wake = xTaskGetTickCount();   // imu_init blocked ~180 ms
                    continue;
                }
            }
            if (now - last_pub >= NO_IMU_PUBLISH_MS) {
                last_pub = now;
                publish_no_imu(now);
            }
            continue;
        }

        // sensor_request_imu_profile(): fusion and the detectors go through
        // a new profile (profile_switched()). The same profile again does
        // nothing (imu_set_profile()).
        imu_profile_t req = (imu_profile_t)s_profile_req;
        if (req != imu_get_profile()) {
            esp_err_t perr = imu_set_profile(req);
            if (perr != ESP_OK) {
                // The lost check below re-initialises it (try_imu_init()).
                ESP_LOGE(TAG, "profile %d: %s", (int)req, esp_err_to_name(perr));
            }
            profile_switched();
            last_data = app_now_ms();
            wake = xTaskGetTickCount();   // imu_set_profile blocked ~180 ms
            continue;
        }

        size_t n = 0;
        imu_raw_info_t raw;
        esp_err_t err = imu_read_fifo_ex(s_buf, FIFO_BATCH, &n, &raw);
        if (err == ESP_OK && n > 0) {
            last_data = now;
        } else if ((uint32_t)(now - last_data) >= IMU_LOST_MS) {
            // The re-init restores FIFO_CTRL, CTRL1 and the sensor enables.
            ESP_LOGE(TAG, "IMU lost: %s", err != ESP_OK ? esp_err_to_name(err) : "no new samples");
            imu_ok = false;
            last_retry = now;
            // Restart from the accelerometer after the gap.
            restart_pipeline();
            last_pub = now;
            publish_no_imu(now);
            recorder_note_imu(false);
            continue;
        }
        // The recorder's tap, right after the read: the raw frames of a RAW
        // profile, unfiltered (non-blocking copy into its ring).
        if (err == ESP_OK) {
            recorder_imu_block(&raw);
        }
        // An empty read (a catch-up pass after a late one, or the first
        // passes of a stuck IMU) has nothing new: republishing the old
        // orientation would stamp it fresh and feed the mount fit the same
        // up vector again. The lost check above has already seen it.
        if (err != ESP_OK || n == 0) {
            continue;
        }

        const imu_sample_t *smp = level_samples(n);
        float dt = imu_sample_period_s();
        level_reading_t reading = {0};
        quat_t q = quat_identity();
        bool still = false;
        float z[3];
        const float *zero = NULL;      // read with the reading

        lock();
        bool turning = process_batch_locked(smp, n, dt);
        bool ready = s_fusion_ready;
        if (ready) {
            q = s_fusion.q;
            still = fusion_is_still(&s_fusion);
            reading = level_compute(&s_level, q, now);
            zero = level_zero_of(&s_level, z);
            mount_add_locked(now);
        }
        unlock();

        vib_state_t was = vib_state(&s_vib);
        for (size_t i = 0; i < n; i++) {
            if (vib_add_sample(&s_vib, smp[i].accel_g, dt)) {
                ESP_LOGD(TAG, "vibration duty %d %%, turn rate max %.2f rad/s",
                         (int)(s_vib.last_duty * 100.0f + 0.5f), (double)s_win_max_rate);
                s_win_max_rate = 0.0f;
            }
        }
        vib_state_t vib = vib_state(&s_vib);
        if (vib != was) {
            ESP_LOGI(TAG, "vibration %s, duty %d %%", vib == VIB_RUNNING ? "running" : "quiet",
                     (int)(s_vib.last_duty * 100.0f + 0.5f));
            vib_note(vib == VIB_RUNNING);
        }
        if (turning) {
            app_note_activity();
        }
        if (ready) {
            app_state_set_motion(true, q, still, vib == VIB_RUNNING, zero, &reading, now);
        }
    }

    xSemaphoreGive(s_stopped);
    vTaskDelete(NULL);
}

esp_err_t sensor_start(const settings_t *s, bool imu_ok)
{
    if (s_task) {
        return ESP_ERR_INVALID_STATE;
    }
    s_lock = xSemaphoreCreateMutexStatic(&s_lock_buf);
    s_stopped = xSemaphoreCreateBinaryStatic(&s_stopped_buf);

    s_fusion_cfg = fusion_default_config();
    s_fusion_ready = false;
    vib_config_t vib_cfg = vib_default_config();
    vib_init(&s_vib, &vib_cfg);
    s_vib_running = false;
    // imu_init() before the task starts in the current profile (LEVEL at
    // boot); a request from now on is applied by the task.
    s_profile_req = (uint8_t)imu_get_profile();
    lpf_feed_reset(&s_lpf, profile_odr_hz());
    turn_config_t turn_cfg = turn_default_config();
    turn_init(&s_turn, &turn_cfg);
    s_win_max_rate = 0.0f;
    level_config_t cfg = settings_level_config(s);
    level_init(&s_level, &cfg);
    level_set_mode(&s_level, s->mode);   // an invalid stored mode stays WALL
    s_acal_valid = s->accel_cal_valid;
    s_acal = s->accel_cal;
    s_cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_result = APP_CAL_RESULT_NONE,
                               .last_kind = APP_CAL_IDLE};
    publish_cal_locked();

    BaseType_t ok = xTaskCreatePinnedToCore(sensor_task, "sensor", SENSOR_TASK_STACK,
                                            (void *)(uintptr_t)imu_ok, SENSOR_TASK_PRIO,
                                            &s_task, SENSOR_TASK_CORE);
    if (ok != pdPASS) {
        s_task = NULL;
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

void sensor_stop(void)
{
    if (!s_task || s_stop) {
        return;
    }
    s_stop = true;
    if (xSemaphoreTake(s_stopped, pdMS_TO_TICKS(STOP_TIMEOUT_MS)) != pdTRUE) {
        ESP_LOGE(TAG, "sensor task did not stop");
    }
}

void sensor_request_imu_profile(imu_profile_t p)
{
    if (imu_profile_info(p)) {
        s_profile_req = (uint8_t)p;
    }
}

bool sensor_gyro_bias(float out_rad_s[3])
{
    lock();
    bool ok = s_fusion_ready && fusion_bias_valid(&s_fusion);
    vec3_t b = s_fusion.gyro_bias;
    unlock();
    out_rad_s[0] = b.x;
    out_rad_s[1] = b.y;
    out_rad_s[2] = b.z;
    return ok;
}

// An invalid correction is not applied, so its values do not matter.
static bool same_accel_cal(bool valid_a, const accel_cal_t *a, bool valid_b, const accel_cal_t *b)
{
    return valid_a == valid_b && (!valid_a || accel_cal_equal(a, b));
}

void sensor_apply_settings(const settings_t *s)
{
    level_config_t cfg = settings_level_config(s);
    lock();
    if (level_set_config(&s_level, &cfg)) {
        ESP_LOGI(TAG, "drill axis changed, zero cleared");
    }
    // The zero angles were measured with the old accelerometer correction (a
    // bias change of 0.05 g tilts the fused gravity by about 3 degrees).
    if (s_level.zero_set &&
        !same_accel_cal(s_acal_valid, &s_acal, s->accel_cal_valid, &s->accel_cal)) {
        ESP_LOGI(TAG, "sensor calibration changed, zero cleared");
        level_clear_zero(&s_level);
    }
    level_mode_t from = s_level.mode;
    if (level_set_mode(&s_level, s->mode)) {
        ESP_LOGI(TAG, "mode %d -> %d, zero cleared", (int)from, (int)s->mode);
    }
    s_acal_valid = s->accel_cal_valid;
    s_acal = s->accel_cal;
    unlock();
}

bool sensor_zero(void)
{
    lock();
    bool ok = s_fusion_ready;   // only while the IMU runs
    if (ok) {
        level_zero(&s_level, s_fusion.q, app_now_ms());
    }
    unlock();
    return ok;
}

void sensor_clear_zero(void)
{
    lock();
    level_clear_zero(&s_level);
    unlock();
}

void sensor_cal_start(app_cal_kind_t kind)
{
    if (kind != APP_CAL_MOUNT && kind != APP_CAL_ACCEL) {
        return;
    }
    lock();
    level_mount_collector_reset(&s_mount, app_now_ms());
    accel_cal_collector_reset(&s_collector);
    s_cal.kind = kind;
    s_cal.samples = 0;
    s_cal.accel_coverage = 0;
    s_collecting = true;
    publish_cal_locked();
    unlock();
}

void sensor_cal_refused(void)
{
    lock();
    s_cal.refused++;
    publish_cal_locked();
    unlock();
}

void sensor_cal_cancel(void)
{
    lock();
    if (s_cal.kind != APP_CAL_IDLE) {
        // last_result -1: a screen still waiting must not show an older
        // result of the same kind as if it were this one.
        s_cal.last_kind = s_cal.kind;
        s_cal.last_result = APP_CAL_RESULT_NONE;
        s_cal.kind = APP_CAL_IDLE;
        s_collecting = false;
        publish_cal_locked();
    }
    unlock();
}

bool sensor_mount_cal_finish(level_cal_result_t *result, vec3_t *drill_axis)
{
    lock();
    bool ok = s_collecting && s_cal.kind == APP_CAL_MOUNT;
    level_mount_cal_t data = s_mount.fit;
    if (ok) {
        s_collecting = false;
        publish_cal_locked();   // final sample count
    }
    unlock();
    if (ok) {
        *result = level_mount_cal_solve(&data, SETTINGS_MAX_MOUNT_OFFSET_DEG, drill_axis);
    }
    return ok;
}

bool sensor_accel_cal_finish(accel_cal_result_t *result, accel_cal_t *cal)
{
    lock();
    bool ok = s_collecting && s_cal.kind == APP_CAL_ACCEL;
    accel_cal_collector_t data = s_collector;
    if (ok) {
        s_collecting = false;
    }
    unlock();
    if (ok) {
        *result = accel_cal_solve(&data, cal);
    }
    return ok;
}

void sensor_cal_done(app_cal_kind_t kind, int result)
{
    lock();
    // A new start in the meantime owns the status now.
    if (s_cal.kind == kind && !s_collecting) {
        s_cal.kind = APP_CAL_IDLE;
        s_cal.last_kind = kind;
        s_cal.last_result = (int8_t)result;
        publish_cal_locked();
    }
    unlock();
}
