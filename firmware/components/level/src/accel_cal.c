#include <math.h>
#include "accel_cal.h"

// A still accelerometer reads 1 g. Anything this far off is not a usable
// pose (moving, or not an accelerometer reading at all), whatever the
// calibration limits allow.
#define POSE_MIN_NORM_G 0.5f
#define POSE_MAX_NORM_G 1.5f

#define N_UNKNOWNS 6

accel_cal_t accel_cal_identity(void)
{
    return (accel_cal_t){
        .bias = {0.0f, 0.0f, 0.0f},
        .scale = {1.0f, 1.0f, 1.0f},
    };
}

bool accel_cal_equal(const accel_cal_t *a, const accel_cal_t *b)
{
    return vec3_equal(a->bias, b->bias) && vec3_equal(a->scale, b->scale);
}

vec3_t accel_cal_apply(const accel_cal_t *cal, vec3_t raw_g)
{
    return (vec3_t){
        (raw_g.x - cal->bias.x) * cal->scale.x,
        (raw_g.y - cal->bias.y) * cal->scale.y,
        (raw_g.z - cal->bias.z) * cal->scale.z,
    };
}

void accel_cal_collector_reset(accel_cal_collector_t *c)
{
    c->n = 0;
    c->win_n = 0;
}

bool accel_cal_collector_offer(accel_cal_collector_t *c, vec3_t mean_raw_g)
{
    if (c->n >= ACCEL_CAL_MAX_POSES) {
        return false;
    }
    float norm = vec3_norm(mean_raw_g);
    // Written so that NaN fails too.
    if (!(norm >= POSE_MIN_NORM_G && norm <= POSE_MAX_NORM_G)) {
        return false;
    }
    vec3_t dir = vec3_scale(mean_raw_g, 1.0f / norm);
    float min_cos = cosf(LEVEL_DEG2RAD(ACCEL_CAL_MIN_POSE_SEPARATION_DEG));
    for (uint8_t i = 0; i < c->n; i++) {
        if (vec3_dot(dir, vec3_normalize(c->poses[i])) > min_cos) {
            return false;
        }
    }
    c->poses[c->n++] = mean_raw_g;
    return true;
}

static void win_start(accel_cal_collector_t *c, vec3_t raw)
{
    c->win_n = 1;
    c->win_sum = raw;
    c->win_min = raw;
    c->win_max = raw;
}

static bool win_spread_ok(const accel_cal_collector_t *c)
{
    return c->win_max.x - c->win_min.x <= ACCEL_CAL_MAX_RANGE_G &&
           c->win_max.y - c->win_min.y <= ACCEL_CAL_MAX_RANGE_G &&
           c->win_max.z - c->win_min.z <= ACCEL_CAL_MAX_RANGE_G;
}

bool accel_cal_collector_add_sample(accel_cal_collector_t *c, vec3_t raw, float gyro_rate,
                                    float dt)
{
    if (gyro_rate > ACCEL_CAL_MAX_GYRO_RAD_S) {
        c->win_n = 0;
        return false;
    }
    if (c->win_n == 0) {
        win_start(c, raw);
        return false;
    }
    c->win_sum = vec3_add(c->win_sum, raw);
    c->win_min = (vec3_t){fminf(c->win_min.x, raw.x), fminf(c->win_min.y, raw.y),
                          fminf(c->win_min.z, raw.z)};
    c->win_max = (vec3_t){fmaxf(c->win_max.x, raw.x), fmaxf(c->win_max.y, raw.y),
                          fmaxf(c->win_max.z, raw.z)};
    c->win_n++;
    if (!win_spread_ok(c)) {
        win_start(c, raw);   // moved: this sample may start the next still pose
        return false;
    }
    if ((float)c->win_n * dt < ACCEL_CAL_WINDOW_S) {
        return false;
    }
    vec3_t mean = vec3_scale(c->win_sum, 1.0f / (float)c->win_n);
    c->win_n = 0;
    return accel_cal_collector_offer(c, mean);
}

uint8_t accel_cal_coverage(const accel_cal_collector_t *c)
{
    float cone_cos = cosf(LEVEL_DEG2RAD(ACCEL_CAL_COVERAGE_CONE_DEG));
    uint8_t bits = 0;
    for (uint8_t i = 0; i < c->n && i < ACCEL_CAL_MAX_POSES; i++) {
        vec3_t d = vec3_normalize(c->poses[i]);
        const float comp[3] = {d.x, d.y, d.z};
        // Bit order is +X, -X, +Y, -Y, +Z, -Z.
        for (int axis = 0; axis < 3; axis++) {
            if (comp[axis] >= cone_cos) {
                bits |= (uint8_t)(1u << (2 * axis));
            } else if (-comp[axis] >= cone_cos) {
                bits |= (uint8_t)(1u << (2 * axis + 1));
            }
        }
    }
    return bits;
}

