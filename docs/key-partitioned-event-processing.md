# EF-2 — Key-Partitioned Event Processing on the Shard-Owned Queue Engine

**Status (2026-10-29):** partly built. What is built is opt-in and measured; what is left is a proposal for the views,
with the decisions in §8 open.
**Modules:** `components/postgresql-event-store` (package `…postgresql.processor`), `components/foundation`
(`DurableQueues`, `Inboxes`), `components/postgresql-queue-shard-owned(-adapter)`, `spring-boot-starter-postgresql-event-store`.
**Driven by:** EF-2 in `examples/essentials-trading-demo/docs/EXPANSION_PLAN.md`, and the trading demo's load and fault
runs (`_demo_harness/CLAUDE.md` in that module).

## At a glance

Every event processor has one **reader**: the event-store subscription with its one resume point. That has to stay
exclusive. What does not have to be exclusive is the **handling** - the `@MessageHandler`s or the view's SQL - which is
where the time goes. EF-2 is about running the handling on every instance while one instance reads.

| | Status | Where |
|---|---|---|
| `EventProcessor`: handlers on every instance (option (a0) below) | **Built** as the class `PartitionedEventProcessor`, measured with two instances | Release notes 0.61 §2.7, `docs/event-processor-specializations.md` |
| Capability flag: does the queue keep one key in order across instances? | **Built**: `DurableQueues.keepsKeyOrderAcrossInstances()` / `Inboxes.keepsKeyOrderAcrossInstances()`, `true` only for the shard-owned adapter | foundation, adapter |
| `EventProcessor`: batched forward from the reader to the inbox | **Built** (`getForwardBatchSize()`), measured | §5 "Batched forward, built and measured" |
| `ViewEventProcessor`: batched handling (one unit of work per batch) | **Built** (`getDirectHandlingBatchSize()`), measured | `docs/view-event-processor-batched-handling.md` |
| `ViewEventProcessor`: handling on every instance (option (a1)) | **Proposal**: §4, decisions §8 | this document |
| Spreading the fenced locks: whole processors over instances, on every queue engine (option (d)) | **Built, opt-in** (`essentials.fenced-lock-manager.spread-locks`, §3.5), with rate-limited rebalancing; measured in the demo | this document |

Which queue implementation an application runs decides how much of this it can use at all: §3.6. In short, only an
engine that keeps one key in order across instances - today the shard-owned engine - can spread handling over
instances. On the others (`PostgresqlDurableQueues`, MongoDB, and the planned multi-table replacement for
`PostgresqlDurableQueues`, as it is expected to work) the batching helps, and the only way to spread work over instances
is to spread whole processors - option (d), spreading the fenced locks, which works on every engine.

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

### 1.7 Where the locks land, measured

Measured in the trading demo with two instances (`_demo_harness/CLAUDE.md`, "F-3 on two instances"), before the
competing-inbox opt-in existed:

- **Every fenced lock sat on the instance that started first: 41 of 41.** A lock manager's acquirer takes any free lock
  on its next tick (every `lockConfirmationInterval`, 4 s there), so the first instance to start takes them all before
  the second one is up. That put every exclusive processor - 15 views, 6 event-processor readers - and, through the
  `SingleGlobalConsumer` inbox locks, every event processor's handlers on one instance.
- **The second instance got 306 of the 2 244 shard-owned units**, a fair half of only the 9 queues it consumed at all.
  The engine balances every queue its instances share; the other 24 had a consumer on the lock holder only.
- **A crash moves the whole block.** Killing the lock holder: the survivor took all 41 locks 11-13 s after the kill
  (the 15 s lock timeout), and every exclusive processor paused for that long at once.

With the six event processors' handlers on competing consumers, they run on both instances (deliveries split
6 194 / 6 124 for the order router, 0 ordering violations), the inbox locks are gone (41 → 35), and the second
instance holds 510 units. What remains concentrated is the 15 views and the 6 readers: (d) in §3.5 would spread them as
whole processors on any engine, (a1) in §4 the views' handling on the shard-owned engine.

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

