# Essentials 0.60.1 — Release Notes

0.60.1 is a patch release, mostly for the durable queues.

- `postgresql-queue`:
  - claims stay fast after a queue table has drained;
  - a busy queue is no longer held to one poll per polling interval;
  - idle queues back off without LISTEN/NOTIFY;
  - a delivery-mode bug is fixed;
  - stored delivery errors are readable.
- Shard-owned engine: four failure-path bugs are fixed, and attempt counts survive a change of owner.
- Units of work: a long-standing bug is fixed where the JDBI and event-store units of work never ran `afterCommit`
  or `afterRollback` callbacks (see §1.0).

There are no API removals and no schema changes. The shard-owned adapter writes a new, cheaper message format and
still reads the old one (§1.8). A few behaviours change in ways a test or a dashboard can notice; they are listed
first.

## 1. Behaviour changes — read this first

### 1.0 ⚠️ `afterCommit` / `afterRollback` callbacks now run outside Spring (`foundation`)

`GenericHandleAwareUnitOfWork` is the unit of work behind `JdbiUnitOfWorkFactory` and the event store's
`EventStoreManagedUnitOfWorkFactory`. Its `commit()` and `rollback()` closed the JDBI handle before calling the
registered callbacks, and closing the handle cleared them. So no `UnitOfWorkLifecycleCallback#afterCommit` or
`#afterRollback` ever ran with these factories. The code has been ordered this way since the first commit of this
repository.

Spring's `SpringTransactionAwareUnitOfWorkFactory` has its own commit path and was never affected. Persisted-event
callbacks (`PersistedEventsCommitLifecycleCallback`) use a separate mechanism and were never affected either.

They run now. If you build units of work with `JdbiUnitOfWorkFactory` or `EventStoreManagedUnitOfWorkFactory`,
code that was silently skipped now executes. This includes Essentials' own callbacks, for example
`AsyncAggregateSnapshotRepository`, which writes snapshots after commit, and the aggregate repositories'
after-commit and after-rollback handling. Your own callbacks run too.

### 1.1 `QueuedMessage.getDeliveryMode()` returns `IN_ORDER` for ordered messages (`postgresql-queue`)

`DefaultQueuedMessage.getDeliveryMode()` returned `NORMAL` for every message, including an `OrderedMessage`.
It now returns `IN_ORDER` whenever `getMessage()` is an `OrderedMessage`. Ordering itself was never affected. Code
that branched on the delivery mode now sees the correct value.

Fixing this exposed a latent crash. Resurrecting an ordered dead letter in `PostgresqlDurableQueues` would have
thrown `ClassCastException`, because its log line cast the wrong object. That code had never run, because the mode
was always `NORMAL`. It is fixed in the same release.

### 1.2 Stored delivery errors start with the root cause (`postgresql-queue`, `springdata-mongo-queue`)

A handler's exception reaches the queue wrapped, typically as
`UnitOfWorkException → ReflectionException → InvocationTargetException → yours`. The stored
`last_delivery_error` therefore began with `UnitOfWorkException`. When the exception is wrapped, the stored text
now starts with one line, `Root cause: <type>: <message>`, followed by the complete, unchanged stack trace. An
exception that is not wrapped is stored exactly as before.

Anything that parses the first line of `getLastDeliveryError()` will see the new summary line.

### 1.3 Shard-owned: `markForRedeliveryIn(delay)` honours the delay and never dead-letters

Through the shard-owned adapter, `QueuedMessage.markForRedeliveryIn(delay)` used to be treated as a failure. The
requested delay was ignored in favour of the policy's backoff, and enough requests dead-lettered a healthy message.
It now redelivers after `delay`. The attempt still counts, as a manual redelivery counts in
`PostgresqlDurableQueues`, but the request never dead-letters the message and is not reported as a handler
failure. Engine users get the same outcome by throwing the new `spi.RedeliveryRequested(delay)`.

### 1.4 Shard-owned: a handler `Error` counts as a failed attempt

An `AssertionError`, or any other non-critical `Error`, thrown by a handler used to free its slot without recording
an attempt. The message came back forever and never reached the dead-letter table. It now runs through the
redelivery policy like any other failure. Critical errors (`VirtualMachineError`, `LinkageError`) still propagate
uncounted, as they do in `postgresql-queue`.

### 1.5 Shard-owned: attempt counts survive a change of owner

Attempt counts were kept only in memory, so every new owner started a message's count from 1. The bulk increment
a new owner made on takeover, meant to stop a message that crashes the JVM from looping forever, was never read
back. It also charged every queued row an attempt on every takeover, including clean ones.

