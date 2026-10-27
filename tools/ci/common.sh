# Shared helpers for the CI job scripts in tools/ci (sourced, not run).
# Each job script runs the same way on a PC and in .github/workflows/ci.yml.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# IDF_VERSION, COMPONENT_MANAGER_VERSION
# shellcheck source=versions.sh
. "$ROOT/tools/ci/versions.sh"
PYTHON="${PYTHON:-python3}"

in_ci() { [ -n "${GITHUB_ACTIONS:-}" ]; }

group() { if in_ci; then echo "::group::$*"; else echo "== $*"; fi; }
endgroup() { if in_ci; then echo "::endgroup::"; fi; }

# annotate LEVEL MESSAGE [FILE [LINE]]: a GitHub annotation (notice, warning,
# error) in CI, a plain line on stderr otherwise. MESSAGE may span lines; FILE
# is relative to the repo root.
annotate() {
    local level=$1 msg=$2 file=${3:-} line=${4:-} props=""
    if in_ci; then
        if [ -n "$file" ]; then props="file=$(annotation_escape "$file" property)"; fi
        if [ -n "$line" ]; then props="${props:+$props,}line=$(annotation_escape "$line" property)"; fi
        echo "::$level${props:+ $props}::$(annotation_escape "$msg")"
    else
        echo "$level: ${file:+$file:}${line:+$line:}${file:+ }$msg" >&2
    fi
}

