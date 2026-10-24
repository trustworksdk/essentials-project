# Plan: batched direct handling for `ViewEventProcessor`

**Status:** decided, being built (2026-10-23). Unparked before the release merge, by the user's call: the phase 6
baseline (`examples/essentials-trading-demo/.../_demo_harness/CLAUDE.md`, the business-day run) measures it now, and
again once the release lands. Step 1 is therefore skipped; conflicts with the release are resolved at the merge.
**Module:** `components/postgresql-event-store` (plus `foundation` and the `DurableQueues` implementations if step 3
goes ahead).
**Builds on:** `3ff8870d` — `EventProcessor.getForwardBatchSize()` and the batched forward.
**Goal:** read models stay fresher under load. Views are not on the order flow's completion path, so this is about view
lag, not throughput of the flow itself.

## Today

A `ViewEventProcessor` handles every event in a unit of work of its own, on a non-exclusive asynchronous subscription
behind the processor's own fenced lock. Each event costs:

- one `hasOrderedMessageQueuedForKey` round trip,
- one savepoint plus a release,
- one commit, which also moves the resume point.

Failures follow the rules in the module `CLAUDE.md` and the `handleDirectlyUnderSavepoint` / `queueAfterRollback`
javadoc. A failure the savepoint can undo is queued in the same unit of work. An *escalation* is a failure that left
state in the unit of work a savepoint cannot undo: appended events, lifecycle resources with pending changes, a unit of
work marked rollback-only, or a failed savepoint rollback. An escalation rolls the unit of work back, the
`SubscriptionErrorPolicy` retries the event, and then `PersistedEventHandler.handOffFailedEvent` queues it in a unit of
work of its own.

## What already exists

- `EventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously` is a **non-exclusive** batched
  subscription. It is the right fit, because the view takes its own lock, so no new subscription class is needed.
- `BatchedPersistedEventSubscriber` runs each batch in one unit of work and applies the policy to the batch as a whole.
  When the policy gives up, it either **skips the whole batch** or **stops at the batch's first event**. Nothing like
  `handOffFailedEvent` exists for a batch.
- `DurableQueues` has no multi-key variant of `hasOrderedMessageQueuedForKey`.

## Design

### Opt-in

`ViewEventProcessor` gains two methods that mirror `EventProcessor`:

- `getDirectHandlingBatchSize()`, default `1`. Behaviour at the default is unchanged, and values below `2` handle
  event by event.
- `getDirectHandlingMaxLatency()`, default 20 ms.

At a batch size above 1, the processor subscribes through `batchSubscribeToAggregateEventsAsynchronously`. It uses the
same subscriber id, so a processor can switch either way and keep its resume point, as `EventProcessor` does.

### Handling one batch (one unit of work)

1. Pass over the events the processor has no handler for (`hasNoHandlerFor`), as today.
2. Find which of the batch's keys already have queued messages. That is the set `Q`. Step 2 below does this with one
   check per event, step 3 with a single query.
3. For each event, in order:
   - If its key is in `Q`, queue it as an `EventReferenceOrderedMessage`.
   - Otherwise take a savepoint, handle the event with `CausationContext.where(event.eventId())`, and release the
     savepoint.
   - If handling fails and the savepoint can undo it, roll back to the savepoint, queue the event, and **add its key to
     `Q`**. Later events for that key in the same batch then queue behind it.
4. The subscriber commits once, together with the resume point.

**Why a stale `Q` is safe:** only this processor's direct path puts messages on its queue, and it records its own
additions in `Q` as it makes them. While a batch runs, the queue consumer can only *empty* a key. When it does, an event
for that key is queued that could have been handled directly. That is harmless, and ordering still holds.

### Escalation inside a batch

A savepoint cannot undo unit-of-work state, so the **whole batch rolls back**, including the view writes of the events
before the failure that had succeeded. Then:

1. The `SubscriptionErrorPolicy` retries the batch as usual. This matters because a failed savepoint rollback normally
   means the connection is gone, which is transient.
2. When the policy gives up, a new **additive** hook, `BatchedPersistedEventHandler.handOffFailedBatch(List<PersistedEvent>,
   Throwable)` with default `false`, is consulted before the skip or stop. It mirrors `handOffFailedEvent`, including
   the rule that the hand-off runs before the resume point is advanced.
3. The view's implementation of the hook replays the batch **one event per unit of work**, through today's per-event
   path. The poison event escalates in its own unit of work, is rolled back, and is queued by `queueAfterRollback`.
   Every other event commits normally. If the replay throws, the policy's skip or stop applies to the batch.

Without the hook the only choices are to skip the batch, so the view silently loses up to batch-size events, or to stop,
so the view stalls on one handler's bug. Neither is acceptable for a view.

### False escalation (decided: conservative)

