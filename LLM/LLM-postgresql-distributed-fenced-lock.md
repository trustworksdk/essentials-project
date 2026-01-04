# PostgreSQL Distributed Fenced Lock - LLM Reference

> See [README](../components/postgresql-distributed-fenced-lock/README.md) for detailed developer documentation.

> For core FencedLock concepts (why, when, how), see [LLM-foundation.md](./LLM-foundation.md#fencedlock-distributed-locking).

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.distributed.fencedlock.postgresql`
- **Purpose**: PostgreSQL-backed distributed fencing locks for intra-service coordination
- **Extends**: `DBFencedLockManager<HandleAwareUnitOfWork, DBFencedLock>` from foundation
- **Key Deps**: JDBI, PostgreSQL, foundation module
- **Scope**: Intra-service only (instances sharing same PostgreSQL database)
- **Requirements**: PostgreSQL 9.5+
- **Status**: WORK-IN-PROGRESS

## TOC
- [Core Classes](#core-classes)
- [Configuration](#configuration)
- [Database Schema](#database-schema)
- [Usage Patterns](#usage-patterns)
- [Spring Integration](#spring-integration)
- [Logging](#logging)
- ⚠️ [Security](#security)
- [Common Use Cases](#common-use-cases)
- [Integration with Other Components](#integration-with-other-components)
- [Gotchas](#gotchas)
- [Comparison with MongoDB](#comparison-with-mongodb)
- [Dependencies & Tests](#dependencies--tests)

## Core Classes

| Class | Package                                                     | Role |
|-------|-------------------------------------------------------------|------|
| `PostgresqlFencedLockManager` | `(root)`                                     | Main lock manager implementation |
| `PostgresqlFencedLockStorage` | `(root)`                                     | PostgreSQL persistence layer |
| `PostgresqlFencedLockManagerBuilder` | `(root)`                              | Fluent builder |
| `DBFencedLock` | `dk.trustworks.essentials.components.foundation.fencedlock` | Lock implementation (from foundation) |

## Configuration

### Builder Pattern

```java
PostgresqlFencedLockManager lockManager = PostgresqlFencedLockManager.builder()
    .setJdbi(jdbi)                                        // Required
    .setUnitOfWorkFactory(unitOfWorkFactory)              // Required
    .setLockManagerInstanceId("node-01")                  // Optional, defaults to hostname
    .setLockTimeOut(Duration.ofSeconds(10))               // Optional, default 10s
    .setLockConfirmationInterval(Duration.ofSeconds(3))   // Optional, default 3s
    .setFencedLocksTableName("my_locks")                  // Optional, default "fenced_locks"
    .setEventBus(Optional.of(eventBus))                   // Optional
    .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(false) // Optional, default false
    .buildAndStart();  // Or .build() without starting
```

### Configuration Options

| Option | Type | Default | Description                                    |
|--------|------|---------|------------------------------------------------|
| `jdbi` | `Jdbi` | Required | JDBI instance for database access              |
| `unitOfWorkFactory` | `HandleAwareUnitOfWorkFactory` | Required | Transaction management                         |
| `lockManagerInstanceId` | `String` | Hostname | Unique ID for this manager instance            |
| `lockTimeOut` | `Duration` | 10 seconds | Lock expiration without confirmation           |
| `lockConfirmationInterval` | `Duration` | 3 seconds | Heartbeat interval                             |
| `fencedLocksTableName` | `String` | `"fenced_locks"` | Database table name ⚠️ SQL injection risk  (see security) |
| `eventBus` | `Optional<EventBus>` | Empty | Event bus for lock events                      |
| `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` | `boolean` | `false` | Release locks on IO errors during confirmation |

### Configuration Guidelines

```java
// Recommended timeout ratio: confirmation = timeout / 3
Duration lockTimeout = Duration.ofSeconds(30);
Duration confirmationInterval = lockTimeout.dividedBy(3);  // 10 seconds

// Instance ID best practices
String instanceId = Network.hostName();                    // Use hostname (default)
String instanceId = "service-" + UUID.randomUUID();        // Or unique ID
```

## Database Schema

### Table Structure

Automatically created on first use:

```sql
CREATE TABLE IF NOT EXISTS fenced_locks (
    lock_name                      TEXT PRIMARY KEY,
    last_issued_fence_token        BIGINT,
    locked_by_lockmanager_instance_id TEXT,
    lock_acquired_ts               TIMESTAMPTZ,
    lock_last_confirmed_ts         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS fenced_locks_current_token_index
    ON fenced_locks (lock_name, last_issued_fence_token);
```

### Column Descriptions

| Column | Purpose |
|--------|---------|
| `lock_name` | Unique lock identifier |
| `last_issued_fence_token` | Monotonically increasing token (starts at 1) |
| `locked_by_lockmanager_instance_id` | Instance holding lock (NULL if unlocked) |
| `lock_acquired_ts` | When lock was acquired |
| `lock_last_confirmed_ts` | Last heartbeat timestamp |

## Usage Patterns

All `FencedLockManager` API methods inherited from foundation. See [LLM-foundation.md#fencedlock-api](./LLM-foundation.md#fencedlock-api) for complete API reference.

### Basic Lock Acquisition

```java
// Try-with-resources (recommended for short-running locks with automatic release)
try (var handle = lockManager.acquireLockAndGetLockHandle(
        LockName.of("order-processor"),
        Duration.ofSeconds(30))) {
    processOrders(handle.getCurrentToken());
} catch (LockException.LockAcquisitionException e) {
    log.warn("Failed to acquire lock", e);
}

// Try-acquire - returns immediately
Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));
if (lock.isPresent()) {
    try {
        processOrders(lock.get().getCurrentToken());
    } finally {
        lockManager.releaseLock(lock.get());
    }
}
```

### Asynchronous Lock Acquisition

```java
lockManager.acquireLockAsync(
    LockName.of("scheduled-task"),
    new LockCallback() {
        @Override
        public void lockAcquired(FencedLock lock) {
            startBackgroundProcessing(lock.getCurrentToken());
        }

        @Override
        public void lockReleased(FencedLock lock) {
            stopBackgroundProcessing();
        }
    }
);
```

### Lock Status Queries

```java
// Check if lock exists in database
Optional<FencedLock> lockInfo = lockManager.lookupLock(LockName.of("my-lock"));

