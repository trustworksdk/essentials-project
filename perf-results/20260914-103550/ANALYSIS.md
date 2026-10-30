# Perf run 20260914-103550 — analysis

Second host run, same machine and same commit as [`20260914-090919`](../20260914-090919/ANALYSIS.md),
with the `resources` suite (`EngineResourceComparisonIT`) added — the suite that run omitted, and the
one the whole exercise was for.

**This run answers the question that motivated it, and the answer is no.**

| | |
|---|---|
| Timestamp | 2026-09-14 10:35:50 |
| Commit | `8344471e`, **tree dirty** |
| Dirty because | `scripts/perf-host.sh` gained the `resources` suite. No engine or lab source changed — the measured code is identical to run 1 |
| Host / Docker / JVM / PostgreSQL | unchanged from run 1 |
| Soak | 10 minutes per arm at 600 msg/s |

All five suites passed. The earlier `No space left on device` failure was a full Docker Desktop
virtual disk, cleared before this run by pruning ~12 GB of orphaned `localhost/testcontainers/*`
images and unused volumes.

---

## 1. The headline: the reproducibility gap is not closed

`docs/durable-queue-measurements.md` §7.1 states the hypothesis plainly — if the interquartile ranges
come back tight on a workstation and wide in the devcontainer, "the lab was the problem and those
figures can start being quoted absolutely." The suite that carries the unquotable column now has a
host measurement:

| Arm | msg/s | IQR | slowest..fastest | Devcontainer (docs §3.6) |
|---|---|---|---|---|
| baseline, 20 ms, 20 consumers | 992 | 1.0% | 973..994 | 996, 0.4% |
| baseline, 20 ms, 40 consumers | 1 974 | 0.4% | 1 963..1 979 | 1 980, 0.3% |
| baseline, 5 ms, 20 consumers | 3 724 | **14.7%** | 2 774..3 868 | 3 915, **0.1%** |
| **shard-owned** | 9 337 | **76.8%** | **3 968..18 315** | 3 767, **78.2%** |

**76.8% on the workstation against 78.2% in the devcontainer.** The spread did not shrink. Three
identical repetitions produced 3 968 and 18 315 msg/s — one run 4.6× another, worse in absolute terms
than the devcontainer's 3 745..9 634.

The devcontainer's shared eight-CPU cgroup is therefore **not** the cause, or at least not the whole
cause. That was the standing explanation in docs §4 and the premise of §7.1, and this run refutes it
for this arm. The suite's own guard fired correctly:

```
!! shard-owned throughput spread is 77% — DO NOT quote this figure.
```

**The 5 ms baseline arm got worse too** — 0.1% IQR in the devcontainer, 14.7% here. So the host is not
uniformly the better lab, and "run it on a workstation" is not the fix anyone assumed it was.

### Where this does not generalise

Saturation alone does not explain it. The cost suite's shard-owned arm is also saturated — 22 396 msg/s,
20 000 messages in ~890 ms — and came in at **7.5% IQR** across three repetitions in this same run.
The concurrency sweep's arms are mostly under 5%. So the instability is not "any saturated arm"; it is
specific to `EngineResourceComparisonIT`'s shard-owned arm and to the sweep's top arm (§4).

What distinguishes that arm from the stable one: it runs interleaved *after* three baseline arms in the
same JVM against one 120-connection Hikari pool, where the cost suite runs two arms. Whether that is
the cause is not established by this run — it is the next thing to test, and it is a cheaper experiment
than buying hardware.

### What the suite did deliver

The throughput column is unquotable; the rest of the table is the suite's actual point and it
reproduced cleanly:

| Arm | Threads | Held connections | Devcontainer |
|---|---|---|---|
| baseline, 20 ms, 20 consumers | 51 | 20 | 51 / 18 |
| baseline, 20 ms, 40 consumers | 70 | **41** | 70 / 31 |
| baseline, 5 ms, 20 consumers | 36 | 21 | 36 / 17 |
| **shard-owned** | 36 | **7** | 36 / 7 |

Thread counts are identical to the devcontainer's. Doubling the baseline's consumers cost **21 more
connections** (20 → 41), and the shard-owned engine held **7** regardless — `pumpThreads + 1` per
process. Both in-test assertions passed, so the *mechanism* is measured rather than asserted. This is
the honest headline of the comparison and it does not depend on the broken throughput column.

---

## 2. Per-message cost — reproduced to within 0.1%