`numberOfEventsPersisted` is compared before and after each event, so it stays exact inside a batch.
`hasLifecycleCallbackResourcesWithPendingChanges()` is a plain boolean, though. When an earlier event in the batch
changed an aggregate successfully, a later failing event looks like an escalation. We accept that. The batch falls back
to the per-event replay, which is correct but slower, and a view handler that changes aggregates is unusual anyway.

### Infrastructure failures

A dropped connection, a failed commit, or a failing key query or `queueMessage` rolls the batch back, and the policy
retries it. If the policy gives up, the replay will most likely fail too. Then skip or stop applies to the batch, which
means `RETRY_N_THEN_SKIP` can leave up to batch-size events out of the view instead of one. This is the same "policy
applies to the batch as a whole" trade `EventProcessor` made, and the javadoc must say so.

### Contention

Row locks are held for the length of a batch. A view that aggregates across keys, such as per-instrument totals, can
deadlock against the queue consumer's transactions. The batch then fails and is retried, so nothing is lost, but
throughput suffers. Watch for it while measuring.

## Steps

1. **Merge the new release** into the branch and rebase this plan on whatever it changed in the subscription and
   processor code.
2. **Batch unit of work plus a savepoint per event**, keeping the per-event queued-for-key check. This covers the opt-in,
   `handOffFailedBatch` (`BatchedPersistedEventHandler` plus `BatchedPersistedEventSubscriber`) and the view's replay.
   Measure.
3. **One queued-for-key check per batch.** Add a `DurableQueues` default method, for example
   `keysWithOrderedMessagesQueued(QueueName, Collection<String>)`, that loops by default, then implement it in
   `PostgresqlDurableQueues`, `ShardOwnedDurableQueues` and `MongoDurableQueues`. This is a queue change, so it lands
   only with before/after measurements against `main` (`QueueFetchStrategyBenchmarkIT`, the shard-owned cost ITs) and
   only if it pays off beyond step 2.
4. **Demo:** set the view processors' batch size in the compose profile, keeping the code default at 1 as with
   forwarding.
5. **Docs:** `ViewEventProcessor` class javadoc, the module `CLAUDE.md` escalation entry, `LLM/LLM-postgresql-event-store.md`
   (bumping the plugin version), and a section in `docs/key-partitioned-event-processing.md` §5.

## Tests

`ViewEventProcessorIT`, with a batch size above 1:

- A failing event in the middle of a batch is queued. The events before and after it commit. A later event for the same
  key in the same batch is queued behind it.
- An escalating event in a batch causes a rollback and policy retries, followed by the per-event replay. Only the poison
  event ends up queued, and every other event is projected exactly once.
- The false-escalation case (an earlier event changed an aggregate) is covered by the replay.
- An infrastructure failure under `RETRY_N_THEN_SKIP` skips the batch and under `STOP` stops at its first event.
- Switching from per-event to batched and back keeps the resume point.
- `BatchedPersistedEventSubscriber` unit test: `handOffFailedBatch` returning `true` advances the resume point,
  `false` falls through to skip or stop, and a hand-off that throws counts as the policy giving up.
- The Spring `UnitOfWork` variant: `SpringTransactionAwareEventStoreUnitOfWorkFactory_ViewEventProcessorIT`.

## Measurements

- `EventProcessorForwardBenchmarkIT` `view_all_five` and `view_one_of_five`, with batch sizes 1, 100 and 100 plus the
  bulk key check, several runs each.
- Trading demo view lag under the order-flow load, using `~/trading-demo-profiling/lag-sample.sh`, alternating runs as
  in EF-2.

## Decisions (2026-10-23)

1. **On escalation, retry the batch per the policy first, then replay** event by event - consistent with the per-event
   semantics today; the usual escalation is a dropped connection, which a retry fixes.
2. **The bulk key check (step 3) is staged after step 2**, and only if step 2's measurements show it is worth it: it
   changes the queue engines, which needs before/after measurements against `main`, and those wait for the release.
   **Built 2026-10-24, earlier than planned**, because the trading demo showed the per-event check was not just a
   round trip: on the shard-owned engine it took a second pooled connection while the batch's unit of work held the
   first, `idle in transaction`. `DurableQueues.getKeysWithOrderedMessagesQueued` (additive `default`), asked once per
   batch; the shard-owned adapter answers it, and the per-key check, on the unit of work's connection. Demo burst at
   300 orders/s, five alternating runs: fewer threads waiting for the pool, no measurable end-to-end change - the
   demo's JVM is CPU-bound at every rush (`examples/essentials-trading-demo/.../_demo_harness/CLAUDE.md`). The fix is
   kept for the pool exhaustion `OrderedKeyCheckOnShardOwnedIT` reproduces. `PostgresqlDurableQueues` keeps the
   looping default - its check already runs in the caller's unit of work.
3. **`getDirectHandlingBatchSize()` / `getDirectHandlingMaxLatency()`** are the names.

The failure semantics above are accepted, including the one trade: an infrastructure failure the replay cannot get past
leaves the policy's skip or stop to the whole batch.
