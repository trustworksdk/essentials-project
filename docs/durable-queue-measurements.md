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

**Not every row in this table is equally safe to repeat.** The cost rows — WAL, dead tuples, row
updates, commits — reproduce across two hosts and the devcontainer to within 0.1%, and travel. The
**p50** latency row reproduces in shape (0.88–0.91 ms on a workstation against 0.54 ms here) and
travels with its conditions attached.

**The p99 row is the one to be careful with**, and specifically *this* p99 — the enqueue-to-handler
figure from the burst-latency suite in §3.2. Re-measured on a workstation, the same suite's Tier 1
response p99 came back 6.69 ms in one run and 40.45 ms in another an hour later. That is not true of
every tail figure in this document: the sustained-load p99s in §3.5 agreed between those same two runs
to 0.3% (baseline) and 16% (shard-owned). §4.5 names the four figures that should not be quoted and
puts everything else on a confidence tier.

**The WAL percentages belong to the 200-byte payload and do not travel.** The saving is a fixed
~1 200–1 350 bytes per message — a narrower row and no claim write — against a payload cost both
engines pay identically, so the *ratio* collapses as messages grow: −71% at 200 bytes, −39% at 1 800,
−11% at 8 000, −1.6% at 64 000. §3.4.3 has the sweep. Quote the percentage with the size, or quote
the constant.

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
- **The remaining −65% is the design**: no claim write, a narrower row, fewer indexes. `n_tup_upd` is **zero in every arm**. As a *percentage* it is specific to this table's 200-byte payload; the saving behind it is a fixed number of bytes per message, and §3.4.3 measures it across sizes.

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

**⚠️ The p50s travel; the p99s do not.** Two workstation runs an hour apart on the same commit
(`perf-results/20260914-090919`, `20260914-103550`) put Tier 2's p50 at 0.91 and 0.88 ms and Tier 1's at
2.72 and 2.69 ms — stable, and about 1.6× the figures above, which is plausibly the Docker Desktop VM
boundary that every enqueue commit crosses on that host. Their p99s did not behave: Tier 1's response
p99 was 6.69 ms in one run and **40.45 ms** in the other, with service-time p99 moving 4.71 → 14.92 ms
alongside it, so it is not the producer-stall artefact described above. Quote the p50s; treat every
tail figure in this section as environment-specific until it reproduces.

**Tier 1's effect on transaction count.** Both columns are the **shard-owned engine**, with idle
polling and with NOTIFY — this is not a comparison against the current implementation, and §1 used to
present it as one:

| Configuration | Shard-owned, Tier 1 off | Shard-owned, Tier 1 on |
|---|---|---|
| bytes, batched | 3.06 | **0.05** |
| + per-message enqueue | 18.86 | **3.10** |

Idle polling is eliminated: 907 transactions to move 20 000 messages, essentially only the ones doing real work.

**Tier 3 (WAL streaming) is not built, and should not be.** The comparison that decides it is against **Tier 1**, not Tier 2: Tier 2 is a hand-off inside one JVM that never reaches the database, so a WAL stream cannot replace it at any latency. Tier 1 is the cross-JVM case a WAL stream would replace, and it measures 1.76 ms p50 / 2.63 ms p99 in the table above. A WAL stream will not clear that by a margin that pays for `wal_level = logical`, replication privileges, and a slot that pins WAL on disk when a consumer dies.

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

### 3.4.1 Partition detection, over a real network

Measured **2026-09-13**, `ShardOwnedCrossHostPartitionIT` (benchmark-gated). The cut-off node is a JVM
in its own container reached over a Docker network; the partition is `docker network disconnect`, so
its packets stop being routed while every socket stays open and nothing is told anything. It reaches
the database **by address rather than by alias** on purpose — disconnecting also removes the name from
the network's DNS, and a new connection then fails fast on an unknown host, which is not what a
partition does to a deployment that reached its database by address. Lease TTL 3 s, 4 shards.

| Client configuration | Survivor took the shards over after | Cut-off node noticed after |
|---|---|---|
| `socketTimeout=3` | 3 273 / 3 274 ms | 3 297 / 3 287 ms |
| no `socketTimeout` | 3 182 / 3 237 ms | **not within 90 s** |

Two runs, both figures given. Two things follow.

**Takeover owes nothing to the cut-off node.** It lands at one lease TTL in every arm, because what
releases the units is that node's membership row going stale — a fact about the database, not about
the node. Whether the node has noticed does not enter into it, which is the property the fence exists
to make safe and the reason the number does not move between arms.