| Metric | Run 1 (09:09) | Run 2 (10:35) |
|---|---|---|
| Baseline WAL B/msg | 1 907 (IQR 1.1%) | **1 907** (IQR 1.0%) |
| Shard-owned WAL B/msg | 541 (IQR 3.1%) | **542** (IQR 3.0%) |
| Change | −71.6% | **−71.6%** |
| Dead tuples per message | 1.96 → 1.00 | **1.95 → 1.00** |
| `tupUpd + tupDel` | 39 283 → 20 000 | 39 061 → 20 000 |

Payload sweep, likewise:

| Payload | Baseline WAL | Shard-owned WAL | Difference | Run 1 difference |
|---|---|---|---|---|
| 200 B | 1 893 | 540 | 1 353 B | 1 356 B |
| 1 800 B | 3 510 | 2 146 | 1 364 B | 1 367 B |
| 8 000 B | 10 491 | 9 340 | 1 151 B | 1 151 B |
| 64 000 B | 71 978 | 70 836 | 1 142 B | 1 150 B |

**Three independent measurements now agree** — two hosts and the devcontainer — on both the per-message
cost and the constant-saving shape. This is the part of the lab that works, and it works everywhere.

**The "zero row updates" claim needs revising.** `n_tup_upd` for the shard-owned arm was 879 / 243 / 43
in this run and 43 / 43 / 343 in run 1. Docs §3.1 says "zero in every arm". It is a database-wide
counter, so these are plausibly catalog or autovacuum writes rather than queue rows — but it is not
zero on either host, and the wording should either be qualified or the counter scoped to the queue
table before it is repeated.

---

## 3. Latency — degraded against run 1, and not only at the tail

| Configuration | Run 2 p50 | p90 | p99 | max | Run 1 p99 |
|---|---|---|---|---|---|
| Tier 2 on — response | 0.88 ms | 1.15 ms | 19.86 ms | 42.85 ms | 17.38 ms |
| Tier 2 on — service | 0.66 ms | 0.85 ms | 1.74 ms | 42.56 ms | 1.56 ms |
| Tier 2 off — response | 2.69 ms | **11.61 ms** | **40.45 ms** | 54.02 ms | 6.69 ms |
| Tier 2 off — service | 2.48 ms | **5.12 ms** | **14.92 ms** | 46.82 ms | 4.71 ms |

**p50 is stable across both runs** — 0.88 vs 0.91 ms, 2.69 vs 2.72 ms. The tails are not.

**Tier 1 (Tier 2 off) is the one that moved, and it moved for real.** Its p90 went 3.11 → 11.61 ms and
its p99 6.69 → 40.45 ms. Unlike the Tier 2 tail, this is *not* explained away by producer stall: the
service-time figures rose with it, p99 4.71 → 14.92 ms, and service time excludes the producer. Two
runs of the same commit on the same machine an hour apart, and the NOTIFY path's p99 differs 6×.

Nothing in this run identifies the cause. It is the same class of problem as §1 — this lab does not
hold a tail figure still either — and it means **neither run's Tier 1 p99 should be quoted**. The p50
figures are the sound ones.

Engine metrics in `latency.log` are clean in both runs: `holesObserved=0`, `orderViolations=0`,
`backstopPolls=0`, `deadLettered=0`, all 2 000 delivered.

---

## 4. Concurrency sweep — the top arm broke

| parallelConsumers | Run 2 drain ms | IQR | msg/s | Run 1 msg/s |
|---|---|---|---|---|
| 1 | 10 416 | 3.7% | 384 | 385 |
| 2 | 5 225 | 0.4% | 766 | 760 |
| 4 | 2 653 | 0.1% | 1 508 | 1 505 |
| 8 | 1 712 | 0.5% | 2 336 | 2 332 |
| 16 | 1 409 | 4.0% | 2 839 | 2 835 |
| 32 | 1 207 | 0.5% | 3 314 | 3 314 |
| 64 | 1 103 | 0.6% | 3 626 | 3 607 |
| **128** | **176** | **44.2%** | **22 727** | **3 607** |

**Arms 1 through 64 reproduced to within 0.5%.** That is a genuinely tight sweep and the strongest
stability result in either run.

**The 128 arm did not.** 22 727 msg/s against run 1's 3 607 — a 6.3× disagreement between two runs of
the same commit. Arm-start timestamps in `concurrency.log` show why the IQR is 44%: repetitions 1 and 2
completed in ~230 ms wall clock, repetition 3 in ~1 209 ms.

