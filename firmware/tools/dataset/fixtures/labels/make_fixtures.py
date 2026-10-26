"""Expected output of dbdata.labels for the app's port (recorder/label/Phases.kt, Labels.kt).

The app derives phases, grammar issues and the derived labels (orientation, off angle) from
the labels with the same state machine as dbdata.labels; PhasesCrossCheckTest replays the
cases here and must give the same results exactly.

    cd firmware/tools/dataset
    .venv/bin/python fixtures/labels/make_fixtures.py            # (re)write phases.json
    .venv/bin/python fixtures/labels/make_fixtures.py --check    # compare with a fresh computation

The cases: the event orders of tests/test_labels.py, the labels of the autodetect fixtures
(truth and detector), and seeded random event sequences, valid and not (ties in time, a wrong
or missing hole, events outside the coverage), each with a synthetic LEVEL table (IMU_OK and
ZERO_SET rows mixed) and a level tolerance.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))

from dbdata import labels as L  # noqa: E402
from dbdata.contract import read_json  # noqa: E402
from dbdata.io import LEVEL_COLUMNS, LEVEL_FLAG_IMU_OK, LEVEL_FLAG_ZERO_SET, read_level_csv  # noqa: E402

OUT = HERE / "phases.json"
AUTODETECT = HERE.parent / "autodetect"
SCHEMA = "dbdata.labels.fixture/1"
GRAMMAR = ("motor_on", "contact", "breakthrough", "retract", "motor_off")


def us(s: float) -> int:
    return int(round(s * 1e6))


def doc(evs, c0=0, c1=10_000_000) -> dict:
    """An events document around (type, t_us[, hole]) tuples."""
    out = []
    for i, e in enumerate(evs):
        d = {"id": f"e{i + 1}", "type": e[0], "t_us": int(e[1]), "source": "auto", "confidence": 1.0}
        if len(e) > 2 and e[2] is not None:
            d["hole"] = int(e[2])
        out.append(d)
    return {"schema": "drillbalancer.events/1", "taxonomy_version": 1, "recording_id": "20261026T143012Z_k7q2_9f3a",
            "revision": 1, "coverage_us": [int(c0), int(c1)], "events": out, "spans": [], "holes": []}


def hand_cases() -> list[tuple[str, dict]]:
    s = us
    return [
        ("one_through_hole", doc([("motor_on", s(1)), ("contact", s(2), 1), ("breakthrough", s(5), 1), ("motor_off", s(6))])),
        ("windows_truncated", doc([("motor_on", s(1)), ("contact", s(1.1), 1), ("breakthrough", s(1.12), 1),
                                   ("motor_off", s(1.2)), ("motor_on", s(1.4)), ("motor_off", s(1.5))])),
        ("holes_and_retract", doc([("motor_on", s(0.5)), ("contact", s(1), 1), ("breakthrough", s(2), 1),
                                   ("contact", s(3), 2), ("retract", s(3.5), 2), ("contact", s(4), 3),
                                   ("breakthrough", s(5), 3), ("motor_off", s(8))])),
        ("invalid_until_motor_off", doc([("motor_on", s(1)), ("breakthrough", s(2), 1), ("contact", s(3), 1),
                                         ("motor_off", s(4)), ("motor_on", s(6)), ("motor_off", s(7))])),
        ("stuck_restart", doc([("motor_on", s(1)), ("contact", s(2), 1), ("motor_off", s(3)), ("motor_on", s(4)),
                               ("retract", s(5), 1), ("motor_off", s(6))])),
        ("stuck_pulled_out", doc([("motor_on", s(1)), ("contact", s(2), 1), ("motor_off", s(3)),
                                  ("retract", s(3.3), 1)])),
        ("open_in_hole", doc([("motor_on", s(1)), ("contact", s(2), 1)])),
        ("open_motor_on", doc([("motor_on", s(1))])),
        ("kickback_ignored", doc([("motor_on", s(1)), ("contact", s(2), 1), ("kickback", s(2.5), 1),
                                  ("retract", s(2.8), 1), ("motor_off", s(4))])),
        ("outside_coverage", doc([("motor_on", s(0.5)), ("motor_off", s(12))], c0=s(1), c1=s(5))),
        ("contact_without_hole", doc([("motor_on", s(1)), ("contact", s(2)), ("retract", s(3)), ("motor_off", s(4))])),
        ("wrong_hole", doc([("motor_on", s(1)), ("contact", s(2), 1), ("retract", s(3), 2), ("motor_off", s(4), 1)])),
        ("empty", doc([])),
    ]


def random_case(rng: random.Random, i: int) -> dict:
    c0 = rng.randrange(0, 5_000_000)
    c1 = c0 + rng.randrange(1_000_000, 12_000_000)
    n = rng.randrange(0, 15)
    t = sorted(rng.randrange(c0 - 500_000, c1 + 500_000) for _ in range(n))
    for k in range(1, n):          # some ties in time
        if rng.random() < 0.1:
            t[k] = t[k - 1]
    evs = []
    state, hole, nxt = "off", None, 1
    allowed = {"off": ["motor_on"], "on": ["contact", "motor_off"], "hole": ["breakthrough", "retract", "motor_off"],
               "hole_off": ["motor_on", "retract"]}
    after = {("off", "motor_on"): "on", ("on", "contact"): "hole", ("on", "motor_off"): "off",
             ("hole", "breakthrough"): "on", ("hole", "retract"): "on", ("hole", "motor_off"): "hole_off",
             ("hole_off", "motor_on"): "hole", ("hole_off", "retract"): "off"}
    for k in range(n):
        if rng.random() < 0.12:
            typ = rng.choice(GRAMMAR + ("kickback",))
        else:
            typ = rng.choice(allowed[state])
        h = None
        if typ == "contact":
            hole, nxt = nxt, nxt + 1
            h = hole if rng.random() > 0.05 else None
        elif typ in ("breakthrough", "retract"):
            h = hole if rng.random() > 0.1 else rng.choice([None, (hole or 0) + 1])
        elif typ == "kickback":
            h = hole
        elif rng.random() < 0.03:
            h = 1
        evs.append((typ, t[k], h))
        state = after.get((state, typ), state)
    d = doc(evs, c0, c1)
    if rng.random() < 0.2:          # the document's order differs from the time order (ties keep it)
        rng.shuffle(d["events"])
    return d


def level_table(rng: random.Random, c0: int, c1: int) -> dict:
    rows = []
    elev = rng.uniform(-80, 80)
    t = c0 - 500_000
    while t < c1 + 500_000:
        elev = max(-89.0, min(89.0, elev + rng.gauss(0, 1.5)))
        flags = (LEVEL_FLAG_IMU_OK if rng.random() < 0.9 else 0) | (LEVEL_FLAG_ZERO_SET if rng.random() < 0.2 else 0) | 1
        zero = rng.randrange(-500, 500) if flags & LEVEL_FLAG_ZERO_SET else 0
        rows.append([t, 0, flags, int(round(elev * 100)) - zero, rng.randrange(-18000, 18000), 0, 0,
                     rng.randrange(-300, 300), zero, 0, 0])
        t += 250_000
    return {"columns": list(LEVEL_COLUMNS), "rows": rows}


def expected(d: dict, level: dict | None, meta: dict) -> dict:
    issues, end = L.grammar_state(d)
    segs = L.phase_segments(d)
    out = {"segments": [[s.t0_us, s.t1_us, s.phase, s.hole] for s in segs],
           "issues": [[g.index, g.event_id, g.message] for g in issues], "end": end}
    lvl = pd.DataFrame(level["rows"], columns=level["columns"]) if level is not None else None
    derived, holes = L.compute_derived(lvl, d, meta)
    out["derived"] = derived
    out["holes"] = {str(h): v for h, v in holes.items()}
    return out


def cases() -> list[dict]:
    rng = random.Random(20261026)
    out = []
    for name, d in hand_cases():
        c0, c1 = d["coverage_us"]
        out.append({"name": name, "events": d, "level": level_table(rng, c0, c1), "meta": {"level": {"tol_deg": 0.5}}})
    for fo in sorted(p.parent for p in AUTODETECT.glob("*/expected.json")):
        meta = read_json(fo / "meta.json")
        lvl = read_level_csv(fo / "level.csv.gz")
        level = {"columns": list(LEVEL_COLUMNS), "rows": lvl[list(LEVEL_COLUMNS)].astype(int).values.tolist()}
        m = {"level": meta["level"]}
        out.append({"name": f"{fo.name}/truth", "events": read_json(fo / "events.json"), "level": level, "meta": m})
        out.append({"name": f"{fo.name}/auto", "events": read_json(fo / "expected.json")["events"], "level": level, "meta": m})
    for i in range(250):
        d = random_case(rng, i)
        c0, c1 = d["coverage_us"]
        meta = {"level": {"tol_deg": rng.choice([0.5, 1.0, 2.0])}} if rng.random() < 0.9 else {}
        level = level_table(rng, c0, c1) if rng.random() < 0.95 else None
        out.append({"name": f"random_{i:03d}", "events": d, "level": level, "meta": meta})
    for c in out:
        c["expected"] = expected(c["events"], c["level"], c["meta"])
    return out


def build() -> dict:
    return {"schema": SCHEMA, "cases": cases()}


def dump(doc: dict) -> str:
    """Compact: one case per line (stable diffs, a small file)."""
    lines = [json.dumps(c, ensure_ascii=False, separators=(",", ":")) for c in doc["cases"]]
    return '{"schema":' + json.dumps(doc["schema"]) + ',"cases":[\n' + ",\n".join(lines) + "\n]}\n"


def check_all() -> list[str]:
    if not OUT.is_file():
        return [f"{OUT.name} is missing"]
    want = build()
    if OUT.read_text(encoding="utf-8") != dump(want):
        return [f"{OUT.name} differs from dbdata.labels"]
    return []


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="compare with a fresh computation")
    a = ap.parse_args(argv)
    if a.check:
        probs = check_all()
        for p in probs:
            print(p)
        print("ok" if not probs else "STALE")
        return 1 if probs else 0
    OUT.write_text(dump(build()), encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
