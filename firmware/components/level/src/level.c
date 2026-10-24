#include <math.h>
#include "level.h"

// Yaw (heading of the bit) is only meaningful while the bit is at least this
// far from vertical: |elevation| <= 60 degrees.
#define YAW_MIN_HORIZONTAL 0.5f

// Mount calibration limits, in units of the (unit length) up vector.
#define CAL_MIN_SAMPLES 10
#define CAL_MIN_ROLL_VARIANCE 0.005f   // about +-45 degrees of roll in a horizontal hole
#define CAL_MAX_CONE_VARIANCE 1e-4f    // rms 0.01, about 0.6 degrees off the cone

level_config_t level_default_config(void)
{
    return (level_config_t){
        .drill_axis = {0.0f, 0.0f, -1.0f},
        .tolerance_deg = 0.5f,
        .near_deg = 2.0f,
        .target_deg = 45.0f,
        .yaw_stale_ms = 60000,
    };
}

void level_init(level_t *lv, const level_config_t *cfg)
{
    *lv = (level_t){.cfg = *cfg, .mode = LEVEL_MODE_WALL};
}

typedef struct {
    float elevation_deg;
    float heading_deg;
    bool heading_valid;
    float bubble_x_deg;
    float bubble_y_deg;
} raw_angles_t;

static float clamp_unit(float v)
{
    return fmaxf(-1.0f, fminf(1.0f, v));
}

static raw_angles_t raw_angles(const level_config_t *cfg, quat_t q)
{
    raw_angles_t r;
    vec3_t d = cfg->drill_axis;
    vec3_t up = quat_rotate_inv(q, (vec3_t){0.0f, 0.0f, 1.0f});

    r.elevation_deg = LEVEL_RAD2DEG(asinf(clamp_unit(vec3_dot(d, up))));

    vec3_t dw = quat_rotate(q, d);
    r.heading_valid = hypotf(dw.x, dw.y) >= YAW_MIN_HORIZONTAL;
    r.heading_deg = LEVEL_RAD2DEG(atan2f(dw.y, dw.x));

    // Screen axes perpendicular to the drill axis (X and Y for a perfect
    // mount). The bubble moves towards the higher side, for floor and
    // ceiling alike.
    vec3_t e1 = vec3_normalize(vec3_sub((vec3_t){1.0f, 0.0f, 0.0f},
                                        vec3_scale(d, d.x)));
    vec3_t e2 = vec3_cross(e1, d);
    float along = fabsf(vec3_dot(up, d));
    r.bubble_x_deg = LEVEL_RAD2DEG(atan2f(vec3_dot(up, e1), along));
    r.bubble_y_deg = LEVEL_RAD2DEG(atan2f(vec3_dot(up, e2), along));
    return r;
}

void level_zero(level_t *lv, quat_t q, uint32_t now_ms)
{
    raw_angles_t r = raw_angles(&lv->cfg, q);
    lv->zero_set = true;
    lv->zero_elevation_deg = r.elevation_deg;
    lv->zero_heading_deg = r.heading_deg;
    lv->zero_heading_valid = r.heading_valid;
    lv->zero_bubble_x_deg = r.bubble_x_deg;
    lv->zero_bubble_y_deg = r.bubble_y_deg;
    lv->zero_time_ms = now_ms;
}

void level_clear_zero(level_t *lv)
{
    lv->zero_set = false;
}

void level_reset_heading(level_t *lv)
{
    lv->zero_heading_valid = false;
}

// Clears a set zero; returns true if there was one.
static bool drop_zero(level_t *lv)
{
    bool was_set = lv->zero_set;
    level_clear_zero(lv);
    return was_set;
}

static bool floor_family(level_mode_t m)
{
    return m == LEVEL_MODE_FLOOR;
}

bool level_set_mode(level_t *lv, level_mode_t mode)
{
    if ((unsigned)mode >= LEVEL_MODE_COUNT) {
        return false;
    }
    bool cross = floor_family(lv->mode) != floor_family(mode);
    lv->mode = mode;
    return cross && drop_zero(lv);
}

