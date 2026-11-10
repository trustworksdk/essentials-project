---
name: slice-check
description: >-
  Audit an existing Trustworks Essentials project against the slice-design law — layout, manifests,
  anti-god-class rules, wiring, processor handler shapes and boundary violations; manifest parsing,
  schema and sole-ownership checks run in scripts/slice-lint.py, the boundary-import gate 8 and the
  source facts behind gates 6, 11, 14 and 15 in scripts/slice-source.py. Read-only by default: --fix-manifests regenerates orientation artifacts, --adopt-tier backfills an absent tier,
  --fix-source proposes source fixes one at a time.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
argument-hint: "[<bc>] [--fix-manifests] [--fix-source] [--adopt-tier]"
---

# /essentials:slice-check

Audit slices against `rules/slice-design.md`. Default is **read-only** — it writes nothing.

Scope to one bounded context by passing its name; otherwise every BC under the resolved package root
is audited.

## Modes

| Mode | What it may change |
|---|---|
| *(default)* | Nothing. Report only. |
| `--fix-manifests` | `slice.yaml`, per-slice `CLAUDE.md`, per-BC `CLAUDE.md`, and the `.claude/rules` pointer. **Never source code.** |
| `--adopt-tier` | One field, one time: writes `tier` into manifests that have none. Never overwrites one. `/essentials:slice-enrich` covers `tier` too, with the other absent human-owned fields |
| `--fix-source` | Additionally *proposes* structural source fixes. Each is shown as a diff and applied only on explicit per-fix confirmation. |

`--fix-source` **refuses to run on a dirty working tree** (`git status --porcelain` non-empty) so
every applied change is trivially revertable. Say so and stop; do not offer to stash.

## Step 1 — Load the law

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/slice-law.py" --project <project-root>
```

It prints the sections of `rules/slice-design.md` that apply to this project: every slice kind, the
lanes its bounded contexts show (all three when it has no BC yet, both when a BC shows two), and its
persistence. Every `§` a gate cites is in that output; a section it leaves out belongs to a lane no BC
here is on. The schema is not read: gate 1 is `slice-lint.py`'s (Step 1.5). `manifest-guide.md` is read
on demand, not here: §3 (`sed -n '/^## 3\. /,/^## 4\. /p'`) when a gate turns on what a field means —
gate 11's `serves` and inbound events, Step 4's tier mapping — and the whole file under `--fix-manifests`.

Detect the language per `references/slice/slice-authoring.md` §1.

## Step 1.2 — Is the project on the law at all?

**No `slice.yaml` anywhere under the root, and no role directory (`use_cases/`, `views/`,
`automations/`, `external_systems/`) either:** the project has not opted into the slice law. Say so,
point at `/essentials:slice-discover`, which infers structure for exactly that case, and stop. Run no
gates, report no severities (no "missing manifest" finding), and write nothing, even under
`--fix-manifests`, `--adopt-tier` or `--fix-source`: an audit of a law the code never adopted is
noise. This agrees with `scripts/slice-lint.py`, which prints "no slice.yaml found" and exits 0 on such
a tree, and it mirrors `slice-discover`'s pass-0 redirect the other way round.

Role directories without manifests are a different case. The project is on the law with its index
missing, so the audit runs, each slice without a manifest is a **Should-fix** (Step 3), and
`--fix-manifests` creates the manifests.

## Step 1.5 — Run the two scripts, before reading a single source file

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py <project-root> --json
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <project-root> --check --json
```

Both carry their pinned dependencies (`pyyaml`, and `jsonschema` for slice-lint) in inline script
metadata, which `uv run --script` installs. Which runner applies is the `uv` line of
`"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for slice`, run first: `ok` ⇒ `uv run --script` as above;
any other status — `cache read-only → python3 fallback` included, where this shell cannot write uv's cache
and every `uv run` fails — ⇒ `python3 <script>` in its place, which uses the packages already installed, and a
script whose package is missing exits 2 (table below) — the line names which one, and how to install
it. Ignore doctor's exit code here: it is non-zero when `/essentials:slice-map` could not run, which
does not stop this audit; a missing or too-old `python3` with no `uv` shows as both scripts exiting 2. Pass `--bc <name>` to `slice-source.py` when the audit is scoped to one bounded
context. Run slice-lint over the whole project even then, because ids and ownership are
project-wide sets.

