#!/usr/bin/env bash
# Firmware host tests (firmware/test_host, ctest) and the UI preview PNGs.
#
#   tools/ci/firmware_host.sh [BUILD_DIR]     # default firmware/build-ci-host
#
# A relative BUILD_DIR is taken from the current directory.
# Needs ESP-IDF (IDF_PATH or ~/esp/v5.5.1/esp-idf): Unity, cJSON and mbedtls
# come from its tree, cmake and ninja from its tools. The preview builds the
# LVGL of firmware/managed_components; when that is missing (a fresh clone),
# an `idf.py reconfigure` in its own build directory fetches the components
# that dependencies.lock names.
set -euo pipefail
. "$(dirname "$0")/common.sh"

fw="$ROOT/firmware"
build=$(abs_path "${1:-$fw/build-ci-host}")
idf_export
component_manager_pin
git_trust_checkout

if [ ! -f "$fw/managed_components/lvgl__lvgl/lvgl.h" ]; then
    group "Fetch the managed components (idf.py reconfigure)"
    (cd "$fw" && idf.py -B build-ci-deps -D SDKCONFIG=build-ci-deps/sdkconfig reconfigure)
    endgroup
    components_check
fi

group "Configure and build the host tests"
cmake -S "$fw/test_host" -B "$build" -G Ninja
cmake --build "$build"
endgroup

ctest --test-dir "$build" --output-on-failure

# The PNGs of this run only: out/ is gitignored and keeps those of earlier runs.
out="$fw/test_host/ui_preview/out"
if [ -d "$out" ]; then find "$out" -maxdepth 1 -name '*.png' -delete; fi
group "UI preview"
cmake --build "$build" --target ui_preview_png
endgroup

pngs=0
if [ -d "$out" ]; then pngs=$(find "$out" -maxdepth 1 -name '*.png' | wc -l); fi
echo "UI preview: $pngs PNGs in $out"
if [ "$pngs" -eq 0 ]; then
    annotate error "ui_preview_png wrote no PNGs"
    exit 1
fi
summary "UI preview: $pngs PNGs"
