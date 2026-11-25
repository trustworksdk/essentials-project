# The PostgreSQL event log — how it works

**Modules:** `components/postgresql-event-log` (engine), `components/postgresql-event-log-processor` (typed processors,
`EventStore` adapter, admin SPI), `components/postgresql-event-log-migration` (one-way data move),
`components/spring-boot-starter-postgresql-event-log` (auto-configuration), plus the *Event-log subscriptions* view in
`spring-boot-starter-admin-ui`.
**Packages:** `dk.trustworks.essentials.components.eventlog`, `…eventlog.processor` (admin SPI in `…processor.api`),
`…eventlog.migration`, `dk.trustworks.essentials.components.boot.autoconfigure.eventlog`.
**Status:** **Preview in 0.62.0.** Built in phases 2-10 of the event store redesign; off by default
(`essentials.event-log.enabled`). The API, the properties and the table layout may change in a minor release. The
classic engine (`postgresql-event-store`) stays the default and fully supported, and was not changed by this work.

This document describes the engine as it is built: its storage, the append path, how a subscription follows the tail,
and what the processor module, the starter, the admin surface and the migration tool add. It is a reference for the
system as it stands, not a record of how it came to be. The decisions, the alternatives that were rejected and the
gate runs are in the plan documents listed below. Source comments and Javadoc in the three engine modules cite this
document (for example "see docs/design/event-log.md §5.5"), and the invariant ids I1-I17 refer to §11.

| Document | Contents |
|---|---|
| [`LLM/LLM-postgresql-event-log.md`](../../LLM/LLM-postgresql-event-log.md) | Consumer reference: when to choose it, the starter, the processors, porting, settings, limits |
| [`components/postgresql-event-log/CLAUDE.md`](../../components/postgresql-event-log/CLAUDE.md) | Contributor notes per phase unit: mutants, seams, which IT guards which rule |
| [`components/postgresql-event-log-processor/CLAUDE.md`](../../components/postgresql-event-log-processor/CLAUDE.md), [`components/spring-boot-starter-postgresql-event-log/CLAUDE.md`](../../components/spring-boot-starter-postgresql-event-log/CLAUDE.md) | The same for the processor module and the starter |
| [`docs/archive/event-store-subscription-redesign.md`](../archive/event-store-subscription-redesign.md) | The overall design: the watermark model, the invariants (§ "Invariants"), the decisions (D1…) and the phase plan (§18) |
| [`docs/archive/event-store-redesign-phase0.md`](../archive/event-store-redesign-phase0.md) | Phase 0: the spikes P0.0-P0.17 and their numbers |
| [`docs/archive/event-store-engine-phase2.md`](../archive/event-store-engine-phase2.md) … [`phase10.md`](../archive/event-store-engine-phase10.md) | One design per phase, each with its decisions and gate results |

---

## 1. Architecture

| Layer | Module | Depends on | What it is |
|---|---|---|---|
| Engine | `postgresql-event-log` | pgjdbc, `foundation` (schema harness, `UnitOfWorkLifecycleCallback`), SLF4J | `EventLog`: schema, append, stream loads, subscriptions, leases, wake-ups, parking, admin reads. Payloads are opaque JSON text. No JDBI, Reactor, Jackson or `postgresql-event-store` (`architecture/DependencyRulesTest`) |
| Processors | `postgresql-event-log-processor` | the engine, `foundation`, `postgresql-event-store`'s value types and serializers only | Typed events (`DeliveredEvent`), `ViewProjection`, `EventReactor`, the annotated base classes, page transaction sources, `EventLogEventStore` (the `EventStore` adapter), `EventLogCommits`, `EventLogSubscriptionsApi` |
| Migration | `postgresql-event-log-migration` | the engine, the JDBC driver | `Migration` and `MigrationCli`: copy the classic tables into the event log's, in the same database |
| Starter | `spring-boot-starter-postgresql-event-log` | the three above, the components starter | `EventLogAutoConfiguration`, `EventLogImportFilter`, `EventLogProcessorLifecycle`, `EssentialsEventLogProperties`, `EventLogAdminApiAutoConfiguration` |

The engine knows nothing about events as Java objects: an `EventToAppend` carries the event type name, the payload and
metadata as JSON text, and a subscription receives `StoredEvent`s. The processor module turns those into typed
`DeliveredEvent`s through the application's `JSONEventSerializer`. Everything that holds a connection, a thread or a
lease belongs to one `EventLog`; the starter declares one per application (one `EventLog` per `DataSource`).

## 2. Storage

All tables are prefixed `el_`. The DDL lives only in `EventLogSchemaContributor` (the schema harness rule); all other SQL
lives in `EventLogStorage`. Derived names go through `AggregateTypeTables`, which validates them with
`PostgresqlUtil.checkIsValidTableOrColumnName` and never truncates.