// Check if THIS instance holds the lock
boolean heldByMe = lockManager.isLockedByThisLockManagerInstance(LockName.of("my-lock"));

// Check if another instance holds the lock
boolean heldByOther = lockManager.isLockAcquiredByAnotherLockManagerInstance(LockName.of("my-lock"));
```

## Spring Integration

### Bean Configuration

```java
@Configuration
public class LockConfiguration {

    @Bean
    public PostgresqlFencedLockManager fencedLockManager(
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return PostgresqlFencedLockManager.builder()
            .setJdbi(jdbi)
            .setUnitOfWorkFactory(unitOfWorkFactory)
            .setLockTimeOut(Duration.ofSeconds(30))
            .setLockConfirmationInterval(Duration.ofSeconds(10))
            .buildAndStart();
    }

    @PreDestroy
    public void cleanup(PostgresqlFencedLockManager lockManager) {
        lockManager.stop();
    }
}
```

### With Custom Table Name

```java
@Bean
public PostgresqlFencedLockManager fencedLockManager(
        Jdbi jdbi,
        UnitOfWorkFactory unitOfWorkFactory,
        @Value("${app.name}") String appName) {
    return PostgresqlFencedLockManager.builder()
                                      .setJdbi(jdbi)
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setLockTimeOut(Duration.ofSeconds(30))
                                      .setLockConfirmationInterval(Duration.ofSeconds(10))
                                      .setFencedLocksTableName(appName + "_fenced_locks")  // Custom table
        .buildAndStart();
}
```

## Logging

Configure loggers for lock operations:

| Logger Name | Purpose |
|-------------|---------|
| `dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage` | PostgreSQL lock storage operations |
| `dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager` | Lock manager operations |

```yaml
# Logback/Spring Boot
logging.level:
  dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage: DEBUG
  dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager: DEBUG
```

```xml
<!-- Logback.xml -->
<logger name="dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager" level="DEBUG"/>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk.  
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

### SQL Injection Risk

⚠️ **CRITICAL**: `fencedLocksTableName` is concatenated directly into SQL statements.

```java
// ❌ DANGEROUS - Never use user input
String tableName = userInput + "_locks";  // SQL INJECTION RISK

// ✅ SAFE - Use controlled values only
String tableName = "order_service_locks";                // Fixed string
String tableName = applicationName + "_fenced_locks";    // From trusted config
```

1. Only use table names from controlled sources (config files, constants)
2. Never derive table names from external/untrusted input
3. Validate at application startup with `PostgresqlUtil.checkIsValidTableOrColumnName(tableName)`
4. Use default table name when possible

## Common Use Cases

### Singleton Worker Pattern

