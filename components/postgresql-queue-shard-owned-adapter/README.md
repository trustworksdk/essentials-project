# Essentials Components - PostgreSQL Shard-Owned Queue Adapter

> **NOTE:** **The library is WORK-IN-PROGRESS**

Presents the [shard-owned queue engine](../postgresql-queue-shard-owned/README.md) as a
[`DurableQueues`](../foundation/README.md#durablequeues-messaging), so that `Inbox`, `Outbox`,
`DurableLocalCommandBus` and every `EventProcessor` run on it **without changing their own code**.

**LLM Context:** [LLM-postgresql-queue-shard-owned.md](../../LLM/LLM-postgresql-queue-shard-owned.md)
**Engine README:** [postgresql-queue-shard-owned](../postgresql-queue-shard-owned/README.md)
**Design & internals:** [docs/durable-queue-shard-owned.md](../../docs/durable-queue-shard-owned.md)

## Table of Contents
- [Overview](#overview)
- [Maven Dependency](#maven-dependency)
- [Getting Started](#getting-started)
- [Queue Registration](#queue-registration)
- [Transactional Behaviour](#transactional-behaviour)
- [Queue Entry Ids](#queue-entry-ids)
- [The Partial `QueuedMessage`](#the-partial-queuedmessage)
- [Redelivery](#redelivery)
- [Interceptors](#interceptors)
- [What It Does Not Serve](#what-it-does-not-serve)
- [Stored Format](#stored-format)
- [Reading Queue Counts Correctly](#reading-queue-counts-correctly)
- [Comparison with PostgresqlDurableQueues](#comparison-with-postgresqldurablequeues)
- ⚠️ [A delivery delay on an OrderedMessage reorders its key](#-a-delivery-delay-on-an-orderedmessage-reorders-its-key)
- [Gotchas](#gotchas)

## Overview

### Why this is a small adapter and not a rewrite

`Inbox`, `Outbox` and `DurableLocalCommandBus` are written against `DurableQueues`. Between them they
touch a handful of its ~30 methods — `queueMessage`, `queueMessages`, `consumeFromQueue`,
`purgeQueue`, `getTotalMessagesQueuedFor`, `getUnitOfWorkFactory` — and never
touch a `QueueEntryId`. Swapping the engine underneath them is therefore a small, well-defined
adapter.

The adapter serves considerably more than that handful: 19 of the 21 interceptable operations, including
everything the admin console's message browser and dead-letter pages need. **Two operations throw**,
each for a structural reason — see [What It Does Not Serve](#what-it-does-not-serve).

### What you get by switching

Everything the engine gives you, under the interface your application already uses:

| | `PostgresqlDurableQueues` | This adapter |
|---|---|---|
| Delivery path writes | `is_being_delivered = true` per message | none — a shard lease already says who owns it |
| Row locking | `FOR UPDATE SKIP LOCKED` | none |
| Ordering scope | per key, coordinated per consumer | per key, across processes, as a consequence of single ownership |
| Held connections | per consumer | `pumpThreads + 1` per process, fixed |

### Classes

| Class | Responsibility |
|---|---|
| `ShardOwnedDurableQueues` | The adapter. Builder-constructed |
| `ShardOwnedDurableQueuesBuilder` | `ShardOwnedDurableQueues.builder()` |
| `ShardOwnedDurableQueueConsumer` | `DurableQueueConsumer` over an engine `Subscription` |
| `ShardOwnedQueuedMessage` | `QueuedMessage`, in a full and a partial shape |
| `MessageEnvelope` | The persisted payload format |
| `QueueEntryIdCodec` | `QueueEntryId` ⟷ `(QueueName, MessageId)` |

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-queue-shard-owned-adapter</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Brings `postgresql-queue-shard-owned` and `foundation` with it. The PostgreSQL driver and JDBI are
`provided` — declare the ones your application already uses. JDBI is only reached to pull the JDBC
`Connection` out of a `HandleAwareUnitOfWork`, which is what makes a transactional enqueue
transactional.

Most applications should not depend on this module directly: depend on
[spring-boot-starter-postgresql-queue-shard-owned](../spring-boot-starter-postgresql-queue-shard-owned),
which has it as a hard dependency and selects it behind a flag.

## Getting Started

### Spring Boot — one property

```yaml
essentials:
  shard-owned-queue:
    durable-queues-enabled: true     # default false
    auto-register-shard-count: 4     # 0 refuses unknown queue names instead of registering them
```

That is the whole change. The starter contributes a `ShardOwnedDurableQueues` bean which **displaces**
the `PostgresqlDurableQueues` bean — it wins because `spring-boot-starter-postgresql` declares that
bean `@ConditionalOnMissingBean`, and this starter orders itself
`@AutoConfiguration(beforeName = "…EssentialsComponentsConfiguration")`.

Everything built on `DurableQueues` moves with it: `Inbox`, `Outbox`, `DurableLocalCommandBus`, every
`EventProcessor`'s inbox and every `ViewEventProcessor`'s queue. Your `DurableQueuesInterceptor` beans
are carried across and applied to the adapter.

**It is off by default and must stay off unless asked.** A starter on the classpath must not relocate
an application's delivery path.

### Manual wiring

```java
ShardOwnedSchema.initialize(dataSource);

var durableQueues = ShardOwnedDurableQueues.builder()
        .setQueues(messageQueues)              // a MessageQueues — the SAME one the rest of the process uses
        .setJsonSerializer(jsonSerializer)
        .setUnitOfWorkFactory(unitOfWorkFactory)   // optional; required for transactional enqueue
        .setDataSource(dataSource)                 // required unless autoRegisterShardCount is 0
        .setAutoRegisterShardCount(4)              // default; 0 refuses unknown names
        .build();

durableQueues.start();
```

| Builder option | Default | Notes |
|---|---|---|
| `queues` | required | Where queues are looked up by name. In Spring, the `ShardOwnedQueueFactory` bean |
| `jsonSerializer` | required | Serializes payloads and metadata into the stored envelope |
| `unitOfWorkFactory` | none | With a `HandleAwareUnitOfWork` in progress, enqueues commit with the caller's work. Without one, every enqueue commits on its own |
| `dataSource` | required unless `autoRegisterShardCount == 0` | Auto-registration writes through it |
| `autoRegisterShardCount` | `4` | Unordered shard count for a queue nobody registered. **0 refuses** |

**Pass the same `MessageQueues` the rest of the process uses.** Two registries in one process each
build their own `MessageQueue` per name, and the two register as two competing consumers — each
allowed half the shards.

## Queue Registration

`DurableQueues` invents a queue on first use, and under this adapter almost every queue an application
has is invented **by the framework, under a name the framework derives**:

| Component | Queue name |
|---|---|
| `EventProcessor` inbox | `Inbox:<processorName>` |
| `ViewEventProcessor` | `<processorName>:queue` |
| `Outbox` | `Outbox:<name>` |
| `DurableLocalCommandBus` | `DefaultCommandQueue` |

The engine will not invent a shard count — it is a property of the queue recorded in the registry, and
it caps horizontal scale. So the adapter registers unknown names itself, at
`autoRegisterShardCount` (default 4, the measured throughput knee).

**Why the default registers rather than refuses.** Refusing was the original default and it made the
adapter a non-starter: declaring those names up front means hard-coding three framework conventions.
What refusing protected against has also weakened — `shardCount` caps the *unordered* lane's instance
count, `growShardCount` raises it online with no restart, and the ordered lane does not read it at all.
Guessing low costs an instance ceiling until somebody raises it; refusing costs an inbox that silently
never consumes.

**`setAutoRegisterShardCount(0)` restores the refusal.** Right for an application that names all its
own queues and would rather a typo be an error than a registered queue nobody meant to create. Then
every queue an `Inbox`, `Outbox` or command bus invents must be registered before use, under exactly
the name the framework derives.

## Transactional Behaviour

### Handling and acknowledgement are separate transactions

Handler work and acknowledgement commit separately — the only model `DurableQueues` has had since
0.60 retired `TransactionalMode`. Shard-owned acknowledgements are batched and flushed on the owning
consumer's connection under a fence, and never enlist in a caller's transaction.
`Inboxes.handleMessage` opens its own `UnitOfWork` inside the handler, so nothing changes for it.

### Enqueue *is* transactional

This is the load-bearing behaviour, and it is preserved exactly. With a `HandleAwareUnitOfWork` in
progress, the messages are written on that unit of work's own connection, so they commit or roll back
with the caller's work:

```java
unitOfWorkFactory.usingUnitOfWork(uow -> {
    orderRepository.save(order);                 // same connection
    outbox.sendMessage(new OrderPlaced(order));  // same connection
});                                              // both, or neither
```

That is the Outbox's entire purpose. `an_outbox_enqueue_rolls_back_with_the_callers_transaction` was
verified to fail when that path is disabled — without it the Outbox leaves messages describing work
that rolled back.

## Queue Entry Ids

A `QueueEntryId` minted here looks like `orders:u-3-1042` — the queue name, a colon, and the engine's
`MessageId` (`u`/`o` = lane, shard, sequence).

**The queue name has to be inside the id.** Most of the by-id surface takes an entry id and nothing
else, which works for `PostgresqlDurableQueues` because its ids are UUIDs. A `MessageId` is unique per
queue only — `u-0-1` exists in every queue — so ignoring that would let `deleteMessage` delete an
unrelated queue's message and return `true`.

An id from a different `DurableQueues` implementation is rejected rather than half-understood.

**`InboxName.asQueueName()` is `Inbox:<name>`, so every queue this module exists to serve contains the
separator.** `decode` splits on the **last** colon — a `MessageId` never contains one.

### Browsing reports a stable order, not a chronological one

`getQueuedMessages` pages this queue's rows ordered by `(lane, shard, sequence)` — which *is* the
message id, so it satisfies the contract's "sort order for `getId()`" exactly. There is no global
arrival order to report; each shard carries its own sequence. What it guarantees is the property paging
needs: a row is never returned on two pages and never skipped between them.

`PostgresqlDurableQueues` answers the same call in id order, which is equally not arrival order. **Do
not present either engine's listing to an operator as "oldest first".**

## The Partial `QueuedMessage`

On the **delivery path** the engine hands over `(messageId, key, payload, payloadType)`. So a
`QueuedMessage` a handler or a `HandleQueuedMessage` interceptor receives is partial:

| Accessor | On the delivery path |
|---|---|
| `getId()` | **answered** |
| `getQueueName()`, `getMessage()`, payload, metadata | **answered** |
| `getTotalDeliveryAttempts()`, `getRedeliveryAttempts()` | **throws `UnsupportedOperationException`** |
| `getAddedTimestamp()`, `getNextDeliveryTimestamp()`, `getDeliveryTimestamp()` | **throws** |
| `getLastDeliveryError()` | **throws** |

The id costs the cursor read **nothing** — the owner already knows its lane and shard, and `seq` is
already a column it reads. The others would each add a column to a read that runs ~2× per delivered
message, for data most handlers never look at.

**They throw rather than returning `0`.** A stub `getTotalDeliveryAttempts()` of 0 would make
attempt-keyed retry logic silently never fire.

**The escape hatch:** a handler that needs one of them fetches it by id.

```java
durableQueues.getQueuedMessage(queuedMessage.getId())
             .ifPresent(full -> log.warn("attempt {}", full.getTotalDeliveryAttempts()));
```

A per-lookup cost paid by the handler that wants it, instead of a per-message cost paid by everyone.

> ⚠️ **The throw reaches callers through *log statements*.** `ViewEventProcessor` once passed
> `queuedMessage.getId()` as a log argument; arguments are eager, so it ran on every delivery whatever
> the level and dead-lettered every projection message. That specific failure is now impossible —
> `getId()` is answered — but the shape still applies to the attempt counts and the timestamps. When a
> framework class touches the partial surface, the failure looks like the framework being broken.

Messages returned by `getQueuedMessage`, `getQueuedMessages`, `getDeadLetterMessage(s)` are **full** —
the partial shape is the delivery path only.

## Redelivery

**Retry is the engine's, not `DefaultDurableQueueConsumer`'s.** That class implements redelivery
itself; this consumer does not use it. Running both would put two retry clocks on one message. The
`RedeliveryPolicy` you pass to `consumeFromQueue` is translated once into the engine's
`ConsumerOptions` at subscription time.

> Note the off-by-one: `RedeliveryPolicy` counts *re*deliveries, the engine counts attempts.

**`markForRedeliveryIn(delay)` redelivers, but not after `delay`.** The adapter turns it into a throw,
and the engine schedules from its own policy — there is no id to schedule against from inside a
handler. The attempt also counts against the policy's budget.

**`resurrectDeadLetterMessage` returns empty even on success.** The message re-enters at a fresh
sequence, so its `QueueEntryId` changes and the old one no longer addresses it.

## Interceptors

**`DurableQueuesInterceptor`s run here, in the adapter** — around its own methods, using the same
`InterceptorChain` machinery `PostgresqlDurableQueues` uses. An interceptor written against either
engine behaves the same way on both, and Spring's `DurableQueuesInterceptor` beans are applied.

```java
durableQueues.addInterceptor(new RecordExecutionTimeDurableQueueInterceptor(meterRegistry));
```

This does **not** bridge onto the engine's own `MessageQueueInterceptor` chain, and bridging would
have been the wrong shape: that chain carries two operations (`EnqueueMessages`, `HandleMessage`)
against this interface's twenty-one, so a bridge would have carried two and silently dropped nineteen.

**The two chains are independent and both may be used.** An interceptor registered on the
`MessageQueue` sees engine operations; one registered here sees `DurableQueues` operations.

Two consequences worth knowing before writing one:

- **`getNextMessageReadyForDelivery` is never intercepted**, because it throws. It is the one
  operation of the twenty-one an interceptor cannot observe here.
- **A `HandleQueuedMessage` interceptor receives the partial message.** One that reaches for the
  attempt count fails the delivery — see [above](#the-partial-queuedmessage).

**Each public method runs only its own chain.** `queueMessage` does not delegate to `queueMessages`,
`acknowledgeMessageAsHandled` does not delegate to `deleteMessage`, and so on — delegation would show
an interceptor two operations for one call, and a metrics interceptor would then report twice the
enqueues.

## What It Does Not Serve

Both throw `UnsupportedOperationException` **with their reason**. Neither is a placeholder for later
work; each is a structural difference between the two engines.

| Operation | Why |
|---|---|
| `queryForMessagesSoonReadyForDelivery` | Orders a queue by next-delivery timestamp across every shard of both lanes. No index produces it and there is no single sequence to merge on. A different question from paging by id, which `getQueuedMessages` answers |
| `getNextMessageReadyForDelivery` | Pulling one message needs a row-lease session that outlives the call. The engine has one (`MessageQueue.openSession`), but a session opened and abandoned per call would leave a lease on every message it returned |

Everything else is served, including the three that used to throw and should not have:
`getQueuedMessages` (the admin console's message browser is built on it),
`hasOrderedMessageQueuedForKey`, and `addInterceptor`/`removeInterceptor`.

## Stored Format

`MessageEnvelope` is a **persisted format from the first enqueue**. The engine's opaque `payloadType`
`int` carries `FORMAT_VERSION`, so a future format is distinguishable per row — it is the only field
outside the envelope, hence the only one readable before deciding how to parse.

Payload and metadata are nested JSON **strings**, which is why they appear escaped in the
`*_readable` views. Metadata travels as its own envelope field rather than merged into the payload; a
payload with its own `metaData` property would otherwise collide.

## Reading Queue Counts Correctly

`EventProcessor` and `ViewEventProcessor` forward differently, so `delivered` means opposite things on
their queues:

| Queue | Forwarding | What a non-zero count means |
|---|---|---|
| `Inbox:<processorName>` | `EventProcessor.forwardEventToInbox` enqueues unconditionally | the event rate. **Normal** |
| `<processorName>:queue` | `ViewEventProcessor.handlePersistedEvent` handles inline, queues only what it could not | an **error signal**. Zero is the healthy state |

It is self-reinforcing in both directions: nothing enters a `ViewEventProcessor` queue unless inline
handling throws, and once nothing is in it `hasOrderedMessageQueuedForKey` keeps answering false,
which keeps the inline path in use.

Measured on the demo: 2 254 account events, the projection current to within two seconds of the last
one, `delivered = 0`. **Reading that as a stalled projection is the obvious mistake.**

## Comparison with PostgresqlDurableQueues

| Aspect | [`PostgresqlDurableQueues`](../postgresql-queue/README.md) | `ShardOwnedDurableQueues` |
|---|---|---|
| **Transactional enqueue** | yes | yes |
| **`QueuedMessageCounts.numberOfMessagesBeingDelivered`** | counted | `null` — unknown, not zero ([below](#queue-statistics-in-flight-is-unknown)) |
| **`QueueEntryId`** | UUID | `<queueName>:<lane>-<shard>-<seq>` |
| **Delivery-path `QueuedMessage`** | full | partial — id, queue name and payload only |
| **Redelivery clock** | `DefaultDurableQueueConsumer` | the engine |
| **Unsupported operations** | none | 2 (both throwing, with reasons) |
| **Queue declaration** | implicit | auto-registered at `autoRegisterShardCount`, or declared |
| **Table names** | configurable → sanitize them | fixed constants |
| **`DurableQueuesInterceptor`** | supported | supported |
| **Ordering across processes** | a barrier in SQL on every fetch | a consequence of shard ownership |
| **A key behind a dead letter** | never advances; messages behind it stay queued | never advances; messages behind it are dead-lettered too |
| **`queueMessage(queue, OrderedMessage, delay)`** | the delay blocks the key | **the delay does not block the key** — see below |

### ⚠️ A delivery delay on an `OrderedMessage` reorders its key

`queueMessage(queueName, orderedMessage, deliveryDelay)` is the one call whose *ordering* differs
between the two engines. `PostgresqlDurableQueues` holds the whole key behind the delayed message —
its per-key barrier does not look at `next_delivery_ts`. This engine dispatches only messages that are
visible, so a later `order` for that key which is *not* delayed is delivered first.

Everything else about ordering now matches, including a key never advancing past a dead letter. This
one is left as it is deliberately, because fixing it means the owner holding not-yet-due rows in
memory and widening the read on the delivery path; the reasoning and what would reopen it are in
[docs/durable-queue-shard-owned.md](../../docs/durable-queue-shard-owned.md) §18.3.

**What to do:** keep an ordering key's messages either all delayed or all not, with the same delay. If
you need one message held back and the rest to wait for it, enqueue it undelayed and let the handler
decide when to act — or stay on `postgresql-queue` for that queue.

### Queue statistics: in flight is unknown

`getQueuedMessageCountsFor` — the cluster-wide half of the admin queue statistics — reports
`numberOfMessagesBeingDelivered` as **`null`, meaning unknown, never zero**. A shard's owner hands messages to
handlers from memory and writes nothing per delivery, so the queue storage cannot tell a message being handled from
one waiting. Reporting 0 would make every healthy, busy queue look stalled; the admin UI shows "unknown" instead.

`oldestReadyMessageTimestamp` is real and cluster-wide: the oldest `visible_at` of any message that has become ready
and is not held by a live pull session, computed in the same per-shard statements as the depth count. Because
deliveries are not recorded, it covers unacknowledged messages — including one an owner is handling right now, and in
the ordered lane one waiting behind its key's head. A key stopped behind a dead letter does not show here: the
messages behind it are dead-lettered too, so it appears in the dead-letter count.

## Gotchas

- **A delay on an `OrderedMessage` does not hold its key.** See above — the only ordering behaviour
  that still differs from `PostgresqlDurableQueues`.
- **The delivery-path `QueuedMessage` is partial** and three groups of accessors throw. Grep for
  `getTotalDeliveryAttempts`, `getAddedTimestamp` and `getLastDeliveryError` in your delivery paths
  before switching — including inside log statements, whose arguments are eager.
- **Pass the same `MessageQueues` the rest of the process uses**, or you get two competing consumers
  with half the shards each.
- **`setDataSource` is effectively required.** Auto-registration writes through it, and omitting it
  fails at `build()` with `autoRegisterShardCount needs a dataSource to register with` — not at the
  first unknown name.
- **With `autoRegisterShardCount = 0`, an unresolvable name throws from `resolve`.** For an `Inbox` in
  `SingleGlobalConsumer` mode that lands inside the fenced lock's `onLockAcquired` callback.
  `DBFencedLockManager` releases the lock when a callback throws, so the message is logged each tick
  and the inbox starts on its own once the queue is registered — no restart needed.
- **Two `queue` packages export `QueueName`, `Message` and `QueuedMessage`** — the engine's `spi`
  package and `foundation`'s messaging package. Star-importing both is a compile error, and it is the
  first thing that happens to anyone adding a file here.
- **The engine's own prerequisites still apply** — PostgreSQL 13+ for the ordered lane, a readable
  `pg_stat_activity.backend_xid`, `pumpThreads + 1` permanently held connections, and above all
  **`socketTimeout` on the DataSource**. See the
  [engine README](../postgresql-queue-shard-owned/README.md#prerequisites).
