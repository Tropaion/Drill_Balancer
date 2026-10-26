#include <math.h>
#include "unity.h"
#include "level.h"
#include "test_util.h"

static level_t lv;
static level_config_t cfg;

void setUp(void)
{
    cfg = level_default_config();
    level_init(&lv, &cfg);
}

void tearDown(void) {}

// Wall pose with the bit pitched up by elev_deg and turned left (CCW seen
// from above) by left_deg.
static quat_t pose_wall_aimed(float elev_deg, float left_deg)
{
    return quat_mul(rot_deg(AXIS_Z, left_deg), rot_deg(AXIS_X, 90.0f + elev_deg));
}

static void test_wall_pose_reads_zero_elevation(void)
{
    level_reading_t r = level_compute(&lv, pose_wall(), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.elevation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, r.status);
}

static void test_bit_pitched_up_reads_positive_elevation(void)
{
    level_reading_t r = level_compute(&lv, pose_wall_aimed(30.0f, 0.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 30.0f, r.elevation_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 30.0f, r.deviation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_OFF, r.status);
}

static void test_elevation_ignores_roll_about_bit(void)
{
    quat_t q = quat_mul(pose_wall_aimed(12.0f, 0.0f), rot_deg(AXIS_Z, 70.0f));
    level_reading_t r = level_compute(&lv, q, 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 12.0f, r.elevation_deg);
}

static void test_yaw_invalid_until_zeroed(void)
{
    level_reading_t r = level_compute(&lv, pose_wall(), 0);
    TEST_ASSERT_FALSE(r.yaw_valid);
}

static void test_turning_left_after_zero_reads_negative_yaw(void)
{
    level_zero(&lv, pose_wall(), 1000);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(0.0f, 10.0f), 2000);
    TEST_ASSERT_TRUE(r.yaw_valid);
    TEST_ASSERT_FALSE(r.yaw_stale);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, -10.0f, r.yaw_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_OFF, r.yaw_status);
}

static void test_zero_makes_elevation_relative(void)
{
    level_zero(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(7.3f, 0.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.3f, r.elevation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, r.status);

    level_clear_zero(&lv);
    r = level_compute(&lv, pose_wall_aimed(7.3f, 0.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 7.3f, r.elevation_deg);
    TEST_ASSERT_FALSE(r.yaw_valid);
}

// What app_state_set_motion() publishes with the reading (LEVEL records).
static void test_zero_of_gives_the_zero_in_effect(void)
{
    float z[3] = {-1.0f, -1.0f, -1.0f};
    TEST_ASSERT_NULL(level_zero_of(&lv, z));
    level_zero(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    TEST_ASSERT_EQUAL_PTR(z, level_zero_of(&lv, z));
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 7.0f, z[0]);
    TEST_ASSERT_EQUAL_FLOAT(lv.zero_bubble_x_deg, z[1]);
    TEST_ASSERT_EQUAL_FLOAT(lv.zero_bubble_y_deg, z[2]);
    level_clear_zero(&lv);
    TEST_ASSERT_NULL(level_zero_of(&lv, z));
}

static void test_yaw_goes_stale(void)
{
    level_zero(&lv, pose_wall(), 1000);
    level_reading_t r = level_compute(&lv, pose_wall(), 1000 + cfg.yaw_stale_ms + 1);
    TEST_ASSERT_TRUE(r.yaw_stale);
}

static void test_yaw_invalid_when_bit_vertical(void)
{
    level_zero(&lv, quat_identity(), 0);
    level_reading_t r = level_compute(&lv, quat_identity(), 0);
    TEST_ASSERT_FALSE(r.yaw_valid);
}

static void test_floor_pose_is_on_target(void)
{
    // Identity: screen up, bit pointing straight down.
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_reading_t r = level_compute(&lv, quat_identity(), 0);
    TEST_ASSERT_EQUAL(LEVEL_MODE_FLOOR, r.mode);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.deviation_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.bubble_x_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.bubble_y_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, r.status);
}

static void test_floor_bubble_moves_towards_high_side(void)
{
    // Tilt about X: the screen's top edge (+Y) goes up.
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_reading_t r = level_compute(&lv, rot_deg(AXIS_X, 5.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 5.0f, r.deviation_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.bubble_x_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 5.0f, r.bubble_y_deg);

    // Tilt about Y by -3: the right edge (+X) goes up.
    r = level_compute(&lv, rot_deg(AXIS_Y, -3.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 3.0f, r.bubble_x_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.bubble_y_deg);
}

static void test_ceiling_pose_is_on_target(void)
{
    // Screen facing down at the user, bit pointing straight up.
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_reading_t r = level_compute(&lv, rot_deg(AXIS_X, 180.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.deviation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, r.status);
}

static void test_angle_mode_measures_against_target(void)
{
    lv.cfg.target_deg = 45.0f;
    level_set_mode(&lv, LEVEL_MODE_ANGLE);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(44.0f, 0.0f), 0);
    TEST_ASSERT_EQUAL(LEVEL_MODE_ANGLE, r.mode);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 44.0f, r.elevation_deg);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, -1.0f, r.deviation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_NEAR, r.status);
}

static void test_status_bands(void)
{
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, level_status_for(&cfg, cfg.tolerance_deg));
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, level_status_for(&cfg, -cfg.tolerance_deg));
    TEST_ASSERT_EQUAL(LEVEL_STATUS_NEAR, level_status_for(&cfg, -cfg.near_deg));
    TEST_ASSERT_EQUAL(LEVEL_STATUS_OFF, level_status_for(&cfg, cfg.near_deg + 0.01f));
}

