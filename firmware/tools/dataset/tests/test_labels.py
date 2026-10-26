"""The phase state machine, guards and boundary policies, overlays, coarse mapping, derived labels."""

import unittest

import numpy as np
import pandas as pd

import fixtures as F
from dbdata import labels as L
from dbdata.contract import Taxonomy, load_json, load_taxonomy

TAX = load_taxonomy()


def doc(events, coverage=(0.0, 10.0), spans=()):
    return F.events_doc(events, [F.us(coverage[0]), F.us(coverage[1])], spans=spans)


def phases_at(d, seconds, **kw):
    t = np.array([F.us(s) for s in seconds])
    return list(L.phase_track(t, d, **kw).ids())


class StateMachineTest(unittest.TestCase):
    def test_one_through_hole(self):
        d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 4.0, 1),
                 ("motor_off", 6.0, None)])
        got = phases_at(d, [0.5, 1.0, 1.29, 1.3, 1.9, 2.0, 3.9, 3.949, 3.95, 4.0, 4.249, 4.25, 5.9,
                            6.0, 6.599, 6.6, 9.9])
        self.assertEqual(got, ["off", "spinup", "spinup", "free_run", "free_run", "drilling", "drilling",
                               "drilling", "breakthrough", "breakthrough", "breakthrough", "free_run",
                               "free_run", "spindown", "spindown", "off", "off"])
        self.assertEqual(L.check_grammar(d), [])

    def test_windows_are_truncated_by_the_next_event(self):
        # contact 100 ms after motor_on cuts spinup; motor_on 200 ms after motor_off cuts spindown;
        # motor_off 100 ms after breakthrough cuts the breakthrough window
        d = doc([("motor_on", 1.0, None), ("contact", 1.1, 1), ("breakthrough", 2.0, 1),
                 ("motor_off", 2.1, None), ("motor_on", 2.3, None), ("motor_off", 3.0, None)])
        got = phases_at(d, [1.05, 1.1, 2.05, 2.1, 2.25, 2.3, 2.7])
        self.assertEqual(got, ["spinup", "drilling", "breakthrough", "spindown", "spindown", "spinup",
                               "free_run"])
        # segments never overlap: the durations add up to the coverage
        dur = L.phase_durations_s(d)
        self.assertAlmostEqual(sum(dur.values()), 10.0, places=6)
        self.assertAlmostEqual(dur["spindown"], 0.2 + 0.6, places=6)

    def test_multiple_holes_and_retract(self):
        d = doc([("motor_on", 1.0, None),
                 ("contact", 1.5, 1), ("breakthrough", 2.5, 1),
                 ("contact", 3.5, 2), ("retract", 4.0, 2),
                 ("contact", 4.5, 2), ("retract", 5.0, 2),   # pecking: the same blind hole again
                 ("motor_off", 6.0, None),
                 ("motor_on", 7.0, None), ("contact", 7.5, 3), ("breakthrough", 8.5, 3),
                 ("motor_off", 9.0, None)])
        self.assertEqual(L.check_grammar(d), [])
        t = np.array([F.us(s) for s in (1.6, 2.5, 3.0, 3.6, 4.1, 4.6, 5.1, 7.6, 8.6)])
        tr = L.phase_track(t, d)
        self.assertEqual(list(tr.ids()), ["drilling", "breakthrough", "free_run", "drilling", "free_run",
                                          "drilling", "free_run", "drilling", "breakthrough"])
        self.assertEqual(list(tr.hole), [1, 1, 0, 2, 0, 2, 0, 3, 3])
        dur = L.phase_durations_s(d)
        self.assertAlmostEqual(dur["drilling"], (0.95 + 0.5 + 0.5 + 0.95), places=6)
        self.assertAlmostEqual(sum(dur.values()), 10.0, places=6)

    def test_invalid_order_is_unknown_until_motor_off(self):
        # second contact without closing hole 1: unknown from the first contact to motor_off
        d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("contact", 3.0, 2),
                 ("breakthrough", 4.0, 2), ("motor_off", 5.0, None),
                 ("motor_on", 6.0, None), ("motor_off", 7.0, None)])
        got = phases_at(d, [1.5, 1.99, 2.0, 3.5, 4.1, 4.99, 5.0, 6.5])
        self.assertEqual(got, ["free_run", "free_run", "unknown", "unknown", "unknown", "unknown",
                               "spindown", "free_run"])
        issues = L.check_grammar(d)
        self.assertEqual(len(issues), 1)
        self.assertIn("contact", issues[0].message)

    def test_more_grammar_violations(self):
        cases = {
            "breakthrough first": [("breakthrough", 1.0, 1), ("motor_on", 2.0, None), ("motor_off", 3.0, None)],
            "double motor_on": [("motor_on", 1.0, None), ("motor_on", 2.0, None), ("motor_off", 3.0, None)],
            "hole mismatch": [("motor_on", 1.0, None), ("contact", 2.0, 1), ("retract", 2.5, 2),
                              ("motor_off", 3.0, None)],
            "breakthrough with the motor off": [("motor_on", 1.0, None), ("contact", 2.0, 1),
                                                ("motor_off", 3.0, None), ("breakthrough", 3.5, 1)],
            "contact with the motor off": [("motor_on", 1.0, None), ("motor_off", 2.0, None),
                                           ("contact", 3.0, 1), ("retract", 3.5, 1)],
        }
        for name, evs in cases.items():
            with self.subTest(name):
                self.assertTrue(L.check_grammar(doc(evs)), name)
        # a stray breakthrough at the start: unknown until the (invalid) motor_off resyncs... here
        # the motor_on is skipped in error mode, the motor_off brings it back
        d = doc(cases["breakthrough first"])
        self.assertEqual(phases_at(d, [0.5, 1.5, 2.5, 3.1, 3.7]),
                         ["unknown", "unknown", "unknown", "spindown", "off"])

    def test_motor_off_in_the_hole_and_open_ends(self):
        # stuck bit: the motor stops in hole 1, restarts in it, then the bit is pulled out
        stuck = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("motor_off", 3.0, None),
                     ("motor_on", 4.0, None), ("retract", 5.0, 1), ("motor_off", 6.0, None)])
        self.assertEqual(L.grammar_state(stuck), ([], L.OFF))
        t = np.array([F.us(s) for s in (2.5, 3.1, 3.7, 4.1, 4.5, 5.5, 6.1, 6.7)])
        tr = L.phase_track(t, stuck)
        self.assertEqual(list(tr.ids()), ["drilling", "spindown", "off", "spinup", "drilling", "free_run",
                                          "spindown", "off"])
        self.assertEqual(list(tr.hole), [1, 1, 1, 1, 1, 0, 0, 0])
        dur = L.phase_durations_s(stuck)
        self.assertAlmostEqual(dur["drilling"], 1.0 + 0.7, places=6)
        self.assertAlmostEqual(sum(dur.values()), 10.0, places=6)
        # restart in the hole and break through: drilling ends breakthrough_pre before it
        bt = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("motor_off", 3.0, None),
                  ("motor_on", 4.0, None), ("breakthrough", 5.0, 1), ("motor_off", 6.0, None)])
        self.assertEqual(L.grammar_state(bt), ([], L.OFF))
        self.assertEqual(phases_at(bt, [4.2, 4.94, 4.96, 5.1, 5.3]),
                         ["spinup", "drilling", "breakthrough", "breakthrough", "free_run"])
        # aborted hole: the bit comes out while the motor runs down; the spindown is not cut short
        ab = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("motor_off", 3.0, None),
                  ("retract", 3.2, 1)])
        self.assertEqual(L.grammar_state(ab), ([], L.OFF))
        tr = L.phase_track(np.array([F.us(s) for s in (3.1, 3.3, 3.7)]), ab)
        self.assertEqual((list(tr.ids()), list(tr.hole)), (["spindown", "spindown", "off"], [1, 0, 0]))
        # takes cut short keep their labels; the state says where they stopped
        ends = {
            "motor off, bit in the hole": ([("motor_on", 1.0, None), ("contact", 2.0, 1),
                                            ("motor_off", 3.0, None)], L.HOLE_OFF, "off"),
            "motor on": ([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 3.0, 1)],
                         L.ON, "free_run"),
            "in the hole": ([("motor_on", 1.0, None), ("contact", 2.0, 1)], L.HOLE, "drilling"),
        }
        for name, (evs, state, last) in ends.items():
            with self.subTest(name):
                d = doc(evs)
                self.assertEqual(L.grammar_state(d), ([], state))
                self.assertEqual(phases_at(d, [9.9]), [last])
                self.assertAlmostEqual(sum(L.phase_durations_s(d).values()), 10.0, places=6)

    def test_events_are_sorted_and_kickback_ignored(self):
        evs = [("motor_off", 3.0, None), ("kickback", 2.5, None), ("contact", 2.0, 1), ("motor_on", 1.0, None),
               ("retract", 2.8, 1)]
        d = doc(evs)
        self.assertEqual(L.check_grammar(d), [])
        self.assertEqual(phases_at(d, [2.5, 2.9]), ["drilling", "free_run"])

    def test_outside_coverage_is_unknown(self):
        d = doc([("motor_on", 2.0, None), ("motor_off", 4.0, None)], coverage=(1.0, 5.0))
        self.assertEqual(phases_at(d, [0.5, 1.0, 3.0, 4.9, 5.0, 6.0]),
                         ["unknown", "off", "free_run", "off", "unknown", "unknown"])

    def test_windows_come_from_the_taxonomy(self):
        tdoc = load_json("taxonomy.v1.json")
        tdoc["phase_params"]["windows_ms"]["spinup_max"] = 500
        tdoc["phase_params"]["windows_ms"]["spindown_max"] = 100
        tax = Taxonomy(tdoc)
        d = doc([("motor_on", 1.0, None), ("motor_off", 3.0, None)])
        self.assertEqual(phases_at(d, [1.4, 3.2]), ["free_run", "spindown"])
        t = np.array([F.us(1.4), F.us(3.2)])
        self.assertEqual(list(L.phase_track(t, d, tax).ids()), ["spinup", "off"])


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 4.0, 1),
                      ("motor_off", 6.0, None)])
        self.t = np.array([F.us(s) for s in (1.5, 1.98, 2.0, 2.025, 2.049, 2.05, 3.0)])

    def test_guard_mask(self):
        tr = L.phase_track(self.t, self.d)
        self.assertEqual(list(tr.guard), [False, True, True, True, True, False, False])
        self.assertIn(F.us(2.0), tr.boundaries_us)
        self.assertIn(F.us(1.3), tr.boundaries_us)  # end of spinup counts as a boundary too

    def test_policies(self):
        ign = L.phase_track(self.t, self.d, boundary_policy="ignore")
        self.assertEqual(list(ign.weight), [1, 0, 0, 0, 0, 1, 1])
        self.assertIsNone(ign.soft)
        hard = L.phase_track(self.t, self.d, boundary_policy="hard")
        self.assertEqual(list(hard.weight), [1] * 7)
        np.testing.assert_array_equal(hard.codes, ign.codes)
        soft = L.phase_track(self.t, self.d, boundary_policy="soft")
        self.assertEqual(list(soft.weight), [1] * 7)
        fr, dr = TAX.phase_code("free_run"), TAX.phase_code("drilling")
        np.testing.assert_allclose(soft.soft[:, dr], [0, 0.3, 0.5, 0.75, 0.99, 1, 1], atol=1e-6)
        np.testing.assert_allclose(soft.soft[:, fr], [1, 0.7, 0.5, 0.25, 0.01, 0, 0], atol=1e-6)
        np.testing.assert_allclose(soft.soft.sum(axis=1), 1.0, atol=1e-6)
        with self.assertRaises(ValueError):
            L.phase_track(self.t, self.d, boundary_policy="fuzzy")

    def test_guard_width_parameter(self):
        tr = L.phase_track(self.t, self.d, guard_ms=10)
        self.assertEqual(list(tr.guard), [False, False, True, False, False, False, False])


