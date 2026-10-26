"""The recorder end to end in the simulator: the firmware's rec_fsm / rec_ring /
rec_stream / rec_ts behind sim_core's glue mirror, the FIFO-loss model, the
microphones, the DrillScenario's ground truth, the LAN stream server and
db_client's pull.

No Bluetooth and no network changes: the BLE link is driven directly, the
stream runs in process or over loopback TCP. The dataset export runs
tools/dataset's venv in a subprocess (dataset_probe.py) and is skipped
without it.
"""

import asyncio
import contextlib
import io
import json
import math
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import aiohttp
from simtest import SIM_DIR, DeviceTest, dc

from dbsim import DbSimDevice
from dbsim.__main__ import start_recorder
from dbsim.physics import DrillScenario
from dbsim import recorder as R
from dbsim import web
from dbsim.web.server import BadRequest, WebPanel

# rec_stream.h payloads (db_client finds the records, these unpack them)
SESSION = struct.Struct("<IQ4s32sBBBBIHHBbBbBbIBBBBHH")
IMU = struct.Struct("<IIQHH")
AUDIO = struct.Struct("<QQHH")
EVENT = struct.Struct("<QHH")
STATS = struct.Struct("<Q11IHBB")
END = struct.Struct(dc.REC_END_FMT)
T_SESSION, T_IMU, T_AUDIO, T_EVENT, T_STATS, T_LEVEL, T_END = 1, 2, 3, 4, 5, 6, 0x0F
IMU_DISCONT, IMU_EPOCH = 1, 2
EV = {n: i + 1 for i, n in enumerate(("MARK", "TOUCH", "BLE_LOST", "BLE_BACK", "IMU_LOST",
                                      "IMU_BACK", "VIB_START", "VIB_STOP", "PROFILE"))}
REASON = {n: i for i, n in enumerate(dc.REC_REASONS)}
STATE = {n: i for i, n in enumerate(dc.REC_STATES)}
RAW448, RAW448_16G, RAW897 = 1, 2, 3
DATASET_PY = SIM_DIR.parent / "dataset" / ".venv" / "bin" / "python"
PROBE = Path(__file__).resolve().parent / "dataset_probe.py"


class Stream:
    """What a client received, decoded as far as the checks need."""

    def __init__(self, data: bytes):
        self.data = bytes(data)
        self.scan = dc.RecScanner()
        self.recs = self.scan.feed(self.data)

    def of(self, rtype):
        return [(seq, p) for t, _, seq, p in self.recs if t == rtype]

    def types(self):
        return [t for t, _, _, _ in self.recs]

    def seqs(self):
        return [seq for _, _, seq, _ in self.recs]

    def imu(self):
        """[(seq, ts_first, chip_ts, host_us, n, flags)]"""
        return [(seq, *IMU.unpack_from(p)) for seq, p in self.of(T_IMU)]

    def audio(self):
        return [(seq, *AUDIO.unpack_from(p)) for seq, p in self.of(T_AUDIO)]

    def events(self):
        """[(seq, code, arg, text)]"""
        out = []
        for seq, p in self.of(T_EVENT):
            _, code, arg = EVENT.unpack_from(p)
            out.append((seq, code, arg, p[EVENT.size:].decode()))
        return out

    def end(self):
        ends = self.of(T_END)
        return END.unpack_from(ends[-1][1]) if ends else None

    def session(self):
        s = self.of(T_SESSION)
        return SESSION.unpack_from(s[0][1]) if s else None


