#!/usr/bin/env bash
# =============================================================================
# DevContainer Post-Create Script
# Runs after the container is created to perform runtime setup
# =============================================================================
set -e

echo "Running post-create setup..."

# Problems that leave the container short of what its config asked for. Each
# step warns and carries on, so one failed install does not stop the rest; the
# list is printed as a banner at the very end, where it cannot scroll past.
SETUP_PROBLEMS=()
setup_problem() {
    SETUP_PROBLEMS+=("$1")
    echo "  WARNING: $1"
}

# Nothing in this script can repair a root-owned path: vscode has no sudo (see
# the Dockerfile's sudo block). The Dockerfile pre-creates every volume
# mountpoint as vscode, so this is a volume from an older build, or one a root
# process wrote into. Name the host-side fix.
fix_owner() {
    setup_problem "$1 is not owned by vscode. From the host: docker exec -u root essentials-project-devcontainer chown -R vscode:vscode $1"
}

# =============================================================================
# Directory Ownership
# =============================================================================
echo "Ensuring directory permissions..."

# Ensure vscode user owns all home directories
directories=(
    "$HOME/.m2"
    "$HOME/.npm"
    "$HOME/.cache"
    "$HOME/.local"
    "$HOME/.config"
    "$HOME/.claude"
    "$HOME/.java"
)

for dir in "${directories[@]}"; do
    if [ -d "$dir" ]; then
        # Only fix ownership if not already correct
        if [ "$(stat -c '%U' "$dir" 2>/dev/null)" != "vscode" ]; then
            fix_owner "$dir"
        fi
    fi
done

# uv cache. UV_CACHE_DIR comes from devcontainer.json "remoteEnv", which reaches
# vscode's processes only — never root's, so a root-context `uv` run cannot
# leave root-owned entries in this volume. The default covers a tool that does
# not pass remoteEnv to lifecycle commands. The check looks two levels down: a
# root-owned bucket (e.g. sdists-v9) under a vscode-owned top directory makes
# every `uv` command fail with "Failed to initialize cache … Permission denied".
export UV_CACHE_DIR="${UV_CACHE_DIR:-$HOME/.uv-cache}"
mkdir -p "$UV_CACHE_DIR" 2>/dev/null || true
if [ -d "$UV_CACHE_DIR" ] && [ -n "$(find "$UV_CACHE_DIR" -maxdepth 2 ! -user vscode -print -quit 2>/dev/null)" ]; then
    fix_owner "$UV_CACHE_DIR"
fi

# =============================================================================
# NPM Configuration
# =============================================================================
echo "Configuring NPM..."

if command -v npm &> /dev/null; then
    # NPM lifecycle scripts are DISABLED for security (ignore-scripts=true).
    # To run scripts manually: npm run <script> --ignore-scripts=false
    echo "  NPM scripts are DISABLED for security (ignore-scripts=true)"
    npm config set ignore-scripts true

    # Node is installed via the devcontainer `node` feature, which uses nvm.
    # nvm is incompatible with `prefix` / `globalconfig` in ~/.npmrc and emits
    # `Your user's .npmrc file ... has a globalconfig and/or a prefix setting`
    # on every shell startup if either is set. Globals under nvm live in
    # $NVM_DIR/versions/node/<version>/bin — already on PATH via nvm.sh, no
    # custom prefix needed. Heal any leftover entries from earlier setups that
    # wrote `prefix=$HOME/.npm-global` into these dotfiles (both ~/.npmrc and
    # ~/.bashrc are on a persisted volume, so they survive container rebuilds).
    if [ -f "$HOME/.npmrc" ]; then
        sed -i '/^[[:space:]]*prefix[[:space:]]*=/d; /^[[:space:]]*globalconfig[[:space:]]*=/d' "$HOME/.npmrc"
    fi
    if [ -f "$HOME/.bashrc" ]; then
        sed -i '\|export PATH="\$HOME/\.npm-global/bin:\$PATH"|d' "$HOME/.bashrc"
    fi

    echo "  Node.js version: $(node --version)"
    echo "  NPM version: $(npm --version)"
fi

# =============================================================================
# Language Servers (always-on for baseline runtimes)
# typescript-language-server — requires npm (Node.js DevContainer feature, only
#   available at runtime, not at Dockerfile build time).
# pyright — requires uv (installed at Dockerfile build time).
# Both installs are idempotent: skip if the binary is already on PATH.
# =============================================================================
echo "Setting up language servers..."

# TypeScript LSP. --ignore-scripts matches the project's NPM security posture.
if command -v npm &> /dev/null; then
    if ! command -v typescript-language-server &> /dev/null; then
        echo "  Installing typescript-language-server (npm)..."
        npm install -g --ignore-scripts typescript typescript-language-server \
            || echo "  WARNING: typescript-language-server install failed."
    fi
fi

# Pyright (uv tool).
if command -v uv &> /dev/null; then
    if ! command -v pyright &> /dev/null; then
        echo "  Installing pyright (uv tool)..."
        UV_TOOL_BIN_DIR="$HOME/.local/bin" \
        UV_TOOL_DIR="$HOME/.local/share/uv/tools" \
        uv tool install pyright \
            || echo "  WARNING: pyright install failed."
    fi
fi

# =============================================================================
# Claude Code CLI Installation (native install)
# =============================================================================
if [ "${INSTALL_CLAUDE:-false}" = "true" ]; then
    echo "Installing Claude Code CLI..."

    curl -fsSL https://claude.ai/install.sh | bash
    echo "  Claude Code CLI installed"
fi

