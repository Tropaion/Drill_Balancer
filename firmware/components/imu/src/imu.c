// QMI8658 driver, register level. Section and table numbers refer to the
// QMI8658A datasheet Rev A (QST, document 13-52-25). Register usage was
// cross-checked against lewisxhe/SensorLib (SensorQMI8658) and the
// waveshare/qmi8658 2.0.2 component.

#include <math.h>
#include <string.h>
#include "imu.h"
#include "imu_frame.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "imu";

#define IMU_I2C_ADDR        0x6B
#define IMU_I2C_HZ          400000
#define IMU_I2C_TIMEOUT_MS  50
// A full FIFO is 1536 bytes, about 35 ms at 400 kHz.
#define IMU_FIFO_TIMEOUT_MS 100

// Register map (Table 19).
#define REG_WHO_AM_I      0x00
#define REG_CTRL1         0x02
#define REG_CTRL2         0x03
#define REG_CTRL3         0x04
#define REG_CTRL5         0x06
#define REG_CTRL7         0x08
#define REG_CTRL8         0x09
#define REG_CTRL9         0x0A
#define REG_CAL1_L        0x0B
#define REG_CAL1_H        0x0C
#define REG_FIFO_CTRL     0x14
#define REG_FIFO_SMPL_CNT 0x15    // followed by FIFO_STATUS (0x16)
#define REG_FIFO_DATA     0x17
#define REG_STATUSINT     0x2D
#define REG_TIMESTAMP_L   0x30    // L, M, H
#define REG_AX_L          0x35    // AX..GZ, 12 bytes
#define REG_RESET_RESULT  0x4D    // dQY_L: 0x80 after a good reset (7.4)
#define REG_RESET         0x60

#define WHO_AM_I_VALUE    0x05
// 5.9 and both reference drivers use 0xB0 (7.4 says 0x0B, a typo).
#define RESET_COMMAND     0xB0
#define RESET_OK          0x80
#define RESET_TIME_MS     20      // max 15 ms (7.3 t0, 7.4)

// CTRL1 (Table 22): ADDR_AI = auto increment, BE left at its reset value 1
// as both reference drivers do, which still read little-endian data.
// TODO(hw): confirm the byte order: at rest screen up the raw Z word must be
// about +8192 (0x2000), not byte-swapped (0x0020).
#define CTRL1_ADDR_AI     0x40
#define CTRL1_BE          0x20
#define CTRL1_INT2_EN     0x10    // INT2 push-pull instead of high-Z (6)
#define CTRL1_RUN         (CTRL1_ADDR_AI | CTRL1_BE)
// FIFO_DATA must be burst-read without auto increment, or the address walks
// on past 0x17 (16.1). TODO(hw): check that such a burst returns consecutive
// FIFO bytes (decoded FIFO samples read ~1 g at rest, like imu_read()).
#define CTRL1_FIFO_READ   CTRL1_BE

// CTRL2, CTRL3 and CTRL5 (range, ODR, LPF) come from the profile table in
// imu_frame.c.

#define CTRL7_AEN         0x01
#define CTRL7_GEN         0x02
// Bit 7: CTRL9 handshake through STATUSINT.bit7 instead of INT1, which only
// reaches the TCA9554 on this board (6.2). Motion engines off.
#define CTRL8_RUN         0x80

// CTRL9 commands (Table 28).
#define CMD_ACK           0x00
#define CMD_RST_FIFO      0x04
#define CMD_REQ_FIFO      0x05
#define CMD_WRITE_WOM     0x08
#define STATUSINT_CMD_DONE 0x80

// FIFO_CTRL (Table 23): stream mode, 128 samples (IMU_FIFO_MAX_FRAMES).
#define FIFO_CTRL_RUN     ((0x3 << 2) | 0x2)
#define FIFO_CTRL_BYPASS  0x00

// Gyro turn-on t1 = 150 ms plus filter settling t5 = 3/ODR (7.3).
#define GYRO_STARTUP_MS   160
// Sensors-off transition t6 is at most 2/ODR (7.3).
#define SENSOR_OFF_MS     5

