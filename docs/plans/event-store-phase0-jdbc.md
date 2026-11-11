# Event store redesign, spike P0.6: JDBI against raw pgjdbc

Date: 2026-11-11. Part of phase 0 of [`event-store-subscription-redesign.md`](event-store-subscription-redesign.md)
(§13.1, §13.2, §18 row P0.6). Findings, not decisions.

The questions were:

1. For the same statements (A's one-event insert batch, the engine's `loadLastPersistedEventRelatedTo` lookup,
   B's single statement and the tail read `SELECT … WHERE global_order > ? ORDER BY global_order LIMIT ?`), how do
   JDBI and raw pgjdbc compare on client CPU per statement, uncontended latency and contended throughput?
2. Why did today's SQL run about 10% slower through raw pgjdbc (arm C of
   [`event-store-phase0-append.md`](event-store-phase0-append.md) §5) than through the engine and JDBI (arm A), with
   about 9 µs more PostgreSQL CPU per attempt?
3. What does JDBI cost per statement once the configuration is equal?

**Short answer.**

- **Arm C's deficit is not a JDBC difference.** A and C send the same protocol messages to the same server-side
  prepared statements on the same pooled connection. The deficit appears only when the VM's CPUs are allowed to go
  idle. With a 50 µs client busy-wait (SPIN), C is about 10% *faster* than A (6-14% between extremes), and the server CPU per attempt is equal.
  With only a 10 µs busy-wait in C, C beats A in NORMAL mode by about 13% (8-18%) at lower server CPU per attempt. The cause is
  the idle and wake-up effect of §9 of the append document: the raw client turns round faster, so the VM idles more
  often, and every round trip pays for waking it.
- **JDBI's own cost is small, a few µs of client CPU per statement**: about +6-8 µs for the one-event insert batch,
  +2-5 µs for the lookup, about +6 µs for B and +3-6 µs for a 100-row tail read. Uncontended p50 latency is +2-9 µs.
  Server execution is identical.
- **One configuration interaction costs a whole round trip.** JDBI's result iterator reads one row ahead. With
  `setFetchSize(1)` inside a transaction, as the engine's `loadLastPersistedEventRelatedTo` does, every single-row
  read takes a second `Execute` round trip. This happens with `findOne()`, `findFirst()` and `first()` alike. The
  cost is about +40 µs per lookup uncontended. Without the fetch size, the JDBI lookup is within 2-6 µs of raw.
- **Under contention, with CPUs kept busy (SPIN)**, raw pgjdbc appends about 15% more per second than JDBI (11-20% between extremes) for
  the lookup + insert unit of work. This holds even with JDBI's extra round trip removed. For lookup + B, the gain is
  11-17%. Each unit of work costs about 9-15 µs less client CPU and 4-6 µs less server CPU. In NORMAL mode the order
  flips for lookup + insert, for the reason in the first point, and lookup + B ties. The 100-row tail read is
  server-bound and ties in both modes.

## 1. Setup

- **Machine.** PostgreSQL 17.5 with `fsync=off`, `pg_stat_statements` loaded. pgjdbc 42.7.14, JDBI 3.55.0,
  HikariCP 7.0.2 with default settings. The client (the test JVM) and the database container share one 8-CPU Docker
  VM.
- **Discipline.** `-Dfailsafe.forkCount=1` and nothing else building. Root fs at 84%; `docker system df` showed no
  containers, so nothing was pruned. Two runs on fresh containers. Palindrome arm order (run 2 rotated by one). 20 s
  contention windows after a 2 s warm-up, fresh table per window, dropped afterwards.
- **Results inside the spread of the runs count as no difference.** Raw output is in
  `components/postgresql-event-store/target/spike-results/p0-6-jdbc.txt` and
  `components/postgresql-event-store/target/spike-results/p0-4-contention.txt` (the last three
  `=== CONTENTION` blocks).

