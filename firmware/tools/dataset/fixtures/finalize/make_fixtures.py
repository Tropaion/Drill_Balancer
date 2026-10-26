"""Expected output of the reference exporter (dbdata.dbrec) for the app's finalizer.

The app rebuilds a take's dataset files from its journal (recorder/export/Finalizer.kt);
FinalizerCrossCheckTest feeds it the streams here and compares what it writes with what
`dbrec.export_take` wrote from the same bytes (README.md says what is compared).

    cd firmware/tools/dataset
    .venv/bin/python fixtures/finalize/make_fixtures.py            # (re)write the fixtures
    .venv/bin/python fixtures/finalize/make_fixtures.py --check    # compare with a fresh export

rich.dbr is written here too (deterministic): one take with every case the export handles.
"""

from __future__ import annotations

import argparse
import gzip
import json
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))

from dbdata import dbrec  # noqa: E402
from dbdata import io as dio  # noqa: E402

GOLDEN = HERE.parents[3] / "test_host" / "recorder" / "vectors" / "golden_stream.bin"
RICH = HERE / "rich.dbr"
RICH_CUT = 0.62             # rich_cut: the journal of an app that died there (mid-record)

# the phone side of each take (take.json): ids, start time, the setup screen's labels
FIXTURES = {
    "golden": {
        "stream": "golden", "cut": None, "take_id": 42,
        "recording_id": "20261026T143012Z_k7q2_9f3a", "session_id": "20261026T1428_k7q2",
        "start_utc": "2026-10-26T14:30:12.418Z",
        "setup": {"material": "wood/softwood", "thickness_mm": 38.0, "mode": "drill", "bit_type": "brad_point",
                  "bit_diameter_mm": 8.0, "drill_type": "drill", "motor": "brushless",
                  "drill_model": "Bosch GSR 18V-55", "gear": 2, "notes": ""},
        "session": {"material": "wood/softwood", "layers": [{"material": "wood/softwood", "thickness_mm": 38.0}],
                    "mode": "drill", "bit": {"type": "brad_point", "diameter_mm": 8.0},
                    "drill": {"type": "drill", "motor": "brushless", "model": "Bosch GSR 18V-55", "gear": 2,
                              "speed_setting": None},
                    "notes": ""},
    },
    "rich": {
        "stream": "rich", "cut": None, "take_id": 5,
        "recording_id": "20261027T091500Z_m3xa_0b7z", "session_id": "20261027T0912_m3xa",
        "start_utc": "2026-10-27T09:15:00.007Z",
        "setup": {"material": "other", "material_other": "Bamboo, \"laminated\"", "thickness_mm": 12.5,
                  "mode": "hammer", "bit_type": "masonry_sds", "bit_diameter_mm": 6.5, "drill_type": "rotary_hammer",
                  "motor": "other", "motor_other": "Brushless – \"2-speed\"", "drill_model": "Makita",
                  "speed_setting": 3.5, "notes": "near the window – ä"},
        "session": {"material": "other", "layers": [{"material": "other", "thickness_mm": 12.5}],
                    "mode": "hammer", "bit": {"type": "masonry_sds", "diameter_mm": 6.5},
                    "drill": {"type": "rotary_hammer", "motor": "other", "model": "Makita", "gear": None,
                              "speed_setting": 3.5},
                    "notes": "near the window – ä",
                    "other_text": {"material": "Bamboo, \"laminated\"", "layers[0]": "Bamboo, \"laminated\"",
                                   "motor": "Brushless – \"2-speed\""}},
    },
    "rich_cut": {
        "stream": "rich", "cut": RICH_CUT, "take_id": 5,
        "recording_id": "20261027T091500Z_m3xa_1c8y", "session_id": "20261027T0912_m3xa",
        "start_utc": "2026-10-27T09:15:00.007Z",
        "setup": {},
        "session": {"material": "unknown", "mode": "drill", "bit": {"type": "unknown", "diameter_mm": None},
                    "drill": {"type": "unknown", "motor": "unknown", "model": "", "gear": None, "speed_setting": None},
                    "notes": ""},
    },
}

# ---- rich.dbr ---------------------------------------------------------------------------------

T = 2_000_000_000                       # device µs at the take start
P = 1e9 / 448400 * (1 + 31e-6)          # the IMU's real period: the chip clock runs 31 ppm slow
AUDIO_HZ = 16000.37
TAIL = {"settings": {"mode": "floor", "target": 0.0, "tol": 1.5, "near": 3},
        "cal": {"accel": {"valid": 1, "bias_g": [0.0040, -0.011, 2e-2], "scale": [1.002, 0.998, 1.0040000000000001]},
                "mount": {"valid": False, "drill_axis": [0.012, -0.02, -0.9997]},
                "gyro_bias_rad_s": [1e-05, -0.0004, 0.1234567890123]}}


