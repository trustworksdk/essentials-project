# Essentials Components - PostgreSQL Queue

> **NOTE:** **The library is WORK-IN-PROGRESS**

PostgreSQL implementation of the `DurableQueues` interface for durable message queuing.

**LLM Context:** [LLM-postgresql-queue.md](../../LLM/LLM-postgresql-queue.md)

## Overview

This module provides `PostgresqlDurableQueues`, a PostgreSQL-backed implementation of the [`DurableQueues`](../foundation/README.md#durablequeues-messaging) interface from the foundation module.

For conceptual documentation on durable queues (why, when, how), see:
- [Foundation - DurableQueues](../foundation/README.md#durablequeues-messaging)
- [Foundation - Inbox Pattern](../foundation/README.md#inbox-pattern)
- [Foundation - Outbox Pattern](../foundation/README.md#outbox-pattern)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-queue</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

The `sharedQueueTableName` parameter is used directly in SQL statements via string concatenation, exposing the component to **SQL injection attacks**.

> ⚠️ **WARNING:** It is your responsibility to sanitize the `sharedQueueTableName` value.  
> See the [Security](../README.md#security) section for more details.

**Mitigations:**
- `PostgresqlDurableQueues` calls `PostgresqlUtil#checkIsValidTableOrColumnName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

**Developer Responsibility:**
- Only use `sharedQueueTableName` values from controlled, trusted sources
- Never derive table/column/index names from external/untrusted input
- Validate all configuration values during application startup

## PostgreSQL-Specific Configuration

### Basic Setup

```java
var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setJsonSerializer(new JacksonJSONSerializer(
        PostgresqlDurableQueues.createDefaultObjectMapper()))
    .setSharedQueueTableName("message_queue")
    .build();

durableQueues.start();
```

### Spring Integration

```java
@Bean
public Jdbi jdbi(DataSource dataSource) {
    var jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
    jdbi.installPlugin(new PostgresPlugin());
    return jdbi;
}

@Bean
public SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory(
        Jdbi jdbi, DataSourceTransactionManager transactionManager) {
    return new SpringTransactionAwareJdbiUnitOfWorkFactory(jdbi, transactionManager);
}

@Bean
public DurableQueues durableQueues(
        HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
    return PostgresqlDurableQueues.builder()
        .setUnitOfWorkFactory(unitOfWorkFactory)
        .setTransactionMode(TransactionMode.SingleOperationTransaction)
        .setSharedQueueTableName("durable_queues")
        .build();
}
```

### Builder Options

| Option | Default | Description |
|--------|---------|-------------|
| `unitOfWorkFactory` | Required | Transaction factory |
| `jsonSerializer` | Default Jackson | Message serialization |
| `sharedQueueTableName` | `durable_queues` | Database table name |
| `transactionMode` | `SingleOperationTransaction` | Transaction behavior |
| `useCentralizedMessageFetcher` | `true` | Polling mechanism |
| `centralizedMessageFetcherPollingInterval` | 20ms | Polling interval |
| `useOrderedUnorderedQuery` | `false` | Optimized query approach |

### Transaction Modes

| Mode | Description | Recommended |
|------|-------------|-------------|
| `SingleOperationTransaction` | Each queue operation in own transaction | **Yes** |
| `FullyTransactional` | Operations share parent transaction | No |

> ⚠️ **Warning:** `FullyTransactional` mode causes issues with retries and dead letter handling because the transaction is marked for rollback and retry counts are never increased.

## Polling Mechanisms

PostgreSQL Queue supports two polling approaches:

### CentralizedMessageFetcher (Default - Recommended)

Single polling thread fetches from all queues, distributes to worker threads:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(true)
    .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
    .build();
```

**Benefits:** Reduced database load, batch fetching, built-in ordering coordination

### DefaultDurableQueueConsumer (Traditional)

Individual polling threads per consumer:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(false)
    .build();
```

**Benefits:** Simpler architecture, independent consumer operation, fine-grained control, built-in ordering coordination

### Comparison

| Aspect | CentralizedMessageFetcher | DefaultDurableQueueConsumer |
|--------|---------------------------|----------------------------|
| **Database Load** | Low | Higher |
| **Scalability** | Excellent | Good |
| **Complexity** | Higher | Lower |
| **Fault Isolation** | Lower | Higher |

## Polling Optimization

### Why

Continuous polling at a fixed interval wastes database resources when queues are idle.  
Polling optimizers implement **adaptive backoff** — reducing poll frequency during quiet periods and resetting to aggressive polling when messages arrive.

> Optimization controls backoff per queue-name, such that queues with high activity will be polled more frequently, where as queues with low activity will be polled less frequently.

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
3. **Message added to queue** → Immediately reset to fast polling (notification via LISTEN/NOTIFY using `MultiTableChangeListener`)

### Optimizer Types

| Optimizer | Used With | Backoff Strategy |
|-----------|-----------|------------------|
| `SimpleQueuePollingOptimizer` | `DefaultDurableQueueConsumer` | Linear increment |
| `CentralizedQueuePollingOptimizer` | `CentralizedMessageFetcher` | Exponential with jitter |

### SimpleQueuePollingOptimizer (for DefaultDurableQueueConsumer)

Uses **linear backoff** — increases delay by a fixed increment each time no messages are found:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(false)  // Use traditional consumers
    .setQueuePollingOptimizerFactory(consumeFromQueue ->
        new SimpleQueuePollingOptimizer(
            consumeFromQueue,
            100,    // delayIncrementMs - add 100ms each empty poll
            5000    // maxDelayMs - cap at 5 seconds
        ))
    .build();
```

**Parameters:**  

| Parameter | Description |
|-----------|-------------|
| `delayIncrementMs` | Added to delay after each empty poll (e.g., 100ms) |
| `maxDelayMs` | Maximum delay cap (e.g., 5000ms = 5 seconds) |

**Behavior:** `delay = min(maxDelayMs, currentDelay + delayIncrementMs)`

### CentralizedQueuePollingOptimizer (for CentralizedMessageFetcher)

Uses **exponential backoff with jitter** — multiplies delay and adds randomization to prevent thundering herd:

```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(true)  // Default
    .setCentralizedQueuePollingOptimizerFactory(queueName ->
        new CentralizedQueuePollingOptimizer(
            queueName,
            100,    // initialDelayMs - start at 100ms
            30000,  // maxDelayMs - cap at 30 seconds
            2.0,    // backoffFactor - double each time
            0.1     // jitterFraction - ±10% randomization
        ))
    .build();
```

**Parameters:**
| Parameter | Description |
|-----------|-------------|
| `initialDelayMs` | Starting delay after finding messages (e.g., 100ms) |
| `maxDelayMs` | Maximum delay cap (e.g., 30000ms = 30 seconds) |
| `backoffFactor` | Multiplier for exponential growth (e.g., 2.0 = double) |
| `jitterFraction` | Random variance to prevent synchronized polling (e.g., 0.1 = ±10%) |

**Behavior:** `delay = min(maxDelayMs, currentDelay × backoffFactor) ± jitter`

### Immediate Wake-up via LISTEN/NOTIFY

For instant response when messages arrive (instead of waiting for next poll cycle), configure a `MultiTableChangeListener`:

```java
var multiTableChangeListener = new MultiTableChangeListener<>(
    jdbi,
    Duration.ofMillis(100),
    jsonSerializer
);

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMultiTableChangeListener(multiTableChangeListener)
    // ... optimizer config
    .build();
```

When a message is added to a queue, PostgreSQL NOTIFY triggers `messageAdded()` on the optimizer, immediately resetting the delay to zero.

## Table Schema

The table is created automatically:

```sql
CREATE TABLE IF NOT EXISTS durable_queues (
    id                      TEXT PRIMARY KEY,
    queue_name              TEXT NOT NULL,
    message_payload         JSONB NOT NULL,
    message_payload_type    TEXT NOT NULL,
    added_ts                TIMESTAMPTZ NOT NULL,
    next_delivery_ts        TIMESTAMPTZ NOT NULL,
    delivery_ts             TIMESTAMPTZ,
    total_attempts          INT DEFAULT 0,
    redelivery_attempts     INT DEFAULT 0,
    last_error              TEXT,
    is_being_delivered      BOOLEAN DEFAULT FALSE,
    is_dead_letter_message  BOOLEAN DEFAULT FALSE,
    meta_data               JSONB,
    delivery_mode           TEXT,
    key                     TEXT,
    key_order               BIGINT
);
```

## Usage

See [Foundation - DurableQueues](../foundation/README.md#durablequeues-messaging) for complete usage patterns including:
- Queueing messages
- Consumer registration
- Pattern matching handlers
- Redelivery policies
- Dead letter handling
- Ordered Message Delivery

## Comparison with MongoDB Implementation

| Aspect | PostgreSQL | MongoDB |
|--------|-----------|---------|
| **Module** | `postgresql-queue` | [`springdata-mongo-queue`](../springdata-mongo-queue/README.md) |
| **Storage** | SQL table with JSONB | MongoDB collection |
| **Transactions** | JDBI/JDBC | Spring Data MongoDB |
| **Notifications** | LISTEN/NOTIFY | Change Streams |
| **Locking** | `FOR UPDATE SKIP LOCKED` | `findAndModify()` |
