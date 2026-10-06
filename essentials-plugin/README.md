# essentials

Claude Code plugin for the **Trustworks Essentials** Java/Kotlin framework.

## What it does

- **`essentials-docs` skill** — auto-loads on Essentials-related questions and on code signals (imports of `dk.trustworks.essentials.*`, framework type names). Provides index-driven progressive disclosure over the LLM-tailored docs in `references/llm/`, with a `search.sh` keyword fallback. Proactively consults the design guide (`references/design/essentials-design.md`) and `rules/slice-design.md` before editing Essentials code; language is auto-detected from project files.
- **`essentials-change` skill** — the no-command path for change requests. Auto-loads when you describe a change in prose (*"let support cancel an order"*, *"the overdue list needs a status column"*) in a project that is already on the slice law, and stays silent everywhere else. It classifies the request into one of five classes — new capability, extend one slice, spans several, read-model shape change, or not a slice change at all — locates the owning slice from its `slice.yaml` rather than by searching source, applies `rules/slice-design.md`, updates the manifest as part of the change, and enters the matching per-kind scaffolding skill when the answer turns out to be a new slice. `references/slice/change-procedure.md` is the written procedure behind it; it exists because `/essentials:add-slice` deliberately refuses to touch an existing slice, which left the most common kind of work — changing one — with no covered path.
- **Stack contract** — `references/stack/` specifies what an Essentials *application* must provide for slice code to run at all, as numbered requirements **S1–S11**: baseline and pins, module selection per persistence profile, the serialization contract (the silent-failure area — the web and persistence mappers, constructor parameter names as a JSON contract), the typed edge, persistence and transactions, contract-first API generation, frontend mode, security posture, testing, and packaging. It is language-neutral; `kotlin-spring-boot.md` and `java-spring-boot.md` carry only the bindings that differ, `frontend-react.md` specifies the **embedded vs standalone** React/TypeScript modes, and `stack-pins.md` is the single place a version number may appear. Where the slice law governs how code inside a bounded context is arranged, this governs the application around it.
- **`/essentials:init` command** — `AskUserQuestion`-driven project scaffolder. The questions are the model's; the writing is a script's: `scripts/init-render.py` renders a **deterministic** project from the plugin's template tree `references/init-assets/project/`, with the stack contract applied and every version read from `stack-pins.md` — two runs with the same answers give the same bytes, the committed goldens in `tests/golden/init/` show them, and the Essentials repository's CI builds them against the framework. Produces a Spring Boot + **WebFlux or WebMvc** + Maven project in **Kotlin or Java** with an optional React frontend in either integration mode, three DB profiles (PG event-sourced, PG CRUD, MongoDB), optional Docker Compose, an optional slice-manifest lint gate, three wiring tests (a Testcontainers context test per DB profile, a CORS preflight test for a standalone frontend, and a frontend base-URL test), and a customised project `CLAUDE.md` — then lints it against S1–S11, builds it and starts its Spring context before handing it over.
- **Slice-design capability** — `rules/slice-design.md` is the standalone law for vertical slices: four kinds (command, view, automation, translation), the JVM directory vocabulary, and the R1–R5 anti-god-class rules — including R5's **three** sanctioned write styles, so a codebase has a legal shape in the law whichever it uses: per-slice deciders (the default), one aggregate per bounded context, or a **state-stored entity with no event store at all** — plus a lane-independent **Spring Data repository-surface rule**: repositories extend the bare `Repository` marker rather than `JpaRepository`/`MongoRepository`, read shapes are closed interface projections rather than the entity, and no query method is named after a CRUD base method (`findById` is captured by the base implementation, which silently discards the declared projection type). It depends on no other plugin. `/essentials:add-slice` (plus four per-kind commands) scaffolds a slice's source, `slice.yaml` manifest, per-slice `CLAUDE.md`, test, and Spring wiring from concrete Java **and** Kotlin templates, rendered by `scripts/render-slice.py` and picked from the bounded context's lane — **all three §R5 write styles in Java**, including the aggregate lane, and two in Kotlin; automation and translation slices are scaffolded on the two event-sourced lanes. `/essentials:slice-check` audits a project that already follows the law with lane-conditional gates, the mechanical ones run by `scripts/slice-lint.py` (manifests) and `scripts/slice-source.py` (syntactic facts of the Java and Kotlin sources); and `/essentials:slice-discover` is its brownfield counterpart — it infers bounded contexts and candidate slices from a codebase that does not, ranks findings by payoff, and hands off to `slice-check` once manifests exist.
- **`/essentials:slice-map` command** — the orientation view for a project already on the law, which neither of the other two provides: `slice-check` audits and `slice-discover` infers, but nothing answered *"what is here, where is X implemented, how does this connect?"*. Built by `scripts/slice-index.py` from every `slice.yaml`, with packages, files, payload keys and read-model columns added by `scripts/slice-source.py` — it never reads a method body for meaning — and derives five sections: contexts with their slices; a **message-flow graph** (commands in → slice → events out → the slices that react, plus the commands an automation dispatches and the external systems a translation slice bridges); the publisher → event → consumer flow indexed by event; write targets (multiple writers on one target called out first) with cross-context reads and their mandatory `via:` reader; and an endpoint → slice index. Prints by default; `--html <file>` renders a single self-contained page where the graph pans, zooms to the cursor, maximises to full screen, isolates a node's one-hop neighbourhood on click, filters by slice kind and message type with one chip each, and dims to the same query box that filters every other view. Hovering any node gives a card with its package, class list, endpoints, messages in and out, invariants and what enforces each, tests, and any divergence flag. `--view graph` prints the same graph as indented chains in the terminal. Stamps the git HEAD SHA, runs eight cheap divergence checks so the map is not fiction, and grades nothing — declared structure is not audited structure.
- **`/essentials:upgrade` command** — the other half of `init`, and the thing `init` never does: bring an **existing** project up to what the installed plugin ships. It is an audit that offers repairs, not a regeneration — it never diffs the project against the template tree `init` renders from (a project diverges from its render the day work starts), so every finding is derived from the project's own files plus the current stack contract, the decidable half by `scripts/stack-lint.py`. Three groups: the two files that leave the plugin (the slice-rules pointer, and the slice-manifest lint gate — **installed here** when the project predates it, which is the case `/essentials:slice-check` gate 12 structurally cannot see); the orientation files (the `CLAUDE.md` framework-knowledge block, the workspace pointer, the version stamp); and conformance against **S1–S11**, led by the S2.1 set — the requirements that compile cleanly and then kill context startup under a message naming nothing relevant. Reports everything before writing anything, offers each fix individually, and re-runs the context-start check when it changed a dependency or a config class. `--check` is a report-only dry run. It never regenerates a skeleton, never edits slice source, never re-asks the init questions, and **never moves a version pin** — a pin move is an upgrade decision that wants an ADR, not a side effect of catching up — except a Kotlin compiler too old for the project's own Java baseline, which does not compile. It reads the project's own Essentials version and keeps apart, as applying with the Essentials upgrade, every finding whose fix would break an application still on its older release.
- **`/essentials:review` command** — review a change — the current branch, a ref, a pull request by number, or a path — against the traps index (`LLM-traps.md`), the stack contract and the slice law. The deterministic half runs as scripts (`review-scan.py` for trap signatures in the added lines, `stack-lint.py`, `slice-lint.py`, `slice-source.py`); the model judges only what they cannot see. Every finding carries an id with a link to the section that owns it: `ESS-NNN` for a trap, `ESS-S<n>` for a stack requirement, `ESS-G<gate>` for a slice-law gate. Findings the change did not introduce are counted, not listed. Reports by default; `--fix` applies the mechanical fixes the scripts describe, one at a time, each confirmed.
- **`/essentials:doctor` command** — checks this machine for the tools the commands run (Requirements, below) and says what each missing one costs: a command that stops, a gate that is not run, a compile-only smoke build, a slower fallback. `scripts/doctor.sh` does the probing, for one command's profile (`init`, `review`, `slice`, `docs`) or all of them; it is bash, because the first thing it detects is a missing Python, and `init`, `review`, `slice-check` and `slice-map` run it as their preflight. Explains each gap with an install hint for the OS; installs nothing.
- **`/essentials:intro` command** — read-only orientation. Detects whether the current repository already uses Essentials, then prints the docs skill, `/essentials:init` and what it sets up, the framework's core principles, and what is deliberately absent. Runs no build and scaffolds nothing.

