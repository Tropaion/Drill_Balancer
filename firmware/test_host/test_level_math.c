#include <math.h>
#include "unity.h"
#include "level_math.h"
#include "test_util.h"

void setUp(void) {}
void tearDown(void) {}

static void test_cross_follows_right_hand_rule(void)
{
    assert_vec3_within(1e-6f, AXIS_Z, vec3_cross(AXIS_X, AXIS_Y));
}

static void test_normalize_zero_vector_stays_zero(void)
{
    assert_vec3_within(0.0f, (vec3_t){0}, vec3_normalize((vec3_t){0}));
}

static void test_rotation_about_x_takes_y_to_z(void)
{
    assert_vec3_within(1e-6f, AXIS_Z, quat_rotate(rot_deg(AXIS_X, 90.0f), AXIS_Y));
}

static void test_rotate_inv_undoes_rotate(void)
{
    quat_t q = quat_mul(rot_deg(AXIS_Z, 33.0f), rot_deg(AXIS_X, -71.0f));
    vec3_t v = {0.3f, -0.5f, 0.8f};
    assert_vec3_within(1e-5f, v, quat_rotate_inv(q, quat_rotate(q, v)));
}

static void test_quat_mul_applies_right_operand_first(void)
{
    // Rz(90) * Rx(90): Y -> Z (Rx), Z stays Z (Rz).
    quat_t q = quat_mul(rot_deg(AXIS_Z, 90.0f), rot_deg(AXIS_X, 90.0f));
    assert_vec3_within(1e-6f, AXIS_Z, quat_rotate(q, AXIS_Y));
    // X stays X (Rx), X -> Y (Rz).
    assert_vec3_within(1e-6f, AXIS_Y, quat_rotate(q, AXIS_X));
}

static void test_wrap_deg_range(void)
{
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, -170.0f, wrap_deg(190.0f));
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 170.0f, wrap_deg(-190.0f));
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 180.0f, wrap_deg(-180.0f));
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 5.0f, wrap_deg(725.0f));
}

static void test_vec3_equal_is_exact(void)
{
    TEST_ASSERT_TRUE(vec3_equal(AXIS_X, AXIS_X));
    TEST_ASSERT_TRUE(vec3_equal((vec3_t){0.0f, 0.0f, 0.0f}, (vec3_t){-0.0f, 0.0f, -0.0f}));
    TEST_ASSERT_FALSE(vec3_equal(AXIS_X, AXIS_Y));
    TEST_ASSERT_FALSE(vec3_equal((vec3_t){1.0f, 0.0f, 0.0f}, (vec3_t){1.0f, 0.0f, 1e-7f}));
    TEST_ASSERT_FALSE(vec3_equal((vec3_t){NAN, 0.0f, 0.0f}, (vec3_t){NAN, 0.0f, 0.0f}));
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_cross_follows_right_hand_rule);
    RUN_TEST(test_normalize_zero_vector_stays_zero);
    RUN_TEST(test_rotation_about_x_takes_y_to_z);
    RUN_TEST(test_rotate_inv_undoes_rotate);
    RUN_TEST(test_quat_mul_applies_right_operand_first);
    RUN_TEST(test_wrap_deg_range);
    RUN_TEST(test_vec3_equal_is_exact);
    return UNITY_END();
}
