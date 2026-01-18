# Spring Boot Starters - LLM Reference

> Quick reference for LLMs. For detailed explanations, see module README files.

## Quick Facts
- Package: `dk.trustworks.essentials.components.boot.autoconfigure.*`
- Purpose: Auto-configure Essentials components for Spring Boot
- All beans: `@ConditionalOnMissingBean` (override by defining same type)

**Dependencies from other modules**:
- `FencedLockManager`, `DurableQueues`, `UnitOfWorkFactory` from [foundation](./LLM-foundation.md)
- `PostgresqlFencedLockManager` from [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md)
- `PostgresqlDurableQueues` from [postgresql-queue](./LLM-postgresql-queue.md)
- `EventStore`, `ConfigurableEventStore` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `MongoFencedLockManager` from [springdata-mongo-distributed-fenced-lock](./LLM-springdata-mongo-distributed-fenced-lock.md)
- `MongoDurableQueues` from [springdata-mongo-queue](./LLM-springdata-mongo-queue.md)

## TOC
- [Starter Selection](#starter-selection)
- [Auto-Configured Beans](#auto-configured-beans)
- [Configuration Properties](#configuration-properties)
- [Common Patterns](#common-patterns)
- ⚠️ [Security](#security)
- [Dependencies](#dependencies)
- [Gotchas](#gotchas)
- [See Also](#see-also)

## Starter Selection

| Starter | Artifact | Use Case |
|---------|----------|----------|
| PostgreSQL | `spring-boot-starter-postgresql` | Microservices + PostgreSQL, not event-sourced |
| MongoDB | `spring-boot-starter-mongodb` | Microservices + MongoDB, not event-sourced |
| Event Store | `spring-boot-starter-postgresql-event-store` | Event-sourced apps + PostgreSQL |
| Admin UI | `spring-boot-starter-admin-ui` | Monitoring/management web UI (requires Vaadin) |

**Starter Relationships:**
- Event Store includes PostgreSQL starter transitively
- Admin UI includes Event Store starter transitively

---

## Auto-Configured Beans

### PostgreSQL Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.postgresql`

See [spring-boot-starter-postgresql README](../components/spring-boot-starter-postgresql/README.md#auto-configured-beans) for full list.

**Core Infrastructure:**
- `Jdbi` - JDBI with PostgresPlugin + TransactionAwareDataSourceProxy
- `SpringTransactionAwareJdbiUnitOfWorkFactory` - Spring transaction integration

**Components:**
- `PostgresqlFencedLockManager` - Distributed locks
- `PostgresqlDurableQueues` - Durable message queuing
- `PostgresqlDurableQueuesStatistics` - Queue statistics (when enabled)
- `Inboxes`, `Outboxes` - Store-and-forward patterns
- `DurableLocalCommandBus` - Command bus with durable delivery
- `MultiTableChangeListener` - PostgreSQL NOTIFY/LISTEN optimization

**Reactive & Events:**
- `LocalEventBus` - Event bus (when EventStoreEventBus not on classpath)
- `ReactiveHandlersBeanPostProcessor` - Auto-register handlers

**Serialization:**
- `EssentialTypesJacksonModule` - Jackson support for types
- `EssentialsImmutableJacksonModule` - Immutable support (when enabled)
- `JacksonJSONSerializer` - JSON serializer (when JSONEventSerializer not on classpath)

**Scheduler:**
- `EssentialsScheduler` - Distributed scheduler (when enabled)
- `PostgresqlTTLManager` - Time-to-live management
- `TTLJobBeanPostProcessor` - Auto-register @TTLJob beans

**Observability:**
- Micrometer tracing interceptors (when `management.tracing.enabled=true`)
- Performance logging interceptors

**Admin APIs:**
- `DBFencedLockApi`, `DurableQueuesApi`, `PostgresqlQueryStatisticsApi`, `SchedulerApi`

**Lifecycle:**
- `DefaultLifecycleManager` - Manages Lifecycle beans

### MongoDB Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.mongodb`

See [spring-boot-starter-mongodb README](../components/spring-boot-starter-mongodb/README.md#auto-configured-beans) for full list.

**Core Infrastructure:**
- `MongoTransactionManager` - Transaction manager (ReadConcern.SNAPSHOT, WriteConcern.ACKNOWLEDGED)
- `SpringMongoTransactionAwareUnitOfWorkFactory` - Spring transaction integration

**Components:**
- `MongoFencedLockManager` - Distributed locks
- `MongoDurableQueues` - Durable message queuing

**MongoDB Integration:**
- `SingleValueTypeRandomIdGenerator` - ID generation for SingleValueType @Id fields
- `MongoCustomConversions` - Converters for LockName, QueueEntryId, QueueName

**Other:** Same reactive, serialization, observability as PostgreSQL starter

### Event Store Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore`

See [spring-boot-starter-postgresql-event-store README](../components/spring-boot-starter-postgresql-event-store/README.md#auto-configured-beans) for full list.

**Includes:** All PostgreSQL starter beans

**Event Store Core:**
- `PostgresqlEventStore` - Event persistence and loading
- `SeparateTablePerAggregateTypePersistenceStrategy` - One table per AggregateType
- `SpringTransactionAwareEventStoreUnitOfWorkFactory` - Spring transaction integration
- `JacksonJSONEventSerializer` - Event JSON serialization

**Subscriptions & Processing:**
- `EventStoreSubscriptionManager` - Coordinate subscriptions
- `PostgresqlDurableSubscriptionRepository` - Resume point persistence
- `EventProcessorDependencies` - Dependencies bundle for EventProcessor/InTransactionEventProcessor
- `ViewEventProcessorDependencies` - Dependencies bundle for ViewEventProcessor
- `AnnotationBasedInMemoryProjector` - @EventHandler projection support

**Event Publishing:**
- `EventStoreEventBus` - Local event publishing
- `PersistableEventMapper` - Event metadata mapping

**Observability:**
- `MicrometerTracingEventStoreInterceptor` - Distributed tracing (when enabled)
- `RecordExecutionTimeEventStoreInterceptor` - Performance logging
- `MeasurementEventStoreSubscriptionObserver` - Subscription metrics
- `EventStoreSubscriptionMonitorManager` - Subscription health monitoring (runs every 1m by default)
- `SubscriberGlobalOrderMicrometerMonitor` - Micrometer gauge for subscriber position

**Admin APIs:**
- `EventStoreApi` - Query events, manage subscriptions
- `PostgresqlEventStoreStatisticsApi` - Event store statistics

### Admin UI Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.adminui`

**Includes:** All Event Store starter beans

**Auto-Configuration:**
```java
@AutoConfiguration
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan("dk.trustworks.essentials.ui")
public class EssentialsAdminUIAutoConfiguration
```

**Views:** See [spring-boot-starter-admin-ui README](../components/spring-boot-starter-admin-ui/README.md) for full list

**Required:** Implement `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` bean

---

## Configuration Properties

### PostgreSQL Starter

See [spring-boot-starter-postgresql README](../components/spring-boot-starter-postgresql/README.md#configuration-properties) for complete documentation.

#### FencedLock

Prefix: `essentials.fenced-lock-manager`

| Property | Default | Notes |
|----------|---------|-------|
| `fenced-locks-table-name` | `fenced_locks` | Table name - see [Security](#security) |
| `lock-time-out` | `15s` | Must be > `lock-confirmation-interval` |
| `lock-confirmation-interval` | `4s` | Heartbeat frequency |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Local release on I/O error |

#### DurableQueues

Prefix: `essentials.durable-queues`

| Property | Default | Notes |
|----------|---------|-------|
| `shared-queue-table-name` | `durable_queues` | Table name - see [Security](#security) |
| `transactional-mode` | `single-operation-transaction` | **Use this**, not `fully-transactional` |
| `message-handling-timeout` | `30s` | Single-op mode only |
| `use-centralized-message-fetcher` | `true` | Recommended |
| `centralized-message-fetcher-polling-interval` | `20ms` | Base interval |
| `centralized-polling-delay-back-off-factor` | `1.5` | Backoff multiplier |
| `use-ordered-unordered-query` | `true` | Optimize mixed ordering |
| `polling-delay-interval-increment-factor` | `0.5` | Legacy (centralized=false) |
| `max-polling-interval` | `2s` | Max backoff |
| `verbose-tracing` | `false` | Include all ops in traces |
| `enable-queue-statistics` | `false` | Collect statistics |
| `shared-queue-statistics-table-name` | `durable_queues_statistics` | Stats table - see [Security](#security) |
| `enable-queue-statistics-ttl` | `false` | Auto-cleanup stats |
| `queue-statistics-ttl-duration` | `90` | Days |

**Transactional Modes:**
- `single-operation-transaction`: Queue ops outside transaction, timeout-based ack (RECOMMENDED)
- `fully-transactional`: Queue ops in transaction (breaks retries/DLQ - don't use)

#### MultiTableChangeListener

Prefix: `essentials.multi-table-change-listener`

| Property | Default |
|----------|---------|
| `polling-interval` | `50ms` |
| `filter-duplicate-notifications` | `true` |

#### EventBus

Prefix: `essentials.reactive`

| Property | Default |
|----------|---------|
| `event-bus-backpressure-buffer-size` | `1024` |
| `event-bus-parallel-threads` | min(processors, 4) |
| `overflow-max-retries` | `20` |
| `queued-task-cap-factor` | `1.5` |
| `command-bus-parallel-send-and-dont-wait-consumers` | min(processors, 4) |

#### Scheduler

Prefix: `essentials.scheduler`

| Property | Default |
|----------|---------|
| `enabled` | `true` |
| `number-of-threads` | min(processors, 4) |

#### Metrics

Prefix: `essentials.metrics`

```yaml
essentials:
  metrics:
    durable-queues:
      enabled: true
      thresholds:
        debug: 25ms
        info: 200ms
        warn: 500ms
        error: 5000ms
    command-bus:
      enabled: true
      thresholds: [same as above]
    message-handler:
      enabled: true
      thresholds: [same as above]
    sql:
      enabled: false
      thresholds: [same as above]
```

**Logger Classes:**
- `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeDurableQueueInterceptor`
- `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeCommandBusInterceptor`
- `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeMessageHandlerInterceptor`
- `dk.trustworks.essentials.components.foundation.postgresql.micrometer.RecordSqlExecutionTimeLogger`

#### Micrometer Tagging

Prefix: `essentials.tracing-properties`

| Property | Default | Notes |
|----------|---------|-------|
| `module-tag` | `null` | Tag value for 'module' in metrics |

#### Lifecycle

Prefix: `essentials`

| Property | Default | Effect |
|----------|---------|--------|
| `life-cycles.start-life-cycles` | `true` | Auto-start Lifecycle beans |
| `reactive-bean-post-processor-enabled` | `true` | Auto-register handlers |
| `immutable-jackson-module-enabled` | `true` | Enable immutable deserialization |

### MongoDB Starter

See [spring-boot-starter-mongodb README](../components/spring-boot-starter-mongodb/README.md#configuration-properties) for complete documentation.

#### FencedLock

Prefix: `essentials.fenced-lock-manager`

| Property | Default | Notes |
|----------|---------|-------|
| `fenced-locks-collection-name` | `fenced_locks` | Collection name - see [Security](#security) |
| `lock-time-out` | `12s` | Must be > `lock-confirmation-interval` |
| `lock-confirmation-interval` | `5s` | Heartbeat frequency |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Local release on I/O error |

#### DurableQueues

Prefix: `essentials.durable-queues`

| Property | Default | Notes |
|----------|---------|-------|
| `shared-queue-collection-name` | `durable_queues` | Collection name - see [Security](#security) |
| `transactional-mode` | `single-operation-transaction` | **Use this**, not `fully-transactional` |
| `message-handling-timeout` | `30s` | Single-op mode only |
| `polling-delay-interval-increment-factor` | `0.5` | Backoff factor |
| `max-polling-interval` | `2s` | Max backoff |
| `verbose-tracing` | `false` | Include all ops in traces |

**Note:** No centralized message fetcher (PostgreSQL-only feature)

**Other config:** Same EventBus, Metrics, Lifecycle as PostgreSQL

### Event Store Starter

See [spring-boot-starter-postgresql-event-store README](../components/spring-boot-starter-postgresql-event-store/README.md#configuration-properties) for complete documentation.

**Includes:** All PostgreSQL starter config

#### Event Store

Prefix: `essentials.eventstore`

| Property | Default | Notes |
|----------|---------|-------|
| `identifier-column-type` | `text` | `text` or `uuid` |
| `json-column-type` | `jsonb` | `jsonb` (queryable) or `json` (faster writes) |
| `use-event-stream-gap-handler` | `true` | Detect sequence gaps |
| `verbose-tracing` | `false` | Low-level ops in traces |
| `add-annotation-based-in-memory-projector` | `true` | Enable @EventHandler projections |
| `auto-flush-and-publish-after-append-to-stream` | `false` | Flush Publishing: Immediate publish (for processors/views) |

**Flush Publishing:**
- `false`: Events published at BeforeCommit/AfterCommit (batch)
- `true`: Also published immediately after `appendToStream()` (individual)

See [postgresql-event-store: Flush Publishing](../components/postgresql-event-store/README.md#flush-publishing)

#### Subscription Manager

Prefix: `essentials.eventstore.subscription-manager`

| Property | Default | Notes |
|----------|---------|-------|
| `event-store-polling-batch-size` | `10` | Events per poll |
| `event-store-polling-interval` | `100ms` | When processing events |
| `max-event-store-polling-interval` | `2000ms` | Max backoff when idle |
| `snapshot-resume-points-every` | `10s` | Save position frequency |

#### Subscription Monitor

Prefix: `essentials.eventstore.subscription-monitor`

| Property | Default |
|----------|---------|
| `enabled` | `true` |
| `interval` | `1m` |

#### Event Store Metrics

Prefix: `essentials.eventstore`

```yaml
essentials:
  eventstore:
    metrics:
      enabled: true
      thresholds:
        debug: 25ms
        info: 200ms
        warn: 500ms
        error: 5000ms
    subscription-manager:
      metrics:
        enabled: true
        thresholds: [same as above]
```

**Logger Classes:**
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor`
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver`

### Admin UI Starter

**No additional config** - uses Event Store starter config

**Required Setup:**
1. Implement `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` bean
2. Configure Spring Security with `VaadinWebSecurity`
3. Add Vaadin + Spring Security dependencies

---

## Common Patterns

### DurableLocalCommandBus Customization

```java
// Package: dk.trustworks.essentials.components.foundation.messaging
@Bean
RedeliveryPolicy durableLocalCommandBusRedeliveryPolicy() {
    return RedeliveryPolicy.exponentialBackoff()
            .setInitialRedeliveryDelay(Duration.ofMillis(200))
            .setFollowupRedeliveryDelay(Duration.ofMillis(200))
            .setFollowupRedeliveryDelayMultiplier(1.1d)
            .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(3))
            .setMaximumNumberOfRedeliveries(20)
            .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(
                    ConstraintViolationException.class,
                    HttpClientErrorException.BadRequest.class))
            .build();
}

@Bean
SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler() {
    return (exception, commandMessage, commandHandler) -> {
        if (exception instanceof HttpClientErrorException.Unauthorized) {
            log.error("Unauthorized - not retrying", exception);
            // Don't rethrow = no retry, no DLQ
        } else {
            Exceptions.sneakyThrow(exception);  // Rethrow = retry/DLQ
        }
    };
}
```

Classes:
- `dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy`
- `dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryErrorHandler`
- `dk.trustworks.essentials.components.foundation.messaging.queue.SendAndDontWaitErrorHandler`

### JdbiConfigurationCallback (PostgreSQL/Event Store)

```java
// Package: dk.trustworks.essentials.components.foundation.postgresql
@Component
public class MyJdbiCustomizer implements JdbiConfigurationCallback {
    @Override
    public void configure(Jdbi jdbi) {
        jdbi.registerArgument(new MyCustomArgumentFactory());
        jdbi.registerRowMapper(new MyCustomRowMapper());
    }
}
```

Interface: `dk.trustworks.essentials.components.foundation.postgresql.JdbiConfigurationCallback`

Called **before** `Lifecycle.start()`

### MongoDB Custom Converters

```java
// Package: dk.trustworks.essentials.components.boot.autoconfigure.mongodb

// For CharSequenceTypes using ObjectId values / Map keys using ObjectId values
@Bean
AdditionalCharSequenceTypesSupported additionalCharSequenceTypesSupported() {
    return new AdditionalCharSequenceTypesSupported(ProductId.class, OrderId.class);
}

// For other custom types
@Bean
AdditionalConverters additionalConverters() {
    return new AdditionalConverters(
            Jsr310Converters.StringToDurationConverter.INSTANCE,
            Jsr310Converters.DurationToStringConverter.INSTANCE
    );
}
```

Classes:
- `dk.trustworks.essentials.components.boot.autoconfigure.mongodb.AdditionalCharSequenceTypesSupported`
- `dk.trustworks.essentials.components.boot.autoconfigure.mongodb.AdditionalConverters`

**When to register `CharSequenceType`:**
1. Uses `ObjectId` values (e.g., `random()` uses `ObjectId.get().toString()`)
2. Used as Map keys (MongoDB stores as `ObjectId` internally)

See [types-springdata-mongo](../types-springdata-mongo/README.md)

### Event Store PersistableEventMapper

```java
// Package: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence
@Bean
public PersistableEventMapper persistableEventMapper() {
    return (aggregateId, aggregateTypeConfig, event, eventOrder) ->
            PersistableEvent.builder()
                            .setEvent(event)
                            .setAggregateType(aggregateTypeConfig.aggregateType)
                            .setAggregateId(aggregateId)
                            .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                            .setEventOrder(eventOrder)
                            .setEventId(EventId.random())
                            .setTimestamp(OffsetDateTime.now())
                            .setCorrelationId(getCurrentCorrelationId())  // Custom
                            .setTenant(getCurrentTenantId())              // Custom
                            .build();
}
```

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper`

Override to add correlation IDs, tenant IDs, etc.

### Admin UI Authentication

```java
// Package: dk.trustworks.essentials.shared.security
@Component
public class SpringSecurityAuthenticatedUser implements EssentialsAuthenticatedUser {
    private final AuthenticationContext authContext;

    @Override
    public boolean hasAdminRole() {
        return authContext.hasRole(EssentialsSecurityRoles.ESSENTIALS_ADMIN.getRoleName());
    }

    @Override
    public boolean hasLockReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.LOCK_READER.getRoleName());
    }

    // Implement other role checks...

    @Override
    public void logout() {
        authContext.logout();
    }
}

@Configuration
@EnableWebSecurity
public class SecurityConfig extends VaadinWebSecurity {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        setLoginView(http, AdminLoginView.class);
    }
}
```

Interface: `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser`
Enum: `dk.trustworks.essentials.shared.security.EssentialsSecurityRoles`

**Security Roles:**
- `ESSENTIALS_ADMIN` - Full access
- `LOCK_READER` / `LOCK_WRITER`
- `QUEUE_READER` / `QUEUE_PAYLOAD_READER` / `QUEUE_WRITER`
- `SUBSCRIPTION_READER` / `SUBSCRIPTION_WRITER`
- `POSTGRESQL_STATS_READER`
- `SCHEDULER_READER`

---

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection-names that are used with **String concatenation** → SQL/NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL/NoSQL injection.

### Table/Collection Name Validation

⚠️ **Warning:** Names validated but NOT fully injection-proof

**PostgreSQL:** `dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil.checkIsValidTableOrColumnName()`
**MongoDB:** `dk.trustworks.essentials.components.foundation.mongodb.MongoUtil.checkIsValidCollectionName()`

**Developer Responsibilities:**
- Only use trusted, controlled sources
- NEVER accept external/untrusted input
- Implement additional validation
- Regular security audits

**Affected Properties:**
- `fenced-locks-table-name` / `fenced-locks-collection-name`
- `shared-queue-table-name` / `shared-queue-collection-name`
- `shared-queue-statistics-table-name`
- All custom table/column/function/index and collection names
- All custom `AggregateType` values

### What Validation Does NOT Protect Against

#### PostgreSQL
- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

#### MongoDB
- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

### Bean Override

All beans use `@ConditionalOnMissingBean`. Override by defining same type:

```java
@Bean
public PostgresqlDurableQueues postgresqlDurableQueues(...) {
    // Your implementation
}
```

---

## Dependencies

### PostgreSQL Starter
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql</artifactId>
</dependency>
<!-- Required: spring-boot-starter-jdbc, jdbi3-core, jdbi3-postgres, postgresql, jackson-databind, reactor-core -->
```

### MongoDB Starter
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-mongodb</artifactId>
</dependency>
<!-- Required: spring-boot-starter-data-mongodb, jackson-databind, reactor-core -->
```

### Event Store Starter
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
</dependency>
<!-- Includes spring-boot-starter-postgresql transitively -->
```

### Admin UI Starter
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-ui</artifactId>
</dependency>
<!-- Required: vaadin-spring-boot-starter, spring-boot-starter-security -->
```

---

## Gotchas

- ⚠️ **Transactional Mode**: Use `single-operation-transaction` for reliable retry/DLQ (fully-transactional breaks retries)
- ⚠️ **Bean Conditionals**: Event Store provides own `UnitOfWorkFactory`, `EventBus`, `JSONSerializer` (PostgreSQL starter skips these when EventStore on classpath)
- ⚠️ **Lifecycle Start**: Set `start-life-cycles=false` to manually control lifecycle
- ⚠️ **MongoDB CharSequenceTypes**: Must register types using ObjectId values or used as Map keys
- ⚠️ **Flush Publishing**: Enable only if sagas need per-event coordination (impacts transaction semantics)
- ⚠️ **Queue Statistics**: Extra DB overhead when enabled, use TTL to prevent unbounded growth
- ⚠️ **Admin UI**: Requires both `EssentialsAuthenticatedUser` implementation AND Spring Security config (not auto-configured)

---

## See Also

- [foundation](./LLM-foundation.md) - Core patterns (UnitOfWork, FencedLock, DurableQueues, Inbox/Outbox)
- [postgresql-event-store](./LLM-postgresql-event-store.md) - Event store internals
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - Lock implementation
- [postgresql-queue](./LLM-postgresql-queue.md) - Queue implementation
- [springdata-mongo-distributed-fenced-lock](./LLM-springdata-mongo-distributed-fenced-lock.md) - Mongo locks
- [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) - Mongo queues
- [eventsourced-aggregates](./LLM-eventsourced-aggregates.md) - Event-sourced aggregates
- [reactive](./LLM-reactive.md) - LocalEventBus, CommandBus

**README Links:**
- [PostgreSQL Starter](../components/spring-boot-starter-postgresql/README.md)
- [MongoDB Starter](../components/spring-boot-starter-mongodb/README.md)
- [Event Store Starter](../components/spring-boot-starter-postgresql-event-store/README.md)
- [Admin UI Starter](../components/spring-boot-starter-admin-ui/README.md)
