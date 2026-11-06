# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.1`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.1.md`](RELEASE-NOTES-0.60.1.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

**So far:** mostly what the engines cost under load - fewer statements, serializations and connections per message,
and two lock waits removed.

- Shard-owned engine and adapter: enqueues no longer serialise their commits on `NOTIFY`; pumps visit only the owners
  that need them and take one horizon probe per pass; idle pumps can give their connections back; a cheaper message
  format; a queue depth read costs 3 statements instead of 69 (§2.13); a head sweep is one statement, not two (§2.14).
- Event store: concurrent gap give-ups no longer deadlock; a give-up costs what it gives up rather than every gap
  outstanding; CDC hands out event JSON as stored; an out-of-order commit no longer costs every CDC subscriber a
  commit of its own (§2.12).
- Aggregates: an update no longer re-queries the newest snapshot, policies on declared aggregates apply to
  repositories built at startup (§1.1.2), and the default snapshot interval is 1000 events (§1.1.3).
- Handler dispatch (`shared`): every aggregate event applied and every handler call no longer builds log messages that
  trace logging is off for.
- Event processors: `PartitionedEventProcessor` runs its handlers on every instance on the shard-owned engine (§2.7),
  `PartitionedViewEventProcessor` does the same for views (§2.10), and both reset only while stopped on every instance;
  an `EventProcessor` overridden to competing consumers on a per-node queue now refuses to start (§1); the default retry
  budget outlasts a failover, minutes instead of ~10 s (§1.1.4).
- Command handlers: an `AnnotatedCommandHandler` failure is logged at DEBUG, not ERROR - the sender reports it (§1.1.5).
- Fenced locks: opt-in spreading of async-acquired locks, and so of exclusive processors, over the instances (§2.9).

There are no API removals. One schema change removes a redundant index on the event store's gap table (§1). The
shard-owned adapter writes a new message format, and **0.60.x and this release must not run side by side against the
same shard-owned queues** (§1). An `EventProcessor` overridden to `GlobalCompetingConsumers` on a queue that keeps key order per
node only now refuses to start (§1).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

**⚠️ Shard-owned adapter: a new message format - stop every 0.60.x instance first.**

The shard-owned `DurableQueues` adapter stores each message as an envelope in the engine's `bytea` payload. In
0.60 (format 1) the payload and metadata were held as JSON strings inside it, so every message was serialized twice
on the way in and parsed twice on the way out. This release writes format 2, which embeds them as JSON:
`{"payloadType":"<class name>","payload":{...},"metaData":{...}}`. Measured on a typical event, that is 35-40%
less CPU to write and read a message and 9% fewer bytes, and the payload is no longer escaped in the
`shard_queue_*_readable` views.

- **Rows in format 1 are still read**, whether queued or dead-lettered, and a resurrected dead letter keeps its
  format. The format is recorded per row in the engine's `payloadType` column, so nothing needs migrating.
- **Do not run 0.60.x and this release side by side against the same shard-owned queues.** A 0.60.x instance cannot read
  format 2: it fails the delivery, and after the retries the message is dead-lettered. Stop every instance before
  starting this release. The adapter is off by default and was new in 0.60.0, which is why this release changes the format
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

**The gap table loses a redundant index (`postgresql-event-store`) - apply it under `validate`.**

`PostgresqlEventStreamGapHandler` now drops `transient_subscriber_gaps_index`, an index on
`transient_subscriber_gaps (subscriber_id, aggregate_type)`, at start-up (`DROP INDEX IF EXISTS`, every boot). The
table's primary key, `(subscriber_id, aggregate_type, gap_global_event_order)`, starts with the same columns and serves
every statement on the table.

The index did harm. With statistics taken while the table was small - as it is after every drain - the planner chose
it as the smaller index and filtered the gap orders instead of looking them up. Giving up a few gaps then read every
transient gap the subscriber had outstanding, and that cost grows with the number of concurrent appends that roll back
(optimistic-concurrency conflicts), since every subscriber of the aggregate type records each one. The give-up now binds
the orders as one array, which, with only the primary key available, is part of the index lookup. Measured with
`auto_explain` in the trading demo before the change: a give-up read a median of 1 509 rows to give up 2, and the
statement was 9.7 % of the database's statement time. With the change and the gap give-up deadlock fix (§3), three alternating runs each: 0.47-0.53
ms per give-up instead of 1.1-2.0 ms, 4.3-4.4 s of database time per run instead of 8.5-17.1 s; latency unchanged within
the spread.

- Briefly takes an exclusive lock on `transient_subscriber_gaps` the first time it runs; the table is normally small.
- **With the schema harness in `validate` mode, startup fails until the change is applied** - as for any new schema
  change: the new change `transient-gaps-index-dropped` has no ledger row yet ("not applied"). Apply the script `emit`
  writes (it carries the `DROP INDEX IF EXISTS` and its ledger row), then start. In the default `create` mode nothing
  needs doing.
- A 0.60.x instance restarting during a rolling upgrade creates the index again; the next start of this release drops it.

**An `EventProcessor` whose inbox runs `GlobalCompetingConsumers` on a queue that cannot keep key order across
instances no longer starts** (`postgresql-event-store`).

Overriding `getInboxMessageConsumptionMode()` to `GlobalCompetingConsumers` lets every instance consume the processor's
inbox. On `PostgresqlDurableQueues` and MongoDB, which keep an ordered key in order per node only, two instances can
then handle one aggregate's events out of order - the `Inboxes` javadoc has always required `SingleGlobalConsumer` for
ordered messages there, but nothing enforced it. `start()` now throws `IllegalStateException` for that combination,
naming the fix. Nothing changes on the shard-owned engine, or for a processor that does not override the method.

- **What to do:** remove the override (one instance handles the inbox again), or run the queues on the shard-owned engine
  (`essentials.shard-owned-queue.durable-queues-enabled=true`) and extend the new `PartitionedEventProcessor` (§2.7).
- With a single instance the combination was harmless; it still refuses, because the code cannot know it will stay one.

### 1.1 ⚠️ Silent behaviour changes

#### 1.1.1 CDC hands out event JSON as stored (`postgresql-event-store`)

On the CDC delivery path, `PersistedEvent.event().getJson()` and `metaData().getJson()` used to be parsed and
re-serialized, which removed the whitespace from PostgreSQL's `jsonb` rendering (`{"orderId":"o-1"}`). They are now
the column text as stored (`{"orderId": "o-1"}`), the same text the polling path has always returned. Values,
order and types are unchanged, and nothing persisted is affected. Code that compares this text byte for byte, for
example a pattern-matching handler taking the JSON as a `String`, sees PostgreSQL's spacing under CDC as it already
did under polling. Dropping the round trip saves a JSON parse and write per event.

#### 1.1.2 Policies on declared aggregates now apply to repositories built at startup (`spring-boot-starter-postgresql-event-store`)

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

#### 1.1.3 Aggregates without a snapshot policy are snapshotted every 1000 events (`spring-boot-starter-postgresql-event-store`)

