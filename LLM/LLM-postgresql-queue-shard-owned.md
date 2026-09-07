# PostgreSQL Shard-Owned Queue - LLM Reference

> Quick reference for LLMs. Design rationale and the full defect log: [docs/durable-queue-next-gen-design.md](../docs/durable-queue-next-gen-design.md). Every number quoted here: [docs/durable-queue-measurements.md](../docs/durable-queue-measurements.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.queue.shardowned`
- **Implementation**: `NextGenMessageQueue` implements `MessageQueue` — a **new SPI**, not `DurableQueues`
- **Storage**: three tables (`ng_unordered`, `ng_ordered`, `ng_dlq`), `bytea` payloads
- **Locking**: none on the delivery path — a shard lease establishes ownership, so there is no claim write
- **Notifications**: LISTEN/NOTIFY on one global channel, payload `queueId:lane:shard`
- **Dependencies**: PostgreSQL driver, Micrometer (both `provided`), `shared`
- **Status**: EXPERIMENTAL — builds and tests with the reactor, **not published** (`maven.deploy.skip=true`). No production use.

## TOC
- [The one idea](#the-one-idea)
- [Getting started](#getting-started)
- [Configuration reference](#configuration-reference)
- [Sizing and scaling](#sizing-and-scaling)
- [How to not kill the database](#how-to-not-kill-the-database)
- [How to not kill the application](#how-to-not-kill-the-application)
- [Worked examples](#worked-examples)
- [Measuring your own deployment](#measuring-your-own-deployment)
- [Gotchas](#gotchas)

## The one idea

Every message is assigned a shard at enqueue. Each shard has exactly one owning consumer at a time, held by a lease in `ng_shard_lease`. A consumer reads only shards it owns.

Consequences, all measured:
- **No claim write.** The current implementation writes `is_being_delivered = true` per message; this writes none, because a lease already says who owns it. `n_tup_upd` is zero.
- **Ordering is a consequence of ownership**, not of a query. Same key → same shard → one owner, whose in-memory set of keys in flight is the whole FIFO guarantee. It holds across processes.
- **Threads and connections are properties of the process**, not of the shard or queue count — but only if you share a `ShardRuntime` (see below).

## Getting started

```java
// 1. Schema, once per database
NextGenSchema.create(dataSource, shardCount);

// 2. Once per queue id
NextGenSchema.registerQueue(dataSource, queueId, shardCount);

// 3. ONE runtime for the whole process, shared by every queue
var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults());

// 4. A queue
var queue = new NextGenQueue(dataSource, queueId, shardCount, instanceId, runtime);
queue.configureUnordered(payload -> handle(payload),
                         ShardOwnerSettings.defaults(),
                         shardCount,
                         RedeliveryPolicy.fixed(Duration.ofMillis(100), 5));
queue.start();                       // Lifecycle: start()/stop()/isStarted()
```

Or through the SPI, which manages both lanes for you:

```java
try (var queue = new NextGenMessageQueue(dataSource, queueId, shardCount, instanceId)) {
    queue.enqueue(List.of(Message.of(payload, payloadType)));
    var subscription = queue.consume((key, payload) -> handle(payload),
                                     ConsumerOptions.defaults());
}
```

**If you construct a `NextGenQueue` without a runtime it borrows the one shared per `DataSource`**, reference counted, closed by its last user. That is the safe default; pass your own only when you deliberately want a separate set of threads and connections.

## Configuration reference

### `ShardOwnerSettings` — per engine, usually one instance for the process

| Setting | Default | What it costs | When to change |
|---|---|---|---|
| `pumpThreads` | 2 | **Held connections = `pumpThreads + 1`.** Also the pump thread count | Raise only if the pumps are the bottleneck; each one costs a permanent connection |
| `sweepInterval` | 500 ms | Backstop read cadence for a *busy* shard | Rarely |
| `maxSweepInterval` | 30 s | Cadence a shard backs off to while empty | Lower for faster recovery from a lost notification; raise for thousands of idle shards |
| `pollBackstop` | 500 ms | Park ceiling | Rarely |
| `keyConcurrency` | 8 | Concurrent keys per **ordered** shard | Raise for many independent keys per shard |
| `handlerConcurrency` | 512 | **Ceiling** on handlers in flight across the whole process | Size against what your handlers contend for |
| `readBatchSize` | 500 | Rows per cursor read | Rarely |
| `ackBatchSize` / `ackFlushInterval` | 200 / 1 ms | Acknowledgement batching | Rarely |
| `holeExpiry` | 10 s | How long an uncommitted sequence value is chased | Must exceed your longest enqueue transaction |
| `shedGrace` | 5 s | How long an ordered shard waits to drain before abandoning a hand-over | Raise if handlers are slow and rebalancing stalls |

### `ConsumerOptions` — per consumer

| Setting | Default | Notes |
|---|---|---|
| `parallelConsumers` | 8 | Handlers in flight **for this consumer**. Same meaning as `ConsumeFromQueue.parallelConsumers` |
| `maxShards` | unbounded | Cap on shards this instance holds |
| `maxAttempts` / `retryDelay` / `retryMultiplier` / `maxRetryDelay` | 3 / 100 ms / 2.0 / 30 s | Redelivery policy |

### `shardCount` — per queue, fixed at schema creation

Shards are the unit of parallelism **and** of ordering. More shards means more concurrent consumers and finer rebalancing; it also means more owners, and idle cost scales with owned shards rather than queues. **Do not give a low-traffic queue eight shards because a busy one has eight.**

## Sizing and scaling

Everything below is a formula, not a guess — each is measured in `NextGenMultiQueueCostIT`.

```
held connections   = pumpThreads + 1                    (per process, NOT per queue)
threads, idle      = pumpThreads + 2                    (pumps, listener, heartbeat)
threads, loaded    = the above + handlers in flight     (virtual threads)
idle queries/s     = ~0.1  x  owned shards              (once sweeps have backed off)
cursor reads       = ~2.0 per message delivered           (measured)
handlers in flight = min( sum of parallelConsumers , handlerConcurrency )
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
4. **`holeExpiry` must exceed your longest enqueue transaction.** A gap in the sequence means an uncommitted transaction. Abandoning it too early means a message only the head sweep will find.

