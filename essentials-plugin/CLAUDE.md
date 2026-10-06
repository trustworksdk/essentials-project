# essentials — maintainer instructions

Applies to any change under `essentials-plugin/`. The repository-root `CLAUDE.md` governs the
Essentials framework build and git hygiene; everything specific to the plugin lives here and only here.

`plugin.json` `version` is the release: Claude Code updates an installed plugin only when that string
changes, so a change without a new version never reaches a user. It is the Essentials version the
plugin targets (the `essentials.version` pin in `references/stack/stack-pins.md`), then `-1`, `-2`, …
for plugin-only releases; the next Essentials release resets it. CI (`plugin-docs`) fails a change under
`essentials-plugin/` — an `LLM/` edit included, since it regenerates `references/llm/` — whose version
did not move, or that is not the pin with an optional `-N`. Land plugin changes on `main` in
release-sized batches, one version each.

Work on the plugin runs it from disk: `claude --plugin-dir essentials-plugin` from the repository root,
then `/reload-plugins` after an edit. In the devcontainer `claude` is already aliased to that
(`.devcontainer/scripts/post-create.sh`). A session an IDE starts does not get the alias.

This file loads when Claude reads a file in this directory — but nested `CLAUDE.md` files are
**not** re-injected after a `/compact`. If you are resuming a compacted session and about to edit
anything here, read this file first.

## `/essentials:intro` is the surface index — keep it in sync

`commands/intro.md` is the one place a user sees the plugin's *whole* surface. It rots silently:
nothing fails when it goes stale, no test covers it, and the drift is invisible until a user runs
a command it never mentioned or hunts for one that no longer exists.

**Whenever you add, remove, or rename a command, skill, or agent, update `commands/intro.md` in
the same commit.**

| Change | What to update in `intro.md` |
|---|---|
| Command added / removed / renamed | Its line in the relevant `━━━` block, and `━━━ GET STARTED ━━━` if it is an entry point |
| `essentials-docs` or `essentials-change` behaviour changes (routing, proactive advisory, self-check, the change classes) | The `━━━ SKILLS THAT FIRE ON THEIR OWN ━━━` block |
| The init template tree gains/loses a stack element, a DB profile, a web stack or a wiring test | The `━━━ WHAT /essentials:init SETS UP ━━━` block |
| A doc is added to or removed from `LLM/` (and so from `references/llm/`) | The bundled-docs count in the key-facts line — **the number is spelled out, verify with `ls references/llm \| wc -l`** |
| A framework opinion changes (intra-service scope, injection surface, slicing) | The `━━━ CORE PRINCIPLES ━━━` block |

Do **not** grow `intro.md` into a reference. One line per command, no worked examples, no
file-by-file enumeration of `references/llm/` — that is the skill's module index's job.

## Companion documents — what must move together

