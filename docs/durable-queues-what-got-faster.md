# Durable Queues — what got faster, and under what conditions

A summary for people **using** the queue, not building it. Every figure here is traceable to a measurement in
[durable-queues-redesign-measurements.md](durable-queues-redesign-measurements.md) or
[durable-queues-v2-design-plan.md](durable-queues-v2-design-plan.md); nothing is estimated, and the things that
did not pay are listed too, because knowing which knobs are pointless is worth as much as knowing which are not.

## Read this first: the numbers do not multiply

Each improvement below attacks a different cost, on a different workload, and several are opt-in. **There is no
single "the queue is now N× faster".** A deployment that is bottlenecked on acknowledgement transactions gains a
great deal from one change and nothing from the others; a deployment dominated by ordered-key contention gains
from a third and is untouched by the first two.

Two levers account for essentially everything measured:

1. **Transactions per message.** Overwhelmingly the dominant cost — not the SQL, the transaction around it.
2. **Index write amplification.** Every index is maintained on insert, claim and delete.

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
| **Batched acknowledgement** (`setUseBatchedAcknowledgement(true)`) | **16.5× on drain time** [10.3–24.2 across 9 repetitions] | Unordered messages only — ordered ones are never batched, and were measured **0.82×, actively worse**. Widens the redelivery window by one flush interval, which is a semantics change, which is why it is off by default. Requires `SingleOperationTransaction` |
| **Two-table split** (`essentials.durable-queues.use-split-queue-tables=true`) | **1.38× overall, 1.62× on insert** | **Unordered traffic only** — ordered traffic gets 1.07×, because its cost is the per-key barrier in the claim and the split does not touch that. Measured against raw SQL on prototype schemas, so it isolates index maintenance rather than end-to-end throughput. ⚠️ **Requires a migration if you have a backlog** — see below |
| **Batched fetch** (`setUseBatchedFetch(true)`) | Correctness under competing consumers is evidenced; **throughput is not measured** | Off by default precisely because no number justifies it yet. Do not enable it expecting a speed-up nobody has demonstrated |

## Where the big remaining win is, and why it is not built

For **ordered** traffic with many messages per key, claiming per-key *runs* instead of one message at a time
measured **217× in the deep-per-key regime**. That is by far the largest unrealised number in this investigation.
It needs a per-key cursor design that has been prototyped, found incorrect twice, and corrected — see §8–§11 of
the measurements. It is not shipped.

If your ordered throughput matters, that is the change to ask for. Nothing you can currently switch on addresses
it.

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
