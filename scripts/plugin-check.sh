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
# The one list of the essentials plugin's checks. Every step of the `plugin-docs` job in .github/workflows/maven.yml is
# a `step` here and runs the same commands with the same arguments and environment; CI calls this script one step at a
# time, so a check is added here and gets one CI step line. Maintainer tooling, not part of the plugin: it needs
# bash 4.4+, git and uv (every Python script runs through `uv run` on UV_PYTHON, default 3.11, the scripts' declared
# minimum; uv fetches that interpreter and the PEP 723 pins when absent). `scaffold` also needs a JDK, Maven and
# Docker; `evals` and `release` need Claude Code (see essentials-plugin/evals/README.md).
#
# Usage:
#   scripts/plugin-check.sh list
#       Every step name with a one-line description, in CI order.
#   scripts/plugin-check.sh step NAME... [--base REF] [--chrome PATH]
#       Run the named steps exactly as the plugin-docs job does. Under GitHub Actions (GITHUB_ACTIONS=true) a failure
#       prints the job's ::error:: annotation, locally plain text. One step exits with its own status; several exit
#       1 if any failed, else 3 if any was skipped.
#   scripts/plugin-check.sh quick [--base REF] [--chrome PATH]
#       Every step in CI order, continuing past failures, then a PASS/FAIL/SKIPPED table; exit 1 if any failed.
#       render-check.py's exit 3 (no Chrome, nothing checked; the arm64 devcontainer) is SKIPPED, never PASS, and
#       does not fail a local run; under GitHub Actions it is a failure, as in the job.
#   scripts/plugin-check.sh manual
#       The checks no CI step runs (counts and greps over the plugin): PASS/FAIL where the expectation is mechanical,
#       READ where the output has to be compared by eye. Exit 1 if any failed.
#   scripts/plugin-check.sh scaffold [matrix|slices|all] [--no-install] [--force] [--dry-run] [plugin-scaffold args]
#       The scaffold builds the `scaffold ·` CI jobs run (default: all, i.e. matrix then slices). First the reactor
#       install `mvn clean install -DskipTests -DskipDependencyCheck=true` from the repository root (--no-install skips
#       it), then scripts/plugin-scaffold.sh with the remaining arguments (--cell/--case, --jobs, --work, ...).
#       Refuses when mvn, java or Docker is missing (Docker not needed with --compile-only) and, before the install,
#       when another Maven process is running (--force overrides): two builds in one target/ corrupt each other.
#   scripts/plugin-check.sh evals [--changed [BASE]] [--case GLOB] [--runs N] [--dry-run] [-- claude args]
#       evals/build.py --check, then `claude plugin eval` with the flags of essentials-plugin/evals/README.md § Run it.
#       --changed: one run per eval case whose directory differs from BASE (default: merge base with origin/main),
#       because --case takes one glob. --case: one glob, the last one given wins (as in claude plugin eval).
#       Costs real tokens; --dry-run prints the commands, the cases and the README's cost note instead.
#   scripts/plugin-check.sh changed [BASE] [--verbose]
#       Which of the above the change since BASE (default: merge base with origin/main) needs, and why: every changed
#       path mapped to the essentials-plugin/CLAUDE.md companion row (or scaffold-job input) it triggers, then the
#       commands to run. Committed, staged, unstaged and untracked changes all count. Deterministic; runs nothing.
#   scripts/plugin-check.sh release [--base REF] [--chrome PATH] [--no-install] [--force] [--dry-run]
#       quick, manual, scaffold all, evals over every case, `claude plugin validate` for the plugin and the marketplace,
#       and CHANGELOG.md's top heading against plugin.json's version and the essentials.version pin. Continues past
#       failures; a SKIPPED render-check fails a release (it needs a Chrome). Then prints what no script can check.
#
# --base REF is what the ess-ids and plugin-version steps compare with (also PLUGIN_CHECK_BASE). CI passes HEAD^1, the
# base side of a pull request's merge commit or the previous commit on a push; maven.yml says so on those two lines,
# and under GitHub Actions neither step runs without it. Locally the defaults are ess-ids: HEAD (the committed
# catalogue); plugin-version: the merge base with origin/main, since one version move covers a whole batch landed on
# main. --chrome PATH (also PLUGIN_CHECK_CHROME) is handed to render-check.py.
#
# Exit codes: 0 green; 1 a check failed; 2 usage or harness error (unknown step, missing tool); `step render-check`
# alone exits 3 when it skipped.

# Run as `sh scripts/plugin-check.sh …` the arrays below are syntax errors; hand over to bash.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
if [ "${BASH_VERSINFO[0]}" -lt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 4 ]; }; then
    echo "plugin-check: needs bash 4.4 or newer, this is $BASH_VERSION" >&2
    exit 2
fi

set -uo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$script_dir/.." && pwd)
plugin=$repo/essentials-plugin
evals_readme=${PLUGIN_CHECK_EVALS_README:-$plugin/evals/README.md}
stack_pins=$plugin/references/stack/stack-pins.md
plugin_json=$plugin/.claude-plugin/plugin.json

# The scripts' declared minimum (`requires-python = ">=3.11"`), as the plugin-docs job sets it.
export UV_PYTHON=${UV_PYTHON:-3.11}

mvn_cmd=${MVN:-mvn}
base=${PLUGIN_CHECK_BASE:-}
chrome=${PLUGIN_CHECK_CHROME:-}
dry_run=false
no_install=false
force=false
verbose=false

die() {
    echo "plugin-check: $*" >&2
    exit 2
}

usage() {
    sed -n '/^# Usage:/,/^# alone exits 3/p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-2}"
}

in_ci() {
    [ "${GITHUB_ACTIONS:-}" = true ]
}

