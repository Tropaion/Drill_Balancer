"""Training access to one or more data repo roots through their index.csv.

    ds = Dataset(["~/data/Drill_Balancer_Data"], split="train",
                 filters={"imu_profile": "RAW448", "mode": ["drill", "hammer"]})
    arrays = ds.arrays()          # stacked windows, cached as npz per recording

Splits are crc32(session_id) % 10: 0-7 train, 8 val, 9 test.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Iterable, Iterator

import numpy as np
import pandas as pd

from . import windows as W
from .contract import SPLITS, Taxonomy, load_taxonomy, split_of
from .index import INDEX_FILE, LIST_COLUMNS, build_rows, read_index, rows_frame
from .io import EVENTS_FILE, META_FILE, Recording, read_recording, sha256_file
from .stats import split_list

CACHE_VERSION = 2  # 2: windowless recordings cache their full key set and shapes
DEFAULT_CACHE = Path(os.environ.get("DBDATA_CACHE", "~/.cache/dbdata")).expanduser()

__all__ = ["Dataset", "split_of", "SPLITS"]


def _matches(series: pd.Series, col: str, want) -> pd.Series:
    """Filter semantics: callable(series) -> mask; list = any of; strings match
    hierarchically ('wood' matches 'wood/softwood'); list columns by membership."""
    if callable(want):
        return pd.Series(want(series), index=series.index).astype(bool)
    values = list(want) if isinstance(want, (list, tuple, set, frozenset)) else [want]
    if col in LIST_COLUMNS:
        sets = series.map(lambda s: {x.partition("=")[0] for x in split_list(s)})
        return sets.map(lambda s: any(str(v) in s for v in values))
    if series.dtype == object or pd.api.types.is_string_dtype(series):
        def ok(x):
            x = str(x)
            return any(x == str(v) or x.startswith(str(v) + "/") for v in values)
        return series.map(ok).astype(bool)
    return series.isin(values)


class Dataset:
    """Recordings selected from index.csv of each root, and their windows.

    filters: {column: value | list | callable}; split: 'train' | 'val' |
    'test' | None; include_invalid / include_rejected widen the default
    selection (valid == 1, review_status != rejected). Window options go to
    windows.iter_windows. cache_dir=None uses ~/.cache/dbdata ($DBDATA_CACHE),
    False disables the cache.
    """

    def __init__(self, roots, filters: dict | None = None, split: str | None = None, *,
                 win_s: float = 1.0, hop_s: float = 0.5, streams=W.STREAMS,
                 boundary_policy: str = "ignore", label: str = "centre",
                 calibrate: bool = True, mount: bool = False,
                 include_invalid: bool = False, include_rejected: bool = False,
                 cache_dir=None, taxonomy: Taxonomy | None = None):
        if split is not None and split not in SPLITS:
            raise ValueError(f"split must be one of {list(SPLITS)} or None")
        self.roots = [Path(r).expanduser() for r in ([roots] if isinstance(roots, (str, Path)) else roots)]
        if not self.roots:
            raise ValueError("no dataset roots given")
        self.taxonomy = taxonomy or load_taxonomy()
        self.window_args = dict(win_s=win_s, hop_s=hop_s, streams=tuple(streams),
                                boundary_policy=boundary_policy, label=label)
        self.calibrate, self.mount = calibrate, mount
        self.cache_dir = None if cache_dir is False else Path(cache_dir or DEFAULT_CACHE).expanduser()

        frames = []
        for root in self.roots:
            p = root / INDEX_FILE
            if p.is_file():
                df = read_index(p)
            else:  # no index yet (a local folder): build one in memory
                df = rows_frame(build_rows(root, taxonomy=self.taxonomy))
            df.insert(0, "root", str(root))
            frames.append(df)
        df = pd.concat(frames, ignore_index=True)
        if len(df):
            df = df.drop_duplicates("recording_id", keep="first")
            keep = pd.Series(True, index=df.index)
            if not include_invalid:
                keep &= df["valid"] == 1
            if not include_rejected:
                keep &= df["review_status"] != "rejected"
            if "audio" in self.window_args["streams"]:
                keep &= df["has_audio"] == 1
            if split is not None:
                keep &= df["session_id"].map(split_of) == split
            for col, want in (filters or {}).items():
                if col not in df.columns:
                    raise KeyError(f"no index column {col!r}")
                keep &= _matches(df[col], col, want)
            df = df[keep]
        self.index = df.sort_values("recording_id").reset_index(drop=True)

    def __len__(self) -> int:
        return len(self.index)

    def path(self, i: int) -> Path:
        r = self.index.iloc[i]
        return Path(r["root"]) / r["path"]

    def recording(self, i: int) -> Recording:
        return read_recording(self.path(i), calibrate=self.calibrate, mount=self.mount,
                              audio="audio" in self.window_args["streams"])

    def __iter__(self) -> Iterator[Recording]:
        for i in range(len(self)):
            yield self.recording(i)

    def windows(self) -> Iterator[dict]:
        """Every window of every selected recording (valid or not; see w['valid'])."""
        for rec in self:
            yield from W.iter_windows(rec, taxonomy=self.taxonomy, **self.window_args)

    # -- cached arrays ---------------------------------------------------------------

    def _cache_key(self, i: int) -> str:
        """Covers everything a window depends on: meta.json as a whole (its file hashes,
        calibration, sync, gaps, muted spans), events.json, the taxonomy and the options."""
        folder = self.path(i)
        key = {"v": CACHE_VERSION, "id": self.index.iloc[i]["recording_id"],
               "meta": sha256_file(folder / META_FILE), "events": sha256_file(folder / EVENTS_FILE),
               "taxonomy": self.taxonomy.version, "calibrate": self.calibrate, "mount": self.mount,
               **self.window_args}
        return hashlib.sha256(json.dumps(key, sort_keys=True).encode()).hexdigest()[:32]

    def recording_arrays(self, i: int) -> dict[str, np.ndarray]:
        """All windows of recording i as arrays (npz-cached)."""
        path = None
        if self.cache_dir is not None:
            path = self.cache_dir / f"{self.index.iloc[i]['recording_id']}_{self._cache_key(i)}.npz"
            if path.is_file():
                with np.load(path) as z:
                    return _from_cache(dict(z))
        rec = self.recording(i)
        a = self.window_args
        n = len(W.window_starts(rec, a["win_s"], a["hop_s"]))
        arr = _stack(W.iter_windows(rec, taxonomy=self.taxonomy, **a), n, self.taxonomy, a,
                     W.window_shapes(rec, a["win_s"]))
        del rec  # freed before the float copy of the audio
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".tmp.npz")
            np.savez_compressed(tmp, **arr)
            os.replace(tmp, path)
        return _from_cache(arr)

    def arrays(self, only_valid: bool = True) -> dict[str, np.ndarray]:
        """Windows of all selected recordings stacked; 'recording' indexes self.index."""
        parts = []
        for i in range(len(self)):
            a = self.recording_arrays(i)
            a["recording"] = np.full(len(a["t0_us"]), i, dtype=np.int32)
            if only_valid:
                a = {k: v[a["valid"]] for k, v in a.items()}
            parts.append(a)
        if not parts:
            return {}
        return {k: np.concatenate([p[k] for p in parts]) for k in parts[0]}


def _stack(ws: Iterable[dict], n: int, tax: Taxonomy, args: dict, shapes: dict) -> dict[str, np.ndarray]:
    """The n windows as arrays, in the cache's form (audio int16). Allocated at their final size
    and filled window by window. Keys and per-window shapes come from the options and
    windows.window_shapes, not from the windows, so a recording without windows (shorter
    than win_s) still stacks with the others."""
    spans = list(tax.span_events)
    out = {
        "t0_us": np.zeros(n, np.int64),
        "imu": np.zeros((n, *shapes["imu"]), np.float32),
        "fill_mask": np.zeros((n, *shapes["fill_mask"]), bool),
        "phase": np.zeros(n, np.int8),
        "weight": np.zeros(n, np.float32),
        "hole": np.zeros(n, np.int16),
        "valid": np.zeros(n, bool),
        "overlays": np.zeros((n, len(spans)), np.float32),
    }
    audio = "audio" in args["streams"]
    soft = args["boundary_policy"] == "soft"
    if audio:
        out["audio"] = np.zeros((n, *shapes["audio"]), np.int16)
    if soft:
        out["soft"] = np.zeros((n, len(tax.phases)), np.float32)
    k = -1
    for k, w in enumerate(ws):
        out["t0_us"][k], out["phase"][k], out["weight"][k] = w["t0_us"], w["phase_code"], w["weight"]
        out["hole"][k], out["valid"][k] = w["hole"], w["valid"]
        out["imu"][k], out["fill_mask"][k] = w["imu"], w["fill_mask"]
        out["overlays"][k] = [w["overlays"][s] for s in spans]
        if audio:  # exact: the samples were int16 / 32768
            out["audio"][k] = np.round(w["audio"] * 32768.0)
        if soft:
            out["soft"][k] = w["soft"]
    if k + 1 != n:
        raise RuntimeError(f"expected {n} windows, got {k + 1}")
    return out


def _from_cache(c: dict) -> dict:
    """The cached form as returned: audio int16 -> float32 in [-1, 1), with one full-size copy."""
    if "audio" in c:
        x = c["audio"].astype(np.float32)
        x /= 32768.0
        c["audio"] = x
    return c