**`socketTimeout` is what decides when the cut-off node finds out, and without one it does not.** The
90 s is a bound, not a measurement of the real figure: an untimed socket keeps retransmitting for
minutes. Nothing else rescues it either — the pool's own `connectionTimeout` never fires, because the
heartbeat thread is blocked inside a read on a connection the pool considers healthy and never asks
for another. Until that read returns, the node believes it owns shards a survivor is already serving
and keeps delivering from memory. At-least-once permits the duplicates that follow and the fence
refuses its acknowledgements when it returns, so nothing is lost or reordered — but the window is
the client's socket configuration, not the engine's.

**Set `socketTimeout` on the engine's DataSource.** It is the difference between a node rejoining in
seconds and one acting on beliefs it cannot check for as long as the OS keeps retransmitting.

### 3.4.2 A full disk, and what it does first

Measured **2026-09-13**, `ShardOwnedDiskPressureIT`. The whole data directory is a 256 MB tmpfs with a
48 MB ballast file on it, and a filler table exhausts the rest.

| What was tried | What happened |
|---|---|
| Filler rows of 1 MB, then 64 KB, 8 KB, 512 B | All four sizes refused: `ERROR: could not extend file "base/…": No space left on device` |
| 20 queue enqueues, with the volume in that state | **All 20 accepted** |
| Same fill against a 192 MB volume with no ballast | `PANIC: could not write to file "pg_wal/xlogtemp.NN"` — the cluster went down and came back through crash recovery |
| Ballast deleted, filler truncated | Delivery resumed with no restart and nothing lost |

Three things worth carrying out of that.

**"The disk is full" is not one state, and the queue reaches it last.** A relation that cannot extend
is not a queue that cannot write: the enqueues landed in pages already allocated, with WAL still
having room. So an application sees its first disk-full error somewhere else entirely, and the queue
keeps working for a while afterwards — which is good for the queue and misleading for anyone reading
the alert.

**Which wall you hit decides how bad it is.** Data-file exhaustion is an ordinary `ERROR` the caller
can handle. WAL exhaustion is a `PANIC`: the backend takes the whole cluster with it, every connection
dies at once, and it will do it again on the next write until somebody frees space. The 192 MB run hit
the second; the 256 MB run with ballast hit the first.

**A full cluster cannot free its own space** — `TRUNCATE` is a write too. That is why the recovery here
is deleting a ballast file from outside the database, and why keeping one on the volume is the
standard advice rather than a trick of this test.

What the engine does through all of it: keeps its shards, accepts or refuses each enqueue rather than
losing one, and resumes without a restart. A slow disk is the other half of disk pressure and is not
the same failure — see the engine document's §8.6, which is what stops an instance whose commits have
outrun the lease from delivering work its successor is already doing.

### 3.4.3 Payload size, and what the headline percentage actually is

Measured **2026-09-13**, `ShardOwnedVsBaselineCostIT.compare_per_message_cost_across_payload_sizes`
(benchmark-gated). Every other figure in this document uses a uniform 200-byte payload; this varies it
and holds the bytes per repetition constant at 16 MB, so each step is the same size on disk. Three
repetitions per arm per size, medians. Three runs of the whole sweep agreed to within a few bytes.

| payload | messages | baseline WAL B/msg | shard-owned WAL B/msg | difference | TOASTed |
|---|---|---|---|---|---|
| 200 B | 2 000 | 1 895 | 543 | **−71.4%** | no |
| 1 800 B | 2 000 | 3 514 | 2 148 | −38.9% | no |
| 8 000 B | 2 000 | 10 493 | 9 340 | −11.0% | **yes** |
| 64 000 B | 200 | 71 984 | 70 836 | **−1.6%** | **yes** |

**The advantage is a constant, not a percentage.** Subtract the columns: 1 352, 1 366, 1 153, 1 148
bytes per message. The saving barely moves across a 320-fold change in payload, because what produces
it — a narrower row and no claim write — is fixed per message, while the payload is a cost both
engines pay identically. The percentage falls only because the denominator grows.

So **−65 to −72% is a statement about 200-byte messages**, and §1 should be read that way. An
application moving 8 KB messages gets 11%, and one moving 64 KB gets nothing measurable. Nothing about
the design got worse; the figure was always a ratio, and the ratio was always against a small payload.

**Crossing the TOAST threshold costs a little of the saving**, from about 1 360 bytes to about 1 150.
Consistent with the mechanism: once the payload leaves the row for the TOAST relation, both engines'
rows are narrow, so the row-width half of the advantage largely goes and what remains is the absent
claim write. The threshold sits between 1 800 and 8 000 bytes, as PostgreSQL's roughly 2 KB
`TOAST_TUPLE_THRESHOLD` predicts.