class RecorderTest(DeviceTest):
    """One device; the test is the app: BLE link, rec_ctrl, and the stream
    client popping what the device's sender sends."""

    def setUp(self):
        super().setUp()
        self.buf = bytearray()
        self.connect()

    # ---- helpers ----
    def ctrl(self, op, **kw):
        return self.dev.write_char("rec_ctrl", dc.encode_rec(op, **kw))

    def state(self):
        return dc.decode_rec_state(self.dev.read_char("rec_state"))

    def run_ms(self, ms, pop=True):
        """Advances in 20 ms steps, a fast client taking everything sent."""
        for _ in range(ms // 20):
            self.dev.advance(20)
            if pop:
                self.buf += self.dev.rec_pop()

    def open_session(self):
        self.assertEqual(self.ctrl("open"), 0)
        self.dev.advance(20)
        rs = self.state()
        self.assertEqual(rs.state_name, "wait_client")
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(rs.token)))
        self.dev.advance(20)
        self.assertEqual(self.state().state_name, "ready")
        return rs

    def take(self, ms, stop=True, **kw):
        """A take of ms milliseconds; returns what the client received."""
        kw.setdefault("take_id", 1)
        self.buf = bytearray()
        self.assertEqual(self.ctrl("take-start", **kw), 0)
        self.run_ms(ms)
        if stop:
            self.assertEqual(self.ctrl("stop"), 0)
            self.run_ms(300)
        return Stream(self.buf)

    def assert_end(self, s: Stream, reason: str):
        end = s.end()
        self.assertIsNotNone(end, "no END")
        self.assertEqual(dc.REC_REASONS[end[1]], reason)
        self.assertEqual(end[2], s.seqs()[-1] + 1, "END.records = its seq + 1")
        return end

    # ---- session and rec_state ----
    def test_rec_state_announces_the_lan_endpoint(self):
        rs = self.state()
        self.assertEqual((rs.state_name, rs.ip, rs.port, rs.token), ("idle", "0.0.0.0", 0, bytes(16)))
        self.assertEqual(self.ctrl("open"), 0)
        self.dev.advance(20)
        rs = self.state()
        # No AP in the simulator: connect over the current network.
        self.assertEqual(rs.state_name, "wait_client")
        self.assertIn("no_ap", rs.flag_names)
        self.assertEqual((rs.ssid, rs.password, rs.channel), ("", "", 0))
        self.assertEqual((rs.ip, rs.port), ("127.0.0.1", 47100))   # no server: loopback
        self.assertEqual(len(rs.token), 16)
        self.assertNotEqual(rs.token, bytes(16))
        self.assertTrue(self.status().flags & dc.STATUS_FLAGS["recording"])
        # A wrong token or version is refused; the right one is taken once.
        self.assertFalse(self.dev.rec_hello(bytes(16) + b"\x01\x00"))
        self.assertFalse(self.dev.rec_hello(rs.token + b"\x02\x00"))
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(rs.token)))
        self.assertFalse(self.dev.rec_hello(dc.encode_hello(rs.token)))
        self.dev.advance(20)
        rs = self.state()
        self.assertEqual(rs.state_name, "ready")
        self.assertIn("client", rs.flag_names)
        # CLOSE: the endpoint goes down, the credentials go with it.
        self.assertEqual(self.ctrl("close"), 0)
        self.run_ms(400)
        rs = self.state()
        self.assertEqual((rs.state_name, rs.reason_name, rs.token), ("idle", "user", bytes(16)))
        self.assertFalse(self.status().flags & dc.STATUS_FLAGS["recording"])

    def test_rec_state_notifies_authenticated_links_only(self):
        notes = []
        self.dev.subscribe(lambda n, d: n == "notify" and d["char"] == "rec_state" and
                           notes.append(dc.decode_rec_state(d["value"])))
        self.dev.subscribe_char("rec_state", True)
        self.dev.advance(200)
        self.assertEqual([n.state_name for n in notes], ["idle"])   # the current state first
        self.open_session()
        self.dev.advance(200)
        self.assertEqual([n.state_name for n in notes][-2:], ["wait_client", "ready"])
        # A take sends one a second (elapsed_s) besides the changes.
        self.assertEqual(self.ctrl("take-start", take_id=5, mic_mask=0), 0)
        self.run_ms(3100)
        self.assertGreaterEqual(sum(1 for n in notes if n.state_name == "recording"), 3)
        # An unauthenticated link hears nothing (the packet carries the token).
        self.dev.disconnect()
        self.assertTrue(self.dev.connect())
        self.dev.subscribe_char("rec_state", True)
        before = len(notes)
        self.run_ms(1500)
        self.assertEqual(len(notes), before)

    def test_heartbeat_carries_the_current_second(self):
        notes = []
        self.dev.subscribe(lambda n, d: n == "notify" and d["char"] == "rec_state" and notes.append(
            (self.dev.core_now, dc.decode_rec_state(d["value"]))))
        self.dev.subscribe_char("rec_state", True)
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=5, mic_mask=0), 0)
        t0 = self.dev.core_now
        self.run_ms(4100)
        beats = [(t - t0, n.elapsed_s) for t, n in notes if n.state_name == "recording"]
        self.assertGreaterEqual(len(beats), 4)
        self.assertEqual([el for _, el in beats], [dt // 1000 for dt, _ in beats], beats)

    def test_refusals(self):
        att = dc.ATT_ERRORS
        self.assertEqual(self.ctrl("take-start"), 0x88)               # no session
        self.assertEqual(self.ctrl("stop"), 0x88)
        self.assertEqual(self.dev.write_char("rec_ctrl", b"\x09"), 0x84)
        self.assertEqual(self.dev.write_char("rec_ctrl", b"\x01\x00"), 0x0D)
        # A calibration excludes a session, and the other way round.
        self.assertEqual(self.cmd("accel-cal-start"), 0)
        self.assertEqual(self.ctrl("open"), 0x88)
        self.assertEqual(self.cmd("cal-cancel"), 0)
        self.open_session()
        # Refused at the write, where the app sees it (before: queued, refused unseen).
        self.assertEqual(self.cmd("mount-cal-start"), 0x86, att[0x86])
        self.assertEqual(self.snap()["cal"]["kind"], 0, "calibration refused during a session")
        self.assertEqual(self.snap()["cal"]["refused"], 0, "never queued")
        self.assertEqual(self.dev.write_char("ota_ctrl", dc.encode_ota_start(4096, bytes(32))), 0)
        logs = [d["msg"] for _, d in self.names("log")]
        self.assertIn("esp_ota_begin: refused, a recording session is open", logs)
        self.assertFalse(self.snap()["ota_active"])
        bad = dc.encode_rec("take-start", take_id=1)
        self.assertEqual(self.dev.write_char("rec_ctrl", bad[:1] + b"\x07" + bad[2:]), 0x85)
        self.dev.set_battery(percent=12)
        self.dev.advance(600)
        self.assertEqual(self.ctrl("take-start", take_id=1), 0x8A, att[0x8A])
        self.assertIn("battery_low", self.state().flag_names)
        self.dev.set_battery(percent=12, vbus=True)
        self.dev.advance(600)
        self.assertEqual(self.ctrl("take-start", take_id=1), 0)
        self.assertEqual(self.ctrl("take-start", take_id=2), 0x88)    # already recording
        self.assertEqual(self.ctrl("mark", code=3, text="x"), 0)

    def test_one_arbiter_for_calibration_ota_and_a_session(self):
        # app_excl_acquire(): a calibration start that passed the write's check but reaches
        # the control task after OPEN took the device is refused there and counted, so the
        # screen that posted it can say so (before: a log line, the guide collected forever).
        self.assertEqual(self.dev.write_char("command", dc.encode_cmd("accel-cal-start")), 0)
        self.assertEqual(self.ctrl("open"), 0)
        self.dev.advance(20)
        cal = self.snap()["cal"]
        self.assertEqual((cal["kind"], cal["refused"]), (0, 1))
        self.assertEqual(self.snap()["excl"], 3)                       # APP_EXCL_REC
        logs = [d["msg"] for _, d in self.names("log")]
        self.assertIn("calibration refused: a recording session is open", logs)
        # The session has the device until it is closed and its endpoint is down.
        self.assertEqual(self.ctrl("close"), 0)
        self.dev.advance(1000)
        self.assertEqual(self.state().state_name, "idle")
        self.assertEqual(self.snap()["excl"], 0)
        self.assertEqual(self.cmd("accel-cal-start"), 0)
        self.assertEqual(self.snap()["cal"]["kind"], 2)
        # A calibration keeps an update out too, and gives the device back when it ends.
        self.assertEqual(self.dev.write_char("ota_ctrl", dc.encode_ota_start(4096, bytes(32))), 0)
        logs = [d["msg"] for _, d in self.names("log")]
        self.assertIn("esp_ota_begin: refused, a calibration runs", logs)
        self.assertEqual(self.cmd("cal-cancel"), 0)
        self.assertEqual(self.snap()["excl"], 0)
        self.assertEqual(self.ctrl("open"), 0)

    def test_open_waits_for_the_last_endpoint_to_go_down(self):
        # The FSM leaves CLOSING after REC_CLOSE_MS even when the endpoint is not down yet;
        # the ring and the device stay that session's until it is (recorder.c
        # WIFI_STOPPING), and an OPEN meanwhile is refused, not a second AP over the first.
        self.dev.STOP_AP_MS = 8000
        self.open_session()
        self.assertEqual(self.ctrl("close"), 0)
        self.dev.advance(5500)
        self.assertEqual(self.state().state_name, "idle")
        self.assertEqual(self.ctrl("open"), 0x88)
        self.assertEqual(self.snap()["excl"], 3, "still the session's")
        self.assertEqual(self.cmd("accel-cal-start"), 0x86)
        self.dev.advance(3000)
        self.assertEqual(self.snap()["excl"], 0)
        self.assertEqual(self.ctrl("open"), 0)

    def test_a_profile_switch_keeps_fusion_and_the_heading_zero(self):
        # Every RAW take starts and ends with a profile switch. Fusion goes through it (only
        # the decode scale and the software LPF change), so the WALL heading zero stays valid
        # and the reading steady (before: fusion restarted at yaw 0, the yaw read invalid
        # until the next zero).
        self.open_session()
        self.dev.advance(2000)
        self.assertEqual(self.cmd("zero"), 0)
        self.dev.advance(500)
        r0 = self.snap()["reading"]
        self.assertTrue(r0["yaw_valid"])
        seen = []

        def watch(ms):
            for _ in range(ms // 20):
                self.dev.advance(20)
                s = self.snap()
                seen.append((s["fusion"]["ready"], s["reading"]["yaw_valid"],
                             s["reading"]["elevation"], s["reading"]["yaw"]))

        self.assertEqual(self.ctrl("take-start", take_id=4, profile=RAW897, mic_mask=0), 0)
        watch(1500)
        self.assertEqual(self.snap()["rec"]["imu_profile"], RAW897)
        self.assertEqual(self.ctrl("stop"), 0)
        watch(1500)
        self.assertEqual(self.snap()["rec"]["imu_profile"], 0)
        self.assertTrue(all(ready and yaw for ready, yaw, _, _ in seen))
        for _, _, el, yaw in seen:
            self.assertAlmostEqual(el, r0["elevation"], delta=0.3)
            self.assertAlmostEqual(yaw, r0["yaw"], delta=0.3)

    # ---- the stream ----
    def test_take_stream(self):
        self.open_session()
        self.dev.start_scenario(seed=4, drill_s=2.0)
        s = self.take(6000, profile=RAW448, take_id=0xC0FFEE, mic_mask=3, gain_db=6)
        end = self.assert_end(s, "user")
        # seq: 0 .. END without a hole (no drops here)
        self.assertEqual(s.seqs(), list(range(len(s.recs))))
        self.assertEqual((s.scan.missing, s.scan.skipped, end[3]), (0, 0, 0))
        self.assertEqual(s.types()[0], T_SESSION)
        sess = s.session()
        self.assertEqual((sess[0], sess[4], sess[8], sess[9], sess[10]),
                         (0xC0FFEE, RAW448, 448400, 8192, 64))            # take, profile, scales
        self.assertEqual((sess[17], sess[18], sess[20], sess[21], sess[22]), (16000, 2, 3, 6, 256))
        fw = sess[3].split(b"\0")[0].decode()
        self.assertTrue(fw.startswith(self.dev.fw_version), fw)
        tail = json.loads(s.of(T_SESSION)[0][1][SESSION.size:])
        self.assertEqual(set(tail), {"settings", "cal"})
        # EVENT PROFILE (RAW448) right before the first IMU record; the switch
        # reset the chip, so the first run is a resync.
        first_imu = s.types().index(T_IMU)
        ev = s.events()
        self.assertIn((EV["PROFILE"], RAW448), [(c, a) for _, c, a, _ in ev])
        prof_seq = next(q for q, c, _, _ in ev if c == EV["PROFILE"])
        self.assertEqual(prof_seq, s.seqs()[first_imu] - 1)
        imu = s.imu()
        self.assertTrue(imu[0][5] & IMU_DISCONT)
        self.assertFalse(any(r[5] for r in imu[1:]), "one resync only")
        # IMU rate and FIFO losses from the timestamp runs (~12 % lost in read mode)
        frames = sum(r[4] for r in imu)
        lost = sum(b[1] - (a[1] + a[4]) for a, b in zip(imu, imu[1:]))
        self.assertEqual((frames, lost), (end[5], end[6]))
        span_ts = imu[-1][1] + imu[-1][4] - imu[0][1]
        self.assertEqual(span_ts, frames + lost)
        self.assertAlmostEqual(lost / span_ts, 0.12, delta=0.04)
        gaps = sum(1 for a, b in zip(imu, imu[1:]) if b[1] != a[1] + a[4])
        self.assertGreater(gaps, len(imu) * 0.5, "a gap after almost every read")
        rate = (imu[-1][2] - imu[0][2]) / ((imu[-1][3] - imu[0][3]) / 1e6)
        self.assertAlmostEqual(rate, 448.4, delta=448.4 * 0.01)
        # Audio: 256-frame blocks without a gap at 16 kHz
        au = s.audio()
        self.assertTrue(all(a[3] == 256 and a[4] == 0 for a in au))
        self.assertEqual([a[1] for a in au], [256 * i for i in range(len(au))])
        self.assertEqual(end[7], 256 * len(au))
        rate = (au[-1][1] - au[0][1]) / ((au[-1][2] - au[0][2]) / 1e6)
        self.assertAlmostEqual(rate, 16000, delta=16)
        self.assertEqual(len(s.of(T_AUDIO)[0][1]), AUDIO.size + 256 * 4)
        # LEVEL 10 Hz, STATS 1 Hz
        self.assertAlmostEqual(len(s.of(T_LEVEL)), 60, delta=2)
        self.assertAlmostEqual(len(s.of(T_STATS)), 6, delta=1)
        st = STATS.unpack_from(s.of(T_STATS)[-1][1])
        self.assertAlmostEqual(1e9 / st[3], 448.4, delta=2)               # imu_period_ns
        self.assertAlmostEqual(st[6] / 1000, 16000, delta=5)              # audio_rate_mhz
        self.assertEqual(st[9], 512 * 1024)                               # ring size
        # The take ended: LEVEL back, the ring empty, READY for the next one.
        rs = self.state()
        self.assertEqual((rs.state_name, rs.reason_name, rs.take_id, rs.ring_pct),
                         ("ready", "user", 0xC0FFEE, 0))
        self.assertEqual(self.snap()["rec"]["imu_profile"], 0)
        self.assertNotIn(b"breakthrough", s.data, "the ground truth never goes into the stream")

    def test_no_imu_record_before_the_profile_is_in_effect(self):
        # A pass under way at TAKE_START reads with the old profile: the tap
        # leaves it out. The switch restarts TIMESTAMP, which does not count
        # while configure() runs.
        self.open_session()
        self.dev.core.fault_profile_lag(3)
        s = self.take(1000, profile=RAW448_16G, mic_mask=0)
        self.assert_end(s, "user")
        imu = s.imu()
        prof = [q for q, c, _, _ in s.events() if c == EV["PROFILE"]]
        self.assertEqual(len(prof), 1)
        self.assertLess(prof[0], imu[0][0])
        self.assertLess(imu[0][1], 448.4 * 0.04, "TIMESTAMP from 0 when the chip runs again")
        self.assertFalse(any(r[5] & IMU_EPOCH for r in imu))
        for _, p in s.of(T_IMU):
            for i in range(IMU.unpack_from(p)[3]):
                a = struct.unpack_from("<3h", p, IMU.size + 12 * i)
                # 1 g at rest: 2048 counts at +-16 g (LEVEL's +-4 g would give 8192)
                self.assertAlmostEqual(math.hypot(*a) / 2048, 1.0, delta=0.1)

    def test_each_take_counts_from_zero(self):
        self.open_session()
        for take_id in (1, 2):
            s = self.take(600, take_id=take_id, mic_mask=3)
            end = self.assert_end(s, "user")
            au = s.audio()
            self.assertEqual(au[0][1], 0, "AUDIO sample_index starts at 0 in every take")
            self.assertEqual(end[7], 256 * len(au))

    def test_stop_with_an_empty_ring_ends_at_once(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=3, mic_mask=0), 0)
        self.run_ms(500)
        self.assertEqual(self.ctrl("stop"), 0)
        self.assertEqual(self.state().state_name, "ready", "nothing queued: no DRAINING")
        self.buf += self.dev.rec_pop()
        self.assert_end(Stream(self.buf), "user")

    def test_raw897_loses_more(self):
        self.open_session()
        s = self.take(4000, profile=RAW897, mic_mask=0)
        self.assert_end(s, "user")
        imu = s.imu()
        frames = sum(r[4] for r in imu)
        lost = sum(b[1] - (a[1] + a[4]) for a, b in zip(imu, imu[1:]))
        self.assertAlmostEqual(lost / (frames + lost), 0.21, delta=0.05)
        rate = (imu[-1][2] - imu[0][2]) / ((imu[-1][3] - imu[0][3]) / 1e6)
        self.assertAlmostEqual(rate, 896.8, delta=9)
        self.assertEqual(s.of(T_AUDIO), [])
        self.assertEqual(s.session()[18], 0)                               # no audio channels

    def test_level_take_keeps_the_imu_running(self):
        self.open_session()
        epoch = self.snap()["rec"]["imu_epoch"]
        s = self.take(1500, profile=0, mic_mask=dc.REC_MICS["mic_2"])
        self.assert_end(s, "user")
        self.assertEqual(self.snap()["rec"]["imu_epoch"], epoch, "no reset for LEVEL")
        self.assertFalse(any(r[5] & IMU_EPOCH for r in s.imu()))
        au = s.audio()
        self.assertEqual(len(s.of(T_AUDIO)[0][1]), AUDIO.size + 256 * 2)  # one channel
        self.assertTrue(au)

    def test_ring_overflow_is_counted(self):
        self.dev.rec_ring_size = 64 * 1024
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=9), 0)
        self.run_ms(500)
        self.run_ms(2000, pop=False)                       # the link stalls
        self.assertGreater(self.state().dropped, 0)
        self.run_ms(500)
        self.assertEqual(self.ctrl("stop"), 0)
        self.run_ms(500)
        s = Stream(self.buf)
        end = self.assert_end(s, "user")
        self.assertGreater(s.scan.missing, 10)
        self.assertEqual(s.scan.missing, end[3], "every seq jump is a counted drop")
        self.assertEqual(self.snap()["rec"]["high_water"] > 60 * 1024, True)

    def test_events(self):
        self.open_session()
        self.dev.physics.vibration_g = 0.4                # the drill runs
        # A LEVEL take: the simulated chip has no LPF by default, so the
        # vibration detector sees these 147/233 Hz tones; under a RAW profile
        # the sensor task's LEVEL-LPF copy (lpf.h) takes them out, as the
        # chip's LPF would on the board.
        self.assertEqual(self.ctrl("take-start", take_id=3, mic_mask=0, profile=0), 0)
        self.run_ms(500)
        self.assertTrue(self.dev.touch())
        self.assertEqual(self.ctrl("mark", code=7, text="hole 1 – oak"), 0)
        self.run_ms(2500)
        self.dev.physics.vibration_g = 0.0
        self.run_ms(3000)
        self.dev.disconnect()
        self.run_ms(500)
        self.connect()
        self.run_ms(200)
        self.assertEqual(self.ctrl("stop"), 0)
        self.run_ms(300)
        s = Stream(self.buf)
        self.assert_end(s, "user")
        got = [(c, a, t) for _, c, a, t in s.events()]
        for want in ((EV["TOUCH"], 0, ""), (EV["MARK"], 7, "hole 1 – oak"), (EV["BLE_LOST"], 0, ""),
                     (EV["BLE_BACK"], 0, ""), (EV["VIB_START"], 0, ""), (EV["VIB_STOP"], 0, "")):
            self.assertIn(want, got)
        self.assertFalse(self.dev.touch(), "no take: a touch is only activity")

    # ---- how a take ends ----
    def test_end_max_s(self):
        self.open_session()
        s = self.take(3000, stop=False, max_s=2, mic_mask=0)
        end = self.assert_end(s, "max_s")
        self.assertAlmostEqual((end[0] - s.session()[1]) / 1e6, 2.0, delta=0.15)

    def test_end_client_lost(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=4, mic_mask=3), 0)
        self.run_ms(1000)
        self.dev.rec_client_lost()
        after = self.dev.rec_pop()
        self.run_ms(500)
        self.assertEqual(after + self.dev.rec_pop(), b"", "the ring is reset, nothing follows")
        s = Stream(self.buf)
        self.assertIsNone(s.end(), "a lost client gets no END")
        rs = self.state()
        self.assertEqual((rs.state_name, rs.reason_name), ("wait_client", "client_lost"))
        self.assertNotIn("client", rs.flag_names)
        self.assertFalse(self.snap()["rec"]["audio_on"])
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(rs.token)), "the same session goes on")

    def test_end_ble_grace(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=5, mic_mask=0), 0)
        self.run_ms(500)
        self.dev.disconnect()
        self.run_ms(119_000)
        self.assertEqual(self.snap()["rec"]["state"], STATE["recording"], "the take survives")
        self.run_ms(1500)
        s = Stream(self.buf)
        self.assert_end(s, "ble_lost")
        # The session closed with it: nothing holds the idle device awake any more.
        self.assertEqual(self.dev.state, "sleep")

    def test_end_low_battery(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=6, mic_mask=0), 0)
        self.run_ms(500)
        self.dev.set_battery(percent=7)
        self.run_ms(1000)
        self.assert_end(Stream(self.buf), "battery")
        self.assertEqual(self.state().state_name, "ready")

    def test_end_imu_lost(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=7, mic_mask=3), 0)
        self.run_ms(500)
        self.dev.imu_present = False
        self.run_ms(4000)
        self.assertEqual(self.snap()["rec"]["state"], STATE["recording"])
        self.assertIn("imu_lost", self.state().flag_names)
        self.run_ms(1500)
        s = Stream(self.buf)
        self.assert_end(s, "imu")
        self.assertIn(EV["IMU_LOST"], [c for _, c, _, _ in s.events()])

    def test_imu_back_restarts_the_clock(self):
        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=8, mic_mask=0), 0)
        self.run_ms(500)
        self.dev.imu_present = False
        self.run_ms(500)
        self.dev.imu_present = True
        self.run_ms(3000)                                 # IMU_RETRY_MS, then data again
        self.assertEqual(self.ctrl("stop"), 0)
        self.run_ms(300)
        s = Stream(self.buf)
        codes = [c for _, c, _, _ in s.events()]
        self.assertIn(EV["IMU_BACK"], codes)
        self.assertEqual(codes.count(EV["PROFILE"]), 2, "PROFILE again after the re-init")
        epoch = [r for r in s.imu() if r[5] & IMU_EPOCH]
        self.assertEqual(len(epoch), 1)
        self.assertTrue(epoch[0][5] & IMU_DISCONT)

    def test_a_detector_reset_stops_the_motor_events(self):
        # A reset of the vibration detector while the motor runs (the IMU lost; a profile
        # switch keeps the detector) is a VIB_STOP, and the QUIET it decides after a reset is
        # none: START and STOP alternate.
        self.open_session()
        self.dev.start_scenario(seed=3, holes=1, free_s=3.0, drill_s=3.0, lead_s=0)
        self.run_ms(1500)
        self.buf = bytearray()
        # LEVEL, so the detector sees the motor (test_events).
        self.assertEqual(self.ctrl("take-start", take_id=10, mic_mask=0, profile=0), 0)
        self.run_ms(2000)
        self.dev.imu_present = False
        self.run_ms(1500)
        self.dev.imu_present = True
        self.run_ms(4000)
        self.assertEqual(self.ctrl("stop"), 0)
        self.run_ms(300)
        codes = [c for _, c, _, _ in Stream(self.buf).events()]
        vib = [c for c in codes if c in (EV["VIB_START"], EV["VIB_STOP"])]
        self.assertGreaterEqual(vib.count(EV["VIB_START"]), 2)
        self.assertTrue(all(a != b for a, b in zip(vib, vib[1:])), vib)
        lost = codes.index(EV["IMU_LOST"])
        self.assertEqual(codes[lost - 1], EV["VIB_STOP"], "the running motor stops with the IMU")

    def test_a_fault_does_not_outlive_its_device(self):
        # sim_fault_profile_lag() is gone with the boot (sim_init), as the busy-command fault is:
        # a device made later in the same process takes a profile at its first pass.
        self.dev.core.fault_profile_lag(50)
        with tempfile.TemporaryDirectory() as tmp:
            dev = DbSimDevice(tmp, seed=4)
            dev.advance(3000)
            self.assertTrue(dev.connect())
            dev.encryption_changed(True, True)
            self.assertEqual(dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
            dev.advance(20)
            dev.rec_hello(dc.encode_hello(dc.decode_rec_state(dev.read_char("rec_state")).token))
            dev.advance(20)
            self.assertEqual(dev.write_char("rec_ctrl", dc.encode_rec("take-start", profile=RAW897,
                                                                     mic_mask=0)), 0)
            dev.advance(40)
            self.assertEqual(dev.snapshot()["rec"]["imu_profile"], RAW897)

    def test_end_shutdown(self):
        self.open_session()
        self.dev.advance(2000)                            # the PWR key counts after 2 s
        self.assertEqual(self.ctrl("take-start", take_id=8, mic_mask=3), 0)
        self.run_ms(1000)
        self.dev.pwrkey(False)
        self.run_ms(400)
        self.assertEqual(self.dev.state, "sleep")
        self.buf += self.dev.rec_pop()                   # still goes out after the halt
        self.assert_end(Stream(self.buf), "shutdown")

    # ---- the ground truth ----
    def test_the_panel_push_does_not_rebuild_the_truth(self):
        # snapshot() (every panel push, 15 Hz) summarises the running take's truth: each scenario's
        # timeline is built once, again only when the scenario is stopped
        calls = []
        orig = DrillScenario.truth

        def counted(scn, until=None):
            calls.append(until)
            return orig(scn, until)

        self.open_session()
        self.assertEqual(self.ctrl("take-start", take_id=11, mic_mask=0), 0)
        DrillScenario.truth = counted
        try:
            self.dev.start_scenario(seed=4, holes=1)
            self.run_ms(500)
            self.dev.start_scenario(seed=5, holes=1)       # stops the first one
            self.snap()
            built = len(calls)                              # the first as stopped, the second
            for _ in range(20):
                self.run_ms(100)
                summary = self.snap()["recorder"]["truth"]
        finally:
            DrillScenario.truth = orig
        self.assertEqual((built, len(calls)), (2, 2), calls)
        self.assertTrue(summary["running"])
        self.assertIn("motor_on", summary["events"])

    def test_ground_truth_file_and_scenarios(self):
        self.open_session()
        scn = self.dev.start_scenario(seed=11, mode="hammer", holes=2, outcome="mixed",
                                      stuck=True, kickback=True, drill_s=1.0)
        s = self.take(int(scn.duration * 1000) + 500, stop=True, take_id=42, profile=2)
        self.assert_end(s, "user")
        path = self.state_dir / "rec_truth_42.json"
        truth = json.loads(path.read_text())
        self.assertEqual((truth["take_id"], truth["end_reason"], truth["running"]), (42, "user", False))
        kinds = [e["type"] for e in truth["events"]]
        self.assertEqual(kinds, ["motor_on", "contact", "kickback", "motor_off", "motor_on", "retract",
                                 "motor_off", "motor_on", "contact", "retract", "motor_off"])
        self.assertEqual([(sp["type"], sp["hole"]) for sp in truth["spans"]], [("stuck", 1)])
        self.assertEqual([h["through"] for h in truth["holes"]], [False, False])
        self.assertIn("hammer", {sg["state"] for sg in truth["segments"]})
        sess = s.session()
        self.assertTrue(all(sess[1] <= e["t_us"] <= s.end()[0] for e in truth["events"]))
        self.assertEqual(self.dev.rec_truth()["take_id"], 42)
        # Hammer blows clip a +-4 g take; RAW448_16G (profile 2) keeps them.
        az = [struct.unpack_from("<6h", p, IMU.size + 12 * i)[2]
              for _, p in s.of(T_IMU) for i in range(IMU.unpack_from(p)[3])]
        self.assertGreater(max(abs(v) for v in az), 2 * 2048, "blows of 5-10 g")
        self.assertLess(max(abs(v) for v in az), 32767)

    def test_truth_of_a_stopped_or_replaced_scenario(self):
        self.open_session()
        scn = self.dev.start_scenario(seed=21, drill_s=2.0)
        full = [e["type"] for e in scn.truth()["events"]]
        self.assertEqual(full, ["motor_on", "contact", "breakthrough", "motor_off"])
        t = {e["type"]: e["t"] for e in scn.truth()["events"]}
        self.assertEqual(self.ctrl("take-start", take_id=31, mic_mask=0), 0)
        # Stopped while cutting: the truth ends there, with the motor off in the hole.
        self.run_ms(int((t["contact"] + 0.5) * 1000))
        self.dev.stop_scenario()
        t_stop = self.dev.core_now * 1000
        # Another scenario started while one runs ends the first one too.
        self.run_ms(1000)
        self.dev.start_scenario(seed=22, drill_s=2.0, lead_s=0.1)
        self.run_ms(400)
        self.dev.start_scenario(seed=23, drill_s=2.0)
        self.run_ms(6000)
        self.assertEqual(self.ctrl("stop"), 0)
        self.run_ms(300)
        truth = json.loads((self.state_dir / "rec_truth_31.json").read_text())
        sc = truth["scenarios"]
        self.assertEqual(len(sc), 3)
        self.assertAlmostEqual(sc[0]["stopped_us"], t_stop, delta=20_000)
        self.assertEqual(sc[0]["end_us"], sc[0]["stopped_us"])
        self.assertNotIn("stopped_us", sc[2])                   # ran to its end
        first = [(e["type"], e.get("hole")) for e in truth["events"] if e["scenario"] == 0]
        self.assertEqual(first, [("motor_on", None), ("contact", 1), ("motor_off", 1)])
        self.assertLessEqual(max(e["t_us"] for e in truth["events"] if e["scenario"] == 0),
                             sc[0]["stopped_us"])
        self.assertEqual([h["through"] for h in truth["holes"] if h["scenario"] == 0], [None])
        self.assertTrue(all(sg["t1_us"] <= sc[0]["stopped_us"]
                            for sg in truth["segments"] if sg["scenario"] == 0))
        # 400 ms into the second one: still spinning up, no hole yet.
        second = [e["type"] for e in truth["events"] if e["scenario"] == 1]
        self.assertEqual(second, ["motor_on", "motor_off"])
        self.assertEqual([e["type"] for e in truth["events"] if e["scenario"] == 2], full)

    def reboot(self):
        self.assertEqual(self.cmd("reboot"), 0)
        self.dev.advance(4000)
        self.assertEqual(self.dev.state, "running")
        self.connect()

    def test_physics_stays_on_the_sim_clock(self):
        # A reboot and a motion wake keep the sensor passes on their cadence:
        # the IMU's physics time stays on the sim clock that the microphones
        # and the ground truth use (it fell 20-40 ms behind at every boot).
        self.reboot()
        self.dev.pwrkey(False)
        self.dev.advance(500)
        self.assertEqual(self.dev.state, "sleep")
        self.dev.physics.pick_up()
        self.dev.advance(4000)
        self.assertEqual([d["reason"] for _, d in self.names("boot")][-2:], ["reboot", "motion"])
        lag = self.dev.now_ms - self.dev.physics.t * 1000
        self.assertTrue(0 <= lag < DbSimDevice.PASS_MS + 5, lag)

    @unittest.skipUnless(DATASET_PY.exists(), "tools/dataset/.venv is missing")
    def test_take_exports_validates_and_matches_the_truth(self):
        self.reboot()                   # IMU, audio and truth stay aligned across it
        self.open_session()
        scn = self.dev.start_scenario(seed=21, drill_s=2.0)
        s = self.take(int(scn.duration * 1000) + 300, take_id=77, profile=RAW448, mic_mask=3)
        self.assert_end(s, "user")
        truth = self.dev.rec_truth()
        tb = [e["t_us"] for e in truth["events"] if e["type"] == "breakthrough"]
        self.assertEqual(len(tb), 1)
        f = self.state_dir / "take.dbrec"
        f.write_bytes(s.data)
        r = subprocess.run([str(DATASET_PY), str(PROBE), str(f), str(self.state_dir / "ds"), str(tb[0])],
                           capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertTrue(out["ok"], out["issues"])
        self.assertEqual(out["end_reason"], "user")
        self.assertAlmostEqual(out["odr_measured_hz"], 448.4, delta=448.4 * 0.02)
        self.assertAlmostEqual(out["audio_rate_hz"], 16000, delta=16)
        # The FIFO's read-mode losses become filled rows (flag bit 0, from the
        # timestamp runs); no transport gap (bit 3) in a take without drops.
        self.assertAlmostEqual(out["filled"] / out["imu_samples"], 0.12, delta=0.04)
        self.assertEqual((out["gap_flags"], out["missing"]), (0, 0))
        on = out["onsets"][0]
        self.assertIsNotNone(on["imu"], out)
        self.assertIsNotNone(on["audio"], out)
        self.assertLessEqual(abs(on["imu"] - tb[0]), 5000, out)
        self.assertLessEqual(abs(on["audio"] - tb[0]), 5000, out)


class PanelRecorderTest(DeviceTest):
    def setUp(self):
        super().setUp()
        self.panel = WebPanel(self.dev)
        self.connect()

    def cmd(self, **d):
        return self.panel.command(d)

    def test_commands_are_validated(self):
        for d in ({"cmd": "scenario", "mode": "screw"}, {"cmd": "scenario", "holes": 0},
                  {"cmd": "scenario", "sag": 0.9}, {"cmd": "scenario", "stuck": 1},
                  {"cmd": "scenario", "stuck_exit": "jump"},
                  {"cmd": "scenario", "stop": False}, {"cmd": "mic_gain"},
                  {"cmd": "mic_gain", "db": 60}, {"cmd": "mic_gain", "db": 3, "mic": 3},
                  {"cmd": "rec_stall", "s": 5}, {"cmd": "rec_client_drop"}):
            with self.assertRaises(BadRequest, msg=d):
                self.cmd(**d)
        self.assertIn("hammer", self.cmd(cmd="scenario", mode="hammer", holes=2, seed=5))
        self.assertEqual(self.snap()["recorder"]["scenario"]["params"]["seed"], 5)
        self.cmd(cmd="scenario", stop=True)
        self.assertIsNone(self.snap()["recorder"]["scenario"])
        self.cmd(cmd="mic_gain", db=-6, mic=2)
        self.assertAlmostEqual(self.snap()["rec"]["mic_gain_db"][1], -6, places=2)
        self.cmd(cmd="mic_gain", db=0)
        self.assertEqual(self.snap()["rec"]["mic_gain_db"], [0.0, 0.0])

    def test_session_commands_without_ble(self):
        for d in ({"cmd": "rec", "op": "pause"}, {"cmd": "rec", "op": "take_start", "profile": "RAW"},
                  {"cmd": "rec", "op": "take_start", "mic_mask": -1},
                  {"cmd": "rec", "op": "mark", "text": "x" * 41}, {"cmd": "rec", "op": "mark", "text": 3}):
            with self.assertRaises(BadRequest, msg=d):
                self.cmd(**d)
        with self.assertRaisesRegex(BadRequest, "REC_STATE"):
            self.cmd(cmd="rec", op="take_start")                       # no session
        self.assertIsNone(self.snap()["recorder"]["session"])
        self.assertEqual(self.cmd(cmd="rec", op="open"), "rec_ctrl OPEN")
        self.dev.advance(20)
        ss = self.snap()["recorder"]["session"]
        rs = dc.decode_rec_state(self.dev.read_char("rec_state"))
        self.assertEqual((ss["ip"], ss["port"], ss["token"], ss["no_ap"]),
                         (rs.ip, rs.port, rs.token.hex(), True))
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(bytes.fromhex(ss["token"]))))
        self.dev.advance(20)
        self.assertIn("RAW448_16G", self.cmd(cmd="rec", op="take_start", profile="RAW448_16G",
                                             mic_mask=1, take_id=9))
        self.assertEqual(self.cmd(cmd="rec", op="mark", code=2, text="hole"), "rec_ctrl MARK 2 'hole'")
        self.dev.advance(200)
        self.assertEqual((self.snap()["rec"]["take_id"], self.snap()["rec"]["profile"]), (9, 2))
        self.cmd(cmd="rec", op="stop")
        self.cmd(cmd="rec", op="close")
        self.dev.advance(3000)                                       # nobody drains: the 2 s limit
        self.assertIsNone(self.snap()["recorder"]["session"])

    def test_touches_during_a_take(self):
        dev = self.dev
        self.assertEqual(dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
        dev.advance(20)
        dev.rec_hello(dc.encode_hello(dc.decode_rec_state(dev.read_char("rec_state")).token))
        self.assertEqual(dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=1)), 0)
        dev.advance(100)
        self.assertEqual(self.cmd(cmd="touch", action="press"), "EVENT TOUCH (recording)")
        self.assertEqual(self.cmd(cmd="touch", action="tap"), "ignored: recording (EVENT TOUCH)")
        self.assertFalse(self.snap()["zero_set"])
        self.assertIn("take stopped", self.cmd(cmd="touch", action="button", button="rec_pill"))
        dev.advance(300)
        self.assertEqual(dc.decode_rec_state(dev.read_char("rec_state")).reason_name, "user")
        state = self.panel.state()
        self.assertEqual(state["snap"]["rec"]["take_id"], 1)



