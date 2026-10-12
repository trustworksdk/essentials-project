# Durable Queues Improvement Plan

Follow-up to `docs/Durable Queues Port Report.md`. Every claim in the report was checked against the 0.60.0 source, and the stale-statistics claim was reproduced on PostgreSQL 17.11. This plan turns the verified findings into fixes. For each fix it says how to investigate or measure it first. It also covers three questions the report did not: how to cut the connection cost of `SingleOperationTransactionDurableQueuesInterceptor`, whether the polling optimizer should be on by default, and what the shard-owned implementation should fix or could lend to the classic queue.

Compatibility rule for every item: central APIs change only additively in a minor release. A changed default, such as turning on the optimizer, is a behaviour change. It goes in the release notes and keeps an opt-out.

## Verification status of the report

| Report item | Status after verification |
| --- | --- |
| 1. Claim by primary key | Reproduced for the unordered claim, but only when the table was **drained and then analyzed** (`reltuples = 0`, `relpages > 0`). A table analyzed when brand new and empty plans fine. The rewrite does little for the ordered claim (see A2). |
| 2. Fixed-rate polling cap | Confirmed in code: `CentralizedMessageFetcher.java:158`, with slots capped by `calculateAvailableWorkerSlotsPerQueue`. |
| 3. Empty queues polled at full rate | Confirmed for the builder without a `MultiTableChangeListener`. The Spring starter always creates a listener, so Spring users already have the optimizer. (An earlier version of this plan said each poll also ran the stuck-message reset. That was wrong: the method is called on every poll, but it runs its SQL at most once per `messageHandlingTimeout` per queue.) |
| 4. Virtual threads | The code claim is correct, but the likely bottleneck is connections, not threads (see D). |
| 5. `getDeliveryMode()` | Confirmed: `DefaultQueuedMessage.java:111`. |
| 6. Dead-letter stack trace, `minimumIdle` | Confirmed: `UnitOfWorkFactory.java:124` wraps the exception, and nothing overrides HikariCP's `minimumIdle`. |

Stale-statistics reproduction, unordered claim, 20 000 rows queued:

| Variant | limit 128 | limit 8 | fresh statistics |
| --- | --- | --- | --- |
| Current `FROM cte u WHERE q.id = u.id AND q.queue_name = …` | 185 ms | 17 ms | 1.4 ms |
| Drop the `queue_name` re-check only | 192 ms | 20 ms | 0.9 ms |
| `WHERE q.id IN (SELECT id FROM cte)` | 187 ms | 19 ms | 1.4 ms |
| `WHERE q.id = ANY(ARRAY(SELECT id FROM cte))` | **4.2 ms** | **3.0 ms** | 0.9 ms |

Ordered claim under the same conditions: 1 026 ms with the current form, 827 ms with `ANY(ARRAY(…))`.

## Progress (branch `queue_improvements`)

| Item | Status |
| --- | --- |
| A1 | Done for the unordered **and** ordered outer `UPDATE`. `PostgresqlDurableQueuesClaimPlanIT` recreates the stale statistics, includes a control that runs the legacy SQL and must show the bad plan, and asserts that neither claim joins on `id`. Confirmed to fail against the old SQL. The batched statement, still marked work in progress, is unchanged. |
| A4, B1 note | Done: `LLM/LLM-postgresql-queue.md` and the module README. |
| E1 | Done. Fixing it exposed a latent crash: the resurrect log line in `PostgresqlDurableQueues` cast the `QueuedMessage` itself to `OrderedMessage`. It never ran, because the mode was always `NORMAL`. With E1 alone, every resurrect of an ordered message would have thrown `ClassCastException`. Both are fixed. |
| E2 | Done: `DeliveryErrorDescription.describe(Throwable)`. A wrapped throwable is stored as a one-line `Root cause: …` summary followed by the unchanged full stack trace. An unwrapped throwable is stored exactly as before. Applies to retries, dead letters and enqueue-as-dead-letter, in both the PostgreSQL and MongoDB queues. |
| F1 | Done with option 2, plus two findings from implementing it. First, a clean handover (the previous owner released the shard) now charges nothing at all, which matters more than the narrow window. Second, a message that crashes its owner never *fails*, so the takeover itself now dead-letters rows its charges have exhausted; without that, the bump protected nothing. `ShardOwnedTakeoverAttemptsIT` covers it, and each of its four guards was checked by mutating it out. Implementing it also turned up an acquire statement that reported every takeover as unclean (a CTE read from `RETURNING` sees nothing) and a `long` fence truncated to `int` at start-up. Both are fixed. |
| F2 | Done, but narrower than the review said. Checked exceptions were already wrapped by `PostgresqlMessageQueue.deliver`. What escaped uncounted were non-critical `Error`s. Owners now catch `RuntimeException \| Error` and rethrow critical errors. |
| F3 | Done: `scheduleRetryIfOwned` and `moveToDeadLetterIfOwned`. The unfenced methods remain for pull sessions and the operator's admin move. |
| F4 | Done: a new SPI outcome, `spi.RedeliveryRequested(delay)`, which the adapter's `markForRedeliveryIn` uses. The requested delay is honoured, the attempt counts, and the request never dead-letters the message, matching the classic queue. |