**The filler has to be incompressible or this measures nothing.** PostgreSQL compresses before it
moves a value out of line, and the comparison previously filled its payload with one repeated byte —
harmless at 200 bytes inline, fatal at 64 KB, where it would collapse to almost nothing and never
reach the TOAST table. Both arms now carry random bytes (Base64 of random bytes for the baseline,
which travels as JSON text).

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

### The same four arms on a workstation

Re-measured **2026-09-14** on a macOS host outside the devcontainer, via `scripts/perf-host.sh`
(`perf-results/20260914-103550`). The JVM runs natively and PostgreSQL runs in the Docker Desktop VM,
so the two are in separate scheduling domains rather than sharing one cgroup — which is the
devcontainer's actual defect and the reason this run exists.

| Arm | msg/s | IQR | slowest..fastest run | threads | held conns |
|---|---|---|---|---|---|
| baseline, 20 ms poll, 20 consumers | 992 | 1.0% | 973..994 | 51 | 20 |
| baseline, 20 ms poll, **40** consumers | 1 974 | 0.4% | 1963..1979 | 70 | **41** |
| baseline, **5 ms** poll, 20 consumers | 3 724 | ⚠️ 14.7% | 2774..3868 | 36 | 21 |
| shard-owned | ⚠️ unquotable | **76.8%** | **3968..18315** | 36 | **7** |

**Threads and connections reproduced**, which is the half of this table that was ever a result. Thread
counts are identical to the devcontainer's. Doubling the baseline's consumers cost 21 connections here
against 13 there — a larger price, same mechanism — and the shard-owned engine held **7** in both
environments.

### ⚠️ The throughput column is not a result, and the container is not the reason

**The shard-owned figure must not be quoted, from either table.** Three identical repetitions produced
3 745 and 9 634 in the devcontainer, and 3 968 and 18 315 on the workstation.

**The workstation was expected to fix this and did not.** 76.8% against 78.2% — the interquartile range
did not shrink and the absolute range widened. So the shared eight-CPU cgroup is **not** the cause of
this instability, or not the whole of it. That explanation stood unchallenged until there was a host
measurement to test it against, and §4 below was written on the strength of it.

Two observations from the same run narrow the search:

- **It is not simply "any saturated arm".** `ShardOwnedVsBaselineCostIT`'s shard-owned arm is equally
  saturated — 20 000 messages in about 890 ms, 22 396 msg/s — and returned a **7.5%** interquartile
  range in the same session. The concurrency sweep's arms 1 through 64 reproduced to within 0.5%
  against the previous host run.
- **The baseline's 5 ms arm became *less* stable on the host**, 0.1% to 14.7%. The workstation is not
  uniformly the better lab either.

What distinguishes the unstable arm from the stable one is not established. This suite interleaves four
arms in one JVM against a 120-connection pool where the cost suite runs two, which is the obvious
difference and the next thing to test — run the shard-owned arm alone and see whether the spread
collapses. That is a far cheaper experiment than hardware, and until it is done "buy a better machine"
is not supported by anything measured.

The test prints a warning above 25% so the number cannot be lifted out of either table innocently.

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

**Not reliable:** throughput at saturation. The same 45 runs saw throughput vary **861%**, and at the capacity knee the same configuration produced interquartile ranges of 47–239% depending on nothing at all. Five interventions were tried — CPU pinning of both sides, JVM processor count matched to the quota, single-run isolation, `ANALYZE` placement, and a profile-independent warmup. None made it reliable.

**⚠️ The cgroup was the assumed cause and is now ruled out as a sufficient one.** This section used to
continue "the cgroup grants 8 CPUs while everything sizes its pools for 14, so any run that saturates
CPU is throttled", and every throughput caveat in this document descended from that sentence. It was an
explanation, never a measurement. `EngineResourceComparisonIT` has now been run on a macOS workstation
where the JVM and PostgreSQL sit in separate scheduling domains, and its shard-owned arm returned a
**76.8%** interquartile range against the devcontainer's 78.2% — see §3.6. Whatever destabilises that
arm follows the suite rather than the container.

The cgroup mismatch is still real and still worth removing; it is simply not what makes this figure
move. Note also what the same host run did *not* destabilise: per-message cost reproduced to within
0.1%, the concurrency sweep's arms 1–64 to within 0.5%, and a differently-structured saturated arm in
`ShardOwnedVsBaselineCostIT` held 7.5%. A cause that explains the unstable arm has to explain those too.

