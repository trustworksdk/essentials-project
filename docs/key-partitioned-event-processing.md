# EF-2 — Key-Partitioned Event Processing on the Shard-Owned Queue Engine

**Status:** design proposal, not implemented. No code has changed. Nothing in §8 is decided yet.
**Modules:** `components/postgresql-event-store` (package `…postgresql.processor`), small additive changes in
`components/foundation` (`DurableQueues`), `components/postgresql-queue-shard-owned(-adapter)` and
`spring-boot-starter-postgresql-event-store`.
**Driven by:** EF-2 in `examples/essentials-trading-demo/docs/EXPANSION_PLAN.md` § "Essentials features this plan
needs", and the measurements in "The ledger load scenario (phase 1)" at the end of
`examples/essentials-trading-demo/src/main/java/dk/trustworks/essentials/examples/trading/_demo_harness/CLAUDE.md`.

On the shard-owned engine, an `Inbox` in `GlobalCompetingConsumers` mode splits keys across instances and keeps
per-key order cluster-wide (2 instances, 0 conflicts, `liveInstances: 2`). `EventProcessor` and `ViewEventProcessor`
never use that: each runs on one instance cluster-wide, and the demo's account views top out around 700–800 events/s.
They trail a 20 000-command run by 13–25 s. EF-2 asks for a processing mode where every instance handles a
processor's events, partitioned by aggregate id through the engine's ordered lane, instead of one lock holder doing
all of it.

**In short:** the exclusive part that is fundamental is the *reader*, meaning the event-store subscription with its
one resume point. The *handling* does not have to be exclusive. The recommended design (§4) keeps one exclusive reader
per processor and makes it a thin, **batched** forwarder into the shard-owned ordered lane. Handling runs on every
instance. It is a new class, not a behaviour change. Before building it, two things must be measured (§5). The first
is whether the reader alone is already the ceiling. If it is, competing consumers will not help without batching.
The second is that a configuration-only variant of the idea can be run on the demo today.

---

## 1. How processors consume today

### 1.1 Who subscribes, who holds which lock

| | `EventProcessor` | `ViewEventProcessor` | `InTransactionEventProcessor` |
|---|---|---|---|
| Subscription | One **exclusive** async subscription per `AggregateType` (`EventProcessor.java:311-358`, `exclusivelySubscribeToAggregateEventsAsynchronously` at `:314`) | One **non-exclusive** async subscription per `AggregateType`, started only inside the processor's own lock callback (`ViewEventProcessor.java:151-169`, `:184-219`) | In-transaction, exclusive or not per `useExclusively` (`InTransactionEventProcessor.java:312-318`) |
| Subscriber id | `<processorName>:<aggregateType>` (`AbstractEventProcessor.java:93-97`) | same | `<processorName>:<aggregateType>:sync` (`InTransactionEventProcessor.java:301`) |
| Lock(s) | One `FencedLock` **per subscription**, named `[<subscriberId>-<aggregateType>]` (`ExclusiveAsynchronousSubscription.java:80`), **plus** the inbox's own lock when the inbox is `SingleGlobalConsumer` (`Inboxes.java:270-289`), the default (`EventProcessor.java:429-431`) | One lock, `<processorName>:lock` (`ViewEventProcessor.java:150`), which gates both the subscriptions and the queue consumer | The subscription lock when exclusive |
| What the subscription thread does | Forwards an `EventReferenceOrderedMessage` (key = aggregate id, order = `EventOrder`) to the inbox (`EventProcessor.java:410-421`) | Handles the event **inline**. If the key already has queued work, or the handler fails, it queues instead (`ViewEventProcessor.java:262-289`) | Handles inside the appending transaction |
| Who runs the handler | Inbox consumer threads, `getNumberOfParallelInboxMessageConsumers()` (default **1**, `EventProcessor.java:439-441`), which re-read the event by reference (`AbstractEventProcessor.java:257-280`) | The subscription thread; the queue consumer only for retries | The appending thread |

