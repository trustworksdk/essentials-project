# Marten Event Store: Design Summary and Optimization Ideas

This document describes how Marten's event store is built on PostgreSQL, with an eye to ideas that a Java/PostgreSQL event store can borrow.

It is based on Marten `master` at 9.53.0 (commit `854b36b2c`). File paths are relative to `src/Marten/` unless stated otherwise.

Marten has two layers:

- **Marten** owns everything PostgreSQL-specific: the SQL, the schema, the append paths, high-water-mark detection, event loading and progress tracking.
- **JasperFx.Events**, an external NuGet package, holds the generic engine: the daemon loop, leadership and distribution of work, and the aggregation runtime. Marten's `ProjectionDaemon` is a thin subclass of it.

---

## 1. The big picture

```
                 ┌────────────── one transaction ──────────────┐
 command ──► FetchForWriting ──► append events ──► inline projections ──► COMMIT
            (1 round trip:       (1 function call     (docs written in the
             version + snapshot   per stream)          same transaction)
             + tail events)
                                        │  mt_events_sequence (global seq_id)
                                        ▼
                         ┌──────────────────────────────┐
                         │  mt_events / mt_streams      │
                         └──────────────────────────────┘
                                        │
   High-water-mark detector ── finds the highest contiguous committed seq_id
                                        │
                                        ▼
   Async daemon shards ── page by seq_id (floor, HWM] ──► projection output
                          + progress CAS in the SAME transaction
```

Core ideas:

- There is **one global ordering**, `seq_id`, taken from a PostgreSQL sequence. There is **one per-stream ordering**, `version`, guarded by a unique index.
- Consumers never read past the **high-water mark (HWM)**. All of the gap-handling machinery exists to keep the HWM both safe and moving.
- A consumer's progress is **committed atomically with its output**, using an optimistic compare-and-set.

---

## 2. Schema

### `mt_events`

Defined in `Events/Schema/EventsTable.cs`.

| Column | Notes |
|---|---|
| `seq_id bigint` PK | Taken from `mt_events_sequence`. A plain sequence with no `CACHE` tuning. |
| `id uuid` | The event id. A unique index on it is opt-in (`EnableUniqueIndexOnEventId`). |
| `stream_id` | `uuid` or `varchar`; the type is chosen per store. There is no surrogate key: a string key is the PK/FK itself. |
| `version bigint` | The position within the stream. |
| `data jsonb NOT NULL` | The payload. |
| `bdata bytea NULL` | Optional binary payload; `data` then holds `'{}'`. Each row is decoded according to whether `bdata` is null, so no migration is needed. |
| `type varchar` | The logical event-type name, used for filtering. |
| `mt_dotnet_type varchar` | The CLR type. |
| `timestamp timestamptz`, `tenant_id varchar`, `is_archived bool` | |
| optional | `correlation_id`, `causation_id`, `headers jsonb`, `user_name`, `is_skipped`, `tags hstore` |

Indexes and constraints:

- A **unique index on `(stream_id, version)`** is the real optimistic-concurrency guarantee. `tenant_id` and/or `is_archived` are added to it in those modes.
- A foreign key to `mt_streams` with `ON DELETE CASCADE`. It is **dropped** under tenant partitioning, because a parent-level FK breaks attaching partitions.
- **No other secondary indexes by default.** The `(type, seq_id)` index is opt-in (`EnableEventTypeIndex`). It helps a lot when a projection consumes only a few event types, and it costs some write latency.
- `bdata` is pinned to a fixed position in the SELECT list, so the reader can choose the deserializer before it reads the payload.

### `mt_streams`

Defined in `Events/Schema/StreamsTable.cs`.

- Columns: `id`, `type` (aggregate alias), `version`, `timestamp`, `created`, `tenant_id`, `compacted_version`, `is_archived`.
- Under conjoined (shared-table) multi-tenancy, the PK is `(tenant_id, id)`, with tenant first.
- This is the row that is **locked** (`FOR UPDATE`) for pessimistic and exclusive writes. Marten never uses advisory locks for streams; it uses them only for leadership in the async daemon.
- v9 removed the snapshot columns. Snapshots are now ordinary projection documents.

