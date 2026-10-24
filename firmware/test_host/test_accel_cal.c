#include <math.h>
#include "unity.h"
#include "accel_cal.h"
#include "test_util.h"

static accel_cal_collector_t col;

static const vec3_t TRUE_BIAS = {0.03f, -0.05f, 0.02f};
static const vec3_t TRUE_SCALE = {1.02f, 0.97f, 1.01f};

void setUp(void)
{
    accel_cal_collector_reset(&col);
}

void tearDown(void) {}

// What a sensor with this bias and scale reads with unit "up" vector t.
static vec3_t raw_for(vec3_t t, vec3_t bias, vec3_t scale)
{
    return (vec3_t){t.x / scale.x + bias.x, t.y / scale.y + bias.y, t.z / scale.z + bias.z};
}

static const vec3_t FACES[6] = {
    {1.0f, 0.0f, 0.0f}, {-1.0f, 0.0f, 0.0f},
    {0.0f, 1.0f, 0.0f}, {0.0f, -1.0f, 0.0f},
    {0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, -1.0f},
};

// Tilted poses, at least 20 degrees away from the faces and each other.
static vec3_t tilted(int i)
{
    static const float dirs[][3] = {
        {1.0f, 1.0f, 1.0f}, {-1.0f, 1.0f, 1.0f}, {1.0f, -1.0f, 1.0f}, {1.0f, 1.0f, -1.0f},
        {-1.0f, -1.0f, -1.0f}, {-1.0f, -1.0f, 1.0f},
    };
    return vec3_normalize((vec3_t){dirs[i][0], dirs[i][1], dirs[i][2]});
}

#define N_TILTED 6

static void offer_all(vec3_t bias, vec3_t scale, bool with_tilted)
{
    for (int i = 0; i < 6; i++) {
        TEST_ASSERT_TRUE(accel_cal_collector_offer(&col, raw_for(FACES[i], bias, scale)));
    }
    for (int i = 0; with_tilted && i < N_TILTED; i++) {
        TEST_ASSERT_TRUE(accel_cal_collector_offer(&col, raw_for(tilted(i), bias, scale)));
    }
}

// The algebra accel_cal_solve() relies on: with s_i = scale, b_i = bias and
// K = 1 - sum(s_i^2 b_i^2), the model |(raw - b) * s| = 1 is the quadric
// A x^2 + B y^2 + C z^2 + D x + E y + F z = 1 with A = s_x^2 / K and
// D = -2 s_x^2 b_x / K (same for y, z), and the solver's inverse formulas
// give back b and s.
static void test_ellipsoid_derivation(void)
{
    const float b[3] = {TRUE_BIAS.x, TRUE_BIAS.y, TRUE_BIAS.z};
    const float g[3] = {TRUE_SCALE.x, TRUE_SCALE.y, TRUE_SCALE.z};
    float k = 1.0f;
    for (int i = 0; i < 3; i++) {
        k -= g[i] * g[i] * b[i] * b[i];
    }
    float quad[3], lin[3];
    for (int i = 0; i < 3; i++) {
        quad[i] = g[i] * g[i] / k;
        lin[i] = -2.0f * g[i] * g[i] * b[i] / k;
    }
    for (int p = 0; p < 6 + N_TILTED; p++) {
        vec3_t r = raw_for(p < 6 ? FACES[p] : tilted(p - 6), TRUE_BIAS, TRUE_SCALE);
        const float v[3] = {r.x, r.y, r.z};
        float lhs = 0.0f;
        for (int i = 0; i < 3; i++) {
            lhs += quad[i] * v[i] * v[i] + lin[i] * v[i];
        }
        TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.0f, lhs);
    }
    float s = 0.0f, bb[3];
    for (int i = 0; i < 3; i++) {
        bb[i] = -lin[i] / (2.0f * quad[i]);
        s += quad[i] * bb[i] * bb[i];
    }
    for (int i = 0; i < 3; i++) {
        TEST_ASSERT_FLOAT_WITHIN(1e-6f, b[i], bb[i]);
        TEST_ASSERT_FLOAT_WITHIN(1e-6f, g[i], sqrtf(quad[i] / (1.0f + s)));
    }
}