**Consequence:** every gate in the design is written against per-message cost, measured at the poll-bound operating point, with throughput reported but never gating. That consequence is unchanged — but the route out of it is no longer "get dedicated hardware", because dedicated hardware has now been tried. It is to find what the unstable arm does that the stable ones do not.

---

## 4.5 Choosing between the two implementations

**Scope: both implementations here are PostgreSQL.** The comparison is `PostgresqlDurableQueues`
against the shard-owned engine. If you need MongoDB you want `springdata-mongo-queue`, which is a third
thing and not in this comparison at all.

### The short answer

**On the same PostgreSQL, the shard-owned engine is faster and costs less per message than
`PostgresqlDurableQueues`, and the resource figures are the best-established numbers in this
document.** Concretely, and all of it reproduced across two hosts and the devcontainer:

- **−71.6% WAL bytes per message** at 200-byte payloads, which is a fixed saving of ~1 150–1 370 bytes
  per message rather than a percentage that travels (§3.4.3).
- **Half the dead tuples** (1.00 against 1.95–1.98), and **no claim write at all** — `n_tup_upd` is
  absent, not reduced.
- **7 held connections per process**, fixed, against 18–20 at 20 consumers and 31–41 at 40. **36 threads
  against 51–70.**
- **Indexes 4.7–9.3× smaller** after a soak.
- **Enqueue-to-handler p50 of 0.54–0.91 ms against 20.7 ms** at the shipped 20 ms poll — 23–38×. Under
  sustained load at 600 msg/s with both engines keeping up, **p50 0.87 ms against 13 ms and p99 1.4 ms
  against 28 ms**.

What is *not* established is a throughput multiple — see "raw throughput" below, which says precisely
what is and is not known. That is a limitation on one number, not a hedge about the direction.

The rest of this section is when that is worth acting on, and what it costs you.

### First: most of what used to block adoption no longer does

This section named eight blockers for a long time and **six of them have since been closed**. Anyone
who decided against this engine on the strength of an earlier reading of §4.5 decided on facts that are
no longer true, and should re-read the list below rather than trusting the conclusion.

What closed, and what it means concretely:

| Was listed as a blocker | Status |
|---|---|
| "The engine is not published" | **Published**, with the adapter and a Spring Boot starter. The `maven.deploy.skip` gate came off when the admin console page landed |
| "`Inbox`, `Outbox`, `EventProcessor` and `DurableLocalCommandBus` consume `DurableQueues`, which this engine does not implement" | `components/postgresql-queue-shard-owned-adapter` presents it **as** a `DurableQueues`. Those four run on it unchanged. `spring-boot-starter-postgresql-queue-shard-owned` selects it with `essentials.shard-owned-queue.durable-queues-enabled` (default `false`) |
| "There is no admin API or UI" | There is: `ShardOwnedQueuesApi`, `ShardOwnedQueuesController` in `spring-boot-starter-admin-api`, and a Shard-owned queues page on the console. Nine operations, in the generated OpenAPI document |
| "By-id operations assume a claim flag, the largest cost this design removes" | **This was wrong on its own terms.** `MessageId` is `(lane, shard, seq)` and the primary key is `(queue_id, shard, seq)` — addressing a row is a point lookup. The claim write is paid per *delivery*; an admin write is paid per *administrator*. The two were priced as if they had the same frequency |
| "Payloads are `bytea`, so you cannot read them in `psql`" | The `shard_queue_*_readable` views render them, falling back to hex for non-UTF-8. Through the adapter the payload is a JSON envelope |
| "`shardCount` is immutable, so sizing it wrong is a drain-and-switch" | `growShardCount` raises it online, picked up on the next heartbeat with **no restart**. Shrinking is still refused, because rows in removed shards would be addressed by nobody |

### Then: read the two remaining lists in the right order

They are not symmetrical, and reading them as a pros-and-cons balance gets the decision wrong.

**The "stay" list is about capability and risk** — things the engine cannot do at all, behaviours that
differ from `DurableQueues` in ways your code may notice, and a maturity judgement. **The "reach for it"
list is about degree** — less WAL, fewer connections, lower median latency.

Those do not trade against each other. If you need `TransactionalMode.FullyTransactional`, seventy
percent less WAL does not partially supply it, and the *size* of the advantage never enters the
question. A capability list is a filter; a performance list is a comparison; the filter runs first.

### Reach for the shard-owned engine when

