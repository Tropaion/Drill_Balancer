"""Web panel (dbsim/web): command validation, the touch UI's screen rules, the
BLE events in the event log, and the HTTP / websocket surface."""

import asyncio
import json
import tempfile
import unittest

import aiohttp
from simtest import DeviceTest

from dbsim import DbSimDevice
from dbsim import ble as B
from dbsim import web
from dbsim.web.server import BadRequest, WebPanel

PHONE = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"


class PanelTest(DeviceTest):
    def setUp(self):
        super().setUp()
        self.panel = WebPanel(self.dev)
        self.settle()

    def cmd(self, **d):
        return self.panel.command(d)

    def touch(self, action, **kw):
        self.cmd(cmd="touch", action="press")
        what = self.cmd(cmd="touch", action=action, **kw)
        self.dev.advance(300)
        return what

    def test_commands_are_validated(self):
        bad = [{}, {"cmd": "nope"}, {"cmd": "pose"}, {"cmd": "pose", "elevation": 91},
               {"cmd": "pose", "yaw": "1"}, {"cmd": "pose", "roll": True},
               {"cmd": "pose", "elevation": float("nan")}, {"cmd": "preset", "name": "x"},
               {"cmd": "face", "face": "+W"}, {"cmd": "battery", "percent": 101},
               {"cmd": "battery", "percent": 1.5}, {"cmd": "accel_error", "bias": [0, 0]},
               {"cmd": "accel_error", "scale": [1, 1, 3]}, {"cmd": "busy", "n": -1},
               {"cmd": "touch", "action": "pinch"}, {"cmd": "touch", "action": "button"},
               {"cmd": "disconnect"}, {"cmd": "roll", "on": 1}]
        for d in bad:
            with self.assertRaises(BadRequest, msg=d):
                self.panel.command(d)
        p = self.dev.physics
        self.cmd(cmd="pose", elevation=12.5, yaw=-3, roll=40)
        self.assertEqual((p.elevation, p.yaw, p.roll), (12.5, -3, 40))
        self.cmd(cmd="preset", name="floor")
        self.assertEqual(p.elevation, -90)
        self.cmd(cmd="battery", percent=30, vbus=True)
        self.assertEqual((self.dev.battery["percent"], self.dev.battery["vbus"]), (30, True))
        self.cmd(cmd="slow_flash", ms=15)
        self.assertEqual(self.dev.ota_write_delay_ms, 15)
        self.cmd(cmd="busy", n=1)
        self.assertEqual(self.dev.write_char("command", bytes([0x02])), 0x86)
        self.assertEqual(self.dev.write_char("command", bytes([0x02])), 0)

    def test_level_gestures(self):
        self.assertEqual(self.touch("tap"), "zero")
        self.assertTrue(self.snap()["zero_set"])
        self.touch("swipe", dir="left")
        self.assertEqual(self.snap()["reading"]["mode"], 1)
        self.touch("swipe", dir="right")
        self.assertEqual(self.snap()["reading"]["mode"], 0)
        self.touch("swipe", dir="up")
        self.assertEqual(self.snap()["reading"]["mode"], 0)
        self.touch("hold")
        self.assertEqual(self.panel.screen.screen, "settings")
        self.touch("swipe", dir="right")
        self.assertEqual(self.panel.screen.screen, "level")

    def test_no_zero_without_sensor(self):
        self.dev.imu_present = False
        self.settle()
        self.assertEqual(self.touch("tap"), "")
        self.assertFalse(self.snap()["zero_set"])

    def test_press_that_wakes_a_dim_screen_does_nothing_else(self):
        self.write_config(dim_s=5, sleep_s=60)
        self.dev.advance(6000)
        self.assertEqual(self.snap()["power"], 1)
        self.assertTrue(self.touch("tap").startswith("ignored"))
        self.assertFalse(self.snap()["zero_set"])
        self.assertEqual(self.snap()["power"], 0)
        self.assertEqual(self.touch("tap"), "zero")

    def test_overlays_swallow_touches(self):
        self.connect(trusted=False)
        self.dev.pairing_passkey(123456)
        self.dev.advance(100)
        self.assertTrue(self.touch("tap").startswith("ignored"))
        self.assertTrue(self.touch("hold").startswith("ignored"))
        self.assertEqual(self.panel.screen.screen, "level")

    def test_sensor_calibration_guide(self):
        self.touch("hold")
        self.touch("button", button="set_sensor")
        g = self.panel.state()["screen"]["cal"]
        self.assertEqual((g["phase"], g["right"], g["right_enabled"]), ("collect", "Finish", False))
        self.assertEqual(self.snap()["cal"]["kind"], 2)
        for face in ("+Z", "-Z", "+Y", "-Y", "-X", "+X"):
            self.cmd(cmd="face", face=face)
            self.dev.advance(3000)
            self.panel.state()
        g = self.panel.state()["screen"]["cal"]
        self.assertEqual((g["tiles"], g["count"], g["right_enabled"]), (0x3F, "6 / 6 sides", True))
        self.touch("button", button="cal_right")
        self.dev.advance(500)
        g = self.panel.state()["screen"]["cal"]
        self.assertEqual((g["phase"], g["result_ok"], g["left"], g["right"]),
                         ("result", True, "Retry", "Done"))
        self.assertTrue(self.snap()["accel_cal_valid"])
        self.touch("button", button="cal_right")
        self.assertEqual(self.panel.screen.screen, "level")

    def test_mount_guide_waits_for_start_and_cancel_returns(self):
        self.touch("hold")
        self.touch("button", button="set_mount")
        g = self.panel.state()["screen"]["cal"]
        self.assertEqual((g["phase"], g["right"]), ("ready", "Start"))
        self.assertEqual(self.snap()["cal"]["kind"], 0)
        self.touch("button", button="cal_right")
        self.assertEqual(self.snap()["cal"]["kind"], 1)
        self.touch("button", button="cal_left")
        self.assertEqual(self.panel.screen.screen, "settings")
        self.assertEqual(self.snap()["cal"]["kind"], 0)

    def test_guide_shows_a_result_when_cancelled_over_ble(self):
        self.touch("hold")
        self.touch("button", button="set_mount")
        self.touch("button", button="cal_right")
        self.panel.state()
        self.assertEqual(self.cmd_ble(0x14), 0)
        g = self.panel.state()["screen"]["cal"]
        self.assertEqual(g["phase"], "result")

    def cmd_ble(self, op):
        att = self.dev.write_char("command", bytes([op]))
        self.dev.advance(50)
        return att

    def test_factory_reset_needs_the_confirmation(self):
        self.write_config(tol=1.0)
        self.touch("hold")
        self.touch("button", button="set_reset")
        self.assertTrue(self.panel.screen.confirm)
        self.touch("button", button="confirm_cancel")
        self.assertEqual(self.config()["tol"], 1.0)
        self.touch("button", button="set_reset")
        self.touch("button", button="confirm_reset")
        self.settle()
        self.assertEqual(self.config()["tol"], 0.5)

    def test_screen_resets_on_boot(self):
        self.touch("hold")
        self.dev.write_char("command", bytes([0x22]))   # REBOOT
        self.dev.advance(3000)
        self.assertEqual(self.dev.state, "running")
        self.assertEqual(self.panel.screen.screen, "level")

    def test_drill_running_toggle_is_detected(self):
        self.assertIs(self.panel.state()["snap"]["vibrating"], False)
        self.cmd(cmd="vibration", g=0.3)   # what the Drill running toggle sends
        self.settle(2000)
        self.assertIs(self.panel.state()["snap"]["vibrating"], True)
        self.cmd(cmd="vibration", g=0)
        self.settle(2600)
        self.assertIs(self.panel.state()["snap"]["vibrating"], False)

    def test_state_is_json_without_nan(self):
        st = self.panel.state()
        json.loads(json.dumps(st, allow_nan=False))
        self.assertEqual(st["snap"]["device_state"], "running")
        self.assertIn("screen", st)


