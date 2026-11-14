# Phase-0 spikes P0.14-P0.17: techniques from `java-raw-stohirov`

2026-11-14. The rows are merged into `event-store-subscription-redesign.md` §18; this document holds their background.

Source read: `java-raw-stohirov/src/conduit/{Pg,WriteLane,ReadLane,Lanes,Sql,JobQueue,Job}.java` and its `README.md`
(read only, nothing built or run). Line numbers below refer to that tree. The extracted tree has no licence file, so
the spikes' wire client must be written from the protocol documentation, not copied from `Pg.java`.

## Where each technique can and cannot apply in our engine

The constraint that shapes everything: an append runs inside the caller's `UnitOfWork`, on the caller's JDBC
connection, with in-transaction handlers, and the caller gets the `PersistedEvent`s (built from `RETURNING
event_order`, §5.1) and any `OptimisticAppendToStreamException` synchronously. The engine owns neither the connection
nor the commit, and it cannot hold the append back until commit, because its results are needed at once. So any
technique that needs the engine to own the transaction or the connection applies only to the paths the engine owns.

| Technique (stohirov) | Appends in the caller's UoW (§5) | Shared tail reader (§6) | Resume points (§8) | Wake-up (§11) |
|---|---|---|---|---|
| Pipelining several statements, one Sync (`Pg.exec`/`Pg.run`) | Only what §5.2 already does: the unit of work's appends as one pgjdbc batch. The commit cannot join that flush, since the caller commits later | Already one statement per read (§13.2, P0.11's `UNION ALL` form). Across aggregate types: several tail reads in one flush (P0.16) | In the handler's transaction (§8.3), when the engine runs the page's UoW: the fenced update and the commit in one flush (P0.14). Periodic saves: all subscribers in one flush or one array statement (P0.15) | The notifier already sends one coalesced statement (P0.10) |
| Commit by Sync, no BEGIN/COMMIT (`WriteLane`) | No: the caller's UoW issues BEGIN and COMMIT | Not needed: the tail read is autocommit already | Yes for engine-owned transactions (P0.14) | Yes for the notifier's own connection (already autocommit) |
| Group commit across callers (`WriteLane.group`) | **No.** Different callers' UoWs cannot share a transaction | No (reads) | Periodic saves only, across the subscriptions of one JVM (P0.15). Not the in-handler resume point | Already done: the coalesced notifier is a group |
| Owned connections, prepare at connect, session settings (`Lanes.connect`, `Lanes.SESSION`) | No: the pool and the caller decide. Plan settings could only be per-pool | Yes (P0.16) | Periodic writer: yes. In-handler: no, it is the UoW's connection | Listener and notifier already own connections |
| Alternative client (custom wire or Vert.x) | **No.** It cannot join a JDBC transaction | Possible (P0.17) | Periodic writer only | Listener: pushed notifications (P0.17). Note `Pg.java` itself drops them |

Two further limits that the spikes must respect:

- A zero-row result is not an error, so it does not stop a pipeline. §5.1's zero-row conflict inside a group is
  therefore harmless (the other appends commit, the conflicting caller gets its exception), but §8.2's fenced
  resume-point update must abort its transaction on zero rows. With the commit in the same flush it no longer can,
  unless the statement is turned into one that raises an error on zero rows (for example
  `WITH u AS (UPDATE … RETURNING 1) SELECT 1 / (SELECT count(*) FROM u)`, which fails with `22012`). After an error
  the server skips everything up to the Sync, the COMMIT included (`Pg.read`, lines 616-631, marks those results
  `skipped`).
- A rolled-back group burns the identity values of every member (the retry draws new ones), so each group rollback
  leaves as many holes as it had appends. That costs the watermark (§4) probe work, not correctness.

## (a) Table rows

