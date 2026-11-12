# Event store redesign, spike P0.4: the single-statement append against today's `PreparedBatch`

Date: 2026-11-10. Part of phase 0 of [`event-store-subscription-redesign.md`](event-store-subscription-redesign.md)
(§5, §17, §18). The question is whether the single-statement append of §5.1 is better than the current engine's
JDBI `PreparedBatch` append, judged on the basis P0.0 set ([`event-store-phase0-baseline.md`](event-store-phase0-baseline.md) §4):
the batch is already one round trip, so the gain has to come from the removed last-event-order lookup, from fewer
statements and less execution time, from several aggregates in one round trip, and from fewer holes under contention.

**Gate (decided before the run, from §18):** lower latency or fewer round trips at 1, 10 and 100 events, and fewer
holes under contention, outside the noise. Otherwise keep the batch shape (with the xid statement first) and drop §5.4.

## 1. Setup

**Code.** `SingleStatementAppendSpikeIT` in `components/postgresql-event-store/src/test/java/.../postgresql/spike/`,
reusing P0.0's container, role and engine wiring (`CurrentEngineBaselineSpikeIT.Pg` / `Stack`) and its
`RoundTripCountingProxy`. Tagged `spike`, runs only with `-Dspike.run=true`. Raw output, including the top five
normalised statements of every measurement window, goes to `components/postgresql-event-store/target/spike-results/p0-4-append.txt`.

**Command** (three test methods, two runs of every shape, about 4 minutes):

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=SingleStatementAppendSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
```

Knobs: `-Dspike.append.runs` (default 2), `-Dspike.append.rounds` (default 3), `-Dspike.append.timeScale` (scales the
contended window; default 1.0). Single methods: `#append`, `#contended`, `#sanity`.

**Machine and database.** As P0.0: 8 CPUs (arm64, Docker Desktop linuxkit VM), JDK 25, PostgreSQL 17.5 with
`pg_stat_statements`, pgjdbc 42.7.14 with its defaults (`reWriteBatchedInserts=false`, `prepareThreshold=5`),
Testcontainers' `fsync=off` (absolute latencies and throughput are flattering; the ratios between arms are what
count), a fresh container per run, nothing else building, run in-tree. The VS Code Java language server was running.

**Arms.** All three write to tables created by `SeparateTablePerAggregateTypePersistenceStrategy` itself (`TEXT` ids,
`JSONB` payloads, the real columns and indexes), inside the same `EventStoreManagedUnitOfWorkFactory` unit of work,
with the same event mapper as P0.0 and the configuration's serializer (`EssentialsJSONEventSerializers.create()`),
and all three register the resulting `PersistedEvent`s on the unit of work:

- **A** — `PostgresqlEventStore.appendToStream`: today's engine (`loadLastPersistedEventRelatedTo` when no expected
  order is given, then a JDBI `PreparedBatch` of single-row `INSERT … RETURNING global_order`).
- **C** — the same two statements through raw pgjdbc on the unit of work's connection. It separates library and
  engine overhead from statement shape; it is not P0.6 and was not taken further.
- **B** — the §5.1 statement through raw pgjdbc on the unit of work's connection: `WITH xid, head, ok INSERT … SELECT …
  FROM ok CROSS JOIN unnest(…) WITH ORDINALITY RETURNING global_order, event_order`, text arrays cast to the
  configured column types. One aggregate is one `executeQuery`; five aggregates are one pgjdbc batch of five
  statements (§5.2), each statement's update count checked (zero = optimistic-concurrency conflict).

**Correctness check (`sanity`).** Before measuring, the spike checked that B writes rows identical to the engine's
(event type, revision, payload and metadata text, `jsonb` type, UTC timestamp, tenant), that the engine's
`fetchStream` reads B's rows back and deserializes them, that an append without an expected order continues the
stream, that a stale expected order returns zero rows and leaves the identity sequence untouched, that a batch of
three aggregates with the middle one stale returns update counts `[1, 0, 2]` and writes the other two, and that the
two advisory-lock variants of §5.3 write the same rows. All passed.

**Append shape.** Single thread, one unit of work per iteration, new aggregates; 1 000 iterations (300 for 100
events, 500 for 5 aggregates), with and without an expected order (for a new aggregate the expected order is
`NO_EVENTS_PREVIOUSLY_PERSISTED`, −1). Per run: one discarded warm-up round, then three measured rounds; the arm order
rotates every round; every (round, case, arm) gets a fresh table and 50 warm-up iterations on it. Latency is pooled
over the three rounds; each round's p50 is also listed so the spread is visible. Round trips are counted in a
separate pass of 100 units of work through the counting proxy, as in P0.0.

**Contended shape.** As P0.0: 8 writers on 10 pre-created aggregates for 20 s, each unit of work reading the
aggregate's last event order (`loadLastPersistedEventRelatedTo`) and appending one event after it; fresh table per
arm, a 2 s warm-up on a throwaway table first, arm order rotated between runs. Arms: A; B; B with
`pg_advisory_xact_lock(hashtextextended('<table>:<aggregate id>', 0))` as the first CTE (§5.3 as written); and B with
the same lock taken by a separate statement immediately before the append. Burned identity values =
sequence advance − rows written.

## 2. Results

### 2.1 Append

p50 / p99 in ms, pooled over three rounds; per-round p50 in brackets. Statements are those `pg_stat_statements`
counted per unit of work (as in P0.0, it records `BEGIN` but not `COMMIT`, so one per transaction is missing from
every arm alike).

| Case | Arm | Run 1 p50 / p99 [per round] | Run 2 p50 / p99 [per round] | Statements | Round trips | WAL/event |
|---|---|---|---|---|---|---|
| 1 event, no expected order | A | 0.098 / 0.324 [0.173, 0.090, 0.091] | 0.167 / 0.205 [0.170, 0.167, 0.167] | 3 | 3.00 | 982-983 B |
| | C | 0.127 / 0.275 [0.161, 0.082, 0.159] | 0.154 / 0.189 [0.154, 0.155, 0.152] | 3 | 3.00 | 982-986 B |
| | B | 0.122 / 0.238 [0.156, 0.072, 0.133] | 0.136 / 0.167 [0.143, 0.136, 0.132] | 2 | 2.00 | 982-986 B |
| 1 event, expected order | A | 0.099 / 0.204 [0.139, 0.066, 0.127] | 0.128 / 0.158 [0.132, 0.127, 0.127] | 2 | 2.00 | 982-985 B |
| | C | 0.099 / 0.177 [0.128, 0.058, 0.110] | 0.118 / 0.143 [0.119, 0.118, 0.118] | 2 | 2.00 | 978-982 B |
| | B | 0.113 / 0.210 [0.148, 0.069, 0.134] | 0.135 / 0.174 [0.140, 0.134, 0.131] | 2 | 2.00 | 978-985 B |
| 10 events, no expected order | A | 0.326 / 0.531 [0.396, 0.315, 0.257] | 0.291 / 0.336 [0.292, 0.292, 0.288] | 12 | 3.00 | 955-956 B |
| | C | 0.204 / 0.479 [0.357, 0.187, 0.194] | 0.258 / 0.411 [0.270, 0.259, 0.243] | 12 | 3.00 | 957 B |
| | B | 0.173 / 0.303 [0.211, 0.194, 0.138] | 0.202 / 0.253 [0.202, 0.209, 0.195] | 2 | 2.00 | 955-956 B |
| 10 events, expected order | A | 0.199 / 0.405 [0.304, 0.185, 0.182] | 0.257 / 0.383 [0.302, 0.249, 0.246] | 11 | 2.00 | 956-959 B |
| | C | 0.238 / 0.350 [0.216, 0.239, 0.244] | 0.235 / 0.349 [0.276, 0.228, 0.229] | 11 | 2.00 | 954-957 B |
| | B | 0.209 / 0.391 [0.245, 0.220, 0.157] | 0.210 / 0.331 [0.243, 0.196, 0.202] | 2 | 2.00 | 955-956 B |
| 100 events, no expected order | A | 1.481 / 2.746 [1.647, 1.475, 1.387] | 1.344 / 1.587 [1.386, 1.308, 1.330] | 102 | 3.00 | 959 B |
| | C | 1.369 / 2.147 [1.652, 1.248, 1.389] | 1.251 / 1.488 [1.285, 1.231, 1.243] | 102 | 3.00 | 957-959 B |
| | B | 0.911 / 1.642 [0.984, 0.868, 0.909] | 0.864 / 1.193 [0.921, 0.848, 0.846] | 2 | 2.00 | 959 B |
| 100 events, expected order | A | 1.397 / 2.817 [1.614, 1.346, 1.353] | 1.259 / 1.630 [1.305, 1.246, 1.261] | 101 | 2.05-2.13 | 958-959 B |
| | C | 1.269 / 1.607 [1.314, 1.234, 1.258] | 1.209 / 1.388 [1.209, 1.203, 1.211] | 101 | 2.02-2.09 | 958-959 B |
| | B | 0.881 / 1.355 [0.948, 0.811, 0.900] | 0.885 / 1.086 [0.884, 0.852, 0.931] | 2 | 2.00 | 957-958 B |
| 5 aggregates × 10 events, no expected order | A | 1.178 / 1.445 [0.946, 1.209, 1.312] | 1.261 / 1.442 [1.269, 1.260, 1.246] | 56 | 11.00 | 954-955 B |
| | C | 0.955 / 1.572 [0.882, 0.971, 1.199] | 1.157 / 1.476 [1.136, 1.155, 1.177] | 56 | 11.00 | 956-957 B |
| | B | 0.575 / 0.937 [0.570, 0.565, 0.585] | 0.569 / 0.788 [0.596, 0.558, 0.556] | 6 | 2.00 | 952-956 B |
| 5 aggregates × 10 events, expected order | A | 0.980 / 1.169 [0.796, 1.009, 1.025] | 1.003 / 1.154 [1.001, 1.002, 1.007] | 51 | 6.00 | 996-997 B |
| | C | 0.884 / 2.352 [0.736, 0.924, 0.865] | 0.924 / 1.128 [0.928, 0.908, 0.940] | 51 | 6.00 | 957 B |
| | B | 0.569 / 0.995 [0.533, 0.580, 0.589] | 0.562 / 0.682 [0.551, 0.549, 0.593] | 6 | 2.00 | 955-956 B |

- **Run 1 is noisy at the small sizes.** Its second round ran every arm at about half the latency of the other
  rounds (0.058-0.090 ms for one event), which drags run 1's pooled p50 below run 2's. The per-round figures, and run 2,
  which is tight (rounds within ~0.01 ms), are the ones to read for 1 and 10 events.
- **1 event.** Without an expected order, B is one round trip and one statement fewer than A (2 against 3: the lookup
  is gone), and its p50 is lower in run 2 (0.136 against 0.167 ms, all three rounds) and in two of three rounds of run 1.
  With an expected order, both are 2 round trips and 2 statements, and B is no faster: 0.135 against 0.128 ms in
  run 2, 0.113 against 0.099 in run 1 — a tie or slightly worse, inside the noise.
- **10 events.** Without an expected order, B is lower in both runs (0.173 / 0.202 against 0.326 / 0.291 ms) and one
  round trip fewer. With an expected order the round trips are equal (2) and the latency is a tie: 0.209 against
  0.199 ms in run 1, 0.210 against 0.257 in run 2, per-round values overlapping.
- **100 events.** B is about 35% lower in both runs and both modes (0.86-0.91 against 1.26-1.48 ms p50; p99 also
  lower) — outside the noise. One execution instead of a hundred is where its gain is.
- **5 aggregates per unit of work.** B is 2 round trips against 11 (no expected order) or 6 (with), and its p50 is
  about half of A's in both runs (0.56-0.58 against 0.98-1.26 ms).
- **WAL per event is the same** (~955-985 B) in every arm; the statement shape does not change what is written. The
  ~997 B for A in the 5-aggregate expected-order case appears in both runs but not in C, which writes the same rows;
  it is not explained and is small.
- **C against A** (library and engine cost on the same statements): C is lower than A by roughly 0.01-0.1 ms in most
  cases, and occasionally higher; that is P0.6's question and is recorded only as an indication. B's gain at 100
  events and 5 aggregates (~0.4-0.7 ms) is much larger than the C-A difference, so it is the statement shape, not
  the library.

### 2.2 Contended aggregates

8 writers on 10 aggregates, 20 s, each unit of work: read last event order, append one event after it.

| Run | Arm | Attempts | Appended (per s) | Zero-row conflicts (no hole) | Unique-violation conflicts (hole) | Burned identity values per 1 000 successful appends | Statements per attempt |
|---|---|---|---|---|---|---|---|
| 1 | A engine | 1 009 189 | 689 640 (34 482) | — | 319 549 | 463.4 | 2.68 |
| 2 | A engine | 1 017 799 | 694 873 (34 744) | — | 322 926 | 464.7 | 2.68 |
| 1 | B | 741 484 | 491 078 (24 554) | 128 601 | 121 805 | 248.0 | 2.84 |
| 2 | B | 716 061 | 471 693 (23 585) | 116 953 | 127 415 | 270.1 | 2.82 |
| 1 | B + advisory lock as first CTE (§5.3 as written) | 642 578 | 428 319 (21 416) | 103 494 | 110 765 | 258.6 | 2.83 |
| 2 | B + advisory lock as first CTE | 664 908 | 442 873 (22 144) | 105 076 | 116 959 | 264.1 | 2.82 |
| 1 | B + advisory lock as a separate statement | 353 205 | 230 758 (11 538) | 122 447 | 0 | 0 | 4.00 |
| 2 | B + advisory lock as a separate statement | 362 540 | 237 297 (11 865) | 125 243 | 0 | 0 | 4.00 |

A's figures reproduce P0.0 (466 per 1 000). Every burned value was a unique-violation conflict, in every arm.

- **B halves the holes, it does not remove them**: 248-270 burned values per 1 000 successful appends against
  463-465. About half of B's conflicts are the clean zero-row kind; the other half are unique violations after
  `nextval`. §5.3 called that "the rare case of a true race"; under contention it is not rare. The reason is READ
  COMMITTED: `head` reads the statement's snapshot, so a competitor that has inserted but not committed is invisible;
  the check passes, the insert then waits on the competitor's index entry and fails with a unique violation when it
  commits. Only a competitor that committed *before* the statement started yields zero rows. Any two overlapping
  appends to one aggregate therefore still burn a value.
- **The advisory lock as the first CTE does nothing** (259-264 per 1 000, the same as B, at lower throughput). The
  statement's snapshot is taken when the statement starts, before it waits for the lock, so after the lock is granted
  `head` still reads the state from before the previous holder's commit, passes the check, and loses on the unique
  index. §5.3's shape is wrong as written.
- **The advisory lock as a separate statement before the append works**: zero burned values in both runs; every
  conflict is a zero-row result. The append statement then starts after the lock is granted, so its snapshot
  includes the previous holder's commit. It costs one more statement and round trip per append and serialises the
  writers of an aggregate until commit: in this deliberately hot shape throughput fell to a third of A's
  (11.5-11.9 against 34.5-34.7 thousand appends per second, with `fsync=off`).
- **B's throughput under contention is ~30% below A's** (23.6-24.6 against 34.5-34.7 thousand appends per second;
  attempts likewise). Investigated in §5: the gap is real, not a counting artefact, and comes from B's statement
  costing more server CPU per append than A's insert, not from lock waits, planning or the client.

## 3. Verdict

**Gate partly met.** Fewer holes under contention: yes, outside the noise (−42 to −47%). Lower latency or fewer
round trips: yes at 100 events (both modes, ~35% lower latency), yes at every size when no expected order is given
(one round trip fewer, and lower latency at 10 events), and yes for several aggregates per unit of work (2 round trips
instead of 6 or 11, about half the latency). **Not met at 1 and 10 events when the caller gives an expected order**:
same round trips, same statement count, latency a tie. B was not worse than A outside the noise in any case.

What that means for §5:

- **§5.1 is kept for the new engine.** It never loses, it wins where appends are large, multi-aggregate or have no
  expected order, and it halves the holes. Two corrections to the text came out of the spike: the `head` CTE must
  use `coalesce(max(event_order), -1)` — event orders start at 0 and `NO_EVENTS_PREVIOUSLY_PERSISTED` is −1, so the
  plan's `0` would have started every new stream at 1 and rejected a caller's `-1`; and because `event_order` is now
  assigned on the server, a `PersistableEventMapper` (and enrichers) called without an expected order can only be
  given a provisional order — the `PersistedEvent` must be built from `RETURNING event_order` (the spike does this).
- **§5.1's claim "a lost race consumes no sequence value" holds only for conflicts with an already committed append.**
  Overlapping appends still burn one, so holes remain a normal event under contention and the read path must still
  handle them as §4 already designs it to.
- **§5.3 is corrected**: the advisory lock must be a separate statement before the append, not a CTE. That variant
  gives zero holes at the cost of one round trip and writer serialisation per aggregate; it stays opt-in, for
  aggregates known to be hot.
- **§5.4 is not dropped, but its premise is restated**: replacing the current engine's `PreparedBatch` would halve
  the holes, not remove them, and gains latency only for large or multi-aggregate appends and appends without an
  expected order. It remains a separate decision.

## 4. Not measured

- Other PostgreSQL majors (only 17.5), UUID-typed ids, and a configuration with tenants.
- Production durability (`fsync=on`): commit latency would dominate the small cases even more, shrinking the relative
  differences at 1 and 10 events.
- Which part of B's statement (array literals, `unnest`, casts, the `head` scan, the CTEs) costs the extra server CPU
  found in §5; no `EXPLAIN (ANALYZE, BUFFERS)` was taken.
- The A/B from worktrees outside `/workspace` that §17 asks for; both arms ran in the same JVM and tree, alternating,
  which is the comparison that matters here since no engine code differs.
- Concurrent uncontended appenders (§17's steady and concurrent-writers shapes) with B.

## 5. Contention throughput

Date: 2026-11-11. Follow-up to §2.2: why does B append about 30% fewer events per second than A when 8 writers
contend for 10 aggregates?

**Code and command.** `ContendedAppendThroughputSpikeIT` (same `spike` package, same gating) runs §2.2's shape and
adds what §2.2 did not measure: client wall time per attempt split into begin, last-order lookup, append and
commit/rollback, per outcome; the writer threads' CPU time and the JVM's; the PostgreSQL container's CPU (cgroup
`cpu.stat`) and the machine's busy fraction (`/proc/stat`; the devcontainer and the database share the same 8-CPU
Docker VM); a `pg_stat_activity` sample of the writer backends every ~2 ms (state and wait event); and
`pg_stat_statements` with `track_planning` on. Two new arms: **C** (today's two statements through raw pgjdbc, as in
§2.1) and **B + `ON CONFLICT (aggregate_id, event_order) DO NOTHING`** (a lost race returns zero rows instead of
raising a unique violation; `SingleStatementAppendSpikeIT.Lock.ON_CONFLICT_DO_NOTHING`). Arms ran in the palindrome
order B, A, C, B+OC, B+OC, C, A, B, so every arm has the same mean position in the container's life; two runs, each
on a fresh container, 20 s per window after a 2 s warm-up on a throwaway table, fresh table per window, `fsync=off`,
`-Dfailsafe.forkCount=1`, nothing else building. Raw output: `target/spike-results/p0-4-contention.txt`.

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=ContendedAppendThroughputSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true \
    -Dspike.contention.runs=2 -Dspike.contention.seconds=20 \
    -Dspike.contention.arms=B_SINGLE,A_ENGINE,C_RAW,B_ON_CONFLICT,B_ON_CONFLICT,C_RAW,A_ENGINE,B_SINGLE
```