**Code.** All of it is in the `spike` package, gated by `@Tag("spike")` and `-Dspike.run=true`.

- `JdbiVsRawPgjdbcSpikeIT`, new, with three tests:
  - `protocol_trace` logs pgjdbc's wire messages (JUL `org.postgresql` at FINEST) for one warmed-up unit of work of
    arm A and of arm C.
  - `statements` runs each statement through both libraries on one pooled connection, with the same SQL text, the
    same parameter types (`setString`/`setLong` against JDBI `bind(int, String/long)`) and the same 12-column row
    mapping. It uses one thread and 20 000 measured calls per pass after 2 000 warm-up calls. For each call it
    records latency (p50/p90/p99) and the thread's CPU time.
  - `contended` runs 8 threads on 10 aggregates. Each thread holds one connection for the window. The units of work
    are lookup + insert (`UOW_A`), lookup + B (`UOW_B`, with a zero-row result rolled back) and the 100-row tail read
    in autocommit (`TAIL`). Each runs in NORMAL mode and in SPIN mode: a 50 µs busy-wait between lookup and append,
    or between reads, as in `UnattributedCpuSpikeIT`.

  The JDBI variants are:
  - `JDBI`: the engine's usage, lookup with `setFetchSize(1)` and `findOne()`.
  - `JDBI_FIRST`: lookup with `findFirst()`.
  - `JDBI_NO_FETCH_SIZE`: lookup with `findOne()` and no fetch size.

  JDBI transactions use `Handle.begin/commit/rollback`; raw transactions use `setAutoCommit(false)/commit/rollback`.
  Both send `BEGIN` with the first statement and nothing else.
- `ContendedAppendThroughputSpikeIT` gained a SPIN mode: an arm written `ARM@SPIN` busy-waits for
  `-Dspike.contention.spinMicros` (default 50) between lookup and append. The spin is counted inside the lookup's
  wall time.
- `UnattributedCpuSpikeIT`: `sql`, `guardFirst` and the payload constants were made package-private so the new
  spike reuses B's statement (`B`, §9.1 of the append document) and A's `INSERT … VALUES … RETURNING`.

```bash
M="mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dfailsafe.failIfNoSpecifiedTests=false \
   -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true"
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#protocol_trace'                                                     # §2
$M -Dit.test=ContendedAppendThroughputSpikeIT -Dspike.contention.runs=2 -Dspike.contention.seconds=20 \
   -Dspike.contention.arms=A_ENGINE,C_RAW,A_ENGINE@SPIN,C_RAW@SPIN,C_RAW@SPIN,A_ENGINE@SPIN,C_RAW,A_ENGINE  # §3
$M -Dit.test=ContendedAppendThroughputSpikeIT -Dspike.contention.runs=2 -Dspike.contention.seconds=20 \
   -Dspike.contention.spinMicros=10 -Dspike.contention.arms=A_ENGINE,C_RAW@SPIN,C_RAW@SPIN,A_ENGINE      # §3 fix arm
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#statements'                                                         # §4
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#statements' -Dspike.jdbc.stmts=LOOKUP -Dspike.jdbc.libs=JDBI,JDBI_FIRST,RAW
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#statements' -Dspike.jdbc.stmts=LOOKUP -Dspike.jdbc.libs=JDBI,JDBI_NO_FETCH_SIZE,RAW
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#contended'                                                          # §5
$M -Dit.test='JdbiVsRawPgjdbcSpikeIT#contended' \
   -Dspike.jdbc.contended=UOW_A:JDBI_FIRST,UOW_A:RAW,UOW_A:JDBI_FIRST@SPIN,UOW_A:RAW@SPIN
```

## 2. Arm C against arm A: what differs between the arms

The arms were compared item by item:

