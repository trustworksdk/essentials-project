---
name: intro
description: Explain the essentials workflow — the docs skill, what /essentials:init sets up, and how to get started
user-invocable: true
allowed-tools: [Read, Grep, Glob]
---

# /essentials:intro

Explain what this plugin provides and how to use it. Read-only — modifies nothing and scaffolds
nothing.

## Detection (cheap, Glob + Grep only)

1. Glob for `pom.xml` and `backend/pom.xml` from the repository root.
2. If any was found, `Grep` it for `dk.trustworks.essentials` to decide whether this already *is*
   an Essentials project, and for `kotlin-maven-plugin` / `kotlin-stdlib` to name the language.

That is the whole check. The plugin has no state directory and no config file — nothing is
"initialised" except a scaffolded project.

**Do not** run Maven, resolve dependencies, walk source trees, or open anything under
`references/llm/` — the `essentials-docs` skill loads those when a real question arrives, and
duplicating it here makes an overview slow. If detection is ambiguous, print the "no Essentials
project" variant rather than guessing.

## Output

### If no `dk.trustworks.essentials` dependency was found:

```
ESSENTIALS — Trustworks Essentials Java/Kotlin framework support
No Essentials dependency found here · nothing to initialise

This repository does not use Trustworks Essentials yet. Run /essentials:init to
scaffold a new project, or just ask an Essentials question — the essentials-docs
skill answers from the bundled docs without needing a project.
```

Then print the `━━━ SKILLS THAT FIRE ON THEIR OWN ━━━` and `━━━ CREATING AND UPDATING A PROJECT ━━━`
blocks from the main variant and stop — but drop the `/essentials:upgrade` entry from the second one,
along with the whole `━━━ SLICES ━━━` and `━━━ REVIEWING A CHANGE ━━━` blocks: all of those need an
existing Essentials project. In the skills block, print the `essentials-docs` entry only: `essentials-change` gates itself
on a project that is already on the slice law, so naming it here would advertise something that cannot
fire.

### If an Essentials project was detected:

