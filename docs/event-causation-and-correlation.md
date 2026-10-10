# Event Causation

Scoped to `postgresql-event-store`, `spring-boot-starter-postgresql-event-store`, `kotlin-eventsourcing`,
`eventsourced-aggregates`, `foundation` (durable queues, Inbox/Outbox, durable command bus) and `reactive` (local
command bus). Raised while building `examples/essentials-webshop-demo`: asked how to *visualise how a decision was
made*, and found that the event store models the answer, the starter's documentation claims to provide it, and
nothing actually writes it.

**Causation only.** Every persisted event can record the id of the event that caused it (`causedByEventId`). This
document is about populating that, end to end. **Correlation is deliberately left to OpenTelemetry**: the
Micrometer tracing interceptors already carry W3C trace context through event metadata and message metadata (see
*Prior art* below), and a trace is the right tool for "what did this request do". The framework does not populate
the `correlation_id` column; it stays as it is, available to an application whose own `PersistableEventMapper`
wants to set it.

**Not in 0.60, and does not need a breaking release.** An earlier draft targeted "the next major" on two grounds:
the mapper SPI could take a new parameter, and the JDK 25 baseline would make `ScopedValue` available. 0.60 has
since shipped the JDK 25 baseline without this work (`docs/platform-upgrade-0.60.md`, D6, deliberately deferred
adopting `ScopedValue`), and the code already has an additive write hook — `PersistableEventEnricher` — that the
earlier draft missed. The design below is additive and can land in any release after 0.60.

Six items. F1 is documentation contradicting code. F2 is the functional gap. F3 and F4 are the two halves of the
mechanism — in-process and across asynchronous boundaries — and F4 is the hard one. F5 is the read path, which
barely exists. F6 is what the whole thing unlocks, stated honestly about where the chain breaks.

Nothing here is a correctness defect: the event store works, and events are complete without causation. It is a
*diagnosability* gap, which is the kind that stays invisible until someone has to answer "why did this happen?"
about production data.

---

## The current behaviour

`PersistedEvent` has modelled causation from the start:

```java
Optional<EventId> causedByEventId,   // "Unique id of the Event that caused this Event to exist"
```

The `caused_by_event_id` column is in every event stream table, created by
`SeparateTablePerAggregateTypePersistenceStrategy` as a schema contributor, and `PersistableEventBuilder` exposes
`setCausedByEventId`.

Every append — Kotlin `DeciderCommandHandlerAdapter`, Java `EventStreamDeciderCommandHandlerAdapter`,
`StatefulAggregateRepository`, the decider `CommandHandler`, `FlexAggregateRepository` — ends in
`PostgresqlEventStore.appendToStream(...)` and then `SeparateTablePerAggregateTypePersistenceStrategy.persist(...)`.
That method builds each row in two steps:

1. `PersistableEventMapper.map(aggregateId, config, event, eventOrder)` — one per event store, supplied by the
   application or the starter.
2. Every registered `PersistableEventEnricher.enrich(PersistableEvent)`, in order. The starter collects all
   enricher beans.

The Spring Boot starter's default mapper says, in its javadoc:

> The `PersistableEventMapper` adds additional information such as: event-id, event-type, event-order,
> event-timestamp, event-meta-data, correlation-id, tenant-id for each persisted event at a cross-functional
> level.

The implementation directly below that comment:

```java
return (aggregateId, aggregateTypeConfiguration, event, eventOrder) ->
        PersistableEvent.builder()
                        .setEvent(event)
                        .setAggregateType(aggregateTypeConfiguration.aggregateType)
                        .setAggregateId(aggregateId)
                        .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                        .setEventOrder(eventOrder)
                        .build();
```

No causation, no correlation id, no tenant. Every event in the webshop demo — and in any application using the
starter's default — has `caused_by_event_id` null.

### Prior art: tracing already travels this route

One thing does flow end to end today. It is both the template for causation and the reason correlation is out of
scope. With `management.tracing.enabled=true` the starters register two interceptors:

- `MicrometerTracingEventStoreInterceptor` is a `PersistableEventEnricher`. It injects the current trace context
  (W3C `traceparent` or B3) into the event's `EventMetaData`.
- `DurableQueuesMicrometerTracingInterceptor` injects trace context into `MessageMetaData` when a message is
  queued, and restores an observation around `HandleQueuedMessage` when it is delivered.

