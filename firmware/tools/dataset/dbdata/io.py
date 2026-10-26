"""Reading and writing one recording folder.

A recording is recordings/YYYY/YYYY-MM/<id>/ with meta.json, events.json,
imu.csv.gz, level.csv.gz and, with audio, audio.flac (or audio.wav) plus
audio_sync.csv. All times are device esp_timer microseconds.
"""

from __future__ import annotations

import gzip
import hashlib
import io as _io
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from .contract import read_json as load_json  # noqa: F401 - the recording documents are read with it

G = 9.80665  # m/s^2 per g

IMU_COLUMNS = ("seq", "t_us", "ax", "ay", "az", "gx", "gy", "gz", "flags")
IMU_AXES = ("ax", "ay", "az", "gx", "gy", "gz")
# the zero_* columns hold the zero in effect on that row (0 while ZERO_SET is clear): elevation and
# bubble are reported relative to it, and a zero can be set, moved or cleared during a take
LEVEL_ZERO_COLUMNS = ("zero_elevation_cdeg", "zero_bubble_x_cdeg", "zero_bubble_y_cdeg")
LEVEL_COLUMNS = ("t_us", "mode", "flags", "elevation_cdeg", "yaw_cdeg",
                 "bubble_x_cdeg", "bubble_y_cdeg", "deviation_cdeg") + LEVEL_ZERO_COLUMNS
LEVEL_MODES = ("wall", "floor", "angle")  # level_mode_t order
SYNC_COLUMNS = ("frame", "t_us")

# imu.csv.gz flags
FLAG_FILLED = 1 << 0      # interpolated by the FIFO gap fill
FLAG_ACCEL_CLIP = 1 << 1  # |count| >= 32767 on an accel axis
FLAG_GYRO_CLIP = 1 << 2
FLAG_GAP_BEFORE = 1 << 3  # first sample after a gap: set exactly where seq jumps (transport gap or FIFO DISCONT)

# level.csv.gz flags (DB_ANGLE_FLAG_* of the BLE angle packet)
LEVEL_FLAG_ZERO_SET = 1 << 4
LEVEL_FLAG_IMU_OK = 1 << 6   # without it the angles are meaningless

META_FILE = "meta.json"
EVENTS_FILE = "events.json"
IMU_FILE = "imu.csv.gz"
LEVEL_FILE = "level.csv.gz"
SYNC_FILE = "audio_sync.csv"
DEVICE_EVENTS_FILE = "device_events.csv"
AUDIO_FILES = ("audio.flac", "audio.wav")
DEVICE_EVENTS_COLUMNS = ("t_us", "code", "arg", "text")
# EVENT record codes of the device stream, by name
DEVICE_EVENT_CODES = ("MARK", "TOUCH", "BLE_LOST", "BLE_BACK", "IMU_LOST", "IMU_BACK",
                      "VIB_START", "VIB_STOP", "PROFILE")

RECORDING_ID_RE = re.compile(r"^(\d{8})T(\d{6})Z_([a-z2-7]{4})_([0-9a-z]{4})$")


def parse_recording_id(rid: str) -> datetime:
    """The UTC start second encoded in a recording id."""
    m = RECORDING_ID_RE.match(rid)
    if not m:
        raise ValueError(f"bad recording id {rid!r}")
    try:
        d = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
    except ValueError:
        raise ValueError(f"recording id {rid!r} holds no valid date") from None
    return d.replace(tzinfo=timezone.utc)


def recording_relpath(rid: str) -> str:
    """recordings/YYYY/YYYY-MM/<id> (posix)."""
    d = parse_recording_id(rid)
    return f"recordings/{d:%Y}/{d:%Y-%m}/{rid}"


def recording_dir(root: str | Path, rid: str) -> Path:
    return Path(root) / recording_relpath(rid)


