# Event Causation and Correlation

Scoped to `postgresql-event-store`, `spring-boot-starter-postgresql-event-store`, `kotlin-eventsourcing`,
`eventsourced-aggregates` and `foundation` (durable queues). Raised while building
`examples/essentials-webshop-demo`: asked how to *visualise how a decision was made*, and found that the event
store models the answer, the starter's documentation claims to provide it, and nothing actually writes it.

**Targeted at the next major**, which is breaking and JDK 25-based. Both facts change the design: the mapper SPI
can take a new parameter rather than reading ambient state, and `ScopedValue` is available as a final API, so
the ambient part does not need a `ThreadLocal`.

Six items. F1 is documentation contradicting code. F2 is the functional gap. F3 and F4 are the two halves of the
mechanism — in-process and across asynchronous boundaries — and F4 is the hard one. F5 is the read path, which
does not exist at all. F6 is what the whole thing unlocks, and is the reason to do it.

Nothing here is a correctness defect: the event store works, and events are complete without causation. It is a
*diagnosability* gap, which is the kind that stays invisible until someone has to answer "why did this happen?"
about production data.

---

## The current behaviour

`PersistedEvent` has modelled causation from the start:

```java
Optional<EventId>       causedByEventId,   // "Unique id of the Event that caused this Event to exist"
Optional<CorrelationId> correlationId,     // "used for tracking how events are related"
```

The columns exist in every event stream table (`caused_by_event_id`, `correlation_id`), and
`PersistableEventBuilder` exposes `setCausedByEventId` / `setCorrelationId`. The write path that would populate
them is `PersistableEventMapper`, an SPI with one method: raw event in, `PersistableEvent` out.

The Spring Boot starter supplies the default implementation, and its javadoc says:

> The `PersistableEventMapper` adds additional information such as: event-id, event-type, event-order,
> event-timestamp, event-meta-data, **correlation-id**, tenant-id for each persisted event at a cross-functional
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

No correlation id, no causation, no meta-data, no tenant. Every event in the webshop demo — and in any
application using the starter's default — has both columns null.

## F1 — The starter's default mapper documents behaviour it does not have

*Documentation defect. Fixed as a by-product of F2, but worth listing because it is why nobody noticed.*

A reader has to open the lambda to discover the sentence above is aspirational. The javadoc is not wrong about
the *SPI* — a mapper is exactly where those fields belong — only about this default implementation.

## F2 — Nothing propagates causation, and no single component can

*The functional gap.*

Causation is inherently ambient. The component that knows the cause is not the component that writes the event:

| Layer | Knows the cause? | Writes the event? |
|---|---|---|
| `EventProcessor` message handler | **yes** — it was handed the triggering event | no |
| Command bus / decider | no — a `Decider` is `(command, events) -> event?` and must stay pure | no |
| `DeciderCommandHandlerAdapter` | no — constructed with decider, config, event store; the command arrives alone | calls `appendToStream` |
| `PersistableEventMapper` | no — receives `(aggregateId, config, event, eventOrder)` | **yes** |

The fix therefore cannot be local to any one of them. Something has to carry "what caused the work I am
currently doing" from the processor to the mapper. Note that every component in that table is *correctly*
ignorant — the purity of the decider especially is a property to keep, not an obstacle to remove.

## F3 — The in-process mechanism: `ScopedValue` plus an explicit context

*Recommended design. Two parts, and it needs both.*

**Capture: a `ScopedValue<CausationContext>`**, bound by the framework at each entry point it owns, and read at
the append call site. Verified against the JDK 25 in this repo's toolchain — `java.lang.ScopedValue` is a final
API there, compiling and running with no `--enable-preview`:

```java
static final ScopedValue<CausationContext> CAUSE = ScopedValue.newInstance();

ScopedValue.where(CAUSE, causeOf(message)).run(() -> handler.invoke(message));
```

