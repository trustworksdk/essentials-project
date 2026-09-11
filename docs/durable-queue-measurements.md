# Durable Queue Measurements — current implementation vs. shard-owned engine

Consolidated results. How the engine works: [durable-queue-shard-owned.md](./durable-queue-shard-owned.md). This document is the numbers themselves, so they can be quoted without reading the design.

**A figure is only comparable with the others in its own table.** Arms *within* one table are interleaved in a single run on one machine, which is what makes their comparison sound; arms from different tables are not comparable, and neither are these figures against any other machine. See [Environment](#environment).

Tables carrying a **Re-measured** date were re-run against the engine as it now stands. Tables without one predate that run — where it matters, the section says what it predates.

The history of how these numbers got here — the reworks, the retractions, and the measurement bugs found along the way — is in [`archive/durable-queue-measurement-history.md`](./archive/durable-queue-measurement-history.md). This document is the current state.

---

## 1. Headline comparison

Re-measured **2026-09-11** against the engine as it now stands, including the ordered-lane rework:

| Metric | Current implementation | Shard-owned engine | Change |
|---|---|---|---|
| WAL bytes per message | 1 904 | **538** | **−72%** |
| WAL bytes per message, all obligations restored | 1 905 | **667** | **−65%** |
| Dead tuples created per message | 1.98 | **1.00** | −49% |
| Row updates per message | 1.00 | **0** | claim write eliminated |
| Commits per message | 1.07 | **0.15** | −86% |
| Enqueue-to-handler p50 | 20.7 ms | **0.54 ms** | **38×** |
| Enqueue-to-handler p99 | 27.1 ms | **1.02 ms** | **27×** |

Conditions: 200-byte payloads, 20 000 messages per repetition, three interleaved repetitions per arm, PostgreSQL 17.5 pinned to CPUs 4–7, load generator to CPUs 0–3. Latency is the Tier 2 response figure from a separate 2 000-message run at 500/s; the baseline latency is its Phase 0 measurement at the shipped 20 ms poll.

**The row-update column is the design in one number.** The current implementation writes one row update per message — `is_being_delivered = true`. The shard-owned engine writes none, because a lease already establishes who owns the message. Not reduced: absent.

Two of these rows have moved since the first publication of this document, and one was wrong:

- **Obligations-restored WAL was 615 bytes (−68%) and is now 667 (−65%).** The batched figure is unchanged, so this is the per-message-enqueue arm costing more, not the design regressing.
- **Latency p50 was 0.44 ms and is now 0.54 ms**, p99 0.97 → 1.02 ms. That regression was introduced by the pump rework and is structural: handlers no longer run on the thread that read them. It was documented in the history and had never been folded back into this table.
- **The commits-per-message row used to read "~3.06 → 0.05 (−98%)", and those were not the two implementations.** They are the shard-owned engine with idle polling and with NOTIFY — the before/after pair from §3.2, put in a column headed "Current implementation". Measured properly, baseline against shard-owned, it is 1.07 against 0.15.

---

## 2. Current implementation — baseline

### 2.1 Latency at the shipped default (20 ms poll, centralized fetcher)

| | Measured | Estimate the design had assumed |
|---|---|---|
| p50 | **20.7 ms** (IQR 1.2 ms) | ~10 ms |
| p99 | **27.1 ms** (IQR 1.0 ms) | ~30–50 ms |

The p50 estimate was low by a factor of two. Measured at an offered rate below capacity — at or above capacity, Little's Law fixes latency at `queueDepth / throughput` regardless of implementation, and the figure says nothing about the design.

### 2.2 Throughput is governed by poll cadence, not by PostgreSQL

| Fetcher | Parallel consumers | Measured | `consumers × pollsPerSecond` |
|---|---|---|---|
| Centralized, 20 ms poll | 3 | 150/s | 3 × 50 |
| Centralized, 20 ms poll | 20 | 993/s | 20 × 50 |
| Traditional, ~100 ms poll | 3 | 30/s | 3 × 10 |
| Traditional, ~100 ms poll | 20 | 200/s | 20 × 10 |

Every point lands on the product, with an interquartile range under 1%. **The database was never the limit in any of these runs**, which means a single "the queue does N messages a second" figure is meaningless without also stating consumer count and poll interval.

### 2.3 Capacity sweep — where PostgreSQL does become the limit

Twenty consumers, eight producers, backpressure engaged throughout:

| Poll interval | Ceiling | Measured | Of ceiling |
|---|---|---|---|
| 20 ms | 1 000/s | 1 000/s | 100% |
| 10 ms | 2 000/s | 1 995/s | 100% |
| 5 ms | 4 000/s | 3 994/s | 100% |
| **2 ms** | 10 000/s | **9 881/s** | 99% |
| 1 ms | 20 000/s | 9 203/s | 46% |

The knee is around **9 900 messages a second**. Polling faster than that makes throughput *worse* — 9 203 against 9 881 — which is its own argument for push-based wake-up.

⚠️ **Numbers taken at the knee do not reproduce in this lab.** See [Environment](#environment).

### 2.4 Workload profiles (20 ms poll, 20 consumers)

| Profile | Throughput | vs unordered | Dead tuples/msg | WAL bytes/msg |
|---|---|---|---|---|
| Unordered | 997/s | 100% | 1.91 | 1 971 |
| Ordered, 10 keys | 498/s | 50% | 2.09 | 2 050 |
| Ordered, 1 000 keys | 1 000/s | 100% | 1.87 | 2 257 |
| 10% injected failures | 900/s | 90% | 2.29 | 2 303 |
| 1 queue (control) | 100.0/s | — | 1.95 | 1 748 |
| 30 queues, 29 idle | 99.7/s | 99.7% of control | 1.99 | 2 193 |
| Soak, 60 s | 1 000/s | 100% | 1.99 | 2 023 |

Two findings worth carrying forward. **Twenty-nine idle queues cost 0.3%** against a matched control, so idle polling is already close to free at that scale. And ordering's cost at 1 000 keys is invisible here *because both arms sit on the poll ceiling*; measured at saturation it is 31% of unordered throughput, +17% WAL and +48% dead tuples.

---

## 3. Shard-owned engine

### 3.1 Cost decomposition — how much of the advantage is design

Each obligation the current implementation carries, added back one at a time. Re-measured
**2026-09-11**:

| Obligation added | WAL bytes/msg | IQR | vs previous | vs baseline | Commits/msg | Elapsed |
|---|---|---|---|---|---|---|
| bytes, batched | 540 | 0.5% | — | −72% | 0.18 | 595 ms |
| + JSON payload | 555 | 0.3% | +3% | −71% | 0.18 | 590 ms |
| + per-message enqueue | 667 | 0.0% | +20% | −65% | 6.03 | 5 028 ms |
| + per-message ack | 667 | 2.2% | +0% | −65% | 7.01 | 5 012 ms |

Baseline in the same run: 1 905 WAL bytes/msg.

- **JSON costs almost nothing in write volume** — a 200-byte payload becomes 214 on the wire, WAL rises 3%. The `bytea`-over-`jsonb` choice is real but small.
- **Batching is worth about 7 percentage points of the 72.** Removing it raised commits from 0.18 to 6.03 per message and made the run roughly 8× slower, but WAL rose only 20%. Batching buys throughput, not write volume.
- **Per-message acknowledgement costs no WAL at all.** Range delete versus individual deletes changes commit count and round trips, not tuple deletions.
- **The remaining −65% is the design**: no claim write, a narrower row, fewer indexes. `n_tup_upd` is **zero in every arm**.

**Commits/msg is a database-wide counter** (`pg_stat_database.xact_commit` divided by messages), so the first arm of a run carries the container's own start-up commits and reads high. Compare arms within a run, and prefer the later arms; the WAL column does not have this problem.

### 3.2 Wake-up tiers

Re-measured **2026-09-11**, 2 000 messages at 500/s:

| Configuration | p50 | p90 | p99 | max |
|---|---|---|---|---|
| Tier 2 — local hand-off, response | **0.54 ms** | 0.66 ms | **1.02 ms** | 4.98 ms |
| Tier 2 — local hand-off, service | 0.35 ms | 0.51 ms | 0.88 ms | 4.96 ms |
| Tier 1 — NOTIFY, read back, response | 1.76 ms | 2.12 ms | 2.63 ms | 11.90 ms |
| Tier 1 — NOTIFY, read back, service | 1.63 ms | 1.93 ms | 2.48 ms | 11.59 ms |
| Current implementation, 20 ms poll | 20.7 ms | — | 27.1 ms | — |

Tier 2's p50 was 0.44 ms before the pump rework; handlers no longer run on the thread that read them,
and 0.1 ms is what that costs. Tier 1's p99 was 4.82 ms and is now 2.63 — that improvement came from
teaching `parkDeadlineMillis` to count pending acknowledgements, and it is the same fix that retired
an earlier claim that the sweep back-off cost 2 ms at p99.

Response time is measured from the intended schedule slot, service time from when the producer actually sent. Both are reported because the pair is what locates a problem: during development, response time alone said Tier 2 had a 266 ms tail and service time alone said it was excellent — the truth was a warmup artefact in the harness, in neither.

**Tier 1's effect on transaction count.** Both columns are the **shard-owned engine**, with idle
polling and with NOTIFY — this is not a comparison against the current implementation, and §1 used to
present it as one:

| Configuration | Shard-owned, Tier 1 off | Shard-owned, Tier 1 on |
|---|---|---|
| bytes, batched | 3.06 | **0.05** |
| + per-message enqueue | 18.86 | **3.10** |

Idle polling is eliminated: 907 transactions to move 20 000 messages, essentially only the ones doing real work.

**Tier 3 (WAL streaming) is not built, and should not be.** Its gate requires clearly beating Tiers 1 and 2 by enough to justify a replication slot's operational weight. Tier 2 measures 0.44 ms at the median; a WAL stream will not clear that by a margin that pays for `wal_level = logical`, replication privileges, and a slot that pins WAL on disk when a consumer dies.

### 3.3 Sequence-gap behaviour

The design's load-bearing assumption is that a reader can follow a sequence with a cursor, write
nothing when it consumes, and never lose a message. The two lanes now answer the "is this gap an
uncommitted transaction?" question differently, so their numbers are not comparable.

**Unordered lane — gaps are chased.** A value the cursor stepped over is re-queried until it appears
or `holeExpiry` writes it off. Measured across 18 runs:

| Arm | Hole rate | Hole resolution p99 | Messages lost |
|---|---|---|---|
| Autocommit enqueue | 2.4–5.1 per 1 000 (~0.3%) | 4.8–5.2 ms | **0** |
| Enqueue holding a 5 ms transaction | 8.8–22.2 per 1 000 (~1.5%) | 5.4–5.7 ms | **0** |

**Hole resolution latency is a tunable, not a property of the database** — it tracked the configured
chase delay almost exactly in both arms, meaning holes resolve as fast as the reader looks for them.

**Ordered lane — gaps are not chased, because the cursor does not step over them.** It advances only
past values no running transaction could still commit, so there is no hole rate to report and no
expiry to tune. The equivalent figure is how far the cursor trails, in §3.9. This is why the two lanes
also differ in sequence scoping: the unordered lane needs one sequence per `(queue, shard)` for
density, and the ordered lane needs one per queue.

---

## 3.4 Multi-process behaviour

Engine instances as separate operating-system processes against one PostgreSQL, with deliveries recorded to a table — because a process killed with `SIGKILL` flushes nothing, and the table's serial id supplies an observation order across processes that no participant could reconstruct alone.

| Property | Result |
|---|---|
| Per-key ordering with owners in separate processes | **0 violations** across 600 messages over 40 keys, both processes delivering (315 / 285) |
| `SIGKILL` of one process | Survivor takes the abandoned shards; **no messages lost** |
| Work after the kill | Handled entirely by the survivor (152 / 433 after, from 152 / 148 before) |
| `SIGSTOP` then `SIGCONT` — a node alive but frozen | Its lease lapses, the survivor takes over, and the resumed node's stale-fence acknowledgements are refused: **no messages lost**. It then rejoins and takes its fair share again |

**This is the guarantee the current implementation explicitly does not make.** Its documentation states that `OrderedMessage` ordering across cluster nodes is not guaranteed, only within a single node. Here a key hashes to one shard and one process holds that shard's lease, so ordering holds across processes — and it is now a result rather than an argument.

Ordering was verified in SQL with a window function over the observation table, so the comparison uses PostgreSQL's own insert ordering rather than anything the test reconstructs.

### 3.5 Sustained load — 30 minutes, both engines, sampled every 30 seconds

Re-measured **2026-09-11**: 30 minutes per arm at 300 messages a second, 60 windows each, 540 000
messages per arm. The rate is deliberately well below either engine's capacity so latency stays a
property of the design rather than of queue depth. Both engines handled exactly 9 000 messages in
every 30-second window — neither fell behind at any point, in any window.

**Read the quarters, not the endpoints.** First-to-last is what the previous six-minute run reported,
and on this longer run it is actively misleading: the baseline's first window is its fastest, so
endpoint arithmetic turns a cold cache into "+14% drift".

| p50, median per quarter | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| Baseline | 13 703 µs | 14 159 µs | 13 439 µs | 13 455 µs |
| Shard-owned | 1 137 µs | 1 017 µs | 1 015 µs | 1 029 µs |

| p99, median per quarter | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| Baseline | 28 095 µs | 27 999 µs | 25 711 µs | 26 559 µs |
| Shard-owned | 2 841 µs | 3 283 µs | 3 515 µs | 3 469 µs |

| | Baseline: first → last | Shard-owned: first → last |
|---|---|---|
| Table size | 4 168 → 13 840 KB | 2 584 → 8 032 KB |
| **Index size** | 680 → **6 440 KB** | 320 → **1 384 KB** |
| Dead tuples outstanding | sawtooth 5 600 – 24 400 | sawtooth 2 700 – 11 900 |
| Autovacuum runs | 30 | 30 |

**The baseline does not drift, and thirty minutes says so more firmly than six did.** Its p50 is flat
across the quarters and its p99 *falls* 5%. The design document's argument — that two dead tuples per
message would surface as p99 degradation once autovacuum had work to do — is now unconfirmed at five
times the previous scale, 1.1 million dead tuples, and thirty autovacuum cycles. It should be quoted
as a structural cost difference, which is measured, and not as a predicted degradation, which has now
twice failed to appear.

**The shard-owned engine's p99 settles about 20% above its cold value, and this is new.** It rises
2 841 → 3 283 → 3 515 µs across the first three quarters and then stops, flat into Q4. Six minutes
reported +5% because six minutes is inside the settling period, not past it. Two things say it is a
settle rather than accumulation: it plateaus while the run continues, and the storage it would be
blamed on plateaus with it — the table reaches 7 360 KB by the third window and ends at 8 032. In
absolute terms it is 0.7 ms on a 2.7 ms figure, against a baseline p99 of 26 ms.

- **Indexes are 4.7× smaller** — 1 384 KB against 6 440 KB. That is the lane split and the narrower
  index set, and it is a durable property rather than a measurement artefact.
- **Dead tuples outstanding run at roughly half**, tracking the 1.00-against-1.98 per-message figure.
  Both arms show a clean two-window sawtooth, which is autovacuum cycling, not growth.
- **Neither engine falls behind**, so the latency advantage in §3.2 is not a cold-start effect.

⚠️ **Thirty minutes at 300 a second is 540 000 messages, and that is still not a pre-release soak.**
Bloat and vacuum debt are effects of hours and of higher rates. What changed against the six-minute
run is that the baseline's negative result got stronger and the shard-owned engine's p99 settle became
visible; what did not change is that neither run reaches the timescale where vacuum debt compounds.
Both arms recorded one isolated ~42 ms window (baseline window 40 at 41 951 µs, shard-owned window 19
at 42 623 µs) — single windows, not a trend, and consistent with container noise on a shared machine.
Only the shard-owned one exceeds twice its arm's median, because the baseline's median p99 is already
28 ms.

## 3.6 Throughput, threads and connections, side by side

`EngineResourceComparisonIT` (perf lab, `-Dbenchmark.run=true`), 20 000 messages of 200 bytes, three
interleaved repetitions per arm, all four arms in one run:

| Arm | msg/s | IQR | slowest..fastest run | threads | held conns |
|---|---|---|---|---|---|
| baseline, 20 ms poll, 20 consumers | 996 | 0.4% | 990..998 | 51 | 18 |
| baseline, 20 ms poll, **40** consumers | 1 980 | 0.3% | 1975..1986 | 70 | **31** |
| baseline, **5 ms** poll, 20 consumers | 3 915 | 0.1% | 3915..3924 | 36 | 17 |
| shard-owned | ⚠️ 3 767 | **78.2%** | **3745..9634** | 36 | **7** |

### ⚠️ The throughput column is not a result

**The shard-owned figure must not be quoted.** Three identical repetitions produced 3 745 and 9 634 —
one run 2.6× another. That is this lab, not the engine, and it is the same instability §4 documents;
the test prints a warning above 25% so the number cannot be lifted out of the table innocently.

**And the baseline's throughput is a configuration choice, not a property.** All three baseline arms
land on `parallelConsumers × pollsPerSecond` to within 0.5% — 996 against 1 000, 1 980 against 2 000,
3 915 against 4 000 — at an interquartile range of 0.4% or better. They are stable *because they are
not measuring the database at all*; they are measuring a timer. Choosing a 20 ms poll makes the
baseline look four times slower than choosing 5 ms, and neither number describes the engine. A single
throughput ratio between these two implementations is therefore meaningless, which is why four arms
are needed to say anything at all.

### What the arms do establish

Both of the baseline's routes to more throughput cost something, and the arms price them:

- **More consumers.** 20 → 40 doubled throughput and cost **+13 held connections** (18 → 31) and
  +19 threads. Every consumer needs a connection to acknowledge on, so its throughput and its pool
  use cannot be tuned independently.
- **A faster poll.** 20 ms → 5 ms quadrupled throughput at no connection cost (18 → 17) — but at four
  times the query rate against a database that was never the bottleneck.

The shard-owned engine pays neither: **7 held connections**, being `pumpThreads + 1` for the whole
process, unchanged by shard count, queue count, consumer count or load. That is a property of the
design rather than of the operating point, which is why the test asserts the *mechanism* — that
doubling the baseline's consumers really does cost connections — rather than just the gap.

---

## 3.7 What shardCount buys, and what the ordered lane does instead

`shardCount` applies to the **unordered lane only**. The ordered lane routes on a fixed per-queue
space and has no such knob — see `durable-queue-ordered-routing-design.md`.

The sweep below was run on the ordered lane before that change, when both lanes shared the number. It
is kept because the *shape* is what informs the default routing space, and it is the only sweep of its
kind: 500 keys, 2 ms handler, `keyConcurrency` 8, three interleaved repetitions per arm.

| shards | msg/s | IQR | % of peak | msg/s per shard | ceiling (`shards x keyConcurrency`) |
|---|---|---|---|---|---|
| 1 | 1 155 | 74.0% | 48% | 1 155 | 8 |
| 2 | 1 849 | 26.4% | 77% | 925 | 16 |
| **4** | 2 152 | 8.0% | **89%** | 538 | 32 |
| **8** | 2 281 | 2.9% | **95%** | 285 | 64 |
| 16 | 2 408 | 2.7% | 100% | 151 | 128 |

**Absolute msg/s is not a result** — this lab moves by an order of magnitude between sessions, and the
arms are interleaved precisely so the shape survives that. What the shape says: the knee is at 4, 8
buys 95% of what 16 does, and return per shard collapses from 1 155 to 151. Past the point where
`shards x keyConcurrency` exceeds the work available, more units buy nothing.

**One shard is the least predictable arm**, at 74% spread against 3% at eight, because everything
serialises through a single owner. If ordered throughput matters at all, one is the wrong answer
before its median is considered.

**The knee moves with the workload.** A handler that waits 200 ms, or a queue with ten keys rather
than five hundred, has a different answer.

This is what makes 64 a defensible default for the ordered routing space: far past the point of
return, so a fixed space costs nothing in throughput, while being high enough that the instance
ceiling it implies is not one a deployment reaches. Where it *would* be reached, the space is set per
queue at creation.

### 3.8 What a routing space costs when idle

The concern with a large space is that per-unit cost multiplies. It does not, because reads are
batched per queue and an instance-owned unit carries no lease to renew. One queue, idle:

| units | acquire (one-off) | idle queries/s | lease writes/s |
|---|---|---|---|
| 64 | 144 ms | 1.33 | 0.00 |
| 256 | 142 ms | 1.27 | 0.00 |
| 1024 | 221 ms | 1.20 | 0.00 |

Flat. Acquisition is the only remaining per-unit cost and is paid once at start-up. What still grows
is per-unit *state* — a lease row and an owner object each — which is why the default stays modest for
processes running many queues.

Across queues, 5 queues holding 170 units, idle: **7.4 queries/s total, 0.0 of them lease writes**,
0.044 per owned unit. The same configuration cost 23.8 queries/s before the per-unit lease renewal was
removed, 17.0 of it lease writes — 71% of an idle engine spent renewing rows to say nothing had
changed.

### 3.9 Ordered-lane discovery: watermark lag

The ordered lane advances its cursor only past values no running transaction could still commit, so
its discovery latency is bounded by the transaction horizon rather than by a timeout. Eight producers
running the outbox shape, sampler running the real algorithm:

| workload | stalled samples | p50 | p99 | re-read window |
|---|---|---|---|---|
| 5 ms transactions | 0.1% | 11.0 ms | 12.7 ms | 14–16 rows |
| 50 ms transactions | 0.3% | 38.9 ms | 64.1 ms | 8–14 rows |
| + unrelated 13 s **read-only** transaction | 0.1% | 12.2 ms | 13.5 ms | 16–20 rows |
| + unrelated 13 s **writing** transaction | **98.1%** | 6 105 ms | 12 619 ms | — |

The cursor trails by about one write-transaction duration. Only *writing* transactions hold it back —
a long read-only transaction is indistinguishable from none, because only a writer is assigned an xid.
The last row is the positive control; without it the first three are an uncalibrated negative.

### 3.10 Read plan shapes for the batched cursor read

Reading many units in one statement is only cheap in one of the three obvious shapes:

| read shape | backlog | near-idle | sparse wake-up |
|---|---|---|---|
| single composite row-value cursor | 0.62 ms | **10.16 ms** | — |
| per-unit cursor list, plain join | **68.1 ms** | 0.61 ms | **234.4 ms** |
| **per-unit cursor list via `LATERAL` + inner `LIMIT`** | **0.94 ms** | **0.74 ms** | **0.19 ms** |

The middle row is plan-unstable rather than slow: the planner merge-joins it and scans the whole
discovery index. A per-row `LIMIT` inside a `LATERAL` cannot be satisfied by a merge join, which makes
the nested loop the only legal plan instead of a preference.

Measure the **near-idle** state, not only backlog. The first shape looks excellent with work to do and
is sixteen times worse with none — which is the state a queue spends almost all its time in.

## 4. Environment

| | |
|---|---|
| PostgreSQL | 17.5, `shared_buffers=512MB`, `synchronous_commit=on`, pinned to CPUs 4–7 |
| Load generator | JVM pinned to CPUs 0–3 via `taskset` |
| Container | Docker-in-Docker; the database is a *child* of the devcontainer and shares its cgroup |
| cgroup budget | **8 CPUs of quota, while `nproc` and the JVM both report 14** |
| Storage | overlayfs |

### ⚠️ What this environment can and cannot measure

**Reliable:** per-message cost metrics. Across 45 unordered runs spanning every operating point, WAL bytes per message varied 33% and dead tuples per message 27% — and *within* a fixed configuration, well under 2%.

**Not reliable:** throughput at saturation. The same 45 runs saw throughput vary **861%**, and at the capacity knee the same configuration produced interquartile ranges of 47–239% depending on nothing at all. The cgroup grants 8 CPUs while everything sizes its pools for 14, so any run that saturates CPU is throttled. Five interventions were tried — CPU pinning of both sides, JVM processor count matched to the quota, single-run isolation, `ANALYZE` placement, and a profile-independent warmup. None made it reliable.

**Consequence:** every gate in the design is written against per-message cost, measured at the poll-bound operating point, with throughput reported but never gating. A throughput gate needs dedicated hardware, or at minimum a cgroup quota matching the advertised processor count with no co-tenants.

---

## 4.5 Choosing between the two implementations

Not a ranking. The two differ in what they are *good at*, and the honest split is narrower than a
headline comparison suggests.

**Reach for the shard-owned engine when**

| Because | Measured |
|---|---|
| Latency matters | 0.44 ms p50 / 0.97 ms p99, against 20.7 / 27.1 at the shipped 20 ms poll. Push, not poll — there is no interval to tune |
| Volume makes per-message cost matter | −65 to −72% WAL bytes; dead tuples 1.98 → 1.00; row updates 1.00 → **0**, because ownership removes the claim write. Index 5.2× smaller after a 6-minute soak |
| The process holds many queues | Connections are `pumpThreads + 1` **per process** — 5 at 300 queues — rather than growing with consumers |
| Per-key ordering must hold across processes | Verified across ~19 lease lifetimes with a third node joining mid-run. The current implementation's own docs say ordering does not hold across instances |
| An outbox needs atomicity *and* working retries | `enqueue(Connection, …)` joins the caller's transaction while attempt counting stays outside its rollback scope. `TransactionalMode.FullyTransactional` gives one or the other |

**Stay on the current implementation when** — and this is the longer list today

| Because |
|---|
| **It is shipped, published and in production.** The shard-owned engine has none of those |
| You use Inbox, Outbox, `EventProcessor` or `DurableLocalCommandBus` — all consume `DurableQueues`, which this engine deliberately does not implement |
| You use the admin API or UI to inspect, retry or resurrect messages. There is no admin surface here |
| You need by-id operations from any caller (`getQueuedMessage`, `retryMessage`, `markAsDeadLetterMessage`). Those assume a claim flag on every message — the largest cost this design removes |
| You want to read payloads in `psql`. This engine stores `bytea`; the current one stores JSON |
| You need MongoDB as well as PostgreSQL |
| You cannot accept an immutable `shardCount`. Changing it re-routes every key, so sizing it wrong is a drain-and-switch rather than a config change |
| You rely on `DurableQueues` being a stable API. This SPI is explicitly still moving |

**What is *not* a differentiator: raw throughput.** At a 5 ms poll the current implementation moved
3 915 msg/s at 0.1% spread; the shard-owned engine's figure in the same run is unquotable. What the
comparison actually shows is that it reached that class of throughput on **7 connections instead of
17–31**, with no poll interval — the cost of the throughput differs far more than the throughput does.

**They are not mutually exclusive.** Both can run against the same database on different tables, so
adoption can be per queue: move the latency-sensitive or high-volume ones and leave the rest.

---

## 5. What has not been measured

Stated so that absence is not mistaken for a passing result.

- **Network partitions and clock skew.** `ShardOwnedMultiProcessIT` runs engine instances as separate operating-system processes and kills one with `SIGKILL`, so real process death is covered. A node that is *alive but partitioned* — the case the fencing design exists for — is not: simulating it needs network control the current harness does not have.
- **Containers as separate hosts.** The node processes share a machine and a kernel clock. Genuinely separate hosts, with independent clocks and a real network between them, are untested.
- **Sustained soak beyond half an hour.** The longest run is the thirty minutes in §3.5 — 540 000 messages per arm, thirty autovacuum cycles. Vacuum behaviour, index bloat and p99 drift over *hours* remain unmeasured, and the baseline's dead-tuple cost is precisely the kind of thing that would only show as drift at that scale. §3.5 looked for it at six minutes and again at thirty and did not find it, which is not the same as it not being there. Nor has any soak run at a rate near either engine's capacity: 300/s keeps the latency signal clean and accumulates debt slowly, and the opposite trade has not been measured.
- **Realistic payload distribution.** Every measurement uses a uniform 200-byte payload. Large payloads, TOAST behaviour and mixed sizes are untested.
- **Failure injection beyond handler exceptions.** Database restarts, connection loss mid-batch, and disk pressure are untested.
- **Throughput on hardware that can measure it.** See above.
- **Idle cost of a large routing space across many queues.** §3.8 varies the space on one queue and the queue count at one space; the product of the two — hundreds of queues at 1 024 units each — is not measured, and per-unit state (a lease row and an owner object each) is what would grow.
- **Growing an existing queue's routing space.** Not built; see `durable-queue-ordered-routing-design.md` §8.

## 6. Where the code lives

The engine and its semantics tests are `components/postgresql-queue-shard-owned` — a reactor module that is **not published** (`maven.deploy.skip=true`), because the project's rule is that central APIs break only on a major version and this SPI is still moving. The benchmarks comparing it against the existing implementation stay in `examples/essentials-performance-lab`, which is where the harness and the other engine are.

## 7. Reproducing

```bash
# Cost comparison and decomposition (benchmark-gated, ~10 minutes)
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='ShardOwnedVsBaselineCostIT,ShardOwnedCostDecompositionIT' \
  -Dbenchmark.run=true -Dlab.pg.cpuset=4-7 -Dlab.pg.shared-buffers=512MB

# Latency comparison
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='ShardOwnedLatencyIT' -Dbenchmark.run=true -Dlab.pg.cpuset=4-7

# Current-implementation baseline profiles and capacity sweep
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='DurableQueueBaselineProfilesIT' -Dbenchmark.run=true -Dlab.pg.cpuset=4-7

# Ordered routing space: idle cost per unit count (§3.8)
./mvnw verify -pl components/postgresql-queue-shard-owned \
  -Dit.test='ShardOwnedRoutingSpaceCostIT' -Dbenchmark.run=true

# Idle cost across queues, incl. lease writes (§3.8), and the ordered-lane gates (§3.9, §3.10)
./mvnw verify -pl components/postgresql-queue-shard-owned \
  -Dit.test='ShardOwnedMultiQueueCostIT,ShardOwnedOrderedIdleCostIT,ShardOwnedBatchedReadIT'

# Sustained-load soak (§3.5). Both arms run the full duration, so wall clock is roughly
# 2 x soak.minutes plus container start-up: 30 here means about 65 minutes.
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='ShardOwnedSoakIT' -Dbenchmark.run=true \
  -Dsoak.minutes=30 -Dsoak.rate=300 -Dlab.pg.cpuset=4-7
```

Run these without `-am`. With it, failsafe also runs on the aggregator and fails the build with "No tests matching pattern" before any arm executes; install the engine modules separately if the working tree has changes the lab must see.

Each run writes machine-readable results under `examples/essentials-performance-lab/target/perf-lab-baseline/`, including the full environment, so a later run can be diffed against an earlier one. Those files are build output and are not committed; copy them somewhere durable if a result matters.