```java
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

## Integration with Other Components

### Used By

- [postgresql-event-store](./LLM-postgresql-event-store.md) - Exclusive subscriptions
- [postgresql-queue](./LLM-postgresql-queue.md) - Queue consumer coordination
- [spring-boot-starter-postgresql](./LLM-spring-boot-starter-modules.md) - Auto-configuration
- [foundation Inbox/Outbox](./LLM-foundation.md#inboxoutbox-patterns) - SingleGlobalConsumer mode

### EventStore Subscription Example

Uses [FencedLockManager](../foundation/README.md#fencedlock-distributed-locking) to ensure only **one subscriber per cluster**:

```java
subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
        SubscriberId.of("InventoryManager"),
       AggregateType.of("Orders"),
       GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, // onFirstSubscriptionSubscribeFromAndIncluding: Start from beginning on first subscription
       Optional.empty(), // No tenant filter
        new FencedLockAwareSubscriber() {
        @Override
        public void onLockAcquired(FencedLock lock, SubscriptionResumePoint resumePoint) {
            log.info("Acquired exclusive lock, resuming from {}", resumePoint);
        }
    
        @Override
        public void onLockReleased(FencedLock lock) {
            log.info("Released exclusive lock");
        }
    },
    new InventoryEventHandler() // PersistedEventHandler
);
```

### Inbox with SingleGlobalConsumer Example

```java
// Inbox uses FencedLockManager for cluster-wide ordering
Inbox inbox = inboxes.getOrCreateInbox(
    InboxConfig.builder()
        .setInboxName(InboxName.of("OrderEvents"))
        .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
        .build(),
    messageHandler
);
```

## Gotchas

### ⚠️ Instance ID Must Be Unique

```java
// ❌ WRONG - All instances use same ID
.setLockManagerInstanceId("service-instance")

// ✅ RIGHT - Each instance has unique ID
.setLockManagerInstanceId(Network.hostName())
.setLockManagerInstanceId("node-" + UUID.randomUUID())
```

### ⚠️ Confirmation Interval vs Timeout

```java
// ❌ WRONG - Confirmation interval too close to timeout
.setLockTimeOut(Duration.ofSeconds(10))
.setLockConfirmationInterval(Duration.ofSeconds(9))  // Only 1s buffer

// ✅ RIGHT - Confirmation interval is 1/3 of timeout
.setLockTimeOut(Duration.ofSeconds(30))
.setLockConfirmationInterval(Duration.ofSeconds(10))  // 3x buffer
```

### ⚠️ Always Release Locks

```java
// ❌ WRONG - Lock not released on exception
FencedLock lock = lockManager.acquireLock(lockName, timeout);
doWork(lock.getCurrentToken());
lockManager.releaseLock(lock);  // Never called if doWork() throws

// ✅ RIGHT - Try-with-resources
try (var handle = lockManager.acquireLockAndGetLockHandle(lockName, timeout)) {
    doWork(handle.getCurrentToken());
}
```

### ⚠️ Table Name Security

```java
// ❌ WRONG - User input in table name
String tableName = request.getParameter("table") + "_locks";

// ✅ RIGHT - Controlled table name
String tableName = config.getApplicationName() + "_locks";
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
```

## Comparison with MongoDB

| Aspect | PostgreSQL | MongoDB |
|--------|-----------|---------|
| **Module** | `postgresql-distributed-fenced-lock` | `springdata-mongo-distributed-fenced-lock` |
| **Storage** | PostgreSQL table | MongoDB collection |
| **Technology** | JDBI + JDBC | Spring Data MongoDB |
| **UnitOfWork** | `HandleAwareUnitOfWork` | `ClientSessionAwareUnitOfWork` |
| **Transactions** | SQL transactions | MongoDB transactions |
| **Requirements** | PostgreSQL 9.5+ | MongoDB 4.0+ (replica set) |
| **Index Type** | B-tree indexes | MongoDB compound indexes |
| **Schema Init** | Automatic table creation | Automatic collection/index creation |
| **Query Language** | SQL | MongoDB query DSL |
| **Injection Risk** | SQL injection | NoSQL injection |
| **Default Storage** | `fenced_locks` table | `fenced_locks` collection |

See: [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md)

## Dependencies & Tests

### Maven

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-distributed-fenced-lock</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

### Runtime Requirements

- PostgreSQL 9.5+
- JDBI 3.x
- PostgreSQL JDBC Driver
- foundation module
- shared module
- reactive module (optional, for EventBus)

### Test References

- `PostgresqlFencedLockManagerIT` - Integration tests
- `PostgresqlFencedLockManager_MultiNode_ReleaseLockIT` - Multi-node scenarios

## See Also

- [LLM-foundation.md#fencedlock-api](./LLM-foundation.md#fencedlock-distributed-locking) - Core FencedLock concepts
- [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB implementation
- [README](../components/postgresql-distributed-fenced-lock/README.md) - Full documentation
