# SpringData MongoDB Distributed Fenced Lock - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/springdata-mongo-distributed-fenced-lock/README.md).

> For core FencedLock concepts (why, when, how), see [LLM-foundation.md](./LLM-foundation.md#fencedlock-distributed-locking).

## Quick Facts

- **Base Package**: `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo`
- **Purpose**: MongoDB-backed distributed fencing locks for intra-service coordination
- **Extends**: `dk.trustworks.essentials.components.foundation.fencedlock.DBFencedLockManager<ClientSessionAwareUnitOfWork, DBFencedLock>`
- **Key Deps**: Spring Data MongoDB, foundation module
- **Scope**: Intra-service only (instances sharing same MongoDB database)
- **Requirements**: MongoDB 4.0+ with replica set (for transactions)
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>springdata-mongo-distributed-fenced-lock</artifactId>
</dependency>
```

## TOC

- [Core Classes](#core-classes)
- [Configuration](#configuration)
- [Database Schema](#database-schema)
- [Usage Patterns](#usage-patterns)
- [Spring Integration](#spring-integration)
- [MongoDB-Specific Behavior](#mongodb-specific-behavior)
- [Logging](#logging)
- ⚠️ [Security](#security)
- [Common Use Cases](#common-use-cases)
- [Gotchas](#gotchas)
- [Comparison with PostgreSQL](#comparison-with-postgresql)
- [Dependencies & Tests](#dependencies--tests)

## Core Classes

**Dependencies from other modules**:
- `FencedLockManager`, `FencedLock`, `LockName`, `LockCallback`, `DBFencedLock` from [foundation](./LLM-foundation.md)
- `ClientSessionAwareUnitOfWork`, `UnitOfWorkFactory` from [foundation](./LLM-foundation.md)

| Class | Role |
|-------|------|
| `MongoFencedLockManager` | Main lock manager implementation |
| `MongoFencedLockStorage` | MongoDB persistence layer |
| `MongoFencedLockManagerBuilder` | Fluent builder |
| `dk.trustworks.essentials.components.foundation.fencedlock.DBFencedLock` | Lock implementation (from foundation) |
| `dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager` | Interface (from foundation) |
| `dk.trustworks.essentials.components.foundation.fencedlock.LockCallback` | Async callback interface (from foundation) |

## Configuration

### Builder Pattern

```java
import dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.mongo.ClientSessionAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;

MongoFencedLockManager lockManager = MongoFencedLockManager.builder()
    .setMongoTemplate(mongoTemplate)                          // Required
    .setUnitOfWorkFactory(unitOfWorkFactory)                  // Required
    .setLockManagerInstanceId("node-01")                      // Optional, defaults to hostname
    .setLockTimeOut(Duration.ofSeconds(10))                   // Optional, default 10s
    .setLockConfirmationInterval(Duration.ofSeconds(3))       // Optional, default 3s
    .setFencedLocksCollectionName("my_locks")                 // Optional, default "fenced_locks"
    .setEventBus(Optional.of(eventBus))                       // Optional
    .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(false) // Optional, default false
    .buildAndStart();  // Or .build() without starting
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mongoTemplate` | `MongoTemplate` | Required | Spring Data MongoDB template |
| `unitOfWorkFactory` | `UnitOfWorkFactory<ClientSessionAwareUnitOfWork>` | Required | Transaction management |
| `lockManagerInstanceId` | `String` | Hostname | Unique ID for this manager instance |
| `lockTimeOut` | `Duration` | 10 seconds | Lock expiration without confirmation |
| `lockConfirmationInterval` | `Duration` | 3 seconds | Heartbeat interval |
| `fencedLocksCollectionName` | `String` | `"fenced_locks"` | Collection name ⚠️ NoSQL injection risk |
| `eventBus` | `Optional<EventBus>` | Empty | Event bus for lock events |
| `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` | `boolean` | `false` | Release locks on IO errors during confirmation |

### Configuration Guidelines

```java
// Recommended timeout ratio: confirmation = timeout / 3
Duration lockTimeout = Duration.ofSeconds(30);
Duration confirmationInterval = lockTimeout.dividedBy(3);  // 10 seconds

// Instance ID best practices
import dk.trustworks.essentials.shared.network.Network;
String instanceId = Network.hostName();                    // Use hostname (default)
String instanceId = "service-" + UUID.randomUUID();        // Or unique ID
```

## Database Schema

### Collection Structure

Automatically created with indexes on first use:

```javascript
// Collection: fenced_locks (default name)
{
  _id/name: "ProcessOrders",                   // LockName (String)
  lastIssuedFencedToken: 42,                   // Long, monotonic counter
  lockedByLockManagerInstanceId: "node-1",     // String, null if unlocked
  lockAcquiredTimestamp: ISODate(...),         // Instant, when acquired
  lockLastConfirmedTimestamp: ISODate(...)     // Instant, last heartbeat
}
```

### Indexes

```javascript
// Auto-created by MongoFencedLockStorage
// Index 1: find_lock
{ name: 1, lastIssuedFencedToken: 1 }

// Index 2: confirm_lock
{ name: 1, lastIssuedFencedToken: 1, lockedByLockManagerInstanceId: 1 }
```

## Usage Patterns

All `FencedLockManager` API methods inherited from foundation. See [LLM-foundation.md#fencedlock-api](./LLM-foundation.md#fencedlock-distributed-locking) for complete API reference.

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
import dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.mongo.ClientSessionAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.types.springdata.mongo.SingleValueTypeConverter;

@Configuration
@EnableMongoRepositories
@EnableTransactionManagement
public class LockConfiguration {

    @Bean
    public MongoFencedLockManager fencedLockManager(
            MongoTemplate mongoTemplate,
            UnitOfWorkFactory<? extends ClientSessionAwareUnitOfWork> unitOfWorkFactory) {

        return MongoFencedLockManager.builder()
                                     .setMongoTemplate(mongoTemplate)
                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                     .setLockTimeOut(Duration.ofSeconds(30))
                                     .setLockConfirmationInterval(Duration.ofSeconds(10))
                                     .buildAndStart();
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        // Required for LockName serialization
        return new MongoCustomConversions(List.of(
                new SingleValueTypeConverter(LockName.class)
        ));
    }

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    @Bean
    public SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory(
            MongoTransactionManager transactionManager,
            MongoDatabaseFactory databaseFactory) {
        return new SpringMongoTransactionAwareUnitOfWorkFactory(
                transactionManager,
                databaseFactory
        );
    }

    @PreDestroy
    public void cleanup(MongoFencedLockManager lockManager) {
        lockManager.stop();
    }
}
```

### MongoDB Requirements

```java
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

// MongoDB connection MUST support transactions (replica set or sharded cluster)
@Bean
public MongoClient mongoClient() {
    // ✅ Correct: Replica set connection string
    return MongoClients.create(
        "mongodb://host1:27017,host2:27017,host3:27017/db?replicaSet=rs0"
    );

    // ❌ Wrong: Standalone MongoDB - transactions will fail
    // return MongoClients.create("mongodb://localhost:27017");
}
```

### With Custom Collection Name

```java
@Bean
public MongoFencedLockManager fencedLockManager(
        MongoTemplate mongoTemplate,
        UnitOfWorkFactory<? extends ClientSessionAwareUnitOfWork> unitOfWorkFactory,
        @Value("${app.name}") String appName) {
    return MongoFencedLockManager.builder()
                                 .setMongoTemplate(mongoTemplate)
                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                 .setLockTimeOut(Duration.ofSeconds(30))
                                 .setLockConfirmationInterval(Duration.ofSeconds(10))
                                 .setFencedLocksCollectionName(appName + "_fenced_locks") // ⚠️ See Security
                                 .buildAndStart();
}
```

## MongoDB-Specific Behavior

### WriteConflict Handling

```java
// MongoFencedLockStorage automatically handles MongoDB WriteConflicts
// Internally retries on WriteConflict exceptions
try {
    var lock = lockManager.tryAcquireLock(LockName.of("resource"));
    if (lock.isEmpty()) {
        // Lock held by another instance OR WriteConflict occurred
    }
} catch (Exception e) {
    // Non-WriteConflict errors propagate
}
```

### Transaction Integration

```java
import dk.trustworks.essentials.components.foundation.transaction.spring.mongo.SpringMongoTransactionAwareUnitOfWorkFactory;

var unitOfWorkFactory = new SpringMongoTransactionAwareUnitOfWorkFactory(
    transactionManager,
    databaseFactory
);
var lockManager = MongoFencedLockManager.builder()
    .setMongoTemplate(mongoTemplate)
    .setUnitOfWorkFactory(unitOfWorkFactory)  // Joins existing transactions
    .buildAndStart();
```

## Logging

Configure loggers for lock operations:

| Logger Name | Purpose |
|-------------|---------|
| `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockStorage` | MongoDB lock storage operations |
| `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager` | Lock manager operations |

```yaml
# Logback/Spring Boot
logging.level:
  dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo: DEBUG
```

```xml
<!-- Logback.xml -->
<logger name="dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo" level="DEBUG"/>
```

## Security

### ⚠️ Critical: NoSQL Injection Risk

`fencedLocksCollectionName` is used directly as MongoDB collection name with **String concatenation** → NoSQL injection risk. While `MongoFencedLockStorage` calls `MongoUtil.checkIsValidCollectionName()` for basic validation, **this is NOT exhaustive protection**.

See [README Security](../components/springdata-mongo-distributed-fenced-lock/README.md#security) for full details.

**Required practices:**

```java
// ❌ DANGEROUS - Never use user input
String collectionName = userInput + "_locks";  // NoSQL INJECTION RISK

// ✅ SAFE - Use controlled values only
String collectionName = "order_service_locks";              // Fixed string
String collectionName = applicationName + "_fenced_locks";  // From trusted config
```

**Mitigations:**
1. Only use collection names from controlled sources (config files, constants)
2. Never derive collection names from external/untrusted input
3. Validate at startup: `MongoUtil.checkIsValidCollectionName(collectionName)`
4. Use default collection name when possible

### What Validation Does NOT Protect Against

- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Gotchas

### ⚠️ MongoDB Transaction Requirements

```java
// ❌ WRONG: Standalone MongoDB (no transactions)
// Will throw MongoTransactionException
MongoClient client = MongoClients.create("mongodb://localhost:27017");

// ✅ RIGHT: Replica set (supports transactions)
MongoClient client = MongoClients.create(
    "mongodb://host1:27017,host2:27017/db?replicaSet=rs0"
);
```

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

### ⚠️ Collection Name Security

```java
import dk.trustworks.essentials.components.foundation.mongo.MongoUtil;

// ❌ WRONG - User input in collection name
String collectionName = request.getParameter("collection") + "_locks";

// ✅ RIGHT - Controlled collection name
String collectionName = config.getApplicationName() + "_locks";
MongoUtil.checkIsValidCollectionName(collectionName);
```

## Comparison with PostgreSQL

| Aspect | MongoDB | PostgreSQL |
|--------|---------|-----------|
| **Module** | `springdata-mongo-distributed-fenced-lock` | `postgresql-distributed-fenced-lock` |
| **Storage** | MongoDB collection | PostgreSQL table |
| **Technology** | Spring Data MongoDB | JDBI + JDBC |
| **UnitOfWork** | `ClientSessionAwareUnitOfWork` | `HandleAwareUnitOfWork` |
| **Transactions** | MongoDB transactions | SQL transactions |
| **Requirements** | MongoDB 4.0+ (replica set) | PostgreSQL 9.5+ |
| **Index Type** | MongoDB compound indexes | B-tree indexes |
| **Schema Init** | Automatic collection/index creation | Automatic table creation |
| **Query Language** | MongoDB query DSL | SQL |
| **Injection Risk** | NoSQL injection | SQL injection |
| **Default Storage** | `fenced_locks` collection | `fenced_locks` table |

See: [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md)

## Dependencies & Tests

### Maven

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>springdata-mongo-distributed-fenced-lock</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

### Runtime Requirements

- MongoDB 4.0+ (replica set)
- Spring Data MongoDB
- foundation module
- shared module
- reactive module (optional, for EventBus)
- types-springdata-mongo (for `SingleValueTypeConverter`)

### Test References

- `MongoFencedLockManagerIT` - Integration tests with TestContainers MongoDB
- `MongoFencedLockManager_MultiNode_ReleaseLockIT` - Multi-node failover scenarios
- `MongoFencedLockStorageTest` - Storage layer unit tests

## See Also

- [LLM-foundation.md#fencedlock-distributed-locking](./LLM-foundation.md#fencedlock-distributed-locking) - Core FencedLock concepts
- [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL implementation
- [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) - Queue consumer coordination use case
- [README](../components/springdata-mongo-distributed-fenced-lock/README.md) - Full documentation
