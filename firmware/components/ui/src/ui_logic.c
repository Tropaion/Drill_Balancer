#include <math.h>
#include <stdio.h>
#include "accel_cal.h"
#include "ui_logic.h"

#define DEG_SIGN "\xC2\xB0"
#define MAX_MAGNITUDE 9999

// Board +Z is the screen side (the bit points along -Z, away from the user).
// TODO(hw): board +Y = top edge and +X = right edge of the screen as seen by
// the user is assumed (QMI8658 placement vs. CO5300 scan direction not
// verified on hardware).
const ui_pose_t ui_poses[UI_POSE_COUNT] = {
    {ACCEL_CAL_DIR_POS_Z, "Screen up"},
    {ACCEL_CAL_DIR_NEG_Z, "Screen down"},
    {ACCEL_CAL_DIR_POS_Y, "Top edge up"},
    {ACCEL_CAL_DIR_NEG_Y, "Bottom edge up"},
    {ACCEL_CAL_DIR_NEG_X, "Left edge up"},
    {ACCEL_CAL_DIR_POS_X, "Right edge up"},
};

void ui_fmt_fixed(char *buf, size_t len, float v, int decimals, bool show_plus,
                  const char *suffix)
{
    static const int32_t pow10[] = {1, 10, 100, 1000};

    if (buf == NULL || len == 0) {
        return;
    }
    if (suffix == NULL) {
        suffix = "";
    }
    if (!isfinite(v)) {
        snprintf(buf, len, "--%s", suffix);
        return;
    }
    if (decimals < 0) {
        decimals = 0;
    } else if (decimals > 3) {
        decimals = 3;
    }
    int32_t scale = pow10[decimals];
    float mag = fminf(fabsf(v) * (float)scale, (float)(MAX_MAGNITUDE * scale));
    long n = lroundf(mag);

    // The sign follows the rounded value, so -0.04 reads "0.0", not "-0.0".
    const char *sign = "";
    if (n > 0) {
        sign = v < 0.0f ? "-" : (show_plus ? "+" : "");
    }
    if (decimals == 0) {
        snprintf(buf, len, "%s%ld%s", sign, n, suffix);
    } else {
        snprintf(buf, len, "%s%ld.%0*ld%s", sign, n / scale, decimals, n % scale, suffix);
    }
}

void ui_fmt_deg(char *buf, size_t len, float deg, bool show_plus)
{
    ui_fmt_fixed(buf, len, deg, 1, show_plus, DEG_SIGN);
}

int32_t ui_tenths(float deg)
{
    if (!isfinite(deg)) {
        return 0;
    }
    float t = fmaxf(-MAX_MAGNITUDE * 10.0f, fminf(MAX_MAGNITUDE * 10.0f, deg * 10.0f));
    return (int32_t)lroundf(t);
}

int ui_wall_arrow(const level_reading_t *r)
{
    if (r->status == LEVEL_STATUS_ON_TARGET) {
        return 0;
    }
    int32_t t = ui_tenths(r->elevation_deg);
    return t > 0 ? 1 : (t < 0 ? -1 : 0);
}

static float sane_near(float near_deg)
{
    // Settings are validated, but a zero here would divide by zero.
    return near_deg > 0.01f ? near_deg : 1.0f;
}

float ui_ring_fraction(float deviation_deg, float near_deg)
{
    if (!isfinite(deviation_deg)) {
        return 0.0f;
    }
    float f = 1.0f - fabsf(deviation_deg) / (3.0f * sane_near(near_deg));
    return fmaxf(0.0f, fminf(1.0f, f));
}

float ui_bubble_ppd(float near_deg, float radius_px)
{
    return radius_px / 3.0f / sane_near(near_deg);
}

void ui_bubble_offset(float bubble_x_deg, float bubble_y_deg, float near_deg, float radius_px,
                      float bubble_r_px, int32_t *x, int32_t *y)
{
    *x = 0;
    *y = 0;
    if (!isfinite(bubble_x_deg) || !isfinite(bubble_y_deg)) {
        return;
    }
    float ppd = ui_bubble_ppd(near_deg, radius_px);
    float px = bubble_x_deg * ppd;
    // TODO(hw): board +Y assumed to be screen up; LVGL y grows downwards.
    float py = -bubble_y_deg * ppd;
    float lim = fmaxf(0.0f, radius_px - bubble_r_px);
    float d = hypotf(px, py);
    if (d > lim) {
        // Pin to the rim but keep the direction, so the user still sees which
        // way to tilt.
        px *= lim / d;
        py *= lim / d;
    }
    *x = (int32_t)lroundf(px);
    *y = (int32_t)lroundf(py);
}

