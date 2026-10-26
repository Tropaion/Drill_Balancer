#include <stdio.h>
#include "accel_cal.h"
#include "ui_priv.h"

// Both guides share one flow: START, collect, FINISH, wait for the control
// task to publish the result, show it. The sensor guide starts on entry
// (poses only count while the device is still). The mount guide first shows
// its instructions with a Start button: samples taken while the bit is
// still being put into the hole would fail the fit. Retry starts at once,
// since the bit is already in the hole then.
#define SOLVE_TIMEOUT_MS 3000
#define TILE_W 150
#define TILE_H 50
#define TILE_GAP 8

typedef enum {
    PHASE_READY = 0,        // instructions, waiting for Start (mount only)
    PHASE_COLLECT,
    PHASE_SOLVING,
    PHASE_RESULT,
} phase_t;

typedef struct {
    app_cal_kind_t kind;
    app_cmd_id_t start_cmd;
    app_cmd_id_t finish_cmd;
    const char *(*result_text)(int result);
    const char *ready_text; // instructions before / while collecting
    const char *collect_text;

    lv_obj_t *scr;
    lv_obj_t *body;         // the collect view (instructions / tiles)
    lv_obj_t *body_text;    // instructions label in body, or NULL
    lv_obj_t *count;
    lv_obj_t *result;
    lv_obj_t *left;         // Cancel / Retry
    lv_obj_t *left_text;
    lv_obj_t *right;        // Start / Finish / Done
    lv_obj_t *right_text;

    phase_t phase;
    bool seen_active;       // the control task has started this calibration
    uint8_t refused;        // app_cal_status_t.refused when the start was posted
    uint32_t finish_ms;
} cal_ui_t;

static cal_ui_t s_mount = {
    .kind = APP_CAL_MOUNT,
    .start_cmd = APP_CMD_MOUNT_CAL_START,
    .finish_cmd = APP_CMD_MOUNT_CAL_FINISH,
    .result_text = ui_mount_result_text,
    .ready_text = "Put the bit in a hole\n(horizontal or tilted),\nthen tap Start.",
    .collect_text = "Roll the drill slowly back\nand forth, at least 45\xC2\xB0\n"
                    "each way. Keep the bit\nin the hole.",
};

static cal_ui_t s_sensor = {
    .kind = APP_CAL_ACCEL,
    .start_cmd = APP_CMD_ACCEL_CAL_START,
    .finish_cmd = APP_CMD_ACCEL_CAL_FINISH,
    .result_text = ui_accel_result_text,
};

static lv_obj_t *s_tiles[UI_POSE_COUNT];
static lv_obj_t *s_tile_text[UI_POSE_COUNT];

static void set_phase(cal_ui_t *c, phase_t phase)
{
    c->phase = phase;
    lv_obj_set_flag(c->body, LV_OBJ_FLAG_HIDDEN, phase == PHASE_RESULT);
    lv_obj_set_flag(c->count, LV_OBJ_FLAG_HIDDEN, phase == PHASE_RESULT || phase == PHASE_READY);
    lv_obj_set_flag(c->result, LV_OBJ_FLAG_HIDDEN, phase != PHASE_RESULT);
    if (c->body_text) {
        ui_set_text(c->body_text, phase == PHASE_READY ? c->ready_text : c->collect_text);
    }
    ui_set_text(c->left_text, phase == PHASE_RESULT ? "Retry" : "Cancel");
    ui_set_text(c->right_text, phase == PHASE_RESULT    ? "Done"
                               : phase == PHASE_SOLVING ? "..."
                               : phase == PHASE_READY   ? "Start"
                                                        : "Finish");
    lv_obj_set_state(c->left, LV_STATE_DISABLED, phase == PHASE_SOLVING);
    lv_obj_set_state(c->right, LV_STATE_DISABLED, phase != PHASE_RESULT && phase != PHASE_READY);
}

// OTA or a recording session has the device (app_excl_t): the result view
// says so (Retry tries again, Done goes back).
static void show_refused(cal_ui_t *c, uint8_t excl)
{
    ui_set_text(c->result, ui_cal_refused_text(excl));
    ui_set_text_color(c->result, lv_color_hex(UI_C_AMBER));
    set_phase(c, PHASE_RESULT);
}

// A start while another one has the device is refused here; one that slips
// past (the owner took it in between) is refused by the control task,
// which counts it (app_cal_status_t.refused, update()).
static void start(cal_ui_t *c)
{
    app_snapshot_t snap;
    app_state_get(&snap);
    if (ui_cal_start_refused(snap.excl)) {
        show_refused(c, snap.excl);
        return;
    }
    c->seen_active = false;
    c->refused = snap.cal.refused;
    set_phase(c, PHASE_COLLECT);
    app_post_cmd(c->start_cmd, 0);
}