> **Caveat (2026-11-11, §9):** about half of the per-attempt PostgreSQL CPU on this VM is the cost of CPUs going idle and being woken up, and the slower arm pays more of it. Read the absolute CPU figures and the size of the A-vs-B gap below with §9 in mind.

### 5.1 The measurement was sound

- **Both arms count the same thing**: rows written in the window (table row count after minus before) per second,
  i.e. successful appends. Attempts are counted the same way too, and both are about 30% lower for B.
- **Both arms do the same work per attempt**: one unit of work, the engine's `loadLastPersistedEventRelatedTo`
  (the same statement, the same JDBI mapping), one append with that expected order, commit or rollback; a failed
  attempt is not retried, the loop simply picks the next random aggregate. The only differences are the append itself
  and how a conflict surfaces: A throws `OptimisticAppendToStreamException` (built with the long message after
  catching the unique violation), B throws a stackless exception on zero rows or gets the raw unique violation.
- **The conflict rates are close**: 31.5-31.7% of attempts in A, 33.4-34.1% in B. Fewer successes per attempt
  explain at most 3 points of the 30% gap.
- **Arm order is not the cause.** §2.2's rotation happened to run A before B in both runs. With the palindrome order,
  A and B each ran first and last in a container and their figures did not move with position (A 34.7-35.5 thousand
  appends per second whether first or last). A smoke run with 3 s windows did show a large first-window effect
  (the first window of a fresh container ran ~30% faster than every later one, whatever the arm), so short windows
  are not usable for this comparison; at 20 s it does not show.

So the gap is real. Per window (successful appends per second; wall time per attempt = 8 writers × 20 s ÷ attempts):

| Run | Arm | Appended/s | Attempts/s | Conflict rate | Wall µs/attempt | Client CPU µs/attempt | PostgreSQL CPU µs/attempt | Machine busy | Lookup µs (successes) | Append µs (successes) | Append exec µs/completed call | Lock-wait share of backend samples |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | B | 26 020 | 39 260 | 33.7% | 204 | 55 | 94 | 79% | 78 | 71 | 19.7 | 2.2% |
| 1 | A | 34 727 | 50 787 | 31.6% | 158 | 56 | 70 | 89% | 52 | 52 | 14.3 | 2.7% |
| 1 | C | 31 608 | 45 956 | 31.2% | 174 | 52 | 79 | 83% | 66 | 49 | 15.0 | 2.3% |
| 1 | B + ON CONFLICT | 13 954 | 21 446 | 34.9% | 373 | 93 | 154 | 69% | 153 | 135 | 45.8 | 2.2% |
| 1 | B + ON CONFLICT | 14 837 | 22 649 | 34.5% | 353 | 90 | 142 | 68% | 149 | 119 | 36.3 | 2.1% |
| 1 | C | 30 649 | 44 576 | 31.2% | 180 | 54 | 83 | 84% | 67 | 51 | 16.2 | 2.4% |
| 1 | A | 35 548 | 51 825 | 31.4% | 154 | 54 | 69 | 90% | 51 | 50 | 14.2 | 2.8% |
| 1 | B | 24 419 | 36 770 | 33.6% | 218 | 55 | 104 | 80% | 76 | 80 | 25.6 | 2.7% |
| 2 | B | 24 017 | 36 049 | 33.4% | 222 | 59 | 99 | 78% | 84 | 75 | 20.5 | 2.3% |
| 2 | B | 23 270 | 35 330 | 34.1% | 226 | 60 | 103 | 78% | 87 | 82 | 22.8 | 2.3% |
| 2 | A | 35 176 | 51 489 | 31.7% | 155 | 55 | 69 | 90% | 51 | 52 | 14.2 | 2.8% |
| 2 | C | 32 308 | 46 826 | 31.0% | 171 | 51 | 78 | 84% | 64 | 48 | 14.6 | 2.5% |
| 2 | B + ON CONFLICT | 14 964 | 22 822 | 34.4% | 351 | 89 | 143 | 68% | 147 | 119 | 37.3 | 2.2% |
| 2 | B + ON CONFLICT | 17 510 | 26 620 | 34.2% | 301 | 77 | 126 | 71% | 125 | 101 | 32.7 | 2.3% |
| 2 | C | 32 875 | 47 597 | 30.9% | 168 | 50 | 78 | 83% | 63 | 47 | 14.6 | 2.5% |
| 2 | A | 35 546 | 51 893 | 31.5% | 154 | 54 | 69 | 90% | 52 | 51 | 13.9 | 2.7% |

(Append exec is `pg_stat_statements`' mean over completed calls: it includes B's zero-row calls but no statement that
failed with a unique violation, which `pg_stat_statements` does not record. Lock-wait share = samples with
`wait_event_type = Lock` (`transactionid`) over all writer-backend samples.)

B reproduces §2.2: 23.3-26.0 against 34.7-35.5 thousand appends per second, 241-268 against 458-464 burned identity
values per 1 000 appends.

### 5.2 What it is not

- **Not lock waits.** Backends waiting on a `transactionid` lock (an insert waiting for the in-flight competitor
  that owns the conflicting index entry) are 2.2-2.8% of writer-backend samples in every arm, B no higher than A —
  about 5-6 µs per attempt. The longer append time of B's unique-violation attempts (161-172 µs against A's
  115-120 µs) is the same in proportion as its successful ones, not a longer wait.
- **Not planning.** Every append statement was planned 70-96 times per window, i.e. about once per connection
  until pgjdbc's `prepareThreshold` and the server's plan cache settle on the generic plan, then never again; plan
  time is a rounding error.
- **Not the client.** The writer threads' own CPU per attempt is the same in A and B (54-56 against 55-60 µs), so
  array building, `createArrayOf`, serialization and exception construction (including A's long OCC message) do not
  explain it; A even does a little more (its exception path). The JVM's total CPU tracks the writer threads (no GC or
  background cost hiding outside them).
- **Not the conflict mix.** The conflict rate differs by 2-3 points; the outcome-specific timings show every
  outcome of B slower, not a shift towards a slower outcome.

### 5.3 What it is: more server CPU per append, on a machine that shares its CPUs

- **B's append statement costs the server more.** Mean execution per completed call is 19.7-25.6 µs for B against
  13.9-14.3 µs for A's insert (1.4-1.8 times), with more buffer accesses per call (12.9-13.0 against 11.1) — the
  `head` index scan, eight text arrays parsed and cast, `unnest`, the CTEs. The PostgreSQL container's CPU per
  attempt is 94-104 µs for B against 69-70 µs for A (+25-35 µs, more than the execution difference alone; the rest
  is not attributed - rollbacks of the zero-row attempts, background processes and the failed statements that
  `pg_stat_statements` does not see are all in it).
- **On this machine every round trip of the attempt pays for it.** The database and the client share one 8-CPU VM
  that is 78-90% busy. B's extra server work lengthens every round trip of every attempt, not just its own: the
  lookup, which is the identical statement with the identical 6.3-6.9 µs server execution in every arm, takes
  76-87 µs of client wall time in B against 51-52 µs in A, and the backends spend correspondingly longer
  "idle in transaction" waiting for a client that is itself waiting for a reply. Wall time per attempt ends up
  +50-70 µs (204-226 against 154-158 µs), which with 8 writers is the 30%.
- **C points the same way.** C runs A's own statements, yet costs ~9 µs more server CPU per attempt than A
  (78-83 against 69-70) and is ~10% slower (30.6-32.9 thousand per second), with its lookup slowed in proportion
  (63-67 µs). Why raw pgjdbc costs the server more than JDBI for the same SQL was not isolated. *(2026-11-11, P0.6: it is not
  the driver. Arms A and C send identical wire messages on the same connection, and the gap reverses when CPUs are kept
  busy. It is the idle and wake-up effect of §9; see [`event-store-phase0-jdbc.md`](event-store-phase0-jdbc.md) §2-§3.)* Across all four arms
  the per-attempt server CPU and the lookup's wall time rise together, which is the pattern a CPU-bound, shared
  machine produces; it is a correlation, not a proof that nothing else contributes.

This is consistent with §2.1: uncontended single appends with an expected order tie on latency, because one round
trip's few microseconds of extra execution are lost in the noise of a 0.13 ms unit of work; under a saturated
machine the extra CPU becomes throughput.

### 5.4 Fix variant: `ON CONFLICT DO NOTHING` is worse

B with `ON CONFLICT (aggregate_id, event_order) DO NOTHING` removes the unique-violation error path (every conflict
is a zero-row result) but still burns an identity value per lost race (242-263 per 1 000 appends, the same as B,
since `nextval` runs before the speculative insert is resolved) and roughly halves throughput again: 14.0-17.5
thousand appends per second, append execution 32.7-45.8 µs per call (speculative insertion), container CPU
126-154 µs per attempt. Its client CPU per attempt was also higher (77-93 µs) with the same Java code as B; that was
not explained. It is not a fix and is not taken further. No variant that closes the gap was found in the time
spent; a cheaper encoding of the event columns (binary arrays, or one `jsonb`/composite array instead of eight text
arrays) is the obvious next candidate, untested.

### 5.5 Consequence for §5 of the redesign

The P0.4 verdict does not change: it was decided on latency, round trips and holes, all of which stand. What this
adds: under a CPU-bound hot-aggregate workload, B costs about 1.5 times A's server execution per one-event append,
and on a machine where client and database share CPUs that cost about 30% of throughput. It was measured with
`fsync=off` and with the database on the client's machine; with durable commits and a separate database host the
fixed commit cost per attempt grows and the shared-CPU amplification goes away, so the gap should shrink, but that
was not measured. For §5.4's decision on replacing the current engine's `PreparedBatch`, the server CPU per
small append is a cost to weigh against the halved holes, and the statement's per-call cost (array encoding,
`head` scan) is worth reducing before the new engine adopts it.

## 6. Encoding

Date: 2026-11-11 (finished after an interruption; the checkpoint's partial single-writer numbers are superseded by
the ones below where they overlap). The question was whether a cheaper parameter encoding closes the
single-statement append's (B) ~1.5× server cost per one-event append found in §5, where that cost goes, and whether
`json` instead of `jsonb` changes the picture. The general `json`/`jsonb`/`bytea` insert and read costs are not
re-measured here; they are in `perf/serialization-profiling:docs/plans/payload-serialization-measurements.md (not yet
on main)`, which found `jsonb` inserts 7/17/25% slower than `json` at 123 B/2 KB/19 KB payloads, reads 22-47% slower,
`json` and `bytea` equal, and Jackson at only 1-5% of machine CPU.

**Code.** `SingleStatementAppendSpikeIT.Encoding` and `Writer.encoded(...)` provide these B variants: `TEXT_ARRAYS`
(P0.4's statement), `BINARY_ARRAYS` (the same statement, arrays bound with `setObject(i, String[])`, which pgjdbc 42.7
sends in binary as `varchar[]`; the elements are still text and still cast per element), `JSONB_ONE` (one `jsonb`
array parameter expanded with `jsonb_to_recordset`), `SCALAR_1` (one event, scalar parameters, no `unnest`) and
`NO_HEAD` (diagnostic: no `head`/`ok` CTE, so no zero-row check). `ContendedAppendThroughputSpikeIT` has an arm per
variant (`B_BINARY`, `B_JSONB_ONE`, `B_SCALAR_1`, `B_NO_HEAD`), and an arm name may carry `:JSON` for tables with
`json` payload and metadata columns (`CurrentEngineBaselineSpikeIT.Stack.writer(pg, role, JSONColumnType)`). It now
drops each window's table after recording it: the first attempt at this run filled the Docker VM's disk at window 10.
`AppendEncodingAttributionSpikeIT` checks that every encoding writes the engine's rows (it does, 1 and 2 events; a
stale expected order gives zero rows except for `NO_HEAD`, by design) and holds the conversion micro-measure and the
`auto_explain` plans quoted below from the checkpoint run.

**Commands** (nothing else building, `fsync=off`, client and database on one 8-CPU VM; ~16 and ~12 minutes):

```bash
A=A_ENGINE,B_SINGLE,B_BINARY,B_JSONB_ONE,B_SCALAR_1,B_NO_HEAD,A_ENGINE:JSON,B_SINGLE:JSON,B_SCALAR_1:JSON
REV=$(echo $A | tr ',' '\n' | tac | paste -sd,)
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=ContendedAppendThroughputSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true \
    -Dspike.contention.runs=2 -Dspike.contention.seconds=20 "-Dspike.contention.arms=$A,$REV"
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test='SingleStatementAppendSpikeIT#append' -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true \
    -Dspike.append.arms=A_ENGINE,B_SINGLE,B_ENCODED -Dspike.append.encoding=SCALAR_1 \
    -Dspike.append.encoding.multi=BINARY_ARRAYS -Dspike.append.jsonTypes=JSONB,JSON
```

Raw output: `target/spike-results/p0-4-contention.txt` and `p0-4-append.txt` (the encoding run's lines are the ones
labelled `B encoded (SCALAR_1)` and `[JSONB]`/`[JSON]`).

### 6.1 Contended

8 writers on 10 aggregates, 20 s per window after a 2 s warm-up, every arm twice per container in palindrome order
(run 2 rotated by one), two containers, so four windows per arm. Ranges are over the four windows.

| Arm | Appended/s (thousands) | Burned per 1 000 appended | Wall µs/attempt | PostgreSQL CPU µs/attempt | Recorded exec µs/attempt | CPU not in recorded exec, µs/attempt |
|---|---|---|---|---|---|---|
| A engine, `jsonb` | 34.0, 35.0 / 37.6, 35.3 | 459-465 | 145-161 | 65.6-69.2 | 15.5-15.9 | 49.9-53.3 |
| A engine, `json` | 33.6, 36.2 / 36.2, 36.1 | 458-463 | 151-163 | 66.5-70.9 | 14.8-15.8 | 51.6-55.2 |
| B text arrays, `jsonb` | 23.6, 23.1 / 24.9, 19.6 | 238-262 | 214-270 | 94.1-122.1 | 23.6-32.3 | 70.5-89.8 |
| B text arrays, `json` | 23.7, 23.3 / 24.7, 23.5 | 238-261 | 216-228 | 98.5-104.5 | 23.2-26.9 | 75.3-78.0 |
| B binary arrays | 22.1, 23.5 / 23.1, 21.7 | 233-247 | 227-246 | 101.7-108.1 | 23.8-26.3 | 75.3-81.9 |
| B one `jsonb` parameter | 25.3, 25.7 / 24.5, 26.8 | 232-240 | 200-218 | 91.0-96.3 | 23.0-24.0 | 68.1-72.8 |
| B scalar, `jsonb` | 25.2, 26.3 / 24.2, 28.0 | 225-230 | 192-222 | 84.4-97.7 | 21.0-22.6 | 66.4-75.1 |
| B scalar, `json` | 26.6, 24.7 / 29.3, 27.0 | 222-230 | 183-217 | 84.4-94.7 | 19.9-20.7 | 64.4-74.0 |
| B no head (diagnostic) | 26.4, 27.3 / 25.7, 28.5 | 467-474 | 191-211 | 87.3-98.5 | 19.0-23.3 | 68.3-75.2 |

(Appended/s: run 1's two windows / run 2's two windows. "Recorded exec" is `pg_stat_statements`' total execution time
of the writer role's statements over attempts; it does not include statements that failed with a unique violation.
"CPU not in recorded exec" = container CPU minus recorded exec.)

- **A reproduces §5** (34-38 thousand appends/s, 458-465 burned per 1 000) and **B text arrays reproduce §5**
  (19.6-24.9 thousand, 238-262).
- **No encoding closes the gap.** The best B variants (scalar, one `jsonb` parameter) reach 24-29 thousand appends
  per second against B text arrays' 19.6-24.9; the ranges overlap, but they are higher in most paired windows. Every
  B variant stays well below A (33.6-37.6 thousand). Binary arrays are no better
  than text arrays (slightly worse in three of four windows).
- **Holes:** every variant with the `head` check burns 222-262 values per 1 000; the scalar shape is at the low end
  (222-230) in all eight of its windows. Without `head` the statement burns as many as A (467-474), so the halving
  comes from the check, not from the encoding.
- **`json` against `jsonb`** with these small payloads (~120 B payload, ~35 B metadata): no difference outside the
  noise for A (33.6-36.2 against 34.0-37.6 thousand) or for B text arrays, and B scalar's throughput is no
  different either (paired windows differ by −1.6 to +5.1 thousand, no consistent
  sign). Server execution per attempt is 0-1.5 µs lower on `json`, consistent with the checkpoint's conversion measure (`jsonb` ~0.7 µs per
  event dearer than `json` at these sizes) and with the other doc.

