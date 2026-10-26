"""bootstrap_repo.py: template + contract copies into a data repo clone, and the workflow's essentials."""

import contextlib
import io
import os
import shutil
import subprocess
import sys
import unittest
import unittest.mock

import fixtures as F
import bootstrap_repo as B  # after fixtures, which puts its folder on sys.path
from dbdata import contract
from dbdata import index as X
from dbdata import validate as V

WORKFLOW = F.HERE.parent / "repo_template" / ".github" / "workflows" / "validate.yml"


def quiet(fn, *a, **kw):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*a, **kw)


class BootstrapTest(F.TempDirTest):
    def test_fresh_repo(self):
        repo = self.tmp / "data"
        repo.mkdir()
        self.assertEqual(quiet(B.main, [str(repo)]), 0)
        for rel in ("README.md", ".github/workflows/validate.yml", ".gitattributes", "recordings/.gitkeep",
                    "index.csv", "stats.md", *contract.REPO_COPIES):
            self.assertTrue((repo / rel).is_file(), rel)
        for rel, name in contract.REPO_COPIES.items():
            self.assertEqual((repo / rel).read_bytes(), contract.contract_path(name).read_bytes())
        self.assertEqual(V.check_contract(repo), [])
        self.assertEqual((repo / "index.csv").read_text(), ",".join(X.COLUMNS) + "\n")
        self.assertEqual(quiet(B.main, [str(repo), "--check"]), 0)

    def test_conflicts_force_and_index_left_alone(self):
        repo = self.tmp / "data"
        repo.mkdir()
        quiet(B.main, [str(repo)])
        F.make_recording(repo)
        X.regenerate(repo)
        index_before = (repo / "index.csv").read_text()
        (repo / "README.md").write_text("local edit\n")
        self.assertEqual(quiet(B.main, [str(repo), "--check"]), 1)
        self.assertEqual(quiet(B.main, [str(repo)]), 1)              # refuses to overwrite
        self.assertEqual((repo / "README.md").read_text(), "local edit\n")
        self.assertEqual(quiet(B.main, [str(repo), "--force"]), 0)
        self.assertNotEqual((repo / "README.md").read_text(), "local edit\n")
        self.assertEqual((repo / "index.csv").read_text(), index_before)  # only the Action writes it
        self.assertEqual(quiet(B.main, [str(self.tmp / "missing")]), 2)

    def test_missing_index_is_not_reported_while_a_conflict_blocks_it(self):
        repo = self.tmp / "data"
        repo.mkdir()
        (repo / "README.md").write_text("local edit\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(B.main([str(repo)]), 1)
        self.assertFalse((repo / "index.csv").exists())
        self.assertNotIn("index.csv", out.getvalue())
        self.assertIn("kept:    README.md", out.getvalue())
        changed, _ = B.bootstrap(repo, check=True)
        self.assertIn("index.csv", changed)                       # --check names it as missing
        self.assertEqual(quiet(B.main, [str(repo), "--force"]), 0)
        self.assertTrue((repo / "index.csv").is_file())

    def test_workflow_essentials(self):
        y = WORKFLOW.read_text()
        # a recording, the contract copies or the workflow itself start a run
        for needle in ('- "recordings/**"', '- "taxonomy/**"', '- "schema/**"',
                       '- ".github/workflows/validate.yml"', "group: index", "contents: write",
                       "DBDATA_REPO: Tropaion/Drill_Balancer", "DBDATA_REF: dataset-v1",
                       "sparse-checkout-cone-mode: false", "/recordings/**/meta.json", "filter: blob:none",
                       "python -m dbdata.validate --contract .", "python -m dbdata.index . --report",
                       "--list-unchecked", "persist-credentials: false", "actions/setup-python@",
                       "ref: ${{ github.ref_name }}", 'echo "{}" > "$RUNNER_TEMP/report.json"'):
            self.assertIn(needle, y)
        # generated files are regenerated on the new tip, never rebased or merged
        self.assertNotIn("pull --rebase", y)
        self.assertNotIn("git merge", y)
        # event data and inputs reach scripts only through env variables, never inline
        for line in y.splitlines():
            if "${{ inputs." in line or "${{ github.event." in line:
                self.assertRegex(line, r"^\s+[A-Z_]+: \$\{\{ [a-z.]+ \}\}$")
        # least privilege: one permissions block, contents only
        self.assertEqual(y.count("permissions:"), 1)
        perm = y.split("permissions:", 1)[1].split("\n\n", 1)[0].strip().splitlines()
        self.assertEqual([p.strip() for p in perm], ["contents: write"])


@unittest.skipUnless(shutil.which("git"), "needs git")
class PinTest(F.TempDirTest):
    """--check-pin: the dbdata the data repo's Action checks out (DBDATA_REF) against this one."""

    def git(self, *args):
        env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1", "GIT_AUTHOR_NAME": "t",
               "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        subprocess.run(["git", *args], cwd=self.repo, env=env, check=True, capture_output=True)

    def setUp(self):
        super().setUp()
        self.repo = self.tmp / "main"
        self.pkg = self.repo / "firmware" / "tools" / "dataset" / "dbdata"
        (self.pkg / "contract").mkdir(parents=True)
        (self.pkg / "contract" / "meta.v1.schema.json").write_text("{}\n")
        (self.pkg / "validate.py").write_text("v = 1\n")
        (self.repo / "README.md").write_text("outside the package\n")
        (self.repo / ".gitignore").write_text("__pycache__/\n")
        self.git("init", "-q", "-b", "main")
        self.git("add", "-A")
        self.git("commit", "-qm", "contract")
        self.git("tag", "dataset-v1")

    def test_the_pinned_ref_is_the_workflows(self):
        self.assertEqual(B.pinned_ref(), "dataset-v1")
        self.assertIn(f"DBDATA_REF: {B.pinned_ref()}", WORKFLOW.read_text())

    def test_a_contract_changed_in_place_without_moving_the_tag(self):
        self.assertEqual(B.pin_differences("dataset-v1", self.pkg), [])
        (self.repo / "README.md").write_text("changed, not the validator\n")
        (self.pkg / "__pycache__").mkdir()
        (self.pkg / "__pycache__" / "validate.cpython-312.pyc").write_bytes(b"x")
        self.assertEqual(B.pin_differences("dataset-v1", self.pkg), [])
        # the schema changed in place (uncommitted, then committed) and a new module: the tag is stale
        (self.pkg / "contract" / "meta.v1.schema.json").write_text('{"required": ["motor"]}\n')
        (self.pkg / "tool.py").write_text("")
        want = ["firmware/tools/dataset/dbdata/contract/meta.v1.schema.json", "firmware/tools/dataset/dbdata/tool.py"]
        self.assertEqual(B.pin_differences("dataset-v1", self.pkg), want)
        self.git("add", "-A")
        self.git("commit", "-qm", "drill type and motor")
        self.assertEqual(B.pin_differences("dataset-v1", self.pkg), want)
        # the tag moved to the commit: the Action's validator is this one
        self.git("tag", "-f", "dataset-v1")
        self.assertEqual(B.pin_differences("dataset-v1", self.pkg), [])
        with self.assertRaises(RuntimeError):
            B.pin_differences("dataset-v9", self.pkg)
        self.assertEqual(B.pin_report("dataset-v9-no-such-ref")[0], 2)

    def test_the_cli(self):
        for (code, text), want in (((0, "same"), 0), ((1, "stale"), 1), ((2, "no git"), 2)):
            with unittest.mock.patch.object(B, "pin_report", return_value=(code, text)):
                self.assertEqual(quiet(B.main, ["--check-pin"]), want)
        # copying warns about a stale tag (and still copies)
        repo = self.tmp / "data"
        repo.mkdir()
        err = io.StringIO()
        with unittest.mock.patch.object(B, "pin_report", return_value=(1, "stale")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            self.assertEqual(B.main([str(repo)]), 0)
        self.assertIn("warning: stale", err.getvalue())
        self.assertTrue((repo / "README.md").is_file())
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            B.main([])


def step_script(name: str) -> str:
    """The `run: |` script of the workflow step with this name, dedented."""
    lines = WORKFLOW.read_text().splitlines()
    i = next(k for k, ln in enumerate(lines) if ln.strip() == f"- name: {name}")
    j = next(k for k in range(i + 1, len(lines)) if lines[k].strip() == "run: |")
    indent = len(lines[j]) - len(lines[j].lstrip()) + 2
    body = []
    for ln in lines[j + 1:]:
        if ln.strip() and len(ln) - len(ln.lstrip()) < indent:
            break
        body.append(ln[indent:])
    return "\n".join(body) + "\n"


@unittest.skipUnless(shutil.which("git") and shutil.which("bash"), "needs git and bash")
class WorkflowCommitTest(F.TempDirTest):
    """The index commit step against a real (local) remote that moved on during the run."""

    def git(self, cwd, *args):
        return subprocess.run(["git", *args], cwd=cwd, env=self.env, check=True, capture_output=True,
                              text=True).stdout.strip()

    def setUp(self):
        super().setUp()
        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "python").write_text(f'#!/bin/sh\nexec "{sys.executable}" "$@"\n')  # this venv
        (bin_dir / "python").chmod(0o755)
        (bin_dir / "sleep").write_text("#!/bin/sh\nexit 0\n")   # no back-off waits in the test
        (bin_dir / "sleep").chmod(0o755)
        self.env = {**os.environ, "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                    "PYTHONPATH": str(F.HERE.parent), "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                    "GIT_COMMITTER_EMAIL": "t@t", "GITHUB_REF_NAME": "main"}

    def test_regenerates_on_the_new_tip_instead_of_rebasing(self):
        remote, phone, runner = self.tmp / "remote.git", self.tmp / "phone", self.tmp / "runner"
        self.git(self.tmp, "init", "--bare", "-b", "main", str(remote))
        self.git(self.tmp, "clone", str(remote), str(phone))
        a = F.make_recording(phone, rid=F.rid(1))
        b = F.make_recording(phone, rid=F.rid(2))
        quiet(X.regenerate, phone)
        self.git(phone, "add", "-A")
        self.git(phone, "commit", "-m", "two recordings + index")
        self.git(phone, "push", "origin", "main")
        # the run starts here and validates both recordings ...
        self.git(self.tmp, "clone", str(remote), str(runner))
        temp = self.tmp / "runner_temp"
        temp.mkdir()
        rels = [p.relative_to(phone).as_posix() for p in (a, b)]
        (temp / "changed.txt").write_text("".join(r + "\n" for r in rels))
        quiet(V.main, ["--quiet", "--report", str(temp / "report.json"), *(str(runner / r) for r in rels)])
        # ... while a label edit of recording 1 lands, and the previous run's index commit after it
        F.edit_json(a / "events.json", lambda e: e["events"][0].update(confidence=0.5))
        self.git(phone, "commit", "-am", "label edit")
        quiet(X.regenerate, phone)
        self.git(phone, "commit", "-am", "Update index")
        self.git(phone, "push", "origin", "main")
        tip = self.git(phone, "rev-parse", "HEAD")

        r = subprocess.run(["bash", "-c", step_script("Regenerate and commit index.csv and stats.md")],
                           cwd=runner, env={**self.env, "RUNNER_TEMP": str(temp)}, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.git(phone, "pull", "origin", "main")
        self.assertEqual(self.git(phone, "rev-parse", "HEAD~1"), tip)         # one commit on top of the tip
        self.assertEqual(self.git(phone, "status", "--porcelain"), "")
        rows = X.read_previous(phone / X.INDEX_FILE)
        # recording 2 is unchanged: its full check counts; recording 1 changed after the check: not
        self.assertNotEqual(rows[F.rid(2)]["checked"], "")
        self.assertNotIn("W_UNCHECKED", rows[F.rid(2)]["warnings"])
        self.assertEqual(rows[F.rid(1)]["checked"], "")
        self.assertIn("W_UNCHECKED", rows[F.rid(1)]["warnings"])
        self.assertEqual(X.unchecked(phone), [rels[0]])                     # the next run's work
        self.assertFalse(quiet(X.regenerate, phone, check=True))           # and the committed index is current


if __name__ == "__main__":
    unittest.main()
