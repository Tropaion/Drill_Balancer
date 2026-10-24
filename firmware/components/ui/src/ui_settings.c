#include <stdint.h>
#include <stdio.h>
#include "esp_app_desc.h"
#include "esp_log.h"
#include "settings_store.h"
#include "ui_priv.h"

static const char *TAG = "ui_settings";

#define ROW_W 330
#define STEP_BTN 64
#define LIST_PAD_V 150     // lets the first and last rows scroll to the centre
// Round list (ui_round_row()): the narrowest a row may get before it fades
// instead. Steppers keep both buttons and the value text apart, the BLE
// row its label and switch, the action buttons their text.
#define STEPPER_MIN_W 240
#define BLE_ROW_MIN_W 280
#define ACTION_W (ROW_W - 30)
#define ACTION_MIN_W 240
// Nominal and minimum width of a list child, packed into its user data
// (0: fixed size, only fades).
#define ROUND_WIDTHS(nominal, min) ((void *)(uintptr_t)(((uint32_t)(nominal) << 16) | (uint32_t)(min)))

static const char *const FIELD_NAMES[UI_FIELD_COUNT] = {
    [UI_FIELD_TOLERANCE] = "Tolerance (green)",
    [UI_FIELD_NEAR] = "Near band (amber)",
    [UI_FIELD_TARGET] = "Target angle",
    [UI_FIELD_BRIGHTNESS] = "Brightness",
    [UI_FIELD_DIM_S] = "Dim after",
    [UI_FIELD_SLEEP_S] = "Sleep after",
    [UI_FIELD_OFF_MIN] = "Power off after sleep",
};

static struct {
    lv_obj_t *scr;
    lv_obj_t *list;
    lv_obj_t *values[UI_FIELD_COUNT];
    lv_obj_t *ble_sw;
    lv_obj_t *confirm;
    lv_timer_t *save_timer;
    settings_t base;        // what edit was loaded from (three-way merge)
    settings_t edit;        // what the screen shows
    bool dirty;             // edit has changes not yet saved
    uint32_t edit_gen;      // ui_cfg_gen() that edit was last copied from
} s;

// --- round list ---

// Narrows and fades the rows by their distance from the centre, so nothing
// near the top or bottom is cut by the round panel (see ui_round_row()).
static void round_update(void)
{
    lv_area_t la;
    lv_obj_get_coords(s.list, &la);
    int32_t cy = la.y1 + lv_area_get_height(&la) / 2;
    uint32_t n = lv_obj_get_child_count(s.list);
    for (uint32_t i = 0; i < n; i++) {
        lv_obj_t *c = lv_obj_get_child(s.list, (int32_t)i);
        lv_area_t a;
        lv_obj_get_coords(c, &a);
        int32_t h = lv_area_get_height(&a);
        uintptr_t widths = (uintptr_t)lv_obj_get_user_data(c);
        int32_t nominal = widths ? (int32_t)(widths >> 16) : lv_area_get_width(&a);
        int32_t min_w = widths ? (int32_t)(widths & 0xFFFFu) : nominal;
        int32_t w;
        uint8_t opa;
        ui_round_row(a.y1 + h / 2 - cy, h, nominal, min_w, UI_SAFE_R, &w, &opa);
        if (widths) {
            lv_obj_set_width(c, w);   // the side buttons follow (aligned left/right)
        }
        if (lv_obj_get_style_opa(c, LV_PART_MAIN) != opa) {
            lv_obj_set_style_opa(c, opa, 0);
        }
    }
}

static void list_scroll_cb(lv_event_t *e)
{
    (void)e;
    round_update();
}

// --- saving ---

static void refresh_values(void)
{
    char buf[24];
    for (int f = 0; f < UI_FIELD_COUNT; f++) {
        ui_settings_format(&s.edit, (ui_field_t)f, buf, sizeof(buf));
        ui_set_text(s.values[f], buf);
    }
    lv_obj_set_state(s.ble_sw, LV_STATE_CHECKED, s.edit.ble_enabled);
}

// Shows the cached settings, dropping any unsaved edit.
static void load_cfg(void)
{
    s.edit = *ui_cfg();
    s.base = s.edit;
    s.edit_gen = ui_cfg_gen();
    refresh_values();
}

