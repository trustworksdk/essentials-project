# Foundation - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/foundation/README.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.foundation`
- **Purpose**: Foundational patterns for distributed systems - transactions, messaging, locking, inbox/outbox
- **Key Dependencies**: `shared`, `types`, `reactive`, `immutable`
- **Scope**: Intra-service coordination (instances of same service sharing a database)
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `InterceptorChain`, `PatternMatchingMethodInvoker` from [shared](./LLM-shared.md)
- `CommandBus`, `LocalCommandBus` from [reactive](./LLM-reactive.md)
- `JSONSerializer` from [immutable-jackson](./LLM-immutable-jackson.md)
- `CorrelationId`, `MessageId`, `SubscriberId` from [foundation-types](./LLM-foundation-types.md)

## TOC
- [Core Concepts](#core-concepts)
- [UnitOfWork (Transactions)](#unitofwork-transactions)
- [DurableQueues (Messaging)](#durablequeues-messaging)
- [FencedLock (Distributed Locking)](#fencedlock-distributed-locking)
- [Inbox/Outbox Patterns](#inboxoutbox-patterns)
- [Ordered Message Processing](#ordered-message-processing)
- [DurableLocalCommandBus](#durablelocalcommandbus)
- [Utilities](#utilities)
- ⚠️ [Security](#security)

## Core Concepts

**Base package**: `dk.trustworks.essentials.components.foundation`

| Pattern | Package | Purpose | Scope |
|---------|---------|---------|-------|
| `UnitOfWork` | `.transaction` | Technology-agnostic transaction management | Any database |
| `DurableQueues` | `.messaging.queue` | Point-to-point messaging, At-Least-Once delivery | Intra-service |
| `FencedLock` | `.fencedlock` | Distributed locking with fence tokens | Intra-service |
| `Inbox` | `.messaging.eip.store_and_forward` | Store-and-forward for incoming messages | External → Service |
| `Outbox` | `.messaging.eip.store_and_forward` | Store-and-forward for outgoing messages | Service → External |
| `DurableLocalCommandBus` | `.reactive.command` | CommandBus with durable sendAndDontWait | Intra-service |

**Intra-service**: Multiple instances of SAME service sharing a database. For cross-service (Sales ↔ Billing ↔ Shipping), use Kafka/RabbitMQ/Zookeeper.

## UnitOfWork (Transactions)

**Package**: `dk.trustworks.essentials.components.foundation.transaction`

### API

```java
// dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory
public interface UnitOfWorkFactory<UOW extends UnitOfWork> {
    // No return, no UnitOfWork access
    void usingUnitOfWork(CheckedRunnable runnable);

    // No return, WITH UnitOfWork access
    void usingUnitOfWork(CheckedConsumer<UOW> consumer);

    // Return value, no UnitOfWork access
    <R> R withUnitOfWork(CheckedSupplier<R> supplier);

    // Return value, WITH UnitOfWork access
    <R> R withUnitOfWork(CheckedFunction<UOW, R> function);

    UOW getRequiredUnitOfWork();  // Throws if no active UnitOfWork
    Optional<UOW> getCurrentUnitOfWork();
}

// dk.trustworks.essentials.components.foundation.transaction.UnitOfWork
public interface UnitOfWork {
    void commit();
    void rollback();
    void markAsRollbackOnly();
    void markAsRollbackOnly(Exception cause);
    UnitOfWorkStatus status();
}
```

### Implementations

| Class | Package | Technology | Notes |
|-------|---------|------------|-------|
| `JdbiUnitOfWorkFactory` | `.transaction.jdbi` | JDBI | Direct JDBC, supports `HandleAwareUnitOfWork` |
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | `.transaction.spring.jdbi` | JDBI + Spring | Supports `HandleAwareUnitOfWork` |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | `.transaction.spring.mongo` | MongoDB + Spring | Spring managed MongoDB transactions |

### Nested Transaction Behavior

- **First call**: Creates UnitOfWork + underlying transaction, commits on success
- **Nested calls**: Create new UnitOfWork instances reusing same underlying transaction
- **Exception**: Marks entire transaction for rollback

```java
unitOfWorkFactory.usingUnitOfWork(() -> {  // UnitOfWork #1 + JDBC tx
    orderRepository.save(order);

    unitOfWorkFactory.usingUnitOfWork(() -> {  // UnitOfWork #2, reuses JDBC tx
        auditLog.record("Order saved");
    });

    // Outer completes → commits underlying JDBC tx
});
```

### Spring Integration

Spring-aware factories join Spring `@Transactional` methods:

```java
@Transactional
public void processOrder(Order order) {
    // Spring tx active - NO explicit usingUnitOfWork needed
    orderRepository.save(order);  // Spring Data participates

    // DurableQueues calls getRequiredUnitOfWork() internally
    // which auto-creates UnitOfWork joining Spring tx
    durableQueues.queueMessage(queueName, new OrderProcessedEvent(order.getId()));
}
```

**When to use explicit `usingUnitOfWork` / `withUnitOfWork`:**
1. No transaction exists - starts new transaction
2. Need return value - use `withUnitOfWork`

**Accessing current UnitOfWork:**
```java
UnitOfWork uow = unitOfWorkFactory.getRequiredUnitOfWork();
uow.markAsRollbackOnly(exception);

// For JDBI - access Handle
HandleAwareUnitOfWork handleUow = (HandleAwareUnitOfWork) unitOfWorkFactory.getRequiredUnitOfWork();
Handle handle = handleUow.handle();

Optional<UnitOfWork> maybeUow = unitOfWorkFactory.getCurrentUnitOfWork();
```
**Note**: `getRequiredUnitOfWork()` throws `NoActiveUnitOfWorkException` if called outside a Spring managed transaction - use `usingUnitOfWork`/`withUnitOfWork` to create one first.

## DurableQueues (Messaging)

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue`

### Key Features

| Feature | Description |
|---------|-------------|
| **At-Least-Once Delivery** | Messages guaranteed delivered at least once |
| **Competing Consumers** | Multiple consumers per queue |
| **Dead Letter Queue** | Failed messages isolated (same QueueName, marked as dead-letter) |
| **Ordered Messages** | Delivered in sequence per key |
| **Delayed Delivery** | Schedule messages for future delivery |
| **Redelivery Policies** | Fixed/linear/exponential backoff |

**Design Requirement**: Handlers MUST be idempotent (can receive duplicates).

### DurableQueues API

```java
// dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues
public interface DurableQueues {
    // Queue messages
    QueueEntryId queueMessage(QueueName queueName, Message message);
    QueueEntryId queueMessage(QueueName queueName, Message message, Duration deliveryDelay);
    List<QueueEntryId> queueMessages(QueueName queueName, List<? extends Message> messages);

    // Consume
    DurableQueueConsumer consumeFromQueue(ConsumeFromQueue config);

    // Dead Letter operations
    Optional<QueuedMessage> resurrectDeadLetterMessage(QueueEntryId id, Duration delay);
    Optional<QueuedMessage> markAsDeadLetterMessage(QueueEntryId id, String reason);
    boolean markAsDeadLetterMessageDirect(QueueEntryId id, Throwable cause);  // No deserialization
    List<QueuedMessage> getDeadLetterMessages(QueueName queueName, QueueingSortOrder order, long startIndex, long pageSize);
}
```

### Pattern Matching Handler

**Class**: `dk.trustworks.essentials.components.foundation.messaging.queue.PatternMatchingQueuedMessageHandler` implementing `dk.trustworks.essentials.components.foundation.messaging.MessageHandler`:

```java
var consumer = durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderProcessing"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(200))
            .setMaximumNumberOfRedeliveries(5)
            .build())
        .setParallelConsumers(3)
        .setQueueMessageHandler(new PatternMatchingQueuedMessageHandler() {
            @MessageHandler
            void handle(ProcessOrderCommand cmd) { }

            @MessageHandler
            void handle(CancelOrderCommand cmd, QueuedMessage msg) { }

            @Override
            protected void handleUnmatchedMessage(QueuedMessage msg) { }
        })
        .build());
```

### RedeliveryPolicy

**Class**: `dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy`

| Strategy | Formula | Use Case |
|----------|---------|----------|
| `fixedBackoff()` | Same delay every retry | Simple retries |
| `linearBackoff()` | Delay increases linearly | Gradual backoff |
| `exponentialBackoff()` | Delay doubles each retry | External service recovery |

```java
// Fixed: 500ms delay, max 5 retries
RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5)

// Exponential: starts 500ms, doubles, max 1min delay, max 8 retries
RedeliveryPolicy.exponentialBackoff(
    Duration.ofMillis(500),  // initialRedeliveryDelay
    Duration.ofMillis(500),  // followupRedeliveryDelay
    2.0,                     // multiplier
    Duration.ofMinutes(1),   // max delay
    8                        // max retries
)
```

### MessageDeliveryErrorHandler

**Interface**: `dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler`

```java
// Always retry (default)
MessageDeliveryErrorHandler.alwaysRetry()

// Stop redelivery on specific exceptions → immediate DLQ
MessageDeliveryErrorHandler.stopRedeliveryOn(
    ValidationException.class,
    IllegalArgumentException.class
)
```

### Dead Letter Queue

```java
// Query DLQ
List<QueuedMessage> deadLetters = durableQueues.getDeadLetterMessages(
    queueName, QueueingSortOrder.ASC, 0, 100);

// Resurrect
durableQueues.resurrectDeadLetterMessage(queueEntryId, Duration.ofSeconds(10));

// Manual mark as DLQ
durableQueues.markAsDeadLetterMessage(queueEntryId, "Invalid customer data");

// Mark as DLQ without returning/deserializing message (for corrupted payloads)
durableQueues.markAsDeadLetterMessageDirect(queueEntryId, deserializationException);
```

**When to use `markAsDeadLetterMessageDirect`**: Use when the message payload cannot be deserialized (e.g., class was renamed/removed). The regular `markAsDeadLetterMessage` returns the updated `QueuedMessage`, which requires deserializing the payload again—causing a loop. The "Direct" variant updates the database without returning the message.

### Interceptors

**Interface**: `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueuesInterceptor`

Add cross-cutting behavior:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "OrderService"))
    .build();
```

**Built-in**:

| Interceptor | Package | Metrics |
|-------------|---------|---------|
| `DurableQueuesMicrometerInterceptor` | `.messaging.queue.micrometer` | Queue size gauges, counters |
| `DurableQueuesMicrometerTracingInterceptor` | `.messaging.queue.micrometer` | Distributed tracing |
| `RecordExecutionTimeDurableQueueInterceptor` | `.interceptor.micrometer` | Execution time |

### Implementations

| Implementation | Module |
|---------------|--------|
| `dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues` | [LLM-postgresql-queue.md](./LLM-postgresql-queue.md) |
| `dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues` | [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) |

## FencedLock (Distributed Locking)

**Package**: `dk.trustworks.essentials.components.foundation.fencedlock`

Based on [Martin Kleppmann's fenced locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html).

### The Problem: Split Brain

Traditional locks fail when holder becomes stale without knowing:

```
Instance A: [Acquire Lock]──[Processing]──[GC PAUSE............................]──[Continues!]
Instance B:                                   [Lock Timeout]──[Acquire]──[Processing]
                                                                  ↑
                                                             SPLIT BRAIN
```

### The Solution: Fence Tokens

**Fence token**: Monotonically increasing counter issued on lock acquire.
**Lock confirmation**: Periodic heartbeat updating last confirmed timestamp without changing token.

Downstream systems reject stale tokens:

```java
// Lock holder: pass token
FencedLock lock = lockManager.acquireLock(LockName.of("ProcessOrders"));
long fenceToken = lock.getCurrentToken();
orderService.processOrder(orderId, fenceToken);

// Downstream: validate
if (lastSeenToken != null && fenceToken < lastSeenToken) {
    throw new StaleTokenException();
}
lastSeenTokens.put(orderId, fenceToken);

// Database: include token in WHERE
UPDATE orders
SET status = :status, last_fence_token = :token
WHERE id = :id AND last_fence_token <= :token
```

### API

```java
// dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager
public interface FencedLockManager extends Lifecycle {
    FencedLock acquireLock(LockName lockName);
    Optional<FencedLock> tryAcquireLock(LockName lockName);
    Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout);
    void acquireLockAsync(LockName lockName, LockCallback callback);
    void cancelAsyncLockAcquiring(LockName lockName);
    boolean isLockAcquired(LockName lockName);
    boolean isLockedByThisLockManagerInstance(LockName lockName);
    boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName);
    String getLockManagerInstanceId();
}

// dk.trustworks.essentials.components.foundation.fencedlock.FencedLock
public interface FencedLock extends AutoCloseable {
    long getCurrentToken();
    void release();
}
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `lockTimeOut` | 10s | Time without confirmation before lock expires |
| `lockConfirmationInterval` | 3s | Heartbeat interval (must be < lockTimeOut) |

**Rule**: `lockConfirmationInterval` should be 2-3x smaller than `lockTimeOut`.

### Usage

```java
// Synchronous
try (FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"))) {
    performCriticalWork();
}

// Pattern: Try-Acquire (Non-Blocking)
Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));
if (lock.isPresent()) {
    try {
        processOrders(lock.get().getCurrentToken());
    } finally {
        lockManager.releaseLock(lock.get());
    }
}

// Asynchronous - PREFERRED for long-running
lockManager.acquireLockAsync(LockName.of("ScheduledTask"), new LockCallback() {
    @Override
    public void lockAcquired(FencedLock lock) {
        long fenceToken = lock.getCurrentToken();
        startPeriodicProcessing(fenceToken);
    }

    @Override
    public void lockReleased(FencedLock lock) {
        stopPeriodicProcessing();
    }
});
```

### Pattern: Lock Status Queries

```java
// Check if lock exists in database
Optional<FencedLock> lockInfo = lockManager.lookupLock(LockName.of("my-lock"));

// Check if THIS instance holds the lock
boolean heldByMe = lockManager.isLockedByThisLockManagerInstance(LockName.of("my-lock"));

// Check if another instance holds the lock
boolean heldByOther = lockManager.isLockAcquiredByAnotherLockManagerInstance(LockName.of("my-lock"));
```

## Common Use Cases

### Singleton Worker Pattern

```java
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledWorker {
    @Scheduled(fixedDelay = 30000)
    public void processOrders() {
        Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));
        if (lock.isPresent()) {
            try {
                processOrderBatch(lock.get().getCurrentToken());
            } finally {
                lockManager.releaseLock(lock.get());
            }
        }
    }
}
```

### Leadership Election

```java
@Component
public class LeaderElection {
    private volatile boolean isLeader = false;
    @Autowired private FencedLockManager lockManager;

    @PostConstruct
    public void startElection() {
        lockManager.acquireLockAsync(
            LockName.of("service-leader"),
            new LockCallback() {
                @Override
                public void lockAcquired(FencedLock lock) {
                    isLeader = true;
                    startLeaderActivities();
                }

                @Override
                public void lockReleased(FencedLock lock) {
                    isLeader = false;
                    stopLeaderActivities();
                }
            }
        );
    }
}
```

### Implementations

| Implementation | Module |
|---------------|--------|
| `PostgresqlFencedLockManager` | [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) |
| `MongoFencedLockManager` | [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) |

## Inbox/Outbox Patterns

**Package**: `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`

### Inbox Pattern

**Problem**: Dual write when consuming from Kafka + updating database.

**Solution**: Store message in Inbox within UnitOfWork, ACK Kafka only after commit.

```
Kafka → KafkaListener → Inbox.addMessageReceived() → UnitOfWork.commit() → ACK Kafka
                              ↓
                        DurableQueues → Handler
```

**Configuration**:

```java
Inbox orderEventsInbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .setInboxName(InboxName.of("OrderService:KafkaEvents"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(100))
            .setMaximumNumberOfRedeliveries(10)
            .build())
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
        .setNumberOfParallelMessageConsumers(5)
        .build(),
    new PatternMatchingMessageHandler() {
        @MessageHandler
        void handle(ProcessOrderCommand cmd) { }

        @Override
        protected void handleUnmatchedMessage(Message msg) { }
    });

@KafkaListener(topics = ORDER_EVENTS_TOPIC, groupId = "order-processing")
public void handle(OrderEvent event) {
    orderEventsInbox.addMessageReceived(new ProcessOrderCommand(event.getId()));
}
```

### Outbox Pattern

**Problem**: Dual write when updating database + publishing to Kafka.

**Solution**: Store message in Outbox within same UnitOfWork as database update.

```
Database Update + Outbox.sendMessage() → UnitOfWork.commit()
                              ↓
                        DurableQueues → Relay → Kafka
```

**Configuration**:

```java
Outbox kafkaOutbox = outboxes.getOrCreateOutbox(
    OutboxConfig.builder()
        .setOutboxName(OutboxName.of("OrderService:KafkaEvents"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 10))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
        .setNumberOfParallelMessageConsumers(1)
        .build(),
    message -> {
        kafkaTemplate.send("order-events", message.getPayload());
    });

// Atomic with database update
unitOfWorkFactory.usingUnitOfWork(() -> {
    Order order = orderRepository.save(new Order(...));
    kafkaOutbox.sendMessage(new OrderCreatedEvent(order.getId()));
});
```

### Message Consumption Modes

**Enum**: `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageConsumptionMode`

| Mode | Description | Use Case |
|------|-------------|----------|
| `SingleGlobalConsumer` | One active consumer in cluster (uses FencedLock) | Ordered message processing |
| `GlobalCompetingConsumers` | Multiple consumers compete | Unordered, max throughput |

## Ordered Message Processing

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue`

### OrderedMessage

**Class**: `dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage`

```java
public class OrderedMessage extends Message {
    public String getKey();   // Entity ID (e.g., "Order-123")
    public long getOrder();   // Sequential position per key (0, 1, 2...)

    public static OrderedMessage of(Object payload, String key, long order);
}
```

Messages with same `key` processed in `order` sequence. Different `key`s can be parallel.

### Single-Node Ordering

Automatic - no configuration:

```java
durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderEvents"))
        .setParallelConsumers(10)  // Ordering maintained per key
        .setQueueMessageHandler(handler)
        .build());
```

**How**: Tracks in-process keys to prevent concurrent processing of same key.

### Multi-Node Ordering

**Problem**: Each node independently polls database → messages for same key processed out of order.

**Solution**: Use Inbox/Outbox with `SingleGlobalConsumer`:

```java
Inbox inbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .setInboxName(InboxName.of("OrderEventsInbox"))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)  // Required!
        .setNumberOfParallelMessageConsumers(10)
        .setRedeliveryPolicy(redeliveryPolicy)
        .build(),
    messageHandler);
```

**How it works**:
1. FencedLock ensures only ONE node actively consumes
2. Active node uses multiple threads (e.g., 10)
3. Message fetcher ensures ordering per key
4. Failover: Lock timeout → another node takes over

### Comparison

| Scenario | Configuration | Ordering | Throughput |
|----------|--------------|----------|------------|
| Single node | Any | ✅ Per key | High |
| Multi-node, unordered | `GlobalCompetingConsumers` | ❌ None | Highest |
| Multi-node, ordered | `SingleGlobalConsumer` + FencedLock | ✅ Per key, cluster-wide | Medium |

**Best Practice**: Design for idempotency (required for At-Least-Once anyway).

## DurableLocalCommandBus

**Package**: `dk.trustworks.essentials.components.foundation.reactive.command`

**Class**: `dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus`

CommandBus using DurableQueues for `sendAndDontWait`.

### Configuration

```java
var commandBus = DurableLocalCommandBus.builder()
    .setDurableQueues(durableQueues)
    .setSendAndDontWaitErrorHandler(new RethrowingSendAndDontWaitErrorHandler())
    .setCommandQueueNameSelector(CommandQueueNameSelector.sameCommandQueueForAllCommands(
        QueueName.of("DefaultCommandQueue")))
    .setCommandQueueRedeliveryPolicyResolver(CommandQueueRedeliveryPolicyResolver
        .sameReliveryPolicyForAllCommandQueues(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(150))
            .setMaximumNumberOfRedeliveries(20)
            .build()))
    .setParallelSendAndDontWaitConsumers(20)
    .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
    .build();
```

### Usage

```java
// Fire-and-forget with guaranteed delivery
commandBus.sendAndDontWait(new ProcessOrderCommand(orderId));

// Delayed execution
commandBus.sendAndDontWait(new SendReminderCommand(customerId), Duration.ofHours(24));

// Synchronous (returns result)
OrderId result = commandBus.send(new CreateOrderCommand(...));
```

## Utilities

### JSONSerializer

**Package**: `dk.trustworks.essentials.components.foundation.json`

```java
// dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer
JSONSerializer serializer = new JacksonJSONSerializer(objectMapper);

String json = serializer.serialize(order);
byte[] bytes = serializer.serializeAsBytes(order);
Order order = serializer.deserialize(json, Order.class);
Object event = serializer.deserialize(json, "com.example.OrderCreatedEvent");
```

### LifecycleManager

**Package**: `dk.trustworks.essentials.components.foundation.lifecycle`

Integrates `Lifecycle` beans with Spring:

```java
// dk.trustworks.essentials.components.foundation.lifecycle.DefaultLifecycleManager
@Bean
public DefaultLifecycleManager lifecycleManager() {
    return new DefaultLifecycleManager(
        context -> { /* optional callback */ },
        true  // auto-start Lifecycle beans
    );
}
```

### PostgreSQL Utilities

**Package**: `dk.trustworks.essentials.components.foundation.postgresql`

#### PostgresqlUtil

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil`

```java
// Validate table/column/index names (initial defense layer)
PostgresqlUtil.checkIsValidTableOrColumnName("orders");  // OK
PostgresqlUtil.checkIsValidTableOrColumnName("SELECT");  // Throws - keyword

// Boolean variants
PostgresqlUtil.isValidSqlIdentifier("valid_id");  // true, max 63 chars
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.table");  // true, max 127 chars
PostgresqlUtil.isValidFunctionName("my_function");  // true

// Other
int version = PostgresqlUtil.getServiceMajorVersion(handle);
boolean hasPgCron = PostgresqlUtil.isPGExtensionAvailable(handle, "pg_cron");
```

**Validation Rules** (see [Security](#security) for full details):
- Start: letter (A-Z) or underscore (`_`)
- Subsequent: letters, digits, underscores
- No reserved SQL keywords (300+ in `RESERVED_NAMES`)
- Max 63 chars (simple) or 127 chars (qualified)

⚠️ **Note**: Validation is an initial defense layer, NOT exhaustive protection. See [Security](#security).

#### ListenNotify

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.ListenNotify`

```java
// Add NOTIFY trigger
ListenNotify.addChangeNotificationTriggerToTable(
    handle, "orders",
    List.of(SqlOperation.INSERT, SqlOperation.UPDATE),
    "order_id", "status");

// Listen
Flux<String> notifications = ListenNotify.listen(jdbi, "orders", Duration.ofMillis(100));
```

#### MultiTableChangeListener

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener`

```java
MultiTableChangeListener<TableChangeNotification> listener = MultiTableChangeListener.builder()
    .setJdbi(jdbi)
    .setPollingInterval(Duration.ofMillis(100))
    .setJsonSerializer(jsonSerializer)
    .setEventBus(eventBus)  // Publishes notifications here
    .setFilterDuplicateNotifications(true)
    .build();

listener.listenToNotificationsFor("orders", OrderNotification.class);
listener.start();
```

### MongoDB Utilities

**Class**: `dk.trustworks.essentials.components.foundation.mongo.MongoUtil`

```java
// Validate collection names (initial defense layer)
MongoUtil.checkIsValidCollectionName("orders");  // OK
MongoUtil.checkIsValidCollectionName("system.users");  // Throws - system.
MongoUtil.checkIsValidCollectionName("my$collection");  // Throws - $

// Detect write conflicts for retry logic
if (MongoUtil.isWriteConflict(exception)) {
    // Retry operation
}
```

**Validation Rules** (see [Security](#security) for full details):
- Max 64 characters
- No `$` or null characters
- No `system.` prefix
- Only letters, digits, underscores

⚠️ **Note**: Validation is an initial defense layer, NOT exhaustive protection. See [Security](#security).

### TTLManager

**Package**: `dk.trustworks.essentials.components.foundation.ttl` (interface)
**Package**: `dk.trustworks.essentials.components.foundation.postgresql.ttl` (PostgreSQL impl)

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager`

The TTLManager is responsible for scheduling and managing actions that enforce data lifecycle operations
such as deletion or archival based on expiration policies.

```java
PostgresqlTTLManager ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
ttlManager.start();

ttlManager.scheduleTTLJob(TTLJobDefinition.builder()
    .setAction(DefaultTTLJobAction.builder()
        .setTableName("audit_logs")  // ✅ Validated
        .setWhereClause("created_at < NOW() - INTERVAL '90 days'")  // ⚠️ NOT validated
        .build())
    .setSchedule(CronScheduleConfiguration.of("0 3 * * *"))
    .build());
```

⚠️ **Security**: `whereClause` and `fullDeleteSql` are **NOT validated** - you must sanitize these values. See [Security](#security).

### EssentialsScheduler

**Package**: `dk.trustworks.essentials.components.foundation.scheduler`

**Class**: `dk.trustworks.essentials.components.foundation.scheduler.DefaultEssentialsScheduler`

Represents a scheduler responsible for scheduling jobs defined by the `EssentialsScheduledJob` interface.  

Note: This scheduler is not intended to replace a full-fledged scheduler such as Quartz or Spring, it is a simple
scheduler that utilizes the postgresql `pg_cron` extension if available or a simple `ScheduledExecutorService` to schedule jobs.
It is meant to be used internally by essentials components to schedule jobs.

The `EssentialsScheduler` validates configuration inputs:

| Component | Validated Field | Validation Method |
|-----------|----------------|-------------------|
| `PgCronJob` | `functionName()` | `PostgresqlUtil.isValidFunctionName()` |
| `ExecutorScheduledJobRepository` | `sharedTableName` | `PostgresqlUtil.checkIsValidTableOrColumnName()` |
| `Arg` | `name`, `table` | `PostgresqlUtil.checkIsValidTableOrColumnName()` |
| `FunctionCall` | `functionName` | `PostgresqlUtil.isValidFunctionName()` |

```java
EssentialsScheduler scheduler = DefaultEssentialsScheduler.builder()
    .setJdbi(jdbi)
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setFencedLockManager(fencedLockManager)
    .setSchedulerThreads(4)
    .build();

// pg_cron job (if extension available)
if (scheduler.isPgCronAvailable()) {
    scheduler.schedulePgCronJob(PgCronJob.builder()
        .setJobName("cleanup_tokens")
        .setSchedule("0 * * * *")
        .setFunctionName("cleanup_expired_tokens")  // ✅ Validated by isValidFunctionName()
        .build());
}

// Executor job (always available)
scheduler.scheduleExecutorJob(ExecutorJob.builder()
    .setJobName("health_check")
    .setSchedule(FixedDelayScheduleConfiguration.of(Duration.ofMinutes(5)))
    .setTask(() -> performHealthCheck())
    .build());
```

⚠️ **Note**: Validation is an initial defense layer, NOT exhaustive protection. See [Security](#security).

### IOExceptionUtil

**Package**: `dk.trustworks.essentials.components.foundation`

**Class**: `dk.trustworks.essentials.components.foundation.IOExceptionUtil`

```java
try {
    performDatabaseOperation();
} catch (Exception e) {
    if (IOExceptionUtil.isIOException(e)) {
        scheduleRetry();  // Transient error
    } else {
        throw e;  // Permanent error
    }
}
```

Detects: `IOException`, `SQLTransientException`, JDBI `ConnectionException`, MongoDB `MongoSocketException`, connection error messages.

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection names used with **String concatenation** → SQL/NoSQL injection risk.
Validation methods provide an **initial defense layer**, but this is **NOT exhaustive protection**.

### Built-in Validation

#### PostgreSQL Validation

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil`

| Method | Validates |
|--------|-----------|
| `checkIsValidTableOrColumnName(String)` | Table, column, index names |
| `isValidFunctionName(String)` | Function names (simple or qualified) |
| `isValidSqlIdentifier(String)` | Simple SQL identifiers |
| `isValidQualifiedSqlIdentifier(String)` | Qualified identifiers (`schema.name`) |

**What PostgreSQL validation checks:**
- Must start with letter (a-z, A-Z) or underscore (`_`)
- Subsequent characters: letters, digits (0-9), or underscores
- Max length: 63 characters (simple) or 127 characters (qualified `schema.name`)
- Cannot be a reserved keyword (300+ PostgreSQL/SQL reserved words in `RESERVED_NAMES`)
- For qualified names: exactly one dot, both parts must be valid identifiers

#### MongoDB Validation

**Class**: `dk.trustworks.essentials.components.foundation.mongo.MongoUtil`

| Method | Validates |
|--------|-----------|
| `checkIsValidCollectionName(String)` | Collection names |

**What MongoDB validation checks:**
- Max length: 64 characters
- Cannot contain `$` or null characters
- Cannot start with `system.` (reserved for system collections)
- Must only contain letters, digits, and underscores

### What IS and IS NOT Validated

| Component | Validated | NOT Validated |
|-----------|-----------|---------------|
| PostgreSQL | Table/column/index/function names | WHERE clauses, function bodies, SQL values |
| MongoDB | Collection names | Query filters, aggregation pipelines |
| TTLManager | Table names | `whereClause`, `fullDeleteSql` |
| EssentialsScheduler | Function names, table names | Job parameters, custom SQL |

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

### Developer Responsibilities

1. **NEVER** use user input directly for names
2. Implement additional sanitization for ALL configuration values
3. Derive names only from controlled, trusted sources
4. Validate API input parameters
5. Sanitize WHERE clauses, SQL values, function names

### Safe Patterns

```java
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.mongo.MongoUtil;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;

// ❌ DANGEROUS
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName(userInput);  // SQL injection risk

// ✅ SAFE - Hardcoded only
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName("durable_queues");

// ✅ SAFE - Validate config values before use
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Throws InvalidTableOrColumnNameException
MongoUtil.checkIsValidCollectionName(collectionName);     // Throws InvalidCollectionNameException

// ✅ SAFE - Check without throwing
if (PostgresqlUtil.isValidSqlIdentifier(name)) {
    // use name
}

// ✅ SAFE - Whitelist validation
if (!ALLOWED_QUEUE_NAMES.contains(userInput)) {
    throw new ValidationException();
}
QueueName.of(userInput);
```

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

See [README Security](../components/foundation/README.md#security) for full details.

## Admin APIs

**Package**: `dk.trustworks.essentials.components.foundation.*.api`

All APIs require `principal` parameter for authorization. Throw `EssentialsSecurityException` if unauthorized.

| API | Key Methods |
|-----|-------------|
| `DBFencedLockApi` | `getAllLocks()`, `releaseLock()` |
| `DurableQueuesApi` | `getQueueNames()`, `getQueuedMessages()`, `resurrectDeadLetterMessage()`, `deleteMessage()` |
| `SchedulerApi` | `getPgCronJobs()`, `getExecutorJobs()` |
| `PostgresqlQueryStatisticsApi` | `getTopTenSlowestQueries()` (requires `pg_stat_statements`) |

## Common Patterns

### Spring Configuration Summary

```java
@Configuration
public class MessagingConfig {
    @Bean public DurableQueues durableQueues(Jdbi jdbi, UnitOfWorkFactory<JdbiUnitOfWork> uow) {
        return PostgresqlDurableQueues.builder().setJdbi(jdbi).setUnitOfWorkFactory(uow).build();
    }
    @Bean public FencedLockManager fencedLockManager(Jdbi jdbi, UnitOfWorkFactory<JdbiUnitOfWork> uow) {
        return PostgresqlFencedLockManager.builder().setJdbi(jdbi).setUnitOfWorkFactory(uow).build();
    }
    @Bean public Inboxes inboxes(DurableQueues dq, FencedLockManager flm) {
        return Inboxes.durableQueueBasedInboxes(dq, flm);
    }
    @Bean public Outboxes outboxes(DurableQueues dq, FencedLockManager flm) {
        return Outboxes.durableQueueBasedOutboxes(dq, flm);
    }
}
```

### Event-Driven Microservice Pattern

1. Create Inbox with `SingleGlobalConsumer` for ordered Kafka events
2. Create Outbox with `GlobalCompetingConsumers` for notifications
3. `@KafkaListener` → `inbox.addMessageReceived(command)`
4. Handler: `unitOfWorkFactory.usingUnitOfWork()` → process → `outbox.sendMessage()`

See [README](../components/foundation/README.md) for full examples.

## See Also

- [README](../components/foundation/README.md) - full documentation
- [postgresql-queue](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues
- [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock
- [springdata-mongo-distributed-fenced-lock](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock
- [postgresql-event-store](./LLM-postgresql-event-store.md) - Event Store using foundation patterns
- [reactive](./LLM-reactive.md) - EventBus and CommandBus abstractions
