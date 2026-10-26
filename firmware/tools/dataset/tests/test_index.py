"""index.csv + stats.md: deterministic output, validator reports, carry-over, split stability."""

import contextlib
import csv
import io
import json
import random
import os
import shutil
import string
import subprocess
import unittest
import unittest.mock

import fixtures as F
from dbdata import contract, labels
from dbdata import index as X
from dbdata import io as dio
from dbdata import validate as V

TRAIN, VAL, TEST = "20261026T1500_k7q2", "20261026T1428_k7q2", "20261026T1430_k7q2"


def populate(root, order=(0, 1, 2)):
    specs = [dict(rid=F.rid(1), session_id=TRAIN),
             dict(rid=F.rid(2), session_id=VAL, material="masonry/concrete", mode="hammer", audio=False),
             dict(rid=F.rid(3), session_id=TEST, odr_true=470.0,         # fails the full check (E_ODR)
                  motor="universal_ac")]
    return [F.make_recording(root, **specs[i]) for i in order]


class SplitTest(unittest.TestCase):
    def test_pinned_buckets(self):
        # crc32(session_id) % 10 must never change: it decides train/val/test for good
        self.assertEqual([X.split_bucket(s) for s in (TRAIN, VAL, TEST, "20261027T0900_ab3d")], [2, 8, 9, 3])
        self.assertEqual([X.split_of(s) for s in (TRAIN, VAL, TEST)], ["train", "val", "test"])

    def test_distribution(self):
        rng = random.Random(4)
        ids = ["".join(rng.choice(string.ascii_lowercase + string.digits) for _ in range(12)) for _ in range(4000)]
        counts = {k: sum(X.split_of(s) == k for s in ids) / len(ids) for k in X.SPLITS}
        self.assertAlmostEqual(counts["train"], 0.8, delta=0.03)
        self.assertAlmostEqual(counts["val"], 0.1, delta=0.02)
        self.assertAlmostEqual(counts["test"], 0.1, delta=0.02)


