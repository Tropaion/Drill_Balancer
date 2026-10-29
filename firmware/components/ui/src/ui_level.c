#include <math.h>
#include <stdio.h>
#include "ui_priv.h"

#define RING_WIDTH 12
// One value step per drawn degree of the 270 degree ring (135 .. 45), so the
// value only changes, and the arc only redraws, when the angle visibly does.
#define RING_RANGE 270
// The crosshair: centred VIEW_DY above the screen centre, the numbers below.
#define VIEW_R 100
#define VIEW_DY (-40)
#define DOT_R 16
#define VALUE_DY 92
#define SUB_DY 136
#define HOLD_FOR_SETTINGS_MS 1000
#define TAP_MAX_MOVE_PX 24
#define ZERO_FLASH_MS 700

static struct {
    lv_obj_t *scr;
    lv_obj_t *root;         // everything that moves for burn-in protection
    lv_obj_t *ring;
    lv_obj_t *title;
    lv_obj_t *no_sensor;

    lv_obj_t *aim;          // crosshair and numbers, hidden without a sensor
    lv_obj_t *ring_tol;
    lv_obj_t *ring_near;
    lv_obj_t *dot;
    lv_obj_t *arrow;
    lv_obj_t *value;
    lv_obj_t *sub_yaw;      // the line below the number: yaw (WALL, ANGLE)
    lv_obj_t *sub_info;     // ... and a hint (WALL) or the current angle (ANGLE)

    lv_obj_t *bat_icon;
    lv_obj_t *bat_pct;
    lv_obj_t *charge;
    lv_obj_t *ble;

    bool imu_ok;

    // touch tracking (a tap that wakes a dimmed screen never gets here, ui.c)
    lv_point_t press_pt;
    uint32_t press_ms;
    bool press_used;        // gesture or long hold already consumed this touch
} s;

// --- touch ---

static bool moved_far(lv_indev_t *in)
{
    lv_point_t p;
    lv_indev_get_point(in, &p);
    int32_t dx = p.x - s.press_pt.x;
    int32_t dy = p.y - s.press_pt.y;
    return dx * dx + dy * dy > TAP_MAX_MOVE_PX * TAP_MAX_MOVE_PX;
}

static void screen_event_cb(lv_event_t *e)
{
    lv_indev_t *in = lv_indev_active();
    if (in == NULL) {
        return;
    }
    switch (lv_event_get_code(e)) {
    case LV_EVENT_PRESSED:
        lv_indev_get_point(in, &s.press_pt);
        s.press_ms = lv_tick_get();
        s.press_used = false;
        break;
    case LV_EVENT_PRESSING:
        // A deliberate one second hold, not LVGL's 400 ms long press: the
        // screen gets touched while handling the drill.
        if (!s.press_used && lv_tick_elaps(s.press_ms) >= HOLD_FOR_SETTINGS_MS &&
            !moved_far(in)) {
            s.press_used = true;
            ui_show(UI_SCREEN_SETTINGS);
        }
        break;
    case LV_EVENT_GESTURE: {
        lv_dir_t dir = lv_indev_get_gesture_dir(in);
        s.press_used = true;   // a swipe is never also a tap
        if (dir == LV_DIR_LEFT) {
            app_post_cmd(APP_CMD_NEXT_MODE, +1);
        } else if (dir == LV_DIR_RIGHT) {
            app_post_cmd(APP_CMD_NEXT_MODE, -1);
        }
        break;
    }
    case LV_EVENT_SHORT_CLICKED:
        if (!s.press_used && !moved_far(in) && s.imu_ok) {
            if (app_post_cmd(APP_CMD_ZERO, 0)) {
                ui_toast("ZERO", lv_color_hex(UI_C_GREEN), ZERO_FLASH_MS);
            }
        }
        break;
    default:
        break;
    }
}

// --- creation ---

