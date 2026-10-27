#!/usr/bin/env bash
# Self-tests of the CI scripts in tools/ci, without ESP-IDF: they run copies of
# the scripts in a scratch repo (firmware/build-ci-selftest, gitignored) with
# stand-ins for idf.py, cmake, ctest and the component manager's pip show.
#
#   tools/ci/test_ci.sh
set -euo pipefail
. "$(dirname "$0")/common.sh"
real_root=$ROOT

cd "$real_root" && rm -rf firmware/build-ci-selftest
sb="$real_root/firmware/build-ci-selftest"
R="$sb/root"
mkdir -p "$R/tools/ci" "$R/firmware" "$sb/bin" "$sb/idf" "$sb/cwd"
cp "$real_root"/tools/ci/*.sh "$real_root"/tools/ci/*.py "$R/tools/ci/"
cp "$real_root/firmware/dependencies.lock" "$R/firmware/"
export GIT_CONFIG_GLOBAL="$sb/gitconfig" GIT_CONFIG_NOSYSTEM=1
git -C "$R" init --quiet
git -C "$R" add firmware/dependencies.lock
git -C "$R" -c user.name=ci -c user.email=ci@example.invalid commit --quiet -m lock

passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok: $1"; }
# Indented after the first line: a CI log would run a line that starts with ::
not_ok() { failed=$((failed + 1)); printf 'NOT OK: %s\n' "$1" | sed '2,$s/^/    /' >&2; }
expect_eq() {   # NAME EXPECTED ACTUAL
    if [ "$2" = "$3" ]; then ok "$1"; else not_ok "$1"$'\n'"  expected: $2"$'\n'"  actual:   $3"; fi
}
expect_grep() {   # NAME PATTERN TEXT
    if grep -qE -- "$2" <<< "$3"; then ok "$1"; else not_ok "$1: no line matches $2 in:"$'\n'"$3"; fi
}
expect_rc() {   # NAME EXPECTED: $rc of the last run, with its output when it differs
    if [ "$rc" = "$2" ]; then ok "$1"; else not_ok "$1: exit $rc, expected $2; output:"$'\n'"$out"; fi
}
expect_no_grep() {   # NAME PATTERN TEXT
    if grep -qE -- "$2" <<< "$3"; then not_ok "$1: a line matches $2 in:"$'\n'"$3"; else ok "$1"; fi
}

# --- stand-ins --------------------------------------------------------------
cat > "$sb/bin/python" <<'EOF'
#!/usr/bin/env bash
if [ "$*" = "-m pip show idf-component-manager" ]; then echo "Version: 2.4.9"; exit 0; fi
exec python3 "$@"
EOF
# idf.py -B BUILD -D SDKCONFIG=... reconfigure|build: logs its directory and
# arguments; build prints $STUB_WARNINGS as the compiler output, then the size
# line of the app_check_size target.
cat > "$sb/bin/idf.py" <<'EOF'
#!/usr/bin/env bash
echo "$PWD $*" >> "$STUB_LOG"
build=$2
case ${*: -1} in
    reconfigure)
        mkdir -p "$build"
        if [ -n "${STUB_LOCK_SED:-}" ]; then sed -i -e "$STUB_LOCK_SED" dependencies.lock; fi ;;
    build)
        if [ -n "${STUB_WARNINGS:-}" ]; then cat "$STUB_WARNINGS"; fi
        echo "drillbalancer.bin binary size 0x100000 bytes. Smallest app partition is 0x600000 bytes. 0x500000 bytes (83%) free." ;;
esac
EOF
# cmake/ctest: log their arguments; the ui_preview_png target writes
# $STUB_PNGS PNGs (default none: a broken target).
cat > "$sb/bin/cmake" <<'EOF'
#!/usr/bin/env bash
echo "$PWD cmake $*" >> "$STUB_LOG"
if [ "${*: -1}" = ui_preview_png ]; then
    for i in $(seq 1 "${STUB_PNGS:-0}"); do
        mkdir -p "$STUB_OUT" && : > "$STUB_OUT/screen$i.png"
    done
fi
EOF
printf '#!/usr/bin/env bash\necho "$PWD ctest $*" >> "$STUB_LOG"\n' > "$sb/bin/ctest"
chmod +x "$sb/bin/"*

# capture CMD ARGS...: runs CMD; its output (stdout and stderr) in $out, its
# exit status in $rc.
capture() {
    set +e
    out=$("$@" 2>&1)
    rc=$?
    set -e
}

# run_stub SCRIPT ARGS...: captures a copied script, run as in CI from $sb/cwd.
run_stub() {
    : > "$sb/stub.log"
    capture env -C "$sb/cwd" PATH="$sb/bin:$PATH" GITHUB_ACTIONS=true GITHUB_STEP_SUMMARY="$sb/summary" \
        IDF_PATH="$sb/idf" STUB_LOG="$sb/stub.log" STUB_OUT="$R/firmware/test_host/ui_preview/out" \
        "$R/tools/ci/$@"
}

# --- annotate ---------------------------------------------------------------
line=$(GITHUB_ACTIONS=true annotate warning $'50% done\r\nnext' 'firmware/a,b:c.c' 12)
expect_eq "annotate escapes the message and the file property" \
    '::warning file=firmware/a%2Cb%3Ac.c,line=12::50%25 done%0D%0Anext' "$line"
line=$(GITHUB_ACTIONS=true annotate error 'a: b, c')
expect_eq "annotate without a file has no properties" '::error::a: b, c' "$line"
line=$(GITHUB_ACTIONS= annotate warning 'text' 'firmware/x.c' 3 2>&1)
expect_eq "annotate on a PC names file and line" 'warning: firmware/x.c:3: text' "$line"

# --- firmware_build.sh: BUILD_DIR -------------------------------------------
run_stub firmware_build.sh rel-build
expect_rc "firmware_build.sh passes" 0
expect_eq "firmware_build.sh takes a relative BUILD_DIR from the caller's directory" \
    "-B $sb/cwd/rel-build" "$(sed -n '1s/.* \(-B [^ ]*\) .*/\1/p' "$sb/stub.log")"
