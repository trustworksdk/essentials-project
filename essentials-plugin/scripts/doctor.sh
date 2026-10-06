#!/usr/bin/env bash
# doctor.sh — which of the tools the essentials plugin's commands run are on this machine, and what
# each missing one costs. Backs /essentials:doctor and the preflights of /essentials:init,
# /essentials:review, /essentials:slice-check and /essentials:slice-map.
#
# Usage:
#   doctor.sh [--for init|review|slice|docs|all] [--json]
#   doctor.sh --help
#
# Bash, not Python: the first thing it has to detect is a missing or too-old python3. It runs on
# macOS's bash 3.2 and BSD userland as well as on Linux, so it uses no associative arrays and no
# external tool besides the ones it probes: parsing is done with bash's own [[ =~ ]].
#
# Profiles — what each one checks, from what the commands actually run:
#   init    /essentials:init: python3 (init-render.py, stack-lint.py); the JDK, Maven, Docker and npm of
#           its post-render hooks and smoke build (Steps 12 and 13.7)
#   review  /essentials:review: python3 (review-scan.py, stack-lint.py); uv for slice-lint.py and
#           slice-source.py; git for its base-ref and --pr modes
#   slice   /essentials:add-slice, the slice skills, /essentials:slice-check, /essentials:slice-map:
#           python3 (render-slice.py, slice-law.py); uv for slice-lint.py, slice-source.py, slice-index.py
#   docs    the essentials-docs and essentials-change skills: search.sh (rg optional) and slice-law.py
#   all     every profile above, plus /essentials:upgrade's re-check (stack-lint.py, ./mvnw or mvn, Docker)
#           — the default
#
# Requirements, from README.md § Requirements and the commands:
#   bash      reported only; the script runs on it
#   python3   >= 3.11, hard for every profile: each runs a script
#   uv        runs the scripts that pin pyyaml / jsonschema (PEP 723). Without it python3 runs them where
#             those packages are installed, so the status is ok, fallback (both importable), partial
#             (pyyaml only: slice-lint.py cannot run) or missing (neither runs)
#   java      the major of the `java.version` row of references/stack/stack-pins.md, or newer; read from
#             that file on every run, never written here. $JAVA_HOME/bin/java first, as Maven uses it
#   maven     mvn; a ./mvnw in the working directory serves /essentials:upgrade, not init's wrapper hook
#   docker    the binary and a running daemon (`docker info`)
#   npm       only for a frontend
#   git       /essentials:review's base-ref and --pr modes; without it only <path> mode works
#   rg        search.sh falls back to grep -R
#
# Status values: ok, missing, too-old, not-running (docker: binary, no daemon), fallback, partial,
# unknown (found, but its version could not be read or the pin could not be parsed).
# Effect values of an impact: stop (the command stops), not-run (a gate or step does not run),
# compile-only, skipped (a hook or a check is skipped), fallback (works, differently).
# A requirement is blocking when its status is not ok and one of its impacts for the profile is a stop.
#
# Plain output: a header, one line per requirement — STATUS NAME FOUND NEED and what degrades without
# it — then a verdict line.
#
# --json writes one object, schema 1 (new fields and requirements may be added; none is renamed or removed):
#   { "schema": 1, "profile": "all", "os": "<bash $OSTYPE>", "ok": true|false,
#     "blocking": ["<name>", ...],
#     "requirements": [ { "name": "python3", "status": "<status>", "found": "<version or what was found>"|null,
#                         "required": "<constraint>"|null, "blocking": true|false,
#                         "impacts": [ { "profile": "init|review|slice|docs|upgrade",
#                                        "effect": "<effect>", "detail": "<sentence>" } ] } ] }
#   "impacts" is empty for a requirement whose status is ok.
#
# Exit: 0 nothing blocking for the profile; 1 a blocking requirement (the verdict names it); 2 usage.
# Read-only: it probes, it never installs or changes anything.

set -o pipefail

# usage: this header, from its second line to the "Read-only" line, without the comment marks. Bash only, like the
# rest: --help must work on the PATH that lacks the tools this script is looking for.
usage() {
    local line n=0
    while IFS= read -r line; do
        n=$((n + 1))
        [ "$n" -ge 2 ] || continue
        line=${line#\#}
        printf '%s\n' "${line# }"
        case $line in " Read-only"*) break ;; esac
    done <"$SELF"
}