static void test_init_starts_in_wall_mode(void)
{
    TEST_ASSERT_EQUAL(LEVEL_MODE_WALL, lv.mode);
    TEST_ASSERT_EQUAL(LEVEL_MODE_WALL, level_compute(&lv, pose_wall(), 0).mode);
}

static void test_wall_zero_cleared_when_switching_to_floor(void)
{
    // A wall zero (bit horizontal) stores a bubble zero of about 90 degrees;
    // carried into floor mode it would pin the bubble at the rim.
    level_zero(&lv, pose_wall(), 0);
    TEST_ASSERT_TRUE(level_set_mode(&lv, LEVEL_MODE_FLOOR));
    TEST_ASSERT_FALSE(lv.zero_set);
    TEST_ASSERT_EQUAL(LEVEL_MODE_FLOOR, lv.mode);
    level_reading_t r = level_compute(&lv, quat_identity(), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.deviation_deg);
    TEST_ASSERT_EQUAL(LEVEL_STATUS_ON_TARGET, r.status);
}

static void test_floor_zero_cleared_when_switching_to_wall(void)
{
    // A floor zero (bit down) stores elevation -90.
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_zero(&lv, quat_identity(), 0);
    TEST_ASSERT_TRUE(level_set_mode(&lv, LEVEL_MODE_WALL));
    level_reading_t r = level_compute(&lv, pose_wall(), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.0f, r.elevation_deg);
}

static void test_floor_zero_cleared_when_switching_to_angle(void)
{
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_zero(&lv, quat_identity(), 0);
    TEST_ASSERT_TRUE(level_set_mode(&lv, LEVEL_MODE_ANGLE));
    TEST_ASSERT_FALSE(lv.zero_set);
}

static void test_zero_kept_between_wall_and_angle(void)
{
    // Both judge the elevation, so the zero means the same in both.
    level_zero(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_ANGLE));
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_WALL));
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_WALL));
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_ANGLE));
    TEST_ASSERT_TRUE(lv.zero_set);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(7.3f, 0.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.3f, r.elevation_deg);
}

static void test_zero_kept_from_floor_to_floor(void)
{
    level_set_mode(&lv, LEVEL_MODE_FLOOR);
    level_zero(&lv, rot_deg(AXIS_X, 3.0f), 0);
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_FLOOR));
    TEST_ASSERT_TRUE(lv.zero_set);
}

