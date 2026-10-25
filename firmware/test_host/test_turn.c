#include <math.h>
#include <stdint.h>
#include "unity.h"
#include "turn.h"
#include "test_util.h"

#define BENCH_NOISE_RAD_S 0.002f      // tools/sim physics.py GYRO_NOISE_RAD_S
// physics.py adds 0.1 rad/s of gyro noise per g of vibration; 2 g is the
// panel's maximum.
#define MOTOR_NOISE_RAD_S 0.2f

static turn_detector_t d;
static turn_config_t cfg;

// Bias-corrected gyro signals (rad/s): a motion plus white noise.
typedef vec3_t (*signal_fn)(float t);
static float t_now;
static float noise_rad_s;
static float amp;       // rad/s, or the turn angle in rad
static float freq_hz;
static float move_s;    // duration of a turn
static vec3_t axis;

static vec3_t with_noise(vec3_t w)
{
    return vec3_add(w, test_gauss_vec3(noise_rad_s));
}

static vec3_t held(float t)
{
    (void)t;
    return with_noise((vec3_t){0.0f, 0.0f, 0.0f});
}

// A turn by `amp` rad about `axis` in move_s (minimum jerk, rest to rest),
// starting at t = 0.
static vec3_t turn(float t)
{
    float r = 0.0f;
    if (t >= 0.0f && t <= move_s) {
        float u = t / move_s;
        r = amp / move_s * 30.0f * u * u * (1.0f - u) * (1.0f - u);
    }
    return with_noise(vec3_scale(axis, r));
}

// Rocking at freq_hz with a rate amplitude of `amp`, in the board X/Y plane.
static vec3_t rocking(float t)
{
    float w = LEVEL_TWO_PI * freq_hz * t;
    return with_noise((vec3_t){amp * cosf(w), amp * sinf(w), 0.0f});
}

// A motor start at t = 0: the tone sweeps up from 0 to freq_hz in 0.3 s,
// its amplitude rising to `amp` by 30 Hz, and the kick rings at 15 Hz with
// 1.5 `amp`, decaying in 50 ms.
static vec3_t spin_up(float t)
{
    float f = freq_hz * fminf(t / 0.3f, 1.0f);
    float phase = t < 0.3f ? LEVEL_TWO_PI * 0.5f * freq_hz / 0.3f * t * t
                           : LEVEL_TWO_PI * (0.5f * freq_hz * 0.3f + freq_hz * (t - 0.3f));
    float a = amp * fminf(f / 30.0f, 1.0f);
    float ring = 1.5f * amp * expf(-t / 0.05f) * sinf(LEVEL_TWO_PI * 15.0f * t);
    return with_noise((vec3_t){a * cosf(phase) + ring, a * sinf(phase), ring});
}

static void use(void)
{
    turn_init(&d, &cfg);
}

// Feeds `seconds` of sig from t = 0; returns the samples that read turning
// and sets *first to the time of the first one (-1 if none).
static int feed(signal_fn sig, float seconds, float *first)
{
    int n = 0;
    if (first) {
        *first = -1.0f;
    }
    for (int i = 0; i < TEST_SAMPLES(seconds); i++) {
        t_now = (float)i * TEST_IMU_DT;
        if (turn_add_sample(&d, sig(t_now), TEST_IMU_DT)) {
            if (n == 0 && first) {
                *first = t_now;
            }
            n++;
        }
    }
    return n;
}

void setUp(void)
{
    cfg = turn_default_config();
    t_now = 0.0f;
    noise_rad_s = BENCH_NOISE_RAD_S;
    amp = 1.0f;
    freq_hz = 10.0f;
    move_s = 1.0f;
    axis = AXIS_X;
    test_noise_seed(12345);
    use();
}

void tearDown(void) {}

static void test_default_config(void)
{
    TEST_ASSERT_EQUAL_FLOAT(3.0f, cfg.lp_hz);
    TEST_ASSERT_EQUAL_FLOAT(0.3f, cfg.rate_rad_s);
}

static void test_held_still_never_turns(void)
{
    TEST_ASSERT_EQUAL_FLOAT(0.0f, d.rate);
    TEST_ASSERT_EQUAL_INT(0, feed(held, 60.0f, NULL));
    TEST_ASSERT_LESS_THAN_FLOAT(0.01f, d.rate);
}

