"""Mount and sensor calibration end to end: the firmware's collectors and
solvers must recover the errors injected into the physics model."""

import math
import unittest

from simtest import DeviceTest, dc

S = dc.STATUS_FLAGS
POSES = ("+Z", "-Z", "+Y", "-Y", "-X", "+X")   # ui_poses order
BIAS = [0.04, -0.03, 0.05]
SCALE = [1.03, 0.97, 1.02]
MOUNT = (-3.0, 2.5, 1.0)   # adds to the Z bias's elevation error


def angle_between(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / (na * nb)))))


class CalibrationTest(DeviceTest):
    def setUp(self):
        super().setUp()
        self.dev.physics.gyro_bias = [0.003, -0.002, 0.001]

    def mount_cal(self, amplitude=50.0, during=None):
        p = self.dev.physics
        p.set_drill(elevation=0.0, yaw=0.0, roll=0.0)
        self.settle(3000)
        self.assertEqual(self.cmd("mount-cal-start"), 0)
        self.settle(1500)                      # the first second is dropped anyway
        p.roll_back_and_forth(amplitude, period_s=4.0, cycles=3)
        if during:
            self.settle(6000)
            during()
            self.settle(6000)
        else:
            self.settle(12000)
        self.settle(1000)                      # held still before the finish tap
        self.assertEqual(self.status().cal_samples, 255)   # saturates in the packet
        self.assertGreater(self.snap()["cal"]["samples"], 500)
        self.assertEqual(self.cmd("mount-cal-finish"), 0)
        return self.status()

    def accel_cal(self, faces=POSES):
        p = self.dev.physics
        self.assertEqual(self.cmd("accel-cal-start"), 0)
        for i, face in enumerate(faces):
            # A table that is not quite level, different for every pose.
            p.set_board_face(face, tilt_deg=(2.0 * math.sin(i), 2.0 * math.cos(i)))
            self.settle(2500)
        st = self.status()
        self.assertEqual(self.cmd("accel-cal-finish"), 0)
        return st, self.status()

    def elevation_error(self, elevation, roll=0.0):
        self.dev.physics.set_drill(elevation=elevation, yaw=0.0, roll=roll)
        self.settle(2500)
        return abs(self.angle().elevation - elevation)

    def test_full_workflow_recovers_injected_errors(self):
        p = self.dev.physics
        p.accel_bias, p.accel_scale, p.mount_offset = list(BIAS), list(SCALE), MOUNT
        self.assertGreater(self.elevation_error(0.0), 1.0, "the errors must be visible first")

        during, after = self.accel_cal()
        self.assertEqual((during.cal_kind, during.cal_samples, during.cal_coverage), (2, 6, 0x3F))
        self.assertEqual(after.cal_last_result, 0)      # no mount cal to reset
        self.assertTrue(after.flags & S["accel_cal"])
        snap = self.snap()
        for i in range(3):
            self.assertClose(snap["accel_bias"][i], BIAS[i], 0.003, f"bias {i}")
            self.assertClose(snap["accel_scale"][i], SCALE[i], 0.003, f"scale {i}")

        st = self.mount_cal()
        self.assertEqual(st.cal_last_result, 0)
        self.assertTrue(st.flags & S["mount_cal"] and st.flags & S["accel_cal"])
        axis = self.snap()["drill_axis"]
        self.assertLess(angle_between(axis, p.expected_drill_axis()), 0.2, axis)

        for elev, roll in ((0.0, 0.0), (20.0, 40.0), (-35.0, -60.0), (60.0, 120.0)):
            self.assertLess(self.elevation_error(elev, roll), 0.15, f"elevation {elev} roll {roll}")
        # Stored: a new boot uses both calibrations.
        self.dev = self.new_device(physics=p)
        self.settle_imu()
        self.assertEqual(self.config()["cal"], {"mount": True, "accel": True})
        self.assertLess(self.elevation_error(10.0, 30.0), 0.15)

    def test_mount_cal_result_codes(self):
        p = self.dev.physics
        self.assertEqual(self.mount_cal(amplitude=20.0).cal_last_result, 2)   # TOO_LITTLE_ROLL
        self.assertEqual(
            self.mount_cal(during=lambda: p.set_drill(elevation=10.0)).cal_last_result,
            3)                                                                 # NOT_ON_CONE
        p.mount_offset = (20.0, 0.0, 0.0)
        self.assertEqual(self.mount_cal().cal_last_result, 4)                  # OFFSET_TOO_LARGE
        self.assertFalse(self.status().flags & S["mount_cal"])
        p.mount_offset = MOUNT
        self.dev.store_fail = True
        st = self.mount_cal()
        self.assertEqual(st.cal_last_result, -2)                               # solved, not stored
        self.assertFalse(st.flags & S["mount_cal"])
        self.dev.store_fail = False
        self.assertEqual(self.mount_cal().cal_last_result, 0)

    def test_sensor_cal_resets_mount_cal(self):
        self.dev.physics.mount_offset = MOUNT
        self.assertEqual(self.mount_cal().cal_last_result, 0)
        self.assertNotEqual(self.snap()["drill_axis"], [0, 0, -1])
        _, st = self.accel_cal()
        self.assertEqual(st.cal_last_result, -3)          # OK, mount cal reset
        self.assertTrue(st.flags & S["accel_cal"])
        self.assertFalse(st.flags & S["mount_cal"])
        self.assertEqual(self.snap()["drill_axis"], [0, 0, -1])

    def test_sensor_cal_poor_coverage(self):
        during, after = self.accel_cal(faces=("+Z", "-Z", "+Y", "-Y"))
        self.assertEqual(during.cal_coverage, 0x3C)
        self.assertEqual(after.cal_last_result, 1)        # ACCEL_CAL_POOR_COVERAGE
        self.assertFalse(after.flags & S["accel_cal"])

    def test_sensor_cal_out_of_range(self):
        self.dev.physics.accel_scale = [1.15, 1.0, 1.0]   # beyond ACCEL_CAL_MAX_SCALE
        _, after = self.accel_cal()
        self.assertEqual(after.cal_last_result, 3)        # ACCEL_CAL_OUT_OF_RANGE


if __name__ == "__main__":
    unittest.main()