- The first failure under a new owner continues from the count stored on the row.
- A clean handover (the previous owner stopped and released the shard) charges nothing, so deploys and rebalances
  no longer cost queued messages attempts.
- A takeover after a crash or a lost lease charges one attempt only to the rows the previous owner could have had
  in flight: at most `parallelConsumers + ackBatchSize` rows per unordered shard, and the first
  `ackBatchSize + 1` rows of each key on the ordered lane.
- A message that has taken its owner down `maxAttempts` times is dead-lettered at takeover, with an error saying
  no failure was ever recorded for it. On the ordered lane its key is blocked behind it, as for any dead letter.

The `takeoverAttemptBumps` metric therefore drops to zero for clean handovers and to a small number for crashes.

### 1.5a A handled message is acknowledged inside the handler's transaction (`postgresql-queue`)

The acknowledgement used to be a second transaction on a second pooled connection, after the handler's committed.
It is now the last statement of the handler's own transaction, so the handler's writes and the acknowledgement commit
together. This saves a connection checkout and a commit per message. Measured on its own with the builder's previous
wiring (no LISTEN/NOTIFY), drain throughput through a 10-connection pool rose about 10% (13.6k to 14.9k msg/s), and
threads waiting for a connection halved (`QueueDrainThroughputIT`, opt-in benchmark). With the Spring Boot starter's
wiring its share was not measured separately; the release as a whole drains 4.2× faster there (see the measurement
table in `docs/durable-queues-improvement-plan.md`).

What changes:

- **A failing acknowledgement rolls back the handler's writes**, and the message is retried as a failed attempt.
  Before, the writes stayed committed, and the message was delivered again after `messageHandlingTimeout`, repeating
  them.
- **An interceptor on `AcknowledgeMessageAsHandled` runs inside the handler's transaction.** If it throws, the
  handler's writes roll back.

What does not change:

- A handler that requests redelivery, one that marks its unit of work rollback-only (its message is still consumed),
  and `UnitOfWorkMode.NONE` handlers are acknowledged separately, as before.
- `springdata-mongo-queue` and the shard-owned engine are unchanged.
- A crash before the commit still redelivers, so handlers should stay idempotent.

### 1.6 The builder is wired like the Spring Boot starter (`postgresql-queue`)

`PostgresqlDurableQueues.builder()` now creates a `MultiTableChangeListener` when none is set, and its default
`CentralizedQueuePollingOptimizer` uses the Spring Boot starter's values (start at the polling interval, ×1.5 per
empty poll, capped at 2 s). A builder-made instance therefore behaves like a Spring one: idle queues back off, and
LISTEN/NOTIFY wakes them when a message is added on any node.

What a builder user sees:

- One more held database connection per instance, for the listener.
- The change-notification trigger on the queue table becomes part of the queue's schema. With
  `SchemaOwnership.COMPONENT` it is created on start-up; in `validate` mode it must exist.
- `setCreateMultiTableChangeListener(false)` keeps the pre-0.60.1 wiring: no listener, and every queue polled every
  interval without a backoff.

Spring Boot applications see no change.

### 1.7 CDC hands out event JSON as stored (`postgresql-event-store`)

On the CDC delivery path, `PersistedEvent.event().getJson()` and `metaData().getJson()` used to be parsed and
re-serialized, which removed the whitespace from PostgreSQL's `jsonb` rendering (`{"orderId":"o-1"}`). They are now
the column text as stored (`{"orderId": "o-1"}`), the same text the polling path has always returned. Values,
order and types are unchanged, and nothing persisted is affected. Code that compares this text byte for byte, for
example a pattern-matching handler taking the JSON as a `String`, sees PostgreSQL's spacing under CDC as it already
did under polling. Dropping the round trip saves a JSON parse and write per event.

### 1.8 Shard-owned adapter: a new message format

The shard-owned `DurableQueues` adapter stores each message as an envelope in the engine's `bytea` payload. In
0.60.0 (format 1) the payload and metadata were held as JSON strings inside it, so every message was serialized twice
on the way in and parsed twice on the way out. 0.60.1 writes format 2, which embeds them as JSON:
`{"payloadType":"<class name>","payload":{...},"metaData":{...}}`. Measured on a typical event, that is 35-40%
less CPU to write and read a message and 9% fewer bytes, and the payload is no longer escaped in the
`shard_queue_*_readable` views.