static void test_identity_apply_is_noop(void)
{
    accel_cal_t id = accel_cal_identity();
    vec3_t v = {0.123f, -0.987f, 0.456f};
    assert_vec3_within(0.0f, v, accel_cal_apply(&id, v));
}

static void test_apply_inverts_the_model(void)
{
    accel_cal_t cal = {.bias = TRUE_BIAS, .scale = TRUE_SCALE};
    vec3_t t = tilted(0);
    assert_vec3_within(1e-6f, t, accel_cal_apply(&cal, raw_for(t, TRUE_BIAS, TRUE_SCALE)));
}

static void test_recovers_bias_and_scale_from_faces_only(void)
{
    offer_all(TRUE_BIAS, TRUE_SCALE, false);
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OK, accel_cal_solve(&col, &cal));
    assert_vec3_within(1e-3f, TRUE_BIAS, cal.bias);
    assert_vec3_within(1e-3f, TRUE_SCALE, cal.scale);
}

static void test_recovers_bias_and_scale_with_tilted_poses(void)
{
    offer_all(TRUE_BIAS, TRUE_SCALE, true);
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OK, accel_cal_solve(&col, &cal));
    assert_vec3_within(1e-3f, TRUE_BIAS, cal.bias);
    assert_vec3_within(1e-3f, TRUE_SCALE, cal.scale);

    // Calibrated readings are unit length in any pose.
    vec3_t t = vec3_normalize((vec3_t){0.3f, -0.8f, 0.5f});
    vec3_t c = accel_cal_apply(&cal, raw_for(t, TRUE_BIAS, TRUE_SCALE));
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 1.0f, vec3_norm(c));
}

static void test_perfect_sensor_gives_identity(void)
{
    offer_all((vec3_t){0.0f, 0.0f, 0.0f}, (vec3_t){1.0f, 1.0f, 1.0f}, true);
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OK, accel_cal_solve(&col, &cal));
    assert_vec3_within(1e-5f, ((vec3_t){0.0f, 0.0f, 0.0f}), cal.bias);
    assert_vec3_within(1e-5f, ((vec3_t){1.0f, 1.0f, 1.0f}), cal.scale);
}

static void test_noise_keeps_bias_within_3mg(void)
{
    // Deterministic +-2 mg noise pattern, different on each axis and pose.
    static const float pat[] = {2.0f, -1.0f, -2.0f, 1.5f, 0.5f, -1.5f, 2.0f, 1.0f, -0.5f};
    int k = 0;
    for (int i = 0; i < 6 + N_TILTED; i++) {
        vec3_t t = i < 6 ? FACES[i] : tilted(i - 6);
        vec3_t r = raw_for(t, TRUE_BIAS, TRUE_SCALE);
        r.x += 0.001f * pat[k++ % 9];
        r.y += 0.001f * pat[k++ % 9];
        r.z += 0.001f * pat[k++ % 9];
        TEST_ASSERT_TRUE(accel_cal_collector_offer(&col, r));
    }
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OK, accel_cal_solve(&col, &cal));
    assert_vec3_within(0.003f, TRUE_BIAS, cal.bias);
    assert_vec3_within(0.005f, TRUE_SCALE, cal.scale);
}

static void test_missing_direction_is_poor_coverage(void)
{
    for (int i = 0; i < 6; i++) {
        if (i == 3) {
            continue;   // never -Y up
        }
        accel_cal_collector_offer(&col, raw_for(FACES[i], TRUE_BIAS, TRUE_SCALE));
    }
    for (int i = 0; i < N_TILTED; i++) {
        accel_cal_collector_offer(&col, raw_for(tilted(i), TRUE_BIAS, TRUE_SCALE));
    }
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_ALL & ~ACCEL_CAL_DIR_NEG_Y, accel_cal_coverage(&col));
    accel_cal_t cal = accel_cal_identity();
    TEST_ASSERT_EQUAL(ACCEL_CAL_POOR_COVERAGE, accel_cal_solve(&col, &cal));
}

static void test_empty_collector_is_poor_coverage(void)
{
    accel_cal_t cal;
    TEST_ASSERT_EQUAL_HEX8(0, accel_cal_coverage(&col));
    TEST_ASSERT_EQUAL(ACCEL_CAL_POOR_COVERAGE, accel_cal_solve(&col, &cal));
}

