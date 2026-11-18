# Shard-owned queue: fewer read statements per message

Status (2026-11-08): items 1(a) (`69da17c65`) and 3 (`cf2d69575`) are built. Item 1(b) is open, items 2 and 4 are
parked on `wip/sq-item2` and `wip/sq-item4`, item 5 is not started. The checkpoint further down has the detail.

## Evidence

Trading demo, one instance, ~71k orders, 8 queues, `pg_stat_statements` over one ~25 min run. About 65 queue
reads per order against 16 ordered enqueues per order, out of roughly 300 statements per order in total. The
machine is CPU-bound in bursts and PostgreSQL takes more of it than the application does.

## Where each statement comes from

| Statement (calls) | Caller | What triggers it |
|---|---|---|
| `count(*), min(visible_at) FILTER … shard_queue_ordered WHERE queue_id AND shard` (1 990 720) | `ShardOwnedStorage.orderedDepthOf`, from `PostgresqlMessageQueue.depth()` | `depth()` loops over **every ordered unit (64)** and every unordered shard. It is reached through the adapter's `getTotalMessagesQueuedFor` / `getQueuedMessageCountsFor`. In this run the caller was the load driver's diagnostic sampler (`--queue-sample-millis=1000`), which calls `GET /api/admin/load-report/queue-depths` once a second. That endpoint (`LoadRunReport.queueDepths()`) calls `depth()` for **every** queue in `getQueueNames()`: each inbox and each event or view processor's queue, about 20–30 in the demo, not only the 8 busy ones. See "Who called `depth()`" below. |
| `count(*) … shard_queue_unordered` (124 420) | `ShardOwnedStorage.depthOf`, same `depth()` call | Same caller. 1 990 720 / 124 420 = 16 = 64 units / 4 shards, which identifies the source: about 31 100 `depth()` calls, each issuing **69 statements** (64 + 4 + the dead-letter summary). |
| `unnest($1::int[], $2::bigint[]) … LATERAL` (1 791 258, 178 s, the costliest statement in the database) | `ShardPump.batchReadQueue` → `readOrderedFromCursors` | One per queue per pump pass in which at least 2 of that queue's units are attentive. |
| `… seq > $3 AND visible_at <= now() ORDER BY seq LIMIT` (601 546) | `OrderedShardOwner.readAhead` / `pumpOnce` → `readOrderedFromCursor` | The same read for a pass in which only 1 unit of the queue is attentive. |
| `unnest($1::int[]) … LATERAL` + `GROUP BY shard` min(visible_at) (192 228 each) | `batchReadQueue` → `sweepOrderedFromHeads` + `millisUntilNextVisibleByShard` | The head sweep for batched units whose `sweepInterval` is due (500 ms, doubling to 30 s only while nothing is delivered). |
| per-unit sweep + `EXTRACT(min(visible_at) - now())` (190 328 each) | `OrderedShardOwner.sweep` | The same sweep for a unit read on its own. |
| unordered `count(*)` / `SELECT seq, payload` (124 420 / 117 079) | `depth()` / `ShardOwner` | Depth as above; the unordered lane's own reads are small. |
| `SELECT pg_current_xact_id()` (885 322) | `ShardOwnedStorage.assignTransactionIdBeforeAllocating` | Once per enqueuing transaction. Required for the watermark: see `ShardOwnedWatermarkXidWindowIT`. |

### Who called `depth()` in this run

About 31 100 calls over ~25 minutes, roughly 21 per second.

- **Load-driver sampler: essentially all of them.** 1 sample/s × N queues × run length. With N ≈ 21 and about
  1 480 s, that alone is 31 100. This is diagnostic instrumentation in the demo harness, not framework behaviour.
- `DurableQueuesMicrometerInterceptor`: **0**. Its bean exists only with `management.tracing.enabled=true`,
  and the run used the profiles `compose,simulators`.
- The shard-owned starter's depth and health gauges (`MicrometerQueueObserver`): **0**. They are opt-in
  (`depthGauges` / `healthGauges`), cached for `gaugeMaxAge`, and computed only on a scrape. OTLP export was
  off, so nothing scraped.