- **Rows in format 1 are still read**, whether queued or dead-lettered, and a resurrected dead letter keeps its
  format. The format is recorded per row in the engine's `payloadType` column, so nothing needs migrating.
- **Do not run 0.60.0 and 0.60.1 side by side against the same shard-owned queues.** A 0.60.0 instance cannot read
  format 2: it fails the delivery, and after the retries the message is dead-lettered. Stop every instance before
  starting 0.60.1. The adapter is off by default and was new in 0.60.0, which is why this release changes the format
  without a release that only reads it first; a future change to a format in use will get that step.
- `MessageEnvelope.deserialize(jsonSerializer, bytes, key, order)` is deprecated for removal: it cannot tell the
  formats apart. Use the overload that takes the row's format version.
- The envelope is read with a new `JSONSerializer.readProperties` (`foundation`): it reads a JSON object's top-level
  properties one at a time, in document order, so a document whose shape depends on one of its own properties can be
  read in a single pass. `Jackson3JSONSerializer` streams; any other implementation inherits a default that works
  through its own `deserialize`. It is a new default method, so existing implementations keep compiling.
- `Jackson3JSONSerializer` now caches the classes it resolves by name, which saves 200-290 ns on every
  `deserialize(json, "<class name>")` - every message `postgresql-queue` and the Mongo queue read, and every envelope.
  The cache follows `getClassLoader()`, so a Spring Boot DevTools restart, which swaps the class loader, is safe.
  Events use it too: `EventJSON` resolves an event's class through the new `JSONSerializer.resolveClass(name)` when
  deserializing it and in `getEventTypeAsJavaClass()`, which `EventProcessor` and `ViewEventProcessor` call for every
  event to decide whether they handle it (117 to 30 ns per call). `resolveClass` is a new default method that resolves
  a name the way the by-name `deserialize` methods do.

### 1.9 ⚠️ Policies on declared aggregates now apply to repositories built at startup (`spring-boot-starter-postgresql-event-store`)

`@AggregateSnapshotPolicy` and `@AggregateClosingBooksPolicy` on an aggregate declared through
`EssentialsAggregateDeclarations` reach the policy registries when `AggregateDeclarationPolicyRegistrar` runs. Nothing
made that happen before a resolution, and an application resolves while building its own beans: a
`StatefulAggregateRepository` bean resolves its snapshot repository in its builder, and `ClosingBooksSetup` its
closing-books policy. Application beans are created before auto-configured ones, so those resolutions found empty
registries and silently got the global defaults, which the caching provider then kept.

Both configuration resolvers now wait for the registrar. If you declare aggregates and build their repositories as
beans, the values you annotated now apply where the defaults applied before - snapshot interval and mode, closing-books
trigger, boundary and time zone. In the trading demo, `Account` was snapshotted every 10 events instead of 100 and
closed its books at UTC end of day instead of New York's. Check your annotations say what you want.
`DeclaredAggregatePolicyResolutionOrderTest` reproduces it.

### 1.10 Aggregates without a snapshot policy are snapshotted every 1000 events (`spring-boot-starter-postgresql-event-store`)

`essentials.eventstore.snapshots.default-every-n-events` defaults to 1000; it was 10, which added a snapshot - a
serialization and an insert inside the command's transaction - every tenth event of every aggregate without an
interval of its own. `@AggregateSnapshotPolicy` keeps its own default of 100. Set the property to keep the old interval.

## 2. Fixes

### 2.0 A busy queue is no longer held to one poll per interval (`postgresql-queue`)

A poll claims at most a queue's free worker slots. Polling once per interval capped a queue at
`parallelConsumers / pollingInterval` messages per second however fast its handler was: 400 msg/s for 8 consumers at
the default 20 ms. A queue whose poll filled every free slot is now polled again as soon as half its workers are free.
Measured: 2 000 messages, 8 consumers, 200 ms polling drained in 3.3 s, against 50 s before
(`PostgresqlDurableQueuesSaturationIT`).

A poll whose ordered claim finds fewer messages than there are free slots also fills the remaining slots with
unordered messages in the same poll. Before, the unordered messages waited for the next poll.

### 2.1 Claims stay fast after a queue table drains (`postgresql-queue`)

When a queue table drains to empty and is then analyzed, PostgreSQL records that it has no rows. When the next
burst arrives, the claim's `UPDATE … FROM cte WHERE q.id = cte.id` was planned as a nested loop over the whole
queue, so each claim took time proportional to the backlog: 185 ms instead of 4 ms per claim at 20 000 queued
messages. Both claim statements now select their rows by primary-key array (`WHERE q.id = ANY(ARRAY(…))`).
`PostgresqlDurableQueuesClaimPlanIT` recreates the stale statistics and guards the plan shape.

