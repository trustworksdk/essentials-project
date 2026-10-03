> **ARCHIVED — historical record, not a description of the shipped engine.**
>
> This is the original clean-sheet design proposal together with the chronological log of what was
> built, what was measured, and what was retracted along the way. Statements of status in it are
> as-of-writing and several are now false; its section numbering is the order the work happened in,
> not a structure.
>
> **For how the engine actually works, read [`docs/durable-queue-shard-owned.md`](../durable-queue-shard-owned.md).**
>
> This file is kept because `§4.3`-style references in source comments point into it, and because the
> defect log and the retractions are the reasoning behind several non-obvious invariants.

---

# Next-Generation Durable Queue — Clean-Sheet Design

**Status:** Design proposal. Nothing implemented yet.
**Goal:** A PostgreSQL-backed durable queue designed from first principles around latency and throughput, keeping the delivery semantics the current `DurableQueues` contract offers.

---

## 1. Goal, scope, non-goals

### Goal

Design a durable queue engine whose steady-state cost per message is the theoretical floor for a mutable-table Postgres queue — **one INSERT and one DELETE, both batched** — and whose enqueue-to-handler latency is bounded by transaction commit rather than by a polling interval.

### Must-keep semantics

The new engine is a new implementation behind the existing public contract. It has to keep:

| Capability | Requirement |
|---|---|
| Unordered messages | At-least-once delivery, no ordering constraint, maximum parallelism |
| Ordered messages | Strict FIFO per message key; a stalled key blocks only that key |
| Retries | Persisted attempt count, configurable backoff, redelivery after failure |
| Dead letter queue | Terminal parking after the retry policy is exhausted, with resurrect |
| Competing consumers | N application instances share the load of one queue |
| Single/exclusive consumer | Exactly one active consumer for a queue across all instances |
| Delayed delivery | Messages that become visible at a future timestamp |
| Transactional enqueue | Enqueue participates in the caller's unit of work (outbox pattern) |

### Non-goals

- Cross-service messaging. This stays an intra-service component, same as today.
- Exactly-once delivery. At-least-once with idempotent handlers remains the contract.
- Replacing the existing implementation on day one. The new engine ships alongside it, selected by configuration, so it can be A/B benchmarked and adopted incrementally.

---

## 2. Where the time actually goes

Any Postgres queue pays from the same cost menu. Designing for speed means removing items from it, not tuning them.

| Cost | Why it hurts |
|---|---|
| **Poll interval** | With a fixed interval `P`, mean idle-queue latency is `P/2` and p99 approaches `P`. At `P = 20ms` that is a ~10ms floor no query tuning can beat. |
| **Claim write** | `UPDATE … SET is_being_delivered = true` is a WAL-logged row update per message, plus an index update if the flag is indexed. It doubles the write cost of every message. |
| **Lock contention** | `FOR UPDATE SKIP LOCKED` means every consumer scans rows other consumers have already taken, then discards them. Wasted index scan work grows with consumer count. |
| **Per-key ordering queries** | Ordered delivery implemented as a query — "find the head of each key group that is not already in flight" — is a correlated/anti-join per fetch. It is the most expensive query shape in the whole system and it runs on every poll. |
| **Dead tuples** | Claim-update plus ack-delete produces two dead tuples per message, and the indexes to maintain alongside them. ⚠️ The predicted consequence — steadily rising p99 under sustained load — was **not** observed in a 6-minute soak (§9 / measurements §3.5); what was observed is 5.3× larger indexes and twice the outstanding dead tuples. Quote this as a structural cost, not as measured degradation. |
| **JSONB** | Payload stored as `jsonb` costs parse-and-validate on insert, a larger on-disk representation, and TOAST detoast on read — for data the database never queries into. |
| **Round trips** | One round trip per message for fetch, and another for ack, puts network+driver latency directly in the per-message budget. |
| **Wide shared table** | All queues in one table means the hot index is diluted by cold queues, and the working set is larger than it needs to be. |

The design below removes the poll interval, the claim write, the lock contention, the per-key ordering query and the JSONB cost, and amortises the round trips.

---

## 3. Core idea: shard ownership

Everything else follows from one decision.

> **Every message is assigned to a shard at enqueue time. Each shard has exactly one owning consumer instance at any moment, held by a lease. A consumer reads only from shards it owns.**

This is the Kafka partition model applied inside Postgres, and it changes the economics:

- **No lock contention.** Two consumers never look at the same row, so `SKIP LOCKED` is unnecessary and no scan work is wasted.
- **Ordering becomes free.** Messages with the same key hash to the same shard, one instance owns that shard, so per-key FIFO is enforced in memory by the owner. The expensive ordering query disappears entirely.
- **The claim write becomes optional.** Nobody else is competing for the row, so "this message is mine" does not have to be written to the database. It is implied by ownership. (Section 6 shows how crash recovery still works.)
- **Competing and exclusive consumers become the same mechanism.** An exclusive consumer is simply one that leases every shard. Two modes, one code path.

Shard count is fixed when the queue is created and is not changeable afterwards — changing it would re-map keys to different shards and break ordering. Rebalancing moves *ownership* of shards between instances; it never changes how many there are. A sensible default is 32.

Unordered messages are assigned a shard round-robin rather than by key hash, so they spread evenly and never inherit another key's head-of-line blocking.

---

## 4. Architecture

### 4.1 Storage layout: three lanes, not one table

Ordered messages, unordered messages and dead letters have different row shapes, different access patterns and — decisively — **different natural primary keys**. Each gets its own table.

**`q_unordered` — the high-volume lane.**

```sql
CREATE TABLE q_unordered (
    queue_id      smallint    NOT NULL,   -- interned from QueueName
    shard         smallint    NOT NULL,   -- assigned round-robin
    seq           bigint      NOT NULL,   -- per (queue_id, shard)
    payload       bytea       NOT NULL,   -- opaque to the database
    payload_type  int         NOT NULL,   -- interned FQCN
    meta_data     bytea,
    enqueued_at   timestamptz NOT NULL,
    visible_at    timestamptz NOT NULL,   -- delayed delivery and retry backoff
    attempts      smallint    NOT NULL DEFAULT 0,
    lease         bigint,                 -- fence or message-lease token; NULL on the fast path
    PRIMARY KEY (queue_id, shard, seq)
) PARTITION BY LIST (queue_id);
```

**`q_ordered` — the per-key FIFO lane.** Same columns plus `msg_key` and `key_order`, but a different primary key and one extra index:

```sql
    PRIMARY KEY (queue_id, shard, msg_key, key_order)          -- head-of-key is a prefix scan
    CREATE INDEX q_ordered_seq ON q_ordered (queue_id, shard, seq);  -- discovery
```

**`q_dlq` — the cold lane.** Dead letters are rare, read by humans, and benefit from richer structure: `jsonb` payload, full error text, no performance constraints. Keeping them out of the live lanes keeps those tables narrow.

#### Why the split earns its keep

The row-width saving is the obvious argument and the weakest one — two nullable columns cost a null bitmap and some alignment, a few percent at most. Three better reasons:

1. **Each lane gets the primary key it actually wants, and they are incompatible.** Unordered discovery is a forward scan on `seq`. Ordered access is *by key* — find the head of key K, check whether key K has anything queued. In one table only one of `(queue_id, shard, seq)` and `(queue_id, shard, msg_key, key_order)` can be the primary key; the other becomes a secondary index maintained on every insert, **including the unordered ones that never use it**. Split, each lane's hottest access is a primary-key prefix scan. This also closes the `hasOrderedMessageQueuedForKey` index gap noted in §8.5 — it becomes a PK prefix existence check rather than a missing index.
2. **Index maintenance is paid on the right lane.** The ordered lane carries two indexes (its PK for access, `q_ordered_seq` for discovery); the unordered lane carries one. Since the unordered lane is normally the high-volume one, the cost lands where there is least of it. For an all-ordered workload the split is still never worse — it just stops being free.
3. **The two lanes were already separate queries.** The current implementation has a `useOrderedUnorderedQuery` flag that emits distinct ordered and unordered statements against one shared table. The queries had already diverged; the storage had not caught up.

Shared choices across both live lanes:

- **`bytea`, not `jsonb`.** The database never looks inside a payload. Bytes skip JSON validation on write and detoast on read, and shrink the row. The admin UI decodes application-side, as it would for any non-JSON serializer.
- **Interned `queue_id` and `payload_type`.** A `smallint`/`int` in the primary key instead of repeated `text` makes the index dramatically denser, which is what keeps the hot working set in shared buffers.
- **No `is_being_delivered`, no `delivery_ts`.** Ownership replaces them; `lease` covers the cases ownership cannot (§8.4).
- **Partitioned by `queue_id`.** Each queue's hot index stays small rather than diluted by unrelated queues, and purging becomes a partition drop.
- **`fillfactor = 80`** so the rare failure-path `UPDATE` is a HOT update touching no index.
- One secondary index each for the recovery sweep and delayed messages: `(queue_id, shard, visible_at)`.

#### What the split costs

Every operation addressed by `QueueEntryId` — `getQueuedMessage`, `deleteMessage`, `retryMessage`, `markAsDeadLetterMessage`, `acknowledgeMessageAsHandled`, `getQueueNameFor` — now has to know which lane the id lives in. Two ways out, and the choice matters less than it looks: encode the lane in `QueueEntryId` (rejected — it is a public type with a stable format), or query both lanes.

Querying both is fine, because **every one of those operations is already off the fast path.** Acknowledgement on the hot path is a `seq`-range delete against a known lane (§4.3), not a lookup by id. The by-id operations are the compatibility and admin surface, where an extra lookup is affordable. Counts and `getQueueNames` become a `UNION ALL` over two narrow indexes.

One thing to watch: partitioning by `queue_id` across two live tables doubles the partition count. Postgres is comfortable with hundreds; a deployment with thousands of queues should partition by hash of `queue_id` instead, and that threshold needs measuring rather than guessing.

### 4.1.1 A storage seam beneath the SPI

The lane split, the partitioning, the interning and the lease column are all *storage* concerns. Retry policy, dead-lettering, ordering enforcement, lease lifecycle and backpressure are *engine* concerns. Putting a seam between them is worth doing, with one firm constraint on its scope.

Above the seam: one engine holding all policy. Below it: a `QueueStorage` implementation that routes to lanes, owns the SQL, and knows about partitions and cursors. The phased plan in §9 becomes genuinely independent as a result — Phase 1 is "implement the new storage" with the engine unchanged; Phase 3 changes the read strategy without touching policy.

**The constraint: this seam is the Postgres engine's internal layering, not a universal storage SPI.** The design depends throughout on Postgres-specific capabilities — `LATERAL`, primary-key range deletes, partial indexes, `LISTEN`/`NOTIFY`, logical replication, `bytea`. An abstraction built to also fit MongoDB would be a lowest common denominator that forbids precisely the mechanisms that make this fast. `MongoDurableQueues` keeps implementing `DurableQueues` directly rather than being forced through this seam; where policy genuinely duplicates between them, it is lifted into shared helpers above both, not into a shared storage interface below.

Capability-extension interfaces are the established pattern here — `BatchMessageFetchingCapableDurableQueues` already works this way — and are the right shape for anything the seam cannot express uniformly.

**`q_shard_lease` — ownership.**

```sql
CREATE TABLE q_shard_lease (
    queue_id    smallint    NOT NULL,
    shard       smallint    NOT NULL,
    owner       text,                   -- instance id
    fence       bigint      NOT NULL,   -- monotonic, incremented on every ownership change
    lease_until timestamptz NOT NULL,
    PRIMARY KEY (queue_id, shard)
);
```

### 4.2 The read path

The owner of a shard keeps an in-memory cursor: the highest `seq` it has read. Reading is then a pure forward range scan on the primary key:

```sql
SELECT … FROM q_lane
 WHERE queue_id = ? AND shard = ? AND seq > ? AND visible_at <= now()
 ORDER BY seq
 LIMIT ?;
```

This is the cheapest query shape Postgres offers: an index range scan in physical order, no filtering of rows that belong to someone else, no anti-join, no dead-tuple rescanning (acked rows are gone). It reads ahead in batches, so the per-message share of a round trip is `1/batchSize`.

**One query per instance, not per shard.** An instance owning many shards across many queues coalesces the whole read into a single statement:

```sql
SELECT m.* FROM unnest(?::smallint[], ?::smallint[], ?::bigint[]) AS o(queue_id, shard, cursor)
CROSS JOIN LATERAL (
    SELECT * FROM q_lane m
     WHERE m.queue_id = o.queue_id AND m.shard = o.shard
       AND m.seq > o.cursor AND m.visible_at <= now()
     ORDER BY m.seq LIMIT ?
) m;
```

This makes the cost of an idle queue essentially zero, which is the case that dominates real deployments — most services have many queues and only a few are busy at any moment.

⚠️ **Measured caveat (§9 Phase 0).** Against a matched control, 29 idle queues cost the current implementation 0.3%. Idle polling is already close to free at that scale, so this optimisation is not the win it reads as here until the queue count is far higher. Either establish the count at which idle cost becomes material, or drop the claim.

### 4.3 The write path: no claim, batched ack

**Enqueue** is a single multi-row `INSERT`. Producers batch within a transaction and across concurrent callers via group commit.

**Delivery** writes nothing. The owner dispatches from its read-ahead buffer to a worker pool.

**Ack** is batched. The dispatcher collects completed `seq` values and flushes them on whichever comes first: `ackBatchSize` messages, or `ackFlushInterval` (target: 1ms). Because acks in a shard are usually a contiguous prefix, the flush is a range delete plus a small out-of-order remainder:

```sql
DELETE FROM q_lane WHERE queue_id = ? AND shard = ? AND seq <= ?;              -- contiguous prefix
DELETE FROM q_lane WHERE queue_id = ? AND shard = ? AND seq = ANY(?);          -- stragglers
```

A range delete over a physically contiguous run of rows is far cheaper for both the delete itself and the subsequent vacuum than the same number of scattered single-row deletes.

⚠️ **The range delete has a floor, and getting it wrong loses messages.** An earlier version of this section specified the delete above with no bound below, which contradicts §4.4 — and the two together lose messages. A hole at sequence value *N* is a row that has not committed yet. Once it commits it sits **below** the acknowledged prefix, so the next range delete removes it without it ever having been delivered.

This is not hypothetical: the implementation reproduced it as **896 of 900 messages delivered, on every run**, as soon as the test constructed the hazard deliberately rather than hoping for it. It went unnoticed while the test held every producer transaction open for the same duration, because uniform hold times make allocation order and commit order coincide and no hole forms.

So the prefix is bounded by the lowest sequence value that is either in flight or a known hole:

```
safeFloor        = min(lowest in-flight seq, lowest pending hole seq)
contiguousThrough = highest contiguous acked seq strictly below safeFloor
```