`EventProcessor` already has two independent exclusive roles, the reader lock(s) and the inbox-consumer lock, and
they can land on different instances. `ViewEventProcessor` has one role. On the shard-owned engine its queue is
normally empty: everything is handled inline on the reader thread (adapter `CLAUDE.md:44-45`, "both `*:queue`
projections report `delivered = 0`").

### 1.2 Where resume points live

`durable_subscriptions`, primary key `(subscriber_id, aggregate_type)` (`PostgresqlDurableSubscriptionRepository.java:69`,
`:175`). One row per processor per aggregate type, so one cursor over the aggregate type's global event order. The
subscriber advances the in-memory resume point after each event's unit of work completes
(`PersistedEventSubscriber.java:403`). The manager persists it on a timer, every `snapshotResumePointsEvery` (default
1 s, demo 2 s; `DefaultEventStoreSubscriptionManager.java:274-276`, `:404`), and exactly on a graceful stop or lock
release (`ExclusiveAsynchronousSubscription.java:276`, `NonExclusiveAsynchronousSubscription.java:205`). The resume
point is also where gap handling lives: a gap fill is acknowledged in the unit of work that handled it
(`PersistedEventSubscriber.java:339-349`).

### 1.3 Failover

The lock moves. The new holder reads the persisted resume point and resubscribes
(`ExclusiveAsynchronousSubscription.java:120-148`). After a graceful release, nothing is replayed. After a crash,
everything since the last timer snapshot (≤ `snapshotResumePointsEvery`) is delivered again. Handlers must therefore
already be idempotent. In the views that is the "Version = EventOrder" guard (`LLM/LLM-postgresql-event-store.md:671`).
In the demo it is the `(account_stream_id, event_order)` key of `projection_account_activity` and the
`last_event_order` guards of the overview and positions projections.

### 1.4 How a projection reset works

`resetAllSubscriptions()` → `doResetSubscription` (`AbstractEventProcessor.java:372-392`) →
`EventStoreSubscription.resetFrom(...)`, which on the running subscription does stop → override and save the resume
point → callback → start (`NonExclusiveAsynchronousSubscription.java:225-245`). The callback purges the processor's
queue (`ViewEventProcessor.java:449-451`; the inbox for `EventProcessor`, `EventProcessor.java:382-384`) and calls
`onSubscriptionsReset`, where the demo's projections `TRUNCATE` their tables (e.g. `AccountActivityProjection.java:158-159`).
This is correct **because** exactly one instance handles events. Nothing else can be writing to the view between the
truncate and the restart.

### 1.5 Where the throughput ceiling comes from

The view pipeline today is: poll or CDC → `PersistedEventSubscriber.hookOnNext` → **one unit of work per event**
(`PersistedEventSubscriber.java:338-351`) → `hasOrderedMessageQueuedForKey` (a prefix seek on the ordered PK, adapter
`CLAUDE.md:44`) → deserialize → handler under a savepoint (`ViewEventProcessor.java:351-388`) → commit → next event.
All of it runs serially on one thread on one instance. CDC changes how events reach `pollEvents`
(`CdcEventStore.java:273`), not this per-event loop. The demo polls in pages of 5 (`application-compose.yml:79`), but
a page only bounds prefetch: each event still costs its own transaction.

At roughly 1.3 ms per event (begin, a seek, one or two writes, commit), that loop gives the measured 700–800 events/s.
That figure is an inference, not a measurement, and §5 M1 exists to confirm or refute it. Three things follow if it
holds:

- The ceiling is the **per-event transaction on a single thread**. Exclusivity matters only because it keeps that
  thread single.
- An `EventProcessor` has the same per-event loop on its reader (one `addMessageReceived` transaction per event,
  `Inboxes.java:348-364`), plus a second read per event on the consumer side (`AbstractEventProcessor.java:269`). Making
  its *consumers* competing does not raise the *reader's* rate.
- The three account views are three processors with three locks. With two instances they may already sit on
  different nodes. Each one is still a single thread.

### 1.6 Fundamental vs incidental

| Property | Fundamental or incidental | Why |
|---|---|---|
| One reader per `(subscriber, aggregateType)` | **Fundamental** (within this design) | One resume point is one cursor. Gap detection and acknowledgement are defined relative to that cursor. Two readers on one cursor would race the resume point |
| Handling on the reader thread (`ViewEventProcessor`) | Incidental | Chosen for latency. The queue path already exists for failures |
| One transaction per event | Incidental | `batchSubscribeToAggregateEventsAsynchronously` (`EventStoreSubscriptionManager.java:117-123`) gives one unit of work per batch, but only non-exclusively. `ViewEventProcessor` already pairs a non-exclusive subscription with its own lock, so the same pairing works here |
| Event re-read by reference in `EventProcessor` | Incidental | Keeps queue rows small. A payload-carrying message would avoid the read |
| Inbox consumer = one instance (`SingleGlobalConsumer`) | Incidental **on the shard-owned engine** | Required on `PostgresqlDurableQueues` for cluster-wide key order (`Inboxes.java:48-62`). The shard-owned engine gives that order by ownership (`docs/durable-queue-shard-owned.md` §1, §7) |
| Inbox consumer parallelism 1 | Incidental | A default (`EventProcessor.java:439-441`). The adapter passes it straight into `ConsumerOptions.parallelConsumers` (`ShardOwnedDurableQueueConsumer.java:181-187`) |
| View update and ack commit together | **Already not true** on the shard-owned engine | `ViewEventProcessor.java:138-140` relies on it. On this engine acks never enlist in the caller's transaction (adapter `CLAUDE.md:30`), so queued view handling is already at-least-once |

---

## 2. The problem, stated precisely

Reading the event log in order and keeping a resume point is naturally a job for one reader per processor. Handling
does not have to be. The EF-2 question is how to separate the two without giving up what the views depend on:

1. **Per-aggregate order.** Events of one aggregate are handled one at a time, in `EventOrder`.
2. **No lost events across failover, reset and rebalancing.** Duplicates are acceptable; losses are not.
3. **A reset leaves the view equal to a fresh replay.**

**Cross-aggregate order is lost in every partitioned option, and this note does not try to keep it.** Today a
`ViewEventProcessor` sees one aggregate type's events in subscription order (commit order under CDC, `docs/cdc.md` §1).
That is close to global order. Under partitioning, account A's event 7 may be handled after account B's event 3 even
though B's event committed later. Three kinds of handler break under that (§6). An `EventProcessor` whose inbox runs
more than one consumer thread has already lost cross-aggregate order today. A `ViewEventProcessor` has not, and that
is the class the measurements are about.

---

## 3. Options

### 3.1 Option (a) — one exclusive reader that forwards, handling on every instance

The reader keeps today's lock, subscriber id and resume point. It does nothing but turn events into ordered messages
(key = aggregate id, order = `EventOrder`) and enqueue them on the shard-owned ordered lane. Every instance consumes
that queue, and the engine assigns each key's unit to exactly one instance at a time (§8 of the engine doc). Per-key
order then comes from the engine, not from the lock.

**(a0) Configuration-only, today.** This is `EventProcessor` with an overridden `getInboxMessageConsumptionMode()`
(`EventProcessor.java:429`) returning `GlobalCompetingConsumers`, plus `getNumberOfParallelInboxMessageConsumers()`
(`:439`) above 1, on `essentials.shard-owned-queue.durable-queues-enabled=true`. Every part already exists: the
reader is exclusive (`:314`), the forward is keyed by aggregate id with `EventOrder` as the order (`:416-419`), the
adapter now carries `key_order` through delivery (adapter `CLAUDE.md`, first gotcha), and `GlobalCompetingConsumers`
starts a consumer on every instance (`Inboxes.java:291-294`). What (a0) keeps from today:

- the per-event forwarding transaction on the reader (§1.5),
- the re-read of every event by reference on the consumer,
- `EventProcessor` semantics (no inline path, `UnitOfWorkMode.NONE` allowed).

**(a1) Batched forwarder (the proposal).** This is the same topology with a reader built for throughput: a
processor-level `FencedLock` (as `ViewEventProcessor` already does) guarding a **batched** non-exclusive subscription
(`batchSubscribeToAggregateEventsAsynchronously`). Each batch goes out with one `queueMessages` call in the batch's
own unit of work. The shard-owned enqueue is a multi-row insert in one explicit transaction
(`components/postgresql-queue-shard-owned/CLAUDE.md:36`). The batch's resume point advances only after the batch
commits, and its gap fills are acknowledged in that unit of work (`BatchedPersistedEventSubscriber.java:63`). Two
choices are open (§8 Q2): whether messages carry the event payload instead of a reference, which would remove the
consumer's re-read, and whether handling is view-shaped (one unit of work per message) or `EventProcessor`-shaped.

Per-key order holds end to end. Events of one aggregate cannot commit out of `EventOrder`, because optimistic
concurrency serialises them. One reader enqueues them in subscription order, in sequential transactions, so a key's
`key_order` values commit in ascending order. That is the producer rule the engine requires
(`docs/durable-queue-shard-owned.md:580`, harness `CLAUDE.md:97-103`). Gaps in `key_order`, from events the processor
has no handler for, are allowed: "a key advances through the `key_order` values that are present".

### 3.2 Option (b) — per-partition readers with their own resume points

Split each aggregate type into N partitions by `hash(aggregate_id) mod N`. Each partition gets its own subscriber id
(for example `Proc:Accounts#3/16`), its own resume point and its own lock, and is read by whichever instance holds
that lock. It is additive at the storage level, since the subscriber id carries the partition. It costs a lot:

- **Read amplification.** Every partition reader scans the whole global order and discards (N−1)/N of it, unless the
  event tables get a partition-expression index. That is a schema change for every aggregate type, in every
  application.
- **Balancing.** `FencedLockManager` has no fair share; the first instance up takes all N locks. The fair-share
  membership that would balance them is internal to the queue engine (`ShardOwnedQueue`, package-private).
- **Changing N** moves aggregates between partitions and therefore between resume points: a stop-the-world
  migration, the outage-shaped step the engine design rejected for its own `shardCount`
  (`components/postgresql-queue-shard-owned/CLAUDE.md:156`).
- **Gap handling and CDC wake-ups** multiply by N, and reset has to coordinate N cursors.

It removes the single-reader ceiling, and it is the only option that does. It should be considered only if §5 shows a
batched single reader cannot keep up.

### 3.3 Option (c) — CDC-driven fan-out

In INBOX delivery mode every node's dispatcher publishes events to its local `CdcEventBus` (`docs/cdc.md` §10.3–10.4).
Each node could handle only the keys it "owns". That only moves (b)'s problems somewhere else: ownership has to come
from somewhere, and correctness still needs a durable per-partition resume point, because CDC is a delivery
accelerator over the event store and not a source of truth (`docs/cdc.md` §1). CDC is also off by default and falls
back to polling when it is not delivering. A processing mode cannot depend on it. **Rejected as a foundation.** CDC
stays useful underneath (a) for reader latency.

### 3.4 Comparison

| | (a0) config today | (a1) batched forwarder | (b) per-partition readers | (c) CDC fan-out |
|---|---|---|---|---|
| Removes the handler ceiling | Yes | Yes | Yes | Yes |
| Removes the reader ceiling | **No**: one transaction per event | Mostly: one transaction per batch | Yes | Only with (b)'s machinery |
| Resume point model | Unchanged | Unchanged | N per processor | N per processor |
| Rebalancing | Engine (drain-shed) | Engine | New, lock-based | New |
| Reset | Cross-instance quiesce needed | Same | Same, × N | Same |
| Requires CDC | No | No | No | Yes |
| New public API | None | One class, one capability flag | Large | Large |

### 3.5 Trade-offs common to every option

- **Ordering.** Per aggregate only, as §2 says.
- **At-least-once, with more sources of duplicates than today.** Duplicates come from three places: the reader
  re-forwarding after an unclean failover (≤ `snapshotResumePointsEvery` of events), the engine redelivering after a
  unit moves mid-handler (`docs/durable-queue-shard-owned.md` §8.3, "Handlers must be idempotent"), and the ack not
  committing with the view update (adapter `CLAUDE.md:30`). The Version = EventOrder guard stops being advice and
  becomes a requirement.
- **Re-forwarding collides with the ordered lane's primary key.** `shard_queue_ordered` has
  `PRIMARY KEY (queue_id, shard, msg_key, key_order)` (`ShardOwnedSchema.java:221`), and the ordered insert has no
  `ON CONFLICT` (`ShardOwnedStorage.java:1029-1031`). If a re-forwarded event is still queued, the forwarding
  transaction fails. The reader then retries and, under the default error policy, stops. This is **inferred, not
  observed**, and today's exclusive `EventProcessor` on this engine is exposed to the same thing after an unclean
  reader failover. It needs a reproducer (§7, IT-4). A partitioned mode needs an idempotent ordered enqueue
  ("already present ⇒ success").
- **Resume point meaning changes.** In (a) the resume point means *forwarded*, not *handled*. Whether the view is
  current becomes reader lag + queue depth for the processor's queue. The admin subscription page alone no longer
  answers it (§6).
- **Rebalancing** is the engine's: ordered units shed by draining, never by dropping a key mid-handler
  (`components/postgresql-queue-shard-owned/CLAUDE.md:26`). The "never two handlers per key" guarantee holds only
  while handlers finish inside `leaseTtl` (same file `:99`). Scale-out is capped at the queue's ordered units, 64 by
  default (`:142`).
- **Reset** has to stop handling **on every instance** before `onSubscriptionsReset` truncates. Otherwise a handler
  already running on another node writes an old row into the fresh table. §4.4 proposes an epoch.
- **The low-latency inline path is given up** for partitioned processors. Event-to-view latency becomes: reader
  commit → NOTIFY wake-up → owner → handler. When the reader's instance owns the key's unit, the engine's local
  hand-off delivers in under a millisecond. Across processes, p99 is a few ms (`CLAUDE.md:59`, `:73`). A hybrid that
  handles inline whenever the reader happens to own the unit was considered and rejected: ownership can move between
  the check and the handler. That is the reordering the drain-shed exists to prevent.
- **`UnitOfWorkMode.NONE`.** (a0) supports it, as `EventProcessor` does. For (a1)'s view shape, keep
  `ViewEventProcessor`'s refusal (`ViewEventProcessor.java:141-145`), so the class has one meaning.

---

## 4. Recommendation

Build **(a1)** as a new class. Treat **(a0)** as the experiment that justifies it (§5). Do not build (b) or (c)
unless the measurements force it.

### 4.1 What is added (all additive)

| Addition | Where | Notes |
|---|---|---|
| `KeyPartitionedViewEventProcessor` (working name) | `postgresql-event-store`, `…processor` | Extends `AbstractEventProcessor`. Takes the existing `ViewEventProcessorDependencies` (`ViewEventProcessor.java:100-106`), so no new dependencies bean is needed. Same subscriber id scheme, so a projection switched over keeps its resume point |
| Batched forwarding | same | Processor lock + `batchSubscribeToAggregateEventsAsynchronously` + one `queueMessages` per batch. Batch size and max latency are overridable protected methods |
| Capability check on the queue engine | `foundation`, `DurableQueues` | `default boolean guaranteesClusterWideKeyOrder() { return false; }`, overridden `true` by `ShardOwnedDurableQueues`. The processor refuses to start without it, because on `PostgresqlDurableQueues` competing consumers break key order (`Inboxes.java:53-55`) |
| Idempotent ordered enqueue | engine SPI + adapter | A key/order already present counts as success. New method or option; existing enqueue semantics are unchanged |
| Reset epoch | processor + message metadata | §4.4 |
| Docs | `LLM/LLM-postgresql-event-store.md` "Choose Processor" table, a new row; `LLM-traps.md` a new ESS id for "partitioned ⇒ per-aggregate order only" | Plugin `version` bump, per root `CLAUDE.md` |

Existing processors, `EventProcessor`'s defaults and the inbox modes do not change.

### 4.2 How a projection opts in

```java
public class AccountActivityProjection extends KeyPartitionedViewEventProcessor {
    public AccountActivityProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        // ...
    }

    @Override public String getProcessorName() { return "AccountActivityProjection"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() { return List.of(AccountsAggregateTypes.ACCOUNTS); }

    @Override protected int getNumberOfParallelQueuedMessageConsumers() { return 8; }   // per instance

    @MessageHandler
    void handle(FundsHeld e, OrderedMessage m) {
        // must be idempotent, guarded by m.getOrder() (Version = EventOrder)
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder from) {
        jdbcTemplate.execute("TRUNCATE TABLE projection_account_activity");
    }
}
```

The opt-in is the superclass. It is chosen at compile time, it is visible in review, and it says "per-aggregate
order only" in its name. A protected `getProcessingMode()` hook on `ViewEventProcessor` was considered. It would be
additive, but one class would then have two latency and ordering contracts, and `ViewEventProcessor`'s javadoc and the
LLM table could no longer describe it in one row.

### 4.3 Spring starter surface

There is no processor registry in the starter. Processors are application beans built from
`ViewEventProcessorDependencies` (`EventStoreConfiguration.java:613-618`). So the starter adds only:

- `essentials.eventstore.key-partitioned-processors.forward-batch-size` (proposed default 100) and
  `.forward-max-latency` (proposed default 20 ms). Global defaults; a processor overrides them in code.
- A startup check: a `KeyPartitionedViewEventProcessor` bean present while the `DurableQueues` bean lacks the
  capability fails startup with a message naming `essentials.shard-owned-queue.durable-queues-enabled`.
- Nothing on the shard-owned starter. Instance identity (`essentials.shard-owned-queue.instance-id`) and ordered
  units are already there.

### 4.4 Reset across instances

The proposal is a **reset epoch**: a per-processor counter, stored beside the resume point. The reader stamps every
forwarded message with it. A reset runs: stop reader → bump epoch → purge queue → `onSubscriptionsReset` → start
reader. Consumers drop any message whose epoch is below the current one. Handlers that started before the bump can
still commit after the truncate. Closing that window needs one of two things: the handler's own guard (the view row
carries the epoch), or the reset waiting out one `leaseTtl`. This is the weakest part of the design and is
Q4 in §8.