static void test_motor_noise_held_still_never_turns(void)
{
    // The sim's strongest vibration: most samples pass 0.3 rad/s on their
    // own, which the low-pass averages out.
    noise_rad_s = MOTOR_NOISE_RAD_S;
    int raw_above = 0;
    for (int i = 0; i < TEST_SAMPLES(1.0f); i++) {
        raw_above += vec3_norm(held(0.0f)) > cfg.rate_rad_s;
    }
    TEST_ASSERT_GREATER_THAN_INT(TEST_SAMPLES(0.5f), raw_above);
    TEST_ASSERT_EQUAL_INT(0, feed(held, 180.0f, NULL));
}

static void test_motor_tones_never_turn(void)
{
    // A chuck imbalance rocks the drill: 1 rad/s at 10 Hz is about 1 degree
    // each way, more than a drill held in both hands moves.
    static const float hz[] = {10.0f, 20.0f, 33.0f, 147.0f, 233.0f};
    noise_rad_s = MOTOR_NOISE_RAD_S;
    for (size_t k = 0; k < sizeof hz / sizeof hz[0]; k++) {
        use();
        freq_hz = hz[k];
        TEST_ASSERT_EQUAL_INT_MESSAGE(0, feed(rocking, 30.0f, NULL), "tone");
    }
}

static void test_motor_start_and_stop_never_turn(void)
{
    // Spin-up sweeps through the low frequencies, and the kick rings in the
    // hand; each start and stop also switches the motor noise on or off.
    freq_hz = 150.0f;
    for (int k = 0; k < 20; k++) {
        noise_rad_s = MOTOR_NOISE_RAD_S;
        TEST_ASSERT_EQUAL_INT(0, feed(spin_up, 3.0f, NULL));
        noise_rad_s = BENCH_NOISE_RAD_S;
        TEST_ASSERT_EQUAL_INT(0, feed(held, 0.5f, NULL));
    }
    // Stuttering: 60 ms on, 20 ms off.
    for (int k = 0; k < 200; k++) {
        noise_rad_s = MOTOR_NOISE_RAD_S;
        TEST_ASSERT_EQUAL_INT(0, feed(held, 0.06f, NULL));
        noise_rad_s = BENCH_NOISE_RAD_S;
        TEST_ASSERT_EQUAL_INT(0, feed(held, 0.02f, NULL));
    }
}

// Feeds a turn of `deg` in `seconds` followed by 1 s at rest. Asserts that
// it reads turning within the move and at rest again 0.3 s after it.
static void assert_turn_counts(float deg, float seconds)
{
    amp = LEVEL_DEG2RAD(deg);
    move_s = seconds;
    float first = -1.0f;
    TEST_ASSERT_GREATER_THAN_INT(0, feed(turn, seconds, &first));
    TEST_ASSERT_TRUE(first >= 0.0f && first < seconds);
    feed(held, 0.3f, NULL);
    TEST_ASSERT_EQUAL_INT(0, feed(held, 1.0f, NULL));
}

static void test_brisk_handling_turns(void)
{
    // Repositioning between holes, shaking the accelerometer far past the
    // vibration detector's threshold: the gyro alone decides.
    assert_turn_counts(90.0f, 0.25f);
    axis = AXIS_Y;
    assert_turn_counts(90.0f, 0.4f);
    axis = AXIS_Z;
    assert_turn_counts(90.0f, 1.0f);
    // A short flick of the wrist.
    assert_turn_counts(10.0f, 0.2f);
    assert_turn_counts(20.0f, 1.0f);
}

static void test_turn_while_the_motor_runs_counts(void)
{
    noise_rad_s = MOTOR_NOISE_RAD_S;
    assert_turn_counts(90.0f, 1.0f);
    assert_turn_counts(10.0f, 0.2f);
}

