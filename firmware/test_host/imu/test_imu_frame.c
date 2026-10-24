#include <math.h>
#include <string.h>
#include "unity.h"
#include "imu_frame.h"
#include "test_util.h"

// The sensor task's FIFO_BATCH.
#define BATCH 64u

static const imu_axis_map_t IDENTITY = {{0, 1}, {1, 1}, {2, 1}};

void setUp(void) {}
void tearDown(void) {}

static void put16(uint8_t *p, int16_t v)
{
    p[0] = (uint8_t)((uint16_t)v & 0xFF);
    p[1] = (uint8_t)((uint16_t)v >> 8);
}

static void make_frame(uint8_t f[IMU_FRAME_BYTES], int16_t ax, int16_t ay, int16_t az,
                       int16_t gx, int16_t gy, int16_t gz)
{
    put16(&f[0], ax);
    put16(&f[2], ay);
    put16(&f[4], az);
    put16(&f[6], gx);
    put16(&f[8], gy);
    put16(&f[10], gz);
}

// Plan with a trusted TIMESTAMP and room for a whole batch.
static imu_gap_plan_t plan(const imu_gap_t *g, uint32_t ts, size_t frames)
{
    return imu_gap_plan(g, ts, frames, true, BATCH);
}

static void test_axis_map_validation(void)
{
    TEST_ASSERT_TRUE(imu_axis_map_valid(&IDENTITY));
    imu_axis_map_t rotated = {{1, -1}, {0, 1}, {2, 1}};
    TEST_ASSERT_TRUE(imu_axis_map_valid(&rotated));

    imu_axis_map_t dup = {{0, 1}, {0, -1}, {2, 1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&dup));
    imu_axis_map_t zero_sign = {{0, 1}, {1, 0}, {2, 1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&zero_sign));
    imu_axis_map_t big_sign = {{0, 2}, {1, 1}, {2, 1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&big_sign));
    imu_axis_map_t bad_src = {{0, 1}, {1, 1}, {3, 1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&bad_src));

    // Mirror images (determinant -1) would turn the gyro the wrong way
    // relative to the accelerometer.
    imu_axis_map_t flipped_z = {{0, 1}, {1, 1}, {2, -1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&flipped_z));
    imu_axis_map_t swapped = {{1, 1}, {0, 1}, {2, 1}};
    TEST_ASSERT_FALSE(imu_axis_map_valid(&swapped));
    imu_axis_map_t upside_down = {{0, 1}, {1, -1}, {2, -1}};
    TEST_ASSERT_TRUE(imu_axis_map_valid(&upside_down));
    imu_axis_map_t cyclic = {{1, 1}, {2, 1}, {0, 1}};
    TEST_ASSERT_TRUE(imu_axis_map_valid(&cyclic));
}

static void test_decode_is_little_endian_and_scaled(void)
{
    uint8_t f[IMU_FRAME_BYTES];
    // 1 g on X, -1 g on Y, 0.5 g on Z; 1 dps, -1 dps, 100 dps.
    make_frame(f, 8192, -8192, 4096, 64, -64, 6400);
    TEST_ASSERT_EQUAL_HEX8(0x00, f[0]);
    TEST_ASSERT_EQUAL_HEX8(0x20, f[1]);

    imu_sample_t s;
    imu_decode_frame(&IDENTITY, f, &s);
    assert_vec3_within(1e-6f, (vec3_t){1.0f, -1.0f, 0.5f}, s.accel_g);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, LEVEL_DEG2RAD(1.0f), s.gyro_rad_s.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, LEVEL_DEG2RAD(-1.0f), s.gyro_rad_s.y);
    TEST_ASSERT_FLOAT_WITHIN(1e-5f, LEVEL_DEG2RAD(100.0f), s.gyro_rad_s.z);
}