`EventProcessor` copies an event's metadata into the `EventReferenceOrderedMessage` it puts on its Inbox, so with
tracing on, a trace already crosses event → Inbox → handler, and every event records the trace it was written in.
Causation needs the same two carriers — an enricher on the write side, a queue interceptor at every hand-off —
with a different payload.

## F1 — The starter's default mapper documents behaviour it does not have

*Documentation defect. Fixed as part of F3, but worth listing because it is why nobody noticed.*

A reader has to open the lambda to discover the sentence above is aspirational. The `PersistableEventMapper`
interface javadoc makes a similar claim about the SPI, and there it is fair — a mapper is a legitimate place for
those fields. It is the default implementation that has none of them. Once the causation enricher exists (F3),
the default mapper's javadoc should name it as the source of `causedByEventId`, point to the tracing interceptor
for trace context, and stop claiming correlation id and tenant, which nothing in the default setup sets.

## F2 — Nothing propagates causation, and no single component can

*The functional gap.*

Causation is inherently ambient. The component that knows the cause is not the component that writes the event:

| Layer | Knows the cause? | Writes the event? |
|---|---|---|
| `EventProcessor` delivery (`EventReferenceResolvingMessageConsumer`) | **yes** — it resolves the triggering `PersistedEvent` | no |
| `@MessageHandler` method | yes, the deserialized event — but not its `EventId` | no |
| Command bus / decider | no — a `Decider` is `(command, events) -> event?` and must stay pure | no |
| Command handler adapters and aggregate repositories | no — the command arrives alone | call `appendToStream` |
| `PersistableEventMapper` / `PersistableEventEnricher` | no — they receive the event being written | **yes** |

The fix therefore cannot be local to any one of them. Something has to carry "what caused the work I am
currently doing" from the delivery to the persistence step. Every component in that table is *correctly*
ignorant — the purity of the decider especially is a property to keep, not an obstacle to remove.

Two facts about *when* the write happens constrain where that something can live:

- `StatefulAggregateRepository`, the decider `CommandHandler` and `FlexAggregateRepository` append lazily, in
  `UnitOfWorkLifecycleCallback.beforeCommit`. The event is written when the UnitOfWork commits, not when the
  handler calls the repository. A binding that ends when the handler method returns misses those events.
- Since 0.60 a handler declared `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` runs with no UnitOfWork at
  all and opens its own. The webshop's `CaptureFundsWhenPackagedPolicy` does exactly that, so it can commit
  `FundsCaptureRequested` before making a blocking call to the gateway (`docs/payment-async-capture.md`). There is
  no ambient UnitOfWork to hang a cause on when the handler starts.

## F3 — The in-process mechanism: a `ScopedValue` read by an enricher

*The design.*

**Capture: a `ScopedValue<CausationContext>`** in `foundation`, holding the causing `EventId`. The framework binds
it at each delivery site it owns, around everything that delivery runs — including the UnitOfWork commit:

```java
static final ScopedValue<CausationContext> CAUSE = ScopedValue.newInstance();

ScopedValue.where(CAUSE, CausationContext.causedBy(persistedEvent))
           .run(() -> patternMatchingMessageHandlerDelegate.accept(resolvedMessage));
```

`java.lang.ScopedValue` is a final API on the JDK 25 baseline 0.60 ships, with no `--enable-preview`. It wins
over a `ThreadLocal` because the binding is immutable, is released at the end of the dynamic extent rather than
needing a `remove()` in a `finally`, and cannot leak into a pooled thread's next task — the failure mode that
matters for a framework handing threads back to Reactor and to durable-queue workers. It would be the first
`ScopedValue` in the codebase, which is the one-feature-per-change adoption D6 asked for. The framework's own
`ThreadLocal`s (`GenericHandleAwareUnitOfWorkFactory`, the two tracing interceptors) stay as they are.

The binding sites, in order of importance:

1. **`AbstractEventProcessor.EventReferenceResolvingMessageConsumer.accept`**, around phase 2. This is the only
   place that holds the resolved `PersistedEvent` — `resolveEventReference` already loads it with one
   `fetchStream` per delivery, then discards everything except the deserialized payload. It must return the
   `PersistedEvent` as well. Binding here encloses `PatternMatchingMessageHandler.invokeMethod`, so it covers a
   `REQUIRED` handler's commit and a `NONE` handler's own `withUnitOfWork` alike.
