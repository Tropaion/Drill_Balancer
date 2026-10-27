#!/usr/bin/env bash
# The device simulator's tests (firmware/tools/sim). No Bluetooth needed: the
# BLE tests export the GATT objects on a private dbus-daemon.
#
#   tools/ci/simulator.sh
#
# Builds the C core (core/build/libdbsim.so) first, so a build error fails
# here and not as a test error. Needs cmake, a C compiler and the ESP-IDF tree
# for cJSON and mbedtls (IDF_PATH or ~/esp/v5.5.1/esp-idf; tools/ci/idf_subset.sh
# fetches just those). Also sets up tools/dataset/.venv, which the recorder
# test runs its export cross-check with.
#
# Skipped without firmware/build/drillbalancer.bin (it is not built here): the
# tests that send the real firmware image over OTA. tools/ci/simulator_ota.sh
# runs just those, against the image of the CI run's firmware build.
set -euo pipefail
. "$(dirname "$0")/common.sh"

dataset="$ROOT/firmware/tools/dataset"
group "Dataset Python environment"
venv "$dataset/.venv" "$dataset/requirements.txt"
endgroup
sim_core

cd "$ROOT/firmware/tools/sim"
.venv/bin/python -m unittest discover -s tests
