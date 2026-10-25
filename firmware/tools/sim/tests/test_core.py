"""The ctypes core on its own: build, state file, raw sensor passes, texts."""

import json
import math
import tempfile
import unittest
from pathlib import Path

from simtest import C, dc

F = dc.ANGLE_FLAGS
DT = 1 / 448.4


class CoreTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.core = C.Core()
        self.now = 300

    def tearDown(self):
        self._tmp.cleanup()

    def boot(self):
        self.core.init(self.dir / "state.json", self.dir / "ota.bin", "1.0.0", self.now)
        self.core.take_events()
        self.core.log_lines()

    def run_flat(self, ms, accel=(0.0, 0.0, 1.0)):
        """Board flat, screen up: 9 samples per 20 ms pass."""
        for _ in range(ms // 20):
            self.now += 20
            self.core.sensor_pass(self.now, [accel + (0.0, 0.0, 0.0)] * 9, DT)
            if self.now % 200 == 0:
                self.core.control_step(self.now)

    def test_library_is_current(self):
        self.assertFalse(C.lib_is_stale())

    def test_raw_sensor_passes(self):
        self.boot()
        self.run_flat(1000)
        a = dc.decode_angle(self.core.angle_packet(0, self.now))
        # Screen up: the bit (-Z) points straight down.
        self.assertAlmostEqual(a.elevation, -90.0, delta=0.05)
        self.assertTrue(a.flags & F["imu_ok"] and a.flags & F["still"])
        snap = self.core.snapshot()
        self.assertEqual(snap["fw_version"], "1.0.0")
        self.assertEqual(snap["ui"]["mode_title"], self.core.ui_text(0, 0))
        # Empty reads: lost after 200 ms, the IMU_OK flag goes.
        for _ in range(11):
            self.now += 20
            self.core.sensor_pass(self.now, [], DT, imu_present=False)
        self.assertFalse(dc.decode_angle(self.core.angle_packet(0, self.now)).flags & F["imu_ok"])
        self.assertIn(("E", "IMU lost: no new samples"), self.core.log_lines())

    def test_state_file_round_trip_and_bad_files(self):
        self.boot()
        self.assertFalse((self.dir / "state.json").exists(), "nothing stored before a change")
        self.assertEqual(self.core.config_write(b'{"tol":0.25,"mode":"angle"}', self.now), 0)
        state = json.loads((self.dir / "state.json").read_text())
        self.assertEqual(state["settings"]["tol"], 0.25)
        self.assertEqual(state["drill_axis"], [0, 0, -1])
        # Calibration values are kept exactly (floats round-trip).
        state["mount_cal_valid"] = True
        state["drill_axis"] = [0.0348995, -0.0523360, -0.9980198]
        (self.dir / "state.json").write_text(json.dumps(state))
        self.boot()
        snap = self.core.snapshot()
        self.assertTrue(snap["mount_cal_valid"])
        self.assertAlmostEqual(snap["drill_axis"][1], -0.052336, places=6)
        self.assertEqual(json.loads(self.core.config_read())["tol"], 0.25)
        # Out of range (axis 20 degrees off -Z) or garbage: defaults, like load().
        state["drill_axis"] = [0.342, 0.0, -0.9397]
        (self.dir / "state.json").write_text(json.dumps(state))
        self.boot()
        self.assertEqual(json.loads(self.core.config_read())["tol"], 0.5)
        (self.dir / "state.json").write_text("{not json")
        self.boot()
        self.assertEqual(json.loads(self.core.config_read())["mode"], "wall")

    def test_vibration_flag(self):
        # vibration.c through the sensor-task mirror: a 0.2 g, 100 Hz vector
        # turning in the board X/Y plane is RUNNING after three 0.5 s windows.
        self.boot()
        self.run_flat(2500)
        self.assertIs(self.core.snapshot()["vibrating"], False)
        k = 0

        def run_tone(ms):
            nonlocal k
            for _ in range(ms // 20):
                self.now += 20
                batch = []
                for _ in range(9):
                    w = 2 * math.pi * 100 * k * DT
                    batch.append((0.2 * math.cos(w), 0.2 * math.sin(w), 1.0, 0.0, 0.0, 0.0))
                    k += 1
                self.core.sensor_pass(self.now, batch, DT)

        run_tone(1000)
        self.assertIs(self.core.snapshot()["vibrating"], False)
        run_tone(1000)
        self.assertIs(self.core.snapshot()["vibrating"], True)
        self.assertIn(("I", "vibration running, duty 100 %"), self.core.log_lines())

    def test_result_texts(self):
        self.assertTrue(self.core.ui_text(1, 0))
        self.assertNotEqual(self.core.ui_text(2, -3), self.core.ui_text(2, 0))


if __name__ == "__main__":
    unittest.main()
