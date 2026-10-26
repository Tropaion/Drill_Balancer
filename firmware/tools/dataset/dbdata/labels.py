"""Phases from events: the one fixed state machine, guards, overlays, derived labels.

The app's Phases.kt implements the same table; keep them in step.

Grammar (labels.GRAMMAR): a motor run is motor_on, holes, motor_off; a hole
is contact, then breakthrough or retract. The motor may stop with the bit in
the hole (stuck bit, aborted hole): motor_on then restarts in that hole, or
retract pulls the bit out with the motor off. The labels may end in any state
(a take stopped by max_s, the battery or a lost link); the validator only
warns. An event the grammar does not allow makes everything from the previous
event to the next motor_off 'unknown'. Outside events.coverage_us the phase
is 'unknown' too.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .contract import Taxonomy, load_taxonomy
from .io import LEVEL_FLAG_IMU_OK, LEVEL_FLAG_ZERO_SET

POLICIES = ("ignore", "hard", "soft")
GRAMMAR: str = load_taxonomy().doc["grammar"]  # the text form of _NEXT, kept in the taxonomy

OFF, ON, HOLE, HOLE_OFF, _ERR = "off", "on", "hole", "hole_off", "err"
# (state, event) -> next state
_NEXT = {
    (OFF, "motor_on"): ON,
    (ON, "contact"): HOLE,
    (ON, "motor_off"): OFF,
    (HOLE, "breakthrough"): ON,
    (HOLE, "retract"): ON,
    (HOLE, "motor_off"): HOLE_OFF,      # the motor stops with the bit in the hole
    (HOLE_OFF, "motor_on"): HOLE,       # restart in the hole
    (HOLE_OFF, "retract"): OFF,         # bit pulled out with the motor off
}


@dataclass(frozen=True)
class Segment:
    t0_us: int
    t1_us: int
    phase: str
    hole: int = 0


@dataclass
class GrammarIssue:
    index: int        # position in the time-sorted grammar events
    event_id: str
    message: str


def grammar_events(events_doc: dict, taxonomy: Taxonomy | None = None) -> list[dict]:
    """The grammar's point events, stable-sorted by t_us (kickback and unknown types left out)."""
    tax = taxonomy or load_taxonomy()
    g = tax.grammar_events()
    evs = [e for e in events_doc.get("events", []) if e.get("type") in g]
    return sorted(evs, key=lambda e: e["t_us"])


def _walk(evs: list[dict]):
    """The state machine over time-sorted grammar events, with its one error rule.

    Yields (index, event, state, after, allowed): the state before and after
    the event. An event the table does not allow (allowed False; every event
    of an invalid stretch counts as not allowed) puts the machine in _ERR
    until the next motor_off, which resynchronises it to OFF. grammar_state
    and phase_segments both walk this, so they cannot disagree.
    """
    state = OFF
    for i, e in enumerate(evs):
        after = None if state == _ERR else _NEXT.get((state, e["type"]))
        allowed = after is not None
        if not allowed:
            after = OFF if e["type"] == "motor_off" else _ERR
        yield i, e, state, after, allowed
        state = after


def grammar_state(events_doc: dict, taxonomy: Taxonomy | None = None) -> tuple[list[GrammarIssue], str]:
    """Order and hole violations, and the state the labels end in (OFF, ON, HOLE or HOLE_OFF)."""
    issues: list[GrammarIssue] = []
    state, hole = OFF, None
    for i, e, before, state, allowed in _walk(grammar_events(events_doc, taxonomy)):
        t = e["type"]
        if not allowed:
            if before != _ERR:  # an invalid stretch is reported at its first event only
                issues.append(GrammarIssue(i, e.get("id", "?"), f"{t} at {e['t_us']} not allowed while {before}"))
            continue
        if t == "contact":
            hole = e.get("hole")
            if hole is None:
                issues.append(GrammarIssue(i, e.get("id", "?"), "contact without hole"))
        elif t in ("breakthrough", "retract") and e.get("hole") != hole:
            issues.append(GrammarIssue(i, e.get("id", "?"),
                                       f"{t} hole {e.get('hole')} does not close contact hole {hole}"))
        elif t in ("motor_on", "motor_off") and "hole" in e:
            issues.append(GrammarIssue(i, e.get("id", "?"), f"{t} must not carry a hole"))
    return issues, (OFF if state == _ERR else state)


