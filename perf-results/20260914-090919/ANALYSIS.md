# Perf run 20260914-090919 — analysis

The first `scripts/perf-host.sh` run recorded in `perf-results/`. It is the **real-machine run** that
[`docs/durable-queue-measurements.md` §7.1](../../docs/durable-queue-measurements.md) exists to make
possible: a macOS host where the JVM runs natively and PostgreSQL runs in the Docker Desktop VM, so
the two sit in separate scheduling domains instead of sharing the devcontainer's eight-CPU cgroup.

Every figure below is derived from the files in this directory. Nothing here has been folded into
`docs/durable-queue-measurements.md` yet — §"What to do with this run" at the end says what should be.

---

## 1. Run identity

| | |
|---|---|
| Timestamp | 2026-09-14 09:09:19 |
| Commit | `8344471e`, clean tree |
| Host | Darwin, 14 cores, 36 GB |
| Docker allocation | 8 CPUs, 15 GB |
| PostgreSQL | 17.5, `shared_buffers=1GB`, `synchronous_commit=on`, `fsync=on`, `full_page_writes=on`, `wal_level=replica`, `wal_compression=off` |
| JVM | Temurin 25.0.2, aarch64, 9 GB max heap |
| CPU partitioning | **no** — no `taskset` on macOS, so the arms are not on disjoint cores |
| Soak | 10 minutes per arm at 600 msg/s |

Suites executed, all passing: `ShardOwnedVsBaselineCostIT` (2 tests), `ShardOwnedLatencyIT`,
`ShardOwnedConcurrencySweepIT`, `ShardOwnedSoakIT`. `build.log` is empty because the build step runs
`mvnw -q` and succeeded.

**Not executed:** `EngineResourceComparisonIT`, `DurableQueueBaselineProfilesIT`, and the capacity
sweep. That matters for §6 — the specific test whose throughput column is marked unquotable in the
docs was not part of this run, so this run does not retire that warning directly.

---

## 2. Headline: the lab held still

This is the result the run was for. The devcontainer's defect is that repeated identical runs disagree
by up to 861%. On this host:

| Suite | Spread measure | Result |
|---|---|---|
| Concurrency sweep | IQR across 8 arms | **0.0 – 3.9%** |
| Cost, WAL bytes/msg | IQR, 3 reps per arm | baseline **1.1%**, shard-owned **3.1%** |
| Cost, shard-owned throughput | IQR, 3 reps | **10.2%** (24 420 / 26 076 / 29 762 msg/s) |
| Soak, messages handled | per 30 s window, 40 windows | **exactly 18 000 in every window, both arms** |

The concurrency sweep is the strongest evidence: eight arms, every one of them tight, including arms
that are plainly saturated. Compare the same shape measured in the devcontainer (docs §3.7), where the
one-shard arm came back at 74% IQR.

The 10.2% on shard-owned throughput is the loosest number in the run and is still an order of
magnitude better than the 78% the devcontainer produced for a throughput arm. It is not tight enough
to quote to three digits, but it is tight enough to say the engine moves ~26 000 msg/s in this
storage-layer configuration, which the devcontainer never was.

---

## 3. Per-message cost — `cost.summary.txt`, `json/nextgen-vs-baseline.json`

20 000 messages × 3 interleaved repetitions, 200-byte payloads.

| Metric | Baseline | Shard-owned | Change |
|---|---|---|---|
| WAL bytes per message (median) | 1 907 | **541** | **−71.6%** |
| WAL IQR | 1.1% | 3.1% | — |
| Dead tuples per message | 1.96 | **1.00** | −49% |
| `tup_upd + tup_del` over the run | 39 283 | 20 000 | claim write absent |
| Row updates (`n_tup_upd`) per run | 19 062 – 20 559 | **43 – 343** | — |
| Commits per message (`xact_commit`/msgs) | 1.04 – 1.08 | **0.16 – 0.20** | −84% |

