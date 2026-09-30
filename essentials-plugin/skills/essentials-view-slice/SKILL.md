---
name: essentials-view-slice
description: >
  Scaffold a Trustworks Essentials view slice (read model) in Java or Kotlin — view entity,
  repository, a ViewEventProcessor projector with EventOrder-based idempotency, a query API,
  slice.yaml manifest, per-slice CLAUDE.md, and the projector integration test. Invoked
  by /essentials:add-slice and /essentials:add-view-slice.
user-invocable: false
disable-model-invocation: true
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

# View slice — Essentials

A view slice projects events into a query-optimised read model and answers queries from it. It
**never** produces events.

## Inputs

Supplied by the dispatching command. **Never re-elicit these.**

| Input | Example |
|---|---|
| `language` | `kotlin` \| `java` |
| `lane` | `decider` \| `aggregate` \| `service-entity` — the BC's §R5 write style. Rendered into the manifest as `lane:` |
| `tier` | `cqrs-es` (both event-sourced lanes) \| `service-entity`. **Derived from `lane`, and not the same value** — it is the manifest's `architectureTier` vocabulary, a different axis (`slice-authoring.md` §4). Never render `tier: aggregate`: it is not a tier value, and another tool reading the manifest silently downgrades an unrecognised tier to `custom` and skips the slice |
| `projectRoot`, `sourceRoot`, `testRoot`, `packagePath` | resolved from the project |
| `entity` / `Entity` | `order` / `Order` — **service-entity lane only** |
| `bc` / `Bc`, `aggregate` / `Aggregate` / `AggregateType` | `orders` / `Orders`, `order` / `Order` / `Orders` |
| `view` / `View` / `viewCamel` | `order_list` / `OrderList` / `orderList` |
| `Event` | the first event projected, e.g. `OrderPlaced` |
| `apiPath`, `owner` | `/api/orders`, `orders-team` |

