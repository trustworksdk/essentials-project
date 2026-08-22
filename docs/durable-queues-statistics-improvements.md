# Durable Queues Statistics Improvements

Scoped to `DurableQueuesStatistics` — the delivery-statistics side of the durable-queues
layer, not the queue itself. Companion in spirit to
[subscription-improvements.md](subscription-improvements.md): a design log with a phased
plan, not a changelog.

---

## Q1 — Replace the `AFTER DELETE` trigger with a Java-side observer

### Current mechanism

[`PostgresqlDurableQueuesStatistics`](../components/postgresql-queue/src/main/java/dk/trustworks/essentials/components/queue/postgresql/PostgresqlDurableQueuesStatistics.java)
does all of its collection in the database. `initializeQueueTables()` (L211–296), called
straight from the constructor, issues four pieces of DDL:

1. `CREATE TABLE IF NOT EXISTS durable_queues_statistics (...)` — the stats table.
2. Two indexes on it (`queue_name`, and `queue_name, added_ts`).
3. `CREATE OR REPLACE FUNCTION log_message_delivery_stats() RETURNS TRIGGER` — a plpgsql
   function whose body inserts `OLD.*` into the stats table, wrapped in
   `BEGIN ... EXCEPTION WHEN OTHERS THEN RAISE NOTICE ... END`.
4. `DROP TRIGGER IF EXISTS trg_log_message_delivery_stats` followed by
   `CREATE TRIGGER trg_log_message_delivery_stats AFTER DELETE ON durable_queues FOR EACH ROW`.

So every row deleted from the queue table — normally an acknowledgement of a handled
message — synchronously writes one statistics row inside the same transaction.

Reads are two queries on that table: an aggregate for `getQueueStatistics(QueueName)` and a
single-row lookup for `getQueueStatisticsMessage(QueueEntryId)`. Retention is handled by the
`@TTLJob` annotation on the class (L76–84), defaulting to 90 days.

The consumer surface is small. `getQueueStatistics` is reached from
[`DefaultDurableQueuesApi.getQueuedStatistics`](../components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/api/DefaultDurableQueuesApi.java)
(L151–155) and exposed as `GET /durable-queues/queues/{queueName}/statistics` by
[`DurableQueuesController`](../components/spring-boot-starter-admin-api/src/main/java/dk/trustworks/essentials/components/adminapi/rest/DurableQueuesController.java)
(L130–136). The whole feature is opt-in — `essentials.durable-queues.enable-queue-statistics`
defaults to `false`, and
[`EssentialsComponentsConfiguration`](../components/spring-boot-starter-postgresql/src/main/java/dk/trustworks/essentials/components/boot/autoconfigure/postgresql/EssentialsComponentsConfiguration.java)
(L314–328) wires `NoOpDurableQueuesStatistics` when it is off.

### Motivation

The trigger is the wrong mechanism for this, on seven distinct counts. The first three are
about cost, the next two about portability, the last two about correctness.

**1. It sits on the acknowledgement hot path.** Every ack pays a plpgsql invocation, an
`INSERT`, and maintenance on two indexes, inside the queue's own transaction. WAL volume
per delivered message roughly doubles. None of that work is needed for the queue to be
correct; it exists only so an admin endpoint can report an average.

**2. `EXCEPTION WHEN OTHERS` costs a subtransaction per row.** In PostgreSQL an exception
block inside plpgsql is implemented as an implicit savepoint, so the "make statistics
failures harmless" guard is the single most expensive part of the trigger. At sustained
queue throughput this burns subtransaction ids and pushes the subtransaction SLRU toward
overflow, which degrades unrelated queries on the same database. The safety net is more
dangerous than the thing it guards.

**3. Purge amplification.** `purgeQueue` deleting 100 000 rows fires the trigger 100 000
times, and every one of those rows is then counted as a delivered message with a delivery
latency measured from `added_ts` to the moment of the purge. A purge therefore both costs a
second bulk insert and corrupts the statistics it writes.