### 6.2 Where B's extra server cost goes

> **Caveat (2026-11-11, §9):** the "CPU not in recorded exec" excess of B over A disappears when the VM's CPUs are kept from idling. It is the idle and wake-up effect of §9 amplifying a few µs of extra executor work, not a cost outside the statement.

- **Inside the executor** (checkpoint `auto_explain`, one event, generic plan): A's insert 13-14 µs, B text arrays
  29-30 µs, B binary arrays 21-22 µs, B one `jsonb` 23 µs, B scalar 27 µs, B without head 26 µs; the `head` CTE is
  5-8 µs (backward index-only scan, 4 buffer hits, one heap fetch) and the `unnest` function scan 9 µs with text
  arrays, 3-4 µs with binary arrays. Under contention the recorded execution per attempt is 15-16 µs for A and 20-32 µs
  for B variants (6.1).
- **Outside the executor: the open hypothesis is refuted.** It was that text array literals are parsed at Bind,
  outside the executor time, and account for the 15-20 µs per attempt §5 could not attribute. If so, binary arrays
  (which have no text array literal to parse) and scalar parameters (which have no array at all) would shrink the CPU
  that is not in recorded execution. They do not: it is 49.9-55.2 µs per attempt for A, 70.5-89.8 for B text arrays,
  75.3-81.9 for binary arrays, 68.1-72.8 for one `jsonb` parameter, 64.4-75.1 for scalar parameters and 68.3-75.2 for
  B without `head`. Every B shape, including the one with neither arrays nor `head`, carries ~15-20 µs more
  unrecorded server CPU per attempt than A. `log_parser_stats`/`log_executor_stats` and `perf` were not tried;
  the stats settings log every statement, which at this rate would itself dominate the measurement.
- **What remains unexplained.** The excess outside the executor is common to every B shape and absent from A. What
  the B shapes share and A does not: an `INSERT … SELECT` with CTEs instead of `INSERT … VALUES`, the
  `pg_current_xact_id()` CTE, `RETURNING` two columns instead of one, and a zero-row outcome for about half of the
  conflicts (a completed statement followed by a rollback, against A's failed statement). The unrecorded part also
  holds everything `pg_stat_statements` cannot see (failed statements, transaction start/commit/rollback, background
  processes), and the arms differ in their mix of failed and completed conflicts, so the comparison is indicative,
  not exact. Isolating it would need a profiler on the server.

### 6.3 Uncontended, best variant

`SingleStatementAppendSpikeIT#append` with B encoded as `SCALAR_1` for one event and `BINARY_ARRAYS` for more, on
`jsonb` and `json` tables; p50 in ms pooled over three rounds, run 1 / run 2.

| Case | A `jsonb` | A `json` | B text `jsonb` | B text `json` | B encoded `jsonb` | B encoded `json` |
|---|---|---|---|---|---|---|
| 1 event, expected order | 0.134 / 0.130 | 0.134 / 0.130 | 0.145 / 0.132 | 0.143 / 0.133 | 0.131 / 0.123 | 0.128 / 0.121 |
| 1 event, no expected order | 0.178 / 0.170 | 0.178 / 0.168 | 0.144 / 0.129 | 0.139 / 0.130 | 0.129 / 0.121 | 0.129 / 0.121 |
| 10 events, expected order | 0.248 / 0.245 | 0.249 / 0.238 | 0.212 / 0.202 | 0.207 / 0.193 | 0.214 / 0.202 | 0.204 / 0.199 |
| 100 events, expected order | 1.302 / 1.275 | 1.272 / 1.241 | 0.891 / 0.865 | 0.849 / 0.831 | 0.878 / 0.875 | 0.844 / 0.819 |
| 5 aggregates × 10 events, expected order | 1.022 / 1.008 | 1.018 / 0.976 | 0.584 / 0.588 | 0.567 / 0.534 | 0.587 / 0.559 | 0.561 / 0.539 |

- **One event with an expected order**, the case P0.4 found a tie: scalar B is 0.121-0.131 ms against A's
  0.130-0.134 and B text arrays' 0.132-0.145. In run 2 the per-round p50s are tight (scalar 0.122-0.123 against A
  0.128-0.139), so scalar B is a few per cent faster than A there; in run 1 the difference is inside the noise.
  Round trips and statements are the same as in P0.4 (2 and 2).
- **10 or more events and several aggregates**: binary arrays tie with text arrays; the gain over A is P0.4's.
- **`json` against `jsonb`**: `json` is 0-6% lower at 10-100 events and the same at one event; WAL per event is
  ~20-25 B lower (929-966 against 953-990 B) and table size per row ~30 B lower, because the `json` text of these
  payloads is smaller than their `jsonb` form.

### 6.4 Summary of findings

- No parameter encoding brings B's contended throughput to A's on this machine; the best (scalar for one event, one
  `jsonb` parameter) recover roughly a quarter of the gap.
- The unattributed server CPU is not Bind-time text-array parsing; it is present in every B shape and its cause is
  still open (6.2).
- Uncontended, scalar parameters make the one-event, expected-order append a tie or slightly better than A, where
  text arrays were a tie or slightly worse.
- With small payloads, `json` against `jsonb` changes neither picture outside the noise; the payload-size effects
  are in the other doc.

## 7. Table design options

Date: 2026-11-11. The redesign may change the event table itself, so this measures layouts the current strategy
cannot express. Nothing here is a decision; each table lists what was observed.

**Code.** `TableDesignOptionsSpikeIT` (same `spike` package, same gating). Every arm creates its own table with spike
DDL and writes through raw pgjdbc with one statement shape for all arms: the §5.1 append with binary `varchar[]`
(`bytea[]` for the `bytea` arm) arrays expanded by `unnest`, the `xid` CTE first, always with an expected order. The
**BASE** arm has today's columns and indexes (`text` ids, `integer` revision, `jsonb` payload and metadata,
`UNIQUE(aggregate_id, event_order)`, `UNIQUE(event_id)`, identity `global_order`); each other arm changes one thing.
Payload ~120 B, metadata ~35 B, event type the ~100-character `FQCN:` name. Per run on a fresh container: correctness
checks; uncontended appends (single writer, new aggregate per unit of work, 1/10/100 events, 1 000/1 000/300
iterations after 50 warm-up, one discarded round then two measured rounds with rotated arm order, fresh table per
(round, size, arm)); a read shape on the 100-event table (2 000 reads of a random aggregate's 100 events, all columns,
payload fetched as text or bytes); 8 writers on 10 aggregates for 20 s per arm in palindrome order (reversed in
run 2), each attempt reading the last order and appending one event after it. Two runs, so four contended windows
and four uncontended rounds per arm. Tables are dropped after use. Raw output: `target/spike-results/p0-7-table-design.txt`.

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=TableDesignOptionsSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
# knobs: -Dspike.design.runs (2), .rounds (2), .seconds (20), .arms (all)
```

About 20 minutes. Same machine and caveats as §5: `fsync=off`, PostgreSQL 17.5, the client and the database share one
8-CPU VM, so absolute numbers flatter and only differences between arms count; differences inside the
round-to-round or window-to-window spread are treated as no difference.

**Correctness, every arm, both runs.** A stale expected order returned zero rows and took no global-order value; the
next append continued the stream; after every contended window every aggregate's event orders were contiguous from 0,
and in the head-table arms every head row equalled its stream's `max(event_order)`. The xid check (a `BEFORE INSERT`
trigger records `pg_current_xact_id_if_assigned()` when the first row's global order has been taken, P0.1's question
in a cheaper form) found an xid in every arm, including the two whose statement has no `xid` CTE (the head-table
arms: the head row's lock or update assigns it) and the explicit-`nextval` arm; a control plain `INSERT … VALUES` on
each table found none, so the check can tell the difference. No arm raised any error other than unique violations.

### 7.1 Uncontended appends and reads (BASE for reference)

p50 latency in ms, server execution per unit of work, WAL per event, table plus indexes per row (heap per row); ranges
over the four measured rounds.

| Arm | 1 event p50 / exec | 10 events p50 / exec | 100 events p50 / exec | WAL B/event (1 / 100) | B/row at 100 (heap) | Read 100 events p50 / exec |
|---|---|---|---|---|---|---|
| BASE (`jsonb`) | 0.124-0.135 / 13.3-15.4 µs | 0.185-0.190 / 61-63 µs | 0.712-0.725 / 490-496 µs | 905-913 / 891-893 | 743-747 (546) | 0.183-0.184 ms / 129-130 µs |
| `json` | 0.124-0.133 / 12.7-14.8 | 0.180-0.189 / 57-59 | 0.678-0.695 / 450-470 | 881-889 / 869 | 711-712 (512) | 0.118-0.119 / 73.5-74.9 |
| `bytea` | 0.123-0.132 / 12.2-14.5 | 0.176-0.181 / 53-55 | 0.648-0.652 / 424-430 | 881-889 / 868-870 | 711-713 (512) | 0.117-0.119 / 71.0-72.8 |
| no `UNIQUE(event_id)` | 0.123-0.131 / 11.9-14.1 | 0.172-0.180 / 50-51 | 0.585-0.592 / 361-368 | 780-796 / 761-762 | 670-672 (546) | 0.182-0.183 / 128-130 |
| `uuid` event id, v4 | 0.125-0.132 / 13.5-14.9 | 0.183-0.187 / 59-60 | 0.672-0.694 / 449-455 | 848-852 / 832-834 | 672-675 (512) | 0.186-0.189 / 131-134 |
| `uuid` event id, v7 | 0.125-0.133 / 14.0-15.5 | 0.179-0.182 / 56-59 | 0.631-0.651 / 428-438 | 843-847 / 822-828 | 673-682 (512) | 0.187-0.189 / 131-133 |
| head table, `FOR UPDATE` | 0.128-0.139 / 16.3-19.4 | 0.185-0.188 / 63-65 | 0.716-0.725 / 492-498 | 1187-1198 / 894-897 | 747-751 (546) | 0.182-0.184 / 129-131 |
| head table, guarded `UPDATE` | 0.125-0.138 / 15.9-19.7 | 0.184-0.197 / 64-66 | 0.727-0.741 / 505-513 | 1187-1195 / 894-896 | 747-750 (546) | 0.182-0.185 / 129-131 |
| narrow row | 0.123-0.138 / 13.1-15.6 | 0.181-0.190 / 61-63 | 0.704-0.717 / 487-505 | 817-822 / 804-806 | 629-634 (431) | 0.182-0.184 / 133-134 |
| explicit `nextval` | 0.125-0.138 / 13.7-15.9 | 0.186-0.191 / 62-63 | 0.721-0.755 / 497-524 | 905-914 / 892-895 | 744-748 (546) | 0.182-0.190 / 129-133 |

(The uncontended appends always create a new aggregate, so in the head-table arms they measure the stream-creation
path, an `INSERT … ON CONFLICT DO NOTHING` on the head table; appends to existing streams are the contended shape. The
head-table B/row includes the head table, which has one row per stream: ~280 B per one-event stream, amortised to
almost nothing at 100 events.)

### 7.2 Contended appends

8 writers, 10 aggregates, 20 s; four windows per arm (run 1's two / run 2's two). The head-table arms read the
expected order from the head table, the others from the events table.

| Arm | Appended/s (thousands) | Zero-row / unique-violation conflicts (share of attempts) | Burned per 1 000 appended | PostgreSQL CPU µs/attempt | Recorded exec µs/attempt | WAL B/appended |
|---|---|---|---|---|---|---|
| BASE | 23.6, 23.9 / 25.0, 25.8 | ~12% / ~20% | 287-305 | 98.1-105.3 | 22.4-26.1 | 1127-1187 |
| `json` | 22.8, 25.6 / 24.1, 25.6 | similar | 280-292 | 98.5-107.3 | 22.2-24.3 | 1096-1148 |
| `bytea` | 24.7, 25.2 / 24.6, 25.9 | similar | 278-286 | 97.3-99.9 | 21.4-22.2 | 1090-1141 |
| no `UNIQUE(event_id)` | 25.8, 26.4 / 26.0, 27.0 | similar | 281-283 | 93.7-95.8 | 19.5-20.0 | 988-990 |
| `uuid` v4 | 24.0, 24.6 / 25.3, 24.0 | similar | 282-289 | 99.7-103.4 | 22.7-23.4 | 1061-1069 |
| `uuid` v7 | 24.6, 23.6 / 25.6, 24.7 | similar | 279-289 | 98.0-104.5 | 21.1-22.8 | 1044-1054 |
| head table, `FOR UPDATE` | 16.9, 16.9 / 16.8, 17.1 | 34-35% / 0 | **0** | 124.2-126.2 | 65.1-66.1 | 1122 |
| head table, guarded `UPDATE` | 21.7, 21.7 / 21.1, 21.3 | 32% / 0 | **0** | 106.5-111.3 | 40.5-44.1 | 1044-1049 |
| narrow row | 23.9, 23.2 / 25.4, 26.8 | similar | 282-288 | 94.8-105.1 | 21.5-23.7 | 1011-1017 |
| explicit `nextval` | 24.3, 21.9 / 24.4, 25.6 | similar | 285-300 | 99.7-110.5 | 23.4-25.4 | 1125-1179 |

BASE here burns 287-305 per 1 000, more than §6's B variants on the engine's tables (222-262). The two set-ups differ in
the lookup (raw pgjdbc against the engine's JDBI `loadLastPersistedEventRelatedTo`), the payload and the statement
encoding, and the cause was not isolated; within this table all arms share the same set-up.

### 7.3 Per option

**Payload and metadata as `json`, `jsonb` or `bytea`.** Under contention, no difference outside the noise in
throughput or holes (`bytea` and `json` 22.8-25.9 against BASE 23.6-25.8 thousand appends/s); `bytea` used
~2-4 µs less server CPU per attempt in three of four windows. Uncontended, `bytea` < `json` < `jsonb` at 10 and 100
events (100 events: 0.65 / 0.68-0.70 / 0.71-0.73 ms, execution 424-430 / 450-470 / 490-496 µs), the same at one event.
Reads of 100 small events are ~35% faster on `json` or `bytea` (0.117-0.119 against 0.183-0.184 ms; server execution
71-75 against 129-130 µs), consistent with the other doc's −22..−47%. Rows are ~32 B smaller and WAL ~22-24 B per
event smaller than `jsonb` at these sizes. Correctness unchanged. Costs not measured here: `jsonb`'s SQL querying,
the admin UI's use of it, and normalisation (see the other doc and §13.3).

**No `UNIQUE(event_id)` index.** The largest single saving measured: 100-event appends 0.585-0.592 against
0.712-0.725 ms (execution −26%), 10 events 0.172-0.180 against 0.185-0.190 ms, WAL −110 to −130 B per event, size
−73 B per row; under contention 25.8-27.0 against 23.6-25.8 thousand appends/s (about +5%, at the edge of the spread
but higher in all four windows) and 4-10 µs less server CPU per attempt. Holes and per-aggregate order unchanged.
It breaks the database-level guarantee that an event id is unique, and `findEvent(EventId)` would need another path
(§13.3).

**`uuid` event ids, random (v4) and time-ordered (v7).** Typing the column `uuid` instead of `text` saves ~70 B per row
(table plus indexes) and ~55-60 B WAL per event. v7 against v4 at 100 events: 0.631-0.651 against 0.672-0.694 ms,
WAL ~8 B per event less; at 1 and 10 events, and under contention, no difference outside the noise. These tables hold
at most ~30 000 rows per round, so the index locality that v7 buys on a large `UNIQUE(event_id)` index (fewer random
page reads and full-page writes once the index outgrows memory) is not exercised here; that is the case §13.3 expects
the larger effect from, and it was not measured.

**Stream head table** (`aggregate_id text PRIMARY KEY, version bigint`, `fillfactor = 50`, `version` unindexed).
Two variants:
- *Guarded `UPDATE` in the append statement*: `WITH h AS (UPDATE streams SET version = version + n WHERE aggregate_id =
  $id AND version = $expected RETURNING version - n) INSERT … SELECT … FROM h CROSS JOIN unnest(…)`. One statement,
  same round trips as §5.1. A writer that loses the race waits for the winner's commit on the head row, re-checks the
  `WHERE` against the new version (EvalPlanQual), updates nothing, so the `INSERT` produces no row and takes no
  global-order value.
- *`SELECT … FOR UPDATE` first*: a separate statement locks and checks the head row, then one statement inserts the
  events and moves the head. One more round trip per append.

Both: **zero burned values in all eight windows**; every conflict is a zero-row result (32-35% of attempts); event
orders contiguous; head equal to `max(event_order)` everywhere; xid assigned before the first global order. Throughput
under contention: guarded `UPDATE` 21.1-21.7 thousand appends/s (8-18% below BASE's 23.6-25.8), `FOR UPDATE`
16.8-17.1 thousand (about 30% below). Their recorded execution per attempt includes the time spent waiting for the
head-row lock (40-44 and 65-66 µs against BASE's 22-26). The head updates were almost all HOT (338 668 updates /
338 659 HOT, and 434 093 / 434 085 in two sample windows), and the head table stayed at 90-480 KB for 10 rows. For
comparison, §5.3's advisory lock as a separate statement also gave zero holes but at a third of A's throughput, with
one extra round trip, on the engine's tables. Uncontended appends to a new stream cost 2-5 µs more execution, ~280 B
more WAL per one-event append and one head row per stream; at 10 and 100 events they are within ~3% of BASE. Reads
are unchanged (the read does not touch the head table). What it changes: the head table becomes the authority for
the stream's version, so every write path (including any migration or repair tool) must keep it in step, and
deleting or truncating a stream must touch both tables.

**Narrower row** (`global_order`, `event_order`, `timestamp`, `event_type smallint`, `event_revision smallint`, then
`aggregate_id`, `event_id`, `event_payload`, `event_meta`; metadata, causation id, correlation id and tenant merged
into one `jsonb`; event types interned in a `td_event_types` table). Heap −115 B per row (431 against 546), table plus
indexes −110 B, WAL −87 to −95 B per event, about 10% less. Latency and contended throughput within the noise of BASE
(23.2-26.8 thousand appends/s; 282-288 burned). Reading 100 events costs the same (0.182-0.184 ms) with the type names
resolved from an in-memory map. Nearly all of the saving is the interned event type (a ~100-character name against
2 bytes); alignment ordering on its own is worth a few bytes. Costs: rows are not readable without the type registry,
causation and correlation ids leave their own columns (no plain-column index on them), and the registry must be
cached and kept consistent across instances.

**Explicit `nextval` from a per-table sequence instead of an identity column.** No difference outside the noise in
any shape (uncontended 100 events 0.721-0.755 against 0.712-0.725 ms, contended 21.9-25.6 against 23.6-25.8 thousand,
holes 285-300 against 287-305). The xid check found the xid assigned before the explicit `nextval` too, with the
`xid` CTE first; the ordering property depends on the statement shape, not on how the value is drawn.

### 7.4 Not measured

- Large payloads (covered for `json`/`jsonb`/`bytea` by the other doc), large tables (index locality of v7, page
  splits, autovacuum), `fsync=on`, a separate database host, PostgreSQL majors other than 17.5.
- Head-table appends to existing streams uncontended (only creation was timed uncontended): now measured, §8.2. The
  head table under many aggregates with little contention: still not measured.
- Combinations of options (for example, head table + no `UNIQUE(event_id)` + `uuid` + narrow row): now measured, §8.
- Why BASE here burns more values than §6's B (§7.2): now explained, §8.3.
- A `(event_type, global_order)` index and partitioning, from §13.3.

## 8. Combined options

> **Caveat (2026-11-11, §9):** 8.3's idle/wake-up reading is confirmed in §9. Absolute CPU per attempt here depends on how often the CPUs go idle.

Date: 2026-11-11. Findings, not decisions. Same spike, set-up and caveats as §7 (`fsync=off`, PostgreSQL 17.5,
client and database on one 8-CPU VM, fresh container per run, fresh table per arm and round, rotated order
uncontended, palindrome order contended, 20 s windows, two runs, so four contended windows and four uncontended rounds
per arm). Results inside the spread count as no difference.

**Code.** `TableDesignOptionsSpikeIT` gained:
- **COMBO_ALL**: guarded-`UPDATE` head table in the append statement, no `UNIQUE(event_id)`, `uuid` (v4) event,
  causation and correlation ids, interned `smallint` event type (and `smallint` revision), `json` payload and
  metadata, columns in alignment order (`global_order`, `event_order`, `timestamp`, the three `uuid`s, the two
  `smallint`s, then `aggregate_id`, payload, metadata, tenant). Only `UNIQUE(aggregate_id, event_order)`.
- **COMBO_ALL_BYTEA**: the same with `bytea` payload and metadata.
- **COMBO_ALL_NO_HEAD**: the same without a head table, with §5.1's `xid`/`head` CTE statement instead.
- **COMBO_ALL_SCALAR**: COMBO_ALL, but a one-event append binds scalar parameters with no `unnest` (§6's `SCALAR_1`);
  multi-event appends as COMBO_ALL.
- An **existing-stream** uncontended shape (gap a): 1 000 streams are created first (untimed), then each unit of work
  appends 1 or 10 events to the next stream with its correct expected order (no lookup); 1 000 iterations after 50
  warm-up.
- Contended-only **BASE variants** (gap b), each adding one difference to §6's B arm on the engine's table:
  `BASE_TEXT_ARRAYS` (`createArrayOf("text", …)` as §6's B text arrays), `BASE_ENGINE_PAYLOAD` (the exact row the
  engine writes for the same `ProductAddedToOrder`, captured from the engine at the start of each run: payload,
  metadata, type, causation and correlation ids), `BASE_TENANT_INDEX` (the engine's extra index on `tenant`),
  `BASE_LOOKUP_DELAY` (a 50 µs busy-wait between the lookup and the append), `BASE_LOOKUP_SLEEP` (a 50 µs
  `parkNanos` there instead, which took ~120 µs) and `BASE_ENGINE_LIKE` (text arrays + engine row + tenant index +
  busy-wait).
- The contended record now also has the JVM's CPU per attempt, how busy the machine was, and the client time per
  attempt split into lookup (including any delay) and append statement, per outcome.

The existing arms' SQL is unchanged. Command (19 min 21 s; raw output from line 284 of `p0-7-table-design.txt`):

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs \
    -Dit.test=TableDesignOptionsSpikeIT -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true \
    -Dspike.design.arms=BASE,HEAD_GUARDED_UPDATE,HEAD_FOR_UPDATE,COMBO_ALL,COMBO_ALL_BYTEA,COMBO_ALL_NO_HEAD,COMBO_ALL_SCALAR,BASE_TEXT_ARRAYS,BASE_ENGINE_PAYLOAD,BASE_TENANT_INDEX,BASE_LOOKUP_DELAY,BASE_LOOKUP_SLEEP,BASE_ENGINE_LIKE
# knob: -Dspike.design.lookupDelayMicros (50)
```

