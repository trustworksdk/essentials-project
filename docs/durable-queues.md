# Durable Queues — the reference

One document. What the queue does, what you can turn on, when it is worth turning on, and how to move between
storage layouts. Evidence for every figure is in
[durable-queues-measurements.md](durable-queues-measurements.md); upgrade notes are in
[MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md).

> **Replaces five earlier documents** — an implementation plan, a v2 design plan, a performance-improvements plan,
> a statistics-improvements plan and a user-facing summary. They disagreed with each other, because a long
> measurement exercise withdrew several of their load-bearing claims. They are in git history if the reasoning is
> ever needed; nothing live depends on them.

---

## 1. The one thing worth doing

**Make sure the queue table has planner statistics.** A freshly-loaded table with none runs the *ordered* claim
about **11× slower** — 13 517 ms against 1 184 ms for 20 000 messages. `ANALYZE` recovers it; `VACUUM` alone does
not, so this is row estimates rather than the visibility map.

It bites because a queue that fills in a burst may never be analysed in time: on a default cluster, **zero
autovacuums ran** during such a workload, since `autovacuum_naptime` is what binds. If you drain a table that was
just filled — a backlog, a replay, a migration — analyse it first.

No code change, no flag, and larger than every tuning option below.

## 2. When each option is worth enabling

Measured against a queue running normally — producers and consumers together, backlog small — **none of these
change throughput or latency**, and capacity across all of them is within 7%. They earn their keep when the queue
has *fallen behind*, which is real and important but not the normal state.

| Option | Backlog recovery | Steady state | Enable when |
|---|---|---|---|
| **Batched acknowledgement** `setUseBatchedAcknowledgement` | 1.02× | 1.00×, p99 25 ms vs 18 ms | Rarely. The **16.5×** quoted historically was a raw-SQL harness artefact — through the component the acknowledgement transaction is not the bottleneck. Widens the redelivery window by one flush interval. Unordered only; ordered measured 0.82×, actively worse |
| **Two-table split** `essentials.durable-queues.use-split-queue-tables` | ~1.1× unordered, parity ordered | parity | It is an **insert and storage** feature: 1.3–1.6× on enqueue, ~9% fewer index bytes unordered, ~29% ordered. Not a drain feature. Needs a migration — §4 |
| **Per-key cursor** `setUseOrderedMessageCursor` | **1.81×** at 8 keys × 2 500 | parity | Ordered traffic with **few keys and deep backlogs per key**, where the barrier rescans a key's depth per candidate. Statistics cannot fix that regime; this can. Experimental |
| **Batched fetch** `setUseBatchedFetch` | 1.01× | — | Never for throughput. It reduces claim statements 16–64×, which is round trips, not time — measured on localhost where a round trip is nearly free. Worth measuring yourself if your database is remote or DB CPU is the constraint. Set `batchedFetchSwitchThreshold` to 0 or a small deployment silently stays on per-queue fetch |
| **Delivery statistics** `essentials.durable-queues.enable-queue-statistics` | — | — | Collected in memory from a `DurableQueueMessageObserver`; no table, no trigger, no cost on the acknowledgement path. Figures are **per instance and reset on restart** |

Every one of these is correctly **opt-in**. Enable one because you recognise your own shape in this table, not
because a number looked large.

## 3. What is on by default, and why

- **`useOrderedUnorderedQuery = true`** — separate ordered/unordered claim queries. The unified query applies the
  per-key barrier to every candidate row including unordered ones that cannot need it: **5.4× slower** on a mixed
  backlog. Pure-ordered traffic is indifferent.
- **`orderedMessageDuplicateStrategy = REJECT`** — a unique index on `(queue_name, key, key_order) WHERE key IS NOT
  NULL`. Two `OrderedMessage`s sharing a key *and* an order never block each other, so that key's ordering silently
  does not hold. ⚠️ **Startup fails on a table that already contains duplicates**, naming them. `ALLOW` restores the
  old behaviour.
- **Centralized message fetcher**, `SingleOperationTransaction`, and an index set of five reduced from six on
  measured evidence (−28% index bytes; two indexes took zero scans across the whole SPI).

## 4. Moving to the two-table split

The split reads `<table>_unordered` and `<table>_ordered` and **never the original shared table**. Switching with
messages queued does not lose them — it stops delivering them, with nothing reporting an error.

```java
// 1. Stop the consumers on the shared table; let in-flight handling finish.
// 2. Move the backlog. Refuses to run while anything is still marked as being delivered.
var result = splitDurableQueues.migrateFromSharedTable("durable_queues");
// 3. Start the new consumers. Drop the old table when satisfied — it is emptied, not dropped.
```

Ids, delivery counts, timestamps, dead-letter state and last errors all carry over, so a half-delivered message
keeps its history and a quarantined one stays quarantined. **There is no rolling upgrade**: a v1 pod and a split pod
on the same base name read different tables.

## 5. Ideas that were measured and rejected

Each is plausible and each will be proposed again.

| Idea | Result |
|---|---|
| Partitioning by `queue_name` | 30% worse acknowledge-by-id, 40% worse claim |
| Per-table autovacuum tuning | Inert on a default cluster — zero autovacuums ran; `naptime` binds |
| `fillfactor=80` | No difference, marginally worse |
| Write-once message row | 12% worse than the split; churn moves rather than disappears |
| Separate dead-letter table | 1.0–1.2×, no index-size win |
| Handler inside the claim transaction | 1.0× where connections are plentiful, **0.25×** where workers outnumber the pool |
| Advisory-lock claim | 0.68–0.79× even at its best |
| Batching acknowledgement for *ordered* messages | 0.82× — structurally worse; the barrier reads completion from a row's absence |