static void test_coverage_bits(void)
{
    accel_cal_collector_offer(&col, FACES[0]);
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_POS_X, accel_cal_coverage(&col));
    accel_cal_collector_offer(&col, FACES[5]);
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_POS_X | ACCEL_CAL_DIR_NEG_Z, accel_cal_coverage(&col));
    // 25 degrees from +Y counts, a corner pose (55 degrees from every axis) does not.
    accel_cal_collector_offer(&col, quat_rotate(rot_deg(AXIS_X, 25.0f), AXIS_Y));
    accel_cal_collector_offer(&col, tilted(4));
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_POS_X | ACCEL_CAL_DIR_NEG_Z | ACCEL_CAL_DIR_POS_Y,
                           accel_cal_coverage(&col));
    // 35 degrees from -X is outside the cone.
    accel_cal_collector_offer(&col, quat_rotate(rot_deg(AXIS_Z, 35.0f), vec3_scale(AXIS_X, -1.0f)));
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_POS_X | ACCEL_CAL_DIR_NEG_Z | ACCEL_CAL_DIR_POS_Y,
                           accel_cal_coverage(&col));
}

static void test_duplicate_pose_rejected(void)
{
    TEST_ASSERT_TRUE(accel_cal_collector_offer(&col, FACES[0]));
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, FACES[0]));
    // Same direction at a different magnitude is still a duplicate.
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, vec3_scale(FACES[0], 1.05f)));
    // 15 degrees away: too close; 25 degrees away: kept.
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, quat_rotate(rot_deg(AXIS_Z, 15.0f), AXIS_X)));
    TEST_ASSERT_TRUE(accel_cal_collector_offer(&col, quat_rotate(rot_deg(AXIS_Z, 25.0f), AXIS_X)));
    TEST_ASSERT_EQUAL_UINT8(2, col.n);
}

static void test_implausible_vector_rejected(void)
{
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, (vec3_t){0.0f, 0.0f, 0.0f}));
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, (vec3_t){0.0f, 0.0f, 2.0f}));
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, (vec3_t){NAN, 0.0f, 1.0f}));
    TEST_ASSERT_EQUAL_UINT8(0, col.n);
}

static void test_collector_full(void)
{
    // A spiral of directions, each well apart from the others.
    int kept = 0;
    for (int i = 0; i < 200 && kept < ACCEL_CAL_MAX_POSES; i++) {
        float z = 1.0f - 2.0f * ((float)i + 0.5f) / 200.0f;
        float r = sqrtf(1.0f - z * z);
        float phi = 2.39996323f * (float)i;
        if (accel_cal_collector_offer(&col, (vec3_t){r * cosf(phi), r * sinf(phi), z})) {
            kept++;
        }
    }
    TEST_ASSERT_EQUAL_INT(ACCEL_CAL_MAX_POSES, kept);
    TEST_ASSERT_EQUAL_UINT8(ACCEL_CAL_MAX_POSES, col.n);
    // The spiral starts at +Z, so the kept poses are all well above the
    // equator and -Z is far from all of them: rejected only for lack of room.
    for (int i = 0; i < ACCEL_CAL_MAX_POSES; i++) {
        TEST_ASSERT_TRUE(col.poses[i].z > -0.5f);
    }
    TEST_ASSERT_FALSE(accel_cal_collector_offer(&col, (vec3_t){0.0f, 0.0f, -1.0f}));
    TEST_ASSERT_EQUAL_UINT8(ACCEL_CAL_MAX_POSES, col.n);
}

static void test_out_of_range_bias(void)
{
    offer_all((vec3_t){0.25f, 0.0f, 0.0f}, (vec3_t){1.0f, 1.0f, 1.0f}, true);
    accel_cal_t cal = accel_cal_identity();
    TEST_ASSERT_EQUAL(ACCEL_CAL_OUT_OF_RANGE, accel_cal_solve(&col, &cal));
    // Not written on failure.
    assert_vec3_within(0.0f, ((vec3_t){0.0f, 0.0f, 0.0f}), cal.bias);
}

static void test_out_of_range_scale(void)
{
    offer_all((vec3_t){0.0f, 0.0f, 0.0f}, (vec3_t){1.0f, 1.15f, 1.0f}, true);
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OUT_OF_RANGE, accel_cal_solve(&col, &cal));
}

