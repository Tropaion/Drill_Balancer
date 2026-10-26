#pragma once

// QMI8658 6-axis IMU at I2C 0x6B on the BSP's shared bus.
//
// All vectors are in the board frame used by the level code: X right, Y up
// along the screen, Z out of the screen towards the user. The chip-to-board
// axis mapping lives in imu.c (IMU_AXIS_MAP) and must be checked on the real
// board.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "driver/i2c_master.h"
#include "esp_err.h"
#include "level_math.h"

#ifdef __cplusplus
extern "C" {
#endif

#define IMU_WAKE_GPIO 21   // QMI8658 INT2 -> ESP32-S3, used for ext1 wake

// INT2 level that signals motion after imu_enable_wake_on_motion(); configure
// ext1 as ESP_EXT1_WAKEUP_ANY_HIGH. WoM drives INT2 push-pull starting at the
// chosen initial value (0 here) and toggles it on every WoM event, so the
// first motion raises it; reading STATUS1 resets it to 0 (QMI8658A datasheet
// 12.2, 12.4). It stays high for at least one low-power sample period (about
// 48 ms) until the next event toggles it back, which an ext1 level wake
// catches. imu_init() leaves WoM through a soft reset.
#define IMU_WAKE_LEVEL 1

typedef struct {
    vec3_t accel_g;
    vec3_t gyro_rad_s;
} imu_sample_t;

// One 6DOF sample: AX AY AZ GX GY GZ, 16-bit little-endian each, chip axes.
// Same layout in the data registers (0x35..0x40) and in the FIFO
// (datasheet 8.9).
#define IMU_FRAME_BYTES 12

// Board axis = sign * chip axis[src].
typedef struct {
    uint8_t src;    // 0 = chip X, 1 = chip Y, 2 = chip Z
    int8_t sign;    // +1 or -1
} imu_axis_t;

typedef struct {
    imu_axis_t x, y, z;
} imu_axis_map_t;

// Sensor configurations. LEVEL is the level pipeline's (on-chip LPF on);
// the RAW ones are for training-data recordings, with both LPFs off so the
// motor vibration (147-233 Hz) stays in the data.
typedef enum {
    IMU_PROFILE_LEVEL = 0,
    IMU_PROFILE_RAW448,         // 448.4 Hz, +-4 g
    IMU_PROFILE_RAW448_16G,     // 448.4 Hz, +-16 g (hammer mode)
    IMU_PROFILE_RAW897,         // 896.8 Hz, +-4 g, experimental
    IMU_PROFILE_COUNT,
} imu_profile_t;

typedef struct {
    const char *name;           // the dataset's meta.imu.profile id
    uint8_t ctrl2;              // aFS | aODR
    uint8_t ctrl3;              // gFS | gODR
    uint8_t ctrl5;              // LPF modes and enables
    uint32_t odr_mhz;           // nominal 6DOF ODR in mHz
    uint16_t accel_lsb_per_g;
    uint16_t gyro_lsb_per_dps;
    float accel_scale;          // g per LSB, 1 / accel_lsb_per_g
    float gyro_scale;           // rad/s per LSB, from gyro_lsb_per_dps
} imu_profile_info_t;

// Register values and scales of a profile; NULL for an unknown one. Pure
// (imu_frame.c).
const imu_profile_info_t *imu_profile_info(imu_profile_t p);

// Chip frame -> int16 counts in the board frame (AX AY AZ GX GY GZ). The
// negation of -32768 saturates to 32767. Pure (imu_frame.c).
void imu_map_frame_i16(const imu_axis_map_t *map, const uint8_t frame[IMU_FRAME_BYTES],
                       int16_t out[6]);

// Configures the chip for profile p (LEVEL at boot; the re-init after a
// lost IMU passes the one the sensor task wants), FIFO enabled for accel +
// gyro. Gyro +-512 dps in every profile.
esp_err_t imu_init(i2c_master_bus_handle_t bus, imu_profile_t p);

// Reconfigures the chip for p: a reset plus the gyro start-up, about 180 ms
// without samples. The gap and ODR bookkeeping start over (a new
// imu_raw_info_t.epoch), so the caller resets whatever integrates the
// samples. Nothing happens (ESP_OK) when p is in effect and the chip runs,
// and the caller keeps them: it compares p with imu_get_profile() first.
// imu_get_profile() is p from this call on, also when the chip does not
// answer (no I2C device yet: ESP_ERR_INVALID_STATE, nothing changes).
// imu_init() always reconfigures, in the profile it is given.
esp_err_t imu_set_profile(imu_profile_t p);
imu_profile_t imu_get_profile(void);

// The chip-to-board axis map (IMU_AXIS_MAP in imu.c), e.g. for the
// recorder's SESSION record.
const imu_axis_map_t *imu_axis_map(void);

// Sample period of the configured ODR (seconds), for the fusion dt.
float imu_sample_period_s(void);

// Drains up to max samples from the FIFO, oldest first. *n_out may be 0.
esp_err_t imu_read_fifo(imu_sample_t *out, size_t max, size_t *n_out);

// The FIFO frames one read returned, before decoding and gap filling, with
// their chip timestamps (24-bit TIMESTAMP, one count per 6DOF sample).
// Frames within a run are consecutive samples. From the previous read to
// run[0] and from run[0] to run[1], a timestamp jump is the number of
// samples the FIFO lost in read mode, unless the run is discontinuous: the
// driver resynchronised (first read, a stall or FIFO overflow, an
// inconsistent count) and the timestamp is only its best guess.
typedef struct {
    uint16_t first;             // index into frames
    uint16_t count;             // 0: unused
    uint32_t ts_first;          // TIMESTAMP of frames[first] (24 bit)
    bool discontinuous;
} imu_raw_run_t;

typedef struct imu_raw_info {
    const uint8_t *frames;      // n chip frames, valid until the next read
    uint16_t n;
    imu_raw_run_t run[2];       // cover frames[0..n) in order
    const imu_axis_map_t *map;  // for imu_map_frame_i16()
    uint32_t chip_ts;           // TIMESTAMP read together with the FIFO level
    int64_t host_us;            // esp_timer at that read
    uint32_t epoch;             // configure() runs since boot: when it changes
                                // the chip was reset and TIMESTAMP counts anew,
                                // unrelated to the earlier reads
                                // TODO(hw): check that the reset restarts it
} imu_raw_info_t;

// imu_read_fifo() that also returns the raw frames when raw is not NULL
// (raw->n is 0 when nothing was read).
esp_err_t imu_read_fifo_ex(imu_sample_t *out, size_t max, size_t *n_out, imu_raw_info_t *raw);

// Reads the latest sample directly (no FIFO), e.g. for self-test.
esp_err_t imu_read(imu_sample_t *out);

// Self-test for the OTA check: WHO_AM_I matches and a sample has |a| ~ 1 g.
bool imu_self_test(void);

// Before deep sleep: gyro off, accel in low-power mode, wake-on-motion
// interrupt on INT2 (active level matching the ext1 wake configuration
// documented in imu.c). threshold_mg is the motion threshold.
esp_err_t imu_enable_wake_on_motion(uint16_t threshold_mg);

#ifdef __cplusplus
}
#endif
