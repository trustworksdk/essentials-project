# Foundation Test - LLM Reference

> Token-efficient reference for LLMs. See [README](https://github.com/trustworksdk/essentials-project/blob/main/components/foundation-test/README.md) for detailed documentation.

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
- [Fast integration tests](#fast-integration-tests)
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
// Runs the action directly — each queue operation carries its own transaction
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

### Schema rules (ArchUnit)

**Package**: `dk.trustworks.essentials.components.foundation.test.architecture`

`EssentialsSchemaRules.ddlLivesInSchemaContributors(allowed)` fails for a class holding a `CREATE`/`ALTER`/`DROP`/`TRUNCATE`
statement that is no `EssentialsSchemaContributor` and not nested in one ([LLM-foundation.md](./LLM-foundation.md#database-schema-harness)). It reads the class files'
constant pools (literals, text blocks, concatenation recipes), so it sees DDL that starts a string constant.
`ALLOWED_DDL_HOLDERS` lists the justified exceptions with their reasons. Subclass `AbstractEssentialsSchemaRulesTest`
in a module whose classpath reaches the modules to guard; it is not frozen.

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

> This test disrupts the database (`disruptDatabaseConnection()` stops or pauses the container), so it owns its container per class
> with `@Testcontainers`/`@Container` and must never share a reused one. Tests that do not disrupt the database should use the
> shared, tuned container in [Fast integration tests](#fast-integration-tests) instead.

```java
package dk.trustworks.essentials.components.postgresql.fencedlock;

import dk.trustworks.essentials.components.foundation.test.fencedlock.DBFencedLockManagerIT;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;   // Testcontainers 2.x; not generic
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;

@Testcontainers
public class PostgresqlFencedLockManagerIT
    extends DBFencedLockManagerIT<PostgresqlFencedLockManager> {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4"); // pin an exact tag

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

> `DurableQueuesIT` does not disrupt the database, so it can run against one shared, reused, in-RAM container. The example below keeps a
> per-class container for brevity; the faster form, and the rules that keep it safe, are in
> [Fast integration tests](#fast-integration-tests). The disruptive variants (`DistributedCompetingConsumersDurableQueuesIT`,
> `DuplicateConsumptionDurableQueuesIT`) keep a per-class container.

```java
package dk.trustworks.essentials.components.postgresql.queue;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;   // Testcontainers 2.x; not generic
import org.testcontainers.junit.jupiter.*;

@Testcontainers
public class PostgresqlDurableQueuesIT
    extends DurableQueuesIT<PostgresqlDurableQueues, JdbiUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

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
        // Never hand-build a mapper here: it drifts from the persisted format
        return EssentialsObjectMappers.createJSONSerializer();
    }
}
```

---

## Fast integration tests

For application integration tests (`@SpringBootTest` + Testcontainers), almost all wall-clock time is container start-up and Spring
context boot. The techniques below remove most of it. **Read [What makes this safe with Essentials](#what-makes-this-safe-with-essentials)
before adopting them:** applied unchanged to an event-sourced application, the usual "shared container + truncate between tests" recipe
makes tests hang instead of fail.

### One container per JVM

A `static` container, started once in a static initializer and shared by every test class, instead of `@Testcontainers`/`@Container`
(whose JUnit extension starts and stops a container per test class). Wire it into Spring with `@ServiceConnection`; the container is
already started when the context boots.

Keeping the container alive across `mvn` invocations (Testcontainers reuse) is **off by default** and gated on a project property,
`.withReuse(Boolean.getBoolean("it.containers.reuse"))`, the same way Essentials' own `EssentialsTestContainers` gates it. A developer
opts in with `-Dit.containers.reuse=true` together with the machine flag, set once per developer machine (never on CI, which wants
throwaway containers):

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
mvn verify -Dit.containers.reuse=true      # only with a single Failsafe fork, see below
```

It is gated because a reused container is shared by every test whose container definition hashes the same: Failsafe forks, and
concurrent builds on the same host, attach to the *same* database and drop each other's tables. Only enable it with a single Failsafe
fork (`-Dfailsafe.forkCount=1` where the build exposes that property, as Essentials' does, or `<forkCount>1</forkCount>`). Without the
machine flag Testcontainers ignores the opt-in (it logs `Reuse was requested but the environment does not support the reuse of
containers`) and the container simply lives for one JVM.

Reuse keys on a hash of the container definition (image, command, env, mounts). **Pin an exact image tag** (Essentials' own tests use
`postgres:18.4`), never `latest` or a floating major, and keep the definition stable: any change starts a new container.

### Postgres in RAM

Put the data directory on a tmpfs and turn off durability. This is safe only because the data is throwaway.

- **Postgres 18 images:** mount the tmpfs at `/var/lib/postgresql`. The image's data directory (`PGDATA`) is
  `/var/lib/postgresql/18/docker`, inside that mount. A tmpfs at the pre-18 path `/var/lib/postgresql/data` without also pointing `PGDATA`
  into it (e.g. `PGDATA=/var/lib/postgresql/data/pgdata`) stops the container at start-up: the 18+ entrypoint refuses an "unused mount" at
  the old location.
- `withCommand(...)` **replaces** Testcontainers' default command (`postgres -c fsync=off`), so list `fsync=off` again alongside
  `synchronous_commit=off` and `full_page_writes=off`.
- Testcontainers 2.x: the class is `org.testcontainers.postgresql.PostgreSQLContainer` and it is **not generic**. `PostgreSQLContainer<?>`
  (Java) or `PostgreSQLContainer<*>` (Kotlin) does not compile against it; `org.testcontainers.containers.PostgreSQLContainer` is the
  deprecated generic one.

```java
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
        .withReuse(Boolean.getBoolean("it.containers.reuse"))   // opt-in, off by default: see "One container per JVM"
        .withTmpFs(Map.of("/var/lib/postgresql", "rw"))   // PG 18: PGDATA is /var/lib/postgresql/18/docker, inside the mount
        .withCommand("postgres",
            "-c", "fsync=off",                            // replaces Testcontainers' default command, so repeat it
            "-c", "synchronous_commit=off",
            "-c", "full_page_writes=off");

    static { postgres.start(); }

    @Autowired
    protected Jdbi jdbi;

    /** Read-model tables only - never an *_events table. See "What makes this safe with Essentials". */
    protected List<String> readModelTablesToTruncate() {
        return List.of();
    }

    @BeforeEach
    void truncateReadModels() {
        var tables = readModelTablesToTruncate();
        if (!tables.isEmpty()) {
            jdbi.useHandle(h -> h.execute("TRUNCATE TABLE " + String.join(", ", tables)));
        }
    }
}
```

Kotlin: the same container in a `companion object`:

```kotlin
companion object {
    @JvmStatic
    @ServiceConnection
    val postgres = PostgreSQLContainer("postgres:18.4")      // not generic: no <*>
        .withReuse(System.getProperty("it.containers.reuse").toBoolean())   // opt-in, off by default
        .withTmpFs(mapOf("/var/lib/postgresql" to "rw"))
        .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
        .also { it.start() }
}
```

### Keep the Spring context cache warm

- **No `@DirtiesContext`.** It evicts the cached context and forces a fresh boot per test class. Isolate tests through the database
  instead (next section), and reset in-memory state in `@BeforeEach` rather than dirtying the context.
- **`reuseForks=true`**, so one test JVM, and therefore one context cache, serves every test class in the fork.
- **A small `spring.test.context.cache.maxSize`** (e.g. `4`) in the Failsafe `argLine` as a fragmentation tripwire: a test class that
  adds a distinct `@MockitoBean`/`@TestPropertySource`/`@Import` creates a second context, and the small cap makes the eviction visible
  instead of letting the default of 32 hide it.

### Cheaper JVM start in test forks

`-XX:TieredStopAtLevel=1` (skip C2) and `-Dspring.jmx.enabled=false` in the test `argLine`. **Never on benchmark or load suites**, which
need the optimising compiler; give those their own profile.

```xml
<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <forkCount>1</forkCount>   <!-- with container reuse: every fork would attach to the same database -->
        <reuseForks>true</reuseForks>
        <argLine>-XX:TieredStopAtLevel=1 -Dspring.jmx.enabled=false -Dspring.test.context.cache.maxSize=4</argLine>
    </configuration>
</plugin>
```

Surefire can take the same `argLine` without `cache.maxSize`.

### Inner loop

Run one class or one method while iterating, the full suite once at the end:

```bash
mvn test-compile failsafe:integration-test failsafe:verify -Dit.test='OrderPlacementIT#places_an_order'
```

### Verification

- With reuse opted in (`-Dit.containers.reuse=true` plus the machine flag), a second run logs `Reusing container with ID: … and hash: …`.
- With `-Dlogging.level.org.springframework.test.context.cache=DEBUG`, the context cache holds one entry across the suite.

### What makes this safe with Essentials

- **Never truncate an `*_events` table with `RESTART IDENTITY`.** Each aggregate type's events live in `<aggregateType>_events` (the
  standard naming; it is configurable), and `global_order` is an identity column. `RESTART IDENTITY` rewinds it, while every running
  subscription keeps its higher in-memory resume point: projections silently stop and the next test **hangs** on its await instead of
  failing. Truncate read-model tables only. Prefer fresh (random) aggregate ids per test over clearing event tables at all.
- **Truncating a read model only works for rows the test itself causes.** Its subscription has already moved past the events that
  populated it, so rows seeded before the test never come back, not even after a restart that re-sends the seeding commands (those are
  no-ops against the existing streams). To rebuild a read model, clear it in `onSubscriptionsReset` and call `resetAllSubscriptions()`,
  or seed the read model directly in the test.

  ```java
  public class OrderSummaryProjection extends ViewEventProcessor {   // same hooks on EventProcessor
      // ...
      @Override
      protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
          jdbi.useHandle(h -> h.execute("TRUNCATE TABLE order_summaries"));
      }
  }

  // in the test
  @BeforeEach
  void rebuildOrderSummaries() {
      orderSummaryProjection.resetAllSubscriptions();   // resume points -> first global order; queue/inbox purged
      // await the replayed rows before asserting
  }
  ```

  `resetAllSubscriptions()` only acts on the instance that **holds the processor's lock**. Anywhere else it does nothing but log at INFO;
  on a `ViewEventProcessor` that has not acquired its lock yet it throws `NullPointerException`. Wait for the processor to be active
  before resetting. `resetSubscriptions(Map<AggregateType, GlobalEventOrder>, boolean)` resets selected aggregate types from a given
  `GlobalEventOrder`.
- **Reuse plus an identical container definition means one shared store per host.** Every build on the machine (another terminal, another
  checkout, an agent session) whose definition hashes the same attaches to the *same* database. Their application instances then form an
  accidental cluster: processor locks move between builds, event tables interleave every build's history, and one build's truncation
  deletes rows another build has already projected. A reset is also skipped when another build's instance holds the lock. Run one build
  per host at a time against a reused container (or give each checkout a distinct definition, e.g. its own database name), use
  `forkCount=1`, and keep fixture ids random per run. The default subscription table, `durable_subscriptions`, is shared the same way.
- **A test that disrupts the database owns its container per class.** See
  [Sharing a Container With a Disruption Test](#-sharing-a-container-with-a-disruption-test); `DBFencedLockManagerIT`,
  `DBFencedLockManager_MultiNode_ReleaseLockIT`, `DistributedCompetingConsumersDurableQueuesIT` and `DuplicateConsumptionDurableQueuesIT`
  are the base ITs that declare `disruptDatabaseConnection()`.

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
// ❌ Wrong - bypasses the subclass's hook, e.g. one that wraps queue calls in a UnitOfWork
durableQueues.queueMessage(queueName, message);

// ✅ Correct - withDurableQueue/usingDurableQueue are pass-throughs a subclass can override
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

### ⚠️ Sharing a Container With a Disruption Test

The base ITs that declare `disruptDatabaseConnection()` / `restoreDatabaseConnection()` take the database away
mid-test (the module's own subclasses pause the container). On a container shared with other test classes — a
JVM-wide singleton, or one reused with `withReuse(true)` — that disruption hits every other test using it.
Give each disruption IT its own per-class container (`@Testcontainers` + `@Container`, as the module's own
`…_MultiNode_ReleaseLockIT` / `…DistributedCompetingConsumersDurableQueuesIT` do); share a singleton only
between ITs that never disrupt it.

---

## See Also

- [README.md](https://github.com/trustworksdk/essentials-project/blob/main/components/foundation-test/README.md) - Full documentation
- [LLM-foundation.md](./LLM-foundation.md) - `FencedLockManager`, `DurableQueues` interfaces
- [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL FencedLock
- [LLM-postgresql-queue.md](./LLM-postgresql-queue.md) - PostgreSQL DurableQueues
- [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB FencedLock
- [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) - MongoDB DurableQueues
