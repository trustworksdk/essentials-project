# Foundation - LLM Reference

> See [README](../components/foundation/README.md) for detailed developer documentation.

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.foundation`
- **Purpose**: Foundational patterns for distributed systems - transactions, messaging, locking, inbox/outbox
- **Key Dependencies**: `shared`, `types`, `reactive`, `immutable`
- **Scope**: Intra-service coordination (instances of same service sharing a database)
- **Status**: WORK-IN-PROGRESS

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

| Pattern | Package | Purpose | Scope |
|---------|---------|---------|-------|
| `UnitOfWork` | `transaction` | Technology-agnostic transaction management | Any database |
| `DurableQueues` | `messaging.queue` | Point-to-point messaging, At-Least-Once delivery | Intra-service |
| `FencedLock` | `fencedlock` | Distributed locking with fence tokens | Intra-service |
| `Inbox` | `messaging.eip.store_and_forward` | Store-and-forward for incoming messages | External → Service |
| `Outbox` | `messaging.eip.store_and_forward` | Store-and-forward for outgoing messages | Service → External |
| `DurableLocalCommandBus` | `reactive.command` | CommandBus with durable sendAndDontWait | Intra-service |

**Intra-service**: Multiple instances of SAME service sharing a database. For cross-service (Sales ↔ Billing ↔ Shipping), use Kafka/RabbitMQ/Zookeeper.

## UnitOfWork (Transactions)

**Package**: `dk.trustworks.essentials.components.foundation.transaction`

### API

```java
public interface UnitOfWorkFactory<UOW extends UnitOfWork> {
    // No return value, no UnitOfWork access
    void usingUnitOfWork(CheckedRunnable runnable);

    // No return value, WITH UnitOfWork access
    void usingUnitOfWork(CheckedConsumer<UOW> consumer);

    // Return value, no UnitOfWork access
    <R> R withUnitOfWork(CheckedSupplier<R> supplier);

    // Return value, WITH UnitOfWork access
    <R> R withUnitOfWork(CheckedFunction<UOW, R> function);

    UOW getRequiredUnitOfWork();  // Throws NoActiveUnitOfWorkException if no active transaction
    Optional<UOW> getCurrentUnitOfWork();
}

public interface UnitOfWork {
    void commit();
    void rollback();
    void markAsRollbackOnly();
    void markAsRollbackOnly(Exception cause);
    UnitOfWorkStatus status();
}
```

### Implementations

| Class | Technology | Supports |
|-------|------------|----------|
| `dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory` | JDBI | Direct JDBC transactions, supports `HandleAwareUnitOfWorkFactory` |
| `dk.trustworks.essentials.components.foundation.transaction.spring.SpringTransactionAwareUnitOfWorkFactory` | Abstract Spring `UnitOfWorkFactory` | Supports `SpringTransactionAwareUnitOfWork`, Spring `@Transactional` / `TransactionTemplate` |
| `dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory` | JDBI + Spring | Inherits from `SpringTransactionAwareUnitOfWorkFactory`, supports `HandleAwareUnitOfWorkFactory` |
| `dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory` | MongoDB + Spring | Inherits from `SpringTransactionAwareUnitOfWorkFactory` |

### Nested Transaction Behavior

Each `usingUnitOfWork`/`withUnitOfWork` call creates a new `UnitOfWork` instance, but nested calls reuse the same underlying database transaction:

- **First call**: Creates `UnitOfWork` + underlying transaction (JDBI/Spring/Mongo), commits on success, rollbacks on exception
- **Nested calls**: Create new `UnitOfWork` instances that **reuse the same underlying transaction**, only outermost call commits unless the innermost marks its `UnitOfWork` as rollback-only
- **Exception in nested call**: Marks entire transaction for rollback

```java
// Creates UnitOfWork #1 + underlying JDBC transaction
unitOfWorkFactory.usingUnitOfWork(() -> {
    orderRepository.save(order);

    // Creates UnitOfWork #2, reuses SAME underlying JDBC transaction
    unitOfWorkFactory.usingUnitOfWork(() -> {
        auditLog.record("Order saved");
        // If this throws, entire JDBC transaction rolls back
    });

    // When outer completes, UnitOfWork #1 commits the underlying JDBC transaction
});
```

**Key point**: Multiple `UnitOfWork` objects, one database transaction. Write methods using `usingUnitOfWork` / `withUnitOfWork` without worrying about nesting.

### Usage

```java
// Simple - no return value
unitOfWorkFactory.usingUnitOfWork(() -> {
    orderRepository.save(order);
    durableQueues.queueMessage(queueName, event);
});