static void show_result(cal_ui_t *c, const app_cal_status_t *cal)
{
    bool have = cal->last_kind == c->kind && ui_cal_result_known(cal->last_result);
    ui_set_text(c->result, have ? c->result_text(cal->last_result) : "No result.\nPlease try again.");
    ui_set_text_color(c->result, lv_color_hex(have && ui_cal_result_ok(cal->last_result)
                                                  ? UI_C_GREEN
                                                  : UI_C_AMBER));
    set_phase(c, PHASE_RESULT);
}

static void left_cb(lv_event_t *e)
{
    cal_ui_t *c = lv_event_get_user_data(e);
    if (c->phase == PHASE_RESULT) {
        start(c);
    } else if (c->phase == PHASE_READY) {
        ui_show(UI_SCREEN_SETTINGS);   // nothing started yet
    } else if (c->phase == PHASE_COLLECT) {
        app_post_cmd(APP_CMD_CAL_CANCEL, 0);
        ui_show(UI_SCREEN_SETTINGS);
    }
}

static void right_cb(lv_event_t *e)
{
    cal_ui_t *c = lv_event_get_user_data(e);
    if (c->phase == PHASE_RESULT) {
        ui_show(UI_SCREEN_LEVEL);
    } else if (c->phase == PHASE_READY) {
        start(c);
    } else if (c->phase == PHASE_COLLECT && c->seen_active) {
        if (app_post_cmd(c->finish_cmd, 0)) {
            c->finish_ms = lv_tick_get();
            set_phase(c, PHASE_SOLVING);
        }
    }
}

static void update(cal_ui_t *c, const app_snapshot_t *snap, bool can_finish)
{
    const app_cal_status_t *cal = &snap->cal;
    bool active = cal->kind == c->kind;

    switch (c->phase) {
    case PHASE_COLLECT:
        if (active) {
            c->seen_active = true;
        } else if (c->seen_active) {
            // Ended elsewhere (e.g. BLE cancel or a power state change).
            show_result(c, cal);
            return;
        } else if (cal->refused != c->refused) {
            show_refused(c, snap->excl);    // the control task refused the start
            return;
        }
        lv_obj_set_state(c->right, LV_STATE_DISABLED, !(c->seen_active && can_finish));
        break;
    case PHASE_SOLVING:
        if (!active || lv_tick_elaps(c->finish_ms) > SOLVE_TIMEOUT_MS) {
            show_result(c, cal);
        }
        break;
    default:
        break;
    }
}

static void create_common(cal_ui_t *c, const char *title)
{
    c->scr = ui_screen_new();
    lv_obj_t *t = ui_label_new(c->scr, &lv_font_montserrat_24, UI_C_GREY);
    lv_label_set_text_static(t, title);
    lv_obj_align(t, LV_ALIGN_TOP_MID, 0, 56);

    c->body = ui_box_new(c->scr);
    lv_obj_set_size(c->body, UI_W, 230);
    lv_obj_align(c->body, LV_ALIGN_TOP_MID, 0, 94);

    c->count = ui_label_new(c->scr, &lv_font_montserrat_24, UI_C_WHITE);
    lv_obj_align(c->count, LV_ALIGN_CENTER, 0, 62);

    c->result = ui_label_new(c->scr, &lv_font_montserrat_24, UI_C_GREEN);
    lv_obj_set_width(c->result, 340);
    lv_label_set_long_mode(c->result, LV_LABEL_LONG_MODE_WRAP);
    lv_obj_align(c->result, LV_ALIGN_CENTER, 0, -30);

    c->left = ui_button_new(c->scr, "Cancel", 140, 64, left_cb, c);
    lv_obj_align(c->left, LV_ALIGN_CENTER, -UI_CAL_BUTTON_DX, UI_CAL_BUTTON_DY);
    c->left_text = lv_obj_get_child(c->left, 0);
    c->right = ui_button_new(c->scr, "Finish", 140, 64, right_cb, c);
    lv_obj_align(c->right, LV_ALIGN_CENTER, UI_CAL_BUTTON_DX, UI_CAL_BUTTON_DY);
    lv_obj_set_style_border_color(c->right, lv_color_hex(UI_C_GREEN), 0);
    c->right_text = lv_obj_get_child(c->right, 0);
    set_phase(c, PHASE_COLLECT);
}