class RawProfileLevelTest(DeviceTest):
    """Under a RAW profile (chip LPFs off) the sensor task feeds fusion and
    level through lpf.h's stand-in for the LEVEL profile's LPFs, while the
    recorder's tap keeps the raw frames. A hammer scenario then reads like
    the same scenario under LEVEL with the chip's LPFs modelled
    (sim_chip_lpf): the drift milestone 3 saw without the filter is gone."""

    ELEVATION = 30.0

    def elevations(self, profile, chip_lpf=False):
        """The device's elevation every 100 ms through a seeded hammer
        scenario recorded in profile, the drill held still."""
        self.dev = self.new_device()
        self.dev.core.chip_lpf(chip_lpf)
        self.assertTrue(self.dev.connect())
        self.dev.encryption_changed(True, True)
        self.dev.physics.set_drill(elevation=self.ELEVATION, yaw=0.0, roll=0.0)
        self.settle_imu()
        self.settle(3000)
        ctrl = lambda op, **kw: self.dev.write_char("rec_ctrl", dc.encode_rec(op, **kw))  # noqa: E731
        self.assertEqual(ctrl("open"), 0)
        self.dev.advance(20)
        token = dc.decode_rec_state(self.dev.read_char("rec_state")).token
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(token)))
        self.dev.advance(20)
        self.assertEqual(ctrl("take-start", profile=profile, mic_mask=0, take_id=1), 0)
        # The profile switch (the measured ODR goes through it, imu_rate_restart()), settled.
        self.settle(3000)
        self.assertEqual(self.snap()["rec"]["imu_profile"], profile)
        scn = self.dev.start_scenario(seed=5, mode="hammer", holes=2, lead_s=0)
        out = []
        for _ in range(int(scn.duration * 10) + 5):
            self.dev.advance(100)
            self.dev.rec_pop()
            out.append(self.angle().elevation)
        return out

    def test_hammering_under_raw448_16g_reads_as_under_level(self):
        level = self.elevations(0, chip_lpf=True)
        raw = self.elevations(RAW448_16G)
        unfiltered = self.elevations(0)     # LEVEL without the chip LPFs: what M3 had
        diff = [a - b for a, b in zip(raw, level)]
        rms = math.sqrt(sum(d * d for d in diff) / len(diff))
        mean_err = sum(e - self.ELEVATION for e in raw) / len(raw)
        self.assertLess(max(map(abs, diff)), 1.5, "within a small bound of LEVEL")
        self.assertLess(rms, 0.6)
        self.assertLess(abs(mean_err), 0.6, "no drift")
        self.assertLess(abs(raw[-1] - self.ELEVATION), 0.3, "back on the pose after the motor stops")
        # Without a filter the same blows tilt the reading by degrees.
        self.assertGreater(max(abs(e - self.ELEVATION) for e in unfiltered), 3.0)
        self.assertGreater(sum(e - self.ELEVATION for e in unfiltered) / len(unfiltered), 1.2)

    def test_the_tap_keeps_the_raw_frames(self):
        # The hammer blows reach the recording unfiltered (+-16 g), far above
        # what the filtered level path sees.
        self.dev.core.chip_lpf(False)
        self.connect()
        self.assertEqual(self.dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
        self.dev.advance(20)
        token = dc.decode_rec_state(self.dev.read_char("rec_state")).token
        self.assertTrue(self.dev.rec_hello(dc.encode_hello(token)))
        self.dev.advance(20)
        buf = bytearray()
        self.assertEqual(self.dev.write_char(
            "rec_ctrl", dc.encode_rec("take-start", profile=RAW448_16G, mic_mask=0, take_id=2)), 0)
        self.settle(3000)
        scn = self.dev.start_scenario(seed=5, mode="hammer", holes=1, lead_s=0)
        for _ in range(int(scn.duration * 50)):
            self.dev.advance(20)
            buf += self.dev.rec_pop()
        peak = 0.0
        for _, p in Stream(buf).of(T_IMU):
            for i in range(IMU.unpack_from(p)[3]):
                a = struct.unpack_from("<3h", p, IMU.size + 12 * i)
                peak = max(peak, math.hypot(*a) / 2048)
        self.assertGreater(peak, 4.0, "the blows, unfiltered and unclipped")


class TruthHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dev = DbSimDevice(self._tmp.name, seed=3)
        self.dev.advance(500)
        self.assertTrue(self.dev.connect())
        self.dev.encryption_changed(True, True)

    async def asyncTearDown(self):
        self._tmp.cleanup()

    async def test_truth_over_http(self):
        runner = await web.start(self.dev, None, "127.0.0.1", 0)
        try:
            async with aiohttp.ClientSession() as http:
                async with http.get(runner.url + "/api/rec/truth") as r:
                    self.assertEqual(r.status, 404)
                self.dev.start_scenario(seed=1, drill_s=0.5)
                self.dev.write_char("rec_ctrl", dc.encode_rec("open"))
                self.dev.advance(20)
                rs = dc.decode_rec_state(self.dev.read_char("rec_state"))
                self.dev.rec_hello(dc.encode_hello(rs.token))
                self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=3, mic_mask=0))
                self.dev.advance(3000)
                async with http.get(runner.url + "/api/rec/truth") as r:
                    t = await r.json()
                self.assertEqual((t["take_id"], t["running"]), (3, True))
                self.assertIn("contact", [e["type"] for e in t["events"]])
        finally:
            await runner.close()


