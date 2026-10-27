#!/usr/bin/env bash
# The PC BLE client's self-checks (firmware/tools/ble). Standard library only:
# bleak (requirements.txt) is needed only to talk to a device, so it is not
# installed here, which also checks that these run without it.
#
#   tools/ci/db_client.sh
set -euo pipefail
. "$(dirname "$0")/common.sh"

cd "$ROOT/firmware/tools/ble"
"$PYTHON" db_client.py --dry-run
"$PYTHON" -m unittest test_db_client
