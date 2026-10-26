"""The auto-detector (dbdata.autodetect).

Event F1 and phase accuracy on synth's "eval" set (simulated takes with
ground truth: drill and hammer, several motor speeds and sags, blind holes,
stuck bits (also while hammering, and a quiet slow motor's restart),
kickbacks, quiet and noisy rooms, floor and ceiling, RAW897), a louder room
as a holdout, determinism, the golden fixtures of the Kotlin port, the
suggestion rules on injected signals, and the CLI. The simulated takes need
the simulator's C core (skipped without it); everything else runs on the
committed fixtures.
"""

import contextlib
import copy
import dataclasses
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import numpy as np

import fixtures as F
from dbdata import autodetect as A
from dbdata import labels, synth
from dbdata import validate as V
from dbdata.io import read_recording, recording_dir

SIM = synth.sim_unavailable_reason()
FX = {p.name: p for p in A.fixture_folders()}

# Achieved on the eval set (21 takes; 37 motor runs, 33 contacts, 23 breakthroughs, 10 retracts,
# 2 kickbacks, 4 stuck spans) with the contract's tolerances (motor +-30 ms, contact +-100 ms,
# breakthrough +-50 ms; retract and kickback +-100 ms; spans at IoU >= 0.5): motor 0.959 (motor_on
# 0.919: the noisy and quiet rooms hide the first 30-90 ms of the spin-up; motor_off 1.0), the
# rest 1.0; phase accuracy per take mean 0.993, lowest 0.974. Three other seed sets of the same
# scenarios (the scenario and device seeds + 1000 / 2000 / 7777): motor_on 0.892, 0.892, 0.865,
# phase lowest 0.971-0.979, the rest the same. Each threshold leaves one more miss than the worst
# of the four, so a simulator change that only moves the random streams does not fail the suite.
F1_MIN = {"motor": 0.91, "motor_on": 0.83, "motor_off": 0.97, "contact": 0.96, "breakthrough": 0.95,
          "retract": 0.9, "kickback": 0.66, "stuck": 0.85}
PHASE_MEAN_MIN = 0.985
PHASE_LOWEST_MIN = 0.96
# The holdout in a room twice as loud (6 eval scenarios): motor_on 0.583 at +-30 ms (late onsets:
# the dataset README's known limits), every one within +-100 ms, the rest 1.0.
ROOM2 = ("wood_233", "wood_147", "metal_slow", "blind_retract", "hammer_concrete", "quiet_drill")
ROOM2_MOTOR_ON_MIN = 0.5
# Fixtures without audio find only the motor runs, late (the dataset README's known limits).
NO_AUDIO_MOTOR_US = 250_000


def totals(pairs) -> dict:
    """Summed counts per event type (and "motor": motor_on + motor_off) over (truth, detected) pairs."""
    tot = {}
    for truth, det in pairs:
        for typ, c in A.score(truth, det).items():
            for key in (typ, "motor") if typ.startswith("motor") else (typ,):
                d = tot.setdefault(key, {"tp": 0, "fp": 0, "fn": 0, "errors_us": []})
                for k in ("tp", "fp", "fn"):
                    d[k] += c[k]
                d["errors_us"] += c.get("errors_us", [])
    return tot


