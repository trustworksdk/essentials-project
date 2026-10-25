#!/usr/bin/env bash
# Context guard: warn an agent before auto-compaction so it can save working state,
# snapshot what can be derived mechanically right before compaction, and hand both back
# once compaction is done. Covers the main conversation and every subagent, in any
# number of parallel sessions.
#
#   start       SessionStart (startup|resume|clear|fork) - remember the session's model
#   warn        PostToolUse, UserPromptSubmit - warning when 8 % and again 5 % of the window
#               is left before the compaction point;
#               for a subagent also the restore (SessionStart never fires for one)
#   precompact  PreCompact (auto|manual)       - write a fallback snapshot
#   restore     SessionStart (compact)         - re-inject saved state (once) + snapshot
#
# Why not PreCompact alone: PreCompact cannot add context for the model, and once auto
# compaction triggers the agent gets no further turn. The warning has to come earlier.
#
# Isolation: state lives in <base>/<session_id>/main or .../agents/<agent_id>. A subagent's
# hook input carries the parent's transcript_path plus its own agent_id; its own transcript
# is <transcript without .jsonl>/subagents/agent-<agent_id>.jsonl. Warn-once guards are
# mkdir locks, so parallel tool calls in one agent cannot double-warn.
# Each agent's percentage is taken against its own window, kept in its own state dir. The
# main session's model is main/model or, when that is absent, the settings `model`; its
# window is 1000000 when that names a [1m] model, else 200000. A subagent shares the main
# session's 1M window only when its own transcript's last assistant entry with a usage block
# names the main session's exact model id; any other model, or none yet, gets 200000. That
# id is the main model without its [1m] suffix when it is a full id (claude-opus-5-5[1m]);
# an alias (opus[1m]) names a whole family, so it is resolved to the model the main
# transcript's last assistant entry with a usage block names, a `<synthetic>` one (an API
# error placeholder) skipped, found by the subagent's first call that finds one and kept for
# the rest of its run (main_model_id in its state dir), so a later model switch in the main
# session is not followed; with no such entry the subagent gets 200000. A transcript
# names the model without its [1m] suffix, so a 1M subagent on another model is found when
# its count passes 200000 (see warn); from then on its window stays 1000000, also after its
# compaction.
# The main conversation compacts at its auto-compact window when one is set (/autocompact saves
# it as modelSettings[<model id>].autoCompactWindow in the user settings; a top-level
# autoCompactWindow sets it for every model; CLAUDE_CODE_AUTO_COMPACT_WINDOW overrides both), so
# its window is that value when it is smaller than the model's: a 1M session with a 300000
# window compacts near 270000 and would never reach a warning measured against 1000000. The
# --autocompact launch flag leaves no trace a hook can read; CONTEXT_GUARD_WINDOW covers it.
# Claude Code compacts a fixed reserve short of the window, not at the window: ~267000 on a
# 300000 window in our sessions, ~967000 on 1M per its docs - both 33000 short. The warnings
# are measured against that compaction point (window - CONTEXT_GUARD_RESERVE), as headroom
# left in % of the window, so they come equally close before compaction on every window size.
# The count is the last API call's, so the tool result just returned is not in it yet: one
# large result can cross a warning point, or reach compaction, unannounced.
# Tested by scripts/test-context-guard.sh.
#
# Base dir is fixed rather than taken from the hook input's scratchpad_dir: that field is
# documented but absent in practice (2.1.310), and the warn and restore paths must agree.
#
# Knobs: CONTEXT_GUARD (off|0|false|no switches the guard off without removing the hooks),
#        CONTEXT_GUARD_WINDOW (tokens; overrides model detection),
#        CONTEXT_GUARD_HEADROOM (default "8 5": warn once at each, when that % of the window or
#          less is left before the compaction point),
#        CONTEXT_GUARD_RESERVE (default 33000: tokens short of the window Claude Code compacts at),
#        CONTEXT_GUARD_DIR (default /tmp/claude-<uid>/context-guard).
# State is private: files are created with umask 077, every state directory must be a real
# directory owned by this user (else the hook does nothing; each is checked before anything is
# created in it, a missing one created a level at a time) and is chmod 700, so on a shared
# host no other user can read STATE.md or plant one for a restore to inject. `start` prunes
# session directories with nothing written in them for 7 days; `warn` marks its state dir
# live on every call, so a long-running session is never pruned.
# Known limit: a /model switch in the main conversation is not followed - main/model is
# written at SessionStart only, so a switch from a [1m] model to a 200k one keeps the 1M
# window (the upward switch is caught by a count past the window).
# Never fails the hook: every path exits 0.
# Needs bash and jq (without jq it does nothing). Transcripts are read by jq (warn's usage
# and main-model checks first take the last 500, then 5000 lines with POSIX `tail -n`), so no
# GNU-only tool (tac, GNU sed) is involved: it works on a stock macOS userland as well.
set -u
case "${CONTEXT_GUARD:-on}" in off|0|false|no) exit 0 ;; esac
umask 077
command -v jq >/dev/null 2>&1 || exit 0

