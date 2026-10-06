#!/usr/bin/env bash
# rtk-bash-rewrite.sh — this project's PreToolUse(Bash) hook for rtk (Rust Token Killer).
# managed by devcontainer-generator (post-create.sh rewrites this file while this line is present)
#
# rtk's own hook (`rtk hook claude`) rewrites a Bash command to its rtk form — `git status` becomes
# `rtk git status` — so the output is compressed before it reaches the model. This wrapper runs that
# hook and passes its answer on unchanged, with one exception:
#
#   Inside a worktree under .claude/worktrees/, a rewrite that would put git behind rtk is dropped
#   and the command runs as typed.
#
# Why: Claude Code refuses any git command from an agent started with isolation "worktree" unless it
# can see that the command targets the agent's own worktree. `rtk git status` hides git behind a
# launcher, so it is refused ("this command runs rtk with a git command among its operands … cannot
# be shown not to be git. Refusing to run it"). Before this wrapper, worktree agents could not
# `git add` or `git commit` at all; they called /usr/bin/git to get past rtk, or stopped with their
# work staged. Every other rewrite (ls, grep, …) is still applied in a worktree: the guard only
# reads git.
#
# .devcontainer/scripts/post-create.sh writes this file and wires it in the committed
# .claude/settings.json, so every clone gets the same behaviour. It also strips rtk's own hook from
# ~/.claude/settings.json: two hooks rewriting the same command would race, and the global one has
# no worktree exception. Delete the marker line above to keep a customised copy.
#
# Fails open: no rtk on PATH, RTK_HOOK_DISABLED=1, or no jq in a worktree — the command runs as
# typed, or as rtk rewrote it. Never blocks a command on its own account.

# Read stdin first, even on the early exits: a hook that exits without reading it can kill the
# writer with SIGPIPE.
input="$(cat)"
[ "${RTK_HOOK_DISABLED:-}" = "1" ] && exit 0
command -v rtk >/dev/null 2>&1 || exit 0

out="$(printf '%s' "$input" | rtk hook claude)"
rc=$?

if [ "$rc" -eq 0 ] && [ -n "$out" ] && command -v jq >/dev/null 2>&1; then
  cwd="$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null)"
  case "$cwd" in
    */.claude/worktrees/*)
      rewritten="$(printf '%s' "$out" | jq -r '.hookSpecificOutput.updatedInput.command // empty' 2>/dev/null)"
      # `rtk git …` anywhere in the command: at the start, after `&&`/`;`/`|`/`(`, or behind
      # `rtk proxy`. Dropping the whole rewrite keeps the command exactly as the model wrote it.
      if [[ "$rewritten" =~ (^|[^[:alnum:]_./-])rtk([[:space:]]+proxy)?[[:space:]]+git([[:space:]]|$) ]]; then
        exit 0
      fi
      ;;
  esac
fi

[ -n "$out" ] && printf '%s\n' "$out"
exit "$rc"
