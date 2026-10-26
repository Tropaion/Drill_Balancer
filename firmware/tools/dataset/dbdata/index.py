"""index.csv + stats.md for a data repo root (the Action runs this; nothing else writes them).

    python -m dbdata.index ROOT [--report validate.json]   # regenerate
    python -m dbdata.index ROOT --check                    # exit 1 if out of date
    python -m dbdata.index ROOT --list-unchecked           # recordings that need a full validation

Needs only meta.json and events.json of each recording (the Action's sparse
checkout; git knows the other files). Each row is checked lightly; the
data-check part of 'valid' and 'warnings' (issue codes, errors included)
comes from a full validator report when one is given for that recording and
it checked these very files (its fingerprint, which covers every file of the
folder; a --light report is no full check), otherwise it is carried over
from the previous index.csv, but only while no file of the recording
changed since that full check (column 'checked', that fingerprint). A
recording without a current full check gets W_UNCHECKED and an empty
'checked', so --list-unchecked names it until a full check. Ids are resolved through the taxonomy (deprecated ->
replaced_by). Output is deterministic: rows sorted by recording_id, fixed
number formats, no timestamps.
"""

from __future__ import annotations

import argparse
import csv
import io as _io
import sys
from pathlib import Path

from . import labels, stats
from .contract import (SESSION_FIELDS, SPLITS, Taxonomy, load_taxonomy, read_json,  # noqa: F401
                       session_value, split_bucket, split_of)
from .io import EVENTS_FILE, META_FILE
from .validate import DATA_CODES, find_recordings, fingerprint, validate_recording

INDEX_FILE = "index.csv"
STATS_FILE = "stats.md"
COLUMNS = ("recording_id", "path", "start_utc", "duration_s", "session_id", "imu_profile",
           "odr_hz", "has_audio", "material", "mode", "bit_type", "bit_diameter_mm", "drill_type",
           "motor", "orientation", "n_holes", "phases", "problems", "outcome_tags", "review_status",
           "min_confidence", "taxonomy_version", "valid", "warnings", "split", "bytes", "checked")
LIST_COLUMNS = ("phases", "problems", "outcome_tags", "warnings")  # ';'-separated (stats.split_list)


def _num(x, nd: int) -> str:
    return "" if x is None else f"{x:.{nd}f}"


def _g(x) -> str:
    return "" if x is None else f"{x:g}"


def index_row(folder: Path, root: Path, meta: dict | None, events: dict | None,
              valid: bool, warnings: list[str], tax: Taxonomy, checked: str = "",
              segments: list | None = None) -> dict:
    """One index.csv row; segments: labels.phase_segments(events), if already computed."""
    rel = folder.relative_to(root).as_posix()
    row = {c: "" for c in COLUMNS}
    row.update(recording_id=folder.name, path=rel, valid="1" if valid else "0",
               warnings=";".join(sorted(set(warnings))), checked=checked)
    if not isinstance(meta, dict):
        return row
    if not isinstance(meta.get("recording_id"), str) or not meta["recording_id"]:
        return row  # E_SCHEMA_META already; the row must stay sortable and hashable
    tags: set[str] = set()

    def tags_text():
        return ";".join(sorted({tax.resolve("outcome_tag", t) for t in tags}))

    try:
        s, d, im = meta["session"], meta["derived"], meta["imu"]
        files = meta.get("files", {})
        row.update(
            recording_id=meta["recording_id"], start_utc=meta["start_utc"],
            duration_s=_num(meta["duration_s"], 3), session_id=meta["session_id"],
            imu_profile=tax.resolve("imu_profile", im["profile"]), odr_hz=_num(im["odr_measured_hz"], 2),
            has_audio="1" if meta.get("audio") else "0",
            **{f: tax.resolve(f, session_value(s, f)) for f in SESSION_FIELDS},
            bit_diameter_mm=_g(s["bit"]["diameter_mm"]),
            orientation=tax.resolve("orientation", d["orientation"]), review_status=meta["review"]["status"],
            taxonomy_version=str(meta["taxonomy_version"]), split=split_of(meta["session_id"]),
            bytes=str(sum(int(f.get("bytes", 0)) for f in files.values())))
        tags = set(s.get("outcome_tags", [])) | set(d.get("tags", []))
    except (KeyError, TypeError, AttributeError):
        return row
    if not isinstance(events, dict):
        row["outcome_tags"] = tags_text()
        return row
    try:
        evs, spans = events.get("events", []), events.get("spans", [])
        row["n_holes"] = str(len({e["hole"] for e in evs if "hole" in e}))
        dur = labels.phase_durations_s(events, tax, segments)
        row["phases"] = ";".join(f"{p}={dur[p]:.2f}" for p in tax.phases if dur.get(p, 0) > 0)
        problem_spans = tax.problem_spans()
        problems = {x["type"] for x in spans if x["type"] in problem_spans}
        problems |= {e["type"] for e in evs if e["type"] == "kickback"}
        row["problems"] = ";".join(sorted(problems))
        for h in events.get("holes", []):
            tags |= set(h.get("outcome_tags", []))
        conf = [x["confidence"] for x in evs + spans if "confidence" in x]
        row["min_confidence"] = _num(min(conf), 3) if conf else ""
    except (KeyError, TypeError, ValueError):
        pass
    row["outcome_tags"] = tags_text()
    return row


def read_previous(path: Path) -> dict[str, dict]:
    if not path.is_file():
        return {}
    with open(path, newline="", encoding="utf-8") as f:
        return {r["recording_id"]: r for r in csv.DictReader(f)}