`ShardOwnedFailurePathIT` covers F2–F4 (6 tests). All 6 fail against the previous code.

### Second round

| Item | Status |
| --- | --- |
| B1 | Done. A queue whose poll filled every free slot is polled again, for that queue only, as soon as half its workers are free. `PostgresqlDurableQueuesSaturationIT` (2 000 messages, 8 consumers, 200 ms polling): 49 948 ms before, which is the predicted 50 000 ms ceiling, and 3 288 ms after. |
| C1 | Done. The centralized fetcher always uses a `CentralizedQueuePollingOptimizer`, capped at 10× the polling interval without a listener and 20× with one. Opt out with `QueuePollingOptimizer.None()`. Idle queue at 20 ms polling: 28 queue-table scans in 3 s, against about 300 claim statements before. |
| C2 | Done. A message queued by this node resets its consumer's backoff in the enqueuing unit of work's `afterCommit`. |
| A2 | Done: option (a), applied per queue. On its own, the head scan regressed many-key queues (11.6 ms at 2 000 keys, 114 ms at 20 000, against 1.6 ms), so each queue switches to it only after a slow claim that came back short, and back after a slow head scan. 20 keys × 1 000: 180 ms → 0.4 ms. Parity is tested, with a mutation check, and the ordered-message suites run against both statements. |
| C3 | Open. |
| C4 | Partial fill done: the unordered claim fills whatever the ordered claim left. The stuck-reset item is void, since it was already throttled. Skipping the ordered claim on queues without ordered messages is open. |
| New: UoW callbacks | C2's test exposed a long-standing bug. `GenericHandleAwareUnitOfWork` (behind `JdbiUnitOfWorkFactory` and `EventStoreManagedUnitOfWorkFactory`) closed its handle, which clears the registered callbacks, before calling `afterCommit`/`afterRollback`, so neither ever ran. Spring's factory was unaffected. Fixed by taking a snapshot before `close()`; `GenericHandleAwareUnitOfWorkLifecycleTest` covers it. This switches on callbacks that never ran outside Spring (async snapshots, aggregate repositories), so it is the headline behaviour change in the 0.60.1 notes. |


## Workstream A — Claim SQL (classic `postgresql-queue`)

### A1. Unordered claim: update by primary-key array

Replace the join in `DurableQueuesSql.buildUnorderedSqlStatement()` with `WHERE q.id = ANY(ARRAY(SELECT id FROM cte_unordered))` and drop the `queue_name` re-check. The `ARRAY(…)` sub-select runs exactly once, so the locked id set is fixed before the update runs. A concurrent check (6 claimers, 4 000 rows) produced 4 000 distinct claims and no double claims.

Investigate and guard:
- Add an integration test that reproduces the plan. Fill the table, delete everything, `ANALYZE`, fill again with autovacuum disabled on the table, then assert with `EXPLAIN (FORMAT JSON)` that the update side contains no `Nested Loop` with a `Join Filter` on `id`. Asserting on the plan is more robust than asserting on timing.
- Check `fetchNextBatchOfMessagesBatched` and the MSSQL queue for the same join shape.