---

## 5. What to measure before building it

All runs use `LedgerLoadGenerator` (`/api/admin/ledger-load`), `compose` profile, ORDERED mode with
`GlobalCompetingConsumers` on the command inbox, 5 000 cycles, 1 and 2 instances. Each run reports `account_activity`
catch-up after drain, as it does today.

| # | What | How | What it decides |
|---|---|---|---|
| M1 | **Reader ceiling in isolation** | A no-op `ViewEventProcessor` (handlers that do nothing) on `ACCOUNTS`, and a no-op `EventProcessor` with its inbox consumer not started. Events/s from the subscription statistics (`/event-store/subscriptions/statistics`) or the demo's subscription metrics | If M1 ≈ 700–800/s, the reader **is** the ceiling. (a0) cannot help, and the case for EF-2 rests on batching (M4) |
| M2 | Handler cost | Subscription `handleEvent` timing (observability profile, `application-observability.yml:56-59`) for the three account views | How much of each ~1.3 ms is the view's own SQL |
| M3 | **(a0) by configuration** | A demo-side `EventProcessor` copy of `AccountActivityProjection` (same handlers and SQL, own table) overriding `getInboxMessageConsumptionMode()` → `GlobalCompetingConsumers` and `getNumberOfParallelInboxMessageConsumers()` → 8. Catch-up, inbox depth, `liveInstances`, `orderViolations` | Whether partitioned handling helps while the reader stays per-event |
| M4 | Batched forwarder prototype | Same as M3, but the forwarder is a demo-side `FencedLock` + `batchSubscribeToAggregateEventsAsynchronously` + `inbox.addMessagesReceived(batch)`, at batch sizes 10 / 100 / 500 | The reader ceiling with batching. This is the number that justifies (a1) |
| M5 | Single-instance parallelism control | M3 with **one** instance and 8 consumer threads | Whether the gain comes from partitioning across instances or simply from leaving the single-threaded loop. If one instance gets most of it, EF-2's cluster story matters less than its batching |

