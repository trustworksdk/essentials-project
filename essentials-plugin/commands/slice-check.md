---
name: slice-check
description: >-
  Audit an existing Trustworks Essentials project against the slice-design law — layout, manifests,
  anti-god-class rules, wiring, processor handler shapes and boundary violations; manifest parsing,
  schema and sole-ownership checks run deterministically in scripts/slice-lint.py. Read-only by
  default: --fix-manifests regenerates orientation artifacts, --adopt-tier backfills the one field
  reconciliation will not write, --fix-source proposes source fixes one at a time.
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
| _(default)_ | Nothing. Report only. |
| `--fix-manifests` | `slice.yaml`, per-slice `CLAUDE.md`, per-BC `CLAUDE.md`, and the `.claude/rules` pointer. **Never source code.** |
| `--adopt-tier` | One field, one time: writes `tier` into manifests that have none. Never overwrites one. |
| `--fix-source` | Additionally *proposes* structural source fixes. Each is shown as a diff and applied only on explicit per-fix confirmation. |

`--fix-source` **refuses to run on a dirty working tree** (`git status --porcelain` non-empty) so
every applied change is trivially revertable. Say so and stop; do not offer to stash.

## Step 1 — Load the law and the schema

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-yaml.schema.json
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/manifest-guide.md
```

Detect the language per `references/slice/slice-authoring.md` §1.

## Step 1.5 — Run the linter, before reading a single source file

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py <project-root> --require-schema --json
```

This is gates **1, 3 and 4** in their entirety, and they are **not yours to re-derive.** Parsing YAML,
validating against a JSON Schema, and intersecting id sets across manifests are mechanical; a model
eyeballing them produces confident wrong answers, and this command has no business guessing where a
parser gives a exact answer. Take the script's findings verbatim into the report.

Two consequences to honour:

- **If the script cannot run** — no `pyyaml`, no `jsonschema`, exit code 2 — say so at the top of the
  report, name the missing dependency and its `pip install` line, and mark gates 1, 3 and 4 **not
  run**. Do not fall back to reading the manifests yourself and do not report them as passing. An
  unvalidated manifest set reported as clean is the exact failure this whole gate exists to prevent.
- **Every unparseable manifest listed by the script excludes its slice from gates 2, 5–18 as well**,
  because there is nothing to compare the code against. Say which slices those are, once, at the top.
  A slice that silently vanishes from an audit reads as compliant.

## Step 2 — Gates

Run all eighteen. Each finding cites `path:line`.

Gate 14 runs **first in practice** — the write style it detects decides how gates 2, 5, 9, 10, 13,
15 and 17 read for that BC.

**Strip comments before counting anything in source.** Every gate below that counts an annotation, a
keyword or a mapping must parse or comment-strip first, because well-written code *explains itself in
prose* and the prose contains the very tokens being counted. This produced a confident false positive
on three separate gates in one real audit:

| Gate | The naive check | Why it fires wrongly |
|---|---|---|
| 17 | grep the routing marker for `sealed` / `permits` | A good marker carries a javadoc paragraph explaining **why it is deliberately not sealed** — the words appear several times in prose. Every *correct* marker reports as a violation. Parse the type **declaration** |
| 10 | count `@MessageHandler` occurrences and inspect parameters | Class javadoc routinely *names* `@MessageHandler` when explaining the handler strategy, inflating the count and inventing phantom handlers with no parameters |
| 6 | count `@(Get\|Post\|Put\|Patch\|Delete\|Request)Mapping` per API file | A class-level `@RequestMapping` for the base route is standard and counts as a second mapping, so **every** command slice reports 2. Count **method-level** mappings only |

The general rule: a finding derived from a token that also appears in a comment is not a finding until
the comment is gone.

