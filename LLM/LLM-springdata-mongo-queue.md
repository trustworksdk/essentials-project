# MongoDB Queue - LLM Reference

> **Foundation**: [LLM-foundation.md](./LLM-foundation.md#durablequeues-messaging) | **Developer docs**: [README](../components/springdata-mongo-queue/README.md)

## TOC
- [Quick Facts](#quick-facts)
- [Configuration](#configuration)
- [Transaction Modes](#transaction-modes)
- [Document Schema](#document-schema)
- [Polling Optimization](#polling-optimization)
- [Common Pitfalls](#common-pitfalls)
- [Monitoring](#monitoring)
- [Integration](#integration)
- [PostgreSQL vs MongoDB](#postgresql-vs-mongodb)
- ⚠️ [Security](#security)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.queue.springdata.mongodb`
- **Implementation**: `MongoDurableQueues`
- **Storage**: MongoDB collection with BSON
- **Locking**: `findAndModify()` atomicity
- **Notifications**: Change Streams (MongoDB 4.0+ replica set required)
- **Dependencies**: Spring Data MongoDB (`provided`), foundation module
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>springdata-mongo-queue</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `DurableQueues`, `QueueName`, `ConsumeFromQueue`, `RedeliveryPolicy` from [foundation](./LLM-foundation.md)
- `SpringMongoTransactionAwareUnitOfWorkFactory` from [foundation](./LLM-foundation.md)

## Configuration

### Constructor Options
```java
// Base package: dk.trustworks.essentials.components.queue.springdata.mongodb

// SingleOperationTransaction (Recommended)
MongoDurableQueues(MongoTemplate mongoTemplate, Duration messageHandlingTimeout)

// Custom collection name
MongoDurableQueues(MongoTemplate mongoTemplate, Duration messageHandlingTimeout,
                   String sharedQueueCollectionName)

// Custom polling optimizer
MongoDurableQueues(MongoTemplate mongoTemplate, String sharedQueueCollectionName,
                   Duration messageHandlingTimeout,
                   Function<ConsumeFromQueue, QueuePollingOptimizer> queuePollingOptimizerFactory)

// FullyTransactional (NOT RECOMMENDED)
MongoDurableQueues(MongoTemplate mongoTemplate,
                   SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory)
```

| Param | Type | Purpose |
|-------|------|---------|
| `mongoTemplate` | `MongoTemplate` | Spring Data MongoDB template |
| `messageHandlingTimeout` | `Duration` | Stuck message timeout |
| `sharedQueueCollectionName` | `String` | Collection name (⚠️ [injection risk](#security)) |
| `queuePollingOptimizerFactory` | `Function<ConsumeFromQueue, QueuePollingOptimizer>` | Custom optimizer |
| `unitOfWorkFactory` | `SpringMongoTransactionAwareUnitOfWorkFactory` | For FullyTransactional mode |

### Basic Setup
```java
// Base package: dk.trustworks.essentials.components.queue.springdata.mongodb
import dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;

@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(mongoTemplate, 
                                        Duration.ofSeconds(10) // Message handling timeout
                                        );
    queues.start();  // ⚠️ Required - enables Change Streams (or register dk.trustworks.essentials.components.foundation.lifecycle.DefaultLifecycleManager as bean)
    return queues;
}
```

### Spring Integration
```java
// Base packages:
// - dk.trustworks.essentials.types.springdata.mongo.converters (SingleValueTypeConverter)
// - dk.trustworks.essentials.components.foundation.types.* (QueueEntryId, QueueName)

@Bean
public MongoTemplate mongoTemplate(MongoDatabaseFactory factory, MongoConverter converter) {
    MongoTemplate template = new MongoTemplate(factory, converter);
    template.setWriteConcern(WriteConcern.ACKNOWLEDGED);
    template.setWriteResultChecking(WriteResultChecking.EXCEPTION);
    return template;
}

@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
        new SingleValueTypeConverter(QueueEntryId.class, QueueName.class)
    ));
}

@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10));
    queues.start(); // - enables Change Streams (or register dk.trustworks.essentials.components.foundation.lifecycle.DefaultLifecycleManager as bean)
    return queues;
}
```

## Transaction Modes

| Mode | Behavior | Recommendation |
|------|----------|----------------|
| `SingleOperationTransaction` | Each op in own transaction | ✅ **Use this** - proper retry/DLQ |
| `FullyTransactional` | Join parent transaction | ❌ **Avoid** - breaks retry counts |

⚠️ **FullyTransactional breaks retry handling**: Transaction rollback prevents retry count updates and DLQ persistence.

## Document Schema

```javascript
{
  _id: QueueEntryId,
  queueName: QueueName,
  isBeingDelivered: Boolean,
  messagePayload: BinData,              // Serialized byte[]
  messagePayloadType: String,           // Java class name
  addedTimestamp: ISODate,
  nextDeliveryTimestamp: ISODate,       // Supports delayed delivery
  deliveryTimestamp: ISODate,
  totalDeliveryAttempts: NumberInt,
  redeliveryAttempts: NumberInt,
  lastDeliveryError: String,
  isDeadLetterMessage: Boolean,
  metaData: Object,

  // OrderedMessage only:
  deliveryMode: String,                 // "NORMAL" | "IN_ORDER"
  key: String,                          // Ordering key
  keyOrder: NumberLong                  // Sequence
}
```

### Indexes
Auto-created on `start()`:
```javascript
{ queueName: 1, nextDeliveryTimestamp: 1, isDeadLetterMessage: 1,
  isBeingDelivered: 1, key: 1, keyOrder: 1 }  // Primary delivery
{ queueName: 1, key: 1, keyOrder: 1 }          // Ordered messages
{ queueName: 1, deliveryTimestamp: 1, isBeingDelivered: 1 }  // Stuck detection
{ _id: 1, isBeingDelivered: 1 }                // Message lookup
{ _id: 1, isDeadLetterMessage: 1 }             // DLQ resurrection
```

## Polling Optimization

### SimpleQueuePollingOptimizer
Linear backoff - only option for MongoDB.

**Algorithm**: `delay += increment` on empty poll, reset on message/notification.

```java
// Base package: dk.trustworks.essentials.components.foundation.messaging.queue
import dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;

new MongoDurableQueues(
    mongoTemplate,
    null,  // unitOfWorkFactory
    "durable_queues",
    Duration.ofSeconds(10), // Message handling timeout
    consumeFromQueue -> new SimpleQueuePollingOptimizer(
        consumeFromQueue,
        100,    // delayIncrementMs
        5000    // maxDelayMs
    )
);
```

| Param | Description |
|-------|-------------|
| `delayIncrementMs` | Added per empty poll (100ms) |
| `maxDelayMs` | Cap (5000ms) |

**Defaults** (if no factory):
- `delayIncrementMs` = 50% of `messageHandlingTimeout`
- `maxDelayMs` = 20× `messageHandlingTimeout`

### Change Streams
Automatically enabled on `start()` - triggers immediate polling on message arrival.

```java
durableQueues.start();  // Creates MessageListenerContainer
// Watches: insert, update, replace operations
// Calls: pollingOptimizer.messageAdded() → delay resets to 0
```

**Requirements**:
- MongoDB 4.0+ with replica set
- AWS DocumentDB: See [Change Streams docs](https://docs.aws.amazon.com/documentdb/latest/developerguide/change_streams.html)

## Common Pitfalls

**1. Standalone MongoDB (no replica set)**
```java
// ❌ "mongodb://localhost:27017/myapp"  // No transactions/Change Streams
// ✅ "mongodb://localhost:27017,localhost:27018/myapp?replicaSet=rs0"
```

**2. FullyTransactional breaks retries**
```java
// ❌ new MongoDurableQueues(mongoTemplate, unitOfWorkFactory)
// ✅ new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10))
```

**3. Forgetting start()**
```java
// ❌ var queues = new MongoDurableQueues(...);  // Change Streams inactive
// ✅ var queues = new MongoDurableQueues(...); queues.start();
```

**4. Missing type converters**
```java
// ❌ new MongoCustomConversions(List.of())
// ✅ new MongoCustomConversions(List.of(
//       new SingleValueTypeConverter(QueueEntryId.class, QueueName.class)))
```

## Monitoring

### DurableQueues API
Standard monitoring via `DurableQueues` interface:

```java
// Base package: dk.trustworks.essentials.components.foundation.messaging.queue
import dk.trustworks.essentials.components.foundation.messaging.queue.*;

// Queue depth and dead letter counts
QueuedMessageCounts counts = durableQueues.getQueuedMessageCountsFor(queueName);
long queuedMessages = counts.getTotalQueuedMessages();
long deadLetterMessages = counts.getDeadLetterMessages();

// Dead letter messages (paginated)
List<QueuedMessage> dlq = durableQueues.getDeadLetterMessages(
    queueName, QueueingSortOrder.ASC, 0, 100);

// All queue names
Set<QueueName> queueNames = durableQueues.getQueueNames();
```

### Interceptors (Micrometer)
Add metrics/tracing via interceptors:

```java
// Base packages:
// - dk.trustworks.essentials.components.foundation.messaging.queue.micrometer
// - dk.trustworks.essentials.components.foundation.interceptor.micrometer

import dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.*;
import dk.trustworks.essentials.components.foundation.interceptor.micrometer.*;

var durableQueues = new MongoDurableQueues(mongoTemplate, 
                                           Duration.ofSeconds(10) // Message handling timeout
                                           );
durableQueues.addInterceptors(new DurableQueuesMicrometerInterceptor(meterRegistry, "MyService"), 
                              new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry),
                              new RecordExecutionTimeDurableQueueInterceptor(meterRegistry, "MyService"));
```

**Interceptor Classes**:

| Class | Package | Purpose |
|-------|---------|---------|
| `DurableQueuesMicrometerInterceptor` | `.messaging.queue.micrometer` | Queue size gauges, counters (processed, handled, retries, DLQ) |
| `DurableQueuesMicrometerTracingInterceptor` | `.messaging.queue.micrometer` | Distributed tracing via Micrometer Observation |
| `RecordExecutionTimeDurableQueueInterceptor` | `.interceptor.micrometer` | Operation execution time |

Base package: `dk.trustworks.essentials.components.foundation`

### Logging
Configure loggers for queue operations:

| Logger Name | Purpose |
|-------------|---------|
| `dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues` | MongoDB queue implementation |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer` | Consumer operations |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures` | Message handling failures |
| `dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer` | Linear backoff optimizer |

```yaml
# Logback/Spring Boot
logging.level:
  dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures: WARN
```

```xml
<!-- Logback.xml -->
<logger name="dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures" level="WARN"/>
```

### MongoDB Queries (Advanced)
Direct MongoDB queries for custom metrics:

```java
// Dead letter count
long deadLetterCount = mongoTemplate.count(
    Query.query(Criteria.where("queueName").is(queueName)
                        .and("isDeadLetterMessage").is(true)),
    "durable_queues");

// Stuck messages
long stuckCount = mongoTemplate.count(
    Query.query(Criteria.where("queueName").is(queueName)
                        .and("isBeingDelivered").is(true)
                        .and("deliveryTimestamp")
                        .lt(Instant.now().minus(Duration.ofMinutes(5)))),
    "durable_queues");
```

### Change Streams Health
Monitor Change Streams for errors:

```java
@Bean
public ApplicationListener<MessageListenerContainerErrorEvent> errorListener() {
    return event -> {
        log.error("Change Stream error: {}", event.getCause().getMessage());
    };
}
```

## Integration

### Spring Boot Starter
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-mongodb</artifactId>
</dependency>
```

See [LLM-spring-boot-starter-modules.md](./LLM-spring-boot-starter-modules.md#mongodb-starter).

### Related Modules
- **[foundation](./LLM-foundation.md#durablequeues-messaging)** - `DurableQueues` interface
- **[postgresql-queue](./LLM-postgresql-queue.md)** - PostgreSQL implementation
- **[types-springdata-mongo](./LLM-types-springdata-mongo.md)** - MongoDB type converters
- **[reactive](./LLM-reactive.md)** - Event/command bus integration

## PostgreSQL vs MongoDB

| Aspect | PostgreSQL | MongoDB |
|--------|-----------|---------|
| **Storage** | SQL table + JSONB | Collection + BSON |
| **Transactions** | JDBI/JDBC | Spring Data MongoDB |
| **Notifications** | LISTEN/NOTIFY | Change Streams |
| **Locking** | `FOR UPDATE SKIP LOCKED` | `findAndModify()` |
| **Polling** | Centralized + Linear/Exponential | Linear only |
| **Config** | Builder pattern | Constructor |

## Security

### ⚠️ NoSQL Injection Risk

`sharedQueueCollectionName` used directly in MongoDB operations.

See [README Security](../components/springdata-mongo-queue/README.md#security) for full details.

**Quick rules**:
```java
// ❌ DANGEROUS
String collectionName = userInput + "_queue";
new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10), collectionName);

// ✅ SAFE - hardcoded value
new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10), "durable_queues");

// ⚠️ Validate if from config
MongoUtil.checkIsValidCollectionName(collectionName);  // Basic validation (not exhaustive)
```

**Mitigations**:
- `MongoUtil.checkIsValidCollectionName()` provides initial defense (NOT exhaustive)
- Only use collection names from controlled, trusted sources
- Never derive from external/untrusted input

### What Validation Does NOT Protect Against

- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Test Utilities

```java
// Base package: dk.trustworks.essentials.components.queue.springdata.mongodb
import dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues;
import org.testcontainers.containers.MongoDBContainer;

@Container
static MongoDBContainer mongo = new MongoDBContainer("mongo:6.0");

@Bean
public DurableQueues testDurableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(
        mongoTemplate,
        Duration.ofSeconds(10), // Message handling timeout
        "test_queue"
    );
    queues.start();
    return queues;
}
```

## Performance Tuning

See [README](../components/springdata-mongo-queue/README.md) for:
- Connection pool configuration
- Parallel consumer tuning
- Polling optimization strategies
