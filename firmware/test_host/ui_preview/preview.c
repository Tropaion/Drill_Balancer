// Host preview of the UI: runs the real ui sources on LVGL with a memory
// display and a scripted touch input, checks the touch behaviour and dumps
// every screen as raw RGB (rgb2png.py turns them into PNGs with the round
// mask drawn).
//
//   cmake --build build-test-x --target ui_preview_png
//
// Not a ctest: it needs LVGL and is for looking at, not for CI.

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "accel_cal.h"
#include "lvgl.h"
#include "preview_stubs.h"
#include "ui.h"
#include "ui_priv.h"

#define W UI_W
#define H UI_H
#define CX (W / 2)
#define CY (H / 2)

static uint8_t s_fb[W * H * 3];
static const char *s_out_dir = ".";
static int s_failures;

typedef struct {
    bool pressed;
    int32_t x, y;
} touch_t;

static touch_t s_touch;

// --- LVGL glue ---

static uint32_t tick_cb(void)
{
    return g_now_ms;
}

static void flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px_map)
{
    // XRGB8888 in memory: B, G, R, X
    int32_t w = lv_area_get_width(area);
    for (int32_t y = area->y1; y <= area->y2; y++) {
        const uint8_t *src = px_map + (size_t)(y - area->y1) * (size_t)w * 4u;
        for (int32_t x = area->x1; x <= area->x2; x++) {
            uint8_t *d = &s_fb[((size_t)y * W + (size_t)x) * 3u];
            d[0] = src[2];
            d[1] = src[1];
            d[2] = src[0];
            src += 4;
        }
    }
    lv_display_flush_ready(disp);
}

static void read_cb(lv_indev_t *indev, lv_indev_data_t *data)
{
    (void)indev;
    data->point.x = s_touch.x;
    data->point.y = s_touch.y;
    data->state = s_touch.pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
}

static void run(uint32_t ms)
{
    for (uint32_t t = 0; t < ms; t += 5) {
        g_now_ms += 5;
        lv_timer_handler();
    }
}

static void render(const char *name)
{
    run(200);   // at least two UI ticks
    lv_refr_now(NULL);
    char path[512];
    snprintf(path, sizeof(path), "%s/%s.rgb", s_out_dir, name);
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        perror(path);
        exit(1);
    }
    fwrite(s_fb, 1, sizeof(s_fb), f);
    fclose(f);
    printf("wrote %s\n", path);
}

// --- touch scripts ---

static void hold(int32_t x, int32_t y, uint32_t ms)
{
    s_touch = (touch_t){.pressed = true, .x = x, .y = y};
    run(ms);
    s_touch.pressed = false;
    run(100);
}

static void tap(int32_t x, int32_t y)
{
    hold(x, y, 80);
}

// Calibration guide buttons (ui_cal.c): Cancel / Retry on the left, Start /
// Finish / Done on the right.
static void tap_cal_left(void)
{
    tap(CX - UI_CAL_BUTTON_DX, CY + UI_CAL_BUTTON_DY);
}

static void tap_cal_right(void)
{
    tap(CX + UI_CAL_BUTTON_DX, CY + UI_CAL_BUTTON_DY);
}

static void swipe(int32_t x0, int32_t y0, int32_t x1, int32_t y1)
{
    s_touch = (touch_t){.pressed = true, .x = x0, .y = y0};
    run(30);
    for (int i = 1; i <= 10; i++) {
        s_touch.x = x0 + (x1 - x0) * i / 10;
        s_touch.y = y0 + (y1 - y0) * i / 10;
        run(15);
    }
    s_touch.pressed = false;
    run(100);
}

static void tap_obj(lv_obj_t *obj)
{
    lv_area_t a;
    lv_obj_get_coords(obj, &a);
    tap((a.x1 + a.x2) / 2, (a.y1 + a.y2) / 2);
}

// A slow vertical drag (no fling), then a pause so the snap settles.
static void drag_v(int32_t x, int32_t y0, int32_t y1)
{
    s_touch = (touch_t){.pressed = true, .x = x, .y = y0};
    run(30);
    for (int i = 1; i <= 20; i++) {
        s_touch.y = y0 + (y1 - y0) * i / 20;
        run(30);
    }
    run(200);
    s_touch.pressed = false;
    run(600);
}