```
ESSENTIALS — Trustworks Essentials Java/Kotlin framework support
Essentials project detected · [Kotlin | Java] · 31 bundled docs

Most of this plugin is not a command. Two skills load themselves — one when you
ask about Essentials or Claude is about to touch dk.trustworks.essentials.* code,
the other when you describe a change to a project already on the slice law. The
commands cover what is worth being deliberate about: creating a project, adding
or auditing a slice, seeing the whole map, and reviewing a change.

━━━ SKILLS THAT FIRE ON THEIR OWN (you do not invoke these) ━━━

  essentials-docs        Routes a question to the right doc via a module index,
                         with a keyword-search fallback. Application-shaped
                         questions — deps, config, build, Java vs Kotlin, "which
                         version?", attaching a frontend — route to the stack
                         contract (S1-S11) instead. Before Claude edits
                         Essentials code it opens the design guide plus the
                         traps index for the modules touched, then re-checks
                         the written code against the anti-pattern table.
  essentials-change      Fires when you ask for a change in prose instead of a
                         command — "let support cancel an order", "the list needs
                         a status column". Classifies the request, finds the
                         owning slice from its manifest, applies the law, and
                         enters the scaffolder when the answer is a new slice.
                         Silent outside a project that is on the law.

━━━ SLICES ━━━

  /essentials:add-slice  Scaffold one vertical slice into an existing project.
                         Asks for kind, bounded context, and names, then emits
                         the source files, slice.yaml, the per-slice CLAUDE.md,
                         the test, and the wiring. Java or Kotlin.
  /essentials:add-command-slice      A use case: enforces invariants, emits events
  /essentials:add-view-slice         A read model: projects events, answers queries
  /essentials:add-automation-slice   A policy: reacts to events, issues commands
  /essentials:add-translation-slice  An ACL to a system you do not own
  /essentials:slice-check
                         Audit slices against the law — layout, manifests,
                         god-class rules, wiring, handler shapes. The mechanical
                         gates run as scripts: slice-lint.py over the manifests,
                         slice-source.py over the sources' syntax. Read-only;
                         --fix-manifests, --adopt-tier and --fix-source are opt-in.
  /essentials:slice-discover
                         The brownfield counterpart. For a codebase that does
                         NOT follow the law: infers bounded contexts and
                         candidate slices, ranks findings by payoff, and gives
                         a migration ladder that hands off to slice-check once
                         manifests exist. Always read-only — no --fix, ever.
  /essentials:slice-map  What is here and how it connects — contexts and their
                         slices, the command → slice → event → reactor graph,
                         event flow, who writes what, and endpoint → slice.
                         Built by scripts from the manifests and the sources'
                         syntax, so it reads no method body for meaning. Prints
                         by default; --view graph draws the chains in the
                         terminal; --html writes a single self-contained page
                         whose graph pans, zooms, maximises, filters by kind and
                         explains each node on hover. Audits nothing.

━━━ REVIEWING A CHANGE ━━━

  /essentials:review     Review a change — the branch, a ref, a pull request or a
                         path — against the traps index, the stack contract
                         (S1-S11) and the slice law. Scripts find what is
                         mechanical; Claude judges the rest. Every finding has an
                         ESS id and a link to the section that owns it; what the
                         change did not introduce is counted, not listed. Reports
                         only; --fix offers each mechanical fix on its own.

━━━ CREATING AND UPDATING A PROJECT ━━━

  /essentials:init       Interactive scaffolder. Asks for target directory,
                         frontend mode, language, web stack, DB profile, Docker
                         Compose, Maven coordinates and the slice-manifest lint
                         gate — then renders the project from the plugin's
                         template tree (scripts/init-render.py, every version
                         from stack-pins.md), lints it and smoke-builds it.
                         Refuses to overwrite a non-empty target, and redirects
                         to /essentials:upgrade when the directory is already an
                         Essentials project.
  /essentials:upgrade    The other half: what init never does. Brings an EXISTING
                         project up to the installed plugin — refreshes the slice
                         rules pointer, installs or refreshes the slice-manifest
                         lint gate, checks the project CLAUDE.md still routes to
                         essentials-docs, and audits the application against the
                         current stack contract (S1-S11), the silent-startup-
                         failure set first. Reports before it writes and offers
                         each fix on its own, apart from what only applies once
                         the project moves to the plugin's Essentials release.
                         Never regenerates a skeleton, never edits slice source,
                         moves no version pin but a Kotlin compiler too old for
                         the project's Java baseline.
                         --check is a report-only dry run.
  /essentials:doctor     Checks this machine for the tools the commands run —
                         python3, uv, the pinned JDK, Maven, Docker, npm — and
                         says what each missing one costs: a command that stops,
                         a gate not run, a compile-only build. Installs nothing.

━━━ WHAT /essentials:init SETS UP ━━━

  Backend                Spring Boot 4 + WebFlux (recommended) or WebMvc + Maven,
                         Kotlin or Java — every combination rendered by one
                         script and built in the Essentials repository's CI
  Frontend (optional)    React 19 + TypeScript + Vite + Tailwind, typed client
                         generated SpringDoc → openapi.json → Orval
  DB profile             PostgreSQL event-sourced (default) · PostgreSQL CRUD ·
                         MongoDB — each wires a different Essentials starter
  Frontend mode          Embedded (one fat JAR, no CORS) or standalone (two
                         deployables, real CORS, a consumed API base URL).
                         A real choice — see references/stack/frontend-react.md.
  First slice            None is scaffolded. Run /essentials:add-slice, which
                         emits one matching the project's own lane.
  Docker Compose         Optional; backend auto-starts the DB on run
  Wiring tests           A Testcontainers context test for the DB profile, a
                         contract test that regenerates contracts/openapi.json,
                         a CORS preflight test (standalone frontend) and a
                         frontend base-URL test
  Lint gate              Optional: the slice-manifest lint as a git pre-commit
                         hook or a script for your CI
  Smoke build            The generated project is linted against S1-S11, then
                         compiled AND its Spring context started before you are
                         handed it. The stack contract's costliest failures
                         compile clean and die at startup.
  Project CLAUDE.md      Points future sessions back at essentials-docs, and
                         carries an <!-- essentials-init: essentials <version> -->
                         stamp — the Essentials version the plugin targets —
                         that /essentials:upgrade reads. In subdirectory mode a
                         workspace-level pointer CLAUDE.md is also written, so
                         planners and other tooling anchor at the project root
                         instead of the workspace root.

━━━ CORE PRINCIPLES ━━━

  Docs, not recall       Essentials diverges from ordinary Spring/JPA idioms.
                         Answer from the bundled docs; never web-search or
                         invent a type signature.
  Advise before writing  The design guide is consulted before the edit,
                         not after the review.
  Intra-service only     Queues, locks, inbox/outbox coordinate instances of one
                         service over one DB — never cross-service messaging.
  Names are an injection surface
                         Table/column/queue/collection names are concatenated
                         into SQL. Hardcode them or validate against an allow-list.
  Slices, not god classes
                         Four kinds — command, view, automation, translation.
                         One decision component per command slice, one API file
                         per slice (a view may serve several queries over its
                         own read model), one variant per event file, shared
                         State/Evolver only per bounded context. The law is
                         rules/slice-design.md; projects get a short pointer at
                         .claude/rules/essentials-slices.md.
  Three write styles, one per bounded context
                         Per-slice deciders (the default), one aggregate in
                         <bc>/aggregates/, or a state-stored entity in
                         <bc>/entities/. The first two derive state from an
                         event stream; the third has no event store at all —
                         the row is the state, and events are published on the
                         EventBus as integration facts. Decider and
                         service-entity are scaffolded in both languages;
                         aggregate in Java only.
                         Neither an aggregate nor an entity is the god class R1
                         forbids — R1 forbids routing — but each carries its own
                         bar: every method enforces an invariant, no query
                         surface, no method-per-slice. A BC holding two designs
                         is the violation, and migrating between them is never
                         an automated fix.
  No event store? Still sliced
                         On the service-entity lane a view slice owns a
                         read-only query interface and a closed projection
                         instead of a projector, and its reads are strongly
                         consistent. What you skip is the projector, not the
                         slice. Choose the lane deliberately — a BC that never
                         needs history — and record the reason in its CLAUDE.md.
  The command is the contract
                         No request/response DTOs and no mappers — the command
                         type is the request body, the read model is the
                         response. Assembling a command from a path variable
                         plus a body is fine; a parallel type hierarchy is not.
  Repositories expose only what the slice declares
                         Spring Data interfaces extend the bare Repository
                         marker, never JpaRepository/MongoRepository — so the
                         declared surface IS the surface, and no view gets a
                         save(). Read shapes are closed interface projections,
                         never the entity. And never name a query after a CRUD
                         base method: findById is captured by the base, ignores
                         your projection, and fails as a ClassCastException at
                         the call site. Essentials' own DocumentDbRepository is
                         out of scope.
  Views evolve, not fork Projecting one more event extends the existing view
                         slice — rebuild it in place. A second directory is only
                         for a rollout the rebuild window cannot absorb, and
                         then it is a declared twin (supersedes:) whose original
                         gets deleted as part of the same change.

━━━ DELIBERATELY ABSENT ━━━

  Kotlin aggregate lane  AggregateRoot and StatefulAggregateRepository are a
                         Java-native family, and slice-check treats aggregates/
                         in a Kotlin BC as Advisory interop. Kotlin gets the
                         other two §R5 styles.

━━━ GET STARTED ━━━

  /essentials:init        ← new project
  /essentials:upgrade     ← existing project; catch up with the plugin
  /essentials:add-slice   ← existing project; add a feature
  /essentials:slice-map   ← existing project that follows the law; see it
  /essentials:slice-check ← existing project that follows the law; audit it
  /essentials:slice-discover ← existing project that does not; map it first
  /essentials:review      ← before you open a pull request
  Just ask                ← both skills load themselves: a question routes to
                            the docs, a change request routes to the slice
```

Adapt the header line to what detection actually found. Name the language only if a build file
said so; otherwise drop that segment rather than assuming Kotlin.

## Supporting material (mention only if asked)

- **Search** — `skills/essentials-docs/search.sh "<query>"` greps the bundled docs; `-l` lists
  files only, `-t <topic>` restricts to one topic.
- **Muting a skill** — under `skillOverrides` in settings, set `"essentials:essentials-docs"` to
  `"user-invocable-only"` when working in a non-Trustworks codebase that shares imports, and
  `"essentials:essentials-change"` to `"off"` to stop change requests routing through the slice law.
  The commands are unaffected either way.

## Rules

- Read-only. Never modify a file, never scaffold, never run a build.
- Print what detection actually observed. Do not name a language, DB profile, or project state you
  did not verify.
- Print no planned or backlog item: the plugin names only what ships. `━━━ DELIBERATELY ABSENT ━━━` lists
  design choices, not future work.
- `━━━ SLICES ━━━`, `━━━ REVIEWING A CHANGE ━━━` and the `/essentials:upgrade` entry are printed only
  when an Essentials project was detected — those commands operate on an existing project.
- Do not enumerate the bundled docs file-by-file. That is the skill's module index's job.
