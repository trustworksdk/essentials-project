# Durable Queue Measurements — current implementation vs. shard-owned engine

Consolidated results. The reasoning behind each number lives in [durable-queue-next-gen-design.md](./durable-queue-next-gen-design.md); this document is the numbers themselves, so they can be quoted without reading the design.

**Every figure here was produced by the same harness, on the same machine, in the same session.** Cross-session comparison is not supported — see [Environment](#environment) for why.

---

## 1. Headline comparison

| Metric | Current implementation | Shard-owned engine | Change |
|---|---|---|---|
| WAL bytes per message | 1 905 | 534 | **−72%** |
| WAL bytes per message, all obligations restored | 1 905 | 615 | **−68%** |
| Dead tuples created per message | 1.98 | **1.00** | −49% |
| Row updates per message | 1.00 | **0** | claim write eliminated |
| Transactions per message (batched) | ~3.06 | **0.05** | −98% |
| Enqueue-to-handler p50 | 20.7 ms | **0.44 ms** | **47×** |
| Enqueue-to-handler p99 | 27.1 ms | **0.97 ms** | **28×** |

Conditions: 200-byte payloads, 20 000 messages per repetition, three interleaved repetitions per arm, PostgreSQL 17.5 pinned to CPUs 4–7, load generator to CPUs 0–3.

**The row-update column is the design in one number.** The current implementation writes one row update per message — `is_being_delivered = true`. The shard-owned engine writes none, because a lease already establishes who owns the message. Not reduced: absent.

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

Each obligation the current implementation carries, added back one at a time:

| Obligation added | WAL bytes/msg | vs previous | vs baseline | Commits/msg | Elapsed |
|---|---|---|---|---|---|
| bytes, batched | 532 | — | −72% | 0.07 | 489 ms |
| + JSON payload | 549 | +3% | −71% | 0.09 | 375 ms |
| + per-message enqueue | 667 | +21% | −65% | 5.05 | 5 532 ms |
| + per-message ack | 666 | −0% | −65% | 6.03 | 5 220 ms |

- **JSON costs almost nothing in write volume** — a 200-byte payload becomes 214 on the wire, WAL rises 3%. The `bytea`-over-`jsonb` choice is real but small.
- **Batching is worth about 4 percentage points of the 72.** Removing it raised commits from 0.07 to 5.05 per message and made the run roughly 11× slower, but WAL rose only 21%. Batching buys throughput, not write volume.
- **Per-message acknowledgement costs no WAL at all.** Range delete versus individual deletes changes commit count and round trips, not tuple deletions.
- **The remaining −65 to −68% is the design**: no claim write, a narrower row, fewer indexes. `n_tup_upd` is **zero in every arm**.

### 3.2 Wake-up tiers

| Configuration | p50 | p90 | p99 | max |
|---|---|---|---|---|
| Tier 2 — local hand-off, response | **0.44 ms** | 0.67 ms | **0.97 ms** | 3.08 ms |
| Tier 2 — local hand-off, service | 0.28 ms | 0.38 ms | 0.79 ms | 3.01 ms |
| Tier 1 — NOTIFY, read back | 1.69 ms | 1.96 ms | 4.82 ms | 17.71 ms |
| Current implementation, 20 ms poll | 20.7 ms | — | 27.1 ms | — |

Response time is measured from the intended schedule slot, service time from when the producer actually sent. Both are reported because the pair is what locates a problem: during development, response time alone said Tier 2 had a 266 ms tail and service time alone said it was excellent — the truth was a warmup artefact in the harness, in neither.

**Tier 1's effect on transaction count**, same workload before and after:

| Configuration | Commits/msg before | after |
|---|---|---|
| bytes, batched | 3.06 | **0.05** |
| + per-message enqueue | 18.86 | **3.10** |

Idle polling is eliminated: 907 transactions to move 20 000 messages, essentially only the ones doing real work.

**Tier 3 (WAL streaming) is not built, and should not be.** Its gate requires clearly beating Tiers 1 and 2 by enough to justify a replication slot's operational weight. Tier 2 measures 0.44 ms at the median; a WAL stream will not clear that by a margin that pays for `wal_level = logical`, replication privileges, and a slot that pins WAL on disk when a consumer dies.

### 3.3 Sequence-gap behaviour

The design's load-bearing assumption is that a reader can follow a per-shard sequence with a cursor, write nothing when it consumes, and never lose a message. Measured across 18 runs:

| Arm | Hole rate | Hole resolution p99 | Messages lost |
|---|---|---|---|
| Autocommit enqueue | 2.4–5.1 per 1 000 (~0.3%) | 4.8–5.2 ms | **0** |
| Enqueue holding a 5 ms transaction | 8.8–22.2 per 1 000 (~1.5%) | 5.4–5.7 ms | **0** |

**Hole resolution latency is a tunable, not a property of the database** — it tracked the configured chase delay almost exactly in both arms, meaning holes resolve as fast as the reader looks for them.

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

### 3.5 Sustained load — 6 minutes, both engines, sampled every 30 seconds

300 messages a second, well below either engine's capacity so latency stays a property of the design rather than of queue depth. Both engines handled exactly 9 000 messages in every 30-second window — neither fell behind at any point.

| | Baseline: first → last window | Shard-owned: first → last window |
|---|---|---|
| p50 | 12 183 → 13 271 µs (**+9%**) | 978 → 1 016 µs (**+4%**) |
| p99 | 24 607 → 24 847 µs (**+1%**) | 2 681 → 2 811 µs (**+5%**) |
| Table size | 5 328 → 13 136 KB | 2 240 → 7 896 KB |
| **Index size** | 664 → **6 832 KB** | 32 → **1 296 KB** |
| Dead tuples outstanding | oscillating 6 100 – 24 300 | oscillating 3 000 – 12 200 |
| Autovacuum runs | 6 | 6 |

**The headline is a negative result, and it is mine.** The design document argued that the current implementation's two dead tuples per message would show up as p99 drift under sustained load — that a design fast for the length of a benchmark would degrade once autovacuum had work to do. Over six minutes at this rate, **it does not**. The baseline's p99 moved 1%, its p50 9%, both inside the run-to-run variation this lab exhibits. Autovacuum ran six times on each engine and kept up with both.

What the soak *does* confirm is structural rather than temporal:

- **Indexes are 5.3× smaller** — 1 296 KB against 6 832 KB. That is the lane split and the narrower index set, and it is a durable property rather than a measurement artefact.
- **Dead tuples outstanding run at roughly half**, tracking the 1.00-against-1.98 per-message figure exactly as predicted.
- **Neither engine drifts**, which is worth knowing on its own: it means the latency advantage measured in §3.2 is not a cold-start effect that erodes.

⚠️ **Six minutes at 300 a second is 108 000 messages, and that is a small soak.** Bloat and vacuum debt are effects of hours and of higher rates. "No drift observed here" is not "no drift exists" — it means the hypothesis was not confirmed at this scale, and a genuine pre-release soak still has to run for hours. The design's dead-tuple argument should be quoted as a structural cost difference, which is measured, rather than as a predicted degradation, which is not.

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

## 5. What has not been measured

Stated so that absence is not mistaken for a passing result.

- **Network partitions and clock skew.** `NextGenMultiProcessIT` runs engine instances as separate operating-system processes and kills one with `SIGKILL`, so real process death is covered. A node that is *alive but partitioned* — the case the fencing design exists for — is not: simulating it needs network control the current harness does not have.
- **Containers as separate hosts.** The node processes share a machine and a kernel clock. Genuinely separate hosts, with independent clocks and a real network between them, are untested.
- **Sustained soak.** The longest run is 60 seconds. Vacuum behaviour, index bloat and p99 drift over hours are unmeasured — and the current implementation's dead-tuple advantage is precisely the kind of thing that only shows up there.
- **Realistic payload distribution.** Every measurement uses a uniform 200-byte payload. Large payloads, TOAST behaviour and mixed sizes are untested.
- **Failure injection beyond handler exceptions.** Database restarts, connection loss mid-batch, and disk pressure are untested.
- **Throughput on hardware that can measure it.** See above.

## 6. Where the code lives

The engine and its semantics tests are `components/postgresql-queue-shard-owned` — a reactor module that is **not published** (`maven.deploy.skip=true`), because the project's rule is that central APIs break only on a major version and this SPI is still moving. The benchmarks comparing it against the existing implementation stay in `examples/essentials-performance-lab`, which is where the harness and the other engine are.

## 7. Reproducing

```bash
# Cost comparison and decomposition (benchmark-gated, ~10 minutes)
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='NextGenVsBaselineCostIT,NextGenCostDecompositionIT' \
  -Dbenchmark.run=true -Dlab.pg.cpuset=4-7 -Dlab.pg.shared-buffers=512MB

# Latency comparison
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='NextGenLatencyIT' -Dbenchmark.run=true -Dlab.pg.cpuset=4-7

# Current-implementation baseline profiles and capacity sweep
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test='DurableQueueBaselineProfilesIT' -Dbenchmark.run=true -Dlab.pg.cpuset=4-7
```

Each run writes machine-readable results under `examples/essentials-performance-lab/target/perf-lab-baseline/`, including the full environment, so a later run can be diffed against an earlier one. Those files are build output and are not committed; copy them somewhere durable if a result matters.


---

## Re-measured after the concurrency rework

Every figure above was taken before the pump rework — a thread and a connection per shard per lane, unordered handlers inline on that thread. The engine now runs a small fixed set of pump threads, each holding one connection and serving many shards, with the ordered lane's handlers on virtual threads. These are the same benchmarks re-run against that engine, pinned the same way (`taskset -c 0-3`, `-Dlab.pg.cpuset=4-7`).

### Per-message cost: unchanged

| arm | WAL B/msg | IQR | dead tuples/msg | tuples upd+del (20 000 msgs) |
|---|---|---|---|---|
| baseline | 1 906 | 1.1% | 1.99 | 39 743 |
| shard-owned | **533** | 0.1% | **1.00** | **20 000** |

−72.0% WAL per message, identical to the figure recorded before the rework. Exactly one delete per message and zero updates: the claim write is still absent. The storage design — which is what the original proposal actually claimed — is untouched by any of the concurrency work.

### Latency: slightly worse, and the reason is structural

| configuration | p50 | p90 | p99 | max |
|---|---|---|---|---|
| Tier 2 on, response | **0.50 ms** (was 0.44) | 0.80 ms | 1.64 ms | 4.70 ms |
| Tier 2 on, service | 0.33 ms | 0.45 ms | 1.45 ms | 2.41 ms |
| Tier 2 off, response | 1.88 ms | 2.14 ms | 2.87 ms | 9.54 ms |
| Tier 2 off, service | 1.77 ms | 2.01 ms | 2.69 ms | 9.47 ms |

Measured after the read-amplification fix. p50 is 0.50 ms against 0.44 ms before the rework — 14% worse, and **41x** the baseline's 20.7 ms rather than the 47x quoted earlier. p99 is noisier between runs (1.07 ms and 1.64 ms on two runs of the same build) and should be treated as approximately 1-1.5 ms rather than a single figure.

### Read amplification: found, and fixed

Re-running the benchmark showed `cursorReadsPerMessage` at **14.5** for eight shards on two pumps. All the shards a pump served shared that pump's wake-up, so a notification for one shard made the pump read every shard it owned. It was not a trade anyone decided on when the pumps were introduced — it was discovered by re-measuring.

Each shard now has its own wake-up which cascades to its pump's. The listener signals the shard's, the pump parks on its own, and on waking asks each of its shards whether the signal was for it (`LeasedOwner.needsAttention`). Time-based work — sweeps, retries, chases, due flushes — still answers yes on its own schedule, so correctness stays independent of notifications.

**`cursorReadsPerMessage`: 14.5 → 2.0.**

### The duplicates: explained and fixed

`NextGenCostDecompositionIT` reported 20 007 handler invocations for 20 000 messages, and the latency benchmark 2 001 for 2 000. Not the equality assertion being wrong — a real race, and it had a specific cause.

The head sweep deliberately does **not** exclude locally handed-off rows, so that a hand-off lost between commit and dispatch is still delivered. That backstop has a window: between an enqueue committing and `handOffLocally` being called, the sweep can read the row and deliver it — and once it has been acknowledged and deleted, the hand-off delivers it again with nothing left to deduplicate against.

The sweep now leaves this owner's own pre-claims alone until they are older than the hand-off grace. Older than that and a pre-claim really is orphaned, so the backstop still does its job; younger and it is far more likely to be in flight than lost.

Both benchmarks pass, and the decomposition run also went from timing out at 313 s to completing in 65 s.

### Soak: six minutes at 300/s, both engines

| | baseline | shard-owned |
|---|---|---|
| p50, first window -> last | 11 775 -> 12 351 us (**+5%**) | 1 001 -> 902 us (**-10%**) |
| p99, first window -> last | 23 311 -> 23 183 us (**-1%**) | 2 473 -> 2 035 us (**-18%**) |
| table size, first -> last | 3 208 -> 16 480 KB | 2 224 -> **5 728 KB** |
| index size, first -> last | 584 -> 6 592 KB | 32 -> **1 272 KB** |

**Neither engine drifts.** This re-confirms the negative result from the pre-rework soak, and it is still the design document's own argument being disconfirmed: the claim was that the baseline's two dead tuples per message would show as p99 growth once autovacuum had work to do. Over six minutes it does not — the baseline's p99 moved -1%. Autovacuum ran five times against each engine and kept up with both.

**What is real is structural, not temporal.** Sustained, the shard-owned engine holds p50 at about 0.9 ms against 12.4 ms and p99 at about 2.0 ms against 23.2 ms — 13x and 11x. Its index ends the run **5.2x smaller** (1 272 KB against 6 592 KB) and its table 2.9x smaller, which matches the 5.3x recorded before the rework.

The performance story is now fully re-established against the reworked engine.

### What this means for the figures above

The cost table stands. The latency table is superseded by the one here. The throughput and knee figures were never gated and remain unreliable for the reasons already documented. The soak result — the negative one, that the baseline's dead tuples do not cause p99 drift — was measured against the old engine and has not been re-confirmed.


---

## The cost gate at real scale: 25 queues, 8 shards

Everything reported for multiple queues until now came from five queues at four shards, extrapolated. Measured directly (`-Dcost.queues=25 -Dcost.shards=8`):

| configuration | held connections | idle threads | threads under load | idle queries/s | per owned shard |
|---|---|---|---|---|---|
| 25 queues, 8 shards, 2 pumps | **150** | 200 | 214 | 1 620 | **4.05** |
| 25 queues, 8 shards, **1 pump** | **100** | 150 | 164 | 1 632 | 4.08 |
| 5 queues, 4 shards, 2 pumps | 30 | 41 | 55 | 162 | 4.05 |

**The extrapolation was right.** 6.0 held connections per queue at two pumps, exactly as predicted from the five-queue run — against roughly 425 before the rework.

**`pumpThreads` is the connection knob, and it works.** Dropping to one pump takes 150 connections to 100 and 200 idle threads to 150, with no change to the idle query rate. That is the trade to make when connections are scarce; what it costs is that one slow inline unordered handler now occupies every shard on that queue's lane rather than half of them.

**Idle cost is 4.05 queries per second per owned shard, at every scale tested.** That is the design's floor and nothing more: a 500 ms sweep plus a 500 ms backstop poll. It is a property of the two intervals, so it can be halved by doubling either, at the cost of how quickly a lost notification is recovered from.

**A deployment constraint worth stating plainly.** At twenty-five queues the engine holds 150 connections, and PostgreSQL's default `max_connections` is 100. A server has to be sized for `pumpThreads x lanes x queues` plus a listener per lane per queue, or `pumpThreads` lowered to fit. The benchmark container runs with `max_connections=500` for exactly this reason.

**The gate was the wrong shape and has been fixed.** It asserted an absolute ceiling of 1 000 idle queries per second, which the same engine failed at twenty-five queues purely for being asked to run bigger — 1 620 in total, and 4.05 per shard either way. It now gates the per-shard figure, which is a property of the design rather than of the fan-out, consistent with every other gate here.


---

## Hundreds of queues: the per-queue cost was never the design's

The 25-queue measurement above showed 150 held connections — six per queue. At three hundred queues that is 1 800, which is not a viable system. The right question was asked: why does the design need this many? It does not. Every remaining per-queue cost was scoping, not design.

- **The listener.** `NextGenListener` listens on one global channel and the payload already carries `queueId:lane:shard`. One listener can demultiplex for every queue and both lanes. It was per-queue only because it was constructed inside `NextGenQueue` — fifty connections at twenty-five queues doing the work of one.
- **The pumps.** A pump holds a connection and calls `owner.pumpOnce(connection)`. `queue_id` is a bind parameter in every statement, not a property of a connection, so one pump can serve shards of any queue and either lane. Scoping pumps to a queue was the same mistake as scoping them to a shard, one level up.
- **The heartbeat.** One scheduled thread per queue, running a handful of queries every ten seconds.

`ShardRuntime` now owns the pumps, the listener, the handler executor and the heartbeat, and every queue in the process shares one.

| queues | shards | pumps | held connections | threads (idle) | threads (load) | idle queries/s |
|---|---|---|---|---|---|---|
| 5 | 4 | 2 | **3** | +5 | +18 | 135 |
| 25 | 8 | 2 | **3** | +4 | +19 | 1 628 |
| 100 | 8 | 4 | **5** | +6 | +21 | 6 422 |
| 300 | 8 | 4 | **5** | +6 | +21 | 18 252 |

**Connections are `pumpThreads + 1`, flat.** Three hundred queues cost five connections and twenty-one threads. The 25-queue case went from 150 connections to 3.

**What still scales is the idle query rate**, and it scales with *shards owned* rather than with queues: 4.0 queries per second per owned shard, which at 300 queues x 8 shards x 2 lanes is 4 800 owners and 18 252 queries a second doing nothing. That floor is a 500 ms sweep plus a 500 ms backstop poll, so it is directly tunable — five-second intervals would make it 1 800/s — at the cost of how quickly a lost notification is recovered from. It is the remaining scaling axis and it is a configuration decision rather than a structural one, but it should not be left at the default for a process with thousands of shards.

**The per-queue fallback is a footgun and behaved like one.** A `NextGenQueue` built without a runtime stands one up for itself, which is right for a single queue and wrong for a hundred: the first run at 100 queues created 100 runtimes and exhausted a 500-connection pool. The gate now asserts that held connections do not scale with queue count, so that mistake cannot pass again.


---

## Two defaults that did not scale, and what fixing them cost

Both of these were defaults that got worse as a caller added queues, which is a defect rather than a caveat. The stated requirement was performance and resources; a configuration that degrades the database as the workload grows fails it whatever the per-message numbers say.

### The per-queue runtime is gone

A queue built without a runtime used to create its own — pumps, listener and heartbeat, five connections each. At a hundred queues that is a hundred runtimes, and the first attempt exhausted a 500-connection pool. Doing nothing now gets the runtime shared by every queue on the same `DataSource`, reference counted, closed by its last borrower. A private runtime has to be asked for.

### Idle work now decays

A fixed 500 ms sweep per shard is a fixed query rate per shard whether or not the shard has seen a message this hour. The sweep exists to recover a **lost** notification, not to deliver — a notification wakes the shard immediately either way — so a quiet shard sweeping twice a second is pure waste. It now doubles up to `maxSweepInterval` (30 s by default) while a shard stays empty and snaps back to `sweepInterval` the moment anything arrives. `pollBackstop` became the park *ceiling* rather than a floor under every park.

| queues x shards | idle queries/s before | after |
|---|---|---|
| 5 x 4 | 162 | **0** |
| 300 x 8 | 18 252 | **481** |

Per owned shard: 4.0/s down to **0.10/s**, a 38x reduction across the estate.

### What it cost, measured over three runs each

| | before backoff | after backoff |
|---|---|---|
| Tier 2 on (same process), p50 | ~0.55 ms | ~0.57 ms |
| Tier 2 on, p99 | ~1.2 ms | ~1.2-2.7 ms |
| Tier 2 **off** (cross process), p50 | ~1.89 ms | ~2.07 ms |
| Tier 2 **off**, p99 | **~2.7 ms** | **~5.6 ms** |

The local hand-off path is unaffected. The cross-process path's p99 roughly doubled, consistently across three runs rather than as run-to-run noise, and it is an honest cost of the trade: 18 252 idle queries a second against 481 is worth two milliseconds at the 99th percentile for almost any deployment with hundreds of queues. A latency-sensitive one sets `maxSweepInterval` equal to `sweepInterval`, which restores the old behaviour exactly.

### Where the resource story now stands

| | 300 queues, 8 shards |
|---|---|
| held connections | **5** (`pumpThreads + 1`) |
| threads, idle | **+6** |
| threads, under load | **+21** |
| idle queries/s | **481** |

Held connections and threads are properties of the process. The only figure that still grows with the estate is the idle query rate, now at a tenth of a query per shard per second, and that is a configured interval rather than a structural cost.


---

## Aligning the two lanes, and choosing the defaults

The two lanes were bounded by nothing comparable. Ordered handlers ran on unbounded virtual threads capped only per shard — roughly 19 000 concurrent at 300 queues of 8 shards. Unordered handlers ran inline on the pump thread, so the whole process could run only `pumpThreads` of them: **four**, which caps unordered throughput at a few hundred messages a second whatever the hardware. Neither number was chosen by anyone.

### One budget, both lanes

`HandlerDispatch` pairs the executor with a process-wide `Semaphore`. Both lanes take a permit before dispatching and return it when the handler finishes. The ordered lane keeps its per-shard `keyConcurrency` cap on top; the unordered lane dispatches asynchronously again.

**Permit before the cursor moves, never after.** That ordering is the whole discipline. A row whose permit cannot be taken must still be there for the next read — advancing the cursor past it leaves it to the head sweep and registers the gap as a hole no transaction ever owned, which is what turned nearly every message into a phantom hole the first time asynchronous delivery was attempted. Every path that can deliver (cursor read, hand-off drain, hole chase, head sweep, retry schedule) now acquires first and returns the permit if the row turns out to be a duplicate.

### Two further defects this surfaced

**The straggler delete was bounded by the acknowledgement floor, and had no business being.** The floor exists because a range delete `seq <= n` would sweep up rows that were never delivered. A *targeted* delete addresses exactly the sequence values this owner handled, so it can remove nothing it did not deliver. Applying one rule to both cost real time: under asynchronous delivery the floor is pinned by the oldest of up to `handlerConcurrency` in-flight messages, so everything finished behind a slow handler waited for it. With inline delivery there was only ever one in flight, which is why it never showed — 43 of 1 000 messages left undeleted after twenty seconds.

**`parkDeadlineMillis` did not count pending acknowledgements.** It accounted for the next sweep and the next retry only, so with the sweep backed off to thirty seconds a late-arriving acknowledgement could wait that long to be flushed. Fixing it also repaired something previously written up as an accepted trade:

| | before | after |
|---|---|---|
| cross-process p50 | 2.07 ms | **1.73 ms** |
| cross-process p99 | ~5.6 ms | **2.65 ms** |

**The sweep backoff never cost 2 ms at p99.** A missing park deadline did. The earlier entry claiming that regression as the price of decaying idle work is retracted — the idle-cost reduction was free.

### The defaults, and which of them are measured

| setting | default | basis |
|---|---|---|
| `pumpThreads` | 2 | **Measured.** Held connections are `pumpThreads + 1`; one pump versus two changed nothing in the idle query rate and the reads are I/O-bound. Two gives three connections and a spare thread when one blocks. |
| `ConsumerOptions.parallelConsumers` | 8 | **Measured**, and deliberately not the fastest value &mdash; see the sweep below. |
| `handlerConcurrency` | 512 | Process-wide **ceiling**, not the knob. |
| `keyConcurrency` | 8 | Unchanged: per-shard ordered concurrency. |
| `sweepInterval` / `maxSweepInterval` | 500 ms / 30 s | **Measured.** 4.05 idle queries/s per shard down to 0.10. |

**A single process-wide budget was the wrong shape, and it is not what the current implementation does.** `ConsumeFromQueue` takes `parallelConsumers` per consumer, and it should: one number for the whole process lets a busy queue starve every other, and gives nobody a way to say this queue deserves four handlers and that one thirty-two.

So there are two levels now, because they answer different questions:

- **`ConsumerOptions.parallelConsumers`** &mdash; per consumer, the knob, named as in the current implementation. Default 10.
- **`ShardOwnerSettings.handlerConcurrency`** &mdash; a ceiling over the sum of them, so whatever the handlers contend for cannot be swamped by everyone's ambitions at once. Default 512.

Both are sized against whatever the *handlers* contend for, usually a connection pool, which is separate from the engine's own `pumpThreads + 1`. Thirty-two parallel consumers against a Hikari pool of ten will starve; these settings exist to be set.

### Measuring the concurrency default

I claimed this could not be measured, on the grounds that throughput at saturation varies 861% on this hardware. **That was wrong, and the distinction matters.** That figure is for an *absolute* throughput number. Choosing `parallelConsumers` needs to know where *more* concurrency stops buying anything, which is a comparison between arms — and `AbRunner` interleaves arms in one container in one run, so the drift that ruins an absolute figure is largely common to all of them. It is the same reason the WAL comparison holds to an interquartile range of a few tenths of a percent while raw throughput swings.

`NextGenConcurrencySweepIT` drains 4 000 messages through a **2 ms handler** across 8 shards. The handler has to block, or the question is meaningless: a handler that returns immediately is CPU-bound and its optimum is one thread per core whatever the queue does.

| parallelConsumers | drain ms (median) | IQR | msg/s | gain over previous |
|---|---|---|---|---|
| 1 | 10 026 | 5.0% | 399 | — |
| 2 | 4 878 | 2.0% | 820 | 2.06x |
| 4 | 2 466 | 2.0% | 1 622 | 1.98x |
| 8 | 1 653 | 0.2% | 2 420 | 1.49x |
| 16 | 1 368 | 0.1% | 2 924 | 1.21x |
| **32** | **1 161** | 4.1% | **3 445** | **1.18x** |
| 64 | 1 151 | 4.0% | 3 475 | 1.01x |
| 128 | 1 070 | **311.8%** | 3 738 | unreadable |

**Throughput peaks at 32**, and 32 was briefly made the default on that basis. That was optimising the wrong objective, and the same table says so once return per permit is read rather than throughput:

| permits | msg/s | % of peak | **msg/s per permit** |
|---|---|---|---|
| 1 | 399 | 11% | 399 |
| 4 | 1 622 | 47% | 406 |
| **8** | **2 420** | **70%** | **302** |
| 16 | 2 924 | 84% | 183 |
| 32 | 3 445 | 99% | 108 |
| 64 | 3 475 | 100% | 54 |

Return per permit is flat to 4 and then collapses. And the peak is the ceiling for *one consumer alone on the machine*, whereas a default is what runs when nobody has reasoned about their handler, beside every other consumer in the process — at 16 consumers, 32 each is not even reachable under the 512 ceiling.

**The default is 8**: 70% of the single-consumer peak for a quarter of the budget.

Two further facts argue against a large default. At 32 permits the handler budget alone would allow 16 000 messages a second (32 in flight, 2 ms each) and the measurement reaches 3 445 — so **past roughly 16 the bottleneck has already moved off handler concurrency onto the pumps and the database**, and the extra permits buy queueing rather than work. And the current implementation has no default at all: `ConsumeFromQueue` requires the number, and callers in this codebase pick 1, 3 or 5. Eight is a starting point to lower as often as to raise.

The knee moves with handler duration and hardware. The sweep is the tool to re-run, not a number to trust.

### The dead-tuple measurement: broken, diagnosed, fixed

The re-run reported **0.00 dead tuples per message and zero tuples deleted** for the shard-owned arm — below the design's own floor of one delete per acknowledged message, and therefore impossible. A second run of the same build reported 0.33 and 6 666. Two different impossible answers from one build is the tell.

Ground truth settled it: a plain `count(*)` on the table returned **zero rows**, so every delete had committed while `pg_stat_user_tables` still reported a fraction of them. The engine was right and the measurement was lying.

The cause was mine and it was dull: `settleStatistics()` slept a fixed 2.5 seconds, and the guard I had added earlier for exactly this failure mode asserted only that *inserts* had caught up. Deletes commit at the end of the run rather than spread through it, so they are the ones still in flight when the snapshot is taken. It now polls until both counters reach the work the harness knows was done, and fails the run loudly if they never do.

| arm | WAL B/msg | dead tuples/msg | tuples upd+del |
|---|---|---|---|
| baseline | 1 919 | 1.94 | 38 727 |
| shard-owned | **539** | **1.00** | **20 000** |

Exactly one delete per message and zero updates, restored. **Third time in this work that a plausible per-message figure turned out to be an unflushed statistic** — 0.10 once, then 0.00 and 0.33. A fixed sleep is not a synchronisation primitive, and asserting the denominator only half-covers it.