| Change | Also update |
|---|---|
| Command / skill added, removed, or re-scoped | `commands/intro.md`, `README.md` (the "What it does" list, Usage, the command count and the Layout block), and the `.claude-plugin/plugin.json` description, which names the commands |
| A script, a test directory or an eval case added, or the command that runs a check changed | Its step in `../scripts/plugin-check.sh`, the one list of the plugin's checks (`list` names them), **and** one `scripts/plugin-check.sh step <name>` line in the `plugin-docs` job of `.github/workflows/maven.yml`. CI runs the script's steps, so a check in the script but not the job is unenforced, and a command inlined in the job instead of the script cannot be reproduced locally. A check no CI step can run goes in the script's `manual` checks; a path that needs a scaffold build or an eval run gets a rule in its `classify`, so `changed` reports it. Then `README.md`'s Layout block |
| A doc added to or removed from `LLM/` | The skill's Module Index in `skills/essentials-docs/SKILL.md`, the doc count in `README.md`'s Layout block, and the intro's key-facts line |
| A trap line added to or retired from `LLM/LLM-traps.md` | Its id: the next free `ESS-NNN`, anchored `<a id="ess-nnn"></a>`; a retired id moves under `## Retired ids` as a tombstone and is never reused (`python3 ../scripts/check-ess-ids.py --baseline HEAD`). Then whether it has a grep-able signature in `scripts/review-scan.py`: add it with a positive and an `expect-not` case in `tests/review/signatures/`; a retired id makes the scan refuse to run until its signature goes. Then `sh ../scripts/sync-plugin-llm.sh` |
| A file under `references/init-assets/project/` (or its `manifest.json`), a pin, or an S2.1 row | The step of `commands/init.md` that asks the question gating it — the command and the tree are one contract. Then `python3 scripts/init-render.py --all-combinations`, `--update-golden`, and review the `tests/golden/init/*.tree` diff; `../scripts/plugin-check.sh scaffold matrix` when it changes what gets built |
| A file `/essentials:init` copies into a project, or a capability it offers to install | `commands/upgrade.md` Group A — an existing project reaches that capability **only** through `/essentials:upgrade`. A capability added to init alone ships to new projects and to nobody else, which is the gap `upgrade` exists to close |
| A requirement (S1–S11) added or re-scoped such that an existing project could now be non-conformant | The Group C table in `commands/upgrade.md`, with its severity and the symptom the failure presents as. A requirement no upgrade check names is one existing projects cannot discover except by booting and misreading a stack trace |
| A slice template added, removed, or changed | `references/slice/api-provenance.md` (re-run its verification snippet) and the owning kind skill's emission table. Then `python3 scripts/render-slice.py update-golden` and review the `tests/slice-golden/` diff, `uv run --script tests/scripts/test_slice_source.py` (it reads the goldens), and `../scripts/plugin-check.sh scaffold slices` — the goldens compile and start only there |
| A new placeholder in a slice template | The placeholder table in `references/slice/slice-authoring.md` §4 — `render-slice.py` refuses an unknown one (exit 2) |
| The pointer template's body changed | Bump its `<!-- essentials-slices-rules: vN -->` stamp by one **in the same edit** — the counter continues from its current value and never restarts at `v1`, because existing projects carry the values already issued and a lower number would never be offered a refresh. `/essentials:init` copies the file straight from `references/slice/project-rules-pointer.md.template`; there is no second copy |
| A section added/renamed in `rules/slice-design.md`, or a scope line under a heading changed | Its scope line (`<!-- slice-law: lane=… kind=… store=… -->`, grammar in `scripts/slice-law.py`'s docstring): a section without one is printed to every reader, so a lane- or kind-specific section left unscoped costs every other lane its bytes. Then `python3 tests/scripts/test_slice_law.py --update-golden` and review the `tests/slice-law/views.golden` diff; `slice-law.py --check` fails a view over its budget — raise `BUDGET_VIEW` only as a decision. Then every skill, command, reference and template citing it by section name — `scripts/check-citations.py` (rule `law-section`) names each one left dangling — and the intro's `━━━ CORE PRINCIPLES ━━━` block. A template's citations are rendered into user projects, where nothing checks them: a rename strands every project scaffolded before it, so rename only for a reason |
| A change class, decision point, or guard rail in `references/slice/change-procedure.md` | `skills/essentials-change/SKILL.md`, which routes by those §-numbers and breaks silently if they move. A decision point also gets a case in `tests/fixtures/change-router/cases.yaml`; then `uv run --script evals/build.py` |
| A manifest field's meaning, or which field a kind declares its inbound events in | `references/slice/manifest-guide.md` §3, `references/slice/manifest-reconciliation.md` §1–§2 (derivable/human-owned split + extraction rules), `commands/slice-check.md` gate 11 and its `--fix-manifests` field list, and **every reader** — `scripts/slice-index.py`, `scripts/slice-source.py`, `commands/slice-map.md` Steps 2–4 and the graph builder in `references/slice/slice-map-template.html` |
| `references/slice/slice-map-template.html` or the data contract in `commands/slice-map.md` §6 | The other one — they are one contract — then `uv run --script tests/scripts/test_slice_index.py` and `python3 tests/fixtures/slice-map/render-check.py`, and check the by-eye rows of that fixture's `TEST-GUIDE.md` |
| `scripts/slice-index.py` or `scripts/slice-source.py` changed | Its test: `uv run --script tests/scripts/test_slice_index.py` / `test_slice_source.py`; `--update-golden` only for an intended change, and review the diff. After a slice-index change, rerun `render-check.py` |
| A heuristic added/changed in `references/slice/discovery-heuristics.md` | `tests/fixtures/brownfield-layered/` — add a source element that exercises it and a row to that fixture's `TEST-GUIDE.md` and `expected.yaml`. A heuristic with no fixture element is unexercised; the fixture's `TEST-GUIDE.md` and `expected.yaml` are its expected results, run by `evals/` |
| `references/slice/slice-yaml.schema.json` changed | Keep it backward compatible: manifests written by another tool — a `generator` other than `essentials`, the optional event-model linkage — must still validate. Then `uv run --script tests/scripts/test_slice_lint.py`, which validates every fixture and every rendered golden: a schema tightening that the plugin's own artifacts fail is a defect in the tightening or in the artifacts, and you cannot tell which without both |
| A gate added, removed, or re-scoped in `commands/slice-check.md` | Whether its mechanical part belongs in a script. Decidable from manifests alone (parsing, schema, id sets) → `scripts/slice-lint.py`. A syntactic fact of the Java/Kotlin source (an annotation, a handler's parameter type, a mapping's route) → `scripts/slice-source.py`, with a case under `tests/slice-source/cases/`. The judgement stays in the gate. Never the same check in both the script and the gate. Gate numbers are review ids (`ESS-G<gate><clause>`): never renumber, and a new gate gets its `<a id="g<n>">` anchor, which `/essentials:review` links to |
| `scripts/slice-lint.py` changed | `commands/slice-check.md` Step 1.5 (its contract and exit codes), `references/slice/manifest-guide.md` § Validating a manifest, `commands/init.md` Step 9 and the hook template `references/init-assets/project/.githooks/pre-commit.template` — projects hold an installed copy that gate 12 diffs against this one — then `uv run --script tests/scripts/test_slice_lint.py` |
| A requirement (S1–S11) added or re-scoped in `references/stack/stack-contract.md` | Every citer — they cite **by number**: `skills/essentials-docs/SKILL.md`, `commands/init.md`, `commands/upgrade.md` Group C, and the three sibling files in `references/stack/`. Its decidable half in `scripts/stack-lint.py` `RULES_LIST` (cites are `path:line` + token) and `tests/stack-lint/expectations.json`, then `python3 scripts/stack-lint.py --self-test`. **Never renumber**: `ESS-S<n>` is a review finding id |
| A fix op added to `scripts/stack-lint.py` or `scripts/review-scan.py` | Its row in `commands/review.md` § Applying a fix descriptor — the op → edit table exists there once |
| A review-scan check that duplicates a stack-lint check | The pair table in `commands/review.md` Step 3e |
| A pin needs changing | `references/stack/stack-pins.md` — the only place. Then `python3 scripts/init-render.py --check` fails until `--update-golden`, because the goldens hold every rendered pin: review that diff |
| A requirement's evidence changes | Its `Proof:` line in `stack-contract.md`. Every requirement cites a `references/llm/` doc or inlines the configuration that *is* the fact; a requirement with neither is an opinion |
| A `references/llm/` doc states an obligation a generated project must meet (a non-transitive dependency, a required registration, a package move) | `stack-contract.md` — **restate it as a requirement**. `/essentials:init` never reads `references/llm/`, so a `Proof:` line alone does not ship it |
| Java or Kotlin lane capability changes (a command starts or stops supporting a language) | The **Status paragraph** of the affected `references/stack/<language>-spring-boot.md`, `commands/intro.md` (and its `━━━ DELIBERATELY ABSENT ━━━` block when the Kotlin aggregate lane is concerned), and `README.md` § Deliberately absent |
| A doc changed in `LLM/` | Run `scripts/sync-plugin-llm.sh` from the repository root (the pre-commit hook installed by `scripts/install-git-hooks.sh` does it for you) and commit both. Never edit `references/llm/` — the hook refuses the commit and the CI drift gate fails it |
| A fixture's source changed | `uv run --script tests/fixtures/check-expected.py` names every `expected.yaml` line that moved: fix them and keep `TEST-GUIDE.md` in step, then `uv run --script evals/build.py`. For `worked-example` or `service-entity`, `uv run --script tests/review/judgement/check-patches.py`: a patch that no longer applies is re-cut, and its `expected.yaml` lines moved. For a compiled fixture, `../scripts/plugin-check.sh scaffold slices --case fixture-<name>` |
| A fixture's `expected.yaml`, `tests/fixtures/change-router/cases.yaml`, or an eval's `grading.yaml` changed | `uv run --script evals/build.py`, and commit the regenerated graders. Never hand-edit a `gen-*` grader or a `change-*` case |
| A feature planned, started, or dropped | Nothing. The plugin names only what ships: no planned or backlog item in the intro, the README or `commands/init.md`, because a list of future work shipped in the plugin goes stale. A feature appears when it ships |

There is no `references/architecture.md` in this plugin. Do not invent one — `README.md` carries
that load.

## Design invariants — do not drift from these

- **`LLM/` (repository root) is the source; `references/llm/` is generated by
  `scripts/sync-plugin-llm.sh`; never edit it.** The copy is generated from `LLM/`, with links out of
  `LLM/` rewritten to GitHub URLs. A doc fix goes into `LLM/` and reaches the plugin through the sync. `SKILL.md` routes and decides; it must not restate doc content, and the docs must
  never be copied into a scaffolded project. Nothing in those docs is authored from memory — every
  claim traces to the Essentials source. The design guide, `references/design/essentials-design.md`,
  is the one plugin-authored doc: Trustworks modelling guidance that links into `references/llm/` for
  every framework fact rather than restating it.
- **Mechanical steps are scripts; the model keeps the judgement.** Whatever can be computed —
  rendering a project or a slice, parsing manifests, the syntactic facts of a source file, the
  decidable half of S1–S11, a trap's grep-able signature — is a script under `scripts/` with a
  committed golden or test, and the command takes its output verbatim: `init-render.py`,
  `render-slice.py`, `slice-lint.py`, `slice-source.py`, `slice-index.py`, `slice-law.py`,
  `stack-lint.py`, `review-scan.py`. The model elicits, reads code for what a script cannot decide, merges into files
  the user already has, and diagnoses failures. It never writes a rendered file freehand and never
  re-derives what a script reports: a model doing mechanical work produces confident wrong answers.
  Every script is Python ≥ 3.11 and either standard library only (`#!/usr/bin/env python3`) or
  pins its dependencies in PEP 723 inline metadata and runs with `uv run --script`. The pins
  travel with the copy init installs into projects. Scripts are Pyright-clean in basic mode.
  The one exception is `scripts/doctor.sh`, bash (3.2-safe, no tools beyond those it probes) because it
  detects a missing Python; its impact lines restate what each command does without a tool, so they move with that command.
- **Goldens are committed and byte-diffed; regenerating one is a review, not a fix.**
  `tests/golden/init/`, `tests/slice-golden/`, `tests/slice-index/golden/` and
  `tests/slice-source/golden/` are what the renderers and readers produce today. Each has an
  `--update-golden`; run it only for an intended change, then read the diff before committing. A
  golden regenerated to make a red check green has stopped checking anything.
- **Exactly two copies leave the plugin, and the second was an explicit decision.** The first is
  `.claude/rules/essentials-slices.md` (below). The second is the lint gate — `scripts/slice-lint.py`
  plus a copy of `slice-yaml.schema.json` beside it — installed by `/essentials:init` Step 9 or
  `/essentials:upgrade` Group A2, and **only on the user's yes**. It exists because a gate that lives only in the plugin cannot run in the
  project's CI or pre-commit hook, and that was the whole point: nothing in a JVM build reads
  `slice.yaml`, so a broken manifest ships and the slice silently vanishes from every audit. Both
  copies are governed by the same discipline — `/essentials:slice-check` gate 12 detects a stale
  project copy and **offers** a refresh, never overwriting silently. **No third file joins them
  without an explicit decision**, and the pressure to add one is exactly the pressure this invariant
  resists: each copy is a thing that can go stale in a repository nobody here can see. The rest of
  a rendered project is generated once and then belongs to the project; nothing keeps it in step.
- **Two scripts are the deterministic half of `slice-check`, and they stay deterministic.**
  `scripts/slice-lint.py` owns gates 1, 3 and 4, plus gate 14's manifest-only `14 tier` clause. It does
  parsing, JSON Schema validation and set intersection over manifests. `scripts/slice-source.py` owns the
  syntactic half of gates 6, 11(b) and 14. It reads the Java and Kotlin sources with comments and strings
  stripped, and reports whatever it could not read (`unparsed`, exit 3) instead of guessing. `slice-check`
  Step 1.5 takes both outputs verbatim. Judgement stays in the command: the service-entity "state loaded,
  mutated and saved in place" criterion, gate 11(a), every other gate, and every judgement field of
  `manifest-reconciliation.md` §1. Neither script gets a heuristic or an LLM-shaped judgement.
  slice-lint stays manifest-only, because it is the copy that ships into projects. Both scripts **report
  and never write**. Repair is `--fix-manifests`'s job, and a linter that edits is a linter nobody dares
  wire into CI.
- **The first copy — `.claude/rules/essentials-slices.md`.** It is a **pointer**,
  capped at 35 lines, carrying only the directory vocabulary, the four slice kinds, the boundary
  rule, and an `<!-- essentials-slices-rules: vN -->` stamp. It restates no framework API and no
  worked example — the law stays in `rules/slice-design.md` so plugin updates propagate live, and
  the stamp is what lets `/essentials:add-slice` and `/essentials:slice-check` detect a stale project
  copy and *offer* a refresh (never overwrite silently). **Growth of that file past a pointer is
  exactly the drift this invariant exists to prevent.** Source of truth is
  `references/slice/project-rules-pointer.md.template`, and there is exactly one copy of it in this
  plugin — `/essentials:init` copies that file directly into the generated project. The stamp is a
  counter that only ever goes up; see the pointer row in the companion table above.
- **`references/stack/stack-contract.md` is the application law, and it is cited by number, never
  restated.** It is to the application what `rules/slice-design.md` is to a bounded context's
  interior, and it follows the same convention: S1–S11 are quoted by number so a change propagates
  to every citer without rewriting them. Two rules keep it from rotting into prose. **Only
  `stack-pins.md` may name a version** — a requirement that names one rots the day the version
  moves, and a stale requirement is worse than none. And **every requirement carries a `Proof:`
  line** naming a `references/llm/` doc or inlining the configuration that *is* the fact; a
  requirement with neither is an opinion, and this plugin does not ship invented framework facts.
  **Be exact about what is executed and what is documentary.** CI renders the nine golden init
  cells and the slice compositions and builds them against the reactor, starting a Spring context
  on Testcontainers (`../scripts/plugin-scaffold.sh`); `stack-lint.py` checks each render. So "the
  template does it" is a claim CI checks — **for the cells and cases it builds, and nothing else**.
  A requirement no cell exercises, and every `Proof:` line, is still documentary. Do not stretch a
  green build into a claim about a combination it never rendered.
  **And the corollary: a fact that lives only in `references/llm/` does not reach a generated
  project.** `/essentials:init` reads the contract; it never opens the LLM docs, and a `Proof:` line
  is a maintainer's trace, not an include. Every requirement a generated project must satisfy has
  to be *stated in S1–S11* — citing the doc that knows it is not enough. That is why S2.1 restates
  the non-transitive dependency set from `LLM-spring-boot-starter-modules.md` § Dependencies
  instead of stopping at a citation of its *Starter Selection* section. When you add a `Proof:`
  line, check whether the proof contains an obligation the contract has not restated.
- **`scripts/stack-lint.py` is the decidable half of S1–S11: deterministic, standard library only,
  reports and never writes.** Its finding ids are `ESS-S<n>` plus a `check` slug that is never
  reused; a retired slug stays in `RETIRED_CHECKS`. `/essentials:init` Step 13.7,
  `/essentials:upgrade` Group C and `/essentials:review` all take its findings verbatim — a
  judgement it cannot make stays with the command, never becomes a heuristic in the script.
- **Review ids are anchored, stable, and defined in one place each.** `ESS-NNN` is defined only on
  its `LLM/LLM-traps.md` line: sequential, never renumbered, never reused, retired by a tombstone
  under `## Retired ids` (`../scripts/check-ess-ids.py` enforces it). `ESS-S<n>` is the stack
  contract's numbering and `ESS-G<gate><clause>` the gate table's in `commands/slice-check.md`;
  neither is renumbered. These three are the one catalogue — no second list of ids, fixes or
  recipes anywhere else in the plugin.
- **`/essentials:review` reviews a change, never a project.** Scripts decide what scripts can decide
  (review-scan, stack-lint, slice-lint, slice-source, taken verbatim); whole-tree findings the change
  did not introduce are counted, never listed. It writes nothing except under `--fix`, which applies
  only script fix descriptors (`mechanical`, confirmed or stack-lint, never `ESS-S1`), one at a time,
  each asked. The op → edit table exists once, in `commands/review.md` § Applying a fix descriptor.
- **`scripts/review-scan.py` is the deterministic half of `/essentials:review`:** standard library
  only, reports and never writes, matches added lines only, and every signature cites a catalogue id
  and its evidence in the framework source. A signature that needs judgement ships as `candidate`, never `confirmed`.
- **`tests/fixtures/worked-example/` is what the slice tooling reads, and that is its only job.**
  It is the {Kotlin, decider-lane} tree carrying manifests, so it is the "project already has manifests" case
  for `slice-map` and `slice-discover` and the decider-lane input for `slice-check`
  (`service-entity` covers {Java, service-entity}, `aggregate-lane` {Java, aggregate}, and `multi-lane`
  gate 14's multi-lane Blocking branch). It is sample **input**, never expected **output**: the slice
  templates are checked elsewhere — rendered by `scripts/render-slice.py`, byte-diffed against
  `tests/slice-golden/`, and compiled and started on the init hosts in CI. The generators
  in `references/slice/templates/` are more current than this example — never invert that.
  It carries **no version pins and no build file**, which is precisely why it is cheap to keep: CI
  compiles it by overlaying its sources onto a rendered host (`tests/slice-compile/`), so it never
  needs its own. Do not grow it back into a project: if it acquires a `pom.xml`, a `package.json` or
  a `Dockerfile`, the rot is back.
- **`rules/slice-design.md` is the slice law, and it is standalone.** It depends on no other
  plugin — an Essentials project gets the whole law from this plugin. Skills and commands cite it
  **by section name and never restate it**. `rules/` is a plugin convention, not a Claude Code
  extension point: it costs nothing until something loads it. **It stays one file.** A reader that
  knows its lane and kind loads it through `scripts/slice-law.py`, which prints only the sections
  their scope lines allow; splitting it into per-lane files instead would break every citation of a
  moved section, including the ones the templates render into user projects.
- **The four slice skills write files, through `scripts/render-slice.py`, and that is deliberate.**
  `essentials-docs` stays model-invoked because its auto-trigger *is* the product; the slice skills
  are the opposite — `disable-model-invocation: true`, entered by path from a command that has
  already elicited a bounded context and a name. They carry `Write`/`Edit` so the emission logic
  exists once instead of five times: the script renders and wires, the skill fills the TODOs it
  reports. Do not "fix" either of these to match the other.
- **Never let a template name an unproven Essentials symbol.** Every `dk.trustworks.essentials.*`
  import in `references/slice/templates/` must appear in `references/slice/api-provenance.md` with
  the `references/llm/` doc that proves it. The ledger carries its own verification snippet — run it
  when you touch a template.
- **`essentials-docs` stays model-invoked** (`user-invocable: false`). The auto-trigger on imports
  and framework type names *is* the product; users mute it with `skillOverrides`, not by us
  weakening the trigger description.
- **`essentials-change` is model-invoked and enters the kind skills by `Read`, not by invocation.**
  This is the one place the two skill policies above meet, and it is easy to "simplify" wrongly. The
  router must fire on prose, so it is model-invoked; the four kind skills must not fire on prose, so
  they stay `disable-model-invocation: true`. The router therefore *reads* a kind skill and follows
  its procedure with inputs it has already resolved — exactly the contract `/essentials:add-slice`
  honours. **Do not make the kind skills model-invocable to "let the router call them".** That would
  put file-writing skills one ambiguous sentence away from firing, which is what the flag prevents.
- **`essentials-change` gates on the project before it says anything.** `change-procedure.md` §1 ends
  in *stop silently* for a non-Essentials repository, and that row is load-bearing: a change-request
  trigger firing in unrelated repositories gets the skill muted globally, which costs exactly the
  projects it was built for. Widening the trigger description without widening the gate is the drift
  to watch for.
- **Every `path:` this plugin emits into a `slice.yaml` is quoted — templates, examples, fixtures and
  guides alike.** The manifests use flow mappings, and inside one an unquoted scalar containing `{`
  opens a nested mapping, so a single path variable makes the file invalid YAML. Nothing in a JVM build
  reads `slice.yaml`, so it compiles, tests green and ships broken; the failure only appears when a tool
  parses it, and then the slice **vanishes** from the map or the audit rather than erroring. That is why
  the rule is *always quote* and not *quote when it has a brace*: the second rule is correct until
  someone adds a path variable to an endpoint that did not have one, which is a routine edit. An
  unquoted template renders valid YAML only while `{{apiPath}}` happens to be derived as `/api/<bc>` —
  luck, not design, and it models the wrong pattern to anyone copying it.
- **A slice's inbound events are the union of `consumes` and `projections[].from`.** Views declare the
  events their projector handles on the projection; automations and translations use `consumes`. Any
  reader that consults `consumes` alone reports every correctly-generated view as reacting to nothing,
  on manifests that are entirely correct. When adding a
  reader, union the two; when adding a writer, write only the field that kind owns, never both.
- **`/essentials:slice-map` renders, never grades.** `scripts/slice-index.py` computes the map from
  the manifests, and `scripts/slice-source.py` adds the facts a manifest does not carry. The map
  shows eight cheap divergence checks and stops there: manifest parses, directory exists, kind
  matches location, handled events declared, endpoint appears in source, id uniqueness, twin
  pairing, and dangling consume (`consumes` ∪ `projections[].from`). Every R1–R5 gate, every severity, and every `--fix`
  belongs to `/essentials:slice-check`; a map that starts scoring becomes a second, weaker audit and
  the two then disagree in front of the user. Its counterpart rule is `slice-discover`'s: `slice-map`
  requires manifests and redirects without them, `slice-discover` redirects *with* them.
- **`/essentials:init` renders the project from `references/init-assets/project/` with
  `scripts/init-render.py`; there is one tree, gated per answer by `manifest.json`.** The
  renderer is the only skeleton source — no Spring Initializr, no second template tree, no file the
  model writes freehand. The Maven wrapper is generated by a post-render hook with pinned versions.
  Two runs with the same answers produce the same bytes, which is what makes the goldens and the CI
  builds mean anything.
- **`/essentials:init` lints and builds what it renders (Step 13.7), and that step is not optional.**
  CI builds the same tree in nine cells, but not on the user's machine, with their JDK, Maven, Docker
  and network. The contract's most expensive failures — S2.1, and S3.1's leftover Jackson 2 module
  jar — compile cleanly and fail at context startup, so the step runs `stack-lint.py` on the render,
  then `verify` (context start, `OpenApiContractIT`, and the CORS preflight on a standalone frontend
  against Testcontainers). Do not weaken it to a compile, and do not let it become advisory: an
  unbuilt scaffold reported as success is exactly the failure this step exists to prevent. Without
  Docker the result is `compiled-only`, never `passed`. When Step 13.7 turns up a dependency S2.1
  does not list, the fix goes in the **contract** first, then the tree.
- **`/essentials:init` creates, `/essentials:upgrade` catches up, and neither does the other's job.**
  Init runs once and never revisits a project, so a capability offered only by init reaches new
  projects only — the lint gate offered at Step 9 would reach nobody who already had a project,
  and `slice-check` gate 12 cannot see the absence of a gate that was never installed. The split is: **init elicits and generates, upgrade detects and
  offers.** Upgrade re-asks nothing (language, profile, frontend mode and coordinates are facts of
  the project — read them), regenerates no skeleton, touches no slice source, and **moves no version
  pin** — a pin move is an upgrade decision wanting an ADR, and folding it in here would make
  "catch up with the plugin" mean "change every dependency in the application". The one exception
  is `s1-kotlin-compiler-floor`: a Kotlin compiler that cannot target the project's own Java
  baseline is a project that does not compile, not a lag. Upgrade reads the project's own
  `essentials.version`, and a finding that only holds on the plugin's Essentials release
  (`appliesWithUpgrade` in stack-lint's JSON) is reported as applying with that upgrade, never
  offered on its own — its fix would break an application still on its older release. Upgrade's Group C is
  `scripts/stack-lint.py`'s output plus the judgement rows, read against the contract live — never a
  diff against the template tree `/essentials:init` renders from: a project diverges from its render
  the day work starts, and a diff would report the application itself as drift.
  **Both halves of a new capability ship together or the capability is half-shipped.**
- **The version stamp is a comment, not state.** `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->`
  lives in the project's own `CLAUDE.md`, a file init was writing anyway. `ESSENTIALS_VERSION` is the
  `essentials.version` pin in `references/stack/stack-pins.md` — the Essentials release the plugin
  targets; nothing reads `plugin.json`'s version, whose `-N` suffix says nothing about a project.
  The stamp exists so upgrade can *report* which Essentials version a project was scaffolded against — nothing branches on it, and
  every check reads current files instead. An absent stamp or an old `v<semver>` one (a project
  scaffolded before the plugin's first release, plus the `skip`/`merge` paths) is reported as such,
  degrades the report by one line, and must never gate a check. This is what keeps the statelessness invariant intact: the
  moment a check needs the stamp to be correct, the stamp has become a `.essentials/` directory with
  extra steps.
- **The placeholder contract is `scripts/init-render.py`'s docstring, and it is load-bearing.**
  The grammar (`{{var}}`, `{{pin:<stack-pins name>}}`, `{{why:S2.1:<artifact>}}`, the nesting
  `<!-- IF var -->` / `IF var=a|b` / `IF var!=a` … `<!-- END var -->` blocks, `<!-- PATHS -->`) and
  the variables — the answers `language`, `db`, `web`, `frontend`, `compose`, `lintGate`,
  `projectName`, `groupId`, `artifactId`, `packagePath`, and the derived `stack`, `sourceLang`,
  `appFile`, `packageDir`, `db_label`, `essentialsVersion` (the `essentials.version` pin) — are
  defined there and nowhere else; `__PACKAGE__` in a manifest `dst` path is the package directory.
  An unknown placeholder, pin, IF variable or IF value is exit 2, even in a branch no answer set
  selects. A template can reach a version only through `{{pin:…}}`. Add a variable to the script
  and the question that supplies it to `commands/init.md` in the same commit.
- **Never invent an Essentials API.** Type names, signatures, starter artifactIds, and config keys
  must be verified against the framework before shipping in a doc, the skill, or a template — a
  fabricated API looks authoritative and fails at the user's compiler.
- **Nothing Trustworks-internal ships in the templates** (scratch scripts, internal hosts,
  credentials). Init deletes known strays as a safety net, not as a licence to add them.
- **Stateless.** No `.essentials/` runtime directory and no Claude Code hooks. Persistent state is a
  design decision to raise, not to add quietly. This is why `/essentials:slice-discover --write`
  and `/essentials:review --out` take a **user-supplied** path and default to printing: a
  plugin-owned directory starts as one report and accretes config, cache, and last-run state, which
  is how statelessness actually dies.
- **`slice-discover` never writes source, at any depth, and gains no `--fix` mode.** It *infers*
  structure where `slice-check` *audits* declared structure; an inferred boundary applied wrongly is
  worse than the layering it replaced. Its findings rank by **payoff**, never by the law's
  Blocking/Should-fix/Advisory severities — that code never opted into the law.
- **`tests/fixtures/` is never shipped as user-facing content.** CI compiles the Java and Kotlin
  fixtures that hold application sources by overlaying them onto a rendered host
  (`tests/slice-compile/overlay.py` lists which, and why the rest are not); nothing builds them in
  place. Each fixture's expected results are its `TEST-GUIDE.md` for a human and its `expected.yaml`
  for a machine, kept in step by `tests/fixtures/check-expected.py`. The sources carry **no answer
  hints** — no finding labels, gate numbers or verdicts in names or comments — because the eval
  sandbox stages the sources without the expectation files and the model must not read the answer.
  Fixtures must stay obviously synthetic so nobody mistakes one for a template, and must contain
  **traps** as well as findings — a fixture that only contains findings proves nothing about false
  positives.
- **The model-judgement steps are scored by `claude plugin eval`, before each release, not per PR.**
  `evals/` holds one case per judgement a command makes (the audits, discovery, the map, the change
  router, add-slice, review). Its graders are generated by `evals/build.py` from the fixtures'
  expected results, so an expectation is written once, in `expected.yaml` or
  `change-router/cases.yaml`; the CI gate is `build.py --check`, which calls no model. A run costs
  real tokens and the model is not
  deterministic: a single red run is a question, not a verdict (`evals/README.md`).

## Before committing

From the repository root, `scripts/plugin-check.sh quick` runs every step of the `plugin-docs` job of
`.github/workflows/maven.yml`, in its order, with the same commands, arguments and `UV_PYTHON=3.11`: CI calls the
same script one step at a time, so the two cannot drift. Each step exits 0. `scripts/plugin-check.sh list` names the
steps and `step <name>` reruns one. It needs `git` and `uv` (uv fetches Python 3.11, the scripts' declared minimum, and
the PEP 723 pins). Locally `ess-ids` compares with HEAD and `plugin-version` with the merge base with `origin/main`
(CI: `HEAD^1`), so an unbumped plugin change fails before the push. `render-check` shows SKIPPED without a headless
Chrome (the arm64 devcontainer): nothing was checked, which is not a pass; pass `--chrome PATH` where one runs.

`scripts/plugin-check.sh changed [BASE]` maps every changed path to its row in the companion table above and prints
what else the change needs, with the commands.

The scaffold builds — the `scaffold ·` jobs beside `verify`, run on every PR — when a template, a pin,
the init tree, an S2.1 row or a fixture's source changed: `scripts/plugin-check.sh scaffold [matrix|slices|all]`.
It installs the reactor first (`mvn clean install -DskipTests -DskipDependencyCheck=true`; `--no-install` skips it;
CI installs only the Essentials modules the init templates declare, `-am`, and a full install is a superset), refuses
without Maven, a JDK or Docker, and needs nothing else building in this checkout (root `CLAUDE.md`, `target/`). The
remaining arguments go to `scripts/plugin-scaffold.sh`. About 1.5 minutes per mode once warm. The frontend legs are
not automated and need Node; run them in each cell with a frontend, after the matrix (`--work` names where the cells
are; the default is `${TMPDIR:-/tmp}/essentials-scaffold`).

```bash
# frontend legs, per cell with a frontend, in <work>/<cell>/project/frontend (the first command is the
# `frontend-lockfile` hook of references/init-assets/project/manifest.json):
npm install --package-lock-only --no-audit --no-fund && npm ci && npx orval && npx tsc --noEmit && npx vitest run && npx eslint . && npm run build
# embedded cells also, in <work>/<cell>/project: the SPA must reach the JAR (<V> = the root pom's <revision>)
mvn -B -ntp -Dessentials.version=<V> package -DskipTests && unzip -l backend/target/*.jar | grep -Eq ' (BOOT-INF/classes/)?static/index\.html$'
```

Scheduled, not per PR (`.github/workflows/plugin-scaffold-scheduled.yml`, nightly and on demand): the
rendered `CLAUDE.md`'s inner-loop command, the Compose dev loop, the S2.1 dependency-removal run and
the released-version run.

Checks no CI step runs: `scripts/plugin-check.sh manual` — the bundled-docs count in the intro, the
slice-map data point, unquoted manifest paths, stale design-guide names and `/sdd:` routes, both halves of the
init/upgrade split, the version-stamp placeholder and the `Proof:` lines. It prints `rules/slice-design.md`'s
sections beside the intro's `━━━ CORE PRINCIPLES ━━━` block as READ: compare them by eye.

## Before a release

A release is a commit that reaches the marketplace ref with a new `plugin.json` version. Before it, run
`scripts/plugin-check.sh release` (with `--chrome PATH` on a machine with Chrome): quick, manual, scaffold all, the
eval suite over every case with the flags of `evals/README.md` § Run it, `claude plugin validate essentials-plugin`
and `claude plugin validate .` (the marketplace manifest), and `CHANGELOG.md`'s top heading against the
`plugin.json` version and the `essentials.version` pin. A SKIPPED `render-check` fails it: a release needs exit 0,
not 3. It ends with what no script can check:

1. The latest run of `plugin-scaffold-scheduled.yml` green (start it by hand if the ref has not had a nightly run),
   and the frontend legs above.
2. `claude plugin validate`: one warning is expected and by design — this `CLAUDE.md` not being loaded as plugin
   context (it is the maintainers' file).
3. The eval report: read every case under the threshold before deciding; one red run of three is noise until it
   repeats.
4. `CHANGELOG.md` names what the release adds or changes under a heading with the new `plugin.json`
   version, and the Essentials release it targets (the `essentials.version` pin).