`essentials.eventstore.snapshots.default-every-n-events` defaults to 1000; it was 10, which added a snapshot - a
serialization and an insert inside the command's transaction - every tenth event of every aggregate without an
interval of its own. `@AggregateSnapshotPolicy` keeps its own default of 100. Set the property to keep the old interval.

#### 1.1.4 Event processors retry a failed message for minutes, not seconds (`postgresql-event-store`)

The default `RedeliveryPolicy` of an `EventProcessor`'s inbox (`getInboxRedeliveryPolicy()`) and a `ViewEventProcessor`'s
queue (`getDurableQueueRedeliveryPolicy()`) retried 20 times, from 200 ms growing ×1.1 to 3 s: about 10 s, then a dead
letter. A handler that throws until another processor's work is there - "not yet", retried - therefore dead-lettered
during any failover, which takes the lock timeout (15 s) or the queue's window (30 s) to start, and every later message
of that aggregate waited behind the dead letter. Measured in the trading demo: one killed instance of two dead-lettered
438 settlement reports.

The default now outlasts a failover: it starts at 200 ms, doubles to a 10 s cap, and retries for 4 × (lock timeout +
the queue's window), derived from the actual settings - the lock manager's `lockTimeOut`, and the new
`DurableQueues.maxUnservedAfterInstanceLoss()` (`message-handling-timeout` on `PostgresqlDurableQueues` and MongoDB; on
the shard-owned engine the lease TTL plus one heartbeat interval, a third of the lease TTL, since a surviving instance
takes a dead one's units only on its next heartbeat tick after the lease TTL has passed). With a 15 s lock timeout and a
30 s queue window that is 24 redeliveries over about 3 minutes; on the shard-owned engine with its default 30 s lease
TTL (a 40 s window) it is 28 redeliveries over about 3.7 minutes. `start()` logs the budget in one line.

- **What changes for you:** a message that can never succeed (a bug, a poison message) now dead-letters after minutes,
  and later messages of its aggregate wait that long. A message that succeeds on an early retry is unaffected.
- **To keep the old budget:** override `getInboxRedeliveryPolicy()` / `getDurableQueueRedeliveryPolicy()`. A processor
  that already overrides them is unaffected. `FailoverRedeliveryPolicy.outlasting(...)` sizes your own `Inbox` policies
  the same way.
- New additive SPI methods, all with defaults: `FencedLockManager.getLockTimeOut()`,
  `DurableQueues`/`Inboxes.maxUnservedAfterInstanceLoss()`, `EventStoreSubscriptionManager.getFencedLockManager()`, and
  the shard-owned `MessageQueues.leaseTtl()`.

#### 1.1.5 A failed command is no longer logged at ERROR by `AnnotatedCommandHandler` (`reactive`)

`AnnotatedCommandHandler` logged every exception its handler method threw at ERROR, with the full stack trace, and then
rethrew it - so every failure was reported twice, once here and once by whoever sent the command, and once more per
retry on a durable command bus. Failures a sender expects and retries on purpose (a "not yet" exception) flooded the
log: a trading-demo instance wrote about 6 000 stack traces in two minutes. The default log level
(`getExceptionLogLevel`) is now DEBUG. The failure is still reported where it is handled: thrown to a `send` caller,
logged by the `SendAndDontWaitErrorHandler` of a `sendAndDontWait`, and on a `DurableLocalCommandBus` kept on the queued
message (`last_delivery_error`, queue statistics) and logged at ERROR by the queue consumer when it dead-letters the
command - a retry is logged at DEBUG there, so a command that fails and then succeeds on a retry no longer shows above
DEBUG at all.

**What to do:** nothing, unless you relied on this log line - for example a `sendAndDontWait` with a custom
`SendAndDontWaitErrorHandler` that logs nothing. Then override `getExceptionLogLevel` in your handler to return
`Optional.of(Level.ERROR)`, or log in that error handler.

---

## 2. New features

**The shard-owned changes together, against 0.60.1** (`docs/durable-queue-measurements.md` §3.16; 2 pumps, 2 ms handler,
three alternating rounds each): ordered-lane latency p50 / p99 at 8 queues and 2 000 msg/s 5.8-7.9 / 9.9-12.1 ms →
2.8-3.1 / 4.1-5.3 ms, at 3 000 msg/s 9.1-11.0 / 15.7-33.9 ms → 2.9-3.1 / 5.0-8.1 ms; at 32 queues 3.3-4.2 / 8.3-10.7 ms
against 11.6-15.7 / 21.7-349 ms, with the pumps using about a third less CPU under load (167-189 → 117-122 ms per 1 000
messages) and half as much idle. Idle statement counts are level. The figures under each change below isolate that
change and were measured before 0.60.1 was merged in.

### 2.1 Shard-owned: idle pumps can give their connections back (`postgresql-queue-shard-owned`, Spring Boot starter)

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

### 2.2 Shard-owned: enqueues no longer serialise their commits on `NOTIFY` (`postgresql-queue-shard-owned`, adapter)

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

### 2.3 Shard-owned: a pump visits only the owners that signalled, came due or are new (`postgresql-queue-shard-owned`)

Every pump pass asked every owner the pump served whether it needed attention, and every park asked them all again for
their deadlines. With a few thousand ordered units that asking was most of the pumps' CPU, and it grew with the number
of units whatever the traffic. A pump now keeps a schedule: it visits the owners whose wake-up was signalled, the ones
whose next time-based attention (a sweep, a delayed row, a retry, an acknowledgement flush) has come, and new ones. A
full scan of every owner still runs every `pollBackstop` and after every reconnect, as a safety net; an owner it finds
needing attention without having signalled or come due is counted in `ShardOwnerMetrics.attentionFoundOnlyByFullScan`
(it should stay 0). An owner whose scheduling throws is paced like any other failing owner, 200 ms later, and ends no
pass. (`752c4fa29`)

Measured (`docs/durable-queue-measurements.md` §3.13, 2 000 msg/s, 2 pumps): pump CPU per message falls by a third at
32 queues (2 048 units, 217 → 146 ms per 1 000 messages); at 8 queues it is unchanged, with 12 % more statements per
message, as smaller passes share batched reads among fewer owners. Idle cost unchanged.

### 2.4 Shard-owned: one horizon probe per pump pass (`postgresql-queue-shard-owned`)

Every ordered owner behind its watermark took its own horizon probe (`pg_stat_activity`) on every pass, about three per
delivered message under load and the pumps' most frequent statement. A pump pass now reads first - batched, then a
read-ahead for the owners that read for themselves - takes ONE probe for every owner still behind its watermark, and
then runs each owner's pass, which retires and records watermark candidates exactly as before. (`3be11d757`)

Measured (`docs/durable-queue-measurements.md` §3.12, 8 ordered queues, 2 pumps, 3 000 msg/s): p99 151 → 71 ms, probes
per message 2.59 → 0.24, transactions per message −23 %. With handlers that burn CPU at saturation the extra passes
cost CPU instead (§3.12 has the caveat). Idle cost unchanged.

### 2.5 An aggregate update no longer re-queries the newest snapshot (`eventsourced-aggregates`)

To decide whether to add a snapshot, every snapshot repository (`PostgresqlAggregateSnapshotRepository`,
`AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`) asked the database for the aggregate's
newest snapshot on every update, inside the command's transaction. The repository asked the same question a moment
earlier, when the aggregate was loaded, so it now uses that answer and queries only when it has none (a new aggregate,
for example). That is one query and round trip fewer per aggregate update. The answer can only be stale if another
instance adds or deletes a snapshot of the same aggregate in between; a snapshot is then added a little early, or at
most one policy interval late. Snapshots deleted through the repository are never taken from a load.

### 2.6 Handler dispatch no longer builds log messages nobody reads (`shared`)

`PatternMatchingMethodInvoker` dispatches every event an aggregate applies (including every event replayed while it is
loaded), every annotated command handler and every `@MessageHandler`, event-handler and view-processor call. For each
one it built two descriptions for trace and debug logging: `Method.toGenericString()`, the argument's `toString()` (the
whole event or command), a regex replace and a `String.format`. It built them even with trace logging off. It now
builds them only when trace or debug logging is enabled, or when a failure is logged. Nothing logged changes.

Measured in the trading demo (one instance, market-open rushes of 300 orders/s, two runs each): the invoker's share of
the JVM's CPU samples while trading fell by almost half, from ~19 % to ~11 %, and the JVM took ~9 % fewer samples in
total. Aggregate rehydration fell from ~22 % to ~17 % of the samples. Latency was unchanged within the spread, because the
demo is not CPU-bound at that load.

### 2.7 `PartitionedEventProcessor`: an event processor whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned adapter)

An `EventProcessor`'s subscription is exclusive, and so, by default, is its `Inbox`, where its `@MessageHandler`s run:
with several instances, every handler of every processor runs on the one instance holding the inbox's lock - typically
the first one started. `PartitionedEventProcessor` is the alternative, chosen by extending it instead of
`EventProcessor`:

- **What it does:** one instance reads and forwards the events (in batches of 100 by default, `getForwardBatchSize()`);
  every instance runs the `@MessageHandler`s for its share of the aggregates. One aggregate's events are still handled
  one at a time, in order.
- **What it does not do:** keep order across aggregates; or start on a queue implementation that keeps an ordered key in
  order per node only (`PostgresqlDurableQueues`, MongoDB) - it refuses at start. It needs the shard-owned engine
  (`essentials.shard-owned-queue.durable-queues-enabled=true`) in every environment it runs in, tests included. Its
  consumption mode is fixed: the contract is the class.
- **Be aware:** handlers run on several instances at once, for different aggregates - not for a handler that keeps
  in-memory state across aggregates or calls something that accepts one caller only. Handlers of different aggregates
  writing one shared stream conflict on it more often (the inbox redelivers them). Handlers must be idempotent.
- **A forward that fails:** the `SubscriptionErrorPolicy` retries and stops at a batch as a whole. A policy that skips
  first forwards the failed batch's events again one per unit of work, each tried once more on its own, without the
  policy's retries, so the skip loses only the events that cannot be forwarded on their own either - not the whole batch. This applies to every
  `EventProcessor` with `getForwardBatchSize()` above 1, and to `PartitionedViewEventProcessor` (§2.10). Up to a batch
  is still skipped when the replay cannot run at all (the database unreachable), and when a stopping policy turns into
  a skip after `AutoResume.maxAttempts` resumes. (`c5e495008`)

Whether a queue keeps key order across instances is the new `DurableQueues.keepsKeyOrderAcrossInstances()` /
`Inboxes.keepsKeyOrderAcrossInstances()`: `false` by default, `true` for the shard-owned adapter.

**Reset only while stopped on every instance.** Unlike `EventProcessor`, whose reset runs live on the instance that
reads, a `PartitionedEventProcessor` is reset only while it is stopped everywhere: with handlers on every instance, a live
reset would let a handler still running elsewhere act on an event from before the reset after it. Checked, not assumed -
see §2.10, which describes the rule both partitioned classes share. `start()` now needs the
`EventStoreSubscriptionManager`'s `FencedLockManager` (the default manager has one).

Measured in the trading demo (two instances in one 8-core container, so correctness, not scaling), with its six event
processors handling their inboxes on every instance: a second instance went from 306 to 510 of the 2 244 shard-owned
units, fenced locks from 41 to 35, and each inbox's deliveries split about evenly (order router 6 194 / 6 124, order
ledger 10 679 / 10 509) with 0 ordering violations. Killing the instance that held every lock lost nothing. The order
ledger's inbox retried 2.9 % of its deliveries on two instances against 2.0 % on one, all successfully.