| Spike | Question | How | Gate | If it fails |
|---|---|---|---|---|
| P0.14 | When the engine owns the transaction (a subscription page with its fenced resume point, §8.3; an append the engine commits itself), what do round trips and server CPU drop to if the last statements and the commit go in one flush, and can pgjdbc do it? | Throwaway `OneFlushTransactionSpikeIT`, harness as P0.4/P0.13 (palindrome 20 s windows, fresh table per window, `RoundTripCountingProxy`, cgroup `cpu.stat` through `-Dspike.serverCpuCommand`, `pg_stat_statements` with `track_planning`), external server, `fsync=on`. Shapes: §5.1 with 1 and 10 events, committed by the engine; §5.2 with five aggregates; a page of 100 events as P0.13's set-based view upsert, then §8.2's fenced update, then commit. Arms: **A** pgjdbc, autocommit off, statements then `commit()` (today); **B** pgjdbc, autocommit on, the same statements as one `executeBatch` or one multi-statement `PreparedStatement` (one Sync, so one implicit transaction: the spike first proves this by failing the last statement and finding nothing committed); **C** a minimal wire client in the `spike` package: statements parsed at connect, Bind/Execute per statement and one Sync in one flush; **D** C with explicit `BEGIN` … `COMMIT` in the same flush. In B-D the fenced update is the erroring form above. 1, 8 and 20 writers. Per unit of work: round trips, server CPU (cgroup), recorded execution, p50/p99; units/s | Phase 2 builds engine-owned transactions as one flush if the best arm removes at least one round trip per unit of work, saves at least 35 µs of server CPU per removed round trip (P0.12 put `SELECT 1` at 40-52 µs) and gives at least 10 % more units/s at 8 writers; **and** a stale epoch commits nothing in every arm. If B is within 10 % of C on server CPU and units/s, it stays pgjdbc | Engine-owned transactions stay explicit pgjdbc transactions (statements, then `commit()`); the 274-310 µs per attempt outside recorded execution (P0.9, 2026-11-14) is not round-trip overhead, and P0.17's request-path half is dropped |
| P0.15 | Does a batcher that takes every waiting engine-owned write and commits them as one implicit transaction (stohirov's write lane) beat each caller committing alone, when PostgreSQL already shares one WAL flush among concurrent commits; and what does a group rollback cost? Applies only to writes outside a caller's UoW: an opt-in append-and-commit API without in-transaction handlers (importers, forwarding, the command-side track, §14), periodic resume-point saves (§8.1), lease renewals (§9) | Throwaway `EngineGroupCommitSpikeIT`, harness as P0.4, external server, `fsync=on`. Appends: **A** each caller its own pooled transaction (pool size = callers); **B** each caller one autocommit §5.1 statement (one round trip); **C** one batcher thread owning one connection, `JobQueue.take` shape (the first write opens a window of 0, 0.5 or 2 ms, or the batch closes at 256), the group as one pgjdbc batch of §5.1 statements in autocommit, one Sync; **D** as C, appends to distinct aggregates of one table merged into one multi-aggregate statement over arrays (two appends to the same aggregate never share a statement, since one `head` would serve both); **E** the better of C and D with a §5.3 unique violation injected into 1 in 1 000 and 1 in 100 groups, rolled back and retried as one segment per write in one flush. 1, 8, 20 and 64 callers; distinct aggregates, and 8 writers on 10 aggregates. Measured: events/s, server CPU per event (cgroup), WAL bytes and WAL syncs per event (`pg_stat_wal`; `pg_stat_io` on 18), caller p50/p99 including the window, identity values burned per 1 000. Periodic saves for 10, 100 and 1 000 subscriptions: one `UPDATE` each in one transaction (today), the same pipelined, one `UPDATE … FROM unnest(…)` | A batcher is offered in phase 2 only if C or D gives at least 1.5x events/s, or at least 30 % less server CPU per event, than the better of A and B at 20 and 64 callers, with p99 no worse than A's plus the window; E at 1 in 100 still at least 1.2x; and burned values per 1 000 no higher than B's in the contended shape. Periodic saves move to the array form if it costs at least 5x less server CPU per save at 100 subscriptions | No batcher. Engine-owned writes commit per caller and rely on PostgreSQL's shared WAL flush. Group commit is recorded as not applicable to this engine (it already could not apply to appends in a UoW). Periodic saves stay as today |
| P0.16 | For the engine's own long-lived connections (tail readers §6, periodic resume-point writer, notifier), what does an owned connection with every statement prepared at connect and fixed session settings save per call over pooled pgjdbc defaults; which `plan_cache_mode` keeps each statement on its index (P0.11's generic-plan trap); and do the tail reads of several woken aggregate types in one flush beat one round trip per type? | Throwaway `OwnedReadConnectionSpikeIT` on P0.11's 2-million-row tables plus 1, 10 and 50 aggregate-type tables, external server. Statements: tail read with 0, 1 and 100 new rows; P0.11's `UNION ALL` combined probe with 0 and 10 holes; §8.2's update. Arms: **A** Hikari, pgjdbc defaults (`prepareThreshold=5`); **B** an owned pgjdbc connection, `prepareThreshold=1`, every statement executed once at connect, `options=-c jit=off -c max_parallel_workers_per_gather=0`, each under `plan_cache_mode` `auto`, `force_custom_plan` and `force_generic_plan`; **C** the minimal wire client: all statements parsed in one round trip at connect, binary parameters and results, the settings in the startup message. Several types: 10 and 50 types woken at once, (i) one reader thread and round trip per type, (ii) one owned connection, every tail read in one flush with one Sync each, through a multi-statement `PreparedStatement` with `getMoreResults` (pgjdbc) and through C. Measured: server CPU per call (cgroup), plan and execution time (`pg_stat_statements`, `track_planning`), `EXPLAIN (GENERIC_PLAN)`, client CPU, p50/p99, round trips per wake | Phase 2's tail reader owns its connection with statements prepared at connect if B saves at least 20 µs of server CPU or 20 % of p50 per tail read over A. Each statement gets the plan mode under which `EXPLAIN` shows index scans in every case, and any statement whose generic plan does not is pinned to `force_custom_plan`. Several-types-in-one-flush is adopted if at 10 woken types it cuts server CPU per wake by at least 30 %. C counts only if it beats B by at least 20 % server CPU per call | Tail readers borrow pooled connections with pgjdbc defaults; plan mode stays at the default except where P0.11 requires otherwise; one round trip per type |
| P0.17 | Does a client that delivers notifications as they arrive, and pipelines many callers' independent statements on one connection, beat pgjdbc enough to justify a second driver, given that it can never join a caller's JDBC UnitOfWork? | Throwaway `AlternativeClientSpikeIT`, external server, the listener on a separate host if one is available. (a) Wake-up: producers as P0.10 (8 and 20 writers through the coalesced after-commit notifier) and the drain IT's 200, 1 000 and 3 000 notifications/s. Listeners: pgjdbc `getNotifications(200)` stock, `PINNED_PEEK`, `QUERY_POLL`; Vert.x `PgSubscriber`; a minimal LISTEN-only reader. Wake latency from the commit's return and from the wire (`NotifyTapSocketFactory`), p50/p99/max; listener client CPU; every notification counted. (b) Request path, engine-owned only: tail reads of 50 types, and 20 and 64 callers each sending one autocommit §5.1 append, through Vert.x (`pipeliningLimit` 1, 16, 256; 1 and 2 connections) and the minimal wire client, against a pgjdbc pool of the same callers and P0.16's best. Server CPU per statement (cgroup), its system part, backends, throughput, p99 | (a) An alternative listener only if pgjdbc's best (`PINNED_PEEK`, or `QUERY_POLL` if reflection is rejected) misses p99 at or below 2 ms at 3 000 notifications/s and the alternative meets it. (b) An alternative request client only if it gives at least 25 % less server CPU per statement and 1.3x throughput at 20 callers against pgjdbc's best. Either way the finding records the cost the drain doc names: provided-scope Vert.x and Netty, a second credential and TLS configuration beside the `DataSource` | pgjdbc only. The listener follows `pgjdbc-notification-drain-options.md` §5 (`PINNED_PEEK`, `QUERY_POLL` as fallback); no second driver in phases 2 or 6 |