// Solves m * x = rhs in place (Gaussian elimination, partial pivoting).
// Returns false if m is singular relative to its own magnitude.
static bool solve_linear(double m[N_UNKNOWNS][N_UNKNOWNS], double rhs[N_UNKNOWNS],
                         double x[N_UNKNOWNS])
{
    double max_abs = 0.0;
    for (int i = 0; i < N_UNKNOWNS; i++) {
        for (int j = 0; j < N_UNKNOWNS; j++) {
            max_abs = fmax(max_abs, fabs(m[i][j]));
        }
    }
    double eps = max_abs * 1e-12;
    if (!(eps > 0.0)) {
        return false;
    }

    for (int col = 0; col < N_UNKNOWNS; col++) {
        int piv = col;
        for (int r = col + 1; r < N_UNKNOWNS; r++) {
            if (fabs(m[r][col]) > fabs(m[piv][col])) {
                piv = r;
            }
        }
        if (!(fabs(m[piv][col]) > eps)) {
            return false;
        }
        if (piv != col) {
            for (int j = 0; j < N_UNKNOWNS; j++) {
                double t = m[col][j];
                m[col][j] = m[piv][j];
                m[piv][j] = t;
            }
            double t = rhs[col];
            rhs[col] = rhs[piv];
            rhs[piv] = t;
        }
        for (int r = col + 1; r < N_UNKNOWNS; r++) {
            double f = m[r][col] / m[col][col];
            for (int j = col; j < N_UNKNOWNS; j++) {
                m[r][j] -= f * m[col][j];
            }
            rhs[r] -= f * rhs[col];
        }
    }
    for (int i = N_UNKNOWNS - 1; i >= 0; i--) {
        double acc = rhs[i];
        for (int j = i + 1; j < N_UNKNOWNS; j++) {
            acc -= m[i][j] * x[j];
        }
        x[i] = acc / m[i][i];
    }
    return true;
}

accel_cal_result_t accel_cal_solve(const accel_cal_collector_t *c, accel_cal_t *out)
{
    if (accel_cal_coverage(c) != ACCEL_CAL_DIR_ALL) {
        return ACCEL_CAL_POOR_COVERAGE;
    }

    // Least squares for A x^2 + B y^2 + C z^2 + D x + E y + F z = 1. The
    // model raw = t / scale + bias with |t| = 1 expands to exactly this form
    // (see test_accel_cal.c). Double precision: the x^2 and x columns are
    // nearly collinear for small biases, and this runs once per calibration.
    double m[N_UNKNOWNS][N_UNKNOWNS] = {{0.0}};
    double rhs[N_UNKNOWNS] = {0.0};
    for (uint8_t k = 0; k < c->n && k < ACCEL_CAL_MAX_POSES; k++) {
        double x = (double)c->poses[k].x;
        double y = (double)c->poses[k].y;
        double z = (double)c->poses[k].z;
        const double phi[N_UNKNOWNS] = {x * x, y * y, z * z, x, y, z};
        for (int i = 0; i < N_UNKNOWNS; i++) {
            for (int j = 0; j < N_UNKNOWNS; j++) {
                m[i][j] += phi[i] * phi[j];
            }
            rhs[i] += phi[i];
        }
    }

    double p[N_UNKNOWNS];
    if (!solve_linear(m, rhs, p)) {
        return ACCEL_CAL_FIT_FAILED;
    }
    // Written so that NaN fails too.
    if (!(p[0] > 0.0 && p[1] > 0.0 && p[2] > 0.0)) {
        return ACCEL_CAL_FIT_FAILED;
    }
    double b[3], s = 0.0;
    for (int i = 0; i < 3; i++) {
        b[i] = -p[3 + i] / (2.0 * p[i]);
        s += p[i] * b[i] * b[i];
    }
    if (!(1.0 + s > 0.0)) {
        return ACCEL_CAL_FIT_FAILED;
    }
    double g[3];
    for (int i = 0; i < 3; i++) {
        g[i] = sqrt(p[i] / (1.0 + s));
        if (!(fabs(b[i]) <= (double)ACCEL_CAL_MAX_BIAS_G)) {
            return ACCEL_CAL_OUT_OF_RANGE;
        }
        if (!(g[i] >= (double)ACCEL_CAL_MIN_SCALE && g[i] <= (double)ACCEL_CAL_MAX_SCALE)) {
            return ACCEL_CAL_OUT_OF_RANGE;
        }
    }
    out->bias = (vec3_t){(float)b[0], (float)b[1], (float)b[2]};
    out->scale = (vec3_t){(float)g[0], (float)g[1], (float)g[2]};
    return ACCEL_CAL_OK;
}
