# Event store redesign, phase 8: engine design

**Status:** decided 2026-11-17 (§15); split into 8a and 8b. 8a built 2026-11-17, gate passed (§16); 8b being built on `feature/event-store-engine-phase8` (from
phase 7, `9d147a6bb`). When phase 8 is done, this file moves to `docs/archive/` or is folded into the redesign's record.
**Scope:** phase 8 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): the new subscription
API, the view-projection and event-processor processors built on it, parked events and the admin API (§12.3), and the
`EventStore` adapter for appends and stream loads (§19 item 19). Also the items earlier phases handed to phase 8: the
temporary public surface (`subscribe` and `subscribeExclusive` overloads, `afterCommit`), the page transaction taken
from the application's transaction management instead of the engine's `DataSource` (phase-4 decision 1), the filter
digest and the tenant filter (phase-5 §1.2), the Spring commit path (phase-6 §2.2), and the notifier/listener and
lease-keeper scope revisits (phase-6 decision 1, phase-7 decision 1).
**Gate:** the new ITs for the API and the processors pass, I14 among them (§16), each invariant arm with a must-fail
mutant; the existing aggregate-repository ITs pass against the adapter (§9.5); the §10 measurements meet the pass lines
decided there. Every phase-2 to phase-7 IT keeps passing, with every `must_fail_*` test, after being moved off the
removed temporary surface.

**Proposed split (§12):** phase 8 is two phases' worth of work with little coupling between its halves. **8a** is the
subscription API, the processors, parked events and the admin SPI; **8b** is the `EventStore` adapter and the admin
API's three-place mapping. The gate is divided in §12.

Section references (§n) are to the plan; "phase-4 §n" etc. are `docs/plans/event-store-engine-phase4.md` and its
siblings. Where this draft proposes something the plan does not say, it is marked **Proposed**. The existing
`PostgresqlEventStore`, `EventStoreSubscriptionManager`, its builders, `EventProcessor`, `ViewEventProcessor`,
`InTransactionEventProcessor`, `durable_subscriptions`, `SubscriptionErrorPolicy`, `FailFast` redelivery and
`FencedLock` are not changed, and nothing below copies their shape (decided 2026-11-15, §12.3). §11.2 lists the few old
*contracts* (types and semantics, not designs) this draft proposes to keep.

---

## 1. What exists today, and what changes

### 1.1 Already in the engine module

| What | Where | State |
|---|---|---|
| Temporary non-exclusive `subscribe` overloads (periodic `Handler`, in-transaction `PageHandler`, each with and without `EventTypeFilter`) | `EventLog.java:237-273` | Four public overloads, each marked "**Temporary**: the phase-8 subscription API replaces it" |
| Temporary `subscribeExclusive` overloads | `EventLog.java:285-316` | Four public overloads (phase-7 decision 9) |
| Package-private `subscribe(SubscriptionSpec)` | `EventLog.java:333-357` | Where every overload ends; builds the `Lease`, the `Subscription`, registers it |
| Reset | `EventLog.reset(...)`, `EventLog.java:325` (package-private) → `ProgressStore.reset` (epoch + 1, delete parked rows, one transaction; `EventLogStorage.PROGRESS_RESET`, `:221`) | "Package-private until phase 8's reset API" |
| `afterCommit(Collection<String>)`, `afterCommit(List<AppendRequest>)` | `EventLog.java:608`, `:621` | Public, temporary (phase-6 decision 3) |
| Commit callback | `EventLogCommitCallback`, `EventLogCommitCallback.java:31` | Package-private `UnitOfWorkLifecycleCallback` (foundation); JDBI unit of work only; "the Spring path is phase 8's" (phase-6 §2.2) |
| Append, stream load, find by id | `EventLog.append`, `:424`, `:438` (caller's `Connection`, never commits); `loadStream`, `:642`, `:664`; `findEvent`, `:673` | Public. **Missing for the adapter:** load by `global_order` range, highest/lowest `global_order`, several events by id, events by causation id (`EventLogStorage` has no such statements) |
| Consistency mode | `Subscription.ConsistencyMode`, `Subscription.java:77` | Public enum `FRESH`/`CONTIGUOUS` |
| Progress model | `Subscription.ProgressModel`, `:86` | Package-private enum `PERIODIC`/`IN_HANDLER_TRANSACTION`; stored on `el_progress` and checked at start (§19 item 17) |
| Handlers | `Subscription.Handler` (`:96`, per event, periodic), `PageHandler` (`:105`, page + engine-owned `GuardedConnection`, "**Temporary**: phase 8 replaces it with the application's own transaction management over the same `DataSource`"), `EventHandler` (`:131`, `PageHandler.perEvent`) | Public |
| `SubscriptionSpec` | `Subscription.java:294` | Package-private record: key, `initialResumeFrom`, mode, model, handler, page handler, seams, filter, exclusive |
| Parked events | `ParkedEvents` (`ParkedEvents.java:34` `Row`, `:54` `load`, `:100-210` inserts, lock, delete, failed-again); statements `EventLogStorage.java:182-219` | Package-private. `Subscription.retryParked(head)` / `discardParked(head)` (`Subscription.java:2015`, `:2023`) are package-private futures served on the delivery thread between pages. No listing, and nothing an operator on another node can reach |
| Resume point, lifecycle phase, lease | `Subscription.resumePoint()` (`:2467`), `phase()` (`:419`), `lease()` (`:750`) | Package-private, for tests |
| Error settings | `EventLogSettings.retryAttempts`, backoff, `pageSize` | Per `EventLog`, not per subscription (§8.3 says "per subscription") |

### 1.2 Existing modules the gate exercises

| What | Where |
|---|---|
| `EventStore` | `components/postgresql-event-store/.../postgresql/EventStore.java`. Abstract: `getUnitOfWorkFactory` `:84`, `localEventBus` `:91`, `getEventStoreSubscriptionObserver` `:98`, `getEventStoreInterceptors` `:107`, `appendToStream(AppendToStream)` `:475`, `loadLastPersistedEventRelatedTo` `:500`, `loadEvent` `:520`, `loadEvents` `:593`, `fetchStream` `:769`, `inMemoryProjection` ×2 `:787`/`:806`, `loadEventsByGlobalOrder` `:887`, `pollEvents` `:956`, `unboundedPollForEvents` `:1085`, `findHighestGlobalEventOrderPersisted` `:1154`, `findLowestGlobalEventOrderPersisted` `:1162`. Default methods that throw unless overridden: `findEvent(EventId)` `:533`, `loadEventsCausedBy` `:548-559` |
| `ConfigurableEventStore<CONFIG extends AggregateEventStreamConfiguration>` | `ConfigurableEventStore.java:40`: stream configurations, in-memory projectors, interceptors. The repositories take `ConfigurableEventStore<?>` (e.g. `EventStreamDeciderAndAggregateTypeConfigurator.java:470`, `PostgresqlAggregateSnapshotRepository.java:85`) |
| `OptimisticAppendToStreamException` | `.../postgresql/persistence/OptimisticAppendToStreamException.java:19`, public constructors `:20`, `:24`: the adapter can throw it without a change |
| Repository ITs without the old subscription manager | `eventsourced-aggregates`: `classic/OrderAggregateRootRepositoryIT`, `classic/objenesis/OrderAggregateRootRepositoryIT`, `classic/state/OrderWithStateAggregateRootRepositoryIT`, `classic/objenesis/state/OrderWithStateAggregateRootRepositoryIT`, `stateful/StatefulAggregateRepositoryIT`, `eventstream/EventStreamDeciderIT`; `spring-postgresql-event-store`: `SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT`, `SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT`; the Kotlin decider ITs of `kotlin-eventsourcing` (unit 8b-B lists them). Each builds `new PostgresqlEventStore<>(…, new SeparateTablePerAggregateTypePersistenceStrategy(…))` inline (e.g. `classic/OrderAggregateRootRepositoryIT.java:82`) |
| Repository ITs that use the old subscription manager | `stateful/InTransactionHandlerLazyAppendIT`, `StatefulAggregateRepositoryCombinedWithInTransactionEventProcessorIT`, `TransactionalBehaviorIT`, `TransactionalBehaviorOnAggregateWithStateIT` (`EventStoreSubscriptionManager` / `InTransactionEventProcessor`). Out of the gate (§9.5, open question 9) |
| Admin contract precedent | `ShardOwnedQueuesApi` lives in its engine module (`components/postgresql-queue-shard-owned/.../shardowned/api/ShardOwnedQueuesApi.java`); `EssentialsAdminApiSpec` lists it (`admin-api-spec/.../EssentialsAdminApiSpec.java:60-67`) and maps its operations (`:391-398`); `admin-api-spec/pom.xml:80` depends on the engine module; `spring-boot-starter-admin-api` has its controller |

---

## 2. The subscription API (engine module)

### 2.1 What the engine exposes, and what the integration module adds

The API has two layers, so that the engine stays free of Jackson, JDBI and the old types (`DependencyRulesTest`):

| Layer | Module | Speaks | For |
|---|---|---|---|
| **Engine API** | `postgresql-event-log` | `StoredEvent` (opaque JSON text), `java.sql.Connection` | Frameworks, the integration module, tests, users who want raw rows |
| **Typed API and processors** | new integration module (§3) | deserialized payloads, `EventMetaData`, value types, foundation's `UnitOfWork` | Applications |

Both layers are **new contracts**; the engine layer replaces the temporary surface of §1.1.

### 2.2 A subscription definition

```java
// engine, public
public record SubscriptionDefinition(
        String subscriber,                 // validated like a table name: shown in admin, logged, keyed
        String aggregateType,
        Ownership ownership,               // Exclusive.INSTANCE | new PerInstance(instanceName)
        StartFrom startFrom,               // beginning() | head() | globalOrder(n): only for a row that does not exist yet
        ConsistencyMode consistency,       // FRESH | CONTIGUOUS
        EventTypeFilter eventTypes,        // all() by default (phase 5)
        Optional<String> tenant,           // in-memory filter, §2.6
        ErrorSettings errors) { … }        // retryAttempts, backoff start/cap, pageSize; defaults from EventLogSettings
```

- **The progress model is not a field.** It follows from the handler kind (§2.3): a `PeriodicHandler` is
  `PERIODIC`, a `PageHandler` is `IN_HANDLER_TRANSACTION`. That removes the invalid combinations `SubscriptionSpec`
  validates today (`Subscription.java:303-307`). `ProgressModel` stays package-private; the status (§2.5) reports it
  as a string.
- **`Ownership`** replaces the `subscribe`/`subscribeExclusive` split and the empty-instance-name convention: exclusive
  is the row `(subscriber, type, '')` (phase 7), per-instance the row with a non-blank name. `PerInstance` with a blank
  name is refused at construction, as now.
- **`StartFrom.head()`** (**Proposed**) resolves to `max(global_order) + 1` when the row is first created, in the same
  statement as `PROGRESS_START` (a sub-select), so a new processor that should see only new events needs no lookup by
  the caller. `beginning()` is `1`.
- **`ErrorSettings`** per subscription (§8.3, §19 item 21): `retryAttempts` (3), `backoffStart` (100 ms), `backoffMax`,
  `pageSize` (100). `EventLogSettings` keeps the engine-wide defaults; `StepDown` and the retry loop read the
  subscription's values.

### 2.3 Handlers

```java
// engine, public: sealed, two kinds
public sealed interface DeliveryHandler permits PeriodicHandler, PageHandler { }

@FunctionalInterface
public non-sealed interface PeriodicHandler extends DeliveryHandler {   // today's Subscription.Handler
    void handle(StoredEvent event) throws Exception;
}

@FunctionalInterface
public non-sealed interface PageHandler extends DeliveryHandler {
    /** Apply the page inside the page transaction; the engine moves the resume point on the same connection, last */
    void handle(List<StoredEvent> page, PageTransaction transaction) throws Exception;
}

public interface PageTransaction {
    Connection connection();            // guarded: no commit/rollback/close/autocommit, savepoints allowed (as GuardedConnection)
    <T> T resource(Class<T> type);      // what the transaction source bound, e.g. a JDBI Handle or the UnitOfWork; else IllegalArgumentException
}
```

- `PageHandler.perEvent(...)` stays, as the row-by-row convenience on top (P0.13: the 10x needs a page-aware
  handler; the per-event shape is the 2x one).
- A handler parks an event at once only by throwing `ParkEventException` (§19 item 21), unchanged.
- No annotation dispatch in the engine. The integration module adds typed dispatch (§3.3).

### 2.4 Where a page's transaction comes from

Phase 4 borrowed the page connection from the engine's `DataSource` and owned the transaction (phase-4 decision 1).
Phase 8 makes the source pluggable, with the engine still issuing the fenced, epoch-guarded progress update as the
**last statement on the page's connection before commit**. That is the only thing I12 and I5 need from it.

```java
// engine, public SPI
public interface PageTransactionSource {
    /** Begin a transaction on a connection to the event log's database; the engine calls exactly one of commit/rollback */
    OpenPage begin() throws SQLException;

    interface OpenPage extends PageTransaction {
        void commit() throws SQLException;
        void rollback();
    }
}
```

| Source | Module | How |
|---|---|---|
| `PageTransactionSource.engine()` (default) | engine | Today's behaviour: the engine's `DataSource`, `setAutoCommit(false)`, `GuardedConnection` |
| `UnitOfWorkPageTransactions(HandleAwareUnitOfWorkFactory)` | integration | A unit of work from the application's factory, so `resource(Handle.class)` and `resource(UnitOfWork.class)` work and the handler's code sees the unit of work it would see anywhere else; the engine still runs the progress update and `commit()` itself (rule below), never as a `BeforeCommit` callback |
| `SpringPageTransactions(PlatformTransactionManager, DataSource)` | integration (optional Spring dependency, `provided`) | A `TransactionTemplate`-style programmatic transaction, connection via `DataSourceUtils.getConnection` |

The rule that makes any source safe: the engine never relies on a callback inside someone else's commit. It runs
handler → progress update → `commit()` itself, through `OpenPage`. A source only says how to obtain the connection and
how to end the transaction. A handler that commits on its own (through the resource) defeats I12; the guarded
connection refuses it for the connection, and the typed resources are wrapped so that `commit()` on them throws
(**Proposed**; tested by mutant `HANDLER_COMMITS_EARLY`, §9.2).

At start the engine checks the source's database identity against its own (`EventLogStorage.DATABASE_IDENTITY`, used
by `CatchUpPermits`) and refuses a source on another database: in-transaction progress on another database is not
atomic with the output, which is the whole point.

