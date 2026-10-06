---
name: parallel-agents
description: Fanning work out to background or worktree agents in this repo — use when launching several Agent-tool agents (with or without isolation "worktree"), when integrating their branches onto the working branch, or when a worktree agent reports a version or a tree that looks stale (an old `<revision>`, no `essentials-plugin/`, Jackson 2 modules present). Carries the stale-base root cause, the mandatory integration safeguards, integrator git hygiene, why file presence never signals completion, the machine resources parallel Maven and Docker runs share, the context-cost rules that keep each agent short-lived (one unit per agent, a stop rule, excerpts instead of files, model fit), and the seam defects that live between two agents' outputs and that no agent's self-report can catch.
---

# Fanning work out to parallel agents

The root `CLAUDE.md` carries the directive in one line (wait for every agent to settle, verify each
worktree base, stage explicit paths). This file carries the *why* and the recipes: the failures
those rules came from, and what to do when you hit one.

**Pick the isolation first.** Two shapes work here:

- **Main tree, disjoint files** — every agent edits the one checkout, each owning a set of paths no
  other agent touches; only the integrator commits. Right for documentation, `LLM/` and
  `essentials-plugin/` work, where nothing builds into a shared directory. No base to go stale.
- **`isolation: "worktree"`** — each agent gets its own checkout and its own `target/`. Needed as
  soon as two agents run Maven at once (see § Shared machine resources), and when agents commit.
  Brings the stale-base problem below.

## Worktree agents: verify the base before integrating

### Root cause — worktrees branch from `origin/main` unless told otherwise

Claude Code's `worktree.baseRef` setting decides which ref `--worktree`, `EnterWorktree` and agent
isolation branch from. Its default, `"fresh"`, is `origin/<default-branch>` — here `origin/main`,
the last *released* line. Work in this repository happens on local feature branches that are often
unpushed or have no upstream at all, and they routinely run hundreds of commits ahead of
`origin/main` (a release-refinement branch measured 245 when this was written). A worktree cut from
`origin/main` is then not "a few commits stale" — it is the previous release: no Jackson 3 migration,
`essentials-plugin/` absent or old, a different `<revision>` in the root `pom.xml`. The agent does
its work faithfully against the wrong codebase.

Symptoms: the agent reports a root-pom `<revision>`, a `plugin.json` `version` or an
`essentials.version` pin that is not the working branch's; it cannot find a file the brief names;
its commit's parent is a commit on `main`.

**Fix at the source:** `.claude/settings.json` sets `"worktree": { "baseRef": "head" }`, so new
worktrees branch from the current local `HEAD` — the feature branch you are on, unpushed commits
included. It is tracked, so it applies to every contributor; override it per developer with
`"baseRef": "fresh"` in the gitignored `.claude/settings.local.json`. Two limits:

- `head` is a **commit**, not the working tree. Uncommitted edits are not in the worktree. Commit
  (or stash and tell the agents) before fanning out.
- A Claude Code too old to know the key, a `settings.json` that failed to load, or a worktree
  created before the setting landed still branches from `origin/main`. Hence the safeguards.

### Mandatory integration safeguards

1. **Verify each worktree branch's base before integrating.**
   `git -C /workspace merge-base --is-ancestor <integration-branch-HEAD-sha> <worktree-branch>`.
   Non-zero exit means the branch was cut from a stale base. Do **not** `git merge --ff-only` it and
   do **not** naive-merge it: that silently reverts every commit the worktree never saw. Instead
   **cherry-pick its own commits (or rebase them) onto the integration branch and resolve
   conflicts**, explicitly keeping the integration branch's side in every overlapping file. Then
   diff the result against the pre-integration `HEAD` to confirm nothing from the missing commits
   was dropped.