2. **`InTransactionEventProcessor`**, around its direct handler call. `ViewEventProcessor` rejects `NONE` handlers
   and projections do not append events, so it can be left until someone needs it.
3. **Durable-queue delivery**, re-binding from `MessageMetaData` (F4). The engine-agnostic place is a
   `DurableQueuesInterceptor` around `HandleQueuedMessage`, which every engine — default, centralized fetcher,
   shard-owned — runs; `Inboxes.handleMessage` opens its UnitOfWork inside that chain, so the commit is covered.

Not in a `MessageHandlerInterceptor`: under `REQUIRED`, `invokeMethod` commits after the interceptor chain
returns, so a binding there misses every lazily appended event.

**Explicit binding and reading.** The same type is public, for the two cases the framework cannot see:

- `CausationContext.where(eventId).run(...)` binds a cause the application determined itself — a webhook that
  looked up the event it answers (F4), or a policy that chose a different cause for a join (below).
- `CausationContext.current()` returns the bound cause, read-only, for logging or for a policy that wants to
  record it. Deciders are never given it: they stay `(command, events) -> event?`.

**Write: a `PersistableEventEnricher` that reads the `ScopedValue`.** It sets `causedByEventId` on the
`PersistableEvent` *only where the mapper left it empty*, and the starter registers it by default. Nothing in any
SPI changes: a consumer's own mapper keeps whatever it sets, and a mapper stays a pure function that is
unit-testable without a runtime. The one component that reads
ambient state is framework-owned and small.

**On by default, one switch to turn it off.** Writing causation costs about 37 bytes per event, in a column that
already exists, plus one metadata entry per queued message; binding a `ScopedValue` and setting one field cost
next to nothing. Against that, a cause that was not written at the time can never be recovered, so a default-off
setting means the data is missing exactly when someone first needs it. The starter therefore writes causation
unless `essentials.eventstore.causation.enabled=false`. That one property governs every part together — the
delivery-site bindings, the enricher, the queue interceptor and the `LocalCommandBus` re-binding — so an
installation cannot end up with causation half on, written in-process but silently dropped at every queue.

**The cause of a join.** A policy that waits for two inputs — `CaptureFundsWhenPackagedPolicy` emits
`FundsCaptureRequested` when the second of `CreditCardHoldPlaced` and `OrderPackagingRequested` arrives — has two
causes and one column. **The cause is the event whose delivery completed the decision**, because that is the one
bound when the event is written. Which input that is depends on arrival order, and the other input's `EventId` is
not stored anywhere; the work-item row holds domain state, not event ids. A policy that wants a different cause
records the id it needs and binds it explicitly with `CausationContext.where(...)`. `caused_by_event_id` stays
single-valued: a list of causes would change the schema and every reader for a case the explicit binding already
covers.

**Rejected: an explicit parameter on the mapper.** The earlier draft's design:

```java
PersistableEvent map(Object aggregateId,
                     AggregateEventStreamConfiguration config,
                     Object event,
                     EventOrder eventOrder,
                     EventAppendContext context);
```

It makes causation a value the mapper is given rather than one the persistence step reaches for, which is
cleaner. But it breaks every mapper — the starter's lambda, about forty inline lambdas in the
`postgresql-event-store` and `eventsourced-aggregates` integration tests, `TestPersistableEventMapper`, and the
`implements PersistableEventMapper` samples in four READMEs and `LLM/LLM-postgresql-event-store.md`. It also
does not remove the ambient capture, only moves its last step: `persist(...)` is not handed the `AppendToStream`
operation, and the lazy appends happen inside `beforeCommit`, so the context would still have to be fetched from
the `ScopedValue` when the operation is built. The enricher gets the same result without the break.

**Rejected: an attribute on `UnitOfWork`.** The earlier draft kept it as a fallback. Under `UnitOfWorkMode.NONE`
there is no UnitOfWork at the moment the cause is known, so it fails for exactly the handlers 0.60 added.

The catch to design around, and the reason F4 exists: a `ScopedValue` binding covers the *dynamic extent of a
call*. It does not cross a thread hand-off, and nothing in the JDK makes it cross a process or a queue. That is no
worse than the UnitOfWork, which is itself thread-bound.

