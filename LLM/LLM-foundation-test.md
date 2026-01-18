# Foundation Test - LLM Reference

> Token-efficient reference for LLMs. See [README](../components/foundation-test/README.md) for detailed documentation.

## Quick Facts
- **Base package**: `dk.trustworks.essentials.components.foundation.test`
- **Purpose**: Abstract integration test templates for `FencedLockManager` and `DurableQueues` implementations
- **Scope**: Test only
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-test</artifactId>
    <scope>test</scope>
</dependency>
```

**Dependencies from other modules**:
- `FencedLockManager`, `DBFencedLockManager`, `LockName`, `FencedLock` from [foundation](./LLM-foundation.md)
- `DurableQueues`, `QueueName`, `ConsumeFromQueue`, `QueuedMessage` from [foundation](./LLM-foundation.md)
- `DurableLocalCommandBus`, `UnitOfWorkFactory` from [foundation](./LLM-foundation.md)

## TOC
- [Purpose](#purpose)
- [Maven Dependency](#maven-dependency)
- [FencedLock Test Templates](#fencedlock-test-templates)
- [DurableQueues Test Templates](#durablequeues-test-templates)
- [DurableLocalCommandBus Test Template](#durablelocalcommandbus-test-template)
- [Test Utilities](#test-utilities)
- [Test Data Classes](#test-data-classes)
- [Implementation Pattern](#implementation-pattern)
- [Common Pitfalls](#common-pitfalls)

## Purpose

Multiple implementations of `FencedLockManager` and `DurableQueues` exist (PostgreSQL, MongoDB). All MUST pass identical behavioral tests. This module provides those tests as abstract classes.

**Pattern:**
1. Extend abstract test template
2. Implement factory methods
3. Inherit all behavioral assertions

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-test</artifactId>
    <version>${essentials.version}</version>
    <scope>test</scope>
</dependency>
```

## FencedLock Test Templates

**Base package**: `dk.trustworks.essentials.components.foundation.test.fencedlock`

### DBFencedLockManagerIT

**Signature:**
```java
public abstract class DBFencedLockManagerIT<LOCK_MANAGER extends DBFencedLockManager<?, ?>>
```

Core integration tests for `FencedLockManager` implementations.

#### Abstract Methods

```java
protected abstract LOCK_MANAGER createLockManagerNode1();
protected abstract LOCK_MANAGER createLockManagerNode2();
protected abstract void disruptDatabaseConnection();
protected abstract void restoreDatabaseConnection();
protected abstract boolean isConnectionRestored();
```

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_that_we_can_perform_tryAcquire_on_a_lock_and_release_it_again` | Non-blocking `tryAcquireLock()` + release |
| `verify_that_we_can_acquire_a_lock_and_release_it_again` | Blocking `acquireLock()` + release |
| `stopping_a_lockManager_releases_all_acquired_locks` | Graceful shutdown releases locks |
| `verify_that_acquireLockAsync_allows_us_to_acquire_locks_asynchronously` | Async acquisition with `LockCallback` |
| `verify_that_acquireLockAsync_allows_us_to_acquire_a_timedout_lock_asynchronously` | Lock takeover after timeout |
| `verify_loosing_db_connection_no_locks_are_released` | Lock retention during DB disruption |

#### Key Assertions

- Lock exclusivity (Node 2 cannot acquire lock held by Node 1)
- Fence token increments per acquisition
- Callbacks receive `onLockAcquired()` / `onLockReleased()`
- Heartbeat updates `lockLastConfirmedTimestamp`

#### Lifecycle

```java
@BeforeEach void setup()    // Creates and starts both managers
@AfterEach  void cleanup()  // Stops both managers
```

#### Helpers

```java
public static void deleteAllLocksInDBWithRetry(DBFencedLockManager<?, ?> lockManager);
protected LOCK_MANAGER getLockManagerNode1();
protected LOCK_MANAGER getLockManagerNode2();
```

---

### DBFencedLockManager_MultiNode_ReleaseLockIT

**Signature:**
```java
public abstract class DBFencedLockManager_MultiNode_ReleaseLockIT<LOCK_MANAGER extends DBFencedLockManager<?, ?>>
```

Tests lock release during DB connectivity issues.

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_loosing_db_connection_all_locally_acquired_locks_are_released` | Locks released locally when DB connection fails |

#### Test Flow