class IndexTest(F.TempDirTest):
    def setUp(self):
        super().setUp()
        self.root = self.tmp / "a"
        self.paths = populate(self.root)

    def text(self, root=None):
        root = root or self.root
        return (root / X.INDEX_FILE).read_text(), (root / X.STATS_FILE).read_text()

    def rows(self):
        return {r["recording_id"]: r for r in X.read_previous(self.root / X.INDEX_FILE).values()}

    def full_report(self, *paths):
        out = self.tmp / "report.json"
        with contextlib.redirect_stdout(io.StringIO()):
            V.main(["--quiet", "--report", str(out), *(map(str, paths or [self.root / "recordings"]))])
        return json.loads(out.read_text())

    def test_deterministic(self):
        self.assertTrue(X.regenerate(self.root))
        first = self.text()
        self.assertFalse(X.regenerate(self.root))
        self.assertEqual(self.text(), first)
        other = self.tmp / "b"
        populate(other, order=(2, 0, 1))  # written in another order
        X.regenerate(other)
        self.assertEqual(self.text(other), first)
        lines = first[0].splitlines()
        self.assertEqual(lines[0].split(","), list(X.COLUMNS))
        self.assertEqual([ln.split(",")[0] for ln in lines[1:]], sorted(F.rid(i) for i in (1, 2, 3)))

    def test_row_contents(self):
        X.regenerate(self.root)
        r = self.rows()[F.rid(1)]
        self.assertEqual(r["path"], dio.recording_relpath(F.rid(1)))
        self.assertEqual((r["split"], r["material"], r["mode"], r["has_audio"]), ("train", "wood/softwood", "drill", "1"))
        self.assertEqual((r["n_holes"], r["orientation"], r["review_status"]), ("2", "wall", "reviewed"))
        self.assertIn("drilling=", r["phases"])
        self.assertEqual(r["outcome_tags"], "clean")
        self.assertEqual(r["min_confidence"], "0.900")
        meta = dio.load_json(self.paths[0] / "meta.json")
        self.assertEqual(int(r["bytes"]), sum(f["bytes"] for f in meta["files"].values()))
        r2 = self.rows()[F.rid(2)]
        self.assertEqual((r2["split"], r2["has_audio"], r2["mode"]), ("val", "0", "hammer"))
        rows = self.rows()
        self.assertEqual([(rows[F.rid(i)]["drill_type"], rows[F.rid(i)]["motor"]) for i in (1, 2, 3)],
                         [("drill", "brushless"), ("combi_drill", "brushless"), ("drill", "universal_ac")])
        cols = list(X.COLUMNS)
        self.assertEqual(cols[cols.index("bit_diameter_mm") + 1:cols.index("orientation")], ["drill_type", "motor"])
        # light checks only, no report: the ODR problem is not seen yet
        self.assertEqual(self.rows()[F.rid(3)]["valid"], "1")
        stats = self.text()[1]
        self.assertIn("| material | drill | hammer |", stats)
        self.assertIn("| masonry/concrete | 0.0 | 0.1 |", stats)   # 7 s of hammer
        self.assertIn("| wood/softwood | 0.2 | 0.0 |", stats)      # 2 x 7 s of drill
        self.assertIn("| 3 | 3 | 0.01 |", stats)
        self.assertIn("## Drill type x motor (valid, minutes)\n\n"
                      "| drill type | brushless | universal_ac |\n|---|---|---|\n"
                      "| combi_drill | 0.1 | 0.0 |\n| drill | 0.1 | 0.1 |\n", stats)

    def test_report_and_carry_over(self):
        X.regenerate(self.root, self.full_report())
        rows = self.rows()
        self.assertEqual((rows[F.rid(3)]["valid"], rows[F.rid(3)]["warnings"]), ("0", "E_ODR"))
        self.assertEqual(rows[F.rid(1)]["valid"], "1")
        # the next run without a report keeps the data-check result
        self.assertFalse(X.regenerate(self.root))
        self.assertEqual(self.rows()[F.rid(3)]["valid"], "0")
        # light problems are always fresh: break and then fix the events of recording 1
        ev = self.paths[0] / "events.json"
        good = ev.read_bytes()
        doc = dio.load_json(ev)
        doc["events"] = doc["events"][1:]  # drop the first motor_on
        dio.dump_json(doc, ev)
        X.regenerate(self.root)
        self.assertEqual(self.rows()[F.rid(1)]["valid"], "0")
        self.assertIn("E_GRAMMAR", self.rows()[F.rid(1)]["warnings"])
        ev.write_bytes(good)
        X.regenerate(self.root)
        self.assertEqual(self.rows()[F.rid(1)]["valid"], "1")
        self.assertIn("Validator issues", self.text()[1])

    def test_check_and_unchecked(self):
        X.regenerate(self.root)   # light only: nothing has had a full check yet
        self.assertEqual(len(X.unchecked(self.root)), 3)
        self.assertIn("W_UNCHECKED", self.rows()[F.rid(1)]["warnings"])
        X.regenerate(self.root, self.full_report())
        self.assertEqual(X.unchecked(self.root), [])
        self.assertNotIn("W_UNCHECKED", self.rows()[F.rid(1)]["warnings"])
        new = F.make_recording(self.root, rid=F.rid(4), session_id=TRAIN)
        self.assertEqual(X.unchecked(self.root), [new.relative_to(self.root).as_posix()])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(X.main([str(self.root), "--check"]), 1)
            self.assertEqual(X.main([str(self.root)]), 0)
            self.assertEqual(X.main([str(self.root), "--check"]), 0)
            self.assertEqual(X.main([str(self.root), "--list-unchecked"]), 0)
        self.assertIn("out of date", out.getvalue())
        self.assertIn(new.name, out.getvalue())

    def test_label_edit_in_a_superseded_push_is_revalidated(self):
        # X is validated and indexed; push A edits its labels (now wrong derived values), but A's
        # run is superseded; push B adds Y. B's diff only shows Y, the index must still flag X.
        X.regenerate(self.root, self.full_report())
        x = self.paths[0]
        self.assertEqual(self.rows()[F.rid(1)]["valid"], "1")
        F.edit_json(x / "meta.json", lambda m: m["derived"].update(off_angle_deg=3.0, tags=["off_angle"]))
        y = F.make_recording(self.root, rid=F.rid(5), session_id=TRAIN)
        rel = lambda f: f.relative_to(self.root).as_posix()  # noqa: E731
        self.assertEqual(sorted(X.unchecked(self.root)), sorted([rel(x), rel(y)]))
        # without a fresh check the old data result is not carried over
        X.regenerate(self.root, self.full_report(y))
        self.assertIn("W_UNCHECKED", self.rows()[F.rid(1)]["warnings"])
        # the workflow validates diff + unchecked: X fails its data checks now
        X.regenerate(self.root, self.full_report(x, y))
        r = self.rows()[F.rid(1)]
        self.assertEqual((r["valid"], r["warnings"]), ("0", "E_DERIVED"))
        self.assertEqual(X.unchecked(self.root), [])

    def test_a_data_file_change_in_a_superseded_push_is_revalidated(self):
        # push A changes only a data file of the checked X (meta.json and events.json stay), and
        # A's run is superseded by push B's, whose diff does not show X: the index must name it
        X.regenerate(self.root, self.full_report())
        x = self.paths[0]
        self.assertEqual(X.unchecked(self.root), [])
        (x / "imu.csv.gz").write_bytes(b"broken")
        self.assertEqual(X.unchecked(self.root), [self.rel(x)])
        X.regenerate(self.root)                 # the old data result no longer applies
        r = self.rows()[F.rid(1)]
        self.assertEqual((r["checked"], "W_UNCHECKED" in r["warnings"]), ("", True))
        X.regenerate(self.root, self.full_report(x))
        r = self.rows()[F.rid(1)]
        self.assertEqual(r["valid"], "0")
        self.assertIn("E_SHA", r["warnings"])
        self.assertEqual(X.unchecked(self.root), [])

    def test_one_bad_recording_does_not_stop_the_index(self):
        bad = "20261399T143012Z_k7q2_0bad"
        p = F.write(self.root / "recordings" / "2026" / "2026-13" / bad,
                    F.build(rid=bad, start_utc="2026-10-26T14:30:12.418Z"))
        X.regenerate(self.root, self.full_report())
        rows = self.rows()
        self.assertEqual((rows[bad]["valid"], rows[bad]["path"]), ("0", p.relative_to(self.root).as_posix()))
        self.assertIn("E_ID", rows[bad]["warnings"])
        self.assertEqual(rows[F.rid(1)]["valid"], "1")
        with contextlib.redirect_stderr(io.StringIO()), \
                unittest.mock.patch.object(X, "split_of", side_effect=RuntimeError("boom")):
            X.regenerate(self.root)
        self.assertIn("E_INTERNAL", self.rows()[F.rid(1)]["warnings"])

    def test_deprecated_ids_are_resolved(self):
        tdoc = contract.load_json(contract.TAXONOMY_FILE)
        tdoc["fields"]["material"].append({"id": "wood/osb", "added": 1, "deprecated": True,
                                           "replaced_by": "wood/plywood"})
        tdoc["fields"]["drill_type"].append({"id": "hammer_drill", "added": 1, "deprecated": True,
                                             "replaced_by": "combi_drill"})
        tdoc["fields"]["motor"].append({"id": "corded", "added": 1, "deprecated": True,
                                        "replaced_by": "universal_ac"})
        tax = contract.Taxonomy(tdoc)
        F.make_recording(self.root, rid=F.rid(6), session_id=TRAIN, material="wood/osb", drill_type="hammer_drill",
                         motor="corded")
        X.regenerate(self.root, taxonomy=tax)
        r = self.rows()[F.rid(6)]
        self.assertEqual((r["material"], r["drill_type"], r["motor"], r["valid"]),
                         ("wood/plywood", "combi_drill", "universal_ac", "1"))
        self.assertIn("W_DEPRECATED", r["warnings"])

    def rel(self, folder):
        return folder.relative_to(self.root).as_posix()

    def light_report(self, *paths):
        out = self.tmp / "light.json"
        with contextlib.redirect_stdout(io.StringIO()):
            V.main(["--quiet", "--light", "--report", str(out), *map(str, paths)])
        return json.loads(out.read_text())

    def test_light_report_is_no_full_check(self):
        x = self.paths[0]
        (x / "imu.csv.gz").write_bytes(b"broken")     # only a data check sees this
        X.regenerate(self.root, self.light_report(self.root / "recordings"))
        r = self.rows()[F.rid(1)]
        self.assertEqual((r["valid"], r["checked"]), ("1", ""))
        self.assertIn("W_UNCHECKED", r["warnings"])
        self.assertIn(self.rel(x), X.unchecked(self.root))
        X.regenerate(self.root, self.full_report())
        r = self.rows()[F.rid(1)]
        self.assertEqual(r["valid"], "0")
        self.assertIn("E_SHA", r["warnings"])

    def test_report_of_other_meta_or_events_is_not_applied(self):
        # validated, then the labels change before the index is written (a push that raced the run)
        report = self.full_report()
        x = self.paths[0]
        F.edit_json(x / "events.json", lambda e: e["events"][0].update(confidence=0.5))
        X.regenerate(self.root, report)
        r = self.rows()[F.rid(1)]
        self.assertEqual(r["checked"], "")
        self.assertIn("W_UNCHECKED", r["warnings"])
        self.assertEqual(X.unchecked(self.root), [self.rel(x)])
        self.assertNotEqual(self.rows()[F.rid(2)]["checked"], "")   # untouched recordings keep theirs

    def test_data_errors_survive_an_edit_and_its_revert(self):
        x = self.paths[0]
        (x / "imu.csv.gz").write_bytes(b"broken")
        X.regenerate(self.root, self.full_report())
        self.assertEqual(self.rows()[F.rid(1)]["valid"], "0")
        meta = x / "meta.json"
        good = meta.read_bytes()
        F.edit_json(meta, lambda m: m["session"].update(notes="edited"))
        X.regenerate(self.root)          # no full check of the edited files: nothing to carry
        r = self.rows()[F.rid(1)]
        self.assertEqual((r["checked"], "W_UNCHECKED" in r["warnings"]), ("", True))
        meta.write_bytes(good)           # back to the files of the failed check
        X.regenerate(self.root)
        r = self.rows()[F.rid(1)]
        # the row no longer holds that check's result, so it must not look checked
        self.assertEqual(r["checked"], "")
        self.assertIn(self.rel(x), X.unchecked(self.root))
        X.regenerate(self.root, self.full_report(x))
        r = self.rows()[F.rid(1)]
        self.assertEqual(r["valid"], "0")
        self.assertIn("E_SHA", r["warnings"])

    def test_non_string_recording_id(self):
        for n, bad in enumerate((None, 12345, ["a", "b"], {"x": 1})):
            with self.subTest(bad=bad):
                p = self.paths[1]
                F.edit_json(p / "meta.json", lambda m: m.update(recording_id=bad))
                report = self.full_report()          # the validator report does not crash either
                self.assertIn(p.name, report)
                X.regenerate(self.root, report)
                r = self.rows()[p.name]
                self.assertEqual((r["valid"], r["path"]), ("0", self.rel(p)))
                self.assertIn("E_SCHEMA_META", r["warnings"])
                self.assertEqual(self.rows()[F.rid(1)]["valid"], "1")

    def test_misnamed_folder_is_indexed_and_listed(self):
        p = F.write(self.root / "recordings" / "2026" / "2026-10" / "my take", F.build(rid=F.rid(8)))
        X.regenerate(self.root)
        r = next(r for r in X.read_previous(self.root / X.INDEX_FILE).values() if r["path"] == self.rel(p))
        self.assertEqual(r["valid"], "0")
        self.assertIn("E_ID", r["warnings"])
        self.assertIn(self.rel(p), X.unchecked(self.root))

    def test_copy_of_a_recording_does_not_fail_the_original(self):
        # a byte-identical copy in a misnamed folder shares the recording_id, so the report holds
        # one entry for both (the copy's, sorted last); its light E_ID must not land on the original
        x = self.paths[0]
        copy = x.parent / f"{x.name} (1)"
        shutil.copytree(x, copy)
        X.regenerate(self.root, self.full_report())
        rows = {r["path"]: r for r in csv.DictReader(io.StringIO(self.text()[0]))}
        self.assertEqual(len(rows), 4)
        orig, dup = rows[self.rel(x)], rows[self.rel(copy)]
        self.assertEqual((orig["valid"], orig["warnings"]), ("1", ""))
        self.assertNotEqual(orig["checked"], "")
        self.assertEqual(dup["valid"], "0")
        self.assertIn("E_ID", dup["warnings"])

    def test_missing_report_is_an_error(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(X.main([str(self.root), "--report", str(self.tmp / "none.json")]), 2)
        self.assertIn("report not found", err.getvalue())
        self.assertFalse((self.root / X.INDEX_FILE).exists())

    def test_meta_and_events_are_read_and_walked_once(self):
        opened = []
        real_open = open

        def counting_open(file, *a, **kw):
            if str(file).endswith(("meta.json", "events.json")):
                opened.append(str(file))
            return real_open(file, *a, **kw)

        report = self.full_report()
        with unittest.mock.patch("builtins.open", counting_open), unittest.mock.patch("io.open", counting_open), \
                unittest.mock.patch.object(labels, "phase_segments", wraps=labels.phase_segments) as segs:
            X.build_rows(self.root, report)
        self.assertEqual(len(opened), 2 * len(self.paths))
        self.assertEqual(len(set(opened)), len(opened))
        self.assertEqual(segs.call_count, len(self.paths))

    def test_empty_and_broken(self):
        empty = self.tmp / "empty"
        empty.mkdir()
        self.assertTrue(X.regenerate(empty))
        self.assertEqual(self.text(empty)[0], ",".join(X.COLUMNS) + "\n")
        self.assertIn("## Drill type x motor (valid, minutes)\n\nnone\n", self.text(empty)[1])
        # an index.csv from before the drill_type / motor columns still loads (empty until regenerated)
        old = self.tmp / "old.csv"
        old.write_text(",".join(c for c in X.COLUMNS if c not in ("drill_type", "motor")) + "\n")
        self.assertEqual(list(X.read_index(old).columns)[-2:], ["drill_type", "motor"])
        (self.paths[1] / "meta.json").write_text("{")
        X.regenerate(self.root)
        r = self.rows()[F.rid(2)]
        self.assertEqual((r["valid"], r["warnings"]), ("0", "E_JSON"))
        shutil.rmtree(self.paths[2])
        X.regenerate(self.root)
        self.assertNotIn(F.rid(3), self.rows())


@unittest.skipUnless(shutil.which("git"), "needs git")
class GitFingerprintTest(F.TempDirTest):
    """In a git checkout 'checked' is the folder's tree id: the Action's sparse checkout, which has
    only meta.json and events.json, still sees a change of any file."""

    def git(self, *args):
        env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
               "GIT_COMMITTER_EMAIL": "t@t"}
        return subprocess.run(["git", *args], cwd=self.root, env=env, check=True, capture_output=True,
                              text=True).stdout.strip()

    def test_tree_id_covers_files_outside_the_sparse_checkout(self):
        self.root = self.tmp / "data"
        self.root.mkdir()
        self.git("init", "-b", "main")
        x = F.make_recording(self.root, rid=F.rid(1))
        rel = x.relative_to(self.root).as_posix()
        self.assertTrue(V.fingerprint(x).startswith("sha:"))    # untracked: hashed
        self.git("add", "-A")
        self.git("commit", "-m", "recording")
        self.assertEqual(V.fingerprint(x), "git:" + self.git("rev-parse", f"HEAD:{rel}"))
        with contextlib.redirect_stdout(io.StringIO()):
            V.main(["--quiet", "--report", str(self.tmp / "report.json"), str(x)])
        X.regenerate(self.root, json.loads((self.tmp / "report.json").read_text()))
        self.git("add", "-A")
        self.git("commit", "-m", "index")
        self.assertEqual(X.unchecked(self.root), [])
        # the Action's checkout: meta.json and events.json only
        self.git("sparse-checkout", "set", "--no-cone", "/index.csv", "/stats.md",
                 "/recordings/**/meta.json", "/recordings/**/events.json")
        self.assertFalse((x / "imu.csv.gz").exists())
        self.assertEqual(X.unchecked(self.root), [])
        self.assertFalse(X.regenerate(self.root, check=True))
        # a push that changes only a data file
        self.git("sparse-checkout", "disable")
        (x / "imu.csv.gz").write_bytes(b"broken")
        self.assertTrue(V.fingerprint(x).startswith("sha:"))    # changed against HEAD: hashed
        self.git("commit", "-am", "data file")
        self.git("sparse-checkout", "set", "--no-cone", "/index.csv", "/stats.md",
                 "/recordings/**/meta.json", "/recordings/**/events.json")
        self.assertEqual(X.unchecked(self.root), [rel])


if __name__ == "__main__":
    unittest.main()