## Step 1 — Load the law and the shared procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md
```

The files are written by `scripts/render-slice.py` (`slice-authoring.md` §4b). This skill decides the
shape, runs the script, then fills the TODOs and reports.

## Step 2 — Decide the shape

**Branch on the lane first.** A view slice looks fundamentally different on the service-entity lane,
and most of this step does not apply there:

| | decider / aggregate lane | service-entity lane |
|---|---|---|
| Read model | a **separate** projected model this slice owns | the **entity's own table**, shared with the write side |
| Machinery | a projector, `EventOrder` idempotency, `OrderedMessage` | a slice-private read-only query interface + a closed projection interface |
| Consistency | eventual | **strong** — same table, same transaction |
| Applies below | §2a–§2f | §2a and §2b only, then § The read side on this lane |

**On the service-entity lane, skip §2c–§2f entirely.** There is no processor to select, no
`EventOrder` to compare, no `Version`, no `OrderedMessage`, and no DocumentDB entity. Read
`rules/slice-design.md` § The read side on this lane, § Spring Data repository surface, and
`slice-model.md` §3.5 instead, then go to Step 4. The four rules that replace them:

1. **Never read through the BC's write repository.** Declare this slice's own interface extending
   bare `Repository<Entity, Id>` — not `JpaRepository`/`MongoRepository`, which would expose `save`
   and `delete` to a view.
2. **Return a closed interface projection, never the entity.** The projection *is* the response body,
   so §R2's no-adapter rule holds — an interface projection is a declaration, not a mapper.
3. **Never name a query method `findById`** — or any other CRUD base method name. The bare marker
   removes the inherited *methods*, not the base *implementation*: Spring Data matches by name and
   parameter types, ignores the declared projection type, returns the entity, and the caller gets a
   `ClassCastException`. `findOrderStatusById` derives the same query and does project. See
   § Spring Data repository surface for the reserved list.
4. **Say that the read is strongly consistent** in the report. It is this lane's one advantage over a
   projection, and teams should not be told to add eventual consistency they do not need.

Everything below applies to the two event-sourced lanes.

Settle scope first — what this slice owns. Then the two decisions that, got wrong, make the slice
fail silently in production: processor selection and idempotency.

### 2a. The slice is the read model, not the endpoint

A view slice's identity is the read model it owns. It exposes **one API file**, with **one query
method by default and more when they interrogate that same model**: filters, sorts, pagination,
lookup-by-id, field projections. `listOrders()`, `filterByLastName()`, `sortedByFirstName()` belong
together in `OrderListAPI`.

Do **not** split those into separate slices. Doing so forces either several slices to share one read
model — breaking the ownership boundary in §R4 — or several read models to duplicate one projection
over the same events.

A genuinely **new** view slice serves a **different purpose** over a **different read-model shape** —
a different entity, a different grain, a different lifecycle. Anything else is another method on this
slice's API. Record every method in the manifest: `serves` gets the query name, `endpoints` gets the
route. Both are arrays.

If the user's request implies several query shapes over one model, emit the slice **once** with the
default method and tell them in the report which additional methods to add and to declare.

**Needing one more event is not a new slice.** If the ask is "the existing view should also show X",
that is the existing slice evolving: add the handler and rebuild. Do not scaffold. Say so, and point
at `rules/slice-design.md` § Evolving a view slice. The only reason to emit a second directory is a
rollout constraint — the model is too large to rebuild in an acceptable window — and then it is a
**declared, temporary twin**: emit `views/<view>_v2/`, stamp `supersedes: <bc>.<view>` and
`status: planned` in its manifest, and state in the report that deleting the original is part of the
change, not a follow-up.

### 2b. The command and the view are the contract

Return the **view entity** from the query method. Do not emit a `…Response` type mirroring it, and do
not add a mapper, assembler, or `toDto()`. The templates already do this — keep it that way when you
extend them. §R2, "The command and the view *are* the contract".

**Kotlin: a value class in a handler signature needs an explicit operationId** (trap ESS-113). Kotlin
mangles the JVM name of a handler that takes a value class as a parameter (nullable or `suspend`
included) or returns one (`placeOrder-40lU5Lw`); springdoc uses that name as the `operationId`, and the
generated frontend client inherits it. A value class only inside a generic (`List<OrderId>`) or a DTO
does not mangle. Give exactly the mangling handlers
`@Operation(operationId = "<viewCamel>…")` (`io.swagger.v3.oas.annotations.Operation`), named after the
slice so it stays stable and unique; `@JvmName` is not an option on Spring's open methods. See
`references/llm/LLM-types-spring-web.md` § Kotlin handler methods: set the operationId. The shipped
templates keep value classes inside the request and response types, so no emitted handler mangles;
the first edit that moves one into the signature — a typed `@PathVariable` id, say — does.

### 2c. Processor selection

```
Must the read model be current the moment the command API returns?
├─ YES → InTransactionEventProcessor   (synchronous, in the same transaction as the append;
│                                       also the tool for cross-aggregate uniqueness)
└─ NO  → ViewEventProcessor            (async, low latency, eventually consistent, replayable)
```

A plain `EventProcessor` is **never** right for a view — that is the Inbox-backed base for external
integration. The template ships `ViewEventProcessor`, which is correct for almost every read model.

`ViewEventProcessor` takes **`ViewEventProcessorDependencies`**; `EventProcessor` and
`InTransactionEventProcessor` take `EventProcessorDependencies`. They are not interchangeable.

### 2d. Idempotency via `EventOrder`

The Inbox redelivers. A projector that is not idempotent will double-count.

The view carries a `version` equal to the projected event's `EventOrder`, and the update is
conditional on it:

- **Kotlin** — `repository.update(entity, Version(message.order))`. Use the constructor:
  **`Version.of()` does not exist.**
- **Java** — `repository.update(entity, message.getOrder())`, using the `long` overload so Java
  never constructs the Kotlin `Version` value class.

`repository.update(entity)` with no version is CRUD auto-increment, not projection. Skip an event
whose order is not newer than the stored version.

### 2e. Handler shape

Every `@MessageHandler` **in a view projection** takes `OrderedMessage` as its second parameter.

The reason is idempotency, not dispatch. `PatternMatchingMessageHandler` treats the second parameter
as **optional** — a single-argument `@MessageHandler` is invoked normally. But `message.order` /
`message.getOrder()` is the `EventOrder` a projection compares against the row's stored `version`,
and that comparison is the whole mechanism in 2d. Without the parameter the projection has no way to
recognise a replay, so redelivery double-applies. A handler that needs no ordering — a
fire-and-forget publisher in a translation slice, say — may legitimately omit it.

Do not report a missing `OrderedMessage` on an arbitrary `@MessageHandler` as a defect. Report it
where the handler mutates a versioned read model without it.

### 2f. Persistence

Both languages use **DocumentDB**. Kotlin entities implement `VersionedEntity`; Java entities extend
**`JavaVersionedEntity<ID, SELF>`**, which bridges the Kotlin `Version` value class to two primitive
`long` accessors, and use the `Class<T>` factory overloads (`createForStringId`) and the string-path
condition API (`eq("address.city", value)`, with an explicit `DbType` for numeric and temporal
paths).

A JDBI `@SqlObject` repository over a Flyway-managed table is the supported alternative in both
languages — choose it when you need hand-tuned SQL or the view must live in an existing relational
schema. No template ships for it: DocumentDB manages its own table and indexes, whereas JDBI means
writing the migration and the SQL yourself.

Java entity gotchas:

- **The `@Id` field must be `public`.** `EntityConfiguration` resolves `@Id` through Kotlin reflection
  over `memberProperties`; for a Java class the property is synthesised from the *field*, so reading
  it is a direct field access, not a call to the getter. A private `@Id` makes the first `save`/
  `update`/`delete` throw Kotlin's `IllegalCallableAccessException` (wrapping `IllegalAccessException`),
  the message is dead-lettered, and the projection silently never populates — nothing fails at startup. `version` and `lastUpdated` escape this because they are
  declared on `JavaVersionedEntity` as Kotlin properties with real getters, so their `KProperty1`
  getter is a method call. Kotlin-declared property → method call; Java-declared field → field read.
- Initialise the version field to `Version.NOT_SAVED_YET_VALUE` (`-1`), not `0`.
- `version` and `lastUpdated` are hardcoded names in the reflection layer, so keep them and keep them
  mutable.
- **Name the `@Configuration` class `<View>RepositoryConfiguration`, never `<View>Repository`.** Spring
  registers a `@Configuration` class's own bean under its decapitalised simple name, which would
  collide with the `<viewCamel>Repository()` `@Bean` method it declares — a
  `BeanDefinitionOverrideException` at context startup that fails *every* `@SpringBootTest` in the
  project, not just this slice. Keep the `@Bean` method name: that is what consumers inject by.
- **Declare `kotlin-stdlib-jdk8` and `kotlin-reflect` in the module's `pom.xml`.**
  `postgresql-document-db` declares both in `provided` scope, so they are not transitive, and a pure-Java
  consumer still needs them at compile time — `createForStringId` and the `Condition` DSL expose
  `KClass`/`KProperty1` overloads javac must resolve. Missing them fails with
  `cannot access kotlin.reflect.KClass`. Adding a view slice is what pulls this module in; Step 4's
  `requires` names whichever of the three the build file lacks.

## Step 3 — Check or scaffold the bounded context

`Glob <sourceRoot>/<packageDir>/<bc>/`. **If absent, stop:** a bounded context starts with its first
command slice (`slice-authoring.md` §3). The projection imports `<bc>/events/<Event>`, which only a
command slice supplies, so a BC scaffolded by a view cannot compile — the script refuses `--new-bc` for
this kind. Tell the user, and offer `/essentials:add-command-slice` for the BC's first command.

**If the BC exists, confirm its lane before emitting** (`slice-authoring.md` §1b). A BC showing two
lanes is Blocking under §R5 — stop and report rather than emitting into it.

On the service-entity lane the view slice reads `<bc>/entities/<Entity>`, so that entity must already
exist. If `entities/` is empty, say so and stop: the read shape is checked against the write model at
startup, and there is nothing to check it against yet. The script refuses in that case too.

## Step 4 — Emit

First the module preconditions; exit 1 names each module the build file lacks — report it and offer
to add it before going on:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py requires --lang <language> --kind view \
    --lane <lane> --build <the build file from add-slice Step 0>
```

