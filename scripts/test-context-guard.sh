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
# Tests .claude/hooks/context-guard.sh: the context window each agent, the main conversation
# included, is measured against (the main conversation's capped at its auto-compact window when
# one is set; and that a subagent reads the main transcript's model from its
# tail, once, and the whole file only when its last 5000 lines name none), the restore after a
# compaction and whose state it hands back (every agent's own, from the warn after a
# compact_boundary in its own transcript; nothing at SessionStart(compact), which cannot tell
# a subagent's compaction from the main conversation's), STATE.md handed back once per write,
# the pre-compaction snapshot kept for an owed main restore, and the pre-compaction snapshot
# without GNU-only tools.
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
unset CONTEXT_GUARD_WINDOW CONTEXT_GUARD_HEADROOM CONTEXT_GUARD_RESERVE
mkdir -p "$CLAUDE_CONFIG_DIR" "$CLAUDE_PROJECT_DIR/.claude" "$tmp/sessions"

case_name=""
fail() { echo "FAIL [$case_name]: $1" >&2; exit 1; }
pass() { echo "ok   [$case_name]"; }

settings_model() { # $1: the settings `model` (the alias Claude Code's settings hold)
  jq -n --arg m "$1" '{model: $m}' >"$CLAUDE_PROJECT_DIR/.claude/settings.json"
}

main_transcript() { echo "$tmp/sessions/$1.jsonl"; }                       # $1 session
agent_transcript() { echo "$tmp/sessions/$1/subagents/agent-$2.jsonl"; }   # $1 session, $2 agent

assistant_entry() { # $1 file, $2 model, $3 input tokens, $4 isSidechain (true|false), $5 output tokens (default 0)
  mkdir -p "$(dirname "$1")"
  jq -nc --arg m "$2" --argjson t "$3" --argjson s "$4" --argjson o "${5:-0}" \
    '{type: "assistant", isSidechain: $s,
      message: {model: $m, content: [], usage: {input_tokens: $t, cache_read_input_tokens: 0, cache_creation_input_tokens: 0,
                                                output_tokens: $o}}}' \
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
expect_nothing "$(run warn s1 a1)" "15% of 1000000 is short of every warning point"
[ "$(window_of s1 a1)" = 1000000 ] || fail "window is '$(window_of s1 a1)', expected 1000000"
assistant_entry "$f" claude-opus-5-5 900000 true
expect_warning "$(run warn s1 a1)" 900000/1000000
pass

case_name="alias main model, subagent on another id of the same family: 200000"
assistant_entry "$(agent_transcript s1 a2)" claude-opus-4-1 155000 true
expect_warning "$(run warn s1 a2)" 155000/200000
pass

case_name="alias main model, no main transcript model yet: 200000"
assistant_entry "$(agent_transcript s2 a1)" claude-opus-5-5 155000 true
expect_warning "$(run warn s2 a1)" 155000/200000
pass

case_name="alias main model, main transcript ends in a <synthetic> entry: still 1000000"
assistant_entry "$(main_transcript s6)" claude-opus-5-5 40000 false
assistant_entry "$(main_transcript s6)" '<synthetic>' 0 false # an API error placeholder
assistant_entry "$(agent_transcript s6 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run warn s6 a1)" "15% of 1000000 is short of every warning point"
[ "$(window_of s6 a1)" = 1000000 ] || fail "window is '$(window_of s6 a1)', expected 1000000"
pass

# tail and jq wrapped to log every argument list: a read of the main transcript names its path.
shim=$tmp/shim
reads=$tmp/main-transcript-reads.log
mkdir -p "$shim"
for t in tail jq; do
  printf '%s\n' '#!/usr/bin/env bash' "printf '%s\n' \"\$*\" >>'$reads'" "exec '$(command -v "$t")' \"\$@\"" >"$shim/$t"
  chmod +x "$shim/$t"
done
main_reads() { # $1 main transcript; prints "<tail reads> <whole-file reads>" logged so far
  local t=0 w=0 line
  [ -f "$reads" ] || { echo "0 0"; return; }
  while IFS= read -r line; do
    case "$line" in
      "-n "*" $1") t=$((t + 1)) ;;
      *" $1") w=$((w + 1)) ;;
    esac
  done <"$reads"
  echo "$t $w"
}

case_name="alias main model, last 500+ main transcript lines hold no usage entry: 1000000, no whole-file read"
m=$(main_transcript s7)
assistant_entry "$m" claude-opus-5-5 40000 false
for i in $(seq 1 600); do # tool results and the like: no assistant entry with a usage block
  jq -nc --argjson i "$i" '{type: "user", message: {content: [{type: "tool_result", content: "r\($i)"}]}}' >>"$m"