- **Driver and connection settings: identical by construction.** Arm C does not open its own connection. It
  executes on `uow.handle().getConnection()`, the same Hikari-pooled connection that JDBI and the engine use in the
  same unit of work. `prepareThreshold` (5), `preparedStatementCacheQueries` (256), `binaryTransfer`,
  `reWriteBatchedInserts` (false) and `defaultRowFetchSize` (0) are the pgjdbc defaults in both arms. The Hikari
  configuration is the same object.
- **Autocommit and transaction statements: identical.** Both arms run inside the same
  `EventStoreManagedUnitOfWorkFactory` unit of work. The lookup is the engine's `loadLastPersistedEventRelatedTo`
  in both arms. Only the insert differs.
- **Protocol: identical, message for message.** The pgjdbc trace of the 12th unit of work on a warm pool is the same
  in both arms:
  1. `BEGIN` as a simple query, piggy-backed on the lookup's `Bind`/`Execute(limit=1)` to a named server-side
     statement. It returns `PortalSuspended`.
  2. A second `Execute` on the same portal returns `SELECT 0`.
  3. `ClosePortal`, then the insert, are sent as `Bind` to a named server-side statement + `Describe(portal)` +
     `Execute` + `Sync`.
  4. `COMMIT` is sent through a named statement.

  Both inserts are server-side prepared after the threshold. `pg_stat_statements` folds them into one entry. Neither
  arm sends `SET`, savepoints or extra `Describe`/`Parse` messages per unit of work.
- **Batch handling and `RETURNING`: equivalent.**
  - JDBI's `executePreparedBatch("global_order")` calls `prepareStatement(sql, new String[]{"global_order"})`,
    `addBatch` and `executeBatch`, then reads `getGeneratedKeys()`.
  - Arm C does the same calls by hand. Both SQL texts already end in `RETURNING global_order`, so pgjdbc appends
    nothing.
- **Server cost of the statements: equal.** Insert execution was 14.3 against 15.0 µs per call in §5's run 1. Plans
  were 70 against 80 per window, about one extra custom plan per connection in C. That is about 0.1 µs per attempt
  and does not explain 9 µs.

So no setting exists that would "configure raw pgjdbc to match": it already matches. What differs is what the
client does between round trips. Through the engine, A's append spends about 3 µs more client CPU per attempt than
C's hand-written path: mapping, enrichers, the `AggregateEventStream`, and JDBI.

## 3. The SPIN control and the fix arm

Arms A and C of `ContendedAppendThroughputSpikeIT`. The table shows each window in palindrome order (run 1 / run 2).
"Client CPU" is the writer threads' CPU per attempt. In SPIN mode it includes the busy-wait.

| Arm @ mode | Appended/s (thousands) | PostgreSQL CPU µs/attempt | Client CPU µs/attempt | Machine busy | Append wall µs (successes) |
|---|---|---|---|---|---|
| A @ NORMAL | 35.6, 33.2 / 35.4, 35.5 | 67.9-72.7 | 53.5-57.1 | 88-91% | 50.2-53.6 |
| C @ NORMAL | 32.5, 31.0 / 30.9, 31.2 | 78.3-80.1 | 51.2-52.5 | 81-84% | 48.0-49.8 |
| A @ SPIN (50 µs) | 28.6, 28.3 / 29.1, 28.5 | 58.5-60.7 | 97.6-99.2 | 95% | 47.4-49.5 |
| C @ SPIN (50 µs) | 32.2, 31.5 / 31.8, 30.8 | 58.3-59.7 | 88.5-89.3 | 95-96% | 36.9-38.9 |
| A @ NORMAL | 37.2, 34.9 / 35.6, 37.0 | 65.8-69.2 | 51.3-53.5 | 88-90% | 47.4-49.7 |
| C @ SPIN (10 µs), fix arm | 41.3, 40.2 / 40.8, 40.9 | 62.0-63.1 | 49.5-50.3 | 91-92% | 37.3-38.3 |

(The last two rows come from a separate run with the arms in the order A, C@SPIN10, C@SPIN10, A.)