// Wake on Motion (12): accel low-power 21 Hz (aODR 1101, about 42 uA,
// Table 15), +-4 g. CAL1_H[7:6] = 01: INT2 with initial value 0, [5:0]
// blanking 16 samples (~0.75 s) to swallow start-up transients.
// TODO(hw): check that a light tap wakes it and a drill lying on a bench
// does not, with power.c's threshold.
#define CTRL2_WOM         ((0x1 << 4) | 0xD)
#define CAL1_H_WOM        ((0x1 << 6) | 16)

// The datasheet gives no CTRL9 execution time; SensorLib allows 1 s.
#define CTRL9_FAST_TIMEOUT_US  20000
#define CTRL9_SLOW_TIMEOUT_US  200000

#define SELF_TEST_READS   8
#define SELF_TEST_TOL_G   0.15f

static i2c_master_dev_handle_t s_dev;
static SemaphoreHandle_t s_lock;
static StaticSemaphore_t s_lock_buf;
static bool s_running;            // 6DOF + FIFO configured by imu_init()
static imu_profile_t s_profile = IMU_PROFILE_LEVEL;
// Leaving FIFO read mode failed: the FIFO does not fill (and its count stays
// 0) until FIFO_CTRL and CTRL1 are written again, which the next read does
// first, whatever the count says.
static bool s_restore_pending;
static imu_gap_t s_gap;
static imu_rate_t s_rate;
static imu_sample_t s_last;       // last sample handed out, for gap filling
static bool s_have_last;
static uint32_t s_filled;         // synthesised samples, for the log
static uint32_t s_epoch;          // configure() runs (imu_raw_info_t.epoch)
static uint8_t s_fifo_buf[IMU_FIFO_MAX_FRAMES * IMU_FRAME_BYTES];

static esp_err_t rd(uint8_t reg, uint8_t *buf, size_t len)
{
    return i2c_master_transmit_receive(s_dev, &reg, 1, buf, len, IMU_I2C_TIMEOUT_MS);
}

// Configuration registers take single writes only (16.1).
static esp_err_t wr(uint8_t reg, uint8_t val)
{
    const uint8_t buf[2] = {reg, val};
    return i2c_master_transmit(s_dev, buf, sizeof(buf), IMU_I2C_TIMEOUT_MS);
}

static void lock(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
}

static void unlock(void)
{
    xSemaphoreGive(s_lock);
}

// Busy polls when `fast`: the FIFO drops samples for as long as it is in
// read mode, and one poll is only ~50 us on the bus.
static esp_err_t wait_cmd_done(bool done, bool fast)
{
    int64_t t0 = esp_timer_get_time();
    for (;;) {
        uint8_t st;
        ESP_RETURN_ON_ERROR(rd(REG_STATUSINT, &st, 1), TAG, "STATUSINT read");
        if (((st & STATUSINT_CMD_DONE) != 0) == done) {
            return ESP_OK;
        }
        if (esp_timer_get_time() - t0 > (fast ? CTRL9_FAST_TIMEOUT_US : CTRL9_SLOW_TIMEOUT_US)) {
            return ESP_ERR_TIMEOUT;
        }
        if (!fast) {
            vTaskDelay(1);
        }
    }
}

// CTRL9 protocol (5.10.1, 5.10.4, 5.10.5): command, wait for CmdDone, ACK,
// wait for CmdDone to clear so the next command starts clean.
static esp_err_t ctrl9(uint8_t cmd, bool fast)
{
    ESP_RETURN_ON_ERROR(wr(REG_CTRL9, cmd), TAG, "CTRL9 write");
    esp_err_t err = wait_cmd_done(true, fast);
    // Acknowledge even after a timeout so the protocol does not stay open.
    esp_err_t ack = wr(REG_CTRL9, CMD_ACK);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "CTRL9 0x%02x: no CmdDone", cmd);
        return err;
    }
    ESP_RETURN_ON_ERROR(ack, TAG, "CTRL9 ack");
    return wait_cmd_done(false, fast);
}

static const imu_profile_info_t *profile(void)
{
    return imu_profile_info(s_profile);
}

