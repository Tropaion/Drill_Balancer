"""Small synthetic recordings for the tests (dbdata.synth in milestone 3 makes realistic ones).

Two holes by default: a through hole (contact -> breakthrough) and a blind
one (contact -> retract), on a 7 s take at 448 Hz with 16 kHz stereo audio
whose clock runs slightly fast against the device clock.
"""

import copy
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from dbdata import io as dio  # noqa: E402

T0 = 1_203_345_000
RID = "20261026T143012Z_k7q2_9f3a"
SESSION = "20261026T1428_k7q2"
ODR_NOMINAL = 448.4
AUDIO_RATE = 16000

# (type, seconds after T0, hole)
EVENTS = [("motor_on", 1.0, None), ("contact", 1.6, 1), ("breakthrough", 3.0, 1), ("motor_off", 3.6, None),
          ("motor_on", 4.2, None), ("contact", 4.8, 2), ("retract", 5.6, 2), ("motor_off", 6.0, None)]


def us(s: float) -> int:
    return T0 + int(round(s * 1e6))


def events_doc(events=EVENTS, coverage=None, rid=RID, spans=()) -> dict:
    evs = []
    for i, (typ, s, hole) in enumerate(events):
        e = {"id": f"e{i + 1}", "type": typ, "t_us": us(s), "source": "auto", "confidence": 0.9}
        if hole is not None:
            e["hole"] = hole
        evs.append(e)
    holes = sorted({h for _, _, h in events if h is not None})
    return {
        "schema": "drillbalancer.events/1", "taxonomy_version": 1, "recording_id": rid, "revision": 1,
        "coverage_us": list(coverage) if coverage else [T0, us(7.0)],
        "events": evs,
        "spans": [dict(s) for s in spans],
        "holes": [{"hole": h, "through": h == 1, "material": None, "outcome_tags": ["clean"],
                   "off_angle_deg": None, "off_angle_source": "computed"} for h in holes],
    }


def motor_on_mask(t_us, events):
    on = np.zeros(len(t_us), dtype=bool)
    start = None
    for typ, s, _ in events:
        if typ == "motor_on":
            start = us(s)
        elif typ == "motor_off" and start is not None:
            on |= (t_us >= start) & (t_us < us(s))
            start = None
    return on