class OverlayTest(unittest.TestCase):
    def test_spans_and_ignore_weight(self):
        spans = [{"id": "s1", "type": "ignore", "reason": "speech", "t0_us": F.us(7.0), "t1_us": F.us(8.0),
                  "source": "auto", "confidence": 0.6},
                 {"id": "s2", "type": "wobble", "t0_us": F.us(2.5), "t1_us": F.us(3.5), "source": "user",
                  "confidence": 1.0},
                 {"id": "s3", "type": "stuck", "t0_us": F.us(3.0), "t1_us": F.us(3.2), "source": "user",
                  "confidence": 1.0}]
        d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 4.0, 1),
                 ("motor_off", 6.0, None)], spans=spans)
        t = np.array([F.us(s) for s in (2.4, 3.1, 7.5, 8.0)])
        for policy in L.POLICIES:
            tr = L.phase_track(t, d, boundary_policy=policy)
            self.assertEqual(list(tr.overlays["wobble"]), [False, True, False, False])
            self.assertEqual(list(tr.overlays["stuck"]), [False, True, False, False])
            self.assertEqual(list(tr.overlays["ignore/speech"]), [False, False, True, False])
            self.assertEqual(list(tr.weight), [1, 1, 0, 1], policy)
        # unknown phases weigh nothing either
        self.assertEqual(L.phase_track(np.array([F.us(11.0)]), d).weight[0], 0)