expect_grep "the build log lands in that directory" '.' "$(ls "$sb/cwd/rel-build/ci-build.log" 2>/dev/null)"

# --- firmware_build.sh: compiler warnings -----------------------------------
mkdir -p "$R/firmware/components/ui/src"
: > "$R/firmware/components/ui/src/a,b.c"
cat > "$sb/warnings.txt" <<EOF
$R/firmware/components/level/src/fusion.c:12:5: warning: unused variable 'x' [-Wunused-variable]
$R/firmware/components/level/src/fusion.c:12:5: warning: unused variable 'x' [-Wunused-variable]
../components/ui/src/a,b.c:3:1: warning: 50% done: x
main/app_main.c:7:2: warning: shadow
$R/firmware/managed_components/lvgl__lvgl/src/x.c:1:1: warning: not ours
/opt/esp-idf/components/freertos/x.c:1:1: warning: not ours either
In file included from $R/firmware/main/app_main.c:1:
EOF
STUB_WARNINGS="$sb/warnings.txt" run_stub firmware_build.sh
expect_rc "firmware_build.sh passes with warnings" 0
expect_eq "warning annotations: our files only, once each, repo-relative and escaped" \
    "::warning file=firmware/components/level/src/fusion.c,line=12::unused variable 'x' [-Wunused-variable]
::warning file=firmware/components/ui/src/a%2Cb.c,line=3::50%25 done: x
::warning file=firmware/main/app_main.c,line=7::shadow" "$(grep '^::warning' <<< "$out")"

for i in $(seq 10 21); do echo "$R/firmware/main/w$i.c:$i:1: warning: w$i"; done > "$sb/warnings.txt"
STUB_WARNINGS="$sb/warnings.txt" run_stub firmware_build.sh
expect_eq "12 warnings: 7 on their lines" 7 "$(grep -c '^::warning file=' <<< "$out")"
expect_grep "12 warnings: then one line for the other 5" \
    '^::warning::5 more compiler warnings in components/ and main/, listed in the log$' "$out"
STUB_WARNINGS="$sb/warnings.txt" MAX_WARNING_ANNOTATIONS=12 run_stub firmware_build.sh
expect_eq "12 warnings, at most 12: all on their lines" 12 "$(grep -c '^::warning file=' <<< "$out")"