| Table | Key | Purpose |
|---|---|---|
| `el_event_types` | `id smallint` identity, `name` unique | Event type names interned to small ids. Registered in a committed transaction of their own on the engine's `DataSource`, cached only after commit |
| `el_aggregate_types` | `aggregate_type` | Each registered aggregate type and its two table names. The admin reads list types through it, so types this JVM never registered are visible too |
| `el_progress` | `(subscriber, aggregate_type, instance_name)` | One row per subscription: `exclusive`, `consistency_mode`, `progress_model`, `resume_from`, `reset_epoch`, the lease (`lease_owner`, `lease_until`, `lease_fence`) and `filter_digest`. `instance_name` is `''` for an exclusive subscription. `fillfactor = 70` for HOT updates |
| `el_lease_fence_seq` | sequence | Fence values, drawn only inside a lease claim |
| `el_parked_events` | `(subscriber, aggregate_type, instance_name, global_order)` | Parked events with attempts and error; `blocked_by` marks a later event of a held aggregate; `requested` (`RETRY`/`DISCARD`) is an operator's request |
| `el_migration` | `aggregate_type` | Per type: `COPYING` or `CUT_OVER`, the copy checkpoint and the cut-over report (§10) |
| `el_<type>_events` | `global_order bigint` identity, `CACHE 1` | The events of one aggregate type: `event_order`, `event_id`, `caused_by_event_id`, `correlation_id`, `event_type smallint`, `event_revision`, `aggregate_id`, `event_payload json`, `event_metadata json`, `tenant`, `timestamp`. `UNIQUE (aggregate_id, event_order)`, an index on `event_id`, a partial index on `caused_by_event_id` |
| `el_<type>_heads` | `aggregate_id` | The stream head: `version` = the last `event_order`. `fillfactor = 50` |

Two opt-in indexes per type, `(event_type, global_order)` (`EventLogSettings.eventTypeIndexed`) and
`(tenant, global_order)` (`tenantIndexed`), serve filtered catch-up (§5.4). They are built `CONCURRENTLY` in the
background by `EventTypeIndex` when the engine creates the schema, and only logged otherwise, since a harness change set
runs in one transaction.

The payload columns are `json`, not `jsonb`: the stored text is the serializer's compact output, byte for byte, which is
what the classic engine stores and what the migration's hash verification compares.

Start-up refuses a database where one connection cannot see another backend's `backend_xid` in
`pg_stat_activity`, the view the running set reads (the role needs `pg_read_all_stats`), and an event table whose identity has a `CACHE`
other than 1. Either would break the watermark (§4).

**Schema ownership.** With `SchemaOwnership.COMPONENT` (`essentials.schema.mode=create`) `EventLogSchema` runs the
contributor's statements under the bootstrap lock. With `HARNESS` the schema harness applies the fixed tables, and
registering an aggregate type hands its two tables to the contributor (`aggregateTypeDdl()`), which either passes them to
the harness' sink or, when the harness does not create the schema, creates them directly (and warns once that the
application's role then needs the right to create tables). `EventLogSchemaContributor.scriptOnly()` changes that last
case for schema mode `emit`: an aggregate type's tables then go into the emitted script and nothing is written to the
database. The starter calls it in `emit`.

## 3. Append

An append runs on the caller's `Connection` and never commits; the caller's transaction decides. Each aggregate type
has three statements generated once (`EventLogStorage.TableStatements`): `appendAfter` (expected version known),
`appendNewStream` and `appendWithoutExpectation`. Each is one statement:

```sql
WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x),
     h AS (UPDATE el_<type>_heads SET version = version + n
            WHERE aggregate_id = ? AND version = expected AND (SELECT true FROM xid)
            RETURNING version - n AS v)
INSERT INTO el_<type>_events (...)
SELECT ?, h.v + e.n, ... FROM h CROSS JOIN unnest(?::uuid[], ...) WITH ORDINALITY AS e(..., n)
RETURNING global_order, event_order
```

- **The xid comes first (I4).** The `xid` CTE gives the transaction its xid before the identity default draws a
  `global_order`, so no writer ever holds an order while it is invisible to the running-set probe. Keep the CTE even
  though the head write would assign an xid too; `EventLogAppendXidWindowIT` guards it.
- **A lost optimistic check consumes no order (I10).** The head update matches no row, `h` is empty and the insert
  draws nothing; the engine raises `StreamAppendConflictException`.
- `event_order` is computed on the server from the head, so a stream is one row lookup and one index range.
- Every array is bound as `text[]` and cast, so payload bytes round-trip exactly.

After a commit, the application calls `EventLog.afterCommit(types)`; in practice `EventLogCommitCallback` (a foundation
`UnitOfWorkLifecycleCallback`) or the processor module's `EventLogCommits` (the Spring path) does it. That wakes the
readers (§6).

## 4. The tail reader

One `ReaderLoop` per `EventLog`: one platform thread and one held connection, with a `TailReader` per aggregate type that
has a subscription. Subscriptions do not read the tail themselves; they receive batches from the loop (§5).

**Cursor and holes.** The cursor is the highest `global_order` read. A gap below it is a **hole**: an order drawn by a
transaction that has not committed yet, or that rolled back. A gap wider than `holeRangeThreshold` (a sequence jump) is
kept as a range instead of single values.

**Candidates and the running set.** A hole can only be resolved by proving that nothing can still commit into it. The
**combined probe** is one statement that reads the tail above the cursor, re-reads the outstanding holes (`= ANY`, and a
range join while a range is outstanding, always as a `UNION ALL` branch, never an `OR`), and reads the **running set**:

