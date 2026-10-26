"""Aligned IMU + audio windows for training.

IMU: cut by searchsorted on t_us, padded/trimmed to round(win_s * odr_nominal)
samples; filled and padded samples stay in and are marked in fill_mask.
Audio: the window's start time goes through the inverse sync fit to a frame,
then round(win_s * rate_nominal) frames. A window is invalid when it overlaps
an IMU gap (a seq jump, a gap flag or meta.gaps.transport_gaps), an audio
gap or muted span, has more than max_filled filled samples, too few IMU
samples, or audio outside the file. Overlays are the exact fraction of the
window each span type covers; any ignore span zeroes the weight.
"""

from __future__ import annotations

from typing import Iterator

import numpy as np

from . import labels
from .contract import SESSION_FIELDS, Taxonomy, load_taxonomy, session_value
from .io import FLAG_GAP_BEFORE, IMU_AXES, Recording

STREAMS = ("imu", "audio")
LABEL_RULES = ("centre", "majority")


def _overlaps(spans, t0, t1) -> bool:
    return any(a < t1 and b > t0 for a, b in spans)


def window_starts(rec: Recording, win_s: float, hop_s: float) -> np.ndarray:
    """Start times (us) of windows inside both the coverage and the IMU data."""
    t = rec.t_us
    c0, c1 = rec.events["coverage_us"]
    lo = max(int(c0), int(t[0])) if len(t) else int(c0)
    hi = min(int(c1), int(t[-1]) + 1) if len(t) else int(c1)
    w, h = int(round(win_s * 1e6)), int(round(hop_s * 1e6))
    if h <= 0 or w <= 0:
        raise ValueError("win_s and hop_s must be > 0")
    if hi - lo < w:
        return np.zeros(0, dtype=np.int64)
    return np.arange(lo, hi - w + 1, h, dtype=np.int64)


def window_shapes(rec: Recording, win_s: float) -> dict[str, tuple[int, ...]]:
    """Shapes of one window's arrays: imu (N, 6), fill_mask (N,), audio (C, M) with audio."""
    n_imu = int(round(win_s * rec.odr_hz))
    out = {"imu": (n_imu, len(IMU_AXES)), "fill_mask": (n_imu,)}
    am = rec.meta.get("audio")
    if am:
        out["audio"] = (len(am["channels"]), int(round(win_s * float(am["rate_nominal_hz"]))))
    return out


def recording_labels(rec: Recording, hole: int = 0, taxonomy: Taxonomy | None = None) -> dict:
    """Recording-level labels, or a hole's: its holes[] entry overrides the session.

    Keys: the session's taxonomy fields (contract.SESSION_FIELDS: material,
    mode, bit_type, drill_type, motor), bit_diameter_mm, orientation,
    off_angle_deg, imu_profile, outcome_tags, hole.

    A hole's material (null inherits), outcome_tags, off angle and
    orientation replace the session's and meta.derived's; the derived tag
    off_angle follows the off angle that applies (labels.off_angle_tags).
    Deprecated ids come back as their replacements (Taxonomy.resolve).
    """
    tax = taxonomy or load_taxonomy()
    s = rec.meta["session"]
    d = rec.meta["derived"]
    out = {
        **{f: session_value(s, f) for f in SESSION_FIELDS},
        "bit_diameter_mm": s["bit"]["diameter_mm"],
        "orientation": d["orientation"],
        "off_angle_deg": d["off_angle_deg"],
        "imu_profile": rec.meta["imu"]["profile"],
        "outcome_tags": set(s.get("outcome_tags", [])) | set(d.get("tags", [])),
        "hole": int(hole),
    }
    for h in rec.events.get("holes", []):
        if h["hole"] == hole:
            if h.get("material"):
                out["material"] = h["material"]
            if h.get("off_angle_deg") is not None:
                out["off_angle_deg"] = h["off_angle_deg"]
            if h.get("orientation"):
                out["orientation"] = h["orientation"]
            out["outcome_tags"] = set(h.get("outcome_tags", [])) | set(
                labels.off_angle_tags(out["off_angle_deg"], rec.meta))
    for fld in (*SESSION_FIELDS, "orientation", "imu_profile"):
        out[fld] = tax.resolve(fld, out[fld])
    out["outcome_tags"] = sorted({tax.resolve("outcome_tag", x) for x in out["outcome_tags"]})
    return out


def imu_gap_spans(rec: Recording) -> list[tuple[int, int]]:
    """(t before, t after) of every IMU gap: a seq jump or a gap flag, plus meta.gaps.transport_gaps."""
    t = rec.t_us
    seq = rec.imu["seq"].to_numpy(np.int64)
    flags = rec.imu["flags"].to_numpy()
    idx = np.nonzero((np.diff(seq) > 1) | ((flags[1:] & FLAG_GAP_BEFORE) != 0))[0] + 1
    spans = [(int(t[i - 1]), int(t[i])) for i in idx]
    return spans + [tuple(g) for g in rec.meta["gaps"].get("transport_gaps", [])]