done
assistant_entry "$(agent_transcript s7 a1)" claude-opus-5-5 150000 true
rm -f "$reads"
out=$(hook_input s7 a1 | PATH=$shim:$PATH bash "$hook" warn | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_nothing "$out" "15% of 1000000 is short of every warning point"
[ "$(window_of s7 a1)" = 1000000 ] || fail "window is '$(window_of s7 a1)', expected 1000000"
read -r tails wholes <<<"$(main_reads "$m")"
[ "$wholes" = 0 ] || fail "the main transcript was read whole $wholes time(s); its last 5000 lines name the model"
[ "$tails" -gt 0 ] || fail "the main transcript's tail was never read"
[ "$(cat "$CONTEXT_GUARD_DIR/s7/agents/a1/main_model_id" 2>/dev/null)" = claude-opus-5-5 ] \
  || fail "main_model_id holds '$(cat "$CONTEXT_GUARD_DIR/s7/agents/a1/main_model_id" 2>/dev/null)', expected claude-opus-5-5"
pass

case_name="alias main model, the subagent's next call does not read the main transcript again"
rm -f "$reads"
assistant_entry "$(agent_transcript s7 a1)" claude-opus-5-5 160000 true
out=$(hook_input s7 a1 | PATH=$shim:$PATH bash "$hook" warn | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_nothing "$out" "16% of 1000000 is short of every warning point"
[ "$(main_reads "$m")" = "0 0" ] || fail "the main transcript was read again (tail, whole: $(main_reads "$m"))"
pass

case_name="alias main model, last main usage entry beyond the 5000-line tail: whole file read, 1000000"
m=$(main_transcript s9)
assistant_entry "$m" claude-opus-5-5 40000 false
jq -nc 'range(1; 5101) | {type: "user", message: {content: [{type: "tool_result", content: "r\(.)"}]}}' >>"$m"
assistant_entry "$(agent_transcript s9 a1)" claude-opus-5-5 150000 true
rm -f "$reads"
out=$(hook_input s9 a1 | PATH=$shim:$PATH bash "$hook" warn | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_nothing "$out" "15% of 1000000 is short of every warning point"
[ "$(window_of s9 a1)" = 1000000 ] || fail "window is '$(window_of s9 a1)', expected 1000000"
read -r tails wholes <<<"$(main_reads "$m")"
[ "$wholes" = 1 ] || fail "the main transcript was read whole $wholes time(s), expected once: its last 5000 lines name no model"
pass

case_name="alias main model, no id found yet: nothing kept, a later call finds it"
assistant_entry "$(main_transcript s8)" '<synthetic>' 0 false # read, but names no model
assistant_entry "$(agent_transcript s8 a1)" claude-opus-5-5 155000 true
expect_warning "$(run warn s8 a1)" 155000/200000
[ -e "$CONTEXT_GUARD_DIR/s8/agents/a1/main_model_id" ] && fail "an empty main_model_id was kept"
assistant_entry "$(main_transcript s8)" claude-opus-5-5 40000 false
assistant_entry "$(agent_transcript s8 a1)" claude-opus-5-5 160000 true
expect_nothing "$(run warn s8 a1)" "16% of 1000000 is short of every warning point"
[ "$(window_of s8 a1)" = 1000000 ] || fail "window is '$(window_of s8 a1)', expected 1000000"
pass

case_name="full id in main/model, subagent on that id: 1000000"
jq -nc --arg s s3 --arg t "$(main_transcript s3)" '{session_id: $s, transcript_path: $t, model: "claude-opus-5-5[1m]"}' \
  | bash "$hook" start >/dev/null
assistant_entry "$(agent_transcript s3 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run warn s3 a1)" "15% of 1000000 is short of every warning point"
[ "$(window_of s3 a1)" = 1000000 ] || fail "window is '$(window_of s3 a1)', expected 1000000"
pass

case_name="full id in main/model, subagent on another id: 200000"
assistant_entry "$(agent_transcript s3 a2)" claude-opus-4-1 155000 true
expect_warning "$(run warn s3 a2)" 155000/200000
pass

# A main model without [1m] sizes every agent at 200000, even one on the main session's own id.
case_name="alias main model without [1m], subagent on the main transcript's id: 200000"
settings_model 'opus'
assistant_entry "$(main_transcript s10)" claude-opus-5-5 40000 false
assistant_entry "$(agent_transcript s10 a1)" claude-opus-5-5 155000 true
expect_warning "$(run warn s10 a1)" 155000/200000
pass

case_name="full id without [1m] in main/model, subagent on that id: 200000"
settings_model 'opus[1m]' # main/model is read before the settings, so this one is not used
jq -nc --arg s s11 --arg t "$(main_transcript s11)" '{session_id: $s, transcript_path: $t, model: "claude-opus-5-5"}' \
  | bash "$hook" start >/dev/null
assistant_entry "$(agent_transcript s11 a1)" claude-opus-5-5 155000 true
expect_warning "$(run warn s11 a1)" 155000/200000
pass

# --- Window sizing for the main conversation ------------------------------------------------

main_window_of() { cat "$CONTEXT_GUARD_DIR/$1/main/window" 2>/dev/null; } # $1 session

case_name="main conversation, alias main model with [1m]: 1000000"
settings_model 'opus[1m]'
assistant_entry "$(main_transcript s12)" claude-opus-5-5 150000 false
expect_nothing "$(run warn s12 "")" "15% of 1000000 is short of every warning point"
[ "$(main_window_of s12)" = 1000000 ] || fail "window is '$(main_window_of s12)', expected 1000000"
pass

case_name="main conversation, full id with [1m] in main/model: 1000000"
settings_model 'opus' # main/model is read before the settings, so this one is not used
jq -nc --arg s s13 --arg t "$(main_transcript s13)" '{session_id: $s, transcript_path: $t, model: "claude-opus-5-5[1m]"}' \
  | bash "$hook" start >/dev/null
assistant_entry "$(main_transcript s13)" claude-opus-5-5 150000 false
expect_nothing "$(run warn s13 "")" "15% of 1000000 is short of every warning point"
[ "$(main_window_of s13)" = 1000000 ] || fail "window is '$(main_window_of s13)', expected 1000000"
pass

case_name="main conversation, alias main model without [1m]: 200000"
settings_model 'opus'
assistant_entry "$(main_transcript s14)" claude-opus-5-5 155000 false
expect_warning "$(run warn s14 "")" 155000/200000
[ "$(main_window_of s14)" = 200000 ] || fail "window is '$(main_window_of s14)', expected 200000"
pass

case_name="main conversation, no model in main/model or any settings file: 200000"
rm -f "$CLAUDE_PROJECT_DIR/.claude/settings.json"
assistant_entry "$(main_transcript s15)" claude-opus-5-5 155000 false
expect_warning "$(run warn s15 "")" 155000/200000
[ "$(main_window_of s15)" = 200000 ] || fail "window is '$(main_window_of s15)', expected 200000"
settings_model 'opus[1m]' # the cases below run under the alias with [1m] again
pass

# /autocompact saves the window under the model id in the user settings; a 1M session with a
# 300000 window compacts near 270000, so 70% of 1000000 would never be reached.
user_settings() { printf '%s' "$1" >"$CLAUDE_CONFIG_DIR/settings.json"; } # $1: JSON

case_name="main conversation, 1M model with an auto-compact window saved for its id: that window"
user_settings '{"model": "opus[1m]", "modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
assistant_entry "$(main_transcript s16)" claude-opus-5-5 250000 false
expect_warning "$(run warn s16 "")" 250000/300000
[ "$(main_window_of s16)" = 300000 ] || fail "window is '$(main_window_of s16)', expected 300000"
pass

case_name="main conversation, window saved for another model id only: 1000000"
user_settings '{"modelSettings": {"claude-sonnet-5-5": {"autoCompactWindow": 300000}}}'
assistant_entry "$(main_transcript s17)" claude-opus-5-5 220000 false
expect_nothing "$(run warn s17 "")" "22% of 1000000 is short of every warning point"
[ "$(main_window_of s17)" = 1000000 ] || fail "window is '$(main_window_of s17)', expected 1000000"
pass

case_name="main conversation, top-level autoCompactWindow: that window"
user_settings '{"autoCompactWindow": 400000}'
assistant_entry "$(main_transcript s18)" claude-opus-5-5 340000 false
expect_warning "$(run warn s18 "")" 340000/400000
pass

case_name="main conversation, CLAUDE_CODE_AUTO_COMPACT_WINDOW outranks the settings"
assistant_entry "$(main_transcript s19)" claude-opus-5-5 155000 false
expect_warning "$(hook_input s19 "" | CLAUDE_CODE_AUTO_COMPACT_WINDOW=200000 bash "$hook" warn \
  | jq -r '.hookSpecificOutput.additionalContext // empty')" 155000/200000
pass

# A settings window is any JSON number: jq keeps its literal (300000.0, 3E+5), and a value that
# is not an integer bash can compare must never reach its numeric tests.
hook_err=$tmp/hook-stderr.log
warn_main() { # $1 session; runs warn for the main conversation, its stderr in $hook_err
  hook_input "$1" "" | bash "$hook" warn 2>"$hook_err" | jq -r '.hookSpecificOutput.additionalContext // empty'
}
expect_no_stderr() { [ ! -s "$hook_err" ] || fail "the hook wrote to stderr: $(head -c 200 "$hook_err")"; }

case_name="main conversation, autoCompactWindow 300000.0 or 3e5: that window, nothing on stderr"
i=0
for w in 300000.0 3e5 3.0E5; do
  i=$((i + 1))
  user_settings "{\"autoCompactWindow\": $w}"
  assistant_entry "$(main_transcript s34-$i)" claude-opus-5-5 250000 false
  out=$(warn_main s34-$i)
  expect_no_stderr
  expect_warning "$out" 250000/300000
done
pass

case_name="main conversation, autoCompactWindow fractional or past bash's integers: ignored, nothing on stderr"
i=0
for w in 300000.5 1e20 99999999999999999999; do
  i=$((i + 1))
  user_settings "{\"autoCompactWindow\": $w}"
  assistant_entry "$(main_transcript s35-$i)" claude-opus-5-5 220000 false
  out=$(warn_main s35-$i)
  expect_no_stderr
  expect_nothing "$out" "22% of 1000000 is short of every warning point (autoCompactWindow $w)"
  [ "$(main_window_of s35-$i)" = 1000000 ] || fail "window is '$(main_window_of s35-$i)' for $w, expected 1000000"
done
pass

case_name="main conversation, CLAUDE_CODE_AUTO_COMPACT_WINDOW past bash's integers: the settings window, nothing on stderr"
user_settings '{"autoCompactWindow": 300000}'
assistant_entry "$(main_transcript s36)" claude-opus-5-5 250000 false
out=$(CLAUDE_CODE_AUTO_COMPACT_WINDOW=99999999999999999999 warn_main s36)
expect_no_stderr
expect_warning "$out" 250000/300000
pass

case_name="subagent ignores the main session's auto-compact window: 1000000"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
assistant_entry "$(main_transcript s20)" claude-opus-5-5 40000 false
assistant_entry "$(agent_transcript s20 a1)" claude-opus-5-5 220000 true
expect_nothing "$(run warn s20 a1)" "22% of 1000000 is short of every warning point"
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

# --- Warning points: headroom before the compaction point (window - 33000) ------------------

case_name="300000 window: warns at 8% and again at 5% left before ~267000, once each"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
f=$(main_transcript s26)
assistant_entry "$f" claude-opus-5-5 240000 false
expect_nothing "$(run warn s26 "")" "27000 left before compaction is more than 8% of 300000"
assistant_entry "$f" claude-opus-5-5 245000 false
out=$(run warn s26 "")
expect_warning "$out" 245000/300000
case "$out" in *"about 267000 tokens, 22000 from now"*) ;; *) fail "warning does not name the compaction point: ${out:0:300}" ;; esac
assistant_entry "$f" claude-opus-5-5 248000 false
expect_nothing "$(run warn s26 "")" "the 8% warning came twice"
assistant_entry "$f" claude-opus-5-5 253000 false
expect_warning "$(run warn s26 "")" 253000/300000
assistant_entry "$f" claude-opus-5-5 260000 false
expect_nothing "$(run warn s26 "")" "the 5% warning came twice"
pass

case_name="a jump straight past 8% warns once, at 5%, and the 8% warning never follows"
f=$(main_transcript s27)
assistant_entry "$f" claude-opus-5-5 260000 false
expect_warning "$(run warn s27 "")" 260000/300000
assistant_entry "$f" claude-opus-5-5 262000 false
expect_nothing "$(run warn s27 "")" "warned again after the tightest level"
pass

case_name="CONTEXT_GUARD_RESERVE moves the compaction point"
assistant_entry "$(main_transcript s28)" claude-opus-5-5 230000 false
out=$(hook_input s28 "" | CONTEXT_GUARD_RESERVE=50000 bash "$hook" warn | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_warning "$out" 230000/300000
case "$out" in *"about 250000 tokens"*) ;; *) fail "reserve not applied: ${out:0:300}" ;; esac
pass

