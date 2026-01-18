# PostgreSQL Queue - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/postgresql-queue/README.md). For DurableQueues API patterns, see [LLM-foundation.md](./LLM-foundation.md#durablequeues-messaging).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.queue.postgresql`
- **Implementation**: `PostgresqlDurableQueues` implements `DurableQueues`
- **Storage**: PostgreSQL table with JSONB payloads
- **Locking**: `FOR UPDATE SKIP LOCKED`
- **Notifications**: LISTEN/NOTIFY via `MultiTableChangeListener`
- **Dependencies**: JDBI, PostgreSQL, Jackson (all `provided`), foundation module
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-queue</artifactId>
</dependency>
```

## TOC
- [Core API](#core-api)
- [Configuration](#configuration)
- [Transaction Modes](#transaction-modes)
- [Polling Mechanisms](#polling-mechanisms)
- [Polling Optimization](#polling-optimization)
- [Database Schema](#database-schema)
- [Monitoring](#monitoring)
- [Performance Tuning](#performance-tuning)
- ⚠️ [Security](#security)
- [Gotchas](#gotchas)

## Core API

Base package: `dk.trustworks.essentials.components.queue.postgresql`

**Dependencies from other modules**:
- `DurableQueues`, `QueueName`, `ConsumeFromQueue`, `RedeliveryPolicy` from [foundation](./LLM-foundation.md)
- `HandleAwareUnitOfWorkFactory` from [foundation](./LLM-foundation.md)

| Class | Purpose |
|-------|---------|
| `PostgresqlDurableQueues` | Main implementation |
| `PostgresqlDurableQueuesBuilder` | Builder via `PostgresqlDurableQueues.builder()` |
| `PostgresqlDurableQueuesStatistics` | Extended statistics API |
| `PostgresqlDurableQueueConsumer` | Traditional per-consumer polling |

Foundation classes (package: `dk.trustworks.essentials.components.foundation.messaging.queue`):

| Class | Purpose |
|-------|---------|
| `CentralizedMessageFetcher` | Single-thread polling across queues |
| `DefaultDurableQueueConsumer` | Per-consumer polling threads |
| `SimpleQueuePollingOptimizer` | Linear backoff for traditional consumers |
| `CentralizedQueuePollingOptimizer` | Exponential backoff with jitter |
| `MultiTableChangeListener` | PostgreSQL LISTEN/NOTIFY support |

## Configuration

### Basic Setup

```java
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
    .build();
durableQueues.start();
```

### Spring Integration

```java
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionMode;

@Bean
public SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory(
        Jdbi jdbi, DataSourceTransactionManager transactionManager) {
    return new SpringTransactionAwareJdbiUnitOfWorkFactory(jdbi, transactionManager);
}

