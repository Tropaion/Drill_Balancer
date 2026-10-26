#include <math.h>
#include <stdio.h>
#include "fusion.h"
#include "lpf.h"
#include "test_util.h"
#include "unity.h"

#define FS_HZ 448.4f

void setUp(void)
{
    test_noise_seed(7);
}

void tearDown(void) {}

static float db(float g)
{
    return 20.0f * log10f(g);
}

static void test_butterworth_response(void)
{
    lpf_biquad_t c = lpf_butter2(LPF_LEVEL_ACCEL_HZ, FS_HZ);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.0f, lpf_gain(&c, 0.0f, FS_HZ));
    TEST_ASSERT_FLOAT_WITHIN(0.05f, -3.01f, db(lpf_gain(&c, LPF_LEVEL_ACCEL_HZ, FS_HZ)));
    // 12 dB per octave above the corner (analog -12.3 dB at twice it; the
    // bilinear transform adds a little towards Nyquist).
    TEST_ASSERT_FLOAT_WITHIN(0.5f, -12.3f, db(lpf_gain(&c, 2.0f * LPF_LEVEL_ACCEL_HZ, FS_HZ)));
    TEST_ASSERT_LESS_THAN_FLOAT(-40.0f, db(lpf_gain(&c, 10.0f * LPF_LEVEL_ACCEL_HZ, FS_HZ)));
    // Hand motion passes, the motor tones (147 Hz, 233 Hz aliased to 215 Hz) do not.
    TEST_ASSERT_GREATER_THAN_FLOAT(0.99f, lpf_gain(&c, 2.0f, FS_HZ));
    TEST_ASSERT_LESS_THAN_FLOAT(-43.0f, db(lpf_gain(&c, 147.0f, FS_HZ)));
    TEST_ASSERT_LESS_THAN_FLOAT(-55.0f, db(lpf_gain(&c, FS_HZ - 233.0f, FS_HZ)));
    // The LEVEL corners at 448.4 Hz, as imu_frame.c CTRL5_LEVEL documents them.
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 11.93f, LPF_LEVEL_ACCEL_HZ);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 24.17f, LPF_LEVEL_GYRO_HZ);
}

static void test_corner_is_clamped_below_nyquist(void)
{
    lpf_biquad_t c = lpf_butter2(1000.0f, FS_HZ);
    TEST_ASSERT_TRUE(isfinite(c.b0) && isfinite(c.a1) && isfinite(c.a2));
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 1.0f, lpf_gain(&c, 0.0f, FS_HZ));
}

// Measured against the time-domain filter, not only the formula.
static void test_sine_attenuation_in_time(void)
{
    lpf_vec3_t f;
    lpf_vec3_init(&f, LPF_LEVEL_ACCEL_HZ, FS_HZ);
    float peak = 0.0f;
    for (int i = 0; i < TEST_SAMPLES(2.0f); i++) {
        float x = sinf(LEVEL_TWO_PI * 60.0f * (float)i / FS_HZ);
        vec3_t y = lpf_vec3_step(&f, (vec3_t){x, 0.0f, 0.0f});
        if (i > TEST_SAMPLES(0.5f)) {
            peak = fmaxf(peak, fabsf(y.x));
        }
    }
    lpf_biquad_t c = lpf_butter2(LPF_LEVEL_ACCEL_HZ, FS_HZ);
    TEST_ASSERT_FLOAT_WITHIN(0.002f, lpf_gain(&c, 60.0f, FS_HZ), peak);
}

static void test_first_sample_primes(void)
{
    // A restart at 1 g on Z is no step: the output starts and stays there.
    lpf_vec3_t f;
    lpf_vec3_init(&f, LPF_LEVEL_ACCEL_HZ, FS_HZ);
    vec3_t g = {0.1f, -0.2f, 0.97f};
    for (int i = 0; i < 200; i++) {
        assert_vec3_within(1e-5f, g, lpf_vec3_step(&f, g));
    }
    // Init again: it primes on the new value, not the old state.
    lpf_vec3_init(&f, LPF_LEVEL_ACCEL_HZ, FS_HZ);
    vec3_t h = {0.0f, 1.0f, 0.0f};
    assert_vec3_within(1e-5f, h, lpf_vec3_step(&f, h));
}

