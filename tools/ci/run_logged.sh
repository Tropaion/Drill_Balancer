#!/usr/bin/env bash
# Runs a CI job script and, when it fails, repeats the lines that explain the
# failure as an error annotation: annotations show on the run's page without
# signing in, the job log does not.
#
#   tools/ci/run_logged.sh tools/ci/<job>.sh [ARGS...]
set -uo pipefail
. "$(dirname "$0")/common.sh"

log=$(mktemp)
trap 'rm -f "$log"' EXIT
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
    # without colour codes; the error-looking lines first (at most 40), then the last 60
    plain=$(sed -E 's/\x1b\[[0-9;]*[A-Za-z]//g' "$log")
    # long lines (compiler and linker command lines) cut to 300 characters
    hits=$(grep -nE '(error|Error|ERROR|FAIL|failed|Failed|undefined reference|What went wrong|Exception|Traceback)' <<< "$plain" \
        | grep -vE -- '- (failed|Failed)$' | cut -c1-300 | head -n 40)
    tail=$(tail -n 60 <<< "$plain" | cut -c1-300)
    msg="$(basename "$1") exited with $status."$'\n'"-- matching lines (line: text) --"$'\n'"$hits"$'\n'"-- last 60 lines --"$'\n'"$tail"
    annotate error "${msg:0:12000}"
fi
exit "$status"
