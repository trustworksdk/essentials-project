# /essentials:slice-enrich and per-application Atlas pages — design

Status: design, agreed with the maintainer 2026-10-08; shipping in plugin 0.60.0-7 beside the Slice Atlas
(`docs/slice-atlas-design.md`).

## 1. Why

Running the Slice Atlas on this repository's own examples showed two gaps.

1. **A folder can hold several applications.** `examples/essentials-spring-examples/` holds three
   (`postgresql-cqrs`, `mongodb-inbox-outbox`, `postgresql-inbox-outbox`) that reuse the slice ids
   `shipping.*`. One page over the parent reports 12 false `map-duplicate-id` errors; one page per
   application reports none. Slice ids are unique per application, not per folder.
2. **Older manifests lack what the Atlas needs.** No example declares `flow:` or `scenarios:`, so
   trading-demo derives 26 processes for 34 slices — nearly one per command — and every board shows
   invariants instead of scenarios. Declaring `flow:` is the fix (decided: the derivation stays as
   designed). But `--fix-manifests` never writes a human-owned field, so nothing in the plugin helps a
   project with manifests from before schema 1.4 catch up.

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| E1 | Multi-application folder | One page per application plus an index page. `atlas` refuses a multi-application root without `--per-app` |
| E2 | Derivation without `flow:` | Unchanged. The fix is declaring `flow:`, which slice-enrich proposes |
| E3 | Command shape | New `/essentials:slice-enrich`. `slice-check` stays the audit; the Atlas Copy prompt's `author` group points here |
| E4 | Scenario evidence | Tests first: every drafted scenario comes from an existing test and links to it with `test:`. A slice with no mapped test gets a suggestion, not a draft |
| E5 | Fields | `flow`, `scenarios`, `summary`, `invariants` (with `enforcedBy`), `tier`, plus the ones with code evidence: `projections[].consistency`, `featureFlag`, `maps` (translations) |
| E6 | Writing | Confirm per group (journey, then slice): yes / edit / skip. Writes only absent fields; stamps `schemaVersion: "1.4"` when it writes `flow` or `scenarios`. `--dry-run` writes nothing |
| E7 | Example fixes | On this branch (`feat/slice-atlas`), after the plugin work |

Never drafted, because the code holds no evidence for them: `notes`, `forbidden`, `sla`, `statutory`,
`observability`, `profile`, `stability`, `characterization` (owned by an external tool), `supersedes`,
`owner`, `status`. They are reported as "not inferable" when absent, once per project, never per slice.

## 3. The law this changes, and the one it keeps

`references/slice/manifest-reconciliation.md` §1 says human-owned fields are never overwritten, that an
absent one may be backfilled once (`tier`), and that `summary`, `invariants[].text`, `flow` and
`scenarios` are **not** backfillable because "a tool inventing content would only corrupt" them.

slice-enrich keeps the first two rules and narrows the third: **a model may draft an absent human-owned
field from evidence in the code or tests, and a person writes it by accepting the draft.** Three things
make that different from inventing content:

- **Evidence or nothing.** Each draft cites where it came from: a test method, a Javadoc line, a guard
  clause, an annotation, an edge of the cause-and-effect graph. No evidence, no draft.
- **Nothing is written without a yes**, per journey and per slice. A skipped field stays absent.
- **Never over a value.** A field that is present — even empty-looking — is the human's; enrich does not
  propose for it. (Exception: `summary` that is exactly a template placeholder, see §5.4.)

`--fix-manifests` is unchanged: it still never writes `flow`, `scenarios` or any human-owned field.
A manifest whose `generator` is not `essentials` belongs to its generator (the SDD plugin writes `flow`
and `scenarios` itself, `references/slice/manifest-guide.md` §3/§5) and is skipped with a note.

## 4. Per-application pages (E1)

### 4.1 What an application is

A **build root** is a directory holding `pom.xml`, `build.gradle` or `build.gradle.kts`. Each
`slice.yaml` belongs to the nearest build root above it, at or below the scanned root. The applications
are the build roots that own at least one manifest. A parent `pom.xml` that only aggregates modules owns
no manifest and is not an application. A manifest above every build root belongs to the scanned root.

Application id: the build root's directory name; when two collide, its path relative to the scanned
root with `/` replaced by `-`. Ordered by relative path.

### 4.2 Scripts

```
slice-index.py apps ROOT                      → JSON [{"id", "root" (relative to ROOT), "slices"}]; exit 3 with no manifest
slice-index.py atlas ROOT --out FILE …        → as today when ROOT holds one application;
                                                exit 2 naming the applications when it holds several:
                                                "ROOT holds 3 applications (a, b, c), each its own slice-id namespace:
                                                 pass --per-app DIR for one page each"
slice-index.py atlas ROOT --per-app DIR …     → DIR/<id>.html per application + DIR/index.html; one summary
                                                line per page, then the index line. Exit: 2 when any page
                                                could not be written, else 1 when any manifest did not parse, else 0
slice-index.py map ROOT --per-app DIR         → DIR/<id>.json per application (CI baselines for --before-map-dir)
slice-index.py atlas ROOT --per-app DIR --before-map-dir BDIR
                                              → each page diffs against BDIR/<id>.json when present
```

