# Making the Ordered Lane's Shard Count Adjustable

**Status: design, not implemented.** §4 records measurements that were taken; §5 and §6 are reasoning
that has not been built or tested. Nothing in this document is in the engine yet.

Revision note, in the order the measurements landed:

- The **first draft** proposed a 1024-bucket routing space with per-bucket sequence allocation from a
  counter table, and named counter-table contention as its first open question. Measured: no (§4.4).
  Its read shape was rejected too (§4.1), and §4.2 put a ceiling on how many units an owner can hold.
- The **second draft** replaced density with a snapshot-horizon watermark. Its safety test was
  **unsafe** — `pg_snapshot_xmax` does not bound running xids, and the rig reproduced the trap before
  the design would have (§4.6). §5.3 now states the corrected test, which needs a different and more
  expensive primitive (§4.5) and a specific sampling order.
- The watermark itself **passes its gate** (§4.7): it trails by about one write-transaction duration,
  the re-read window is 8 to 20 rows, and only long-running *writing* transactions stall it — the
  second draft overstated that risk.

What survives is §5. The highest-risk unknown is no longer the mechanism but whether `backend_xid`
stays readable everywhere the engine runs (§6).

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

## 3. The constraint that turned out not to be one

The fix in shape is a fixed routing space: hash keys into units that never change, and let owners hold
*sets* of units. Growing the number of owners then moves units, not keys.

The earlier draft treated one property as a hard requirement and designed around it, at considerable
cost. The property is **density**: a hole — a row whose sequence value the cursor has passed but whose
transaction had not committed when the read happened — is detected today by noticing a gap in a
sequence that is otherwise contiguous, because sequences are allocated per `(queue, shard)`. Share one
sequence across a larger routing space and an owner holding 1/8 of it sees 7 of every 8 values as a
gap, chases each, finds nothing, and abandons it after `holeExpiry`.

Accepting density as a requirement forces one of two bad options — a sequence object per routing unit
(too many relations), or per-unit allocation from a counter table (a row lock on the enqueue path).
The earlier draft chose the counter table. §4.4 measures what that costs.

The requirement is wrong, and the reason is worth stating plainly, because it is the load-bearing
insight of this revision:

> Density is not what hole detection needs. It is one way to obtain what hole detection needs, which
> is **an answer to "is it safe to advance the cursor past this value yet"**. PostgreSQL answers that
> question directly, exactly, and for a tenth of a microsecond, from the transaction snapshot — with
> no reference to how values are allocated.

§5.3 gives the argument and §4.5 prices the primitive. Once the cursor can be advanced safely without
density, per-unit sequences are unnecessary, the counter table is unnecessary, and hole chasing,
`holeExpiry` and the hole half of the ack floor are all deleted rather than reworked.

## 4. What was measured

PostgreSQL 17.5, `max_parallel_workers_per_gather = 0` (an owner holds one connection and never gets a
gather on this path). Fixture: a table shaped like `shard_queue_ordered` with `shard` widened to a
1024-value `bucket`, 2 000 000 rows inserted round-robin across the buckets so the heap is in enqueue
order and *not* clustered by bucket, `seq` dense per bucket (max 1954 per bucket), 200-byte payloads.
948 MB total — 744 MB heap, 60 MB discovery index.

**Correction to the earlier draft.** Its fixture used `PRIMARY KEY (queue_id, bucket, seq)`. The
engine's ordered table is `PRIMARY KEY (queue_id, shard, msg_key, key_order)` with discovery on the
*secondary* index `shard_queue_ordered_seq (queue_id, shard, seq)` — the primary key serves key lookup,
not the cursor. Everything below is measured on the engine's real shape. The earlier draft's headline
result survives the correction, because the index doing the work was always the discovery index.

### 4.1 Reading a bucket range: three shapes, three verdicts

