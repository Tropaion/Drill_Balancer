#!/usr/bin/env bash
# Starts the DrillBalancer simulator (BLE peripheral on BlueZ + web UI).
# Arguments go to "python -m dbsim" (--adapter, --state-dir, --no-web, --no-ble,
# --host, --web-port, -q). Creates the venv on first use.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
    .venv/bin/pip install -q -r requirements.txt
fi
exec .venv/bin/python -m dbsim "$@"