2. **Tell every worktree agent its expected base in the brief**: the integration branch's `HEAD`
   SHA, and the exact starting values of what it will touch — typically the root-pom `<revision>`,
   `essentials-plugin/.claude-plugin/plugin.json` `version`, and the `essentials.version` pin in
   `essentials-plugin/references/stack/stack-pins.md`. Instruct it to check `git log -1` and those
   values first, and to **halt and report** if they do not match rather than carry on. An agent
   given this can self-correct; one that is not given it produces a branch that needs conflict
   resolution across every file it touched.
3. **Do not push to make `origin/main` current.** Pushing is the maintainer's decision, never a
   side effect of an agent fan-out, and pushing a feature branch would not move `origin/main`
   anyway. The setting plus safeguards 1–2 are the fix.

### Integrator git hygiene

- **Operate on the main checkout explicitly with `git -C /workspace …`** and confirm
  `git -C /workspace rev-parse --show-toplevel` and `git -C /workspace symbolic-ref --short HEAD`
  before any `reset`, `merge`, `cherry-pick` or `commit`. A worktree agent can leave the shell's
  working directory inside its worktree, and a bare `git` then reports *that* worktree's `HEAD` and
  reflog — which looks exactly like commits on the working branch having been lost. Cross-check with
  `git worktree list` (each worktree's real `HEAD`) before concluding anything was lost.
- **Never `git add -A` or `git add .` during integration.** `/.claude/worktrees/` is gitignored
  here, so the worktree gitlinks are not the risk; the risk is the untracked maintainer notes that
  sit in the repository root and in locally excluded directories, and an agent's scratch file left
  inside the tree. Stage explicit paths only; after resolving a cherry-pick, `git add <files>` then
  `git cherry-pick --continue`.
- **Let the pre-commit hook own `references/llm/`.** Stage the `LLM/` change; the hook regenerates
  `essentials-plugin/references/llm/` from the index and refuses a commit with unstaged edits in the
  copy. An agent that hand-edited the copy, or ran `sync-plugin-llm.sh` mid-flight, leaves exactly
  that state behind — rerun the sync on the settled tree, not per agent.
- **Anchor critical commits before any history surgery**: `git branch safety-<name> <sha>` for the
  pre-integration tip(s), so nothing can be lost while you investigate; delete the safety branches
  once the integration branch is verified.
- **Remove finished worktrees before the next fan-out** — only after every agent is settled;
  the rule and its reason are consequence 5 below.

## Background agents: file presence is NOT a completion signal

This applies to **any** fan-out of background agents via the `Agent` tool, with or without
`isolation: "worktree"`.

**The failure:** when N agents write disjoint paths and you poll the filesystem to track progress,
every assigned file appears on disk *while its author is still editing it*. Concluding "all files
exist, so all agents are done" and committing on that basis captures a **mid-write snapshot**. It has
happened: a nine-agent plugin build was committed when seven had reported; the two still running had
already created every file they owned and went on to revise them substantially (one command file
changed by 138 lines after the commit). Nothing was lost, but the commit was not the work the agents
produced.

**The rule: wait for the completion of every agent you launched — count them.** Not when its files
exist, not when they look complete, not when they stopped changing between two polls. Completion
shows up as one of two signals — its `<task-notification>`, or `ListAgents` reporting it
`completed` — so act on whichever comes first. A notification that arrives while the integrator is
idle does **not** reliably wake it: in the project this skill was adapted from, one of six agents'
notifications reached the session only attached to the user's next message, and the run sat idle
until the user asked. So when either signal arrives, settle the question **in the same turn**:

1. `ListAgents` — the agent says `completed`, not `running`;
2. no process of its own is alive — `ps -eo pid,etimes,args | grep -E '[m]vn|[p]lugin-(check|scaffold)'`,
   and `readlink /proc/<pid>/cwd` for any you cannot place (a worktree agent's build runs in its
   worktree path);
3. you have its hand-back report.

All three → it is settled; count it and carry on (dispatch the next agent, update the brief). Only
when one fails do you end the turn, and then say which agent you are waiting on and why — a stall
the user can see is better than one they discover.

**Consequences for the integrator:**

1. **Track launches against completions explicitly.** Nine launched means nine settled agents
   before you stage anything. If you cannot account for one, it is still running — check it with
   the three steps above rather than waiting for a notification that may not wake you.
2. **Do not edit a file whose author is still live.** Normalisation passes (frontmatter, formatting,
   a companion-table update) applied to an in-flight file are either clobbered by the agent's next
   write or silently race it. Agents in the run above independently reported "something rewrote my
   frontmatter between my edits" — that was the integrator. Do every integrator-side edit after the
   last agent is settled.
3. **Generated artefacts and verification run on the settled tree, not the polled one.** In this
   repository that means, after the last agent is settled and never per agent:
   `sh scripts/sync-plugin-llm.sh`, `init-render.py --update-golden`, `render-slice.py update-golden`,
   `evals/build.py`, `graphify update .`, and the `essentials-plugin/CLAUDE.md` § Before committing
   block. Each of them reads the whole tree; run mid-flight they bake in a half-written file and look
   green.
4. **If you committed early anyway, `--amend` rather than stacking a fixup** — but only once every
   agent has reported, so the amend is against a settled tree, and only after re-running the full
   verification pass.
5. **Then remove every finished worktree before the next fan-out** — once its agent is settled
   and its branch integrated (`git diff --stat <integration-branch> <branch>` shows nothing of
   the agent's left unintegrated): `git worktree remove <path>`, or `git worktree prune` for one whose
   directory is already gone; confirm with `git worktree list`. A leftover worktree holds a full copy
   of every `CLAUDE.md` in the tree — the root and `essentials-plugin/` ones alone are tens of KB, and
   some module ones are larger — and those copies are injected as nested memory into any later agent
   that reads a file inside it, on top of the live versions already in context. Never remove one
   whose agent is still live.

**What polling the filesystem is good for:** telling the user which agents have produced anything
yet, and doing *non-overlapping* integrator work on files no agent owns. It is never evidence of
completion.

## Shared machine resources: worktrees isolate files, not the machine

A worktree gives an agent its own sources and its own `target/`. It does not give it its own Maven
repository, Docker daemon or temp directory, and each of those has bitten parallel runs.

- **Never two Maven builds in one checkout.** The root `CLAUDE.md`'s *`target/` has more than one
  writer* gotcha is the single-checkout form of this: a second `mvn` `clean`s and repopulates the
  first one's `target/classes`, and the symptoms (`Unresolved compilation problem`, a vanished
  package) point at the source, not at the race. Main-tree agents do not build; build agents get
  worktrees.
- **`~/.m2` is shared by every worktree.** Every module carries the same `<revision>`, so an
  `mvn install` from one worktree overwrites the artifacts another worktree's build resolves.
  Agents build with `mvn verify -pl <module> -am` (siblings resolve from the reactor, not `~/.m2`)
  and never `install`; only the integrator installs, on the settled tree.
- **Docker is one daemon.** Each `mvn verify` forks `failsafe.forkCount` JVMs (default 2), each
  starting its own Testcontainers. Three IT agents at the default is six concurrent suites of
  containers, which is slower than serial, not faster. Pass `-Dfailsafe.forkCount=1` in every
  agent's build command, or run the integration tests from the integrator once.
- **`scripts/plugin-scaffold.sh` wipes its work directory per case**, and the default is one shared
  path under `${TMPDIR:-/tmp}`. Give each agent its own `--work <scratchpad>/scaffold-<unit>`
  (outside the repository; the script refuses a path inside it).

## Context cost: an agent's bill grows with the square of its life

**What it costs.** Every turn re-sends the agent's whole context, so an agent's input is the sum of
its context over all its turns — and since the context grows on most turns, that sum grows roughly
with the *square* of the number of turns. One measured workspace (eight days of transcripts, usage
fields of the API) found general-purpose subagents were about 70 % of all input tokens, and the
fourteen longest agents alone about 30 % — each 125–320 turns, peaking at 400–650 K tokens.
Everything those agents first read as text was a rounding error by comparison: reading is cheap,
keeping it resident is not. A 28 KB file read at turn 20 of a 300-turn agent costs ~2 M tokens, not
8 K.

The long agents had two shapes, and both are brief decisions, not bad luck:

- **The kept-alive agent** — one integrator handed batch after batch through `SendMessage`
  (12 messages, 320 turns, peak 604 K).
- **The bundled brief** — "fix three independent findings", "fix every seam the eight agents left"
  in one agent (220–290 turns, peaks 560–650 K).

### The rules

1. **One unit of work per agent; a fresh agent for the next.** A unit is one finding, one seam, one
   module, one plugin release step. Three agents of 100 turns cost about half of one agent of 300.
   `SendMessage` a finished agent only to finish *its own* unit — never to hand it the next one. What
   the next agent must know travels in a shared brief file outside the tracked tree (the session
   scratchpad, or a locally excluded working directory for longer plan work) with a dated
   *state of the wave* section, which is cheaper to read once than to keep resident.
2. **Every brief carries a stop rule.** "If the unit turns out bigger than one commit, or you find
   yourself re-reading files you already read, stop: write what is done and what is left to
   `<scratch>/handoff-<unit>.md` and report." The integrator then dispatches a fresh agent on the
   handoff. A report that says "half done, here is the rest" is a success, not a failure.
3. **Excerpts in the brief, not files per agent.** When every agent needs the same section of a
   shared document — a stack-contract requirement, a `rules/slice-design.md` section, a row of the
   companion table in `essentials-plugin/CLAUDE.md`, an `LLM/LLM-traps.md` entry — the integrator
   copies that section into the shared brief once. Ten agents each opening the same 30 KB document
   is the failure.
4. **Never tell an agent to open a `CLAUDE.md`.** A directory's `CLAUDE.md` arrives as nested memory
   when the `Read` tool opens a file below it. Briefs say "Read a file under `essentials-plugin/` —
   its `CLAUDE.md` then loads on its own", never "read `essentials-plugin/CLAUDE.md` first": an
   explicit open is a second resident copy on top of the one already in memory. Two corollaries: `cat`
   through Bash does not trigger nested memory, so an agent working under a directory must `Read` at
   least one file there; and nested files are not re-injected after a `/compact`, which is the one
   time an explicit read is right.
5. **Command output is summarised before it lands.** Whole-suite runs end in `| tail -40`; a failing
   suite is re-run for the one failing class (`-Dtest=…` / `-Dit.test=…`). Searches are `rg -l` (or a
   1–3-token `graphify query`, per the root `CLAUDE.md`) first, then only the lines that matter.
6. **The model fits the job.** Agents that write code, tests, `LLM/` docs, plugin prose or release
   notes keep the session model. Lookups, running a fixture across N implementations, regenerating an
   index or checking a seam go to `model: "sonnet"` on the `Agent` call, or to the `Explore` agent
   for read-only searches. This does not shrink the tokens; it shrinks what each one costs.
7. **The orchestrator obeys the same arithmetic.** Main sessions have run 600–900 turns at
   160–210 K per turn. At a wave boundary whose context has passed ~250 K, write a continue prompt
   into the shared brief — what is integrated, what is in flight, launched vs reported counts, the
   next units — end the session, and resume in a fresh one from that prompt.

## The seam: every agent is correct and the work is still broken

The failure modes above are about *an agent's own output*. This one is about the space **between**
two agents' outputs, which no agent owns and therefore no agent verifies.

**The failure.** Several components each needed a helper that appends a fixed set of entries to a
consumer's configuration file. Each helper carries its own copy of those entries, which must stay
set-equal to the template the same component ships. Agent A was editing one component's template,
adding three entries. Agent B was writing the helper that mirrors it.

Agent B did the careful thing: it derived its table from `git show HEAD:<template>` rather than
reading a file another agent had open mid-flight — exactly the previous section's rule. That also
pinned B to the version *before* A's additions, so the shipped helper wrote 60 of 63 entries. Every
agent's self-report said PASS, and every one of them was telling the truth about what it could see.

**What caught it:** one adversarial fixture, built by the integrator, run against *every*
implementation. All but one passed; the one that failed named the three missing entries. Reading the
reports would never have found it; neither would reading the helpers, because the defect was a set
difference against a file none of them contained.

### Seams in this repository

`essentials-plugin/CLAUDE.md`'s *Companion documents* table is a catalogue of seams: every row is a
pair of places that must move together. When a fan-out cuts across a row, the seam is yours. The
ones that most often split across agents:

- `LLM/` ↔ `essentials-plugin/references/llm/` (the sync and its drift gate)
- an admin operation's three places: the `*Api` SPI, the `EssentialsAdminApiSpec` mapping table and
  the `spring-boot-starter-admin-api` controller
- a slice template ↔ `references/slice/api-provenance.md` ↔ the slice goldens
- an S-requirement in `stack-contract.md` ↔ `stack-lint.py` `RULES_LIST` ↔
  `tests/stack-lint/expectations.json`
- a manifest field ↔ every reader of it (`slice-index.py`, `slice-source.py`, `slice-map.md`, the
  map template) — including the `consumes` ∪ `projections[].from` union
- a command or skill ↔ `commands/intro.md`, `README.md`'s counts and Layout block, the `plugin.json`
  description
- a serializer change ↔ the wire-format golden documents, which are never regenerated

**Integrator-owned, never assigned to an agent:** the `plugin.json` `version` (one bump per batch —
N agents each bumping it is N conflicting releases), `commands/intro.md`, `README.md`'s counts,
`CHANGELOG.md`, and every golden or generated file. Agents report what those files need; the
integrator writes them once, after the last agent is settled.

### The rules

1. **Name the seams before you fan out.** A seam is any place where two agents' outputs must agree:
   a table mirroring a template, a reader mirroring a writer, a schema two writers both emit, a
   contract one agent produces and another consumes. Write the list into the shared brief. Each seam
   is an integration check you owe, not an agent's homework.
2. **Verify a seam by execution against one shared fixture — never by reading the reports.** Build
   the nastiest input you can and run *every* implementation against it. N agents reporting PASS on
   N private fixtures is not evidence that they agree with each other.
3. **`git show HEAD:<path>` is the right way to avoid a live file and the wrong source of truth.**
   When a brief tells an agent to derive from a file another agent is editing, the derivation is
   **provisional**: the integrator re-derives it on the settled tree, after the last agent is settled.
   Better, order the fan-out so the deriving agent starts after the file's owner has reported.
4. **Assert on the parsed result, not on the log line.** A helper that prints its own success is a
   witness with an interest in the outcome. Here the canonical case is `slice.yaml`: an unquoted
   path compiles, tests green, and makes the slice vanish from every tool that parses it — check it
   with `slice-lint.py`, not by looking.
5. **Turn the check into a gate, not a habit.** A seam verified once by hand regresses the next time
   someone adds a row. Most seams above already have one — the drift gate, `stack-lint.py
   --self-test`, `init-render.py --check`, `render-slice.py check`, `evals/build.py --check`,
   `check-expected.py`, the wire-format tests — so the integration check is running them on the
   settled tree. A seam you had to check by hand that no gate covers is the thing to raise with the
   maintainer as a new gate.

**Why briefs cannot fix this.** No instruction to agent A or agent B would have prevented it. A was
right to add the entries; B was right not to read a live file; both verified their own work
correctly. Seam defects are a property of the decomposition, so they are the integrator's to find —
budget for them explicitly rather than treating a clean set of agent reports as a green build.
