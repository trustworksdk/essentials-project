# Essentials Slice Model

**Status: NORMATIVE for Essentials projects, language-neutral above the rendering tables.**

The structural law is `rules/slice-design.md`. This document is the *anatomy reference*: what each
slice kind is made of, what each part is called in Java and in Kotlin, and where each file lands.
Skills read this to decide **shape**; `scripts/render-slice.py` renders `references/slice/templates/` to emit **code**.

Self-contained — no other plugin required.

---

## 1. What a slice is

A **vertical slice** is one complete, independently testable feature path: trigger → decision →
persistence → output. It is the unit of work, the unit of review, and the unit of navigation.

Package by slice, never by technical layer. A bounded context has no `controllers/`, `services/`, or
`repositories/` directories; it has `use_cases/`, `views/`, `automations/`, and `external_systems/`.

Adding a feature adds a **directory**. It does not edit another slice's files. (The one sanctioned
exception is appending a name to a Java sealed parent's `permits` clause — see `rules/slice-design.md`
§R3.)

## 2. The four kinds at a glance

| Kind | Input → Output | Owns an API? | Emits events? | Test floor (event-sourced lanes) | Test floor (service-entity lane) |
|---|---|---|---|---|---|
| command | command → event(s) | one endpoint | yes | decider unit test (given/when/then) | entity unit test for the invariant (pure, no Spring) **+** a slice integration test through the `CommandBus` asserting the event was published |
| view | events → read model; query → response | one API, one or more queries over its own model | never | projector integration test | integration test over a seeded table asserting the read shape and the query |
| automation | event(s) → command(s) | no | no (it dispatches commands) | integration test | integration test (unchanged) |
| translation | external message → internal command, or internal event → external call | no | no | translator round-trip test | round-trip test (unchanged) |

The lane is `rules/slice-design.md` §R5's write style, a per-BC property. The **entity unit test** is
the load-bearing addition: an idempotency guard that returns `false` the second time is testable in
three lines with no database, and it is the one invariant a service-entity slice most often leaves
untested because the integration test appears to cover it.

## 3. Anatomy per kind

### 3.1 Command slice — `use_cases/<slice>/`

| Part | Role | Kotlin | Java |
|---|---|---|---|
| Command type | The intent, as data | `data class PlaceOrder(...) : OrderCommand` | `record PlaceOrder(...) implements OrderCommand` |
| Decider | Pure decision: `(command, events) → event?` | `Decider<PlaceOrder, OrderEvent>` | `EventStreamDecider<PlaceOrder, OrderEvent>` |
| API handler | One endpoint, sends on the command bus | `@RestController` + one `@PostMapping` | same |
| Event variant | What it emits — lands in the BC's `events/` | `data class OrderPlaced(...) : OrderEvent` | `record OrderPlaced(...) implements OrderEvent` |
| Test | Given/when/then over the decider | `GivenWhenThenScenario` | `GivenWhenThenScenario` |

State reconstruction is an **Evolver** (`Evolver<EVENT, STATE>` / `EventStreamEvolver<EVENT, STATE>`),
**per-slice by default** — it lives in the slice directory alongside the decider. Promotion to
`use_cases/_shared/` requires three or more decider consumers needing the *same* state, and is a
plain move that keeps both type names. See `rules/slice-design.md` § The `_shared/` promotion bar.

The decider is pure — no I/O, no repositories, no read-model queries. Return `null` for an
idempotent no-op; throw to reject.

### 3.2 View slice — `views/<slice>/`

| Part | Role | Kotlin | Java |
|---|---|---|---|
| View entity | The read model document | `@DocumentEntity` data class implementing `VersionedEntity` | `@DocumentEntity` class extending `JavaVersionedEntity<ID, SELF>` |
| Repository | Persistence for the view | `DelegatingDocumentDbRepository` via `factory.create(...)` | `DocumentDbRepository` via `factory.createForStringId(Class)` |
| Projector | Applies events to the view | `ViewEventProcessor` | `ViewEventProcessor` |
| API handler | Queries over this slice's own read model | `@RestController`, one `@GetMapping` by default | same |

