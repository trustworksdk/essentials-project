# Foundation Test - LLM Reference

> See [README](../components/foundation-test/README.md) for detailed developer documentation.

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.foundation.test`
- **Purpose**: Reusable abstract integration test templates for `FencedLockManager` and `DurableQueues` implementations
- **Scope**: Test only (`<scope>test</scope>`)
- **Key Dependencies**: JUnit Jupiter, AssertJ, Awaitility, Mockito, Testcontainers
- **Artifact**: `dk.trustworks.essentials.components:foundation-test:${essentials.version}`
- **Status**: WORK-IN-PROGRESS

## TOC
- [Why This Module Exists](#why-this-module-exists)
- [Maven Dependency](#maven-dependency)
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
- [Implementation Pattern](#implementation-pattern)
- [Common Pitfalls](#common-pitfalls)
- [Cross-References](#cross-references)

## Why This Module Exists

Multiple implementations of `FencedLockManager` and `DurableQueues` exist:

| Interface | PostgreSQL | MongoDB |
|-----------|-----------|---------|
| `FencedLockManager` | `PostgresqlFencedLockManager` | `MongoFencedLockManager` |
| `DurableQueues` | `PostgresqlDurableQueues` | `MongoDurableQueues` |

All implementations MUST pass identical behavioral tests. This module provides those tests as abstract classes.

**Pattern:**
1. Extend abstract test template
2. Implement factory methods for your implementation
3. Inherit all behavioral test assertions

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

**Package**: `dk.trustworks.essentials.components.foundation.test.fencedlock`

### DBFencedLockManagerIT

`public abstract class DBFencedLockManagerIT<LOCK_MANAGER extends DBFencedLockManager<?, ?>>`

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

| Test Method | Behavior Verified |
|-------------|-------------------|
| `verify_that_we_can_perform_tryAcquire_on_a_lock_and_release_it_again` | Non-blocking `tryAcquireLock()` + release |
| `verify_that_we_can_acquire_a_lock_and_release_it_again` | Blocking `acquireLock()` + release |
| `stopping_a_lockManager_releases_all_acquired_locks` | Graceful shutdown releases locks |
| `verify_that_acquireLockAsync_allows_us_to_acquire_locks_asynchronously` | Async acquisition with `LockCallback` |
| `verify_that_acquireLockAsync_allows_us_to_acquire_a_timedout_lock_asynchronously` | Lock takeover after timeout |
| `verify_loosing_db_connection_no_locks_are_released` | Lock retention during DB disruption |

#### Key Assertions

- Lock exclusivity: Node 2 cannot acquire lock held by Node 1
- Fence token increments on each acquisition
- Lock callbacks receive `onLockAcquired()` / `onLockReleased()` notifications
- Heartbeat confirmation updates `lockLastConfirmedTimestamp`

#### Lifecycle Hooks

```java
@BeforeEach void setup()    // Creates and starts both lock managers
@AfterEach  void cleanup()  // Stops both lock managers
```

#### Helper Methods

```java
public static void deleteAllLocksInDBWithRetry(DBFencedLockManager<?, ?> lockManager);
protected LOCK_MANAGER getLockManagerNode1();
protected LOCK_MANAGER getLockManagerNode2();
```

---

### DBFencedLockManager_MultiNode_ReleaseLockIT

`public abstract class DBFencedLockManager_MultiNode_ReleaseLockIT<LOCK_MANAGER extends DBFencedLockManager<?, ?>>`

Tests lock release behavior during DB connectivity issues.

#### Abstract Methods

Same as `DBFencedLockManagerIT`.

#### Test Coverage

| Test Method | Behavior Verified |
|-------------|-------------------|
| `verify_loosing_db_connection_all_locally_acquired_locks_are_released` | Locks released locally when DB connection fails |

#### Test Flow

1. Node 1 acquires lock, Node 2 waits
2. DB connection disrupted for both nodes
3. Node 1 releases lock locally (cannot confirm, assumes lost)
4. Connection restored
5. Either Node 1 or Node 2 acquires lock (race condition acceptable)

---

## DurableQueues Test Templates

**Package**: `dk.trustworks.essentials.components.foundation.test.messaging.queue`

### DurableQueuesIT

`public abstract class DurableQueuesIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Comprehensive integration tests for `DurableQueues` implementations.

