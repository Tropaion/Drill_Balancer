"""The dataset contract: taxonomy and JSON Schemas, loaded from dbdata/contract/.

These files are the single source of truth. The data repo keeps
human-readable copies under taxonomy/ and schema/ (REPO_COPIES);
validate.check_contract() checks that they match.
"""

from __future__ import annotations

import functools
import json
import zlib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

CONTRACT_DIR = Path(__file__).resolve().parent / "contract"
TAXONOMY_FILE = "taxonomy.v1.json"
META_SCHEMA_FILE = "meta.v1.schema.json"
EVENTS_SCHEMA_FILE = "events.v1.schema.json"

# data repo path -> packaged file name
REPO_COPIES = {
    "taxonomy/" + TAXONOMY_FILE: TAXONOMY_FILE,
    "schema/" + META_SCHEMA_FILE: META_SCHEMA_FILE,
    "schema/" + EVENTS_SCHEMA_FILE: EVENTS_SCHEMA_FILE,
}

UNKNOWN = "unknown"
OTHER = "other"

# EVENT record codes of the device stream, by name: code 1.. in this order
# (rec_stream.h REC_EV_*)
DEVICE_EVENT_CODES = ("MARK", "TOUCH", "BLE_LOST", "BLE_BACK", "IMU_LOST", "IMU_BACK",
                      "VIB_START", "VIB_STOP", "PROFILE")


def contract_path(name: str) -> Path:
    return CONTRACT_DIR / name


def read_json(path: str | Path) -> dict:
    """A UTF-8 JSON file (contract files, recording documents, reports)."""
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_json(name: str) -> dict:
    """A packaged contract file by name, freshly parsed."""
    return read_json(contract_path(name))


def duplicates(ids) -> list[str]:
    """The ids that appear more than once, sorted."""
    return sorted(k for k, n in Counter(ids).items() if n > 1)


# Splits: crc32(session_id) % 10, so all takes of one session (one workpiece) share a split.
SPLITS = {"train": tuple(range(8)), "val": (8,), "test": (9,)}


def split_bucket(session_id: str) -> int:
    """crc32(session_id) % 10; grouping by session keeps a workpiece in one split."""
    return zlib.crc32(session_id.encode("utf-8")) % 10


def split_of(session_id: str) -> str:
    b = split_bucket(session_id)
    return next(k for k, v in SPLITS.items() if b in v)


@functools.lru_cache(maxsize=None)
def schema(name: str) -> dict:
    """A packaged JSON Schema by file name (META_SCHEMA_FILE, EVENTS_SCHEMA_FILE)."""
    return load_json(name)


@functools.lru_cache(maxsize=None)
def schema_validator(name: str):
    """A cached jsonschema Draft 2020-12 validator for a packaged schema."""
    import jsonschema

    s = schema(name)
    jsonschema.Draft202012Validator.check_schema(s)
    return jsonschema.Draft202012Validator(s)


@dataclass(frozen=True)
class Entry:
    id: str
    added: int = 1
    deprecated: bool = False
    replaced_by: str | None = None
    extra: dict = field(default_factory=dict, compare=False, hash=False)


class Taxonomy:
    """Stable string ids for every label field, phases and their parameters."""

    def __init__(self, doc: dict):
        self.doc = doc
        self.version: int = doc["version"]
        self.special: tuple[str, ...] = tuple(doc.get("special", (UNKNOWN, OTHER)))
        self.point_events = {e["id"]: _entry(e) for e in doc["point_events"]}
        self.span_events = {e["id"]: _entry(e) for e in doc["span_events"]}
        self.phases: tuple[str, ...] = tuple(p["id"] for p in doc["phases"])
        pp = doc["phase_params"]
        self.windows_ms: dict[str, float] = dict(pp["windows_ms"])
        self.guard_ms: float = pp["guard_ms"]
        self.coarse: dict[str, dict[str, str]] = doc.get("coarse", {})
        self.fields = {name: {e["id"]: _entry(e) for e in entries}
                       for name, entries in doc["fields"].items()}
        lim = doc["derived_rules"]["orientation_limits_deg"]
        self.orientation_wall_deg: float = lim["wall"]
        self.orientation_vertical_deg: float = lim["vertical"]

    # -- lookups ---------------------------------------------------------------

    def phase_code(self, phase: str) -> int:
        return self.phases.index(phase)

    def grammar_events(self) -> frozenset[str]:
        """Point events that take part in the event grammar (not kickback)."""
        return frozenset(k for k, e in self.point_events.items() if e.extra.get("grammar", True))

    def hole_events(self) -> frozenset[str]:
        return frozenset(k for k, e in self.point_events.items() if e.extra.get("hole"))

    def span_group(self, span_type: str) -> str | None:
        e = self.span_events.get(span_type)
        return e.extra.get("group") if e else None

    def problem_spans(self) -> tuple[str, ...]:
        return tuple(k for k in self.span_events if self.span_group(k) == "problem")

    def ignore_reasons(self) -> tuple[str, ...]:
        return tuple(self.span_events["ignore"].extra.get("reasons", ()))

    def lookup(self, field_name: str, value: str) -> Entry | None:
        """The entry for value in a field, or None. 'unknown'/'other' are always valid."""
        if value in self.special:
            return Entry(value)
        return self.fields[field_name].get(value)

    def resolve(self, field_name: str, value: str) -> str:
        """Follows replaced_by for a deprecated id (cycles stop)."""
        seen = set()
        while value not in seen:
            seen.add(value)
            e = self.lookup(field_name, value)
            if e is None or not e.deprecated or not e.replaced_by:
                return value
            value = e.replaced_by
        return value

    def coarse_phase(self, phase: str) -> str:
        return self.coarse.get("phase", {}).get(phase, phase)

    def problems(self) -> list[str]:
        """Self-check: unique ids, added <= version, replaced_by names a live id of the same field."""
        out = []
        groups = {"point_events": self.doc["point_events"], "span_events": self.doc["span_events"],
                  "phases": self.doc["phases"], **{f"fields.{k}": v for k, v in self.doc["fields"].items()}}
        for name, entries in groups.items():
            ids = [e["id"] for e in entries]
            for i in duplicates(ids):
                out.append(f"{name}: {i} appears twice")
            by_id = {e["id"]: e for e in entries}
            for e in entries:
                if e.get("added", 1) > self.version:
                    out.append(f"{name}: {e['id']} added in {e['added']} > version {self.version}")
                if e.get("deprecated") and name.startswith("fields."):
                    r = by_id.get(e.get("replaced_by"))
                    if r is None:
                        out.append(f"{name}: {e['id']} is deprecated without a valid replaced_by")
                    elif r.get("deprecated"):
                        out.append(f"{name}: {e['id']} is replaced by {r['id']}, which is deprecated too")
                elif e.get("replaced_by") and not e.get("deprecated"):
                    out.append(f"{name}: {e['id']} has replaced_by but is not deprecated")
        return out


def _entry(e: dict) -> Entry:
    extra = {k: v for k, v in e.items() if k not in ("id", "added", "deprecated", "replaced_by")}
    return Entry(e["id"], e.get("added", 1), bool(e.get("deprecated", False)), e.get("replaced_by"), extra)


@functools.lru_cache(maxsize=None)
def _packaged_taxonomy() -> Taxonomy:
    return Taxonomy(load_json(TAXONOMY_FILE))


def load_taxonomy(path: str | Path | None = None) -> Taxonomy:
    """The packaged taxonomy (cached), or one from a file."""
    if path is None:
        return _packaged_taxonomy()
    return Taxonomy(read_json(path))