**`slice-lint.py` is gates 1, 3 and 4 in their entirety, plus gate 14's one manifest-only clause**
(`14 tier`, a write style written into `tier`). **`slice-source.py` is gate 8 in its entirety and the
syntactic half of gates 6, 11 and 14**: the findings whose `gate` starts `6 `, `8(`, `11(b)`, `11(c)`
or `14 `. It reads the Java and Kotlin sources with comments and strings stripped, resolves types
through imports, Kotlin `import … as` aliases, typealiases and fully-qualified names, and reports under
`unparsed` what it could not read. Every finding carries an `impact` (Step 3). Two more top-level
arrays are facts, not findings: `writeSites`, every mutating call on the write repository of an entity
declared in some `<bc>/entities/` — with the caller's `bc`, `slice`, `callerRole` and whether its
manifest `declared` the write — which gates 15(a) and 19 start from; and `unhandledVariants` (Step 3).
None of this is yours to re-derive. Parsing YAML, validating against a JSON Schema, intersecting id
sets, resolving an import to the directory it names, and reading which type a handler takes or which
route a mapping carries are mechanical, and a model doing them by eye produces confident wrong
answers. Take both scripts' findings verbatim into the report, with their `id` (`ESS-G4b`, `ESS-G8c`,
`ESS-G11b`, …). The judgement those gates still need is listed in their rows below. It is yours, and it
starts from the scripts' facts.

**The acceptance record.** A project may keep `slice-check-accepted.yaml` at its root: a YAML list of
`{gate, match, reason, accepted, review-by}` entries, one per finding the team has decided to live
with, `accepted` and `review-by` as dates. `slice-source.py` applies it to its own findings. A finding
whose `gate` starts with an entry's `gate` and whose file or message contains its `match` moves to
`accepted`, carrying the entry. An entry past its `review-by` applies to nothing: the finding stays,
noted `acceptance expired`, and the entry is listed under `expiredAcceptances`. A malformed entry is itself a finding, `0 acceptance record`
(**Should-fix**), and an entry that matched nothing is listed under `unusedAcceptances`. Apply the same
file by hand, by the same rule, to every finding the script did not filter — slice-lint's and the ones
you judge. This command never writes the file: an acceptance is the team's decision, recorded by them.

Exit codes decide what the report may claim:

| Script | Exit | Meaning | Report |
|---|---|---|---|
| either | 0 | ran, nothing found | the gates it covers passed |
| either | 1 | findings | take them verbatim. slice-lint: if its JSON says `schemaValidated: false`, gate 1(b) is also **not run** — findings win the exit code over a skipped validation |
| slice-source | 0 or 1, with `--fail-on` | `--fail-on blocking\|should-fix\|advisory` sets the lowest severity that exits 1; the default, `advisory`, is any finding. Accepted findings never count | run it here with the default. A project's CI may pass `--fail-on blocking`; findings below the threshold are still in the JSON at exit 0, and are still findings |
| slice-lint | 0 or 1, with `notes` | a manifest stamped with a newer `1.x` than the schema copy carries keys the copy does not define. `subscribesTo`, `handles`, `table` and `kind` on a projection are never notes: they are gate 1(b) findings at every stamp | not findings: list them once under the report's notes, with `/essentials:upgrade` as the way to have them checked (`manifest-guide.md` §3) |
| slice-lint | 2 | could not run (no `pyyaml`, unreadable schema: no JSON), or ran without `jsonschema` (or with one older than 4.0: "too old … needs jsonschema 4.0 or newer") and found nothing (JSON with `schemaValidated: false`). A non-UTF-8 `slice.yaml` is not exit 2: it is a Blocking 1(a) parse finding | no JSON: gates 1, 3, 4 and `14 tier` **not run**. `schemaValidated: false`: gate 1(b) **not run**, the rest passed. Either at the top, with the install line. Never pass `--allow-skip-schema` here: it turns a parse-only run into exit 0 |
| slice-source | 2 | could not run (no `pyyaml`, bad root) | gate 8 and the script's halves of gates 6, 11 and 14 **not run**, at the top, and gates 15(a) and 19 have no `writeSites` to start from |
| slice-source | 3 | incomplete: nothing found, but `unparsed` or `unverified` is non-empty | list every `unparsed` file and every `unverified` check as **not checked**, never as passed |