mode=${1:-}
input=$(cat)
field() { jq -r --arg k "$1" '.[$k] // empty' <<<"$input" 2>/dev/null; }

session=$(field session_id)
transcript=$(field transcript_path)
main_transcript=$transcript # a subagent's hook input names the main session's transcript
agent=$(field agent_id)
cwd=$(field cwd)
cwd=${cwd:-${CLAUDE_PROJECT_DIR:-.}}
[ -n "$session" ] || exit 0

root=${CONTEXT_GUARD_DIR:-/tmp/claude-$(id -u)/context-guard}
# Trailing slashes dropped: with one, `[ -L ]` below tests the link's target, not the link.
while case "$root" in ?*/) ;; *) false ;; esac; do root=${root%/}; done
base=$root/$session
if [ -n "$agent" ]; then
  state_dir=$base/agents/$agent
  transcript=${transcript%.jsonl}/subagents/agent-$agent.jsonl
  who="this subagent"
else
  state_dir=$base/main
  who="this conversation"
fi
# Refuse state another user could have pre-created or swapped for a symlink. Each state
# directory, from the root down, is checked before anything is created in it, and a missing
# one is created a single level at a time: `mkdir -p` would follow a planted link and create
# the rest in its target before any check ran. The root's parent is created when missing,
# unchecked.
parent=${root%/*}
case "$root" in */*) ;; *) parent=. ;; esac
[ -d "${parent:-/}" ] || mkdir -p "$parent" 2>/dev/null || exit 0
for d in "$root" "$base" ${agent:+"$base/agents"} "$state_dir"; do
  [ -e "$d" ] || [ -L "$d" ] || mkdir "$d" 2>/dev/null
  [ -d "$d" ] && [ ! -L "$d" ] && [ -O "$d" ] || exit 0
  chmod 700 "$d" 2>/dev/null
done
state_file=$state_dir/STATE.md
restored_file=$state_dir/STATE.restored.md # the STATE.md the last restore handed back
snapshot_file=$state_dir/precompact-snapshot.md

emit() { # $1 event name, $2 text
  jq -n --arg e "$1" --arg c "$2" '{hookSpecificOutput:{hookEventName:$e, additionalContext:$c}}'
}

clear_locks() { rm -rf "$state_dir"/warned.* 2>/dev/null; }

has_lock() {
  local d
  for d in "$state_dir"/warned.*; do [ -d "$d" ] && return 0; done
  return 1
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

integer_or_empty() { # $1: a value; prints it when it is a decimal integer of at most 18 digits
  # Digits only, and at most 18 of them, which always fit bash's 64-bit integers: a longer one may
  # not, and then `[ -gt ]` prints "integer expression expected" instead of comparing.
  case "$1" in ''|*[!0-9]*) return ;; esac
  [ "${#1}" -le 18 ] && printf '%s' "$1"
}