class CoarseTest(unittest.TestCase):
    def test_map_to_level(self):
        self.assertEqual(L.map_to_level("wood/softwood", 1), "wood")
        self.assertEqual(L.map_to_level(["masonry/concrete", "drywall", "unknown"], 1),
                         ["masonry", "drywall", "unknown"])
        self.assertEqual(L.map_to_level("wood/softwood", 2), "wood/softwood")

    def test_coarse_phases(self):
        self.assertEqual(L.coarse_phases(["drilling", "breakthrough", "spinup", "off"]),
                         ["cutting", "cutting", "motor_free", "off"])

    def test_taxonomy_self_check(self):
        self.assertEqual(TAX.problems(), [])
        tdoc = load_json("taxonomy.v1.json")
        m = tdoc["fields"]["material"]
        m.append({"id": "wood/osb", "added": 1, "deprecated": True, "replaced_by": "wood/nothing"})
        m.append({"id": "wood/old", "added": 1, "deprecated": True, "replaced_by": "wood/osb"})
        m.append({"id": "wood/new", "added": 2})
        m.append({"id": "wood/new", "added": 1})
        probs = " | ".join(Taxonomy(tdoc).problems())
        for needle in ("wood/osb is deprecated without a valid replaced_by", "wood/old is replaced by wood/osb",
                       "wood/new added in 2", "wood/new appears twice"):
            self.assertIn(needle, probs)

    def test_deprecated_ids_resolve(self):
        tdoc = load_json("taxonomy.v1.json")
        tdoc["fields"]["material"].append({"id": "wood/osb", "added": 2, "deprecated": True,
                                           "replaced_by": "wood/plywood"})
        tax = Taxonomy(tdoc)
        self.assertEqual(tax.resolve("material", "wood/osb"), "wood/plywood")
        self.assertEqual(tax.resolve("material", "wood/softwood"), "wood/softwood")
        self.assertIsNotNone(tax.lookup("material", "unknown"))
        self.assertIsNone(tax.lookup("material", "cheese"))


