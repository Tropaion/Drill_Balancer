#pragma once

// LVGL 9 user interface for the 466x466 round AMOLED. Black background.
//
// The UI only reads app_state_get() snapshots (from its own lv_timer, about
// 15 Hz) and posts app commands / edits settings via settings_get/update.
// It never calls sensor, PMU or BLE code.
//
// Screens:
// - Level: one view per mode (swipe left/right = APP_CMD_NEXT_MODE),
//   tap = APP_CMD_ZERO, long press = settings. Big numbers, colour by status
//   (green on target, amber near, white off), bubble for floor mode, yaw
//   bar with stale warning for wall mode, battery %, charging and BLE icons.
// - Settings: tolerance, target angle, brightness, auto-off times, BLE,
//   "Calibrate mount", "Calibrate sensor", clear zero, firmware version.
// - Calibration guides: mount (bit in a hole, Start, roll the drill +-45
//   deg, progress), sensor (six sides, coverage shown as six targets).
// - Overlays: BLE passkey while pairing, OTA progress, low battery.
// A touch that wakes a dimmed screen does nothing else, on any screen.

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

// Call once after bsp_display_start(), with the display lock NOT held.
esp_err_t ui_init(void);

// Stores a settings edit that is still waiting for the 1 s save debounce.
// Call before deep sleep or power-off (which take the display lock for
// good, so the UI's save timer would never run). Takes the display lock;
// call it from outside LVGL, with the lock not held. No-op before ui_init().
void ui_flush_pending_settings(void);

#ifdef __cplusplus
}
#endif