# =============================================================================
# Claude Code default permission mode → "auto" (Conditional)
# Auto mode MUST live in USER settings ($CLAUDE_CONFIG_DIR/settings.json, default
# ~/.claude/settings.json): as of Claude Code v2.1.142 the "auto" value of
# permissions.defaultMode is IGNORED in project/local .claude/settings.json so a
# repo can't grant itself auto mode. It therefore cannot be baked into the
# committed project settings the way acceptEdits/bypassPermissions are — those
# are written to .claude/settings.json at generation time and work fine there.
# Gated on CLAUDE_DEFAULT_MODE_AUTO, which the generator sets in containerEnv
# only when the user chose Auto mode. Best-effort: auto mode also needs an
# eligible account (Claude Code v2.1.83+); an ineligible session silently starts
# in "default" mode. The merge is idempotent so it survives rebuilds without
# clobbering other user settings.
# =============================================================================
if [ "${CLAUDE_DEFAULT_MODE_AUTO:-false}" = "true" ] && [ "${INSTALL_CLAUDE:-false}" = "true" ]; then
    echo "Setting Claude Code default permission mode to 'auto' (user settings)..."
    CLAUDE_USER_SETTINGS="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json"
    mkdir -p "$(dirname "$CLAUDE_USER_SETTINGS")"
    if command -v jq &> /dev/null; then
        _tmp="$(mktemp)"
        if [ -s "$CLAUDE_USER_SETTINGS" ] && jq -e . "$CLAUDE_USER_SETTINGS" > /dev/null 2>&1; then
            jq '.permissions.defaultMode = "auto"' "$CLAUDE_USER_SETTINGS" > "$_tmp" && mv "$_tmp" "$CLAUDE_USER_SETTINGS"
        else
            # Missing/empty/invalid file — start fresh.
            jq -n '{ permissions: { defaultMode: "auto" } }' > "$CLAUDE_USER_SETTINGS"
            rm -f "$_tmp"
        fi
        echo "  → permissions.defaultMode=auto in $CLAUDE_USER_SETTINGS"
    else
        echo "  WARNING: jq not found; skipping auto-mode default (set permissions.defaultMode=auto in ~/.claude/settings.json manually)."
    fi
fi

# =============================================================================
# Claude Code status line (Conditional)
# Renders model, context usage, git branch and worktree in the footer:
#   <model>[:effort] [<fast>] │ <used>/<window> (<pct>%) │ <branch> [<worktree>] │ <dir>
# Claude Code has NO built-in setting for this (no showContext / contextMeter key
# exists) — statusLine is the only supported route, and it runs a command per
# render with the session state as JSON on stdin.
#
# USER settings, not project settings, on purpose: project settings WIN over user
# settings, so a tracked statusLine would silently replace whatever footer every
# contributor already chose. A footer is a personal display preference, unlike
# worktree.baseRef which is a repo-level opinion about behaviour.
#
# Idempotent and non-destructive: an existing statusLine (the developer's own, or
# one from a previous run pointing elsewhere) is never overwritten. To re-point it
# at this script, delete the statusLine key and re-create the container.
# =============================================================================
if [ "${CLAUDE_STATUSLINE:-false}" = "true" ] && [ "${INSTALL_CLAUDE:-false}" = "true" ]; then
    echo "Configuring Claude Code status line (user settings)..."
    # Sibling of this script — never hardcode /workspace, the generated project's
    # workspace folder is whatever the user named it.
    STATUSLINE_SH="$(cd "$(dirname "$0")" && pwd)/statusline.sh"
    CLAUDE_USER_SETTINGS="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json"
    if [ ! -f "$STATUSLINE_SH" ]; then
        echo "  WARNING: $STATUSLINE_SH not found; skipping status line."
    elif ! command -v jq &> /dev/null; then
        echo "  WARNING: jq not found; skipping status line."
    else
        chmod +x "$STATUSLINE_SH" 2>/dev/null || true
        mkdir -p "$(dirname "$CLAUDE_USER_SETTINGS")"
        if [ ! -s "$CLAUDE_USER_SETTINGS" ] || ! jq -e . "$CLAUDE_USER_SETTINGS" > /dev/null 2>&1; then
            echo '{}' > "$CLAUDE_USER_SETTINGS"
        fi
        if [ "$(jq -r 'has("statusLine")' "$CLAUDE_USER_SETTINGS")" = "true" ]; then
            echo "  → statusLine already set in $CLAUDE_USER_SETTINGS — left untouched."
        else
            _tmp="$(mktemp)"
            if jq --arg cmd "$STATUSLINE_SH" \
                  '.statusLine = { type: "command", command: $cmd, padding: 0 }' \
                  "$CLAUDE_USER_SETTINGS" > "$_tmp"; then
                mv "$_tmp" "$CLAUDE_USER_SETTINGS"
                echo "  → statusLine → $STATUSLINE_SH"
            else
                rm -f "$_tmp"
                echo "  WARNING: failed to write statusLine to $CLAUDE_USER_SETTINGS."
            fi
        fi
    fi
fi