| Because | Measured | Reproduced? |
|---|---|---|
| **Latency matters** | **p50 0.54 ms** in the lab and 0.88–0.91 ms on a workstation, against **20.7 ms** at the shipped 20 ms poll — 23–38× at the median. Push, not poll: there is no interval to tune, so the figure does not depend on a configuration choice the way the baseline's does | **Yes** at p50. This suite's *tail* figures do not travel; the sustained-load row below is where a reproducible tail comparison lives |
| **Sustained load, where latency must stay flat** | Ten minutes at 600 msg/s, both engines keeping up in every 30-second window: **p50 ~0.87 ms against ~13 ms, 15×**, and p99 ~1.4 ms against ~28 ms. Thirty minutes at 300/s in the lab agrees | **Yes** — same shape across three soaks in two environments |
| **Volume makes per-message cost matter, *and the messages are small*** | A fixed **~1 150–1 370 WAL bytes saved per message**: −71% at 200 bytes, −39% at 1 800, −11% at 8 000, −1.6% at 64 KB (§3.4.3). Dead tuples 1.95–1.98 → **1.00**. The claim write is absent, not reduced | **Yes** — the most solid result here. Two hosts and the devcontainer agree to within 0.1% |
| **Storage growth matters** | Indexes **4.7–9.3× smaller** across soaks, the range depending on rate and duration. That is the lane split and the narrower index set, not a vacuum artefact | **Yes** in direction and rough size; the multiple itself moves with the run |
| **The process holds many queues, or connections are scarce** | **7 held connections** for the whole process — `pumpThreads + 1`, unchanged by shard, queue or consumer count — against 18–20 at 20 consumers and 31–41 at 40. Threads 36 against 51–70 | **Yes**, exactly, on both a workstation and in the lab |
| **Per-key ordering must hold across processes** | Verified across ~19 lease lifetimes with a third node joining mid-run, and under `SIGKILL`, `SIGSTOP`/`SIGCONT` and a real network partition (§3.4). The current implementation's own documentation says ordering does *not* hold across instances | Behavioural, not a timing figure — it does not depend on the lab |
| **An outbox needs atomicity *and* working retries** | `enqueue(Connection, …)` joins the caller's transaction while attempt counting stays outside its rollback scope. `TransactionalMode.FullyTransactional` gives one or the other | Behavioural |

### How much to trust each row

The two 2026-09-14 workstation runs were run to find out which of these figures are properties of the
engine and which are properties of whatever machine measured them. That turned out to be the more
useful question, and the answer splits cleanly:

| Confidence | Figures | Basis |
|---|---|---|
| **Quote freely** | Per-message WAL bytes, dead tuples per message, row updates, the constant-saving shape across payload sizes (§3.4.3), held connections and thread counts (§3.6) | Agree across two hosts and the devcontainer — WAL to within 0.1%, connections and threads exactly |
| **Quote with the conditions attached** | Latency **p50**, both tiers (§3.2). The soak's p50, its **p99**, and the engine-to-engine ratios built from them (§3.5). Index size ratios | Reproduce in shape and often closely — between the two host runs the soak's baseline p99 agreed to 0.3% and the shard-owned p99 to 16%. But they move with host, rate and duration, so state the conditions or state a range |
| **Do not quote** | Four specific figures, named below | Each varied by more than 2× between repetitions or runs of the same commit |
| **Unknown** | The soak's shard-owned p99 *across environments* | 3 469 µs in the devcontainer at 300/s for 30 min, 1 385–1 608 µs on a workstation at 600/s for 10 min. Three variables changed at once, so the difference is unattributed rather than measured |

**The four "do not quote" figures, precisely.** This is a short list of named measurements, not a
blanket ban on a statistic:

1. **The shard-owned engine's throughput when it is the bottleneck** (§3.6). Three identical
   repetitions gave 3 745 and 9 634 msg/s in the devcontainer, 3 968 and 18 315 on a workstation —
   76.8% and 78.2% interquartile range.
2. **The concurrency sweep's 128-consumer arm** (§3.7). 3 607 msg/s in one host run, 22 727 in the
   next, at a 44.2% spread. Arms 1 through 64 in the same sweep reproduced to within 0.5% and are fine.
3. **Tier 1's p90, p99 and max** (§3.2). Response p99 measured 6.69 ms and 40.45 ms an hour apart on
   one machine, with service-time p99 moving 4.71 → 14.92 ms alongside it — so it is the engine or the
   environment, not the producer.
4. **Tier 2's *response* p99 and max on a host where the producer stalls** (§3.2). 17–20 ms against a
   1.56–1.74 ms service-time p99 for the same messages. The service figure is the sound one, and it
   *is* quotable with conditions: it reproduced to 12% between the two host runs.