int32_t ui_yaw_offset(float yaw_deg, float near_deg, int32_t half_w_px)
{
    if (!isfinite(yaw_deg)) {
        return 0;
    }
    float hw = (float)half_w_px;
    float v = yaw_deg * hw / 3.0f / sane_near(near_deg);
    return (int32_t)lroundf(fmaxf(-hw, fminf(hw, v)));
}

void ui_burnin_offset(uint32_t step, int32_t amp, int32_t *dx, int32_t *dy)
{
    // Every step moves to a neighbouring point of a 3x3 grid, so no pixel
    // stays lit at the same place for more than one interval.
    static const int8_t pattern[9][2] = {
        {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1},
    };
    uint32_t i = step % 9u;
    *dx = pattern[i][0] * amp;
    *dy = pattern[i][1] * amp;
}

// Fade over this much corner overshoot, so a row is faint before the panel
// edge would cut it visibly.
#define ROUND_FADE_PX 40.0f

void ui_round_row(int32_t dy_px, int32_t h_px, int32_t nominal_w, int32_t min_w,
                  int32_t radius_px, int32_t *w_out, uint8_t *opa_out)
{
    float r = (float)radius_px;
    // The row edge farthest from the centre limits the width.
    float outer = fabsf((float)dy_px) + (float)h_px / 2.0f;
    float half = outer < r ? sqrtf(r * r - outer * outer) : 0.0f;
    int32_t w = (int32_t)floorf(2.0f * half);
    w = w > nominal_w ? nominal_w : w;
    w = w < min_w ? min_w : w;
    *w_out = w;

    float x = (float)w / 2.0f;
    float over = sqrtf(x * x + outer * outer) - r;
    if (over <= 0.0f) {
        *opa_out = 255;
        return;
    }
    float opa = 255.0f - over * (255.0f - (float)UI_ROUND_MIN_OPA) / ROUND_FADE_PX;
    *opa_out = opa <= (float)UI_ROUND_MIN_OPA ? UI_ROUND_MIN_OPA : (uint8_t)opa;
}

int ui_battery_level(uint8_t percent)
{
    if (percent >= 88) {
        return 4;
    }
    if (percent >= 63) {
        return 3;
    }
    if (percent >= 38) {
        return 2;
    }
    if (percent >= UI_LOW_BATTERY_PCT) {
        return 1;
    }
    return 0;
}

bool ui_low_battery(const app_battery_t *b)
{
    return b->valid && b->percent < UI_LOW_BATTERY_PCT && !b->charging && !b->vbus_present;
}

// --- settings steppers ---

static int32_t floor_div(int32_t a, int32_t b)
{
    int32_t q = a / b;
    return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q;
}

// Next grid value strictly above / below v, so off-grid values (e.g. set over
// BLE) snap onto the grid.
static int32_t grid_step(int32_t v, int32_t step, int dir)
{
    if (dir > 0) {
        return floor_div(v, step) * step + step;
    }
    return -floor_div(-v, step) * step - step;
}

static int32_t clamp_i32(int32_t v, int32_t lo, int32_t hi)
{
    return v < lo ? lo : (v > hi ? hi : v);
}

static uint16_t preset_step(const uint16_t *list, size_t n, uint16_t cur, int dir, uint16_t lo,
                            uint16_t hi)
{
    uint16_t out = cur;
    if (dir > 0) {
        for (size_t i = 0; i < n; i++) {
            if (list[i] > cur && list[i] <= hi) {
                out = list[i];
                break;
            }
        }
    } else {
        out = lo < cur ? lo : cur;
        for (size_t i = n; i-- > 0;) {
            if (list[i] < cur) {
                out = list[i] > lo ? list[i] : lo;
                break;
            }
        }
    }
    return (uint16_t)clamp_i32(out, lo, hi);
}

static const uint16_t DIM_PRESETS_S[] = {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600};
static const uint16_t SLEEP_PRESETS_S[] = {15, 30, 45, 60, 90, 120, 180, 300, 600, 900,
                                           1200, 1800, 2700, 3600};
static const uint16_t OFF_PRESETS_MIN[] = {0, 15, 30, 60, 120, 240, 480, 720, 1440};

#define N_ELEM(a) (sizeof(a) / sizeof((a)[0]))