`ScopedValue` over `ThreadLocal` for the reasons the JDK now recommends it: the binding is immutable, it is
released automatically at the end of the dynamic extent rather than requiring a `remove()` in a `finally`, and it
cannot leak into a pooled thread's next task — which for a framework handing threads back to Reactor and to
durable-queue workers is the failure mode that matters. It is also the only one of the two with a defined story
for virtual threads and structured concurrency.

The catch to design around, and the reason F4 exists: a `ScopedValue` binding covers the *dynamic extent of a
call*. It does not cross a thread hand-off, and nothing in the JDK makes it cross a process or a queue.

**Hand-over: an explicit parameter on the mapper.** Because the next release is breaking, the mapper SPI can
stop being told nothing:

```java
PersistableEvent map(Object aggregateId,
                     AggregateEventStreamConfiguration config,
                     Object event,
                     EventOrder eventOrder,
                     EventAppendContext context);   // causation, correlation, tenant, meta-data
```

This is worth the break. It makes the causation a *value the mapper is given* rather than ambient state the
mapper reaches for, so a mapper stays unit-testable without a runtime, and a consumer who wants to ignore
causation simply ignores a parameter. The ambient capture still exists — something has to get the context from
the processor to the append call — but it stops at the framework boundary instead of reaching into the SPI.

The alternative considered and rejected: attribute storage on `UnitOfWork`. It works, and the transaction is a
defensible boundary, but it adds surface to one of the most widely implemented interfaces in the framework in
order to pass one value, and it makes the mapper read hidden state again. It remains a reasonable fallback if
binding at every entry point proves impractical.

## F4 — Crossing asynchronous boundaries

*The hard half. A solution that skips this produces causation chains that break at exactly the interesting
moments.*

No in-JVM mechanism — `ScopedValue`, `ThreadLocal` or a `UnitOfWork` attribute — survives a durable queue. The
webshop demo's own capture flow crosses two such boundaries:

```
OrderPackagingRequested ──[event store subscription]──> policy
    └── FundsCaptureRequested ──[HTTP to gateway, webhook back]──> endpoint
            └── Inbox ──[durable queue, minutes later, another thread, possibly another instance]──> decider
                    └── FundsCaptured        ← causation must still point at FundsCaptureRequested
```

So the cause has to **travel with the message**, and be re-bound on the consuming side:

- **Durable queues / Inbox / Outbox** — carry it in `MessageMetaData`, which already exists and already carries
  framework keys (`EVENT_REFERENCE`, `FENCED_LOCK_TOKEN`). The consuming side re-binds the `ScopedValue` from
  the metadata before invoking the handler, so the mechanism is symmetric with F3 rather than a second
  mechanism.
- **Event store subscriptions** — `EventReferenceOrderedMessage` carries `(aggregateType, aggregateId,
  eventOrder)` but **not** the `EventId`, which is what `causedByEventId` needs. Either add it to that message
  — cheap, and this is a breaking release — or resolve it per delivery, which costs a query per event and should
  not be the default.
- **Process boundaries (Kafka, HTTP)** — out of scope for causation, which is an internal id; this is where a
  *correlation* id earns its keep instead, and where adopting an inbound `traceparent` would pay off.

A chain that is correct in-process and silently null across a queue is worse than no chain at all, because it
looks complete.

## F5 — There is no read path

*Additive, and useless without F2–F4, but part of the whole.*

`EventStore` has no query by correlation id and no "events caused by this event". The columns are written and
never read: no finder, no index, nothing in `EventStoreApi`, nothing in the admin console. The whole solution
needs:

- `EventStore.loadEventsByCorrelationId(...)` returning events across aggregate types in global order. Note this
  crosses stream tables — the table-per-aggregate-type strategy makes it a union query, and doing it efficiently
  is the real work in this item, not the API shape.
- A causation walk — "what did this event cause?" — which is the same query filtered by `caused_by_event_id`.
- An index per stream table on each column, with the write cost measured rather than assumed.
- An admin API operation and console view, remembering the house rule that an admin operation lives in three
  synced places (`*Api` SPI, `EssentialsAdminApiSpec` mapping, controller).

## F6 — What it unlocks

*The motivation. None of this is possible today.*

