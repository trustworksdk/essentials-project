# PostgreSQL Shard-Owned Queue - LLM Reference

> Quick reference for LLMs. How the engine works: [docs/durable-queue-shard-owned.md](../docs/durable-queue-shard-owned.md). Every number quoted here: [docs/durable-queue-measurements.md](../docs/durable-queue-measurements.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.queue.shardowned`
- **Implementation**: `PostgresqlMessageQueue` implements `MessageQueue` — a **new SPI**, not `DurableQueues`
- **Storage**: three lanes (`shard_queue_unordered`, `shard_queue_ordered`, `shard_queue_dead_letter`) plus `shard_queue_lease`, `shard_queue_instance` and `shard_queue_registry`; `bytea` payloads
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
- [Administrative API](#administrative-api)
- [Gotchas](#gotchas)

## The one idea

Every message is assigned a shard at enqueue. Each shard has exactly one owning consumer at a time, held by a lease in `shard_queue_lease`. A consumer reads only shards it owns.

Consequences, all measured:
- **No claim write.** The current implementation writes `is_being_delivered = true` per message; this writes none, because a lease already says who owns it. `n_tup_upd` is zero.
- **Ordering is a consequence of ownership**, not of a query. Same key → same shard → one owner, whose in-memory set of keys in flight is the whole FIFO guarantee. It holds across processes.
- **Threads and connections are properties of the process**, not of the shard or queue count — but only if you share a `ShardRuntime` (see below).

## Getting started

```java
// 1. Schema, once per database
ShardOwnedSchema.create(dataSource, shardCount);

// 2. Once per queue, by NAME. Idempotent, so every process can call it at start-up.
//    The shard count is recorded WITH the name and refused if it ever disagrees.
var orders = ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);

// 3. ONE runtime for the whole process, shared by every queue
var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults());

// 4. A queue
var queue = ShardOwnedQueue.builder()
                           .setDataSource(dataSource)
                           .setQueueId(queueId)
                           .setShardCount(shardCount)
                           .setInstanceId(instanceId)
                           .setRuntime(runtime)
                           .build();
queue.configureUnordered(payload -> handle(payload),
                         ShardOwnerSettings.defaults(),
                         shardCount,
                         RedeliveryPolicy.fixed(Duration.ofMillis(100), 5));