### 3.1 Option (a0) — `PartitionedEventProcessor`: event-processor handlers on every instance (built)

The reader keeps its lock, subscriber id and resume point and forwards each event to the processor's `Inbox` as an
ordered message (key = aggregate id, order = `EventOrder`). With `GlobalCompetingConsumers` every instance consumes
that inbox, and the shard-owned engine gives each key's unit to one instance at a time, so per-key order comes from
the engine instead of the inbox lock. Built as the class `PartitionedEventProcessor` (release notes §2.7,
`docs/event-processor-specializations.md`): mode fixed, forwarding batched by default, and it refuses to start where
`Inboxes.keepsKeyOrderAcrossInstances()` is `false`. A global opt-in property was built first and removed before
release: the contract belongs in the class a processor extends, not in configuration.

Pros:
- Spreads the expensive part of an `EventProcessor` - its handlers - over every instance; measured evenly split, 0
  ordering violations, nothing lost when the lock holder was killed.
- The choice is visible in the code (the superclass) and cannot be configured into something unsafe.
- Combines with the batched forward (on by default), which removes most of the reader's per-event cost.
- Every processor keeps its semantics otherwise, including `UnitOfWorkMode.NONE`.

Cons:
- Handlers run on several instances at once (for different aggregates). A handler keeping in-memory state across
  aggregates, or calling a downstream that takes one caller, must stay an `EventProcessor`.
- Cross-aggregate order is gone (it already was with more than one consumer thread).
- Handlers of different aggregates that write one shared stream conflict more often (order ledger: 2.0 % → 2.9 % of
  deliveries retried with two instances; all succeeded).
- Ties the processor to an engine with cross-instance key order in every environment, tests included.
- Only `EventProcessor`s; the views stay on one instance.

### 3.2 Option (a1) — key-partitioned views: one batched reader, view handling on every instance (proposal)

The same topology for views, as a new class (working name `KeyPartitionedViewEventProcessor`): the view's own
`FencedLock` guards a **batched** non-exclusive subscription that only forwards each batch into the shard-owned ordered
lane, in one `queueMessages` call per batch, with the resume point. Every instance consumes the queue and runs the
view's handlers. Details in §4.

Per-key order holds end to end: events of one aggregate cannot commit out of `EventOrder` (optimistic concurrency), one
reader enqueues them in subscription order in sequential transactions, so a key's `key_order` values commit ascending -
the producer rule the engine requires (`docs/durable-queue-shard-owned.md:580`). Gaps in `key_order` (events the view
has no handler for) are allowed.

Pros:
- Spreads the work that is still concentrated after (a0): the views' SQL, about half of a view's per-event cost
  (§5 M1).
- The reader becomes a thin, batched forwarder, which removes the per-event transaction that is the views' ceiling
  today (§1.5).
- Failover pauses only the reader; handling of keys on surviving instances goes on.
- Resume point, subscriber id and lock name stay, so a view can switch over and keep its place.

Cons:
- **Per-aggregate order only.** A view that today sees one aggregate type's events in commit order loses
  cross-aggregate order (§6 lists the views that break).
- **Latency:** the inline path (handle on the reader thread) is given up; event-to-view becomes reader commit → owner
  → handler, a few ms p99 across processes.
- **More duplicates:** reader re-forward after an unclean failover, engine redelivery when a unit moves, and the ack not
  committing with the view update. The Version = EventOrder guard becomes a requirement.
- **Reset needs cross-instance coordination** (§4.4), the weakest part of the design.
- **Needs an idempotent ordered enqueue,** or a re-forward after a crash collides with the ordered lane's primary key
  (§3.8, IT-4).
- Shard-owned engine only (§3.6).

### 3.3 Option (b) — per-partition readers with their own resume points

Split each aggregate type into N partitions by `hash(aggregate_id) mod N`. Each partition gets its own subscriber id
(e.g. `Proc:Accounts#3/16`), resume point and lock, read by whichever instance holds that lock.