**The slice is the read model, not the endpoint.** One API file, serving one or more queries over
*this slice's own* read model — `listOrders()`, `filterByStatus()`, `sortedByDate()` are one slice.
A query serving a different purpose over a different read-model shape is a different slice. Needing
one more event is **not** — that is this slice evolving. See `rules/slice-design.md` §R2 and
§ Evolving a view slice.

**No adapter layer.** The API returns the view entity; there is no `…Response` mirror and no mapper.
On the write side the command type is the request body. See §R2, "The command and the view *are* the
contract".

**Two load-bearing rules:**

1. **Processor selection.** If the read model must be current the moment the command API returns,
   use `InTransactionEventProcessor`; otherwise `ViewEventProcessor`. Never a plain `EventProcessor`
   for a view. See `references/llm/LLM-postgresql-event-store.md`.
2. **Idempotency via `EventOrder`.** The view carries a version equal to the event's order, and
   updates are conditional on it. Kotlin passes `Version(message.order)` — the constructor;
   `Version.of()` does not exist. Java passes `message.getOrder()` to the `long` overload, so it never
   constructs the Kotlin value class. A projector calling a plain `update(entity)` with no version is
   doing CRUD auto-increment, not projection.

A projection's `@MessageHandler` accepts `OrderedMessage` as its second parameter — not because the
dispatcher requires it (it is optional; a single-argument handler is invoked normally) but because
`message.order` is the `EventOrder` the idempotency check above compares against. Rebuild support is
`onSubscriptionsReset(aggregateType, resubscribeFromAndIncluding)` → delete the rows for that
aggregate type. The hook fires **once per subscribed `AggregateType`**, so a blanket `deleteAll()` is
correct only for a single-aggregate projection.

Both languages use **DocumentDB**, which creates its own table and indexes — so neither ships a
Flyway migration for the view. A JDBI `@SqlObject` repository over a Flyway-managed table is the
supported alternative in both languages when you need hand-tuned SQL or the view must live in an
existing relational schema; no template ships for it.

**On the service-entity lane a view slice has no projector at all** — it queries the entity's own
table through a slice-private read-only interface and returns a declared read shape. See §3.5 and
`rules/slice-design.md` § The read side on this lane, plus § Spring Data repository surface for the
interface's own contract — the bare `Repository` marker, the closed projection, and the reserved
CRUD base method names that must not be reused (`findById` above all).

### 3.3 Automation slice — `automations/<slice>/`

| Part | Role |
|---|---|
| TodoList (optional) | Explicit state of what is done and what is pending, with `canX()` guards |
| Repository (if stateful) | Persistence for the TodoList |
| Processor | `EventProcessor` reacting to events across several aggregate types, dispatching commands |

The minimum automation is a stateless handler: one event in, one command out. The TodoList
elaboration is for processes whose progress spans several events.

Every handler must be **idempotent** — check whether the step already happened and return early.
Retries are bounded; cancellation paths issue compensating commands. Delayed commands go through the
command bus with a duration and are handled with `@CmdHandler`; they survive restarts.

Automation slices have **no external API**.

### 3.4 Translation slice — `external_systems/<system>/`

| Part | Role |
|---|---|
| Client port | The typed outbound interface to the external system |
| Translator | Pure mapping between external and internal shapes — no framework imports |
| Inbound handler | Receives an external message, dispatches an internal command (Inbox) |
| Outbound publisher | `EventProcessor` reacting to internal events, calling out (Outbox) |

"External" is defined by **boundary, not distance**: another team's service is external; your own
browser client is not. Everything past the boundary speaks the external schema; nothing inside the
bounded context does. The translator is where the two meet, and it is pure so it can be unit-tested
without either side running.

A translation slice declares `direction` (`inbound` / `outbound` / `both`) and `maps` in its manifest.
Its test floor is a contract test; this plugin bundles **no** contract-testing tooling, so the shipped
template is a plain round-trip test and the choice of Pact/Specmatic is the project's.

