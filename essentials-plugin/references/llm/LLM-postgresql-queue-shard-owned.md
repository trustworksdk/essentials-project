# PostgreSQL Shard-Owned Queue - LLM Reference

> Quick reference for LLMs. How the engine works: [docs/durable-queue-shard-owned.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/docs/durable-queue-shard-owned.md). Every number quoted here: [docs/durable-queue-measurements.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/docs/durable-queue-measurements.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.queue.shardowned`
- **Implementation**: `PostgresqlMessageQueue` implements `MessageQueue` — a **new SPI**, not `DurableQueues`. One `consume()` covers both lanes; it builds a `ShardOwnedQueue` per lane underneath, since one of those serves one lane
- **Spring Boot**: the starter is **off by default** — nothing is configured until `essentials.shard-owned-queue.enabled=true` (default false), unlike the other Essentials starters
- **As `DurableQueues`**: `postgresql-queue-shard-owned-adapter`, selected by `essentials.shard-owned-queue.durable-queues-enabled` (default false; needs `enabled=true` as well)
- **Storage**: three lanes (`shard_queue_unordered`, `shard_queue_ordered`, `shard_queue_dead_letter`) plus `shard_queue_lease`, `shard_queue_instance` and `shard_queue_registry`; `bytea` payloads
- **Locking**: none on the delivery path — a shard lease establishes ownership, so there is no claim write
- **Notifications**: LISTEN/NOTIFY on one global channel, payload `queueId:lane:shard`
- **Dependencies**: PostgreSQL driver, Micrometer (both `provided`), `shared`
- **Status**: **Published**, and new. No production use yet. `MessageQueue` freezes at the release that ships it — additive in minor, breaking only in a major — but it is **not frozen on this branch**: the dead-letter work widened it on purpose (`resurrectKey`, two `QueueStatistics` counters, `DeadLetter.blockedByKeyOrder`) while that is still free.

## TOC
- [The one idea](#the-one-idea)
- [Getting started](#getting-started)
- [Prerequisites](#prerequisites)
- [Configuration reference](#configuration-reference)
- [Sizing and scaling](#sizing-and-scaling)
- [How to not kill the database](#how-to-not-kill-the-database)
- [How to not kill the application](#how-to-not-kill-the-application)
- [Worked examples](#worked-examples)
- [Measuring your own deployment](#measuring-your-own-deployment)
- [Administrative API](#administrative-api)
- [Gotchas](#gotchas)

## The one idea

Every message is assigned a shard at enqueue. Each shard has exactly one owning consumer at a time, held by a lease in `shard_queue_lease`. A consumer reads only shards it owns.

Consequences, all measured:
- **No claim write.** The current implementation writes `is_being_delivered = true` per message; this writes none, because a lease already says who owns it. `n_tup_upd` is zero.
- **Ordering is a consequence of ownership**, not of a query. Same key → same shard → one owner, whose in-memory set of keys in flight is the whole FIFO guarantee. It holds across processes.
- **A key never advances past a dead letter.** Once one of a key's messages is parked, nothing above that `key_order` is delivered and anything arriving for the key is dead-lettered too, marked `DeadLetter.neverDelivered()` (do not use `attempts` — a takeover bumps it on rows never delivered). The block is derived from the dead-letter table, so it survives a rebalance and a restart, and is cleared by resurrecting or deleting the dead letter. Recovery is `queue.resurrectKey(key)` — every dead letter for that key, in one transaction, replayed in `key_order` (`POST /shard-owned-queues/{queueName}/ordered-keys/{key}/resurrect`). Per-message resurrect still works but must be walked ascending and awaited, since restoring a higher `key_order` while a lower one is parked simply parks it again. What is *not* prevented, and is counted as `orderViolations` instead: a producer that numbers and commits in different orders. A retry does not reorder, including across a shard hand-over: a failing message blocks its key for its whole backoff, and a new owner after a rebalance, graceful stop or crash reloads that block from the table (before 0.60.1 it did not, and a later `key_order` overtook the message in backoff). And a delayed message does not block its key — an undelayed later `key_order` overtakes it, because only visible rows are dispatched. `PostgresqlDurableQueues` blocks the key instead, and the adapter reaches this from `queueMessage(queue, orderedMessage, deliveryDelay)`, so that one `DurableQueues` call behaves differently on the two engines; left deliberately, reasoning and reopen trigger in `docs/durable-queue-shard-owned.md` §18.3. Keep a key's messages either all delayed or all not.
- **Threads and connections are properties of the process**, not of the shard or queue count — but only if you share a `ShardRuntime` (see below).

## Getting started

```java
// 1. Schema, once per database. Non-destructive and idempotent, so it is safe on every boot.
//    (ShardOwnedSchema.recreate(dataSource) DROPS everything and is for tests.)
ShardOwnedSchema.initialize(dataSource);

// 2. Once per queue, by NAME, IN CODE — next to the component that owns the queue, not in a
//    config file. Idempotent, so every instance may call it at start-up; the shard count is
//    recorded with the name and refused if it ever disagrees.
//    In Spring: queues.register(QueueName.of("orders"), 8) on the ShardOwnedQueueFactory.
ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);

