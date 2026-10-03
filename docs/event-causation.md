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

**About the webshop.** The webshop demo, and the flows this document uses as its running example
(`CaptureFundsWhenPackagedPolicy`, the capture webhook, `examples/essentials-webshop-demo/docs/payment-async-capture.md`),
live on the `presentation` branch and are not on the `release/0.60` line yet. The examples below are still the
clearest illustration of the problem, but the test that decides whether the design works is a framework
integration test that reproduces the same two shapes (see *Implementation plan*, phase 7). The worked example in
the webshop follows when the demo reaches the release line.

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
`setCausedByEventId`. The CDC path (`PgOutputToPersistedEventConverter`) already reads the column, so CDC-delivered
events will carry causation as soon as it is written.

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
| `EventProcessor` delivery (`EventReferenceResolvingMessageConsumer`, `InTransactionEventProcessor`) | **yes** — it resolves or receives the triggering `PersistedEvent` | no |
| Raw event-store subscriptions (`PersistedEventHandler`, `TransactionalPersistedEventHandler` and their `PatternMatching*` variants) | **yes** — they are handed the `PersistedEvent` | no — but the handler they call may append |
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
  handler calls the repository. A binding that ends when the handler method returns misses those events. (The
  two decider adapters, Java `EventStreamDeciderCommandHandlerAdapter` and Kotlin `DeciderCommandHandlerAdapter`,
  append eagerly and have no such problem.)
- Since 0.60 a handler declared `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` runs with no UnitOfWork at
  all and opens its own. The webshop's `CaptureFundsWhenPackagedPolicy` does exactly that, so it can commit
  `FundsCaptureRequested` before making a blocking call to the gateway. There is no ambient UnitOfWork to hang a
  cause on when the handler starts.

## F3 — The in-process mechanism: a `ScopedValue` read by an enricher

*The design.*

**Capture: a `ScopedValue<CausationContext>`** in `foundation`, holding the causing `EventId`. `EventId` lives in
`foundation-types`, which `foundation` already depends on. The framework binds it at each delivery site it owns,
around everything that delivery runs — including the UnitOfWork commit where it can:

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

**Bindings nest, and the innermost one wins.** That is `ScopedValue`'s own semantics, and it is the whole
precedence rule: an explicit `CausationContext.where(...)` inside a handler overrides the delivery's binding; a
delivery site that resolves an event overrides a cause re-bound from message metadata around it (F4). No
component needs to know about any other to get this right.

The binding sites, in order of importance:

1. **`AbstractEventProcessor.EventReferenceResolvingMessageConsumer.accept`**, around phase 2. This is the only
   place that holds the resolved `PersistedEvent` — `resolveEventReference` already loads it with one
   `fetchStream` per delivery, then discards everything except the deserialized payload. It must return the
   `PersistedEvent` as well. Binding here encloses `PatternMatchingMessageHandler.invokeMethod`, so it covers a
   `REQUIRED` handler's commit and a `NONE` handler's own `withUnitOfWork` alike.
2. **Durable-queue delivery**, re-binding from `MessageMetaData` (F4). The engine-agnostic place is a
   `DurableQueuesInterceptor` around `HandleQueuedMessage`, which every engine — default, centralized fetcher,
   shard-owned — runs; `Inboxes.handleMessage` opens its UnitOfWork inside that chain, so the commit is covered.
3. **`InTransactionEventProcessor.invokeHandler`**, around its handler call. It already has the `PersistedEvent`.
   `ViewEventProcessor` rejects `NONE` handlers and projections do not append events, but an in-transaction
   processor can append, so it is bound from the start rather than left for later.
4. **Raw event-store subscriptions**, which are public API and which applications use directly:
   - `PersistedEventSubscriber` (asynchronous subscriptions), around `handleWithBackPressure(e)`, per event.
   - `ExclusiveInTransactionSubscription` and `NonExclusiveInTransactionSubscription`, around
     `eventHandler.handle(event, unitOfWork)`, per event.
   - `BatchedPersistedEventSubscriber` is **not** bound. A batch has as many causes as it has events and one
     UnitOfWork; binding any one of them would be a guess. A batch handler that appends per event binds
     explicitly with `CausationContext.where(event.eventId())`.

