"""Power FSM timing on the accelerated clock: dim, sleep, wake, off."""

import unittest

from simtest import C, DeviceTest


class PowerEvents:
    def power_events(self, since=0):
        return [(t, d["state"]) for t, d in self.names("power") if t >= since]

    def first(self, state, since=0):
        return next((t for t, s in self.power_events(since) if s == state), None)


class PowerTest(PowerEvents, DeviceTest):
    def setUp(self):
        super().setUp()
        self.t_write = self.dev.now_ms
        self.assertEqual(self.write_config(dim_s=5, sleep_s=10), 0)   # a write is activity

    def test_dim_and_sleep_timing(self):
        self.settle(7000)
        t_dim = self.first("dim")
        self.assertIsNotNone(t_dim)
        # The control task checks every 200 ms.
        self.assertTrue(self.t_write + 5000 <= t_dim <= self.t_write + 5200, t_dim - self.t_write)
        self.assertEqual(self.snap()["brightness"], 20)
        self.assertEqual(self.status().power_state, 1)

        # Picking it up is activity (low-passed gyro above 0.3 rad/s): back to full brightness.
        t_move = self.dev.now_ms
        self.dev.physics.pick_up()
        self.settle(500)
        self.assertEqual(self.first("active", since=t_move) is not None, True)
        self.assertEqual(self.snap()["brightness"], 100)
        self.settle(500)
        self.dev.physics.put_down()
        t_end = self.dev.now_ms + 800
        self.settle(12000)
        t_sleep = self.first("sleep", since=t_move)
        self.assertIsNotNone(t_sleep)
        self.assertTrue(t_end + 9200 <= t_sleep <= t_end + 10200, t_sleep - t_end)
        self.assertEqual(self.dev.state, "sleep")

    def test_trusted_link_with_writes_holds_awake(self):
        self.connect(trusted=True)
        self.assertEqual(self.write_config(tol=0.4), 0)
        t_write = self.dev.now_ms
        self.settle(30000)
        self.assertEqual(self.dev.state, "running")
        self.assertTrue(self.snap()["ble_holds_awake"])
        self.assertEqual(self.status().power_state, 1)            # dimmed, not asleep
        # Ten minutes after the last write the link no longer counts.
        self.settle(t_write + 600_000 - self.dev.now_ms - 500)
        self.assertEqual(self.dev.state, "running")
        self.settle(1000)
        self.assertEqual(self.dev.state, "sleep")
        self.assertIn("sleep", [d["reason"] for _, d in self.names("disconnect_request")])

    def test_untrusted_link_does_not_hold_awake(self):
        self.connect(trusted=False)
        self.dev.subscribe_char("angle", True)                    # not idle either
        self.settle(11000)
        self.assertEqual(self.dev.state, "sleep")

    def test_usb_power_blocks_sleep(self):
        self.dev.set_battery(vbus=True, charging=True)
        self.settle(20000)
        self.assertEqual(self.dev.state, "running")
        self.assertEqual(self.status().power_state, 1)
        t = self.dev.now_ms
        self.dev.set_battery(vbus=False, charging=False)           # unplugging is activity
        self.settle(500)
        self.assertIsNotNone(self.first("active", since=t))
        self.settle(10500)
        self.assertEqual(self.dev.state, "sleep")

    def test_low_battery_powers_off_after_settle(self):
        self.dev.set_battery(percent=4, voltage_mv=3400)
        self.settle(500)
        self.assertEqual(self.dev.state, "running", "battery ignored for 5 s after boot")
        self.settle(4500)
        self.assertEqual(self.dev.state, "off")
        # Charging switches it back on (USB), and keeps it on despite 4 %.
        self.dev.set_battery(vbus=True, charging=True)
        self.settle(7000)
        self.assertEqual(self.dev.state, "running")

    def test_battery_through_the_pmu_decoder(self):
        # pmu_decode_status(): below 2.5 V there is no battery, the gauge is clamped.
        self.dev.set_battery(voltage_mv=2400)
        self.settle(1300)   # polled every 500 ms, on the 200 ms control passes
        self.assertEqual(self.snap()["battery"]["valid"], False)
        self.assertEqual(self.status().battery_pct, 0)
        self.dev.set_battery(voltage_mv=3700, percent=100, charging=True, vbus=True)
        self.settle(300)    # a charger event shows at once
        b = self.snap()["battery"]
        self.assertEqual((b["valid"], b["percent"], b["charging"], b["vbus"]), (True, 100, True, True))
        # The SOC warning IRQ (10 %) reaches the control task's log.
        self.dev.set_battery(percent=9)
        self.settle(300)
        self.assertIn("battery low", [d["msg"] for _, d in self.names("log")])

    def test_off_timer_after_deep_sleep(self):
        self.assertEqual(self.write_config(off_min=1), 0)
        self.settle(10500)
        self.assertEqual(self.dev.state, "sleep")
        t_sleep = self.first("sleep")
        self.settle(t_sleep + 60_000 - self.dev.now_ms - 100)
        self.assertEqual(self.dev.state, "sleep")
        self.settle(300)
        self.assertEqual(self.dev.state, "off")

    def test_abandoned_calibration_is_cancelled(self):
        self.dev.ui_command(C.APP_CMD_MOUNT_CAL_START)
        t = self.dev.now_ms
        self.settle(300_000)
        self.assertEqual(self.dev.state, "running", "a calibration blocks sleep")
        self.assertEqual(self.snap()["cal"]["kind"], 1)
        self.settle(t + 310_000 + 400 - self.dev.now_ms)        # sleep_s + 5 min idle
        self.assertEqual(self.dev.state, "sleep")
        cal = self.names("cal")[-1][1]
        self.assertEqual((cal["kind"], cal["last_kind"], cal["last_result"]), (0, 1, -1))