**4. It does not port to other dialects.** The in-progress `jdbc-queue-base` /
`mssql-queue` split intends `JdbcDurableQueuesStatistics` to be shared with
`MsSqlDurableQueuesStatistics` as a thin subclass — that is what
[`components/jdbc-queue-base/CLAUDE.md`](../components/jdbc-queue-base/CLAUDE.md) and
[`components/mssql-queue/CLAUDE.md`](../components/mssql-queue/CLAUDE.md) already promise.
A plpgsql trigger body cannot be shared; it has to be re-authored in T-SQL and then kept
semantically identical to the plpgsql version forever. Collection in Java is dialect-neutral
by construction, and only the `INSERT`/`SELECT` statements stay dialect-specific.

**5. The statistics component performs DDL on a table it does not own.** `CREATE TRIGGER`
on `durable_queues` requires ownership of the queue table and mutates the queue module's
schema from the statistics bean's constructor. It also means enabling statistics is a
schema migration, not a configuration change.

**6. The trigger function name is unqualified and its target table is baked into the body.**
`log_message_delivery_stats()` takes no arguments and hardcodes `statsQueueTableName` in its
body. Two `PostgresqlDurableQueuesStatistics` instances in one schema with different stats
table names therefore fight: the second `CREATE OR REPLACE` rewrites the shared function, and
both triggers start writing to whichever table was initialised last.

**7. The per-message read path is broken and untested.** `delivery_latency` is stored as
`INTERVAL`, and `QueueStatisticsMessageRowMapper` (L418–447) reads it with
`rs.getInt("delivery_latency")`, which pgjdbc rejects for a non-numeric type. Nothing caught
this because `getQueueStatisticsMessage` has no caller anywhere in the reactor outside the
`LLM/` docs, and the one statistics integration test
([`PostgresqlDurableQueuesIT`](../components/postgresql-queue/src/test/java/dk/trustworks/essentials/components/queue/postgresql/PostgresqlDurableQueuesIT.java)
L122–125) asserts only `isPresent()` on the queue-level aggregate.

### What the data is actually used for

Worth stating plainly, because it sets how much machinery is justified:

| Read | Consumers | Coverage |
|---|---|---|
| `getQueueStatistics(QueueName)` | one admin REST endpoint | one IT, asserts presence only |
| `getQueuedStatisticsMessage(QueueEntryId)` | none in the reactor | none |

The feature is off by default and its per-message half is unreferenced. That is a lot of
design freedom, and it argues for the cheapest mechanism that keeps the queue-level answer
credible — not for faithfully reproducing a per-message audit log nobody reads.

### The hook point: an observer, not an interceptor

The existing `DurableQueuesInterceptor` chain already sees enough data in principle.
`HandleQueuedMessage` carries the full `QueuedMessage`, which exposes every field the trigger
copies out of `OLD.*`: id, queue name, added timestamp, delivery timestamp, total and
redelivery attempts, delivery mode, metadata, and last delivery error. Micrometer already
uses exactly this hook — see
[`DurableQueuesMicrometerInterceptor`](../components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/micrometer/DurableQueuesMicrometerInterceptor.java).

The interceptor route is nevertheless the wrong shape here, because the *outcome* is decided
outside the intercepted call. In both consumer paths, `chain.proceed()` covers only the
handler invocation; the acknowledgement, the dead-letter marking and the retry all happen
after it returns:

- [`DefaultDurableQueueConsumer.handleMessage`](../components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/DefaultDurableQueueConsumer.java)
  — chain at L456–464, `acknowledgeMessageAsHandled` at L479, `markAsDeadLetterMessage` at
  L507, `retryMessage` at L537.
- [`CentralizedMessageFetcher.processMessage`](../components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/CentralizedMessageFetcher.java)
  — chain at L332–340, ack at L364, dead-letter at L392, retry at L404.

