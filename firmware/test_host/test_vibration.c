#include <math.h>
#include <stdint.h>
#include "unity.h"
#include "vibration.h"
#include "test_util.h"

#define BENCH_NOISE_G 0.0015f         // tools/sim physics.py ACCEL_NOISE_G

static vib_detector_t d;
static vib_config_t cfg;

// What the signals below add up: gravity, white noise and one of the
// vibration patterns, all in g.
typedef vec3_t (*signal_fn)(float t);
static float t_now;
static vec3_t up;
static float noise_g;
static float amp_g;
static float freq_hz;

static vec3_t with_noise(vec3_t a)
{
    return vec3_add(a, test_gauss_vec3(noise_g));
}

static vec3_t bench(float t)
{
    (void)t;
    return with_noise(up);
}

// Chuck imbalance: a vector turning in the board X/Y plane at freq_hz.
static vec3_t drill(float t)
{
    float w = LEVEL_TWO_PI * freq_hz * t;
    return with_noise(vec3_add(up, (vec3_t){amp_g * cosf(w), amp_g * sinf(w), 0.0f}));
}

// A tone on X only: its magnitude crosses the threshold for part of each cycle.
static vec3_t tone_x(float t)
{
    return with_noise(vec3_add(up, (vec3_t){amp_g * sinf(LEVEL_TWO_PI * freq_hz * t), 0.0f, 0.0f}));
}

// Blows every 1 / freq_hz s: an 80 Hz ring of amp_g decaying with 20 ms.
static vec3_t hammer(float t)
{
    float since = fmodf(t, 1.0f / freq_hz);
    float ring = amp_g * expf(-since / 0.02f) * sinf(LEVEL_TWO_PI * 80.0f * since);
    return with_noise(vec3_add(up, (vec3_t){ring, 0.0f, ring}));
}

// Walking: a vertical bounce at freq_hz.
static vec3_t walk(float t)
{
    return with_noise(vec3_add(up, vec3_scale(up, amp_g * sinf(LEVEL_TWO_PI * freq_hz * t))));
}

static void use(void)
{
    vib_init(&d, &cfg);
}

// Feeds one sample with period dt. Returns true when it closed a window.
static bool sample_dt(signal_fn sig, float dt)
{
    t_now += dt;
    return vib_add_sample(&d, sig(t_now), dt);
}

static bool sample(signal_fn sig)
{
    return sample_dt(sig, TEST_IMU_DT);
}

// Feeds until `windows` windows have closed; returns the samples fed.
static int feed_windows(signal_fn sig, int windows)
{
    int n = 0;
    while (windows > 0) {
        n++;
        if (sample(sig)) {
            windows--;
        }
    }
    return n;
}

// Feeds `seconds`; returns whether the state was `s` at any sample.
static bool ever(signal_fn sig, float seconds, vib_state_t s)
{
    bool seen = false;
    for (int i = 0; i < TEST_SAMPLES(seconds); i++) {
        sample(sig);
        seen |= vib_state(&d) == s;
    }
    return seen;
}

// Bench until QUIET, ending on a window boundary.
static void settle_quiet(void)
{
    feed_windows(bench, cfg.quiet_windows);
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
}

// The samples in one window at TEST_IMU_DT (224 or 225, depending on float rounding).
static int window_samples(void)
{
    vib_detector_t saved = d;
    vib_init(&d, &cfg);
    int n = feed_windows(bench, 1);
    d = saved;
    return n;
}

void setUp(void)
{
    cfg = vib_default_config();
    t_now = 0.0f;
    up = AXIS_Z;
    noise_g = BENCH_NOISE_G;
    amp_g = 0.1f;
    freq_hz = 10.0f;
    test_noise_seed(12345);
    use();
}

void tearDown(void) {}

static void test_default_config(void)
{
    TEST_ASSERT_EQUAL_FLOAT(6.0f, cfg.hp_hz);
    TEST_ASSERT_EQUAL_FLOAT(0.05f, cfg.amp_g);
    TEST_ASSERT_EQUAL_FLOAT(0.5f, cfg.duty);
    TEST_ASSERT_EQUAL_FLOAT(0.5f, cfg.window_s);
    TEST_ASSERT_EQUAL_UINT8(3, cfg.on_windows);
    TEST_ASSERT_EQUAL_UINT8(4, cfg.quiet_windows);
}

