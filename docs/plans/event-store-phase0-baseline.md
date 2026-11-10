# Event store redesign, spike P0.0: baseline of the current engine

Date: 2026-11-10. Part of phase 0 of [`event-store-subscription-redesign.md`](event-store-subscription-redesign.md)
(§17, §18). This records what the current engine costs before any new engine code exists, so later phases are
compared against measured numbers rather than a reading of the code.

## 1. Setup

**Code.** `CurrentEngineBaselineSpikeIT` in `components/postgresql-event-store/src/test/java/.../postgresql/spike/`
(with `RoundTripCountingProxy`). Like the P0.1/P0.2 spikes it is tagged `spike` and runs only with
`-Dspike.run=true`, so the normal build does not run it. Raw output, including the top twelve normalised
statements of every measurement window, goes to `components/postgresql-event-store/target/spike-results/p0-0-baseline.txt`.

**Command** (the whole suite, two runs of every shape, about 32 minutes):

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=CurrentEngineBaselineSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
```

One shape alone: `-Dit.test=CurrentEngineBaselineSpikeIT#idle` (`steady`, `burst`, `concurrentWriters`, `append`).
Knobs: `-Dspike.baseline.runs` (default 2), `-Dspike.baseline.timeScale` (scales the warm-up and window lengths;
default 1.0), `-Dspike.baseline.image` (default `postgres:17.5`).

**Machine.** 8 CPUs (arm64, Docker Desktop linuxkit VM), Docker memory 15.6 GiB, JDK 25 (Temurin), failsafe JVM max
heap 6 392 MB, PostgreSQL 17.5 (the `postgres:17.5` image the spikes use). Nothing else was building; the VS Code
Java language server was running, as it always is in the devcontainer. The plan asks for A/B runs from worktrees
outside `/workspace`; for a single baseline the run was made in-tree.

**Database.** A fresh container per run of each shape, so every shape has a fresh event table, fresh gap tables and
fresh statistics. `shared_preload_libraries=pg_stat_statements` (`track=top`, `track_utility=on`), statistics reset
at the start of each measurement window. Testcontainers' default `fsync=off` was kept, as in every other IT of the
module; this lowers commit latency compared with a production server and applies equally to any later A/B. The
application connects as separate roles — `writer` for appends, `reader` (and `reader2` for a second instance) for
the event store and subscription manager that consume — so `pg_stat_statements` separates the write path from the
read path; the observing connection uses a third role and is excluded. WAL is the `pg_current_wal_lsn()` difference
over the window.