case_name="after a warning, a count below half the compaction point means compaction: warns again later"
f=$(main_transcript s29)
assistant_entry "$f" claude-opus-5-5 245000 false
expect_warning "$(run warn s29 "")" 245000/300000
assistant_entry "$f" claude-opus-5-5 30000 false
expect_nothing "$(run warn s29 "")" "a compacted main conversation was warned"
assistant_entry "$f" claude-opus-5-5 246000 false
expect_warning "$(run warn s29 "")" 246000/300000
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

# --- A subagent on another family: window grows, never shrinks, restore once ----------------

case_name="other family at 150000: 200000"
f=$(agent_transcript s1 a3)
assistant_entry "$f" claude-sonnet-5 155000 true
expect_warning "$(run warn s1 a3)" 155000/200000
pass

case_name="other family at 250000: window becomes 1000000"
assistant_entry "$f" claude-sonnet-5 250000 true
expect_nothing "$(run warn s1 a3)" "25% of 1000000 is short of every warning point"
[ "$(window_of s1 a3)" = 1000000 ] || fail "window is '$(window_of s1 a3)', expected 1000000"
pass

case_name="other family at 900000: warns against 1000000"
assistant_entry "$f" claude-sonnet-5 900000 true
expect_warning "$(run warn s1 a3)" 900000/1000000
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

# --- Whose compaction a restore is ------------------------------------------------------------

# A subagent's PreCompact and SessionStart(compact) arrive as the main conversation's do: no
# agent_id, the main transcript_path. So every PreCompact and restore below runs with agent "",
# and a subagent's compaction is modelled by what it leaves in its own transcript: a
# compact_boundary line.
compact() { # $1 session, $2 trigger (auto|manual): a PreCompact input as Claude Code sends it
  hook_input "$1" "" | jq -c --arg t "$2" '.trigger = $t' | bash "$hook" precompact >/dev/null
}
compact_boundary() { # $1 transcript, $2 uuid, $3 isSidechain (default true: a subagent's transcript)
  # The line a compaction appends to its agent's transcript.
  jq -nc --arg u "$2" --argjson s "${3:-true}" '{type: "system", subtype: "compact_boundary", isSidechain: $s, uuid: $u,
                       content: "Conversation compacted", compactMetadata: {trigger: "auto"}}' >>"$1"
}

case_name="subagent compacted with no warning before it: its next warn restores its own state, once per compaction"
f=$(agent_transcript s37 a1)
assistant_entry "$f" claude-opus-5-5 100000 true
expect_nothing "$(run warn s37 a1)" "50% of 200000 is short of every warning point"
mkdir -p "$CONTEXT_GUARD_DIR/s37/main"
printf '%s\n' "MAIN-STATE-S37" >"$CONTEXT_GUARD_DIR/s37/main/STATE.md"
printf '%s\n' "AGENT-STATE-S37" >"$CONTEXT_GUARD_DIR/s37/agents/a1/STATE.md"
compact_boundary "$f" boundary-1
assistant_entry "$f" claude-opus-5-5 20000 true
out=$(run warn s37 a1)
case "$out" in
  *"was just compacted"*AGENT-STATE-S37*) ;;
  *) fail "expected the subagent's own STATE.md, got: ${out:0:300}" ;;