**M1 measured** (2026-10-19, one instance, `compose` profile, fixed-window rate over a 690 000-event `Accounts`
backlog, warm-up discarded, one sample each; harness `NoOpAccountsViewProcessor` / `NoOpAccountsEventProcessor`):

| Processor, handler does nothing but count | Steady rate | Note |
|---|---|---|
| `ViewEventProcessor` | **~1 500 events/s** | 43–46 k per 30 s window. Twice the real account views (700–800/s), so the views' own SQL costs about half |
| `EventProcessor` (forward to inbox, one consumer) | **~850 events/s forwarded** | ~25 k handled per 30 s plus inbox depth climbing 6 → 1 606: the per-event forward costs ~45 % of the reader, and the single default consumer already trails |

Read against the rule below: the plain reader (1 500/s) is above 800/s, so for a `ViewEventProcessor` the handler is
the larger cost — but forwarding event by event (what (a0) does) caps the reader at ~850/s, no better than today's
views. Partitioned handling alone therefore cannot win; a batched forward (M4) is what (a1) has to deliver, and
cheaper handler work (batched view writes) is a second, independent lever.

**M4 measured** (2026-10-19, harness `BatchedAccountsForwarder`: one reader behind a fenced lock, a batched
subscription to `Accounts`, one `addMessagesReceived` per batch carrying the deserialized events, keyed by aggregate id
with `EventOrder` as the order; inbox `GlobalCompetingConsumers`, 8 consumers per instance, handler only counts; fresh
subscriber per run reading the existing ~890 000-event backlog; 90 s window after 30 s warm-up; one sample per row):

| Batch | Instances | Forwarded/s | Handled/s (sum) | Inbox depth at end |
|---|---|---|---|---|
| 10 | 1 | 2 176 | 2 651 | 865 |
| 100 | 1 | **4 107** | 2 962 | 104 276 |
| 500 | 1 | **4 000** | 4 816 | 6 475 |
| 100 | 2 | 3 587 | 2 797 | ~72 000 |
| 100 | 2 (rerun, CPU sampled) | 4 057 | 3 702 | ~38 000 |

- **The batched forward is stable at ~4 000 events/s** from batch 100 up: 2.7× the plain `ViewEventProcessor` reader
  (M1, ~1 500/s) and 4.7× the per-event `EventProcessor` forward (M1, ~850/s). Against the rule below that is just
  under the 3× bar measured against the view reader, and well over it against today's forwarding processor.