Everything not on that list is on one of the rows above it. In particular **the soak's p99 is not on
this list** — at sustained load, both engines' tail latencies held between runs, and the 15×/20×
sustained-load ratios in the table above are built from figures that reproduced.

**The practical reading for anyone sizing a system:** the cost and connection advantages are real,
measured, and will show up in your database. The latency advantage at the median is real and large, and
under sustained load the tail advantage holds too. What you cannot take from this document is a
*peak throughput* number, or a tail figure from the burst-latency suite — measure those on your own
hardware with your own payloads, and see §3.6 for why we cannot hand you ours.

**Stay on the current implementation when**

**Hard stops — the capability is absent, and no configuration supplies it:**

| Because |
|---|
| **You need `TransactionalMode.FullyTransactional`** — the handler's writes and the dequeue committing together. The adapter reports `SingleOperationTransaction` and **refuses** `FullyTransactional` rather than approximating it: acknowledgements are batched and flushed on the owner's connection under a fence, and cannot enlist in a caller's transaction. Note the half that *does* work — `enqueue(Connection, …)` joins the caller's transaction, so an Outbox enqueue rolls back with the business transaction |
| **You call `queryForMessagesSoonReadyForDelivery` or `getNextMessageReadyForDelivery`.** These are the two operations of the `DurableQueues` surface that throw. The first needs an ordering by next-delivery timestamp across every shard of both lanes, which no index produces; the second needs a row-lease session outliving the call. The admin surface needs neither |

**Behavioural differences — it works, but not identically, and your code may notice:**

| Because |
|---|
| **`QueuedMessage` is *partial* inside a handler** — though less so than it was. The engine's `MessageHandler` receives `(messageId, key, payload, payloadType)`, so **`getId()` is answered**; `getTotalDeliveryAttempts()` and the timestamp accessors still **throw** rather than returning a stub, because reading them would widen a cursor read that runs roughly twice per delivered message. Anything on your delivery path that touches *those* fails the delivery, and the stack trace will look like the framework breaking rather than like a partial message. The escape hatch is the id: pass it to `getQueuedMessage(queueEntryId)` and read the full row, paying per lookup instead of per message. A `DurableQueuesInterceptor` is handed the same surface and the same rule |
| **`markForRedeliveryIn(delay)` redelivers, but not after `delay`** — it throws, and the engine schedules from its own `RedeliveryPolicy`, because there is no id to schedule against from inside a handler. The attempt still counts against the policy's budget |
| **Retry is the engine's**, not `DefaultDurableQueueConsumer`'s. The `RedeliveryPolicy` is translated once into `ConsumerOptions` at subscription time, with an off-by-one to know about: the policy counts *re*deliveries, the engine counts attempts |
| **`resurrectDeadLetterMessage` returns empty even when it succeeds.** The message re-enters at a fresh sequence, so its `QueueEntryId` changes and the old one no longer addresses it |

**A judgement call rather than a fact:**

| Because |
|---|
| **Maturity.** The engine is published and its `MessageQueue` contract is frozen — additive in a minor, breaking only in a major — but it is far newer than `PostgresqlDurableQueues`, which is shipped, published and in production. How much that matters is yours to weigh; it is not a missing capability |
| **`shardCount` grows but never shrinks.** Growing is online with no restart. Sizing it too *high* is the cheaper mistake, and it must be at least the largest number of instances you will ever run, since a lane's unit count caps how many instances can hold anything for it |

### Raw throughput: the direction is established, the multiple is not

These are different claims and this section used to collapse them into "unquotable", which understated
what the runs show.

**What is established.** In every head-to-head repetition recorded — three suites, two hosts, the
devcontainer — the shard-owned engine moved more messages per second than the baseline arm interleaved
beside it. Its *slowest* observation anywhere is **3 968 msg/s** (the resources suite, which is the
unstable one); the baseline's *fastest* in that same run is **3 868 msg/s**, and 3 915 msg/s in the
devcontainer. In the cost suite the shard-owned floor is **7 435 msg/s**. The 76.8% spread is entirely
in the upper bound — the floor is stable and sits above the baseline's ceiling.

**What is not established: by how much.** Across repetitions the ratio ranges from about 1.03× to 4.7×,
and no repetition of that measurement predicts the next. Do not quote a multiple.

**And one honest limit on the comparison.** The baseline arms here poll at 20 ms and 5 ms. The
baseline's own capacity sweep (§2.3) reached 9 881 msg/s at a 2 ms poll, which lands *inside* the
shard-owned engine's measured range rather than below it — so "beats a maximally tuned baseline" is
**not** shown. It is also not refuted, and §2.3 carries its own warning that numbers taken at the knee
do not reproduce in this lab.