def build(*, rid=RID, session_id=SESSION, duration_s=7.0, odr_true=448.0, odr_nominal=ODR_NOMINAL,
          audio=True, audio_rate_true=16000.9, audio_offset_us=-1500, filled=(), gap=None,
          impulse_s=None, events=EVENTS, spans=(), elevation_deg=0.4, level_mode="wall",
          target_deg=0.0, tol_deg=0.5, material="wood/softwood", mode="drill",
          bit_type="brad_point", review="reviewed", seed=1, start_utc=None) -> dict:
    """The parts of one recording: meta, events, imu, level, audio, audio_sync.

    filled: [(t0_s, t1_s, fraction)] marks samples as interpolated; gap:
    (t0_s, t1_s) drops samples (a transport gap); impulse_s puts a spike in
    IMU az and both audio channels at that device time.
    """
    rng = np.random.default_rng(seed)
    n = int(duration_s * odr_true)
    i = np.arange(n)
    t = T0 + np.round(i * 1e6 / odr_true).astype(np.int64)
    seq = 1000 + i
    on = motor_on_mask(t, events)
    ax = rng.normal(0, 20, n) + on * 2000 * np.sin(2 * np.pi * 200 * (t - T0) / 1e6)
    ay = 8192 + rng.normal(0, 20, n)
    az = rng.normal(0, 20, n)
    gyro = rng.normal(0, 3, (n, 3))
    if impulse_s is not None:
        az[np.argmin(np.abs(t - us(impulse_s)))] += 20000
    counts = np.clip(np.round(np.column_stack([ax, ay, az, gyro])), -32768, 32767).astype(np.int64)
    flags = np.zeros(n, dtype=np.int64)
    for f0, f1, frac in filled:
        sel = (t >= us(f0)) & (t < us(f1)) & ((i % 20) < int(round(frac * 20)))
        flags[sel] |= dio.FLAG_FILLED
    transport_gaps = []
    keep = np.ones(n, dtype=bool)
    if gap is not None:
        keep = ~((t >= us(gap[0])) & (t < us(gap[1])))
        removed = np.nonzero(~keep)[0]
        before, after = removed[0] - 1, removed[-1] + 1
        flags[after] |= dio.FLAG_GAP_BEFORE
        transport_gaps = [[int(t[before]), int(t[after])]]
    imu = pd.DataFrame({"seq": seq, "t_us": t, "ax": counts[:, 0], "ay": counts[:, 1], "az": counts[:, 2],
                        "gx": counts[:, 3], "gy": counts[:, 4], "gz": counts[:, 5], "flags": flags})[keep]
    imu = imu.reset_index(drop=True)
    ts, sq = imu["t_us"].to_numpy(), imu["seq"].to_numpy()
    odr_meas = (sq[-1] - sq[0]) / ((ts[-1] - ts[0]) / 1e6)

    lt = T0 + 50_000 + np.arange(int(duration_s * 10)) * 100_000
    elev = np.round(elevation_deg * 100 + rng.normal(0, 2, len(lt))).astype(np.int64)
    dev = elev - int(round(target_deg * 100)) if level_mode != "floor" else np.abs(elev + 9000)
    level = pd.DataFrame({"t_us": lt, "mode": dio.LEVEL_MODES.index(level_mode), "flags": 0x40,
                          "elevation_cdeg": elev, "yaw_cdeg": 0, "bubble_x_cdeg": 0, "bubble_y_cdeg": 0,
                          "deviation_cdeg": dev, "zero_elevation_cdeg": 0, "zero_bubble_x_cdeg": 0,
                          "zero_bubble_y_cdeg": 0})

    parts = {"imu": imu, "level": level, "audio": None, "audio_sync": None}
    audio_meta = sync_meta = None
    if audio:
        b = 1e6 / audio_rate_true
        m = int((duration_s * 1e6 - audio_offset_us) / b)
        at = T0 + audio_offset_us + np.arange(m) * b
        sig = rng.normal(0, 30, m) + motor_on_mask(at.astype(np.int64), events) * 3000 * np.sin(
            2 * np.pi * 400 * (at - T0) / 1e6)
        if impulse_s is not None:
            sig[int(np.argmin(np.abs(at - us(impulse_s))))] += 25000
        left = np.clip(np.round(sig), -32768, 32767).astype(np.int16)
        right = np.roll((left * 0.8).astype(np.int16), 1)
        parts["audio"] = np.column_stack([left, right])
        frames = np.arange(256, m + 1, 256)
        st = np.round(T0 + audio_offset_us + frames * b + rng.normal(0, 40, len(frames))).astype(np.int64)
        parts["audio_sync"] = pd.DataFrame({"frame": frames, "t_us": st})
        fit = dio.SyncFit.fit(frames, st)
        audio_meta = {"codec": "flac", "file": "audio.flac", "rate_nominal_hz": AUDIO_RATE,
                      "rate_measured_hz": round(fit.rate_hz, 2), "bits": 16, "channels": ["mic1", "mic2"],
                      "adc": "ES7210", "gain_db": 6, "muted_spans_us": [], "clipped_frac": 0.0,
                      "block_frames": 256}
        sync_meta = {"method": "esp_timer@dma_block", "fit": "t_us = a + b*frame", "a_us": round(fit.a_us, 1),
                     "b_us_per_frame": round(fit.b_us_per_frame, 6),
                     "residual_rms_us": round(fit.residual_rms_us, 1)}

    meta = {
        "schema": "drillbalancer.meta/1", "taxonomy_version": 1, "recording_id": rid, "session_id": session_id,
        "start_utc": start_utc or dio.parse_recording_id(rid).strftime("%Y-%m-%dT%H:%M:%S.418Z"),
        "device_t0_us": int(T0), "duration_s": round((ts[-1] - ts[0]) / 1e6, 3),
        "device": {"board": "waveshare-esp32-s3-touch-amoled-1.75", "fw_version": "0.3.0", "fw_git": "0e5105e",
                   "board_id": "b3c9e1a2"},
        "app": {"version": "0.3.0", "phone_model": "Pixel 8", "android_sdk": 35},
        "imu": {"chip": "QMI8658", "profile": "RAW448", "odr_nominal_hz": odr_nominal,
                "odr_measured_hz": round(float(odr_meas), 2), "accel_range_g": 4, "gyro_range_dps": 512,
                "accel_lsb_per_g": 8192, "gyro_lsb_per_dps": 64, "accel_lpf": "off", "gyro_lpf": "off",
                "frame": "board: X right, Y up the screen, Z out of the screen; bit along -Z",
                "axis_map": "x=+cy,y=-cx,z=+cz",
                "accel_cal": {"valid": True, "bias_g": [0.004, -0.011, 0.020], "scale": [1.002, 0.998, 1.004]},
                "mount": {"valid": True, "drill_axis": [0.012, -0.020, -0.9997]},
                "gyro_bias_rad_s": [0.0011, -0.0004, 0.0002]},
        "audio": audio_meta, "sync": sync_meta,
        "gaps": {"imu_filled_samples": int(((imu["flags"] & dio.FLAG_FILLED) != 0).sum()),
                 "imu_missing_samples": int(sq[-1] - sq[0] + 1 - len(sq)),
                 "transport_gaps": transport_gaps, "audio_missing_frames": 0},
        "level": {"mode": level_mode, "target_deg": target_deg, "tol_deg": tol_deg, "near_deg": 2.0},
        "session": {"material": material, "layers": [{"material": material, "thickness_mm": 38}],
                    "mode": mode, "bit": {"type": bit_type, "diameter_mm": 8},
                    "drill": {"model": "Bosch GSR 18V-55", "gear": 2, "speed_setting": None}, "notes": ""},
        "derived": {"orientation": "unknown", "off_angle_deg": None, "tags": []},
        "review": {"status": review, "detector": "dbauto/1.0.0", "revision": 1, "edited_events": 0,
                   "reviewer": "k7q2"},
        "quality": {"flags": []},
        "files": {},
    }
    parts["meta"] = meta
    parts["events"] = events_doc(events, [int(ts[0]), int(ts[-1]) + 1], rid, spans)
    return parts


def write(dest, parts, derive=True) -> Path:
    dio.write_recording(dest, parts["meta"], parts["events"], parts["imu"], level=parts["level"],
                        audio=parts["audio"], audio_sync=parts["audio_sync"],
                        device_events=parts.get("device_events"), derive=derive)
    return Path(dest)


def edit_json(path, fn) -> None:
    """Edits a JSON file in place: fn(doc) changes the parsed document."""
    doc = dio.load_json(path)
    fn(doc)
    dio.dump_json(doc, path)


def make_recording(root, **kw) -> Path:
    """Writes a valid recording under root/recordings/... and returns its folder."""
    parts = build(**kw)
    return write(dio.recording_dir(root, parts["meta"]["recording_id"]), parts)


def rid(n: int, install: str = "k7q2") -> str:
    """Distinct ids: 20261026T1430<ss>Z_<install>_<n:04x-ish>."""
    return f"20261026T14{30 + n // 60:02d}{n % 60:02d}Z_{install}_{n:04d}"


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def deep(self, x):
        return copy.deepcopy(x)