- **The handled rate is not conclusive.** Identical runs varied from ~2 800 to ~4 800/s, and a second instance did
  not reliably add to it. The devcontainer has 8 cores shared by PostgreSQL and every JVM; during the two-instance
  rerun the host was 65–75 % busy and PostgreSQL alone used 2.5–4 cores. Horizontal scaling of the handling side has
  to be measured with the database on its own host before the second half of the rule can be applied.
- Handled can exceed forwarded within a window because it includes draining the warm-up backlog.

**M4 on a partitioned box** (2026-10-19, follow-up): PostgreSQL pinned to cores 0–3 (`docker update --cpuset-cpus`),
each instance pinned to its own two cores with `taskset` and `-XX:ActiveProcessorCount=2`, started from
`target/classes` (no Maven JVM beside it); batch 100; 1 and 2 instances alternated, three rounds:

| Round | 1 instance, handled/s | 2 instances, handled/s (sum) |
|---|---|---|
| 1 | 3 481 | 6 116 |
| 2 | 5 384 | 4 420 |
| 3 | 5 774 | 3 792 |
| **Median** | **5 384** | **4 420** |

- **Forwarding with the reader isolated: 6 100–7 100 events/s** on one instance — 4–5× the plain view reader. With two
  instances it drops to 4 500–5 200, because the forwarding instance also handles half the messages on its two cores.
- **A second instance did not raise handled throughput, even isolated.** Not database CPU (PostgreSQL used 104–365 % of
  its 400 %), not app CPU in the sense of a shared pool (each instance had its own two cores), not rebalancing (the
  second instance held 32 of 64 units before the measurement window opened). PostgreSQL waits sampled during a
  two-instance run (196 active-session samples): 46 % on CPU, 20 % the CDC walsender (off the message path), 14 % data
  file reads (default `shared_buffers` against a ~890 000-event table), 8 % WAL sync/write, 4 % vacuum delay — no
  dominant database wait. **Cause unresolved.**
- **Method flaw found:** every run reuses the `Ef2BatchedForward` inbox, so a run inherits the previous run's backlog
  (end depths ranged 4 000–234 000). Part of the round-to-round spread (single instance 3 481–5 774) is that. A clean
  rerun purges the inbox between runs; profiling the instances (JFR) is the next step for the scale-out question.

**Follow-up: purged inbox, uniform workload, forward timing** (2026-10-19, same partitioned box):

1. *Clean start.* Purging the inbox before each run removed the inherited backlog but not the result: one instance
   ~5 300 handled/s (median of 4), two ~4 170.
2. *Key skew, ruled out.* The replayed backlog was skewed (top 10 of 15 000 keys hold 14.7 % of 690 000 events, the
   biggest 10 394), which caps a strictly ordered workload at (hot keys ÷ per-message latency). A uniform stretch was
   generated (50 000 cycles over 1 000 accounts, no hot accounts: 202 000 events) and replayed alone
   (`trading-demo.ef2.from-global-order`), timing first to last handled message: one instance 4 327 / 4 342 / 5 239/s,
   two 4 115 / 4 213 / 4 083/s. Still flat, now with tight runs and an even unit split. (Generating it on one instance
   ran at 2 196 commands/s against 1 365 for the hot workload on two — skew does cost the *command* side.)
3. *Not consumer concurrency.* One instance with 16 or 32 consumers: 4 942 / 4 863/s; two with 16 each: 4 623/s.
4. *The forwarder is the cap.* With forward timing captured: one instance forwarded the 202 000 events in 46.2 s
   (4 370/s) and finished handling 10 s later; two instances took **91.3 s to forward (2 213/s)** and finished handling
   the same moment. The single reader bounds the end-to-end rate, and **it slows down when another instance consumes the
   same queue** (also seen in the window runs: 6 100–7 100/s alone, 4 500–5 200 with two). Unverified candidates:
   insert/acknowledge contention on the ordered table's index, and PostgreSQL serialising the commits of transactions
   that `NOTIFY` (the engine notifies the shard on every enqueue).
5. JFR during the window runs: the instances were mostly *not* executing Java (≈ 6–13 execution samples/s against the
   ~200/s two busy cores give), and the non-forwarding instance's top frames were the pump polling — the system waits
   on round trips rather than computing.
6. Absolute rates drifted across the session (single-instance 4 300–5 200 → 3 581 after dozens of runs, `VACUUM` or
   not): compare only runs from the same batch.

**Investigation of the forwarder slowdown** (2026-10-19, uniform stretch, `pg_stat_statements` reset per run, one
wait sample per second):

| PostgreSQL time | 1 instance | 2 instances (polling batch 100) |
|---|---|---|
| Engine batched cursor reads (`LATERAL`) | ~2 s | **~25 s** (28 500 calls, 0.7–0.9 ms) |
| Watermark horizon probes (`backend_xid` from `pg_stat_activity`) | 2.3 s (97 712 calls) | **9.4 s (204 548 calls, ~4 800/s)** |
| Acknowledge deletes | 1.9 s | 6.8 s |
| Forwarder `INSERT` into the ordered lane | 2.7 s | 3.2 s |
| `pg_notify` | 0.08 s | 0.1 s |

- **The forwarder's own statements are cheap and unchanged.** `NOTIFY` is ruled out (the per-commit notify lock was
  a candidate), and so is insert contention. The forwarder is latency-bound per batch: sequential round trips plus a
  commit, sharing a busier database.
- **The demo's `event-store-polling-batch-size: 5`** (`application-compose.yml`) made the batched subscription read
  the event store 5 events at a time: 42 087 reads (and as many gap-table lookups) for ~2 020 batches. At 100 it is
  ~4 reads per batch. Raise it for any throughput measurement.
- **The engine's consumer-side work grows with instances, not with messages**: each instance's owners poll and probe
  independently, so a second instance multiplied the cursor-read time ~12× and the horizon probes to ~4 800/s, with
  `LWLock:WALWrite` waits 8 → 27 samples. Engine candidates with evidence: one horizon probe per pump (or per
  instance) instead of per owner, and the batched cursor read's cost when units are split across instances.
- **Two-instance runs are not repeatable enough here**: identical configurations gave 3 334 and 4 751 forwarded/s.
  Single-instance runs were within ~15 %.

**Conclusion so far.** Batching the forward is proven and is the part of EF-2 worth building. Multi-instance handling
cannot be judged yet, because the single forwarder is slower than the handling and is itself slowed by consumption on
other instances. The next measurement is the forwarder's enqueue under concurrent consumption (statement latency,
lock and notify waits); its answer decides whether the shard-owned engine's enqueue path needs work before EF-2's
scale-out claim can be tested at all.

