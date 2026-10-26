"""Recording validator (used by the data repo's Action and before uploads).

    python -m dbdata.validate PATH...          # recording folders or trees holding them
    python -m dbdata.validate --light PATH     # meta.json/events.json only
    python -m dbdata.validate --contract REPO  # repo taxonomy/ and schema/ match dbdata

Exit code 0 when no errors (warnings are allowed unless --strict), 1 on
errors, 2 on usage errors. Error codes start with E_, warnings with W_.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

from . import contract, labels
from .contract import Taxonomy, duplicates, load_taxonomy, read_json
from .io import (AUDIO_FILES, DEVICE_EVENT_CODES, DEVICE_EVENTS_FILE, EVENTS_FILE, FLAG_FILLED,
                 FLAG_GAP_BEFORE, IMU_AXES, IMU_FILE, LEVEL_FILE, LEVEL_FLAG_ZERO_SET, LEVEL_ZERO_COLUMNS,
                 META_FILE, SYNC_FILE, SyncFit, parse_recording_id, read_device_events_csv, read_imu_csv,
                 read_level_csv, read_sync_csv, recording_relpath, sha256_file)

MAX_FILE_BYTES = 95 * 1024 * 1024
ODR_TOL = 0.02            # measured ODR within 2 % of nominal
FILLED_WARN = 0.15        # filled fraction
MISSING_WARN = 0.01       # missing fraction
DURATION_TOL = 0.01       # audio vs IMU duration
SYNC_RESIDUAL_MAX_US = 1000.0
DERIVED_TOL_DEG = 0.051
DEVICE_TEXT_MAX = 64

FORBIDDEN_KEYS = frozenset({
    "lat", "lon", "lng", "latitude", "longitude", "location", "gps", "geo",
    "name", "first_name", "last_name", "full_name", "username", "user_name",
    "email", "e_mail", "mail", "mac", "mac_address", "bssid", "android_id",
    "imei", "serial", "phone_number", "timezone", "tz",
})
# Codes only the data checks produce (the index carries them over between runs).
DATA_CODES = frozenset({
    "E_FILE_SIZE", "W_EXTRA_FILE", "E_MISSING_FILE", "E_BYTES", "E_SHA", "E_IMU_FORMAT",
    "E_SEQ_ORDER", "E_T_ORDER", "E_ODR", "W_ODR_META", "W_FILLED", "W_MISSING", "W_GAPS_META",
    "W_COVERAGE", "E_AUDIO_FORMAT", "E_SYNC_FORMAT", "E_SYNC", "W_SYNC_META", "E_DURATION",
    "E_LEVEL_FORMAT", "E_LEVEL_ZERO", "E_DERIVED", "W_GAP_FLAG", "E_DEVICE_EVENTS", "E_PII_DATA",
    "E_INTERNAL",
})

_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_MAC_RE = re.compile(r"\b[0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\1){4}[0-9A-Fa-f]{2}\b")


@dataclass
class Issue:
    code: str
    message: str

    @property
    def is_error(self) -> bool:
        return self.code.startswith("E_")


@dataclass
class Report:
    """The issues of one recording.

    fingerprint: of every file of the folder that was checked (fingerprint();
    '' when meta.json or events.json is missing). docs holds the parsed
    meta and events (None where they did not parse) and segments the phase
    segments once the events passed their schema, so the index reads and
    walks nothing twice. to_dict() leaves docs and segments out.
    """

    path: str
    recording_id: str | None = None
    light: bool = False
    issues: list[Issue] = field(default_factory=list)
    fingerprint: str = ""
    docs: dict = field(default_factory=dict, repr=False, compare=False)
    segments: list | None = field(default=None, repr=False, compare=False)

    def add(self, code: str, message: str) -> None:
        self.issues.append(Issue(code, message))

    @property
    def errors(self) -> list[Issue]:
        return [i for i in self.issues if i.is_error]

    @property
    def warnings(self) -> list[Issue]:
        return [i for i in self.issues if not i.is_error]

    @property
    def ok(self) -> bool:
        return not self.errors

    def codes(self) -> set[str]:
        return {i.code for i in self.issues}

    def to_dict(self) -> dict:
        return {"path": self.path, "recording_id": self.recording_id, "light": self.light,
                "ok": self.ok, "fingerprint": self.fingerprint, "issues": [asdict(i) for i in self.issues]}

    @property
    def key(self) -> str:
        """Its key in a --report file, which the index looks up: the recording id, else the folder name."""
        return self.recording_id or Path(self.path).name


def fingerprint(folder: str | Path, raw: dict[str, bytes] | None = None) -> str:
    """The identity of every file of a recording folder: a report's fingerprint, the index's 'checked'.

    In a git checkout whose copy of the folder matches HEAD (nothing changed, nothing untracked):
    'git:' + the folder's tree id at HEAD, which also covers the data files a sparse checkout (the
    Action's) leaves out. Otherwise 'sha:' + a sha256 over the relative path and content of every
    file below the folder (raw: contents already read, by file name). '' when meta.json or
    events.json is missing.
    """
    folder = Path(folder)
    raw = raw or {}
    if not all(n in raw or (folder / n).is_file() for n in (META_FILE, EVENTS_FILE)):
        return ""
    tree = _git_tree(folder)
    if tree:
        return "git:" + tree
    h = hashlib.sha256()
    for f in sorted(p for p in folder.rglob("*") if p.is_file()):
        rel = f.relative_to(folder).as_posix()
        digest = hashlib.sha256(raw[rel]).hexdigest() if rel in raw else sha256_file(f)
        h.update(f"{rel}\0{digest}\0".encode("utf-8"))
    return "sha:" + h.hexdigest()[:40]


def _git_tree(folder: Path) -> str | None:
    """The folder's tree id at HEAD when git tracks it and its files match HEAD, else None."""
    def git(*args):
        return subprocess.run(["git", "-C", str(folder), *args], capture_output=True, text=True, timeout=60,
                              env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"})
    try:
        tree = git("rev-parse", "--verify", "--quiet", "HEAD:./")
        if tree.returncode != 0:
            return None
        status = git("status", "--porcelain", "--untracked-files=all", "--", ".")
    except (OSError, subprocess.SubprocessError):  # no git
        return None
    if status.returncode != 0 or status.stdout.strip():
        return None
    return tree.stdout.strip() or None


# ---- helpers --------------------------------------------------------------------

def _walk(doc, path=""):
    """(json path, key, value) for every dict key and (path, None, value) for strings."""
    if isinstance(doc, dict):
        for k, v in doc.items():
            p = f"{path}.{k}" if path else k
            yield p, k, v
            yield from _walk(v, p)
    elif isinstance(doc, list):
        for i, v in enumerate(doc):
            yield from _walk(v, f"{path}[{i}]")
    elif isinstance(doc, str):
        yield path, None, doc


def _looks_pii(text: str) -> bool:
    """Holds something that looks like an email or MAC address."""
    return bool(_EMAIL_RE.search(text) or _MAC_RE.search(text))


def check_privacy(doc: dict, where: str, rep: Report) -> None:
    for path, key, value in _walk(doc):
        if key is not None and key.lower() in FORBIDDEN_KEYS:
            rep.add("E_FORBIDDEN_KEY", f"{where}: forbidden key {path}")
        if key is None and _looks_pii(value):
            rep.add("E_PII", f"{where}: {path} looks like an email or MAC address")


def _schema(doc: dict, name: str, code: str, rep: Report) -> bool:
    errs = sorted(contract.schema_validator(name).iter_errors(doc), key=lambda e: list(e.path))
    for e in errs[:20]:
        loc = "/".join(str(p) for p in e.absolute_path) or "(root)"
        rep.add(code, f"{loc}: {e.message[:200]}")
    if len(errs) > 20:
        rep.add(code, f"... {len(errs) - 20} more schema errors")
    return not errs


def _tax(tax: Taxonomy, fld: str, value, where: str, rep: Report, version: int,
         other_text: dict | None = None, other_key: str | None = None) -> None:
    if value is None:
        return
    e = tax.lookup(fld, value)
    if e is None:
        rep.add("E_TAXONOMY", f"{where}: {value!r} is not a {fld} id")
    elif e.deprecated:
        rep.add("W_DEPRECATED", f"{where}: {value!r} is deprecated (use {e.replaced_by})")
    if e is not None and e.added > version:
        rep.add("W_TAXONOMY_ADDED", f"{where}: {value!r} was added in taxonomy {e.added}, "
                                    f"the document says {version}")
    if value == contract.OTHER and not (other_text or {}).get(other_key or fld):
        rep.add("E_OTHER_TEXT", f"{where}: 'other' needs other_text.{other_key or fld}")


# ---- light checks (meta.json + events.json) ---------------------------------------

def _check_meta_events(meta: dict, events: dict, folder: Path, tax: Taxonomy, rep: Report) -> bool:
    ok_m = _schema(meta, contract.META_SCHEMA_FILE, "E_SCHEMA_META", rep)
    ok_e = _schema(events, contract.EVENTS_SCHEMA_FILE, "E_SCHEMA_EVENTS", rep)
    check_privacy(meta, META_FILE, rep)
    check_privacy(events, EVENTS_FILE, rep)
    if not (ok_m and ok_e):
        return False

    rid = meta["recording_id"]
    if events["recording_id"] != rid:
        rep.add("E_ID", f"events.json recording_id {events['recording_id']} != {rid}")
    if folder.name != rid:
        rep.add("E_ID", f"folder {folder.name} != recording_id {rid}")
    try:
        start = parse_recording_id(rid)
    except ValueError as e:
        rep.add("E_ID", str(e))
        start = None
    parts = folder.parts
    if start is not None and len(parts) >= 4 and parts[-4] == "recordings":
        want = recording_relpath(rid).split("/")[1:3]   # recordings/YYYY/YYYY-MM/<id>
        if list(parts[-3:-1]) != want:
            rep.add("E_ID", f"folder {'/'.join(parts[-3:-1])} does not match {'/'.join(want)}")
    if start is not None and meta["start_utc"][:19].replace("-", "").replace(":", "") != f"{start:%Y%m%dT%H%M%S}":
        rep.add("W_START_UTC", f"start_utc {meta['start_utc']} does not match the id")
    for doc, name in ((meta, META_FILE), (events, EVENTS_FILE)):
        if doc["taxonomy_version"] > tax.version:
            rep.add("E_TAXONOMY_VERSION",
                    f"{name} uses taxonomy {doc['taxonomy_version']}, this validator knows {tax.version}")

    s = meta["session"]
    ot = s.get("other_text", {})
    v = meta["taxonomy_version"]
    _tax(tax, "material", s["material"], "session.material", rep, v, ot)
    for i, layer in enumerate(s.get("layers", [])):
        _tax(tax, "material", layer["material"], f"session.layers[{i}]", rep, v, ot, f"layers[{i}]")
    _tax(tax, "mode", s["mode"], "session.mode", rep, v, ot)
    _tax(tax, "bit_type", s["bit"]["type"], "session.bit.type", rep, v, ot)
    for tag in s.get("outcome_tags", []):
        _tax(tax, "outcome_tag", tag, "session.outcome_tags", rep, v, ot, "outcome_tags")
    _tax(tax, "orientation", meta["derived"]["orientation"], "derived.orientation", rep, v)
    for tag in meta["derived"]["tags"]:
        _tax(tax, "outcome_tag", tag, "derived.tags", rep, v)
    for fl in meta["quality"]["flags"]:
        _tax(tax, "quality_flag", fl, "quality.flags", rep, v)
    _tax(tax, "imu_profile", meta["imu"]["profile"], "imu.profile", rep, v)

    g = meta["gaps"]
    gap_frames = g.get("audio_gap_frames", [])
    if any(f1 <= f0 for f0, f1 in gap_frames):
        rep.add("E_AUDIO_GAPS", "gaps.audio_gap_frames holds an empty or reversed range")
    elif sum(f1 - f0 for f0, f1 in gap_frames) != g["audio_missing_frames"]:
        rep.add("E_AUDIO_GAPS", f"gaps.audio_gap_frames cover {sum(f1 - f0 for f0, f1 in gap_frames)} frames, "
                                f"audio_missing_frames is {g['audio_missing_frames']}")

    am = meta["audio"]
    if am is not None:
        if am["file"] not in meta["files"]:
            rep.add("E_FILES_META", f"meta.files lacks {am['file']}")
        if SYNC_FILE not in meta["files"]:
            rep.add("E_FILES_META", f"meta.files lacks {SYNC_FILE}")
        if meta["sync"] is None:
            rep.add("E_AUDIO_META", "audio without a sync fit")
        if not am["file"].endswith(am["codec"]):
            rep.add("E_AUDIO_META", f"codec {am['codec']} does not match {am['file']}")
    elif any(n in meta["files"] for n in (*AUDIO_FILES, SYNC_FILE)):
        rep.add("E_AUDIO_META", "audio files listed but meta.audio is null")

    _check_events(events, tax, rep)
    return True


def _check_events(events: dict, tax: Taxonomy, rep: Report) -> None:
    v = events["taxonomy_version"]
    c0, c1 = events["coverage_us"]
    if c1 <= c0:
        rep.add("E_COVERAGE", f"coverage_us {c0}..{c1} is empty")
    dup = duplicates(x["id"] for x in events["events"] + events["spans"])
    if dup:
        rep.add("E_EVENT_ID", f"duplicate event ids {dup}")

    hole_events = tax.hole_events()
    times = [e["t_us"] for e in events["events"]]
    if times != sorted(times):
        rep.add("W_EVENTS_UNSORTED", "events are not in time order")
    known = {}  # per kind, the items of a known type (the checks below need their taxonomy entry)
    for kind, what, items, types in (("event", "point event", events["events"], tax.point_events),
                                     ("span", "span event", events["spans"], tax.span_events)):
        known[kind] = []
        for x in items:
            entry = types.get(x["type"])
            if entry is None:
                rep.add("E_TAXONOMY", f"{kind} {x['id']}: {x['type']!r} is not a {what}")
                continue
            if entry.added > v:
                rep.add("W_TAXONOMY_ADDED", f"{kind} {x['id']}: {x['type']!r} is newer than taxonomy {v}")
            known[kind].append(x)
    for e in known["event"]:
        if not c0 <= e["t_us"] < c1:
            rep.add("E_EVENT_RANGE", f"event {e['id']} at {e['t_us']} outside coverage")
        if e["type"] in hole_events and "hole" not in e:
            rep.add("E_HOLE", f"event {e['id']} ({e['type']}) needs a hole")
    for s in known["span"]:
        if s["t1_us"] <= s["t0_us"]:
            rep.add("E_SPAN", f"span {s['id']} ends before it starts")
        if s["t0_us"] < c0 or s["t1_us"] > c1:
            rep.add("E_EVENT_RANGE", f"span {s['id']} {s['t0_us']}..{s['t1_us']} outside coverage")
        if s["type"] == "ignore":
            if s.get("reason") not in tax.ignore_reasons():
                rep.add("E_SPAN", f"span {s['id']}: ignore reason {s.get('reason')!r} not in "
                                  f"{list(tax.ignore_reasons())}")
        elif "reason" in s:
            rep.add("E_SPAN", f"span {s['id']}: only ignore spans carry a reason")

    issues, end = labels.grammar_state(events, tax)
    for g in issues:
        rep.add("E_GRAMMAR", f"{g.event_id or 'end'}: {g.message}")
    if end != labels.OFF:
        rep.add("W_OPEN_END", f"the labels end with the motor on or the bit in a hole ({end})")

    # kickback outside motor-on time
    track = rep.segments = labels.phase_segments(events, tax)
    on = [(s.t0_us, s.t1_us) for s in track if s.phase not in ("off", "unknown")]
    for e in events["events"]:
        if e["type"] == "kickback" and not any(a <= e["t_us"] < b for a, b in on):
            rep.add("W_KICKBACK_OFF", f"kickback {e['id']} while the motor is off")

    used = {e["hole"] for e in events["events"] if "hole" in e}
    listed = [h["hole"] for h in events["holes"]]
    if len(listed) != len(set(listed)):
        rep.add("E_HOLE", "holes[] lists a hole twice")
    for h in sorted(used - set(listed)):
        rep.add("E_HOLE", f"hole {h} has events but no holes[] entry")
    for h in sorted(set(listed) - used):
        rep.add("W_HOLE_UNUSED", f"holes[] entry {h} has no events")
    for h in events["holes"]:
        ot = h.get("other_text", {})
        _tax(tax, "material", h["material"], f"holes[{h['hole']}].material", rep, v, ot)
        for tag in h["outcome_tags"]:
            _tax(tax, "outcome_tag", tag, f"holes[{h['hole']}].outcome_tags", rep, v, ot, "outcome_tags")
        if "orientation" in h:
            _tax(tax, "orientation", h["orientation"], f"holes[{h['hole']}].orientation", rep, v)


# ---- data checks --------------------------------------------------------------------

def _check_files(meta: dict, folder: Path, rep: Report, max_bytes: int) -> set[str]:
    """sha256, sizes and presence; returns the data files that are usable."""
    good = set()
    for p in sorted(folder.iterdir()):
        if p.is_file() and p.stat().st_size > max_bytes:
            rep.add("E_FILE_SIZE", f"{p.name} is {p.stat().st_size} bytes (limit {max_bytes})")
        if p.is_file() and p.name not in meta["files"] and p.name not in (META_FILE, EVENTS_FILE):
            rep.add("W_EXTRA_FILE", f"{p.name} is not listed in meta.files")
    for name, info in sorted(meta["files"].items()):
        p = folder / name
        if not p.is_file():
            rep.add("E_MISSING_FILE", f"{name} is listed but missing")
            continue
        size = p.stat().st_size
        if size != info["bytes"]:
            rep.add("E_BYTES", f"{name}: {size} bytes, meta says {info['bytes']}")
        if sha256_file(p) != info["sha256"]:
            rep.add("E_SHA", f"{name}: sha256 mismatch")
            continue
        good.add(name)
    return good


def _check_imu(meta: dict, folder: Path, rep: Report):
    try:
        df = read_imu_csv(folder / IMU_FILE)
    except Exception as e:  # noqa: BLE001 - any parse failure is a format error
        rep.add("E_IMU_FORMAT", f"{IMU_FILE}: {e}")
        return None
    if len(df) < 2:
        rep.add("E_IMU_FORMAT", f"{IMU_FILE}: fewer than two samples")
        return None
    axes = df[list(IMU_AXES)].to_numpy()
    if axes.min() < -32768 or axes.max() > 32767:
        rep.add("E_IMU_FORMAT", f"{IMU_FILE}: counts outside int16")
    if df["flags"].min() < 0 or df["flags"].max() > 0xFF:
        rep.add("E_IMU_FORMAT", f"{IMU_FILE}: flags outside 0..255")
    seq = df["seq"].to_numpy(np.int64)
    t = df["t_us"].to_numpy(np.int64)
    ok = True
    if seq.min() < 0 or seq.max() > 0xFFFFFFFF:
        rep.add("E_IMU_FORMAT", "seq outside uint32")
    if np.any(np.diff(seq) <= 0):
        rep.add("E_SEQ_ORDER", f"seq not strictly increasing at row {int(np.argmax(np.diff(seq) <= 0)) + 1}")
        ok = False
    if np.any(np.diff(t) <= 0):
        rep.add("E_T_ORDER", f"t_us not strictly increasing at row {int(np.argmax(np.diff(t) <= 0)) + 1}")
        ok = False
    if not ok:
        return None

    im = meta["imu"]
    span = int(seq[-1] - seq[0])
    odr = span / ((t[-1] - t[0]) / 1e6)
    nominal = float(im["odr_nominal_hz"])
    if abs(odr - nominal) > ODR_TOL * nominal:
        rep.add("E_ODR", f"measured ODR {odr:.2f} Hz is more than {ODR_TOL:.0%} off nominal {nominal}")
    if abs(odr - float(im["odr_measured_hz"])) > 0.005 * odr:
        rep.add("W_ODR_META", f"meta odr_measured_hz {im['odr_measured_hz']} but data gives {odr:.2f}")
    n = len(seq)
    filled = int(((df["flags"].to_numpy() & FLAG_FILLED) != 0).sum())
    missing = span + 1 - n
    if filled > FILLED_WARN * n:
        rep.add("W_FILLED", f"{filled / n:.1%} of IMU samples are filled (> {FILLED_WARN:.0%})")
    if missing > MISSING_WARN * (span + 1):
        rep.add("W_MISSING", f"{missing / (span + 1):.1%} of IMU samples are missing (> {MISSING_WARN:.0%})")
    g = meta["gaps"]
    if g["imu_filled_samples"] != filled or g["imu_missing_samples"] != missing:
        rep.add("W_GAPS_META", f"meta gaps filled/missing {g['imu_filled_samples']}/{g['imu_missing_samples']}"
                               f", data {filled}/{missing}")
    # bit 3 is set exactly on the first sample after a seq jump
    jump = np.diff(seq) > 1
    flagged = (df["flags"].to_numpy()[1:] & FLAG_GAP_BEFORE) != 0
    bad = np.nonzero(jump != flagged)[0]
    if len(bad) or df["flags"].iloc[0] & FLAG_GAP_BEFORE:
        rep.add("W_GAP_FLAG", f"gap flag (bit 3) does not match the seq jumps at {len(bad)} row(s)"
                              + (f", first row {int(bad[0]) + 1}" if len(bad) else ""))
    return t


def _check_audio(meta: dict, folder: Path, t_imu, rep: Report) -> None:
    import soundfile as sf

    am = meta["audio"]
    try:
        info = sf.info(str(folder / am["file"]))
    except Exception as e:  # noqa: BLE001
        rep.add("E_AUDIO_FORMAT", f"{am['file']}: {e}")
        return
    if info.samplerate != int(round(am["rate_nominal_hz"])):
        rep.add("E_AUDIO_FORMAT", f"sample rate {info.samplerate} != {am['rate_nominal_hz']}")
    if info.channels != len(am["channels"]):
        rep.add("E_AUDIO_FORMAT", f"{info.channels} channels, meta lists {len(am['channels'])}")
    if info.subtype != "PCM_16":
        rep.add("E_AUDIO_FORMAT", f"subtype {info.subtype}, expected PCM_16")
    try:
        sync_df = read_sync_csv(folder / SYNC_FILE)
    except Exception as e:  # noqa: BLE001
        rep.add("E_SYNC_FORMAT", f"{SYNC_FILE}: {e}")
        return
    fr = sync_df["frame"].to_numpy(np.int64)
    if len(fr) < 2 or np.any(np.diff(fr) <= 0) or np.any(np.diff(sync_df["t_us"].to_numpy()) <= 0):
        rep.add("E_SYNC_FORMAT", f"{SYNC_FILE}: needs two or more rows with increasing frame and t_us")
        return
    fit = SyncFit.fit(fr, sync_df["t_us"])
    if fit.residual_rms_us > SYNC_RESIDUAL_MAX_US:
        rep.add("E_SYNC", f"sync residual {fit.residual_rms_us:.0f} us rms > {SYNC_RESIDUAL_MAX_US:.0f}")
    ms = meta["sync"]
    ends = np.array([0, info.frames])
    drift = np.abs(SyncFit(ms["a_us"], ms["b_us_per_frame"]).t_of(ends) - fit.t_of(ends))
    if drift.max() > SYNC_RESIDUAL_MAX_US:
        rep.add("W_SYNC_META", f"meta.sync differs from the {SYNC_FILE} fit by {drift.max():.0f} us")
    if t_imu is not None:
        d_audio = info.frames * fit.b_us_per_frame
        d_imu = float(t_imu[-1] - t_imu[0])
        if abs(d_audio - d_imu) > DURATION_TOL * max(d_audio, d_imu):
            rep.add("E_DURATION", f"audio {d_audio / 1e6:.3f} s vs IMU {d_imu / 1e6:.3f} s (> {DURATION_TOL:.0%})")


def _check_level_derived(meta: dict, events: dict, folder: Path, tax: Taxonomy, rep: Report) -> None:
    try:
        lvl = read_level_csv(folder / LEVEL_FILE)
    except Exception as e:  # noqa: BLE001
        rep.add("E_LEVEL_FORMAT", f"{LEVEL_FILE}: {e}")
        return
    if np.any(np.diff(lvl["t_us"].to_numpy()) <= 0):
        rep.add("E_LEVEL_FORMAT", f"{LEVEL_FILE}: t_us not strictly increasing")
        return
    # the zero columns hold the zero in effect on each row, so a row without ZERO_SET has none
    no_zero = (lvl["flags"].to_numpy() & LEVEL_FLAG_ZERO_SET) == 0
    stray = no_zero & (lvl[list(LEVEL_ZERO_COLUMNS)].to_numpy() != 0).any(axis=1)
    if stray.any():
        rep.add("E_LEVEL_ZERO", f"{LEVEL_FILE}: {int(stray.sum())} row(s) without ZERO_SET carry a zero, "
                                f"first row {int(np.argmax(stray)) + 1}")
    derived, holes = labels.compute_derived(lvl, events, meta, tax)
    md = meta["derived"]
    if md["orientation"] != derived["orientation"]:
        rep.add("E_DERIVED", f"derived.orientation {md['orientation']}, recomputed {derived['orientation']}")
    if not _close(md["off_angle_deg"], derived["off_angle_deg"]):
        rep.add("E_DERIVED", f"derived.off_angle_deg {md['off_angle_deg']}, recomputed {derived['off_angle_deg']}")
    if sorted(md["tags"]) != sorted(derived["tags"]):
        rep.add("E_DERIVED", f"derived.tags {md['tags']}, recomputed {derived['tags']}")
    for h in events["holes"]:
        r = holes.get(h["hole"])
        if r is None:
            continue
        if h["off_angle_source"] == "computed" and not _close(h["off_angle_deg"], r["off_angle_deg"]):
            rep.add("E_DERIVED", f"hole {h['hole']} off_angle_deg {h['off_angle_deg']}, "
                                 f"recomputed {r['off_angle_deg']}")
        if "orientation" in h and h["orientation"] != r["orientation"]:
            rep.add("E_DERIVED", f"hole {h['hole']} orientation {h['orientation']}, recomputed {r['orientation']}")


def _check_device_events(folder: Path, rep: Report) -> None:
    try:
        de = read_device_events_csv(folder / DEVICE_EVENTS_FILE)
    except Exception as e:  # noqa: BLE001
        rep.add("E_DEVICE_EVENTS", f"{DEVICE_EVENTS_FILE}: {e}")
        return
    if np.any(np.diff(de["t_us"].to_numpy()) < 0):
        rep.add("E_DEVICE_EVENTS", f"{DEVICE_EVENTS_FILE}: t_us decreases")
    unknown = sorted(set(de["code"]) - set(DEVICE_EVENT_CODES))
    if unknown:
        rep.add("E_DEVICE_EVENTS", f"{DEVICE_EVENTS_FILE}: unknown codes {unknown}")
    if (de["text"].str.len() > DEVICE_TEXT_MAX).any():
        rep.add("E_DEVICE_EVENTS", f"{DEVICE_EVENTS_FILE}: text longer than {DEVICE_TEXT_MAX} characters")
    for i, text in enumerate(de["text"]):
        if _looks_pii(text):
            rep.add("E_PII_DATA", f"{DEVICE_EVENTS_FILE} row {i + 1}: text looks like an email or MAC address")


def _close(a, b) -> bool:
    if a is None or b is None:
        return a is None and b is None
    return abs(a - b) <= DERIVED_TOL_DEG


# ---- entry points ------------------------------------------------------------------

def validate_recording(path: str | Path, *, taxonomy: Taxonomy | None = None, light: bool = False,
                       max_file_bytes: int | None = None, with_fingerprint: bool = True) -> Report:
    """Every check of the contract on one recording folder (light: meta/events only).

    with_fingerprint=False leaves Report.fingerprint empty (it reads every file of the folder
    outside a clean git checkout). Never raises on bad data: an unexpected failure is reported
    as E_INTERNAL.
    """
    tax = taxonomy or load_taxonomy()
    folder = Path(path)
    rep = Report(str(folder), light=light, docs={META_FILE: None, EVENTS_FILE: None})
    try:
        _validate(folder, tax, light, max_file_bytes, with_fingerprint, rep)
    except Exception as e:  # noqa: BLE001 - one malformed recording must not stop the others
        rep.add("E_INTERNAL", f"validator failed: {type(e).__name__}: {e}")
    return rep


def _validate(folder: Path, tax: Taxonomy, light: bool, max_file_bytes: int | None, with_fingerprint: bool,
              rep: Report) -> None:
    docs, raw = {}, {}
    for name in (META_FILE, EVENTS_FILE):
        p = folder / name
        if not p.is_file():
            rep.add("E_JSON", f"{name} is missing")
            continue
        raw[name] = p.read_bytes()
        try:
            docs[name] = json.loads(raw[name].decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            rep.add("E_JSON", f"{name}: {e}")
    if len(raw) == 2 and with_fingerprint:
        rep.fingerprint = fingerprint(folder, raw)
    rep.docs.update(docs)
    if len(docs) < 2:
        return
    meta, events = docs[META_FILE], docs[EVENTS_FILE]
    rid = meta.get("recording_id") if isinstance(meta, dict) else None
    rep.recording_id = rid if isinstance(rid, str) and rid else None  # anything else fails the schema
    if not (isinstance(meta, dict) and isinstance(events, dict)):
        rep.add("E_JSON", "meta.json and events.json must be objects")
        return
    if not _check_meta_events(meta, events, folder, tax, rep) or light:
        return

    good = _check_files(meta, folder, rep, MAX_FILE_BYTES if max_file_bytes is None else max_file_bytes)
    t_imu = _check_imu(meta, folder, rep) if IMU_FILE in good else None
    if t_imu is not None:
        c0, c1 = events["coverage_us"]
        if c0 < t_imu[0] - 1_000_000 or c1 > t_imu[-1] + 1_000_000:
            rep.add("W_COVERAGE", f"coverage {c0}..{c1} reaches beyond the IMU data "
                                  f"{t_imu[0]}..{t_imu[-1]} by more than 1 s")
    if meta["audio"] is not None and {meta["audio"]["file"], SYNC_FILE} <= good:
        _check_audio(meta, folder, t_imu, rep)
    if LEVEL_FILE in good:
        _check_level_derived(meta, events, folder, tax, rep)
    # scanned whenever present: unlisted or not matching meta.files, it is in the repo all the same
    if (folder / DEVICE_EVENTS_FILE).is_file():
        _check_device_events(folder, rep)


def find_recordings(path: str | Path) -> list[Path]:
    """A recording folder itself, or every folder below path holding a meta.json, whatever its
    name (a misnamed folder is a recording that fails E_ID, not one to leave out)."""
    p = Path(path)
    if (p / META_FILE).is_file():
        return [p]
    return sorted(m.parent for m in p.rglob(META_FILE))


def check_contract(repo_root: str | Path) -> list[Issue]:
    """The repo's taxonomy/ and schema/ copies must equal dbdata's (as JSON), and the taxonomy
    must pass its self-check."""
    issues = [Issue("E_CONTRACT", f"taxonomy: {p}") for p in load_taxonomy().problems()]
    root = Path(repo_root)
    for rel, name in contract.REPO_COPIES.items():
        p = root / rel
        if not p.is_file():
            issues.append(Issue("E_CONTRACT", f"{rel} is missing"))
            continue
        try:
            same = read_json(p) == contract.load_json(name)
        except ValueError as e:
            issues.append(Issue("E_CONTRACT", f"{rel}: {e}"))
            continue
        if not same:
            issues.append(Issue("E_CONTRACT", f"{rel} differs from dbdata's {name}"))
    return issues


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m dbdata.validate", description=__doc__.split("\n")[0])
    ap.add_argument("paths", nargs="*", help="recording folders or trees holding them")
    ap.add_argument("--light", action="store_true", help="check meta.json and events.json only")
    ap.add_argument("--contract", metavar="REPO", help="check REPO's taxonomy/ and schema/ copies")
    ap.add_argument("--report", metavar="FILE", help="write a JSON report")
    ap.add_argument("--strict", action="store_true", help="warnings count as errors")
    ap.add_argument("-q", "--quiet", action="store_true", help="print only failing recordings")
    args = ap.parse_args(argv)
    if not args.paths and not args.contract:
        ap.print_usage(sys.stderr)
        return 2

    bad = False
    if args.contract:
        for i in check_contract(args.contract):
            print(f"contract: {i.code} {i.message}")
            bad = True
    reports = []
    folders = []
    empty = False
    for p in args.paths:
        found = find_recordings(p)
        if not found:  # a usage error; the other paths are still validated and reported
            print(f"{p}: no recordings found", file=sys.stderr)
            empty = True
        folders += found
    tax = load_taxonomy()
    for f in folders:
        r = validate_recording(f, taxonomy=tax, light=args.light)
        reports.append(r)
        failed = not r.ok or (args.strict and r.warnings)
        bad |= bool(failed)
        if failed or not args.quiet:
            status = "FAIL" if failed else ("ok, warnings" if r.warnings else "ok")
            print(f"{r.recording_id or f}: {status}")
            for i in r.issues:
                print(f"  {i.code} {i.message}")
    if args.report:
        Path(args.report).write_text(json.dumps({r.key: r.to_dict() for r in reports},
                                                indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if folders and not args.quiet:
        n_bad = sum(1 for r in reports if not r.ok)
        print(f"{len(reports)} recording(s), {n_bad} with errors")
    return 2 if empty else 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