Pros:
- Removes the single-reader ceiling, the only option that does.
- Works on any queue implementation: order comes from the partition's single reader, not from the queue.

Cons:
- **Read amplification:** every partition reader scans the whole global order and discards (N−1)/N of it, unless the
  event tables get a partition-expression index - a schema change for every aggregate type in every application.
- **Balancing:** N locks per processor, and `FencedLockManager` has no fair share - the first instance up takes all of
  them (§1.7). It needs lock balancing such as (d), §3.5.
- **Changing N** moves aggregates between resume points: a stop-the-world migration.
- Gap handling, CDC wake-ups and reset all multiply by N.

Only worth it if a batched single reader cannot keep up (§5).

### 3.4 Option (c) — CDC-driven fan-out

Every node's CDC dispatcher publishes events to its local `CdcEventBus` (`docs/cdc.md` §10.3-10.4); each node would
handle only the keys it "owns".

Pros:
- Low latency: no extra queue hop.

Cons:
- Ownership still has to come from somewhere, and correctness still needs a durable per-partition resume point: CDC is
  a delivery accelerator over the event store, not a source of truth (`docs/cdc.md` §1) - so it inherits (b)'s costs.
- CDC is off by default and falls back to polling; a processing mode cannot depend on it.

**Rejected as a foundation.** CDC stays useful underneath (a0)/(a1) for reader latency.

### 3.5 Option (d) — spread the fenced locks (built, opt-in, with rate-limited rebalancing)

Leave every processor exclusive and spread the locks - and so whole processors - over the instances. It is independent
of the queue engine, so it is the one way to spread work over instances on `PostgresqlDurableQueues`, MongoDB and the
planned replacement, and it complements (a0)/(a1) on the shard-owned engine (it also places the views and the readers
those leave on one instance).

**Sketch (opt-in, in `DBFencedLockManager`):**

- **Membership without a new table.** Each lock manager holds one lock of its own, `__member:<instanceId>`, confirmed
  on the same tick as every other lock and gone after the lock timeout when the instance dies. The live instances are
  the confirmed `__member:*` rows of `fenced_locks` - today's schema and mechanics.
- **Preferred owner per lock** by rendezvous hashing of the lock name over the live instances: every instance computes
  the same answer, and an instance joining or leaving changes the preferred owner of only ~1/N of the locks.
- **Acquire:** a free lock goes to its preferred owner; a non-preferred instance takes it only if the preferred owner
  has not within a grace (a tick or two), so a lock is never left unserved.
- **Rebalance (optional, rate-limited):** an instance releases a lock whose preferred owner is another *live* instance,
  at most one lock per interval, so a membership change trickles locks over instead of moving them all at once.

**What it costs: a pause per move.** An exclusive processor cannot move in place. The old holder finishes its event,
saves the resume point and releases; the new one takes the lock on its next acquirer tick (up to
`lockConfirmationInterval`), reads the resume point and resubscribes (with CDC, a catch-up first). A few seconds without
handling, per processor per move. Rendezvous hashing bounds the moves to ~1/N of the locks per membership change, and
only locks whose preferred owner is alive ever move; a rolling deploy of N pods moves each lock at most out and back.

Pros:
- Works on every queue engine and for every processor type, views included; no change to any processor's semantics or
  ordering contract.
- Even spread at start-up and on scale-out, whatever the start order; a crash spreads the dead instance's processors
  over all survivors instead of one, so less load lands on any one instance (§1.7: today the whole block moves).
- No schema change (membership rides on lock rows); opt-in, so nothing changes for anyone who does not turn it on.

Cons:
- A pause of a few seconds per moved processor, on every membership change - the price of the even spread. Bounded and
  rate-limited, not avoidable.