case ${BASH_SOURCE[0]} in
    */*) SELF_DIR=${BASH_SOURCE[0]%/*} ;;
    *) SELF_DIR=. ;;
esac
SELF="$SELF_DIR/doctor.sh"
PLUGIN_ROOT=$(cd "$SELF_DIR/.." && pwd)
PINS="$PLUGIN_ROOT/references/stack/stack-pins.md"

profile=all
json=false
while [ $# -gt 0 ]; do
    case $1 in
        --for)
            [ $# -ge 2 ] || { echo "doctor.sh: --for needs a profile: init, review, slice, docs or all" >&2; exit 2; }
            profile=$2
            shift 2 ;;
        --for=*) profile=${1#--for=}; shift ;;
        --json) json=true; shift ;;
        -h | --help) usage; exit 0 ;;
        *) echo "doctor.sh: unknown argument '$1' (usage: doctor.sh [--for init|review|slice|docs|all] [--json])" >&2; exit 2 ;;
    esac
done

case $profile in
    init | review | slice | docs) tags=$profile ;;
    all) tags="init review slice docs upgrade" ;;
    *) echo "doctor.sh: unknown profile '$profile' (init, review, slice, docs or all)" >&2; exit 2 ;;
esac

# in_profile TAG...: true when any TAG belongs to the selected profile.
in_profile() {
    local t
    for t in "$@"; do
        case " $tags " in *" $t "*) return 0 ;; esac
    done
    return 1
}

have() { command -v "$1" >/dev/null 2>&1; }

json_str() {
    if [ -z "${1+x}" ] || [ "$1" = "<null>" ]; then
        printf 'null'
        return
    fi
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\"/\\\"}
    s=${s//$'\t'/ }
    s=${s//$'\n'/ }
    s=${s//$'\r'/}
    printf '"%s"' "$s"
}

# ---- probes ---------------------------------------------------------------------------------------------------------

py_status=missing py_found="<null>" py_yaml=false py_jsonschema=false
if have python3; then
    v=$(python3 -c 'import sys; print("%d.%d.%d" % tuple(sys.version_info[:3]))' 2>/dev/null)
    re='^([0-9]+)\.([0-9]+)'
    if [[ $v =~ $re ]]; then
        py_found=$v
        if [ "${BASH_REMATCH[1]}" -gt 3 ] || { [ "${BASH_REMATCH[1]}" -eq 3 ] && [ "${BASH_REMATCH[2]}" -ge 11 ]; }; then
            py_status=ok
            python3 -c 'import yaml' >/dev/null 2>&1 && py_yaml=true
            python3 -c 'import jsonschema' >/dev/null 2>&1 && py_jsonschema=true
        else
            py_status=too-old
        fi
    else
        py_status=unknown py_found="python3 (version unreadable)"
    fi
fi

uv_found="<null>"
if have uv; then
    v=$(uv --version 2>/dev/null)
    re='^uv ([^[:space:]]+)'
    if [[ $v =~ $re ]]; then uv_found=${BASH_REMATCH[1]}; else uv_found="uv"; fi
    uv_status=ok
elif $py_yaml && $py_jsonschema; then
    uv_status=fallback uv_found="no uv; python3 has pyyaml and jsonschema"
elif $py_yaml; then
    uv_status=partial uv_found="no uv; python3 has pyyaml, not jsonschema"
else
    uv_status=missing
    [ "$py_status" = ok ] && uv_found="no uv; python3 has neither pyyaml nor jsonschema"
fi

java_pin=""
if [ -r "$PINS" ]; then
    # shellcheck disable=SC2016 # the backticks are literal Markdown
    re='^[|][[:space:]]*`java\.version`[[:space:]]*[|][[:space:]]*[*]*([0-9]+)'
    while IFS= read -r line || [ -n "$line" ]; do
        if [[ $line =~ $re ]]; then
            java_pin=${BASH_REMATCH[1]}
            break
        fi
    done <"$PINS"
fi
java_required="<null>"
[ -n "$java_pin" ] && java_required=">= $java_pin"

java_status=missing java_found="<null>" java_major=""
java_bin=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_bin=$JAVA_HOME/bin/java
elif have java; then
    java_bin=java
fi
if [ -n "$java_bin" ]; then
    out=$("$java_bin" -version 2>&1)
    re='version "([0-9]+)(\.([0-9]+))?'
    if [[ $out =~ $re ]]; then
        java_major=${BASH_REMATCH[1]}
        [ "$java_major" = 1 ] && java_major=${BASH_REMATCH[3]:-1}
        v=${out#*version \"}
        java_found=${v%%\"*}
        [ "$java_bin" = java ] || java_found="$java_found (\$JAVA_HOME)"
        if [ -z "$java_pin" ]; then
            java_status=unknown
        elif [ "$java_major" -ge "$java_pin" ]; then
            java_status=ok
        else
            java_status=too-old
        fi
    else
        # macOS ships a /usr/bin/java stub that only says no runtime is installed.
        java_found="java on PATH, but no JDK answers -version"
    fi
fi

maven_status=missing maven_found="<null>"
if have mvn; then
    maven_status=ok
    v=$(mvn -v 2>/dev/null)
    re='Apache Maven ([^[:space:]]+)'
    if [[ $v =~ $re ]]; then maven_found=${BASH_REMATCH[1]}; else maven_found="mvn (version unreadable)"; fi
elif [ -x ./mvnw ]; then
    maven_status=partial maven_found="./mvnw only, no mvn on PATH"
fi

docker_status=missing docker_found="<null>"
if have docker; then
    v=$(docker --version 2>/dev/null)
    re='version ([^[:space:],]+)'
    if [[ $v =~ $re ]]; then docker_found=${BASH_REMATCH[1]}; else docker_found="docker"; fi
    if docker info >/dev/null 2>&1; then
        docker_status=ok
    else
        docker_status=not-running docker_found="$docker_found (daemon not reachable)"
    fi
fi

npm_status=missing npm_found="<null>"
if have npm; then
    npm_status=ok
    v=$(npm --version 2>/dev/null)
    npm_found=${v:-npm}
fi

git_status=missing git_found="<null>"
if have git; then
    git_status=ok
    v=$(git --version 2>/dev/null)
    re='^git version ([^[:space:]]+)'
    if [[ $v =~ $re ]]; then git_found=${BASH_REMATCH[1]}; else git_found="git"; fi
fi

rg_status=missing rg_found="<null>"
if have rg; then
    rg_status=ok
    v=$(rg --version 2>/dev/null)
    re='^ripgrep ([^[:space:]]+)'
    if [[ $v =~ $re ]]; then rg_found=${BASH_REMATCH[1]}; else rg_found="rg"; fi
fi

bash_found=${BASH_VERSION%%(*}

# ---- impacts --------------------------------------------------------------------------------------------------------
# impacts NAME STATUS -> lines "TAG|EFFECT|DETAIL", every profile; the caller keeps the selected ones. Each line
# restates what the named command does when the tool is absent — change it with that command, never on its own.

impacts() {
    local name=$1 status=$2
    [ "$status" = ok ] && return 0
    case $name in
        python3)
            echo "init|stop|/essentials:init stops at Step 0: init-render.py is the only way it writes a project"
            echo "review|stop|/essentials:review stops at Step 0: no deterministic check can run, and it does not review by eye"
            echo "slice|stop|/essentials:add-slice and the slice skills cannot render a slice (render-slice.py) or load the slice law (slice-law.py)"
            echo "docs|stop|the essentials-docs and essentials-change skills cannot load the slice law (slice-law.py) before a slice change"
            echo "upgrade|not-run|/essentials:upgrade cannot run stack-lint.py: Group C is reported not run" ;;
        uv)
            case $status in
                fallback)
                    echo "review|fallback|slice-lint.py and slice-source.py run with python3 and the pyyaml and jsonschema already installed, not the pinned versions"
                    echo "slice|fallback|slice-lint.py, slice-source.py and slice-index.py run with python3 and the pyyaml and jsonschema already installed, not the pinned versions" ;;
                partial)
                    echo "review|not-run|slice-lint.py cannot run (no jsonschema): /essentials:review lists its slice-law gates under Not run; slice-source.py runs with python3"
                    echo "slice|not-run|slice-lint.py cannot run (no jsonschema): /essentials:slice-check reports gates 1, 3, 4 and 14 tier not run; slice-source.py and slice-index.py run with python3" ;;
                *)
                    echo "review|not-run|slice-lint.py and slice-source.py cannot run (no pyyaml): /essentials:review lists the slice-law gates they cover under Not run"
                    echo "slice|not-run|/essentials:slice-check: slice-lint.py and slice-source.py cannot run, so gates 1, 3, 4 and 14 tier and the script halves of 6, 11(b) and 14 are not run"
                    echo "slice|stop|/essentials:slice-map stops: slice-index.py cannot run without pyyaml, and the map is never built by hand" ;;
            esac ;;
        java)
            echo "init|not-run|/essentials:init's smoke build (Step 13.7) cannot build the rendered project, which targets the pinned Java release: its result is failed"
            echo "upgrade|not-run|/essentials:upgrade's context-start re-check after a dependency or config fix cannot build the project" ;;
        maven)
            echo "init|skipped|/essentials:init skips the maven-wrapper hook (no mvnw in the new project) and its smoke build cannot run"
            [ "$status" = partial ] ||
                echo "upgrade|not-run|/essentials:upgrade's context-start re-check needs ./mvnw or mvn" ;;
        docker)
            echo "init|compile-only|/essentials:init's smoke build is compile-only (compiled-only): the Spring context is never started"
            echo "upgrade|compile-only|/essentials:upgrade's re-check runs test-compile only: the context start is unverified" ;;
        npm)
            echo "init|skipped|with a frontend, /essentials:init skips the frontend-lockfile hook and leaves the frontend unchecked; an embedded build needs -Pskip-frontend until a package-lock.json exists" ;;
        git)
            echo "review|not-run|/essentials:review: only <path> mode works outside git; a base ref and --pr cannot be resolved" ;;
        rg)
            echo "docs|fallback|search.sh falls back to grep -R: the same matches, slower" ;;
    esac
}

# ---- report ---------------------------------------------------------------------------------------------------------

plain_rows=""
json_rows=""
blocking_names=""
degraded=0

# row NAME STATUS FOUND REQUIRED TAG...: report one requirement when any TAG is in the profile.
row() {
    local name=$1 status=$2 found=$3 required=$4
    shift 4
    in_profile "$@" || return 0
    local line tag effect detail blocking=false impacts_json="" impacts_plain="" label shown_found shown_req
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        tag=${line%%|*}
        line=${line#*|}
        effect=${line%%|*}
        detail=${line#*|}
        in_profile "$tag" || continue
        [ "$effect" = stop ] && blocking=true
        [ -z "$impacts_json" ] || impacts_json="$impacts_json,"
        impacts_json="$impacts_json{\"profile\":$(json_str "$tag"),\"effect\":$(json_str "$effect"),\"detail\":$(json_str "$detail")}"
        case $effect in
            stop) label=STOP ;;
            not-run) label="NOT RUN" ;;
            compile-only) label=COMPILE-ONLY ;;
            skipped) label=SKIPPED ;;
            fallback) label=FALLBACK ;;
            *) label=$effect ;;
        esac
        [ -z "$impacts_plain" ] || impacts_plain="$impacts_plain | "
        impacts_plain="$impacts_plain$label: $detail"
    done <<EOF
$(impacts "$name" "$status")
EOF
    if $blocking; then
        blocking_names="$blocking_names $name"
    elif [ "$status" != ok ]; then
        degraded=$((degraded + 1))
    fi
    [ -z "$json_rows" ] || json_rows="$json_rows,"
    json_rows="$json_rows{\"name\":$(json_str "$name"),\"status\":$(json_str "$status"),\"found\":$(json_str "$found"),\"required\":$(json_str "$required"),\"blocking\":$blocking,\"impacts\":[${impacts_json}]}"
    case $status in
        ok) label=ok ;;
        missing) label=MISSING ;;
        too-old) label="TOO OLD" ;;
        not-running) label="NOT RUNNING" ;;
        fallback) label=FALLBACK ;;
        partial) label=PARTIAL ;;
        *) label=UNKNOWN ;;
    esac
    shown_found=$found
    [ "$shown_found" = "<null>" ] && shown_found="-"
    shown_req=$required
    [ "$shown_req" = "<null>" ] && shown_req="any"
    if [ -n "$impacts_plain" ]; then
        plain_rows="$plain_rows$(printf '%-12s %-8s %s (need %s) — %s' "$label" "$name" "$shown_found" "$shown_req" "$impacts_plain")"$'\n'
    else
        plain_rows="$plain_rows$(printf '%-12s %-8s %s (need %s)' "$label" "$name" "$shown_found" "$shown_req")"$'\n'
    fi
}

row bash "ok" "$bash_found" "<null>" init review slice docs upgrade
row python3 "$py_status" "$py_found" ">= 3.11" init review slice docs upgrade
row uv "$uv_status" "$uv_found" "<null>" review slice
row java "$java_status" "$java_found" "$java_required" init upgrade
row maven "$maven_status" "$maven_found" "<null>" init upgrade
row docker "$docker_status" "$docker_found" "running daemon" init upgrade
row npm "$npm_status" "$npm_found" "<null>" init
row git "$git_status" "$git_found" "<null>" review
row rg "$rg_status" "$rg_found" "<null>" docs

blocking_names=${blocking_names# }
ok=true
[ -z "$blocking_names" ] || ok=false

if $json; then
    blocking_json=""
    for n in $blocking_names; do
        [ -z "$blocking_json" ] || blocking_json="$blocking_json,"
        blocking_json="$blocking_json$(json_str "$n")"
    done
    printf '{"schema":1,"profile":%s,"os":%s,"ok":%s,"blocking":[%s],"requirements":[%s]}\n' \
        "$(json_str "$profile")" "$(json_str "${OSTYPE:-unknown}")" "$ok" "$blocking_json" "$json_rows"
else
    printf 'essentials doctor — profile %s (%s)\n' "$profile" "${OSTYPE:-unknown}"
    printf '%s' "$plain_rows"
    if $ok; then
        printf 'OK: nothing missing stops a command in profile %s; %d requirement(s) degrade a step (above)\n' "$profile" "$degraded"
    else
        list=""
        for n in $blocking_names; do list="${list:+$list, }$n"; done
        printf 'BLOCKED: %s — a command in profile %s stops without it\n' "$list" "$profile"
    fi
fi

$ok || exit 1
exit 0
