# The integration contract — entry points for another plugin's agent

This plugin's commands are written for a person: they ask with `AskUserQuestion` and report in prose.
Another plugin's agent cannot answer a question, so a few entry points also have a deterministic face
that agent can drive. The sdd plugin is its first consumer (its side is sdd's
`references/essentials-contract.md`), but nothing here is sdd-specific. Every command and skill keeps
working unchanged for a person who runs it by hand; each mode below is opt-in by flag.

This file is the contract. `capabilities.json` at the plugin root advertises it, and
`tests/scripts/test_capabilities.py` checks every value in that file against the code.

## 1. `capabilities.json`

A static file, read without running anything of ours (`jq`). Change it in the same commit as any
entry point it names.

| Key | Meaning |
|---|---|
| `contractVersion` | A JSON number. `1` grows only by addition; a breaking change to anything in this file is `2`, announced in `CHANGELOG.md`. A consumer that knows only `1` must use no capability on any other value. |
| `python` | The minimum interpreter every `entry` needs, as `"3.11"`. |
| `capabilities` | One object per capability id. A listed id is implemented as this file describes; an absent one is not. `since` is the first plugin version that has it, informational. |
| `declined` | Capability id → one-line reason, for a capability this plugin decided never to offer. Empty today. |

The plugin's version is `.claude-plugin/plugin.json`'s and is deliberately not repeated. Versions order
as `X.Y.Z` < `X.Y.Z-1` < `X.Y.Z-2` < `X.Y.(Z+1)`: a `-N` suffix is a later plugin-only build of the
same Essentials release, not a pre-release, and `N` compares as a number.

| Id | Section |
|---|---|
| `init.preset` | §3 |
| `slice.render.agent` | §2 |
| `slice.render.tests-none` | §2.3 |
| `slice.render.manifest` | §2.4 |
| `slice.render.report` | §2.5 |
| `schema.sdd-keys` | §4 |

`slice.lane.cqrs` (a lane for state-stored CQRS rendering `tier: cqrs`) is neither offered nor
declined: it is not designed yet.

## 2. `slice.render.agent` — `scripts/render-slice.py render`, interface 1

The renderer the four kind skills use (`slice/slice-authoring.md` §4b). Interface `1` is the options
and report fields below. Within it an option or a report field is only ever added; removing or
renaming one, or changing what an exit code means, is interface `2` (`INTERFACE` in the script).

```text
python3 <plugin>/scripts/render-slice.py render --lang java|kotlin
    --kind command|view|automation|translation --lane decider|aggregate|service-entity
    --main-root DIR --test-root DIR [--project-root DIR] [--new-bc] [--with-state] [--wire]
    [--tests stub|none] [--manifest FILE] [--inputs FILE.json] [--set name=value ...] [--dry-run] [--json]
```

### 2.1 Inputs

The placeholder names of `slice/slice-authoring.md` §4, as `--set name=value`. Only the elicited ones
are passed; the rest are derived (`Bc`, `Slice`, `View`, `ExternalSystem`, `aggregate`, `entity`,
`apiPath` = `/api/<bc>`, `owner` = `<bc>-team`, and `tier` from the lane — never supplied differently).
`render-slice.py families --json` lists the placeholders every template family uses.

| Kind | Every lane | Also |
|---|---|---|
| command | `packagePath`, `bc`, `slice`, `Command`, `Event`, `Aggregate` | `AggregateType` with `--new-bc` on decider and aggregate; `Aggregates` on aggregate |
| view | `packagePath`, `bc`, `view` (the slice id), `Aggregate` | `Event` and `AggregateType` on decider and aggregate |
| automation | `packagePath`, `bc`, `slice`, `Event`, `AggregateType` | — (not on service-entity) |
| translation | `packagePath`, `bc`, `externalSystem` (the slice id), `ExternalEvent`, `Event`, `AggregateType` | — (not on service-entity) |

`AggregateType` and `Aggregates` are plurals and are never derived from `Aggregate`. Rendering into an
**existing** BC reads `AggregateType`, `Aggregates`, `Aggregate` and `Entity` from its scaffold when
they are not passed and the BC names exactly one of each; a **new** BC (`--new-bc`) has nothing to
read, so they are passed. `--new-bc` is accepted with `--kind command` only.

### 2.2 Output and exit codes

- `--json` prints exactly one JSON document on stdout. Diagnostics go to stderr.
- `0` — rendered.
- `2` — nothing was rendered and nothing is left behind, wiring included. The first stderr line starts
  `render-slice:` and a space, and says why: a bad or missing input, a refused combination, an existing target, a
  manifest that contradicts the flags. A failure part-way through the writes or the wiring edits
  restores every file it created or edited before exiting `2`.
