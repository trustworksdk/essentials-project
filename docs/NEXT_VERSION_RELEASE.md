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
  format.
- Event store: concurrent gap give-ups no longer deadlock; a give-up costs what it gives up rather than every gap
  outstanding; CDC hands out event JSON as stored.
- Aggregates: an update no longer re-queries the newest snapshot, policies on declared aggregates apply to
  repositories built at startup (§1.1.2), and the default snapshot interval is 1000 events (§1.1.3).
- Handler dispatch (`shared`): every aggregate event applied and every handler call no longer builds log messages that
  trace logging is off for.
- Event processors: `PartitionedEventProcessor` runs its handlers on every instance on the shard-owned engine (§2.7);
  an `EventProcessor` overridden to competing consumers on a per-node queue now refuses to start (§1); the default retry
  budget outlasts a failover, minutes instead of ~10 s (§1.1.4).

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
`DurableQueues.maxUnservedAfterInstanceLoss()` (`message-handling-timeout` on `PostgresqlDurableQueues` and MongoDB, the
lease TTL on the shard-owned engine). With the defaults that is 24 redeliveries over about 3 minutes. `start()` logs the
budget in one line.

- **What changes for you:** a message that can never succeed (a bug, a poison message) now dead-letters after minutes,
  and later messages of its aggregate wait that long. A message that succeeds on an early retry is unaffected.
- **To keep the old budget:** override `getInboxRedeliveryPolicy()` / `getDurableQueueRedeliveryPolicy()`. A processor
  that already overrides them is unaffected. `FailoverRedeliveryPolicy.outlasting(...)` sizes your own `Inbox` policies
  the same way.
- New additive SPI methods, all with defaults: `FencedLockManager.getLockTimeOut()`,
  `DurableQueues`/`Inboxes.maxUnservedAfterInstanceLoss()`, `EventStoreSubscriptionManager.getFencedLockManager()`, and
  the shard-owned `MessageQueues.leaseTtl()`.

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

Whether a queue keeps key order across instances is the new `DurableQueues.keepsKeyOrderAcrossInstances()` /
`Inboxes.keepsKeyOrderAcrossInstances()`: `false` by default, `true` for the shard-owned adapter.

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

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Fenced locks | `FencedLockManagerSettings` accepted a `lockConfirmationInterval` equal to `lockTimeOut`, though documented as "strictly shorter": confirming exactly at the timeout loses the lock whenever a confirmation is late. Equal values are now refused at start - a configuration with them fails until the interval is shortened | - |
| Event store (PostgreSQL) | Concurrent gap give-ups could deadlock. Every subscriber of an aggregate type records the same rolled-back global orders as permanent gaps, at about the same time, as they share the give-up timeout. `PostgresqlEventStreamGapHandler` inserted them in whatever order its rows came back in, so two subscribers inserting overlapping orders in different orders could deadlock. PostgreSQL resolves that only after `deadlock_timeout` (1 s by default), and a CDC subscription's delivery waits on its give-up meanwhile. In the trading demo this happened 1-2 times per run as shipped, and 37 times in one run, stalling settlement for 40 s, once a different plan returned the rows in heap order. Permanent gaps are now inserted in ascending order on every path (range give-up, per-order give-up, promotion), so concurrent give-ups cannot form a cycle. | `8b43e61c` |
| Aggregates (Spring Boot) | Policies on declared aggregates were ignored by repositories built at startup - see §1.1.2. | `162761f8` |

---

## 4. The `essentials` Claude Code plugin

0.60.1 shipped plugin `0.60.1`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- `0.60.1-1` - the LLM docs describe elastic pump connections, wake-after-commit, `JSONSerializer.readProperties` and
  the batched forward, and their `InboxConfig.builder()` examples compile.

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

---

## 6. Documentation

- The LLM docs describe elastic pump connections, and their `InboxConfig.builder()` examples compile: that builder's
  methods take no `set` prefix (`inboxName`, `redeliveryPolicy`, ...), unlike `OutboxConfigBuilder`'s.

---

## 7. Build and tooling

None so far.