@unittest.skipIf(SIM, f"no simulator: {SIM}")
class EvalSetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.takes = []
        for s in synth.synthesize_set("eval", cls._tmp.name):
            rec = read_recording(s.path)
            cls.takes.append((s, rec, A.detect(rec)))

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_event_f1(self):
        tot = totals((rec.events, det.events) for _, rec, det in self.takes)
        report = {k: round(A.f1(v), 3) for k, v in tot.items() if v["tp"] + v["fp"] + v["fn"]}
        for key, lo in F1_MIN.items():
            with self.subTest(key):
                self.assertGreaterEqual(A.f1(tot[key]), lo, report)
        # timing of the matches: the breakthrough from the impulse onset, the rest from 10 ms frames
        err = {k: np.mean(np.abs(v["errors_us"])) / 1000 for k, v in tot.items() if v["errors_us"]}
        self.assertLess(err["breakthrough"], 2.0, err)
        self.assertLess(err["contact"], 15.0, err)
        self.assertLess(err["motor_off"], 10.0, err)

    def test_phase_accuracy(self):
        acc = [A.phase_accuracy(rec.events, det.events) for _, rec, det in self.takes]
        self.assertGreaterEqual(float(np.mean(acc)), PHASE_MEAN_MIN, acc)
        self.assertGreaterEqual(min(acc), PHASE_LOWEST_MIN, acc)

    def test_no_suggestions_without_cause(self):
        # the simulator has no wobble, wander or speech; hammer blows shake the device's level
        for s, _, det in self.takes:
            with self.subTest(s.spec.name):
                self.assertEqual([x["type"] for x in det.events["spans"] if x["type"] != "stuck"], [])

    def test_output_is_a_valid_label_set(self):
        for s, rec, det in self.takes:
            with self.subTest(s.spec.name):
                ev = det.events
                self.assertEqual(labels.check_grammar(ev), [])
                self.assertTrue(all(e["source"] == "auto" and 0 <= e["confidence"] <= 1 for e in ev["events"]))
                self.assertEqual(ev["detector"], {"version": A.load_params().version,
                                                  "params_sha256": A.load_params().sha256})
                self.assertEqual(ev["coverage_us"], rec.events["coverage_us"])
                truth_holes = {h["hole"]: h["through"] for h in rec.events["holes"]}
                self.assertEqual({h["hole"]: h["through"] for h in ev["holes"]}, truth_holes)

    def test_written_labels_validate(self):
        with tempfile.TemporaryDirectory() as tmp:
            for s, rec, det in self.takes[:6]:
                dest = Path(tmp) / s.path.relative_to(self._tmp.name)
                shutil.copytree(s.path, dest)
                A.write_labels(dest, det, force=True)
                rep = V.validate_recording(dest)
                self.assertTrue(rep.ok, [(i.code, i.message) for i in rep.issues])
                self.assertFalse({"W_REVIEW_STATUS", "W_DETECTOR"} & rep.codes())
                meta = json.loads((dest / "meta.json").read_text())
                self.assertEqual(meta["review"]["status"], "auto_only")
                self.assertEqual(meta["review"]["detector"], det.events["detector"]["version"])
                self.assertEqual(meta["review"]["revision"], 2)     # synth's truth was revision 1

    def test_kickback_and_stuck_tags(self):
        names = {s.spec.name: det for s, _, det in self.takes}
        holes = {h["hole"]: h for h in names["stuck_kick"].events["holes"]}
        self.assertEqual(holes[1]["outcome_tags"], ["bit_stuck", "kickback"])
        self.assertEqual(holes[1]["through"], False)
        kicks = [e for e in names["kick_breakthrough"].events["events"] if e["type"] == "kickback"]
        self.assertEqual([k["hole"] for k in kicks], [1])
        # every hole's tags as the truth has them (clean: ended without a problem found)
        for s, rec, det in self.takes:
            with self.subTest(s.spec.name):
                self.assertEqual([h["outcome_tags"] for h in det.events["holes"]],
                                 [h["outcome_tags"] for h in rec.events["holes"]])


@unittest.skipIf(SIM, f"no simulator: {SIM}")
class LoudRoomTest(unittest.TestCase):
    """A holdout in a room twice as loud: the onsets of the motor come late (the first part of
    the spin-up drowns), nothing else moves."""

    def test_room_x2(self):
        pairs = []
        with tempfile.TemporaryDirectory() as tmp:
            specs = [dataclasses.replace(s, room=2.0) for s in synth.SETS["eval"] if s.name in ROOM2]
            self.assertEqual(len(specs), len(ROOM2))
            for i, s in enumerate(specs):
                rec = read_recording(synth.synthesize(s, tmp, rid=synth.recording_id(1 + i)).path)
                pairs.append((rec.events, A.detect(rec).events))
        tot = totals(pairs)
        report = {k: round(A.f1(v), 3) for k, v in tot.items() if v["tp"] + v["fp"] + v["fn"]}
        self.assertGreaterEqual(A.f1(tot["motor_on"]), ROOM2_MOTOR_ON_MIN, report)
        for key in ("motor_off", "contact", "breakthrough", "retract"):
            self.assertEqual(A.f1(tot[key]), 1.0, report)
        wide = {**A.TOLERANCES_US, "motor_on": 100_000}
        late = sum(A.score(t, d, wide)["motor_on"]["fn"] for t, d in pairs)
        self.assertEqual(late, 0, "every motor_on within 100 ms")
        self.assertGreater(np.median(tot["motor_on"]["errors_us"]), 0, "the onsets come late, not early")


