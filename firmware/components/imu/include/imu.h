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

// Accel +-4 g, gyro +-512 dps, both at the same ODR (about 470 Hz) with the
// on-chip low-pass filter on. FIFO enabled for accel + gyro.
esp_err_t imu_init(i2c_master_bus_handle_t bus);

// Sample period of the configured ODR (seconds), for the fusion dt.
float imu_sample_period_s(void);

// Drains up to max samples from the FIFO, oldest first. *n_out may be 0.
esp_err_t imu_read_fifo(imu_sample_t *out, size_t max, size_t *n_out);

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