```sql
SELECT ARRAY[count(*)::text] || coalesce(array_agg(backend_xid::text) FILTER (WHERE backend_xid IS NOT NULL AND datid = (SELECT oid FROM pg_database WHERE datname = current_database())), '{}')
FROM pg_stat_activity
```

Only writers connected to this database count: no backend of another database can insert into this one's tables, and
counting them let one long writing transaction anywhere on the server hold every type's watermark back. The backend
count stays server-wide, because it measures the cost of the scan, not the writers. A prepared transaction
(`PREPARE TRANSACTION`) has no backend and never appears in the running set; appending inside one is not supported.

**The lock check of a stuck candidate.** The running set still counts every writer of this database, so a transaction
that holds an xid without ever touching the type's event table (a long migration or batch job, a session idle in a
transaction after a write, an append of another type) would hold the type's candidates, and every `CONTIGUOUS`
subscriber of the type, for as long as it runs. An order is drawn only by the identity default of an `INSERT` into the
event table, which takes a `RowExclusiveLock` on the table when the statement starts and keeps it until the transaction
ends. So once the oldest candidate has waited `TailReader.LOCK_CHECK_AFTER` (1 s) on the running set alone, the reader
also runs `EventLogStorage.lockers`: the xids of the transactions of this database that hold a `RowExclusiveLock` on the
event table, read from `pg_locks` alone (its own transaction-id lock gives each holder's xid; `pg_locks` is not cached
for the rest of a transaction, as `pg_stat_activity` is). A candidate none of whose writers is among them retires as it
would against a later running set: each of its writers has ended or drew nothing at or below its highest read, and the
hole read that follows runs after the check. Writers that never inserted into the table then hold the watermark for
about a second; an append that drew a hole still holds it until it ends. The check costs a pass over the lock manager,
so the probe never runs it, and it runs at most once per `LOCK_CHECK_AFTER` while a candidate waits.
`EventLogLockCheckIT` (with the mutant `LOCK_CHECK_WITHOUT_LOCKERS`); the age of the oldest candidate is the gauge
`eventlog.reader.oldest_candidate_age`.
The filter is on `datid` against an init plan, not on `datname`, which would keep the view's join to `pg_database`.
Measured on PostgreSQL 18.4 with 109 backends (10 holding an xid), `pgbench -M prepared`, one client: 0.117 ms per call
unfiltered, 0.093 ms filtered on `datname`, 0.086 ms on `datid` - no cost, within noise.

The running set's statement reads the `pg_stat_activity` view rather than `pg_stat_get_activity(NULL)` behind it. The
view has no filter and only left joins on unique keys (`pg_database`, `pg_authid`), which the planner removes, so both
return the same count and xids through the same single function scan. Prepared, the view measured cheaper on
PostgreSQL 17.5 with 106 backends (10 holding an xid), 5 000 server-prepared calls per form, `pg_stat_statements` mean
execution: `SELECT RUNNING_SET` 39-40 µs through the view against 64 µs through the function as superuser, 26 against
51 µs as a plain role; the combined probe with a 100-row tail 55-66 against 85-88 µs (superuser) and 41 against
68-88 µs (plain role). The gap is the allocator state of the per-call backend-status copy, not the SQL
(`docs/design/durable-queue-measurements.md` §3.18). The start-up check reads the same view.

The running set is read during execution, after the statement's snapshot was taken (I11), so any writer that could
still commit into a hole is in it. A probe creates a `Candidate (cursor, running set)` when a hole lies above every
outstanding candidate, and **retires** each candidate whose set is disjoint from the new running set. A retired candidate
resolves every hole at or below it; those are re-read once and their rows published before the watermark moves past them.

**Watermark.** `min(cursor, lowest unresolved hole − 1)`. Everything at or below it is final: committed and visible, or
never going to exist.

**Start.** A reader starts at the head, read with the running set in one statement. If a writer is running, an initial
candidate `(head, running set)` makes the watermark unknown until it retires, since that writer may still commit below
the head.

**The probe clock.** The running set costs a scan of every backend's status, so it is rationed. Without holes, a type's
next running set waits

- `max(max(probeBackoffMin, backstopInterval), min(probeBackoffMax, cost × 100 × max(1, backends / 10)))` after an
  unwoken read, and
- `max(wokenProbeMin, min(probeBackoffMax, cost × 100 × max(1, backends / 10)))` after a wake,

where `cost` is the last probe's own execution time and `backends` how many backends it saw
(`EventLogSettings.probeWait`/`wokenProbeWait`; defaults `probeBackoffMin` 1 s, `probeBackoffMax` 10 s,
`wokenProbeMin` 10 ms). The factor 100 aims the probes at about 1 % of one core across the cluster; `backends / 10`
estimates how many readers share the database.