esac
case "$out" in *MAIN-STATE-S37*) fail "the subagent's restore handed it the main conversation's STATE.md" ;; esac
case "$out" in *"this subagent (agent_id a1)"*) ;; *) fail "the restore does not name its owner: ${out:0:200}" ;; esac
# The same boundary does not restore again: the warning points work as before.
assistant_entry "$f" claude-opus-5-5 160000 true
expect_warning "$(run warn s37 a1)" 160000/200000
printf '%s\n' "AGENT-STATE-S37-B" >"$CONTEXT_GUARD_DIR/s37/agents/a1/STATE.md"
compact_boundary "$f" boundary-2
assistant_entry "$f" claude-opus-5-5 21000 true
out=$(run warn s37 a1)
case "$out" in *AGENT-STATE-S37-B*) ;; *) fail "a second compaction did not restore: ${out:0:300}" ;; esac
# A parallel tool call that read boundary before this one renamed it sees boundary-2 as new:
# the restoring.<uuid> claim keeps it from handing the state back a second time.
printf '%s\n' "AGENT-STATE-S37-C" >"$CONTEXT_GUARD_DIR/s37/agents/a1/STATE.md"
printf '%s' boundary-1 >"$CONTEXT_GUARD_DIR/s37/agents/a1/boundary"
expect_nothing "$(run warn s37 a1)" "a boundary already claimed was restored again"
pass

# mv wrapped to fail onto a state directory's boundary file only: the write a full disk refuses.
no_boundary_mv=$tmp/no-boundary-mv
mkdir -p "$no_boundary_mv"
printf '%s\n' '#!/usr/bin/env bash' 'case "${!#}" in */boundary) exit 1 ;; esac' \
  "exec '$(command -v mv)' \"\$@\"" >"$no_boundary_mv/mv"
chmod +x "$no_boundary_mv/mv"

case_name="a subagent restore whose boundary write fails: its claim is dropped and its next warn restores; older claims are removed"
f=$(agent_transcript s49 a1)
a=$CONTEXT_GUARD_DIR/s49/agents/a1
assistant_entry "$f" claude-opus-5-5 100000 true
expect_nothing "$(run warn s49 a1)" "50% of 200000 is short of every warning point"
printf '%s\n' "AGENT-STATE-S49" >"$a/STATE.md"
compact_boundary "$f" boundary-1
assistant_entry "$f" claude-opus-5-5 20000 true
out=$(hook_input s49 a1 | PATH=$no_boundary_mv:$PATH bash "$hook" warn | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_nothing "$out" "restored though the boundary could not be recorded (the next warn would restore it again)"
[ ! -e "$a/restoring.boundary-1" ] || fail "the claim of a boundary that could not be recorded was kept"
out=$(run warn s49 a1)
case "$out" in *AGENT-STATE-S49*) ;; *) fail "the warn after a failed boundary write did not restore: ${out:0:300}" ;; esac
[ "$(cat "$a/boundary")" = boundary-1 ] || fail "boundary holds '$(cat "$a/boundary")', expected boundary-1"
# Each recorded boundary removes the claims older than the one it replaced.
for u in boundary-2 boundary-3; do
  compact_boundary "$f" "$u"
  assistant_entry "$f" claude-opus-5-5 20000 true
  run warn s49 a1 >/dev/null
done
claims=$(cd "$a" && echo restoring.*)
[ "$claims" = "restoring.boundary-2 restoring.boundary-3" ] \
  || fail "claims left: '$claims', expected the current boundary's and the one it replaced"
pass

case_name="a subagent's compaction, as the harness reports it: nothing from main/, main's warn lock kept, the subagent gets its own state"
assistant_entry "$(main_transcript s38)" claude-opus-5-5 50000 false
expect_nothing "$(run warn s38 "")" "5% of 1000000 is short of every warning point"
printf '%s\n' "MAIN-STATE-S38" >"$CONTEXT_GUARD_DIR/s38/main/STATE.md"
mkdir "$CONTEXT_GUARD_DIR/s38/main/warned.8" # a lock as a warning leaves it: not the restore's to clear
f=$(agent_transcript s38 a1)
assistant_entry "$f" claude-sonnet-5 180000 true # not the main session's id: 200000
expect_warning "$(run warn s38 a1)" 180000/200000
printf '%s\n' "AGENT-STATE-S38" >"$CONTEXT_GUARD_DIR/s38/agents/a1/STATE.md"
compact s38 auto
compact_boundary "$f" boundary-1
expect_nothing "$(run restore s38 "")" "a subagent's compaction injected main/'s state"
[ -d "$CONTEXT_GUARD_DIR/s38/main/warned.8" ] || fail "a subagent's compaction cleared main's warn lock"
assistant_entry "$f" claude-sonnet-5 30000 true
out=$(run warn s38 a1)
case "$out" in *AGENT-STATE-S38*) ;; *) fail "expected the subagent's own STATE.md, got: ${out:0:300}" ;; esac
case "$out" in *MAIN-STATE-S38*) fail "the subagent's restore handed it the main conversation's STATE.md" ;; esac
# The subagent's boundary is in its own transcript, and a sidechain line in the main one (an
# older transcript layout) is not the main conversation's: no main restore from either.
compact_boundary "$(main_transcript s38)" sidechain-1 true
expect_nothing "$(run warn s38 "")" "a subagent's compaction restored the main conversation on its next warn"
pass

# A subagent compacts at any main count: SessionStart(compact) adds nothing at any of them.
case_name="a subagent's compaction while the main count is past half its compaction point but not near it: nothing from main/"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
assistant_entry "$(main_transcript s40)" claude-opus-5-5 200000 false # trigger 267000
expect_nothing "$(run warn s40 "")" "67000 left of a 300000 window is short of every warning point"
[ "$(cat "$CONTEXT_GUARD_DIR/s40/main/window")" = 300000 ] || fail "main/window is not 300000"
printf '%s\n' "MAIN-STATE-S40" >"$CONTEXT_GUARD_DIR/s40/main/STATE.md"
mkdir "$CONTEXT_GUARD_DIR/s40/main/warned.8"
f=$(agent_transcript s40 a1)
assistant_entry "$f" claude-sonnet-5 180000 true
expect_warning "$(run warn s40 a1)" 180000/200000
printf '%s\n' "AGENT-STATE-S40" >"$CONTEXT_GUARD_DIR/s40/agents/a1/STATE.md"
compact s40 auto
compact_boundary "$f" boundary-1
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
expect_nothing "$(run restore s40 "")" "a subagent's compaction at a main count of 200000 injected main/'s state"
[ -d "$CONTEXT_GUARD_DIR/s40/main/warned.8" ] || fail "a subagent's compaction cleared main's warn lock"
assistant_entry "$f" claude-sonnet-5 30000 true
out=$(run warn s40 a1)
case "$out" in *AGENT-STATE-S40*) ;; *) fail "expected the subagent's own STATE.md, got: ${out:0:300}" ;; esac
pass

case_name="the main conversation's auto compaction near its compaction point: nothing at SessionStart, its next warn restores main/'s state and snapshot, named as its, once"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
m=$(main_transcript s39)
assistant_entry "$m" claude-opus-5-5 266000 false # 1000 short of 267000
expect_warning "$(run warn s39 "")" 266000/300000
printf '%s\n' "MAIN-STATE-S39" >"$CONTEXT_GUARD_DIR/s39/main/STATE.md"
compact s39 auto
expect_nothing "$(run restore s39 "")" "SessionStart(compact) injected main/'s state"
[ -d "$CONTEXT_GUARD_DIR/s39/main/warned.5" ] || fail "SessionStart(compact) cleared main's warn lock"
compact_boundary "$m" boundary-1 false
assistant_entry "$m" claude-opus-5-5 20000 false
out=$(run warn s39 "")
case "$out" in
  *"the main conversation was just compacted"*"treat it as your own notes"*MAIN-STATE-S39*"Pre-compaction snapshot"*) ;;
  *) fail "expected main/'s STATE.md and snapshot from the warn after the boundary, named as the main conversation's: ${out:0:300}" ;;