| # | Gate | How |
|---|---|---|
| 1 | **Parseable, then schema-valid** | **Run by `scripts/slice-lint.py` (Step 1.5), not by you.** Two steps, in order. (a) **Every `slice.yaml` parses as YAML.** A file that does not parse is **Blocking** and is reported by name — never skipped, because a manifest no tool can read is invisible to gates 3, 4, 11 and to `/essentials:slice-map`, and "invisible" reads as "compliant". The dominant cause is an **unquoted path containing a brace** inside a flow mapping — `- { method: POST, path: /api/orders/{id}/cancel, auth: user }` — where the `{` opens a nested mapping and the parse dies there. See `manifest-guide.md` §3. (b) The parsed document validates against the schema; required-by-kind fields present. The script also reports a braced path that is unquoted but currently *legal* because it sits in block form — **Should-fix**, because reflowing that entry is a routine edit that turns it into (a). (c) **A projection's `aggregateTypes` value that is used as an event type anywhere else in the project** — **Should-fix**. `aggregateTypes` is the `AggregateType` STREAM a projector subscribes to and `from` is the event types it handles; both are arrays of strings, so a swap validates cleanly and the schema cannot see it. The corpus can: a name that appears in any manifest's `publishes`, `consumes` or `projections[].from` is an event, not a stream. See `manifest-guide.md` §3 |
| 2 | **Layout** | Role directory matches `kind`; no layer directory inside a BC — `controllers/`, `services/`, `repositories/`, `adapters/`, `ports/`, `infrastructure/`, `dto/`, `mappers/`; `use_cases/_shared/` holds only `*State`/`*Evolver`, **and has three or more decider consumers** — see the promotion-bar note below. A `_`-prefixed directory is not a slice: skip it, and report it as **Advisory** if it carries no one-line `CLAUDE.md` saying what it is |
| 3 | **Uniqueness** | `slice:` ids unique across the repo. **Run by `scripts/slice-lint.py`** |
| 4 | **Sole ownership** | Three checks, all **run by `scripts/slice-lint.py`**, all **Blocking**. (a) **One handler per command type** — a name in `handles` appearing in two slices. The command bus permits exactly one handler per command type and throws `MultipleCommandHandlersFoundException` at startup, so this is a boot failure, not a style note. (b) **One writer per read model** — a name in `owns` appearing in two slices (§R4); a deliberate migration twin declares `supersedes` and is gate 13's business. (c) **One bounded context per aggregate** — a name in `writes` written from two BCs; an aggregate is one consistency boundary. **The non-finding matters as much as the findings: several command slices writing the same aggregate inside one BC is the design on every lane and is never reported** — see the note below |
| 5 | **§R1** | A decider file with `when (cmd` / `switch (cmd` over ≥2 command types, or ≥2 `decide[A-Z]` methods |
| 6 | **§R2** | A **command** slice's API file with more than one **method-level** request mapping. A class-level `@RequestMapping` declaring the base route is standard and is **not** one of them — counting it reports every command slice as having two. For a **view** slice, several mappings are legitimate (queries over its own read model) — flag instead: a mapping whose handler reads a repository the slice does not own, and any mapping absent from the manifest's `serves`/`endpoints`. **Compare mappings to endpoints on the route, not on the literal string.** A manifest path may carry query discriminators after a `?` (`"/api/shipping/order-status?status="` for `@GetMapping(params = "status")`, `manifest-guide.md` §3): split there, match the route, then confirm each named parameter is bound in that handler via `params = "…"`, `@RequestParam`, or `queryParam("…")`. Two mappings sharing a route and differing only by `params` are two endpoints and two `serves` names in **one** slice — §R2 working, not an §R2 violation. An optional filter with a default is not a discriminator and needs no entry of its own |
| 7 | **§R3** | An `events/*.{kt,java}` file declaring ≥2 concrete event types |
| 8 | **§R4** | Four checks. (a) An import matching `\.use_cases\.(?!_[a-z0-9_]+)[a-z0-9_]+\.` or `\.views\.[a-z0-9_]+\.` from outside that slice's directory — **except** a command type whose only use is constructing a command passed to `commandBus.send`/`sendAndDontWait`, which §R4 explicitly sanctions. (b) An import matching `\.automations\.[a-z0-9_]+\.` or `\.external_systems\.[a-z0-9_]+\.` from outside that slice. (c) An import of `<bc>.routing.` or `<bc>.config.` from a **different** bounded context — those are BC-private; only `events/` and `types/` are importable across BCs. (d) **Command-type leakage:** any file under `<bc>/events/`, `<bc>/entities/`, or `<bc>/aggregates/` importing a type from `<bc>.use_cases.<slice>.` — **Blocking**, and worst in `events/`, which is importable across BCs |
| 9 | **Wiring** | Every `*Decider` has a matching `@Bean` in its BC's `config/` |
| 10 | **Projection idempotency** | A `@MessageHandler` that writes a versioned read model (`save`/`update` on a `DocumentDbRepository`) takes `OrderedMessage` as its 2nd parameter and passes its order to the write. The parameter is **optional** to the dispatcher — a single-argument handler is invoked normally — so flag it **only** where the handler mutates versioned state, never on handlers with no versioned state (a translation publisher, a stateless automation step) |
| 11 | **Manifest ↔ code** | Both directions. (a) `handles` / `publishes` / `serves` names resolve to types in or adjacent to the slice. (b) **Every event type the slice's handlers actually handle is declared** — the parameter types of its `@MessageHandler` / `@Handler` methods on an `EventProcessor`, `ViewEventProcessor` or `InTransactionEventProcessor`. Look for them in the field that kind uses: **`projections[].from` for a view**, `consumes` for an automation or translation (`manifest-guide.md` §3). An undeclared handled event is **Should-fix** — the code is right and the manifest is stale, so the repair is `--fix-manifests`, never a source edit. This is the most common drift in a mature project: a projector gains one more `@MessageHandler` and the manifest is not touched, so the slice silently reads as reacting to fewer events than it does |
| 12 | **Project-copy freshness** | Two files, and only these two ever leave the plugin. (a) `.claude/rules/essentials-slices.md` exists and its `<!-- essentials-slices-rules: vN -->` stamp is not older than the plugin's — compare the integer after `v` numerically, never as strings (`v10` is newer than `v9`). (b) If the project installed the lint gate (`scripts/slice-lint.py` + `scripts/slice-yaml.schema.json`, `/essentials:init` Step 12.5), neither differs from the plugin's current copy — **Advisory**, and `diff` says it in one line. A project running a stale schema validates against yesterday's contract and reports a clean pass it has not earned. (c) If the gate is **absent entirely** — the case for every project scaffolded before it shipped — say so once as **Advisory** and name `/essentials:upgrade`, which offers the install. Do not offer it here: this is a slice audit, and clause (b) staying silent on a project that never had the gate is exactly how a shipped capability never reaches an existing project. **Offer** a refresh for (a) and (b); never overwrite silently |
| 13 | **View duplication / migration twins** | Two view slices whose `projections.from` sets and read-model shapes coincide. Resolve against `supersedes` + `status` — see the table below. Also flag a `supersedes` pointing at a slice that no longer exists (stale link — drop the field) |
| 14 | **Write-style lane (§R5)** | Per BC, detect one of three lanes — see the detection table below. Report a BC holding **any two** of per-slice deciders, `<bc>/aggregates/`, and `<bc>/entities/` as **Blocking** — two write designs over one consistency boundary. The detected lane selects which of gates 2, 4, 5, 9, 10, 13, 15 and 17 apply and how |
| 15 | **Write-repository purity and placement** (service-entity lane only) | Identify the BC's write repositories **by mutation surface, not by path and not by base interface**: any type through which an entity declared in `<bc>/entities/` is persisted — it declares or inherits `save`/`delete`/`update`/`saveAll`. Spring Data is one vehicle (`CrudRepository`/`JpaRepository`/`MongoRepository`, or a bare `Repository` declaring the methods itself); Essentials' own document repository, a JDBI DAO, and any other store are others, and **the gate must find all of them** (§R5, "identify the write repository by its mutation surface"). Two ways this gate silently stops working: keying on the CRUD-family interfaces alone misses every repository that took gate 18's advice, and keying on Spring Data at all misses every project that uses none — the purity rules below are framework-agnostic and were the valuable part all along. For each: (a) a mutating call (`save`, `delete`, `saveAll`) on it from outside `use_cases/*` — **Blocking**; (b) a finder on it whose only callers are view slices or API handlers — **Should-fix**, the read side served from the write model (§ The read side on this lane); (c) **its file is not in `<bc>/entities/`** — **Should-fix**, it must sit beside the entity it persists (§R5, the `<bc>/entities/` tree). A `repositories/`, `persistence/`, `dao/` or `store/` folder is the common form and gate 2 catches only the first; a repository at the BC root or beside one slice is the same violation and only this check sees it. The fix is a file move, never a rewrite |
| 16 | **Lane hygiene** (service-entity lane only) | A `@RestController` returning an `@Entity`/`@Document` type — **Should-fix**. A public setter writing a field that an invariant method also guards — **Should-fix**, the guard is bypassable. A `use_cases/_shared/` in a service-entity BC — **Should-fix**, there are no evolvers to promote |
| 17 | **Routing ↔ lane** | `<bc>/routing/` is decider-lane-only and **required** there (§ Directory vocabulary). Check **both** directions — see the routing table below. Gate 14 owns lane detection: `routing/` is evidence *about* a BC, never an input to deciding its lane, or the two gates reason in a circle |
| 18 | **Spring Data repository surface** (all lanes) | **Scope check first: if the project declares no Spring Data repository at all, this gate is 100% out of scope — report it as skipped, with the reason, and move on.** Silence here must never read as a pass; a project persisting through Essentials' `DocumentDbRepository` or JDBI legitimately has nothing for this gate to see. Otherwise: every Spring Data repository interface in the project — write repository, view query interface, or a Spring Data-backed read model. (a) It extends anything other than the bare `org.springframework.data.repository.Repository` — `CrudRepository`, `ListCrudRepository`, `PagingAndSortingRepository`, `ListPagingAndSortingRepository`, `JpaRepository`, `MongoRepository`, `ReactiveCrudRepository`, `ReactiveMongoRepository`, `R2dbcRepository` — **Should-fix**; the surface is now everything the framework offers rather than what the slice declared. (b) A method returning the mapped `@Entity`/`@Document` where a closed interface projection belongs — **Should-fix**. (c) A method declaring a **projection** return type but **named after a CRUD base method** — **Blocking**; see the reserved-name list below. Essentials' `DocumentDbRepository`/`DelegatingDocumentDbRepository` is **not** a Spring Data repository and is out of scope for all three (§ Spring Data repository surface) |

