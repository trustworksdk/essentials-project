# Durable Queues Improvement Plan — open items

What is left of the durable-queue improvement plan after 0.60.1. The full plan, with the verification of the original report, every measurement and the reasoning behind each item that was built or closed, is archived at [`../archive/durable-queues-improvement-plan.md`](../archive/durable-queues-improvement-plan.md). Item ids (D4, E3, …) are kept from it.

Last checked against the code: 2026-11-26 (`release/0.61.1`).

Compatibility rule for every item: central APIs change only additively in a minor release. A changed default is a behaviour change. It goes in the release notes and keeps an opt-out.

Measurement rule: no performance change is committed without before/after numbers from the existing benchmark suites (`-Dbenchmark.run=true`), run from clean worktrees with nothing else running. C4 below was reverted for breaking this rule.

## Classic queue (`postgresql-queue`)

### D4. Virtual threads for handlers

Default the worker pool to virtual threads, with a semaphore of `parallelConsumers` permits, as the shard-owned engine does in `ShardRuntime`. The plan's precondition is met: D0 (measure) and D1 (acknowledge inside the handler's unit of work) are done, so connection waits no longer hide the effect of the threads.

- Benchmark first, with no code change: `ConsumeFromQueue.consumerExecutorService` already accepts a caller's executor. Run `QueueDrainThroughputIT` with a virtual-thread executor and compare it with the D1 baseline (about 14.9k msg/s, about 0.8 threads waiting for a connection, 2 queues × 32 consumers, pool of 10).
- Expect connections, not threads, to remain the limit. Change the default only if the benchmark shows a gain.
- Check that thread-local unit-of-work state still works. It should, because each handler runs on its own virtual thread.
- Watch for pinning: a handler that blocks inside `synchronized` code pins its carrier thread.

### C4. Skip the ordered claim on queues without ordered messages

Each poll runs the ordered claim first, and on a queue that never receives ordered messages it returns no rows. A version that merged both claims into one statement was committed without measurement and reverted, because it undid the 0.60 split that measured 5.4× faster. The partial fill (the unordered claim fills the slots the ordered claim left) is done.

Remaining option: remember per queue that no ordered message has been seen. Reset the flag on an enqueue with a key and on any resurrect or retry of a keyed message, and re-check every N polls to stay safe across nodes.

Measure first: `pg_stat_statements` calls and total time per statement at 0, 100 and 5 000 msg/s on 10 queues, before and after. Do it only if the saved statement is measurable against the steady-load benchmark (0.50 claim statements per message at 200 msg/s).

### Batched acknowledgements

The shard-owned engine acknowledges in batches on the poller's connection. Since D1, a successful handler's acknowledgement runs inside the handler's own transaction, which makes the handler's effects and the acknowledgement atomic. Batching is therefore only possible for handlers that do not need that atomicity: `UnitOfWorkMode.NONE` handlers and handlers with no database side effects. Decide whether that subset is large enough to be worth a second acknowledgement path before designing it.

### Batched fetch: stale "work in progress" javadoc

`PostgresqlDurableQueues.fetchNextBatchOfMessagesBatched` and `DurableQueuesSql`'s batched statement builder are still documented as "work in progress — doesn't handle competing consumers yet" (the `DurableQueuesSql` sentence is also cut off: "due to )"). The strategy is opt-in (`PostgresqlDurableQueuesBuilder.setUseBatchedFetch`, default `false`), covered by `BatchedFetchStrategyIT`, and its statement now locks with `FOR UPDATE SKIP LOCKED` and updates by primary-key array, like the per-queue claims.

- Add a competing-consumers test: two `PostgresqlDurableQueues` instances on the same table, both with batched fetch, assert no message is delivered twice.
- If it passes, replace the javadoc with what the strategy does and when the fetcher switches to it. If it fails, fix the statement or document the restriction on `setUseBatchedFetch`.

## Shard-owned engine (`postgresql-queue-shard-owned`)

### F5. A message being retried shows no error

Only the dead-letter table has a `last_error` column. `ShardOwnedStorage.scheduleRetry` and `scheduleRetryIfOwned` take no error, and the live tables store none, so an operator looking at a message that keeps failing cannot see why until it is dead-lettered. The dead-letter error text is rendered by `DeliveryErrorText`.

Decide:
1. Add a nullable `last_error text` column to the live tables through `ShardOwnedSchema` (additive, behind the existing catalog check for columns added after the table shipped), and write it on every retry. The retry update is already not a HOT update (`visible_at` is indexed, F7), so the extra column costs only the row width.
2. Keep the live tables narrow and log the error at retry time only.

Either way, store the root cause, as the classic queue does through `DeliveryErrorDescription`.

### F10. NOTIFY cost on the commit path

One channel per queue was tried and performed worse (closed). The local hand-off already wakes the owner after commit instead of notifying inside an autocommit transaction (`ShardOwnedQueue.wakesAfterCommit`). Not yet measured: commit throughput with N concurrent producers, with NOTIFY on and off. Measure before changing anything. The related pgjdbc notification drain limit is tracked in [`README.md`](README.md) under "Open items from documents kept elsewhere".

## Documentation

### E3. HikariCP `minimumIdle`

HikariCP keeps `maximumPoolSize` connections open even when idle, because `minimumIdle` defaults to the pool size. Services that only consume queues add up quickly against PostgreSQL's `max_connections`. Document a lower `minimumIdle` for such services in `LLM/LLM-postgresql-queue.md` and the module README, next to the connection guidance added for D2 and D3. Change no default.
