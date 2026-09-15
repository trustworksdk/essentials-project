#!/usr/bin/env bash
# =============================================================================
# DevContainer Post-Create Script
# Runs after the container is created to perform runtime setup
# =============================================================================
set -e

echo "Running post-create setup..."

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
            sudo chown -R vscode:vscode "$dir" 2>/dev/null || true
        fi
    fi
done

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
        # Heal root-owned entries inside ~/.cache/uv that the top-level
        # ownership loop misses (it stats only the top dir; subtrees from a
        # prior root-context `uv` run stay root-owned and break uv tool install
        # with a silent permission warning + visible "Permission denied" later).
        if [ -d "$HOME/.cache/uv" ] && find "$HOME/.cache/uv" -maxdepth 2 ! -user vscode -print -quit | grep -q .; then
            echo "  Healing root-owned entries in ~/.cache/uv..."
            sudo chown -R vscode:vscode "$HOME/.cache/uv" 2>/dev/null || true
        fi
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
            # Heal any root-owned uv cache entries (same guard as skillspector/pyright).
            if [ -d "$HOME/.cache/uv" ] && find "$HOME/.cache/uv" -maxdepth 2 ! -user vscode -print -quit | grep -q .; then
                sudo chown -R vscode:vscode "$HOME/.cache/uv" 2>/dev/null || true
            fi
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
            if [ -d "$HOME/.cache/uv" ] && find "$HOME/.cache/uv" -maxdepth 2 ! -user vscode -print -quit | grep -q .; then
                sudo chown -R vscode:vscode "$HOME/.cache/uv" 2>/dev/null || true
            fi
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
            sudo chown -R vscode:vscode "$HOME/.headroom" 2>/dev/null || true
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
# it rewrites Claude Code's Bash commands (e.g. `cargo test` -> `rtk cargo test`)
# via a PreToolUse hook, shrinking command output 60-90% before it hits context.
# The `rtk` binary is installed at build time (see Dockerfile); here we install
# its Claude Code hook with `rtk init -g`. This is the lean, proxy-free way to get
# rtk's job — independent of headroom (headroom's own rtk path needs the proxy).
# =============================================================================
if [ "${INSTALL_RTK:-false}" = "true" ]; then
    echo "Setting up rtk (CLI-output compression)..."
    export RTK_TELEMETRY_DISABLED=1
    if [ "${INSTALL_CLAUDE:-false}" = "true" ] && command -v claude &> /dev/null && command -v rtk &> /dev/null; then
        # Idempotency check: `rtk init --show` ALWAYS exits 0 — it's a status
        # display, not a test — so its exit code CANNOT gate the install (doing so
        # made every run take the "already installed" branch and never install the
        # hook). Parse its output instead: the operative signal is the settings.json
        # line reading "RTK hook configured" — that's the PreToolUse entry Claude
        # Code actually fires on. Run from $HOME so any global RTK.md lands outside the repo.
        if ( cd "$HOME" && rtk init --show 2>&1 ) | grep -q "settings.json: RTK hook configured"; then
            echo "  rtk Claude Code hook already installed"
        else
            # --auto-patch: patch settings.json WITHOUT the interactive prompt that
            # plain `rtk init -g` shows. post-create runs non-interactively, so the
            # prompt would otherwise be skipped and leave the hook unconfigured.
            echo "  Installing rtk PreToolUse hook (rtk init -g --auto-patch)..."
            ( cd "$HOME" && rtk init -g --auto-patch ) 2>&1 \
                && echo "    -> hook installed (Bash commands auto-rewritten to rtk)" \
                || echo "  WARNING: 'rtk init -g --auto-patch' failed — install the hook manually with: rtk init -g --auto-patch"
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

# Ensure /usr/local/bin/python (and python3) resolve to the active interpreter.
# The ghcr.io/devcontainers/features/python feature installs Python under
# /usr/local/python/current/bin and only adds that directory to PATH — it does
# NOT create /usr/local/bin/python. VS Code's Python extension (and the
# python.defaultInterpreterPath setting in devcontainer.json) expects an
# absolute path at /usr/local/bin/python, so we bridge it with a symlink.
PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [ -n "$PYTHON_BIN" ]; then
    if [ "$PYTHON_BIN" != "/usr/local/bin/python3" ] && [ ! -e "/usr/local/bin/python3" ]; then
        sudo ln -sf "$PYTHON_BIN" /usr/local/bin/python3
        echo "  Linked /usr/local/bin/python3 -> $PYTHON_BIN"
    fi
    if [ "$PYTHON_BIN" != "/usr/local/bin/python" ] && [ ! -e "/usr/local/bin/python" ]; then
        sudo ln -sf "$PYTHON_BIN" /usr/local/bin/python
        echo "  Linked /usr/local/bin/python -> $PYTHON_BIN"
    fi
fi

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
if [ "${INSTALL_GRAPHIFY:-false}" = "true" ]; then
    echo "  graphify (knowledge graph — code + SQL + infra + docs)"
    echo "    → Re-index (code-only, local): graphify update /workspace"
fi
if [ "${INSTALL_HEADROOM:-false}" = "true" ]; then
    echo "  headroom (context compression — MCP server: compress/retrieve/stats)"
    echo "    → Reverse with: headroom mcp uninstall"
fi
if [ "${INSTALL_RTK:-false}" = "true" ]; then
    echo "  rtk (CLI-output compression — PreToolUse hook rewrites Bash -> rtk)"
    echo "    -> Verify: rtk init --show   |   Reverse: rtk init -g --uninstall"
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
