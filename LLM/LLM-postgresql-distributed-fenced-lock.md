# PostgreSQL Distributed Fenced Lock - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/postgresql-distributed-fenced-lock/README.md).

> For core FencedLock concepts (why, when, how), see [LLM-foundation.md](./LLM-foundation.md#fencedlock-distributed-locking).

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.distributed.fencedlock.postgresql`
- **Purpose**: PostgreSQL-backed distributed fencing locks for intra-service coordination
- **Extends**: `DBFencedLockManager<HandleAwareUnitOfWork, DBFencedLock>` from foundation
- **Key Deps**: JDBI, PostgreSQL, foundation module
- **Scope**: Intra-service only (instances sharing same PostgreSQL database)
- **Requirements**: PostgreSQL 9.5+
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-distributed-fenced-lock</artifactId>
</dependency>
```

## TOC
- [Core Classes](#core-classes)
- [Database Schema](#database-schema)
- [Configuration](#configuration)
- [Usage Patterns](#usage-patterns)
- [Spring Integration](#spring-integration)
- [Common Use Cases](#common-use-cases)
- [Integration with Other Components](#integration-with-other-components)
- [Gotchas](#gotchas)
- ⚠️ [Security](#security)
- [Dependencies & Tests](#dependencies--tests)

## Core Classes

**Base package**: `dk.trustworks.essentials.components.distributed.fencedlock.postgresql`

**Dependencies from other modules**:
- `FencedLockManager`, `FencedLock`, `LockName`, `LockCallback`, `DBFencedLock` from [foundation](./LLM-foundation.md)
- `HandleAwareUnitOfWorkFactory` from [foundation](./LLM-foundation.md)

| Class | Role |
|-------|------|
| `PostgresqlFencedLockManager` | Main lock manager implementation |
| `PostgresqlFencedLockStorage` | PostgreSQL persistence layer |
| `PostgresqlFencedLockManagerBuilder` | Fluent builder |
| `DBFencedLock` | Lock implementation (from `dk.trustworks.essentials.components.foundation.fencedlock`) |

## Database Schema

### Table Structure

Automatically created on first use:

```sql
CREATE TABLE IF NOT EXISTS fenced_locks (
    lock_name                          TEXT PRIMARY KEY,
    last_issued_fence_token            BIGINT,
    locked_by_lockmanager_instance_id  TEXT,
    lock_acquired_ts                   TIMESTAMPTZ,
    lock_last_confirmed_ts             TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS fenced_locks_current_token_index
    ON fenced_locks (lock_name, last_issued_fence_token);
```

### Column Semantics

| Column | Purpose |
|--------|---------|
| `lock_name` | Unique lock identifier |
| `last_issued_fence_token` | Monotonically increasing token (starts at 1) |
| `locked_by_lockmanager_instance_id` | Instance holding lock (NULL if unlocked) |
| `lock_acquired_ts` | When lock was acquired |
| `lock_last_confirmed_ts` | Last heartbeat timestamp |

## Configuration

### Builder Pattern

```java
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;

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

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `jdbi` | `Jdbi` | Required | JDBI instance for database access |
| `unitOfWorkFactory` | `HandleAwareUnitOfWorkFactory` | Required | Transaction management |
| `lockManagerInstanceId` | `String` | Hostname | Unique ID for this manager instance |
| `lockTimeOut` | `Duration` | 10 seconds | Lock expiration without confirmation |
| `lockConfirmationInterval` | `Duration` | 3 seconds | Heartbeat interval |
| `fencedLocksTableName` | `String` | `"fenced_locks"` | Database table name ⚠️ SQL injection risk |
| `eventBus` | `Optional<EventBus>` | Empty | Event bus for lock events |
| `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` | `boolean` | `false` | Release locks on IO errors during confirmation |

### Configuration Best Practices

```java
// Recommended timeout ratio: confirmation = timeout / 3
Duration lockTimeout = Duration.ofSeconds(30);
Duration confirmationInterval = lockTimeout.dividedBy(3);  // 10 seconds

// Instance ID best practices
String instanceId = Network.hostName();                    // Use hostname (default)
String instanceId = "service-" + UUID.randomUUID();        // Or unique ID
```

## Usage Patterns

All `FencedLockManager` API methods inherited from foundation. See [LLM-foundation.md#fencedlock-distributed-locking](./LLM-foundation.md#fencedlock-distributed-locking) for complete API reference.

### Pattern: Try-With-Resources (Recommended)

```java
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;

// Short-running locks with automatic release
try (var handle = lockManager.acquireLock(
        LockName.of("order-processor")) {
    processOrders(handle.getCurrentToken());
} 
```

### Pattern: Try-Acquire (Non-Blocking)

```java
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;

// Returns immediately
Optional<FencedLock> lock = lockManager.tryAcquireLock(LockName.of("order-processor"));
if (lock.isPresent()) {
    try {
        processOrders(lock.get().getCurrentToken());
    } finally {
        lockManager.releaseLock(lock.get());
    }
}
```

### Pattern: Async Lock with Callback

```java
import dk.trustworks.essentials.components.foundation.fencedlock.LockCallback;

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

### Pattern: Lock Status Queries

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
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;

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

### Custom Table Name

```java
@Bean
public PostgresqlFencedLockManager fencedLockManager(
        Jdbi jdbi,
        HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
        @Value("${app.name}") String appName) {
    return PostgresqlFencedLockManager.builder()
        .setJdbi(jdbi)
        .setUnitOfWorkFactory(unitOfWorkFactory)
        .setLockTimeOut(Duration.ofSeconds(30))
        .setLockConfirmationInterval(Duration.ofSeconds(10))
        .setFencedLocksTableName(appName + "_fenced_locks")  // ⚠️ Custom table (SQL injection risk)
        .buildAndStart();
}
```

## Integration with Other Components

### Used By

- [postgresql-event-store](./LLM-postgresql-event-store.md) - Exclusive subscriptions
- [spring-boot-starter-postgresql](./LLM-spring-boot-starter-modules.md) - Auto-configuration
- [foundation Inbox/Outbox](./LLM-foundation.md#inboxoutbox-patterns) - SingleGlobalConsumer mode

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
FencedLock lock = lockManager.acquireLock(lockName);
doWork(lock.getCurrentToken());
lockManager.releaseLock(lock);  // Never called if doWork() throws

// ✅ RIGHT - Try-with-resources
try (var handle = lockManager.acquireLock(lockName)) {
    doWork(handle.getCurrentToken());
}
```

### ⚠️ Table Name Security

```java
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

// ❌ WRONG - User input in table name
String tableName = request.getParameter("table") + "_locks";

// ✅ RIGHT - Controlled table name
String tableName = config.getApplicationName() + "_locks";
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
```

## Security

### ⚠️ Critical: SQL Injection Risk

**Problem**: `fencedLocksTableName` is concatenated directly into SQL statements.

**Mitigation by Essentials**:
- `PostgresqlFencedLockStorage` calls `PostgresqlUtil.checkIsValidTableOrColumnName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

**Your Responsibility**:
1. Only use table names from controlled sources (config files, constants)
2. Never derive table names from external/untrusted input
3. Validate at application startup with `PostgresqlUtil.checkIsValidTableOrColumnName(tableName)`
4. Use default table name when possible

See [README Security](../components/postgresql-distributed-fenced-lock/README.md#security) for full details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

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

- [LLM-foundation.md#fencedlock-distributed-locking](./LLM-foundation.md#fencedlock-distributed-locking) - Core FencedLock concepts
- [LLM-springdata-mongo-distributed-fenced-lock.md](./LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB implementation
- [README](../components/postgresql-distributed-fenced-lock/README.md) - Full documentation
