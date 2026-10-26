#include "unity.h"
#include "rec_ts.h"
#include "imu_ts.h"

void setUp(void) {}
void tearDown(void) {}

static void test_near(void)
{
    TEST_ASSERT_EQUAL_HEX32(0x01000002u, rec_ts_near(0x00FFFFFFu, 2));
    TEST_ASSERT_EQUAL_HEX32(0x00FFFFFEu, rec_ts_near(0x01000001u, 0xFFFFFE));
    TEST_ASSERT_EQUAL_HEX32(0x05123456u, rec_ts_near(0x05123450u, 0x123456));
    // Wraps like uint32_t.
    TEST_ASSERT_EQUAL_HEX32(0x00000001u, rec_ts_near(0xFFFFFFFEu, 0x000001));
}

static void test_extend_across_wraps(void)
{
    rec_ts_ext_t e = {0};
    TEST_ASSERT_EQUAL_HEX32(0xFFFF00u, rec_ts_extend(&e, 0xFFFF00));
    TEST_ASSERT_EQUAL_HEX32(0x1000010u, rec_ts_extend(&e, 0x000010));
    TEST_ASSERT_EQUAL_HEX32(0x1000100u, rec_ts_extend(&e, 0x000100));
    // One sample older than the last read: one older, not a wrap ahead.
    TEST_ASSERT_EQUAL_HEX32(0x10000FFu, rec_ts_extend(&e, 0x0000FF));
    TEST_ASSERT_EQUAL_HEX32(0x1000100u, rec_ts_extend(&e, 0x000100));
    // Many wraps in steps below half a wrap.
    uint32_t want = 0x1000100u;
    for (int i = 0; i < 100; i++) {
        want += 0x7FFF00u;
        TEST_ASSERT_EQUAL_HEX32(want, rec_ts_extend(&e, want & IMU_TS_MASK));
    }
    // The first value keeps its 24 bits.
    rec_ts_ext_t f = {0};
    TEST_ASSERT_EQUAL_HEX32(0x123456u, rec_ts_extend(&f, 0xFF123456u));
}

static void test_rate_needs_a_baseline(void)
{
    rec_rate_t r;
    rec_rate_reset(&r);
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_mhz(&r));
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_period_ns(&r));
    rec_rate_update(&r, 0, 1000, 5000000);
    rec_rate_update(&r, 0, 1000 + 15999, 5000000 + 999999);
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_mhz(&r));
    rec_rate_update(&r, 0, 1000 + 16000, 5000000 + 1000000);
    TEST_ASSERT_EQUAL_UINT32(16000000u, rec_rate_mhz(&r));
    TEST_ASSERT_EQUAL_UINT32(62500u, rec_rate_period_ns(&r));
}

static void test_rate_measures_odd_clocks(void)
{
    // 16000.9 Hz audio over 60 s, blocks of 256 frames.
    rec_rate_t r;
    rec_rate_reset(&r);
    for (int b = 0; b <= 3750; b++) {
        uint64_t frames = (uint64_t)b * 256u;
        int64_t us = 1000000 + (int64_t)((double)frames * 1e6 / 16000.9 + 0.5);
        rec_rate_update(&r, 0, frames, us);
    }
    TEST_ASSERT_UINT32_WITHIN(2, 16000900u, rec_rate_mhz(&r));
    // 4479 samples in 9.999129 s (447.94 Hz): 2232447 ns each.
    rec_rate_t i;
    rec_rate_reset(&i);
    rec_rate_update(&i, 0, 0xFFFF00u, 0);
    rec_rate_update(&i, 0, 0xFFFF00u + 4479u, 9999129);
    TEST_ASSERT_UINT32_WITHIN(5, 2232447u, rec_rate_period_ns(&i));
}

// An IMU reset restarts TIMESTAMP: the estimate starts over with the new
// epoch instead of freezing on counts below the old start or measuring
// across the restart.
static void test_rate_starts_over_with_a_new_epoch(void)
{
    rec_rate_t r;
    rec_rate_reset(&r);
    rec_rate_update(&r, 1, 0x500000u, 0);
    rec_rate_update(&r, 1, 0x500000u + 4484u, 10000000);
    TEST_ASSERT_UINT32_WITHIN(1, 448400u, rec_rate_mhz(&r));
    // Counting anew from near 0, then from above the old start.
    rec_rate_update(&r, 2, 10, 10200000);
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_mhz(&r));
    rec_rate_update(&r, 2, 10 + 897u, 11200000);
    TEST_ASSERT_UINT32_WITHIN(1, 897000u, rec_rate_mhz(&r));
    rec_rate_update(&r, 3, 0x600000u, 11300000);
    rec_rate_update(&r, 3, 0x600000u + 448u, 12300000);
    TEST_ASSERT_UINT32_WITHIN(1, 448000u, rec_rate_mhz(&r));
}

static void test_rate_ignores_stale_pairs(void)
{
    rec_rate_t r;
    rec_rate_reset(&r);
    rec_rate_update(&r, 0, 0, 0);
    rec_rate_update(&r, 0, 1000, 2000000);
    TEST_ASSERT_EQUAL_UINT32(500000u, rec_rate_mhz(&r));
    // Same or earlier host time, or a count below the start: ignored.
    rec_rate_update(&r, 0, 5000, 2000000);
    rec_rate_update(&r, 0, 5000, 1000000);
    TEST_ASSERT_EQUAL_UINT32(500000u, rec_rate_mhz(&r));
    // No progress of the counter: unknown.
    rec_rate_t s;
    rec_rate_reset(&s);
    rec_rate_update(&s, 0, 7, 0);
    rec_rate_update(&s, 0, 7, 3000000);
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_mhz(&s));
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_period_ns(&s));
    // Reset starts over.
    rec_rate_reset(&r);
    TEST_ASSERT_EQUAL_UINT32(0, rec_rate_mhz(&r));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_near);
    RUN_TEST(test_extend_across_wraps);
    RUN_TEST(test_rate_needs_a_baseline);
    RUN_TEST(test_rate_measures_odd_clocks);
    RUN_TEST(test_rate_starts_over_with_a_new_epoch);
    RUN_TEST(test_rate_ignores_stale_pairs);
    return UNITY_END();
}