Not in a `MessageHandlerInterceptor`: under `REQUIRED`, `invokeMethod` commits after the interceptor chain
returns, so a binding there misses every lazily appended event.

**Lazy appends capture the cause when the aggregate joins the UnitOfWork.** Binding around the commit (site 1)
works when the delivery owns the UnitOfWork. It does not work for the in-transaction sites (3, 4), and the
failure there is worse than a null. An in-transaction handler runs inside the *appending* UnitOfWork: if it loads
an aggregate through `StatefulAggregateRepository` and changes it, the resulting events are written in
`beforeCommit` of that outer UnitOfWork — after the per-event binding has ended, and inside whatever binding the
outer work had. They would be recorded as caused by whatever caused the outer work — one step too far back —
which is a wrong answer that looks right. So the three lazily appending repositories — `StatefulAggregateRepository`, the decider
`CommandHandler` and `FlexAggregateRepository` — capture `CausationContext.current()` at the moment they register
the aggregate with the UnitOfWork, and re-bind that captured value around their own `appendToStream` in
`beforeCommit`. This is internal to each repository, changes no API, and makes every lazy append independent of
where the commit happens to run. If one aggregate instance is touched under two different causes in the same
UnitOfWork, the first cause wins; that is documented rather than solved, since it needs an in-transaction chain
that loops back to the same aggregate.

**Explicit binding and reading.** The same type is public, for the cases the framework cannot see:

- `CausationContext.where(eventId).run(...)` / `.call(...)` binds a cause the application determined itself — a
  webhook that looked up the event it answers (F4), a policy that chose a different cause for a join (below), or a
  batch handler (above).
- `CausationContext.current()` returns the bound cause as an `Optional<EventId>`, read-only, for logging or for a
  policy that wants to record it. Deciders are never given it: they stay `(command, events) -> event?`.

**Write: a `PersistableEventEnricher` that reads the `ScopedValue`.** It sets `causedByEventId` on the
`PersistableEvent` *only where the mapper left it empty*, and the starter registers it by default. Nothing in any
SPI changes: a consumer's own mapper keeps whatever it sets, and a mapper stays a pure function that is
unit-testable without a runtime. The one component that reads ambient state is framework-owned and small.

**On by default, one switch to turn it off.** Writing causation costs about 37 bytes per event, in a column that
already exists, plus one metadata entry per queued message; binding a `ScopedValue` and setting one field cost
next to nothing. Against that, a cause that was not written at the time can never be recovered, so a default-off
setting means the data is missing exactly when someone first needs it. The starter therefore writes causation
unless `essentials.eventstore.causation.enabled=false`. That one property governs both components that *write*
anything — the enricher and the queue interceptor — so an installation cannot end up with causation half on,
written in-process but silently dropped at every queue. The bindings themselves stay unconditional: they persist
nothing, cost next to nothing, and keeping them on means `CausationContext.current()` behaves the same whether or
not the installation records causation.

**The cause of a join.** A policy that waits for two inputs — `CaptureFundsWhenPackagedPolicy` emits
`FundsCaptureRequested` when the second of `CreditCardHoldPlaced` and `OrderPackagingRequested` arrives — has two
causes and one column. **The cause is the event whose delivery completed the decision**, because that is the one
bound when the event is written. Which input that is depends on arrival order, and the other input's `EventId` is
not stored anywhere; the work-item row holds domain state, not event ids. A policy that wants a different cause
records the id it needs and binds it explicitly with `CausationContext.where(...)`. `caused_by_event_id` stays
single-valued: a list of causes would change the schema and every reader for a case the explicit binding already
covers.

**When no cause is bound, nothing is written.** That is always legal and yields today's behaviour: an event
started by an HTTP request, a scheduler or a person has no causing event, and saying so is correct. The framework
never throws because it cannot determine causation. The cost is that a *missing* binding fails silently, which is
why the integration test in phase 7 asserts specific causes on specific events rather than "some cause is set".

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
  every delivery. If the event reference happened to be queued while some other cause was bound, the queue
  interceptor re-binds that stale cause around the delivery — and the resolving consumer's own binding, being
  innermost, overrides it. Correct without a special case.