esac
case "$out" in *"if you are a subagent"*) fail "the main conversation's restore still addresses a subagent: ${out:0:300}" ;; esac
[ -e "$CONTEXT_GUARD_DIR/s39/main/warned.5" ] && fail "the main conversation's restore kept its warn lock"
expect_nothing "$(run warn s39 "")" "the same boundary restored again"
# The next compaction's boundary restores the STATE.md written since.
printf '%s\n' "MAIN-STATE-S39-B" >"$CONTEXT_GUARD_DIR/s39/main/STATE.md"
compact s39 auto
expect_nothing "$(run restore s39 "")" "SessionStart(compact) injected main/'s state the second time"
compact_boundary "$m" boundary-2 false
assistant_entry "$m" claude-opus-5-5 15000 false
out=$(run warn s39 "")
case "$out" in *MAIN-STATE-S39-B*) ;; *) fail "the second compaction was not restored by the warn after its boundary: ${out:0:300}" ;; esac
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

# A last turn that ends in a large tool result puts the main conversation's own compaction well
# short of its compaction point: the restore does not depend on the count it compacted at.
case_name="the main conversation's auto compaction 17000 short of its compaction point: nothing at SessionStart, its next warn restores main/'s state and snapshot, once"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
assistant_entry "$(main_transcript s41)" claude-opus-5-5 250000 false # 17000 short of 267000
expect_warning "$(run warn s41 "")" 250000/300000
[ -d "$CONTEXT_GUARD_DIR/s41/main/warned.8" ] || fail "no 8% warn lock"
printf '%s\n' "MAIN-STATE-S41" >"$CONTEXT_GUARD_DIR/s41/main/STATE.md"
compact s41 auto
expect_nothing "$(run restore s41 "")" "a compaction 17000 short of the compaction point was restored at SessionStart"
compact_boundary "$(main_transcript s41)" boundary-1 false
assistant_entry "$(main_transcript s41)" claude-opus-5-5 20000 false
out=$(run warn s41 "")
case "$out" in
  *"the main conversation was just compacted"*MAIN-STATE-S41*"Pre-compaction snapshot"*) ;;
  *) fail "expected main/'s STATE.md and snapshot from the warn after the boundary: ${out:0:300}" ;;
esac
[ -e "$CONTEXT_GUARD_DIR/s41/main/warned.8" ] && fail "the main conversation's restore kept its warn lock"
expect_nothing "$(run warn s41 "")" "the same boundary restored again"
# A parallel tool call that read main/boundary before this one renamed it sees boundary-1 as new:
# the restoring.<uuid> claim keeps the main conversation's state from being handed back twice.
printf '%s\n' "MAIN-STATE-S41-B" >"$CONTEXT_GUARD_DIR/s41/main/STATE.md"
printf '%s' "" >"$CONTEXT_GUARD_DIR/s41/main/boundary" # what the first main warn recorded
expect_nothing "$(run warn s41 "")" "a main boundary already claimed was restored again"
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

case_name="a last turn's output counts toward the main count; a manual compaction: nothing at SessionStart, its next warn restores"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
m=$(main_transcript s42)
assistant_entry "$m" claude-opus-5-5 262000 false 3000 # 262000 in and 3000 out: 265000
expect_warning "$(run warn s42 "")" 265000/300000
printf '%s\n' "MAIN-STATE-S42" >"$CONTEXT_GUARD_DIR/s42/main/STATE.md"
compact s42 manual
expect_nothing "$(run restore s42 "")" "a manual compaction was restored at SessionStart"
compact_boundary "$m" boundary-1 false
assistant_entry "$m" claude-opus-5-5 20000 false
out=$(run warn s42 "")
case "$out" in *MAIN-STATE-S42*) ;; *) fail "the manual compaction was not restored by the warn after its boundary: ${out:0:300}" ;; esac
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

case_name="a subagent's compaction while the main count is near its compaction point: nothing from main/, main's warn lock kept; the main conversation's own compaction is restored by the warn after its boundary"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
m=$(main_transcript s43)
assistant_entry "$m" claude-opus-5-5 265000 false # 2000 short of 267000
expect_warning "$(run warn s43 "")" 265000/300000
printf '%s\n' "MAIN-STATE-S43-A" >"$CONTEXT_GUARD_DIR/s43/main/STATE.md"
f=$(agent_transcript s43 a1)
assistant_entry "$f" claude-sonnet-5 180000 true
expect_warning "$(run warn s43 a1)" 180000/200000
compact s43 auto # the subagent's PreCompact: no agent_id, the main transcript
compact_boundary "$f" boundary-a1
expect_nothing "$(run restore s43 "")" "a subagent's compaction at a main count of 265000 injected main/'s state"
[ -d "$CONTEXT_GUARD_DIR/s43/main/warned.5" ] || fail "a subagent's compaction cleared main's warn lock"
expect_nothing "$(run warn s43 "")" "a subagent's compaction restored the main conversation on its next warn"
printf '%s\n' "MAIN-STATE-S43-B" >"$CONTEXT_GUARD_DIR/s43/main/STATE.md"
compact s43 auto # the main conversation's own
expect_nothing "$(run restore s43 "")" "the main conversation's compaction was restored at SessionStart"
compact_boundary "$m" boundary-1 false
assistant_entry "$m" claude-opus-5-5 20000 false
out=$(run warn s43 "")
case "$out" in *MAIN-STATE-S43-B*) ;; *) fail "the main conversation's compaction was not restored by the warn after its boundary: ${out:0:300}" ;; esac
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

case_name="a compaction already in the main transcript at the main conversation's first warn (a resume): not restored; a later one is"
m=$(main_transcript s44)
compact_boundary "$m" old-boundary false
assistant_entry "$m" claude-opus-5-5 30000 false
mkdir -p "$CONTEXT_GUARD_DIR/s44/main"
printf '%s\n' "MAIN-STATE-S44" >"$CONTEXT_GUARD_DIR/s44/main/STATE.md"
expect_nothing "$(run warn s44 "")" "a compaction from before the hook's first call was restored"
compact_boundary "$m" new-boundary false
assistant_entry "$m" claude-opus-5-5 10000 false
out=$(run warn s44 "")
case "$out" in *MAIN-STATE-S44*) ;; *) fail "a compaction after the first call was not restored: ${out:0:300}" ;; esac
pass