### 2.8 A handler that outlasts `message-handling-timeout` is logged (foundation, `postgresql-queue`)

On `PostgresqlDurableQueues` and MongoDB a delivery older than `message-handling-timeout` (30 s) is taken back as stuck
and delivered again - also when its handler is merely slow and still running, so two attempts run at once (for an
ordered message, the same key in two threads). Nothing said so above DEBUG. Now:

- A handler that returns after more than the timeout logs a WARN naming the queue, how long it ran and the setting - at
  most once a minute per queue, every occurrence counted. It fires only when the overlap was possible.
- `PostgresqlDurableQueues` logs the messages it takes back at INFO (was DEBUG): either a consumer died with them in
  hand - normal recovery - or the WARN above says a handler ran too long.

The shard-owned engine already warned for its equivalent (a handler outlasting `leaseTtl`).

Related: an `EventProcessor` with more than 4 inbox consumer threads (`getNumberOfParallelInboxMessageConsumers()`) says
so at start, at INFO - each thread holds a pooled connection while it handles a message, so the connection pool has to
fit them together with every other processor's threads and the queue engine's pumps.

### 2.9 Fenced locks can be spread over the instances, opt-in (foundation, PostgreSQL/MongoDB lock managers, Spring Boot starters)

Every exclusive `EventProcessor`, `Inbox` and consumer runs on whichever instance takes its fenced lock first, so in a
cluster one instance usually ends up with nearly all of them, and after a crash a single survivor takes over everything
the dead instance held. `essentials.fenced-lock-manager.spread-locks=true` (builders: `setSpreadLocks(true)`) spreads the
locks taken through `acquireLockAsync` over the live instances instead: each lock has a preferred instance, computed the
same way by all of them, and another instance takes a free lock only after `spread-locks-acquire-grace` (default two
`lock-confirmation-interval`s). As instances never start at once, held locks move too: each instance releases at most one
lock per `spread-locks-rebalance-interval` (default two `lock-confirmation-interval`s, `0` = off) to its preferred
instance. Measured with three managers and 30 locks: 30/0/0 without, 10/9/11 with; one instance
stopped, its 11 locks went to both survivors (15/15). Three trading-demo instances, 35 locks, default intervals, three
rounds each: after a staggered start 35/0/0 without, 10/13/12 with spreading, settled after about 3.5 minutes; after a
cluster restart 34/0/1, 35/0/0 and 2/30/3 without, 10/13/12 every time with; after a crash the survivors' share was
uneven to all-on-one without, 17/18 every time with, at about 5 s longer failover. No schema change: membership is a `__member:<instanceId>` row in
the lock table.

Off by default, because it has costs:

- Every lock that moves pauses its processor or consumer for a few seconds, on every instance joining, leaving or
  restarting - a rolling deploy moves locks out and back. With rebalancing off nothing moves, but then instances started
  one after another stay uneven and spreading only helps when an instance dies.
- A free lock waits up to the grace longer when its preferred instance does not take it.
- It moves whole consumers; one busy consumer is still one instance's work.
- One extra read of the lock table per instance per `lock-confirmation-interval`, and per tick for each free lock a
  non-preferred instance waits on. The membership rows appear in the lock table and the admin API.

`FencedLockManagerSettings` gained a `lockSpreading` component. Its 4-argument constructor stays, deprecated, and means
"not spread"; build the settings with `FencedLockManagerSettings.builder()`.

---

### 2.10 `PartitionedViewEventProcessor`: a view projector whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned engine and adapter)

The view counterpart of `PartitionedEventProcessor` (§2.7). One instance - the holder of `<processorName>:lock` - reads
the events and forwards them in batches (`getForwardBatchSize()`, default 100) to `<processorName>:queue`, one
`queueMessages` per batch in the unit of work that moves the resume point. Every instance consumes the queue and handles
its share of the aggregates, each message in a `UnitOfWork` of its own together with its acknowledgement.

- **Differs from `ViewEventProcessor`:** no order across aggregates (a view relying on one aggregate's row being written
  before another's, or totals over many aggregates, stays a `ViewEventProcessor`); no inline handling, every event goes
  through the queue (a few ms across instances); no `FENCED_LOCK_TOKEN` metadata; handlers must be idempotent (guard rows
  with the event order). `UnitOfWorkMode.NONE` handlers are refused, as by `ViewEventProcessor`.
- **Refuses to start** where `DurableQueues.keepsKeyOrderAcrossInstances()` is `false`: it needs the shard-owned engine.
- **A batch the `SubscriptionErrorPolicy` skips** is first queued again one event per unit of work, as described in
  §2.7 (each tried once more on its own, without the policy's retries), so the skip loses only the events that cannot be
  queued on their own either.
- **The queue key is `<aggregateType>|<aggregateId>`** (`PartitionedViewEventProcessor.qualifiedKey`), so two aggregate
  types sharing an id are two keys. Handlers still see the plain id.
- **Switching from `ViewEventProcessor`** keeps the resume point, lock and queue names. Stop the view on every instance
  first and deploy the new class everywhere before starting it again. **Drain the queue first:** the queue key changes
  from the plain aggregate id to `<aggregateType>|<aggregateId>`, so a pending redelivery or dead letter the old
  processor left in `<processorName>:queue` is not ordered with newer events of the same aggregate - it is applied on
  top of them - and `awaitProcessed` does not see it. Wait until `getTotalMessagesQueuedFor` is 0 and every dead letter
  is resolved; nothing enforces this at start-up.
- **Forward payloads, opt-in:** `forwardsEventPayloads()` returning `true` puts each event itself in the queue instead
  of a reference, so the handling side does not load it from the event store again. Measured in the trading demo (two
  instances, one 8-core machine, 80 000 ledger commands, medians of 3): command drain 47.4 s → 42.2 s, CPU 118 s → 109 s,
  WAL 574 MB → 640 MB. The default stays a reference: small queue rows, the event store authoritative. An event the
  reader cannot deserialize is still forwarded as a reference.

**Reset only while stopped on every instance** - the rule both partitioned classes share. A reset (`resetAllSubscriptions`,
`resetSubscriptions`) is refused unless:

1. the processor is not started on the calling instance;
2. no instance holds a reader lock - the reset holds them while it runs;
3. the queue implementation counts no live consumer of the processor's queue, and no departed instance still holding part
   of it - one that crashed, or one whose stop kept part of the queue for a handler still running. The database cannot
   tell those two apart, so both refuse, for as long as the unit is held. After a crash nobody recovered from, start and
   cleanly stop an instance, or - once you have made sure the old process is gone - call
   `resetAllSubscriptionsIgnoringDepartedInstances()`, which skips only this last check.

A starting instance registers its queue consumer while holding `<processorName>:reset`, which the reset holds too, so a
start and a reset exclude each other (within one JVM as well). The reset can run on any instance, including one that never
read: it moves the stored resume points itself (new `EventStoreSubscriptionManager.resetStoredResumePoint`).

The reset methods of both partitioned classes are `final`, so a subclass cannot route a reset around these checks. To act
on a reset - for instance code a `ViewEventProcessor` ran in an overridden `resetSubscriptions` - override
`onSubscriptionsReset(AggregateType, GlobalEventOrder)`, which the reset calls once per aggregate type.

New, all additive: `DurableQueues.getQueueConsumers(QueueName)` / `Inboxes.getQueueConsumers(InboxName)` returning
`QueueConsumers(liveInstances, departedInstancesHoldingUnits)` (empty by default, answered by the shard-owned adapter);
`MessageQueue.departedInstancesHoldingUnits()` on the shard-owned engine; `EventStoreSubscriptionManager.resetStoredResumePoint`
and `exclusiveSubscriptionLockName`.

### 2.11 `InvokeMessageHandlerMethod` exposes the message being handled (foundation)

`InvokeMessageHandlerMethod` - the operation a `MessageHandlerInterceptor` sees around every `@MessageHandler` call -
now has `message` and `messagPayload` as public final fields, like `methodToInvoke` and `invokeMethodOn` already were,
so an interceptor can read the `Message` and its metadata without reflection. Additive; nothing to change.

### 2.12 CDC: an out-of-order commit no longer costs each subscriber a commit of its own (`postgresql-event-store`)

The CDC bus delivers events in commit order. When appenders run concurrently, a lower global order often commits a few
milliseconds after a higher one. Each time, every CDC subscription recorded the "gap" below the higher event as a
transient gap: one extra transaction, on a pooled connection, before the event was handed on. A second statement then
deleted the row once the late event arrived. In the trading demo, that came to ~550k inserts plus ~550k deletes in
`transient_subscriber_gaps` and 407k reconcile statements over ~780k events. This on a machine whose throughput is
bound by WAL flushes.

The row only matters once a resume point past the gap is saved, so for subscriptions managed by an
`EventStoreSubscriptionManager`, the subscription now keeps a narrow opened gap in memory. The gap is written only if it
is still open when the subscriber's resume point is next saved (periodically, on stop, lock release or reset), and is
always written before that save. A gap that fills before then costs no database write at all. The crash guarantee is
unchanged: no resume point is saved past a gap that is not recorded. The gap is written in a transaction of its own,
committed before the save goes on, also when the save runs inside a unit of work of the caller's, or inside a Spring
transaction with no unit of work bound yet - a rollback of that unit of work or transaction cannot take it back. There, the write runs on a virtual thread and is waited for at most 10 s; if that
runs out, the resume point is not saved and the next save tries again. While such a write still runs, a later save or the
subscription's end on that thread waits for it at most 10 s too, instead of hanging. Any acknowledgement, on any
thread, waits for a running gap recording at most 10 s and then fails, and the gap of a gap fill it acknowledged is
recorded and stays open, so a later subscription delivers that event again. `awaitProcessed` likewise waits for it at
most 10 s per check and then treats the event as not processed yet (`8b99e122e`). A save whose gaps could not be written (that
timeout, or an unreachable database) saves the other resume points and then fails, so a stop or lock release stops
retrying it at once and a shutdown treats an unreachable database as one, instead of retrying it every 100 ms for up
to 5 s. A reset (`EventStoreSubscription#resetFrom`, `DefaultEventStoreSubscriptionManager#resetStoredResumePoint`)
whose gaps could not be written now fails with an exception instead of reporting a reset whose resume point was not
saved (`85ad4303e`).

There is nothing to configure. A few cases still write the gap at once, as before:

- direct `pollEvents` subscribers;
- gap handlers other than `PostgresqlEventStreamGapHandler`;
- gaps wider than 1 000 orders.

New, additive: role interface `DeferredGapRecording`, implemented by `CdcEventStore`. An `EventStore` decorator that does
not forward it simply keeps the old behaviour. Design and every save path: `docs/cdc-deferred-gap-recording.md`.

### 2.13 Shard-owned: a queue depth read is 3 statements, not 69 (`postgresql-queue-shard-owned`)

`MessageQueue.depth()` - behind the adapter's `getTotalMessagesQueuedFor` / `getQueuedMessageCountsFor`, the admin
API, the depth gauges and `Inbox.getNumberOfUndeliveredMessages` - counted every unordered shard and every one of the
ordered lane's 64 units with a statement each: 69 statements per read. It now counts each lane with one queue-wide
statement, plus the dead-letter summary. Same result (shards only ever grow, so the queue-wide count is the sum the
loop added up). Anything that polls depth - a monitoring endpoint sampling every queue once a second, an inbox
catch-up loop - costs 23x fewer statements.

Measured (`ShardOwnedDepthCostIT`, benchmark-gated, 4 unordered shards, 200 reads per backlog):

| Backlog per lane | p50 before → after | p99 before → after |
|---|---|---|
| empty | 3.21 → 0.24 ms | 6.36 → 0.57 ms |
| 10 000 | 5.23 → 1.61 ms | 8.33 → 1.94 ms |
| 100 000 | 22.3 → 13.6 ms | 27.1 → 19.1 ms |

### 2.14 Shard-owned: a head sweep and its next-visible time are one statement (`postgresql-queue-shard-owned`)

Every head sweep of an ordered unit - the safety net that recovers rows a cursor skipped and finds delayed messages
that have become due - asked the database two things in two round trips: the visible rows from the head, and how long
until the unit's next delayed row becomes visible. Both now come back from one `UNION ALL` statement, for a unit swept on
its own and for the pump's batched sweep of every due unit of a queue. Each half is the statement it replaces, so each
keeps its own plan (checked against a table vacuumed while drained, where a wrong plan
hurts most). Correctness: `ShardOwnedBatchedReadIT` checks the one-statement sweep returns what the two statements
return. Nothing to configure; behaviour is unchanged.

Measured (`ShardOwnedOrderedLoadedCostIT` / `ShardOwnedOrderedIdleCostIT`, 3 alternating rounds against the previous
commit):

| Measure | Before | After |
|---|---|---|
| Read statements per delivered message | 3.82–3.98 | 3.34–3.67 |
| Idle ordered queue, statements per 3 s | 90 | 62–68 |
| Delivery p50 / p99 | 2.9 / 4.5–7.2 ms | 2.8–2.9 / 4.3–5.1 ms |

### 2.15 Closing books can roll at a business day's close, not at midnight (`eventsourced-aggregates`, Spring Boot starter, admin API)

An `END_OF_DAY` closing-books boundary can now take its days from an application-supplied business calendar instead of
the clock: `@AggregateClosingBooksPolicy(timeBoundary = END_OF_DAY, periodSource = ClosingBooksPeriodSource.BUSINESS_CALENDAR)`
(or `essentials.eventstore.closing-books[.aggregates.<Type>].period-source=business-calendar`), and the
`BuiltInClosingBooksPolicyEvaluator` built with `setBusinessCalendar(calendar)`. The books then roll when the calendar
records that a business day closed - an exchange's clearing cutoff, a bank's end of day - and never on weekends or
holidays; Friday to Monday counts as one period, not three.

- New SPI `ClosingBooksBusinessCalendar` (`currentBusinessDate()`, `businessDaysClosedBetween(from, to)`) and a
  thread-safe `InMemoryClosingBooksBusinessCalendar` fed with `recordClose(businessDate, nextBusinessDate)`:
  idempotent, never moves backwards, tolerates closes arriving out of order. The library does not persist closes;
  the application stores them and feeds the calendar on every instance.
- An unknown business date (an empty calendar on a cold start) never rolls the books; it is logged once at WARN and
  counted (`essentials.closing_books.business_date_unknown`). The gap counter gains a `period_source` tag.
- Period ids keep the `yyyy-MM-dd` format, so an aggregate moves between `CLOCK` and `BUSINESS_CALENDAR` without
  migration. New calculator overloads `ClosingBooksTimeBoundaryCalculator.currentPeriodId(calendar)` and
  `evaluate(calendar, periodId)`.
- Startup validation rejects `BUSINESS_CALENDAR` with any boundary other than `END_OF_DAY`, and skips the `zone-id`
  check for it.
- The admin API's closing-books policy (`GET /aggregate-lifecycle/closing-books-policies`) gains a `periodSource`
  property (a new response property; the generated Java client is regenerated).
- `ResolvedAggregateClosingBooksConfiguration` and `ApiAggregateClosingBooksPolicy` gain a `periodSource` component and
  a `builder()`. Their previous constructors still compile and default to `CLOCK`, deprecated for removal.

Cost on the hot path (every mutating command evaluates it), a warmed-up loop of 2 million `shouldRolloverOnAccess`
calls, 5 rounds on one JVM: the calendar path takes 180-260 ns, the clock-and-zone path 300-370 ns - the calendar reads a
`volatile` snapshot and needs no zone conversion.

Additive: an aggregate that does not set `periodSource` behaves as before. Not covered: business weeks or months, and
rolling on a scheduled scan (the built-in evaluator rolls on access only). Design:
`docs/closing-books-business-day-boundary.md`.

### 2.16 Scheduled closing books can roll an aggregate the way the application does on access (`eventsourced-aggregates`)

A scheduled scan that decided `CLOSE_AND_OPEN_NEXT` only closed the generation row and opened the next one with an
empty stream: no closing event in the old generation, no state carried forward, no period id. That is why
`BuiltInClosingBooksPolicyEvaluator.asDecisionPolicy()` never rolls on a scan, and why idle aggregates under a time
boundary did not roll at all.

- **`ClosingBooksScanRollover`**, set with `DefaultClosingBooksScheduledScanProcessor.builder().setScanRollover(...)`:
  the application's own rollover - its closing event and `ClosingBooksLogicalAggregateRepository.closeAndOpenNextGeneration`
  with its next-generation factory and the next period id. The scan runs it inside the aggregate's generation lock and a
  unit of work; a failure rolls the decision back and defers the generation, as any scan failure does. The scan calls
  its aggregate loader in that same lock and unit of work, so `context.aggregate()` belongs to
  `context.currentGeneration()` and a closing event recorded on it is persisted with the rollover (when the loader's
  repository joins the unit of work, as a `StatefulAggregateRepository` does). A generation rolled or closed after the
  scan read it - by a command that rolled on access in between - is left alone (`generation_moved` outcome) instead of
  being rolled again from the older generation's aggregate (`3c9f7defe`).
- **`BuiltInClosingBooksPolicyEvaluator.asDecisionPolicyRollingOn(ON_ACCESS, SCHEDULED_SCAN)`** rolls on scans too, so a
  passed time boundary - or a closed business day under `periodSource = BUSINESS_CALENDAR` (§2.15) - rolls idle aggregates.
- `ClosingBooksCoordinator.evaluatePolicy(..., rollover)` is the public overload for a custom
  `ClosingBooksScheduledScanProcessor`, which must load the aggregate from the open generation in its own unit of work;
  the built-in scan uses its own guarded path.

Additive: without a rollover a scan behaves as before, and logs a WARN once the first time it rolls a generation row
only. `asDecisionPolicy()` is unchanged.

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Shard-owned queues (`postgresql-queue-shard-owned`, admin API) | Opening a dead letter's details in the admin UI answered 404 (`No message 'o-62-2252' in shard-owned queue …`): looking a message up by id searched queued messages only, though a dead letter keeps its id. It now finds dead letters too, with the error that parked them. The `MessageQueue` SPI gains `getDeadLetter(MessageId)` (a `default` returning empty); `getMessage` stays live-only | - |
| Shard-owned queues (`postgresql-queue-shard-owned`) | The blocked-key warning named the queue by its internal number (`Queue 30, ordered shard 61: …`), so an operator could not tell which queue's dead letters to open. It now gives the registered name with the id (`Queue 'Inbox:Orders:ExchangeReports' (id 30), …`), read once from the registry when first logged | - |
| CDC (`postgresql-event-store`) | A CDC subscription that falls more than a page behind the bus under a burst - routine, and healed within milliseconds by catching up from the event store - logged a WARN and an INFO line every time, a screenful per burst. Each occurrence is now DEBUG, and each subscription logs one INFO line a minute counting them; the `live_source.overflow.count` metric is unchanged | - |
| CDC (`postgresql-event-store`) | The replication tailer reconnected every quiet stream after `max-idle-duration` (5 min): it counted only data messages as signs of life, not the server's keepalives, which keep moving the WAL position while the database writes to tables outside the publication. Each such reconnect marked CDC failed for a moment - an interruption on the health endpoint, and subscriptions polling until it was active again - and logged a WARN with a stack trace. A keepalive that moves the position now counts; only a server silent for the whole window is reconnected, logged at INFO without a stack trace | - |
| Closing books (`eventsourced-aggregates`) | `BuiltInClosingBooksPolicyEvaluator` logged and counted a time-boundary gap twice per rollover - once deciding to roll, again computing the next period id - so `essentials.closing_books.time_boundary_gap_detected` read double. It is now reported once, by `shouldRolloverOnAccess`, which under `EVENT_COUNT_OR_TIME_BOUNDARY` always evaluates the boundary, so a gap is also reported when the event count alone rolls the books | - |
| Fenced locks | `FencedLockManagerSettings` accepted a `lockConfirmationInterval` equal to `lockTimeOut`, though documented as "strictly shorter": confirming exactly at the timeout loses the lock whenever a confirmation is late. Equal values are now refused at start - a configuration with them fails until the interval is shortened | - |
| Event store (PostgreSQL) | Concurrent gap give-ups could deadlock. Every subscriber of an aggregate type records the same rolled-back global orders as permanent gaps, at about the same time, as they share the give-up timeout. `PostgresqlEventStreamGapHandler` inserted them in whatever order its rows came back in, so two subscribers inserting overlapping orders in different orders could deadlock. PostgreSQL resolves that only after `deadlock_timeout` (1 s by default), and a CDC subscription's delivery waits on its give-up meanwhile. In the trading demo this happened 1-2 times per run as shipped, and 37 times in one run, stalling settlement for 40 s, once a different plan returned the rows in heap order. Permanent gaps are now inserted in ascending order on every path (range give-up, per-order give-up, promotion), so concurrent give-ups cannot form a cycle. | `8b43e61c` |
| Aggregates (Spring Boot) | Policies on declared aggregates were ignored by repositories built at startup - see §1.1.2. | `162761f8` |
| Shard-owned queue (unordered lane) | A message whose enqueue transaction committed more than `holeExpiry` (default 10 s) after its sequence value was allocated could be deleted undelivered. Once the owner stopped chasing that value, the next acknowledgement of later messages deleted a range starting at the point the owner took the shard over, and the late row lay inside it. The range now starts just below the first message it acknowledges, so it deletes only messages the owner handled; the late message is delivered by the head sweep, up to `maxSweepInterval` late. Nothing to do on upgrade; keeping enqueueing transactions shorter than `holeExpiry` still avoids that delay. The ordered lane was not affected. | - |
| Event processors on the shard-owned engine | After an unclean failover of an `EventProcessor`'s reader (the lock holder crashed, or was cut off), the new reader re-forwards the events since the last resume-point snapshot, and some of them are usually still queued. The ordered lane refuses a second message under the same key and order (primary key), so the forward failed: the subscription retried three times, stopped, was auto-resumed 10 s later, and repeated that until the originals had been handled - a stall as long as the backlog, with ERROR logs, after every such failover. Forwarded event references now carry the new marker `IdentifiedByKeyAndOrder`. The adapter still enqueues them with the plain insert, but once such an enqueue is refused as a duplicate it writes that queue's marked messages with the engine's new `MessageQueue.enqueueIfAbsent` (additive default methods) for 10 minutes, so the reader's ordinary retry succeeds: one failed attempt and a WARN per failover instead of a stop. Not always "if absent", because that insert costs about 25 % more time and 11 % more WAL per message (measured, batches of 100). Every other ordered enqueue still refuses a reused key and order. Reproducer: `EventProcessorReForwardOnShardOwnedIT`. `PostgresqlDurableQueues`/MongoDB unchanged | - |
| Shard-owned queue (unordered lane) | A message the head sweep delivered ahead of the cursor, and that was handled and acknowledged before the cursor reached it, was recorded as a gap the moment the cursor read past it. Nothing was lost, but the owner then looked the message up every `chaseDelay` (2 ms) until `holeExpiry` (10 s), about 3 400 extra statements each time, and acknowledged later messages one by one instead of as a range for that long. It happened in roughly one run in three of a 2 000 msg/s benchmark. Messages delivered ahead of the cursor are now remembered until the cursor passes them, and any delivery of a message that left a gap clears it. | - |
| Event store (CDC) | When a CDC subscription falls behind the bus and catches up, or moves onto the bus after polling, it must re-read the gaps it is still waiting for. The event that fills one may have committed while the subscription was off the bus, and is then only in the database. The catch-up asked for only the lowest 1 000 awaited global orders, plus the 50 transient gaps the gap handler's query selects (20 newest, 10 lowest, 20 rotating). With more outstanding gaps than that, which happens in bursts with many rolled-back appends, an older but not-lowest gap's late event was missed. It was then delivered after later events of its own aggregate, or never: after the gap timeout it was given up and recorded as a permanent gap over an event that exists. A catch-up now reads every awaited gap. Narrow gaps are listed in batches of up to 1 000 global orders per statement, and the middle of a wide gap is read as one range, so the cost is about one statement per 1 000 awaited orders, never one per order. Nothing to change. Polling was not affected: a gap is always asked for before it is given up. Design: `docs/cdc-deferred-gap-recording.md`. | - |
| `Money` JSON (`types-jackson3`) | `MoneyDeserializer` reads a quoted amount, `{"amount":"99.99","currency":"USD"}`, as the number it holds, the way a `NumberType` field already reads a quoted number. The LLM docs showed that quoted form as `Money`'s format, though `Money` is written with the amount as a JSON number; they now show `{"amount":99.99,"currency":"USD"}`. A numeric amount keeps the scale it was written with, as before; a quoted amount that is not a number, or an amount that is neither a number nor a string, is refused with a `MismatchedInputException`. A quoted amount is held to Jackson's number-length limit (`StreamReadConstraints.maxNumberLength`, 1000 characters by default) like a numeric one, and a longer one is refused with a `StreamConstraintsException`; the same goes for a quoted number read into a `NumberType`. A quoted number read into a `BigInteger`-based `NumberType` is also held to Jackson's BigInteger scale limit (`StreamReadConstraints.validateBigIntegerScale`), so a short text with an extreme exponent is refused with a `StreamConstraintsException` instead of being expanded. What `Money` writes is unchanged. | `900902000`, `9b9d8c6b1` |
| Shard-owned queue (ordered lane) | With the one horizon probe per pump pass (§2.4), an ordered owner whose rows for the pass had not been read yet - no batched read and no read-ahead - had to discard the pump's probe, since its own read comes after it. It told that case by comparing the probe's `System.nanoTime()` stamp against `Long.MAX_VALUE`, which holds only while the stamp is non-negative; `System.nanoTime()` has an arbitrary origin and may be negative, and the subtraction then overflowed and kept the probe. The watermark could then step over a message whose transaction had not committed, so the cursor skipped that message and only the head sweep delivered it, late. The owner now discards the probe whenever no rows were read before it, whatever the clock reads. Nothing to do on upgrade. | `fb8f73801` |
| Shard-owned queue (both lanes) | When a unit was re-acquired on the same pump, its new owner could be added while the pump was still dropping the old one, and the pump's map from a unit's wake-up to its owners (§2.3) could then lose the new owner. For the rest of that owner's tenure a signalled wake-up for its unit did not name it, so the pump visited it only when its own schedule came due or on the full scan every `pollBackstop`, and an enqueue on the same instance sent a `NOTIFY` instead of waking it in memory (§2.2). Nothing was lost; delivery for that unit was slower. Adding and dropping an owner now update the map atomically for the unit. Nothing to do on upgrade. | `083a7a216` |

---

## 4. The `essentials` Claude Code plugin

0.60.1 shipped plugin `0.60.1`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- `0.60.1-3` - the LLM docs show `Money`'s `amount` as a JSON number, the form it is written in, and say a quoted
  amount is also read (§3).
- `0.60.1-2` - the LLM docs describe elastic pump connections, wake-after-commit, `JSONSerializer.readProperties` and
  the batched forward, and their `InboxConfig.builder()` examples compile; they describe `PartitionedViewEventProcessor` and the
  partitioned reset (§2.10), and the slice tooling recognises both partitioned processors as event processors.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- **`essentials-trading-demo`** grows into a retail brokerage trading against the exchange-simulator and the
  payment-gateway: accounts, orders, funding, settlement and reconciliation, a phase 6 load driver (`run-load.sh`) and
  fault runs (`run-faults.sh`). See its `docs/EXPANSION_PLAN.md`.
- **`essentials-trading-demo`: every view projection and query uses Jdbi through the `UnitOfWork`'s handle**, as
  0.60.1 made the original views do. A projection writes through the processor's own unit of work, so its view row
  commits together with the processor's progress by construction; with `JdbcTemplate` it did so only because the
  Spring wiring bound both to one connection, which code copied into a project with another unit-of-work factory
  would not get. Its DDL and reset open a unit of work of their own. Nothing changes at runtime in the demo.
- **`essentials-trading-demo` runs its `DurableQueues` on the shard-owned engine in every profile, tests included**,
  and its two account views (`AccountActivityProjection`, `AccountOverviewProjection`) are `PartitionedViewEventProcessor`s
  (§2.10), with `trading-demo.views.partitioned-consumers` queue consumers per instance (8 under `compose`). Measured on
  one 8-core machine, two instances, 80 000 ledger commands: the account activity view was current 64-91 ms after the
  last command, against 7-9 s for the inline `ViewEventProcessor`, for 30 % longer command drain and 40 % more CPU -
  every event goes through the queue and is reloaded; the gain needs instances on separate machines. Under `compose`
  the views forward event payloads (`trading-demo.views.partitioned-forward-payloads`), which cut that to 21 % and 33 %.
- **`essentials-trading-demo`: two instances started together on an empty database no longer fail on the projections'
  start-up DDL.** `CREATE TABLE IF NOT EXISTS` is not safe against itself; every DDL statement now runs behind
  `PostgresqlUtil.acquireBootstrapLock` (`config/TradingDemoSchema`).
- **`essentials-trading-demo`: phase 6's hour-long run passes on one instance** - 60 business days at 100 orders/s
  with a 600/s open burst, 426 893 orders, every check green, booked p99 under 2 s between bursts and each burst drained
  in ~20 s (`docs/design/phase-6-realistic-load.md` §4.1). The load driver gained the steady-state check, store-size
  sampling (`store-sample-every-days`), queue-depth sampling (`queue-sample-millis`) and, with
  `trading-demo.orders.router-stage-timings.enabled`, a split of the router stage into queue wait, exchange call and
  reports queue wait; its load report no longer times out on a database holding earlier runs. Under `compose` the CDC
  inbox now deletes dispatched rows (`essentials.eventstore.cdc.cdc-dispatcher.dispatched-row-policy: DELETE`) instead
  of keeping them for the TTL job - note the `cdc-dispatcher` key: `dispatcher:` binds to nothing, silently.
  `reset-stack.sh` wipes the compose stack and its volumes before a run (the simulators' stores never prune and filled
  the disk), and under `compose` the load driver's `@load.local` users are stored with `{noop}` password hashes
  (`trading-demo.identity.unhashed-password-email-suffix`), so registering 10 000 of them no longer costs BCrypt.
- **`essentials-trading-demo`: `run-instance.sh` connects to PostgreSQL without Docker's port proxy where it can.**
  Through `localhost:5432` every statement crossed `docker-proxy`. Connecting to the container's IP on the same load
  took the burst days' booked p50 from 557-878 ms to 208-324 ms and p99 from 9.6-11.1 s to 5.3-6.6 s, with about a core
  less CPU. The script uses the direct path when the container IP answers (Linux, the devcontainer) and falls back to
  `localhost:5432` otherwise (Docker Desktop on macOS and Windows) or with `PG_DIRECT=0`; `run-faults.sh` does the same
  for the instances it starts. Demo latency figures from
  before this change include the proxy (`_demo_harness/CLAUDE.md`, "PostgreSQL through the port proxy").
- **`essentials-trading-demo`: the `compose` profile runs 16 inbox consumers on each order-flow stage, on a pool of 60,
  and its PostgreSQL allows 200 connections.** At 8 each stage capped the 600/s market-open burst; raising all three
  (router, exchange reports, ledger) took the burst days' booked p99 from 5.3-6.6 s to 2.9-4.5 s for no more CPU. Two
  instances at a pool of 60 need more than PostgreSQL's default of 100 connections, hence `max_connections=200` in
  both compose files (`_demo_harness/CLAUDE.md`, "Inbox consumers at the opens").
- **`essentials-trading-demo`: the whole demo can run in Docker** - `./run-app-stack.sh [1|2]` with `compose.app.yml`
  and `app.Dockerfile` start one or two application instances in containers beside PostgreSQL and the simulators. On
  Docker Desktop (macOS, Windows) this is how the application reaches PostgreSQL without a port relay, which on the
  host costs about half the burst latency. Loki's URL in `logback-spring.xml` can now be set with `LOKI_URL`.
- **`essentials-trading-demo`: a customer UI** (phase 7, `docs/design/phase-7-customer-ui.md`). After login, `/app`
  has Portfolio, Trade (order ticket beside a live quote, orders followed until filled) and Funding (bank account,
  deposits, withdrawals, payments), as Thymeleaf shells with vanilla JS over the same JSON API the load driver uses.
  Session-authenticated writes to the customer API (`/api/accounts/**`, `/api/market-data/**`) now need the session's
  CSRF token, which every page renders in a `<meta>` tag; HTTP Basic clients are unaffected. A client of its own that
  logs in through the form and writes on the session cookie must send the token, as the load driver now does. Customers
  can read instrument reference data at `GET /api/market-data/instruments`.
  The demo customer `alice@demo.local` starts with a funded account, `ACC-ALICE-1` (100,000 USD, deposited through
  the payment flow at startup; `trading-demo.identity.seed-accounts=false` turns it off).

---

- **`essentials-trading-demo`: the exchange has trading hours, and the accounts roll their books at its close**
  (D32). `simulators/exchange.yaml` turns on the simulator's trading sessions, 09:30-16:00 New York on both markets:
  outside them the exchange rejects orders `MARKET_CLOSED`, the order ticket says the market is closed and does not
  offer to place one (`GET /api/market-data/sessions`), and the load driver places orders only in the session
  (`--session`, `none` for an exchange without sessions), so load runs from before are not directly comparable. The
  accounts' closing books use the new business-calendar boundary (§2.15) fed from the exchange's recorded
  `BusinessDayClosed`, instead of midnight New York on the application's clock. The integration tests keep an
  always-open exchange. The simulator's operator clock endpoints are switched on (`clock.operator_control`).

- **`essentials-trading-demo`: a guide, a stop script, and polling versus CDC measured.**
  [`docs/DEMO_GUIDE.md`](../examples/essentials-trading-demo/docs/DEMO_GUIDE.md) covers starting, stopping and
  resetting the stack, the operator page, the customer UI and what to show with each, and a measured comparison of the
  demo on CDC, on polling and on polling woken by `NOTIFY` (CDC fastest and cheapest at the default load; `NOTIFY`
  doubled the statements). `./stop-instances.sh` stops the instances, the load driver and the containers and keeps
  the data. The load driver's steady-state check no longer fails every short run with trading sessions on: when no
  placement falls outside the bursts, each burst must have recovered within 30 s instead.
- **`essentials-trading-demo`: PostgreSQL's data is in a named volume, `postgres-data`**, like the simulators'
  `exchange-data` and `gateway-data`. It was anonymous, so recreating the database container (Docker Desktop, `up
  --force-recreate`) started an empty database next to simulators that remembered the earlier run, and every old fill
  and settlement report became a dead letter. Now the three are kept and removed together (`./reset-stack.sh`). A
  database from before the change is not carried over: reset once.
- **`essentials-trading-demo` spreads its fenced locks over the instances** (`compose` profile,
  `essentials.fenced-lock-manager.spread-locks`, §2.9): a second instance takes its share of the exclusive processors
  within a few minutes instead of standing by until the first one dies, and `/ops` shows the locks each instance holds.
- **`essentials-trading-demo`: a database reset without the simulators is named once, not reported per order.** When
  the database is new but the simulators remember an earlier run (their volumes kept), the demo logs one WARN saying so
  and how to reset both (`reset-stack.sh`), and the replayed orders and payments it never placed are logged at DEBUG
  instead of a WARN each. `reset-stack.sh` and the run scripts also work on macOS.
- **`essentials-trading-demo`: quieter logs under load.** The exchange's and payment gateway's events are numbered in
  the order the demo receives them, one key at a time, instead of by the source's sequence, so the shard-owned engine no
  longer reports their webhook-and-catch-up arrival order as out-of-order delivery (around a thousand warnings a run
  before). Deposit clearing at a close retries a conflict with the customer's own activity and logs a deposit left for
  the next close on one line instead of with a stack trace.
- **`essentials-trading-demo`: two local instances no longer log each other out in one browser.** The session cookie is
  named per instance, `TRADING_DEMO_SESSION_<instance-id>` (`demo-1`, `demo-2`), instead of `JSESSIONID`: browsers share
  cookies across ports, so logging in on `localhost:8081` replaced the session of `localhost:8080`. The load driver
  reads the new name.
- **`essentials-trading-demo`: an operator page, `/ops`** (phase 8, `docs/design/phase-8-demo-control.md`), to play a
  trading day at any wall-clock time. It drives the exchange-simulator's clock - pause, resume, speeds from 1x to
  1440x, skip to just before the next open or close, advance a number of business days - always forward, and every
  jump passes each close in turn and waits until the application has processed it, so day orders expire, the books
  roll, trades settle and deposits clear as they would have. It runs background traffic in the application (simulated
  customers on the command bus, the load driver's mix, a rate per exchange minute - 2 by default, capped at 10 real
  orders a second - orders only while the market is open with a burst after the open), shows the system's orders, payments and reconciliation cases, and explains each
  panel, control and the steps of a trading day as it goes. Operator only, simulators profile only; API under
  `/api/admin/demo`.

- **`essentials-trading-demo`'s integration tests are opt-in** (`-Ddemo.its`): they took ~6 minutes of every full
  build and test the demo, not the framework. The reactor build runs the demo's unit tests only (~30 s).

## 6. Documentation

- The LLM docs describe elastic pump connections, and their `InboxConfig.builder()` examples compile: that builder's
  methods take no `set` prefix (`inboxName`, `redeliveryPolicy`, ...), unlike `OutboxConfigBuilder`'s.
- The LLM docs and the `types-jackson3` README show `Money` as `{"amount":99.99,"currency":"USD"}`, with the amount a
  JSON number as it is written; they showed it quoted (§3).

---

## 7. Build and tooling

None so far.