## 6. Gotchas worth knowing before changing anything

- **`FullyTransactional` is broken for retries and dead-lettering** — rollback reverts the attempt count. Use
  `SingleOperationTransaction`.
- **Table names are concatenated into SQL.** `PostgresqlUtil.checkIsValidTableOrColumnName` is a first line of
  defence, not a sanitizer. Derive them from trusted sources only.
- **Ordered delivery is per-node.** Ordering across cluster nodes is not guaranteed.
- **An ack-counting `DurableQueuesInterceptor` must implement both** `intercept(AcknowledgeMessageAsHandled…)`
  **and** `intercept(AcknowledgeMessagesAsHandled…)`, or it goes blind when batching is on.
- **A redundant-looking index predicate may be load-bearing.** The split's unordered index omitted `key IS NULL`,
  which the claim filters on, so every claim took heap fetches instead of an index-only scan — 6× slower, growing
  with backlog.
- **Reuse across a boundary where the invariants differ will cost you.** The split reused v1's statements, which
  assume v1's index set, while the split's entire purpose is not to have v1's index set. That produced two separate
  regressions.
- **On the split, an interceptor registered once runs on the composite *and* on both delegates.** Any composite
  operation implemented by "try one delegate, then the other" therefore fires the chain twice for a message in the
  second table, and no correctness test can see it. Every by-id operation is now a single statement over both
  tables for exactly this reason; `PostgresqlSplitDurableQueuesByIdOperationsIT` counts statements and chain
  invocations rather than trusting the result.

## 7. Still open

### Parked pending review: how the split should be composed

**Status: parked 2026-08-25, awaiting review by a second pair of eyes. Do not extend the current structure
further until that review has happened** — the open items below are all consequences of the structure, and fixing
them one at a time entrenches it.

Every defect the split has shipped came from the same place, and it is not "reuse" as such. Two of the three came
from *reusing* v1's behaviour where the composite is not v1 (the claim scope, the interceptor double-firing); the
third came from *not* reusing it — the split re-derived its index DDL and dropped the `key IS NULL` predicate v1
had always carried. The boundary between what was reused and what was re-derived was never drawn deliberately, and
every defect to date sits exactly on that boundary.

The gate that justified composing two `PostgresqlDurableQueues` was that `PostgresqlSplitDurableQueuesIT` extends
the shared `DurableQueuesIT` unmodified. That suite passed through all three defects. It proves *semantics*, and
all three were invisible to semantics — so the strongest argument for composition bought less than it appeared to.

Two candidate ways forward:

1. **An explicit storage SPI.** A `QueueMessageStore` that is *not* a `DurableQueues` — statements and row mapping
   only, no interceptors, no consumer registry, no lifecycle, no observer, no fetcher. Whole classes of defect stop
   being representable rather than being fixed: `PostgresqlDurableQueues.Role` disappears (it exists only to make a
   v1 store act as a non-owning half), `ClaimScope` disappears (an unordered store has no ordered query to run by
   accident), the interceptor chain has exactly one owner, and the outstanding merge-operation item below falls out
   for free instead of costing eleven hand-written wrappers. The cost is the acceptance gate: the composite would
   implement the SPI itself and needs its own proof of semantics.
2. **A clean-room second attempt.** Design the two-table queue from the requirements without reading the current
   implementation, then compare. The current shape was reached by incremental composition, so it is worth knowing
   what someone would build who was not anchored to it.

Timing favours deciding sooner: `essentials.durable-queues.use-split-queue-tables` defaults to `false` and has no
operational experience behind it, so nothing is committed yet. After a first deployment the table layout and
`migrateFromSharedTable` become a compatibility contract.

### Open items

- **Six merge operations still fire the interceptor chain twice** — `getQueuedMessages`, `getDeadLetterMessages`,
  `getQueuedMessageCountsFor`, `getTotalMessagesQueuedFor`, `getTotalDeadLetterMessagesQueuedFor`, `purgeQueue`.
  The composite runs no chain and both delegates run theirs, so unlike the by-id operations this happens on
  *every* call, not only for messages in the second table. Wrapping them in a chain on the composite would make it
  fire three times; the only coherent fix is for the composite to own the chain for all 22 operations and stop
  propagating user interceptors to the delegates (delegates keep their own internal
  `SingleOperationTransactionDurableQueuesInterceptor`, so transactions are unaffected). That is roughly eleven
  more methods to wrap — or nothing at all, under option 1 above. **Blocked on the parked decision.**
- **A steady-state harness exists** ([`SteadyStateThroughputBenchmarkIT`](../components/postgresql-queue/src/test/java/dk/trustworks/essentials/components/queue/postgresql/benchmark/SteadyStateThroughputBenchmarkIT.java))
  but has only been pointed at batched acknowledgement, the split and the cursor. Anything else quoted in these
  documents is backlog-recovery behaviour.
- **Run-claiming** — claim a contiguous prefix of one key and acknowledge it in one transaction. Pays 2.25×
  single-threaded; three concurrency defects blocked it, all recorded in the measurements.
- **The split's admin statistics** — `DurableQueuesStatistics` is per-instance and in-memory, so it works across
  the split unchanged. A durable sink, if wanted, is a batched asynchronous writer fed by the same observer —
  never a trigger.
- **The cursor is not wired into the split.** It replaces the ordered claim, and the split's ordered delegate would
  need its own key-state table. They are independent opt-ins; combining them multiplies what a measurement has to
  control for.
- **The ArchUnit construction-ergonomics freeze store is uncommitted**, so that guard currently cannot fail. It
  belongs on `main`, not on this branch.