All against a 128-bucket range, `LIMIT 100`, in three states an owner is actually in — **backlog**
(cursors far behind, plenty to return), **near-idle** (cursors at the head of every bucket, nothing to
return — by far the most frequent poll), and **sparse wake-up** (one bucket has new work, 127 are
drained — what a `ShardWakeup` notification produces).

| Read shape | backlog | near-idle | sparse wake-up |
|---|---|---|---|
| **A** single composite row-value cursor `(bucket, seq) > (…)` | 107 buf, **0.62 ms** | 1 970 buf, **10.16 ms** | — |
| **B** per-bucket cursor list as a plain join | 135 977 buf, **68.1 ms** | 384 buf, 0.61 ms | 502 016 buf, **234.4 ms** |
| **C** per-bucket cursor list via `LATERAL` + inner `LIMIT` | 1 418 buf, **1.68 ms** | 384 buf, **0.74 ms** | 393 buf, **0.23 ms** |
| **C′** same, cursors bound as two arrays (what pgjdbc sends) | 1 412 buf, **0.94 ms** | — | 393 buf, **0.19 ms** |
| today, for reference: one query per unit, single unit | 110 buf, 0.20 ms | 3 buf, 0.012 ms | — |

**Shape A is not viable.** It is the shape the earlier draft proposed, and its good number is real —
the row-value comparison is pushed into the index condition, one index scan, 0.62 ms over two million
rows:

```
Limit (actual time=0.043..0.592 rows=100)
  ->  Index Scan using ord_seq on ord
        Index Cond: ((queue_id = 1) AND (bucket >= 128) AND (bucket < 256)
                     AND (ROW(bucket, seq) > ROW('128'::smallint, '900'::bigint)))
        Buffers: shared hit=6 read=101
```

But the earlier draft measured only the backlog state. Near-idle, with the cursor at the end of the
range, the planner abandons the discovery index for `ord_visible`, adds an Incremental Sort, reads
1 953 rows to return none, and takes **10.16 ms** — sixteen times the cost of the state that has work
to do, in the state the engine spends almost all its time in. Dropping the redundant lower bucket
bound does not help (1 958 buffers, 1.85 ms).

There is a second, independent defect in shape A that the measurement makes concrete. A single
composite cursor cannot represent per-bucket progress: with the cursor at `(200, 1954)` the scan
resumes at bucket 201 *from seq 0*, and returns 100 rows the owner has already delivered (103 buffers,
0.18 ms — fast, and wrong). The earlier draft's §5.4 argued this is acceptable because acknowledged
rows are deleted, so a re-read only re-reads what is still in flight. Under backlog that is exactly
the failure: every poll returns rows already in flight and the owner makes no progress. **Per-bucket
cursors are required, not a more precise alternative.**

**Shape B is plan-unstable, which is worse than slow.** Given a list of per-bucket cursors as an
ordinary join, the planner picks a Merge Join in two of the three states, pushes the per-bucket cursor
down to a *join filter*, and scans the whole discovery index: 68 ms under backlog, **234 ms** on the
sparse wake-up, half a million buffers. It picks the nested loop only in the near-idle state, where it
is excellent. A read shape whose cost varies by three orders of magnitude with the planner's mood
cannot go on this path.

**Shape C is the one to build.** Wrapping the per-bucket read in `LATERAL` with its own `LIMIT` makes
the nested loop *structural* rather than a planner choice — a merge join cannot satisfy a per-row
limit — and it is stable and fast in all three states:

```sql
SELECT x.bucket, x.seq, x.msg_key, x.key_order, x.payload_type
FROM unnest(?::smallint[], ?::bigint[]) AS cur(unit, cursor),
LATERAL (SELECT o.bucket, o.seq, o.msg_key, o.key_order, o.payload_type
         FROM shard_queue_ordered o
         WHERE o.queue_id = ? AND o.bucket = cur.unit AND o.seq > cur.cursor
           AND o.visible_at <= now()
         ORDER BY o.seq LIMIT ?) x          -- inner limit: per-unit fair share
ORDER BY x.bucket, x.seq LIMIT ?;
```

