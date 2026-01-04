# PostgreSQL Queue - LLM Reference

> **Foundation**: [LLM-foundation.md](./LLM-foundation.md#durablequeues-api) | **Developer docs**: [README](../components/postgresql-queue/README.md)

## TOC
- [Quick Facts](#quick-facts)
- [Configuration](#configuration)
- [Transaction Modes](#transaction-modes)
- [Database Schema](#database-schema)
- [Polling Mechanisms](#polling-mechanisms)
- [Polling Optimization](#polling-optimization)
- ⚠️ [Security](#security)
- [Common Pitfalls](#common-pitfalls)
- [Performance Tuning](#performance-tuning)
- [Monitoring](#monitoring)
- [Integration](#integration)
- [PostgreSQL vs MongoDB](#postgresql-vs-mongodb)
- [Test Utilities](#test-utilities)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.postgresql.queue`
- **Implementation**: `PostgresqlDurableQueues`
- **Storage**: PostgreSQL table with JSONB
- **Locking**: `FOR UPDATE SKIP LOCKED`
- **Notifications**: LISTEN/NOTIFY via `MultiTableChangeListener`
- **Dependencies**: JDBI, PostgreSQL, Jackson (all `provided`), foundation module
- **Status**: WORK-IN-PROGRESS

## Configuration

### Builder Options

For `PostgresqlDurableQueuesBuilder` that can be created via `PostgresqlDurableQueues.builder()`.

| Option | Type | Default | Notes |
|--------|------|---------|-------|
| `unitOfWorkFactory` | `HandleAwareUnitOfWorkFactory` | **Required** | JDBI transaction factory |
| `jsonSerializer` | `JSONSerializer` | Jackson | Message serialization |
| `sharedQueueTableName` | `String` | `durable_queues` | ⚠️ SQL injection risk |
| `transactionMode` | `TransactionMode` | `SingleOperationTransaction` | See [Transaction Modes](#transaction-modes) |
| `useCentralizedMessageFetcher` | `boolean` | `true` | Centralized vs per-consumer |
| `centralizedMessageFetcherPollingInterval` | `Duration` | 20ms | Polling interval |
| `useOrderedUnorderedQuery` | `boolean` | `false` | Query optimization |
| `queuePollingOptimizerFactory` | `Function` | null | For `DefaultDurableQueueConsumer` |
| `centralizedQueuePollingOptimizerFactory` | `Function` | null | For `CentralizedMessageFetcher` |
| `multiTableChangeListener` | `MultiTableChangeListener` | null | LISTEN/NOTIFY |

### Basic Setup
```java
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
    .build();
durableQueues.start();
```

### Spring Integration
```java
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

## Transaction Modes

| Mode | Behavior | Recommendation |
|------|----------|----------------|
| `SingleOperationTransaction` | Each op in own transaction | ✅ **Use this** - proper retry/DLQ |
| `FullyTransactional` | Join parent transaction | ❌ **Avoid** - breaks retry counts |

⚠️ **FullyTransactional breaks retry handling**: Transaction rollback prevents retry count updates and DLQ persistence.

## Database Schema

```sql
CREATE TABLE durable_queues (
    id                      TEXT PRIMARY KEY, -- QueueEntryId
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

    -- OrderedMessage only:
    delivery_mode           TEXT,           -- "NORMAL" | "IN_ORDER"
    key                     TEXT,           -- OrderedMessage
    key_order               BIGINT          -- OrderedMessage sequence
);
```

**Query pattern**: Uses `FOR UPDATE SKIP LOCKED` for lock-free concurrent access.

### Indexes
Auto-created on start (`*` = durable queues table name):

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

-- Ordered messages ready for delivery (optimized with useOrderedUnorderedQuery)
CREATE INDEX idx_*_ordered_ready
  ON durable_queues (key, queue_name, key_order, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NOT NULL AND NOT is_dead_letter_message AND NOT is_being_delivered;

-- Unordered messages ready for delivery (optimized with useOrderedUnorderedQuery)
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

## Polling Mechanisms

### CentralizedMessageFetcher (Default)
Single thread polls all queues, distributes to workers.

- **Pros**: Low DB load, batch ops, ordering support
- **Cons**: Single point of failure

```java
.setUseCentralizedMessageFetcher(true)
.setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
```

### DefaultDurableQueueConsumer
Per-consumer polling threads.

- **Pros**: Simpler, fault isolation
- **Cons**: Higher DB load

```java
.setUseCentralizedMessageFetcher(false)
```

## Polling Optimization

### SimpleQueuePollingOptimizer
Linear backoff for `DefaultDurableQueueConsumer`.

**Algorithm**: `delay += increment` on empty poll, reset on message/notification.

```java
.setUseCentralizedMessageFetcher(false)
.setMultiTableChangeListener(multiTableChangeListener)  // Required
.setQueuePollingOptimizerFactory(consumeFromQueue ->
    new SimpleQueuePollingOptimizer(consumeFromQueue, 100, 5000))
```

| Param | Description |
|-------|-------------|
| `delayIncrementMs` | Added per empty poll (100ms) |
| `maxDelayMs` | Cap (5000ms) |

### CentralizedQueuePollingOptimizer
Exponential backoff with jitter for `CentralizedMessageFetcher`.

**Algorithm**: `delay = min(maxDelay, delay * factor) ± jitter`.

```java
.setUseCentralizedMessageFetcher(true)
.setMultiTableChangeListener(multiTableChangeListener)  // Required
.setCentralizedQueuePollingOptimizerFactory(queueName ->
    new CentralizedQueuePollingOptimizer(queueName, 100, 30000, 2.0, 0.1))
```

| Param | Description |
|-------|-------------|
| `initialDelayMs` | Start delay (100ms) |
| `maxDelayMs` | Cap (30000ms) |
| `backoffFactor` | Multiplier (2.0) |
| `jitterFraction` | Variance (±10%) |

### LISTEN/NOTIFY
PostgreSQL NOTIFY triggers immediate polling.

```java
var multiTableChangeListener = new MultiTableChangeListener<>(
    jdbi, Duration.ofMillis(100), jsonSerializer);

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMultiTableChangeListener(multiTableChangeListener)
    .build();
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

### SQL Injection Risk
`sharedQueueTableName` used in string concatenation.

```java
// ❌ DANGEROUS
.setSharedQueueTableName(userInput)

// ✅ SAFE
.setSharedQueueTableName("message_queue")  // Hardcoded only

// ⚠️ Validate if from config
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Basic validation (not exhaustive)
```

## Common Pitfalls

**1. FullyTransactional breaks retries**
```java
// ❌ .setTransactionMode(TransactionMode.FullyTransactional)
// ✅ .setTransactionMode(TransactionMode.SingleOperationTransaction)
```

**2. SQL injection via table name**
```java
// ❌ .setSharedQueueTableName(request.getParameter("table"))
// ✅ .setSharedQueueTableName("message_queue")
```

**3. Optimizer without listener**
```java
// ❌ .setQueuePollingOptimizerFactory(...)  // No listener
// ✅ .setMultiTableChangeListener(...).setQueuePollingOptimizerFactory(...)
```

**4. Aggressive polling without optimization**
```java
// ❌ .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(1))
// ✅ Add optimizer + reasonable interval
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
var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .addInterceptor(new DurableQueuesMicrometerInterceptor(meterRegistry, "MyService"))
    .addInterceptor(new DurableQueuesMicrometerTracingInterceptor(tracer, propagator, registry))
    .addInterceptor(new RecordExecutionTimeDurableQueueInterceptor(meterRegistry, "MyService"))
    .build();
```

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue.micrometer`
- `DurableQueuesMicrometerInterceptor` - Queue size gauges, counters (processed, handled, retries, DLQ)
- `DurableQueuesMicrometerTracingInterceptor` - Distributed tracing via Micrometer Observation

**Package**: `dk.trustworks.essentials.components.foundation.interceptor.micrometer`
- `RecordExecutionTimeDurableQueueInterceptor` - Operation execution time

### Statistics API (PostgreSQL-specific)
PostgreSQL implementation supports `DurableQueuesStatistics`:

```java
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

**Package**: `dk.trustworks.essentials.components.foundation.messaging.queue.stats`

### Logging
Configure loggers for queue operations:

| Logger Name | Purpose |
|-------------|---------|
| `dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues` | PostgreSQL queue implementation |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer` | Consumer operations |
| `dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures` | Message handling failures |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher` | Centralized polling (when enabled) |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcherDurableQueueConsumer` | Centralized consumer operations |
| `dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedQueuePollingOptimizer` | Exponential backoff optimizer |
| `dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer` | Linear backoff optimizer |

```yaml
# Logback/Spring Boot
logging.level:
  dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures: WARN
  dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher: DEBUG
  dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcherDurableQueueConsumer: DEBUG
```

```xml
<!-- Logback.xml -->
<logger name="dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer.MessageHandlingFailures" level="WARN"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher" level="DEBUG"/>
<logger name="dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcherDurableQueueConsumer" level="DEBUG"/>
```

### SQL Queries (Advanced)
Direct SQL for custom metrics:

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

-- Stuck messages (being delivered for too long)
SELECT queue_name, COUNT(*) as stuck_count
FROM durable_queues
WHERE is_being_delivered = true
  AND delivery_ts < NOW() - INTERVAL '5 minutes'
GROUP BY queue_name;
```

## Integration

### Spring Boot Starter
See [LLM-spring-boot-starter-modules.md](./LLM-spring-boot-starter-modules.md).

```yaml
essentials.postgresql:
  queue-table-name: message_queue
  use-centralized-fetcher: true
  polling-interval: 20ms
```

### Related Modules
- **[foundation](./LLM-foundation.md#durablequeues-messaging)** - `DurableQueues` interface
- **[springdata-mongo-queue](./LLM-springdata-mongo-queue.md)** - MongoDB implementation
- **[types-jdbi](./LLM-types-jdbi.md)** - JDBI argument factories
- **[types-jackson](./LLM-types-jackson.md)** - JSON serialization
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
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

@Bean
public DurableQueues testDurableQueues(Jdbi jdbi) {
    return PostgresqlDurableQueues.builder()
        .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
        .setSharedQueueTableName("test_queue")
        .build();
}
```