class VibrationHoldTest(PowerEvents, DeviceTest):
    """A running drill held still counts as activity for 3 min after the last
    real activity (control.c VIB_HOLD_MS, power_fsm.c), never longer."""

    HOLD_MS = 180_000

    def setUp(self):
        super().setUp()
        self.t_write = self.dev.now_ms
        self.assertEqual(self.write_config(dim_s=5, sleep_s=10), 0)   # arms the hold

    def vibrate(self, g=0.3):
        self.dev.physics.vibration_g = g   # what the panel's Drill running toggle sets

    def until(self, t):
        self.settle(t - self.dev.now_ms)

    def boots(self, since=0):
        return [t for t, d in self.names("boot") if t >= since]

    def assert_at(self, t, expected, slack=200, msg=None):
        # The control task checks every 200 ms.
        self.assertIsNotNone(t, msg)
        self.assertTrue(expected - slack <= t <= expected + slack, f"{msg}: {t - expected:+d} ms")

    def test_drill_running_keeps_screen_bright(self):
        self.vibrate()
        self.settle(2100)
        self.assertTrue(self.snap()["vibrating"])
        self.until(self.t_write + 60_000)
        self.assertIsNone(self.first("dim"), "dimmed while drilling")
        self.assertEqual(self.snap()["brightness"], 100)
        self.assertEqual(self.status().power_state, 0)
        # The motor stops: quiet within 4 windows, dim_s after the last credit.
        self.vibrate(0)
        t_off = self.dev.now_ms
        self.settle(2600)
        self.assertFalse(self.snap()["vibrating"])
        self.until(t_off + 8000)
        t_dim = self.first("dim")
        self.assertIsNotNone(t_dim)
        self.assertTrue(t_off + 5000 <= t_dim <= t_off + 2600 + 5200, t_dim - t_off)
        self.assertIn("vibration quiet, duty 0 %", [d["msg"] for _, d in self.names("log")])

    def test_drill_running_still_sleeps_after_cap(self):
        self.vibrate()
        self.until(self.t_write + self.HOLD_MS + 11_000)
        self.assert_at(self.first("dim"), self.t_write + self.HOLD_MS + 5000, msg="dim")
        t_sleep = self.first("sleep")
        self.assert_at(t_sleep, self.t_write + self.HOLD_MS + 10_000, msg="sleep")
        # The sim's wake on motion (0.3 g > 150 mg) reboots it. A motion-wake
        # boot is not activity, so in continuous vibration each cycle is as
        # short as without the feature.
        for _ in range(2):
            self.settle(1500)
            self.assertEqual([d["reason"] for _, d in self.names("boot")][-1], "motion")
            t_boot = self.boots()[-1]
            self.until(t_boot + 10_500)
            self.assert_at(self.first("dim", since=t_boot), t_boot + 5000, msg="dim after wake")
            self.assert_at(self.first("sleep", since=t_boot), t_boot + 10_000, msg="sleep after wake")

    def test_strong_vibration_gyro_noise_does_not_keep_awake(self):
        # At 1 g the sim's gyro vibration noise passes 0.3 rad/s in about a
        # quarter of the batches: the turn detector's low-pass averages it out.
        self.vibrate(1.0)
        self.until(self.t_write + self.HOLD_MS + 10_500)
        self.assert_at(self.first("dim"), self.t_write + self.HOLD_MS + 5000, msg="dim")
        self.assert_at(self.first("sleep"), self.t_write + self.HOLD_MS + 10_000, msg="sleep")

    def test_strong_vibration_on_and_off_does_not_keep_awake(self):
        # Each motor start and stop switches the gyro noise on or off: no turn.
        while self.dev.now_ms < self.t_write + self.HOLD_MS + 10_500:
            self.vibrate(1.0)
            self.settle(20_000)
            self.vibrate(0)
            self.settle(3000)
        self.assert_at(self.first("dim"), self.t_write + self.HOLD_MS + 5000, msg="dim")
        self.assert_at(self.first("sleep"), self.t_write + self.HOLD_MS + 10_000, msg="sleep")

    def test_pick_up_while_quiet_rearms(self):
        self.vibrate()
        self.until(self.t_write + 100_000)
        self.vibrate(0)                      # the motor stops between holes
        self.settle(3000)
        self.assertFalse(self.snap()["vibrating"])
        t_pick = self.dev.now_ms
        self.dev.physics.pick_up()           # moving to the next hole
        self.settle(1500)
        self.vibrate()
        self.until(t_pick + self.HOLD_MS + 6000)
        t_dim = self.first("dim")
        self.assertIsNotNone(t_dim)
        # The stamp is the last batch that read turning during the 0.8 s pick-up.
        self.assertTrue(t_pick + self.HOLD_MS + 4800 <= t_dim <= t_pick + self.HOLD_MS + 6000,
                        t_dim - t_pick)

    def test_pick_up_right_after_motor_stop_rearms(self):
        # Straight to the next hole: the vibration detector still reads
        # running for 2 s after the motor stops, but the move counts at once.
        self.vibrate()
        self.until(self.t_write + 100_000)
        self.vibrate(0)
        self.settle(300)
        t_pick = self.dev.now_ms
        self.dev.physics.pick_up()
        self.settle(200)
        self.assertTrue(self.snap()["vibrating"])
        self.settle(1300)
        self.vibrate()
        self.until(t_pick + self.HOLD_MS + 7000)
        t_dim = self.first("dim")
        self.assertIsNotNone(t_dim)
        self.assertTrue(t_pick + self.HOLD_MS + 4800 <= t_dim <= t_pick + self.HOLD_MS + 6000,
                        t_dim - t_pick)

    def test_turning_while_vibrating_rearms(self):
        # Rolling the drill while the motor runs strongly is handling: it
        # counts whatever the accelerometer reads.
        self.vibrate(1.0)
        self.until(self.t_write + 100_000)
        t_end = self.dev.now_ms + 12_000
        self.dev.physics.roll_back_and_forth()   # 3 x 4 s
        self.until(t_end + self.HOLD_MS + 6000)
        self.assert_at(self.first("dim"), t_end + self.HOLD_MS + 5000, msg="dim")

    def test_motor_stuttering_does_not_rearm(self):
        # 60 ms on, 20 ms off at 1 g, the drill held still: every batch
        # switches the gyro noise on or off, and none of it is a turn.
        self.vibrate()
        self.until(self.t_write + 100_000)
        while self.dev.now_ms < self.t_write + self.HOLD_MS + 6000:
            self.vibrate(1.0)
            self.settle(60)
            self.vibrate(0)
            self.settle(20)
        self.assert_at(self.first("dim"), self.t_write + self.HOLD_MS + 5000, msg="dim")

    def test_touch_rearms_while_vibrating(self):
        self.vibrate()
        self.until(self.t_write + 120_000)
        t_tap = self.dev.now_ms
        self.dev.touch()
        self.until(t_tap + self.HOLD_MS + 6000)
        self.assert_at(self.first("dim"), t_tap + self.HOLD_MS + 5000, msg="dim")

    def sleep_and_wake_by_pick_up(self):
        self.until(self.t_write + 10_500)
        self.assertEqual(self.dev.state, "sleep")
        t = self.dev.now_ms
        self.dev.physics.pick_up()           # wakes it (0.3 g > 150 mg)
        while not self.boots(since=t):
            self.settle(20)
        return self.boots(since=t)[0]

    def test_pick_up_right_after_motion_wake_arms(self):
        t_boot = self.sleep_and_wake_by_pick_up()
        self.assertEqual([d["reason"] for _, d in self.names("boot")][-1], "motion")
        # A short twist before the first 0.5 s window closes: counted when
        # that window closes calm, then the drill runs.
        self.settle(100)
        self.dev.physics.pick_up(duration_s=0.25, height_m=0.0, wobble_deg=4.0)
        self.until(t_boot + 1000)
        self.vibrate()
        self.until(t_boot + 30_000)
        self.assertIsNone(self.first("dim", since=t_boot), "dimmed while drilling")
        self.assertTrue(self.snap()["vibrating"])

    def test_pick_up_while_drilling_after_motion_wake_arms(self):
        # Woken straight into drilling: the motor already runs when the drill
        # is moved, and the move still counts.
        t_boot = self.sleep_and_wake_by_pick_up()
        self.vibrate(1.0)
        self.settle(200)
        self.dev.physics.pick_up()
        self.until(t_boot + 30_000)
        self.assertIsNone(self.first("dim", since=t_boot), "dimmed while drilling")
        self.assertTrue(self.snap()["vibrating"])

    def test_imu_restart_resets_detector(self):
        self.vibrate()
        self.settle(2100)
        self.assertTrue(self.snap()["vibrating"])
        self.dev.imu_present = False             # lost after 200 ms, retried every 2 s
        self.settle(300)
        self.assertFalse(self.snap()["vibrating"])
        self.vibrate(0)
        self.dev.imu_present = True
        while not self.snap()["imu_ok"]:
            self.settle(20)
        # Undecided again, not the running verdict from before the gap.
        for _ in range(150):
            self.assertFalse(self.snap()["vibrating"])
            self.settle(20)


if __name__ == "__main__":
    unittest.main()