- Spreads whole processors only: one busy view is still one thread on one instance (that is (a1)'s job).
- A new behaviour in the central `FencedLockManager`, with the timing cases that come with it: a preferred owner that
  is slow to start, flapping membership (hysteresis needed), and two managers disagreeing on membership for a tick.

**Measured, built without rebalancing (2026-10-30, one run per arm).** Three trading-demo instances, 35 locks,
`lockTimeOut` 15 s, grace 8 s (`~/trading-demo-profiling/lockspread-2026-10-30/lockspread.sh`):

| Scenario | Spreading off | Spreading on |
|---|---|---|
| Staggered start (each instance up and settled before the next) | 35 / 0 / 0 | 35 / 0 / 0 |
| SIGKILL of the holder of all 35 | all 35 to one survivor, re-held after 13.0 s | 17 / 18 over both survivors, after 17.4 s |
| Cluster restart (all killed, started together) | 1 / 16 / 18 | 24 / 0 / 11 |

What it shows: without rebalancing, the one case spreading wins is a crash (the dead instance's locks go to every
survivor instead of one), at about 4 s more failover - the locks whose preferred owner was the dead instance wait for the
grace or the next membership read. A staggered start is unchanged, as documented. A "simultaneous" restart is not
simultaneous: JVMs come up seconds apart, the first ones read a membership that lacks the later ones and take the locks
they are preferred for, and nothing moves a held lock afterwards - here one instance ended with none, worse than without.
Rebalancing (or a settle period after start before acquiring) is what the restart and staggered cases need.

**Measured with rebalancing (2026-10-30, three alternating rounds per arm).** Same setup, default intervals
(confirmation 4 s, grace 8 s, rebalance 8 s); "settled" = unchanged for 30 s:

| Scenario | Spreading off (rounds 1 / 2 / 3) | Spreading on, rebalancing (rounds 1 / 2 / 3) |
|---|---|---|
| Staggered start | 35/0/0 · 35/0/0 · 35/0/0 | 10/13/12 in all three, settled after 204 / 200 / 203 s |
| SIGKILL of the largest holder | 26/9 · 13/22 · 35/0, re-held after 12.4 / 8.5 / 12.3 s | 17/18 in all three, re-held after 16.9 / 18.6 / 17.6 s |
| Cluster restart | 34/0/1 · 35/0/0 · 2/30/3 | 10/13/12 in all three, settled after 179 / 145 / 165 s |

Rebalancing makes the spread the same every time, whatever the start order: about 25 of 35 locks move after a
staggered start, one per 8 s, so it takes about 3.5 minutes, each move a pause of up to one confirmation interval for
its processor. Not measured: processor downtime per move, and a rolling deploy. Failover after a crash takes about 5 s
longer (the grace, for the locks whose preferred owner was the dead instance). Without spreading, a crash lands
anywhere from evenly to all on one survivor, and a restart usually leaves one instance with nearly everything.

A cheaper variant without membership - an instance holding more locks waits longer before taking a free one - was
looked at and set aside: it spreads only locks that are free while several instances already run, and instances start
seconds apart, so the first one up still takes most locks; making the back-off long enough to bridge that would delay
a single instance's own start-up.

### 3.6 What each queue implementation can use

Whether handling can run on several instances depends on one property of the queue: does it deliver one key's ordered
messages in order when several instances consume the queue? That is `DurableQueues.keepsKeyOrderAcrossInstances()`.
On an engine without it, a key's messages go to whichever node claims them, and two nodes can hold two of a key's
messages at once - so ordered messages need a single consumer (`Inboxes` javadoc, `SingleGlobalConsumer`).

| | `PostgresqlDurableQueues` | Shard-owned engine | MongoDB (`MongoDurableQueues`) | Planned multi-table replacement for `PostgresqlDurableQueues` (expected, no design yet) |
|---|---|---|---|---|
| Key order across instances | No (per node) | **Yes** (one owner per unit, fenced) | No (per node) | Expected no (per node), assignment model unchanged |
| (a0) `PartitionedEventProcessor` | Refuses to start | **Yes** | Refuses | Refuses - unless the design adds cross-instance key order |
| (a1) partitioned views | Refuses to start | **Yes** | Refuses | Refuses - same condition |
| Batched forward (`EventProcessor`) | Yes | Yes | Yes | Yes |
| Batched view handling (`ViewEventProcessor`) | Yes | Yes | Yes | Yes |
| (b) per-partition readers | Would work (order from the reader) | Would work | Would work | Would work |
| (d) spread the fenced locks | **Yes** - the one way to spread work here | Yes, complements (a0)/(a1) | **Yes** | **Yes** |
| What spreads over instances today | Unordered competing consumers only; every exclusive processor on the first instance up | Event-processor handlers (opt-in), every competing inbox; views and readers stay on one | As `PostgresqlDurableQueues` | As `PostgresqlDurableQueues` |

Two consequences:

- **Build (a1) against the capability, never against the shard-owned engine by type.** The capability is what the
  design needs, so any engine that later answers `true` - the planned replacement included, if its design ever gives
  a key one owner across instances - gets (a0) and (a1) without a change here. Answering `true` is a promise: one
  owner per key at a time, and a former owner fenced out of acknowledging.
- **For applications on a per-node engine, EF-2's spreading does not apply.** What they get is the batching (forward and
  view handling), which raises each processor's single-instance rate, and - if built - (d), which spreads whole
  processors. If the planned replacement is meant to scale *one* processor over instances, that is a requirement on its
  design: cross-instance key order, or partitioned readers (b).

