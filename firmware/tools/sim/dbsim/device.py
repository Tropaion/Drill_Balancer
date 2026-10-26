"""The simulated DrillBalancer: firmware core + physics on one clock.

DbSimDevice runs the sensor task (20 ms passes), the control task (200 ms,
and right after a queued command), the BLE notify timer (100 ms while
connected), the OTA timer (1 s), the recorder task (100 ms) and, during a
take, the microphones' DMA blocks (16 ms) on a virtual millisecond clock.
Tests step it with advance(ms); run() drives it from asyncio in real time
(or faster).

The recorder's network side is an endpoint object (dbsim/recorder.py's
RecorderServer, a TCP server on the LAN) set as rec_endpoint: START_AP calls
its ap_start(), STOP_AP its ap_stop(), and it reports back with
rec_endpoint_up() / rec_hello() / rec_client_lost() / rec_endpoint_down()
and moves the stream with rec_pop(). Without one the endpoint is "up" at
once on 127.0.0.1 and the caller plays the client with the same methods.
A DrillScenario (start_scenario()) drives the IMU and the microphones; its
ground truth for each take goes to state/rec_truth_<take>.json.

The BLE layer calls the link and GATT methods (connect, write_char, ...) and
reacts to events; the web UI reads snapshot() and uses the pose and fault
controls. Events go to subscribe() callbacks and events() queues as
(name, data):

  state             {"state": "running" | "sleep" | "off" | "booting", "reason"}
  advertising       {"on": bool}  advertise (name, main service UUID) or stop
  disconnect_request {"reason"}   the BLE layer must drop the link (sleep, off,
                    reboot, factory_reset, ble_disabled, idle_unauth, fault)
  clear_bonds       {}            factory reset: forget all bonds
  notify            {"char": "angle" | "status" | "battery", "value": bytes}
  indicate          {"char": "ota_ctrl", "value": bytes}; the BLE layer calls
                    indication_confirmed() once the client confirmed it
  conn_params       {"fast": bool}
  connected / disconnected / security {"trusted": bool} / passkey {"pending", "passkey"}
  power             {"state": "active" | "dim" | "sleep" | "off"}
  settings          {"json": str}  stored settings changed
  cal               the "cal" part of the snapshot
  ota               {"active", "percent", "state"}
  boot              {"version", "reason": "cold" | "motion" | "reboot"}
  log               {"level": "I" | "W" | "E", "msg", "source": "fw" | "sim" | "ble"}
  ble               BLE transport events (ble.py): {"event", "peer", ...}
  rec               recorder: {"event": "take_start" | "take_end" | "scenario" |
                    "endpoint" | ..., ...}
"""

from __future__ import annotations

import asyncio
import collections
import hashlib
import json
import os
import random
from pathlib import Path

from . import core as C
from .physics import ODR_HZ, DrillPhysics, DrillScenario
from .wire import dc

DEFAULT_MAC = "3C:0A:F3:D9:F2:A4"
DEFAULT_IMAGE = C.FIRMWARE_DIR / "build" / "drillbalancer.bin"

# esp_image_header_t + esp_image_segment_header_t, then esp_app_desc_t.
_DESC_OFFSET = 32
_DESC_MAGIC = 0xABCD5432


def image_version(path) -> str | None:
    """esp_app_desc_t.version of an app image, or None."""
    try:
        with open(path, "rb") as f:
            head = f.read(_DESC_OFFSET + 256)
    except OSError:
        return None
    if len(head) < _DESC_OFFSET + 80 or \
            int.from_bytes(head[_DESC_OFFSET:_DESC_OFFSET + 4], "little") != _DESC_MAGIC:
        return None
    return head[_DESC_OFFSET + 16:_DESC_OFFSET + 48].split(b"\0")[0].decode(errors="replace")


# GATT characteristic names used by the BLE layer.
SUB_BITS = {"angle": C.SUB_ANGLE, "status": C.SUB_STATUS, "battery": C.SUB_BAT,
            "ota_ctrl": C.SUB_OTA, "rec_state": C.SUB_REC}
ATT_UNLIKELY = 0x0E
LOOPBACK_ENDPOINT = ("127.0.0.1", dc.REC_STREAM_PORT)


def board_id(mac: str) -> bytes:
    """SESSION.board_id: a salted hash of the chip id, never the MAC itself."""
    return hashlib.sha256(b"drillbalancer-board:" + mac.upper().encode()).digest()[:4]