Nothing at or above `safeFloor` may be deleted, however contiguous the prefix looks. §4.3 and §4.4 have to be read together, and an implementation that reads only one of them has a message-loss bug.

**Steady-state cost per message: one INSERT and one DELETE, each amortised across a batch.** That is the floor.

### 4.4 The completeness problem, and why the cursor is safe

A cursor over `seq` has a well-known hazard. Producer A takes `seq = 100`, producer B takes `seq = 101`, B commits first. A consumer reading `seq > cursor` sees 101, advances its cursor to 101, and then A commits — 100 is now permanently behind the cursor and would never be delivered.

Three ways out, and the reason for the choice:

1. **Serialize sequence assignment inside the enqueue transaction** so `seq` order equals commit order. Correct, but it holds a row lock for the duration of the caller's transaction. For the outbox pattern — where enqueue joins a long business transaction — that is unacceptable.
2. **Keep a claim marker** so completeness is "unclaimed rows exist" rather than "seq beyond the cursor". Correct, but reinstates the per-message write we just removed.
3. **Detect holes and chase them.** Chosen.

Because the shard has exactly one reader, hole tracking is cheap and entirely in memory. The owner records which `seq` values it has actually observed. A gap in the observed sequence means a concurrent enqueue transaction has not committed yet (or aborted). The owner:

- **does not stall the cursor** — it keeps delivering everything it can see;
- issues a targeted `WHERE seq = ANY(missingSeqs)` re-query after `gapChaseDelay` (a few milliseconds), which resolves as soon as the late transaction commits;
- declares a hole permanently absent (aborted transaction) after `gapExpiry`, which is set above the longest expected enqueue transaction.

A hole costs a few milliseconds of extra latency **for the delayed message only**, and it costs one cheap point-lookup query. Nothing else is affected. In exchange, the common path stays write-free.

A slow periodic **head sweep** using the `(queue_id, shard, visible_at)` index is the backstop that guarantees nothing is ever lost, whatever happens to the in-memory state:

```sql
SELECT … FROM q_lane
 WHERE queue_id = ? AND shard = ? AND visible_at <= now()
 ORDER BY seq LIMIT ?;
```

The owner filters the result against its in-memory in-flight set and delivers anything it does not recognise. Because acked rows are deleted, this set is normally tiny — only in-flight and retry-waiting messages. It runs at a low frequency (target: every 1–5 seconds) and is pure insurance.

### 4.5 The three delivery transports

Wakeup is tiered. Each tier is faster than the one below it, each is optional, and **correctness never depends on any of them** — the poll/sweep in §4.4 is always the floor.

**Tier 0 — adaptive poll (always on).** The coalesced multi-shard query of §4.2, at an interval that backs off when idle and collapses to zero when a wakeup arrives. This is the correctness backstop, not the latency mechanism.

**Tier 1 — `LISTEN`/`NOTIFY` hint.** On commit, the enqueuing instance notifies with `(queue_id, shard)` — a hint, not a payload. Listening owners wake and issue a targeted read for exactly that shard. Two rules matter:

- **The notify is issued by the application at transaction end, not by a row-level trigger.** A per-row trigger turns every message into a notify, and Postgres serializes notify-queue access cluster-wide. Coalescing to at most one notify per shard per transaction — and rate-limiting to at most one per shard per few milliseconds under load — keeps this cheap.
- **A dedicated listener connection.** The listening connection never does work; a busy listener delays every notification behind it.

Expected effect: p50 enqueue-to-handler drops from `pollInterval/2` to roughly one round trip.

**Tier 2 — local hand-off with pre-claimed insert.** When the enqueuing JVM also owns the target shard — extremely common for outbox and single-instance deployments — the message never needs to be read back at all. The row is inserted for durability, and a post-commit hook hands the already-in-memory message straight to the local dispatcher.

The subtlety is preventing double delivery. It is handled with no extra write: the insert records the owner's `fence` in a `dispatched_by` column, and the reader's query excludes rows carrying its own current fence. The owner knows it already has them. On crash the lease expires, the fence changes, and the new owner's query no longer excludes them — so they are redelivered. Exactly the behaviour we want, at zero cost.

Expected effect: enqueue-to-handler latency collapses to the producer's own commit latency plus microseconds.

**Tier 3 — WAL streaming (optional).** Deliver from the logical replication stream instead of querying at all. The enqueue `INSERT` is decoded from the WAL and pushed to the consumer, which removes the entire read path from the query engine and gives commit-order delivery for free.

This repo already has the machinery: `WalReplicationTailer`, `Wal2JsonLogicalDecodingPlugin`, pgoutput publication management, slot lifecycle and invalidation handling, consumer groups, backpressure and poison handling — currently in `postgresql-event-store`. Adopting it here means extracting it to a module both can depend on.

It is opt-in because it carries real operational weight: `wal_level = logical`, replication privileges, and a slot that pins WAL on disk if a consumer dies. Those risks are already understood and instrumented in the existing CDC scenarios.

### 4.6 Retries and dead letters, off the hot path

Failure is rare, so it is allowed to be expensive.

On handler failure the owner:
1. computes the next backoff from the redelivery policy;
2. issues `UPDATE q_lane SET attempts = attempts + 1, visible_at = ? WHERE queue_id = ? AND shard = ? AND seq = ?` — a HOT update, no index touched;
3. keeps the message in an **in-memory timer wheel** and re-dispatches it when the backoff expires, without ever re-reading it.

The database row is durability backup, not the retry mechanism. If the owner crashes, the new owner finds the row through the head sweep and honours `visible_at`.

When attempts exceed the policy, the message is moved to `q_dlq` and deleted from its lane in one transaction. Resurrect is the reverse.

**Crash-loop protection.** A handler that crashes the JVM never gets to increment `attempts`, so a poison message could loop forever. On lease takeover the new owner issues a single bulk statement before it starts:

```sql
UPDATE q_lane SET attempts = attempts + 1 WHERE queue_id = ? AND shard = ?;
```

One statement over the small unacked set. Poison messages now reach the DLQ even under repeated crashes.

### 4.7 Ownership, rebalancing and fencing

Leases are acquired opportunistically:

```sql
UPDATE q_shard_lease
   SET owner = ?, fence = fence + 1, lease_until = now() + ?
 WHERE queue_id = ? AND shard = ?
   AND (lease_until < now() OR owner = ?)
RETURNING fence;
```

An instance takes at most `ceil(shards / knownInstances)` shards, which makes the assignment self-balancing without a coordinator. Heartbeats renew at a third of the TTL. Losing a heartbeat means the owner stops dispatching immediately and drains its in-flight set.

**Ordering safety across a rebalance** is the one genuinely hard correctness question. A lease is a time-based guarantee, and time-based guarantees can be violated by a stop-the-world GC pause or a clock jump. The mitigation is the `fence` token: every ack and every retry update carries the fence it was issued under, and the statement joins `q_shard_lease` to assert the fence still matches. A stale owner's writes are rejected rather than silently applied. This costs one extra index lookup per *batch*, not per message.

The residual risk — a stale owner that has already dispatched a message to a handler before it notices it lost the lease — is inherent to every lease-based system including Kafka's. It is bounded by the lease TTL and must be documented as such, not papered over. Handlers that need strict guarantees still need to be idempotent.

**Exclusive consumers** lease all shards. If another instance is already holding some, the exclusive consumer is simply not active yet. This reuses the same protocol with no special case.

### 4.8 Backpressure

Read-ahead is bounded by an in-flight budget per shard. When workers fall behind, the fetcher stops reading rather than buffering — the queue depth belongs in the database, which is designed to hold it, not in JVM heap. The ack flusher gets its own connection so ack throughput is never blocked behind fetch.

---

## 5. Latency and throughput targets

These are hypotheses to be measured, not claims. They exist so each implementation phase has a number to be judged against.

**Enqueue-commit to handler-start, p50 / p99:**

| Configuration | p50 | p99 |
|---|---|---|
| Baseline (20 ms poll) — **measured, §9 Phase 0** | **20.7 ms** | **27.1 ms** |
| Tier 1, NOTIFY hint | **1.95 ms** (measured, §9.4) | **4.14 ms** (measured) |
| Tier 2, local hand-off | **0.51 ms** (measured, §9.4) | **1.05 ms** (measured) |
| Tier 3, WAL streaming | ~2ms | ~8ms |

**Throughput, single instance against a modest Postgres:**

| Path | Target |
|---|---|
| Enqueue, batched | ≥ 30k msg/s |
| Consume, unordered | ≥ 30k msg/s |
| Consume, ordered, 1000 keys | ≥ 20k msg/s |
| Idle cost, 100 idle queues | < 1 query/s total |

**Resource targets:**

| Metric | Target |
|---|---|
| WAL bytes per message | Within 1.5× of payload size — baseline measures ~9× |
| Dead tuples per message | 1 (the ack delete) — baseline measures 1.8–2.4 |
| DB round trips per message | < 0.1 (batching) |

---

## 6. Failure modes

| Failure | Behaviour |
|---|---|
| Consumer JVM crash | Lease expires; another instance takes the shard, bulk-increments `attempts`, redelivers everything unacked from the head. At-least-once holds. |
| Handler throws | Backoff via `visible_at`, in-memory timer wheel re-dispatch, DLQ after policy exhaustion. |
| Handler hangs | Per-message handling timeout forces a failure so the in-flight slot is released. |
| Late-committing enqueue | Hole chasing (§4.4) delivers it within a few milliseconds; head sweep is the backstop. |
| Aborted enqueue transaction | Hole expires after `gapExpiry` and is dropped. |
| NOTIFY lost or queue overflows | Poll and sweep still deliver. Latency degrades; correctness does not. |
| Replication slot dies (Tier 3) | Fall back to Tier 1/0. Existing CDC slot-invalidation handling applies. |
| Clock skew | Bounded by lease TTL, fence tokens reject stale writes. Documented residual risk. |
| Split-brain during rebalance | Fence assertion rejects the stale owner's acks; message is redelivered. |

---

## 7. Risks and open questions

1. **The fence-checked ack costs a join.** Measure whether asserting the fence per ack batch is cheap enough, or whether a cached lease with a short validity window is a better trade.
2. **Shard count is immutable.** Getting the default wrong is a migration, not a config change. Needs a documented sizing rule and possibly a supported re-shard procedure via drain.
3. **`bytea` payloads change the admin UI.** Payload inspection moves application-side. Worth confirming no admin feature queries into payload JSON today.
4. **Tier 3 operational weight.** Logical replication requires privileges many deployments will not grant. It must remain genuinely optional, and the default path must be fast without it.
5. **Pull-style operations under an ownership model** — resolved in §8. Lease scope turned out to be a dial rather than a fork (§8.4), and its default is derived from the message rather than configured, so nothing is left open here beyond calibrating the default batch width.
6. **Postgres version floor.** Partitioning, `SKIP LOCKED` removal and the `LATERAL` read all work on PG 12+, but partition-wise behaviour improves materially in later versions. Pick and document a floor.
7. **The head sweep interval is a tuning knob with a correctness flavour.** Too long and a takeover redelivers late; too short and it costs. Needs measurement, not a guess.

---

## 8. The pull API, and leases as one primitive

### 8.1 What the requirement actually is

The existing API exposes `getNextMessageReadyForDelivery`. The name says query; the implementation is a claim-and-return that increments `total_attempts`, sets `is_being_delivered`, nulls `next_delivery_ts` and returns the row in a single statement. Calling it dequeues.

It is tempting to treat "make that method work on the new engine" as the design question. It is the wrong question — it takes an accident of the current shape as a requirement. Asking instead *who pulls, and why* gives three separate requirements:

| | Requirement | Who | Currently served by |
|---|---|---|---|
| **R1** | Deliver a message inside a unit of work the **caller** controls, so business writes and the acknowledgement commit together | Inbox / Outbox | `TransactionalMode.FullyTransactional` — documented as broken for retries and DLQ, because a rollback reverts the attempt count |
| **R2** | Let the caller drive the loop | Tests, admin and replay tooling, batch steps | The pull method, incidentally |
| **R3** | Support a handler that runs longer than the default timeout | Long-running handlers | One global `messageHandlingTimeoutMs` per queue, plus a periodic stuck-message reset sweep |

R1 is the load-bearing one, and it is the one currently unmet: the mode that offers caller-controlled transactions breaks redelivery, and the mode that fixes redelivery gives up the atomicity that motivated it. That is a real defect, not a performance question, and the new engine should fix it rather than reproduce it.

### 8.2 Option space

Four ways to serve pull under an ownership model. They are worth laying out because the interesting difference between the first two is not obvious.

**Option 1 — Message lease (visibility timeout with extension).** A pull writes a lease — expiry plus token — onto the row, and the caller can extend it while it works. This is what SQS, Google Pub/Sub, pgmq and river all do. Serves R2 and R3 well. Costs **one write per pulled message**, because a lease that is not recorded is not a lease.

**Option 2 — Shard lease, i.e. a session.** The caller opens a session that leases a shard or a slice of one, then pulls from it. Because the lease already establishes ownership, individual pulls write **nothing** — they are the same cursor read the engine's own consumer uses. Serves R2 and R3 at no per-message cost. The price is that the caller holds a shard for the session's life, which blocks other consumers for that shard. That is precisely how SQS FIFO behaves with message groups, so it is a defensible and well-understood trade rather than a novel hazard.

**Option 3 — Pull as a degenerate consumer.** No new API: run a normal handler that hands messages to a blocking queue the caller drains. Adds no concepts, but inverts control awkwardly and cannot give the caller a transaction boundary. Fails R1.

**Option 4 — Offset commit, the Kafka model.** No per-message state at all; acknowledgement is an offset advance. Elegant and the cheapest possible, but it cannot express per-message retry and dead-lettering without a side structure, and a caller-controlled per-message transaction does not fit an offset model. Set aside for the same reasons the main design does not use a commit-order cursor.

**The finding.** Options 1 and 2 differ *only* in lease granularity — and the per-message write in Option 1 is a consequence of choosing message granularity, not an inherent cost of pulling. That only becomes visible by asking the general question. Working backwards from the existing method's signature leads straight to Option 1 and never surfaces Option 2 at all.

### 8.3 Decision: one primitive, two granularities

A shard lease held by a consumer and a message lease held by a puller are the same idea at two scales. The engine adopts both and treats them uniformly.

- **Session pull (Option 2) is the recommended path**, and it is the *same* mechanism the engine's own consumers use — manual callers get the fast path rather than a slow compatibility lane.
- **Message lease (Option 1) is offered** for a caller who genuinely wants one message without taking a shard, accepting the write. This is also what `getNextMessageReadyForDelivery` maps onto, with a default TTL — its signature is unchanged and its observable semantics are near-identical, since it already writes and already has a timeout. The timeout simply becomes per-message and extendable instead of global.
- **The stuck-message sweep stops being a concept.** `resetMessagesStuckBeingDelivered` and consumer-crash takeover become one mechanism: lease expiry, applied at whichever granularity the lease was taken. One rule replaces two.