static void test_mode_change_without_zero_reports_nothing_cleared(void)
{
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_FLOOR));
    TEST_ASSERT_EQUAL(LEVEL_MODE_FLOOR, lv.mode);
}

static void test_invalid_mode_is_ignored(void)
{
    level_zero(&lv, pose_wall(), 0);
    TEST_ASSERT_FALSE(level_set_mode(&lv, LEVEL_MODE_COUNT));
    TEST_ASSERT_FALSE(level_set_mode(&lv, (level_mode_t)-1));
    TEST_ASSERT_EQUAL(LEVEL_MODE_WALL, lv.mode);
    TEST_ASSERT_TRUE(lv.zero_set);
}

static void test_new_drill_axis_clears_zero(void)
{
    // The zero angles were measured about the old axis.
    level_zero(&lv, pose_wall(), 0);
    level_config_t c = cfg;
    c.drill_axis = vec3_normalize((vec3_t){0.05f, 0.0f, -1.0f});
    TEST_ASSERT_TRUE(level_set_config(&lv, &c));
    TEST_ASSERT_FALSE(lv.zero_set);
    TEST_ASSERT_EQUAL_FLOAT(c.drill_axis.x, lv.cfg.drill_axis.x);
    // Without a zero there is nothing to report, but the axis still changes.
    TEST_ASSERT_FALSE(level_set_config(&lv, &cfg));
    TEST_ASSERT_EQUAL_FLOAT(0.0f, lv.cfg.drill_axis.x);
}

static void test_other_config_changes_keep_zero(void)
{
    level_set_mode(&lv, LEVEL_MODE_ANGLE);
    level_zero(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    level_config_t c = cfg;
    c.tolerance_deg = 1.0f;
    c.near_deg = 3.0f;
    c.target_deg = 30.0f;
    c.yaw_stale_ms = 1000;
    TEST_ASSERT_FALSE(level_set_config(&lv, &c));
    TEST_ASSERT_TRUE(lv.zero_set);
    TEST_ASSERT_EQUAL(LEVEL_MODE_ANGLE, lv.mode);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, -30.0f, r.deviation_deg);
}

static void test_heading_reset_keeps_gravity_zero(void)
{
    // After a fusion restart (yaw frame back at 0) yaw is invalid until the
    // next zero; the elevation zero still applies.
    level_zero(&lv, pose_wall_aimed(7.0f, 0.0f), 0);
    level_reset_heading(&lv);
    TEST_ASSERT_TRUE(lv.zero_set);
    level_reading_t r = level_compute(&lv, pose_wall_aimed(7.3f, 0.0f), 0);
    TEST_ASSERT_FALSE(r.yaw_valid);
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.3f, r.elevation_deg);

    level_zero(&lv, pose_wall(), 0);
    TEST_ASSERT_TRUE(level_compute(&lv, pose_wall(), 0).yaw_valid);
}

// --- mount calibration ---

// Board mounted on the drill with a small rotation: board -> drill frame.
static quat_t mount_offset(void)
{
    return quat_mul(rot_deg(AXIS_X, 3.0f), rot_deg(AXIS_Y, -2.0f));
}

// Board orientation with the bit in a hole pitched up by hole_elev_deg and the
// drill rolled about the bit by roll_deg.
static quat_t board_in_hole(quat_t mount, float hole_elev_deg, float roll_deg)
{
    quat_t drill = quat_mul(pose_wall_aimed(hole_elev_deg, 25.0f), rot_deg(AXIS_Z, roll_deg));
    return quat_mul(drill, mount);
}

static void roll_in_hole(level_mount_cal_t *cal, quat_t mount, float hole_elev_deg, float roll_range_deg)
{
    level_mount_cal_reset(cal);
    for (float roll = -roll_range_deg; roll <= roll_range_deg; roll += 2.0f) {
        level_mount_cal_add(cal, accel_at_rest(board_in_hole(mount, hole_elev_deg, roll)));
    }
}