### 3.5 Service-entity anatomy — the same four kinds without an event store

On `rules/slice-design.md` §R5's **service-entity** lane the four kinds are unchanged; three artefacts
are substituted. Read that section for the law — this is the file-level anatomy only.

| Part | Lives in | Role |
|---|---|---|
| Entity | `<bc>/entities/<Entity>` | State-stored (`@Entity` / `@Document`). Invariant-carrying methods only — § The entity's own bar |
| Write repository | `<bc>/entities/<Entity>Repository` | The write path's persistence contract: load by id, save, delete. **Nothing else** — enforced by extending the bare `Repository` marker, never `JpaRepository`/`MongoRepository` (§ Spring Data repository surface) |
| Command | `use_cases/<slice>/<Slice>` | Unchanged. It is the request body (§R2) |
| Handler | `use_cases/<slice>/<Slice>Handler` | A Spring bean with **one** `@CmdHandler` method: load, call the entity's one method, save, publish on the `EventBus`. Two command types in one handler is R1's router |
| API | `use_cases/<slice>/<Slice>API` | Unchanged — one endpoint |
| Event variant | `<bc>/events/<Event>` | Unchanged: sealed parent, one variant per file (§R3). Published for integration, not appended to a stream |
| View query interface | `views/<slice>/<View>Queries` | Slice-private, read-only, narrow. A Spring Data `Repository<Entity, Id>` — the **bare** marker — declaring only this slice's finders, and none of them named after a CRUD base method (§ Spring Data repository surface) |
| View read shape | `views/<slice>/<View>View` | A **closed interface projection**. It *is* the response body — no `…Response` mirror, no mapper (§R2) |

Absent by construction: the decider, the evolver, `routing/`, `use_cases/_shared/`, the projector, the
read-model table, and any Flyway migration for a read model. The **write** table still needs a
migration on a relational database — that is the write model, not a projection.

**Why the query interface does not breach §R2's no-adapter rule.** A closed projection interface is a
*declaration*, not a mapper class: nothing copies fields, and there is no second type to keep in sync.
It is also what keeps the read side off the write repository — the point of §R5's § The read side on
this lane. Interface projections behave identically on Spring Data JPA and Spring Data Mongo, which is
what makes this cheap in both flavours.

**The one way to get the projection wrong is the method name.** A lookup declared as `findById` is
captured by the Spring Data base implementation rather than derived as a query — the match is on name
and parameter types, so the declared projection type is ignored, the entity comes back, and the caller
gets a `ClassCastException`. Name it `find<View>ById` and the same `id = ?` query derives and projects.
§ Spring Data repository surface carries the full reserved list.

**Persistence: named, not templated.** Both Spring Data JPA and Spring Data Mongo are supported on
this lane, and the entity plus its write repository are the only files that must name one. **No
template ships for either** — the anatomy above is the contract, exactly as the JDBI read-side
alternative in §3.2 is supported without a template. Two reasons, both upstream:

- The Essentials `types-springdata-jpa` integration is marked **EXPERIMENTAL — "may be discontinued;
  prefer types-jdbi"** (`references/llm/LLM-types-springdata-jpa.md` Quick Facts and Limitations).
- Its handling of a semantic `@Id` is unsettled: the bundled docs disagree on whether a semantic id
  needs `@EmbeddedId` plus a converter or must degrade to a Java primitive. Shipping a JPA entity
  template would harden that contradiction into scaffolding.

Everything *else* on this lane is persistence-neutral, so the slice-level templates do ship — the
command, the handler, the API, the query interface, the read shape, and the tests.

## 4. The bounded-context scaffold — what lives outside the slices

Five directories sit beside the slices, plus one more on the aggregate style and one on the
service-entity style — never both. None of them is a slice, and the recurring mistake is treating them
as one (or dissolving them into one). Each exists because something is genuinely BC-scoped rather than
slice-scoped.

