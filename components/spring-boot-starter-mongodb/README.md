# Essentials Components - Spring Boot Starter: MongoDB

> **NOTE:** **The library is WORK-IN-PROGRESS**

Spring Boot auto-configuration for all MongoDB-focused Essentials components.

**LLM Context:** [LLM-spring-boot-starter-modules.md](../../LLM/LLM-spring-boot-starter-modules.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Auto-Configured Beans](#auto-configured-beans)
- [Configuration Properties](#configuration-properties)
  - [FencedLock Configuration](#fencedlock-configuration)
  - [DurableQueues Configuration](#durablequeues-configuration)
  - [EventBus Configuration](#eventbus-configuration)
  - [Metrics Configuration](#metrics-configuration)
  - [Lifecycle Configuration](#lifecycle-configuration)
- [DurableLocalCommandBus Customization](#durablelocalcommandbus-customization)
- [Spring Data MongoDB Converters](#spring-data-mongodb-converters)
  - [When to Register Additional CharSequenceTypes](#when-to-register-additional-charsequencetypes)
  - [Adding Custom Converters](#adding-custom-converters)
- [Typical Dependencies](#typical-dependencies)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-mongodb</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: NoSQL Injection Risk

The components allow customization of collection names that are used with **String concatenation** → NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against NoSQL injection.

⚠️ **Collection Name Security:** All collection name properties are validated using `MongoUtil.checkIsValidCollectionName()` as a first-line defense, but this does NOT provide exhaustive protection.

> **Developer Responsibility:**
> - Only derive collection names from controlled, trusted sources
> - NEVER allow external/untrusted input to provide collection names
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
- [springdata-mongo-queue](springdata-mongo-queue/README.md#security)
- [springdata-mongo-distributed-fenced-lock](springdata-mongo-distributed-fenced-lock/README.md#security)

### What Validation Does NOT Protect Against

- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## Auto-Configured Beans

All beans use `@ConditionalOnMissingBean` for easy overriding.

### Transaction & UnitOfWork

| Bean | Description |
|------|-------------|
| `MongoTransactionManager` | Spring transaction manager with `ReadConcern.SNAPSHOT` and `WriteConcern.ACKNOWLEDGED` |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | Joins `UnitOfWork`s with Spring-managed transactions. See [UnitOfWork documentation](../foundation/README.md#unitofwork-transactions) |

### Distributed Locking

| Bean | Description |
|------|-------------|
| `MongoFencedLockManager` | Distributed fenced locks via MongoDB. See [FencedLock documentation](../foundation/README.md#fencedlock-distributed-locking) |

### Messaging & Queues

| Bean | Description |
|------|-------------|
| `MongoDurableQueues` | Durable message queuing via MongoDB. See [DurableQueues documentation](../foundation/README.md#durablequeues-messaging) |
| `Inboxes` | Store-and-forward for incoming messages. See [Inbox Pattern](../foundation/README.md#inbox-pattern) |
| `Outboxes` | Store-and-forward for outgoing messages. See [Outbox Pattern](../foundation/README.md#outbox-pattern) |
| `DurableLocalCommandBus` | Command bus with durable message delivery. See [DurableLocalCommandBus](../foundation/README.md#durablelocalcommandbus) |

### Reactive & Event Processing

| Bean | Description |
|------|-------------|
| `LocalEventBus` | Named "default", supports async event handling. See [LocalEventBus](../../reactive/README.md#localeventbus) |
| `ReactiveHandlersBeanPostProcessor` | Auto-registers `EventHandler` and `CommandHandler` beans with their respective buses. See [Spring Integration](../../reactive/README.md#spring-integration) |

### Serialization

| Bean | Condition | Description |
|------|-----------|-------------|
| `EssentialTypesJacksonModule` | Always | Jackson support for Essentials semantic types |
| `EssentialsImmutableJacksonModule` | Objenesis on classpath + `essentials.immutable-jackson-module-enabled=true` | Jackson support for immutable objects without default constructor |
| `JacksonJSONSerializer` | Always | Pre-configured ObjectMapper with sensible defaults |

### MongoDB Integration

| Bean | Description |
|------|-------------|
| `SingleValueTypeRandomIdGenerator` | Server-generated IDs for `SingleValueType` @Id fields. See [types-springdata-mongo](../../types-springdata-mongo/README.md) |
| `MongoCustomConversions` | Converters for `LockName`, `QueueEntryId`, `QueueName`. See [Adding Your Domain Types](#adding-your-domain-types) |

### Observability

| Bean | Condition | Description |
|------|-----------|-------------|
| `DurableQueuesMicrometerTracingInterceptor` | `management.tracing.enabled=true` | Distributed tracing |
| `DurableQueuesMicrometerInterceptor` | `management.tracing.enabled=true` | Micrometer metrics |
| `RecordExecutionTimeMessageHandlerInterceptor` | Always | Performance logging |
| `RecordExecutionTimeCommandBusInterceptor` | Always | Performance logging |
| `RecordExecutionTimeDurableQueueInterceptor` | Always | Performance logging |

### Lifecycle

| Bean | Description |
|------|-------------|
| `DefaultLifecycleManager` | Manages `Lifecycle.start()`/`stop()` for components |

---

## Configuration Properties

### FencedLock Configuration

```properties
essentials.fenced-lock-manager.fenced-locks-collection-name=fenced_locks
essentials.fenced-lock-manager.lock-time-out=12s
essentials.fenced-lock-manager.lock-confirmation-interval=5s
essentials.fenced-lock-manager.release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation=false
```

| Property | Default | Description |
|----------|---------|-------------|
| `fenced-locks-collection-name` | `fenced_locks` | MongoDB collection for locks. **See [Security](#security)** |
| `lock-time-out` | `12s` | Duration before unconfirmed lock expires |
| `lock-confirmation-interval` | `5s` | Heartbeat interval (must be < timeout) |
| `release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Release locks locally on IO exceptions during confirmation |

### DurableQueues Configuration

```properties
essentials.durable-queues.shared-queue-collection-name=durable_queues
essentials.durable-queues.transactional-mode=single-operation-transaction
essentials.durable-queues.message-handling-timeout=5s
essentials.durable-queues.polling-delay-interval-increment-factor=0.5
essentials.durable-queues.max-polling-interval=2s
essentials.durable-queues.verbose-tracing=false
```

| Property | Default | Description                                                                   |
|----------|---------|-------------------------------------------------------------------------------|
| `shared-queue-collection-name` | `durable_queues` | MongoDB collection for messages. **See [Security](#security)** |
| `transactional-mode` | `singleoperationtransaction` | `fully-transactional` or `single-operation-transaction` (recommended)            |
| `message-handling-timeout` | `30s` | Timeout before unacknowledged message is redelivered (single-op mode only)    |
| `polling-delay-interval-increment-factor` | `0.5` | Backoff factor when no messages found                                         |
| `max-polling-interval` | `2s` | Maximum polling delay                                                         |
| `verbose-tracing` | `false` | Include all operations in traces (not just top-level)                         |

**Transactional Modes:**

| Mode | Description | Recommended |
|------|-------------|-------------|
| `single-operation-transaction` | Queue operations execute without transactions; uses timeout-based message acknowledgment | **Yes** |
| `fully-transactional` | Queue operations share the parent `UnitOfWork` transaction | No |

> ⚠️ **Warning:** `fully-transactional` mode causes issues with retries and dead letter handling because when an exception occurs, the transaction is marked for rollback and retry counts are never increased. Use `single-operation-transaction` for reliable message processing.

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
```

**Logger Classes** (for log level configuration):
- Durable Queues: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeDurableQueueInterceptor`
- Command Bus: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeCommandBusInterceptor`
- Message Handler: `dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeMessageHandlerInterceptor`

### Lifecycle Configuration

```properties
essentials.life-cycles.start-life-cycles=true
essentials.reactive-bean-post-processor-enabled=true
essentials.immutable-jackson-module-enabled=true
```

| Property | Default | Description                                                                                                                                                                                                                       |
|----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `life-cycles.start-life-cycles` | `true` | **true**: Automatically call `start()` on all `Lifecycle` beans (FencedLockManager, DurableQueues, etc.) when ApplicationContext starts, and `stop()` on shutdown.  <br/>**false**: You must manually start/stop Lifecycle beans. |
| `reactive-bean-post-processor-enabled` | `true` | **true**: Auto-register `EventHandler` beans with `EventBus` and `CommandHandler` beans with `CommandBus`.  <br/>**false**: You must manually register handlers with their buses.                                                 |
| `immutable-jackson-module-enabled` | `true` | **true**: Enable `EssentialsImmutableJacksonModule` for deserializing immutable objects (requires Objenesis).  <br/>**false**: Disable even if Objenesis is available.                                                                 |

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

## Spring Data MongoDB Converters

### What's Auto-Configured

The starter auto-registers a `SingleValueTypeConverter` that handles conversion between `SingleValueType` instances and MongoDB types. This converter is pre-configured with:
- `LockName` - Used by FencedLockManager
- `QueueEntryId` - Used by DurableQueues
- `QueueName` - Used by DurableQueues

The converter automatically supports all `SingleValueType` subtypes (`CharSequenceType`, `NumberType`, `InstantType`, etc.) for basic document field storage.

### When to Register Additional CharSequenceTypes

You must explicitly register `CharSequenceType` subtypes that:
1. **Contain `ObjectId` values** - Types where `random()` returns `new YourType(ObjectId.get().toString())`
2. **Are used as Map keys** - MongoDB stores Map keys as `ObjectId` internally when they contain valid ObjectId strings

Without registration, MongoDB can't convert the `ObjectId` back to your `CharSequenceType` when reading.

**Example type using ObjectId:**

```java
public class ProductId extends CharSequenceType<ProductId> implements Identifier {
    public ProductId(CharSequence value) {
        super(value);
    }

    public static ProductId random() {
        return new ProductId(ObjectId.get().toString());  // Uses ObjectId
    }
}
```

**Register types that use ObjectId values:**

```java
@Bean
AdditionalCharSequenceTypesSupported additionalCharSequenceTypesSupported() {
    return new AdditionalCharSequenceTypesSupported(ProductId.class, OrderId.class);
}
```

### Adding Custom Converters

For non-`SingleValueType` types or types requiring custom conversion logic:

```java
@Bean
AdditionalConverters additionalConverters() {
    return new AdditionalConverters(
            Jsr310Converters.StringToDurationConverter.INSTANCE,
            Jsr310Converters.DurationToStringConverter.INSTANCE
    );
}
```

See [types-springdata-mongo](../../types-springdata-mongo/README.md) for complete details on `SingleValueTypeConverter`, supported type conversions, and JSR-310 temporal types.

---

## Typical Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-mongodb</artifactId>
        <version>${essentials.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-mongodb</artifactId>
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
        <artifactId>testcontainers-mongodb</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```