static float odr_hz(void)
{
    return (float)profile()->odr_mhz * 1e-3f;
}

static esp_err_t configure(void)
{
    const imu_profile_info_t *p = profile();
    uint8_t v = 0;
    ESP_RETURN_ON_ERROR(rd(REG_WHO_AM_I, &v, 1), TAG, "no answer at 0x%02x", IMU_I2C_ADDR);
    if (v != WHO_AM_I_VALUE) {
        ESP_LOGE(TAG, "WHO_AM_I 0x%02x, expected 0x%02x", v, WHO_AM_I_VALUE);
        return ESP_ERR_NOT_FOUND;
    }

    // Also leaves wake-on-motion after a deep-sleep wake: the IMU stayed
    // powered. A reset restores every UI register (7.4), which 12.6 would
    // otherwise need a CTRL9 sequence for.
    ESP_RETURN_ON_ERROR(wr(REG_RESET, RESET_COMMAND), TAG, "reset");
    vTaskDelay(pdMS_TO_TICKS(RESET_TIME_MS));
    ESP_RETURN_ON_ERROR(rd(REG_RESET_RESULT, &v, 1), TAG, "reset result");
    if (v != RESET_OK) {
        ESP_LOGW(TAG, "reset result 0x%02x, expected 0x%02x", v, RESET_OK);
    }

    ESP_RETURN_ON_ERROR(wr(REG_CTRL1, CTRL1_RUN), TAG, "CTRL1");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL2, p->ctrl2), TAG, "CTRL2");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL3, p->ctrl3), TAG, "CTRL3");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL5, p->ctrl5), TAG, "CTRL5");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL8, CTRL8_RUN), TAG, "CTRL8");
    // FIFO set up with the sensors off, as in SensorLib's configFifo().
    ESP_RETURN_ON_ERROR(wr(REG_FIFO_CTRL, FIFO_CTRL_RUN), TAG, "FIFO_CTRL");
    ESP_RETURN_ON_ERROR(ctrl9(CMD_RST_FIFO, false), TAG, "FIFO reset");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL7, CTRL7_AEN | CTRL7_GEN), TAG, "CTRL7");

    vTaskDelay(pdMS_TO_TICKS(GYRO_STARTUP_MS));
    // Drop what the FIFO collected while the gyro was starting.
    ESP_RETURN_ON_ERROR(ctrl9(CMD_RST_FIFO, false), TAG, "FIFO reset");

    s_gap = (imu_gap_t){0};
    s_restore_pending = false;
    // A period measured before stays (scaled to this ODR): FIFO losses are
    // filled at once, not only after a new 2 s baseline.
    imu_rate_restart(&s_rate, 1.0f / odr_hz());
    s_have_last = false;
    s_filled = 0;
    s_epoch++;
    s_running = true;
    return ESP_OK;
}

esp_err_t imu_init(i2c_master_bus_handle_t bus, imu_profile_t p)
{
    ESP_RETURN_ON_FALSE(bus, ESP_ERR_INVALID_ARG, TAG, "no I2C bus");
    ESP_RETURN_ON_FALSE(imu_profile_info(p), ESP_ERR_INVALID_ARG, TAG, "profile %d", (int)p);
    ESP_RETURN_ON_FALSE(imu_axis_map_valid(imu_axis_map()), ESP_ERR_INVALID_STATE, TAG,
                        "the axis map is not a rotation");
    if (!s_lock) {
        s_lock = xSemaphoreCreateMutexStatic(&s_lock_buf);
    }
    if (!s_dev) {
        const i2c_device_config_t cfg = {
            .dev_addr_length = I2C_ADDR_BIT_LEN_7,
            .device_address = IMU_I2C_ADDR,
            .scl_speed_hz = IMU_I2C_HZ,
        };
        ESP_RETURN_ON_ERROR(i2c_master_bus_add_device(bus, &cfg, &s_dev), TAG, "add device");
    }

    lock();
    s_profile = p;
    s_running = false;
    esp_err_t err = configure();
    unlock();
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "QMI8658 6DOF %.1f Hz, +-%u g, +-512 dps, profile %s, FIFO stream",
                 (double)odr_hz(), 32768u / profile()->accel_lsb_per_g, profile()->name);
    }
    return err;
}