@Bean
public DurableQueues durableQueues(HandleAwareUnitOfWorkFactory unitOfWorkFactory) {
    return PostgresqlDurableQueues.builder()
        .setUnitOfWorkFactory(unitOfWorkFactory)
        .setTransactionMode(TransactionMode.SingleOperationTransaction)
        .build();
}
```

### Builder Options

Created via `PostgresqlDurableQueues.builder()`.

| Option | Type | Default | Notes |
|--------|------|---------|-------|
| `unitOfWorkFactory` | `HandleAwareUnitOfWorkFactory` | **Required** | JDBI transaction factory |
| `jsonSerializer` | `JSONSerializer` | Jackson | Message serialization |
| `sharedQueueTableName` | `String` | `durable_queues` | ⚠️ SQL injection risk - validate! |
| `transactionMode` | `TransactionMode` | `SingleOperationTransaction` | See [Transaction Modes](#transaction-modes) |
| `useCentralizedMessageFetcher` | `boolean` | `true` | Centralized vs per-consumer |
| `centralizedMessageFetcherPollingInterval` | `Duration` | 20ms | Polling interval |
| `useOrderedUnorderedQuery` | `boolean` | `false` | Query optimization |
| `queuePollingOptimizerFactory` | `Function<ConsumeFromQueue,QueuePollingOptimizer>` | null | For `DefaultDurableQueueConsumer` |
| `centralizedQueuePollingOptimizerFactory` | `Function<QueueName,QueuePollingOptimizer>` | null | For `CentralizedMessageFetcher` |
| `multiTableChangeListener` | `MultiTableChangeListener` | null | LISTEN/NOTIFY support |

## Transaction Modes

| Mode | Behavior | Retries | DLQ | Recommended |
|------|----------|---------|-----|-------------|
| `SingleOperationTransaction` | Each op in own tx | ✅ Works | ✅ Works | ✅ **Use this** |
| `FullyTransactional` | Join parent tx | ❌ Broken | ❌ Broken | ❌ Avoid |

⚠️ **FullyTransactional breaks retry handling**: Transaction rollback prevents retry count updates and DLQ persistence.

## Polling Mechanisms

### CentralizedMessageFetcher (Default)

Single polling thread fetches from all queues, distributes to workers.

**Pros**: Low DB load, batch ops, ordering support
**Cons**: Single point of failure

```java
.setUseCentralizedMessageFetcher(true)
.setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
```

### DefaultDurableQueueConsumer (Traditional)

Per-consumer polling threads.

**Pros**: Simpler, fault isolation
**Cons**: Higher DB load

```java
.setUseCentralizedMessageFetcher(false)
```

### Comparison

| Aspect | CentralizedMessageFetcher | DefaultDurableQueueConsumer |
|--------|---------------------------|----------------------------|
| DB Load | Low | Higher |
| Scalability | Excellent | Good |
| Complexity | Higher | Lower |
| Fault Isolation | Lower | Higher |

## Polling Optimization

### Why Optimize

Continuous polling at fixed intervals wastes DB resources when queues are idle. Optimizers implement adaptive backoff - reducing poll frequency during quiet periods, resetting to aggressive polling when messages arrive.

### How It Works

1. **Message found** → Reset to initial (fast) polling interval
2. **No message found** → Increase delay using backoff strategy
3. **Message added** → LISTEN/NOTIFY immediately resets to fast polling (requires `MultiTableChangeListener`)

### SimpleQueuePollingOptimizer

**Used with**: `DefaultDurableQueueConsumer`
**Strategy**: Linear backoff (`delay += increment`)

```java
import dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer;

.setUseCentralizedMessageFetcher(false)
.setMultiTableChangeListener(multiTableChangeListener)  // Required
.setQueuePollingOptimizerFactory(consumeFromQueue ->
    new SimpleQueuePollingOptimizer(
        consumeFromQueue,
        100,    // delayIncrementMs - add 100ms per empty poll
        5000    // maxDelayMs - cap at 5s
    ))
```

| Param | Description |
|-------|-------------|
| `delayIncrementMs` | Added per empty poll (e.g., 100ms) |
| `maxDelayMs` | Cap (e.g., 5000ms) |

**Algorithm**: `delay = min(maxDelay, delay + increment)`

### CentralizedQueuePollingOptimizer

**Used with**: `CentralizedMessageFetcher`
**Strategy**: Exponential backoff with jitter (`delay = min(max, delay × factor) ± jitter`)

```java
import dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedQueuePollingOptimizer;

.setUseCentralizedMessageFetcher(true)
.setMultiTableChangeListener(multiTableChangeListener)  // Required
.setCentralizedQueuePollingOptimizerFactory(queueName ->
    new CentralizedQueuePollingOptimizer(
        queueName,
        100,    // initialDelayMs - start at 100ms
        30000,  // maxDelayMs - cap at 30s
        2.0,    // backoffFactor - double each time
        0.1     // jitterFraction - ±10% randomization
    ))
```

| Param | Description |
|-------|-------------|
| `initialDelayMs` | Start delay (e.g., 100ms) |
| `maxDelayMs` | Cap (e.g., 30000ms) |
| `backoffFactor` | Multiplier (e.g., 2.0 = double) |
| `jitterFraction` | Variance (e.g., 0.1 = ±10%) |

**Algorithm**: `delay = min(maxDelay, delay × factor) ± jitter`

### LISTEN/NOTIFY Setup

PostgreSQL NOTIFY triggers immediate polling when messages arrive.

```java
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;

var multiTableChangeListener = new MultiTableChangeListener<>(
    jdbi,
    Duration.ofMillis(100),
    jsonSerializer
);

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMultiTableChangeListener(multiTableChangeListener)
    .build();