```
Limit (actual time=0.905..0.914 rows=100)
  ->  Nested Loop (actual time=0.056..0.813 rows=1024)
        ->  Function Scan on cur (rows=128)
        ->  Limit (actual time=0.004..0.005 rows=8 loops=128)
              ->  Index Scan using ord_seq on ord o
                    Index Cond: ((queue_id = 1) AND (bucket = cur.b) AND (seq > cur.c))
```

The inner `LIMIT` is not only a plan-stability device. It fair-shares the read across the owner's
units, so one hot unit cannot monopolise a batch and starve the other 127 — which the ordered lane
wants anyway. Binding the cursors as two arrays rather than a literal `VALUES` list is both what
pgjdbc would send and slightly faster.

### 4.2 The idle poll costs three buffers per unit held, linearly

The near-idle poll is the one that multiplies by deployment size: an owner pays it roughly twice a
second per queue whether or not there is work. Warm, one query, nothing to return:

| units held | buffers | time |
|---|---|---|
| 8 | 24 | 0.019 ms |
| 32 | 96 | 0.063 ms |
| 64 | 192 | 0.074 ms |
| 128 | 384 | 0.142 ms |
| 1024 | 3 072 | 1.158 ms |

Three buffers per unit — one index descent each — and it does not amortise. **The range read makes
round trips O(1) per owner; it does not make units free.** At 8 units held it is a strict improvement
on today (one query and 24 buffers, against eight queries and 24 buffers). At 1024 it is 1.16 ms per
poll, and one instance owning the whole routing space across 300 queues would spend about 70% of a
core on polls that return nothing.

This is the measurement that sets the routing space. It rejects "1024 units, ownership by range"
independently of anything to do with sequences, and it does so through the same multi-queue budget
`ShardOwnedMultiQueueCostIT` already guards.

### 4.3 Acknowledging a scattered set within a unit stays an index scan

0.076 ms, unchanged from the earlier draft — the ack path is not affected by any of this.

```
Delete on ord
  ->  Index Scan using ord_pkey
        Index Cond: ((queue_id = 1) AND (bucket = 200) AND (seq = ANY ('{5,9,17,33}')))
```

### 4.4 Counter-table allocation holds a row lock for the caller's whole transaction

Uncontended, the earlier draft's number reproduces: a block allocation is a primary-key update at
0.062 ms. Uncontended was never the question. The engine's headline case is the Outbox — enqueue
inside the caller's business transaction — and a row lock is held to **commit**, not to statement end.

Producer A holds a 200 ms business transaction after allocating; producer B allocates concurrently.
The harness baseline (process start plus connect) is ~42 ms, so anything near 42 ms did not block:

