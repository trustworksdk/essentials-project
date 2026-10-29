# Event processors by contract, not by knobs

**Status:** decided 2026-10-29 (§ Decisions). `PartitionedEventProcessor` and the start-up refusal are built;
`PartitionedViewEventProcessor` comes later.

## Why

An event processor's behaviour is spread over settings that interact: the inbox consumption mode (an override), the
competing-inbox opt-in (a global property, unreleased), the inbox consumer count, the forward batch size, the view batch
size - and the queue engine underneath decides which combinations are safe. A user has to know all of it to get one
processor right, and some combinations are wrong in ways that only show in production: competing consumers on
`PostgresqlDurableQueues` can handle one aggregate's events out of order, and today that only logs a WARN.

The proposal: a user picks a **class whose name states its contract**. Each class has one behaviour, sensible defaults,
and **refuses to start** where its contract cannot be kept. Throughput settings stay, as tuning that cannot break
correctness.

## Which one

| You need | Use | Handlers run | Order kept | Works on |
|---|---|---|---|---|
| Async reactions with retries (send a command, call a system) | `EventProcessor` | One instance | Per aggregate; close to commit order across aggregates with one consumer thread | Every queue engine |
| The same, spread over instances | `PartitionedEventProcessor` (new) | Every instance | Per aggregate only | Engines that keep key order across instances (today: shard-owned) |
| A read model, low latency | `ViewEventProcessor` | One instance | Commit order across aggregates | Every queue engine |
| A read model, spread over instances | `PartitionedViewEventProcessor` (new, later - EF-2 (a1)) | Every instance | Per aggregate only | As `PartitionedEventProcessor` |
| Work that must commit with the event | `InTransactionEventProcessor` | The appending instance | The appender's | Every queue engine |

## The classes

### `EventProcessor` (exists, unchanged)

**Why use it:** the default for asynchronous reactions. Safe on every engine, nothing to configure.
**What it does:** one instance reads the events (exclusive subscription) and forwards them to the processor's inbox;
one instance runs the `@MessageHandler`s, with redelivery and dead letters. `UnitOfWorkMode.NONE` handlers are allowed.
**What it does not do:** spread handling over instances. A second instance is a hot standby for this processor.
**Be aware:** more inbox consumer threads (`getNumberOfParallelInboxMessageConsumers()`) speed it up but give up order
across aggregates, as any parallelism does; per-aggregate order always holds.

### `PartitionedEventProcessor` (new)

**Why use it:** an `EventProcessor` whose handlers are the bottleneck and may run on several instances at once.
**What it does:** one instance reads and forwards in batches; every instance runs the `@MessageHandler`s for its share
of the aggregates (the queue engine assigns each aggregate to one instance at a time). Batched forward is on by default.
**What it does not do:** keep order across aggregates; run on an engine that keeps key order per node only - it refuses
to start there, naming the setting to change (`essentials.shard-owned-queue.durable-queues-enabled`).
**Be aware:**
- Handlers run concurrently on several instances, for different aggregates. Not for handlers that keep in-memory state
  across aggregates or call something that accepts one caller only - use `EventProcessor` for those.
- Handlers of different aggregates writing one shared stream (all orders of one account) conflict on it more often;
  they are retried (measured: 2.0 % → 2.9 % of deliveries).
- Handlers must be idempotent: a failover can deliver a message twice (true of every processor, more likely here).

### `ViewEventProcessor` (exists, unchanged)

**Why use it:** the default for read models. Lowest latency: the event is handled on the reading thread.
**What it does:** one instance reads and handles each event inline, in its own unit of work; a failed event goes to the
processor's queue and is retried there, and later events of the same aggregate queue behind it, so its order holds. Batch handling
(`getDirectHandlingBatchSize()`) is a throughput setting.
**What it does not do:** spread over instances; allow `UnitOfWorkMode.NONE` handlers (refused at start).
**Be aware:** one instance handles every event of the view, so a busy view is bounded by one thread; batching raises
that bound.

### `PartitionedViewEventProcessor` (new, later)

**Why use it:** a read model keyed by aggregate whose updates are the bottleneck.
**What it does:** one instance reads and forwards in batches; every instance updates the view for its share of the
aggregates.
**What it does not do:** keep order across aggregates; handle inline (latency is a queue hop, a few ms); run without
the engine capability (refuses to start).
**Be aware:** only for views where each row belongs to one aggregate - a view that reads or writes rows shared across
aggregates (totals, "latest N across all accounts", a parent row written by another aggregate) breaks. Resetting the
view needs it stopped on every instance. Design and open points: `docs/key-partitioned-event-processing.md` §3.2, §4, §8.

### `InTransactionEventProcessor` (exists, unchanged)

**Why use it:** a reaction that must commit or roll back with the event that caused it.
**What it does:** runs the handler inside the appending transaction.
**What it does not do:** retry: a handler that throws fails the append it runs in; allow `UnitOfWorkMode.NONE`.
**Be aware:** the handler's time is the appender's time.

## What changes relative to this branch

| Today (unreleased, on this branch) | Proposed |
|---|---|
| Global property `essentials.eventstore.event-processors.competing-inbox-consumers` | **Removed before release**; the class is the opt-in |
| `EventProcessorDependencies.setCompetingInboxConsumers(...)` | Removed |
| Override `getInboxMessageConsumptionMode()` → `GlobalCompetingConsumers` on an engine without the capability: WARN, runs | **Refuses to start** (an existing override on a capable engine keeps working) |
| `DurableQueues.keepsKeyOrderAcrossInstances()` / `Inboxes.keepsKeyOrderAcrossInstances()` | Kept - the new classes check it |
| Forward batch size, consumer counts, view batch size | Kept as tuning; the partitioned classes default them up |
| The demo's `competing-inbox-consumers: true` | Its six processors extend a demo base, `TradingDemoEventProcessor`, that picks competing consumers where the engine keeps key order - a test aid so the same processors run on both engines (tests and the A/B on `PostgresqlDurableQueues`); not a pattern for applications |

Released API is unchanged: `getInboxMessageConsumptionMode()` stays overridable; only the unsafe combination becomes
a start-up failure, which is a behaviour change for anyone relying on it today and goes in the release notes.

Spreading whole processors over instances by spreading their locks (`docs/key-partitioned-event-processing.md` §3.5)
is not a processor contract and is not part of this proposal; it would be a lock-manager setting, decided separately.

## Decisions

1. **Names:** `Partitioned…` - `PartitionedEventProcessor`, later `PartitionedViewEventProcessor`.
2. **Fail-fast in 0.61:** an override to `GlobalCompetingConsumers` on a queue without cross-instance key order refuses
   to start; a breaking-change entry in the release notes.
3. **The partitioned classes fix the consumption mode** (`getInboxMessageConsumptionMode()` is final).
4. **`PartitionedEventProcessor` first;** the view class after EF-2's view decisions
   (`docs/key-partitioned-event-processing.md` §8).
