# essentials

Claude Code plugin for the **Trustworks Essentials** Java/Kotlin framework.

## What it does

- **`essentials-docs` skill** — auto-loads on Essentials-related questions and on code signals (imports of `dk.trustworks.essentials.*`, framework type names). Provides index-driven progressive disclosure over the LLM-tailored docs in `references/llm/`, with a `search.sh` keyword fallback. Proactively consults the design guide (`references/design/essentials-design.md`) and `rules/slice-design.md` before editing Essentials code; language is auto-detected from project files.
- **`essentials-change` skill** — the no-command path for change requests. Auto-loads when you describe a change in prose (*"let support cancel an order"*, *"the overdue list needs a status column"*) in a project that is already on the slice law, and stays silent everywhere else. It classifies the request into one of five classes — new capability, extend one slice, spans several, read-model shape change, or not a slice change at all — locates the owning slice from its `slice.yaml` rather than by searching source, applies `rules/slice-design.md`, updates the manifest as part of the change, and enters the matching per-kind scaffolding skill when the answer turns out to be a new slice. `references/slice/change-procedure.md` is the written procedure behind it; it exists because `/essentials:add-slice` deliberately refuses to touch an existing slice, which left the most common kind of work — changing one — with no covered path.
- **Stack contract** — `references/stack/` specifies what an Essentials *application* must provide for slice code to run at all, as numbered requirements **S1–S11**: baseline and pins, module selection per persistence profile, the serialization contract (the silent-failure area — the web and persistence mappers, constructor parameter names as a JSON contract), the typed edge, persistence and transactions, contract-first API generation, frontend mode, security posture, testing, and packaging. It is language-neutral; `kotlin-spring-boot.md` and `java-spring-boot.md` carry only the bindings that differ, `frontend-react.md` specifies the **embedded vs standalone** React/TypeScript modes, and `stack-pins.md` is the single place a version number may appear. Where the slice law governs how code inside a bounded context is arranged, this governs the application around it.
- **`/essentials:init` command** — `AskUserQuestion`-driven project scaffolder. It ships **no checked-in project tree**: the Spring Boot skeleton comes from the official Spring Initializr by default (which tracks the Boot parent, wrapper and release cadence so this plugin does not have to) or is generated locally with no network at all — your choice, asked up front — and the Essentials layer on top is derived from the stack contract, with every version read from `stack-pins.md`. Produces a Spring Boot + WebFlux + Maven project in **Kotlin or Java** with an optional React frontend in either integration mode, three DB profiles (PG event-sourced, PG CRUD, MongoDB), optional Docker Compose, and a customised project `CLAUDE.md` — then compiles it and starts its Spring context before handing it over.
- **Slice-design capability** — `rules/slice-design.md` is the standalone law for vertical slices: four kinds (command, view, automation, translation), the JVM directory vocabulary, and the R1–R5 anti-god-class rules — including R5's **three** sanctioned write styles, so a codebase has a legal shape in the law whichever it uses: per-slice deciders (the default), one aggregate per bounded context, or a **state-stored entity with no event store at all** — plus a lane-independent **Spring Data repository-surface rule**: repositories extend the bare `Repository` marker rather than `JpaRepository`/`MongoRepository`, read shapes are closed interface projections rather than the entity, and no query method is named after a CRUD base method (`findById` is captured by the base implementation, which silently discards the declared projection type). It depends on no other plugin. `/essentials:add-slice` (plus four per-kind commands) scaffolds a slice's source, `slice.yaml` manifest, per-slice `CLAUDE.md`, test, and Spring wiring from concrete Java **and** Kotlin templates, picking the template family from the bounded context's lane — **all three §R5 write styles in Java**, including the aggregate lane, and two in Kotlin; `/essentials:slice-check` audits a project that already follows the law with lane-conditional gates, and `/essentials:slice-discover` is its brownfield counterpart — it infers bounded contexts and candidate slices from a codebase that does not, ranks findings by payoff, and hands off to `slice-check` once manifests exist.
- **`/essentials:slice-map` command** — the orientation view for a project already on the law, which neither of the other two provides: `slice-check` audits and `slice-discover` infers, but nothing answered *"what is here, where is X implemented, how does this connect?"*. Reads every `slice.yaml` — never a method body — and derives five sections: contexts with their slices; a **message-flow graph** (commands in → slice → events out → the slices that react, plus the commands an automation dispatches and the external systems a translation slice bridges); the publisher → event → consumer flow indexed by event; write targets (multiple writers on one target called out first) with cross-context reads and their mandatory `via:` reader; and an endpoint → slice index. Prints by default; `--html <file>` renders a single self-contained page where the graph pans, zooms to the cursor, maximises to full screen, isolates a node's one-hop neighbourhood on click, filters by slice kind and message type with one chip each, and dims to the same query box that filters every other view. Hovering any node gives a card with its package, class list, endpoints, messages in and out, invariants and what enforces each, tests, and any divergence flag. Stamps the git HEAD SHA, runs six cheap divergence checks so the map is not fiction, and grades nothing — declared structure is not audited structure.
- **`/essentials:upgrade` command** — the other half of `init`, and the thing `init` never does: bring an **existing** project up to what the installed plugin ships. It is an audit that offers repairs, not a regeneration — there is no checked-in project tree to diff against, so every finding is derived from the project's own files plus the current stack contract. Three groups: the two files that leave the plugin (the slice-rules pointer, and the slice-manifest lint gate — **installed here** when the project predates it, which is the case `/essentials:slice-check` gate 12 structurally cannot see); the orientation files (the `CLAUDE.md` framework-knowledge block, the workspace pointer, the version stamp); and conformance against **S1–S11**, led by the S2.1 set — the requirements that compile cleanly and then kill context startup under a message naming nothing relevant. Reports everything before writing anything, offers each fix individually, and re-runs the context-start check when it changed a dependency or a config class. `--check` is a report-only dry run. It never regenerates a skeleton, never edits slice source, never re-asks the init questions, and **never moves a version pin** — a pin move is an upgrade decision that wants an ADR, not a side effect of catching up.
- **`/essentials:intro` command** — read-only orientation. Detects whether the current repository already uses Essentials, then prints the docs skill, `/essentials:init` and what it sets up, the framework's core principles, and what is still on the roadmap. Runs no build and scaffolds nothing.

