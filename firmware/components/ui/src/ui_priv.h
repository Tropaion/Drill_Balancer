#pragma once

// Internals shared by the UI sources. All functions run in the LVGL task
// (timer and event callbacks) or with the display lock held.

#include <stdbool.h>
#include <stdint.h>
#include "lvgl.h"
#include "app_state.h"
#include "settings.h"
#include "ui_logic.h"

#ifdef __cplusplus
extern "C" {
#endif

#define UI_W 466
#define UI_H 466
// TODO(hw): the CO5300 panel is assumed to show the full inscribed circle
// (radius 233) centred on the 466x466 frame; content is kept within 225.
#define UI_VISIBLE_R (UI_W / 2)
#define UI_SAFE_R 225

#define UI_TICK_MS 66
#define UI_SETTINGS_POLL_MS 1000    // re-read settings_get() for changes made elsewhere
#define UI_SAVE_DEBOUNCE_MS 1000
#define UI_BURNIN_PERIOD_MS (3u * 60u * 1000u)
#define UI_BURNIN_AMP_PX 4

#define UI_C_GREEN 0x00E676
#define UI_C_AMBER 0xFFB300
#define UI_C_WHITE 0xFFFFFF
#define UI_C_GREY 0x9E9E9E
#define UI_C_DARK 0x2A2A2A          // inactive ring, outlines
#define UI_C_BUTTON 0x1C1C1C
#define UI_C_BUTTON_PR 0x3A3A3A
#define UI_C_RED 0xFF5252
#define UI_C_RED_BG 0xC62828
#define UI_C_BLUE 0x448AFF

extern const lv_font_t ui_font_num112;  // digits . - + ° and space only

typedef enum {
    UI_SCREEN_LEVEL = 0,
    UI_SCREEN_SETTINGS,
    UI_SCREEN_MOUNT_CAL,
    UI_SCREEN_SENSOR_CAL,
    UI_SCREEN_COUNT
} ui_screen_t;

// --- ui.c ---
void ui_show(ui_screen_t screen);
ui_screen_t ui_current(void);
void ui_toast(const char *text, lv_color_t color, uint32_t ms);
// Last settings seen by the UI (refreshed every UI_SETTINGS_POLL_MS and
// after the UI saves). ui_cfg_gen() counts the refreshes that changed them,
// so a screen can skip work while they stay the same.
const settings_t *ui_cfg(void);
void ui_cfg_refresh(void);
uint32_t ui_cfg_gen(void);

lv_color_t ui_status_color(level_status_t st);

// Widget helpers. The setters only touch LVGL when the value changes, so an
// unchanged frame redraws nothing. For flags and states use LVGL's own
// lv_obj_set_flag() / lv_obj_set_state(): they already skip unchanged values.
lv_obj_t *ui_screen_new(void);
lv_obj_t *ui_box_new(lv_obj_t *parent);    // transparent, no border, not clickable
// Full-screen opaque black box that swallows touches (modal), created hidden.
lv_obj_t *ui_panel_new(lv_obj_t *parent);
// Filled box with fully rounded ends (a circle when w == h).
lv_obj_t *ui_pill_new(lv_obj_t *parent, int32_t w, int32_t h, uint32_t color);
// Centred, non-clickable arc with a track (MAIN) and a rounded indicator of
// the same width, value range 0 .. range, value 0. The caller sets the
// angles and rotation.
lv_obj_t *ui_arc_new(lv_obj_t *parent, int32_t diameter, int32_t width, int32_t range,
                     uint32_t track_color, uint32_t ind_color);
lv_obj_t *ui_label_new(lv_obj_t *parent, const lv_font_t *font, uint32_t color);
lv_obj_t *ui_button_new(lv_obj_t *parent, const char *text, int32_t w, int32_t h,
                        lv_event_cb_t cb, void *user_data);
void ui_set_text(lv_obj_t *label, const char *text);
void ui_set_text_color(lv_obj_t *obj, lv_color_t c);
void ui_set_bg_color(lv_obj_t *obj, lv_color_t c);
void ui_set_border_color(lv_obj_t *obj, lv_color_t c);
void ui_set_arc_color(lv_obj_t *arc, lv_color_t c);

// --- screens ---
lv_obj_t *ui_level_create(void);
void ui_level_update(const app_snapshot_t *snap, uint32_t now_ms);

lv_obj_t *ui_settings_create(void);
void ui_settings_on_show(void);
void ui_settings_on_hide(void);    // saves a pending change at once
void ui_settings_flush(void);      // saves a pending change at once, stays shown
void ui_settings_update(const app_snapshot_t *snap);

// Calibration guides: Cancel / Retry and Start / Finish / Done buttons,
// centred at (-+UI_CAL_BUTTON_DX, UI_CAL_BUTTON_DY) from the screen centre.
#define UI_CAL_BUTTON_DX 76
#define UI_CAL_BUTTON_DY 125

lv_obj_t *ui_mount_cal_create(void);
void ui_mount_cal_on_show(void);   // instructions; Start posts APP_CMD_MOUNT_CAL_START
void ui_mount_cal_update(const app_snapshot_t *snap);

lv_obj_t *ui_sensor_cal_create(void);
void ui_sensor_cal_on_show(void);  // posts APP_CMD_ACCEL_CAL_START
void ui_sensor_cal_update(const app_snapshot_t *snap);

// --- overlays (ui.c) ---
void ui_overlays_create(void);
void ui_overlays_update(const app_snapshot_t *snap);

// For the host preview harness only: the settings list (to scroll it) and
// the factory reset confirmation.
lv_obj_t *ui_settings_list(void);
void ui_settings_confirm_reset(void);

#ifdef __cplusplus
}
#endif