**`holeProbeWaitMax`.** While a hole, range or candidate of the type is outstanding (`TailReader.holesWaiting()`), the
wait above is capped at `holeProbeWaitMax` (`EventLogSettings.holeProbeWait`, default 100 ms, property
`essentials.event-log.hole-probe-wait-max`). A hole retires only on a running-set read and holds the watermark, and so
every `CONTIGUOUS` subscriber of the type, until it does. Under load the uncapped rule put the next probe seconds away
(§13). While holes wait, the 1 % budget is therefore a soft target. Two further rules keep the cost honest: a flush that
read several types keeps the previous probe cost rather than its own wall time (`ReaderLoop.probeCostAfter`), and a
read without the running set still re-reads outstanding holes (`rereadTail`).

**Flushes.** When two or more types are due at once (at most `MAX_FLUSH_TYPES`), their tail statements go out in one
pgjdbc flush with exactly one running set, last. The flush shares one implicit transaction, in which backend status is
cached, so the single running set is read after every tail statement's snapshot.

Every reader statement is checked under `EXPLAIN (GENERIC_PLAN)` to be index-only (`EventLogGenericPlanIT`), since they
run as prepared statements with generic plans.

## 5. Subscriptions

### 5.1 Delivery

A `Subscription` runs on its own virtual thread and holds no connection between pages. It reads catch-up pages
`(P, W]` from its resume point `P` up to the watermark `W` on a borrowed connection, then asks the loop to **attach**
at the loop's quiescent point, **bridges** `(P, C]` up to the loop's cursor `C`, and from then on receives the loop's
batches through its `Inbox`. The loop never waits on an inbox: a batch that would overflow `subscriptionBufferSize`
detaches the subscription, which catches up again. A read failure restarts the reader and closes every attached
inbox (`READER_RESTART`). `HandledSet` (the resume point plus runs of handled orders) removes duplicates between catch-up,
bridge and attached delivery. Catch-up statements are capped per database per JVM (`CatchUpPermits`,
`catchUpPermits`, I15).

### 5.2 Consistency modes

| | `CONTIGUOUS` | `FRESH` |
|---|---|---|
| Delivers | Only at or below the received watermark, in `global_order` | Every committed event as soon as it is seen |
| Order | Strict global order of the aggregate type | Per aggregate only (I2) |
| Holes | Waits; events above a hole are held back (`HeldBack`) | Delivers above them, re-reads `HandledSet` gaps on a bridge, picks late commits up afterwards |
| Usual progress model | `IN_HANDLER_TRANSACTION` (page transactions) | `PERIODIC` |

A `FRESH` subscription that has waited one backstop interval under an unknown watermark attaches anyway and hands its
unhandled orders of `(P, C]` to the loop as holes of the initial candidate (the gap hand-off), so a reader restart
under a long writer does not stall it. `CONTIGUOUS` waits for a known watermark.

### 5.3 Progress models and page transactions

The handler kind passed to `EventLog.subscribe(SubscriptionDefinition, …)` chooses the progress model:

- **`PERIODIC`** (`DeliveryHandler`, `PeriodicHandler`): the resume point is saved every `progressSaveInterval`, so
  delivery is at least once.
- **`IN_HANDLER_TRANSACTION`** (`PageHandler`): each page of at most `pageSize` events runs in one transaction that
  the engine opens through a `PageTransactionSource` (`EnginePageTransactions` by default; the processor module adds
  `UnitOfWorkPageTransactions` and `SpringPageTransactions`). The handler gets a guarded connection or resource that
  refuses commit and rollback; the engine issues the progress update **last**, guarded by the reset epoch, the fence and
  `resume_from = expected`, then commits. A crash anywhere leaves the projection equal to applying each event once (I12).
  A source other than the engine's is checked against the engine's database identity at subscribe.

### 5.4 Filters

`EventTypeFilter` and a tenant filter are applied in SQL on catch-up and bridge reads and in memory on attached batches;
filtered-out rows count as handled. A filtered catch-up scans adaptive windows (`CatchUpWindow`: double on a short page,
quarter on a cancelled statement) so a sparse type does not read the whole table in one statement, and the opt-in
indexes (§2) enable a skip-ahead probe. `el_progress.filter_digest` records the filter; a change is logged and stored,
never refused.

### 5.5 Failures and parking

Failures are classified. An **I/O failure** of the engine (anything thrown outside the handler, or an invalid page
connection) retries the same page indefinitely with a
doubling backoff (`retryBackoffInitial` to `retryBackoffMax`), never shrinking or parking. A **handler failure**
shrinks the page (`StepDown`: halve, keep the size inside the suspect range), retries the failing event alone
`retryAttempts` times, then **parks** it in `el_parked_events`; `ParkEventException` parks at once (I14). What the handler throws on a live page connection is a handler failure even
when it is I/O-classified (SQLState `08*` or `SQLTransientConnectionException` in the cause chain): before 0.62.0
shipped such a failure was retried forever and stalled the subscription (mutant `HANDLER_IO_RETRIED_FOREVER`). An
`Error` from the handler is a handler failure too (`AssertionError`, `StackOverflowError`), except the ones that say
nothing about the event: `OutOfMemoryError`, `InternalError`, `UnknownError` and `LinkageError` (a broken deployment),
also anywhere in the cause chain, are engine failures, retried with the backoff and never parked, so a sick JVM does not
park healthy events (`Subscription.isEngineFailure`, `SubscriptionFailureClassificationTest`). A parked
event holds its aggregate: later events of that aggregate are parked `blocked_by` it (`ParkedEvents`), and the rest of
the stream continues. Retry and discard requests are served by the subscription's own thread between pages. A serve
hands the head (on a retry) and the held events to the handler one by one, at most `ErrorSettings.pageSize` per
transaction; the next unhandled event then becomes the head with a `RETRY` request of its own, so a long-held backlog
never runs in one transaction and a crash between serves loses nothing (`ParkedEvents.rehead`). A held event that fails
(or throws `ParkEventException`) becomes the head with one attempt counted, after the events before it are applied
(in-transaction: in a second transaction, the first having rolled back), and the events after it wait behind it. Before
0.62.0 shipped, one failing held event failed every retry and every discard of its head, and the aggregate could never
be released.

