#!/usr/bin/env bash
# Fetches the parts of the ESP-IDF tree that the PC builds use (Unity, cJSON,
# mbedtls) into DEST, laid out as in ESP-IDF, so that IDF_PATH=DEST works for
# firmware/test_host and the simulator's C core without the full IDF and its
# toolchains. Each part is the submodule commit of the ESP-IDF release tag.
#
#   tools/ci/idf_subset.sh DEST      # then: export IDF_PATH=DEST
set -euo pipefail
. "$(dirname "$0")/common.sh"

dest=${1:?usage: idf_subset.sh DEST}
done_mark="$dest/.subset-$IDF_VERSION"
if [ -f "$done_mark" ]; then
    echo "ESP-IDF $IDF_VERSION subset already in $dest"
    exit 0
fi

meta="$dest/.idf-meta"
if [ ! -d "$meta/.git" ]; then
    # Trees only: enough for .gitmodules and the submodule commits.
    git clone --quiet --depth 1 --branch "$IDF_VERSION" --filter=blob:none --no-checkout \
        https://github.com/espressif/esp-idf.git "$meta"
fi
git -C "$meta" show HEAD:.gitmodules > "$dest/.gitmodules"

for path in components/unity/unity components/json/cJSON components/mbedtls/mbedtls; do
    sha=$(git -C "$meta" ls-tree HEAD "$path" | awk '$2 == "commit" { print $3 }')
    url=$(git config -f "$dest/.gitmodules" "submodule.$path.url")
    case $url in
        ../../*) url="https://github.com/${url#../../}" ;;
    esac
    if [ -z "$sha" ] || [ -z "$url" ]; then
        echo "no submodule $path in ESP-IDF $IDF_VERSION" >&2
        exit 1
    fi
    echo "$path: $url @ $sha"
    git init --quiet "$dest/$path"
    git -C "$dest/$path" fetch --quiet --depth 1 "$url" "$sha"
    git -C "$dest/$path" -c advice.detachedHead=false checkout --quiet FETCH_HEAD
done

touch "$done_mark"
echo "ESP-IDF $IDF_VERSION subset in $dest"