`routing/` is itself lane-conditional, and in both directions: it answers two questions only the
**decider** configurator asks, so it is **required** on the decider lane and **absent** on the other
two. On the service-entity lane the command bus routes by command type to a handler method and there
is no stream to select at all; on the aggregate lane there is a stream, but the handler already holds
the aggregate id and passes it to `StatefulAggregateRepository` — no filter, no resolver, nothing for
a marker interface to do. `/essentials:slice-check` gate 17 checks both directions.

### 4.1 `routing/` — aggregate routing interfaces

**Holds:** one marker interface per aggregate in the bounded context — `OrderCommand` for the Order
aggregate, and a sibling `ShipmentCommand` if the BC owns a second aggregate. Nothing else.

The interface declares exactly one member: the aggregate id.

```kotlin
interface OrderCommand {
    val id: OrderId
}
```

**Where shared command metadata goes instead.** Real commands do share fields — an actor, a
timestamp, a correlation id — and the marker is the obvious place to hoist them to, which is exactly
why the rule has to say where they belong rather than only where they do not. Put them in a **value
object in `types/`, composed into each command as a field**:

```kotlin
// types/CommandMetadata.kt — the BC's public surface, no framework dependency
data class CommandMetadata(val actor: UserId, val issuedAt: Instant, val correlationId: CorrelationId)

// use_cases/place_order/PlaceOrder.kt
data class PlaceOrder(override val id: OrderId, val meta: CommandMetadata, val sku: Sku) : OrderCommand
```

Composition rather than inheritance, for three reasons. The marker's *whole* job is routing — the two
questions in the table below — and a field the configurator never reads is dead weight on the one
interface every command in the BC is forced to implement. Metadata is domain vocabulary that outlives
the event-sourcing framework, so it belongs in `types/` where `OrderId` already lives, not in the
directory that disappears if you swap frameworks. And a value object is reusable by commands in a BC
that has *no* marker at all — the aggregate and service-entity lanes have none, and their commands
want the same three fields.

**What it is for.** Every concrete command, in its own slice under `use_cases/`, implements it. The
configurator then uses it for two distinct jobs — both visible in the BC's `config/`:

| Job | Wiring | What it answers |
|---|---|---|
| **Membership** | `DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(OrderCommand::class)` | Which deciders belong to this aggregate type? |
| **Id extraction** | `commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id }` | Which event stream should this command load? |

Without it the framework cannot route a command: it would know neither which aggregate a decider
serves nor which stream to read. (The Java names differ — `EventStreamDeciderSupportsAggregateTypeChecker`
and `EventStreamAggregateTypeConfiguration` — but the two jobs are identical. See §R5.)

**It is deliberately NOT sealed**, and that is the whole point. Contrast with `events/`:

| | `events/<Aggregate>Event` | `routing/<Aggregate>Command` |
|---|---|---|
| Sealed? | **yes** — you want exhaustive `when`/`switch` in evolvers | **no** — adding a command must touch no existing file |
| Adding a member | Kotlin: new file. Java: new file **+ one `permits` append** | new file only, in both languages |

This asymmetry is the open/closed hinge of the whole slice model, and it is exactly why Java command
slices need no `permits` edit while Java event variants do (§R3).

**Why its own directory**, rather than folding it into a neighbour:

- Not `use_cases/` — it is not a slice, and anything enumerating `use_cases/*/` would miscount it.
- **Not `types/` — because `types/` is public and this is private.** This is the decisive one. §6
  makes `events/` and `types/` a BC's importable surface; the routing interface is the opposite, since
  a foreign BC implementing `OrderCommand` would route its command into *your* aggregate. Folding it
  into `types/` would either make it legitimately importable cross-BC, or turn `types/` into a package
  where some files are public and some are not — per-file visibility rules inside one package, which
  is worse than an extra directory. It also happens to hold no domain meaning: swap the event-sourcing
  framework and `OrderId` survives, `OrderCommand` may not.
- Not `config/` — the dependency runs `config/ → routing/`. Putting it in `config/` would force every
  command slice to import the BC's Spring wiring to declare its own command type.