- **Everything else queued while a cause is bound** — `Inbox.addMessageReceived`, `Outbox.sendMessage`, and
  `DurableLocalCommandBus.sendAndDontWait` — goes through `DurableQueues.queueMessage`. A
  `CausationDurableQueuesInterceptor` writes the cause into `MessageMetaData` on `QueueMessage` and
  `QueueMessages` when one is bound, and re-binds it around `HandleQueuedMessage` on delivery, the way the tracing
  interceptor handles `traceparent`. This covers the durable command bus without changing it: it queues
  `Message.of(command)` with empty metadata, and the interceptor fills it in. Details that matter:
  - **The key is namespaced**, `essentials.causedByEventId`, so it cannot collide with application keys. Today
    `MessageMetaData` has a single framework key, `FENCED_LOCK_TOKEN`.
  - **The interceptor never overwrites the key.** A message whose metadata already carries a cause — set by the
    application, or carried over from a redelivery — keeps it.
  - **On delivery it reads only `getMessage().getMetaData()`.** On the shard-owned engine the `HandleQueuedMessage`
    interceptor receives a partial `QueuedMessage` whose `getId()` and `getTotalDeliveryAttempts()` throw (see the
    `ShardOwnedDurableQueues` javadoc). The metadata itself survives: `MessageEnvelope` serializes it with the
    payload.
  - **Its position in the interceptor chain does not matter for correctness.** In every engine — default
    (`DefaultDurableQueueConsumer`), centralized fetcher (`CentralizedMessageFetcher`) and shard-owned
    (`ShardOwnedDurableQueues.handleWithInterceptors`) — the chain's terminal action is the queue's message
    handler, and the UnitOfWork is opened inside it: by `Inboxes.handleMessage`, or by the command bus's
    `UnitOfWorkControllingCommandBusInterceptor`. No engine opens a UnitOfWork outside the `HandleQueuedMessage`
    chain, so any interceptor around it encloses the commit. The phase 5 tests pin this per engine, so a future
    engine that wraps delivery in a UnitOfWork from outside fails a test instead of silently dropping causes.
  - **It is registered by the event-store starter**, as a `DurableQueuesInterceptor` bean. The queue starters
    (`spring-boot-starter-postgresql`, `spring-boot-starter-postgresql-queue-shard-owned`) already collect every
    `DurableQueuesInterceptor` bean, and without an event store there are no causing events to carry.
- **The plain `LocalCommandBus`** in `reactive` needs no change, and the earlier draft was wrong to say it did.
  `sendAndDontWait` builds `Mono.fromCallable(...).publishOn(boundedElastic()).subscribe()`; `fromCallable` runs
  its callable when it is subscribed, and `subscribe()` is called on the sending thread, so the handler runs on
  the sender's thread, inside the sender's binding. (`publishOn` only moves the *result* signal.) `sendAsync`
  returns the same shape unsubscribed: the handler runs on whichever thread subscribes, so the cause survives when
  the caller subscribes inside the binding — the normal case — and is lost when the `Mono` is stored and
  subscribed later. That limitation is documented rather than fixed: `reactive` depends only on `shared` and
  cannot see `CausationContext`, and adding a generic context-propagation hook to the command bus for this one
  case is not worth it. Tests in `foundation` pin both behaviours, so if `sendAndDontWait` is ever made truly
  asynchronous the causation test fails and the change has to carry the cause.
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
  already indexed: every stream table is created with `UNIQUE(event_id)`. **The backward walk needs no new
  index**, so the union lookup is the choice until measurement says otherwise.
- **Both walks see only the AggregateTypes registered with this event store.** The persistence strategy knows
  the stream tables it has been configured with, not every table in the database. In a single service that is
  every type that can be a cause; a cause written by another service's event store sharing the database is not
  found, and the walk says so rather than returning a silently short chain.
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
  synced places (`*Api` SPI, `EssentialsAdminApiSpec` mapping, controller in `spring-boot-starter-admin-api`),
  plus the committed OpenAPI document that `admin-api-client-java` is generated from.

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

- **No SPI changes.** `PersistableEventMapper`, `PersistedEvent`, `EventReferenceOrderedMessage`, `CommandBus` and
  `LocalCommandBus` are untouched. The enricher and the queue interceptor are new default beans;
  `MessageMetaData` gains one namespaced framework key. The read-path methods are added to `EventStore` as
  default methods, so an application's own `EventStore` implementation or test double keeps compiling.