**This reproduces the devcontainer figures almost exactly** — docs §1 records 1 904 → 538, −72%, dead
tuples 1.98 → 1.00. Two independent environments agreeing to within 0.2% is what the docs' claim that
cost metrics are the *reliable* half of the lab predicted, and this run confirms it rather than
revising it.

The three shard-owned repetitions show `n_tup_upd` of 43, 43 and 343 rather than a clean zero. Docs
§3.1 states it is "zero in every arm". The counter is database-wide, so these are almost certainly
catalog or autovacuum-driven updates rather than queue rows — but it is worth confirming before the
"zero" wording is repeated, because this is the first run where it is not literally zero.

### 3.1 Payload-size sweep — `json/payload-size-sweep.json`

16 MB per run held constant across sizes, 3 repetitions per arm per size.

| Payload | Messages | Baseline WAL B/msg | Shard-owned WAL B/msg | Difference | As % | TOASTed |
|---|---|---|---|---|---|---|
| 200 B | 2 000 | 1 895.8 | 539.6 | **1 356 B** | −71.5% | no |
| 1 800 B | 2 000 | 3 511.6 | 2 144.9 | **1 367 B** | −38.9% | no |
| 8 000 B | 2 000 | 10 491.3 | 9 340.8 | **1 151 B** | −11.0% | yes |
| 64 000 B | 200 | 71 985.8 | 70 835.8 | **1 150 B** | −1.6% | yes |

Within a few bytes of the 2026-09-13 devcontainer sweep in docs §3.4.3 (1 352 / 1 366 / 1 153 / 1 148).
The constant-saving finding is now reproduced on different hardware, which is the strongest form the
claim can take: the advantage is **~1 150–1 370 bytes per message**, and the percentage is a statement
about payload size, not about the design.

The step down at the TOAST threshold — ~1 360 B to ~1 150 B — reproduces too, at the same place.

---

## 4. Latency — `latency.summary.txt`

2 000 messages at 500/s. "Response" is measured from the intended schedule slot, "service" from when
the producer actually sent; a gap between them is the producer stalling, not the engine delivering
slowly.

| Configuration | p50 | p90 | p99 | max |
|---|---|---|---|---|
| Tier 2 on — response | 0.91 ms | 1.14 ms | **17.38 ms** | 33.02 ms |
| Tier 2 on — service | 0.67 ms | 0.88 ms | 1.56 ms | 21.09 ms |
| Tier 2 off — response | 2.72 ms | 3.11 ms | 6.69 ms | 9.94 ms |
| Tier 2 off — service | 2.53 ms | 2.91 ms | 4.71 ms | 9.71 ms |
| Baseline, 20 ms poll (Phase 0, devcontainer) | 20.7 ms | — | 27.1 ms | — |

Engine metrics from `latency.log` for the Tier 2 arms: `delivered=2000`, `holesObserved=0`,
`orderViolations=0`, `handlerFailures=0`, `deadLettered=0`, `backstopPolls=0`,
`localHandoffs=2000`, `readsSkippedByHandoff=2000`, `cursorReadsPerMessage≈1.99`,
`ackFlushesPerMessage=1.0`. Clean deliveries, no fallback path taken.

**Two things to take from this table, and one not to.**

**Both tiers are roughly 1.6× slower at p50 than in the devcontainer** — Tier 2 0.91 ms against the
documented 0.54 ms, Tier 1 2.72 ms against 1.76 ms. The likely cause is structural rather than a
regression: enqueue-to-handler includes the enqueue commit, and on macOS every round trip to
PostgreSQL crosses the Docker Desktop VM boundary, which the devcontainer's shared-kernel setup does
not pay. That is an inference from the environment, not something this run measures — it would be
confirmed by measuring raw round-trip time to the database on both hosts.

