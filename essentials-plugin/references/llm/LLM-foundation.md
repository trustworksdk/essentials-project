# Foundation - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/foundation/README.md).

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
- `EssentialTypesJacksonModule` from [types-jackson3](./LLM-types-jackson.md) and `EssentialsImmutableJacksonModule` from [immutable-jackson3](./LLM-immutable-jackson.md), registered by `EssentialsObjectMappers`
- `CorrelationId`, `MessageId`, `SubscriberId` from [foundation-types](./LLM-foundation-types.md)

## TOC
- [Core Concepts](#core-concepts)
- [UnitOfWork (Transactions)](#unitofwork-transactions)
- [DurableQueues (Messaging)](#durablequeues-messaging)
- [FencedLock (Distributed Locking)](#fencedlock-distributed-locking)
- [Inbox/Outbox Patterns](#inboxoutbox-patterns)
- [Ordered Message Processing](#ordered-message-processing)
- [DurableLocalCommandBus](#durablelocalcommandbus)
- [Event Causation](#event-causation)
- [Database Schema Harness](#database-schema-harness)
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

    // Framework-internal - see "Usage rule" below
    UOW getRequiredUnitOfWork();      // Throws NoActiveUnitOfWorkException if no active UnitOfWork
    UOW getOrCreateNewUnitOfWork();   // Caller must start/commit/roll back itself

    // Read-only probe: is a UnitOfWork bound to this thread?
    Optional<UOW> getCurrentUnitOfWork();
}

// dk.trustworks.essentials.components.foundation.transaction.UnitOfWork
public interface UnitOfWork {
    void commit();
    void rollback();
    void markAsRollbackOnly();
    void markAsRollbackOnly(Exception cause);
    UnitOfWorkStatus status();

    // Every resource registered via registerLifecycleCallbackForResource(...), across all callbacks - in-memory
    // state the UnitOfWork may act on at commit, which rolling back to a savepoint does not undo.
    // The default throws UnsupportedOperationException. All Essentials implementations override it.
    List<Object> getAllUnitOfWorkLifecycleCallbackResources();

    // Would committing persist or publish something for any registered resource? Asks each resource's
    // UnitOfWorkLifecycleCallback.hasPendingChanges(resource): an aggregate that had an event applied has pending
    // changes, one that was only loaded has none. False when nothing is registered. The default throws
    // UnsupportedOperationException - callers must read that as "pending changes". All Essentials implementations
    // override it. ViewEventProcessor uses it to decide whether a failed direct handler can be queued.
    boolean hasLifecycleCallbackResourcesWithPendingChanges();
}

// dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkLifecycleCallback<RESOURCE_TYPE>
public interface UnitOfWorkLifecycleCallback<RESOURCE_TYPE> {
    // ... beforeCommit / afterCommit / beforeRollback / afterRollback ...

    // Would committing make this callback persist/publish something for the resource? Default true (the safe
    // answer). Override it in a custom callback whose registered resources can be unchanged - otherwise every
    // resource registered with it counts as pending, and e.g. a failed ViewEventProcessor handler rolls the whole UnitOfWork back before it is queued.
    // The stateful, flex and decider repository callbacks answer true only while there are uncommitted events.
    default boolean hasPendingChanges(RESOURCE_TYPE resource) { return true; }
}
```

### Usage rule

- **Application code** opens or joins a transaction with `unitOfWorkFactory.usingUnitOfWork(...)`/`withUnitOfWork(...)`, or with Spring `@Transactional`/`TransactionTemplate`. These join an active UnitOfWork or Spring transaction, or start one and commit or roll it back.
- **Framework-internal**: `getOrCreateNewUnitOfWork()`, `getRequiredUnitOfWork()` and a UnitOfWork's own `start()`/`commit()`/`rollback()`. Need the UnitOfWork itself (a JDBI `Handle`, `markAsRollbackOnly`)? Take it as the lambda parameter: `usingUnitOfWork(uow -> …)`, `withUnitOfWork(uow -> …)`.
- **`getCurrentUnitOfWork()` is a read-only probe** ("is a UnitOfWork bound to this thread?"), never a way to decide whether to join or open one — `withUnitOfWork` already decides that. Under a Spring-aware factory it is empty inside a plain `@Transactional` method until something has used the factory, so it does not answer "is a transaction active?" either.

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

    // Joins the Spring tx: the enqueue commits or rolls back with the save
    durableQueues.queueMessage(queueName, new OrderProcessedEvent(order.getId()));
}
```

A `DurableQueues` enqueue joins an active UnitOfWork or Spring transaction, or runs on its own (PostgreSQL through `withUnitOfWork`, MongoDB as a plain `MongoTemplate` write). An enqueue outside both therefore commits on its own and is not rolled back with the caller's work. Each call site's first one WARNs, with its stack trace (up to 32 innermost application frames per `DurableQueues` instance, each with up to 4 of its callers; a call site is the first three stack frames outside Essentials, the JDK, Reactor, Kotlin and Spring, so callers behind your own wrapper around `Outbox.sendMessage` count separately); `essentials.durable-queues.require-active-unit-of-work-for-enqueue=true` (builder: `setRequireActiveUnitOfWorkForEnqueue(true)`) makes it throw `NoActiveUnitOfWorkException` instead — see [LLM-postgresql-queue.md § Transactions](./LLM-postgresql-queue.md#transactions).

**When to use explicit `usingUnitOfWork` / `withUnitOfWork`:**
1. No transaction exists - starts new transaction
2. Need return value - use `withUnitOfWork`
3. Need the UnitOfWork itself - take it as the lambda parameter:

```java
unitOfWorkFactory.usingUnitOfWork(uow -> {
    uow.markAsRollbackOnly(exception);
});

// For JDBI - access the Handle (joins an active UnitOfWork or @Transactional, or starts one)
int rows = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
        .createUpdate("UPDATE orders SET status = :status WHERE id = :id")
        .bind("status", status)
        .bind("id", orderId)
        .execute());
```
`withUnitOfWork(uow -> …)` gives the same UnitOfWork `getRequiredUnitOfWork()` would return inside a transaction, and opens one where `getRequiredUnitOfWork()` would throw `NoActiveUnitOfWorkException`.

### Hand-opened `Jdbi` handles (PostgreSQL starter)

The `Jdbi` bean of `spring-boot-starter-postgresql` wraps the `DataSource` in Spring's `TransactionAwareDataSourceProxy`, and the starter's Spring-aware UnitOfWork factories open their handles from that same `Jdbi`. A handle you open yourself (`jdbi.useHandle(...)`, `jdbi.withHandle(...)`, `jdbi.open()`) therefore behaves like this:

- **Inside an active UnitOfWork or Spring transaction** it gets the transaction's connection and joins it: its writes commit or roll back with the transaction.
- **With no UnitOfWork or Spring transaction active** it gets its own pooled connection and every statement auto-commits. Such a write is not atomic with any queue, inbox or outbox operation, so it gets none of their guarantees.

Prefer `unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()…)`: it joins an active transaction and opens one when none is active.

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

`n` is the message's redelivery-attempt count, 0 when its first delivery failed.

| Strategy | Delay before redelivery `n` | Use Case |
|----------|---------|----------|
| `fixedBackoff(delay, …)` | `delay`, every time | Simple retries |
| `linearBackoff(delay, max, …)` | `delay × (n+1)`, capped at `max` | Gradual backoff |
| `exponentialBackoff(initial, followup, multiplier, max, …)` | `n = 0`: `initial`; `n ≥ 1`: `followup × multiplier^(n-1)`, capped at `max` | External service recovery |

Before 0.60 neither `linearBackoff` nor `exponentialBackoff` grew: every redelivery after the first waited
`initial + followup × multiplier`. A `multiplier` of `1.0` (or less, including an unset builder value) gives a constant
`followup` delay.

```java
// Fixed: 500ms delay, max 5 retries
RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5)

// Linear: 1s, 2s, 3s, … capped at 30s, max 10 retries
RedeliveryPolicy.linearBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30), 10)

// Exponential: 500ms, 500ms, 1s, 2s, 4s, 8s, 16s, 32s (cap 1min never reached), then dead letter
RedeliveryPolicy.exponentialBackoff(
    Duration.ofMillis(500),  // initialRedeliveryDelay
    Duration.ofMillis(500),  // followupRedeliveryDelay
    2.0,                     // multiplier
    Duration.ofMinutes(1),   // max delay
    8                        // max retries
)
```

**With a builder (`RedeliveryPolicy.exponentialBackoff()`, `.linearBackoff()`, `.fixedBackoff()`, `.builder()`), call
every setter except `setDeliveryErrorHandler`.** An unset `setMaximumNumberOfRedeliveries` silently means **0**
redeliveries (the first failure dead-letters the message) and an unset multiplier means no growth. Since 0.60.1
`build()` logs one WARN naming each missing or out-of-range setter (delays, multiplier < 1.0, maximum redeliveries not
set explicitly - pass `0` if you mean it); from 0.61 it throws `IllegalArgumentException` instead.

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

#### The built-in permanent-error list

The consumer applies its own list of permanent error types **after** consulting your
`MessageDeliveryErrorHandler`. A message failing with one of these is dead-lettered on the first delivery
attempt, whatever the `RedeliveryPolicy`'s backoff says — unless the handler explicitly asks to retry that
type and the type allows it:

| Type | Overridable by `alwaysRetryOn` | Why |
|---|---|---|
| `DurableQueueDeserializationException` | No | The stored bytes will not parse on the hundredth attempt either |
| `MismatchedInputException` | No | Same |
| `NoClassDefFoundError` | No | A missing class is a deployment fault, not a transient one |
| `IllegalArgumentException` (incl. `NumberFormatException`) | **Yes** | The house guard idiom, frequently thrown about data that may be valid later |
| `ClassCastException` | **Yes** | Usually a genuine bug, but a cast against a projection that has not caught up is legitimately transient |

`IllegalArgumentException` on that list is the one that surprises people. `FailFast.requireNonNull(...)` and
`requireTrue(...)` — the validation idiom used across this codebase — throw it, and so does Kotlin's
`require(...)`. A `@MessageHandler` that guards its arguments dead-letters its message on the first delivery
attempt unless you opt out:

```java
RedeliveryPolicy.exponentialBackoff()
    // ...
    .setDeliveryErrorHandler(MessageDeliveryErrorHandler.builder()
                                                        .alwaysRetryOn(IllegalArgumentException.class)
                                                        .build())
    .build();
```

Two limits on that opt-out. It cannot override the three types marked "No" above, and it does not lift
`maximumNumberOfRedeliveries` — the message is still dead-lettered once its attempts are used up. Note also
that `MessageDeliveryErrorHandler.alwaysRetry()` is *not* the same thing: it means "I have no opinion", so the
built-in list still applies. Only the explicit `alwaysRetryOn(...)` list overrides it.

The whole cause chain is examined, not just its ends. A handler throw arrives wrapped
(`UnitOfWorkException → ReflectionException → InvocationTargetException → yours`), and a match anywhere in that
chain counts — so classification no longer depends on whether your exception happens to carry a cause of its
own.

The dead-letter log line names which rule fired, the matched type, its depth in the cause chain, and the
attempt count, e.g.
`PERMANENT_ERROR (built-in permanent list matched IllegalArgumentException at cause-chain depth 3; attempt 1 of 6)`.

#### Validating inside a message handler

Pick the exception type by whether the condition can ever become true:

```java
@MessageHandler
void handle(OrderShipped event) {
    // The message can never be processed: the payload itself is wrong.
    // IllegalArgumentException -> dead-lettered immediately, unless you opted out above.
    requireNonNull(event.orderId, "orderId is required");

    var order = orderRepository.find(event.orderId);
    if (order == null) {
        // The projection may simply not have caught up yet. Throw something retryable,
        // NOT IllegalArgumentException, or the message is dead-lettered on first delivery.
        throw new IllegalStateException("Order " + event.orderId + " not projected yet");
    }
}
```

### Delivery observability

**Interface**: `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueMessageObserver`

Notified of how each delivery *ended*. Not an interceptor: an interceptor sees the operation, not the outcome.

```java
var registry = new QueueStatisticsRegistry();

PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMessageObserver(new StatisticsCollectingDurableQueueMessageObserver(registry))
    .build();

// Per-queue, this JVM only
registry.findStatistics(queueName).ifPresent(stats -> {
    stats.delivery().messagesHandled();
    stats.delivery().averageHandlerDuration();
    stats.outcomes().messagesDeadLettered();
    stats.outcomes().lastFailureReason();
});
```

| Callback | When |
|---|---|
| `messageHandled(message, handlerDuration)` | after the acknowledgement — "delivered and removed", not "the handler returned" |
| `messageRetried(message, cause, redeliveryDelay)` | a failed delivery that will be redelivered |
| `messageDeadLettered(message, cause)` | a delivery that ended as a dead letter |
| `messageRedeliveryRequested(message)` | the handler asked for redelivery; not a failure |

Use `DurableQueueMessageObserver.composite(List)` for more than one observer — it is not a single-slot SPI. The
queue wraps whatever it is given in `safe(...)`, so an observer that throws cannot break delivery; it runs on
delivery threads, so it must not block.

`deleteMessage` and `purgeQueue` deliberately do **not** notify. They are administrative, not deliveries.

**Dead-letter metric.** `MicrometerDurableQueueMessageObserver` increments
`essentials.messaging.durable_queues.dead_lettered` once per dead letter, tagged `queue_name`,
`message_payload_type` and `reason` (`permanent_error` | `redeliveries_exhausted`). Both Spring Boot starters
register it whenever a `MeterRegistry` is present — deliberately *not* behind
`essentials.metrics.durable-queues.enabled`, which controls execution-time measurement. A timing switch must
not turn an incident counter off. This is the counter to alert on; the pre-existing
`essentials.messaging.durable_queues.mark_as_dead_letter_message` timer measures how long the marking took and
carries no reason.

**Dead-letter health indicator.** `DurableQueuesHealthIndicator` reports per-queue dead-letter counts under
`durableQueues` on `/actuator/health`. Both Spring Boot starters register it by default.

```properties
# Reports UP regardless of the counts unless this is set to a positive number
essentials.durable-queues.health.dead-letter-threshold=100
# How long a computed result is reused before the counts are read again (default 10s)
essentials.durable-queues.health.cache-time-to-live=10s
# Do not register the indicator at all
management.health.durable-queues.enabled=false
```

⚠️ **The default never reports `DOWN`, and that is the point.** A `HealthIndicator` contributes to the
composite `/actuator/health` status, which readiness and liveness probes are routinely pointed at — so an
indicator that went `DOWN` on the first dead letter would take working pods out of service, or restart them,
leaving fewer consumers to drain the queue behind the poison message. Set a threshold only if a queue reaching
that count really does mean the instance should stop taking traffic. This is the same rule `CdcHealthIndicator`
follows with `CdcMode.REQUIRE`. To alert without touching a probe, use the Micrometer counter above.

The threshold applies **per queue**, not to the total, so the number does not silently mean something else in
an application with more queues. A failure reading the counts reports `UNKNOWN`, not `DOWN` — that is the
`DataSource` indicator's job, and `UNKNOWN` does not drag the aggregated status down on its own.

⚠️ **`QueueStatisticsRegistry` is per-JVM and resets on restart.** The queued and dead-letter counts from
`getQueuedMessageCountsFor` are cluster-wide. Do not present them as one set of numbers — see
`ApiQueueStatistics`, which keeps the two halves apart for exactly this reason.

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
    Optional<FencedLock> lookupLock(LockName lockName);   // snapshot of the stored row
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
    LockName getName();
    Long getCurrentToken();
    String getLockedByLockManagerInstanceId();
    boolean isLocked();
    boolean isLockedByThisLockManagerInstance();
    void release();
    default void close() throws Exception { release(); }
}
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `lockTimeOut` | 10s | Time without confirmation before lock expires |
| `lockConfirmationInterval` | 3s | Heartbeat interval (must be < lockTimeOut) |

**Rule**: `lockConfirmationInterval` should be 2-3x smaller than `lockTimeOut`.

### Ownership is per lock-manager instance, not per caller or thread

A `FencedLock` excludes **other lock-manager instances** (other JVMs), never other callers in the same JVM:

- While this `FencedLockManager` holds a lock, a second `tryAcquireLock`/`acquireLock` for the same `LockName` — from another component, another thread or the same thread — succeeds at once and returns the **same** `FencedLock` instance. It is not refused as busy.
- There is no acquire count: `release()` by **either** caller releases the lock for both, and another node can then take it while the first caller is still working.
- `isLockedByThisLockManagerInstance()` (on the lock or the manager) answers for the manager instance, so it is `true` for every caller in the JVM.

Two callers in one JVM that share a lock name and must not overlap (a `@Scheduled` run and a manual trigger of the same job, two schedulers using one name) need their own in-process guard in front of the lock — see [Singleton Worker Pattern](#singleton-worker-pattern). An exclusive acquire is planned as an additive API.

### Usage

```java
// Synchronous - close() calls release(), but is declared `throws Exception`: catch or declare it, or use try/finally
try (FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"))) {
    performCriticalWork();
}

// Pattern: Try-Acquire (Non-Blocking) - excludes other JVMs only, see "Ownership is per lock-manager instance"
Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));
if (lock.isPresent()) {
    try {
        processOrders(lock.get().getCurrentToken());
    } finally {
        lock.get().release();
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

The two `isLockedByThisLockManagerInstance` predicates answer from different sources:

| Predicate | Reads | Can be wrong when |
|---|---|---|
| `FencedLockManager.isLockedByThisLockManagerInstance(LockName)` | The lock row in the database | It compares the stored owner id with `getLockManagerInstanceId()` and ignores the fence token and the last-confirmed timestamp. A row left by a crashed process with the same instance id (a fixed `essentials.fenced-lock-manager.instance-id`) reads `true` until the lock is acquired again. A failed lookup returns `false` (logged at DEBUG) |
| `FencedLock.isLockedByThisLockManagerInstance()` | The `FencedLock` object only, no database call | The object changes only when this manager confirms or releases the lock, so it can still read `true` up to one `lockConfirmationInterval` after another instance took the lock over — longer while the database is unreachable and `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` is `false` (the default). A lock from `lookupLock` is a snapshot and never changes |

`isLockAcquired` and `isLockAcquiredByAnotherLockManagerInstance` use the same database lookup as the manager predicate. Use the predicates for status and reporting. To guard work, hold the acquired `FencedLock` and pass its token downstream (see [Fence Tokens](#the-solution-fence-tokens)).

## Common Use Cases

### Singleton Worker Pattern

The fenced lock makes the work run on one JVM at a time. It does **not** stop a second caller in the *same* JVM: that caller gets the same lock back, and its `release()` frees the lock under the first one (see [Ownership is per lock-manager instance](#ownership-is-per-lock-manager-instance-not-per-caller-or-thread)). If anything else in the JVM can start the same work under the same lock name — a manual trigger, an admin endpoint, a second schedule — put an in-process guard in front of the lock:

```java
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledWorker {
    private final AtomicBoolean running = new AtomicBoolean();   // exclusion inside this JVM - not a ReentrantLock,
                                                                  // a nested call on the same thread must be refused too

    @Scheduled(fixedDelay = 30000)
    public void processOrders() {                                  // also called by a manual trigger
        if (!running.compareAndSet(false, true)) {
            return;                                                // already running in this JVM
        }
        try {
            Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));  // exclusion across JVMs
            if (lock.isPresent()) {
                try {
                    processOrderBatch(lock.get().getCurrentToken());
                } finally {
                    lock.get().release();
                }
            }
        } finally {
            running.set(false);
        }
    }
}
```

A lone `@Scheduled(fixedDelay = …)` method never overlaps itself, so the guard is only redundant when that method is the lock name's sole user.

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

### Blocking I/O in a Message Handler: `UnitOfWorkMode`

**Enum**: `dk.trustworks.essentials.components.foundation.messaging.UnitOfWorkMode`
**Attribute**: `MessageHandler.unitOfWork()`, default `REQUIRED`

**Problem**: A `@MessageHandler` method runs inside a `UnitOfWork` by default, and a `UnitOfWork` holds a pooled database connection with an open transaction. A handler that calls an external system therefore parks a connection in `idle in transaction` for the duration of that call — one per parallel consumer — while writing nothing.

**Solution**: Declare the handler `UnitOfWorkMode.NONE`. It is then invoked with **no** `UnitOfWork` active, and wraps its own transactional tail after the blocking call returns.

```java
new PatternMatchingMessageHandler() {
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void handle(AssessRiskCommand cmd) {
        var assessment = riskServiceHttpClient.assess(cmd.instrumentId());   // no connection held

        unitOfWorkFactory.usingUnitOfWork(() -> repository.save(assessment)); // transactional tail
    }

    @MessageHandler                                                          // REQUIRED (default), unchanged
    void handle(ProcessOrderCommand cmd) { }
}
```

There is no ambient `UnitOfWork` between the two statements, so touching a transactional resource outside the wrapper fails fast instead of silently opening a transaction.

| Mode | Handler invoked | Commit/rollback |
|------|-----------------|-----------------|
| `REQUIRED` (default) | Inside a `UnitOfWork`; joins an active one if present | On normal return / on throw. Historic behaviour |
| `NONE` | With no `UnitOfWork`, no connection, no open transaction | Handler's own `usingUnitOfWork(...)` / `withUnitOfWork(...)` blocks |

**Who honours it**: only dispatchers that own the `UnitOfWork` boundary — see `UnitOfWorkBoundaryOwningMessageConsumer` and `PatternMatchingMessageHandler.setUnitOfWorkFactory(...)`. In practice that means an `EventProcessor`; the snippet above is the handler body, not a wiring example. Everything else wraps the delivery in a `UnitOfWork` of its own, so it cannot honour the mode — and rejects it rather than ignoring it, see **Guards** below.

**Two responsibilities the mode shifts onto the handler**:

1. **Idempotency is mandatory.** Delivery is at-least-once and the blocking call is no longer part of the transaction that acknowledges the message. A failure after the call returned but before the tail committed redelivers the message and repeats the call.
2. **The blocking call must time out well inside `DurableQueues` `messageHandlingTimeout`** (30s by default in the Spring Boot starters). Past that timeout the in-flight message is reset as stuck and can be delivered again *concurrently with the still-running first attempt* — which also degrades `OrderedMessage` per-key ordering for as long as that overlap lasts. Size the client's timeout, not just the happy path.

**Guards**: `NONE` is never silently ignored. Every dispatcher that cannot provide a `UnitOfWork`-free window throws `IllegalStateException` at wiring/start-up time instead:

| Dispatcher | Rejects `NONE` when | Why |
|---|---|---|
| `Inbox` | The consumer is not a `UnitOfWorkBoundaryOwningMessageConsumer` and the `DurableQueues` has a `UnitOfWorkFactory` | The `Inbox` itself wraps every delivery in a `UnitOfWork` |
| `Outbox` | Always, when the `DurableQueues` has a `UnitOfWorkFactory` | An `Outbox` has no boundary-owning consumer variant |
| `PatternMatchingQueuedMessageHandler` | Always, at construction time | It invokes handlers as-is and never owns the boundary |

Whether a consumer needs the window is introspected from the `@MessageHandler` annotations by `MessageHandlerMethods` — nothing has to be declared by hand. The one exception is a consumer whose handler methods live on *another* object: `UnitOfWorkBoundaryOwningMessageConsumer.hasNonTransactionalMessageHandlers()` defaults to introspecting the consumer itself, so a delegating consumer overrides it to answer for its delegate.

For `EventProcessor` handlers — including the `usingUnitOfWork` / `withUnitOfWork` helpers used above and which processor types support the mode — see [LLM-postgresql-event-store.md](./LLM-postgresql-event-store.md#blocking-io-in-a-handler-unitofworkmodenone).

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

### When a queued command's handler throws

The `SendAndDontWaitErrorHandler` decides retry, not rollback (since 0.60.1). The handler's exception first unwinds
through the interceptors - `UnitOfWorkControllingCommandBusInterceptor` (both Spring Boot starters add it) rolls back
what the handler wrote, or marks the queue consumer's `UnitOfWork` rollback-only - and the error handler is then called
once with the handler's own exception. The default `RethrowingSendAndDontWaitErrorHandler` rethrows, so the queue
redelivers per the `RedeliveryPolicy`; a handler that does not rethrow drops the command, and the message is
acknowledged with nothing committed. Before 0.60.1 a non-rethrowing error handler committed the handler's partial
writes. Keep the interceptor when building the bus by hand: without it nothing marks the PostgreSQL queue consumer's
`UnitOfWork` (which the handler's writes join) rollback-only, so a non-rethrowing error handler still lets it commit.
Since 0.60.1 `start()` logs one WARN for a bus that has a non-rethrowing error handler and no such interceptor. Details:
[LLM-reactive.md § When a handler throws](./LLM-reactive.md#when-a-handler-throws-rejections).

### Commands are persisted

A command sent with `sendAndDontWait` is stored as JSON in the durable-queue table (the command object is the queued
message's payload) and deserialized again when a consumer picks it up — possibly after a deploy. The command type is
therefore a persisted contract, exactly like an event or an Inbox message: the Jackson 3 rules apply to it —
constructor parameter names ([JSONSerializer](#jsonserializer)) and value types in it registered with the
persistence mapper. A renamed constructor parameter breaks the commands already queued, not the ones sent after the
rename. `send(...)` does not persist the command.

## Event Causation

`CausationContext` (package `dk.trustworks.essentials.components.foundation.causation`) carries "the event that
caused the work currently being done" from the place that knows it to the place that writes new events. It is a
`ScopedValue`: bound for the dynamic extent of a call, never leaking into a pooled thread's next task. What reads and
writes it in the event store: [postgresql-event-store](./LLM-postgresql-event-store.md#event-causation).

```java
CausationContext.where(eventId).run(() -> ...);          // bind for a call
var result = CausationContext.where(eventId).call(() -> ...);
CausationContext.where(Optional.empty()).run(() -> ...); // bind "no cause" - hides an outer binding
Optional<EventId> cause = CausationContext.current();     // read
```

- **Bindings nest; the innermost wins.**
- **A binding does not cross threads.** Capture `current()` and re-bind on the other side.
- **Durable queues carry it**: `CausationDurableQueuesInterceptor` writes it into `MessageMetaData` under
  `MessageMetaData.CAUSED_BY_EVENT_ID` (`essentials.causedByEventId`) when a message is queued - unless the message
  already carries one - and re-binds it around the handler. So `Inbox.addMessageReceived`, `Outbox.sendMessage` and
  `DurableLocalCommandBus.sendAndDontWait` carry the sender's cause. It must run outermost
  (`@InterceptorOrder(1)`), because on PostgreSQL the handler's UnitOfWork is opened by an interceptor.
- **Command buses carry it**: `send` runs the handler on the caller's thread; `sendAsync` and
  `LocalCommandBus.sendAndDontWait` run it on a Reactor worker, so `CausationCommandContextPropagator` (a
  `CommandContextPropagator`, see [reactive](./LLM-reactive.md#localcommandbus-api)) captures it at send.
- The Spring Boot event-store starter registers the interceptor and the propagator (on every command-bus bean)
  unless `essentials.eventstore.causation.enabled=false`. Without Spring:
  `durableQueues.addInterceptor(new CausationDurableQueuesInterceptor())` and
  `commandBus.addContextPropagator(new CausationCommandContextPropagator())`.

## Database Schema Harness

**Package**: `dk.trustworks.essentials.components.foundation.schema` (SPI), PostgreSQL appliers in `.postgresql`.
Design and rationale: [docs/database-schema-harness.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/docs/database-schema-harness.md).

Every Essentials component that owns tables *describes* its schema as `SchemaChange`s; an applier decides what
happens to them. In Spring, `essentials.schema.mode` selects the applier - see
[LLM-spring-boot-starter-modules.md](./LLM-spring-boot-starter-modules.md). Default: nothing changes, each
component creates its own schema as it is constructed.

| Type | Role |
|---|---|
| `EssentialsSchemaContributor` | `moduleId()`, `order()` (`SchemaOrder` constants), `contribute(SchemaContext)` → `List<SchemaChange>`. Never executes DDL |
| `DynamicSchemaContributor` | For objects registered at any time (a table per `AggregateType`). `contribute` describes what is registered so far; `attach(SchemaChangeSink)` receives each later registration's changes |
| `SchemaChange` | `repeatable(changeId, objectName, statements...)` - runs every time, checksum tracked; `once(...)` - runs once per `(module, changeId, objectName)`, edited-after-applied fails startup. `.describedAs("AggregateType 'Orders'")` (0.60.1) labels the object in the emitted script, the emit log line and validate errors - not part of the ledger key or checksum |
| `SchemaOwnership` | `COMPONENT` (default): the component applies its own schema on construction. `HARNESS`: it leaves it to a harness |
| `SchemaMode` | `CREATE` / `VALIDATE` / `EMIT` / `EXTERNAL`; `schemaOwnership()` is `COMPONENT` only for `CREATE` |
| `EssentialsSchemaHarness` | `new EssentialsSchemaHarness(applier, context, contributors).apply()` - orders by `order()` then `moduleId()`, drops a change described identically twice, rejects one described differently |
| `SchemaApplier` | `apply(List<SchemaChangeSet>)`; `createsSchema()` tells a dynamic contributor whether later objects get created |
| `PostgresqlCreateSchemaApplier` | Executes under the bootstrap advisory lock, records in `essentials_schema_history` |
| `PostgresqlValidateSchemaApplier` | Executes nothing; throws `SchemaValidationException` (`problems()`) for every change not in the ledger or recorded with other statements. Checks the ledger, not the catalog |
| `PostgresqlEmitSchemaApplier` | Writes the whole schema as one script (`PostgresqlSchemaScript`) - one transaction, bootstrap lock, header per module, ledger rows included, re-runnable. Needs no connection |
| `ExternalSchemaApplier` | Executes and verifies nothing |

Without Spring - applying components built with `SchemaOwnership.HARNESS`:

```java
var queues = PostgresqlDurableQueues.builder()
                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                    .setSchemaOwnership(SchemaOwnership.HARNESS)
                                    .build();
new EssentialsSchemaHarness(new PostgresqlValidateSchemaApplier(jdbi),   // or Create / Emit
                            SchemaContext.empty(),
                            List.of(queues, fencedLockManager, persistenceStrategy))
        .apply();
```

Your own contributor, e.g. for an application table:

```java
public final class OrderViewSchema implements EssentialsSchemaContributor {
    public String moduleId() { return "order-view"; }
    public int order() { return SchemaOrder.ORDER_APPLICATION; }
    public List<SchemaChange> contribute(SchemaContext context) {
        return List.of(SchemaChange.repeatable("order-view-table", "order_view",
                                               "CREATE TABLE IF NOT EXISTS order_view (id TEXT PRIMARY KEY, total NUMERIC)"));
    }
}
```

As a Spring bean it is applied with the Essentials ones. Rules that hold everywhere:
- A statement must be safe against an object that already exists - there is no special adoption path for databases
  created before 0.60; the first start runs everything and records it.
- `objectName` is a validated identifier and part of the ledger key; for an index change use its table.
- Keep repeatable changes repeatable: prefer `IF [NOT] EXISTS` over `once(...)`. `once` is for what must not run
  twice (a backfill, a type change); editing a `once` change after release fails startup - ship a new change id.
- DDL outside a contributor fails the `EssentialsSchemaRules` guard in `foundation-test` - see
  [LLM-foundation-test.md](./LLM-foundation-test.md).

## Utilities

### JSONSerializer

**Package**: `dk.trustworks.essentials.components.foundation.json`

Jackson 3 (`tools.jackson`) only. Build persistence serializers through `EssentialsObjectMappers`, which carries the
canonical configuration the persisted wire format depends on (field access, ISO-8601 dates, final-field mutation
re-enabled, Essentials value-type modules registered). That format is byte-identical to what the Jackson 2 mapper of
0.50 wrote, so data persisted before 0.60 stays readable. A hand-assembled `ObjectMapper` used for persistence drifts
from that format (for example, value types written as `{"value":"…"}`, or a `Duration` as `"PT30S"`), and the drift
shows on replay, not on write.

```java
// Canonical serializer (Jackson3JSONSerializer over the canonical mapper)
JSONSerializer serializer = EssentialsObjectMappers.createJSONSerializer();

// Canonical mapper + extra application modules (tools.jackson.databind.JacksonModule)
tools.jackson.databind.ObjectMapper mapper = EssentialsObjectMappers.createJackson3ObjectMapper(new MyModule());
JSONSerializer custom = new Jackson3JSONSerializer(mapper);

String json = serializer.serialize(order);
byte[] bytes = serializer.serializeAsBytes(order);
Order order = serializer.deserialize(json, Order.class);
Object event = serializer.deserialize(json, "com.example.OrderCreatedEvent");

// Without immutable-jackson3's Objenesis fallback (reading only; written JSON identical) (0.60.1)
tools.jackson.databind.ObjectMapper noImmutable = EssentialsObjectMappers.createJackson3ObjectMapper(false);

// Does a mapper Essentials did not build reproduce the persisted format? (0.60.1) Empty = yes
List<WireFormatDeviation> deviations = EssentialsObjectMappers.findWireFormatDeviations(someMapper);
```

`findWireFormatDeviations` checks behaviour, not configuration: it writes and reads small probe types and compares
with the 0.50 bytes - field access, getter / `is`-getter visibility, ISO-8601 dates, numeric `Duration`, a missing
primitive reading as its default, unknown properties ignored, untyped floats as `BigDecimal`, and value types as JSON
primitives when `types-jackson3` is present. Each `WireFormatDeviation` carries `aspect`, `expected`, `actual`. The
Spring Boot starters run it on every `Jackson3JSONSerializer`-backed serializer bean (`essentials.json.wire-format-check`,
[starters](./LLM-spring-boot-starter-modules.md#json-wire-format-check)).

⚠️ `EssentialsJacksonModules.modules()` (used by `EssentialsObjectMappers`) throws `IllegalStateException` when a
0.50-era Jackson 2 `types-jackson` / `immutable-jackson` jar is on the classpath (same FQCNs, wrong Jackson major) —
depend on `types-jackson3` / `immutable-jackson3`. Since 0.60.1 it also throws when `immutable-jackson3` is present but
`org.objenesis:objenesis` is not (provided by design, so the application declares it): without it every read of a
persisted object would fail with `NoClassDefFoundError: org/objenesis/...` after a normal startup.

⚠️ Under Jackson 3 a constructor parameter **name** is part of the JSON contract. Jackson 3 reads parameter names from
the bytecode (classes compiled with `-parameters`, and Kotlin) and uses a class's constructor as a properties-based
creator — even when a no-arg constructor exists. The 0.50 Jackson 2 mapper registered no parameter-names module and
populated fields instead, so types that worked then can break now. A parameter whose name does not match the JSON
property it receives gets `null`, and the object fails its own `requireNonNull` or comes back half-populated. Two
shapes bite:
- a parameter named differently from the field it assigns (`priceValidity` → field `priceValidityPeriod`);
- a parameter that is not a property at all because the value is routed elsewhere — the classic `Event<ID>`
  subclass taking `orderId` and calling `aggregateId(orderId)`, which persists as `aggregateId`.

Nothing fails on write: a service can append events for days and then fail on replay. Fix it on the type — rename
the parameter, or annotate it `@JsonProperty("…")` (`com.fasterxml.jackson.annotation`, shared by both Jackson
majors). `ConstructorDetector.EXPLICIT_ONLY` does **not** help: with no other way to construct the type, Jackson 3
uses the sole constructor regardless.

⚠️ Upgrading from 0.50: `JacksonJSONSerializer` (Jackson 2) was removed — use `Jackson3JSONSerializer` or
`EssentialsObjectMappers.createJSONSerializer()`.

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

### Run report (startup, runtime, shutdown)

Since 0.60.1. `EssentialsRunReport` (`dk.trustworks.essentials.components.foundation.startup`) collects the findings
of the Essentials checks so they can be printed as one block at start-up, and again, complete, when the application
stops. Every check still logs where it runs; it also calls `EssentialsRunReport.addToCurrent(finding)`, which adds
to the report bound to the **current thread**; when none is bound (after start-up, another thread) to the **run
report** - the report registered with `registerAsRunReport()` - but only while **exactly one** is registered in the JVM;
otherwise it does nothing. There is no JVM-wide report (a test JVM runs many contexts at once, so with 0 or 2+
registered a runtime finding is only logged by its check). The Spring Boot starters create, bind, register and print one
per context - see [LLM-spring-boot-starter-modules.md § Run Report](LLM-spring-boot-starter-modules.md#run-report).
Without Spring:

```java
var report = new EssentialsRunReport();             // keeps 500 distinct findings; new EssentialsRunReport(n) for another cap
try (var binding = report.bindToCurrentThread()) {     // close on the same thread; bindings nest
    // build and start the Essentials components
}
report.log();   // one block: ERROR if any error finding, WARN if any warning, else INFO "no findings"
var registration = report.registerAsRunReport();       // later findings, any thread, reach it while it is the only one
scheduler.scheduleWithFixedDelay(report::logNewFindings, 5, 5, TimeUnit.MINUTES);   // only what was not logged before; nothing if nothing new
// on shutdown
report.logRunReport();   // every finding of the run, with count and first/last seen
registration.close();
List<EssentialsRunReport.Finding> findings = report.getFindings();       // Finding(severity, source, message, fix)
List<EssentialsRunReport.Occurrences> seen = report.getOccurrences();     // Occurrences(finding, count, firstSeen, lastSeen)
```

Identical findings (equal severity, source, message and fix) are kept once, with a count and first/last-seen time.
Beyond the cap further distinct findings are only counted (`getOverflowCount()`), so a noisy runtime finding cannot
grow memory. A shutdown log can be lost (SIGTERM followed by a kill, a log shipper already gone) - that is what
`logNewFindings()` is for.

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
boolean installed   = PostgresqlUtil.isPGExtensionAvailable(handle, "pg_cron");    // already CREATEd in this database
boolean creatable   = PostgresqlUtil.isPGExtensionInstallable(handle, "pg_cron");  // offered by the server
boolean preloaded   = PostgresqlUtil.isPGLibraryPreloaded(handle, "pg_cron");      // in shared_preload_libraries
boolean created     = PostgresqlUtil.executeAllowingRefusal(handle, "CREATE EXTENSION IF NOT EXISTS pg_cron");  // refusal does not abort the UoW
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

Required: `setJdbi`, `setJsonSerializer`, `setEventBus`. Optional (default): `setPollingInterval` (50 ms), `setFilterDuplicateNotifications` (true), `setChannelDeduplicationPolicy` (`ChannelDeduplicationPolicy.allChannels()`), `setLivenessCheckInterval` (10 s, `Duration.ZERO` disables), `setConnectionLossLogLevel` (WARN), `setConnectionLossLogInterval` (1 min), `setPollThreadName`, `setListenerJdbi(Jdbi)` / `setListenerDataSource(DataSource)` (LISTEN connection outside the app pool). The 5-argument constructor still works.

Behaviour (0.60.1+):
- Holds one LISTEN connection for as long as it polls, borrowed from `setJdbi`'s pool unless a listener `Jdbi`/`DataSource` is set (starter: `essentials.multi-table-change-listener.dedicated-connection=true`).
- Probes it with `Connection.isValid()` every liveness interval - catches a half-open socket. A lost connection logs at WARN (rate-limited) and is reopened on the next poll; the new connection re-LISTENs every registered table.
- Delivery is **at most once**: notifications sent while no connection listens are lost. Treat them as wake-up hints, never as the data.
- Polling starts in `start()`; `stop()` closes the connection and `start()` resumes. A listener never `start()`ed polls from its first `listenToNotificationsFor`.
- Channel names are lower-cased (`Locale.ROOT`): `"Orders"` and `"orders"` are the same channel.
- Duplicates are filtered per channel, only when a `NotificationDuplicationFilter` is registered; a payload with no key (or not JSON) is never dropped as a duplicate.
- `TableChangeNotification.equals` compares class, table name and operation only - a subclass with state must override `equals`/`hashCode`.

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

See [README Security](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/foundation/README.md#security) for full details.

## Admin APIs

**Package**: `dk.trustworks.essentials.components.foundation.*.api`

All APIs require `principal` parameter for authorization. Throw `EssentialsSecurityException` if unauthorized.

| API | Key Methods |
|-----|-------------|
| `DBFencedLockApi` | `getAllLocks()`, `releaseLock()` |
| `DurableQueuesApi` | `getQueueNames()`, `getQueuedMessages()`, `resurrectDeadLetterMessage()`, `deleteMessage()` |
| `SchedulerApi` | `getPgCronJobs()`, `getExecutorJobs()`, `runJobNow(principal, jobName)` — runs a registered job once and returns `ApiScheduledJobRun` (succeeded, duration, error); requires `SCHEDULER_WRITER`. An executor job only on the scheduler-lock holder (else `ScheduledJobNotRunnableHereException`, 409 over HTTP, naming the holder); a pg_cron job calls its registered function directly from any instance, not recorded in `cron.job_run_details`. Not coordinated with a scheduled run of the same job |
| `PostgresqlQueryStatisticsApi` | `getSlowestQueries(principal, QueryStatisticsOrder, limit)`, `getTopTenSlowestQueries()` (requires `pg_stat_statements`: in the server's `shared_preload_libraries`, and created in the database — the API creates it at startup when the server preloads it and the role may create extensions; otherwise it returns an empty list) |
| `PostgresqlTableStatisticsApi` | `fetchTableStatistics()` — size, activity, dead rows, cache hit and last vacuum/analyze for every table the registered `PostgresqlStatisticsTableProvider`s report, each tagged with a section |

**Ranking slow queries.** `getTopTenSlowestQueries()` ranks by cumulative time (`TOTAL_TIME`), which a busy system's
cheap, constantly running statements dominate — queue polling above all. Use `getSlowestQueries(...)` with
`MEAN_TIME` or `MAX_TIME` to find statements that are slow per call; `CALLS` and `BLOCKS_READ` rank by load and I/O.
Only statements for the current database are returned (`pg_stat_statements` is cluster-wide), and `limit` is capped
at `MAX_SLOWEST_QUERIES_LIMIT` (100).

**Reporting tables.** `DefaultPostgresqlTableStatisticsApi` reports what its `PostgresqlStatisticsTableProvider`s
contribute, asked on every request. A `PostgresqlStatisticsTable` is `(section, tableName)`; the `SECTION_*` constants
cover the Essentials components, and any other section id is reported after them. Names resolve through
`to_regclass`, so a table that does not exist yet is left out rather than failing. The Spring Boot starters register a
provider per component with its configured table names; add a provider bean to report your own tables:

```java
@Bean
PostgresqlStatisticsTableProvider orderTables() {
    return PostgresqlStatisticsTableProvider.of("orders", "order_view", "order_lines_view");
}
```

`cacheHitRatio` is a percentage 0-100 (one decimal), `null` until the table has had block access.

Index tuning signals on `ApiTableStatistics`:
- `rowsHotUpdated` / `hotUpdateRatio()` — share of updates that touched no index. Low on a frequently updated table
  means an index covers an updated column, or pages lack free space (`fillfactor`).
- `indexes` — one `ApiIndexStatistics` per index: size, `idxScan`, entries read, rows fetched, cache hit, and
  `unique` / `primary` / `valid`. `unused()` is `idxScan == 0` on a non-unique, non-primary index: a drop candidate
  once the statistics cover a representative period. Scans on read replicas are not counted. `valid == false` is a
  leftover of a failed `CREATE INDEX CONCURRENTLY`, maintained on every write and never used.

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

See [README](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/foundation/README.md) for full examples.

## See Also

- [README](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/foundation/README.md) - full documentation
- [postgresql-queue](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues
- [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock
- [springdata-mongo-distributed-fenced-lock](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock
- [postgresql-event-store](./LLM-postgresql-event-store.md) - Event Store using foundation patterns
- [reactive](./LLM-reactive.md) - EventBus and CommandBus abstractions
