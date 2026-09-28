# Durable Queue Measurements — history

How the shard-owned engine's measured numbers got to where they are: the reworks that moved them, the
conclusions that were later retracted, and the measurement bugs found along the way. Kept because the
retractions are the useful part — several plausible figures here turned out to be artefacts of the
harness rather than properties of the engine, and the same traps are easy to fall into again.

**Nothing in this file is a current result.** Current numbers, each stamped with the date it was
measured, are in [`../durable-queue-measurements.md`](../durable-queue-measurements.md). Where an
entry below contradicts that document, that document is right.

Entries are in the order they happened.

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

`ShardOwnedCostDecompositionIT` reported 20 007 handler invocations for 20 000 messages, and the latency benchmark 2 001 for 2 000. Not the equality assertion being wrong — a real race, and it had a specific cause.

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

- **The listener.** `ShardWakeupListener` listens on one global channel and the payload already carries `queueId:lane:shard`. One listener can demultiplex for every queue and both lanes. It was per-queue only because it was constructed inside `ShardOwnedQueue` — fifty connections at twenty-five queues doing the work of one.
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

**The per-queue fallback is a footgun and behaved like one.** A `ShardOwnedQueue` built without a runtime stands one up for itself, which is right for a single queue and wrong for a hundred: the first run at 100 queues created 100 runtimes and exhausted a 500-connection pool. The gate now asserts that held connections do not scale with queue count, so that mistake cannot pass again.


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

`HandlerDispatch` pairs the executor with a process-wide `Semaphore`. Both lanes take a permit before dispatching and return it when the handler finishes. *(That process-wide semaphore was removed later — see the entry below on the defaults. The per-consumer permits remain.)* The ordered lane keeps its per-shard `keyConcurrency` cap on top; the unordered lane dispatches asynchronously again.

**Permit before the cursor moves, never after.** That ordering is the whole discipline. A row whose permit cannot be taken must still be there for the next read — advancing the cursor past it leaves it to the head sweep and registers the gap as a hole no transaction ever owned, which is what turned nearly every message into a phantom hole the first time asynchronous delivery was attempted. Every path that can deliver (cursor read, hand-off drain, hole chase, head sweep, retry schedule) now acquires first and returns the permit if the row turns out to be a duplicate.

### Two further defects this surfaced

**The straggler delete was bounded by the acknowledgement floor, and had no business being.** The floor exists because a range delete `seq <= n` would sweep up rows that were never delivered. A *targeted* delete addresses exactly the sequence values this owner handled, so it can remove nothing it did not deliver. Applying one rule to both cost real time: under asynchronous delivery the floor is pinned by the oldest in-flight message, so everything finished behind a slow handler waited for it. With inline delivery there was only ever one in flight, which is why it never showed — 43 of 1 000 messages left undeleted after twenty seconds.

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
| `keyConcurrency` | 8 | Unchanged: per-shard ordered concurrency. |
| `sweepInterval` / `maxSweepInterval` | 500 ms / 30 s | **Measured.** 4.05 idle queries/s per shard down to 0.10. |

**A single process-wide budget was the wrong shape, and it is not what the current implementation does.** `ConsumeFromQueue` takes `parallelConsumers` per consumer, and it should: one number for the whole process lets a busy queue starve every other, and gives nobody a way to say this queue deserves four handlers and that one thirty-two.

So there are two levels now, because they answer different questions:

- **`ConsumerOptions.parallelConsumers`** &mdash; per consumer, the knob, named as in the current implementation. *(This line said "Default 10" and was simply wrong; the default was and is 8, as the table above it says.)*
- A process-wide ceiling (`handlerConcurrency`, default 512) existed alongside it and has since been removed: it was never measured, and at 512 it could not bind before any plausible shared resource was exhausted.

Both are sized against whatever the *handlers* contend for, usually a connection pool, which is separate from the engine's own `pumpThreads + 1`. Thirty-two parallel consumers against a Hikari pool of ten will starve; these settings exist to be set.

### Measuring the concurrency default

I claimed this could not be measured, on the grounds that throughput at saturation varies 861% on this hardware. **That was wrong, and the distinction matters.** That figure is for an *absolute* throughput number. Choosing `parallelConsumers` needs to know where *more* concurrency stops buying anything, which is a comparison between arms — and `AbRunner` interleaves arms in one container in one run, so the drift that ruins an absolute figure is largely common to all of them. It is the same reason the WAL comparison holds to an interquartile range of a few tenths of a percent while raw throughput swings.