### A2. Ordered claim: the `NOT EXISTS` barrier

Under stale statistics the per-key barrier (`NOT EXISTS … q2.key_order < q.key_order`) becomes a nested loop that discards 2.56 M rows for 20 000 messages. The A1 rewrite removes only the outer-join share of that.

Investigate:
- EXPLAIN the barrier with fresh and with stale statistics, at 2 000 keys × 10 and at 20 keys × 1 000.
- Candidate rewrites:
  1. A `LATERAL … LIMIT 1` probe of `idx_<table>_ordered_msg` per candidate. Shard-owned already uses this pattern to force a stable nested loop with an index probe (`ShardOwnedStorage.java:1150-1179`).
  2. `DISTINCT ON (key) … ORDER BY key, key_order` to select each key's head first, then filter that head by readiness.
- Accept a rewrite only if it is no slower with fresh statistics and is bounded with stale ones. Guard it with the same plan-shape integration test as A1.

**A2 measurements (second round).** Full claim statement, 20 000 ordered messages, limit 128, PostgreSQL 17:

| Barrier | 2 000 keys × 10, stale | 2 000 keys, fresh | 20 keys × 1 000, stale | 20 keys, fresh |
| --- | --- | --- | --- | --- |
| Current `NOT EXISTS (earlier key_order)` | 1.6 ms | 1.4 ms | 179 ms | **180 ms** |
| `key_order = (SELECT min(key_order) …)` | 44 ms | 43 ms | 196 ms | 211 ms |
| `(key, key_order) IN (SELECT key, min(key_order) … GROUP BY key)` | 7.9 ms | 7.3 ms | 5.7 ms | 3.2 ms |

Findings:
- **The worst case is long keys, not stale statistics.** With 20 keys there are only 20 eligible heads, so the limit is never reached. The claim walks every candidate with one index probe each (20 000 probes, 180 ms) on every poll while such a backlog exists, even with fresh statistics. Few keys with many messages each is a typical event-processor shape, and B1 polls a busy queue more often.
- The stale-statistics case is state-dependent. The first reproduction (no column statistics yet) took about 1 s; with column statistics from an earlier `ANALYZE` it took 1.6 ms. The statement's real problem is plan instability.
- `IN … GROUP BY key` is bounded in every state measured, but it costs 5× more in the common many-short-keys case. It also aggregates every ordered row in the queue, so it grows with the backlog.
- `min()` and `NOT EXISTS … OFFSET 0` are rejected: no better, or worse.

Options, to decide before implementing:
1. A recursive "loose index scan" that visits each key's head through `idx_<table>_ordered_msg`: cost proportional to the number of keys, independent of messages per key.
2. Pick per poll: the current statement, falling back to `IN … GROUP BY` when the previous claim on the queue returned fewer rows than its limit.
3. Accept it, document it, and steer long-key workloads to the shard-owned engine, which has no claim.

### A3. Index choice inside the claim's select

Even after A1, the claim's inner select under stale statistics scans `idx_<table>_ordered_msg` and reads all 20 000 rows, instead of using the partial index `idx_<table>_unordered_ready`. That accounts for the remaining 3–4 ms. Investigate whether the partial index's predicate fails to match because the query writes `= FALSE` where the index says `NOT …`. If so, aligning the predicate text is a cheap win. Measure with the A1 test setup.

### A4. Operational guidance

Document that a queue table which drains to empty is exposed to stale statistics, and that per-table autovacuum analyze settings help (`autovacuum_analyze_scale_factor = 0`, a small `autovacuum_analyze_threshold`). This is a stopgap until A1 and A2 ship, not a replacement for them.

## Workstream B — Fetcher scheduling

### B1. Poll again when a poll fills every free slot

Replace `scheduleAtFixedRate` in `CentralizedMessageFetcher` with a self-scheduling loop:
- After a poll, if any queue got as many messages as it had free slots, schedule the next poll as soon as a worker finishes. The worker's `finally` block, which already decrements `activeWorkers`, signals the fetcher.
- Otherwise wait the polling interval.
- Coalesce wake-ups so that a burst of handler completions triggers one poll, not one each.