**Automatic retry** (I17). The engine retries a parked aggregate by itself, forever, unless its head is **terminal**: a
head that failed with `ParkEventException` waits for an operator. The latest failure decides: a terminal head whose
operator retry fails with an ordinary exception retries by itself from then on, and a retrying head whose retry throws
`ParkEventException` becomes terminal. `el_parked_events` keeps the schedule on the head row: `retries` (failed
retries), `next_retry_at` (null for a held row and a terminal head), `terminal`, `last_failed_at`. A park stores
`next_retry_at = now() + parkedRetryInitial`; each failed retry (operator or automatic) stores
`now() + min(parkedRetryMax, parkedRetryInitial * 2^retries)`; a new head (`rehead`) starts over - at once if it has not
run (the rest of a backlog), after `parkedRetryInitial` if it failed. Defaults 1 s doubling to 5 min, engine-wide on
`EventLogSettings` and per subscription on `ErrorSettings`. `ParkedEvents` keeps the schedule in memory (due-ordered,
earliest at hand), loaded with the held set: `PARKED_HEADS` returns each head's remaining wait measured by the database's
clock, turned into a local `nanoTime` due time, so a new owner continues the schedule and node clocks do not matter. The
due check in `Subscription.serveParkedRequests()` runs after the operator's parked check: while an aggregate is held and
a non-terminal head is due, it queues an automatic request for at most four due heads per pass (none already queued), on
the same queue and serve path as the operator's. It sends no statement while nothing is due, and nothing at all while
nothing is held. The wait loops sleep at most until the earliest due time while something is held, so an idle
subscription retries on time. A serve that does not succeed moves the head's due time forward even if its guarded update
matched no row, so it never runs in a tight loop. Only the running owner serves: a standby holds no schedule, a reset
reloads it, a lost lease clears it.

**Late locking.** Every in-transaction serve, operator and automatic alike (`Subscription.releaseParked`), reads the
head's and held rows unlocked, runs the handler in the page transaction with no lock on the progress row, and only then
locks the progress row under `FENCE_GUARD` (`PARKED_SERVE_GUARD`) and after it the parked rows - the order a reset
locks in, or a reset racing the serve deadlocks - checks that the set is unchanged (else it rolls back and the serve
runs again later), deletes or re-heads the rows, saves and commits. `LEASE_RENEW` is one statement for every lease of
the JVM and waits on any progress row it renews, so a serve that held the row across a slow handler stalled every
exclusive subscription's renewal (mutant `SERVE_LOCKS_PROGRESS_FIRST`, `EventLogLeaseIT`). A reset that arrives while
the handler runs goes ahead at once; the serve's late lock sees the new epoch and rolls back.

### 5.6 Leases and ownership

An **exclusive** subscription (key `(subscriber, type, '')`) runs on an engine lease, not a `FencedLockManager`. One
`LeaseKeeper` per `EventLog` (a virtual thread while any exclusive subscription exists) sends one `LEASE_RENEW` for all
owned leases and one `LEASE_CLAIM` for all standbys every `leaseRenewalInterval`, on one borrowed connection. A claim
draws a new fence from `el_lease_fence_seq`; the owner id is `[nodeName/]instance`. The delivery thread checks a local
deadline (`confirmedAt + leaseLocalStop`) before every handler call, save, park and page; past it, it suspends until a
renewal confirms, so an owner that cannot renew stops before its lease can expire on the server (I16). Every write
binds the `FENCE_GUARD` (epoch, fence, owner), so a stale owner's write never lands after the new owner's (I5); a reset
bumps the epoch and wins over a concurrent save (I6). Exclusive pages set `idle_in_transaction_session_timeout` to the
lease duration. Both lease statements take their keys as a `MATERIALIZED` CTE that carries every value the row is
checked against, so their plan stays linear whatever the statistics say (`EventLogLeasePlanIT`).

A **per-instance** subscription carries a non-blank instance name and no lease; the admin API can delete the row of a
retired instance.

## 6. Wake-ups

Three tiers wake a reader; a wake only sets a flag (`ActiveType.wake`) and unparks the loop, and never moves reader state.