def _session(take_id: int, tail: dict) -> bytes:
    fixed = dbrec.SESSION.pack(take_id, T, bytes.fromhex("5e11a0c3"), b"0.4.1-3-g1a2b3c4d-dirty".ljust(32, b"\0"),
                               1, 0x14, 0x54, 0x00, 448400, 8192, 64, 1, 1, 0, -1, 2, 1, 16000, 2, 16, 3, 12, 256, 300)
    return fixed + json.dumps(tail).encode()


def rich_stream() -> bytes:
    """One take (RAW448, two microphones, 3.4 s), its records in time order:
    - audio from T, IMU from T + 150 ms (the profile switch), audio ending after the IMU;
    - an IMU read before the take's PROFILE event (left out of the export);
    - reads of 6-12 samples, one sample lost in read mode after most (filled), a 40-sample loss
      with DISCONT that host time confirms, an IMU read dropped on the device (a transport
      gap), an IMU reset (IMU_LOST / IMU_BACK, EPOCH, the count from 5 again) after 400 ms;
    - clipped IMU and audio samples; audio blocks with host-time jitter, one dropped on the
      device, one lost to an I2S overflow (OVF_BEFORE on the next);
    - LEVEL at 10 Hz (a zero set from 1.5 s), STATS at 1 Hz, MARK / TOUCH / VIB_START events
      (MARK text with a comma, quotes and non-ASCII), an unknown record type, junk bytes,
      END max_s."""
    rng = np.random.default_rng(20261027)
    items = []                      # (t_us, order, kind, args)

    def add(t, kind, *args):
        items.append((int(t), len(items), kind, args))

    # IMU
    add(T + 120_000, "imu", 0x00FF0000, np.tile([7, 7, 7, 7, 7, 7], (4, 1)), 0x00FF0003, 0)    # still LEVEL
    add(T + 140_000, "event", 9, 1, b"")                                                         # PROFILE RAW448

    def frames(k0, n, seg):
        k = np.arange(k0, k0 + n)
        f = np.column_stack([
            np.round(3000 * np.sin(k / 9.0)) + rng.integers(-40, 40, n),
            8192 + rng.integers(-60, 60, n),
            np.round(1500 * np.cos(k / 4.0)) + seg * 100,
            rng.integers(-300, 300, n), rng.integers(-300, 300, n), (k % 97) - 48])
        for j, kk in enumerate(k):
            if kk % 211 == 17:
                f[j, 0] = 32767
            if kk % 307 == 5:
                f[j, 5] = -32768
        return f.astype(np.int64)

    def imu_run(ts0, host0, t_end, seg, first_flags, read_no):
        k = 0
        flags = first_flags
        while True:
            n = int(rng.integers(6, 13))
            t_last = host0 + (k + n - 1) * P
            if t_last > t_end:
                break
            host = int(round(t_last)) + int(rng.integers(0, 400))
            drop = read_no == 57
            add(host, "imu", ts0 + k, frames(k, n, seg), ts0 + k + n - 1, flags, drop)
            read_no += 1
            k += n
            flags = 0
            if read_no == 23:
                k += 40                       # a big FIFO loss: DISCONT on the next, host time agrees
                flags = dbrec.IMU_DISCONT
            elif read_no % 4:
                k += 1                        # read-mode loss: filled
        return read_no

    reads = imu_run(0x00FFF000, T + 150_000, T + 1_900_000, 0, dbrec.IMU_DISCONT, 0)
    add(T + 1_905_000, "event", 5, 0, b"")                                                      # IMU_LOST
    add(T + 2_301_000, "event", 6, 0, b"")                                                      # IMU_BACK
    imu_run(5, T + 2_300_000, T + 3_200_000, 1, dbrec.IMU_DISCONT | dbrec.IMU_EPOCH, reads)

    # audio: 256-frame blocks
    block = 0
    index = 0
    flags = 0
    while True:
        end = T + (index + 256) / AUDIO_HZ * 1e6
        if end > T + 3_350_000:
            break
        if block == 70:
            index += 256                    # lost to an I2S overflow: never sent
            flags = dbrec.AUDIO_OVF_BEFORE
            block += 1
            continue
        i = np.arange(index, index + 256)
        s = np.column_stack([np.round(9000 * np.sin(i / 11.0)) + rng.integers(-200, 200, 256),
                             np.round(6000 * np.sin(i / 7.3 + 1)) + rng.integers(-200, 200, 256)])
        if block == 12:
            s[10:14, 0] = 32767
            s[100:102, 1] = -32768
        add(end + int(rng.integers(0, 90)), "audio", index, s.astype(np.int64), flags, block == 33)
        flags = 0
        index += 256
        block += 1

    # level, stats, events
    for i in range(34):
        zero = i >= 15
        add(T + i * 100_000, "level", 1, 0x40 | (0x10 if zero else 0) | (0x01 if i % 3 == 0 else 0),
            -8950 + i * 7, 120 - i, 30 + i, -12, 50 + 2 * i, (1234, 10, -20) if zero else (0, 0, 0))
    for i in range(1, 4):
        add(T + i * 1_000_000, "stats", i)
    add(T + 800_000, "event", 1, 7, "hole 1, \"oak\" – äöü".encode())
    add(T + 1_100_000, "event", 2, 0, b"")
    add(T + 1_300_000, "event", 7, 0, b"")
    add(T + 1_350_000, "unknown")
    add(T + 1_360_000, "junk")
    items.sort(key=lambda x: (x[0], x[1]))

    out = bytearray()
    seq = 0
    stats_counts = {"imu": 0}

    def rec(rtype, payload, drop=False):
        nonlocal seq
        if not drop:
            out.extend(dbrec.encode_record(rtype, 1, seq, payload))
        seq += 1

    rec(dbrec.T_SESSION, _session(5, TAIL))
    for t, _, kind, args in items:
        if kind == "imu":
            ts0, f, chip, fl, *drop = args
            stats_counts["imu"] += len(f)
            rec(dbrec.T_IMU, dbrec.IMU.pack(ts0, chip, t, len(f), fl) + f.astype("<i2").tobytes(), bool(drop and drop[0]))
        elif kind == "audio":
            index, s, fl, drop = args
            rec(dbrec.T_AUDIO, dbrec.AUDIO.pack(index, t, len(s), fl) + s.astype("<i2").tobytes(), drop)
        elif kind == "event":
            code, arg, text = args
            rec(dbrec.T_EVENT, dbrec.EVENT.pack(t, code, arg) + text)
        elif kind == "level":
            mode, fl, elev, yaw, bx, by, dev, zero = args
            rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(t, mode, fl, elev, yaw, bx, by, dev, *zero))
        elif kind == "stats":
            (i,) = args
            rec(dbrec.T_STATS, dbrec.STATS.pack(t, stats_counts["imu"], 40 * i, 2230000, 16000 * i, 1, 16000370,
                                                3 * i, 9000 * i + 3, 4 << 20, i - 1, 1044 * (i - 1), 3900 - i,
                                                81 - i, 1 if i != 2 else 2))
        elif kind == "unknown":
            out.extend(dbrec.encode_record(0x7E, 1, seq, b"future"))
            seq += 1
        elif kind == "junk":
            out.extend(b"\x00\x01\xffjunk")
    out.extend(dbrec.encode_record(dbrec.T_END, 1, seq, dbrec.END.pack(T + 3_400_000, 2, seq + 1, 2, 2088,
                                                                      stats_counts["imu"], 120, 51200, 1)))
    return bytes(out)