## F4 — Crossing asynchronous boundaries

*The hard half. A solution that skips this produces causation chains that break at exactly the interesting
moments.*

No in-JVM mechanism survives a durable queue, and in this framework almost every hop is one. `EventProcessor` is
not a direct subscription: the subscription puts an `EventReferenceOrderedMessage` on the processor's Inbox, and
the handler runs when the Inbox delivers it — possibly minutes later, on another thread, on another instance. So
the cause has to **travel with the message** and be re-bound on the consuming side:

- **The `EventProcessor` Inbox hop** needs nothing new. The message is resolved back into the `PersistedEvent`
  before the handler runs (F3, binding site 1), and that event *is* the cause. The earlier draft proposed adding
  the `EventId` to `EventReferenceOrderedMessage` to avoid "a query per event"; that query already happens on
  every delivery. Adding the id only matters if something other than the resolving consumer needs it.
- **Everything else queued while a cause is bound** — `Inbox.addMessageReceived`, `Outbox.sendMessage`, and
  `DurableLocalCommandBus.sendAndDontWait` — goes through `DurableQueues.queueMessage`. A `DurableQueuesInterceptor`
  writes the cause into `MessageMetaData` on `QueueMessage(s)` when one is bound, and re-binds it around
  `HandleQueuedMessage` on delivery, the way the tracing interceptor handles `traceparent`. This covers the
  durable command bus without changing it: it queues `Message.of(command)` with empty metadata, and the
  interceptor fills it in. Today `MessageMetaData` has a single framework key, `FENCED_LOCK_TOKEN`; the new key
  should be namespaced so it cannot collide with application keys.
- **The plain `LocalCommandBus.sendAndDontWait`** in `reactive` has no queue and no metadata — it hands the
  command to a Reactor scheduler, and the binding stays behind on the sending thread. It captures
  `CausationContext.current()` at send and re-binds it on the scheduler thread around the handler call. A few
  lines in one class, so that "a command carries its cause" holds on both buses rather than only the durable one.
  `send()` needs nothing on either bus: the handler runs on the caller's thread, inside the binding.
- **Process boundaries (HTTP, Kafka)** carry no framework metadata, and connecting work across processes is what
  trace context is for. But one cross-process causation matters inside a single service, and the webshop's
  capture flow shows it: the webhook builds a fresh `RecordCaptureOutcome` from the HTTP body and adds it to an
  Inbox, so `FundsCaptured` has no cause unless the application supplies one. The application *can* — the webhook
  carries the idempotency key that identifies the `FundsCaptureRequested`, so the cause is a domain lookup,
  followed by `CausationContext.where(eventId)` around `addMessageReceived`, which the queue interceptor then
  carries like any other binding.

A chain that is correct in-process and silently null across a queue is worse than no chain at all, because it
looks complete.

## F5 — The read path is almost absent

*Additive, and useless without F2–F4, but part of the whole.*

`EventStore` has no query for "events caused by this event". The column is written — null — and read in exactly
one place: `ApiPersistedEvent` exposes `causedByEventId`, and is returned by the admin API's closing-the-books
generation stream. The admin console does not render it. The whole solution needs:

- **A causation walk forward** — "what did this event cause?" — querying `caused_by_event_id` across aggregate
  types in global order. This crosses stream tables: the table-per-aggregate-type strategy makes it a union
  query, and doing it efficiently is the real work in this item, not the API shape.
- **A causation walk backward** — "what caused this?" — which has the same problem from the other side:
  `loadEvent` needs the `AggregateType` as well as the `EventId`, and a bare `causedByEventId` does not say which
  table to look in. Either a union lookup by event id, or record the cause's aggregate type alongside its id; the
  second is a schema change and should be argued for on measured cost, not assumed. Each per-table lookup is
  already indexed: every stream table has `UNIQUE(event_id)`. **The backward walk needs no new index.**