// With return value
String orderId = unitOfWorkFactory.withUnitOfWork(() -> {
    Order order = orderRepository.save(new Order(...));
    return order.getId();
});

// Explicit rollback control
unitOfWorkFactory.usingUnitOfWork(uow -> {
    try {
        riskyOperation();
    } catch (BusinessException e) {
        uow.markAsRollbackOnly(e);  // Mark rollback without throwing
    }
});

// Nested - joins outer transaction
unitOfWorkFactory.usingUnitOfWork(() -> {
    orderRepository.save(order);

    unitOfWorkFactory.usingUnitOfWork(() -> {  // Joins same transaction
        auditLog.record("Order saved");
    });
});
```

### Spring Integration

Spring-aware factories transparently join Spring transactions:

```java
@Transactional
public void processOrder(Order order) {
    // Spring transaction active - NO explicit usingUnitOfWork needed
    orderRepository.save(order);  // Spring Data participates

    // DurableQueues internally calls getRequiredUnitOfWork()
    // which auto-creates UnitOfWork joining Spring transaction
    durableQueues.queueMessage(queueName, new OrderProcessedEvent(order.getId()));
}
```

**When to use explicit `usingUnitOfWork` / `withUnitOfWork`:**
1. **No transaction exists** - starts new transaction
2. **Need return value** - use `withUnitOfWork` otherwise `usingUnitOfWork` is preferred

**Accessing the current UnitOfWork:**
```java
// Inside @Transactional or usingUnitOfWork/withUnitOfWork block
UnitOfWork uow = unitOfWorkFactory.getRequiredUnitOfWork();  // Auto-creates if needed, but only for Spring transactions (used in framework code)
uow.markAsRollbackOnly(exception);  // Manual rollback control

// For JDBI - access underlying Handle
HandleAwareUnitOfWork handleUow = (HandleAwareUnitOfWork) unitOfWorkFactory.getRequiredUnitOfWork();
Handle handle = handleUow.handle();  // Execute custom JDBI queries

// Check if transaction active (returns Optional.empty if not)
Optional<UnitOfWork> maybeUow = unitOfWorkFactory.getCurrentUnitOfWork();
```

**Note**: `getRequiredUnitOfWork()` throws `NoActiveUnitOfWorkException` if called outside a Spring managed transaction - use `usingUnitOfWork`/`withUnitOfWork` to create one first.

## DurableQueues (Messaging)

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue`

### Key Features