**Correctness, all 13 arms, both runs.** A stale expected order returned zero rows and took no global-order value; the
3-event append after it continued the stream; a 1-event append in its own transaction (COMBO_ALL_SCALAR's scalar
statement) wrote one row; the xid trigger found an xid before the first global order in both, and the control plain
`INSERT … VALUES` found none. After every contended window every aggregate's orders were contiguous from 0, every
head row equalled `max(event_order)`, and no error other than unique violations occurred.

### 8.1 Contended appends

8 writers, 10 aggregates, 20 s; values per window, run 1's two / run 2's two (ranges where they hardly vary).

| Arm | Appended/s (thousands) | Zero-row / unique-violation (share of attempts) | Burned per 1 000 | PostgreSQL CPU µs/attempt | Recorded exec µs/attempt | WAL B/appended |
|---|---|---|---|---|---|---|
| BASE | 28.5, 26.3 / 25.4, 26.1 | 12-13% / 20% | 289-299 | 92.7-98.9 | 22.6-24.9 | 1178-1357 |
| head, guarded `UPDATE` | 24.9, 21.4 / 20.0, 21.2 | 32-33% / 0 | 0 | 97.2-117.5 | 40.8-48.9 | 1043-1048 |
| head, `FOR UPDATE` | 16.7, 16.7 / 16.7, 16.5 | 34% / 0 | 0 | 126.0-127.4 | 66.3-68.6 | 1122-1124 |
| COMBO_ALL | 22.6, 22.1 / 21.9, 21.7 | 32% / 0 | 0 | 102.8-106.4 | 36.2-39.3 | 701-703 |
| COMBO_ALL_BYTEA | 21.9, 21.9 / 21.9, 22.0 | 32% / 0 | 0 | 103.7-104.9 | 35.9-37.6 | 702 |
| COMBO_ALL_NO_HEAD | 23.4, 26.3 / 27.3, 26.9 | 13% / 19% | 276-279 | 92.5-103.1 | 19.2-20.7 | 720-721 |
| COMBO_ALL_SCALAR | 23.6, 23.4 / 23.2, 23.9 | 31-32% / 0 | 0 | 94.4-96.9 | 31.1-32.0 | 699-702 |

- **The combination keeps the head table's zero burned values** in all twelve head-table combo windows, at
  21.7-23.9 thousand appends/s against BASE's 25.4-28.5 (COMBO_ALL ~15% below BASE, COMBO_ALL_SCALAR ~10% below)
  and against the plain head table's 20.0-24.9 in the same run (no difference outside the noise between COMBO_ALL
  and the plain guarded `UPDATE`; COMBO_ALL_SCALAR is higher than COMBO_ALL in all four paired windows, by
  1.0-2.2 thousand, with ~5-8 µs less recorded execution per attempt).
- **The head table is most of the combination's throughput cost.** Without it (COMBO_ALL_NO_HEAD) the same columns
  and indexes give 23.4-27.3 thousand, within BASE's spread, and burn 276-279 per 1 000 (BASE 289-299).
- **WAL per appended event**: 699-721 B for every combo against 1043-1357 for the text/`jsonb` arms, about −40%.
- `bytea` against `json` in the combination: no difference under contention.

### 8.2 Uncontended (ranges over four rounds)

p50 ms / server execution µs per unit of work. "New" creates a stream (as §7.1); "existing" appends to one (gap a).

| Arm | New, 1 event | New, 10 | New, 100 | Existing, 1 event | Existing, 10 | WAL B/event (new 1 / new 100 / existing 1) | B/row at 100 (heap) | Read 100 events p50 / exec |
|---|---|---|---|---|---|---|---|---|
| BASE | 0.124-0.135 / 13.1-16.7 | 0.186-0.202 / 62-69 | 0.723-0.742 / 497-517 | 0.129-0.141 / 14.8-18.2 | 0.184-0.195 / 62-66 | 905-909 / 893-894 / 918-926 | 745-747 (546) | 0.182-0.195 / 126-139 |
| head, guarded `UPDATE` | 0.128-0.133 / 16.0-18.4 | 0.187-0.204 / 64-72 | 0.721-0.818 / 493-579 | 0.126-0.139 / 16.9-18.3 | 0.186-0.196 / 65-69 | 1183-1187 / 896-897 / 1055-1059 | 750-754 (546) | 0.182-0.187 / 125-132 |
| head, `FOR UPDATE` | 0.127-0.130 / 15.9-17.7 | 0.187-0.203 / 64-71 | 0.627-0.722 / 480-497 | **0.166-0.194 / 21.2-27.5** | **0.224-0.246 / 68-72** | 1183-1191 / 895-897 / 1110-1115 | 749-752 (546) | 0.152-0.185 / 128-136 |
| COMBO_ALL | 0.126 (one round 0.042) / 10.4-15.2 | 0.169-0.190 / 52-59 | 0.594-0.602 / 371-382 | 0.125-0.139 / 15.0-17.4 | 0.173-0.182 / 52-54 | 853-865 / 554-556 / 709-716 | 430-433 (304) | 0.113-0.114 / 66-69 |
| COMBO_ALL_BYTEA | 0.122-0.139 / 13.6-17.6 | 0.169-0.176 / 49-52 | 0.453-0.557 / 325-349 | 0.134-0.145 / 18.8-21.1 | 0.171-0.194 / 51-58 | 849-857 / 555-556 / 709-728 | 431-434 (304) | 0.097-0.114 / 64-72 |
| COMBO_ALL_NO_HEAD | 0.124-0.136 / 11.5-14.6 | 0.174-0.186 / 49-54 | 0.568-0.580 / 356-362 | 0.124-0.138 / 12.8-14.9 | 0.164-0.184 / 49-54 | 571-576 / 550-553 / 576-592 | 426-428 (304) | 0.112-0.116 / 65-69 |
| COMBO_ALL_SCALAR | 0.119-0.128 / 11.0-12.3 | 0.173-0.186 / 51-57 | 0.594-0.606 / 369-376 | 0.120-0.132 / 11.0-13.2 | 0.174-0.184 (one round 0.086) / 44-53 | 849-857 / 554-555 / 709-717 | 432-433 (304) | 0.114-0.117 / 66-71 |

(The two bracketed single-round p50s are outliers of run 1 round 1 that we cannot explain; the other three rounds
agree with each other.)

- **Gap (a), existing streams.** The guarded-`UPDATE` head costs the same as BASE on an existing stream (1 event
  0.126-0.139 against 0.129-0.141 ms, 10 events 0.186-0.196 against 0.184-0.195), with ~130 B more WAL per one-event
  append (the head row's update). `SELECT … FOR UPDATE` first costs one round trip more: 1 event 0.166-0.194 ms
  (+~0.04 ms, +5-10 µs execution) and 10 events 0.224-0.246 ms. COMBO_ALL and COMBO_ALL_SCALAR on an existing stream
  are at or below BASE at 1 event (0.125-0.139 and 0.120-0.132 ms; scalar execution 11-13 µs against 15-18) and
  ~6% faster at 10 events (0.173-0.182 ms).
- **100-event appends**: COMBO_ALL 0.594-0.602 ms against BASE 0.723-0.742 (−18%, execution −26%),
  COMBO_ALL_BYTEA 0.453-0.557, NO_HEAD 0.568-0.580. Most of it is no `UNIQUE(event_id)` plus `json` (§7.3 measured
  each alone).
- **Size**: heap 304 B per row against 546 (−44%), table plus indexes 430-434 against 745-747 B per row (−42%),
  WAL per event at 100 events 554-556 against 893-894 B (−38%).
- **Reads** of 100 events: 0.097-0.117 ms against 0.182-0.195 (≈ −38%), the `json`/`bytea` effect of §7.3.

### 8.3 Gap (b): why BASE burns ~290 values per 1 000 here and §6's B burned 222-262

A value is burned only by a unique-violation conflict: the racer's statement snapshot was taken before the winner
committed, so it passed the `head` check, drew a global order and then failed on `UNIQUE(aggregate_id,
event_order)`. A winner that committed before the racer's statement started produces a zero-row conflict and burns
nothing. The split therefore depends on how long the gap between a writer's lookup and its append is, relative to
the statement itself.

| BASE variant (same table and statement) | Appended/s (thousands) | Zero-row / unique (share) | Burned per 1 000 | PostgreSQL CPU µs/attempt | JVM CPU µs/attempt | Machine busy | Client lookup / append µs (success) |
|---|---|---|---|---|---|---|---|
| BASE | 25.4-28.5 | 12-13% / 20% | 289-299 | 92.7-98.9 | 36.5-45.3 | 74-79% | 49-61 / 75-83 |
| text arrays | 24.5-26.0 | 13% / 19% | 281-283 | 96.7-100.1 | 45.2-47.7 | 72-74% | 60-64 / 81-85 |
| engine's row | 24.7-27.0 | 13% / 20% | 291-294 | 94.9-100.2 | 42.8-46.4 | 73-75% | 56-63 / 79-86 |
| tenant index | 24.2-26.5 | 12-13% / 19-20% | 286-297 | 95.0-102.6 | 42.8-47.2 | 72-74% | 59-65 / 80-87 |
| 50 µs busy-wait after lookup | 36.7-37.6 | 24% / 14% | 219-225 | 54.5-56.6 | 69.4-70.3 | 97% | 71-72 / 43-45 |
| 50 µs `parkNanos` after lookup (~120 µs taken) | 15.4-15.6 | 25% / 11-12% | 181-184 | 113.8-115.9 | 67.7-68.4 | 59-61% | 180-182 / 91-93 |
| engine-like (all four but the park) | 35.9-37.0 | 24-25% / 13-14% | 216-219 | 54.4-56.8 | 70.8-72.3 | 96-97% | 71-72 / 44-47 |

- **Ruled out**: text-array encoding (281-283, at most ~10 fewer than BASE), the engine's exact row (291-294) and
  the tenant index (286-297). None moves the ratio outside or much beyond BASE's spread.
- **What explains it: the gap between lookup and append.** Lengthening only that gap moves the ratio from 289-299
  down to 219-225 (busy-wait, lookup phase ~72 µs) and 181-184 (park, ~180 µs), by turning unique-violation
  conflicts into zero-row ones (20% → 14% → 11-12% of attempts, zero-row 12% → 24-25%). §6's B arm spent 79-107 µs
  per attempt in the engine's lookup (`loadLastPersistedEventRelatedTo` through JDBI: `SELECT *` and event
  deserialisation) against 49-61 µs here, and burned 222-262 — between the busy-wait and BASE, as this predicts.
  The "half of today's holes" for the §5.1 statement is therefore not a fixed property of the statement: it depends
  on the client's lookup-to-append latency.
- **A side finding we cannot fully explain**: the busy-wait arms ran at 36-38 thousand appends/s (BASE 25-29) with
  about 55 µs of PostgreSQL CPU per attempt instead of 93-99, and the append statement itself took 43-47 µs of client
  time instead of 75-83, with the machine 96-97% busy. The parked variant, which leaves CPUs idle, did the opposite
  (114-116 µs per attempt). The most likely reading is that on this VM the cost of CPUs going idle and being woken
  (scheduler and virtualisation wake-up latency, charged partly to the database container) is a large part of the
  per-attempt server CPU, and spinning client threads keep the CPUs from idling. That would also bear on §6.2's
  unattributed server CPU. Not verified with a profiler; it makes absolute CPU-per-attempt numbers on this machine
  sensitive to how busy the client keeps the CPUs.

### 8.4 Not measured

- The combination under many aggregates with little contention, large tables (v7 index locality), large payloads,
  `fsync=on`, a separate database host.