**R1 is fixed, not reproduced.** The lease write and the acknowledgement are separate operations, and the *acknowledgement* can join the caller's unit of work. So business writes and the ack commit atomically — the property `FullyTransactional` was reaching for — while attempt counting and dead-lettering stay outside the caller's rollback scope, which is what made the old mode break. Both halves, for the first time.

### 8.4 Lease scope is a dial, not a fork

The open question was whether a session should lease a whole shard or a slice of one. Framed as two designs to choose between, it invites building both and picking by benchmark. Framed correctly it is neither: **"slice" is not a third mechanism — a slice of n messages is n message leases taken in one statement.** So the choice collapses into a single parameter, *how much does a session take ownership of at once*, with four useful settings:

| Scope | Write cost | Blocks | Ordering safe? | Use |
|---|---|---|---|---|
| **Message** (n = 1) | 1 write per message | that message | n/a | Compatibility surface for `getNextMessageReadyForDelivery` |
| **Batch** (n > 1) | 1 write per n | those n messages | **No** | Unordered pull, long or short sessions |
| **Key** | 1 write per key | one key | Yes | Ordered pull without blocking a whole shard |
| **Shard** | zero | the shard | Yes | The engine's own consumers; short ordered sessions |

Both underlying mechanisms already have to exist: shard leases for the consumer path (§4.7), and a per-row lease column for Tier 2's local hand-off (§4.5) and the compatibility surface. Scope is which one a given session uses and at what width. **No new machinery, no second code path.**

**Key scope is the rung worth noticing.** It did not appear in the original either/or framing, and it is the right answer for the case that motivated the question — a long-lived ordered session that must not block a whole shard. It is also exactly how SQS FIFO behaves: while a message from a group is in flight, no other message from that group is delivered, and other groups are unaffected.

#### What actually decides it

Not throughput. The write cost of batch scope is `1/n`, so it converges on shard scope quickly — by n ≈ 50 the difference is a couple of percent and will sit inside run-to-run variance. **A benchmark comparing shard scope against batch scope at a sensible n will most likely report "no significant difference", which is a finding but not a decision.** Planning to decide this by measurement alone would leave it undecided.

What discriminates is **ordering**, and it is not a preference:

- **Unordered messages** — batch scope is safe at any width. Nothing is being guaranteed that a concurrent reader could violate.
- **Ordered messages** — batch scope is *unsafe*. If a puller leases messages 5, 6 and 7 for key K while the shard owner still owns the shard, nothing stops the owner dispatching K's message 8 concurrently: the per-key in-flight set that enforces order lives in the owner's memory, and the puller is in a different JVM. Order survives only if the lease is taken at key scope or wider.

So the default is derived from the message, not configured: **unordered pull defaults to batch scope, ordered pull defaults to key scope**, and shard scope stays what the engine's own consumers use. The dial remains exposed for callers with a reason to override, but no one has to reason about it to be correct.

The measurement that *is* worth running is a different one: not "which is faster" but **where the amortization curve flattens**, which sets the default n, and **how blocking cost grows with session duration**, which tells a caller when to prefer key scope over shard scope. Both are calibration, not adjudication.

#### The cost of keeping the dial

Configurability is not free even when the implementation is. Four scopes are four semantic contracts, and the expensive tests here are the ordering and chaos suites, not the throughput runs.

That cost is contained by the fact that scope is derived rather than free-floating: ordering guarantees only need verifying under key and shard scope, and batch scope only needs unordered correctness, which is far cheaper to test. The matrix is additive, not multiplicative. If it ever stops being additive, the right response is to drop a rung rather than keep testing all four.

### 8.5 What this costs elsewhere

Three concrete consequences to carry into implementation:

- **Attempt counting moves.** On the fast path, `attempts` is incremented at failure or at takeover, not at dispatch. A message inspected via the admin API *while in flight* reads one lower than it does today. A deliberate, documented behaviour change — not a bug to be discovered later.
- **`hasOrderedMessageQueuedForKey` — resolved by the lane split.** An earlier draft left `msg_key` unindexed. Giving the ordered lane its own table with `(queue_id, shard, msg_key, key_order)` as the primary key (§4.1) turns that call into a primary-key prefix existence check, with no extra index to maintain.
- **One inherited semantic needs a decision.** In the current ordering query, the per-key head subquery filters on neither the dead-letter flag nor the delivery flag, so a dead-lettered ordered message blocks every later message for its key until it is resurrected or deleted. That may well be intended as fail-stop ordering, but the new engine enforces ordering in memory and would have to reproduce it on purpose. It needs a characterization test and an explicit decision before Phase 4, either way.

## 9. Implementation status

Nothing in the shipped modules has been touched. Everything below lives in `examples/essentials-performance-lab`, which is not a published artifact — so the design can be built and measured with no release surface, and promoted to a component only once it has earned it.

| Built | What it covers |
|---|---|
| `nextgen/NextGenSchema` | Both live lanes and the shard-lease table, per-shard sequences, `bytea` payloads, each lane's own primary key, key-to-shard hashing |
| `nextgen/NextGenStorage` | The storage seam for both lanes: batched insert, cursor read, targeted hole lookup, head sweep, floored range-delete ack, lease acquisition with fencing, takeover attempt bump |
| `nextgen/ShardOwner` | Unordered lane: cursor, hole detection and chasing, in-flight and pending-ack sets, batched ack flush, head sweep |
| `nextgen/OrderedShardOwner` | Ordered lane: per-key FIFO in memory, cross-key parallelism over a handler pool, per-key requeue on failure, order-violation counting |
| `nextgen/NextGenQueue` | Engine facade with its own API — shard leasing, competing and exclusive consumers as one mechanism, both lanes |
| `NextGenQueueIT` | Four unordered semantics tests: exactly-once-and-drained, no loss under out-of-order commits, redelivery after an owner crash, no double delivery across two instances |
| `NextGenOrderedQueueIT` | Three ordered semantics tests: delivery in `key_order` per key with zero order violations, a stuck key not blocking same-shard keys, stable key-to-shard routing |
| `nextgen/RedeliveryPolicy` | Fixed and exponential backoff, attempt ceiling |
| Failure path in both owners | In-memory retry schedule, HOT-update backoff, atomic dead-letter move, per-key blocking during retry |
| `NextGenRetryAndDeadLetterIT` | Three failure tests: retry-then-succeed without duplication, dead-lettered exactly once after the policy is exhausted and never resurrected, and a failing message not overtaken by later messages of its key |

Ten tests, three consecutive green runs.

| `nextgen/NextGenListener`, `ShardWakeup` | Tier 1 wake-up: coalesced `pg_notify` inside the enqueue transaction, dedicated listener connection, per-shard signal |
| Tier 2 in `NextGenStorage`/`ShardOwner` | Pre-claimed insert stamped with the owner's fence, excluded from that owner's own read, handed over in memory after commit |
| Fencing and heartbeat | Acknowledgements assert the lease they were issued under; a heartbeat renews at a third of the lease lifetime and stops an owner that has been superseded |
| `NextGenLocalHandoffIT`, `NextGenFencingIT`, `NextGenLatencyIT` | Hand-off without double delivery, recovery of rows pre-claimed by a dead owner, superseded owners unable to acknowledge, heartbeat keeping an idle owner alive, and the latency comparison |
| `NextGenVsBaselineCostIT`, `NextGenCostDecompositionIT` | The cost comparisons below, benchmark-gated |

**Not built:** Tier 3 WAL streaming. `SessionScope.KEY` is not merely unbuilt — §9.18 shows it cannot exist in this engine.

### 9.15 Pull sessions, at the scope the design recommends

`openSession` is implemented for `SHARD` scope, which §8.3 identifies as the recommended one and the only scope costing no per-message write: ownership is already recorded in the lease table, so individual messages need no claim. Polling is the same cursor read the engine's own consumers use, so a caller that pulls gets the fast path rather than a compatibility lane.

It serves all three requirements the old pull method bundled behind one signature — a caller controlling its own transaction boundary, a caller driving its own loop, and a handler outliving any queue-wide timeout — and it is fenced like every other write: a superseded session's acknowledgement is refused and the message stays for whoever now owns it.

Holding a shard excludes consumers from it for the session's life, which is the price and is the same trade SQS FIFO makes with message groups. Closing hands the shards straight back rather than making the next consumer wait out a lease.

**`MESSAGE`, `BATCH` and `KEY` scope are refused with the reason.** Each needs a per-row lease *with its own expiry*, so a row a session holds is hidden from the shard's owner while the session lives and visible again once it does not. The row carries a bare fence and no expiry, which cannot express that: the owner's read has no way to tell a live session's claim from a dead owner's pre-claim, and it must treat those two oppositely. That is a schema change rather than an oversight, and shard scope is the recommended path regardless.

### 9.8 A node that is alive but frozen

`SIGKILL` proves recovery from death; it cannot produce the case fencing actually exists for — a node that still holds its connections, its in-memory state and its belief that it owns shards, but has stopped renewing leases. `SIGSTOP` produces exactly that, and `SIGCONT` then resumes a node convinced it still owns work another node has taken.

The guarantee held: the frozen node's lease lapsed, the survivor took over, and when the frozen node resumed and tried to acknowledge, **nothing was lost**. From outside the processes that is the only observable form the check can take, and it is the right one — a stale acknowledgement that succeeded would have deleted messages the new owner had not yet delivered.

**Two things the test found that reasoning had not.**

First, the recovery behaviour is better than the test initially assumed. A resumed node does not simply stay excluded: its stale leases are refused, its owners stop, and on the same heartbeat it re-registers as a live instance, recomputes its fair share and legitimately re-acquires shards. A node recovering from a long pause rejoins and does useful work again. The first version of the test asserted it would go quiet, which is the weaker and less desirable outcome.

Second — and this is why the test was worth writing — **it did not rejoin, because of a real bug.** `rebalance` pruned owners that had lost their leases only *after* deciding what to acquire, so `ownedShards` still mapped every shard to a dead owner, the acquire loop skipped them all as already-held, and the node never took anything again. One long stop-the-world pause and it was permanently degraded until restart. It survived every same-JVM test, and appeared the moment a process was actually frozen.

### 9.7 A new contract, not an adapter

The remaining question was how the engine meets consumers. Working through `DurableQueues` member by member, roughly two fifths of it encodes decisions this design does not make — so implementing it would not be compatibility, it would be reintroducing the mechanism the design exists to remove.

| Member | Verdict |
|---|---|
| `parallelConsumers` | **Artefact.** Concurrency here is shard count × per-key parallelism, both engine-decided. The knob would be a no-op, and an option that does nothing is worse than an absent one. |
| `TransactionalMode` | **Artefact.** Its two values exist because the old design could give atomicity or working retries, not both. Under a lease both are available at once (§8.3), so there is nothing to choose. |
| `getNextMessageReadyForDelivery` | **Artefact.** Named a query, implemented as a claim. §8 showed it bundles three requirements behind one signature. |
| ack / delete / retry / dead-letter **by id, from any caller** | **Artefact.** Assumes any caller may act on any message, which is only true when every message carries a claim flag. Supporting it means writing that flag per message — the largest cost the design removes. |
| Transactional enqueue, handler consumption, per-key ordering, retry and dead letters, depth, purge | **Genuine.** Consumer needs, independent of either implementation. |

So: `spi/MessageQueue` and its supporting types, with each omission justified in the interface's own javadoc so a future reader can disagree on the merits rather than assume it was never considered. `NextGenMessageQueue` implements it over the engine, and `NextGenSpiIT` exercises it without reaching past the contract — an interface that needs the implementation's types to be useful has not replaced anything.

**Writing the implementation found two defects the interface alone had not shown**, which is the reason to write it rather than stop at the design:

1. **The two lanes were competing for the same shard leases.** One lease row per shard, but separate owners per lane — so an unordered message could land in a shard leased by the ordered consumer, which never reads that lane, and simply sit there. Forty of forty unordered messages were never delivered. The lease key now includes the lane.
2. **Dead letters did not carry their shard**, so `resurrect` looked in shard 0 for a message parked elsewhere and silently failed. The identity type carries it now, which is the argument for `MessageId` being structured rather than opaque: the structure *is* the addressing scheme.

**`openSession` is deliberately unimplemented** and says so. §8 established what the scopes mean and which are safe for ordered messages; building it needs its own tests, and an interface member that silently does the wrong thing is worse than one that refuses.

**Tier 3 is unlikely to be worth building.** Its gate in §10 is that it must clearly beat Tiers 1 and 2 by enough to justify a replication slot's operational weight. Tier 2 measures 0.51 ms at the median and 1.05 ms at p99 (§9.4); a WAL stream will not improve on that by a margin that pays for `wal_level = logical`, replication privileges and a slot that pins WAL on disk when a consumer dies. The gate exists to prevent building it anyway, and on the evidence it should hold.

### 9.6 Rebalancing without a coordinator

Each instance heartbeats into a membership table and independently computes the same fair share — `ceil(shardCount / liveInstances)` — then holds no more than that. An instance over its share releases the excess; one under it takes whatever is free. Because every instance derives the same number from the same table, the split converges with nobody deciding it.

Two details do the work. Membership needs its own table rather than being inferred from the lease table, because an instance holding no shards — the one that most needs to be counted, since it is waiting for a share — leaves no trace there. And releasing a lease deliberately does *not* bump the fence; the next acquirer does. Bumping on release would invalidate the releasing owner's own in-flight acknowledgements before it has finished draining them.

`NextGenRebalanceIT` asserts the parts that can silently go wrong: eight shards split exactly four and four once a second instance appears, no message delivered by both instances while the shards move, and — the case a naive fair-share rule gets wrong because it only ever sheds load — the survivor taking all eight back when the second instance leaves, then successfully handling new work on them.

**Proactive shedding is still not applied to the ordered lane.** Taking a shard from a living owner would move it mid-key, and that needs its own test before it is switched on. Ordered shards are picked up when they become *free* — which happens when an owner dies, not when one is merely busy — so failover works without mid-key migration.

### 9.14 Batch enqueue was not atomic, and small batches hid it

`enqueue` takes a list, and the design's own measurements push callers towards large batches — batching is worth roughly an order of magnitude in wall-clock time (§9.2). So what happens when one row in a large batch is rejected is a question every caller eventually asks, and the answer has to be all or none.

It was neither. **2 295 of 5 000 rows persisted from a batch whose caller was told it had failed.**

