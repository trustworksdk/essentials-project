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

**Decision rule.** Build (a1) if M4 forwards at least 3× M1 and M4 with two instances cuts catch-up materially below
M5. If M1 already runs well above 800/s, the handler is the cost. Then (a0) plus parallel consumers may be enough, and
EF-2 shrinks to documentation and the capability check.

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
