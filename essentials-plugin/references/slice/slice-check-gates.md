# The slice-check gates

The nineteen gates of `/essentials:slice-check`, with the notes and lane tables they are applied by.
`commands/slice-check.md` Step 2 reads this file whole. `/essentials:review` Step 4c reads only the parts
for the gates in scope, by these headings — so a note about a gate stays under the heading that names it.

Step numbers here are `commands/slice-check.md`'s: Step 1.5 runs `slice-lint.py` and `slice-source.py`,
Step 3 is the report (severities, impacts, `Gates not run`), Step 4 the repairs. Gate numbers are review
ids (`ESS-G<gate><clause>`), never renumbered; each gate's `<a id="g<n>">` anchor is here and, as a stub,
in `commands/slice-check.md` Step 2, which `/essentials:review` findings link to.

## Before any gate
Gate 14 runs **first in practice** — the write style it detects decides how gates 2, 5, 9, 10, 13,
15, 17 and 19 read for that BC.

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
the comment is gone. `slice-source.py` applies it for gates 6, 8, 11 and 14 and for `writeSites`, since
its lexer drops comments and string contents before anything is counted. It binds every gate you count
by hand, gates 10 and 17 included.

## The gate table
| # | Gate | How |
|---|---|---|
| <a id="g1"></a>1 | **Parseable, then schema-valid** | **Run by `scripts/slice-lint.py` (Step 1.5), not by you.** Two steps, in order. (a) **Every `slice.yaml` parses as YAML.** A file that does not parse is **Blocking** and is reported by name — never skipped, because a manifest no tool can read is invisible to gates 3, 4, 11 and to `/essentials:slice-map`, and "invisible" reads as "compliant". The dominant cause is an **unquoted path containing a brace** inside a flow mapping — `- { method: POST, path: /api/orders/{id}/cancel, auth: user }` — where the `{` opens a nested mapping and the parse dies there. See `manifest-guide.md` §3. (b) The parsed document validates against the schema; required-by-kind fields present. An unknown key is a finding at or below the copy's `x-manifestVersion` and a note above it, except the projection near-synonyms (`subscribesTo`, `handles`, `table`, `kind`), which are a finding at every stamp; an `x-` key is an extension key and always valid (`manifest-guide.md` §3). The script also reports a braced path that is unquoted but currently *legal* because it sits in block form — **Should-fix**, because reflowing that entry is a routine edit that turns it into (a). (c) **A projection's `aggregateTypes` value that is used as an event type anywhere else in the project** — **Should-fix**. `aggregateTypes` is the `AggregateType` STREAM a projector subscribes to and `from` is the event types it handles; both are arrays of strings, so a swap validates cleanly and the schema cannot see it. The corpus can: a name that appears in any manifest's `publishes`, `consumes` or `projections[].from` is an event, not a stream. See `manifest-guide.md` §3. (d) **A scenario that names a message outside the slice's own lists** — **Should-fix**, `1(d) scenario`. `scenarios:` (schema 1.4) is given / when / then over the slice's own messages; the schema checks the shape, and the script checks the names against the same manifest: `when` ∈ `handles` / `serves` / `consumes` per kind, `then` events ⊆ `publishes`, commands ⊆ `dispatches`, a view's `readModel` its own, a unique `name`. The table is `manifest-guide.md` §3. What needs other manifests or the source — a `given` event nobody publishes, a `with` key that is no payload field, a `test:` that resolves to nothing — is the map's (`/essentials:slice-map` Step 4), never this gate's |
| <a id="g2"></a>2 | **Layout** | Role directory matches `kind`; no layer directory inside a BC — `controllers/`, `services/`, `repositories/`, `adapters/`, `ports/`, `infrastructure/`, `dto/`, `mappers/`; `use_cases/_shared/` holds only `*State`/`*Evolver`, **and has three or more decider consumers** — see the promotion-bar note below. A `_`-prefixed directory is not a slice: skip it, and report it as **Advisory** if it carries no one-line `CLAUDE.md` saying what it is |
| <a id="g3"></a>3 | **Uniqueness** | `slice:` ids unique across the repo. **Run by `scripts/slice-lint.py`** |
| <a id="g4"></a>4 | **Sole ownership** | Three checks, all **run by `scripts/slice-lint.py`**, all **Blocking**. (a) **One handler per command type** — a name in `handles` appearing in two slices. The command bus permits exactly one handler per command type and throws `MultipleCommandHandlersFoundException` at startup, so this is a boot failure, not a style note. (b) **One writer per read model** — a name in `owns` appearing in two slices (§R4). Owners that are all linked to each other through `supersedes` are a declared migration twin: the script does not report them here, because they are gate 13's **Should-fix**, not a Blocking ownership clash. (c) **One bounded context per aggregate** — a name in `writes` written from two BCs; an aggregate is one consistency boundary. **The non-finding matters as much as the findings: several command slices writing the same aggregate inside one BC is the design on every lane and is never reported** — see the note below |
| <a id="g5"></a>5 | **§R1** | A decider file with `when (cmd` / `switch (cmd` over ≥2 command types, or ≥2 `decide[A-Z]` methods |
| <a id="g6"></a>6 | **§R2** | **The syntactic half is `slice-source.py`'s** (Step 1.5), and you take it verbatim. `6 command mappings` (Blocking): a command slice file with more than one method-level mapping. `6 endpoint route`: a manifest endpoint whose method and route match no mapping in the slice. `6 discriminator`: the route matches, but no single handler binds every discriminator. `6 undeclared mapping`: a mapping in a view or command slice that no endpoint describes. **An inbound translation webhook is the external system's ingress and needs no `endpoints` entry**, so the script never reports one. `6 raw id` (Advisory): a `@PathVariable` / `@RequestParam` parameter of a command or view slice's request mapping whose name — the parameter's, or the one it binds — is `id` or ends in `Id` at a camelCase boundary (`orderId`; not `paid`, `valid`, `sku`, `status`), typed as a JDK or Kotlin scalar: `String`, `CharSequence`, `long`/`Long`, `int`/`Integer`/`Int`, `short`/`Short`, `UUID`, `BigInteger`, nullable or inside `Optional<…>`, or a Kotlin typealias of one. It is the typed-edge default of § The command and the view *are* the contract left unwritten; a translation webhook is exempt, because it carries the external system's ids. The fix has two parts. First, type the parameter with the BC's semantic id. Second, for a Java `CharSequenceType` / `SingleValueType` id, always make sure exactly one configurer matching the web stack is `@Import`ed (S4): `EssentialsWebMvcConfigurer` on spring-webmvc, `EssentialsWebFluxConfigurer` on spring-webflux. That holds even when Spring could bind the id through its `String` constructor. `stack-lint.py`'s `ESS-S4` findings say what is missing: `s4-typed-edge-unregistered` (Blocking) for an id only the Essentials converter can bind, and `s4-typed-edge-convention` (Should-fix) for one that binds today without it. The procedure is `references/slice/slice-authoring.md` §4c. A Kotlin `@JvmInline value class` id needs neither. Whether the BC has a semantic id type for the parameter is yours to read before proposing the change. What stays yours: a view mapping whose handler reads a repository the slice does not own, a mapping missing from `serves`, and the no-adapter rule below. `6 raw id` is not among them: a parameter the script did not report (a `sku`, a `code`) gets no raw-id finding from you, even where the BC has a type for it. The rule the script implements, for reading its findings: a **command** slice's API file with more than one **method-level** request mapping. A class-level `@RequestMapping` declaring the base route is standard and is **not** one of them — counting it reports every command slice as having two. For a **view** slice, several mappings are legitimate (queries over its own read model) — flag instead: a mapping whose handler reads a repository the slice does not own, and any mapping absent from the manifest's `serves`/`endpoints`. **Compare mappings to endpoints on the route, not on the literal string.** A manifest path may carry query discriminators after a `?` (`"/api/shipping/order-status?status="` for `@GetMapping(params = "status")`, `manifest-guide.md` §3): split there, match the route, then confirm each named parameter is bound in that handler via `params = "…"`, `@RequestParam`, or `queryParam("…")`. Two mappings sharing a route and differing only by `params` are two endpoints and two `serves` names in **one** slice — §R2 working, not an §R2 violation. An optional filter with a default is not a discriminator and needs no entry of its own |
| <a id="g7"></a>7 | **§R3** | An `events/*.{kt,java}` file declaring ≥2 concrete event types |
| <a id="g8"></a>8 | **§R4** | **Run by `slice-source.py` (Step 1.5), in its entirety**; take its findings verbatim. It resolves each import, static imports included, to the directory it names. Four checks: `8(a) slice import`, a type from another slice's `use_cases/<slice>/` or `views/<slice>/`; `8(b) slice import`, from another slice's `automations/` or `external_systems/`; `8(c) bc-private import`, anything of another bounded context's except its `events/` and `types/` — `routing/`, `config/`, `entities/`, `aggregates/` or any other directory; `8(d) command in domain`, a file in `events/`, `entities/` or `aggregates/` importing a type from `<bc>.use_cases.<slice>.`, always **Blocking** and worst in `events/`. Otherwise the severity follows what the imported type can do (§R4): **Blocking** for a decider, an evolver or state, a handler, a processor, a write repository or a routing marker; **Should-fix** for anything read-only. The finding names the target's shape. Not findings: a command type whose every use in the file constructs a command for the command bus; an aggregate type named only for its `AggregateType` constant, the stream a read-side slice subscribes to; a target outside every bounded context — root `config/`, a shared kernel, a library (§ Directory vocabulary); a `_`-prefixed directory, as importer or as target. What stays yours: a read-side slice **in the same BC** calling a method of the aggregate or of its repository wrapper — **Blocking**, and invisible to an import check; and the fix — move a type both sides genuinely share into `types/`, or replace the crossing with the owner's command, an event or a published read seam (§R4, § Sanctioned sharing) |
| <a id="g9"></a>9 | **Wiring** | Every `*Decider` has a matching `@Bean` in its BC's `config/` |
| <a id="g10"></a>10 | **Projection idempotency** | A `@MessageHandler` that writes a versioned read model (`save`/`update` on a `DocumentDbRepository`) takes `OrderedMessage` as its 2nd parameter and passes its order to the write. The parameter is **optional** to the dispatcher — a single-argument handler is invoked normally — so flag it **only** where the handler mutates versioned state, never on handlers with no versioned state (a translation publisher, a stateless automation step) |
| <a id="g11"></a>11 | **Manifest ↔ code** | Both directions. (a) `handles` / `publishes` names resolve to types in or adjacent to the slice. `serves` holds query **names**, not types and not routes (`manifest-guide.md` §3): resolve each to the endpoint or handler method that answers it — a name nothing answers is the finding. This half is yours. (b) **Every event type the slice's handlers actually handle is declared.** This half is **`slice-source.py`'s** (`11(b) handled events`, Step 1.5), taken verbatim. The rule it implements: take the parameter types of the slice's `@MessageHandler` / `@Handler` methods on an `EventProcessor`, `ViewEventProcessor` or `InTransactionEventProcessor`, and of its Spring `@EventListener` methods, which service-entity-lane automations use (`@EventListener` on an `org.springframework.*` or `java.*` type is framework plumbing and is ignored). Resolve each to the declared type through imports, Kotlin `import … as` aliases, typealiases and fully-qualified names, so a handler taking `Placed` under `import …OrderPlaced as Placed` handles `OrderPlaced`. A handler typed as a sealed parent declared in the project stands for its concrete subtypes. A `@Handler` method on an `AnnotatedCommandHandler` is a command handler, not an event handler. An inbound translation (`direction` `inbound` or `both`) is also held to its ingress: the payload type of each `@KafkaListener`, `@RabbitListener` or `@JmsListener` method, or each concrete subtype of it declared in the project, must be in `consumes`; a payload that is not a project type (`String`, a record from a library) is listed as not checked. Look for them in the field that kind uses: **`projections[].from` for a view**, `consumes` for an automation or translation (`manifest-guide.md` §3). An undeclared handled event is **Should-fix** — the code is right and the manifest is stale, so the repair is `--fix-manifests`, never a source edit. This is the most common drift in a mature project: a projector gains one more `@MessageHandler` and the manifest is not touched, so the slice silently reads as reacting to fewer events than it does. (c) **`11(c) undeclared write`** — **Should-fix**, `slice-source.py`'s: a `writeSites` entry inside a slice whose manifest `writes` lacks the entity. The repair is `--fix-manifests`, unless the write is itself gate 15(a)'s finding — then fix the write, not the manifest |
| <a id="g12"></a>12 | **Project-copy freshness** | Two files, and only these two ever leave the plugin. (a) `.claude/rules/essentials-slices.md` exists and its `<!-- essentials-slices-rules: vN -->` stamp is not older than the plugin's — compare the integer after `v` numerically, never as strings (`v10` is newer than `v9`). (b) If the project installed the lint gate (`scripts/slice-lint.py` + `scripts/slice-yaml.schema.json`, `/essentials:init` Step 9), neither differs from the plugin's current copy — **Advisory**, and `diff` says it in one line. A project running a stale schema validates against yesterday's contract and reports a clean pass it has not earned. **Before offering that refresh, measure what it costs** — see § Refreshing the lint gate at the end of Step 2: a newer copy can fail the project's hook and CI on manifests the installed copy passed, and an offer that does not say so turns the gate red on the user's next commit. (c) If the gate is **absent entirely** — the case for every project scaffolded before it shipped — say so once as **Advisory** and name `/essentials:upgrade`, which offers the install. Do not offer it here: this is a slice audit, and clause (b) staying silent on a project that never had the gate is exactly how a shipped capability never reaches an existing project. **Offer** a refresh for (a) and (b); never overwrite silently |
| <a id="g13"></a>13 | **View duplication / migration twins** | Two view slices whose `projections.from` sets and read-model shapes coincide. Resolve against `supersedes` + `status` — see the table below. Also flag a `supersedes` pointing at a slice that no longer exists (stale link — drop the field) |
| <a id="g14"></a>14 | **Write-style lane (§R5)** | Per BC, detect one of three lanes — see the detection table below. Report a BC holding **any two** of per-slice deciders, `<bc>/aggregates/`, and `<bc>/entities/` as **Blocking** — two write designs over one consistency boundary. The detected lane selects which of gates 2, 4, 5, 9, 10, 13, 15 and 17 apply and how. **The signal-count rows are `slice-source.py`'s** (Step 1.5), taken verbatim: `14 two write styles` and `14 entities with event store` (Blocking), `14 declared lanes` (Blocking), `14 stale lane` and `14 lane without write model` (Should-fix). Its `lanes[]` lists each BC's signals with `path:line`. `14 tier` is **slice-lint's**. What stays yours: the service-entity criterion "state loaded, mutated and saved in place" (the script says `service-entity?` and stops), the bus-deviation table, and deciding whether an undetermined BC is simply new |
| <a id="g15"></a>15 | **Write-repository purity and placement** (service-entity lane only) | Identify the BC's write repositories **by mutation surface, not by path and not by base interface**: any type through which an entity declared in `<bc>/entities/` is persisted — it declares or inherits `save`/`delete`/`update`/`saveAll`. Spring Data is one vehicle (`CrudRepository`/`JpaRepository`/`MongoRepository`, or a bare `Repository` declaring the methods itself); Essentials' own document repository, a JDBI DAO, and any other store are others, and **the gate must find all of them** (§R5, "identify the write repository by its mutation surface"). Two ways this gate silently stops working: keying on the CRUD-family interfaces alone misses every repository that took gate 18's advice, and keying on Spring Data at all misses every project that uses none — the purity rules below are framework-agnostic and were the valuable part all along. For each: (a) a mutating call (`save`, `delete`, `saveAll`) on it from outside `use_cases/*` — **Blocking**. Start from `slice-source.py`'s `writeSites`: every such call is an entry, and one whose `callerRole` is not `use_cases` is the candidate. The facts are the script's; the verdict is yours. An idempotent insert-if-absent seed run at startup — it inserts a row only when none exists and never updates one — is the exception: **Advisory** when the BC's `CLAUDE.md` documents it, **Should-fix** when not; (b) a finder on it whose only callers are view slices or API handlers, or a screen-shaped finder no write path calls (the law's `findByShipped(boolean)`) — **Should-fix**, the read side served from the write model (§ The read side on this lane). A finder whose callers load rows in order to act on them — a `use_cases/` handler, an automation dispatching a command per row — is the write path and not (b), whatever the caller's kind; (c) **its file is not in `<bc>/entities/`** — **Should-fix**, it must sit beside the entity it persists (§R5, the `<bc>/entities/` tree). A `repositories/`, `persistence/`, `dao/` or `store/` folder is the common form and gate 2 catches only the first; a repository at the BC root or beside one slice is the same violation and only this check sees it. The fix is a file move, never a rewrite |
| <a id="g16"></a>16 | **Lane hygiene** (service-entity lane only) | A `@RestController` returning an `@Entity`/`@Document` type — **Should-fix**. A public setter writing a field that an invariant method also guards — **Should-fix**, the guard is bypassable. A `use_cases/_shared/` in a service-entity BC — **Should-fix**, there are no evolvers to promote |
| <a id="g17"></a>17 | **Routing ↔ lane** | `<bc>/routing/` is decider-lane-only and **required** there (§ Directory vocabulary). Check **both** directions — see the routing table below. Gate 14 owns lane detection: `routing/` is evidence *about* a BC, never an input to deciding its lane, or the two gates reason in a circle |
| <a id="g18"></a>18 | **Spring Data repository surface** (all lanes) | **Scope check first: if the project declares no Spring Data repository at all, this gate is 100% out of scope — report it as skipped, with the reason, and move on.** Silence here must never read as a pass; a project persisting through Essentials' `DocumentDbRepository` or JDBI legitimately has nothing for this gate to see. Otherwise: every Spring Data repository interface in the project — write repository, view query interface, or a Spring Data-backed read model. (a) It extends anything other than the bare `org.springframework.data.repository.Repository` — `CrudRepository`, `ListCrudRepository`, `PagingAndSortingRepository`, `ListPagingAndSortingRepository`, `JpaRepository`, `MongoRepository`, `ReactiveCrudRepository`, `ReactiveMongoRepository`, `R2dbcRepository` — **Should-fix**; the surface is now everything the framework offers rather than what the slice declared. (b) A method returning the mapped `@Entity`/`@Document` where a closed interface projection belongs — **Should-fix**. (c) A method declaring a **projection** return type but **named after a CRUD base method** — **Blocking**; see the reserved-name list below. Essentials' `DocumentDbRepository`/`DelegatingDocumentDbRepository` is **not** a Spring Data repository and is out of scope for all three (§ Spring Data repository surface) |
| <a id="g19"></a>19 | **Side effects after a state change** | Model-judged; **Should-fix**, impact *wrong today* (§ Side effects after a state change). Two shapes. (a) A method that changes state and, in the same method, calls something outside its transaction — a mail sender, an HTTP or SOAP client, a broker template, another service's client. Start from `writeSites` on the service-entity lane, and from each command handler and aggregate method on the event-sourced lanes; read the method for an external call before or after the write. (b) A processor or translation handler that calls an external port and then records the outcome with `commandBus.send(...)`, without first checking a recorded outcome — when the send fails, the redelivery repeats the call. Not findings: a call made by an `EventProcessor` handler or an `Outbox` consumer that checks the recorded outcome or passes a key the external system deduplicates on; an `Outbox.sendMessage`, a `DurableQueues` enqueue or a `sendAndDontWait` on a `DurableLocalCommandBus` inside the write's unit of work — that is the fix, not the defect |

## Notes on gates 2, 4, 6, 10 and 13
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
| No `supersedes` link between them | **Blocking** — two slices sharing a read model (§R4). When both `owns` the same read model, this is slice-lint's `4(b)`; report it once, there |

## Gate 14 — lane detection
Gate 14 exists because **most of this law is lane-independent and a few gates are not.** Never report
the absence of deciders as a violation on a lane that has none — that is the style working as
intended.

**Lane detection, per BC:**

| Evidence | Lane |
|---|---|
| `Decider` / `EventStreamDecider` implementations | decider |
| `<bc>/aggregates/` exists | aggregate |
| `<bc>/entities/` exists **and** state is loaded, mutated and saved in place **and** no `EventStore` / `AggregateType` / `EventOrder` is referenced in the BC | service-entity |
| Any two of the above | **Blocking** — report both signals, name the files, and stop. Do not pick one (`14 two write styles`) |
| `<bc>/entities/` but an `EventStore`/`AggregateType` is referenced | **Blocking** — the BC is drifting off the lane; name both signals and stop, as above: it is not on the service-entity lane either (`14 entities with event store`) |

A BC either Blocking row above describes **has no lane**. Its lane-dependent gates — 2's `_shared/`
rule, 5, 9, 10, 13's twin machinery, 15, 16 and 17 — are not applied to it, under the lane they look
closest to or any other, and go in `Gates not run` with the reason **Lane**. Do not report what one
of them would conclude under either reading (a command missing a routing marker, a repository
placement): that is choosing the lane by the back door. `14 declared lanes` alone is different — the
code has one lane and keeps it; only the manifests disagree.

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
| A manifest declares a lane the BC no longer has | **Should-fix** (`14 stale lane`) — the BC migrated write styles and the manifests were not reconciled. `--fix-manifests` repairs it (`lane` is machine-derived) |
| A manifest declares a lane in a BC whose detected lane is undetermined — no deciders, no `aggregates/`, no `entities/` | **Should-fix** (`14 lane without write model`) — the manifest claims a write model the code does not have. Either the write model was never written or it lives somewhere gate 14 cannot see it; say which, from the code |
| Manifests in one BC declare **two different** lanes | **Blocking** (`14 declared lanes`) — corroborates gate 14's two-designs-over-one-boundary finding, and names which slices sit on which side. This is the cheapest signal there is for a half-finished migration |
| No manifest declares `lane` | Not a finding — the manifests predate the field. Detection stands alone |

**Do not read `lane` as an input to detection**, for the same reason gate 17 must not read `routing/`:
a declared value is evidence *about* the BC, and letting it decide the lane makes the gate agree with
whatever the manifest claims, including when the manifest is the thing that is wrong.

**Do not confuse `lane` with `tier`.** They are different axes and coincide on one value. A manifest
carrying `tier: aggregate` is a **Should-fix**: `aggregate` is a write style, not an `architectureTier`
value, and the schema requires a reader to treat an unrecognised tier as `custom`, which silently
drops the slice's tier-specific handling.
The repair is `tier: cqrs-es` + `lane: aggregate`. `tier: decider` is the same mistake, but its repair
is `lane: decider` plus `tier: cqrs-es` **only if the BC is event-sourced** — otherwise remove `tier`,
which falls back to the project default: a wrong tier is worse than an absent one. slice-lint reports
both as `14 tier`, from the manifest alone, with that repair as its hint.

**Also do not require Spring Data to classify.** Criterion 1 is *state is stored and mutated in
place*, whatever persists it. A BC using Essentials' own document repository or JDBI is on this lane;
what changes is that gate 18 is out of scope and gate 15 must find the write repository by its
mutation surface.

## Gates 5, 15, 17 and 18 per lane
**Gate 5 (§R1) per lane.** On the aggregate and service-entity lanes it applies as a bar rather than
as a `switch` scan:

| Check | Lane | Severity |
|---|---|---|
| A public method that only calls `apply(...)` with no invariant behind it | aggregate | **Advisory** — one is fine; report the *count* when most methods qualify |
| A public method that only assigns fields | service-entity | **Advisory** — same shape: a setter with a better name |
| Method count tracking the BC's command-slice count, with independent invariants | both | **Should-fix** — R1's router as a class; the BC wants decider style |
| A getter or query method whose only caller is an API or a view | both | **Should-fix** — the read side served from the write model |
| A command slice appending events directly instead of through the aggregate | aggregate | **Blocking** — outside the consistency boundary |
| A command handler deciding a rule the aggregate or entity guards (or should), or an `invariants[].enforcedBy` naming the handler | both | **Blocking**, impact *wrong today*, when the two checks can disagree — one throws where the other returns `false` or no-ops, so one input gets two answers. Otherwise **Should-fix**, impact *wrong on the next caller* — the write model is no longer the rule's sole enforcer. The row below is a different case: there the write model does not guard the rule at all |
| A command handler checking a precondition the entity method it calls does not guard | service-entity | **Should-fix**, impact *wrong on the next caller* — correct only while every caller goes through this handler; move the check onto the method (§ The entity's own bar) |
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
| decider | A command type under `use_cases/<slice>/` that a **decider** handles and that does not implement its BC's marker | **Blocking** — the checker will not match it, so that command routes nowhere. This is the failure the directory exists to prevent, and it is silent at startup. A command an `AnnotatedCommandHandler` handles is routed by its type and needs no marker: not a finding |
| decider | The marker is `sealed` (Kotlin) or carries `permits` (Java) | **Should-fix** — sealing it means adding a command slice edits an existing file, destroying the open/closed hinge the slice model turns on (`slice-model.md` §4.1) |
| decider | `routing/` holds a class, an enum, or an interface declaring more than the aggregate id | **Should-fix** — it is a marker directory, not a home for shared command shape. **Advisory** when the extra members are audit fields every command carries (who, when, a correlation id) and the BC's `CLAUDE.md` or the marker documents why |
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

## Gate applicability by lane
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
| 19 (side effects) | the stream is the outbox: a command handler making the call is the finding | same | from `writeSites`: the call belongs behind an `Outbox` or a durable enqueue in the save's unit of work |