## (b) What each spike is based on

### P0.14: one-flush transactions

**In the stohirov code.** `Pg.exec` (`Pg.java` 544-566) only appends Bind and Execute to the write buffer, each with an
unnamed portal and binary formats; `Pg.run` (576-582) appends Sync, writes the buffer once (`flush`, 353-357) and reads
until ReadyForQuery (`read`, 601-637). With no BEGIN, everything up to the Sync is one implicit transaction, committed
at the Sync; `WriteLane.group` (`WriteLane.java` 66-97) relies on exactly that, so a write group costs one round trip
including its commit. `Pg.read` shows the error rule P0.14 needs: after an ErrorResponse the remaining executes are
reported as `skipped` (616-631). Arm D (explicit BEGIN/COMMIT in one flush) is not in the code; it is there to test
whether the gain needs the implicit transaction or only the single flush.

**What it builds on.** P0.9 (2026-11-14): 274-310 µs of server CPU per append attempt falls outside recorded
execution, with an identical 117-120 µs of system CPU in every arm; P0.12: `SELECT 1` costs 40-52 µs of server CPU for
about 1 µs of execution, so at about three round trips per attempt a per-round-trip floor explains roughly half. P0.14
removes round trips on purpose and checks whether the CPU follows. P0.4 gives the append shapes and the
`RoundTripCountingProxy`; P0.13 gives the page shape, its set-based handler (117 000-119 000 events/s) and its fenced
update, and showed one round trip per event is the floor for row-by-row arms.

