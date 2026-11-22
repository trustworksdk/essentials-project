#!/usr/bin/env bash
#
# claude-rc — launch Claude Code with Remote Control on, for THIS session only.
#
# post-create.sh puts a `claude-rc` shim on PATH (~/.local/bin/claude-rc) that runs
# this file, so it works from any directory:
#
#   claude-rc                        # new interactive session, Remote Control on
#   claude-rc --resume [<id>]        # resume a session (picker without an id)
#   claude-rc --continue             # resume the most recent session here
#   claude-rc --name "My Project"    # session title at claude.ai/code
#   claude-rc remote-control [...]   # server mode instead of an interactive session
#
# Every other argument goes to `claude` untouched.
#
# Why a wrapper
# -------------
# The container sets DO_NOT_TRACK=1 (devcontainer.json containerEnv) as a
# cross-tool telemetry opt-out. Remote Control needs Claude Code's feature-flag
# fetch, and these variables turn that fetch off:
#
#   CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC, DISABLE_GROWTHBOOK
#       Always block Remote Control. Cleared here unconditionally.
#   DO_NOT_TRACK, DISABLE_TELEMETRY
#       Block it on Claude Code 2.1.154–2.1.282, and on 2.1.283+ only when the
#       organization requires Trusted Devices. Kept on 2.1.283+ by default, so
#       the telemetry opt-out stays in force for this session too. Cleared on an
#       older Claude Code, or when CLAUDE_RC_CLEAR_TELEMETRY_OPTOUT=1 (set it if
#       your org requires Trusted Devices).
#
# When DO_NOT_TRACK has to go, it goes for the `claude` process only: a
# CLAUDE_ENV_FILE re-exports it into every Bash command Claude runs, so the
# tools it starts keep the opt-out. The container-wide value is never touched.
# See https://code.claude.com/docs/en/remote-control § "Requirements".

set -euo pipefail

# ---------------------------------------------------------------------------
# 1. Clear the variables that always block Remote Control.
# ---------------------------------------------------------------------------
unset CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC DISABLE_GROWTHBOOK

command -v claude >/dev/null 2>&1 || {
  echo "claude-rc: ERROR — 'claude' not found on PATH." >&2
  exit 127
}

# ---------------------------------------------------------------------------
# 2. DO_NOT_TRACK / DISABLE_TELEMETRY — only when they still block.
# ---------------------------------------------------------------------------
min_version="2.1.283"
version="$(claude --version 2>/dev/null | awk 'NR==1 {print $1}')"
clear_optout=0
if [[ "${CLAUDE_RC_CLEAR_TELEMETRY_OPTOUT:-}" == "1" ]]; then
  clear_optout=1
elif [[ -z "$version" ]] ||
     [[ "$(printf '%s\n%s\n' "$min_version" "$version" | sort -V | sed -n 1p)" != "$min_version" ]]; then
  clear_optout=1
fi

if [[ $clear_optout -eq 1 && ( -n "${DO_NOT_TRACK:-}" || -n "${DISABLE_TELEMETRY:-}" ) ]]; then
  # Hand the opt-out back to the tools Claude runs. Claude Code sources
  # CLAUDE_ENV_FILE before each Bash command; an existing one is chained first.
  env_dir="${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/claude-rc-$(id -u)"
  mkdir -p -m 700 "$env_dir"
  env_file="$env_dir/telemetry-optout.sh"
  {
    if [[ -n "${CLAUDE_ENV_FILE:-}" && "${CLAUDE_ENV_FILE}" != "$env_file" ]]; then
      printf '. %q\n' "$CLAUDE_ENV_FILE"
    fi
    if [[ -n "${DO_NOT_TRACK:-}" ]]; then printf 'export DO_NOT_TRACK=%q\n' "$DO_NOT_TRACK"; fi
    if [[ -n "${DISABLE_TELEMETRY:-}" ]]; then printf 'export DISABLE_TELEMETRY=%q\n' "$DISABLE_TELEMETRY"; fi
  } > "$env_file"
  export CLAUDE_ENV_FILE="$env_file"
  unset DO_NOT_TRACK DISABLE_TELEMETRY
  echo "claude-rc: DO_NOT_TRACK cleared for this claude process only (Claude Code ${version:-unknown}" \
       "${CLAUDE_RC_CLEAR_TELEMETRY_OPTOUT:+, Trusted Devices override}); Bash commands keep it." >&2
fi

# ---------------------------------------------------------------------------
# 3. Preflight — the other Remote Control requirements, as warnings.
# ---------------------------------------------------------------------------
# Checks, not fixes: silently rewriting auth or API routing would be worse than
# saying why Remote Control will refuse.
if [[ -n "${ANTHROPIC_BASE_URL:-}" && "${ANTHROPIC_BASE_URL}" != *"api.anthropic.com"* ]]; then
  echo "claude-rc: WARNING — ANTHROPIC_BASE_URL=${ANTHROPIC_BASE_URL}" >&2
  echo "  Remote Control needs requests to go directly to api.anthropic.com." >&2
  echo "  Run: env -u ANTHROPIC_BASE_URL claude-rc" >&2
fi
if [[ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]]; then
  echo "claude-rc: WARNING — CLAUDE_CODE_OAUTH_TOKEN is set." >&2
  echo "  A \`claude setup-token\` token can only make model requests, not Remote Control" >&2
  echo "  sessions. Sign in with \`claude auth login\`, then run: env -u CLAUDE_CODE_OAUTH_TOKEN claude-rc" >&2
fi

# ---------------------------------------------------------------------------
# 4. Add --remote-control unless the caller already asked for it, or runs the
#    `remote-control` subcommand (server mode refuses a global flag before it).
# ---------------------------------------------------------------------------
inject_flag=1
for arg in "$@"; do
  case "$arg" in
    remote-control|--remote-control|--rc) inject_flag=0; break ;;
  esac
done
if [[ $inject_flag -eq 1 ]]; then set -- --remote-control "$@"; fi

# exec: signals and the exit code pass straight through; no wrapper lingers.
exec claude "$@"
