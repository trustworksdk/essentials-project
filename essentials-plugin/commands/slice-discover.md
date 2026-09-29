---
name: slice-discover
description: >
  Analyse a codebase that does not follow the slice law and infer what it would look like if it did —
  candidate bounded contexts, candidate slices in the four kinds, structural findings ranked by
  payoff, and a migration ladder. Works on layered Spring/JPA code and on Essentials aggregate-style
  code, not just decider-style. Strictly read-only; never edits source and never writes manifests.
user-invocable: true
allowed-tools: [Read, Bash, Glob, Grep, Write]
argument-hint: "[<path>] [--bc <name>] [--depth quick|standard|deep] [--write <file>]"
---

# /essentials:slice-discover

Answers **"what shape is this code in, and what is the nearest slice decomposition?"**

This is the brownfield counterpart to `/essentials:slice-check`. That command audits code against a
structure it *declares* — manifests are the ground truth and a finding is a contradiction. This one
has no declarations: it **infers** from evidence and every output is a candidate.

**Read-only, at every depth. There is no `--fix` mode and there will not be one** — an inferred
boundary applied wrongly is far worse than the layering it replaced. `--write` persists the report
and nothing else.

## Arguments

| Argument | Meaning |
|---|---|
| `<path>` | Module or source root. Default: the resolved package root |
| `--bc <name>` | Focus one candidate context named by an earlier run |
| `--depth quick\|standard\|deep` | Cost tier. Default `standard` |
| `--write <file>` | Also write the report to a **user-supplied** path. No default location — this plugin owns no runtime directory |

| Depth | Runs |
|---|---|
| `quick` | Passes 0–1. Entry-point census and context candidates only |
| `standard` | Passes 0–4 over one module |
| `deep` | Adds call-graph tracing of write paths per context |

## Step 1 — Load the law and the heuristics

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/discovery-heuristics.md
```

The law defines the target; the heuristics define the inference. Cite both by section; restate
neither.

## Step 2 — Pass 0: terrain

Cheap, always runs, and it decides whether the rest should.

1. Build system and module layout (`pom.xml`, `build.gradle{,.kts}`, reactor/multi-project).
2. Language per module. Slice Zero applies — a module mixing `.kt` and `.java` under one context is
   **flagged, not guessed at**.
3. Framework and persistence signals: `dk.trustworks.essentials.*`? Spring? JPA/Hibernate, JDBI,
   Spring Data, plain JDBC?
4. **Current R5 lane**, per `discovery-heuristics.md` §3.1 — one of the three sanctioned lanes
   (deciders, aggregates, service-entity), a **conflict** where one BC shows two, or **none**. Where
   nothing is sanctioned, report `lane: none (nearest: X)`: state-stored code with no Essentials on
   the classpath is *nearest* to service-entity, which says where it would migrate, not what it
   already is. Never report "nearest" as though it were "on".
5. Any existing `slice.yaml`, `use_cases/`, or `views/`.

**If manifests already exist, stop.** Report the terrain, say the project is already on the law, and
redirect to `/essentials:slice-check`. Do not run passes 1–4 — that is the other command's job and
running both produces two competing views of one codebase.

If the terrain shows no domain code at all (a library, a build-tooling module), say so and stop.

## Step 3 — Pass 1: candidate bounded contexts

Apply the evidence ladder in `discovery-heuristics.md` §1, and **§2 first** if a `.refac/` graph, written by an external modernization tool, is present:
adopt its bounded-context nodes as priors, respect the precedence order, and remember that its
contexts are module-granular while these are type-granular — a differing count is not a conflict.

Report per candidate: name, owned types, supporting signals **with their rung**, confidence, and
what argues against it. The last field is mandatory; an unfalsifiable candidate cannot be ranked.

*"No discernible context structure"* is a legitimate result. Prefer it to a manufactured boundary.

## Step 4 — Pass 2: candidate slices

Work from entry points inward, classifying each path into one of the four kinds per
`discovery-heuristics.md` §3.

Per candidate slice: proposed kind, proposed `<bc>.<snake_name>` id, the files that would move into
it, and the files it would have to **split**.

Two rules carry most of the accuracy here:

- **Group views by returned shape, not per endpoint.** `list()` and `getById()` over the same model
  are one slice with two queries (§R2). One slice per endpoint rebuilds the god controller as a fan
  of slices.
- **Same entity is not same shape.** An aggregation or summary over the same rows is a *different*
  read model, so a different slice.

State how many slices each god file splits into — that number is the finding, not its line count.

## Step 5 — Pass 3: findings

Rank by payoff per `discovery-heuristics.md` §4: sole-writer first, then cohesion, boundary, lane.

**Do not use `slice-check`'s Blocking / Should-fix / Advisory severities.** This code never opted
into the law; grading it against one it never adopted is how the report gets dismissed unread. The
one exception is behaviour that is a bug on any reading — a real concurrency hazard — which is
already what the sole-writer finding is, and it ranks first for that reason.

A context already deciding through an Essentials aggregate, or through the command bus onto a
state-stored entity, is **on a sanctioned lane** (§R5). Report it as such, apply § The aggregate's own
bar or § The entity's own bar respectively, and do not recommend deciders to either.

Where the lane is service-entity or nearest to it, also run `discovery-heuristics.md` §7 — the
lane-specific findings (write-repository query drift, command-type leakage, entity returned from an
API, god handler, bypassable invariant) and, just as importantly, its two traps: ORM-only accessors
and write-path-only finders are **not** findings.

## Step 6 — Pass 4: the ladder

Four rungs, per `discovery-heuristics.md` §5, each paying off standalone: regroup → split → add
manifests → adopt the framework.

Two things must appear:

- **Rung 3 is the handoff.** Once manifests exist, `/essentials:slice-check` takes over and this
  command is done. Name that exit explicitly.
- **Rungs 1–3 leave a better codebase even if Essentials is never adopted.** Rung 4 is optional. A
  ladder whose first rung mentions `Decider` or `AggregateRoot` is inverted — rewrite it.
- **Rung 4 offers three destinations, not one.** For state-stored code the service-entity lane is the
  shortest — formalise the entity into `entities/`, split the god handler, move the read side off the
  write repository, with no event store and no new persistence dependency. Presenting rung 4 as
  "adopt event sourcing" is now factually wrong and costs the proposal exactly the audience that
  already decided against a stream.

## Step 7 — Report

Provenance header, always:

```
slice-discover — <path> @ <git HEAD sha, short>   depth=<tier>
```

Run `git rev-parse --short HEAD` for the SHA. An inferred structure is a snapshot of a moving
codebase; without the stamp a stale map is indistinguishable from a current one, and two runs cannot
be diffed. If the path is not in a git repository, say `@ not-versioned`.

Then: terrain, contexts, slices, findings, ladder. Close with **what was sampled and what was
skipped** — a silent cap reads as "covered everything" and is the one way this report can mislead
without being wrong.

Three real candidates with honest confidence beat thirty speculative ones. If a pass yields nothing,
say so in a line.

With `--write <file>`, write the same report to that path and tell the user where it went. Never
choose the path yourself; suggest one and let them pass it.

## Cost bounding

Landscape-first: enumerate entry points, types, and repositories **before** reading any method
bodies. Read bodies only for paths being classified, and only at the depth requested.

At `standard`, cap the sweep at one module. If the tree exceeds the cap, analyse the largest module,
name the others, and recommend re-running per module — do not silently sample.

## Out of scope — state these if asked

- **Writes no source, at any depth.** No `--fix`.
- **Writes no manifests.** That is ladder rung 3, done by `slice-check --fix-manifests`.
- **Invents no bounded context.** Weak evidence is reported as weak.
- Not a general architecture review — dependency hygiene, coverage, and build are out of scope.
- Not a modernization programme — that is an external modernization tool's job; when one has written
  a `.refac/` graph, this command reads it rather than reproducing it.

## Verification fixture

`tests/fixtures/brownfield-layered/` is the oracle: a layered Spring/JPA tree whose ground truth is
written out in its `TEST-GUIDE.md`, including the false positives a run must **not** produce. Run
against it after changing anything in `discovery-heuristics.md`.