- **NORMAL reproduces §5.** C appends about 10% fewer events per second and uses 8-12 µs more PostgreSQL CPU per
  attempt. Its client CPU per attempt is *lower*, by 1-5 µs. The machine is less busy (81-84% against 88-91%), so
  more CPUs go idle between requests.
- **SPIN removes the server-CPU gap and reverses the throughput gap.** PostgreSQL CPU per attempt is equal
  (58.3-59.7 against 58.5-60.7 µs). C appends about 10% more per second (6-14%), because its append is about 11 µs shorter on
  the client: 37-39 against 47-50 µs wall per successful append. That 11 µs is the engine's and JDBI's client work,
  which in NORMAL mode was hidden behind wake-up latency.
- **Fix arm.** A 10 µs busy-wait in C between lookup and append makes C about 13% (8-18%) faster than A in NORMAL (40.2-41.3
  against 34.9-37.2 thousand per second). Its server CPU per attempt is 3-7 µs lower (62.0-63.1 against 65.8-69.2 µs).
  Adding client work cannot make the statements cheaper. It only keeps a CPU from going idle and makes the backends'
  wake-ups cheaper. So the C-versus-A deficit in §5 is §9's idle and wake-up effect.
- **How it arises was not isolated.** The raw client answers the lookup a few µs sooner, and the VM ends up idling
  more often. Why a slightly faster client leads to more idle time, and so to more expensive wake-ups, was not
  measured (no PMU, no scheduler trace). It is a property of this shared VM, not of pgjdbc.

## 4. Uncontended, per statement

The table shows four passes per cell (two runs × two passes, palindrome), as ranges. "Client CPU" is the calling
thread's CPU time per call, blocking I/O syscalls included. Server execution (`pg_stat_statements`) is equal per
statement in both libraries and is listed once.

