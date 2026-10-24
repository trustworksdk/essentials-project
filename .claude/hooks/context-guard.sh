#!/usr/bin/env bash
# Context guard: warn an agent before auto-compaction so it can save working state,
# snapshot what can be derived mechanically right before compaction, and hand both back
# once compaction is done. Covers the main conversation and every subagent, in any
# number of parallel sessions.
#
#   start       SessionStart (startup|resume|clear|fork) - remember the session's model
#   warn        PostToolUse, UserPromptSubmit - context-usage warning at 70/80/90 %;
#               for a subagent also the restore (SessionStart never fires for one)
#   precompact  PreCompact (auto|manual)       - write a fallback snapshot
#   restore     SessionStart (compact)         - re-inject saved state + snapshot
#
# Why not PreCompact alone: PreCompact cannot add context for the model, and once auto
# compaction triggers the agent gets no further turn. The warning has to come earlier.
#
# Isolation: state lives in <base>/<session_id>/main or .../agents/<agent_id>. A subagent's
# hook input carries the parent's transcript_path plus its own agent_id; its own transcript
# is <transcript without .jsonl>/subagents/agent-<agent_id>.jsonl. Warn-once guards are
# mkdir locks, so parallel tool calls in one agent cannot double-warn.
# Each agent's percentage is taken against its own window, kept in its own state dir. A
# subagent shares the main session's window only when its own transcript's last assistant
# entry with a usage block names the main session's model; any other model, or none yet,
# gets 200000. The main session's model is main/model or, when that is absent, the settings
# `model`, an alias included: an alias (opus, sonnet, haiku) matches its family's ids, and
# any other alias ('default', 'opusplan') gets 200000. A transcript names the model without
# its [1m] suffix, so a 1M subagent on another model is found when its count passes 200000
# (see warn); from then on its window stays 1000000, also after its compaction.
#
# Base dir is fixed rather than taken from the hook input's scratchpad_dir: that field is
# documented but absent in practice (2.1.310), and the warn and restore paths must agree.
#
# Knobs: CONTEXT_GUARD_WINDOW (tokens; overrides model detection),
#        CONTEXT_GUARD_LEVELS (default "70 80 90"),
#        CONTEXT_GUARD_DIR (default /tmp/claude-<uid>/context-guard).
# Never fails the hook: every path exits 0.
set -u
command -v jq >/dev/null 2>&1 || exit 0

mode=${1:-}
input=$(cat)
field() { jq -r --arg k "$1" '.[$k] // empty' <<<"$input" 2>/dev/null; }

session=$(field session_id)
transcript=$(field transcript_path)
agent=$(field agent_id)
cwd=$(field cwd)
cwd=${cwd:-${CLAUDE_PROJECT_DIR:-.}}
[ -n "$session" ] || exit 0

base=${CONTEXT_GUARD_DIR:-/tmp/claude-$(id -u)/context-guard}/$session
if [ -n "$agent" ]; then
  state_dir=$base/agents/$agent
  transcript=${transcript%.jsonl}/subagents/agent-$agent.jsonl
  who="this subagent"
else
  state_dir=$base/main
  who="this conversation"
fi
mkdir -p "$state_dir" 2>/dev/null || exit 0
state_file=$state_dir/STATE.md
snapshot_file=$state_dir/precompact-snapshot.md

emit() { # $1 event name, $2 text
  jq -n --arg e "$1" --arg c "$2" '{hookSpecificOutput:{hookEventName:$e, additionalContext:$c}}'
}

clear_locks() { rm -rf "$state_dir"/warned.* 2>/dev/null; }

highest_lock() {
  local m=0 d l
  for d in "$state_dir"/warned.*; do
    [ -d "$d" ] || continue
    l=${d##*.}
    [ "$l" -gt "$m" ] 2>/dev/null && m=$l
  done
  echo "$m"
}

main_model() {
  local model=""
  [ -f "$base/main/model" ] && model=$(cat "$base/main/model")
  if [ -z "$model" ]; then
    local cfg=${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json
    local proj=${CLAUDE_PROJECT_DIR:-.}/.claude
    for f in "$proj/settings.local.json" "$proj/settings.json" "$cfg"; do
      [ -f "$f" ] && model=$(jq -r '.model // empty' "$f" 2>/dev/null) && [ -n "$model" ] && break
    done
  fi
  printf '%s' "$model"
}

context_window() { # $1: the model named by this agent's own transcript (may be empty)
  if [ -n "${CONTEXT_GUARD_WINDOW:-}" ]; then echo "$CONTEXT_GUARD_WINDOW"; return; fi
  local model
  model=$(main_model)
  # A subagent runs the main session's window only on the main session's model (the
  # transcript's id carries no [1m] suffix); otherwise 200000 until its count says more.
  # A full id must equal the transcript's id; an alias (opus, sonnet, haiku, as settings
  # hold it) matches the ids of its family: opus matches claude-opus-5-5.
  if [ -n "$agent" ]; then
    local m=${model/'[1m]'/} same=""
    case "$m" in
      '') ;;
      claude-*) [ "$1" = "$m" ] && same=1 ;;
      *) case "$1" in *-"$m"-*|*-"$m") same=1 ;; esac ;;
    esac
    if [ -z "$1" ] || [ -z "$same" ]; then
      echo 200000
      return
    fi
  fi
  case "$model" in *'[1m]'*) echo 1000000 ;; *) echo 200000 ;; esac
}