```

## Database Schema

Auto-created on start.

```sql
CREATE TABLE durable_queues (
    id                      TEXT PRIMARY KEY,        -- QueueEntryId
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

    -- OrderedMessage only
    delivery_mode           TEXT,                    -- "NORMAL" | "IN_ORDER"
    key                     TEXT,                    -- OrderedMessage key
    key_order               BIGINT                   -- OrderedMessage sequence
);
```

### Indexes

Auto-created. `*` = table name.

```sql
-- Ordered message lookup
CREATE INDEX idx_*_ordered_msg
  ON durable_queues (queue_name, key, key_order);

-- Next message to deliver
CREATE INDEX idx_*_next_msg
  ON durable_queues (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts);

-- Ready messages (general)
CREATE INDEX idx_*_ready
  ON durable_queues (queue_name, next_delivery_ts, key, key_order)
  WHERE is_dead_letter_message = FALSE AND is_being_delivered = FALSE;

-- Ordered messages ready (when useOrderedUnorderedQuery=true)
CREATE INDEX idx_*_ordered_ready
  ON durable_queues (key, queue_name, key_order, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NOT NULL AND NOT is_dead_letter_message AND NOT is_being_delivered;

-- Unordered messages ready (when useOrderedUnorderedQuery=true)
CREATE INDEX idx_*_unordered_ready
  ON durable_queues (queue_name, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NULL AND NOT is_dead_letter_message AND NOT is_being_delivered;

-- Ordered message head (for ordered processing)
CREATE INDEX idx_*_ordered_head
  ON durable_queues (queue_name, key_order, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NOT NULL AND is_dead_letter_message = FALSE AND is_being_delivered = FALSE;
```

**Query pattern**: `FOR UPDATE SKIP LOCKED` for lock-free concurrent access.

## Monitoring

### Standard DurableQueues API

Package: `dk.trustworks.essentials.components.foundation.messaging.queue`

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

Package: `dk.trustworks.essentials.components.foundation.messaging.queue.micrometer` and `.foundation.interceptor.micrometer`

```java
import dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.*;
import dk.trustworks.essentials.components.foundation.interceptor.micrometer.RecordExecutionTimeDurableQueueInterceptor;

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "MyService"))
    .addInterceptor(new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry))
    .addInterceptor(new RecordExecutionTimeDurableQueueInterceptor(meterRegistry, "MyService"))
    .build();
```

| Interceptor | Metrics |
|-------------|---------|
| `DurableQueuesMicrometerInterceptor` | Queue size gauges, counters (processed, handled, retries, DLQ) |
| `DurableQueuesMicrometerTracingInterceptor` | Distributed tracing via Micrometer Observation |
| `RecordExecutionTimeDurableQueueInterceptor` | Operation execution time |

### PostgreSQL-Specific Statistics

Package: `dk.trustworks.essentials.components.foundation.messaging.queue.stats`

```java
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.messaging.queue.stats.*;

PostgresqlDurableQueues queues = (PostgresqlDurableQueues) durableQueues;
DurableQueuesStatistics stats = queues.getStatistics();

// Queue statistics
Optional<QueueStatistics> queueStats = stats.getQueueStatistics(queueName);
queueStats.ifPresent(s -> {
    log.info("Total: {}, DLQ: {}, Earliest: {}",
        s.getTotalMessages(), s.getDeadLetterMessages(), s.getEarliestMessageTimestamp());
});

// Individual message statistics
Optional<QueuedStatisticsMessage> msgStats = stats.getQueueStatisticsMessage(queueEntryId);
```

### Logging

| Logger | Purpose |
|--------|---------|
| `dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues` | PostgreSQL queue ops |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer` | Consumer ops |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures` | Message failures |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher` | Centralized polling |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcherDurableQueueConsumer` | Centralized consumer |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedQueuePollingOptimizer` | Exponential backoff |
| `dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer` | Linear backoff |

```yaml
# Logback/Spring Boot
logging.level:
  dk.trustworks.essentials.components.queue.postgresql: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures: WARN
  dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher: DEBUG
```

### SQL Queries (Custom Metrics)

```sql
-- Dead letter counts by queue
SELECT queue_name, COUNT(*) as dead_letter_count,
       MAX(added_ts) as latest_dead_letter
FROM durable_queues
WHERE is_dead_letter_message = true
GROUP BY queue_name;

-- Queue depth
SELECT queue_name, COUNT(*) as pending_count,
       MIN(next_delivery_ts) as earliest_delivery
FROM durable_queues
WHERE is_being_delivered = false AND is_dead_letter_message = false
GROUP BY queue_name;

-- Stuck messages (being delivered too long)
SELECT queue_name, COUNT(*) as stuck_count
FROM durable_queues
WHERE is_being_delivered = true
  AND delivery_ts < NOW() - INTERVAL '5 minutes'
GROUP BY queue_name;
```