**Decision rule.** Build (a1) if M4 forwards at least 3× M1 and M4 with two instances cuts catch-up materially below
M5. If M1 already runs well above 800/s, the handler is the cost. Then (a0) plus parallel consumers may be enough, and
EF-2 shrinks to documentation and the capability check.

**Field evidence: the trading demo's order flow** (2026-10-19, phase 3, one instance, `compose` profile, stub
exchange; `_demo_harness/CLAUDE.md`, "The order flow scenario"). This is the first workload where processors chain
rather than project: an order's life is two `EventProcessor`s deep (`ExchangeOrderRouter` submits it and its reports
come back as five `Orders` events; `OrderLedgerProcessor` books and releases it on the account).

| 10 000 orders, 200 customers, 32 at once | |
|---|---|
| Placement (HTTP-equivalent command, order + hold in one unit of work) | **3 174 orders/s**, p99 67 ms, 0 conflicts |
| Completion (placed → booked and released) | **109 orders/s** |
| End to end, placement → `TradeBooked` | p50 59 s, p99 89 s (a backlog: completion is 30× slower than placement) |
| `Orders` stream growth while draining | ~690 events/s (50 000 in 73 s), gated by the router reaching `OrderPlaced` |
| placed → submitted / filled → booked | p50 45 s / 15 s |

The handlers are not the cost: the stub answers in microseconds and the reports' inbox handles them as fast as they
arrive (submitted → filled p50 ≈ 0). Each processor forwards every `Orders` event, five per order, although each
handles two, and does so one event per unit of work. The rate matches M1's per-event forward. That makes the
forward, not handling, the cap on a realistic chain, and it supports building the batched forward (a1) first. A
processor-side filter that does not forward events it has no handler for would cut this workload's forwards by more
than half on its own; it is a candidate to add to §4.

**Correction and isolated measurement** (2026-10-20, `EventProcessorForwardBenchmarkIT`, opt-in, in
`postgresql-event-store`). Two claims above do not hold up:

- *"Each processor forwards every `Orders` event."* It does not: `EventProcessor.forwardEventToInbox` already forwards
  only events it has a handler for. What an unhandled event still costs is the subscriber's per-event unit of work,
  which is read-only and measured at ~0.07 ms. Skipping it before the unit of work is not worth building.
- *"The forward is the cap."* In isolation it is not. The benchmark drains 20 000 events (4 000 orders x 5) through an
  `EventProcessor` handling two of five types: the reader forwards ~4 300 events/s, about 860 orders/s per processor,
  eight times the demo's completion rate. End to end the processor is bound by its **single inbox consumer**, not by the
  reader.

The reader does carry one per-event cost worth knowing: `PersistedEventSubscriber` requests one event after each it
handles, and every request schedules its own poll, so a polling subscription runs a query per event. Requesting in
half-batches makes the forward 1.5-2.75x faster (2 916 -> 4 317 and 4 284 -> 11 784 events/s, medians of 9) but leaves
the `EventProcessor` drain and a `ViewEventProcessor`'s end-to-end rate unchanged within noise. It is parked on branch
`ef-2/batched-demand` until the consumer side is parallel and the reader becomes the limit, or until (a1) supersedes it.

**Profiled** (2026-10-20, one instance, `compose` profile on a fresh database, full JIT, 200 x 50 orders, 32 at once):
completion **75 orders/s**, with the JVM at ~120 % and PostgreSQL at ~130 % of eight cores. The order flow is not CPU-
bound but **latency-bound**: every stage is one thread making serial round trips. In order of what they cost per
event or message:

| Where | What | Evidence |
|---|---|---|
| `ExclusiveAsynchronousSubscription.request` | Every request asks `FencedLockManager.isLockedByThisLockManagerInstance`, a lock lookup in its own transaction, and the subscriber requests once per event | 100 000 `SELECT * FROM fenced_locks` for 10 000 orders; reader stacks parked in `lookupLockInDB` |
| `ViewEventProcessor.handlePersistedEvent` | `hasOrderedMessageQueuedForKey`, payload deserialization, `SAVEPOINT` and `RELEASE` for every event, also those with no handler | 217 604 of each; `LateFillsProjection` (one handled type, none occurring) lagged 15-18k events |
| Jdbi `Handle` | `Connection.getTransactionIsolation()` per transaction, a round trip in pgjdbc | 739 559 `SHOW TRANSACTION ISOLATION LEVEL` for 672 087 `BEGIN` |
| `Inbox:ExchangeOrderRouter`, `Inbox:OrderLedgerProcessor` | One consumer each (`SingleGlobalConsumer`, parallelism 1) | Router inbox to 6 300 while placing; ledger inbox to 4 900 afterwards, draining ~170/s |

The first two are small, local fixes in `postgresql-event-store`, both made (2026-10-20/21): the lock is checked in
memory (`EventProcessor` forward 1.65-2.6x), and a view passes over events it has no handler for (`view_one_of_five`
4.5x). Neither moves the order flow's end-to-end rate, which the inbox consumers bound. The last row is what EF-2
itself is about.

**M5 measured** (2026-10-21, one instance, same setup, both fixes in, 10 % of orders cancelled): more threads on the
router, ledger and reports inboxes (`trading-demo.orders.consumers.*`), each inbox still `SingleGlobalConsumer`, so one
order's messages stay in order. Completed orders/s, A and D alternating within each round:

| Round | A: 1 / 1 / 2 consumers, pool 20 | D: 8 / 8 / 8 consumers, pool 60 |
|---|---|---|
| 1 | 62 | 172 |
| 2 | 133 | 159 |
| 3 | 114 | 172 |
| 4 | 74 | 102 |
| 5 | 64 | 106 |
| **Median** | **74** | **159** (D ahead in every round, 1.2-2.8x) |

4 / 4 / 4 and 8 / 8 / 8 on the 20-connection pool (two rounds each: 60 / 128 and 91 / 132) were no better than A: the
pool, shared with ~20 subscriptions and the placing threads, bounds what more consumers can do. No dead letter and no
order left open in any run. So one instance gets most of what partitioned handling promises, given the connections
for it; EF-2's cross-instance story matters less than its parallelism and its connection budget. The run-to-run spread
on this devcontainer is large (A: 62-133), so the size of the gain is indicative, its direction is not in doubt.

A 40-connection pool does as well as 60 (three more rounds, alternating: A 150 / 104 / 100, 8 / 8 / 8 on pool 40
220 / 224 / 209, on pool 60 215 / 217 / 203), and two instances at 40 stay inside PostgreSQL's default
`max_connections` of 100.

