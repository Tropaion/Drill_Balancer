"""Power FSM timing on the accelerated clock: dim, sleep, wake, off."""

import unittest

from simtest import C, DeviceTest


class PowerTest(DeviceTest):
    def setUp(self):
        super().setUp()
        self.t_write = self.dev.now_ms
        self.assertEqual(self.write_config(dim_s=5, sleep_s=10), 0)   # a write is activity

    def power_events(self, since=0):
        return [(t, d["state"]) for t, d in self.names("power") if t >= since]

    def first(self, state, since=0):
        return next((t for t, s in self.power_events(since) if s == state), None)

    def test_dim_and_sleep_timing(self):
        self.settle(7000)
        t_dim = self.first("dim")
        self.assertIsNotNone(t_dim)
        # The control task checks every 200 ms.
        self.assertTrue(self.t_write + 5000 <= t_dim <= self.t_write + 5200, t_dim - self.t_write)
        self.assertEqual(self.snap()["brightness"], 20)
        self.assertEqual(self.status().power_state, 1)

        # Picking it up is activity (gyro above 0.3 rad/s): back to full brightness.
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


if __name__ == "__main__":
    unittest.main()