static void test_decode_full_scale_extremes(void)
{
    uint8_t f[IMU_FRAME_BYTES];
    make_frame(f, INT16_MIN, INT16_MAX, 0, INT16_MIN, INT16_MAX, 0);
    imu_sample_t s;
    imu_decode_frame(&IDENTITY, f, &s);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, -4.0f, s.accel_g.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, 4.0f, s.accel_g.y);
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, LEVEL_DEG2RAD(-512.0f), s.gyro_rad_s.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-3f, LEVEL_DEG2RAD(512.0f), s.gyro_rad_s.y);
}

static void test_decode_applies_axis_map_to_both_sensors(void)
{
    // Board X = -chip Y, board Y = -chip X, board Z = -chip Z (a rotation).
    imu_axis_map_t map = {{1, -1}, {0, -1}, {2, -1}};
    TEST_ASSERT_TRUE(imu_axis_map_valid(&map));
    uint8_t f[IMU_FRAME_BYTES];
    make_frame(f, 8192, 4096, 2048, 640, 320, 64);
    imu_sample_t s;
    imu_decode_frame(&map, f, &s);
    assert_vec3_within(1e-6f, (vec3_t){-0.5f, -1.0f, -0.25f}, s.accel_g);
    assert_vec3_within(1e-6f,
                       (vec3_t){LEVEL_DEG2RAD(-5.0f), LEVEL_DEG2RAD(-10.0f), LEVEL_DEG2RAD(-1.0f)},
                       s.gyro_rad_s);
}

static void test_fifo_frame_count(void)
{
    TEST_ASSERT_EQUAL_UINT(0, imu_fifo_frames(0, 0));
    TEST_ASSERT_EQUAL_UINT(0, imu_fifo_frames(5, 0));      // 10 bytes: no full frame
    TEST_ASSERT_EQUAL_UINT(1, imu_fifo_frames(6, 0));
    TEST_ASSERT_EQUAL_UINT(1, imu_fifo_frames(7, 0));
    TEST_ASSERT_EQUAL_UINT(21, imu_fifo_frames(126, 0));
    // Full 1536-byte FIFO: 768 words, MSBs in FIFO_STATUS[1:0]; the flag
    // bits (full, watermark, overflow, not empty) must not leak in.
    TEST_ASSERT_EQUAL_UINT(128, imu_fifo_frames(0x00, 0x03));
    TEST_ASSERT_EQUAL_UINT(128, imu_fifo_frames(0x00, 0xF3));
    TEST_ASSERT_EQUAL_UINT(64, imu_fifo_frames(0x80, 0xD1));
}

static void test_gap_first_read_has_no_gap(void)
{
    imu_gap_t g = {0};
    imu_gap_plan_t p = plan(&g, 5000, 7);
    TEST_ASSERT_EQUAL_UINT32(0, p.lost);
    imu_gap_commit(&g, 5000, 7, 7);
    TEST_ASSERT_TRUE(g.valid);
    TEST_ASSERT_EQUAL_UINT32(5001, g.next_ts);
    TEST_ASSERT_EQUAL_UINT(0, g.contiguous);
}

static void test_gap_steady_reads_lose_nothing(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);
    for (uint32_t ts = 105; ts < 200; ts += 5) {
        imu_gap_plan_t p = plan(&g, ts, 5);
        TEST_ASSERT_EQUAL_UINT32(0, p.lost);
        imu_gap_commit(&g, ts, 5, 5);
    }
    TEST_ASSERT_EQUAL_UINT32(196, g.next_ts);
}

static void test_gap_after_full_drain_is_before_first_frame(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);           // read 96..100
    // 101 and 102 were dropped in read mode, 103..107 are in the FIFO.
    imu_gap_plan_t p = plan(&g, 107, 5);
    TEST_ASSERT_EQUAL_UINT32(2, p.lost);
    TEST_ASSERT_EQUAL_UINT(0, p.insert_at);
    imu_gap_commit(&g, 107, 5, 5);
    TEST_ASSERT_EQUAL_UINT32(108, g.next_ts);
    TEST_ASSERT_EQUAL_UINT32(0, plan(&g, 110, 3).lost);
}