**The directory is the enforcement mechanism, because neither language can express this.** Kotlin's
`internal` is module-scoped, so with several bounded contexts in one Maven module it does not separate
them; Java's package-private does not work either, because the implementing commands live in
`use_cases/<slice>/`, a different package. There is no language-level way to say "BC-private" here —
so the convention plus `/essentials:slice-check` gate 8 is what holds the line.

**Yes, it is usually one file.** That is the cost, and it is accepted deliberately: a single-file
package buys a public/private split that the boundary rule and its automated check both depend on. A
BC owning two aggregates holds two interfaces here.

It is named `routing/` rather than `commands/` because `commands/` collided with the command-slice
directory — two different things called the same name in the same tree.

**BC-private.** Other bounded contexts must never implement your routing interface: doing so would
route a foreign command into your aggregate. A BC's public surface is `events/` and `types/` only
(§6).

### 4.2 The others

| Directory | Holds | Why it is BC-scoped |
|---|---|---|
| `events/` | The sealed event parent plus one file per variant | The event contract is the BC's public surface; variants are *owned* by their emitting slice but must share the parent's package |
| `types/` | Semantic types — ids, enums, value objects | Shared by several slices in the BC. A type used by exactly one slice stays in that slice; do not promote prematurely |
| `config/` | Spring wiring: the configurator, the aggregate-type configuration, one `@Bean` per decider | Wiring is per-aggregate, not per-slice. Adding a slice adds one line here — the only BC file a new slice touches |
| `use_cases/_shared/` | A per-BC `State` + `Evolver`, and nothing else | Sanctioned sharing, but **only once three or more deciders need the same state** — the default is per-slice, and a `_shared/` with fewer consumers is premature promotion. The `_` prefix marks it as *not a slice* among slices, and lets the boundary check exclude it with a prefix test rather than a name list |
| `aggregates/` | One aggregate type per file — **aggregate style only** (`rules/slice-design.md` §R5) | The aggregate *is* the consistency boundary, so it is BC-scoped by definition and shared by every command slice in the BC. It is kept out of `use_cases/_shared/` deliberately: that directory's value is that a decision component in it is a violation on sight. Absent entirely from a decider-style BC, which is the default and the only style scaffolded |
| `entities/` | One state-stored entity per file plus the write repository that persists it — **service-entity style only** (§R5) | Same reasoning as `aggregates/`: the entity is the consistency boundary, so it is BC-scoped and shared by every command slice. The repository sits beside it rather than in a `repositories/` folder because the directory is named for a **domain concept**, not a layer — and because a repository one directory away from its entity is one that grows finders for other people's screens (§ The read side on this lane) |

## 5. Role-name → file-name rendering

Given bounded context `orders`, slice `place_order`, aggregate `Order`:

| Role | Kotlin file | Java file |
|---|---|---|
| Command | `use_cases/place_order/PlaceOrder.kt` | `use_cases/place_order/PlaceOrder.java` |
| Decider | `use_cases/place_order/PlaceOrderDecider.kt` | `.../PlaceOrderDecider.java` |
| API handler | `use_cases/place_order/PlaceOrderAPI.kt` | `.../PlaceOrderAPI.java` |
| Event parent | `events/OrderEvent.kt` | `events/OrderEvent.java` |
| Event variant | `events/OrderPlaced.kt` | `events/OrderPlaced.java` |
| Routing marker | `routing/OrderCommand.kt` | `routing/OrderCommand.java` |
| Shared state | `use_cases/_shared/OrderState.kt` | `.../OrderState.java` |
| Shared evolver | `use_cases/_shared/OrderStateEvolver.kt` | `.../OrderStateEvolver.java` |
| Aggregate (aggregate style only) | — (Java family; see §R5) | `aggregates/Order.java` |
| Entity (service-entity style only) | `entities/Order.kt` | `entities/Order.java` |
| Write repository (service-entity style only) | `entities/OrderRepository.kt` | `entities/OrderRepository.java` |
| Command handler (service-entity style only) | `use_cases/place_order/PlaceOrderHandler.kt` | `.../PlaceOrderHandler.java` |
| View query interface (service-entity style only) | `views/order_list/OrderListQueries.kt` | `.../OrderListQueries.java` |
| Wiring | `config/OrdersConfiguration.kt` | `config/OrdersConfiguration.java` |
| View | `views/order_list/OrderListView.kt` | `views/order_list/OrderListView.java` |
| View repository | `views/order_list/OrderListRepository.kt` | `.../OrderListRepository.java` |
| Projector | `views/order_list/OrderListProjection.kt` | `.../OrderListProjector.java` |
| Automation processor | `automations/fulfillment/FulfillmentProcessor.kt` | `.../FulfillmentProcessor.java` |
| Translator | `external_systems/billing/BillingTranslator.kt` | `.../BillingTranslator.java` |