# annotation_escape TEXT [property]: TEXT for a workflow command, as the
# message or (with "property") as a property value such as file=.
annotation_escape() {
    local s=$1
    s=${s//'%'/'%25'}
    s=${s//$'\r'/'%0D'}
    s=${s//$'\n'/'%0A'}
    if [ "${2:-}" = property ]; then
        s=${s//':'/'%3A'}
        s=${s//','/'%2C'}
    fi
    printf '%s' "$s"
}

# abs_path PATH: PATH made absolute from the current directory (symlinks not
# resolved), so that a later cd does not change what it names.
abs_path() { realpath -m -s -- "$1"; }

# summary LINE...: appended to the job summary in CI, ignored otherwise.
summary() {
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$@" >> "$GITHUB_STEP_SUMMARY"; fi
}

# Puts idf.py, cmake and ninja on PATH (export.sh of IDF_PATH, default
# ~/esp/v5.5.1/esp-idf) unless idf.py is there already.
idf_export() {
    if command -v idf.py >/dev/null 2>&1; then return 0; fi
    local idf="${IDF_PATH:-$HOME/esp/$IDF_VERSION/esp-idf}"
    if [ ! -f "$idf/export.sh" ]; then
        echo "ESP-IDF not found at $idf (set IDF_PATH)" >&2
        return 1
    fi
    set +u
    # shellcheck disable=SC1091
    . "$idf/export.sh" >/dev/null
    set -u
}

# component_manager_version: the idf-component-manager version in the IDF
# Python environment (empty when it is not installed).
component_manager_version() {
    python -m pip show idf-component-manager 2>/dev/null | sed -n 's/^Version: //p'
}

# component_manager_pin: in CI installs COMPONENT_MANAGER_VERSION into the
# IDF Python environment; on a PC only warns when another version is there.
component_manager_pin() {
    local have
    have=$(component_manager_version)
    if [ "$have" = "$COMPONENT_MANAGER_VERSION" ]; then return 0; fi
    if in_ci; then
        group "Component manager $have -> $COMPONENT_MANAGER_VERSION"
        python -m pip install -q --disable-pip-version-check "idf-component-manager==$COMPONENT_MANAGER_VERSION"
        endgroup
    else
        annotate warning "idf-component-manager $have here, the lock was written with $COMPONENT_MANAGER_VERSION"
    fi
}

# A container job runs as root on a workspace the runner user owns: let git
# (git describe in components/recorder, the lock check) read the checkout.
git_trust_checkout() {
    if in_ci; then git config --global --add safe.directory "$ROOT"; fi
}

# venv DIR REQUIREMENTS...: creates DIR with $PYTHON if it has no python yet,
# then installs the requirements files into it.
venv() {
    local dir=$1 req
    shift
    if [ ! -x "$dir/bin/python" ]; then "$PYTHON" -m venv "$dir"; fi
    for req in "$@"; do
        "$dir/bin/python" -m pip install -q --disable-pip-version-check -r "$req"
    done
}

# sim_core: the simulator's Python environment (firmware/tools/sim/.venv) and
# its C core (core/build/libdbsim.so), built here so that a build error fails
# the job and not as a test error.
sim_core() {
    local sim="$ROOT/firmware/tools/sim"
    group "Python environment"
    venv "$sim/.venv" "$sim/requirements.txt"
    endgroup
    group "Build libdbsim.so"
    (cd "$sim" && .venv/bin/python -c 'from dbsim import core; print(core.build_lib(quiet=False))')
    endgroup
}

# lock_versions: reads a dependencies.lock, writes "NAME VERSION" for each
# component in it. Not the idf entry: its version is the ESP-IDF patch
# release that wrote the lock, no component choice.
lock_versions() {
    awk '/^dependencies:/ { deps = 1; next }
         /^[^ ]/ { deps = 0 }
         deps && /^  [^ -]/ { name = $1; sub(/:$/, "", name) }
         deps && /^    version:/ && name != "idf" { print name, $2 }'
}

# components_check: after the component manager ran (reconfigure or build),
# reports its version and the resolved component versions, then checks that
# firmware/dependencies.lock is unchanged: fails when a component version
# differs, warns on any other rewrite (such as the idf entry's version from
# another ESP-IDF patch release) or when the lock is not committed.
components_check() {
    local lock=firmware/dependencies.lock cm resolved committed now
    group "Components (dependencies.lock)"
    # (grep fails without managed components; pipefail would end the script)
    resolved=$({ grep -H '^version:' "$ROOT"/firmware/managed_components/*/idf_component.yml 2>/dev/null || true; } \
        | sed -E 's#^.*/managed_components/([^/]+)/idf_component.yml:version: *#\1 #' | tr '\n' ' ')
    cm=$(component_manager_version)
    echo "component manager: $cm"
    echo "resolved: $resolved"
    if in_ci; then annotate notice "component manager $cm: $resolved"; fi
    if [ -z "$(git -C "$ROOT" ls-files -- "$lock")" ]; then
        annotate warning "$lock is not committed: the component versions are whatever the registry had today"
    elif ! git -C "$ROOT" diff --quiet -- "$lock"; then
        git -C "$ROOT" --no-pager diff -- "$lock"
        committed=$(git -C "$ROOT" show ":$lock" | lock_versions)
        now=$(lock_versions < "$ROOT/$lock")
        if [ "$committed" != "$now" ]; then
            annotate error "the component manager resolved other versions than $lock: $(diff <(echo "$committed") <(echo "$now") | sed -n 's/^< /lock: /p; s/^> /now: /p' | tr '\n' ' ')(resolved: $resolved)"
            endgroup
            return 1
        fi
        annotate warning "the component manager rewrote $lock without changing a component version (diff in the log)"
    else
        echo "$lock unchanged"
    fi
    endgroup
}

# compiler_warnings LOG BUILD_DIR: the compiler warnings in LOG about our own
# code (firmware/components, firmware/main), once each, as
# FILE<TAB>LINE<TAB>TEXT with FILE relative to the repo root. A relative path
# in LOG is taken from BUILD_DIR (where ninja runs the compiler) if it exists
# there, else from firmware/.
compiler_warnings() {
    local log=$1 build=$2 w path rest line text abs
    { grep -E '^[^ :][^:]*:[0-9]+:[0-9]+: warning: ' "$log" || true; } | sort -u | while IFS= read -r w; do
        path=${w%%:*}
        rest=${w#*:}
        line=${rest%%:*}
        text=${w#*: warning: }
        case $path in
            /*) abs=$path ;;
            *) if [ -e "$build/$path" ]; then abs=$build/$path; else abs=$ROOT/firmware/$path; fi ;;
        esac
        abs=$(realpath -m -s -- "$abs")
        case $abs in
            "$ROOT"/firmware/components/* | "$ROOT"/firmware/main/*)
                printf '%s\t%s\t%s\n' "${abs#"$ROOT"/}" "$line" "$text" ;;
        esac
    done | sort -u
}

# warning_annotations MAX: a warning annotation for each FILE<TAB>LINE<TAB>TEXT
# line on stdin, at most MAX of them: when there are more, the last one says
# how many more there are.
warning_annotations() {
    local max=$1 total shown row file line text
    local -a rows
    mapfile -t rows
    total=${#rows[@]}
    shown=$total
    if [ "$total" -gt "$max" ]; then shown=$((max - 1)); fi
    for row in "${rows[@]:0:shown}"; do
        IFS=$'\t' read -r file line text <<< "$row"
        annotate warning "$text" "$file" "$line"
    done
    if [ "$total" -gt "$shown" ]; then
        annotate warning "$((total - shown)) more compiler warnings in components/ and main/, listed in the log"
    fi
}