# --- components_check (through firmware_build.sh) ---------------------------
STUB_LOCK_SED='/^  idf:$/,/^    version:/ s/^    version: .*/    version: 5.5.2/' run_stub firmware_build.sh
expect_rc "another ESP-IDF patch release in the idf entry does not fail" 0
expect_grep "... but warns about the rewrite" '^::warning::the component manager rewrote firmware/dependencies.lock' "$out"
git -C "$R" checkout --quiet -- firmware/dependencies.lock
STUB_LOCK_SED='/^  lvgl\/lvgl:$/,/^    version:/ s/^    version: .*/    version: 9.6.0/' run_stub firmware_build.sh
expect_rc "another component version fails" 1
expect_grep "... with both versions in the error" '^::error::the component manager resolved other versions .*lock: lvgl/lvgl 9.4.0 now: lvgl/lvgl 9.6.0' "$out"
git -C "$R" checkout --quiet -- firmware/dependencies.lock

# --- firmware_host.sh -------------------------------------------------------
mkdir -p "$R/firmware/managed_components/lvgl__lvgl" "$R/firmware/test_host/ui_preview/out"
: > "$R/firmware/managed_components/lvgl__lvgl/lvgl.h"
: > "$R/firmware/test_host/ui_preview/out/stale.png"
run_stub firmware_host.sh rel-host
expect_rc "firmware_host.sh fails when the target writes no PNG, despite a stale one" 1
expect_grep "... with the error annotation" '^::error::ui_preview_png wrote no PNGs$' "$out"
expect_eq "firmware_host.sh takes a relative BUILD_DIR from the caller's directory" \
    "-B $sb/cwd/rel-host" "$(sed -n '1s/.* \(-B [^ ]*\) .*/\1/p' "$sb/stub.log")"
rmdir "$R/firmware/test_host/ui_preview/out"
run_stub firmware_host.sh
expect_rc "firmware_host.sh fails when out/ is missing" 1
expect_grep "... with the error annotation" '^::error::ui_preview_png wrote no PNGs$' "$out"
STUB_PNGS=3 run_stub firmware_host.sh
expect_rc "firmware_host.sh passes when the target writes PNGs" 0
expect_grep "... and counts them" '^UI preview: 3 PNGs' "$out"

# --- sim_image_tests.py -----------------------------------------------------
expect_eq "sim_image_tests.py finds the image tests of tools/sim" \
    "test_ble.BleTest.test_ota_indications_and_confirm
test_ble.BleTest.test_unsubscribe_ends_the_unconfirmed_indication
test_ota.FirmwareImageOtaTest" \
    "$(python3 "$real_root/tools/ci/sim_image_tests.py" --list "$real_root/firmware/tools/sim/tests")"
mkdir -p "$sb/fake_tests"
cat > "$sb/fake_tests/test_fake.py" <<EOF
import unittest
from pathlib import Path
FIRMWARE_IMAGE = Path("$sb/fake.bin")

class PlainTest(unittest.TestCase):
    def test_plain(self):
        pass

    @unittest.skipUnless(FIRMWARE_IMAGE.exists(), "needs the image")
    def test_image(self):
        self.assertTrue(FIRMWARE_IMAGE.exists())
EOF
capture python3 "$R/tools/ci/sim_image_tests.py" "$sb/fake_tests"
expect_rc "sim_image_tests.py fails when an image test skips" 1
expect_grep "... and says which" '^skipped: test_fake.PlainTest.test_image' "$out"
expect_no_grep "... and runs only the image tests" 'test_plain' "$out"
: > "$sb/fake.bin"
capture python3 "$R/tools/ci/sim_image_tests.py" "$sb/fake_tests"
expect_rc "sim_image_tests.py passes when they run" 0
sed -i 's/FIRMWARE_IMAGE.exists()/True/' "$sb/fake_tests/test_fake.py"
capture python3 "$R/tools/ci/sim_image_tests.py" "$sb/fake_tests"
expect_rc "sim_image_tests.py fails when it finds no image test" 1

cd "$real_root" && rm -rf firmware/build-ci-selftest
echo "$passed passed, $failed failed"
if [ "$failed" -ne 0 ]; then
    annotate error "test_ci.sh: $failed of $((passed + failed)) checks failed (see the log)"
    exit 1
fi
