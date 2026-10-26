"""Run by test_recorder.py with tools/dataset's venv (numpy, pandas,
soundfile): exports take 0 of a .dbrec with dbdata.dbrec, validates the
recording with dbdata.validate and finds, near each given device time, the
onset of an impulse along the bit in the IMU and of a burst in MIC1.
Prints one JSON object.

  python dataset_probe.py FILE.dbrec ROOT T_US [T_US ...]
"""

import json
import sys
from pathlib import Path

DATASET = Path(__file__).resolve().parents[2] / "dataset"
sys.path.insert(0, str(DATASET))

import numpy as np  # noqa: E402

from dbdata import dbrec, validate  # noqa: E402
from dbdata.io import G, read_recording, recording_dir  # noqa: E402

RID = "20261026T143012Z_simq_0001"
SID = "20261026T1428_simq"
PRE_US = 60_000          # the baseline window before the time
POST_US = 40_000
IMU_STEP_G = 0.9         # the breakthrough lunge is 1.5-2 g along the bit
AUDIO_FACTOR = 1.6       # times the loudest sample of the baseline window


def imu_onset(rec, t_us):
    t = rec.t_us
    az = rec.imu["az"].to_numpy() / G             # board Z; the bit points along -Z
    pre = (t >= t_us - PRE_US) & (t < t_us - 5_000)
    win = np.flatnonzero((t >= t_us - PRE_US) & (t <= t_us + POST_US))
    base = float(np.median(az[pre]))
    hit = [i for i in win if base - az[i] > IMU_STEP_G]
    return int(t[hit[0]]) if hit else None


def audio_onset(rec, t_us):
    x = rec.audio[:, 0].astype(np.float64) / 32767.0
    f = np.arange(len(x))
    t = rec.sync.t_of(f)
    pre = (t >= t_us - PRE_US) & (t < t_us - 5_000)
    win = np.flatnonzero((t >= t_us - PRE_US) & (t <= t_us + POST_US))
    thr = AUDIO_FACTOR * float(np.max(np.abs(x[pre])))
    hit = [i for i in win if abs(x[i]) > thr]
    return (float(t[hit[0]]) if hit else None), thr


def main(argv):
    path, root, times = argv[0], Path(argv[1]), [int(v) for v in argv[2:]]
    recs, stats = dbrec.decode_file(path)
    takes = dbrec.split_takes(recs)
    folder = recording_dir(root, RID)
    meta = dbrec.export_take(takes[0], folder, recording_id=RID, session_id=SID)
    rep = validate.validate_recording(folder)
    rec = read_recording(folder)
    out = {"ok": rep.ok, "issues": [f"{i.code} {i.message}" for i in rep.issues],
           "duration_s": meta["duration_s"], "odr_measured_hz": meta["imu"]["odr_measured_hz"],
           "audio_rate_hz": meta["audio"]["rate_measured_hz"] if meta["audio"] else None,
           "imu_samples": int(len(rec.imu)), "gap_flags": int((rec.imu["flags"] & 8).astype(bool).sum()),
           "filled": meta["gaps"]["imu_filled_samples"], "missing": meta["gaps"]["imu_missing_samples"],
           "end_reason": (meta.get("take") or {}).get("end_reason"), "records": stats.records,
           "onsets": []}
    for t_us in times:
        a, thr = audio_onset(rec, t_us)
        out["onsets"].append({"t_us": t_us, "imu": imu_onset(rec, t_us), "audio": a, "audio_thr": thr})
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
