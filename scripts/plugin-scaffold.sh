#!/usr/bin/env bash
#
# Copyright 2021-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Builds what the essentials plugin generates, against this checkout's installed reactor: the nine /essentials:init
# golden cells, and the slice templates and fixtures overlaid on a rendered host project. Maintainer and CI tooling,
# not part of the plugin: it needs bash 4.4+, a JDK, Maven, Python 3 and, for `verify`, Docker (Testcontainers).
#
# Every project is rendered by essentials-plugin/scripts/init-render.py, so the build proves exactly what users get.
# Nothing is written inside the repository: projects go to a work directory outside it.
#
# Prerequisite: the reactor is installed in the Maven repository the builds use (the artifacts are not on Central):
#   mvn -B install -DskipTests -DskipDependencyCheck=true [-Dmaven.repo.local=DIR]
# A subset is enough (see essentials-plugin/tests/slice-compile/README.md).
#
# Usage:
#   scripts/plugin-scaffold.sh matrix [--cell ID]... [--list]
#       Each cell of essentials-plugin/tests/golden/init/cells.json (default: all nine): render, add the CI-only
#       overlay tests/init-overlay/<language>/, stack-lint the result, build with --goal (default verify), and after
#       `verify` check that contracts/openapi.json was regenerated with the overlay's /api/wiring/{id} endpoint and documents
#       its semantic id as a plain string.
#   scripts/plugin-scaffold.sh slices [--case NAME]... [--list]
#       Each case of essentials-plugin/tests/slice-compile/overlay.py (slice golden compositions, fixtures): render its
#       host, add its overlays, build with the case's default goal (`overlay.py list` shows it): verify for every
#       composition (the two-BC ones with the boot check) and fixture-worked-example; test-compile for fixture-multi-lane
#       and fixture-aggregate-lane, whose packages no host context would load.
#       After every `verify` (matrix and slices): no operationId in contracts/openapi.json may carry Kotlin's value-class
#       mangling ('-') or be the bare `on`.
#   scripts/plugin-scaffold.sh build-host --host DIR [--overlay DIR]... [--log FILE]
#       Build one rendered project: copy each overlay's src/ tree into DIR/backend/src/, refusing to overwrite any file
#       (an overlay never replaces generated wiring), then run Maven in DIR (default goal verify).
#
# Options (every subcommand):
#   --goal test-compile|verify   override every case's default goal (matrix and build-host: verify; slices: per case, above)
#   --compile-only               test-compile everything; no Docker needed (no context start, no spec check)
#   --work DIR                   work directory, outside the repository (default: $SCAFFOLD_WORK, else
#                                ${TMPDIR:-/tmp}/essentials-scaffold); each case gets DIR/<case>/, wiped first
#   --jobs N                     matrix/slices: run N cases at once (default 1; each verify starts a database container)
#   --essentials-version V       default: <revision> of the root pom.xml
#   --maven-repo DIR             passed as -Dmaven.repo.local=DIR (CI: the job-local repository the reactor went into)
# Environment: MVN (default mvn), SCAFFOLD_MAVEN_ARGS (extra Maven arguments, word-split).
#
# Exit codes: 0 every build green; 1 a render, stack-lint, build or spec check failed; 2 harness error (bad arguments,
# missing tool, work directory inside the repository, an overlay that would overwrite a host file).
# matrix/slices print one line per case (result, goal, wall-clock seconds, log path) and exit with the worst result.

# Run as `sh scripts/plugin-scaffold.sh …` the arrays and process substitution below are syntax errors; hand over to bash.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -uo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$script_dir/.." && pwd)
plugin=$repo/essentials-plugin
init_render=$plugin/scripts/init-render.py
stack_lint=$plugin/scripts/stack-lint.py
overlay_py=$plugin/tests/slice-compile/overlay.py
cells_json=$plugin/tests/golden/init/cells.json

mvn_cmd=${MVN:-mvn}
goal=""
compile_only=false
work=${SCAFFOLD_WORK:-${TMPDIR:-/tmp}/essentials-scaffold}
jobs=1
essentials_version=""
maven_repo=""
host=""
log=""
list=false
overlays=()
selected=()

