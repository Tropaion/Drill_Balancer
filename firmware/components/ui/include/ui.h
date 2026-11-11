#pragma once

// LVGL 9 user interface for the 466x466 round AMOLED. Black background.
//
// The UI only reads app_state_get() snapshots (from its own lv_timer, about
// 15 Hz) and posts app commands / edits settings via settings_get/update.
// It never calls sensor, PMU or BLE code.
//
// Screens:
// - Level: one screen, no swipe; the device picks the view from how the board
//   sits (title WALL = crosshair, LEVEL = bubble). A 1 s hold zeroes (wall view
//   only), a tap does nothing; BOOT opens the settings and leaves them. One
//   dot, coloured by status (green on target, amber near, white off): a bubble
//   in the level view, where the bit points in the wall view. Below it the
//   error and the yaw (wall, stale warning); battery, charging and BLE icons.
// - Settings: tolerance, brightness, auto-off times, BLE,
//   "Calibrate mount", "Calibrate sensor", clear zero, firmware version.
// - Calibration guides: mount (bit in a hole, Start, roll the drill +-45
//   deg, progress), sensor (six sides, coverage shown as six targets).
// - Overlays: BLE passkey while pairing, OTA progress, low battery, and the
//   REC pill of a recording session ("REC ready", "REC mm:ss"; not modal).
// A touch or a BOOT press that wakes a dimmed screen does nothing else, on any
// screen. BOOT does nothing on the calibration guides, under the passkey and
// OTA overlays and during a take.
// During a take a touch is logged in the recording (EVENT TOUCH) and does
// nothing else either, unless it is on the REC pill: held 2 s, the pill
// stops the take. The UI posts that STOP with recorder_send() and the
// touches with recorder_note_touch(); it never calls anything else of the
// recorder.

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

// Starts the display, touch and the LVGL task (bsp_display_start() with
// small draw buffers in internal RAM, see ui_display.c). Call once, before
// ui_init(); the BSP's display lock and brightness calls work afterwards.
esp_err_t ui_display_start(void);

// Call once after ui_display_start(), with the display lock NOT held.
esp_err_t ui_init(void);

// Stores a settings edit that is still waiting for the 1 s save debounce.
// Call before deep sleep or power-off (which take the display lock for
// good, so the UI's save timer would never run). Takes the display lock;
// call it from outside LVGL, with the lock not held. No-op before ui_init().
void ui_flush_pending_settings(void);

#ifdef __cplusplus
}
#endif
