# Durable Queues — what got faster, and under what conditions

A summary for people **using** the queue, not building it. Every figure here is traceable to a measurement in
[durable-queues-redesign-measurements.md](durable-queues-redesign-measurements.md) or
[durable-queues-v2-design-plan.md](durable-queues-v2-design-plan.md); nothing is estimated, and the things that
did not pay are listed too, because knowing which knobs are pointless is worth as much as knowing which are not.

## The short version

Measured against a queue running normally — producers and consumers together, backlog small — **none of the tuning
options below change throughput or latency**, and capacity is within 7% across all of them. They help when the
queue has *fallen behind*, which is a real and important event but not the normal one.

The exception is the first section, which is free and is not a code change.

## Before any of this: run `ANALYZE` on your queue table

The single largest effect measured in this investigation that costs nothing to fix. A freshly-loaded queue table
with no planner statistics runs the **ordered** claim about **11× slower** — 13 517 ms against 1 184 ms for 20 000
messages. `ANALYZE` recovers it; `VACUUM` alone does not, so this is the planner's row estimates rather than the
visibility map.

It matters because a queue that fills in a burst may never be analysed in time: on a default cluster, **zero
autovacuums ran** during such a workload, since `autovacuum_naptime` is what binds. If you drain ordered messages
from a table that was just filled, analyse it first.

## And read this too: every number here is backlog recovery, not steady state

Every measurement behind this page fills a queue with tens of thousands of messages and then drains it with no
concurrent arrivals. That is a real case — recovering from a backlog — but it is not how a queue normally runs, and
a steady-state queue differs in table size, planner statistics, dead-tuple churn and insert/claim contention. The
figures should be read as *backlog-recovery* behaviour. A steady-state harness does not exist yet.

## Read this first: the numbers do not multiply

Each improvement below attacks a different cost, on a different workload, and several are opt-in. **There is no
single "the queue is now N× faster".** A deployment that is bottlenecked on acknowledgement transactions gains a
great deal from one change and nothing from the others; a deployment dominated by ordered-key contention gains
from a third and is untouched by the first two.

It also explains why figures from early prototypes were consistently optimistic: they measured raw SQL on one
connection with claim and acknowledge strictly alternating, no framework and no polling — further from a running
system again, and wrong in the same direction every time.

Two levers account for essentially everything measured:

1. **Transactions per message** — *in a raw harness*. Through the component this turned out **not** to be the
   dominant cost: removing the per-message acknowledgement transaction entirely changes throughput by ~2% (§27).
   The lever is real in isolation and largely absent in a running system.
2. **Index write amplification.** Every index is maintained on insert, claim and delete.

One refinement on the first, learned the hard way: **the gradient is not linear.** Batched acknowledgement's 16.5×
comes from putting 64 messages in one transaction. Halving transactions per message — one commit instead of two —
measures at 1.0× and buys nothing. Amortising across a batch is the mechanism; shaving a commit is not.

Anything that attacks neither turned out not to matter, and several plausible-sounding ideas were measured and
rejected on exactly that basis.

---

## What you get without changing anything

| Change | Effect | Conditions |
|---|---|---|
| **Split ordered/unordered claim queries** (`useOrderedUnorderedQuery`, now `true` by default) | **5.4× faster** claim on a backlog mixing ordered and unordered messages | Mixed traffic only. Pure-unordered 1.63×, pure-ordered 1.04× — ordered traffic needs the barrier either way |
| **Index set reduced from six to five, evidence-led** | **−28% index bytes**, paid back on every insert, claim and delete | All workloads. Two indexes were measured at *zero* scans across the whole SPI |
| **Statistics no longer collected by a database trigger** | Removes a **2.80×** penalty on the acknowledgement path | Only if you had statistics enabled — it was off by default |

The 5.4× is the largest single number here and it was already available before this work; what changed is that
the builder's default no longer disagrees with the Spring starter's, so direct builder users stop silently getting
the slow path.

## What you get by opting in