## Install

The plugin is published from the Essentials repository, which is also its marketplace:

```
/plugin marketplace add trustworksdk/essentials-project
/plugin install essentials@essentials-marketplace
```

To pin a branch or tag, add it to the marketplace source with `#<ref>`, for example
`/plugin marketplace add trustworksdk/essentials-project#<ref>`. The plugin carries no version
number: every commit on that ref is a release. Third-party marketplaces do not auto-update by
default — turn it on in the `/plugin` Marketplaces tab, or refresh with
`/plugin marketplace update essentials-marketplace`.

## Usage

### Getting oriented

```
/essentials:intro
```

Prints what the plugin offers, what `/essentials:init` sets up, and what is still planned. Read-only.

### Looking up framework knowledge

The `essentials-docs` skill auto-triggers when you ask about Trustworks Essentials concepts (event store, durable queues, fenced locks, aggregates, etc.) or when Claude is reading/editing code that imports `dk.trustworks.essentials.*`. No command needed.

For keyword search across the docs:

```bash
${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/search.sh "FencedLock"
```

### Disabling auto-trigger via `skillOverrides` (CC 2.1.129+)

If the proactive advisory is too eager — for example when you're working in a non-Trustworks codebase that happens to share filenames or imports — you can keep the skill installed but stop it from auto-firing. Add this to `~/.claude/settings.json` or a project's `.claude/settings.json`:

```json
{
  "skillOverrides": {
    "essentials:essentials-docs": "user-invocable-only"
  }
}
```