class StreamServerTest(unittest.IsolatedAsyncioTestCase):
    """RecorderServer on loopback with db_client's pull as the client."""

    async def asyncSetUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dev = DbSimDevice(self._tmp.name, seed=2)
        self.dev.advance(500)
        self.srv = await R.RecorderServer(self.dev, "127.0.0.1", 0).start()
        self.assertTrue(self.dev.connect())
        self.dev.encryption_changed(True, True)
        self.runner = asyncio.ensure_future(self.dev.run(speed=4.0))

    async def asyncTearDown(self):
        self.dev.stop()
        await self.runner
        await self.srv.stop()
        self._tmp.cleanup()

    def state(self):
        return dc.decode_rec_state(self.dev.read_char("rec_state"))

    async def until(self, cond, timeout=5.0):
        loop = asyncio.get_running_loop()
        end = loop.time() + timeout
        while not cond():
            self.assertLess(loop.time(), end, "timed out")
            await asyncio.sleep(0.01)

    async def open(self):
        self.assertEqual(self.dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
        await self.until(lambda: self.state().state_name == "wait_client")
        return self.state()

    async def test_pull_a_take(self):
        rs = await self.open()
        self.assertIn("no_ap", rs.flag_names)
        self.assertEqual((rs.ip, rs.port, rs.ssid), ("127.0.0.1", self.srv.port, ""))
        # A wrong token: the server closes the connection.
        reader, writer = await asyncio.open_connection(rs.ip, rs.port)
        writer.write(bytes(16) + b"\x01\x00")
        self.assertEqual(await asyncio.wait_for(reader.read(100), 2), b"")
        writer.close()
        self.assertEqual(self.state().state_name, "wait_client")

        async def on_ready():
            await self.until(lambda: self.state().state_name == "ready")
            # A second client is turned away.
            r2, w2 = await asyncio.open_connection(rs.ip, rs.port)
            w2.write(dc.encode_hello(rs.token))
            try:
                self.assertEqual(await asyncio.wait_for(r2.read(100), 2), b"")
            except ConnectionResetError:
                pass                    # closed before it read the HELLO
            w2.close()
            self.assertEqual(self.dev.write_char(
                "rec_ctrl", dc.encode_rec("take-start", take_id=12, max_s=2)), 0)

        out = io.BytesIO()
        scan = await asyncio.wait_for(dc.rec_pull_stream(rs.ip, rs.port, rs.token, out,
                                                         on_ready=on_ready), 20)
        self.assertEqual([r for r, _ in scan.ends], ["max_s"])
        self.assertEqual((scan.takes, scan.missing, scan.skipped), (1, 0, 0))
        s = Stream(out.getvalue())
        self.assertEqual(s.seqs(), list(range(len(s.recs))))
        self.assertGreater(len(s.of(T_AUDIO)), 100)
        # The pull closed the connection: CLIENT_LOST, the session waits again.
        await self.until(lambda: self.state().reason_name == "client_lost")
        self.assertEqual(self.state().state_name, "wait_client")
        self.assertGreater(self.srv.status()["sent_bytes"], 100_000)

    async def test_the_stream_address_is_ipv4(self):
        # rec_state carries the address in 4 bytes: a name is served on its
        # IPv4 socket, an IPv6 address is refused when the server starts
        # (before, OPEN raised in the device's rec_ctrl write).
        with tempfile.TemporaryDirectory() as tmp:
            dev = DbSimDevice(tmp, seed=3)
            dev.advance(500)
            self.assertTrue(dev.connect())
            dev.encryption_changed(True, True)
            srv = await R.RecorderServer(dev, "localhost", 0).start()
            try:
                self.assertEqual(dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
                rs = dc.decode_rec_state(dev.read_char("rec_state"))
                self.assertEqual((rs.state_name, rs.ip, rs.port),
                                 ("wait_client", "127.0.0.1", srv.port))
                reader, writer = await asyncio.open_connection(rs.ip, rs.port)
                writer.write(dc.encode_hello(rs.token))
                await self.until(lambda: dc.decode_rec_state(
                    dev.read_char("rec_state")).state_name == "ready")
                writer.close()
            finally:
                await srv.stop()
            try:
                with socket.socket(socket.AF_INET6) as s:
                    s.bind(("::1", 0))
            except OSError:
                self.skipTest("no IPv6 loopback")
            with self.assertRaisesRegex(ValueError, "IPv4"):
                await R.RecorderServer(dev, "::1", 0).start()
            self.assertIsNone(dev.rec_endpoint)

    async def test_pull_without_ble(self):
        # The recipe for a run with --no-ble: the panel opens the session and
        # starts the take, db_client pulls with the endpoint and token from
        # /api/state.
        panel = WebPanel(self.dev)
        panel.command({"cmd": "rec", "op": "open"})
        ss = panel.state()["snap"]["recorder"]["session"]
        out = Path(self._tmp.name) / "take.dbrec"
        proc = await asyncio.create_subprocess_exec(
            sys.executable, str(SIM_DIR.parent / "ble" / "db_client.py"), "rec", "pull",
            "--host", ss["ip"], "--port", str(ss["port"]), "--token", ss["token"], "-o", str(out),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        await self.until(lambda: self.state().state_name == "ready", timeout=10)
        panel.command({"cmd": "rec", "op": "take_start", "max_s": 1, "take_id": 17})
        o, e = await asyncio.wait_for(proc.communicate(), 15)
        self.assertEqual(proc.returncode, 0, e.decode())
        self.assertIn("END max_s", o.decode())
        s = Stream(out.read_bytes())
        self.assertEqual(s.session()[0], 17)
        self.assertEqual(dc.REC_REASONS[s.end()[1]], "max_s")

    async def test_drop_and_stall_lose_the_client(self):
        rs = await self.open()
        reader, writer = await asyncio.open_connection(rs.ip, rs.port)
        writer.write(dc.encode_hello(rs.token))
        await self.until(lambda: self.state().state_name == "ready")
        # The device took this connection: HELLO_OK first (rec_wifi.c welcome()).
        self.assertEqual(await asyncio.wait_for(reader.readexactly(len(dc.REC_HELLO_OK)), 2), dc.REC_HELLO_OK)
        self.assertTrue(self.srv.drop_client())
        self.assertEqual(await asyncio.wait_for(reader.read(100), 2), b"")
        await self.until(lambda: self.state().reason_name == "client_lost")
        writer.close()
        # A stall of SEND_STALL_S or more is a lost client too (shortened here).
        reader, writer = await asyncio.open_connection(rs.ip, rs.port)
        writer.write(dc.encode_hello(rs.token))
        await self.until(lambda: self.state().state_name == "ready")
        self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=13))
        saved = R.SEND_STALL_S
        R.SEND_STALL_S = 0.3
        try:
            self.srv.stall(5.0)
            await self.until(lambda: self.state().state_name == "wait_client")
        finally:
            R.SEND_STALL_S = saved
        self.assertEqual(self.state().reason_name, "client_lost")
        writer.close()

    async def manual_client(self):
        """The device's clock stops running by itself (the test moves it, tick()),
        with a client connected and a take running."""
        self.dev.stop()
        await self.runner
        self.assertEqual(self.dev.write_char("rec_ctrl", dc.encode_rec("open")), 0)
        self.dev.advance(20)
        rs = self.state()
        reader, writer = await asyncio.open_connection(rs.ip, rs.port)
        writer.write(dc.encode_hello(rs.token))
        for _ in range(200):
            if self.state().state_name == "ready":
                break
            await self.tick(20)
        self.assertEqual(self.state().state_name, "ready")
        self.assertEqual(self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=18, mic_mask=0)), 0)
        await self.tick(200)
        return reader, writer

    async def tick(self, ms, step=100):
        """Moves the device's clock by ms in steps, letting the server run between them."""
        step = min(step, ms)
        for _ in range(int(ms // step)):
            self.dev.advance(step)
            await asyncio.sleep(0.003)

    async def test_a_stall_runs_on_the_device_clock(self):
        # SEND_STALL_S is the board's send timeout: device seconds, which dev.run(speed) runs at
        # speed x the wall clock. Frozen device time loses no client however long it stalls...
        _, writer = await self.manual_client()
        saved = R.SEND_STALL_S
        R.SEND_STALL_S = 0.2
        try:
            self.srv.stall(30.0)
            await asyncio.sleep(0.6)
            self.assertEqual(self.state().state_name, "recording")
        finally:
            R.SEND_STALL_S = saved
        # ... and SEND_STALL_S of device time loses it however fast that passes.
        await self.tick(R.SEND_STALL_S * 1000 + 500, step=250)
        await self.until(lambda: self.state().state_name == "wait_client", timeout=1.0)
        self.assertEqual(self.state().reason_name, "client_lost")
        writer.close()

    async def test_a_stall_of_exactly_send_stall_s_loses_the_client(self):
        # firmware_sim.md / the panel: a stall of SEND_STALL_S or more loses the client, whether
        # or not a poll of the send loop lands inside it
        _, writer = await self.manual_client()
        saved = R.SEND_STALL_S
        R.SEND_STALL_S = 1.0
        try:
            self.srv.stall(0.9)
            await self.tick(1500, step=300)
            await asyncio.sleep(0.05)
            self.assertEqual(self.state().state_name, "recording", "a shorter stall keeps it")
            self.srv.stall(1.0)
            await self.tick(1500, step=300)
            await self.until(lambda: self.state().state_name == "wait_client", timeout=1.0)
        finally:
            R.SEND_STALL_S = saved
        self.assertEqual(self.state().reason_name, "client_lost")
        writer.close()

    async def test_a_client_that_stops_reading(self):
        # It backs the stream up into the ring once its receive buffer and
        # the board's TCP_SND_BUF are full, is lost after SEND_STALL_S, and
        # its connection goes: stop() does not wait for it, and it gets no
        # more than what was in the socket buffers when it was lost.
        loop = asyncio.get_running_loop()
        rs = await self.open()
        with socket.socket() as sock:                  # no asyncio reader buffering for it
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            sock.setblocking(False)
            await loop.sock_connect(sock, (rs.ip, rs.port))
            await loop.sock_sendall(sock, dc.encode_hello(rs.token))
            await self.until(lambda: self.state().state_name == "ready")
            saved = R.SEND_STALL_S
            R.SEND_STALL_S = 1.0
            try:
                self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=15, mic_mask=3))
                await self.until(lambda: self.state().ring_pct >= 5)   # backed up
                self.assertLess(self.srv.sent_bytes, R.TCP_SND_BUF + 16_000)
                await self.until(lambda: self.state().state_name == "wait_client", timeout=10)
            finally:
                R.SEND_STALL_S = saved
            self.assertEqual(self.state().reason_name, "client_lost")
            held = self.srv.sent_bytes
            self.assertLess(held, R.TCP_SND_BUF + 16_000)
            await asyncio.wait_for(self.srv.stop(), 2)
            got = 0
            try:
                while data := await asyncio.wait_for(loop.sock_recv(sock, 65536), 2):
                    got += len(data)
            except ConnectionResetError:
                pass
            self.assertLessEqual(got, held)

    async def test_shutdown_sends_the_backlog_before_the_endpoint_goes(self):
        # SHUTDOWN queues END and STOP_AP at once: the 200 ms drain sends it.
        rs = await self.open()
        await self.until(lambda: self.dev.core_now > 2500)   # the PWR key counts after 2 s
        out = io.BytesIO()

        async def on_ready():
            await self.until(lambda: self.state().state_name == "ready")
            self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=16, mic_mask=3))
            await asyncio.sleep(0.3)
            self.dev.pwrkey(False)

        scan = await asyncio.wait_for(dc.rec_pull_stream(rs.ip, rs.port, rs.token, out,
                                                         on_ready=on_ready, takes=0), 10)
        self.assertEqual([r for r, _ in scan.ends], ["shutdown"])
        self.assertEqual(self.dev.state, "sleep")

    async def test_close_ends_the_stream_and_waits_for_the_client(self):
        # rec_wifi.c finish_stream(): after the drain the stream ends with a FIN and the
        # endpoint only goes down once the client has read up to it and closed (on the board,
        # esp_wifi_stop() would drop what lwIP still holds, END among it), or after LINGER_S.
        _, writer = await self.manual_client()
        downs = []
        self.dev.subscribe(lambda n, d: n == "rec" and d.get("event") == "endpoint"
                           and not d["up"] and downs.append(self.dev.core_now))
        t0 = self.dev.core_now
        self.assertEqual(self.dev.write_char("rec_ctrl", dc.encode_rec("close")), 0)
        # A client that reads to EOF but keeps its side open: LINGER_S of device time.
        await self.tick(R.LINGER_S * 1000 - 200, step=100)
        self.assertEqual(downs, [], "the endpoint waits for the client")
        await self.tick(800, step=100)
        await self.until(lambda: downs, timeout=2.0)
        self.assertGreaterEqual(downs[0] - t0, R.LINGER_S * 1000)
        writer.close()

    async def test_close_drains_and_takes_the_endpoint_down(self):
        rs = await self.open()
        out = io.BytesIO()
        seen = []
        self.dev.subscribe(lambda n, d: n == "rec" and seen.append(d["event"]))

        async def on_ready():
            await self.until(lambda: self.state().state_name == "ready")
            self.dev.write_char("rec_ctrl", dc.encode_rec("take-start", take_id=14, mic_mask=0))
            await asyncio.sleep(0.5)
            self.dev.write_char("rec_ctrl", dc.encode_rec("close"))

        scan = await asyncio.wait_for(dc.rec_pull_stream(rs.ip, rs.port, rs.token, out,
                                                         on_ready=on_ready, takes=0), 10)
        self.assertEqual([r for r, _ in scan.ends], ["user"])
        await self.until(lambda: self.state().state_name == "idle")
        self.assertEqual(self.state().reason_name, "user")
        self.assertNotIn("client_lost", seen, "a closing session does not lose its client")