// A profile switch under way (LEVEL -> RAW at 896.8 Hz): the filter goes on
// from the last sample fusion got, so the first raw sample (a vibration
// peak) moves the output by one step's worth, not all the way.
static void test_restart_goes_on_from_the_last_sample(void)
{
    lpf_imu_t f;
    lpf_imu_init(&f, FS_HZ);
    vec3_t last_a = {0.0f, 1.0f, 0.0f};
    vec3_t last_w = {0.01f, 0.0f, -0.02f};
    lpf_imu_restart(&f, 2.0f * FS_HZ, last_a, last_w);
    // The new rate's coefficients.
    lpf_biquad_t c = lpf_butter2(LPF_LEVEL_ACCEL_HZ, 2.0f * FS_HZ);
    TEST_ASSERT_EQUAL_FLOAT(c.b0, f.accel.c.b0);
    TEST_ASSERT_EQUAL_FLOAT(c.a1, f.accel.c.a1);
    // A 3 g peak as the first sample after the switch: a small move, not a
    // jump to 3 g (a filter primed by that sample would output it as is).
    vec3_t a = {0.0f, 3.0f, 0.0f}, w = last_w;
    lpf_imu_step(&f, &a, &w);
    TEST_ASSERT_LESS_THAN_FLOAT(1.05f, a.y);
    TEST_ASSERT_GREATER_THAN_FLOAT(1.0f, a.y);
    assert_vec3_within(1e-5f, last_w, w);
    // A steady input stays where it was.
    lpf_imu_restart(&f, 2.0f * FS_HZ, last_a, last_w);
    a = last_a;
    w = last_w;
    lpf_imu_step(&f, &a, &w);
    assert_vec3_within(1e-5f, last_a, a);
    assert_vec3_within(1e-5f, last_w, w);
}

// The sensor task's bookkeeping (lpf_feed_t): a switch goes on from the
// last sample fusion got, filtered (RAW) or as read (LEVEL); after a reset
// (IMU lost or back) there is none and the next sample primes.
static void test_feed_switch_goes_on_from_the_last_fed_sample(void)
{
    lpf_feed_t fd;
    lpf_feed_reset(&fd, FS_HZ);
    vec3_t a = {0.0f, 1.0f, 0.0f}, w = {0.01f, 0.0f, 0.0f};
    lpf_feed_step(&fd, false, &a, &w);           // LEVEL: as read, kept
    TEST_ASSERT_TRUE(fd.last_valid);
    assert_vec3_within(1e-6f, a, fd.last_accel_g);
    lpf_feed_switch(&fd, 2.0f * FS_HZ);          // to RAW897
    vec3_t pa = {0.0f, 3.0f, 0.0f}, pw = w;
    lpf_feed_step(&fd, true, &pa, &pw);          // a peak moves it a little
    TEST_ASSERT_GREATER_THAN_FLOAT(1.0f, pa.y);
    TEST_ASSERT_LESS_THAN_FLOAT(1.05f, pa.y);
    assert_vec3_within(1e-6f, pa, fd.last_accel_g);   // the filtered one is kept
    lpf_feed_reset(&fd, FS_HZ);
    TEST_ASSERT_FALSE(fd.last_valid);
    lpf_feed_switch(&fd, 2.0f * FS_HZ);          // nothing kept: the next one primes
    vec3_t b = {0.0f, 3.0f, 0.0f}, g = w;
    lpf_feed_step(&fd, true, &b, &g);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, 3.0f, b.y);
}

static void test_step_settles_without_ringing(void)
{
    lpf_vec3_t f;
    lpf_vec3_init(&f, LPF_LEVEL_ACCEL_HZ, FS_HZ);
    lpf_vec3_step(&f, (vec3_t){0});
    float top = 0.0f, last = 0.0f;
    for (int i = 0; i < TEST_SAMPLES(0.5f); i++) {
        last = lpf_vec3_step(&f, (vec3_t){1.0f, 0.0f, 0.0f}).x;
        top = fmaxf(top, last);
    }
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 1.0f, last);
    TEST_ASSERT_LESS_THAN_FLOAT(1.05f, top);   // Butterworth: about 4 % overshoot
}

// Hammer blows at 45 Hz along the bit: a 5 ms half sine of recoil, then a
// 15 ms one back at a third of it, so a blow moves the drill nowhere
// (integral 0). Smooth pulses: a step-like edge, sampled without the chip's
// LPF, would fold its harmonics down to a few Hz (10 x 45 Hz = 450 Hz lands
// at 1.6 Hz), and no filter after the sampler can take that out again (the
// aliasing check of the raw profiles, imu_frame.c CTRL5_RAW).
#define BLOW_PERIOD_S (1.0f / 45.0f)
#define BLOW_PI 3.14159265f

static float blows_g(float t, float peak_g)
{
    float d = fmodf(t + 0.01f, BLOW_PERIOD_S);   // not a blow at the priming sample
    if (d < 0.005f) {
        return peak_g * sinf(BLOW_PI * d / 0.005f);
    }
    d -= 0.005f;
    return d < 0.015f ? -peak_g / 3.0f * sinf(BLOW_PI * d / 0.015f) : 0.0f;
}