esp_err_t imu_set_profile(imu_profile_t p)
{
    ESP_RETURN_ON_FALSE(imu_profile_info(p), ESP_ERR_INVALID_ARG, TAG, "profile %d", (int)p);
    if (!s_dev) {
        return ESP_ERR_INVALID_STATE;
    }
    lock();
    // Already in effect: no reset, so the samples and the bookkeeping go on
    // (a LEVEL take asks for LEVEL at its start and end).
    bool same = s_running && s_profile == p;
    esp_err_t err = ESP_OK;
    if (!same) {
        s_profile = p;
        s_running = false;
        err = configure();
    }
    unlock();
    if (err == ESP_OK && !same) {
        ESP_LOGI(TAG, "profile %s, %.1f Hz", profile()->name, (double)odr_hz());
    }
    return err;
}

imu_profile_t imu_get_profile(void)
{
    return s_profile;
}

float imu_sample_period_s(void)
{
    // A float load is atomic; the estimate only moves slowly.
    return s_running ? s_rate.period_s : 1.0f / odr_hz();
}

// TIMESTAMP and FIFO count from the same instant: a sample landing between
// the reads would shift the gap bookkeeping by one.
static esp_err_t read_level(uint32_t *ts, size_t *frames, int64_t *now_us)
{
    uint8_t t1[3], cnt[2], t2[3];
    for (int attempt = 0; attempt < 4; attempt++) {
        ESP_RETURN_ON_ERROR(rd(REG_TIMESTAMP_L, t1, 3), TAG, "timestamp");
        ESP_RETURN_ON_ERROR(rd(REG_FIFO_SMPL_CNT, cnt, 2), TAG, "FIFO count");
        *now_us = esp_timer_get_time();
        ESP_RETURN_ON_ERROR(rd(REG_TIMESTAMP_L, t2, 3), TAG, "timestamp");
        if (memcmp(t1, t2, 3) == 0) {
            break;
        }
    }
    // After four misses take the last values; the gap logic tolerates +-1.
    *ts = (uint32_t)t2[0] | ((uint32_t)t2[1] << 8) | ((uint32_t)t2[2] << 16);
    *frames = imu_fifo_frames(cnt[0], cnt[1]);
    return ESP_OK;
}

// FIFO read sequence of 8.8 / 5.10.6.3. Everything between REQ_FIFO and
// clearing FIFO_RD_MODE is time in which new samples are lost, so the CTRL1
// switches happen outside it.
static esp_err_t read_fifo_bytes(uint8_t *buf, size_t len)
{
    ESP_RETURN_ON_ERROR(wr(REG_CTRL1, CTRL1_FIFO_READ), TAG, "CTRL1");
    esp_err_t err = ctrl9(CMD_REQ_FIFO, true);
    if (err == ESP_OK) {
        const uint8_t reg = REG_FIFO_DATA;
        err = i2c_master_transmit_receive(s_dev, &reg, 1, buf, len, IMU_FIFO_TIMEOUT_MS);
    }
    // Leave read mode whatever happened, or the FIFO stops filling. Samples
    // not read stay queued for the next call.
    // TODO(hw): verify that a partial read keeps the rest (only happens when
    // the caller's buffer is smaller than the FIFO content).
    esp_err_t err2 = wr(REG_FIFO_CTRL, FIFO_CTRL_RUN);
    esp_err_t err3 = wr(REG_CTRL1, CTRL1_RUN);
    s_restore_pending = err2 != ESP_OK || err3 != ESP_OK;
    if (err == ESP_OK) {
        err = err2 != ESP_OK ? err2 : err3;
    }
    return err;
}

// Retries what read_fifo_bytes() could not finish, before the FIFO level is
// read (without ADDR_AI the multi-byte level reads would return garbage).
static esp_err_t restore_run_mode(void)
{
    if (!s_restore_pending) {
        return ESP_OK;
    }
    ESP_RETURN_ON_ERROR(wr(REG_FIFO_CTRL, FIFO_CTRL_RUN), TAG, "FIFO_CTRL restore");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL1, CTRL1_RUN), TAG, "CTRL1 restore");
    s_restore_pending = false;
    ESP_LOGW(TAG, "FIFO read mode left on retry");
    return ESP_OK;
}