| Feature | Description                                                                                                                    |
|---------|--------------------------------------------------------------------------------------------------------------------------------|
| **At-Least-Once Delivery** | Messages guaranteed delivered at least once                                                                                    |
| **Competing Consumers** | Multiple consumers per queue                                                                                                   |
| **Dead Letter Queue** | Failed messages isolated for review (same `QueueName` with the message marked as a dead-letter-message (aka. a poison-message) |
| **Ordered Messages** | Delivered in sequence per key                                                                                                  |
| **Delayed Delivery** | Schedule messages for future delivery                                                                                          |
| **Redelivery Policies** | Fixed/linear/exponential backoff                                                                                               |

**Design Requirement**: Handlers/Consumers MUST be idempotent (can receive duplicates).

### DurableQueues API

```java
public interface DurableQueues {
    // Queue messages
    QueueEntryId queueMessage(QueueName queueName, Object message);
    QueueEntryId queueMessage(QueueName queueName, Object message, Duration deliverAfterDelay);
    List<QueueEntryId> queueMessages(QueueName queueName, List<Object> messages);

    // Consume
    DurableQueueConsumer consumeFromQueue(ConsumeFromQueue config);

    // Dead Letter operations
    Optional<QueuedMessage> resurrectDeadLetterMessage(QueueEntryId id, Duration delay);
    Optional<QueuedMessage> markAsDeadLetterMessage(QueueEntryId id, String reason);
    List<QueuedMessage> getDeadLetterMessages(QueueName queueName, QueueingSortOrder order, long startIndex, long pageSize);
}
```

### Pattern Matching Handler

Using `dk.trustworks.essentials.components.foundation.messaging.queue.PatternMatchingQueuedMessageHandler` that implements the `dk.trustworks.essentials.components.foundation.messaging.MessageHandler` interface:

```java
var consumer = durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderProcessing"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(200))
            .setMaximumNumberOfRedeliveries(5)
            .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(
                ValidationException.class))
            .build())
        .setParallelConsumers(3)
        .setQueueMessageHandler(new PatternMatchingQueuedMessageHandler() {
            @MessageHandler
            void handle(ProcessOrderCommand cmd) {
                // Handle command
            }

            @MessageHandler
            void handle(CancelOrderCommand cmd, QueuedMessage msg) {
                // Access full message context
            }

            @Override
            protected void handleUnmatchedMessage(QueuedMessage msg) {
                log.warn("Unmatched: {}", msg.getPayload().getClass());
            }
        })
        .build());
```

### RedeliveryPolicy

Class: dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy

| Strategy | Formula | Use Case |
|----------|---------|----------|
| `fixedBackoff()` | Same delay every retry | Simple retries |
| `linearBackoff()` | Delay increases linearly | Gradual backoff |
| `exponentialBackoff()` | Delay doubles each retry | External service recovery |

```java
// Fixed: 500ms delay, max 5 retries
RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5)

// Linear: starts 1s, increases 1s per retry, max 30s delay, max 10 retries
RedeliveryPolicy.linearBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30), 10)

// Exponential: starts 500ms, doubles each time, max 1min delay, max 8 retries
RedeliveryPolicy.exponentialBackoff(
    Duration.ofMillis(500),  // initialRedeliveryDelay
    Duration.ofMillis(500),  // followupRedeliveryDelay
    2.0,                     // multiplier
    Duration.ofMinutes(1),   // max delay
    8                        // max retries
)
```

### MessageDeliveryErrorHandler

Interface: dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler

```java
// Always retry (default)
MessageDeliveryErrorHandler.alwaysRetry()

// Immediately move to DLQ on specific exceptions
MessageDeliveryErrorHandler.stopRedeliveryOn(
    ValidationException.class,
    IllegalArgumentException.class
)
```

### Dead Letter Queue (Dead Letter Message / Poison Message)

```java
// Query DLQ
List<QueuedMessage> deadLetters = durableQueues.getDeadLetterMessages(
    queueName, QueueingSortOrder.ASC, 0, 100);

// Resurrect for redelivery
durableQueues.resurrectDeadLetterMessage(queueEntryId, Duration.ofSeconds(10));

// Manual mark as DLQ
durableQueues.markAsDeadLetterMessage(queueEntryId, "Invalid customer data");
```

### Interceptors

Interface: dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueuesInterceptor

Add cross-cutting behavior (logging, metrics, tracing):

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "OrderService"))
    .addInterceptor(new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry))
    .build();
