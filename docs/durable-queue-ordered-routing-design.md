# Making the Ordered Lane's Shard Count Adjustable

**Status: design, not implemented.** The measurements in §4 were taken; everything in §5 is reasoning
that has not been built or tested. Nothing in this document is in the engine yet.

## 1. The problem, stated without euphemism

`shardCount` is chosen once when a queue is registered, and for the **ordered lane it cannot be
changed afterwards in a running system.**

`ShardOwnedSchema.growShardCount` exists and appears to offer a way out. It does not, because it
refuses while the ordered lane holds any message, and there is no mechanism by which an operator can
empty that lane except by stopping the producers — which is an outage of that flow, not an operational
procedure. Earlier revisions of the engine's documentation described this as "pause producers, drain,
grow, resume" as though it were a runbook. It is not one. There is no pause.

The consequence is that a user must guess a number correctly, up front, with no way back. That is not
an acceptable property for a component in an application where things go wrong, and it is the reason
this document exists.

The unordered lane does not have this problem: routing is round-robin, so no message's placement
depends on the count, `growShardCount` is a single call, and running consumers pick the new value up
on the next heartbeat (`a_running_consumer_picks_up_a_grown_shard_count_without_a_restart`).

## 2. Why it happens

One number does two unrelated jobs:

```java
// ShardOwnedSchema.shardForKey
shard = hash(key) mod shardCount     // (a) which unit a key is routed to
```

and `shardCount` is *also* how many units exist to be owned — one lease row, one owner, one sequence
each. So changing how many consumers can share the work also changes where every key lives, and a key
that moves while it has messages in flight is a key with two owners, which reorders it.

Every partitioned system separates these two. This one does not, and that is the whole defect.

## 3. Why the obvious fix does not work

The obvious fix is a fixed routing space: hash keys into 1024 buckets that never change, and let
owners hold *ranges* of buckets. Growing the number of owners then moves ranges, not keys.

That is the right shape, but it collides with how delivery detects holes.

A hole is a row whose sequence value the cursor has passed but whose transaction had not committed
when the read happened. It is detected by **density**: sequences are allocated per `(queue, shard)`
from a PostgreSQL sequence (`shard_queue_ordered_seq_q<queue>_s<shard>`), so within one shard the
values are contiguous, and a gap means "not committed yet — chase it".

Share one sequence across 1024 buckets and density is gone. An owner holding 1/8 of the buckets sees
7 of every 8 values as a gap, registers each as a hole, chases it, finds nothing, and abandons it
after `holeExpiry`. Not incorrect — but the hole map fills with garbage proportional to enqueue rate,
and every chase is a wasted query.

Give each bucket its own PostgreSQL sequence instead and there are 1024 sequences per queue per lane,
which does not scale across queues (`ShardOwnedMultiQueueCostIT` exists because multi-queue is where
this engine has broken before).

So the fixed routing space needs a way to keep **dense per-bucket sequences without 1024 sequence
objects**, and a way to read a whole owned range **without one query per bucket**.

## 4. What was measured

Against PostgreSQL 17.5, a table shaped like `shard_queue_ordered` with
`PRIMARY KEY (queue_id, bucket, seq)` and 2,000,000 rows spread over 1024 buckets.

**A composite row-value cursor over a bucket range is an index scan.** This is the load-bearing
result: it means an owner reads its entire range in one query, in `(bucket, seq)` order, so each
bucket's rows arrive as a contiguous run and density is preserved for hole detection.

```sql
SELECT seq, bucket, msg_key FROM ord
WHERE queue_id = 1 AND bucket >= 128 AND bucket < 256
  AND (bucket, seq) > (128::smallint, 900::bigint)
  AND visible_at <= now()
ORDER BY bucket, seq LIMIT 100;
```

```
Limit (actual time=0.029..0.139 rows=100 loops=1)
  ->  Index Scan using ord_pkey on ord
        Index Cond: ((queue_id = 1) AND (bucket >= 128) AND (bucket < 256)
                     AND (ROW(bucket, seq) > ROW('128'::smallint, '900'::bigint)))
        Buffers: shared hit=101 read=5
Execution Time: 0.154 ms
```

