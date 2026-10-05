# Essentials Components - Spring Boot Starter: PostgreSQL Event Store

> **NOTE:** **The library is WORK-IN-PROGRESS**

Spring Boot auto-configuration for the PostgreSQL Event Store and all PostgreSQL-focused Essentials components.

**LLM Context:** [LLM-spring-boot-starter-modules.md](../../LLM/LLM-spring-boot-starter-modules.md)

## Table of Contents

- [What This Starter Provides](#what-this-starter-provides)
- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Auto-Configured Beans](#auto-configured-beans)
- [Configuration Properties](#configuration-properties)
  - [Event Store Configuration](#event-store-configuration)
  - [Gap Handling](#gap-handling)
  - [CDC Configuration (Hybrid Logical Replication)](#cdc-configuration-hybrid-logical-replication)
  - [Subscription Manager Configuration](#subscription-manager-configuration)
  - [Subscription Monitor Configuration](#subscription-monitor-configuration)
  - [Event Store Metrics Configuration](#event-store-metrics-configuration)
- [Shared Configuration (from spring-boot-starter-postgresql)](#shared-configuration-from-spring-boot-starter-postgresql)
- [Customizing Event Mapping](#customizing-event-mapping)
- [Typical Dependencies](#typical-dependencies)

## What This Starter Provides

This starter gives you a fully configured **Event Store** - a specialized database for storing events in an event-sourced application.  
Instead of storing the current state of your domain objects, you store the sequence of events that led to that state.

**What you get out of the box:**
- **Event persistence** - Store domain events (e.g., `OrderPlaced`, `OrderShipped`) with full history
- **Event subscriptions** - React to events as they happen (for projections, notifications, integrations)
- **Event processors** - Handle events reliably with built-in retry and dead-letter support
- **Spring integration** - Works seamlessly with `@Transactional` and Spring's transaction management

This starter also includes everything from `spring-boot-starter-postgresql` (distributed locks, durable queues, inbox/outbox patterns).

**Typically combined with:**
- [eventsourced-aggregates](../eventsourced-aggregates/README.md) - Event-sourced aggregate base classes and repository for **Java**
- [kotlin-eventsourcing](../kotlin-eventsourcing/README.md) - Kotlin DSL for defining aggregates and commands with less boilerplate
- [postgresql-document-db](../postgresql-document-db/README.md) - Kotlin Document database (using Postgresql) for read models/projections (query-optimized views of your event data) produced by e.g. `ViewEventProcessor`s.

---

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

> **Note:** This starter transitively includes `spring-boot-starter-postgresql`, so you don't need to add it separately.

## Security

### ⚠️ Critical: SQL Injection Risk

Components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

⚠️ **Table Name Security:** All table name properties are validated using `PostgresqlUtil.checkIsValidTableOrColumnName()` as a first-line defense, but this does NOT provide exhaustive protection.

> **Developer Responsibility:**
> - Only derive table/column/index names from controlled, trusted sources
> - NEVER allow external/untrusted input to provide table names
> - Implement additional sanitization for all configuration values
>
> Failure to sanitize values could compromise database security and integrity.

### Module-Specific Security Guidance

See individual module documentation for detailed security considerations:
- [foundation](foundation/README.md#security)
- [foundation-types](foundation-types/README.md#security)
- [postgresql-event-store](postgresql-event-store/README.md#security)
- [postgresql-distributed-fenced-lock](postgresql-distributed-fenced-lock/README.md#security)
- [postgresql-queue](postgresql-queue/README.md#security)
- [eventsourced-aggregates](eventsourced-aggregates/README.md#security)
- [kotlin-eventsourcing](kotlin-eventsourcing/README.md#security)

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## Auto-Configured Beans

All beans use `@ConditionalOnMissingBean` - define your own bean of the same type to override the default.

> **Note:** This starter includes `spring-boot-starter-postgresql`, so you also get all its beans (Jdbi, FencedLockManager, DurableQueues, etc.).
> See [spring-boot-starter-postgresql: Auto-Configured Beans](../spring-boot-starter-postgresql/README.md#auto-configured-beans) for the full list.

### Event Store Core

| Bean | What It Does                                                                                                                                                                                                       |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PostgresqlEventStore` | The main event store. Use it to append events and load event history. See [Event Store documentation](../postgresql-event-store/README.md)                                                                         |
| `SeparateTablePerAggregateTypePersistenceStrategy` | Creates one database table per `AggregateType` <br/>(e.g., `AggregateType("Orders")` -> `orders_events`, `AggregateType("Customers")` -> `customers_events`). <br/>This keeps related events together for efficient querying |
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | Ensures event store operations participate in Spring's `@Transactional` transactions. See [UnitOfWork documentation](../foundation/README.md#unitofwork-transactions)                                              |
| `Jackson3JSONEventSerializer` | Converts your event objects to/from JSON for database storage                                                                                                                                                      |

### Subscriptions & Event Processing

Subscriptions let you react to events - for building read models, sending notifications, or triggering workflows.

| Bean | What It Does                                                                                                                                                                                                                                                                               |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `EventStoreSubscriptionManager` | Coordinates all event subscriptions. Handles polling, resume points, and distributes events to subscribers. See [Subscriptions](../postgresql-event-store/README.md#subscriptions)                                                                                                         |
| `PostgresqlDurableSubscriptionRepository` | Remembers where each subscriber left off (like a bookmark), so subscribers resume from the right position after restart                                                                                                                                                                    |
| `EventProcessorDependencies` | A convenience object bundling all dependencies needed by [`EventProcessor`](../postgresql-event-store/README.md#eventprocessor) and [`InTransactionEventProcessor`](../postgresql-event-store/README.md#intransactioneventprocessor). <br/>Just inject this instead of 5+ separate dependencies |
| `ViewEventProcessorDependencies` | Same convenience for [`ViewEventProcessor`](../postgresql-event-store/README.md#vieweventprocessor)                 |
| `AnnotationBasedInMemoryProjector` | Lets you project events onto plain Java objects using `@EventHandler` methods - useful for loading aggregate state. See [In-Memory Projections](../eventsourced-aggregates/README.md#in-memory-projections)                                                                                 |

### Event Publishing

| Bean | What It Does |
|------|--------------|
| `EventStoreEventBus` | Publishes events locally within your application when they're persisted. In-transaction subscribers receive events before commit; other subscribers receive them after |
| `PersistableEventMapper` | Transforms your domain events into the format stored in the database, adding metadata like event-id, timestamp, and event-order |

### Observability

| Bean | When Active | What It Does |
|------|-------------|--------------|
| `MicrometerTracingEventStoreInterceptor` | `management.tracing.enabled=true` | Adds distributed tracing spans to event store operations |
| `RecordExecutionTimeEventStoreInterceptor` | Always | Logs slow event store operations based on configurable thresholds |
| `MeasurementEventStoreSubscriptionObserver` | Always | Collects metrics about subscription processing (events/second, lag, etc.) |
| `EventStoreSubscriptionMonitorManager` | Always | Periodically checks subscription health (enabled by default, runs every minute) |
| `SubscriberGlobalOrderMicrometerMonitor` | `management.tracing.enabled=true` | Exposes a Micrometer gauge showing each subscriber's current position |
| `SubscriptionStoppedMicrometerMonitor` | A `MeterRegistry` is present and `essentials.eventstore.subscription-monitor.enabled=true` (default) | Exposes the gauge `essentials.eventstore.subscription.stopped` - `1` while a subscription is stopped by its `SubscriptionErrorPolicy`, else `0`. The signal to alert on for a halted projection (the `stopped_by_error_policy` counter only records that a stop happened) |

### Admin APIs

Service-layer APIs for building admin dashboards or REST endpoints:

| Bean | What It Does |
|------|--------------|
| `EventStoreApi` | Query events, manage subscriptions, inspect event streams |
| `PostgresqlEventStoreStatisticsApi` | Get statistics like event counts, table sizes, and performance metrics |

---

## Configuration Properties

> **Note:** This starter includes `spring-boot-starter-postgresql`, so all its configuration properties also apply.
> See [spring-boot-starter-postgresql: Configuration Properties](../spring-boot-starter-postgresql/README.md#configuration-properties) for FencedLock, DurableQueues, EventBus, Scheduler, and Metrics configuration.

### Event Store Configuration

```properties
essentials.eventstore.identifier-column-type=text
essentials.eventstore.json-column-type=jsonb
essentials.eventstore.use-event-stream-gap-handler=true
essentials.eventstore.verbose-tracing=false
essentials.eventstore.add-annotation-based-in-memory-projector=true
essentials.eventstore.auto-flush-and-publish-after-append-to-stream=false
```

| Property | Default | What It Controls                                                                                                                                                     |
|----------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `identifier-column-type` | `text` | How aggregate IDs are stored: `text` (any string) or `uuid` (optimized for UUID values)                                                                              |
| `json-column-type` | `jsonb` | How events are stored: `jsonb` (queryable, slightly slower writes) or `json` (faster writes, no indexing)                                                            |
| `use-event-stream-gap-handler` | `true` | Whether to detect and handle gaps in `GlobalEventOrder` (can happen during concurrent writes). See [Gap Handling](#gap-handling) before turning it off                |
| `verbose-tracing` | `false` | When `true`, traces include low-level operations. When `false`, only high-level operations are traced                                                                |
| `add-annotation-based-in-memory-projector` | `true` | Auto-register the projector that supports `@EventHandler` methods on POJOs - See [In-Memory Projections](../eventsourced-aggregates/README.md#in-memory-projections) |
| `auto-flush-and-publish-after-append-to-stream` | `false` | **Flush Publishing** - Publish events immediately after `appendToStream()` instead of waiting for commit. See [Flush Publishing](#flush-publishing)                  |

#### Flush Publishing

Controls *when* in-transaction subscribers receive events. See [postgresql-event-store: Flush Publishing](../postgresql-event-store/README.md#flush-publishing) for detailed documentation.

| Setting | Behavior |
|---------|----------|
| `false` (default) | Events published at `BeforeCommit` and `AfterCommit`. Subscribers receive all events from a transaction together, just before it commits |
| `true` | Events *also* published immediately after each `appendToStream()` call. Use this when subscribers need to react to each event individually within the same transaction (e.g., saga coordination) |

### Gap Handling

A **gap** is a `GlobalEventOrder` that is missing from what a subscription just read, typically because the transaction that took it has not committed yet. A **transient** gap may still be filled when that transaction commits; one that stays open past the promotion threshold (120 seconds by default) becomes **permanent** (the transaction was most likely rolled back) and is no longer waited for. The mechanics, the gap types and their behavior are documented in [postgresql-event-store: Gap Handling](../postgresql-event-store/README.md#gap-handling) (see "Gap Types" and "Behavior"); this section covers what the starter wires and how to change it. `spring-postgresql-event-store` only adds Spring transaction integration and wires no gap handling itself.

**What the starter wires**

| Piece | Default | Notes |
|-------|---------|-------|
| `essentials.eventstore.use-event-stream-gap-handler` | `true` | `true`: the `PostgresqlEventStore` is built on the `EventStreamGapHandler` bean. `false`: it is built on `NoEventStreamGapHandler`, so polling subscriptions track no gaps at all. The `EventStreamGapHandler` bean itself is still created (the CDC beans take it) |
| `EventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>` bean | `PostgresqlEventStreamGapHandler`, 60 s refresh of its transient-gap cache, 120 s permanent-gap threshold, default per-poll gap selection | `@ConditionalOnMissingBean`. Schema ownership follows `essentials.schema.mode` |
| Gap statistics | collected | Per subscription, as `SubscriptionStatistics.gaps()` (`newTransientGaps`, `resolvedTransientGaps`, `promotedToPermanentGaps`), while `essentials.eventstore.subscription-manager.statistics.enabled` is `true` (the default). Also exposed through the admin API |

**How gaps are handled in 0.60**

- Each poll asks again for the subscriber's open transient gaps. With the default handler that is every open gap up to 50; beyond 50, the 20 highest, the 10 lowest and a rotating window of 20 in between, so a poll never carries more than 50 gap orders.
- Transient gaps are per subscriber; permanent gaps are shared by every subscriber of the `AggregateType` (and can be reset, see below).
- A tenant-filtered subscription loads every tenant's events in the polled range and filters by tenant in memory, so other tenants' global orders are never mistaken for gaps. This holds for polling and for CDC.
- A gap is resolved only once the event that fills it has been **handled**. Subscriptions created by the `EventStoreSubscriptionManager` acknowledge each event through a `SubscriberAcknowledgement`, and the gap is deleted inside the handler's own unit of work, so a rolled-back handler leaves the gap open and a subscription that stops or crashes before handling the fill gets the event again after the restart.

**Consequences for your handlers**

- Handlers must tolerate redelivery of a gap-filling event.
- `GlobalEventOrder` is **not** delivered in strict sequence across aggregates (polling when a gap fills, CDC whenever a lower order commits after a higher one). Never deduplicate in a handler by "the highest `GlobalEventOrder` seen so far": that drops exactly the late events. Deduplicate by event id, or rely on `EventOrder` per aggregate. This is trap `ESS-116` in [LLM-traps.md](../../LLM/LLM-traps.md).

**Customizing**

Define your own `EventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>` bean and the starter's default backs off. The event store, the CDC dispatcher and `CdcEventStore` then all use your bean. The `PostgresqlEventStreamGapHandler` constructor takes the strategies:

```java
@Bean
EventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration> eventStreamGapHandler(
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
        EssentialsComponentsProperties properties) {
    return new PostgresqlEventStreamGapHandler<>(
            unitOfWorkFactory,
            Duration.ofSeconds(60),    // how often the transient-gap cache is refreshed from the database
            // which transient gaps each poll asks for again: keep the default selection (or compose it in your own strategy)
            PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection(),
            // when a transient gap is given up on and becomes permanent (CDC subscriptions give up at the same threshold)
            PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(300),
            properties.getSchema().getMode().schemaOwnership());   // keeps essentials.schema.mode honoured
}
```

- `ResolveTransientGapsToIncludeInQueryStrategy` decides which transient gaps a poll asks for; `ResolveTransientGapsToPermanentGapsPromotionStrategy` decides when a gap becomes permanent (`thresholdBased(seconds)` is the built-in). Both are nested in `PostgresqlEventStreamGapHandler`. Keep the promotion threshold longer than your longest transaction.
- `ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection()` returns the default selection described above; call it inside your own strategy to add to it or filter it. It keeps a rotation per subscription and per instance whenever the gap handler asks - passed as is, wrapped, or composed in your own strategy - so call each instance once per ask. That holds only on the thread the gap handler asks on: called directly outside the gap handler, or from an executor or a `CompletableFuture`, an instance rotates with its own, shared by every subscription that reaches it that way. Whatever your strategy returns, a poll also asks for the gaps that are old enough to be promoted, because a gap is only promoted when a poll asked for it and its event was not there.
- Under CDC a subscription gives up waiting for a gap at the promotion threshold of `thresholdBased(seconds)`. A promotion strategy written as a lambda states no threshold, so CDC then gives up after 120 s; implement `permanentGapThreshold()` on it to change that.
- Build the gap handler on the event store's `EventStoreUnitOfWorkFactory`. On a different factory it logs a one-time WARN and resolves gaps in a transaction of its own, which commits before the handler's.
- Always pass `properties.getSchema().getMode().schemaOwnership()`; the shorter constructors default to `SchemaOwnership.COMPONENT` and would run DDL even in `essentials.schema.mode=validate`.
- Reset permanent gaps (for example after data recovery) with `eventStreamGapHandler.resetPermanentGapsFor(AggregateType.of("Orders"))`; the overloads take a `LongRange` or a list of `GlobalEventOrder`.
- Gaps are also covered by [LLM-postgresql-event-store.md](../../LLM/LLM-postgresql-event-store.md#gap-handling).

### CDC Configuration (Hybrid Logical Replication)

> **Which delivery mechanism should I use?** Subscribers are always correct regardless —
> the choice is only *how fast the fast path is* and *what operational footprint you accept*.
> See **[cdc.md §12.6 "Choosing a delivery mechanism"](../../docs/cdc.md)**
> for the full comparison (plain/jittered/notify polling vs CDC INBOX/DIRECT, with indicative
> latency and DB-load numbers). Quick guide:
> - **Simplest, any Postgres, new project** → leave CDC off (the default) and run polling
>   (jittered by default).
> - **Latency-sensitive read models / high fan-out / want audit trail or replica-offload** →
>   opt in with `essentials.eventstore.cdc.enabled=true`; the delivery mode is then `INBOX` (the
>   default) and the startup mode `AUTO` (falls back to polling if CDC cannot start).
> - **Don't control the DB / no `wal_level=logical` / want minimal moving parts** → leave CDC
>   disabled.

> **CDC is disabled by default.** `essentials.eventstore.cdc.enabled` defaults to `false`, and
> every CDC bean is gated on it being `true` with no `matchIfMissing`. An application that says
> nothing about CDC gets no replication slot, no publication changes and no tailer: the event store
> polls, exactly as before CDC existed. CDC's operational surface (replication slot, publication,
> WAL retention; see [cdc.md §5](../../docs/cdc.md)) is heavier than polling, so adopt it
> deliberately:
> ```properties
> essentials.eventstore.cdc.enabled=true
> ```
> Once enabled, if your database does **not** have `wal_level=logical` (or you can't create slots),
> `mode=auto` keeps the application up with subscribers on polling - no events are lost, but check
> `/actuator/health/cdc` so a broken CDC setup does not go unnoticed.

Hybrid CDC can run in durable inbox mode (`INBOX`) or direct publish mode (`DIRECT`):

```properties
essentials.eventstore.cdc.enabled=true
essentials.eventstore.cdc.mode=auto
essentials.eventstore.cdc.delivery-mode=inbox
essentials.eventstore.cdc.wal-parser-mode=bytes
```

CDC filtering defaults to EventStore inserts only:

- `wal2json`: only `kind=insert` changes where `table` is one of the configured aggregate event stream tables
- `pgoutput`: relation metadata is cached, only insert changes for configured aggregate event stream tables are converted, and non-insert messages are ignored

Tuning baseline (good starting point from perf-lab runs):

```properties
essentials.eventstore.cdc.cdc-event-store-backfill-batch-size=1000
essentials.eventstore.cdc.cdc-dispatcher.batch-size=200
essentials.eventstore.cdc.cdc-dispatcher.poll-interval=PT0.05S
essentials.eventstore.cdc.wal-replication-tailer.poll-interval=PT0.025S
```

| Property | Default | What It Controls |
|----------|---------|------------------|
| `essentials.eventstore.cdc.enabled` | `false` | Opt-in. Enables CDC beans (`WalReplicationTailer`, `CdcDispatcher`, `CdcEventStore`) |
| `essentials.eventstore.cdc.mode` | `auto` | `auto`: fallback to polling if CDC cannot start. `require`: fail startup if CDC cannot start |
| `essentials.eventstore.cdc.delivery-mode` | `inbox` | `inbox`: durable inbox + dispatcher. `direct`: tailer converts and publishes directly (no inbox persistence, dispatcher idle) |
| `essentials.eventstore.cdc.plugin` | `pgoutput` | Logical decoding plugin. `pgoutput` is the default; `wal2json` remains available for explicit use |
| `essentials.eventstore.cdc.pg-output.publication-name` | `essentials_cdc_publication` | Publication used when `plugin=pgoutput` |
| `essentials.eventstore.cdc.wal-parser-mode` | `string` | `string` or `bytes` parser path for wal2json payloads (recommend `bytes`) |
| `essentials.eventstore.cdc.cdc-dispatcher.dispatched-row-policy` | `mark-dispatched` | `mark-dispatched`: keep row for TTL cleanup. `delete`: remove row immediately after successful dispatch |
| `essentials.eventstore.cdc.event-bus.backpressure-buffer-size` | `8192` | DIRECT-mode CDC bus buffer size |
| `essentials.eventstore.cdc.event-bus.non-serialized-max-retries` | `16` | Retries for `FAIL_NON_SERIALIZED` emit races |
| `essentials.eventstore.cdc.event-bus.overflow-max-retries` | `20` | Retries for `FAIL_OVERFLOW` before applying overflow policy |
| `essentials.eventstore.cdc.event-bus.overflow-policy` | `fail-fast` | `fail-fast` throws on irrecoverable emit failures, `log-and-drop` logs and drops |
| `essentials.eventstore.cdc.event-bus.queued-task-cap-factor` | `1.5` | Reserved for parity with `LocalEventBus`; currently informational for `CdcEventBus` |

### Subscription Manager Configuration

Controls how the subscription manager polls for and processes events:

```properties
essentials.eventstore.subscription-manager.event-store-polling-batch-size=10
essentials.eventstore.subscription-manager.event-store-polling-interval=100ms
essentials.eventstore.subscription-manager.max-event-store-polling-interval=2000ms
essentials.eventstore.subscription-manager.snapshot-resume-points-every=1s
essentials.eventstore.subscription-manager.snapshot-resume-points-after-events=0
```

| Property | Default | What It Controls |
|----------|---------|------------------|
| `event-store-polling-batch-size` | `10` | How many events to fetch per poll. Higher = more throughput, but more memory per batch |
| `event-store-polling-interval` | `100ms` | How often to check for new events when events are being processed |
| `max-event-store-polling-interval` | `2000ms` | Maximum wait between polls when no events are found (uses jittered backoff) |
| `snapshot-resume-points-every` | `1s` | How often to save each subscriber's position. Bounds how much is re-processed after a crash. Only positions that changed are written (one batched `UPDATE` per interval at most), so idle subscribers cost nothing |
| `snapshot-resume-points-after-events` | `0` (off) | Opt-in. Also save a subscriber's position as soon as it has moved this many global event order positions since its last save, instead of waiting for the next `snapshot-resume-points-every` tick. Bounds re-processing after a crash by event count as well as by time. Checked in memory, so idle or slow subscribers cost nothing extra |

### CDC Operational API

When CDC is enabled, the starter also exposes a `CdcApi` bean alongside `EventStoreApi`.

`CdcApi#getStatus(principal)` returns:

- CDC availability state (`ACTIVE` / `INACTIVE` / `FAILED`)
- effective CDC configuration values
- replication slot snapshot from `pg_replication_slots`
- tailer runtime diagnostics and counters
- dispatcher runtime diagnostics and counters

### Subscription Monitor Configuration

The monitor periodically checks subscription health (e.g., detecting stuck subscribers):

```properties
essentials.eventstore.subscription-monitor.enabled=true
essentials.eventstore.subscription-monitor.interval=1m
```

| Property | Default | What It Controls |
|----------|---------|------------------|
| `enabled` | `true` | Whether to run periodic health checks on subscriptions |
| `interval` | `1m` | How often to run the health checks |

### Event Store Metrics Configuration

Configure threshold-based logging - operations exceeding thresholds are logged at the corresponding level:

```yaml
essentials:
  eventstore:
    metrics:
      enabled: true
      thresholds:
        debug: 25ms    # Log at DEBUG if operation takes >= 25ms
        info: 200ms    # Log at INFO if operation takes >= 200ms
        warn: 500ms    # Log at WARN if operation takes >= 500ms
        error: 5000ms  # Log at ERROR if operation takes >= 5000ms
    subscription-manager:
      metrics:
        enabled: true
        thresholds:
          debug: 25ms
          info: 200ms
          warn: 500ms
          error: 5000ms
```

**To see these logs**, configure the log level for:
- Event Store operations: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor`
- Subscription processing: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver`

---

## Shared Configuration (from spring-boot-starter-postgresql)

This starter includes `spring-boot-starter-postgresql` which provides:
- **Jdbi** - SQL database access that participates in Spring transactions
- **PostgresqlFencedLockManager** - Distributed locks for coordinating work across instances
- **PostgresqlDurableQueues** - Reliable message queuing with retry and dead-letter support
- **Inboxes/Outboxes** - Patterns for reliable message processing
- **DurableLocalCommandBus** - Command bus with guaranteed delivery
- **EssentialsScheduler** - Distributed task scheduling
- **Jackson modules** - Automatic serialization for Essentials types

**See [spring-boot-starter-postgresql](../spring-boot-starter-postgresql/README.md) for:**
- [FencedLock Configuration](../spring-boot-starter-postgresql/README.md#fencedlock-configuration)
- [DurableQueues Configuration](../spring-boot-starter-postgresql/README.md#durablequeues-configuration)
- [MultiTableChangeListener Configuration](../spring-boot-starter-postgresql/README.md#multitablechangelistener-configuration)
- [EventBus Configuration](../spring-boot-starter-postgresql/README.md#eventbus-configuration)
- [Scheduler Configuration](../spring-boot-starter-postgresql/README.md#scheduler-configuration)
- [Metrics Configuration](../spring-boot-starter-postgresql/README.md#metrics-configuration)
- [Lifecycle Configuration](../spring-boot-starter-postgresql/README.md#lifecycle-configuration)
- [DurableLocalCommandBus Customization](../spring-boot-starter-postgresql/README.md#durablelocalcommandbus-customization)
- [JdbiConfigurationCallback](../spring-boot-starter-postgresql/README.md#jdbiconfigurationcallback)

---

## Customizing Event Mapping

The default `PersistableEventMapper` adds basic metadata. Override it to include additional context like correlation IDs for tracing or tenant IDs for multi-tenancy:

```java
@Bean
public PersistableEventMapper persistableEventMapper() {
    return (aggregateId, aggregateTypeConfiguration, event, eventOrder) ->
            PersistableEvent.builder()
                            .setEvent(event)
                            .setAggregateType(aggregateTypeConfiguration.aggregateType)
                            .setAggregateId(aggregateId)
                            .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                            .setEventOrder(eventOrder)
                            // Add your custom metadata:
                            .setEventId(EventId.random())
                            .setTimestamp(OffsetDateTime.now())
                            .setCorrelationId(getCurrentCorrelationId())  // For distributed tracing
                            .setTenant(getCurrentTenantId())              // For multi-tenancy
                            .build();
}
```

---

## Typical Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
        <version>${essentials.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jdbi</groupId>
        <artifactId>jdbi3-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.jdbi</groupId>
        <artifactId>jdbi3-postgres</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>tools.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-postgresql</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```
