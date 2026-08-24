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
- [Ordered Message Duplicates](#ordered-message-duplicates)
- [Two-Table Split](#two-table-split-opt-in)
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
| `PostgresqlSplitDurableQueues` | Ordered and unordered messages in separate tables, so each carries only the indexes it needs. Opt-in; configured via `PostgresqlSplitDurableQueuesSettings` / `.builder()` |
| `PostgresqlDurableQueuesStatistics` | **Deprecated, for removal, wired by nothing** - collected via an `AFTER DELETE` trigger on the queue table (2.80x on acknowledgement throughput). Use `InMemoryDurableQueuesStatistics` (foundation) |
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
| `useOrderedUnorderedQuery` | `boolean` | `true` | Separate ordered/unordered claim queries. `false` uses one unified query, measured **5.4x slower** on a backlog mixing both kinds |
| `orderedMessageDuplicateStrategy` | `OrderedMessageDuplicateStrategy` | `REJECT` | `REJECT` adds a unique index on `(queue_name, key, key_order) WHERE key IS NOT NULL`. **Startup fails on a table that already contains duplicates** - see [Ordered message duplicates](#ordered-message-duplicates) |
| `messageObserver` | `DurableQueueMessageObserver` | `none()` | Notified of how each delivery ended. Pass `InMemoryDurableQueuesStatistics.observer()` to collect delivery statistics |
| `queuePollingOptimizerFactory` | `Function<ConsumeFromQueue,QueuePollingOptimizer>` | null | For `DefaultDurableQueueConsumer` |
| `centralizedQueuePollingOptimizerFactory` | `Function<QueueName,QueuePollingOptimizer>` | null | For `CentralizedMessageFetcher` |
| `multiTableChangeListener` | `MultiTableChangeListener` | null | LISTEN/NOTIFY support |
| `useBatchedFetch` | `boolean` | `false` | One claim statement across all active queues instead of one per queue. Competing consumers verified; throughput unmeasured |
| `batchedFetchSwitchThreshold` | `int` | 4 | Per-queue fetch for active-queue counts ≤ threshold, batched above it |
| `useBatchedAcknowledgement` | `boolean` | `false` | Coalesce acks into one statement+transaction per batch. See [Batched Acknowledgement](#batched-acknowledgement) |
| `acknowledgementMaxBatchSize` | `int` | 64 | Flush once this many acks are pending |
| `acknowledgementFlushInterval` | `Duration` | 50ms | Flush at least this often. Must be ≤ ¼ of `messageHandlingTimeout` |

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

## Batched Acknowledgement

The acknowledgement is the dominant per-message cost, and the cost is the **transaction**, not the `DELETE`.
Measured: one transaction per ack is **16.5× more expensive** on drain time than one per batch
[10.3–24.2× across 9 repetitions]; two transactions per message rather than per batch costs 134×
(`docs/durable-queues-redesign-measurements.md` §7).

```java
PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setUseBatchedAcknowledgement(true)
    .setAcknowledgementMaxBatchSize(64)
    .setAcknowledgementFlushInterval(Duration.ofMillis(50))
    .build();
```

⚠️ **Constraints — all three are enforced, not advisory:**

| Constraint | Why |
|---|---|
| `OrderedMessage` is never buffered | The per-key barrier reads completion from the *absence* of a lower `key_order` row, so a buffered ack stalls the key. Measured 0.82× — worse than not batching. Acknowledged immediately regardless of the setting |
| Requires `SingleOperationTransaction` | The buffer relies on `resetMessagesStuckBeingDelivered` to recover acks lost before a flush; `FullyTransactional` has no such recovery. Construction fails |
| `acknowledgementFlushInterval` ≤ ¼ × `messageHandlingTimeout` | Otherwise the stuck-message reset resurrects messages whose ack is merely buffered → duplicate delivery. Constructor throws |

**Semantics**: at-least-once is unchanged, but the redelivery window widens by up to one flush interval — a
crash in that window redelivers messages that were in fact handled. Handlers must be idempotent (they always
had to be). Off by default for exactly this reason.

**Interceptors**: batched acks go through `AcknowledgeMessagesAsHandled`, not `AcknowledgeMessageAsHandled`.
An interceptor that counts or times acks must implement **both** `intercept` overloads or it silently stops
seeing them.

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
-- Next message to deliver
CREATE INDEX idx_*_next_msg
  ON durable_queues (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts);

-- Ready messages (general)
CREATE INDEX idx_*_ready
  ON durable_queues (queue_name, next_delivery_ts, key, key_order)
  WHERE is_dead_letter_message = FALSE AND is_being_delivered = FALSE;

-- Unordered messages ready
CREATE INDEX idx_*_unordered_ready
  ON durable_queues (queue_name, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NULL AND NOT is_dead_letter_message AND NOT is_being_delivered;

-- Ordered message head
CREATE INDEX idx_*_ordered_head
  ON durable_queues (queue_name, key_order, next_delivery_ts)
  INCLUDE (id)
  WHERE key IS NOT NULL AND is_dead_letter_message = FALSE AND is_being_delivered = FALSE;

-- Ordered per-key, unique under orderedMessageDuplicateStrategy = REJECT (the default)
CREATE UNIQUE INDEX idx_*_ordered_unique
  ON durable_queues (queue_name, key, key_order)
  WHERE key IS NOT NULL;

-- Under ALLOW, a non-unique equivalent is created instead, since no unique index exists to serve the barrier
CREATE INDEX idx_*_ordered_msg
  ON durable_queues (queue_name, key, key_order);
```

**Two indexes were removed** on measured evidence (`PostgresqlIndexUsageIT`, net −28% index bytes):
`idx_*_ordered_ready` took zero scans at both 8 and 200 ordered keys, and `idx_*_ordered_msg` is superseded by the
unique index under `REJECT`. Existing deployments have them dropped on startup.

⚠️ **The schema auto-migrates on startup** — indexes are dropped and recreated to match the current set, which is
not zero-downtime safe across versions when index names change.

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

### Delivery Statistics

Package: `dk.trustworks.essentials.components.foundation.messaging.queue.stats`

Collected in memory from a `DurableQueueMessageObserver`, so nothing is written on the acknowledgement path and
enabling them creates no table. **The queue does not own the statistics object** — you create it and hand the queue
its observer, which is what makes this a configuration change rather than a schema migration:

```java
import dk.trustworks.essentials.components.foundation.messaging.queue.stats.*;

var statistics = new InMemoryDurableQueuesStatistics();
var durableQueues = PostgresqlDurableQueues.builder()
                                           .setUnitOfWorkFactory(unitOfWorkFactory)
                                           .setJsonSerializer(jsonSerializer)
                                           .setMessageObserver(statistics.observer())   // <- the wiring
                                           .build();

// Queue-level aggregate
statistics.getQueueStatistics(queueName).ifPresent(s ->
    log.info("Delivered: {}, avg latency: {} ms, last delivery: {}",
             s.totalMessagesDelivered(), s.avgDeliveryLatencyMs(), s.lastDelivery()));

// One message, best-effort: answers for a message this instance recently finished with
Optional<QueuedStatisticsMessage> messageStatistics = statistics.getQueueStatisticsMessage(queueEntryId);
```

Under Spring, set `essentials.durable-queues.enable-queue-statistics=true` and the starter does the wiring.

⚠️ **Per instance, and since startup.** Each instance counts only the deliveries it performed, and a restart
resets them — so a low number is not a slow queue and a zero is not a stall. Nothing is persisted. For
cluster-wide or historical answers, aggregate the Micrometer meters. `purgeQueue` and `deleteMessage` produce no
statistics: they are administrative operations, not deliveries.

Custom observers are the extension point — implement `DurableQueueMessageObserver` and combine with
`DurableQueueMessageObserver.composite(List.of(...))`. Exceptions thrown by an observer never reach the delivery
path.

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

## Ordered Message Duplicates

Two `OrderedMessage`s sharing a key **and** a `key_order` never block each other — the per-key barrier blocks only
on a *strictly* lower order — so that key's ordering guarantee silently does not hold. `orderedMessageDuplicateStrategy`
defaults to `REJECT`, which adds a unique index that makes the second enqueue fail instead.

```java
// REJECT (default) - a duplicate key+order is refused, which doubles as an idempotent enqueue
durableQueues.queueMessage(queueName, OrderedMessage.of("first",  "key-a", 0L));   // ok
durableQueues.queueMessage(queueName, OrderedMessage.of("second", "key-a", 0L));   // throws
durableQueues.queueMessage(queueName, OrderedMessage.of("third",  "key-a", 1L));   // ok - different order
```

Safe as a default because every ordered message the framework produces keys on the aggregate id and orders by
`EventOrder`, unique within its stream. The exposure is application code deriving the order from something that is
not unique.

⚠️ **Startup fails on a table that already contains duplicates**, naming the offending key — `CREATE UNIQUE INDEX`
cannot succeed against them, and carrying on would leave the deployment believing ordering is protected when it is
not. Either resolve them, or opt out with `.setOrderedMessageDuplicateStrategy(OrderedMessageDuplicateStrategy.ALLOW)`.
Unordered messages are unaffected: the index is partial on `key IS NOT NULL`.

## Two-Table Split (opt-in)

`PostgresqlSplitDurableQueues` stores ordered and unordered messages in separate tables (`<base>_unordered` /
`<base>_ordered`), so each carries only the indexes its own access pattern needs.

Measured through the component at 40 000 messages: unordered traffic is **1.07× overall** — insert 1.34–1.60×,
drain at parity, 8–9% fewer index bytes. The **1.38×/1.62×** quoted historically came from raw-SQL prototype
schemas and never described this implementation. **Ordered traffic is unmeasured** — repeat runs of the same
configuration differ by 4.75×, so no figure is quoted.

```java
var durableQueues = PostgresqlSplitDurableQueues.builder()
                                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                                .setJsonSerializer(jsonSerializer)
                                                .setBaseQueueTableName("durable_queues")
                                                .setMultiTableChangeListener(listener)     // optional, for wake-ups
                                                .build();
```

Transparent through the `DurableQueues` API — routing is by message type, and the shared cross-implementation test
suite passes against it unmodified. Notes:

- **No Spring property enables it yet**; the starter always builds `PostgresqlDurableQueues`. Hand-wire it.
- **Deep paging costs more**: a page cannot push its offset into either table, so page *n* reads *n* × `pageSize`
  rows from each. Fine for admin browsing.
- **Migration is not automatic** — it is a different physical layout, so moving to it means draining the existing
  table first.

## Gotchas

| Issue | Wrong | Right |
|-------|-------|-------|
| FullyTransactional breaks retries | `.setTransactionMode(TransactionMode.FullyTransactional)` | `.setTransactionMode(TransactionMode.SingleOperationTransaction)` |
| Ack-counting interceptor goes blind when batching acks | implementing only `intercept(AcknowledgeMessageAsHandled…)` | implement `intercept(AcknowledgeMessagesAsHandled…)` too |
| Statistics assumed cluster-wide | reading `totalMessagesDelivered` as the queue's throughput | it is this instance's, since its startup — aggregate Micrometer for a cluster figure |
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