#define CHECK(cond)                                                     \
    do {                                                                \
        if (cond) {                                                     \
            printf("  ok    %s\n", #cond);                              \
        } else {                                                        \
            printf("  FAIL  %s (line %d)\n", #cond, __LINE__);          \
            s_failures++;                                               \
        }                                                               \
    } while (0)

// Every clickable widget in the settings list that is shown (mostly) opaque
// must lie inside the visible circle: corners within the panel radius.
static int round_violations(void)
{
    int bad = 0;
    lv_obj_t *list = ui_settings_list();
    uint32_t n = lv_obj_get_child_count(list);
    for (uint32_t i = 0; i < n; i++) {
        lv_obj_t *row = lv_obj_get_child(list, (int32_t)i);
        lv_obj_t *kids[8];
        uint32_t k = 0;
        if (lv_obj_has_flag(row, LV_OBJ_FLAG_CLICKABLE)) {
            kids[k++] = row;
        }
        for (uint32_t j = 0; j < lv_obj_get_child_count(row) && k < 8; j++) {
            lv_obj_t *c = lv_obj_get_child(row, (int32_t)j);
            if (lv_obj_has_flag(c, LV_OBJ_FLAG_CLICKABLE)) {
                kids[k++] = c;
            }
        }
        if (lv_obj_get_style_opa_recursive(row, LV_PART_MAIN) < LV_OPA_80) {
            continue;
        }
        for (uint32_t j = 0; j < k; j++) {
            lv_area_t a;
            lv_obj_get_coords(kids[j], &a);
            const int32_t xs[2] = {a.x1, a.x2}, ys[2] = {a.y1, a.y2};
            for (int cx = 0; cx < 2; cx++) {
                for (int cy = 0; cy < 2; cy++) {
                    float dx = (float)(xs[cx] - CX), dy = (float)(ys[cy] - CY);
                    if (dx * dx + dy * dy > (float)(UI_VISIBLE_R * UI_VISIBLE_R)) {
                        printf("    outside the circle: row %u widget %u corner (%d,%d)\n",
                               (unsigned)i, (unsigned)j, (int)xs[cx], (int)ys[cy]);
                        bad++;
                    }
                }
            }
        }
    }
    return bad;
}

// --- states ---

static level_reading_t wall(float elev, level_status_t st, bool yaw_valid, float yaw,
                            level_status_t yst, bool stale)
{
    return (level_reading_t){
        .mode = LEVEL_MODE_WALL, .elevation_deg = elev, .deviation_deg = elev, .status = st,
        .yaw_valid = yaw_valid, .yaw_deg = yaw, .yaw_status = yst, .yaw_stale = stale,
    };
}

static level_reading_t floor_r(float bx, float by, level_status_t st)
{
    level_reading_t r = {.mode = LEVEL_MODE_FLOOR, .bubble_x_deg = bx, .bubble_y_deg = by,
                         .status = st};
    r.deviation_deg = sqrtf(bx * bx + by * by);
    return r;
}

static level_reading_t angle_r(float elev, float target, level_status_t st)
{
    return (level_reading_t){.mode = LEVEL_MODE_ANGLE, .elevation_deg = elev,
                             .deviation_deg = elev - target, .status = st};
}

static void base_state(void)
{
    preview_stubs_reset();
    g_snap.imu_ok = true;
    g_snap.battery = (app_battery_t){.valid = true, .percent = 87, .voltage_mv = 4010};
    g_snap.ble_enabled = true;
    g_snap.ble_connected = true;
    g_snap.reading = wall(0.2f, LEVEL_STATUS_ON_TARGET, true, 0.1f, LEVEL_STATUS_ON_TARGET,
                          false);
    ui_cfg_refresh();
}

static void touch_checks(void)
{
    printf("touch checks\n");
    base_state();
    ui_show(UI_SCREEN_LEVEL);
    run(200);

    tap(CX, CY);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 1);
    render("14_zero_flash");
    run(1000);

    // A tap that wakes a dimmed screen only brightens it: no zero, and
    // neither a swipe nor a hold acts.
    g_snap.power = APP_POWER_DIM;
    run(200);
    tap(CX, CY);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 1);
    swipe(330, 240, 120, 240);
    CHECK(preview_count_cmd(APP_CMD_NEXT_MODE, 0, false) == 0);
    hold(CX, CY, 1200);
    CHECK(ui_current() == UI_SCREEN_LEVEL);
    g_snap.power = APP_POWER_ACTIVE;
    run(200);
    tap(CX, CY);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 2);
    run(1000);

    swipe(330, 240, 120, 240);
    CHECK(preview_count_cmd(APP_CMD_NEXT_MODE, +1, true) == 1);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 2);   // a swipe is no tap
    swipe(120, 240, 330, 240);
    CHECK(preview_count_cmd(APP_CMD_NEXT_MODE, -1, true) == 1);

    // LVGL's 400 ms long press alone does not open settings
    hold(CX, CY, 600);
    CHECK(ui_current() == UI_SCREEN_LEVEL);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 2);
    hold(CX, CY, 1200);
    CHECK(ui_current() == UI_SCREEN_SETTINGS);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 2);

    // Tolerance "+" three times: one debounced save of 0.65
    lv_obj_t *row = lv_obj_get_child(ui_settings_list(), 2);
    lv_obj_t *plus = lv_obj_get_child(row, 2);
    tap_obj(plus);
    tap_obj(plus);
    tap_obj(plus);
    CHECK(g_settings_store_count == 0);
    run(1200);
    CHECK(g_settings_store_count == 1);
    CHECK(fabsf(g_settings.tolerance_deg - 0.65f) < 1e-4f);

    // A change made elsewhere (BLE) shows up while not editing
    g_settings.tolerance_deg = 1.5f;
    g_settings.near_deg = 3.0f;
    run(1200);
    lv_obj_t *value = lv_obj_get_child(row, 3);
    CHECK(strcmp(lv_label_get_text(value), "1.50\xC2\xB0") == 0);

    // A tap that wakes a dimmed settings screen does not step either
    g_snap.power = APP_POWER_DIM;
    run(200);
    tap_obj(plus);
    run(1200);
    CHECK(g_settings_store_count == 1);
    CHECK(strcmp(lv_label_get_text(value), "1.50\xC2\xB0") == 0);
    g_snap.power = APP_POWER_ACTIVE;
    run(200);

    // Edit, then sleep at once (control task, outside LVGL): the change
    // waiting for the debounce is stored first
    lv_obj_t *plus2 = lv_obj_get_child(row, 2);
    tap_obj(plus2);
    CHECK(g_settings_store_count == 1);
    // ... and a BLE write to another field of this screen while the edit
    // waits survives the save
    g_settings.brightness_pct = 60;
    ui_flush_pending_settings();
    CHECK(g_settings_store_count == 2);
    CHECK(fabsf(g_settings.tolerance_deg - 1.75f) < 1e-4f);
    CHECK(g_settings.brightness_pct == 60);
    CHECK(g_lock_depth == 0);
    ui_flush_pending_settings();   // nothing pending: no second write
    CHECK(g_settings_store_count == 2);

    // Edit, then leave at once: the pending change is saved on exit
    lv_obj_t *minus = lv_obj_get_child(row, 1);
    tap_obj(minus);
    swipe(120, 240, 330, 240);
    CHECK(ui_current() == UI_SCREEN_LEVEL);
    CHECK(g_settings_store_count == 3);
    CHECK(fabsf(g_settings.tolerance_deg - 1.50f) < 1e-4f);

    // The list scrolls from anywhere, not only from a button: drag up from
    // the value text of the centred row
    hold(CX, CY, 1200);
    CHECK(ui_current() == UI_SCREEN_SETTINGS);
    lv_obj_t *list = ui_settings_list();
    lv_obj_scroll_to_y(list, 0, LV_ANIM_OFF);
    run(100);
    lv_area_t va;
    lv_obj_get_coords(value, &va);
    int32_t y0 = lv_obj_get_scroll_y(list);
    drag_v((va.x1 + va.x2) / 2, (va.y1 + va.y2) / 2, (va.y1 + va.y2) / 2 - 150);
    CHECK(lv_obj_get_scroll_y(list) > y0 + 60);
    // ... also from a gap between two rows
    y0 = lv_obj_get_scroll_y(list);
    lv_area_t ra;
    lv_obj_get_coords(lv_obj_get_child(list, 5), &ra);
    drag_v(CX, ra.y1 - 15, ra.y1 - 15 - 150);
    CHECK(lv_obj_get_scroll_y(list) > y0 + 60);
    CHECK(preview_count_cmd(APP_CMD_ZERO, 0, false) == 2);
    CHECK(g_settings_store_count == 3);      // no stepper fired on the way
    CHECK(round_violations() == 0);
    ui_show(UI_SCREEN_LEVEL);

    // Mount calibration: nothing is collected before Start (the bit is
    // still being put into the hole); Finish only after the control task
    // started it
    ui_show(UI_SCREEN_MOUNT_CAL);
    run(200);
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_START, 0, false) == 0);
    tap_cal_right();   // Start
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_START, 0, false) == 1);
    run(200);
    tap_cal_right();
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_FINISH, 0, false) == 0);
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_MOUNT, .samples = 50, .last_result = -1};
    run(200);
    tap_cal_right();
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_FINISH, 0, false) == 1);
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_MOUNT,
                                    .last_result = LEVEL_CAL_OK};
    run(200);
    tap_cal_right();   // Done
    CHECK(ui_current() == UI_SCREEN_LEVEL);

    // Cancel before Start leaves without posting anything
    ui_show(UI_SCREEN_MOUNT_CAL);
    run(200);
    tap_cal_left();
    CHECK(ui_current() == UI_SCREEN_SETTINGS);
    CHECK(preview_count_cmd(APP_CMD_CAL_CANCEL, 0, false) == 0);
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_START, 0, false) == 1);
    ui_show(UI_SCREEN_LEVEL);
    CHECK(g_lock_depth == 0);
}