**This retracts a reading in run 1's analysis.** That document noted the plateau at 3 607 msg/s was
consistent with `8 shards × (1/2 ms) = 4 000/s`, flagged as inference pending a `shardCount` sweep. Run
2 exceeds that supposed ceiling 5.7× on the same configuration, so the ceiling reading is wrong, or the
128 arm is not measuring a drain at all. Do not quote either figure for the 128 arm.

Unlike `EngineResourceComparisonIT`, this suite prints its IQR but has **no "DO NOT quote" guard** above
a threshold. Given it just produced a 44% arm, it should have one.

---

## 5. Soak — reproduced

10 minutes per arm at 600 msg/s. Both arms held the rate in every window; shard-owned handled exactly
18 000 or 18 001 per window, baseline 17 986–18 014.

| | Run 2 | Run 1 |
|---|---|---|
| Steady-state p50 (windows 10–19), baseline | 12 967 µs | 13 699 µs |
| Steady-state p50, shard-owned | 869 µs | 867 µs |
| **p50 ratio** | **14.9×** | 15.8× |
| Steady-state p99, baseline | 28 271 µs | 28 351 µs |
| Steady-state p99, shard-owned | 1 385 µs | 1 608 µs |
| **p99 ratio** | **20.4×** | 17.6× |
| Index, first → last, baseline | 2 752 → 12 512 KB | 2 384 → 11 824 KB |
| Index, first → last, shard-owned | 464 → 1 352 KB | 464 → 1 400 KB |
| **Index ratio** | **9.3×** | 8.4× |
| Autovacuum cycles | 10 / 10 | 10 / 10 |

Same shape as run 1 throughout. Two details:

**Baseline window 0 is cold again** (31 151 µs p99 against 27 743–29 903 for every other window), and
the summary's drift line still computes first → last. It reads `-10%` here rather than run 1's
misleading `-56%`, because this run's cold window was less extreme — the arithmetic is just as wrong,
it simply got a kinder input. **The generator still needs fixing.**

**Shard-owned window 13 spiked to 11 815 µs p99** against 1 235–1 510 everywhere else. A single window,
no neighbour elevated, storage flat across it — consistent with the isolated windows docs §3.5 records
on both arms and attributes to container noise. Not a trend, but it is the third such spike across
three soaks and they are always single windows.

**The baseline still shows no p99 drift at 600/s.** Fourth attempt, fourth negative. Docs §3.5's
position — structural cost difference, measured; predicted degradation, not observed — holds.

---

## 6. Harness bug found and fixed

`resources.summary.txt` came out **empty** despite the suite passing and printing its table. The suite
opened with a three-`=` banner and printed no closing banner; `perf-host.sh` extracts each table by
toggling on `/=====/`, so it matched nothing.

That was a defect in the line that added the suite to the script — the suite was wired in without
checking its output matched the convention the other four follow. Fixed in
`EngineResourceComparisonIT.java` by bracketing the table with five-`=` banners like every other suite,
with a comment naming the coupling so it is not re-broken. Module recompiles clean.

The table itself is intact in `resources.log`; §1 above is read from there. Re-running is not necessary
to recover this run's data — but the *next* run is the first one whose summary file will be populated.

---

## 7. What to do

1. **Stop attributing the throughput instability to the devcontainer.** Two hosts, 78% and 77%. Docs §4
   and §7.1 both state or imply the cgroup is the cause; this run is evidence against that and should
   be recorded before anyone spends money on hardware.
2. **Test the interleaving hypothesis** — the stable saturated arm runs 2 arms per JVM, the unstable one
   runs 4 against a 120-connection pool. Run `EngineResourceComparisonIT` with the shard-owned arm
   alone and see whether the spread collapses. Cheap, and it either finds the cause or eliminates the
   most likely one.
3. **Add a spread guard to `ShardOwnedConcurrencySweepIT`**, matching the one
   `EngineResourceComparisonIT` already prints above 25%. It just emitted a 44% arm with no warning.
4. **Investigate the 128-consumer arm** — 176 ms to drain 4 000 messages with a 2 ms handler needs
   confirming as a real drain before the arm is trusted at any value.
5. **Fix the soak summary's drift line** to use quarters or drop the cold first window. Carried over
   from run 1; it has now produced a wrong number in two runs running.
6. **Qualify or re-scope the "zero row updates" claim** in docs §3.1 — non-zero on both hosts.
7. **Promote what did reproduce.** Per-message cost, the constant WAL saving across payload sizes, the
   connection and thread counts, and the soak ratios are now measured on two hosts plus the
   devcontainer and agree closely. Those are quotable; the throughput and tail-latency figures are not.
