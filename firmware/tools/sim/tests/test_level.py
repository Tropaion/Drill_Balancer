"""Level readings, flags and freshness through the firmware core and the physics model."""

import unittest

from simtest import DeviceTest, dc

F = dc.ANGLE_FLAGS


class LevelTest(DeviceTest):
    def pose(self, ms=2500, **kw):
        self.dev.physics.set_drill(**kw)
        self.settle(ms)
        return self.angle()

    def test_elevation_at_known_orientations(self):
        for elev in (0.0, 45.0, -45.0, 90.0, -30.0):
            a = self.pose(elevation=elev, roll=0.0)
            self.assertClose(a.elevation, elev, 0.1, f"elevation {elev}: {a}")
            self.assertTrue(a.flags & F["imu_ok"])
            # Roll about the bit does not change the elevation.
            if abs(elev) < 80:
                a = self.pose(elevation=elev, roll=35.0)
                self.assertClose(a.elevation, elev, 0.1, f"elevation {elev} rolled: {a}")
        self.assertEqual(a.version, 1)
        self.assertEqual(a.mode, 0)

    def test_status_flags_follow_tolerance_settings(self):
        cases = [(0.3, "on_target"), (-1.5, "near"), (3.0, None)]
        for elev, flag in cases:
            a = self.pose(elevation=elev)
            self.assertEqual(bool(a.flags & F["on_target"]), flag == "on_target", a)
            self.assertEqual(bool(a.flags & F["near"]), flag == "near", a)
            self.assertClose(a.deviation, elev, 0.1)
        self.assertTrue(self.pose(elevation=0.0).flags & F["still"])
        # Tighter tolerance from the config characteristic.
        self.assertEqual(self.write_config(tol=0.2, near=1.0), 0)
        self.assertTrue(self.pose(elevation=0.3).flags & F["near"])
        self.assertFalse(self.pose(elevation=1.5).flags & (F["near"] | F["on_target"]))

    def test_angle_mode_judges_against_target(self):
        self.assertEqual(self.write_config(target=30.0), 0)
        self.assertEqual(self.cmd("set-mode", "angle"), 0)
        a = self.pose(elevation=29.8)
        self.assertEqual(a.mode, 2)
        self.assertClose(a.deviation, -0.2, 0.1)
        self.assertTrue(a.flags & F["on_target"])

    def test_yaw_after_zero(self):
        self.dev.physics.gyro_bias = [0.004, -0.003, 0.002]
        self.pose(ms=5000, elevation=5.0, yaw=0.0)   # fusion learns the gyro bias
        a = self.angle()
        self.assertFalse(a.flags & (F["yaw_valid"] | F["zero_set"]))
        self.assertEqual(self.write_config(stale_s=10), 0)
        self.assertEqual(self.cmd("zero"), 0)
        a = self.angle()
        self.assertTrue(a.flags & F["zero_set"] and a.flags & F["yaw_valid"], a)
        self.assertClose(a.elevation, 0.0, 0.05)
        self.assertClose(a.yaw, 0.0, 0.05)
        for yaw in (10.0, -25.0):
            a = self.pose(elevation=8.0, yaw=yaw)
            self.assertClose(a.yaw, yaw, 0.3, f"yaw {yaw}: {a}")
            self.assertClose(a.elevation, 3.0, 0.1)   # relative to the zero
            self.assertTrue(a.flags & F["yaw_valid"])
        self.assertFalse(a.flags & F["yaw_stale"])
        self.settle(6000)                              # > stale_s after the zero
        self.assertTrue(self.angle().flags & F["yaw_stale"])
        # Bit near vertical: no yaw, the zero stays set.
        a = self.pose(elevation=75.0)
        self.assertFalse(a.flags & F["yaw_valid"])
        self.assertTrue(a.flags & F["zero_set"])
        self.assertEqual(self.cmd("clear-zero"), 0)
        self.assertFalse(self.angle().flags & F["zero_set"])

    def test_floor_bubble(self):
        self.assertEqual(self.cmd("set-mode", "floor"), 0)
        a = self.pose(elevation=-90.0)
        self.assertEqual(a.mode, 1)
        self.assertClose(a.deviation, 0.0, 0.1)
        self.assertTrue(a.flags & F["on_target"])
        for tilt, flag in ((1.5, "near"), (4.0, None)):
            a = self.pose(elevation=-90.0 + tilt)
            self.assertClose(a.deviation, tilt, 0.1, a)
            self.assertClose((a.bubble_x ** 2 + a.bubble_y ** 2) ** 0.5, tilt, 0.15)
            self.assertEqual(bool(a.flags & F["near"]), flag == "near")
        # A ceiling hole (bit up) reads the same way.
        self.assertClose(self.pose(elevation=88.0).deviation, 2.0, 0.1)

    def test_imu_lost_and_back(self):
        self.pose(elevation=10.0)
        self.assertEqual(self.cmd("zero"), 0)
        self.dev.imu_present = False
        self.settle(300)
        a = self.angle()
        self.assertFalse(a.flags & F["imu_ok"])
        self.assertEqual(a.flags, F["zero_set"], "without IMU_OK only ZERO_SET may be set")
        self.assertFalse(self.snap()["imu_ok"])
        self.dev.imu_present = True
        self.settle(2500)   # IMU_RETRY_MS
        a = self.angle()
        self.assertTrue(a.flags & F["imu_ok"])
        self.assertTrue(a.flags & F["zero_set"])
        # Fusion restarted at yaw 0: the zero's heading is gone until the next zero.
        self.assertFalse(a.flags & F["yaw_valid"])

    def test_stale_reading_drops_flags(self):
        self.pose(elevation=0.0)
        snap = self.snap()
        core = self.dev.core
        fresh = dc.decode_angle(core.angle_packet(0, snap["reading_ms"] + 500))
        stale = dc.decode_angle(core.angle_packet(0, snap["reading_ms"] + 501))
        self.assertTrue(fresh.flags & F["imu_ok"] and fresh.flags & F["on_target"])
        self.assertEqual(stale.flags, 0)


if __name__ == "__main__":
    unittest.main()