bool level_set_config(level_t *lv, const level_config_t *cfg)
{
    bool axis_changed = !vec3_equal(cfg->drill_axis, lv->cfg.drill_axis);
    lv->cfg = *cfg;
    return axis_changed && drop_zero(lv);
}

level_status_t level_status_for(const level_config_t *cfg, float error_deg)
{
    float e = fabsf(error_deg);
    if (e <= cfg->tolerance_deg) {
        return LEVEL_STATUS_ON_TARGET;
    }
    if (e <= cfg->near_deg) {
        return LEVEL_STATUS_NEAR;
    }
    return LEVEL_STATUS_OFF;
}

level_reading_t level_compute(const level_t *lv, quat_t q, uint32_t now_ms)
{
    raw_angles_t r = raw_angles(&lv->cfg, q);
    level_reading_t out = {.mode = lv->mode};

    out.elevation_deg = r.elevation_deg;
    out.bubble_x_deg = r.bubble_x_deg;
    out.bubble_y_deg = r.bubble_y_deg;
    if (lv->zero_set) {
        out.elevation_deg -= lv->zero_elevation_deg;
        out.bubble_x_deg -= lv->zero_bubble_x_deg;
        out.bubble_y_deg -= lv->zero_bubble_y_deg;

        out.yaw_valid = lv->zero_heading_valid && r.heading_valid;
        // Heading is counter-clockwise from above; report turning right as positive.
        out.yaw_deg = out.yaw_valid ? -wrap_deg(r.heading_deg - lv->zero_heading_deg) : 0.0f;
        out.yaw_stale = (uint32_t)(now_ms - lv->zero_time_ms) > lv->cfg.yaw_stale_ms;
    }
    out.yaw_status = out.yaw_valid ? level_status_for(&lv->cfg, out.yaw_deg) : LEVEL_STATUS_OFF;

    switch (lv->mode) {
    case LEVEL_MODE_FLOOR:
        out.deviation_deg = hypotf(out.bubble_x_deg, out.bubble_y_deg);
        break;
    case LEVEL_MODE_ANGLE:
        out.deviation_deg = out.elevation_deg - lv->cfg.target_deg;
        break;
    case LEVEL_MODE_WALL:
    default:
        out.deviation_deg = out.elevation_deg;
        break;
    }
    out.status = level_status_for(&lv->cfg, out.deviation_deg);
    return out;
}

// --- mount calibration ---

void level_mount_cal_reset(level_mount_cal_t *cal)
{
    *cal = (level_mount_cal_t){0};
}

void level_mount_cal_add(level_mount_cal_t *cal, vec3_t u)
{
    cal->n++;
    cal->sum = vec3_add(cal->sum, u);
    cal->sxx += u.x * u.x;
    cal->sxy += u.x * u.y;
    cal->sxz += u.x * u.z;
    cal->syy += u.y * u.y;
    cal->syz += u.y * u.z;
    cal->szz += u.z * u.z;
}

void level_mount_collector_reset(level_mount_collector_t *c, uint32_t start_ms)
{
    *c = (level_mount_collector_t){.start_ms = start_ms};
}

bool level_mount_collector_add(level_mount_collector_t *c, vec3_t up_body, uint32_t now_ms)
{
    // Signed: `now_ms` may have been read just before the start was stamped.
    if (c->fit.n >= LEVEL_MOUNT_CAL_MAX_SAMPLES ||
        (int32_t)(now_ms - c->start_ms) < (int32_t)LEVEL_MOUNT_CAL_SETTLE_MS) {
        return false;
    }
    if (c->tail_n < LEVEL_MOUNT_CAL_TAIL) {
        c->tail[(c->tail_head + c->tail_n) % LEVEL_MOUNT_CAL_TAIL] = up_body;
        c->tail_n++;
        return false;
    }
    // Ring full: the oldest is now old enough for the fit.
    level_mount_cal_add(&c->fit, c->tail[c->tail_head]);
    c->tail[c->tail_head] = up_body;
    c->tail_head = (c->tail_head + 1u) % LEVEL_MOUNT_CAL_TAIL;
    return true;
}

