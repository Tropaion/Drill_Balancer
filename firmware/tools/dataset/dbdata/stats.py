"""Dataset statistics from index.csv: phase time, material x mode, review status.

    python -m dbdata.stats ROOT...    # one or more data repo roots (or index.csv files)

Valid recordings only, except in the totals. Deterministic Markdown.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

from .contract import SPLITS, Taxonomy, load_taxonomy


def split_list(s: str) -> list[str]:
    """The items of an index.csv list column (';'-separated; phases as 'phase=seconds')."""
    return [x for x in str(s).split(";") if x]


def phase_seconds(df) -> dict[str, float]:
    out: dict[str, float] = {}
    for s in df["phases"]:
        for item in split_list(s):
            k, _, v = item.partition("=")
            out[k] = out.get(k, 0.0) + float(v or 0)
    return out


def _tally(values) -> dict:
    return dict(sorted(Counter(values).items()))


def _tally_lists(series) -> dict:
    return _tally(x for s in series for x in split_list(s))


def compute(df, taxonomy: Taxonomy | None = None) -> dict:
    """Plain numbers behind stats.md."""
    tax = taxonomy or load_taxonomy()
    v = df[df["valid"] == 1]
    ph = phase_seconds(v)
    return {
        "recordings": int(len(df)),
        "valid": int(len(v)),
        "hours_valid": float(v["duration_s"].sum()) / 3600.0,
        "mb": float(df["bytes"].sum()) / 1e6,
        "splits": {s: (int(m.sum()), float(v.loc[m, "duration_s"].sum()) / 60.0)
                   for s in SPLITS for m in [v["split"] == s]},
        "phase_minutes": {p: ph.get(p, 0.0) / 60.0 for p in tax.phases},
        "material_mode_minutes": v.groupby(["material", "mode"])["duration_s"].sum().div(60.0).to_dict(),
        "review": _tally(v["review_status"]),
        "profiles": _tally(v["imu_profile"]),
        "orientation": _tally(v["orientation"]),
        "problems": _tally_lists(v["problems"]),
        "outcome_tags": _tally_lists(v["outcome_tags"]),
        "issues": _tally_lists(df["warnings"]),
    }


def _table(head: list[str], rows: list[list[str]]) -> list[str]:
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    out += ["| " + " | ".join(r) + " |" for r in rows]
    return out


def _counts(title: str, d: dict) -> list[str]:
    lines = ["", f"## {title}", ""]
    if not d:
        return lines + ["none"]
    return lines + _table(["id", "recordings"], [[k or "(empty)", str(n)] for k, n in d.items()])


def render_markdown(df, taxonomy: Taxonomy | None = None) -> str:
    s = compute(df, taxonomy)
    lines = ["# Dataset statistics", "",
             "Generated from index.csv by `dbdata.index`. Do not edit by hand.", "",
             "## Totals", ""]
    lines += _table(["recordings", "valid", "hours (valid)", "size (MB)"],
                    [[str(s["recordings"]), str(s["valid"]), f"{s['hours_valid']:.2f}", f"{s['mb']:.1f}"]])
    lines += ["", "## Splits (valid)", ""]
    lines += _table(["split", "recordings", "minutes"],
                    [[k, str(n), f"{m:.1f}"] for k, (n, m) in s["splits"].items()])
    lines += ["", "## Phase time (valid, minutes)", ""]
    lines += _table(["phase", "minutes"], [[p, f"{m:.2f}"] for p, m in s["phase_minutes"].items()])
    lines += ["", "## Material x mode (valid, minutes)", ""]
    mm = s["material_mode_minutes"]
    if mm:
        mats = sorted({k[0] for k in mm})
        modes = sorted({k[1] for k in mm})
        lines += _table(["material"] + modes,
                        [[m] + [f"{mm.get((m, mo), 0.0):.1f}" for mo in modes] for m in mats])
    else:
        lines.append("none")
    lines += _counts("Review status (valid)", s["review"])
    lines += _counts("IMU profiles (valid)", s["profiles"])
    lines += _counts("Orientation (valid)", s["orientation"])
    lines += _counts("Problems (valid)", s["problems"])
    lines += _counts("Outcome tags (valid)", s["outcome_tags"])
    lines += _counts("Validator issues (all recordings)", s["issues"])
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    import pandas as pd

    from .index import INDEX_FILE, read_index

    ap = argparse.ArgumentParser(prog="python -m dbdata.stats", description=__doc__.split("\n")[0])
    ap.add_argument("roots", nargs="+", help="data repo roots or index.csv files")
    args = ap.parse_args(argv)
    frames = []
    for r in args.roots:
        p = Path(r)
        frames.append(read_index(p / INDEX_FILE if p.is_dir() else p))
    sys.stdout.write(render_markdown(pd.concat(frames, ignore_index=True)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