**The third row** (`SHOW TRANSACTION ISOLATION LEVEL`) came from Spring, not Jdbi: `SpringTransactionAwareUnitOfWorkFactory`
asks for READ COMMITTED explicitly, so `DataSourceTransactionManager` reads the connection's level before every
transaction. `essentials.unit-of-work.transaction-isolation: database-default` asks for none (opt-in; the default stays
READ COMMITTED, as a database whose default differs would otherwise lose the guarantee silently). Same setup, defaults
for the consumers, three rounds alternating: ~623 000 isolation queries per run and 144 / 163 completed orders/s with
the explicit level (a third run stalled - see below), none and 168 / 183 / 185 without. The demo opts in.

**The intermittent stall was the load generator** (explained 2026-10-21): some runs never completed because a few
orders never closed (53 in one run, 1 in another, 319 in a pure-polling run; no dead letters). No event was lost. The
generator priced an ordinary limit order at ask x 1.01 when placing it, while the stub exchange matches a limit once, on
arrival, against the ask of that moment; under a slow pipeline (60 s from placement to submission under pure polling)
the synthetic feed moved past late orders, they rested at the exchange for good, and the run waited for bookings that
could not come. In the 319-order run every stuck order's limit lay inside the ask's band at its submission second.
Fixed in the harness (`47c23cc8`): a 5 % margin, and a run that stands still for 10 s cancels such orders and reports
them as `restedUnexpectedly` instead of hanging.

**Two speeds, and where the ceiling sits now** (2026-10-21/22, one instance, `compose` profile with the demo's
defaults - 1 / 1 / 2 inbox consumers, a 20-connection pool - and the isolation opt-in, 200 x 50 orders). After the fixes above, runs still came out at one of two speeds, polling and CDC
alike (CDC ~117 or ~178 completed orders/s, pure polling ~124 or ~200). Three causes, each fixed and measured:

| Cause | Where | Fix | Effect on the order flow |
|---|---|---|---|
| CDC subscriptions polled for 60 s after every start: the first `INACTIVE -> ACTIVE` was debounced by `activeCutbackDebounce` like a recovery | `CdcEventStore.buildAdaptiveLiveSource` | debounce an `ACTIVE` only after an interruption (`96ba391a`) | CDC: one speed, ~155/s |
| A shard-owned pump's cached generic plan, made while the queue table was vacuumed empty, scanned the table once per unit (70 ms instead of 0.4 ms per read) until auto-analyze, 30-40 s later; whether autovacuum ran just before the flow decided the run | `ShardOwnedStorage`, all 12 pump statements | plan each execution afresh, `setPrepareThreshold(0)` (`0e7e5c5b`) | polling: 8 of 8 runs at 193-199/s |
| The CDC tailer and dispatcher committed once per WAL message: ~1 500 events/s, so a 22 000-event burst reached the bus ~15 s late | `WalReplicationTailer`, `CdcDispatcher` | write and acknowledge the inbox in batches (`4f1bd5fc`) | CDC: ~152 -> ~184/s, end to end p50 42 -> 30 s |

