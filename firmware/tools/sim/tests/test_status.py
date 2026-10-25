"""Status packet, battery level and the notify timer (10 Hz angle, status on
change / 1 Hz), plus the link policy and device identity."""

import unittest

from simtest import DeviceTest, dc

S = dc.STATUS_FLAGS


class StatusTest(DeviceTest):
    def notifies(self, char, since=0):
        return [(t, d["value"]) for t, d in self.names("notify") if d["char"] == char and t >= since]

    def test_status_packet_contents(self):
        self.dev.set_battery(percent=87, voltage_mv=4012, charging=True, vbus=True)
        self.settle(600)
        st = self.status()
        self.assertEqual((st.version, st.battery_pct, st.battery_mv), (1, 87, 4012))
        self.assertEqual(st.flags, S["battery_valid"] | S["charging"] | S["vbus"])
        self.assertEqual((st.power_state, st.cal_kind, st.cal_samples, st.cal_coverage,
                          st.cal_last_result), (0, 0, 0, 0, -1))
        self.assertEqual(self.dev.read_char("battery"), bytes([87]))
        self.dev.set_battery(charging=False, charge_done=True)
        self.settle(300)   # a charge event shows at the next PMU poll
        self.assertTrue(self.status().flags & S["charge_done"])
        # No battery: zeros, not stale values.
        self.dev.set_battery(valid=False)
        self.settle(600)
        st = self.status()
        self.assertEqual((st.battery_pct, st.battery_mv), (0, 0))
        self.assertFalse(st.flags & S["battery_valid"])
        self.assertEqual(self.dev.read_char("battery"), b"\x00")

    def test_notify_rates(self):
        self.connect(trusted=False)
        for c in ("angle", "status", "battery"):
            self.dev.subscribe_char(c, True)
        t0 = self.dev.now_ms
        self.settle(2000)
        angles = [dc.decode_angle(v) for _, v in self.notifies("angle")]
        self.assertEqual(len(angles), 20)                          # 10 Hz
        self.assertEqual([a.seq for a in angles], list(range(20)))
        self.assertTrue(all(a.flags & dc.ANGLE_FLAGS["imu_ok"] for a in angles))
        status_t = [t - t0 for t, _ in self.notifies("status")]
        self.assertEqual(status_t, [100, 1100])                    # at once, then the 1 s heartbeat
        self.assertEqual([v for _, v in self.notifies("battery")], [bytes([80])])

        # battery_mv alone waits for the heartbeat...
        last = self.notifies("status")[-1][0]
        self.settle(last + 10 - self.dev.now_ms)
        t1 = self.dev.now_ms
        self.dev.set_battery(voltage_mv=3940)
        self.settle(2000)
        self.assertEqual([t for t, _ in self.notifies("status", since=t1)], [last + 1000, last + 2000])
        self.assertEqual(dc.decode_status(self.notifies("status")[-1][1]).battery_mv, 3940)
        # ...a percent change goes out at the next tick after the PMU poll (500 ms).
        last = self.notifies("status")[-1][0]
        self.settle(last + 10 - self.dev.now_ms)
        t2 = self.dev.now_ms
        self.dev.set_battery(percent=79)
        self.settle(700)
        st = self.notifies("status", since=t2)
        self.assertEqual(len(st), 1)
        self.assertLess(st[0][0], last + 1000)
        self.assertEqual(dc.decode_status(st[0][1]).battery_pct, 79)
        self.assertEqual([v for _, v in self.notifies("battery", since=t2)], [bytes([79])])

        # A calibration start is a change too.
        t2 = self.dev.now_ms
        self.connect_trusted_again()
        self.assertEqual(self.cmd("accel-cal-start", settle_ms=150), 0)
        st = [dc.decode_status(v) for _, v in self.notifies("status", since=t2)]
        self.assertTrue(any(s.cal_kind == 2 and s.flags & S["calibrating"] for s in st))

    def connect_trusted_again(self):
        self.dev.encryption_changed(True, True)

    def test_angles_pause_during_ota(self):
        self.connect()
        self.dev.subscribe_char("angle", True)
        self.dev.subscribe_char("ota_ctrl", True)
        self.dev.subscribe(lambda n, d: n == "indicate" and self.dev.indication_confirmed())
        self.settle(500)
        image = b"\xE9" + bytes(9999)
        import hashlib
        self.assertEqual(self.dev.write_char("ota_ctrl", dc.encode_ota_start(
            len(image), hashlib.sha256(image).digest())), 0)
        t = self.dev.now_ms + 1   # after the tick that ran before the write
        self.settle(1000)
        self.assertEqual(self.notifies("angle", since=t), [])
        self.assertEqual(self.names("conn_params")[-1][1], {"fast": True})
        self.assertTrue(self.snap()["ota_active"])
        self.assertEqual(self.dev.write_char("ota_ctrl", bytes([dc.OTA_OP_ABORT])), 0)
        t = self.dev.now_ms
        self.settle(1000)
        self.assertEqual(len(self.notifies("angle", since=t)), 10)
        self.assertEqual(self.names("conn_params")[-1][1], {"fast": False})

    def test_idle_unauthenticated_link_is_dropped(self):
        self.connect(trusted=False)
        self.settle(59_000)
        self.assertEqual(self.names("disconnect_request"), [])
        self.settle(1500)
        self.assertEqual([d["reason"] for _, d in self.names("disconnect_request")], ["idle_unauth"])
        # Subscribed or trusted links stay.
        self.dev.disconnect()
        self.connect(trusted=False)
        self.dev.subscribe_char("status", True)
        self.settle(61_000)
        self.dev.disconnect()
        self.connect(trusted=True)
        self.settle(61_000)
        self.assertEqual(len(self.names("disconnect_request")), 1)

    def test_identity_and_advertising(self):
        self.assertEqual(self.dev.name, "DrillBalancer-F2A4")
        self.assertEqual(self.dev.read_char("manufacturer"), b"DrillBalancer")
        self.assertEqual(self.dev.read_char("model"),
                         b"DrillBalancer (ESP32-S3-Touch-AMOLED-1.75)")
        self.assertEqual(self.dev.read_char("fw_revision"), self.dev.fw_version.encode())
        self.assertTrue(self.dev.advertising)
        self.connect()
        self.assertFalse(self.dev.advertising)
        # BLE switched off in the settings: link dropped, no advertising.
        self.assertEqual(self.write_config(ble=False), 0)
        self.assertIn("ble_disabled", [d["reason"] for _, d in self.names("disconnect_request")])
        self.dev.disconnect()
        self.assertFalse(self.dev.advertising)
        self.assertFalse(self.dev.connect())


if __name__ == "__main__":
    unittest.main()