- Any other code is a crash. The render restores what it touched before the error propagates.

`render --help` names every option, so a probe can check for one before using it.

### 2.3 `--tests stub|none` (`slice.render.tests-none`)

`stub`, the default, also writes each family's stub tests under `--test-root`. `none` writes nothing
under `--test-root` and stamps every entry of the manifest's `tests:` block `present: false`.
Production code and wiring are identical in both modes.

### 2.4 `--manifest FILE` (`slice.render.manifest`)

Renders from a `slice.yaml` that someone wrote before any code, then updates it in place.

- **Where.** `FILE` must be the slice's own manifest path,
  `<main-root>/<packagePath dirs>/<bc>/<use_cases|views|automations|external_systems>/<slice-id>/slice.yaml`.
  The slice directory may already exist holding only that `slice.yaml` and a `CLAUDE.md`. With
  `--new-bc` the BC directory may already exist holding only orientation files: its own `CLAUDE.md`,
  and slice directories holding only a `slice.yaml` and a `CLAUDE.md`. Any other file there is exit 2.
  Without `--manifest` an existing slice or BC directory is still refused.
- **Identity must agree.** `kind`, `language` and `lane` (when present) must equal `--kind`, `--lang`
  and `--lane`. `slice` must be the qualified `<bc>.<slice-id>` and agree with `--set bc=` and the
  slice-id input. A disagreement is exit 2, never a correction: the manifest is in the wrong place or
  describes another slice.
- **Inputs.** A value `--set` does not supply is taken from the manifest:

  | Kind | From the manifest |
  |---|---|
  | command | `Command` ← `handles[0]`, `Event` ← `publishes[0]`, `Aggregate` ← `writes[0]` (service-entity: `Entity` too), `apiPath` ← `endpoints[0].path`, `owner` |
  | view | `AggregateType` ← `projections[0].aggregateTypes[0]`, `Event` ← `projections[0].from[0]`, `apiPath` ← `endpoints[0].path`, `owner` |
  | automation | `Event` ← `consumes[0]`, `owner` |
  | translation | `externalSystem`, `owner` |

  An explicit `--set` wins. The report's `inputSources` says where each input came from (`set`,
  `manifest`, `project`, `derived`).
- **The merge.** The file is merged block by block at the top level, so a key the render does not own
  keeps its exact bytes, comments and position:
  - **Derived keys** — `schemaVersion`, `slice`, `kind`, `bc`, `generator`, `language`, `tier`,
    `lane`, `tests` — are rewritten to the rendered value when they differ. `generator` becomes
    `essentials`, `tier` follows the lane, and `schemaVersion` is never lowered.
  - **Input-bearing keys** — those the table above reads, plus `owner` — are kept as written while
    they still name the value the render used, and replaced by the rendered block otherwise (only an
    explicit `--set` can make them disagree).
  - **A key the template has and the manifest lacks** (`reads`, `invariants`, `forbidden`, …) is
    appended.
  - **Every other key** — `flow`, `scenarios`, `requirements`, `summary`, `status`, `notes`, `x-*`,
    and any key a newer schema adds — is kept byte for byte.
- **Kept files.** An existing `CLAUDE.md` in the slice directory, and with `--new-bc` the BC's own
  `CLAUDE.md`, are left exactly as they are and listed in `kept`.
- **What it can read.** Only the blocks named above are parsed, by a reader for the YAML subset
  manifests use: block mappings and sequences, and one-line flow values. Anything else in one of those
  blocks (a multi-line flow value, an anchor, a block scalar) is exit 2 naming the key; pass its inputs
  with `--set`.

### 2.5 The `--json` report (`slice.render.report`, `reportVersion` 1)

| Field | Meaning |
|---|---|
| `interface`, `reportVersion` | `1` and `1` |
| `essentialsVersion` | The Essentials release the templates target (the `essentials.version` pin), not the plugin version |
| `language`, `kind`, `lane`, `tier`, `families` | What was rendered. `tier` is the manifest's `architectureTier` value derived from the lane |
| `slice` | The qualified id, `<bc>.<slice-id>` |
| `tests` | `stub` or `none` |
| `inputs`, `inputSources` | Every placeholder value, and where each came from |
| `written` | Every file the render wrote, created or rewritten (the merged manifest included), relative to `--project-root` |
| `files[] {path, role}` | The same files with a role: `main`, `test`, `manifest`, `doc` |
| `kept` | Orientation files a `--manifest` render left untouched |
| `manifest {path, source, filled?, corrected?}` | The slice's manifest; `source` is `rendered` or `merged`, and a merge lists the keys it added and rewrote |
| `wiring[] {path, edit, status, reason?}` | Each wiring edit: `applied` (made by this render), `present` (already there; nothing edited) or `manual` (the anchor is gone, or the decider-configurator count is not one; `reason` says which — nothing was edited) |
| `todos` | `path:line: text` of every `TODO` the templates left |
| `behaviours[] {id, status, summary, where}` | What the rendered code already does. `status` is `implemented` when a test of it passes against the untouched skeleton, `placeholder` when it is left to the developer. `id` is stable and names the behaviour, not a class; `where` is `path#member` |
| `stubTests[] {path, covers[]}` | Every stub test file written, each with the behaviour ids it exercises. Empty under `--tests none` |