**Gate 4 never reads "each aggregate in a `writes:` has exactly one command slice" — that rule is
wrong on every lane.** The decider lane's premise is N command slices per aggregate type, each with
its own decider — `place_order` and `cancel_order` both declare `writes: [Order]`, and adding a
command adds a directory (§R1). The aggregate lane is the same: every command slice in the BC calls
the one aggregate. So is the service-entity lane. All three of this plugin's own fixtures carry two
command slices over one aggregate, and that rule would fail all three. On a mature project it would
fire once per command slice beyond the first, per aggregate — dozens of findings, none of them real,
which is how a report trains people to skip it. `writes` names the **aggregate**, and an aggregate
having many commands is the whole point of slicing it. Never report that.

Gate 2's `_shared/` check is a **promotion-bar** check, not just a contents check. Count the
deciders importing `<bc>.use_cases._shared`:

| Consumers | Verdict |
|---|---|
| 0–1 | **Should-fix** — premature promotion; move `State`+`Evolver` back into the one slice that folds it |
| 2 | **Should-fix**, unless both slices' manifests name the same `invariants[].id` — the sanctioned same-invariant exception (§ The `_shared/` promotion bar) |
| 3+ | Fine. Separately report as **Advisory** any `_shared/State` field read by only one decider — the shared state is drifting toward the union of everyone's needs |