Consequences to honour:

- **Never fall back to doing a script's job yourself** when it cannot run, and never report its gates
  as passing. An unvalidated manifest set or an unread handler reported as clean is the exact failure
  these scripts exist to prevent.
- **Every unparseable manifest listed by slice-lint excludes its slice from gates 2, 5–19 as well**,
  because there is nothing to compare the code against. `slice-source.py` lists the same files under
  `skippedManifests`. Say which slices those are, once, at the top. A slice that silently vanishes
  from an audit reads as compliant.
- **A `slice-source.py` `unverified` entry is a check that had nothing to run against**, for example a
  view that declares BC events and carries no handler the reader found, or a handler inside an
  anonymous class, or endpoints served by a WebFlux `RouterFunction`. Report it as not checked, or read
  that source by hand and say in the report that you did.

## Step 2 — Gates

Run all nineteen. Each finding cites `path:line`. The gates — the table, the notes it is applied by,
gate 14's lane detection and the per-lane applicability table — are in a reference of their own, which
`/essentials:review` also reads in part. Read it whole, now:

```
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-check-gates.md
```

The index below is only a map of it, and the link target of every `ESS-G<gate>` a review reports; each
gate's rule is in the reference, never here.

| # | Gate |
|---|---|
| <a id="g1"></a>1 | **Parseable, then schema-valid** |
| <a id="g2"></a>2 | **Layout** |
| <a id="g3"></a>3 | **Uniqueness** |
| <a id="g4"></a>4 | **Sole ownership** |
| <a id="g5"></a>5 | **§R1** |
| <a id="g6"></a>6 | **§R2** |
| <a id="g7"></a>7 | **§R3** |
| <a id="g8"></a>8 | **§R4** |
| <a id="g9"></a>9 | **Wiring** |
| <a id="g10"></a>10 | **Projection idempotency** |
| <a id="g11"></a>11 | **Manifest ↔ code** |
| <a id="g12"></a>12 | **Project-copy freshness** |
| <a id="g13"></a>13 | **View duplication / migration twins** |
| <a id="g14"></a>14 | **Write-style lane (§R5)** |
| <a id="g15"></a>15 | **Write-repository purity and placement** (service-entity lane only) |
| <a id="g16"></a>16 | **Lane hygiene** (service-entity lane only) |
| <a id="g17"></a>17 | **Routing ↔ lane** |
| <a id="g18"></a>18 | **Spring Data repository surface** (all lanes) |
| <a id="g19"></a>19 | **Side effects after a state change** |

**Migrating a BC between write styles is out of scope for `--fix-source` on all three lanes** — see
Step 4.

### Refreshing the lint gate

Gate 12(b)'s offer replaces the project's `scripts/slice-lint.py` and `scripts/slice-yaml.schema.json`.
Never offer it blind: measure what the refresh adds first, then offer the manifest fixes before the
refresh, by `${CLAUDE_PLUGIN_ROOT}/references/init-assets/lint-gate.md` § Measuring a refresh — read that
section only when gate 12(b) found a stale copy. Run it with the runner Step 1.5 chose.

## Step 3 — Report

**Open with both scripts' results and any manifest they could not parse** — those slices are absent
from every other gate, and an absent slice reads as a compliant one. If a script could not run at all,
say that first and mark its gates **not run** (Step 1.5).

**Then the detected lane, one line per bounded context, with the signals `slice-source.py`'s `lanes[]`
lists as `path:line`** — `orders: decider (use_cases/place_order/PlaceOrderDecider.kt:12, …)`,
`shipping: service-entity (entities/ShippingOrder.java:11)`, `ledger: none — Blocking, two write
styles (…)`, `invoicing: decider — Blocking, manifests declare two lanes (…)`: a `14 declared lanes`
finding shows on its BC's lane line as well as in the findings. Without it a reader cannot tell a gate
that passed from one that was skipped, and gates 10, 13, 15 and 17 are skipped or reshaped per lane.

