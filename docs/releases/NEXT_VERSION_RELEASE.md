# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.61.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/releases/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.61.0.md`](RELEASE-NOTES-0.61.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

_A bug-fix entry also says how to observe the fix: the call, and what it returns, throws or logs before the fix
and after it. A project that worked around the bug can then check whether its workaround is still needed and
remove it, instead of keeping it because nobody can tell._

**So far:**

- Shard-owned queues: `purgeQueue` inside a unit of work commits or rolls back with it (§1.1.1).
- A new event store engine, as a **preview** and off by default (`essentials.event-log.enabled`): its modules, a
  Spring Boot starter, admin operations and an admin console view (§2.2-§2.7). Existing event processors must be
  ported to run on it. Its parked aggregates retry by themselves, with meters, bulk admin operations and a health
  indicator (§2.13).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
   - [2.1 `AppendedEvents.appended` is public, for `EventStore` implementations (`postgresql-event-store`)](#21-appendedeventsappended-is-public-for-eventstore-implementations-postgresql-event-store)
   - [2.2 Admin API: seven operations for the new event-log engine's subscriptions (`admin-api-spec`, `spring-boot-starter-admin-api`, `admin-api-client-java`)](#22-admin-api-seven-operations-for-the-new-event-log-engines-subscriptions-admin-api-spec-spring-boot-starter-admin-api-admin-api-client-java)
   - [2.3 Spring Boot starter for the new event store engine, preview (`spring-boot-starter-postgresql-event-log`)](#23-spring-boot-starter-for-the-new-event-store-engine-preview-spring-boot-starter-postgresql-event-log)
   - [2.4 The snapshot beans accept any `ConfigurableEventStore` (`spring-boot-starter-postgresql-event-store`)](#24-the-snapshot-beans-accept-any-configurableeventstore-spring-boot-starter-postgresql-event-store)
   - [2.5 Annotated processor base classes for the new event store engine, preview (`postgresql-event-log-processor`)](#25-annotated-processor-base-classes-for-the-new-event-store-engine-preview-postgresql-event-log-processor)
   - [2.6 Admin console: a view for the new event-log engine's subscriptions (`spring-boot-starter-admin-ui`)](#26-admin-console-a-view-for-the-new-event-log-engines-subscriptions-spring-boot-starter-admin-ui)
   - [2.7 The new event store engine, preview (`postgresql-event-log`, `postgresql-event-log-processor`, `postgresql-event-log-migration`)](#27-the-new-event-store-engine-preview-postgresql-event-log-postgresql-event-log-processor-postgresql-event-log-migration)
   - [2.8 `PostgresqlNotificationDrain`: LISTEN notifications without pgjdbc holding them back (foundation)](#28-postgresqlnotificationdrain-listen-notifications-without-pgjdbc-holding-them-back-foundation)
   - [2.9 New event store engine: Micrometer meters (`postgresql-event-log`, `spring-boot-starter-postgresql-event-log`)](#29-new-event-store-engine-micrometer-meters-postgresql-event-log-spring-boot-starter-postgresql-event-log)
   - [2.10 New event store engine starter: tracing and append timing, as on the old engine (`spring-boot-starter-postgresql-event-log`)](#210-new-event-store-engine-starter-tracing-and-append-timing-as-on-the-old-engine-spring-boot-starter-postgresql-event-log)
   - [2.11 New event store engine: per-subscriber TRACE and causation in every processor (`postgresql-event-log`, `postgresql-event-log-processor`)](#211-new-event-store-engine-per-subscriber-trace-and-causation-in-every-processor-postgresql-event-log-postgresql-event-log-processor)
   - [2.12 New event store engine: processors read only the event types they handle (`postgresql-event-log-processor`)](#212-new-event-store-engine-processors-read-only-the-event-types-they-handle-postgresql-event-log-processor)
   - [2.13 New event store engine: parked aggregates retry by themselves (`postgresql-event-log`, `postgresql-event-log-processor`, `spring-boot-starter-postgresql-event-log`, admin API and console)](#213-new-event-store-engine-parked-aggregates-retry-by-themselves-postgresql-event-log-postgresql-event-log-processor-spring-boot-starter-postgresql-event-log-admin-api-and-console)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

None so far.

### 1.1 ⚠️ Silent behaviour changes

#### 1.1.1 A shard-owned `purgeQueue` inside a unit of work commits or rolls back with it (`postgresql-queue-shard-owned`, `postgresql-queue-shard-owned-adapter`)

`ShardOwnedDurableQueues.purgeQueue` used to delete the queue's messages on a connection of its own, committed at once,
even when called inside a `UnitOfWork`. Now, inside a unit of work that carries a JDBC connection (a
`HandleAwareUnitOfWork`, such as the JDBI and Spring-aware factories' units of work), it deletes them on that unit of
work's connection, so the purge commits with the caller's work, or is undone when that work rolls back - as
`PostgresqlDurableQueues.purgeQueue` and the shard-owned enqueue already do. Outside a unit of work nothing changes: the
purge commits on its own connection. The shard-owned admin API (`ShardOwnedQueuesApi.purgeQueue`) is unaffected. The
engine SPI gains `MessageQueue.purge(Connection)` (`PostgresqlMessageQueue` implements it; the default throws
`UnsupportedOperationException`).

**What changes for you:** a purge you call inside a unit of work - `Inbox.deleteAllMessages()`,
`Outbox.deleteAllMessages()`, a classic processor reset that purges its queue, your own code - is no longer kept if
that unit of work then rolls back, and its rows stay locked until the unit of work ends. This is what made
`EventLogPartitionedViewProcessor.rebuild()` atomic (§2.5).

**What to do:** nothing, unless you relied on a purge inside a unit of work surviving that unit of work's rollback;
then call it outside the unit of work.

---

## 2. New features

### 2.1 `AppendedEvents.appended` is public, for `EventStore` implementations (`postgresql-event-store`)

`AppendedEvents.appended(List<PersistedEvent>)` is now public, so an `EventStore` other than `PostgresqlEventStore` can report its appends to `AppendedEvents.recordDuring` (which `EventStreamDeciderCommandHandlerAdapter` uses to return the events a command appended). Additive; nothing to change. (Event store redesign, phase 8b.)

### 2.2 Admin API: seven operations for the new event-log engine's subscriptions (`admin-api-spec`, `spring-boot-starter-admin-api`, `admin-api-client-java`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

The admin contract (`essentials-admin-api.yaml`) and the generated Java client grow by seven additive paths under the new
tag `event-log-subscriptions`, for the subscriptions of the new event store engine (`postgresql-event-log`,
`postgresql-event-log-processor`). They answer from the database on any instance. You can list subscriptions, get one,
and page its parked events with `SUBSCRIPTION_READER`. You can reset a subscription, retry or discard a parked event, and
delete a retired per-instance subscription with `SUBSCRIPTION_WRITER`. `ESSENTIALS_ADMIN` can do both. The path is
`/event-log/subscriptions/{subscriber}/aggregate-types/{aggregateType}/…` with an optional `instanceName` query parameter;
leave it out to address an exclusive subscription.

`spring-boot-starter-admin-api` serves these paths only when the application has an `EventLogSubscriptionsApi` bean
(`DefaultEventLogSubscriptionsApi` over an `EventLog`). The new `spring-boot-starter-postgresql-event-log` declares one
when it is enabled (§2.3); an existing application serves nothing new and changes nothing; the paths answer 404 as any area without its SPI bean does. The
starter now depends on `postgresql-event-log-processor`, as it does on the shard-owned queue engine. The admin console
shows them in its event-log subscriptions view (§2.6). (Event store redesign, phase 8b.)

### 2.3 Spring Boot starter for the new event store engine, preview (`spring-boot-starter-postgresql-event-log`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

The new module `spring-boot-starter-postgresql-event-log` auto-configures the new event store engine
(`postgresql-event-log`) and its `EventStore` adapter (`EventLogEventStore`, `postgresql-event-log-processor`). It is
**off by default**: nothing happens until you set `essentials.event-log.enabled=true`. Enabled, it declares:

- the `EventLog`, started when the context is built and stopped on shutdown. `essentials.schema.mode` decides who creates
  the schema: the engine itself in `create`, the schema harness through the `EventLogSchemaContributor` bean in the other
  modes;
- `EventLogEventStore` as the `essentialsEventStore` bean. The unit-of-work factory, the local event bus, the JSON event
  serializer, the `PersistableEventMapper` and the causation beans (`essentials.eventstore.causation.enabled`) are built as
  the old starter builds them, and each backs off from a bean of your own;
- a `PageTransactionSource` for `ViewProjection`s (Spring-managed when there is a `PlatformTransactionManager`);
- a lifecycle bean that starts every `ViewProjection` and `EventReactor` bean once the context is refreshed and stops them
  before the engine stops (not in schema mode `emit`). It registers each one's aggregate type with the engine first, so
  a projection-only application need not;
- an `EventLogProcessorDependencies` bean for the annotated processor base classes (§2.5);
- with the admin API starter and an `EssentialsSecurityProvider` present, the `EventLogSubscriptionsApi` the admin API's
  event-log subscription endpoints need (§2.2).

Every `EventLogSettings` value is a property under `essentials.event-log.*` (for example `backstop-interval`,
`lease-duration`, `node-name`), with the engine's defaults.

**Beside the old event-store starter.** If `spring-boot-starter-postgresql-event-store` is on the class path too, the new
starter removes the old starter's `EventStoreConfiguration` from the auto-configurations while it is enabled, through an
`AutoConfigurationImportFilter`. That stops the old subscription manager, gap handler and CDC beans from being built on
top of the adapter, which does not support them. The old starter's snapshot, closing-books and aggregate admin
configurations stay and run on the adapter (§2.4). The old processors (`EventProcessor`, `ViewEventProcessor`,
`InTransactionEventProcessor`) do not run under the new engine: start-up fails before any bean is created, with one line
per such bean naming it, its class and the base class to port it to, instead of a bare missing-bean error (ESS-145).
Port them to `EventLogReactor`, `EventLogInboxProcessor`, `EventLogViewProcessor` and `EventLogPartitionedViewProcessor` (§2.5), or to `ViewProjection`
and `EventReactor`.

**Schema modes.** In `validate`, the engine's own schema is validated when the `EventLog` bean is built, so a missing one
fails start-up with a `SchemaValidationException` even when an aggregate type is registered while the context is built.
In `emit`, an aggregate type's tables go into the emitted script and nothing is created on the database. With `essentials.event-log.enabled` unset or `false`, the filter removes nothing.

### 2.4 The snapshot beans accept any `ConfigurableEventStore` (`spring-boot-starter-postgresql-event-store`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

`SnapshotConfiguration`'s snapshot store, snapshot job processor and snapshot repository factory beans, and
`DefaultAggregateSnapshotRepositoryFactory` (its constructors, builder field and `Builder.setEventStore`), now take
`ConfigurableEventStore<? extends AggregateEventStreamConfiguration>` instead of
`ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration>`. They only pass the store on to
`PostgresqlAggregateSnapshotRepository`, which already accepted any configuration type, so snapshots now also work on the
new engine's adapter (§2.3). Source- and behaviour-compatible: code that passes the old `PostgresqlEventStore` compiles
and works as before.

### 2.5 Annotated processor base classes for the new event store engine, preview (`postgresql-event-log-processor`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

Two abstract classes port an existing processor to the new engine by swapping its superclass:
`EventLogViewProcessor` for a `ViewEventProcessor`, `EventLogReactor` for an `EventProcessor`. Each is a thin shell over
the engine's processor of the same kind - one `ViewProjection` (`CONTIGUOUS`, exclusive, each page in one transaction
with the resume point) or one `EventReactor` (`FRESH`, exclusive, at least once) per aggregate type - so retries, page
shrinking and parking are the engine's.

```java
public class OrderSummaryView extends EventLogViewProcessor {
    public OrderSummaryView(EventLogProcessorDependencies dependencies, EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
        super("order-summary", List.of("Orders"), dependencies);
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @MessageHandler
    void on(OrderPlaced placed, DeliveredEvent event) {
        unitOfWorkFactory.getRequiredUnitOfWork().handle().execute("INSERT ...");
    }
}
```

- The constructor takes the processor name, the aggregate types (one subscription each, started and stopped together)
  and `EventLogProcessorDependencies` (the event log, the JSON event serializer, the page transactions) - a bean of the
  Spring Boot starter. `consistency()`, `startFrom()`, `errorSettings()` and `tenant()` can be overridden.
- `@MessageHandler` methods take the payload, optionally followed by a `DeliveredEvent` (it replaces the old
  `PersistedEvent` second parameter); the most specific payload type wins, and a payload with no handler counts as
  handled. In a view processor the page's unit of work (or Spring transaction) is bound, so `getRequiredUnitOfWork()` and
  its `Handle` work as before, and the writes commit with the page.
- A handler's exception reaches the engine unchanged: retried, then parked; a `ParkEventException` parks at once.
- An aggregate type not yet registered with the `EventLog` is registered (with the engine's default tables) at `start()`.
- `EventLogViewProcessor.rebuild()` replays the view from the start, and the protected hook `onRebuild(ProjectionContext)`
  clears it - the counterpart of the old processors' `onSubscriptionsReset(AggregateType, GlobalEventOrder)`. The
  rebuild stops every aggregate type's subscription, then, in one transaction of the processor's page source, calls
  `onRebuild` and resets every subscription to `startFrom()`; that transaction commits the cleared view and the reset
  resume points together, or neither, and the processor starts again if it was started. Unlike the old hook,
  `onRebuild` runs once per rebuild, not once per aggregate type, and writes as a handler does: the transaction's unit of
  work (or Spring transaction) is bound. When no subscription has progress yet, `rebuild()` returns `false` and the
  clear is rolled back. Call it outside a unit of work; it needs `UnitOfWorkPageTransactions` or
  `SpringPageTransactions`, not `PageTransactionSource.engine()`. In the trading demo the Users, InstrumentDetails and
  Positions views on the new engine now clear their tables on a rebuild, as their classic twins do on a reset.
- Both are foundation `Lifecycle`s: in Spring the components starter's lifecycle manager starts them, as it starts the
  old processors (not in schema mode `emit`). `start()` and `stop()` are idempotent and run one at a time, as do
  `rebuild()`, `EventReactor.reset` and `ViewProjection.rebuild`, but no lock is held while a stop waits for the delivery thread to
  finish, so a handler may call `reactors()`, `projections()` or `isStarted()` while its processor is stopping.
- **Not carried over** from the old processors: `MessageHandlerInterceptor`s, `@MessageHandler(unitOfWork = ...)`, the
  built-in permanent-error list (an `IllegalArgumentException` is retried and then parked like any other failure),
  inbox redelivery and the `RedeliveryPolicy`, Reactor, and the `FencedLock` (exclusivity is the engine's lease).

A third class, `EventLogInboxProcessor`, ports an inbox-based `EventProcessor` that keeps its inbox: it reads the events
of its aggregate types (`CONTIGUOUS`, from the beginning, one instance at a time) and puts each one whose payload one of
its `@MessageHandler` methods handles into the `Inbox` named after the processor; the inbox's consumer then calls that
method as the old processor did - payload, optionally an `OrderedMessage`, `@MessageHandler(unitOfWork = ...)` honoured,
`CausationContext` bound to the event, failures under the inbox's `RedeliveryPolicy`. A page of up to
`forwardPageSize()` events is enqueued in one page transaction that also moves the resume point, so each event is
forwarded exactly once; the enqueue joins that transaction through the `UnitOfWorkFactory` passed in, which must be the
one the page transactions and the inbox's queues share. The event travels with its payload, keyed by aggregate id and
ordered by event order, with its metadata plus its event id under `EventLogInboxProcessor.EVENT_ID_METADATA_KEY`. Hooks,
named as the old processor's without the `get`: `inboxMessageConsumptionMode()`, `numberOfParallelInboxMessageConsumers()`,
`inboxRedeliveryPolicy()` (a `FailoverRedeliveryPolicy` by default), `forwardPageSize()` (the old `getForwardBatchSize()`;
the engine's page size by default), `errorSettings()`. The trading demo's six inbox reactions run on it.

A fourth class, `EventLogPartitionedViewProcessor`, ports a `PartitionedViewEventProcessor`: a view whose `@MessageHandler`
methods run on every instance, each handling its share of the aggregates. One instance at a time reads the events
(`CONTIGUOUS`, from the beginning) and queues every event a handler takes on the durable queue `<name>:queue`, with one
`queueMessages` per page of up to `forwardPageSize()` events inside the page transaction, so the messages commit with the
resume point and each event is queued exactly once (the classic class forwards per batch in the subscription's unit of
work). The messages are the classic class's payload messages: key `<aggregateType>|<aggregateId>`
(`EventLogPartitionedViewProcessor.qualifiedKey`, equal to the classic `qualifiedKey`), order = event order, the event's
metadata plus `PARTITIONED_VIEW_AGGREGATE_TYPE` and `PARTITIONED_VIEW_EVENT_ID`, the classic metadata keys. A classic
*reference* message (the classic default without `forwardsEventPayloads()`) is refused, so drain the queue before
switching. Every instance consumes the queue with `numberOfParallelQueuedMessageConsumers()` threads, each message in one
unit of work (`messageUnitOfWork()`), with the plain aggregate id as `OrderedMessage#getKey()` and `CausationContext` bound
to the event; `start()` refuses a queue implementation that does not keep key order across instances and
`UnitOfWorkMode.NONE` handlers, as the classic class does. Other hooks: `durableQueueRedeliveryPolicy()` (a
`FailoverRedeliveryPolicy` by default), `forwardPageSize()`, `errorSettings()`. `rebuild()` with the hook
`onRebuild(ProjectionContext)` stops this instance's consumers, refuses while any other instance consumes the queue (or
the queue implementation cannot tell), then in one transaction of the page source runs `onRebuild`, purges the queue
(queued, in-flight and dead-lettered messages) and resets the reading side, and restarts. The cleared view, the empty
queue and the reset progress commit together or not at all: the purge joins the rebuild's unit of work (§1.1.1). The
trading demo's two partitioned views run on it.

### 2.6 Admin console: a view for the new event-log engine's subscriptions (`spring-boot-starter-admin-ui`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

The admin console has a new **Event-log subscriptions** view under *Event store*, for the subscriptions of the new event
store engine. It uses only the seven `event-log-subscriptions` operations of the admin API (§2.2). It lists every
instance's subscriptions from the database, and you can filter the list by aggregate type. For each subscription it
shows the subscriber, aggregate type, instance, mode, progress model, a status, resume point, lag, lease owner and fence,
and the number of parked events. Pick a subscription to see its details and its parked events, page by page. From there
you can retry or discard a parked head, reset the resume point to a global order or to the head, and delete a retired
per-instance subscription. Each write asks for confirmation on the page first. Deleting a retired instance also asks you
to type the instance name.

You need `SUBSCRIPTION_READER` (or `ESSENTIALS_ADMIN`) to open the view and `SUBSCRIPTION_WRITER` to use its write
buttons. Without the role, the nav item or button is shown disabled, as in the other views. An application without the
new engine serves no `EventLogSubscriptionsApi`, so the view shows the API's 404, as any area without its SPI bean does.
The status column is the console's own summary of the row (parked, not leased, leased, per instance), not a field of
the contract. "Reset to the head" means the row's resume point plus its lag, read again when you confirm the reset.
(Event store redesign, phase 10.)

### 2.7 The new event store engine, preview (`postgresql-event-log`, `postgresql-event-log-processor`, `postgresql-event-log-migration`)

**Preview:** this release ships the new event store engine as a preview. It is opt-in (`essentials.event-log.enabled=true`), and its API, properties and tables may change in a minor release. The classic event store stays the default and fully supported. Existing `EventProcessor`s and `ViewEventProcessor`s do not run on the new engine and must be ported (§2.5): there is deliberately no `EventStoreSubscriptionManager` adapter.

Three new modules make up the engine that §2.2-§2.6 build on:

- `postgresql-event-log`: the `EventLog`. Appends and stream loads, and subscriptions that follow the database's own
  transaction visibility (a watermark from the `pg_stat_activity` view) instead of CDC, a gap handler or a fenced lock. Two
  consistency modes per subscription: `CONTIGUOUS` (strict global order, never above a hole) and `FRESH` (every committed
  event as soon as it is seen, in per-aggregate order). Exclusive subscriptions run on engine leases kept in
  `el_progress`; a failing event is retried alone and then parked while the rest of the stream continues. Its tables are
  prefixed `el_`. It refuses to start where the role cannot see other backends' `backend_xid` (grant
  `pg_read_all_stats`).
- `postgresql-event-log-processor`: `ViewProjection` (each page in one transaction with its resume point) and
  `EventReactor` (at least once), the annotated base classes (§2.5), the `EventStore` adapter `EventLogEventStore` for
  aggregate repositories, snapshots and closing books, and the admin SPI (§2.2).
- `postgresql-event-log-migration`: a one-way, same-database move of aggregate types from the classic engine's tables,
  with online copy epochs, a short cut-over with every classic node stopped, hash verification and the import of
  resume points. It only reads the classic tables; there is no way back but a backup.

A handler's own connection error (SQLState `08*` or `SQLTransientConnectionException`, also wrapped) is retried and
then parked like any other handler failure, as the classic Inbox dead-letters it; only a failure of the engine's own
page connection or statements is retried indefinitely. Earlier builds of the preview retried such a handler error
forever, so the subscription stalled behind one event.

Hardened before release (code review of the preview). Each item changed what an earlier preview build did:

- An `EventLogObserver` that throws no longer stops delivery. The engine wraps the observer, logs the exception (WARN at
  most once a minute) and goes on. Before, a throwing `onError` or `onAttach` ended the shared reader thread for good:
  every subscription of that `EventLog` waited on its attach reply forever, with nothing logged. The same applied to an
  `Error` on the reader thread; now a new reader thread starts after one backstop interval and serves the requests
  that were queued.
- One aggregate type whose reader fails to start (a missing table, for example) no longer restarts the reader of every
  other type. If the shared connection is still valid, only that type's subscriptions are detached, and it alone
  retries after one backstop interval - commits to it do not pull the retry forward. Observe it: before, every subscription of every type logged a detach with
  reason `READER_RESTART`; now only the failing type's do.
- A long writing transaction in another database on the same PostgreSQL server no longer holds every type's watermark
  back. The running set now counts only backends connected to the event log's own database. Before, an idle-in-
  transaction session in an unrelated database stalled every `CONTIGUOUS` subscriber until it ended. A long writing
  transaction in the same database still holds the watermark, as it must.
- An `Error` thrown by a handler (an `AssertionError`, a `StackOverflowError`) is a handler failure like any other: the
  page is rolled back, the event retried and then parked. Before, the page transaction was left open, its connection
  leaked and the delivery thread ended without reporting anything; `isRunning()` just turned false.
- Stopping a subscription while it retries a parked aggregate rolls that retry back. Before, the interrupted retry left
  its transaction open, holding the subscription's progress row and parked rows locked. A parked retry of an exclusive
  subscription is now bound by the lease like a page: it cannot hold the progress row past one lease duration.
- An `OutOfMemoryError`, `InternalError` or `LinkageError` (`NoClassDefFoundError`, …) thrown by a handler is retried
  like the engine's own failures and never parks the event: it says nothing about the event, and a parked event would
  need releasing by hand after the restart or redeploy that fixes it. Other `Error`s, `StackOverflowError` among them,
  are retried and then parked.
- A parked retry of a periodic subscription no longer holds the subscription's progress row while its handler runs:
  it reads the held events in one short transaction and deletes them and saves progress in another, so a slow handler
  no longer blocks the lease renewal. Before, an exclusive subscription's retry slower than `lease-local-stop` (7 s)
  was rolled back every time and the aggregate stayed parked. A reset during the handler now goes ahead and the events
  are delivered again. An in-transaction subscription's retry now also runs its handler before it locks the progress
  row (§2.13), so no parked retry has to finish within `lease-local-stop` any more.
- The lease keeper survives a renewal pass that ends in an `Error`; before, its thread ended and every exclusive
  subscription of the node suspended until another subscription registered. A retry or discard request made while a
  subscription is stopping now completes (with an exception) instead of never completing.
- `SubscriptionHandle.isRunning()` stays `true` until the delivery thread has actually ended. `stop()` still returns
  after 30 s if a handler ignores interrupts, but now logs a WARN, and the admin's `deleteRetiredInstance` and a
  re-subscribe of the same exclusive key see the subscription as running. A start that times out (60 s) stops its
  delivery thread instead of leaving it running unreachable, and two concurrent subscribes of the same exclusive key
  can no longer both start.
- `deleteRetiredInstance` on a per-instance subscription that is in fact still running on another node no longer
  strands its parked aggregates. Per-instance rows carry no lease, so the admin cannot tell the two apart; the running
  instance recreates its progress row at its next save and now also reloads its parked set, which the delete emptied.
  Before, later events of a previously parked aggregate were parked behind a head that no longer existed and were
  never delivered; a retry of that head answered `NOT_A_HEAD`.
- The cross-JVM wake listener notices a connection that died silently (a firewall or NAT dropping it without a reset):
  after 30 s without a notification it checks the connection with one round trip and reconnects if it does not
  answer. Before, wake-ups from other JVMs were lost until TCP keepalive gave up, often hours, and readers fell back
  to the backstop interval without any log line. Under load notifications keep arriving and the check never runs.
- A page whose unit of work (or Spring transaction) a handler marked rollback-only now fails like any other failing
  page: it is retried, then the event parked. The engine asks the page source right after the handler returns (new
  `OpenPage.rollbackOnly()`, default `false`), so it counts as the handler's failure, not the engine's. Before, `commit()` rolled back silently and the engine counted the page
  and its resume point as committed, so the next save conflicted and the page was delivered again.
- The annotated processor base classes deliver each aggregate type's typed id (`OrderId`), resolved from the event
  store's `AggregateEventStreamConfiguration` as on the classic engine. Before, every handler got a `String`, so a
  handler ported by swapping its superclass failed at runtime. `EventLogProcessorDependencies` has a fourth component,
  `aggregateIdSerializers`; its three-argument constructor keeps `String` ids, and the starter wires the event store's.
- The event-log migration's epoch also waits for prepared two-phase transactions (`PREPARE TRANSACTION`, XA/JTA).
  Before, it waited only for the transactions in `pg_stat_activity`, which a prepared one leaves: an old-engine append
  prepared there and committed after the epoch had copied past its global order was never copied - silent event loss
  at the cut-over. An orphaned prepared transaction now holds `copyEpoch()` until it is committed or rolled back; the
  report's blockers name it (`prepared transaction <gid>`, pid 0).
- The event-log tail reader no longer lets an unrelated long transaction stall a type's `CONTIGUOUS` subscribers. A
  hole's candidate waited for every transaction of the database that held an xid, so a batch job, a migration or a
  session idle in a transaction after any write held the watermark of every type for its whole life. After a candidate
  has waited 1 s the reader now asks `pg_locks` which of those transactions hold a `RowExclusiveLock` on the type's
  event table (the lock every append takes before it draws a `global_order`) and waits only for them; the normal probe
  is unchanged. New gauge `essentials.eventlog.reader.oldest_candidate_age` per aggregate type shows a stall.
- An event-log aggregate parked behind a failing head can always be released now. A retry or discard handed the head
  and every event held behind it to the handler in one transaction, so a second poisoned event in the backlog failed
  every retry and every discard: the aggregate stayed held for good. A held event that fails now becomes the parked head
  (one attempt counted) after the events before it are applied; retry or discard it next. A retry also hands over at
  most `pageSize` events per transaction, continuing on its own at the next parked check, instead of the whole backlog.
- `ViewProjection.rebuild(...)` refuses with `IllegalStateException` ("... did not stop") when its subscription is still
  running after the stop - a handler that ignores interrupts outlives the bounded stop - as `EventLogViewProcessor.rebuild()`
  already did. Before, it cleared the view and reset the progress while that handler could still apply a page to it.
- The event-log admin reset (`EventLogSubscriptionsApi.resetSubscription`, `POST /event-log/subscriptions/.../reset`)
  also refuses the subscription of a `ViewProjection` bean, with `IllegalArgumentException` (HTTP 400) pointing to its
  `rebuild(truncate)`. Before, only processor-owned views were refused; a `ViewProjection` bean's reset moved the resume
  point and the projection re-applied events on top of the rows its view already held, so a counter or an insert-only
  view double-counted. A reset that is intended stays available as `EventLog.admin().reset(key, from)`.
- The event-log engine works on a connection pool configured to hand out connections with auto-commit off (for
  example `spring.datasource.hikari.auto-commit=false`, a common Hibernate setting). Its writes outside its own
  transactions assumed auto-commit: on such a pool a parked event's row and a periodic subscription's resume point were
  never committed, and the pool rolled them back on return, so a failed event counted as handled with no parked row. Every
  connection the engine borrows is now switched to auto-commit and handed back with auto-commit off again, as the pool
  gave it; a connection already in auto-commit is untouched. The engine's wake listener no longer hands its connection
  back with auto-commit switched on.
- The shard-owned queue's wake-up listener works on such a pool too. Its `LISTEN` ran in a transaction that never
  committed, so no notification arrived and every owner fell back to the backstop poll, with nothing logged.
- Event-log metrics: stopping one instance of a subscriber no longer removes the meters that another running instance
  of it on the same aggregate type shares (same tags); they are removed when the last one stops. Before, the running
  instance's `failed`/`parked` counters and lag gauge disappeared and came back from zero. A lease loss reported after
  the stop no longer registers meters that nothing removes. New `EventLogObserver.onStarted` (default no-op) pairs with
  `onStopped`.
- A non-exclusive re-subscribe of a key whose old delivery thread is still running (a handler that ignored the
  interrupt and outlived `stop()`) is refused with `IllegalStateException` ("... is already running in this EventLog"),
  as an exclusive one already was. Before, it started a second delivery thread on the same progress row, and events
  were delivered twice.
- An aggregate type whose registration is refused (for example `orders` after `Orders`, which map to the same table)
  no longer stays registered. Before, every later `EventLog.findCausedBy` (and so the adapter's `loadEventsCausedBy`)
  threw `IllegalStateException` for every caller, and a second `registerAggregateType` of it returned without error.
- The wake-up notifier no longer spins a CPU core when a send is interrupted inside the JDBC driver. Before, it
  restored the interrupt flag, so the idle notifier never parked again for the life of the `EventLog`.
- `EventLog.stop()` interrupts every subscription first and then waits for them together, so a shutdown with several
  handlers that ignore interrupts waits one join timeout (30 s) rather than one per stuck subscription. Before, each
  stuck subscription was interrupted only after the previous one's timeout had run out; each still logs its own
  warning naming the subscription.
- The annotated processor base classes deliver the application's own `Tenant` type, read with each aggregate type's
  `TenantSerializer` from the event store's configuration, the one the `EventLogEventStore` adapter writes with. Before,
  every tenant was read as a `TenantId`, so with a `TenantSerializer` bean of your own a handler that cast
  `event.tenant()` to your tenant type threw `ClassCastException` and the event was parked. `EventLogProcessorDependencies`
  has a fifth component, `tenantSerializers`; its three- and four-argument constructors keep `TenantId` tenants. Without
  a `TenantSerializer` bean the starter keeps reading a tenant as a `TenantId` (the single-tenant default would throw on
  an event appended through the `EventLog` with a tenant).
- `onRebuild` and a `ViewProjection.rebuild(Truncate)` get the guarded connection a page handler gets: `commit`,
  `rollback`, `setAutoCommit` and `close` throw. Before, a hook that committed made the clear commit before the
  progress reset, and a crash in between left an empty view resuming at the head. New public
  `PageTransaction.guarded(OpenPage)` gives other code the same guard. A `ViewProjection` whose rebuild fails is
  restarted if it was running; before, it stayed stopped.
- The admin API's subscription reset refuses a subscription owned by an `EventLogViewProcessor`,
  `EventLogPartitionedViewProcessor` or `EventLogInboxProcessor` running in the application that serves the call
  (`IllegalArgumentException`, naming the processor). Use the view processors' `rebuild()`, which clears the view (or
  purges the queue) in the same transaction; an inbox processor has no `rebuild()`, so replay it with
  `EventLog.admin().reset(key, from)` from the application if that is intended. Before, the reset moved the resume
  point alone: a view that is not idempotent got duplicate rows on the replay.
  `DefaultEventLogSubscriptionsApi` takes the refusal as an optional third argument.
- The Micrometer lag gauge (`essentials.eventlog.subscription.lag`) reads 0 on a node that lost or suspended an
  exclusive subscription's lease, until it owns it again and saves. Before, every standby reported a lag that grew
  without bound (its position froze while the shared cursor moved on), which tripped lag alerts on healthy clusters.
  A stopped subscription's meters are removed from the registry; before, they stayed and its lag kept growing.
  `EventLogObserver` has a new default method, `onStopped(subscriber, aggregateType)`.
- The event-log starter declares the two beans of the old starter it used to drop: the
  `AnnotationBasedInMemoryProjector` (when `eventsourced-aggregates` is on the class path, unless
  `essentials.eventstore.add-annotation-based-in-memory-projector=false`) and
  `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream` (with
  `essentials.eventstore.auto-flush-and-publish-after-append-to-stream=true`). Before,
  `eventStore.inMemoryProjection(type, id, SomeView.class)` threw "Couldn't find an InMemoryProjector" after switching
  to the new engine, and the auto-flush property silently did nothing.

The payload columns are `json`, not `jsonb`: the stored text is the serializer's compact output, byte for byte the
classic engine's. Code that hashes or compares payload text sees no difference after a migration.

Measured on the trading demo (one and two instances): equal throughput, booked latency outside the bursts at or near the
classic engine's (p99 1.14× with one instance, 0.91× with two), about 1.2-1.3× the PostgreSQL CPU and 22 % more
statements per order, and about 19 % less WAL. How it works: `docs/design/event-log.md`; the consumer reference:
`LLM/LLM-postgresql-event-log.md`. (Event store redesign, phases 2-10.)

### 2.8 `PostgresqlNotificationDrain`: LISTEN notifications without pgjdbc holding them back (foundation)

New public helper `dk.trustworks.essentials.components.foundation.postgresql.PostgresqlNotificationDrain` for a
hand-written LISTEN loop on a dedicated connection: `open(Connection)`, `next(timeoutMillis)` and `close()` (call it
before the connection is released). pgjdbc's `PGConnection.getNotifications(timeout)` returns nothing while
notifications keep arriving less than about a millisecond apart, so a loop built on it delivers late under dense
traffic. The drain switches that peek off by reflection and falls back to a short query poll (one WARN per JVM) if
pgjdbc's internals change; `openQueryPoll` forces the poll. Lifted from the new event store engine's wake listener,
which now uses it unchanged; the shard-owned queue's listener uses it too (§3). `ListenNotify` and
`MultiTableChangeListener` are unchanged. Requires `org.postgresql:postgresql` on the classpath, as before.

### 2.9 New event store engine: Micrometer meters (`postgresql-event-log`, `spring-boot-starter-postgresql-event-log`)

**Preview.** `MicrometerEventLogObserver` (package `dk.trustworks.essentials.components.eventlog.observability.micrometer`)
binds the engine's `EventLogObserver` callbacks to Micrometer, with the tags of the old engine's subscription meters
(`subscriber_id`, `aggregate_type`, optional `Module`):

| Meter | Kind | What |
|---|---|---|
| `essentials.eventlog.subscription.lag` | gauge | events between the subscription's saved resume point and the reader's cursor for its aggregate type: the new engine's `DurableSubscriptions_EventOrder_Diff` |
| `essentials.eventlog.subscription.failed` | counter | handler and engine failures (retried or parked after) |
| `essentials.eventlog.subscription.parked` | counter | events parked or held behind a parked head |
| `essentials.eventlog.subscription.lease_lost` | counter | leases an exclusive subscription lost |
| `essentials.eventlog.subscription.page` | timer, tag `outcome` = `committed`/`failed` | in-transaction page time, from opening the page transaction to its commit or rollback |
| `essentials.eventlog.lease.renewal_failed` | counter (no subscriber tag) | lease keeper passes that failed |

Meters are updated per page, read or failure, never per event, from meter instances cached per subscription. The
starter installs the observer when a `MeterRegistry` bean exists and the application declares no `EventLogObserver` of
its own (`essentials.event-log.metrics.enabled=false` switches it off); an application observer replaces it - extend
`MicrometerEventLogObserver` and call `super` to have both. `micrometer-core` is `provided` in the engine and optional in
the starter. `EventLogObserver` gained `onReaderCursor(aggregateType, cursor)` and `nanos` variants of
`onPageCommitted`/`onPageFailed`, all default methods: an existing observer compiles and is still called.

### 2.10 New event store engine starter: tracing and append timing, as on the old engine (`spring-boot-starter-postgresql-event-log`)

**Preview.** The starter now declares the event store interceptors the old starter's `EventStoreConfiguration` declares
(which it drops), under the same conditions and properties: `RecordExecutionTimeEventStoreInterceptor`
(`essentials.eventstore.metrics.enabled` and `.thresholds.*`, module tag from `essentials.tracing-properties.module-tag`)
and, with `management.tracing.enabled=true`, `MicrometerTracingEventStoreInterceptor`
(`essentials.eventstore.verbose-tracing`). `EventLogEventStore` runs them on appends and loads, and runs the tracing
interceptor as a `PersistableEventEnricher`, so an append stores the current trace context (`traceparent`) in each event's
metadata, as the old engine does. Both need Micrometer (and Micrometer Tracing) on the class path; without it nothing is
declared.

### 2.11 New event store engine: per-subscriber TRACE and causation in every processor (`postgresql-event-log`, `postgresql-event-log-processor`)

**Preview.** Each subscription logs per event at TRACE on a logger of its own,
`dk.trustworks.essentials.components.eventlog.Subscription.<subscriber>` (characters outside `[A-Za-z0-9_-]` become `_`):
`delivering <eventType> global_order <n> event_order <m> to <subscriber>`, and `page committed: ...` per in-transaction
page. Switch it on for one subscriber with `logging.level.<that logger>=TRACE`; off, it costs one level check per page.

`EventLogViewProcessor` and `EventLogReactor` now bind `CausationContext` to the delivered event around each handler
call, as `EventLogInboxProcessor` and `EventLogPartitionedViewProcessor` already did: an event a handler appends records
the delivered event as its `caused_by_event_id` (and a command or queued message it sends carries it on). Before, such
events had no cause.

### 2.12 New event store engine: processors read only the event types they handle (`postgresql-event-log-processor`)

**Preview.** `EventLogViewProcessor`, `EventLogReactor`, `EventLogInboxProcessor` and `EventLogPartitionedViewProcessor`
now subscribe with an `EventTypeFilter` built from the payload parameter types of their `@MessageHandler` methods, so
PostgreSQL skips the other event types of the aggregate type. Before, every event was read, deserialized and given its
own page transaction, only to be ignored because no handler took it - the main source of the new engine's extra
database work per event.

- **When it applies:** only when every handled payload type is a final class (a record, an enum, a final class) outside
  the JDK. A handler on an interface, an abstract or other non-final class, `Object` or `String` also receives other
  classes, which an exact-name filter cannot express, so such a processor keeps receiving every event type. The choice
  and its reason are logged at INFO when the processor starts.
- **Existing subscriptions keep their position:** the new filter is stored on the subscription's progress row at the
  next start; nothing behind the position is delivered again.
- **To change it:** override `protected EventTypeFilter eventTypeFilter()` - return `EventTypeFilter.all()` when your
  `JSONEventSerializer` maps stored event type names to classes other than the named ones, or add names to
  `super.eventTypeFilter()`.

### 2.13 New event store engine: parked aggregates retry by themselves (`postgresql-event-log`, `postgresql-event-log-processor`, `spring-boot-starter-postgresql-event-log`, admin API and console)

**Preview.** A parked aggregate no longer waits for an operator. The engine retries it by itself, in the aggregate's
order, forever: first `parkedRetryInitial` (1 s) after it parked, then after each failed retry twice as long, at most
`parkedRetryMax` (5 min). A downstream outage that parked events therefore heals on its own once it ends, at most
about `parkedRetryMax` later. (`7367c9bc0`)

- **Terminal heads wait for an operator.** A head is terminal when it failed with `ParkEventException`. The processors
  (`postgresql-event-log-processor`, and so every base class) now throw `ParkEventException` for an event whose payload
  or metadata text is not JSON at all (blank, truncated), so it parks as terminal at once instead of retrying
  (`4704c1e2c`). An unknown event class, or valid JSON that does not fit the class, keeps retrying, so a rolling
  deployment that brings the class heals it. The latest failure decides: a terminal head whose operator retry fails
  with an ordinary exception retries by itself from then on, and a retrying head that throws `ParkEventException`
  becomes terminal.
- **Settings.** `EventLogSettings.parkedRetryInitial` / `parkedRetryMax`, per subscription on `ErrorSettings` (its
  four-argument constructor is kept); Spring: `essentials.event-log.parked-retry-initial` / `parked-retry-max`.
- **Cost.** Only the running owner retries, at most four due heads per pass, on the same serve path as an operator's
  retry. While nothing is parked or due no extra statement is sent. The schedule lives on the parked row
  (`el_parked_events` gains `retries`, `next_retry_at`, `terminal`, `last_failed_at`, added with
  `ADD COLUMN IF NOT EXISTS`) and is measured by the database's clock, so a new lease owner continues it.
- **Late locking.** Every serve of a parked head, automatic or operator, runs the handler with no lock on the progress
  row and locks it only afterwards. Before, an in-transaction subscription's retry held the progress row while its
  handler ran, the lease renewal waited on it, and a retry slower than `lease-local-stop` (7 s) was rolled back every
  time. A reset during the handler now goes ahead and the retry rolls back.
- **Meters and observer** (`03eb00714`): `essentials.eventlog.subscription.parked.heads{state=retrying|terminal}` (gauge),
  `essentials.eventlog.subscription.parked.retries{origin=auto|operator, outcome=released|failed|deferred}` (counter) and
  `essentials.eventlog.subscription.parked.oldest.age` (time gauge). `EventLogObserver` gains the default methods
  `onParkedRetry` and `onParkedHeads`; `SubscriptionStatus` gains `retryingHeads` and `terminalHeads` (old constructor
  kept, deprecated).
- **Admin** (`d23c91436`, `0de20325e`): `EventLogAdmin.retryAllParked(key)` / `discardAllParked(key)` store the request
  on every parked head of a subscription in one statement (`BulkParkedResult`), and `parkedSummary()` reads every
  subscription's parked heads in one statement. `SubscriptionRow` gains `retryingHeads`, `terminalHeads`,
  `oldestParkedAt`, `nextRetryAt`, `maxRetries`, `lastFailedAt`; `ParkedRow` gains `retries`, `nextRetryAt`,
  `terminal`, `lastFailedAt` (old constructors kept, deprecated). The admin API adds `POST
  .../parked-events/retry-all` and `.../parked-events/discard-all` (`SUBSCRIPTION_WRITER`), so the event-log area has
  nine operations and the contract 69; the API rows carry the same new fields. The console's event-log view shows
  "N terminal - needs action" / "N retrying - self-healing", the oldest parked age, per head its retries, next retry
  and last failure, and *Retry all parked* / *Discard all parked* buttons (discard asks you to type the subscriber).
- **Health indicator** (`a642d7e84`): `EventLogParkedHealthIndicator` (`eventLogParked`) on `/actuator/health`. Retrying
  heads alone report `UP`; a terminal head reports `essentials.event-log.health.terminal-parked-status` (default
  `DOWN`); a failing read reports `DOWN`. The summary is cached for `essentials.event-log.health.cache-ttl` (10 s). Off
  with `management.health.eventLogParked.enabled=false`. `spring-boot-health` is now a `provided` dependency of the
  starter.

**What changes for you:** parked aggregates are released without you once the cause goes away. A
`ParkEventException` you throw for a condition that may pass now makes the head terminal; throw an ordinary exception
instead. On upgrade, a head parked before it has no stored schedule and is **due at once**: every aggregate parked under
an earlier preview build is retried as soon as its subscription runs.

**What to do:** keep `eventLogParked` out of the `liveness` and `readiness` health groups - a terminal park is a data
problem a restart does not fix, and every replica reports it - and alert on it or on
`essentials.eventlog.subscription.parked.heads{state=terminal}` instead. If the composite `/actuator/health` is a probe,
set `terminal-parked-status` to a status the probe ignores or switch the indicator off.

**How to observe it:** a parked head's `nextRetryAt` in the admin API or console, and
`essentials.eventlog.subscription.parked.retries{origin=auto}` counting. Before, a parked aggregate stayed parked until
an operator retried or discarded it.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| CDC (`postgresql-event-store`) | PostgreSQL 17.12 and 18 refuse an output plugin that is not listed in `output_plugin_libraries` (default `pgoutput, test_decoding`), so creating a `wal2json` slot fails with `library "wal2json" may not be used as an output plugin`: under `cdc.mode=require` start-up fails, under `auto` subscribers silently stay on polling. What to do: add `wal2json` to the setting (`postgresql.conf`: `output_plugin_libraries = 'pgoutput, test_decoding, wal2json'`; SQL: `ALTER SYSTEM SET output_plugin_libraries = 'pgoutput', 'test_decoding', 'wal2json'`, each name quoted on its own, then `SELECT pg_reload_conf()`), or use `pgoutput`. How to observe: `Wal2JsonLogicalDecodingPlugin.unusableReason` read only "wal2json plugin not usable"; it now names `output_plugin_libraries` and both ways to add `wal2json`. `pgoutput` is unaffected. | ported from `bbf1d28f0` |
| Shard-owned queues (`postgresql-queue-shard-owned`) | The cross-instance wake-up (Tier 1, `ShardWakeupListener`) stalled under dense notification traffic: pgjdbc's `getNotifications(200)` hands nothing back while notifications keep arriving less than ~1 ms apart, so owners on other instances were woken late, by the backstop (`pollBackstop`, 500 ms by default) at worst. The listener now drains with `PostgresqlNotificationDrain` (§2.8). To observe it: cross-instance commit-to-handler latency under sustained load (`ShardWakeupListenerDrainIT`, `-Dbenchmark.run=true`, 10 s backstop): p50 / p99 at 1 000 msg/s 18.6 / 566 ms before, 0.19 / 0.28 ms after; the old drain in the same run 88 / 973 ms at 1 000 msg/s and 3.5 s / 55 s at 3 000 msg/s, against 0.19 / 0.30 ms. Nothing to do on upgrade; a WARN `Cannot pin pgjdbc's notification peek` means a pgjdbc version whose internals changed, and the listener then polls with `SELECT 1` instead. | - |
| New event store engine, preview (`postgresql-event-log`) | Under load, an aggregate type with rolled-back appends could hold its watermark for 1-6 s, so every `CONTIGUOUS` subscription of that type stopped delivering while appends kept coming. When several types were read in one flush, the reader recorded the whole flush's wall time as each type's running-set probe cost, and the probe clock (`cost x 100 x max(1, backends / 10)`) then put the next running-set read seconds out. A hole retires only through a running-set read, so the watermark waited. To observe it: `EventLogObserver.onRead`'s `oldestCandidateAge` climbed to seconds during bursts. Now only a probe measured on its own sets the cost, and a flush of several types keeps the previous one. Nothing to do on upgrade. | phase 10 gate fix |
| New event store engine, preview (`postgresql-event-log`) | Even with an honest probe cost, the probe clock's back-off (`cost x 100 x max(1, backends / 10)`, at most `probeBackoffMax`) put the next running-set read 1.8-3 s out at a 3-5 ms probe and a 60-connection pool. A hole left by a rolled-back append retires only through a running-set read, so the type's watermark - and every `CONTIGUOUS` subscription of it - waited that long. While a hole, range or candidate of a type is outstanding, the wait before its next running-set read is now capped by the new setting `EventLogSettings.holeProbeWaitMax` (Spring: `essentials.event-log.hole-probe-wait-max`), default 100 ms. Outside such holes nothing changes. While holes wait the probes may take more than the cost rule's ~1 % of a core (about 3-5 % per probing reader at a 3-5 ms probe); raise the setting to trade hole latency back for probe cost. Nothing to do on upgrade. | phase 10 gate fix |

---

## 4. The `essentials` Claude Code plugin

0.61.0 shipped plugin `0.61.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- **0.61.0-15** — the event-log reference documents parked aggregates that retry by themselves (§2.13): terminal
  heads, the two settings, the parked meters and observer callbacks, the bulk admin operations and the health
  indicator; it drops the `lease-local-stop` gotcha for parked retries, leads with the measured benefits over the
  classic engine with CDC, and the admin API count is 69.
- **0.61.0-14** — the event-log reference, module index and traps index say the new engine is a preview from 0.62.0,
  not 0.61.0, which shipped without it.
- **0.61.0-12** — the event-log reference warns that an exclusive in-transaction subscription's parked retry must finish
  within `lease-local-stop`.
- **0.61.0-11** — the event-log reference documents the admin reset refusal per processor kind and that a timed-out
  `stop()` makes a re-subscribe of the key refuse rather than wait.
- **0.61.0-10** — the event-log reference counts four processor base classes everywhere, names the `pg_stat_activity`
  view as the watermark source, and documents the engine's review fixes (§2.7 "Hardened before release"); the admin
  API reference's Endpoints table gains the shard-owned queue and aggregate lifecycle and archive tags.
- **0.61.0-5** — the event-log reference documents `EventLogPartitionedViewProcessor`, the port of a classic
  `PartitionedViewEventProcessor` (§2.5), and lists it in the porting table.
- **0.61.0-4** — the event-log reference's porting section states plainly that `EventLogInboxProcessor` forwards
  exactly once, `onRebuild` runs once per rebuild and `EventLogReactor` is at least once; the three traps that framed
  these as pitfalls are retired.
- **0.61.0-2** — a new reference, `LLM-postgresql-event-log.md`, for the new event store engine (preview, §2.7), the
  trap `ESS-145`, and the admin API's operation count corrected to 67 (§6).
- **0.61.0-1** — the event-store reference says CDC with `wal2json` on PostgreSQL 17.12 / 18 needs `wal2json` in
  `output_plugin_libraries`, and how to add it.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- `essentials-trading-demo`: its compose files use the repository's PostgreSQL image under its new tag
  `essentials-postgres:17.5-2` (see §7).
- **`essentials-trading-demo` runs on either event store engine.** `trading-demo.engine` picks it: `classic` (the
  default) or `event-log`, which the `event-log` profile sets (combine it with the others, e.g. `compose,event-log`, on
  a fresh database - the new engine has its own `el_*` tables and does not read events the classic engine wrote). Every
  processor, view, partitioned view and reaction alike, was ported by one pattern: the projection or reaction keeps its
  class name and holds the handler bodies, engine-neutral, and two thin wrappers - a classic processor and its
  new-engine twin, each a bean on its own engine only - carry the `@MessageHandler`s and call into it. Processor names,
  inbox and queue names, per-account ordering and the partitioned views' every-instance consumption are the same on both
  engines (`config/CLAUDE.md`, "One handler body, two engine wrappers"). The three EF-2 measurement processors in
  `_demo_harness` stay classic-only, and under `event-log` CDC is off, since the new engine does not use it.
  `TradingDemoEventLogIT` starts the whole demo on the new engine and runs a customer from deposit to a filled order
  through all three processor kinds. On the new engine a reaction forwards a whole page of events into its inbox in one
  transaction, the page's, which also moves the subscription's progress, so every event is forwarded exactly once and
  the forward costs one commit per page instead of one per event; the page size is `essentials.event-log.page-size`,
  and for the order flow's two reactions `trading-demo.orders.forwarding.batch-size`, as on the classic engine.
  `trading-demo.views.partitioned-forward-payloads` stays and applies to the classic engine only; on the new engine the
  partitioned views always queue the event payload. Those views, `AccountOverview` and `AccountActivity`, extend the
  framework's `EventLogPartitionedViewProcessor` (§2.5): each page of events is queued in the page's own transaction, so
  every event is queued exactly once, and a rebuild truncates their tables and purges their queues, as the classic
  twins' reset does.

---

## 6. Documentation

- A new LLM reference, `LLM/LLM-postgresql-event-log.md`, covers the new event store engine (preview, §2.7): when to
  choose it over the classic event store, the starter, the processor base classes, consistency and page transactions,
  porting from the `EventProcessor` family, the settings (including `hole-probe-wait-max`), admin, migration and the
  known limits. The traps index gains `ESS-145`: a classic processor failing start-up once the new engine is on. The
  porting section states plainly what each port keeps: `EventLogInboxProcessor` forwards exactly once, `onRebuild` runs
  once per rebuild with no aggregate type, and `EventLogReactor` is at least once like the classic `EventProcessor`. A contributor reference, `docs/design/event-log.md`, describes the engine as built; its plan documents
  moved to `docs/archive/`. The plugin ships the new reference (§4).
- The admin API's LLM docs gave the contract as 40 operations; it has 67 (the shard-owned queue and event-log
  subscription operations were not counted).

---

## 7. Build and tooling

- The repository's PostgreSQL test image (`docker/postgresql`, now `essentials-postgres:17.5-2`) runs the server its tag
  names: it shipped the builder's whole PostgreSQL tree, so the 17.5 image ran 17.12, and all CDC integration tests failed
  because 17.12 refuses `wal2json` as an output plugin. Its extensions are now compiled against exactly the base image's
  server version, pg_cron is 1.6.8 (1.6.4 does not compile on 18), its initdb script creates pg_cron only when the server
  preloads it, and the CDC tests use this image instead of a second one of their own; their setup adds `wal2json` to
  `output_plugin_libraries` where the server has that setting. Any base works:
  `-Dessentials.postgres.base-image=postgres:18-bookworm`.