static void test_bias_at_limit_accepted(void)
{
    offer_all((vec3_t){0.19f, -0.19f, 0.0f}, (vec3_t){1.08f, 0.92f, 1.0f}, true);
    accel_cal_t cal;
    TEST_ASSERT_EQUAL(ACCEL_CAL_OK, accel_cal_solve(&col, &cal));
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 0.19f, cal.bias.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 0.92f, cal.scale.y);
}

static void test_hyperboloid_fails_fit(void)
{
    // Poses exactly on x^2 + y^2 - 0.1 z^2 = 1 (filled directly: the
    // magnitudes are beyond what the collector accepts). Every direction is
    // covered, but the fit has C < 0: not an ellipsoid.
    float t = tanf(LEVEL_DEG2RAD(28.0f));
    float z = 1.0f / sqrtf(t * t - 0.1f);
    float r = t * z;
    vec3_t poses[] = {
        {1.0f, 0.0f, 0.0f}, {-1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}, {0.0f, -1.0f, 0.0f},
        {r, 0.0f, z}, {-r, 0.0f, z}, {0.0f, r, z}, {0.0f, -r, z},
        {r, 0.0f, -z}, {-r, 0.0f, -z}, {0.0f, r, -z}, {0.0f, -r, -z},
    };
    col.n = 0;
    for (unsigned i = 0; i < sizeof(poses) / sizeof(poses[0]); i++) {
        col.poses[col.n++] = poses[i];
    }
    TEST_ASSERT_EQUAL_HEX8(ACCEL_CAL_DIR_ALL, accel_cal_coverage(&col));
    accel_cal_t cal = accel_cal_identity();
    TEST_ASSERT_EQUAL(ACCEL_CAL_FIT_FAILED, accel_cal_solve(&col, &cal));
    assert_vec3_within(0.0f, ((vec3_t){1.0f, 1.0f, 1.0f}), cal.scale);
}

// --- stillness window (accel_cal_collector_add_sample) ---

#define DT (1.0f / 448.4f)
// Samples in one ACCEL_CAL_WINDOW_S window: the first n with n * DT >= 0.5 s.
#define WINDOW_N 225u

// Feeds n samples of raw + alternating +-jitter on every axis; returns how
// many poses were kept.
static unsigned feed_still(vec3_t raw, float jitter, float gyro, unsigned n)
{
    unsigned kept = 0;
    for (unsigned i = 0; i < n; i++) {
        float j = (i & 1u) ? jitter : -jitter;
        vec3_t v = {raw.x + j, raw.y - j, raw.z + j};
        kept += accel_cal_collector_add_sample(&col, v, gyro, DT) ? 1u : 0u;
    }
    return kept;
}

static void test_window_keeps_still_pose(void)
{
    const vec3_t up = {0.02f, -0.01f, 1.01f};
    TEST_ASSERT_EQUAL_UINT(0, feed_still(up, 0.004f, 0.05f, WINDOW_N - 1u));
    TEST_ASSERT_EQUAL_UINT8(0, col.n);
    TEST_ASSERT_EQUAL_UINT(1, feed_still(up, 0.004f, 0.05f, 1));
    TEST_ASSERT_EQUAL_UINT8(1, col.n);
    // The pose is the window mean.
    assert_vec3_within(1e-4f, up, col.poses[0]);
}

static void test_window_same_pose_kept_once(void)
{
    const vec3_t up = {0.0f, 0.0f, 1.0f};
    // Holding still for several windows offers the pose again each time; the
    // separation rule keeps only the first.
    TEST_ASSERT_EQUAL_UINT(1, feed_still(up, 0.0f, 0.0f, 4u * WINDOW_N));
    TEST_ASSERT_EQUAL_UINT8(1, col.n);
}

static void test_window_restarts_on_gyro_motion(void)
{
    const vec3_t up = {0.0f, 1.0f, 0.0f};
    TEST_ASSERT_EQUAL_UINT(0, feed_still(up, 0.0f, 0.0f, WINDOW_N - 1u));
    // Turning (after bias) above the limit throws the window away.
    TEST_ASSERT_EQUAL_UINT(0, feed_still(up, 0.0f, ACCEL_CAL_MAX_GYRO_RAD_S + 0.01f, 1));
    TEST_ASSERT_EQUAL_UINT(0, feed_still(up, 0.0f, 0.0f, WINDOW_N - 1u));
    TEST_ASSERT_EQUAL_UINT(1, feed_still(up, 0.0f, 0.0f, 1));
}

