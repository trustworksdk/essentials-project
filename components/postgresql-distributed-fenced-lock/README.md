# Essentials Components - PostgreSQL Distributed Fenced Lock

> **NOTE:** **The library is WORK-IN-PROGRESS**

PostgreSQL implementation of the `FencedLockManager` for distributed lock coordination.

**LLM-context**: [LLM-postgresql-distributed-fenced-lock.md](../../LLM/LLM-postgresql-distributed-fenced-lock.md)

## Overview

This module provides `PostgresqlFencedLockManager`, a PostgreSQL-backed implementation of the [`FencedLockManager`](../foundation/README.md#fencedlock-distributed-locking) interface from the foundation module.

For conceptual documentation on fenced locks (why, when, how), see:
- [Foundation - FencedLock](../foundation/README.md#fencedlock-distributed-locking)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-distributed-fenced-lock</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

The `fencedLocksTableName` parameter is used directly in SQL statements via string concatenation, exposing the component to **SQL injection attacks**.

> ⚠️ **WARNING:** It is your responsibility to sanitize the `fencedLocksTableName` value.

**Mitigations:**
- `PostgresqlFencedLockStorage` calls `PostgresqlUtil#checkIsValidTableOrColumnName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

**Developer Responsibility:**
- Only use `fencedLocksTableName` values from controlled, trusted sources
- Never derive table/column/index names from external/untrusted input
- Validate all configuration values during application startup

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## PostgreSQL-Specific Configuration

### Standalone Usage

```java
var lockManager = PostgresqlFencedLockManager.builder()
    .setJdbi(Jdbi.create(jdbcUrl, username, password))
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setLockTimeOut(Duration.ofSeconds(3))               // Lock expiration
    .setLockConfirmationInterval(Duration.ofSeconds(1))  // Heartbeat interval
    .setFencedLocksTableName("my_fenced_locks")          // Custom table name
    .buildAndStart();
```

### Spring Integration

```java
@Bean
public FencedLockManager fencedLockManager(
        Jdbi jdbi,
        HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
    return PostgresqlFencedLockManager.builder()
        .setJdbi(jdbi)
        .setUnitOfWorkFactory(unitOfWorkFactory)
        .setLockTimeOut(Duration.ofSeconds(3))
        .setLockConfirmationInterval(Duration.ofSeconds(1))
        .buildAndStart();
}
```

### Builder Options

| Option | Default | Description |
|--------|---------|-------------|
| `jdbi` | Required | JDBI instance for database access |
| `unitOfWorkFactory` | Required | Transaction factory |
| `lockManagerInstanceId` | Hostname | Unique identifier for this manager instance |
| `lockTimeOut` | 10 seconds | Duration before lock expires without confirmation |
| `lockConfirmationInterval` | 3 seconds | Heartbeat interval for confirming lock ownership |
| `fencedLocksTableName` | `fenced_locks` | Database table name for storing locks |
| `eventBus` | None | Optional event bus for lock events |

### Table Schema

The table is created automatically with the following structure:

```sql
CREATE TABLE IF NOT EXISTS fenced_locks (
    lock_name              TEXT PRIMARY KEY,
    last_issued_fence_token BIGINT,
    locked_by_instance_id   TEXT,
    lock_acquired_ts        TIMESTAMPTZ,
    lock_last_confirmed_ts  TIMESTAMPTZ
);
```

## Usage

See [Foundation - FencedLock](../foundation/README.md#fencedlock-distributed-locking) for complete usage patterns including:
- Synchronous lock acquisition
- Asynchronous lock acquisition with callbacks
- Try-with-resources pattern
- Lock status monitoring

## Comparison with MongoDB Implementation

| Aspect | PostgreSQL | MongoDB |
|--------|-----------|---------|
| **Module** | `postgresql-distributed-fenced-lock` | [`springdata-mongo-distributed-fenced-lock`](../springdata-mongo-distributed-fenced-lock/README.md) |
| **Storage** | SQL table | MongoDB collection |
| **Transactions** | JDBI/JDBC | Spring Data MongoDB |
| **Requirements** | PostgreSQL 9.5+ | MongoDB 4.0+ (replica set) |