# ---- export ---------------------------------------------------------------------------------

def stream_bytes(spec: dict) -> bytes:
    data = GOLDEN.read_bytes() if spec["stream"] == "golden" else RICH.read_bytes()
    return data[:int(len(data) * spec["cut"])] if spec["cut"] else data


def export(name: str, dest: Path) -> dict:
    """The fixture's take exported by dbrec into dest (the recording files and spec.json)."""
    spec = FIXTURES[name]
    recs, _ = dbrec.decode(stream_bytes(spec))
    take = next(t for t in dbrec.split_takes(recs) if t.session and t.session.take_id == spec["take_id"])
    dest.mkdir(parents=True, exist_ok=True)
    meta = dbrec.export_take(take, dest, recording_id=spec["recording_id"], session_id=spec["session_id"],
                             start_utc=spec["start_utc"], session_labels=spec["session"])
    dio.dump_json({k: v for k, v in spec.items()}, dest / "spec.json")
    return meta


def write_all() -> None:
    RICH.write_bytes(rich_stream())
    for name in FIXTURES:
        d = HERE / name
        if d.is_dir():
            shutil.rmtree(d)
        meta = export(name, d)
        print(f"wrote {d.relative_to(HERE)}: {meta['duration_s']} s, files {sorted(meta['files'])}")


def _content(folder: Path, name: str):
    p = folder / name
    if name.endswith(".gz"):
        return gzip.decompress(p.read_bytes())
    if name.endswith(".flac"):
        return dio.read_audio(p)[0].tobytes()
    if name == "meta.json":
        meta = json.loads(p.read_text(encoding="utf-8"))
        meta["files"] = {k: v for k, v in meta["files"].items() if not k.endswith((".gz", ".flac"))}
        return meta
    return p.read_bytes()


def check_all() -> list[str]:
    problems = []
    if RICH.read_bytes() != rich_stream():
        problems.append("rich.dbr differs from rich_stream()")
    with tempfile.TemporaryDirectory() as tmp:
        for name in FIXTURES:
            fresh = Path(tmp) / name
            export(name, fresh)
            have = sorted(p.name for p in (HERE / name).iterdir()) if (HERE / name).is_dir() else []
            want = sorted(p.name for p in fresh.iterdir())
            if have != want:
                problems.append(f"{name}: files {have} != {want}")
                continue
            for f in want:
                if _content(HERE / name, f) != _content(fresh, f):
                    problems.append(f"{name}/{f} differs")
    return problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="compare the fixtures with a fresh export")
    a = ap.parse_args(argv)
    if a.check:
        problems = check_all()
        for p in problems:
            print(p)
        print("finalize fixtures " + ("differ" if problems else "match"))
        return 1 if problems else 0
    write_all()
    return 0


if __name__ == "__main__":
    sys.exit(main())