A brand-new BC scaffolded by `/essentials:add-slice` has no `_shared/` at all, by design. Finding one
with a single consumer usually means it was scaffolded by hand from an older template.

Two Essentials-specific checks worth calling out inside gate 10: a projector calling
`repository.update(entity)` with no version argument (CRUD auto-increment masquerading as a
projection), and any use of `Version.of(...)`, which does not exist.

Gate 6 has a **false-positive trap**: a view slice with several `@GetMapping` methods is *not* a
violation — §R2 scopes view slices by the read model they own, not by endpoint count. Count
mappings only for command slices; for views, check ownership and manifest coverage instead.

Gate 6 also covers the **no-adapter rule** (§R2 "The command and the view *are* the contract"):
report a `…Request`/`…Response` type that mirrors the slice's command or read model **field for
field**, and any `…Mapper`/`…Assembler`/`…Converter` class or `toDto()` method sitting between the
API and the slice's own types. A body type holding only the fields the client actually sends — the
id arriving by path or generated server-side — is *assembly* and is not a finding.

Gate 13 covers the inverse of the duplicate case, and it has its own trap: two view slices
projecting the same events into the same read-model shape are usually one slice split by mistake,
but they are also exactly what a **legitimate migration twin** looks like mid-rollout (§ Evolving a
view slice). Read the manifests before reporting:

| Manifests say | Verdict |
|---|---|
| `v2` declares `supersedes: <v1 id>` and `v1` is `status: deprecated` | **Should-fix** — retirement outstanding; name the directory to delete |
| `v2` declares `supersedes` but `v1` is still `status: live` | **Should-fix** — swap the consumers over or drop the twin |
| No `supersedes` link between them | **Blocking** — two slices sharing a read model (§R4) |

Gate 14 exists because **most of this law is lane-independent and a few gates are not.** Never report
the absence of deciders as a violation on a lane that has none — that is the style working as
intended.

**Lane detection, per BC:**

| Evidence | Lane |
|---|---|
| `Decider` / `EventStreamDecider` implementations | decider |
| `<bc>/aggregates/` exists | aggregate |
| `<bc>/entities/` exists **and** state is loaded, mutated and saved in place **and** no `EventStore` / `AggregateType` / `EventOrder` is referenced in the BC | service-entity |
| Any two of the above | **Blocking** — report both signals, name the files, and stop. Do not pick one |
| `<bc>/entities/` but an `EventStore`/`AggregateType` is referenced | **Blocking** — the BC is drifting off the lane; name both signals |

Detection is deliberately **not** a bare filesystem predicate for the service-entity lane. An
`entities/` directory alone is just a package name; the lane is the two decisive criteria in §R5 —
state stored and mutated in place, and no stream to replay. A project with `entities/` and no
Essentials at all is not on this lane and is not this command's business — that is
`/essentials:slice-discover`'s territory.

**The command bus and the `EventBus` are signals, not criteria** (§R5, criteria 3 and 4), and this is
where lane detection would otherwise deadlock. A BC meeting both decisive criteria is on this lane even if it
dispatches through a plain `@Component` handler rather than `commandBus.send`. Classify it, then
report the deviation separately:

| Bus deviation | Verdict |
|---|---|
| The BC's `CLAUDE.md` documents the reason | **Advisory** — record it, do not re-litigate it |
| Undocumented | **Should-fix** — the reason belongs in the BC's `CLAUDE.md` beside the no-history decision |
| The stated reason is *"the bus would make this asynchronous"* or *"we don't want durability"* | **Should-fix**, and quote §R5's dispatch table back: `send(cmd)` blocks and returns the handler's result, and durability attaches only to `sendAndDontWait` on a `DurableLocalCommandBus`. Both premises are false, so the deviation rests on nothing |

Never report the lane as **undetermined** because of the bus. Undetermined means gate 14 found no
deciders, no `aggregates/` and no `entities/` — nothing to classify — not that a BC dispatches
differently than the default.

**Cross-check the detected lane against the declared one.** Manifests carry `lane:` (`decider` |
`aggregate` | `service-entity`) — the write style the slice was scaffolded on. Detection still wins,
because the code is what runs, but a disagreement is a finding in its own right:

| Observation | Verdict |
|---|---|
| Every manifest in the BC declares the detected lane | Fine — say so in one line |
| A manifest declares a lane the BC no longer has | **Should-fix** — the BC migrated write styles and the manifests were not reconciled. `--fix-manifests` repairs it (`lane` is machine-derived) |
| Manifests in one BC declare **two different** lanes | **Blocking** — corroborates gate 14's two-designs-over-one-boundary finding, and names which slices sit on which side. This is the cheapest signal there is for a half-finished migration |
| No manifest declares `lane` | Not a finding — the manifests predate the field. Detection stands alone |

**Do not read `lane` as an input to detection**, for the same reason gate 17 must not read `routing/`:
a declared value is evidence *about* the BC, and letting it decide the lane makes the gate agree with
whatever the manifest claims, including when the manifest is the thing that is wrong.