| producer B does | elapsed |
|---|---|
| counter table, **same** unit as A | **211 ms** — waits out A's entire transaction |
| counter table, different unit (control) | 42 ms — no wait |
| `nextval` on the same sequence as A (today's path) | 44 ms — no wait |
| counter table, one unit, after A allocated a **1024-unit batch** | **217 ms** |

The last row is the one that settles it. Ordered enqueue routes per key, so a large batch of distinct
keys touches essentially every unit and takes a lock on every one of them. Any other producer then
blocks, whichever unit it wanted. Today that same batch takes no locks at all.

There is no way to hold the lock more briefly from inside a caller-supplied transaction: savepoints do
not release locks, and PostgreSQL has no autonomous transaction without an extension. Sorting units
within a batch is still mandatory if a counter table is ever used — otherwise two batches deadlock —
but it removes only the deadlock, not the serialisation.

**Open question 1 of the earlier draft is answered: no.**

### 4.5 The snapshot horizon costs 0.086 ms per poll, not 0.098 µs

`pg_snapshot_xmin(pg_current_snapshot())` is effectively free — 100 000 calls in 9.79 ms, **0.098 µs
per call**. But it is not sufficient on its own, for the reason in §4.6, and the primitive that is
sufficient costs more:

| primitive | cost | note |
|---|---|---|
| `pg_snapshot_xmin(pg_current_snapshot())` | 0.098 µs | needed, not sufficient |
| `max(backend_xid)` over `pg_stat_activity` | **0.086 ms** | the bound §5.3 actually needs |
| `nextval` on a `CACHE 1` sequence | 0.18 µs | today's enqueue path, unchanged |

0.086 ms once per poll roughly doubles the idle-poll cost measured in §4.2 (0.074 ms at 64 units held),
which is still comfortably inside the multi-queue budget but is not the rounding error the first draft
of §5.3 implied.

`backend_xid` for other users' backends was readable by an ordinary `LOGIN` role on 17.5 with no
`pg_read_all_stats` grant — verified against a role holding only table and sequence privileges. Do not
carry that assumption to another major version or a managed provider without re-checking it; if it
ever redacts, the algorithm silently computes too low a bound and advances the watermark over live
writers.

### 4.6 The obvious formulation of the safety test is unsafe

The first draft of §5.3 proposed advancing the watermark when
`pg_snapshot_xmin(pg_current_snapshot()) >= pg_snapshot_xmax(X)`. **That test is wrong**, and the
measurement rig fell into it before the design would have.

A snapshot's `xmax` is `latestCompletedXid + 1`, so a transaction that has been *assigned* an xid and
is still running sits at or above `xmax` and appears in neither the in-progress list nor below `xmax`.
A single running writer is observed as:

```
backend_xid = 55486  (running, idle in transaction)
pg_current_snapshot() = 55486:55486:      -- xmin = xmax, in-progress list EMPTY
```

Under a workload of 8 concurrent 50 ms outbox transactions, 98% of polls saw an apparently empty
in-progress list. Quantified against the corrected rule of §5.3, the `xmax` test reports a p99
watermark lag of **12.6 ms where the true figure is 64.1 ms** — it would advance the watermark roughly
five times too early, over transactions that had already allocated a lower `seq` and had not yet
committed. That is the §4.3 message-loss shape, arrived at from a different direction.

### 4.7 Watermark lag under the engine's own workload

The gate for §5.3. 8 producers running the outbox shape — `BEGIN; INSERT` (allocating `seq` via
`nextval`); business work; `COMMIT` — with a sampler running the §5.3 algorithm in its own short
transaction per poll, 5 ms apart, ~1 000–1 700 polls per arm. Lag is how far `safeCursor` trails the
newest committed `seq`, in wall-clock time and in rows. A poll with no qualifying earlier poll is
**stalled**, and is censored at time-since-arm-start rather than dropped.

| arm | producer tps | stalled polls | lag p50 | lag p99 | lag max | rows p50 / p99 |
|---|---|---|---|---|---|---|
| A — 5 ms transactions | 1 185 | 0.1% | 11.0 ms | 12.7 ms | 13 ms | 14 / 16 |
| B — 50 ms transactions | 148 | 0.3% | 38.9 ms | 64.1 ms | 72 ms | 8 / 14 |
| C — 5 ms + an unrelated 13 s **read-only** transaction | 1 194 | 0.1% | 12.2 ms | 13.5 ms | 14 ms | 16 / 20 |
| D — 5 ms + an unrelated 13 s **writing** transaction | 1 172 | **98.1%** | 6 105 ms | 12 619 ms | 12 750 ms | — |

**The watermark trails by roughly one write-transaction duration plus one poll interval**, and the
re-read window is 8 to 20 rows. Open question 2 of the previous revision — re-read window size — is
answered and it is negligible.

**Arm D is the positive control and it stalls as it must**, for 12.75 s of a 14 s writer, at 98% of
polls. Without it, arms A–C would be an uncalibrated negative result.

**Arm C corrects a claim the previous revision made.** It asserted that "a long-running transaction
anywhere, including an unrelated analytics query or a `pg_dump`, holds the horizon back". That is false
for read-only transactions: a 13 s `REPEATABLE READ` reader is indistinguishable from arm A. Only a
long-running **writing** transaction stalls the watermark, because only a writing transaction is
assigned an xid. The risk is real but much narrower than stated — and note that in this engine the most
likely long writer is an Outbox producer, whose messages are the ones being waited for anyway. The
wall-clock cap of §5.3 still earns its place against an unrelated batch writer in the same database.

## 5. The design this suggests — reasoning, not results

### 5.1 Routing

`unit = mix(hash(key)) mod 64`, fixed for the life of the schema and **not configurable**. The knob
that generated this whole problem disappears rather than being made adjustable.

64 rather than 1024 because §4.2 prices an owned unit at three buffers per poll and nothing amortises
it, and because 64 exceeds anything the throughput measurements suggest is useful: the shard-count
sweep found the knee at 4, with 8 buying 95% of what 16 does. 64 caps instances per lane at 64 and
costs 0.074 ms per idle poll — about 4% of a core across 300 queues. 128 is defensible at twice that.
1024 is not.

The routing space and the ownable-unit space are the **same** space. Under §5.2 there is no per-unit
sequence, so a finer routing space than the ownership space buys only rebalance granularity, and §4.2
says read cost is paid per ownable unit — so splitting them adds a concept and buys nothing.

`mix()` matters and is new: `Math.floorMod(key.hashCode(), 64)` takes the low six bits, and
`String.hashCode` clusters in low bits for structured keys (`ORDER-1000`, `ORDER-1016`, …). At
`shardCount` 8 that was invisible. Apply an integer finalizer before the modulus. It is one line, and
once the routing space is frozen it can never be changed.

### 5.2 Sequences

**One sequence per `(queue, lane)`**, `CACHE 1`. Not per unit, and no counter table. The enqueue path
keeps `nextval` and acquires no locks — it stays the write-free path the engine is built around.

`CACHE 1` stops being a nicety and becomes a correctness invariant, because §5.3's argument depends on
allocation order being wall-clock order, which per-backend caching breaks. It is already `CACHE 1`,
already commented as deliberate, and should acquire a test.

A pleasant side effect: `seq` becomes unique within `(queue, lane)`, which retires the gotcha that
"seq 1 exists in every shard" and the deduplication bugs it caused. `MessageId` keeps the triple
`(lane, unit, seq)` because the unit is what makes a by-id lookup an index descent.

### 5.3 The cursor is a safe watermark, not a high-water mark

This replaces hole detection entirely.

**The argument.** Sequence values are handed out in increasing order over time (one sequence object,
`CACHE 1`), and a transaction that allocates a value is assigned its xid no later than the allocation.
So at any instant, let

- `A` = the highest `seq` allocated so far — `pg_sequence_last_value(...)`, which reports allocation,
  not commit; and
- `R` = the highest xid currently running — `max(backend_xid)` over `pg_stat_activity`.

Every `seq ≤ A` was allocated by a transaction that already held an xid at that moment, so any such
transaction still running is at or below `R`. A transaction assigned an xid above `R` had not yet
allocated, so everything it allocates will be above `A`. Therefore:

> **once `pg_snapshot_xmin(pg_current_snapshot()) > R`, every `seq ≤ A` is resolved** — committed and
> visible, or aborted and never coming.

`A` is the watermark that becomes safe, and it is strictly better than the highest `seq` the owner
happened to *see*, because it includes values allocated but not yet visible.

**Read `A` before `R`, and never the reverse.** This is the one ordering the argument depends on. If
`R` is sampled first, a transaction can allocate a `seq ≤ A` afterwards with an xid above `R`, and the
watermark then advances over a live writer. Two statements in that order, not one — evaluation order
within a single statement is not guaranteed.

The rejected formulation — `xmin >= pg_snapshot_xmax(X)` — is measured and unsafe; see §4.6. Do not
reintroduce it because it is cheaper.

**The mechanism.** Per owner, a small queue of `(A, R)` pairs instead of a hole map:

- each poll records `(A_k, R_k)`;
- `safeCursor` advances to `A_k` for the newest `k` whose `R_k` the current `xmin` has passed;
- each poll scans from `safeCursor` and deduplicates against the in-flight and delivered sets the owner
  already keeps.

The re-read window is bounded by the horizon lag rather than by the backlog — measured at 8 to 20 rows
in §4.7 — so there is no equivalent of shape A's livelock. Note that `maxSeen` does not appear: the
owner no longer needs to track the highest `seq` it has seen.

**What this deletes**, rather than reworks: `pendingHoles`, `chaseHoles`, `readOrderedSpecific` as a
chase path, `holeExpiry`, `maxHolesPerChase`, `chaseDelay`, and the hole half of the ack floor — which
is the §4.3/§4.4 bug family, historically the most expensive area of this engine.

**What it does NOT fix, contrary to an earlier revision of this document.** That revision claimed the
watermark eliminates the ordering violation `OrderedShardOwner` documents — a message committing late,
after a higher `key_order` for the same key has already shipped. It does not, and the implementation
measures `orderViolations = 1` in exactly that scenario. The watermark governs the **cursor**, not
dispatch: rows read from above the gap are still accepted and still handed to their key. Eliminating
the violation would mean withholding dispatch until the watermark passes each row, which costs every
message one write-transaction duration of latency — about 11 ms against a pipeline whose p50 is
0.44 ms. That is a *strict-ordering mode* worth offering as an option, not a default, and it is not
part of this design.

What does improve is the cliff. Today an unresolved value is chased until `holeExpiry` and then written
off, after which only the head sweep finds it — and the sweep backs off to `maxSweepInterval` on a
quiet shard. The watermark has no equivalent state to write off: the value is simply read again on the
next pass. Delayed messages get the same treatment for free — a future `visible_at` row manufactures a
hole today that is chased, not found, and expired; under a watermark it is skipped, and the head sweep
delivers it exactly as it does now.

**What it risks, and the bound.** The horizon is **database-global**, so a long-running *writing*
transaction anywhere in the database holds it back and with it the watermark — measured in §4.7 arm D,
a 13 s writer stalls the watermark for 12.75 s. Read-only transactions do not, however long they run
(arm C), which is the narrower and more benign version of the risk than the previous revision claimed.
Today's `holeExpiry` degrades latency instead, and is bounded. So the watermark also advances on a
wall-clock cap, which is `holeExpiry` under a better name and reproduces today's behaviour as the
fallback. Normal operation gets an exact answer for 0.086 ms; the pathological case is no worse than
today.

### 5.4 Ownership

An owner holds a **set** of units, not a contiguous range. Lease rows stay per unit, keyed as today,
and `beginShedding()` / acquire stay exactly as they are.

The earlier draft proposed contiguous ranges with split and merge, which is what made its open question
2 hard — fair-share over ranges is a different calculation, and an instance departing mid-shed is a new
edge case. Sets need none of that: `fairShare = ceil(64 / liveInstances)` is today's arithmetic against
a constant, and today's acquire loop is unchanged. §4.1's read takes an array of units and an array of
cursors, so it does not care whether they are contiguous.

The hard half already exists and this design does not touch it: `beginShedding()` stops new dispatch,
waits out the in-flight keys, flushes acknowledgements under its still-valid fence, then releases.
Moving a unit between owners without reordering is exactly what it does today for a shard. What breaks
ordering now is not the handover — it is that routing moves at the same time. Fix the routing and the
existing machinery covers the rest.

### 5.5 Polling

One `ShardWakeup`, one park, one backstop poll and one query **per owner**, not per unit. This is the
change that makes the read in §4.1 worth having, and it is not free work: today each shard has its own
`ShardWakeup` cascading to its pump's, and the notification payload is `queueId:lane:shard`. The
payload becomes `queueId:lane:unit` and the owner maps the unit to its held set.

At the same units held this is strictly better than today — one round trip instead of eight for the
same 24 buffers — and it preserves the property the per-shard wake-up was introduced to get
(14.5 cursor reads per message before it, 2.0 after), because the owner still only reads when one of
its own units is signalled.

### 5.6 What this costs

- A `unit` column on `shard_queue_ordered` replacing `shard`, and the discovery index re-keyed to
  `(queue_id, unit, seq)`. Not backward compatible — the engine is unpublished, which is when to do
  this.
- One sequence per `(queue, lane)` instead of one per `(queue, shard, lane)`: **fewer** relations than
  today, not more.
- The owner's read becomes one `LATERAL` query over arrays; the cursor becomes an array plus a
  watermark.
- Wake-up, park and backstop move from per shard to per owner.
- `growShardCount`, `refreshShardCount`, `setAutoRegisterShardCount`, `shard_count` in the registry,
  the sizing sections of three documents and `ShardOwnedShardCountSweepIT` are **deleted**, not
  rewritten.
- `pendingHoles`, `chaseHoles`, `holeExpiry`, `maxHolesPerChase`, `chaseDelay` are deleted (§5.3).

The net is a smaller engine than the one that exists today, with one fewer configurable number and one
fewer subsystem.

## 5.7 Build order, and why it is not the obvious one

The obvious order is routing first — it is the defect this document exists for. That order is wrong,
and §4.2 is what says so.

1. **The watermark (§5.3), on today's per-shard schema.** Today's sequences are dense, so a watermark
   is strictly more conservative than hole detection: it is a drop-in that needs no schema change and
   no routing change, and the existing suite validates it. It is worth having on its own, and it
   removes the only reason per-unit sequences existed. **Done.**
2. **The read and the polling (§4.1, §5.5).** One `LATERAL` query, one wake-up, one park per *owner*
   instead of per unit.
3. **The routing space (§5.1).** Only now.

Doing 3 before 2 regresses the thing this engine is most careful about. §4.2 measures the idle poll at
three buffers per unit held, linear and unamortised, and today each unit is its own owner with its own
wake-up, park and query. Going from eight units to sixty-four without step 2 is therefore sixty-four
queries and sixty-four parks per poll cycle rather than eight — about 6.4 queries/s per queue idle
against 0.8, or **1 920/s across 300 queues against 240**. That is the budget `ShardOwnedMultiQueueCostIT`
exists to hold.

Step 2 does not depend on step 3: at today's eight units it is already one round trip instead of eight
for the same twenty-four buffers. It is what makes a large routing space affordable, so it goes first.

**Step 2 is half done, and the halves came apart in a useful way.** An owner with work issues three
statements — cursor read, head sweep, next-visible. Batching all three slowed delivery badly: 380, then
280, of 1 000 within a minute, against 1 000 in under three seconds unbatched, with no statement
failure and no fallback taken. Bisecting split it cleanly:

- **The batched cursor read is correct and is now in place.** `ShardOwnedBatchedReadIT` compares it
  against the per-shard read it replaces — same sequence values, same order, same keys, at cursors at
  the head, mid-shard and past the end — and the same for the batched sweep and next-visible. Batched
  cursor read with per-shard sweeps passes the full suite.
- **The batched sweep is not, and the cause is in how swept rows are APPLIED rather than in the
  statement**, since the statements are proven equivalent. Neutralising the batched next-visible does
  not fix it, so it is the swept rows themselves. Left per-shard until understood.

Instrumenting the failing configuration says it is **starvation, not slow work**. With batched sweeps,
600 of 1 000 messages arrive in sixty seconds and the counters read:

| counter | batched sweep | meaning |
|---|---|---|
| `cursorReads` | 193 over 60 s, 8 shards | ~24 passes per shard per minute — the pump is barely running |
| `headSweeps` | 59 | sweeps are rare, not frequent |
| `horizonProbes` | 10 | `advanceWatermark` returned early almost every pass |
| `maxWatermarkLagSeq` | 1 000 | the watermark never advanced off the start |

So the owners are not being pumped, rather than being pumped and doing too much. That points at the
interaction between a batched sweep and the two things that decide whether an owner is pumped at all —
`needsAttention` and `parkDeadlineMillis`, both of which read `lastSweepNanos` and `sweepIntervalNanos`,
and `adjustSweepBackoff`, which doubles the interval to a thirty-second ceiling on any pass that swept
and delivered nothing. The next attempt should start by logging that interval per owner rather than by
re-reading the SQL.

Two things follow for whoever finishes it.

**Most of the win is still in the sweep.** Two of the three statements are the sweep pair, so batching
only the cursor read saves at most one in three, and only when two or more ordered owners are attentive
in the same pass.

**Which is rarer than it looks, and that reframes the whole step.** `needsAttention` consumes a
per-shard wake-up — the mechanism that took cursor reads per message from 14.5 to 2.0 — so on a busy
queue typically ONE shard is attentive per pass and the batch has a single member. Measured on the
4-shard watermark test before the split: statements issued equalled owners served, meaning the batch
never formed at all. Batching is therefore **an idle-cost mechanism, not a throughput one**: its win is
the case where many shards' sweeps fall due together, which is precisely the case that decides how
large a routing space costs. It has to be gated on a quiet queue's queries-per-second, not on a
workload where the per-shard wake-up has already made it a no-op.

Two pieces of step 3 do not depend on step 2 and have landed early, because both are cheap and both
get harder to change later: the hash mixer of §5.1, and collapsing the ordered lane to one sequence
per `(queue, lane)` — safe as soon as step 1 removed the density requirement, and it retires the
"sixty-four sequence objects per queue" objection before it can be raised. The unordered lane keeps
its per-shard sequences, because it still detects holes by density.

## 6. Open questions, in the order they should be answered

Four questions are now closed: counter-table contention by §4.4, range splitting by §5.4 choosing sets
over ranges, horizon lag by §4.7, and re-read window size by §4.7 (8 to 20 rows).

1. **`backend_xid` visibility across environments.** §4.5 verified an ordinary role can read it on
   PostgreSQL 17.5 with no grant. If a managed provider or a later major redacts it, the algorithm does
   not fail loudly — it computes too low a bound and advances the watermark over live writers. This
   needs an explicit start-up probe that refuses to run rather than a comment, and it is now the
   highest-risk unknown in the design.
2. **Whether 64 is right.** §4.2 gives the cost curve and §5.1 argues from the shard-count knee at 4.
   128 doubles idle cost for headroom nobody has asked for. The argument for a number larger than the
   largest useful instance count is rebalance granularity alone, and it should be made explicitly or
   not at all.
3. **Whether the unordered lane should change too.** Unchanged from the earlier draft: it has no
   routing problem, and its ack path is a *range delete* bounded by the ack floor, which depends on
   density differently from the ordered lane's per-value acks. Leaving it alone is the conservative
   answer and keeps the change to the lane that needs it. Note that §5.3 would let the unordered lane
   drop hole handling too; that is a second change, not part of this one.

## 7. Until this is built

The ordered lane's shard count is a one-time, effectively irreversible decision. Size it for the
highest instance count and throughput the queue will ever need — idle shards cost about 0.1 queries/s
each, so over-provisioning is close to free and under-provisioning has no cheap remedy.

Unordered queues have none of this: start low, grow with a single call, no restart. `Inbox`, `Outbox`
and `DurableLocalCommandBus` sending plain `Message` are unordered end to end, so the constraint does
not reach them.