die() {
    echo "plugin-scaffold: $*" >&2
    exit 2
}

usage() {
    sed -n '/^# Usage:/,/^# matrix\/slices print/p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -ge 1 ] || usage
command=$1
shift
case $command in
    matrix | slices | build-host) ;;
    -h | --help | help) usage ;;
    *) die "unknown subcommand '$command' (matrix | slices | build-host)" ;;
esac

while [ $# -gt 0 ]; do
    case $1 in
        --cell | --case) [ $# -ge 2 ] || die "$1 needs a value"; selected+=("$2"); shift 2 ;;
        --host) [ $# -ge 2 ] || die "--host needs a directory"; host=$2; shift 2 ;;
        --overlay) [ $# -ge 2 ] || die "--overlay needs a directory"; overlays+=("$2"); shift 2 ;;
        --log) [ $# -ge 2 ] || die "--log needs a file"; log=$2; shift 2 ;;
        --goal) [ $# -ge 2 ] || die "--goal needs a value"; goal=$2; shift 2 ;;
        --compile-only) compile_only=true; shift ;;
        --work) [ $# -ge 2 ] || die "--work needs a directory"; work=$2; shift 2 ;;
        --jobs) [ $# -ge 2 ] || die "--jobs needs a number"; jobs=$2; shift 2 ;;
        --essentials-version) [ $# -ge 2 ] || die "--essentials-version needs a value"; essentials_version=$2; shift 2 ;;
        --maven-repo) [ $# -ge 2 ] || die "--maven-repo needs a directory"; maven_repo=$2; shift 2 ;;
        --list) list=true; shift ;;
        -h | --help) usage ;;
        *) die "unknown option '$1'" ;;
    esac
done

case $goal in
    "" | test-compile | verify) ;;
    *) die "--goal must be test-compile or verify, not '$goal'" ;;
esac
case $jobs in
    '' | *[!0-9]* | 0) die "--jobs must be a positive number" ;;
esac
if $compile_only; then
    goal=test-compile
fi

command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH"

if [ -z "$essentials_version" ]; then
    essentials_version=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$repo/pom.xml" | head -n 1)
    [ -n "$essentials_version" ] || die "no <revision> in $repo/pom.xml; pass --essentials-version"
fi

# ---- build-host -------------------------------------------------------------------------------------------------

# Copies every overlay's src/ into $1/backend/src/. Checks every file of every overlay before copying any, so a
# refused overlay leaves the host untouched.
apply_overlays() {
    local project=$1
    shift
    local overlay rel target seen=""
    for overlay in "$@"; do
        [ -d "$overlay/src" ] || { echo "plugin-scaffold: overlay $overlay has no src/ directory" >&2; return 2; }
        while IFS= read -r rel; do
            target=$project/backend/src/$rel
            if [ -e "$target" ]; then
                echo "plugin-scaffold: overlay $overlay would overwrite backend/src/$rel" >&2
                return 2
            fi
            case $seen in
                *"|$rel|"*) echo "plugin-scaffold: two overlays both write backend/src/$rel" >&2; return 2 ;;
            esac
            seen="$seen|$rel|"
        done < <(cd "$overlay/src" && find . -type f | sed 's:^\./::')
    done
    for overlay in "$@"; do
        (cd "$overlay/src" && find . -type f) | sed 's:^\./::' | while IFS= read -r rel; do
            mkdir -p "$(dirname "$project/backend/src/$rel")" && cp "$overlay/src/$rel" "$project/backend/src/$rel"
        done || return 2
    done
    return 0
}