`--out` and `--per-app` are mutually exclusive. `map ROOT` without `--per-app` keeps its behaviour on a
multi-application root (one map; the duplicate ids it reports are true of that one map), so
`/essentials:slice-map` is unaffected.

`--source-facts` / `--source-check` are produced once at ROOT; each page uses the facts under its
application's root, with paths made relative to that root exactly as a run of `slice-source.py` at the
application root would have written them. `--before-ref REF` reads each application's subtree from REF.
`--bc`, `--lens`, `--focus` apply to every page that has the context or target; a page without it opens
as default. A page's `meta` gains `app: {id, root, index: "index.html"}` and the page header links back to
the index.

### 4.3 The index page

`references/slice/atlas/index.html`, self-contained like the Atlas, inlined by the same inliner. It takes
the brand tokens from `atlas.html` — the inliner copies the token block between `/* __TOKENS_BEGIN__ */`
and `/* __TOKENS_END__ */` markers in `atlas.html` into the index, so the palette exists once. One row per
application: name and root, slices · contexts · processes, issues per level (dots as on the Atlas),
"map incomplete" when a manifest did not parse, and with a baseline the diff summary (+ − ~ slices, new and
fixed issues); the name links to the page. Light and dark as the Atlas (`prefers-color-scheme` plus
`data-theme` from the hash).

### 4.4 The command

`commands/slice-map-html.md`: Step 1 runs `slice-index.py apps`; with several applications Step 2 proposes
the directory `target/slice-map/` (`build/slice-map/` for Gradle) instead of a file, Step 4 passes
`--per-app`, and Step 5 prints every summary line and the index path.

## 5. /essentials:slice-enrich (E3–E6)

### 5.1 Pipeline

```
slice-lint.py  →  slice-source.py --json  →  slice-enrich.py propose  →  model drafts, user confirms
               →  slice-enrich.py apply   →  slice-lint.py
```

`scripts/slice-enrich.py` (Python ≥ 3.11, PEP 723, `uv run --script`, pyyaml) computes every candidate
and all the evidence. The model's job is the prose a script cannot write (a scenario's name and the steps a
script could not extract, a summary sentence, a journey name) and the conversation. The script writes;
the model never edits a `slice.yaml` by hand.

```
slice-enrich.py propose ROOT --source-facts FILE [--bc BC] [--fields f,…] [--json]
    exit 0 proposals written (possibly none) · 2 could not run · 3 no slice.yaml under ROOT
slice-enrich.py apply ROOT ACCEPTED.json [--dry-run]
    exit 0 written (or would be) · 1 some entries refused (reported; the rest written) · 2 could not run
```

`propose` reuses `slice-source.py`'s lexer (comment and string stripping, import resolution) by importing
it with importlib, as `slice-index.py` imports `slice_atlas`; it does not duplicate it. New syntactic
facts live in `slice-enrich.py`, so `slice-source.py`'s goldens do not move.

### 5.2 Proposal JSON (contract P1)

```json
{
  "schema": "slice-enrich/1",
  "root": "examples/essentials-trading-demo",
  "fields": ["flow", "scenarios", "summary", "invariants", "tier", "consistency", "featureFlag", "maps"],
  "journeys": [
    {"id": "j1", "suggestedName": "place-trade", "declared": null,
     "slices": ["brokerage.place_trade", "brokerage.execute_trade", "brokerage.trade_list"],
     "entries": ["brokerage.place_trade"], "bcs": ["brokerage"],
     "evidence": [{"from": "brokerage.place_trade", "to": "brokerage.execute_trade", "via": "TradePlaced", "kind": "feeds"}]}
  ],
  "slices": [
    {"id": "brokerage.place_trade", "path": "src/main/java/…/place_trade/slice.yaml", "kind": "command",
     "bc": "brokerage", "absent": ["flow", "scenarios"],
     "proposals": {
       "flow": {"journeys": ["j1"]},
       "scenarios": {"tests": [
         {"class": "PlaceTradeDeciderTest", "method": "placesATrade", "path": "src/test/java/…", "line": 41,
          "displayName": null,
          "extracted": {"given": [], "when": {"command": "PlaceTrade", "with": {"quantity": 10}},
                        "then": [{"event": "TradePlaced"}]}}]},
       "summary": {"draft": "Places a trade for an account.", "evidence": [{"path": "…/PlaceTrade.java", "line": 12, "text": "/** Places a trade …"}]},
       "invariants": [{"text": "Quantity must be positive", "enforcedBy": "PlaceTradeDecider#handle",
                       "evidence": {"path": "…", "line": 30, "text": "FailFast.requireTrue(cmd.quantity() > 0, \"Quantity must be positive\")"}}],
       "tier": {"value": "cqrs-es", "from": "…the value resolved for the owning BC (slice-check --adopt-tier's rule)"},
       "consistency": [{"projection": "TradeListView", "value": "eventual", "evidence": {"path": "…", "line": 18, "text": "extends ViewEventProcessor"}}],
       "featureFlag": {"value": {"property": "trading.settlement.enabled", "havingValue": "true"}, "evidence": {…}},
       "maps": {"evidence": [{"path": "…", "line": 55, "text": "…"}]}
     }}
  ],
  "skipped": [{"path": "…/slice.yaml", "why": "generator: sdd — its generator owns flow and scenarios"},
              {"path": "…/slice.yaml", "why": "does not parse — /essentials:slice-check --fix-manifests first"}],
  "notInferable": ["sla", "statutory", "observability", "profile", "stability", "notes", "forbidden"]
}
```

