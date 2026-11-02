#!/usr/bin/env bash
# Whether a tag may publish a release: it has to be vMAJOR.MINOR.PATCH of the
# VERSION file, with MINOR and PATCH below 100 (the app's versionCode). Prints
# the version; in CI also as the step output "version".
#
#   tools/ci/release_check.sh TAG
set -euo pipefail
. "$(dirname "$0")/common.sh"

tag=${1:?usage: release_check.sh TAG}
version=$(tr -d '[:space:]' < "$ROOT/VERSION")
if ! [[ $version =~ ^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$ ]]; then
    annotate error "VERSION is not MAJOR.MINOR.PATCH with parts below 100: $version"
    exit 1
fi
if [ "$tag" != "v$version" ]; then
    annotate error "tag $tag is not the VERSION file's $version"
    exit 1
fi
echo "$version"
if in_ci; then echo "version=$version" >> "$GITHUB_OUTPUT"; fi