# =============================================================================
# graphify Knowledge Graph (Conditional) — knowledge-graph indexer slot
# https://github.com/safishamsi/graphify (MIT). A whole-system knowledge graph
# (code + SQL + infra + docs) delivered as an agent skill + CLI. Installed as a
# uv tool (the package is `graphifyy` with a double-y; the CLI is `graphify`).
# CODE-ONLY by default: `graphify update` re-extracts code via tree-sitter
# locally and makes ZERO model calls — nothing leaves the machine. The skill is installed
# project-scoped (.claude/skills/graphify) so it's reproducible/committable, and
# post-commit/post-checkout git hooks keep the graph fresh (code rebuilds are
# LLM-free).
# =============================================================================
if [ "${INSTALL_GRAPHIFY:-false}" = "true" ]; then
    echo "Setting up graphify (knowledge graph)..."
    # The Claude CLI and uv-installed tools both land in ~/.local/bin.
    export PATH="$HOME/.local/bin:$PATH"
    if command -v uv &> /dev/null; then
        if ! command -v graphify &> /dev/null; then
            echo "  Installing graphifyy (uv tool, with SQL grammar)..."
            # [sql] extra pulls tree-sitter-sql so .sql files are indexed too;
            # without it graphify warns and skips SQL sources (#1745).
            uv tool install "graphifyy[sql]" 2>&1 || echo "  WARNING: graphify install failed. Retry later with: uv tool install \"graphifyy[sql]\""
        else
            echo "  graphify already installed ($(command -v graphify))"
            # Reassert the SQL grammar in case this is a pre-[sql] install being
            # reprovisioned on a reused uv-tool volume (idempotent; #1745).
            uv tool install "graphifyy[sql]" 2>&1 | grep -iv "already installed" || true
        fi

        if command -v graphify &> /dev/null; then
            # Install the skill project-scoped (.claude/skills/graphify). Idempotent
            # (re-asserts the skill files). --platform defaults to Claude Code.
            #
            # ALSO REWRITES THE TRACKED root CLAUDE.md. `install --project` calls
            # _replace_or_append_section(content, "## graphify", <packaged template>)
            # (graphify/install.py): it finds the LAST line that is exactly
            # "## graphify" and replaces everything from there to the next "## "
            # heading (or EOF) with graphify/always_on/claude-md.md. Hand-written
            # bullets inside that section are silently lost on every rebuild — this
            # is what reverted the query-shaping rules from commit caa652fd. The
            # match is exact-line only (they anchored it in #1688), and any other H2
            # terminates the replaced range, so durable graphify guidance lives under
            # "## Knowledge graph queries" instead. Same applies to the "# graphify"
            # block in .claude/CLAUDE.md, which graphify also owns (skill
            # registration). Do not "fix" the stock section — it is regenerated.
            if [ -d "/workspace" ]; then
                ( cd /workspace && graphify install --project 2>&1 ) \
                    || echo "  WARNING: 'graphify install --project' failed."
                # (Re)install git hooks every setup — the hook re-embeds the current
                # interpreter path, so this survives interpreter/tool upgrades.
                ( cd /workspace && graphify hook install 2>&1 ) \
                    || echo "  INFO: 'graphify hook install' skipped (not a git repo yet?)."

                # Make graphify's committed PreToolUse hook portable. On every
                # `install --project`, graphify hardcodes the absolute exe path
                # (/home/vscode/.local/bin/graphify hook-guard ...) into the TRACKED
                # .claude/settings.json. That path does not exist for contributors
                # working outside this devcontainer, so their every Bash/Grep/Read/Glob
                # tool call would fire a failing hook. Rewrite the command to a guarded,
                # PATH-relative form ("command -v graphify ... && graphify hook-guard X ||
                # true") that runs where graphify is installed and silently no-ops where
                # it is not. Idempotent, and re-applied here after each install because
                # graphify overwrites the hook back to the absolute path. See
                # docs/ai-tooling.md for the rationale.
                if [ -f /workspace/.claude/settings.json ] && command -v jq &> /dev/null; then
                    _hooktmp="$(mktemp)"
                    if jq '
                      if (.hooks?.PreToolUse | type) == "array"
                      then .hooks.PreToolUse |= map(
                        if (.hooks | type) == "array"
                        then .hooks |= map(
                          if ((.command? // "") | test("graphify hook-guard"))
                          then .command = ("command -v graphify >/dev/null 2>&1 && graphify hook-guard "
                                           + ((.command | capture("hook-guard (?<rest>[a-z]+(?: --strict)?)")).rest)
                                           + " || true")
                          else . end)
                        else . end)
                      else . end
                    ' /workspace/.claude/settings.json > "$_hooktmp" 2>/dev/null; then
                        mv "$_hooktmp" /workspace/.claude/settings.json
                        echo "  Made graphify PreToolUse hook portable (guarded, PATH-relative)."
                    else
                        rm -f "$_hooktmp"
                        echo "  WARNING: could not rewrite graphify hook to portable form (jq failed)."
                    fi
                fi
            fi

            # Initial local index. `graphify update` re-extracts files via
            # tree-sitter with ZERO model calls (fully local), and is exactly what
            # the post-commit/post-checkout git hooks run. Non-fatal if it fails.
            # (It indexes code AND keeps document/markdown nodes — it is not
            # docs-blind; only the optional semantic LLM layer, which needs an API
            # key and we never run, is skipped.)
            #
            # WHY `update` can be "rejected": a graph previously built by the heavier
            # `graphify extract` carries extra reference-STUB nodes — duplicate
            # unresolved symbols plus JDK/stdlib type stubs (Boolean, Collection, …).
            # A fresh `update` RESOLVES those references to their defining file and
            # prunes the stdlib stubs, so it legitimately has FEWER nodes: a cleaner,
            # better-resolved graph, NOT data loss (every source file stays indexed).
            # graphify's node-count guard only sees "fewer" and refuses to overwrite
            # unless --force / GRAPHIFY_FORCE=1 (set in devcontainer.json). We make the
            # leaner `update` graph authoritative because it is complete for code; the
            # only thing it cannot produce is the LLM-inferred semantic layer (needs a
            # key), which is additive inference, not an accuracy fix.
            if [ -d "/workspace" ] && [ "$(ls -A /workspace 2>/dev/null)" ]; then
                echo "  Building code-only graph (graphify update — local, no model calls)..."
                ( cd /workspace && graphify update /workspace 2>&1 ) \
                    || echo "  WARNING: graphify indexing failed (will retry on next container start). Rebuild manually with: graphify update /workspace"
            else
                echo "  Workspace empty — skipping initial index. Run 'graphify update /workspace' after cloning your project."
            fi

            # Gitignore only the rebuildable cache. Managed block (sentinel
            # markers) so the reverse path can strip it cleanly. grep-guarded → idempotent.
            if [ -d "/workspace" ] && ! grep -q '>>> devcontainer-stack (managed) >>>' /workspace/.gitignore 2>/dev/null; then
                printf '\n# >>> devcontainer-stack (managed) >>>\n# graphify: ignore the rebuildable graph output + the self-installed skill.\ngraphify-out\n.claude/skills/graphify\n# <<< managed <<<\n' >> /workspace/.gitignore
                echo "  Added graphify entries to /workspace/.gitignore (managed block)."
            fi
        fi
    else
        echo "  WARNING: uv not found — cannot install graphify."
    fi
    echo "  graphify setup complete"
fi

# =============================================================================
# Repository git hooks — always on, not an optional tool
# .githooks/pre-commit keeps essentials-plugin/references/llm/ in step with LLM/.
# The installer writes a small wrapper into .git/hooks rather than setting
# core.hooksPath, so graphify's own git hooks (installed above) stay where they
# are. Idempotent. Contributors outside the devcontainer run the same script once
# (README "Editing the LLM docs").
# =============================================================================
if [ -e "/workspace/.git" ]; then
    echo "Installing repository git hooks..."
    ( cd /workspace && sh scripts/install-git-hooks.sh ) \
        || echo "  WARNING: repository git hooks not installed. Retry with: scripts/install-git-hooks.sh"
fi

# =============================================================================
# The in-repo essentials plugin — loaded from disk in every terminal session
# `claude` started from a container shell gets `--plugin-dir` pointing at
# essentials-plugin/, so the session runs the plugin as it is on disk and an
# edit applies on /reload-plugins. Installing it from the repository's own
# marketplace instead would run a cached copy of the last commit. Sessions an
# IDE starts do not read ~/.bashrc and get no essentials plugin. ~/.bashrc is on
# a persisted volume, so the line is replaced rather than appended again.
# =============================================================================
if [ -d "/workspace/essentials-plugin" ]; then
    touch "$HOME/.bashrc"
    sed -i '\|# essentials-plugin-dir$|d' "$HOME/.bashrc"
    echo "alias claude='claude --plugin-dir /workspace/essentials-plugin' # essentials-plugin-dir" >> "$HOME/.bashrc"
    echo "  claude in a container shell loads /workspace/essentials-plugin"
fi

# =============================================================================
# headroom (Conditional) — context-compression layer (MCP mode)
# https://github.com/headroomlabs-ai/headroom (Apache-2.0). Compresses large
# tool outputs / files before they reach the LLM. Installed as a uv tool with
# the LIGHT [code,mcp] extras (AST-aware code compression + the MCP server) —
# this deliberately AVOIDS the heavy [proxy] extra (transformers + onnxruntime)
# that `headroom wrap claude` requires. Instead we register
# headroom's MCP server with Claude Code (`headroom mcp install`), exposing the
# on-demand CCR tools (mcp__headroom__headroom_compress / _retrieve / _stats).
# The always-on proxy (full-traffic auto-compression) is left OFF — if you ever
# want it, run `headroom proxy` and set ANTHROPIC_BASE_URL=http://127.0.0.1:8787
# (that path needs the [proxy] extra). State lives on the ~/.headroom named
# volume; the update check is disabled.
#
# WHAT "needs the [proxy] extra" LOOKS LIKE (verified on headroom-ai 0.34.0), so
# nobody rediscovers it: `headroom proxy` / `headroom wrap claude` dies at
# startup with `ImportError: Using http2=True, but the 'h2' package is not
# installed` — h2 arrives via [proxy]'s `httpx[http2]` pin, and the proxy
# defaults to HTTP/2. `headroom proxy --no-http2` (env: HEADROOM_HTTP2=0) does
# get past startup and binds :8787, but that is a false summit: 9 of the 13
# [proxy] requirements are absent under [code,mcp] (orjson, h2, openai, magika,
# zstandard, websockets, onnxruntime, transformers, sqlite-vec), and orjson +
# magika sit on the request hot path. So there is no cheap subset — resolving
# [code,mcp,proxy] pulls 28 packages including the ONNX/transformers ML stack.
#
# If you ever opt in: add `proxy` to the extras on BOTH `uv tool install` lines
# below and DROP both `--with` flags — [proxy] already declares fastapi>=0.100.0
# and a bounded mcp>=1.28.1,<2.0.0, making the two workarounds below redundant.
# Also set HEADROOM_HTTP2=0 (headroom's own --http2 help warns HTTP/2 hits
# SSLV3_ALERT_BAD_RECORD_MAC when many concurrent streams are cancelled, which
# is exactly Claude Code's traffic shape). Do NOT put ANTHROPIC_BASE_URL in
# devcontainer.json containerEnv: a proxy that is not running would then break
# Claude Code container-wide. Route per-launch via `headroom wrap claude`.
# Note the payoff is weak on a subscription seat — billing is not per-token, so
# headroom's cost figures do not apply; the only benefit is hitting usage limits
# less often.
#
# `--with fastapi`: UPSTREAM BUG (headroom-ai 0.32.1). fastapi is declared only
# under the [proxy]/[dev] extras, but headroom's CLI eagerly registers every
# subcommand at import time, so `headroom mcp serve` drags in the proxy chain
# (cli → doctor → wrap → providers.aider → proxy.request_scope → `from fastapi
# import Request`) and dies with ModuleNotFoundError. That makes [code,mcp] not
# self-sufficient. We inject fastapi alone (~pure-python, no ML deps) rather
# than pulling all of [proxy]. Drop this once upstream makes the import lazy.
#
# `--with "mcp<2"`: UPSTREAM BUG (headroom-ai 0.32.1). headroom declares an
# unbounded `mcp>=1.0.0`, but the MCP Python SDK 2.0.0 removed the low-level
# decorator API that headroom's server is written against — `Server` no longer
# has .list_tools()/.call_tool(). With mcp 2.x resolved, `headroom mcp serve`
# crashes at startup (AttributeError in ccr/mcp_server.py::_setup_handlers)
# before completing the MCP handshake, so Claude Code reports the server as
# failed. Pin to the 1.x line. Drop this once upstream supports mcp 2.x.
# =============================================================================
if [ "${INSTALL_HEADROOM:-false}" = "true" ]; then
    echo "Setting up headroom (context compression — MCP mode)..."
    export PATH="$HOME/.local/bin:$PATH"
    # No egress + deterministic local state dir (named volume). Defence-in-depth
    # alongside the containerEnv vars, so `uv tool install`, `headroom mcp install`
    # and any headroom process this script starts are covered even if the container
    # env is edited away. HEADROOM_BEACON is the one that matters: since 0.35.0 the
    # anonymous session-summary upload to Headroom Labs is ON by default and
    # fail-open, and it fires on the MCP path (after every headroom_compress).
    # HEADROOM_OFFLINE is upstream's fail-closed master switch for all of it.
    export HEADROOM_BEACON=off
    export HEADROOM_OFFLINE=1
    export HEADROOM_UPDATE_CHECK=off
    export HEADROOM_WORKSPACE_DIR="$HOME/.headroom"
    if command -v uv &> /dev/null; then
        if ! command -v headroom &> /dev/null; then
            echo "  Installing headroom-ai[code,mcp] (uv tool — light extras, no proxy/ML deps)..."
            uv tool install "headroom-ai[code,mcp]" --with fastapi --with "mcp<2" 2>&1 \
                || echo "  WARNING: headroom install failed. Retry with: uv tool install \"headroom-ai[code,mcp]\" --with fastapi --with \"mcp<2\"."
        else
            echo "  headroom already installed ($(command -v headroom))"
            # Self-heal an env that predates the mcp<2 pin (or was pushed onto
            # mcp 2.x by `uv tool upgrade`): re-pin in place so `headroom mcp
            # serve` can start. See the mcp<2 note above.
            headroom_py="$(uv tool dir 2>/dev/null)/headroom-ai/bin/python"
            if [ -x "$headroom_py" ] && ! "$headroom_py" -c \
                 'import mcp.server, sys; sys.exit(0 if hasattr(mcp.server.Server, "list_tools") else 1)' &> /dev/null; then
                echo "  Re-pinning MCP SDK to 1.x (upstream mcp>=1.0.0 is unbounded)..."
                uv tool install "headroom-ai[code,mcp]" --force --with fastapi --with "mcp<2" 2>&1 \
                    || echo "  WARNING: could not re-pin mcp<2 — 'headroom mcp serve' may fail to start."
            fi
        fi

        # Heal the ~/.headroom named-volume mountpoint if it ended up root-owned.
        if [ -d "$HOME/.headroom" ] && [ "$(stat -c '%U' "$HOME/.headroom" 2>/dev/null)" != "vscode" ]; then
            fix_owner "$HOME/.headroom"
        fi

        # Register headroom's MCP server with Claude Code (--force → idempotent
        # across rebuilds). This exposes the on-demand compress/retrieve/stats
        # tools; it does NOT start the always-on proxy and needs no API key.
        if [ "${INSTALL_CLAUDE:-false}" = "true" ] && command -v claude &> /dev/null && command -v headroom &> /dev/null; then
            echo "  Registering headroom MCP server with Claude Code (headroom mcp install)..."
            headroom mcp install --agent claude --force 2>&1 \
                && echo "    → registered (reverse with: headroom mcp uninstall)" \
                || echo "  WARNING: 'headroom mcp install' failed — register manually: headroom mcp install --agent claude"
        elif [ "${INSTALL_CLAUDE:-false}" != "true" ]; then
            echo "  NOTE: Claude Code not installed (INSTALL_CLAUDE=false) — headroom installed but MCP server not registered."
        fi
    else
        echo "  WARNING: uv not found — cannot install headroom."
    fi
    echo "  headroom setup complete"
fi

# =============================================================================
# rtk — Rust Token Killer (Conditional, part of the local-first stack)
# https://github.com/rtk-ai/rtk (Apache-2.0). Transparent CLI-output compression:
# it rewrites Claude Code's Bash commands (e.g. `cargo test` → `rtk cargo test`)
# via a PreToolUse hook, shrinking command output 60-90% before it hits context.
# The `rtk` binary is installed at build time (see Dockerfile). The hook is the
# PROJECT's: .claude/hooks/rtk-bash-rewrite.sh, wired in the tracked
# .claude/settings.json. It calls `rtk hook claude` and passes the rewrite on,
# except that inside .claude/worktrees/ it never puts git behind rtk — Claude Code
# refuses `rtk git …` from an agent started with isolation "worktree" (it cannot
# see that the command targets the agent's own worktree), so with rtk's global
# hook a worktree agent could not `git add` or `git commit` at all. This is the
# lean, proxy-free way to get rtk's job — independent of headroom.
# =============================================================================
if [ "${INSTALL_RTK:-false}" = "true" ]; then
    echo "Setting up rtk (CLI-output compression)..."
    # Hard override: blocks the daily ping AND suppresses the consent prompt that
    # `rtk init` would otherwise raise (upstream #1307). The value is compared for
    # literal string equality against "1" — "true"/"yes" silently do nothing.
    export RTK_TELEMETRY_DISABLED=1
    if [ "${INSTALL_CLAUDE:-false}" = "true" ] && command -v claude &> /dev/null && command -v rtk &> /dev/null; then
        # 1. The hook script. Written only while it carries the managed marker, so a
        #    project that customises it keeps its version (same rule as graphify-nudge.sh).
        mkdir -p /workspace/.claude/hooks
        _rtk_hook=/workspace/.claude/hooks/rtk-bash-rewrite.sh
        if [ ! -f "$_rtk_hook" ] || grep -q 'managed by devcontainer-generator' "$_rtk_hook" 2>/dev/null; then
            cat > "$_rtk_hook" <<'RTK_HOOK_EOF'
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
RTK_HOOK_EOF
            chmod +x "$_rtk_hook"
        fi
        # 2. Wire it in the tracked .claude/settings.json (once; created if absent).
        if command -v jq &> /dev/null; then
            _rtk_proj=/workspace/.claude/settings.json
            [ -f "$_rtk_proj" ] || echo '{}' > "$_rtk_proj"
            if ! jq -e '[.hooks.PreToolUse[]?.hooks[]?.command // empty | select(test("rtk-bash-rewrite\\.sh"))] | length > 0' "$_rtk_proj" >/dev/null 2>&1; then
                _rtk_tmp="$(mktemp)"
                if jq '.hooks.PreToolUse = ([{"matcher":"Bash","hooks":[{"type":"command","command":"bash \"${CLAUDE_PROJECT_DIR:-.}/.claude/hooks/rtk-bash-rewrite.sh\"","timeout":10}]}] + (.hooks.PreToolUse // []))' \
                      "$_rtk_proj" > "$_rtk_tmp" 2>/dev/null; then
                    mv "$_rtk_tmp" "$_rtk_proj"
                    echo "    → hook wired in .claude/settings.json (commit it with .claude/hooks/rtk-bash-rewrite.sh)"
                else
                    rm -f "$_rtk_tmp"
                    echo "  WARNING: could not wire the rtk hook into .claude/settings.json (jq failed)."
                fi
            fi
        else
            echo "  WARNING: jq not found — the rtk hook is not wired; Bash commands are not rewritten."
        fi
        # 3. rtk's docs, once. `rtk init -g --auto-patch` writes three USER-GLOBAL
        #    things (on the Claude config named volume, none in the project repo):
        #      a. a PreToolUse hook in ~/.claude/settings.json  (stripped in step 4)
        #      b. ~/.claude/RTK.md — a SHORT companion (~200 tokens) covering the rtk-ONLY
        #         meta commands the hook cannot rewrite for you (rtk gain / discover / proxy)
        #      c. a one-line `@RTK.md` IMPORT in ~/.claude/CLAUDE.md that pulls (b) in
        #    (c) is an import, NOT the full catalog: the ~2k-token <!-- rtk-instructions -->
        #    command reference is what PROJECT-scoped `rtk init` (no -g) writes inline into
        #    a project CLAUDE.md. The global path is deliberately the lean variant — out of
        #    the repo and roughly an order of magnitude cheaper in always-on context. Do not
        #    "fix" the missing catalog; the hook rewrites commands whether or not it is loaded.
        #    The gate is RTK.md, not `rtk init --show`: that ALWAYS exits 0 (a status
        #    display), and its "hook configured" line is now false by design.
        #    --auto-patch: no interactive prompt (post-create runs non-interactively).
        if [ ! -f "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/RTK.md" ]; then
            echo "  Installing rtk docs (rtk init -g --auto-patch)..."
            ( cd "$HOME" && rtk init -g --auto-patch ) 2>&1 \
                || echo "  WARNING: 'rtk init -g --auto-patch' failed — rtk's docs are missing; the project hook still works."
        fi
        # 4. Strip rtk's global hook, on every run — a volume from an older generation
        #    still has one, and two hooks rewriting the same command race.
        _rtk_user="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json"
        if [ -f "$_rtk_user" ] && command -v jq &> /dev/null \
           && jq -e '[.hooks.PreToolUse[]?.hooks[]?.command // empty | select(test("^rtk hook claude$|rtk-rewrite\\.sh"))] | length > 0' "$_rtk_user" >/dev/null 2>&1; then
            _rtk_tmp="$(mktemp "${_rtk_user}.XXXXXX")"
            if jq '.hooks.PreToolUse |= (map(.hooks |= map(select((.command // "") | test("^rtk hook claude$|rtk-rewrite\\.sh") | not)))
                                         | map(select((.hooks | length) > 0)))' "$_rtk_user" > "$_rtk_tmp"; then
                mv "$_rtk_tmp" "$_rtk_user"
                echo "    → removed rtk's global hook from ~/.claude/settings.json (the project hook runs it)"
            else
                rm -f "$_rtk_tmp"
                echo "  WARNING: could not strip rtk's global hook from $_rtk_user — remove the 'rtk hook claude' entry by hand."
            fi
        fi
        # Report what actually landed so a partial install is visible rather than silent.
        _rtk_claude_md="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/CLAUDE.md"
        if [ -f "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/RTK.md" ]; then
            echo "    → ~/.claude/RTK.md present (rtk-only meta commands)"
        else
            echo "    NOTE: ~/.claude/RTK.md missing — 'rtk gain / discover / proxy' go undocumented."
        fi
        if grep -qE '^\s*@RTK\.md\s*$|rtk-instructions' "$_rtk_claude_md" 2>/dev/null; then
            echo "    → user CLAUDE.md imports RTK.md (global — nothing added to your repo)"
        else
            echo "    NOTE: no @RTK.md import in $_rtk_claude_md. The hook still rewrites Bash"
            echo "          commands without it; only the rtk-only meta commands go undocumented."
            echo "          Re-run with: rtk init -g"
        fi
    elif ! command -v rtk &> /dev/null; then
        echo "  NOTE: rtk binary not found (INSTALL_RTK build arg not applied?) — skipping hook install."
    elif [ "${INSTALL_CLAUDE:-false}" != "true" ]; then
        echo "  NOTE: Claude Code not installed (INSTALL_CLAUDE=false) — rtk binary present but its hook targets Claude Code."
    fi
    echo "  rtk setup complete"
fi

# =============================================================================
# Native LSP layer (Conditional) — Claude Code's built-in LSP tool
# The graph/compression/governance tools above are tree-sitter (syntactic) only;
# they provide NO compiler-accurate go-to-definition, find-references, hover
# types, or live diagnostics. This layer supplies that via Claude Code's native
# LSP tool + per-language Code-Intelligence plugins from the OFFICIAL,
# Anthropic-curated marketplace (claude-plugins-official). The language-server
# binaries are already installed/gated elsewhere (jdtls, csharp-ls,
# kotlin-language-server, rust-analyzer, typescript-language-server, pyright) and
# the official plugins require exactly those binaries on PATH. Registration is
# idempotent (claude plugin install no-ops if already present).
# =============================================================================
if [ "${ENABLE_LSP_TOOL:-0}" = "1" ] && [ "${INSTALL_CLAUDE:-false}" = "true" ] && command -v claude &> /dev/null; then
    echo "Registering native LSP plugins (official marketplace)..."
    export PATH="$HOME/.local/bin:$PATH"
    # Ensure the official marketplace is known (auto-available on recent Claude
    # Code; add explicitly as a no-op safety net). Failures are non-fatal.
    claude plugin marketplace add anthropics/claude-plugins-official 2>/dev/null \
        || true

    _lsp_install() {
        # $1 = plugin name on claude-plugins-official
        claude plugin install "$1@claude-plugins-official" --scope project 2>&1 \
            || echo "  INFO: LSP plugin '$1' not installed automatically — install from /plugin (Discover) if needed."
    }

    # Always-on runtimes (Node + Python baselines).
    _lsp_install typescript-lsp
    _lsp_install pyright-lsp
    # Gated per enabled runtime — binary is on PATH only when that runtime is installed.
    [ "${INSTALL_JAVA:-false}"       = "true" ] && _lsp_install jdtls-lsp
    [ "${INSTALL_KOTLIN_LSP:-false}" = "true" ] && _lsp_install kotlin-lsp
    echo "  LSP plugin registration complete (ENABLE_LSP_TOOL=1)."
fi

# =============================================================================
# Python/UV Configuration
# =============================================================================
echo "Configuring Python/UV..."

# No /usr/local/bin/python symlink: creating one needs root, which vscode no
# longer has. The Python feature installs under /usr/local/python/current/bin,
# and python.defaultInterpreterPath in devcontainer.json points there directly.

# Verify UV installation
if command -v uv &> /dev/null; then
    echo "  UV version: $(uv --version)"
fi

# Verify Python installation
if command -v python &> /dev/null; then
    echo "  Python version: $(python --version)"
fi

# =============================================================================
# Java/Maven Configuration
# =============================================================================
if [ "${INSTALL_JAVA:-false}" = "true" ]; then
    echo "Configuring Java/Maven..."

    # Source Maven profile
    [ -f /etc/profile.d/maven.sh ] && source /etc/profile.d/maven.sh

    # Create Maven settings if not exists
    if [ ! -f "$HOME/.m2/settings.xml" ]; then
        mkdir -p "$HOME/.m2"
        cat > "$HOME/.m2/settings.xml" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
    <localRepository>${user.home}/.m2/repository</localRepository>
</settings>
EOF
    fi

    # Testcontainers container-reuse opt-in (~/.testcontainers.properties is
    # not on a persisted volume; re-write idempotently on every post-create).
    if ! grep -q '^testcontainers.reuse.enable=true' "$HOME/.testcontainers.properties" 2>/dev/null; then
        echo 'testcontainers.reuse.enable=true' >> "$HOME/.testcontainers.properties"
        echo "  Testcontainers reuse: enabled (~/.testcontainers.properties)"
    fi

    # Verify installations
    if command -v java &> /dev/null; then
        echo "  Java version: $(java -version 2>&1 | head -n 1)"
    fi
    if command -v mvn &> /dev/null; then
        echo "  Maven version: $(mvn -version 2>&1 | head -n 1)"
    fi
    if command -v jdtls &> /dev/null; then
        echo "  JDT-LS: $(readlink -f "$(command -v jdtls)")"
    fi
    if [ "${INSTALL_KOTLIN_LSP:-false}" = "true" ] && command -v kotlin-language-server &> /dev/null; then
        echo "  Kotlin LSP: $(readlink -f "$(command -v kotlin-language-server)")"
    fi
fi

# =============================================================================
# Git Configuration
# =============================================================================
echo "Configuring Git..."

# Source per-developer overrides (git identity, personal env). This file is
# gitignored — copy .devcontainer/.env.local.example to .env.local to create it.
ENV_LOCAL="/workspace/.devcontainer/.env.local"
ENV_LOCAL_EXAMPLE="/workspace/.devcontainer/.env.local.example"
if [ -f "$ENV_LOCAL" ]; then
    echo "  Loading $ENV_LOCAL"
    # Strip CRLF on the fly — .env.local is gitignored, so Windows editors
    # may save it with CRLF and break sourcing (values get a trailing \r).
    # shellcheck disable=SC1090
    set -a
    . <(tr -d '\r' < "$ENV_LOCAL")
    set +a
fi

# Apply git identity (populated by .env.local, or containerEnv if baked).
if [ -n "${GIT_USER_NAME}" ]; then
    git config --global user.name "${GIT_USER_NAME}"
    echo "  Git user.name: ${GIT_USER_NAME}"
fi
if [ -n "${GIT_USER_EMAIL}" ]; then
    git config --global user.email "${GIT_USER_EMAIL}"
    echo "  Git user.email: ${GIT_USER_EMAIL}"
fi

# Big warning when the per-developer .env.local mechanism is the intended
# path (example file exists) but the developer has not created their copy.
# This re-prints on every container start until .env.local exists, so it's
# hard to miss.
if [ -f "$ENV_LOCAL_EXAMPLE" ] && [ ! -f "$ENV_LOCAL" ]; then
    cat <<'WARN'

============================================================================

  WARNING: GIT IDENTITY NOT CONFIGURED

  .devcontainer/.env.local is missing. Git has no name/email — your
  next `git commit` will fail (or record an empty author).

  Quick setup — run in the project root (host or container):

      cp .devcontainer/.env.local.example .devcontainer/.env.local

  Then edit .devcontainer/.env.local and set your name + email.
  Re-run post-create.sh, or rebuild the container, to apply:

      bash .devcontainer/scripts/post-create.sh

  Details: README.md > Git Identity

============================================================================

WARN
elif [ -z "${GIT_USER_NAME}" ] && [ -z "${GIT_USER_EMAIL}" ]; then
    # Bake strategy was chosen but values were left empty, or someone
    # deleted .env.local.example. Smaller hint — there's no canonical fix.
    echo "  Git identity not configured. Set GIT_USER_NAME / GIT_USER_EMAIL"
    echo "  in devcontainer.json -> containerEnv, or run"
    echo "  'git config --global user.name/email' inside the container."
    echo "  See README.md -> Git Identity for details."
fi

git config --global --add safe.directory /workspace 2>/dev/null || true

# Bind-mount stat-race mitigation. /workspace is a `consistency=delegated` bind
# mount, so inode/ctime drift makes git's default stat check report phantom
# "local changes" on a clean tree — which aborts `git rebase`/`checkout` with
# "Your local changes ... would be overwritten" (the file list even varies run
# to run). checkStat=minimal compares only mtime+size (ignoring ctime/inode/
# uid/gid/dev) and trustctime=false ignores ctime, removing the false positives
# while still detecting real edits. Per-clone local config, so re-applied on
# every container rebuild.
git -C /workspace config core.checkStat minimal 2>/dev/null || true
git -C /workspace config core.trustctime false 2>/dev/null || true

git config --global pull.rebase true 2>/dev/null || true

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "=============================================="
echo "DevContainer Setup Complete! (${PROJECT_NAME:-devcontainer})"
echo "=============================================="
echo ""
echo "Installed Runtimes:"
echo "  Node.js + NPM"
echo "  Python + UV"
[ "${INSTALL_JAVA:-false}" = "true" ] && echo "  Java + Maven"
if [ "${INSTALL_CLAUDE:-false}" = "true" ]; then
    echo "  Claude Code CLI"
    if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        echo "    → Authenticated via CLAUDE_CODE_OAUTH_TOKEN"
    else
        echo "    → To authenticate: run 'claude' and follow the OAuth flow."
        echo "      If the browser shows 'localhost refused to connect', the sign-in"
        echo "      page will instead display a login code — paste it at the CLI's"
        echo "      'Paste code here if prompted:' prompt. Credentials are saved to"
        echo "      ~/.claude/ and persist across rebuilds via the claude-config volume."
        echo ""
        echo "      If subscription tokens keep expiring, use 'claude setup-token'"
        echo "      (1-year token) and add it to ~/.bashrc:"
        echo "        echo 'export CLAUDE_CODE_OAUTH_TOKEN=<token>' >> ~/.bashrc"
        echo ""
        echo "      See 'Authenticating Claude Code' at the top of README.md for details."
    fi
fi
if [ "${CLAUDE_STATUSLINE:-false}" = "true" ] && [ "${INSTALL_CLAUDE:-false}" = "true" ]; then
    echo "  Status line (model · context usage · branch · worktree)"
    echo "    → Registered in user settings; edit or disable in ~/.claude/settings.json"
fi
if [ "${INSTALL_GRAPHIFY:-false}" = "true" ]; then
    echo "  graphify (knowledge graph — code + SQL + infra + docs)"
    echo "    → Re-index (code-only, local): graphify update /workspace"
fi
if [ "${INSTALL_HEADROOM:-false}" = "true" ]; then
    echo "  headroom (context compression — MCP server: compress/retrieve/stats)"
    echo "    → Reverse with: headroom mcp uninstall"
fi
if [ "${INSTALL_RTK:-false}" = "true" ]; then
    echo "  rtk (CLI-output compression — .claude/hooks/rtk-bash-rewrite.sh rewrites Bash → rtk)"
    echo "    → Commit .claude/hooks/rtk-bash-rewrite.sh   |   Reverse: bash .devcontainer/scripts/uninstall-stack.sh"
fi
echo ""
echo "Installed Language Servers:"
command -v typescript-language-server &> /dev/null && echo "  typescript-language-server"
command -v pyright &> /dev/null && echo "  pyright"
[ "${INSTALL_JAVA:-false}" = "true" ] && command -v jdtls &> /dev/null && echo "  jdtls (Eclipse JDT-LS)"
[ "${INSTALL_KOTLIN_LSP:-false}" = "true" ] && command -v kotlin-language-server &> /dev/null && echo "  kotlin-language-server"
if [ "${ENABLE_LSP_TOOL:-0}" = "1" ]; then
    echo "    → Claude Code native LSP tool ENABLED (semantic go-to-def / find-refs / diagnostics)."
    echo "      Prefer the LSP tool over grep for symbol navigation; trust its results."
fi
echo ""

# =============================================================================
# Setup check — keep it last, so the banner is the end of the creation log
# Every tool this config asked for must be on PATH now, whatever made an install
# above fail (each one only warns). The script still exits 0.
# =============================================================================
export PATH="$HOME/.local/bin:$PATH"
require_tool() {
    # $1 = command, $2 = what should have provided it
    if ! command -v "$1" &> /dev/null; then
        SETUP_PROBLEMS+=("$1 is missing ($2)")
    fi
}
require_tool uv "Dockerfile"
require_tool npm "Node.js feature"
require_tool pyright "post-create.sh: uv tool install pyright"
require_tool typescript-language-server "post-create.sh: npm install -g"
if [ "${INSTALL_JAVA:-false}" = "true" ]; then require_tool jdtls "Dockerfile"; fi
if [ "${INSTALL_KOTLIN_LSP:-false}" = "true" ]; then require_tool kotlin-language-server "Dockerfile"; fi
if [ "${INSTALL_CLAUDE:-false}" = "true" ]; then require_tool claude "post-create.sh: Claude Code installer"; fi
if [ "${INSTALL_GRAPHIFY:-false}" = "true" ]; then require_tool graphify "post-create.sh: uv tool install graphifyy"; fi
if [ "${INSTALL_HEADROOM:-false}" = "true" ]; then require_tool headroom "post-create.sh: uv tool install headroom-ai"; fi
if [ "${INSTALL_RTK:-false}" = "true" ]; then require_tool rtk "Dockerfile"; fi

if [ "${#SETUP_PROBLEMS[@]}" -gt 0 ]; then
    echo "============================================================================"
    echo ""
    echo "  SETUP INCOMPLETE: ${#SETUP_PROBLEMS[@]} problem(s)"
    echo ""
    for _problem in "${SETUP_PROBLEMS[@]}"; do
        echo "  - $_problem"
    done
    echo ""
    echo "  The WARNING lines above give each cause. Once it is fixed, re-run:"
    echo "      bash .devcontainer/scripts/post-create.sh"
    echo ""
    echo "============================================================================"
    echo ""
fi