The ordered claim's per-key ordering check can still be planned badly under the same conditions. If ordered queues
regularly drain and refill, keep the table's statistics fresh; see "Stale statistics on a draining queue table" in
`LLM/LLM-postgresql-queue.md`.

### 2.1a Ordered queues with a few long keys claim in under a millisecond (`postgresql-queue`)

The ordered claim walked every queued message on every poll when a queue had fewer heads than free slots. Few keys
with many messages each is a common event-processor shape: 20 keys × 1 000 messages took 180 ms per claim, even with
fresh statistics. Such a queue now switches to a statement that visits each key's head once (0.4 ms). It switches
back when the queue has many keys, where the original statement is the cheap one. Both claim the same messages;
`PostgresqlDurableQueuesOrderedClaimStrategyIT` checks that, and the ordered-message suites run against both.

### 2.2 Shard-owned: a superseded owner cannot record a failure

Retry and dead-letter writes now carry the same lease check as acknowledgements. An owner that has lost a shard no
longer pushes out the visibility of, or dead-letters, a message its successor is delivering. The successor
redelivers it.

### 2.3 Shard-owned: the unordered read fetches far fewer rows it cannot dispatch

Each read of the unordered lane fetched up to `readBatchSize` (500) rows, but only as many as there were free
handlers could be handed out; the rest were fetched again on the next read. That came to about 24 rows, payloads
included, per delivered message. Reads are now capped at 64 rows, or twice the free handlers if that is more, which
brings it to about 4. Drain time and the number of reads are unchanged, so this saves database and network I/O,
which grows with payload size; it does not make draining faster.

### 2.4 Retries and delayed messages are delivered on time on a backed-off queue (`postgresql-queue`)

An idle queue's polling backoff could make a message that came due wait out the rest of the backoff. A retry,
delayed enqueue or delayed resurrection made by this node now tells the queue's optimizer when the message becomes
deliverable, and the queue is polled then. With a 10 s cap, a 6 s retry arrived 20 ms late instead of 2.5 s late, and
a 6 s delayed message 29 ms late instead of 3.1 s (`PostgresqlDurableQueuesDueTimeIT`). Spring applications benefit
too: their default cap is 2 s. A delayed message enqueued by another node still waits at most the cap.

### 2.6 Shard-owned: pull-session claims stay fast after a shard drains

A pull session (`MessageQueue.openSession`) claimed its rows with
`UPDATE … WHERE (queue_id, shard, seq) IN (SELECT … FOR UPDATE SKIP LOCKED)`. When the unordered table had been
analyzed while drained, PostgreSQL planned that as a nested loop over the shard, quadratic in its size: a 32-row
claim took 155 s instead of 7.5 ms at 20 000 rows per shard. The claim now selects its rows by primary key (the same
fix as §2.1). `ShardOwnedSessionClaimPlanIT` recreates the stale statistics and guards the plan.

### 2.7 Shard-owned: a takeover no longer chases the shard's history

A new owner of an unordered shard (after a restart, deploy or rebalance) started reading from sequence 0. It
therefore treated every message the shard had ever delivered and deleted as a "hole" and kept querying for them for
`holeExpiry` (10 s by default). The memory and query cost grew with the shard's history: a 5 000-message history
became 5 000 holes and thousands of chase queries. A new owner now starts at the shard's first readable row. A message
whose sequence value was allocated before the takeover but committed after it is still delivered, by the head sweep,
exactly once (`ShardOwnedTakeoverHistoryIT`).

### 2.8 Shard-owned: a shared instance id is reported

The instance id defaults to the hostname. Two JVMs on one host therefore share it, count as one instance in the
rebalance, and keep taking the same shards from each other. This is safe, because the lease fence refuses the losing
process's writes, but wasteful and confusing. The engine now logs a WARN naming `essentials.shard-owned-queue.instance-id`
when another live process heartbeats under the same id, and counts it as `sharedInstanceIdHeartbeats`. Restarts are
not reported.

**Schema:** the membership table `shard_queue_instance` gains a nullable column, `incarnation text`. It is added on
start-up with `SchemaOwnership.COMPONENT`. If you run the schema harness in `validate` mode, apply
`ALTER TABLE shard_queue_instance ADD COLUMN IF NOT EXISTS incarnation text` first.

### 2.9 Shard-owned: ordered messages from concurrent producers are no longer delivered out of order

