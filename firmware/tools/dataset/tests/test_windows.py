"""Aligned windows: clock drift through the sync fit, IMU gaps, filled samples, validity, labels."""

import unittest
from unittest import mock

import numpy as np

import fixtures as F
from dbdata import contract
from dbdata import io as dio
from dbdata import windows as W


def load(tmp, name, **kw):
    return dio.read_recording(F.write(tmp / name, F.build(**kw)))


def by_start(ws):
    return {round((w["t0_us"] - F.T0) / 1e6, 3): w for w in ws}


class ShapeTest(F.TempDirTest):
    def test_shapes_and_grid(self):
        rec = load(self.tmp, "a")
        ws = list(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual(len(ws), 12)  # 7 s of data, 1 s windows every 0.5 s
        w = ws[0]
        self.assertEqual(w["imu"].shape, (448, 6))  # round(1.0 * 448.4)
        self.assertEqual(w["imu"].dtype, np.float32)
        self.assertEqual(w["fill_mask"].shape, (448,))
        self.assertEqual(w["audio"].shape, (2, 16000))
        self.assertTrue(all(x["valid"] for x in ws))
        self.assertEqual(ws[1]["t0_us"] - ws[0]["t0_us"], 500_000)
        # imu only
        ws = list(W.iter_windows(rec, 0.25, 0.25, streams=("imu",)))
        self.assertNotIn("audio", ws[0])
        self.assertEqual(ws[0]["imu"].shape, (112, 6))

    def test_padding_marks_the_mask(self):
        # the true ODR (440 Hz) gives fewer samples than round(win * nominal): padded tail is masked
        rec = load(self.tmp, "slow", odr_true=440.0)
        w = next(W.iter_windows(rec, 1.0, 1.0, streams=("imu",)))
        self.assertTrue(w["fill_mask"][440:].all())
        self.assertFalse(w["fill_mask"][:440].any())
        np.testing.assert_array_equal(w["imu"][440:], 0)
        self.assertTrue(w["valid"])

    def test_audio_needs_audio(self):
        rec = load(self.tmp, "noaudio", audio=False)
        with self.assertRaises(ValueError):
            list(W.iter_windows(rec))
        self.assertEqual(len(list(W.iter_windows(rec, streams=("imu",)))), 12)


class DriftTest(F.TempDirTest):
    def test_impulse_lines_up_despite_audio_clock_drift(self):
        # the audio clock runs 0.5 % fast: a naive frame = t * 16000 would be 22 ms off at 4.5 s
        rec = load(self.tmp, "drift", audio_rate_true=16080.0, impulse_s=5.0)
        w = by_start(W.iter_windows(rec, 1.0, 0.5))[4.5]
        t0 = w["t0_us"]
        i = int(np.argmax(np.abs(w["imu"][:, 2])))
        t_imu = rec.t_us[np.searchsorted(rec.t_us, t0) + i]
        k = int(np.argmax(np.abs(w["audio"][0])))
        t_audio = rec.sync.t_of(w["audio_frame0"] + k)
        self.assertLess(abs(t_imu - F.us(5.0)), 1500)
        self.assertLess(abs(t_audio - F.us(5.0)), 300)
        self.assertLess(abs(t_audio - t_imu), 1500)
        naive_f0 = (t0 - F.T0 + 1500) * 16000 / 1e6
        self.assertGreater(abs(naive_f0 - w["audio_frame0"]), 300)
        # and the second channel (right = 0.8 * left delayed by one frame)
        self.assertEqual(int(np.argmax(np.abs(w["audio"][1]))), k + 1)

    def test_adc_delay_moves_audio_later(self):
        # a sound at device time t reaches the file adc_delay_us later: frame0 moves forward
        parts = F.build()
        base = by_start(W.iter_windows(dio.read_recording(F.write(self.tmp / "d0", parts)), 1.0, 0.5))
        parts["meta"]["sync"]["adc_delay_us"] = 10_000
        rec = dio.read_recording(F.write(self.tmp / "d1", parts))
        late = by_start(W.iter_windows(rec, 1.0, 0.5))
        frames = 10_000 / rec.sync.b_us_per_frame
        self.assertAlmostEqual(late[3.0]["audio_frame0"] - base[3.0]["audio_frame0"], frames, delta=1.0)


class GapTest(F.TempDirTest):
    def test_transport_gap_invalidates_overlapping_windows(self):
        rec = load(self.tmp, "gap", gap=(3.2, 3.4))
        self.assertGreater(rec.meta["gaps"]["imu_missing_samples"], 80)
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertTrue(ws[2.0]["valid"])
        self.assertEqual((ws[2.5]["valid"], ws[2.5]["reason"]), (False, "imu_gap"))
        self.assertEqual(ws[3.0]["reason"], "imu_gap")
        self.assertTrue(ws[3.5]["valid"])

    def test_unflagged_seq_jump_is_a_gap(self):
        # 15 samples lost at 3.0 s without the gap flag (a FIFO discontinuity, say): the samples after
        # it would sit 33 ms early on the window grid, so every window over it is invalid
        parts = F.build(impulse_s=3.3)
        imu = parts["imu"]
        t = imu["t_us"]
        parts["imu"] = imu[(t < F.us(3.0)) | (t >= F.us(3.034))].reset_index(drop=True)
        rec = dio.read_recording(F.write(self.tmp / "jump", parts))
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual((ws[2.5]["valid"], ws[2.5]["reason"]), (False, "imu_gap"))
        self.assertEqual(ws[3.0]["reason"], "imu_gap")
        self.assertEqual(ws[2.0]["reason"], "imu_gap")   # its last samples are lost too
        self.assertTrue(ws[1.5]["valid"])
        self.assertTrue(ws[3.5]["valid"])

    def test_hole_without_seq_jump_is_too_short(self):
        # samples missing while seq runs on (inconsistent data): caught by the sample count
        parts = F.build()
        imu = parts["imu"]
        t = imu["t_us"]
        imu = imu[(t < F.us(2.1)) | (t >= F.us(2.4))].reset_index(drop=True)
        imu["seq"] = 1000 + np.arange(len(imu))
        parts["imu"] = imu
        rec = dio.read_recording(F.write(self.tmp / "hole", parts))
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual(ws[1.5]["reason"], "imu_short")
        self.assertTrue(ws[3.0]["valid"])

    def test_filled_samples(self):
        rec = load(self.tmp, "filled", filled=[(1.0, 2.0, 0.4), (5.0, 6.0, 0.1)])
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual((ws[1.0]["valid"], ws[1.0]["reason"]), (False, "filled"))
        self.assertTrue(ws[0.5]["valid"])           # 20 % filled is fine
        w = ws[5.0]
        self.assertTrue(w["valid"])
        i0 = np.searchsorted(rec.t_us, w["t0_us"])
        np.testing.assert_array_equal(w["fill_mask"], rec.filled[i0:i0 + 448])
        self.assertAlmostEqual(w["fill_mask"].mean(), 0.1, delta=0.01)
        # filled samples stay in the data
        self.assertFalse(np.all(w["imu"][w["fill_mask"]] == 0))

    def test_audio_validity(self):
        parts = F.build(audio_offset_us=300_000)  # audio starts 0.3 s after the IMU
        parts["meta"]["audio"]["muted_spans_us"] = [[F.us(6.0), F.us(6.2)]]
        parts["meta"]["gaps"]["audio_gap_frames"] = [[16000 * 3, 16000 * 3 + 256]]
        parts["meta"]["gaps"]["audio_missing_frames"] = 256
        rec = dio.read_recording(F.write(self.tmp / "aud", parts))
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual(ws[0.0]["reason"], "audio_range")
        self.assertTrue(ws[0.5]["valid"])
        self.assertEqual(ws[5.5]["reason"], "audio_muted")
        self.assertEqual(ws[3.0]["reason"], "audio_gap")


class LabelTest(F.TempDirTest):
    def test_centre_and_majority(self):
        rec = load(self.tmp, "lab")
        c = by_start(W.iter_windows(rec, 1.0, 0.5, label="centre"))
        m = by_start(W.iter_windows(rec, 1.0, 0.5, label="majority"))
        # [1.0, 2.0): spinup 0.3 s, free_run 0.3 s, drilling 0.4 s
        self.assertEqual(c[1.0]["phase"], "free_run")
        self.assertEqual(m[1.0]["phase"], "drilling")
        self.assertEqual(c[2.0]["hole"], 1)
        self.assertEqual(c[2.0]["phase_code"], 3)
        self.assertEqual((m[2.0]["phase"], m[2.0]["hole"]), ("drilling", 1))
        self.assertEqual((m[5.0]["phase"], m[5.0]["hole"]), ("drilling", 2))
        with self.assertRaises(ValueError):
            list(W.iter_windows(rec, label="mode"))

    def test_soft_policy_and_overlays(self):
        spans = [{"id": "s1", "type": "ignore", "reason": "bump", "t0_us": F.us(0.2), "t1_us": F.us(0.4),
                  "source": "user", "confidence": 1.0},
                 {"id": "s2", "type": "wobble", "t0_us": F.us(2.0), "t1_us": F.us(2.5), "source": "user",
                  "confidence": 1.0}]
        rec = load(self.tmp, "ov", spans=spans)
        ws = by_start(W.iter_windows(rec, 1.0, 0.5, boundary_policy="soft"))
        self.assertEqual(ws[0.0]["weight"], 0.0)
        self.assertAlmostEqual(ws[0.0]["overlays"]["ignore"], 0.2)
        self.assertAlmostEqual(ws[2.0]["overlays"]["wobble"], 0.5)
        self.assertEqual(ws[2.0]["soft"].shape, (7,))
        self.assertAlmostEqual(float(ws[2.0]["soft"].sum()), 1.0, places=5)

    def test_short_and_overlapping_spans_are_exact(self):
        # a 40 ms bump falls between any coarse sampling grid; overlapping spans count once
        spans = [{"id": "s1", "type": "ignore", "reason": "bump", "t0_us": F.us(0.51), "t1_us": F.us(0.55),
                  "source": "auto", "confidence": 0.9},
                 {"id": "s2", "type": "wobble", "t0_us": F.us(2.0), "t1_us": F.us(2.5), "source": "user",
                  "confidence": 1.0},
                 {"id": "s3", "type": "wobble", "t0_us": F.us(2.25), "t1_us": F.us(2.75), "source": "auto",
                  "confidence": 0.6}]
        rec = load(self.tmp, "short", spans=spans)
        ws = by_start(W.iter_windows(rec, 1.0, 0.5, boundary_policy="hard"))
        self.assertAlmostEqual(ws[0.0]["overlays"]["ignore"], 0.04)
        self.assertEqual(ws[0.0]["weight"], 0.0)
        self.assertEqual(ws[1.0]["weight"], 1.0)
        self.assertAlmostEqual(ws[2.0]["overlays"]["wobble"], 0.75)
        self.assertAlmostEqual(ws[2.5]["overlays"]["wobble"], 0.25)

    def test_recording_labels_with_hole_override(self):
        parts = F.build()
        parts["events"]["holes"][0]["material"] = "wood/hardwood"
        parts["events"]["holes"][1]["outcome_tags"] = ["wandered_start"]
        rec = dio.read_recording(F.write(self.tmp / "ovr", parts))
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual(ws[2.0]["labels"]["material"], "wood/hardwood")
        self.assertEqual(ws[4.5]["labels"]["material"], "wood/softwood")
        self.assertIn("wandered_start", ws[4.5]["labels"]["outcome_tags"])
        self.assertEqual(ws[0.0]["labels"]["imu_profile"], "RAW448")
        # the tool (meta.session.drill), the session's for every hole
        for w in (ws[0.0], ws[2.0], ws[4.5]):
            self.assertEqual((w["labels"]["drill_type"], w["labels"]["motor"]), ("drill", "brushless"))

    def test_hole_tags_override_the_session_and_off_angle_is_per_hole(self):
        # hole 1 is 2 deg off, hole 2 on target: the recording-level off angle is above the tolerance
        parts = F.build(tol_deg=0.5)
        lvl = parts["level"]
        hole1 = lvl["t_us"] < F.us(4.0)
        lvl.loc[hole1, ["elevation_cdeg", "deviation_cdeg"]] = -200
        parts["meta"]["session"]["outcome_tags"] = ["aborted"]
        parts["events"]["holes"][1]["outcome_tags"] = ["wandered_start"]
        rec = dio.read_recording(F.write(self.tmp / "tags", parts))
        self.assertEqual(rec.meta["derived"]["tags"], ["off_angle"])
        ws = by_start(W.iter_windows(rec, 1.0, 0.5))
        self.assertEqual((ws[2.0]["hole"], ws[5.0]["hole"], ws[0.0]["hole"]), (1, 2, 0))
        self.assertEqual(ws[2.0]["labels"]["outcome_tags"], ["clean", "off_angle"])
        self.assertEqual(ws[5.0]["labels"]["outcome_tags"], ["wandered_start"])
        self.assertEqual(ws[0.0]["labels"]["outcome_tags"], ["aborted", "off_angle"])   # no hole: the take's
        self.assertEqual(ws[5.0]["labels"]["off_angle_deg"], rec.events["holes"][1]["off_angle_deg"])

    def test_labels_are_built_once_per_hole(self):
        rec = load(self.tmp, "once")
        with mock.patch.object(W, "recording_labels", wraps=W.recording_labels) as rl:
            ws = list(W.iter_windows(rec, 0.25, 0.25))
        self.assertEqual(sorted(c.args[1] for c in rl.call_args_list), sorted({w["hole"] for w in ws}))
        ws[0]["labels"]["outcome_tags"].append("x")       # each window has its own copy
        self.assertNotIn("x", ws[1]["labels"]["outcome_tags"])

    def test_majority_soft_window_without_imu_samples(self):
        # a 1.5 s transport gap holds whole windows without one IMU sample
        rec = load(self.tmp, "longgap", gap=(2.0, 3.5))
        ws = list(W.iter_windows(rec, 0.5, 0.25, label="majority", boundary_policy="soft"))
        empty = [w for w in ws if np.searchsorted(rec.t_us, w["t0_us"]) == np.searchsorted(rec.t_us, w["t1_us"])]
        self.assertTrue(empty)
        self.assertTrue(all("soft" in w for w in ws))
        for w in empty:
            self.assertEqual((w["phase"], w["weight"], w["valid"]), ("unknown", 0.0, False))
            np.testing.assert_array_equal(w["soft"], np.eye(7, dtype=np.float32)[w["phase_code"]])

    def test_deprecated_ids_are_resolved(self):
        tdoc = contract.load_json(contract.TAXONOMY_FILE)
        tdoc["fields"]["material"].append({"id": "wood/osb", "added": 1, "deprecated": True,
                                           "replaced_by": "wood/plywood"})
        tdoc["fields"]["outcome_tag"].append({"id": "stuck", "added": 1, "deprecated": True,
                                              "replaced_by": "bit_stuck"})
        tdoc["fields"]["drill_type"].append({"id": "hammer_drill", "added": 1, "deprecated": True,
                                             "replaced_by": "combi_drill"})
        tdoc["fields"]["motor"].append({"id": "corded", "added": 1, "deprecated": True,
                                        "replaced_by": "universal_ac"})
        tax = contract.Taxonomy(tdoc)
        parts = F.build(material="wood/osb", drill_type="hammer_drill", motor="corded")
        parts["meta"]["session"]["outcome_tags"] = ["stuck"]
        rec = dio.read_recording(F.write(self.tmp / "dep", parts))
        w = next(W.iter_windows(rec, 1.0, 0.5, taxonomy=tax))
        self.assertEqual((w["labels"]["material"], w["labels"]["outcome_tags"]), ("wood/plywood", ["bit_stuck"]))
        self.assertEqual((w["labels"]["drill_type"], w["labels"]["motor"]), ("combi_drill", "universal_ac"))


if __name__ == "__main__":
    unittest.main()
