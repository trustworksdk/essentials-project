# The Ordered Lane: Routing, Cursor and Ownership

How the shard-owned engine delivers per-key FIFO, as built. Unordered-lane mechanics are in
`durable-queue-shard-owned.md`; this covers what the ordered lane does differently — routing,
discovery — and the liveness model underneath both lanes.

The three properties everything here serves:

- **A key's messages are handled one at a time, in `key_order`,** by one consumer.
- **No decision an operator makes is unrecoverable,** and no failure needs a human to heal it.
- **Idle cost follows the number of queues a process serves,** not how finely the work is divided.

---

## 1. Routing: a fixed space, chosen per queue

A key's owner is its **unit**:

```java
unit = mix(hash(key)) mod orderedUnits
```

`orderedUnits` is recorded in the registry when the queue is created and never changes for that
queue. `ShardOwnedSchema.ORDERED_UNITS` (64) is only the **default** for a queue being created;
`registerQueue(dataSource, name, shardCount, orderedUnits)` takes an explicit value.

Two things follow, and they are the point of the design.

**Consumers scale without moving keys.** The routing space and the number of consumers are separate
numbers. Adding an instance moves *units* between owners — which `beginShedding()` already does
correctly — and never changes which unit a key belongs to. There is no reshard, no drain, and no
window in which one key has two owners.

**Upgrading the framework cannot re-route live data.** Every process routes by the value it finds in
the registry, so changing the default affects queues created afterwards and nothing else.

**The hash is mixed before the modulus.** `String.hashCode` is a 31-polynomial and `floorMod` against
a power of two keeps only its low bits, which for structured keys are not well spread. Over 1 024 keys
into 64 units, mean 16 per unit:

| key shape | unmixed | mixed |
|---|---|---|
| `ORDER-<n>` | 54/64 units, busiest 39 | 64/64, busiest 24 |
| `acct-<n>-EU` | 52/64 units, busiest 42 | 64/64, busiest 25 |
| zero-padded, step 64 | 63/64 units, busiest 33 | 64/64, busiest 24 |

A skew in how evenly consumers share work rather than a correctness problem — but the mapping is
frozen for the life of a queue's data, and the fix is one multiply and two shifts on the enqueue path.
`ShardRoutingTest` pins both the property and the defect in the obvious implementation.

### Sizing

The space caps how many instances can hold ordered units for that queue. Exceeding it **degrades**:
surplus instances hold nothing on that lane, nothing is lost, duplicated or reordered, and it recovers
on its own when the instance count drops. Measured with 65 instances against 64 units, they converge
to 64 holders of one unit each and one idle.

Raise it at creation only for a queue you already know will be consumed by more instances than the
default allows. Steady-state cost is flat in the space (§7), but per-unit *state* is not — a lease row
and an owner object each — so a process running hundreds of queues should keep the default.

---

## 2. Sequences: one per queue

The ordered lane draws `seq` from a single sequence per `(queue, lane)`, `CACHE 1`.

Not one per unit: per-unit sequences existed to keep values dense within a unit, and density was how
the lane used to tell an uncommitted value from another unit's. It does not decide that by density any
more (§3), so the only thing per-unit sequences would still cost is relations — which is what would
make a large routing space unaffordable across many queues.

Consequences worth knowing:

- An ordered `seq` is unique within its queue, which retires the trap that seq 1 exists in every shard.
- An owner's sequence values are **sparse** within its unit. Fine for a watermark; not fine for hole
  detection, which is why the unordered lane keeps its per-shard sequences.
- `CACHE 1` is a correctness requirement, not a nicety: §3's argument rests on values being handed out
  in wall-clock order, which per-backend caching breaks.

---

## 3. The cursor is a safe watermark

The ordered lane does not detect holes. It never advances its cursor past a value a running
transaction could still commit, so there is nothing to chase.

### The rule

At any instant, let

- **`A`** = the highest `seq` allocated so far — `pg_sequence_last_value`, which reports allocation,
  not commit;
- **`R`** = the highest transaction id currently running — `max(backend_xid)` over `pg_stat_activity`.

Every `seq ≤ A` was allocated by a transaction that already held an xid at that moment, so any such
transaction still running is at or below `R`. A transaction assigned an xid above `R` had not yet
allocated, so everything it allocates will be above `A`. Therefore:

> once `pg_snapshot_xmin(pg_current_snapshot()) > R`, every `seq ≤ A` is resolved — committed and
> visible, or aborted and never coming.