// settings_update() callback: only the fields the user changed on this
// screen (edit against base) go on top of the stored settings, under the
// store mutex, so a mode change, calibration or BLE write stored meanwhile
// (or at the same moment), even one to another field of this screen, is not
// overwritten. A BLE write to the same field the user changed loses.
static bool merge_edit(settings_t *cur, void *ctx)
{
    (void)ctx;
    ui_settings_merge(cur, &s.base, &s.edit);
    return true;
}

static void save_now(void)
{
    if (!s.dirty) {
        return;
    }
    s.dirty = false;
    lv_timer_pause(s.save_timer);

    esp_err_t err = settings_update(merge_edit, NULL);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "settings_update failed: %s", esp_err_to_name(err));
        ui_toast("Not saved", lv_color_hex(UI_C_RED), 1500);
    }
    ui_cfg_refresh();
    load_cfg();
}

static void save_timer_cb(lv_timer_t *t)
{
    (void)t;
    save_now();
}

static void mark_dirty(void)
{
    s.dirty = true;
    // Debounce: stepping through values writes flash once, after the last press.
    lv_timer_reset(s.save_timer);
    lv_timer_resume(s.save_timer);
}

// --- events ---

static void step_cb(lv_event_t *e)
{
    lv_event_code_t code = lv_event_get_code(e);
    if (code != LV_EVENT_SHORT_CLICKED && code != LV_EVENT_LONG_PRESSED &&
        code != LV_EVENT_LONG_PRESSED_REPEAT) {
        return;
    }
    intptr_t v = (intptr_t)lv_event_get_user_data(e);
    ui_field_t f = (ui_field_t)(v >> 1);
    int dir = (v & 1) ? 1 : -1;
    if (ui_settings_step(&s.edit, f, dir)) {
        refresh_values();
        mark_dirty();
    }
}

static void ble_cb(lv_event_t *e)
{
    lv_obj_t *sw = lv_event_get_target_obj(e);
    s.edit.ble_enabled = lv_obj_has_state(sw, LV_STATE_CHECKED);
    mark_dirty();
}

static void mount_cal_cb(lv_event_t *e)
{
    (void)e;
    ui_show(UI_SCREEN_MOUNT_CAL);
}

static void sensor_cal_cb(lv_event_t *e)
{
    (void)e;
    ui_show(UI_SCREEN_SENSOR_CAL);
}

static void clear_zero_cb(lv_event_t *e)
{
    (void)e;
    if (app_post_cmd(APP_CMD_CLEAR_ZERO, 0)) {
        ui_toast("Zero cleared", lv_color_hex(UI_C_WHITE), 1000);
    }
}

static void back_cb(lv_event_t *e)
{
    (void)e;
    ui_show(UI_SCREEN_LEVEL);
}

void ui_settings_confirm_reset(void)
{
    lv_obj_set_flag(s.confirm, LV_OBJ_FLAG_HIDDEN, false);
}

static void reset_cb(lv_event_t *e)
{
    (void)e;
    ui_settings_confirm_reset();
}

static void reset_cancel_cb(lv_event_t *e)
{
    (void)e;
    lv_obj_set_flag(s.confirm, LV_OBJ_FLAG_HIDDEN, true);
}

static void reset_confirm_cb(lv_event_t *e)
{
    (void)e;
    lv_obj_set_flag(s.confirm, LV_OBJ_FLAG_HIDDEN, true);
    // The control task resets; pending edits would undo part of it.
    s.dirty = false;
    lv_timer_pause(s.save_timer);
    load_cfg();
    if (app_post_cmd(APP_CMD_FACTORY_RESET, 0)) {
        ui_toast("Reset done", lv_color_hex(UI_C_WHITE), 1200);
    }
}

static void screen_gesture_cb(lv_event_t *e)
{
    (void)e;
    lv_indev_t *in = lv_indev_active();
    if (in && lv_indev_get_gesture_dir(in) == LV_DIR_RIGHT && lv_obj_has_flag(s.confirm, LV_OBJ_FLAG_HIDDEN)) {
        ui_show(UI_SCREEN_LEVEL);
    }
}

// --- creation ---

static lv_obj_t *row_new(int32_t h, int32_t min_w)
{
    lv_obj_t *row = ui_box_new(s.list);
    lv_obj_set_size(row, ROW_W, h);
    lv_obj_set_user_data(row, ROUND_WIDTHS(ROW_W, min_w));
    return row;
}