**Do not confuse `lane` with `tier`.** They are different axes and coincide on one value. A manifest
carrying `tier: aggregate` is a **Should-fix**: `aggregate` is a write style, not an `architectureTier`
value, and the schema requires a reader to treat an unrecognised tier as `custom`, which silently
drops the slice's tier-specific handling.
The repair is `tier: cqrs-es` + `lane: aggregate`.

**Also do not require Spring Data to classify.** Criterion 1 is *state is stored and mutated in
place*, whatever persists it. A BC using Essentials' own document repository or JDBI is on this lane;
what changes is that gate 18 is out of scope and gate 15 must find the write repository by its
mutation surface.

**Gate 5 (§R1) per lane.** On the aggregate and service-entity lanes it applies as a bar rather than
as a `switch` scan:

| Check | Lane | Severity |
|---|---|---|
| A public method that only calls `apply(...)` with no invariant behind it | aggregate | **Advisory** — one is fine; report the *count* when most methods qualify |
| A public method that only assigns fields | service-entity | **Advisory** — same shape: a setter with a better name |
| Method count tracking the BC's command-slice count, with independent invariants | both | **Should-fix** — R1's router as a class; the BC wants decider style |
| A getter or query method whose only caller is an API or a view | both | **Should-fix** — the read side served from the write model |
| A command slice appending events directly instead of through the aggregate | aggregate | **Blocking** — outside the consistency boundary |
| One handler class carrying `@CmdHandler`/`@Handler` methods for ≥2 command types | service-entity | **Should-fix** — R1's router as a handler; it splits into that many slices |
| `aggregates/` in a BC whose sources are `.kt` | aggregate | **Advisory** — the Kotlin module ships no aggregate pattern, so this is the Java family via interop (§R5); confirm it is deliberate |