class DerivedTest(unittest.TestCase):
    def level(self, elev_deg, dev_deg=None):
        t = F.T0 + np.arange(100) * 100_000
        e = np.full(100, int(elev_deg * 100))
        dv = e if dev_deg is None else np.full(100, int(dev_deg * 100))
        return pd.DataFrame({"t_us": t, "mode": 0, "flags": 0x40, "elevation_cdeg": e, "yaw_cdeg": 0,
                             "bubble_x_cdeg": 0, "bubble_y_cdeg": 0, "deviation_cdeg": dv,
                             "zero_elevation_cdeg": 0, "zero_bubble_x_cdeg": 0, "zero_bubble_y_cdeg": 0})

    def test_orientation_and_off_angle(self):
        d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 4.0, 1),
                 ("motor_off", 6.0, None)])
        meta = {"level": {"mode": "wall", "target_deg": 0, "tol_deg": 0.5}}
        for elev, orient in ((0.4, "wall"), (-29.0, "wall"), (-45.0, "angled"), (-88.0, "floor"),
                             (75.0, "ceiling")):
            derived, holes = L.compute_derived(self.level(elev), d, meta)
            self.assertEqual(derived["orientation"], orient, elev)
            self.assertEqual(holes[1]["orientation"], orient)
        derived, _ = L.compute_derived(self.level(0.4), d, meta)
        self.assertEqual(derived["tags"], [])
        derived, _ = L.compute_derived(self.level(-1.2), d, meta)
        self.assertEqual((derived["off_angle_deg"], derived["tags"]), (1.2, ["off_angle"]))
        # zero offset: the device reported elevation relative to a zero at -90
        lvl = self.level(1.0, 0.3)
        lvl["flags"] = 0x40 | 0x10
        lvl["zero_elevation_cdeg"] = -9000
        meta_z = {"level": {"mode": "floor", "target_deg": 0, "tol_deg": 0.5}}
        derived, _ = L.compute_derived(lvl, d, meta_z)
        self.assertEqual((derived["orientation"], derived["off_angle_deg"]), ("floor", 0.3))

    def test_zero_set_and_cleared_during_the_take(self):
        # a wall hole, then a zero at -45 for an angled hole, cleared again for a second wall hole:
        # each row is relative to the zero in effect on it (none outside the middle hole)
        d = doc([("motor_on", 0.5, None), ("contact", 1.0, 1), ("retract", 2.5, 1),
                 ("contact", 3.5, 2), ("retract", 6.0, 2), ("contact", 7.0, 3), ("retract", 9.5, 3),
                 ("motor_off", 9.8, None)])
        lvl = self.level(0.2)
        mid = (lvl["t_us"] >= F.us(3.0)) & (lvl["t_us"] < F.us(6.5))
        lvl.loc[mid, "flags"] = 0x40 | 0x10
        lvl.loc[mid, "zero_elevation_cdeg"] = -4500
        meta = {"level": {"mode": "wall", "target_deg": 0, "tol_deg": 0.5}}
        _, holes = L.compute_derived(lvl, d, meta)
        self.assertEqual([holes[h]["orientation"] for h in (1, 2, 3)], ["wall", "angled", "wall"])
        np.testing.assert_allclose(L.absolute_elevation_deg(lvl)[mid.to_numpy()], -44.8)
        # a stale zero value on rows without ZERO_SET is not applied
        lvl.loc[~mid, "zero_elevation_cdeg"] = -4500
        _, holes = L.compute_derived(lvl, d, meta)
        self.assertEqual([holes[h]["orientation"] for h in (1, 2, 3)], ["wall", "angled", "wall"])

    def test_rows_without_imu_ok_do_not_count(self):
        d = doc([("motor_on", 1.0, None), ("contact", 2.0, 1), ("breakthrough", 4.0, 1),
                 ("motor_off", 6.0, None)])
        lvl = self.level(0.4)
        drilling = (lvl["t_us"] >= F.us(2.0)) & (lvl["t_us"] < F.us(3.95))
        stale = drilling & (np.arange(len(lvl)) % 3 != 0)   # two thirds of the drilling rows
        lvl.loc[stale, ["elevation_cdeg", "deviation_cdeg"]] = -8800
        lvl.loc[stale, "flags"] = 0x10                     # ZERO_SET only: no real angle
        derived, holes = L.compute_derived(lvl, d, {"level": {"mode": "wall", "target_deg": 0, "tol_deg": 0.5}})
        self.assertEqual((derived["orientation"], derived["off_angle_deg"], derived["tags"]), ("wall", 0.4, []))
        lvl["flags"] = 0
        derived, _ = L.compute_derived(lvl, d, {"level": {"tol_deg": 0.5}})
        self.assertEqual(derived["orientation"], "unknown")

    def test_no_drilling(self):
        d = doc([("motor_on", 1.0, None), ("motor_off", 6.0, None)])
        derived, holes = L.compute_derived(self.level(0.0), d, {"level": {"tol_deg": 0.5}})
        self.assertEqual(derived, {"orientation": "unknown", "off_angle_deg": None, "tags": []})
        self.assertEqual(holes, {})


class LabelFixturesTest(unittest.TestCase):
    """fixtures/labels (the app's Phases/Derived cross-check) is what dbdata.labels computes today."""

    def test_fixtures_are_current(self):
        import importlib.util
        from pathlib import Path

        path = Path(__file__).resolve().parents[1] / "fixtures" / "labels" / "make_fixtures.py"
        spec = importlib.util.spec_from_file_location("make_label_fixtures", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        self.assertEqual(mod.check_all(), [], "regenerate: .venv/bin/python fixtures/labels/make_fixtures.py")


if __name__ == "__main__":
    unittest.main()