#### Abstract Methods

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory,
                                                       JSONSerializer jsonSerializer);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
protected abstract void resetQueueStorage(UOW_FACTORY unitOfWorkFactory);
protected abstract JSONSerializer createJSONSerializer();
```

#### Helper Methods

```java
// Transaction-aware helpers
protected <R> R withDurableQueue(Supplier<R> supplier);
protected void usingDurableQueue(Runnable action);
```

Automatically wraps operations in `UnitOfWork` if `TransactionalMode.FullyTransactional`, otherwise executes directly.

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `test_simple_enqueueing_and_afterwards_querying_queued_messages` | Queue messages, verify metadata |
| `verify_queued_messages_are_dequeued_in_order` | FIFO delivery order |
| `verify_a_message_queues_as_a_dead_letter_message_is_marked_as_such_and_will_not_be_delivered` | DLQ messages excluded from delivery |
| `verify_a_that_as_long_as_an_ordered_message_with_same_key_and_a_lower_key_order_exists_as_a_dead_letter_message_then_no_further_messages_with_the_same_key_will_be_delivered` | Ordered message DLQ blocking |
| `verify_hasOrderedMessageQueuedForKey` | Check for pending ordered messages by key |
| `verify_failed_messages_are_redelivered` | Automatic redelivery on failure |
| `verify_a_message_that_failed_too_many_times_is_marked_as_dead_letter_message_AND_the_message_can_be_resurrected` | DLQ after max retries + resurrection |
| `test_two_stage_redelivery_where_a_message_about_to_be_marked_as_a_deadletter_message_is_queued_with_a_redelivery_delay` | Interceptor-based DLQ override |
| `verify_a_message_can_manually_be_marked_as_dead_letter_message_AND_the_message_can_afterwards_be_resurrected` | Manual DLQ + resurrection |
| `test_messagehandler_with_call_to_markForRedeliveryIn` | Manual redelivery scheduling |
| `verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` | Deserialization failure → DLQ |

#### RecordingQueuedMessageHandler

Utility class for test verification:

```java
protected static class RecordingQueuedMessageHandler implements QueuedMessageHandler {
    public ConcurrentLinkedQueue<Message> messages;
    public RecordingQueuedMessageHandler(Consumer<Message> functionLogic);
}
```

Captures all consumed messages for assertions.

---

### LocalCompetingConsumersDurableQueueIT

`public abstract class LocalCompetingConsumersDurableQueueIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

High-throughput parallel consumption test.

#### Configuration

| Parameter | Value |
|-----------|-------|
| `NUMBER_OF_MESSAGES` | 2000 |
| `PARALLEL_CONSUMERS` | 20 |

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_queued_messages_are_dequeued_in_order` | All messages delivered exactly once, no duplicates |

#### Key Assertions

- All 2000 messages consumed
- No duplicates (distinct count = total count)
- Messages distributed across consumers
- Performance timing logged

---

### LocalOrderedMessagesDurableQueueIT

`public abstract class LocalOrderedMessagesDurableQueueIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Ordered message delivery per key with parallel consumers.

#### Configuration

| Parameter | Value |
|-----------|-------|
| `NUMBER_OF_MESSAGES` | 2000 |
| `PARALLEL_CONSUMERS` | 20 |
| Message keys | 45 distinct keys |

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_queued_ordered_messages_are_dequeued_in_order_per_key` | Messages with same key delivered in order |

#### Key Assertions

- All messages delivered exactly once
- Per key: messages received in order (`order=0`, `order=1`, `order=2`, ...)
- Different keys processed concurrently

---

### LocalOrderedMessagesRedeliveryDurableQueueIT

`public abstract class LocalOrderedMessagesRedeliveryDurableQueueIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Ordered message delivery with redelivery - ensures ordering maintained through failures.