1. **Same `EventLog`:** `afterCommit(types)` sets the wake flags directly.
2. **Other JVMs** (`crossJvmWakeUp`, default on): `WakeNotifier` coalesces committed types and sends one
   `SELECT pg_notify('el_wake', p) FROM unnest(?::text[]) p` at most every `notifySendMin` (2 ms) on a borrowed
   connection; a failed send is dropped. `WakeListener` (one platform thread and one `LISTEN` connection per `EventLog`,
   only while the loop has an active type) drains notifications through foundation's `PostgresqlNotificationDrain`:
   pgjdbc's peek cool-down pinned (`PINNED_PEEK`), or a short query poll if the pin fails. It ignores its own instance's, and sets flags. After a failure it reconnects and wakes
   every type.
3. **Backstop:** every type is read at least every `backstopInterval` (1 s), so a committed event is delivered within
   that interval with every notification lost (I7).

`wokenReadMin` (off by default) can coalesce woken reads of one type; a deferred read is never dropped or pushed past
the backstop.

## 7. The processor module

- **`ViewProjection`** (`CONTIGUOUS`, exclusive, page transactions) and **`EventReactor`** (`FRESH`, exclusive,
  periodic progress) are built from a builder (`ViewProjection.builder(subscriber, type)…build(eventLog, serializer)`)
  with lambda dispatch by payload class (`Dispatch`). They catch nothing: retries, shrinking and parking are the engine's.
  Deserialization runs inside the handler call, so a bad payload is a handler failure: an unknown event class or JSON
  that does not fit the class retries, while a payload or metadata text that is not JSON at all throws
  `ParkEventException` and parks as terminal at once.
  `ViewProjection.rebuild(Truncate)` and `EventReactor.reset(StartFrom)` reset one subscription.
- **Annotated base classes**, for porting the classic processors by superclass swap: `EventLogViewProcessor` (one
  `ViewProjection` per aggregate type, `rebuild()` with the `onRebuild(ProjectionContext)` hook, clear and reset in one
  transaction), `EventLogReactor` (one `EventReactor` per type) and `EventLogInboxProcessor` (a `CONTIGUOUS` subscription
  that forwards each page into the processor's `Inbox` inside the page transaction, so each event is forwarded exactly
  once, and the inbox calls the `@MessageHandler` methods as the classic `EventProcessor` did) and
  `EventLogPartitionedViewProcessor` (the same reading side, queuing each page's handled events on `<name>:queue` with
  one `queueMessages` in the page transaction, keyed `<aggregateType>|<aggregateId>` as the classic
  `PartitionedViewEventProcessor`; every instance consumes the queue, one unit of work per message; `rebuild()` refuses
  while another instance consumes, then runs `onRebuild`, the queue purge and the progress reset in one page-source
  transaction; the shard-owned `purgeQueue` joins the unit of work in progress, so clear, purge and reset commit
  together). All four dispatch to
  foundation `@MessageHandler` methods (`MessageHandlerDispatch`), take `EventLogProcessorDependencies` and are foundation
  `Lifecycle`s. There is deliberately no `EventStoreSubscriptionManager` adapter (decision D3): classic processors must
  be ported. Each subscribes with an `EventTypeFilter` of its handlers' payload types (`FQCN:` names) when
  all are final classes outside the JDK, else `all()` (a supertype handler also takes subclasses); the protected
  `eventTypeFilter()` hook overrides it.
- **Page transaction sources.** `UnitOfWorkPageTransactions` binds the page's JDBI unit of work and installs
  `PageGuardTransactionHandler`, so a handle whose page is open refuses commit and rollback. `SpringPageTransactions`
  opens a `REQUIRES_NEW` transaction whose status refuses `setRollbackOnly`.
- **`EventLogEventStore`** is a `ConfigurableEventStore<EventLogAggregateEventStreamConfiguration>` for repositories,
  snapshots and closing books: appends, stream loads, global-order loads, `findEvent(s)`, causation lookups. It does not
  poll (`pollEvents` throws) and has no subscription manager.
- **`EventLogCommits`** wakes the readers after a Spring or unit-of-work commit.

## 8. Admin

