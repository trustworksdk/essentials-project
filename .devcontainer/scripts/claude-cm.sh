#!/usr/bin/env bash
#
# claude-cm — launch Claude Code in Code Mode (experimental), for THIS session only.
#
# post-create.sh puts a `claude-cm` shim on PATH (~/.local/bin/claude-cm) that runs
# this file, so it works from any directory:
#
#   claude-cm                        # new interactive Code Mode session
#   claude-cm --continue             # resume the most recent Code Mode session here
#   claude-cm --resume [<id>]        # resume a Code Mode session (picker without an id)
#
# Every argument goes to `claude` untouched. A Code Mode session resumes only with
# Code Mode on, so resume it through claude-cm, never plain `claude`. The first
# Code Mode session asks you to confirm and then restarts Claude Code once.
# See https://code.claude.com/docs/en/code-mode.
#
# Why a wrapper
# -------------
# Code Mode needs Claude Code's feature-flag fetch, and the container turns that
# fetch off: devcontainer.json containerEnv sets DO_NOT_TRACK=1 as a cross-tool
# telemetry opt-out (https://code.claude.com/docs/en/env-vars § "Features that
# need feature-flag fetching"). Two more settings refuse Code Mode outright:
# agent teams (CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS) and Remote Control.
#
#   DO_NOT_TRACK, DISABLE_TELEMETRY
#       Cleared for the `claude` process only. A CLAUDE_ENV_FILE re-exports them
#       into every Bash command Claude runs, so the tools it starts keep the
#       opt-out. The container-wide value is never touched.
#   CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC, CLAUDE_CODE_DISABLE_FEATURE_FLAGS,
#   DISABLE_GROWTHBOOK
#       Claude-only switches that also stop the fetch. Cleared.
#   CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS
#       Cleared for this session: agent teams and Code Mode can't run together.
#   CLAUDE_CODE_EXPERIMENTAL_CODE_MODE
#       Set to 1. Claude Code ignores it in a project's settings files, so the
#       process environment (or your user settings) is the only place it counts.
#
# claude-rc (Remote Control) and claude-cm are separate launchers because Code
# Mode can't be used with Remote Control.

set -euo pipefail

min_version="2.1.323"

command -v claude >/dev/null 2>&1 || {
  echo "claude-cm: ERROR — 'claude' not found on PATH." >&2
  exit 127
}

# ---------------------------------------------------------------------------
# 1. Refuse the launch options Code Mode can't run with.
# ---------------------------------------------------------------------------
for arg in "$@"; do
  case "$arg" in
    remote-control|--remote-control|--rc)
      echo "claude-cm: ERROR — Code Mode can't be used with Remote Control." >&2
      echo "  Use claude-rc for a Remote Control session." >&2
      exit 2 ;;
    -p|--print)
      echo "claude-cm: ERROR — Code Mode works only in an interactive session, not with $arg." >&2
      exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# 2. Version gate — an older Claude Code starts without Code Mode.
# ---------------------------------------------------------------------------
version="$(claude --version 2>/dev/null | awk 'NR==1 {print $1}')"
if [[ -z "$version" ]] ||
   [[ "$(printf '%s\n%s\n' "$min_version" "$version" | sort -V | sed -n 1p)" != "$min_version" ]]; then
  echo "claude-cm: ERROR — Code Mode needs Claude Code ${min_version} or later (found ${version:-unknown})." >&2
  echo "  Run: claude update" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 3. Clear what turns the feature-flag fetch off, keeping the opt-out for Bash.
# ---------------------------------------------------------------------------
unset CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC CLAUDE_CODE_DISABLE_FEATURE_FLAGS DISABLE_GROWTHBOOK

if [[ -n "${DO_NOT_TRACK:-}" || -n "${DISABLE_TELEMETRY:-}" ]]; then
  # Claude Code sources CLAUDE_ENV_FILE before each Bash command; an existing one
  # is chained first.
  env_dir="${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/claude-cm-$(id -u)"
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
  echo "claude-cm: DO_NOT_TRACK cleared for this claude process only; Bash commands keep it." >&2
fi

if [[ -n "${CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS:-}" ]]; then
  unset CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS
  echo "claude-cm: agent teams off for this session (Code Mode can't run with them)." >&2
fi

export CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1

# ---------------------------------------------------------------------------
# 4. Preflight — the other Code Mode requirements, as warnings.
# ---------------------------------------------------------------------------
# Checks, not fixes: silently rewriting auth, routing or an observability setup
# would be worse than saying why Code Mode will not start.
if [[ -n "${CLAUDE_CODE_ENABLE_TELEMETRY:-}" || -n "${ENABLE_BETA_TRACING_DETAILED:-}" ]]; then
  echo "claude-cm: WARNING — OpenTelemetry export is on; Code Mode will not start." >&2
  echo "  Run: env -u CLAUDE_CODE_ENABLE_TELEMETRY -u ENABLE_BETA_TRACING_DETAILED claude-cm" >&2
fi
if [[ -n "${ANTHROPIC_BASE_URL:-}" && "${ANTHROPIC_BASE_URL}" != *"api.anthropic.com"* ]]; then
  echo "claude-cm: WARNING — ANTHROPIC_BASE_URL=${ANTHROPIC_BASE_URL}" >&2
  echo "  Code Mode works only with the Anthropic API. Run: env -u ANTHROPIC_BASE_URL claude-cm" >&2
fi
if [[ -n "${ANTHROPIC_AUTH_TOKEN:-}" ]]; then
  echo "claude-cm: WARNING — ANTHROPIC_AUTH_TOKEN is set; Code Mode doesn't work with it." >&2
  echo "  Sign in with \`claude auth login\` or use ANTHROPIC_API_KEY, then run: env -u ANTHROPIC_AUTH_TOKEN claude-cm" >&2
fi
for provider in CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_FOUNDRY; do
  if [[ -n "${!provider:-}" ]]; then
    echo "claude-cm: WARNING — $provider is set; Code Mode works only with the Anthropic API." >&2
  fi
done
# Settings files can refuse it too; the environment can't override them.
settings_files=("${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json" ".claude/settings.json" ".claude/settings.local.json")
for f in "${settings_files[@]}"; do
  [[ -f "$f" ]] || continue
  if grep -q '"apiKeyHelper"' "$f"; then
    echo "claude-cm: WARNING — $f sets apiKeyHelper; Code Mode doesn't work with an API key helper." >&2
  fi
  if grep -q '"CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"' "$f"; then
    echo "claude-cm: WARNING — $f turns on agent teams; Code Mode can't run with them." >&2
  fi
  if grep -qE '"CLAUDE_CODE_EXPERIMENTAL_CODE_MODE"[[:space:]]*:[[:space:]]*"0"' "$f"; then
    echo "claude-cm: WARNING — $f sets CLAUDE_CODE_EXPERIMENTAL_CODE_MODE to 0, which keeps Code Mode off." >&2
  fi
done

# exec: signals and the exit code pass straight through; no wrapper lingers.
exec claude "$@"