### 3.7 Comparison

| | (a0) competing inboxes | (a1) partitioned views | (b) partition readers | (c) CDC fan-out | (d) spread locks |
|---|---|---|---|---|---|
| Status | Built (class) | Proposal | Not pursued | Rejected | Open |
| Spreads handling over instances | `EventProcessor`s | Views | Yes | Yes | Whole processors only |
| Removes the reader ceiling | With the batched forward, mostly | Mostly (batched) | Yes | Only with (b)'s machinery | No |
| Needs cross-instance key order | Yes | Yes | No | No | No - works on every engine |
| Resume point model | Unchanged | Unchanged | N per processor | N per processor | Unchanged |
| Ordering contract | Per aggregate | Per aggregate | Per aggregate | Per aggregate | Unchanged |
| New public API | Class + capability (shipped) | One class, idempotent enqueue | Large | Large | An opt-in on the lock manager |

### 3.8 Trade-offs common to (a0) and (a1)

- **Ordering.** Per aggregate only, as §2 says.
- **At-least-once, with more sources of duplicates than today.** Duplicates come from three places: the reader
  re-forwarding after an unclean failover (≤ `snapshotResumePointsEvery` of events), the engine redelivering after a
  unit moves mid-handler (`docs/durable-queue-shard-owned.md` §8.3, "Handlers must be idempotent"), and the ack not
  committing with the view update (adapter `CLAUDE.md:30`). The Version = EventOrder guard stops being advice and
  becomes a requirement.
- **Re-forwarding collides with the ordered lane's primary key.** `shard_queue_ordered` has
  `PRIMARY KEY (queue_id, shard, msg_key, key_order)` (`ShardOwnedSchema.java:221`), and the ordered insert has no
  `ON CONFLICT` (`ShardOwnedStorage.java:1029-1031`). If a re-forwarded event is still queued, the forwarding
  transaction fails. The reader then retries and, under the default error policy, stops. **Observed (2026-10-30,
  `EventProcessorReForwardOnShardOwnedIT`, IT-4)** on today's exclusive `EventProcessor`: the new reader's re-forward
  fails on `shard_queue_ordered_pkey`, `RETRY_N_THEN_STOP` retries three times within a few hundred milliseconds and
  stops the subscription (ERROR), the auto-resumer restarts it 10 s later, and that repeats until the originals have
  drained - a stall per unclean failover as long as the backlog, not a loss. A partitioned mode needs an idempotent
  ordered enqueue ("already present ⇒ success"), and today's `EventProcessor` on this engine needs it too.
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

(a0) is done (`PartitionedEventProcessor`, §3.1). For the views, build **(a1)** as a new class, gated on the capability that now exists.
Do not build (b) or (c) unless the measurements force it. (d) is independent of (a1) and decided separately (§8). The batching that (a1) relies on already exists on both
processor types, so what is left is the class, the idempotent ordered enqueue and the reset story.