// Samples lost while the FIFO was in read mode are replaced by interpolated
// ones, so every output sample is one ODR period after the previous and the
// fusion can integrate with a fixed dt. This needs TIMESTAMP to count one per
// 6DOF sample and to keep counting in read mode, which the ODR estimate
// confirms before any filling happens.
// TODO(hw): log s_filled against the read count on the board; expect about
// 10% at 448 Hz.
static esp_err_t read_fifo_locked(imu_sample_t *out, size_t max, size_t *n_out,
                                  imu_raw_info_t *raw)
{
    uint32_t ts;
    size_t frames;
    int64_t now_us;
    ESP_RETURN_ON_ERROR(restore_run_mode(), TAG, "FIFO run mode");
    ESP_RETURN_ON_ERROR(read_level(&ts, &frames, &now_us), TAG, "FIFO level");
    imu_rate_update(&s_rate, ts, now_us);

    imu_gap_plan_t plan = imu_gap_plan(&s_gap, ts, frames, s_rate.measured, max);
    size_t n = plan.n_read;
    size_t fill = plan.n_fill;
    if (n > 0) {
        ESP_RETURN_ON_ERROR(read_fifo_bytes(s_fifo_buf, n * IMU_FRAME_BYTES), TAG, "FIFO read");
    }
    if (raw) {
        imu_raw_runs(&plan, raw->run);
        raw->frames = s_fifo_buf;
        raw->n = (uint16_t)n;
        raw->map = imu_axis_map();
        raw->chip_ts = ts;
        raw->host_us = now_us;
        raw->epoch = s_epoch;
    }

    const imu_profile_info_t *p = profile();
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        imu_sample_t s;
        imu_decode_frame(imu_axis_map(), p, &s_fifo_buf[i * IMU_FRAME_BYTES], &s);
        if (i == plan.insert_at && fill > 0) {
            imu_fill_gap(s_have_last ? &s_last : &s, &s, fill, &out[o]);
            o += fill;
            s_filled += (uint32_t)fill;
        }
        out[o++] = s;
    }
    if (o > 0) {
        s_last = out[o - 1];
        s_have_last = true;
    }
    imu_gap_commit(&s_gap, ts, frames, n);
    *n_out = o;
    return ESP_OK;
}

esp_err_t imu_read_fifo_ex(imu_sample_t *out, size_t max, size_t *n_out, imu_raw_info_t *raw)
{
    ESP_RETURN_ON_FALSE(out && n_out, ESP_ERR_INVALID_ARG, TAG, "null argument");
    *n_out = 0;
    if (raw) {
        *raw = (imu_raw_info_t){0};
    }
    if (!s_running) {
        return ESP_ERR_INVALID_STATE;
    }
    if (max == 0) {
        return ESP_OK;
    }
    lock();
    esp_err_t err = s_running ? read_fifo_locked(out, max, n_out, raw) : ESP_ERR_INVALID_STATE;
    unlock();
    return err;
}

esp_err_t imu_read_fifo(imu_sample_t *out, size_t max, size_t *n_out)
{
    return imu_read_fifo_ex(out, max, n_out, NULL);
}

// The data registers keep updating in FIFO mode (only DRDY is disabled, 6.2).
// TODO(hw): confirm with the FIFO running. Without SyncSample a sample can
// update mid-read (6.3); the 12-byte burst takes ~0.3 ms of the 2.2 ms period.
esp_err_t imu_read(imu_sample_t *out)
{
    ESP_RETURN_ON_FALSE(out, ESP_ERR_INVALID_ARG, TAG, "null argument");
    if (!s_running) {
        return ESP_ERR_INVALID_STATE;
    }
    uint8_t buf[IMU_FRAME_BYTES];
    lock();
    esp_err_t err = rd(REG_AX_L, buf, sizeof(buf));
    unlock();
    ESP_RETURN_ON_ERROR(err, TAG, "data read");
    imu_decode_frame(imu_axis_map(), profile(), buf, out);
    return ESP_OK;
}