`AcknowledgeMessageAsHandled` and `DeleteMessage` carry only a `QueueEntryId`, so an
interceptor-only implementation has to keep a `ConcurrentHashMap<QueueEntryId, ...>` of
in-flight messages, populated during `HandleQueuedMessage` and consumed at ack — plus a size
cap and a sweep for entries whose ack never arrives (failed ack, crash mid-handling). That is
avoidable state, and it is state that leaks by default.

Recording at the call sites instead removes the correlation problem entirely: both places hold
the `QueuedMessage` *and* know how the delivery ended. So introduce an observer SPI in
`foundation`, modelled on
[`EventStoreSubscriptionObserver`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/observability/EventStoreSubscriptionObserver.java):

```java
public interface DurableQueueMessageObserver {
    default void messageHandled(QueuedMessage message, Duration handlerDuration) {}
    default void messageRedeliveryRequested(QueuedMessage message) {}
    default void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {}
    default void messageDeadLettered(QueuedMessage message, Throwable cause) {}

    static DurableQueueMessageObserver none() { ... }
    static DurableQueueMessageObserver composite(List<DurableQueueMessageObserver> observers) { ... }
}
```

`messageHandled` fires after the acknowledgement succeeds, so the count means "delivered and
removed from the queue" rather than "handler returned".

Two contracts carry straight over from the event-store side, and both are load-bearing:

- **Recording must never break delivery.** Swallow every exception from an observer and log
  once behind an `AtomicBoolean`, exactly as
  [`StatisticsCollectingEventStoreSubscriptionObserver`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/observability/StatisticsCollectingEventStoreSubscriptionObserver.java)
  does.
- **Do not make it a single-slot SPI.** `EventStoreSubscriptionObserver` is single-slot, which
  is why anything new there has to decorate rather than replace — a documented trap in
  [`components/postgresql-event-store/CLAUDE.md`](../components/postgresql-event-store/CLAUDE.md).
  Ship this one as a list/composite from the start so statistics and Micrometer can coexist
  without one wrapping the other.

Because every consumer path funnels through these two classes, the observer also covers
`Inbox`, `Outbox` and `DurableLocalCommandBus` deliveries with no extra wiring.

**Documented semantic change:** administrative `deleteMessage` and `purgeQueue` stop producing
statistics rows. That fixes the purge amplification described above, but it does change what
the numbers mean for anyone reading the current table.

### Storage: two tiers

**Tier 1 — in-memory registry, the new default.** A `DurableQueuesStatisticsRegistry` keyed by
`QueueName`, holding `LongAdder` counters, a latency sum and max, and volatile
first/last-delivery instants, with an immutable snapshot taken when a reader asks. This is a
direct analogue of
[`SubscriptionStatisticsRegistry`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/observability/SubscriptionStatisticsRegistry.java)
and its package-private `MutableSubscriptionStatistics`, including the bounded-tracking
backstop: cap the number of tracked queues and log a single warning on reaching it. A small
bounded ring of recent terminal records per queue (order of 100) keeps
`getQueueStatisticsMessage` able to answer for recently completed ids.

These statistics are per JVM. The event-store side already solved how to say so honestly —
`DefaultEventStoreApi` marks the join explicitly and its CLAUDE.md notes that "a zero counter
is not a stall". Mirror that on `ApiQueuedStatistics` with additive fields: an instance
identifier, a `statisticsSince` instant, and a flag stating the numbers are per instance.

**Tier 2 — optional durable sink.** For deployments that genuinely need cluster-wide or
historical answers, feed the same observer into a batched asynchronous writer against the
existing statistics table: a bounded `ArrayBlockingQueue`, a single daemon drainer (or an
`EssentialsScheduler` job), multi-row `INSERT` in its own unit of work, a flush interval
around one second, and drop-on-overflow with a dropped-record counter. The table, the two read
queries and the TTL job all survive unchanged; only the trigger goes away, and the write moves
off the acknowledgement transaction. A plain multi-row `INSERT` ports cleanly, so this belongs
in `jdbc-queue-base` with `mssql-queue` inheriting it.