- **An opt-in index on `caused_by_event_id`, for the forward walk only.** Since the backward walk is served by the
  existing `event_id` index, the new index is the price of one feature — "what did this event cause?" — not of
  causation itself, and an installation that never uses that feature should not pay for it. So:
  - **Off by default**, behind one global property, `essentials.eventstore.causation.index-enabled`. A
    per-AggregateType setting can follow if someone needs it.
  - **Harness-managed.** When enabled, `SeparateTablePerAggregateTypePersistenceStrategy.schemaChangesFor` adds a
    `SchemaChange.repeatable(...)` next to the existing `event-stream-tenant-index`, applied once per stream
    table. Stream tables are created per AggregateType, including types added after deployment, and a
    harness-managed index is the only option that covers those automatically — on a new, empty table the build is
    instant. A DBA-only recipe was rejected because every later AggregateType would silently lack the index;
    always-on was rejected because it would force a blocking build and, in validate mode, a DBA step on every
    installation at upgrade, for a feature many will never open.
  - **Partial**: `CREATE INDEX IF NOT EXISTS … ON <table> (caused_by_event_id) WHERE caused_by_event_id IS NOT
    NULL`. Historical rows are all null and stay out of it, and so do new events without a cause (anything
    started by an HTTP command), so both its size and its per-insert cost track only the events that have one.
  - **A recipe for large existing tables.** The harness runs a contributor's changes in one transaction, so it
    cannot use `CREATE INDEX CONCURRENTLY`, and enabling the property on a large table blocks writes while the
    index builds. Because the statement is `IF NOT EXISTS`, the way round is for the DBA to build the same index
    by hand with `CONCURRENTLY` first, using the exact name and predicate the framework would; enabling the
    property afterwards then finds it in place and only records the change in the ledger. The migration notes
    must give that statement verbatim. In `essentials.schema.mode=validate` the property adds a change the
    deployment does not yet have, so it fails to start until the emitted script is applied — the recipe applies
    there in the same way.
  - **The forward walk refuses without it.** With the property off, the forward walk fails with a message naming
    the property and the recipe, rather than quietly running a sequential scan over every stream table.
- **Admin API operations and a console view**, remembering the house rule that an admin operation lives in three
  synced places (`*Api` SPI, `EssentialsAdminApiSpec` mapping, controller in `spring-boot-starter-admin-api`).

## F6 — What it unlocks

*The motivation. None of this is possible today.*

"Why did this event happen?" becomes a query rather than an investigation. Drawn honestly against the webshop
demo as it is now, causation gives this:

```
CheckOutRequested · PaymentDetailsAdded · OrderPlaced                     (sales)
└── CreditCardHoldPlaced         ← whichever input completed the join     (HoldFundsOnOrderPlacedPolicy)

POST /api/shipping/orders/{id}/package                                    (a person)
└── OrderPackagingRequested      ← no cause, correctly                    (PackageOrderDecider)

CreditCardHoldPlaced · OrderPackagingRequested
└── FundsCaptureRequested        ← whichever arrived second               (CaptureFundsWhenPackagedPolicy)
    ── HTTP to the gateway, webhook back ──
    └── FundsCaptured            ← bound by the webhook from the idempotency key
                                                                          (Inbox → RecordCaptureOutcomeDecider)
```

That is three short chains, not one tree, and the breaks are real rather than defects. A person deciding to
package an order *is* the cause of `OrderPackagingRequested`, and no event id can stand for them. And the two
policies are joins, so each records the input that completed it (F3).

Three questions, three tools, and this document is about the first:

- **Why did this event happen?** Causation — the chain of facts that led to it, across contexts that never
  called each other.
- **What did this request do?** The trace. With tracing on, every event already records the trace it was written
  in, so the tracing backend and the event store are joinable on the trace id.
- **What happened to this order?** The domain — the order id, which every context in the webshop keys on.
  Neither causation nor a trace survives a human step, and neither should be stretched to.

With causation in place, in ascending order of effort: a "why is this in this state?" view in the admin console,
walking back from any event; after-the-fact analysis of a policy's behaviour — which input completed each join,
what each delivery produced — without adding logging to the policy; and, combined with the trace id in event
metadata, a jump from a span in the tracing backend to the exact events it caused.

That is the honest answer to how a decision was made in an event-sourced system: not a stack trace, but the
chain of facts that led to it.

## Compatibility

Nothing breaks, so this can ship in a minor release:

- **No SPI changes.** `PersistableEventMapper`, `PersistedEvent` and `EventReferenceOrderedMessage` are untouched.
  The enricher and the queue interceptor are new default beans; `MessageMetaData` gains one namespaced framework
  key.