**Then a `Gates not run` block, before the findings.** A gate can go unrun for three different
reasons and all three must be named, because silence is indistinguishable from a pass:

| Reason | Example |
|---|---|
| **Lane** | gate 10 on the service-entity lane — no versioned read model exists to double-apply into; every lane-dependent gate on a BC gate 14 rates Blocking for two write styles or entities with event store |
| **Subject absent** | gate 18 on a project with no Spring Data repository anywhere. This is a 100% skip and, unreported, looks exactly like a clean pass |
| **Input missing** | gates 2, 5–19 for a slice whose manifest did not parse; gates 1, 3, 4 when the linter could not run; gate 8 and the script halves of 6, 11 and 14 when `slice-source.py` could not run, and each `unparsed` file and `unverified` check it lists. A project with no test tree is **not** missing input: it has no tests, so every view slice has none (Should-fix) |

A gate that never ran is not a finding *or* a pass, and collapsing it into either is the one
reporting error that costs a reader their trust in the whole report.

**`tier` absent is not a defect.** A project that adopted manifests before the field existed will
never gain it, because `tier` is human-owned and `--fix-manifests` will not write over a human-owned
field. Report absence as *"falls back to the project default"*, never as unset by mistake, and
mention `--adopt-tier` (Step 4) once rather than per slice — or `/essentials:slice-enrich`, when the
manifests also lack `flow` or `scenarios`, since one run of it catches up every absent human-owned field.

Then one table — Severity, Impact, id, `path:line`, finding — using the taxonomy in
`rules/slice-design.md` § Reporting severities, sorted by severity, then by impact in the law's order:

- **Blocking** — §R1–§R3 violated, an §R4 import of something that can change state or decide, more
  than one write style in a BC, a command type imported into `events/`/`entities/`/`aggregates/`, a
  decider unwired, a projection writing a versioned read model from a `@MessageHandler` that omits
  `OrderedMessage`, a read model queried inside a decider, a decider-handled command in a
  decider-style BC that does not implement its BC's routing marker.
- **Should-fix** — a slice missing its manifest, an §R4 import of something read-only, `_shared/`
  holding more than state or promoted below the three-consumer bar, an outstanding migration-twin
  retirement, a view slice with no test, a view reading through the BC's write repository, a write
  repository living outside `entities/`, a decider-style BC with no `routing/`, a `routing/` on the
  aggregate or service-entity lane, a dual write (gate 19).
- **Advisory** — file-cohesion smells, naming drift, a missing invariant record, a `_`-prefixed
  directory with no `CLAUDE.md`, a raw scalar id at the API edge (`6 raw id`).

**Impact** is the script's `impact` for its findings — `wrong-today`, `wrong-next-caller`, `coupling`,
`documentation`, the law's *wrong today*, *wrong on the next caller*, *structure-coupling* and
*documentation* — taken verbatim. For a finding you judged, pick it by the law's definitions; a gate
row that names one decides it.

**Then an `Accepted` block**: every finding the acceptance record filtered (Step 1.5) — the script's
`accepted` and the ones you applied it to by hand — one line each with its gate, `path:line`, the
entry's `reason` and `review-by`. Name each `unusedAcceptances` entry once, as a record to delete or
correct, and each `expiredAcceptances` entry once, as a decision due for review. An accepted finding is neither hidden nor counted: a reader must see what the team decided to
live with, and when they meant to look again.

**`unhandledVariants` are an Advisory note, not a gate finding.** Each entry is a view's
`ViewEventProcessor` handling none of a sealed event parent's listed variants and not naming them in
`@IgnoresEventTypes`. That processor logs a WARN about them at start
(`references/llm/LLM-postgresql-event-store.md` § Unhandled event types). Say which view and which
variants, and that the fix is a handler or an `@IgnoresEventTypes` entry, whichever the view means.

Three real findings stated plainly beat thirty padded ones. If a gate passes cleanly, say so in one
line rather than enumerating what did not fail.

## Step 4 — Repair (only if asked)

