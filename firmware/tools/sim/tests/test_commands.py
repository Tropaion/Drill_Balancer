"""Command characteristic: every DB_CMD_* with its validation and effect."""

import unittest

from simtest import C, DeviceTest, dc

F = dc.ANGLE_FLAGS
S = dc.STATUS_FLAGS


class CommandTest(DeviceTest):
    def test_validation(self):
        w = lambda data: self.dev.write_char("command", data)
        self.assertEqual(w(b""), 0x0D)
        self.assertEqual(w(b"\x99"), 0x84)
        self.assertEqual(w(b"\x01\x00"), 0x0D)          # ZERO takes no argument
        self.assertEqual(w(b"\x03"), 0x0D)              # SET_MODE needs one
        self.assertEqual(w(b"\x03\x03"), 0x85)          # mode out of range
        self.assertEqual(w(b"\x03\x01\x00"), 0x0D)
        self.assertEqual(w(b"\x23"), 0x0D)              # FACTORY_RESET needs 0xA5
        self.assertEqual(w(b"\x23\x00"), 0x85)
        self.assertEqual(w(b"\x10" * 513), 0x0D)
        # BUSY: injected, then a really full queue.
        self.dev.busy_next_commands(1)
        self.assertEqual(w(b"\x01"), 0x86)
        self.assertEqual(w(b"\x01"), 0)
        self.dev.advance(20)
        for _ in range(16):
            self.assertTrue(self.dev.core.post_cmd(C.APP_CMD_CLEAR_ZERO))
        self.assertEqual(w(b"\x01"), 0x86)
        self.dev.advance(250)   # the next control pass empties the queue
        self.assertEqual(w(b"\x01"), 0)

    def test_zero_mode_and_cal_commands(self):
        self.settle()
        self.assertEqual(self.cmd("zero"), 0)
        self.assertTrue(self.angle().flags & F["zero_set"])
        self.assertEqual(self.cmd("clear-zero"), 0)
        self.assertFalse(self.angle().flags & F["zero_set"])

        self.assertEqual(self.cmd("set-mode", "angle"), 0)
        self.assertEqual(self.angle().mode, 2)
        self.assertEqual(self.config()["mode"], "angle")

        self.assertEqual(self.cmd("mount-cal-start"), 0)
        st = self.status()
        self.assertEqual(st.cal_kind, 1)
        self.assertTrue(st.flags & S["calibrating"])
        self.assertEqual(self.cmd("cal-cancel"), 0)
        st = self.status()
        self.assertEqual((st.cal_kind, st.cal_last_result), (0, -1))
        self.assertFalse(st.flags & S["calibrating"])

        self.assertEqual(self.cmd("mount-cal-start", settle_ms=500), 0)
        self.assertEqual(self.cmd("mount-cal-finish"), 0)
        self.assertEqual(self.status().cal_last_result, 1)    # LEVEL_CAL_TOO_FEW_SAMPLES

        self.assertEqual(self.cmd("accel-cal-start", settle_ms=1500), 0)
        st = self.status()
        # The drill lies horizontal: board +Y is up.
        self.assertEqual((st.cal_kind, st.cal_samples, st.cal_coverage), (2, 1, 0x04))
        self.assertEqual(self.cmd("accel-cal-finish"), 0)
        st = self.status()
        self.assertEqual((st.cal_kind, st.cal_last_result), (0, 1))  # ACCEL_CAL_POOR_COVERAGE

        # A finish without a start does nothing.
        self.assertEqual(self.cmd("mount-cal-finish"), 0)
        self.assertEqual(self.status().cal_last_result, 1)

    def test_ble_calibration_cancelled_on_disconnect(self):
        self.connect()
        self.assertEqual(self.cmd("mount-cal-start"), 0)
        self.dev.disconnect()
        self.dev.advance(20)
        st = self.status()
        self.assertEqual((st.cal_kind, st.cal_last_result), (0, -1))
        # One started on the touch screen survives a disconnect.
        self.connect()
        self.dev.ui_command(C.APP_CMD_MOUNT_CAL_START, 0)
        self.dev.advance(20)
        self.dev.disconnect()
        self.dev.advance(20)
        self.assertEqual(self.status().cal_kind, 1)

    def test_sleep_and_wake_on_motion(self):
        self.connect()
        self.assertEqual(self.cmd("sleep"), 0)
        self.assertEqual(self.dev.state, "sleep")
        self.assertFalse(self.dev.advertising)
        self.assertIn("sleep", [d["reason"] for _, d in self.names("disconnect_request")])
        self.settle(5000)
        self.assertEqual(self.dev.state, "sleep", "lying still does not wake it")
        self.dev.physics.pick_up()
        self.settle(3000)
        self.assertEqual(self.dev.state, "running")
        self.assertEqual(self.names("boot")[-1][1]["reason"], "motion")
        self.assertTrue(self.dev.advertising)

    def test_power_off_and_on(self):
        self.assertEqual(self.cmd("power-off"), 0)
        self.assertEqual(self.dev.state, "off")
        self.dev.physics.pick_up()
        self.settle(3000)
        self.assertEqual(self.dev.state, "off", "motion does not switch it on")
        self.dev.pwrkey()
        self.settle(500)
        self.assertEqual(self.dev.state, "running")

    def test_reboot(self):
        self.connect()
        self.assertEqual(self.write_config(tol=0.3), 0)
        self.assertEqual(self.cmd("reboot"), 0)
        self.assertEqual(self.dev.state, "booting")
        self.assertIn("reboot", [d["reason"] for _, d in self.names("disconnect_request")])
        self.settle(1500)
        self.assertEqual(self.dev.state, "running")
        self.assertEqual(self.config()["tol"], 0.3)

    def test_factory_reset(self):
        self.connect()
        self.settle()
        self.assertEqual(self.write_config(tol=0.3, mode="floor"), 0)
        self.assertEqual(self.cmd("zero"), 0)
        self.assertEqual(self.cmd("mount-cal-start"), 0)
        self.assertEqual(self.cmd("factory-reset"), 0)
        self.assertTrue(self.names("clear_bonds"))
        self.assertIn("factory_reset", [d["reason"] for _, d in self.names("disconnect_request")])
        cfg = self.config()
        self.assertEqual((cfg["tol"], cfg["mode"]), (0.5, "wall"))
        a = self.angle()
        self.assertFalse(a.flags & F["zero_set"])
        self.assertEqual(self.status().cal_kind, 0)

    def test_pwr_key(self):
        self.settle(2500)   # past PWRKEY_BOOT_IGNORE_MS
        self.dev.pwrkey(long_press=False)
        self.settle(300)
        self.assertEqual(self.dev.state, "sleep")

    def test_pwr_key_ignored_right_after_boot(self):
        self.dev.pwrkey(long_press=True)   # 0.5 s after power-on
        self.settle(300)
        self.assertEqual(self.dev.state, "running")


if __name__ == "__main__":
    unittest.main()