```

**Built-in Interceptors:**

| Interceptor | Package | Metrics |
|-------------|---------|---------|
| `DurableQueuesMicrometerInterceptor` | `messaging.queue.micrometer` | Queue size gauges, counters (processed, handled, retries, DLQ) |
| `DurableQueuesMicrometerTracingInterceptor` | `messaging.queue.micrometer` | Distributed tracing via Micrometer Observation |
| `RecordExecutionTimeDurableQueueInterceptor` | `interceptor.micrometer` | Operation execution time |

### Implementations

| Implementation | Module |
|---------------|--------|
| `dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues` | [LLM-postgresql-queue.md](./LLM-postgresql-queue.md) |
| `dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues` | [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) |

## FencedLock (Distributed Locking)

**Package**: `dk.trustworks.essentials.components.foundation.fencedlock`

Based on [Martin Kleppmann's fenced locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html).

### The Problem: Split Brain

```
Timeline:
Instance A: [Acquire Lock]──[Processing]──[GC PAUSE..........]──[Continues!]
Instance B:                        [Lock Timeout]──[Acquire]──[Processing]
                                        ↑
                                   SPLIT BRAIN - Both active!
```

Lock holder doesn't know lock was taken over.

### The Solution: Fence Tokens

**Fence token**: Monotonically increasing counter issued on lock acquire.
**Lock confirmation**: Periodic heartbeat that updates the last confirmed timestamp to keep the lock alive without changing the token.

Downstream systems reject stale tokens:

```java
// Lock holder: pass token to downstream
FencedLock lock = lockManager.acquireLock(LockName.of("ProcessOrders"));
long fenceToken = lock.getCurrentToken();
orderService.processOrder(orderId, fenceToken);

// Downstream: validate token
if (lastSeenToken != null && fenceToken < lastSeenToken) {
    throw new StaleTokenException();
}
lastSeenTokens.put(orderId, fenceToken);

// Database: include token in WHERE clause
UPDATE orders
SET status = :status, last_fence_token = :token
WHERE id = :id AND last_fence_token <= :token
```

### API

```java
public interface FencedLockManager extends Lifecycle {
    // Blocks until lock is acquired
    FencedLock acquireLock(LockName lockName);

    // Non-blocking attempt to acquire lock
    Optional<FencedLock> tryAcquireLock(LockName lockName);

    // Attempt to acquire lock with timeout
    Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout);

    // Acquire lock asynchronously, callback invoked on acquire/release
    void acquireLockAsync(LockName lockName, LockCallback callback);

    // Check if this lock manager holds the specified lock
    boolean isLockAcquired(LockName lockName);

    // Get unique ID for this lock manager instance
    String getLockManagerInstanceId();
}

public interface FencedLock extends AutoCloseable {
    // Get monotonic fence token for stale lock detection
    long getCurrentToken();

    // Release the lock
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
// Synchronous - try-with-resources
try (FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"))) {
    performCriticalWork();
}

// Asynchronous - PREFERRED for long-running tasks
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

### Implementations

| Implementation | Module |
|---------------|--------|
| `dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager` | [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) |
| `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager` | [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) |

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

**Configuration:**

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
        void handle(ProcessOrderCommand cmd) {
            processOrder(cmd);
        }

        @Override
        protected void handleUnmatchedMessage(Message msg) {
            log.warn("Unknown message type");
        }
    });

// Kafka listener
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
                        DurableQueues → Outbox Relay → Kafka
```

**Configuration:**

```java
Outbox kafkaOutbox = outboxes.getOrCreateOutbox(
    OutboxConfig.builder()
        .setOutboxName(OutboxName.of("OrderService:KafkaEvents"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 10))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
        .setNumberOfParallelMessageConsumers(1)
        .build(),
    message -> {
        var event = message.getPayload();
        kafkaTemplate.send("order-events", event);
    });

// Usage - atomic with database update
unitOfWorkFactory.usingUnitOfWork(() -> {
    Order order = orderRepository.save(new Order(...));
    kafkaOutbox.sendMessage(new OrderCreatedEvent(order.getId()));
});
```

### Message Consumption Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `SingleGlobalConsumer` | One active consumer in cluster (uses FencedLock) | Ordered message processing |
| `GlobalCompetingConsumers` | Multiple consumers compete | Unordered, max throughput |

## Ordered Message Processing

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue`