Shard-owned already does this (`ShardPump` loops while it delivers, and a handler completion signals the wakeup).

Investigate first:
- Write a throughput test with a no-op handler, 8 consumers, 20 ms polling, offering 1 000 msg/s. Today it should plateau at about 400 msg/s, the arithmetic ceiling.
- Keep the test as a regression guard in the opt-in benchmark suite (`-Dbenchmark.run=true`).
- Check that the Bug #19 guard (`Math.max(0, …)`, never over-fetch) still holds. Refilling on a freed slot fetches only what is free, so it should.

Until B1 ships, document the `parallelConsumers / pollingInterval` ceiling next to `parallelConsumers`.

## Workstream C — Idle polling cost and the polling optimizer

### C1. Turn the centralized polling optimizer on by default in the builder

Today `PostgresqlDurableQueues.java:719-720` chooses `QueuePollingOptimizer.None()` when there is no `MultiTableChangeListener`. Spring is unaffected because the starter always creates the listener bean, with a 2 s backoff cap. Plain-builder users poll every queue every 20 ms forever.

The catch: without LISTEN/NOTIFY, nothing resets the backoff when another node enqueues a message. The worst-case extra latency equals the cap, which the builder's factory sets at `pollingInterval × 20` (400 ms at 20 ms polling). Proposal:
- Use `CentralizedQueuePollingOptimizer` whether or not a listener exists.
- Without a listener, cap the backoff lower, for example 10 × the polling interval (200 ms). That cuts idle claims by roughly 10× and adds at most 200 ms to the first message after an idle period.
- Make the cap a builder setting, and allow `None()` as an explicit opt-out.

Investigate:
- Count idle statements per second per queue with `pg_stat_statements`, before and after.
- Measure first-message latency after 1 minute idle, with the message enqueued from another node and from the same node.

### C2. Reset backoff on a local enqueue

`messageAdded` is only ever called from the LISTEN/NOTIFY path (`PostgresqlDurableQueues.java:565,576`). An enqueue in the same JVM should reset that queue's optimizer after commit, which removes the C1 latency penalty for local producers. A `UnitOfWork` after-commit callback is the natural hook.

### C3. Delayed and retried messages

A retry with a delay sends a notification when the row is updated, not when the message becomes due. With backoff at its cap, a due message can wait up to the cap. Two options:
- When a poll returns nothing, let the optimizer cap its next delay at the earliest `next_delivery_ts` in the queue. Shard-owned computes this "next visible" interval on the server.
- Accept the latency and document it.

Investigate: retry with a 5 s delay on an idle queue, then measure how late the redelivery is compared with the requested delay.

### C4. Fewer statements per poll

- **Stuck-message reset:** `resetMessagesStuckBeingDeliveredAcrossMultipleQueues` runs on every poll. It only needs to run about once per `messageHandlingTimeout / N`. Rate-limit it.
- **Two claim statements:** the ordered claim runs first and returns 0 rows on queues that never get ordered messages. Options:
  - Remember per queue that no ordered message has been seen. Reset that flag on an enqueue with a key and after any resurrect or retry of a keyed message, and re-check every N polls to stay safe across nodes.
  - Merge both claims into one statement. This is harder and depends on A2.
- **Partial fill:** if the ordered claim returns fewer rows than there are free slots, the unordered claim is skipped that poll, so unordered messages on mixed queues are partly starved. Run the unordered claim for the remaining slots.

Measure: `pg_stat_statements` calls and total time per statement at 0, 100 and 5 000 msg/s on 10 queues, before and after.

## Workstream D — Handler transactions and connections

### Why this matters

`SingleOperationTransactionDurableQueuesInterceptor.intercept(HandleQueuedMessage)` wraps every handler in `withUnitOfWork`. `GenericHandleAwareUnitOfWork.start()` (`GenericHandleAwareUnitOfWorkFactory.java:134`) opens a JDBI handle and begins a transaction immediately, and holds it for the whole handler. On success, the fetcher then acknowledges the message in a second unit of work, with a second connection checkout and a second commit.