### P0.15: group commit for engine-owned writes

**In the stohirov code.** `WriteLane.run` (39-64) takes a group from `JobQueue.take` (`JobQueue.java` 47-62: wait for
the first job, then until `want` jobs or the oldest has waited the window, then take up to `max`; `push`, 19-29, only
signals the consumer on the first job or a full batch, which keeps wake-ups down). `WriteLane.group` merges writes of
one kind into one array statement (67-80, statements in `Sql.writes`, `Sql.java` 142-198, e.g. `fav_n` 151-154 with
`ORDER BY 1, 2` for lock order), runs the group with one Sync (87), and on any failure retries every write as its own
segment, one Sync each, in one flush (98-124, `Pg.runSegments` 643-649). On a lost connection the whole group is
answered 500 and not retried (53-60), although it may have committed. Defaults: one write lane, 3 ms window, 256 writes
(`README.md` settings table).

**What it builds on.** P0.4 contended shape and its burned-values count (A 616-629, B 521-531 per 1 000 on
2026-11-14), which E must not make worse; P0.10's coalesced notifier, which is already a group of this kind and
measured −10 to −12 % at 8 writers; P0.13's set-based statement as the model for an array-form periodic save. New
here: whether a client-side group buys anything over PostgreSQL's own sharing of the WAL flush, so WAL syncs per event
are measured, not assumed.

### P0.16: owned connections for the engine's reads

**In the stohirov code.** `Lanes.connect` (`Lanes.java` 27-36) opens a connection with the session settings in the
startup message (`Lanes.SESSION`, 22-25: `force_generic_plan`, `jit=off`, `max_parallel_workers_per_gather=0`; sent
by `Pg.startup`, 359-376) and parses every statement in one round trip (`Pg.prepare`, 528-541: Parse per statement,
one Sync, no Describe). Each lane owns its connection for life and reconnects lazily after a failure (`ReadLane.run`
65-73, `WriteLane.run` 51-56). The several-types arm copies `ReadLane.Plan.sendPhaseA` (324-331): several statements,
cut into segments by `Pg.cut` (585-590), sent in one flush and read in order by `Pg.receive` (593-595); the first
segment is answered before the second is read (`receiveFirstSegment`, 409-418). The second connection for the costly
statement (`sendFeed`, 349-362) has no counterpart in our engine.

**What it builds on.** P0.11: under a generic plan the `> cursor OR = ANY(holes)` tail shape walked the whole
primary key (about 2 million rows removed, about 300 ms), `auto` switched to generic at 1 000 holes on 300 000 rows,
and only the `UNION ALL` form stayed on index scans in every mode. That is the opposite of stohirov's reason for
`force_generic_plan` (array statements re-planned on every execution), so the plan mode is chosen per statement and
proven with `EXPLAIN`, never set per connection by habit. P0.6: library overhead is a few µs of client CPU and none on
the server, so C is expected to tie B on the server; it is in the table so the custom-client question is answered with
a number.

### P0.17: an alternative client

