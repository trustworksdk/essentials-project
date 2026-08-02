#!/usr/bin/env bash
# =============================================================================
# Local-first agent stack — reverse / uninstall
# Removes the graphify + headroom + native-LSP integration that post-create.sh
# installed. Run manually from inside the container; NOT wired into any lifecycle
# hook (teardown is a deliberate, rarely-run action).
#
#   bash .devcontainer/scripts/uninstall-stack.sh           # de-register + unwrap
#   bash .devcontainer/scripts/uninstall-stack.sh --purge   # also drop graph cache
#
# Every step is best-effort (|| true / guarded) so a partial install still
# cleans up what's present. The graphify graph output (graphify-out/) is
# gitignored and rebuildable, so nothing committed is at stake. It does NOT
# remove the named volumes (those survive on purpose — `docker volume rm
# devcontainer-headroom-<id>` to discard them).
# =============================================================================
set -uo pipefail

PURGE="false"
[ "${1:-}" = "--purge" ] && PURGE="true"

export PATH="$HOME/.local/bin:$PATH"
WORKSPACE="/workspace"

echo "Uninstalling local-first agent stack..."

# --- headroom: de-register the MCP server, then uninstall the tool ------------
if command -v headroom &> /dev/null; then
    echo "  headroom: removing MCP server registration..."
    headroom mcp uninstall 2>&1 || echo "    NOTE: 'headroom mcp uninstall' failed or was not registered."
    uv tool uninstall headroom-ai 2>&1 || echo "    NOTE: could not uv-tool-uninstall headroom-ai."
fi

# --- rtk: remove the Claude Code PreToolUse hook ------------------------------
if command -v rtk &> /dev/null; then
    echo "  rtk: removing Claude Code hook (rtk init -g --uninstall)..."
    ( cd "$HOME" && rtk init -g --uninstall ) 2>&1 || echo "    NOTE: 'rtk init -g --uninstall' failed or hook was not installed."
fi

# --- graphify: uninstall skill + tool, strip the managed .gitignore block -----
if command -v graphify &> /dev/null; then
    echo "  graphify: uninstalling..."
    if [ "$PURGE" = "true" ]; then
        ( cd "$WORKSPACE" 2>/dev/null && graphify uninstall --purge 2>&1 ) || graphify uninstall --purge 2>&1 || echo "    NOTE: 'graphify uninstall --purge' failed."
    else
        ( cd "$WORKSPACE" 2>/dev/null && graphify uninstall 2>&1 ) || graphify uninstall 2>&1 || echo "    NOTE: 'graphify uninstall' failed."
    fi
    uv tool uninstall graphifyy 2>&1 || echo "    NOTE: could not uv-tool-uninstall graphifyy."
fi

# Remove the managed block from .gitignore (idempotent; leaves everything else).
if [ -f "$WORKSPACE/.gitignore" ] && grep -q '>>> devcontainer-stack (managed) >>>' "$WORKSPACE/.gitignore" 2>/dev/null; then
    echo "  Removing graphify managed block from .gitignore..."
    sed -i '/# >>> devcontainer-stack (managed) >>>/,/# <<< managed <<</d' "$WORKSPACE/.gitignore" \
        || echo "    NOTE: could not edit .gitignore."
fi

# --- LSP plugins: leave installed (cheap, broadly useful) ---------------------
echo "  NOTE: native-LSP plugins (typescript-lsp, pyright-lsp, …) are left installed."
echo "        Remove individually if desired: claude plugin uninstall <name>@claude-plugins-official"
echo "        And set ENABLE_LSP_TOOL=0 (or remove it) in devcontainer.json containerEnv."

echo "Uninstall complete. Named volumes (e.g. headroom store) are preserved —"
echo "drop them with: docker volume rm devcontainer-headroom-<devcontainerId>"