// 3. A queue. PostgresqlMessageQueue is the entry point: one consume() covers BOTH lanes.
//    setQueueName takes the id and the shard count from the registry, so there is nowhere to
//    supply a count that disagrees with the one the queue was created with.
try (var queue = PostgresqlMessageQueue.builder()
                                       .setDataSource(dataSource)
                                       .setQueueName(QueueName.of("orders"))
                                       .setInstanceId(instanceId)
                                       .build()) {
    queue.enqueue(List.of(Message.of(payload, payloadType)));
    var subscription = queue.consume((messageId, key, payload, payloadType) -> handle(payload, payloadType),
                                     ConsumerOptions.defaults());
}
```

**There is no runtime to construct here.** A `PostgresqlMessageQueue` borrows the `ShardRuntime`
shared per `DataSource` — reference counted, closed by its last user — which is what keeps held
connections at `pumpThreads + 1` for the whole process rather than per queue. Constructing one
explicitly is only for the low-level path below, and it is the only way to get that sharing wrong.

**Queue names live in code, as everywhere else in Essentials.** There is a
`essentials.shard-owned-queue.queues` map, and it is not the normal way to declare a queue — it puts
the name in two places that have to agree. Register where you use:
`queues.register(QueueName.of("orders"), 4)`. Through `DurableQueues` you declare nothing at all: the
framework derives the names (`Inbox:<processorName>`, `<processorName>:queue`, `Outbox:<name>`,
`DefaultCommandQueue`) and the adapter registers them on first use.

**Turning that off means registering by hand.** `setAutoRegisterShardCount(0)` — or
`essentials.shard-owned-queue.auto-register-shard-count: 0` — restores the refusal, and then every
queue an Inbox, Outbox or command bus invents has to be registered before it is used, under exactly
the name the framework derives. Worth it only if you would rather a typo fail than become a queue.

**An enqueue through `DurableQueues` joins the caller's transaction — give the adapter a
`UnitOfWorkFactory`.** `ShardOwnedDurableQueues` runs every enqueue inside `withUnitOfWork` on the
`HandleAwareUnitOfWork`'s connection, so an `Outbox.sendMessage` rolls back with an Essentials
`UnitOfWork` *and* with a plain Spring `@Transactional`/`TransactionTemplate` when the factory is
Spring-aware (the starter wires `SpringTransactionAwareJdbiUnitOfWorkFactory`). 0.60.0 joined only an
already-bound `UnitOfWork`, so under a plain Spring transaction the enqueue committed on its own and
survived the rollback. Built without `setUnitOfWorkFactory(...)`, every enqueue commits on its own and
`build()` WARNs; 0.61 will refuse to build. An enqueue outside any transaction commits on its own and WARNs once per call site (within a budget of 32 innermost application frames with up to 4 callers each);
`essentials.durable-queues.require-active-unit-of-work-for-enqueue=true` (or the builder's
`setRequireActiveUnitOfWorkForEnqueue(true)`, which then needs a factory) makes it throw `NoActiveUnitOfWorkException`.

**The lane is chosen by the message, not by the consumer.** `Message.of` is unordered,
`Message.ordered` carries a key, and one `consume` serves both. A *second* `consume` on the same
queue is therefore a separate competing consumer with its own instance identity — not "the other
lane". Registering one per lane is a mistake the demo made: it delivered unordered messages to the
ordered handler and reported two live instances for one process.

**`Subscription.stop()` keeps it registered; `unsubscribe()` and `close()` end it.** A stopped
subscription stays with its queue, and the queue's `start()` starts it again. `close()` is
`unsubscribe()` (since 0.60.1; it was `stop()` in 0.60.0), so try-with-resources, or Spring destroying
a `Subscription` bean, ends it. Letting go of one and calling `consume()` anew needs `unsubscribe()` or
`close()` first, which stops both lanes and takes them out of the queue: its instance id is free for
the next `consume()`, which is not counted as competing with the
unsubscribed subscription (it still is with any other subscription of the queue, and logs the COMPETING
WARN for it). Two cases delay the id, either one enough, and when both apply it is free only once both
have passed: when the stop kept an ordered unit, because a key was still in its handler past
`shedGrace`, the id stays out of use until that handler has returned and the unit has been handed back
(before it picks an id, a `consume()` retries a hand-back that failed), or until that handler has been
running longer than the queue's `stuckHandlerTimeout` (default 15 min, below), or until the unit is
released on request (`MessageQueue.releaseKeptOrderedUnit`, `POST .../ordered-units/{unit}/release-kept?instanceId=…`, below),
as until then that unit's lease names the id and a subscription under it would take the unit at once and
could start the key beside the running handler; and, when the stop gave up waiting for the
hand-back to the database of a lane whose stop kept no ordered unit (releasing that lane's leases and
deregistering the id; an ordered lane that kept one never deletes the id's membership row from its
hand-back), the id stays out of use
until that hand-back has returned, as it may still delete the id's membership row, each of its waits
for the database bounded by the queue's `leaseTtl` (2 s at least) unless the connection's own network
timeout is shorter. A `consume()` before then registers under another instance id,
the lowest one free (`<id>-1` when the unsubscribed subscription held `<id>` and no other is registered
or held back), with no COMPETING WARN unless another subscription is registered. Once the handler has
returned, the engine hands the kept unit back, whether or not anything runs under the id again: it flushes
the handled message's acknowledgement and releases the unit's lease, so whichever instance takes the unit
next charges no attempt and does not deliver that message again. A hand-back that fails (a database
error) is retried by the heartbeat, start or stop of the subscription's lanes if they run again, and,
for an unsubscribed subscription, by the next `consume()`.
Nothing heartbeats an unsubscribed id, so until the unit is handed back another instance can take it once
the id's membership row has aged out (`leaseTtl`). A subscription or queue restarted instead (`Subscription.start()`, the queue's
`start()`) stays registered under the id and heartbeats it, so no other instance takes the kept unit
before it is handed back; the restart itself leaves that unit alone until then, and it or another
instance below its fair share takes it after that. Until then no key of that unit is delivered.
**`stuckHandlerTimeout` bounds that wait** (default 15 minutes, per queue): once the kept key has been in its
handler longer, the restarted queue - or the next `consume()` wanting the unsubscribed id - stops waiting and
takes the unit over under a new fence. The stuck handler's acknowledgement is then refused and its message
delivered again with an attempt charged, so **that key may run in two handlers at once** (at-least-once).
Without the bound (it cannot be turned off; a value longer than the process will run comes to the same) a
handler that never returns leaves the unit unserved, and the id out of use, until the process restarts. Set
it above the longest time a handler of the queue really takes, as `messageHandlingTimeout` is set for
`postgresql-queue`. Once a kept key has been in its handler longer than `leaseTtl` the engine WARNs, naming
the queue, the unit, the key(s) and how long they have run, at most once a minute per unit; it WARNs once
more when it stops waiting. `ShardOwnerMetrics.keptUnitsInHandler` counts such units (below).
**To release a kept unit before the bound** - a handler known to be hung - call
`MessageQueue.releaseKeptOrderedUnit(instanceId, unit)` (admin API: `ShardOwnedQueuesApi.releaseKeptOrderedUnit`,
`POST /shard-owned-queues/{queueName}/ordered-units/{unit}/release-kept?instanceId=…`, `QUEUE_WRITER`) on the
instance whose WARN named the unit, with the instance id that WARN names (required): kept units live in that
process's memory, and only a unit a subscription under that instance id kept is touched - one of the same
number kept under another id is left alone. The answer is `{answeredBy, releases}`: `answeredBy` names the
instance ids the answering process has for the queue, so an empty `releases` from another process, or for
another instance id, says which process answered. It flushes the unit's acknowledgements under the fence it was kept under and releases its
lease row, so any instance below its fair share takes it under a new fence - and **delivers the messages
still in a handler again, possibly beside those handlers** (the cost the wait exists to avoid). Before the
release each of those messages is charged an attempt, and one that reaches `maxAttempts` by it is
dead-lettered instead of delivered again, so a message whose handler hangs on every delivery reaches the
dead-letter table once the queue has stopped waiting for it `maxAttempts` times (a hang an owner goes on
serving its unit through is not bounded by this). Only a unit a stop kept whose row still names the requested instance id
is touched. Each entry of `releases` reports `RELEASED` (with the keys in a handler), `ALREADY_TAKEN` (taken under a newer
fence meanwhile, nothing released) or `BUSY` (the stop is still flushing it or another hand-back or release
of it runs; ask again).
Once released the unit no longer keeps an unsubscribed subscription's id out of use. The same
bound covers a fair-share shed of an ordered unit and the hand-back of a unit whose pump died, which wait
for the keys in their handlers: past it each releases the unit with every key still in a handler — the one
past the bound and any other still running — with one WARN. Each of those messages is charged an attempt
before the release, as on a release on request, and dead-lettered if that brings it to `maxAttempts`; the
next owner delivers the rest again, possibly beside their handlers. Stopping
instead left one stopped subscription behind per round — a COMPETING WARN with a new `-N` instance id
suffix each time, and a queue `stop()`/`start()` restarting every abandoned handler. `ShardOwnedDurableQueues`
unsubscribes on every consumer stop and cancel, so a `DurableLocalCommandBus` restart or an `Inbox`
lock hand-over keeps one subscription. An unsubscribed subscription's `start()` throws, and its counts
leave `statistics()`.

**The queue's `isStarted()` says whether the queue is started, not whether anything runs.** Only
`start()` and a `consume()` made while no subscription is registered mark it started. After a queue `stop()`, a
`Subscription.start()` or a later `consume()` runs that subscription with `isStarted()` false, so the
queue's `start()` still restarts the others. The queue's `stop()`/`close()` stop every registered
subscription whatever `isStarted()` reads; a manager that stops only a `Lifecycle` reporting itself
started (`DefaultLifecycleManager` at shutdown) skips the queue and leaves that subscription running —
see Gotchas.

### The low-level path serves ONE lane

`ShardOwnedQueue` is the engine underneath, and a single instance consumes a single lane —
`configureUnordered` and `configureOrdered` are mutually exclusive and the second call is refused.
Reach for it when you want exactly one lane and direct control of it; for both lanes use
`PostgresqlMessageQueue` above, which builds the pair for you and shares one instance id between them
so the cluster counts them as one member.

```java
var queue = ShardOwnedQueue.builder()
                           .setDataSource(dataSource)
                           .setQueueId(queueId)
                           .setShardCount(shardCount)   // the UNORDERED lane's count; inert for ordered
                           .setInstanceId(instanceId)
                           .setRuntime(runtime)
                           .build();
queue.configureUnordered((messageId, payload, payloadType) -> handle(payload),  // OR configureOrdered, never both
                         ShardOwnerSettings.defaults(),
                         shardCount,
                         RedeliveryPolicy.fixed(Duration.ofMillis(100), 5));
