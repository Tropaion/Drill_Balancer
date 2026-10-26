"""The validator: a valid recording passes cleanly, each corruption fails with its code."""

import ast
import contextlib
import copy
import io
import json
import shutil
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
import pandas as pd

import fixtures as F
from dbdata import contract
from dbdata import io as dio
from dbdata import validate as V

BAD_DATE = "20261399T143012Z_k7q2_0bad"   # matches the id pattern, but month 13 does not exist


class ValidateTest(F.TempDirTest):
    def write(self, parts, name=F.RID, keep_id=False):
        if not keep_id:
            parts["meta"]["recording_id"] = parts["events"]["recording_id"] = name
        return F.write(self.tmp / "recordings" / "2026" / "2026-10" / name, parts)

    def codes(self, path, **kw):
        return V.validate_recording(path, **kw).codes()

    def assertFails(self, path, code, **kw):
        rep = V.validate_recording(path, **kw)
        self.assertFalse(rep.ok, f"expected {code}, got {rep.codes()}")
        self.assertIn(code, rep.codes())
        return rep

    # -- the good case ---------------------------------------------------------------

    def test_valid_recording_passes_cleanly(self):
        p = self.write(F.build())
        rep = V.validate_recording(p)
        self.assertTrue(rep.ok)
        self.assertEqual(rep.issues, [], [(i.code, i.message) for i in rep.issues])
        self.assertEqual(rep.recording_id, F.RID)
        self.assertEqual(self.codes(self.write(F.build(audio=False), F.RID.replace("9f3a", "0000"))), set())

    # -- the corruptions the contract names -------------------------------------------

    def test_bad_sha(self):
        p = self.write(F.build())
        b = bytearray((p / "audio_sync.csv").read_bytes())
        i = b.index(b"\n") + 1  # a digit in the first data row, same size
        b[i] = ord("1") if b[i] != ord("1") else ord("2")
        (p / "audio_sync.csv").write_bytes(bytes(b))
        rep = self.assertFails(p, "E_SHA")
        self.assertNotIn("E_BYTES", rep.codes())
        (p / "level.csv.gz").write_bytes(b"x")
        self.assertTrue({"E_SHA", "E_BYTES"} <= self.codes(p))

    def test_non_monotonic_t_us(self):
        parts = F.build()
        t = parts["imu"]["t_us"].to_numpy().copy()
        t[100], t[101] = t[101], t[100]
        parts["imu"]["t_us"] = t
        rep = self.assertFails(self.write(parts), "E_T_ORDER")
        self.assertNotIn("E_SHA", rep.codes())

    def test_non_monotonic_seq(self):
        parts = F.build()
        parts["imu"].loc[50, "seq"] = parts["imu"].loc[49, "seq"]
        self.assertFails(self.write(parts), "E_SEQ_ORDER")

    def test_odr_off(self):
        parts = F.build(odr_true=470.0)  # 4.8 % above the nominal 448.4
        self.assertFails(self.write(parts), "E_ODR")
        ok = self.write(F.build(odr_true=445.0), F.RID.replace("9f3a", "0001"))  # 0.8 %: fine
        self.assertNotIn("E_ODR", self.codes(ok))

    def test_grammar_violation(self):
        evs = [("motor_on", 1.0, None), ("contact", 1.6, 1), ("contact", 2.0, 2), ("breakthrough", 3.0, 2),
               ("motor_off", 3.6, None)]
        rep = self.assertFails(self.write(F.build(events=evs)), "E_GRAMMAR")
        self.assertFalse({"E_SCHEMA_EVENTS", "E_DERIVED"} & rep.codes())
        evs = [("motor_on", 1.0, None), ("motor_off", 2.0, None), ("retract", 3.0, 1)]  # no hole open
        self.assertFails(self.write(F.build(events=evs), F.RID.replace("9f3a", "0001")), "E_GRAMMAR")

    def test_stuck_bit_aborted_hole_and_open_ends_are_valid(self):
        cases = {
            "stuck bit": [("motor_on", 1.0, None), ("contact", 1.6, 1), ("motor_off", 2.6, None),
                          ("motor_on", 3.4, None), ("retract", 4.4, 1), ("motor_off", 5.0, None)],
            "aborted hole": [("motor_on", 1.0, None), ("contact", 1.6, 1), ("motor_off", 3.0, None),
                             ("retract", 3.3, 1)],
            "ends with the motor on": [("motor_on", 1.0, None), ("contact", 1.6, 1), ("breakthrough", 3.0, 1)],
            "ends in the hole": [("motor_on", 1.0, None), ("contact", 1.6, 1)],
            "ends with the motor off in the hole": [("motor_on", 1.0, None), ("contact", 1.6, 1),
                                                    ("motor_off", 3.0, None)],
        }
        spans = [{"id": "s1", "type": "stuck", "t0_us": F.us(2.2), "t1_us": F.us(2.6), "source": "auto",
                  "confidence": 0.8}]
        for n, (name, evs) in enumerate(cases.items()):
            with self.subTest(name):
                p = self.write(F.build(events=evs, spans=spans), F.RID.replace("9f3a", f"{n + 10:04d}"))
                rep = V.validate_recording(p)
                self.assertTrue(rep.ok, rep.codes())
                self.assertNotIn("E_GRAMMAR", rep.codes())
                self.assertEqual("W_OPEN_END" in rep.codes(), name.startswith("ends"))
                self.assertEqual(dio.load_json(p / "meta.json")["derived"]["orientation"], "wall")

    def test_forbidden_key_and_pii(self):
        parts = F.build()
        parts["meta"]["ext"] = {"where": {"Location": "workshop"}}
        self.assertFails(self.write(parts), "E_FORBIDDEN_KEY")
        parts = F.build()
        parts["meta"]["session"]["notes"] = "ask me at someone@example.com"
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0001")), "E_PII")
        parts = F.build()
        parts["meta"]["session"]["notes"] = "board 24:0a:c4:12:34:56"
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0002")), "E_PII")
        p = self.write(F.build(), F.RID.replace("9f3a", "0003"))
        F.edit_json(p / "events.json", lambda d: d["events"][0].update(email="x"))
        self.assertTrue({"E_FORBIDDEN_KEY", "E_SCHEMA_EVENTS"} <= self.codes(p))

    def test_oversized_file(self):
        p = self.write(F.build())
        with mock.patch.object(V, "MAX_FILE_BYTES", 100_000):  # audio.flac is ~190 kB
            rep = self.assertFails(p, "E_FILE_SIZE")
        self.assertEqual(sum(i.code == "E_FILE_SIZE" for i in rep.issues), 1)
        self.assertIn("E_FILE_SIZE", self.codes(p, max_file_bytes=1000))
        self.assertTrue(V.validate_recording(p).ok)

    def test_derived_mismatch(self):
        p = self.write(F.build())
        F.edit_json(p / "meta.json", lambda m: m["derived"].update(orientation="ceiling"))
        self.assertFails(p, "E_DERIVED")
        p = self.write(F.build(), F.RID.replace("9f3a", "0001"))
        F.edit_json(p / "meta.json", lambda m: m["derived"].update(off_angle_deg=2.0))
        self.assertFails(p, "E_DERIVED")
        p = self.write(F.build(), F.RID.replace("9f3a", "0002"))
        F.edit_json(p / "events.json", lambda e: e["holes"][0].update(off_angle_deg=3.0))
        self.assertFails(p, "E_DERIVED")
        # a user-set hole angle is not recomputed
        F.edit_json(p / "events.json", lambda e: e["holes"][0].update(off_angle_source="user"))
        self.assertTrue(V.validate_recording(p).ok)
        # the off_angle tag follows the device tolerance
        p = self.write(F.build(elevation_deg=-1.5), F.RID.replace("9f3a", "0003"))
        self.assertEqual(dio.load_json(p / "meta.json")["derived"]["tags"], ["off_angle"])
        F.edit_json(p / "meta.json", lambda m: m["derived"].update(tags=[]))
        self.assertFails(p, "E_DERIVED")

    def test_events_outside_coverage(self):
        parts = F.build()
        parts["events"]["coverage_us"][1] = F.us(5.0)  # motor_off at 6.0 s is now outside
        rep = self.assertFails(self.write(parts), "E_EVENT_RANGE")
        self.assertIn("e8", " ".join(i.message for i in rep.issues))
        spans = [{"id": "s1", "type": "wobble", "t0_us": F.T0 - 10, "t1_us": F.us(1.0), "source": "auto",
                  "confidence": 0.5}]
        self.assertFails(self.write(F.build(spans=spans), F.RID.replace("9f3a", "0001")), "E_EVENT_RANGE")

    # -- further checks ----------------------------------------------------------------

    def test_audio_duration_and_sync(self):
        parts = F.build()
        parts["audio"] = parts["audio"][: len(parts["audio"]) // 2]
        self.assertFails(self.write(parts), "E_DURATION")
        parts = F.build()
        sync = parts["audio_sync"]
        sync["t_us"] = sync["t_us"] + np.random.default_rng(2).normal(0, 3000, len(sync)).astype(np.int64)
        sync["t_us"] = np.maximum.accumulate(sync["t_us"].to_numpy() + np.arange(len(sync)) * 20000)
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0001")), "E_SYNC")
        parts = F.build()
        parts["meta"]["audio"]["rate_nominal_hz"] = 16000
        parts["meta"]["audio"]["channels"] = ["mic1"]
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0002")), "E_AUDIO_FORMAT")

    def test_gap_thresholds_are_warnings(self):
        rep = V.validate_recording(self.write(F.build(filled=[(0.0, 7.0, 0.2)])))
        self.assertTrue(rep.ok)
        self.assertIn("W_FILLED", rep.codes())
        rep = V.validate_recording(self.write(F.build(gap=(2.0, 2.2)), F.RID.replace("9f3a", "0001")))
        self.assertTrue(rep.ok)
        self.assertIn("W_MISSING", rep.codes())
        parts = F.build()
        parts["meta"]["gaps"]["imu_filled_samples"] = 7
        rep = V.validate_recording(self.write(parts, F.RID.replace("9f3a", "0002")))
        self.assertEqual(rep.codes(), {"W_GAPS_META"})

    def test_taxonomy_and_schema(self):
        cases = {
            "E_TAXONOMY": lambda m: m["session"].update(material="cheese"),
            "E_OTHER_TEXT": lambda m: m["session"].update(material="other"),
            "E_SCHEMA_META": lambda m: m["session"].update(notes="x" * 201),
        }
        for n, (code, fn) in enumerate(cases.items()):
            with self.subTest(code):
                p = self.write(F.build(), F.RID.replace("9f3a", f"{n:04d}"))
                F.edit_json(p / "meta.json", fn)
                self.assertFails(p, code)
        p = self.write(F.build(), F.RID.replace("9f3a", "0009"))
        F.edit_json(p / "meta.json", lambda m: m["session"].update(material="other",
                                                                     other_text={"material": "cork"}))
        self.assertTrue(V.validate_recording(p).ok)
        p = self.write(F.build(), F.RID.replace("9f3a", "0010"))
        F.edit_json(p / "events.json", lambda e: e["events"].append(
            {"id": "e99", "type": "sneeze", "t_us": F.us(2.0), "source": "user", "confidence": 1.0}))
        self.assertFails(p, "E_TAXONOMY")

    def test_ids_and_files(self):
        p = self.write(F.build(), "20261026T143012Z_k7q2_zzzz", keep_id=True)
        self.assertFails(p, "E_ID")
        # the right id in the wrong month folder
        p = F.write(self.tmp / "recordings" / "2026" / "2026-11" / F.RID, F.build())
        rep = self.assertFails(p, "E_ID")
        self.assertIn("does not match 2026/2026-10", " ".join(i.message for i in rep.issues))
        p = self.write(F.build(), F.RID.replace("9f3a", "0001"))
        (p / "level.csv.gz").unlink()
        self.assertFails(p, "E_MISSING_FILE")
        (p / "stray.txt").write_text("x")
        self.assertIn("W_EXTRA_FILE", self.codes(p))
        (p / "meta.json").write_text("{not json")
        self.assertFails(p, "E_JSON")

    def test_impossible_date_in_the_id(self):
        p = self.write(F.build(rid=BAD_DATE, start_utc="2026-10-26T14:30:12.418Z"), BAD_DATE)
        for light in (True, False):
            rep = self.assertFails(p, "E_ID", light=light)
            self.assertNotIn("E_INTERNAL", rep.codes())
        good = self.write(F.build())
        code, out = self.run_cli(str(self.tmp / "recordings"))
        self.assertEqual(code, 1)
        self.assertIn(f"{good.name}: ok", out)

    def test_internal_failure_is_reported_not_raised(self):
        p = self.write(F.build())
        with mock.patch.object(V, "_check_imu", side_effect=RuntimeError("boom")):
            rep = self.assertFails(p, "E_INTERNAL")
        self.assertIn("boom", rep.issues[-1].message)

    def test_taxonomy_versions_and_deprecation(self):
        p = self.write(F.build())
        F.edit_json(p / "events.json", lambda e: e.update(taxonomy_version=2))
        self.assertFails(p, "E_TAXONOMY_VERSION")
        tdoc = contract.load_json(contract.TAXONOMY_FILE)
        tdoc["version"] = 2
        tdoc["fields"]["material"].append({"id": "wood/osb", "added": 1, "deprecated": True,
                                           "replaced_by": "wood/plywood"})
        tdoc["fields"]["mode"].append({"id": "chisel", "added": 2})
        tax = contract.Taxonomy(tdoc)
        p = self.write(F.build(material="wood/osb"), F.RID.replace("9f3a", "0001"))
        rep = V.validate_recording(p, taxonomy=tax)
        self.assertTrue(rep.ok, rep.codes())
        self.assertEqual(rep.codes(), {"W_DEPRECATED"})
        self.assertIn("E_TAXONOMY", self.codes(p))   # unknown to the packaged taxonomy
        # an id newer than the taxonomy version the document claims
        p = self.write(F.build(mode="chisel"), F.RID.replace("9f3a", "0002"))
        self.assertEqual(V.validate_recording(p, taxonomy=tax).codes(), {"W_TAXONOMY_ADDED"})

    def test_event_ids_and_kickback(self):
        p = self.write(F.build())
        F.edit_json(p / "events.json", lambda e: e["events"][1].update(id="e1"))
        self.assertFails(p, "E_EVENT_ID")
        kick = {"id": "k1", "type": "kickback", "t_us": F.us(0.5), "source": "auto", "confidence": 0.7}
        p = self.write(F.build(), F.RID.replace("9f3a", "0001"))
        F.edit_json(p / "events.json", lambda e: e["events"].append(dict(kick)))
        self.assertEqual(self.codes(p), {"W_KICKBACK_OFF", "W_EVENTS_UNSORTED"})
        F.edit_json(p / "events.json", lambda e: e["events"][-1].update(t_us=F.us(2.0)))
        self.assertEqual(self.codes(p), {"W_EVENTS_UNSORTED"})

    def test_sync_meta_and_level_format(self):
        p = self.write(F.build())
        F.edit_json(p / "meta.json", lambda m: m["sync"].update(a_us=m["sync"]["a_us"] + 5000))
        self.assertEqual(self.codes(p), {"W_SYNC_META"})
        parts = F.build()
        lt = parts["level"]["t_us"].to_numpy().copy()
        lt[10], lt[11] = lt[11], lt[10]
        parts["level"]["t_us"] = lt
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0001")), "E_LEVEL_FORMAT")

    def test_level_zero(self):
        # bit at -45 deg, zeroed there at 0.9 s: the rows after it report about 0 relative to the zero
        parts = F.build(elevation_deg=-45.0)
        lvl = parts["level"]
        later = lvl["t_us"] >= F.us(0.9)
        lvl.loc[later, "flags"] = 0x40 | 0x10
        lvl.loc[later, "elevation_cdeg"] += 4500
        lvl.loc[later, "zero_elevation_cdeg"] = -4500
        p = self.write(parts)
        self.assertEqual(self.codes(p), set())
        self.assertEqual(dio.load_json(p / "meta.json")["derived"]["orientation"], "angled")
        # a zero value on a row without ZERO_SET
        parts = F.build()
        parts["level"].loc[5, "zero_bubble_y_cdeg"] = 120
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0001")), "E_LEVEL_ZERO")
        # the zero lives in level.csv.gz only; meta.level has no zero any more
        parts = F.build()
        parts["meta"]["level"]["zero_elevation_deg"] = 2.0
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0002")), "E_SCHEMA_META")
        # level.csv.gz without the zero columns
        parts = F.build()
        p = self.write(parts, F.RID.replace("9f3a", "0003"))
        lvl = dio.read_level_csv(p / "level.csv.gz").drop(columns=list(dio.LEVEL_ZERO_COLUMNS))
        dio._write_csv_gz(lvl, p / "level.csv.gz")
        F.edit_json(p / "meta.json", lambda m: m["files"]["level.csv.gz"].update(
            sha256=dio.sha256_file(p / "level.csv.gz"), bytes=(p / "level.csv.gz").stat().st_size))
        self.assertFails(p, "E_LEVEL_FORMAT")

    def test_gap_flag_must_match_seq_jumps(self):
        parts = F.build()
        imu = parts["imu"]
        t = imu["t_us"]
        parts["imu"] = imu[(t < F.us(3.0)) | (t >= F.us(3.034))].reset_index(drop=True)  # ~15 samples, no flag
        parts["meta"]["gaps"]["imu_missing_samples"] = len(imu) - len(parts["imu"])
        rep = V.validate_recording(self.write(parts))
        self.assertTrue(rep.ok)
        self.assertEqual(rep.codes(), {"W_GAP_FLAG"})
        self.assertEqual(self.codes(self.write(F.build(gap=(3.0, 3.034)), F.RID.replace("9f3a", "0001"))), set())

    def test_audio_gap_frames_match_the_count(self):
        parts = F.build()
        parts["meta"]["gaps"]["audio_missing_frames"] = 256
        self.assertFails(self.write(parts), "E_AUDIO_GAPS")
        parts["meta"]["gaps"]["audio_gap_frames"] = [[48000, 48256]]
        self.assertTrue(V.validate_recording(self.write(parts, F.RID.replace("9f3a", "0001"))).ok)
        parts["meta"]["gaps"]["audio_gap_frames"] = [[48256, 48000]]
        self.assertFails(self.write(parts, F.RID.replace("9f3a", "0002")), "E_AUDIO_GAPS", light=True)

    def test_device_events_take_and_registers(self):
        parts = F.build()
        parts["device_events"] = pd.DataFrame({
            "t_us": [F.us(0.5), F.us(1.0), F.us(1.0), F.us(6.2)],
            "code": ["PROFILE", "VIB_START", "TOUCH", "VIB_STOP"], "arg": [1, 0, 0, 0],
            "text": ["RAW448", "", "", ""]})
        parts["meta"]["take"] = {"take_id": 7, "end_reason": "max_s",
                                 "stats": {"ring_high_water_pct": 3.5, "dropped_records": 0, "audio_overflows": 0}}
        parts["meta"]["imu"]["registers"] = {"ctrl2": 0x37, "ctrl3": 0x67, "ctrl5": 0x00}
        parts["events"]["events"][0]["confirmed"] = True
        p = self.write(parts)
        self.assertEqual(self.codes(p), set())
        self.assertIn("device_events.csv", dio.load_json(p / "meta.json")["files"])
        rec = dio.read_recording(p)
        self.assertEqual(list(rec.device_events["code"]), ["PROFILE", "VIB_START", "TOUCH", "VIB_STOP"])
        bad = copy.deepcopy(parts)
        bad["device_events"].loc[2, "code"] = "SNEEZE"
        self.assertFails(self.write(bad, F.RID.replace("9f3a", "0001")), "E_DEVICE_EVENTS")
        bad = copy.deepcopy(parts)
        bad["device_events"].loc[0, "text"] = "me@example.com"
        self.assertFails(self.write(bad, F.RID.replace("9f3a", "0002")), "E_PII_DATA")
        bad = copy.deepcopy(parts)
        bad["meta"]["take"]["end_reason"] = "bored"
        self.assertFails(self.write(bad, F.RID.replace("9f3a", "0003")), "E_SCHEMA_META")

    def test_unlisted_device_events_are_scanned(self):
        p = self.write(F.build())
        pd.DataFrame({"t_us": [F.us(1.0)], "code": ["MARK"], "arg": [0], "text": ["call me@example.com"]}).to_csv(
            p / "device_events.csv", index=False)
        rep = self.assertFails(p, "E_PII_DATA")
        self.assertIn("W_EXTRA_FILE", rep.codes())
        # listed, but not the listed bytes: still scanned
        parts = F.build()
        parts["device_events"] = pd.DataFrame({"t_us": [F.us(1.0)], "code": ["MARK"], "arg": [0], "text": [""]})
        p = self.write(parts, F.RID.replace("9f3a", "0001"))
        (p / "device_events.csv").write_text("t_us,code,arg,text\n1,MARK,0,ab:cd:ef:01:23:45\n")
        self.assertTrue({"E_PII_DATA", "E_SHA"} <= self.codes(p))

    def test_misnamed_folders_are_found(self):
        p = F.write(self.tmp / "recordings" / "2026" / "2026-10" / "copy of take", F.build())
        self.assertEqual(V.find_recordings(self.tmp / "recordings"), [p])
        self.assertFails(p, "E_ID")

    def test_non_string_recording_id_in_the_report(self):
        p = self.write(F.build())
        F.edit_json(p / "meta.json", lambda m: m.update(recording_id=["a"]))
        rep = self.assertFails(p, "E_SCHEMA_META")
        self.assertEqual((rep.recording_id, rep.key), (None, p.name))
        report = self.tmp / "report.json"
        code, _ = self.run_cli("--report", str(report), str(p))
        self.assertEqual(code, 1)
        self.assertEqual(list(json.loads(report.read_text())), [p.name])

    def test_a_path_without_recordings_does_not_stop_the_others(self):
        good = self.write(F.build())
        (self.tmp / "empty").mkdir()
        report = self.tmp / "report.json"
        code, out = self.run_cli("--report", str(report), str(self.tmp / "empty"), str(good))
        self.assertEqual(code, 2)
        self.assertIn("no recordings found", out)
        self.assertIn(f"{F.RID}: ok", out)
        rep = json.loads(report.read_text())
        self.assertEqual(list(rep), [F.RID])
        self.assertTrue(rep[F.RID]["ok"])
        self.assertEqual(rep[F.RID]["fingerprint"], V.fingerprint(good))

    def test_data_codes_are_what_the_data_checks_emit(self):
        # index.build_rows takes and carries over exactly DATA_CODES: a data check's code missing
        # there would be dropped, and a recording failing only that check would look valid
        funcs = {f.name: f for f in ast.parse(Path(V.__file__).read_text()).body if isinstance(f, ast.FunctionDef)}

        def codes(*names):
            return {n.args[0].value for name in names for n in ast.walk(funcs[name])
                    if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == "add"
                    and n.args and isinstance(n.args[0], ast.Constant)}

        data = codes("_check_files", "_check_imu", "_check_audio", "_check_level_derived", "_check_device_events")
        # W_COVERAGE: the data check _validate makes itself; E_INTERNAL: the run failed, whatever it checked
        self.assertEqual(data | {"W_COVERAGE", "E_INTERNAL"}, V.DATA_CODES)
        light = codes("_validate", "_check_meta_events", "_check_events", "check_privacy", "_tax")
        self.assertEqual(light & V.DATA_CODES, {"W_COVERAGE"})

    def test_light_mode_skips_data(self):
        p = self.write(F.build())
        (p / "imu.csv.gz").write_bytes(b"broken")
        self.assertTrue(V.validate_recording(p, light=True).ok)
        self.assertFalse(V.validate_recording(p).ok)

    # -- CLI and contract ----------------------------------------------------------------

    def run_cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = V.main(list(argv))
        return code, out.getvalue()

    def test_cli_exit_codes_and_report(self):
        good = self.write(F.build())
        code, out = self.run_cli(str(good))
        self.assertEqual(code, 0, out)
        bad = self.write(F.build(odr_true=470.0), F.RID.replace("9f3a", "0001"))
        report = self.tmp / "report.json"
        code, out = self.run_cli("--report", str(report), str(self.tmp / "recordings"))
        self.assertEqual(code, 1)
        self.assertIn("E_ODR", out)
        rep = json.loads(report.read_text())
        self.assertEqual(sorted(rep), sorted([F.RID, bad.name]))
        self.assertTrue(rep[F.RID]["ok"])
        self.assertFalse(rep[bad.name]["ok"])
        self.assertEqual(self.run_cli()[0], 2)
        self.assertEqual(self.run_cli(str(self.tmp / "nothing"))[0], 2)
        warn = self.write(F.build(filled=[(0.0, 7.0, 0.2)]), F.RID.replace("9f3a", "0002"))
        self.assertEqual(self.run_cli(str(warn))[0], 0)
        self.assertEqual(self.run_cli("--strict", str(warn))[0], 1)

    def test_contract_copies(self):
        repo = self.tmp / "repo"
        for rel, name in contract.REPO_COPIES.items():
            (repo / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(contract.contract_path(name), repo / rel)
        self.assertEqual(V.check_contract(repo), [])
        self.assertEqual(self.run_cli("--contract", str(repo))[0], 0)
        tax = json.loads((repo / "taxonomy/taxonomy.v1.json").read_text())
        tax["fields"]["mode"].append({"id": "chisel", "added": 2})
        (repo / "taxonomy/taxonomy.v1.json").write_text(json.dumps(tax))
        (repo / "schema/events.v1.schema.json").unlink()
        codes = [i.code for i in V.check_contract(repo)]
        self.assertEqual(codes, ["E_CONTRACT", "E_CONTRACT"])
        self.assertEqual(self.run_cli("--contract", str(repo))[0], 1)


if __name__ == "__main__":
    unittest.main()
