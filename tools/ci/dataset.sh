#!/usr/bin/env bash
# The dataset tools (firmware/tools/dataset): unit tests, the auto-detector's
# fixtures, the finalize and labels fixtures the app checks against, and the
# pin of the data repo's validator.
#
#   tools/ci/dataset.sh
#
# The synthetic takes need the simulator's C core (built on demand, see
# tools/ci/simulator.sh for what that needs). Its tests skip without it, so
# this script fails first when it cannot be loaded.
#
# bootstrap_repo.py --check-pin only warns: the dataset-v1 tag is moved after
# a contract change lands, so it may lag a commit. It needs that tag in the
# checkout (fetch-depth 0 in CI).
set -euo pipefail
. "$(dirname "$0")/common.sh"

cd "$ROOT/firmware/tools/dataset"
group "Python environment"
venv .venv requirements.txt
endgroup
py=.venv/bin/python

group "Simulator core"
$py -c 'import sys
from dbdata import synth
why = synth.sim_unavailable_reason()
print(why or "simulator core loaded")
sys.exit(1 if why else 0)'
endgroup

$py -m unittest discover -s tests
$py -m dbdata.autodetect --check-fixtures
$py fixtures/finalize/make_fixtures.py --check
$py fixtures/labels/make_fixtures.py --check

rc=0
pin=$($py bootstrap_repo.py --check-pin 2>&1) || rc=$?
echo "$pin"
if [ "$rc" -ne 0 ]; then
    annotate warning "bootstrap_repo.py --check-pin (exit $rc, not a failure): $pin"
fi