### OrderedMessage Structure

```java
public class OrderedMessage extends Message {
    public String getKey();   // Entity ID (e.g., "Order-123")
    public long getOrder();   // Sequential position - relative to the Entity ID (0, 1, 2...)

    public static OrderedMessage of(Object payload, String key, long order);
}
```

Messages with same `key` processed in `order` sequence. Messages with different `key`s can be processed in parallel.

### Single-Node Ordering

Automatic - no configuration needed:

```java
durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderEvents"))
        .setParallelConsumers(10)  // Ordering still maintained per key
        .setQueueMessageHandler(handler)
        .build());
```

**How it works:**

Depending on the `DurableQueues` implementation, ordering is maintained using different fetcher approaches:

**Centralized Fetcher** (PostgreSQL with `useCentralizedMessageFetcher=true`):
- Single polling thread fetches messages
- Tracks `inProcessOrderedKeys` to prevent concurrent processing of same key
- Dispatches to worker threads only if key not in-process

**Traditional Fetcher** (MongoDB, or PostgreSQL with `useCentralizedMessageFetcher=false`):
- Each consumer thread tracks in-process keys via `orderedMessageDeliveryThreads`
- Fetch queries exclude keys currently being processed

**Result:** Sequential per key, parallel across keys

### Multi-Node Ordering

**Problem**: When running across multiple nodes (cluster):
- Each node independently polls database
- Without coordination, messages for same key can be processed out of order
- Node 1 might fetch `Order-123:event-5` while Node 2 fetches `Order-123:event-4`
- In-process key tracking only works within a single node, not cluster-wide

**Solution**: Use Inbox/Outbox with `SingleGlobalConsumer`:

```java
Inbox inbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .setInboxName(InboxName.of("OrderEventsInbox"))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)  // Required!
        .setNumberOfParallelMessageConsumers(10)  // Still uses threads on active node
        .setRedeliveryPolicy(redeliveryPolicy)
        .build(),
    messageHandler);
```

**How it works:**
1. **FencedLock Coordination**: Ensures only ONE node actively consumes
2. **Active Node**: The node holding the lock uses its message fetcher (Centralized or Traditional) to maintain ordering
3. **Multi-threading**: Active node can still use multiple parallel threads (e.g., 10)
4. **Failover**: Lock timeout → another node takes over

### Comparison

| Scenario | Configuration | Ordering | Throughput |
|----------|--------------|----------|------------|
| Single node, unordered | `GlobalCompetingConsumers` | ❌ None | Highest |
| Single node, ordered | `GlobalCompetingConsumers` | ✅ Per key | High |
| Multi-node, unordered | `GlobalCompetingConsumers` | ❌ None | Highest (distributed) |
| Multi-node, ordered | `SingleGlobalConsumer` + FencedLock | ✅ Per key, cluster-wide | Medium (one active) |

**Best Practice**: Design for idempotency (required for At-Least-Once delivery anyway).

## DurableLocalCommandBus

**Package**: `dk.trustworks.essentials.components.foundation.reactive.command`

Specialization of `CommandBus` (from [reactive](./LLM-reactive.md)) that uses DurableQueues for `sendAndDontWait`.

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

// Synchronous (returns result) - inherited from CommandBus
OrderId result = commandBus.send(new CreateOrderCommand(...));
```

## Utilities

### JSONSerializer

**Package**: `dk.trustworks.essentials.components.foundation.json`

```java
JSONSerializer serializer = new JacksonJSONSerializer(objectMapper);