**Read `A` before `R`, never the reverse.** If `R` is sampled first, a transaction can allocate a
`seq ≤ A` afterwards with an xid above `R`, and the watermark advances over a live writer.

**`pg_snapshot_xmax` does not bound running xids** and must not be used here. It is
`latestCompletedXid + 1`, so a transaction holding an assigned xid sits at or above it and appears in
neither the in-progress list nor below `xmax` — one running writer reads as `55486:55486:`, an
apparently empty snapshot. Under eight concurrent 50 ms transactions, 98% of samples look idle.

### The cap

The horizon is database-global, so a long-running **writing** transaction anywhere holds it back.
Read-only transactions do not, however long they run — only a writer is assigned an xid. Rather than
stall behind an unrelated batch writer, a candidate older than `watermarkCap` is taken anyway and
counted as `watermarkCapped`, which is the number to watch.

`watermarkCap` is deliberately **not** `holeExpiry`, and defaults far higher (60 s). `holeExpiry` was
held low by the cost of a map entry and a chase query per unresolved value; a watermark costs one
deque entry and no query, so nothing pushes the bound down. Setting them equal throws away the
exactness and reproduces the mechanism this replaced.

### What this is and is not

It removes hole chasing, `holeExpiry` on this lane, and the `pendingHoles` bookkeeping. It does **not**
make the lane strict about `key_order`: the watermark governs the cursor, not dispatch, so a row read
from above a gap is still handed to its key, and a message committing late under a lower `key_order`
still counts an `orderViolation`. Preventing that would mean gating dispatch on the watermark, at a
cost of about one write-transaction duration per message.

Delayed messages are unaffected — a future `visible_at` row is skipped, and the head sweep delivers it.

---

## 4. Ownership and liveness

An owner holds a **set** of units, each with its own lease row carrying an `owner` and a `fence`.
Acquisition, shedding and fair share work as on the unordered lane: `fairShare = ceil(units /
liveInstances)`, computed per lane, because the two lanes have different unit counts.

**An instance-owned lease carries no expiry.** `lease_until` is NULL and the owner's liveness is its
row in `shard_queue_instance`, which the heartbeat refreshes **once per queue**. Storing an expiry per
unit meant writing one row per unit held on every heartbeat — a write cadence that scales with units
held, which is exactly what this design must not have.

**Correctness rests on the fence, not on the expiry.** An owner whose unit is taken finds its fence
bumped and its writes refused, whether the takeover followed a lapsed lease or a stale instance row.
The heartbeat's remaining job — noticing a unit was taken away, so an owner stops before acknowledging
work its successor is also dispatching — is a question, answered by one `SELECT` per lane.

Two consequences that are easy to get wrong:

- **An instance must register itself before acquiring anything.** Registering on the first heartbeat
  would leave it looking dead for that interval, and instances would steal each other's units at
  start-up.
- **A pull session is not an instance** and heartbeats nothing, so it keeps a real expiry and renews it
  (`acquireSessionLease`). Which rule applies is decided by whether `lease_until` is NULL.

The ordered lane sheds by draining, never by releasing: `beginShedding()` stops new dispatch, waits out
the in-flight keys, flushes acknowledgements under its still-valid fence, then releases. Dropping a
lease mid-key would hand the successor a key the outgoing owner is still running.

---

## 5. Reading: batched per queue

An owner with work would issue three statements of its own — cursor read, head sweep, and when its next
delayed row becomes visible. The pump issues those three **once per queue per pass**, covering every
attentive owner of that queue.

**The shape is `LATERAL` with a per-unit inner `LIMIT`.** The obvious alternative — a list of per-unit
cursors joined ordinarily — is plan-unstable: the planner merge-joins it, pushes the cursor down to a
join filter, and scans the whole discovery index.

| read shape | backlog | near-idle | sparse wake-up |
|---|---|---|---|
| single composite row-value cursor | 0.62 ms | **10.16 ms** | — |
| per-unit cursor list, plain join | **68.1 ms** | 0.61 ms | **234.4 ms** |
| **per-unit cursor list via `LATERAL` + inner `LIMIT`** | **0.94 ms** | **0.74 ms** | **0.19 ms** |

A per-row `LIMIT` cannot be satisfied by a merge join, so the nested loop stops being a preference and
becomes the only legal plan. **Each unit gets a full batch, never a share of one** — splitting it makes
the batched read return less than the per-unit read it replaces, so an owner would see a different
amount of its own backlog depending on how many siblings happened to be attentive.