queue.start();                       // Lifecycle: start()/stop()/isStarted()
```

Or through the SPI, which manages both lanes for you:

```java
try (var queue = PostgresqlMessageQueue.builder()
                                       .setDataSource(dataSource)
                                       .setQueueId(queueId)
                                       .setShardCount(shardCount)
                                       .setInstanceId(instanceId)
                                       .build()) {
    queue.enqueue(List.of(Message.of(payload, payloadType)));
    var subscription = queue.consume((key, payload, payloadType) -> handle(payload, payloadType),
                                     ConsumerOptions.defaults());
}
```

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

## Configuration reference

### `ShardOwnerSettings` — per engine, usually one instance for the process

| Setting | Default | What it costs | When to change |
|---|---|---|---|
| `pumpThreads` | 2 | **Held connections = `pumpThreads + 1`.** Also the pump thread count | Raise only if the pumps are the bottleneck; each one costs a permanent connection |
| `sweepInterval` | 500 ms | Backstop read cadence for a *busy* shard | Rarely |
| `maxSweepInterval` | 30 s | Cadence a shard backs off to while empty | Lower for faster recovery from a lost notification; raise for thousands of idle shards |
| `pollBackstop` | 500 ms | Park ceiling | Rarely |
| `keyConcurrency` | 8 | Concurrent keys per **ordered** shard | Raise for many independent keys per shard |
| `readBatchSize` | 500 | Rows per cursor read | Rarely |
| `ackBatchSize` / `ackFlushInterval` | 200 / 1 ms | Acknowledgement batching | Rarely |
| `holeExpiry` | 10 s | How long an uncommitted sequence value is chased | Must exceed your longest enqueue transaction |
| `leaseTtl` | 30 s | How long a shard stays unserved if its owner dies without releasing. Heartbeat renews at a third of it | Lower for faster failover, but not below your worst stop-the-world pause |
| `shedGrace` | 5 s | How long an ordered shard waits to drain before abandoning a hand-over | Raise if handlers are slow and rebalancing stalls |

### `ConsumerOptions` — per consumer

| Setting | Default | Notes |
|---|---|---|
| `parallelConsumers` | 8 | Handlers in flight **for this consumer**. Same meaning as `ConsumeFromQueue.parallelConsumers` |
| `maxShards` | unbounded | Cap on shards this instance holds |
| `maxAttempts` / `retryDelay` / `retryMultiplier` / `maxRetryDelay` | 3 / 100 ms / 2.0 / 30 s | Redelivery policy |

### `shardCount` — per queue, set at registration

Shards are the unit of parallelism **and** of ordering. Measured by `ShardOwnedShardCountSweepIT`
(ordered lane, 500 keys, 2 ms handler, `keyConcurrency` 8, interleaved arms):

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
Idle cost is ~0.1 queries/s per owned shard per lane.

One shard is also the *least predictable* arm: 74% spread against 3% at eight, because everything
serialises through one owner. If your ordered throughput matters, one shard is the wrong answer even
before the median is considered.

**The knee moves with your workload.** 500 keys and a 2 ms handler; a handler that waits 200 ms or a
queue with 10 keys has a different answer. Re-run the sweep rather than adopting the table.

#### When does any of this affect me?

Almost never. The table is the whole answer:

| Operation | Shard count changes? | What you do |
|---|---|---|
| **Rolling redeploy** | no | nothing |
| **Scale up** | no | nothing — new pods register and take a share within a heartbeat |
| **Scale down / pod evicted** | no | nothing — a graceful stop releases its shards and deregisters immediately |
| **Crash** | no | nothing — shards move when the lease expires (`leaseTtl`, 30 s default) |
| **Grow an unordered queue** | yes | `growShardCount(...)`. That is all — routing is round-robin, every shard has an owner either way |
| **Grow an ordered queue** | yes | the only case with a procedure — see below |

**Redeploys and autoscaling never touch the shard count**, so they never open a window where
instances disagree about the modulus. Every instance reads the same registry row; a queue built with
a stale count corrects itself from the registry within a heartbeat. `ShardOwnedAdminSurfaceIT`
asserts a three-generation rolling deploy with ordered traffic sees one shard count throughout, and
`ShardOwnedOrderedRebalanceIT` / `ShardOwnedMultiProcessIT` assert no key is ever in two handlers
while shards move between instances.

**Growing an ordered queue** is the one procedure, and it is rare by design — size the queue so it
does not happen (see below):

1. Stop producing to that queue, or pick a quiet moment.
2. Let the ordered lane drain. `growShardCount` refuses while it holds anything, so this checks itself.
3. `ShardOwnedSchema.growShardCount(dataSource, name, n)`.
4. Wait one heartbeat (`leaseTtl / 3`, 10 s by default) for every instance to pick it up.
5. Resume producing.

No deploy, no restart. Steps 1 and 4 exist because a key's shard is `hash(key) mod shardCount`, so
while instances disagree one key could be handled in two shards at once.

#### `shardCount` is a hard cap on how many instances can consume

`fairShare = ceil(shardCount / liveInstances)`, so **at most `shardCount` instances can hold anything
for that lane**. Eight shards and twelve pods means four pods consume nothing:

| shards | instances | holding a shard | idle |
|---|---|---|---|
| 8 | 8 | 8 | 0 |
| 8 | 12 | 8 | **4** |
| 16 | 12 | 12 | 0 |

So the rule is **`shardCount` >= the most instances you will ever run for that lane**, which for an
autoscaled deployment means its maximum replica count — not its current one. At ~0.1 queries/s per
idle shard that headroom is nearly free.

**Instance identity is the hostname** by default (`Network.hostName()`, as the fenced lock manager and scheduler use), overridable with `essentials.shard-owned-queue.instance-id`. Set it where one host runs several instances: two processes sharing an id look like one instance, so each is allowed only half the shards.

**Autoscaling specifics.** Membership is a row per instance, refreshed every `leaseTtl / 3` and
counted live within `leaseTtl`. A gracefully stopped instance deregisters immediately, so scaling in
frees its shards and lets survivors take them at once. An instance that *crashes* is counted live
until its row goes stale, which is the same window its leases take to expire — that is the designed
failover time, not an extra cost. Rows for departed instances are pruned by the heartbeat.

#### ⚠️ Pick the ordered number for your future peak, not today's load

This is the asymmetry that should drive the decision, and the two lanes are not alike:

- **Unordered — err low.** Growing is `ShardOwnedSchema.growShardCount(...)` plus a **rolling
  restart**. Under-provisioning is cheap to fix, so start at 1–2 and grow when you measure a reason.
- **Ordered — err high.** Growing requires the ordered lane to be **empty** and **every instance
  restarted together**, because a key's shard is `hash(key) mod shardCount` and two moduli in flight
  put one key under two owners. In practice that means **draining the queue and redeploying**, so
  under-provisioning is an outage-shaped fix rather than a config change.

Given ~0.1 queries/s per idle shard, over-provisioning an ordered queue is close to free and
under-provisioning is not. **8 is a defensible starting point for an ordered queue you expect to be
busy; 1–2 for one you do not.**

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
4. **`holeExpiry` must exceed your longest enqueue transaction.** A gap in the sequence means an uncommitted transaction. Abandoning it too early means a message only the head sweep will find.

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
in shards nobody reads.

`liveInstances` below your running process count means colliding instance ids — two processes sharing
one id count as one instance and are each allowed half the shards.

Via Micrometer, `bindQueueHealth(queue, maxAge)` publishes:

| Meter | Read it for |
|---|---|
| `essentials.queue.shards.unowned` | **the alert** — messages in shards nobody is reading |
| `essentials.queue.shards.owned` (tag `lane`) | coverage per lane |
| `essentials.queue.instances` | scale events, and id collisions |
| `essentials.queue.depth` (tag `lane`) | backlog, via `bindQueueDepth` |

Both bindings are opt-in and cached, because a gauge is polled on every scrape and these are queries.

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
| delete, retry, mark-as-dead-letter, resurrect, purge | `QUEUE_WRITER` |

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

**These endpoints are NOT in the generated OpenAPI contract**, and do not appear in the admin API's
start-up summary of served areas. The engine is unpublished, so it has no `EssentialsAdminApiSpec`
entry — a published contract cannot describe an artifact that is in no repository. They work; they are
just not declared.

## Gotchas

- **`SessionScope.KEY` does not exist here** and cannot: per-key exclusivity lives in the owner's memory, and a second party could only enter it by adding a query per message to the ordered fast path. Use `SHARD`.
- **MESSAGE / BATCH pull sessions are unordered-lane only.** A row lease cannot retract a row the owner already read, so a message can reach both a session and a push consumer — a duplicate, which at-least-once permits and ordering does not.
- **Interceptors change things; observers watch them.** `queue.addInterceptor(...)` wraps `EnqueueMessages` (replace the batch, or refuse) and `HandleMessage` (wrap, or skip the handler). An interceptor that throws fails the operation; an observer that throws does not. Both must be attached before `consume`. In Spring, declare either as a bean and the starter attaches it to every queue.
- **Not proceeding on `HandleMessage` acknowledges the message** — the handler is skipped and the message is gone. Useful as a kill switch, indistinguishable afterwards from having processed it.
- **`payloadType` is yours, not the engine's.** An `int` you define, stored and handed back to the handler (and on `PulledMessage` / `DeadLetter`). Never compared, indexed or interpreted; there is no registry mapping it to a type name, so keeping two services' mappings in step is your job.
- **The contract is at-least-once.** A shard moving mid-flight legitimately redelivers. Do not assert exactly-once.
- **Ordering holds across processes**, which the current implementation's documentation says it does not — but only within a lane, per key, and only while the key's shard has one owner.
- **`enqueue` routes unordered messages round-robin and ordered messages by `key.hashCode()`.** That is why growing `shardCount` is safe for the unordered lane and refused while the ordered lane holds messages.
- **By-id operations exist**: `getMessage`, `deleteMessage`, `retryMessage`, `markAsDeadLetter`, `resurrect`. `MessageId` is `(lane, shard, seq)` which IS the primary key, so these are point lookups that cost delivery nothing. Acknowledging by id is still a `QueueSession` operation — the engine cannot know an arbitrary caller did the work.
- **A by-id write races a delivery in progress and cannot be made not to.** Whether a message is in a handler lives in the owner's memory. Deleting one the owner holds means the handler still completes; the engine tolerates the mismatched acknowledgement, but the handler did run.
- **Read payloads in psql through the views**, not the tables: `shard_queue_unordered_readable`, `shard_queue_ordered_readable`, `shard_queue_dead_letter_readable`. Queue name as a column, payload as text where it is valid UTF-8, hex otherwise.
- **`shardCount` can GROW**, via `ShardOwnedSchema.growShardCount(ds, name, n)`.
  - Refused while the **ordered lane** holds anything: a key's shard is `hash(key) mod shardCount`, so changing the count sends a key's next message to a different shard from its last — one key, two owners, reordered.
  - **Shrinking is never allowed**: messages already in the removed shards would be addressed by nobody. Drain those shards and recreate the queue instead.
  - **No restart needed.** A running queue re-reads `shard_count` on each heartbeat and takes the new shards within one interval (`leaseTtl / 3`, 10s by default). A registry reporting *fewer* shards is ignored — dropping shards at runtime would strand what is in them.
  - **A one-heartbeat window remains where instances disagree about the modulus.** Harmless for unordered (round-robin, every shard owned). For ordered, keep producers paused across it — seconds, not a deployment.
- **Register by name, always.** `registerQueue(ds, QueueName.of("orders"), 8)` interns the name to a `short` and records the shard count with it. Building a queue from a name takes both from the registry, so two processes cannot disagree about the shard count — a disagreement routes the same key to different shards and strands whole shards.
- **`shardCount` cannot change once registered.** Re-registering with a different count is refused rather than accepted.
- **A queue name is unique; a message id is not.** `MessageId` is `(lane, shard, seq)` and sequences are per `(queue, shard)`, so `u-0-1` exists in every queue. Every admin operation therefore takes the queue name too, and there is no `getQueueNameFor(messageId)` — the question has no answer.
- **Not published.** `maven.deploy.skip=true`. The Spring Boot starter and interceptor chain exist; the admin API's `EssentialsAdminApiSpec` entry does not, and cannot until the engine is published.