**`--adopt-tier`.** A one-time backfill for manifests that have no `tier`.
Writes `tier` **only where it is absent**, from the value resolved for the owning BC, and reports
every slice it stamped. This does not contradict the human-owned rule in
`manifest-reconciliation.md` §1 — that rule forbids *overwriting* a human's value, and there is no
value here to overwrite. A `tier` that is present and disagrees with the resolved default is a
**finding**, never an edit. Combinable with `--fix-manifests`; on its own it touches nothing else.

The BC's resolved value, decided without judgement, so both commands reach the same one:

1. **From the lane.** The one lane the BC's manifests declare; when they declare none, the lane
   `slice-source.py` detected — never an uncertain (`service-entity?`), conflicting or undetermined one.
   That lane maps by `manifest-guide.md` §3: aggregate → `cqrs-es`, service-entity → `service-entity`,
   decider → `cqrs-es` only when `slice-source.py` saw event-store use in the BC.
2. **From the siblings.** The one `tier` the BC's other manifests declare.
3. Both give a value: they must agree. One gives a value: use it. Otherwise — none, several, or a
   disagreement — there is no resolved value: write nothing and ask.

`/essentials:slice-enrich` applies the same rule to `tier` alongside the other absent human-owned fields;
`--adopt-tier` stays for a run that wants this one field and nothing else.

**`--fix-manifests`.** Regenerate `slice.yaml` and per-slice `CLAUDE.md` by three-way merge, following
`${CLAUDE_PLUGIN_ROOT}/references/slice/manifest-reconciliation.md` — read it first; it carries the
derivable/human-owned split, the per-field extraction rules, the `CLAUDE.md` contract, and the
whole-project ordering.

Machine-derived fields (`handles`, `publishes`, **`consumes`**, **`dispatches`**, `serves`, `endpoints`,
`writes`, `owns`, `reads`, `projections` **including `from` and `aggregateTypes`**) are overwritten from the code;
hand-written prose is preserved byte-for-byte; contradictions are reported rather than silently
resolved. Refresh the pointer file only after the user accepts gate 12's offer — never silently.
`flow`, `scenarios` and `requirements` are never written or rewritten: they are generator- or human-owned
(`manifest-reconciliation.md` §1). Where `flow` or `scenarios` is absent, `/essentials:slice-enrich` drafts it from
the code's cause and effect and the slice's tests, and writes only what a person accepts — name it in
the report rather than drafting here. A derived field the refresh changes can leave a scenario naming a
message the slice no longer has; slice-lint's `1(d)` then reports it — report it on, do not edit the
scenario.

`projections[].from` is on that list deliberately: the events a projector handles are the field most
likely to drift.

**Fix gate 1(a) first, and fix it as a text edit.** An unparseable manifest cannot be three-way merged
— there is no parsed side to merge — so quoting is repaired *before* the merge, on the raw text:
wrap the value of any `path:` that is not already quoted in `"`. That is the whole fix, it is
mechanical, and it changes no field's meaning. Re-parse afterwards and only then merge. A file that
still does not parse after quoting has a different problem: report it and leave it alone rather than
guessing at the author's intent.

**`--fix-source`.** For each finding, in order, one at a time:

1. State the finding and the intended fix in one sentence.
2. Show the diff.
3. `AskUserQuestion`: Apply / Skip / Stop here.
4. Apply only on Apply. Never batch, never auto-apply, never continue past Stop.

Only these three fixes are in scope — a god-file split, moving a misplaced event variant, and
moving a prematurely-shared `State`+`Evolver` out of `use_cases/_shared/` back into its single
consuming slice. (Promotion *into* `_shared/` is never an automated fix: it needs the three-consumer
judgement, and the move is trivial once that is settled.) Anything else is reported, not fixed: a
structural refactor applied wrongly is worse than the violation it fixes.

**Migrating a BC between any two write styles is explicitly out of scope for `--fix-source`.** Moving
decision logic between per-slice deciders, an aggregate, and a state-stored entity rewrites the BC's
invariant enforcement — and between the event-sourced lanes and the service-entity lane it also
changes what is durable. That is a design decision with a test suite and a data migration attached,
not a mechanical move. Report it and stop.