class BleLogTest(unittest.IsolatedAsyncioTestCase):
    """GATT traffic through a BluezPeripheral (no bus) ends up in the event log."""

    async def test_writes_and_att_errors_are_logged(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = DbSimDevice(tmp, seed=1, boot=False)
            dev.power_on()
            dev.advance(500)
            panel = WebPanel(dev)
            p = B.BluezPeripheral(dev, hci=False)

            async def call(*a, **kw):
                return []
            p.call = call
            p._started = True
            p.devices[PHONE] = {"Address": "AA:BB:CC:DD:EE:FF", "Alias": "Galaxy", "Paired": True,
                                "Bonded": True, "Connected": False}
            p._device_changed(PHONE, {"Connected": True})
            dev.encryption_changed(True, True)
            opts = {"device": PHONE, "mtu": 247, "link": "LE"}
            p.gatt_write("command", b"\x01", opts)
            with self.assertRaises(Exception):
                p.gatt_write("command", b"\x03\x09", opts)    # SET_MODE 9: BAD_ARG
            p.gatt_read("config", opts)
            msgs = [e["msg"] for e in panel.log]
            self.assertIn("write command ZERO", msgs)
            self.assertIn("write command SET_MODE 9 -> 0x85 BAD_ARG", msgs)
            self.assertTrue(any(m.startswith("read config (") for m in msgs))
            st = p.status()
            self.assertEqual((st["peer"], st["paired"], st["bonded"]), ("AA:BB:CC:DD:EE:FF", True, True))
            for t in list(p._tasks):
                t.cancel()


class HttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dev = DbSimDevice(self._tmp.name, seed=1)
        self.runner = await web.start(self.dev, None, "127.0.0.1", 0)
        self.url = self.runner.url

    async def asyncTearDown(self):
        await self.runner.close()
        self._tmp.cleanup()

    async def test_page_state_stream_and_commands(self):
        async with aiohttp.ClientSession() as s:
            for path in ("/", "/app.js", "/style.css"):
                async with s.get(self.url + path) as r:
                    self.assertEqual(r.status, 200, path)
            async with s.get(self.url + "/server.py") as r:
                self.assertEqual(r.status, 404)
            async with s.post(self.url + "/api/cmd", json={"cmd": "pose", "elevation": 200}) as r:
                self.assertEqual(r.status, 400)
            async with s.post(self.url + "/api/cmd", data=b"{") as r:
                self.assertEqual(r.status, 400)
            async with s.post(self.url + "/api/cmd", json={"cmd": "pose", "elevation": 20}) as r:
                self.assertEqual(r.status, 200)
            self.assertEqual(self.dev.physics.elevation, 20)
            async with s.ws_connect(self.url + "/ws") as ws:
                hello = json.loads((await ws.receive()).data)
                self.assertEqual(hello["type"], "hello")
                loop = asyncio.get_running_loop()
                t0, n = loop.time(), 0
                while loop.time() - t0 < 1.0:
                    m = json.loads((await ws.receive(timeout=1)).data)
                    n += m["type"] == "state"
                self.assertGreaterEqual(n, 10)
                self.assertLessEqual(n, 20)
                await ws.send_str(json.dumps({"cmd": "touch", "action": "press"}))
                while (m := json.loads((await ws.receive(timeout=1)).data))["type"] != "reply":
                    pass
                self.assertTrue(m["ok"])

    async def test_other_sites_are_refused(self):
        port = self.url.rsplit(":", 1)[1]
        cmd = '{"cmd": "pose", "elevation": 33}'
        async with aiohttp.ClientSession() as s:
            # A page elsewhere posting text/plain (no CORS preflight), and a page
            # on a name rebound to 127.0.0.1.
            for headers in ({"Origin": "https://evil.example", "Content-Type": "text/plain"},
                            {"Host": f"evil.example:{port}", "Origin": f"http://evil.example:{port}"},
                            {"Host": f"evil.example:{port}"}):
                async with s.post(self.url + "/api/cmd", data=cmd, headers=headers) as r:
                    self.assertEqual(r.status, 403, headers)
                with self.assertRaises(aiohttp.WSServerHandshakeError, msg=headers):
                    await s.ws_connect(self.url + "/ws", headers=headers)
            self.assertNotEqual(self.dev.physics.elevation, 33)
            # The panel itself, by IP, IPv6 or localhost, and scripts without Origin.
            for host in (f"127.0.0.1:{port}", f"localhost:{port}", f"[::1]:{port}"):
                headers = {"Host": host, "Origin": f"http://{host}"}
                async with s.post(self.url + "/api/cmd", data=cmd, headers=headers) as r:
                    self.assertEqual(r.status, 200, host)
                async with s.ws_connect(self.url + "/ws", headers=headers) as ws:
                    self.assertEqual(json.loads((await ws.receive()).data)["type"], "hello")
            async with s.post(self.url + "/api/cmd", data=cmd) as r:
                self.assertEqual(r.status, 200)


if __name__ == "__main__":
    unittest.main()