// Eigenvalues of a symmetric 3x3 matrix, largest first (closed form,
// Smith 1961).
static void sym3_eigenvalues(const float a[3][3], float eig[3])
{
    float p1 = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
    float q = (a[0][0] + a[1][1] + a[2][2]) / 3.0f;
    float d0 = a[0][0] - q, d1 = a[1][1] - q, d2 = a[2][2] - q;
    float p = sqrtf((d0 * d0 + d1 * d1 + d2 * d2 + 2.0f * p1) / 6.0f);
    if (p <= 0.0f) {
        eig[0] = eig[1] = eig[2] = q;
        return;
    }
    float b[3][3];
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            b[i][j] = (a[i][j] - (i == j ? q : 0.0f)) / p;
        }
    }
    float det = b[0][0] * (b[1][1] * b[2][2] - b[1][2] * b[2][1])
              - b[0][1] * (b[1][0] * b[2][2] - b[1][2] * b[2][0])
              + b[0][2] * (b[1][0] * b[2][1] - b[1][1] * b[2][0]);
    float phi = acosf(clamp_unit(det / 2.0f)) / 3.0f;
    eig[0] = q + 2.0f * p * cosf(phi);
    eig[2] = q + 2.0f * p * cosf(phi + 2.0943951f);
    eig[1] = 3.0f * q - eig[0] - eig[2];
}

// Eigenvector for an eigenvalue that is not repeated: the rows of A - lambda*I
// span the plane perpendicular to it.
static vec3_t sym3_eigenvector(const float a[3][3], float lambda)
{
    vec3_t r0 = {a[0][0] - lambda, a[0][1], a[0][2]};
    vec3_t r1 = {a[1][0], a[1][1] - lambda, a[1][2]};
    vec3_t r2 = {a[2][0], a[2][1], a[2][2] - lambda};
    vec3_t c[3] = {vec3_cross(r0, r1), vec3_cross(r0, r2), vec3_cross(r1, r2)};
    vec3_t best = c[0];
    for (int i = 1; i < 3; i++) {
        if (vec3_norm(c[i]) > vec3_norm(best)) {
            best = c[i];
        }
    }
    return vec3_normalize(best);
}

level_cal_result_t level_mount_cal_solve(const level_mount_cal_t *cal, float max_offset_deg,
                                         vec3_t *drill_axis_out)
{
    if (cal->n < CAL_MIN_SAMPLES) {
        return LEVEL_CAL_TOO_FEW_SAMPLES;
    }
    float inv_n = 1.0f / (float)cal->n;
    vec3_t m = vec3_scale(cal->sum, inv_n);
    float c[3][3];
    c[0][0] = cal->sxx * inv_n - m.x * m.x;
    c[0][1] = c[1][0] = cal->sxy * inv_n - m.x * m.y;
    c[0][2] = c[2][0] = cal->sxz * inv_n - m.x * m.z;
    c[1][1] = cal->syy * inv_n - m.y * m.y;
    c[1][2] = c[2][1] = cal->syz * inv_n - m.y * m.z;
    c[2][2] = cal->szz * inv_n - m.z * m.z;

    float eig[3];
    sym3_eigenvalues(c, eig);
    // The samples must spread in two directions (a real arc of the cone)...
    if (eig[1] < CAL_MIN_ROLL_VARIANCE) {
        return LEVEL_CAL_TOO_LITTLE_ROLL;
    }
    // ...and stay close to one plane (the bit didn't move).
    if (eig[2] > CAL_MAX_CONE_VARIANCE) {
        return LEVEL_CAL_NOT_ON_CONE;
    }

    vec3_t axis = sym3_eigenvector(c, eig[2]);
    if (axis.z > 0.0f) {
        axis = vec3_scale(axis, -1.0f);
    }
    if (-axis.z < cosf(LEVEL_DEG2RAD(max_offset_deg))) {
        return LEVEL_CAL_OFFSET_TOO_LARGE;
    }
    *drill_axis_out = axis;
    return LEVEL_CAL_OK;
}