**Engine wiring.** As `spring-boot-starter-postgresql-event-store` wires it by default:
`PostgresqlEventStore` with `SeparateTablePerAggregateTypePersistenceStrategy` (`TEXT` ids, `JSONB` payloads, the
standard single-tenant configuration), `PostgresqlEventStreamGapHandler` (the starter's
`use-event-stream-gap-handler=true`; the builder's own default is the no-op handler), `EventStoreSubscriptionManager`
with polling batch size 10, polling interval 100 ms, resume points saved every 1 s, the jittered polling optimizer
the starter builds (`JitteredEventStorePollingOptimizer(name, 100, 50, 2000, 0.1)`: +50 ms per empty poll up to
2 000 ms, ±10% jitter), notify-polling off, CDC off, `PostgresqlFencedLockManager` with a 15 s lock timeout and
4 s confirmation interval, `PostgresqlDurableSubscriptionRepository`, schema mode `CREATE`. A Hikari pool with
Spring Boot's defaults (10 connections) per instance. Serializer from `EssentialsJSONEventSerializers.create()`.
Events are the module's `OrderEvent` test types with two metadata entries, a correlation id and a cause id.

Differences from a Spring application, all believed immaterial to the statement counts: the unit of work factory is
`EventStoreManagedUnitOfWorkFactory` (the Spring `SpringTransactionAwareEventStoreUnitOfWorkFactory` lives in
another module and is not on this module's test classpath); the subscription observer is the no-op one rather than
the Micrometer one; the causation enricher is absent (no causation context exists in these tests anyway).

**Counting caveat.** `pg_stat_statements` recorded a `BEGIN` for every transaction but almost no `COMMIT` or
`ROLLBACK` (one or two per window), although the proxy shows the commit being sent (see §2.5). So every count below
that includes utility statements is one statement per transaction short of what the client sends; "non-utility"
counts exclude `BEGIN`/`SET`/`SHOW` and are exact.

## 2. Results

Every shape was run twice. Both runs are shown; anything whose spread is large enough to be noise is flagged.

### 2.1 Idle

No writes in the window. 100 events seeded, then a 45 s warm-up after every subscription had caught up (the
optimizer needs about 41 s of empty polls to reach its 2 s ceiling), then a 60 s window.

| Subscriptions | Instances | Statements/s, run 1 / run 2 | Non-utility/s | Gap-table rows written | WAL from statements |
|---|---|---|---|---|---|
| 1 non-exclusive | 1 | 1.45 / 1.50 | 0.97 / 1.00 | 0 | 0 B |
| 10 non-exclusive | 1 | 14.90 / 15.00 | 9.93 / 10.00 | 0 | 0 B |
| 50 non-exclusive | 1 | 75.05 / 74.65 | 50.03 / 49.77 | 0 | 0 B |
| 10 exclusive | 1 | 17.70 / 17.65 | 12.47 / 12.43 | 0 | 11 KB (lock confirms) |
| 10 exclusive | 2 | 22.75 / 22.75 | 15.00 / 15.00 | 0 | 11 KB (lock confirms) |

- An idle poll is one transaction of three counted statements (four sent): `BEGIN`,
  `SELECT MIN(global_order) … WHERE global_order >= ?` and the subscriber's transient gaps
  (`SELECT … FROM transient_subscriber_gaps WHERE aggregate_type = ? AND subscriber_id = ?`). Each subscription
  polls about every 2 s, so the cost is **0.5 polls/s, 1.5 counted (2 sent) statements/s per subscription, exactly
  linear in the number of subscriptions**.
- Exclusive subscriptions add the lock manager: the holder confirms each lock every 4 s
  (`UPDATE fenced_locks …`, 150/min for 10 locks), and the second instance, which polls nothing, tries to acquire
  each lock every 4 s (`BEGIN` + `SELECT * FROM fenced_locks WHERE lock_name = ?`, 300 statements/min for 10 locks).
  A second instance therefore costs about 0.5 statements/s per exclusive subscription; a non-exclusive one would
  double the poll cost instead (not measured separately; it is the 1-instance row times two by construction).
- An idle read path writes nothing: no gap rows, no WAL from any statement. The total WAL position still moved
  about 92 KB per minute in every idle window, independent of the subscription count; that is server background
  (checkpointer, autovacuum), not the engine.
- More than two instances: not measured (deferred, see §3).

### 2.2 Steady

10 non-exclusive subscriptions, one writer appending one `OrderAdded` to a new aggregate per unit of work with no
expected order given (so the append includes the last-event-order lookup), a fixed rate, 10 s warm-up, 60 s window.
Latency is from the moment the appender's unit of work returned after commit to the handler's receipt, same JVM,
over all ten subscriptions' deliveries.

| Rate | Latency p50 (ms) | p99 (ms) | max (ms) | Statements per appended event (writer / reader) | Reader statements per delivery | WAL per event |
|---|---|---|---|---|---|---|
| 10 ev/s | 49.8 / 49.1 | 108.2 / 107.9 | 116.3 / 142.4 | 3.0 / 68.3 and 3.0 / 68.1 | 6.83 / 6.81 | 1 248 / 1 255 B |
| 100 ev/s | 50.5 / 50.1 | 106.5 / 106.6 | 118.9 / 142.9 | 3.0 / 10.4 and 3.0 / 10.4 | 1.04 / 1.04 | 1 030 / 1 033 B |

- Latency is the polling interval: p50 is half of 100 ms and p99 just above it, at both rates.
- At 10 ev/s the read path costs **68 statements per appended event** against 3 on the write path. A poll that
  returns events is followed at once by another, which is empty and then sleeps, so each subscription makes about
  two polls per event; every poll is a transaction with `BEGIN`, the transient-gap read and a range read
  (`SELECT * FROM orders_events WHERE global_order BETWEEN ? AND ?`), and every empty one adds the `MIN` probe.
  Resume points add one `UPDATE durable_subscriptions` per subscription per second while they move.
- At 100 ev/s polls fill better and the read path falls to about one statement per delivery.
- WAL per event is the event row itself (about 950 B, see §2.5) plus resume-point updates and the background WAL,
  which weighs more at the lower rate.
- Max latency (116-143 ms) differs between runs; treat it as noise.

### 2.3 Burst

One subscription stopped after its first event; 100 000 events appended (1 000 units of work of one aggregate and
100 events, 4 writer threads); the subscription started again.

| Run | Append | Catch-up | Rate | Reader statements | Per event | Polls (`BEGIN`s) | Heap baseline / peak | WAL during catch-up |
|---|---|---|---|---|---|---|---|---|
| 1 | 0.7 s | 2.3 s | 42 753 ev/s | 37 513 | 0.375 | 12 506 | 25 / 104 MB (+79) | 552 B |
| 2 | 0.7 s | 2.6 s | 38 805 ev/s | 37 513 | 0.375 | 12 506 | 29 / 103 MB (+73) | 552 B |

- Catch-up reads 8 events per range read on average (batch size 10), three counted statements per poll, so
  **12 500 transactions for 100 000 events**. It writes nothing but three resume-point updates.
- Heap is the whole JVM's used heap sampled every 50 ms, so it is coarse; the peak stayed around 100 MB.
- Catch-up time differs by 12% between runs; the statement counts are identical.

### 2.4 Concurrent writers

8 writer threads, each at most 50 units of work per second (one `OrderAdded` to a new aggregate, no expected
order), 10% of them rolled back after the insert, for 30 s; one non-exclusive subscription; then 135 s more so the
transient gaps reach the 120 s give-up threshold.

| Run | Committed | Rolled back = holes | All committed delivered | Late deliveries | Transient gap rows inserted / deleted | Permanent gap rows inserted | In-order delivery p50 / p99 |
|---|---|---|---|---|---|---|---|
| 1 | 10 208 | 1 121 | yes, no duplicates | 2 (1.2 / 2.5 ms) | 1 122 / 1 122 | 1 120 | 71.7 / 278.3 ms |
| 2 | 10 172 | 1 146 | yes, no duplicates | 1 (1.8 ms) | 1 147 / 1 147 | 1 146 | 86.0 / 432.7 ms |

- Every committed event was delivered, once.
- Every rolled-back append left exactly one hole, and **every hole cost the subscriber one transient-gap insert,
  one delete and one permanent-gap insert** 120 s later — about 440 B of WAL per hole for this one subscriber
  (record ~265 KB plus permanent insert ~228 KB for ~1 120 holes). With n subscribers this is paid n times for the
  transient rows. While gaps were open every poll carried them as an `IN (…)` list on the range read.
- Late commits (an event delivered below one already delivered) were rare at this load: 1-2 per run. Those were
  delivered 1-3 ms after their commit returned. Two samples are too few for a latency distribution; the plan's
  "time to delivery of late commits" is therefore not established by this shape, only that late commits are
  delivered and that holes cost rows.
- In-order delivery latency p99 (278 vs 433 ms) differs a lot between runs and is higher than in §2.2 because the
  single subscription lags at times; treat the p99 as noise.

### 2.5 Append

Single thread, one unit of work per iteration, new aggregates, 50 warm-up iterations per case; 1 000 iterations
(300 for 100 events, 500 for 5 aggregates). Round trips were counted in a separate pass of 100 units of work
through a TCP proxy that counts client flights (bytes the client sends after it last received something), so the
proxy does not affect the latency figures. Driver: pgjdbc 42.7.14 with its defaults, `reWriteBatchedInserts=false`,
`prepareThreshold=5`.

| Case | p50 (ms) | p99 (ms) | Counted statements per UoW | Non-utility | Round trips per UoW | WAL per event |
|---|---|---|---|---|---|---|
| 1 event, no expected order | 0.19 / 0.20 | 0.42 / 0.29 | 3.01 / 3.01 | 2.00 | 3.04 / 3.01 | 983 / 982 B |
| 1 event, expected order given | 0.14 / 0.14 | 0.21 / 0.19 | 2.00 / 2.00 | 1.00 | 2.00 / 2.00 | 980 / 990 B |
| 10 events, 1 aggregate | 0.29 / 0.32 | 0.36 / 0.40 | 12.00 / 12.00 | 11.00 | 3.00 / 3.00 | 961 / 963 B |
| 100 events, 1 aggregate | 1.34 / 1.58 | 3.17 / 1.90 | 102.00 / 102.00 | 101.00 | 3.00 / 3.00 | 963 / 963 B |
| 5 aggregates × 10 events | 1.39 / 1.39 | 1.58 / 1.62 | 56.00 / 56.00 | 55.00 | 11.00 / 11.00 | 954 / 948 B |

- The JDBI `PreparedBatch` of N single-row `INSERT … RETURNING` statements is **N executions but one network round
  trip**: pgjdbc sends the whole batch before a single sync. 1, 10 and 100 events cost the same three round trips:
  `BEGIN` with the last-event-order lookup, the insert batch, `COMMIT`.
- Giving the expected event order removes the lookup: one round trip and one statement fewer, p50 0.19 → 0.14 ms.
- Several aggregates per unit of work cost two round trips per aggregate (lookup, batch) plus the commit.
- WAL is about 950-990 B per event in every case; batching does not change it.
- The p99 of the 100-event case (3.17 vs 1.90 ms) differs between runs; noise.

**OCC contended.** 8 writers on 10 pre-created aggregates for 20 s, each unit of work reading the aggregate's last
event order (`loadLastPersistedEventRelatedTo`) and appending one event after it.

| Run | Attempts | Appended | OCC conflicts | Identity values burned | per 1 000 attempts | per 1 000 successful appends | Counted statements per attempt |
|---|---|---|---|---|---|---|---|
| 1 | 870 537 | 593 740 | 276 797 | 276 797 | 318 | 466 | 2.68 |
| 2 | 963 741 | 657 312 | 306 429 | 306 429 | 318 | 466 | 2.68 |

Every lost race burned exactly one `global_order` value — a hole every subscriber of the aggregate type then waits
out for 120 s and records as rows (§2.4). Absolute rates are inflated by `fsync=off`; the ratio is what matters.

## 3. Not measured in P0.0

- More than two instances, and a non-exclusive subscription on two instances (deferred; the latter is two copies
  of the one-instance cost by construction).
- §17's rare-type catch-up, restart storm, long writer, projection throughput and command-latency shapes, and the
  trading-demo shape (deferred to the phases that need them).
- JDBI versus raw pgjdbc (spike P0.6).
- §17's 1 event/s steady rate (10 and 100 events/s were run), and the `CONTIGUOUS`/`FRESH` modes, which only exist in the new engine.
- Other PostgreSQL majors; only 17.5 was used.

## 4. Consequences for the design

- **§13.1's order of gains holds, and the read path dominates by more than an order of magnitude at low rates.**
  At 10 events/s with 10 subscriptions the read path issues 68 statements per appended event against 3 on the write
  path; idle cost is 2 statements/s sent per subscription per instance, all of it transactions that read nothing.
  A shared, autocommit tail reader (§4, §6, §13.2) attacks exactly this: the targets for phases 2 and 3 are fewer
  than ~7 reader statements per delivery at 10 ev/s and ~1 at 100 ev/s, and an idle cost flat in the number of
  subscriptions instead of 1.5 counted statements/s each.
- **The read path writes only when there are holes**, but then it writes per hole and per subscriber: one transient
  insert, one delete and one permanent insert, ~440 B WAL per hole per subscriber, plus a resume-point update per
  subscription per second while it moves. Removing the gap tables (§4) removes the former; the per-second resume
  point update is §8's subject.
- **Round trips on the write path are fewer than §2.2 feared.** The `PreparedBatch` is already a single round trip
  however many events it holds, so the single-statement append (§5.1) cannot save round trips for one aggregate;
  for one aggregate its gain must come from the removed last-event-order lookup (one round trip, about 0.05 ms or
  25% of a one-event append here) and from statement count and execution time, and for several aggregates from
  sending them in one round trip instead of two each (11 round trips for 5 aggregates today). P0.4 should be judged
  on that basis, and on holes.
- **Holes under contention are large**: 466 burned identity values per 1 000 successful appends with 8 writers on
  10 aggregates. That makes §5.1's "a lost race consumes no sequence value" (shown in P0.1) a material gain, not a
  corner case.
- **Freshness today is the polling interval**: p50 ≈ 50 ms and p99 ≈ 107 ms at the starter's 100 ms. That is the
  latency figure the wake-up tiers (§11) and phase 6 are compared against.
- **Catch-up is cheap in statements** (0.375 per event, ~40 000 events/s here) but costs a transaction per 8-10
  events; streaming catch-up (§10.1) mainly changes transactions per event and memory, not throughput, at this size.