# The main conversation can end its turn with no tool call (waiting on background agents), so a
# subagent's PreCompact and SessionStart(compact) can come between the main conversation's
# compaction and the warn that finds its boundary.
case_name="a subagent's compaction between the main conversation's compaction and its next warn: that warn restores main/'s state, once"
user_settings '{"modelSettings": {"claude-opus-5-5": {"autoCompactWindow": 300000}}}'
m=$(main_transcript s45)
assistant_entry "$m" claude-opus-5-5 265000 false
run warn s45 "" >/dev/null
printf '%s\n' "MAIN-STATE-S45" >"$CONTEXT_GUARD_DIR/s45/main/STATE.md"
compact s45 auto
expect_nothing "$(run restore s45 "")" "the main conversation's compaction was restored at SessionStart"
compact_boundary "$m" boundary-1 false
assistant_entry "$m" claude-opus-5-5 20000 false
compact s45 auto # a subagent's PreCompact: no agent_id, the main transcript
expect_nothing "$(run restore s45 "")" "a subagent's compaction at a main count of 20000 was restored at SessionStart"
out=$(run warn s45 "")
case "$out" in *MAIN-STATE-S45*) ;; *) fail "a subagent's compaction in between kept the main conversation's restore back: ${out:0:300}" ;; esac
expect_nothing "$(run warn s45 "")" "the main conversation's compaction was handed back twice"
rm -f "$CLAUDE_CONFIG_DIR/settings.json"
pass

# Which snapshot a PreCompact left is told by its trigger: the main conversation's compactions
# below are manual, the subagents' auto.
snapshot_trigger() { # $1 session; prints the trigger main/'s snapshot names
  sed -n '1s/^# Pre-compaction snapshot (\([a-z]*\) compaction.*/\1/p' "$CONTEXT_GUARD_DIR/$1/main/precompact-snapshot.md" 2>/dev/null
}

case_name="a subagent's PreCompact while a main restore is owed keeps the main compaction's snapshot for that restore; otherwise every PreCompact rewrites it"
m=$(main_transcript s52)
assistant_entry "$m" claude-opus-5-5 30000 false
run warn s52 "" >/dev/null # the first main warn: it records that there is no boundary yet
compact s52 auto # a subagent's, no main restore owed
[ "$(snapshot_trigger s52)" = auto ] || fail "no snapshot written: '$(snapshot_trigger s52)'"
compact s52 manual # the main conversation's: its boundary is not in the transcript yet
[ "$(snapshot_trigger s52)" = manual ] || fail "the main compaction's PreCompact did not rewrite a subagent's snapshot"
compact_boundary "$m" boundary-1 false
compact s52 auto # a subagent's, the main restore owed (the recorded boundary is empty)
[ "$(snapshot_trigger s52)" = manual ] || fail "a subagent's PreCompact replaced the snapshot the owed main restore hands back"
assistant_entry "$m" claude-opus-5-5 20000 false
out=$(run warn s52 "")
case "$out" in *"Pre-compaction snapshot (manual compaction"*) ;; *) fail "the main restore did not hand back the main compaction's snapshot: ${out:0:300}" ;; esac
compact s52 auto # restored: no main restore owed
[ "$(snapshot_trigger s52)" = auto ] || fail "a PreCompact after the main restore did not rewrite the snapshot"
# The same with a uuid recorded: the next main compaction.
compact s52 manual
compact_boundary "$m" boundary-2 false
compact s52 auto
[ "$(snapshot_trigger s52)" = manual ] || fail "a subagent's PreCompact replaced the snapshot the second owed main restore hands back"
assistant_entry "$m" claude-opus-5-5 21000 false
out=$(run warn s52 "")
case "$out" in *"Pre-compaction snapshot (manual compaction"*) ;; *) fail "the second main restore did not hand back the main compaction's snapshot: ${out:0:300}" ;; esac
compact s52 auto
[ "$(snapshot_trigger s52)" = auto ] || fail "a PreCompact after the second main restore did not rewrite the snapshot"
pass

case_name="a snapshot written before main/boundary's uuid was recorded (or before the first main warn) is not kept for an owed main restore"
m=$(main_transcript s53)
compact s53 manual # no boundary file yet
[ "$(snapshot_trigger s53)" = manual ] || fail "no snapshot written: '$(snapshot_trigger s53)'"
assistant_entry "$m" claude-opus-5-5 30000 false
run warn s53 "" >/dev/null # the first main warn
# A main compaction whose PreCompact did not run on this state (the guard switched off for it).
compact_boundary "$m" boundary-1 false
compact s53 auto
[ "$(snapshot_trigger s53)" = auto ] || fail "a snapshot from before the first main warn was kept for the owed main restore"
# Nor is one written before the boundary main/boundary now holds was recorded.
assistant_entry "$m" claude-opus-5-5 20000 false
run warn s53 "" >/dev/null # restores boundary-1 and records it
compact_boundary "$m" boundary-2 false # again with no PreCompact on this state
compact s53 manual
[ "$(snapshot_trigger s53)" = manual ] || fail "a snapshot from before the recorded boundary was kept for the owed main restore"
pass

# While the main conversation waits on background agents, its transcript takes lines that are
# not its turns (attachments, queue operations as the agents report): its compaction's boundary
# can be thousands of lines back by its next warn, and is restored all the same.
non_turn_lines() { # $1 transcript, $2 count: lines with no assistant entry and no boundary
  jq -nc --argjson n "$2" 'range(1; $n + 1) | if . % 2 == 0 then {type: "queue-operation", operation: "enqueue", content: "agent \(.) done"}
                           else {type: "attachment", attachment: {type: "task_status", content: "r\(.)"}} end' >>"$1"
}

case_name="the main conversation's compaction boundary 6000 lines back at its next warn: that warn restores main/'s state, once"
m=$(main_transcript s50)
assistant_entry "$m" claude-opus-5-5 30000 false
run warn s50 "" >/dev/null # the first main warn: it records that there is no boundary yet
printf '%s\n' "MAIN-STATE-S50" >"$CONTEXT_GUARD_DIR/s50/main/STATE.md"
compact s50 auto
compact_boundary "$m" boundary-1 false
assistant_entry "$m" claude-opus-5-5 20000 false
non_turn_lines "$m" 6000
out=$(run warn s50 "")
case "$out" in *MAIN-STATE-S50*) ;; *) fail "a boundary 6000 lines back was not restored: ${out:0:300}" ;; esac
[ "$(cat "$CONTEXT_GUARD_DIR/s50/main/boundary")" = boundary-1 ] \
  || fail "boundary holds '$(cat "$CONTEXT_GUARD_DIR/s50/main/boundary")', expected boundary-1"
expect_nothing "$(run warn s50 "")" "the main conversation's compaction was handed back twice"
pass

case_name="a subagent's compaction boundary 600 lines back at its next warn: that warn restores its own state"
f=$(agent_transcript s51 a1)
assistant_entry "$f" claude-opus-5-5 100000 true
expect_nothing "$(run warn s51 a1)" "50% of 200000 is short of every warning point"
printf '%s\n' "AGENT-STATE-S51" >"$CONTEXT_GUARD_DIR/s51/agents/a1/STATE.md"
compact_boundary "$f" boundary-1
assistant_entry "$f" claude-opus-5-5 20000 true
non_turn_lines "$f" 600
out=$(run warn s51 a1)
case "$out" in *AGENT-STATE-S51*) ;; *) fail "a subagent boundary 600 lines back was not restored: ${out:0:300}" ;; esac
pass

# --- STATE.md handed back once per write -----------------------------------------------------

main_compaction() { # $1 session, $2 boundary uuid: a main compaction as its next warn finds it
  # PreCompact, the boundary line, a small count; prints what that warn emitted.
  local m
  m=$(main_transcript "$1")
  compact "$1" manual
  compact_boundary "$m" "$2" false
  assistant_entry "$m" claude-opus-5-5 20000 false
  run warn "$1" ""
}