def iter_windows(rec: Recording, win_s: float = 1.0, hop_s: float = 0.5,
                 streams=STREAMS, *, taxonomy: Taxonomy | None = None,
                 boundary_policy: str = "ignore", label: str = "centre",
                 max_filled: float = 0.30, min_imu_frac: float = 0.95) -> Iterator[dict]:
    """Yields one dict per window.

    Keys: recording_id, t0_us, t1_us, imu (N, 6) float32 SI, fill_mask (N,)
    bool, audio (C, M) float32 in [-1, 1) (with 'audio' in streams), phase,
    phase_code, weight, soft (P,) under 'soft', hole, overlays {span: fraction},
    labels (recording_labels), valid, reason ('' when valid).
    """
    if label not in LABEL_RULES:
        raise ValueError(f"label must be one of {LABEL_RULES}")
    streams = tuple(streams)
    tax = taxonomy or load_taxonomy()
    want_audio = "audio" in streams
    if want_audio and (rec.audio is None or rec.sync is None):
        raise ValueError(f"{rec.id}: no audio; pass streams=('imu',)")

    t = rec.t_us
    imu = rec.imu[list(IMU_AXES)].to_numpy(np.float32)
    filled = rec.filled
    gap_spans = imu_gap_spans(rec)

    shapes = window_shapes(rec, win_s)
    n_imu = shapes["imu"][0]
    odr_meas = float(rec.meta["imu"].get("odr_measured_hz") or rec.odr_hz)
    w_us = int(round(win_s * 1e6))
    starts = window_starts(rec, win_s, hop_s)

    if label == "majority":
        sample_track = labels.phase_track(t, rec.events, tax, boundary_policy)
    centre_track = labels.phase_track(starts + w_us // 2, rec.events, tax, boundary_policy)
    spans = labels.span_intervals(rec.events, tax)
    unknown = tax.phase_code("unknown")
    no_samples_soft = np.eye(len(tax.phases), dtype=np.float32)[unknown]
    hole_labels: dict[int, dict] = {}  # recording_labels depends on the hole only

    if want_audio:
        am = rec.meta["audio"]
        n_aud = shapes["audio"][1]
        audio = rec.audio
        muted = [tuple(m) for m in am.get("muted_spans_us", [])]
        audio_gaps = [tuple(g) for g in rec.meta["gaps"].get("audio_gap_frames", [])]

    for k, t0 in enumerate(starts):
        t0 = int(t0)
        t1 = t0 + w_us
        i0, i1 = np.searchsorted(t, [t0, t1])
        seg = imu[i0:i1][:n_imu]
        n_real = len(seg)
        x = np.zeros(shapes["imu"], dtype=np.float32)
        x[:n_real] = seg
        mask = np.ones(n_imu, dtype=bool)
        mask[:n_real] = filled[i0:i0 + n_real]

        reason = ""
        if _overlaps(gap_spans, t0, t1):
            reason = "imu_gap"
        elif n_real < min_imu_frac * win_s * odr_meas - 1:
            reason = "imu_short"
        elif mask.mean() > max_filled:
            reason = "filled"

        w = {"recording_id": rec.id, "t0_us": t0, "t1_us": t1, "imu": x, "fill_mask": mask}

        if want_audio:
            f0 = int(np.round(rec.sync.frame_of(t0)))
            a = np.zeros(shapes["audio"], dtype=np.float32)
            lo, hi = max(f0, 0), min(f0 + n_aud, len(audio))
            if hi > lo:
                a[:, lo - f0:hi - f0] = audio[lo:hi].T.astype(np.float32) / 32768.0
            if not reason:
                if f0 < 0 or f0 + n_aud > len(audio):
                    reason = "audio_range"
                elif _overlaps(muted, t0, t1):
                    reason = "audio_muted"
                elif _overlaps(audio_gaps, f0, f0 + n_aud):
                    reason = "audio_gap"
            w["audio"] = a
            w["audio_frame0"] = f0

        if label == "centre":
            code = int(centre_track.codes[k])
            weight = float(centre_track.weight[k])
            hole = int(centre_track.hole[k])
            soft = centre_track.soft[k] if centre_track.soft is not None else None
        else:
            sl = slice(i0, i1)
            cs = sample_track.codes[sl]
            if len(cs):
                code = int(np.bincount(cs, minlength=len(tax.phases)).argmax())
                weight = float(sample_track.weight[sl].mean())
                hole = int(np.bincount(sample_track.hole[sl]).argmax())
                soft = sample_track.soft[sl].mean(axis=0) if sample_track.soft is not None else None
            else:  # no IMU sample in the window (a long gap): unknown, but every key stays
                code, weight, hole = unknown, 0.0, 0
                soft = no_samples_soft.copy() if sample_track.soft is not None else None

        w["overlays"] = {k: labels.covered_fraction(iv, t0, t1) for k, iv in spans.items()}
        if w["overlays"]["ignore"] > 0.0:
            weight = 0.0
        if hole not in hole_labels:
            hole_labels[hole] = recording_labels(rec, hole, tax)
        lbl = hole_labels[hole]
        w.update(phase=tax.phases[code], phase_code=code, weight=weight, hole=hole,
                 labels={**lbl, "outcome_tags": list(lbl["outcome_tags"])}, valid=not reason, reason=reason)
        if soft is not None:
            w["soft"] = soft
        yield w