### 2.5 Subscribing, and the handle

```java
// EventLog, public: replaces the eight temporary overloads
public SubscriptionHandle subscribe(SubscriptionDefinition definition, DeliveryHandler handler) throws SQLException;
public SubscriptionHandle subscribe(SubscriptionDefinition definition, PageHandler handler, PageTransactionSource source) throws SQLException;

public interface SubscriptionHandle {
    SubscriptionDefinition definition();
    void stop();                                  // save (periodic) and release (exclusive), as today
    boolean isRunning();
    SubscriptionStatus status();                  // local snapshot, §2.7
    CompletableFuture<Boolean> retryParked(long headGlobalOrder);
    CompletableFuture<Boolean> discardParked(long headGlobalOrder);
}
```

`Subscription` keeps its internals and becomes package-private behind `SubscriptionHandle` (**Proposed**); its public
`ConsistencyMode`, `Handler`, `PageHandler`, `EventHandler` move to top-level types (`ConsistencyMode`,
`PeriodicHandler`, `PageHandler`, `PageHandler.EventHandler`). The test seams stay on the package-private
`subscribe(SubscriptionSpec)`, which the definition maps onto.

### 2.6 Filters, tenant and the stored definition

- **Event-type filter:** unchanged from phase 5 (SQL on catch-up and bridge, in memory on attached batches).
- **Tenant** (**Proposed**): in memory only, everywhere. An event of another tenant counts as handled and does not
  reach the handler, exactly as a non-matching event type does when attached. Phase 5 left a SQL tenant predicate
  open because it widens the index question (phase-5 §1.2); no gate shape needs it. A SQL predicate on catch-up is a
  later, measured item.
- **Stored definition** (phase-5 §1.2 handed this to phase 8): `el_progress` gains `filter_digest text` (a SHA-256 of
  the sorted event types and the tenant). At start, a digest that differs from the stored one is **logged at WARN and
  stored**, not refused, as §19 item 17 does for a changed mode or model: a filter change is a deployment decision, and
  the operator gets a reset (§4.2) to replay. Open question 5.

### 2.7 Status