def check_grammar(events_doc: dict, taxonomy: Taxonomy | None = None) -> list[GrammarIssue]:
    """Order and hole violations; empty when the events follow the grammar (an open end is fine)."""
    return grammar_state(events_doc, taxonomy)[0]


def phase_segments(events_doc: dict, taxonomy: Taxonomy | None = None) -> list[Segment]:
    """Contiguous phase segments over coverage_us (clipped to it), merged."""
    tax = taxonomy or load_taxonomy()
    w = {k: int(round(v * 1000)) for k, v in tax.windows_ms.items()}
    c0, c1 = (int(x) for x in events_doc["coverage_us"])
    evs = grammar_events(events_doc, tax)
    t_lo = min([c0] + [e["t_us"] for e in evs])
    t_hi = max([c1] + [e["t_us"] + 1 for e in evs])

    segs: list[Segment] = []

    def add(t0, t1, phase, hole=0):
        if t1 > t0:
            segs.append(Segment(t0, t1, phase, hole))

    def cut(at):  # drop everything from `at` on
        while segs and segs[-1].t0_us >= at:
            segs.pop()
        if segs and segs[-1].t1_us > at:
            s = segs.pop()
            segs.append(Segment(s.t0_us, at, s.phase, s.hole))

    prev_t, err_from, hole, spin_end, after = t_lo, None, 0, t_lo, OFF
    add(t_lo, evs[0]["t_us"] if evs else t_hi, "off")
    for i, e, state, after, allowed in _walk(evs):  # state, after: the machine before and after e
        t, typ = e["t_us"], e["type"]
        nxt = evs[i + 1] if i + 1 < len(evs) else None
        t_next = nxt["t_us"] if nxt else t_hi
        # cutting ends breakthrough_pre before a breakthrough
        bt = max(t, t_next - w["breakthrough_pre"]) if nxt is not None and nxt["type"] == "breakthrough" \
            else t_next
        if not allowed:
            if state != _ERR:
                cut(prev_t)
                err_from = prev_t
            prev_t = t
            if typ != "motor_off":
                continue
            add(err_from, t, "unknown")  # then the resynchronising motor_off (after == OFF) as usual
        prev_t = t
        if typ == "contact":
            hole = int(e.get("hole") or 0)
            add(t, bt, "drilling", hole)
            add(bt, t_next, "breakthrough", hole)
        elif typ == "motor_on":
            in_hole = state == HOLE_OFF
            end = min(t + w["spinup_max"], bt)
            add(t, end, "spinup", hole if in_hole else 0)
            if in_hole:  # restart in the hole: cutting again after the spinup
                add(end, bt, "drilling", hole)
                add(bt, t_next, "breakthrough", hole)
            else:
                add(end, t_next, "free_run")
        elif typ == "breakthrough":
            end = min(t + w["breakthrough_post"], t_next)
            add(t, end, "breakthrough", hole)
            add(end, t_next, "free_run")
        elif typ == "retract" and state == HOLE:
            add(t, t_next, "free_run")
        else:  # motor_off, or retract with the motor off: the spindown runs on
            h = hole if after == HOLE_OFF else 0
            if typ == "motor_off":  # every segment also ends at the next event (a motor_on)
                spin_end = t + w["spindown_max"]
            add(t, min(spin_end, t_next), "spindown", h)
            add(max(t, spin_end), t_next, "off", h)
    if after == _ERR:  # the labels end inside an invalid stretch
        add(err_from, t_hi, "unknown")

    out: list[Segment] = []
    for s in segs:
        a, b = max(s.t0_us, c0), min(s.t1_us, c1)
        if b <= a:
            continue
        if out and out[-1].phase == s.phase and out[-1].hole == s.hole and out[-1].t1_us == a:
            out[-1] = Segment(out[-1].t0_us, b, s.phase, s.hole)
        else:
            out.append(Segment(a, b, s.phase, s.hole))
    return out


