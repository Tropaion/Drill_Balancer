#include <math.h>
#include <stdint.h>
#include "unity.h"
#include "fusion.h"
#include "test_util.h"

#define DT (1.0f / 200.0f)
#define SAMPLES(seconds) ((int)((seconds) / DT + 0.5f))

static fusion_t f;
static fusion_config_t cfg;

void setUp(void)
{
    cfg = fusion_default_config();
    test_noise_seed(12345);
}

void tearDown(void) {}

// Angle between the filter's up estimate and the true up for orientation q.
static float tilt_error_deg(quat_t truth)
{
    // atan2 instead of acos: acosf near 1 is off by ~0.02 deg in float.
    vec3_t est = fusion_up_body(&f);
    vec3_t ref = accel_at_rest(truth);
    return LEVEL_RAD2DEG(atan2f(vec3_norm(vec3_cross(est, ref)), vec3_dot(est, ref)));
}

// Heading of the body X axis in the world frame, degrees.
static float body_x_heading_deg(void)
{
    vec3_t x = quat_rotate(f.q, AXIS_X);
    return LEVEL_RAD2DEG(atan2f(x.y, x.x));
}

static void test_init_takes_tilt_from_accel(void)
{
    quat_t truth = rot_deg(AXIS_X, 90.0f);
    fusion_init(&f, &cfg, accel_at_rest(truth));
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, tilt_error_deg(truth));

    truth = quat_mul(rot_deg(AXIS_Y, -35.0f), rot_deg(AXIS_X, 120.0f));
    fusion_init(&f, &cfg, accel_at_rest(truth));
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, tilt_error_deg(truth));
}