`EventLog.admin()` returns `EventLogAdmin` (`DatabaseEventLogAdmin`), which answers from the database on any node: list
and get subscriptions with their lag, page parked events, reset (also inside a caller's transaction), retry or discard a
parked head, and delete a retired per-instance row. A retry or discard is stored as a request on the parked row; the node
that runs the subscription serves it at its next parked check (at most every `parkedCheckInterval`), or at once when it
runs on the answering node. The engine's own automatic retries (§5.5) take the same queue and serve path. A serve locks
the progress row before the parked rows, in the order a reset does, so the two cannot deadlock.

An in-transaction subscription's serve hands every held event of the aggregate to the page handler inside one
transaction, but takes its locks only after the handler returned (late locking, §5.5): the keeper's renewal never waits
on a serve's handler, and a reset during the handler goes first and rolls the serve back. A `PERIODIC` subscription's serve holds no lock while its
handler runs (`releaseParkedPeriodic`): a short transaction reads the held events, the handler runs with nothing locked
(the local deadline checked before each call), and a second short transaction - progress row first again - checks
that nothing moved, deletes the rows and saves. A reset or lease change in between refuses that step and the events
run again, the periodic handler's at-least-once contract.

The processor module wraps it as the `EventLogSubscriptionsApi` SPI (`DefaultEventLogSubscriptionsApi`, roles
`SUBSCRIPTION_READER`/`SUBSCRIPTION_WRITER`). It is one admin contract in three places: the SPI, the seven rows of tag
`event-log-subscriptions` in `EssentialsAdminApiSpec`, and `EventLogSubscriptionsController` in
`spring-boot-starter-admin-api`. The admin console's *Event-log subscriptions* view calls only those seven operations.

## 9. The starter

`EventLogAutoConfiguration` does nothing unless `essentials.event-log.enabled=true`. It declares the `EventLog`
(started in its bean method, since aggregate types register while the context is built), `EventLogSchemaContributor`
(`scriptOnly()` in `emit`; dropped with `initialize-schema=false`), `EventLogEventStore` as `essentialsEventStore`, the
unit-of-work factory, event bus, serializer, mapper and causation beans as the classic starter builds them, a
`PageTransactionSource`, `EventLogProcessorDependencies` and `EventLogProcessorLifecycle` (starts `ViewProjection` and
`EventReactor` beans after refresh, stops them before the `EventLog`). `EventLogImportFilter` removes the classic
starter's `EventStoreConfiguration` while the property is on, and only that, so the snapshot, closing-books and
aggregate admin configurations run on the adapter. `EssentialsEventLogProperties` mirrors `EventLogSettings` one to one
(`EssentialsEventLogPropertiesTest`). In `validate`, the `EventLog` bean validates the engine's schema before it starts,
so a missing one fails as `SchemaValidationException`.

**Observability.** `EventLogObserver` (every method a no-op default, called on the reader loop, delivery or lease keeper
thread) is the engine's only metrics seam. The engine wraps the observer it is given (`SafeEventLogObserver`), so a
callback that throws is caught and logged (a WARN at most once a minute) and never ends the reader loop, a delivery thread
or the lease keeper. `onStopped` is the last call for a subscription - after `stop()`, a failed start or a fatal error,
once its delivery thread is done - so an observer that keeps per-subscription state drops it there. Each delivery thread makes one `onStarted`/`onStopped`
pair; the Micrometer observer counts them per (subscriber, type), since instances share their meters, and removes the
meters when the last one stops. A lease callback after that registers nothing again. `MicrometerEventLogObserver` binds it: a lag gauge (`essentials.eventlog.subscription.lag`
= the type's reader cursor, reported by `onReaderCursor` once per tail read, minus the saved resume point from
`onProgressSaved`, plus one), counters `subscription.failed`/`parked`/`lease_lost` and `lease.renewal_failed`, and a
`subscription.page` timer by outcome, fed by the `nanos` variants of `onPageCommitted`/`onPageFailed` (one `nanoTime` at
the page's start; the commit side reuses the `lastSaveNanos` read). Meters are cached per subscription, never looked up
per call. The starter installs it when a `MeterRegistry` exists and no application observer is declared
(`EventLogMetrics`, resolved when the `EventLog` bean is built, so no ordering against the metrics auto-configuration),
and declares the classic starter's `RecordExecutionTimeEventStoreInterceptor` and (with `management.tracing.enabled`)
`MicrometerTracingEventStoreInterceptor`, which the adapter runs as an enricher, so `traceparent` lands in the stored
metadata. Per-event TRACE goes through `DeliveryTrace`, on the logger `…eventlog.Subscription.<sanitised subscriber>`,
created once per subscription and guarded by `isTraceEnabled()`. `EventLogViewProcessor` and `EventLogReactor` bind
`CausationContext` around each handler call, as the inbox and partitioned processors do (`ProcessorCausationIT`).

## 10. Migration

`postgresql-event-log-migration` moves aggregate types one way from the classic tables to the event log's, in the same
database, and only reads the classic tables. `Migration` runs `preflight()` (read-only checks), `prepare()` (registers
the types and marks them `COPYING` in `el_migration`, which keeps every event-log node off their tables), repeated
online `copyEpoch()`s (copying what can no longer change, in batches whose checkpoint commits with them), then, with
every classic node stopped, `cutover()`: one transaction under `SHARE` locks that copies the delta, writes heads, sets the
identity sequences, imports resume points and marks the types `CUT_OVER`. `verify()`, `verifyAll()` and
`verifyAfterCutover()` compare windows by hash. Imported subscriptions start at or below their old resume point and
receive every event that filled one of their open transient gaps (I8); `trustOldResumePoint` keeps the old value
instead. `MigrationCli` runs the same from a YAML file. There is no way back after the cut-over except a backup.

## 11. Invariants

Defined in the redesign's "Invariants" section; each has an IT and, where it makes sense, a mutant the IT must catch
(`WatermarkMutant`, `ProcessorMutant`, `MigrationMutant`, set only through package-private seams).

| # | Invariant | Where it is kept |
|---|---|---|
| I1 | No loss: every committed event reaches every active subscription at least once | Watermark, hole re-reads, `HandledSet` |
| I2 | Per-aggregate order within one subscription incarnation | Tail reader, gap re-read after a hand-off |
| I3 | A resume point never passes an order that could still be delivered | Resume = handled prefix from received watermarks only |
| I4 | xid before value | The append's `xid` CTE (§3) |
| I5 | Fencing: a stale owner's write never lands after the new owner's | `FENCE_GUARD` on every write (§5.6) |
| I6 | Reset wins over a concurrent save | `reset_epoch` compare-and-set |
| I7 | Delivered within the backstop with every notification lost | Backstop read (§6) |
| I8 | Migration copies every event exactly and imported subscriptions miss nothing | `Migration`, resume-point import (§10) |
| I9 | A slow subscription never delays another | The loop never waits on an inbox (§5.1) |
| I10 | A lost optimistic check consumes no `global_order` | Head CTE before the insert (§3) |
| I11 | The running set is read after the statement's snapshot | Combined probe; one running set last in a flush (§4) |
| I12 | `CONTIGUOUS` never delivers above the watermark; page transactions apply each event once | `HeldBack`, progress update last (§5.3) |
| I13 | Filtered catch-up never skips a late matching event | Catch-up only to the watermark; F4 start; detach after overflow |
| I14 | A failing event is isolated, retried, parked; no other event is lost or applied twice; a parked aggregate is applied in order when its retry succeeds, by an operator or automatically | `StepDown`, parking, automatic retry (§5.5) |
| I15 | Catch-up reads never exceed the per-database cap | `CatchUpPermits` |
| I16 | An owner that cannot renew stops before its lease expires; a renewal never extends a lease whose fence changed | Local deadline, `LEASE_RENEW` keyed by fence (§5.6); late locking of parked serves (§5.5) |
| I17 | A non-terminal parked head of a running subscription is retried no later than `parkedRetryMax` plus one loop pass after it is due; a terminal one only on request | `ParkedEvents` schedule, due check, wait loops cut to the earliest due time (§5.5) |

## 12. Settings

Every `EventLogSettings` value is a property under `essentials.event-log.*`; the consumer table is in the LLM doc's
"Settings" section. The ones that shape the mechanisms above: `backstop-interval` (1 s), `page-size` (100),
`subscription-buffer-size`, `catch-up-permits`, `hole-range-threshold`, `probe-backoff-min`/`-max`,
`woken-probe-min`, `hole-probe-wait-max` (100 ms), `cross-jvm-wake-up`, `woken-read-min`, `notify-send-min`,
`lease-duration`/`lease-renewal-interval`/`lease-local-stop` (15 s / 4 s / 7 s), `retry-attempts` (3),
`retry-backoff-initial`/`-max`, `parked-retry-initial`/`-max` (1 s / 5 min), `progress-save-interval` (1 s).

## 13. Measured

The phase-10 gate ran the trading demo on both engines (`trading-demo.engine=classic` with CDC against `event-log`),
2 000 customers, 10 business days with a 600 orders/s burst at each open, one and then two instances, palindrome order,
in the devcontainer (8 cores). Full tables: [phase 10 §12](../archive/event-store-engine-phase10.md#12-gate-results-10-d).

**First pass (2026-11-18, `cae69a1c8`): failed.** The engine was correct (no lost or duplicated view rows, every order
reached its outcome, no `ERROR`) and completed the same work, but booked latency outside the bursts had a p99 6.0× the
classic engine's with one instance and 3.8× with two, and one two-instance run failed the steady-state check. The cause:
rolled-back appends to Orders leave 20-35 holes per burst, and a hole retired only on a running-set probe whose wait,
`cost × 100 × max(1, backends / 10)`, came to seconds under load, stalling every `CONTIGUOUS` Orders subscriber for
0.5-6 s behind a hole already known to be a rollback.

**Fixes**, each committed on its own:

- `0f6506116`: the demo's inbox forwarding moves a page per transaction (correct and kept, but not the cause).
- `25ec28bce`: a multi-type flush no longer counts as the probe cost.
- `16a3cbdb7`: the probe wait is capped at `holeProbeWaitMax` while holes wait (§4).

**Second pass (2026-11-19, `16a3cbdb7`): passed.**

| | One instance | Two instances |
|---|---|---|
| Completed operations vs classic | equal | 1.01× |
| Booked p99 outside bursts | 507 ms against 446 ms (1.14×; accepted by the maintainer) | 1 878 ms against 2 060 ms (0.91×) |
| Booked p50 outside bursts | about a tenth of classic's | 31 ms against 232 ms |
| Lost or duplicated view rows | none | none |
| Steady state | pass | pass (burst recovery 2-9 s against 0-9 s) |
| Statements per order | about 1.2× | 1.22× |
| PostgreSQL CPU per order | 15.8-16.4 ms against 13.0 ms (about 1.2×) | 20.1 against 15.4 ms (1.30×) |
| WAL per order | about 0.85× | 0.81× |

So the cost line is about 1.2-1.3× the classic engine's PostgreSQL CPU per order and about 22 % more statements, for
about 19 % less WAL (no CDC inbox rows). The extra statements are mainly a progress `UPDATE` and an
`idle_in_transaction_session_timeout` `set_config` per page, and `pg_notify` calls with two instances. The remaining
one-instance tail is in the second after a burst window closes; its cause is suspected (a slower backlog drain at the
end of a burst) but not established.

Not measured: connections held, the admin page under load, whole-machine CPU, and the projections other than the four
checked.