case_name="restore hands STATE.md back once per write"
assistant_entry "$(main_transcript s4)" claude-opus-5-5 30000 false
run warn s4 "" >/dev/null # the first main warn: it records that there is no boundary yet
state=$CONTEXT_GUARD_DIR/s4/main/STATE.md
printf '%s\n' "STATE-MARKER-1" >"$state"
out=$(main_compaction s4 boundary-1)
case "$out" in *STATE-MARKER-1*) ;; *) fail "first restore lacks STATE.md: ${out:0:200}" ;; esac
out=$(main_compaction s4 boundary-2)
case "$out" in *STATE-MARKER*) fail "second restore without a rewrite handed STATE.md back again" ;; esac
printf '%s\n' "STATE-MARKER-2" >"$state"
out=$(main_compaction s4 boundary-3)
case "$out" in *STATE-MARKER-2*) ;; *) fail "restore after a rewrite lacks STATE.md: ${out:0:200}" ;; esac
out=$(main_compaction s4 boundary-4)
case "$out" in *STATE-MARKER*) fail "restore after the rewrite was handed back handed it back again" ;; esac
touch -t 209901010000 "$state" # the same content written again, later
out=$(main_compaction s4 boundary-5)
case "$out" in *STATE-MARKER-2*) ;; *) fail "restore after a same-content rewrite lacks STATE.md: ${out:0:200}" ;; esac
pass

case_name="restore of a STATE.md over 20000 bytes says it was cut and where the rest is"
assistant_entry "$(main_transcript s21)" claude-opus-5-5 30000 false
run warn s21 "" >/dev/null
state=$CONTEXT_GUARD_DIR/s21/main/STATE.md
{ printf 'STATE-HEAD\n'; head -c 25000 /dev/zero | tr '\0' 'x'; printf '\nSTATE-TAIL\n'; } >"$state"
out=$(main_compaction s21 boundary-1)
case "$out" in *STATE-HEAD*) ;; *) fail "restore lacks the start of STATE.md" ;; esac
case "$out" in *STATE-TAIL*) fail "restore handed back more than 20000 bytes" ;; esac
case "$out" in *"cut at 20000 bytes"*"$state"*) ;; *) fail "restore does not say STATE.md was cut: ${out: -200}" ;; esac
pass

# --- Off switch, private state, pruning -------------------------------------------------------

case_name="CONTEXT_GUARD=off|0|false|no: nothing printed, no state written"
for v in off 0 false no; do
  s=s22-$v
  assistant_entry "$(main_transcript $s)" claude-opus-5-5 950000 false
  out=$(hook_input $s "" | CONTEXT_GUARD=$v bash "$hook" warn)
  expect_nothing "$out" "CONTEXT_GUARD=$v still warned"
  [ ! -e "$CONTEXT_GUARD_DIR/$s" ] || fail "CONTEXT_GUARD=$v still wrote $CONTEXT_GUARD_DIR/$s"
done
pass

case_name="state directories are private: 700, files 600, whatever the umask"
assistant_entry "$(main_transcript s23)" claude-opus-5-5 950000 false
out=$(hook_input s23 "" | (umask 000; bash "$hook" warn) | jq -r '.hookSpecificOutput.additionalContext // empty')
expect_warning "$out" 950000/1000000
for d in "$CONTEXT_GUARD_DIR" "$CONTEXT_GUARD_DIR/s23" "$CONTEXT_GUARD_DIR/s23/main"; do
  [ "$(ls -ld "$d" | cut -c1-10)" = drwx------ ] || fail "$d is $(ls -ld "$d" | cut -c1-10), expected drwx------"
done
[ "$(ls -l "$CONTEXT_GUARD_DIR/s23/main/window" | cut -c1-10)" = -rw------- ] \
  || fail "window is $(ls -l "$CONTEXT_GUARD_DIR/s23/main/window" | cut -c1-10), expected -rw-------"
pass

expect_empty_dir() { # $1 dir, $2 what was symlinked to it
  [ -z "$(ls -A "$1")" ] || fail "created through a symlinked $2: $(ls -A "$1" | tr '\n' ' ')"
}

run_err() { # as run, its stderr in $hook_err
  hook_input "$2" "$3" | bash "$hook" "$1" 2>"$hook_err" | jq -r '.hookSpecificOutput.additionalContext // empty'
}
expect_refused() { # $1 the reason the stderr line must give, $2 what ran
  [ "$(cat "$hook_err")" = "context-guard: $1; guard disabled" ] \
    || fail "$2: expected 'context-guard: $1; guard disabled' on stderr, got '$(head -c 300 "$hook_err")'"
}

case_name="a state directory that is a symlink is refused: nothing printed but the reason on stderr, nothing written through it"
elsewhere=$tmp/elsewhere
mkdir -p "$elsewhere/main"
printf '%s\n' "PLANTED-STATE" >"$elsewhere/main/STATE.md"
ln -s "$elsewhere" "$CONTEXT_GUARD_DIR/s24"
assistant_entry "$(main_transcript s24)" claude-opus-5-5 950000 false
expect_nothing "$(run_err warn s24 "")" "warned through a symlinked session directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s24 is a symlink" "warn"
expect_nothing "$(run_err restore s24 "")" "restore injected a STATE.md from a symlinked session directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s24 is a symlink" "restore"
[ "$(ls "$elsewhere/main")" = STATE.md ] || fail "wrote through the symlink: $(ls "$elsewhere/main" | tr '\n' ' ')"
rm -f "$CONTEXT_GUARD_DIR/s24"
pass

# The directories below the link do not exist yet: the hook must not create them in its target.
case_name="a symlinked session directory: nothing created in its target"
elsewhere=$tmp/elsewhere-session
mkdir -p "$elsewhere"
ln -s "$elsewhere" "$CONTEXT_GUARD_DIR/s30"
assistant_entry "$(main_transcript s30)" claude-opus-5-5 950000 false
assistant_entry "$(agent_transcript s30 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run_err warn s30 "")" "warned through a symlinked session directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s30 is a symlink" "warn"
expect_nothing "$(run_err restore s30 "")" "restored through a symlinked session directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s30 is a symlink" "restore"
expect_nothing "$(run_err warn s30 a1)" "a subagent warned through a symlinked session directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s30 is a symlink" "a subagent's warn"
expect_empty_dir "$elsewhere" "session directory"
rm -f "$CONTEXT_GUARD_DIR/s30"
pass

case_name="a symlinked agents directory: nothing created in its target"
elsewhere=$tmp/elsewhere-agents
mkdir -p "$elsewhere" "$CONTEXT_GUARD_DIR/s31"
ln -s "$elsewhere" "$CONTEXT_GUARD_DIR/s31/agents"
assistant_entry "$(main_transcript s31)" claude-opus-5-5 150000 false
assistant_entry "$(agent_transcript s31 a1)" claude-opus-5-5 150000 true
expect_nothing "$(run_err warn s31 a1)" "a subagent warned through a symlinked agents directory"
expect_refused "state directory $CONTEXT_GUARD_DIR/s31/agents is a symlink" "a subagent's warn"
expect_empty_dir "$elsewhere" "agents directory"
rm -f "$CONTEXT_GUARD_DIR/s31/agents"
pass

