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
# Tests .claude/hooks/context-guard.sh: the context window each agent is measured against, the
# restore after a subagent's compaction, STATE.md handed back once per write, and the
# pre-compaction snapshot without GNU-only tools.
#
# Every case runs the hook as Claude Code does - hook JSON on stdin, the mode as $1 - against
# fake transcripts in a temp dir. CONTEXT_GUARD_DIR, CLAUDE_CONFIG_DIR and CLAUDE_PROJECT_DIR
# point into that dir, so the user's settings and the live sessions' state are never read.
#
# Usage: scripts/test-context-guard.sh        # needs bash and jq; exits non-zero on the first
#                                             # failing case and names it
set -u

root=$(cd "$(dirname "$0")/.." && pwd)
hook=$root/.claude/hooks/context-guard.sh
command -v jq >/dev/null 2>&1 || { echo "test-context-guard: jq is required" >&2; exit 2; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export CONTEXT_GUARD_DIR=$tmp/guard
export CLAUDE_CONFIG_DIR=$tmp/config
export CLAUDE_PROJECT_DIR=$tmp/project
unset CONTEXT_GUARD_WINDOW CONTEXT_GUARD_LEVELS
mkdir -p "$CLAUDE_CONFIG_DIR" "$CLAUDE_PROJECT_DIR/.claude" "$tmp/sessions"

case_name=""
fail() { echo "FAIL [$case_name]: $1" >&2; exit 1; }
pass() { echo "ok   [$case_name]"; }

settings_model() { # $1: the settings `model` (the alias Claude Code's settings hold)
  jq -n --arg m "$1" '{model: $m}' >"$CLAUDE_PROJECT_DIR/.claude/settings.json"
}

main_transcript() { echo "$tmp/sessions/$1.jsonl"; }                       # $1 session
agent_transcript() { echo "$tmp/sessions/$1/subagents/agent-$2.jsonl"; }   # $1 session, $2 agent

assistant_entry() { # $1 file, $2 model, $3 tokens, $4 isSidechain (true|false)
  mkdir -p "$(dirname "$1")"
  jq -nc --arg m "$2" --argjson t "$3" --argjson s "$4" \
    '{type: "assistant", isSidechain: $s,
      message: {model: $m, content: [], usage: {input_tokens: $t, cache_read_input_tokens: 0, cache_creation_input_tokens: 0}}}' \
    >>"$1"
}

hook_input() { # $1 session, $2 agent ("" for the main conversation)
  jq -nc --arg s "$1" --arg t "$(main_transcript "$1")" --arg a "$2" --arg c "$CLAUDE_PROJECT_DIR" \
    '{session_id: $s, transcript_path: $t, cwd: $c, hook_event_name: "PostToolUse", trigger: "auto"}
     + (if $a == "" then {} else {agent_id: $a} end)'
}

run() { # $1 mode, $2 session, $3 agent; prints the additionalContext the hook emitted
  hook_input "$2" "$3" | bash "$hook" "$1" | jq -r '.hookSpecificOutput.additionalContext // empty'
}

expect_warning() { # $1 output, $2 "<used>/<window>"
  case "$1" in
    *"CONTEXT GUARD: context for"*"($2 tokens)"*) ;;
    '') fail "expected a warning at $2 tokens, got none" ;;
    *) fail "expected a warning at $2 tokens, got: ${1:0:200}" ;;
  esac
}

expect_nothing() { [ -z "$1" ] || fail "$2; got: ${1:0:200}"; }

window_of() { cat "$CONTEXT_GUARD_DIR/$1/agents/$2/window" 2>/dev/null; } # $1 session, $2 agent

# --- Window sizing for a subagent -----------------------------------------------------------

# Below 200000 first: a count past 200000 proves 1M on its own, so only a smaller one shows
# that the window was 1000000 from the start.
case_name="alias main model, subagent inherits it: 1000000"
settings_model 'opus[1m]'
assistant_entry "$(main_transcript s1)" claude-opus-5-5 40000 false
f=$(agent_transcript s1 a1)
assistant_entry "$f" claude-opus-5-5 150000 true
expect_nothing "$(run warn s1 a1)" "15% of 1000000 is below every level"
[ "$(window_of s1 a1)" = 1000000 ] || fail "window is '$(window_of s1 a1)', expected 1000000"
assistant_entry "$f" claude-opus-5-5 750000 true
expect_warning "$(run warn s1 a1)" 750000/1000000
pass

case_name="alias main model, subagent on another id of the same family: 200000"
assistant_entry "$(agent_transcript s1 a2)" claude-opus-4-1 150000 true
expect_warning "$(run warn s1 a2)" 150000/200000
pass

case_name="alias main model, no main transcript model yet: 200000"
assistant_entry "$(agent_transcript s2 a1)" claude-opus-5-5 150000 true
expect_warning "$(run warn s2 a1)" 150000/200000
pass

case_name="alias main model, main transcript ends in a <synthetic> entry: still 1000000"
assistant_entry "$(main_transcript s6)" claude-opus-5-5 40000 false
assistant_entry "$(main_transcript s6)" '<synthetic>' 0 false # an API error placeholder
assistant_entry "$(agent_transcript s6 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run warn s6 a1)" "15% of 1000000 is below every level"
[ "$(window_of s6 a1)" = 1000000 ] || fail "window is '$(window_of s6 a1)', expected 1000000"
pass