static lv_obj_t *circle_new(lv_obj_t *parent, int32_t r, uint32_t border_color, int32_t border_w)
{
    lv_obj_t *c = ui_box_new(parent);
    lv_obj_set_size(c, 2 * r, 2 * r);
    lv_obj_set_style_radius(c, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_border_color(c, lv_color_hex(border_color), 0);
    lv_obj_set_style_border_width(c, border_w, 0);
    lv_obj_center(c);
    return c;
}

// One view for every mode. FLOOR: the dot is a bubble and moves to the
// higher side. WALL and ANGLE: the dot is where the bit points, up when it
// points too high, right when it has turned right since the zero; steer it
// into the centre. A ring is where the dot's edge touches it at that
// deviation, like the circle on a spirit level vial. near_deg is always a
// third of the view radius (ui_bubble_ppd()), so only the tolerance ring
// moves.
static void create_aim(void)
{
    s.aim = ui_box_new(s.root);
    lv_obj_set_size(s.aim, UI_W, UI_H);
    lv_obj_center(s.aim);

    lv_obj_t *view = circle_new(s.aim, VIEW_R, 0x5A5A5A, 2);
    lv_obj_align(view, LV_ALIGN_CENTER, 0, VIEW_DY);
    lv_obj_t *h = ui_pill_new(view, 2 * VIEW_R - 8, 2, UI_C_DARK);
    lv_obj_center(h);
    lv_obj_t *v = ui_pill_new(view, 2, 2 * VIEW_R - 8, UI_C_DARK);
    lv_obj_center(v);
    s.ring_near = circle_new(view, (int32_t)lroundf((float)VIEW_R / 3.0f) + DOT_R, 0x8A6200, 2);
    s.ring_tol = circle_new(view, 20, 0x00873F, 2);
    s.dot = ui_pill_new(view, 2 * DOT_R, 2 * DOT_R, UI_C_WHITE);
    lv_obj_set_align(s.dot, LV_ALIGN_CENTER);

    // Chevron + magnitude: the chevron gives the sign, so the number stays
    // narrow. Flex keeps the pair centred whatever the width.
    lv_obj_t *row = ui_box_new(s.aim);
    lv_obj_set_size(row, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(row, 10, 0);
    lv_obj_align(row, LV_ALIGN_CENTER, 0, VALUE_DY);
    s.arrow = ui_label_new(row, &lv_font_montserrat_48, UI_C_WHITE);
    s.value = ui_label_new(row, &lv_font_montserrat_48, UI_C_WHITE);

    lv_obj_t *sub = ui_box_new(s.aim);
    lv_obj_set_size(sub, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(sub, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(sub, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(sub, 16, 0);
    lv_obj_align(sub, LV_ALIGN_CENTER, 0, SUB_DY);
    s.sub_yaw = ui_label_new(sub, &lv_font_montserrat_24, UI_C_WHITE);
    s.sub_info = ui_label_new(sub, &lv_font_montserrat_24, UI_C_GREY);
}

lv_obj_t *ui_level_create(void)
{
    s.scr = ui_screen_new();
    lv_obj_add_event_cb(s.scr, screen_event_cb, LV_EVENT_ALL, NULL);

    s.root = ui_box_new(s.scr);
    lv_obj_set_size(s.root, UI_W, UI_H);
    lv_obj_set_align(s.root, LV_ALIGN_CENTER);

    // Status ring, open at the bottom for the status bar. It fills as the
    // deviation goes to zero.
    s.ring = ui_arc_new(s.root, 2 * UI_SAFE_R, RING_WIDTH, RING_RANGE, UI_C_DARK, UI_C_WHITE);
    lv_arc_set_bg_angles(s.ring, 135, 45);
    lv_obj_set_style_arc_rounded(s.ring, true, LV_PART_MAIN);

    s.title = ui_label_new(s.root, &lv_font_montserrat_24, UI_C_GREY);
    lv_obj_align(s.title, LV_ALIGN_TOP_MID, 0, 52);

    create_aim();

    s.no_sensor = ui_label_new(s.root, &lv_font_montserrat_48, UI_C_AMBER);
    lv_label_set_text_static(s.no_sensor, LV_SYMBOL_WARNING "\nNO SENSOR");
    lv_obj_align(s.no_sensor, LV_ALIGN_CENTER, 0, -10);
    lv_obj_add_flag(s.no_sensor, LV_OBJ_FLAG_HIDDEN);

    lv_obj_t *bar = ui_box_new(s.root);
    lv_obj_set_size(bar, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(bar, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(bar, 8, 0);
    lv_obj_align(bar, LV_ALIGN_BOTTOM_MID, 0, -38);
    s.bat_icon = ui_label_new(bar, &lv_font_montserrat_24, UI_C_GREY);
    s.bat_pct = ui_label_new(bar, &lv_font_montserrat_24, UI_C_GREY);
    s.charge = ui_label_new(bar, &lv_font_montserrat_24, UI_C_AMBER);
    lv_label_set_text_static(s.charge, LV_SYMBOL_CHARGE);
    s.ble = ui_label_new(bar, &lv_font_montserrat_24, UI_C_BLUE);
    lv_label_set_text_static(s.ble, LV_SYMBOL_BLUETOOTH);

    return s.scr;
}

// --- update ---

// The tolerance ring, inside the near ring since tol <= near. LVGL skips
// the resize while the size stays the same.
static void size_tol_ring(const settings_t *cfg)
{
    float ppd = ui_bubble_ppd(cfg->near_deg, (float)VIEW_R);
    int32_t rt = (int32_t)lroundf(cfg->tolerance_deg * ppd) + DOT_R;
    lv_obj_set_size(s.ring_tol, 2 * rt, 2 * rt);
}

// The line below the number (ui_aim_sub()).
static void update_sub(const level_reading_t *r, level_mode_t mode, bool zero_set)
{
    ui_aim_sub_t sub;
    ui_aim_sub(r, mode, zero_set, &sub);
    ui_set_text(s.sub_yaw, sub.yaw);
    ui_set_text_color(s.sub_yaw, sub.yaw_stale ? lv_color_hex(UI_C_AMBER)
                                               : ui_status_color(r->yaw_status));
    lv_obj_set_flag(s.sub_yaw, LV_OBJ_FLAG_HIDDEN, sub.yaw[0] == '\0');
    ui_set_text(s.sub_info, sub.info);
    lv_obj_set_flag(s.sub_info, LV_OBJ_FLAG_HIDDEN, sub.info[0] == '\0');
}

static void update_aim(const level_reading_t *r, level_mode_t mode, bool zero_set,
                       const settings_t *cfg)
{
    char buf[24];
    lv_color_t c = ui_status_color(r->status);
    size_tol_ring(cfg);

    float xd, yd;
    ui_dot_deg(r, &xd, &yd);
    int32_t x, y;
    ui_bubble_offset(xd, yd, cfg->near_deg, (float)VIEW_R, (float)DOT_R + 2.0f, &x, &y);
    lv_obj_set_pos(s.dot, x, y);
    ui_set_bg_color(s.dot, ui_status_color(ui_dot_status(r)));

    int arrow = ui_aim_arrow(r);
    ui_set_text(s.arrow, arrow > 0 ? LV_SYMBOL_UP : (arrow < 0 ? LV_SYMBOL_DOWN : ""));
    lv_obj_set_flag(s.arrow, LV_OBJ_FLAG_HIDDEN, arrow == 0);
    ui_set_text_color(s.arrow, c);
    ui_fmt_deg(buf, sizeof(buf), fabsf(r->deviation_deg), false);
    ui_set_text(s.value, buf);
    ui_set_text_color(s.value, c);
    update_sub(r, mode, zero_set);
}

static void update_status_bar(const app_snapshot_t *snap)
{
    static const char *const icons[] = {
        LV_SYMBOL_BATTERY_EMPTY, LV_SYMBOL_BATTERY_1, LV_SYMBOL_BATTERY_2,
        LV_SYMBOL_BATTERY_3, LV_SYMBOL_BATTERY_FULL,
    };
    const app_battery_t *b = &snap->battery;
    lv_obj_set_flag(s.bat_icon, LV_OBJ_FLAG_HIDDEN, !b->valid);
    lv_obj_set_flag(s.bat_pct, LV_OBJ_FLAG_HIDDEN, !b->valid);
    if (b->valid) {
        char buf[8];
        uint8_t pct = b->percent;   // 0..100 (app_battery_t)
        int lvl = ui_battery_level(pct);
        ui_set_text(s.bat_icon, icons[lvl]);
        uint32_t col = lvl == 0 ? UI_C_RED : UI_C_GREY;
        ui_set_text_color(s.bat_icon, lv_color_hex(col));
        snprintf(buf, sizeof(buf), "%u%%", (unsigned)pct);
        ui_set_text(s.bat_pct, buf);
        ui_set_text_color(s.bat_pct, lv_color_hex(col));
    }
    bool charging = b->valid && (b->charging || b->charge_done);
    lv_obj_set_flag(s.charge, LV_OBJ_FLAG_HIDDEN, !charging);
    if (charging) {
        ui_set_text_color(s.charge, lv_color_hex(b->charge_done ? UI_C_GREEN : UI_C_AMBER));
    }
    lv_obj_set_flag(s.ble, LV_OBJ_FLAG_HIDDEN, !snap->ble_connected);
}

void ui_level_update(const app_snapshot_t *snap, uint32_t now_ms)
{
    const settings_t *cfg = ui_cfg();
    const level_reading_t *r = &snap->reading;
    level_mode_t mode = r->mode < LEVEL_MODE_COUNT ? r->mode : LEVEL_MODE_WALL;

    s.imu_ok = snap->imu_ok;
    char title[32];
    ui_level_title(mode, cfg->target_deg, title, sizeof(title));
    ui_set_text(s.title, title);
    lv_obj_set_flag(s.no_sensor, LV_OBJ_FLAG_HIDDEN, snap->imu_ok);
    lv_obj_set_flag(s.aim, LV_OBJ_FLAG_HIDDEN, !snap->imu_ok);

    if (snap->imu_ok) {
        float f = ui_ring_fraction(r->deviation_deg, cfg->near_deg);
        lv_arc_set_value(s.ring, (int32_t)lroundf(f * (float)RING_RANGE));
        ui_set_arc_color(s.ring, ui_status_color(r->status));
        update_aim(r, mode, snap->zero_set, cfg);
    } else {
        lv_arc_set_value(s.ring, 0);
    }
    update_status_bar(snap);

    int32_t dx, dy;
    ui_burnin_offset(now_ms / UI_BURNIN_PERIOD_MS, UI_BURNIN_AMP_PX, &dx, &dy);
    lv_obj_set_pos(s.root, dx, dy);
}
