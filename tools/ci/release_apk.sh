#!/usr/bin/env bash
# The release-signed APK, as OUT_DIR/drillbalancer-<VERSION>.apk.
#
#   tools/ci/release_apk.sh OUT_DIR
#
# The key comes from the environment: DB_RELEASE_KEYSTORE_B64 (CI) or
# DB_RELEASE_KEYSTORE (a path), and DB_RELEASE_STORE_PASSWORD,
# DB_RELEASE_KEY_ALIAS and DB_RELEASE_KEY_PASSWORD. Nothing here may print them:
# no set -x, and Gradle's configuration and build caches stay off, which would
# keep the passwords. Needs the Android SDK as tools/ci/app.sh does.
set -euo pipefail
. "$(dirname "$0")/common.sh"

out=$(abs_path "${1:?usage: release_apk.sh OUT_DIR}")
for name in DB_RELEASE_STORE_PASSWORD DB_RELEASE_KEY_ALIAS DB_RELEASE_KEY_PASSWORD; do
    if [ -z "${!name:-}" ]; then
        annotate error "$name is not set"
        exit 1
    fi
done

if [ -n "${DB_RELEASE_KEYSTORE_B64:-}" ]; then
    keystore=$(mktemp)
    trap 'rm -f "$keystore"' EXIT
    base64 -d <<< "$DB_RELEASE_KEYSTORE_B64" > "$keystore"
    export DB_RELEASE_KEYSTORE=$keystore
elif [ -n "${DB_RELEASE_KEYSTORE:-}" ]; then
    DB_RELEASE_KEYSTORE=$(abs_path "$DB_RELEASE_KEYSTORE")
    export DB_RELEASE_KEYSTORE
else
    annotate error "neither DB_RELEASE_KEYSTORE_B64 nor DB_RELEASE_KEYSTORE is set"
    exit 1
fi
if [ ! -s "$DB_RELEASE_KEYSTORE" ]; then
    annotate error "no keystore at $DB_RELEASE_KEYSTORE"
    exit 1
fi

cd "$ROOT/app"
./gradlew --no-configuration-cache --no-build-cache assembleRelease

sdk=${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties)}
apk=build/outputs/apk/release/DrillBalancer-release.apk
apksigner=$(printf '%s\n' "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
"$apksigner" verify --print-certs "$apk"

mkdir -p "$out"
cp "$apk" "$out/drillbalancer-$(tr -d '[:space:]' < "$ROOT/VERSION").apk"