- `DurableQueuesHealthIndicator`: only on `/actuator/health`. The driver calls it only in fault-injection
  runs (`watchOutage`).
- `Inbox.getNumberOfUndeliveredMessages` via `BankStatements` / `ClearingStatements.catchUp`: polls every
  100 ms while one of those two inboxes is non-empty, once per closed business day. Tens to hundreds of calls,
  well under 1 %.
- `AbstractEventProcessor` awaitProcessed (only on timeout), `ViewEventProcessor` start-up log (once), and
  `QueueLoadGenerator.status()` (only its own admin endpoint): negligible.

To confirm, in phase 2: N is the number of keys in one `queue-depths` response. The `deadLetterSummary`
statement's `pg_stat_statements` call count equals the number of `depth()` calls.

So the ~2.15M depth statements were the sampler, and item 1 below is a cost fix for **any** depth caller
(admin API, health, gauges, inbox polling, the classic interceptor) rather than the demo's hot path. Excluding
the sampler, the demo's real queue reads are about 35 per order, not 65.

### Why a read happens far more often than an enqueue

There is no timed poll on the hot path. LISTEN/NOTIFY and the in-JVM wake-up (`ShardRuntime.wakeIfServedHere`,
`ShardWakeupNotifier`) are already in place, and an idle queue costs a few statements per second
(`ShardOwnedOrderedIdleCostIT`). The reads are driven by **pump passes**, and every ordered pass reads from the
cursor, whatever the reason it was scheduled. `OrderedShardOwner.needsAttention()` selects a unit when any of
these holds:

