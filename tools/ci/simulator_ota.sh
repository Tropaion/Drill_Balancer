#!/usr/bin/env bash
# The simulator tests that send the real firmware image over OTA, against
# firmware/build/drillbalancer.bin. CI puts the image of the same run's
# firmware build there; on a PC it is the one `idf.py build` made.
#
#   tools/ci/simulator_ota.sh
#
# Fails when the image is missing or one of these tests skips (they skip in
# tools/ci/simulator.sh, which runs without the image). Needs what
# simulator.sh needs for the C core (cmake, a C compiler, IDF_PATH).
set -euo pipefail
. "$(dirname "$0")/common.sh"

image="$ROOT/firmware/build/drillbalancer.bin"
if [ ! -f "$image" ]; then
    annotate error "no firmware image at ${image#"$ROOT"/}"
    exit 1
fi
echo "image: ${image#"$ROOT"/} ($(wc -c < "$image") bytes)"

sim_core

cd "$ROOT/firmware/tools/sim"
.venv/bin/python "$ROOT/tools/ci/sim_image_tests.py" tests