used_tokens() { # prints "<tokens> <model>" from the last assistant entry; model may be absent
  [ -f "$transcript" ] || return
  # Main transcript: skip sidechain entries. A subagent transcript is all sidechain.
  local filter='select(.type=="assistant" and .message.usage)'
  [ -z "$agent" ] && filter='select(.type=="assistant" and (.isSidechain|not) and .message.usage)'
  tac "$transcript" 2>/dev/null \
    | jq -r "$filter"' | .message
             | "\((.usage.input_tokens//0) + (.usage.cache_read_input_tokens//0) + (.usage.cache_creation_input_tokens//0)) \(.model // "")"' 2>/dev/null \
    | head -1
}

restore_text() {
  local text=""
  if [ -s "$state_file" ]; then
    text+="CONTEXT GUARD: ${who} was just compacted. The working state you saved before compaction (${state_file}) follows; treat it as your own notes and resume from its next step. Keep the file updated if you change course."$'\n\n'
    text+=$(head -c 20000 "$state_file")$'\n\n'
  fi
  [ -s "$snapshot_file" ] && text+=$(head -c 8000 "$snapshot_file")
  printf '%s' "$text"
}

case "$mode" in
  start)
    [ -n "$agent" ] && exit 0
    model=$(field model)
    [ -n "$model" ] && printf '%s' "$model" >"$state_dir/model"
    clear_locks
    ;;

  warn)
    read -r used own_model <<<"$(used_tokens)"
    [ -n "$used" ] && [ "$used" -gt 0 ] 2>/dev/null || exit 0
    window=$(context_window "${own_model:-}")
    # A count past the assumed window proves the window is bigger (e.g. a 1M model).
    [ "$used" -gt "$window" ] && window=1000000
    # A subagent's model cannot change mid-run, so its window never shrinks back once its
    # count proved it bigger: after compaction its count is small again, and a shrink here
    # would clear its warn locks before the restore check below.
    [ -n "$agent" ] && [ -z "${CONTEXT_GUARD_WINDOW:-}" ] \
      && [ "$(cat "$state_dir/window" 2>/dev/null)" = 1000000 ] && window=1000000
    # Window re-estimated: earlier levels were measured against the wrong size.
    if [ "$(cat "$state_dir/window" 2>/dev/null)" != "$window" ]; then
      clear_locks
      echo "$window" >"$state_dir/window"
    fi
    pct=$(( used * 100 / window ))
    event=$(field hook_event_name)
    event=${event:-PostToolUse}

    # Usage fell well below a level already warned at: compaction happened. The main
    # conversation gets its restore from SessionStart(compact); a subagent gets it here.
    peak=$(highest_lock)
    if [ "$peak" -gt 0 ] && [ "$pct" -lt $(( peak - 25 )) ]; then
      clear_locks
      if [ -n "$agent" ]; then
        text=$(restore_text)
        [ -n "$text" ] && emit "$event" "$text"
      fi
      exit 0
    fi

    level=0
    for l in ${CONTEXT_GUARD_LEVELS:-70 80 90}; do [ "$pct" -ge "$l" ] && level=$l; done
    [ "$level" -gt 0 ] || exit 0
    mkdir "$state_dir/warned.$level" 2>/dev/null || exit 0
    emit "$event" "CONTEXT GUARD: context for ${who} is at ${pct}% (${used}/${window} tokens); auto-compaction will summarise it soon and detail will be lost. Before continuing, write your working state to ${state_file} (overwrite it; keep it under ~150 lines): the task and its acceptance criteria, decisions made and why, what is done (commits, files), what is in progress and the exact next step, open questions, and anything you verified that must not be re-derived. It will be handed back to you after compaction. Then carry on with the task."
    ;;

  precompact)
    {
      echo "# Pre-compaction snapshot ($(field trigger) compaction, $(date -u +%Y-%m-%dT%H:%M:%SZ))"
      echo
      echo "Derived mechanically by the context-guard hook, not written by the agent."
      if git -C "$cwd" rev-parse --git-dir >/dev/null 2>&1; then
        echo
        echo "## Git ($cwd)"
        echo
        echo "Branch: $(git -C "$cwd" branch --show-current 2>/dev/null)"
        echo
        echo '```'
        git -C "$cwd" status --short 2>/dev/null | head -40
        echo '```'
      fi
      if [ -f "$transcript" ]; then
        echo
        echo "## Files edited by ${who} (most recent last)"
        echo
        jq -r 'select(.type=="assistant") | .message.content[]?
               | select(.type=="tool_use" and (.name=="Edit" or .name=="Write" or .name=="NotebookEdit"))
               | .input.file_path // .input.notebook_path // empty' "$transcript" 2>/dev/null \
          | awk '!seen[$0]++' | tail -30 | sed 's/^/- /'
        echo
        echo "## Last prompts"
        echo
        jq -r 'select(.type=="user" and (.isMeta|not)) | .message.content
               | if type=="string" then . else ([.[]? | select(.type=="text") | .text] | join("\n")) end
               | select(length>0 and (startswith("<")|not))' "$transcript" 2>/dev/null \
          | tail -5 | cut -c1-500 | sed 's/^/> /;s/$/\n/'
      fi
    } >"$snapshot_file" 2>/dev/null
    ;;

  restore)
    [ -n "$agent" ] && exit 0
    clear_locks
    text=$(restore_text)
    [ -n "$text" ] && emit SessionStart "$text"
    ;;
esac
exit 0
