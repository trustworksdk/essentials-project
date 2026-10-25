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
# subagent's compaction, STATE.md handed back once per write, and the pre-compaction snapshot
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

case_name="restore of a STATE.md over 20000 bytes says it was cut and where the rest is"
mkdir -p "$CONTEXT_GUARD_DIR/s21/main"
state=$CONTEXT_GUARD_DIR/s21/main/STATE.md
{ printf 'STATE-HEAD\n'; head -c 25000 /dev/zero | tr '\0' 'x'; printf '\nSTATE-TAIL\n'; } >"$state"
out=$(run restore s21 "")
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

case_name="a state directory that is a symlink is refused: nothing printed, nothing written through it"
elsewhere=$tmp/elsewhere
mkdir -p "$elsewhere/main"
printf '%s\n' "PLANTED-STATE" >"$elsewhere/main/STATE.md"
ln -s "$elsewhere" "$CONTEXT_GUARD_DIR/s24"
assistant_entry "$(main_transcript s24)" claude-opus-5-5 950000 false
expect_nothing "$(run warn s24 "")" "warned through a symlinked session directory"
expect_nothing "$(run restore s24 "")" "restore injected a STATE.md from a symlinked session directory"
[ "$(ls "$elsewhere/main")" = STATE.md ] || fail "wrote through the symlink: $(ls "$elsewhere/main" | tr '\n' ' ')"
rm -f "$CONTEXT_GUARD_DIR/s24"
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