String json = serializer.serialize(order);
byte[] bytes = serializer.serializeAsBytes(order);
Order order = serializer.deserialize(json, Order.class);
Object event = serializer.deserialize(json, "com.example.OrderCreatedEvent");
```

### LifecycleManager

**Package**: `dk.trustworks.essentials.components.foundation.lifecycle`

Integrates `Lifecycle` beans with Spring ApplicationContext:

```java
@Bean
public DefaultLifecycleManager lifecycleManager() {
    return new DefaultLifecycleManager(
        context -> { /* optional callback */ },
        true  // auto-start Lifecycle beans
    );
}
```

Components like `FencedLockManager`, `DurableQueues` implement `Lifecycle`.

### PostgreSQL Utilities

**Package**: `dk.trustworks.essentials.components.foundation.postgresql`

#### PostgresqlUtil

```java
// Validate table/column/index names (initial defense layer)
PostgresqlUtil.checkIsValidTableOrColumnName("orders");  // OK
PostgresqlUtil.checkIsValidTableOrColumnName("SELECT");  // Throws - reserved keyword
PostgresqlUtil.checkIsValidTableOrColumnName("123_table");  // Throws - starts with digit

// Boolean validation variants
PostgresqlUtil.isValidSqlIdentifier("valid_identifier");  // true, max 63 chars
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.table");  // true, max 127 chars
PostgresqlUtil.isValidFunctionName("my_function");  // true

// Other utilities
int version = PostgresqlUtil.getServiceMajorVersion(handle);
boolean hasPgCron = PostgresqlUtil.isPGExtensionAvailable(handle, "pg_cron");
```

**Validation Rules:**
- Start with letter (A-Z) or underscore (_)
- Subsequent: letters, digits (0-9), underscores
- No reserved SQL/PostgreSQL keywords
- Max 63 chars (simple) or 127 chars (qualified)

#### ListenNotify

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

**Package**: `dk.trustworks.essentials.components.foundation.mongo`

```java
// Validate collection names (initial defense layer)
MongoUtil.checkIsValidCollectionName("orders");  // OK
MongoUtil.checkIsValidCollectionName("system.users");  // Throws - starts with system.
MongoUtil.checkIsValidCollectionName("my$collection");  // Throws - contains $

// Detect write conflicts for retry logic
if (MongoUtil.isWriteConflict(exception)) {
    // Retry operation
}
```

**Validation Rules:**
- Max 64 characters
- No `$` or null characters
- No `system.` prefix
- Only letters, digits, underscores

### TTLManager

**Package**: `dk.trustworks.essentials.components.foundation.ttl`

Handles scheduled deletion/archival of expired data (Time-To-Live management). Coordinates with `EssentialsScheduler` to execute TTL jobs using pg_cron or executor-based scheduling.

#### Usage

```java
PostgresqlTTLManager ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
ttlManager.start();

ttlManager.scheduleTTLJob(TTLJobDefinition.builder()
    .setAction(DefaultTTLJobAction.builder()
        .setTableName("audit_logs")  // ✅ Validated
        .setWhereClause("created_at < NOW() - INTERVAL '90 days'")  // ⚠️ NOT validated
        .build())
    .setSchedule(CronScheduleConfiguration.of("0 3 * * *"))  // Daily at 3 AM
    .build());
```

#### TTLJobAction Options

| Option | Description | Security                                                    |
|--------|-------------|-------------------------------------------------------------|
| `tableName` | Target table for deletion | ✅ Validated via `PostgresqlUtil` as first life of defense   |
| `whereClause` | WHERE condition for deletion | ⚠️ **NOT validated** - must sanitize to avoid SQL injection |
| `fullDeleteSql` | Complete DELETE statement | ⚠️ **NOT validated** - must sanitize to avoid SQL injection |

**Security Warning**: `whereClause` and `fullDeleteSql` are executed directly without validation. Only use trusted sources - never allow external input.

### EssentialsScheduler

**Package**: `dk.trustworks.essentials.components.foundation.scheduler`

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
        .setFunctionName("cleanup_expired_tokens")
        .build());
}

// Executor job (always available)
scheduler.scheduleExecutorJob(ExecutorJob.builder()
    .setJobName("health_check")
    .setSchedule(FixedDelayScheduleConfiguration.of(Duration.ofMinutes(5)))
    .setTask(() -> performHealthCheck())
    .build());
```