// Hammer blows on top of gravity: the filter keeps the mean (unity
// DC gain) and takes off the peaks.
static void test_hammer_blows_lose_their_peaks_not_their_mean(void)
{
    lpf_imu_t f;
    lpf_imu_init(&f, FS_HZ);
    double sum_in = 0.0, sum_out = 0.0;
    float peak_in = 0.0f, peak_out = 0.0f;
    int n = TEST_SAMPLES(4.0f);
    for (int i = 0; i < n; i++) {
        float t = (float)i / FS_HZ;
        float y = 1.0f + blows_g(t, 8.0f) + 0.02f * test_gauss();
        vec3_t a = {0.0f, y, 0.0f};
        vec3_t w = {0.0f, 0.0f, 0.0f};
        lpf_imu_step(&f, &a, &w);
        if (i > TEST_SAMPLES(0.5f)) {
            sum_in += y;
            sum_out += a.y;
            peak_in = fmaxf(peak_in, fabsf(y - 1.0f));
            peak_out = fmaxf(peak_out, fabsf(a.y - 1.0f));
        }
    }
    int m = n - TEST_SAMPLES(0.5f) - 1;
    TEST_ASSERT_FLOAT_WITHIN(0.02f, 1.0f, (float)(sum_in / m));
    TEST_ASSERT_FLOAT_WITHIN(0.02f, (float)(sum_in / m), (float)(sum_out / m));
    TEST_ASSERT_GREATER_THAN_FLOAT(6.0f, peak_in);
    TEST_ASSERT_LESS_THAN_FLOAT(0.5f, peak_out);   // mostly the 45 Hz fundamental
}

// The reason for the filter: the motor's radial vibration (147 Hz and its
// harmonic, as the simulator's DrillScenario) and hammer blows within the
// +-16 g range of RAW448_16G. Fusion trusts the accelerometer only near
// 1 g, so it picks the samples where vibration and gravity happen to add up
// to about 1 g, and those lean one way: the raw samples tilt the reading,
// the filtered copy does not.
static float fusion_tilt(bool filtered)
{
    test_noise_seed(3);
    vec3_t up = {0.0f, 1.0f, 0.0f};          // pose_wall(): bit along -Z, Y up
    fusion_config_t cfg = fusion_default_config();
    fusion_t fu;
    fusion_init(&fu, &cfg, up);
    lpf_imu_t f;
    lpf_imu_init(&f, FS_HZ);
    float worst = 0.0f;
    for (int i = 0; i < TEST_SAMPLES(10.0f); i++) {
        float t = (float)i / FS_HZ;
        float p = LEVEL_TWO_PI * 147.0f * t;
        float amp = 0.3f;
        vec3_t a = vec3_add(up, (vec3_t){amp * (sinf(p) + 0.4f * sinf(2.0f * p)),
                                         amp * (cosf(p) + 0.4f * cosf(2.0f * p + 0.7f)),
                                         0.3f * amp * sinf(p + 1.1f)});
        a.z += blows_g(t, 7.0f);
        a = vec3_add(a, test_gauss_vec3(0.01f));
        vec3_t w = test_gauss_vec3(0.03f);
        if (filtered) {
            lpf_imu_step(&f, &a, &w);
        }
        fusion_update(&fu, w, a, 1.0f / FS_HZ);
        if (t > 1.0f) {
            float c = fminf(1.0f, vec3_dot(fusion_up_body(&fu), up));
            worst = fmaxf(worst, LEVEL_RAD2DEG(acosf(c)));
        }
    }
    return worst;
}

static void test_fusion_under_vibration_with_and_without_filter(void)
{
    float raw = fusion_tilt(false);
    float filtered = fusion_tilt(true);
    printf("tilt raw %.2f deg, filtered %.2f deg\n", (double)raw, (double)filtered);
    TEST_ASSERT_GREATER_THAN_FLOAT(3.0f, raw);
    TEST_ASSERT_LESS_THAN_FLOAT(1.0f, filtered);
    TEST_ASSERT_LESS_THAN_FLOAT(0.1f * raw, filtered);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_butterworth_response);
    RUN_TEST(test_corner_is_clamped_below_nyquist);
    RUN_TEST(test_sine_attenuation_in_time);
    RUN_TEST(test_first_sample_primes);
    RUN_TEST(test_restart_goes_on_from_the_last_sample);
    RUN_TEST(test_feed_switch_goes_on_from_the_last_fed_sample);
    RUN_TEST(test_step_settles_without_ringing);
    RUN_TEST(test_hammer_blows_lose_their_peaks_not_their_mean);
    RUN_TEST(test_fusion_under_vibration_with_and_without_filter);
    return UNITY_END();
}