### Bookkeeping tables

| Table | Purpose |
|---|---|
| `mt_event_progression(name PK, last_seq_id, last_updated, …)` | One row per projection or subscription shard, plus special rows: `HighWaterMark`, `HighWaterAllocationFence` and `HighWaterStuckGap`. |
| `mt_high_water_skips` | An audit of seq_id ranges that the HWM skipped. |
| `mt_event_projection_floors` | A start floor per projection. It survives rebuilds and version bumps. |
| `mt_streams_identity` | Global id uniqueness when `mt_streams` is partitioned. |
| `mt_natural_key_<agg>` | Maps business keys to streams (§9). |
| `mt_event_tag_<type>`, `mt_dcb_tag_version` | Dynamic Consistency Boundary tags (§9). |

---

## 3. Write path

The v9 default is `AppendMode = QuickWithServerTimestamps`. Every write path runs through `NpgsqlBatch`, made of separate commands rather than `;`-joined text. Operations are paged at `UpdateBatchSize` (default 500), and all pages run in **one transaction**.

### 3.1 Quick append: server-side function, one call per stream

Implemented in `Events/QuickEventAppender.cs` and `Events/Schema/QuickAppendEventFunction.cs`.

The client calls `select mt_quick_append_events(stream, stream_type, tenant, event_ids uuid[], types varchar[], dotnet_types varchar[], bodies jsonb[], bdatas bytea[], [metadata arrays...], [tag arrays...], expected_version)`.

- The arguments are **column arrays**, one array per column.
- JSON is serialized straight to UTF-8 into pooled arrays.

The function body runs these steps in order:

1. **Version check before any `nextval`:**

   ```sql
   select version, is_archived into event_version, stream_is_archived
     from mt_streams where id = stream [and tenant_id = ..] [for update];
   if coalesce(event_version,0) != expected_version then
     raise exception ... using errcode = 'MT003';
   ```

   A writer that loses the race therefore **consumes no sequence numbers** and leaves no gap (#4749).
   - `FOR UPDATE` is opt-in (`UseExclusiveLockOnConcurrentAppends`). Without it, two READ COMMITTED writers can both pass the check. The loser then hits the unique index (error 23505) *after* calling `nextval`, which does leave a gap.
2. **New stream:** insert the `mt_streams` row with its **final version** straight away. This avoids an insert followed by an update, which would write a second tuple and more WAL (#5456).
3. Loop over the events. For each one: `nextval`, `version + 1`, `INSERT INTO mt_events`, and optionally the tag inserts.
4. **Existing stream:** `update mt_streams set version = greatest(version, event_version)`.
   - The `greatest()` guard fixed a lost-update bug: an empty append wrote back a stale version under READ COMMITTED (#5539).
5. Return `ARRAY[final_version, seq1, seq2, …]`. The client stamps each event's version and sequence from that array, with no extra read.

The trade-off is that inline projections running *before* commit see `Version` and `Sequence` as 0. The docs report Quick appends as roughly **40–50% faster** than Rich.

> Not worth copying as-is: the function inserts **row by row in a PL/pgSQL loop**. A set-based
> `INSERT … SELECT … FROM unnest(...) WITH ORDINALITY` with `nextval` per row would likely be faster
> for multi-event appends.

### 3.2 Rich append: client assigns numbers, two round trips

Implemented in `Events/RichEventAppender.cs`.

- **Round trip 1**, a single batch:
  - `select nextval('mt_events_sequence') from generate_series(1, N)` pre-fetches every sequence number for the whole session at once.
  - One `select … from mt_streams where id = ?` per stream.
- The client then assigns versions and sequences.
- **Round trip 2:** per-event `INSERT`s, plus a guarded `update mt_streams set version = $new where id = $id and version = $expected returning version`. Zero rows means a concurrency exception.
- Downside: every failed save burns its pre-fetched sequence numbers, which leaves gaps (§4).

### 3.3 Bulk append: COPY

Implemented in `Events/BulkEventAppender.cs`. This path is for seeding and migrations.

1. Pre-allocate sequence numbers with `generate_series`.
2. Assign versions and sequences on the client.
3. `COPY mt_streams … FROM STDIN BINARY`.
4. `COPY mt_events … FROM STDIN BINARY`, re-opened every 1,000 rows.
5. Upsert the high-water mark with `GREATEST`.

It skips concurrency checks and inline projections.

A streaming variant fetches sequence numbers block by block. It has to close and reopen COPY at each block, because the connection can't run a SELECT while COPY is open. With a "preserve source sequence" mode it ends with `setval(seq, greatest(last_value, max))`.

### 3.4 Concurrency control

| Mode | Mechanism |
|---|---|
| Optimistic (default) | Read `mt_streams.version` during `FetchForWriting`, then check it at save time: inside the function (MT003) for Quick, or with a guarded UPDATE for Rich. **Backstop: the `(stream_id, version)` unique index.** |
| Exclusive | `FetchForExclusiveWriting` begins the transaction early and runs `select version from mt_streams where id = ? for update`. A lock timeout becomes `StreamLockedException`. |
| Consistency assertion without new events | `AlwaysEnforceConsistency` queues `select version …` as an assertion. |

Unique-index violations are identified by **constraint name or shape**, never by parsing the error's `Detail` field: Npgsql redacts `Detail` unless `Include Error Detail=true`. JDBC has the same concern, so carry the stream id from your own context instead of parsing it out of the error.

---

## 4. Sequence gaps

A PostgreSQL sequence is **not transactional**. A rolled-back append, or a writer that lost a concurrency race after calling `nextval`, leaves a permanent hole in `seq_id`. An in-flight transaction that has called `nextval` but not yet committed leaves a hole that *looks identical*. If a consumer reads past an in-flight hole, it **misses that event forever**. If it waits on a dead hole, it **stalls forever**.

Marten uses four layers of defense.

### 4.1 Don't create gaps

- In the function, the version check runs before `nextval`; `FOR UPDATE` on the stream row is opt-in.
- Starting a stream goes through the same path, so a collision fails before any sequence number is used.

### 4.2 Fill the gaps you created: tombstones

Implemented in `EventGraph.Processing.cs`.

- When `SaveChanges` fails after sequence numbers were drawn, Marten writes **`Tombstone` events at exactly those seq_ids**. They go into a well-known tombstone stream, with `version = seq_id` so the unique index never collides.
- The write is asynchronous, on a fresh connection, with retries.
- Gaps from failures the client *knows about* therefore close within milliseconds instead of waiting for a timeout.

### 4.3 Detect the high-water mark safely

Implemented in `Events/Daemon/HighWater/`.

- The HWM is the highest `seq_id` below which every number is committed or proven dead. It only advances over contiguous numbers.
- **Every reading is one SQL statement.** Under READ COMMITTED, each statement takes its own snapshot. If the "leading gap", "interior gap" and `max()` probes run as separate statements, a commit landing between them can advance the mark over an in-flight number (#4953).
- The gap walk:

  ```sql
  select
    (select min(seq_id) from mt_events where seq_id > :start) as first_after,
    (select seq_id from (select seq_id, lead(seq_id) over (order by seq_id) as next_seq
                         from mt_events where seq_id >= :start) g
     where next_seq is not null and next_seq - seq_id > 1
     order by seq_id limit 1) as gap_edge,
    (select max(seq_id) from mt_events where seq_id >= :start) as max_seq
  ```

- The statistics read also takes `last_value`/`is_called` from the sequence and `pg_snapshot_xmax(pg_current_snapshot())` in the same statement.

### 4.4 Skip stuck gaps using transaction evidence, not a timer

- A gap that is older than `StaleSequenceThreshold` (3 s by default) is **not** skipped on time alone. A liveness probe first checks the PostgreSQL catalogs, in one statement, for a transaction that could still commit into the gap:
  - transactions holding `RowExclusiveLock` on `mt_events*` that started before the gap was observed;
  - client backends with a transaction open since before the observation;
  - in-progress xids from `pg_snapshot_xip(pg_current_snapshot())` below the xmax captured at observation time, restricted to this database and including `pg_prepared_xacts`;
  - it excludes `idle in transaction` sessions that went quiet before the "allocation fence", and the daemon's own advisory-lock sessions.
- If something is alive, it **waits**. A configurable cap (off by default) forces a skip after a longer time.
- If the gap is proven dead, it skips the whole span in one move. It skips only up to the sequence ceiling recorded at observation time, so numbers reserved later are not skipped.
- The fence and "stuck since" timestamps are stored as rows in `mt_event_progression`, so they survive restarts and changes of leader.
- Each skip is recorded in `mt_high_water_skips` through a compare-and-set function.
- The older wall-clock path is still available. It never advances to within 32 of `last_value`.

The HWM is persisted with an upsert function (`mt_mark_event_progression('HighWaterMark', seq)`). Optionally, appends call `pg_notify('mt_events_appended')` and the detector LISTENs for it, with polling as the fallback.

---

## 5. Read path: aggregates

Implemented in `Events/EventStore.FetchForWriting.cs` and `Events/Fetching/*`.

`FetchForWriting<T>(id)` caches a **fetch plan** per (aggregate type, id type). Every plan is a **single round trip**: one `NpgsqlBatch` containing `select version from mt_streams where id = ? [for update]` plus the reads below.

| Snapshot lifecycle | What is in the batch |
|---|---|
| **Live** (no snapshot) | The version, plus every event in the stream, folded in memory. |
| **Inline** (snapshot written in the same transaction as the events) | The version, plus a load of the snapshot document. No events, because the snapshot is always at the stream head. |
| **Async** (snapshot written by the daemon, may lag) | `begin isolation level repeatable read read only` → version → snapshot → **tail events** → `end`. |

The tail-events query for the Async plan:

```sql
select … from mt_events d left outer join <snapshot_table> a on d.stream_id = a.id
 where (a.mt_version is null or d.version > a.mt_version) and d.stream_id = $1
 order by d.seq_id
```

This gives strongly consistent writes on top of an eventually consistent projection. The `REPEATABLE READ` bracket ensures the snapshot and the tail come from the same MVCC snapshot.

Other optimizations:

- **Session identity map** (`UseIdentityMapForAggregates`, the v9 default). The aggregate fetched for writing is reused by the inline projection at commit, which saves a snapshot load. A later `FetchLatest` in the same session can then return it with zero database reads. The catch: don't mutate the aggregate between fetch and save.
- **Node-local LRU cache** of aggregates (`CacheAggregatesForWriting<T>(size)`).
  - Entries are taken out atomically (`TryTake`), so only one caller gets an entry, and written back only after a successful commit.
  - With the Async plan, a cache hit turns the query into `where stream_id = $1 and version > $cachedVersion`.
  - The version read and the concurrency check are **never** skipped. The team measured that skipping them saved about 1.4%, so they removed that option.
- **`FetchManyForWriting`**: N streams in one batch.
- **`StreamLatestJson`**: streams the snapshot's raw `jsonb` bytes straight to an HTTP response, with no deserialization.

---

## 6. Async daemon

### 6.1 Loading events

Implemented in `Events/Daemon/Internals/EventLoader.cs`.

```sql
select d.<cols>, s.type from mt_events d join mt_streams s on d.stream_id = s.id
 where d.seq_id > :floor and d.seq_id <= :high_water
   and d.type = any(:types) [and s.type = :stream_type]
   and d.is_archived = false [and d.is_skipped = false]
 order by d.seq_id limit :batch_size
```

- The ceiling is always the HWM.
- The event-type filter is pushed into SQL. `tenant_id` is inlined as a literal so the planner can prune partitions.
- **A page that matched few rows still moves progress up to the HWM.** A projection that consumes rare event types doesn't crawl through its range.
- **Adaptive fallback when the query hits a statement timeout:**
  1. normal query;
  2. skip-ahead, which probes `… order by seq_id limit 1` (it avoids `min()`, which degraded to a bitmap scan);
  3. fixed windows of 10,000 seq_ids.
- Concurrency is capped **per database**: 4 concurrent event loads and 4 concurrent batch writes by default.

### 6.2 Progress = compare-and-set, in the same transaction as the output

```sql
update mt_event_progression set last_seq_id = :ceiling, last_updated = transaction_timestamp()
 where name = :shard and last_seq_id = :floor      -- 0 rows → ProgressionProgressOutOfOrderException
```

- The progress update is queued into the **same batch and transaction** as the projection's document writes. An outbox, if present, is enlisted in the same transaction just before COMMIT.
- The result is **exactly-once in database state**: output and progress commit together, and the compare-and-set rejects a node that has lost its lock but is still running.
- Side effects outside the database are **at-least-once**.

### 6.3 Coordination

| Mode | Behavior |
|---|---|
| Solo | Everything runs on one node, with no locks. |
| HotCold, single database | One **advisory lock per shard**. The lock id is derived from the schema and shard name. "First node to grab the lock runs that shard." |
| HotCold, database per tenant | One lock per database. All of that database's projections run on one node, to save connections. |
| ExternallyManaged | Distribution is delegated to something else, for example Wolverine. |

- Locks are session-scoped `pg_try_advisory_lock` by default. Behind PgBouncer in transaction mode you can choose `pg_try_advisory_xact_lock`, which keeps a transaction open.
- A **monitored lock** detects that the connection holding a lock has died, for example after a failover. Otherwise two nodes could both run a shard.
- The docs admit that hot/cold balancing is weak, since everything tends to land on the first node. Better balancing is in a commercial add-on.

---

## 7. Projections

| Type | When it runs | Notes |
|---|---|---|
| Inline | Inside `SaveChanges`, same transaction as the events | Strongly consistent. Its writes are added to the same batch as the event inserts. |
| Async | In the daemon | Each page loads all the aggregates it touches with one `LoadMany(ids)` call. Each shard caches aggregates (MRU, `CacheLimitPerTenant`). `BatchSize` can be tuned, e.g. 500–5000. |
| Live | When read | No storage. |

Error handling:

- During normal running, apply errors, serialization errors and unknown events are **skipped into a dead-letter table** by default.
- During rebuilds, every error stops the rebuild.
- An error that isn't skipped pauses the shard.

Rebuilds:

- The projection's tables are truncated, then it replays.
- Optional binary-COPY writes for rebuild batches that only insert.
- **Optimized rebuilds** replay stream by stream, most recently modified first, so hot aggregates come back first.
- **Blue/green:** bumping `ProjectionVersion` writes to a new table (`…_{version}`) under a new progress row. The new version catches up alongside the old one, then you switch over.

Subscriptions (`src/Marten/Subscriptions`) use the same loader and the same progress-in-same-transaction model. They can start from the present, a sequence number, a timestamp, or a persisted floor ("first run").

---

## 8. Data lifecycle and scale-out

- **Archiving** sets `is_archived = true` on the stream and its events. Default queries filter it out, and appending to an archived stream fails.
- **`UseArchivedStreamPartitioning`** LIST-partitions `mt_events` and `mt_streams` (plus the tag and natural-key tables) on `is_archived`. Hot queries then only touch the small active partition.
  - Archiving becomes an explicit move between partitions: insert…select into the archived partition, then delete from the active one, in FK-safe order.
  - `is_archived` has to be added to every PK, unique index and FK, and the global id uniqueness this loses is restored by `mt_streams_identity`.
- **`UseTenantPartitionedEvents`** LIST-partitions by `tenant_id`, with a **sequence per tenant**, an HWM per tenant (found with one vectorized `unnest(:tenants)` + `LATERAL` query), progress per tenant, and rebuilds per tenant.
  - Costs: the FK from events to streams, and the global ordering across tenants.
  - It can't be combined with archive partitioning.
- **Stream compaction** folds a stream into an aggregate. It overwrites the stream's last event in place with a `Compacted<T>` snapshot event, keeping its version and seq_id, and deletes the earlier events. An optional hook can copy them to cold storage first. `mt_streams.compacted_version` records how far compaction has gone.
- **Hard delete** is one CTE: delete tags, then events, then identity, then the stream.

---

## 9. Dynamic Consistency Boundary (DCB) and natural keys

**DCB** is consistency across streams based on tags.

- Tags are stored either in **one table per tag type** (`(value, seq_id)` PK, FK to events) or in an **`hstore` column with a GIN index**. The hstore form makes OR queries over two tags about 13× faster, at a small cost for single-tag existence checks.
- **The consistency check uses a version-counter row instead of a predicate lock.** `mt_dcb_tag_version(tag_table, tag_value, tenant_id, version)`:
  - **Reading:** read the counters *first*, then the events, in the same batch. With that order, a race can only cause a spurious conflict, never a missed one.
  - **Writing:** for each tag, sorted to avoid deadlocks:

    ```sql
    insert … values (…, captured+1)
    on conflict (tag_table, tag_value, tenant_id) do update set version = version + 1
    where mt_dcb_tag_version.version = $captured returning 1   -- no row → DcbConcurrencyException
    ```

  - Every tagged append also increments its counters, in sorted order.
  - This works at READ COMMITTED, with no SERIALIZABLE isolation and no advisory locks. `version` is deliberately **unindexed** so its updates stay HOT; fillfactor is configurable.
  - The check is coarse: any append carrying the tag conflicts, regardless of event type.

**Natural keys** (`mt_natural_key_<agg>`):

- A business key is claimed with a single `insert … on conflict do update set <CASE that keeps the existing owner unless the claim is legitimate> returning`.
- There is no read before the claim, so there is no race between checking and claiming.

---

## 10. Other performance choices worth knowing

- **No prepared statements by default.** The team measured auto-prepare and found no significant gain (430→365 µs, within noise). The risks they cite are generic plans with skewed `tenant_id`, memory per connection, and runtime DDL invalidating plans.
- **Connections are used and closed** per operation by default (no sticky connections).
- **Event storage code is hand-written, not generated at runtime**, with one class per append mode chosen once when the store is built.
- **Upcasting happens on read only.** Stored rows are never rewritten. An old type name maps to a transform delegate.
- **Binary serialization per event type** (MemoryPack or MessagePack into `bdata`) for hot streams.
- **`bigint` for version and seq** is the default in v9.

---

## 11. Optimization checklist for our Java store

These are ordered roughly by expected value. Each one asks whether we already do this.

**Write path**

1. **One round trip per append.** A server-side function per stream, taking **column arrays** (`uuid[]`, `text[]`, `jsonb[]`, `bytea[]`) and returning `[final_version, seq…]`. In JDBC: `connection.createArrayOf(...)`, or a single `INSERT … SELECT FROM unnest(?, ?, ?)`. Improve on Marten by making the insert **set-based** instead of a PL/pgSQL loop.
2. **Check the expected version before `nextval`**, so writers that lose the race leave no sequence gaps. Offer `FOR UPDATE` on the stream row for hot streams.
3. **Make the `(stream_id, version)` unique index the source of truth.** Map violations by constraint name. Don't rely on parsing the message's `Detail`.
4. **Insert a new stream row with its final version**, not insert-then-update. Guard version write-backs with `greatest()`.
5. **Batch everything in a unit of work** into one JDBC batch per page, inside one transaction (`reWriteBatchedInserts=true` with pgjdbc, or a pipeline).
6. **Use binary COPY for imports and migrations** (pgjdbc `CopyManager`), with sequence numbers allocated in bulk through `generate_series`.

**Consumers (projections and subscriptions)**

7. **Never read past a high-water mark.** Compute it in **one statement** (one snapshot).
8. **Tombstone the seq_ids of failed appends** right away, instead of waiting out a timeout.
9. **Prove a gap is dead from `pg_locks`, `pg_stat_activity` and `pg_snapshot_xip`** before skipping it. Store the fence timestamps durably. Pure wall-clock skipping is the classic way to lose events silently.
10. **Commit consumer progress as a compare-and-set** (`where last_seq_id = :floor`) **in the same transaction** as the consumer's output. Put an outbox in that transaction too.
11. **Push the event-type filter into SQL**, advance progress to the HWM on pages that matched few rows, and consider a `(type, seq_id)` index plus a skip-ahead or window fallback for consumers that only read a few event types.
12. **Wake consumers with LISTEN/NOTIFY**, with polling as the fallback (pgjdbc `PGConnection.getNotifications(timeout)`).
13. **Cap concurrent loads and writes per database**, separately from the connection pool size.
14. **One advisory lock per shard**, using a *monitored* connection so a failover can't produce two leaders. The progress compare-and-set is the fence behind it.

**Reads**

15. **Fetch the version, snapshot and tail events in one batch**, wrapped in `REPEATABLE READ READ ONLY` when the snapshot is written asynchronously.
16. **An in-process LRU of aggregates per node**, taken out atomically and written back on commit, while still checking the version.
17. **Reuse the aggregate from the command handler** for the inline snapshot write at commit.

**Data volume**

18. **Partition by `is_archived`** (hot/cold) or **by tenant** with per-tenant sequences. Budget for the PK, FK and uniqueness fallout.
19. **Compaction**: replace a stream's history with a snapshot event, and archive the deleted events elsewhere.
20. **Binary payloads** for hot event types, chosen per row so no migration is needed.
21. **Keep frequently updated counter rows unindexed** on the updated column so updates stay HOT.

## 12. Things in Marten not to copy blindly

- **The row-by-row PL/pgSQL insert loop** in the quick-append function. Set-based `unnest` is the obvious improvement.
- **Rich mode's client-side `nextval` pre-fetch.** It doubles the round trips and creates gaps on every failed save.
- **Wall-clock gap skipping.** Marten moved away from it by default because it can skip events whose transaction is merely slow.
- **Hot/cold leader distribution.** "First node grabs everything" balances poorly.
- **The DCB tag-version check is coarse.** A hot tag becomes a write hotspot.
- **Tenant partitioning loses the event→stream FK and the global ordering across tenants.**

## 13. Where to look in the source

| Topic | Files |
|---|---|
| Schema | `Events/Schema/EventsTable.cs`, `StreamsTable.cs`, `EventProgressionTable.cs`, `EventGraph.FeatureSchema.cs` |
| Quick append function | `Events/Schema/QuickAppendEventFunction.cs`, `Events/QuickEventAppender.cs`, `Events/Operations/QuickAppendEventsOperationBase.cs` |
| Rich and bulk append | `Events/RichEventAppender.cs`, `Events/EventSequenceFetcher.cs`, `Events/BulkEventAppender.cs` |
| Concurrency | `Events/EventStore.ConcurrentAppends.cs`, `Events/EventVersionConstraint.cs` |
| Tombstones | `Events/EventGraph.Processing.cs`, `Internal/Sessions/DocumentSessionBase.SaveChanges.cs` |
| High-water mark | `Events/Daemon/HighWater/HighWaterDetector.cs`, `GapDetector.cs`, `GapLivenessProbe.cs` |
| Loader and progress | `Events/Daemon/Internals/EventLoader.cs`, `Events/Daemon/Progress/UpdateProjectionProgress.cs` |
| Coordination | `Events/Daemon/Coordination/ProjectionCoordinator.cs` |
| Fetch plans | `Events/Fetching/FetchLivePlan*.cs`, `FetchInlinedPlan*.cs`, `FetchAsyncPlan*.cs` |
| DCB | `Events/Dcb/*`, `Events/Schema/DcbTagVersionTable.cs` |
| Docs | `docs/events/optimizing.md`, `appending.md`, `archiving.md`, `dcb.md`, `projections/async-daemon.md` |
