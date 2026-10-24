#pragma once

// Sensor task: QMI8658 FIFO -> accel calibration -> fusion -> level reading,
// polled and published to app_state at 50 Hz. Also collects the mount and
// accel calibration data (level_mount_collector_t, accel_cal_collector_t).
//
// The task owns fusion_t, level_t and the calibration collectors. Other
// tasks (the control task) reach them only through the functions below. They
// take the sensor mutex, which the task holds only while it runs one batch of
// samples through the filter (tens of microseconds, never during I2C), so the
// calls are short and race-free. FreeRTOS mutexes inherit priority, so a
// low-priority caller cannot stall the sensor task for long.

#include <stdbool.h>
#include "accel_cal.h"
#include "app_state.h"
#include "esp_err.h"
#include "level.h"
#include "settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// Starts the task. imu_ok tells whether imu_init() succeeded; if not, the
// task keeps retrying in the background and reports imu_ok = false. An IMU
// that delivers no samples for about 200 ms counts as lost and is
// re-initialised the same way.
esp_err_t sensor_start(const settings_t *s, bool imu_ok);

// Stops the task before deep sleep or power-off, so it no longer touches the
// IMU (it would otherwise re-initialise it out of wake-on-motion mode).
// Blocks until the task has stopped (at most about a second).
void sensor_stop(void);

// Level config, mode and accel calibration from the settings. The zero is
// kept unless the drill axis (level_set_config()) or the accel calibration
// changed, or the mode moved between FLOOR and WALL/ANGLE (level_set_mode()).
// The only way the mode changes: the control task stores it and applies the
// reloaded settings.
void sensor_apply_settings(const settings_t *s);

// False if there is no orientation yet (IMU missing or not started).
bool sensor_zero(void);
void sensor_clear_zero(void);

// Starts collecting for kind (APP_CAL_MOUNT or APP_CAL_ACCEL); restarts if a
// calibration is already running. Mount samples from the first second after
// the start and the last half second before the finish are left out.
void sensor_cal_start(app_cal_kind_t kind);
// Abandons the running calibration (published with APP_CAL_RESULT_NONE).
void sensor_cal_cancel(void);

// Stop collecting and solve. Return false if no calibration of that kind is
// collecting. The published status stays "active" until sensor_cal_done(),
// so the UI never sees a result before it was stored.
bool sensor_mount_cal_finish(level_cal_result_t *result, vec3_t *drill_axis);
bool sensor_accel_cal_finish(accel_cal_result_t *result, accel_cal_t *cal);
// Publishes the outcome of a finish: kind -> idle, last_kind/last_result.
void sensor_cal_done(app_cal_kind_t kind, int result);

#ifdef __cplusplus
}
#endif