autocompact_window() { # $1: the main session's model id; prints its auto-compact window, if set
  # Settings are read as Claude Code ranks them: local, project, then user; within one file a
  # window saved for the model outranks the top-level key. A settings value is any JSON number
  # and jq keeps its literal (300000.0, 3E+5), so an integral one is printed as an integer
  # (floor) and a fractional one dropped; a value that is still no integer bash can compare is
  # ignored, like one that is not a number.
  local v
  v=$(integer_or_empty "${CLAUDE_CODE_AUTO_COMPACT_WINDOW:-}")
  if [ -z "$v" ]; then
    local cfg=${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json
    local proj=${CLAUDE_PROJECT_DIR:-.}/.claude
    for f in "$proj/settings.local.json" "$proj/settings.json" "$cfg"; do
      [ -f "$f" ] || continue
      v=$(jq -r --arg m "$1" '(.modelSettings[$m].autoCompactWindow // .autoCompactWindow // empty)
                             | numbers | select(. == floor) | floor' "$f" 2>/dev/null)
      v=$(integer_or_empty "$v")
      [ -n "$v" ] && break
    done
  fi
  printf '%s' "$v"
}

context_window() { # $1: the model named by this agent's own transcript (may be empty)
  if [ -n "${CONTEXT_GUARD_WINDOW:-}" ]; then echo "$CONTEXT_GUARD_WINDOW"; return; fi
  local model
  model=$(main_model)
  case "$model" in *'[1m]'*) ;; *) echo 200000; return ;; esac
  # A subagent runs the main session's 1M window only on the main session's exact model id
  # (a transcript's id carries no [1m] suffix); otherwise 200000 until its count says more.
  # A full id (it has a '-') is taken as it is. An alias (opus, as settings hold it) names a
  # family, not a model - claude-opus-4-1 is an opus too - so it is resolved to the id the
  # main transcript's last assistant entry with a usage block names, a `<synthetic>` one (an
  # API error placeholder) skipped, found by the subagent's first call that finds one and kept
  # for the rest of its run (main_model_id in its state dir), so a later model switch in the
  # main session is not followed; with none yet, the subagent gets 200000.
  if [ -n "$agent" ]; then
    local id=${model/'[1m]'/}
    case "$id" in *-*) ;; *) id=$(main_transcript_model) ;; esac
    if [ -z "$1" ] || [ "$1" != "$id" ]; then
      echo 200000
      return
    fi
  fi
  echo 1000000
}

last_match() { # $1 transcript, $2 jq program over its raw lines that prints the last match
  # The tail is read first - the last 500 lines, then the last 5000 - and the whole file only
  # when neither holds a match: a long session's transcript runs to tens of MB.
  local n out
  for n in 500 5000; do
    out=$(tail -n "$n" "$1" 2>/dev/null | jq -nrR "$2" 2>/dev/null)
    [ -n "$out" ] && { printf '%s' "$out"; return; }
  done
  jq -nrR "$2" "$1" 2>/dev/null
}

main_transcript_model() { # the model id named by the main transcript's last assistant entry
  # with a usage block, a `<synthetic>` one (an API error placeholder) skipped. Called for a
  # subagent only. A subagent's model cannot change mid-run, so the main model id its first
  # call finds is the one it is compared with for the rest of its run: the id is kept in its
  # state dir (main_model_id) and the main transcript is not read again by its later calls;
  # with no id found yet nothing is kept, and the next call reads the transcript again.
  local cache=$state_dir/main_model_id
  if [ -s "$cache" ]; then cat "$cache"; return; fi
  [ -f "$main_transcript" ] || return
  local prog='[inputs | fromjson? | objects
             | select(.type=="assistant" and (.isSidechain|not) and .message.usage) | .message.model
             | select(type=="string" and . != "" and (startswith("<")|not))] | last // empty'
  local out
  out=$(last_match "$main_transcript" "$prog")
  # Written to a temporary file and renamed onto main_model_id, not truncated in place.
  [ -n "$out" ] && printf '%s' "$out" >"$cache.$$" 2>/dev/null && mv -f "$cache.$$" "$cache" 2>/dev/null
  rm -f "$cache.$$" 2>/dev/null
  printf '%s' "$out"
}

