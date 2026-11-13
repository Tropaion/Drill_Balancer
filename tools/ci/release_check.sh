#!/usr/bin/env bash
# Whether a tag may publish a release: it has to be vMAJOR.MINOR.PATCH of the
# VERSION file, with MINOR and PATCH below 100 (the app's versionCode), and
# docs/releases/TAG.md has to hold its notes, which the app shows with the
# update. Prints the version; in CI also the step outputs "version" and
# "notes" (the notes file, relative to the repo).
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
notes="docs/releases/$tag.md"
# Comments show nowhere, on GitHub or in the app: notes of only comments are none.
if ! perl -0777 -e '$_ = <>; s/<!--.*?-->//gs; exit(/\S/ ? 0 : 1)' "$ROOT/$notes" 2> /dev/null; then
    annotate error "no release notes in $notes"
    exit 1
fi
echo "$version"
if in_ci; then printf 'version=%s\nnotes=%s\n' "$version" "$notes" >> "$GITHUB_OUTPUT"; fi