// --- mount ---

lv_obj_t *ui_mount_cal_create(void)
{
    create_common(&s_mount, "Mount calibration");
    lv_obj_t *l = ui_label_new(s_mount.body, &lv_font_montserrat_24, UI_C_WHITE);
    lv_obj_set_width(l, 350);
    lv_label_set_long_mode(l, LV_LABEL_LONG_MODE_WRAP);
    lv_obj_align(l, LV_ALIGN_TOP_MID, 0, 0);
    s_mount.body_text = l;
    set_phase(&s_mount, PHASE_READY);
    return s_mount.scr;
}

void ui_mount_cal_on_show(void)
{
    // Collecting starts with the Start button, once the bit is in the hole.
    set_phase(&s_mount, PHASE_READY);
    ui_set_text(s_mount.count, "");
}

void ui_mount_cal_update(const app_snapshot_t *snap)
{
    char buf[24];
    if (snap->cal.kind == APP_CAL_MOUNT) {
        snprintf(buf, sizeof(buf), "%u samples", (unsigned)snap->cal.samples);
        ui_set_text(s_mount.count, buf);
    }
    update(&s_mount, snap, true);
}

// --- sensor ---

lv_obj_t *ui_sensor_cal_create(void)
{
    create_common(&s_sensor, "Sensor calibration");
    // The tiles carry the progress; the count is secondary here.
    lv_obj_set_style_text_font(s_sensor.count, &lv_font_montserrat_16, 0);
    lv_obj_set_style_text_color(s_sensor.count, lv_color_hex(UI_C_GREY), 0);
    lv_obj_align(s_sensor.count, LV_ALIGN_CENTER, 0, 72);
    lv_obj_t *hint = ui_label_new(s_sensor.body, &lv_font_montserrat_16, UI_C_GREY);
    lv_label_set_text_static(hint, "Hold still in each position");
    lv_obj_align(hint, LV_ALIGN_TOP_MID, 0, 0);

    lv_obj_t *grid = ui_box_new(s_sensor.body);
    lv_obj_set_size(grid, 2 * TILE_W + 12, 3 * TILE_H + 2 * TILE_GAP);
    lv_obj_align(grid, LV_ALIGN_TOP_MID, 0, 28);
    lv_obj_set_flex_flow(grid, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_style_pad_column(grid, 12, 0);
    lv_obj_set_style_pad_row(grid, TILE_GAP, 0);
    for (int i = 0; i < UI_POSE_COUNT; i++) {
        lv_obj_t *t = ui_box_new(grid);
        lv_obj_set_size(t, TILE_W, TILE_H);
        lv_obj_set_style_radius(t, 14, 0);
        lv_obj_set_style_border_width(t, 2, 0);
        lv_obj_set_style_border_color(t, lv_color_hex(0x5A5A5A), 0);
        lv_obj_set_style_bg_opa(t, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(t, lv_color_black(), 0);
        lv_obj_t *l = ui_label_new(t, &lv_font_montserrat_16, UI_C_WHITE);
        lv_label_set_text_static(l, ui_poses[i].label);
        lv_obj_center(l);
        s_tiles[i] = t;
        s_tile_text[i] = l;
    }
    return s_sensor.scr;
}

void ui_sensor_cal_on_show(void)
{
    start(&s_sensor);
    ui_set_text(s_sensor.count, "");
}

void ui_sensor_cal_update(const app_snapshot_t *snap)
{
    char buf[24];
    bool active = snap->cal.kind == APP_CAL_ACCEL;
    uint8_t cov = active ? snap->cal.accel_coverage : 0;
    if (s_sensor.phase == PHASE_COLLECT) {
        for (int i = 0; i < UI_POSE_COUNT; i++) {
            bool done = (cov & ui_poses[i].bit) != 0;
            ui_set_bg_color(s_tiles[i], done ? lv_color_hex(UI_C_GREEN) : lv_color_black());
            ui_set_border_color(s_tiles[i], lv_color_hex(done ? UI_C_GREEN : 0x5A5A5A));
            ui_set_text_color(s_tile_text[i], done ? lv_color_black() : lv_color_white());
        }
        if (active) {
            snprintf(buf, sizeof(buf), "%u / 6 sides", (unsigned)__builtin_popcount(cov));
            ui_set_text(s_sensor.count, buf);
        }
    }
    update(&s_sensor, snap, (cov & ACCEL_CAL_DIR_ALL) == ACCEL_CAL_DIR_ALL);
}
