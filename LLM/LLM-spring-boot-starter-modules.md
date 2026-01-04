# Spring Boot Starters - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.components.boot.autoconfigure.*`
- Purpose: Auto-configure Essentials components for Spring Boot
- All beans: `@ConditionalOnMissingBean` (override by defining same type)
- **Status**: WORK-IN-PROGRESS

## TOC
- [Starter Selection](#starter-selection)
- [Auto-Configured Beans](#auto-configured-beans)
  - [PostgreSQL Starter](#postgresql-starter)
  - [MongoDB Starter](#mongodb-starter)
  - [Event Store Starter](#event-store-starter)
  - [Admin UI Starter](#admin-ui-starter)
- [Configuration Properties](#configuration-properties)
  - [PostgreSQL Starter](#postgresql-starter-1)
  - [MongoDB Starter](#mongodb-starter-1)
  - [Event Store Starter](#event-store-starter-1)
  - [Admin UI Starter](#admin-ui-starter-1)
- [Common Patterns](#common-patterns)
  - [DurableLocalCommandBus Customization](#durablelocalcommandbus-customization)
  - [JdbiConfigurationCallback](#jdbiconfigurationcallback-postgresqlevent-store)
  - [MongoDB Custom Converters](#mongodb-custom-converters)
  - [Event Store PersistableEventMapper](#event-store-persistableeventmapper)
  - [Admin UI Authentication](#admin-ui-authentication)
- [Security](#security)
- [Dependencies](#dependencies)
- [Gotchas](#gotchas)
- [See Also](#see-also)

## Starter Selection

| Starter | Includes | Adds | Artifact |
|---------|----------|------|----------|
| PostgreSQL | - | Jdbi, FencedLock, Queues, Inbox/Outbox, Scheduler | `spring-boot-starter-postgresql` |
| MongoDB | - | MongoTemplate, FencedLock, Queues, Inbox/Outbox | `spring-boot-starter-mongodb` |
| Event Store | PostgreSQL | EventStore, Subscriptions, EventProcessors | `spring-boot-starter-postgresql-event-store` |
| Admin UI | Event Store | Vaadin admin views | `spring-boot-starter-admin-ui` |

**Use Cases:**
- **PostgreSQL**: Microservices + PostgreSQL, not event-sourced
- **MongoDB**: Microservices + MongoDB, not event-sourced
- **Event Store**: Event-sourced apps + PostgreSQL
- **Admin UI**: Monitoring/management web UI (requires Vaadin)

## Auto-Configured Beans

### PostgreSQL Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.postgresql`

#### Core Infrastructure
| Bean | Type | Package |
|------|------|---------|
| `Jdbi` | Database | `org.jdbi.v3.core` |
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | Transaction | `dk.trustworks.essentials.components.foundation.transaction.jdbi` |

**Note:** `Jdbi` configured with:
- `PostgresPlugin`
- `TransactionAwareDataSourceProxy` (joins JDBI with Spring `@Transactional`)

**Conditional:** `UnitOfWorkFactory` only if `SpringTransactionAwareEventStoreUnitOfWorkFactory` NOT on classpath

#### Components
| Bean | Type | Package | Config Prefix |
|------|------|---------|---------------|
| `PostgresqlFencedLockManager` | Lock | `dk.trustworks.essentials.components.distributed.fencedlock.postgresql` | `essentials.fenced-lock-manager` |
| `PostgresqlDurableQueues` | Queue | `dk.trustworks.essentials.components.queue.postgresql` | `essentials.durable-queues` |
| `PostgresqlDurableQueuesStatistics` | Stats | `dk.trustworks.essentials.components.queue.postgresql` | When `enable-queue-statistics=true` |
| `Inboxes` | Pattern | `dk.trustworks.essentials.components.foundation.inbox` | - |
| `Outboxes` | Pattern | `dk.trustworks.essentials.components.foundation.outbox` | - |
| `DurableLocalCommandBus` | Command | `dk.trustworks.essentials.components.foundation.messaging` | - |
| `MultiTableChangeListener` | Optimize | `dk.trustworks.essentials.components.foundation.postgresql.notifications` | `essentials.multi-table-change-listener` |

#### Reactive & Events
| Bean | Package | Config | Conditional |
|------|---------|--------|-------------|
| `LocalEventBus` | `dk.trustworks.essentials.reactive` | `essentials.reactive` | NOT if `EventStoreEventBus` on classpath |
| `ReactiveHandlersBeanPostProcessor` | `dk.trustworks.essentials.components.boot.autoconfigure.postgresql` | - | - |

#### Scheduler
| Bean | Package | Condition | Config |
|------|---------|-----------|--------|
| `EssentialsScheduler` | `dk.trustworks.essentials.components.foundation.scheduling` | `scheduler.enabled=true` | `essentials.scheduler` |
| `PostgresqlTTLManager` | `dk.trustworks.essentials.components.foundation.postgresql.ttl` | `EssentialsScheduler` exists | - |
| `TTLJobBeanPostProcessor` | `dk.trustworks.essentials.components.boot.autoconfigure.postgresql` | `PostgresqlTTLManager` exists | - |

#### Serialization
| Bean | Package | Condition |
|------|---------|-----------|
| `EssentialTypesJacksonModule` | `dk.trustworks.essentials.jackson.types` | Always |
| `EssentialsImmutableJacksonModule` | `dk.trustworks.essentials.jackson.immutable` | Objenesis + `immutable-jackson-module-enabled=true` |
| `JacksonJSONSerializer` | `dk.trustworks.essentials.components.foundation.json` | NOT if `JSONEventSerializer` on classpath |

#### Observability
| Bean | Package | Condition |
|------|---------|-----------|
| `DurableQueuesMicrometerTracingInterceptor` | `dk.trustworks.essentials.components.foundation.interceptor.micrometer` | `management.tracing.enabled=true` |
| `DurableQueuesMicrometerInterceptor` | `dk.trustworks.essentials.components.foundation.interceptor.micrometer` | `management.tracing.enabled=true` |
| `RecordExecutionTimeMessageHandlerInterceptor` | `dk.trustworks.essentials.components.foundation.interceptor.micrometer` | Always |
| `RecordExecutionTimeCommandBusInterceptor` | `dk.trustworks.essentials.components.foundation.interceptor.micrometer` | Always |
| `RecordExecutionTimeDurableQueueInterceptor` | `dk.trustworks.essentials.components.foundation.interceptor.micrometer` | Always |

#### Admin APIs
| Bean | Package | Purpose |
|------|---------|---------|
| `DBFencedLockApi` | `dk.trustworks.essentials.components.foundation.fencedlock.api` | Lock management |
| `DurableQueuesApi` | `dk.trustworks.essentials.components.foundation.queue.api` | Queue management |
| `PostgresqlQueryStatisticsApi` | `dk.trustworks.essentials.components.foundation.postgresql.api` | DB diagnostics |
| `SchedulerApi` | `dk.trustworks.essentials.components.foundation.scheduling.api` | Scheduler management |

#### Lifecycle
| Bean | Package |
|------|---------|
| `DefaultLifecycleManager` | `dk.trustworks.essentials.components.foundation.lifecycle` |

### MongoDB Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.mongodb`

#### Core Infrastructure
| Bean | Type | Package |
|------|------|---------|
| `MongoTransactionManager` | Transaction | `org.springframework.data.mongodb` |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | Transaction | `dk.trustworks.essentials.components.foundation.transaction.spring.mongo` |

**`MongoTransactionManager` config:**
- `ReadConcern.SNAPSHOT`
- `WriteConcern.ACKNOWLEDGED`

#### Components
Same as PostgreSQL but MongoDB implementations:

| Bean | Package |
|------|---------|
| `MongoFencedLockManager` | `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo` |
| `MongoDurableQueues` | `dk.trustworks.essentials.components.queue.springdata.mongo` |

#### MongoDB Integration
| Bean | Package | Purpose |
|------|---------|---------|
| `SingleValueTypeRandomIdGenerator` | `dk.trustworks.essentials.types.springdata.mongo` | Generate IDs for `SingleValueType` @Id fields |
| `MongoCustomConversions` | `org.springframework.data.mongodb.core.convert` | Convert `LockName`, `QueueEntryId`, `QueueName` |

**Note:** Reactive, serialization, observability same as PostgreSQL

### Event Store Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore`

**Includes:** All PostgreSQL starter beans

#### Event Store Core
| Bean | Package | Purpose |
|------|---------|---------|
| `PostgresqlEventStore` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql` | Append/load events |
| `SeparateTablePerAggregateTypePersistenceStrategy` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence` | One table per `AggregateType` |
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring` | Spring transaction integration |
| `JacksonJSONEventSerializer` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json` | Event JSON serialization |

#### Subscriptions & Processing
| Bean | Package | Purpose |
|------|---------|---------|
| `EventStoreSubscriptionManager` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription` | Coordinate subscriptions |
| `PostgresqlDurableSubscriptionRepository` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription` | Resume point persistence |
| `EventProcessorDependencies` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor` | Bundle for `EventProcessor`/`InTransactionEventProcessor` |
| `ViewEventProcessorDependencies` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor` | Bundle for `ViewEventProcessor` |
| `AnnotationBasedInMemoryProjector` | `dk.trustworks.essentials.components.eventsourced.aggregates.modern.inmemory` | `@EventHandler` projection support |

#### Event Publishing
| Bean | Package | Purpose |
|------|---------|---------|
| `EventStoreEventBus` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus` | Publish events locally |
| `PersistableEventMapper` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence` | Add metadata to events |

#### Observability
| Bean | Package | Condition |
|------|---------|-----------|
| `MicrometerTracingEventStoreInterceptor` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer` | `management.tracing.enabled=true` |
| `RecordExecutionTimeEventStoreInterceptor` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer` | Always |
| `MeasurementEventStoreSubscriptionObserver` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer` | Always |
| `EventStoreSubscriptionMonitorManager` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription` | Always (runs every 1m by default) |
| `SubscriberGlobalOrderMicrometerMonitor` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer` | `management.tracing.enabled=true` |

#### Admin APIs
| Bean | Package | Purpose |
|------|---------|---------|
| `EventStoreApi` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api` | Query events, manage subscriptions |
| `PostgresqlEventStoreStatisticsApi` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api` | Event store statistics |

### Admin UI Starter

Package: `dk.trustworks.essentials.components.boot.autoconfigure.adminui`

**Includes:** All Event Store starter beans

#### Auto-Configuration
```java
@AutoConfiguration
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan("dk.trustworks.essentials.ui")
public class EssentialsAdminUIAutoConfiguration
```

#### Views
Package: `dk.trustworks.essentials.ui`

| View Class | Route | Required Role |
|------------|-------|---------------|
| `AdminView` | `/` | Authenticated |
| `LocksView` | `/locks` | `LOCK_READER` |
| `QueuesView` | `/queues` | `QUEUE_READER` |
| `SubscriptionsView` | `/subscriptions` | `SUBSCRIPTION_READER` |
| `EventProcessorsView` | `/eventprocessors` | `SUBSCRIPTION_READER` |
| `PostgresqlStatisticsView` | `/postgresql` | `POSTGRESQL_STATS_READER` |
| `SchedulerView` | `/scheduler` | `SCHEDULER_READER` |
| `AdminLoginView` | `/login` | Public |
| `AccessDeniedView` | `/access-denied` | Public |

**Required:** Implement `EssentialsAuthenticatedUser` (`dk.trustworks.essentials.shared.security`) bean

## Configuration Properties

### PostgreSQL Starter

#### FencedLock
Prefix: `essentials.fenced-lock-manager`

| Property | Default | Notes |
|----------|---------|-------|
| `fenced-locks-table-name` | `fenced_locks` | ⚠️ Validated, not injection-proof |
| `lock-time-out` | `15s` | Must be > `lock-confirmation-interval` |
| `lock-confirmation-interval` | `4s` | Heartbeat frequency |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Local release on I/O error |

#### DurableQueues
Prefix: `essentials.durable-queues`

| Property | Default | Notes                                   |
|----------|---------|-----------------------------------------|
| `shared-queue-table-name` | `durable_queues` | ⚠️ Validated, not injection-proof       |
| `transactional-mode` | `single-operation-transaction` | **Use this**, not `fully-transactional` |
| `message-handling-timeout` | `30s` | Single-op mode only                     |
| `use-centralized-message-fetcher` | `true` | Recommended                             |
| `centralized-message-fetcher-polling-interval` | `20ms` | Base interval                           |
| `centralized-polling-delay-back-off-factor` | `1.5` | Backoff multiplier                      |
| `use-ordered-unordered-query` | `true` | Optimize mixed ordering                 |
| `polling-delay-interval-increment-factor` | `0.5` | Legacy (centralized=false)              |
| `max-polling-interval` | `2s` | Max backoff                             |
| `verbose-tracing` | `false` | Include all ops in traces               |
| `enable-queue-statistics` | `false` | Collect statistics                      |
| `shared-queue-statistics-table-name` | `durable_queues_statistics` | Stats table - ⚠️ Validated, not injection-proof  |
| `enable-queue-statistics-ttl` | `false` | Auto-cleanup stats                      |
| `queue-statistics-ttl-duration` | `90` | Days                                    |

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
| `life-cycles.start-life-cycles` | `true` | Auto-start `Lifecycle` beans |
| `reactive-bean-post-processor-enabled` | `true` | Auto-register handlers |
| `immutable-jackson-module-enabled` | `true` | Enable immutable deserialization |

### MongoDB Starter

#### FencedLock
Prefix: `essentials.fenced-lock-manager`

| Property | Default        | Notes |
|----------|----------------|-------|
| `fenced-locks-collection-name` | `fenced_locks` | ⚠️ Validated, not injection-proof |
| `lock-time-out` | `15s`          | Must be > `lock-confirmation-interval` |
| `lock-confirmation-interval` | `4s`           | Heartbeat frequency |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false`        | Local release on I/O error |

#### DurableQueues
Prefix: `essentials.durable-queues`

| Property | Default                        | Notes |
|----------|--------------------------------|-------|
| `shared-queue-collection-name` | `durable_queues`               | ⚠️ Validated, not injection-proof |
| `transactional-mode` | `single-operation-transaction` | **Use this** |
| `message-handling-timeout` | `30s`                          | Single-op mode only |
| `polling-delay-interval-increment-factor` | `0.5`                          | Backoff factor |
| `max-polling-interval` | `2s`                           | Max backoff |
| `verbose-tracing` | `false`                        | Include all ops in traces |

**Note:** No centralized message fetcher (PostgreSQL-only feature)

**Other config:** Same EventBus, Metrics, Lifecycle as PostgreSQL

### Event Store Starter

**Includes:** All PostgreSQL starter config

#### Event Store
Prefix: `essentials.eventstore`

| Property | Default | Notes                                                      |
|----------|---------|------------------------------------------------------------|
| `identifier-column-type` | `text` | `text` or `uuid`                                           |
| `json-column-type` | `jsonb` | `jsonb` (queryable) or `json` (faster writes)              |
| `use-event-stream-gap-handler` | `true` | Detect sequence gaps                                       |
| `verbose-tracing` | `false` | Low-level ops in traces                                    |
| `add-annotation-based-in-memory-projector` | `true` | Enable `@EventHandler` projections                         |
| `auto-flush-and-publish-after-append-to-stream` | `false` | Flush Publishing: Immediate publish (for processors/views) |

**Flush Publishing:**
- `false`: Events published at `BeforeCommit`/`AfterCommit` (batch)
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
1. Implement `EssentialsAuthenticatedUser` (`dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser`) bean
2. Configure Spring Security with `VaadinWebSecurity`
3. Add Vaadin + Spring Security dependencies

## Common Patterns

### DurableLocalCommandBus Customization

```java
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

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

The components allow customization of table/column/index/function/collection-names that are used with **String concatenation** → SQL/NoSQL injection risk. 
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

### Bean Override

All beans use `@ConditionalOnMissingBean`. Override by defining same type:

```java
@Bean
public PostgresqlDurableQueues postgresqlDurableQueues(...) {
    // Your implementation
}
```

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

## Gotchas

- ⚠️ **Transactional Mode**: Use `single-operation-transaction` for reliable retry/DLQ (fully-transactional breaks retries)
- ⚠️ **Bean Conditionals**: Event Store provides own `UnitOfWorkFactory`, `EventBus`, `JSONSerializer` (PostgreSQL starter skips these when EventStore on classpath)
- ⚠️ **Lifecycle Start**: Set `start-life-cycles=false` to manually control lifecycle
- ⚠️ **MongoDB CharSequenceTypes**: Must register types using ObjectId values or used as Map keys
- ⚠️ **Flush Publishing**: Enable only if sagas need per-event coordination (impacts transaction semantics)
- ⚠️ **Queue Statistics**: Extra DB overhead when enabled, use TTL to prevent unbounded growth
- ⚠️ **Admin UI**: Requires both `EssentialsAuthenticatedUser` implementation AND Spring Security config (not auto-configured)

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