static void test_starts_unknown(void)
{
    TEST_ASSERT_EQUAL(VIB_UNKNOWN, vib_state(&d));
    // The first sample primes the filter: gravity is no step.
    feed_windows(bench, 1);
    TEST_ASSERT_LESS_THAN_FLOAT(0.01f, d.last_duty);
    // Three calm windows are not a decision yet.
    feed_windows(bench, cfg.quiet_windows - 2);
    TEST_ASSERT_EQUAL(VIB_UNKNOWN, vib_state(&d));
}

static void test_bench_noise_becomes_quiet_exactly(void)
{
    int w = window_samples();
    TEST_ASSERT_INT_WITHIN(1, TEST_SAMPLES(cfg.window_s), w);
    for (int i = 1; i < cfg.quiet_windows * w; i++) {
        TEST_ASSERT_EQUAL(i % w == 0, sample(bench));
        TEST_ASSERT_EQUAL(VIB_UNKNOWN, vib_state(&d));
    }
    TEST_ASSERT_TRUE(sample(bench));
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    TEST_ASSERT_LESS_THAN_FLOAT(0.01f, d.last_duty);
    TEST_ASSERT_FALSE(ever(bench, 60.0f, VIB_RUNNING));
}

static void test_bench_noise_x10_never_running(void)
{
    noise_g = 10.0f * BENCH_NOISE_G;
    TEST_ASSERT_FALSE(ever(bench, 60.0f, VIB_RUNNING));
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
}

static void test_running_drill_after_on_windows(void)
{
    settle_quiet();
    int w = window_samples();
    // Not one sample before the third shaking window closes.
    for (int i = 1; i < cfg.on_windows * w; i++) {
        sample(drill);
        TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    }
    TEST_ASSERT_TRUE(sample(drill));
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    TEST_ASSERT_GREATER_THAN_FLOAT(0.9f, d.last_duty);
    TEST_ASSERT_FALSE(ever(drill, 60.0f, VIB_QUIET));
}

static void test_stop_back_to_quiet_after_quiet_windows(void)
{
    settle_quiet();
    feed_windows(drill, cfg.on_windows);
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    int w = window_samples();
    for (int i = 1; i < cfg.quiet_windows * w; i++) {
        sample(bench);
        TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    }
    TEST_ASSERT_TRUE(sample(bench));
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
}

static void test_mixed_windows_keep_state(void)
{
    // Time hysteresis: alternating windows never complete a run.
    settle_quiet();
    for (int i = 0; i < 20; i++) {
        feed_windows(i % 2 ? bench : drill, 1);
        TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    }
    feed_windows(drill, cfg.on_windows);
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    for (int i = 0; i < 20; i++) {
        feed_windows(i % 2 ? drill : bench, 1);
        TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    }
    // Three calm windows in a row are still not enough.
    feed_windows(bench, cfg.quiet_windows - 1);
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
}

static void test_duty_threshold(void)
{
    // 100 Hz passes the 6 Hz high-pass almost unchanged. |sin| > 1/2 for
    // 2/3 of the time: shaking.
    freq_hz = 100.0f;
    amp_g = 2.0f * cfg.amp_g;
    settle_quiet();
    feed_windows(tone_x, cfg.on_windows);
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 0.667f, d.last_duty);

    // |sin| > 1/1.2 for 37 % of the time: calm, however long it lasts. (At
    // 448 Hz a window samples few distinct phases of 100 Hz, so a window's
    // duty spreads around that.)
    use();
    amp_g = 1.2f * cfg.amp_g;
    TEST_ASSERT_FALSE(ever(tone_x, 30.0f, VIB_RUNNING));
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    TEST_ASSERT_FLOAT_WITHIN(0.08f, 0.373f, d.last_duty);
}

static void test_single_knock_stays_quiet(void)
{
    settle_quiet();
    freq_hz = 0.01f;   // one blow in the next 100 s
    amp_g = 2.0f;
    t_now = 0.0f;
    TEST_ASSERT_FALSE(ever(hammer, 10.0f, VIB_RUNNING));
}

static void test_hammer_blows_stay_quiet(void)
{
    settle_quiet();
    freq_hz = 2.0f;
    amp_g = 0.5f;
    TEST_ASSERT_FALSE(ever(hammer, 30.0f, VIB_RUNNING));
}