The cause is a driver detail rather than anything in the SQL. PostgreSQL wraps the statements between two syncs in an implicit transaction, and pgjdbc sends a small batch as a single sync — so a ten-row batch is atomic, by accident. Large batches are split into chunks and each chunk is synced, so the implicit transaction covers a chunk rather than the batch, and a rejection part way through leaves everything before it committed.

**Small batches being atomic is exactly what makes this easy to miss.** The first version of this test used ten rows, passed, and would have been recorded as evidence that enqueue is atomic. It only became visible at a size a real caller would use.

Partial enqueue is worst precisely where this design is aimed. A caller writing an outbox sees an exception, reasonably assumes nothing was written, and retries — and half the batch is delivered twice while the caller believes it was delivered once.

Fixed by making every enqueue path run its batch in an explicit transaction, so the guarantee comes from the engine rather than from driver chunking behaviour. A caller that has already begun a transaction — the outbox case — is left alone: their commit decides, which is the entire point of enqueueing transactionally.

### 9.13 A permanent write failure, and a third test that injected nothing

Every other database failure covered here heals on its own — a connection returns, a pool frees up, a server unpauses. The reconnect loop added in §9.10 retries indefinitely, which is obviously right for a transient fault and needs justifying for a permanent one: disk full, a read-only tablespace, a broken constraint. The connection succeeds every time and the statement fails every time.

Blocking `DELETE` is the sharpest version, because acknowledgement is what breaks: the engine can read and deliver but cannot record that it has. Measured behaviour, with the failure verified to be real before anything was asserted about it:

- **Nothing is lost.** The rows stay, and the queue drains completely once the fault is repaired.
- **Zero redeliveries to the live owner**, which is the right answer rather than a suspicious one. The owner remembers what it has handed to a handler, so a sweep finding those rows still present does not deliver them again. Re-running a handler that already succeeded, purely because the database cannot record that it did, would turn a write outage into duplicate side effects — worse than the outage itself. A *new* owner has no such memory and would redeliver, which is where at-least-once actually comes from.
- **The retry is paced, not spinning:** 84 reconnect cycles across four shards over four seconds, which is the 200 ms pacing doing its job.

**The first version of this test injected no failure at all.** It used `REVOKE DELETE` — and the test user *owns* the tables, so it keeps every privilege regardless of grants. Acknowledgement carried on working and the test passed. The tell was zero redeliveries, which at the time was inconsistent with the behaviour being claimed. It now blocks deletes with a trigger and **asserts that the injection itself works** before asserting anything about behaviour under it.

That is three failure-injection tests in this session that initially injected nothing: a malformed JDBC parameter, a false premise about connection pooling, and a privilege that does not apply to owners. The lesson is narrow and worth stating plainly — **a failure test must prove the failure happened**, and the cheapest way is an assertion that the injection itself throws.

### 9.12 The wake-up listener died silently on any connection blip

The listener caught its exception, logged a warning, and exited the thread. No reconnect. So after any connection loss at all — the same routine triggers as §9.10 — Tier 1 was gone for the life of the process.

**Its failure is silent by construction, which is what makes it serious.** Owners fall back to the backstop poll, so messages keep arriving and only the latency changes: from the 0.44 ms measured in §9.4 to as much as the 500 ms backstop. Nothing fails, nothing alerts, and the tier the design's headline latency figure rests on has quietly stopped existing. The connection-loss test in §9.10 passes either way, because delivery still works — which is precisely why this needed its own assertion rather than being assumed covered.

Two details matter in the fix. The loop reconnects rather than exits; and it re-executes `LISTEN` on the new connection, because the subscription is per-connection and a retry that merely resumed reading would be attached to nothing.

The test asserts what actually distinguishes a live listener from a dead one: **notifications continuing to arrive**, not messages continuing to be delivered. Delivery proves nothing here — the backstop delivers too. It also asserts `LISTEN` was re-established, so a future change that reconnects without re-subscribing fails rather than silently degrading. Against the unfixed listener the count is frozen at exactly its pre-failure value.

### 9.11 Time is the database's, and two tests that proved nothing

**Every durable moment in time is `now()`, evaluated by the server.** Message visibility, retry backoff, lease expiry and instance liveness are all written and compared server-side; no client timestamp is ever sent. That is what makes clock skew between nodes a non-issue rather than a bounded risk: two nodes disagreeing by seconds would otherwise disagree about who owns a shard and which messages are due, and the resulting failure would be intermittent, environment-dependent and near-impossible to reproduce deliberately. Client clocks are used only for local scheduling — the retry wheel, hole chasing, wake-up timeouts — none of which crosses a node boundary or is persisted.

`ServerSideTimeTest` guards it structurally, because the property cannot be observed from one machine: with a single clock there is no skew to detect. What can be checked is that no client timestamp is ever written, which is the thing that would make skew matter.

**Two database-failure tests were written, passed, and turned out to prove nothing.** Both were run against deliberately broken code and still passed — the discipline that caught it, and the reason to apply it to every regression test rather than only the interesting ones.

- **The outage test never produced a single error.** Testcontainers' JDBC URL already carries a query string, so appending `?socketTimeout=5` gave `…?loggerLevel=OFF?socketTimeout=5`; pgjdbc parsed none of it and silently ignored the timeout. Queries blocked through the pause and resumed on unpause. One character — `&` — separated a test that exercised nothing from one that kills four owner threads against the unfixed engine.
- **The pool-exhaustion test rested on a false premise.** An owner takes one connection when it starts and holds it, so no amount of pool pressure reaches its reads. What starvation actually starves is everything acquiring per use: enqueue, the listener, and lease renewal — and a heartbeat that cannot renew lets leases lapse. Rewritten around that, it now tests recovery through a different door than the connection-loss test rather than duplicating it badly.

Both now fail against the unfixed engine and pass against the fixed one, with the reconnect path confirmed to fire four times per run.

### 9.10 A lost connection killed the owner and stalled the shard forever

Found by inspection while choosing what to build next, then reproduced before being fixed.

An owner's run loop let a `SQLException` escape, which killed the owner thread — while `leaseHeld` stayed true and the heartbeat, holding a **different** pooled connection, went on renewing the lease quite happily. The shard was then held forever and served by nobody, and because the lease stayed valid nothing else could take it either.

The trigger is not exotic. A database failover, a pooler restart, a connection reaper, an idle timeout on a proxy — all routine, none of which kills the application. `pg_terminate_backend` reproduces it exactly: connections go, process stays, and it still believes it owns its shards. Against the unfixed code every owner thread died on the first terminated connection.

**A stall is worse than a crash**, because a crash gets noticed and restarted while a queue that quietly stops delivering does not.

Fixed by making the run loop reconnect: the connection is reopened, the owner resumes, and the attempt-bump on takeover runs again harmlessly. The retrying is bounded by the lease rather than by a retry count — if the database is genuinely gone the heartbeat cannot renew either, the owner is marked lost, and the loop exits. So reconnecting can never hold a shard the cluster has already moved on from, which is the property that makes an unbounded retry safe here.

### 9.9 The ordered lane was not protected by the lease mechanism at all

Found while deciding what to build next, not by a failing test. The ordered lane:

- **never renewed its leases** — taken once at startup, then left to expire;
- **never checked them** — the owner loop had no lease condition, and the `fence` it was constructed with was stored and never used;
- **acknowledged without asserting them** — no fence clause on the delete.

So once a lease lifetime elapsed, a second node could acquire the same ordered shard while the first was still delivering. Two owners on one shard is precisely an ordering violation, and a superseded owner could delete work the new owner still owed — which for an ordered key loses messages *and* reorders what remains.

**Every existing test passed, because every existing test finished inside the lease lifetime.** Nothing about a short run can distinguish "the lease is being renewed" from "the lease has not expired yet". The lane whose entire purpose is ordering was the one lane the lease mechanism did not cover, and the multi-process ordering result in §9 was obtained inside that same window.

Fixed by extracting a `LeasedOwner` contract both lanes implement, so the heartbeat renews for either, an owner stops when superseded, and the ordered acknowledgement carries its fence.

Three tests were added, and **each was verified against the unfixed code**, because a regression test that has never been seen to fail is a guess about what it covers:

| Test | Against the unfixed code |
|---|---|
| An ordered owner idles past several lease lifetimes and still works | — |
| A superseded owner's acknowledgement deletes nothing | **Fails: deletes 2 rows where it should delete 0** |
| Cross-process ordering over ~19 lease lifetimes, with a third node arriving mid-run and asking for every shard | **Fails: 600 of 800 messages delivered** |

The multi-process test also replaces an earlier version that enqueued everything in one burst and finished inside a single lease lifetime — which is precisely why it passed while the lane had no heartbeat. It now feeds messages over roughly fifteen seconds and starts a third node halfway through. With renewal working, that node gets nothing and both original owners keep their shards; the cross-node ordering result is therefore now established across renewals rather than inside one lease.

**The failure mode without renewal was worse than predicted.** The expectation was reordering from two owners on one shard. What actually happens is that the original owners are fenced out and stop, the late-arriving node picks up only the shards that happened to be free at the moment it started, and the rest is **stranded — 200 messages never delivered at all**. A queue that silently stops delivering a quarter of its traffic is a more serious failure than one that delivers it out of order, and it would have been invisible to every test that ran for less than a second.

### 9.5 Fencing and the heartbeat

§4.7 is now implemented rather than described. Every acknowledgement carries the fence it was issued under and asserts, in the same statement, that the lease still matches and has not expired — one extra index lookup on the lease table's primary key, per batch rather than per message. An owner whose delete affects zero rows has been superseded, and stops immediately instead of continuing to dispatch alongside its successor.

The heartbeat renews at a third of the lease lifetime, so a single missed renewal costs nothing. A renewal that is refused, or granted under a *new* fence — meaning the shard was taken and handed back — stops the owner at once rather than letting it discover the problem at acknowledgement time, by which point it may already have dispatched work its successor is also dispatching.

The dangerous write is the acknowledgement, because it deletes: a superseded owner acknowledging its in-flight work would remove exactly the messages the new owner is about to deliver. `NextGenFencingIT` tests that directly — a stale fence deletes nothing, the messages remain for the new owner, and the current owner's acknowledgement still succeeds, so the clause is not simply blocking everything.

**It costs nothing measurable.** After fencing, the comparison re-ran at 534 WAL bytes per message against the baseline's 1 909 (−72.0%, unchanged), dead tuples still exactly 1.00, and Tier 2 latency at p50 0.44 ms / p99 0.97 ms — if anything slightly better than before, which is measurement noise rather than an improvement.

### 9.1 First measured result — both cost gates met

20 000 messages, 200-byte payloads, three interleaved repetitions per arm, fixed message count so both arms divide by the same denominator. Database pinned to CPUs 4–7, generator to 0–3.

| Arm | WAL bytes/msg | IQR | Inserts | **Updates** | Deletes | Dead tuples/msg |
|---|---|---|---|---|---|---|
| Baseline (existing engine) | 1 905 | 1.1% | 20 000 | **20 000** | 19 608 | 1.98 |
| Shard-owned | **524** | 0.1% | 20 000 | **0** | 20 000 | **1.00** |

**The update column is the whole design in one number.** The baseline writes one row update per message — that is the claim write, `is_being_delivered = true`. The shard-owned engine writes **zero**, because ownership makes the claim unnecessary. Not reduced: absent. Dead tuples per message falls from 1.98 to exactly 1.00, the ack delete and nothing else, which is the floor §2 predicted.

Against the re-specified gates:

- **Phase 1's gate — ≥ 25% reduction in WAL bytes per message — is met at −72.5%**, with the two distributions cleanly separated rather than overlapping.
- **Phase 3's gate — dead tuples per message from 2.00 to 1.00 — is met exactly.**

A cross-check that the WAL figure is real rather than an artefact: the sequence-gap scenario measured a bare insert of a comparable row at 408 WAL bytes. 524 minus that leaves roughly 116 bytes for the delete, which is the right order for a WAL delete record. The numbers agree with each other from two independent directions.

**What this is not.** The baseline serializes every payload through the flavour-neutral JSON serializer, routes every operation through an interceptor chain, and opens a `UnitOfWork` per operation. The shard-owned engine did none of that — it moved opaque bytes in batches of a hundred. So −72.5% mixes a design advantage with a batching advantage, and only one of those is inherent. §9.2 separates them.

### 9.2 Decomposition — how much of the win survives the obligations

Each obligation added back one at a time, same engine, same 20 000 messages, three interleaved repetitions:

| Obligation added | WAL bytes/msg | IQR | vs previous | vs baseline | Commits | Elapsed |
|---|---|---|---|---|---|---|
| bytes, batched (the headline) | 524 | 0.1% | — | −72% | 61 288 | 444 ms |
| + JSON payload | 540 | 1.9% | +3% | −72% | 61 625 | 359 ms |
| + per-message enqueue | 613 | 0.5% | +13% | −68% | 377 154 | 13 044 ms |
| + per-message ack | 615 | 1.1% | +0% | −68% | 383 894 | 12 547 ms |

**The honest number is −68%, not −72.5%.** Restoring every obligation costs 17% more WAL, and the advantage barely moves.

That decomposition is the answer to the question that matters — how much of this is design and how much is the harness being generous:

- **JSON serialization costs almost nothing in WAL.** A 200-byte payload becomes 214 bytes on the wire, and WAL rises 3%. The `bytea`-over-`jsonb` argument in §4.1 is real but small; it is not where the win lives.
- **Batching is worth about 4 percentage points of the 72.** Removing it raised commits from 61 288 to 377 154 and made the run 29× slower — but WAL rose only 13%. Batching buys throughput, not write volume.
- **Per-message acknowledgement costs no WAL at all.** Deleting 20 000 rows one at a time or in ranges produces the same tuple deletions; only commit count and round trips differ. §4.3's range delete is a throughput and vacuum optimisation, not a WAL one — and given it is also the source of the message-loss bug, that is worth knowing.
- **The remaining −68% is the design**: no claim write, a narrower row, and fewer indexes to maintain. `n_tup_upd` is **zero in every arm**, so the claim write does not creep back in under any of these conditions.

**One cost the design had not yet addressed showed up here.** Even the fastest arm recorded 61 288 commits for 20 000 messages — roughly three transactions per message, almost all of them empty polling reads from eight shard owners parking at 200 µs. That is precisely what §4.5's tiered wakeup exists to remove, and it identified the next thing to build: the read path's transaction count, not its write volume.

### 9.3 Tier 1 wake-up — measured

Implemented as §4.5 specifies: application-issued `pg_notify` inside the enqueue transaction (so a hint is delivered only if the enqueue commits, and discarded if it does not), one hint per batch per shard rather than one per message, a dedicated listener connection, and owners parking on a per-shard signal with a backstop timeout instead of a 200 µs spin.