| Change | Effect | Conditions and cost |
|---|---|---|
| **Batched acknowledgement** (`setUseBatchedAcknowledgement(true)`) — ⚠️ **no longer recommended** | **~1.0× through the component**: backlog drain 1.02×, steady-state throughput 1.00× with a *worse* p99 (25 ms against 18 ms), saturation 0.91× | The **16.5×** quoted historically came from a raw-SQL harness where the per-message acknowledgement transaction was the dominant cost. Through the component it is not: the delete count drops from 40 000 to 1 268, so batching plainly works, and the time does not move (§27). It still widens the redelivery window by one flush interval. Unordered only; ordered measured 0.82×. Requires `SingleOperationTransaction` |
| **Two-table split** (`essentials.durable-queues.use-split-queue-tables=true`) | **1.07–1.36× total** for unordered traffic — and it is an *insert* feature: 1.34–1.66× on enqueue, drain at parity, ~9% fewer index bytes | Measured through the shipped component at 40 000 messages (§21–§23), not the raw-SQL prototype the often-quoted **1.38×/1.62×** came from. It reached this only after two defects were found and fixed by measurement: the composite asked each table for messages it cannot hold, and the unordered index omitted `key IS NULL` so every claim took heap fetches. **And the headline rationale — "six indexes down to one" — does not apply to unordered traffic at all**: the ordered indexes are partial on `key IS NOT NULL`, so with no ordered messages they hold 8 KB and cost nothing. The split removes two *small* indexes, 9% of the bytes (§24). **Ordered traffic is parity** (0.97×) once the table has been analysed — the 4.75× spread that previously prevented a figure was stale planner statistics, not the split. So the split is an insert-and-storage optimisation, never a drain one. ⚠️ **Requires a migration if you have a backlog** — see below |
| **Batched fetch** (`setUseBatchedFetch(true)`) | **16–64× fewer claim statements**, and **no throughput change** (1.01–1.02×) | It reduces database round trips, not time. Measured against a database on localhost, where a round trip is nearly free — so if yours is remote or database CPU is your constraint, this is worth measuring in *your* environment, and `BatchedFetchThroughputBenchmarkIT` is the harness. Set `batchedFetchSwitchThreshold` to 0 or a small deployment silently stays on per-queue fetch |
| **Ordered-message cursor** (`setUseOrderedMessageCursor(true)`) | **1.85×** at 8 keys × 2 500 messages; **1.02–1.05×** at 100–600 per key | Ordered traffic only, and the benefit is a function of how deep your per-key backlogs get — the barrier it replaces rescans a key's depth per candidate row. Experimental, and the remaining headroom is the acknowledgement rather than the claim |

## Where the remaining win is

For **ordered** traffic, the per-key cursor above is half of a design. The other half — claiming per-key *runs*
and acknowledging a whole run in one transaction — is what would let ordered traffic amortise a transaction at
all, which is the only lever that has ever mattered. It is not built.

That is also why the cursor measures at 1.85× rather than the 217× quoted from the prototype: **217× was a
claim-phase number**, and an end-to-end drain still pays one acknowledgement transaction per message. The claim is
no longer the bottleneck once you have fixed the claim.

If your ordered throughput matters, run-claiming is the change to ask for.

---

## Migrating to the two-table split

The split reads `<table>_unordered` and `<table>_ordered`, and **never the original shared table**. Switching a
deployment that has messages queued therefore does not lose them — it stops delivering them, which is worse,
because nothing reports an error.

```java
// 1. Stop the consumers on the old shared table and let in-flight handling finish.
// 2. Move the backlog. Refuses to run if anything is still marked as being delivered.
var result = splitDurableQueues.migrateFromSharedTable("durable_queues");
log.info("Moved {} messages", result.totalMessagesMoved());
// 3. Start the new consumers. Drop the old table once you are satisfied - it is emptied, not dropped.
```

Ids, delivery counts, timestamps, dead-letter state and last errors all carry over unchanged, so a
half-delivered message keeps its history and a quarantined one stays quarantined. This is straightforward only
because both split tables keep the shared table's columns exactly.

**There is no rolling upgrade.** A v1 pod and a split pod pointed at the same base name read different tables.

---

## Things that were measured and did *not* pay

Worth listing, because each is a plausible idea someone will suggest again:

| Idea | Result |
|---|---|
| Partitioning the queue table by `queue_name` | **Rejected** — 30% worse acknowledge-by-id, 40% worse claim |
| Per-table autovacuum tuning | **Inert on a default cluster** — zero autovacuums ran; cluster `autovacuum_naptime` is what binds |
| The bloat degradation autovacuum tuning was meant to fix | **Did not reproduce** — no drain degradation over 12 cycles, in any arm |
| `fillfactor=80` on the queue table | No difference, marginally worse. The cheap `ALTER TABLE` escape hatch does not exist |
| A write-once message row (no in-place claim update) | **Rejected** — 12% worse than the split; the churn moves rather than disappears |
| A separate dead-letter table | Real but **modest**: 1.0–1.2×, and no index-size win |
| Batching acknowledgement for *ordered* messages | **0.82× — worse.** Structural: the ordering barrier reads completion from a row's absence, so a buffered ack stalls the key |
| Running the message handler inside the claim transaction (one commit per message instead of two) | **Rejected.** 1.0× where connections are plentiful, **0.25×** where workers outnumber the connection pool — holding a connection across the handler caps throughput at `pool ÷ handler duration` |
| Advisory-lock claim instead of marking the row | **Rejected.** 0.68–0.79× even at its best — a candidate scan plus per-row lock attempts costs more than the write it saves |
| Batched fetch, for throughput | 1.01–1.02×. It reduces round trips (16–64× fewer claim statements), not time — see the opt-in table above |

## Claims previously made that did not survive measurement

Stated plainly because they appear in earlier design documents:

- **"The framework costs ~4% over raw SQL"** — withdrawn. The comparison was confounded (the two arms used
  different claim queries). The honest statement is that the framework is not a large multiplier; its actual
  per-message cost has not been sized.
- **"The cursor gives 4.0× / 2.64×"** — withdrawn. Measured against a prototype with two correctness faults, one
  of which lost messages. Corrected, it is 1.54× end-to-end — and the real case for it is per-key runs (above),
  not the cursor itself.
- **"The statistics trigger's `EXCEPTION` block is its most expensive part"** — withdrawn. The mechanism is real
  (one subtransaction per row) but it is 1.03× of wall clock. The cost is the `INSERT` and its indexes.
