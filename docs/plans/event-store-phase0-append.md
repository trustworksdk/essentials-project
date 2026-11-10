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
  attempts likewise). Not investigated; a plausible cause is that B's attempts that lose spend longer — the zero-row
  path still runs the `head` scan and the losing insert waits on the winner — but this was not measured. Uncontended
  appends (§2.1) show no such cost, so it is a property of the hot-aggregate case, which the opt-in lock exists for.

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
- The cause of B's lower throughput under contention.
- The A/B from worktrees outside `/workspace` that §17 asks for; both arms ran in the same JVM and tree, alternating,
  which is the comparison that matters here since no engine code differs.
- Concurrent uncontended appenders (§17's steady and concurrent-writers shapes) with B.