# build_host PROJECT GOAL LOG OVERLAY... -> 0 green, 1 build failed, 2 harness error
build_host() {
    local project=$1 build_goal=$2 build_log=$3
    shift 3
    [ -f "$project/pom.xml" ] && [ -f "$project/backend/pom.xml" ] ||
        { echo "plugin-scaffold: $project is not a rendered project (no pom.xml and backend/pom.xml)" >&2; return 2; }
    command -v "$mvn_cmd" >/dev/null 2>&1 || { echo "plugin-scaffold: '$mvn_cmd' is not on PATH" >&2; return 2; }
    apply_overlays "$project" "$@" || return 2

    local args=(-B -ntp "-Dessentials.version=$essentials_version")
    if grep -q '<id>skip-frontend</id>' "$project/pom.xml" "$project/backend/pom.xml"; then
        args+=(-Pskip-frontend)
    fi
    [ -z "$maven_repo" ] || args+=("-Dmaven.repo.local=$maven_repo")
    # shellcheck disable=SC2206 # word-splitting the extra arguments is the point
    [ -z "${SCAFFOLD_MAVEN_ARGS:-}" ] || args+=(${SCAFFOLD_MAVEN_ARGS})
    args+=("$build_goal")

    if [ -n "$build_log" ]; then
        echo "$mvn_cmd ${args[*]}  (in $project)" >"$build_log"
        (cd "$project" && "$mvn_cmd" "${args[@]}") >>"$build_log" 2>&1
    else
        (cd "$project" && "$mvn_cmd" "${args[@]}")
    fi
    [ $? -eq 0 ] || return 1
    return 0
}

if [ "$command" = build-host ]; then
    [ -n "$host" ] || die "build-host needs --host DIR"
    [ -d "$host" ] || die "--host $host is not a directory"
    build_host "$(cd "$host" && pwd)" "${goal:-verify}" "$log" "${overlays[@]}"
    status=$?
    if [ $status -eq 1 ] && [ -n "$log" ]; then
        tail -n 60 "$log" >&2
    fi
    exit $status
fi

# ---- matrix / slices --------------------------------------------------------------------------------------------

work=$(python3 -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$work")
case "$work/" in
    "$(cd "$repo" && pwd -P)/"*) die "work directory $work is inside the repository; pass --work outside it" ;;
    //) die "refusing / as the work directory" ;;
esac
mkdir -p "$work" || die "cannot create work directory $work"

all_cases() {
    if [ "$command" = matrix ]; then
        python3 -c 'import json, sys; print("\n".join(json.load(open(sys.argv[1]))["cells"]))' "$cells_json"
    else
        python3 "$overlay_py" list 2>/dev/null | cut -d' ' -f1
    fi
}

known=$(all_cases) || die "cannot list the ${command} cases"
if $list; then
    echo "$known"
    exit 0