# Prints the command (with the directory it runs in) and runs it there.
x() {
    local dir=$1
    shift
    local where=${dir#"$repo"}
    where=${where#/}
    printf '+ (%s) %s\n' "${where:-.}" "$*"
    (cd "$dir" && "$@")
}

# Prints an argument vector as a copy-pasteable shell line.
show() {
    local out="" arg
    for arg in "$@"; do
        out+="$(printf '%q' "$arg") "
    done
    printf '%s\n' "${out% }"
}

# The essentials.version pin. The same extraction as scripts/sync-plugin-llm.sh.
essentials_pin() {
    # shellcheck disable=SC2016 # the backticks are literal Markdown
    sed -n 's/^| `essentials.version` | \*\*\([^*]*\)\*\*.*/\1/p' "$stack_pins"
}

json_version() {
    if command -v jq >/dev/null 2>&1; then
        jq -r '.version // ""'
    else
        python3 -c 'import json, sys; print(json.load(sys.stdin).get("version") or "")'
    fi
}

# The default local base: the merge base of HEAD with origin/main (else main).
default_merge_base() {
    local ref
    for ref in origin/main main; do
        if git -C "$repo" rev-parse --verify -q "$ref^{commit}" >/dev/null; then
            git -C "$repo" merge-base HEAD "$ref"
            return
        fi
    done
    return 1
}

# ---- steps ------------------------------------------------------------------------------------------------------
# One function per plugin-docs step, in the job's order. Each runs in the directory that CI step ran in.

steps=(llm-sync citations ess-ids plugin-version symlinks slice-lint render-slice slice-source slice-index slice-law stack-lint
    init-render review-scan docs-search check-patches check-expected eval-graders eval-flags slice-compile-table render-check)
declare -A step_desc=(
    [llm-sync]="regenerate references/llm/ from LLM/ (sync-plugin-llm.sh) and fail on drift"
    [citations]="only stack-pins.md / stack-contract.md state a pin or an S-requirement; every slice-law § cite resolves (check-citations.py)"
    [ess-ids]="ESS-NNN ids on LLM/LLM-traps.md: unique, contiguous, tombstoned, resolved (check-ess-ids.py)"
    [plugin-version]="a change under essentials-plugin/ moves plugin.json's version, to the pin with an optional -N"
    [symlinks]="no tracked symlinks under essentials-plugin/"
    [slice-lint]="every fixture, golden and script case through slice-lint.py (test_slice_lint.py)"
    [render-slice]="slice templates render to the committed goldens (render-slice.py check + unit tests)"
    [slice-source]="slice source facts (test_slice_source.py)"
    [slice-index]="slice index and graph (test_slice_index.py)"
    [slice-law]="slice-law scope lines valid, every lane x kind view within budget and matching its golden (test_slice_law.py)"
    [stack-lint]="stack contract lint (stack-lint.py --self-test + unit tests)"
    [init-render]="every answer set renders and lints; the nine cells match their goldens (init-render.py)"
    [review-scan]="review signatures (review-scan.py --self-test + unit tests)"
    [docs-search]="the essentials-docs search script (test_search.py)"
    [check-patches]="review judgement cases, deterministic half (check-patches.py)"
    [check-expected]="fixture expected.yaml anchors still point at their lines (check-expected.py)"
    [eval-graders]="eval graders generated from their expected results, not edited (evals/build.py --check)"
    [eval-flags]="evals/README.md § Run it's claude plugin eval command equals the flags \`evals\` runs"
    [slice-compile-table]="every fixture is compiled or listed as not compiled (overlay.py check)"
    [render-check]="slice-map page renders in headless Chrome (render-check.py; exit 3 = skipped, no Chrome)"
)

step_llm-sync() {
    x "$repo" sh scripts/sync-plugin-llm.sh || return 1
    local drift
    drift=$(git -C "$repo" status --porcelain --untracked-files=all -- essentials-plugin/references/llm)
    if in_ci; then
        if [ -n "$drift" ]; then
            echo "::error::essentials-plugin/references/llm/ is out of step with LLM/. Edit LLM/ only, run scripts/sync-plugin-llm.sh and commit both:"
            echo "$drift"
            return 1
        fi
        return 0
    fi
    # Before a commit the working tree differs from HEAD, so a listed copy is fine as long as its LLM/ twin is listed
    # too (commit both). A copy listed alone means HEAD's copy is out of step with HEAD's LLM/, or it was hand-edited.
    if [ -z "$drift" ]; then
        echo "references/llm/ matches LLM/ and HEAD"
        return 0
    fi
    git -C "$repo" status --porcelain --untracked-files=all -- LLM essentials-plugin/references/llm
    local line path orphans=""
    while IFS= read -r line; do
        path=${line:3}
        path=${path##* -> }
        if [ -z "$(git -C "$repo" status --porcelain --untracked-files=all -- "LLM/${path#essentials-plugin/references/llm/}")" ]; then
            orphans+="  $path"$'\n'
        fi
    done <<<"$drift"
    if [ -n "$orphans" ]; then
        echo "plugin-check: references/llm/ is out of step with LLM/ at HEAD (a copy changed without its LLM/ twin)."
        echo "Edit LLM/ only, run scripts/sync-plugin-llm.sh and commit both:"
        printf '%s' "$orphans"
        return 1
    fi
    echo "every changed references/llm/ copy has its LLM/ twin: commit both"
}

step_citations() {
    x "$repo" uv run --script essentials-plugin/scripts/check-citations.py --self-test &&
        x "$repo" uv run --script essentials-plugin/scripts/check-citations.py
}

step_ess-ids() {
    local ref=$base
    if [ -z "$ref" ]; then
        in_ci && { echo "::error::ess-ids needs --base (CI: HEAD^1)"; return 2; }
        ref=HEAD
    fi
    x "$repo" uv run --script scripts/check-ess-ids.py --self-test &&
        x "$repo" uv run --script scripts/check-ess-ids.py --baseline "$ref"
}

step_plugin-version() {
    local ref=$base
    if [ -z "$ref" ]; then
        in_ci && { echo "::error::plugin-version needs --base (CI: HEAD^1)"; return 2; }
        ref=$(default_merge_base) || { echo "plugin-check: no origin/main or main to compare with; pass --base REF"; return 2; }
    fi
    # CI compares two commits; locally the working tree counts too, so an uncommitted change needs its version move.
    if in_ci; then
        if git -C "$repo" diff --quiet "$ref" HEAD -- essentials-plugin/; then
            echo "essentials-plugin/ unchanged against $ref"
            return 0
        fi
    elif git -C "$repo" diff --quiet "$ref" -- essentials-plugin/ &&
        [ -z "$(git -C "$repo" ls-files --others --exclude-standard -- essentials-plugin)" ]; then
        echo "essentials-plugin/ unchanged against $ref"
        return 0
    fi
    local old new pin
    old=$(git -C "$repo" show "$ref:essentials-plugin/.claude-plugin/plugin.json" 2>/dev/null | json_version)
    new=$(json_version <"$plugin_json")
    if [ -z "$new" ] || [ "$new" = "$old" ]; then
        if in_ci; then
            echo "::error file=essentials-plugin/.claude-plugin/plugin.json::essentials-plugin/ changed but plugin.json version is still '${new}'. Set it to the Essentials version the plugin targets, or add or raise the -N suffix for a plugin-only release (essentials-plugin/CLAUDE.md)."
        else
            echo "plugin-check: essentials-plugin/ changed against $ref but plugin.json version is still '${new}'. Set it to the Essentials version the plugin targets, or add or raise the -N suffix for a plugin-only release (essentials-plugin/CLAUDE.md)."
        fi
        return 1
    fi
    pin=$(essentials_pin)
    if ! printf '%s\n' "$new" | grep -Eq "^${pin//./\\.}(-[0-9]+)?$"; then
        if in_ci; then
            echo "::error file=essentials-plugin/.claude-plugin/plugin.json::plugin.json version '${new}' is not the essentials.version pin '${pin}' (stack-pins.md) or that pin with a -N suffix."
        else
            echo "plugin-check: plugin.json version '${new}' is not the essentials.version pin '${pin}' (stack-pins.md) or that pin with a -N suffix."
        fi
        return 1
    fi
    echo "plugin version ${old:-none} -> ${new} (against $ref)"
}

step_symlinks() {
    local links
    links=$(git -C "$repo" ls-files -s -- essentials-plugin | awk '$1 == "120000" { print $4 }')
    if [ -n "$links" ]; then
        if in_ci; then
            echo "::error::Symlinks are tracked under essentials-plugin/; commit a copy instead:"
        else
            echo "plugin-check: symlinks are tracked under essentials-plugin/; commit a copy instead:"
        fi
        echo "$links"
        return 1
    fi
    echo "no tracked symlinks under essentials-plugin/"
}

step_slice-lint() {
    x "$plugin" uv run --script tests/scripts/test_slice_lint.py
}

step_render-slice() {
    x "$plugin" uv run --script scripts/render-slice.py check &&
        x "$plugin" uv run python -m unittest discover -s tests/scripts -p 'test_render_slice.py'
}

step_slice-source() {
    x "$plugin" uv run --script tests/scripts/test_slice_source.py
}

step_slice-index() {
    x "$plugin" uv run --script tests/scripts/test_slice_index.py
}

step_slice-law() {
    x "$plugin" uv run --script tests/scripts/test_slice_law.py
}

step_stack-lint() {
    x "$plugin" uv run --script scripts/stack-lint.py --self-test &&
        x "$plugin" uv run python -m unittest tests/stack-lint/test_stack_lint.py
}

step_init-render() {
    x "$plugin" uv run --script scripts/init-render.py --self-test &&
        x "$plugin" uv run --script scripts/init-render.py --all-combinations &&
        x "$plugin" uv run --script scripts/init-render.py --check
}

step_review-scan() {
    x "$plugin" uv run --script scripts/review-scan.py --self-test &&
        x "$plugin" uv run python -m unittest discover -s tests/scripts -p 'test_review_scan.py'
}

step_docs-search() {
    x "$plugin" uv run python -m unittest discover -s tests/scripts -p 'test_search.py'
}

step_check-patches() {
    x "$plugin" uv run --script tests/review/judgement/check-patches.py
}

step_check-expected() {
    x "$plugin" uv run --script tests/fixtures/check-expected.py --self-test &&
        x "$plugin" uv run --script tests/fixtures/check-expected.py
}

step_eval-graders() {
    x "$plugin" uv run --script evals/build.py --check
}

# The README's command, continuations joined and shell-split, against eval_flags below, token for token.
# PLUGIN_CHECK_EVALS_README points it at another copy (for testing the step).
step_eval-flags() {
    local where=${evals_readme#"$repo"/}
    printf '+ (.) compare the claude plugin eval command in %s with eval_flags in scripts/plugin-check.sh\n' "$where"
    (cd "$repo" && uv run python - "$evals_readme" "$where" "${eval_flags[@]}") <<'PY'
import re, shlex, sys
readme, where, flags = sys.argv[1], sys.argv[2], sys.argv[3:]
want = ["claude", "plugin", "eval", "essentials-plugin", *flags]
lines, inside, cmd = open(readme, encoding="utf-8").read().splitlines(), False, None
for line in lines:
    if not inside and line.startswith("claude plugin eval essentials-plugin --scaffold"):
        inside, cmd = True, []
    if inside:
        if line.startswith("```"):
            break
        cmd.append(line.rstrip())
        if not line.rstrip().endswith("\\"):
            break
if cmd is None:
    sys.exit(f"eval-flags: no 'claude plugin eval essentials-plugin --scaffold …' command in {where} § Run it")
have = shlex.split(re.sub(r"\\\s*$", " ", "\n".join(cmd), flags=re.M).replace("\\\n", " "))
if have != want:
    print(f"eval-flags: the claude plugin eval command in {where} § Run it differs from eval_flags in scripts/plugin-check.sh (what `plugin-check.sh evals` runs). Make the two equal.")
    print("  README: " + shlex.join(have))
    print("  script: " + shlex.join(want))
    sys.exit(1)
print(f"eval-flags: {where} and scripts/plugin-check.sh agree ({len(flags)} flag tokens)")
PY
    local status=$?
    if [ "$status" -ne 0 ] && in_ci; then
        echo "::error file=$where::the claude plugin eval command in $where differs from eval_flags in scripts/plugin-check.sh; make the two equal."
    fi
    return "$status"
}

step_slice-compile-table() {
    x "$plugin" uv run --script tests/slice-compile/overlay.py check
}

# CI installs a pinned chrome-headless-shell and passes it in; locally render-check.py looks for one itself.
step_render-check() {
    local args=() status=0
    [ -z "$chrome" ] || args+=(--chrome "$chrome")
    x "$plugin" uv run --script tests/fixtures/slice-map/render-check.py "${args[@]}" || status=$?
    if [ "$status" -eq 3 ] && in_ci; then
        echo "::error::render-check.py skipped: it found no Chrome. In CI a skip is a failure."
    fi
    return "$status"
}

is_step() {
    [ -n "${step_desc[$1]+set}" ]
}

# run_step NAME -> the step's exit status
run_step() {
    echo "==> $1: ${step_desc[$1]}"
    "step_$1"
}

need_tools() {
    local tool missing=()
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    [ ${#missing[@]} -eq 0 ] || die "not on PATH: ${missing[*]}"
}

# ---- summary table ----------------------------------------------------------------------------------------------

summary_rows=()
worst=0

# record NAME STATUS-WORD SECONDS [NOTE]
record() {
    summary_rows+=("$(printf '  %-24s %-8s %5ss  %s' "$1" "$2" "$3" "${4:-}")")
    case $2 in
        FAIL) worst=1 ;;
    esac
}

print_summary() {
    echo
    echo "plugin-check $1 summary:"
    printf '%s\n' "${summary_rows[@]}"
}

# run_steps_into_summary STEP... : runs each, records PASS / FAIL / SKIPPED
run_steps_into_summary() {
    local name started status
    for name in "$@"; do
        started=$(date +%s)
        run_step "$name"
        status=$?
        if [ "$status" -eq 0 ]; then
            record "$name" PASS $(($(date +%s) - started))
        elif [ "$status" -eq 3 ] && [ "$name" = render-check ] && ! in_ci; then
            record "$name" SKIPPED $(($(date +%s) - started)) "no Chrome: nothing was checked (pass --chrome PATH)"
        else
            record "$name" FAIL $(($(date +%s) - started)) "exit $status"
        fi
        echo
    done
}

# ---- manual -----------------------------------------------------------------------------------------------------
# The checks no CI step runs. Each runs from essentials-plugin/.

manual_checks() {
    local out n count started
    cd "$plugin" || return 2
    started=$(date +%s)

    # The spelled-out docs count in commands/intro.md's key-facts line matches references/llm/.
    # shellcheck disable=SC2012 # the CLAUDE.md check, verbatim; the doc names are plain
    n=$(ls references/llm | wc -l | tr -d ' ')
    count=$(sed -n 's/.*· \([0-9][0-9]*\) bundled docs.*/\1/p' commands/intro.md | head -n 1)
    echo "+ ls references/llm | wc -l: $n; commands/intro.md says: ${count:-<no '· N bundled docs' line>}"
    if [ "$n" = "$count" ]; then record docs-count PASS 0; else record docs-count FAIL 0 "intro says ${count:-nothing}, references/llm has $n"; fi

    # rules/slice-design.md's sections against intro's CORE PRINCIPLES block: compare by eye.
    echo "+ grep -n '^## ' rules/slice-design.md"
    grep -n '^## ' rules/slice-design.md
    echo "+ commands/intro.md ━━━ CORE PRINCIPLES ━━━ block:"
    sed -n '/━━━ CORE PRINCIPLES ━━━/,/━━━ [A-Z]/p' commands/intro.md | sed '$d'
    record core-principles READ 0 "slice-design.md sections vs intro CORE PRINCIPLES, above"

    # The HTML template's substitution point matches commands/slice-map.md §6 verbatim: 1 and 1.
    out=$(grep -c 'const SLICE_MAP = /\* __SLICE_MAP_DATA__ \*/ null;' references/slice/slice-map-template.html commands/slice-map.md)
    echo "+ grep -c 'const SLICE_MAP = …' slice-map-template.html commands/slice-map.md"
    echo "$out"
    if [ "$(printf '%s\n' "$out" | cut -d: -f2 | tr '\n' ' ')" = "1 1 " ]; then record slice-map-data-point PASS 0; else record slice-map-data-point FAIL 0 "want 1 and 1"; fi

    # No unquoted endpoint path in any manifest this plugin ships; one hit is a file that is not YAML. The same
    # one-liner slice-check gate 1 and slice-map Step 2 give users. Scoped to slice.yaml: the guides show the broken
    # form as a counter-example.
    out=$(grep -rn "path: [^\"']*{" --include=slice.yaml .)
    echo "+ grep -rn \"path: [^\\\"']*{\" --include=slice.yaml ."
    [ -z "$out" ] || echo "$out"
    if [ -z "$out" ]; then record unquoted-paths PASS 0; else record unquoted-paths FAIL 0 "unquoted path in a slice.yaml"; fi

    # The design guide lives at references/design/essentials-design.md, and no command of another plugin is routed to.
    # CLAUDE.md is excluded: it names the patterns it guards against.
    out=$(grep -rnE "LLM-essentials-design|LLM-opinionated|/sdd:" . \
        --include=*.md --include=*.json --include=*.template --include=*.yaml | grep -vE "^(\./)?CLAUDE\.md:")
    echo "+ grep -rnE \"LLM-essentials-design|LLM-opinionated|/sdd:\" . (CLAUDE.md excluded)"
    [ -z "$out" ] || echo "$out"
    if [ -z "$out" ]; then record forbidden-names PASS 0; else record forbidden-names FAIL 0 "a stale design-guide name or a /sdd: route"; fi

    # Both halves of the init/upgrade split: what init copies into a project has a Group A counterpart in upgrade.
    n=$(grep -c 'slice-lint\.py' commands/upgrade.md)
    count=$(grep -c 'essentials-slices-rules' commands/upgrade.md)
    echo "+ grep -c commands/upgrade.md: slice-lint.py $n, essentials-slices-rules $count"
    if [ "$n" -ge 1 ] && [ "$count" -ge 1 ]; then record init-upgrade-split PASS 0; else record init-upgrade-split FAIL 0 "want both >= 1"; fi

    # The stamp placeholder is the renderer's to substitute; --all-combinations fails on a leftover.
    out=$(grep -l '{{essentialsVersion}}' references/init-assets/project/CLAUDE.md.template)
    echo "+ grep -l '{{essentialsVersion}}' references/init-assets/project/CLAUDE.md.template: ${out:-<none>}"
    if [ -n "$out" ]; then record version-stamp PASS 0; else record version-stamp FAIL 0 "the stamp placeholder is gone"; fi

    # Every requirement carries its evidence.
    n=$(grep -c '^> Proof:' references/stack/stack-contract.md)
    echo "+ grep -c '^> Proof:' references/stack/stack-contract.md: $n"
    if [ "$n" -ge 9 ]; then record proof-lines PASS 0; else record proof-lines FAIL 0 "want >= 9"; fi

    : "$started"
    cd "$repo" || return 2
}

# ---- changed ----------------------------------------------------------------------------------------------------

# changed_files MERGE_BASE: every path that differs from it (committed, staged or unstaged), plus untracked files.
changed_files() {
    { git -C "$repo" diff --name-only "$1" --; git -C "$repo" ls-files --others --exclude-standard; } | sort -u
}

# resolve_base [BASE] -> the merge base of BASE (default origin/main, else main) with HEAD
resolve_base() {
    if [ -n "${1:-}" ]; then
        git -C "$repo" rev-parse --verify -q "$1^{commit}" >/dev/null || die "unknown ref '$1'"
        git -C "$repo" merge-base "$1" HEAD || die "no merge base between '$1' and HEAD"
    else
        default_merge_base || die "no origin/main or main to compare with; pass BASE"
    fi
}

eval_cases() {
    local d
    for d in "$plugin"/evals/*/case.yaml; do
        d=${d%/case.yaml}
        echo "${d##*/}"
    done
}

compiled_fixtures() {
    (cd "$plugin" && python3 tests/slice-compile/overlay.py list 2>/dev/null) | grep -v '^#' | cut -d' ' -f1 | grep '^fixture-'
}

# s21_touched MERGE_BASE: does the diff touch stack-contract.md's S2.1 section (the working tree's line range)?
s21_touched() {
    local file=essentials-plugin/references/stack/stack-contract.md range start end hunk c d
    range=$(awk '/^### S2\.1 /{s=NR; next} s && !e && /^#{1,3} /{e=NR-1} END{if (s) print s, (e ? e : NR)}' "$repo/$file")
    [ -n "$range" ] || return 1
    read -r start end <<<"$range"
    while IFS= read -r hunk; do
        c=${hunk#*+}
        c=${c%% *}
        d=1
        case $c in *,*) d=${c#*,}; c=${c%,*} ;; esac
        [ "$d" -eq 0 ] && d=1
        if [ "$c" -le "$end" ] && [ $((c + d - 1)) -ge "$start" ]; then
            return 0
        fi
    done < <(git -C "$repo" diff -U0 "$1" -- "$file" | grep '^@@')
    return 1
}

# classify PATH -> lines "NEED<TAB>REASON". NEED: quick, manual, matrix, slices, slices:<case>, evals:<case>,
# evals:all, frontend, framework. REASON names the essentials-plugin/CLAUDE.md companion row, or the CI job the path
# is an input of.
classify() {
    local p=$1 rel name rest
    local plugin_row="any change under essentials-plugin/ (every plugin-docs step; plugin.json version moves)"
    local host_row="scaffold-slices input: its hosts render from the same init tree"
    case $p in
        LLM/LLM-traps.md)
            printf 'quick\tcompanion: a trap line added to or retired from LLM/LLM-traps.md (ess-ids, review-scan, llm-sync)\n'
            printf 'manual\tcompanion: a doc changed in LLM/ (bundled-docs count)\n' ;;
        LLM/*)
            printf 'quick\tcompanion: a doc changed in LLM/ (llm-sync; plugin-version, it regenerates references/llm/)\n'
            printf 'manual\tcompanion: a doc changed in LLM/ (bundled-docs count)\n' ;;
        scripts/plugin-scaffold.sh)
            printf 'matrix\tthe scaffold harness itself\n'
            printf 'slices\tthe scaffold harness itself\n' ;;
        scripts/plugin-check.sh | scripts/sync-plugin-llm.sh | scripts/check-ess-ids.py | .githooks/pre-commit | .github/workflows/maven.yml)
            printf 'quick\tdefines or runs a plugin-docs step\n' ;;
        .claude-plugin/*)
            printf 'quick\tthe marketplace manifest (claude plugin validate . before a release)\n' ;;
        essentials-plugin/*)
            rel=${p#essentials-plugin/}
            printf 'quick\t%s\n' "$plugin_row"
            printf 'manual\t%s\n' "$plugin_row"
            case $rel in
                references/llm/*)
                    printf 'quick\tcompanion: never edit references/llm/, it is generated from LLM/ (llm-sync)\n' ;;
                references/slice/templates/*)
                    printf 'slices\tcompanion: a slice template added, removed, or changed\n' ;;
                tests/slice-golden/*)
                    printf 'slices\tcompanion: a slice template changed (the goldens compile and start only in scaffold slices)\n' ;;
                references/init-assets/project/*)
                    printf 'matrix\tcompanion: a file under references/init-assets/project/\n'
                    printf 'slices\t%s\n' "$host_row"
                    case $rel in
                        references/init-assets/project/frontend/*)
                            printf 'frontend\tcompanion: a file under references/init-assets/project/ (the frontend tree)\n' ;;
                    esac ;;
                references/stack/stack-pins.md)
                    printf 'matrix\tcompanion: a pin needs changing\n'
                    printf 'slices\t%s\n' "$host_row"
                    printf 'frontend\tcompanion: a pin needs changing (Node and the frontend pins)\n' ;;
                references/stack/stack-contract.md)
                    if s21_touched "$mb"; then
                        printf 'matrix\tcompanion: an S2.1 row\n'
                        printf 'slices\t%s\n' "$host_row"
                    fi ;;
                scripts/init-render.py)
                    printf 'matrix\tscaffold input: renders every scaffold project\n'
                    printf 'slices\tscaffold input: renders every scaffold project\n' ;;
                tests/golden/init/cells.json | tests/init-overlay/*)
                    printf 'matrix\tscaffold-matrix input (cells, CI-only overlay)\n' ;;
                tests/slice-compile/*)
                    printf 'slices\tscaffold-slices input (overlay.py case table and overlays)\n' ;;
                tests/fixtures/change-router/cases.yaml | tests/fixtures/*/expected.yaml)
                    printf 'quick\tcompanion: a fixture'"'"'s expected.yaml or change-router cases.yaml (evals/build.py, commit the graders)\n' ;;
                tests/fixtures/*/TEST-GUIDE.md) ;;
                tests/fixtures/*/*)
                    rest=${rel#tests/fixtures/}
                    name=${rest%%/*}
                    printf 'quick\tcompanion: a fixture'"'"'s source changed (check-expected, evals/build.py, check-patches)\n'
                    if printf '%s\n' "$compiled" | grep -qx "fixture-$name"; then
                        printf 'slices:fixture-%s\tcompanion: a fixture'"'"'s source changed (compiled fixture)\n' "$name"
                    fi ;;
                evals/_lib/*)
                    printf 'evals:all\teval staging every case uses (evals/_lib/)\n' ;;
                evals/*/grading.yaml)
                    name=${rel#evals/}
                    name=${name%%/*}
                    printf 'quick\tcompanion: an eval'"'"'s grading.yaml (evals/build.py, commit the graders)\n'
                    printf 'evals:%s\teval case files changed\n' "$name" ;;
                evals/*/*)
                    name=${rel#evals/}
                    name=${name%%/*}
                    if [ -f "$plugin/evals/$name/case.yaml" ] || [ -n "$(git -C "$repo" ls-tree --name-only "$mb" -- "essentials-plugin/evals/$name/case.yaml")" ]; then
                        printf 'evals:%s\teval case files changed\n' "$name"
                    fi ;;
            esac ;;
        *.java | *.kt | pom.xml | */pom.xml)
            printf 'framework\tframework source: CI'"'"'s scaffold jobs build the generated projects against the reactor\n' ;;
    esac
}

# wider_evals PATH... : eval cases (not in $selected_cases) that stage a changed path or run a changed command.
wider_evals() {
    local case_name tokens tok p cmd hit
    for case_name in $(eval_cases); do
        case " $selected_cases " in *" $case_name "*) continue ;; esac
        hit=""
        tokens=$(grep -oE 'tests/[A-Za-z0-9_./-]+' "$plugin/evals/$case_name/scaffold.sh" 2>/dev/null | sort -u)
        cmd=$(sed -n 's/^[[:space:]]*prompt:[[:space:]]*"\{0,1\}\/essentials:\([a-z-]*\).*/\1/p' "$plugin/evals/$case_name/case.yaml")
        for p in "$@"; do
            case $p in essentials-plugin/*) ;; *) continue ;; esac
            p=${p#essentials-plugin/}
            for tok in $tokens; do
                case $p in "$tok" | "$tok"/*) hit="stages ${tok}"; break 2 ;; esac
            done
            if [ -n "$cmd" ] && [ "$p" = "commands/$cmd.md" ]; then
                hit="runs /essentials:$cmd"
                break
            fi
        done
        [ -z "$hit" ] || printf '  %-36s %s\n' "$case_name" "$hit"
    done
}

cmd_changed() {
    local base_arg="" arg
    for arg in "$@"; do
        case $arg in
            --verbose) verbose=true ;;
            -*) die "changed: unknown option '$arg'" ;;
            *) [ -z "$base_arg" ] || die "changed takes one BASE"; base_arg=$arg ;;
        esac
    done
    mb=$(resolve_base "$base_arg")
    compiled=$(compiled_fixtures)
    local files total relevant=0 path line need reason
    files=$(changed_files "$mb")
    total=$(printf '%s' "$files" | grep -c . || true)

    declare -A needs=() reason_paths=() reason_needs=()
    local reasons=()
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        local hit=false
        while IFS=$'\t' read -r need reason; do
            [ -n "$need" ] || continue
            hit=true
            needs[$need]=1
            if [ -z "${reason_paths[$reason]+set}" ]; then
                reasons+=("$reason")
                reason_paths[$reason]=""
                reason_needs[$reason]=""
            fi
            case " ${reason_needs[$reason]} " in *" $need "*) ;; *) reason_needs[$reason]+="$need " ;; esac
            case $'\n'"${reason_paths[$reason]}" in *$'\n'"$path"$'\n'*) ;; *) reason_paths[$reason]+="$path"$'\n' ;; esac
        done < <(classify "$path")
        $hit && relevant=$((relevant + 1))
    done <<<"$files"

    echo "plugin-check changed: base $base_arg${base_arg:+ }(merge base $(git -C "$repo" rev-parse --short "$mb")), $total changed path(s), $relevant mapped to a check"
    if [ ${#needs[@]} -eq 0 ]; then
        echo "Nothing in this change is a plugin check input."
        return 0
    fi

    # Which slices cases: all, or only the compiled fixtures whose sources changed.
    local slice_cases=() k
    for k in "${!needs[@]}"; do
        case $k in slices:*) slice_cases+=("${k#slices:}") ;; esac
    done
    mapfile -t slice_cases < <(printf '%s\n' "${slice_cases[@]}" | grep . | sort)

    # Which eval cases: those whose directory changed, or all when the shared staging did.
    local eval_list=()
    for k in "${!needs[@]}"; do
        case $k in evals:all) ;; evals:*) eval_list+=("${k#evals:}") ;; esac
    done
    mapfile -t eval_list < <(printf '%s\n' "${eval_list[@]}" | grep . | sort)
    selected_cases="${eval_list[*]}"

    echo
    echo "Needs:"
    [ -z "${needs[quick]+set}" ] || echo "  quick              every plugin-docs step (about a minute, no Docker)"
    [ -z "${needs[manual]+set}" ] || echo "  manual             the checks no CI step runs (seconds)"
    [ -z "${needs[matrix]+set}" ] || echo "  scaffold matrix    the nine init cells (Docker, minutes)"
    if [ -n "${needs[slices]+set}" ]; then
        echo "  scaffold slices    every slice-compile case (Docker, minutes)"
    elif [ ${#slice_cases[@]} -gt 0 ]; then
        echo "  scaffold slices    ${slice_cases[*]}"
    fi
    [ -z "${needs[frontend]+set}" ] || echo "  frontend legs      by hand, needs Node: essentials-plugin/CLAUDE.md § Before committing"
    if [ -n "${needs[evals:all]+set}" ]; then
        echo "  evals              every case (real tokens)"
    elif [ ${#eval_list[@]} -gt 0 ]; then
        echo "  evals --changed    ${#eval_list[@]} case(s): ${eval_list[*]}"
    fi
    [ -z "${needs[framework]+set}" ] || echo "  (FYI) framework    sources changed; CI's scaffold jobs build against them: 'scaffold all' covers it locally"

    echo
    echo "Why (companion row or job input -> changed paths):"
    local shown n
    for reason in "${reasons[@]}"; do
        printf '  [%s] %s\n' "${reason_needs[$reason]% }" "$reason"
        n=$(printf '%s' "${reason_paths[$reason]}" | grep -c .)
        if $verbose || [ "$n" -le 5 ]; then
            printf '%s' "${reason_paths[$reason]}" | sed 's/^/      /'
        else
            shown=$(printf '%s' "${reason_paths[$reason]}" | head -n 4)
            printf '%s\n' "$shown" | sed 's/^/      /'
            echo "      ... and $((n - 4)) more (--verbose lists them)"
        fi
    done

    local wider file_list=()
    mapfile -t file_list <<<"$files"
    wider=$(wider_evals "${file_list[@]}")
    if [ -n "$wider" ] && [ -z "${needs[evals:all]+set}" ]; then
        echo
        echo "Wider eval cases (optional; they stage a changed path or run a changed command, but their case files did not change):"
        echo "$wider"
    fi

    local base_word=${base_arg:-}
    echo
    echo "Run next:"
    [ -z "${needs[quick]+set}" ] || echo "  scripts/plugin-check.sh quick"
    [ -z "${needs[manual]+set}" ] || echo "  scripts/plugin-check.sh manual"
    local matrix=false slices_all=false
    [ -z "${needs[matrix]+set}" ] || matrix=true
    [ -z "${needs[slices]+set}" ] || slices_all=true
    if $matrix && $slices_all; then
        echo "  scripts/plugin-check.sh scaffold all"
    else
        $matrix && echo "  scripts/plugin-check.sh scaffold matrix"
        if $slices_all; then
            echo "  scripts/plugin-check.sh scaffold slices"
        elif [ ${#slice_cases[@]} -gt 0 ]; then
            local case_args=""
            for k in "${slice_cases[@]}"; do case_args+=" --case $k"; done
            if $matrix; then
                echo "  scripts/plugin-check.sh scaffold slices --no-install$case_args"
            else
                echo "  scripts/plugin-check.sh scaffold slices$case_args"
            fi
        fi
    fi
    if [ -n "${needs[evals:all]+set}" ]; then
        echo "  scripts/plugin-check.sh evals"
    elif [ ${#eval_list[@]} -gt 0 ]; then
        echo "  scripts/plugin-check.sh evals --changed${base_word:+ $base_word}"
    fi
    return 0
}

# ---- scaffold ---------------------------------------------------------------------------------------------------

# Refuses up front when a tool is missing, and warns about the one thing no check can see: another build in target/.
scaffold_preflight() {
    local compile_only=$1 missing=()
    command -v "$mvn_cmd" >/dev/null 2>&1 || missing+=("$mvn_cmd (Maven)")
    command -v java >/dev/null 2>&1 || missing+=("java (the JDK the stack pins name)")
    command -v python3 >/dev/null 2>&1 || missing+=("python3")
    if ! $compile_only; then
        if ! command -v docker >/dev/null 2>&1; then
            missing+=("docker (Testcontainers; or pass --compile-only)")
        elif ! docker info >/dev/null 2>&1; then
            missing+=("a running Docker daemon (docker info failed; or pass --compile-only)")
        fi
    fi
    if [ ${#missing[@]} -gt 0 ]; then
        printf 'plugin-check scaffold: missing %s\n' "${missing[@]}" >&2
        $dry_run || exit 2
    fi
}

scaffold_concurrency_guard() {
    echo "plugin-check scaffold: nothing else may build in this checkout's target/ directories while this runs (root"
    echo "  CLAUDE.md, '\`target/\` has more than one writer'): another mvn run, an agent session, or the IDE's Java"
    echo "  language server, which compiles into the same target/classes."
    local others
    others=$(pgrep -af 'org\.codehaus\.plexus\.classworlds\.launcher\.Launcher' 2>/dev/null | grep -v "^$$ " || true)
    if [ -n "$others" ]; then
        echo "plugin-check scaffold: another Maven process is running:" >&2
        printf '%s\n' "$others" | cut -c1-200 | sed 's/^/  /' >&2
        if ! $force && ! $dry_run; then
            echo "plugin-check scaffold: refusing the reactor install while it runs; wait for it, or pass --force." >&2
            exit 2
        fi
    fi
}

# run_scaffold MODE ARG... -> exit status of the worst build
run_scaffold() {
    local mode=$1
    shift
    local args=("$@") compile_only=false maven_repo="" i
    for ((i = 0; i < ${#args[@]}; i++)); do
        case ${args[$i]} in
            --compile-only) compile_only=true ;;
            --goal) [ "${args[$((i + 1))]:-}" != test-compile ] || compile_only=true ;;
            --maven-repo) maven_repo=${args[$((i + 1))]:-} ;;
        esac
    done
    scaffold_preflight "$compile_only"

    local install=("$mvn_cmd" clean install -DskipTests -DskipDependencyCheck=true)
    [ -z "$maven_repo" ] || install+=("-Dmaven.repo.local=$maven_repo")
    if ! $no_install; then
        scaffold_concurrency_guard
        if $dry_run; then
            echo "(cd $repo && $(show "${install[@]}"))"
        else
            x "$repo" "${install[@]}" || { echo "plugin-check scaffold: the reactor install failed" >&2; return 1; }
        fi
    fi

    local modes=("$mode") m status=0 rc
    [ "$mode" != all ] || modes=(matrix slices)
    for m in "${modes[@]}"; do
        if $dry_run; then
            show scripts/plugin-scaffold.sh "$m" "${args[@]}"
            continue
        fi
        x "$repo" scripts/plugin-scaffold.sh "$m" "${args[@]}"
        rc=$?
        [ "$rc" -le "$status" ] || status=$rc
    done
    if [ "$mode" != slices ]; then
        echo "plugin-check scaffold: the frontend legs are not automated; run them by hand in each cell with a frontend"
        echo "  (essentials-plugin/CLAUDE.md § Before committing)."
    fi
    return "$status"
}

cmd_scaffold() {
    local mode=all
    case ${1:-} in
        matrix | slices | all) mode=$1; shift ;;
    esac
    local pass=()
    while [ $# -gt 0 ]; do
        case $1 in
            --no-install) no_install=true ;;
            --force) force=true ;;
            --dry-run) dry_run=true ;;
            -h | --help) usage 0 ;;
            *) pass+=("$1") ;;
        esac
        shift
    done
    run_scaffold "$mode" "${pass[@]}"
}

# ---- evals ------------------------------------------------------------------------------------------------------

# The flags of essentials-plugin/evals/README.md § Run it, which explains each. The eval-flags step fails when the
# README's command and these differ.
eval_flags=(--scaffold --trust-plugin
    --allow-tools Bash Write Edit "WebFetch(domain:pypi.org)" "WebFetch(domain:files.pythonhosted.org)"
    --ablation none --judge-model sonnet --threshold 0.8 -j 4 --no-publish)

version_ge() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" = "$2" ]
}

# Prerequisites as evals/README.md § Run it states them (its minimum versions are read from there).
evals_preflight() {
    local problems=() want have
    want=$(sed -n 's/.*Claude Code \([0-9][0-9.]*\) or newer.*/\1/p' "$evals_readme" | head -n 1)
    if ! command -v claude >/dev/null 2>&1; then
        problems+=("claude (Claude Code ${want:-?} or newer) is not on PATH")
    elif [ -n "$want" ]; then
        have=$(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
        version_ge "${have:-0}" "$want" || problems+=("Claude Code ${have:-unknown} is older than $want")
    else
        problems+=("cannot read the minimum Claude Code version from $evals_readme")
    fi
    want=$(sed -n 's/.*git \([0-9][0-9.]*\) or newer.*/\1/p' "$evals_readme" | head -n 1)
    have=$(git --version | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -n 1)
    [ -z "$want" ] || version_ge "${have:-0}" "$want" || problems+=("git ${have:-unknown} is older than $want")
    command -v uv >/dev/null 2>&1 || problems+=("uv is not on PATH")
    command -v python3 >/dev/null 2>&1 || problems+=("python3 is not on PATH")
    if [ "$(uname -s)" = Linux ]; then
        command -v bwrap >/dev/null 2>&1 || problems+=("bubblewrap (bwrap) is not on PATH; Claude Code's OS sandbox needs it on Linux")
        command -v socat >/dev/null 2>&1 || problems+=("socat is not on PATH; Claude Code's OS sandbox needs it on Linux")
    fi
    if [ ${#problems[@]} -gt 0 ]; then
        printf 'plugin-check evals: %s\n' "${problems[@]}" >&2
        $dry_run || exit 2
    fi
}

# changed_eval_cases MERGE_BASE: case directories with a changed file; every case when evals/_lib/ changed.
changed_eval_cases() {
    local files
    files=$(changed_files "$1" | sed -n 's:^essentials-plugin/evals/::p')
    if printf '%s\n' "$files" | grep -q '^_lib/'; then
        eval_cases
        return
    fi
    printf '%s\n' "$files" | grep / | cut -d/ -f1 | sort -u | while IFS= read -r name; do
        [ -f "$plugin/evals/$name/case.yaml" ] && echo "$name"
    done
}

cmd_evals() {
    local changed=false changed_base="" case_glob="" runs="" extra=()
    while [ $# -gt 0 ]; do
        case $1 in
            --changed)
                changed=true
                if [ $# -ge 2 ] && [ "${2#-}" = "$2" ]; then changed_base=$2; shift; fi ;;
            --case) [ $# -ge 2 ] || die "--case needs a glob"; case_glob=$2; shift ;;
            --runs) [ $# -ge 2 ] || die "--runs needs a number"; runs=$2; shift ;;
            --dry-run) dry_run=true ;;
            -h | --help) usage 0 ;;
            --) shift; extra=("$@"); break ;;
            *) die "evals: unknown option '$1' (claude plugin eval arguments go after --)" ;;
        esac
        shift
    done
    if $changed && [ -n "$case_glob" ]; then
        die "evals: --changed and --case are exclusive (--case takes one glob)"
    fi
    case $runs in '' | *[!0-9]* | 0) [ -z "$runs" ] || die "--runs must be a positive number" ;; esac
    evals_preflight

    local tail_args=()
    [ -z "$runs" ] || tail_args+=(--runs "$runs")
    tail_args+=("${extra[@]}")

    local cases=()
    if $changed; then
        local cmb
        cmb=$(resolve_base "$changed_base")
        mapfile -t cases < <(changed_eval_cases "$cmb")
        echo "plugin-check evals --changed: ${#cases[@]} case(s) differ from ${changed_base:-the merge base with origin/main} ($(git -C "$repo" rev-parse --short "$cmb")): ${cases[*]:-none}"
        if [ ${#cases[@]} -eq 0 ]; then
            return 0
        fi
    fi

    local build=(uv run --script essentials-plugin/evals/build.py --check)
    local eval_cmd=(claude plugin eval essentials-plugin "${eval_flags[@]}")
    if $dry_run; then
        echo "(cd $repo && $(show "${build[@]}"))"
        if $changed; then
            local c
            for c in "${cases[@]}"; do
                show "${eval_cmd[@]}" --case "$c" "${tail_args[@]}"
            done
        elif [ -n "$case_glob" ]; then
            show "${eval_cmd[@]}" --case "$case_glob" "${tail_args[@]}"
        else
            show "${eval_cmd[@]}" "${tail_args[@]}"
        fi
        echo
        echo "Cost (evals/README.md § Run it):"
        sed -n '/^\*\*Cost and time\.\*\*/,/^$/p' "$evals_readme" | sed 's/^/  /'
        return 0
    fi

    x "$repo" "${build[@]}" || { echo "plugin-check evals: graders are out of step with their expected results; run evals/build.py and commit them" >&2; return 1; }
    if ! $changed; then
        if [ -n "$case_glob" ]; then
            x "$repo" "${eval_cmd[@]}" --case "$case_glob" "${tail_args[@]}"
        else
            x "$repo" "${eval_cmd[@]}" "${tail_args[@]}"
        fi
        return $?
    fi
    local c rc status=0 started
    summary_rows=()
    for c in "${cases[@]}"; do
        started=$(date +%s)
        x "$repo" "${eval_cmd[@]}" --case "$c" "${tail_args[@]}"
        rc=$?
        case $rc in
            0) record "$c" PASS $(($(date +%s) - started)) ;;
            1) record "$c" FAIL $(($(date +%s) - started)) "below --threshold" ;;
            *) record "$c" FAIL $(($(date +%s) - started)) "exit $rc" ;;
        esac
        [ "$rc" -le "$status" ] || status=$rc
    done
    print_summary "evals --changed"
    echo "Reports: essentials-plugin/evals/results/<timestamp>/report.html. A case red in one run of three is noise until it repeats."
    return "$status"
}

# ---- release ----------------------------------------------------------------------------------------------------

check_changelog() {
    local heading top version pin
    heading=$(grep -m 1 '^## ' "$plugin/CHANGELOG.md")
    top=$(printf '%s\n' "$heading" | sed 's/^## \([^ ]*\).*/\1/')
    version=$(json_version <"$plugin_json")
    pin=$(essentials_pin)
    echo "+ CHANGELOG.md top heading: '$heading'; plugin.json version: '$version'; essentials.version pin: '$pin'"
    if [ "$top" != "$version" ]; then
        echo "plugin-check: CHANGELOG.md's top heading names '$top', plugin.json's version is '$version'"
        return 1
    fi
    if ! printf '%s\n' "$version" | grep -Eq "^${pin//./\\.}(-[0-9]+)?$"; then
        echo "plugin-check: plugin.json version '$version' is not the essentials.version pin '$pin' or that pin with a -N suffix"
        return 1
    fi
    case $heading in
        *"Essentials $pin"*) ;;
        *) echo "plugin-check: CHANGELOG.md's top heading does not name the Essentials release it targets ('Essentials $pin')"; return 1 ;;
    esac
}

cmd_release() {
    local started rc skipped=false
    if $dry_run; then
        echo "plugin-check release --dry-run: would run"
        echo "  scripts/plugin-check.sh quick${base:+ --base $base}${chrome:+ --chrome $chrome}"
        echo "  scripts/plugin-check.sh manual"
        run_scaffold all | sed 's/^/  /'
        cmd_evals --dry-run | sed 's/^/  /'
        echo "  (cd $repo && claude plugin validate essentials-plugin)"
        echo "  (cd $repo && claude plugin validate .)"
        echo "  CHANGELOG.md top heading == plugin.json version == essentials.version pin [-N]"
        return 0
    fi
    command -v claude >/dev/null 2>&1 || die "claude is not on PATH; a release runs the evals and claude plugin validate"

    run_steps_into_summary "${steps[@]}"
    for row in "${summary_rows[@]}"; do
        case $row in *render-check*SKIPPED*) skipped=true ;; esac
    done
    manual_checks

    started=$(date +%s)
    run_scaffold all
    rc=$?
    if [ "$rc" -eq 0 ]; then record "scaffold all" PASS $(($(date +%s) - started)); else record "scaffold all" FAIL $(($(date +%s) - started)) "exit $rc"; fi

    started=$(date +%s)
    local saved=("${summary_rows[@]}") saved_worst=$worst
    cmd_evals
    rc=$?
    summary_rows=("${saved[@]}")
    worst=$saved_worst
    if [ "$rc" -eq 0 ]; then record "evals (all cases)" PASS $(($(date +%s) - started)); else record "evals (all cases)" FAIL $(($(date +%s) - started)) "exit $rc; read the report"; fi

    for target in essentials-plugin .; do
        started=$(date +%s)
        if x "$repo" claude plugin validate "$target"; then
            record "validate $target" PASS $(($(date +%s) - started))
        else
            record "validate $target" FAIL $(($(date +%s) - started))
        fi
    done

    if check_changelog; then record changelog-version PASS 0; else record changelog-version FAIL 0; fi

    if $skipped; then
        worst=1
        summary_rows+=("  -> render-check was SKIPPED: a release needs it with a Chrome (exit 0, not 3); pass --chrome PATH")
    fi
    print_summary release
    cat <<'EOF'

By hand (no script can check these; essentials-plugin/CLAUDE.md § Before a release):
  [ ] The latest run of .github/workflows/plugin-scaffold-scheduled.yml is green on the release ref (start it by
      hand if the ref has had no nightly run): gh run list --workflow plugin-scaffold-scheduled.yml --limit 1
  [ ] The frontend legs, in each cell with a frontend (essentials-plugin/CLAUDE.md § Before committing).
  [ ] The eval report (essentials-plugin/evals/results/<timestamp>/report.html): read every case under the
      threshold before deciding; one red run of three is noise until it repeats.
  [ ] claude plugin validate: the one expected warning is CLAUDE.md not loaded as plugin context; anything else is not.
  [ ] CHANGELOG.md says what the release adds or changes.
EOF
    return "$worst"
}

# ---- main -------------------------------------------------------------------------------------------------------

[ $# -ge 1 ] || usage 2
command=$1
shift

# --base / --chrome for the subcommands that run steps.
parse_step_options() {
    rest=()
    while [ $# -gt 0 ]; do
        case $1 in
            --base) [ $# -ge 2 ] || die "--base needs a ref"; base=$2; shift 2 ;;
            --chrome) [ $# -ge 2 ] || die "--chrome needs a path"; chrome=$2; shift 2 ;;
            --dry-run) dry_run=true; shift ;;
            --no-install) no_install=true; shift ;;
            --force) force=true; shift ;;
            -h | --help) usage 0 ;;
            *) rest+=("$1"); shift ;;
        esac
    done
}