static void test_mount_cal_finds_drill_axis(void)
{
    level_mount_cal_t cal;
    roll_in_hole(&cal, mount_offset(), 10.0f, 60.0f);
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_OK, level_mount_cal_solve(&cal, 15.0f, &axis));
    vec3_t truth = quat_rotate_inv(mount_offset(), (vec3_t){0, 0, -1.0f});
    assert_vec3_within(2e-3f, truth, axis);
}

static void test_mount_cal_corrects_elevation(void)
{
    level_mount_cal_t cal;
    roll_in_hole(&cal, mount_offset(), 0.0f, 60.0f);
    TEST_ASSERT_EQUAL(LEVEL_CAL_OK, level_mount_cal_solve(&cal, 15.0f, &lv.cfg.drill_axis));

    for (float roll = -40.0f; roll <= 40.0f; roll += 20.0f) {
        level_reading_t r = level_compute(&lv, board_in_hole(mount_offset(), 20.0f, roll), 0);
        TEST_ASSERT_FLOAT_WITHIN(0.05f, 20.0f, r.elevation_deg);
    }
}

static void test_mount_cal_rejects_too_little_roll(void)
{
    level_mount_cal_t cal;
    roll_in_hole(&cal, mount_offset(), 0.0f, 0.0f);
    for (int i = 0; i < 30; i++) {
        level_mount_cal_add(&cal, accel_at_rest(board_in_hole(mount_offset(), 0.0f, 0.0f)));
    }
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_TOO_LITTLE_ROLL, level_mount_cal_solve(&cal, 15.0f, &axis));
}

static void test_mount_cal_rejects_vertical_hole(void)
{
    level_mount_cal_t cal;
    roll_in_hole(&cal, mount_offset(), -90.0f, 60.0f);
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_TOO_LITTLE_ROLL, level_mount_cal_solve(&cal, 15.0f, &axis));
}

static void test_mount_cal_rejects_too_few_samples(void)
{
    level_mount_cal_t cal;
    level_mount_cal_reset(&cal);
    level_mount_cal_add(&cal, accel_at_rest(board_in_hole(mount_offset(), 0.0f, -45.0f)));
    level_mount_cal_add(&cal, accel_at_rest(board_in_hole(mount_offset(), 0.0f, 45.0f)));
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_TOO_FEW_SAMPLES, level_mount_cal_solve(&cal, 15.0f, &axis));
}

static void test_mount_cal_rejects_moving_bit(void)
{
    // The bit wobbles +-5 degrees in the hole during the roll: not a cone.
    level_mount_cal_t cal;
    level_mount_cal_reset(&cal);
    for (float roll = -60.0f; roll <= 60.0f; roll += 2.0f) {
        level_mount_cal_add(&cal, accel_at_rest(board_in_hole(mount_offset(), 5.0f * sinf(LEVEL_DEG2RAD(roll * 9.0f)), roll)));
    }
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_NOT_ON_CONE, level_mount_cal_solve(&cal, 15.0f, &axis));
}

static void test_mount_cal_rejects_large_offset(void)
{
    level_mount_cal_t cal;
    roll_in_hole(&cal, rot_deg(AXIS_X, 30.0f), 0.0f, 60.0f);
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_OFFSET_TOO_LARGE, level_mount_cal_solve(&cal, 15.0f, &axis));
}

// --- mount calibration collector ---

static level_mount_collector_t mc;

#define START_MS 5000u
#define TICK_MS 20u   // the sensor task's 50 Hz

// Up vectors that tell the samples apart: sample i has x = i.
static vec3_t tagged(uint32_t i)
{
    return (vec3_t){(float)i, 0.0f, 1.0f};
}

// Feeds n samples at 50 Hz from t0; returns how many entered the fit.
static uint32_t feed(uint32_t first_tag, uint32_t n, uint32_t t0)
{
    uint32_t entered = 0;
    for (uint32_t i = 0; i < n; i++) {
        entered += level_mount_collector_add(&mc, tagged(first_tag + i), t0 + i * TICK_MS) ? 1u : 0u;
    }
    return entered;
}