static void stepper_new(ui_field_t f)
{
    lv_obj_t *row = row_new(92, STEPPER_MIN_W);
    lv_obj_t *name = ui_label_new(row, &lv_font_montserrat_16, UI_C_GREY);
    lv_label_set_text_static(name, FIELD_NAMES[f]);
    lv_obj_align(name, LV_ALIGN_TOP_MID, 0, 0);

    lv_obj_t *minus = ui_button_new(row, LV_SYMBOL_MINUS, STEP_BTN, STEP_BTN, NULL, NULL);
    lv_obj_align(minus, LV_ALIGN_BOTTOM_LEFT, 0, 0);
    lv_obj_add_event_cb(minus, step_cb, LV_EVENT_ALL, (void *)(intptr_t)((intptr_t)f << 1));
    lv_obj_t *plus = ui_button_new(row, LV_SYMBOL_PLUS, STEP_BTN, STEP_BTN, NULL, NULL);
    lv_obj_align(plus, LV_ALIGN_BOTTOM_RIGHT, 0, 0);
    lv_obj_add_event_cb(plus, step_cb, LV_EVENT_ALL, (void *)(intptr_t)(((intptr_t)f << 1) | 1));

    s.values[f] = ui_label_new(row, &lv_font_montserrat_24, UI_C_WHITE);
    lv_obj_align(s.values[f], LV_ALIGN_BOTTOM_MID, 0, -19);
}

static void ble_row_new(void)
{
    lv_obj_t *row = row_new(72, BLE_ROW_MIN_W);
    lv_obj_t *l = ui_label_new(row, &lv_font_montserrat_24, UI_C_WHITE);
    lv_label_set_text_static(l, LV_SYMBOL_BLUETOOTH "  Bluetooth");
    lv_obj_align(l, LV_ALIGN_LEFT_MID, 16, 0);

    s.ble_sw = lv_switch_create(row);
    lv_obj_remove_style_all(s.ble_sw);
    lv_obj_set_size(s.ble_sw, 96, 52);
    lv_obj_align(s.ble_sw, LV_ALIGN_RIGHT_MID, -8, 0);
    lv_obj_set_style_radius(s.ble_sw, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s.ble_sw, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s.ble_sw, lv_color_hex(UI_C_DARK), LV_PART_MAIN);
    lv_obj_set_style_radius(s.ble_sw, LV_RADIUS_CIRCLE, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s.ble_sw, LV_OPA_TRANSP, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s.ble_sw, LV_OPA_COVER, LV_PART_INDICATOR | LV_STATE_CHECKED);
    lv_obj_set_style_bg_color(s.ble_sw, lv_color_hex(UI_C_BLUE),
                              LV_PART_INDICATOR | LV_STATE_CHECKED);
    lv_obj_set_style_radius(s.ble_sw, LV_RADIUS_CIRCLE, LV_PART_KNOB);
    lv_obj_set_style_bg_opa(s.ble_sw, LV_OPA_COVER, LV_PART_KNOB);
    lv_obj_set_style_bg_color(s.ble_sw, lv_color_white(), LV_PART_KNOB);
    lv_obj_set_style_pad_all(s.ble_sw, -5, LV_PART_KNOB);
    lv_obj_add_event_cb(s.ble_sw, ble_cb, LV_EVENT_VALUE_CHANGED, NULL);
}

static lv_obj_t *action_new(const char *text, lv_event_cb_t cb, uint32_t color)
{
    lv_obj_t *b = ui_button_new(s.list, text, ACTION_W, 66, cb, NULL);
    lv_obj_set_style_text_color(lv_obj_get_child(b, 0), lv_color_hex(color), 0);
    lv_obj_set_user_data(b, ROUND_WIDTHS(ACTION_W, ACTION_MIN_W));
    return b;
}

static void confirm_new(void)
{
    s.confirm = ui_panel_new(s.scr);

    lv_obj_t *l = ui_label_new(s.confirm, &lv_font_montserrat_48, UI_C_RED);
    lv_label_set_text_static(l, LV_SYMBOL_WARNING);
    lv_obj_align(l, LV_ALIGN_CENTER, 0, -120);
    l = ui_label_new(s.confirm, &lv_font_montserrat_24, UI_C_WHITE);
    lv_label_set_text_static(l, "Factory reset?");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, -62);
    l = ui_label_new(s.confirm, &lv_font_montserrat_16, UI_C_GREY);
    lv_label_set_text_static(l, "All settings and both\ncalibrations will be lost.");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, -16);

    lv_obj_t *cancel = ui_button_new(s.confirm, "Cancel", 150, 66, reset_cancel_cb, NULL);
    lv_obj_align(cancel, LV_ALIGN_CENTER, -82, 70);
    lv_obj_t *ok = ui_button_new(s.confirm, "Reset", 150, 66, reset_confirm_cb, NULL);
    lv_obj_set_style_bg_color(ok, lv_color_hex(UI_C_RED_BG), 0);
    lv_obj_align(ok, LV_ALIGN_CENTER, 82, 70);
}

lv_obj_t *ui_settings_create(void)
{
    s.scr = ui_screen_new();
    lv_obj_add_event_cb(s.scr, screen_gesture_cb, LV_EVENT_GESTURE, NULL);

    s.list = ui_box_new(s.scr);
    // Clickable too: LVGL only starts a scroll on a clickable object (or one
    // whose clickable child chains to it), and the labels, gaps and rows are
    // not, so without this only a drag that starts on a button scrolls.
    lv_obj_add_flag(s.list, LV_OBJ_FLAG_SCROLLABLE | LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(s.list, UI_W, UI_H);
    lv_obj_set_scroll_dir(s.list, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(s.list, LV_SCROLLBAR_MODE_OFF);
    lv_obj_set_scroll_snap_y(s.list, LV_SCROLL_SNAP_CENTER);
    lv_obj_set_flex_flow(s.list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(s.list, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_top(s.list, 70, 0);
    lv_obj_set_style_pad_bottom(s.list, LIST_PAD_V, 0);
    lv_obj_set_style_pad_row(s.list, 30, 0);
    lv_obj_add_event_cb(s.list, list_scroll_cb, LV_EVENT_SCROLL, NULL);

    lv_obj_t *title = ui_label_new(s.list, &lv_font_montserrat_24, UI_C_GREY);
    lv_label_set_text_static(title, LV_SYMBOL_SETTINGS "  SETTINGS");
    lv_obj_t *hint = ui_label_new(s.list, &lv_font_montserrat_16, UI_C_GREY);
    lv_label_set_text_static(hint, "Swipe right or tap Back to leave");

    stepper_new(UI_FIELD_TOLERANCE);
    stepper_new(UI_FIELD_NEAR);
    stepper_new(UI_FIELD_TARGET);
    stepper_new(UI_FIELD_BRIGHTNESS);
    stepper_new(UI_FIELD_DIM_S);
    stepper_new(UI_FIELD_SLEEP_S);
    stepper_new(UI_FIELD_OFF_MIN);
    ble_row_new();
    action_new("Calibrate mount", mount_cal_cb, UI_C_WHITE);
    action_new("Calibrate sensor", sensor_cal_cb, UI_C_WHITE);
    action_new("Clear zero", clear_zero_cb, UI_C_WHITE);
    action_new("Factory reset", reset_cb, UI_C_RED);

    lv_obj_t *ver = ui_label_new(s.list, &lv_font_montserrat_16, UI_C_GREY);
    lv_label_set_text_fmt(ver, "Firmware %s", esp_app_get_description()->version);

    action_new(LV_SYMBOL_LEFT "  Back", back_cb, UI_C_GREEN);

    confirm_new();
    s.save_timer = lv_timer_create(save_timer_cb, UI_SAVE_DEBOUNCE_MS, NULL);
    lv_timer_pause(s.save_timer);
    load_cfg();
    lv_obj_update_layout(s.scr);
    round_update();
    return s.scr;
}

lv_obj_t *ui_settings_list(void)
{
    return s.list;
}

void ui_settings_on_show(void)
{
    ui_cfg_refresh();
    s.dirty = false;
    lv_obj_set_flag(s.confirm, LV_OBJ_FLAG_HIDDEN, true);
    load_cfg();
    lv_obj_scroll_to_y(s.list, 0, LV_ANIM_OFF);
    lv_obj_update_layout(s.list);
    round_update();   // also when the list was already at the top
}

void ui_settings_on_hide(void)
{
    save_now();
    lv_obj_set_flag(s.confirm, LV_OBJ_FLAG_HIDDEN, true);
}

void ui_settings_flush(void)
{
    save_now();
}

void ui_settings_update(const app_snapshot_t *snap)
{
    (void)snap;
    // Follow changes made elsewhere (BLE, factory reset) unless the user is
    // in the middle of editing. The cache changes at most once a poll.
    if (!s.dirty && s.edit_gen != ui_cfg_gen()) {
        load_cfg();
    }
}