// The step grids work in integer hundredths / tenths of a degree.
#define TOL_MIN_H ((int32_t)lroundf(SETTINGS_TOL_MIN_DEG * 100.0f))
#define TOL_MAX_H ((int32_t)lroundf(SETTINGS_TOL_MAX_DEG * 100.0f))
#define TARGET_MAX_T ((int32_t)lroundf(SETTINGS_TARGET_MAX_DEG * 10.0f))

static bool edited_fields_equal(const settings_t *a, const settings_t *b)
{
    return a->tolerance_deg == b->tolerance_deg && a->near_deg == b->near_deg &&
           a->target_deg == b->target_deg && a->brightness_pct == b->brightness_pct &&
           a->dim_brightness_pct == b->dim_brightness_pct && a->dim_after_s == b->dim_after_s &&
           a->sleep_after_s == b->sleep_after_s && a->off_after_min == b->off_after_min &&
           a->ble_enabled == b->ble_enabled;
}

bool ui_settings_step(settings_t *s, ui_field_t f, int dir)
{
    settings_t before = *s;
    dir = dir > 0 ? 1 : -1;

    switch (f) {
    case UI_FIELD_TOLERANCE: {
        // Fine steps where drilling tolerances usually are, coarse above 1 deg.
        int32_t t = (int32_t)lroundf(s->tolerance_deg * 100.0f);
        int32_t step = (dir > 0 ? t < 100 : t <= 100) ? 5 : 25;
        t = clamp_i32(grid_step(t, step, dir), TOL_MIN_H, TOL_MAX_H);
        s->tolerance_deg = (float)t / 100.0f;
        break;
    }
    case UI_FIELD_NEAR: {
        int32_t t = (int32_t)lroundf(s->near_deg * 100.0f);
        int32_t step = (dir > 0 ? t < 500 : t <= 500) ? 25 : 100;
        float v = (float)grid_step(t, step, dir) / 100.0f;
        s->near_deg = fminf(SETTINGS_NEAR_MAX_DEG, fmaxf(s->tolerance_deg, v));
        break;
    }
    case UI_FIELD_TARGET: {
        int32_t t = (int32_t)lroundf(s->target_deg * 10.0f);
        t = clamp_i32(grid_step(t, 10, dir), -TARGET_MAX_T, TARGET_MAX_T);
        s->target_deg = (float)t / 10.0f;
        break;
    }
    case UI_FIELD_BRIGHTNESS:
        s->brightness_pct = (uint8_t)clamp_i32(grid_step(s->brightness_pct, 10, dir),
                                               SETTINGS_BRIGHT_MIN_PCT, SETTINGS_BRIGHT_MAX_PCT);
        break;
    case UI_FIELD_DIM_S:
        s->dim_after_s = preset_step(DIM_PRESETS_S, N_ELEM(DIM_PRESETS_S), s->dim_after_s, dir,
                                     SETTINGS_DIM_MIN_S, SETTINGS_DIM_MAX_S);
        break;
    case UI_FIELD_SLEEP_S:
        s->sleep_after_s = preset_step(SLEEP_PRESETS_S, N_ELEM(SLEEP_PRESETS_S),
                                       s->sleep_after_s, dir, s->dim_after_s,
                                       SETTINGS_SLEEP_MAX_S);
        break;
    case UI_FIELD_OFF_MIN:
        s->off_after_min = preset_step(OFF_PRESETS_MIN, N_ELEM(OFF_PRESETS_MIN),
                                       s->off_after_min, dir, 0, SETTINGS_OFF_MAX_MIN);
        break;
    default:
        return false;
    }
    settings_fixup(s);
    return !edited_fields_equal(&before, s);
}

static void fmt_duration_s(char *buf, size_t len, uint32_t s)
{
    if (s != 0 && s % 3600u == 0) {
        snprintf(buf, len, "%lu h", (unsigned long)(s / 3600u));
    } else if (s != 0 && s % 60u == 0) {
        snprintf(buf, len, "%lu min", (unsigned long)(s / 60u));
    } else {
        snprintf(buf, len, "%lu s", (unsigned long)s);
    }
}