#### Configuration

| Parameter | Value |
|-----------|-------|
| `NUMBER_OF_MESSAGES` | 2000 |
| `PARALLEL_CONSUMERS` | 20 |
| `MAXIMUM_NUMBER_OF_REDELIVERIES` | 5 |

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_queued_ordered_messages_are_dequeued_in_order_per_key_even_if_some_messages_are_redelivered` | Ordering preserved through redelivery cycles |

---

### DistributedCompetingConsumersDurableQueuesIT

`public abstract class DistributedCompetingConsumersDurableQueuesIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Multi-node consumption (simulates multiple pods/instances).

#### Configuration

| Parameter | Value |
|-----------|-------|
| `NUMBER_OF_MESSAGES` | 1000 |
| `PARALLEL_CONSUMERS` | 20 total (10 per node) |

#### Abstract Methods (Additional)

```java
protected abstract void disruptDatabaseConnection();
protected abstract void restoreDatabaseConnection();
```

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_queued_messages_are_dequeued_in_order` | Distributed consumption without duplicates |
| `verify_queued_messages_are_dequeued_in_order_with_db_connectivity_issues` | Resilience during DB disruption |

#### Key Assertions

- Both nodes consume messages (load distributed)
- No duplicates across nodes
- All messages consumed exactly once
- Resilient to temporary DB connectivity loss

---

### DuplicateConsumptionDurableQueuesIT

`public abstract class DuplicateConsumptionDurableQueuesIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Tests for duplicate consumption bugs (Bug #19).

#### Configuration

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `NUMBER_OF_MESSAGES` | 40 | Small set for focused testing |
| `PARALLEL_CONSUMERS` | 1 | Single consumer |
| `PROCESSING_DELAY_MS` | 3000 | Artificially slow processing |
| `DEFAULT_MESSAGE_HANDLING_TIMEOUT_MS` | 50 | Short timeout to trigger reset |
| `CONSUMER_START_DELAY_MS` | 200 | Staggered consumer start |

#### Why These Values

Creates conditions that could cause duplicates:
- Processing (3000ms) >> timeout (50ms) → message appears "stuck"
- Message reset triggers while still processing
- Instance 2 starts 200ms later, could fetch reset message

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `verify_no_duplicate_message_consumption` | No message consumed more than once |
| `verify_no_duplicate_message_consumption_with_db_connectivity_issues` | No duplicates during DB disruption |

#### Key Assertions

- Tracks consumption count per `QueueEntryId`
- Fails if any message consumed > 1 time
- Both instances must consume some messages (no starvation)

---

### DurableQueuesLoadIT

`public abstract class DurableQueuesLoadIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Load testing for index optimization.

#### Configuration

| Parameter | Value |
|-----------|-------|
| Messages queued | 20,000 |
| Transaction | Single transaction |

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `queue_a_large_number_of_messages` | Consumption starts within 5 seconds despite large queue |

#### Purpose

Validates database indexes properly utilized - consumption shouldn't be delayed significantly when many messages queued.

---

## DurableLocalCommandBus Test Template

**Package**: `dk.trustworks.essentials.components.foundation.test.reactive.command`

### AbstractDurableLocalCommandBusIT

`public abstract class AbstractDurableLocalCommandBusIT<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>`

Tests for `DurableLocalCommandBus` - synchronous, asynchronous, and fire-and-forget command handling.

#### Abstract Methods

```java
protected abstract DURABLE_QUEUES createDurableQueues(UOW_FACTORY unitOfWorkFactory);
protected abstract UOW_FACTORY createUnitOfWorkFactory();
```

#### Test Coverage

| Test Method | Behavior |
|-------------|----------|
| `test_sync_send` | Synchronous command execution with result |
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

Proxy `JSONSerializer` that simulates deserialization failures.

#### API

```java
ProxyJSONSerializer proxy = new ProxyJSONSerializer(actualSerializer);

// Enable corruption for specific type
proxy.enableJSONCorruptionDuringDeserialization(OrderEvent.OrderAdded.class);

// Subsequent deserialization of OrderAdded will fail
// Corruption auto-disables after one failure

// Manual disable
proxy.disableJSONCorruptionDuringDeserialization();
```

#### Use Case

Used in `DurableQueuesIT.verify_json_deserialization_problem_causes_message_to_be_marked_as_dead_letter_message` to verify deserialization failures → DLQ.

---

## Test Data Classes

**Package**: `dk.trustworks.essentials.components.foundation.test.messaging.queue.test_data`

Realistic test data for queue message testing:

| Class | Type | Purpose |
|-------|------|---------|
| `OrderId` | `CharSequenceType` | Order identifiers |
| `CustomerId` | `CharSequenceType` | Customer identifiers |
| `ProductId` | `CharSequenceType` | Product identifiers |
| `AccountId` | `IntegerType` | Account identifiers (ordered message tests) |
| `OrderEvent` | Base event | Subclasses: `OrderAdded`, `ProductAddedToOrder`, `ProductOrderQuantityAdjusted`, `ProductRemovedFromOrder`, `OrderAccepted` |
| `ProductEvent` | Event | Product-related events |

All classes are immutable, properly implement `equals()`/`hashCode()`, and support JSON serialization.

---

## Implementation Pattern

### FencedLock Implementation Test

```java
public class PostgresqlFencedLockManagerIT
    extends DBFencedLockManagerIT<PostgresqlFencedLockManager> {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    private Jdbi jdbi;

    @BeforeEach
    void setupDb() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(), /* ... */);
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
public class PostgresqlDurableQueuesIT
    extends DurableQueuesIT<PostgresqlDurableQueues, JdbiUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    private Jdbi jdbi;

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(), /* ... */);
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

