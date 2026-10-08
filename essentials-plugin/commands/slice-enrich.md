---
name: slice-enrich
description: >
  Catch the slice.yaml manifests of an existing Trustworks Essentials project up on the human-owned
  fields they lack — flow (journeys), scenarios, summary, invariants, tier, projection consistency,
  featureFlag and a translation's maps. A script finds each absent field's evidence in the code and
  tests; the model drafts only from that evidence, the user accepts each journey and slice, and the
  script writes only what was accepted, only where the field is absent. --dry-run writes nothing.
user-invocable: true
allowed-tools: [Read, Bash, Glob, Grep, AskUserQuestion]
argument-hint: "[<path>] [--bc <name>] [--fields <list>] [--dry-run]"
---

# /essentials:slice-enrich

Answers **"what would these manifests say if they had been written against schema 1.4 from the start?"**
for a project whose `slice.yaml` files predate `flow:`, `scenarios:` and the other optional fields. The
Slice Atlas needs them: without `flow:` it derives a journey per command, and without `scenarios:` every
board shows invariants in their place (`/essentials:slice-map` Step 4 reports both as `flow-undeclared`
and `scenario-none`).

These fields are human-owned, and `--fix-manifests` never writes one. This command is the narrow
exception `references/slice/manifest-reconciliation.md` §1 sets out: **a model may draft an absent
human-owned field from evidence in the code or tests, and a person writes it by accepting the draft.**
Evidence or nothing; nothing written without a yes; never over a value.

Two scripts do everything mechanical. `slice-enrich.py propose` computes every candidate and its
evidence; `slice-enrich.py apply` writes the accepted values. **The model's job is the prose a script
cannot write** — a scenario's name and the steps the script could not extract, a summary sentence, a
journey name — and the conversation. It never edits a `slice.yaml` by hand, never re-derives a journey or
a test mapping the script reported, and never drafts a field the script reported no evidence for.

**Writes only the manifests, only the fields accepted, only where they are absent.** No source, no tests,
no `CLAUDE.md`, no git state.

## Arguments

| Argument | Meaning |
|---|---|
| `<path>` | Module or source root. Default: the resolved package root (`slice-map.md` Step 1) |
| `--bc <name>` | Propose for this bounded context only. Journeys are still computed over the whole project, because a journey crosses contexts; only this context's slices are offered |
| `--fields <list>` | Comma-separated subset of `flow`, `scenarios`, `summary`, `invariants`, `tier`, `consistency`, `featureFlag`, `maps`. Default: all of them |
| `--dry-run` | Run every step, ask every question, then show the diff `apply` would write and write nothing |

## Step 1 — Terrain and the redirect

`${CLAUDE_PLUGIN_ROOT}/commands/slice-map.md` Step 1, unchanged: resolve the roots and glob for
`slice.yaml`. **With no manifest anywhere, stop** and redirect to `/essentials:slice-discover` — there is
nothing to enrich, and inferring slices from code is that command's job. When `use_cases/` or `views/`
exist without manifests, point at `/essentials:slice-check --fix-manifests`, which creates them.

Then the runner: `"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for slice`, whose `uv` line picks
`uv run --script` or `python3 <script>` exactly as in `slice-check.md` Step 1.5. `MISSING` for the runner
means stop and quote doctor's lines.

**A dirty working tree:** say so before Step 3 and recommend committing first. The diff is the only review
the written manifests get (`manifest-reconciliation.md` §6). Unless `--dry-run`, ask once whether to go on.

## Step 2 — Lint first, and the schema copy

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py <project-root> --json
```

Over the whole project, as `slice-check.md` Step 1.5 runs it. A `1(a) parse` finding names a manifest
that does not parse: `propose` skips it and lists it under `skipped`; name each one, with
`/essentials:slice-check --fix-manifests` as the repair. Other findings are not this command's to fix —
count them in the report and name `/essentials:slice-check`. Exit 2 is "could not run": stop and say why.

**Check the schema copy knows 1.4** before proposing anything, per `references/slice/manifest-guide.md`
§3 ("A generator writes `flow` and `scenarios` only where the project's schema copy knows them"): look
for a `scenarios` property in the project's `scripts/slice-yaml.schema.json`. When the project has a copy
and it lacks the property, the pre-commit hook would reject every manifest this command writes. Offer
`/essentials:upgrade` (Group A2 refreshes the gate) first, and stop unless the user chooses to go on with
`--dry-run` only. A project with no lint gate is checked against the plugin's own schema, which `apply`
validates against anyway.

## Step 3 — Propose

```bash
tmp=$(mktemp -d)
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --json > "$tmp/facts.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-enrich.py propose <root> \
    --source-facts "$tmp/facts.json" [--bc <name>] [--fields <list>] --json > "$tmp/proposal.json"