`SubscriptionStatus` is a local snapshot: definition, progress model, lifecycle phase, resume point, received
watermark, `lag = max(global_order) − resume point` (from the reader's cursor, no statement), lease state and fence,
held aggregates and parked head count, last error. The admin listing (§4.1) is the cluster view, read from the database.

### 2.8 The temporary surface: what goes and what stays

| Temporary item | Phase 8 |
|---|---|
| Eight `subscribe`/`subscribeExclusive` overloads | **Removed**; phases 2-7 ITs move to `subscribe(definition, handler)` (mechanical; their package-private `SubscriptionSpec` path is unchanged) |
| `afterCommit(Collection<String>)` | **Kept, made permanent**: an application that appends on a connection it commits itself needs it (§11's "explicit after-commit call") |
| `afterCommit(List<AppendRequest>)` | **Removed**: one way to say the same thing |
| `EventLogCommitCallback` | Made **public** as `EventLog.commitCallback()` returning foundation's `UnitOfWorkLifecycleCallback`, so the integration module registers it on any unit of work; the Spring path registers it through a `TransactionSynchronization` (§3.4) |
| `Subscription.ConsistencyMode` etc. | Moved to top-level types (§2.5) |
| `reset(...)` (package-private) | Becomes the admin operation of §4.2 |

---

## 3. The integration module and the processors

### 3.1 The module

**Proposed:** `components/postgresql-event-log-integration`, package `dk.trustworks.essentials.components.eventlog.integration`.
Depends on `postgresql-event-log`, `foundation`, `postgresql-event-store` (for the adapter's interfaces, the JSON
serializer and the value types it re-uses, §11.2) and, `provided`, JDBI, Spring TX and Kotlin as the existing modules
do. No Reactor at runtime: the adapter's `Flux` methods throw (§5.5), so Reactor is `provided` only to satisfy the
interface's signatures. The starter stays phase 10 (§18).

### 3.2 Typed events

```java
public record DeliveredEvent(AggregateType aggregateType, Object aggregateId, EventOrder eventOrder, GlobalEventOrder globalOrder,
                             EventId eventId, Optional<EventId> causedBy, Optional<CorrelationId> correlationId,
                             Object payload, EventMetaData metaData, OffsetDateTime timestamp, Optional<Tenant> tenant,
                             StoredEvent raw) { … }
```

Deserialized with the application's `JSONEventSerializer` from `EssentialsObjectMappers` (root `CLAUDE.md`: never a
hand-built mapper), and the aggregate id with the type's `AggregateIdSerializer`. The value types are the existing
ones (§11.2), so a handler moved from the old engine changes its method shape, not its types. Deserialization happens
on the delivery thread, per event, lazily for filtered-out types (they never reach it).

### 3.3 The two processors

Both are thin: a builder that produces a `SubscriptionDefinition` and a `DeliveryHandler`, with dispatch by payload
type. They are not subclasses of a framework base class and have no lifecycle of their own beyond start/stop.

| | `ViewProjection` | `EventReactor` (**name open**, question 3) |
|---|---|---|
| Output | In the same database | Outside the database (commands, queues, HTTP) |
| Default mode | `CONTIGUOUS` (D1) | `FRESH` (D1) |
| Progress | In the page transaction (`PageHandler`) | Periodic (`PeriodicHandler`) |
| Default ownership | Exclusive | Exclusive |
| Handler shapes | `onPage((List<DeliveredEvent>, ProjectionContext) -> …)` for set-based writes; `on(Type.class, (event, ctx) -> …)` per event | `on(Type.class, event -> …)` |
| `ProjectionContext` | the `PageTransaction` resources: `connection()`, `handle()` (JDBI) | — |
| Unknown payload type | counts as handled (logged once per type at DEBUG); `strict()` makes it a handler failure | same |
| Rebuild | `rebuild(Consumer<Connection> truncate)`: truncate and reset in one transaction (§10.3, §4.2) | `reset(StartFrom)` |

```java
var projection = ViewProjection.builder("order-summary", ORDERS)
        .onPage((events, ctx) -> upsertAll(ctx.handle(), events))
        .transactions(new UnitOfWorkPageTransactions(unitOfWorkFactory))
        .build(eventLog, serializer);
projection.start();
```

- Dispatch is a `Map<Class<?>, handler>` resolved on the payload's class and its supertypes (cached), no reflection on
  annotated methods. **Proposed:** an annotation-based variant (`@EventHandler` methods, through foundation's
  `PatternMatchingMethodInvoker`) is a convenience for ported handlers; open question 4.
- Failures, retries, step-down and parking are the engine's (§8.3); the processors add nothing: no error policy, no
  permanent-error list, no catching. A processor that caught a handler exception would mark the event handled and
  break I14; the must-fail mutant `PROCESSOR_SWALLOWS_FAILURE` (§9.2) proves the test sees it.

### 3.4 In-transaction delivery and the commit path

- **Delivery inside the appender's commit** (a "must keep", §1) stays where it is today: the adapter's local event bus
  publishes `PersistedEvents` at the unit of work's stages, as `PostgresqlEventStore` does (§5.4). The old
  `InTransactionEventProcessor` needs `EventStoreSubscriptionManager` and so does not run on the new engine; a new
  in-transaction processor is not part of phase 8 (open question 9).
- **Tier-2 wake-up** on commit: the adapter registers `EventLog.commitCallback()` on the JDBI unit of work, and through
  a Spring `TransactionSynchronization.afterCommit` when the unit-of-work factory is Spring-managed (the phase-6 §2.2
  "Spring path").

---

## 4. Parked events and the admin operations

### 4.1 The engine's admin facade

```java
// engine, public
public interface EventLogAdmin {                       // EventLog.admin()
    List<SubscriptionRow> subscriptions(Optional<String> aggregateType);          // every el_progress row, every node
    Optional<SubscriptionRow> subscription(ProgressKey key);
    Page<ParkedRow> parked(ProgressKey key, long afterGlobalOrder, int limit);
    boolean reset(ProgressKey key, long resumeFrom);
    boolean reset(ProgressKey key, long resumeFrom, Connection connection);       // on the caller's transaction (rebuilds)
    ParkedRequestResult retryParked(ProgressKey key, long head);
    ParkedRequestResult discardParked(ProgressKey key, long head);
    boolean deleteRetiredInstance(ProgressKey key);                                // §19 item 17: rows of retired instances
}
```

`SubscriptionRow`: key, exclusive, mode, model, resume point, reset epoch, lease owner, `lease_until`, fence,
`updated_at`, filter digest, parked head count and parked total, `lag` (head minus resume point, one sub-select per
type). One statement for the list, joined with `el_parked_events` counts. `ParkedRow`: global order, aggregate id,
`blocked_by`, attempts, error type and message, parked at; the event is loaded by `global_order` (no copy, §19 item 18).

### 4.2 Reset

The existing `ProgressStore.reset` (epoch + 1, delete parked, one transaction). The `Connection` overload runs the same
two statements on the caller's transaction, so a `CONTIGUOUS` rebuild is "truncate, reset, commit" (§10.3) and at every
point the projection matches its resume point. A reset never touches the lease columns (phase 7 §3.1). Refused for a
row that does not exist (returns `false`), so a typo never creates a subscription.

### 4.3 Retry and discard from any node

Today a parked head is served only by the delivery thread that holds its aggregate (`Subscription.java:2015-2056`),
because its in-memory `held` set must change with the rows. An operator's request arrives at whichever node serves the
admin API, which may not run the subscription at all.

**Proposed: requests on the parked row.** `el_parked_events` gains `requested text` (`'RETRY'` or `'DISCARD'`, null by
default) and `requested_at timestamptz`.

1. The admin call sets `requested` on the head row (`UPDATE … WHERE key AND global_order = :head AND blocked_by IS
   NULL`); zero rows means not a head, or already gone.
2. If this node runs the subscription and owns it, it also calls the local future at once (no wait).
3. Otherwise the subscription that runs it sees the request at its next **parked check**: a delivery thread with at
   least one held aggregate reads `SELECT global_order, requested FROM el_parked_events WHERE key AND requested IS NOT
   NULL` once per backstop interval, between pages. With no held aggregate it reads nothing (cost 0).
4. Serving it is today's `releaseParked` (`Subscription.java:2056`): one fenced, epoch-guarded transaction that deletes
   the head (and, for retry, hands it to the handler first) and releases the rows held behind it.

The admin call returns `ACCEPTED` (request stored), `DONE` (served locally), `NOT_A_HEAD` or `NO_SUCH_SUBSCRIPTION`.
A subscription that is not running anywhere keeps the request until it runs. A reset deletes parked rows, requests
included. Open question 6.

### 4.4 The admin SPI (integration module) and its three places

`EventLogSubscriptionsApi` (integration module, as `ShardOwnedQueuesApi` lives beside its engine): the operations of
§4.1 with the existing admin value conventions (`ApiSubscription`-style records of the new SPI's own, not the old
engine's `ApiSubscription`), each guarded by `EssentialsSecurityProvider` roles as the existing APIs are (read: the
existing subscription reader role; reset, retry, discard, delete: the existing writer role). Its mapping in
`EssentialsAdminApiSpec` and its controller in `spring-boot-starter-admin-api` are **existing-module changes** (§11).
The admin UI page is phase 10.

---

## 5. The `EventStore` adapter (integration module)

### 5.1 Shape

`EventLogEventStore implements ConfigurableEventStore<EventLogAggregateEventStreamConfiguration>`, built with an
`EventLog`, an `EventStoreUnitOfWorkFactory` (the **existing** JDBI or Spring factories, unchanged), a
`JSONEventSerializer` and the per-type `AggregateIdSerializer`/`TenantSerializer`. `EventLogAggregateEventStreamConfiguration`
extends the existing `AggregateEventStreamConfiguration` with the engine's table names; `addAggregateEventStreamConfiguration`
calls `EventLog.registerAggregateType`.

### 5.2 Methods

| `EventStore` method | Adapter |
|---|---|
| `appendToStream(AppendToStream)` | Interceptor chain (existing `EventStoreInterceptorChain`), serialise each event (existing serializer; payload and metadata as JSON text), `EventLog.append(unitOfWork.handle().getConnection(), …)`; `StreamAppendConflictException` → `OptimisticAppendToStreamException` with **today's message text** (phase-2 §5.1); register `PersistedEvents` on the unit of work for the local bus stages |
| `fetchStream` | `loadStream(connection, type, id, from, to)` on the unit of work's connection; tenant filter as today |
| `loadLastPersistedEventRelatedTo` | **New engine read**: one row, `ORDER BY event_order DESC LIMIT 1` on `(aggregate_id, event_order)` |
| `loadEvent`, `loadEvents` | `findEvent`; **new** `findEvents(ids)` (`= ANY(:ids)`, the plain event-id index of §19 item 23) |
| `findEvent(EventId)` | Throws as the default does (needs the aggregate type; old engine behaviour kept) |
| `loadEventsByGlobalOrder` | **New engine read**: `(from, to]` range, optional event-type and tenant filter, streamed with `setFetchSize` on autocommit-off, returned as a `Stream` closed by the caller. Reads committed rows only; it is *not* a subscription and makes no hole guarantee, as today |
| `findHighest/LowestGlobalEventOrderPersisted` | **New engine reads**: `max`/`min(global_order)` (index-only) |
| `loadEventsCausedBy` | **New engine read** with an index on `caused_by_event_id` (open question 8) |
| `inMemoryProjection` | Existing projectors over `fetchStream`, as `PostgresqlEventStore` |
| `localEventBus` | An `EventStoreEventBus` (existing) driven by the unit of work's stages |
| `getUnitOfWorkFactory` | The one passed in |
| `getEventStoreSubscriptionObserver` | The no-op observer |
| `pollEvents`, `unboundedPollForEvents` (all overloads) | **Throw** `UnsupportedOperationException("… use EventLog subscriptions (SubscriptionDefinition) …")` |

### 5.3 What is stored

- `event_type` text = the old engine's persisted event-type value (`EventJSON`'s type-or-name string), so phase 9's
  copy maps types 1:1 and the existing serializer reads it back.