**The Tier 2 p99 of 17.38 ms is a producer stall, not the engine.** Service-time p99 for the same
messages is 1.56 ms, an 11× gap; the response figure includes time the producer spent not sending.
Docs §3.2 records exactly this failure mode being chased once before and found to be a harness
artefact. **Do not quote 17.38 ms as a latency result, and do not quote 0.91 / 17.38 as a pair** —
they come from the same run but only one of them describes the engine.

The consequence is that this run does **not** produce a quotable response-time p99 for Tier 2. Service
time p99 1.56 ms is the sound figure here.

---

## 5. Concurrency sweep — `concurrency.summary.txt`

4 000 messages, 2 ms handler, 8 shards.

| parallelConsumers | Drain ms (median) | IQR | msg/s | Speed-up vs 1 | msg/s per consumer |
|---|---|---|---|---|---|
| 1 | 10 393 | 0.5% | 385 | 1.00× | 385 |
| 2 | 5 262 | 1.0% | 760 | 1.97× | 380 |
| 4 | 2 657 | 2.0% | 1 505 | 3.91× | 376 |
| **8** | 1 715 | 2.7% | 2 332 | 6.06× | 292 |
| 16 | 1 411 | 3.9% | 2 835 | 7.36× | 177 |
| 32 | 1 207 | 0.0% | 3 314 | 8.61× | 104 |
| 64 | 1 109 | 0.8% | **3 607** | 9.37× | 56 |
| 128 | 1 109 | 0.8% | **3 607** | 9.37× | 28 |

**Scaling is near-linear to 4 and then bends.** 1 → 4 returns 3.91× of a possible 4×; 4 → 8 returns
1.55× of a possible 2×; beyond 32 it returns essentially nothing, and 64 → 128 returns exactly
nothing — identical medians to the millisecond.

**The plateau sits where the shard count and the handler put it.** 8 shards at a 2 ms handler implies
4 000 msg/s if a shard delivers serially, and the measured plateau of 3 607 is 90% of that. The
arithmetic is suggestive rather than proven — this run varies only `parallelConsumers`, so attributing
the ceiling to shard count would need a second sweep varying `shardCount` at fixed consumers to
confirm. If it holds, the practical rule is that raising `parallelConsumers` past `shardCount` buys
little, which is a more useful statement than "pick the knee".

> **Retracted by run [`20260914-103550`](../20260914-103550/ANALYSIS.md) §4.** The same 128-consumer
> arm, same commit, same machine, returned 22 727 msg/s — 5.7× the supposed 4 000/s ceiling — at a
> 44.2% interquartile range. The ceiling reading above does not hold, and the 128 arm's value should
> not be quoted from either run until it is confirmed to be measuring a real drain.

**Read the knee as a cost curve, not a peak.** 8 consumers deliver 65% of peak throughput; 16 deliver
79%; 32 deliver 92% at four times the consumers of the 8-consumer arm. Per-consumer return has already
fallen 24% by 8 and 85% by 64.

---

## 6. Soak — `soak.summary.txt`, `json/soak.json`

10 minutes per arm at 600 msg/s, sampled every 30 seconds, 20 windows per arm. **Both arms handled
exactly 18 000 messages in every single window** — neither fell behind at any point, so all latency
below is a property of the design rather than of queue depth.

Reported by quarter, following the docs' convention of not reading endpoints:

| p50, median per quarter | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| Baseline | 12 591 µs | 13 311 µs | 13 351 µs | 14 191 µs |
| Shard-owned | 998 µs | 1 007 µs | 971 µs | **866 µs** |

| p99, median per quarter | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| Baseline | 27 759 µs | 28 111 µs | 28 111 µs | 28 591 µs |
| Shard-owned | 1 282 µs | 1 251 µs | 1 859 µs | 1 567 µs |

