#!/usr/bin/env python3
"""Fills a local clone of the data repo with the template and the contract.

    bootstrap_repo.py ~/data/Drill_Balancer_Data            # copy, refuse to overwrite changes
    bootstrap_repo.py ~/data/Drill_Balancer_Data --force    # overwrite differing files
    bootstrap_repo.py ~/data/Drill_Balancer_Data --check    # exit 1 if anything differs
    bootstrap_repo.py --check-pin                           # exit 1 if the Action's validator is stale

Copies repo_template/ (README, .github/workflows/validate.yml, ...) and the
packaged taxonomy/schemas to taxonomy/ and schema/, creates recordings/ and,
if they are missing, a first index.csv + stats.md (afterwards only the
Action writes them). It never changes git state: review, commit and push
yourself.

The data repo's Action checks out dbdata at the tag in its DBDATA_REF
(dataset-v1). --check-pin compares the dbdata package at that tag with this
checkout (uncommitted changes included) and lists what differs: after a
contract change in place (no new version, so E_TAXONOMY_VERSION cannot
catch it) the pinned validator rejects what the new app writes until the
tag is moved (README, "Changing the contract"). Copying runs the same check
and warns.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from dbdata import contract, index  # noqa: E402

TEMPLATE = HERE / "repo_template"
WORKFLOW = TEMPLATE / ".github" / "workflows" / "validate.yml"
PACKAGE = HERE / "dbdata"


def pinned_ref(workflow: Path = WORKFLOW) -> str:
    """The tag (or ref) of dbdata the data repo's Action checks out: its DBDATA_REF."""
    m = re.search(r"^\s*DBDATA_REF:\s*(\S+)\s*$", workflow.read_text(encoding="utf-8"), re.M)
    if not m:
        raise ValueError(f"{workflow}: no DBDATA_REF")
    return m.group(1)


def pin_differences(ref: str | None = None, package: Path = PACKAGE) -> list[str]:
    """Files of [package] that differ between [ref] (default pinned_ref()) and the working tree.

    Repo-relative paths, uncommitted and untracked (not ignored) files included; empty: the
    Action's validator is this one. Read-only git; RuntimeError when git or the ref is missing.
    """
    ref = ref or pinned_ref()
    package = Path(package).resolve()

    def git(*args: str) -> str:
        try:
            r = subprocess.run(["git", "-C", str(package), *args], capture_output=True, text=True)
        except FileNotFoundError as e:
            raise RuntimeError("git is not installed") from e
        if r.returncode != 0:
            raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()}")
        return r.stdout

    git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
    # (run in the package: the pathspec "." is the package, the names are repo-relative)
    changed = git("-c", "core.quotePath=false", "diff", "--no-relative", "--name-only", ref, "--", ".").splitlines()
    new = git("-c", "core.quotePath=false", "ls-files", "--full-name", "--others", "--exclude-standard",
              "--", ".").splitlines()
    return sorted({p for p in changed + new if p and "__pycache__" not in p.split("/")})


def pin_report(ref: str | None = None) -> tuple[int, str]:
    """(exit code, text) of --check-pin: 0 the pinned validator is this one, 1 it differs, 2 unknown."""
    ref = ref or pinned_ref()
    try:
        diff = pin_differences(ref)
    except RuntimeError as e:
        return 2, f"cannot compare dbdata with {ref}: {e}"
    if not diff:
        return 0, f"the Action's validator ({ref}) is this dbdata"
    lines = [f"the Action's validator ({ref}) differs from this dbdata:"] + [f"  {p}" for p in diff]
    lines.append(f"commit, then: git tag -f {ref} && git push --force origin {ref}; "
                 "then bootstrap_repo.py DEST --force and push the data repo; then ship the app")
    return 1, "\n".join(lines)


def planned_files() -> dict[str, bytes]:
    """Repo-relative path -> content."""
    files = {p.relative_to(TEMPLATE).as_posix(): p.read_bytes()
             for p in sorted(TEMPLATE.rglob("*")) if p.is_file() and "__pycache__" not in p.parts}
    for rel, name in contract.REPO_COPIES.items():
        files[rel] = contract.contract_path(name).read_bytes()
    files["recordings/.gitkeep"] = b""
    return files


def bootstrap(dest: str | Path, force: bool = False, check: bool = False) -> tuple[list[str], list[str]]:
    """Returns (written or differing paths, conflicting paths left alone)."""
    root = Path(dest)
    changed, conflicts = [], []
    for rel, data in planned_files().items():
        p = root / rel
        if p.is_file() and p.read_bytes() == data:
            continue
        if p.exists() and not force and not check:
            conflicts.append(rel)
            continue
        changed.append(rel)
        if not check:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
    # a first, empty index; afterwards only the Action writes it
    # (skipped while a conflict is left, and then not reported: the run that resolves it writes them)
    missing = [n for n in (index.INDEX_FILE, index.STATS_FILE) if not (root / n).is_file()]
    if missing and check:
        changed += missing
    elif missing and not conflicts:
        index.regenerate(root)
        changed += missing
    return changed, conflicts


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dest", nargs="?", help="local clone of the data repo")
    ap.add_argument("--force", action="store_true", help="overwrite files that differ")
    ap.add_argument("--check", action="store_true", help="only report what differs (exit 1 if anything)")
    ap.add_argument("--check-pin", action="store_true",
                    help="only compare dbdata with the tag the Action pins (exit 1 if it differs)")
    args = ap.parse_args(argv)
    if args.check_pin:
        code, text = pin_report()
        print(text, file=sys.stdout if code == 0 else sys.stderr)
        return code
    if args.dest is None:
        ap.error("dest is required (or --check-pin)")
    code, text = pin_report()
    if code:
        print(f"warning: {text}", file=sys.stderr)
    dest = Path(args.dest).expanduser()
    if not dest.is_dir():
        print(f"{dest}: not a directory (clone the data repo first)", file=sys.stderr)
        return 2
    if not (dest / ".git").exists():
        print(f"note: {dest} is not a git checkout", file=sys.stderr)
    changed, conflicts = bootstrap(dest, force=args.force, check=args.check)
    for rel in changed:
        print(("differs: " if args.check else "wrote:   ") + rel)
    for rel in conflicts:
        print(f"kept:    {rel} (differs from the template; --force overwrites)")
    if args.check:
        return 1 if changed else 0
    if not changed and not conflicts:
        print("up to date")
    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())