### 4.1 What is added (all additive)

| Addition | Where | Notes |
|---|---|---|
| `KeyPartitionedViewEventProcessor` (working name) | `postgresql-event-store`, `…processor` | Extends `AbstractEventProcessor`. Takes the existing `ViewEventProcessorDependencies` (`ViewEventProcessor.java:100-106`), so no new dependencies bean is needed. Same subscriber id scheme, so a projection switched over keeps its resume point |
| Batched forwarding | same | Processor lock + `batchSubscribeToAggregateEventsAsynchronously` + one `queueMessages` per batch. Batch size and max latency are overridable protected methods |
| Capability check on the queue engine | **Exists**: `DurableQueues.keepsKeyOrderAcrossInstances()` / `Inboxes.keepsKeyOrderAcrossInstances()` (shipped with (a0)) | The processor refuses to start without it, because on a per-node engine competing consumers break key order. (a0) falls back instead; a class whose name promises partitioning should refuse |
| Idempotent ordered enqueue | engine SPI + adapter | A key/order already present counts as success. New method or option; existing enqueue semantics are unchanged |
| Reset epoch | processor + message metadata | §4.4 |
| Docs | `LLM/LLM-postgresql-event-store.md` "Choose Processor" table, a new row; `LLM-traps.md` a new ESS id for "partitioned ⇒ per-aggregate order only" | Plugin `version` bump, per root `CLAUDE.md` |

Existing processors and `ViewEventProcessor`'s behaviour do not change.

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

Two hazards to watch during M3/M4. Kill the reader's instance with `kill -9` mid-run to see the PK collision in §3.8,
or show that it does not happen. And compare per-key order against the exact `EventOrder` sequence, not `isSorted()`
(the trap recorded in adapter `CLAUDE.md`, first gotcha).

---