The skill remains available via `/essentials:init` and direct invocation; it just stops auto-triggering on imports / framework keywords. Other valid values: `"on"` (default), `"name-only"` (model knows it exists but it doesn't auto-execute), `"off"` (hidden entirely).

The same mechanism mutes the change router — `"essentials:essentials-change": "off"` — if you would rather route every change through `/essentials:add-slice` explicitly.

### Changing something — no command needed

Just say what you want changed:

> *"Support needs to be able to cancel an order after it has shipped."*
> *"The overdue-invoice list should show the customer's payment terms."*

In a project that is on the slice law, `essentials-change` picks this up, says which of the five change classes it is and which slice it will touch, and waits for a yes before writing anything. The first example is a new command slice (§R1 — a second intent is a second slice, never a second method on an existing decider); the second is the same view slice extended (§ Evolving a view slice — in-place rebuild by default). Outside such a project the skill stays silent.

### Seeing what is there

```
/essentials:slice-map                      # print contexts, graph, flow, data, endpoints
/essentials:slice-map --bc orders          # one bounded context
/essentials:slice-map --view graph         # just the message chains
/essentials:slice-map --view endpoints     # just the endpoint → slice index
/essentials:slice-map --html target/map.html
```

Built from the `slice.yaml` manifests, so it is fast on a large project and complete on a small one. `--html` writes a self-contained page — no server, no CDN, works from `file://` — to a path **you** name; the command suggests `target/` (already git-ignored) for the throwaway case and asks before writing. Read-only always: it reports manifest drift and repairs none of it.

### Scaffolding a new project

```
/essentials:init
```

The command interactively asks for:
- Target directory (current dir or new subdir)
- Backend language (Kotlin or Java — both fully supported)
- Stack (embedded frontend / backend-only / standalone frontend)
- Skeleton source (Spring Initializr or local, offline generation)
- DB profile (PG event-sourced / PG CRUD / MongoDB)
- Docker Compose toggle
- Project metadata (name, groupId, artifactId)

Then it produces the Boot skeleton by the chosen route, applies the contract's S1–S11 (dependencies, compiler configuration, the config classes, the OpenAPI pipeline, the frontend mode), copies the slice-rules pointer, and writes a project `CLAUDE.md` with a strong directive pointing future Claude sessions back to this plugin's `essentials-docs` skill.

**Then it builds what it generated, and starts the Spring context.** That last part is the point: the contract's most expensive requirements — above all the starters' non-transitive framework dependencies (S2.1) — compile cleanly and fail at context startup. A scaffold that has only been compiled has not been checked.

**No example bounded context is scaffolded.** The generated project gets an empty package and a pointer to `/essentials:add-slice`, which emits a slice matching the project's actual lane. The worked `orders` context at `tests/fixtures/worked-example/` is this plugin's own test fixture, not user-facing content. The generated `CLAUDE.md` carries **"Slicing & anti-god-class rules"** and **"When to write PBT"** sections.

**Run `/essentials:init` in a directory that is already an Essentials project and it stops**, names what it found, and points at `/essentials:upgrade` — the one case where continuing is offered is a monorepo deliberately gaining a second, separate service.

### Catching an existing project up with the plugin

```
/essentials:upgrade --check      # report only — writes nothing, asks nothing
/essentials:upgrade              # report, then offer each fix individually
```

`/essentials:init` runs once and nothing afterwards pulls plugin changes into the project: the two copied files go stale, capabilities added later (the slice-manifest lint gate) are never offered, and a project created before a contract requirement existed stays silently non-conformant. `/essentials:upgrade` is the only thing that closes that gap.

It reports in three groups — plugin copies, orientation files, and S1–S11 conformance — with `slice-check`'s severity vocabulary (Blocking / Should-fix / Advisory), then offers each fix on its own with Apply / Skip / Show me first. When it changes a dependency, a compiler flag or a config class, it re-runs the context-start check, because that is precisely the class of edit that compiles and then fails at startup. It is **idempotent**: a second run right after a first reports nothing to do.

Out of scope by design: slice source (that is `/essentials:slice-check`), the skeleton, the init questions, and **version pins** — catching up with the plugin is not the same decision as moving onto a new Spring Boot or Essentials version, and the second one wants an ADR.

## Layout

```
essentials-plugin/
├── .claude-plugin/plugin.json
├── README.md
├── CHANGELOG.md
├── CLAUDE.md                    (maintainer instructions for this plugin)
├── rules/
│   └── slice-design.md          (THE SLICE LAW — standalone, cited never restated)
├── skills/
│   ├── essentials-docs/         (auto-loaded knowledge skill + search.sh)
│   ├── essentials-change/       (auto-loaded change router — prose in, slice change out)
│   ├── essentials-command-slice/
│   ├── essentials-view-slice/
│   ├── essentials-automation-slice/
│   └── essentials-translation-slice/
├── commands/                    (11: init, upgrade, intro, add-slice, 4 per-kind,
│                                 slice-check, slice-discover, slice-map)
├── scripts/                     (slice-lint.py — deterministic slice.yaml parse + schema +
│                                 sole-ownership gate; runs gates 1/3/4 for slice-check and is
│                                 installable into a project by /essentials:init or
│                                 /essentials:upgrade as a CI / pre-commit hook.
│                                 Exit 0 clean / 1 findings / 2 could not run)
├── references/
│   ├── llm/                     (31 docs — the framework docs, LLM.md plus 30 LLM-*.md incl.
│   │                             LLM-traps.md. Generated from the repository's LLM/ by
│   │                             scripts/sync-plugin-llm.sh, with links out of LLM/
│   │                             rewritten to GitHub URLs; never edited here)
│   ├── design/                  (essentials-design.md — the design guide: aggregate
│   │                             boundaries, event design, error handling, anti-patterns)
│   ├── stack/                   (the application stack contract S1-S11 + Kotlin/Java bindings,
│   │                             the embedded-vs-standalone frontend spec, and stack-pins.md)
│   ├── slice/                   (slice-model.md, slice-authoring.md, change-procedure.md,
│   │                             manifest-guide.md, api-provenance.md,
│   │                             discovery-heuristics.md, slice-yaml.schema.json,
│   │                             slice-map-template.html, and templates/{java,kotlin}/
│   │                             — 120 slice template files across the decider,
│   │                             aggregate and service-entity lanes)
│   └── init-assets/             (CLAUDE.md.template, README.md.template, dev.sh —
│                                 what /essentials:init renders; no version pins)
└── tests/fixtures/              (brownfield-layered — the slice-discover oracle;
                                  service-entity — the slice-check oracle;
                                  aggregate-lane — the aggregate-lane case for
                                  slice-discover and slice-check;
                                  slice-map — the HTML renderer oracle;
                                  worked-example — the Kotlin decider-lane orders
                                  bounded context, the "already has manifests" case
                                  for slice-map / slice-discover. No build files, no pins)
```

## Not yet shipped

- **Anti-pattern hook** — a post-write check. Not implemented; `/essentials:slice-check` is the opt-in, read-only stand-in.
- **Kotlin aggregate lane** — deliberately absent: `AggregateRoot` and `StatefulAggregateRepository` are a Java-native family, so Kotlin gets the other two §R5 write styles.

## Author

Jeppe Cramon · Trustworks · `jeppe.cramon@trustworks.dk`