queue.start();                       // Lifecycle: start()/stop()/isStarted()
```

`remaining()` and `shardsHeld()` on this object cover the configured lane only. Across both lanes,
`Subscription.shardsHeld()` sums the pair, and the admin API's queue status reports each lane's depth
and ownership separately.

**Delayed delivery** — `Message.delayed(payload, type, Duration.ofMinutes(5))`, and
`Message.delayedOrdered(...)` for the ordered lane. The delay is applied by the server, so it does
not depend on this node's clock.

**Transactional enqueue (outbox)** — hand it your own connection and your commit decides:

```java
try (var connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);          // required: autocommit is refused
    orderRepository.save(order, connection);
    queue.enqueue(connection, List.of(Message.of(payload, payloadType)));
    connection.commit();                      // business write and enqueue land together
}
```

**If you construct a `ShardOwnedQueue` without a runtime it borrows the one shared per `DataSource`**, reference counted, closed by its last user. That is the safe default; pass your own only when you deliberately want a separate set of threads and connections.

## Prerequisites

Full detail and the failure modes: [docs/durable-queue-shard-owned.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/docs/durable-queue-shard-owned.md) §17.

- **PostgreSQL 15+**, the supported floor (`LLM.md`). The ordered lane on its own would need 13+, for its start-up probe (`pg_current_xact_id()`).
- **`pg_stat_activity.backend_xid` must be readable.** The ordered lane's cursor decides that a sequence value can never arrive from the set of running write transactions. A *partial* answer is a wrong answer, not a degraded one — the cursor would step over a live writer and lose its messages silently. Verified on PostgreSQL 17.10: an ordinary `LOGIN` role with no grants reads it, for its own backends and for other roles'. `pg_read_all_stats` made no difference in any case tested; it is the fallback the error message names if a managed platform ever redacts the view.
- **The engine probes for it at start-up** (`verifyWatermarkPrerequisites`, first statement of `startOrdered`, once per `DataSource`) and refuses to start the lane rather than risk silent loss. Do not suppress it. It constructs the condition rather than inspecting the column, because `backend_xid` is legitimately null for a backend that has not written.
- **No superuser, no replication slot, no `wal_level=logical`, no extensions.**
- **The right to create sequences at runtime.** Each queue's sequences are named after the id the registry assigns at registration, so they cannot come from a script written beforehand. Under the schema harness ([LLM-foundation.md](./LLM-foundation.md#database-schema-harness)) in a mode other than `create`, the tables, views and fixed sequences come from the script (`ShardOwnedSchema.schemaStatements()`, carried by `ShardOwnedSchemaContributor` in the adapter module) and the engine creates each queue's sequences as it registers; the contributor logs a warning saying so. `registerQueue`/`growShardCount` take a `QueueDdlExecutor` to route that DDL elsewhere.
- **`pumpThreads + 1` connections are held permanently** (default 3) and never returned to the pool. Everything else is per operation — lease renewal and enqueue included, so the pool needs room above that floor. `ShardRuntime` checks at start-up from the pool's `ConnectionPoolMetadata`: a maximum size `<= pumpThreads + 1` throws `IllegalStateException` before any connection is taken, and held connections above half the pool — summed across every running runtime on the same `DataSource` — log a WARN. The Spring Boot starter supplies the metadata from Spring Boot's `DataSourcePoolMetadataProvider`s (HikariCP, Commons DBCP2, Tomcat JDBC, Oracle UCP, proxies seen through). Constructing the runtime yourself, pass `new ShardRuntime(dataSource, settings, metrics, ConnectionPoolMetadata.ofMaximumSize(n))` or your own implementation; without one the requirement is only logged at INFO. Every start logs the share held — `Shard-owned queue: holding 3 of the connection pool's 20 connections permanently (15%; ...)` — at INFO, as a WARN naming the pool size to set once it passes half. When a pump later fails to get a connection and every connection is checked out, it logs ERROR `connection pool exhausted` instead of `lost its connection; reconnecting`.
- **`LISTEN`/`NOTIFY` on one channel.** Blocked notifications (some poolers in transaction mode) cost latency, not correctness: delivery falls back to the sweep cadence, worst case `maxSweepInterval`.
- **Set `socketTimeout` on the DataSource.** Not a requirement, the single most consequential thing you can get wrong. Measured across a real network partition: with `socketTimeout=3` a cut-off instance learns it has lost the database in 3.3 s; without one it had not learned within 90 s, and 90 s is where the measurement stopped, not where the socket did. Nothing else saves it — the pool's `connectionTimeout` never fires, because the heartbeat is blocked inside a read on a connection the pool still considers healthy. The survivors are unaffected either way, taking the shards over at one lease TTL; the cut-off instance is the one that keeps delivering duplicates until its read returns. See `docs/durable-queue-measurements.md` §3.4.1.
- **Intra-service only.** Multiple instances of one service against one database — like the rest of Essentials' queues, locks and inbox/outbox.

## Configuration reference

### `ShardOwnerSettings` — per engine, usually one instance for the process

Build it with `ShardOwnerSettings.builder()`, which starts from `defaults()`, so only the settings that differ are
named; `settings.toBuilder()` starts from existing settings instead (both new in 0.60.1). Prefer either to the
canonical constructor, which takes every setting in a row and gains a parameter whenever a setting is added, as it did
for `stuckHandlerTimeout`.

```java
var settings = ShardOwnerSettings.builder()
                                 .setLeaseTtl(Duration.ofSeconds(10))
                                 .setStuckHandlerTimeout(Duration.ofHours(1))
                                 .build();
var slowShedding = settings.toBuilder().setShedGrace(Duration.ofSeconds(30)).build();   // every other setting as in `settings`
```

| Setting | Default | What it costs | When to change |
|---|---|---|---|
| `pumpThreads` | 2 | **Held connections = `pumpThreads + 1`.** Also the pump thread count | Raise only if the pumps are the bottleneck; each one costs a permanent connection |
| `sweepInterval` | 500 ms | Backstop read cadence for a *busy* shard | Rarely |
| `maxSweepInterval` | 30 s | Cadence a shard backs off to while empty | Lower for faster recovery from a lost notification; raise for thousands of idle shards |
| `pollBackstop` | 500 ms | Park ceiling | Rarely |
| `keyConcurrency` | 8 | Concurrent keys per **ordered** shard | Raise for many independent keys per shard |
| `readBatchSize` | 500 | Rows per cursor read | Rarely |
| `ackBatchSize` / `ackFlushInterval` | 200 / 1 ms | Acknowledgement batching | Rarely |
| `holeExpiry` | 10 s | **UNORDERED lane only.** How long an uncommitted sequence value is chased | Must exceed your longest enqueue transaction |
| `chaseDelay` | 2 ms | **UNORDERED lane only** as hole-resolution latency. On the ordered lane it only throttles the watermark probe | Rarely |
| `maxHolesPerChase` | 1 000 | **UNORDERED lane only.** Holes resolved per chase query | Rarely |
| `watermarkCap` | 60 s | **ORDERED lane only.** How long one long-running *write* transaction may pin the lane before the cursor is forced past it — which can skip that transaction's messages | Leave it. It is an escape hatch, not a tuning knob; if it fires, fix the long transaction |
| `leaseTtl` | 30 s | How long a shard stays unserved if its owner dies without releasing. Heartbeat renews at a third of it | Lower for faster failover, but not below your worst stop-the-world pause, and **well above your slowest handler**: if an instance loses liveness while a handler runs longer than the rest of the lease, a successor starts the same key beside it. `ShardOwnerMetrics.handlersOutlastingLease` counts handlers that ran longer than `leaseTtl`, with a WARN at most once a minute |
| `shedGrace` | 5 s | How long an ordered shard waits to drain before abandoning a hand-over (retried every heartbeat; once a key has been in its handler longer than `stuckHandlerTimeout`, the hand-over goes ahead with every key still in a handler), and how long `stop()` waits for handlers in flight | Raise if handlers are slow and rebalancing stalls, or if `stop()` keeps ordered units |
| `stuckHandlerTimeout` | 15 min | How long a message may be in its handler before the queue treats the handler as stuck and stops waiting for it. Applied to the three waits for an ordered unit's handler: a unit a `stop()` kept because a key was still in its handler (past it a restart of the queue, or a `consume()` wanting that subscription's instance id, takes the unit over under a new fence), a fair-share shed, and the hand-back of a unit whose pump died (past it both release the unit with every key still in a handler — the one past the bound and any other still running — one WARN each). Each time it stops waiting, the message in that handler is charged an attempt, and dead-lettered if that brings it to `maxAttempts`; otherwise the next owner delivers the message of each key still in a handler again, possibly beside its handler, and **those keys may run in two handlers at once**. The unordered lane waits for no handler, but a message in a handler longer than this when its shard is released (shed, dead-pump hand-back, `stop()`) is charged an attempt first, and dead-lettered at `maxAttempts`, with one WARN per shard that charged. Must be positive; no off switch | **New in 0.60.1, and a behaviour change**: in 0.60.0 a restart (or that `consume()`) took the kept unit back at once; now it waits for the handler up to this bound, then takes the unit over. A fair-share shed, which waited for the handler however long it ran, now releases the unit past this bound; the dead-pump hand-back, new in 0.60.1, does the same. Raise it, for the queue or for all, if handlers legitimately run longer than 15 minutes. Per queue: `settings.withStuckHandlerTimeout(...)` on that queue's `PostgresqlMessageQueueBuilder.setSettings`, or `essentials.shard-owned-queue.stuck-handler-timeouts.<queue>` in the starter (`stuck-handler-timeout` for every queue) |

