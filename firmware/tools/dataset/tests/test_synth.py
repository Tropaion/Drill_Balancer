"""Synthetic recordings (dbdata.synth): the simulator's take through dbrec's export, with the truth as labels."""

import contextlib
import io
import json
import unittest

import fixtures as F
from dbdata import labels, synth
from dbdata import validate as V
from dbdata.io import RECORDING_ID_RE, read_recording

SIM = synth.sim_unavailable_reason()

TRUTH = {
    "events": [{"type": "motor_on", "t_us": 1_000}, {"type": "contact", "t_us": 2_000, "hole": 1},
               {"type": "kickback", "t_us": 2_500, "hole": 1}, {"type": "motor_off", "t_us": 3_000, "hole": 1},
               {"type": "motor_on", "t_us": 4_000, "hole": 1}, {"type": "retract", "t_us": 5_000, "hole": 1},
               {"type": "motor_off", "t_us": 6_000}, {"type": "motor_on", "t_us": 7_000},
               {"type": "contact", "t_us": 8_000, "hole": 2}, {"type": "breakthrough", "t_us": 9_000, "hole": 2},
               {"type": "motor_off", "t_us": 99_000}],
    "spans": [{"type": "stuck", "t0_us": 2_400, "t1_us": 3_000, "hole": 1}],
    "holes": [{"hole": 1, "through": False}, {"hole": 2, "through": True}],
}