Deterministic: same input, same bytes (sorted keys where order carries no meaning; journeys and slices by
id). A key appears in `proposals` only when the field is absent and the script found evidence; a field
absent without evidence goes in the slice's `absent` list only. One exception: absent `scenarios` with no
mapped test gives `{"tests": [], "unmapped": true}`, so the command can suggest the test the slice lacks.
Also emitted: top-level `ambiguousTests` (a test class matching several slices equally), a journey's
`alsoDeclared` (when members declare several flows), and evidence kind `projects` (event → view) beside
`feeds` and `dispatches`. `extracted` is already in the manifest's scenario shape.

### 5.3 Journeys (`flow`)

Graph over parsed, essentials-generated slices: an edge A → B when A publishes an event B consumes
(`consumes` ∪ `projections[].from`), and when A dispatches a command B handles. **A journey is a connected
component of the non-view slices** under those edges; each view joins every journey that publishes an
event it projects (so a view may get a list). Entries: slices with an endpoint, a schedule or an inbound
translation, and no edge into them from inside the journey; else the lowest causal rank (`slice-index.py`
ROLE_FLOOR ranks).

`suggestedName`: when a member already declares `flow:`, that name (the journey extends a declared flow;
`declared` holds it — the declared members are not proposed again); else the entry slice's name in
kebab case (`place_trade` → `place-trade`), lowest id when several. Names match the schema's flow pattern
`^[a-z0-9][a-z0-9_-]*$`; the model may rename, the user decides.

This is the "merge derived journeys" heuristic the Atlas does **not** use for drawing (E2): here it only
proposes, and a person accepts or splits it.

### 5.4 The other fields

| Field | Evidence, in order | Draft |
|---|---|---|
| `scenarios` | Tests mapped to the slice: (1) a test class whose package equals the slice's package or a subpackage of it (`src/test/…` mirroring `src/main/…`); (2) a test class whose name starts with a type declared in the slice's directory. A test class matching several slices goes to the one with the longest package match, then none (reported as ambiguous). Uses `slice-source.py`'s `tests` index | Per test method: `name` from `@DisplayName`, else the method name humanised; `test: Class#method`. `extracted` from a `GivenWhenThenScenario` chain (the API in `LLM/LLM-eventsourced-aggregates.md` § Testing with GivenWhenThenScenario — **verify every method name there; never invent one**): `given(…)` events, `when(…)` command, `then(…)`/expected events, a failing expectation → `{error: <Exception>}`; `with` only when the constructor arguments are literals and their count equals the payload's field count in the facts (record order). Anything not extractable is `null`, and the model reads that test method — only that method — to draft it |
| `summary` | Javadoc/KDoc first sentence of the slice's main type (the type named after the slice, else its decider/handler/projector), else the first paragraph of the slice's `CLAUDE.md` | That sentence, ≤ 160 characters. Absent also means: the key is missing, empty, or exactly a `render-slice.py` template placeholder (look the placeholders up in `references/slice/templates/`) |
| `invariants` | Guard clauses in the slice's own source: `FailFast.requireTrue/requireFalse(…, "msg")`, `throw new X("msg")` inside a decider/handler/aggregate method, Kotlin `require(…) { "msg" }` / `check(…) { "msg" }`. **Not** `requireNonNull` / null guards — a null check is plumbing, not a business rule | `text` = the message, `enforcedBy` = `Class#method`. At most 10 per slice, in source order |
| `tier` | The value resolved for the owning BC, by exactly the rule of `slice-check --adopt-tier` (`commands/slice-check.md` Step 4) | That value. `--adopt-tier` stays and does the same thing; slice-enrich includes it so one run catches a manifest up |
| `consistency` | The projector's processor type: `InTransactionEventProcessor` → `strong`; `EventProcessor` / `ViewEventProcessor` → `eventual` | Per projection without one |
| `featureFlag` | `@ConditionalOnProperty` on a type in the slice's directory | The object shape `manifest-guide.md` §3 gives for `featureFlag`; when the guide gives none, propose `{property, havingValue}` and say so in the docs |
| `maps` | A translation's mapping code (methods turning an external payload into a command/event) | Evidence lines only; the model drafts `{from, to}` pairs from them |