`watermarkCap` and `holeExpiry` answer the same question for different lanes and are deliberately
separate settings with an order of magnitude between them. The ordered lane resolves a gap *exactly*
(it waits for the writing transactions to finish), so waiting is free and the cap can be generous.
The unordered lane *chases* each unresolved value with a query, so `holeExpiry` is held low by cost.
`leaseTtl` was once derived as `holeExpiry × 3`; it is now its own setting, because a short
`holeExpiry` was leasing shards for less time than the heartbeat needed to renew them.

### `ConsumerOptions` — per consumer

| Setting | Default | Notes |
|---|---|---|
| `parallelConsumers` | 8 | Handlers in flight **for this consumer**. Same meaning as `ConsumeFromQueue.parallelConsumers` |
| `maxShards` | unbounded | Cap on shards this instance holds |
| `maxAttempts` | 3 | Deliveries before a dead letter, counting the first |
| `retryBackoff` | `RetryBackoff.exponential(100 ms, 2.0, 30 s)` | Wait before each retry: `delayAfter(attemptsSoFar)`, where the first retry passes 1. `RetryBackoff.fixed(delay)`, `RetryBackoff.exponential(initial, multiplier, max)`, or any function |

Through the `DurableQueues` adapter these come from the `ConsumeFromQueue`'s `RedeliveryPolicy`:
`maxAttempts = maximumNumberOfRedeliveries + 1`, and the backoff delegates to the policy's own
`calculateNextRedeliveryDelay`, so exponential, linear and fixed policies wait exactly as they do on
`postgresql-queue`.

**What counts as a failure.** Any `RuntimeException`, and any non-critical `Error` such as an `AssertionError`,
is a failed attempt that runs through the policy. Critical errors (`VirtualMachineError`, `LinkageError`) propagate
uncounted, as they do on `postgresql-queue`. To deliver a message again later without it being a failure, throw
`spi.RedeliveryRequested(delay)`. The message comes back after `delay`. The request is not an attempt: it does not
raise the redelivery count or use up the policy's budget, and it never dead-letters the message, also not at a
takeover. This differs from `postgresql-queue`, where a manual redelivery counts. Through the adapter, `QueuedMessage.markForRedeliveryIn(delay)` does exactly this.

**A failure is recorded only under the owner's lease fence.** An owner that has lost a shard does not
schedule the retry or dead-letter the message; the next owner redelivers it.

**Attempts survive a change of owner.** A message that failed under one instance continues from its stored count
under the next. A clean handover (the previous owner stopped and released the shard) charges nothing. A takeover
after a crash or a lost lease charges one attempt to the few rows the previous owner could have had in flight, and a
message that has taken its owner down `maxAttempts` times is dead-lettered with an error saying no failure was ever
recorded for it. An ordered unit released with a message still in a handler that has not returned - by a shed or a
dead-pump hand-back past `stuckHandlerTimeout`, or on request - is released, so its next owner charges nothing: the
releasing owner charges that message its attempt before the release instead, and dead-letters it at `maxAttempts`,
so a message whose handler hangs on every delivery is dead-lettered too, once the queue has stopped waiting for it
`maxAttempts` times. An unordered shard is released without waiting for its handlers - by a fair-share shed or a
dead-pump hand-back at once, by `stop()` once `shedGrace` has run out - and each message in one of them longer than
`stuckHandlerTimeout` is charged its attempt the same way before the release, and dead-lettered at `maxAttempts`; one
in a handler for less is released uncharged. A hang in a handler of an owner that goes on serving its unit is not
bounded by this.

### What a handler is given

`handle(messageId, key, payload, payloadType)`. `key` is null on the unordered lane.

`messageId` is `(lane, shard, sequence)`, unique within this queue — not globally, because sequences
are per `(queue, shard)`, so `u-0-1` exists in every queue. Carry the queue name alongside it anywhere
it leaves the handler.

**A handler is NOT given the delivery attempt count, the enqueue or next-delivery timestamps, or the
last delivery error.** They are all on the row in the database; none is in the `SELECT` the delivery
path issues, and adding one widens a read that runs roughly twice per delivered message for data most
handlers never look at. The id is the exception because it costs nothing — the owner already knows its
lane and shard, and `seq` is already read.

If you need one of the others, ask for it by id: `MessageQueue.getMessage(messageId)` reads the full
row. That is a cost paid per lookup by the handler that wants it, rather than per message by everyone.

Through the `DurableQueues` adapter the same boundary appears as `QueuedMessage.getId()` working while
`getTotalDeliveryAttempts()` and the timestamp accessors raise `UnsupportedOperationException` — a
throw rather than a plausible-looking `0`, which would make attempt-keyed retry logic silently never
fire.

### `shardCount` — the UNORDERED lane's parallelism, set at registration

`shardCount` applies to the **unordered lane only**. The ordered lane routes on a fixed space of its
own (`ShardOwnedSchema.ORDERED_UNITS`, 64) that nothing configures — see the next section.

Shards are the unit of parallelism. A historic sweep on the ordered lane (500 keys, 2 ms handler,
`keyConcurrency` 8, interleaved arms) measured where the return sits:

| shards | % of peak | msg/s per shard | concurrency ceiling |
|---|---|---|---|
| 1 | 48% | 1 155 | 8 |
| 2 | 77% | 925 | 16 |
| **4** | **89%** | 538 | 32 |
| **8** | **95%** | 285 | 64 |
| 16 | 100% | 151 | 128 |

**The knee is at 4, and 8 buys 95% of what 16 does.** Return per shard collapses after 4 — the same
shape as `parallelConsumers`, and for the same reason: the ceiling is `shardCount x keyConcurrency`,
and past the point where that exceeds the work available, more shards buy idle cost and nothing else.
One shard is also the *least predictable* arm: 74% spread against 3% at eight, because everything
serialises through one owner.

**The knee moves with your workload.** 500 keys and a 2 ms handler; a handler that waits 200 ms or a
queue with 10 keys has a different answer.

#### The ordered lane has no shard count, and that is the point

It used to. A key's shard was `hash(key) mod shardCount`, so the number decided both where a key lives
and how many consumers could share the work — and changing it moved every key. `growShardCount`
therefore refused while the ordered lane held anything, and the only way to satisfy that was to stop
producing, which is an outage rather than a procedure. The count was frozen for the life of the queue
and had to be guessed correctly, once.

There is now nothing to guess. Ordered keys hash into a fixed 64-unit space; consumers own **units**,
and adding a consumer moves units rather than keys. Sixty-four is far past the useful instance count
(the knee above is at 4), so it is a ceiling nobody reaches rather than a number to size.