**The service-entity bar has three false-positive traps, and all must be respected**
(§ The entity's own bar):

- **Accessors whose only callers are the ORM and `toString()` are persistence machinery, not a query
  surface.** Distinguish by *caller*, never by shape. An ORM mandates a no-arg constructor and field
  access; flagging those reproduces the "every getter is a finding" noise that makes audits useless.
- **A write-repository finder used only by the write path** (loading an entity in order to mutate it)
  is the repository doing its job. Gate 15's finding is a finder serving a *read* path.
- **A view slice's query interface is a repository over the same entity, and it belongs where it
  is.** On this lane a view slice owns a narrow read-only `Repository<E, ID>` in
  `views/<view>/`, typed over the BC's entity — that is the sanctioned read shape, not a misplaced
  write repository. Gate 15(c) must therefore key on **mutation**, never on the entity type alone: a
  repository that neither extends a mutating Spring Data interface nor declares `save`/`delete` is a
  read side and is out of scope for every part of gate 15.

**Gate 17 — `routing/` per lane.** The directory is decider-lane-only and required there, so the
check runs in both directions:

| Lane | Observation | Verdict |
|---|---|---|
| decider | No `<bc>/routing/` at all | **Should-fix** — nothing tells the configurator which deciders serve the aggregate type, nor how to extract the stream id from a command. Downgrade to **Advisory** if `config/` supplies a `deciderSupportsAggregateTypeChecker` that is *not* `HandlesCommandsThatInherit(s)FromCommandType` — the checker is a filter and a BC may implement it another way; the marker convention is then unfollowed rather than broken |
| decider | A command type under `use_cases/<slice>/` that does not implement its BC's marker | **Blocking** — the checker will not match it, so that command routes nowhere. This is the failure the directory exists to prevent, and it is silent at startup |
| decider | The marker is `sealed` (Kotlin) or carries `permits` (Java) | **Should-fix** — sealing it means adding a command slice edits an existing file, destroying the open/closed hinge the slice model turns on (`slice-model.md` §4.1) |
| decider | `routing/` holds a class, an enum, or an interface declaring more than the aggregate id | **Should-fix** — it is a marker directory, not a home for shared command shape |
| aggregate | `<bc>/routing/` exists | **Should-fix** — a decider-style vestige. The handler already holds the aggregate id and hands it to `StatefulAggregateRepository`; there is no decider to filter and no resolver to feed |
| service-entity | `<bc>/routing/` exists | **Should-fix** — the command bus routes by command *type* to a handler method. There is no stream to select |
| **undetermined** | Gate 14 detected no lane (no deciders, no `aggregates/`, no `entities/`) | **Skip, and say so.** A freshly scaffolded BC that has not written Slice Zero yet is not a violation, and guessing its lane from `routing/` is the circularity this gate must not introduce |

Two false positives to respect: a BC owning **two** aggregates holds two markers, and that is correct
(`slice-model.md` §4.1) — do not report the second as clutter. And a marker whose id is nullable is
fine: `commandAggregateIdResolver` accepts null for create commands.

**Gate 18(c) — the reserved names.** These are the methods the Spring Data base implementation
(`SimpleJpaRepository`, `SimpleMongoRepository`) owns. A declared method matching one by **name and
parameter types** is captured by that base and never derived as a query — the return type is not part
of the match, so a declared projection type is silently ignored and the caller gets the entity:

| Scope | Reserved |
|---|---|
| All | `findById`, `existsById`, `findAll`, `findAllById`, `count`, `save`, `saveAll`, `delete`, `deleteById`, `deleteAll`, `deleteAllById` |
| JPA | `getById`, `getReferenceById`, `getOne`, `flush`, `saveAndFlush`, `saveAllAndFlush`, `deleteAllInBatch`, `deleteAllByIdInBatch` |
| Mongo | `insert` |

Extending the bare `Repository` marker does **not** protect against this — it removes the inherited
*methods*, not the base *implementation*. The fix is a rename to a name the base does not own
(`findOrderStatusById`), which derives the same query and does project.

**Gate 18 has three false-positive traps:**

- **A reserved name returning the mapped type is not a finding.** `Optional<ShippingOrder>
  findById(String)` on the write repository is the base implementation doing exactly its job. 18(c)
  fires only where the declared return type is a **projection** — that is the whole defect.
- **`DocumentDbRepository` is out of scope, and so is the `@DocumentEntity` read model it stores.**
  It is Essentials' own repository, not Spring Data; it has no interface-projection mechanism, and
  its read model is slice-owned and purpose-built. Reporting 18(b) against a view slice on an
  event-sourced lane would flag the templates this plugin ships.
- **18(a) is a capability finding, not a usage one.** A view slice extending `JpaRepository` is
  Should-fix here; an actual `save`/`delete` call through the write repository from outside
  `use_cases/*` is gate 15(a)'s **Blocking**. Report each once, under its own gate.

**Gate applicability by lane:**

| Gate | decider | aggregate | service-entity |
|---|---|---|---|
| 1, 3, 6, 7, 11, 12 | apply unchanged | apply unchanged | apply unchanged |
| 2 (`_shared/` promotion bar) | promotion bar | promotion bar | **`_shared/` should not exist** — Should-fix, no consumer count |
| 4 (sole ownership) | (a)(b)(c), lane-independent | (a)(b)(c), lane-independent | (a)(b)(c), lane-independent. 4(b) matters **more** here — `save()` is callable from anywhere, so read-model ownership is a convention rather than a framework guarantee |
| 5 (§R1) | `switch` scan | § The aggregate's own bar | § The entity's own bar + the god-handler row |
| 8 | (a)(b)(c)(d) | (a)(b)(c)(d) | (a)(b)(c)(d) |
| 9 (wiring) | `@Bean` per decider | the aggregate's repository bean | **check, do not expect an edit** — `ReactiveHandlersBeanPostProcessor` auto-registers `CommandHandler` beans and Spring Data repositories are scanned. Confirm the handler is a bean in a scanned package and that `reactive-bean-post-processor-enabled` (default `true`) is not switched off; disabling it silently unwires every handler |
| 10 (projection idempotency) | applies | applies | **skipped** — no versioned read model exists to double-apply into |
| 13 (view duplication) | applies | applies | duplication check applies; the `supersedes` **twin machinery does not** — there is no projection to rebuild, so `projections.from` is empty and shape comparison is the only signal |
| 15, 16 | n/a | n/a | apply — gate 15 identifies the write repository by mutation surface, on whatever persistence the BC uses |
| 17 (routing ↔ lane) | `routing/` **required** | `routing/` must be **absent** | `routing/` must be **absent** |
| 18 (repository surface) | applies **only where Spring Data is used** — usually nowhere on this lane, since read models go through `DocumentDbRepository` or JDBI. **Report as skipped where it is nowhere** | same | applies to every **Spring Data** repository in the BC: the write repository *and* each view slice's query interface. A BC persisting through another vehicle skips this gate entirely — say so |

**Migrating a BC between write styles is out of scope for `--fix-source` on all three lanes** — see
Step 4.

## Step 3 — Report

**Open with the linter's result and any manifest it could not parse** — those slices are absent from
every other gate, and an absent slice reads as a compliant one. If the linter could not run at all,
say that first and mark gates 1, 3 and 4 **not run**.

**Then the detected lane, one line per bounded context** — `orders: decider`,
`shipping: service-entity`. Without it a reader cannot tell a gate that passed from one that was
skipped, and gates 10, 13, 15 and 17 are skipped or reshaped per lane.

**Then a `Gates not run` block, before the findings.** A gate can go unrun for three different
reasons and all three must be named, because silence is indistinguishable from a pass:

| Reason | Example |
|---|---|
| **Lane** | gate 10 on the service-entity lane — no versioned read model exists to double-apply into |
| **Subject absent** | gate 18 on a project with no Spring Data repository anywhere. This is a 100% skip and, unreported, looks exactly like a clean pass |
| **Input missing** | gates 2, 5–18 for a slice whose manifest did not parse; gates 1, 3, 4 when the linter could not run |

A gate that never ran is not a finding *or* a pass, and collapsing it into either is the one
reporting error that costs a reader their trust in the whole report.

**`tier` absent is not a defect.** A project that adopted manifests before the field existed will
never gain it, because `tier` is human-owned and `--fix-manifests` will not write over a human-owned
field. Report absence as *"falls back to the project default"*, never as unset by mistake, and
mention `--adopt-tier` (Step 4) once rather than per slice.

Then one table, ordered by severity, using the taxonomy in `rules/slice-design.md`
§ Reporting severities:

- **Blocking** — §R1–§R4 violated, more than one write style in a BC, a command type imported into
  `events/`/`entities/`/`aggregates/`, a decider unwired, a projection writing a versioned read model
  from a `@MessageHandler` that omits `OrderedMessage`, a read model queried inside a decider, a
  command in a decider-style BC that does not implement its BC's routing marker.
- **Should-fix** — a slice missing its manifest, `_shared/` holding more than state or promoted
  below the three-consumer bar, an outstanding migration-twin retirement, a view slice with no test,
  a view reading through the BC's write repository, a write repository living outside `entities/`,
  a decider-style BC with no `routing/`, a `routing/` on the aggregate or service-entity lane.
- **Advisory** — file-cohesion smells, naming drift, a missing invariant record, a `_`-prefixed
  directory with no `CLAUDE.md`.

Three real findings stated plainly beat thirty padded ones. If a gate passes cleanly, say so in one
line rather than enumerating what did not fail.

## Step 4 — Repair (only if asked)

**`--adopt-tier`.** A one-time backfill for manifests that have no `tier`.
Writes `tier` **only where it is absent**, from the value resolved for the owning BC, and reports
every slice it stamped. This does not contradict the human-owned rule in
`manifest-reconciliation.md` §1 — that rule forbids *overwriting* a human's value, and there is no
value here to overwrite. A `tier` that is present and disagrees with the resolved default is a
**finding**, never an edit. Combinable with `--fix-manifests`; on its own it touches nothing else.

**`--fix-manifests`.** Regenerate `slice.yaml` and per-slice `CLAUDE.md` by three-way merge, following
`${CLAUDE_PLUGIN_ROOT}/references/slice/manifest-reconciliation.md` — read it first; it carries the
derivable/human-owned split, the per-field extraction rules, the `CLAUDE.md` contract, and the
whole-project ordering.

Machine-derived fields (`handles`, `publishes`, **`consumes`**, **`dispatches`**, `serves`, `endpoints`,
`writes`, `owns`, `reads`, `projections` **including `from` and `aggregateTypes`**) are overwritten from the code;
hand-written prose is preserved byte-for-byte; contradictions are reported rather than silently
resolved. Refresh the pointer file only after the user accepts gate 12's offer — never silently.

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
