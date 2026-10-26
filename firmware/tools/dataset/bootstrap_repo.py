#!/usr/bin/env python3
"""Fills a local clone of the data repo with the template and the contract.

    bootstrap_repo.py ~/data/Drill_Balancer_Data            # copy, refuse to overwrite changes
    bootstrap_repo.py ~/data/Drill_Balancer_Data --force    # overwrite differing files
    bootstrap_repo.py ~/data/Drill_Balancer_Data --check    # exit 1 if anything differs

Copies repo_template/ (README, .github/workflows/validate.yml, ...) and the
packaged taxonomy/schemas to taxonomy/ and schema/, creates recordings/ and,
if they are missing, a first index.csv + stats.md (afterwards only the
Action writes them). It never runs git: review, commit and push yourself.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from dbdata import contract, index  # noqa: E402

TEMPLATE = HERE / "repo_template"


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
    ap.add_argument("dest", help="local clone of the data repo")
    ap.add_argument("--force", action="store_true", help="overwrite files that differ")
    ap.add_argument("--check", action="store_true", help="only report what differs (exit 1 if anything)")
    args = ap.parse_args(argv)
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
