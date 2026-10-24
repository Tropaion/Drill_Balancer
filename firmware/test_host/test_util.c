#include "test_util.h"
#include "unity.h"

quat_t rot_deg(vec3_t axis, float deg)
{
    return quat_from_axis_angle(axis, LEVEL_DEG2RAD(deg));
}

quat_t pose_wall(void)
{
    return rot_deg(AXIS_X, 90.0f);
}

vec3_t accel_at_rest(quat_t q)
{
    return quat_rotate_inv(q, AXIS_Z);
}

void assert_vec3_within(float delta, vec3_t expected, vec3_t actual)
{
    TEST_ASSERT_FLOAT_WITHIN(delta, expected.x, actual.x);
    TEST_ASSERT_FLOAT_WITHIN(delta, expected.y, actual.y);
    TEST_ASSERT_FLOAT_WITHIN(delta, expected.z, actual.z);
}