void ui_settings_format(const settings_t *s, ui_field_t f, char *buf, size_t len)
{
    if (buf == NULL || len == 0) {
        return;
    }
    switch (f) {
    case UI_FIELD_TOLERANCE:
        ui_fmt_fixed(buf, len, s->tolerance_deg, 2, false, DEG_SIGN);
        break;
    case UI_FIELD_NEAR:
        ui_fmt_fixed(buf, len, s->near_deg, 2, false, DEG_SIGN);
        break;
    case UI_FIELD_TARGET:
        ui_fmt_deg(buf, len, s->target_deg, false);
        break;
    case UI_FIELD_BRIGHTNESS:
        snprintf(buf, len, "%u %%", (unsigned)s->brightness_pct);
        break;
    case UI_FIELD_DIM_S:
        fmt_duration_s(buf, len, s->dim_after_s);
        break;
    case UI_FIELD_SLEEP_S:
        fmt_duration_s(buf, len, s->sleep_after_s);
        break;
    case UI_FIELD_OFF_MIN:
        if (s->off_after_min == 0) {
            snprintf(buf, len, "Never");
        } else {
            fmt_duration_s(buf, len, (uint32_t)s->off_after_min * 60u);
        }
        break;
    default:
        buf[0] = '\0';
        break;
    }
}

// Copies field f from edit to cur only if the screen changed it.
#define MERGE_FIELD(f)                \
    do {                              \
        if (edit->f != base->f) {     \
            cur->f = edit->f;         \
        }                             \
    } while (0)

void ui_settings_merge(settings_t *cur, const settings_t *base, const settings_t *edit)
{
    MERGE_FIELD(tolerance_deg);
    MERGE_FIELD(near_deg);
    MERGE_FIELD(target_deg);
    MERGE_FIELD(brightness_pct);
    MERGE_FIELD(dim_brightness_pct);
    MERGE_FIELD(dim_after_s);
    MERGE_FIELD(sleep_after_s);
    MERGE_FIELD(off_after_min);
    MERGE_FIELD(ble_enabled);
    settings_fixup(cur);
}

#undef MERGE_FIELD

// --- texts ---

const char *ui_mode_title(level_mode_t mode)
{
    switch (mode) {
    case LEVEL_MODE_WALL:
        return "WALL";
    case LEVEL_MODE_FLOOR:
        return "FLOOR / CEILING";
    case LEVEL_MODE_ANGLE:
        return "ANGLE";
    default:
        return "?";
    }
}

const char *ui_mount_result_text(int result)
{
    switch (result) {
    case LEVEL_CAL_OK:
        return "Mount calibrated.";
    case LEVEL_CAL_TOO_FEW_SAMPLES:
        return "Too few samples.\nRoll a little longer.";
    case LEVEL_CAL_TOO_LITTLE_ROLL:
        return "Not enough roll.\nRoll 45" DEG_SIGN " each way, in a\nlevel or tilted hole.";
    case LEVEL_CAL_NOT_ON_CONE:
        return "The bit moved.\nKeep it steady in the hole.";
    case LEVEL_CAL_OFFSET_TOO_LARGE:
        return "Mount is too crooked.\nRe-seat the device.";
    case APP_CAL_RESULT_NOT_STORED:
        return "Calibrated, but not saved.\nPlease try again.";
    default:
        return "Unknown result.";
    }
}

const char *ui_accel_result_text(int result)
{
    switch (result) {
    case ACCEL_CAL_OK:
        // There was no mount calibration (else it would have been dropped);
        // the mount calibration comes next.
        return "Sensor calibrated.\nNow run the mount\ncalibration.";
    case APP_CAL_RESULT_OK_MOUNT_DROPPED:
        // The mount calibration was measured with the old correction and
        // has been reset (control.c).
        return "Sensor calibrated.\nNow redo the mount\ncalibration.";
    case ACCEL_CAL_POOR_COVERAGE:
        return "Not all six sides captured.";
    case ACCEL_CAL_FIT_FAILED:
        return "Fit failed.\nHold still and try again.";
    case ACCEL_CAL_OUT_OF_RANGE:
        return "Sensor error too large.\nTry again; if it repeats,\nthe sensor may be faulty.";
    case APP_CAL_RESULT_NOT_STORED:
        return "Calibrated, but not saved.\nPlease try again.";
    default:
        return "Unknown result.";
    }
}

bool ui_cal_result_known(int result)
{
    return result >= 0 || result == APP_CAL_RESULT_NOT_STORED ||
           result == APP_CAL_RESULT_OK_MOUNT_DROPPED;
}

bool ui_cal_result_ok(int result)
{
    // LEVEL_CAL_OK == ACCEL_CAL_OK
    return result == 0 || result == APP_CAL_RESULT_OK_MOUNT_DROPPED;
}