**What does not depend on any of that** is the price of the throughput. The baseline's is a
configuration choice — every arm lands within 0.5% of `parallelConsumers × pollsPerSecond` — and each
route to more of it costs something: more consumers costs connections (18 → 41 when doubled), a faster
poll costs query rate against a database that was never the bottleneck. The shard-owned engine pays
neither, holding **7 connections per process** with no interval to tune. So the sound reason to move is
not "it is N times faster"; it is that it reaches the same class of throughput for a fraction of the
connections, threads, WAL and dead tuples — and those are the figures that reproduce.

**They are not mutually exclusive.** Both can run against the same database on different tables, so
adoption can be per queue: move the latency-sensitive or high-volume ones and leave the rest.

---

## 5. What has not been measured

Stated so that absence is not mistaken for a passing result.

**This list and [`durable-queue-shard-owned.md`](./durable-queue-shard-owned.md) §18 describe the same
gaps from two angles — detail here, priority there. Change one and change the other.** They were
allowed to drift apart once and three entries survived the work that closed them, which is how a gap
list starts costing more than it gives.

- ~~**Partition between separate hosts.**~~ **Closed, in both halves.** The behaviour was already
  covered by `ShardOwnedNetworkPartitionIT` — a forwarder that stops passing bytes without closing
  anything, after which the cut-off instance's units are taken, the queue keeps delivering, and it is
  fenced out on healing. What a forwarder cannot reproduce is timing, and that is now measured
  separately (§3.4.1): the cut-off node in its own container, the partition made with
  `docker network disconnect`, and the finding that `socketTimeout` is the whole difference between
  finding out in three seconds and not finding out within ninety. The containers do share a host
  kernel; that is a decision rather than an outstanding gap, and it is recorded as one in
  [`durable-queue-shard-owned.md`](./durable-queue-shard-owned.md) §18.3 along with what would reopen
  it.
- **Clock skew is not a gap and should stop being listed as one.** All durable time is server-side
  `now()` — `visible_at`, `lease_until`, `last_seen` — and `ServerSideTimeTest` fails the build if a
  client clock reaches the storage layer. Independent clocks change nothing.
- **Sustained soak beyond half an hour.** The longest run is the thirty minutes in §3.5 — 540 000 messages per arm, thirty autovacuum cycles. Vacuum behaviour, index bloat and p99 drift over *hours* remain unmeasured, and the baseline's dead-tuple cost is precisely the kind of thing that would only show as drift at that scale. §3.5 looked for it at six minutes and again at thirty and did not find it, which is not the same as it not being there. Nor has any soak run at a rate near either engine's capacity: 300/s keeps the latency signal clean and accumulates debt slowly, and the opposite trade has not been measured.
- ~~**Realistic payload distribution.**~~ **Half closed.** Payload *size* is now swept from 200 bytes to 64 KB, across the TOAST threshold, in §3.4.3 — which is what showed that the headline percentage belongs to the 200-byte payload and the saving is a constant. What remains untested is a realistic *distribution*: every run is uniform, so nothing exercises a mix of sizes in one queue, where a large message's TOAST chunks and a small one's inline row share the same pages and the same vacuum.
- ~~**Failure injection beyond process death, connection loss, partition and restart.**~~ **Closed.** Those four are covered (`ShardOwnedMultiProcessIT`, `ShardOwnedConnectionLossIT`, `ShardOwnedNetworkPartitionIT`, `ShardOwnedDatabaseRestartIT`), and disk pressure is now covered in both of its forms: a full volume in §3.4.2, and the slow-disk case as the condition it actually produces — commits outrunning the lease — in `ShardOwnedStaleLivenessIT`. How a genuinely slow *device* behaves is not measured, and that is a decision rather than an omission — `durable-queue-shard-owned.md` §18.3 records it with what would reopen it. What the engine is exposed to is the consequence, and the consequence is what is tested.
- **Throughput at saturation, anywhere.** This used to read "throughput on hardware that can measure
  it", which assumed such hardware existed and had merely not been used. It has now been used: the
  workstation run of 2026-09-14 reproduced the instability at 76.8% (§3.6, §4). The gap is therefore
  no longer "we lack a machine" but "we do not know what makes this arm move", and the first step is
  the isolation experiment in §3.6 rather than another environment.
