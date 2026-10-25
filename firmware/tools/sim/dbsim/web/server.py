"""Web control panel of the simulator (aiohttp): one static page, a websocket
that pushes the device state at the UI's tick rate, and JSON commands.

  GET  /            the panel (index.html, app.js, style.css; no build step)
  GET  /ws          websocket: {"type": "state", ...} every UI_TICK_MS; the page
                    may also send commands on it (touch gestures, to keep order)
  GET  /api/state   the same state once (scripts, tests)
  POST /api/cmd     {"cmd": name, ...} -> {"ok": true, "msg"} or 400 {"error"}

Every command is validated here; see COMMANDS for the names and arguments.
"""

from __future__ import annotations

import asyncio
import collections
import ipaddress
import json
import math
import time
from pathlib import Path

from aiohttp import WSMsgType, web

from ..physics import FACES, LiftMotion, RollMotion
from .screen import DeviceScreen

STATIC_DIR = Path(__file__).resolve().parent
PUSH_MS = 66                  # ui_priv.h UI_TICK_MS: the device redraws at this rate
LOG_LEN = 600
BASE_ACCEL_NOISE_G = 0.0015   # physics.py defaults: noise level 1
BASE_GYRO_NOISE = 0.002

# ble_protocol.h
CMD_NAMES = {0x01: "ZERO", 0x02: "CLEAR_ZERO", 0x03: "SET_MODE", 0x10: "MOUNT_CAL_START",
             0x11: "MOUNT_CAL_FINISH", 0x12: "ACCEL_CAL_START", 0x13: "ACCEL_CAL_FINISH",
             0x14: "CAL_CANCEL", 0x20: "SLEEP", 0x21: "POWER_OFF", 0x22: "REBOOT",
             0x23: "FACTORY_RESET"}
OTA_OPS = {0x01: "START", 0x02: "FINISH", 0x03: "ABORT"}
ATT_NAMES = {0x05: "insufficient authentication", 0x07: "invalid offset",
             0x0D: "invalid length", 0x0E: "unlikely error", 0x80: "JSON_PARSE",
             0x81: "JSON_TYPE", 0x82: "JSON_RANGE", 0x83: "STORE", 0x84: "UNKNOWN_CMD",
             0x85: "BAD_ARG", 0x86: "BUSY", 0x87: "UNAVAILABLE"}
MODES = ("wall", "floor", "angle")
PRESETS = {"wall": 0.0, "floor": -90.0, "ceiling": 90.0, "deg45": 45.0}   # elevation
BUTTONS = ("cal_left", "cal_right", "set_mount", "set_sensor", "set_clear_zero", "set_reset",
           "set_back", "confirm_cancel", "confirm_reset")


class BadRequest(Exception):
    pass