With several producers enqueueing ordered messages concurrently — even on disjoint keys, each producer numbering and
committing its own keys strictly in order — a message could be delivered after a later `key_order` of the same key. The
ordered lane's watermark retired a pending observation against the running-transaction probe it took *after* reading,
so a writer that committed between the read and the probe looked finished although the read had not seen its rows.
The cursor stepped over them, and the head sweep delivered them late. The engine logged it as `key 'X' is being
delivered key_order N after M was already delivered` and counted `orderViolations` and `sweepRecoveries`. Found by the
trading demo's ledger load test, where it cost a cycle's release and settlement in a 200 000-message run;
`ShardOwnedConcurrentProducersOrderIT` reproduced it in 4 of 5 runs. The watermark now retires against the probe taken
before the read. Measured cost: none beyond noise in drain time and read statements; the watermark trails one pass
longer.

### 2.10 An aggregate update no longer re-queries the newest snapshot (`eventsourced-aggregates`)

To decide whether to add a snapshot, every snapshot repository (`PostgresqlAggregateSnapshotRepository`,
`AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`) asked the database for the aggregate's
newest snapshot on every update, inside the command's transaction. The repository asked the same question a moment
earlier, when the aggregate was loaded, so it now uses that answer and queries only when it has none (a new aggregate,
for example). That is one query and round trip fewer per aggregate update. The answer can only be stale if another
instance adds or deletes a snapshot of the same aggregate in between; a snapshot is then added a little early, or at
most one policy interval late. Snapshots deleted through the repository are never taken from a load.

### 2.11 Shard-owned: enqueues no longer serialise their commits on `NOTIFY` (`postgresql-queue-shard-owned`, adapter)

Every enqueue transaction sent the wake-up `pg_notify`. A transaction that notifies takes, at commit, a lock every
notifying transaction in the cluster shares and holds it through its WAL flush, so enqueueing transactions committed one
at a time. In the trading demo backends waited on that lock for about 20 % of the database's active time, and 36-39 % at
peaks.

The engine's own-transaction enqueues and the `DurableQueues` adapter (inside a `UnitOfWork`, from `afterCommit`) now
write without it and wake the owner after commit: in memory when the owner runs in the same process, otherwise by a
notification a background notifier sends batched, outside the business transaction. `MessageQueue` gains
`enqueueWithoutWakeup(Connection, …)` and `wakeUp(ids)` for callers that control their own transaction;
`enqueue(Connection, …)` keeps notifying inside the transaction, unchanged. A wake-up lost on the way costs latency up to
the head sweep, as a lost notification always did.

Measured (`docs/durable-queue-measurements.md` §3.14): the lock wait disappears, the database's active time falls 20-35 %,
and the ordered-lane benchmark's p99 falls from 9-70 ms to 5-7 ms, at about 30 % more pump statements per message. In
the demo, booked p50 255-261 ms → 237-244 ms and placed→submitted p99 1 271-1 496 ms → 796-992 ms over three runs each.

### 2.12 Shard-owned: idle pumps can give their connections back (`postgresql-queue-shard-owned`, Spring Boot starter)

Every pump holds a database connection for as long as it runs, so a process holds `pumpThreads + 1` connections (the
wake-up listener's included) whether its queues are busy or quiet. A new, opt-in setting keeps that for some pumps and
lets the others give their connection back while their queues are quiet:

```yaml
essentials:
  shard-owned-queue:
    min-held-pump-connections: 1           # unset = every pump holds its connection, as before
    release-idle-pump-connection-after: 10s
```

Without Spring: `ShardRuntime.builder()...setPumpConnections(PumpConnections.elastic(1, Duration.ofSeconds(10)))`. A
pump beyond `min-held-pump-connections` that has delivered nothing for `release-idle-pump-connection-after` borrows a
connection only for a pass with database work and returns it at the end of that pass. The pool still has to fit every
pump during a rush, and the start-up check still counts all of them; this frees connections for the application while
queues are quiet. `ShardOwnerMetrics.pumpConnectionsReleased` counts the releases.

Measured (`docs/durable-queue-measurements.md` §3.15): with 4 pumps and 1 held, the trading demo holds 2 connections
instead of 5 while quiet, between rushes as well as when idle, with latency unchanged within the spread. The default
(every pump held) is unchanged, and benchmarks the same as before.

## 3. Documentation

- The polling-optimizer docs describe the new defaults, and the stale-statistics guidance is next to the claim
  settings.
- The `essentials` Claude Code plugin is updated to `0.60.0-17` with the documentation above.