- **Idle cost of a large routing space across many queues.** §3.8 varies the space on one queue and the queue count at one space; the product of the two — hundreds of queues at 1 024 units each — is not measured, and per-unit state (a lease row and an owner object each) is what would grow.
- **Growing an existing queue's routing space.** Not built; see `durable-queue-ordered-routing-design.md` §8.

## 6. Where the code lives

Three published reactor modules, not one:

| Module | What it is |
|---|---|
| `components/postgresql-queue-shard-owned` | The engine and its semantics tests. Exposes `MessageQueue`, a **new contract** rather than an implementation of `DurableQueues`. **Published** — the `maven.deploy.skip=true` gate came off when the admin console page closed the "no admin UI" gap, which also froze `MessageQueue` as a compatibility-checked contract |
| `components/postgresql-queue-shard-owned-adapter` | Presents the engine **as** a `DurableQueues`, so `Inbox`, `Outbox`, `EventProcessor` and `DurableLocalCommandBus` run on it unchanged. Two of the interface's operations throw; see §4.5 |
| `components/spring-boot-starter-postgresql-queue-shard-owned` | Selects the adapter's `DurableQueues` on `essentials.shard-owned-queue.durable-queues-enabled` (default `false`), with `auto-register-shard-count` beside it |

The admin controller lives in `spring-boot-starter-admin-api` with every other admin controller, per the
three-place rule for admin operations. The benchmarks comparing the two engines stay in
`examples/essentials-performance-lab`, which is where the harness and the other engine are, and which
is **not** published.

## 7. Reproducing

### 7.1 On a real machine, outside the devcontainer

`scripts/perf-host.sh`. One command, roughly an hour. It was written to close the one gap this lab
cannot close from inside itself — **throughput on hardware that can hold a number still** (§18.1) — and
the first two runs of it, on 2026-09-14, established that the gap is not about hardware. Keep running
it anyway: it is the only way to tell an environment-specific figure from a real one, and it is what
showed that per-message cost travels between environments while throughput does not.

```bash
scripts/perf-host.sh --dry-run     # what it would run, and what it thinks of the machine
scripts/perf-host.sh               # cost, latency, concurrency, resources, then a 10-minute soak per arm
scripts/perf-host.sh --no-soak     # the first four only, ~25 minutes
scripts/perf-host.sh --soak 30 --rate 1000
```

**The figure to read is not the peak — it is the interquartile range beside each median.** Every
throughput number in this document is marked comparative-only because throughput at saturation varied
861% between repetitions.

**The test this section proposed has been run, and it failed.** The proposal was: if the IQRs come back
tight on a workstation and wide in the devcontainer, the lab was the problem and those figures can
start being quoted absolutely. They came back **76.8% on the workstation against 78.2% in the
devcontainer** (§3.6), so the premise — that `dockerd`-in-`dockerd` and the shared eight-CPU cgroup are
what move the number — does not survive its own test. Read the IQRs for exactly what they are, and do
not assume a wide one here will be narrow somewhere else.

What the same comparison *did* establish is which figures travel. Per-message cost agreed to within
0.1% across two hosts and the devcontainer; the concurrency sweep's lower arms to within 0.5%; thread
and connection counts reproduced outright. Those are the quotable ones.

**On macOS the isolation is better than it looks.** The JVM runs natively and PostgreSQL runs in the
Docker Desktop VM, so the two are in separate scheduling domains rather than sharing a cgroup — which
removes the devcontainer's most obvious defect, though not, as it turns out, the instability. What is
lost is deliberate partitioning: there is no
`taskset` on macOS and the JVM cannot be pinned, so the arms are not assigned disjoint cores. The
script records that in `environment.json` rather than leaving it to be inferred.

Two settings decide whether the run measures the engine or the VM, both in **Docker Desktop →
Settings → Resources**: give it **at least 8 CPUs and 16 GB**, and leave the host a few cores rather
than handing Docker all of them — otherwise the JVM and PostgreSQL contend for every core and the
result reproduces the lab's problem on better hardware. The script checks both and says so.

Results land in `perf-results/<timestamp>/`: a log and an extracted summary table per suite, the raw
JSON the suites write, and `environment.json` — host, Docker allocation, JVM, `shared_buffers`,
whether the CPUs were partitioned, and the commit with a dirty flag. That file is what makes one run
comparable with another; a number without it cannot be diffed against anything later.

Prerequisites the script checks before it starts: Docker running, and a JDK between 21 and 25 (the
enforcer requires `[21,26)`). It builds what it measures first — `./mvnw install -pl
examples/essentials-performance-lab -am -DskipTests` — so a fresh clone needs to populate `~/.m2`
once, which is not counted in the hour.

### 7.2 Individual suites

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