def finite(obj):
    """obj with NaN / infinity replaced by None (JSON.parse rejects them)."""
    if isinstance(obj, float):
        return obj if math.isfinite(obj) else None
    if isinstance(obj, dict):
        return {k: finite(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [finite(v) for v in obj]
    return obj


def _num(d: dict, key: str, lo: float, hi: float, default=None) -> float:
    v = d.get(key, default)
    if v is None:
        raise BadRequest(f"'{key}' is missing")
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise BadRequest(f"'{key}' must be a number")
    if not lo <= v <= hi:
        raise BadRequest(f"'{key}' must be within {lo} .. {hi}")
    return float(v)


def _int(d: dict, key: str, lo: int, hi: int, default=None) -> int:
    v = _num(d, key, lo, hi, default)
    if v != int(v):
        raise BadRequest(f"'{key}' must be a whole number")
    return int(v)


def _bool(d: dict, key: str, default=None) -> bool:
    v = d.get(key, default)
    if not isinstance(v, bool):
        raise BadRequest(f"'{key}' must be true or false")
    return v


def _choice(d: dict, key: str, options, default=None):
    v = d.get(key, default)
    if v not in options:
        raise BadRequest(f"'{key}' must be one of {', '.join(map(str, options))}")
    return v


def _vec3(d: dict, key: str, lo: float, hi: float) -> list:
    v = d.get(key)
    if not isinstance(v, list) or len(v) != 3:
        raise BadRequest(f"'{key}' must be a list of 3 numbers")
    return [_num({"v": x}, "v", lo, hi) for x in v]


def _att(code: int) -> str:
    return f"0x{code:02X} {ATT_NAMES.get(code, '')}".rstrip()


def describe_write(char: str, value: bytes) -> str:
    """A GATT write as the log shows it."""
    if char == "command" and value:
        name = CMD_NAMES.get(value[0], f"0x{value[0]:02X}")
        if value[0] == 0x03 and len(value) > 1:
            return f"command {name} {MODES[value[1]] if value[1] < 3 else value[1]}"
        return f"command {name}" + (f" {value[1:].hex()}" if len(value) > 1 else "")
    if char == "ota_ctrl" and value:
        op = OTA_OPS.get(value[0], f"0x{value[0]:02X}")
        if value[0] == 0x01 and len(value) >= 5:
            return f"ota_ctrl {op} size {int.from_bytes(value[1:5], 'little')}"
        return f"ota_ctrl {op}"
    if char == "config":
        return "config " + value.decode(errors="replace")
    return f"{char} {value.hex()}"


class WebPanel:
    def __init__(self, dev, ble=None):
        self.dev = dev
        self.ble = ble
        self.screen = DeviceScreen(dev)
        self.clients: set[web.WebSocketResponse] = set()
        self.log = collections.deque(maxlen=LOG_LEN)
        self._new_log = []
        self._seq = 0
        self._ota_window = collections.deque()   # (time, rx bytes)
        self._ota_start_bytes = None
        self._ota_last = {"bytes": 0, "seconds": 0.0}
        self._ota_t0 = 0.0
        self._task = None
        self._unsubscribe = dev.subscribe(self._on_event)

    # ---- event log ----------------------------------------------------------------------
    def add_log(self, source: str, msg: str, level: str = "I", kind: str = ""):
        self._seq += 1
        e = {"id": self._seq, "t": time.time(), "src": source, "level": level, "msg": msg,
             "kind": kind}
        self.log.append(e)
        self._new_log.append(e)

    def _on_event(self, name: str, data: dict):
        if name == "log":
            self.add_log(data["source"], data["msg"], data["level"])
        elif name == "ble":
            self._ble_event(data)
        elif name == "boot":
            self.screen.reset()   # ui_init() after every boot
            self.add_log("dev", f"firmware {data['version']} booted ({data['reason']})")
        elif name == "state":
            self.add_log("dev", f"device {data['state']} ({data['reason']})")
        elif name == "power":
            self.add_log("dev", f"power {data['state']}")
        elif name == "ota":
            if data["active"] and self._ota_start_bytes is None:
                self._ota_start_bytes = self.dev.ota_rx_bytes
                self._ota_t0 = time.monotonic()
            elif not data["active"] and self._ota_start_bytes is not None:
                self._ota_last = {"bytes": self.dev.ota_rx_bytes - self._ota_start_bytes,
                                  "seconds": time.monotonic() - self._ota_t0}
                self._ota_start_bytes = None
                self.add_log("dev", f"OTA ended (state {data['state']})")
        elif name == "settings":
            self.add_log("dev", f"settings stored: {data['json']}", kind="settings")

    def _ble_event(self, d: dict):
        # Connects, pairing and advertising are already in the BLE log lines.
        ev = d.get("event")
        if ev == "write":
            msg = describe_write(d["char"], bytes.fromhex(d["value"]))
            if d["size"] > 32:
                msg += f" ... ({d['size']} bytes)"
            if d["att"]:
                self.add_log("ble", f"write {msg} -> {_att(d['att'])}", "W", "att")
            else:
                self.add_log("ble", f"write {msg}", kind="write")
        elif ev == "read":
            if d["att"]:
                self.add_log("ble", f"read {d['char']} @{d['offset']} -> {_att(d['att'])}", "W", "att")
            else:
                self.add_log("ble", f"read {d['char']} ({d['size']} bytes)", kind="read")
        elif ev == "subscribe":
            self.add_log("ble", f"{'subscribed' if d['on'] else 'unsubscribed'} {d['char']}")
        elif ev == "mtu":
            self.add_log("ble", f"MTU {d['mtu']}")
        elif ev == "conn_params":
            self.add_log("ble", f"wants {'fast' if d['fast'] else 'slow'} connection parameters "
                                "(not possible as a BlueZ peripheral)")

    # ---- state --------------------------------------------------------------------------
    def _ota_rate(self, active: bool) -> dict:
        now = time.monotonic()
        w = self._ota_window
        w.append((now, self.dev.ota_rx_bytes))
        while w and now - w[0][0] > 2.0:
            w.popleft()
        rate = 0.0
        if active and len(w) > 1 and w[-1][0] > w[0][0]:
            rate = (w[-1][1] - w[0][1]) / (w[-1][0] - w[0][0]) / 1024
        done = self.dev.ota_rx_bytes - self._ota_start_bytes if self._ota_start_bytes is not None else 0
        return {"kbps": round(rate, 1), "bytes": done, "last": self._ota_last}

    def state(self) -> dict:
        snap = finite(self.dev.snapshot())
        self.screen.update(snap)
        p = self.dev.physics
        return {
            "type": "state",
            "snap": snap,
            "screen": self.screen.view(),
            "link": self.ble.status() if self.ble else None,
            "ble": self.ble is not None,
            "ota": self._ota_rate(bool(snap.get("ota_active"))),
            "sim": {
                "roll_running": p.running(RollMotion),
                "noise": round(p.accel_noise_g / BASE_ACCEL_NOISE_G, 2),
                "accel_bias": list(p.accel_bias), "accel_scale": list(p.accel_scale),
            },
        }

    async def _push_loop(self):
        while True:
            await asyncio.sleep(PUSH_MS / 1000)
            try:
                msg = self.state()
            except Exception as e:   # never let a bad frame stop the panel
                self.add_log("web", f"state: {type(e).__name__}: {e}", "E")
                continue
            msg["log"], self._new_log = self._new_log, []
            if not self.clients:
                continue
            text = json.dumps(msg)
            for ws in list(self.clients):
                if ws.closed:
                    self.clients.discard(ws)
                    continue
                try:
                    await ws.send_str(text)
                except (ConnectionError, RuntimeError):
                    self.clients.discard(ws)

    # ---- commands -----------------------------------------------------------------------
    def command(self, d) -> str:
        if not isinstance(d, dict):
            raise BadRequest("expected a JSON object")
        name = d.get("cmd")
        fn = COMMANDS.get(name) if isinstance(name, str) else None
        if fn is None:
            raise BadRequest(f"unknown cmd {name!r}")
        msg = fn(self, d)
        if msg and name != "touch":
            self.add_log("web", msg, kind="control")
        return msg

    def c_pose(self, d):
        kw = {}
        for key, lim in (("elevation", 90), ("yaw", 180), ("roll", 180)):
            if key in d:
                kw[key] = _num(d, key, -lim, lim)
        if not kw:
            raise BadRequest("give elevation, yaw and/or roll")
        self.dev.physics.set_drill(**kw)
        return ""   # sliders send many of these

    def c_preset(self, d):
        name = _choice(d, "name", tuple(PRESETS))
        self.dev.physics.set_drill(elevation=PRESETS[name])
        return f"pose preset {name} (elevation {PRESETS[name]:+.0f}°)"

    def c_face(self, d):
        face = _choice(d, "face", tuple(FACES))
        self.dev.physics.set_board_face(face)
        return f"board on the table, {face} up"

    def c_vibration(self, d):
        self.dev.physics.vibration_g = _num(d, "g", 0, 2)
        return f"drill vibration {self.dev.physics.vibration_g:.2f} g"

    def c_noise(self, d):
        level = _num(d, "level", 0, 20)
        self.dev.physics.accel_noise_g = BASE_ACCEL_NOISE_G * level
        self.dev.physics.gyro_noise_rad_s = BASE_GYRO_NOISE * level
        return f"sensor noise x{level:g}"

    def c_mount_offset(self, d):
        p = self.dev.physics
        cur = list(p.mount_offset)
        for i, key in enumerate("xyz"):
            if key in d:
                cur[i] = _num(d, key, -15, 15)
        p.mount_offset = tuple(cur)
        return "mount offset {:+.1f} {:+.1f} {:+.1f}°".format(*cur)

    def c_accel_error(self, d):
        p = self.dev.physics
        if "bias" in d:
            p.accel_bias = _vec3(d, "bias", -0.25, 0.25)
        if "scale" in d:
            p.accel_scale = _vec3(d, "scale", 0.8, 1.25)
        if "gyro_bias" in d:
            p.gyro_bias = _vec3(d, "gyro_bias", -0.1, 0.1)
        return (f"accel bias {[round(v, 3) for v in p.accel_bias]} g, "
                f"scale {[round(v, 3) for v in p.accel_scale]}")

    def c_roll(self, d):
        p = self.dev.physics
        p.stop_motions(RollMotion)
        if not _bool(d, "on"):
            return "roll in hole stopped"
        amp = _num(d, "amplitude", 5, 90, 50)
        period = _num(d, "period", 1, 30, 4)
        p.roll_back_and_forth(amp, period, cycles=1e6)
        return f"roll in hole: ±{amp:g}°, {period:g} s per cycle"

    def c_nudge(self, d):
        # A light knock: well over the 0.15 g wake-on-motion threshold.
        self.dev.physics.start_motion(LiftMotion(True, height_m=0.08, duration_s=0.4,
                                                 rng=self.dev.physics.rng))
        return "motion nudge"

    def c_touch(self, d):
        action = _choice(d, "action", ("press", "tap", "swipe", "hold", "button"))
        dir = _choice(d, "dir", ("left", "right", "up", "down")) if action == "swipe" else None
        button = _choice(d, "button", BUTTONS) if action == "button" else None
        what = self.screen.touch(action, dir, button)
        if what:
            self.add_log("web", " ".join(filter(None, ("touch", action, dir, button))) + f": {what}",
                         kind="touch")
        return what

    def c_battery(self, d):
        kw = {}
        if "percent" in d:
            pct = _int(d, "percent", 0, 100)
            kw["percent"] = pct
            kw["voltage_mv"] = 3400 + 8 * pct   # a rough Li-ion curve; only the percent is shown
        for key in ("valid", "charging", "charge_done", "vbus"):
            if key in d:
                kw[key] = _bool(d, key)
        if not kw:
            raise BadRequest("give percent, valid, charging, charge_done and/or vbus")
        self.dev.set_battery(**kw)
        b = self.dev.battery
        return (f"battery {b['percent']}%{'' if b['valid'] else ' (invalid)'}"
                f"{', charging' if b['charging'] else ''}{', charge done' if b['charge_done'] else ''}"
                f"{', USB in' if b['vbus'] else ''}")

    def c_settings(self, d):
        # As if changed on the device's own settings screen: {"cmd": "settings", "set": {"tol": 3}}
        patch = d.get("set")
        if not isinstance(patch, dict) or not patch:
            raise BadRequest("'set' must be a JSON object of settings keys")
        text = json.dumps(patch)
        err = self.dev.ui_settings(text)
        if err:
            raise BadRequest({1: "rejected by settings_validate()", -1: "bad JSON or device not running",
                              -2: "store failed"}.get(err, f"error {err}"))
        return f"device settings screen: {text}"

    def c_pwrkey(self, d):
        long = _bool(d, "long", False)
        self.dev.pwrkey(long)
        return f"PWR key {'held 4 s' if long else 'short press'}"

    def c_imu(self, d):
        self.dev.imu_present = _bool(d, "present")
        return "IMU " + ("back" if self.dev.imu_present else "lost")

    def c_busy(self, d):
        n = _int(d, "n", 0, 50, 1)
        self.dev.busy_next_commands(n)
        return f"next {n} command write(s) answered BUSY"

    def c_drop_ind(self, d):
        n = _int(d, "count", 0, 100, 1)
        after = _int(d, "after", 0, 100000, 0)
        self.dev.drop_ota_indications(n, after)
        return f"drop {n} OTA indication(s) after {after}"

    def c_disconnect(self, d):
        if not self.dev.connected:
            raise BadRequest("not connected")
        self.dev.force_disconnect()
        return "disconnect now"

    def c_slow_flash(self, d):
        self.dev.ota_write_delay_ms = _int(d, "ms", 0, 500)
        return f"OTA flash write delay {self.dev.ota_write_delay_ms} ms"

    def c_store_fail(self, d):
        self.dev.store_fail = _bool(d, "on")
        return f"settings store {'fails' if self.dev.store_fail else 'works'}"

    def c_flash_fail(self, d):
        self.dev.flash_fail = _bool(d, "on")
        return f"OTA flash writes {'fail' if self.dev.flash_fail else 'work'}"

    # ---- HTTP ---------------------------------------------------------------------------
    async def h_state(self, request):
        return web.json_response(self.state())

    async def h_cmd(self, request):
        try:
            d = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            return web.json_response({"error": "body must be JSON"}, status=400)
        try:
            return web.json_response({"ok": True, "msg": self.command(d)})
        except BadRequest as e:
            return web.json_response({"error": str(e)}, status=400)
        except Exception as e:   # a bug: logged, and the sim keeps running
            self.add_log("web", f"command failed: {type(e).__name__}: {e}", "E")
            return web.json_response({"error": f"{type(e).__name__}: {e}"}, status=500)

    async def h_ws(self, request):
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        await ws.send_str(json.dumps({"type": "hello", "name": self.dev.name, "mac": self.dev.mac,
                                      "log": list(self.log)}))
        self.clients.add(ws)
        try:
            async for m in ws:
                if m.type != WSMsgType.TEXT:
                    continue
                try:
                    d = json.loads(m.data)
                    reply = {"type": "reply", "id": d.get("id") if isinstance(d, dict) else None}
                    reply.update(ok=True, msg=self.command(d))
                except (BadRequest, json.JSONDecodeError) as e:
                    reply = {"type": "reply", "ok": False, "error": str(e)}
                except Exception as e:   # a bug must not cost the page its stream
                    self.add_log("web", f"command failed: {type(e).__name__}: {e}", "E")
                    reply = {"type": "reply", "ok": False, "error": f"{type(e).__name__}: {e}"}
                await ws.send_str(json.dumps(reply))
        finally:
            self.clients.discard(ws)
        return ws

    def app(self) -> web.Application:
        app = web.Application(middlewares=[_same_origin_only])
        app.router.add_get("/", _static("index.html"))
        app.router.add_get("/ws", self.h_ws)
        app.router.add_get("/api/state", self.h_state)
        app.router.add_post("/api/cmd", self.h_cmd)
        for name in ("app.js", "style.css"):
            app.router.add_get(f"/{name}", _static(name))
        return app


@web.middleware
async def _same_origin_only(request, handler):
    """Keeps other web pages in the user's browser away from the panel. Binding to
    127.0.0.1 is not enough: any page can POST a text/plain body to /api/cmd or open
    /ws (websockets have no same-origin rule), and DNS rebinding puts a foreign
    page on our address. So the Host must be an IP address or localhost (a rebound
    name is neither), and a browser's Origin must be this server. curl and scripts
    send no Origin and still work."""
    host = request.url.host or ""
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if host != "localhost":
            return web.json_response({"error": "forbidden host"}, status=403)
    origin = request.headers.get("Origin")
    if origin is not None and origin != f"{request.scheme}://{request.host}":
        return web.json_response({"error": "cross-origin request"}, status=403)
    return await handler(request)


def _static(name: str):
    async def handler(request):
        return web.FileResponse(STATIC_DIR / name, headers={"Cache-Control": "no-store"})
    return handler


COMMANDS = {name[2:]: fn for name, fn in vars(WebPanel).items() if name.startswith("c_")}


class Runner:
    """What start() returns: close() stops the server and the push loop."""

    def __init__(self, panel: WebPanel, runner: web.AppRunner):
        self.panel = panel
        self._runner = runner
        host, port = runner.addresses[0][:2]
        self.url = f"http://{host}:{port}"   # port 0 picks a free one

    async def close(self):
        p = self.panel
        if p._task:
            p._task.cancel()
            try:
                await p._task
            except asyncio.CancelledError:
                pass
        for ws in list(p.clients):
            await ws.close()
        p._unsubscribe()
        await self._runner.cleanup()


async def start(device, ble, host="127.0.0.1", port=8765) -> Runner:
    """Serves the panel for device (and the BluezPeripheral ble, or None)."""
    panel = WebPanel(device, ble)
    runner = web.AppRunner(panel.app(), access_log=None)
    await runner.setup()
    await web.TCPSite(runner, host, port).start()
    panel._task = asyncio.create_task(panel._push_loop())
    return Runner(panel, runner)