def sha256_file(path: str | Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dump_json(doc: dict, path: str | Path) -> None:
    """UTF-8, 2-space indent, trailing newline (stable diffs)."""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ---- audio/IMU clock alignment ------------------------------------------------

@dataclass(frozen=True)
class SyncFit:
    """t_us = a_us + b_us_per_frame * frame - adc_delay_us (frame = audio frame index)."""

    a_us: float
    b_us_per_frame: float
    residual_rms_us: float = 0.0
    adc_delay_us: float = 0.0
    n: int = 0

    @classmethod
    def fit(cls, frame, t_us, adc_delay_us: float = 0.0) -> "SyncFit":
        """Least-squares line through the (frame, t_us) pairs of audio_sync.csv."""
        f = np.asarray(frame, dtype=np.float64)
        t = np.asarray(t_us, dtype=np.float64)
        if len(f) < 2:
            raise ValueError("a sync fit needs at least two blocks")
        fm, tm = f.mean(), t.mean()
        df, dt = f - fm, t - tm
        b = float((df * dt).sum() / (df * df).sum())
        a = float(tm - b * fm)
        res = dt - b * df
        return cls(a, b, float(np.sqrt(np.mean(res * res))), adc_delay_us, len(f))

    @classmethod
    def from_meta(cls, sync: dict) -> "SyncFit":
        return cls(sync["a_us"], sync["b_us_per_frame"], sync.get("residual_rms_us", 0.0),
                   sync.get("adc_delay_us", 0.0))

    @property
    def rate_hz(self) -> float:
        return 1e6 / self.b_us_per_frame

    def t_of(self, frame):
        return self.a_us + self.b_us_per_frame * np.asarray(frame, dtype=np.float64) - self.adc_delay_us

    def frame_of(self, t_us):
        return (np.asarray(t_us, dtype=np.float64) + self.adc_delay_us - self.a_us) / self.b_us_per_frame


# ---- the recording ------------------------------------------------------------

@dataclass
class Recording:
    """One recording in memory.

    imu: seq, t_us, ax ay az (m/s^2), gx gy gz (rad/s), flags; imu_raw: the
    int16 counts (N, 6) as stored; audio: int16 (frames, channels) or None;
    device_events: device_events.csv or None.
    """

    path: Path | None
    meta: dict
    events: dict
    imu: pd.DataFrame
    imu_raw: np.ndarray
    level: pd.DataFrame | None
    audio: np.ndarray | None
    audio_sync: pd.DataFrame | None
    sync: SyncFit | None
    device_events: pd.DataFrame | None = None

    @property
    def id(self) -> str:
        return self.meta["recording_id"]

    @property
    def odr_hz(self) -> float:
        return float(self.meta["imu"]["odr_nominal_hz"])

    @property
    def audio_rate_hz(self) -> float | None:
        a = self.meta.get("audio")
        return float(a["rate_nominal_hz"]) if a else None

    @property
    def t_us(self) -> np.ndarray:
        return self.imu["t_us"].to_numpy(np.int64)

    @property
    def filled(self) -> np.ndarray:
        return (self.imu["flags"].to_numpy() & FLAG_FILLED) != 0


def imu_to_si(raw: np.ndarray, imu_meta: dict, *, calibrate: bool = False,
              mount: bool = False) -> np.ndarray:
    """Counts (N, 6) -> float64 m/s^2 and rad/s in the board frame.

    calibrate applies accel_cal ((g - bias) * scale) and subtracts
    gyro_bias_rad_s; mount rotates so the drill axis becomes -Z.
    """
    x = np.asarray(raw, dtype=np.float64)
    acc = x[:, :3] / float(imu_meta["accel_lsb_per_g"])
    gyr = np.deg2rad(x[:, 3:] / float(imu_meta["gyro_lsb_per_dps"]))
    if calibrate:
        cal = imu_meta.get("accel_cal")
        if cal and cal.get("valid"):
            acc = (acc - np.asarray(cal["bias_g"])) * np.asarray(cal["scale"])
        if imu_meta.get("gyro_bias_rad_s") is not None:
            gyr = gyr - np.asarray(imu_meta["gyro_bias_rad_s"])
    acc = acc * G
    if mount:
        m = imu_meta.get("mount")
        if m and m.get("valid"):
            r = rotation_to_minus_z(m["drill_axis"])
            acc, gyr = acc @ r.T, gyr @ r.T
    return np.hstack([acc, gyr])


def rotation_to_minus_z(axis) -> np.ndarray:
    """The smallest rotation taking the unit vector axis to (0, 0, -1)."""
    a = np.asarray(axis, dtype=np.float64)
    a = a / np.linalg.norm(a)
    b = np.array([0.0, 0.0, -1.0])
    v, c = np.cross(a, b), float(a @ b)
    if c < -1 + 1e-12:  # axis is +Z: half turn about X
        return np.diag([1.0, -1.0, -1.0])
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx / (1 + c)


def _select(df: pd.DataFrame, columns, what: str, dtype: str | None = "int64") -> pd.DataFrame:
    """df[columns] (cast to dtype unless None); ValueError naming the missing columns."""
    missing = [c for c in columns if c not in df.columns]
    if missing:
        raise ValueError(f"{what}: missing columns {missing}")
    out = df[list(columns)]
    return out if dtype is None else out.astype(dtype)


def read_imu_csv(path: str | Path) -> pd.DataFrame:
    return _select(pd.read_csv(path, dtype={c: "int64" for c in IMU_COLUMNS}), IMU_COLUMNS, f"{path}: IMU",
                   dtype=None)


def read_level_csv(path: str | Path) -> pd.DataFrame:
    return _select(pd.read_csv(path), LEVEL_COLUMNS, f"{path}: level")


def read_sync_csv(path: str | Path) -> pd.DataFrame:
    return _select(pd.read_csv(path), SYNC_COLUMNS, f"{path}: sync")


def read_device_events_csv(path: str | Path) -> pd.DataFrame:
    """device_events.csv: t_us, code (DEVICE_EVENT_CODES name), arg, text (may be empty)."""
    df = pd.read_csv(path, dtype={"t_us": "int64", "code": str, "arg": "int64", "text": str},
                     keep_default_na=False)
    return _select(df, DEVICE_EVENTS_COLUMNS, f"{path}: device events", dtype=None)


def read_audio(path: str | Path) -> tuple[np.ndarray, int]:
    import soundfile as sf

    data, rate = sf.read(str(path), dtype="int16", always_2d=True)
    return data, rate


def read_recording(path: str | Path, *, calibrate: bool = False, mount: bool = False,
                   audio: bool = True, level: bool = True) -> Recording:
    """Loads a recording folder. IMU in SI units (see imu_to_si); raw counts
    and flags are kept. The sync fit comes from audio_sync.csv (meta.sync if
    that file is missing)."""
    p = Path(path)
    meta = load_json(p / META_FILE)
    events = load_json(p / EVENTS_FILE)
    raw_df = read_imu_csv(p / IMU_FILE)
    raw = raw_df[list(IMU_AXES)].to_numpy(np.int16)
    si = imu_to_si(raw, meta["imu"], calibrate=calibrate, mount=mount)
    imu = pd.DataFrame({"seq": raw_df["seq"].to_numpy(np.int64),
                        "t_us": raw_df["t_us"].to_numpy(np.int64)})
    for i, c in enumerate(IMU_AXES):
        imu[c] = si[:, i]
    imu["flags"] = raw_df["flags"].to_numpy(np.int64)

    lvl = read_level_csv(p / LEVEL_FILE) if level and (p / LEVEL_FILE).exists() else None

    am = meta.get("audio")
    samples = sync_df = sync = None
    if am:
        if audio and (p / am["file"]).exists():
            samples, _ = read_audio(p / am["file"])
        delay = (meta.get("sync") or {}).get("adc_delay_us", 0.0)
        if (p / SYNC_FILE).exists():
            sync_df = read_sync_csv(p / SYNC_FILE)
            if len(sync_df) >= 2:
                sync = SyncFit.fit(sync_df["frame"], sync_df["t_us"], delay)
        if sync is None and meta.get("sync"):
            sync = SyncFit.from_meta(meta["sync"])
    dev = read_device_events_csv(p / DEVICE_EVENTS_FILE) if (p / DEVICE_EVENTS_FILE).exists() else None
    return Recording(p, meta, events, imu, raw, lvl, samples, sync_df, sync, dev)


# ---- writing ------------------------------------------------------------------

def _write_csv_gz(df: pd.DataFrame, path: Path) -> None:
    """gzip with mtime 0 and no file name, so equal data gives equal bytes."""
    with open(path, "wb") as raw, \
            gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as gz, \
            _io.TextIOWrapper(gz, encoding="ascii", newline="\n") as txt:
        df.to_csv(txt, index=False, lineterminator="\n")


def _as_frame(data, columns, what: str, dtype: str | None = "int64") -> pd.DataFrame:
    return _select(data if isinstance(data, pd.DataFrame) else pd.DataFrame(data), columns, what, dtype)


def write_recording(dest: str | Path, meta: dict, events: dict, imu, *, level,
                    audio: np.ndarray | None = None, audio_sync=None, device_events=None,
                    derive: bool = False, taxonomy=None) -> dict:
    """Writes a complete recording folder and returns the meta that was written.

    imu: DataFrame/dict with IMU_COLUMNS (raw int16 counts); level: LEVEL_COLUMNS;
    audio: int16 (frames, channels) with meta.audio set; audio_sync: SYNC_COLUMNS;
    device_events: optional DEVICE_EVENTS_COLUMNS (device_events.csv).
    meta.files (sha256, bytes) is filled in; derive=True also recomputes
    meta.derived and the computed per-hole off angles (labels.compute_derived).
    """
    d = Path(dest)
    d.mkdir(parents=True, exist_ok=True)
    meta = json.loads(json.dumps(meta))
    events = json.loads(json.dumps(events))

    imu_df = _as_frame(imu, IMU_COLUMNS, "imu")
    axes = imu_df[list(IMU_AXES)].to_numpy()
    if axes.size and (axes.min() < -32768 or axes.max() > 32767):
        raise ValueError("IMU counts outside int16")
    level_df = _as_frame(level, LEVEL_COLUMNS, "level")
    _write_csv_gz(imu_df, d / IMU_FILE)
    _write_csv_gz(level_df, d / LEVEL_FILE)
    names = [IMU_FILE, LEVEL_FILE]

    am = meta.get("audio")
    if am:
        if audio is None or audio_sync is None:
            raise ValueError("meta.audio is set: audio and audio_sync are required")
        import soundfile as sf

        a = np.asarray(audio)
        if a.ndim == 1:
            a = a[:, None]
        if a.dtype != np.int16:
            raise ValueError("audio must be int16")
        fmt = "FLAC" if am["codec"] == "flac" else "WAV"
        sf.write(str(d / am["file"]), a, int(round(am["rate_nominal_hz"])),
                 subtype="PCM_16", format=fmt)
        sync_df = _as_frame(audio_sync, SYNC_COLUMNS, "audio_sync")
        sync_df.to_csv(d / SYNC_FILE, index=False, lineterminator="\n")
        names += [am["file"], SYNC_FILE]
    elif audio is not None:
        raise ValueError("audio given but meta.audio is null")

    if device_events is not None:
        de = _as_frame(device_events, DEVICE_EVENTS_COLUMNS, "device_events", dtype=None)
        de.to_csv(d / DEVICE_EVENTS_FILE, index=False, lineterminator="\n")
        names.append(DEVICE_EVENTS_FILE)

    if derive:
        from . import labels

        derived, holes = labels.compute_derived(level_df, events, meta, taxonomy)
        meta["derived"] = derived
        for h in events.get("holes", []):
            if h.get("off_angle_source") == "computed" and h["hole"] in holes:
                h["off_angle_deg"] = holes[h["hole"]]["off_angle_deg"]

    meta["files"] = {n: {"sha256": sha256_file(d / n), "bytes": (d / n).stat().st_size}
                     for n in sorted(names)}
    dump_json(events, d / EVENTS_FILE)
    dump_json(meta, d / META_FILE)
    return meta