**In the stohirov code.** Nothing for notifications: `Pg.read`'s default branch (634) and `Pg.startup`'s (459-461)
skip every message they do not expect, NotificationResponse (`A`) included, and the repo has no `LISTEN` or `NOTIFY`
anywhere. What the code does show is the frame reader such a client needs (`Pg.In`, 246-297; message loop, 606-636)
and its size: `Pg.java` is 650 lines with SCRAM-SHA-256, MD5 and cleartext auth, but no TLS, no cancel request (the
BackendKeyData is discarded at 459-461), no Describe, no COPY. The request-path half rests on the README's premise
that PostgreSQL CPU per request falls when one backend is fed a continuous pipeline; the code achieves it with few
connections owned by threads (`Lanes.start`, 38-61; 17 backends by default).

**What it builds on.** P0.10 (2026-11-14): pgjdbc wake latency p50 3.4-15.9 ms after commit and up to 301 ms, against
0.02-1.06 ms on the wire; `pgjdbc-notification-drain-options.md`: `getNotifications` does not return while
notifications keep arriving, `PINNED_PEEK` reached p99 0.3 ms at 3 000/s, and options 3 and 4 (own LISTEN client,
Vert.x/R2DBC) were reasoned about but not built. P0.17 builds them so that recommendation rests on measurement. P0.9's
identical 117-120 µs of system CPU per attempt is the number the request-path half tries to move.

## (c) Corrections and additions to `postgresql-techniques-beyond-jdbc.md`

1. **Line 32 and the diagram ("Each arrow into PostgreSQL is one flush … then one Sync").** True for the write lane
   only. A read batch is two flushes (phase A, then phase B in `runPhaseB`, `ReadLane.java` 474-500), and phase A
   carries two Syncs on the main connection plus one on the feed connection (`sendPhaseA`, 324-331), i.e. up to four
   implicit transactions per batch.
2. **Line 200 ("Reads batched across callers share one snapshot").** Wrong. There is no BEGIN and the isolation is
   READ COMMITTED, so every statement takes its own snapshot; phase A's segments, the feed connection and phase B are
   separate transactions. Callers in a batch share a snapshot only for the rows of one statement. The repo accepts that
   phase B may see a newer state than phase A.
3. **Lines 43, 61 ("one WAL flush for many requests' writes").** True for the group, but PostgreSQL already lets one
   backend's WAL flush cover every commit record inserted before it, so N concurrent commits do not cost N flushes.
   What a client-side group saves is per-transaction work and round trips: the commit record, BEGIN/COMMIT, backend
   wake-ups. P0.15 measures WAL syncs per event instead of assuming the difference.
4. **Line 63 (unknown outcome "is not retried").** Correct, but the code then answers 500 to every member
   (`WriteLane.run`, 53-60), so a client can see a failure for a write that committed. For appends an engine batcher
   must report "outcome unknown", not failure; `UNIQUE(event_id)` is what could make a retry safe.
5. **Technique 2, missing.** A rolled-back group has burned the sequence values of all its members (`newart`,
   `newcmt_n` draw `nextval`); for our tables that is one hole per append in the group.
6. **Line 97 (`ORDER BY 1, 2` "makes concurrent groups take row locks in the same order").** With the default single
   write lane there are no concurrent groups; the ordering matters only with `WRITE_LANES` above 1. Also, `fav_n`'s
   final `SELECT` reads `r`, not the insert's `RETURNING`, so a missing row means "article not found", not "not
   inserted". Our version check needs the insert's own `RETURNING`, as §5.1 has; the pattern does not carry over
   literally.
7. **Line 101 (technique 6).** `Pg.prepare` sends Parse without Describe, so the client never receives a
   RowDescription: column layouts and binary decoding are hard-coded per statement (`Rows.i64` decodes by cell length,
   timestamps as int8 microseconds). That is what makes "binary everywhere" cheap here and why it does not transfer to
   a general driver. Statements are also re-parsed on every reconnect (`ReadLane.run` 65-66, `WriteLane.run` 51).
   `arttags` relies on `currval` from the same session (`Sql.java` 183-184), which only an owned connection allows.
8. **Line 105 and table row 7 ("pgjdbc sends JSONB as text").** The binary form of `jsonb` is a version byte followed
   by the same text, so binary saves little for payloads; the gain is for `int8`, `timestamptz` and arrays. For our
   append the design doc already found that the extra server CPU of §5.1 is not Bind-time array parsing (§5.3 note,
   append doc §6).
