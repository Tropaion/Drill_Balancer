#!/usr/bin/env bash
# The Android app's JVM and Robolectric tests, lint, and the screenshot tests
# against app/src/test/screenshots.
#
#   tools/ci/app.sh [GRADLE_ARGS...]
#
# Needs the Android SDK (platform android-37.0, build-tools 36.0.0) through
# app/local.properties or ANDROID_HOME, and a JDK 17+ to start Gradle (the
# daemon runs on JDK 21, see app/gradle/gradle-daemon-jvm.properties).
# --continue: a test failure still lets lint run, so one run reports both.
set -euo pipefail
. "$(dirname "$0")/common.sh"

cd "$ROOT/app"
status=0
./gradlew --continue testDebugUnitTest lintDebug verifyRoborazziDebug "$@" || status=$?

# Test counts from the JUnit XML (also when a task failed).
results=build/test-results/testDebugUnitTest
if [ -d "$results" ]; then
    counts=$(python3 - "$results" <<'EOF'
import sys, pathlib, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in pathlib.Path(sys.argv[1]).glob("*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
    e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print(f"{t} tests, {f} failures, {e} errors, {s} skipped")
EOF
)
    echo "App unit tests: $counts"
    summary "App unit tests: $counts"
fi
exit "$status"