#### When does any of this affect me?

Almost never. The table is the whole answer:

| Operation | Shard count changes? | What you do |
|---|---|---|
| **Rolling redeploy** | no | nothing |
| **Scale up** | no | nothing — new pods register and take a share within a heartbeat |
| **Scale down / pod evicted** | no | nothing — a graceful stop drains in-flight handlers (up to `shedGrace`), releases its shards and deregisters immediately. An ordered unit whose key outlasts the grace is kept instead, and the instance stays registered: the unit is handed back (acknowledged, released) once that handler returns, and before then another instance can take it only once this instance's liveness lapses (`leaseTtl`) |
| **Crash** | no | nothing — shards move when the lease expires (`leaseTtl`, 30 s default) |
| **Grow an unordered queue** | yes | `growShardCount(...)`. That is all |
| **Grow an ordered queue** | n/a | nothing to grow — the routing space is fixed |

`growShardCount` no longer has an ordered-lane precondition: it can be called with ordered traffic in
flight, because the ordered lane does not read the number. It still refuses to **shrink** — messages in
the removed shards would be addressed by nobody.

**Redeploys and autoscaling never touch the shard count**, so they never open a window where
instances disagree about the modulus. `ShardOwnedAdminSurfaceIT` asserts a three-generation rolling
deploy with ordered traffic sees one shard count throughout, and `ShardOwnedOrderedRebalanceIT` /
`ShardOwnedMultiProcessIT` assert no key is ever in two handlers while shards move between instances.
The one exception is a handler the queue counts as stuck: once a key has been in its handler longer than
`stuckHandlerTimeout`, a shed releases the unit with every key still in a handler — the one past the bound
and any other still running — each of those messages is charged an attempt before the release, and
dead-lettered if that brings it to `maxAttempts`, and the next owner delivers the rest again, possibly
beside their handlers; the hand-back of a dead pump's unit does the same.

#### Each lane caps how many instances can consume it

`fairShare = ceil(units / liveInstances)` per lane, so **at most `units` instances can hold anything
for that lane** — `shardCount` for unordered, 64 for ordered. Eight unordered shards and twelve pods
means four pods consume nothing from that lane:

| unordered shards | instances | holding a shard | idle |
|---|---|---|---|
| 8 | 8 | 8 | 0 |
| 8 | 12 | 8 | **4** |
| 16 | 12 | 12 | 0 |

So the rule is **`shardCount` >= the most instances you will ever run**, which for an autoscaled
deployment means its maximum replica count — not its current one. The ordered lane needs no such rule.
The ceiling is reported as `maxInstances` on the **admin API's** queue status
(`ApiShardOwnedQueueStatus`), which derives it as `max(shardCount, orderedUnits)`. `QueueHealth`
itself carries the two ceilings separately — `shardCount()` and `orderedUnits()` — and no combined
accessor.

**Instance identity is `<hostname>:<pid>`** by default in the starter (`FencedLockManagerSettings.defaultLockManagerInstanceId()`, the fenced lock manager's default; the bare hostname before 0.60.1), overridable with `essentials.shard-owned-queue.instance-id`. Two processes sharing an id look like one instance, so each is allowed only half the shards — set it explicitly where `hostname:pid` is still not unique (containers that share a hostname and all run as pid 1). The pid changing on a restart is harmless: a gracefully stopped instance hands its units back, and a crashed one's units are taken once its membership row is older than `leaseTtl`. Since 0.60.1 the engine logs a WARN naming the property when it detects two live processes under one id (metric `sharedInstanceIdHeartbeats`); a restart is not reported.

**Autoscaling specifics.** Membership is a row per instance, refreshed every `leaseTtl / 3` and
counted live within `leaseTtl`. A gracefully stopped instance deregisters immediately, so scaling in
frees its shards and lets survivors take them at once — unless it kept an ordered unit because a key
outlasted `shedGrace`: it then stays registered and its row ages out with `leaseTtl`, and until it has,
survivors keep counting it in their fair share, so until then one already at its share takes none of the
units it released. The kept unit's lease is released once its handler has returned (the instance hands
it back then, and stays registered): a survivor below its share takes it on its next heartbeat, one at its
share only once the row has aged out, handed back or not. An instance that *crashes* is counted live
until its row goes stale, which is the same window its leases take to expire — that is the designed
failover time, not an extra cost. Rows for departed instances are pruned by the heartbeat.

#### ⚠️ Pick the ordered number for your future peak, not today's load

This is the asymmetry that should drive the decision, and the two lanes are not alike:

- **Unordered — err low.** Growing is one call to `ShardOwnedSchema.growShardCount(...)`. Running
  consumers pick it up on their next heartbeat; nothing to restart, nothing to pause. Start at 1–2
  and grow when you measure a reason.
- **Ordered — nothing to size.** The lane routes on a fixed 64-unit space, so there is no number to
  choose and no procedure to grow it. This used to be the asymmetry that mattered: growing required
  the lane to be empty, and the only way to empty it was to stop producing.

So the decision is the unordered lane's alone: **start at 1–2 and grow when you measure a reason.**

## Sizing and scaling

Everything below is a formula, not a guess — each is measured in `ShardOwnedMultiQueueCostIT`.

```
held connections   = pumpThreads + 1                    (per process, NOT per queue)
threads, idle      = pumpThreads + 2                    (pumps, listener, heartbeat)
threads, loaded    = the above + handlers in flight     (virtual threads)
idle queries/s     = ~0.1  x  owned shards              (once sweeps have backed off)
cursor reads       = ~2.0 per message delivered           (measured)
handlers in flight = sum of parallelConsumers across consumers
owned shards       = sum over queues of shardCount x lanes in use
```

Measured, one shared runtime, idle:

| queues × shards | connections | threads idle | threads loaded | idle queries/s |
|---|---|---|---|---|
| 5 × 4 | 3 | 6 | 19 | 0 |
| 25 × 8 | 3 | 4 | 19 | ~40 |
| 100 × 8 | 5 | 6 | 21 | ~160 |
| 300 × 8 | 5 | 6 | 21 | 481 |

## How to not kill the database

1. **Share one `ShardRuntime`.** This is the single most important line on the page. A runtime per queue costs five connections each — 100 queues once exhausted a 500-connection pool. The default does the right thing; only an explicitly-passed runtime can get this wrong.
2. **Size `max_connections` for the engine plus your handlers plus everything else.** The engine's own need is small and fixed (`pumpThreads + 1`), but a `parallelConsumers` of 8 across 25 consumers is up to 200 handlers each potentially wanting a pool connection. **The handlers, not the engine, are what will exhaust your pool.**
3. **Count your shards, not your queues.** Idle cost follows owned shards: ~0.1 queries/s each. 300 queues × 8 shards × 2 lanes = 4 800 owners = ~481 queries/s doing nothing. Acceptable; 10 000 queues at 16 shards would not be. Lower `shardCount` for quiet queues, or raise `maxSweepInterval`.
4. **`holeExpiry` must exceed your longest enqueue transaction — on the UNORDERED lane.** A gap in the sequence means an uncommitted transaction. Abandoning it too early means a message only the head sweep will find. The ordered lane has no equivalent requirement: it waits for the actual transactions rather than guessing on a timer, so a long enqueue delays that lane's cursor but cannot make it skip anything until `watermarkCap` (60 s) is exceeded.

## How to not kill the application

1. **`parallelConsumers` is per consumer and multiplies.** Eight is a starting point measured on one consumer with the whole machine; it is as often too high as too low. The current implementation makes this mandatory rather than defaulted, and callers there pick 1, 3 or 5.
2. **There is no process-wide ceiling — `parallelConsumers` is the whole story.** Budget it per consumer against what your handlers actually contend for. A process running eight consumers at 8 can have 64 handlers in flight, and if they each want a connection your pool needs to say so.
3. **Handlers run on virtual threads. Blocking is fine; `synchronized` around blocking I/O is not** — on JDK 21-23 that pins a carrier thread. Use `ReentrantLock`.
4. **A slow handler occupies a permit, not a thread.** Throughput degrades gracefully; it does not deadlock. But a handler that never returns holds its permit forever.

