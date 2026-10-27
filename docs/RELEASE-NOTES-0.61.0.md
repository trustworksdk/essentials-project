# Essentials 0.61.0 — Release Notes

0.61.0 follows 0.60.1 (`RELEASE-NOTES-0.60.1.md`) and is mostly about what the engines cost under load: fewer
statements, serializations and connections per message, and two lock waits removed.

- Shard-owned engine and adapter:
  - enqueues no longer serialise their commits on `NOTIFY`;
  - idle pumps can give their connections back;
  - a cheaper message format.
- Event store:
  - concurrent gap give-ups no longer deadlock;
  - a give-up costs what it gives up rather than every gap outstanding;
  - CDC hands out event JSON as stored.
- Aggregates: an update no longer re-queries the newest snapshot, policies on declared aggregates apply to
  repositories built at startup (§1.3), and the default snapshot interval is 1000 events (§1.4).

There are no API removals. One schema change removes a redundant index on the event store's gap table (§1.5). The
shard-owned adapter writes a new message format, and **0.60.x and 0.61.0 must not run side by side against the same
shard-owned queues** (§1.2). A few behaviours change in ways a test or a dashboard can notice; they are listed first.

## 1. Behaviour changes — read this first

### 1.1 CDC hands out event JSON as stored (`postgresql-event-store`)

On the CDC delivery path, `PersistedEvent.event().getJson()` and `metaData().getJson()` used to be parsed and
re-serialized, which removed the whitespace from PostgreSQL's `jsonb` rendering (`{"orderId":"o-1"}`). They are now
the column text as stored (`{"orderId": "o-1"}`), the same text the polling path has always returned. Values,
order and types are unchanged, and nothing persisted is affected. Code that compares this text byte for byte, for
example a pattern-matching handler taking the JSON as a `String`, sees PostgreSQL's spacing under CDC as it already
did under polling. Dropping the round trip saves a JSON parse and write per event.

### 1.2 ⚠️ Shard-owned adapter: a new message format

The shard-owned `DurableQueues` adapter stores each message as an envelope in the engine's `bytea` payload. In
0.60 (format 1) the payload and metadata were held as JSON strings inside it, so every message was serialized twice
on the way in and parsed twice on the way out. 0.61.0 writes format 2, which embeds them as JSON:
`{"payloadType":"<class name>","payload":{...},"metaData":{...}}`. Measured on a typical event, that is 35-40%
less CPU to write and read a message and 9% fewer bytes, and the payload is no longer escaped in the
`shard_queue_*_readable` views.

- **Rows in format 1 are still read**, whether queued or dead-lettered, and a resurrected dead letter keeps its
  format. The format is recorded per row in the engine's `payloadType` column, so nothing needs migrating.
- **Do not run 0.60.x and 0.61.0 side by side against the same shard-owned queues.** A 0.60.x instance cannot read
  format 2: it fails the delivery, and after the retries the message is dead-lettered. Stop every instance before
  starting 0.61.0. The adapter is off by default and was new in 0.60.0, which is why this release changes the format
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

### 1.3 ⚠️ Policies on declared aggregates now apply to repositories built at startup (`spring-boot-starter-postgresql-event-store`)

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

### 1.4 Aggregates without a snapshot policy are snapshotted every 1000 events (`spring-boot-starter-postgresql-event-store`)

`essentials.eventstore.snapshots.default-every-n-events` defaults to 1000; it was 10, which added a snapshot - a
serialization and an insert inside the command's transaction - every tenth event of every aggregate without an
interval of its own. `@AggregateSnapshotPolicy` keeps its own default of 100. Set the property to keep the old interval.

### 1.5 The gap table loses a redundant index (`postgresql-event-store`)

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
statement was 9.7 % of the database's statement time. With the change and §2.4, three alternating runs each: 0.47-0.53
ms per give-up instead of 1.1-2.0 ms, 4.3-4.4 s of database time per run instead of 8.5-17.1 s; latency unchanged within
the spread.

- Briefly takes an exclusive lock on `transient_subscriber_gaps` the first time it runs; the table is normally small.
- **With the schema harness in `validate` mode, startup fails until the change is applied** - as for any new schema
  change: the new change `transient-gaps-index-dropped` has no ledger row yet ("not applied"). Apply the script `emit`
  writes (it carries the `DROP INDEX IF EXISTS` and its ledger row), then start. In the default `create` mode nothing
  needs doing.
- A 0.60.x instance restarting during a rolling upgrade creates the index again; the next 0.61.0 start drops it.

## 2. Fixes and improvements

### 2.1 An aggregate update no longer re-queries the newest snapshot (`eventsourced-aggregates`)

To decide whether to add a snapshot, every snapshot repository (`PostgresqlAggregateSnapshotRepository`,
`AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`) asked the database for the aggregate's
newest snapshot on every update, inside the command's transaction. The repository asked the same question a moment
earlier, when the aggregate was loaded, so it now uses that answer and queries only when it has none (a new aggregate,
for example). That is one query and round trip fewer per aggregate update. The answer can only be stale if another
instance adds or deletes a snapshot of the same aggregate in between; a snapshot is then added a little early, or at
most one policy interval late. Snapshots deleted through the repository are never taken from a load.

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

### 2.3 Shard-owned: idle pumps can give their connections back (`postgresql-queue-shard-owned`, Spring Boot starter)

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

### 2.4 Concurrent gap give-ups no longer deadlock (`postgresql-event-store`)

Every subscriber of an aggregate type records the same rolled-back global orders as permanent gaps, at about the same
time, as they share the give-up timeout. `PostgresqlEventStreamGapHandler` inserted them in whatever order its rows came
back in, so two subscribers inserting overlapping orders in different orders could deadlock. PostgreSQL resolves that only
after `deadlock_timeout` (1 s by default), and a CDC subscription's delivery waits on its give-up meanwhile. In the
trading demo this happened 1-2 times per run as shipped, and 37 times in one run, stalling settlement for 40 s, once a
different plan returned the rows in heap order. Permanent gaps are now inserted in ascending order on every path (range
give-up, per-order give-up, promotion), so concurrent give-ups cannot form a cycle.

## 3. Documentation

- The LLM docs describe elastic pump connections, and their `InboxConfig.builder()` examples compile: that builder's
  methods take no `set` prefix (`inboxName`, `redeliveryPolicy`, ...), unlike `OutboxConfigBuilder`'s.
- The `essentials` Claude Code plugin carries the documentation above.