def phase_durations_s(events_doc: dict, taxonomy: Taxonomy | None = None,
                      segments: list[Segment] | None = None) -> dict[str, float]:
    """Seconds per phase inside coverage (segments: phase_segments of events_doc, if already known)."""
    d: dict[str, float] = {}
    for s in phase_segments(events_doc, taxonomy) if segments is None else segments:
        d[s.phase] = d.get(s.phase, 0.0) + (s.t1_us - s.t0_us) / 1e6
    return d


@dataclass
class PhaseTrack:
    """Per-sample labels at t_us.

    codes index taxonomy.phases; weight is the loss weight (0 in guards under
    'ignore', on 'unknown' and under ignore spans); soft is (N, P) under 'soft'.
    """

    t_us: np.ndarray
    phases: tuple[str, ...]
    codes: np.ndarray
    hole: np.ndarray
    guard: np.ndarray
    weight: np.ndarray
    soft: np.ndarray | None
    overlays: dict[str, np.ndarray]
    boundaries_us: np.ndarray

    def ids(self) -> np.ndarray:
        return np.asarray(self.phases, dtype=object)[self.codes]


def _lookup(segs: list[Segment], t: np.ndarray, phases: tuple[str, ...]):
    """codes and hole at t; 'unknown' outside the segments."""
    unknown = phases.index("unknown")
    codes = np.full(len(t), unknown, dtype=np.int8)
    hole = np.zeros(len(t), dtype=np.int16)
    if not segs:
        return codes, hole
    starts = np.array([s.t0_us for s in segs], dtype=np.int64)
    ends = np.array([s.t1_us for s in segs], dtype=np.int64)
    seg_codes = np.array([phases.index(s.phase) for s in segs], dtype=np.int8)
    seg_holes = np.array([s.hole for s in segs], dtype=np.int16)
    i = np.searchsorted(starts, t, side="right") - 1
    ok = (i >= 0) & (t < ends[np.clip(i, 0, None)])
    codes[ok] = seg_codes[i[ok]]
    hole[ok] = seg_holes[i[ok]]
    return codes, hole


def boundaries(segs: list[Segment]) -> np.ndarray:
    """Times where the phase changes, coverage edges included."""
    if not segs:
        return np.zeros(0, dtype=np.int64)
    b = [segs[0].t0_us]
    for p, s in zip(segs, segs[1:]):
        if p.t1_us != s.t0_us:
            b += [p.t1_us, s.t0_us]
        elif p.phase != s.phase:
            b.append(s.t0_us)
    b.append(segs[-1].t1_us)
    return np.unique(np.asarray(b, dtype=np.int64))


def overlay_masks(t_us, events_doc: dict, taxonomy: Taxonomy | None = None) -> dict[str, np.ndarray]:
    """One bool mask per span type (and 'ignore/<reason>'), [t0_us, t1_us)."""
    tax = taxonomy or load_taxonomy()
    t = np.asarray(t_us, dtype=np.int64)
    masks = {k: np.zeros(len(t), dtype=bool) for k in tax.span_events}
    for r in tax.ignore_reasons():
        masks["ignore/" + r] = np.zeros(len(t), dtype=bool)
    for s in events_doc.get("spans", []):
        if s.get("type") not in masks:
            continue
        m = (t >= s["t0_us"]) & (t < s["t1_us"])
        masks[s["type"]] |= m
        key = "ignore/" + str(s.get("reason"))
        if s["type"] == "ignore" and key in masks:
            masks[key] |= m
    return masks