static void test_gap_after_partial_read_sits_behind_old_frames(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 10, 4);          // FIFO 91..100, read 91..94
    TEST_ASSERT_EQUAL_UINT32(95, g.next_ts);
    TEST_ASSERT_EQUAL_UINT(6, g.contiguous);

    // 101, 102 lost during that read, 103..105 arrived: FIFO 95..100, 103..105.
    imu_gap_plan_t p = plan(&g, 105, 9);
    TEST_ASSERT_EQUAL_UINT32(2, p.lost);
    TEST_ASSERT_EQUAL_UINT(6, p.insert_at);

    // Reading only old frames does not consume the gap.
    imu_gap_commit(&g, 105, 9, 3);           // read 95..97
    TEST_ASSERT_EQUAL_UINT32(98, g.next_ts);
    TEST_ASSERT_EQUAL_UINT(3, g.contiguous);
    p = plan(&g, 105, 6);
    TEST_ASSERT_EQUAL_UINT32(2, p.lost);
    TEST_ASSERT_EQUAL_UINT(3, p.insert_at);

    imu_gap_commit(&g, 105, 6, 6);
    TEST_ASSERT_EQUAL_UINT32(106, g.next_ts);
    TEST_ASSERT_EQUAL_UINT(0, g.contiguous);
}

static void test_gap_across_timestamp_wrap(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 0xFFFFFE, 2, 2);
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFF, g.next_ts);
    // 0xFFFFFF and 0x000000 lost, FIFO holds 1 and 2.
    imu_gap_plan_t p = plan(&g, 2, 2);
    TEST_ASSERT_EQUAL_UINT32(2, p.lost);
    imu_gap_commit(&g, 2, 2, 2);
    TEST_ASSERT_EQUAL_UINT32(3, g.next_ts);
    TEST_ASSERT_EQUAL_UINT32(0, plan(&g, 4, 2).lost);
}

static void test_gap_inconsistent_or_huge_resyncs(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);           // next 101
    // FIFO claims frames older than already read: count/timestamp race.
    TEST_ASSERT_EQUAL_UINT32(0, plan(&g, 103, 5).lost);
    imu_gap_commit(&g, 103, 5, 5);
    TEST_ASSERT_EQUAL_UINT32(104, g.next_ts);

    TEST_ASSERT_EQUAL_UINT32(IMU_GAP_MAX, plan(&g, 104 + IMU_GAP_MAX + 4, 5).lost);
    TEST_ASSERT_EQUAL_UINT32(0, plan(&g, 104 + IMU_GAP_MAX + 5, 5).lost);
    imu_gap_commit(&g, 104 + IMU_GAP_MAX + 5, 5, 5);
    TEST_ASSERT_EQUAL_UINT32(104 + IMU_GAP_MAX + 6, g.next_ts);
}

static void test_plan_reads_everything_without_gap(void)
{
    imu_gap_t g = {0};
    imu_gap_plan_t p = plan(&g, 5000, 7);
    TEST_ASSERT_EQUAL_UINT(7, p.n_read);
    TEST_ASSERT_EQUAL_UINT(0, p.n_fill);
    // Bounded by the output and by what one read can return.
    TEST_ASSERT_EQUAL_UINT(3, imu_gap_plan(&g, 5000, 7, true, 3).n_read);
    TEST_ASSERT_EQUAL_UINT(IMU_FIFO_MAX_FRAMES,
                           imu_gap_plan(&g, 5000, 170, true, 1000).n_read);
}

static void test_plan_fills_gap(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);           // next 101
    // 101, 102 lost; 103..107 in the FIFO.
    imu_gap_plan_t p = plan(&g, 107, 5);
    TEST_ASSERT_EQUAL_UINT(5, p.n_read);
    TEST_ASSERT_EQUAL_UINT(2, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(0, p.insert_at);
}

