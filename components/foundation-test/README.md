# Essentials Components - Foundation Test

> **NOTE:** **The library is WORK-IN-PROGRESS**

This module provides reusable abstract integration test templates for testing `FencedLockManager` and `DurableQueues` implementations.  
These templates verify consistent behavior across different database backends (PostgreSQL, MongoDB).

**LLM Context:** [LLM-foundation-test.md](../../LLM/LLM-foundation-test.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Why Use This Module](#why-use-this-module)
- [FencedLock Test Templates](#fencedlock-test-templates)
  - [DBFencedLockManagerIT](#dbfencedlockmanagerit)
  - [DBFencedLockManager_MultiNode_ReleaseLockIT](#dbfencedlockmanager_multinode_releaselockit)
- [DurableQueues Test Templates](#durablequeues-test-templates)
  - [DurableQueuesIT](#durablequeuesit)
  - [LocalCompetingConsumersDurableQueueIT](#localcompetingconsumersdurablequeueit)
  - [LocalOrderedMessagesDurableQueueIT](#localorderedmessagesdurablequeueit)
  - [LocalOrderedMessagesRedeliveryDurableQueueIT](#localorderedmessagesredeliverydurablequeueit)
  - [DistributedCompetingConsumersDurableQueuesIT](#distributedcompetingconsumersdurablequeuesit)
  - [DuplicateConsumptionDurableQueuesIT](#duplicateconsumptiondurablequeuesit)
  - [DurableQueuesLoadIT](#durablequeuesloadit)
- [DurableLocalCommandBus Test Template](#durablelocalcommandbus-test-template)
- [Test Utilities](#test-utilities)
- [Test Data Classes](#test-data-classes)
- [Implementation Examples](#implementation-examples)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-test</artifactId>
    <version>${essentials.version}</version>
    <scope>test</scope>
</dependency>
```

**Dependencies included:**
- JUnit Jupiter
- AssertJ
- Awaitility
- Mockito
- Testcontainers

## Why Use This Module

### Consistent Testing Across Implementations

The `foundation` module defines abstract interfaces (`FencedLockManager`, `DurableQueues`).   
Multiple implementations exist:

| Interface | PostgreSQL Implementation | MongoDB Implementation |
|-----------|---------------------------|------------------------|
| `FencedLockManager` | `PostgresqlFencedLockManager` | `MongoFencedLockManager` |
| `DurableQueues` | `PostgresqlDurableQueues` | `MongoDurableQueues` |

Each implementation must pass the **same behavioral tests** to ensure consistent semantics.  
This module provides those tests as abstract classes that implementation-specific tests extend.

### How It Works

1. Extend the abstract test class
2. Implement factory methods to create your implementation
3. Run tests - all behavioral assertions are inherited

```java
// Your implementation's test class
public class PostgresqlFencedLockManagerIT
    extends DBFencedLockManagerIT<PostgresqlFencedLockManager> {

    @Override
    protected PostgresqlFencedLockManager createLockManagerNode1() {
        return PostgresqlFencedLockManager.builder()
            .setJdbi(jdbi)
            .setLockTableName("fenced_locks")
            // ... configuration
            .build();
    }

    @Override
    protected PostgresqlFencedLockManager createLockManagerNode2() {
        // Second instance for multi-node tests
    }

    @Override
    protected void disruptDatabaseConnection() {
        // Testcontainers: pause container, drop connections, etc.
    }

    @Override
    protected void restoreDatabaseConnection() {
        // Restore connectivity
    }

    @Override
    protected boolean isConnectionRestored() {
        // Check if connection is working again
    }
}
```

---

## FencedLock Test Templates

**Package:** `dk.trustworks.essentials.components.foundation.test.fencedlock`

### `DBFencedLockManagerIT`

**Extends:** Abstract class parameterized on `LOCK_MANAGER extends DBFencedLockManager<?, ?>`

Comprehensive integration tests for `FencedLockManager` implementations covering core lock operations and multi-node scenarios.

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_that_we_can_perform_tryAcquire_on_a_lock_and_release_it_again` | Non-blocking lock acquisition with `tryAcquireLock()` |
| `verify_that_we_can_acquire_a_lock_and_release_it_again` | Blocking lock acquisition with `acquireLock()` |
| `stopping_a_lockManager_releases_all_acquired_locks` | Graceful shutdown releases all locks |
| `verify_that_acquireLockAsync_allows_us_to_acquire_locks_asynchronously` | Async acquisition with `LockCallback` |
| `verify_that_acquireLockAsync_allows_us_to_acquire_a_timedout_lock_asynchronously` | Lock takeover after timeout |
| `verify_loosing_db_connection_no_locks_are_released` | Lock retention during DB connectivity loss |

#### Abstract Methods to Implement

```java
protected abstract LOCK_MANAGER createLockManagerNode1();
protected abstract LOCK_MANAGER createLockManagerNode2();
protected abstract void disruptDatabaseConnection();
protected abstract void restoreDatabaseConnection();
protected abstract boolean isConnectionRestored();
```

#### Key Assertions

- Lock exclusivity: Node 2 cannot acquire a lock held by Node 1
- Fence token increments on re-acquisition
- Lock callbacks receive acquisition/release notifications
- Lock confirmation heartbeats update timestamps

---

### `DBFencedLockManager_MultiNode_ReleaseLockIT`

**Extends:** Abstract class parameterized on `LOCK_MANAGER extends DBFencedLockManager<?, ?>`

Tests lock release behavior when database connectivity is disrupted.

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_loosing_db_connection_all_locally_acquired_locks_are_released` | Locks are released locally when DB connection fails |

#### Behavior Verified

1. Node 1 acquires lock, Node 2 waits
2. Database connection is disrupted for both nodes
3. Node 1 releases lock locally (can't confirm, assumes lost)
4. Connection restored
5. Either Node 1 or Node 2 acquires the lock (race condition acceptable)

---

## DurableQueues Test Templates

**Package:** `dk.trustworks.essentials.components.foundation.test.messaging.queue`

### `DurableQueuesIT`

**Extends:** Abstract class parameterized on `DURABLE_QUEUES extends DurableQueues`, `UOW extends UnitOfWork`, `UOW_FACTORY extends UnitOfWorkFactory<UOW>`

Core integration tests for `DurableQueues` implementations covering message lifecycle, redelivery, and dead letter handling.

#### Test Coverage

| Test | Description |
|------|-------------|
| `test_simple_enqueueing_and_afterwards_querying_queued_messages` | Queue messages and verify metadata |
| `verify_queued_messages_are_dequeued_in_order` | FIFO delivery order |
| `verify_a_message_queues_as_a_dead_letter_message_is_marked_as_such_and_will_not_be_delivered` | DLQ messages excluded from delivery |
| `verify_a_that_as_long_as_an_ordered_message_with_same_key_and_a_lower_key_order_exists_as_a_dead_letter_message_then_no_further_messages_with_the_same_key_will_be_delivered` | Ordered message DLQ blocking |
| `verify_hasOrderedMessageQueuedForKey` | Check for pending ordered messages |
| `verify_failed_messages_are_redelivered` | Automatic redelivery on failure |
| `verify_a_message_that_failed_too_many_times_is_marked_as_dead_letter_message_AND_the_message_can_be_resurrected` | DLQ after max retries + resurrection |
| `test_two_stage_redelivery_where_a_message_about_to_be_marked_as_a_deadletter_message_is_queued_with_a_redelivery_delay` | Interceptor-based DLQ override |
| `verify_a_message_can_manually_be_marked_as_dead_letter_message_AND_the_message_can_afterwards_be_resurrected` | Manual DLQ + resurrection |
| `test_messagehandler_with_call_to_markForRedeliveryIn` | Manual redelivery scheduling |
| `verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` | Deserialization failure handling |

#### Abstract Methods to Implement

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory,
                                                       JSONSerializer jsonSerializer);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
protected abstract void resetQueueStorage(UOW_FACTORY unitOfWorkFactory);
protected abstract JSONSerializer createJSONSerializer();
```

#### Provided Helpers

```java
// Transaction wrapper that respects transactional mode
protected <R> R withDurableQueue(Supplier<R> supplier);
protected void usingDurableQueue(Runnable action);

// Recording handler for verification
protected static class RecordingQueuedMessageHandler implements QueuedMessageHandler {
    public ConcurrentLinkedQueue<Message> messages;
    public RecordingQueuedMessageHandler(Consumer<Message> functionLogic);
}
```

---

### `LocalCompetingConsumersDurableQueueIT`

Tests high-throughput message consumption with multiple parallel consumers on a single node.

**Configuration:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_queued_messages_are_dequeued_in_order` | All messages delivered exactly once, no duplicates |

#### Key Assertions

- All 2000 messages consumed
- No duplicates (distinct count equals total)
- Messages delivered to consumers (order not guaranteed across parallel threads)
- Performance timing logged

---

### `LocalOrderedMessagesDurableQueueIT`

Tests ordered message delivery per key with parallel consumers.

**Configuration:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`
- 45 distinct message keys

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_queued_ordered_messages_are_dequeued_in_order_per_key` | Messages with same key delivered in sequence |

#### Key Assertions

- All messages delivered exactly once
- For each key, messages received in order: `order=0`, `order=1`, `order=2`, ...
- Different keys can be processed concurrently

---

### `LocalOrderedMessagesRedeliveryDurableQueueIT`

Tests ordered message delivery with redelivery scenarios - ensures ordering is maintained even when messages fail and are redelivered.

**Configuration:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`
- `MAXIMUM_NUMBER_OF_REDELIVERIES = 5`

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_queued_ordered_messages_are_dequeued_in_order_per_key_even_if_some_messages_are_redelivered` | Ordering preserved through redelivery cycles |

---

### `DistributedCompetingConsumersDurableQueuesIT`

Tests message consumption across two separate `DurableQueues` instances (simulating multiple pods/nodes).

**Configuration:**
- `NUMBER_OF_MESSAGES = 1000`
- `PARALLEL_CONSUMERS = 20` (10 per node)

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_queued_messages_are_dequeued_in_order` | Distributed consumption without duplicates |
| `verify_queued_messages_are_dequeued_in_order_with_db_connectivity_issues` | Resilience during DB disruption |

#### Abstract Methods

```java
protected abstract void disruptDatabaseConnection();
protected abstract void restoreDatabaseConnection();
```

#### Key Assertions

- Both nodes consume messages (load distributed)
- No duplicates across nodes
- All messages consumed exactly once
- Resilient to temporary DB connectivity loss

---

### `DuplicateConsumptionDurableQueuesIT`

Specifically tests for duplicate message consumption bugs in multi-node scenarios (e.g., Bug #19).

**Configuration:**
- `NUMBER_OF_MESSAGES = 40`
- `PARALLEL_CONSUMERS = 1`
- `PROCESSING_DELAY_MS = 3000` (artificially slow processing)
- `DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS = 50` (short timeout to trigger reset)
- `CONSUMER_START_DELAY_MS = 200` (staggered consumer start)

#### Test Coverage

| Test | Description |
|------|-------------|
| `verify_no_duplicate_message_consumption` | No message consumed more than once |
| `verify_no_duplicate_message_consumption_with_db_connectivity_issues` | No duplicates during DB disruption |

#### Why This Test Exists

The test creates conditions that could cause duplicate consumption:
1. Short message handling timeout (50ms)
2. Long processing delay (3000ms)
3. Staggered consumer start (Instance 2 starts 200ms after Instance 1)

Messages queued in the worker pool wait longer than the timeout, triggering "stuck" message reset, which another instance could then fetch.

#### Key Assertions

- Tracks consumption count per `QueueEntryId`
- Fails if any message is consumed more than once
- Both instances must consume some messages (no starvation)

---

### `DurableQueuesLoadIT`

Load testing to verify index optimization and consumption performance with large message volumes.

**Configuration:**
- 20,000 messages queued in single transaction

#### Test Coverage

| Test | Description |
|------|-------------|
| `queue_a_large_number_of_messages` | Consumption starts within 5 seconds despite large queue |

#### Purpose

Validates that database indexes are properly utilized - consumption shouldn't be delayed significantly when many messages are queued.

---

## DurableLocalCommandBus Test Template

**Package:** `dk.trustworks.essentials.components.foundation.test.reactive.command`

### `AbstractDurableLocalCommandBusIT`

Tests for `DurableLocalCommandBus` covering synchronous, asynchronous, and fire-and-forget command handling.

#### Test Coverage

| Test | Description |
|------|-------------|
| `test_sync_send` | Synchronous command execution with result |
| `test_sync_send_with_command_processing_exception` | Exception propagation on sync send |
| `test_async_send` | Async command with `Mono.block()` |
| `test_sendAndDontWait` | Fire-and-forget without transaction |
| `test_sendAndDontWait_with_managed_transaction` | Fire-and-forget within UnitOfWork |
| `test_sendAndDontWait_with_error` | Error handler invoked, message to DLQ |
| `test_sendAndDontWait_with_delay` | Delayed command execution |
| `test_no_matching_command_handler` | `NoCommandHandlerFoundException` |
| `test_multiple_matching_command_handlers` | `MultipleCommandHandlersFoundException` |

#### Abstract Methods

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
```

---

## Test Utilities

### `ProxyJSONSerializer`

A proxy `JSONSerializer` that can simulate deserialization failures for specific payload types.

**Package:** `dk.trustworks.essentials.components.foundation.test.messaging.queue`

#### Usage

```java
ProxyJSONSerializer proxy = new ProxyJSONSerializer(actualSerializer);

// Enable corruption for a specific type
proxy.enableJSONCorruptionDuringDeserialization(OrderEvent.OrderAdded.class);

// Subsequent deserialization of OrderAdded will fail
// The corruption auto-disables after one failure

// Or disable manually
proxy.disableJSONCorruptionDuringDeserialization();
```

#### Purpose

Used in `DurableQueuesIT.verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` to verify that deserialization failures are handled correctly (message goes to DLQ).

---

## Test Data Classes

**Package:** `dk.trustworks.essentials.components.foundation.test.messaging.queue.test_data`

| Class | Description |
|-------|-------------|
| `OrderId` | `CharSequenceType` for order identifiers |
| `CustomerId` | `CharSequenceType` for customer identifiers |
| `ProductId` | `CharSequenceType` for product identifiers |
| `AccountId` | `IntegerType` for account identifiers (used in ordered message tests) |
| `OrderEvent` | Base event class with subclasses: `OrderAdded`, `ProductAddedToOrder`, `ProductOrderQuantityAdjusted`, `ProductRemovedFromOrder`, `OrderAccepted` |
| `ProductEvent` | Product-related events |

These classes provide realistic test data for queue message testing.

---

## Implementation Examples

See how the templates are used in implementation modules:

| Implementation | Test Class Location |
|----------------|---------------------|
| PostgreSQL FencedLock | [postgresql-distributed-fenced-lock/src/test/java](../postgresql-distributed-fenced-lock/src/test/java) |
| MongoDB FencedLock | [springdata-mongo-distributed-fenced-lock/src/test/java](../springdata-mongo-distributed-fenced-lock/src/test/java) |
| PostgreSQL DurableQueues | [postgresql-queue/src/test/java](../postgresql-queue/src/test/java) |
| MongoDB DurableQueues | [springdata-mongo-queue/src/test/java](../springdata-mongo-queue/src/test/java) |

### Example: PostgreSQL DurableQueues Test

```java
public class PostgresqlDurableQueuesIT
    extends DurableQueuesIT<PostgresqlDurableQueues, JdbiUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    private Jdbi jdbi;

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(),
                           postgres.getUsername(),
                           postgres.getPassword());
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected PostgresqlDurableQueues createDurableQueues(
            JdbiUnitOfWorkFactory unitOfWorkFactory,
            JSONSerializer jsonSerializer) {
        return PostgresqlDurableQueues.builder()
            .setUnitOfWorkFactory(unitOfWorkFactory)
            .setQueueTableName("durable_queues")
            .setJsonSerializer(jsonSerializer)
            .build();
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        jdbi.useHandle(handle ->
            handle.execute("TRUNCATE TABLE durable_queues"));
    }

    @Override
    protected JSONSerializer createJSONSerializer() {
        return new JacksonJSONSerializer(
            JsonMapper.builder()
                .addModule(new EssentialTypesJacksonModule())
                .build());
    }
}
```