1. its `ShardWakeup` was signalled. That covers an enqueue, but also **every handler that returns**
   (`runHandler`'s `finally`: "Capacity may have freed up"). The flag cannot tell the two apart;
2. acknowledgements are pending and `ackFlushInterval` (default **1 ms**) has passed, which is true right after
   every handler returns;
3. a watermark candidate is unretired and `chaseDelay` (default **2 ms**) has passed. Under steady load
   writers are almost always in flight, so this keeps re-selecting units;
4. rows are buffered in `readyByKey` and a key slot is free, or a retry is due, or the sweep is due.

Only 1, when caused by an enqueue, and 3 can surface new rows. Reasons 2 and 4, and a handler-return wake-up,
cannot, yet each still pays a full cursor read. That read also starts at `safeCursor` (the watermark), not at
`maxSeen`, so under load it returns rows the owner already holds (`seen` drops them) together with their
payloads. That is where the 178 s comes from. In short: about one read per message **enqueued**, plus about one
per message **handled**, plus chase and flush passes, give the ~34 ordered cursor reads per order.

When idle, a queue costs the backed-off sweep per queue (batched) and the pump's full scan, as measured and
gated by the cost ITs. Under load, reads follow handler completions, ack flushes and watermark chases, and the
sweep stays at 500 ms because every delivery resets its backoff.

## Proposed reductions, ranked

| # | Change | Saves (statements/order, estimated) | Risk |
|---|---|---|---|
| 1 | **Cheaper depth.** (a) `PostgresqlMessageQueue.depth()` counts each lane with one queue-wide statement (`… WHERE queue_id = ?`) instead of one per shard or unit: 69 → 3 statements, with the same result. (b) Optional and separate: `DurableQueuesMicrometerInterceptor` stops recounting the queue after every operation and reads a max-age, single-flight cache instead (the pattern `MicrometerQueueObserver.CachedDepth` already uses). | In this run ≈ 29 per order, but only because of the sampler: (a) makes each `depth()` about 23× cheaper for every caller. (b) had no effect in this run (the interceptor was off). It matters to any application with tracing on, where today every enqueue costs a full count on all three `DurableQueues` implementations. | Low. (a) is internal. (b) changes when a gauge value is computed but not what it reports, and needs a release note. |
| 2 | **No cursor read on a pass that cannot find new rows.** Split "rows may have arrived" from "work to do". Handler return, ack-flush due, retry due and a free key slot set a dispatch-only reason. The cursor read (batched or single) runs only on an enqueue wake-up, a chase that is due, a due sweep or `nextVisible`, a takeover, or when **the previous read was capped** (`readReachedSeq != READ_NOT_CAPPED`, since more rows are known to exist). `BatchReadableOwner` gets `batchReadWanted()`, and `readAhead` and `horizonProbeWanted` honour it. All of this is package-private. | ≈ 12–20 of the ~34, and most of the 178 s execution time | Medium. The watermark rules are untouched: no read means no `advanceWatermark`, and the probe is only consumed by a pass that read. The hazard is a reason wrongly classified as dispatch-only, which turns into latency (the sweep still recovers it) rather than loss. Pinned by the watermark, order and lost-wake-up ITs. |
| 3 | **Fold the sweep's `millisUntilNextVisible` into the sweep statement** (per unit and batched), one statement instead of two. | ≈ 5 | Low–medium: a new statement shape, so measure the near-idle plan as the module's CLAUDE.md requires. |
| 4 | **Sweep backoff under load.** Reset the sweep interval to base only when the sweep itself recovered a row, not on every delivery, so a unit the cursor keeps up with backs off. Keep the post-takeover `holeExpiry` pin. | ≈ 2–4 | Medium: the sweep is the lost-notification and delayed-message safety net. `nextVisibleAt` covers delayed messages, the backstop covers lost NOTIFYs. |
| 5 | *Later, not in this round:* read only `(maxSeen, …]` with payloads plus a payload-free `seq` list for `(safeCursor, maxSeen]`; fold `pg_current_xact_id()` into the INSERT (`… FROM (SELECT pg_current_xact_id()) x`, xid before `nextval`); raise the 1 ms `ackFlushInterval` default. | 5: bytes, not statements; xid: ≈ 12; ack: up to ≈ 10 | Higher: watermark and xid-window correctness, and redelivery after a crash. Each needs its own measurement. |

Expected for the demo hot path (2 + 3): about **17–25 fewer statements per order**, against about 35 real queue
reads per order once the sampler is excluded, plus most of the cursor read's execution time. Item 1(a) removes
another ≈ 29 per order from this run's totals, but those come from the diagnostic sampler. In production it
pays off wherever depth is polled: admin API, health, gauges, inbox catch-up. Every change is general-purpose.
The demo is the evidence, not the target.

## How it will be measured (phase 2)

- Branch vs `main`, each from its own git worktree under `/tmp`, never `/workspace`, with a fresh table per
  shape, `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64`.
- Statement counts: `ShardOwnedOrderedLoadedCostIT` (read statements, horizon probes and ack flushes per
  delivered message, plus owner-passes), `ShardOwnedOrderedIdleCostIT` and `ShardOwnedMultiQueueCostIT` (idle
  must not regress), and `ShardOwnedUnorderedReadCostIT`.
- Latency and throughput: the module's `-Dbenchmark.run=true` suites (`ShardOwnedRoutingSpaceCostIT`,
  `ShardOwnedStalledKeyCostIT`) and `DurableQueueBenchmarkScenario` (performance lab), with p50/p99 delivery
  latency reported alongside statements per message, because #2 and #4 trade wake-ups for latency.
- Correctness gates: `ShardOwnedOrderedWatermarkIT`, `ShardOwnedWatermarkXidWindowIT`,
  `ShardOwnedConcurrentProducersOrderIT`, the rebalance, shed and lifecycle ITs, and the adapter's
  `DurableQueues` contract ITs. For #1(b): `DurableQueuesMicrometerInterceptor` tests on all three
  implementations.
- Demo confirmation: a repeat `pg_stat_statements` run with `reset-stack.sh` beforehand, compared per order.

## Status (checkpoint 2026-11-03)

Measured against the commit before each item, not `main`: `main`'s copy of this module is ~178 files behind the
branch. Worktrees are under `/tmp/sq-*` (`sq-base`, `sq-item1`, `sq-item3`, `sq-item2`). Scripts are in
`/tmp/claude-1000`: `runit.sh` runs one IT class in a worktree, `measure.sh` runs alternating A/B rounds,
`extract.sh` pulls the figures out of the logs. Logs are `/tmp/claude-1000/*.log`.

