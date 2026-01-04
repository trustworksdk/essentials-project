# SpringData MongoDB Distributed Fenced Lock - LLM Reference

> See [README](../components/springdata-mongo-distributed-fenced-lock/README.md) for detailed developer documentation.

> For core FencedLock concepts (why, when, how), see [LLM-foundation.md](./LLM-foundation.md#fencedlock-distributed-locking).

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo`
- **Purpose**: MongoDB-backed distributed fencing locks for intra-service coordination
- **Extends**: `DBFencedLockManager<ClientSessionAwareUnitOfWork, DBFencedLock>` from foundation
- **Key Deps**: Spring Data MongoDB, foundation module
- **Scope**: Intra-service only (instances sharing same MongoDB database)
- **Requirements**: MongoDB 4.0+ with replica set (for transactions)
- **Status**: WORK-IN-PROGRESS

## TOC
- [Core Classes](#core-classes)
- [Configuration](#configuration)
- [Database Schema](#database-schema)
- [Usage Patterns](#usage-patterns)
- [Spring Integration](#spring-integration)
- [Logging](#logging)
- [Security](#security)
- [Common Use Cases](#common-use-cases)
- [Integration with Other Components](#integration-with-other-components)
- [Gotchas](#gotchas)
- [Comparison with PostgreSQL](#comparison-with-postgresql)
- [Dependencies & Tests](#dependencies--tests)

## Core Classes

| Class | Package                                                    | Role |
|-------|------------------------------------------------------------|------|
| `MongoFencedLockManager` | `(root)`                                          | Main lock manager implementation |
| `MongoFencedLockStorage` | `(root)`                                          | MongoDB persistence layer |
| `MongoFencedLockManagerBuilder` | `(root)`                                   | Fluent builder |
| `DBFencedLock` | `dk.trustworks.essentials.components.foundation.fencedlock` | Lock implementation (from foundation) |

## Configuration

### Builder Pattern

```java
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

| Option | Type | Default          | Description                                             |
|--------|------|------------------|---------------------------------------------------------|
| `mongoTemplate` | `MongoTemplate` | Required         | Spring Data MongoDB template                            |
| `unitOfWorkFactory` | `UnitOfWorkFactory<ClientSessionAwareUnitOfWork>` | Required         | Transaction management  |
| `lockManagerInstanceId` | `String` | Hostname         | Unique ID for this manager instance                     |
| `lockTimeOut` | `Duration` | 10 seconds       | Lock expiration without confirmation                    |
| `lockConfirmationInterval` | `Duration` | 3 seconds        | Heartbeat interval                                      |
| `fencedLocksCollectionName` | `String` | `"fenced_locks"` | Collection name ⚠️ NoSQL security risk  (see security) |
| `eventBus` | `Optional<EventBus>` | Empty            | Event bus for lock events                               |
| `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` | `boolean` | `false`          | Release locks on IO errors during confirmation          |

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

### Collection Structure

Automatically created with indexes on first use:

```javascript
// Collection: fenced_locks (default name)
{
  _id/name: "ProcessOrders",                   // LockName (String)
  lastIssuedFencedToken: 42,              // Long, monotonic counter
  lockedByLockManagerInstanceId: "node-1", // String, null if unlocked
  lockAcquiredTimestamp: ISODate(...),    // Instant, when acquired
  lockLastConfirmedTimestamp: ISODate(...)// Instant, last heartbeat
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

### MongoDB-Specific Behavior

#### WriteConflict Handling

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

#### Transaction Integration

```java
var unitOfWorkFactory = new SpringMongoTransactionAwareUnitOfWorkFactory(
    transactionManager,
    databaseFactory
);
var lockManager = MongoFencedLockManager.builder()
    .setMongoTemplate(mongoTemplate)
    .setUnitOfWorkFactory(unitOfWorkFactory)  // Joins existing transactions
    .buildAndStart();
```

## Spring Integration

### Bean Configuration

```java
import java.beans.BeanProperty;

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
    public SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory(MongoTransactionManager mongoTransactionManager,
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
                                 .setFencedLocksCollectionName(appName + "_fenced_locks") // Custom collection name
                                 .buildAndStart();
}
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
  dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockStorage: DEBUG
  dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager: DEBUG
```

```xml
<!-- Logback.xml -->
<logger name="dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockStorage" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager" level="DEBUG"/>
```

## Security

### ⚠️ Critical: NoSQL Injection Risk

The components allow customization of collection-names that are used with **String concatenation** → NoSQL injection risk.  
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against NoSQL injection.

### NoSQL Injection Risk

⚠️ **CRITICAL**: `fencedLocksCollectionName` is used directly as MongoDB collection name.

```java
// ❌ DANGEROUS - Never use user input
String collectionName = userInput + "_locks";  // NoSQL INJECTION RISK

// ✅ SAFE - Use controlled values only
String collectionName = "order_service_locks";         // Fixed string
String collectionName = applicationName + "_fenced_locks";    // From trusted config
```

1. Only use collection names from controlled sources (config files, constants)
2. Never derive collection names from external/untrusted input
3. Validate at application startup with `MongoUtil.checkIsValidCollectionName(collectionName)`
4. Use default collection name when possible

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

- [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) - Queue consumer coordination
- [spring-boot-starter-mongodb](./LLM-spring-boot-starter-modules.md) - Auto-configuration
- [foundation Inbox/Outbox](./LLM-foundation.md#inboxoutbox-patterns) - SingleGlobalConsumer mode

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
FencedLock lock = lockManager.acquireLock(lockName, timeout);
doWork(lock.getCurrentToken());
lockManager.releaseLock(lock);  // Never called if doWork() throws

// ✅ RIGHT - Try-with-resources
try (var handle = lockManager.acquireLockAndGetLockHandle(lockName, timeout)) {
    doWork(handle.getCurrentToken());
}
```

### ⚠️ Collection Name Security

```java
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

- [LLM-foundation.md#fencedlock-api](./LLM-foundation.md#fencedlock-distributed-locking) - Core FencedLock concepts
- [LLM-postgresql-distributed-fenced-lock.md](./LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL implementation
- [README](../components/springdata-mongo-distributed-fenced-lock/README.md) - Full documentation
