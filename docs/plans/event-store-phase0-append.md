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
  (63-67 µs). Why raw pgjdbc costs the server more than JDBI for the same SQL was not isolated. Across all four arms
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