class ScenarioMotionTest(unittest.TestCase):
    def test_breakthrough_wobble_swings_back(self):
        # The gyro reads the rotation between two samples: a wobble the pose
        # never makes must integrate to nothing, or fusion tilts at every hole.
        scn = DrillScenario(seed=21, drill_s=2.0)
        tb = next(e["t"] for e in scn.truth()["events"] if e["type"] == "breakthrough")
        dt, axis = 1 / 448.4, (0.0, 0.0, -1.0)
        ang, peak = [0.0, 0.0, 0.0], 0.0
        for i in range(round(0.45 / dt)):
            _, g = scn.vibration(tb - 0.05 + i * dt, dt, axis)
            ang = [a + w * dt for a, w in zip(ang, g)]
            peak = max(peak, math.hypot(*g))
        self.assertGreater(peak, 1.2, "the wobble is there (rad/s)")
        self.assertLess(math.hypot(*ang), 0.005, "and ends where it started (rad)")


class RecorderUnavailableTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_stream_server_that_cannot_start(self):
        # python -m dbsim runs on without its recorder (the port in use, an IPv6 address): a
        # warning, and every OPEN fails as AP_FAIL instead of the simulator exiting
        with tempfile.TemporaryDirectory() as tmp, socket.socket() as busy:
            busy.bind(("127.0.0.1", 0))
            busy.listen()
            dev = DbSimDevice(tmp, seed=5)
            dev.advance(500)
            self.assertTrue(dev.connect())
            dev.encryption_changed(True, True)
            for host, port in (("127.0.0.1", busy.getsockname()[1]), ("::1", 0)):
                args = SimpleNamespace(rec_host=host, rec_port=port, rec_ap=False, rec_ap_ifname=None)
                err = io.StringIO()
                with contextlib.redirect_stderr(err):
                    self.assertIsNone(await start_recorder(dev, args))
                self.assertIn("recorder unavailable", err.getvalue())
            self.assertIsNone(dev.rec_endpoint)
            self.assertIn("::1", dev.snapshot()["recorder"]["unavailable"])
            dev.write_char("rec_ctrl", dc.encode_rec("open"))
            dev.advance(300)
            rs = dc.decode_rec_state(dev.read_char("rec_state"))
            self.assertEqual((rs.state_name, rs.reason_name), ("idle", "ap_fail"))
            panel = WebPanel(dev)
            with self.assertRaisesRegex(BadRequest, "unavailable"):
                panel.command({"cmd": "rec_stall", "s": 5})


class LanAddressTest(unittest.TestCase):
    def test_lan_address_and_credentials(self):
        ip = R.lan_address()
        self.assertEqual(len(ip.split(".")), 4)
        ssid, pw = R.ap_credentials("3C:0A:F3:D9:F2:A4")
        self.assertRegex(ssid, r"^DB-F2A4-[0-9A-F]{4}$")
        self.assertEqual(len(pw), 20)
        self.assertLessEqual(len(ssid), 16)


if __name__ == "__main__":
    unittest.main()
