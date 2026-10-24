#include <math.h>
#include <stdio.h>
#include "ui_priv.h"

#define RING_WIDTH 12
// One value step per drawn degree of the 270 degree ring (135 .. 45), so the
// value only changes, and the arc only redraws, when the angle visibly does.
#define RING_RANGE 270
#define BUBBLE_VIEW_R 110
#define BUBBLE_R 18
#define YAW_HALF_W 120
#define HOLD_FOR_SETTINGS_MS 1000
#define TAP_MAX_MOVE_PX 24
#define ZERO_FLASH_MS 700

static struct {
    lv_obj_t *scr;
    lv_obj_t *root;         // everything that moves for burn-in protection
    lv_obj_t *ring;
    lv_obj_t *title;
    lv_obj_t *no_sensor;

    lv_obj_t *wall;
    lv_obj_t *wall_arrow;
    lv_obj_t *wall_value;
    lv_obj_t *yaw;
    lv_obj_t *yaw_marker;
    lv_obj_t *yaw_text;
    lv_obj_t *yaw_hint;

    lv_obj_t *floor;
    lv_obj_t *bubble_view;
    lv_obj_t *ring_tol;
    lv_obj_t *ring_near;
    lv_obj_t *bubble;
    lv_obj_t *floor_value;

    lv_obj_t *angle;
    lv_obj_t *angle_target;
    lv_obj_t *angle_value;
    lv_obj_t *angle_diff;

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

static lv_obj_t *group_new(lv_obj_t *parent)
{
    lv_obj_t *g = ui_box_new(parent);
    lv_obj_set_size(g, UI_W, UI_H);
    lv_obj_center(g);
    lv_obj_add_flag(g, LV_OBJ_FLAG_HIDDEN);
    return g;
}

static void create_wall(void)
{
    s.wall = group_new(s.root);

    // Chevron + magnitude: the chevron gives the sign, so the number stays
    // narrow. Flex keeps the pair centred whatever the width.
    lv_obj_t *row = ui_box_new(s.wall);
    lv_obj_set_size(row, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(row, 14, 0);
    lv_obj_align(row, LV_ALIGN_CENTER, 0, -24);
    s.wall_arrow = ui_label_new(row, &lv_font_montserrat_48, UI_C_WHITE);
    s.wall_value = ui_label_new(row, &ui_font_num112, UI_C_WHITE);

    s.yaw = ui_box_new(s.wall);
    lv_obj_set_size(s.yaw, 2 * YAW_HALF_W + 40, 110);
    lv_obj_align(s.yaw, LV_ALIGN_CENTER, 0, 92);
    lv_obj_t *bar = ui_pill_new(s.yaw, 2 * YAW_HALF_W, 6, 0x4A4A4A);
    lv_obj_align(bar, LV_ALIGN_TOP_MID, 0, 15);
    lv_obj_t *tick = ui_pill_new(s.yaw, 4, 26, UI_C_GREY);
    lv_obj_align(tick, LV_ALIGN_TOP_MID, 0, 5);
    s.yaw_marker = ui_pill_new(s.yaw, 14, 36, UI_C_WHITE);
    lv_obj_align(s.yaw_marker, LV_ALIGN_TOP_MID, 0, 0);
    s.yaw_text = ui_label_new(s.yaw, &lv_font_montserrat_24, UI_C_WHITE);
    lv_obj_align(s.yaw_text, LV_ALIGN_TOP_MID, 0, 42);
    s.yaw_hint = ui_label_new(s.yaw, &lv_font_montserrat_16, UI_C_AMBER);
    lv_label_set_text_static(s.yaw_hint, "Yaw drifts: tap to re-zero");
    lv_obj_align(s.yaw_hint, LV_ALIGN_TOP_MID, 0, 74);
}

static void create_floor(void)
{
    s.floor = group_new(s.root);

    s.bubble_view = circle_new(s.floor, BUBBLE_VIEW_R, 0x5A5A5A, 2);
    lv_obj_align(s.bubble_view, LV_ALIGN_CENTER, 0, -22);
    lv_obj_t *h = ui_pill_new(s.bubble_view, 2 * BUBBLE_VIEW_R - 8, 2, UI_C_DARK);
    lv_obj_center(h);
    lv_obj_t *v = ui_pill_new(s.bubble_view, 2, 2 * BUBBLE_VIEW_R - 8, UI_C_DARK);
    lv_obj_center(v);
    // A ring is where the bubble's edge touches it at that deviation, like
    // the circle on a spirit level vial. near_deg is always a third of the
    // view radius (ui_bubble_ppd()), so only the tolerance ring moves.
    s.ring_near = circle_new(s.bubble_view,
                             (int32_t)lroundf((float)BUBBLE_VIEW_R / 3.0f) + BUBBLE_R, 0x8A6200, 2);
    s.ring_tol = circle_new(s.bubble_view, 20, 0x00873F, 2);
    s.bubble = ui_pill_new(s.bubble_view, 2 * BUBBLE_R, 2 * BUBBLE_R, UI_C_WHITE);
    lv_obj_set_align(s.bubble, LV_ALIGN_CENTER);

    s.floor_value = ui_label_new(s.floor, &lv_font_montserrat_48, UI_C_WHITE);
    lv_obj_align(s.floor_value, LV_ALIGN_CENTER, 0, 124);
}

static void create_angle(void)
{
    s.angle = group_new(s.root);
    s.angle_target = ui_label_new(s.angle, &lv_font_montserrat_24, UI_C_GREY);
    lv_obj_align(s.angle_target, LV_ALIGN_CENTER, 0, -112);
    s.angle_value = ui_label_new(s.angle, &ui_font_num112, UI_C_WHITE);
    lv_obj_align(s.angle_value, LV_ALIGN_CENTER, 0, -24);
    s.angle_diff = ui_label_new(s.angle, &lv_font_montserrat_48, UI_C_WHITE);
    lv_obj_align(s.angle_diff, LV_ALIGN_CENTER, 0, 76);
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

    create_wall();
    create_floor();
    create_angle();

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

static void update_wall(const level_reading_t *r, const settings_t *cfg)
{
    char buf[24];
    lv_color_t c = ui_status_color(r->status);

    int arrow = ui_wall_arrow(r);
    ui_set_text(s.wall_arrow, arrow > 0 ? LV_SYMBOL_UP : (arrow < 0 ? LV_SYMBOL_DOWN : ""));
    lv_obj_set_flag(s.wall_arrow, LV_OBJ_FLAG_HIDDEN, arrow == 0);
    ui_set_text_color(s.wall_arrow, c);
    ui_fmt_deg(buf, sizeof(buf), fabsf(r->elevation_deg), false);
    ui_set_text(s.wall_value, buf);
    ui_set_text_color(s.wall_value, c);

    lv_obj_set_flag(s.yaw, LV_OBJ_FLAG_HIDDEN, !r->yaw_valid);
    if (!r->yaw_valid) {
        return;
    }
    lv_color_t yc = ui_status_color(r->yaw_status);
    lv_obj_set_pos(s.yaw_marker, ui_yaw_offset(r->yaw_deg, cfg->near_deg, YAW_HALF_W), 0);
    ui_set_bg_color(s.yaw_marker, yc);
    int32_t t = ui_tenths(r->yaw_deg);
    char deg[16];
    ui_fmt_deg(deg, sizeof(deg), fabsf(r->yaw_deg), false);
    snprintf(buf, sizeof(buf), "%s %s", t > 0 ? "R" : (t < 0 ? "L" : " "), deg);
    ui_set_text(s.yaw_text, t == 0 ? deg : buf);
    ui_set_text_color(s.yaw_text, yc);
    lv_obj_set_flag(s.yaw_hint, LV_OBJ_FLAG_HIDDEN, !r->yaw_stale);
}

// The tolerance ring, inside the near ring since tol <= near. LVGL skips
// the resize while the size stays the same.
static void size_tol_ring(const settings_t *cfg)
{
    float ppd = ui_bubble_ppd(cfg->near_deg, (float)BUBBLE_VIEW_R);
    int32_t rt = (int32_t)lroundf(cfg->tolerance_deg * ppd) + BUBBLE_R;
    lv_obj_set_size(s.ring_tol, 2 * rt, 2 * rt);
}

static void update_floor(const level_reading_t *r, const settings_t *cfg)
{
    char buf[24];
    lv_color_t c = ui_status_color(r->status);
    size_tol_ring(cfg);

    int32_t x, y;
    ui_bubble_offset(r->bubble_x_deg, r->bubble_y_deg, cfg->near_deg, (float)BUBBLE_VIEW_R,
                     (float)BUBBLE_R + 2.0f, &x, &y);
    lv_obj_set_pos(s.bubble, x, y);
    ui_set_bg_color(s.bubble, c);
    ui_fmt_deg(buf, sizeof(buf), r->deviation_deg, false);
    ui_set_text(s.floor_value, buf);
    ui_set_text_color(s.floor_value, c);
}

static void update_angle(const level_reading_t *r, const settings_t *cfg)
{
    char buf[32];
    char deg[16];
    ui_fmt_deg(deg, sizeof(deg), cfg->target_deg, false);
    snprintf(buf, sizeof(buf), "TARGET  %s", deg);
    ui_set_text(s.angle_target, buf);
    ui_fmt_deg(buf, sizeof(buf), r->elevation_deg, false);
    ui_set_text(s.angle_value, buf);
    ui_fmt_deg(buf, sizeof(buf), r->deviation_deg, true);
    ui_set_text(s.angle_diff, buf);
    ui_set_text_color(s.angle_diff, ui_status_color(r->status));
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
    ui_set_text(s.title, ui_mode_title(mode));
    lv_obj_set_flag(s.no_sensor, LV_OBJ_FLAG_HIDDEN, snap->imu_ok);
    lv_obj_set_flag(s.wall, LV_OBJ_FLAG_HIDDEN, !snap->imu_ok || mode != LEVEL_MODE_WALL);
    lv_obj_set_flag(s.floor, LV_OBJ_FLAG_HIDDEN, !snap->imu_ok || mode != LEVEL_MODE_FLOOR);
    lv_obj_set_flag(s.angle, LV_OBJ_FLAG_HIDDEN, !snap->imu_ok || mode != LEVEL_MODE_ANGLE);

    if (snap->imu_ok) {
        float f = ui_ring_fraction(r->deviation_deg, cfg->near_deg);
        lv_arc_set_value(s.ring, (int32_t)lroundf(f * (float)RING_RANGE));
        ui_set_arc_color(s.ring, ui_status_color(r->status));
        switch (mode) {
        case LEVEL_MODE_FLOOR:
            update_floor(r, cfg);
            break;
        case LEVEL_MODE_ANGLE:
            update_angle(r, cfg);
            break;
        default:
            update_wall(r, cfg);
            break;
        }
    } else {
        lv_arc_set_value(s.ring, 0);
    }
    update_status_bar(snap);

    int32_t dx, dy;
    ui_burnin_offset(now_ms / UI_BURNIN_PERIOD_MS, UI_BURNIN_AMP_PX, &dx, &dy);
    lv_obj_set_pos(s.root, dx, dy);
}