class TruthTest(unittest.TestCase):
    def test_truth_events(self):
        ev = synth.truth_events(TRUTH, [0, 10_000], F.RID)
        self.assertEqual([e["type"] for e in ev["events"]],
                         ["motor_on", "contact", "kickback", "motor_off", "motor_on", "retract", "motor_off",
                          "motor_on", "contact", "breakthrough"])     # the last one is outside coverage
        self.assertTrue(all(e["source"] == "user" and e["confidence"] == 1.0 for e in ev["events"]))
        self.assertNotIn("hole", ev["events"][3])                    # motor events never carry one
        self.assertEqual(ev["spans"][0]["type"], "stuck")
        self.assertEqual({h["hole"]: h["outcome_tags"] for h in ev["holes"]},
                         {1: ["bit_stuck", "kickback"], 2: ["clean"]})
        self.assertEqual(labels.check_grammar(ev), [])

    def test_a_hole_the_take_ends_in(self):
        # the detector's rule: through null and no clean tag for a hole the take did not see end
        ev = synth.truth_events(TRUTH, [0, 8_500], F.RID)
        self.assertEqual({h["hole"]: (h["through"], h["outcome_tags"]) for h in ev["holes"]},
                         {1: (False, ["bit_stuck", "kickback"]), 2: (None, [])})

    def test_a_motor_running_when_the_data_begins(self):
        seg = [{"state": s, "t0_us": a, "t1_us": b}
               for s, a, b in (("off", 0, 500), ("spinup", 500, 1_500), ("free", 1_500, 1_900), ("load", 1_900, 2_000))]
        truth = {**TRUTH, "events": TRUTH["events"][1:], "segments": seg}
        ev = synth.truth_events(truth, [1_000, 10_000], F.RID)
        self.assertEqual([(e["type"], e["t_us"]) for e in ev["events"][:2]],
                         [("motor_on", 1_000), ("contact", 2_000)])
        self.assertEqual(labels.check_grammar(ev), [])
        self.assertEqual(synth.truth_events(truth, [0, 10_000], F.RID)["events"][0]["type"], "contact")
        with self.assertRaises(ValueError):
            synth.truth_events(truth, [1_950, 10_000], F.RID)              # in the hole already

    def test_the_data_begins_in_a_hole(self):
        # the bit stays in hole 1 from its contact through the stall, the motor off and the restart's
        # spin-up to the retract: a take beginning anywhere there has no truth (no contact for the hole)
        seg = [{"state": s, "t0_us": a, "t1_us": b} for s, a, b in (
            ("spinup", 1_000, 1_500), ("free", 1_500, 2_000), ("load", 2_000, 2_400), ("stuck", 2_400, 3_000),
            ("spindown", 3_000, 3_250), ("off", 3_250, 4_000), ("spinup", 4_000, 4_300), ("load", 4_300, 5_000),
            ("retract", 5_000, 5_600), ("free", 5_600, 6_000))]
        truth = {**TRUTH, "segments": seg}
        for c0 in (3_100, 3_500, 4_100, 4_500):
            with self.assertRaises(ValueError, msg=c0):
                synth.truth_events(truth, [c0, 10_000], F.RID)
            # as DbSimDevice.rec_truth() gives it: no events before the take's start
            later = {**truth, "events": [e for e in truth["events"] if e["t_us"] >= c0]}
            with self.assertRaises(ValueError, msg=f"{c0}, from the take start"):
                synth.truth_events(later, [c0, 10_000], F.RID)
        ev = synth.truth_events(truth, [5_500, 10_000], F.RID)           # out of it again: the motor runs
        self.assertEqual([e["type"] for e in ev["events"][:2]], ["motor_on", "motor_off"])
        self.assertEqual(labels.check_grammar(ev), [])

    def test_ids_and_sets(self):
        ids = [synth.recording_id(n) for n in range(200)]
        self.assertEqual(len(set(ids)), 200)
        self.assertTrue(all(RECORDING_ID_RE.match(i) for i in ids))
        names = [s.name for specs in synth.SETS.values() for s in specs]
        self.assertEqual(len(names), len(set(names)))
        hammer = [s for s in synth.SETS["eval"] if s.mode == "hammer"]
        self.assertTrue(hammer and all(s.profile_name == "RAW448_16G" for s in hammer))
        self.assertEqual(synth.Spec("x").profile_name, "RAW448")

    def test_list(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(synth.main(["--list"]), 0)
        self.assertIn("eval:", out.getvalue())
        self.assertIn("fx_stuck_kick", out.getvalue())
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(synth.main([]), 2)


@unittest.skipIf(SIM, f"no simulator: {SIM}")
class SynthTest(F.TempDirTest):
    def test_a_take_is_a_valid_recording_with_its_truth(self):
        spec = synth.Spec("t", {"seed": 5, "mode": "hammer", "holes": 2, "outcome": "mixed", "drill_s": 1.0,
                                "free_s": 0.6}, mic_mask=1, material="masonry/concrete")
        s = synth.synthesize(spec, self.tmp, rid=synth.recording_id(7))
        rep = V.validate_recording(s.path)
        self.assertTrue(rep.ok, [(i.code, i.message) for i in rep.issues])
        rec = read_recording(s.path)
        m = rec.meta
        self.assertEqual((m["app"]["tool"], m["review"]["status"], m["imu"]["profile"]),
                         ("dbdata.synth", "reviewed", "RAW448_16G"))
        self.assertEqual((m["session"]["material"], m["session"]["mode"]), ("masonry/concrete", "hammer"))
        self.assertEqual(m["audio"]["channels"], ["mic1"])
        self.assertEqual(rec.audio.shape[1], 1)
        # the labels are the device-time truth of the scenario, inside the data
        self.assertEqual([e["type"] for e in rec.events["events"]], [e["type"] for e in s.truth["events"]])
        self.assertEqual([e["t_us"] for e in rec.events["events"]], [e["t_us"] for e in s.truth["events"]])
        t = rec.t_us
        self.assertTrue(all(t[0] < e["t_us"] < t[-1] for e in rec.events["events"]))
        self.assertEqual([h["through"] for h in rec.events["holes"]], [True, False])
        # the FIFO-loss model's filled samples are in the data, flagged
        self.assertGreater(m["gaps"]["imu_filled_samples"], 0.05 * len(rec.imu))

    def test_injected_signals_gap_and_mount(self):
        # what the fixtures need beyond a plain take: injected wobble / wander / speech with their
        # spans, a transport gap from a stalled client, a calibrated mount axis
        base = dict(seed=207, holes=1, sag=0.2, free_s=0.8, drill_s=1.8, mic_mask=1)
        s = synth.synthesize(synth._s("inj", tail_s=3.0, inject=synth.INJECTIONS, **base), self.tmp,
                             rid=synth.recording_id(8))
        rep = V.validate_recording(s.path)
        self.assertTrue(rep.ok, [(i.code, i.message) for i in rep.issues])
        self.assertEqual(rep.issues, [])                                 # meta.files rewritten with them
        self.assertEqual(sorted((x["type"], x.get("hole"), x.get("reason")) for x in s.events["spans"]),
                         [("ignore", None, "speech"), ("wander", 1, None), ("wobble", 1, None)])
        self.assertGreater(s.meta["derived"]["off_angle_deg"], 0.9)    # the 3 deg drift in the level rows
        with self.assertRaises(ValueError):
            synth.inject(s.path, ("hum",), s.events)
        g = synth.synthesize(synth._s("gap", ring_kib=32, stall=(2.3, 1.6), **base), self.tmp,
                             rid=synth.recording_id(9))
        self.assertTrue(g.meta["gaps"]["transport_gaps"])
        self.assertGreater(g.meta["gaps"]["audio_missing_frames"], 8000)
        self.assertIn("W_MISSING", V.validate_recording(g.path).codes())
        no_off = {**g.events, "events": [e for e in g.events["events"] if e["type"] != "motor_off"]}
        with self.assertRaisesRegex(ValueError, "no motor_off"):
            synth.inject(g.path, ("speech",), no_off)
        m = synth.synthesize(synth._s("mnt", mount_offset=(-3.0, 2.5, 1.0), **base), self.tmp,
                             rid=synth.recording_id(10))
        mount = m.meta["imu"]["mount"]
        self.assertTrue(mount["valid"])
        self.assertGreater(abs(mount["drill_axis"][0]), 0.02)       # tilted, as the board is on the drill

    def test_inject_into_a_retract_take_without_audio(self):
        s = synth.synthesize(synth._s("ret", seed=209, holes=1, outcome="retract", sag=0.2, free_s=0.8,
                                      drill_s=1.8, mic_mask=0), self.tmp, rid=synth.recording_id(11))
        self.assertIsNone(s.meta["audio"])
        with self.assertRaisesRegex(ValueError, "no audio"):
            synth.inject(s.path, ("speech",), s.events)
        meta, events = synth.inject(s.path, ("wobble", "wander"), s.events)
        retract = next(e["t_us"] for e in events["events"] if e["type"] == "retract")
        self.assertEqual(sorted((x["type"], x["t1_us"] <= retract) for x in events["spans"]),
                         [("wander", True), ("wobble", True)])
        rep = V.validate_recording(s.path)
        self.assertTrue(rep.ok, [(i.code, i.message) for i in rep.issues])

    def test_cli_writes_a_set(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(synth.main([str(self.tmp), "--set", "fixtures", "--count", "1"]), 0)
        self.assertIn("fx_drill_through", out.getvalue())
        (folder,) = V.find_recordings(self.tmp)
        self.assertTrue(V.validate_recording(folder).ok)
        self.assertEqual(json.loads((folder / "meta.json").read_text())["session"]["notes"],
                         "synthetic: fx_drill_through")


if __name__ == "__main__":
    unittest.main()
