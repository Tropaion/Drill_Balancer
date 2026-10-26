"""dbdata: the DrillBalancer training-data contract and its Python tooling.

The contract (taxonomy, JSON Schemas) is in dbdata/contract/. Modules:
io (read/write a recording), labels (phases from events), windows (aligned
IMU + audio windows), dataset (index.csv-based loader), validate, index,
stats, dbrec (the device stream: decoder and export), autodetect (the
auto-labeller), synth (synthetic recordings from the simulator). The names
below are imported lazily, so `python -m dbdata.<module>` runs each module
only once.
"""

import importlib

__version__ = "1.0.0"

_EXPORTS = {
    "detect": "autodetect",
    "Taxonomy": "contract", "load_taxonomy": "contract",
    "Dataset": "dataset",
    "split_of": "index",
    "Recording": "io", "SyncFit": "io", "read_recording": "io", "recording_dir": "io",
    "write_recording": "io",
    "compute_derived": "labels", "map_to_level": "labels", "phase_segments": "labels",
    "phase_track": "labels",
    "validate_recording": "validate",
    "iter_windows": "windows",
}

__all__ = sorted(_EXPORTS)


def __getattr__(name):
    mod = _EXPORTS.get(name)
    if mod is None:
        raise AttributeError(f"module 'dbdata' has no attribute {name!r}")
    value = getattr(importlib.import_module(f".{mod}", __name__), name)
    globals()[name] = value
    return value


def __dir__():
    return sorted(list(globals()) + __all__)