"Why did this happen?" becomes a query rather than an investigation:

```
OrderPlaced
├── CreditCardHoldPlaced            (HoldFundsOnOrderPlacedPolicy)
└── [packaging work item]           (OrdersReadyForPackagingProjection)
    └── OrderPackagingRequested
        └── FundsCaptureRequested   (CaptureFundsWhenPackagedPolicy)
            └── FundsCaptured       (webhook → Inbox → RecordCaptureOutcomeDecider)
```

That tree is the honest answer to how a decision was made in an event-sourced system: not a stack trace, but the
chain of facts that led to it, across contexts that never called each other. In ascending order of effort it
gives: a "why is this order in this state?" timeline in an admin console; correlation between a distributed
trace and the events a request produced; and after-the-fact analysis of a policy's behaviour without adding
logging to the policy.

It also gives support and operations something they do not have today — take one customer complaint, pull every
event in every stream that belongs to it.

## Compatibility

The next release is a breaking major, so the constraint is no longer "additive only" — it is "break once,
deliberately, and document it".

- **`PersistableEventMapper` gains a parameter (F3).** Every consumer implementation stops compiling, which is
  the desired failure: a silent behaviour change here would be worse. The migration is mechanical — add the
  parameter, ignore it — and belongs in `MIGRATION-NEXT_MAJOR.md`.
- **`EventReferenceOrderedMessage` gains the `EventId` (F4).** Framework-internal, but it is public API.
- **JDK 25 baseline** is what makes `ScopedValue` usable without preview flags. Worth stating in the migration
  notes as one of the things the baseline buys, since it is otherwise invisible.
- **Persisted data is untouched.** Existing rows keep their nulls and nothing should be backfilled: a causation
  invented after the fact is a lie about what happened, and the whole value of the field is that it is not.
- **Consumers with their own mapper** keep whatever they set. The framework must never overwrite a causation a
  mapper has already decided.

## Open questions

1. **Where is a correlation id born, and does it adopt `traceparent`?** Adopting the W3C header when present
   makes the event store joinable with the tracing backend, which is most of the operational value — at the cost
   of coupling the id's lifetime to the tracing setup. A framework-minted id when absent either way.
2. **Is causation on by default?** It has a per-event storage cost and, until F5, no reader. Default-on means
   the data is there when someone finally needs it; default-off means it is missing exactly then.
3. **Are the columns indexed by default?** Measurable write cost against a read path that arrives in the same
   release. Probably an option first, a default once F5's queries are benchmarked.
4. **How far does `eventsourced-aggregates` participate?** It is a second append path and this document has not
   audited it. The scope below assumes it is in; that assumption needs checking before estimating.
5. **What happens when no cause is bound?** Recommended: always legal, yielding today's behaviour. A framework
   that throws because it cannot determine causation would be worse than one that omits it — but that does mean
   a missing binding fails silently, so the test in the scope below matters more than usual.

## Scope

Whole solution, in dependency order. Each step is testable on its own, and none of them is useful shipped alone.

1. **`CausationContext` + `ScopedValue` binding** in `foundation`, bound by `AbstractEventProcessor` around
   handler invocation and by the durable-queue consumer around message delivery.
2. **`EventAppendContext` on `PersistableEventMapper`**, `AppendToStream` carrying it, the starter's default
   mapper populating causation, correlation and tenant from it — which fixes F1 by construction.
3. **Both append paths**: `kotlin-eventsourcing`'s `DeciderCommandHandlerAdapter` and
   `eventsourced-aggregates`' repositories.
4. **Message-metadata propagation** and re-binding on the consuming side, plus the `EventId` on
   `EventReferenceOrderedMessage`.
5. **Read path**: correlation and causation queries, indexes, `EventStoreApi`, admin console.
6. **Migration notes** and a worked example in the webshop demo — which already has the shape that exercises
   every hop.

The test that decides whether this works is not a unit test: an event produced by a policy, triggered by a
webhook, delivered through an Inbox, must carry the id of the event that started the chain. The webshop demo's
capture flow is that test, which is a good reason to build it there first.