static void test_window_restarts_when_accel_spreads(void)
{
    const vec3_t a = {1.0f, 0.0f, 0.0f};
    const vec3_t b = {1.0f, 0.0f, ACCEL_CAL_MAX_RANGE_G + 0.01f};
    TEST_ASSERT_EQUAL_UINT(0, feed_still(a, 0.0f, 0.0f, WINDOW_N - 1u));
    // b leaves the range: the window restarts with b as its first sample.
    TEST_ASSERT_EQUAL_UINT(0, feed_still(b, 0.0f, 0.0f, WINDOW_N - 1u));
    TEST_ASSERT_EQUAL_UINT8(0, col.n);
    TEST_ASSERT_EQUAL_UINT(1, feed_still(b, 0.0f, 0.0f, 1));
    assert_vec3_within(1e-5f, b, col.poses[0]);
}

static void test_window_rejects_noise_above_range(void)
{
    // Peak to peak 0.024 g > ACCEL_CAL_MAX_RANGE_G: never still.
    TEST_ASSERT_EQUAL_UINT(0, feed_still(AXIS_Z, 0.012f, 0.0f, 4u * WINDOW_N));
    // Peak to peak 0.016 g: still.
    accel_cal_collector_reset(&col);
    TEST_ASSERT_EQUAL_UINT(1, feed_still(AXIS_Z, 0.008f, 0.0f, WINDOW_N));
}

static void test_reset_clears_window(void)
{
    TEST_ASSERT_EQUAL_UINT(0, feed_still(AXIS_X, 0.0f, 0.0f, WINDOW_N - 1u));
    accel_cal_collector_reset(&col);
    TEST_ASSERT_EQUAL_UINT(0, feed_still(AXIS_X, 0.0f, 0.0f, WINDOW_N - 1u));
    TEST_ASSERT_EQUAL_UINT(1, feed_still(AXIS_X, 0.0f, 0.0f, 1));
}

static void test_cal_equal_compares_bias_and_scale(void)
{
    accel_cal_t a = accel_cal_identity();
    accel_cal_t b = accel_cal_identity();
    TEST_ASSERT_TRUE(accel_cal_equal(&a, &b));
    b.bias.y = 0.01f;
    TEST_ASSERT_FALSE(accel_cal_equal(&a, &b));
    b = a;
    b.scale.z = 1.01f;
    TEST_ASSERT_FALSE(accel_cal_equal(&a, &b));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_ellipsoid_derivation);
    RUN_TEST(test_identity_apply_is_noop);
    RUN_TEST(test_apply_inverts_the_model);
    RUN_TEST(test_recovers_bias_and_scale_from_faces_only);
    RUN_TEST(test_recovers_bias_and_scale_with_tilted_poses);
    RUN_TEST(test_perfect_sensor_gives_identity);
    RUN_TEST(test_noise_keeps_bias_within_3mg);
    RUN_TEST(test_missing_direction_is_poor_coverage);
    RUN_TEST(test_empty_collector_is_poor_coverage);
    RUN_TEST(test_coverage_bits);
    RUN_TEST(test_duplicate_pose_rejected);
    RUN_TEST(test_implausible_vector_rejected);
    RUN_TEST(test_collector_full);
    RUN_TEST(test_out_of_range_bias);
    RUN_TEST(test_out_of_range_scale);
    RUN_TEST(test_bias_at_limit_accepted);
    RUN_TEST(test_hyperboloid_fails_fit);
    RUN_TEST(test_window_keeps_still_pose);
    RUN_TEST(test_window_same_pose_kept_once);
    RUN_TEST(test_window_restarts_on_gyro_motion);
    RUN_TEST(test_window_restarts_when_accel_spreads);
    RUN_TEST(test_window_rejects_noise_above_range);
    RUN_TEST(test_reset_clears_window);
    RUN_TEST(test_cal_equal_compares_bias_and_scale);
    return UNITY_END();
}