- **Consumers with their own mapper** keep whatever they set, causation and correlation both. The enricher only
  fills an empty `causedByEventId`; the framework must never overwrite a causation a mapper has already decided.
- **Persisted data is untouched.** Existing rows keep their nulls and nothing should be backfilled: a causation
  invented after the fact is a lie about what happened, and the whole value of the field is that it is not.
- **Causation starts being written on upgrade** (F3). New rows get a `caused_by_event_id` where there is a cause.
  Nothing reads the column in a way this could break, and `essentials.eventstore.causation.enabled=false`
  restores today's behaviour.
- **No schema change unless asked for.** The index is opt-in (F5), so an upgrade alone runs no DDL. Enabling it
  is the one operational step, and the migration notes — in the style of `docs/MIGRATION-0.60.md` — must carry
  the concurrent-build recipe for large tables and the validate-mode note.

## Decided

- **Causation only; correlation is OpenTelemetry's.** The tracing interceptors already carry trace context
  through event and message metadata. The framework does not populate `correlation_id`.
- **An enricher, not a mapper parameter** (F3). Same result, no break.
- **The cause of a join is the delivering event**, with an explicit `CausationContext.where(...)` override for a
  policy that records a different one (F3). `caused_by_event_id` stays single-valued.
- **A command carries its cause on every bus** (F4): synchronously by the binding, on the durable bus by the
  queue interceptor, on the plain `LocalCommandBus` by re-binding across the scheduler hop. Deciders never see it;
  other code may read it with `CausationContext.current()`.
- **Causation is written by default** (F3), with `essentials.eventstore.causation.enabled=false` turning every
  part of it off together.
- **The `caused_by_event_id` index is opt-in, harness-managed and partial** (F5): off by default behind
  `essentials.eventstore.causation.index-enabled`, applied to every stream table including future ones, with a
  documented concurrent pre-build for large tables. Only the forward walk needs it, and it refuses to run without
  it.
- **`eventsourced-aggregates` participates unchanged.** Every one of its repositories appends through
  `EventStore.appendToStream`, so the enricher applies. Its lazy, commit-time appends are what put the binding
  site outside the UnitOfWork (F2, F3).

## Open questions

1. **What happens when no cause is bound?** Recommended: always legal, yielding today's behaviour. A framework
   that throws because it cannot determine causation would be worse than one that omits it — but that does mean
   a missing binding fails silently, so the test in the scope below matters more than usual.

## Scope

Whole solution, in dependency order. Each step is testable on its own, and none of them is useful shipped alone.

1. **`CausationContext` and its `ScopedValue`** in `foundation`, with `where(...)` for explicit binding and
   `current()` for reading.
2. **Bind it at the event-processor delivery sites**: `EventReferenceResolvingMessageConsumer.accept` (keeping
   the resolved `PersistedEvent` instead of discarding it) and `InTransactionEventProcessor`.
3. **The causation enricher** in `postgresql-event-store`, registered by the starter, filling only an empty
   `causedByEventId`, with `essentials.eventstore.causation.enabled` governing it and every binding; fix the
   default mapper's javadoc (F1).
4. **Propagation across hand-offs**: a `DurableQueuesInterceptor` that writes the cause on queueing and re-binds it
   on `HandleQueuedMessage`, and the re-binding in `LocalCommandBus.sendAndDontWait`.
5. **Read path**: the two causation walks; the opt-in partial index as a harness schema change behind
   `essentials.eventstore.causation.index-enabled`, with the forward walk refusing when it is off; `EventStoreApi`
   / `EssentialsAdminApiSpec` / controller, console view.
6. **Migration notes** — causation on by default, the index property, the verbatim concurrent-build statement —
   and a worked example in the webshop demo, including the webhook binding the cause from the
   idempotency key.

The test that decides whether this works is not a unit test: a `FundsCaptured` produced through the webhook and
the Inbox must carry the id of the `FundsCaptureRequested` that a `NONE` policy committed before calling the
gateway, and a `CreditCardHoldPlaced` appended inside a `REQUIRED` handler's UnitOfWork must carry the id of the
event that triggered it. The webshop's capture flow exercises both, which is a good reason to build it there
first.
