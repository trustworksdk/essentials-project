# Essentials design guide

Modelling guidance for event-sourced, slice-based applications on Trustworks Essentials: what to
decide *before* a slice exists — aggregate boundaries, bounded contexts, which processor, uniqueness
across aggregates, event design, error-handling policy — and the design anti-patterns.
**Language-neutral**: where Java and Kotlin differ, both are shown.

This is Trustworks guidance, not framework reference. Where it relies on a framework fact it links
into `references/llm/` (the framework's own docs) instead of restating it. Slice structure is
`rules/slice-design.md` (the law) and `references/slice/slice-model.md` (the anatomy); application
wiring is `references/stack/stack-contract.md`; code templates come from `/essentials:add-slice`.

## TOC
- [Philosophy](#philosophy)
- [Aggregate Design](#aggregate-design)
- [Bounded Contexts](#bounded-contexts)
- [Event Processor Selection](#event-processor-selection)
- [Uniqueness Enforcement](#uniqueness-enforcement)
- [Integration (Inbox/Outbox)](#integration-inboxoutbox)
- [Best Practices](#best-practices)
- [Anti-Patterns](#anti-patterns)
- [State-stored entities](#state-stored-entities)
- [Further Reading](#further-reading)

---

## Philosophy

### Events as the Single Source of Truth

Instead of UPDATE statements that discard history, store **immutable events** describing what
happened. Current state is derived by replaying events.

| New Requirement | CRUD | Event Sourcing |
|---|---|---|
| Show order history timeline | Add audit table, migration, backfill | New view projection — history exists |
| Alert when orders are stuck | Add timestamp columns, monitoring | Subscribe to events — timestamps exist |
| Add inventory reservation on order | Modify existing code, Dual Write Problem | New automation slice — zero changes to ordering code |
| Calculate average time-to-ship | Maybe, if you added audit columns | New projection — all data is there |
| Undo last 3 operations | Nearly impossible | Replay events excluding last 3 |
| Point-in-time state | Impossible unless built from day one | Replay one stream up to an `EventOrder`, or an aggregate type's events up to a `GlobalEventOrder` — never by timestamp ([Ordering Guarantees](../llm/LLM-postgresql-event-store.md#ordering-guarantees)) |

**The Dual Write Problem** — solved by event sourcing:

```java
// ❌ CRUD: fundamentally broken — partial failure leaves inconsistent state
order.setStatus("CONFIRMED");
orderRepository.save(order);       // Step 1: local DB
inventoryService.reserve(orderId); // Step 2: external API — can fail independently

// ✅ Event sourcing: single atomic write, subscribers react reliably
eventStore.appendToStream(ORDERS, orderId, new OrderConfirmed(orderId));
// A separate EventProcessor with at-least-once delivery handles inventory
```

**What event sourcing provides:** complete audit trail, time travel, natural decoupling,
future-proof data, safe experimentation, business insight.

### Core Principles

1. **Events as single source of truth** — immutable facts, not mutable rows
2. **Domain split by bounded context** — linguistic boundaries where terms have consistent meaning
3. **Slice-based implementation** — vertical slices, not horizontal layers (see `rules/slice-design.md`)
4. **Semantic types for domain clarity** — `OrderId`, not `String`; compile-time safety ([LLM-types.md](../llm/LLM-types.md))
5. **Asynchronous by default** — synchronous only where atomic consistency is genuinely required
6. **Explicit over implicit** — commands, events, and handlers all visible
7. **Optimistic concurrency** — version checks, not pessimistic locks

---

## Aggregate Design

### The Noun Trap

Don't model aggregates around nouns (Product, Customer, Order). Nouns attract unrelated
responsibilities, creating god-aggregates with thousands of events.

**Ask instead:**
- What are the use cases?
- What state must be consistent within a single transaction?
- What has an independent lifecycle?

**Example — split "Product" by lifecycle:**

```
ProductCatalog (ProductId)    ProductPricing (ProductId)    InventoryItem (ProductId)
├── EntryCreated              ├── PriceSet                  ├── StockReceived
├── DescriptionUpdated        ├── DiscountApplied           ├── StockReserved
└── CategoryChanged           └── PromotionStarted          └── StockShipped
(~50 events/lifetime)         (~200 events/year)            (~10K events/year — OK, designed for it)
```

All share the same `ProductId`. View projections join them when needed.

**Example — split "Order" by use-case phase:**

```
ShoppingCart (CartId)     Order (OrderId)         Payment (OrderId)       Shipment (ShipmentId)
├── ItemAdded             ├── OrderCreated        ├── PaymentInitiated    ├── ShipmentCreated
├── ItemRemoved           ├── OrderConfirmed      ├── PaymentCaptured     ├── ShipmentDispatched
└── CartSubmitted         └── OrderCancelled      └── PaymentFailed       └── ShipmentDelivered
(~10-20 events, done)     (~2-3 events, done)     (~3-5 events)           (~4-6 events)
```

### Choosing the Right Consistency Boundary

| Situation | Approach | Write style (`rules/slice-design.md` §R5) |
|---|---|---|
| Single entity, independent lifecycle | Traditional aggregate (Order, Product) | any |
| One invariant spanning two "natural" aggregates, bounded scope | Invariant-shaped aggregate (e.g. `SemesterEnrollments`) — below | any |
| Unbounded accumulation | Closing the books — below | aggregate style only |
| High-volume, needs optimisation | Any of the above + snapshots | aggregate style only |

Snapshots and closing the books are both built on `StatefulAggregateRepository`, i.e. the aggregate
style. A decider-style bounded context (the plugin's default) has neither: keep its streams short
with the lifecycle and use-case splits above instead
([Aggregate Snapshots](../llm/LLM-eventsourced-aggregates.md#aggregate-snapshots)).

**Closing the books:** for aggregates that accumulate indefinitely (loyalty points, ledgers), the
framework closes the current stream at a boundary and opens a new *generation* for the same business
id — declare `@AggregateClosingBooksPolicy` on the aggregate rather than hand-rolling period events
and snapshots. Aggregate style only. Trigger modes, policies and Spring wiring:
[LLM-eventsourced-aggregates.md § Closing the Books](../llm/LLM-eventsourced-aggregates.md#closing-the-books).

**Model around the invariant, not the nouns.** When one invariant spans two "natural" aggregates,
make the invariant's scope the aggregate. Course enrolment: "a course takes at most 20 students" and
"a student takes at most 10 courses per semester" cannot both be enforced atomically by separate
`Course` and `Student` aggregates; a `SemesterEnrollments` aggregate (id = the semester) holds both
and enforces them in one decision. Bound its stream by that scope (one semester) rather than by
splitting the invariant. When no bounded scope exists, the alternative is eventual consistency with
a compensating command. (This is sometimes called a "dynamic consistency boundary"; that name also
refers to a tag-based event-store feature Essentials does not provide — here it means only an
aggregate shaped by its invariant.)

---

## Bounded Contexts

A bounded context is a **linguistic boundary** where terms have consistent, unambiguous meaning.

### Discovery Steps

1. **Start with business capabilities** (verb phrases: "Enable shopping", "Fulfill orders")
2. **Look for language boundaries** — when the same word means different things, you have found one
3. **Map aggregates to contexts** — each aggregate belongs to the context owning its lifecycle
4. **Validate with Event Modeling** — pivotal events reveal context transitions

For an existing codebase, infer the contexts from the code instead: `references/slice/discovery-heuristics.md` §1 and `/essentials:slice-discover`.

### Context Integration Map (Retail Example)

```
Shopping ──CartSubmitted──▶ Fulfillment ──ItemsPacked──▶ Shipping
                            ▲           │                    │
                     PaymentReceived    OrderCreated    ShipmentDelivered
                            │           ▼                    │
                          Payments                        Returns
                            ▲                                │
                            └──────RefundCompleted───────────┘

Shopping ──CartSubmitted──▶ Inventory ◀── Fulfillment.ItemsPacked (deduct)
                (reserve)               ◀── Returns.ReturnReceived (restock)
```

| Source | Event | Consumers | Action |
|---|---|---|---|
| Shopping | CartSubmitted | Fulfillment, Inventory | CreateOrder, ReserveStock |
| Fulfillment | OrderCreated | Payments | InitiatePayment |
| Payments | PaymentReceived | Fulfillment | ConfirmOrder |
| Fulfillment | ItemsPacked | Shipping, Inventory | CreateShipment, DeductStock |
| Shipping | ShipmentDelivered | Returns | EnableReturnWindow |
| Returns | ReturnReceived | Inventory | RestockItems |
| Returns | RefundCompleted | Payments | RecordRefund |

**Communication between contexts:** ✅ events (async, decoupled) ✅ commands via the command bus —
between contexts deployed in the same service only (`DurableLocalCommandBus` is JVM-local; across
services, integrate through a translation slice) ❌ direct method calls ❌ shared database.

---

## Event Processor Selection

```
Does the handler call out of the service (Kafka, email, webhooks, another team's API),
or react to an event by issuing a follow-up command (automation)?
├─ YES → EventProcessor              (Inbox-backed, at-least-once, redelivery policy)
└─ NO  → it maintains a read model. Must it be current the moment the command API returns?
    ├─ YES → InTransactionEventProcessor  (same transaction as the append; also uniqueness)
    └─ NO  → ViewEventProcessor           (low latency, eventually consistent, replayable)
```

The slice skills apply this per slice kind, including which dependencies bundle each base class
takes and why a projection's handler takes `OrderedMessage` (`skills/essentials-view-slice/SKILL.md`
§2c–2d, `skills/essentials-automation-slice/SKILL.md`, `skills/essentials-translation-slice/SKILL.md`).
The comparison table (processing model, exclusivity, latency, consistency, replay) is
[LLM-postgresql-event-store.md § EventProcessor Framework](../llm/LLM-postgresql-event-store.md#eventprocessor-framework).

---

## Uniqueness Enforcement

For values that must be unique across **all** aggregates (tenant name, user email), use an
`InTransactionEventProcessor` plus a unique database constraint. Its handler runs inside the
transaction that appends the event, so a constraint violation rolls back the append as well and the
command fails. The full class shape (constructor, `getProcessorName()`,
`reactsToEventsRelatedToAggregateTypes()`) is in
[LLM-postgresql-event-store.md § InTransactionEventProcessor](../llm/LLM-postgresql-event-store.md#intransactioneventprocessor);
only the handler is shown here.

```sql
CREATE TABLE tenant_names (
    aggregate_id TEXT PRIMARY KEY,
    tenant_name  TEXT NOT NULL UNIQUE
);
```

```java
@MessageHandler
void on(TenantCreated event, OrderedMessage message) {
    jdbi.useHandle(h -> h.execute(
        "INSERT INTO tenant_names (aggregate_id, tenant_name) VALUES (?, ?)",
        event.tenantId().toString(), event.name()));
}
```

```kotlin
@MessageHandler
fun on(event: TenantCreated, message: OrderedMessage) {
    jdbi.useHandle<Exception> { h ->
        h.execute("INSERT INTO tenant_names (aggregate_id, tenant_name) VALUES (?, ?)",
                  event.tenantId.toString(), event.name)
    }
}
```

The insert joins the appending transaction only through a transaction-aware `Jdbi`. The one the
Essentials Spring Boot starter provides is (it wraps the `DataSource` in
`TransactionAwareDataSourceProxy`); a `Jdbi` built on the raw `DataSource` takes its own connection,
and the constraint then no longer protects the event.

**Rules:** `InTransactionEventProcessor` + a unique DB constraint. Do **not** query a read model in a
decider to check uniqueness — read models are eventually consistent, so the check is racy and it
violates CQRS.

---

## Integration (Inbox/Outbox)

Inbound and outbound integration both belong in a **translation slice** under
`external_systems/<system>/`: inbound dispatches with `sendAndDontWait` (Inbox — durable and retried,
so the receiving slice must be idempotent); outbound is an `EventProcessor` with a
`getInboxRedeliveryPolicy()` (Outbox). The pattern is `skills/essentials-translation-slice/SKILL.md`
and `/essentials:add-translation-slice`; the framework API is
[LLM-foundation.md § Inbox/Outbox Patterns](../llm/LLM-foundation.md#inboxoutbox-patterns).

---

## Best Practices

### Event Design

- ✅ Past-tense names (`OrderCreated`), immutable records/data classes, semantic types, all relevant
  data included
- ❌ No generic events (`EntityUpdated`), no technical details (`RowId`), no foreign-key dependencies
- An event's constructor parameter names are part of its persisted JSON contract — see
  [LLM-foundation.md § JSONSerializer](../llm/LLM-foundation.md#jsonserializer) before renaming one.

### Aggregate Design

- ✅ Small and focused, reference by id, one aggregate per transaction, coordinate via events
- ❌ No nested aggregates, no loading other aggregates in handlers, no DB-generated ids
- **Size guideline:** more than 3–4 events per common operation means the aggregate is too large

### Naming Wiring Classes

The slice naming convention (role → file name, e.g. a view's `OrderListRepository`) is
`references/slice/slice-model.md` §5. One Spring consequence of it:

- ⚠️ **Do not name a `@Configuration` class after the bean it declares.** Spring registers a `@Configuration` class's own bean under its decapitalised simple name, so `class OrderListRepository { @Bean DocumentDbRepository<…> orderListRepository(…) }` claims `orderListRepository` twice → `BeanDefinitionOverrideException` at context startup, failing every `@SpringBootTest` in the project. Name the class `OrderListRepositoryConfiguration` and keep the `@Bean` method name — that is what consumers inject by.

### Error Handling

- **Decision component** (decider, aggregate method, entity method): throw for business-rule
  violations — the command is rejected and nothing is written. A decider returns no event for an
  idempotent no-op.
- **EventProcessor:** catch business exceptions and issue a compensating command; let technical
  exceptions propagate so the Inbox retries.
- **Not every propagated exception is retried.** The queue consumer dead-letters some types on the
  first delivery whatever the redelivery policy says — among them `IllegalArgumentException`, which
  `FailFast.requireNonNull`/`requireTrue` and Kotlin's `require(...)` throw. A guard that may become
  true later must throw something retryable, or the policy must opt in with `alwaysRetryOn(...)`:
  [LLM-foundation.md § The built-in permanent-error list](../llm/LLM-foundation.md#the-built-in-permanent-error-list).

---

## Anti-Patterns

Structural anti-patterns (god deciders, multi-endpoint controllers, god event files, cross-slice
reach) are the slice law's territory — see `rules/slice-design.md` § Red flags. The ones below are
design and runtime defects.

### ❌ Loading Other Aggregates in Command Handlers
Creates coupling and violates the single consistency boundary. **Fix:** accept the command; let an
EventProcessor check the other aggregate asynchronously.

### ❌ Using Read Models in Command Handlers
Read models are eventually consistent, so the check is racy. **Fix:** derive state from events via
the Evolver; for uniqueness use an `InTransactionEventProcessor` + unique constraint
([Uniqueness Enforcement](#uniqueness-enforcement)).

### ❌ Synchronous Cross-Context Communication
Tight coupling, reduced autonomy. **Fix:** async event-based communication.

### ❌ Modifying Multiple Aggregates in One Transaction
Violates boundaries and widens the failure scope. **Fix:** modify one aggregate; coordinate via events.

### ❌ Large, Complex Aggregates
Poor performance, lock contention. **Fix:** split by lifecycle or use-case phase — see
[Aggregate Design](#aggregate-design).

### ❌ Event-store calls with no UnitOfWork

Every event-store call needs an active UnitOfWork. With the Essentials Spring Boot starters, that
UnitOfWork is a Spring transaction, and the framework opens it for you wherever it does the
dispatching:

- **Commands on the command bus.** The starter's `DurableLocalCommandBus` handles every `send`,
  `sendAsync` and queued `sendAndDontWait` command inside a UnitOfWork (the
  `UnitOfWorkControllingCommandBusInterceptor`). If the sender is already inside a Spring
  transaction, the command is handled in that transaction; otherwise a new one is started.
- **Event processors and the Inbox.** Handlers run inside the processor's own UnitOfWork.

A decider reached through the bus therefore needs **no** `@Transactional`. Register it on the bus:
in Java, an `EventStreamDecider<COMMAND, EVENT>` through `EventStreamDeciderAndAggregateTypeConfigurator`
(one `EventStreamAggregateTypeConfiguration` per aggregate type); in Kotlin, a
`kotlin.eventsourcing.Decider<COMMAND, EVENT>` through
`kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator`
([LLM-kotlin-eventsourcing.md](../llm/LLM-kotlin-eventsourcing.md)). An unregistered decider fails
with `NoCommandHandlerFoundException` on `send`.

**The defect** is an event-store call on a path none of that covers — a `@Service`,
`@RestController` or scheduled job calling `eventStore.appendToStream(...)` directly, with no
`@Transactional` and no `unitOfWorkFactory.usingUnitOfWork(...)`. It compiles, and throws
`NoActiveUnitOfWorkException` on the first call. The exception names the missing UnitOfWork, not the
missing transaction boundary, so the cause is not obvious from the stack trace.

**Fix:** in a slice project, send a command so the write goes through a registered decider. Where
code must touch the event store directly, give it a transaction boundary: `@Transactional` or an
explicit UnitOfWork ([LLM-spring-postgresql-event-store.md § Transaction Management](../llm/LLM-spring-postgresql-event-store.md#transaction-management)).
If you declare your own `DurableLocalCommandBus` bean, the starter no longer adds the interceptor,
so add `UnitOfWorkControllingCommandBusInterceptor` yourself.

---

## State-stored entities

A state-stored entity (persisted with JPA or Spring Data MongoDB, no event store) inverts two assumptions the
event-sourced style bakes in: a long-lived mutable object now holds the state, and the ORM pushes toward full
get/set access. Three traps follow from that. (The two that apply to every Essentials application — commands sent
with `sendAndDontWait` are persisted JSON, and `reactive-bean-post-processor-enabled=false` unwires every
`CommandHandler` — are in [LLM-foundation.md § Commands are persisted](../llm/LLM-foundation.md#commands-are-persisted)
and [LLM-spring-boot-starter-modules.md § Gotchas](../llm/LLM-spring-boot-starter-modules.md#gotchas).)

- **A mutable value object passed from a command into the entity is shared state.** The entity outlives the command,
  and both now point at the same object. Defensive-copy any mutable value crossing command → entity (a JPA
  `@Embedded` address, a mutable collection).
- **A public setter defeats the entity's invariant guard.** `setShipped(true)` silently bypasses the
  `markOrderAsShipped()` idempotency check that is the entity's reason to exist. Map fields directly
  (`@Access(AccessType.FIELD)` in JPA, field mapping in Mongo) and keep accessors package-private or absent; in
  Kotlin, `var … private set`.
- **A read-side repository that extends `JpaRepository`/`MongoRepository` can write** — it inherits `save` and
  `delete`. Extend the bare `org.springframework.data.repository.Repository` marker, which exposes only the methods
  you declare.

---

## Further Reading

| Topic | Reference |
|---|---|
| Slice structure law (four kinds, R1–R5, directory vocabulary, red flags) | `rules/slice-design.md` |
| Slice anatomy per kind, role→file mapping | `references/slice/slice-model.md` |
| Application wiring (S1–S11) | `references/stack/stack-contract.md` |
| EventStore, subscriptions, processors | [LLM-postgresql-event-store.md](../llm/LLM-postgresql-event-store.md) |
| Event-sourced aggregates, EventStreamDecider, snapshots, closing the books (Java) | [LLM-eventsourced-aggregates.md](../llm/LLM-eventsourced-aggregates.md) |
| Kotlin event-sourcing DSL (Decider/Evolver) | [LLM-kotlin-eventsourcing.md](../llm/LLM-kotlin-eventsourcing.md) |
| Spring transaction patterns | [LLM-spring-postgresql-event-store.md](../llm/LLM-spring-postgresql-event-store.md) |
| Foundation (UnitOfWork, Queues, Locks, Inbox/Outbox) | [LLM-foundation.md](../llm/LLM-foundation.md) |
| PostgreSQL Document DB (incl. the Java interop surface) | [LLM-postgresql-document-db.md](../llm/LLM-postgresql-document-db.md) |
| Spring Boot starters | [LLM-spring-boot-starter-modules.md](../llm/LLM-spring-boot-starter-modules.md) |
| Types & the semantic-type pattern | [LLM-types.md](../llm/LLM-types.md) |
| Traps index (symptom → the module doc that owns it) | [LLM-traps.md](../llm/LLM-traps.md) |
| Module index | [LLM.md](../llm/LLM.md) |
