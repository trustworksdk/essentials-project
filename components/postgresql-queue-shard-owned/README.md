# Essentials Components - PostgreSQL Shard-Owned Queue

> **NOTE:** **The library is WORK-IN-PROGRESS**

A PostgreSQL-backed durable message queue in which **every message belongs to a shard, and every shard
has exactly one owning consumer at a time**. Ownership is held as a lease, so the delivery path needs
no per-message claim write and no row lock.

This module implements the `MessageQueue` SPI it ships — it is **not** an implementation of
`DurableQueues`. To run `Inbox`, `Outbox` or `DurableLocalCommandBus` on this engine, add
[postgresql-queue-shard-owned-adapter](../postgresql-queue-shard-owned-adapter/README.md).

**LLM Context:** [LLM-postgresql-queue-shard-owned.md](../../LLM/LLM-postgresql-queue-shard-owned.md)
**Design & internals:** [docs/durable-queue-shard-owned.md](../../docs/durable-queue-shard-owned.md)
**Every number quoted here:** [docs/durable-queue-measurements.md](../../docs/durable-queue-measurements.md)

## Table of Contents
- [Overview](#overview)
- [Maven Dependency](#maven-dependency)
- [Prerequisites](#prerequisites)
- ⚠️ [Security](#security)
- [Getting Started](#getting-started)
- [The Two Lanes](#the-two-lanes)
- [Transactional Enqueue (Outbox)](#transactional-enqueue-outbox)
- [Pull Sessions](#pull-sessions)
- [Table Schema](#table-schema)
- [Configuration Reference](#configuration-reference)
- [Shard Count, Scaling and Autoscaling](#shard-count-scaling-and-autoscaling)
- [Sizing Formulas](#sizing-formulas)
- [Observability](#observability)
- [Interceptors and Observers](#interceptors-and-observers)
- [Administrative API](#administrative-api)
- [The Low-Level Engine](#the-low-level-engine)
- [Spring Boot](#spring-boot)
- [Comparison with postgresql-queue](#comparison-with-postgresql-queue)
- [Gotchas](#gotchas)

## Overview

### The one idea

Every message is assigned a shard when it is enqueued. Each shard has exactly one owning consumer at a
time, and that ownership is recorded as a lease in `shard_queue_lease`. A consumer reads only the
shards it owns.

Three consequences follow from that single rule:

- **No claim write on the delivery path.** `PostgresqlDurableQueues` marks each row
  `is_being_delivered = true` before handing it to a handler. This engine writes nothing — the lease
  already says who owns the message. Measured `n_tup_upd` on the delivery path is zero.
- **Ordering is a consequence of ownership, not of a query.** Same key → same shard → one owner, whose
  in-memory set of in-flight keys *is* the FIFO guarantee. It therefore holds across processes.
- **Threads and connections are properties of the process**, not of the queue or shard count — as long
  as a single `ShardRuntime` is shared (which is the default).

### What it gives you

| Capability | Notes |
|---|---|
| At-least-once delivery | A shard moving mid-flight legitimately redelivers. Never assert exactly-once |
| Ordered delivery per key | Across processes, within the ordered lane |
| Unordered (competing) delivery | Round-robin across shards |
| Delayed delivery | Server-side clock, not this node's |
| Transactional enqueue | On your own `Connection`; commits with your business write |
| Dead letters | With retry / resurrect / mark-as-dead-letter by id |
| Pull sessions | Row-lease based, unordered lane only |
| Redelivery policy | Per consumer, with backoff |
| Interceptors and observers | Enqueue and handle; Micrometer observer included |
| Administrative API | `ShardOwnedQueuesApi`, in the generated OpenAPI contract |

### Status

**Published**, and new — no production use yet. `MessageQueue` is a frozen contract: additive in a
minor, breaking only in a major.

**Intra-service only.** Multiple instances of *one* service against one database — like the rest of
Essentials' queues, locks and inbox/outbox. It is not a cross-service message broker.

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-queue-shard-owned</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

The PostgreSQL JDBC driver and Micrometer are `provided` — per the project-wide rule that third-party
integrations are not transitive, your application declares the driver and the connection pool it
actually uses. Micrometer is only needed if you use `MicrometerQueueObserver`.

## Prerequisites

Full detail and the failure modes: [docs/durable-queue-shard-owned.md](../../docs/durable-queue-shard-owned.md) §17.

| Requirement | Why |
|---|---|
| **PostgreSQL 13+** for the ordered lane (9.5+ for the unordered lane alone) | The floor comes from the ordered lane's start-up probe (`pg_current_xact_id()`), not from its delivery path |
| **`pg_stat_activity.backend_xid` must be readable** | The ordered lane's cursor proves a sequence value can never arrive from the set of running write transactions. A *partial* answer is a wrong answer — the cursor would step over a live writer and lose its messages silently |
| **`pumpThreads + 1` pool connections held permanently** (default 3) | Held for the life of the process and never returned. A pool below that floor does not fail cleanly: the engine starts and the remaining pumps block forever |
| **`LISTEN`/`NOTIFY` on one channel** | Blocked notifications (some poolers in transaction mode) cost latency, not correctness — delivery falls back to the sweep cadence, worst case `maxSweepInterval` |
| **No superuser, no replication slot, no `wal_level=logical`, no extensions** | — |

The engine probes for the `backend_xid` prerequisite at start-up (`verifyWatermarkPrerequisites`, the
first statement of the ordered lane's start, once per `DataSource`) and **refuses to start the lane**
rather than risk silent loss. Do not suppress it.

### ⚠️ Set `socketTimeout` on the DataSource

Not a hard requirement, and the single most consequential thing you can get wrong.

Measured across a real network partition: with `socketTimeout=3` a cut-off instance learns it has lost
the database in 3.3 s. Without one it had not learned within 90 s — and 90 s is where the measurement
stopped, not where the socket did. Nothing else saves it: the pool's `connectionTimeout` never fires,
because the heartbeat is blocked inside a read on a connection the pool still considers healthy.

The survivors are unaffected either way and take the shards over after one lease TTL. The cut-off
instance is the one that keeps delivering duplicates until its read returns. See
[docs/durable-queue-measurements.md](../../docs/durable-queue-measurements.md) §3.4.1.

## Security

### Table and column names are fixed, not configurable

Unlike [postgresql-queue](../postgresql-queue/README.md#security), this module exposes **no**
configurable table, column, index or function name. Every identifier is a compile-time constant on
`ShardOwnedSchema` (`shard_queue_unordered`, `shard_queue_ordered`, `shard_queue_dead_letter`,
`shard_queue_lease`, `shard_queue_instance`, `shard_queue_registry`, plus the `*_readable` views). The
only derived identifiers are per-queue sequence names, and they are derived from a `short` queue id
and an `int` shard number — both allocated by the engine, never from caller input.

There is consequently nothing for a caller to sanitize here, and no SQL-injection surface of the kind
the configurable-table-name components carry.

### What still is your responsibility

- **Queue names reach the database as bound parameters**, not as identifiers, so they cannot inject
  SQL. They *are* interned into the registry, so a queue name derived from untrusted input still lets
  an attacker create unbounded registry rows. Derive queue names in code.
- **Payloads are opaque `bytea`.** The engine never parses them. Whatever you serialize into a payload
  is deserialized by your handler, with your deserializer's trust assumptions.
- **`payloadType` is an `int` you define**, never compared, indexed or interpreted by the engine.
- **The administrative API withholds payloads by default** — see [Administrative API](#administrative-api).

## Getting Started

```java
// 1. Schema — once per database. Non-destructive and idempotent; safe on every boot.
ShardOwnedSchema.initialize(dataSource);

// 2. Register the queue by NAME, IN CODE — next to the component that owns it, not in a config
//    file. Idempotent, so every instance may call it at start-up. The shard count is recorded with
//    the name and refused if it ever disagrees.
ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 4);

// 3. A queue. PostgresqlMessageQueue is the entry point — ONE consume() covers BOTH lanes.
try (var orders = PostgresqlMessageQueue.builder()
                                        .setDataSource(dataSource)
                                        .setQueueName(QueueName.of("orders"))  // id + shard count from the registry
                                        .setInstanceId(Network.hostName())
                                        .build()) {

    // 4. Produce
    orders.enqueue(Message.of(payloadBytes, MY_PAYLOAD_TYPE));

    // 5. Consume. No start() needed — consume() starts what it needs.
    var subscription = orders.consume((messageId, key, payload, payloadType) -> handle(payload, payloadType),
                                      ConsumerOptions.defaults());
}
```

`ShardOwnedSchema.recreate(dataSource)` **drops everything** and exists for tests. Never call it from
an application.

### Registration is by name, always

`registerQueue` interns the name to a `short` queue id and records the shard count with it. Building a
queue from a name takes both from the registry, so two processes cannot disagree about the shard count
— a disagreement would route the same key to different shards and strand whole shards.

Re-registering a name with a *different* shard count is refused rather than accepted.

### Lifecycle

`PostgresqlMessageQueue` implements `Lifecycle` and `AutoCloseable`.

| Call | Effect |
|---|---|
| `consume(handler, options)` | Registers a consumer and starts it. Returns a `Subscription` |
| `start()` | Restarts consumers this queue created and then stopped. A queue that has never consumed has nothing to start |
| `stop()` | Stops the consumers, **releasing their shard leases** so a successor picks them up immediately rather than waiting the lease out. They stay registered, so `start()` brings them back |
| `close()` | `stop()` |

Enqueueing and reading depth never require the queue to be started — matching `DurableQueues`, where
the lifecycle governs the consuming side.

## The Two Lanes

**The lane is chosen by the message, not by the consumer.**

| Factory | Lane | Routing |
|---|---|---|
| `Message.of(payload, payloadType)` | unordered | round-robin across `shardCount` shards, one step per message |
| `Message.ordered(payload, payloadType, key, keyOrder)` | ordered | `mix(hash(key)) mod orderedUnits` |
| `Message.delayed(payload, payloadType, delay)` | unordered | as above, visible after `delay` |
| `Message.delayedOrdered(payload, payloadType, key, keyOrder, delay)` | ordered | as above, visible after `delay` |

The delay is applied by the **server**, so it does not depend on this node's clock.

One `consume()` serves both lanes. A **second** `consume()` on the same queue is therefore a separate
*competing consumer* with its own instance identity — **not** "the other lane". Registering one
consumer per lane is a mistake with two visible symptoms: unordered messages delivered to the ordered
handler, and one process reported as two live instances.

### What a handler is given

```java
(messageId, key, payload, payloadType) -> { ... }
```

`key` is `null` on the unordered lane.

`messageId` is `(lane, shard, sequence)` — unique **within this queue**, not globally, because
sequences are per `(queue, shard)`. `u-0-1` exists in every queue, so carry the queue name alongside
it anywhere the id leaves the handler.

**A handler is not given the delivery attempt count or the enqueue and next-delivery timestamps.**
They are all columns on the row; none is in the `SELECT` the delivery path issues, and adding one
widens a read that runs roughly twice per delivered message for data most handlers never look at. The
id is the exception because it costs nothing — the owner already knows its lane and shard, and `seq`
is already read.

If you need one of the others, ask for it by id:

```java
// QueuedMessage(id, key, payload, payloadType, attempts, enqueuedAt, visibleAt)
queue.getMessage(messageId).ifPresent(m -> log.warn("attempt {} of {}, enqueued {}",
                                                    m.attempts(), m.id(), m.enqueuedAt()));
```

The last delivery error is not on `QueuedMessage`; it is on `DeadLetter(id, key, payload, payloadType,
attempts, lastError)`, read through `deadLetters(offset, limit)`.

That is a cost paid per lookup by the handler that wants it, rather than per message by everyone.

### Ordering guarantee

Per key, within the ordered lane, and only while the key's unit has one owner — which is always,
except for the hand-over window a rebalance opens, during which the outgoing owner drains for up to
`shedGrace`. `ShardOwnedOrderedRebalanceIT` and `ShardOwnedMultiProcessIT` assert that no key is ever
in two handlers while units move between instances.

## Transactional Enqueue (Outbox)

Hand the engine your own connection and your commit decides:

```java
try (var connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);          // required: autocommit is refused
    orderRepository.save(order, connection);
    queue.enqueue(connection, List.of(Message.of(payload, payloadType)));
    connection.commit();                      // business write and enqueue land together
}
```

`enqueue(Connection, ...)` with autocommit on is refused rather than silently committed separately —
an autocommitted "transactional" enqueue is an Outbox that publishes work that rolled back.

## Pull Sessions

Where a push consumer does not fit, open a session and pull:

```java
try (var session = queue.openSession(SessionScope.MESSAGE, Duration.ofSeconds(30))) {
    var pulled = session.poll(100);
    for (var message : pulled) {
        try {
            handle(message.payload(), message.payloadType());
        } catch (Exception e) {
            session.fail(message.id(), e);
        }
    }
    session.acknowledge(pulled.stream().map(PulledMessage::id).toList());
}
```

| Scope | Meaning |
|---|---|
| `MESSAGE` | Row leases per message. **Unordered lane only** |
| `BATCH` | Row leases per polled batch. **Unordered lane only** |
| `SHARD` | The session takes shard ownership. Required for the ordered lane |

`PulledMessage` carries `(id, key, payload, payloadType, attempts)` — the pull path *does* report the
attempt count, because it reads the row rather than the delivery cursor.

`extendLease()` renews a session whose work outruns its lease duration.

**`SessionScope.KEY` does not exist and cannot.** Per-key exclusivity lives in the owner's memory; a
second party could only enter it by adding a query per message to the ordered fast path.

**`MESSAGE` and `BATCH` are unordered-lane only** for a related reason: a row lease cannot retract a
row the owner already read, so a message could reach both a session and a push consumer. That is a
duplicate, which at-least-once permits and ordering does not.

## Table Schema

Created by `ShardOwnedSchema.initialize(dataSource)`. Names are fixed.

| Object | Holds |
|---|---|
| `shard_queue_unordered` | Unordered-lane messages. PK `(queue_id, shard, seq)` |
| `shard_queue_ordered` | Ordered-lane messages. PK `(queue_id, shard, msg_key, key_order)` |
| `shard_queue_dead_letter` | Dead letters from both lanes |
| `shard_queue_lease` | Shard ownership — who owns what, until when |
| `shard_queue_instance` | Cluster membership, one row per live instance |
| `shard_queue_registry` | Queue name → `(queue_id, shard_count, ordered_units)` |
| `shard_queue_unordered_readable` / `_ordered_readable` / `_dead_letter_readable` | Views for psql: queue **name** as a column, payload as text where it is valid UTF-8, hex otherwise |

Payloads are `bytea`. **Read them in psql through the views, not the tables.**

## Configuration Reference

### `ShardOwnerSettings` — per engine, usually one instance per process

| Setting | Default | What it costs | When to change |
|---|---|---|---|
| `pumpThreads` | 2 | **Held connections = `pumpThreads + 1`.** Also the pump thread count | Raise only if the pumps are the bottleneck; each costs a permanent connection |
| `sweepInterval` | 500 ms | Backstop read cadence for a *busy* shard | Rarely |
| `maxSweepInterval` | 30 s | Cadence a shard backs off to while empty | Lower for faster recovery from a lost notification; raise for thousands of idle shards |
| `pollBackstop` | 500 ms | Park ceiling | Rarely |
| `keyConcurrency` | 8 | Concurrent keys per **ordered** shard | Raise for many independent keys per shard |
| `readBatchSize` | 500 | Rows per cursor read | Rarely |
| `ackBatchSize` / `ackFlushInterval` | 200 / 1 ms | Acknowledgement batching | Rarely |
| `holeExpiry` | 10 s | **Unordered lane only.** How long an uncommitted sequence value is chased | **Must exceed your longest enqueue transaction** |
| `chaseDelay` | 2 ms | **Unordered lane only** as hole-resolution latency. On the ordered lane it only throttles the watermark probe | Rarely |
| `maxHolesPerChase` | 1 000 | **Unordered lane only.** Holes resolved per chase query | Rarely |
| `watermarkCap` | 60 s | **Ordered lane only.** How long one long-running *write* transaction may pin the lane before the cursor is forced past it — which can skip that transaction's messages | Leave it. An escape hatch, not a tuning knob; if it fires, fix the long transaction |
| `leaseTtl` | 30 s | How long a shard stays unserved if its owner dies without releasing. The heartbeat renews at a third of it | Lower for faster failover, but not below your worst stop-the-world pause |
| `shedGrace` | 5 s | How long an ordered shard waits to drain before abandoning a hand-over | Raise if handlers are slow and rebalancing stalls |

`watermarkCap` and `holeExpiry` answer the same question for different lanes, and are deliberately
separate settings with an order of magnitude between them. The ordered lane resolves a gap *exactly*
— it waits for the writing transactions to finish — so waiting is free and the cap can be generous.
The unordered lane *chases* each unresolved value with a query, so `holeExpiry` is held low by cost.

### `ConsumerOptions` — per consumer

| Setting | Default | Notes |
|---|---|---|
| `parallelConsumers` | 8 | Handlers in flight **for this consumer**. Same meaning as `ConsumeFromQueue.parallelConsumers` |
| `maxShards` | unbounded | Cap on shards this instance holds |
| `maxAttempts` | 3 | Redelivery budget |
| `retryDelay` / `retryMultiplier` / `maxRetryDelay` | 100 ms / 2.0 / 30 s | Backoff |

### Instance identity

Defaults to the hostname (`Network.hostName()`, the same source the fenced lock manager and the
scheduler use). Set it explicitly where one host runs several instances: **two processes sharing an id
look like one instance**, so each is allowed half the shards.

## Shard Count, Scaling and Autoscaling

### `shardCount` applies to the unordered lane only

The ordered lane routes over a fixed space of its own — `ShardOwnedSchema.ORDERED_UNITS`, 64 — that
nothing configures.

Shards are the unit of parallelism. A sweep on the ordered lane (500 keys, 2 ms handler,
`keyConcurrency` 8) measured where the return sits:

| shards | % of peak | msg/s per shard | concurrency ceiling |
|---|---|---|---|
| 1 | 48% | 1 155 | 8 |
| 2 | 77% | 925 | 16 |
| **4** | **89%** | 538 | 32 |
| **8** | **95%** | 285 | 64 |
| 16 | 100% | 151 | 128 |

**The knee is at 4, and 8 buys 95% of what 16 does.** Return per shard collapses after 4 — the ceiling
is `shardCount × keyConcurrency`, and past the point where that exceeds the work available, more
shards buy idle cost and nothing else. One shard is also the *least predictable* arm: 74% spread
against 3% at eight, because everything serialises through one owner.

**The knee moves with your workload.** 500 keys and a 2 ms handler; a handler that waits 200 ms, or a
queue with 10 keys, has a different answer.

### Each lane caps how many instances can consume it

`fairShare = ceil(units / liveInstances)` per lane, so **at most `units` instances can hold anything
for that lane** — `shardCount` for unordered, 64 for ordered:

| unordered shards | instances | holding a shard | idle |
|---|---|---|---|
| 8 | 8 | 8 | 0 |
| 8 | 12 | 8 | **4** |
| 16 | 12 | 12 | 0 |

So the rule is **`shardCount` ≥ the most instances you will ever run** — for an autoscaled deployment,
its *maximum* replica count, not its current one. The ordered lane needs no such rule.

`QueueHealth` reports the two ceilings separately, as `shardCount()` and `orderedUnits()`. The admin
API's queue status combines them into `maxInstances` — `max(shardCount, orderedUnits)` — which is the
number to compare a replica count against.

### When does any of this affect me?

Almost never. The table is the whole answer:

| Operation | Shard count changes? | What you do |
|---|---|---|
| **Rolling redeploy** | no | nothing |
| **Scale up** | no | nothing — new pods register and take a share within a heartbeat |
| **Scale down / pod evicted** | no | nothing — a graceful stop releases its shards and deregisters immediately |
| **Crash** | no | nothing — shards move when the lease expires (`leaseTtl`, 30 s default) |
| **Grow an unordered queue** | yes | `ShardOwnedSchema.growShardCount(dataSource, name, n)`. That is all |
| **Grow an ordered queue** | n/a | nothing to grow — the routing space is fixed |

Redeploys and autoscaling never touch the shard count, so they never open a window where instances
disagree about the modulus. `ShardOwnedAdminSurfaceIT` asserts a three-generation rolling deploy with
ordered traffic in flight sees one shard count throughout.

### Growing

```java
ShardOwnedSchema.growShardCount(dataSource, QueueName.of("orders"), 16);
```

- **No restart needed.** A running queue re-reads `shard_count` on each heartbeat and takes the new
  shards within one interval (`leaseTtl / 3`, 10 s by default).
- **No ordered-lane precondition.** It may be called with ordered traffic in flight, because that lane
  routes on `orderedUnits` and never reads `shardCount`.
- **Shrinking is never allowed** — messages already in the removed shards would be addressed by
  nobody. Drain those shards and recreate the queue instead.
- A one-heartbeat window remains where instances disagree about the count. Harmless: routing is
  round-robin, and every shard has an owner under either count.

### Pick the unordered number low, and the ordered one not at all

- **Unordered — err low.** Growing is one call, online. Start at 1–2 and grow when you measure a
  reason.
- **Ordered — nothing to size.** `orderedUnits` defaults to 64, is set at registration
  (`registerQueue(ds, name, shardCount, orderedUnits)`), and **cannot grow**. 64 is far past the
  useful instance count, so it is a ceiling nobody reaches rather than a number to size. Exceeding it
  degrades rather than fails: surplus instances hold nothing on that lane and recover on their own
  when the instance count drops.

## Sizing Formulas

Each is measured in `ShardOwnedMultiQueueCostIT`, not estimated:

```
held connections   = pumpThreads + 1                    (per process, NOT per queue)
threads, idle      = pumpThreads + 2                    (pumps, listener, heartbeat)
threads, loaded    = the above + handlers in flight     (virtual threads)
idle queries/s     = ~0.1 × owned shards                (once sweeps have backed off)
cursor reads       = ~2.0 per message delivered
handlers in flight = sum of parallelConsumers across consumers
owned shards       = sum over queues of shardCount × lanes in use
```

Measured, one shared runtime, idle:

| queues × shards | connections | threads idle | threads loaded | idle queries/s |
|---|---|---|---|---|
| 5 × 4 | 3 | 6 | 19 | 0 |
| 25 × 8 | 3 | 4 | 19 | ~40 |
| 100 × 8 | 5 | 6 | 21 | ~160 |
| 300 × 8 | 5 | 6 | 21 | 481 |

### How to not kill the database

1. **Share one `ShardRuntime`.** The single most important line on this page. A runtime per queue
   costs five connections each — 100 queues once exhausted a 500-connection pool. The default does the
   right thing (a runtime is shared per `DataSource`, reference counted, closed by its last user);
   only an explicitly-passed runtime can get this wrong.
2. **Size `max_connections` for the engine plus your handlers plus everything else.** The engine's own
   need is small and fixed (`pumpThreads + 1`), but `parallelConsumers` of 8 across 25 consumers is up
   to 200 handlers each potentially wanting a pool connection. **The handlers, not the engine, are
   what will exhaust your pool.**
3. **Count your shards, not your queues.** Idle cost follows owned shards at ~0.1 queries/s each.
   300 queues × 8 shards × 2 lanes = 4 800 owners ≈ 481 queries/s doing nothing. Acceptable; 10 000
   queues at 16 shards would not be. Lower `shardCount` for quiet queues, or raise `maxSweepInterval`.
4. **`holeExpiry` must exceed your longest enqueue transaction — on the unordered lane.** A gap in the
   sequence means an uncommitted transaction; abandoning it too early leaves a message only the head
   sweep will find.

### How to not kill the application

1. **`parallelConsumers` is per consumer and multiplies.** Eight is a starting point measured on one
   consumer with the whole machine, and is as often too high as too low.
2. **There is no process-wide ceiling** — `parallelConsumers` is the whole story. Budget it per
   consumer against what your handlers actually contend for.
3. **Handlers run on virtual threads. Blocking is fine; `synchronized` around blocking I/O is not** —
   on JDK 21-23 that pins a carrier thread. Use `ReentrantLock`.
4. **A slow handler occupies a permit, not a thread.** Throughput degrades gracefully; it does not
   deadlock. But a handler that never returns holds its permit forever.

### Measuring your own deployment

```bash
# Threads, held connections, idle query rate at your scale
mvn verify -pl components/postgresql-queue-shard-owned -Dit.test=ShardOwnedMultiQueueCostIT \
  -Dcost.queues=300 -Dcost.shards=8 -Dcost.pumpThreads=4

# Where more parallelConsumers stops buying anything, for YOUR handler
taskset -c 0-3 mvn verify -pl examples/essentials-performance-lab \
  -Dit.test=ShardOwnedConcurrencySweepIT -Dbenchmark.run=true -Dlab.pg.cpuset=4-7
```

The concurrency sweep uses a 2 ms handler. A handler that returns immediately is CPU-bound and its
optimum is unrelated; one that waits 200 ms has a completely different knee. **Re-run it rather than
trusting the default.**

## Observability

### Is the queue being served?

```java
var health = queue.health();   // QueueHealth(shardCount, orderedUnits, unorderedOwned, orderedOwned, liveInstances)
var depth  = queue.depth();    // QueueDepth(unordered, ordered, deadLettered)
var stats  = queue.statistics();

if (!health.fullyOwned()) {
    alert("{} shards of '{}' have no live owner", health.unownedShards(), "orders");
}
```

**Alert on `unownedShards()`.** It counts each lane against its own total — `shardCount` for
unordered, `orderedUnits` for ordered. Depth tells you how much work is waiting; it cannot tell a queue nobody
is consuming from a queue that is merely busy, and those diverge only slowly. It is zero in steady
state, briefly non-zero while shards move between instances, and persistently non-zero when messages
sit in shards nobody reads.

`liveInstances` below your running process count means **colliding instance ids** — two processes
sharing one id count as one instance and are each allowed half the shards.

### Micrometer

```java
var observer = new MicrometerQueueObserver(meterRegistry, QueueName.of("orders"))
        .bindQueueHealth(queue, Duration.ofSeconds(10))
        .bindQueueDepth(queue, Duration.ofSeconds(10));
queue.addObserver(observer);
```

| Meter | Read it for |
|---|---|
| `essentials.queue.shards.unowned` | **the alert** — messages in shards nobody is reading |
| `essentials.queue.shards.owned` (tag `lane`) | coverage per lane |
| `essentials.queue.instances` | scale events, and id collisions |
| `essentials.queue.depth` (tag `lane`) | backlog |
| `essentials.queue.enqueued` / `.delivery` / `.delivery.failures` / `.retries` / `.deadletters` | throughput and failure rates |

Both bindings are **opt-in and cached**, because a gauge is polled on every scrape and these are
database queries — hence the `maxAge` argument.

### Two engine-level metrics worth watching

**`ShardOwnerMetrics.deliveryPauses`.** An instance that has not been able to confirm its own liveness
within `leaseTtl` stops dispatching until it can — by then the rest of the cluster already considers
its units takeable, so anything it delivered would be work a successor is doing too. Nothing is lost
and it resumes at the next successful heartbeat. A non-zero count says the database was unreachable or
too slow for longer than the lease.

**`ShardOwnerMetrics.surplusInstances`.** How many instances the lane's routing space could not give a
unit to. A surplus instance holds nothing, delivers nothing and reports no error — which looks exactly
like an instance whose queue is quiet, so this is the only way to tell. The engine also logs a warning
naming the queue, the instance count and the space when the condition starts, and another when it
clears. A value that *persists* means the queue was created with a space too small for the deployment.

## Interceptors and Observers

**Interceptors change things; observers watch them.** Both must be attached before `consume()`.

|  | `QueueObserver` | `MessageQueueInterceptor` |
|---|---|---|
| Position | told what happened | in the call path |
| Can change the outcome | no | yes — modify, or skip by not proceeding |
| If it throws | its own bug; the message is unaffected | the operation fails |
| Cost when none registered | an empty loop | nothing at all — the chain is not built |
| Typical use | metrics, tracing, logging | enrichment, filtering, multi-tenancy, kill switches |

```java
queue.addInterceptor(new MessageQueueInterceptor() {
    @Override
    public List<MessageId> intercept(EnqueueMessages operation,
                                     InterceptorChain<EnqueueMessages, List<MessageId>, MessageQueueInterceptor> chain) {
        operation.setMessages(enrich(operation.getMessages()));   // or don't proceed, and enqueue nothing
        return chain.proceed();
    }

    @Override
    public Void intercept(HandleMessage operation,
                          InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> chain) {
        return chain.proceed();   // NOT proceeding acknowledges the message
    }
});

queue.addObserver(myObserver);
```

- **Only those two operations are interceptable**, because they are the two that carry a message.
  `depth`, `purge`, `deadLetters` and `resurrect` are observable but not interceptable — nothing
  useful can be substituted for their results.
- **Ordering:** annotate with `@InterceptorOrder`; lower runs first, unannotated defaults to 10.
- **Not proceeding on `HandleMessage` acknowledges the message** — the handler is skipped and the
  message is gone. Useful as a kill switch, indistinguishable afterwards from having processed it.

## Administrative API

`ShardOwnedQueuesApi` is the operator surface. Registry-scoped (every operation takes a `QueueName`),
authorised per principal, unchecked, payloads withheld by default.

```java
var api = new DefaultShardOwnedQueuesApi(securityProvider, queues);  // any MessageQueues

api.getQueueNames(principal);                                  // every REGISTERED queue, not just this pod's
api.getQueueStatus(principal, QueueName.of("orders"));         // depth AND ownership, together
api.getMessage(principal, orders, MessageId.parse("u-3-1042"));
api.getDeadLetterMessages(principal, orders, 0, 100);
api.retryMessage(principal, orders, id, Duration.ZERO);
api.markAsDeadLetterMessage(principal, orders, id, "parked by hand");
api.resurrectDeadLetterMessage(principal, orders, id);
api.deleteMessage(principal, orders, id);
api.purgeQueue(principal, orders);
```

**Message ids have a text form.** `MessageId.toString()` → `u-3-1042` (`u`/`o` = lane, then shard, then
sequence); `MessageId.parse` reads it back. URL-safe without escaping, and the same triple the tables
use, so it can be pasted into psql.

### Roles

| Operation | Role (or `ESSENTIALS_ADMIN`) |
|---|---|
| all reads | `QUEUE_READER` |
| seeing message payloads in those reads | `QUEUE_PAYLOAD_READER`, **additionally** |
| delete, retry, mark-as-dead-letter, resurrect, purge | `QUEUE_WRITER` |

A reader without the payload role still gets the message, with `payload` **null** — null, not empty,
because an empty payload is a legal message. Non-UTF-8 payloads come back as hex, matching the
`*_readable` views.

### HTTP endpoints

In Spring, the API bean and its controller appear automatically when `spring-boot-starter-admin-api`
and an `EssentialsSecurityProvider` are on the classpath. Under the admin API base path (default
`/api/essentials/admin/v1`), and present in the generated OpenAPI contract:

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

## The Low-Level Engine

`ShardOwnedQueue` is the engine underneath `PostgresqlMessageQueue`, and a single instance consumes a
**single lane** — `configureUnordered` and `configureOrdered` are mutually exclusive, and the second
call is refused. Reach for it when you want exactly one lane and direct control of it; for both lanes
use `PostgresqlMessageQueue`, which builds the pair for you and shares one instance id between them so
the cluster counts them as one member.

```java
var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults());   // ONE per process

var queue = ShardOwnedQueue.builder()
                           .setDataSource(dataSource)
                           .setQueueId(queueId)
                           .setShardCount(shardCount)   // the UNORDERED lane's count; inert for ordered
                           .setInstanceId(instanceId)
                           .setRuntime(runtime)
                           .build();

queue.configureUnordered((messageId, payload, payloadType) -> handle(payload),   // OR configureOrdered, never both
                         ShardOwnerSettings.defaults(),
                         shardCount,
                         RedeliveryPolicy.fixed(Duration.ofMillis(100), 5));
queue.start();
```

`remaining()` and `shardsHeld()` on this object cover the configured lane only. Across both lanes,
`Subscription.shardsHeld()` sums the pair (`unorderedShardsHeld()` and `orderedUnitsHeld()` report them
separately), and the admin API's queue status reports each lane's depth and ownership separately.

**If you construct a `ShardOwnedQueue` without a runtime it borrows the one shared per `DataSource`**,
reference counted and closed by its last user. That is the safe default; pass your own only when you
deliberately want a separate set of threads and connections.

## Spring Boot

Use [spring-boot-starter-postgresql-queue-shard-owned](../spring-boot-starter-postgresql-queue-shard-owned).
It wires `ShardOwnerSettings`, one `ShardRuntime`, non-destructive schema initialization, and a
`ShardOwnedQueueFactory` that caches a `MessageQueue` per name:

```java
@Bean
ApplicationRunner registerQueues(ShardOwnedQueueFactory queues) {
    return args -> queues.register(QueueName.of("orders"), 4);   // by name, in code, idempotent
}
```

Settings come from `essentials.shard-owned-queue.*` — the `ShardOwnerSettings` table above, plus
`enabled`, `initialize-schema`, `instance-id`, and `durable-queues-enabled` (see the
[adapter](../postgresql-queue-shard-owned-adapter/README.md)).

There *is* an `essentials.shard-owned-queue.queues` map, and it is **not** the normal way to declare a
queue — it puts the name in two places that have to agree. Register in code, next to the component
that owns the queue.

## Comparison with postgresql-queue

| Aspect | [postgresql-queue](../postgresql-queue/README.md) | postgresql-queue-shard-owned |
|---|---|---|
| **Contract** | `DurableQueues` | `MessageQueue` (+ `DurableQueues` via the [adapter](../postgresql-queue-shard-owned-adapter/README.md)) |
| **Delivery path** | `FOR UPDATE SKIP LOCKED` + `is_being_delivered = true` per message | Shard lease. No lock, no claim write |
| **Ordering** | Per key, coordinated per consumer | Per key, as a consequence of single ownership — holds across processes |
| **Payload** | `JSONB` | `bytea`, opaque |
| **Table names** | Configurable → your responsibility to sanitize | Fixed constants → nothing to sanitize |
| **Notifications** | LISTEN/NOTIFY per table | LISTEN/NOTIFY on one global channel, payload `queueId:lane:shard` |
| **Scaling unit** | Consumer threads | Shards, leased to instances |
| **Queue declaration** | Implicit on first use | Registered by name, with a shard count |
| **Status** | Mature | Published, new — no production use yet |

## Gotchas

- **The contract is at-least-once.** A shard moving mid-flight legitimately redelivers. Do not assert
  exactly-once.
- **A queue name is unique; a message id is not.** `MessageId` is `(lane, shard, seq)` and sequences
  are per `(queue, shard)`, so `u-0-1` exists in every queue. Every admin operation therefore takes the
  queue name too, and there is no `getQueueNameFor(messageId)` — the question has no answer.
- **A second `consume()` is a second competing consumer**, not the second lane. One `consume()` serves
  both.
- **`payloadType` is yours, not the engine's.** An `int` you define, stored and handed back. Never
  compared, indexed or interpreted, and there is no registry mapping it to a type name — keeping two
  services' mappings in step is your job.
- **By-id operations are point lookups.** `getMessage`, `deleteMessage`, `retryMessage`,
  `markAsDeadLetter`, `resurrect` — `MessageId` *is* the primary key, so these cost delivery nothing.
  Acknowledging by id is still a `QueueSession` operation: the engine cannot know an arbitrary caller
  did the work.
- **A by-id write races a delivery in progress and cannot be made not to.** Whether a message is in a
  handler lives in the owner's memory. Deleting one the owner holds means the handler still completes;
  the engine tolerates the mismatched acknowledgement, but the handler did run.
- **`enqueue(Connection, …)` refuses autocommit.** Deliberately — see
  [Transactional Enqueue](#transactional-enqueue-outbox).
- **`ShardOwnedSchema.recreate` drops everything.** It is for tests. `initialize` is the one to call
  from an application, on every boot.
- **`shardCount` cannot shrink, and `orderedUnits` cannot change at all.** Both are recorded at
  registration; re-registering with a different `shardCount` is refused rather than accepted.
- **Read payloads in psql through the `*_readable` views**, not the tables.
- **Two `queue` packages export `QueueName`, `Message` and `QueuedMessage`** — this module's `spi`
  package and `foundation`'s messaging package. Star-importing both is a compile error; write those
  three out in full when mixing the two worlds.