## Worked examples

**One busy queue, one process.** `shardCount` 8, defaults elsewhere. 3 connections, ~19 threads under load, `parallelConsumers` tuned by running the sweep against your own handler.

**Twenty-five queues, mixed traffic.** One shared runtime. `shardCount` 8 for the two busy queues, 1-2 for the rest — that alone takes owned shards from 400 to ~60. Defaults elsewhere: 3 connections, ~20 threads, negligible idle load. Set `parallelConsumers` per queue rather than globally.

**Three hundred queues.** One shared runtime, `pumpThreads` 4, `shardCount` 1-2 for anything not demonstrably busy. 5 connections, ~21 threads. Raise `maxSweepInterval` to 60 s if the idle query rate matters. Check the sum of your `parallelConsumers` against your handlers' connection pool before raising any of them.

## Measuring your own deployment

The defaults here were measured on one machine with a synthetic handler. Two benchmarks are provided to re-derive them:

```bash
# Threads, held connections, idle query rate at your scale
./mvnw verify -pl components/postgresql-queue-shard-owned -Dit.test=ShardOwnedMultiQueueCostIT \
  -Dcost.queues=300 -Dcost.shards=8 -Dcost.pumpThreads=4

# Where more parallelConsumers stops buying anything, for YOUR handler
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test=ShardOwnedConcurrencySweepIT -Dbenchmark.run=true -Dlab.pg.cpuset=4-7
```

The concurrency sweep uses a 2 ms handler. A handler that returns immediately is CPU-bound and its optimum is unrelated; one that waits 200 ms has a completely different knee. **Re-run it rather than trusting the default.**

## Is the queue being served?

`queue.health()` -> `QueueHealth(shardCount, unorderedOwned, orderedOwned, liveInstances)`.

**Alert on `unownedShards()`.** Depth tells you how much work is waiting; it cannot tell a queue
nobody is consuming from a queue that is merely busy, and those diverge only slowly. Zero in steady
state, briefly non-zero while shards move between instances, persistently non-zero when messages are
in shards nobody reads. It does **not** see a shard that is leased but not served — one whose takeover
keeps failing; see `essentials.queue.shards.awaiting.takeover` and `shardsAwaitingTakeover` below.

`liveInstances` below your running process count means colliding instance ids — two processes sharing
one id count as one instance and are each allowed half the shards.

Via Micrometer, `bindQueueHealth(queue, maxAge)` publishes:

| Meter | Read it for |
|---|---|
| `essentials.queue.shards.unowned` | **the alert** for shards nobody holds — messages in shards nobody is reading. Blind to a held shard that is not served (`essentials.queue.shards.awaiting.takeover` below) |
| `essentials.queue.shards.owned` (tag `lane`) | coverage per lane |
| `essentials.queue.instances` | scale events, and id collisions |
| `essentials.queue.depth` (tag `lane`) | backlog, via `bindQueueDepth` |
| `essentials.queue.keys.blocked` | ordered keys stopped behind a dead letter right now, via `bindQueueDepth` |
| `essentials.queue.deadletters.parked` | dead letters parked unhandled behind another on their key, right now, via `bindQueueDepth`. Already included in the `dead-letter` lane of `essentials.queue.depth` — do not add the two |

Both bindings are opt-in and cached, because a gauge is polled on every scrape and these are queries.

One gauge needs no binding, because it costs no query: `essentials.queue.shards.awaiting.takeover` is registered by the `MicrometerQueueObserver` constructor and fed by the `QueueObserver.takeoverStalled` callback. It counts shards **this instance** holds a lease on but does not serve because their takeover keeps failing — the blind spot of `shards.unowned`. **Alert on it above zero next to `shards.unowned`**; it is per instance, so aggregate it with `sum by (queue)`.

**In Spring Boot** the starter binds a `MicrometerQueueObserver` to every queue `ShardOwnedQueueFactory` builds whenever a `MeterRegistry` exists — tagged `queue=<name>` — so the event meters (`essentials.queue.enqueued`, `.delivery`, `.delivery.failures`, `.retries`, `.deadletters`, `.shard.ownership`) and the `essentials.queue.shards.awaiting.takeover` gauge need no code; that includes the queues the `DurableQueues` adapter runs on. The gauges stay opt-in:

```yaml
essentials:
  shard-owned-queue:
    metrics:
      enabled: true          # default; false turns every per-queue meter off
      depth-gauges: true     # depth per lane, blocked keys, parked dead letters
      health-gauges: true    # shards owned/unowned, live instances
      gauge-max-age: 10s     # scrapes inside the window share one query round
```

The opt-in gauges read the database, so every instance reports the same cluster-wide value: aggregate them with `max by (queue)`, not `sum`. Event meters, and `shards.awaiting.takeover`, are per instance and do sum.

**`ShardOwnerMetrics.deliveryPauses`** is another one to watch. An instance that has not been able
to confirm its own liveness within four fifths of `leaseTtl` stops dispatching until it can — by then the rest of the
cluster already considers its units takeable, so anything it delivered would be work a successor is
doing too. Nothing is lost and it resumes on its own at the next successful heartbeat; a non-zero
count says the database was unreachable or too slow for longer than the lease, which is the same
condition a partition without `socketTimeout` produces and the one a slow disk produces on every
instance at once.

**`ShardOwnerMetrics.surplusInstances`** answers the neighbouring question: how many instances the
lane's routing space could not give a unit to. A surplus instance holds nothing, delivers nothing and
reports no error, which looks exactly like an instance whose queue is quiet — so this is the only way
to tell. The engine also logs a warning naming the queue, the instance count and the space when the
condition starts, and another when it clears. Nothing is lost or reordered while it lasts, and it
resolves itself when the instance count drops; a value that *persists* means the queue was created
with a space too small for the deployment, and on the ordered lane that space cannot be changed
afterwards.