**A pump is process-wide.** It serves every queue, and `ShardRuntime` hands it a storage handle bound to
queue id 0 for opening connections, plus its own metrics object. A batched statement must therefore be
grouped by queue and bound to the owners' queue id, and counted on the owners' metrics — binding the
pump's own is silent, returns nothing, and looks exactly like an empty queue.

**Batching is an idle-cost mechanism, not a throughput one.** `needsAttention` consumes a per-unit
wake-up, so a pump reads only the unit that was signalled; under load that usually leaves one attentive
owner per pass and nothing to batch. Its win is the case where many sweeps fall due together — a quiet
queue — which is what decides what a routing space costs.

---

## 6. Prerequisites the engine refuses to run without

`verifyWatermarkPrerequisites` runs as the first statement of `startOrdered`, once per `DataSource`.

If `pg_stat_activity.backend_xid` is not readable the query does not fail — it returns a **subset** of
the running transactions, and a subset is not a degraded answer but a wrong one: the watermark advances
over a live writer. So the probe **constructs the condition** rather than inspecting the column, which
is legitimately null for a backend that has not written: it opens a second connection, forces it to
take a real xid, and asserts the first can see it. On a database that hides it, `startConsumingOrdered`
throws and names the grant (`pg_read_all_stats`).

Verified: an ordinary `LOGIN` role can read it on PostgreSQL 17.5 with no grant.

---

## 7. Measurements

PostgreSQL 17.5. The routing-space sweep is benchmark-gated (`-Dbenchmark.run=true`); the rest run in
the normal build.

**Watermark lag** — 8 producers running the outbox shape, sampler running the real algorithm:

| workload | stalled samples | p50 | p99 | re-read window |
|---|---|---|---|---|
| 5 ms transactions | 0.1% | 11.0 ms | 12.7 ms | 14–16 rows |
| 50 ms transactions | 0.3% | 38.9 ms | 64.1 ms | 8–14 rows |
| + unrelated 13 s **read-only** transaction | 0.1% | 12.2 ms | 13.5 ms | 16–20 rows |
| + unrelated 13 s **writing** transaction | **98.1%** | 6 105 ms | 12 619 ms | — |

The watermark trails by about one write-transaction duration. The last row is the positive control:
without it, the first three are an uncalibrated negative.

**Idle cost against routing space** — one queue, idle:

| units | acquire (one-off) | idle queries/s | lease writes/s |
|---|---|---|---|
| 64 | 144 ms | 1.33 | 0.00 |
| 256 | 142 ms | 1.27 | 0.00 |
| 1024 | 221 ms | 1.20 | 0.00 |

Flat. Acquisition is the only per-unit cost left, and it is paid once at start-up.

**Idle cost across queues** — 5 queues, 170 units held: total **7.4 queries/s**, of which lease writes
**0.0**; 0.044 per owned unit. Before the per-unit lease renewal was removed the same configuration
cost 23.8 queries/s, 17.0 of it lease writes.

**Primitives:** `pg_snapshot_xmin(pg_current_snapshot())` 0.098 µs; `max(backend_xid)` over
`pg_stat_activity` 0.086 ms, once per poll and skipped entirely when the watermark is caught up;
`nextval` on a `CACHE 1` sequence 0.18 µs.

**Rejected on measurement:** per-unit sequence allocation from a counter table. Uncontended it is a
0.062 ms primary-key update, but the row lock is held to the *caller's* commit, and this engine's
headline case is an Outbox enqueue inside a business transaction. A producer waits out another
producer's whole transaction (211 ms behind a 200 ms one), and because ordered enqueue routes per key,
a large batch takes a lock on every unit it touches and blocks everyone. `nextval` acquires nothing.

---

## 8. Deliberately not built

**Growth of an existing queue's routing space.** A queue's space is fixed at creation. Raising it for a
queue that already holds data would need the doubling split — `mix(hash(K)) mod 2N` is either `S` or
`S+N`, so each unit splits in exactly two and an owner holding both halves sees every message for every
affected key, with no drain. The mechanism is sound and cheaper than when it was first considered,
since the objections it had to fight — per-unit sequences and density-based hole detection — are both
gone. It is not built because the space is a per-queue choice with a generous default and graceful
degradation past it, so the case for needing it is narrow.

**Changing the unordered lane.** It has no routing problem — placement is round-robin — and its
acknowledgement is a range delete bounded by a floor that depends on density differently from the
ordered lane's per-value acks. Leaving it alone keeps the change to the lane that needed it. §3 would
let it drop hole handling too; that is a separate change.

**Strict `key_order`.** See §3.
