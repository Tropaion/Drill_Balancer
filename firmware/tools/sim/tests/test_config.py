"""Config characteristic: JSON round trip, persistence and every ATT error."""

import json
import unittest

from simtest import DeviceTest

DEFAULTS = {"tol": 0.5, "near": 2, "target": 45, "mode": "wall", "bright": 100,
            "dim_bright": 20, "dim_s": 30, "sleep_s": 120, "off_min": 60, "ble": True,
            "stale_s": 60, "cal": {"mount": False, "accel": False}, "ver": 1}


class ConfigTest(DeviceTest):
    def test_defaults(self):
        self.assertEqual(self.config(), DEFAULTS)

    def test_round_trip_and_persistence(self):
        upd = {"tol": 0.2, "near": 1.5, "target": -12.5, "mode": "floor", "bright": 80,
               "dim_bright": 10, "dim_s": 15, "sleep_s": 300, "off_min": 0, "stale_s": 120}
        self.assertEqual(self.write_config(**upd), 0)
        cfg = self.config()
        for k, v in upd.items():
            self.assertEqual(cfg[k], v, k)
        self.assertEqual(self.angle().mode, 1)          # the sensor picked up the reload
        self.assertEqual(self.snap()["brightness"], 80)
        # Partial update keeps the rest.
        self.assertEqual(self.write_config(tol=0.3), 0)
        self.assertEqual(self.config()["near"], 1.5)
        # A new boot (same state dir) loads it.
        raw = self.dev.read_char("config")
        self.dev = self.new_device()
        self.assertEqual(self.dev.read_char("config"), raw)
        self.assertEqual(self.angle().mode, 1)

    def test_att_errors(self):
        w = lambda data: self.dev.write_char("config", data)
        cases = [
            (b"", 0x0D),                               # empty write
            (b" " * 513, 0x0D),                        # longer than an attribute
            (b"nope", 0x80),
            (b"[1,2]", 0x80),                          # not an object
            (b'{"tol":0.2} x', 0x80),                  # trailing garbage
            (b'{"tol":"a"}', 0x81),
            (b'{"ble":1}', 0x81),
            (b'{"bright":50.5}', 0x81),
            (b'{"mode":2}', 0x81),
            (b'{"tol":9}', 0x82),
            (b'{"near":0.1}', 0x82),                   # below tol
            (b'{"mode":"sideways"}', 0x82),
            (b'{"bright":300}', 0x82),
            (b'{"dim_bright":90,"bright":50}', 0x82),  # dim above bright
            (b'{"sleep_s":10}', 0x82),                 # below dim_s
        ]
        for data, att in cases:
            self.assertEqual(w(data), att, data)
        self.assertEqual(self.config(), DEFAULTS, "a rejected write changes nothing")
        # Accepted oddities: unknown keys, a C string's NUL, whitespace.
        self.assertEqual(w(b'{"foo":1,"cal":{"mount":true},"ver":7}'), 0)
        self.assertEqual(w(b'{"tol":0.4}\0'), 0)
        self.assertEqual(w(b' {"tol":0.45} \n'), 0)
        self.assertEqual(self.config()["cal"], {"mount": False, "accel": False})
        # Store failure: 0x83, nothing changed; an unchanged value needs no store.
        self.dev.store_fail = True
        self.assertEqual(w(b'{"tol":0.3}'), 0x83)
        self.assertEqual(self.config()["tol"], 0.45)
        self.assertEqual(w(b'{"tol":0.45}'), 0)
        self.dev.store_fail = False
        self.assertEqual(w(b'{"tol":0.3}'), 0)

    def test_long_read_is_consistent(self):
        first = self.dev.read_char("config", 0)[:22]
        self.assertEqual(self.cmd("set-mode", "angle"), 0)   # changed elsewhere
        # A blob read after the change still continues the captured document...
        rest = self.dev.read_char("config", 22)
        self.assertEqual(json.loads(first + rest)["mode"], "wall")
        # ...and a new read starts over.
        self.assertEqual(json.loads(self.dev.read_char("config", 0))["mode"], "angle")
        # The client's own write starts over too.
        first = self.dev.read_char("config", 0)[:22]
        self.assertEqual(self.write_config(mode="floor"), 0)
        self.assertEqual(json.loads(first + self.dev.read_char("config", 22))["mode"], "floor")
        # A rejected write keeps it (ble_service.c config_write returns first)...
        first = self.dev.read_char("config", 0)[:22]
        self.assertEqual(self.cmd("set-mode", "wall"), 0)
        self.assertEqual(self.write_config(tol="x"), 0x81)
        self.assertEqual(self.write_config(near=0.1), 0x82)
        self.assertEqual(json.loads(first + self.dev.read_char("config", 22))["mode"], "floor")
        # ...a new link starts over.
        self.connect(trusted=False)
        self.assertEqual(json.loads(first + self.dev.read_char("config", 22))["mode"], "wall")

    def test_device_settings_screen(self):
        # As the device's own settings screen: merged onto the stored settings.
        self.assertEqual(self.dev.ui_settings('{"tol":3,"near":3}'), 0)
        cfg = self.config()
        self.assertEqual((cfg["tol"], cfg["near"], cfg["target"]), (3, 3, 45))
        # A BLE write made against the old values now breaks near >= tol.
        self.assertEqual(self.write_config(near=2.5), 0x82)
        self.assertEqual(self.dev.ui_settings('{"near":1}'), 1)
        self.assertEqual(self.dev.ui_settings('nope'), -1)
        self.dev.store_fail = True
        self.assertEqual(self.dev.ui_settings('{"tol":2}'), -2)
        self.assertEqual(self.config()["tol"], 3)


if __name__ == "__main__":
    unittest.main()
