# Essentials Components - SpringData MongoDB Queue

> **NOTE:** **The library is WORK-IN-PROGRESS**

MongoDB implementation of the `DurableQueues` interface using Spring Data MongoDB for durable message queuing.

**LLM Context:** [LLM-springdata-mongo-queue.md](../../LLM/LLM-springdata-mongo-queue.md)

## Table of Contents
- [Overview](#overview)
- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [MongoDB-Specific Configuration](#mongodb-specific-configuration)
- [Polling Optimization](#polling-optimization)
- [Collection Schema](#collection-schema)
- [Usage](#usage)
- [Comparison with PostgreSQL Implementation](#comparison-with-postgresql-implementation)

## Overview

This module provides `MongoDurableQueues`, a MongoDB-backed implementation of the [`DurableQueues`](../foundation/README.md#durablequeues-messaging) interface from the foundation module.

For conceptual documentation on durable queues (why, when, how), see:
- [Foundation - DurableQueues](../foundation/README.md#durablequeues-messaging)
- [Foundation - Inbox Pattern](../foundation/README.md#inbox-pattern)
- [Foundation - Outbox Pattern](../foundation/README.md#outbox-pattern)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>springdata-mongo-queue</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: NoSQL Injection Risk

The components allow customization of collection names that are used with **String concatenation** → NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against NoSQL injection.

The `sharedQueueCollectionName` parameter is used directly as a MongoDB collection name, exposing the component to potential malicious input.

> ⚠️ **WARNING:** It is your responsibility to sanitize the `sharedQueueCollectionName` value.
> See the [Security](../README.md#security) section for more details.

**Mitigations:**
- `MongoDurableQueues` calls `MongoUtil.checkIsValidCollectionName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete security

**Developer Responsibility:**
- Only use `sharedQueueCollectionName` values from controlled, trusted sources
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

- **MongoDB 4.0+** with replica set (required for Change Streams and transactions)
- Spring Data MongoDB

### Basic Setup (SingleOperationTransaction - Recommended)

```java
@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    return new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10));
}
```

### Full Spring Configuration

```java
@Configuration
@EnableMongoRepositories
@EnableTransactionManagement
public class MongoQueueConfiguration {

    @Bean
    public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
        return new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10));
    }

    @Bean
    public SingleValueTypeRandomIdGenerator registerIdGenerator() {
        return new SingleValueTypeRandomIdGenerator();
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(QueueEntryId.class, QueueName.class)));
    }

    @Bean
    MongoTemplate mongoTemplate(MongoDatabaseFactory mongoDbFactory, MongoConverter converter) {
        MongoTemplate mongoTemplate = new MongoTemplate(mongoDbFactory, converter);
        mongoTemplate.setWriteConcern(WriteConcern.ACKNOWLEDGED);
        mongoTemplate.setWriteResultChecking(WriteResultChecking.EXCEPTION);
        return mongoTemplate;
    }

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }
}
```

### Constructor Options

| Option | Description |
|--------|-------------|
| `mongoTemplate` | Required - MongoTemplate instance |
| `messageHandlingTimeout` | Timeout before unacknowledged messages are redelivered (default: 10s) |
| `sharedQueueCollectionName` | Collection name for all queues (default: `durable_queues`) |
| `unitOfWorkFactory` | For FullyTransactional mode only |
| `queuePollingOptimizerFactory` | Custom polling optimizer factory |

### Transaction Modes

| Mode | Description | Recommended |
|------|-------------|-------------|
| `SingleOperationTransaction` | Each queue operation in own transaction | **Yes** |
| `FullyTransactional` | Operations share parent transaction | No |

> ⚠️ **Warning:** `FullyTransactional` mode causes issues with retries and dead letter handling because the transaction is marked for rollback and retry counts are never increased.

### FullyTransactional Configuration (Not Recommended)

```java
@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate,
        SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory) {
    return new MongoDurableQueues(mongoTemplate, unitOfWorkFactory);
}

@Bean
public SpringMongoTransactionAwareUnitOfWorkFactory unitOfWorkFactory(
        MongoTransactionManager transactionManager, MongoDatabaseFactory databaseFactory) {
    return new SpringMongoTransactionAwareUnitOfWorkFactory(transactionManager, databaseFactory);
}
```

## Polling Optimization

### Why

Continuous polling at a fixed interval wastes database resources when queues are idle.
Polling optimizers implement **adaptive backoff** — reducing poll frequency during quiet periods and resetting to aggressive polling when messages arrive.

> Optimization controls backoff per queue-name, such that queues with high activity will be polled more frequently, whereas queues with low activity will be polled less frequently.

### How It Works

```
Queue Activity Over Time:
┌──────────────────────────────────────────────────────────────────────────────┐
│  Messages       ▓▓▓▓▓                       ▓▓                               │
│  Arriving       ▓▓▓▓▓▓▓                     ▓▓▓                              │
│                                                                              │
│  Poll           │││││││││                   │││││││││││                      │
│  Frequency      │││││││││                   │││││││││││   ← short intervals  │
│  (high)         │││││││││                   │││││││││││                      │
│                                                                              │
│  Poll                     │     │     │                 │     │     │     │  │
│  Frequency                │     │     │                 │     │     │     │  │
│  (backed off)             │     │     │                 │     │     │     │  │
│                           ↑ long intervals ↑                                 │
│                 ──────────────────────────────────────────────────── time    │
└──────────────────────────────────────────────────────────────────────────────┘
```

1. **Message found** → Reset to initial (fast) polling interval
2. **No message found** → Increase delay incrementally using backoff strategy
3. **Message added to queue** → Immediately reset to fast polling (notification via Change Streams)

### SimpleQueuePollingOptimizer

MongoDB uses `SimpleQueuePollingOptimizer` with **linear backoff** — increases delay by a fixed increment each time no messages are found:

```java
@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    return new MongoDurableQueues(
        mongoTemplate,
        null,  // unitOfWorkFactory - null for SingleOperationTransaction
        "durable_queues",
        Duration.ofSeconds(10),
        consumeFromQueue -> new SimpleQueuePollingOptimizer(
            consumeFromQueue,
            100,    // delayIncrementMs - add 100ms each empty poll
            5000    // maxDelayMs - cap at 5 seconds
        )
    );
}
```

**Parameters:**

| Parameter | Description |
|-----------|-------------|
| `delayIncrementMs` | Added to delay after each empty poll (e.g., 100ms) |
| `maxDelayMs` | Maximum delay cap (e.g., 5000ms = 5 seconds) |

**Behavior:** `delay = min(maxDelayMs, currentDelay + delayIncrementMs)`

**Default Configuration:**
If no optimizer factory is provided, `MongoDurableQueues` creates a default `SimpleQueuePollingOptimizer` with:
- `delayIncrementMs` = 50% of polling interval
- `maxDelayMs` = 20× polling interval

### Automatic Wake-up via Change Streams (Built-in)

`MongoDurableQueues` automatically configures MongoDB Change Streams for immediate wake-up when messages arrive. **No manual configuration is required.**

When `MongoDurableQueues.start()` is called, it:
1. Creates a `DefaultMessageListenerContainer` internally
2. Registers a `ChangeStreamRequest` to watch for insert/update/replace operations
3. Automatically calls `messageAdded()` on the appropriate consumer's polling optimizer

```java
// Change Streams are enabled automatically - just call start()
@Bean
public DurableQueues durableQueues(MongoTemplate mongoTemplate) {
    var queues = new MongoDurableQueues(mongoTemplate, Duration.ofSeconds(10));
    queues.start();  // Starts the MessageListenerContainer and Change Stream listener
    return queues;
}
```

When a message is added to any queue, the Change Stream notification triggers `messageAdded()` on the polling optimizer, immediately resetting the delay to zero for that queue.

> **Note:** Change Streams require MongoDB 4.0+ with replica set configuration.
> If using AWS DocumentDB, see: https://docs.aws.amazon.com/documentdb/latest/developerguide/change_streams.html

## Collection Schema

Messages are stored in a MongoDB collection with automatic indexes:

```javascript
{
  _id: QueueEntryId,
  queueName: QueueName,
  isBeingDelivered: Boolean,
  messagePayload: Binary,           // Serialized payload
  messagePayloadType: String,
  addedTimestamp: ISODate,
  nextDeliveryTimestamp: ISODate,
  deliveryTimestamp: ISODate,
  totalDeliveryAttempts: Int,
  redeliveryAttempts: Int,
  lastDeliveryError: String,
  isDeadLetterMessage: Boolean,
  metaData: Object,
  deliveryMode: String,             // NORMAL or IN_ORDER
  key: String,                      // Ordering key
  keyOrder: Long                    // Order within key
}
```

## Usage

See [Foundation - DurableQueues](../foundation/README.md#durablequeues-messaging) for complete usage patterns including:
- Queueing messages
- Consumer registration
- Pattern matching handlers
- Redelivery policies
- Dead letter handling
- Ordered Message Delivery

## Comparison with PostgreSQL Implementation

| Aspect | MongoDB | PostgreSQL |
|--------|---------|-----------|
| **Module** | `springdata-mongo-queue` | [`postgresql-queue`](../postgresql-queue/README.md) |
| **Storage** | MongoDB collection | SQL table with JSONB |
| **Transactions** | Spring Data MongoDB | JDBI/JDBC |
| **Notifications** | Change Streams | LISTEN/NOTIFY |
| **Locking** | `findAndModify()` | `FOR UPDATE SKIP LOCKED` |
| **Polling** | Per-consumer threads | Centralized or per-consumer |
| **Requirements** | MongoDB 4.0+ (replica set) | PostgreSQL 9.5+ |