static void test_mount_collector_drops_settle_time(void)
{
    level_mount_collector_reset(&mc, START_MS);
    // Everything in the first second after the start is dropped, before the
    // tail ring sees it.
    uint32_t settle = LEVEL_MOUNT_CAL_SETTLE_MS / TICK_MS;
    TEST_ASSERT_EQUAL_UINT32(0, feed(0, settle, START_MS));
    TEST_ASSERT_EQUAL_UINT32(0, mc.tail_n);
    // A `now` read just before the start was stamped counts as settling too.
    TEST_ASSERT_FALSE(level_mount_collector_add(&mc, tagged(0), START_MS - 1u));
    TEST_ASSERT_EQUAL_UINT32(0, mc.tail_n);
    TEST_ASSERT_FALSE(level_mount_collector_add(&mc, tagged(0), START_MS + LEVEL_MOUNT_CAL_SETTLE_MS));
    TEST_ASSERT_EQUAL_UINT32(1, mc.tail_n);
}

static void test_mount_collector_holds_back_the_tail(void)
{
    level_mount_collector_reset(&mc, START_MS);
    uint32_t t0 = START_MS + LEVEL_MOUNT_CAL_SETTLE_MS;
    // The first LEVEL_MOUNT_CAL_TAIL samples only fill the ring.
    TEST_ASSERT_EQUAL_UINT32(0, feed(0, LEVEL_MOUNT_CAL_TAIL, t0));
    TEST_ASSERT_EQUAL_UINT32(0, mc.fit.n);
    // From then on each new sample pushes the oldest held one into the fit.
    uint32_t more = 40;
    TEST_ASSERT_EQUAL_UINT32(more, feed(LEVEL_MOUNT_CAL_TAIL, more, t0 + LEVEL_MOUNT_CAL_TAIL * TICK_MS));
    TEST_ASSERT_EQUAL_UINT32(more, mc.fit.n);
    // The fit holds samples 0..more-1; the last LEVEL_MOUNT_CAL_TAIL never
    // entered it (sum of x = 0 + 1 + ... + more-1).
    TEST_ASSERT_EQUAL_FLOAT((float)(more * (more - 1u) / 2u), mc.fit.sum.x);
    TEST_ASSERT_EQUAL_UINT32(LEVEL_MOUNT_CAL_TAIL, mc.tail_n);
}

static void test_mount_collector_stops_at_sample_cap(void)
{
    level_mount_collector_reset(&mc, START_MS);
    uint32_t t0 = START_MS + LEVEL_MOUNT_CAL_SETTLE_MS;
    uint32_t total = LEVEL_MOUNT_CAL_TAIL + LEVEL_MOUNT_CAL_MAX_SAMPLES + 50u;
    TEST_ASSERT_EQUAL_UINT32(LEVEL_MOUNT_CAL_MAX_SAMPLES, feed(0, total, t0));
    TEST_ASSERT_EQUAL_UINT32(LEVEL_MOUNT_CAL_MAX_SAMPLES, mc.fit.n);
}

static void test_mount_collector_reset_clears_everything(void)
{
    level_mount_collector_reset(&mc, START_MS);
    feed(0, LEVEL_MOUNT_CAL_TAIL + 10u, START_MS + LEVEL_MOUNT_CAL_SETTLE_MS);
    TEST_ASSERT_EQUAL_UINT32(10, mc.fit.n);

    uint32_t restart = START_MS + 60000u;
    level_mount_collector_reset(&mc, restart);
    TEST_ASSERT_EQUAL_UINT32(0, mc.fit.n);
    TEST_ASSERT_EQUAL_UINT32(0, mc.tail_n);
    // The new start brings a new settle time.
    TEST_ASSERT_FALSE(level_mount_collector_add(&mc, tagged(0), restart + 100u));
    TEST_ASSERT_EQUAL_UINT32(0, mc.tail_n);
}