| Configuration | Commits per message, before | after | WAL bytes/msg, before → after |
|---|---|---|---|
| bytes, batched | 3.06 | **0.05** | 524 → 527 |
| + JSON payload | 3.08 | **0.06** | 540 → 543 |
| + per-message enqueue | 18.86 | **3.10** | 613 → 622 |
| + per-message ack | 19.19 | **3.09** | 615 → 621 |

**Idle polling is gone.** In the batched configuration the engine now issues 907 transactions to move 20 000 messages — essentially only the ones that do real work. The per-message arms settle at about three transactions per message, which is what per-message operation inherently costs: one enqueue, one read, one acknowledgement.

**WAL is unchanged**, and that is the point rather than a disappointment. The wake-up is a transaction-count and latency mechanism, not a write-volume one, and the measurement says exactly that instead of leaving it to be assumed. The honest cost advantage stays at **−67%**.

A secondary effect worth recording: in the semantics suite, cursor reads for the same 900-message workload fell from 16 779 to 322.

### 9.4 Tier 2 local hand-off — measured, and only half of it works

Implemented as §4.5 specifies: the insert stamps the row with the owning consumer's fence, that owner's cursor read excludes its own fence, and the message is handed to the dispatcher in memory strictly after the insert commits. The stamp is self-expiring — a new owner takes the shard under a new fence, so rows pre-claimed by a dead owner become visible again.