### 5.5 Apply (contract P2)

`apply` takes the **accepted** subset — the proposal JSON with each slice's `proposals` replaced by
`accept: {field: value}` holding the final values the user approved, plus `journeys[].name` for accepted
journeys. It writes per manifest, only fields still absent: a field that became present since `propose`
is refused for that manifest (exit 1) and the manifest is left untouched. Insertion is a text edit that
preserves every existing byte: new top-level keys go before `tests:` when it exists, else at the end, in
block style; strings are quoted when YAML needs it and **every `path` is quoted** (the plugin's quoting
invariant). `consistency` is inserted inside the matching `projections[]` item; when that item is a flow
mapping the projection is refused rather than rewritten. Stamps `schemaVersion: "1.4"` (rewriting the
existing value) when it writes `flow` or `scenarios`. Before writing a file it parses the result and
validates it against `references/slice/slice-yaml.schema.json`; a result that fails is refused, not
written. `--dry-run` prints the unified diff per file and writes nothing.

### 5.6 The command

`commands/slice-enrich.md`, `/essentials:slice-enrich [<path>] [--bc <name>] [--fields <list>] [--dry-run]`:

1. **Terrain** — `slice-map.md` Step 1; no manifest → redirect to `/essentials:slice-discover`.
   Doctor (`--for slice`).
2. **Lint first** — `slice-lint.py`: a manifest that does not parse is skipped and named, with
   `/essentials:slice-check --fix-manifests` as the repair. Check the project's installed schema copy knows
   1.4 (`manifest-guide.md` §3): when it does not, offer `/essentials:upgrade` Group A2 first, because the
   pre-commit hook would reject the written manifests.
3. **Propose** — `slice-source.py --json` into a temp directory, then `slice-enrich.py propose`.
4. **Journeys** — show each journey: members in causal order, the evidence edges, the suggested name;
   ask yes / rename / split / skip (AskUserQuestion, at most four per call, grouped by context).
5. **Slices** — per context, per slice: scenarios drafted from `extracted`, completing the `null` parts
   from that test method's body only; summary, invariants, the rest from their evidence. Show each draft
   with its evidence; yes / edit / skip per slice.
6. **Write** — the accepted set to a temp file, `slice-enrich.py apply`, then `slice-lint.py` again.
7. **Report** — fields written per manifest, skipped manifests and why, the not-inferable list once, and
   "run `/essentials:slice-map-html` to see the journeys and scenarios".

Never edits source or tests; never writes a present field; never proposes without evidence; never drafts a
scenario from code alone (E4). The Atlas Copy prompt's `author` group names
`/essentials:slice-enrich --bc <bc>`; the `flow-undeclared` and `scenario-none` issue texts name it too.

## 6. Tests

- `tests/fixtures/atlas-multi/`: two applications (each its own `pom.xml`, no Java sources needed beyond
  what `slice-source.py` reads) sharing slice ids. `test_slice_atlas.py`: `apps`, the exit-2 refusal,
  `--per-app` pages and index, a per-app diff.
- `tests/fixtures/enrich/`: a Java project slice tree with manifests lacking the fields, and sources and
  tests carrying the evidence. Traps as well as findings: a `requireNonNull` guard (no invariant), a test
  class that names a type of another slice, a manifest with `generator: sdd`, a manifest that does not parse,
  a manifest that already declares `flow:` (journey extends it), a present-but-empty field, a projection in
  flow style (consistency refused). No answer hints in names or comments (`essentials-plugin/CLAUDE.md`).
- `tests/scripts/test_slice_enrich.py` and goldens `tests/slice-enrich/golden/`: `propose` byte-diffed;
  `apply` writes only absent fields, preserves bytes and comments, quotes paths, stamps 1.4, refuses a field
  that became present, refuses an invalid result, `--dry-run` writes nothing, idempotent on a second run.
- An eval case for the command's judgement (`evals/`), its grader generated from the fixture's
  `expected.yaml` by `evals/build.py`.

## 7. Out of scope

- Changing how the Atlas derives processes (E2).
- Drafting fields with no evidence (§2 list).
- Editing source or tests, writing a test for a scenario that has none.
- Manifests of another generator.