**`ShardOwnerMetrics.shardsAwaitingTakeover`** covers what `unownedShards` cannot: shards this instance
holds a lease on but does not serve, because taking them over keeps failing with the connection still
healthy (typically a trigger or constraint refusing the takeover's dead-letter move). The takeover is
retried every 200 ms with no limit and the lease is kept, so no other instance takes the shard and it
delivers nothing until the cause is fixed — a data-caused failure does not clear on its own. Alert on it
being above zero next to `unownedShards`; `takeoverFailures` counts the failed attempts. The engine logs
at most one ERROR a minute per subscription (one per queue unless the queue is consumed twice in one
process), with the cause, while any of its shards is stalled (a cause that fails one takeover usually fails
them all), every failed attempt at DEBUG, and an INFO for each shard when it recovers.
**Where to read it:** `ShardOwnerMetrics` is exposed only by the engine-level `ShardOwnedQueue.metrics()`;
`PostgresqlMessageQueue` keeps it private. Through `MessageQueue` — including the Spring starter and the
`DurableQueues` adapter — the same stall reaches `QueueObserver.takeoverStalled(lane, shard, stalled)`
(`true` once when it starts, `false` once when it ends; never per retry), which `MicrometerQueueObserver`
publishes as `essentials.queue.shards.awaiting.takeover`. Alert on that. The ERROR log (logger
`dk.trustworks.essentials.components.queue.shardowned.ShardPump`) names one shard and the cause, and counts the
subscription's other stalled shards.
`takeoverStalled` is the one callback whose exceptions the engine catches and logs, because it runs on the
pump thread.

Five more `ShardOwnerMetrics` cover a shard that is leased but delivers nothing for another reason, which
`unownedShards` cannot see either:

- **`pumpFailures`** counts delivery passes for a shard that failed with the connection still healthy (a
  statement the database refused, or an exception from the engine; not a handler failing, which is
  `handlerFailures`), and the checks deciding whether to serve the shard when they throw. The shard keeps
  its lease and its pass is retried 200 ms later with no limit, so a shard whose reads or acknowledgements
  are permanently refused delivers nothing while this rises by about five a second. Alert on its rate.
  Logged at ERROR with the stack trace at most once a minute per subscription (one per queue unless the queue
  is consumed twice in one process), naming one shard; every failure at DEBUG; when the shard's pass
  succeeds again, or, for a throwing check, when the checks next complete, an INFO if that run of failures
  logged the ERROR and a DEBUG otherwise, so a check that throws on and off does not flood INFO either.
- **`batchedReadFailures`** counts the ordered lane's per-queue batched reads that failed. The shards then
  read for themselves, so this alone stops nothing; when they cannot either, their failures count under
  `pumpFailures`. Logged at WARN at most once a minute per subscription (one per queue unless the queue is
  consumed twice in one process), naming the queue and the shards read for; DEBUG otherwise. On recovery an
  INFO if that run of failures logged the WARN, a DEBUG otherwise.
- **`pumpLoopFailures`** counts pump passes ended by something thrown outside the per-shard guards: the
  connection pool, the pump's own bookkeeping, or an owner's `parkDeadlineMillis`. An owner's failing
  delivery pass, takeover or selection checks (`leaseHeld`, `deliveryPermitted`, `needsAttention`) count
  under `pumpFailures` / `takeoverFailures` instead. The pump reconnects 200 ms later, reruns every shard's
  takeover and goes on serving. While a pool failure persists it delivers nothing for any shard it serves;
  while a `parkDeadlineMillis` keeps throwing, every pass that delivered nothing ends this way, so the pump
  reconnects and reruns every takeover about five times a second and new messages wait up to 200 ms.
  Logged at ERROR at most once a minute per pump.
- **`shardsOnDeadPumps`** counts shards on a pump thread that a critical error (`VirtualMachineError`,
  `LinkageError`) ended, until their leases are handed back. Nothing restarts the pump, so the queue's next
  heartbeat releases those leases (an ordered unit only once no key is in one of its handlers, or once one has
  been there longer than `stuckHandlerTimeout`) and another
  instance, or a live pump in this one, takes them over; what was handled but not acknowledged is redelivered.
  Alert on it staying above zero, next to `shardsAwaitingTakeover`. With no live pump left the instance
  acquires nothing, so its shards stay unowned (`QueueHealth.unownedShards`) until the `ShardRuntime` (or the
  application) restarts. The pump logs one ERROR naming itself and how many shards it hands back.
- **`keptUnitsInHandler()`** (a method, read live; also `snapshot()`'s `keptUnitsInHandler`) counts ordered
  units a `stop()` kept because a key was still in its handler, while that key is in its handler. Normally
  zero. While above zero the unit is unserved - no run of the queue takes it, and no other instance while
  this one is live - until the handler returns or outlasts `stuckHandlerTimeout`, or the unit is released on
  request (`MessageQueue.releaseKeptOrderedUnit`); a unit taken over at the
  bound still counts while its stuck handler runs on, and so does a unit a fair-share shed or a dead pump's
  hand-back released at the bound with a key still in a handler, and so does a unit released on request while
  its handler runs on. Alert on it staying above zero. Logged at WARN once
  the handler has run longer than `leaseTtl`, at most once a minute per unit, from logger
  `dk.trustworks.essentials.components.queue.shardowned.ShardOwnedQueue`.

**Where to read them:** only through `ShardOwnedQueue.metrics()`. `PostgresqlMessageQueue` keeps its metrics
private and no `QueueObserver` callback reports any of the five, so through `MessageQueue` (the Spring starter,
the `DurableQueues` adapter) the `ShardPump` and `ShardOwnedQueue` log lines above are the only signal. Alert on the ERROR lines from
logger `dk.trustworks.essentials.components.queue.shardowned.ShardPump`.

## Administrative API

`ShardOwnedQueuesApi` is the operator surface over the engine. Registry-scoped (every operation takes
a `QueueName`), authorised per principal, unchecked, payloads withheld by default.

```java
var api = new DefaultShardOwnedQueuesApi(securityProvider, queueFactory);  // factory IS a MessageQueues

api.getQueueNames(principal);                                  // every REGISTERED queue, not just this pod's
api.getQueueStatus(principal, QueueName.of("orders"));         // depth AND ownership, together
api.getMessage(principal, orders, MessageId.parse("u-3-1042"));
api.getDeadLetterMessages(principal, orders, 0, 100);
api.retryMessage(principal, orders, id, Duration.ZERO);
api.markAsDeadLetterMessage(principal, orders, id, "parked by hand");
api.resurrectDeadLetterMessage(principal, orders, id);
api.resurrectDeadLettersForKey(principal, orders, "account-7");   // the whole key, in key_order
api.releaseKeptOrderedUnit(principal, orders, "node-1", 17);   // a unit a stop of instance node-1 kept behind a hung handler; ask that instance
api.deleteMessage(principal, orders, id);
api.purgeQueue(principal, orders);
```

**Message ids have a text form.** `MessageId.toString()` -> `u-3-1042` (`u`/`o` = lane, then shard,
then sequence); `MessageId.parse` reads it back. URL-safe without escaping, and the same triple the
tables use, so it can be pasted into psql.

**Roles**

| Operation | Role (or `ESSENTIALS_ADMIN`) |
|---|---|
| all reads | `QUEUE_READER` |
| seeing message payloads in those reads | `QUEUE_PAYLOAD_READER`, **additionally** |
| delete, retry, mark-as-dead-letter, resurrect, release a kept ordered unit, purge | `QUEUE_WRITER` |

A reader without the payload role still gets the message with `payload` **null** — null, not empty,
because an empty payload is a legal message. Non-UTF-8 payloads come back as hex, matching the
`*_readable` views.

**In Spring**, the API bean and its controller appear automatically when
`spring-boot-starter-admin-api` and an `EssentialsSecurityProvider` are on the classpath. That
dependency is `provided`, so a queue-only application gets neither the endpoints nor the event store
the admin API starter brings with it — the context starts fine without them.

Endpoints live under the admin API base path (default `/api/essentials/admin/v1`):

| Method | Path |
|---|---|
| `GET` | `/shard-owned-queues` |
| `GET` | `/shard-owned-queues/{queueName}/status` |
| `GET` | `/shard-owned-queues/{queueName}/messages/{messageId}` |
| `GET` | `/shard-owned-queues/{queueName}/dead-letter-messages?offset=&limit=` |
| `DELETE` | `/shard-owned-queues/{queueName}/messages/{messageId}` |
| `DELETE` | `/shard-owned-queues/{queueName}/messages` |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/retry` |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/mark-as-dead-letter` |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/resurrect` |
| `POST` | `/shard-owned-queues/{queueName}/ordered-units/{unit}/release-kept?instanceId=…` |

These endpoints **are** in the generated OpenAPI contract: `EssentialsAdminApiSpec` carries the
`shard-owned-queues` paths and `ShardOwnedQueuesController` sits in `spring-boot-starter-admin-api`
with every other admin controller. This section used to say the opposite, correctly at the time — a
published contract cannot describe an artifact that is in no repository. Publishing the engine removed
the obstacle.

## Gotchas

- **`SessionScope.KEY` does not exist here** and cannot: per-key exclusivity lives in the owner's memory, and a second party could only enter it by adding a query per message to the ordered fast path. Use `SHARD`.
- **MESSAGE / BATCH pull sessions are unordered-lane only.** A row lease cannot retract a row the owner already read, so a message can reach both a session and a push consumer — a duplicate, which at-least-once permits and ordering does not.
- **Interceptors change things; observers watch them.** `queue.addInterceptor(...)` wraps `EnqueueMessages` (replace the batch, or refuse) and `HandleMessage` (wrap, or skip the handler). An interceptor that throws fails the operation; an observer that throws does not. Both must be attached before `consume`. In Spring, declare either as a bean and the starter attaches it to every queue.
- **`DurableQueuesInterceptor` is a separate chain, and works too.** Running on the adapter, `durableQueues.addInterceptor(...)` is honoured — the adapter runs it around its own operations, so an interceptor written for `PostgresqlDurableQueues` behaves the same here, and Spring's `DurableQueuesInterceptor` beans are applied to it. Two things to know: `getNextMessageReadyForDelivery` is never intercepted because the adapter refuses it, and a `HandleQueuedMessage` interceptor is handed the delivery-path message, whose `getId()` and `getTotalDeliveryAttempts()` throw — the queue name, payload and metadata are all there.
- **Not proceeding on `HandleMessage` acknowledges the message** — the handler is skipped and the message is gone. Useful as a kill switch, indistinguishable afterwards from having processed it.
- **`payloadType` is yours, not the engine's.** An `int` you define, stored and handed back to the handler (and on `PulledMessage` / `DeadLetter`). Never compared, indexed or interpreted; there is no registry mapping it to a type name, so keeping two services' mappings in step is your job.
- **The contract is at-least-once.** A shard moving mid-flight legitimately redelivers. Do not assert exactly-once.
- **A `stop()` that gives up waiting returns before the queue has let go.** `stop()` waits for a `consume()`/`start()` still doing its database work at most 2 s on `ShardOwnedQueue` (about 4 s on `PostgresqlMessageQueue`, whose leasing lane's own stop waits too), on an interrupted thread not at all, then returns with `isStarted()` false and logs a WARN: that start releases its units and deregisters the instance only once its statements return, which nothing bounds but the driver's `socketTimeout`. Until it does, a later `start()`/`consume()` waits for it (one WARN after 2 s, then it keeps waiting), and closing the `DataSource` makes the release fail: the units stay unserved as after a crash, until this instance's membership row is older than `leaseTtl`. Stopping a `ShardRuntime` you passed to `setRuntime` meanwhile skips the drain instead (the release still succeeds): acknowledgements of handlers still running may be lost and those messages redelivered. Set `socketTimeout` on the pool.
- **A partly started `PostgresqlMessageQueue` is skipped by a manager that stops only what reports itself started.** After a queue `stop()`, a `Subscription.start()` or a later `consume()` runs that subscription while the queue's `isStarted()` reads false. `DefaultLifecycleManager` calls `stop()` at shutdown only on a `Lifecycle` reporting itself started, so a queue you register as a `Lifecycle` bean is then not stopped and the subscription goes on leasing units and handling messages. Stop the queue whatever `isStarted()` reads — `stop()` and `close()` stop every registered subscription, and `ShardOwnedQueueFactory.close()` does exactly that — or bring it back to started with the queue's own `start()` first. Reading `isStarted()` as "anything running" would fail the other way: a start gated on it would never restart the subscriptions the stop stopped. `Subscription.isStarted()` answers for one subscription.
- **`Subscription.close()` ends the subscription since 0.60.1.** It calls `unsubscribe()`; in 0.60.0 it called `stop()`. Code that closed a subscription and counted on the queue's `start()`, or the subscription's own `start()`, to bring it back now finds it gone: the queue's `start()` skips it and its `start()` throws `IllegalStateException`. Call `Subscription.stop()` to pause a subscription you mean to start again.
- **A `QueueObserver` calling the queue back from `shardOwnershipChanged` runs inside the call that told it.** A `consume()`, `start()` or `Subscription.start()` tells its acquisitions and the releases it causes, and a `stop()` its releases, on the caller's thread under the queue's lifecycle lock, so a call from the callback is nested in that one. Nothing starts there while that call is stopping consumers: a `stop()`'s releases; a start's callbacks once it owes a stop (the observer called `stop()`, or a stop elsewhere gave up waiting for it) or does the stop it owes; and a failed start's releases of the lanes it had started, which it stops. There `start()`/`Subscription.start()` start nothing, and `consume()` returns a subscription that is registered but **not started** — `isStarted()` false, consuming nothing until the queue's `start()` or its own runs after the outer call returns; each logs a WARN saying so — `Subscription.start()` even with its lanes running, as the outer call may stop them. One case gets no WARN: a queue `start()` finding the queue marked started while a failed `consume()` or `Subscription.start()` stops its lanes returns at once, as that call leaves the queue started. While a failed queue `start()` stops its lanes the queue reads started only until that start fails (bar a critical error), so a `start()` there logs the WARN. Anywhere else — an acquisition, or the release of a unit a lane's own failed start had adopted, told before that failure reaches the outer call — they start as usual, `consume()` adding a competing subscription under its own instance id. `stop()` from inside a start marks the queue not started and the outer call stops every consumer before it returns; from inside a `stop()` it adds nothing. `unsubscribe()` (or `close()`, which calls it) stops both lanes and leaves taking them out of the queue to the outer call; `Subscription.stop()` acts as from any thread. Releases from `Subscription.stop()`/`unsubscribe()` and a heartbeat's rebalances are told outside the lock, and calls from there behave as from any thread. Full table: `QueueObserver`'s class javadoc.
- **Ordering holds across processes**, which the current implementation's documentation says it does not — but only within a lane, per key, and only while the key's shard has one owner.
- **`enqueue` routes unordered messages round-robin and ordered messages by `mix(hash(key)) mod orderedUnits`.** The two lanes route on different numbers, which is why growing `shardCount` is safe with ordered traffic in flight: the ordered lane never reads it.
- **By-id operations exist**: `getMessage`, `deleteMessage`, `retryMessage`, `markAsDeadLetter`, `resurrect`. `MessageId` is `(lane, shard, seq)` which IS the primary key, so these are point lookups that cost delivery nothing. Acknowledging by id is still a `QueueSession` operation — the engine cannot know an arbitrary caller did the work.
- **A by-id write races a delivery in progress and cannot be made not to.** Whether a message is in a handler lives in the owner's memory. Deleting one the owner holds means the handler still completes; the engine tolerates the mismatched acknowledgement, but the handler did run.
- **Read payloads in psql through the views**, not the tables: `shard_queue_unordered_readable`, `shard_queue_ordered_readable`, `shard_queue_dead_letter_readable`. Queue name as a column, payload as text where it is valid UTF-8, hex otherwise.
- **`shardCount` can GROW**, via `ShardOwnedSchema.growShardCount(ds, name, n)`. It is the **unordered** lane's number.
  - **No ordered-lane precondition.** It can be called with ordered traffic in flight, because that lane routes on `orderedUnits` and never reads `shardCount`. It used to be refused while the ordered lane held anything, and that refusal could not be satisfied — nothing lets an operator quiesce producers.
  - **Shrinking is never allowed**: messages already in the removed shards would be addressed by nobody. Drain those shards and recreate the queue instead.
  - **No restart needed.** A running queue re-reads `shard_count` on each heartbeat and takes the new shards within one interval (`leaseTtl / 3`, 10s by default). A registry reporting *fewer* shards is ignored — dropping shards at runtime would strand what is in them.
  - **A one-heartbeat window remains where instances disagree about the count.** Harmless: routing is round-robin and every shard has an owner under either count.
- **`orderedUnits` cannot grow, and is the number to get right at registration.** `registerQueue(ds, name, shardCount, orderedUnits)` takes it; the default is 64. It caps how many instances can hold ordered units for that queue, and exceeding it degrades rather than fails — surplus instances hold nothing on that lane, and it recovers on its own when the instance count drops. Raising it for a queue that already holds data is deliberately not built; see `docs/durable-queue-shard-owned.md` §18.3.
- **Register by name, always.** `registerQueue(ds, QueueName.of("orders"), 8)` interns the name to a `short` and records the shard count with it. Building a queue from a name takes both from the registry, so two processes cannot disagree about the shard count — a disagreement routes the same key to different shards and strands whole shards.
- **`shardCount` cannot change once registered.** Re-registering with a different count is refused rather than accepted.
- **A queue name is unique; a message id is not.** `MessageId` is `(lane, shard, seq)` and sequences are per `(queue, shard)`, so `u-0-1` exists in every queue. Every admin operation therefore takes the queue name too, and there is no `getQueueNameFor(messageId)` — the question has no answer.
- **Published**, along with its adapter and starter — no `maven.deploy.skip` on any of the three; only the `examples/` modules carry one. The admin API's `EssentialsAdminApiSpec` entry exists, which publishing is what made possible.