With 128 consumers and a 10-connection pool, about 118 handlers are waiting on HikariCP at any moment. That fits the report's drain figures better than thread overhead does. Moving to virtual threads alone (report item 4) would just make more threads wait on the same 10 connections.

### D0. Measure before changing anything

- Rerun the drain benchmark (10 queues × 128 consumers, pool 10) with HikariCP metrics on: `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds` and `hikaricp_connections_usage_seconds`.
- Repeat with pool sizes 10, 32 and 140. If throughput scales with pool size, connections are the bottleneck.
- Check whether the Rust no-op handler ran inside a transaction. If it did not, the report's 6.6k vs 16.9k msg/s comparison is not like for like.
- Profile the drain with JFR (allocation, lock contention, `jdk.ThreadPark`) to put the CPU cost on JSON mapping, unit-of-work setup or thread hand-off.

### D1. Acknowledge inside the handler's unit of work

Move the success-path acknowledgement into the same unit of work as the handler, as the last step of the interceptor chain. Because nested `withUnitOfWork` joins the existing unit of work, `acknowledgeMessageAsHandled` joins the handler's transaction.

Effects:
- One connection checkout and one commit per message instead of two.
- The handler's database side effects and the acknowledgement become atomic. Today a crash between the commit and the acknowledgement redelivers a message whose effects have already been committed.

Constraints:
- `isManuallyMarkedForRedelivery()` must still retry rather than acknowledge.
- Handlers with `UnitOfWorkBoundaryOwningQueuedMessageHandler` (`UnitOfWorkMode.NONE`) keep the separate acknowledgement.
- Retry and dead-letter must stay in their own unit of work, because the handler's transaction has rolled back by then.
- Spring-managed units of work (`SpringTransactionAwareJdbiUnitOfWorkFactory`) need the same behaviour. Test both factories.

Investigate:
- An integration test that kills the handler thread between the handler's commit and the acknowledgement, and asserts that it is no longer possible to commit the effect without the acknowledgement.
- Benchmark the drain before and after.

### D2. Acquire the connection lazily

Make `GenericHandleAwareUnitOfWork` open its handle on the first `handle()` call instead of in `start()`. A handler that never touches the database, or touches it only at the end after a slow external call, would then hold no connection while it waits. This is the general form of what `UnitOfWorkMode.NONE` asks handlers to do by hand.

Risks:
- Code that relies on `start()` beginning a transaction, for example lifecycle callbacks that expect an open handle.
- The Spring factories, which take the connection from the `DataSourceTransactionManager`. `LazyConnectionDataSourceProxy` is Spring's equivalent. Shard-owned's `ShardRuntime.java:72` documents why it rejects that proxy, so read that first.

Treat D2 as an investigation spike: list every caller of `handle()` and the lifecycle callbacks, then prototype it behind a flag.

### D3. Check connection budget at startup

Shard-owned's `PoolBudget` refuses, at startup, a pool that its pumps would exhaust. The classic queue could warn (not fail, since that would be a behaviour change) when the sum of `parallelConsumers` across consumers, plus the fetcher and the listener, exceeds the pool's maximum size. It cannot always read the pool size. Use `HikariDataSource` when available and skip the check otherwise.

### D4. Virtual threads for handlers

Default the worker pool to virtual threads with a semaphore of `parallelConsumers` permits, as shard-owned does (`ShardRuntime.java:205`). Users can already pass their own executor through `ConsumeFromQueue.consumerExecutorService`, so a benchmark needs no code change. Do D0 and D1 first, so the effect of virtual threads is not hidden behind connection waits. Thread-local unit-of-work state must still work, and it should because each handler runs on its own virtual thread.

## Workstream E — Small fixes

- **E1. `getDeliveryMode()`**: return `IN_ORDER` when `getMessage()` is an `OrderedMessage`, or carry the row's `delivery_mode` through. Update the unit test. The Rust interop test that pins the current behaviour has to change at the same time.
- **E2. Readable dead-letter errors**: when storing `last_delivery_error`, unwrap `UnitOfWorkException`, or store the root cause's summary first, followed by the full trace. Keep the full chain so nothing is lost.
- **E3. HikariCP `minimumIdle`**: document a lower `minimumIdle` for services that only consume queues. Do not change any default.