1. Node 1 acquires lock, Node 2 waits
2. DB connection disrupted
3. Node 1 releases lock locally (cannot confirm)
4. Connection restored
5. Either Node 1 or Node 2 acquires lock (race acceptable)

---

## DurableQueues Test Templates

**Base package**: `dk.trustworks.essentials.components.foundation.test.messaging.queue`

### DurableQueuesIT

**Signature:**
```java
public abstract class DurableQueuesIT<DURABLE_QUEUES extends DurableQueues,
                                       UOW extends UnitOfWork,
                                       UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Comprehensive integration tests for `DurableQueues` implementations.

#### Abstract Methods

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory,
                                                       JSONSerializer jsonSerializer);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
protected abstract void resetQueueStorage(UOW_FACTORY unitOfWorkFactory);
protected abstract JSONSerializer createJSONSerializer();
```

#### Helpers

```java
// Auto-wraps in UnitOfWork if TransactionalMode.FullyTransactional
protected <R> R withDurableQueue(Supplier<R> supplier);
protected void usingDurableQueue(Runnable action);

// Recording handler for assertions
protected static class RecordingQueuedMessageHandler implements QueuedMessageHandler {
    public ConcurrentLinkedQueue<Message> messages;
    public RecordingQueuedMessageHandler(Consumer<Message> functionLogic);
}
```

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `test_simple_enqueueing_and_afterwards_querying_queued_messages` | Queue messages, verify metadata |
| `verify_queued_messages_are_dequeued_in_order` | FIFO delivery |
| `verify_a_message_queues_as_a_dead_letter_message_is_marked_as_such_and_will_not_be_delivered` | DLQ exclusion |
| `verify_a_that_as_long_as_an_ordered_message_with_same_key_and_a_lower_key_order_exists_as_a_dead_letter_message_then_no_further_messages_with_the_same_key_will_be_delivered` | Ordered DLQ blocking |
| `verify_hasOrderedMessageQueuedForKey` | Check for pending ordered messages |
| `verify_failed_messages_are_redelivered` | Automatic redelivery |
| `verify_a_message_that_failed_too_many_times_is_marked_as_dead_letter_message_AND_the_message_can_be_resurrected` | DLQ after max retries + resurrection |
| `test_two_stage_redelivery_where_a_message_about_to_be_marked_as_a_deadletter_message_is_queued_with_a_redelivery_delay` | Interceptor DLQ override |
| `verify_a_message_can_manually_be_marked_as_dead_letter_message_AND_the_message_can_afterwards_be_resurrected` | Manual DLQ + resurrection |
| `test_messagehandler_with_call_to_markForRedeliveryIn` | Manual redelivery scheduling |
| `verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` | Deserialization failure → DLQ |

---

### LocalCompetingConsumersDurableQueueIT

**Signature:**
```java
public abstract class LocalCompetingConsumersDurableQueueIT<DURABLE_QUEUES extends DurableQueues,
                                                              UOW extends UnitOfWork,
                                                              UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

High-throughput parallel consumption test.

**Config:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`

**Test:**
- `verify_queued_messages_are_dequeued_in_order` - All messages delivered exactly once, no duplicates

**Assertions:**
- 2000 messages consumed
- Distinct count = total count (no duplicates)
- Messages distributed across consumers

---

### LocalOrderedMessagesDurableQueueIT

**Signature:**
```java
public abstract class LocalOrderedMessagesDurableQueueIT<DURABLE_QUEUES extends DurableQueues,
                                                           UOW extends UnitOfWork,
                                                           UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Ordered message delivery per key with parallel consumers.

**Config:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`
- 45 distinct keys

**Test:**
- `verify_queued_ordered_messages_are_dequeued_in_order_per_key` - Messages with same key delivered in order

**Assertions:**
- All messages delivered exactly once
- Per key: received in order (`order=0`, `order=1`, `order=2`, ...)
- Different keys processed concurrently

---

### LocalOrderedMessagesRedeliveryDurableQueueIT