- A busy-wait or park variant of the head-table arms (the head table's throughput relative to BASE may also depend on
  the machine's idle behaviour above).
- A profiler on the VM to confirm the idle/wake-up reading of 8.3.

## 9. Unattributed CPU and the machine

Date: 2026-11-11. Findings, not decisions. Two questions were left open. §6.2: every form of B carries about 15-20 µs
more PostgreSQL CPU per attempt *outside* recorded execution than A. §8.3: a client that busy-waits between lookup
and append cuts PostgreSQL CPU per attempt from 93-99 to about 55 µs, and one that parks raises it to 114-116 µs.
The working hypothesis was that, on this Docker VM, the cost of CPUs going idle and being woken up is a large part
of per-attempt server CPU.

**Short answer.** The hypothesis holds. About half of the PostgreSQL CPU per contended attempt on this machine is
idle and wake-up cost. When no CPU is allowed to go idle, the "outside execution" CPU falls from 59-74 µs to
32-37 µs per attempt, and B's excess outside execution over A disappears. What is left of the A-vs-B difference is
about the size of the recorded execution difference (+2-4 µs). None of the four B-only suspects carries the cost by
itself.

### 9.1 Set-up

**Code.** `UnattributedCpuSpikeIT` (same `spike` package, `@Tag("spike")`, `-Dspike.run=true`) uses raw pgjdbc and
the engine-shaped table. It runs 8 writers on 10 aggregates. Every arm uses the same lookup
(`SELECT coalesce(max(event_order), -1) … WHERE aggregate_id = ?`), the same parameters with the same types and
casts, and the same client code. Only the append statement and the client mode differ.

Statement arms:
- `A_VALUES`: `INSERT … VALUES … RETURNING global_order`.
- `A_SELECT`: the same written as `INSERT … SELECT`.
- `B_NO_CTE`: `A_SELECT` plus the head check as a scalar sub-select in `WHERE`. It has no CTE and no `xid`.
- `B_NO_XID`: the `head`/`ok` CTEs without the `pg_current_xact_id()` CTE.
- `B`: §6's `SCALAR_1` statement, with the `xid`, `head` and `ok` CTEs and two `RETURNING` columns.
- `B_RET1`: `B` with one `RETURNING` column.
- `B_COMMIT0`: `B`, but a zero-row result is committed instead of rolled back.

Client modes:
- `NORMAL`.
- `SPIN`: a 50 µs busy-wait between lookup and append, as in §8.3.
- `BURNER`: the client is unchanged, but a privileged sidecar runs 8 spinning shells at `SCHED_IDLE` in a cgroup
  with `cpu.idle=1`, so no CPU ever executes WFI.

Each window records:
- appends and attempts per second, and the outcome mix;
- the PostgreSQL container's cgroup CPU, split into user and system;
- the writer backends' `/proc/<pid>/schedstat`: on-CPU time and run-queue wait;
- `pg_stat_statements`' recorded execution;
- `/proc/stat` busy fraction, context switches and interrupts;
- arm64 IPIs from `/proc/interrupts` (`IPI1`, "function call", is how a remote wake-up reaches another CPU here).

Run settings: 20 s windows after a 2 s warm-up on a throwaway table, a fresh table per window that is dropped
afterwards, palindrome order (run 2 rotated by one), two runs on fresh containers, PostgreSQL 17.5 with `fsync=off`,
`-Dfailsafe.forkCount=1`, and nothing else building. The root fs was at 84% and `docker system df` showed no
containers and no dangling images, so nothing was pruned. Raw output is in `target/spike-results/p0-9-cpu.txt`,
with perf reports in `target/spike-results/p0-9-perf/`.

**perf worked**, with limits:
- It runs from a privileged `alpine` sidecar with `--pid=host` and `perf` 7.1.5, which sees the whole VM, the JVM
  included.
- The VM has no hardware PMU (`cycles` is not supported), so profiles are `cpu-clock` samples at 997 Hz.
- Kernel symbols resolve, but PostgreSQL's own symbols did not (no `--symfs`), so user-space time is attributed only
  to the `postgres` binary as a whole.
- Interrupts are disabled around a wake-up, so a sample taken inside one lands on the instruction that re-enables
  them. The time spent waking another CPU therefore shows as `__wake_up_sync_key`.

```bash
M="mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test=UnattributedCpuSpikeIT \
   -Dfailsafe.failIfNoSpecifiedTests=false -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true \
   -Dspike.cpu.runs=2 -Dspike.cpu.seconds=20"
$M -Dspike.cpu.arms=A_VALUES,B,A_VALUES@BURNER,B@BURNER,A_VALUES@SPIN,B@SPIN                 # 9.2
$M -Dspike.cpu.arms=A_VALUES,A_SELECT,B_NO_CTE,B_NO_XID,B,B_RET1,B_COMMIT0                    # 9.3
taskset -c 0-3 $M -Dspike.cpu.pgCpus=4-7 -Dspike.cpu.arms=A_VALUES,B,A_VALUES@SPIN,B@SPIN     # 9.4
$M -Dspike.cpu.runs=1 -Dspike.cpu.perf=true -Dspike.cpu.arms=A_VALUES,B,A_VALUES@SPIN,B@SPIN  # 9.2 profile
```

### 9.2 Idle and wake-up: A and B with and without idle CPUs

The figures are ranges over four windows per cell. "Outside" means PostgreSQL CPU minus recorded execution. Call
IPIs are the VM-wide function-call IPIs per attempt.

| Arm @ mode | Appended/s (thousands) | PostgreSQL CPU µs/attempt (user / system) | Recorded exec µs/attempt | Outside µs/attempt | Call IPIs/attempt | Machine busy |
|---|---|---|---|---|---|---|
| A @ NORMAL | 29.6-34.2 | 75.7-85.1 (43-48 / 32-38) | 16.6-16.9 | 59.1-68.2 | 1.46-2.04 | 75-79% |
| B @ NORMAL | 26.0-27.4 | 91.0-95.4 (54-56 / 37-40) | 20.5-21.0 | 70.5-74.4 | 2.09-2.36 | 73-74% |
| A @ SPIN | 40.2-41.1 | 46.9-48.3 (31-32 / 16-17) | 11.9-12.3 | 35.0-36.0 | 0.13-0.14 | 97-98% |
| B @ SPIN | 40.0-40.3 | 48.8-49.3 (34 / 15) | 15.0-15.2 | 33.8-34.2 | 0.12 | 98% |
| A @ BURNER | 11.3-13.8 | 46.8-48.9 (29-30 / 17-19) | 14.3-14.6 | 32.4-34.4 | 0.01-0.07 | 96-100% |
| B @ BURNER | 12.1-13.3 | 51.3-52.5 (34 / 17-19) | 17.3-18.1 | 34.0-34.4 | 0.01-0.02 | 99% |

(A @ NORMAL's 75.7 µs and 34.2 thousand/s are the first window of run 1; the other three are 83.5-85.1 µs and
29.6-30.1 thousand/s.)

- **Removing idle halves the CPU outside execution, in both ways tried.** Spinning the client and a never-idle
  burner each cut it from 59-74 µs to 32-36 µs per attempt. The burner leaves the client's behaviour unchanged.
  System time falls from 32-40 µs to 15-19 µs, and wake-up IPIs from 1.5-2.4 to 0.01-0.14 per attempt.
- **User time falls too**, from 43-56 µs to 29-34 µs. The same statements cost less user CPU when the CPU never went
  to sleep. Cold caches and TLBs after the vCPU halts are the likely reason, but that was not measured: the VM has
  no PMU.
- **The burner is not a usable throughput set-up.** Even at `SCHED_IDLE` it delays the writers' wake-ups: run-queue
  wait was 240-276 µs per attempt against 44-50 µs in `NORMAL`, and throughput was 11-14 thousand per second. Its
  value is that it keeps the client's behaviour and the conflict mix close to `NORMAL`, and still shows the same CPU
  per attempt as `SPIN`.
- **The A-vs-B gap outside execution survives only when CPUs go idle.**
  - `NORMAL`: B's outside execution is +6-12 µs above A in paired windows (+6-21 µs across §9.3's windows).
  - `SPIN`: B is 1-2 µs *below* A.
  - `BURNER`: B and A are equal within 0-2 µs.
  - In `SPIN` and `BURNER`, B's whole CPU excess over A (+1-5 µs) is about its recorded execution excess (+3-4 µs).
  - Under `SPIN`, B and A also append at the same rate (40.0-40.3 against 40.2-41.1 thousand per second). Spinning
    changes the conflict mix, though: B has 25.6% zero-row results, A 36.5% unique violations. So this throughput
    tie is not like-for-like.

**perf profile** (one run, µs per attempt from sample share × 8 CPUs × 20 s ÷ attempts; the windows match §9.2's
figures):

| Arm @ mode | `postgres` binary (user) | kernel, `postgres` processes | of which `__wake_up_sync_key` | `finish_task_switch` | libc |
|---|---|---|---|---|---|
| A @ NORMAL | 34.6-35.9 | 34.4-36.3 | 8.2-8.7 | 3.8-4.0 | 7.9-8.6 |
| B @ NORMAL | 40.4-43.1 | 35.8-38.9 | 9.0-10.3 | 3.8-3.9 | 9.6-10.3 |
| A @ SPIN | 25.3-25.8 | 17.1 | 0.7 | 1.7 | 5.1-5.2 |
| B @ SPIN | 26.8-27.7 | 15.2-15.8 | 0.7 | 1.6-1.7 | 5.1-5.3 |

- In `NORMAL`, waking a sleeping CPU (`__wake_up_sync_key` plus `finish_task_switch`) costs a backend about
  12-14 µs per attempt; in `SPIN` it costs about 2.5 µs. The rest of the kernel time drops by about half as well.
- **Where B's excess in `NORMAL` sits.** It is in user space (+5-8 µs in the `postgres` binary, +1-2 µs in libc),
  not in the kernel. A and B have the same kernel time in both modes. In `SPIN`, B's user-space excess shrinks to
  1-2 µs. The likely reading is that B's few extra µs of executor work become 1.5-2 times as expensive on a CPU
  that keeps going to sleep. That would explain §6.2: the "outside execution" excess was never outside the
  statement. Recorded execution time itself is inflated less than total CPU, and the inflation is not proportional
  to it.

### 9.3 The B-only suspects, one at a time

`NORMAL` mode, two runs, four windows per arm (run 1's two / run 2's two).

| Arm | Appended/s (thousands) | PostgreSQL CPU µs/attempt | Recorded exec µs/attempt | Outside µs/attempt | Zero-row / unique |
|---|---|---|---|---|---|
| `A_VALUES` | 31.9, 27.1 / 27.1, 28.1 | 79.2, 89.6 / 89.9, 87.6 | 16.8-17.6 | 62.4-72.3 | 0 / 30.4-30.7% |
| `A_SELECT` | 31.7, 28.7 / 28.6, 25.0 | 80.0, 86.3 / 86.7, 98.7 | 16.4-19.5 | 63.6-79.2 | 0 / 30.4-30.7% |
| `B_NO_CTE` | 26.8, 24.6 / 26.5, 23.5 | 92.1, 98.5 / 94.1, 103.8 | 20.2-22.8 | 71.9-81.0 | 13.2-13.3% / 18.5-18.6% |
| `B_NO_XID` | 26.1, 23.7 / 24.1, 24.4 | 94.5, 100.6 / 99.4, 100.8 | 20.8-22.5 | 73.7-79.1 | 13.1-13.3% / 18.5-18.7% |
| `B` | 22.3, 24.0 / 23.9, 23.2 | 107.9, 99.8 / 100.6, 102.6 | 21.5-24.7 | 78.1-83.2 | 13.0-13.3% / 18.6-18.8% |
| `B_RET1` | 21.8, 23.0 / 24.5, 24.9 | 108.0, 102.6 / 99.3, 98.0 | 21.5-23.2 | 76.5-84.8 | 13.1-13.2% / 18.7% |
| `B_COMMIT0` | 22.9, 23.1 / 24.4, 23.0 | 103.6, 102.8 / 99.0, 103.1 | 21.3-22.5 | 77.7-81.4 | 13.1-13.2% / 18.6-18.8% |

This run drifted within each container: windows got slower over time, and `A_SELECT`'s last window is the slowest
`A_SELECT`. The ranges are therefore wide. Read the table by its steps:

- **`INSERT … VALUES` → `INSERT … SELECT`: no difference** (62-72 against 64-79 µs outside execution, overlapping).
- **Adding the head check (`A_SELECT` → `B_NO_CTE`): the one step that moves.** It adds +4 µs recorded execution and
  roughly +8 µs PostgreSQL CPU per attempt. It also changes the conflict mix from 30.5% unique violations to 13%
  zero-row plus 18.6% unique, and lowers throughput. More wake-up IPIs per attempt follow (2.3-2.6 against 1.7-2.2).
- **Inlined sub-select against CTEs (`B_NO_CTE` → `B_NO_XID`): no difference outside the spread.**
- **`pg_current_xact_id()` (`B_NO_XID` → `B`): not separable from the noise.** In the paired windows B's outside
  execution is −1 to +2.4 µs from `B_NO_XID`'s in three pairs and +9.5 µs in one (run 1's first B window, the
  slowest of the run).
- **One `RETURNING` column (`B_RET1`): no difference.**
- **Committing zero-row conflicts instead of rolling them back (`B_COMMIT0`): no difference.**

So no single B-only suspect carries an excess outside execution. The head check is a few µs of real executor work.
On this machine that work, and the change it makes to the conflict mix, is amplified by the idle and wake-up effect
of §9.2. With the CPUs kept busy, what is left is about the recorded execution difference. `log_parser_stats`,
`log_planner_stats` and `log_executor_stats` were not used. Once perf placed the excess in executor-side user time
under idle, they would only have repeated the recorded-execution split, at the cost of logging every statement.

### 9.4 Pinning client and database to separate CPU sets

The JVM ran under `taskset -c 0-3` and the PostgreSQL container with `--cpuset-cpus=4-7`, in two runs.

| Arm @ mode | Appended/s (thousands) | PostgreSQL CPU µs/attempt (user / system) | Recorded exec | Outside | Call IPIs/attempt |
|---|---|---|---|---|---|
| A @ NORMAL | 20.0-21.7 | 101.9-111.7 (51-58 / 50-54) | 17.2-19.9 | 84.7-91.8 | 3.62-3.65 |
| B @ NORMAL | 20.3-20.9 | 107.5-111.0 (58-59 / 50-52) | 21.3-22.4 | 86.2-88.6 | 3.67-3.71 |
| A @ SPIN | 15.9-16.4 | 105.1-108.8 | 17.2-17.9 | 87.9-90.9 | 3.91-3.96 |
| B @ SPIN | 15.9-16.1 | 110.2-111.8 | 20.8-21.6 | 89.4-90.2 | 3.83-3.87 |

- **Pinning makes everything worse, not cleaner.** Every round trip now crosses CPU sets, so every wake-up is
  remote: 3.6-4.0 call IPIs per attempt against 1.5-2.4 unpinned. PostgreSQL CPU per attempt rises to 102-112 µs,
  and throughput falls to 16-22 thousand per second.
- Spinning the client no longer helps, because it keeps only the client's CPUs busy. The database's four CPUs still
  go idle between requests.
- In this set-up A and B append at the same rate (20.0-21.7 against 20.3-20.9 thousand per second), and B's outside
  execution is no higher than A's (86-89 against 85-92 µs). This is consistent with §9.2: when the idle and wake-up
  cost dominates both arms equally, B's few µs of extra execution disappear into it.

### 9.5 What this means for the earlier numbers

- **Absolute CPU per attempt on this machine is not a property of the statement.** About half of it depends on
  whether the CPUs go idle between requests. It moved from 47 to 112 µs per attempt for the same `A_VALUES`
  statement across the modes above. Earlier figures (§5, §6, §8) are comparable *within* one table, since the client
  shape was the same, but not as absolute costs. Ratios between arms are inflated whenever the arms differ in
  throughput: the slower arm leaves the CPUs idle more often, and pays more per attempt for it.
- **§6.2's unattributed 15-20 µs is mostly this effect, not anything B does outside execution.** It vanishes with
  idle removed (§9.2), and no B-only suspect carries it (§9.3). §5's "30% fewer appends per second" was measured on
  the same machine and was amplified by the same effect. On a database host whose CPUs do not halt between requests,
  the gap should be closer to the recorded execution difference (+3-5 µs per one-event append) than to §5's
  +25-35 µs. That was not measured on such a host.
