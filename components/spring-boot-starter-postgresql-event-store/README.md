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
| `JacksonJSONEventSerializer` | Converts your event objects to/from JSON for database storage                                                                                                                                                      |

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
| `use-event-stream-gap-handler` | `true` | Whether to detect and handle gaps in event sequences (can happen during concurrent writes). Disable only if you don't need strict ordering guarantees                |
| `verbose-tracing` | `false` | When `true`, traces include low-level operations. When `false`, only high-level operations are traced                                                                |
| `add-annotation-based-in-memory-projector` | `true` | Auto-register the projector that supports `@EventHandler` methods on POJOs - See [In-Memory Projections](../eventsourced-aggregates/README.md#in-memory-projections) |
| `auto-flush-and-publish-after-append-to-stream` | `false` | **Flush Publishing** - Publish events immediately after `appendToStream()` instead of waiting for commit. See [Flush Publishing](#flush-publishing)                  |

#### Flush Publishing

Controls *when* in-transaction subscribers receive events. See [postgresql-event-store: Flush Publishing](../postgresql-event-store/README.md#flush-publishing) for detailed documentation.

| Setting | Behavior |
|---------|----------|
| `false` (default) | Events published at `BeforeCommit` and `AfterCommit`. Subscribers receive all events from a transaction together, just before it commits |
| `true` | Events *also* published immediately after each `appendToStream()` call. Use this when subscribers need to react to each event individually within the same transaction (e.g., saga coordination) |

### Subscription Manager Configuration

Controls how the subscription manager polls for and processes events:

```properties
essentials.eventstore.subscription-manager.event-store-polling-batch-size=10
essentials.eventstore.subscription-manager.event-store-polling-interval=100ms
essentials.eventstore.subscription-manager.max-event-store-polling-interval=2000ms
essentials.eventstore.subscription-manager.snapshot-resume-points-every=10s
```

| Property | Default | What It Controls |
|----------|---------|------------------|
| `event-store-polling-batch-size` | `10` | How many events to fetch per poll. Higher = more throughput, but more memory per batch |
| `event-store-polling-interval` | `100ms` | How often to check for new events when events are being processed |
| `max-event-store-polling-interval` | `2000ms` | Maximum wait between polls when no events are found (uses jittered backoff) |
| `snapshot-resume-points-every` | `10s` | How often to save each subscriber's position. Lower = less re-processing after crash, but more database writes |

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
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jdk8</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
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