static void test_plan_untrusted_timestamp_fills_nothing(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);
    imu_gap_plan_t p = imu_gap_plan(&g, 107, 5, false, BATCH);
    TEST_ASSERT_EQUAL_UINT32(2, p.lost);
    TEST_ASSERT_EQUAL_UINT(0, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(5, p.n_read);
}

static void test_plan_caps_fill_to_capacity(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 5, 5);           // next 101
    // 101..110 lost, 111..118 in the FIFO, room for 6: 5 filled, then the
    // first sample after the gap.
    imu_gap_plan_t p = imu_gap_plan(&g, 118, 8, true, 6);
    TEST_ASSERT_EQUAL_UINT32(10, p.lost);
    TEST_ASSERT_EQUAL_UINT(0, p.insert_at);
    TEST_ASSERT_EQUAL_UINT(5, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(1, p.n_read);

    // A smaller gap fits; the read shrinks to leave room for the fill.
    // 101..103 lost, 104 and 105 in the FIFO, room for 3.
    p = imu_gap_plan(&g, 105, 2, true, 3);
    TEST_ASSERT_EQUAL_UINT32(3, p.lost);
    TEST_ASSERT_EQUAL_UINT(2, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(1, p.n_read);
}

static void test_plan_defers_gap_behind_old_samples(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 10, 4);          // FIFO 91..100, read 91..94
    // 101..104 lost, 105..107 arrived: FIFO 95..100, 105..107. With room for
    // 8, the six old samples fit but the gap does not: read only those and
    // leave the gap for the next call.
    imu_gap_plan_t p = imu_gap_plan(&g, 107, 9, true, 8);
    TEST_ASSERT_EQUAL_UINT32(4, p.lost);
    TEST_ASSERT_EQUAL_UINT(6, p.insert_at);
    TEST_ASSERT_EQUAL_UINT(0, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(6, p.n_read);
    imu_gap_commit(&g, 107, 9, p.n_read);

    // Next call: the gap now sits at the head and is filled.
    p = imu_gap_plan(&g, 107, 3, true, 8);
    TEST_ASSERT_EQUAL_UINT(0, p.insert_at);
    TEST_ASSERT_EQUAL_UINT(4, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(3, p.n_read);

    // With enough room the old samples, the fill and the new ones go in one
    // call.
    imu_gap_t g2 = {0};
    imu_gap_commit(&g2, 100, 10, 4);
    p = imu_gap_plan(&g2, 107, 9, true, BATCH);
    TEST_ASSERT_EQUAL_UINT(6, p.insert_at);
    TEST_ASSERT_EQUAL_UINT(4, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(9, p.n_read);
}

static void test_plan_only_old_samples_read_leaves_gap(void)
{
    imu_gap_t g = {0};
    imu_gap_commit(&g, 100, 10, 4);          // FIFO 91..100, read 91..94
    // Room for only 3: the read stops before the gap, nothing is filled.
    imu_gap_plan_t p = imu_gap_plan(&g, 105, 9, true, 3);
    TEST_ASSERT_EQUAL_UINT(6, p.insert_at);
    TEST_ASSERT_EQUAL_UINT(0, p.n_fill);
    TEST_ASSERT_EQUAL_UINT(3, p.n_read);
}

static void test_fill_gap_interpolates_between_neighbours(void)
{
    imu_sample_t a = {.accel_g = {0.0f, 1.0f, 0.0f}, .gyro_rad_s = {3.0f, 0.0f, -3.0f}};
    imu_sample_t b = {.accel_g = {3.0f, 1.0f, 0.0f}, .gyro_rad_s = {0.0f, 0.0f, 3.0f}};
    imu_sample_t out[3];
    memset(out, 0x7F, sizeof(out));
    imu_fill_gap(&a, &b, 2, out);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 1.0f, out[0].accel_g.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 2.0f, out[1].accel_g.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 1.0f, out[1].accel_g.y);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 2.0f, out[0].gyro_rad_s.x);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, -1.0f, out[0].gyro_rad_s.z);
    TEST_ASSERT_FLOAT_WITHIN(1e-6f, 1.0f, out[1].gyro_rad_s.z);
    // Only n samples are written.
    uint8_t untouched[sizeof(imu_sample_t)];
    memset(untouched, 0x7F, sizeof(untouched));
    TEST_ASSERT_EQUAL_MEMORY(untouched, &out[2], sizeof(imu_sample_t));
}