static void test_steady_rate_threshold(void)
{
    // A steady turn (rocking at 0 Hz: amp about X) passes the low-pass
    // unchanged.
    noise_rad_s = 0.0f;
    freq_hz = 0.0f;
    float first = -1.0f;
    amp = 1.2f * cfg.rate_rad_s;
    TEST_ASSERT_GREATER_THAN_INT(0, feed(rocking, 2.0f, &first));
    TEST_ASSERT_TRUE(first < 0.3f);
    use();
    amp = 0.9f * cfg.rate_rad_s;
    TEST_ASSERT_EQUAL_INT(0, feed(rocking, 10.0f, NULL));
    TEST_ASSERT_FLOAT_WITHIN(0.001f, amp, d.rate);
}

static void test_turn_right_after_reset_counts(void)
{
    // Picked up out of deep sleep, or the IMU came back: the filter starts
    // at rest, and a turn from the first sample counts within the move.
    assert_turn_counts(90.0f, 1.0f);
    feed(turn, 0.5f, NULL);
    TEST_ASSERT_GREATER_THAN_FLOAT(cfg.rate_rad_s, d.rate);
    turn_reset(&d);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, d.rate);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, d.m1.x);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, d.m2.x);
    TEST_ASSERT_EQUAL_FLOAT(cfg.lp_hz, d.cfg.lp_hz);
    assert_turn_counts(30.0f, 0.4f);
    // Restarted while the motor runs: its noise from the first sample on is
    // no turn.
    turn_reset(&d);
    noise_rad_s = MOTOR_NOISE_RAD_S;
    TEST_ASSERT_EQUAL_INT(0, feed(held, 10.0f, NULL));
}

static void test_move_right_after_motor_stops_counts(void)
{
    noise_rad_s = MOTOR_NOISE_RAD_S;
    TEST_ASSERT_EQUAL_INT(0, feed(held, 30.0f, NULL));
    // Straight to the next hole: counts from the first samples of the move,
    // whether the motor is still spinning down or has stopped.
    for (int k = 0; k < 2; k++) {
        noise_rad_s = k ? BENCH_NOISE_RAD_S : MOTOR_NOISE_RAD_S;
        float first = -1.0f;
        amp = LEVEL_DEG2RAD(20.0f);
        move_s = 0.5f;
        TEST_ASSERT_GREATER_THAN_INT(0, feed(turn, 0.5f, &first));
        TEST_ASSERT_TRUE(first >= 0.0f && first < 0.3f);
        // Drilling again.
        noise_rad_s = MOTOR_NOISE_RAD_S;
        feed(held, 0.3f, NULL);
        TEST_ASSERT_EQUAL_INT(0, feed(held, 5.0f, NULL));
    }
}

static void test_zero_dt_holds(void)
{
    // A period of 0 (or NaN) leaves the filter where it was.
    amp = 1.0f;
    freq_hz = 0.0f;
    feed(rocking, 1.0f, NULL);
    vec3_t m2 = d.m2;
    TEST_ASSERT_TRUE(turn_add_sample(&d, (vec3_t){50.0f, 0.0f, 0.0f}, 0.0f));
    TEST_ASSERT_TRUE(turn_add_sample(&d, (vec3_t){50.0f, 0.0f, 0.0f}, NAN));
    assert_vec3_within(1e-6f, m2, d.m2);
    // lp_hz 0 passes every sample through.
    cfg.lp_hz = 0.0f;
    use();
    TEST_ASSERT_TRUE(turn_add_sample(&d, (vec3_t){0.0f, 0.4f, 0.0f}, TEST_IMU_DT));
    TEST_ASSERT_FALSE(turn_add_sample(&d, (vec3_t){0.0f, 0.2f, 0.0f}, TEST_IMU_DT));
    turn_add_sample(&d, (vec3_t){0.0f, 0.4f, 0.0f}, 0.0f);
    TEST_ASSERT_TRUE(isfinite(d.rate));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_default_config);
    RUN_TEST(test_held_still_never_turns);
    RUN_TEST(test_motor_noise_held_still_never_turns);
    RUN_TEST(test_motor_tones_never_turn);
    RUN_TEST(test_motor_start_and_stop_never_turn);
    RUN_TEST(test_brisk_handling_turns);
    RUN_TEST(test_turn_while_the_motor_runs_counts);
    RUN_TEST(test_steady_rate_threshold);
    RUN_TEST(test_turn_right_after_reset_counts);
    RUN_TEST(test_move_right_after_motor_stops_counts);
    RUN_TEST(test_zero_dt_holds);
    return UNITY_END();
}