`ShardOwnedConcurrencySweepIT` drains 4 000 messages through a **2 ms handler** across 8 shards. The handler has to block, or the question is meaningless: a handler that returns immediately is CPU-bound and its optimum is one thread per core whatever the queue does.

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

Return per permit is flat to 4 and then collapses. And the peak is the ceiling for *one consumer alone on the machine*, whereas a default is what runs when nobody has reasoned about their handler, beside every other consumer in the process — at 16 consumers, 32 each was not even reachable under the 512 ceiling that then existed.

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


---

## The devcontainer was not the reason throughput would not hold still

Every throughput figure in this work has been marked comparative-only since the beginning, and the
reason given was always the same: the devcontainer runs `dockerd` inside itself, so the load generator
and PostgreSQL share one eight-CPU cgroup while both size their pools for the 14 CPUs `nproc` reports.
Any run that saturates CPU is throttled, the explanation went, and a machine without that defect would
hold a number still. `scripts/perf-host.sh` exists to test exactly that.

It was tested on 2026-09-14, twice, on a macOS workstation where the JVM runs natively and PostgreSQL
runs in the Docker Desktop VM — separate scheduling domains, no shared cgroup. `EngineResourceComparisonIT`'s
shard-owned arm returned a **76.8%** interquartile range, against **78.2%** in the devcontainer. Three
identical repetitions produced 3 968 and 18 315 msg/s; the devcontainer's worst pair was 3 745 and
9 634. The spread did not shrink and the absolute range widened.

**The explanation was never a measurement.** It was a plausible mechanism that fitted the symptom, and
it went unchallenged for as long as there was no host to check it against. It is now ruled out as a
sufficient cause. The cgroup mismatch is still real and still worth removing — it simply is not what
moves this figure.

Three facts from the same session bound whatever the real cause is, and the third is the useful one:

- Per-message cost reproduced across two hosts and the devcontainer to within **0.1%**, and the
  payload-size sweep's constant saving to within about 15 bytes across a 320-fold payload range.
- The concurrency sweep's arms 1 through 64 reproduced between the two host runs to within **0.5%**.
- `ShardOwnedVsBaselineCostIT`'s shard-owned arm is **equally saturated** — 20 000 messages in about
  890 ms, 22 396 msg/s — and returned a **7.5%** interquartile range in the same session.

So it is not "saturation is unmeasurable here". One saturated arm is stable and another is not, in the
same JVM on the same machine minutes apart. The structural difference between them is that the unstable
suite interleaves four arms in one JVM against a 120-connection pool where the stable one runs two.
That is the next experiment — run the shard-owned arm alone — and it is far cheaper than the hardware
the old explanation kept pointing at.

**Two smaller results from the same runs, both negative.**

The concurrency sweep's 128-consumer arm returned 3 607 msg/s in the first host run and **22 727** in
the second, at a 44.2% interquartile range, having "drained" 4 000 messages in 176 ms with a 2 ms
handler. The first run's analysis had read the 3 607 plateau as the `8 shards × (1/2 ms) = 4 000/s`
ceiling; the second exceeds that 5.7× on the same configuration, so the reading is retracted and the
arm is not trusted at either value until it is confirmed to be measuring a real drain. That suite prints
its interquartile range but, unlike `EngineResourceComparisonIT`, has no "do not quote" guard above a
threshold — it should.

Tier 1's latency tail did the same thing: response p99 6.69 ms in one run and 40.45 ms in the other,
with service-time p99 moving 4.71 → 14.92 ms alongside it. That rules out the producer-stall artefact
that explains Tier 2's response tail, since service time excludes the producer. The p50s were stable
across both runs to within 0.03 ms. Quote the p50s.

**And a harness bug, in the usual place.** The suite was added to `scripts/perf-host.sh` without
checking that its output matched the convention the other four follow: it opened its table with a
three-`=` banner and printed no closing one, while the script extracts each table by toggling on
`/=====/`. The suite passed, printed its table to the log, and wrote an empty summary file. Fixed by
bracketing the table properly, with a comment in the test naming the coupling. **Fourth time in this
work that a result was lost or wrong because of the harness rather than the engine** — after the three
unflushed-statistic episodes above.