case_name="full id in main/model, subagent on that id: 1000000"
jq -nc --arg s s3 --arg t "$(main_transcript s3)" '{session_id: $s, transcript_path: $t, model: "claude-opus-5-5[1m]"}' \
  | bash "$hook" start >/dev/null
assistant_entry "$(agent_transcript s3 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run warn s3 a1)" "15% of 1000000 is below every level"
[ "$(window_of s3 a1)" = 1000000 ] || fail "window is '$(window_of s3 a1)', expected 1000000"
pass

case_name="full id in main/model, subagent on another id: 200000"
assistant_entry "$(agent_transcript s3 a2)" claude-opus-4-1 150000 true
expect_warning "$(run warn s3 a2)" 150000/200000
pass

# --- A subagent on another family: window grows, never shrinks, restore once ----------------

case_name="other family at 150000: 200000"
f=$(agent_transcript s1 a3)
assistant_entry "$f" claude-sonnet-5 150000 true
expect_warning "$(run warn s1 a3)" 150000/200000
pass

case_name="other family at 250000: window becomes 1000000"
assistant_entry "$f" claude-sonnet-5 250000 true
expect_nothing "$(run warn s1 a3)" "25% of 1000000 is below every level"
[ "$(window_of s1 a3)" = 1000000 ] || fail "window is '$(window_of s1 a3)', expected 1000000"
pass

case_name="other family at 750000: warns against 1000000"
assistant_entry "$f" claude-sonnet-5 750000 true
expect_warning "$(run warn s1 a3)" 750000/1000000
pass

case_name="other family compacted to 20000: window stays 1000000, restore emitted once"
printf '%s\n' "# Working state" "STATE-MARKER-A3" >"$CONTEXT_GUARD_DIR/s1/agents/a3/STATE.md"
assistant_entry "$f" claude-sonnet-5 20000 true
out=$(run warn s1 a3)
case "$out" in
  *"was just compacted"*STATE-MARKER-A3*) ;;
  *) fail "expected the restore with STATE.md, got: ${out:0:200}" ;;
esac
[ "$(window_of s1 a3)" = 1000000 ] || fail "window is '$(window_of s1 a3)', expected 1000000"
expect_nothing "$(run warn s1 a3)" "the restore was emitted a second time"
[ "$(window_of s1 a3)" = 1000000 ] || fail "window shrank to '$(window_of s1 a3)'"
pass

# --- STATE.md handed back once per write -----------------------------------------------------

case_name="restore hands STATE.md back once per write"
mkdir -p "$CONTEXT_GUARD_DIR/s4/main"
state=$CONTEXT_GUARD_DIR/s4/main/STATE.md
printf '%s\n' "STATE-MARKER-1" >"$state"
out=$(run restore s4 "")
case "$out" in *STATE-MARKER-1*) ;; *) fail "first restore lacks STATE.md: ${out:0:200}" ;; esac
out=$(run restore s4 "")
case "$out" in *STATE-MARKER*) fail "second restore without a rewrite handed STATE.md back again" ;; esac
printf '%s\n' "STATE-MARKER-2" >"$state"
out=$(run restore s4 "")
case "$out" in *STATE-MARKER-2*) ;; *) fail "restore after a rewrite lacks STATE.md: ${out:0:200}" ;; esac
out=$(run restore s4 "")
case "$out" in *STATE-MARKER*) fail "restore after the rewrite was handed back handed it back again" ;; esac
touch -t 209901010000 "$state" # the same content written again, later
out=$(run restore s4 "")
case "$out" in *STATE-MARKER-2*) ;; *) fail "restore after a same-content rewrite lacks STATE.md: ${out:0:200}" ;; esac
pass

# --- Snapshot without GNU-only tools ---------------------------------------------------------

case_name="precompact without tac, sed and awk: each edited path once, at most 30, oldest first"
bin=$tmp/bin
mkdir -p "$bin"
for t in bash jq cat head tail mkdir rm cp cmp date git id; do
  p=$(command -v "$t") && ln -s "$p" "$bin/$t"
done
for t in tac sed awk; do
  PATH=$bin command -v "$t" >/dev/null 2>&1 && fail "$t is still on the test PATH"
done
f=$(main_transcript s5)
for i in $(seq 1 35) 20 3; do # 3 re-edited past the cut, 20 within it
  jq -nc --arg p "/p/$i" \
    '{type: "assistant", message: {content: [{type: "tool_use", name: "Edit", input: {file_path: $p}}]}}' >>"$f"
done
hook_input s5 "" | PATH=$bin "$bin/bash" "$hook" precompact
snapshot=$CONTEXT_GUARD_DIR/s5/main/precompact-snapshot.md
[ -s "$snapshot" ] || fail "no snapshot written"
listed=()
while IFS= read -r line; do
  case "$line" in "- /p/"*) listed+=("${line#- }") ;; esac
done <"$snapshot"
expected=()
for i in $(seq 7 19) $(seq 21 35) 20 3; do expected+=("/p/$i"); done
[ "${listed[*]}" = "${expected[*]}" ] || fail "edited files listed as '${listed[*]}', expected '${expected[*]}'"
pass

echo "test-context-guard: all cases passed"