Paths are relative to `--project-root`. `behaviours` and `stubTests` come from
`slice/templates/behaviours.json`; `tests/scripts/test_behaviours_catalog.py` keeps it complete for
every family and every stub test, and fails an `implemented` behaviour that no test under
`tests/slice-compile/behaviours/` names. Those tests run in the plugin's CI slices build against the
rendered, untouched skeleton of every golden composition (`tests/slice-golden/compositions.json`)
that renders the family, the `--manifest` render's included, except a composition
`tests/slice-compile/overlay.py` lists in `BEHAVIOUR_EXEMPT` with the reason and a covering
composition that renders and checks every family it renders: today the two two-bounded-context
compositions, which run a boot check instead. `test_behaviours_catalog.py` fails a
composition that leaves an `implemented` behaviour of a family it renders unchecked and is not
exempt (`tests/slice-compile/README.md` § Behaviour checks).

## 3. `init.preset` — `/essentials:init --preset FILE --report FILE`

The procedure is `commands/init.md` § Preset and report; `scripts/init-report.py` decides the preset
and writes the report, never the model. Without arguments the command asks every question, as always.
The command stays model-invocable, so another plugin runs it through the Skill tool with the argument
string `--preset <abs file> --report <abs file>`.

- **Preset keys** — `capabilities.json` `init.preset.presetKeys`: the ten `answers.json` keys with
  `init-render.py`'s own value sets, plus `targetMode` (`current` | `subdirectory`, Step 1's two
  options) and `targetDir` (absolute or relative to the workspace, created when absent; it supersedes
  `targetMode`, which is then reported as ignored). A usable key is not asked again. Step 7's
  confirmation stays when any of its four keys was asked. A value outside its set is asked as if
  absent and an unknown key is ignored; both are reported in `ignoredPreset`, and an unreadable file
  is reported under the key `(preset)`. A preset never stops the command.
- **Report** — written at Step 14 and whenever the command stops early:
  `contractVersion` (`1`), `essentialsVersion` (the pin), `pluginVersion`, `status` (`rendered` once
  Step 11 has rendered, even when lint or the build then fails — `stackLint`/`smokeBuild` say so;
  `stopped` when nothing was rendered, with `stoppedAt`), `target` (absolute, or `null` when a stop
  came before it was known), `workspace`, `answers`, `preset` and `asked` (key lists), `ignoredPreset`
  (key → reason), `stackLint` (`passed` | `failed` | `not-run`), `smokeBuild` (`passed` |
  `compiled-only` | `failed` | `not-run`).
- A `targetDir` other than the workspace or `<workspace>/<artifactId>` gets no workspace pointer file:
  that template names `./<artifactId>/`.

## 4. `schema.sdd-keys` — manifest schema 1.4

`slice/slice-yaml.schema.json` declares `x-manifestVersion` (`"1.4"`) and accepts the optional `flow`,
`scenarios` and `requirements`, plus `x-` extension keys on every object. So do `scripts/slice-lint.py`,
the copy `/essentials:init` installs into a project as `scripts/slice-yaml.schema.json`, and the refresh
`/essentials:upgrade` offers for an older copy (Group A2). Every copy keeps the root `x-manifestVersion`.

## 5. The marks a rendered project carries

A consumer decides "this project uses Essentials" from what init and the renderer leave behind. A
change to any of these is a contract change, even when no command's surface moves:

- the stamp `<!-- essentials-init: essentials <ver> -->` in the rendered `CLAUDE.md`, `<ver>` starting
  with a digit (the project root, or the subdirectory init rendered into);
- `.claude/rules/essentials-slices.md`, beside that `CLAUDE.md`;
- a root `generator: essentials` line in a rendered `slice.yaml`;
- `dk.trustworks.essentials` in the build file;
- the lint gate's schema copy at `scripts/slice-yaml.schema.json`, when the user installed the gate;
- the slice layout `src/main/<java|kotlin>/<package dirs>/<bc>/<use_cases|views|automations|external_systems>/<slice-id>/`,
  and an `aggregates/` directory in a BC for the aggregate lane, `entities/` for service-entity, neither
  for decider (`slice/slice-authoring.md` §1b).