case_name="a symlinked root: nothing created in its target"
elsewhere=$tmp/elsewhere-root
mkdir -p "$elsewhere"
ln -s "$elsewhere" "$tmp/guard-link"
assistant_entry "$(main_transcript s32)" claude-opus-5-5 950000 false
for r in "$tmp/guard-link" "$tmp/guard-link/" "$tmp/guard-link//"; do
  expect_nothing "$(CONTEXT_GUARD_DIR=$r run_err warn s32 "")" "warned through a symlinked root $r"
  expect_refused "state directory $tmp/guard-link is a symlink" "warn with root $r"
  expect_nothing "$(CONTEXT_GUARD_DIR=$r run_err restore s32 "")" "restored through a symlinked root $r"
  expect_refused "state directory $tmp/guard-link is a symlink" "restore with root $r"
  hook_input s32 "" | CONTEXT_GUARD_DIR=$r bash "$hook" start >/dev/null 2>"$hook_err"
  expect_refused "state directory $tmp/guard-link is a symlink" "start with root $r"
  expect_empty_dir "$elsewhere" "root $r"
done
rm -f "$tmp/guard-link"
pass

case_name="a missing root is created with its missing parents, each state directory 700"
r=$tmp/fresh/parent/guard
assistant_entry "$(main_transcript s33)" claude-opus-5-5 950000 false
expect_warning "$(CONTEXT_GUARD_DIR=$r run_err warn s33 "")" 950000/1000000
expect_no_stderr
for d in "$r" "$r/s33" "$r/s33/main"; do
  [ "$(ls -ld "$d" | cut -c1-10)" = drwx------ ] || fail "$d is $(ls -ld "$d" | cut -c1-10), expected drwx------"
done
pass

# The root's parent is not chmod (it may be /tmp): one another user could rename the root in,
# or a symlink, would let them swap the checked root for a tree of their own afterwards. A test
# cannot make a directory another user owns, so these cases pin the symlink and mode checks.
case_name="a symlinked parent of the root: nothing printed but the reason on stderr, nothing created in its target"
elsewhere=$tmp/elsewhere-parent
mkdir -p "$elsewhere"
ln -s "$elsewhere" "$tmp/parent-link"
assistant_entry "$(main_transcript s46)" claude-opus-5-5 950000 false
for r in "$tmp/parent-link/guard" "$tmp/parent-link//guard"; do
  why="$tmp/parent-link, the parent of the state root $r, is a symlink"
  expect_nothing "$(CONTEXT_GUARD_DIR=$r run_err warn s46 "")" "warned under a symlinked parent $r"
  expect_refused "$why" "warn with root $r"
  expect_nothing "$(CONTEXT_GUARD_DIR=$r run_err restore s46 "")" "restored under a symlinked parent $r"
  expect_refused "$why" "restore with root $r"
  hook_input s46 "" | CONTEXT_GUARD_DIR=$r bash "$hook" start >/dev/null 2>"$hook_err"
  expect_refused "$why" "start with root $r"
  expect_empty_dir "$elsewhere" "parent $r"
done
rm -f "$tmp/parent-link"
pass

# 775 is what mkdir makes under umask 002 (user-private groups), 2775 a setgid team directory.
case_name="a parent of the root its group or others can write to, not sticky: nothing printed but the reason on stderr, no root created"
assistant_entry "$(main_transcript s47)" claude-opus-5-5 950000 false
for mode in 777 775 2775 770 707 720 702; do
  p=$tmp/open-$mode
  mkdir -p "$p"
  chmod "$mode" "$p"
  why="$p, the parent of the state root $p/guard, is writable by its group or others and not sticky ($(ls -ldn "$p" | cut -d' ' -f1))"
  expect_nothing "$(CONTEXT_GUARD_DIR=$p/guard run_err warn s47 "")" "warned under a parent of mode $mode"
  expect_refused "$why" "warn under a parent of mode $mode"
  hook_input s47 "" | CONTEXT_GUARD_DIR=$p/guard bash "$hook" start >/dev/null 2>"$hook_err"
  expect_refused "$why" "start under a parent of mode $mode"
  [ ! -e "$p/guard" ] || fail "created the root under a parent of mode $mode"
done
pass

case_name="a parent of the root that is a file: nothing printed but the reason on stderr"
: >"$tmp/file-parent"
assistant_entry "$(main_transcript s54)" claude-opus-5-5 950000 false
expect_nothing "$(CONTEXT_GUARD_DIR=$tmp/file-parent/guard run_err warn s54 "")" "warned under a parent that is a file"
expect_refused "$tmp/file-parent, the parent of the state root $tmp/file-parent/guard, is not a directory and could not be created as one" \
  "warn under a parent that is a file"
pass

case_name="a sticky world-writable parent (as /tmp is), a 755 one and a relative root: the guard works, nothing on stderr"
for mode in 1777 755; do
  p=$tmp/parent-$mode
  mkdir -p "$p"
  chmod "$mode" "$p"
  assistant_entry "$(main_transcript s48-$mode)" claude-opus-5-5 950000 false
  expect_warning "$(CONTEXT_GUARD_DIR=$p/guard run_err warn s48-$mode "")" 950000/1000000
  expect_no_stderr
done
mkdir -p "$tmp/rel-cwd"
chmod 700 "$tmp/rel-cwd" # the parent of a relative root is the working directory
assistant_entry "$(main_transcript s48-rel)" claude-opus-5-5 950000 false
expect_warning "$(cd "$tmp/rel-cwd" && CONTEXT_GUARD_DIR=guard run_err warn s48-rel "")" 950000/1000000
expect_no_stderr
[ -d "$tmp/rel-cwd/guard/s48-rel/main" ] || fail "the relative root was not created in the working directory"
pass

case_name="start prunes sessions with nothing written for 7 days, keeps a live one and its own"
mkdir -p "$CONTEXT_GUARD_DIR/stale/main" "$CONTEXT_GUARD_DIR/live/main" "$CONTEXT_GUARD_DIR/s25/main"
for p in "$CONTEXT_GUARD_DIR/stale" "$CONTEXT_GUARD_DIR/stale/main" "$CONTEXT_GUARD_DIR/live" \
         "$CONTEXT_GUARD_DIR/live/main" "$CONTEXT_GUARD_DIR/s25" "$CONTEXT_GUARD_DIR/s25/main"; do
  touch -t 202001010000 "$p"
done
: >"$CONTEXT_GUARD_DIR/live/main/live" # written now, inside directories that look old
jq -nc --arg s s25 --arg t "$(main_transcript s25)" '{session_id: $s, transcript_path: $t}' | bash "$hook" start >/dev/null
[ ! -e "$CONTEXT_GUARD_DIR/stale" ] || fail "stale session directory not pruned"
[ -d "$CONTEXT_GUARD_DIR/live" ] || fail "a session with a recent write was pruned"
[ -d "$CONTEXT_GUARD_DIR/s25" ] || fail "start pruned its own session directory"
pass

# --- Snapshot without GNU-only tools ---------------------------------------------------------

case_name="precompact without tac, sed and awk: each edited path once, at most 30, oldest first"
bin=$tmp/bin
mkdir -p "$bin"
for t in bash jq cat head tail mkdir rm cp cmp date git id ls; do
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