- **Consumers with their own mapper** keep whatever they set, causation and correlation both. The enricher only
  fills an empty `causedByEventId`; the framework must never overwrite a causation a mapper has already decided.
- **Persisted data is untouched.** Existing rows keep their nulls and nothing should be backfilled: a causation
  invented after the fact is a lie about what happened, and the whole value of the field is that it is not.
- **Causation starts being written on upgrade** (F3). New rows get a `caused_by_event_id` where there is a cause.
  Nothing reads the column in a way this could break, and `essentials.eventstore.causation.enabled=false`
  restores today's behaviour.
- **Queued messages gain one metadata entry** while a cause is bound. A consumer that asserts on the exact
  contents of `MessageMetaData` will see it.
- **No schema change unless asked for.** The index is opt-in (F5), so an upgrade alone runs no DDL. Enabling it
  is the one operational step, and the migration notes — in the style of `docs/MIGRATION-0.60.md` — must carry
  the concurrent-build recipe for large tables and the validate-mode note.

## Decided

- **Causation only; correlation is OpenTelemetry's.** The tracing interceptors already carry trace context
  through event and message metadata. The framework does not populate `correlation_id`.
- **An enricher, not a mapper parameter** (F3). Same result, no break.
- **Bindings nest and the innermost wins** (F3). That one rule settles every precedence question: explicit over
  delivery, resolved event over queue metadata.
- **Every framework delivery site that holds a `PersistedEvent` binds it** (F3): the two event-processor sites
  and the asynchronous and in-transaction raw subscriptions. The batched subscriber does not; its handler binds
  explicitly.
- **Lazy appends capture the cause at registration, not at commit** (F3). The three lazily appending repositories
  re-bind the captured cause around their own append, so an in-transaction handler's events are never written
  under the outer work's cause.
- **The cause of a join is the delivering event**, with an explicit `CausationContext.where(...)` override for a
  policy that records a different one (F3). `caused_by_event_id` stays single-valued.
- **No cause bound means no cause written**, never an error (F3).
- **A command carries its cause on every bus** (F4): synchronously and through the plain `LocalCommandBus` because
  the handler runs inside the sender's binding, and on the durable bus by the queue interceptor. A `sendAsync`
  `Mono` subscribed outside the binding loses it, documented. Deciders never see it; other code may read it with
  `CausationContext.current()`.
- **Causation is written by default** (F3), with `essentials.eventstore.causation.enabled=false` turning off both
  writers — the enricher and the queue interceptor — together.
- **The `caused_by_event_id` index is opt-in, harness-managed and partial** (F5): off by default behind
  `essentials.eventstore.causation.index-enabled`, applied to every stream table including future ones, with a
  documented concurrent pre-build for large tables. Only the forward walk needs it, and it refuses to run without
  it. The backward walk is a union lookup on the existing `UNIQUE(event_id)`.
- **`eventsourced-aggregates` participates with one internal change.** Every one of its repositories appends
  through `EventStore.appendToStream`, so the enricher applies; the three lazy ones additionally capture the cause
  at registration.
- **The decisive test is a framework integration test**, because the webshop is not on the release line yet; the
  webshop gets the worked example when it is.

## Open questions

None blocking. Two things are deliberately left for evidence rather than decided up front:

1. **Recording the cause's AggregateType** next to its id, to turn the backward walk's union into a single-table
   lookup. Only if the union lookup measures as too slow on a realistic number of AggregateTypes.
2. **A per-AggregateType switch for the index.** Only if someone needs the forward walk for some types and not
   others.

## Implementation plan

Nine phases in dependency order. Each is one commit (or a small series), compiles and passes its own tests, and
leaves the build green; none is useful *shipped* alone, so they land on one branch and merge together. Commands
follow the root `CLAUDE.md`: `mvn test -pl <module> -am` for unit tests, `mvn verify -pl <module> -am` for
integration tests (Docker).

### Phase 1 — `CausationContext` (`foundation`)

- New package `dk.trustworks.essentials.components.foundation.causation` with `CausationContext`:
  - `static Optional<EventId> current()`
  - `static Binding where(EventId causedBy)` and `static Binding where(Optional<EventId> causedBy)`, returning a
    small wrapper with `run(Runnable)` and `call(ScopedValue.CallableOp)` (which propagates checked exceptions
    unchanged).
  - The value bound is an `Optional<EventId>`, so `where(Optional.empty())` binds an explicit *"no cause"* that
    hides any outer binding. That is what phase 4 needs: a repository that captured "no cause" at registration
    must not inherit some unrelated outer cause at commit. No `null` anywhere in the API.
  - The `ScopedValue` instance itself stays private; the class is the only way in.