bool imu_self_test(void)
{
    if (!s_dev || !s_running) {
        ESP_LOGE(TAG, "self-test: not initialised");
        return false;
    }
    uint8_t id = 0;
    lock();
    esp_err_t err = rd(REG_WHO_AM_I, &id, 1);
    unlock();
    if (err != ESP_OK || id != WHO_AM_I_VALUE) {
        ESP_LOGE(TAG, "self-test: WHO_AM_I 0x%02x (%s)", id, esp_err_to_name(err));
        return false;
    }

    // Averaged so one noisy or torn sample cannot fail the check.
    vec3_t sum = {0.0f, 0.0f, 0.0f};
    int ok = 0;
    for (int i = 0; i < SELF_TEST_READS; i++) {
        imu_sample_t s;
        if (imu_read(&s) == ESP_OK) {
            sum = vec3_add(sum, s.accel_g);
            ok++;
        }
        vTaskDelay(pdMS_TO_TICKS(3));
    }
    if (ok == 0) {
        ESP_LOGE(TAG, "self-test: no samples");
        return false;
    }
    float g = vec3_norm(vec3_scale(sum, 1.0f / (float)ok));
    bool pass = fabsf(g - 1.0f) < SELF_TEST_TOL_G;
    if (pass) {
        ESP_LOGI(TAG, "self-test passed, |a| = %.3f g", (double)g);
    } else {
        ESP_LOGE(TAG, "self-test failed, |a| = %.3f g", (double)g);
    }
    return pass;
}

// Sequence of 12.5 (Figure 25).
static esp_err_t enable_wom_locked(uint8_t threshold_mg)
{
    if (s_filled > 0) {
        ESP_LOGI(TAG, "%lu lost FIFO samples were interpolated", (unsigned long)s_filled);
    }
    ESP_RETURN_ON_ERROR(wr(REG_CTRL7, 0), TAG, "sensors off");
    vTaskDelay(pdMS_TO_TICKS(SENSOR_OFF_MS));
    // Bypass, so INT2 carries nothing but the WoM signal.
    ESP_RETURN_ON_ERROR(wr(REG_FIFO_CTRL, FIFO_CTRL_BYPASS), TAG, "FIFO off");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL2, CTRL2_WOM), TAG, "CTRL2");
    ESP_RETURN_ON_ERROR(wr(REG_CAL1_L, threshold_mg), TAG, "CAL1_L");
    ESP_RETURN_ON_ERROR(wr(REG_CAL1_H, CAL1_H_WOM), TAG, "CAL1_H");
    ESP_RETURN_ON_ERROR(ctrl9(CMD_WRITE_WOM, false), TAG, "WoM setting");
    // Push-pull from here on, so GPIO21 sees a defined level in deep sleep.
    ESP_RETURN_ON_ERROR(wr(REG_CTRL1, CTRL1_RUN | CTRL1_INT2_EN), TAG, "CTRL1");
    ESP_RETURN_ON_ERROR(wr(REG_CTRL7, CTRL7_AEN), TAG, "accel on");
    return ESP_OK;
}

esp_err_t imu_enable_wake_on_motion(uint16_t threshold_mg)
{
    if (!s_dev) {
        return ESP_ERR_INVALID_STATE;
    }
    // CAL1_L is 1 mg per LSB and 0 would disable WoM (Table 39).
    uint8_t thr = threshold_mg == 0 ? 1 : threshold_mg > 255 ? 255 : (uint8_t)threshold_mg;
    if (thr != threshold_mg) {
        ESP_LOGW(TAG, "WoM threshold %u mg clamped to %u mg", (unsigned)threshold_mg,
                 (unsigned)thr);
    }
    lock();
    s_running = false;
    esp_err_t err = enable_wom_locked(thr);
    unlock();
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "wake on motion: %u mg, INT2 active %s", (unsigned)thr,
                 IMU_WAKE_LEVEL ? "high" : "low");
    }
    return err;
}
