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

**Status:** implemented on `feature/event-causation`, cut from `release/0.60`; phases 1–8 are done, phase 9 waits
for the webshop to reach the release line. 0.60 is not yet tagged, so merging the branch ships it in **0.60.0**; the
release notes and migration guide carry it there (§2.9, §1.1.7). The paragraph below is the original reasoning, kept
for the record.

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
   `REQUIRED` handler's commit and a `NONE` handler's own `withUnitOfWork` alike. `ViewEventProcessor` reuses
   this consumer for its queued path, and binds the same way on its direct path (`handlePersistedEvent`, which
   hands the event straight to the handler when nothing is queued for its key). Projections rarely append, but a
   view handler may send a command, and the binding costs nothing.
2. **Durable-queue delivery**, re-binding from `MessageMetaData` (F4). The engine-agnostic place is a
   `DurableQueuesInterceptor` around `HandleQueuedMessage`, which every engine — default, centralized fetcher,
   shard-owned — runs; `Inboxes.handleMessage` opens its UnitOfWork inside that chain, so the commit is covered.
3. **`InTransactionEventProcessor.invokeHandler`**, around its handler call. It already has the `PersistedEvent`,
   and an in-transaction processor can append, so it is bound from the start rather than left for later.
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
outer work had. (The mechanics: `GenericHandleAwareUnitOfWork.commit` loops over the resource callbacks, then
publishes the `BeforeCommit` events — which is when in-transaction handlers run — and makes another pass only if a
callback returned `REQUIRED`. The lazily appending repositories return `REQUIRED` after appending, so in the usual
case the handler's newly registered aggregate is processed in that next pass. `CausationBindingIT` pins it.) They would be recorded as caused by whatever caused the outer work — one step too far back —
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
  - **It must run outermost in the chain — `@InterceptorOrder(1)`.** An earlier revision of this document said
    its position did not matter, reasoning that every engine opens the handler's UnitOfWork inside the chain's
    terminal action. That missed that on PostgreSQL the UnitOfWork is opened *by an interceptor*:
    `PostgresqlDurableQueues` always registers `SingleOperationTransactionDurableQueuesInterceptor`, which wraps
    `HandleQueuedMessage` in a UnitOfWork. With the causation interceptor inside it, the handler saw the cause but
    the commit — where repositories append — ran after the binding had ended. The phase 5 IT caught it on both
    PostgreSQL engines (the shard-owned engine has no such interceptor and passed). Interceptors without an order
    sort as 10, and the sort is stable.
  - **It is registered by the event-store starter**, as a `DurableQueuesInterceptor` bean. The queue starters
    (`spring-boot-starter-postgresql`, `spring-boot-starter-postgresql-queue-shard-owned`) already collect every
    `DurableQueuesInterceptor` bean, and without an event store there are no causing events to carry.
- **The plain `LocalCommandBus`** in `reactive` *does* lose the cause on `sendAndDontWait` and `sendAsync`, as the
  first draft said. A later revision of this document claimed otherwise — that `Mono.fromCallable(...)` runs its
  callable on the subscribing thread, so the handler would run inside the sender's binding — and a test proved it
  wrong: `publishOn` fuses with `fromCallable` and pulls the callable onto the `boundedElastic` worker. Only `send`
  runs the handler on the caller's thread. Because `reactive` depends only on `shared` and cannot see
  `CausationContext`, the bus gained a small SPI, `CommandContextPropagator`: `AbstractCommandBus` calls each
  registered propagator on the sending thread when a command is sent (including a delayed `sendAndDontWait`), and
  runs the handler inside what they return. `foundation`'s `CausationCommandContextPropagator` captures the cause
  there and re-binds it on the worker; since it captures at *send*, a `sendAsync` `Mono` subscribed later still
  carries it. `DurableLocalCommandBus.sendAndDontWait` goes through the queue interceptor instead.
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
- **A command carries its cause on every bus** (F4): through `send` because the handler runs inside the sender's
  binding; through the durable bus's `sendAndDontWait` by the queue interceptor; and through `sendAsync` and the
  plain `LocalCommandBus`'s `sendAndDontWait` — which run the handler on a Reactor worker — by a
  `CommandContextPropagator`, a small SPI added to `reactive` for this (captured on the sending thread, restored
  around the handler). Chosen over a JVM-global Reactor schedule hook, which would have needed no API but changed
  every Reactor hand-off in the application. `foundation` supplies `CausationCommandContextPropagator`; the starter
  adds it to every command-bus bean. A `LocalCommandBus` built by hand outside Spring needs it added. Deciders never
  see the cause; other code may read it with `CausationContext.current()`.
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
- **Done** on `feature/event-causation`.

### Phase 2 — Delivery-site bindings (`postgresql-event-store`)

- `AbstractEventProcessor.EventReferenceResolvingMessageConsumer`: `resolveEventReference` returns the
  `PersistedEvent` together with the resolved `OrderedMessage` (a private record); phase 2 runs inside
  `CausationContext.where(persistedEvent.eventId())`.
- `InTransactionEventProcessor.invokeHandler`: bind around `patternMatchingHandlerDelegate.accept(...)`.
- `PersistedEventSubscriber`: bind around `eventHandler.handleWithBackPressure(e)`.
- `ExclusiveInTransactionSubscription`, `NonExclusiveInTransactionSubscription`: bind around
  `eventHandler.handle(event, unitOfWork)`.
- `BatchedPersistedEventSubscriber`: no binding; javadoc on the batch handler says how to bind explicitly.
- `ViewEventProcessor.handlePersistedEvent` (direct path): bind around `patternMatchingMessageHandlerDelegate.accept(msg)`.
  Its queued path already goes through the resolving consumer.
- Tests (`CausationBindingIT`): for each site, a handler that records `CausationContext.current()` sees the
  delivered event's id. For the `EventProcessor`, one `REQUIRED` and one `NONE` handler, each also recording what a
  UnitOfWork callback sees at commit; and an explicit binding inside a handler overriding the delivered cause. The
  async subscription is checked at commit too. One test pins the phase 4 premise: a callback registered by an
  in-transaction handler runs at commit under the *appender's* binding, not the delivered event's.
- **Done** on `feature/event-causation`.

### Performance gate (applies from phase 3 on)

There are no recorded baselines for the paths causation touches. The performance lab
(`examples/essentials-performance-lab`) writes its results to `target/`, so nothing is committed; the only committed
figures are `docs/durable-queue-measurements.md`, which compare the two queue engines with each other, and the lab has
no scenario that drives event → `EventProcessor` → handler → append at all.

Rather than record a "before" on `release/0.60` and compare it with an "after" from a different run — which the
measurements document already warns is not a sound comparison — the gate is an A/B **within one interleaved run**,
using `essentials.eventstore.causation.enabled` as the switch (it exists from phase 3):

- **Phase 2** adds one `ScopedValue` binding per delivery and nothing else; it is not measured separately.
- **Phase 3 (enricher):** `EventCausationCostIT`'s append-path test in the lab. The cost to look for is the
  `PersistableEvent` copy per event and the extra column value written. (The `baseline-polling-vs-cdc` scenario was
  the first idea, but it compares polling with CDC and has no causation switch to interleave on; a dedicated IT on
  the lab's `AbRunner` was simpler than adding one.)
- **Phase 4 (lazy appends):** `EventCausationCostIT`'s processor-chain test, added in phase 3 because no lab
  scenario drove this path: an `EventProcessor` whose handler saves through `StatefulAggregateRepository`.
- **Phase 5 (queue interceptor):** the lab's `durable-queues` scenario, both arms, both engines. The cost to look for
  is one metadata entry per queued message, serialized and stored.
- **Phase 6 (index):** a separate A/B with `index-enabled` on and off, measuring append latency, since the index is
  the one part with a per-insert database cost.

The acceptance bar is "no difference outside the run-to-run noise of the same arm" for throughput and latency. WAL
bytes per event are expected to grow by the size of the value written, and the check is that they grow by no more. A result outside it is a design
question, not a tuning task, and gets written up next to the numbers in `docs/durable-queue-measurements.md` style.

### Measured

`EventCausationCostIT` in the performance lab (`-Dbenchmark.run=true`), interleaved A/B on whether the enricher is
registered, run on 2026-10-03 against `58da23de` (phase 3). Bindings are unconditional, so they are in both arms.
Environment: devcontainer, aarch64, 8-CPU cgroup quota shared by JVM and database (no CPU pinning), PostgreSQL 17.5
in Testcontainers with `synchronous_commit=on`, JDK 25. **Only the two arms within one table are comparable**;
absolute numbers are this machine's.

Append path — 5 000 events, one per UnitOfWork, with a cause bound; 7 repetitions per arm:

| Arm | Events/s, median [Q1–Q3] | Append p50 µs, median [Q1–Q3] | WAL bytes/event |
|---|---|---|---|
| causation off | 4 798 [4 501–6 013] | 214 [158–225] | 677.8 |
| causation on | 4 916 [4 673–5 498] | 208 [165–218] | 718.0 |

Processor chain — 3 000 events through an `EventProcessor` (8 inbox consumers, centralized fetcher) whose handler
saves a new aggregate through `StatefulAggregateRepository`, appended lazily at commit; 7 repetitions per arm:

| Arm | Events/s, median [Q1–Q3] | WAL bytes/event |
|---|---|---|
| causation off | 1 576 [1 563–1 580] | 2 108.6 |
| causation on | 1 586 [1 566–1 590] | 2 153.2 |

Reading: throughput and latency distributions overlap in both shapes, so the run does not separate the arms. WAL
grows by 40.2 and 44.6 bytes per event, which is the cause value itself (a 36-character id plus its header) — the
column was already there and null before. Nothing beyond the stored value is visible.

Two harness lessons from getting there: with the queue defaults the processor chain delivered exactly one message per
20 ms poll, so both arms measured 50 events/s with zero spread — a measurement of configuration, fixed by the
consumer count and fetcher above. And WAL bytes cannot be held to "overlap": the enabled arm writes one more value
per event by design, so the check is that the difference is the size of that value.

**Rerun after phase 4** (lazy appends capture the cause at registration), same settings, same day:

| Shape | Arm | Events/s, median [Q1–Q3] | WAL bytes/event |
|---|---|---|---|
| Append path | causation off | 5 045 [4 077–6 033] | 678.6 |
| Append path | causation on | 4 831 [4 293–5 543] | 717.9 |
| Processor chain | causation off | 1 577 [1 570–1 583] | 2 114.1 |
| Processor chain | causation on | 1 592 [1 590–1 593] | 2 154.6 |

The append path overlaps again. The processor chain's interquartile ranges do *not* overlap — but the enabled arm
is the faster one, by 0.9%, with both ranges under 1% wide. A difference that size, pointing the way the added
work cannot, is drift between interleaved runs that this machine's spread happens to resolve, not a cost; the
harness reports it as separated because it is, and the reading is recorded rather than smoothed over. WAL still
grows by the stored value only (+39.3 and +40.5 bytes).

**Phase 5 — the queue path**, added to the same IT and run with the other two (7 repetitions per arm): 5 000
messages queued one per transaction with a cause bound — the shape of `Inbox.addMessageReceived` — and drained by 8
consumers on the centralized fetcher. The enabled arm registers the `CausationDurableQueuesInterceptor`:

| Arm | Messages/s, median [IQR] | WAL bytes/message |
|---|---|---|
| causation off | 1 595 [2] | 1 181.3 |
| causation on | 1 587 [5] | 1 296.9 |

The append path and processor chain overlapped again in the same run (+41.0 and +41.8 WAL bytes per event).

Reading the queue path: WAL grows by 115.6 bytes per message, which is the metadata entry
(`"essentials.causedByEventId":"<36-character id>"`, about 58 bytes in JSONB) written **twice** — PostgreSQL logs
the full row when the message is inserted and again when the UPDATE that claims it for delivery rewrites it. That
is still only the stored value. Throughput is 0.5% lower, with interquartile ranges too narrow to overlap; unlike
the processor chain's earlier separation this one points the way the added work does (one more metadata entry
serialized on the way in and parsed on the way out), so it is recorded as a possibly real cost of about half a
percent rather than dismissed as drift.

### Phase 3 — The enricher and the switch (`postgresql-event-store`, starter)

- `CausationPersistableEventEnricher` in `persistence.table_per_aggregate_type`: if `causedByEventId()` is empty
  and a cause is bound, return a copy with it set (every other field copied unchanged); otherwise return the
  event as is.
- Starter: `EssentialsEventStoreProperties` gains a nested `causation` block with `enabled` (default `true`).
  `index-enabled` is added in phase 6, together with the index it switches, so no release carries a property that
  does nothing. The enricher bean is
  `@ConditionalOnProperty(prefix = "essentials.eventstore.causation", name = "enabled", matchIfMissing = true)`.
  Check the generated `spring-configuration-metadata.json` after a clean build (the `-proc:full` gotcha).
- F1: rewrite the default mapper's javadoc in `EventStoreConfiguration`; tighten the `PersistableEventMapper`
  javadoc to say the starter's default sets none of those fields and the enricher supplies causation.
- Tests: enricher unit tests (fills empty, never overwrites, no-op when unbound); a starter test that the bean is
  present by default and absent with `enabled=false`; an IT where an `EventProcessor` handler appends through an
  eager adapter and the persisted row carries the triggering event's id.
- The enricher copies the event through `PersistableEvent.DefaultPersistableEvent`'s constructor rather than
  `PersistableEvent.from(...)`, because `from` assigns a timestamp when none is set and a custom mapper may leave
  the timestamp to the event store.
- **Done** on `feature/event-causation`: `CausationPersistableEventEnricher(Test)`, `CausationAutoConfigurationIT`
  (default on, off switch, a mapper's own cause kept), and two write-path tests in `CausationBindingIT`.

### Phase 4 — Lazy appends (`eventsourced-aggregates`)

- `StatefulAggregateRepository`, decider `CommandHandler`, `FlexAggregateRepository`: when registering the
  aggregate / `EventsToAppendToStream` / events-to-persist with the UnitOfWork, capture
  `CausationContext.current()` into the registered callback state; in `beforeCommit`, run the
  `appendToStream` inside `CausationContext.where(captured)`.
- Tests (IT): a `REQUIRED` `EventProcessor` handler that changes an aggregate through each repository writes the
  triggering event's id. And the case that motivated this phase: an in-transaction subscription handler that
  changes an aggregate through `StatefulAggregateRepository` writes the id of the event it was handed, not the id
  of the outer work's cause.
- **How it landed.** "Into the registered callback state" turned out not to exist for two of the three: the
  stateful and flex repositories each share *one* callback instance across every aggregate and UnitOfWork, so the
  callback has nowhere per-aggregate to keep a cause. Giving each registration its own callback instance would
  have changed how the UnitOfWork groups and orders appends, so instead an internal helper,
  `CausesCapturedAtRegistration`, keeps the captured cause per (UnitOfWork, resource identity) beside the callback:
  first registration wins, entries are released in `afterCommit`/`afterRollback`, and the map is weak on the
  UnitOfWork so an abandoned one — or a read-only Spring transaction, which skips `afterCommit` — cannot leak.
  The decider's resource is its own private `EventsToAppendToStream` record, so there the cause simply rides on
  the record. `FlexAggregateRepository`'s resource, `EventsToPersist`, is public API, which is why it uses the
  helper rather than gaining a field.
- **Done** on `feature/event-causation`: `CausationCapturedAtRegistrationIT` (9 tests: the cause at registration
  wins over the one at commit for all three repositories; "no cause" at registration is kept; first registration
  wins; a loaded aggregate's later changes; the in-transaction case end to end). All nine fail with the phase 4
  changes reverted. The `EventProcessor` → repository case is covered end to end by the lab's processor chain,
  which asserts every reaction event carries a cause.

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
    handler *and* in the UnitOfWork's `beforeCommit` — this is the test that caught the interceptor-order bug.
  - Inbox, Outbox and `DurableLocalCommandBus.sendAndDontWait`: an event appended by the receiving handler
    carries the cause bound at send.
  - `LocalCommandBus`: `CausationAcrossLocalCommandBusTest` — with the propagator every send method carries the
    cause, including a delayed `sendAndDontWait` and a `sendAsync` subscribed after the binding ended; without it,
    `sendAndDontWait` and `sendAsync` lose it (pinned, so a change to the bus's threading is noticed).
    `CommandContextPropagatorTest` in `reactive` covers the SPI itself with a `ThreadLocal`.
- **Done** on `feature/event-causation`: `CausationDurableQueuesInterceptorTest` (8),
  `CausationAcrossDurableQueuesIT` (Inbox, Outbox, durable command bus and the no-cause case, each on the per-queue
  consumer and the centralized fetcher; 8), a shard-owned test in `InboxOutboxOnShardOwnedIT`, and the starter
  registering the interceptor with the `DurableQueues` bean (`CausationAutoConfigurationIT`).

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
- **Split.** 6a is the event store and the index; 6b the admin API and console.
- **6a — how it landed** on `feature/event-causation`:
  - `EventStore.findEvent(EventId)` is one `loadEvent(aggregateType, eventId)` per registered aggregate type, in
    table-name order, so every lookup reuses the existing event-id index *and* goes through the interceptor chain.
    No new SQL. An id a UUID-typed table cannot hold is treated as "not in this table".
  - `EventStore.loadEventsCausedBy(EventId)` (named for the existing `loadEvent`/`loadEvents`, rather than the
    plan's `findEventsCausedBy`) is a new `LoadEventsCausedBy` operation with its own interceptor hook, and one
    indexed query per table. "Ordered by global order" was not quite possible: a global event order is per table,
    so results come in table-name order, and in global order within a table.
  - Both are `default` methods on `EventStore` (and the forward one on `AggregateEventStreamPersistenceStrategy`)
    that throw `UnsupportedOperationException`, so other implementations keep compiling; `PostgresqlEventStore`
    and `CdcEventStore` implement them.
  - The index follows the existing `enableNotifyTriggers` pattern: `enableCausationIndex()` on the strategy,
    idempotent, sweeping the tables registered so far; the starter calls it before any type is registered when
    `essentials.eventstore.causation.index-enabled=true`. `causationIndexStatement(configuration)` is public so the
    concurrent pre-build uses the exact name and predicate.
  - **Found along the way:** with UUID-typed event-id columns, a cause that is not a UUID made the *append* fail
    (`UUID.fromString` in the persist path). Framework ids are UUIDs, but a cause bound explicitly, or carried over
    from a TEXT-typed stream with custom ids, could have failed a business transaction over diagnostic metadata.
    Such a cause is now dropped with a WARN. (Correlation ids keep the old behaviour; they are outside this work.)
  - Tests: `CausationLookupIT` (9) and a validate-mode test in `EventStoreSchemaModeIT`.
- **6b — how it landed** on `feature/event-causation`:
  - `EventStoreApi` gains `findEvent`, `findCausationChain` (walk back to `maxDepth`, default 20, at most 100,
    stopping at a missing cause or a revisited event) and `findEventsCausedBy`, at `GET /event-store/events/{eventId}`,
    `…/causation-chain` and `…/caused-events`, guarded by `essentials_subscription_reader` / `essentials_admin`.
  - They return a new `ApiCausationEvent`: identity, position, timestamp and cause, **without payloads**. Walking
    causation does not need them, and the admin API otherwise guards payloads with a separate role; they can be
    added later without breaking anyone.
  - A missing index answers `409` with the property to set. `DefaultEventStoreApi` unwraps it from the
    `UnitOfWorkException` its UnitOfWork wraps it in — without that the adapter answered `500`, which only the
    end-to-end test could see.
  - Console: an **Event causation** page — look up an event id and see the whole flow it belongs to as one tree,
    rooted at the start of its chain, with the path down to the event expanded and the event highlighted. Other
    branches expand on click, one level at a time (at most 50 children shown per node), and each node's effects are
    fetched once per page visit. Event types show as their simple class name, the full type on hover. Without the
    caused-by index the tree degrades to the chain alone, with a notice saying how to enable it. (A first version
    showed the chain and the direct effects as two tables; trying it on the trading demo showed a flow is hard to read
    that way.)
  - **Where users get an event id from** was the gap a review found: they know the business id, not an event id, and
    the console showed event ids only for closing-books generations. So `findAggregateEvents` (`GET
    /event-store/aggregate-types/{aggregateType}/aggregates/{aggregateId}/events`, most recent `limit` events, default
    100) lists an aggregate's events, converting the text id with the type's configured `AggregateIdSerializer`, and
    the causation page takes an aggregate type (suggested from the subscriptions) and id as its starting point. A queued or
    dead-lettered `EventProcessor` inbox message links to it too: `ApiQueuedMessage` gained `orderedMessageKey`,
    `orderedMessageOrder` and `referencedAggregateType` (set when the payload is an `AggregateType`, the shape of an
    event reference - routing information, so not behind the payload role). The aggregate lookup's event-stream rows gain a
    *Causation* button. The page's three calls are literal paths, so the parity gate covers them.
  - Verified: contract drift, validation and compatibility gates; `AdminApiEndpointsTest` and the conformance
    count; the UI parity gate; and the three endpoints exercised over HTTP against the demo application. **Not
    verified visually** — no browser is installed in the devcontainer, so the page was syntax-checked but not
    rendered.

### Phase 7 — The decisive integration test

A framework IT (in the event-store starter's test suite, so it runs with the real bean wiring) reproducing the
two webshop shapes without the webshop:

1. An `EventProcessor` with a `REQUIRED` handler that appends through `StatefulAggregateRepository` (lazily, at
   commit). The written event must carry the id of the event that triggered the handler.
2. An `EventProcessor` with a `NONE` handler that opens its own UnitOfWork, commits an event *R*, then — standing
   in for the gateway webhook — binds *R*'s id explicitly and adds a message to an Inbox. The Inbox handler's
   appended event must carry *R*'s id.

Both assert exact ids, not "some cause is set", because a missing binding fails silently (F3).

**Done** on `feature/event-causation`: `EventCausationEndToEndIT` in the event-store starter, through the real
auto-configuration and lifecycle. Both shapes, as above, with every lazy append going through
`StatefulAggregateRepository` - and the second one continues past the plan: the admin API's
`findCausationChain` walks the Inbox-reached event back through `FundsCaptureRequested` to the triggering event,
and `findEventsCausedBy` finds the forward step. It passes with causation on and fails on every assertion with
`essentials.eventstore.causation.enabled=false`.

### Phase 8 — Documentation

- `LLM/LLM-postgresql-event-store.md` and `LLM/LLM-foundation.md`: `CausationContext`, the two properties, the
  join rule, the batch-handler and `sendAsync` caveats, the read methods.
- Module `README.md`s for the same modules, and the module `CLAUDE.md`s where contributor context changes (the
  binding sites, "bind at every new delivery site").
- Migration notes for the release this ships in: causation on by default and how to turn it off; the new
  metadata key; the index property with the verbatim `CREATE INDEX CONCURRENTLY` statement and the validate-mode
  note.
- Release notes entry.
- **Done** on `feature/event-causation`: an *Event Causation* section in `LLM/LLM-postgresql-event-store.md`, and
  causation coverage in `LLM-foundation.md`, `LLM-reactive.md`, `LLM-admin-api.md` and
  `LLM-spring-boot-starter-modules.md`; an *Event Causation* section in the event store's README; §1.1.7 and §2.9 in
  `RELEASE-NOTES-0.60.0.md`; an *Event causation* section in `MIGRATION-0.60.md` with the verbatim concurrent-build
  statement; the OpenAPI changelog; and module `CLAUDE.md` gotchas throughout.

### Phase 9 — Webshop worked example (when the webshop is on the release line)

- The capture webhook binds the `FundsCaptureRequested` id looked up from the idempotency key around
  `addMessageReceived`.
- Replace the webshop `CLAUDE.md` note that events carry no causation (it currently points to this document's
  old name, `docs/event-causation-and-correlation.md`).
- An end-to-end assertion over the F6 chains, and the "why is this in this state?" view exercised against real
  demo data.