## Performance Tuning

### High-Throughput

```java
PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(true)
    .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(5))
    .setUseOrderedUnorderedQuery(true)
    .setMultiTableChangeListener(multiTableChangeListener)
    .setCentralizedQueuePollingOptimizerFactory(queueName ->
        new CentralizedQueuePollingOptimizer(queueName, 5, 10000, 1.5, 0.1))
    .build();
```

### Low-Latency

```java
PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseCentralizedMessageFetcher(true)
    .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(5))
    .setMultiTableChangeListener(multiTableChangeListener)
    .build();
```

## Security

### ⚠️ Critical: SQL Injection Risk

`sharedQueueTableName` used in SQL via string concatenation → SQL injection risk.

While `PostgresqlUtil.checkIsValidTableOrColumnName()` provides basic validation, this is **NOT exhaustive protection**.

**Safe usage**:

```java
// ✅ SAFE - hardcoded only
.setSharedQueueTableName("message_queue")

// ⚠️ Validate if from config
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Basic validation
.setSharedQueueTableName(tableName)

// ❌ DANGEROUS - never from untrusted input
.setSharedQueueTableName(userInput)
```

**Developer responsibility**:
- Only use values from controlled, trusted sources
- Never derive from external/untrusted input
- Validate all config values at startup

See [README Security](../components/postgresql-queue/README.md#security) for full details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Gotchas

| Issue | Wrong | Right |
|-------|-------|-------|
| FullyTransactional breaks retries | `.setTransactionMode(TransactionMode.FullyTransactional)` | `.setTransactionMode(TransactionMode.SingleOperationTransaction)` |
| SQL injection via table name | `.setSharedQueueTableName(request.getParameter("table"))` | `.setSharedQueueTableName("message_queue")` |
| Optimizer without listener | `.setQueuePollingOptimizerFactory(...)` alone | `.setMultiTableChangeListener(...).setQueuePollingOptimizerFactory(...)` |
| Aggressive polling without optimization | `.setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(1))` | Add optimizer + reasonable interval |

## Integration

### Spring Boot Starter

See [LLM-spring-boot-starter-modules.md](./LLM-spring-boot-starter-modules.md#spring-boot-starter-postgresql).

```yaml
essentials.postgresql:
  queue-table-name: message_queue
  use-centralized-fetcher: true
  polling-interval: 20ms
```

### Related Modules

| Module | Purpose |
|--------|---------|
| [foundation](./LLM-foundation.md#durablequeues-messaging) | `DurableQueues` interface and core patterns |
| [springdata-mongo-queue](./LLM-springdata-mongo-queue.md) | MongoDB implementation |
| [types-jdbi](./LLM-types-jdbi.md) | JDBI argument factories |
| [types-jackson](./LLM-types-jackson.md) | JSON serialization |

### PostgreSQL vs MongoDB

| Aspect | PostgreSQL | MongoDB |
|--------|-----------|---------|
| **Module** | `postgresql-queue` | `springdata-mongo-queue` |
| **Storage** | SQL table + JSONB | Collection + BSON |
| **Transactions** | JDBI/JDBC | Spring Data MongoDB |
| **Notifications** | LISTEN/NOTIFY | Change Streams |
| **Locking** | `FOR UPDATE SKIP LOCKED` | `findAndModify()` |
| **Polling** | Centralized + Linear/Exponential | Linear only |
| **Config** | Builder pattern | Constructor |

## Test Utilities

```java
import org.testcontainers.containers.PostgreSQLContainer;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;

@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

@Bean
public DurableQueues testDurableQueues(Jdbi jdbi) {
    return PostgresqlDurableQueues.builder()
        .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
        .setSharedQueueTableName("test_queue")
        .build();
}
```

## See Also

- [README.md](../components/postgresql-queue/README.md) - Full documentation with examples
- [LLM-foundation.md](./LLM-foundation.md#durablequeues-messaging) - DurableQueues API patterns
- [LLM-springdata-mongo-queue.md](./LLM-springdata-mongo-queue.md) - MongoDB implementation