### IOExceptionUtil

**Package**: `dk.trustworks.essentials.components.foundation`

```java
try {
    performDatabaseOperation();
} catch (Exception e) {
    if (IOExceptionUtil.isIOException(e)) {
        // Transient error - safe to retry
        scheduleRetry();
    } else {
        // Permanent error - don't retry
        throw e;
    }
}
```

Detects: `IOException`, `SQLTransientException`, JDBI `ConnectionException`, MongoDB `MongoSocketException`, connection error messages.

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Component allows customization of table/column/index/function/collection-names that are used with **String concatenation** → SQL/NoSQL injection risk.  
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL/NoSQL injection.

### Critical Security Warnings

⚠️ **SQL Injection Risk**

Validation methods provide **ONLY an initial defense layer**, NOT exhaustive protection.

| Component | Validated                                                             | NOT Validated |
|-----------|-----------------------------------------------------------------------|---------------|
| PostgreSQL | Table/column/index/function names via `checkIsValidTableOrColumnName` | WHERE clauses, function bodies, SQL values |
| MongoDB | Collection names via `checkIsValidCollectionName`                     | Query filters, aggregation pipelines |
| TTLManager | Table names                                                           | `whereClause`, `fullDeleteSql` |

**Developer Responsibilities:**
1. **NEVER** use user input directly for table/column/index/function- and collection names
2. Implement additional sanitization for ALL configuration values
3. Derive names only from controlled, trusted sources
4. Validate API input parameters
5. Sanitize WHERE clauses, SQL values, function names

```java
// ❌ DANGEROUS
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName(userInput);  // SQL injection risk due to user input

// ❌ DANGEROUS
QueueName.of(userInput);  // Depends on implementation

// ✅ SAFE
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName("durable_queues");  // Fixed string

// ✅ SAFE - whitelist validation
if (!ALLOWED_QUEUE_NAMES.contains(userInput)) {
    throw new ValidationException();
}
QueueName.of(userInput);
```

