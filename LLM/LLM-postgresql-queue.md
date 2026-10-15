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
- [Transactions](#transactions)
- [Polling Mechanisms](#polling-mechanisms)
- [Polling Optimization](#polling-optimization)
- [Database Schema](#database-schema)
- [Dead-Letter Classification](#dead-letter-classification)
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
| `messageHandlingTimeout` | `Duration` | 30s | Stuck message timeout |
| `useCentralizedMessageFetcher` | `boolean` | `true` | Centralized vs per-consumer |
| `centralizedMessageFetcherPollingInterval` | `Duration` | 20ms | Polling interval |
| `queuePollingOptimizerFactory` | `Function<ConsumeFromQueue,QueuePollingOptimizer>` | null | For `DefaultDurableQueueConsumer` |
| `centralizedQueuePollingOptimizerFactory` | `Function<QueueName,QueuePollingOptimizer>` | `CentralizedQueuePollingOptimizer` (interval, ×1.5, 2 s cap) | For `CentralizedMessageFetcher`; only used with a listener |
| `multiTableChangeListener` | `MultiTableChangeListener` | created by `build()` | LISTEN/NOTIFY support |
| `createMultiTableChangeListener` | `boolean` | `true` | `false`: no listener and no backoff, as before 0.60.1 |

## Transactions

Queue operations run in their own transactions: queueing, fetching, retrying and dead-lettering are separate, so a
failing handler can never roll back its own retry count. (0.60 removed `TransactionalMode`; its `FullyTransactional`
mode broke exactly that.) A `queueMessage` called inside a caller's `UnitOfWork` joins it, so the enqueue commits or
rolls back with the caller's writes - which is what an Outbox relies on. With the starter's
`SpringTransactionAwareJdbiUnitOfWorkFactory` a plain Spring `@Transactional`/`TransactionTemplate` is joined too.

The one exception, since 0.60.1: a successfully handled message is acknowledged inside the handler's own transaction.
The handler's writes and the acknowledgement commit together or not at all, and a message costs one connection
checkout and one commit instead of two (measured on its own: about +10% drain throughput through a 10-connection
pool, without LISTEN/NOTIFY). The acknowledgement is the handler's last statement, but `beforeCommit` callbacks
(event-store appends, in-transaction handlers) still run after it, holding the queue row's lock until the commit.
If the acknowledgement or the commit fails, the handler's writes roll back and the failure is classified like a
handler exception: it uses a redelivery attempt and goes through the permanent-error list, so an
`IllegalArgumentException` thrown by a `DurableQueuesInterceptor` on `AcknowledgeMessageAsHandled` - which now runs
inside the handler's transaction - dead-letters the message on its first delivery. The acknowledgement stays
separate, as before, for a handler that requests redelivery, one that marks its unit of work rollback-only (its
message is still consumed), and a handler that owns its unit-of-work boundary - every `EventProcessor` handler,
whatever its `UnitOfWorkMode`, and an `Inbox` with a `UnitOfWorkBoundaryOwningMessageConsumer`, which between them
cover every `UnitOfWorkMode.NONE` handler. A handler that returns with the interrupt flag set is acknowledged in its
transaction only while its consumer is known to be started (it re-set the flag for a reason of its own). While its
consumer is stopping it may have given up part-way, so its unit of work is marked rollback-only - its writes are
discarded - and the consumer hands the message back for redelivery instead of acknowledging it; the rollback is not a
handler failure, so it is never classified or dead-lettered (the hand-back counts one redelivery attempt). When the operation does not know the consumer's state, its writes commit and the message is left to the
consumer unacknowledged. The rollback reaches only a handler run in the queue's own unit of work: a boundary-owning
handler keeps whatever it committed before returning and is still handed back, so the redelivery runs it again on top
of its own partial writes - it must be idempotent across a partial run. A crash before the commit still redelivers,
so handlers should stay idempotent.

An enqueue **outside** any `UnitOfWork` or Spring transaction commits on its own and is not undone by a later
rollback of the caller's work - e.g. `Outbox.sendMessage` called before the business transaction starts. The first
enqueue from a call site logs a WARN with its stack trace, for up to 4 call sites per innermost application frame and up to 32 such
frames per `DurableQueues`; past that budget enqueues are not logged, so a silent enqueue is not proof of a transaction. A call site is the
first three stack frames outside Essentials, the JDK, Reactor, Kotlin and Spring, so two callers of one application wrapper (a gateway
around `Outbox.sendMessage`) are two call sites, but they share that wrapper's budget of 4. Opt in to failing instead with
`setRequireActiveUnitOfWorkForEnqueue(true)` on the builder, or in Spring Boot
`essentials.durable-queues.require-active-unit-of-work-for-enqueue=true`: such an enqueue then throws
`NoActiveUnitOfWorkException` and queues nothing. A plain Spring `@Transactional`/`TransactionTemplate` counts as
active. `Inbox.addMessageReceived` opens a `UnitOfWork` itself and is unaffected; `DurableLocalCommandBus.sendAndDontWait`
outside a transaction is not. Dead-letter enqueues are never checked.

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
3. **Message added** → resets to fast polling. A message queued by this node resets its own consumer when the
   transaction commits, whether it is consumed through the centralized message fetcher or polls on its own
   (`useCentralizedMessageFetcher(false)`, since 0.60.1). A message queued by another node resets it through
   LISTEN/NOTIFY.

4. **Message due later** → a retry, delayed enqueue or delayed resurrection made by this node tells the optimizer
   when the message becomes deliverable, and the queue is polled then instead of sleeping past it (since 0.60.1;
   before, a retry on a backed-off queue could arrive a whole backoff cap late). A delayed message enqueued by
   another node still waits at most the cap.

**The builder is wired like the Spring Boot starter.** Since 0.60.1, `PostgresqlDurableQueues.builder()` creates a
`MultiTableChangeListener` when none is set (LISTEN/NOTIFY on the queue table, polled every 50 ms; it holds one
connection, is started and stopped with the queues, and is released by `close()`), and the default `CentralizedQueuePollingOptimizer` uses the
starter's values: start at the polling interval, ×1.5 per empty poll, ±10% jitter, capped at 2 s. A builder-made
instance therefore behaves like a Spring one. `.setCreateMultiTableChangeListener(false)` keeps the old wiring: no
LISTEN/NOTIFY and no backoff, every queue polled every interval - a queue only backs off where something can wake it.

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
.setMultiTableChangeListener(multiTableChangeListener)  // Optional: wakes the queue when another node enqueues
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

var multiTableChangeListener = MultiTableChangeListener.builder()
    .setJdbi(jdbi)
    .setJsonSerializer(jsonSerializer)
    .setEventBus(eventBus)
    .setPollingInterval(Duration.ofMillis(100))
    .build();

var durableQueues = PostgresqlDurableQueues.builder()
    .setUnitOfWorkFactory(unitOfWorkFactory)
    .setMultiTableChangeListener(multiTableChangeListener)
    .build();
```

## Database Schema

Auto-created on start - unless a schema harness owns it: `PostgresqlDurableQueuesBuilder.setSchemaOwnership(SchemaOwnership.HARNESS)`
(the Spring starter does it when `essentials.schema.mode` is not `create`), after which the queues are a schema
contributor (module `postgresql-queue`) - see [LLM-foundation.md](./LLM-foundation.md#database-schema-harness). The legacy index drops and the 0.50 queue-statistics
removal stay repeatable changes, so a 0.50 instance restarting mid-rollout cannot leave them behind.

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

-- Unordered messages ready
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

## Dead-Letter Classification

A failed delivery is either retried according to the `RedeliveryPolicy` or dead-lettered immediately. The
consumer asks the policy's `MessageDeliveryErrorHandler` first, then applies its own built-in list of permanent
error types. Two of the five can be overridden by an explicit `alwaysRetryOn(...)`; three cannot:

| Type | Overridable by `alwaysRetryOn` |
|---|---|
| `DurableQueueDeserializationException` | No |
| `MismatchedInputException` | No |
| `NoClassDefFoundError` | No |
| `IllegalArgumentException` (incl. `NumberFormatException`) | **Yes** |
| `ClassCastException` | **Yes** |

`IllegalArgumentException` on that list is the common surprise: `FailFast.requireNonNull(...)` /
`requireTrue(...)` and Kotlin's `require(...)` all throw it, so a `@MessageHandler` that guards its arguments
dead-letters its message on the first delivery attempt unless the policy opts out.

The whole cause chain is examined, not just the thrown exception and the deepest root cause. Overriding does
not lift `maximumNumberOfRedeliveries`.

See [LLM-foundation.md](./LLM-foundation.md) for `MessageDeliveryErrorHandler`, the opt-out, the
`RedeliveryPolicy` strategies and the recipe for validating inside a handler.

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

### Queue statistics

The trigger-based statistics table was removed in 0.60. Delivery figures now come from an in-memory
`QueueStatisticsRegistry` fed by a `DurableQueueMessageObserver`, joined with the cluster-wide queue depth at
the API layer:

```
GET /durable-queues/queues/{queueName}/statistics   ->  ApiQueueStatistics
```

| Half | Source | Scope |
|---|---|---|
| `depth` | the queue table, one statement | **cluster-wide** — queued, dead letters, in flight, oldest ready age |
| `instance` | `QueueStatisticsRegistry` | **this JVM only** — handled, retried, dead-lettered, handler durations, last failure. `null` when this instance has delivered nothing |

`depth.messagesBeingDelivered` and `depth.oldestReadyMessageAgeMillis` are what separate "nothing to do" from
"stalled": zero handled on this instance means nothing on its own. `PostgresqlDurableQueues` always reports
`messagesBeingDelivered`; the field is a nullable `Long` because an implementation that cannot count it cluster-wide
reports `null` — unknown, not zero.

See [LLM-foundation.md](./LLM-foundation.md) for the observer contract.

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

### Saturated queues are refilled, not held to the polling interval

The `CentralizedMessageFetcher` claims at most a queue's free worker slots per poll. Up to 0.60.0 it polled once per
interval, so a queue handled at most `parallelConsumers / pollingInterval` messages per second however fast its handler
was (400 msg/s for 8 consumers at 20 ms). Since 0.60.1, a queue whose poll filled every free slot is polled again as
soon as half its workers are free, so throughput is bounded by the handler and the database. Measured: 2 000 messages,
8 consumers, 200 ms polling drained in 3.3 s, against 50 s before.

### Stale statistics on a draining queue table

A queue table that drains to empty and is then analyzed (by autovacuum or by hand) records `reltuples = 0` while
keeping its pages. When the next burst arrives, the planner expects no rows. The claim statements, the opt-in batched
fetch's included, select the rows to update by primary-key array, so the unordered claim stays fast (4 ms instead of
185 ms at 20 000 queued messages; a batched poll over two such queues 111 ms instead of 37.6 s). Since 0.60.1 the batched fetch
also takes each queue's candidates under that queue's slot limit instead of sorting every queue's whole backlog. It
still reads a polled queue's ordered messages once per poll, because it hands out ordered heads by
`next_delivery_ts` and no index serves that order, so a large ordered backlog costs it more than the per-queue
claims. The ordered claim's per-key barrier can still be planned badly in that state. If ordered queues
regularly drain and refill, keep the table's statistics fresh with per-table autovacuum settings:

```sql
ALTER TABLE durable_queues SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 1000);
```

### Handlers that hold connections while waiting on something else

A handler runs inside a unit of work, and its transaction takes a pooled connection when it begins: in a Spring
application the transaction manager does that, not Essentials. A handler that spends most of its time on a slow
call to another system therefore holds a connection it is not using. With more consumers than connections, the other
handlers then queue on the pool. Two remedies, in order of preference:

- **`@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`** for the handlers that make the slow call: they run with no
  unit of work and no connection, and open their own transaction for the database part afterwards. See
  [Blocking I/O in a Message Handler](LLM-foundation.md#blocking-io-in-a-message-handler-unitofworkmode).
- **Spring's `LazyConnectionDataSourceProxy`** around the application's `DataSource`: a transaction then takes its
  connection at its first statement rather than when it begins. This is an application-wide choice that affects
  every transaction, so Essentials does not make it for you.

A successful handler's transaction always ends with the acknowledgement (since 0.60.1), so it needs a connection
at some point; laziness only shortens how long it is held.

### Ordered queues whose backlog sits on few keys

The ordered claim takes each key's head: its lowest `key_order`, which blocks the rest of the key. The default
statement walks candidates until it has as many heads as there are free slots. With fewer heads than that (a few
keys with many messages each, the typical event-processor shape), it never stops early and probes every queued
message on every poll: 180 ms at 20 keys × 1 000 messages.

Since 0.60.1 such a queue switches to a second statement that finds each key's head with one index probe per key:
0.4 ms for the same backlog. It switches back when the queue has many keys, where that statement is the slow one
(114 ms at 20 000 keys of one message each, against 1.6 ms). The switch happens per queue, after three slow claims in
a row (over 10 ms; `setOrderedClaimSlowThreshold(Duration)` on the builder), and only to the statement last measured
as faster, so a slow round trip to the database does not make a queue alternate. A queue stuck on a slow statement
re-measures the other one now and then. Both statements claim the same messages. A claim that tries the other
statement runs under a transaction-local `statement_timeout` of the claim that caused the switch (at least the slow
threshold), and is cancelled when it runs past it: on stale statistics (below) the head scan took 41 s at 20 000 keys.

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
| Assuming exactly-once because the handler's writes and the acknowledgement commit together | Non-idempotent handler | Idempotent handler - a crash before the commit still redelivers |
| SQL injection via table name | `.setSharedQueueTableName(request.getParameter("table"))` | `.setSharedQueueTableName("message_queue")` |
| Traditional-consumer optimizer without listener (ignored) | `.setUseCentralizedMessageFetcher(false).setQueuePollingOptimizerFactory(...)` alone | Add `.setMultiTableChangeListener(...)`, or use the centralized fetcher, whose optimizer works without one |
| Aggressive polling without optimization | `.setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(1))` | Add optimizer + reasonable interval |

## Integration

### Spring Boot Starter

See [LLM-spring-boot-starter-modules.md](./LLM-spring-boot-starter-modules.md#postgresql-starter).

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
| [types-jackson3](./LLM-types-jackson.md) | JSON serialization |

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
import org.testcontainers.postgresql.PostgreSQLContainer;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;

@Container
static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15");

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