def build_rows(root: str | Path, report: dict | None = None, previous: dict | None = None,
               taxonomy: Taxonomy | None = None) -> list[dict]:
    """One row per recording under root/recordings, sorted by recording_id.

    report: {Report.key: Report.to_dict()} of python -m dbdata.validate;
    an entry counts only as a full check of the files it fingerprinted, and only its
    data-check codes (DATA_CODES) are used: the light checks run here afresh.
    previous: rows of the old index.csv by recording_id.
    """
    tax = taxonomy or load_taxonomy()
    root = Path(root)
    report, previous = report or {}, previous or {}
    rows = []
    rec_dir = root / "recordings"
    for folder in (find_recordings(rec_dir) if rec_dir.is_dir() else []):
        # also reads and parses both docs; the fingerprint matters only against a report or a previous row
        light = validate_recording(folder, taxonomy=tax, light=True, with_fingerprint=bool(report or previous))
        meta, events, fp = light.docs.get(META_FILE), light.docs.get(EVENTS_FILE), light.fingerprint
        valid, warns = light.ok, [i.code for i in light.issues]
        full, prev = report.get(light.key), previous.get(light.key)
        checked = ""     # set only while the row holds the data-check result of these very files
        data = None      # the codes of a full check of these very files
        if full is not None and not full.get("light", True) and fp and full.get("fingerprint") == fp:
            # only its data checks: the light ones are fresh, and the entry may be of another folder
            # with the same recording_id and files (a copy), whose light issues (E_ID) are its own
            data = [i["code"] for i in full["issues"]]
        elif prev is not None and fp and prev.get("checked") == fp:
            data = prev.get("warnings", "").split(";")   # an earlier full run on these same files
        if data is not None:
            data = [c for c in data if c in DATA_CODES]
            valid = valid and not any(c.startswith("E_") for c in data)
            warns += data
            checked = fp
        elif light.ok:
            warns.append("W_UNCHECKED")
        try:
            row = index_row(folder, root, meta, events, valid, warns, tax, checked, light.segments)
        except Exception as e:  # noqa: BLE001 - one malformed recording must not stop the index
            row = index_row(folder, root, None, None, False, warns + ["E_INTERNAL"], tax, checked)
            print(f"{folder}: index row failed: {type(e).__name__}: {e}", file=sys.stderr)
        rows.append(row)
    rows.sort(key=lambda r: (r["recording_id"], r["path"]))
    return rows


def render_csv(rows: list[dict]) -> str:
    buf = _io.StringIO()
    w = csv.DictWriter(buf, fieldnames=COLUMNS, lineterminator="\n")
    w.writeheader()
    w.writerows(rows)
    return buf.getvalue()


def rows_frame(rows: list[dict]):
    """Rows as the DataFrame read_index gives for them (through the CSV text, so typed alike)."""
    return read_index(_io.StringIO(render_csv(rows)))


def read_index(path):
    """index.csv (a path or a text buffer) as a DataFrame with numeric columns typed (empty -> NaN)."""
    import pandas as pd

    df = pd.read_csv(path, dtype=str, keep_default_na=False)
    for c in COLUMNS:       # an index.csv written before a column was added: empty until regenerated
        if c not in df.columns:
            df[c] = ""
    for c in ("duration_s", "odr_hz", "bit_diameter_mm", "min_confidence"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    for c in ("has_audio", "n_holes", "taxonomy_version", "valid", "bytes"):
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0).astype("int64")
    return df


def regenerate(root: str | Path, report: dict | None = None, check: bool = False,
               taxonomy: Taxonomy | None = None) -> bool:
    """Writes index.csv and stats.md; returns True if either changed (check: only compare)."""
    root = Path(root)
    tax = taxonomy or load_taxonomy()
    rows = build_rows(root, report, read_previous(root / INDEX_FILE), tax)
    text = render_csv(rows)
    md = stats.render_markdown(rows_frame(rows), tax)
    changed = False
    for name, content in ((INDEX_FILE, text), (STATS_FILE, md)):
        p = root / name
        old = p.read_text(encoding="utf-8") if p.is_file() else None
        if old != content:
            changed = True
            if not check:
                p.write_text(content, encoding="utf-8", newline="\n")
    return changed


def unchecked(root: str | Path) -> list[str]:
    """Recording paths (relative to root) without a current full validation: not in
    index.csv, or any file of the recording changed since the check recorded there."""
    root = Path(root)
    checked = {r["path"]: r.get("checked") or "" for r in read_previous(root / INDEX_FILE).values()}
    rec_dir = root / "recordings"
    out = []
    for f in (find_recordings(rec_dir) if rec_dir.is_dir() else []):
        rel = f.relative_to(root).as_posix()
        if not checked.get(rel) or checked[rel] != fingerprint(f):
            out.append(rel)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m dbdata.index", description=__doc__.split("\n")[0])
    ap.add_argument("root", help="data repo root")
    ap.add_argument("--report", metavar="FILE", help="JSON report of python -m dbdata.validate")
    ap.add_argument("--check", action="store_true", help="exit 1 if index.csv/stats.md are out of date")
    ap.add_argument("--list-unchecked", action="store_true",
                    help="print recordings not in index.csv or changed since their last full validation")
    args = ap.parse_args(argv)
    if args.list_unchecked:
        for p in unchecked(args.root):
            print(p)
        return 0
    report = None
    if args.report:
        if not Path(args.report).is_file():  # the validator writes it even on errors: it crashed
            print(f"{args.report}: report not found", file=sys.stderr)
            return 2
        report = read_json(args.report)
    changed = regenerate(args.root, report, check=args.check)
    if args.check:
        print("out of date" if changed else "up to date")
        return 1 if changed else 0
    print(f"{INDEX_FILE}, {STATS_FILE}: {'updated' if changed else 'unchanged'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