## How to not kill the application

1. **`parallelConsumers` is per consumer and multiplies.** Eight is a starting point measured on one consumer with the whole machine; it is as often too high as too low. The current implementation makes this mandatory rather than defaulted, and callers there pick 1, 3 or 5.
2. **`handlerConcurrency` is the backstop, not the knob.** It stops the *sum* of everyone's ambitions swamping a shared resource. Set it to roughly the size of whatever your handlers contend for.
3. **Handlers run on virtual threads. Blocking is fine; `synchronized` around blocking I/O is not** — on JDK 21-23 that pins a carrier thread. Use `ReentrantLock`.
4. **A slow handler occupies a permit, not a thread.** Throughput degrades gracefully; it does not deadlock. But a handler that never returns holds its permit forever.

## Worked examples

**One busy queue, one process.** `shardCount` 8, defaults elsewhere. 3 connections, ~19 threads under load, `parallelConsumers` tuned by running the sweep against your own handler.

**Twenty-five queues, mixed traffic.** One shared runtime. `shardCount` 8 for the two busy queues, 1-2 for the rest — that alone takes owned shards from 400 to ~60. Defaults elsewhere: 3 connections, ~20 threads, negligible idle load. Set `parallelConsumers` per queue rather than globally.

**Three hundred queues.** One shared runtime, `pumpThreads` 4, `shardCount` 1-2 for anything not demonstrably busy. 5 connections, ~21 threads. Raise `maxSweepInterval` to 60 s if the idle query rate matters. Check `handlerConcurrency` against your handlers' connection pool before raising any `parallelConsumers`.

## Measuring your own deployment

The defaults here were measured on one machine with a synthetic handler. Two benchmarks are provided to re-derive them:

```bash
# Threads, held connections, idle query rate at your scale
./mvnw verify -pl components/postgresql-queue-shard-owned -Dit.test=NextGenMultiQueueCostIT \
  -Dcost.queues=300 -Dcost.shards=8 -Dcost.pumpThreads=4

# Where more parallelConsumers stops buying anything, for YOUR handler
taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dit.test=NextGenConcurrencySweepIT -Dbenchmark.run=true -Dlab.pg.cpuset=4-7
```

The concurrency sweep uses a 2 ms handler. A handler that returns immediately is CPU-bound and its optimum is unrelated; one that waits 200 ms has a completely different knee. **Re-run it rather than trusting the default.**

## Gotchas

- **`SessionScope.KEY` does not exist here** and cannot: per-key exclusivity lives in the owner's memory, and a second party could only enter it by adding a query per message to the ordered fast path. Use `SHARD`.
- **MESSAGE / BATCH pull sessions are unordered-lane only.** A row lease cannot retract a row the owner already read, so a message can reach both a session and a push consumer — a duplicate, which at-least-once permits and ordering does not.
- **The contract is at-least-once.** A shard moving mid-flight legitimately redelivers. Do not assert exactly-once.
- **Ordering holds across processes**, which the current implementation's documentation says it does not — but only within a lane, per key, and only while the key's shard has one owner.
- **`enqueue` routes unordered messages round-robin and ordered messages by `key.hashCode()`.** Changing `shardCount` after messages exist re-routes keys and breaks ordering for in-flight work.
- **Not published.** `maven.deploy.skip=true`, no Spring Boot starter, no admin API, no interceptor chain, no transactional-outbox integration.
