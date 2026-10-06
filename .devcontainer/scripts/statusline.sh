#!/usr/bin/env bash
# Claude Code status line: active model, context usage, git branch, worktree.
#
# Renders, left to right:
#   <model>[:effort] [⚡fast] │ <used>/<window> (<pct>%) │ ⎇ <branch> [⧉ <worktree>] │ <dir>
#
# ---------------------------------------------------------------------------
# Payload (stdin, JSON). Verified against the Claude Code 2.1.266 binary:
#   .model.display_name              e.g. "Opus 5"
#   .context_window.total_input_tokens
#   .context_window.context_window_size
#   .context_window.used_percentage
#   .fast_mode                       bool
#   .effort.level                    e.g. "high"  (absent on some models)
#   .workspace.current_dir
#   .worktree.{name,branch,path}     ONLY for Claude-Code-managed worktrees
#                                    (EnterWorktree / --worktree), absent otherwise
#   .workspace.git_worktree          a bare directory BASENAME, not a branch,
#                                    and null outside a linked worktree
#   .workspace.repo                  {host,owner,name} — repo identity, no branch
#
# So the branch is NOT dependably in the payload and must come from git.
#
# ---------------------------------------------------------------------------
# Why this reads .git/HEAD instead of calling git:
# no fork/exec per render, and no dependency on git being on PATH. To be
# straight about the size of the win — measured on this virtiofs checkout,
# `git branch --show-current` is ~4.6 ms and the whole script is ~5.6 ms, so
# branch resolution is NOT the bottleneck either way and this is a small
# margin, not a rescue. It matters if you later extend the line: the
# expensive call is `git status`/`--porcelain` for dirty state, which DOES
# scan the index (23k files here). Do not add that without caching.
set -uo pipefail

input=$(cat)

# One field per LINE, read with mapfile — NOT `IFS=$'\t' read` on @tsv output.
# Tab is an IFS *whitespace* character, so read collapses runs of tabs into a
# single delimiter: every empty field (no effort, fast_mode false, not in a
# worktree) silently vanishes and shifts all later fields left.
mapfile -t F < <(
  printf '%s' "$input" | jq -r '
    [ (.model.display_name // "")
    , (.context_window.total_input_tokens  // 0)
    , (.context_window.context_window_size // 0)
    , (.context_window.used_percentage     // 0 | floor)
    , (if .fast_mode then "fast" else "" end)
    , (.effort.level // "")
    , (.workspace.current_dir // .cwd // "")
    , (.worktree.name   // .workspace.git_worktree // "")
    , (.worktree.branch // "")
    ] | .[]' 2>/dev/null
)
model=${F[0]:-}   used=${F[1]:-0}  max=${F[2]:-0}
pct=${F[3]:-0}    fast=${F[4]:-}   effort=${F[5]:-}
dir=${F[6]:-}     wt_name=${F[7]:-} wt_branch=${F[8]:-}

# Guard the arithmetic: a non-numeric value from a malformed payload would
# otherwise abort the script under `set -u`-adjacent evaluation.
[[ $used == *[!0-9]* || -z $used ]] && used=0
[[ $max  == *[!0-9]* || -z $max  ]] && max=0
[[ $pct  == *[!0-9]* || -z $pct  ]] && pct=0

# --- git: resolve $gitdir for $dir, honouring linked worktrees -------------
# In a linked worktree `.git` is a FILE containing "gitdir: <path>", where
# <path> is .../.git/worktrees/<name> and holds that worktree's own HEAD.
gitdir="" wt_from_git=""
probe="$dir"
# Only walk absolute paths, and stop the moment the parent stops shrinking —
# `${probe%/*}` on a slash-less string returns it unchanged, which spins.
[[ $probe == /* ]] || probe=""
while [[ -n $probe && $probe != / ]]; do
  if [[ -d $probe/.git ]]; then
    gitdir="$probe/.git"; break
  elif [[ -f $probe/.git ]]; then
    IFS= read -r line <"$probe/.git" || line=""
    [[ $line == gitdir:* ]] && gitdir="${line#gitdir: }"
    # .../.git/worktrees/<name>  ->  <name>
    [[ $gitdir == */worktrees/* ]] && wt_from_git="${gitdir##*/}"
    break
  fi
  parent="${probe%/*}"
  [[ $parent == "$probe" ]] && break
  probe="$parent"
done

branch=""
if [[ -n $gitdir && -r $gitdir/HEAD ]]; then
  IFS= read -r head <"$gitdir/HEAD" || head=""
  if [[ $head == ref:*refs/heads/* ]]; then
    branch="${head##*refs/heads/}"
  elif [[ -n $head ]]; then
    branch="${head:0:7}"           # detached HEAD -> short SHA
    detached=1
  fi
fi

# A Claude-Code-managed worktree reports its own branch; trust it over HEAD.
[[ -n $wt_branch ]] && branch="$wt_branch"
[[ -z $wt_name ]] && wt_name="$wt_from_git"

# --- formatting ------------------------------------------------------------
# 1234 -> 1k, 162431 -> 162k, 1000000 -> 1.0M
hn() {
  local n=${1:-0}
  if   (( n >= 1000000 )); then awk -v n="$n" 'BEGIN{printf "%.1fM", n/1000000}'
  elif (( n >= 1000    )); then awk -v n="$n" 'BEGIN{printf "%.0fk", n/1000}'
  else printf '%s' "$n"
  fi
}

DIM=$'\033[2m'; RESET=$'\033[0m'; CYAN=$'\033[36m'; MAGENTA=$'\033[35m'
GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'

if   (( pct >= 85 )); then ctx_col=$RED
elif (( pct >= 60 )); then ctx_col=$YELLOW
else                       ctx_col=$GREEN
fi

sep=" ${DIM}│${RESET} "
out="${CYAN}${model:-?}${RESET}"
[[ -n $effort ]] && out+="${DIM}:${effort}${RESET}"
[[ -n $fast   ]] && out+=" ${YELLOW}⚡fast${RESET}"

(( max > 0 )) && out+="${sep}${ctx_col}$(hn "$used")/$(hn "$max") (${pct}%)${RESET}"

if [[ -n $branch ]]; then
  # Detached HEAD is worth noticing, so colour it like a warning.
  if [[ ${detached:-} == 1 ]]; then out+="${sep}${YELLOW}⎇ ${branch}${RESET}"
  else                              out+="${sep}${GREEN}⎇ ${branch}${RESET}"
  fi
fi
[[ -n $wt_name ]] && out+=" ${MAGENTA}⧉ ${wt_name}${RESET}"

# The footer has finite width, so keep long paths from pushing the line around:
# home-relative, and deep paths collapsed to their last two segments.
if [[ -n $dir ]]; then
  short="${dir/#$HOME/\~}"
  if (( ${#short} > 28 )) && [[ $short == */*/* ]]; then
    short="…/${short#"${short%/*/*}/"}"
  fi
  out+="${sep}${DIM}${short}${RESET}"
fi

printf '%s' "$out"