- Payload and metadata bytes = what `EssentialsObjectMappers` writes; the engine stores them as `json` unchanged. The
  frozen wire format (root `CLAUDE.md`) is guarded by an IT comparing adapter-written bytes with `PostgresqlEventStore`'s
  for the same events (§9.4).
- Tenant: the `TenantSerializer`'s string in the engine's `tenant` column.

### 5.4 Unit-of-work stages

The adapter reproduces the stages the repositories and the local bus rely on: `Flush`, `BeforeCommit`, `AfterCommit`,
`AfterRollback`, published as `PersistedEvents`, and it calls `EventLog.afterCommit(types)` after commit through the
commit callback (tier 2, tier 1). Nothing is published for a rolled-back append.

### 5.5 Not in the adapter

Polling, subscriptions, `SubscriberAcknowledgement`, `forgetGapMiddlesAwaitedInMemory`, the `EventStreamGapHandler`,
`EventStorePollingOptimizer`, CDC: no counterpart (§12.3).

---

## 6. Settings

| Setting | Where | Default | Rule | Why |
|---|---|---|---|---|
| `ErrorSettings.retryAttempts` | per subscription | 3 | ≥ 0 | §19 item 21 |
| `ErrorSettings.backoffStart` / `backoffMax` | per subscription | 100 ms / 10 s | start ≤ max | §19 item 21, "capped" |
| `ErrorSettings.pageSize` | per subscription | 100 | 1-10 000 | P0.13 |
| `parkedCheckInterval` | `EventLogSettings` | = `backstopInterval` | > 0 | §4.3; only while a subscription holds an aggregate |
| `adminListLimit` | `EventLogSettings` | 1 000 | > 0 | bounds one admin listing |
| `PageTransactionSource` | per subscription | `engine()` | same database (checked) | §2.4 |

`EventLogSettings.retryAttempts` and the backoff fields stay as the defaults `ErrorSettings.defaults(settings)` reads.
No lease or wake setting changes.

---

## 7. Failure cases

| Case | What happens | Bound |
|---|---|---|
| Handler throws in a page | Engine's step-down, retry, park (phase 4); the processor adds nothing | I14 |
| Handler commits through a typed resource | Wrapped resource throws on `commit`; page fails as a handler failure | §2.4 |
| Page source on another database | Refused at `subscribe` | — |
| Application transaction manager fails `commit()` with unknown outcome | As phase 7's unknown page commit: the next page reads the progress row first; the resume point says whether it committed | I12 |
| Payload cannot be deserialized (class gone, bad JSON) | A handler failure: retried, then parked with the error; the aggregate is held | I14; operator discards or deploys a fix and retries |
| Unknown payload type, non-strict | Counted as handled, logged once | — |
| Admin request on a node not running the subscription | Stored on the parked row; served by the owner within one parked-check interval; kept until a node runs it | §4.3 |
| Admin request racing a reset | The reset deletes the parked rows, requests included; the serve transaction is epoch-guarded and updates nothing | I6 |
| Admin request racing a takeover | The serve transaction is fenced; the new owner reads the request at its own check | I5 |
| Filter or tenant changed between deploys | WARN, digest stored, delivery continues under the new filter | §2.6 |
| Adapter append conflict | `OptimisticAppendToStreamException`, today's message; no `global_order` consumed | I10 |
| Adapter's `pollEvents` called | `UnsupportedOperationException` naming the replacement | — |
| Adapter's `loadEventsByGlobalOrder` racing writers | Returns committed rows only; may skip a late commit, as today | Documented: not a subscription |

---

## 8. Resource cost

| | Threads | Held connections | Transient | Statements |
|---|---|---|---|---|
| Engine API (§2) | 0 new | 0 | page connection from the chosen source instead of the engine pool | 0 new per page |
| Processors (§3) | 0: they run on the subscription's delivery thread | 0 | 0 | 0; deserialisation CPU per delivered event |
| Parked check (§4.3) | 0 | 0 | 1 per check | 1 per `parkedCheckInterval` **per subscription with a held aggregate**, else 0 |
| Admin calls | caller's | 0 | 1 per call | 1-3 per call |
| Adapter append | 0 | 0 | the unit of work's | today's engine append (one statement per shape batch); interceptors and serialisation in the JVM |
| Adapter reads | 0 | 0 | the unit of work's or 1 borrowed | 1 per call; `loadEventsByGlobalOrder` streams in fetch-size pages |

No new held connection, no new thread. The phase-6 notifier/listener and the phase-7 keeper stay **one per `EventLog`**
(open question 10): the integration module wires one `EventLog` per database, so the case those revisits waited for
(several `EventLog`s on one database in one JVM) does not arise from phase 8's API.

---

## 9. Test plan

Every new rule has an IT seen to fail against a mutant (§16; history §9.13). New mutants go into `WatermarkMutant` (or
an integration-module twin, `IntegrationMutant`, settable only through package-private seams there). Engine ITs extend
`AbstractEventLogIT`; integration ITs reuse `EssentialsTestContainers.postgres()`.

### 9.1 Engine API

`EventLogSubscriptionApiIT`: every definition field round-trips to `el_progress` (mode, model, digest, exclusive);
`StartFrom.head()` on a busy table starts after the head at creation and ignores it for an existing row; invalid
combinations refused; a changed digest warns and continues; `PageTransactionSource` on another database refused;
`afterCommit(Collection)` still wakes (tier 2). Every phase-2 to phase-7 IT, moved to `subscribe(definition, …)`,
passes with its `must_fail_*` tests unchanged.

### 9.2 Gate row I14 through the processors and the application's transaction

`ViewProjectionIT`, `EventReactorIT`, each with the JDBI unit-of-work source and the Spring source:

| Arm | Shape | Asserts | Must-fail variants |
|---|---|---|---|
| (a) Once | `onPage` throws on one event once in a 100-event page | The page shrinks, the event succeeds alone, the view equals the reference fold; every event applied once (a per-event write counter table) | `PROCESSOR_SWALLOWS_FAILURE` (the processor catches and continues: the event is skipped and the fold differs); `NO_SHRINK` (existing step-down mutant, if present; else new) |
| (b) Always | Same, always throws | Parked with the error and `attempts = retryAttempts + 1`; later events of its aggregate parked behind it; other aggregates applied; fold equals reference minus the parked aggregate's tail | `PROCESSOR_SWALLOWS_FAILURE` |
| (c) Deserialization failure | One event's payload class unknown | Parked with the deserialization error, as (b) | `DESERIALIZE_OUTSIDE_HANDLER_RETRY` (deserialization failure classed as I/O: retried forever, nothing parked) |
| (d) Handler commits | The handler calls `commit` on its JDBI handle | Page fails as a handler failure; no output lands without its progress | `HANDLER_COMMITS_EARLY` (resource not wrapped: output lands, progress not; crash after shows output above the resume point, I12) |
| (e) Crash at every step of an application-sourced page | `TestSeams.Crash` before handler, after handler, after progress, after commit | Fold equals reference (I12) | `PROGRESS_OUTSIDE_PAGE_TRANSACTION` (progress written on an engine connection after the application's commit) |
| (f) `EventReactor` periodic | Handler throws always for one event | Parked; other aggregates continue; at-least-once for the rest | `PROCESSOR_SWALLOWS_FAILURE` |

### 9.3 Parked events and admin

`EventLogAdminIT`, two `EventLog`s on their own `DataSource`s ("another node"):

| Arm | Asserts | Must-fail variants |
|---|---|---|
| Listing | Every row of both nodes with mode, model, position, lag, lease owner, parked counts; one statement | — |
| Retry from the other node | Request stored, served by the owner within one parked-check interval; head handled, held events released in order | `PARKED_CHECK_SKIPPED` (the owner never reads requests: the head stays parked) |
| Discard from the other node | Head deleted unhandled, held events delivered in order | same |
| Request racing a reset | No parked row survives; nothing served under the old epoch | `SERVE_WITHOUT_EPOCH` |
| Request racing a takeover | The old owner serves nothing; the new owner serves it | `SERVE_WITHOUT_FENCE` |
| Rebuild | Truncate + reset in one transaction, then catch up: projection equals fold at every observed point | `RESET_OUTSIDE_CALLER_TRANSACTION` (reset on its own transaction: a crash between leaves an empty view at the old resume point) |
| No held aggregate costs nothing | `pg_stat_statements`: zero parked-check statements over 10 backstop intervals | `PARKED_CHECK_ALWAYS` |

`EventLogSubscriptionsApiIT` (integration): the SPI's operations and role checks. In 8b, the controller IT in
`spring-boot-starter-admin-api` follows the existing shard-owned controller ITs.

### 9.4 The adapter

`EventLogEventStoreIT`:

| Arm | Asserts | Must-fail variants |
|---|---|---|
| Conflict | Two writers, stale expected order: `OptimisticAppendToStreamException`, message equal to `PostgresqlEventStore`'s for the same input, no `global_order` consumed (I10 through the adapter) | `CONFLICT_UNMAPPED` (the engine exception escapes) |
| Wire format | The same events appended through `PostgresqlEventStore` and the adapter: payload and metadata bytes equal | `HAND_BUILT_MAPPER` (a plain `JsonMapper`: value types differ) |
| Unit-of-work stages | `PersistedEvents` at `Flush`/`BeforeCommit`/`AfterCommit`; none after rollback; tier-2 wake after commit only | `PUBLISH_BEFORE_COMMIT` |
| Interceptors | Order and modification as with `PostgresqlEventStore` (same interceptor test fixture) | — |
| Reads | `loadEventsByGlobalOrder` with holes, filters, tenant; highest/lowest; `loadEvents(ids)`; caused-by | — |
| Subscription methods | Every `pollEvents`/`unboundedPollForEvents` overload throws | — |

### 9.5 Existing repository ITs against the adapter

The ITs of §1.2's first repository row, run against `EventLogEventStore` on the engine's tables. **Proposed:** copies in
the integration module's test tree (`…/integration/repository/`), each differing from its original only in the
store construction, with a unit test (`RepositoryItCopiesTest`) that diffs each copy against its original minus that
block and fails on any other drift. No existing module is touched (open question 2). The Spring ones run with both
Spring unit-of-work factories.

---

## 10. Gate measurement

`spike/EventLogGateSpikeIT` (engine) and a new `EventLogIntegrationGateSpikeIT` (integration), suite `p8-gate`. Same
harness as phase 7 §10: `pg_stat_statements` by role, fresh container and database per arm, two runs of every arm; a
result inside the noise is no change (§17). Pass lines are decided here, before the run.

### 10.1 Processor overhead

Steady 100 ev/s, 10 subscriptions (5 `ViewProjection`, 5 `EventReactor`) against the same 10 on the raw engine API
(phase 7 §10.3's shape); burst 100 000 events into one `ViewProjection` with a set-based `onPage` against a raw
`PageHandler` writing the same rows. **Pass:** steady commit-to-handler p50 and p99 within the noise of the raw arm, or
above it by at most 0.2 ms (deserialization); statements per delivered event equal; burst events/s ≥ 0.9× the raw arm.
**Recorded:** CPU per delivered event (client), allocation rate.

### 10.2 Application-sourced pages

Burst shape with the JDBI unit-of-work source and the Spring source against `engine()`. **Pass:** events/s ≥ 0.9× and
statements per page equal (begin, handler, progress, commit). **Recorded:** borrow time.

### 10.3 Adapter appends and loads

P0.4's append shapes (1, 10, 100 events; 5 aggregates × 10) through `StatefulAggregateRepository` on the adapter against
the same on `PostgresqlEventStore` (both wired as the Spring starter wires the old one, `fsync=on`), and the contended
shape (8 writers, 10 aggregates). **Pass:** round trips per unit of work ≤ the old engine's in every shape and lower for
5 aggregates; append p50 ≤ the old engine's or within the noise; burned `global_order` values per 1 000 appends 0 for
committed-conflict losers (I10). `fetchStream` of a 1 000-event aggregate: p50 within the noise of the old engine or
better. **Recorded:** appends/s, WAL per event.

### 10.4 Admin and parked check

1 000 progress rows, 100 with parked heads. **Pass:** `subscriptions()` one statement, p99 < 50 ms; parked check 0
statements for subscriptions without held aggregates, ≤ 1 per interval with. **Recorded:** retry-from-another-node
latency p50/p100 (expected ≤ one backstop interval).

---

## 11. Against the invariants, and the old contracts kept

### 11.1 Invariants

- **I1-I3, I9, I11, I13, I15, I16.** Untouched: the API maps onto the existing `SubscriptionSpec`; the processors run
  on the delivery thread and add no state; the parked check reads no event table and takes no catch-up permit.
- **I5.** Every new write that changes subscription state (serving a parked request, the `Connection` reset excepted,
  which by §9 writes no fence) goes through the existing fenced statements; the progress update stays the last
  statement of every page whatever its source (§2.4). Tested in §9.3 (`SERVE_WITHOUT_FENCE`).
- **I6.** The caller-transaction reset is the same epoch bump; served requests are epoch-guarded (§9.3).
- **I10.** Through the adapter (§9.4).
- **I12.** With an application-sourced page (§9.2 (d), (e)).
- **I14.** Through both processors and with deserialization failures (§9.2).
- **I7.** `afterCommit(Collection)` and the commit callback keep tier 2; the adapter calls it after commit only (§9.4).
- **I4, I8.** Not affected (I8 is phase 9; the adapter's event-type string is chosen for it, §5.3).

### 11.2 Old contracts proposed to keep

Only types and semantics, never the subscription design:

| Kept | Why |
|---|---|
| The `EventStore`/`ConfigurableEventStore` interfaces, for appends and loads only (§19 item 19) | Repositories, deciders and snapshots run unchanged |
| `OptimisticAppendToStreamException` and its message | Must-keep (§1) |
| Value types: `AggregateType`, `EventId`, `EventOrder`, `GlobalEventOrder`, `Tenant`, `CorrelationId`, `EventMetaData` | Handlers ported from the old engine keep their types; no parallel set |
| `JSONEventSerializer` / `EssentialsObjectMappers` | Frozen wire format |
| `EventStoreUnitOfWorkFactory` implementations (JDBI, Spring) and `EventStoreEventBus` with its commit stages | In-transaction delivery is a must-keep and outside the subscription engine |
| `EssentialsSecurityProvider` roles for admin operations | One security model across the admin API |

Not kept: `EventStoreSubscriptionManager`, the builders, `EventProcessor`/`ViewEventProcessor`/`InTransactionEventProcessor`
as shapes, `@MessageHandler` as the dispatch contract, `SubscriptionErrorPolicy`, `FailFast` redelivery and its
permanent-error list, `RedeliveryPolicy`, `durable_subscriptions`, `SubscriberAcknowledgement`, `Flux`.

---

## 12. Build split

Phase 8 is too large for one gate: the API and processors (8a) and the adapter (8b) share only the integration
module's skeleton, and either can be gated without the other. **Proposed:** two phases.

| Phase | Gate |
|---|---|
| **8a** subscription API, processors, parked events, admin SPI | §9.1, §9.2 (I14, I12 with application pages), §9.3 pass with every must-fail test; phase 2-7 ITs pass on the new API; §10.1, §10.2, §10.4 |
| **8b** `EventStore` adapter, admin three-place mapping | §9.4, §9.5 (existing repository ITs against the adapter); §10.3; the admin controller IT |

| Unit | Phase | Content | Done when |
|---|---|---|---|
| **8a-A: engine API** | 8a | `SubscriptionDefinition`, `Ownership`, `StartFrom`, `ErrorSettings`, sealed handlers, `PageTransaction(Source)`, `SubscriptionHandle`, `SubscriptionStatus`; the temporary overloads removed and every engine IT moved; `afterCommit(Collection)` permanent; `commitCallback()` public; `filter_digest` column; per-subscription error settings in `StepDown` and the retry loop | `mvn verify -pl components/postgresql-event-log`, all ITs and `must_fail_*` pass |
| **8a-B: parked and admin (engine)** | 8a | `EventLogAdmin`, the request columns and parked check, the `Connection` reset, the listing statement; §9.3's mutants and `EventLogAdminIT` | §9.3 passes on PostgreSQL 16-18 |
| **8a-C: integration module and processors** | 8a | Module skeleton (pom, `DependencyRulesTest`-style rules), `DeliveredEvent`, dispatch, `ViewProjection`, `EventReactor`, JDBI and Spring page sources, `EventLogSubscriptionsApi` SPI; §9.2's ITs and mutants | §9.2 passes |
| **8a-D: gate** | 8a | §10.1, §10.2, §10.4 in `p8-gate`, results in a "Results" section here | each line met or the miss recorded with a decision |
| **8b-A: engine reads** | 8b | Last event of a stream, events by ids, by global-order range (streamed, filtered), highest/lowest, by causation (with its index decision); `EventLogGenericPlanIT` gains them | engine ITs pass |
| **8b-B: adapter** | 8b | `EventLogEventStore`, its configuration, unit-of-work stages, interceptor chain, exception mapping, commit callback for JDBI and Spring; `EventLogEventStoreIT`; the repository IT copies and `RepositoryItCopiesTest` | §9.4, §9.5 pass |
| **8b-C: admin mapping** | 8b | `EssentialsAdminApiSpec` rows, controller in `spring-boot-starter-admin-api`, their ITs (only after the maintainer confirms §13's E1/E2) | admin module tests and the OpenAPI contract check pass |
| **8b-D: gate** | 8b | §10.3 | line met or the miss recorded |

8a-A comes first; 8a-B and 8a-C overlap once 8a-A's types exist. 8b-A can start in parallel with 8a (it touches only
`EventLog`'s read side).

---

## 13. Existing modules touched

Each line is a change to a module outside `components/postgresql-event-log` and the new integration module, and each
needs the maintainer's confirmation (also open question 1).

| # | Module | Change | Kind | Phase | Avoidable? |
|---|---|---|---|---|---|
| E1 | `components/admin-api-spec` | `EssentialsAdminApiSpec`: add `EventLogSubscriptionsApi` to the API list and its operation rows and DTO key sets; `pom.xml`: dependency on the integration module (as `:80` does for the shard-owned queue). The published OpenAPI document grows (additive), so the contract diff check sees new operations | Main code, additive | 8b-C | Yes: defer to phase 10 with the starter and the UI |
| E2 | `components/spring-boot-starter-admin-api` | A controller for `EventLogSubscriptionsApi`, conditional on the bean; optional dependency on the integration module | Main code, additive | 8b-C | Same as E1 |
| E3 | Root `pom.xml` | `<module>components/postgresql-event-log-integration</module>` beside `:216`; a `dependencyManagement` entry if the BOM lists modules | Build, additive | 8a-C | No |
| E4 | `eventsourced-aggregates`, `spring-postgresql-event-store`, `kotlin-eventsourcing` (tests) | **None proposed** (§9.5 copies the ITs). The alternative is a test-only refactor: each IT's store construction moved to an overridable factory method, subclassed in the integration module through a test-jar | Test code | 8b-B | Yes, with copies |
| E5 | `postgresql-event-store`, `foundation` | **None expected.** The adapter uses public types only (`OptimisticAppendToStreamException` constructors are public, `OptimisticAppendToStreamException.java:20-24`). If 8b-B finds a needed type non-public (e.g. the interceptor chain's construction or `EventStoreEventBus`'s stage publishing), it stops and raises it rather than changing the module | — | 8b-B | — |
| E6 | `docs/releases/NEXT_VERSION_RELEASE.md`, `LLM/` | None in phase 8: the modules are unreleased and phase 10 owns docs and release notes (§18) | — | — | — |

---

## 14. Open questions

| # | Question | Recommendation | Alternatives |
|---|---|---|---|
| 1 | Confirm the existing-module touches E1-E3 (§13) | **E3 yes (needed); E1 and E2 in 8b-C**, since §18 puts the admin API in phase 8 and the shard-owned queue set the precedent | Defer E1/E2 to phase 10 with the starter and UI: phase 8 touches no existing main module, and the SPI is exercised by its own ITs only |
| 2 | How the existing repository ITs run against the adapter | **Copies in the integration module with a drift test** (§9.5): no existing module touched | (a) Test-only refactor of the originals to an overridable factory plus a test-jar (E4): no copies, but touches three modules. (b) Parameterise the originals over both stores: same touch, larger |
| 3 | Name of the side-effect processor | **`EventReactor`**: avoids a second `EventProcessor` class in the codebase while the old one exists | `EventProcessor` in the new package (familiar, but two same-named types in one application during porting); `Automation` (matches slice vocabulary, vaguer) |
| 4 | Annotation dispatch on processors | **Lambda registry only in phase 8**; annotated methods later if porting asks for it | Annotated methods through foundation's `PatternMatchingMethodInvoker` now: easier ports, more surface, reflection on the hot path |
| 5 | Changed filter or tenant between deploys | **Warn, store, continue** (as §19 item 17 for mode and model) | Refuse to start until reset: safer for views, but turns a filter edit into an outage |
| 6 | Retry/discard from a node not running the subscription | **Request columns on the parked row and a parked check only while an aggregate is held** (§4.3) | (a) Admin works only on the node running the subscription (the caller routes; simpler, unusable behind a load balancer). (b) A `pg_notify` to the owner: faster, but a lost notification needs the check anyway |
| 7 | Tenant filtering in SQL | **In memory only in phase 8**; SQL tenant predicate later, measured, with the index question | Add it now beside the type filter |
| 8 | `loadEventsCausedBy` on the adapter | **Implement with a plain index on `caused_by_event_id`, created by the contributor** (causation lookup is in §19 item 19's list) | Opt-in index like the event-type index (append cost only for those who need it); or throw, as `EventStore`'s default does |
| 9 | In-transaction subscribers on the new engine (`InTransactionEventProcessor` needs the old manager) | **Out of phase 8**: the adapter's local event bus keeps `PersistedEvents` at every commit stage, which is what in-transaction handlers consume; the four ITs that use `EventStoreSubscriptionManager` stay out of the gate. A typed in-transaction processor on the new API is a later item | Build an `InTransactionReactor` on the adapter's bus now; or run the old manager's in-transaction path against the adapter (it uses only `localEventBus`), which re-imports the old subscription model |
| 10 | Notifier/listener (phase-6 decision 1) and lease keeper (phase-7 decision 1) per `EventLog` or per database | **Keep per `EventLog`**: phase 8 wires one `EventLog` per database; revisit in phase 10 if the starter allows several | One per database per JVM, shared like `CatchUpPermits` |
| 11 | Split the phase | **Yes, 8a / 8b** as §12 | One phase 8 with eight units and one gate |
| 12 | `Subscription` public type | **Package-private behind `SubscriptionHandle`**, so the 2 500-line class is not API | Keep `Subscription` public with fewer public methods |
| 13 | `StartFrom.head()` resolved in the start statement | **Yes** | Caller resolves via `findHighestGlobalEventOrderPersisted` (racy between lookup and start) |

## 15. Decisions (2026-11-17)

The maintainer decided the questions that touch the phase's shape and existing modules, and question 7 (tenant
filter). Claude settled the rest as recommended in §14, under the maintainer's standing instruction to ask only on
genuine design forks.

- **Split:** phase 8 is built as **8a** (subscription API, processors, parked events and the admin SPI) and **8b**
  (the `EventStore` adapter, the engine reads it needs, and the admin REST mapping), each with its own gate and stop,
  as §12 divides them.
- **Existing modules (§13):**
  - E1 `admin-api-spec` and E2 `spring-boot-starter-admin-api`: approved, additively, in 8b.
  - E3, the root POM entry for the new `postgresql-event-log-integration` module: approved.
  - No other existing module is changed. A unit that finds it must change one stops and asks.
- **Repository ITs:** copied into the new module, with a drift test that fails when the originals change. The
  originals are not refactored.
- **Tenant filter (question 7), decided by the maintainer 2026-11-17:** in SQL as well, not in memory only. A
  `tenant = ?` predicate on catch-up and bridge reads, in memory on live batches (the event-type filter's split), with an
  optional `(tenant, global_order)` index built like the type index. Built in 8a-C's scope or a small unit after 8a-B.

  **Built (2026-11-17, unit after 8a-B).** The tenant is part of the subscription spec and is applied wherever the
  event-type filter is: a `tenant = ?` predicate on the filtered catch-up page, the skip-ahead probe, the `CONTIGUOUS`
  deferred bridge and both branches of the `FRESH` bridge (holes and ranges), combined with `event_type = ANY(?)` when
  the subscription also filters on types; attached batches and the inbox's bound are filtered in memory, so the handler
  wrapper of 8a-A is gone. Each filter shape has its own statement set (`EventLogStorage.FilterStatements`); in the
  bridge's ranges branch the tenant predicate sits behind an `OFFSET 0` fence, because inside the join a tenant the
  statistics consider rare made the generic plan scan the event table. The density switch obscures the tenant column
  (`tenant || ''`) as it does `event_type + 0`. A filtered bridge under an unknown watermark keeps counting non-matching
  runs as gaps and hands them off, as phase 5 §6.3 requires; mutant `FILTERED_BRIDGE_MARKS_RANGE_UNDER_UNKNOWN_WATERMARK`
  (mark the covered range handled there) loses the held event in `EventLogTenantFilterIT`. The opt-in index
  `el_<type>_events_tenant_order` on `(tenant, global_order)` is configured with `EventLogSettings.tenantIndexed` and
  built like the type index (`CONCURRENTLY`, in the background, only in schema mode `create`, otherwise the statement is
  logged; DDL in `EventLogSchemaContributor.tenantIndexStatements`); a valid index for either filter enables the
  skip-ahead probe. The filter digest already covered the tenant. `EventLogTenantFilterIT` also shows that a rare
  tenant's catch-up returns only that tenant's rows (with and without the index) and that the tenant combines with the
  type filter on catch-up and on live batches.
- **Every other question in §14:** settled by Claude as recommended there.

---

## 16. Results (2026-11-17)

Measured by `integration/spike/EventLogIntegrationGateSpikeIT` in `postgresql-event-log-integration`
(`scripts/run-phase0-spikes.sh --container --suite p8-gate`: `processorSteady`, `burst`, `retryBackoff`,
`adminAndParked`). The processor arms need this module, so the whole 8a suite lives here, §10.4 included (it uses only
the engine's public `EventLogAdmin`); the engine's `spike/EventLogGateSpikeIT` is unchanged. The harness is phase 7's:
a copy of the engine's `SpikeDb` (the integration module has no dependency on the engine's test classes), a fresh
container and a database name of its own per arm, `pg_stat_statements` by role. The engine runs on the `reader` role;
the application's pool (appends, and the JDBI and Spring page sources) on the `writer` role. Results files:
`components/postgresql-event-log-integration/target/spike-results/p8-c-steady.txt`, `p8-c-burst.txt`,
`p8-c-backoff.txt`, `p8-c-admin.txt`.

**The VM caveat.** As in phases 2-7 this is the shared development VM: PostgreSQL 18.4 in a container with `fsync=off`,
8 CPUs shared by client and server. Absolute times and rates need the dedicated server; statement counts, row counts and
the ratios between arms of one run do not depend on it. Every arm ran twice in the suite run. Two rows were then run
again because two runs were not enough to separate them from the noise: the burst (100 000 events take 0.8-1.0 s, so
JIT warm-up and the 5 ms completion poll matter) five more times, and twice at 1 000 000 events
(`-Dspike.gate.p8BurstEvents=1000000`); the steady shape four more times. Those reruns are reported with the suite run.

Harness notes. Commit-to-handler is from just before the commit to the handler, keyed by aggregate id (the processor
arms read it from the typed payload). Client CPU is the process CPU time (`OperatingSystemMXBean`) and allocation is
`ThreadMXBean.getTotalThreadAllocatedBytes()`, both for the whole test JVM over the window, so they include the writer
and the lease keeper; the difference between arms is what the processors add. Burst arms are timed from `subscribe`
until the progress row, polled on the observer connection (not counted), passes the last event. The integration module
got a `logback-test.xml` (a copy of the engine's): without one, logback logged at `DEBUG`, which would have charged log
formatting to the client CPU figures. No main code was changed and no knob was added to main code;
`IntegrationTestBridge` (test code) gained `parkedChecks(SubscriptionHandle)`. Spike knobs: `-Dspike.gate.runs` (2),
`-Dspike.gate.timeScale` (1.0), `-Dspike.gate.p8BurstEvents` (100000), `-Dspike.gate.p8Backoffs` ("100,25,5,1").

### 16.1 Processor overhead (§10.1)

**Steady.** Phase 7 §10.3's exclusive shape: 100 ev/s, one event per unit of work to a new aggregate, 10 s warm-up,
60 s window, 6 000 events and 60 000 deliveries per arm; RAW = 5 `CONTIGUOUS` page handlers + 5 `FRESH` periodic
handlers on the engine API, PROCESSOR = 5 `ViewProjection` (`on(Added.class, ...)`) + 5 `EventReactor`. Six pairs (the
suite's two runs and the four reruns, arm order alternating). Commit-to-handler, ms:

| | RAW | PROCESSOR | Difference |
|---|---|---|---|
| All deliveries p50 | 0.55-0.62 | 0.55-0.62 | 0.00-+0.02 per pair |
| All deliveries p99 | 1.50-2.36 (median 1.98) | 1.57-2.88 (median 2.34) | −0.34-+0.93 per pair |
| `CONTIGUOUS` p50 / p99 | 0.76-0.87 / 2.14-8.03 | 0.80-0.90 / 2.93-7.82 | +0.00-+0.04 / −0.93-+4.66 per pair |
| `FRESH` p50 / p99 | 0.43-0.51 / 0.84-1.08 | 0.43-0.52 / 0.80-1.09 | −0.01-+0.01 / −0.04-+0.17 per pair |
| Reader statements per delivery | 1.605-1.606 | 1.605-1.606 | equal |
| Client CPU per delivery (whole JVM) | 275-323 µs | 284-306 µs | inside the noise |
| Allocation | 3.5-4.2 MB/s, 3 495-4 232 B per delivery | 5.6-5.8 MB/s, 5 646-5 799 B per delivery | +1.5-2.2 KB per delivery |

**Burst.** 100 000 seeded events over 10 000 interleaved aggregates into one exclusive `CONTIGUOUS` projection at the
default page size (100), a pre-populated view, one `unnest` upsert per page: RAW = a `PageHandler` on the engine API,
VIEW_ENGINE = `ViewProjection.onPage` (deserializing every event) on `PageTransactionSource.engine()`. Events/s:

| Run | RAW | VIEW_ENGINE | Ratio |
|---|---|---|---|
| suite 1, 2 | 125 054, 128 284 | 117 373, 119 229 | 0.94, 0.93 |
| reruns 1-5 | 101 755, 127 340, 128 975, 126 782, 129 214 | 103 236, 119 362, 119 166, 120 052, 118 013 | 1.01, 0.94, 0.92, 0.95, 0.91 |
| 1 000 000 events, runs 1, 2 | 113 183, 119 370 | 110 010, 111 963 | 0.97, 0.94 |

Statements per page 4.21 in both arms (4.20 at 1 000 000 events): `BEGIN`, the idle-timeout `set_config`, the
handler's upsert and the progress update per page, plus 0.2 catch-up reads (one per 500 rows); every view folded to
exactly the seeded count. Client CPU per event 2.9-3.0 µs RAW against 3.6-4.5 µs VIEW_ENGINE once warm (the first arm
of a JVM pays the JIT: 5-18 µs); allocation 1.33-1.41 KB per event RAW against 3.39-3.56 KB per event VIEW_ENGINE.

**Verdict: met.** p50 is within 0.04 ms of the raw arm in every pair, well inside the 0.2 ms allowance. p99 moves
between runs of the same arm by more than the arms differ: the raw arm's own pooled p99 spans 1.50-2.36 ms and its
`CONTIGUOUS` p99 2.1-8.0 ms, and each processor figure lies inside or at the edge of that spread (`FRESH` p99 at most
+0.17 ms). The pooled p99 median is 0.36 ms higher for the processors; that is inside the raw arm's 0.86 ms spread, so by
§10's rule it is no change, but it leans one way in four of six pairs and is the figure to re-check on the dedicated
server. Statements per delivered event are equal (1.605-1.606). The burst ratio is 0.91-0.95 at 100 000 events once the
JVM is warm (1.01 in a cold first arm) and 0.94-0.97 at 1 000 000, all ≥ 0.9. The cost the processors add is the
deserialization: about 2 KB allocated and 0.6-1.5 µs of CPU per event, which is 5-9 % of a set-based page's throughput
and invisible at 100 ev/s.

### 16.2 Application-sourced pages (§10.2)

The burst shape with the same `ViewProjection` on the JDBI unit-of-work source
(`UnitOfWorkPageTransactions(new JdbiUnitOfWorkFactory(Jdbi.create(pool)))`) and the Spring source
(`SpringPageTransactions` on a `DataSourceTransactionManager`), against VIEW_ENGINE. Events/s and the ratio to
VIEW_ENGINE of the same run:

| Run | VIEW_JDBI | VIEW_SPRING |
|---|---|---|
| suite 1, 2 | 108 518 (0.92), 115 425 (0.97) | 112 288 (0.96), 116 371 (0.98) |
| reruns 1-5 | 105 654 (1.02), 115 441 (0.97), 118 931 (1.00), 118 096 (0.98), 119 349 (1.01) | 114 379 (1.11), 114 713 (0.96), 115 420 (0.97), 119 586 (1.00), 117 756 (1.00) |
| 1 000 000 events, runs 1, 2 | 108 372 (0.99), 110 857 (0.99) | 111 027 (1.01), 111 903 (1.00) |

Statements per page 4.21 in every arm (4.20 at 1 000 000): the four page statements move to the application's role
(writer 4.00 per page) and only the catch-up reads stay on the engine's (0.21). Borrow time from the application pool:
mean ≤ 0.001 ms, maximum < 0.05 ms over 1 001 (10 001) borrows; the engine's own pool in VIEW_ENGINE: mean ≤ 0.006 ms,
maximum 0.2-0.4 ms (20 ms once, in a cold first arm). The JDBI source's per-commit lookup of its transaction-handler
guard (8a-C's note) does not show: JDBI is level with Spring and with `engine()`. Client CPU and allocation per event
are the same as VIEW_ENGINE once warm (3.5-4.5 µs, 3.4-3.6 KB).

**Verdict: met.** Events/s 0.92-1.11 of `engine()` (0.99-1.01 at 1 000 000 events), statements per page equal.

### 16.3 Admin and parked check (§10.4)

**Listing.** 1 000 progress rows over 10 aggregate types with 1 000 events each, 100 of them with a parked head and 9
rows behind it, seeded in SQL; `subscriptions(Optional.empty())` called 200 times (after 20 warm-up calls) from a second
`EventLog` on the writer role that runs nothing. Every call returned 1 000 rows, 100 with parked heads.

| Run | p50 | p99 | max | Statements per call |
|---|---|---|---|---|
| 1 | 2.68 ms | 4.93 ms | 5.87 ms | 1.00 |
| 2 | 2.51 ms | 3.24 ms | 3.56 ms | 1.01 (200 listing statements and one `SET application_name` of a pool connection opened in the window) |

**Parked check.** 20 per-instance `CONTIGUOUS` subscriptions on one event log (default backstop 1 s, so the parked-check
interval is 1 s): 10 whose handler fails 20 events with zero retries, so each holds 20 parked heads, and 10 that hold
none; then an idle 60 s window. In both runs the parked-request read ran 590 times: 59 per holding subscription, 0.98
per interval, and **0** for every subscription holding nothing (its `parkedChecks` counter did not move).

**Retry from another node.** A second `EventLog` that runs nothing calls `retryParked` on one of the 20 heads of a
holding subscription after the handler has been allowed to succeed for it, 20 times, 50-1 050 ms apart; every call
answered `ACCEPTED`, and the head reached the handler on the owning node after p50 509 / 539 ms and p100 1 004 / 958 ms.

**Verdict: met.** `subscriptions()` is one statement with p99 3.2-4.9 ms against 50 ms; the parked check reads nothing
for a subscription without held aggregates and at most once per interval for one with (0.98). Retry from another node
lands within one backstop interval (p100 at most 1 004 ms: the 1 s interval plus the page that serves it), as expected.

### 16.4 The retry back-off default (phase 4 §11.1)

Phase 4's open settings question, measured on this suite's burst seed (100 000 events, 10 000 aggregates): a raw
`CONTIGUOUS` row-by-row upsert at page 100, 3 retries, back-off maximum 10 s, every global order divisible by 997 always
failing (100 events, each heading its own aggregate), the back-off set per subscription with `ErrorSettings`. Both runs:

| Initial back-off | Time | Events/s | vs no failures |
|---|---|---|---|
| no failures | 8.7-8.9 s | 11 243-11 466 | 1.00x |
| 100 ms (default) | 83.7-83.8 s | 1 193-1 195 | 0.10-0.11x |
| 25 ms | 29.3 s | 3 416-3 417 | 0.30x |
| 5 ms | 15.7 s | 6 375-6 384 | 0.56-0.57x |
| 1 ms | 12.7 s | 7 863-7 868 | 0.69-0.70x |

Every failing event was parked as a head with its 450 later events behind it (100 heads, 550 rows), and view plus parked
rows equal the seeded count in every arm. The times fit `8.8 s + 100 × (7 × backoff + 32 ms)`: each always-failing event
waits backoff × (1 + 2 + 4) over its three retries, plus about 32 ms of shrinking (seven halvings), failed pages and
smaller pages afterwards that no back-off setting removes. At 100 ms the waits are 70 s of the 84 s; at 1 ms the shrink
is most of what is left, which is why 1 ms reaches only 0.70x here (phase 4's 0.92x was measured against row by row on
200 000 events with its periodic baseline, a different denominator).

**Recommendation: lower the default `retryBackoffInitial` to 10 ms; do not change it in 8a.** At 10 ms the three retries
of an event that fails alone wait 10 + 20 + 40 = 70 ms, and the model gives about 19 s here, 0.45x, against 0.10x today.
The trade-off is the chance a *transient* handler failure gets to clear before its event is parked: 70 ms of waiting
instead of 700 ms. That still covers a deadlock retry or a serialization failure, which clear in milliseconds; a lock
that is held for seconds outlasts both settings anyway (its failure already takes `lock_timeout` per attempt), and a
parked event can be retried by an operator from any node (§16.3). The same setting is the first wait after an I/O
failure, which is retried indefinitely and doubles to `retryBackoffMax` (10 s): at 10 ms it reaches 1 s after seven
failures instead of four, a few more statements against a database that is down, which is cheap. Going lower than
10 ms buys little: 5 ms gives 0.57x and 1 ms 0.70x, and the remaining cost is the shrink, whose fix is phase 4's other
option (an event isolated by the shrink retried without the first wait), not a smaller back-off. A rate of one
always-failing event per 1 000 is a poison stream, not a normal one; at realistic rates both settings cost little,
which is why this is a default to choose, not a defect. For the maintainer to decide; nothing was changed.

### 16.5 Verdict summary

| Row | Pass line | Measured | Verdict |
|---|---|---|---|
| §10.1 steady latency | p50, p99 within the noise of raw or ≤ +0.2 ms | p50 +0.00-0.04 ms; p99 inside the raw arm's run-to-run spread (pooled median +0.36 ms against a 0.86 ms spread) | met (p99 to re-check on the dedicated server) |
| §10.1 statements per delivery | equal | 1.605-1.606 both | met |
| §10.1 burst | ≥ 0.9× raw | 0.91-0.95 warm at 100 000 events, 0.94-0.97 at 1 000 000 | met |
| §10.2 page sources | ≥ 0.9× `engine()`, statements per page equal | JDBI 0.92-1.02, Spring 0.96-1.11; 4.21 per page everywhere | met |
| §10.4 listing | one statement, p99 < 50 ms | 1.00 statement, p99 3.2-4.9 ms | met |
| §10.4 parked check | 0 without held aggregates, ≤ 1 per interval with | 0; 0.98 per interval | met |
| §10.4 retry from another node (recorded) | ≤ one backstop interval expected | p50 509-539 ms, p100 958-1 004 ms | as expected |

No row missed. The one figure that leans is the steady p99 of the processors (§16.1), inside the noise on this VM.

### 16.x Retry back-off default, decided (2026-11-17)

The maintainer chose the recommended 10 ms default and asked that it stay configurable. `EventLogSettings.defaults()` now uses `DEFAULT_RETRY_BACKOFF_INITIAL` = 10 ms. It is configurable engine-wide with `withRetryBackoff(initial, max)` and per subscription with `ErrorSettings`.