## Workstream F — Shard-owned implementation

Shard-owned already avoids most of the report's weaknesses:
- It has no claim join.
- It refills a slot as soon as a handler finishes.
- It wakes on LISTEN/NOTIFY, with a backstop poll and a backing-off sweep.
- It runs handlers on virtual threads.
- It wraps no unit of work around handlers.
- It acknowledges in batches.
- It reports `getDeliveryMode()` correctly.

The review did find the following. F1–F3 were confirmed by reading the code; the rest come from the code review and should be confirmed by the test listed with each.

### Correctness (fix first)

- **F1. Attempt count is lost on takeover.** The delivery read returns only `(seq, payload, payload_type)` (`ShardOwnedStorage.java:1001-1006`). Attempts are counted only in memory (`ShardOwner.java:110,579`), so they restart at 1 under a new owner. `scheduleRetry` then overwrites the database value with that lower count. As a result, `bumpAttemptsOnTakeover` (`ShardOwnedStorage.java:853-859`), which exists to stop a JVM-crashing poison message from looping forever, achieves nothing. Every rebalance also grants fresh attempts.
  - Test: fail a message N−1 times, force a takeover, and assert that the next failure dead-letters it.
  - Fix options. The delivery read must not grow: `MessageHandler`'s javadoc keeps `attempts` out of the hot `SELECT` on purpose. So read the stored count on the failure path, which may be slow, the first time a message fails under an owner. The open question is what the stored count means. `bumpAttemptsOnTakeover` adds 1 to **every** unacknowledged row of the shard, so once the count is honoured, each rebalance costs every backlog message an attempt. With the adapter's default budget, a message that waits through two rolling deploys and then fails once would be dead-lettered.
    1. **Honour the count as it is.** Poison messages that crash the JVM are stopped, but rebalances consume attempts.
    2. **Honour the count, and bump only rows the previous owner could have had in flight.** That is the first `parallelConsumers` visible rows above the acknowledged floor, plus rows with a scheduled retry. Rebalances become nearly free; the cost is that the bump becomes a heuristic.
    3. **Count takeovers in a separate column.** Exact, but it is a schema change: an additive `ALTER TABLE … ADD COLUMN IF NOT EXISTS` through `ShardOwnedSchema`.
- **F2. Throwables outside `RuntimeException` are never counted.** `runHandler` catches only `RuntimeException` (`ShardOwner.java:542`). An `Error` or a sneaky-thrown checked exception frees the slot with no attempt recorded, so the message loops with no dead-letter.
  - Fix: route `Throwable` to `onFailure`, then rethrow critical errors (`Exceptions.rethrowIfCriticalError`, as the classic fetcher does).
  - Check `OrderedShardOwner` for the same pattern.
- **F3. Failure-path writes are not fenced.** `scheduleRetry` and `moveToDeadLetter` (`ShardOwnedStorage.java:~1356-1410`) have no `stillOwnedClause`, unlike acknowledgements. An owner that has lost its lease can delay or dead-letter a row that the new owner is delivering.
  - Fix: apply the fence clause and treat 0 rows updated as "no longer owner".
  - Test: inject a failure after a fence bump.
- **F4. Manual redelivery loses the delay and counts as a failure.** In `adapter/ShardOwnedDurableQueueConsumer`, `ManualRedeliveryRequested` drops the requested delay and records a failed attempt, so `markForRedeliveryIn` can dead-letter a healthy message. Carry the delay through and do not count the attempt. Parity with the classic queue is the spec.
- **F5. Retrying messages show no error.** Only the type and message of the top-level exception are kept (`ShardOwner.java:587`), and only when the message is dead-lettered. `scheduleRetry` writes no error, and the live table has no `last_error` column. Decide whether to add a nullable column through a schema contributor change. Either way, store the root cause.

### Performance (measure first)

