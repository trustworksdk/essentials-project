# Essentials Components - SpringData MongoDB Distributed Fenced Lock

> **NOTE:** **The library is WORK-IN-PROGRESS**

MongoDB implementation of the `FencedLockManager` using Spring Data MongoDB for distributed lock coordination.

**LLM Context:** [LLM-springdata-mongo-distributed-fenced-lock.md](../../LLM/LLM-springdata-mongo-distributed-fenced-lock.md)

## Table of Contents
- [Overview](#overview)
- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [MongoDB-Specific Configuration](#mongodb-specific-configuration)
- [Usage](#usage)
- [Comparison with PostgreSQL Implementation](#comparison-with-postgresql-implementation)

## Overview

This module provides `MongoFencedLockManager`, a MongoDB-backed implementation of the [`FencedLockManager`](../foundation/README.md#fencedlock-distributed-locking) interface from the foundation module.

For conceptual documentation on fenced locks (why, when, how), see:
- [Foundation - FencedLock](../foundation/README.md#fencedlock-distributed-locking)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>springdata-mongo-distributed-fenced-lock</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: NoSQL Injection Risk

The components allow customization of collection names that are used with **String concatenation** → NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against NoSQL injection.

The `fencedLocksCollectionName` parameter is used directly as a MongoDB collection name, exposing the component to potential malicious input.

> ⚠️ **WARNING:** It is your responsibility to sanitize the `fencedLocksCollectionName` value.

**Mitigations:**
- `MongoFencedLockStorage` calls `MongoUtil.checkIsValidCollectionName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete security

**Developer Responsibility:**
- Only use `fencedLocksCollectionName` values from controlled, trusted sources
- Never derive collection names from external/untrusted input
- Validate all configuration values during application startup

### What Validation Does NOT Protect Against

- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## MongoDB-Specific Configuration

### Requirements

- **MongoDB 4.0+** with replica set (required for transactions)
- Spring Data MongoDB with transaction support

### Spring Configuration

```java
@Configuration
@EnableMongoRepositories
@EnableTransactionManagement
public class MongoFencedLockConfiguration {

    @Bean
    public FencedLockManager fencedLockManager(
            MongoTemplate mongoTemplate,
            MongoTransactionManager transactionManager,
            MongoDatabaseFactory databaseFactory) {
        return MongoFencedLockManager.builder()
            .setMongoTemplate(mongoTemplate)
            .setUnitOfWorkFactory(null) // null for SingleOperationTransaction
            .setLockTimeOut(Duration.ofSeconds(3))
            .setLockConfirmationInterval(Duration.ofSeconds(1))
            .buildAndStart();
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(LockName.class)));
    }

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }
}
```

### Builder Options

| Option | Default | Description |
|--------|---------|-------------|
| `mongoTemplate` | Required | MongoTemplate instance |
| `unitOfWorkFactory` | Required | Transaction factory |
| `lockManagerInstanceId` | Hostname | Unique identifier for this manager instance |
| `lockTimeOut` | 10 seconds | Duration before lock expires without confirmation |
| `lockConfirmationInterval` | 3 seconds | Heartbeat interval for confirming lock ownership |
| `fencedLocksCollectionName` | `fenced_locks` | MongoDB collection name for storing locks |
| `eventBus` | None | Optional event bus for lock events |

### Collection Schema

Locks are stored in a MongoDB collection with automatic indexes:

```javascript
{
  _id: "lock_name",                        // LockName (String)
  lastIssuedFencedToken: Long,             // Monotonic counter
  lockedByLockManagerInstanceId: String,   // Owner instance
  lockAcquiredTimestamp: ISODate,          // When acquired
  lockLastConfirmedTimestamp: ISODate      // Last heartbeat
}
```

## Usage

See [Foundation - FencedLock](../foundation/README.md#fencedlock-distributed-locking) for complete usage patterns including:
- Synchronous lock acquisition
- Asynchronous lock acquisition with callbacks
- Try-with-resources pattern
- Lock status monitoring

## Comparison with PostgreSQL Implementation

| Aspect | MongoDB | PostgreSQL |
|--------|---------|-----------|
| **Module** | `springdata-mongo-distributed-fenced-lock` | [`postgresql-distributed-fenced-lock`](../postgresql-distributed-fenced-lock/README.md) |
| **Storage** | MongoDB collection | SQL table |
| **Transactions** | Spring Data MongoDB | JDBI/JDBC |
| **Requirements** | MongoDB 4.0+ (replica set) | PostgreSQL 9.5+ |