used_tokens() { # prints "<tokens> <model>" from the last assistant entry; model may be absent
  [ -f "$transcript" ] || return
  # Main transcript: skip sidechain entries. A subagent transcript is all sidechain.
  local filter='select(.type=="assistant" and .message.usage)'
  [ -z "$agent" ] && filter='select(.type=="assistant" and (.isSidechain|not) and .message.usage)'
  # jq takes the last match itself. The transcript's tail is read first (last_match), as
  # this runs on every tool call; the whole file only when the tail holds no usage entry.
  local prog='[inputs | fromjson? | objects | '"$filter"' | .message
             | "\((.usage.input_tokens//0) + (.usage.cache_read_input_tokens//0) + (.usage.cache_creation_input_tokens//0)) \(.model // "")"]
             | last // empty'
  local out
  out=$(last_match "$transcript" "$prog")
  printf '%s\n' "$out"
}

restore_text() {
  local text=""
  # STATE.md goes back once: a restore copies what it handed back to STATE.restored.md, and a
  # later restore (the next compaction of the same agent) hands STATE.md back only when it
  # was written since - its content differs from that copy, or it is newer than the copy.
  # Otherwise those notes predate an earlier compaction and would send the agent back to a
  # step it has already done. The content check covers a rewrite within the same second as
  # the restore, which a one-second mtime cannot tell apart.
  if [ -s "$state_file" ] && { [ ! -f "$restored_file" ] \
      || [ "$state_file" -nt "$restored_file" ] || ! cmp -s "$state_file" "$restored_file"; }; then
    text+="CONTEXT GUARD: ${who} was just compacted. The working state you saved before compaction (${state_file}) follows; treat it as your own notes and resume from its next step. Keep the file updated if you change course."$'\n\n'
    text+=$(head -c 20000 "$state_file")$'\n\n'
    [ "$(wc -c <"$state_file")" -gt 20000 ] \
      && text+="(STATE.md was cut at 20000 bytes here - read ${state_file} for the rest.)"$'\n\n'
    cp "$state_file" "$restored_file" 2>/dev/null
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
    # Prune other sessions' state with nothing written for 7 days. A session directory's own
    # mtime does not track activity (it changes only when main/ or agents/ is created), so a
    # directory is kept while any entry in it is newer; warn refreshes main/live and each
    # agent's live on every call.
    for d in "$root"/*/; do
      d=${d%/}
      [ -d "$d" ] && [ ! -L "$d" ] && [ "$d" != "$base" ] || continue
      find "$d" -mtime -7 2>/dev/null | read -r _ || rm -rf "$d" 2>/dev/null
    done
    ;;

  warn)
    : >"$state_dir/live" 2>/dev/null # marks this state live for start's pruning
    read -r used own_model <<<"$(used_tokens)"
    [ -n "$used" ] && [ "$used" -gt 0 ] 2>/dev/null || exit 0
    window=$(context_window "${own_model:-}")
    # A count past the assumed window proves the window is bigger (e.g. a 1M model).
    [ "$used" -gt "$window" ] && window=1000000
    # The main conversation compacts at its auto-compact window when that is smaller. Its model
    # id is the settings model without [1m] when that is a full id, else its transcript's.
    if [ -z "$agent" ] && [ -z "${CONTEXT_GUARD_WINDOW:-}" ]; then
      id=$(main_model)
      id=${id/'[1m]'/}
      case "$id" in *-*) ;; *) id=${own_model:-} ;; esac
      cap=$(autocompact_window "$id")
      [ -n "$cap" ] && [ "$cap" -gt 0 ] && [ "$cap" -lt "$window" ] && window=$cap
    fi
    # A subagent's model cannot change mid-run, so its window never shrinks back once its
    # count proved it bigger: after compaction its count is small again, and a shrink here
    # would clear its warn locks before the restore check below.
    [ -n "$agent" ] && [ -z "${CONTEXT_GUARD_WINDOW:-}" ] \
      && [ "$(cat "$state_dir/window" 2>/dev/null)" = 1000000 ] && window=1000000
    # Window re-estimated: earlier levels were measured against the wrong size.
    if [ "$(cat "$state_dir/window" 2>/dev/null)" != "$window" ]; then
      clear_locks
      # Written to a temporary file and renamed onto window, not truncated in place.
      printf '%s' "$window" >"$state_dir/window.$$" 2>/dev/null \
        && mv -f "$state_dir/window.$$" "$state_dir/window" 2>/dev/null
      rm -f "$state_dir/window.$$" 2>/dev/null
    fi
    reserve=${CONTEXT_GUARD_RESERVE:-33000}
    case "$reserve" in ''|*[!0-9]*) reserve=33000 ;; esac
    trigger=$(( window - reserve ))
    [ "$trigger" -gt $(( window / 2 )) ] || trigger=$(( window / 2 ))
    pct=$(( used * 100 / window ))
    event=$(field hook_event_name)
    event=${event:-PostToolUse}

    # Warned already, and usage is now below half the compaction point: compaction happened.
    # The main conversation gets its restore from SessionStart(compact); a subagent gets it here.
    if has_lock && [ "$used" -lt $(( trigger / 2 )) ]; then
      clear_locks
      if [ -n "$agent" ]; then
        text=$(restore_text)
        [ -n "$text" ] && emit "$event" "$text"
      fi
      exit 0
    fi

    # The tightest headroom level reached; each warns once (a jump past one skips it).
    left=$(( trigger - used ))
    [ "$left" -gt 0 ] || left=0
    level=""
    for h in ${CONTEXT_GUARD_HEADROOM:-8 5}; do
      case "$h" in ''|*[!0-9]*) continue ;; esac
      [ $(( left * 100 )) -le $(( h * window )) ] && { [ -z "$level" ] || [ "$h" -lt "$level" ]; } && level=$h
    done
    [ -n "$level" ] || exit 0
    mkdir "$state_dir/warned.$level" 2>/dev/null || exit 0
    emit "$event" "CONTEXT GUARD: context for ${who} is at ${pct}% (${used}/${window} tokens); auto-compaction triggers at about ${trigger} tokens, ${left} from now, and will summarise it, losing detail. Before continuing, if you can write files, write your working state to ${state_file} (overwrite it; keep it under ~150 lines): the task and its acceptance criteria, decisions made and why, what is done (commits, files), what is in progress and the exact next step, open questions, and anything you verified that must not be re-derived. It will be handed back to you after compaction. If that write is refused (a write-scope guard, a permission check), do not retry it. Then carry on with the task."
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
        # Read to the end rather than piped into head, which would leave git to die of SIGPIPE.
        n=0
        git -C "$cwd" status --short 2>/dev/null | while IFS= read -r line; do
          n=$((n + 1))
          [ "$n" -le 40 ] && printf '%s\n' "$line"
        done
        echo '```'
      fi
      if [ -f "$transcript" ]; then
        echo
        echo "## Files edited by ${who} (most recent last)"
        echo
        # Each path once, at its last edit; the 30 most recently edited.
        jq -nrR '[inputs | fromjson? | objects | select(.type=="assistant") | .message.content[]?
               | select(.type=="tool_use" and (.name=="Edit" or .name=="Write" or .name=="NotebookEdit"))
               | .input.file_path // .input.notebook_path // empty]
               | reverse
               | reduce .[] as $p ({seen: {}, out: []};
                   if .seen[$p] then . else .seen[$p] = true | .out += [$p] end)
               | .out[:30] | reverse | .[] | "- " + .' "$transcript" 2>/dev/null
        echo
        echo "## Last prompts"
        echo
        # The last five prompts, each cut to 500 characters, quoted line by line and
        # followed by a blank line.
        jq -nrR '[inputs | fromjson? | objects | select(.type=="user" and (.isMeta|not)) | .message.content
               | if type=="string" then . else ([.[]? | select(.type=="text") | .text] | join("\n")) end
               | select(length>0 and (startswith("<")|not))]
               | .[-5:][] | .[0:500] | split("\n") | map("> " + .) | join("\n") + "\n"' \
          "$transcript" 2>/dev/null
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
