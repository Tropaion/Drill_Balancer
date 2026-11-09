#!/usr/bin/env bash
# The ESP-IDF build of firmware/ from the defaults alone, then checks:
#   - the app image fits the smallest app partition (fails), and warns when
#     less than MIN_FREE_PCT (default 10) percent of it is left;
#   - static internal RAM (D/IRAM) left: fails below MIN_DIRAM_FREE_KB
#     (default 125; firmware/README.md, bring-up step 34, says why);
#   - compiler warnings in our own code (components/, main/) become warning
#     annotations on their lines (not a failure: the firmware does not build
#     with -Werror), at most MAX_WARNING_ANNOTATIONS of them;
#   - dependencies.lock (before the build): the component manager must not
#     change the committed lock (fails when a component version differs, warns
#     on any other rewrite).
#
#   tools/ci/firmware_build.sh [BUILD_DIR]    # default firmware/build-ci
#
# A relative BUILD_DIR is taken from the current directory.
# BUILD_DIR has its own sdkconfig (BUILD_DIR/sdkconfig), made from
# sdkconfig.defaults + sdkconfig.d/*.defaults: firmware/sdkconfig and
# firmware/build are left alone.
set -euo pipefail
. "$(dirname "$0")/common.sh"

fw="$ROOT/firmware"
build=$(abs_path "${1:-$fw/build-ci}")
min_free_pct=${MIN_FREE_PCT:-10}
min_diram_free_kb=${MIN_DIRAM_FREE_KB:-125}
# GitHub keeps 10 warning annotations per step; the size and the lock check
# can add one each.
max_warning_annotations=${MAX_WARNING_ANNOTATIONS:-8}
idf_export
component_manager_pin
git_trust_checkout
cd "$fw"
mkdir -p "$build"
log="$build/ci-build.log"

# Components first: a version other than the lock's fails here with the diff,
# not later as a compile or link error.
idf=(idf.py -B "$build" -D SDKCONFIG="$build/sdkconfig")
"${idf[@]}" reconfigure
components_check
"${idf[@]}" build 2>&1 | tee "$log"

group "Size"
# The build's app_check_size target (check_sizes.py) printed this line, and
# failed the build when the image does not fit.
size_line=$(grep -m1 'Smallest app partition' "$log")
echo "$size_line"
summary "Firmware: $size_line"
free_pct=$(echo "$size_line" | sed -n 's/.*(\([0-9]*\)%) free.*/\1/p')
if [ -n "$free_pct" ] && [ "$free_pct" -lt "$min_free_pct" ]; then
    annotate warning "app image: only $free_pct% of the app partition free (warning below $min_free_pct%)"
fi
size_json="$build/ci-size.json"
"${idf[@]}" size --format json --output-file "$size_json" > /dev/null
diram_free=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["diram_remain"])' \
    "$size_json") || { annotate error "no diram_remain in $size_json (idf.py size)"; exit 1; }
diram_free_kb=$((diram_free / 1024))
echo "Static internal RAM (D/IRAM) left: $diram_free_kb KB"
summary "Firmware: $diram_free_kb KB of static internal RAM left"
if [ "$diram_free_kb" -lt "$min_diram_free_kb" ]; then
    annotate error "only $diram_free_kb KB of static internal RAM left (at least $min_diram_free_kb KB): the recorder's Wi-Fi would run short on the board"
    exit 1
fi
endgroup

warnings=$(compiler_warnings "$log" "$build")
if [ -n "$warnings" ]; then
    count=$(echo "$warnings" | wc -l)
    echo "$count compiler warning(s) in our components:"
    echo "$warnings"
    if in_ci; then warning_annotations "$max_warning_annotations" <<< "$warnings"; fi
    summary "Compiler warnings in components/ and main/: $count"
else
    echo "No compiler warnings in components/ or main/."
fi