| | Baseline | Shard-owned | Ratio |
|---|---|---|---|
| Steady-state p50 (windows 10–19) | 13 699 µs | 867 µs | **15.8×** |
| Steady-state p99 (windows 10–19) | 28 351 µs | 1 608 µs | **17.6×** |
| Table size, first → last | 15 784 → 37 384 KB | 4 920 → 14 560 KB | 2.57× smaller |
| **Index size, first → last** | 2 384 → **11 824 KB** | 464 → **1 400 KB** | **8.4× smaller** |
| Dead tuples outstanding | sawtooth 11 630 – 48 427 | sawtooth 5 718 – 24 118 | ~2× fewer |
| Autovacuum runs | 10 | 10 | — |

**The `-56%` p99 drift in `soak.summary.txt` is an artefact of the first window and should not be
quoted.** Baseline window 0 reports 64 991 µs; every other window sits between 27 343 and 29 359. The
summary computes drift as first → last, so one cold window turns a flat series into a 56% improvement.
By quarter the baseline p99 is flat and its p50 rises 13%, which is the honest reading. This is the
same endpoint-arithmetic trap docs §3.5 flagged in the opposite direction, and the summary generator
still falls into it.

**The shard-owned engine is the arm that gets faster.** Its p50 drops 998 → 866 µs across the
quarters, a 13% improvement that plateaus, while its p99 rises from 1 282 to a 1 567–1 859 µs band.
Both movements are sub-millisecond and both settle; the docs' description of a settling period rather
than accumulation fits this run too.

**The index gap widened.** 8.4× here against the 4.7× the 30-minute devcontainer soak recorded — but
this run is at double the rate for a third of the duration, so the two are not directly comparable and
the number should not be quoted as a trend.

**At double the documented soak rate the baseline did not degrade.** Docs §3.5 ran 300/s and found no
p99 drift; this ran 600/s and found none either, at 360 000 messages per arm and ten autovacuum
cycles. That is now three attempts to observe the predicted dead-tuple degradation and three failures
to find it. It remains a structural cost difference, which is measured, and not a predicted
degradation.

---

## 7. What must not be quoted from this run

**Baseline throughput, and therefore any throughput ratio.** Every baseline arm lands on ~1 000 msg/s —
20 000 msgs in 20 049 ms, 2 000 in 2 054 ms, 2 000 in 2 062 ms — which is the `20 consumers × 50
polls/s` ceiling the docs describe in §2.2, not a database limit. Setting that against the
shard-owned arm's 26 076 msg/s yields a 26× figure that describes a poll interval, not an engine.

**Tier 2 response-time p99 (17.38 ms) and max (33.02 ms).** Producer stall; see §4.

**The soak summary's drift line.** Both arms; see §6.

**Anything absolute across environments.** Latency is 1.6× slower here than in the devcontainer, and
index ratios differ; only the cost metrics reproduced closely enough to treat as environment-independent.

---

## 8. What to do with this run

1. **Record that the reproducibility gap is closed for the sweep, and only for the sweep.** Docs §7.1
   says "if the IQRs come back tight on a workstation and wide here, the lab was the problem". They came
   back tight — 0.0–3.9% on eight arms. But `EngineResourceComparisonIT`, whose throughput column
   carries the explicit "must not be quoted" warning, was not in this run. Re-running it on this host
   is the single highest-value follow-up, and it is what would let docs §3.6 and §4 be rewritten.
2. **Promote the payload sweep from one measurement to a reproduced one.** The constant saving now
   holds on two hosts to within ~15 bytes across a 320-fold payload range.
3. **Fix the summary generator's drift line** to report quarters rather than endpoints, or to drop the
   first window. It has now produced a misleading number in both directions on two different runs.
4. **Check the non-zero `n_tup_upd`** (43 / 43 / 343) before repeating the "zero in every arm" claim.
5. **Consider a `shardCount` sweep at fixed `parallelConsumers`** to confirm or refute the reading in
   §5 that the 3 607 msg/s plateau is set by shard count rather than by consumer count.
6. **Fix or document the producer stall in the latency harness**, which currently costs this suite its
   response-time p99 — the figure docs §1 quotes as the headline latency number.
