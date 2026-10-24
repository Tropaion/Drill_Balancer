#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "bsp/esp-bsp.h"
#include "settings_store.h"
#include "ui.h"
#include "ui_priv.h"

static const char *TAG = "ui";

// Generous: LVGL finishes a frame well within this.
#define FLUSH_LOCK_TIMEOUT_MS 500

static bool s_started;
static lv_obj_t *s_screens[UI_SCREEN_COUNT];
static ui_screen_t s_current = UI_SCREEN_LEVEL;
static settings_t s_cfg;
static uint32_t s_cfg_ms;
static uint32_t s_cfg_gen;
static app_power_t s_power;     // last snapshot's power state

// Overlays live on lv_layer_top() so they cover every screen.
static struct {
    lv_obj_t *passkey;
    lv_obj_t *passkey_digits;
    lv_obj_t *ota;
    lv_obj_t *ota_arc;
    lv_obj_t *ota_pct;
    lv_obj_t *lowbat;
    lv_obj_t *lowbat_text;
    lv_obj_t *toast;
    lv_obj_t *toast_text;
    lv_timer_t *toast_timer;
} s_ov;

static lv_style_t s_btn_style;
static lv_style_t s_btn_pressed;
static lv_style_t s_btn_disabled;

// --- helpers ---

lv_color_t ui_status_color(level_status_t st)
{
    switch (st) {
    case LEVEL_STATUS_ON_TARGET:
        return lv_color_hex(UI_C_GREEN);
    case LEVEL_STATUS_NEAR:
        return lv_color_hex(UI_C_AMBER);
    default:
        return lv_color_hex(UI_C_WHITE);
    }
}

