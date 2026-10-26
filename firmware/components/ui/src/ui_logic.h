#pragma once

// Pure C helpers behind the UI: number formatting, display geometry, the
// settings steppers and result texts. No LVGL, host-tested in
// test_host/ui_preview (test_ui_logic).

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "app_state.h"
#include "settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// "%.*f" with an optional suffix, built from integers (no double promotion,
// never "-0.0"). decimals 0..3. Non-finite values give "--" + suffix.
// Magnitudes saturate at 9999.
void ui_fmt_fixed(char *buf, size_t len, float v, int decimals, bool show_plus,
                  const char *suffix);

// One decimal and a degree sign, e.g. "-12.3°".
void ui_fmt_deg(char *buf, size_t len, float deg, bool show_plus);

// Elevation rounded to tenths of a degree: what the display shows.
int32_t ui_tenths(float deg);

// Wall mode arrow: +1 bit above horizontal, -1 below, 0 none (on target or
// it would read 0.0).
int ui_wall_arrow(const level_reading_t *r);

// Fill of the status ring: 1 at zero deviation, 0 at 3 * near_deg or more.
float ui_ring_fraction(float deviation_deg, float near_deg);

// Pixels per degree of the floor bubble: near_deg maps to a third of the
// view radius.
float ui_bubble_ppd(float near_deg, float radius_px);

// Bubble centre offset in pixels (LVGL axes: x right, y down), clamped so
// the bubble stays inside the view of radius radius_px.
void ui_bubble_offset(float bubble_x_deg, float bubble_y_deg, float near_deg, float radius_px,
                      float bubble_r_px, int32_t *x, int32_t *y);

// Yaw marker offset from the centre of a bar of half width half_w_px;
// near_deg maps to a third of it. Right (positive yaw) is positive.
int32_t ui_yaw_offset(float yaw_deg, float near_deg, int32_t half_w_px);

// Burn-in shift for step n (cycles through a small pattern, |d| <= amp).
void ui_burnin_offset(uint32_t step, int32_t amp, int32_t *dx, int32_t *dy);

// Round settings list: a row whose centre is dy_px from the screen centre,
// h_px high, gets the width w_out (min_w .. nominal_w) that keeps its
// corners inside radius_px, so the rows above and below the centred one
// narrow and their side buttons move inward. Where even min_w does not fit,
// the row fades (opa_out down to UI_ROUND_MIN_OPA) instead of being cut by
// the panel edge. A row with min_w == nominal_w only fades.
#define UI_ROUND_MIN_OPA 40
void ui_round_row(int32_t dy_px, int32_t h_px, int32_t nominal_w, int32_t min_w,
                  int32_t radius_px, int32_t *w_out, uint8_t *opa_out);

// Battery icon level 0 (empty) .. 4 (full).
int ui_battery_level(uint8_t percent);

// True when the low-battery banner should show.
bool ui_low_battery(const app_battery_t *b);

#define UI_LOW_BATTERY_PCT 10

// The REC pill over every screen (ui.c): "REC mm:ss" (the take's elapsed
// time) while RECORDING, "REC ready" between takes and while a stopped
// take drains. False, and an empty text, when it is hidden (no session, or
// the session is not ready yet or closing).
bool ui_rec_pill_text(const app_rec_status_t *r, char *buf, size_t len);

// Only a recording pill takes touches (its 2 s hold stops the take); as
// "REC ready" it is a label and a tap goes to the screen beneath it.
bool ui_rec_pill_live(const app_rec_status_t *r);

#define UI_REC_HOLD_MS 2000

// --- settings steppers ---

typedef enum {
    UI_FIELD_TOLERANCE = 0,
    UI_FIELD_NEAR,
    UI_FIELD_TARGET,
    UI_FIELD_BRIGHTNESS,
    UI_FIELD_DIM_S,
    UI_FIELD_SLEEP_S,
    UI_FIELD_OFF_MIN,
    UI_FIELD_COUNT
} ui_field_t;

// Steps one field up (dir > 0) or down (dir < 0) within its SETTINGS_*
// limits (settings.h) and then repairs the cross-field rules
// (settings_fixup()), so a valid input stays valid. Returns true if
// anything changed.
bool ui_settings_step(settings_t *s, ui_field_t f, int dir);

// The value text of a field, e.g. "0.50°", "30 s", "Never".
void ui_settings_format(const settings_t *s, ui_field_t f, char *buf, size_t len);

// Three-way merge of a settings screen edit onto cur (the current stored
// settings). base is what the screen loaded, edit what it shows now; only
// the screen's fields that differ between the two are copied (including
// ones settings_fixup() moved while stepping), so anything changed
// elsewhere meanwhile (BLE write, mode, calibration) is kept. Then repairs
// the cross-field rules (settings_fixup()).
void ui_settings_merge(settings_t *cur, const settings_t *base, const settings_t *edit);

// --- texts ---

const char *ui_mode_title(level_mode_t mode);
// level_cal_result_t / accel_cal_result_t (or APP_CAL_RESULT_NOT_STORED,
// and for the sensor calibration APP_CAL_RESULT_OK_MOUNT_DROPPED) as user
// text.
const char *ui_mount_result_text(int result);
const char *ui_accel_result_text(int result);
// A last_result the result screen can show (a result code, NOT_STORED or
// OK_MOUNT_DROPPED, not NONE), and whether it is a success (green).
bool ui_cal_result_known(int result);
bool ui_cal_result_ok(int result);
// A calibration start while app_snapshot_t.excl (app_excl_t) says OTA or a
// recording session has the device: the guide shows the refusal text (for
// that owner) instead of waiting for a calibration that never starts.
bool ui_cal_start_refused(uint8_t excl);
const char *ui_cal_refused_text(uint8_t excl);

// The six sensor calibration poses in display order.
typedef struct {
    uint8_t bit;        // ACCEL_CAL_DIR_*
    const char *label;
} ui_pose_t;

#define UI_POSE_COUNT 6
extern const ui_pose_t ui_poses[UI_POSE_COUNT];

#ifdef __cplusplus
}
#endif