class DbSimDevice:
    PASS_MS = 20              # sensor_task.c POLL_PERIOD_MS
    NOTIFY_MS = 100           # ble_service.c NOTIFY_PERIOD_US
    CONTROL_MS = 200          # control.c CMD_TIMEOUT_MS
    OTA_TICK_MS = 1000        # ota_update.c TICK_PERIOD_US
    CORE_BOOT_MS = 300        # app_now_ms() when app_main starts the tasks
    # app_now_ms() by which the IMU rate is measured: imu_frame.h
    # IMU_RATE_MIN_BASELINE_US after the boot, plus a pass
    IMU_TRUSTED_MS = CORE_BOOT_MS + 2000 + 40
    RESET_MS = 1200           # reset until the firmware runs again (bootloader, init)
    IND_QUEUE_LEN = 8         # ble_service.c IND_QUEUE_LEN
    REBOOT_DELAY_MS = 500     # ble_service.c REBOOT_DELAY_US
    REBOOT_FALLBACK_MS = 3000
    WOM_THRESHOLD_G = 0.150   # power.c WAKE_ON_MOTION_MG
    ACL_BUFFERS = 24          # CONFIG_BT_NIMBLE_TRANSPORT_ACL_FROM_LL_COUNT
    REC_TICK_MS = 100         # recorder.c task timer
    AUDIO_BLOCK_MS = 16       # rec_audio.c: 256 frames at 16 kHz per DMA block
    STOP_AP_MS = 200          # REC_ACT_STOP_AP: what is queued goes out first
    TRUTH_KEEP = 32           # scenarios kept for the ground truth

    def __init__(self, state_dir=None, physics: DrillPhysics | None = None, mac=DEFAULT_MAC,
                 default_version: str | None = None, seed=None, boot=True):
        self.state_dir = Path(state_dir) if state_dir else C.SIM_DIR / "state"
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.state_path = self.state_dir / "device_state.json"
        self.ota_image_path = self.state_dir / "ota_image.bin"
        self.physics = physics or DrillPhysics(seed)
        self.mac = mac.upper()
        b = self.mac.split(":")
        self.name = f"DrillBalancer-{b[4]}{b[5]}"   # ble_service_init(): last 2 MAC bytes
        self.default_version = default_version or image_version(DEFAULT_IMAGE) or "0.0.0-sim"
        self.core = C.Core()
        self.seed = seed
        self._rng = random.Random(seed)   # session tokens; os.urandom without a seed
        self.core.seed(seed if seed is not None else random.getrandbits(32))
        self.core.set_identity(board_id(self.mac), C.firmware_git())

        # recorder
        self.rec_endpoint = None          # RecorderServer (recorder.py), or None
        self.rec_unavailable = None       # why there is no stream server: every OPEN fails
        self._rec_ring_size = C.REC_RING_DEFAULT
        self._odr = ODR_HZ                # the simulated chip's sample rate
        self._take = None                 # the running take, for the ground truth
        self.last_truth = None
        # [[DrillScenario, physics start s, stop s or None, its truth() or None until asked]]
        self._scenarios = []

        self.now_ms = 0
        self.state = "off"
        self._boot_at = 0
        self._subscribers = []
        self._timers = []          # [due_ms, seq, fn]
        self._timer_seq = 0
        self._sample_acc = 0.0
        self._stopping = False

        # link (ble_service.c)
        self.connected = False
        self.mtu = 23
        self._subs = set()
        self._ind = collections.deque()
        self._ind_busy = False
        self._reboot_armed = False
        self._reboot_posted = False
        self._reboot_timer = None
        self._advertising = False

        # faults
        self.imu_present = True
        self.ota_write_delay_ms = 0       # slow flash: per OTA data write
        self._drop_ind_skip = 0
        self._drop_ind_count = 0
        self._store_fail = False
        self._flash_fail = False
        self._ota_fifo = collections.deque()
        self._ota_busy_until = 0
        self.ota_rx_bytes = 0             # ota_data bytes received (web UI transfer rate)

        # the battery the PMU reads
        self.battery = {"valid": True, "percent": 80, "voltage_mv": 3950, "charging": False,
                        "charge_done": False, "vbus": False}
        self._push_battery()

        self._last_power = None
        self._sleep_ref = None
        self._sleep_since = 0
        self._off_after_s = 0
        # The sensor pass keeps one cadence from t = 0 through halts and boots:
        # DrillPhysics.t advances PASS_MS a pass, so it stays on the sim clock
        # (physics.t * 1000 == now_ms after each pass) that the microphones and
        # the ground truth use.
        self._next = {"pass": self.PASS_MS}
        if boot:
            self.power_on()

    # ---- events -------------------------------------------------------------------
    def subscribe(self, callback):
        """callback(name, data) for every event. Returns an unsubscribe function."""
        self._subscribers.append(callback)
        return lambda: self._subscribers.remove(callback) if callback in self._subscribers else None

    def events(self, maxsize=1000) -> asyncio.Queue:
        """An asyncio.Queue of (name, data); the oldest event is dropped when full."""
        q = asyncio.Queue(maxsize)

        def put(name, data):
            if q.full():
                q.get_nowait()
            q.put_nowait((name, data))
        self.subscribe(put)
        return q

    def _emit(self, name, data=None):
        for cb in list(self._subscribers):
            cb(name, data or {})

    def emit(self, name, data=None):
        """Puts an event of another layer (BLE transport, web UI) on the device's bus."""
        self._emit(name, data)

    def _log(self, msg, level="I"):
        self._emit("log", {"level": level, "msg": msg, "source": "sim"})

    # ---- clock --------------------------------------------------------------------
    @property
    def core_now(self) -> int:
        """app_now_ms() of the running firmware (restarts at every boot)."""
        return (self.now_ms - self._boot_at + self.CORE_BOOT_MS) & 0xFFFFFFFF

    def _schedule(self, delay_ms, fn):
        self._timer_seq += 1
        t = [self.now_ms + max(0, int(delay_ms)), self._timer_seq, fn]
        self._timers.append(t)
        return t

    def _cancel(self, timer):
        if timer in self._timers:
            self._timers.remove(timer)

    def advance(self, ms):
        """Runs the device for ms milliseconds of virtual time."""
        end = self.now_ms + int(ms)
        while True:
            due = [t[0] for t in self._timers] + list(self._next.values())
            if self._ota_fifo and self.state == "running":
                due.append(max(self._ota_busy_until, self.now_ms))
            t = min(due) if due else end + 1
            if t > end:
                break
            self.now_ms = max(self.now_ms, t)
            self._run_due()
        self.now_ms = end

    def settle_imu(self):
        """Runs until the IMU driver has measured the ODR (IMU_TRUSTED_MS): only
        then does it fill the samples the FIFO loses in read mode, so a move
        before that integrates ~12 % short and leaves a tilt error the
        accelerometer takes seconds to remove."""
        self.advance(max(0, self.IMU_TRUSTED_MS - self.core_now))

    def _run_due(self):
        now = self.now_ms
        for t in sorted((t for t in self._timers if t[0] <= now), key=lambda t: (t[0], t[1])):
            if t in self._timers:
                self._timers.remove(t)
                t[2]()
        if self._next.get("pass", now + 1) <= now:
            self._next["pass"] += self.PASS_MS
            self._pass()
        if self.state != "running":
            return
        if self._next.get("control", now + 1) <= now:
            self._next["control"] = now + self.CONTROL_MS
            self._control()
        if self.state == "running" and self._next.get("notify", now + 1) <= now:
            self._next["notify"] += self.NOTIFY_MS
            self._notify_tick()
        if self.state == "running" and self._next.get("ota", now + 1) <= now:
            self._next["ota"] += self.OTA_TICK_MS
            self.core.ota_tick(self.core_now)
            self._drain()
        if self.state == "running" and self._next.get("rec", now + 1) <= now:
            self._next["rec"] += self.REC_TICK_MS
            self.core.rec_tick(self.core_now)
            self._drain()
        if self.state == "running" and self._next.get("audio", now + 1) <= now:
            self._next["audio"] += self.AUDIO_BLOCK_MS
            self._audio_pass()
        while self.state == "running" and self._ota_fifo and now >= self._ota_busy_until:
            self._ota_flush_one()

    async def run(self, speed=1.0, step_s=0.005):
        """Runs in real time (speed x) until stop()."""
        loop = asyncio.get_running_loop()
        self._stopping = False
        t0, v0 = loop.time(), self.now_ms
        while not self._stopping:
            target = v0 + int((loop.time() - t0) * 1000 * speed)
            if target > self.now_ms:
                self.advance(target - self.now_ms)
            await asyncio.sleep(step_s)

    def stop(self):
        self._stopping = True

    # ---- power and boot -----------------------------------------------------------
    def power_on(self, reason="cold"):
        """Cold start (PWR key or USB on a switched-off device). No-op while running."""
        if self.state in ("running", "booting"):
            return
        self._boot(reason)

    def _boot(self, reason):
        self._boot_at = self.now_ms
        self.core.rec_set_ring_size(self._rec_ring_size)
        self.core.init(self.state_path, self.ota_image_path, self.default_version,
                       self.core_now, self.imu_present)
        self._apply_faults()
        self._push_battery()
        self.state = "running"
        self.connected = False
        self._clear_link()
        self._reboot_armed = self._reboot_posted = False
        self._reboot_timer = None
        self._last_power = None
        now = self.now_ms
        self._next = {"pass": self._next["pass"], "control": now, "ota": now + self.OTA_TICK_MS,
                      "rec": now + self.REC_TICK_MS}
        self._odr = self.core.imu_odr_hz
        self._take = None
        self._drain()
        self._emit("state", {"state": "running", "reason": reason})
        self._emit("boot", {"version": self.core.fw_version, "reason": reason})
        self._update_adv()

    def _reset(self, reason):
        """esp_restart(), or a wake from deep sleep: back after RESET_MS."""
        self._halted("booting", reason)
        self._schedule(self.RESET_MS, lambda: self._boot(reason))

    def _halted(self, state, reason):
        was_connected = self.connected
        self.state = state
        self.connected = False
        self._next = {"pass": self._next["pass"]}   # physics keeps running, on its cadence
        self._ind.clear()
        self._ind_busy = False
        self._ota_fifo.clear()
        self._timers = [t for t in self._timers if t is not self._reboot_timer]
        if was_connected:
            self._emit("disconnect_request", {"reason": reason})
        self._emit("state", {"state": state, "reason": reason})
        self._update_adv()

    @property
    def fw_version(self) -> str:
        return self.core.fw_version

    @property
    def advertising(self) -> bool:
        return self._advertising

    def _update_adv(self):
        on = (self.state == "running" and self.core.ble_enabled and not self.connected and
              not self._reboot_armed)
        if on != self._advertising:
            self._advertising = on
            self._emit("advertising", {"on": on})

    # ---- task passes --------------------------------------------------------------
    def _pass(self):
        # The samples that arrived at the chip since the last pass, at the
        # rate of the profile it runs.
        odr = self._odr
        self._sample_acc += self.PASS_MS / 1000 * odr
        n = int(self._sample_acc)
        self._sample_acc -= n
        samples = self.physics.samples(n, 1.0 / odr)
        if self.state == "running":
            present = self.imu_present
            self.core.sensor_pass(self.core_now, samples if present else [], 1.0 / odr, present)
            self._odr = self.core.imu_odr_hz
            self._drain()
        elif self.state == "sleep":
            self._sleep_pass(samples)

    def _sleep_pass(self, samples):
        # Wake on motion: the accelerometer moved by more than the threshold.
        for s in samples:
            if self._sleep_ref is None:
                self._sleep_ref = s[:3]
            elif self.imu_present and \
                    max(abs(a - b) for a, b in zip(s[:3], self._sleep_ref)) > self.WOM_THRESHOLD_G:
                self._log("woke up by motion")
                self._reset("motion")
                return
        off_s = self._off_after_s
        if off_s and self.now_ms - self._sleep_since >= off_s * 1000:
            self._log("off timer expired: power off")
            self._halted("off", "off_timer")

    def _control(self):
        self.core.control_step(self.core_now)
        # The power state only changes in the control task.
        power = C.POWER_NAMES[self.core.power]
        if power != self._last_power:
            self._last_power = power
            self._emit("power", {"state": power})
        self._drain()

    def _control_soon(self):
        # The control task wakes on the queued command.
        self._schedule(1, lambda: self.state == "running" and self._control())

    def _notify_tick(self):
        if not self.connected:
            return
        tx, angle, status, bat = self.core.notify_tick(self.core_now)
        if tx & C.TX_ANGLE:
            self._emit("notify", {"char": "angle", "value": angle})
        if tx & C.TX_STATUS:
            self._emit("notify", {"char": "status", "value": status})
        if tx & C.TX_BATTERY:
            self._emit("notify", {"char": "battery", "value": bytes([bat])})
        if tx & (C.TX_CONN_FAST | C.TX_CONN_SLOW):
            self._emit("conn_params", {"fast": bool(tx & C.TX_CONN_FAST)})
        if tx & C.TX_KICK:
            self._emit("disconnect_request", {"reason": "idle_unauth"})
        self._drain()

    def _drain(self):
        """Firmware log, OTA replies, rec_state notifications and core events after
        every core call."""
        for level, msg in self.core.log_lines():
            self._emit("log", {"level": level, "msg": msg, "source": "fw"})
        if self.connected and self.state == "running":
            # rec_state goes out on every change (the FSM's NOTIFY), not on a timer.
            rec = self.core.rec_notify(self.core_now)
            if rec is not None:
                self._emit("notify", {"char": "rec_state", "value": rec})
        for msg in self.core.ota_replies():
            self._ota_reply(msg)
        ev = self.core.take_events()
        if ev & C.EV_SETTINGS:
            self._emit("settings", {"json": self.core.settings_json()})
        if ev & C.EV_CAL:
            self._emit("cal", self.core.snapshot()["cal"])
        if ev & C.EV_OTA:
            snap = self.core.snapshot()
            self._emit("ota", {"active": snap["ota_active"], "percent": snap["ota_percent"],
                               "state": snap["ota_state"]})
        if ev & C.EV_CLEAR_BONDS:
            self._emit("clear_bonds")
            if self.connected:
                self._emit("disconnect_request", {"reason": "factory_reset"})
        if ev & C.EV_BLE_ENABLED and not self.core.ble_enabled and self.connected:
            self._emit("disconnect_request", {"reason": "ble_disabled"})
        if ev & C.EV_REC_TAKE:
            self._rec_take_changed()
        if ev & C.EV_REC_AUDIO:
            if self.core.rec_audio_on:
                self._next["audio"] = self.now_ms + self.AUDIO_BLOCK_MS
            else:
                self._next.pop("audio", None)
        if ev & C.EV_REC_AP:
            self._rec_ap_changed()
        if ev & C.EV_SLEEP:
            self._off_after_s = self.core.off_after_s
            self._sleep_since = self.now_ms
            self._sleep_ref = None
            self._halted("sleep", "sleep")
        elif ev & C.EV_POWER_OFF:
            self._halted("off", "power_off")
        elif ev & C.EV_REBOOT:
            self._reset("reboot")
        else:
            self._update_adv()

    # ---- user and hardware inputs -------------------------------------------------
    def touch(self) -> bool:
        """A touch on the screen: user activity. During a take it is an EVENT
        TOUCH and the UI ignores it (returns True then)."""
        taken = self.core.rec_note_touch(self.core_now)
        self.core.note_activity(self.core_now)
        return taken

    def rec_ui_stop(self) -> int:
        """The 2 s long-press on the REC pill: stops the take."""
        att = self.core.rec_ui_stop(self.core_now)
        self._drain()
        return att

    def ui_command(self, cmd_id, arg=0) -> bool:
        """A command from the touch UI (core.APP_CMD_*); counts as a touch."""
        self.touch()
        ok = self.core.post_cmd(cmd_id, arg)
        self._control_soon()
        return ok

    def ui_settings(self, json_text: str) -> int:
        """A change on the device's settings screen (a JSON patch, e.g. '{"tol":3}').
        0 stored, 1 a range or cross-field rule broken, -1 bad JSON / not running, -2 store failed."""
        err = self.core.ui_settings_edit(json_text, self.core_now)
        self._control_soon()
        return err

    def pwrkey(self, long_press=False):
        """PWR key: short press sleeps, long press powers off; on a switched-off device, on."""
        if self.state == "off":
            self.power_on("cold")
        elif self.state == "running":
            self.core.pwrkey(long_press)

    def set_battery(self, **kw):
        """Fault injection / PMU readings: valid, percent, voltage_mv, charging, charge_done, vbus."""
        bad = set(kw) - set(self.battery)
        if bad:
            raise TypeError(f"unknown battery fields {bad}")
        plugged = kw.get("vbus") and not self.battery["vbus"]
        self.battery.update(kw)
        self._push_battery()
        if plugged and self.state == "off":
            self.power_on("cold")   # the AXP2101 switches on with USB power

    def _push_battery(self):
        b = self.battery
        self.core.set_battery(b["valid"], b["percent"], b["voltage_mv"], b["charging"],
                              b["charge_done"], b["vbus"])

    # ---- recorder: drilling scenarios, microphones, ground truth --------------------
    def start_scenario(self, **kw) -> DrillScenario:
        """Starts a DrillScenario (physics.DrillScenario arguments) now, stopping a running one."""
        self._end_scenario()
        scn = self.physics.start_scenario(**kw)
        self._scenarios.append([scn, self.physics.t, None, None])
        del self._scenarios[:-self.TRUTH_KEEP]
        self._emit("rec", {"event": "scenario", "params": scn.params, "duration_s": scn.duration})
        return scn

    def stop_scenario(self):
        self._end_scenario()
        self.physics.stop_scenario()

    def _end_scenario(self):
        # The ground truth of a scenario cut short ends where it stopped.
        sc = self.physics.scenario
        for entry in self._scenarios:
            if sc is not None and entry[0] is sc[0] and entry[2] is None:
                entry[2], entry[3] = self.physics.t, None

    @property
    def rec_ring_size(self) -> int:
        return self._rec_ring_size

    @rec_ring_size.setter
    def rec_ring_size(self, size: int):
        """The stream ring's size from the next OPEN (the board has 4 MB)."""
        self._rec_ring_size = int(size)
        self.core.rec_set_ring_size(self._rec_ring_size)

    def set_mic_gain(self, mic: int, db: float):
        """Microphone sensitivity (1 or 2), dB: what reaches the ES7210."""
        self.core.set_mic_gain(mic, db)

    def _audio_pass(self):
        # One DMA block, ending now.
        t1 = self.now_ms / 1000
        m = self.physics.motor_block(t1 - self.AUDIO_BLOCK_MS / 1000, t1)
        motor = C.Motor()
        motor.f0_hz[:] = m["f0_hz"]
        motor.hum[:] = m["hum"]
        motor.noise[:] = m["noise"]
        motor.ambient = m["ambient"]
        hits = m["impacts"][:C.MAX_IMPACTS]
        motor.n_impacts = len(hits)
        for i, (t, amp, tau) in enumerate(hits):
            motor.impact_s[i], motor.impact_amp[i], motor.impact_tau_s[i] = t, amp, tau
        self.core.audio_block(self.core_now, motor)
        self._drain()

    def _core_us(self, t_phys: float) -> int:
        """esp_timer us of a physics time (DrillPhysics.t runs on the sim clock)."""
        return int(round((t_phys * 1000 - self._boot_at + self.CORE_BOOT_MS) * 1000))

    def truth_for(self, start_us: int, end_us: int) -> dict:
        """The ground truth of the scenarios between two device times (esp_timer us)."""
        events, spans, segments, holes, used = [], [], [], [], []
        for entry in self._scenarios:
            t0, tr = entry[1], self._scenario_truth(entry)
            s0, s1 = self._core_us(t0), self._core_us(t0 + tr["duration_s"])
            if s1 < start_us or s0 > end_us:
                continue
            base = len(used)
            used.append({"params": dict(tr["params"]), "start_us": s0, "end_us": s1,
                         **({"stopped_us": s1} if "stopped_s" in tr else {})})
            for e in tr["events"]:
                us = self._core_us(t0 + e["t"])
                if start_us <= us <= end_us:
                    events.append({"type": e["type"], "t_us": us, "scenario": base,
                                   **({"hole": e["hole"]} if "hole" in e else {})})
            for sp in tr["spans"]:
                a, b = self._core_us(t0 + sp["t0"]), self._core_us(t0 + sp["t1"])
                if b >= start_us and a <= end_us:
                    spans.append({"type": sp["type"], "t0_us": a, "t1_us": b, "hole": sp["hole"],
                                  "scenario": base})
            for sg in tr["segments"]:
                a, b = self._core_us(t0 + sg["t0"]), self._core_us(t0 + sg["t1"])
                if b >= start_us and a <= end_us:
                    segments.append({"state": sg["state"], "t0_us": a, "t1_us": b, "scenario": base})
            seen = {e.get("hole") for e in events if e["scenario"] == base}
            holes += [{**h, "scenario": base} for h in tr["holes"] if h["hole"] in seen]
        return {"schema": "dbsim.truth/1", "start_us": start_us, "end_us": end_us,
                "scenarios": used, "events": events, "spans": spans, "holes": holes,
                "segments": segments}

    def _scenario_truth(self, entry: list) -> dict:
        """scn.truth() of a scenario entry, kept in it (the panel asks at every push):
        it changes only when the scenario is stopped (_end_scenario). Read only."""
        scn, t0, t_stop, truth = entry
        if truth is None:
            truth = entry[3] = scn.truth(None if t_stop is None else t_stop - t0)
        return truth

    def rec_truth(self) -> dict | None:
        """The ground truth of the running take (so far), else of the last one."""
        if self._take is not None:
            t = dict(self._take)
            return {**t, **self.truth_for(t["start_us"], self.core_now * 1000), "running": True}
        return self.last_truth

    def _rec_take_changed(self):
        rec = self.core.snapshot()["rec"]
        if rec["tap"]:
            if self._take is None or self._take["take_id"] != rec["take_id"]:
                self._take = {"take_id": rec["take_id"], "start_us": self.core_now * 1000,
                              "profile": rec["profile"]}
                self._emit("rec", {"event": "take_start", "take_id": rec["take_id"]})
            return
        if self._take is None:
            return
        take, self._take = self._take, None
        end_us = self.core_now * 1000
        reason = dc._name(dc.REC_REASONS, rec["reason"])
        truth = {**take, "end_reason": reason, **self.truth_for(take["start_us"], end_us),
                 "running": False}
        self.last_truth = truth
        path = self.state_dir / f"rec_truth_{take['take_id']}.json"
        try:
            path.write_text(json.dumps(truth, indent=1) + "\n")
        except OSError as e:
            self._log(f"ground truth not written: {e}", "W")
        self._emit("rec", {"event": "take_end", "take_id": take["take_id"], "reason": reason,
                           "truth": str(path), "events": len(truth["events"])})

    # ---- recorder: the network side (rec_wifi.c, the TCP server) ------------------
    def _rec_ap_changed(self):
        if self.core.rec_ap_wanted:
            if self.rec_unavailable:
                # python -m dbsim could not start its stream server: AP_FAIL, as a
                # soft-AP that does not come up
                self._log(f"recorder unavailable: {self.rec_unavailable}", "E")
                self.rec_endpoint_failed()
            elif self.rec_endpoint is not None:
                self.rec_endpoint.ap_start()
            else:
                self.rec_endpoint_up(*LOOPBACK_ENDPOINT)
        elif self.rec_endpoint is not None:
            self.rec_endpoint.ap_stop()
        else:
            self._schedule(self.STOP_AP_MS, self.rec_endpoint_down)

    def _token(self) -> bytes:
        return self._rng.randbytes(16) if self.seed is not None else os.urandom(16)

    def rec_endpoint_up(self, ip: str, port: int, no_ap: bool = True, channel: int = 0,
                        ssid: str = "", password: str = "") -> bytes | None:
        """The endpoint START_AP asked for is up: rec_state announces it with a
        new session token (returned; None if no session wants one)."""
        token = self._token()
        self.core.rec_set_endpoint(no_ap, ip, port, channel, ssid, password)
        ok = self.core.rec_net(C.NET_UP, token, self.core_now)
        self._drain()
        if ok:
            self._emit("rec", {"event": "endpoint", "up": True, "ip": ip, "port": port,
                               "no_ap": no_ap})
        return token if ok else None

    def rec_endpoint_failed(self):
        self.core.rec_net(C.NET_FAIL, b"", self.core_now)
        self._drain()

    def rec_endpoint_down(self):
        """STOP_AP is done: the endpoint is gone."""
        self.core.rec_net(C.NET_DOWN, b"", self.core_now)
        self._drain()
        self._emit("rec", {"event": "endpoint", "up": False})

    def rec_hello(self, data: bytes) -> bool:
        """A client's first bytes (token + version): True if it may stay."""
        ok = self.core.rec_net(C.NET_HELLO, bytes(data), self.core_now)
        self._drain()
        return ok

    def rec_client_lost(self):
        """The stream client is gone (its socket is already closed)."""
        if self.core.rec_net(C.NET_CLIENT_LOST, b"", self.core_now):
            self._emit("rec", {"event": "client_lost"})
        self._drain()

    def rec_session(self) -> dict | None:
        """What rec_state announces to the app (the stream endpoint and the
        session token), or None without a session: for a client on this PC
        that does not read it over BLE (db_client.py rec pull --host)."""
        r = dc.decode_rec_state(self.core.rec_state())
        if r.state_name == "idle":
            return None
        return {"ip": r.ip, "port": r.port, "token": r.token.hex(), "no_ap": "no_ap" in r.flag_names,
                "ssid": r.ssid}

    def rec_pop(self, cap: int = 65536) -> bytes:
        """What the sender sends now (whole records or parts; works while halted)."""
        data = self.core.rec_pop(cap)
        if data and self.state == "running":
            self._drain()   # a drained ring moves the FSM on
        return data

    # ---- faults ---------------------------------------------------------------------
    def drop_ota_indications(self, count, after=0):
        """Drops the next `count` OTA indications after letting `after` through."""
        self._drop_ind_skip = after
        self._drop_ind_count = count

    def busy_next_commands(self, n=1):
        """The next n valid command writes are answered with BUSY (0x86)."""
        self.core.fault_busy(n)

    def force_disconnect(self):
        """Asks the BLE layer to drop the link (as on a radio loss)."""
        if self.connected:
            self._emit("disconnect_request", {"reason": "fault"})

    @property
    def store_fail(self) -> bool:
        return self._store_fail

    @store_fail.setter
    def store_fail(self, on: bool):
        """Settings writes to "NVS" fail (config 0x83, calibration result -2)."""
        self._store_fail = bool(on)
        self._apply_faults()

    @property
    def flash_fail(self) -> bool:
        return self._flash_fail

    @flash_fail.setter
    def flash_fail(self, on: bool):
        """OTA flash writes fail (NACK FLASH_ERROR)."""
        self._flash_fail = bool(on)
        self._apply_faults()

    def _apply_faults(self):
        self.core.set_faults((C.FAULT_STORE if self._store_fail else 0) |
                             (C.FAULT_FLASH if self._flash_fail else 0))

    # ---- BLE link (called by the BLE layer) ----------------------------------------
    def _clear_link(self):
        """Drops the per-link transport state: subscriptions, indications, OTA writes."""
        self._subs.clear()
        self._ind.clear()
        self._ind_busy = False
        self._ota_fifo.clear()

    def connect(self) -> bool:
        """A central connected. False: terminate the link at once."""
        if self.state != "running":
            return False
        ok = self.core.link_connect(self.core_now)
        if not ok:
            self.core.link_disconnect(self.core_now)
            self._drain()
            return False
        self.connected = True
        self.mtu = 23
        self._clear_link()
        self._next["notify"] = self.now_ms + self.NOTIFY_MS
        self._emit("connected")
        self._drain()
        return True

    def disconnect(self):
        """The link is gone (either side)."""
        if not self.connected:
            return
        self.connected = False
        self._clear_link()
        self._next.pop("notify", None)
        self.core.link_disconnect(self.core_now)
        self._emit("disconnected")
        if self._reboot_armed:
            self._reboot_after(self.REBOOT_DELAY_MS)
        self._control_soon()   # APP_CMD_CAL_CANCEL from BLE
        self._drain()

    def pairing_passkey(self, passkey: int):
        """Pairing started: the device shows this passkey (BlueZ DisplayPasskey)."""
        self.core.link_passkey(passkey, self.core_now)
        self._emit("passkey", {"pending": True, "passkey": passkey})
        self._drain()

    def encryption_changed(self, ok: bool, authenticated: bool = True, key_size: int = 16):
        """Encryption on (ok) or pairing failed. Trusted = authenticated, 16-byte key."""
        trusted = ok and authenticated and key_size >= 16
        self.core.link_enc_change(0 if ok else 1, trusted, self.core_now)
        self._emit("passkey", {"pending": False, "passkey": 0})
        self._emit("security", {"trusted": self.core.link_trusted})
        self._drain()

    @property
    def link_trusted(self) -> bool:
        return self.core.link_trusted

    def set_mtu(self, mtu: int):
        self.mtu = mtu
        self.core.ota_set_mtu(mtu)

    def subscribe_char(self, char: str, on: bool):
        """CCCD write: 'angle', 'status', 'battery', 'rec_state' (notify), 'ota_ctrl' (indicate)."""
        if on:
            self._subs.add(char)
        else:
            self._subs.discard(char)
        self.core.link_subscribe(SUB_BITS[char], on)

    # ---- GATT -----------------------------------------------------------------------
    def read_char(self, char: str, offset: int = 0) -> bytes:
        """'manufacturer', 'model', 'fw_revision', 'battery', 'status', 'config', 'rec_state'.

        Returns the value from offset on, like an ATT read blob; IndexError if
        offset is past the end (ATT 0x07 invalid offset).
        """
        if char == "manufacturer":
            value = self.core.dis_string(0).encode()
        elif char == "model":
            value = self.core.dis_string(1).encode()
        elif char == "fw_revision":
            value = self.core.fw_version.encode()
        elif char == "battery":
            value = bytes([self.core.battery_pct])
        elif char == "status":
            value = self.core.status_packet()
        elif char == "config":
            value = self.core.config_read(offset)   # captured at offset 0 (long reads)
        elif char == "rec_state":
            value = self.core.rec_state()
        else:
            raise KeyError(char)
        if offset > len(value):
            raise IndexError(offset)
        return value[offset:]

    def write_char(self, char: str, data: bytes) -> int:
        """GATT write; returns 0 or the ATT error code."""
        if self.state != "running":
            return ATT_UNLIKELY
        data = bytes(data)
        if char == "config":
            att = self.core.config_write(data, self.core_now)
            self._control_soon()
        elif char == "command":
            att = self.core.command_write(data, self.core_now)
            if att == 0:
                self._control_soon()
        elif char == "rec_ctrl":
            att = self.core.rec_ctrl_write(data, self.core_now)
        elif char == "ota_ctrl":
            self._ota_flush_all()   # keeps the order behind queued data writes
            att = self.core.ota_ctrl_write(data, self.core_now)
            self._drain()
            self._check_reboot()
        elif char == "ota_data":
            self.ota_rx_bytes += len(data)
            if self.ota_write_delay_ms > 0:
                # Slow flash: the host task is busy, writes wait in the ACL buffers.
                self.core.note_activity(self.core_now)
                if len(self._ota_fifo) >= self.ACL_BUFFERS:
                    self._log("ota_data write lost: ACL buffers full", "W")
                else:
                    self._ota_fifo.append(data)
                return 0
            att = self.core.ota_data_write(data, self.core_now)
        else:
            raise KeyError(char)
        self._drain()
        return att

    def _ota_flush_one(self):
        self.core.ota_data_write(self._ota_fifo.popleft(), self.core_now)
        self._ota_busy_until = self.now_ms + self.ota_write_delay_ms
        self._drain()

    def _ota_flush_all(self):
        while self._ota_fifo:
            self._ota_flush_one()

    # ---- OTA indications (ble_service.c ind queue) --------------------------------
    def _ota_reply(self, msg: bytes):
        if len(self._ind) >= self.IND_QUEUE_LEN:
            self._log(f"ota reply queue full, 0x{msg[0]:02x} dropped", "W")
            return
        self._ind.append(msg)
        self._ind_pump()

    def _ind_pump(self):
        while not self._ind_busy and self._ind:
            msg = self._ind[0]
            subscribed = "ota_ctrl" in self._subs
            dropped = False
            if subscribed and self.connected and self._drop_ind_count > 0:
                if self._drop_ind_skip > 0:
                    self._drop_ind_skip -= 1
                else:
                    self._drop_ind_count -= 1
                    dropped = True
            if subscribed and self.connected and not dropped:
                self._ind_busy = True
                self._emit("indicate", {"char": "ota_ctrl", "value": msg})
                return
            self._log(f"ota reply 0x{msg[0]:02x} dropped "
                      f"({'injected' if dropped else 'not subscribed'})", "W")
            self._ind.popleft()

    def indication_confirmed(self):
        """The client confirmed the OTA indication in flight (or it timed out)."""
        if self._ind_busy and self._ind:
            self._ind.popleft()
        self._ind_busy = False
        self._ind_pump()
        if self._reboot_armed and self._ind_idle():
            self._reboot_after(self.REBOOT_DELAY_MS)

    def _ind_idle(self) -> bool:
        return not self._ind_busy and not self._ind

    # ---- reboot after OTA (ble_service.c) -----------------------------------------
    def _check_reboot(self):
        if self._reboot_armed or not self.core.ota_reboot_pending:
            return
        self._reboot_armed = True
        self._log("OTA done, reboot once the reply is confirmed")
        self._reboot_after(self.REBOOT_DELAY_MS if self._ind_idle() else self.REBOOT_FALLBACK_MS)
        self._update_adv()

    def _reboot_after(self, delay_ms):
        if self._reboot_posted:
            return
        if self._reboot_timer is not None:
            self._cancel(self._reboot_timer)
        self._reboot_timer = self._schedule(delay_ms, self._reboot_cb)

    def _reboot_cb(self):
        self._reboot_timer = None
        if self.state != "running":
            return
        self._log("rebooting into the new image")
        self._reboot_posted = True
        if self.core.post_cmd(C.APP_CMD_REBOOT, 0):
            self._control()
        else:
            self._reset("reboot")   # the backstop: esp_restart() directly

    # ---- state ----------------------------------------------------------------------
    def snapshot(self) -> dict:
        """Core snapshot (app_state + settings + UI values) plus the simulator's own state."""
        snap = self.core.snapshot() if self.state == "running" else {}
        p = self.physics
        snap.update({
            "device_state": self.state,
            "name": self.name,
            "mac": self.mac,
            "sim_ms": self.now_ms,
            "connected": self.connected,
            "advertising": self._advertising,
            "mtu": self.mtu,
            "subscriptions": sorted(self._subs),
            "reboot_armed": self._reboot_armed,
            "fw_version": self.core.fw_version,
            "battery_input": dict(self.battery),
            "faults": {"imu_present": self.imu_present, "store_fail": self._store_fail,
                       "flash_fail": self._flash_fail, "ota_write_delay_ms": self.ota_write_delay_ms,
                       "drop_ota_indications": self._drop_ind_count},
            "recorder": self._rec_snapshot(),
            "physics": {"elevation": p.elevation, "yaw": p.yaw, "roll": p.roll,
                        "board_face": p.board_face, "mount_offset": list(p.mount_offset),
                        "moving": p.moving, "vibration_g": p.vibration_g,
                        "q": list(p.orientation)},
        })
        return snap

    def _rec_snapshot(self) -> dict:
        sc = self.physics.scenario
        scenario = None
        if sc is not None:
            m, t0 = sc
            scenario = {"params": m.params, "elapsed_s": round(self.physics.t - t0, 2),
                        "duration_s": round(m.duration, 2)}
        truth = self.rec_truth()
        summary = None
        if truth is not None:
            kinds = collections.Counter(e["type"] for e in truth["events"])
            summary = {"take_id": truth["take_id"], "running": truth["running"],
                       "events": dict(sorted(kinds.items())), "spans": len(truth["spans"]),
                       "end_reason": truth.get("end_reason")}
        ep = self.rec_endpoint
        return {"endpoint": ep.status() if ep is not None else None, "unavailable": self.rec_unavailable,
                "session": self.rec_session(),
                "scenario": scenario,
                "truth": summary, "take": dict(self._take) if self._take else None}