**Signature:**
```java
public abstract class LocalOrderedMessagesRedeliveryDurableQueueIT<DURABLE_QUEUES extends DurableQueues,
                                                                     UOW extends UnitOfWork,
                                                                     UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Ordered delivery with redelivery - ordering maintained through failures.

**Config:**
- `NUMBER_OF_MESSAGES = 2000`
- `PARALLEL_CONSUMERS = 20`
- `MAXIMUM_NUMBER_OF_REDELIVERIES = 5`

**Test:**
- `verify_queued_ordered_messages_are_dequeued_in_order_per_key_even_if_some_messages_are_redelivered` - Ordering preserved through redelivery

---

### DistributedCompetingConsumersDurableQueuesIT

**Signature:**
```java
public abstract class DistributedCompetingConsumersDurableQueuesIT<DURABLE_QUEUES extends DurableQueues,
                                                                     UOW extends UnitOfWork,
                                                                     UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Multi-node consumption (simulates pods/instances).

**Config:**
- `NUMBER_OF_MESSAGES = 1000`
- `PARALLEL_CONSUMERS = 20` (10 per node)

**Additional abstract methods:**
```java
protected abstract void disruptDatabaseConnection();
protected abstract void restoreDatabaseConnection();
```

**Tests:**
- `verify_queued_messages_are_dequeued_in_order` - Distributed consumption without duplicates
- `verify_queued_messages_are_dequeued_in_order_with_db_connectivity_issues` - Resilience during DB disruption

**Assertions:**
- Both nodes consume messages (load distributed)
- No duplicates across nodes
- All messages consumed exactly once

---

### DuplicateConsumptionDurableQueuesIT

**Signature:**
```java
public abstract class DuplicateConsumptionDurableQueuesIT<DURABLE_QUEUES extends DurableQueues,
                                                           UOW extends UnitOfWork,
                                                           UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Tests for duplicate consumption bugs (Bug #19).

**Config:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `NUMBER_OF_MESSAGES` | 40 | Small focused set |
| `PARALLEL_CONSUMERS` | 1 | Single consumer |
| `PROCESSING_DELAY_MS` | 3000 | Slow processing |
| `DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS` | 50 | Short timeout → reset trigger |
| `CONSUMER_START_DELAY_MS` | 200 | Staggered start |

**Why these values:**
Processing (3000ms) >> timeout (50ms) creates conditions where message appears "stuck", triggering reset while still processing. Instance 2 could fetch reset message.

**Tests:**
- `verify_no_duplicate_message_consumption` - No message consumed > 1 time
- `verify_no_duplicate_message_consumption_with_db_connectivity_issues` - No duplicates during DB disruption

**Assertions:**
- Tracks consumption count per `QueueEntryId`
- Fails if any message consumed > 1 time
- Both instances must consume (no starvation)

---

### DurableQueuesLoadIT

**Signature:**
```java
public abstract class DurableQueuesLoadIT<DURABLE_QUEUES extends DurableQueues,
                                           UOW extends UnitOfWork,
                                           UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Load test for index optimization.

**Config:**
- 20,000 messages queued in single transaction

**Test:**
- `queue_a_large_number_of_messages` - Consumption starts within 5 seconds despite large queue

**Purpose:**
Validates database indexes utilized - consumption shouldn't delay significantly with many queued messages.

---

## DurableLocalCommandBus Test Template

**Base package**: `dk.trustworks.essentials.components.foundation.test.reactive.command`

### AbstractDurableLocalCommandBusIT

**Signature:**
```java
public abstract class AbstractDurableLocalCommandBusIT<DURABLE_QUEUES extends DurableQueues,
                                                        UOW extends UnitOfWork,
                                                        UOW_FACTORY extends UnitOfWorkFactory<UOW>>
```

Tests for `DurableLocalCommandBus` - sync, async, fire-and-forget.

#### Abstract Methods

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
```

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `test_sync_send` | Sync command execution with result |
| `test_sync_send_with_command_processing_exception` | Exception propagation on sync send |
| `test_async_send` | Async command with `Mono.block()` |
| `test_sendAndDontWait` | Fire-and-forget without transaction |
| `test_sendAndDontWait_with_managed_transaction` | Fire-and-forget within UnitOfWork |
| `test_sendAndDontWait_with_error` | Error handler invoked, message to DLQ |
| `test_sendAndDontWait_with_delay` | Delayed command execution |
| `test_no_matching_command_handler` | `NoCommandHandlerFoundException` |
| `test_multiple_matching_command_handlers` | `MultipleCommandHandlersFoundException` |

---

## Test Utilities

### ProxyJSONSerializer

**Package**: `dk.trustworks.essentials.components.foundation.test.messaging.queue`

Proxy `JSONSerializer` simulating deserialization failures.

**API:**
```java
ProxyJSONSerializer proxy = new ProxyJSONSerializer(actualSerializer);