The trade-off to state in the javadoc: statistics buffered at crash time are lost. For
delivery statistics that is the right trade; for anything audit-grade it is not, and such
callers should be pointed at their own observer implementation.

**Cluster-wide rollups belong in Micrometer, not SQL.** Emitting a per-queue latency `Timer`
plus handled/dead-lettered counters from the observer lets Prometheus do the aggregation
across instances, which is what the durable table is really being used to approximate today.
`DurableQueuesMicrometerInterceptor`'s `HANDLED_QUEUED_MESSAGES_COUNTER_NAME` can migrate to
the observer afterwards, since the observer knows the outcome and the interceptor does not.

### Configuration and migration

`DurableQueuesStatistics`, `QueueStatistics` and `QueuedStatisticsMessage` are unchanged, so
the stable-central-API rule is satisfied: this is an implementation and wiring change.

Replace the boolean with a mode, keeping the old property working:

```properties
essentials.durable-queues.statistics.mode = OFF | IN_MEMORY | DURABLE_ASYNC | DURABLE_TRIGGER
```

`DURABLE_TRIGGER` reproduces today's behaviour and is marked
`@Deprecated(forRemoval = true)` from the moment it lands, for removal in the next major.
`enable-queue-statistics=true` maps onto one of the durable modes for one release so that no
existing deployment changes behaviour on upgrade, and the mapping is announced in
[MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md).

### Phased plan

**Phase 1 — collection moves into Java.** Add `DurableQueueMessageObserver` and the two call
sites, `DurableQueuesStatisticsRegistry`, and an in-memory `DurableQueuesStatistics`
implementation. Add the mode property with the legacy trigger path still selectable, and the
additive per-instance fields on `ApiQueuedStatistics`. Nothing is removed in this phase.

**Phase 2 — durable without a trigger.** Add the batched asynchronous writer in
`jdbc-queue-base` behind `DURABLE_ASYNC`. Fix the schema while there: replace
`delivery_latency INTERVAL` with `delivery_latency_ms INTEGER` (new column, old one left
nullable) so the per-message read path stops being broken, and drop the unqualified shared
plpgsql function.

**Phase 3 — next major.** Delete the trigger path and the DDL against `durable_queues`, and
let `MsSqlDurableQueuesStatistics` become the thin subclass its module documentation already
describes.

### Testing

The existing `PostgresqlDurableQueuesIT` statistics test should stay green across all three
phases — and should be tightened first, since asserting `isPresent()` would not catch a
count of zero or a nonsensical latency. Add coverage for:

- no trigger is installed on `durable_queues` in any non-legacy mode;
- `purgeQueue` produces no statistics records;
- retried and dead-lettered messages are classified as such, and are not counted as delivered;
- an observer that throws does not affect delivery, acknowledgement or retry;
- the Tier 2 writer drops records and increments its counter under overflow rather than
  blocking the caller.

Throughput impact is measurable on the existing `*PerformanceIT` / `*LatencyIT` bases in
`jdbc-queue-base`; use `scripts/test-timings.sh --csv` for a before/after baseline rather than
reasoning about the cost of the trigger in the abstract.

### Open decisions

1. **Keep `getQueueStatisticsMessage` at all?** It has no callers, no tests, and a broken
   column mapping. Options: remove it in the next major, serve it from the Tier 1 bounded ring
   (recent messages only), or keep it durable-only and unavailable in `IN_MEMORY` mode.
2. **What `enable-queue-statistics=true` maps to.** `IN_MEMORY` is cheap but changes the
   meaning of the numbers for existing users; `DURABLE_ASYNC` preserves today's answers at the
   cost of keeping the table in the default path.
3. **Whether Micrometer's handled-message counter migrates to the observer in Phase 1** or
   stays on the interceptor until the observer has shipped one release.