lv_obj_t *ui_screen_new(void)
{
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_remove_style_all(scr);
    lv_obj_set_size(scr, UI_W, UI_H);
    lv_obj_set_style_bg_color(scr, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_set_style_text_color(scr, lv_color_white(), 0);
    lv_obj_set_style_text_font(scr, &lv_font_montserrat_24, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
    return scr;
}

lv_obj_t *ui_box_new(lv_obj_t *parent)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    return o;
}

lv_obj_t *ui_panel_new(lv_obj_t *parent)
{
    lv_obj_t *p = ui_box_new(parent);
    lv_obj_set_size(p, UI_W, UI_H);
    lv_obj_set_style_bg_color(p, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(p, LV_OPA_COVER, 0);
    // Swallow touches so nothing behind the panel reacts.
    lv_obj_add_flag(p, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_flag(p, LV_OBJ_FLAG_HIDDEN);
    return p;
}

lv_obj_t *ui_pill_new(lv_obj_t *parent, int32_t w, int32_t h, uint32_t color)
{
    lv_obj_t *r = ui_box_new(parent);
    lv_obj_set_size(r, w, h);
    lv_obj_set_style_radius(r, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(r, lv_color_hex(color), 0);
    lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
    return r;
}

lv_obj_t *ui_arc_new(lv_obj_t *parent, int32_t diameter, int32_t width, int32_t range,
                     uint32_t track_color, uint32_t ind_color)
{
    lv_obj_t *a = lv_arc_create(parent);
    lv_obj_remove_style_all(a);
    lv_obj_remove_flag(a, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(a, diameter, diameter);
    lv_obj_center(a);
    lv_arc_set_range(a, 0, range);
    lv_arc_set_value(a, 0);
    lv_obj_set_style_arc_width(a, width, LV_PART_MAIN);
    lv_obj_set_style_arc_color(a, lv_color_hex(track_color), LV_PART_MAIN);
    lv_obj_set_style_arc_width(a, width, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(a, lv_color_hex(ind_color), LV_PART_INDICATOR);
    lv_obj_set_style_arc_rounded(a, true, LV_PART_INDICATOR);
    return a;
}

lv_obj_t *ui_label_new(lv_obj_t *parent, const lv_font_t *font, uint32_t color)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, font, 0);
    lv_obj_set_style_text_color(l, lv_color_hex(color), 0);
    lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text_static(l, "");
    return l;
}

lv_obj_t *ui_button_new(lv_obj_t *parent, const char *text, int32_t w, int32_t h,
                        lv_event_cb_t cb, void *user_data)
{
    lv_obj_t *b = lv_button_create(parent);
    // Own styles instead of the theme: same look on the device and in the
    // host preview, and no shadows (power, burn-in).
    lv_obj_remove_style_all(b);
    lv_obj_add_style(b, &s_btn_style, 0);
    lv_obj_add_style(b, &s_btn_pressed, LV_STATE_PRESSED);
    lv_obj_add_style(b, &s_btn_disabled, LV_STATE_DISABLED);
    lv_obj_set_size(b, w, h);
    lv_obj_remove_flag(b, LV_OBJ_FLAG_SCROLL_ON_FOCUS);
    if (cb) {
        lv_obj_add_event_cb(b, cb, LV_EVENT_CLICKED, user_data);
    }
    if (text) {
        lv_obj_t *l = ui_label_new(b, &lv_font_montserrat_24, UI_C_WHITE);
        lv_label_set_text(l, text);
        lv_obj_center(l);
    }
    return b;
}

void ui_set_text(lv_obj_t *label, const char *text)
{
    const char *cur = lv_label_get_text(label);
    if (cur == NULL || strcmp(cur, text) != 0) {
        lv_label_set_text(label, text);
    }
}

void ui_set_text_color(lv_obj_t *obj, lv_color_t c)
{
    if (!lv_color_eq(lv_obj_get_style_text_color(obj, LV_PART_MAIN), c)) {
        lv_obj_set_style_text_color(obj, c, 0);
    }
}

void ui_set_bg_color(lv_obj_t *obj, lv_color_t c)
{
    if (!lv_color_eq(lv_obj_get_style_bg_color(obj, LV_PART_MAIN), c)) {
        lv_obj_set_style_bg_color(obj, c, 0);
    }
}

void ui_set_border_color(lv_obj_t *obj, lv_color_t c)
{
    if (!lv_color_eq(lv_obj_get_style_border_color(obj, LV_PART_MAIN), c)) {
        lv_obj_set_style_border_color(obj, c, 0);
    }
}

void ui_set_arc_color(lv_obj_t *arc, lv_color_t c)
{
    if (!lv_color_eq(lv_obj_get_style_arc_color(arc, LV_PART_INDICATOR), c)) {
        lv_obj_set_style_arc_color(arc, c, LV_PART_INDICATOR);
    }
}

static void styles_init(void)
{
    lv_style_init(&s_btn_style);
    lv_style_set_bg_color(&s_btn_style, lv_color_hex(UI_C_BUTTON));
    lv_style_set_bg_opa(&s_btn_style, LV_OPA_COVER);
    lv_style_set_radius(&s_btn_style, LV_RADIUS_CIRCLE);
    lv_style_set_border_color(&s_btn_style, lv_color_hex(UI_C_DARK));
    lv_style_set_border_width(&s_btn_style, 2);
    lv_style_set_text_color(&s_btn_style, lv_color_white());

    lv_style_init(&s_btn_pressed);
    lv_style_set_bg_color(&s_btn_pressed, lv_color_hex(UI_C_BUTTON_PR));

    lv_style_init(&s_btn_disabled);
    lv_style_set_opa(&s_btn_disabled, LV_OPA_40);
}

// --- settings cache ---

const settings_t *ui_cfg(void)
{
    return &s_cfg;
}

void ui_cfg_refresh(void)
{
    settings_t next;
    settings_get(&next);
    if (!settings_equal(&next, &s_cfg)) {   // settings_t has padding: no memcmp
        s_cfg = next;
        s_cfg_gen++;
    }
    s_cfg_ms = lv_tick_get();
}

uint32_t ui_cfg_gen(void)
{
    return s_cfg_gen;
}

// --- screens ---

ui_screen_t ui_current(void)
{
    return s_current;
}

void ui_show(ui_screen_t screen)
{
    if (screen >= UI_SCREEN_COUNT) {
        return;
    }
    // The finger that triggered the switch must not act on the new screen.
    lv_indev_t *indev = lv_indev_active();
    if (indev) {
        lv_indev_wait_release(indev);
    }
    if (s_current == UI_SCREEN_SETTINGS && screen != UI_SCREEN_SETTINGS) {
        ui_settings_on_hide();
    }
    s_current = screen;
    switch (screen) {
    case UI_SCREEN_SETTINGS:
        ui_settings_on_show();
        break;
    case UI_SCREEN_MOUNT_CAL:
        ui_mount_cal_on_show();
        break;
    case UI_SCREEN_SENSOR_CAL:
        ui_sensor_cal_on_show();
        break;
    default:
        break;
    }
    lv_screen_load(s_screens[screen]);
}

// --- overlays ---

static void toast_timer_cb(lv_timer_t *t)
{
    lv_timer_pause(t);
    lv_obj_set_flag(s_ov.toast, LV_OBJ_FLAG_HIDDEN, true);
}

void ui_toast(const char *text, lv_color_t color, uint32_t ms)
{
    ui_set_text(s_ov.toast_text, text);
    ui_set_text_color(s_ov.toast_text, color);
    ui_set_border_color(s_ov.toast, color);
    lv_obj_set_flag(s_ov.toast, LV_OBJ_FLAG_HIDDEN, false);
    lv_timer_set_period(s_ov.toast_timer, ms);
    lv_timer_reset(s_ov.toast_timer);
    lv_timer_resume(s_ov.toast_timer);
}

void ui_overlays_create(void)
{
    // BLE passkey
    s_ov.passkey = ui_panel_new(lv_layer_top());
    lv_obj_t *l = ui_label_new(s_ov.passkey, &lv_font_montserrat_24, UI_C_GREY);
    lv_label_set_text_static(l, LV_SYMBOL_BLUETOOTH "  Pairing");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, -100);
    s_ov.passkey_digits = ui_label_new(s_ov.passkey, &ui_font_num112, UI_C_WHITE);
    lv_obj_align(s_ov.passkey_digits, LV_ALIGN_CENTER, 0, 0);
    l = ui_label_new(s_ov.passkey, &lv_font_montserrat_24, UI_C_GREY);
    lv_label_set_text_static(l, "Enter this code\non your phone");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, 104);

    // OTA progress
    s_ov.ota = ui_panel_new(lv_layer_top());
    s_ov.ota_arc = ui_arc_new(s_ov.ota, 320, 16, 100, UI_C_DARK, UI_C_GREEN);
    lv_arc_set_bg_angles(s_ov.ota_arc, 0, 360);
    lv_arc_set_rotation(s_ov.ota_arc, 270);
    l = ui_label_new(s_ov.ota, &lv_font_montserrat_24, UI_C_GREY);
    lv_label_set_text_static(l, "Updating");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, -52);
    s_ov.ota_pct = ui_label_new(s_ov.ota, &lv_font_montserrat_48, UI_C_WHITE);
    lv_obj_align(s_ov.ota_pct, LV_ALIGN_CENTER, 0, 4);
    l = ui_label_new(s_ov.ota, &lv_font_montserrat_16, UI_C_AMBER);
    lv_label_set_text_static(l, "Do not switch off");
    lv_obj_align(l, LV_ALIGN_CENTER, 0, 56);

    // Low battery banner: over the title, lets touches through.
    // Wider than the longest mode title it covers; the round ends stay
    // inside the ring.
    s_ov.lowbat = ui_pill_new(lv_layer_top(), 224, 42, UI_C_RED_BG);
    lv_obj_align(s_ov.lowbat, LV_ALIGN_TOP_MID, 0, 46);
    s_ov.lowbat_text = ui_label_new(s_ov.lowbat, &lv_font_montserrat_24, UI_C_WHITE);
    lv_obj_center(s_ov.lowbat_text);
    lv_obj_add_flag(s_ov.lowbat, LV_OBJ_FLAG_HIDDEN);

    // Toast (e.g. "ZERO")
    s_ov.toast = ui_box_new(lv_layer_top());
    lv_obj_set_size(s_ov.toast, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    // Big enough to hide the widest elevation, so the flash reads cleanly.
    lv_obj_set_style_min_width(s_ov.toast, 340, 0);
    lv_obj_set_style_min_height(s_ov.toast, 116, 0);
    lv_obj_set_style_pad_hor(s_ov.toast, 30, 0);
    lv_obj_set_style_pad_ver(s_ov.toast, 14, 0);
    lv_obj_set_style_radius(s_ov.toast, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(s_ov.toast, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(s_ov.toast, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(s_ov.toast, 3, 0);
    lv_obj_set_style_border_color(s_ov.toast, lv_color_hex(UI_C_GREEN), 0);
    lv_obj_align(s_ov.toast, LV_ALIGN_CENTER, 0, -22);   // over the big number
    s_ov.toast_text = ui_label_new(s_ov.toast, &lv_font_montserrat_48, UI_C_GREEN);
    lv_obj_center(s_ov.toast_text);
    lv_obj_add_flag(s_ov.toast, LV_OBJ_FLAG_HIDDEN);
    s_ov.toast_timer = lv_timer_create(toast_timer_cb, 1000, NULL);
    lv_timer_pause(s_ov.toast_timer);
}

void ui_overlays_update(const app_snapshot_t *snap)
{
    char buf[24];

    lv_obj_set_flag(s_ov.ota, LV_OBJ_FLAG_HIDDEN, !snap->ota_active);
    if (snap->ota_active) {
        uint8_t pct = snap->ota_percent > 100 ? 100 : snap->ota_percent;
        lv_arc_set_value(s_ov.ota_arc, pct);
        snprintf(buf, sizeof(buf), "%u %%", (unsigned)pct);
        ui_set_text(s_ov.ota_pct, buf);
    }

    lv_obj_set_flag(s_ov.passkey, LV_OBJ_FLAG_HIDDEN, !snap->passkey_pending);
    if (snap->passkey_pending) {
        snprintf(buf, sizeof(buf), "%06lu", (unsigned long)(snap->passkey % 1000000u));
        ui_set_text(s_ov.passkey_digits, buf);
    }

    bool low = ui_low_battery(&snap->battery) && !snap->ota_active && !snap->passkey_pending;
    lv_obj_set_flag(s_ov.lowbat, LV_OBJ_FLAG_HIDDEN, !low);
    if (low) {
        snprintf(buf, sizeof(buf), LV_SYMBOL_BATTERY_EMPTY "  LOW %u%%",
                 (unsigned)snap->battery.percent);
        ui_set_text(s_ov.lowbat_text, buf);
    }
}

void ui_flush_pending_settings(void)
{
    if (!s_started) {
        return;
    }
    if (bsp_display_lock(FLUSH_LOCK_TIMEOUT_MS) != ESP_OK) {
        ESP_LOGW(TAG, "display lock busy, pending settings not saved");
        return;
    }
    ui_settings_flush();
    bsp_display_unlock();
}

// --- main loop ---

// Indev hook: LVGL runs it before the pressed object sees the press.
static void activity_cb(lv_event_t *e)
{
    (void)e;
    app_note_activity();
    // A tap that brightens a dimmed screen does nothing else, on any screen
    // (no re-zero, no stepper, no calibration button): the object never gets
    // the press, and the rest of the touch is ignored until release. The
    // control task only leaves DIM a moment after this press, so s_power
    // still says DIM here.
    if (s_power == APP_POWER_DIM) {
        lv_indev_t *in = lv_indev_active();
        lv_indev_stop_processing(in);
        lv_indev_wait_release(in);
    }
}

static void tick_cb(lv_timer_t *t)
{
    (void)t;
    app_snapshot_t snap;
    app_state_get(&snap);
    s_power = snap.power;

    // Nothing to draw while the panel is off; the power code owns that.
    if (snap.power == APP_POWER_SLEEP || snap.power == APP_POWER_OFF) {
        return;
    }
    uint32_t now = lv_tick_get();
    if (lv_tick_diff(now, s_cfg_ms) >= UI_SETTINGS_POLL_MS) {
        ui_cfg_refresh();
    }

    ui_overlays_update(&snap);
    switch (s_current) {
    case UI_SCREEN_LEVEL:
        ui_level_update(&snap, now);
        break;
    case UI_SCREEN_SETTINGS:
        ui_settings_update(&snap);
        break;
    case UI_SCREEN_MOUNT_CAL:
        ui_mount_cal_update(&snap);
        break;
    case UI_SCREEN_SENSOR_CAL:
        ui_sensor_cal_update(&snap);
        break;
    default:
        break;
    }
}

esp_err_t ui_init(void)
{
    // bsp_display_lock: -1 waits forever (the BSP passes it on as int32_t).
    if (bsp_display_lock(-1) != ESP_OK) {
        ESP_LOGE(TAG, "display lock failed");
        return ESP_ERR_TIMEOUT;
    }
    if (s_started) {
        bsp_display_unlock();
        return ESP_OK;
    }

    ui_cfg_refresh();
    styles_init();
    s_screens[UI_SCREEN_LEVEL] = ui_level_create();
    s_screens[UI_SCREEN_SETTINGS] = ui_settings_create();
    s_screens[UI_SCREEN_MOUNT_CAL] = ui_mount_cal_create();
    s_screens[UI_SCREEN_SENSOR_CAL] = ui_sensor_cal_create();
    ui_overlays_create();

    lv_timer_t *timer = lv_timer_create(tick_cb, UI_TICK_MS, NULL);
    if (timer == NULL) {
        bsp_display_unlock();
        return ESP_ERR_NO_MEM;
    }
    for (lv_indev_t *in = lv_indev_get_next(NULL); in; in = lv_indev_get_next(in)) {
        lv_indev_add_event_cb(in, activity_cb, LV_EVENT_PRESSED, NULL);
    }

    s_current = UI_SCREEN_LEVEL;
    lv_screen_load(s_screens[UI_SCREEN_LEVEL]);
    tick_cb(timer);
    s_started = true;
    bsp_display_unlock();
    ESP_LOGI(TAG, "started");
    return ESP_OK;
}