// Enable corruption for type
proxy.enableJSONCorruptionDuringDeserialization(OrderEvent.OrderAdded.class);

// Subsequent deserialization of OrderAdded fails
// Auto-disables after one failure

// Manual disable
proxy.disableJSONCorruptionDuringDeserialization();
```

**Use case:**
Used in `DurableQueuesIT.verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` to verify deserialization failures → DLQ.

---

## Test Data Classes

**Base package**: `dk.trustworks.essentials.components.foundation.test.messaging.queue.test_data`

| Class | Type | Purpose |
|-------|------|---------|
| `OrderId` | `CharSequenceType` | Order identifiers |
| `CustomerId` | `CharSequenceType` | Customer identifiers |
| `ProductId` | `CharSequenceType` | Product identifiers |
| `AccountId` | `IntegerType` | Account identifiers (ordered message tests) |
| `OrderEvent` | Base event | Subclasses: `OrderAdded`, `ProductAddedToOrder`, `ProductOrderQuantityAdjusted`, `ProductRemovedFromOrder`, `OrderAccepted` |
| `ProductEvent` | Event | Product events |

All immutable, proper `equals()`/`hashCode()`, JSON serialization support.

---

## Implementation Pattern

### FencedLock Implementation Test

```java
package dk.trustworks.essentials.components.postgresql.fencedlock;

import dk.trustworks.essentials.components.foundation.test.fencedlock.DBFencedLockManagerIT;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;

@Testcontainers
public class PostgresqlFencedLockManagerIT
    extends DBFencedLockManagerIT<PostgresqlFencedLockManager> {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    private Jdbi jdbi;

    @BeforeEach
    void setupDb() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(),
                          postgres.getUsername(),
                          postgres.getPassword());
    }

    @Override
    protected PostgresqlFencedLockManager createLockManagerNode1() {
        return PostgresqlFencedLockManager.builder()
            .setJdbi(jdbi)
            .setLockTableName("fenced_locks")
            .setLockTimeOut(Duration.ofSeconds(5))
            .setLockConfirmationInterval(Duration.ofSeconds(1))
            .build();
    }

    @Override
    protected PostgresqlFencedLockManager createLockManagerNode2() {
        return createLockManagerNode1(); // Same config, separate instance
    }

    @Override
    protected void disruptDatabaseConnection() {
        postgres.stop();
    }

    @Override
    protected void restoreDatabaseConnection() {
        postgres.start();
    }

    @Override
    protected boolean isConnectionRestored() {
        try {
            jdbi.withHandle(h -> h.select("SELECT 1").mapTo(Integer.class).one());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### DurableQueues Implementation Test

```java
package dk.trustworks.essentials.components.postgresql.queue;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.jackson.immutable.JacksonJSONSerializer;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
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
            .setSharedQueueTableName("durable_queues")
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

---

## Common Pitfalls

### ⚠️ Forgetting to Start

```java
// ❌ Wrong
durableQueues = createDurableQueues(...);
// Tests fail

// ✅ Correct
durableQueues = createDurableQueues(...);
durableQueues.start();
```

### ⚠️ Missing Transaction Wrapper

```java
// ❌ Wrong - FullyTransactional mode needs wrapping
durableQueues.queueMessage(queueName, message);

// ✅ Correct
withDurableQueue(() -> durableQueues.queueMessage(queueName, message));
```

### ⚠️ Reusing Instances Between Tests

```java
// ❌ Wrong - static instance reused
private static DurableQueues durableQueues;

@BeforeAll
static void setup() {
    durableQueues = createDurableQueues(...);
}

// ✅ Correct - fresh instance per test
private DurableQueues durableQueues;

@BeforeEach
void setup() {
    durableQueues = createDurableQueues(...);
}
```

### ⚠️ Not Cleaning Up

```java
// ❌ Wrong
@AfterEach
void cleanup() {
    // Nothing - resources leak
}

// ✅ Correct
@AfterEach
void cleanup() {
    if (durableQueues != null) {
        durableQueues.stop();
    }
}
```

---

## See Also

- [README.md](../components/foundation-test/README.md) - Full documentation
- [LLM-foundation.md](./LLM-foundation.md) - `FencedLockManager`, `DurableQueues` interfaces
- [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock
- [LLM-postgresql-queue.md](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues
- [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock
- [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues
