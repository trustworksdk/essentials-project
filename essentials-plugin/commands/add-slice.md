---
name: add-slice
description: >
  Scaffold a vertical slice into an existing Trustworks Essentials project — command, view,
  automation, or translation, in Java or Kotlin. Asks (via AskUserQuestion) for the kind, bounded
  context, and names, then delegates to the matching slice skill, which emits the source files,
  the slice.yaml manifest, the per-slice CLAUDE.md, and the test, and wires the slice up.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# /essentials:add-slice

Add one vertical slice to an existing Essentials project. To create a *new* project, use
`/essentials:init` instead.

This command elicits; the kind skill emits, through `scripts/render-slice.py`
(`references/slice/slice-authoring.md` §4b). Do not write source files from here — one emission path,
not five.

## Step 0 — Confirm the project, then the rules pointer

1. `Glob` for `pom.xml`, `backend/pom.xml`, `build.gradle.kts`. No build file → stop: *"This does not
   look like a project root. Run `/essentials:init` to scaffold a new Essentials project."*
2. `Grep` the build file for `dk.trustworks.essentials`. Absent → stop with the same pointer. Never
   scaffold Essentials slices into a project that does not use Essentials.
3. Run the pointer-file check in `references/slice/slice-authoring.md` §8: compare the
   `<!-- essentials-slices-rules: vN -->` stamp in the project's `.claude/rules/essentials-slices.md`
   against `references/slice/project-rules-pointer.md.template`. Compare the integer after `v`
   numerically, never as strings (`v10` is newer than `v9`).
   - Missing → `AskUserQuestion`: **Write it** (recommended) / Skip this time.
   - Project stamp older → `AskUserQuestion`: **Refresh** / Show me the diff first / Keep mine.
     Never overwrite silently — the user may have edited it.
   - Project stamp newer → advisory line only; do not touch the file.

## Step 1 — Detect the language

Apply the detection table in `references/slice/slice-authoring.md` §1. That file is the single
source; do not restate the table here.

If the **target bounded context** contains both `.kt` and `.java` sources, stop and report it. A
project may be polyglot across modules; a bounded context may not.

## Step 2 — Kind

`AskUserQuestion`, one question, four options:

| Option | Choose this when |
|---|---|
| **Command** | A user or system wants to *do* something that changes state and must enforce rules |
| **View** | Someone needs to *see* something — a query answered from a read model |
| **Automation** | Something that happened should *cause* a follow-up, with no user involved |
| **Translation** | You are talking to a system you do not own, in either direction |

## Step 3 — Bounded context

`Glob <sourceRoot>/<packageDir>/*/` and offer every directory containing a `use_cases/` or `views/`
child, plus **New bounded context** — **for the command kind only**. A bounded context starts with its
first command slice: the view, automation and translation templates import `<bc>/events/<Event>`,
which only a command slice supplies (`slice-authoring.md` §3). For any other kind with no BC to offer,
say so and suggest `/essentials:add-command-slice`. For a new BC, also ask the aggregate name — it
drives the id type, the sealed event parent, the routing interface, and the `AggregateType`.

### Step 3b — Write-style lane

The §R5 write style decides which template family the skill emits, so it must be settled before
delegating. It is a **per-BC** property.

**For an existing BC, detect it — do not ask** (`references/slice/slice-authoring.md` §1b):
`aggregates/` → aggregate; `entities/` plus an Essentials command bus and no `EventStore`/
`AggregateType` → service-entity; otherwise decider. If the BC shows **two**, abort: that is Blocking
under §R5 and adding a slice would deepen it.

**Automation or translation on a service-entity BC: stop here.** No template exists for that lane —
both kinds are event-store subscribers, and the lane has no event store (its events travel on the
`EventBus`). The kind skill states the reason; do not ask for names first.

**For a new BC, ask.** One `AskUserQuestion`. Offer the **third option only when the project is
Java** — the aggregate lane's API is Java-native and this plugin does not scaffold it in Kotlin:

| Option | Choose this when |
|---|---|
| **Decider (default)** | Normal case. State is derived from an event stream, so you get history, replay, and projections. Adding a command adds a directory and edits nothing |
| **Service-entity** | This bounded context will **never need to reconstruct state from history** — no audit trail derived from events, no temporal queries, no replay. State lives in a row you mutate in place |
| **Aggregate** (Java only) | You want event sourcing, and the invariants of this context cluster on **one** entity that every command touches. The aggregate is the consistency boundary and holds the rules; each slice's handler loads it, calls one method, and is done. Prefer the decider lane when commands are largely independent of each other — this lane's whole cost is that every slice shares one class |

State the trade-off in the question, not just the labels: service-entity is simpler and its reads are
strongly consistent, but a BC that later needs history cannot recover what it never kept, and
migrating between lanes is a project with a data migration attached. Record the answer in the BC's
`CLAUDE.md` — the scaffold's "Why this lane" heading exists for exactly that, and is not optional.

## Step 4 — Names

`AskUserQuestion`, kind-dependent. Slice names are `snake_case`; type names are derived.

| Kind | Ask for |
|---|---|
| command | slice name (`place_order`), command type (`PlaceOrder`), event (`OrderPlaced`) |
| view | view name (`order_list`), the first event it projects |
| automation | automation name (`fulfillment`), the first event that drives it |
| translation | external system (`billing`), the external event, and the direction |

Also ask `owner`, defaulting to `<bc>-team`.

## Step 5 — Delegate

```
Read ${CLAUDE_PLUGIN_ROOT}/skills/essentials-<kind>-slice/SKILL.md
```

Follow it to completion, passing a literal `## Inputs` block with every resolved value: `language`,
`lane`, `tier`, `projectRoot`, `sourceRoot`, `testRoot`, `packagePath`, `bc`, `Bc`, `slice`, `Slice`,
`sliceCamel`, `aggregate`, `Aggregate`, `AggregateType`, `apiPath`, `owner`, plus the kind-specific
names. **`tier` is derived from `lane`, and is not the same value** — `decider` and `aggregate` are
both `cqrs-es`; only `service-entity` maps to itself (`slice-authoring.md` §4). Both go into the
manifest, because they answer different questions: `tier` is how the backend is organised,
`lane` is where the decision lives inside it. On the **service-entity** lane also pass `entity` / `Entity` — derived from
`aggregate`/`Aggregate` unless the BC's `entities/` already names one, in which case read it from
there.

The skill owns emission, wiring, and the closing report. This command writes no source file itself.

## Errors

- **Slice directory already exists** — abort, name the path, and suggest a different slice name.
  Never merge into an existing slice.
- **`render-slice.py` exits 2** — nothing was written. Relay its message. An unknown or unfilled
  placeholder, or a leftover `{{`/`__`, means the templates drifted from the placeholder table: a plugin
  bug, not a user error.
- **`render-slice.py requires` exits 1** — the build file lacks a module the slice imports. Name it
  and offer to add it; the slice does not compile without it.
- **Mixed-language bounded context** — abort and report both file types found.