### ⚠️ Forgetting to Start Managers/Queues

```java
// ❌ Wrong - not started
durableQueues = createDurableQueues(...);
// Tests will fail

// ✅ Correct - started
durableQueues = createDurableQueues(...);
durableQueues.start();
```

### ⚠️ Incorrect Transaction Wrapping

```java
// ❌ Wrong - FullyTransactional mode needs wrapping
durableQueues.queueMessage(queueName, message);

// ✅ Correct
withDurableQueue(() -> durableQueues.queueMessage(queueName, message));
```

### ⚠️ Reusing Instances Between Tests

```java
// ❌ Wrong - instance reused
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

### ⚠️ Not Cleaning Up Resources

```java
// ❌ Wrong - containers/connections leak
@AfterEach
void cleanup() {
    // Nothing
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

## Cross-References

### Implementations Tested

| Implementation | Module | Test Location |
|----------------|--------|---------------|
| `PostgresqlFencedLockManager` | `postgresql-distributed-fenced-lock` | [src/test/java](../components/postgresql-distributed-fenced-lock/src/test/java) |
| `MongoFencedLockManager` | `springdata-mongo-distributed-fenced-lock` | [src/test/java](../components/springdata-mongo-distributed-fenced-lock/src/test/java) |
| `PostgresqlDurableQueues` | `postgresql-queue` | [src/test/java](../components/postgresql-queue/src/test/java) |
| `MongoDurableQueues` | `springdata-mongo-queue` | [src/test/java](../components/springdata-mongo-queue/src/test/java) |

### Related Documentation

- [LLM-foundation.md](./LLM-foundation.md) - `FencedLockManager`, `DurableQueues` interface details
- [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock implementation
- [LLM-postgresql-queue.md](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues implementation
- [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock implementation
- [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues implementation