```

The temporary directory is outside the project. `slice-source.py` exits 0, 1 and 3 with JSON written (3:
some source could not be read — say which files, from its `unparsed`, because evidence in them was not
seen); 2 wrote none: stop, because `propose` has no evidence without the facts.

| `slice-enrich.py propose` | What it means here |
|---|---|
| 0 | the proposal is written — possibly with nothing to propose. Then say so: every manifest already carries every field the code evidences. Go to Step 7 |
| 2 | could not run (no `pyyaml`, a bad root, unreadable facts). Stop and say why. Never draft a proposal by hand |
| 3 | no `slice.yaml` under the root: the Step 1 redirect |

The JSON (contract P1) is deterministic — same input, same bytes. What the model reads from it:

- `journeys[]` — `id`, `suggestedName`, `declared` (a name a member already declares: the journey extends
  that flow), `alsoDeclared` (only when members declare several flows — show them all and let the user
  choose), `slices`, `entries`, `bcs` and the `evidence` edges (`from`, `to`, `via`, `kind`: `feeds`,
  `dispatches` or `projects`).
- `slices[]` — `id`, `path`, `kind`, `bc`, `absent` (every absent field, with or without evidence), and
  `proposals`: one key per absent field the script found evidence for, each carrying that evidence.
  `scenarios.tests[]` holds the mapped test methods, each with its `name`, its `test` reference and what
  the script `extracted`, already in the manifest's scenario shape (`given` steps, a `when` step, `then`
  steps or `{error, message?}` or `{nothing: true}`); a `null` part is one the script could not read. `scenarios.unmapped: true` (with
  `tests: []`) means no test maps to the slice.
- `skipped[]` — manifests not proposed for, each with `why` (a `generator` other than `essentials`, or
  does not parse). A manifest with no `generator` is enriched.
- `ambiguousTests[]` — test classes that map to several slices and so went to none (`class`, `path`,
  `slices`). Name them in the report; never assign one by hand.
- `notInferable` — the absent fields no code can evidence, project-wide.

A field in `absent` with no key in `proposals` has no evidence: **draft nothing for it.**

## Step 4 — Journeys (`flow`)

Only when `flow` is in scope. A journey is a connected component of the non-view slices under "publishes
an event the other consumes" and "dispatches a command the other handles"; each view joins every journey
that publishes an event it projects. The script computed it — do not regroup slices.

Show each journey as: the suggested name (or the declared one it extends), its members in causal order
starting from `entries`, and the evidence edges as `from —via→ to`. Then ask, with **AskUserQuestion, at
most four questions per call, grouped by context** (a journey crossing contexts goes with its entry's
context):

| Answer | What happens |
|---|---|
| **Yes** | the journey is accepted under its suggested name |
| **Rename** | ask the name; it must match `^[a-z0-9][a-z0-9_-]*$` (`manifest-guide.md` §3) — refuse another and ask again. A journey that extends a `declared` flow keeps that name; renaming it would split it from its declared members |
| **Split** | ask which members form each part, and a name per part. Every member lands in exactly one part or is left out |
| **Skip** | the members get no `flow` from this journey |

Recommend **Yes** first when the entries and the edges read as one user goal; recommend **Split** when the
evidence shows two entries with no edge between their halves except through a shared view. A slice in
several accepted journeys gets a list (`flow: [a, b]`), typical of a shared view.

## Step 5 — Slices

Per context, per slice, the drafts for the fields in its `proposals`, each shown **with its evidence**
(`path:line` and the text):

| Field | Draft |
|---|---|
| `scenarios` | One per mapped test method, in given / when / then form (`manifest-guide.md` §3, the kind table), with the `name` and `test` the script gave. Take what `extracted` holds; for each `null` part, **read that test method, and only that method**, and complete it from what the method asserts. A part the method does not show stays out of the scenario, and a scenario that cannot name its `when` and `then` is not drafted. `unmapped: true`: no draft — suggest the test the slice lacks, in one line |
| `summary` | The evidence sentence, tightened to one sentence of at most 160 characters. Never a sentence the evidence does not say |
| `invariants` | `text` from the guard's message, `enforcedBy` as given. Reword only to make the message read as a rule |
| `tier` | The value given, unchanged — it is `slice-check --adopt-tier`'s rule (`slice-check.md` Step 4) |
| `consistency` | The value given per projection |
| `featureFlag` | The value given (`manifest-guide.md` §3) |
| `maps` | `{from, to}` pairs drafted from the evidence lines, naming only messages the evidence names |

Ask **yes / edit / skip per slice**, AskUserQuestion at most four per call, one call per context. **Edit**
asks which field and the change; a skipped slice, or a skipped field within an edit, stays absent. Never
draft a scenario from production code alone, and never draft a field whose evidence is not in the proposal.

## Step 6 — Write

Build the accepted set (contract P2): the proposal JSON, each accepted
journey given its final `name`, and each slice's `proposals` replaced by `accept: {field: value}` holding
only the final values the user approved, in manifest shape. `apply` reads only each slice's `path` and
`accept`, so resolve journeys yourself: `flow` is the accepted journey name, or the list of names. A
slice with no accepted field gets no `accept`. Write it into the temporary directory with a heredoc through Bash, never into the project:

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-enrich.py apply <root> "$tmp/accepted.json" [--dry-run]
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py <project-root> --json
```