## 6. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Projections that depend on **cross-aggregate order** (a parent row written by aggregate X before a child row from Y; "latest N across all accounts" feeds) | Missing parent or FK failures, a feed out of order | Do not use the mode for them. The class name and the LLM table say "per-aggregate order only". Handlers that need a parent should tolerate its absence (upsert, or retry with a retryable exception) |
| Views that **read-modify-write shared rows** (totals across accounts, per-instrument aggregates fed by many accounts) | Lost updates under concurrent instances, or hot-row lock contention that erases the gain | Key the row by the aggregate, or update it atomically (`UPDATE … SET x = x + ?`) with an idempotency guard. The demo's account views are keyed by account (`AccountActivityProjection.java` PK `(account_stream_id, event_order)`) and are good candidates; `PositionsProjection`'s per-account version row is too |
| **Aggregate ids that collide across aggregate types** in one processor | The key is the aggregate id alone (`EventProcessor.java:416-417`). Two types sharing an id share a key, and equal `EventOrder`s hit the ordered PK | Qualify the key with the aggregate type in the new class. Note: this already applies to a multi-type `EventProcessor` on this engine today |
| Duplicate forward after reader crash | Reader stops on the ordered PK (§3.8) | Idempotent ordered enqueue (§4.1) |
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
| IT-4 Reader failover, unclean | Reader instance killed mid-batch. Re-forwarded events either hit the idempotent enqueue or are handled twice and absorbed by the guard; the reader **does not stop** (reproducer for §3.8, run first against today's `EventProcessor`) |
| IT-5 Fencing | A reader that has lost its lock but still holds a batch (paused past `leaseTtl`): its late forward is a duplicate and nothing worse |
| IT-6 Reset with in-flight handlers | Two instances, slow handlers, reset mid-run: the final view equals a fresh replay |
| IT-7 Dead letter | A poison event blocks only its key; other keys progress; `resurrectKey` replays in order |
| IT-8 Multi-type key collision | Two aggregate types with the same id in one processor: both streams handled, in order |
| IT-9 Catch-up from zero | 100 k events, reset to `FIRST_GLOBAL_EVENT_ORDER`, 1 vs 2 instances: catch-up time recorded (benchmark-gated) |

Give leases headroom over handler time in IT-2/IT-5, or the test measures a liveness lapse
(`components/postgresql-queue-shard-owned/CLAUDE.md:99`).

---

## 8. Open decisions

Answered since the first version of this document:

- **Where the capability lives** (old Q8): on `DurableQueues`/`Inboxes` as `keepsKeyOrderAcrossInstances()`, not by
  type - shipped with (a0).
- **Should the mode exist only on the `EventProcessor` side** (old Q1, second half): it exists there now, as
  `PartitionedEventProcessor`. What remains is whether views get it too (`PartitionedViewEventProcessor`).
- **Class or setting:** a class per contract (`docs/event-processor-specializations.md`).
- **(a1) and questions 2-7 (2026-10-30):** build it, as `PartitionedViewEventProcessor` (named like
  `PartitionedEventProcessor`), with the recommendations below: a reference in the forwarded message, the key qualified
  with the aggregate type, reset only while the processor is stopped cluster-wide, no new admin entity, a clean stop to
  migrate a view. Question 5 was settled by IT-4 (`EventProcessorReForwardOnShardOwnedIT`, §3.8): the re-forward did stop
  today's `EventProcessor`, so the idempotent ordered enqueue is in the engine, opt-in - `MessageQueue.enqueueIfAbsent`
  (additive default methods) for messages marked `IdentifiedByKeyAndOrder` (foundation), which the forwarded event
  references are - and every other ordered enqueue still refuses a reused key and order.
- **Spread the fenced locks ((d), §3.5)?** Yes, as an opt-in on `DBFencedLockManager`, off by default, with its cons
  (a pause per moved processor on every membership change, whole processors only, new timing cases in the lock manager)
  documented wherever the option is. Built as the prototype the recommendation described (membership lock, rendezvous
  preferred owner, grace on acquire; rebalancing off at first), measured with three instances in the demo, then
  rebalancing, rate-limited, if the prototype holds.

Still open, with a recommendation each:

1. **Do views get per-aggregate-only order at all ((a1) yes or no)?** `ViewEventProcessor` is effectively
   commit-ordered today. *Recommendation:* yes, as a separate class whose name says it, for views keyed by aggregate
   (the demo's account views are); never by changing `ViewEventProcessor`. Decide after the planned queue
   replacement's design says whether it will answer the capability - if it will not, (a1) serves shard-owned users only,
   which may still be enough.
2. **Payload or reference in the forwarded message?** *Recommendation:* reference, as `EventProcessor` does: it keeps
   queue rows and their WAL small and the event store authoritative; revisit only if the consumer's re-read shows up in
   a profile.
3. **Key qualification:** qualify the key with the aggregate type in the new class, or keep the bare id?
   *Recommendation:* qualify in the new class (no collision between two aggregate types sharing an id), and document
   the existing collision for multi-type `EventProcessor`s.
4. **Reset across instances:** epoch plus waiting one `leaseTtl`, or reset only while the processor is stopped
   cluster-wide? *Recommendation:* the admin precondition (stopped cluster-wide) for v1 - simple and obviously
   correct; the epoch only if resets under load turn out to be needed.
5. **Idempotent ordered enqueue** ("already present ⇒ success"): SPI addition (additive default method) or inside the
   adapter, and should it be fixed for today's `EventProcessor` on this engine anyway? *Recommendation:* first run
   IT-4 against today's `EventProcessor`. The two-instance kill of the lock holder in the demo (§1.7) passed, which
   is suggestive, not proof.
6. **Admin representation:** is "reader subscription + queue status" enough for v1? *Recommendation:* yes; a
   processor-level entity is the three-place admin change and can wait.
7. **Migrating an existing view:** reuse `<name>:lock` and `<name>:queue` so a rolling deploy can mix old and new nodes,
   or require a clean stop? *Recommendation:* a clean stop for v1 - mixing inline and partitioned handlers of one view
   is the hardest case to reason about.
