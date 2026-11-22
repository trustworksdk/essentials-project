# PostgreSQL Event Log (new event store engine, preview) - LLM Reference

> Quick reference for LLMs. The new event store engine and its typed processors. **Preview in 0.61.0**: the engine,
> the processor module, the starter and their properties may change in a minor release. The classic event store
> ([LLM-postgresql-event-store.md](LLM-postgresql-event-store.md)) stays the default and fully supported.

## Quick Facts

| Aspect | Details |
|--------|---------|
| **Modules** | `postgresql-event-log` (engine), `postgresql-event-log-processor` (typed API), `postgresql-event-log-migration` (one-way data move), `spring-boot-starter-postgresql-event-log` (auto-configuration) |
| **GroupId** | `dk.trustworks.essentials.components` |
| **Packages** | `dk.trustworks.essentials.components.eventlog` (engine), `…eventlog.processor` (processors, adapter, admin SPI under `…processor.api`), `…eventlog.migration` |
| **Tables** | Prefixed `el_` (`el_progress`, one event table per aggregate type, …) |
| **Status** | Preview, off by default (`essentials.event-log.enabled=false`) |
| **Replaces** | Nothing yet. It runs instead of the classic engine in one application, never beside it on the same aggregate types |

## TOC
- [When to use it](#when-to-use-it)
- [Enabling it with the starter](#enabling-it-with-the-starter)
- [Processors](#processors)
- [Consistency and transactions](#consistency-and-transactions)
- [Porting from the EventProcessor family](#porting-from-the-eventprocessor-family)
- [Settings](#settings)
- [Observability](#observability)
- [Admin](#admin)
- [Migrating existing data](#migrating-existing-data)
- [Known limits](#known-limits)
- [Gotchas](#gotchas)
- [See Also](#see-also)

## When to use it

Choose the classic event store for production systems today. Consider the event log when you can run a preview and
want what it changes:

- **Subscriptions without CDC, gap handler or fenced lock.** A subscription follows the database's own transaction
  visibility (a watermark from the `pg_stat_activity` view, counting only this database's writers), so it never skips a late commit and needs no gap handling.
  Exclusive subscriptions run on leases the engine keeps in `el_progress`, not on a `FencedLockManager`.
- **Two consistency modes**, chosen per subscription: `CONTIGUOUS` (strict global order, never above a hole) for read
  models, `FRESH` (every committed event as soon as it is seen, per-aggregate order only) for reactions.
- **Views committed with their resume point.** A view's writes and its progress commit in one page transaction.
- **Retry, page shrinking and parking are the engine's**: a failing event is retried alone, then parked; the rest of the
  stream continues. Parked events are retried or discarded from the admin API.

Aggregates and repositories do not change: the starter provides `EventLogEventStore`, an `EventStore`
(`ConfigurableEventStore<EventLogAggregateEventStreamConfiguration>`) that appends to and loads from the event log.
Snapshots and closing books run on it too. What it does not offer: polling (`pollEvents`/`unboundedPollForEvents` throw
`UnsupportedOperationException`), the `EventStoreSubscriptionManager`, CDC, in-transaction event processors and
multi-aggregate-type subscriptions (a processor with several aggregate types runs one subscription per type).

**Start-up refusal.** The engine refuses to start on a database where one connection cannot see another backend's
`backend_xid` in `pg_stat_activity` (`IllegalStateException: The event log cannot start: …`). Grant the
application's database role `pg_read_all_stats`, or stay on the classic event store. It also refuses an event table
whose `global_order` identity has a `CACHE` other than 1.

## Enabling it with the starter

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-log</artifactId>
</dependency>
<!-- Same provided dependencies as spring-boot-starter-postgresql: PostgreSQL driver, JDBI, Jackson 3, Reactor -->
```

```yaml
essentials:
  event-log:
    enabled: true          # off by default: without it the starter declares nothing
```

Enabled, the starter (`EventLogAutoConfiguration`) declares:

| Bean | Notes |
|------|-------|
| `EventLog` | Started in its bean method, stopped on shutdown. Who creates the schema follows `essentials.schema.mode`: the engine in `create`, the schema harness through `EventLogSchemaContributor` otherwise |
| `EventLogEventStore` (`essentialsEventStore`) | The `EventStore` for repositories. Backs off from an `EventStore` bean of your own |
| Unit-of-work factory, `EventStoreEventBus`, `JSONEventSerializer`, `PersistableEventMapper`, causation beans | Built as the classic starter builds them; each backs off from a bean of your own |
| `PageTransactionSource` | `SpringPageTransactions` with a `PlatformTransactionManager`, else `UnitOfWorkPageTransactions` |
| `EventLogProcessorDependencies` | Constructor argument of the four base classes below; carries each aggregate type's `AggregateIdSerializer` and `TenantSerializer`, from the event store's configuration (the single-tenant default reads a tenant as `TenantId`) |
| `EventLogProcessorLifecycle` | Starts every `ViewProjection` and `EventReactor` **bean** after refresh (registering its aggregate type first) and stops them before the `EventLog` |
| `EventLogSubscriptionsApi` | With `spring-boot-starter-admin-api` and an `EssentialsSecurityProvider` |

**Beside the classic starter.** With `spring-boot-starter-postgresql-event-store` also on the class path, an
`AutoConfigurationImportFilter` removes the classic `EventStoreConfiguration` while `essentials.event-log.enabled=true`.
Its subscription manager, gap handler, CDC beans, `EventProcessorDependencies` and `ViewEventProcessorDependencies` are
then not declared. Snapshot, closing-books and aggregate admin configurations stay and run on `EventLogEventStore`.
With the property unset or `false`, nothing is removed.

**Schema modes.** `create`: the engine creates its tables; registering an aggregate type creates that type's tables.
`validate`: the engine's schema is validated when the `EventLog` bean is built, so a missing one fails start-up with
`SchemaValidationException`. `emit`: an aggregate type's tables go into the emitted script, nothing is written to the
database, and no processor is started. `essentials.event-log.initialize-schema=false` drops the schema contributor, for
when a migration tool owns the schema.

## Processors

All four base classes are abstract, live in `dk.trustworks.essentials.components.eventlog.processor`, take their
aggregate types as **strings**, and implement foundation's `Lifecycle`: in Spring the components starter's lifecycle
manager starts them after refresh, as it starts the classic processors (not in schema mode `emit`). Handlers use
foundation's `@MessageHandler` and take the payload, optionally followed by a `DeliveredEvent` (the counterpart of the
classic `PersistedEvent` parameter: aggregate type and id, `eventOrder()`, `globalOrder()`, `eventId()`, metadata,
tenant, timestamp; `aggregateId()` is the type's id class, e.g. `OrderId`, as on the classic engine). The most specific payload type wins; an event with no handler counts as handled. An aggregate type
not yet registered with the `EventLog` is registered (with the engine's default tables) at `start()`.

Overridable hooks on the view processor and the reactor: `consistency()`, `startFrom()` (`StartFrom.beginning()` by
default), `errorSettings()` (`Optional<ErrorSettings>`, empty = engine defaults) and `tenant()` (empty = every tenant).
They are read at the first `start()`, never in the constructor.

### `EventLogViewProcessor` - a read model, with a rebuild hook

One `ViewProjection` per aggregate type: `CONTIGUOUS`, exclusive (one instance at a time), each page in one transaction
that also moves the resume point.

```java
@Service
public class OrderSummaryView extends EventLogViewProcessor {
    private final EventStoreUnitOfWorkFactory<?> unitOfWorkFactory;

    public OrderSummaryView(EventLogProcessorDependencies dependencies, EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
        super("OrderSummaryView", List.of("Orders"), dependencies);
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @MessageHandler
    void on(OrderPlaced placed, DeliveredEvent event) {
        // The page's unit of work (or Spring transaction) is bound: the write commits with the page and its resume point
        unitOfWorkFactory.getRequiredUnitOfWork().handle()
                         .execute("INSERT INTO order_summary(order_id, placed_order) VALUES (?, ?)",
                                  placed.orderId().toString(), event.eventOrder().longValue());
    }

    @Override
    protected void onRebuild(ProjectionContext context) {
        context.handle().execute("TRUNCATE order_summary");   // once per rebuild, for every aggregate type
    }
}
```

`rebuild()` replays the view from `startFrom()`: it stops every aggregate type's subscription, then in **one**
transaction of the page source calls `onRebuild(ProjectionContext)` and resets every subscription, so the cleared view
and the reset resume points commit together or not at all, and starts again if it was started. It returns `false`
(and rolls the clear back) when no subscription has progress yet. Call it outside a unit of work; it needs
`UnitOfWorkPageTransactions` or `SpringPageTransactions`, and throws `IllegalStateException` with
`PageTransactionSource.engine()`. `ProjectionContext` offers `handle()` (JDBI), `connection()`, `resource(Class)` and
`transaction()`; `onRebuild` must not end the transaction - its `connection()` is the guarded page connection, so `commit`,
`rollback`, `setAutoCommit` and `close` throw `IllegalStateException` (as in a page handler). The same holds for
`ViewProjection.rebuild(Truncate)`; code of your own that hands an open page to application code can use
`PageTransaction.guarded(OpenPage)`.

### `EventLogReactor` - side effects

One `EventReactor` per aggregate type: `FRESH`, exclusive, **at least once**, progress saved periodically
(`progress-save-interval`). Handlers are not in a page transaction; make them idempotent.

```java
@Service
public class ShippingNotifier extends EventLogReactor {
    private final Mailer mailer;

    public ShippingNotifier(EventLogProcessorDependencies dependencies, Mailer mailer) {
        super("ShippingNotifier", List.of("Orders"), dependencies);
        this.mailer = mailer;
    }

    @MessageHandler
    void on(OrderShipped shipped, DeliveredEvent event) {
        mailer.sendOnce(event.eventId(), shipped.customerEmail());   // idempotent by event id: a restart may redeliver
    }
}
```

### `EventLogInboxProcessor` - a reaction that keeps its inbox

The counterpart of a classic inbox-based `EventProcessor`. It reads its aggregate types `CONTIGUOUS`, from the
beginning, one instance at a time, and puts each event whose payload a `@MessageHandler` method handles into the
`Inbox` named after the processor. The inbox consumer then calls that method as the classic processor did: payload,
optionally an `OrderedMessage`, `@MessageHandler(unitOfWork = ...)` honoured, `CausationContext` bound to the event,
failures under the inbox's `RedeliveryPolicy`.

```java
@Service
public class PaymentRouterReaction extends EventLogInboxProcessor {
    private final PaymentRouter router;

    public PaymentRouterReaction(EventLogProcessorDependencies dependencies, Inboxes inboxes,
                                 EventStoreUnitOfWorkFactory<?> unitOfWorkFactory, PaymentRouter router) {
        super("PaymentRouterReaction", List.of("Payments"), dependencies, inboxes, unitOfWorkFactory);
        this.router = router;
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.REQUIRED)
    void on(DepositRequested requested, OrderedMessage message) {
        router.route(requested);
    }
}
```

A page of up to `forwardPageSize()` events is enqueued in one page transaction that also moves the resume point, so
each event is forwarded **exactly once**. The `unitOfWorkFactory` passed in must be the one the page transactions and
the inbox's queues share (in Spring, the transaction-aware one); outside a page the enqueue fails. The forwarded message
carries the payload, is keyed by aggregate id and ordered by event order, and carries the event's metadata plus its event
id under `EventLogInboxProcessor.EVENT_ID_METADATA_KEY`. Hooks, named as the classic ones without `get`:
`inboxMessageConsumptionMode()`, `numberOfParallelInboxMessageConsumers()`, `inboxRedeliveryPolicy()` (a
`FailoverRedeliveryPolicy` by default), `forwardPageSize()` (the classic `getForwardBatchSize()`; the engine's
`page-size` by default), `errorSettings()`.

### `EventLogPartitionedViewProcessor` - a view handled on every instance

The counterpart of the classic `PartitionedViewEventProcessor`, for a view keyed by the aggregate whose handlers should run
on every instance. Needs a `DurableQueues` that keeps key order across instances (the shard-owned engine); `start()`
refuses any other, and refuses `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`.

```java
@Service
public class AccountOverviewView extends EventLogPartitionedViewProcessor {
    public AccountOverviewView(EventLogProcessorDependencies dependencies, DurableQueues durableQueues,
                               EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
        super("AccountOverviewProjection", List.of("Accounts"), dependencies, durableQueues, unitOfWorkFactory);
    }

    @MessageHandler
    void on(AccountOpened e, OrderedMessage message) {   // message.getKey() = plain aggregate id, getOrder() = event order
        ((EventStoreUnitOfWork) messageUnitOfWork()).handle().execute("INSERT ...");
    }

    @Override
    protected void onRebuild(ProjectionContext context) {
        context.handle().execute("TRUNCATE TABLE account_overview");
    }
}
```

One instance at a time reads the events (`CONTIGUOUS`, from the beginning) and queues each event a handler takes on
`<name>:queue` with one `queueMessages` per page, inside the page transaction: each event is queued **exactly once**. The
`unitOfWorkFactory` must be the one the page transactions and the queues share. Messages match the classic class's
payload messages: key `<aggregateType>|<aggregateId>` (`qualifiedKey`), order = event order, metadata plus
`AGGREGATE_TYPE_METADATA_KEY` / `EVENT_ID_METADATA_KEY` (the classic key names). A classic *reference* message is refused:
drain the queue before switching. Every instance consumes the queue; each message is handled in one unit of work
(`messageUnitOfWork()`) with `CausationContext` bound. Handlers must be idempotent (guard rows with the event order), as on
classic. Hooks: `numberOfParallelQueuedMessageConsumers()`, `durableQueueRedeliveryPolicy()`, `forwardPageSize()`,
`errorSettings()`, `onRebuild(ProjectionContext)`.

`rebuild()` stops this instance's consumers, refuses while another instance consumes the queue (stop it everywhere else
first, as for the classic reset), then in one page-source transaction runs `onRebuild`, purges the queue (dead letters
included) and resets the reading side, and restarts. The cleared view, the empty queue and the reset progress commit
together or not at all: the purge joins the transaction's unit of work (the shard-owned `purgeQueue` does since 0.61.0).

### Lambda builders

Without a base class, `ViewProjection.builder(subscriber, aggregateType)` (`.on(Type.class, (event, ctx) -> …)` or
`.onPage((events, ctx) -> …)`, `.transactions(pageTransactionSource)`, `.build(eventLog, serializer)`) and
`EventReactor.builder(subscriber, aggregateType)` (`.on(Type.class, event -> …)`) build the same processors. Declared as
Spring beans, the starter's `EventLogProcessorLifecycle` starts them. Both builders also take `consistency(…)`,
`ownership(…)`, `startFrom(…)`, `eventTypes(EventTypeFilter)`, `tenant(…)` and `errors(ErrorSettings)`.
`ViewProjection.rebuild(Truncate)` and `EventReactor.reset(StartFrom)` reset one subscription.

## Consistency and transactions

| | `CONTIGUOUS` | `FRESH` |
|---|---|---|
| Order | Strict `global_order` of the aggregate type | Per aggregate only |
| Holes (uncommitted or rolled-back orders) | Waits until the hole commits or is known rolled back | Delivers above holes; picks late commits up afterwards |
| Default for | `EventLogViewProcessor`, `EventLogInboxProcessor`, `EventLogPartitionedViewProcessor`, `ViewProjection` | `EventLogReactor`, `EventReactor` |
| Delivery | Exactly once with page transactions (view writes and resume point commit together) | At least once (resume point saved periodically) |

**Page transactions.** A `ViewProjection` handler runs in a transaction opened by a `PageTransactionSource`:
`UnitOfWorkPageTransactions` binds the page's unit of work (`getRequiredUnitOfWork()`, its `Handle`), and
`SpringPageTransactions` opens a `REQUIRES_NEW` Spring transaction. The progress update is the last statement before
the engine commits. A handler cannot commit or roll the page back: the unit of work and the transaction status refuse
it.

**Failures.** A handler exception reaches the engine unchanged: the page is shrunk, the event retried alone
`retry-attempts` times with a doubling backoff, then parked; the stream continues behind it. `ParkEventException`
(engine package) parks at once. That includes an exception the handler throws from a connection of its own (SQLState
`08*`, `SQLTransientConnectionException`, also wrapped): it is retried and parked like any other. Only a failure of the
engine's own work (the page connection lost, beginning or committing the page, the engine's statements) is retried
indefinitely without shrinking and without parking, so a database outage never parks events. There is **no permanent-error list**: an
`IllegalArgumentException` is retried and parked like any other failure.

## Porting from the EventProcessor family

The classic processors do not run on the new engine: there is deliberately no `EventStoreSubscriptionManager` adapter.
Port each one by swapping its superclass:

| Classic | New engine | Notes |
|---------|------------|-------|
| `ViewEventProcessor` (or an in-transaction view) | `EventLogViewProcessor` | Write through `getRequiredUnitOfWork().handle()` as before; it is the page's |
| `EventProcessor` with an inbox | `EventLogInboxProcessor` | Handlers unchanged, including `OrderedMessage` and `unitOfWork = …` |
| `PartitionedViewEventProcessor` | `EventLogPartitionedViewProcessor` | Handlers unchanged (`OrderedMessage`); same queue name and key; drain classic reference messages first |
| `EventProcessor` whose handlers only cause side effects | `EventLogReactor` | `FRESH`, at least once; no inbox, no redelivery policy |

Steps that keep a port small:

1. **Keep the processor name.** Pass the classic processor's name to the constructor, so logs, admin views and the
   inbox name stay the same.
2. **Aggregate types become strings**: `AGGREGATE_TYPES.stream().map(AggregateType::toString).toList()`.
3. **Replace the second handler parameter.** `PersistedEvent` becomes `DeliveredEvent`; a view handler reading
   `OrderedMessage#getOrder()` reads `event.eventOrder().longValue()` instead. Inbox processors keep `OrderedMessage`.
4. **Drop `@IgnoresEventTypes`**: an event without a handler is skipped.
5. **Move `onSubscriptionsReset(AggregateType, GlobalEventOrder)` to `onRebuild(ProjectionContext)`**, which runs once
   per rebuild for all aggregate types together, not once per type.
6. **`getCommandBus()` and `usingUnitOfWork` from the base class** become an injected `CommandBus` and
   `EventStoreUnitOfWorkFactory`.
7. **A `getForwardBatchSize()` override** becomes a `forwardPageSize()` override, and `getNumberOfParallelQueuedMessageConsumers()`
   becomes `numberOfParallelQueuedMessageConsumers()`.

What each port keeps or changes in delivery:

- **Inbox forwarding is exactly once with `EventLogInboxProcessor`**, because the enqueue and the resume point commit in
  the same page transaction. A forwarder you write yourself gets the same guarantee only if it enqueues inside that page
  transaction; one that enqueues in a unit of work of its own, or from a `FRESH` reactor, forwards an event again when
  the page fails after the enqueue.
- **`onRebuild(ProjectionContext)` runs once per rebuild, with no aggregate type**, where `onSubscriptionsReset` ran
  once per type. A ported `onSubscriptionsReset` that cleared only the rows of the type it was given should clear
  everything the view writes.
- **`EventLogReactor` is at least once**, the same contract as the classic `EventProcessor`: after a restart a handler
  may see an event again, so its handlers are idempotent, as they were before. Use `EventLogInboxProcessor` when a
  reaction needs a durable hand-off.

To run both engines from one code base, keep each handler body in an engine-neutral bean with package-private
`on(Handle, Event)` methods, and add two thin wrappers, one per engine, each gated on a property; the trading demo does
this behind `trading-demo.engine` (`examples/essentials-trading-demo`, `config/CLAUDE.md`).

**Not carried over** from the classic processors: `MessageHandlerInterceptor`s, `@MessageHandler(unitOfWork = …)` on
view processors and reactors, the permanent-error list, inbox redelivery and the `RedeliveryPolicy` (except on
`EventLogInboxProcessor` and `EventLogPartitionedViewProcessor`), Reactor, and the `FencedLock` (exclusivity is the engine's lease).

## Settings

Every `EventLogSettings` value is a property under `essentials.event-log.*`, with the engine's defaults. The ones
most often touched:

| Property | Default | Meaning |
|----------|---------|---------|
| `enabled` | `false` | Turns the starter on |
| `initialize-schema` | `true` | Declare `EventLogSchemaContributor`; `false` when a migration tool owns the schema |
| `backstop-interval` | `1s` | How long a caught-up reader waits before reading again without a wake-up |
| `page-size` | `100` | Most events in one page transaction; also `EventLogInboxProcessor`'s default `forwardPageSize()` |
| `retry-attempts` | `3` | Retries of an event failing alone before it is parked |
| `retry-backoff-initial` / `retry-backoff-max` | `10ms` / `10s` | Doubling backoff between those retries, and after an I/O failure |
| `progress-save-interval` | `1s` | Periodic resume-point save of `FRESH` reactors |
| `hole-probe-wait-max` | `100ms` | Most time between two running-set probes of an aggregate type while a hole of it waits |
| `cross-jvm-wake-up` | `true` | `NOTIFY`/`LISTEN` wake-ups between instances |
| `lease-duration` / `lease-renewal-interval` / `lease-local-stop` | `15s` / `4s` / `7s` | Exclusive-subscription leases |
| `node-name` | empty | Prefix of the lease owner id, for operators |
| `event-type-indexed`, `tenant-indexed` | empty | Aggregate types that get the opt-in event-type or tenant index for filtered catch-up |

**Hole probes and `hole-probe-wait-max`.** An append that rolls back leaves a hole in the global order, and a hole
holds every `CONTIGUOUS` subscriber of that aggregate type until a probe of the running transactions proves it can no
longer commit. The probe is paced to cost about 1 % of one core across the cluster, which under load can push the next
probe seconds away. `hole-probe-wait-max` caps the wait while a hole waits, so while holes wait the 1 % probe budget
is a **soft target**, not a limit (one probe per 100 ms per reader at the default). Outside holes nothing changes.
Raise it to spend less database CPU on probes; lower it to retire holes sooner.

## Observability

**Meters.** `MicrometerEventLogObserver` (`…eventlog.observability.micrometer`, needs `micrometer-core`, `provided` in
the engine) implements `EventLogObserver`. The starter installs it when a `MeterRegistry` bean exists and the application
declares no `EventLogObserver` bean; `essentials.event-log.metrics.enabled=false` switches it off. An application
observer bean **replaces** it - to keep both, extend `MicrometerEventLogObserver` and call `super`. Tags: `subscriber_id`,
`aggregate_type`, optional `Module` (`essentials.tracing-properties.module-tag`), as the old engine's subscription meters.

| Meter | Kind | Meaning |
|---|---|---|
| `essentials.eventlog.subscription.lag` | gauge | `max(0, readerCursor - resumeFrom + 1)`: events past the *saved* resume point (the old `DurableSubscriptions_EventOrder_Diff`). A `PERIODIC` subscription saves every `progress-save-interval`, so it reads up to that much behind what was handled |
| `essentials.eventlog.subscription.failed` | counter | every `onError`: handler and engine failures, retried or parked after. Reader failures count under `subscriber_id=eventlog-reader` |
| `essentials.eventlog.subscription.parked` | counter | parked heads and events held behind them |
| `essentials.eventlog.subscription.lease_lost` | counter | leases an exclusive subscription lost |
| `essentials.eventlog.subscription.page` | timer, `outcome=committed\|failed` | in-transaction page time (handler included); periodic subscriptions have no pages |
| `essentials.eventlog.lease.renewal_failed` | counter, no subscriber tag | lease keeper passes that failed |
| `essentials.eventlog.reader.oldest_candidate_age` | time gauge, `aggregate_type` (and `Module`) only | how long the tail reader's oldest unresolved hole candidate has waited; 0 when none. While it grows the type's watermark stands still and every `CONTIGUOUS` subscriber of the type waits: an append that drew a hole has not ended |

The lag reads 0 on a node that lost or suspended an exclusive subscription's lease (another node delivers) until it
owns it again and saves; a stopped subscription's meters are removed once the last instance of that subscriber on that type stops
(`EventLogObserver.onStarted` / `onStopped`, one pair per delivery thread; instances share their meters). An observer that
throws is logged (WARN at most once a minute) and ignored - it never stops delivery.

Meters are cached per subscription and updated per page, read or failure, never per event.

**A long transaction and the watermark.** A hole in a type's `global_order` is resolved once no transaction that was
running when it appeared can still commit into it. Any transaction of the database that holds an xid (it has written
something) counts at first; after a candidate has waited 1 s the reader asks `pg_locks` which of them hold a
`RowExclusiveLock` on the type's event table, and only those keep holding it. So a long batch job, a migration or a
session idle in a transaction after a write delays a type's `CONTIGUOUS` subscribers by about a second, but an append
of that type that stays open holds them for as long as it runs. To find it: `SELECT l.pid, a.application_name,
a.state, a.xact_start FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE l.relation = 'el_<type>_events'::regclass
AND l.mode = 'RowExclusiveLock'`. Read-only transactions never count.

**Tracing and append timing.** As on the old engine, and under its properties: `RecordExecutionTimeEventStoreInterceptor`
(`essentials.eventstore.metrics.enabled`, `.thresholds.*`) always, `MicrometerTracingEventStoreInterceptor` with
`management.tracing.enabled=true` (`essentials.eventstore.verbose-tracing`). The adapter runs the tracing interceptor as
an enricher, so each appended event's metadata carries the `traceparent` of the appending span.

**Per-event TRACE.** Each subscription logs on `dk.trustworks.essentials.components.eventlog.Subscription.<subscriber>`
(characters outside `[A-Za-z0-9_-]`, dots included, become `_`): `delivering <eventType> global_order <n> event_order <m>
to <subscriber>` per event and `page committed: ...` per in-transaction page. Switch it on for one subscriber, e.g.
`logging.level.dk.trustworks.essentials.components.eventlog.Subscription.orders-view=TRACE`; off, it costs one level check
per page.

**Causation.** Every processor base class (`EventLogViewProcessor`, `EventLogReactor`, `EventLogInboxProcessor`,
`EventLogPartitionedViewProcessor`) binds `CausationContext` to the delivered event around each handler call, so an
event a handler appends records it as `caused_by_event_id` (with `essentials.eventstore.causation.enabled`, default on).
A bare `ViewProjection`/`EventReactor` handler does not bind it.

**Event type filter.** Every processor base class subscribes only to the event types its `@MessageHandler` methods
handle - filtered in SQL, so other events cost no page transaction - when every handled payload type is a final class
(record, enum, final class) outside the JDK; with any interface, non-final class, `Object` or `String` handler it
receives every event type, because dispatch also hands subclasses to a supertype handler. Override
`protected EventTypeFilter eventTypeFilter()` to widen it (`EventTypeFilter.all()` when the serializer maps stored type
names to other classes) or add names. A changed filter keeps the subscription's position.

## Admin

`EventLogSubscriptionsApi` (`…eventlog.processor.api`, implementation `DefaultEventLogSubscriptionsApi(securityProvider,
eventLog)`) answers from the database, so any instance sees every instance's subscriptions. Through
`spring-boot-starter-admin-api` it is served as seven operations under `/event-log/subscriptions` (tag
`event-log-subscriptions`): list, get, parked events, reset, retry or discard a parked event, delete a retired
per-instance subscription. Reads need `SUBSCRIPTION_READER`, changes `SUBSCRIPTION_WRITER`; `ESSENTIALS_ADMIN` does both.
**Reset refuses a view or processor subscription** (`IllegalArgumentException` naming its owner; HTTP 400) - a
`ViewProjection` bean (call its `rebuild(truncate)`), one of an
`EventLogViewProcessor` or `EventLogPartitionedViewProcessor` (call its `rebuild()`, which clears the view, or purges the
queue, in the same transaction; moving the resume point alone would replay into an uncleared view) or an
`EventLogInboxProcessor` (no `rebuild()`: a reset would hand every event to its handlers again; call
`EventLog.admin().reset(key, from)` from the application if that is intended). Only the processors running in the
application that serves the admin call are known: a subscription of a processor that runs only in another application is
not refused, nor is a `ViewProjection` built outside the context. A reset moves the resume point only: nothing a
handler wrote is undone, so a refused view would re-apply events on top of its rows. The starter wires the refusal; by hand it is
`DefaultEventLogSubscriptionsApi(securityProvider, eventLog, key -> Optional<reason>)`.
The admin console (`spring-boot-starter-admin-ui`) shows them under *Event store → Event-log subscriptions*. Details:
[LLM-admin-api.md](LLM-admin-api.md).

## Migrating existing data

`postgresql-event-log-migration` moves aggregate types one way from the classic engine's tables to the event log's,
in the same database: `Migration` (`preflight()`, `prepare()`, repeated online `copyEpoch()`, then `cutover()` with
every classic node stopped, and the `verify…()` checks), also as a command line (`MigrationCli`, a YAML config). It
only reads the classic tables. Subscriptions' resume points are imported per mapping (`ImportedSubscription`;
`trustOldResumePoint` keeps the classic value instead of the safe, earlier one). There is no way back after the
cut-over except restoring a backup; rehearse it on a copy first. Each `copyEpoch()` first waits (up to `epochWaitTimeout`) for
the database's older transactions and for every prepared two-phase transaction (`pg_prepared_xacts`): an orphaned
prepared transaction holds the copy until it is committed or rolled back, and is named in the report's blockers.

## Known limits

- **Preview.** API, properties and table layout may change in a minor release.
- **More database CPU.** The phase-10 load gate (trading demo, one and two instances) measured about 1.2-1.3× the classic
  engine's PostgreSQL CPU per order and about 22 % more statements, for about 19 % less WAL and equal throughput. Size
  the database for it.
- **Aggregate types are strings** in the processor constructors and builders, not `AggregateType`.
- **No polling, CDC, `EventStoreSubscriptionManager`, in-transaction event processors or multi-type subscriptions.**
- **One `EventLog` per `DataSource`.**

## Gotchas

- ⚠️ **A classic `EventProcessor`, `ViewEventProcessor`, `PartitionedViewEventProcessor` or `InTransactionEventProcessor` bean fails start-up** once
  `essentials.event-log.enabled=true`, before any bean is created, with one line per such bean naming it, its class and
  its port target, for example `Bean 'orderProjection' (com.x.OrderProjection) is an old-engine ViewEventProcessor. With essentials.event-log.enabled=true it must be ported to EventLogViewProcessor - see LLM-postgresql-event-log.md § Porting from the EventProcessor family.`
  Port it (above).
- ⚠️ **A downstream outage the handler calls parks events.** A handler's own connection error is a handler failure:
  after `retry-attempts` retries the event is parked (as the classic Inbox dead-letters). Raise `retry-attempts` /
  `retry-backoff-max` for a subscription that must ride out a longer outage, and retry the parked aggregates after it.
- ⚠️ **`stop()` returns after 30 s even if a handler ignores interrupts**, with a WARN; `isRunning()` stays `true`
  until the delivery thread really ends; until then, in the same `EventLog`, a re-subscribe of the same key (exclusive or
  not) and `deleteRetiredInstance` of its row are refused with `IllegalStateException`. `EventLog.stop()` interrupts
  every subscription first and waits for them together, so several stuck handlers cost one timeout, not one each.
- ⚠️ **An exclusive in-transaction subscription's parked retry must finish within `lease-local-stop`** (7 s by default):
  it hands every held event of the aggregate to the handler in one transaction that holds the progress row, which the
  lease renewal waits on, so a slower retry is rolled back and the aggregate stays parked. Raise `lease-local-stop` /
  `lease-duration` for such a subscription. A periodic subscription's retry holds no lock while its handler runs; a
  reset meanwhile makes it deliver the events again (at least once).
- ⚠️ **Payload columns are `json`, not `jsonb`**: the stored text is the serializer's compact output, byte-equal to the
  classic engine's.

## See Also

- [postgresql-event-store](LLM-postgresql-event-store.md) - the classic engine and its `EventProcessor` family
- [spring-boot-starter-modules](LLM-spring-boot-starter-modules.md#event-log-starter-preview) - the starter
- [admin-api](LLM-admin-api.md) - the event-log subscription endpoints
- [foundation](LLM-foundation.md) - `Inbox`, `UnitOfWork`, `@MessageHandler`, `Lifecycle`
- [Traps](LLM-traps.md#ess-138) - ESS-138
