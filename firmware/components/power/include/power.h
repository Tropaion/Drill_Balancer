#pragma once

// Applies power states to the hardware: brightness, deep sleep with IMU
// wake-on-motion, AXP2101 power-off.

#include <stdint.h>
#include "esp_err.h"
#include "power_fsm.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    POWER_BOOT_COLD = 0,        // power-on or reset
    POWER_BOOT_MOTION,          // woke from deep sleep by motion (ext1)
    POWER_BOOT_OFF_TIMER,       // deep sleep timer: time to power off
} power_boot_reason_t;

// Call early in app_main (before the display starts). On POWER_BOOT_OFF_TIMER
// the caller should power off right away with power_off().
power_boot_reason_t power_boot_reason(void);

// Brightness for ACTIVE / DIM (percent), via the BSP.
esp_err_t power_set_brightness(uint8_t percent);

// Display off, PMU rails down, IMU wake-on-motion, then ESP32 deep sleep.
// Wakes on motion (ext1, IMU_WAKE_GPIO) or after off_after_s (timer; 0 =
// no timer). Does not return.
void power_enter_deep_sleep(uint32_t off_after_s);

// AXP2101 off. Does not return (falls back to deep sleep without timer if
// the PMU does not respond).
void power_off(void);

#ifdef __cplusplus
}
#endif