def span_intervals(events_doc: dict, taxonomy: Taxonomy | None = None) -> dict[str, list[tuple[int, int]]]:
    """Merged, sorted [t0_us, t1_us) intervals per span type."""
    tax = taxonomy or load_taxonomy()
    raw: dict[str, list[tuple[int, int]]] = {k: [] for k in tax.span_events}
    for s in events_doc.get("spans", []):
        if s.get("type") in raw and s["t1_us"] > s["t0_us"]:
            raw[s["type"]].append((int(s["t0_us"]), int(s["t1_us"])))
    out = {}
    for k, iv in raw.items():
        merged: list[tuple[int, int]] = []
        for a, b in sorted(iv):
            if merged and a <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(merged[-1][1], b))
            else:
                merged.append((a, b))
        out[k] = merged
    return out


def covered_fraction(intervals, t0: int, t1: int) -> float:
    """Fraction of [t0, t1) covered by disjoint intervals."""
    if t1 <= t0:
        return 0.0
    cov = sum(max(0, min(t1, b) - max(t0, a)) for a, b in intervals)
    return cov / (t1 - t0)


def phase_track(t_us, events_doc: dict, taxonomy: Taxonomy | None = None,
                boundary_policy: str = "ignore", guard_ms: float | None = None) -> PhaseTrack:
    """Phase labels at the sample times t_us (device us)."""
    if boundary_policy not in POLICIES:
        raise ValueError(f"boundary_policy must be one of {POLICIES}")
    tax = taxonomy or load_taxonomy()
    t = np.asarray(t_us, dtype=np.int64)
    phases = tax.phases
    segs = phase_segments(events_doc, tax)
    codes, hole = _lookup(segs, t, phases)
    b = boundaries(segs)
    g_us = (tax.guard_ms if guard_ms is None else guard_ms) * 1000.0

    # nearest boundary and the phases on either side of it
    guard = np.zeros(len(t), dtype=bool)
    near = np.zeros(len(t), dtype=np.int64)
    dist = np.full(len(t), np.inf)
    if len(b):
        j = np.clip(np.searchsorted(b, t), 0, len(b) - 1)
        jm = np.clip(j - 1, 0, len(b) - 1)
        dj, djm = np.abs(t - b[j]), np.abs(t - b[jm])
        near = np.where(dj <= djm, b[j], b[jm])
        dist = np.minimum(dj, djm).astype(np.float64)
        guard = dist < g_us

    weight = np.ones(len(t), dtype=np.float32)
    soft = None
    if boundary_policy == "ignore":
        weight[guard] = 0.0
    elif boundary_policy == "soft":
        soft = np.zeros((len(t), len(phases)), dtype=np.float32)
        soft[np.arange(len(t)), codes] = 1.0
        if guard.any():
            gi = np.nonzero(guard)[0]
            before, _ = _lookup(segs, near[gi] - 1, phases)
            after, _ = _lookup(segs, near[gi], phases)
            w_after = np.clip(0.5 + (t[gi] - near[gi]) / (2.0 * g_us), 0.0, 1.0).astype(np.float32)
            soft[gi] = 0.0
            np.add.at(soft, (gi, before), 1.0 - w_after)
            np.add.at(soft, (gi, after), w_after)

    overlays = overlay_masks(t, events_doc, tax)
    weight[codes == phases.index("unknown")] = 0.0
    weight[overlays["ignore"]] = 0.0
    return PhaseTrack(t, phases, codes, hole, guard, weight, soft, overlays, b)


# ---- coarse classes -------------------------------------------------------------

def map_to_level(ids, depth: int):
    """Truncates hierarchical ids to depth components: wood/softwood -> wood at depth 1.

    Accepts a single id or a sequence; 'unknown'/'other' stay as they are.
    """
    def one(x):
        return x if not isinstance(x, str) else "/".join(x.split("/")[:depth])
    if isinstance(ids, str) or ids is None:
        return one(ids)
    return [one(x) for x in ids]