static void test_slow_handling_and_walking_stay_quiet(void)
{
    settle_quiet();
    // A 90 degree turn in 1 s (smoothstep), screen up to edge up, and back.
    for (int k = 0; k < 10; k++) {
        for (int i = 0; i < TEST_SAMPLES(1.0f); i++) {
            float s = (float)i / (float)TEST_SAMPLES(1.0f);
            float a = 1.5707963f * s * s * (3.0f - 2.0f * s);
            if (k % 2) {
                a = 1.5707963f - a;
            }
            up = (vec3_t){0.0f, sinf(a), cosf(a)};
            sample(bench);
            TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
        }
    }
    // Walking with it: 0.3 g at 2 Hz is cut about 10x.
    amp_g = 0.3f;
    freq_hz = 2.0f;
    TEST_ASSERT_FALSE(ever(walk, 30.0f, VIB_RUNNING));
}

static void test_gravity_orientation_independent(void)
{
    // The same vibration in the board X/Y plane with the screen up (all of it
    // perpendicular to gravity) and with the edge up (half of it along it).
    int n[2];
    for (int k = 0; k < 2; k++) {
        setUp();
        up = k ? AXIS_Y : AXIS_Z;
        settle_quiet();
        n[k] = 0;
        while (vib_state(&d) != VIB_RUNNING) {
            sample(drill);
            n[k]++;
        }
    }
    TEST_ASSERT_EQUAL_INT(n[0], n[1]);
}

static void test_reset_returns_unknown(void)
{
    settle_quiet();
    feed_windows(drill, cfg.on_windows);
    TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    vib_reset(&d);
    TEST_ASSERT_EQUAL(VIB_UNKNOWN, vib_state(&d));
    TEST_ASSERT_EQUAL_FLOAT(cfg.amp_g, d.cfg.amp_g);
    // The IMU came back on another side: the first sample primes the filter,
    // so the 1.4 g jump is no step.
    up = (vec3_t){0.0f, -1.0f, 0.0f};
    feed_windows(bench, 1);
    TEST_ASSERT_LESS_THAN_FLOAT(0.01f, d.last_duty);
    feed_windows(bench, cfg.quiet_windows - 1);
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    TEST_ASSERT_LESS_THAN_FLOAT(0.01f, d.last_duty);
}

static void test_dt_jitter(void)
{
    // The windows count sample time, so +-10 % jitter in the period (a late
    // pass, a measured ODR off the nominal) gives the same decisions.
    settle_quiet();
    int n = 0;
    while (vib_state(&d) != VIB_RUNNING) {
        sample_dt(drill, TEST_IMU_DT * (1.0f + 0.1f * test_noise()));
        n++;
        TEST_ASSERT_TRUE(n < TEST_SAMPLES(5.0f));
    }
    TEST_ASSERT_INT_WITHIN(TEST_SAMPLES(0.1f), TEST_SAMPLES(cfg.on_windows * cfg.window_s), n);
    for (int i = 0; i < TEST_SAMPLES(20.0f); i++) {
        sample_dt(drill, TEST_IMU_DT * (1.0f + 0.1f * test_noise()));
        TEST_ASSERT_EQUAL(VIB_RUNNING, vib_state(&d));
    }
}

static void test_zero_dt_does_not_freeze(void)
{
    // A period of 0 (or NaN) adds no window time: the sample cap closes the
    // windows, and the filter holds instead of blowing up.
    int n = 0;
    while (!vib_add_sample(&d, bench(0.0f), 0.0f)) {
        n++;
        TEST_ASSERT_TRUE(n < 5000);
    }
    TEST_ASSERT_EQUAL_INT(1023, n);
    for (int i = 0; i < 3 * 1024; i++) {
        vib_add_sample(&d, bench(0.0f), NAN);
    }
    TEST_ASSERT_EQUAL(VIB_QUIET, vib_state(&d));
    TEST_ASSERT_TRUE(isfinite(d.m1.z) && isfinite(d.m2.z));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_default_config);
    RUN_TEST(test_starts_unknown);
    RUN_TEST(test_bench_noise_becomes_quiet_exactly);
    RUN_TEST(test_bench_noise_x10_never_running);
    RUN_TEST(test_running_drill_after_on_windows);
    RUN_TEST(test_stop_back_to_quiet_after_quiet_windows);
    RUN_TEST(test_mixed_windows_keep_state);
    RUN_TEST(test_duty_threshold);
    RUN_TEST(test_single_knock_stays_quiet);
    RUN_TEST(test_hammer_blows_stay_quiet);
    RUN_TEST(test_slow_handling_and_walking_stay_quiet);
    RUN_TEST(test_gravity_orientation_independent);
    RUN_TEST(test_reset_returns_unknown);
    RUN_TEST(test_dt_jitter);
    RUN_TEST(test_zero_dt_does_not_freeze);
    return UNITY_END();
}