The row-value comparison is pushed into the **index condition**, not applied as a filter. 106 buffers
over two million rows.

**Block allocation from a counter table is a primary-key update.** 0.073 ms.

```
Update on bucket_seq
  ->  Index Scan using bucket_seq_pkey  Index Cond: ((queue_id = 1) AND (bucket = 77))
```

**Acknowledging a scattered set within a bucket stays an index scan.** 0.076 ms.

```
Delete on ord
  ->  Index Scan using ord_pkey
        Index Cond: ((queue_id = 1) AND (bucket = 200) AND (seq = ANY ('{5,9,17,33}')))
```

## 5. The design this suggests — reasoning, not results

### 5.1 Routing

`bucket = hash(key) mod 1024`, fixed for the life of the schema and **not configurable**. The knob
that generated this whole problem disappears rather than being made adjustable.

### 5.2 Sequences

Replace the per-shard PostgreSQL sequence with a counter table:

```sql
shard_queue_bucket_seq(queue_id, lane, bucket, next)
```

Enqueue already groups a batch by target shard, so it becomes one `UPDATE … SET next = next + n
RETURNING next - n` per *distinct bucket in the batch*, allocating a block — not one per message.
Values stay dense per bucket, which is what hole detection needs.

Contention is per bucket rather than per queue, so two producers collide only when writing the same
bucket in overlapping transactions. **Unmeasured**, and it is the first thing to measure: it puts a
row lock on the enqueue path, which is the path this engine is otherwise proud of keeping write-free.

### 5.3 Ownership

An owner holds a contiguous bucket range `[a, b)` instead of a shard number. Leases key on the range.
Rebalancing splits and merges ranges instead of handing over numbered shards.

The hard half already exists: `beginShedding()` stops new dispatch, waits out in-flight keys, flushes
acknowledgements under its still-valid fence, then releases. Moving a bucket range between owners
without reordering is exactly what that does today for a shard. What breaks ordering now is not the
handover — it is that routing moves at the same time. Fix the routing and the existing machinery
covers the rest.

### 5.4 Reading

One query per poll per owner, the shape measured in §4. Per-bucket density holds within each run.

**On a range change, reset the cursor to the range start.** A single cursor per range is enough
because acknowledged rows are deleted, so a reset re-scans only rows that are still in flight or
pending acknowledgement, and the in-flight set already filters those. A per-bucket cursor array would
also work and is more precise; the reset is simpler and there is no obvious reason it is not enough.

### 5.5 What this costs

- `bucket` column and a primary key change on `shard_queue_ordered`. Not backward compatible — the
  engine is unpublished, which is when to do this.
- A counter table plus block allocation on enqueue.
- Lease and rebalance move from shard numbers to ranges.
- `growShardCount`, `refreshShardCount`, `setAutoRegisterShardCount`, the sizing sections of three
  documents and the shard-count sweep are **deleted**, not rewritten.

## 6. Open questions, in the order they should be answered

1. **Counter-table contention on enqueue.** The one new write on the hot path. Measure against the
   current per-shard sequence before committing to the design.
2. **Range splitting during rebalance.** Fair-share currently divides a shard count. Dividing a
   bucket space into contiguous ranges is a different calculation and needs its own test — including
   the case where an instance departs mid-shed.
3. **Whether 1024 is right.** It caps instances per lane at 1024, which is not a real limit, but it
   also sets how coarse a range split can be. Larger costs nothing at read time (the range is an index
   bound) and something at rebalance bookkeeping.
4. **Whether the unordered lane should change too.** It has no routing problem, and its ack path is a
   *range delete* bounded by the ack floor, which depends on density differently from the ordered
   lane's per-value acks. Leaving it alone is the conservative answer and keeps the change to the lane
   that needs it.

## 7. Until this is built

The ordered lane's shard count is a one-time, effectively irreversible decision. Size it for the
highest instance count and throughput the queue will ever need — idle shards cost about 0.1 queries/s
each, so over-provisioning is close to free and under-provisioning has no cheap remedy.

Unordered queues have none of this: start low, grow with a single call, no restart. `Inbox`, `Outbox`
and `DurableLocalCommandBus` sending plain `Message` are unordered end to end, so the constraint does
not reach them.