def coarse_phases(ids, taxonomy: Taxonomy | None = None):
    """Maps phase ids through taxonomy.coarse.phase (e.g. drilling -> cutting)."""
    tax = taxonomy or load_taxonomy()
    if isinstance(ids, str):
        return tax.coarse_phase(ids)
    return [tax.coarse_phase(x) for x in ids]


# ---- derived labels -------------------------------------------------------------

def orientation_of(elevation_deg: float | None, taxonomy: Taxonomy | None = None) -> str:
    tax = taxonomy or load_taxonomy()
    if elevation_deg is None or not np.isfinite(elevation_deg):
        return "unknown"
    if abs(elevation_deg) < tax.orientation_wall_deg:
        return "wall"
    if elevation_deg < -tax.orientation_vertical_deg:
        return "floor"
    if elevation_deg > tax.orientation_vertical_deg:
        return "ceiling"
    return "angled"


def off_angle_tags(off_angle_deg: float | None, meta: dict) -> list[str]:
    """['off_angle'] when the off angle is above the device tolerance meta.level.tol_deg."""
    tol = float(meta.get("level", {}).get("tol_deg", np.inf))
    return ["off_angle"] if off_angle_deg is not None and off_angle_deg > tol else []


def absolute_elevation_deg(level_df) -> np.ndarray:
    """Bit elevation above horizontal per level row: the device reports elevation_cdeg relative
    to its zero while ZERO_SET is on, and zero_elevation_cdeg is the zero in effect on that row."""
    flags = level_df["flags"].to_numpy(np.int64)
    zero = np.where((flags & LEVEL_FLAG_ZERO_SET) != 0, level_df["zero_elevation_cdeg"].to_numpy(np.float64), 0.0)
    return (level_df["elevation_cdeg"].to_numpy(np.float64) + zero) / 100.0


def compute_derived(level_df, events_doc: dict, meta: dict,
                    taxonomy: Taxonomy | None = None) -> tuple[dict, dict[int, dict]]:
    """meta.derived and per-hole {hole: {orientation, off_angle_deg}} from LEVEL during drilling.

    Only rows with the IMU_OK flag count (the others hold no real angle).
    Elevation is absolute_elevation_deg (row by row, so a zero set, moved or
    cleared during the take is followed); the off angle is the median
    |deviation_cdeg| (the error the device's mode judges). Off angles are
    rounded to 0.01 deg.
    """
    tax = taxonomy or load_taxonomy()
    if level_df is None or len(level_df) == 0:
        t = np.zeros(0, dtype=np.int64)
        elev = dev = np.zeros(0)
    else:
        ok = (level_df["flags"].to_numpy(np.int64) & LEVEL_FLAG_IMU_OK) != 0
        t = level_df["t_us"].to_numpy(np.int64)[ok]
        elev = absolute_elevation_deg(level_df)[ok]
        dev = np.abs(level_df["deviation_cdeg"].to_numpy(np.float64)[ok]) / 100.0
    codes, hole = _lookup(phase_segments(events_doc, tax), t, tax.phases)
    drilling = codes == tax.phase_code("drilling")

    def summary(mask):
        if not mask.any():
            return {"orientation": "unknown", "off_angle_deg": None}
        return {"orientation": orientation_of(float(np.median(elev[mask])), tax),
                "off_angle_deg": round(float(np.median(dev[mask])), 2)}

    s = summary(drilling)
    holes = {}
    for h in sorted({int(e["hole"]) for e in events_doc.get("events", []) if e.get("hole")}):
        holes[h] = summary(drilling & (hole == h))
    return {"orientation": s["orientation"], "off_angle_deg": s["off_angle_deg"],
            "tags": off_angle_tags(s["off_angle_deg"], meta)}, holes