9. **Line 111 and "Caution: generic plans" (lines 170-172).** The code comment says the array statements are
   re-planned on every execution under the default mode. More important for us: the caution is not only for the
   durable queue. P0.11 found the event store's own tail read with holes walks the whole index under a generic plan;
   `force_generic_plan` is a trap there too, and only the `UNION ALL` form is safe.
10. **Line 55 ("a 64 KB buffer").** It is the initial size; `Buf` grows. More important, the client writes the whole
    pipeline before reading any result, on one blocking socket. That is safe here because requests are small. With
    large payloads (100-event appends) the client can block writing while the server blocks writing results; pgjdbc
    guards against that by splitting batches, and any wire client in P0.14-P0.17 must too.
11. **Line 57 ("each `executeQuery` is a full round trip").** pgjdbc already sends Parse/Bind/Describe/Execute/Sync of
    a statement in one flush and puts BEGIN in the first statement's flush, so a JDBC unit of work's extra round trips
    are the COMMIT and one per dependent statement (plus JDBI's fetch-size read-ahead, P0.6).
12. **Line 176 (a custom client "only adds pipelining of different statements across calls and lower allocation").**
    It also adds the commit in the same flush (technique 2 needs it) and could add pushed notifications, which
    `Pg.java` does not do: it discards NotificationResponse (lines 459-461, 634). It also lacks TLS, cancel requests,
    `statement_timeout` handling and COPY; the doc mentions only TLS.
13. **Line 187 ("text parameters only").** pgjdbc also accepts several statements with `?` parameters in one
    `PreparedStatement` and sends them in one flush with one Sync; and an `executeBatch` in autocommit ends with one
    Sync, which should make it one implicit transaction. Both are expected from the protocol and pgjdbc's composite
    queries, not yet verified; P0.14 arm B verifies them.
14. **Lines 154, 158 and experiment 4.** Already measured. P0.10 (2026-11-14): NOTIFY inside the transaction −76 to
    −83 % at 8-20 writers; after commit per writer −32 to −38 % at 8 writers; coalesced per JVM −10 to −12 %. "Version
    conflicts as rows" is §5.1's design, but §5.3 shows about half of contended conflicts still end as unique
    violations, and P0.4's `ON CONFLICT` arm (1 869-1 989 appended/s) did not beat B (1 917-1 931).
15. **Missing from the techniques list.** Lanes take turns forming batches (`ReadLane.TURN`, lines 22, 51-57: one lane
    gathers while the others execute); the queue wakes its consumer only on the first job or a full batch
    (`JobQueue.push`, 25); reads whose client has gone are dropped unrun (`ReadLane.run`, 60). The first two matter for
    any batcher P0.15 builds.

## (d) Techniques not made into spikes

- **Array parameters and `unnest … WITH ORDINALITY` (techniques 4, 5).** Already the design (§5.1, §13.2) and
  measured: P0.4 (append), P0.11 (hole re-reads), P0.13 (set-based page, 10-13x). P0.15 uses them for periodic saves
  and the multi-aggregate arm.
- **Session settings (technique 8).** Not a spike of their own: `jit=off` and no parallel workers are arms of P0.16,
  and the plan mode is decided per statement there, from P0.11's finding.
- **Binary format (technique 7) on its own.** P0.4's encoding attribution found Bind-time parsing is not where §5.1's
  CPU goes; P0.16's arm C covers binary results for reads.
- **Allocation-light row parsing (technique 10).** Client CPU only. P0.6 put library overhead at a few µs per call,
  and JSON deserialisation dominates our reads.
- **A second connection for the costliest statement (`sendFeed`).** No analogue: our costly reads are catch-up reads,
  already separate and capped (§10.2).
- **Long read windows (40 ms).** The tail reader is already one shared read per aggregate type; a window would only
  add latency. Batching across types is covered by P0.16.
- **Segments for early answers.** Covered as the several-types arm of P0.16 and the retry path of P0.15; nothing else
  in the engine has a fast and a slow part in one batch.
- **Moving NOTIFY out of the commit.** Measured (P0.10); §11's revision already follows it.
- **COPY.** Not used in the repo; useful for imports and table migration only, not on phase 2's path.
- **A full custom driver for the append path.** Ruled out by the UnitOfWork constraint above, whatever it measures.
- **`SCHED_BATCH`, bcrypt, HTTP, JWT.** Outside PostgreSQL.