- **Item 1(a): done.** Committed on `feature/trading-demo-expansion` as `69da17c65`, with release note §2.13 and a
  module CLAUDE.md bullet. `ShardOwnedDepthCostIT`, 200 reads per backlog:

  | Backlog per lane | Statements per read | p50 | p99 |
  |---|---|---|---|
  | empty | 69 → 3 | 3.2 → 0.24 ms | 6.4 → 0.57 ms |
  | 10 000 | 69 → 3 | 5.2 → 1.6 ms | 8.3 → 1.9 ms |
  | 100 000 | 69 → 3 | 22.3 → 13.6 ms | 27.1 → 19.1 ms |

  Engine depth/admin ITs and the adapter's count and inbox/outbox ITs pass.
- **Item 3: done.** Committed on `feature/trading-demo-expansion` with release note §2.14 and a module CLAUDE.md
  bullet (also on `wip/sq-item3`, `8be6d3a66`). `ShardOwnedBatchedReadIT`, `ShardOwnedOrderedReadPlanIT`,
  `ShardOwnedOrderedWatermarkIT`, `ShardOwnedDelayedDeliveryIT` and `ShardOwnedPumpScheduleIT` pass (19 tests). The
  near-idle plan was checked on a table vacuumed while drained: each `UNION ALL` branch plans exactly as the
  statement it replaced. Loaded/idle/multi-queue cost ITs, 3 alternating
  rounds against `69da17c65`; round 1 overlapped a unit-test run:

  | Measure | Before | After |
  |---|---|---|
  | Read statements per message | 3.82–3.98 | 3.34–3.67 |
  | Transactions per message | 7.40–7.63 | 6.97–7.52 |
  | Idle ordered queue, statements per 3 s | 90 | 62–68 |
  | p50 | 2.9 ms | 2.8–2.9 ms |
  | p99 | 4.5–7.2 ms | 4.3–5.1 ms |

  Multi-queue idle cost is unchanged (~0.04–0.05 queries/s per owned unit).
- **Item 2: parked (user's call, 2026-11-03).** Kept on `wip/sq-item2`, not committed to the feature branch. Revisit
  only if the demo rerun shows dispatch-only passes are a large share. The branch holds `f535f2708` plus a WIP commit
  with a fix: `readWanted` is now cleared after the read statement, not before, so a read
  that throws does not swallow the wake-up. A/B against item 3, 3 rounds (`m2-*` logs):

  | Per delivered message | Item 3 | Item 2 |
  |---|---|---|
  | Read statements | 2.91 / 3.41 / 3.58 | 3.03 / 3.04 / 3.23 |
  | Transactions | 6.00 / 6.92 / 7.32 | 6.52 / 6.50 / 6.87 |
  | Owner-passes | 3.72 / 3.78 / 3.76 | 3.05 / 2.99 / 3.19 |

  Base round 1 is an outlier (p99 127 ms). Without it, about 10% fewer reads and 5% fewer transactions. Latency and
  idle cost are unchanged. 374 unit tests pass, as do the rebalance, lifecycle, concurrent-producer, xid-window,
  wake-up, pump-schedule, delayed and batched-read ITs. Five `ShardOwnedOrderedWatermarkIT` tests fail. They drive
  `pumpOnce` by hand without `needsAttention()` and with no wake-up for the rows they commit, so passes 2 and up skip
  the read. The production pump always selects first (`ShardPump` ~620 → ~684), so this is a contract change the
  tests encode rather than an observed loss. Adapting them means modelling selection and enqueue wake-ups in the
  guard test. Also found: `nextAttentionNanos` does not report "now" for a capped read.

Next steps:
1. The team lead reruns the demo (items 1 and 3), with `reset-stack.sh` beforehand and `pg_stat_statements`
   compared per order.
2. Item 2: only if that run shows many passes that are only dispatching. Adapt the watermark IT passes to model the
   pump's selection and enqueue wake-ups, check they still fail without the watermark fixes, make
   `nextAttentionNanos` return now after a capped read, then rerun the A/B.