So the order flow now completes at **~196/s on polling and ~184/s on CDC**, against 109/s on 2026-10-19 and 75/s in
the first profile. CDC's remaining gap is small and only partly its own: the bus path records each gap an
out-of-commit-order event opens in a transaction of its own (12 300 per run, 1-3 % of a subscriber's samples), and the
runs vary by about as much.

**What bounds both paths now is per-event handling.** Sampling every subscription's delivery thread during the burst
(CDC, batched inbox), the busy ones spend their time on the unit of work per event: its commit (15-22 of 60 samples),
and for a `ViewEventProcessor` the savepoint and the `hasOrderedMessageQueuedForKey` check against the queue (7-14),
then the handler's own SQL. `ExchangeOrderRouter` spends it enqueueing each forwarded event into its inbox. The CPU
stays mostly idle; every stage is still serial round trips. That points the same way as M4: the gain left is in doing
per-event work per batch - a batched forward for `EventProcessor`s, and batched handling (one unit of work and one
queued-for-key check per batch) for views - not in the reader or the delivery path.

**Batched forward, built and measured** (2026-10-22). `EventProcessor.getForwardBatchSize()` (opt-in, default 1) reads
through an exclusive batched subscription (`ExclusiveBatchedAsynchronousSubscription`, same fenced lock and resume point)
and forwards each batch to the inbox in one unit of work - M4's prototype moved into the library, still one processor
per node, nothing cross-instance. It pays only together with more inbox consumers:

| `EventProcessorForwardBenchmarkIT`, 2 000 orders x 5 events, drain (median of 3) | 1 consumer | 1 consumer, batch 100 | 8 consumers | 8 consumers, batch 100 |
|---|---|---|---|---|
| two of five handled | 2 022 ms | 2 040 ms | 820 ms | **472 ms** |
| all five handled | 5 310 ms | 5 065 ms | 1 942 ms | **1 099 ms** |
| two of five, after the 0.60.1 merge (median of 3, per run) | 2 569 / 2 320 / 2 071 ms | 2 072 / 2 111 / 2 689 ms | 820 / 820 / 821 ms | **737 / 631 / 453 ms** |
| all five, after the 0.60.1 merge (median of 3, per run) | 4 967 / 7 469 / 5 122 ms | 7 433 / 5 134 / 5 318 ms | 2 001 / 1 929 / 1 949 ms | **1 106 / 1 106 / 1 104 ms** |

The 2026-10-28 rows are three runs of the benchmark on the merged code (`897cb31e`). The conclusion holds: the batch pays
only with eight consumers, and there it halves the drain again. One consumer is noisy (all five, unbatched: single
drains from 4.7 to 12.9 s); eight are steady.

| Trading demo order flow, CDC, 4 runs each, alternating | forward per event | forward batch 100 |
|---|---|---|
| 1 / 1 / 2 consumers, pool 20 | ~180 orders/s | ~180 orders/s |
| 8 / 8 / 8 consumers, pool 40 | ~298 orders/s, p50 20 s | **~366 orders/s, p50 15.6 s** |

With one consumer the batch only fills the inbox faster; with eight the consumers keep up and the forward is the limit
again, which the batch then removes. The demo now runs 8 / 8 / 8, pool 40, batch 100 (`compose` profile). Against the
decision rule above this is the single-instance half of (a1) - batched forwarding - on its own; the cross-instance half
still cannot be measured here.

Two hazards to watch during M3/M4. Kill the reader's instance with `kill -9` mid-run to see the PK collision in §3.5,
or show that it does not happen. And compare per-key order against the exact `EventOrder` sequence, not `isSorted()`
(the trap recorded in adapter `CLAUDE.md`, first gotcha).

---

## 6. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Projections that depend on **cross-aggregate order** (a parent row written by aggregate X before a child row from Y; "latest N across all accounts" feeds) | Missing parent or FK failures, a feed out of order | Do not use the mode for them. The class name and the LLM table say "per-aggregate order only". Handlers that need a parent should tolerate its absence (upsert, or retry with a retryable exception) |
| Views that **read-modify-write shared rows** (totals across accounts, per-instrument aggregates fed by many accounts) | Lost updates under concurrent instances, or hot-row lock contention that erases the gain | Key the row by the aggregate, or update it atomically (`UPDATE … SET x = x + ?`) with an idempotency guard. The demo's account views are keyed by account (`AccountActivityProjection.java` PK `(account_stream_id, event_order)`) and are good candidates; `PositionsProjection`'s per-account version row is too |
| **Aggregate ids that collide across aggregate types** in one processor | The key is the aggregate id alone (`EventProcessor.java:416-417`). Two types sharing an id share a key, and equal `EventOrder`s hit the ordered PK | Qualify the key with the aggregate type in the new class. Note: this already applies to a multi-type `EventProcessor` on this engine today |
| Duplicate forward after reader crash | Reader stops on the ordered PK (§3.5) | Idempotent ordered enqueue (§4.1) |
| `FENCED_LOCK_TOKEN` metadata | `ViewEventProcessor` stamps queued messages with the lock token (`ViewEventProcessor.java:177-178`). Partitioned handlers have none | Do not stamp it. Document that handlers cannot fence on it |
| Lag invisible in the admin UI | `ApiSubscription` (`EssentialsAdminApiSpec.java:113`, `:152-169`) is keyed `(subscriberId, aggregateType)`, describes the **reader**, and is reported per instance ("null when not running in this instance"). The handler side shows only as a shard-owned queue (`/shard-owned-queues/{queueName}/status`). There is no processor-level entity and no reset operation in the spec | v1: no new admin operation. Document "lag = reader lag + queue depth" and name the processor's queue predictably (`<processorName>:queue`, as `ViewEventProcessor.java:149`). A processor-level status would be the three-place change (SPI, spec mapping, controller) and is deferred (Q6) |
| Long handlers | A handler outlasting `leaseTtl` can overlap a successor on the same key (`components/postgresql-queue-shard-owned/CLAUDE.md:99`) | Same rule as any ordered-lane consumer. `handlersOutlastingLease` is the signal |
| Engine maturity | "Still no production use" (`docs/durable-queue-shard-owned.md:5`) | The mode exists only on this engine, so the risk is contained to applications that opt in |

---

## 7. Test plan

**Unit (no Docker)**

- The forwarder builds `OrderedMessage`s with key = aggregate id (type-qualified, if Q3 decides so) and
  order = `EventOrder`, ascending per key within a batch, and skips events with no handler.
- Startup refuses a `DurableQueues` without the capability and refuses `UnitOfWorkMode.NONE` handlers.
- Reset sequencing: reader stopped before purge, epoch bumped before purge, `onSubscriptionsReset` after purge;
  old-epoch messages are dropped by the consumer.
- Properties bind and their defaults apply. Per-processor overrides win.

**Integration (Docker, shard-owned engine)**

| IT | Asserts |
|---|---|
| IT-1 Two instances, steady load | Every event handled ≥ 1, the view equals a single-instance replay, per-key order **exact**, both instances handle (`orderedUnitsHeld` > 0 on each), `orderViolations` = 0 |
| IT-2 Rebalance under load | Third instance joins, then one leaves gracefully. No key has two handlers at once, asserted at the moment of overlap with handler-side timestamps, not afterwards; nothing lost |
| IT-3 Reader failover, graceful | Reader lock moves; no event re-forwarded; no gap |
| IT-4 Reader failover, unclean | Reader instance killed mid-batch. Re-forwarded events either hit the idempotent enqueue or are handled twice and absorbed by the guard; the reader **does not stop** (reproducer for §3.5, run first against today's `EventProcessor`) |
| IT-5 Fencing | A reader that has lost its lock but still holds a batch (paused past `leaseTtl`): its late forward is a duplicate and nothing worse |
| IT-6 Reset with in-flight handlers | Two instances, slow handlers, reset mid-run: the final view equals a fresh replay |
| IT-7 Dead letter | A poison event blocks only its key; other keys progress; `resurrectKey` replays in order |
| IT-8 Multi-type key collision | Two aggregate types with the same id in one processor: both streams handled, in order |
| IT-9 Catch-up from zero | 100 k events, reset to `FIRST_GLOBAL_EVENT_ORDER`, 1 vs 2 instances: catch-up time recorded (benchmark-gated) |

Give leases headroom over handler time in IT-2/IT-5, or the test measures a liveness lapse
(`components/postgresql-queue-shard-owned/CLAUDE.md:99`).

---

## 8. Open questions for the maintainer

1. **Is per-aggregate order an acceptable contract for a view processor at all**, given that `ViewEventProcessor` is
   effectively commit-ordered today? Or should the mode exist only on the `EventProcessor` side (a0, documented),
   with no new view class?
2. **Payload or reference in the forwarded message?** A payload removes one read per event on the consumer. In exchange
   it makes every queue row and its WAL as large as the event (how payload size drives the engine's per-message cost
   is measured in `components/postgresql-queue-shard-owned/CLAUDE.md:86`). A reference keeps the queue small and the
   event store authoritative.
3. **Key qualification.** Qualify the key with the aggregate type in the new class (safe, but differs from
   `EventProcessor`), or keep the bare id and document the collision?
4. **Reset across instances.** Is the epoch plus "wait one `leaseTtl`" acceptable, or should reset be allowed only
   while the processor is stopped cluster-wide (an admin precondition)?
5. **Idempotent ordered enqueue.** Is it an SPI addition to `MessageQueue` (frozen at release: additive only,
   default method), or is it handled inside the adapter? And should it be fixed for today's `EventProcessor` on this
   engine independently of EF-2, if IT-4 reproduces it?
6. **Admin representation.** Is "reader subscription + queue status" enough for v1, or does a partitioned processor
   need its own entity in `EssentialsAdminApiSpec` (with the three-place sync)?
7. **Migration of an existing projection.** Switching a `ViewEventProcessor` to the new class keeps its subscriber
   id and resume point. Should it also reuse `<name>:lock` and `<name>:queue`, so a rolling deploy mixes old inline
   handlers and new partitioned ones safely (old nodes already queue behind a key with queued work), or should it
   require a clean stop?
8. **Scope of the capability flag.** Is `guaranteesClusterWideKeyOrder()` on `DurableQueues` the right place, or
   should the processor simply require `ShardOwnedDurableQueues` by type?