static void test_rate_estimate(void)
{
    const float nominal = 1.0f / 448.4f;
    imu_rate_t r;
    imu_rate_init(&r, nominal);
    TEST_ASSERT_EQUAL_FLOAT(nominal, r.period_s);

    // True rate 440 Hz, polled every 10 ms, starting near the wrap point.
    uint32_t ts0 = 0xFFFF00;
    int64_t t = 1000000;
    for (int i = 0; i <= 150; i++) {
        uint32_t ts = (ts0 + (uint32_t)(i * 44 / 10)) & IMU_TS_MASK;
        imu_rate_update(&r, ts, t + (int64_t)i * 10000);
    }
    // 1.5 s baseline: still nominal.
    TEST_ASSERT_EQUAL_FLOAT(nominal, r.period_s);
    TEST_ASSERT_FALSE(r.measured);
    for (int i = 151; i <= 300; i++) {
        uint32_t ts = (ts0 + (uint32_t)(i * 44 / 10)) & IMU_TS_MASK;
        imu_rate_update(&r, ts, t + (int64_t)i * 10000);
    }
    TEST_ASSERT_FLOAT_WITHIN(1e-7f, 1.0f / 440.0f, r.period_s);
    TEST_ASSERT_TRUE(r.measured);
}

static void test_rate_rejects_implausible(void)
{
    const float nominal = 1.0f / 448.4f;
    imu_rate_t r;
    imu_rate_init(&r, nominal);
    // 300 Hz is a third off: a bad measurement, not a slow gyro.
    for (int i = 0; i <= 300; i++) {
        imu_rate_update(&r, (uint32_t)(i * 3), (int64_t)i * 10000);
    }
    TEST_ASSERT_EQUAL_FLOAT(nominal, r.period_s);
    // The driver only trusts TIMESTAMP for gap filling once it was measured.
    TEST_ASSERT_FALSE(r.measured);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_axis_map_validation);
    RUN_TEST(test_decode_is_little_endian_and_scaled);
    RUN_TEST(test_decode_full_scale_extremes);
    RUN_TEST(test_decode_applies_axis_map_to_both_sensors);
    RUN_TEST(test_fifo_frame_count);
    RUN_TEST(test_gap_first_read_has_no_gap);
    RUN_TEST(test_gap_steady_reads_lose_nothing);
    RUN_TEST(test_gap_after_full_drain_is_before_first_frame);
    RUN_TEST(test_gap_after_partial_read_sits_behind_old_frames);
    RUN_TEST(test_gap_across_timestamp_wrap);
    RUN_TEST(test_gap_inconsistent_or_huge_resyncs);
    RUN_TEST(test_plan_reads_everything_without_gap);
    RUN_TEST(test_plan_fills_gap);
    RUN_TEST(test_plan_untrusted_timestamp_fills_nothing);
    RUN_TEST(test_plan_caps_fill_to_capacity);
    RUN_TEST(test_plan_defers_gap_behind_old_samples);
    RUN_TEST(test_plan_only_old_samples_read_leaves_gap);
    RUN_TEST(test_fill_gap_interpolates_between_neighbours);
    RUN_TEST(test_rate_estimate);
    RUN_TEST(test_rate_rejects_implausible);
    return UNITY_END();
}