Slice directories are `snake_case`; type names are `PascalCase`; the bounded context directory is
`snake_case`.

## 6. Boundary rules

**Public surface of a slice:** nothing. Other slices do not call into it.

**Public surface of a bounded context:** its `events/` and its `types/`. That is the entire import
allowance for code outside the BC. `routing/`, `config/`, `use_cases/`, `use_cases/_shared/`,
`views/`, `automations/`, `external_systems/`, `aggregates/`, and `entities/` are all BC-private — see
§4.1 for why `routing/` in particular cannot simply live in `types/`. The surface is the same on every
lane: a service-entity BC still has `events/`, because its events are declared exactly as §R3
prescribes and merely delivered on the `EventBus` rather than stored.

Collaboration between slices happens by one of exactly three mechanisms:

1. Publishing an event that the other slice's projector or automation reacts to.
2. Issuing the other slice's command on the command bus.
3. Calling a **published read seam** — an interface in the BC's `types/` that the data-owning slice
   implements and registers, declared as `provides:` on the owner and `reads[].via` on the consumer.
   Read-only, question-shaped, and subject to the bar in `rules/slice-design.md` § The published read
   seam. Cross-BC it is mandatory; within a BC it is the sanctioned alternative to exposing a table.

The first two are asynchronous and the third is not, which is the whole reason mechanism 3 ranks last:
a seam couples the caller to the owner's availability, so prefer an event wherever one will do.

Mechanism 2 is the **one** sanctioned reason a slice may name another slice's command type; the
dependency runs one way only, so nothing in `events/`, `entities/`, or `aggregates/` may name a
command type at all (`rules/slice-design.md` §R4).

Note what mechanism 3 does **not** relax: the implementation stays slice-private. A slice's public
surface is still nothing — what became importable is an interface in the BC's `types/`, which was
already the BC's public surface.

Reconstructing another slice's state and calling its decider is forbidden — it couples you to its
decision logic, which is the thing slicing exists to isolate.

## 7. Per-slice orientation artifacts

Every slice directory carries two files beside its code:

- **`CLAUDE.md`** — 15–40 lines of prose: kind, status, owner, purpose, invariants, boundaries
  (including an explicit *Forbidden* list), data, and a file inventory. This is what lets an agent
  open one slice and change it correctly without reading the rest of the service.
- **`slice.yaml`** — the machine-readable manifest. Schema:
  `references/slice/slice-yaml.schema.json`; field guidance: `references/slice/manifest-guide.md`.

Both are generated by `/essentials:add-slice` and audited by `/essentials:slice-check`.

## 8. Manifest compatibility

The manifest schema is **toolchain-neutral**. `generator` records which tool owns a manifest, and the
`arch` event-model linkage is optional data that Essentials neither writes nor reads, so a manifest
written by an external generator still validates against `references/slice/slice-yaml.schema.json`.
Essentials stamps `generator: essentials` and `language:` on what it writes.

Essentials requires no other tool. For Essentials projects this document and `rules/slice-design.md`
govern: notably the JVM directory rendering uses `use_cases/` and `external_systems/` with
underscores, because hyphens are illegal in JVM package names.
