# Essentials Components - Spring Boot Starter: PostgreSQL

> **NOTE:** **The library is WORK-IN-PROGRESS**

Spring Boot auto-configuration for all PostgreSQL-focused Essentials components.

**LLM Context:** [LLM-spring-boot-starter-modules.md](../../LLM/LLM-spring-boot-starter-modules.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Auto-Configured Beans](#auto-configured-beans)
- [Configuration Properties](#configuration-properties)
  - [FencedLock Configuration](#fencedlock-configuration)
  - [DurableQueues Configuration](#durablequeues-configuration)
  - [MultiTableChangeListener Configuration](#multitablechangelistener-configuration)
  - [EventBus Configuration](#eventbus-configuration)
  - [Scheduler Configuration](#scheduler-configuration)
  - [Metrics Configuration](#metrics-configuration)
  - [Lifecycle Configuration](#lifecycle-configuration)
- [DurableLocalCommandBus Customization](#durablelocalcommandbus-customization)
- [JdbiConfigurationCallback](#jdbiconfigurationcallback)
- [Typical Dependencies](#typical-dependencies)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

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

All beans use `@ConditionalOnMissingBean` for easy overriding.

### Database & Transactions

| Bean | Description |
|------|-------------|
| `Jdbi` | JDBI instance with PostgreSQL plugin and `TransactionAwareDataSourceProxy` |
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | Joins `UnitOfWork`s with Spring-managed transactions. See [UnitOfWork documentation](../foundation/README.md#unitofwork-transactions) |

> **Why `TransactionAwareDataSourceProxy`?**  
> JDBI normally manages its own connections independently from Spring. By wrapping the spring provided `DataSource` with `TransactionAwareDataSourceProxy`, JDBI operations automatically participate in Spring-managed transactions (e.g., `@Transactional` methods). This ensures that JDBI and Spring share the same database connection and transaction context, enabling consistent transactional behavior across your application.

> **Note:** The `UnitOfWorkFactory` bean is only auto-registered if `SpringTransactionAwareEventStoreUnitOfWorkFactory` is NOT on the classpath.  
> If you're using `spring-boot-starter-postgresql-event-store`, it provides its own specialized `UnitOfWorkFactory`.

### Distributed Locking

| Bean | Description |
|------|-------------|
| `PostgresqlFencedLockManager` | Distributed fenced locks via PostgreSQL. See [FencedLock documentation](../foundation/README.md#fencedlock-distributed-locking) |

### Messaging & Queues

| Bean | Description |
|------|-------------|
| `PostgresqlDurableQueues` | Durable message queuing via PostgreSQL. See [DurableQueues documentation](../foundation/README.md#durablequeues-messaging) |
| `PostgresqlDurableQueuesStatistics` | Queue statistics (when `enable-queue-statistics=true`) |
| `Inboxes` | Store-and-forward for incoming messages. See [Inbox Pattern](../foundation/README.md#inbox-pattern) |
| `Outboxes` | Store-and-forward for outgoing messages. See [Outbox Pattern](../foundation/README.md#outbox-pattern) |
| `DurableLocalCommandBus` | Command bus with durable message delivery. See [DurableLocalCommandBus](../foundation/README.md#durablelocalcommandbus) |
| `MultiTableChangeListener` | PostgreSQL NOTIFY/LISTEN for optimized queue polling |

### Reactive & Event Processing

| Bean | Description |
|------|-------------|
| `LocalEventBus` | Named "default", supports async event handling. See [LocalEventBus](../../reactive/README.md#localeventbus) |
| `ReactiveHandlersBeanPostProcessor` | Auto-registers `EventHandler` and `CommandHandler` beans with their respective buses. See [Spring Integration](../../reactive/README.md#spring-integration) |

> **Note:** The `LocalEventBus` bean is only auto-registered if `EventStoreEventBus` is NOT on the classpath.  
> If you're using `spring-boot-starter-postgresql-event-store`, it provides its own `EventStoreEventBus`.

### Serialization

| Bean | Condition | Description |
|------|-----------|-------------|
| `EssentialTypesJacksonModule` | Always | Jackson support for Essentials semantic types |
| `EssentialsImmutableJacksonModule` | Objenesis on classpath + `essentials.immutable-jackson-module-enabled=true` | Jackson support for immutable objects without default constructor |
| `JacksonJSONSerializer` | `JSONEventSerializer` NOT on classpath | Pre-configured ObjectMapper with sensible defaults |

> **Note:** The `JSONSerializer` bean is only auto-registered if `JSONEventSerializer` is NOT on the classpath.  
> If you're using `spring-boot-starter-postgresql-event-store`, it provides its own serializer.

### Scheduler & TTL

| Bean | Condition | Description |
|------|-----------|-------------|
| `EssentialsScheduler` | `essentials.scheduler.enabled=true` (default) | Distributed task scheduler. See [EssentialsScheduler](../foundation/README.md#essentialsscheduler) |
| `PostgresqlTTLManager` | `EssentialsScheduler` available | Time-to-live management for database records. See [TTLManager](../foundation/README.md#ttlmanager) |
| `TTLJobBeanPostProcessor` | `PostgresqlTTLManager` available | Auto-registers `@TTLJob` annotated beans |

### Observability

| Bean | Condition | Description |
|------|-----------|-------------|
| `DurableQueuesMicrometerTracingInterceptor` | `management.tracing.enabled=true` | Distributed tracing |
| `DurableQueuesMicrometerInterceptor` | `management.tracing.enabled=true` | Micrometer metrics |
| `RecordExecutionTimeMessageHandlerInterceptor` | Always | Performance logging |
| `RecordExecutionTimeCommandBusInterceptor` | Always | Performance logging |
| `RecordExecutionTimeDurableQueueInterceptor` | Always | Performance logging |

### Admin APIs

Service-layer APIs designed to support Admin REST endpoints or Admin UI integrations:

| Bean | Description |
|------|-------------|
| `DBFencedLockApi` | Fenced lock management (list locks, release locks, etc.) |
| `DurableQueuesApi` | Queue management (inspect queues, retry/delete messages, etc.) |
| `PostgresqlQueryStatisticsApi` | PostgreSQL query statistics and diagnostics |
| `SchedulerApi` | Scheduler management (list/pause/resume scheduled tasks) |

### Lifecycle

| Bean | Description |
|------|-------------|
| `DefaultLifecycleManager` | Manages `Lifecycle.start()`/`stop()` for components |

---

## Configuration Properties

### FencedLock Configuration

```properties
essentials.fenced-lock-manager.fenced-locks-table-name=fenced_locks
essentials.fenced-lock-manager.lock-time-out=15s
essentials.fenced-lock-manager.lock-confirmation-interval=4s
essentials.fenced-lock-manager.release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation=false
```

| Property | Default | Description |
|----------|---------|-------------|
| `fenced-locks-table-name` | `fenced_locks` | PostgreSQL table for locks. **See [Security](#security)** |
| `lock-time-out` | `15s` | Duration before unconfirmed lock expires |
| `lock-confirmation-interval` | `4s` | Heartbeat interval (must be < timeout) |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Release locks locally on IO exceptions during confirmation |

### DurableQueues Configuration

```properties
essentials.durable-queues.shared-queue-table-name=durable_queues
essentials.durable-queues.transactional-mode=single-operation-transaction
essentials.durable-queues.message-handling-timeout=30s
essentials.durable-queues.use-centralized-message-fetcher=true
essentials.durable-queues.centralized-message-fetcher-polling-interval=20ms
essentials.durable-queues.centralized-polling-delay-back-off-factor=1.5
essentials.durable-queues.use-ordered-unordered-query=true
essentials.durable-queues.polling-delay-interval-increment-factor=0.5
essentials.durable-queues.max-polling-interval=2s
essentials.durable-queues.verbose-tracing=false
essentials.durable-queues.enable-queue-statistics=false
essentials.durable-queues.shared-queue-statistics-table-name=durable_queues_statistics
essentials.durable-queues.enable-queue-statistics-ttl=false
essentials.durable-queues.queue-statistics-ttl-duration=90
```

| Property | Default | Description                                                                 |
|----------|---------|-----------------------------------------------------------------------------|
| `shared-queue-table-name` | `durable_queues` | PostgreSQL table for messages. **See [Security](#security)** |
| `transactional-mode` | `single-operation-transaction` | `single-operation-transaction` or `fully-transactional`                     |
| `message-handling-timeout` | `30s` | Timeout before unacknowledged message is redelivered (single-op mode only)  |
| `use-centralized-message-fetcher` | `true` | Use optimized centralized message fetching                                  |
| `centralized-message-fetcher-polling-interval` | `20ms` | Base polling interval for centralized fetcher                               |
| `centralized-polling-delay-back-off-factor` | `1.5` | Backoff factor when no messages found                                       |
| `use-ordered-unordered-query` | `true` | Enable specialized query for mixed message ordering                         |
| `polling-delay-interval-increment-factor` | `0.5` | Legacy backoff factor (when not using centralized fetcher)                  |
| `max-polling-interval` | `2s` | Maximum polling delay                                                       |
| `verbose-tracing` | `false` | Include all operations in traces (not just top-level)                       |
| `enable-queue-statistics` | `false` | Enable queue statistics collection                                          |
| `shared-queue-statistics-table-name` | `durable_queues_statistics` | Table for queue statistics. **See [Security](#security)**                                                 |
| `enable-queue-statistics-ttl` | `false` | Enable TTL for statistics records                                           |
| `queue-statistics-ttl-duration` | `90` | TTL duration in days                                                        |

**Transactional Modes:**

| Mode | Description | Recommended |
|------|-------------|-------------|
| `single-operation-transaction` | Queue operations execute without transactions; uses timeout-based message acknowledgment | **Yes** |
| `fully-transactional` | Queue operations share the parent `UnitOfWork` transaction | No |

> ⚠️ **Warning:** `fully-transactional` mode causes issues with retries and dead letter handling because when an exception occurs, the transaction is marked for rollback and retry counts are never increased. Use `single-operation-transaction` for reliable message processing.

### MultiTableChangeListener Configuration

```properties
essentials.multi-table-change-listener.polling-interval=50ms
essentials.multi-table-change-listener.filter-duplicate-notifications=true
```

| Property | Default | Description |
|----------|---------|-------------|
| `polling-interval` | `50ms` | Interval for polling PostgreSQL NOTIFY channel |
| `filter-duplicate-notifications` | `true` | Filter duplicate notifications using registered filters |

### EventBus Configuration

```properties
essentials.reactive.event-bus-backpressure-buffer-size=1024
essentials.reactive.event-bus-parallel-threads=4
essentials.reactive.overflow-max-retries=20
essentials.reactive.queued-task-cap-factor=1.5
essentials.reactive.command-bus-parallel-send-and-dont-wait-consumers=4
```

| Property | Default | Description |
|----------|---------|-------------|
| `event-bus-backpressure-buffer-size` | `1024` | Reactor backpressure buffer size |
| `event-bus-parallel-threads` | min(available processors, 4) | Threads for async event processing |
| `overflow-max-retries` | `20` | Max retries when buffer overflows |
| `queued-task-cap-factor` | `1.5` | Factor for calculating queued task capacity |
| `command-bus-parallel-send-and-dont-wait-consumers` | min(available processors, 4) | Parallel consumers for `sendAndDontWait` |

### Scheduler Configuration

```properties
essentials.scheduler.enabled=true
essentials.scheduler.number-of-threads=4
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Enable the distributed scheduler |
| `number-of-threads` | min(available processors, 4) | Thread pool size |

### Metrics Configuration

Threshold-based logging for performance monitoring:

```yaml
essentials:
  metrics:
    durable-queues:
      enabled: true
      thresholds:
        debug: 25ms    # Log at DEBUG if duration >= 25ms
        info: 200ms    # Log at INFO if duration >= 200ms
        warn: 500ms    # Log at WARN if duration >= 500ms
        error: 5000ms  # Log at ERROR if duration >= 5000ms
    command-bus:
      enabled: true
      thresholds:
        debug: 25ms
        info: 200ms
        warn: 500ms
        error: 5000ms
    message-handler:
      enabled: true
      thresholds:
        debug: 25ms
        info: 200ms
        warn: 500ms
        error: 5000ms
    sql:
      enabled: false
      thresholds:
        debug: 25ms
        info: 200ms
        warn: 500ms
        error: 5000ms
```

**Logger Classes** (for log level configuration):
- Durable Queues: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeDurableQueueInterceptor`
- Command Bus: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeCommandBusInterceptor`
- Message Handler: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeMessageHandlerInterceptor`
- SQL: `dk.trustworks.essentials.components.foundation.postgresql.micrometer.RecordSqlExecutionTimeLogger`

### Micrometer Tagging

```properties
essentials.tracing-properties.module-tag=my-module
```

| Property | Default | Description |
|----------|---------|-------------|
| `module-tag` | `null` | Tag value for 'module' in all Micrometer metrics (helps differentiate metrics across modules) |

### Lifecycle Configuration

```properties
essentials.life-cycles.start-life-cycles=true
essentials.reactive-bean-post-processor-enabled=true
essentials.immutable-jackson-module-enabled=true
```

| Property | Default | Description |
|----------|---------|-------------|
| `life-cycles.start-life-cycles` | `true` | **true**: Automatically call `start()` on all `Lifecycle` beans (FencedLockManager, DurableQueues, etc.) when ApplicationContext starts, and `stop()` on shutdown.  <br/>**false**: You must manually start/stop Lifecycle beans. |
| `reactive-bean-post-processor-enabled` | `true` | **true**: Auto-register `EventHandler` beans with `EventBus` and `CommandHandler` beans with `CommandBus`.  <br/>**false**: You must manually register handlers with their buses. |
| `immutable-jackson-module-enabled` | `true` | **true**: Enable `EssentialsImmutableJacksonModule` for deserializing immutable objects (requires Objenesis).  <br/>**false**: Disable even if Objenesis is available. |

---

## DurableLocalCommandBus Customization

Override default error handling and redelivery behavior by providing custom beans:

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
            // Don't rethrow = no retry, no dead letter
        } else {
            Exceptions.sneakyThrow(exception);  // Rethrow = retry/dead letter
        }
    };
}
```

---

## JdbiConfigurationCallback

The starter automatically invokes `JdbiConfigurationCallback.configure(Jdbi)` on all beans implementing this interface during context initialization.  
This allows you to customize the Jdbi instance (e.g., register argument types, row mappers):

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

> **Note:** `JdbiConfigurationCallback.configure()` is called BEFORE any `Lifecycle` beans are started.

---

## Typical Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-postgresql</artifactId>
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