## Install

The plugin is published from the Essentials repository, which is also its marketplace:

```
/plugin marketplace add trustworksdk/essentials-project
/plugin install essentials@essentials-marketplace
```

To pin a branch or tag, add it to the marketplace source with `#<ref>`, for example
`/plugin marketplace add trustworksdk/essentials-project#<ref>`. The plugin's version is the
Essentials release it targets, with a `-N` suffix for plugin-only releases; you are offered an
update when it changes. Third-party marketplaces do not auto-update by
default — turn it on in the `/plugin` Marketplaces tab, or refresh with
`/plugin marketplace update essentials-marketplace`.

## Requirements

Run `/essentials:doctor` to check them on this machine: it lists each one with the version found and what a missing one costs.

- **Python 3.11 or newer** for every command that runs a script. The deterministic checks are scripts, and a command without them stops or marks its gates not run rather than guessing.
- **[`uv`](https://docs.astral.sh/uv/)** for the scripts that need `pyyaml` or `jsonschema` (`slice-lint.py`, `slice-source.py`, `slice-index.py`). Each pins its dependencies in PEP 723 inline metadata, and the commands run them with `uv run --script`, which installs exactly those versions. Without `uv`, `python3 <script>` works where those packages are already installed (or `pipx run <script>`, which reads the same metadata). `init-render.py`, `render-slice.py`, `slice-law.py`, `stack-lint.py`, `review-scan.py` and `check-citations.py` are standard library only.
- **For `/essentials:init`'s smoke build:** the JDK `references/stack/stack-pins.md` pins, Maven, and Docker (without Docker the result is compile-only and says so); `npm` to check a frontend.
- **git for `/essentials:review`'s base-ref and `--pr` modes.** Outside a git repository only `<path>` mode works.

## Usage

### Getting oriented

```
/essentials:intro
```

Prints what the plugin offers and what `/essentials:init` sets up. Read-only.

```
/essentials:doctor            # every requirement below, and what each missing one costs
/essentials:doctor slice      # only what add-slice, slice-check and slice-map run (also: init, review, docs)
```

Checks the tools the commands run (§ Requirements). Read-only; it suggests installs and never runs one.

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

### Reviewing a change

```
/essentials:review                    # the current branch vs the default branch, uncommitted work included
/essentials:review main               # vs the merge base with a ref
/essentials:review --pr 123           # a pull request, fetched with git; nothing is checked out
/essentials:review src/main/kotlin    # no diff: everything under a path
/essentials:review --fix              # after the report, offer each mechanical fix on its own
```

Run it before you open a pull request. It reviews **the change**: what the diff gets wrong about Essentials, not everything that was already wrong — `/essentials:slice-check` and `/essentials:upgrade` audit the whole project. Every finding links to the doc section or requirement that owns it. Nothing is written unless you pass `--fix`, and then only a fix you confirm.

### Scaffolding a new project

```
/essentials:init
```

The command interactively asks for:
- Target directory (current dir or new subdir)
- Frontend (embedded / standalone / none)
- Backend language (Kotlin or Java — both fully supported)
- Web stack (WebFlux, recommended / WebMvc)
- DB profile (PG event-sourced / PG CRUD / MongoDB)
- Docker Compose toggle
- Project metadata (name, groupId, artifactId)
- Slice-manifest lint gate (git pre-commit hook / script only / none)

Then `scripts/init-render.py` renders the project from those answers: the contract's S1–S11 applied (dependencies, compiler configuration, the config classes, the contract-first OpenAPI pipeline with a seed `contracts/openapi.json`, the frontend mode), the slice-rules pointer, the wiring tests, and a project `CLAUDE.md` with a strong directive pointing future Claude sessions back to this plugin's `essentials-docs` skill. A `CLAUDE.md` or `README.md` you already have is merged, overwritten or kept, as you choose (merge is the default). The Maven wrapper is generated with pinned versions.

**Then it lints the result with `scripts/stack-lint.py`, builds it, and starts the Spring context.** That last part is the point: the contract's most expensive requirements — above all the starters' non-transitive framework dependencies (S2.1) — compile cleanly and fail at context startup. A scaffold that has only been compiled has not been checked. After that, `mvn verify` regenerates `contracts/openapi.json`; commit it, because the frontend build reads the committed copy.

**No example bounded context is scaffolded.** The generated project gets an empty package and a pointer to `/essentials:add-slice`, which emits a slice matching the project's actual lane. The worked `orders` context at `tests/fixtures/worked-example/` is this plugin's own test fixture, not user-facing content. The generated `CLAUDE.md` carries **"Slicing & anti-god-class rules"** and **"When to write PBT"** sections.

**Run `/essentials:init` in a directory that is already an Essentials project and it stops**, names what it found, and points at `/essentials:upgrade` — the one case where continuing is offered is a monorepo deliberately gaining a second, separate service.

### Catching an existing project up with the plugin

```
/essentials:upgrade --check      # report only — writes nothing, asks nothing
/essentials:upgrade              # report, then offer each fix individually
```

`/essentials:init` runs once and nothing afterwards pulls plugin changes into the project: the two copied files go stale, capabilities added later (the slice-manifest lint gate) are never offered, and a project created before a contract requirement existed stays silently non-conformant. `/essentials:upgrade` is the only thing that closes that gap.

It reports in three groups — plugin copies, orientation files, and S1–S11 conformance (`scripts/stack-lint.py`'s findings plus the checks that need judgement) — with `slice-check`'s severity vocabulary (Blocking / Should-fix / Advisory), then offers each fix on its own with Apply / Skip / Show me first. When it changes a dependency, a compiler flag or a config class, it re-runs the context-start check, because that is precisely the class of edit that compiles and then fails at startup. It is **idempotent**: a second run right after a first reports nothing to do.

Out of scope by design: slice source (that is `/essentials:slice-check`), the skeleton, the init questions, and **version pins** — catching up with the plugin is not the same decision as moving onto a new Spring Boot or Essentials version, and the second one wants an ADR.

## Layout

```
essentials-plugin/
├── .claude-plugin/plugin.json
├── README.md
├── CHANGELOG.md
├── CLAUDE.md                    (maintainer instructions: what moves together, the invariants,
│                                 the pre-commit checks and the release checklist)
├── rules/
│   └── slice-design.md          (THE SLICE LAW — standalone, cited never restated; its sections
│                                 carry lane / kind / store scope lines for slice-law.py)
├── skills/
│   ├── essentials-docs/         (auto-loaded knowledge skill + search.sh)
│   ├── essentials-change/       (auto-loaded change router — prose in, slice change out)
│   ├── essentials-command-slice/
│   ├── essentials-view-slice/
│   ├── essentials-automation-slice/
│   └── essentials-translation-slice/
├── commands/                    (13: init, upgrade, intro, doctor, review, add-slice, 4 per-kind,
│                                 slice-check, slice-discover, slice-map)
├── scripts/                     (the deterministic half of the commands. Python 3.11+; the three
│   │                             needing pyyaml/jsonschema pin them in PEP 723 metadata for
│   │                             `uv run --script`. Exit 0 clean / 1 findings / 2 could not run;
│   │                             each --help says more)
│   ├── init-render.py           (/essentials:init's renderer: answers → project, byte-reproducible;
│   │                             goldens in tests/golden/init/)
│   ├── render-slice.py          (deterministic slice rendering + wiring for the kind skills;
│   │                             goldens in tests/slice-golden/)
│   ├── slice-lint.py            (manifest parse / schema / sole-ownership / tier lint — gates 1, 3,
│   │                             4 and 14 tier for slice-check; the copy /essentials:init and
│   │                             /essentials:upgrade install into a project as a CI / pre-commit gate)
│   ├── slice-source.py          (syntactic facts from Java/Kotlin sources; --check backs
│   │                             slice-check gates 6, 11(b), 14)
│   ├── slice-index.py           (slice-map's data, terminal graph, locate queries and HTML page)
│   ├── slice-law.py             (prints the slice law's sections for one lane, kind and project —
│   │                             how the slice skills load it; --check holds the byte budgets)
│   ├── stack-lint.py            (the decidable half of S1–S11: ESS-S findings with fix descriptors)
│   ├── review-scan.py           (trap signatures in a diff's added lines: ESS-NNN findings)
│   ├── doctor.sh                (bash, not Python: which required tools are here and what each
│   │                             missing one costs, per command profile; --json for scripts)
│   └── check-citations.py       (plugin lint: a pin or S-requirement restated outside
│                                 references/stack/, or a citation of a slice-law section
│                                 that rules/slice-design.md does not have)
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
│   │                             manifest-guide.md, manifest-reconciliation.md, api-provenance.md,
│   │                             discovery-heuristics.md, slice-yaml.schema.json,
│   │                             slice-map-template.html, and templates/{java,kotlin}/ — the
│   │                             slice templates across the decider, aggregate and
│   │                             service-entity lanes)
│   └── init-assets/project/     (the /essentials:init template tree + manifest.json, rendered by
│                                 scripts/init-render.py; versions come only from stack-pins.md)
├── tests/                       (the maintainers' expected results; nothing here reaches a project)
│   ├── fixtures/                (synthetic inputs, each with a TEST-GUIDE.md and a machine-readable
│   │                             expectation file — expected.yaml, or cases.yaml for change-router and
│   │                             expected.json for slice-map: worked-example — the Kotlin
│   │                             decider-lane orders context, the "already has manifests" case;
│   │                             service-entity; aggregate-lane; multi-lane — gate 14's
│   │                             multi-lane branch; brownfield-layered — the slice-discover
│   │                             input; slice-map — the page renderer's data and render-check.py;
│   │                             change-router — the change router's cases. Only service-entity,
│   │                             brownfield-layered and change-router/essentials-not-on-law carry
│   │                             a pom.xml, so the commands can detect the stack from it; the
│   │                             first and last pin no dependency version, and brownfield-layered's
│   │                             Spring Boot 3 parent is part of the legacy code it models)
│   ├── golden/init/             (the nine answer cells as rendered .tree goldens, and the six
│   │                             slice-compile hosts)
│   ├── init-overlay/            (CI-only wiring tests added to the nine cells)
│   ├── slice-golden/            (rendered slice compositions, byte-diffed by render-slice.py check)
│   ├── slice-compile/           (which composition or fixture builds on which host; the two-BC
│   │                             boot test)
│   ├── slice-source/, slice-index/, slice-law/, stack-lint/
│   │                            (each script's cases and goldens)
│   ├── review/                  (review-scan's signature diffs; judgement patches over two fixtures)
│   ├── citations/               (check-citations --self-test input)
│   └── scripts/                 (the unittest suites: slice-lint, render-slice, slice-source,
│                                 slice-index, slice-law, review-scan, search.sh, doctor.sh)
└── evals/                       (claude plugin eval suite for the model-judgement steps —
                                  maintainer, before release; see evals/README.md)
```

The scaffold builds that compile and start the rendered projects and slices against the framework
live in the repository: `scripts/plugin-scaffold.sh` at its root. Beside it, `scripts/plugin-check.sh`
runs every check above (the CI job calls it step by step) and says which ones a change needs.

## Deliberately absent

- **Kotlin aggregate lane** — `AggregateRoot` and `StatefulAggregateRepository` are a Java-native family, so Kotlin gets the other two §R5 write styles.

## Author

Jeppe Cramon · Trustworks · `jeppe.cramon@trustworks.dk`