Then render. Pass the inputs you were given; the script derives the rest (`slice-authoring.md` §4b):

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py render --lang <language> --kind view \
    --lane <lane> --json \
    --project-root <projectRoot> --main-root <sourceRoot> --test-root <testRoot> \
    --set packagePath=<packagePath> --set bc=<bc> --set view=<view> --set Aggregate=<Aggregate> \
    --set AggregateType=<AggregateType> --set owner=<owner> \
    [--set Event=<Event>]             # decider / aggregate lane
    [--set Entity=<Entity>]           # service-entity lane, when entities/ already names one
    [--set apiPath=<apiPath>]         # only when it is not /api/<bc>
```

Exit 2 means nothing was written: relay the message and stop. The tables below are what it writes;
then fill the TODOs the JSON lists under `todos`.

**Decider / aggregate lane — `templates/<language>/view/`:**

| Template | Destination |
|---|---|
| `__View__View.<ext>` | `<bc>/views/<view>/<View>View.<ext>` |
| **kotlin** `__View__Repository.kt` | `<bc>/views/<view>/<View>Repository.kt` |
| **java** `__View__RepositoryConfiguration.java` | `<bc>/views/<view>/<View>RepositoryConfiguration.java` |
| `__View__Projection.<ext>` | `<bc>/views/<view>/<View>Projection.<ext>` |
| `__View__API.<ext>` | `<bc>/views/<view>/<View>API.<ext>` |
| `test/__View__ProjectionIT.<ext>` | test tree, mirroring the slice package |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/views/<view>/` |