- Unit tests: binding visible inside and gone after, including when the action throws; nesting with the innermost
  winning and the outer restored; "no cause" hiding an outer binding; a binding is not visible in a task submitted
  to an executor from inside it (the property F4 exists for), can be captured and re-bound there, and is not
  visible in that pool thread's next task.

### Phase 2 — Delivery-site bindings (`postgresql-event-store`)

- `AbstractEventProcessor.EventReferenceResolvingMessageConsumer`: `resolveEventReference` returns the
  `PersistedEvent` together with the resolved `OrderedMessage` (a private record); phase 2 runs inside
  `CausationContext.where(persistedEvent.eventId())`.
- `InTransactionEventProcessor.invokeHandler`: bind around `patternMatchingHandlerDelegate.accept(...)`.
- `PersistedEventSubscriber`: bind around `eventHandler.handleWithBackPressure(e)`.
- `ExclusiveInTransactionSubscription`, `NonExclusiveInTransactionSubscription`: bind around
  `eventHandler.handle(event, unitOfWork)`.
- `BatchedPersistedEventSubscriber`: no binding; javadoc on the batch handler says how to bind explicitly.
- Tests: for each site, a handler that records `CausationContext.current()` sees the delivered event's id. For
  the `EventProcessor`, one `REQUIRED` and one `NONE` handler.

### Phase 3 — The enricher and the switch (`postgresql-event-store`, starter)

- `CausationPersistableEventEnricher` in `persistence.table_per_aggregate_type`: if `causedByEventId()` is empty
  and a cause is bound, return a copy with it set (every other field copied unchanged); otherwise return the
  event as is.
- Starter: `EssentialsEventStoreProperties` gains a nested `causation` block with `enabled` (default `true`) and
  `indexEnabled` (default `false`, used in phase 6). The enricher bean is
  `@ConditionalOnProperty(prefix = "essentials.eventstore.causation", name = "enabled", matchIfMissing = true)`.
  Check the generated `spring-configuration-metadata.json` after a clean build (the `-proc:full` gotcha).
- F1: rewrite the default mapper's javadoc in `EventStoreConfiguration`; tighten the `PersistableEventMapper`
  javadoc to say the starter's default sets none of those fields and the enricher supplies causation.
- Tests: enricher unit tests (fills empty, never overwrites, no-op when unbound); a starter test that the bean is
  present by default and absent with `enabled=false`; an IT where an `EventProcessor` handler appends through an
  eager adapter and the persisted row carries the triggering event's id.

### Phase 4 — Lazy appends (`eventsourced-aggregates`)

- `StatefulAggregateRepository`, decider `CommandHandler`, `FlexAggregateRepository`: when registering the
  aggregate / `EventsToAppendToStream` / events-to-persist with the UnitOfWork, capture
  `CausationContext.current()` into the registered callback state; in `beforeCommit`, run the
  `appendToStream` inside `CausationContext.where(captured)`.
- Tests (IT): a `REQUIRED` `EventProcessor` handler that changes an aggregate through each repository writes the
  triggering event's id. And the case that motivated this phase: an in-transaction subscription handler that
  changes an aggregate through `StatefulAggregateRepository` writes the id of the event it was handed, not the id
  of the outer work's cause.

### Phase 5 — Queue propagation (`foundation`, starter)

- `CausationDurableQueuesInterceptor` in `foundation.messaging.queue`:
  - `intercept(QueueMessage)` / `intercept(QueueMessages)`: if a cause is bound and the metadata has no
    `essentials.causedByEventId`, add it.
  - `intercept(HandleQueuedMessage)`: read the key from `operation.message.getMessage().getMetaData()` only, and
    proceed inside `CausationContext.where(...)` when present.
  - The key is a public constant on `MessageMetaData`, next to `FENCED_LOCK_TOKEN`.
- Starter: a `DurableQueuesInterceptor` bean in the event-store starter, under the same
  `essentials.eventstore.causation.enabled` condition as the enricher.