- **F6. Reads far more rows than it dispatches.** `readFromCursor` reads up to `readBatchSize` (500) rows, payloads included, but dispatches only as many as there are free permits (default 8). Each handler completion triggers a read. Limit the read to `min(readBatchSize, availablePermits)`, or keep a bounded read-ahead buffer.
  - Measure: rows and `shared_blks_hit` per call in `pg_stat_statements`, the `cursorReadsPerMessage` metric, and a run with 64 KB payloads.
- **F7. The retry update is not a HOT update.** The javadoc says it is, but `visible_at` is indexed. Check `n_tup_hot_upd` against `n_tup_upd` after a retry storm, then fix either the comment or the index design.
- **F8. Unordered lanes have no batched read.** Only `OrderedShardOwner` implements `BatchReadableOwner`. Count statements per wakeup with 64 unordered shards per pump before implementing it.
- **F9. Pull-session claim under stale statistics.** `claimForSession` (`ShardOwnedStorage.java:448-454`) has the classic claim's shape. Run the EXPLAIN reproduction from A1 against it.
- **F10. NOTIFY cost.**
  - One global channel means every process wakes for every queue. Consider one channel per queue.
  - The local hand-off path notifies even though the local owner already has the rows. Skip that notification.
  - Measure commit throughput with N concurrent producers, with NOTIFY on and off.
- **F11. Takeover write amplification.** `bumpAttemptsOnTakeover` rewrites every backlog row of the shard. After F1, consider bumping only rows that were in flight. Check `pg_stat_wal` across a rebalance with a large backlog.

### Hygiene

- **F12.** The module's `CLAUDE.md` gives two idle-cost figures that contradict each other: "~4 q/s per owned shard" and "84 statements / 3 s per queue". Measure the real figure and fix the text.
- **F13.** `instanceId` defaults to the hostname, so two JVMs on one host count as one instance and skew `fairShare`. Default to hostname plus PID, or a random suffix.

### What the classic queue can borrow from shard-owned

| Shard-owned mechanism | Classic queue item |
| --- | --- |
| Loop while delivering; signal the poller when a handler finishes | B1 |
| Backoff that resets on any delivery; deadline from the server-computed next-visible time | C1, C3 |
| `LATERAL … LIMIT` to force a stable plan | A2 |
| Virtual-thread handlers behind a semaphore; no unit of work around the handler | D4, D1/D2 |
| `PoolBudget` startup check | D3 |
| Batched acknowledgements on the poller's connection | Follow-up to D1. Only for handlers that do not need an atomic acknowledgement. |

## Sequencing

1. **Quick, low-risk fixes:** A1 with its plan-shape test, E1, E2, A4 documentation, and the B1 ceiling note.
2. **Shard-owned correctness:** F1–F4, each with a failing test written first.
3. **Measurement round:** D0, the C1/C4 statement counts, and the F6/F9/F10 measurements. Record baselines with `scripts/test-timings.sh --csv` and the benchmark suite.
4. **Throughput:** B1, C1 + C2, then C4.
5. **Connections:** D1, then D4. D2 stays a spike until D0 shows it is needed.
6. **Harder SQL work:** A2 and A3, and C3.

## Appendix — stale-statistics reproduction

Scratch PostgreSQL 17 with the 0.60 table DDL and the three indexes from `PostgresqlDurableQueues.contribute`, created `WITH (autovacuum_enabled = false)` so the stale statistics persist:

```sql
-- 1. fill 20 000 rows into one queue, then drain
INSERT INTO dq (id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts, delivery_mode)
SELECT gen_random_uuid()::text, 'q0', '{}'::jsonb, 'T', now() - interval '1 hour',
       now() - interval '1 hour' + g * interval '1 ms', 'NORMAL'
FROM generate_series(1, 20000) g;
DELETE FROM dq;
-- 2. analyze while empty: reltuples = 0, relpages > 0
ANALYZE dq;
-- 3. burst arrives (repeat the INSERT), then EXPLAIN ANALYZE each claim variant inside BEGIN … ROLLBACK
```

Analyzing a table that has never held rows does **not** reproduce the problem. With `relpages = 0`, PostgreSQL estimates the row count from the table's actual size on disk.