**Service-entity lane — `templates/<language>/view_service_entity/`:**

| Template | Destination |
|---|---|
| `__View__View.<ext>` | `<bc>/views/<view>/<View>View.<ext>` — the closed projection interface |
| `__View__Queries.<ext>` | `<bc>/views/<view>/<View>Queries.<ext>` — slice-private read-only queries |
| `__View__API.<ext>` | `<bc>/views/<view>/<View>API.<ext>` |
| `test/__View__IT.<ext>` | test tree, mirroring the slice package |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/views/<view>/` |

No projector, no repository configuration, and **no Flyway migration** on that lane — there is no
separate read model to create. The write table's migration belongs to `entities/`, not here. The
templates are persistence-neutral: interface projections behave identically on Spring Data JPA and
Spring Data Mongo, so the same files serve both.

The repository file differs by language because the two shapes differ: Kotlin ships a real
`@Repository` component extending `DelegatingDocumentDbRepository`, whereas Java ships a
`@Configuration` that *produces* a `DocumentDbRepository` `@Bean` — and a `@Configuration` class must
not be named for the bean it declares (see the naming gotcha in 2f).

The script refuses an existing slice directory and any file it would overwrite.

## Step 5 — Wire it

**Decider / aggregate lane:** the projector is a `@Service` and the repository a `@Bean`/`@Repository`,
so component scanning picks them up — but confirm the BC's package is actually scanned. Record every
event the projection handles in `slice.yaml` `projections.from`, and the `AggregateType` stream(s)
its subscription is opened against in `projections.aggregateTypes` — those are two different facts
and writing a stream name into `from` corrupts the event list (`manifest-guide.md` §3).

**Service-entity lane:** Spring Data registers the query interface by scanning; there is nothing to
wire. Leave `projections` empty in `slice.yaml` — its emptiness is the signal that gates 10 and 13's
twin machinery do not apply, so do not delete the key and do not populate it.

## Step 6 — Report and self-check

Report the files written. State what the user must fill in: the view's real fields, the queries it
serves, and the API's actual query — plus, on the event-sourced lanes, a handler per projected event
and the indexes those queries need.

Re-read `rules/slice-design.md` § Red flags, then confirm per lane:

- **Decider / aggregate:** every projection `@MessageHandler` takes `OrderedMessage`; no update omits
  the version; in Java the `@Id` field is `public` and the `@Configuration` class is named
  `…RepositoryConfiguration`; the slice emits no events.
- **Service-entity:** the query interface extends bare `Repository`, not `JpaRepository`/
  `MongoRepository`; nothing in the slice injects `<Entity>Repository`; no method returns `<Entity>`;
  nothing calls `save`/`delete`; **no query method is named after a CRUD base method** — `findById`,
  `findAll`, `findAllById`, `existsById`, `count`, `getById`, `getReferenceById` — because the base
  implementation captures the name and discards the declared projection type (§ Spring Data
  repository surface); `projections` is `[]`. Say in the report that the read is **strongly
  consistent** — same table, same transaction — so nobody adds polling or awaitility to a test that
  needs neither.

## Red flags specific to this kind

- A projection `@MessageHandler` with only the event parameter — it cannot detect a replay.
- `repository.update(entity)` with no version argument.
- **Java:** a `private` `@Id` field — dead-letters every message, projection never populates.
- **Java:** a `@Configuration` class named `<View>Repository` — collides with its own `@Bean`.
- `onSubscriptionsReset()` with no parameters — the hook is
  `onSubscriptionsReset(AggregateType, GlobalEventOrder)`, and a blanket `deleteAll()` in it is only
  correct for a single-aggregate projection.
- The projector or the API writing to the event store or calling a Decider.
- The API reading another slice's repository.
- `Version.of(...)` — it does not exist.
- Indexes added inside a query path rather than once at construction.
- Several view slices projecting the same events into the same read-model shape **with no
  `supersedes` link** — that is one slice split by mistake (§R2). With the link it is a migration
  twin, and the finding is instead the outstanding retirement of the original.
- A `…Response` type mirroring the view entity, or a mapper/assembler between the API and the read
  model — the read model *is* the response (§R2).
- An API method exposed but absent from `serves` / `endpoints` in the manifest.

## API provenance

Every Essentials symbol in these templates is listed in
`${CLAUDE_PLUGIN_ROOT}/references/slice/api-provenance.md`. Never introduce one that is not there.