It prints one line per manifest — `written <path>: <fields>`, `would write …`, `unchanged <path>` or
`refused <path>: <reason>` — and the report takes them verbatim. It writes each manifest as a text edit that keeps every existing byte, quotes every `path`, stamps
`schemaVersion: "1.4"` when it writes `flow` or `scenarios`, and validates the result against the schema
before writing it.

| `slice-enrich.py apply` | What it means here |
|---|---|
| 0 | every accepted field written, or already present with the accepted value (`unchanged`, which is what a second run reports). With `--dry-run`, the unified diffs come first, then the `would write` lines |
| 1 | some entries refused, the rest written. A refusal is a field that became present with another value since `propose` (that manifest is left untouched), a projection written as a flow mapping (its `consistency` is refused, not rewritten), or a result that adds a schema error the file did not already have. Report each as the script words it; never write the refused value by hand |
| 2 | could not run. Nothing is written. Stop and say why |

Skip the second `slice-lint.py` under `--dry-run`. Otherwise compare its findings with Step 2's: a
finding that is new on a manifest `apply` wrote is a defect in the write — report it plainly, with the
file, and do not repair it here.

## Step 7 — Report

`apply`'s lines, verbatim: per manifest, the fields written or refused. Then, once each:

- the journeys accepted, with their member counts;
- the manifests skipped, and why (`skipped[]`, plus every slice or field the user skipped);
- the test classes in `ambiguousTests`, which mapped to no slice;
- the slices with `unmapped` scenarios, as a count with the test each one lacks;
- the fields no code can evidence (`notInferable`), as one line — never per slice;
- "run `/essentials:slice-map-html` to see the journeys and scenarios".

With `--dry-run`, say that nothing was written.

## Out of scope — state these if asked

- **Never edits source or tests**, and never writes a test for a slice that has none.
- **Never writes a present field.** A field present in any form is the human's, the one exception being
  a `summary` that is exactly a slice template's placeholder.
- **Never proposes without evidence**: `notes`, `forbidden`, `sla`, `statutory`, `observability`,
  `profile`, `stability`, `characterization`, `supersedes`, `owner` and `status` are never drafted.
- **Never drafts a scenario from code alone** — tests first, each scenario linked to its test.
- **Never changes how the Atlas derives journeys.** It proposes `flow:`; the derivation for slices
  without one stays as `slice-map.md` describes.
- **Another generator's manifests** (`generator` other than `essentials`) are skipped with a note: that
  tool owns their `flow` and `scenarios` (`manifest-reconciliation.md` §1).
- **Audits nothing.** Machine-derived drift is `/essentials:slice-check --fix-manifests`; the gates are
  `/essentials:slice-check`.

## Verification fixture

`tests/fixtures/enrich/` is a Java slice tree whose manifests lack the fields, with sources and tests
carrying the evidence and the traps: a `requireNonNull` guard (no invariant), a test class named after a
type of another slice, a `generator: sdd` manifest, a manifest that does not parse, a manifest that
already declares `flow:`, a present-but-empty field, and a projection in flow style.
`tests/scripts/test_slice_enrich.py` byte-diffs `propose` against goldens in `tests/slice-enrich/golden/`
and checks that `apply` writes only absent fields, keeps bytes and comments, quotes paths, stamps 1.4,
refuses a field that became present and an invalid result, writes nothing under `--dry-run`, and is
idempotent on a second run.