- Tests:
  - Unit: never overwrites an existing key; no key when unbound; tolerates the shard-owned partial message.
  - Per engine (default, centralized fetcher, shard-owned): a cause bound at `queueMessage` is visible in the
    handler *and* in the UnitOfWork's `beforeCommit` — this is the test that pins "no engine opens a UnitOfWork
    outside the chain".
  - Inbox, Outbox and `DurableLocalCommandBus.sendAndDontWait`: an event appended by the receiving handler
    carries the cause bound at send.
  - `LocalCommandBus` (in `foundation`'s tests, since `reactive` cannot see `CausationContext`):
    `sendAndDontWait` and a `sendAsync` subscribed inside the binding see the cause; a `sendAsync` subscribed
    after the binding ended does not. The last one documents the limitation rather than wishing it away.

### Phase 6 — Read path (`postgresql-event-store`, admin modules)

- `EventStore` default methods, implemented by `PostgresqlEventStore` through the persistence strategy:
  - `Optional<PersistedEvent> findEvent(EventId)` — the backward step: union lookup over the registered stream
    tables on `event_id`.
  - `List<PersistedEvent> findEventsCausedBy(EventId)` — the forward step: union over `caused_by_event_id`,
    ordered by global order; throws with the property name and the recipe when the index is not enabled.
  - Walking further is a loop over these, kept in the caller (admin API) with a depth cap, so the event store
    gains two queries, not a graph engine.
- Table names come from the strategy's own configuration, never from input, and still go through
  `PostgresqlUtil.checkIsValidTableOrColumnName()`.
- Index: `schemaChangesFor` adds `SchemaChange.repeatable("event-stream-caused-by-index", …)` when
  `indexEnabled`; partial, `IF NOT EXISTS`, named deterministically per table so the DBA recipe can reproduce it.
  Wire `indexEnabled` through the starter into the strategy.
- Admin: `EventStoreApi` gains "caused by" and "causes of" operations; mapping rows in `EssentialsAdminApiSpec`;
  controller in `spring-boot-starter-admin-api`; regenerate the committed OpenAPI document and the generated
  client; the `OpenApiContractCompatibilityTest` gate must stay green (additive operations only).
  Console: a causation panel on the event view in `spring-boot-starter-admin-ui` (Thymeleaf + vanilla JS).
- Tests: ITs for both lookups across two AggregateTypes; the forward walk refuses with the index off; the schema
  change applies to an AggregateType added after start-up; validate mode fails until the emitted script is
  applied; a hand-built `CONCURRENTLY` index with the documented name is adopted by the harness without
  rebuilding.

### Phase 7 — The decisive integration test

A framework IT (in the event-store starter's test suite, so it runs with the real bean wiring) reproducing the
two webshop shapes without the webshop:

1. An `EventProcessor` with a `REQUIRED` handler that appends through `StatefulAggregateRepository` (lazily, at
   commit). The written event must carry the id of the event that triggered the handler.
2. An `EventProcessor` with a `NONE` handler that opens its own UnitOfWork, commits an event *R*, then — standing
   in for the gateway webhook — binds *R*'s id explicitly and adds a message to an Inbox. The Inbox handler's
   appended event must carry *R*'s id.

Both assert exact ids, not "some cause is set", because a missing binding fails silently (F3).

### Phase 8 — Documentation

- `LLM/LLM-postgresql-event-store.md` and `LLM/LLM-foundation.md`: `CausationContext`, the two properties, the
  join rule, the batch-handler and `sendAsync` caveats, the read methods.
- Module `README.md`s for the same modules, and the module `CLAUDE.md`s where contributor context changes (the
  binding sites, "bind at every new delivery site").
- Migration notes for the release this ships in: causation on by default and how to turn it off; the new
  metadata key; the index property with the verbatim `CREATE INDEX CONCURRENTLY` statement and the validate-mode
  note.
- Release notes entry.

### Phase 9 — Webshop worked example (when the webshop is on the release line)

- The capture webhook binds the `FundsCaptureRequested` id looked up from the idempotency key around
  `addMessageReceived`.
- Replace the webshop `CLAUDE.md` note that events carry no causation (it currently points to this document's
  old name, `docs/event-causation-and-correlation.md`).
- An end-to-end assertion over the F6 chains, and the "why is this in this state?" view exercised against real
  demo data.
