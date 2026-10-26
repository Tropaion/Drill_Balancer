"""Dataset over several roots: default selection, filters, splits, npz cache."""

import contextlib
import io
import json
import unittest
from unittest import mock

import numpy as np

import fixtures as F
from dbdata import index as X
from dbdata import validate as V
from dbdata import windows as W
from dbdata.dataset import Dataset

TRAIN, VAL, TEST = "20261026T1500_k7q2", "20261026T1428_k7q2", "20261026T1430_k7q2"
WOBBLE = [{"id": "s1", "type": "wobble", "t0_us": F.us(2.0), "t1_us": F.us(2.5), "source": "user",
           "confidence": 1.0}]


class DatasetTest(F.TempDirTest):
    def setUp(self):
        super().setUp()
        self.a, self.b = self.tmp / "a", self.tmp / "b"
        F.make_recording(self.a, rid=F.rid(1), session_id=TRAIN)
        F.make_recording(self.a, rid=F.rid(2), session_id=VAL, material="masonry/concrete", mode="hammer",
                         audio=False)
        F.make_recording(self.a, rid=F.rid(3), session_id=TEST, odr_true=470.0)  # invalid
        F.make_recording(self.b, rid=F.rid(4), session_id=TRAIN, material="wood/hardwood", review="rejected")
        F.make_recording(self.b, rid=F.rid(5), session_id=TRAIN, material="metal/steel", mode="screw",
                         bit_type="twist_hss", spans=WOBBLE)
        report = self.tmp / "report.json"
        with contextlib.redirect_stdout(io.StringIO()):
            V.main(["--quiet", "--report", str(report), str(self.a)])
        X.regenerate(self.a, json.loads(report.read_text()))
        X.regenerate(self.b)
        self.cache = self.tmp / "cache"

    def ids(self, ds):
        return [r[-4:] for r in ds.index["recording_id"]]

    def ds(self, **kw):
        kw.setdefault("cache_dir", self.cache)
        return Dataset([self.a, self.b], **kw)

    def test_default_selection(self):
        # valid, not rejected, with audio (streams include audio)
        self.assertEqual(self.ids(self.ds()), ["0001", "0005"])
        self.assertEqual(self.ids(self.ds(streams=("imu",))), ["0001", "0002", "0005"])
        self.assertEqual(self.ids(self.ds(streams=("imu",), include_invalid=True, include_rejected=True)),
                         ["0001", "0002", "0003", "0004", "0005"])

    def test_splits(self):
        kw = dict(streams=("imu",), include_invalid=True)
        self.assertEqual(self.ids(self.ds(split="train", **kw)), ["0001", "0005"])
        self.assertEqual(self.ids(self.ds(split="val", **kw)), ["0002"])
        self.assertEqual(self.ids(self.ds(split="test", **kw)), ["0003"])
        with self.assertRaises(ValueError):
            self.ds(split="holdout")

    def test_filters(self):
        kw = dict(streams=("imu",), include_rejected=True)
        self.assertEqual(self.ids(self.ds(filters={"material": "wood"}, **kw)), ["0001", "0004"])
        self.assertEqual(self.ids(self.ds(filters={"material": "wood/hardwood"}, **kw)), ["0004"])
        self.assertEqual(self.ids(self.ds(filters={"material": "woo"}, **kw)), [])
        self.assertEqual(self.ids(self.ds(filters={"mode": ["hammer", "screw"]}, **kw)), ["0002", "0005"])
        self.assertEqual(self.ids(self.ds(filters={"problems": "wobble"}, **kw)), ["0005"])
        self.assertEqual(self.ids(self.ds(filters={"phases": "breakthrough", "has_audio": 0}, **kw)), ["0002"])
        self.assertEqual(self.ids(self.ds(filters={"recording_id": lambda s: s.str.endswith("4")}, **kw)),
                         ["0004"])
        self.assertEqual(self.ids(self.ds(filters={"material": "wood", "mode": "drill",
                                                   "imu_profile": "RAW448"})), ["0001"])
        with self.assertRaises(KeyError):
            self.ds(filters={"colour": "red"})

    def test_arrays_and_cache(self):
        ds = self.ds()
        a = ds.arrays()
        n = len(a["t0_us"])
        self.assertEqual(n, 24)  # 2 recordings x 12 windows
        self.assertEqual(a["imu"].shape, (n, 448, 6))
        self.assertEqual(a["audio"].shape, (n, 2, 16000))
        self.assertEqual(a["fill_mask"].shape, (n, 448))
        self.assertEqual(a["overlays"].shape, (n, 5))
        self.assertEqual(sorted(set(a["recording"])), [0, 1])
        self.assertTrue(a["valid"].all())
        self.assertEqual(len(list(self.cache.glob("*.npz"))), 2)
        ref = [w for w in W.iter_windows(ds.recording(0))]
        np.testing.assert_array_equal(a["audio"][0], ref[0]["audio"])
        np.testing.assert_array_equal(a["imu"][0], ref[0]["imu"])
        # the second load comes from the cache only
        with mock.patch.object(W, "iter_windows", side_effect=AssertionError("cache missed")):
            b = self.ds().arrays()
        for k in a:
            np.testing.assert_array_equal(a[k], b[k], k)
        # other window parameters, other cache entries
        c = self.ds(win_s=0.5, hop_s=0.5).arrays()
        self.assertEqual(c["imu"].shape[1:], (224, 6))
        self.assertEqual(len(list(self.cache.glob("*.npz"))), 4)

    def test_cache_follows_meta_and_events_edits(self):
        self.ds().arrays()
        folder = self.ds().path(0)
        edits = {
            "meta.json": lambda m: m["sync"].update(adc_delay_us=5000),   # the tap-test constant, added later
            "events.json": lambda e: e["spans"].append({"id": "s9", "type": "ignore", "reason": "speech",
                                                        "t0_us": F.us(2.0), "t1_us": F.us(2.1),
                                                        "source": "user", "confidence": 1.0}),
        }
        for name, fn in edits.items():
            with self.subTest(name):
                before = self.ds().recording_arrays(0)
                F.edit_json(folder / name, fn)
                with mock.patch.object(W, "iter_windows", side_effect=AssertionError("cache hit")):
                    with self.assertRaises(AssertionError):
                        self.ds().recording_arrays(0)
                after = self.ds().recording_arrays(0)
                key = "audio" if name == "meta.json" else "weight"
                self.assertFalse(np.array_equal(before[key], after[key]), key)

    def test_recording_shorter_than_a_window(self):
        # 0.8 s: no window at all, but its arrays must still stack with the others'
        bare = self.tmp / "short"
        F.make_recording(bare, rid=F.rid(8), session_id=TRAIN, duration_s=0.8, events=())
        F.make_recording(bare, rid=F.rid(9), session_id=TRAIN)
        for policy in ("ignore", "soft"):
            with self.subTest(policy):
                ds = Dataset(bare, cache_dir=self.cache, boundary_policy=policy)
                self.assertEqual(self.ids(ds), ["0008", "0009"])
                empty = ds.recording_arrays(0)
                self.assertEqual(empty["imu"].shape, (0, 448, 6))
                self.assertEqual(empty["audio"].shape, (0, 2, 16000))
                self.assertEqual(empty["fill_mask"].shape, (0, 448))
                self.assertEqual("soft" in empty, policy == "soft")
                for _ in range(2):   # computed, then from the cache
                    a = ds.arrays()
                    self.assertEqual(a["imu"].shape, (12, 448, 6))
                    self.assertEqual(set(a), set(ds.recording_arrays(1)) | {"recording"})
                    if policy == "soft":
                        self.assertEqual(a["soft"].shape, (12, 7))

    def test_without_cache_or_index(self):
        bare = self.tmp / "bare"
        F.make_recording(bare, rid=F.rid(7), session_id=TRAIN)
        ds = Dataset(bare, cache_dir=False)
        self.assertEqual(self.ids(ds), ["0007"])
        self.assertEqual(len(ds.arrays()["t0_us"]), 12)
        self.assertFalse(self.cache.exists())
        self.assertEqual(sum(1 for _ in ds.windows()), 12)
        # the same recording in two roots is used once
        self.assertEqual(self.ids(Dataset([bare, bare], cache_dir=False)), ["0007"])


if __name__ == "__main__":
    unittest.main()
