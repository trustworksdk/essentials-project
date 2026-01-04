# MongoDB Queue - LLM Reference

> **Foundation**: [LLM-foundation.md](./LLM-foundation.md#durablequeues-api) | **Developer docs**: [README](../components/springdata-mongo-queue/README.md)

## TOC
- [Quick Facts](#quick-facts)
- [Configuration](#configuration)
- [Transaction Modes](#transaction-modes)
- [Document Schema](#document-schema)
- [Polling Optimization](#polling-optimization)
- ⚠️ [Security](#security)
- [Common Pitfalls](#common-pitfalls)
- [Performance Tuning](#performance-tuning)
- [Monitoring](#monitoring)
- [Integration](#integration)
- [PostgreSQL vs MongoDB](#postgresql-vs-mongodb)
- [Test Utilities](#test-utilities)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.springdata.mongodb.queue`
- **Implementation**: `MongoDurableQueues`
- **Storage**: MongoDB collection with BSON
- **Locking**: `findAndModify()` atomicity
- **Notifications**: Change Streams (MongoDB 4.0+ replica set required)
- **Dependencies**: Spring Data MongoDB (`provided`), foundation module
- **Status**: WORK-IN-PROGRESS

## Configuration

### Constructor Options
```java
// SingleOperationTransaction (Recommended)
MongoDurableQueues(MongoTemplate mongoTemplate, Duration messageHandlingTimeout)

// Custom collection name
MongoDurableQueues(MongoTemplate mongoTemplate, Duration messageHandlingTimeout,
                   String sharedQueueCollectionName)

// Custom polling optimizer
MongoDurableQueues(MongoTemplate mongoTemplate, String sharedQueueCollectionName,
                   Duration messageHandlingTimeout, QueuePollingOptimizerFactory factory)

// FullyTransactional (NOT RECOMMENDED)
MongoDurableQueues(MongoTemplate mongoTemplate,
                   SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory)
```

| Param | Type | Purpose |
|-------|------|---------|
| `mongoTemplate` | `MongoTemplate` | Spring Data MongoDB template |
| `messageHandlingTimeout` | `Duration` | Stuck message timeout |
| `sharedQueueCollectionName` | `String` | Collection name (⚠️ injection risk) |
| `queuePollingOptimizerFactory` | `Function` | Custom optimizer |
| `unitOfWorkFactory` | `SpringMongoTransactionAwareUnitOfWorkFactory` | For FullyTransactional mode |

### Basic Setup
```java
@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10));
    queues.start();  // ⚠️ Required - enables Change Streams
    return queues;
}
```

### Spring Integration
```java
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
    queues.start();
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
Auto-created on start:
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
Linear backoff (only option for MongoDB).

**Algorithm**: `delay += increment` on empty poll, reset on message/notification.

```java
new MongoDurableQueues(
    mongoTemplate,
    null,
    "durable_queues",
    Duration.ofSeconds(10),
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

## Security

### ⚠️ Critical: NoSQL Injection Risk

The components allow customization of collection-names that are used with **String concatenation** → NoSQL injection risk.  
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against NoSQL injection.

### NoSQL Injection Risk
`sharedQueueCollectionName` used directly in MongoDB operations.

```java
// ❌ DANGEROUS
String collectionName = userInput + "_queue";
new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10), collectionName);

// ✅ SAFE
new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10), "durable_queues");

// ⚠️ Validate if from config
MongoUtil.checkIsValidCollectionName(collectionName);  // Basic validation (not exhaustive)
```

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

## Performance Tuning

### Connection Pool
```java
@Bean
public MongoClientSettings mongoClientSettings() {
    return MongoClientSettings.builder()
        .applyToConnectionPoolSettings(builder ->
            builder.maxSize(20).minSize(5)
                   .maxConnectionIdleTime(30, TimeUnit.SECONDS))
        .applyToSocketSettings(builder ->
            builder.connectTimeout(5, TimeUnit.SECONDS)
                   .readTimeout(10, TimeUnit.SECONDS))
        .build();
}
```

### Parallel Consumers
```java
int cpus = Runtime.getRuntime().availableProcessors();
int optimal = Math.min(cpus, expectedVolume * processingTime / 1000);

ConsumeFromQueue.builder()
    .setParallelConsumers(optimal)
    .build()
```

### Polling Tuning
```java
// High-volume: aggressive
new SimpleQueuePollingOptimizer(consumeFromQueue, 50, 1000)

// Low-volume: conservative
new SimpleQueuePollingOptimizer(consumeFromQueue, 500, 30000)
```

## Monitoring

### DurableQueues API
Standard monitoring via `DurableQueues` interface:

```java
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
var durableQueues = new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10)) {
    @Override
    public void start() {
        addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "MyService"));
        addInterceptor(new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry));
        addInterceptor(new RecordExecutionTimeDurableQueueInterceptor(meterRegistry, "MyService"));
        super.start();
    }
};
```

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue.micrometer`
- `DurableQueuesMicrometerInterceptor` - Queue size gauges, counters (processed, handled, retries, DLQ)
- `DurableQueuesMicrometerTracingInterceptor` - Distributed tracing via Micrometer Observation

**Package**: `dk.trustworks.essentials.components.foundation.interceptor.micrometer`
- `RecordExecutionTimeDurableQueueInterceptor` - Operation execution time

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

## Test Utilities

```java
@Container
static MongoDBContainer mongo = new MongoDBContainer("mongo:6.0");

@Bean
public DurableQueues testDurableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(
        mongoTemplate,
        Duration.ofSeconds(10),
        "test_queue"
    );
    queues.start();
    return queues;
}
```