static void test_mount_collector_feeds_the_solver(void)
{
    // A roll in a hole, with the Start/Finish taps at the edges: the solver
    // still finds the axis, since the edges are dropped.
    level_mount_collector_reset(&mc, START_MS);
    uint32_t t = START_MS;
    for (int i = 0; i < 50; i++, t += TICK_MS) {   // tap and settle: junk
        level_mount_collector_add(&mc, (vec3_t){0.7f, 0.7f, 0.1f}, t);
    }
    for (float roll = -60.0f; roll <= 60.0f; roll += 1.0f, t += TICK_MS) {
        level_mount_collector_add(&mc, accel_at_rest(board_in_hole(mount_offset(), 10.0f, roll)), t);
    }
    for (uint32_t i = 0; i < LEVEL_MOUNT_CAL_TAIL; i++, t += TICK_MS) {   // finish tap: junk
        level_mount_collector_add(&mc, (vec3_t){-0.7f, 0.7f, 0.1f}, t);
    }
    vec3_t axis;
    TEST_ASSERT_EQUAL(LEVEL_CAL_OK, level_mount_cal_solve(&mc.fit, 15.0f, &axis));
    vec3_t truth = quat_rotate_inv(mount_offset(), (vec3_t){0, 0, -1.0f});
    assert_vec3_within(2e-3f, truth, axis);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_wall_pose_reads_zero_elevation);
    RUN_TEST(test_bit_pitched_up_reads_positive_elevation);
    RUN_TEST(test_elevation_ignores_roll_about_bit);
    RUN_TEST(test_yaw_invalid_until_zeroed);
    RUN_TEST(test_turning_left_after_zero_reads_negative_yaw);
    RUN_TEST(test_zero_makes_elevation_relative);
    RUN_TEST(test_zero_of_gives_the_zero_in_effect);
    RUN_TEST(test_yaw_goes_stale);
    RUN_TEST(test_yaw_invalid_when_bit_vertical);
    RUN_TEST(test_floor_pose_is_on_target);
    RUN_TEST(test_floor_bubble_moves_towards_high_side);
    RUN_TEST(test_ceiling_pose_is_on_target);
    RUN_TEST(test_angle_mode_measures_against_target);
    RUN_TEST(test_status_bands);
    RUN_TEST(test_init_starts_in_wall_mode);
    RUN_TEST(test_wall_zero_cleared_when_switching_to_floor);
    RUN_TEST(test_floor_zero_cleared_when_switching_to_wall);
    RUN_TEST(test_floor_zero_cleared_when_switching_to_angle);
    RUN_TEST(test_zero_kept_between_wall_and_angle);
    RUN_TEST(test_zero_kept_from_floor_to_floor);
    RUN_TEST(test_mode_change_without_zero_reports_nothing_cleared);
    RUN_TEST(test_invalid_mode_is_ignored);
    RUN_TEST(test_new_drill_axis_clears_zero);
    RUN_TEST(test_other_config_changes_keep_zero);
    RUN_TEST(test_heading_reset_keeps_gravity_zero);
    RUN_TEST(test_mount_cal_finds_drill_axis);
    RUN_TEST(test_mount_cal_corrects_elevation);
    RUN_TEST(test_mount_cal_rejects_too_little_roll);
    RUN_TEST(test_mount_cal_rejects_vertical_hole);
    RUN_TEST(test_mount_cal_rejects_too_few_samples);
    RUN_TEST(test_mount_cal_rejects_moving_bit);
    RUN_TEST(test_mount_cal_rejects_large_offset);
    RUN_TEST(test_mount_collector_drops_settle_time);
    RUN_TEST(test_mount_collector_holds_back_the_tail);
    RUN_TEST(test_mount_collector_stops_at_sample_cap);
    RUN_TEST(test_mount_collector_reset_clears_everything);
    RUN_TEST(test_mount_collector_feeds_the_solver);
    return UNITY_END();
}