- **§8.3's busy-wait result is explained.** The busy-wait kept the CPUs from halting, so it cut both server CPU and
  wake-up cost. It also stretched the lookup-to-append gap, which changed the conflict mix (§8.3's own finding). The
  parked variant left more CPUs idle and paid more.
- **Still open.**
  - Why user-space time grows when CPUs idle: cache/TLB or the host's frequency handling, which needs a PMU this VM
    does not have.
  - The size of the effect on a real database host, or with `fsync=on`.
  - Why the burner slows wake-ups even at `SCHED_IDLE`.
  - PostgreSQL user-space symbols were not resolved, so the excess cannot be named inside the executor.

## 10. Wake-up notification cost (P0.10)

Date: 2026-11-12. Findings, not decisions. The question is what the tier-1 wake-up of the redesign's §11 costs the
append path. §11 as written has the appending unit of work issue one `pg_notify('essentials_eventstore', '<table>')`
per table per commit, inside the transaction. The shard-owned queue already measured that this hurts: a notifying
commit takes PostgreSQL's cluster-wide NOTIFY lock (`pg_locks` object, classid 1262, objid 0) and holds it through
its WAL flush, so notifying commits run one at a time and lose group commit. In the trading demo backends waited on
that lock for 17.5-22.2 % of all active time and 35.8-38.9 % at peaks, and waking after commit cut the loaded queue's
p50 from 5.3-8.7 ms to 2.8-3.2 ms and its p99 from up to 70 ms to 4.8-7.2 ms
([`durable-queue-measurements.md`](../design/durable-queue-measurements.md) §3.14; the design and its rules are in
[`durable-queue-shard-owned.md`](../design/durable-queue-shard-owned.md), "Tier 1 — `LISTEN`/`NOTIFY`"). This spike
asks the same question of the event store's append.

**Short answer.** Notifying inside the append transaction caps the whole database's notifying commits at about one
WAL flush each, one after another. With `fsync=on`, 8 and 20 writers appended 57-63 % and 76-77 % less than without a
notification (8.3-9.3 against 21.5-22.3 thousand per second, and 9.3-9.4 against 39.4-40.1 thousand), commit p50 went
from 0.26-0.36 ms to 0.73-2.0 ms, and 80-93 % of the writers' active time was spent waiting for the NOTIFY lock.
Waking after commit through one coalescing notifier per JVM (the queue's pattern) cost nothing measurable in
throughput or commit latency in any shape, and added about 0.1-0.2 ms (p50) to the wake-up. Sending the notification
after commit but from each writer separately, without coalescing, is not enough: it still serialises on the lock, and
at 20 writers with `fsync=off` it was as slow as notifying inside the transaction.

### 10.1 Setup

**Code.** `WakeupNotifyCostSpikeIT` in the same `spike` package (`@Tag("spike")`, `-Dspike.run=true`), with
`NotifyTapSocketFactory` beside it. It reuses P0.0's container, roles and engine wiring
(`CurrentEngineBaselineSpikeIT.Pg` / `Stack`, which gained `Pg.start(boolean fsync)`) and P0.4's `Writer` for fresh
tables. Raw output, including one `CSV` line per window, goes to `target/spike-results/p0-10-notify.txt`. Through the
script: `scripts/run-phase0-spikes.sh --container --suite notify`.

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test=WakeupNotifyCostSpikeIT \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
```

Knobs: `-Dspike.notify.shapes` (default `1,8,20,8@10`), `-Dspike.notify.arms`, `-Dspike.notify.seconds` (default 8),
`-Dspike.notify.fsync` (default `off,on`; ignored on an external server, which runs one pass with its own settings),
`-Dspike.notify.listener` (`block`, the default, or `poll`, see 10.4).

**Append shape.** The current engine's append (`PostgresqlEventStore.appendToStream` in an
`EventStoreManagedUnitOfWorkFactory` unit of work, JDBI `PreparedBatch`) on tables created by
`SeparateTablePerAggregateTypePersistenceStrategy`, one event per unit of work. "New aggregates": each unit of work
appends to a new aggregate with expected order `NO_EVENTS_PREVIOUSLY_PERSISTED`. "8 on 10": 8 writers on 10
pre-created aggregates, each unit of work reads the last event order and appends after it, as in §5; a conflict rolls
back and sends nothing. Not the §5.1 single-statement append: the notification cost is in the commit, not in the
statement, so the engine's append is the simpler baseline. Hikari pool 24.

**Arms.**
- `NONE`: no notification.
- `IN_TX`: `SELECT pg_notify(?, ?)` on the unit of work's connection after the append, before commit (§11 as written).
  This is one more statement and round trip inside the transaction. The new engine could fold it into the append
  statement and save the round trip, but not the lock, which is taken at commit.
- `AFTER_COMMIT`: after the commit returns, the writer queues the payload on one per-JVM notifier. Its thread sends
  whatever accumulated while its previous send was in flight as one `SELECT pg_notify(?, key) FROM unnest(?::text[])`
  in autocommit, on its own connection and role (`notifier`). This is a copy of the queue's `ShardWakeupNotifier`
  send loop. Identical payloads coalesce, so one table gives one key per batch.
- `AFTER_COMMIT_DIRECT`: after the commit returns, each writer sends its own `pg_notify` as a separate autocommit
  statement on a pooled connection, without coalescing. It separates "outside the append transaction" from
  "coalesced".

**Listener and wake latency.** One `LISTEN essentials_eventstore` connection drains notifications in every arm, with
pgjdbc's `getNotifications(50)` (as the queue's `ShardWakeupListener` does), and counts what it receives. About every
5 ms one successful append is tagged: its payload is `<table>|s<n>` instead of `<table>`. Wake latency is the time from
that append's commit returning in the writer to the tagged notification's bytes arriving on the listener's socket.
The socket is tapped (`NotifyTapSocketFactory`, a pgjdbc `socketFactory` that frames the server's messages), because
pgjdbc's own API could not be used for this (10.4). For `IN_TX` the notification can arrive before the writer has
seen its commit return, so its figure is close to zero by construction: it is the floor, not a cost.

**Run.** Per shape, the arms in a palindrome (`NONE, IN_TX, AFTER_COMMIT, AFTER_COMMIT_DIRECT` and back), each window
8 s on a fresh table after a 1 s warm-up on the same table, the table dropped and a `CHECKPOINT` taken after each.
`pg_stat_activity` sampled every ~2 ms for the writer and notifier roles, `pg_locks` every ~20 ms for waiters on the
NOTIFY lock. Two passes, each on a fresh PostgreSQL 17.5 container: `fsync=off` (Testcontainers' default, as every
earlier spike) and `fsync=on`. `synchronous_commit=on`, `wal_sync_method=fdatasync`, `full_page_writes=on` in both.
8-CPU arm64 Docker VM, client and database on it, `-Dfailsafe.forkCount=1`, nothing else building, 10 min 22 s in all.
Every notification sent was received in every window.

**`fsync` caveat.** The NOTIFY lock is held through the commit's WAL flush, so its cost scales with the flush time.
With `fsync=off` the flush is a `write()` to the page cache, and the in-container result *understates* `IN_TX`'s
cost. The `fsync=on` pass flushes to the VM's virtual disk, which is fast: one writer's commit took 0.09-0.13 ms
(p50) against 0.04-0.05 ms with `fsync=off`, so a flush costs about 50-80 µs here. A real disk without a write cache
that can be trusted takes far longer per flush, so `IN_TX`'s ceiling there is lower still. The dedicated machine
(`event-store-phase0-external-db.md`) is the real test.

### 10.2 Results

Ranges over the two windows of each cell. Commit = the time from the end of the unit of work's body to the commit
returning. Lock = the share of the writers' active backend-samples waiting on `Lock:object` (the NOTIFY lock, as
`pg_locks` confirmed). WALWrite = `LWLock:WALWrite`, waiting for another backend's WAL flush, which is what group commit
looks like. Wake = wake latency p50 / p99 over all samples of both windows' ranges.

**`fsync=on`:**

| Shape | Arm | Appends/s (thousands) | Unit of work p50 / p99 (ms) | Commit p50 / p99 (ms) | Lock | WALWrite | PostgreSQL CPU µs/append | Wake p50 / p99 (ms) |
|---|---|---|---|---|---|---|---|---|
| 1 writer | NONE | 5.2-6.2 | 0.15-0.20 / 0.26-0.27 | 0.09-0.11 / 0.15-0.16 | 0 | 0 | 62-74 | - |
| | IN_TX | 4.1-4.5 | 0.21-0.24 / 0.33-0.34 | 0.12-0.14 / 0.18 | 0 | 0 | 105-120 | 0.013-0.014 / 0.03 |
| | AFTER_COMMIT | 4.7 | 0.21 / 0.33 | 0.12 / 0.20-0.21 | 0 | 0 | 127-128 | 0.087 / 0.13-0.14 |
| | AFTER_COMMIT_DIRECT | 4.2-4.3 | 0.21 / 0.27 | 0.13 / 0.17 | 0 | 0 | 111 | 0.066 / 0.09 |
| 8 writers | NONE | 21.5-22.3 | 0.36-0.37 / 0.47-0.49 | 0.26-0.27 / 0.35-0.37 | 0 | 65 % | 101-104 | - |
| | IN_TX | 8.3-9.3 | 0.85-0.87 / 1.02-1.59 | 0.73-0.75 / 0.88-1.41 | 80-81 % | 0 | 141-151 | 0.013-0.014 / 0.04-0.05 |
| | AFTER_COMMIT | 10.8 / 21.8 (see below) | 0.37-0.48 / 0.51-6.1 | 0.27-0.35 / 0.39-5.1 | 0 | 66-70 % | 120-168 | 0.10-0.18 / 0.21-6.0 |
| | AFTER_COMMIT_DIRECT | 18.3-18.4 | 0.36 / 0.52-0.53 | 0.26 / 0.39-0.40 | 0.7-0.8 % | 57 % | 143 | 0.073 / 0.17 |
| 20 writers | NONE | 39.4-40.1 | 0.48 / 0.83-0.86 | 0.36 / 0.61-0.63 | 0 | 74-75 % | 85 | - |
| | IN_TX | 9.3-9.4 | 2.12-2.14 / 2.32-2.35 | 2.00-2.01 / 2.20-2.22 | 93 % | 0 | 142-143 | 0.013 / 0.04 |
| | AFTER_COMMIT | 38.1-38.6 | 0.50-0.51 / 0.90-0.91 | 0.37-0.38 / 0.67-0.69 | 0 | 75 % | 92-94 | 0.14 / 0.35-0.38 |
| | AFTER_COMMIT_DIRECT | 29.5 | 0.47 / 0.80-0.81 | 0.35 / 0.62-0.63 | 21-23 % | 50-51 % | 123-124 | 0.18-0.19 / 0.58 |
| 8 on 10 | NONE | 12.0-12.1 | 0.42-0.43 / 0.62-0.64 | 0.25 / 0.38-0.40 | 0 | 33-34 % | 211-212 | - |
| | IN_TX | 6.7 | 0.77-0.78 / 1.05-1.13 | 0.53-0.54 / 0.80-0.86 | 44-45 % | 0 | 324-325 | 0.016-0.018 / 0.09-0.10 |
| | AFTER_COMMIT | 12.1 | 0.42 / 0.62-0.63 | 0.25 / 0.39-0.40 | 0 | 33-35 % | 231 | 0.10 / 0.23 |
| | AFTER_COMMIT_DIRECT | 11.0-11.2 | 0.43 / 0.64 | 0.25 / 0.39-0.40 | 0.2 % | 32 % | 243-245 | 0.072-0.073 / 0.16 |

One `AFTER_COMMIT` window at 8 writers (the first) ran at 10.8 thousand per second with a commit p99 of 5.1 ms and a
wake p99 of 6.0 ms. Its palindrome twin ran at 21.8 thousand with 0.39 ms and 0.21 ms, like `NONE`. The slow window's
writers spent 70 % in `LWLock:WALWrite` and nothing on the NOTIFY lock, so it looks like a VM stall during WAL flushing
rather than an effect of the notifier; it was not investigated further.

**`fsync=off`:**

| Shape | Arm | Appends/s (thousands) | Unit of work p50 / p99 (ms) | Commit p50 / p99 (ms) | Lock | WALWrite | PostgreSQL CPU µs/append | Wake p50 / p99 (ms) |
|---|---|---|---|---|---|---|---|---|
| 1 writer | NONE | 7.1-7.6 | 0.13 / 0.17-0.22 | 0.044-0.045 / 0.05-0.07 | 0 | 0 | 68-71 | - |
| | IN_TX | 5.0-5.3 | 0.19 / 0.22-0.31 | 0.053-0.056 / 0.06-0.10 | 0 | 0 | 116-125 | 0.020 / 0.03-0.05 |
| | AFTER_COMMIT | 6.5-6.6 | 0.14 / 0.22-0.24 | 0.047-0.048 / 0.08 | 0 | 0 | 132-134 | 0.094-0.097 / 0.14-0.18 |
| | AFTER_COMMIT_DIRECT | 5.2 | 0.14 / 0.17-0.18 | 0.044-0.046 / 0.06 | 0 | 0 | 115-117 | 0.072-0.073 / 0.09-0.10 |
| 8 writers | NONE | 40.0-48.3 | 0.17-0.19 / 0.35 | 0.053-0.066 / 0.17-0.18 | 0 | 7-11 % | 70-79 | - |
| | IN_TX | 27.7-27.9 | 0.28 / 0.47-0.48 | 0.091 / 0.26 | 23-24 % | 0 | 117-118 | 0.010-0.011 / 0.11 |
| | AFTER_COMMIT | 53.1-62.4 | 0.11-0.15 / 0.33 | 0.032-0.048 / 0.16-0.17 | 0 | 9-10 % | 62-70 | 0.11-0.12 / 0.29-0.33 |
| | AFTER_COMMIT_DIRECT | 28.9 | 0.19 / 0.33 | 0.065 / 0.17 | 7 % | 4 % | 115 | 0.090-0.091 / 0.23 |
| 20 writers | NONE | 67.4-67.7 | 0.26 / 0.99 | 0.12 / 0.39-0.42 | 0 | 18-19 % | 63 | - |
| | IN_TX | 24.5-26.5 | 0.74-0.80 / 1.07-1.27 | 0.55-0.59 / 0.84-0.98 | 83-84 % | 0 | 126-132 | 0.010-0.013 / 0.10-0.15 |
| | AFTER_COMMIT | 62.2-65.2 | 0.27-0.28 / 0.94-1.04 | 0.13 / 0.39-0.44 | 0 | 19-20 % | 67-69 | 0.16-0.18 / 0.50-0.58 |
| | AFTER_COMMIT_DIRECT | 26.3-27.1 | 0.20-0.21 / 0.42-0.46 | 0.07 / 0.23-0.25 | 80 % | 0.6-0.7 % | 129-132 | 0.54-0.55 / 0.86-0.90 |
| 8 on 10 | NONE | 34.1-34.2 | 0.10 / 0.40-0.42 | 0.018 / 0.12-0.13 | 0 | 1.3 % | 103 | - |
| | IN_TX | 13.2-14.8 | 0.36-0.39 / 0.59-0.60 | 0.068-0.076 / 0.20 | 3-4 % | 0 | 203-223 | 0.016-0.020 / 0.11-0.13 |
| | AFTER_COMMIT | 32.7-34.0 | 0.12 / 0.37-0.40 | 0.019-0.020 / 0.11-0.12 | 0 | 1.6-1.7 % | 112-116 | 0.10 / 1.1-1.4 |
| | AFTER_COMMIT_DIRECT | 14.1-17.7 | 0.24-0.32 / 0.50-0.64 | 0.045-0.066 / 0.18-0.19 | 1 % | 1-1.4 % | 180-211 | 0.08-0.09 / 0.24-0.55 |

**Notifications sent and received.** Every notification sent was received, in every window. `IN_TX` and
`AFTER_COMMIT_DIRECT` send one per successful append. `AFTER_COMMIT` sent 0.16-0.30 batches per append at 8 and 20
writers with `fsync=off`, 0.29-0.48 with `fsync=on`, 0.49 and 0.69-0.70 for 8 on 10, and one per append with one
writer (nothing to coalesce). Batches were one key each, apart from the tagged samples: every append went to the same
table.

### 10.3 Findings

- **Inside the transaction, notifying commits serialise, and group commit is lost.** At 20 writers with `fsync=on`
  `IN_TX` ran at 9.3-9.4 thousand appends per second, the same as at 8 writers (8.3-9.3): about 107 µs per commit, one
  after another, which is a WAL flush plus the commit's own work. The writers' wait profile changes from
  `LWLock:WALWrite` (65-75 %, waiting for a group flush to finish) to `Lock:object` (80-93 %, waiting for the NOTIFY
  lock), and `pg_locks` showed 5.4 (8 writers) and 17.4 (20 writers) backends waiting on that lock on average, in
  practically every sample. This is the queue's §3.14 mechanism, reproduced on the append path.
- **The cost grows with concurrency and with the flush time.** With `fsync=off` the lock is held for a shorter flush,
  so `IN_TX` lost 30-43 % at 8 writers and 61-64 % at 20, against 57-63 % and 76-77 % with `fsync=on`. The `fsync=off`
  numbers are the optimistic bound; on a disk with real flush latency the ceiling is lower again.
- **At one writer `IN_TX` costs the extra round trip and some CPU, not the lock.** 25-35 % fewer appends with
  `fsync=off`, 13-34 % with `fsync=on`, and no lock wait. That part is what folding the notify into the append
  statement would save; the lock is not.
- **Contended aggregates make it worse in a second way.** For 8 writers on 10 aggregates with `fsync=off`, `IN_TX`
  waited little on the NOTIFY lock (3-4 %) but 19 % on `Lock:transactionid`: the extra statement inside the
  transaction keeps the inserted row's uniqueness check pending longer, so conflicting writers wait for it. Appends
  fell 57-61 %. With `fsync=on` the lock dominates again (44-45 %), and appends fell 44-45 %.
- **After commit, coalesced, costs nothing measurable.** `AFTER_COMMIT` matched `NONE` within the window-to-window
  spread at every shape with `fsync=on` (21.8 against 21.5-22.3, 38.1-38.6 against 39.4-40.1, and 12.1 against
  12.0-12.1 thousand per second, commit p50/p99 equal), apart from the one slow window above. With `fsync=off` it was
  3-8 % below `NONE` at 20 writers and 0-4 % on contended aggregates, and above it at 8 writers (53-62 against 40-48
  thousand per second), which is the VM's idle and wake-up effect (§9) more than anything the notifier does. The
  notifier's backend never waited on the NOTIFY lock: it is the only notifying session, so the lock is always free.
  At one writer it cost 7-15 % with `fsync=off` and 10-24 % with `fsync=on`, from the notifier's own statement per
  append competing for the CPU and the WAL (nothing to coalesce at one writer).
- **After commit but per writer is not enough.** `AFTER_COMMIT_DIRECT` takes the append transaction off the lock,
  but every writer's own notify transaction still takes it and still flushes (`pg_notify` assigns an xid, so its
  commit writes and flushes WAL). At 20 writers with `fsync=off` it waited 80 % on the lock and appended 60 % less
  than `NONE`, as slow as `IN_TX`; with `fsync=on` it lost 14-18 % at 8 writers and 25-26 % at 20, with 21-23 % lock
  waits. Coalescing is what removes the cost, not merely moving the notify out of the transaction.
- **Wake latency after commit is about 0.1-0.2 ms more.** Commit returned to notification on the listener's socket:
  `AFTER_COMMIT` p50 0.09-0.18 ms and p99 0.13-0.58 ms in every window but the slow one above (and 1.1-1.4 ms p99 for
  8 on 10 with `fsync=off`), against 0.01-0.02 ms for `IN_TX`, whose notification leaves at commit. That is one
  hand-off to the notifier thread plus one statement. It is the same order as the queue's tier 1 (1.76 ms p50 end to
  end including the read, shard-owned design, "Tier 3").
- **A crash between commit and notify loses the hint.** As in the queue: the event is committed, but no other JVM is
  woken; their tier-0 backstop (§11, the backstop interval) finds it. Tier 2, the same-JVM wake-up, is unaffected:
  it never involves the database.

### 10.4 Side finding: pgjdbc's `getNotifications` under continuous traffic

The first smoke runs measured wake latency when `getNotifications(50)` returned, and got p50 values of 170-500 ms in
1 s windows, roughly uniform over the window. pgjdbc 42.7.14's `QueryExecutorImpl.processNotifies(timeout)` sets the
timeout to −1 after the first notification and keeps reading while `hasMessagePending()` is true. While notifications
keep arriving it does not return, and then hands out everything at once: in the full run the listener got 75 114
notifications in 2 reads over an 8 s window (`IN_TX`, 20 writers, `fsync=on`), and 1-350 reads per 8 s window at
any load, including one writer at about 5 thousand notifications per second. Measured through pgjdbc's API, wake latency was 0.3-2.0 s
p50 in every notifying window. A `poll` variant (`SELECT 1` on the listener connection, then the no-argument
`getNotifications()`) did no better. The server's notify interrupt loops while new signals keep arriving and does not
flush before `ReadyForQuery`, so the `SELECT 1` did not return until the traffic paused (5 reads in 2 s). The
notifications themselves reach the socket promptly: the tap sees them within 0.01-0.2 ms.

The queue's `ShardWakeupListener` drains with `getNotifications(200)`, so the same holds for it: under a continuous
stream of notifications, its wake-ups are delivered only when the stream pauses. At the queue's rates and with its
coalescing that may never have shown, and its backstop sweep bounds the damage. This spike did not test the queue, and
did not look for a pgjdbc setting or API that avoids it. Any tier-1 listener built on pgjdbc's `getNotifications`
needs a test under sustained notification traffic.

### 10.5 How this relates to the queue's §3.14

The mechanism is the same, and the append path shows it in isolation. The queue measured it end to end, in the
trading demo (lock wait 17.5-22.2 % of active time, 35.8-38.9 % at peaks) and in a loaded-cost IT (p50 halved, p99 from
up to 70 ms to about 6 ms). Here, with only appending writers and nothing else in the database, the lock took 80-93 %
of the writers' active time at 8 and 20 writers with `fsync=on`, and throughput fell to one commit per flush. The fix
the queue adopted — write without `pg_notify`, wake in memory for the same JVM, and send a coalesced batch after
commit for other JVMs — removed the cost on the append path too. The extra arm adds one thing the queue did not
measure: moving the notify out of the transaction without coalescing keeps most of the cost.

### 10.6 Not measured

- A real disk with `fsync=on` (the dedicated machine): the most informative pass, and the one this VM cannot give.
- Several tables per commit, several JVMs notifying at once (each with its own notifier, so the lock is contended
  again, once per batch), and other notifying traffic in the same cluster (the lock is cluster-wide).
- The notify folded into the §5.1 append statement (saves `IN_TX`'s round trip, not its lock).
- A listener that delivers notifications as they arrive (10.4), and the queue's listener under sustained traffic.
- PostgreSQL 18 and later. Whether a newer major shortens the lock's hold was not checked.

## 11. Re-reading the outstanding holes in the tail statement (P0.11)

Date: 2026-11-12. Findings, not decisions. P0.5's finding F1 (redesign §4.2, proposed revision) has the tail
statement re-read the outstanding holes, so that a late commit is published before anything above it and `FRESH`
keeps per-aggregate order: `WHERE global_order > :cursor OR global_order = ANY(:holes)`, with ranges after a sequence
jump, rows returned in `global_order`. §19 risk 13 asked whether the planner keeps both halves as index scans and what
the statement costs with many holes, or with a long writer holding an old hole while the cursor moves on.

**Short answer.** The `OR` form is only safe as a *custom* plan. Under a generic plan (a named server-side statement
after its five custom plans, or `plan_cache_mode=force_generic_plan`) PostgreSQL walks the primary key from the lowest
`global_order` with the whole predicate as a filter, reading every row below the cursor: 300 ms per probe on the
2-million-row table here, against 10 µs for today's tail read. Under the default `plan_cache_mode=auto` the server
chose that plan by itself on a 300 000-row table with 1 000 holes (smoke run); on the 2-million-row table it stayed
custom in this run, so whether it flips depends on table size and hole count, not on anything the engine controls.
Adding a jump range with `OR global_order BETWEEN lo AND hi` makes even the custom plan take that path (280-300 ms).
Writing the same read as one statement with `UNION ALL` — the tail read as one branch, `global_order = ANY(:holes)` as
another — gives index scans in every plan mode, the custom plan's cost when no hole is outstanding, and the same
server time as the `OR` form at every hole count. Ranges as a further `UNION ALL` branch over `unnest` are correct but
cross PostgreSQL's JIT threshold through a poor row estimate (5-8 ms custom, 80-95 ms generic, on the engine's
layout). Cost grows with the number of holes, not with how far below the cursor they lie: about 10 µs of server time
up to 10 holes, about 20 µs at 100 and about 120 µs at 1 000, plus about 170 µs of planning per probe at 1 000 holes
when the plan is custom.

### 11.1 Setup

**Code.** `TailHoleReReadSpikeIT` in the `spike` package (`@Tag("spike")`, `-Dspike.run=true`, external-server
capable through `SpikeServer`). Raw output: `target/spike-results/p0-11-tail-holes.txt` (`PLAN` and `CSV` lines, one
per plan and per timed cell) and `p0-11-tail-plans.txt` (plan shapes, full `EXPLAIN` text for a subset). Through the
script: `scripts/run-phase0-spikes.sh --container --suite tail`.

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test=TailHoleReReadSpikeIT \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
```

Knobs: `-Dspike.tail.rows` (default 2 000 000), `-Dspike.tail.designs` (`BASE,COMBO`), `-Dspike.tail.variants`,
`-Dspike.tail.protocols`, `-Dspike.tail.iterations` (200), `-Dspike.tail.limit` (page size, 1 000),
`-Dspike.tail.slowMillis` (50), `-Dspike.tail.timeoutMillis` (5 000), `-Dspike.tail.image` (`postgres:17.5`).

**Tables.** Two tables, each bulk-loaded with 2 million rows: `BASE`, the engine's current layout (`text` ids,
`jsonb` payload and metadata, `UNIQUE (aggregate_id, event_order)` and `UNIQUE (event_id)`; 822 MB, 1 056 MB with
indexes), and `COMBO`, the combined option of §8 (fixed-width columns first, `uuid` ids, `smallint` type, `json`
payload, no `UNIQUE (event_id)`; 473 / 594 MB). Payload about 120 bytes of JSON. The primary key on `global_order` is
43 MB in both. Head 3 000 000; a sequence jump of one million values 150 000 rows below the head (`[1 850 001,
2 850 000]` holds no rows); 2 000 rows deleted to make holes, then `VACUUM (ANALYZE)`: a *near* pool of 1 000 values
spaced 3 apart from 200 below the head, and a *far* pool of 1 000 from 100 000 below the head (a long writer holding an
old value while the cursor moved 100 000 on). Every hole in the measurement is still a hole (no row), which is what an
outstanding hole normally is; a late commit adds one row to the result.

**Cases.** Holes outstanding 0, 1, 10, 100, 1 000, from the near or the far pool (the nearest first); cursor 0, 1 or
100 rows below the head, so each probe reads that many new rows. Nothing is written while measuring. Page `LIMIT`
1 000.

**Variants.** Every variant carries §4.10's running-set sub-select (`(SELECT array_agg(backend_xid::text) FROM
pg_stat_activity WHERE backend_xid IS NOT NULL) AS running`) and returns every event column.

- `A`: `WHERE global_order > ? ORDER BY global_order LIMIT ?`, today's design. The baseline; it does not re-read holes.
- `B`: `WHERE (global_order > ? OR global_order = ANY(?)) ORDER BY global_order LIMIT ?` (F1 as written).
- `C`: `B` plus `OR global_order BETWEEN ? AND ?`, the jump as one range (always the one-million-value jump here).
- `C_UNNEST`: `WHERE (global_order > ? OR EXISTS (SELECT 1 FROM unnest(?::bigint[], ?::bigint[]) r(lo, hi) WHERE
  global_order BETWEEN r.lo AND r.hi))`, holes and the jump as an array of ranges.
- `D`: `SELECT u.*, <running> FROM ((SELECT … WHERE global_order > ? ORDER BY global_order LIMIT ?) UNION ALL (SELECT …
  WHERE global_order = ANY(?))) u ORDER BY u.global_order`. One statement, one snapshot; the holes do not count
  against the page `LIMIT`.
- `D_RANGES`: `D` plus a third branch `SELECT x.* FROM unnest(?::bigint[], ?::bigint[]) r(lo, hi) CROSS JOIN LATERAL
  (SELECT … WHERE global_order BETWEEN r.lo AND r.hi) x` for the jump.
- `E`: two statements, `A` and then, if holes are outstanding, `SELECT … WHERE global_order = ANY(?) ORDER BY
  global_order`. For the round-trip comparison only: two snapshots, so it does not meet the model's constraint (a row
  committed between them is missed by the first and may be returned by the second after rows above it).

**Plan shapes.** For 13 cases per table (every hole configuration with one new row, plus 0 and 100 new rows with no
holes and with 1 000 far holes), each statement was `PREPARE`d at SQL level and run as `EXPLAIN (ANALYZE, BUFFERS)
EXECUTE` under `force_custom_plan` (the plan the simple protocol and pgjdbc's unnamed statements get), under
`force_generic_plan`, under `auto` after five executions with the same parameters, and under `auto` after five
executions with no holes and no jump (`AUTO_DRIFT`); `pg_prepared_statements.generic_plans` before and after says which
plan the `EXPLAIN` used. A (statement, mode) pair slower than 50 ms twice in a row was not explained again.

**Timings.** Each case and variant on three pgjdbc connections: `prepareThreshold=0` (extended protocol, unnamed
statement: a parse, a custom plan and a bind every time), `prepareThreshold=1` under `auto` (named server-side
statement) and `prepareThreshold=1` under `force_generic_plan`. Fresh connection per cell, six warm-up probes, then
200 timed probes reading every column of every row; client p50 / p99; server execution and planning time, buffers and
rows per probe from `pg_stat_statements` (reset per cell, `track_planning=on`). A cell whose first probe took over
50 ms was timed for two probes only, and after two such cells the (variant, protocol) pair was skipped.

**Sanity.** Before measuring, every variant on every protocol (including the simple protocol) was given ten absent
holes plus two values that exist, with 100 new rows: every variant except `A` returned the two late rows and the 100
new rows, in `global_order` for `B`, `C`, `C_UNNEST`, `D` and `D_RANGES` (and `A` the 100 new rows). `E` returned the
same set across its two statements, the late rows last.

**Machine.** PostgreSQL 17.5 in a container on the 8-CPU arm64 Docker VM, `fsync=off`, `shared_buffers=1GB` (both
tables fit; every buffer access was a hit), `jit=on`, `random_page_cost=4`, other settings default, pgjdbc 42.7.14,
client on the same VM (§9 applies: absolute latencies are the VM's). One run, 3 min 55 s including the bulk loads.

### 11.2 Plan shapes

Identical on both tables, at every hole count and position unless noted.

| Variant | Custom plan | Generic plan | `auto` (own params / after no-hole history) |
|---|---|---|---|
| A | `Limit > Index Scan (Index Cond > $1)` | same | same |
| B | `Limit > Sort > Bitmap Heap Scan > BitmapOr > 2 × Bitmap Index Scan` — both halves index conditions | **`Limit > Index Scan` with the whole predicate as `Filter`, 1 997 999 rows removed, 110 731 buffers, ~300 ms** | custom in this run (2 M rows); **generic, 610-625 ms, at 1 000 holes on a 300 000-row table** in the smoke run |
| C | **`Limit > Index Scan` with `Filter`, ~280-310 ms** | same | same |
| C_UNNEST | **`Limit > Index Scan` with `Filter` and a `SubPlan` over `unnest` per row, ~430-510 ms** | same | same |
| D | `Merge Append` of `Limit > Index Scan (> $1)` and `Sort > Index Scan (= ANY)`; with 100 new rows `Sort > Append` of the same two scans | same index scans | same |
| D_RANGES | `D` plus `Nested Loop (Function Scan on unnest > Index Scan BETWEEN)` — index conditions throughout; BASE: JIT compiled (cost 130 000 > `jit_above_cost`), **5-8 ms** | same shape, JIT with inlining and optimisation, **80-95 ms** | as custom |
| E | `Limit > Index Scan` and `Index Scan (= ANY)` | same | same |

Why `B` generic and `C` fail: an `OR` cannot be an index condition of one ordered index scan, so the planner chooses
between a `BitmapOr` plus a sort and walking the primary key in order with the predicate as a filter, stopping at the
`LIMIT`. The walk is cheap when it expects the `LIMIT`'s rows early. A generic plan estimates `global_order > $1` at a
third of the table (666 000 rows), and the jump's `BETWEEN` is estimated at about 19 500 rows from the histogram (the
range is empty, but the statistics do not know); either way the planner expects 1 000 matches long before the end and
picks the walk. The matches are all at the far end of the index, so the walk reads the whole table. The custom plan of
`B` knows the cursor is near the head and that the hole array has *n* elements, so it uses the `BitmapOr`. `D` never
has the choice: each branch is a separate index scan with its own index condition, and a generic estimate only changes
the cost, not the shape. `D_RANGES`' range branch is estimated at 222 000 rows (custom, BASE) or 2.2 million (generic)
for an empty range; the plan stays right, but the estimated cost crosses `jit_above_cost` (100 000) and, generic, the
inlining and optimisation thresholds (500 000), and the JIT compile is the whole cost (`JIT: … Emission 7.1 ms`).
COMBO's custom estimate stayed under the threshold.

**The generic-plan trap, precisely.** pgjdbc's default `prepareThreshold=5` makes the engine's tail statement a named
server-side statement from its fifth execution, and the server then compares the generic plan's estimated cost with
the average of the custom plans so far. The custom cost of `B` grows with the length of the hole array (7 876 at
1 000 holes on 2 M rows, ~13 with none), the generic estimate does not depend on it, and it grows with the table
(18 717 on 2 M rows, much less on 300 000). Once the average custom cost exceeds the generic estimate, the server uses
the generic plan, and from then on every probe reads the whole table below the cursor, holes or not. That happened in
the smoke run at 1 000 holes and 300 000 rows; with a no-hole history (`AUTO_DRIFT`) it did not, so the trap waits for
a burst of holes (a long writer, a rollback storm) and then stays. Nothing in the result reveals it except latency.

### 11.3 Cost

Server execution time per probe (µs, `pg_stat_statements`), client p50 (µs) in brackets, BASE, one new row per probe.
Unnamed = `prepareThreshold=0` (custom plan, parse and plan every probe); generic = named statement under
`force_generic_plan`. `auto` behaved as custom throughout in this run and lay between the two on the client.

| Variant / holes | 0 | 1 near | 10 near | 100 near | 1 000 near | 1 far | 10 far | 100 far | 1 000 far |
|---|---|---|---|---|---|---|---|---|---|
| A unnamed | 10 (224) | 9 (204) | 9 (207) | 10 (226) | 9 (209) | 9 (202) | 9 (207) | 9 (213) | 9 (202) |
| A generic | 8 (35) | 8 (32) | 8 (34) | 11 (57) | 7 (31) | 8 (32) | 7 (31) | 8 (32) | 7 (32) |
| B unnamed | 15 (257) | 14 (197) | 16 (249) | 27 (304) | 120 (500) | 16 (249) | 16 (247) | 24 (274) | 119 (492) |
| B generic | 299 883 | skipped | | | | | | | |
| D unnamed | 14 (333) | 14 (310) | 14 (310) | 26 (358) | 121 (546) | 14 (306) | 14 (306) | 25 (331) | 119 (547) |
| D generic | 12 (74) | 9 (36) | 11 (36) | 22 (82) | 121 (195) | 10 (37) | 10 (36) | 21 (51) | 119 (194) |
| E generic (2 statements) | 9 (38) | 11 (105) | 14 (114) | 22 (117) | 122 (245) | 10 (102) | 12 (106) | 21 (116) | 121 (245) |
| D_RANGES unnamed | 5 181 (5 771) | 5 147 | 5 204 | 5 227 | 5 298 | 5 123 | 5 154 | 5 150 | 5 244 (5 989) |
| C, C_UNNEST, any | 282 909 / 429 455 (custom); skipped after two | | | | | | | | |

- **Hole count, not position, sets the cost.** About 0.11 µs of execution per hole (one index probe each in a
  `= ANY` scan): +1-5 µs at 10, +12-16 µs at 100, +110 µs at 1 000, the same near or far. A hole 100 000 below the
  cursor costs the same as one just below it, because it is an index lookup by value, not a range. Buffers per probe
  8 → 11 (10 holes) → 35 (1 000 near) or 19 (1 000 far).
- **Planning dominates the custom form at many holes.** With the unnamed statement, planning a 1 000-element array
  cost 168-197 µs per probe on top of the 120 µs execution (`total_plan_time`), and the client paid ~490-550 µs p50.
  The generic `D` paid no planning: 194 µs p50 at 1 000 holes, 36 µs at up to 10.
- **`D` against `B`.** Same execution time at every hole count (within 1-5 µs); `D`'s custom form costs ~45-110 µs more
  on the client than `B`'s unnamed (a larger statement to parse and plan), and `D` generic is cheaper than any `B` that
  is safe. With no holes `D` generic costs what `A` generic costs within the noise (9-12 against 7-8 µs execution,
  36-74 against 32-35 µs p50).
- **`E`** has the same server time as `D` and costs one round trip more on the client whenever a hole is outstanding
  (about +35-80 µs p50 here, 102-117 against 36-82 µs at 1-100 holes), and it does not meet the one-snapshot constraint.
- **New rows.** With 100 new rows every variant adds the same ~110-120 µs (BASE) or ~60-70 µs (COMBO) of execution
  for reading and returning them; the hole cost adds on top unchanged. With 0 new rows and no hole row found, the
  statement returns no row, so the running-set sub-select is never evaluated (execution 1-3 µs at up to 10 holes): the
  P0.2 caveat, and why §4.10's final shape must return the set once regardless.
- **COMBO** shows the same shapes and the same per-hole cost; its new-row cost is about half BASE's (narrower rows),
  and its custom `D_RANGES` stayed under the JIT threshold (14-23 µs execution up to 100 holes, 120-136 µs at 1 000).

### 11.4 Findings

- **F1's `OR` form is a generic-plan hazard.** It is index-only as a custom plan, and a full walk of the primary key as
  a generic plan. The default `plan_cache_mode=auto` can switch to the generic plan by itself when many holes are
  outstanding, and stays there. Protecting the `OR` form needs a custom plan on every execution (`prepareThreshold=0`
  for that statement, or `plan_cache_mode=force_custom_plan` on the reader's connection), which costs ~170 µs of
  planning per probe at 1 000 holes and ~160-300 µs p50 on the client against a generic `D`.
- **The jump must not be an `OR` range.** Even the custom plan walks the index once a `BETWEEN` joins the `OR`.
- **`UNION ALL` (`D`) keeps index scans in every plan mode, for one statement and one snapshot.** Rows come back in
  `global_order` through the outer `ORDER BY` (a `Merge Append` of two ordered inputs, or a sort of at most page size
  plus the holes found), and the holes do not count against the page `LIMIT`, so a burst of late commits cannot crowd
  out the tail. It is the cheapest correct form at every hole count measured, with a generic plan.
- **Ranges need their own branch, and the JIT kept away from it.** The `LATERAL` range branch has the right plan but
  a row estimate large enough to trigger JIT on every execution (5-8 ms custom, 80-95 ms generic). `jit=off` on the
  reader's connection, or the ranges read only on the probe after a jump is first seen (a jump is resolved by its first
  candidate's retirement, so it is outstanding for one or a few probes), would avoid it; neither was measured.
- **1 000 outstanding holes cost about 0.12 ms of server time per probe.** Linear in the count. No cap is needed for
  cost at these numbers; a cap would anyway have to stop the cursor rather than drop holes, or F1's guarantee is lost.

### 11.5 Not measured

- More than 1 000 holes, a hole array long enough to change the plan of `D` (no sign of it at 1 000), and where the
  `auto` switch of `B` happens as a function of table size and hole count.
- `jit=off`, `plan_cache_mode=force_custom_plan` or a higher `jit_above_cost` for `D_RANGES`; a range branch that is
  not `LATERAL` (`UNION ALL` of one `BETWEEN` per range, statement text varying with the range count).
- The running set returned once (a `UNION ALL` row of its own) instead of per row; holes that turn into late commits in
  numbers; concurrent appends during the probe; other PostgreSQL majors (only 17.5); a dedicated machine.
- Tables larger than 2 million rows. The trap's cost grows with the rows below the cursor; `D`'s does not.

## 12. Cost of the probe's `pg_stat_activity` read as connections grow (P0.12)

Date: 2026-11-12. Findings, not decisions. The combined probe of redesign §4.10 reads the running set with
`(SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL)`. Reading
`pg_stat_activity` builds a row for every live backend in the cluster, so its cost depends on how many connections
the cluster has, not on how many of them write. Many JVMs, each with a Hikari pool, can mean 100-500 connections, and
§6 and §7.3 have every JVM probe its own tail every few tens of milliseconds while a hole is outstanding.

**Short answer.** The read costs about 0.55 µs of server execution per live backend: 10-17 µs at 10 connections,
about 60 µs at 100, about 240 µs at 400. `max_connections` itself adds only about 8 µs per 1 000 slots. Most of the
cost is building the view's rows, not the per-transaction copy of the status array: `pg_stat_get_backend_idset()`,
which does the same copy and builds no rows, costs 36-39 µs at 400. The full combined probe costs the tail read plus
that amount, and on this machine the client pays about 130 µs (10 connections) to 370 µs (400) per probe more than for
the same tail read without the sub-select. While appends run, the read gets much more expensive, and at 400
connections it hurts the appends. With 80 writers and 40 probers each probing every 10 ms, the combined probe halved
appends per second against the same probers running the tail read alone: 25-26 thousand against 49-52 thousand per
second. Append p99 went from 11 ms to 55-64 ms, and each probe took 13-15 ms. At 100 connections the effect was
3-5 %, and at 10 connections it was within the noise. In core PostgreSQL the only cheaper source of `backend_xid` is
calling `pg_stat_get_activity(NULL)` directly, without the view's joins. It returns the same data and costs about
70-110 µs less per call at 10-100 connections, but nearly the same at 400. `stats_fetch_consistency` makes no
difference.

### 12.1 Setup

**Code.** `ProbeRunningSetCostSpikeIT` in the `spike` package (`@Tag("spike")`, `-Dspike.run=true`, external-server
capable through `SpikeServer`). Raw output: `target/spike-results/p0-12-probe-cost.txt` (`CSV-COST` and `CSV-APPEND`
lines). Through the script: `scripts/run-phase0-spikes.sh --container --suite probe-cost`.

```bash
mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test=ProbeRunningSetCostSpikeIT \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true
```

Knobs: `-Dspike.probecost.levels` (`10,100,400`), `.iterations` (2 000), `.activeIterations` (1 000),
`.windowSeconds` (6), `.writerShare` (0.2), `.rows` (preload, 100 000), `.appends` (`true`), `.image`
(`postgres:17.5`).

**Servers.** PostgreSQL 17.5 containers with `fsync=off`, `shared_buffers=256MB`, `jit=on`,
`track_activity_query_size=1024`, `stats_fetch_consistency=cache` (the default) and `pg_stat_statements` preloaded.
The main container runs `max_connections=500`. Two more, at `max_connections=50` (10 connections) and `2000` (10 and
400 connections), separate the slot count from the live-backend count. Each runs on the 8-CPU arm64 Docker VM with
the client on the same VM, so §9 applies: the absolute CPU and latency figures belong to this VM.

**Connections.** At each level, *n* client connections are open, counting the observer and the measuring connection.
Each has run `SELECT 1` and is idle. `pg_stat_activity` showed *n* + 5 rows (the background processes).

**Table and statements.** An engine-shaped event table (§11's `BASE` layout) with 100 000 rows and ten deleted values
below the head as holes. Each statement carries a comment tag, so `pg_stat_statements` can attribute it. pgjdbc
defaults apply, so after five executions a statement runs as a named server-side statement.
- `SUB_ROWS`: `SELECT backend_xid FROM pg_stat_activity WHERE backend_xid IS NOT NULL`
- `SUB_AGG`: the probe's exact sub-select, `SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE
  backend_xid IS NOT NULL`
- `SRF_AGG`: the same over `pg_stat_get_activity(NULL)`, the function behind the view, without the view's joins to
  `pg_database` and `pg_authid`. It returns the same `backend_xid` from the same per-transaction status snapshot.
- `IDSET`: `SELECT count(pg_stat_get_backend_pid(i)) FROM pg_stat_get_backend_idset() i`. It does the same status
  copy and builds no rows. It has no xid, so it is a cost reference only. No `pg_stat_get_backend_*` function returns
  `backend_xid`.
- `XIP`: `pg_snapshot_xip(pg_current_snapshot())`. Rejected for correctness by redesign §4.4; measured as a cost
  reference only.
- `LOCKS`: the granted `transactionid` `ExclusiveLock`s from `pg_locks`. A cost reference only.
- `SUB_AGG` under `stats_fetch_consistency` `none` and `snapshot`.
- `TXN_CLEAR_SUB_AGG`: inside an explicit transaction, `SELECT pg_stat_clear_snapshot()` then `SUB_AGG`, then commit
  (P0.2's condition for a probe that must run in a transaction).
- `TXN_CACHED_SUB_AGG`: repeated `SUB_AGG` inside one transaction, which reuses the cached status (unsafe per P0.2).
- `PROBE_D0` / `PROBE_D10`: P0.11's `UNION ALL` form of the combined probe (tail read plus hole read by `= ANY`) with
  the running-set column, with 0 and 10 holes. `TAIL_D0` / `TAIL_D10` are the same statements without the running set.
- `SELECT1`: the round-trip floor.

A probe's cursor is always one below the highest row it read last, so every probe returns at least one row and the
sub-select is evaluated (P0.2's caveat).

**Cost cells.** Each statement ran 50 times as warm-up, then 2 000 times back to back on one connection. Recorded
per call: client p50/p99, server execution (`pg_stat_statements`, reset per cell) and server CPU (container cgroup,
minus the idle rate measured over 2 s before the level). In *active* cells, 20 % of the connections were writers
appending one event per autocommit transaction as fast as they could (2, 20 and 80 writers), and server CPU is not
separable.

**Append windows.** With the same writers running, at each level: no prober (`NONE`); one prober every 1 ms
(`TAIL_1MS`, `PROBE_1MS`); and *N* = connections / 10 probers, each every 10 ms with staggered phases, as if every JVM
had a pool of 10 and one reader (`TAIL_N_10MS`, `PROBE_N_10MS`). Every prober reads the new rows since its cursor
(page 1 000) plus the ten holes. `TAIL_*` is the control without the running set. Each arm ran a 6 s window after 1 s
of settling, in palindrome order (two windows per arm). One run took 5 min.

### 12.2 Cost per call, idle connections

Server execution µs per call (`pg_stat_statements`), with client p50 µs in brackets. `max_connections=500` unless
noted.

| Statement | 10 conn (mc 50) | 10 conn | 10 conn (mc 2 000) | 100 conn | 400 conn | 400 conn (mc 2 000) |
|---|---|---|---|---|---|---|
| `SELECT1` | 0.3 (49) | 0.4 (53) | 0.4 (49) | 0.4 (48) | 0.4 (47) | 0.4 (47) |
| `SUB_ROWS` | 9 (163) | 19 (178) | 26 (185) | 58 (210) | 244 (401) | 229 (383) |
| `SUB_AGG` (probe's form) | 10 (170) | 17 (141) | 26 (192) | 60 (224) | 238 (373) | 231 (394) |
| `SRF_AGG` | 10 (72) | 11 (74) | 15 (79) | 59 (122) | 238 (304) | 231 (296) |
| `IDSET` (no xid) | 2.5 (56) | 3.6 (56) | 8 (65) | 11 (66) | 36 (90) | 39 (95) |
| `XIP` (rejected, §4.4) | 0.7 (55) | 0.6 (53) | 0.6 (56) | 0.7 (56) | 0.6 (54) | 0.7 (57) |
| `LOCKS` | 18 (87) | 115 (179) | 259 (326) | 124 (197) | 122 (187) | 259 (325) |
| `SUB_AGG`, fetch `none` / `snapshot` | 10 / 10 | 18 / 18 | 26 / 25 | 62 / 60 | 239 / 240 | 231 / 234 |
| `TXN_CLEAR_SUB_AGG` (2 statements) | 11 (265) | 19 (271) | 27 (286) | 60 (312) | 244 (497) | 233 (487) |
| `TXN_CACHED_SUB_AGG` | 9 (167) | 9 (165) | 9 (166) | 55 (213) | 226 (385) | 217 (379) |
| `TAIL_D10` | 4 (117) | 4 (106) | 3 (72) | 4 (112) | 4 (112) | 4 (117) |
| `PROBE_D10` | 14 (249) | 15 (242) | 20 (256) | 73 (340) | 249 (482) | 232 (471) |
| `PROBE_D0` | 13 (247) | 14 (242) | 18 (252) | 63 (295) | 249 (483) | 235 (474) |

Server CPU per call, idle rate subtracted, µs (`SELECT1` 18-26 is the floor of a round trip on this VM):

| Statement | 10 conn | 100 conn | 400 conn (mc 500) | 400 conn (mc 2 000) |
|---|---|---|---|---|
| `SUB_AGG` | 140-167 | 199 | 301 | 367 |
| `SRF_AGG` | 49-54 | 99 | 225 | 272 |
| `TAIL_D10` → `PROBE_D10` | 71-88 → 216-226 | 86 → 306 | 67 → 370 | 87 → 438 |

The level-400 figures at `max_connections=500` are low by an unknown amount. That level followed the level-100
append windows, and the idle rate measured before it was 188 ms/s (autovacuum and checkpoint after the writes),
against 7-24 ms/s elsewhere. The `max_connections=2000` column has a clean idle rate (8 ms/s).

### 12.3 Cost while 20 % of the connections append

Server execution µs per call, with client p50 / p99 µs in brackets (`max_connections=500`).

| Statement | 10 conn, 2 writers | 100 conn, 20 writers | 400 conn, 80 writers |
|---|---|---|---|
| `SUB_AGG` | 13 (197 / 284) | 89 (236 / 478) | 633 (870 / 2 741) |
| `SRF_AGG` | 14 (92 / 121) | 86 (115 / 242) | 660 (709 / 2 789) |
| `XIP` | 0.7 (55 / 82) | 1.7 (21 / 52) | 10 (81 / 290) |
| `TAIL_D10` | 8 (133 / 187), 5 rows | 24 (104 / 600), 10 rows | 218 (223 / 6 414), 46 rows |
| `PROBE_D10` | 27 (290 / 449), 9 rows | 187 (437 / 1 077), 38 rows | 3 362 (3 249 / 19 562), 299 rows |

The running-set read costs 2.5-3 times as much as when the same connections are idle. The full probe at 400
connections costs far more than tail plus sub-select. Its rows per probe also grow because each probe takes longer (a
slower probe has more new rows to read), but 300 rows cost the tail read about 0.3 ms, not 3 ms.

### 12.4 Effect on appends

Appends per second, thousands, both palindrome windows (append p99 ms in brackets, probe p50 ms after the slash).

| Connections (writers) | `NONE` | `TAIL_1MS` | `PROBE_1MS` | `TAIL_N_10MS` | `PROBE_N_10MS` |
|---|---|---|---|---|---|
| 10 (2), *N* = 1 | 23.8, 21.0 (0.14, 0.25) | 24.6, 23.7 / 0.17 | 24.5, 24.6 / 0.37 | 22.5, 23.6 / 0.42 | 20.8, 23.3 / 0.66-0.72 |
| 100 (20), *N* = 10 | 86.9, 87.3 (0.66-0.72) | 85.7, 82.3 / 0.38 | 83.5, 78.3 / 0.78-0.85 | 73.0, 75.5 (0.95-0.99) / 1.9 | 71.5, 71.1 (0.97-1.01) / 2.8-2.9 |
| 400 (80), *N* = 40 | 81.9, 74.2 (7.2) | 74.6, 78.4 / 0.54-0.63 | 74.1, 70.1 / 3.1-3.6 | 49.4, 52.2 (11.3) / 3.5 | **26.2, 25.1 (55-64)** / 13-16 |

- **At 10 connections nothing measurable happens.** The arms vary by as much as `NONE`'s two windows (21.0-23.8
  thousand).
- **At 100 connections the running set costs 3-5 % of appends** against the same probers without it. The probers
  themselves cost more: ten tail readers each reading about 700 rows every 10 ms cost 13-16 %.
- **At 400 connections, 40 probing readers halve the appends.** Against the tail-only control, appends fall from 49-52
  thousand to 25-26 thousand per second, and append p99 rises from 11 ms to 55-64 ms. Append p50 *falls* (0.15-0.25
  against 0.71-0.77 ms), so appends split into a fast majority and a stalled tail. Server CPU rose from 6.0-6.1 to 7.0
  cores while doing less work, and the probers reached only 2.3-2.5 thousand of their 4 thousand probes per second.
  The cause was not attributed: no wait events were sampled. The candidates are contention between the status read and
  the writers' commits (each backend's xid is looked up while the snapshot is built) and plain CPU saturation of the
  8-CPU VM.
- **One reader probing every 1 ms** cost 0-7 % at 100 and 400 connections. At 400 that reader could not keep up
  (243-265 probes per second at 3.1-3.6 ms each).

### 12.5 Findings

- **Cost scales with live backends, about 0.55 µs of execution per backend per read, and with nothing the engine
  controls.** It is the same idle or with xids in the set, near or far, with 0 or 10 holes. Slots that are not in use
  add about 8 µs per 1 000. A cluster of 400 connections pays about 0.24 ms of execution and, on this VM, 0.3-0.37 ms
  of server CPU per probe.
- **Cluster-wide cost is readers × connections.** Both grow with the number of JVMs. With *J* JVMs, each with a pool
  of *p* connections and one tail reader per aggregate type *T*, probing every *i* ms while holes are outstanding,
  the cluster reads *J·T·1000/i* times per second a status table of *J·p* rows. That is quadratic in *J*. At *J* = 40,
  *p* = 10, *T* = 1 and *i* = 10, that is 4 000 reads per second of 400 rows. Here those reads, together with the tail
  reads, took the VM from 52 to 25 thousand appends per second. Under write load holes are nearly always outstanding,
  so §4.10's "plain tail read when no candidate is outstanding" does not remove the cost when it matters.
- **Calling the function directly is the cheaper form, and only at low connection counts.** `pg_stat_get_activity(NULL)`
  instead of the view gives the same `backend_xid` from the same per-transaction snapshot. That keeps P0.2's
  autocommit / `pg_stat_clear_snapshot()` conditions, and it needs the same `pg_read_all_stats` visibility. It saves
  70-110 µs of client latency and about 90 µs of server CPU per call at 10-100 connections, outside recorded
  execution (unattributed). At 400 the execution cost dominates and both cost about 230-240 µs. No in-core function
  returns `backend_xid` without building the whole activity row; `IDSET` shows that the copy alone would be about 6x
  cheaper.
- **`stats_fetch_consistency` does not matter.** It governs cumulative statistics, not backend status, and all three
  settings measured the same. `pg_stat_clear_snapshot()` inside a transaction costs one more statement (about +90-130
  µs p50 here) and the same execution. The cached second read inside a transaction is cheaper only at 10 connections
  (9 against 17-26 µs), and it is unsafe (P0.2).
- **`pg_snapshot_xip` costs about 1 µs whatever the connection count** (10 µs under the heaviest load), against
  0.24-0.63 ms. Redesign §4.4 still rejects it for correctness; the number shows what that correctness costs.
  `pg_locks` scales with `max_connections` (18 / 115 / 259 µs at 50 / 500 / 2 000) and is no alternative.

### 12.6 Not measured

- Wait events during the 400-connection windows, so the cause of the append collapse is open: lock contention
  against commits, or CPU saturation of the VM.
- A dedicated database host, `fsync=on`, other majors (only 17.5), and `track_activity_query_size` other than 1 024.
- Where the view's extra ~90 µs outside execution goes. `track_planning` was off and no `EXPLAIN` was taken.
- Probe intervals other than 1 and 10 ms, adaptive back-off, and fewer readers than JVMs (one reader per cluster, or
  a reader that shares its running set).
- Connections that are active but do not write (read traffic), and more than 400 connections.