Enqueue-to-handler latency, 2 000 messages offered at 500/s (far below capacity, so Little's Law is not setting the answer), after warming both paths:

| Configuration | p50 | p90 | p99 | max |
|---|---|---|---|---|
| **Tier 2 on — response** (from the intended schedule slot) | **0.51 ms** | 0.71 ms | **1.05 ms** | 5.06 ms |
| Tier 2 on — service (from when the producer actually sent) | 0.32 ms | 0.42 ms | 0.74 ms | 1.80 ms |
| Tier 2 off — response | 1.95 ms | 2.20 ms | 4.14 ms | 6.30 ms |
| Existing engine, 20 ms poll (Phase 0) | 20.7 ms | — | 27.1 ms | — |

**The design's latency target is met and beaten.** §5 predicted p50 under 1 ms and p99 around 3 ms for Tier 2; measured, it is **0.51 ms and 1.05 ms** — 40× the existing engine's median and 26× its p99, and about 4× better than reading the message back.

#### A retraction, and the discipline that produced it

An earlier version of this section reported Tier 2 as a **net p99 regression**: response p99 of 266 ms against 3.28 ms with the tier off. That conclusion was wrong, and it was wrong because this test violated two rules Phase 0 had already established.

The tail was **warmup**. Tier 2 ran first, paying JIT, class loading and connection-pool growth in a single ~287 ms stall — which coordinated-omission accounting then correctly propagated into the response time of every message queued behind it. Phase 0's own conclusions say to warm up and to interleave arms; this test did neither, and no amount of care inside the engine would have fixed a defect that lived in the measurement.

Two hypotheses were eliminated on the way, and the elimination was worth more than the conclusions:

- **"A lost wake-up waiting for the head sweep"** — 266 ms is almost exactly half the 500 ms sweep interval, which is persuasive and false. `sweepRecoveries` and `backstopPolls` both measured zero: no message ever waited for either. Chasing it did surface two genuine defects in the wake-up primitive, each failing differently — draining permits after acquiring discards signals that represent real queued messages, while not draining leaks them, because capping the release with a check-then-act on `availablePermits` is itself a race. The second one spun an owner hot enough to starve its siblings and hang the suite. Both are gone; the signal is now a boolean flag under a monitor, which can neither be lost nor accumulate.
- **"The enqueue statement"** — the pre-claimed insert built its SQL dynamically per batch size, so pgjdbc's prepared-statement cache never hit and every enqueue paid a fresh parse and plan. Fixing it to a fixed statement batched through generated keys improved service-time p99 from 2.01 ms to 0.74 ms. A real improvement, and still not the cause of the tail.

The general lesson is the one this work keeps relearning: **a number that indicts a mechanism should be checked against the harness before it is believed.** Splitting response from service time is what made that possible here — response time alone said the mechanism was broken, service time alone said it was excellent, and only the pair located the truth in neither.

**And it broke a test, in a way worth remembering.****And it broke a test, in a way worth remembering.** The out-of-order-commit test asserted that holes were observed. Tier 1 changed *when* an owner reads — just after a commit, rather than every 200 µs — so it now far more often sees a consistent picture, and the assertion became flaky. That test now asserts only the invariant it exists for, that nothing is lost, and the hole mechanism gets a separate test that *constructs* the hazard: one transaction inserts and holds, another inserts into the same shard and commits, so the owner cannot avoid stepping over an uncommitted sequence value. This is the second time in this work that a race-dependent assertion passed by luck; constructing the hazard is the only version that stays honest.

**Two deliberate deviations from the design as written**, both recorded rather than quietly absorbed:

- **The ordered lane acknowledges by sequence value, not by range.** Keys progress independently, so what has been handled is not a contiguous run of `seq`, and inventing a second range-delete floor for a lane where the range is rarely contiguous would repeat §4.3's bug for no gain. Still one round trip, batched by `ANY`.
- **The dead letter lane stores `bytea`, not `jsonb`** as §4.1 specifies. Payloads are opaque bytes end to end in this engine, so `jsonb` would mean inventing a decode nothing else has. Revisit when there is an admin surface that queries into them.
- **Per-key ordering is not strict against a late-committing `key_order`.** A key advances through the values that are present rather than waiting for a producer-assigned gap that may never be filled — waiting would stall the key on a producer's bookkeeping error. The exposure is counted (`orderViolations`) instead of being claimed away; it measured zero across every run so far.

**Two defects the implementation found that the design and the measurements had both missed.** Both are the kind that a prototype measured for speed would have reported as a success:

1. **An interrupted handler was acknowledged as a success.** A handler that catches `InterruptedException` and returns — which is what well-behaved code does on shutdown — was indistinguishable from one that finished, so its message was deleted without being processed. Silent message loss on every graceful shutdown.
2. **The range-delete ack contradicted the hole chasing** (§4.3). Reproducible loss of 4 messages in 900.

3. **Cross-key parallelism did not exist.** `dispatchReadyKeys` called handlers inline on the owner thread, so exactly one key was ever in flight and a single slow key stalled its entire shard — while the code read as though §4.7's per-key concurrency were implemented. The `keysInFlight` set that supposedly enforced per-key exclusivity never held more than one entry, so it was enforcing nothing.

4. **Table statistics had not flushed when they were read.** PostgreSQL accumulates `n_tup_ins`/`upd`/`del` per backend and flushes to shared memory at most about once a second. The shard-owned arm completes 20 000 messages in under 400 ms, so the first cost comparison read counters that were simply not there yet — reporting 16 800, 0 and 0 inserts across three repetitions of identical work, and **0.10 dead tuples per message**. The tell was that 0.10 is ten times *better* than the design's own floor of one delete per message: not possible, therefore not a measurement. Fixed by settling before the read, and by asserting the measured insert count against the work the harness knows it did, so a lagging collector now fails the run instead of flattering an arm. WAL bytes were unaffected, being read from `pg_current_wal_lsn` rather than the collector — which is why they were stable to 0.1% across the same broken runs.

None of the four is visible in a throughput number, and two of them — the range-delete contradiction and the unflushed statistics — would have made the new engine look *better* than it is. All were found by checking that a number was possible before believing it.

**What the third one cost.** Buying the parallelism claim back required moving handlers onto a pool, which means the owner's state is no longer touched by one thread — so the hole set, in-flight set, ready-by-key map and pending acks now need a lock. §4.7 presents cross-key parallelism as free; it is not. It costs exactly the single-threaded-owner simplicity that made in-memory hole tracking attractive in the first place, and that trade should be stated in the design rather than discovered in the implementation.

## 10. Implementation plan

Every phase is independently mergeable, independently measurable, and revertible. Every phase has a gate. **A phase that misses its gate is reverted or redesigned, not merged with a promise to fix it later.**

The new engine lives behind the existing `DurableQueues` interface as a separate implementation, selected by configuration. The current implementation is untouched, so A/B benchmarking is a config switch and no consumer is broken.

### Phase 0 — Benchmark harness and baseline

**The most important phase.** Nothing after this is trustworthy without it.

Build the harness in `examples/essentials-performance-lab` as `LabScenario` implementations, alongside the existing CDC and slot scenarios.

Deliverables:
- Latency measurement free of coordinated omission — record *intended* send time, not actual, and use HdrHistogram for percentiles.
- Explicit warmup, then a measured steady-state window.
- Postgres-side metrics captured per run: `pg_stat_database` transactions, WAL bytes (`pg_stat_wal`), `n_tup_ins/upd/del`, `n_dead_tup`, autovacuum counts, index sizes.
- JVM metrics: allocation rate, GC pause distribution, connection-pool wait time.
- A/B runner that interleaves baseline and candidate (ABAB) in the same container to cancel environmental drift, runs ≥ 3 repetitions, and reports median with interquartile range — not a single number.
- Machine-readable output (JSON) so results can be diffed across commits.

Scenarios:
1. Unordered, low rate, latency-focused — measures the poll-interval floor.
2. Unordered, saturating — measures peak throughput.
3. Ordered, varying key cardinality (10 / 1k / 100k keys).
4. Mixed with an injected failure rate exercising retries and DLQ.
5. Many queues, few busy — measures idle cost.
6. Sustained soak (≥ 30 min) — measures bloat and p99 drift over time.

**Gate:** the harness reports the same numbers within its stated confidence interval across three consecutive identical runs. If it cannot do that, it is not measuring anything and no later gate means anything either.

#### Result — harness built, gate met, core assumption survives its first test

Delivered in `examples/essentials-performance-lab`: `LatencyRecorder` (coordinated-omission-free, HdrHistogram), `PgSnapshot` (WAL bytes via `pg_lsn` arithmetic, plus generically-read `pg_stat_*` counters so a PostgreSQL upgrade cannot break the harness), `JvmSnapshot`, `AbRunner` (interleaved arms, median with interquartile range), `RunResult` (JSON), and the first scenario, `SequenceGapScenario`. `SequenceGapScenarioSmokeIT` keeps it honest in the normal build.

**Reproducibility.** Three consecutive sessions, two arms, three repetitions each:

| Metric | Session medians | Cross-session spread | Within-session IQR |
|---|---|---|---|
| Throughput, autocommit arm | 46 298 / 43 542 / 42 998 per second | 7.5% | 4.2–8.5% |
| Response time p99, autocommit arm | 566 / 557 / 605 µs | 8.4% | ~3.5% |

Cross-session spread matches within-session spread for throughput, so the harness adds no drift of its own. For p99 the cross-session figure is the larger of the two, which sets the real resolution.

**The number that matters for every later phase: this environment resolves differences of roughly 10%, no better.** Anything smaller is noise. That is a measured justification for Phase 1's ≥ 20% gate, which until now was only a guess.

**The core assumption holds.** Across all 18 measured runs: zero messages lost, zero permanent holes. Every hole the cursor stepped over was chased and resolved.

| Arm | Hole rate | Hole resolution p99 |
|---|---|---|
| Autocommit enqueue | 2.4–5.1 per 1000 messages (~0.3%) | 4.8–5.2 ms |
| Enqueue holding a 5 ms transaction | 8.8–22.2 per 1000 messages (~1.5%) | 5.4–5.7 ms |

**Hole resolution latency is a tunable, not a property of the database.** Resolution p99 tracked the configured 5 ms chase delay almost exactly in both arms, which means holes were resolving as fast as the reader bothered to look. The cost of a hole is therefore set by `chaseDelay`, and the design can trade it against chase query volume rather than being stuck with whatever PostgreSQL does.

**Baseline write amplification:** 408 WAL bytes per 200-byte insert, roughly 2×. This is the number Phase 1's `bytea` and interning changes have to beat.

**Two caveats, neither cosmetic.** The low-rate arm gathered only ~1400 samples per repetition against ~90 000 for the autocommit arm, and its p99 varied 33% across sessions as a result — real runs must scale duration to sample count rather than wall-clock time. And this scenario never acknowledges or deletes, so it says nothing yet about dead tuples or vacuum behaviour; that is the soak scenario's job.

#### Result — baseline of the current implementation

`DurableQueueBenchmarkScenario` drives the existing `PostgresqlDurableQueues` through the same harness. The plan's remaining workload profiles are configuration of this one scenario rather than separate classes, since that is all that distinguishes them: rate for latency, unthrottled for throughput, `workload=ORDERED` plus key cardinality, `failure-percent` for retries and dead-lettering, many queues with few busy for idle cost, and a long duration for the soak.

**Latency, centralized fetcher, 20 ms polling, offered rate below capacity:**

| | Measured | Design doc's estimate |
|---|---|---|
| p50 | **20.7 ms** (IQR 1.2 ms) | ~10 ms |
| p99 | **27.1 ms** (IQR 1.0 ms) | ~30–50 ms |

The p50 estimate was low by a factor of two and the p99 estimate was high. Both are now measured, and the spread is tight enough to compare against.

**Throughput is governed by poll cadence, not by PostgreSQL.** Four configurations, two topologies:

| Fetcher | Parallel consumers | Measured | `consumers × poll rate` |
|---|---|---|---|
| Centralized (20 ms poll) | 3 | 150/s | 3 × 50 |
| Centralized (20 ms poll) | 20 | 993/s | 20 × 50 |
| Traditional (~100 ms poll) | 3 | 30/s | 3 × 10 |
| Traditional (~100 ms poll) | 20 | 200/s | 20 × 10 |

Every point lands on `parallelConsumers × pollsPerSecond`, with an interquartile range under 1%. The database was never the limit in any of these runs. This is the strongest evidence so far for the first item on §2's cost menu — and it means quoting a single "the current queue does N messages a second" figure would be meaningless without also quoting its consumer count and poll interval.

**Per-message cost, now measured rather than asserted:**

| Metric | Measured | Note |
|---|---|---|
| Dead tuples created per message | **1.8 – 2.4** | Confirms §2's "two dead tuples per message" — a claim update plus an ack delete |
| WAL bytes per message | **~1 700 – 2 000** | For a 200-byte payload: roughly 9× payload |
| WAL bytes for a bare insert | 408 | From the sequence-gap scenario; the queue lifecycle costs 4–5× a raw insert |

#### Three harness defects the first run exposed

Recorded because each would have produced confident, wrong numbers, and because the same traps apply to every later phase.

1. **A gauge was being differenced.** `n_dead_tup` is driven back down by autovacuum, so subtracting two readings produced *negative* dead tuples per message. Tuple churn is now derived from `n_tup_upd + n_tup_del`, which are counters; `n_dead_tup` is reported as an absolute reading via `PgSnapshot.gauge`.
2. **The per-message denominator was wrong under backlog.** WAL covers enqueues and deliveries, but was divided by messages *handled*. When producers outran consumers this inflated the figure roughly fivefold — the first traditional-arm run reported 45 000 WAL bytes per message.
3. **Unthrottled producers turned a latency measurement into a queue-depth measurement.** The first run reported a p50 of 16 seconds. Bounding in-flight work fixed the runaway backlog but not the metric: at capacity, Little's Law fixes wait at `standingDepth / throughput` regardless of implementation. The scenario now reports `latencyMeaningful`, true only when the offered rate is below capacity and backpressure never engaged, and warns otherwise. **Latency must be measured at a rate the consumers can keep up with; anything else measures the buffer.**

#### Result — workload profiles, and three gates that turn out not to discriminate

`DurableQueueBaselineProfilesIT` captures the profiles the later phases are gated on. It is measurement-only, so per the project's testing convention it is opt-in behind `-Dbenchmark.run=true`. Centralized fetcher, 20 consumers, 20 ms polling, three repetitions each:

| Profile | Throughput | vs unordered | Dead tuples/msg | WAL bytes/msg |
|---|---|---|---|---|
| Unordered | 997/s | 100% | 1.91 | 1 971 |
| Ordered, 10 keys | 498/s | 50% | 2.09 | 2 050 |
| Ordered, 1 000 keys | 1 000/s | 100% | 1.87 | 2 257 |
| 10% injected failures | 900/s | 90% | 2.29 | 2 303 |
| 1 queue (control) | 100.0/s | — | 1.95 | 1 748 |
| 30 queues, 29 idle | 99.7/s | 99.7% of control | 1.99 | 2 193 |
| Soak, 60 s | 1 000/s | 100% | 1.99 | 2 023 |

**Every one of these numbers sits at or below the poll-cadence ceiling of `parallelConsumers × pollsPerSecond` = 1 000/s.** The database was never the bottleneck in any profile, so any cost difference smaller than the headroom below that ceiling is invisible. That single fact reframes three of the plan's gates:

- **Phase 4's gate — "ordered throughput within 30% of unordered" — is already met** by the current implementation at 1 000 keys, which measures 100%. Ordered at 10 keys measures 50%, but that is key parallelism (10 keys × 50 polls/s ≈ 500/s), not an ordering-query cost. The one place ordering *is* visibly more expensive is WAL: 2 257 against 1 971 bytes per message, about 15% more.
- **Phase 5's gate — "10% failures cost under 10% throughput" — is already met**, measuring 90%.
- **The idle-queue claim does not survive contact with the baseline.** Twenty-nine idle queues cost 0.3% against a matched control. Idle polling is already close to free at this scale, so §4.2's coalesced read is not the win it was presented as until the queue count is far higher — and that threshold needs measuring before the claim is repeated.

None of these gates were wrong to write; they were written against estimates. Measured against the real baseline they simply do not discriminate, and a gate that cannot fail is not a gate. **Before Phase 4 and Phase 5 begin, both must be re-specified at an operating point where PostgreSQL is the bottleneck** — more consumers, a shorter poll interval, or both — and the idle-queue claim either demonstrated at a realistic queue count or dropped.

#### Two more harness defects, and the numbers they changed

Both were caught by results that did not survive being questioned, which is the only reason to look at a benchmark's intermediate values rather than its summary.

4. **Throughput divided by a window that included the drain.** The failure profile spent 6 seconds producing and 45 seconds draining retried messages, and reported **145/s — an apparent 85% collapse**. Measured over the steady-state window alone, where producers are backpressure-bound to the consumers, it is **900/s**. The reported figure was wrong by a factor of six and would have condemned the failure path on the strength of an arithmetic artifact. Throughput is now measured over the produce window only; the drain is reported separately as `drainMillis`.
5. **The idle-queue profile was confounded by its own configuration.** It ran with two consumers where the others used twenty, so its "10% of unordered" measured nothing but its own consumer count. It now runs against a matched control that varies only the queue count — which is what turned an apparent finding into the real one above.

#### Result — the capacity sweep, and why the throughput gates cannot be rescued here

The obvious response to "every profile sits on the poll-cadence ceiling" is to lift the ceiling until PostgreSQL becomes the limit, then re-specify the gates there. That was attempted. It produced a clear answer and then a clearer obstacle.

**The sweep.** Poll interval is the right lever rather than consumer count — every consumer needs a connection to acknowledge on, so raising consumers hits `max_connections` long before anything interesting, while shortening the interval raises the ceiling for free. Twenty consumers, eight producers, backpressure engaged throughout (so the consumers, not the producers, bound every run):

| Poll interval | Ceiling | Measured | Of ceiling |
|---|---|---|---|
| 20 ms | 1 000/s | 1 000/s | 100% |
| 10 ms | 2 000/s | 1 995/s | 100% |
| 5 ms | 4 000/s | 3 994/s | 100% |
| 2 ms | 10 000/s | 9 881/s | 99% |
| 1 ms | 20 000/s | 9 203/s | **46%** |

The knee is at roughly **9 900 messages a second**, reached at a 2 ms poll. Polling faster than that makes throughput *worse* — 9 203 against 9 881 — which is itself an argument for the design's push tiers: past a point, the poll is pure overhead.

**The obstacle, and the wrong diagnosis.** Numbers taken at that knee did not reproduce: 239% interquartile range in a profile sequence, 47% in isolation, 108% with `-XX:ActiveProcessorCount` matched to the real quota. Repetitions swung between 1 300 and 9 900 messages a second.

The environment offered an obvious culprit. `dockerd` runs *inside* this devcontainer, so a Testcontainers PostgreSQL is a child of it and shares its cgroup: **a quota of eight CPUs, which both the JVM and the database size their pools against as if it were the fourteen `nproc` advertises.** The generator and the database then compete for the same cores. That reading was recorded here as "the saturated operating point is not measurable in this environment".

**That conclusion was wrong, and the evidence that overturned it was already in the data.** The values were not noisy, they were *bimodal* — approximately 1 600 or approximately 9 500, with almost nothing between. Noise does not do that; a query plan flipping between a sequential scan and an index scan does. Two further facts confirmed it: WAL bytes per message stayed within 0.6% across repetitions whose throughput differed sixfold, so the *work* was identical and only its cost differed; and partitioning the eight cores explicitly — database pinned to 4–7 via `LabPostgres`, generator to 0–3 via `taskset` — left the interquartile range at **202%**. CPU contention was not the cause.

The cause was the harness starting every repetition from a freshly truncated table. With no representative statistics, whether autoanalyze fired early or late in a six-second run decided which plan most of that run used. Running a real warmup and analysing **on the table as the warmup leaves it** — churned, drained, and the size the measured run will see — took the interquartile range from **245% to 4.5%**.

An earlier attempt made this worse before it made it better: the first version of the reset ran `TRUNCATE` followed immediately by `ANALYZE`, which records that the table is empty and hands the planner an empty-table estimate for a run about to insert tens of thousands of rows. A fix has to be verified, not assumed.

**This is also a finding about the implementation, not only about the harness.** The existing fetch query's cost depends on autoanalyze having caught up, and the difference between its two plans is roughly sixfold at this operating point. A freshly deployed or freshly purged queue table can therefore sit in a materially slower plan until statistics catch up — worth confirming with `EXPLAIN` and, if it holds, worth an explicit `ANALYZE` after table creation and after `purgeQueue`.

#### The constructive fix: gate on per-message cost, not throughput

Across 45 unordered runs spanning every operating point and every session:

| Metric | Median | Range | Spread |
|---|---|---|---|
| Throughput | 1 150/s | 100 – 9 999 | **861%** |
| WAL bytes per message | 1 817 | 1 747 – 2 337 | **33%** |
| Dead tuples per message | 2.00 | 1.81 – 2.34 | **27%** |

Throughput varied by nearly an order of magnitude while the per-message cost figures barely moved — because throughput is a property of the machine and the configuration, and per-message cost is a property of the design. That holds regardless of poll interval, consumer count or CPU throttling.

**This is a better way to write the gates than the original, independent of the environment.** A design that halves the WAL a message costs has improved; whether that shows up as throughput depends on hardware nobody controls. The affected gates are re-specified below against cost.

#### Result — the corrected saturated baseline, and a retracted conclusion

With stable statistics, the saturated operating point *is* measurable, and the numbers say something different from the poll-bound ones:

| Profile | Throughput | IQR | vs unordered | WAL bytes/msg | Dead tuples/msg |
|---|---|---|---|---|---|
| Unordered | 1 534/s | 8.7% | 100% | 1 762 | 2.19 |
| Ordered, 1 000 keys | 477/s | **0.2%** | **31%** | 2 062 | 3.23 |
| 1 queue (control) | 1 498/s | 4.6% | 98% | 1 778 | 1.94 |
| Ordered, 10 keys | 14.7/s | 127% | — | 1 964 | 2.00 |
| 10% failures | 7 485/s | 44% | 488% | 2 230 | 2.06 |
| 30 queues, 29 idle | 199/s | 156% | — | 1 774 | 1.99 |

**Ordered delivery does carry a real throughput cost.** At 1 000 keys it measured 31% of unordered in one run and 36% in another — consistent across runs whose absolute numbers were not, and consistent with the +17% WAL and +48% dead-tuple premiums measured alongside. Phase 4's gate therefore looks as though it discriminates, and the current implementation looks as though it fails it. Both statements need confirming on hardware that can hold a throughput number still; the direction is trustworthy, the boundary is not.

#### Partial un-retraction: the statistics fix was real, but it was not the whole cause

The plan-flip finding stands: the values were bimodal, `ANALYZE` placement moved the interquartile range from 245% to 4.5%, and `TRUNCATE`-then-`ANALYZE` demonstrably poisons the plan. All of that is reproducible.

What does not stand is the stronger claim that this *explained* the instability. Five distinct interventions were tried — CPU pinning of both sides, JVM processor count matched to the quota, single-profile isolation, `ANALYZE` placement, and a profile-independent neutral warmup. Each improved some profiles. None made the saturated operating point reliable:

- `sat-idle-control-1` and `sat-unordered` are byte-for-byte identical configurations and measured **766/s against 1 339/s** in the same suite run.
- `sat-ordered-keys-1000` moved between 0.2% and 87% interquartile range across attempts with no configuration change.
- Scaling the in-flight bound down for the ten-key ordered profile — correct in itself, since a 5 000-deep backlog that only ten keys can drain measures backlog pathology rather than ordered delivery — introduced a *new* confound, because a smaller bound means a smaller table and a cheaper query. It then reported ordered running at 180% of unordered.

So the original conclusion was closer to right than its retraction: **the saturated operating point is not reliably measurable in this environment.** Retracting it after one successful intervention was premature — a fix that improves a symptom is not the same as a fix that explains it.

#### What this means for the gates

Across 45 unordered runs spanning every operating point and every one of those attempts:

| Metric | Median | Spread | Gate-able here? |
|---|---|---|---|
| Throughput | 1 000/s | **990%** | No |
| WAL bytes per message | 1 805 | **33%** | Yes |
| Dead tuples per message | 2.02 | 63% | Only at the poll-bound point, where it is 27% |

**WAL bytes per message is the one metric this lab can gate on**, and it is measured well clear of any ceiling. Dead tuples per message is noisier than an earlier version of this document claimed: 27% at the poll-bound operating point, 63% once the pathological saturated runs are included. Phase 3's "2.00 → 1.00" gate is a 50% change and therefore sits above the poll-bound noise but not above the saturated noise — so it must be measured at the poll-bound point, and the plan says so explicitly.

**Every gate is measured at the poll-bound operating point (20 ms poll), not at saturation.** Absolute throughput there is low and uninteresting, but it is reproducible, and the cost metrics are properties of the design rather than of the operating point — which is exactly why they were chosen.

#### On configurable lab container resources

Implemented as `LabPostgres`: CPU set, CPU quota, memory, `shared_buffers`, and optional tmpfs for `PGDATA`, all driven by `-Dlab.pg.*` and all recorded into the run's environment map alongside the cgroup budget and the generator's own CPU affinity — so a run that forgot its `taskset` wrapper is visible in the result instead of quietly incomparable.

Worth having, for reasons that turned out not to include the one it was built for:

- **Comparability.** A resource-constrained measurement whose constraints were not recorded cannot be compared with anything. This is the main value.
- **The storage-layout phases.** Whether the hot index fits in `shared_buffers` is exactly what Phase 1 changes, so it has to be a controlled variable rather than an image default.
- **Removing storage variance.** tmpfs for `PGDATA` takes disk speed out of the measurement — right when comparing two designs, wrong when quoting absolute latency, hence opt-in and recorded.
- **Determinism over headroom.** The quota cannot be raised from inside the container; that is a host-side `devcontainer.json` change. Partitioning the cores is available, and for A/B work a smaller but deterministic budget beats a larger contended one.

**It did not fix the instability**, and it is worth being explicit about that: pinning both sides left the interquartile range at 202%. The lever was query-planner statistics. Reaching for the resource knobs first was the intuitive move and it cost a measurement cycle to rule out.

#### Harness defect status

Six were found. Five are closed; one is improved but open, and one of the closed five is a mitigation rather than a structural fix. Stated precisely so nobody has to re-derive it:

| # | Defect | Status |
|---|---|---|
| 1 | `n_dead_tup`, a gauge, was being differenced | **Closed** — churn now from the `n_tup_upd`/`n_tup_del` counters, gauge read absolutely via `PgSnapshot.gauge` |
| 2 | WAL-per-message divided by messages handled, wrong under backlog | **Mitigated, not structural** — bounded in-flight keeps handled ≈ queued, `saturated` flags when it does not, and `walBytesPerMessageQueued` gives the other denominator. `walBytesPerOperation` still divides by handled |
| 3 | Unthrottled producers made latency a measure of queue depth | **Closed** — bounded in-flight, plus `latencyMeaningful` so a capacity-bound run cannot pass as a latency measurement |
| 4 | Throughput divided by a window including the drain | **Closed** — steady-state window only, drain reported separately as `drainMillis` |
| 5 | Idle-queue profile confounded by its own consumer count | **Closed** — matched control varying only queue count |
| 6 | Every repetition started from a truncated table, so autoanalyze timing chose the plan | **Open** — real warmup plus post-warmup `ANALYZE` fixed the poll-bound profiles and the bimodality, but the saturated profiles remain unreliable (see the un-retraction above) |

**Phase 0 status.** The poll-bound baseline is complete and reproducible, and it is what the gates are written against. The capacity knee is located (~9 900/s at a 2 ms poll) but numbers taken there are not reliable in this lab. The design's load-bearing assumption survives. Six harness defects and one implementation finding are documented. Two gates are re-specified against cost, one is restored pending confirmation, and all are now measured at the poll-bound point.

**What a saturated-throughput gate needs:** a machine this lab does not have — dedicated hardware, or at minimum a cgroup quota matching the advertised processor count with no co-tenants. Defect 6's remaining half is very likely to stay open until then, because five interventions inside the container did not close it.

### Phase 1 — Storage layout

The three-lane split, interning, `bytea` payloads, `queue_id` partitioning, and the `QueueStorage` seam beneath the engine (§4.1.1). No behaviour change: keep the existing claim-based fetch logic on the new storage so the layout change is measured in isolation.

**Gate (cost-based, per §9 Phase 0):** **≥ 25% reduction in WAL bytes per message** against the measured baseline of 1 817 bytes for a 200-byte payload, with no regression in dead tuples per message (baseline 2.00) and no latency regression at the poll-bound operating point (baseline p50 20.7 ms, p99 27.1 ms). Throughput is reported but does not gate — this environment cannot resolve it.

25% rather than 20% because the cost metrics carry a ~33% spread across configurations; the threshold must clear the noise, and a change this structural (`bytea` for `jsonb`, interned keys, narrower rows) should clear it comfortably or it is not doing what it claims.

Report the ordered and unordered lanes **separately** — the split is expected to help them by different amounts and for different reasons, and a blended number would hide both. Also confirm the by-id compatibility operations (§4.1) have not regressed beyond a single extra lookup.

### Phase 2 — Shard assignment and lease ownership

Shard column, lease table, acquisition and heartbeat protocol, fence tokens, rebalancing. Fetch still uses a claim write. Ordering still uses the old query. This phase is about proving the ownership protocol is correct under failure, not about speed.

The lease record is designed for both granularities from the start (§8.3) even though message leases are not wired up until Phase 6 — retrofitting a second lease scale onto a shard-only record later would be a schema migration.

**Gate:** correctness first — chaos tests (kill -9 an owner mid-batch, pause a JVM past its lease TTL, partition the network) show no message loss and no ordering violation that fencing does not catch. Performance gate: no regression.

### Phase 3 — Cursor read path, claim write removed

Cursor scan, hole detection and chasing, head sweep, coalesced multi-shard `LATERAL` read, batched range-delete acks. This is where the big win lands.

**Gate (cost-based, measured at the poll-bound operating point):** **dead tuples per message drops from the measured 2.00 to 1.00** — the single clearest test that the claim write is gone. It must be measured at the 20 ms poll, where that metric's spread is 27%; across saturated runs it is 63%, which a 50% change does not clear. Alongside it: WAL bytes per message down at least a further 20% on top of Phase 1's reduction, and the soak scenario showing flat p99 across 30 minutes rather than drift. Throughput is reported but does not gate.

### Phase 4 — Ordering in memory

Per-key FIFO enforced by the owner with cross-key parallelism inside a shard. Deletes the ordering query entirely.

**Gate (restored, plus cost criteria):** the original "ordered within 30% of unordered throughput" briefly looked as though it could not fail — Phase 0 first measured 100%, because both arms were pinned at the poll-cadence ceiling and, at saturation, because the query plan was flipping between repetitions. Measured properly, ordered at 1 000 keys runs at **31% of unordered**, so the gate sits almost exactly on the current implementation's number and discriminates cleanly.

Keep it, and add the cost criteria measured alongside: ordered within **5%** of unordered on WAL bytes per message (baseline premium +17%, 2 062 against 1 762) and on dead tuples per message (baseline premium +48%, 3.23 against 2.19), at 10, 1 000 and 100 000 keys. Strict FIFO verified per key under concurrent load and across a rebalance regardless.

### Phase 5 — Retries and DLQ off the hot path

In-memory timer wheel, HOT-update backoff, bulk `attempts` bump on takeover, DLQ table.

**Gate (re-specified, cost-based):** the original "10% failures cost under 10% throughput" was already met at 90% and could not fail. Gate instead on the cost premium a 10% failure rate carries, which Phase 0 measured as **+17% WAL bytes per message** (2 303 against 1 971) and **+20% dead tuples per message** (2.29 against 1.91): moving retries to an in-memory timer wheel should cut both premiums by at least half, since the database stops seeing most of the retry traffic at all. The correctness half stands unchanged: poison messages reach the DLQ within the policy bound even when the JVM is killed mid-handler on every attempt.

### Phase 6 — Leases as one primitive, and the pull API

Unify shard leases and message leases behind one lease record and one expiry rule, retiring the stuck-message sweep as a separate concept. Add session pull (§8.3) on the fast path, map `getNextMessageReadyForDelivery` onto a default-TTL message lease, and add lease extension for long-running handlers.

Lease scope (§8.4) ships as one parameter with four settings, defaulted from the message rather than configured. Two calibration runs belong here, neither of them adjudicating: sweep n to find where the write-amortization curve flattens (sets the default batch width), and grow session duration to chart blocking cost (tells callers when key scope beats shard scope).

**Gate:** the R1 defect is demonstrably fixed — a caller's business writes and its acknowledgement commit atomically, while a caller-triggered rollback still leaves attempt counting and dead-lettering intact. That is the test `FullyTransactional` fails today, so it is written against the *old* engine first to confirm it fails, then against the new one. Session pull costs zero writes per message; existing pull semantics unchanged except the documented attempt-counting shift.

### Phase 7 — Tier 1 wakeup: coalesced NOTIFY

Application-issued, per-shard, rate-limited notify on commit. Dedicated listener connection. Poll remains the backstop.

**Gate:** p50 enqueue-to-handler under 3ms at low rate, and — critically — **no throughput regression at saturation**. If coalescing is wrong, notify contention will show up here as a throughput cliff.

### Phase 8 — Tier 2 wakeup: local hand-off

Pre-claimed insert with `dispatched_by` fence, post-commit local dispatch, reader exclusion.

**Gate:** p50 under 1ms for the same-JVM case. Zero duplicate deliveries across the full chaos-test suite — this phase is the one most able to introduce a subtle double-delivery bug, so it earns the heaviest correctness scrutiny.

### Phase 9 — Tier 3: WAL streaming (optional, gated by measurement)

Only if Phases 3–8 leave the read path as the measured bottleneck. Requires extracting `WalReplicationTailer` and friends from `postgresql-event-store` into a shared module.

**Gate:** a measured improvement over Tier 1/2 large enough to justify the operational cost of a replication slot. If it does not clearly win, it does not ship.

---

## 11. Performance verification method

The plan is only as good as the measurements, so the method is part of the design.

**Statistical discipline.** Three repetitions minimum, interleaved A/B, median-of-medians with IQR reported. A single run is an anecdote. Any claimed improvement smaller than the observed run-to-run variance is not an improvement.

**Environment control.** Same container image, same Postgres configuration, same `synchronous_commit` and `fsync` settings across A and B — these dominate everything else and must never differ between arms. Pin CPU where the host allows it. Record the full environment in the result JSON.

**Coordinated omission.** Latency is measured against intended send time. A harness that measures actual-send-to-receive under saturation will report beautiful numbers that describe nothing.

**Gate on what the environment can actually measure.** Phase 0 established that throughput here varies 861% across operating points while WAL bytes per message varies 33% and dead tuples per message 27% — because throughput is a property of the machine and per-message cost is a property of the design. Every gate is therefore written against cost, with throughput reported but not gating. This is the better formulation regardless of environment: a design that halves what a message costs has improved, whether or not the hardware of the day turns that into throughput.

**Know the measurement ceiling before trusting a number.** This lab's cgroup grants eight CPUs while advertising fourteen, so any run that saturates CPU is throttled and swings by 50–240% between repetitions. Below saturation it reproduces to about 10%. A benchmark that does not know which side of that line it is on is not a benchmark.

**Calibration runs are labelled as such.** Some measurements exist to set a default, not to choose between designs — the lease-scope sweep in §8.4 is one. Mixing the two invites hanging a decision on a benchmark that was never going to discriminate. A run that is expected to come back "no significant difference" should say so *before* it is run, so that outcome reads as confirmation rather than failure.

**Both directions.** Every gate checks the metric it targets *and* the metrics it might have traded away. A latency phase that quietly costs 30% throughput has failed even if its own number improved.

**Regression tracking.** Results land as JSON in the repo so a later commit's numbers can be diffed against the phase that introduced them. Benchmarks stay opt-in (`-Dbenchmark.run=true`, as the build already does) so they never slow the normal build.

**What gets published per phase:** before/after percentile tables, throughput at saturation, WAL bytes per message, dead tuples per message, and the soak curve. Not a single headline number.

---

## 12. Migration and compatibility

- The engine is additive. `DurableQueues` does not change; the new implementation is selected by configuration.
- Both engines can run against different tables in the same database, which makes a per-queue cutover possible.
- Migration of in-flight messages from the old schema to the new is a drain-and-switch: stop enqueuing to the old queue, let it empty, start the new one. A bulk copy path is possible but should not be built until someone needs it.
- The API compatibility review in Risk 5 happens before Phase 1, not after.


### 9.16 Ordered-lane rebalancing, and why it cannot copy the unordered one

Ordered shards now move between instances, which §9.6 had left switched off pending a test. The mechanism is not the unordered lane's, because the unordered lane's would be wrong here.

The unordered lane sheds by dropping the lease. Whatever was in a handler gets redelivered by the new owner, and at-least-once permits that. Doing the same on the ordered lane hands the new owner a key the outgoing owner is still running — two messages of one key in flight at once, which is **reordering**, not the duplicate the contract allows. So a shed here quiesces first: `beginShedding()` stops the owner dispatching new keys, the ones already in handlers are allowed to finish, their acknowledgements are flushed under a fence the owner still holds, and only then is the lease released.

**Two consequences, both priced in.** Ordered rebalancing converges more slowly than unordered — the release waits for the slowest in-flight handler, plus a heartbeat tick for the rebalancer to notice. And a shed that cannot finish inside `ShardOwnerSettings.shedGrace` is **abandoned**, not forced: the shard stays where it is and `shedsAbandoned` counts it. Unbalanced beats reordered. The shed is retried on the following tick, so a slow handler delays convergence rather than permanently degrading the split.

**What the test had to be forced to measure.** Three successive versions of the overlap test asserted nothing, each for a different reason, and each passed:

| Version | Why it was vacuous |
|---|---|
| 2 ms handler, 720 messages | The incumbent drained the queue before the joining instance had published its first heartbeat, so no shard ever moved |
| 20 ms handler | Shards moved, but every handler finished inside the ~600 ms between release and the successor acquiring — the overlap window never opened |
| 3 s handler, 32 messages | The drain itself pushed shed completion past the end of the workload, so the shards moved against an empty queue |

The version that works uses a 3-second handler against a ~600 ms takeover gap, with enough work per key to outlast the drain, and asserts on the queue depth *at the moment the shed completed* so a shed against an idle queue cannot be mistaken for one under load. Verified by breaking the engine on purpose: releasing without draining produces **7 cross-instance key overlaps**; draining produces **0**.

Worth stating plainly, because it kept catching me: a rebalancing test passing tells you nothing until you have confirmed the rebalance happened *while the hazard was live*. Two of my checks of that confirmation were themselves invalid — an in-place edit that truncated the file before reading it meant Maven silently reused the previously compiled class, and the "broken" engine under test was the fixed one.

Turning shedding on also falsified an assertion in the multi-process test, which is the useful kind of failure: it required that a node arriving mid-run receive *no* shards, and that had been true only because the ordered lane never gave any up. The property it was actually guarding — that the incumbents are not evicted, which is what would happen if their leases were not being renewed — is now asserted directly, by checking that both original processes keep delivering after the late node's first delivery.

### 9.17 The Micrometer binding, and the three callbacks that had no caller

`MicrometerQueueObserver` binds `QueueObserver` to a `MeterRegistry`. `micrometer-core` is `provided`, per the project rule that third-party integrations are not transitive.

**Writing the binding is not the work; having something to bind to is.** Three of the seven observer callbacks had no call site anywhere in the engine — `retryScheduled`, `deadLettered` and `shardOwnershipChanged`. A binding over them would have published counters that stayed at zero through every retry and every dead letter, which are the two things a queue gets paged for. A fourth was worse than silent: `deliveryFailed` was emitted from a frame that does not know the attempt count and passed a hardcoded `attempt = 0`, so the number was always wrong rather than merely absent. All four now come from the owners, which is the only place that counts attempts, and `NextGenMessageQueue` fans them back out to the registered observers through a façade that reads the observer list live — so an observer registered after `consume()` still receives them.

**Deliberate shapes in the binding.**

- *Every meter is registered eagerly*, including the failure counters. A counter that materialises on first use does not exist as a series until the incident has already started, which is exactly when an alert needed it to exist.
- *No tag carries the ordering key.* Keys are unbounded — one per customer, per order, per aggregate — and a tag value per key is the standard way to take a metrics backend down. The key reaches `aroundDelivery` for tracing, where it belongs.
- *Depth is opt-in and cached* (`bindQueueDepth`). Every other meter is fed by an event the engine already emits, so it costs an increment. Depth is a query, and Micrometer polls a gauge on every scrape: an unguarded depth gauge would put `2 x shardCount + 1` queries on the database per scrape per process. A scrape while the database is unreachable reports the last known depth instead of throwing into the registry's scrape loop and taking every other meter down with it.

**Two defects, both found by testing against the engine rather than against a mock.** A mock observer would have confirmed that the binding counts what it is told, which was never the risk. Driving real traffic and reading the meters found (1) the three dead callbacks above, and (2) an overflow in the depth cache: it seeded its timestamp with `Long.MIN_VALUE`, and `System.nanoTime()` may be negative, so `now - readAt` overflowed, the staleness test read as fresh, and the gauge reported its seed of zero forever. Both were verified by unwiring the callbacks again on purpose — with the deadlettering and retry callbacks disabled the test times out waiting for the counters, and with ownership disabled it fails on the ownership assertion.

### 9.18 Per-row leases, and the scope that cannot exist

`SessionScope.MESSAGE` and `SessionScope.BATCH` now work, on the unordered lane. `SessionScope.KEY` does not, and will not: the reason is the design's central claim rather than a missing piece.

**The mechanism.** One nullable column, `lease_until`, on `ng_unordered`. It is NULL for a pre-claim — Tier 2's local hand-off, which is consumed immediately and never expires — and set for a session's row lease, which does. That distinction is the whole point: the shard's owner must ignore a live session's rows and must pick up a dead owner's pre-claims, and a bare fence with no expiry cannot tell those apart. Every unordered read gains one predicate, and so does the range-delete acknowledgement — without that last one a session-held row would be deleted undelivered once its hole expired, which is §4.3's bug in a new costume.

Session fences are allocated **negative**, from their own sequence, so they can never collide with an owner fence in the shared `lease` column. The two mean opposite things and are read by the same predicates.

**What it costs and what it buys.** A write per claimed message — deliberately, and exactly as `SessionScope` documents. Several sessions can pull from one shard at once (`SKIP LOCKED` in the claim, the one place in this engine where that construct earns its keep, because here there genuinely are competing writers), and a push consumer keeps running beside them. The push fast path is untouched and still writes nothing when it consumes.

**The residual overlap, stated rather than hidden.** A row lease cannot retract a row the owner had already read into memory before the claim landed. The owner's read is a snapshot, and re-checking at dispatch would put a query on the fast path. So a message can reach both a push consumer and a session. That is a duplicate, which the unordered lane's at-least-once contract permits — and it is precisely why these scopes are not offered on the ordered lane, where the same overlap would be reordering.

**`KEY` scope is not implementable here, and that is a finding.** It is specified as "ordering safe, blocks only that key". Per-key order in this engine is enforced by an in-memory set of the keys a shard's owner has in flight. A session in another process cannot enter that set. For it to take one key safely, the owner would have to re-check the database before dispatching each key — a query per message on the fast path, which is exactly the cost ordering-by-ownership exists to avoid. The taxonomy came from claim-based queues, where every dispatch already reads and writes, so an intermediate granularity is free; here it is not. On the ordered lane the unit of exclusivity **is** the shard, because that is what makes ordering free, so `SHARD` is the answer rather than a fallback. `openSession` says so, with the reason.

**Verified by breaking it.** With the row-lease predicate removed from the owner's reads, a push consumer delivers all five messages a live session is holding; with it, the consumer sees none until the session's lease lapses, and then all five. The lapsed session's acknowledgement is refused. One stale test had to be rewritten — it asserted that MESSAGE and BATCH throw, which was the old behaviour; that is the third time in this work a test encoded a limitation as if it were a contract.

### 9.19 Lifecycle, and a thread count that does not scale

Two things a read of the code caught that the tests could not.

**The SPI did not follow the project's own lifecycle convention.** `DurableQueues` and `DurableQueueConsumer` both extend `dk.trustworks.essentials.shared.Lifecycle`; this engine exposed `AutoCloseable` plus an ad-hoc `startConsuming(handler, settings, maxShards)`. That is not a cosmetic difference: a container has to construct a resource first and start it later, and it has nowhere to pass a handler at start time. So configuration is now separate from starting — `configureUnordered` / `configureOrdered` set the handler, `start()` leases and runs — and `MessageQueue`, `Subscription` and `NextGenQueue` all extend `Lifecycle`, with `close()` defaulting to `stop()` so try-with-resources still works. `startConsuming*` remains as configure-then-start, so nothing that used it changed.

`stop()` had to become genuinely restartable rather than merely idempotent: it now clears the owners, wake-ups and shard map. Without that a restart would resurrect owners whose fences were long superseded — every write they attempted would be refused by the fencing clause, silently, and the instance would hold shards it could not serve. The lifecycle test asserts the restart delivers, not just that `isStarted()` flips.

**Threads scale with `shardCount x keyConcurrency`, which is a defect.** One `consume()` at 8 shards and `keyConcurrency = 8` creates roughly 83 threads:

| Source | Threads |
|---|---|
| `NextGenListener` (one per queue, unordered lane only) | 1 |
| unordered owner pool + heartbeat | 8 + 1 |
| ordered owner pool + heartbeat | 8 + 1 |
| `OrderedShardOwner.handlerPool` — **allocated per owner** | 8 x 8 = 64 |

The listener is one thread per queue and is fine. The problem is that each `OrderedShardOwner` allocates its own fixed pool of `keyConcurrency`, so the ordered lane's handler threads multiply by shard count. It should be one bounded pool per queue, with per-key serialisation left exactly where it already is — the `keysInFlight` set, which is what actually enforces FIFO. Recorded rather than fixed here: it changes the shape of §4.7's parallelism claim and deserves its own measurement.


### 9.20 What several queues cost, and the gate that should have existed from the start

Every measurement before this one was taken against a single queue, and every phase gate was about storage cost — WAL bytes per message, dead tuples per message. The argument for choosing those was that they are properties of the design rather than of the machine, and that argument is correct. It was then applied to exactly one kind of cost. Threads, held connections and idle query rate are equally properties of the design and equally machine-independent, and nothing gated them. `NextGenMultiQueueCostIT` closes that gap.

**Measured: 5 queues x 2 lanes x 4 shards.**

| | measured | per queue |
|---|---|---|
| threads, idle | +56 | 11.2 |
| threads, under load | +136 | 27 |
| held connections | 45 | 9.0 (= 2 lanes x 4 owners + 1 listener) |
| idle queries/second | **52 132** | ~2 600 per ordered owner |
| holes per message (5 queues) | **3.8** | = queues - 1 |

Extrapolated to 25 queues at 8 shards: roughly 1 350 threads, **~425 held connections**, and **~520 000 queries/second while idle**.

**The dominant defect is the ordered lane's missing wake-up.** Tier 1 was built, tested and measured for the unordered lane; `OrderedShardOwner` was never wired to it and instead sleeps `idleParkMicros` — 200 microseconds — and re-queries. The park does not pace anything: the loop runs as fast as the database answers. Every headline latency figure in this document was taken on the unordered lane, which is why this never surfaced.

**Per-shard sequences are shared by every queue.** `ng_seq_shard_0` is one counter for all queue ids, so from any single queue's view the values other queues took are gaps, and a gap is a hole. The chase queries are not the damage — there were only 26. The damage is that the acknowledgement floor is `min(lowest in-flight, lowest pending hole)`, so it stays pinned for the full `holeExpiry` while `pendingHoles` grows at `queues - 1` per message.

**Two corrections to what this document previously implied, and one to my own test.**

- An earlier estimate of 2 105 threads was a saturated upper bound quoted as if it were the figure. `Executors.newFixedThreadPool` populates lazily, so idle queues carry a fraction of it. The measured equivalent is roughly 1 350.
- The first version of this test measured **zero** holes and very nearly disproved the sequence finding. Every enqueue had taken the Tier 2 local hand-off path, which stamps the row with the owner's fence so the cursor read skips it — the cursor never walks the sequence, so the question was never asked. Disabling hand-off is what made the mechanism observable.
- The first version also counted rows in `pg_stat_activity`, reporting 99 connections for five queues. Most of that was Hikari keeping what it had opened. Held (checked-out) connections is the number that starves a pool, and it is 45.

**Direction.** The storage design is unaffected by any of this — no claim write, indexes 5.3x smaller, cross-process per-key ordering. What is wrong is the concurrency model, which took a thread and a connection per shard per lane where the old implementation's `CentralizedMessageFetcher` already uses one scheduler thread for all queues. The rework separates the two axes: database contact onto a small fixed set of platform threads each owning one connection and serving many shards, and consumer concurrency onto virtual threads bounded by a semaphore. Virtual threads are the wrong tool for the first axis — a virtual thread blocked in JDBC still holds a connection — and the right one for the second.

One constraint on that: the project compiles `--release 21`, and on JDK 21-23 `synchronized` pins the carrier thread. `OrderedShardOwner.onFailure` performs a JDBC round trip inside `synchronized (stateLock)`, which would pin a carrier per failing message. It needs a `ReentrantLock` with the database call outside it — which it should have anyway, since today it serialises a shard's whole failure path behind one monitor across a network call.


### 9.21 Steps one and two of the rework, measured

**Per-(queue, shard) sequences.** `ng_seq_shard_N` was one counter shared by every queue id. `registerQueue` now creates `ng_seq_q<queue>_s<shard>`, so a gap in the sequence a queue reads means what the design always claimed it meant: a transaction that has not committed yet.

**The ordered lane wired to Tier 1.** The mechanism existed, was tested, and had been measured — it was simply never connected to this lane, which slept 200 microseconds and re-queried instead. The notification was already being emitted by `enqueueOrderedBatch`; nothing was listening for it. The wake-up payload now carries the lane as well as the queue and shard, because the two lanes have separate owners reading separate tables and an unordered enqueue was otherwise free to wake an ordered owner into a read that could only return nothing.

| 5 queues x 2 lanes x 4 shards | before | after |
|---|---|---|
| idle queries/second | 52 132 | **160** |
| holes per message | 3.8 | **0** |
| held connections per queue | 9.0 | 10.0 |
| threads, idle | 11.2/queue | 12.2/queue |

Both are now gates rather than recorded numbers: the test fails above 1 000 idle queries/second, and above 0.5 holes per message.

Connections and threads went slightly **up**, which is honest and expected: the ordered lane gained a listener thread and its dedicated connection. That cost is real and is what step three removes, along with the per-shard threads themselves. It is worth being explicit that the two big numbers so far were fixed by *connecting existing mechanisms correctly*, not by new machinery — the spin existed because Tier 1 was built for one lane and the other lane was left on a placeholder park that nobody revisited.


### 9.22 The concurrency rework: pumps, and handlers on virtual threads

The two axes are now separate mechanisms, which is what the whole problem needed.

**Database contact.** `ShardPump` is one platform thread holding one connection and serving many shards. Its count comes from `ShardOwnerSettings.pumpThreads`, never from `shardCount`. Every shard a pump serves shares that pump's `ShardWakeup`, so the listener signals the pump and the pump works out which of its shards has something — an occasional empty read on a quiet shard, in exchange for a thread per shard.

**Consumer concurrency.** Handlers run on one `newVirtualThreadPerTaskExecutor` per queue. They are user code that mostly waits, which is what virtual threads are for. What used to be `shardCount x keyConcurrency` platform threads is now a virtual thread per message actually in flight, bounded per shard by `keyConcurrency` as before. Virtual threads are deliberately **not** used for the pumps: a virtual thread blocked in JDBC still holds a connection, so putting database work on them would raise the connection count rather than lower it.

| 5 queues x 2 lanes x 4 shards | start of the session | now |
|---|---|---|
| threads under load | +201 | **+41** |
| held connections per queue | 9.0 | **6.0** |
| idle queries/second | 52 132 | **160** |
| holes per message | 3.8 | **0** |

For the twenty-five queue, eight shard case that prompted this: roughly **20 held connections instead of 425**, since connections now follow `pumpThreads x lanes x queues` rather than `shards x lanes x queues`.

**Four defects, all mine, all introduced by this change.**

1. **The pump ran `pumpOnce` before `onTakeover`** for owners added after it started — and pumps start before any shard is leased. The attempt bump then found nothing left to bump, which is the only thing standing between a JVM-killing handler and an infinite redelivery loop.
2. **`flushAcks` iterated `pendingAcks` and read `inFlight` with no lock** while handler threads mutated them. The `ConcurrentModificationException` escaped `pumpOnce`, and the pump caught only `SQLException` — so the pump thread died and took every shard it served with it. Exactly 250 of 1 000 messages went missing, which is one shard of four. The pump now also catches `RuntimeException` per owner, because a shared thread means one owner's bug must not stall the shards beside it.
3. **The in-flight slot was released before the failure was recorded**, so the pump re-read and re-delivered the row before `onFailure` had moved `visible_at`. The message burned two attempts for one failure and was dead-lettered where the policy said retry — exactly one message of forty, every run. The slot now spans `onFailure`.
4. **The local hand-off drain polled before checking capacity.** `for (var handed = localHandoffs.poll(); handed != null && !atCapacity(); ...)` evaluates the condition *after* `poll()` has removed the element, and a hand-off has no other copy anywhere. 350 of 500 messages, discarded outright.

**A fifth was a test, and it is the fourth of its kind in this work.** `observers_see_enqueue_delivery_and_can_wrap_the_handler` compared a global `delivered` counter across `delivery.run()`, which asserts that no *other* delivery completed meanwhile — a claim about the engine being single-threaded, not about the wrapper. The wrapper's contract is only that this handler runs inside this call, and that is now what it checks.

**The duplicate delivery noted here as an open item was root-caused; see §9.23, which also retracts the asynchronous-delivery half of this section.**


### 9.23 Root-causing the duplicate — and retracting the asynchronous unordered delivery

The open item from §9.22 was a duplicate delivery seen once in three runs. Chasing it properly cost two retractions and produced one real fix.

**Two clocks, one message.** A failed message exists in two places: this shard's in-memory retry schedule, and the table with `visible_at` in the future. Those are timed by *different clocks* — `visible_at` by the database's `now()`, the schedule by `System.nanoTime()`. When the row becomes visible before the schedule says it is due, the head sweep delivers it, it is acknowledged, and the row is deleted; the schedule entry then fires against a message that no longer exists and delivers it a second time. `deliver` now drops any pending schedule entry for a sequence it accepts, so whichever path gets there first invalidates the other. Four runs of a test built to force it, clean.

**A second, genuine defect found on the way.** `flushAcks` treated a delete affecting zero rows as "the fence rejected me", when it equally means "those rows were already gone". Reading the first as the second costs a lease the owner still holds: it stops, the shard is re-acquired, and everything in flight is redelivered. It now asks the lease table directly, one primary-key lookup and only on the ambiguous path.

**Retraction 1: asynchronous unordered delivery is reverted.** Dispatching unordered handlers to virtual threads decouples a slow handler from the other shards a pump serves, which is a real benefit. It also makes the cursor, the hole map, the in-flight set and the acknowledgement floor concurrent — and those four are what the design's correctness rests on. One change produced five distinct defects: a dead pump thread from an unguarded iteration in `flushAcks`, a message dead-lettered because its in-flight slot was released before its failure was recorded, 350 of 500 hand-offs discarded by a `poll()` evaluated before its guard, the head sweep taking over most of delivery from the fast path, and the false fencing rejection above. Unordered delivery is inline again. The cost is real and stated: a slow handler occupies its pump and therefore the other shards that pump serves, tunable through `pumpThreads`. **The ordered lane keeps its virtual threads** — it was already asynchronous by design, its state was already behind a lock, and it is where the thread multiplication actually was.

**Retraction 2: "the fast path has collapsed, 97% of deliveries come from the head sweep" was wrong.** That measurement came from a test written to saturate capacity, and the same measurement with delivery inline still showed 76%. This workload enqueues faster than a 40 ms handler drains, so a sweep running after a long delivery batch legitimately finds work the cursor has not reached. The number is a property of the harness, not of the engine, and I had already begun reverting on the strength of it. The assertion has been removed rather than loosened, with the reasoning recorded in the test.

**Final measurements, unchanged by the revert** — the thread and connection wins came from the pumps and the ordered lane, not from asynchronous unordered delivery:

| 5 queues x 2 lanes x 4 shards | session start | now |
|---|---|---|
| threads under load | +201 | +55 |
| held connections per queue | 9.0 | **6.0** |
| idle queries/second | 52 132 | **160** |
| holes per message | 3.8 | **0** |

51 tests, three consecutive clean full runs.