int main(int argc, char **argv)
{
    if (argc > 1) {
        s_out_dir = argv[1];
    }
    lv_init();
    lv_tick_set_cb(tick_cb);
    lv_display_t *disp = lv_display_create(W, H);
    static uint8_t buf[W * H * 4];
    lv_display_set_buffers(disp, buf, NULL, sizeof(buf), LV_DISPLAY_RENDER_MODE_FULL);
    lv_display_set_flush_cb(disp, flush_cb);
    lv_indev_t *indev = lv_indev_create();
    lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(indev, read_cb);

    preview_stubs_reset();
    if (ui_init() != ESP_OK) {
        printf("ui_init failed\n");
        return 1;
    }

    // --- level screen ---
    base_state();
    ui_show(UI_SCREEN_LEVEL);
    render("01_wall_on_target");

    base_state();
    g_snap.reading = wall(-1.4f, LEVEL_STATUS_NEAR, true, 1.2f, LEVEL_STATUS_NEAR, false);
    g_snap.battery.charging = true;
    g_snap.battery.vbus_present = true;
    render("02_wall_near_charging");

    base_state();
    g_snap.reading = wall(12.3f, LEVEL_STATUS_OFF, true, -6.4f, LEVEL_STATUS_OFF, true);
    g_snap.ble_connected = false;
    render("03_wall_off_yaw_stale");

    base_state();
    g_snap.reading = wall(-88.8f, LEVEL_STATUS_OFF, false, 0.0f, LEVEL_STATUS_OFF, false);
    render("04_wall_no_yaw_worst_width");

    base_state();
    g_snap.reading = floor_r(0.2f, -0.1f, LEVEL_STATUS_ON_TARGET);
    render("05_floor_on_target");

    base_state();
    g_snap.reading = floor_r(1.2f, 0.8f, LEVEL_STATUS_NEAR);
    render("06_floor_near");

    base_state();
    g_snap.reading = floor_r(-8.0f, 5.0f, LEVEL_STATUS_OFF);
    render("07_floor_off_clamped");

    base_state();
    g_snap.reading = angle_r(46.3f, 45.0f, LEVEL_STATUS_NEAR);
    render("08_angle_near");

    base_state();
    g_snap.reading = angle_r(-12.0f, 45.0f, LEVEL_STATUS_OFF);
    render("09_angle_off");

    base_state();
    g_snap.imu_ok = false;
    render("10_no_sensor");

    // --- overlays ---
    base_state();
    g_snap.battery.percent = 7;
    g_snap.reading = floor_r(0.3f, 0.1f, LEVEL_STATUS_ON_TARGET);
    render("11_low_battery_floor");

    base_state();
    g_snap.passkey_pending = true;
    g_snap.passkey = 42917;
    render("12_passkey");

    base_state();
    g_snap.ota_active = true;
    g_snap.ota_percent = 40;
    render("13_ota_40");

    // --- touch behaviour (also renders 14) ---
    touch_checks();

    // --- burn-in shift ---
    base_state();
    ui_show(UI_SCREEN_LEVEL);
    g_now_ms = (g_now_ms / UI_BURNIN_PERIOD_MS + 1) * UI_BURNIN_PERIOD_MS + 10;
    render("15_wall_burnin_shifted");

    // --- settings ---
    base_state();
    ui_show(UI_SCREEN_SETTINGS);
    render("20_settings_top");
    CHECK(round_violations() == 0);
    lv_obj_scroll_to_y(ui_settings_list(), 420, LV_ANIM_OFF);
    render("21_settings_mid");
    CHECK(round_violations() == 0);
    lv_obj_scroll_to_y(ui_settings_list(), 900, LV_ANIM_OFF);
    render("22_settings_lower");
    CHECK(round_violations() == 0);
    // Snapped onto a row, as after a real drag
    lv_obj_scroll_to_view(lv_obj_get_child(ui_settings_list(), 6), LV_ANIM_OFF);
    render("25_settings_snapped_row");
    CHECK(round_violations() == 0);
    lv_obj_scroll_to_y(ui_settings_list(), 5000, LV_ANIM_OFF);
    render("23_settings_bottom");
    CHECK(round_violations() == 0);
    ui_settings_confirm_reset();
    render("24_settings_confirm_reset");

    // --- calibration guides ---
    base_state();
    ui_show(UI_SCREEN_MOUNT_CAL);
    render("29_mount_cal_ready");
    tap_cal_right();   // Start
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_MOUNT, .samples = 143, .last_result = -1};
    render("30_mount_cal_collect");
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_MOUNT, .samples = 151, .last_result = -1};
    run(200);
    tap_cal_right();
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_MOUNT,
                                    .last_result = LEVEL_CAL_OK};
    render("31_mount_cal_ok");
    tap_cal_left();   // Retry
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_MOUNT, .samples = 12, .last_result = 0,
                                    .last_kind = APP_CAL_MOUNT};
    run(200);
    tap_cal_right();
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_MOUNT,
                                    .last_result = LEVEL_CAL_TOO_LITTLE_ROLL};
    render("32_mount_cal_too_little_roll");
    tap_cal_left();   // Retry: starts at once, the bit is in the hole
    CHECK(preview_count_cmd(APP_CMD_MOUNT_CAL_START, 0, false) == 3);
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_MOUNT, .samples = 80, .last_result = -1,
                                    .last_kind = APP_CAL_MOUNT};
    run(200);
    tap_cal_right();
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_MOUNT,
                                    .last_result = APP_CAL_RESULT_NOT_STORED};
    render("36_mount_cal_not_saved");

    base_state();
    ui_show(UI_SCREEN_SENSOR_CAL);
    g_snap.cal = (app_cal_status_t){
        .kind = APP_CAL_ACCEL, .samples = 4, .last_result = -1,
        .accel_coverage = ACCEL_CAL_DIR_POS_Z | ACCEL_CAL_DIR_NEG_Y | ACCEL_CAL_DIR_POS_X};
    render("33_sensor_cal_partial");
    g_snap.cal.accel_coverage = ACCEL_CAL_DIR_ALL;
    g_snap.cal.samples = 9;
    render("34_sensor_cal_all_covered");
    tap_cal_right();
    CHECK(preview_count_cmd(APP_CMD_ACCEL_CAL_FINISH, 0, false) == 1);
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_ACCEL,
                                    .last_result = ACCEL_CAL_OUT_OF_RANGE};
    render("35_sensor_cal_out_of_range");
    tap_cal_left();   // Retry
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_ACCEL, .samples = 6, .last_result = -1,
                                    .accel_coverage = ACCEL_CAL_DIR_ALL};
    run(200);
    tap_cal_right();
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_ACCEL,
                                    .last_result = APP_CAL_RESULT_OK_MOUNT_DROPPED};
    render("37_sensor_cal_ok_redo_mount");
    tap_cal_left();   // Retry
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_ACCEL, .samples = 6, .last_result = -1,
                                    .accel_coverage = ACCEL_CAL_DIR_ALL};
    run(200);
    tap_cal_right();
    // No mount calibration existed, so none was reset.
    g_snap.cal = (app_cal_status_t){.kind = APP_CAL_IDLE, .last_kind = APP_CAL_ACCEL,
                                    .last_result = ACCEL_CAL_OK};
    render("38_sensor_cal_ok_first");

    printf("%s: %d check failure(s)\n", s_failures ? "FAILED" : "OK", s_failures);
    return s_failures ? 1 : 0;
}