case $command in
    -h | --help | help)
        usage 0 ;;
    list)
        for name in "${steps[@]}"; do
            printf '%-20s %s\n' "$name" "${step_desc[$name]}"
        done ;;
    step)
        parse_step_options "$@"
        [ ${#rest[@]} -ge 1 ] || die "step needs at least one name; 'list' shows them"
        for name in "${rest[@]}"; do
            is_step "$name" || die "unknown step '$name'; 'list' shows them"
        done
        need_tools git uv
        if [ ${#rest[@]} -eq 1 ]; then
            run_step "${rest[0]}"
            exit $?
        fi
        overall=0
        for name in "${rest[@]}"; do
            run_step "$name"
            rc=$?
            if [ "$rc" -eq 3 ] && [ "$name" = render-check ]; then
                [ "$overall" -ne 0 ] || overall=3
            elif [ "$rc" -ne 0 ]; then
                overall=1
            fi
        done
        exit "$overall" ;;
    quick)
        parse_step_options "$@"
        [ ${#rest[@]} -eq 0 ] || die "quick: unexpected argument '${rest[0]}'"
        need_tools git uv
        run_steps_into_summary "${steps[@]}"
        print_summary quick
        exit "$worst" ;;
    manual)
        [ $# -eq 0 ] || die "manual takes no arguments"
        manual_checks
        print_summary manual
        exit "$worst" ;;
    changed)
        cmd_changed "$@" ;;
    scaffold)
        cmd_scaffold "$@"
        exit $? ;;
    evals)
        cmd_evals "$@"
        exit $? ;;
    release)
        parse_step_options "$@"
        [ ${#rest[@]} -eq 0 ] || die "release: unexpected argument '${rest[0]}'"
        need_tools git uv
        cmd_release
        exit $? ;;
    *)
        die "unknown subcommand '$command' (list | step | quick | manual | scaffold | evals | changed | release)" ;;
esac