| Statement | Library | p50 µs | p99 µs | Client CPU µs/call | Server exec µs/call |
|---|---|---|---|---|---|
| A insert, one event (`PreparedBatch` / `executeBatch` + generated keys) | JDBI | 82.6-89.5 | 109-135 | 35.2-36.1 (41.0 in the very first pass) | 13.6-14.9 |
| | raw | 75.7-76.8 | 101-107 | 27.7-29.4 | 13.6-14.1 |
| Lookup, `setFetchSize(1)` in a transaction (the engine's shape) | JDBI `findOne` | 96.0-106.2 | 115-174 | 37.5-43.6 | 6.5-7.7 |
| | JDBI `findFirst` | 98.5-108.4 | 118-179 | 38.1-44.5 | 7.4-8.3 |
| | raw | 56.0-68.7 | 72-120 | 24.2-30.2 | 6.3-8.2 |
| Lookup, no fetch size | JDBI `findOne` | 58.2-64.5 | 74-122 | 26.6-29.7 | 6.8-7.5 |
| | raw | 59.0-68.3 | 73-85 | 24.1-27.9 | 6.4-7.1 |
| B, one event | JDBI | 85.1-88.8 | 113-158 | 33.1-37.1 | 15.8-18.2 |
| | raw | 79.3-84.5 | 105-158 | 27.2-31.3 | 16.5-18.3 |
| Tail read, 100 rows, autocommit | JDBI | 210.8-211.8 | 240-251 | 90.5-93.1 | 134.5-135.5 |
| | raw | 206.3-208.2 | 234-243 | 87.1-88.2 | 134.4-136.3 |

- **JDBI's own overhead is a few µs of client CPU per statement.** Against raw:
  - one-event insert batch: +6-8 µs CPU, +6-8 µs p50;
  - B: about +6 µs CPU (ranges 33-37 against 27-31), +1-9 µs p50;
  - tail read of 100 mapped rows: +3-6 µs CPU, +3-5 µs p50;
  - lookup without a fetch size: +2-5 µs CPU, which is within the spread at p50.

  The server does the same work in both. The overhead is JDBI's statement and binding machinery, its
  `ResultIterable`/mapper layer, and for the batch the `PreparedBatch` bookkeeping. It was not broken down further.
- **The fetch-size interaction is a round trip, not overhead.** `ResultSetResultIterator.next()` in JDBI 3.55.0
  advances the `ResultSet` in a `finally` block after mapping each row: it reads ahead. With `setFetchSize(1)` and
  autocommit off, pgjdbc uses a portal of one row, so that read-ahead is a second `Execute` round trip. The trace in
  §2 shows it: `PortalSuspended`, then `Execute` returning `SELECT 0`. `findFirst()` does not avoid it, because the
  read-ahead happens inside `next()`. Raw pgjdbc reads one row and closes the result set; the `ClosePortal` is sent
  with the next statement. Dropping the fetch size brings JDBI to raw's latency.
- **Consequence for the current engine, as a fact rather than a decision.** `loadLastPersistedEventRelatedTo`,
  and the other single-row reads in `SeparateTablePerAggregateTypePersistenceStrategy` that set `setFetchSize(1)`
  (`loadEvent`, the `MAX`/`MIN` order reads) combine it with JDBI's read-ahead. Inside a transaction, each costs one extra round trip; on this
  machine that is about 40 µs. The trace therefore shows four round trips for a unit of work with the lookup.

  *Reconciled 2026-11-11.* P0.0's proxy counted three because P0.0 measured a different shape, not because it
  miscounted. P0.0's append cases use a **new** aggregate each time, so the lookup finds no row: pgjdbc gets
  `CommandStatus` instead of `DataRow` + `PortalSuspended`, and there is nothing to read ahead. The trace above uses
  an **existing** aggregate, whose lookup returns a row. `round_trip_reconciliation` (same class, results in
  `p0-6-jdbc.txt`, lines `RT-RECONCILE`) runs each shape through the counting proxy and with the wire trace on the
  same unit of work, 50 units per shape. Proxy and trace agreed on every unit of work (0 of 50 differ in each shape):

  | Shape | Proxy flights | Trace flights |
  |---|---|---|
  | P0.0 "1 event, no expected order": new aggregate, the engine's lookup finds no row | 3.00 | 3.00 |
  | P0.0 "1 event, expected order given": new aggregate, no lookup | 2.00 | 2.00 |
  | P0.6 trace: existing aggregate, explicit `loadLastPersistedEventRelatedTo`, append with that order | 4.00 | 4.00 |
  | Existing aggregate, no expected order: the engine's own lookup finds a row | 4.00 | 4.00 |

  The proxy is correct, so P0.0's and P0.4's round-trip numbers stand for the shapes they ran. Those shapes are
  appends to new aggregates. Appending to an aggregate that already has events costs one round trip more whenever
  the lookup runs. That is the OCC-contended shape and the usual command-handler case. Single run, Testcontainers
  PostgreSQL 17.5, pgjdbc 42.7.14.

## 5. Contended, per unit of work

The table shows 8 threads, four windows per cell (palindrome × two runs), appends or reads per second in thousands,
as run 1 / run 2. `JDBI` is the engine's usage, including the extra lookup round trip; `JDBI_FIRST` is from the
second contended run. "Client CPU" includes the 50 µs busy-wait in SPIN mode.

| Unit @ mode | JDBI | JDBI_FIRST | raw | PostgreSQL CPU µs/unit (JDBI / raw) | Client CPU µs/unit (JDBI / raw) |
|---|---|---|---|---|---|
| lookup + insert @ NORMAL | 30.7, 33.5 / 29.3, 31.4 | 33.7, 31.6 / 32.8, 32.4 | 28.1, 31.9 / 28.7, 29.4 (second run: 31.7, 29.5 / 29.9, 28.4) | 73.5-80.7 / 79.2-86.6 | 49.8-55.3 / 41.7-47.0 |
| lookup + insert @ SPIN | 32.1-32.8 | 32.4-33.0 | 36.7-38.4 | 55.1-56.9 / 49.5-52.4 | 88.0-89.5 / 73.1-74.5 |
| lookup + B @ NORMAL | 23.8, 25.6 / 22.8, 22.9 | — | 24.0, 25.1 / 25.1, 26.8 | 92.1-99.1 / 91.5-99.9 | 55.9-62.2 / 45.3-50.5 |
| lookup + B @ SPIN | 32.6-33.9 | — | 37.5-38.0 | 56.0-58.2 / 51.5-51.9 | 81.8-82.8 / 70.4-70.7 |
| tail read @ NORMAL (reads/s) | 39.2-40.7 | — | 37.6-42.0 | 132-137 / 131-141 | 60.6-62.7 / 56.1-71.8 |
| tail read @ SPIN (reads/s) | 37.1-37.6 | — | 36.1-38.2 | 111.9-112.7 / 111.6-112.2 | 97.7-98.3 / 94.5-105.7 |

- **With the CPUs kept busy, raw pgjdbc is about 15% faster for lookup + insert (11-20% between extremes).** This holds against both JDBI
  variants (36.7-38.4 against 32.1-33.0 thousand per second). For lookup + B it is 11-17% faster. Client CPU per
  unit is 9-15 µs lower: JDBI's few µs per statement, plus the extra round trip's client work where `findOne` with
  a fetch size is used. Server CPU is 4-6 µs lower, which is the extra round trip and the wake-up costs it brings.
  `JDBI_FIRST` is no better than `JDBI`; as §4 explains, it still takes the extra round trip.
- **In NORMAL mode, the order flips or ties.** JDBI is 3-10% ahead for lookup + insert, with less PostgreSQL CPU per
  unit and a busier machine. Lookup + B ties within the spread. This is the same pattern as arm C against arm A (§3),
  and it is a property of this VM. As §9 of the append document says, absolute and relative figures on this machine
  depend on how often CPUs go idle.
- **The tail read is bound by the server and the machine** (98-99% busy, about 135 µs of execution per read). JDBI
  and raw tie in both modes, inside a spread of ±6%.
- `JDBI_NO_FETCH_SIZE` was measured only uncontended (§4). The contended equal-configuration figure for the lookup is
  therefore not measured. `JDBI_FIRST`, which still carries the round trip, is the nearest.

## 6. Not measured

- A separate database host, or `fsync=on`. On such a host the idle and wake-up amplification of §3 should change
  size or direction; that is untested.
- A breakdown of JDBI's per-statement overhead (binding, statement context, mapper lookup, cleanables). JFR was not
  used.
- JDBI's named-parameter parsing. The spike used positional `?` in both libraries, so that the SQL text was
  identical. The engine uses `:name` parameters, which JDBI parses once and caches; that cost was not measured.
- The `Describe(portal)` that pgjdbc sends with every batch execution that returns generated keys. It appears in both
  libraries, so it does not separate them. Whether a plain `executeQuery` on `INSERT … RETURNING` (no batch) saves
  anything was not measured.

## 7. Bearing on the redesign

These are findings for §13.2, not decisions:

- JDBI's library overhead is real but small: a few µs of client CPU per statement, nothing on the server. It does
  not argue for or against raw pgjdbc by itself. The capability reasons in §13.2 stand as they were.
- The material cost found here is a configuration interaction: JDBI's read-ahead together with `setFetchSize(1)`
  inside a transaction. In the current engine it costs a round trip per single-row read. A raw pgjdbc engine avoids
  it naturally, but it must still use fetch sizes deliberately: `setFetchSize` is only worth it for the streaming
  reads of §10.1, never for single-row reads.
- Throughput comparisons between client libraries on this VM are dominated by the idle and wake-up effect unless
  they are run in SPIN mode. In SPIN mode raw pgjdbc gave 11-20% more units of work per second for the append
  paths. That figure belongs to this machine and to this unit of work.
