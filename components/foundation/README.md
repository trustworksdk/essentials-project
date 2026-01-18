# Essentials Components - Foundation

> **NOTE:** **The library is WORK-IN-PROGRESS**

This module provides foundational patterns for distributed systems: durable queues, inbox/outbox, fenced locks, transactions, and command handling.

**LLM Context:** [LLM-foundation.md](../../LLM/LLM-foundation.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [UnitOfWork (Transactions)](#unitofwork--unitofworkfactory-transactions)
- [FencedLock (Distributed Locking)](#fencedlock-distributed-locking)
- [DurableQueues (Messaging)](#durablequeues-messaging)
  - [DurableQueuesInterceptor](#durablequeuesinterceptor)
- [Inbox Pattern](#inbox-pattern)
- [Outbox Pattern](#outbox-pattern)
- [DurableLocalCommandBus](#durablelocalcommandbus)
- [Ordered Message Processing](#ordered-message-processing)
- [JSONSerializer](#jsonserializer)
- [LifecycleManager](#lifecyclemanager)
- [PostgreSQL Utilities](#postgresql-utilities)
- [MongoDB Utilities](#mongodb-utilities)
- [TTLManager](#ttlmanager)
- [EssentialsScheduler](#essentialsscheduler)
- [IOExceptionUtil](#ioexceptionutil)
- [Admin APIs](#admin-apis)
- [Implementation Modules](#implementation-modules)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection names that are used with **String concatenation** → SQL/NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL/NoSQL injection.

> **Your Responsibility:**
> - Never use unsanitized user input for table, column, function, index, or collection names
> - Validate all configuration values derived from external sources
> - Use only controlled, trusted sources for naming configuration

**Safe Pattern:**
```java
// Example configuring PostgreSQL table names
var durableQueues = PostgresqlDurableQueues.builder()

// ❌ DANGEROUS - user input could enable SQL injection
.setSharedQueueTableName(userInput + "_queue")

// ✅ SAFE
.setSharedQueueTableName("message_queue")  // Hardcoded only

// ⚠️ Validate if from config
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Basic validation (not exhaustive)
MongoUtil.checkIsValidCollectionName(collectionName);  // Basic validation (not exhaustive)
```

⚠️ Please see the **Security** notices for [components README.md](../README.md#security), as well as **Security** notices for the individual components:
- [foundation](../foundation/README.md#security)
- [foundation-types](../foundation-types/README.md#security)
- [postgresql-event-store](../postgresql-event-store/README.md#security)
- [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md#security)
- [postgresql-queue](../postgresql-queue/README.md#security)
- [eventsourced-aggregates](../eventsourced-aggregates/README.md#security)
- [kotlin-eventsourcing](../kotlin-eventsourcing/README.md#security)
- [springdata-mongo-queue](../springdata-mongo-queue/README.md#security)
- [springdata-mongo-distributed-fenced-lock](../springdata-mongo-distributed-fenced-lock/README.md#security)

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

---

## `UnitOfWork` / `UnitOfWorkFactory` (Transactions)

**Package:** `dk.trustworks.essentials.components.foundation.transaction`

### Why

The `UnitOfWork` pattern provides technology-agnostic transaction management, ensuring multiple operations execute within a single transactional boundary.  
This is essential for maintaining data consistency across different persistence technologies.

### Key Capabilities

- **Technology Agnostic**: Works with JDBI, MongoDB, Spring, etc.
- **Nested Transaction Support**: Automatically joins existing transactions (see below)
- **Automatic Resource Management**: Handles commit/rollback automatically
- **Exception Safety**: Automatic rollback on exceptions

### Nested Transaction Behavior

The `UnitOfWorkFactory`'s `withUnitOfWork(CheckedFunction)` and `usingUnitOfWork(CheckedConsumer)` methods handle nested calls intelligently:

**When NO existing `UnitOfWork` exists:**
1. A new `UnitOfWork` is created and started
2. Your code executes within this transaction
3. On successful completion → `UnitOfWork` is **committed**
4. On exception → `UnitOfWork` is **rolled back** and exception is re-thrown as `UnitOfWorkException`

**When an existing `UnitOfWork` exists (nested call):**
1. The nested call **joins** the existing `UnitOfWork`'s underlying Transaction (no new transaction created)
2. Your code executes within the **same** underlying transaction as the outer call
3. On successful completion → the nested `UnitOfWork` is **NOT committed** (this is deferred to the outermost `UnitOfWork`)
4. On exception → `UnitOfWork` is **marked as rollback-only** (via `markAsRollbackOnly(e)`)
   - The exception is re-thrown as a `UnitOfWorkException`, allowing the outer `UnitOfWork` logic to handle it
   - If the outer `UnitOfWork` attempts to commit, it will instead rollback due to the rollback-only flag

This behavior enables composable transactional methods where inner methods don't need to know if they're running in an existing transaction.

### `UnitOfWorkFactory` Implementations

| Class | Use Case                                                            |
|-------|---------------------------------------------------------------------|
| `JdbiUnitOfWorkFactory` | Direct JDBI transactions (supports `HandleAwareUnitOfWork`)         |
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | Spring-managed JDBI transactions (supports `HandleAwareUnitOfWork`) |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | Spring-managed MongoDB transactions                                 |

### Spring Transaction Integration

The Spring-aware `UnitOfWorkFactory` implementations provide **transparent integration** with Spring's transaction management:

| Implementation | Module | Description |
|----------------|--------|-------------|
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | `foundation` | JDBI with Spring transaction coordination |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | `foundation` | MongoDB with Spring transaction coordination |
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | `spring-postgresql-event-store` | EventStore with Spring transaction coordination |

**How it works:**

1. **Joining existing Spring transactions**: When a `UnitOfWork` is created inside an existing Spring-managed transaction (e.g., from a `@Transactional` method), the `UnitOfWork` **transparently joins** that transaction. No new transaction is started.

2. **Starting Spring transactions**: When a `UnitOfWork` is created without an existing Spring transaction, it will **start and coordinate** a new Spring transaction automatically.

3. **Bidirectional integration**: This works both ways:
   - Framework code using `UnitOfWork` can participate in application-level `@Transactional` boundaries
   - Application code using `@Transactional` can call framework code that uses `UnitOfWork` internally

#### Internal Mechanism: `getRequiredUnitOfWork()`

The `getRequiredUnitOfWork()` method is used by framework components (like `DurableQueues`, `EventStore`) to obtain a UnitOfWork:

1. Checks if a Spring transaction is active
2. If **YES** → calls `getOrCreateNewUnitOfWork()` which creates a `UnitOfWork` if one doesn't exist
3. If **NO** → throws `NoActiveUnitOfWorkException`

```java
// From SpringTransactionAwareUnitOfWorkFactory
public UOW getRequiredUnitOfWork() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new NoActiveUnitOfWorkException();
    }
    return getOrCreateNewUnitOfWork();
}
```

This means framework components work correctly whether called from:
- `usingUnitOfWork()` / `withUnitOfWork()` blocks (which start a Spring transaction if needed)
- `@Transactional` methods (Spring transaction already active)
- `TransactionTemplate` callbacks (Spring transaction already active)

#### Internal Mechanism: `getOrCreateNewUnitOfWork()`

When `usingUnitOfWork()`, `withUnitOfWork()`, or `getRequiredUnitOfWork()` is called, the factory's `getOrCreateNewUnitOfWork()` method determines what to do based on the current transaction state:

**Scenario 1: No Spring transaction active**
```
getOrCreateNewUnitOfWork() called
    ↓
TransactionSynchronizationManager.isActualTransactionActive() → NO
    ↓
Create new Spring transaction via transactionManager.getTransaction()
    ↓
Create UnitOfWork via createUnitOfWorkForFactoryManagedTransaction()
    ↓
Bind UnitOfWork to TransactionSynchronizationManager
    ↓
Return UnitOfWork (factory manages commit/rollback)
```

**Scenario 2: Spring transaction active, no `UnitOfWork` exists**
```
getOrCreateNewUnitOfWork() called (e.g., from @Transactional method)
    ↓
TransactionSynchronizationManager.isActualTransactionActive() → YES
    ↓
TransactionSynchronizationManager.getResource(unitOfWorkType) → NULL
    ↓
Create UnitOfWork via createUnitOfWorkForSpringManagedTransaction()
    ↓
Bind UnitOfWork to TransactionSynchronizationManager
    ↓
Return UnitOfWork (Spring manages commit/rollback)
```

**Scenario 3: Spring transaction active, `UnitOfWork` already exists**
```
getOrCreateNewUnitOfWork() called (nested call)
    ↓
TransactionSynchronizationManager.isActualTransactionActive() → YES
    ↓
TransactionSynchronizationManager.getResource(unitOfWorkType) → existing UnitOfWork
    ↓
Return existing UnitOfWork (joins current transaction)
```

This mechanism enables framework components (like `DurableQueues`, `EventStore`) to work seamlessly whether called from:
- Explicit `usingUnitOfWork()` blocks
- `@Transactional` methods
- `TransactionTemplate` callbacks

#### No Explicit `UnitOfWork` Required in @Transactional Methods

When your code runs inside a Spring-managed transaction (`@Transactional` or `TransactionTemplate`), you do **NOT** need to explicitly wrap framework component calls with `usingUnitOfWork()`:

```java
@Service
public class OrderService {
    private final DurableQueues durableQueues;
    private final OrderRepository orderRepository;  // Spring Data

    @Transactional
    public void processOrder(Order order) {
        // Spring transaction is active - NO explicit usingUnitOfWork() needed!

        // Spring Data repository participates in the Spring transaction
        orderRepository.save(order);

        // DurableQueues internally calls getRequiredUnitOfWork() which auto-creates
        // a UnitOfWork joining the existing Spring transaction
        durableQueues.queueMessage(queueName, new OrderProcessedEvent(order.getId()));

        // All operations commit or rollback together
    }
}
```

**Why this works:** Framework components internally call `unitOfWorkFactory.getRequiredUnitOfWork()`, which detects the active Spring transaction and automatically creates a `UnitOfWork` that participates in it.

#### When to Use Explicit `usingUnitOfWork()`

Use `usingUnitOfWork()` or `withUnitOfWork()` when:

1. **No Spring transaction exists** - to start a new transaction:
   ```java
   // Not inside @Transactional - must explicitly start transaction
   public void queueEvent(OrderEvent event) {
       unitOfWorkFactory.usingUnitOfWork(() -> {
           durableQueues.queueMessage(queueName, event);
       });
   }
   ```

2. **You need direct `UnitOfWork` access** - for custom operations or transaction control:
   ```java
   @Transactional
   public void complexOperation(Order order) {
       // Get the UnitOfWork for direct access
       var uow = unitOfWorkFactory.getRequiredUnitOfWork();
       // ... use uow.handle() for custom JDBI queries
   }
   ```

#### Accessing the Current UnitOfWork

Regardless of how the transaction was started, you can access the current `UnitOfWork`:

```java
// Get current UnitOfWork (returns Optional - empty if no transaction active)
Optional<UnitOfWork> currentUow = unitOfWorkFactory.getCurrentUnitOfWork();

// Get required UnitOfWork (throws NoActiveUnitOfWorkException if none exists)
UnitOfWork uow = unitOfWorkFactory.getRequiredUnitOfWork();

// For JDBI-based factories (HandleAwareUnitOfWork), access the Handle
HandleAwareUnitOfWork handleUow = (HandleAwareUnitOfWork) unitOfWorkFactory.getRequiredUnitOfWork();
Handle handle = handleUow.handle();
```

This is useful when you need to:
- Execute custom JDBI queries within the same transaction
- Access transaction metadata or status
- Perform conditional logic based on transaction state
- Register lifecycle callbacks on the current transaction

This seamless integration ensures that **all database operations** (whether from Spring Data, JDBI, or Essentials components) participate in the **same transaction boundary**, maintaining data consistency.

### Method Variants

Both `usingUnitOfWork` and `withUnitOfWork` have two overloaded variants:

| Method | Lambda Signature | Use Case |
|--------|------------------|----------|
| `usingUnitOfWork(CheckedRunnable)` | `() -> { ... }` | No return value, no `UnitOfWork` access needed |
| `usingUnitOfWork(CheckedConsumer<UOW>)` | `(uow) -> { ... }` | No return value, need `UnitOfWork` access (e.g., for `markAsRollbackOnly`) |
| `withUnitOfWork(CheckedSupplier<R>)` | `() -> { return ...; }` | Return value, no `UnitOfWork` access needed |
| `withUnitOfWork(CheckedFunction<UOW, R>)` | `(uow) -> { return ...; }` | Return value, need `UnitOfWork` access |

Use the variant **with** `UnitOfWork` parameter when you need to:
- Call `uow.markAsRollbackOnly(exception)` to mark the transaction for rollback without throwing
- Access `uow.status()` to check the current transaction status
- Access to `handle()` method when using `HandleAwareUnitOfWork`
- Use other `UnitOfWork` methods

> **Note:** Even without the `UnitOfWork` parameter, you can always retrieve the current `UnitOfWork` via `unitOfWorkFactory.getRequiredUnitOfWork()` from within your lambda.

### Avoid Using `getOrCreateNewUnitOfWork` Directly

> **⚠️ Warning:** Prefer `usingUnitOfWork` or `withUnitOfWork` over calling `getOrCreateNewUnitOfWork()` directly.
>
> If you use `getOrCreateNewUnitOfWork()`, **you are responsible** for:
> - Calling `unitOfWork.commit()` on successful completion
> - Calling `unitOfWork.rollback(exception)` when exceptions occur
> - Ensuring proper cleanup in all code paths (including exceptions)
>
> The `usingUnitOfWork` and `withUnitOfWork` methods handle all of this automatically, including proper nested transaction semantics.

```java
// ❌ Avoid - manual management required
UnitOfWork uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
try {
    doWork();
    uow.commit();  // Easy to forget!
} catch (Exception e) {
    uow.rollback(e);  // Easy to forget!
    throw e;
}

// ✅ Preferred - automatic management
unitOfWorkFactory.usingUnitOfWork(() -> {
    doWork();
});
```

### Usage

```java
// Without return value
unitOfWorkFactory.usingUnitOfWork(() -> {
    orderRepository.save(order);
    durableQueues.queueMessage(queueName, event);
});

// With return value
String orderId = unitOfWorkFactory.withUnitOfWork(() -> {
    Order order = orderRepository.save(new Order(...));
    return order.getId();
});

// Nested calls automatically join the outer transaction
unitOfWorkFactory.usingUnitOfWork(() -> {
    orderRepository.save(order);

    // This nested call joins the same transaction
    unitOfWorkFactory.usingUnitOfWork(() -> {
        auditLog.record("Order saved");
        // If this throws, the outer UnitOfWork/transaction is marked rollback-only
    });

    // Both operations commit or rollback together
});

// With explicit UnitOfWork access for manual rollback control
unitOfWorkFactory.usingUnitOfWork((uow) -> {
    try {
        orderRepository.save(order);
        riskyOperation();
    } catch (BusinessException e) {
        uow.markAsRollbackOnly(e); // Mark for rollback but continue execution
    }
});
```

### Exception Handling and Rollback

Throwing an exception from within `usingUnitOfWork` or `withUnitOfWork` ensures the transaction is rolled back:

```java
unitOfWorkFactory.usingUnitOfWork(() -> {
    // If any exception is thrown, all changes are rolled back
        
    orderRepository.save(order);

    if (order.getTotal().isNegative()) {
        throw new ValidationException("Order total cannot be negative");
        // Transaction will be rolled back automatically
    }

    inventoryService.reserve(order.getItems());
});
```

---

## `FencedLock` (Distributed Locking)

**Package:** `dk.trustworks.essentials.components.foundation.fencedlock`

### Why

Distributed locks coordinate access to shared resources across multiple service instances.  
Based on [Martin Kleppmann's fenced locking concept](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html), each lock acquisition receives a monotonically increasing token to prevent stale lock holders from performing operations.

### Scope: Intra-Service Coordination

`FencedLockManager` is designed for **intra-service** coordination (multiple instances of the **same** service sharing a database):

![Intra Service Fenced Locks](images/intra-service-fenced-locks.png)

For **cross-service** locks (different services like Sales, Billing, Shipping), use a dedicated distributed locking service such as Zookeeper.

### Key Concepts

| Concept | Description |
|---------|-------------|
| **FencedLockManager** | Obtains and manages distributed FencedLocks |
| **FencedLock** | Named exclusive lock with fence token |
| **Fence Token** | Monotonically increasing value for stale lock detection |
| **Lock Confirmation** | Periodic heartbeat to maintain lock ownership |
| **LockCallback** | Callback for async lock acquisition/release |

### The Problem: Split Brain with Simple Locks

Traditional distributed locks have a fundamental flaw: **a lock holder can become stale without knowing it**.

Consider this scenario:
1. **Service Instance A** acquires lock for "ProcessOrders"
2. Instance A starts processing, but experiences a **long GC pause** or network delay
3. The lock times out (Instance A doesn't confirm ownership in time)
4. **Service Instance B** acquires the same lock and starts processing
5. Instance A's GC pause ends—it **still thinks it holds the lock** and continues processing
6. **Both instances are now processing simultaneously** (split brain!)

```
Timeline:
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│ Instance A: [Acquire Lock]──[Processing]──[GC PAUSE.........................]──[Continues!] │
│ Instance B:                                 [Lock Timeout]──[Acquire]──[Processing]         │
│                                                    ↑                                        │
│                                              SPLIT BRAIN - Both active!                     │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

This happens because the lock holder has no way to know its lock was taken over.

### The Solution: Fenced Tokens

A **Fenced Token** is a monotonically increasing counter issued every time a lock is acquired or confirmed.  
The key insight: **downstream systems can reject requests from stale lock holders** by comparing tokens.

```
Lock Acquisitions Over Time:
┌───────────────────────────────────────────────────────────────────┐
│ Instance A acquires lock     → Token = 42                         │
│ Instance A confirms lock     → Token = 42                         │
│ Instance A times out (GC)                                         │
│ Instance B acquires lock     → Token = 43  (higher than 42!)      │
│ Instance A wakes up          → Still holds Token = 42             │
│                                                                   │
│ When Instance A tries to write with Token 42:                     │
│   → Storage sees lastSeenToken = 43                               │
│   → 42 < 43, so request from Instance A is REJECTED as stale      │
└───────────────────────────────────────────────────────────────────┘
```
> **Note:** When Instance A attempts its next periodic lock confirmation heartbeat, the confirmation will fail because Instance B now holds a higher token.  
> Instance A's `FencedLockManager` will then release the lock locally and notify any registered `LockCallback`.  
> At this point, only Instance B remains the active lock holder.

### Using Fence Tokens for Stale Lock Detection

**Pass the fence token to all downstream operations**, then validate it before performing work:

```java
// Lock holder: Pass token to downstream service
FencedLock lock = lockManager.acquireLock(LockName.of("ProcessOrders"));
long fenceToken = lock.getCurrentToken();

// Include token in all operations
orderService.processOrder(orderId, fenceToken);
inventoryService.reserveStock(orderId, fenceToken);
```

**Downstream service: Validate token before processing (over simplified)**

```java
public class OrderService {
    // Track the highest token seen for each resource
    private final Map<OrderId, Long> lastSeenTokens = new ConcurrentHashMap<>();

    public void processOrder(OrderId orderId, long fenceToken) {
        Long lastSeenToken = lastSeenTokens.get(orderId);

        // Reject if token is stale (lower than what we've seen)
        if (lastSeenToken != null && fenceToken < lastSeenToken) {
            throw new StaleTokenException(
                "Rejecting stale request: token " + fenceToken +
                " < lastSeenToken " + lastSeenToken);
        }

        // Token is valid - update and proceed
        lastSeenTokens.put(orderId, fenceToken);

        // Safe to process - we know this is from the current lock holder
        doProcessOrder(orderId);
    }
}
```

**For database operations, store the token with the record:**

```java
// When updating, include token check in WHERE clause
int updated = jdbi.withHandle(handle ->
    handle.createUpdate("""
        UPDATE orders
        SET status = :status, last_fence_token = :token
        WHERE id = :id AND last_fence_token <= :token
        """)
        .bind("id", orderId)
        .bind("status", newStatus)
        .bind("token", fenceToken)
        .execute());

if (updated == 0) {
    throw new StaleTokenException("Another lock holder already processed this order");
}
```

### Timing Configuration

Configure timing to balance responsiveness against false timeouts:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `lockTimeOut` | 10 seconds | Duration without confirmation before lock expires |
| `lockConfirmationInterval` | 3 seconds | Heartbeat interval (must be < lockTimeOut) |

**Rule of thumb:** `lockConfirmationInterval` should be 2-3x smaller than `lockTimeOut` to allow for network delays and missed confirmations.

```java
FencedLockManager lockManager = PostgresqlFencedLockManager.builder()
    .setLockTimeOut(Duration.ofSeconds(10))
    .setLockConfirmationInterval(Duration.ofSeconds(3))
    // ... other config
    .buildAndStart();
```

### Synchronous Lock Acquisition

```java
FencedLockManager lockManager = ...; // PostgresqlFencedLockManager or MongoFencedLockManager
lockManager.start();

// Blocking wait to acquire lock
FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"));
try {
    // Critical section
    processOrders(lock.getCurrentToken());
} finally {
    lock.release();
}

// Try-with-resources pattern
try (FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"))) {
    performCriticalWork();
}

// Non-blocking try acquire
Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("MyLock"));
lock.ifPresent(l -> {
    try {
        doWork();
    } finally {
        l.release();
    }
});
```

### Asynchronous Lock Acquisition (Preferred for Long-Running Tasks)

```java
lockManager.acquireLockAsync(LockName.of("ScheduledTask"), new LockCallback() {
    @Override
    public void lockAcquired(FencedLock lock) {
        // Pass fence token to downstream services for stale lock detection
        long fenceToken = lock.getCurrentToken();
        startPeriodicProcessing(fenceToken);
    }

    @Override
    public void lockReleased(FencedLock lock) {
        stopPeriodicProcessing();
    }
});
```

### FencedLockManager API

```java
public interface FencedLockManager extends Lifecycle {
    Optional<FencedLock> lookupLock(LockName lockName);
    Optional<FencedLock> tryAcquireLock(LockName lockName);
    Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout);
    FencedLock acquireLock(LockName lockName);
    boolean isLockAcquired(LockName lockName);
    boolean isLockedByThisLockManagerInstance(LockName lockName);
    boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName);
    void acquireLockAsync(LockName lockName, LockCallback lockCallback);
    void cancelAsyncLockAcquiring(LockName lockName);
    String getLockManagerInstanceId();
}
```

### Implementations

| Implementation | Module |
|---------------|--------|
| `PostgresqlFencedLockManager` | [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md) |
| `MongoFencedLockManager` | [springdata-mongo-distributed-fenced-lock](../springdata-mongo-distributed-fenced-lock/README.md) |

---

## `DurableQueues` (Messaging)

**Package:** `dk.trustworks.essentials.components.foundation.messaging.queue`

### Why

`DurableQueues` provides **intra-service** point-to-point messaging with **At-Least-Once** delivery guarantees.  
Use this when message producers and consumers can access the same underlying database.

The Durable Queue concept that supports **queuing** a `Message` on to a Queue.  
Each Queue is uniquely identified by its `QueueName`.  
Each message is associated with a unique `QueueEntryId` (think MessageId).

### Key Features

| Feature | Description |
|---------|-------------|
| **At-Least-Once Delivery** | Messages guaranteed to be delivered at least once |
| **Competing Consumers** | Multiple consumers can process from the same queue |
| **Dead Letter Queue** | Failed messages are isolated for manual review |
| **Ordered Messages** | Messages delivered in sequence per key |
| **Delayed Delivery** | Schedule messages for future delivery |
| **Redelivery Policies** | Configurable retry with linear/exponential backoff |

### Requirement: 
**Design for Idempotency**: Handlers should handle duplicates gracefully, since message consumers can and will receive the same message more than once (due to the At-Least-Once delivery guarantees)

### Scope: Intra-Service Messaging

All deployed instances of a service share the same database and can use `DurableQueues` for point-to-point messaging:

![Intra Service Durable Queues](images/intra-service-durable-queues.png)

For **cross-service** messaging, use a dedicated queue service like RabbitMQ.

### Queueing Messages

```java
QueueName queueName = QueueName.of("OrderProcessing");

// Simple queuing (transactional with UnitOfWork)
unitOfWorkFactory.usingUnitOfWork(() -> {
    durableQueues.queueMessage(queueName, Message.of(orderEvent));
});

// Non-transactional queuing
durableQueues.queueMessage(queueName, Message.of(orderEvent));

// Delayed delivery
durableQueues.queueMessage(queueName, Message.of(reminder), Duration.ofMinutes(30));
```

### Consuming Messages

```java
DurableQueueConsumer consumer = durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderProcessing"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(200))
            .setMaximumNumberOfRedeliveries(5)
            .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(ValidationException.class))
            .build())
        .setParallelConsumers(3)
        .setQueueMessageHandler(message -> handleMessage(message))
        .build());

// Cancel consumer when done
consumer.cancel();
```

### Pattern Matching Message Handler

```java
var consumer = durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(queueName)
        .setRedeliveryPolicy(redeliveryPolicy)
        .setParallelConsumers(3)
        .setQueueMessageHandler(new PatternMatchingQueuedMessageHandler() {
            @MessageHandler
            void handle(ProcessOrderCommand command) {
                // Handle ProcessOrderCommand
            }

            @MessageHandler
            void handle(CancelOrderCommand command, QueuedMessage queuedMessage) {
                // Access full message context when needed
            }

            @Override
            protected void handleUnmatchedMessage(QueuedMessage queuedMessage) {
                log.warn("Unmatched: {}", queuedMessage.getPayload().getClass());
            }
        })
        .build());
```

#### MessageHandlerInterceptor

`PatternMatchingMessageHandler` supports `MessageHandlerInterceptor` for adding cross-cutting behavior (logging, metrics, tracing) to `@MessageHandler` method invocations.

**Interface:** `MessageHandlerInterceptor`  
**Package:** `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`

```java
// Add interceptors via constructor
var handler = new PatternMatchingQueuedMessageHandler(List.of(
    new RecordExecutionTimeMessageHandlerInterceptor(
        Optional.of(meterRegistry),
        true,
        LogThresholds.defaultThresholds(),
        "OrderService")
)) {
    @MessageHandler
    void handle(ProcessOrderCommand command) {
        // Metrics recorded for this method invocation
    }
};

// Or add interceptors after construction
handler.addInterceptor(myCustomInterceptor);
```

**Built-in Interceptor:**

| Interceptor | Package | Description |
|-------------|---------|-------------|
| `RecordExecutionTimeMessageHandlerInterceptor` | `interceptor.micrometer` | Records `@MessageHandler` method execution time |

**Metrics:**
- `essentials.messaging.message_handler` - Timer metric with tags: `message_handler_class`, `message_handler_method`, `message_type`, and optional `Module`

### RedeliveryPolicy Options

The `RedeliveryPolicy` determines how message redelivery is handled when a `DurableQueueConsumer` experiences an error during message delivery.

#### Backoff Strategies

| Strategy | Description | Use Case |
|----------|-------------|----------|
| `fixedBackoff()` | Same delay between every retry | Simple retry scenarios |
| `linearBackoff()` | Delay increases linearly with each retry | Gradual backoff for transient issues |
| `exponentialBackoff()` | Delay increases exponentially with each retry | External service recovery, rate limiting |

#### Configuration Parameters

| Parameter | Description |
|-----------|-------------|
| `initialRedeliveryDelay` | Delay before the first redelivery attempt |
| `followupRedeliveryDelay` | Base delay for subsequent redelivery attempts |
| `followupRedeliveryDelayMultiplier` | Multiplier applied to followup delay (1.0 = linear, >1.0 = exponential) |
| `maximumFollowupRedeliveryDelayThreshold` | Cap on the maximum delay between retries |
| `maximumNumberOfRedeliveries` | Maximum retry attempts before marking as Dead Letter |
| `deliveryErrorHandler` | Strategy for determining permanent vs transient errors |

#### Examples

```java
// Fixed backoff: 500ms delay, max 5 retries
RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5)

// Linear backoff: starts at 1s, increases by 1s each retry, max 30s delay, max 10 retries
RedeliveryPolicy.linearBackoff(
    Duration.ofSeconds(1),    // redeliveryDelay
    Duration.ofSeconds(30),   // maximumFollowupRedeliveryDelayThreshold
    10                        // maximumNumberOfRedeliveries
)

// Exponential backoff: starts at 500ms, doubles each time, max 1 minute delay, max 8 retries
RedeliveryPolicy.exponentialBackoff(
    Duration.ofMillis(500),   // initialRedeliveryDelay
    Duration.ofMillis(500),   // followupRedeliveryDelay
    2.0,                      // followupRedeliveryDelayMultiplier (doubles each retry)
    Duration.ofMinutes(1),    // maximumFollowupRedeliveryDelayThreshold
    8                         // maximumNumberOfRedeliveries
)

// Full builder for custom configuration
RedeliveryPolicy.builder()
    .setInitialRedeliveryDelay(Duration.ofSeconds(1))
    .setFollowupRedeliveryDelay(Duration.ofSeconds(2))
    .setFollowupRedeliveryDelayMultiplier(1.5)
    .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMinutes(5))
    .setMaximumNumberOfRedeliveries(10)
    .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(
        ValidationException.class,
        IllegalArgumentException.class
    ))
    .build();
```

### MessageDeliveryErrorHandler

The `MessageDeliveryErrorHandler` determines which errors should immediately mark a message as a Dead Letter Message (permanent error) versus which errors should allow redelivery attempts (transient error).

#### Built-in Handlers

| Handler | Description |
|---------|-------------|
| `alwaysRetry()` | Always attempt redelivery regardless of exception type (default) |
| `stopRedeliveryOn(Exception...)` | Immediately mark as Dead Letter for specified exception types |
| `builder()` | Custom builder for complex error handling rules |

#### Usage

```java
// Always retry (default) - transient errors will eventually succeed or exhaust retries
RedeliveryPolicy.fixedBackoff()
    .setDeliveryErrorHandler(MessageDeliveryErrorHandler.alwaysRetry())
    .build();

// Stop redelivery on specific exceptions - mark as Dead Letter immediately
RedeliveryPolicy.fixedBackoff()
    .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(
        ValidationException.class,      // Invalid message format
        UnknownOrderException.class,    // Business logic error
        IllegalArgumentException.class  // Programming error
    ))
    .build();

// Custom builder for complex rules
RedeliveryPolicy.builder()
    .setDeliveryErrorHandler(MessageDeliveryErrorHandler.builder()
        .stopRedeliveryOn(ValidationException.class)
        .stopRedeliveryOn(SecurityException.class)
        .build())
    .build();
```

#### Exception Matching

The handler checks the **entire exception causal chain**, not just the top-level exception:
- First attempts **exact class match**
- Then attempts **hierarchy match** (subclasses of specified exceptions also match)

### Dead Letter Queue (DLQ)

Messages that fail delivery after exhausting all redelivery attempts (or encounter a permanent error) are marked as **Dead Letter Messages** (also called Poison Messages).

#### Why Dead Letter Messages?

- **Isolation**: Failed messages don't block processing of other messages
- **Debugging**: Preserves the original message and error information for analysis
- **Recovery**: Allows manual review and resurrection when the underlying issue is fixed

#### Dead Letter Message Lifecycle

```
     Message Delivery Fails
               │
               ▼
┌─────────────────────────────┐
│ MessageDeliveryErrorHandler │
│   isPermanentError()?       │
└──────────────┬──────────────┘
               │
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
  Permanent          Transient
    Error              Error
      │                 │
      │                 ▼
      │        ┌─────────────────┐
      │        │ Retry with      │
      │        │ RedeliveryPolicy│
      │        └────────┬────────┘
      │                 │
      │        Retries exhausted?
      │                 │
      ▼                 ▼
┌─────────────────────────────┐
│  markAsDeadLetterMessage()  │
│  - Stores error details     │
│  - Excluded from delivery   │
└─────────────────────────────┘
               │
               ▼
      Manual Review / Fix
               │
               ▼
┌─────────────────────────────┐
│ resurrectDeadLetterMessage()│
│  - Resets delivery state    │
│  - Schedules for redelivery │
└─────────────────────────────┘
```

#### Dead Letter Operations

```java
// Query dead letter messages for a queue
List<QueuedMessage> deadLetters = durableQueues.getDeadLetterMessages(
    queueName,
    QueueingSortOrder.ASC,  // Oldest first
    0,                      // startIndex
    100                     // pageSize
);

// Get count of dead letter messages
long dlqCount = durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName);

// Get a specific dead letter message
Optional<QueuedMessage> deadLetter = durableQueues.getDeadLetterMessage(queueEntryId);

// Resurrect a dead letter message for redelivery
// The message will be delivered after the specified delay
Optional<QueuedMessage> resurrected = durableQueues.resurrectDeadLetterMessage(
    queueEntryId,
    Duration.ofSeconds(10)  // deliveryDelay
);

// Manually mark a message as dead letter (e.g., from application code)
Optional<QueuedMessage> marked = durableQueues.markAsDeadLetterMessage(
    queueEntryId,
    "Manual intervention: Invalid customer data"
);

// Queue a message directly as a dead letter (skip normal processing)
QueueEntryId entryId = durableQueues.queueMessageAsDeadLetterMessage(
    queueName,
    message,
    new ValidationException("Message failed pre-validation")
);
```

#### QueuedMessage Dead Letter Properties

```java
QueuedMessage message = durableQueues.getDeadLetterMessage(queueEntryId).orElseThrow();

message.isDeadLetterMessage();      // true for DLQ messages
message.getLastDeliveryError();     // Error message/stack trace
message.getTotalDeliveryAttempts(); // Total attempts before DLQ
message.getRedeliveryAttempts();    // Redelivery attempts count
message.getAddedTimestamp();        // When originally queued
message.getDeliveryTimestamp();     // Last delivery attempt time
```

#### Admin API for Dead Letter Management

The `DurableQueuesApi` provides REST-friendly operations for dead letter management:

```java
@Autowired
DurableQueuesApi durableQueuesApi;

// Resurrect with delivery delay (e.g., from admin UI)
durableQueuesApi.resurrectDeadLetterMessage(principal, queueEntryId, Duration.ofMinutes(1));

// Mark as dead letter (e.g., from admin UI)
durableQueuesApi.markAsDeadLetterMessage(principal, queueEntryId);

// Delete a dead letter message
durableQueuesApi.deleteMessage(principal, queueEntryId);
```

### DurableQueuesInterceptor

**Interface:** `DurableQueuesInterceptor`  
**Package:** `dk.trustworks.essentials.components.foundation.messaging.queue`

Interceptors allow you to add cross-cutting behavior (logging, metrics, tracing) to `DurableQueues` operations.  
Each interceptor can perform **before**, **after**, or **around** logic for any operation.

#### Interceptable Operations

| Method | Description                                 |
|--------|---------------------------------------------|
| `intercept(QueueMessage, chain)` | Queue a single message                      |
| `intercept(QueueMessages, chain)` | Queue multiple messages                     |
| `intercept(QueueMessageAsDeadLetterMessage, chain)` | Queue directly as dead letter message (DLQ) |
| `intercept(HandleQueuedMessage, chain)` | Handle a message (consumer processing)      |
| `intercept(GetNextMessageReadyForDelivery, chain)` | Poll for next message                       |
| `intercept(AcknowledgeMessageAsHandled, chain)` | Acknowledge successful handling             |
| `intercept(RetryMessage, chain)` | Mark message for retry                      |
| `intercept(MarkAsDeadLetterMessage, chain)` | Move message to DLQ                         |
| `intercept(ResurrectDeadLetterMessage, chain)` | Resurrect from DLQ                          |
| `intercept(DeleteMessage, chain)` | Delete a message                            |
| `intercept(GetQueuedMessage, chain)` | Get a specific message                      |
| `intercept(GetDeadLetterMessage, chain)` | Get a dead letter message                   |
| `intercept(GetQueuedMessages, chain)` | Get queued messages                         |
| `intercept(GetDeadLetterMessages, chain)` | Get dead letter messages                    |
| `intercept(GetTotalMessagesQueuedFor, chain)` | Count queued messages                       |
| `intercept(GetTotalDeadLetterMessagesQueuedFor, chain)` | Count dead letter messages                  |
| `intercept(GetQueuedMessageCountsFor, chain)` | Get combined counts                         |
| `intercept(ConsumeFromQueue, chain)` | Start consuming from queue                  |
| `intercept(StopConsumingFromQueue, chain)` | Stop consuming                              |
| `intercept(PurgeQueue, chain)` | Purge all messages                          |

#### Adding Interceptors

Interceptors are added via the `DurableQueues` builder:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMessageHandlingTimeout(Duration.ofSeconds(30))
    .addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "OrderService"))
    .addInterceptor(new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry, false))
    .build();
```

#### Custom Interceptor Example

```java
public class LoggingDurableQueuesInterceptor implements DurableQueuesInterceptor {
    private DurableQueues durableQueues;

    @Override
    public void setDurableQueues(DurableQueues durableQueues) {
        this.durableQueues = durableQueues;  // Called when interceptor is added
    }

    @Override
    public QueueEntryId intercept(QueueMessage operation,
            InterceptorChain<QueueMessage, QueueEntryId, DurableQueuesInterceptor> chain) {
        // Before
        log.info("Queueing message to {}: {}",
            operation.queueName,
            operation.getMessage().getPayload().getClass().getSimpleName());

        // Proceed
        var entryId = chain.proceed();

        // After
        log.info("Queued with ID: {}", entryId);
        return entryId;
    }

    @Override
    public Void intercept(HandleQueuedMessage operation,
            InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> chain) {
        var message = operation.getMessage();
        log.info("Handling message {} from {}", message.getId(), message.getQueueName());

        try {
            return chain.proceed();
        } catch (Exception e) {
            log.error("Failed to handle message {}", message.getId(), e);
            throw e;
        }
    }
}
```

#### Built-in Interceptors

| Interceptor | Package | Description |
|-------------|---------|-------------|
| `DurableQueuesMicrometerInterceptor` | `messaging.queue.micrometer` | Queue size gauges, message counters (processed, handled, retries, DLQ) |
| `DurableQueuesMicrometerTracingInterceptor` | `messaging.queue.micrometer` | Distributed tracing via Micrometer Observation API |
| `RecordExecutionTimeDurableQueueInterceptor` | `interceptor.micrometer` | Operation execution time metrics |

#### DurableQueuesMicrometerInterceptor Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `DurableQueues_QueuedMessages_Size` | Gauge | Current queue depth (per queue) |
| `DurableQueues_DeadLetterMessages_Size` | Gauge | Current DLQ depth (per queue) |
| `DurableQueues_QueuedMessages_Processed` | Counter | Messages queued (per queue) |
| `DurableQueues_QueuedMessages_Handled` | Counter | Messages successfully handled (per queue) |
| `DurableQueues_QueuedMessages_Retries` | Counter | Retry attempts (per queue) |
| `DurableQueues_DeadLetterMessages_Processed` | Counter | Messages moved to DLQ (per queue) |

All metrics include a `QueueName` tag and optional `Module` tag.

### Implementations

| Implementation | Module |
|---------------|--------|
| `PostgresqlDurableQueues` | [postgresql-queue](../postgresql-queue/README.md) |
| `MongoDurableQueues` | [springdata-mongo-queue](../springdata-mongo-queue/README.md) |

---

## Inbox Pattern

**Package:** `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`

### Why

The Inbox pattern implements **Store and Forward** from Enterprise Integration Patterns with At-Least-Once delivery.  
Use it when receiving messages from external infrastructure (Kafka, RabbitMQ) that doesn't share your database's transactional resource.

### The Dual Write Problem

When consuming messages from external systems like Kafka, you face the **Dual Write Problem**:

```
External System          Your Service
    (Kafka)
       │
       │  1. Receive message
       ▼
   ┌───────┐           ┌─────────────┐
   │Message│──────────▶│  Process &  │
   └───────┘           │ Update DB   │
       │               └──────┬──────┘
       │                      │
       │  2. ACK message      │ 3. Commit DB
       ▼                      ▼
```

**The problem:** You need to both:
1. Process the message and update your database
2. Acknowledge the message to the external system

These are **two separate operations** that cannot be made atomic:
- If you ACK first, then DB fails → Message lost, data inconsistent
- If you commit DB first, then ACK fails → Message will be redelivered, potentially causing duplicates

### How the `Inbox` Pattern Solves It

The `Inbox` pattern leverages the fact that Kafka (and similar systems) already provides **At-Least-Once delivery** semantics.  
The pattern is symmetric: Kafka delivers messages at least once to your Inbox consumer, and your `Inbox` delivers messages at least once to your handlers/consumers.

**Flow:**
1. Message arrives from Kafka
2. Message is **stored in `Inbox`** within a `UnitOfWork`
3. Kafka receives ACK **only after** `UnitOfWork` commits
4. Messages are **asynchronously delivered** to handlers in a new `UnitOfWork`
5. Redelivery handles transient failures

**Why this works:**
- If storing in `Inbox` fails → Kafka doesn't receive ACK → Kafka redelivers
- If Inbox storage succeeds but ACK fails → Kafka redelivers → `Inbox` queues the duplicate message
- Once in `Inbox` → Your handlers receive the message at least once with redelivery guarantees. The handlers are responsible for handling duplicates.

The `Inbox` ensures consistency by allowing your message handler to update business data and mark the `Inbox` message as processed within the **same `UnitOfWork`/database-transaction** - if one fails, both are rolled back.

> Note: This requires an `Inbox` implementation that uses the same database system and transaction as your business data.
> - If you store your business data in PostgreSQL, then your `Inbox` must also store its messages in the same PostgreSQL database (e.g., using `PostgresqlDurableQueues`)
> - If you store your business data in MongoDB, then your `Inbox` must also store its messages in the same MongoDB database (e.g., using `MongoDurableQueues`)


```                                              
┌──────────┐      ┌───────────────┐               ┌─────────┐              ┌─────────────┐              ┌────────────────┐    
│  Kafka   │      │               │  addMessage   │         │  send(msg)   │             │  handle(msg) │                │      
│  Topic   │─────▶│ KafkaListener │──Received────▶│  Inbox  │─────────────▶│ CommandBus  │─────────────▶│ CommandHandler │      
│          │      │               │    (msg)      │         │              │             │              │                │      
└──────────┘      └───────────────┘               └────┬────┘              └─────────────┘              └────────────────┘    
                                                       │         
                                                       │         
                                                       ▼         
                                                ┌──────────────┐
                                                │              │
                                                │DurableQueues │
                                                │              │
                                                └──────┬───────┘
                                                       │        
                                                       ▼        
                                                 ┌─────────────┐ 
                                                 │ PostgreSQL/ │ 
                                                 │  MongoDB    │ 
                                                 └─────────────┘                                                  
```

### Message Consumption Modes

| Mode | Description                                                       |
|------|-------------------------------------------------------------------|
| `SingleGlobalConsumer` | One active consumer in the cluster (uses `FencedLock` for failover) |
| `GlobalCompetingConsumers` | Multiple consumers compete for messages                           |

### Spring Configuration

```java
@Bean
public Inboxes inboxes(DurableQueues durableQueues, FencedLockManager fencedLockManager) {
    return Inboxes.durableQueueBasedInboxes(durableQueues, fencedLockManager);
}
```

### Creating an Inbox

```java
Inbox orderEventsInbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .inboxName(InboxName.of("OrderService:KafkaEvents"))
        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(100))
            .setMaximumNumberOfRedeliveries(10)
            .build())
        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
        .numberOfParallelMessageConsumers(5)
        .build(),
    new PatternMatchingMessageHandler() {
        @MessageHandler
        void handle(ProcessOrderCommand command) {
            processOrder(command);
        }

        @Override
        protected void handleUnmatchedMessage(Message message) {
            log.warn("Unknown message type");
        }
    });
```

### Receiving from Kafka

```java
@KafkaListener(topics = ORDER_EVENTS_TOPIC_NAME, groupId = "order-processing")
public void handle(OrderEvent event) {
    if (event instanceof OrderAccepted) {
        orderEventsInbox.addMessageReceived(new ProcessOrderCommand(event.getId()));
    }
}
```

---

## Outbox Pattern

**Package:** `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`

### Why

The Outbox pattern solves the **Dual Write Problem** when you need to update a database AND publish a message to an external system atomically.

### The Dual Write Problem

When your service needs to both update its database and publish an event/message to an external system, you face the **Dual Write Problem**:

```
Your Service                    External System
                                   (Kafka)
┌─────────────┐
│ Update DB   │
└──────┬──────┘
       │ 1. Commit DB
       ▼
┌─────────────┐     2. Publish    ┌───────┐
│   Publish   │──────────────────▶│ Topic │
│   Message   │                   └───────┘
└─────────────┘
```

**The problem:** You need to both:
1. Commit changes to your database
2. Publish a message/event to the external system

These are **two separate operations** that cannot be made atomic:
- If DB commits but publish fails → External systems never see the event → Data inconsistency
- If publish succeeds but DB fails to commit → External systems see an event for something that didn't happen
- Network partitions, service crashes, or timeouts between the two operations cause inconsistency

### How the Outbox Pattern Solves It

Instead of publishing directly to the external system, store the outgoing message in an **`Outbox`** within the **same `UnitOfWork`/database-transaction** as your business data.

> Note: This requires an `Outbox` implementation that uses the same database system and transaction as your business data.
> - If you store your business data in a PostgreSQL, then your `Outbox` must also store its messages in the same PostgreSQL database (e.g., using the `PostgresqlDurableQueues`)
> - If you store your business data in MongoDB, then your `Outbox` must also store its messages in the same MongoDB database (e.g., using the `MongoDurableQueues`)

```
Your Service                    Outbox Table              External System
                                                             (Kafka)
┌─────────────┐  Same TX   ┌─────────────┐
│ Update DB   │───────────▶│ Store in    │
│ + Outbox    │            │ Outbox      │
└──────┬──────┘            └──────┬──────┘
       │                          │
       │ 1. Commit (atomic)       │ 2. Async polling
       ▼                          ▼
                           ┌─────────────┐  3. Publish  ┌───────┐
                           │   Outbox    │─────────────▶│ Topic │
                           │   Relay     │              └───────┘
                           └─────────────┘
```

**How it works:**
1. Business logic updates database entities and stores the outgoing message in `Outbox` within the **same `UnitOfWork`/transaction** → Atomic (both succeed or both rollback)
2. `UnitOfWork` commits
3. A separate process (`Outbox` relay) polls the `Outbox` and publishes messages to external systems
4. If publish fails → Message stays in `Outbox` → Retry on next poll
5. Messages are delivered **At-Least-Once** to external systems

```
                      ┌───────────────────┐
  sendMessage(msg)    │                   │   KafkaTemplate      ┌──────────┐
─────────────────────▶│      Outbox       │─────.send(event)────▶│  Kafka   │
                      │                   │                      │  Topic   │
                      └─────────┬─────────┘                      └──────────┘
                                │
                                │
                                ▼
                      ┌───────────────────┐
                      │                   │
                      │   DurableQueues   │
                      │                   │
                      └─────────┬─────────┘
                                │
                                ▼
                      ┌───────────────────┐
                      │    PostgreSQL/    │
                      │     MongoDB       │
                      └───────────────────┘
```

### Spring Configuration

```java
@Bean
public Outboxes outboxes(DurableQueues durableQueues, FencedLockManager fencedLockManager) {
    return Outboxes.durableQueueBasedOutboxes(durableQueues, fencedLockManager);
}
```

### Creating an Outbox

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
```

### Sending Messages Atomically

```java
unitOfWorkFactory.usingUnitOfWork(() -> {
    // Business logic (same transaction)
    Order order = orderRepository.save(new Order(...));

    // Queue for external delivery (same transaction)
    kafkaOutbox.sendMessage(new OrderCreatedEvent(order.getId()));
});
// Both committed together or both rolled back
```

---

## DurableLocalCommandBus

**Package:** `dk.trustworks.essentials.components.foundation.reactive.command`

### Why

A CommandBus variant that uses `DurableQueues` to ensure commands sent via `sendAndDontWait` aren't lost in case of failure.  
Combines the reactive programming model with guaranteed delivery.

### How It Works

```
┌──────────┐      ┌───────────────┐  sendAndDontWait  ┌───────────────────────┐                 ┌────────────────┐
│  Kafka   │      │               │       (msg)       │                       │  handle(msg)    │                │
│  Topic   │─────▶│ KafkaListener │──────────────────▶│ DurableLocalCommandBus│─────────────────│ CommandHandler │
│          │      │               │                   │                       │                 │                │
└──────────┘      └───────────────┘                   └───────────┬───────────┘                 └────────────────┘ 
                                                                  │                             
                                                                  │                   
                                                                  │                   
                                                                  │                   
                                                                  ▼                   
                                                        ┌───────────────────┐         
                                                        │                   │
                                                        │   DurableQueues   │
                                                        │                   │
                                                        └─────────┬─────────┘
                                                                  │
                                                                  ▼
                                                        ┌───────────────────┐
                                                        │    PostgreSQL/    │
                                                        │     MongoDB       │
                                                        └───────────────────┘
```

### Configuration

```java
// Simple configuration
var commandBus = DurableLocalCommandBus.builder()
    .setDurableQueues(durableQueues)
    .build();

// Full configuration
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

// Synchronous execution (returns result)
OrderId result = commandBus.send(new CreateOrderCommand(...));
```

---

## Ordered Message Processing

### Why

When processing events from event sourcing or CQRS systems, messages must be processed in order per aggregate/entity to maintain consistency.
This is supported by queuing `OrderedMessage`s with `DurableQueues`/`Inbox`/`Outbox`.

`OrderedMessage`'s are designed to support events or messages that must be processed in a specific order based on their **key** (typically an Aggregate ID)
and **order** (typically a sequential `EventOrder` number that is a per aggregate instance `event-sequence-number`).

### Key Concepts

- **OrderedMessage.key**: Identifies the entity (e.g., "Order-123")
- **OrderedMessage.order**: Sequential position (0, 1, 2, ...) related to the key


```java
// Example creating sequential OrderedMessages for events relation to aggregate with id "Order-123"
OrderedMessage.of(
    orderCreatedEvent,           // Event payload
    "Order-123",                 // key = aggregateId
    1L                          // order = eventOrder
);

OrderedMessage.of(
    orderItemAddedEvent,
    "Order-123",
    2L
);
```


### Single-Node Ordering (Automatic)

On a single node with multiple parallel consumers, `DurableQueues` automatically ensures ordering per **key**:

```java
durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderEvents"))
        .setParallelConsumers(10)  // Ordering still maintained per key
        .setQueueMessageHandler(handler)
        .build());
```

### Multi-Node Ordering (Requires `SingleGlobalConsumer` consumption mode)

When running `DurableQueues` across **multiple nodes** (cluster) that are all connected to the same database, we run into a problem:
- Each node's `DurableQueues` instance acts as a distributed **competing consumer**
- **Without coordination**, messages for the same aggregate (`Order-123`) can be processed out of order across nodes
- The in-process key tracking only works **within a single node**, not across the cluster
    - Node 1 might fetch and process `Order-123:event-5`, while Node 2 fetches `Order-123:event-4`

```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│   Node 1        │      │   Node 2        │      │   Node 3        │
│ ┌─────────────┐ │      │ ┌─────────────┐ │      │ ┌─────────────┐ │
│ │DurableQueues│ │      │ │DurableQueues│ │      │ │DurableQueues│ │
│ │10 threads   │ │      │ │10 threads   │ │      │ │10 threads   │ │
│ └─────────────┘ │      │ └─────────────┘ │      │ └─────────────┘ │
└────────┬────────┘      └────────┬────────┘      └────────┬────────┘
         │                        │                        │
         └────────────────────────┴────────────────────────┘
                                  │
                        ┌─────────▼─────────┐
                        │  Shared Database  │
                        │   (PostgreSQL)    │
                        └───────────────────┘
```

**Why this happens:**
- Each node independently polls the database for messages
- Database-level locking (like `SELECT ... FOR UPDATE SKIP LOCKED`) only prevents the **same message** from being consumed concurrently by multiple nodes
- **No coordination** between nodes to ensure ordering per **key** (e.g. per aggregate ID)
- It does **NOT** prevent different messages with the same **key** from being consumed by different nodes simultaneously – hence we cannot guarantee ordering per **key**

#### How to Fix it:
To guarantee message delivery coordination across multiple nodes, you MUST use `Inbox`/`Outbox` with comsumption mode `SingleGlobalConsumer`:

**How it works:**

1. **FencedLock Coordination**:
    - The `FencedLockManager` ensures only ONE node in the cluster can actively consume from the `Inbox`/`Outbox`'s underlying `DurableQueue` at any time
    - Other nodes are on standby for failover

2. **Single Active Consumer**:
    - Only the node holding the `FencedLock` will poll for and process messages
    - This node can still use multiple parallel threads (e.g., 10 threads) internally
    - The message fetcher on the active node ensures proper ordering (see implementation details below)

3. **Cluster-Wide Ordering Guarantee**:
    - Since only ONE node consumes at a time, the ordering coordination happens in a single place
    - Messages for `Order-123` are processed in sequence: `event-0` then `event-1`, then `event-2`, then `event-3`...
    - Messages for different aggregates (`Order-123`, `Order-456`) can still be processed in parallel

4. **Failover**:
    - If the active node crashes, another node acquires the lock and takes over
    - The new node continues processing messages in order

```java
Inbox inbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .setInboxName(InboxName.of("OrderEventsInbox"))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer) // Required!
        .setNumberOfParallelMessageConsumers(10)
        .setRedeliveryPolicy(redeliveryPolicy)
        .build(),
    messageHandler);
```

**Visualization:**

```
Cluster with Inbox/Outbox using SingleGlobalConsumer:

┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│   Node 1        │      │   Node 2        │      │   Node 3        │
│ ┌─────────────┐ │      │ ┌─────────────┐ │      │ ┌─────────────┐ │
│ │   ACTIVE    │◄┼──────┼─│  STANDBY    │ │      │ │  STANDBY    │ │
│ │ Inbox       │ │ Lock │ │  Inbox      │ │      │ │  Inbox      │ │
│ │10 threads   │ │      │ │             │ │      │ │             │ │
│ └─────────────┘ │      │ └─────────────┘ │      │ └─────────────┘ │
└────────┬────────┘      └─────────────────┘      └─────────────────┘
         │                                           
         │  Processes messages in order per key
         │  while utilizing 10 parallel threads
         │
┌────────▼─────────┐
│ Shared Database  │
│  + FencedLock    │
└──────────────────┘
```

##### Important Limitations

**Distributed Lock Guarantees:**
- `FencedLock` provides strong coordination guarantees but is not 100% perfect
- In rare cases (network partitions, node crashes), split-brain scenarios can occur temporarily
- See `FencedLockManager` documentation for details on limitations and edge cases
- **Design for idempotency**: Message handlers should handle duplicate/out-of-order messages gracefully

**When to Use Each Mode:**

| Scenario | Configuration | Ordering Guarantee | Performance |
|----------|--------------|-------------------|------|
| Single node, unordered messages | `GlobalCompetingConsumers` | ❌ None | Highest |
| Single node, ordered messages | `GlobalCompetingConsumers` | ✅ Per key, within node | High |
| Multi-node, unordered messages | `GlobalCompetingConsumers` | ❌ None | Highest (distributed) |
| Multi-node, ordered messages | `SingleGlobalConsumer` + `FencedLock` | ✅ Per key, cluster-wide | Medium (single active consumer) |

### Best Practices

1. **Design for Idempotency**: Handlers should handle duplicates gracefully - this is already required when using At-Least-Once delivery guarantees
2. **Use SingleGlobalConsumer**: When ordering is critical in multi-node deployments
3. **Monitor FencedLock**: Track lock acquisition for failover visibility

### Ordering implementation Details
Dpendending on the `DurableQueues` implementation there is either one or multiple fetching approaches supported - each with their own performance characterisctics:

**Centralized Message Fetcher** (PostgreSQL with `useCentralizedMessageFetcher=true`):
- Uses a single polling thread per `DurableQueues` instance to fetch messages from the database
- Tracks which `OrderedMessage.key`'s are currently being processed using `inProcessOrderedKeys`
- Before dispatching a message to a worker thread, it checks if another message with the same key is already being processed

**Traditional Message Fetcher** (MongoDB, or PostgreSQL with `useCentralizedMessageFetcher=false`):
- Each consumer thread in `DefaultDurableQueueConsumer` tracks in-process keys via `orderedMessageDeliveryThreads`
- When fetching the next message, the query excludes keys that are currently being processed by other threads

**In both approaches:**
- If a message with key `Order-123` is being processed, no other message with key `Order-123` will be processed until the current one completes
- Messages with **different keys** (e.g., `Order-123` and `Order-456`) can be processed in parallel across all threads
- **Result**: OrderedMessages are guaranteed to be processed in order per key, while different keys can be processed concurrently
---

## JSONSerializer

**Package:** `dk.trustworks.essentials.components.foundation.json`

### Why

`JSONSerializer` provides a technology-agnostic interface for JSON serialization/deserialization, abstracting away the underlying implementation (e.g., Jackson using `JacksonJSONSerializer`).

### Key Methods

| Method | Description |
|--------|-------------|
| `serialize(Object)` | Serialize object to JSON string |
| `serializePrettyPrint(Object)` | Serialize with pretty formatting |
| `serializeAsBytes(Object)` | Serialize to byte array |
| `deserialize(String, Class<T>)` | Deserialize from JSON string |
| `deserialize(byte[], Class<T>)` | Deserialize from byte array |
| `setClassLoader(ClassLoader)` | Set ClassLoader (required for Spring Boot DevTools) |

### Usage

```java
JSONSerializer serializer = new JacksonJSONSerializer(objectMapper);

// Serialize
String json = serializer.serialize(order);
byte[] bytes = serializer.serializeAsBytes(order);

// Deserialize
Order order = serializer.deserialize(json, Order.class);
Order order = serializer.deserialize(bytes, Order.class);

// Using fully-qualified class name (for polymorphic types)
Object event = serializer.deserialize(json, "com.example.OrderCreatedEvent");
```

### Exception Handling

- `JSONSerializationException` - Thrown on serialization failure
- `JSONDeserializationException` - Thrown on deserialization failure

---

## LifecycleManager

**Package:** `dk.trustworks.essentials.components.foundation.lifecycle`

### Why

`DefaultLifecycleManager` integrates with Spring to ensure that beans implementing the `Lifecycle` interface are properly started and stopped with the ApplicationContext.

### Key Features

- Implements `SmartLifecycle` for Spring integration
- Automatically discovers and manages all `Lifecycle` beans
- Supports optional context-refreshed event callbacks
- Uses phase `Integer.MAX_VALUE - 1` for late startup, early shutdown

### Spring Configuration

```java
@Bean
public DefaultLifecycleManager lifecycleManager() {
    return new DefaultLifecycleManager(
        context -> {
            // Optional callback when context is refreshed
            // Called BEFORE Lifecycle beans are started
        },
        true  // isStartLifecycles - set to false to disable auto-start
    );
}
```

### Lifecycle Interface

```java
public interface Lifecycle {
    void start();
    void stop();
    boolean isStarted();
}
```

Components like `FencedLockManager`, `DurableQueues`, and `EventStoreSubscriptionManager` implement `Lifecycle` and are automatically managed.

---

## PostgreSQL Utilities

**Package:** `dk.trustworks.essentials.components.foundation.postgresql`

### PostgresqlUtil

Provides PostgreSQL-specific utilities including **critical SQL identifier validation**.

#### Security Validation

#### `checkIsValidTableOrColumnName`
> The `checkIsValidTableOrColumnName(String)` method provides an **initial layer of defense** against SQL injection:

**Validation Rules:**
- Must not be null or empty
- Must start with letter (A-Z) or underscore (_)
- Subsequent characters: letters, digits (0-9), underscores
- Also checks `isValidSqlIdentifier` / `isValidQualifiedSqlIdentifier`
- Max length: 63 characters or 127 characters for schema prefixed names (2 * 63 + separator)
- Must not be a reserved SQL/PostgreSQL keyword (300+ keywords checked)

```java
// Validates table/column/index names
PostgresqlUtil.checkIsValidTableOrColumnName("orders");           // OK
PostgresqlUtil.checkIsValidTableOrColumnName("my_table_123");     // OK
PostgresqlUtil.checkIsValidTableOrColumnName("SELECT");           // Throws - reserved keyword
PostgresqlUtil.checkIsValidTableOrColumnName("123_table");        // Throws - starts with digit
```

#### `isValidSqlIdentifier`

Returns `true`/`false` instead of throwing exceptions. Use for conditional validation of simple (non-qualified) identifiers:

**Validation Rules:**
- Must not be null, empty, or whitespace only
- Must start with letter (A-Z) or underscore (_)
- Subsequent characters: letters, digits (0-9), underscores
- Max length: 63 characters (`MAX_IDENTIFIER_LENGTH`)
- Must not be a reserved SQL/PostgreSQL keyword

```java
// Simple identifier validation (returns boolean)
PostgresqlUtil.isValidSqlIdentifier("valid_identifier");   // true
PostgresqlUtil.isValidSqlIdentifier("_valid_identifier");  // true
PostgresqlUtil.isValidSqlIdentifier("valid123");           // true
PostgresqlUtil.isValidSqlIdentifier("123invalid");         // false - starts with digit
PostgresqlUtil.isValidSqlIdentifier("invalid-name");       // false - contains hyphen
PostgresqlUtil.isValidSqlIdentifier("SELECT");             // false - reserved keyword
```

#### `isValidQualifiedSqlIdentifier`

Validates qualified identifiers in `schema.entity` format

**Qualified Identifier Rules:**
- Must not be null, empty, or whitespace only
- Must contain exactly one dot separator
- Must not start or end with a dot
- Must not contain consecutive dots
- Each part must be a valid SQL identifier (per `isValidSqlIdentifier` rules)
- Max total length: 127 characters (`MAX_QUALIFIED_IDENTIFIER_LENGTH` = 63 per part + separator)

```java
// Qualified identifier validation (schema.table format)
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.table");         // true
PostgresqlUtil.isValidQualifiedSqlIdentifier("my_schema.my_table");   // true
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema");               // false - no dot
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema..table");        // false - consecutive dots
PostgresqlUtil.isValidQualifiedSqlIdentifier("schema.table.extra");   // false - too many parts
PostgresqlUtil.isValidQualifiedSqlIdentifier("SELECT.table");         // false - reserved keyword
```

#### `isValidFunctionName`

Validates function names, supporting both simple and schema-qualified formats:

**Validation Rules:**
- Must not be null, empty, or whitespace only
- For simple names: delegates to `isValidSqlIdentifier` (max 63 characters)
- For qualified names (contains `.`): delegates to `isValidQualifiedSqlIdentifier` (max 127 characters)
- Must not contain reserved SQL/PostgreSQL keywords

```java
// Function name validation (returns boolean)
PostgresqlUtil.isValidFunctionName("my_function");              // true
PostgresqlUtil.isValidFunctionName("my_schema.my_function");    // true
PostgresqlUtil.isValidFunctionName("123_function");             // false - starts with digit
PostgresqlUtil.isValidFunctionName("schema.123_func");          // false - entity starts with digit
PostgresqlUtil.isValidFunctionName("EXECUTE");                  // false - reserved keyword
```

> ⚠️ **CRITICAL SECURITY WARNING:**
> This validation provides **ONLY an initial layer of defense**. It does **NOT** offer exhaustive SQL injection protection.
>
> **Developer Responsibility:**
> - Users MUST implement additional sanitization for all configuration values (API input parameters, values, column names, function names, table names, index names, etc.)
> - Only derive function/table/column/index/... names from controlled, trusted sources
> - NEVER allow external/untrusted input to provide these values directly
>
> **Failure to adequately sanitize values could expose the database to SQL injection attacks.**

#### Other Utilities

```java
// Get PostgreSQL major version
int version = PostgresqlUtil.getServiceMajorVersion(handle);

// Check extension availability
boolean hasPgCron = PostgresqlUtil.isPGExtensionAvailable(handle, "pg_cron");
```

### `ListenNotify`

Implements PostgreSQL LISTEN/NOTIFY for real-time table change notifications for a single table.

```java
// Add NOTIFY trigger to table
ListenNotify.addChangeNotificationTriggerToTable(
    handle,
    "orders",
    List.of(SqlOperation.INSERT, SqlOperation.UPDATE, SqlOperation.DELETE),
    "order_id", "status"  // Additional columns in notification payload
);

// Listen for notifications (returns Flux for reactive processing)
Flux<String> notifications = ListenNotify.listen(jdbi, "orders", Duration.ofMillis(100));
notifications.subscribe(payload -> {
    // Handle notification
});
```

### `MultiTableChangeListener`

Manages listening to multiple tables with a single polling thread and publishes notifications to interested parties via an `EventBus`.

#### How It Works

1. **Polling**: A single background thread polls PostgreSQL for NOTIFY messages at the configured interval
2. **Deserialization**: Notification payloads are deserialized to typed `TableChangeNotification` subclasses using the `JSONSerializer`
3. **Duplicate Filtering**: Optionally filters duplicate notifications within the same poll batch
4. **Publishing**: Notifications are published to the configured `EventBus`, where subscribers receive them

#### Setup

```java
// Create the listener with an EventBus for notification distribution
MultiTableChangeListener<TableChangeNotification> listener = MultiTableChangeListener.builder()
    .setJdbi(jdbi)
    .setPollingInterval(Duration.ofMillis(100))
    .setJsonSerializer(jsonSerializer)
    .setEventBus(eventBus)  // Notifications are published here
    .setFilterDuplicateNotifications(true)
    .build();

// Register tables to listen to (each with its notification type)
listener.listenToNotificationsFor("orders", OrderNotification.class);
listener.listenToNotificationsFor("customers", CustomerNotification.class);

listener.start();
```

#### Receiving Notifications

Interested parties subscribe to the `EventBus` to receive notifications:

```java
// Define a notification type matching the table's trigger payload
public class OrderNotification extends TableChangeNotification {
    public String orderId;
    public String status;
    public String operation;  // INSERT, UPDATE, DELETE
}

// Subscribe to receive notifications
eventBus.addSyncSubscriber(notification -> {
    if (notification instanceof OrderNotification orderNotification) {
        log.info("Order {} was {}: status={}",
            orderNotification.orderId,
            orderNotification.operation,
            orderNotification.status);

        // React to the change (e.g., invalidate cache, trigger workflow)
        if ("UPDATE".equals(orderNotification.operation)) {
            orderCache.invalidate(orderNotification.orderId);
        }
    }
});

// Or subscribe asynchronously
eventBus.addAsyncSubscriber(notification -> {
    // Handle in separate thread
});
```

#### Duplicate Notification Filtering

When `filterDuplicateNotifications` is enabled, the listener can filter duplicate notifications within the same poll batch using `NotificationDuplicationFilter`:

```java
// Add a custom duplication filter
listener.addDuplicationFilterAsFirst(jsonNode -> {
    // Extract a unique key from the notification JSON
    if (jsonNode.has("orderId")) {
        return Optional.of(jsonNode.get("orderId").asText());
    }
    return Optional.empty();  // Don't deduplicate if no key found
});
```

> ⚠️ **Security Note:** Table names passed to these components are validated using `PostgresqlUtil.checkIsValidTableOrColumnName()`, but this is only an initial defense layer.
> **See security warning above.**

---

## MongoDB Utilities

**Package:** `dk.trustworks.essentials.components.foundation.mongo`

### `MongoUtil`

Provides MongoDB-specific utilities including **collection name validation**.

#### `checkIsValidCollectionName`

The `checkIsValidCollectionName(String)` method provides an **initial layer of defense** against malicious collection names:

**Validation Rules:**
- Must not be null, empty, or whitespace only
- Must not exceed 64 characters (`MAX_LENGTH`)
- Must not contain `$` or null characters
- Must not start with `system.` (reserved for MongoDB system collections)
- Must contain only letters (a-z, A-Z), digits (0-9), and underscores (_)

```java
// Validates MongoDB collection names
MongoUtil.checkIsValidCollectionName("orders");              // OK
MongoUtil.checkIsValidCollectionName("my_collection_123");   // OK
MongoUtil.checkIsValidCollectionName("system.users");        // Throws - starts with system.
MongoUtil.checkIsValidCollectionName("my$collection");       // Throws - contains $
MongoUtil.checkIsValidCollectionName("invalid-name");        // Throws - contains hyphen
```

#### `isWriteConflict`

Checks if an exception is a MongoDB write conflict (useful for retry logic):

```java
try {
    // MongoDB operation
} catch (Exception e) {
    if (MongoUtil.isWriteConflict(e)) {
        // Retry the operation
    }
}
```

> ⚠️ **CRITICAL SECURITY WARNING:**
> The `checkIsValidCollectionName` validation provides **ONLY an initial layer of defense**. It does **NOT** offer exhaustive protection against all forms of malicious input.
>
> **Developer Responsibility:**
> - Users MUST implement additional sanitization for all configuration values
> - Only derive collection names from controlled, trusted sources
> - NEVER allow external/untrusted input to provide collection names directly
>
> **Failure to adequately sanitize values could compromise the security and integrity of the database.**

---

## `TTLManager`

**Package:** `dk.trustworks.essentials.components.foundation.ttl` (interface)  
**Package:** `dk.trustworks.essentials.components.foundation.postgresql.ttl` (PostgreSQL implementation)

### Why

`TTLManager` handles scheduled deletion/archival of expired data (Time-To-Live management).

### `PostgresqlTTLManager`

Coordinates with `EssentialsScheduler` to execute TTL jobs using either pg_cron extension or executor-based scheduling.

```java
PostgresqlTTLManager ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
ttlManager.start();

// Schedule a TTL job
ttlManager.scheduleTTLJob(TTLJobDefinition.builder()
    .setAction(DefaultTTLJobAction.builder()
        .setTableName("audit_logs")
        .setWhereClause("created_at < NOW() - INTERVAL '90 days'")
        .build())
    .setSchedule(CronScheduleConfiguration.of("0 3 * * *"))  // Daily at 3 AM
    .build());
```

### `TTLJobAction` Options

| Option | Description | Security                                                                    |
|--------|-------------|-----------------------------------------------------------------------------|
| `tableName` | Target table for deletion | ✅ Validated via PostgresqlUtil - see general SQL Security warning above  |
| `whereClause` | WHERE condition for deletion | ⚠️ **NOT validated** - developer must sanitize value to avoid SQL injection |
| `fullDeleteSql` | Complete DELETE statement | ⚠️ **NOT validated** - developer must sanitize value to avoid SQL injection |

> ⚠️ **CRITICAL SECURITY WARNING:**
> The `whereClause` and `fullDeleteSql` parameters are executed directly **without any security validation**.
> - Only derive these values from controlled, trusted sources
> - NEVER allow external/untrusted input to provide these values
> - Failure to sanitize could result in SQL injection vulnerabilities

---

## `EssentialsScheduler`

**Package:** `dk.trustworks.essentials.components.foundation.scheduler`

### Why

Lightweight scheduler for managing PostgreSQL pg_cron jobs and executor-based jobs. Intended for internal Essentials component use, not as a replacement for full-fledged schedulers like Quartz.

### Key Features

- Supports pg_cron (PostgreSQL extension) when available
- Falls back to executor-based scheduling
- Uses `FencedLock` for distributed coordination

### Usage

```java
EssentialsScheduler scheduler = DefaultEssentialsScheduler.builder()
    .setJdbi(jdbi)
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setFencedLockManager(fencedLockManager)
    .setSchedulerThreads(4)
    .build();

scheduler.start();

// Check pg_cron availability
if (scheduler.isPgCronAvailable()) {
    scheduler.schedulePgCronJob(PgCronJob.builder()
        .setJobName("cleanup_expired_tokens")
        .setSchedule("0 * * * *")  // Every hour
        .setFunctionName("cleanup_expired_tokens")
        .build());
}

// Executor-based job (always available)
scheduler.scheduleExecutorJob(ExecutorJob.builder()
    .setJobName("health_check")
    .setSchedule(FixedDelayScheduleConfiguration.of(Duration.ofMinutes(5)))
    .setTask(() -> performHealthCheck())
    .build());
```

---

## `IOExceptionUtil`

**Package:** `dk.trustworks.essentials.components.foundation`

### Why

Helper utility to identify transient IO, Connection, Transaction, and Socket exceptions for retry logic and resilience patterns.

### Key Method

```java
boolean isTransient = IOExceptionUtil.isIOException(exception);
```

### Detection Covers

- Direct `IOException` or `SQLTransientException`
- Root cause analysis for wrapped exceptions
- Known transient error messages:
  - "An I/O error occurred while sending to the backend"
  - "Could not open JDBC Connection for transaction"
  - "Connection is closed"
  - "Unable to acquire JDBC Connection"
  - "Could not open JPA EntityManager for transaction"
- JDBI `ConnectionException`
- MongoDB `MongoSocketException`

### Usage

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

---

## Admin APIs

**Package:** `dk.trustworks.essentials.components.foundation.*.api`

Admin APIs provide management and monitoring interfaces for Essentials components. Each API takes a `principal` parameter for authorization.

### DBFencedLockApi

**Package:** `dk.trustworks.essentials.components.foundation.fencedlock.api`

```java
public interface DBFencedLockApi {
    List<ApiDBFencedLock> getAllLocks(Object principal);
    boolean releaseLock(Object principal, LockName lockName);
}
```

### DurableQueuesApi

**Package:** `dk.trustworks.essentials.components.foundation.messaging.queue.api`

```java
public interface DurableQueuesApi {
    Set<QueueName> getQueueNames(Object principal);
    Optional<ApiQueuedMessage> getQueuedMessage(Object principal, QueueEntryId queueEntryId);
    Optional<ApiQueuedMessage> resurrectDeadLetterMessage(Object principal, QueueEntryId queueEntryId, Duration deliveryDelay);
    Optional<ApiQueuedMessage> markAsDeadLetterMessage(Object principal, QueueEntryId queueEntryId);
    boolean deleteMessage(Object principal, QueueEntryId queueEntryId);
    long getTotalMessagesQueuedFor(Object principal, QueueName queueName);
    long getTotalDeadLetterMessagesQueuedFor(Object principal, QueueName queueName);
    List<ApiQueuedMessage> getQueuedMessages(Object principal, QueueName queueName, QueueingSortOrder sortOrder, long startIndex, long pageSize);
}
```

### SchedulerApi

**Package:** `dk.trustworks.essentials.components.foundation.scheduler.api`

```java
public interface SchedulerApi {
    List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize);
    long getTotalPgCronJobs(Object principal);
    List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(Object principal, Integer jobId, long startIndex, long pageSize);
    List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize);
}
```

### PostgresqlQueryStatisticsApi

**Package:** `dk.trustworks.essentials.components.foundation.postgresql.api`

```java
public interface PostgresqlQueryStatisticsApi {
    List<ApiQueryStatistics> getTopTenSlowestQueries(Object principal);
}
```

Requires the PostgreSQL `pg_stat_statements` extension. Returns normalized SQL (literals replaced with placeholders for security – only for internal use).

### Authorization

All APIs throw `EssentialsSecurityException` if the principal lacks required permissions. Implement proper authorization checks in your application.

---

## Implementation Modules

| Component | PostgreSQL | MongoDB |
|-----------|-----------|---------|
| FencedLockManager | [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md) | [springdata-mongo-distributed-fenced-lock](../springdata-mongo-distributed-fenced-lock/README.md) |
| DurableQueues | [postgresql-queue](../postgresql-queue/README.md) | [springdata-mongo-queue](../springdata-mongo-queue/README.md) |

Each implementation module contains:
- Database-specific configuration
- Security considerations for table/collection names
- Performance tuning options