static void test_converges_to_accel_tilt(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    quat_t truth = rot_deg(AXIS_X, 20.0f);
    // The error decays with time constant 1/kp; 7 of them take 20 deg below 0.02.
    for (int i = 0; i < SAMPLES(7.0f / cfg.kp); i++) {
        fusion_update(&f, (vec3_t){0}, accel_at_rest(truth), DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.1f, 0.0f, tilt_error_deg(truth));
}

static void test_integrates_yaw_rotation(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    for (int i = 0; i < SAMPLES(1.0f); i++) {
        fusion_update(&f, (vec3_t){0, 0, LEVEL_DEG2RAD(90.0f)}, AXIS_Z, DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 90.0f, body_x_heading_deg());
}

static void test_tracks_fast_tilt_rotation(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    quat_t truth = quat_identity();
    const float rate = LEVEL_DEG2RAD(60.0f);
    for (int i = 0; i < SAMPLES(1.5f); i++) {
        truth = quat_normalize(quat_mul(truth, quat_from_axis_angle(AXIS_X, rate * DT)));
        fusion_update(&f, (vec3_t){rate, 0, 0}, accel_at_rest(truth), DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 0.0f, tilt_error_deg(truth));
}

static void test_ignores_accel_far_from_1g(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    // A sustained 1.5 g push sideways (e.g. the drill jerking) must not tilt
    // the estimate.
    for (int i = 0; i < SAMPLES(1.0f); i++) {
        fusion_update(&f, (vec3_t){0}, (vec3_t){1.1f, 0.0f, 1.0f}, DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 0.0f, tilt_error_deg(quat_identity()));
}

static void test_vibration_averages_out(void)
{
    quat_t truth = quat_mul(rot_deg(AXIS_Z, 10.0f), rot_deg(AXIS_X, 95.0f));
    vec3_t g = accel_at_rest(truth);
    fusion_init(&f, &cfg, g);
    for (int i = 0; i < SAMPLES(10.0f); i++) {
        vec3_t a = {g.x + 0.08f * test_noise(), g.y + 0.08f * test_noise(),
                    g.z + 0.08f * test_noise()};
        vec3_t w = {0.02f * test_noise(), 0.02f * test_noise(), 0.02f * test_noise()};
        fusion_update(&f, w, a, DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.3f, 0.0f, tilt_error_deg(truth));
}

static void test_learns_gyro_bias_when_still(void)
{
    const vec3_t bias = {0.01f, -0.02f, 0.015f};
    fusion_init(&f, &cfg, AXIS_Z);
    for (int i = 0; i < SAMPLES(20.0f); i++) {
        fusion_update(&f, bias, AXIS_Z, DT);
    }
    TEST_ASSERT_TRUE(fusion_bias_valid(&f));
    assert_vec3_within(0.001f, bias, f.gyro_bias);

    // With the bias removed the heading no longer drifts.
    float heading = body_x_heading_deg();
    for (int i = 0; i < SAMPLES(10.0f); i++) {
        fusion_update(&f, bias, AXIS_Z, DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.2f, heading, body_x_heading_deg());
}

static void test_slow_turn_is_not_learned_as_bias(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    for (int i = 0; i < SAMPLES(3.0f); i++) {
        fusion_update(&f, (vec3_t){0}, AXIS_Z, DT);
    }
    TEST_ASSERT_TRUE(fusion_bias_valid(&f));

    // Slowly aiming the drill left at 5 deg/s for 10 s.
    float start = body_x_heading_deg();
    for (int i = 0; i < SAMPLES(10.0f); i++) {
        fusion_update(&f, (vec3_t){0, 0, LEVEL_DEG2RAD(5.0f)}, AXIS_Z, DT);
    }
    assert_vec3_within(1e-4f, (vec3_t){0}, f.gyro_bias);
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 50.0f, wrap_deg(body_x_heading_deg() - start));
}

static void test_learns_a_large_bias(void)
{
    // The QMI8658 on the board read about this at rest, far above any
    // threshold on |gyro| itself.
    const vec3_t bias = {0.27f, 0.11f, -0.02f};
    quat_t truth = rot_deg(AXIS_X, 30.0f);
    fusion_init(&f, &cfg, accel_at_rest(truth));
    for (int i = 0; i < SAMPLES(3.0f); i++) {
        fusion_update(&f, vec3_add(bias, test_gauss_vec3(0.002f)), accel_at_rest(truth), DT);
    }
    TEST_ASSERT_TRUE(fusion_bias_valid(&f));
    assert_vec3_within(0.003f, bias, f.gyro_bias);

    // Neither the heading drifts nor the tilt settles off by bias / kp.
    float heading = body_x_heading_deg();
    for (int i = 0; i < SAMPLES(10.0f); i++) {
        fusion_update(&f, vec3_add(bias, test_gauss_vec3(0.002f)), accel_at_rest(truth), DT);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.5f, heading, body_x_heading_deg());
    TEST_ASSERT_FLOAT_WITHIN(0.3f, 0.0f, tilt_error_deg(truth));
}

static void test_bias_follows_a_change_between_still_periods(void)
{
    // The QMI8658's bias shifts with orientation (lying flat vs upright).
    const vec3_t flat = {0.112f, 0.266f, 0.021f};
    const vec3_t upright = {0.128f, 0.266f, 0.021f};
    fusion_init(&f, &cfg, AXIS_Z);
    for (int i = 0; i < SAMPLES(10.0f); i++) {
        fusion_update(&f, vec3_add(flat, test_gauss_vec3(0.002f)), AXIS_Z, DT);
    }
    assert_vec3_within(0.001f, flat, f.gyro_bias);

    // Picked up (not steady), set down again.
    for (int i = 0; i < SAMPLES(0.5f); i++) {
        fusion_update(&f, (vec3_t){1.0f, 0, 0}, AXIS_Z, DT);
    }
    // About a second for the recent mean to settle after the motion, then
    // still_time_s, then about a second of learning.
    for (int i = 0; i < SAMPLES(cfg.still_time_s + 2.5f); i++) {
        fusion_update(&f, vec3_add(upright, test_gauss_vec3(0.002f)), AXIS_Z, DT);
    }
    assert_vec3_within(0.001f, upright, f.gyro_bias);
}

static void test_bias_from_a_turn_is_learned_anew(void)
{
    // An even turn right after power-up looks like a bias...
    fusion_init(&f, &cfg, AXIS_Z);
    const vec3_t turn = {0, 0, LEVEL_DEG2RAD(5.0f)};
    for (int i = 0; i < SAMPLES(2.0f); i++) {
        fusion_update(&f, turn, AXIS_Z, DT);
    }
    TEST_ASSERT_TRUE(fusion_bias_valid(&f));
    assert_vec3_within(0.001f, turn, f.gyro_bias);

    // ... until the device lies steady but off it for relearn_after_s.
    for (int i = 0; i < SAMPLES(cfg.relearn_after_s + 3.0f); i++) {
        fusion_update(&f, (vec3_t){0}, AXIS_Z, DT);
    }
    TEST_ASSERT_TRUE(fusion_bias_valid(&f));
    assert_vec3_within(0.001f, (vec3_t){0}, f.gyro_bias);
    TEST_ASSERT_TRUE(fusion_is_still(&f));
}

static void test_even_tilt_at_power_up_is_no_bias(void)
{
    // Tilting at an even 10 deg/s keeps |a| at 1 g, but the accel turns.
    fusion_init(&f, &cfg, AXIS_Z);
    quat_t truth = quat_identity();
    const float rate = LEVEL_DEG2RAD(10.0f);
    for (int i = 0; i < SAMPLES(3.0f); i++) {
        truth = quat_normalize(quat_mul(truth, quat_from_axis_angle(AXIS_X, rate * DT)));
        fusion_update(&f, (vec3_t){rate, 0, 0}, accel_at_rest(truth), DT);
    }
    TEST_ASSERT_FALSE(fusion_bias_valid(&f));
    assert_vec3_within(1e-6f, (vec3_t){0}, f.gyro_bias);
}

static void test_fast_even_turn_is_never_a_bias(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    for (int i = 0; i < SAMPLES(3.0f); i++) {
        fusion_update(&f, (vec3_t){0, 0, 2.0f * cfg.bias_max_rad_s}, AXIS_Z, DT);
    }
    TEST_ASSERT_FALSE(fusion_bias_valid(&f));
    TEST_ASSERT_FALSE(fusion_is_still(&f));
    assert_vec3_within(1e-6f, (vec3_t){0}, f.gyro_bias);
}

static void test_still_needs_still_time(void)
{
    fusion_init(&f, &cfg, AXIS_Z);
    TEST_ASSERT_FALSE(fusion_is_still(&f));
    for (int i = 0; i < SAMPLES(cfg.still_time_s + 0.1f); i++) {
        fusion_update(&f, (vec3_t){0}, AXIS_Z, DT);
    }
    TEST_ASSERT_TRUE(fusion_is_still(&f));
    fusion_update(&f, (vec3_t){0.5f, 0, 0}, AXIS_Z, DT);
    TEST_ASSERT_FALSE(fusion_is_still(&f));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_init_takes_tilt_from_accel);
    RUN_TEST(test_converges_to_accel_tilt);
    RUN_TEST(test_integrates_yaw_rotation);
    RUN_TEST(test_tracks_fast_tilt_rotation);
    RUN_TEST(test_ignores_accel_far_from_1g);
    RUN_TEST(test_vibration_averages_out);
    RUN_TEST(test_learns_gyro_bias_when_still);
    RUN_TEST(test_slow_turn_is_not_learned_as_bias);
    RUN_TEST(test_learns_a_large_bias);
    RUN_TEST(test_bias_follows_a_change_between_still_periods);
    RUN_TEST(test_bias_from_a_turn_is_learned_anew);
    RUN_TEST(test_even_tilt_at_power_up_is_no_bias);
    RUN_TEST(test_fast_even_turn_is_never_a_bias);
    RUN_TEST(test_still_needs_still_time);
    return UNITY_END();
}