fi
if [ ${#selected[@]} -eq 0 ]; then
    mapfile -t selected <<<"$known"
fi
for name in "${selected[@]}"; do
    printf '%s\n' "$known" | grep -qx -- "$name" || die "unknown ${command} case '$name'; --list shows them"
done

# run_case NAME -> writes "<status> <result> <goal> <seconds>" to $work/NAME/result.
run_case() {
    local name=$1 dir=$work/$1 started case_goal status=0 result=green
    rm -rf "$dir" && mkdir -p "$dir" || return 2
    started=$(date +%s)
    local project=$dir/project log_file=$dir/build.log
    local case_overlays=()

    if [ "$command" = matrix ]; then
        case_goal=${goal:-verify}
        python3 - "$cells_json" "$name" "$dir/answers.json" <<'PY' || { echo "2 harness-error $case_goal 0" >"$dir/result"; return; }
import json, sys
cells = json.load(open(sys.argv[1]))
answers = {**cells["defaults"], **cells["cells"][sys.argv[2]]}
json.dump(answers, open(sys.argv[3], "w"), indent=2)
PY
        local language db web frontend
        read -r language db web frontend < <(python3 -c 'import json, sys; a = json.load(open(sys.argv[1])); print(a["language"], a["db"], a["web"], a["frontend"])' "$dir/answers.json")
        if ! python3 "$init_render" --answers "$dir/answers.json" --out "$project" >"$dir/render.log" 2>&1; then
            status=1 result=render-failed
        elif ! python3 "$stack_lint" "$project" --language "$language" --db "$db" --web "$web" --frontend "$frontend" \
                --quiet >"$dir/stack-lint.log" 2>&1; then
            status=1 result=stack-lint-failed
        else
            case_overlays+=("$plugin/tests/init-overlay/$language")
        fi
    else
        local host_id
        host_id=$(python3 "$overlay_py" host "$name") || { echo "2 harness-error - 0" >"$dir/result"; return; }
        case_goal=${goal:-$(python3 "$overlay_py" goal "$name")}
        if ! python3 "$init_render" --host "$host_id" --out "$project" >"$dir/render.log" 2>&1; then
            status=1 result=render-failed
        else
            mapfile -t case_overlays < <(python3 "$overlay_py" render "$name" --out "$dir/overlays" 2>>"$dir/render.log")
            [ ${#case_overlays[@]} -gt 0 ] || status=2 result=harness-error
        fi
    fi

    if [ $status -eq 0 ]; then
        build_host "$project" "$case_goal" "$log_file" "${case_overlays[@]}" 2>>"$log_file"
        case $? in
            0) ;;
            1) status=1 result=build-failed ;;
            *) status=2 result=harness-error ;;
        esac
    fi
    if [ $status -eq 0 ] && [ "$command" = matrix ] && [ "$case_goal" = verify ]; then
        if ! grep -q '"/api/wiring/{id}"' "$project/contracts/openapi.json" 2>/dev/null; then
            status=1 result=spec-not-regenerated
        # The overlay's semantic id must document as a plain string (types-spring-web's SingleValueTypeModelConverter,
        # registered by config/EssentialsWebConfig): without it Java shows an object and Kotlin a mangled `id-…` key.
        elif ! python3 - "$project/contracts/openapi.json" >>"$log_file" 2>&1 <<'PY'; then
import json, sys
schema = json.load(open(sys.argv[1]))["components"]["schemas"]["ProbeDocument"]["properties"]
plain = {"type": "string"}
problems = [k for k in schema if not k.isidentifier()]
if schema.get("id") != plain or schema.get("related", {}).get("items") != plain or problems:
    sys.exit(f"spec check: ProbeDocument properties are {json.dumps(schema)}; expected id and related[] as plain strings")
PY
            status=1 result=spec-schema-wrong
        fi
    fi
    # Every verify run regenerates contracts/openapi.json. Kotlin mangles the JVM name of a method taking or returning a
    # value class (`placeOrder-AbCd12`), and springdoc takes the operationId from that name, so the generated client's
    # function names drift. A '-' never occurs in an unmangled one.
    if [ $status -eq 0 ] && [ "$case_goal" = verify ] &&
        ! python3 - "$project/contracts/openapi.json" >>"$log_file" 2>&1 <<'PY'; then
import json, sys
spec = json.load(open(sys.argv[1]))
ids = [op["operationId"] for item in spec.get("paths", {}).values() for op in item.values()
       if isinstance(op, dict) and "operationId" in op]
mangled = [i for i in ids if "-" in i]
if mangled:
    sys.exit(f"spec check: mangled operationId(s) {mangled}; add @Operation(operationId = ...) to the handler")
if "on" in ids:
    sys.exit("spec check: an operationId is the bare 'on'; name the handler on<Event> (it becomes the client function name)")
PY
        status=1 result=spec-operation-id-mangled
    fi
    echo "$status $result $case_goal $(($(date +%s) - started))" >"$dir/result"
}

echo "plugin-scaffold $command: ${#selected[@]} case(s), essentials.version=$essentials_version, work $work, jobs $jobs"
running=0
for name in "${selected[@]}"; do
    if [ "$jobs" -eq 1 ]; then
        run_case "$name"
        read -r _ result case_goal seconds <"$work/$name/result"
        printf '  %-40s %-22s %-13s %5ss\n' "$name" "$result" "$case_goal" "$seconds"
    else
        run_case "$name" &
        running=$((running + 1))
        if [ $running -ge "$jobs" ]; then
            wait -n
            running=$((running - 1))
        fi
    fi
done
wait

worst=0
echo "plugin-scaffold $command summary:"
for name in "${selected[@]}"; do
    if [ -f "$work/$name/result" ]; then
        read -r status result case_goal seconds <"$work/$name/result"
    else
        status=2 result=no-result case_goal=- seconds=0
    fi
    printf '  %-40s %-22s %-13s %5ss  %s\n' "$name" "$result" "$case_goal" "$seconds" "$work/$name/"
    [ "$status" -le "$worst" ] || worst=$status
done
exit $worst
