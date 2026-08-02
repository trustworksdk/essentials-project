# AI Coding Tooling

This repository's **devcontainer** provisions an optional, local-first AI-assist
stack for contributors who use AI coding agents (e.g. Claude Code). **None of it
is required to build, test, or contribute.** `mvn verify` and everything in the
root `README.md` work identically whether or not any of these tools are present.

If you work **outside the devcontainer**, you can safely ignore this stack — the
sections below explain why nothing here breaks your setup, and how to opt in if
you want it.

## What the devcontainer installs

All of it is gated behind `INSTALL_*` flags in `.devcontainer/devcontainer.json`
and set up by `.devcontainer/scripts/post-create.sh` (reverse with
`.devcontainer/scripts/uninstall-stack.sh`):

| Tool | Purpose | Flag |
|------|---------|------|
| [graphify](https://github.com/safishamsi/graphify) | Whole-repo knowledge graph (code + SQL + docs) as an agent skill + CLI. Code indexing is 100% local — no model calls, nothing leaves the machine. | `INSTALL_GRAPHIFY` |
| [rtk](https://github.com/rtk-ai/rtk) | Compresses CLI command output (60–90%) before it reaches the agent's context. | `INSTALL_RTK` |
| [headroom](https://github.com/headroomlabs-ai/headroom) | On-demand context compression exposed as an MCP server. | `INSTALL_HEADROOM` |
| Native LSP plugins | Compiler-accurate go-to-def / find-refs / diagnostics for the agent (jdtls, kotlin, pyright, typescript). | `ENABLE_LSP_TOOL` |

## graphify and version control

graphify is the only tool in the stack that writes into **tracked** files, so it
deserves a note. Running `graphify install --project` (which the devcontainer does
on every start) touches:

**Committed (shared) — safe for everyone:**
- `.claude/CLAUDE.md` — a one-line pointer to the graphify skill. Just text; inert
  if graphify isn't installed.
- `CLAUDE.md` — a `## graphify` section telling the agent to prefer `graphify query`
  over raw grep. It is **guarded**: every instruction is conditioned on
  `graphify-out/graph.json` existing. That file is gitignored (see below), so for a
  fresh clone without graphify the guidance never triggers.
- `.claude/settings.json` — a `PreToolUse` hook that nudges the agent toward
  graphify before searching/reading.

**Gitignored (local, rebuildable) — never committed:**
- `graphify-out/` — the generated graph, report, and wiki. Fully rebuildable with
  `graphify update .` (local, no API calls).
- `.claude/skills/graphify/` — the self-installed skill files.
- Git hooks (`.git/hooks/post-commit`, `post-checkout`) and the `graph.json` merge
  driver — per-clone, never tracked. `.gitattributes` tags only the gitignored
  `graph.json`, so it is inert for anyone without graphify.

### The one thing we had to fix: the hook path

By default graphify writes the `PreToolUse` hook command with the **absolute path**
to its executable, e.g. `/home/vscode/.local/bin/graphify hook-guard search`. That
path only exists inside this devcontainer. Committed verbatim, it would make every
Bash/Grep/Read/Glob tool call fire a *failing* hook on any other machine.

So `post-create.sh` rewrites the committed hook into a **guarded, PATH-relative**
form:

```sh
command -v graphify >/dev/null 2>&1 && graphify hook-guard search || true
```

This runs where graphify is on `PATH` and **silently no-ops where it isn't** — so
the committed hook is safe for everyone. The rewrite is idempotent and re-applied
after each `graphify install` (graphify resets the command to the absolute path on
every run). If you edit `.claude/settings.json` and see the absolute path reappear,
that's graphify reinserting it; rerun `post-create.sh` (or the jq step in it) to
re-normalize.

> Note (Windows): the guard uses POSIX shell syntax. It works in the devcontainer
> and on macOS/Linux hosts. If you run the agent from a native Windows shell,
> either use graphify's own install or remove the hook from your local settings.

## Committed Claude settings are kept portable

`.claude/settings.json` is committed, so it only contains things that can work on
any machine: the official-marketplace LSP plugin enablement and the guarded
graphify hook above. **Machine-specific config stays in the gitignored
`.claude/settings.local.json`**, a devcontainer-only bind-mount under
`/opt/claude-seed/...` and any
`autoMode`/permission settings. `permissions.defaultMode:auto` is deliberately
**not** committed: Claude Code ignores it in project-scope settings, and the
devcontainer sets it in the user-scope `~/.claude/settings.json` instead.

If you're outside the devcontainer, you won't have the `/opt/claude-seed` path — and because that registration is gitignored, nothing points
your Claude Code at a marketplace that doesn't exist.

## Working outside the devcontainer

Nothing to do — the committed files are inert without graphify (guarded hook, graph
guidance conditioned on a gitignored graph). If your agent ever prints a
`graphify: command not found`, it can be ignored.

### Opting in manually

```sh
# 1. Install the CLI (SQL grammar included so .sql files are indexed too)
uv tool install "graphifyy[sql]"

# 2. Wire it into this repo (skill + CLAUDE.md + hook + git hooks)
graphify install --project

# 3. Build the local, model-free graph
graphify update .
```

After step 2, re-run the portable-hook normalization if you plan to commit — see
the jq block in `.devcontainer/scripts/post-create.sh` — so you don't commit your
own machine's absolute graphify path.

To remove everything the stack added: `bash .devcontainer/scripts/uninstall-stack.sh`
(add `--purge` to also drop `graphify-out/`).