See [README Security](../components/foundation/README.md#security) for full details.

## Admin APIs

**Package**: `dk.trustworks.essentials.components.foundation.*.api`

All APIs require `principal` parameter for authorization. Throw `EssentialsSecurityException` if unauthorized.

### DBFencedLockApi

Interface: dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi

```java
List<ApiDBFencedLock> getAllLocks(Object principal);
boolean releaseLock(Object principal, LockName lockName);
```

### DurableQueuesApi

Interface: dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi

```java
Set<QueueName> getQueueNames(Object principal);
Optional<ApiQueuedMessage> getQueuedMessage(Object principal, QueueEntryId queueEntryId);
Optional<ApiQueuedMessage> resurrectDeadLetterMessage(Object principal, QueueEntryId id, Duration delay);
Optional<ApiQueuedMessage> markAsDeadLetterMessage(Object principal, QueueEntryId id);
boolean deleteMessage(Object principal, QueueEntryId queueEntryId);
List<ApiQueuedMessage> getQueuedMessages(Object principal, QueueName queueName, QueueingSortOrder order, long start, long pageSize);
```

### SchedulerApi

Interface: dk.trustworks.essentials.components.foundation.scheduler.api.SchedulerApi

```java
List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize);
long getTotalPgCronJobs(Object principal);
List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize);
```

### PostgresqlQueryStatisticsApi

Interface: dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi

```java
List<ApiQueryStatistics> getTopTenSlowestQueries(Object principal);
```

Requires `pg_stat_statements` extension.

## Common Patterns

### Complete Spring Configuration

```java
@Configuration
public class MessagingConfig {

    @Bean
    public DurableQueues durableQueues(Jdbi jdbi, UnitOfWorkFactory<JdbiUnitOfWork> uowFactory) {
        return PostgresqlDurableQueues.builder()
            .setJdbi(jdbi)
            .setUnitOfWorkFactory(uowFactory)
            .setSharedQueueTableName("durable_queues")  // Fixed string - leave out for default
            .build();
    }

    @Bean
    public FencedLockManager fencedLockManager(Jdbi jdbi, UnitOfWorkFactory<JdbiUnitOfWork> uowFactory) {
        return PostgresqlFencedLockManager.builder()
            .setJdbi(jdbi)
            .setUnitOfWorkFactory(uowFactory)
            .setFencedLocksTableName("fenced_locks")  // Fixed string - leave out for default
            .setLockManagerInstanceId("instance-" + UUID.randomUUID()) // Unique per instance - leave out to use the machines hostname
            .build();
    }

    @Bean
    public Inboxes inboxes(DurableQueues durableQueues, FencedLockManager fencedLockManager) {
        return Inboxes.durableQueueBasedInboxes(durableQueues, fencedLockManager);
    }

    @Bean
    public Outboxes outboxes(DurableQueues durableQueues, FencedLockManager fencedLockManager) {
        return Outboxes.durableQueueBasedOutboxes(durableQueues, fencedLockManager);
    }
}
```

### Event-Driven Microservice Pattern

```java
@Service
public class OrderService {
    private final Inbox kafkaInbox;
    private final Outbox notificationOutbox;

    @PostConstruct
    public void setup() {
        // Inbox: Kafka → Service
        kafkaInbox = inboxes.getOrCreateInbox(
            InboxConfig.builder()
                .setInboxName(InboxName.of("OrderService:KafkaEvents"))
                .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                .setNumberOfParallelMessageConsumers(5)
                .setRedeliveryPolicy(RedeliveryPolicy.exponentialBackoff()
                    .setInitialRedeliveryDelay(Duration.ofMillis(100))
                    .setRedeliveryDelayMultiplier(2.0)
                    .setMaximumNumberOfRedeliveries(5)
                    .build())
                .build(),
            new PatternMatchingMessageHandler() {
                @MessageHandler
                void handle(ProcessOrderCommand cmd) {
                    handleProcessOrder(cmd);
                }

                @Override
                protected void handleUnmatchedMessage(Message msg) {
                    log.warn("Unmatched: {}", msg.getPayload().getClass());
                }
            });

        // Outbox: Service → Kafka
        notificationOutbox = outboxes.getOrCreateOutbox(
            OutboxConfig.builder()
                .setOutboxName(OutboxName.of("OrderService:Notifications"))
                .setMessageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                .setNumberOfParallelMessageConsumers(2)
                .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofSeconds(1), 3))
                .build(),
            new PatternMatchingMessageHandler() {
                @MessageHandler
                void handle(OrderProcessedNotification notification) {
                    kafkaTemplate.send("notifications", notification);
                }
            });
    }

    @KafkaListener(topics = "order-events")
    public void handleKafkaEvent(OrderEvent event) {
        kafkaInbox.addMessageReceived(toCommand(event));
    }

    private void handleProcessOrder(ProcessOrderCommand cmd) {
        unitOfWorkFactory.usingUnitOfWork(() -> {
            Order order = processOrder(cmd);
            orderRepository.save(order);

            // Same transaction - atomic with database update
            notificationOutbox.sendMessage(new OrderProcessedNotification(order.getId()));
        });
    }
}
```

## See Also

- [README](../components/foundation/README.md) - full documentation
- [postgresql-queue](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues implementation
- [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues implementation
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock implementation
- [springdata-mongo-distributed-fenced-lock](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock implementation
- [postgresql-event-store](./LLM-postgresql-event-store.md) - Event Store using foundation patterns
- [reactive](./LLM-reactive.md) - EventBus and CommandBus abstractions