@unittest.skipIf(SIM, f"no simulator: {SIM}")
class DeterminismTest(F.TempDirTest):
    def test_same_spec_same_recording_same_labels(self):
        spec = synth.SETS["fixtures"][2]
        a = synth.synthesize(spec, self.tmp / "a", rid=synth.recording_id(1))
        b = synth.synthesize(spec, self.tmp / "b", rid=synth.recording_id(1))
        self.assertEqual(a.meta["files"], b.meta["files"])      # sha256 of every data file
        self.assertEqual((a.path / "meta.json").read_bytes(), (b.path / "meta.json").read_bytes())
        da, db = A.detect(read_recording(a.path)), A.detect(read_recording(b.path))
        self.assertEqual(da.events, db.events)
        for c in A.FEATURE_COLUMNS[2:]:
            np.testing.assert_array_equal(da.features[c], db.features[c])


@unittest.skipUnless(FX, "fixtures/autodetect is missing")
class FixtureTest(unittest.TestCase):
    """The golden fixtures the app's Kotlin port replays (fixtures/autodetect/README.md)."""

    def test_the_fixtures_are_there(self):
        self.assertEqual(sorted(FX), sorted(s.name for s in synth.SETS["fixtures"]))
        for p in FX.values():
            audio = json.loads((p / "meta.json").read_text())["audio"] is not None
            for name in ("meta.json", "events.json", "imu.csv.gz", "level.csv.gz", "expected.json",
                         "features.csv.gz", "spec.json") + (("audio.flac", "audio_sync.csv") if audio else ()):
                self.assertTrue((p / name).is_file(), p / name)

    def test_the_session_labels_are_the_scenarios(self):
        # meta.session as synth writes it for the spec in SETS (the tool too: patched in place, not re-simulated)
        specs = {s.name: s for s in synth.SETS["fixtures"]}
        for name, p in FX.items():
            with self.subTest(name):
                s, spec = json.loads((p / "meta.json").read_text())["session"], specs[name]
                self.assertEqual((s["mode"], s["material"], s["bit"]["type"], s["drill"]["type"], s["drill"]["motor"]),
                                 (spec.mode, spec.material, spec.bit_type, spec.drill_type_id, spec.motor))

    def test_replay(self):
        for name, p in FX.items():
            with self.subTest(name):
                self.assertEqual(A.check_fixture(p), [])

    def test_check_finds_a_difference(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / "fx"
            shutil.copytree(FX["fx_drill_through"], dest)
            exp = json.loads((dest / "expected.json").read_text())
            exp["events"]["events"][0]["t_us"] += 10_000
            (dest / "expected.json").write_text(json.dumps(exp))
            self.assertEqual(A.check_fixture(dest), ["events differ"])

    def test_fixtures_are_right(self):
        # the expected output matches the scenario's truth (events.json of the fixture)
        for name, p in FX.items():
            with self.subTest(name):
                truth = json.loads((p / "events.json").read_text())
                det = json.loads((p / "expected.json").read_text())["events"]
                if not json.loads((p / "spec.json").read_text())["mic_mask"]:
                    # no microphone: the motor runs only, late (README, known limits)
                    sc = A.score(truth, det, {"motor_on": NO_AUDIO_MOTOR_US, "motor_off": NO_AUDIO_MOTOR_US})
                    for typ in ("motor_on", "motor_off"):
                        self.assertEqual((sc[typ]["fp"], sc[typ]["fn"]), (0, 0), (typ, sc[typ]))
                    self.assertEqual({e["type"] for e in det["events"]}, {"motor_on", "motor_off"})
                    continue
                tot = totals([(truth, det)])
                for typ, c in tot.items():
                    self.assertEqual((c["fp"], c["fn"]), (0, 0), (typ, c))
                self.assertGreater(A.phase_accuracy(truth, det), 0.98)
                self.assertEqual([(h["through"], h["outcome_tags"]) for h in det["holes"]],
                                 [(h["through"], h["outcome_tags"]) for h in truth["holes"]])

    def test_fixtures_cover_the_branches(self):
        # what the Kotlin port needs a vector for (fixtures/autodetect/README.md)
        meta = {n: json.loads((p / "meta.json").read_text()) for n, p in FX.items()}
        exp = {n: json.loads((p / "expected.json").read_text())["events"] for n, p in FX.items()}
        self.assertTrue(any(m["audio"] is None for m in meta.values()), "no audio")
        self.assertTrue(any(m["imu"]["odr_nominal_hz"] > 800 for m in meta.values()), "RAW897")
        self.assertTrue(any(m["imu"]["mount"]["valid"] for m in meta.values()), "a calibrated mount axis")
        self.assertTrue(any(m["gaps"]["transport_gaps"] and m["gaps"]["audio_gap_frames"] for m in meta.values()),
                        "a transport gap")
        types = [[e["type"] for e in ev["events"] if e["type"] != "kickback"] for ev in exp.values()]
        self.assertTrue(any(t.count("contact") > 1 for t in types), "several holes")
        self.assertTrue(any(t[-1] != "motor_off" for t in types), "an open end")
        pulled = A.load_params()["confidence"]["restart_retract"]
        self.assertTrue(any(e["type"] == "retract" and e["confidence"] == pulled
                            for ev in exp.values() for e in ev["events"]), "pulled out with the motor off")
        self.assertTrue(any(t[t.index("motor_off"):][:2] == ["motor_off", "motor_on"] and
                            "retract" not in t[:t.index("motor_off")] for t in types if "motor_off" in t),
                        "a restart in the hole")
        spans = {s["type"] for ev in exp.values() for s in ev["spans"]}
        self.assertEqual(spans, {"stuck", "wobble", "wander", "ignore"})
        kick = [(n, e) for n, ev in exp.items() for e in ev["events"] if e["type"] == "kickback"]
        self.assertTrue(kick)

    def test_params_hash_and_version(self):
        p = A.load_params()
        self.assertEqual(p.sha256, A.params_sha256(A.PARAMS_FILE.read_bytes()))
        self.assertEqual(A.params_sha256(b"{\r\n}\r\n"), A.params_sha256(b"{\n}\n"))
        for fx in FX.values():
            self.assertEqual(json.loads((fx / "expected.json").read_text())["detector"],
                             {"version": p.version, "params_sha256": p.sha256})
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "p.json"
            bad.write_text('{"schema": "other"}')
            with self.assertRaises(ValueError):
                A.load_params(bad)

    def test_every_parameter_is_used(self):
        # the params file is the port's contract: nothing in it may be dead. Every (section, key)
        # the detector reads while it replays the fixtures is recorded.
        read = set()

        class Tracked(dict):
            def __init__(self, d, path=()):
                super().__init__({k: Tracked(v, path + (k,)) if isinstance(v, dict) else v for k, v in d.items()})
                self.path = path

            def __getitem__(self, k):
                read.add(self.path + (k,))
                return super().__getitem__(k)

            def get(self, k, default=None):
                read.add(self.path + (k,))
                return super().get(k, default)

        def leaves(d, path=()):
            for k, v in d.items():
                if isinstance(v, dict):
                    yield from leaves(v, path + (k,))
                else:
                    yield path + (k,)

        p = A.load_params()
        tracked = A.Params(Tracked(p.doc), p.sha256)
        for fx in FX.values():
            A.detect(read_recording(fx), tracked)
        unused = [".".join(k) for k in leaves(p.doc) if k[0] not in ("schema", "note") and k not in read]
        self.assertEqual(unused, [])


@unittest.skipUnless("fx_drill_through" in FX, "fixtures/autodetect is missing")
class HelperTest(unittest.TestCase):
    """The vectorised helpers against the definitions the Kotlin port follows."""

    def test_median_is_numpys_percentile_to_the_bit(self):
        rng = np.random.default_rng(5)
        for n in list(range(1, 12)) + [33, 100, 101]:
            for scale in (1e-6, 1.0, 1e6):
                v = rng.normal(0.0, scale, n)
                v[rng.random(n) < 0.2] = np.nan
                fin = v[np.isfinite(v)]
                want = float(np.percentile(fin, 50)) if len(fin) else None
                got = A.med(v)
                self.assertTrue(np.isnan(got) if want is None else got == want, (n, scale))
        self.assertTrue(np.isnan(A.med([np.inf, np.nan])))

    def test_centred_median_is_med_per_frame(self):
        rng = np.random.default_rng(6)
        v = rng.normal(0.0, 1.0, 400)
        v[rng.random(400) < 0.3] = np.nan
        v[17:40] = np.nan                       # all-NaN windows too
        v[3] = np.inf
        for n in (1, 3, 5, 9):
            h = n // 2
            want = [A.med(v[max(0, k - h):k + h + 1]) for k in range(len(v))]
            np.testing.assert_array_equal(A.centred_median(v, n), want)
        self.assertEqual(len(A.centred_median(np.zeros(0), 5)), 0)

    def test_biquad_is_the_reference(self):
        rng = np.random.default_rng(7)
        for n in (0, 1, 2, 3, 127, 128, 129, 5000):
            x = 1.0 + np.cumsum(rng.normal(0.0, 0.01, n)) + rng.normal(0.0, 0.1, n)
            for fs in (448.4, 896.8):
                for kind, fc in (("hp", 20.0), ("hp", 1.0), ("lp", 8.0), ("lp", 20.0)):
                    co = A.biquad_coeffs(kind, fc, fs)
                    ref = A.biquad_reference(x, co, kind)
                    got = A.biquad(x, co, kind)
                    self.assertEqual(got.shape, ref.shape)
                    if n:
                        self.assertLess(np.max(np.abs(got - ref)), 1e-10 * max(1.0, np.max(np.abs(ref))),
                                        (n, fs, kind, fc))


class SuggestionTest(unittest.TestCase):
    """wobble, wander and speech on a fixture take with the signal injected."""

    def setUp(self):
        self.rec = read_recording(FX["fx_drill_through"])
        self.t0 = int(self.rec.t_us[0])
        self.ev = {e["type"]: e["t_us"] for e in self.rec.events["events"]}

    def spans(self, det, typ):
        return [s for s in det.events["spans"] if s["type"] == typ]

    def test_plain_take_has_none(self):
        self.assertEqual(A.detect(self.rec).events["spans"], [])

    def test_wobble(self):
        t = self.rec.t_us
        sel = (t >= self.ev["contact"] + 200_000) & (t < self.ev["breakthrough"] - 100_000)
        lsb = self.rec.meta["imu"]["gyro_lsb_per_dps"]
        wob = 15.0 * lsb * np.sin(2 * np.pi * 4.0 * (t[sel] - self.t0) / 1e6)   # 15 dps at 4 Hz, radial
        self.rec.imu_raw[sel, 3] = np.clip(self.rec.imu_raw[sel, 3] + wob, -32768, 32767)
        det = A.detect(self.rec)
        (s,) = self.spans(det, "wobble")
        self.assertEqual(s["hole"], 1)
        self.assertLess(abs(s["t0_us"] - self.ev["contact"] - 200_000), 150_000)
        self.assertLess(abs(s["t1_us"] - self.ev["breakthrough"]), 200_000)
        self.assertLessEqual(s["confidence"], A.load_params()["wobble"]["confidence_max"])
        self.assertEqual([e["type"] for e in det.events["events"]],
                         ["motor_on", "contact", "breakthrough", "motor_off"])

    def test_wander(self):
        lvl = self.rec.level.copy()
        ramp = np.clip((lvl["t_us"].to_numpy() - self.ev["contact"]) / 1e6, 0.0, 1.0) * 300   # 3 deg in 1 s
        lvl["elevation_cdeg"] = lvl["elevation_cdeg"].to_numpy() + ramp.astype(np.int64)
        self.rec.level = lvl
        det = A.detect(self.rec)
        (s,) = self.spans(det, "wander")
        self.assertEqual((s["hole"], s["t0_us"]), (1, next(e["t_us"] for e in det.events["events"]
                                                           if e["type"] == "contact")))
        self.assertLessEqual(s["confidence"], A.load_params()["wander"]["confidence_max"])

    def extended(self, copies=3):
        """Inputs with the quiet lead (before motor_on) appended `copies` times: room for 1 s windows."""
        inp = A.Inputs.from_recording(self.rec)
        t, on = inp.t_us, self.ev["motor_on"]
        lead = t < on - 100_000
        step = int(1e6 / inp.odr_hz)
        span = int(t[lead][-1] - t[0]) + step
        ts, cs = [t], [inp.counts]
        for i in range(copies):
            ts.append(t[lead] + int(t[-1]) + step - int(t[0]) + i * span)
            cs.append(inp.counts[lead])
        inp.t_us, inp.counts = np.concatenate(ts), np.concatenate(cs)
        a0 = max(0, int(np.ceil((t[0] - inp.sync_a_us) / inp.sync_b_us)))
        a1 = int((on - 100_000 - inp.sync_a_us) / inp.sync_b_us)
        need = int((inp.t_us[-1] - inp.sync_a_us) / inp.sync_b_us) + 1 - len(inp.audio)
        quiet = inp.audio[a0:a1]
        inp.audio = np.concatenate([inp.audio, np.tile(quiet, need // len(quiet) + 1)[:need]])
        inp.coverage_us = (inp.coverage_us[0], int(inp.t_us[-1]) + 1)
        return inp

    def test_speech(self):
        inp = self.extended()
        self.assertEqual(A.detect(inp).events["spans"], [])
        x = inp.audio.astype(np.float64)
        ta = inp.sync_a_us + inp.sync_b_us * np.arange(len(x))
        noise = np.random.default_rng(3).normal(0.0, 1.0, len(x))
        band = np.convolve(noise, np.ones(3) / 3, "same") - np.convolve(noise, np.ones(50) / 50, "same")
        start = self.ev["motor_off"] + 1_000_000
        sel = ta >= start
        x[sel] += 0.03 * 32768 * band[sel] * 0.5 * (1.0 + np.sin(2 * np.pi * 4.0 * ta[sel] / 1e6))
        inp.audio = np.clip(x, -32768, 32767).astype(np.int16)
        det = A.detect(inp)
        (s,) = self.spans(det, "ignore")
        self.assertEqual(s["reason"], "speech")
        self.assertLess(abs(s["t0_us"] - start), 600_000)
        # the sound alone is no motor run: the IMU never confirms it
        self.assertEqual([e["type"] for e in det.events["events"]],
                         ["motor_on", "contact", "breakthrough", "motor_off"])


@unittest.skipUnless("fx_stuck_kick" in FX, "fixtures/autodetect is missing")
class RestartTest(unittest.TestCase):
    def test_restart_in_the_hole_or_pulled_out(self):
        rec = read_recording(FX["fx_stuck_kick"])
        det = A.detect(rec)
        types = [e["type"] for e in det.events["events"] if e["type"] != "kickback"]
        self.assertEqual(types, ["motor_on", "contact", "motor_off", "motor_on", "retract", "motor_off"])
        # a restart that does not cut again: the bit was pulled out with the motor off
        p = A.load_params()
        doc = copy.deepcopy(p.doc)
        doc["restart"]["score"] = 1e9
        det = A.detect(rec, A.Params(doc, p.sha256))
        evs = [e for e in det.events["events"] if e["type"] != "kickback"]
        self.assertEqual([e["type"] for e in evs], ["motor_on", "contact", "motor_off", "retract", "motor_on",
                                                    "motor_off"])
        self.assertEqual(evs[3]["confidence"], p["confidence"]["restart_retract"])
        self.assertEqual(evs[4]["t_us"] - evs[3]["t_us"], 10_000)
        self.assertEqual(labels.check_grammar(det.events), [])
        self.assertEqual(det.events["holes"][0]["through"], False)


@unittest.skipUnless("fx_drill_through" in FX, "fixtures/autodetect is missing")
class CliTest(F.TempDirTest):
    def setUp(self):
        super().setUp()
        src = FX["fx_drill_through"]
        rid = json.loads((src / "meta.json").read_text())["recording_id"]
        self.rec = recording_dir(self.tmp, rid)
        self.rec.mkdir(parents=True)
        for p in src.iterdir():
            if p.name not in ("expected.json", "features.csv.gz", "spec.json"):
                shutil.copy(p, self.rec / p.name)

    def run_cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = A.main([str(a) for a in argv])
        return code, out.getvalue()

    def test_print_score_and_write(self):
        self.assertTrue(V.validate_recording(self.rec).ok)
        code, out = self.run_cli(self.rec, "--score")
        self.assertEqual(code, 0, out)
        self.assertIn("breakthrough hole 1", out)
        self.assertIn("score breakthrough: F1 1.00", out)
        # synth's labels are the reviewed truth: --write refuses them without --force
        code, out = self.run_cli(self.rec, "--write")
        self.assertEqual(code, 1)
        self.assertIn("not written", out)
        self.assertIn("reviewed", json.loads((self.rec / "meta.json").read_text())["review"]["status"])
        code, out = self.run_cli(self.tmp, "--write", "--force")
        self.assertEqual(code, 0, out)
        self.assertIn("validate: ok", out)
        ev = json.loads((self.rec / "events.json").read_text())
        meta = json.loads((self.rec / "meta.json").read_text())
        self.assertEqual({e["source"] for e in ev["events"]}, {"auto"})
        self.assertEqual((meta["review"]["status"], ev["revision"]), ("auto_only", 2))
        # auto_only labels may be redone without --force
        self.assertEqual(self.run_cli(self.rec, "--write")[0], 0)
        self.assertEqual(json.loads((self.rec / "events.json").read_text())["revision"], 3)
        rep = V.validate_recording(self.rec)
        self.assertTrue(rep.ok)
        self.assertEqual(rep.issues, [], rep.codes())

    def test_edited_labels_need_force(self):
        self.run_cli(self.rec, "--write", "--force")
        ev = json.loads((self.rec / "events.json").read_text())
        ev["events"][1]["edited"] = True
        ev["events"][1]["auto_t_us"] = ev["events"][1]["t_us"]
        (self.rec / "events.json").write_text(json.dumps(ev))
        self.assertEqual(self.run_cli(self.rec, "--write")[0], 1)
        self.assertEqual(self.run_cli(self.rec, "--write", "--force")[0], 0)

    def test_usage_and_fixtures(self):
        self.assertEqual(self.run_cli()[0], 2)
        self.assertEqual(self.run_cli(self.tmp / "nothing")[0], 2)
        code, out = self.run_cli("--check-fixtures")
        self.assertEqual(code, 0, out)
        self.assertEqual(self.run_cli("--check-fixtures", self.tmp / "nothing")[0], 2)
        # --update-fixtures rewrites a copy's expected output without the simulator
        fx = self.tmp / "fx" / "fx_drill_through"
        shutil.copytree(FX["fx_drill_through"], fx)
        (fx / "features.csv.gz").unlink()
        self.assertEqual(self.run_cli("--check-fixtures", fx.parent)[0], 1)
        self.assertEqual(self.run_cli("--update-fixtures", fx.parent)[0], 0)
        self.assertEqual(self.run_cli("--check-fixtures", fx.parent)[0], 0)


if __name__ == "__main__":
    unittest.main()
