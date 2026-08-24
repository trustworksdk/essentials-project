# DurableQueues redesign — measurements

> **Superseded in part — read [`durable-queues-implementation-plan.md`](durable-queues-implementation-plan.md) first.**
> It consolidates every finding and lists which conclusions here have been withdrawn.

> This is the evidence of record: every measurement, sections 1-10, including the ones that withdrew earlier
> conclusions. The plan built on it is in the consolidated document.

Evidence gathered ahead of the proposed new `DurableQueues` implementation that splits ordered and unordered
messages into two tables behind a delivery-mode-aware consumer API. Two levers were measured, plus two
defects found on the way.

Harness: `examples/essentials-performance-lab`, scenario `queue-design-ab`
(`QueueDesignAbScenario` + `QueueDesignAbBenchmarkIT`). Nothing in `components/` was changed.

Environment: Temurin 25.0.4 (aarch64), 14 processors, PostgreSQL 17.5 in Testcontainers, Hikari pool 100,
32 parallel consumers, 4000 messages per case, 64 ordered keys, **no artificial handler delay** — the
handler does nothing, so what is being measured is the queue's own per-message cost. Medians of 5
repetitions, ranges in brackets, arms alternating within each repetition. Absolute numbers are host-specific;
the ratios are the result.

## Summary

| Finding | Result | Status |
|---|---|---|
| Split fetch query on a **mixed** backlog | **5.4× faster** — and already implemented behind a flag | — |
| `useOrderedUnorderedQuery` default is inconsistent | Starter says `true`, builder says `false` — direct builder users get the slow path | **Fixed** on `queue_fix` |
| `queueMessages` with a mixed ordered/unordered list | **Throws `ClassCastException`** — untested path, reachable from the public API | **Fixed** on `queue_fix` |
| Ack batching, unordered | 1.13× — far smaller than predicted | Open |
| Ack batching, ordered | **0.82× — actively worse**, and the reason is structural | Open |
| Transaction granularity, per-message vs per-batch | **134× on drain time** [93–182] — this is the whole cost | §7 |
| Acknowledgement granularity alone | **16.5×** [10.3–24.2] — the half production still pays | §7 |
| Framework overhead over raw SQL | **Not established** — the comparison is confounded, see §7 | §7 |
| Consequence for the cursor's 2.64× | **Collapses to ≈1.2× unless acknowledgement is batched first** | §7 |
| The measured cursor prototype | **Not correct** — two faults, both reproduced against the SQL | §8 |
| Cursor's win once corrected | End-to-end **2.38× → 1.54×**, claim **3.75× → 2.18×** | §8 |
| Why the cursor is still worth building | **Corrected in §10** — not deferred acks; per-key *runs* | §10 |
| The gate: does run-claiming pay? | **Yes, decisively — and the bigger win is the claim itself at low key counts** | §11 |
| Dead-letter side table | Real but **modest**: 1.0–1.2×, and no index-size win | §12 |
| Partitioning by `queue_name` | **Rejected** — 30% worse acknowledge-by-id, 40% worse claim | §12 |
| Per-table autovacuum settings | **Inert on a default cluster** — zero autovacuums ran, naptime is what binds | §13 |
| The bloat degradation they were meant to fix | **Did not reproduce** — no drain degradation over 12 cycles, any arm | §13 |
| Enabling delivery statistics | **2.80× on the acknowledgement path** | §14 |
| The statistics trigger's `EXCEPTION` block | Mechanism confirmed (one subtransaction per row) but **only 1.03× wall clock, zero SLRU writes** | §14 |
| The proposed Java-side observer | **1.34× better than the trigger** — worth building, but the write itself is the cost | §14 |
| B1, the write-once message row | **Rejected** — 12% worse than the split; churn moves rather than disappears | §15 |
| B3, the index diet | **Pays, and shipped** — index set reduced, 28% fewer index bytes | §16, §17 |
| Mixed-version rollout (barrier pods + cursor pods, one table) | **Safe** — 27 consecutive runs, negative control fires | §9 |
| A key with no cursor row | **Silently invisible to cursor pods** — never claimed, no error | §9 |
| Recovery | Reconcile-on-empty-claim drains it, and cannot rewind a live cursor | §9 |

## 1. Ordered/unordered query split — confirmed, and already shipped behind a flag

`PostgresqlDurableQueues` has two fetch strategies, selected by `useOrderedUnorderedQuery`:

- **`false`** — one unified query (`DurableQueuesSql.buildGetNextMessageReadyForDeliverySqlStatement`) that
  applies the ordered per-key barrier to *every* candidate row and sorts by `key_order, next_delivery_ts`.
  Unordered rows have `key IS NULL` and `key_order = -1`, so for them the correlated `NOT EXISTS` is
  vacuously true and the `key_order` sort key is a constant — but both still execute.
- **`true`** — separate `buildUnorderedSqlStatement` / `buildOrderedSqlStatement` CTEs, plus the matching
  partial indexes (`idx_*_unordered_ready`, `idx_*_ordered_ready`, `idx_*_ordered_head`).

Throughput, immediate-ack arm, messages/second:

| Ordered fraction | `useOrderedUnorderedQuery=true` | `=false` | Ratio |
|---|---|---|---|
| 0% (all unordered) | 394.5 [350–402] | 242.6 [67–379] | 1.63× |
| 50% (mixed) | 359.3 [224–386] | **66.2 [62–71]** | **5.42×** |
| 100% (all ordered) | 355.7 [348–361] | 342.9 [324–363] | 1.04× |

The shape is exactly what the query text predicts. Pure-ordered is indifferent — it needs the barrier
anyway. Pure-unordered gains, though with a spread wide enough that 1.63× should be treated as
"substantially better" rather than a precise figure. **Mixed traffic is where the unified query collapses**:
the unordered majority pays a correlated self-join and a pointless sort on every poll.

**The two-table split's core performance argument is therefore already realised by an existing flag.** A new
implementation should be justified on API and correctness grounds — a delivery-mode-aware consumer API,
per-table index sets, independent partitioning and retention — not on this speed-up, which is available now.

### Defect: the flag's default disagrees between the two construction paths

- `EssentialsComponentsProperties.DurableQueues.useOrderedUnorderedQuery` = **`true`**
- `PostgresqlDurableQueuesBuilder.useOrderedUnorderedQuery` = **`false`** (uninitialised `boolean` field)

Spring applications get the fast path. Anyone constructing `PostgresqlDurableQueues` through the builder or
the constructors — the documented non-Spring path — silently gets the query that is up to 5.4× slower on
mixed traffic. `components/postgresql-queue/CLAUDE.md` states "Off by default", which is true of the builder
and false of the starter.

Flipping the builder default to `true` is a one-line change and is the highest value-per-risk item in this
document. It is a behaviour change for direct-builder users, so it belongs with the other converged-defaults
notes in `docs/MIGRATION-NEXT_MAJOR.md`.

**Done** on branch `queue_fix` — see §4.

## 2. Ack batching — a real but modest win, and harmful for ordered messages

Today each handled message issues its own `DELETE FROM durable_queues WHERE id = :id` in its own transaction
(`SingleOperationTransaction` is the default). Because the batch fetch amortises over a whole fetcher tick,
that delete is *the* per-message commit.

The `BATCHED` arm defers acks and flushes them as one `DELETE ... WHERE id IN (...)` every 50 ms or every 200
ids, via `BatchingAcknowledgeInterceptor` — a prototype in the perf-lab, not a proposal to ship as an
interceptor. It reduced 4000 individual deletes to ~156 batched ones, a **25× reduction in statements**.

Throughput, `useOrderedUnorderedQuery=true`:

| Ordered fraction | Immediate ack | Batched ack | Ratio | Ranges overlap |
|---|---|---|---|---|
| 0% | 394.5 [350–402] | 444.7 [430–533] | 1.13× | no |
| 50% | 359.3 [224–386] | 388.0 [248–409] | 1.08× | yes |
| 100% | 355.7 [348–361] | 290.1 [80–312] | **0.82×** | no |

**This contradicts the prediction that ack batching would dominate.** 25× fewer statements bought 13%. Two
things explain it, and both matter more than the number.

### 2a. The statement is not the cost — the operation is

The prototype can only remove the `DELETE` and its WAL-writing commit. It cannot remove the connection
acquisition, because `PostgresqlDurableQueues.acknowledgeMessageAsHandled` wraps the interceptor chain in
`unitOfWorkFactory.withUnitOfWork(...)` — the unit of work, and therefore the Hikari borrow, happens before
any interceptor is consulted. So 1.13× is a **lower bound** on what real batching would give.

But the direction of the evidence is clear: eliminating 96% of the delete statements while keeping the
per-message unit of work moved throughput 13%. The per-message *operation* — connection acquire, begin,
commit, release — is the expensive part, not the SQL inside it. A redesign that batches the statement but
keeps a unit of work per message will not pay off.

### 2b. The ordered barrier uses row deletion as its completion signal

The ordered barrier, in both fetch strategies, is:

```sql
NOT EXISTS (SELECT 1 FROM q2 WHERE q2.key = q1.key AND q2.queue_name = q1.queue_name AND q2.key_order < q1.key_order)
```

There is **no predicate on `q2`'s state** — not `is_being_delivered`, not `is_dead_letter_message`, nothing.
Any physically present row with a lower `key_order` blocks its successors. The only thing that unblocks the
next message for a key is the predecessor's row being *deleted*.

So deferring the delete stalls the whole key until the next flush. That is the 0.82×, and it is also why
that arm's range is 80–312 — the stall interacts with flush timing. It is a structural property of the
schema, not a tuning problem: **ordered delivery currently cannot batch acks at all.**

This is the most useful design conclusion here. A redesign should decouple "message handled" from "row
deleted" — an explicit per-key cursor or head table (`queue_name, key, next_key_order`) makes completion an
update to one small row instead of a deletion the barrier subquery infers from. That would:

- let ordered messages batch acks like unordered ones,
- turn the correlated `NOT EXISTS` into a direct index lookup,
- and give cross-node per-key exclusivity, which today lives in the per-JVM `inProcessOrderedKeys` set and is
  why `OrderedMessage` ordering is documented as not guaranteed across cluster nodes.

The last point is a correctness improvement, not a performance one, and is a stronger argument for the new
implementation than any figure in this document.

## 3. Defect: `queueMessages` cannot take a mixed ordered/unordered list

Found while building the mixed-fraction case. Queueing a list containing both `OrderedMessage` and plain
`Message` instances throws:

```
java.lang.ClassCastException: class org.jdbi.v3.core.argument.NullArgument cannot be cast to class java.lang.String
    at org.jdbi.v3.core.argument.internal.strategies.LoggableBinderArgument.apply
    at org.jdbi.v3.core.statement.PreparedBatch.execute
    at PostgresqlDurableQueues.lambda$queueMessages$1(PostgresqlDurableQueues.java:1096)
```

The per-row binding in `PostgresqlDurableQueues.queueMessages` is written correctly — `bind("key", String)`
for an ordered message, `bindNull("key", Types.VARCHAR)` otherwise. The problem is JDBI's `PreparedBatch`,
which prepares one binder from the first row's argument types and reuses it for every subsequent row; a
`NullArgument` then arrives where the prepared binder expects a `String`.

It has no test coverage because every existing test that uses both kinds already splits them into separate
calls — see `PostgresqlDurableQueuesPerformanceIT` (lines ~199–205) and `PostgresqlDurableQueuesLatencyIT`
(lines ~298–304), both of which queue unordered and ordered chunks separately.

`QueueDesignAbScenario` works around it by enqueueing in consecutive homogeneous runs — that workaround is
still in place, since the scenario must also run against older versions.

**Fixed** on branch `queue_fix`: the key is now bound with `bindByType("key", value, String.class)` in both
branches, so the prepared binder sees one argument type whether or not the value is null. Pinned by
`PostgresqlDurableQueuesMixedBatchIT`, which covers both orderings — see §4.

## 4. Recommended sequence

| # | Action | Basis | Risk | Status |
|---|---|---|---|---|
| 1 | Flip `PostgresqlDurableQueuesBuilder.useOrderedUnorderedQuery` default to `true` | §1 — up to 5.4× on mixed traffic, and removes a Spring-vs-builder divergence | One line; behaviour change for direct-builder users | **Done** — branch `queue_fix` |
| 2 | Fix `queueMessages` mixed-batch binding, with a regression test | §3 — public API throws | Low | **Done** — branch `queue_fix` |
| 3 | ~~Add a state predicate to the ordered barrier~~, or replace it with a per-key cursor | §2b — the blocker for batched ordered acks and for cross-node ordering | **High** — see §5 | Open |
| 4 | Batch acknowledgement at a level that removes the per-message unit of work | §2a — the operation is the cost, not the statement | **Medium-high** — see §5 | Open |
| 5 | New two-table implementation | API, per-table indexes, partitioning, retention — **not** raw fetch speed, which (1) already delivers | Large | Open |

### What items 1 and 2 changed

- `PostgresqlDurableQueues.DEFAULT_USE_ORDERED_UNORDERED_QUERY = true`, used by both the builder field and
  the constructor that previously passed a bare `true` literal — one named constant now feeds every path.
- `PostgresqlDurableQueues.isUseOrderedUnorderedQuery()` added, so the setting is readable at runtime. It was
  invisible before, which is how the two defaults diverged unnoticed; the perf-lab now reports the value it
  actually got rather than the one it was told to configure.
- `CentralizedFetcherDurableQueueIT` now sets the flag to `false` explicitly. It relied on the old builder
  default for its half of a deliberate `false`/`true` pair, so the flip would otherwise have silently made it
  a duplicate of `CentralizedFetcherDurableQueueIT_WithOrderedUnordered` and dropped all coverage of the
  unified query.
- `queueMessages` binds the ordered key with `bindByType(..., String.class)` in both branches instead of
  `bind`/`bindNull`, so JDBI's prepared batch binder sees one argument type per row.
- `PostgresqlDurableQueuesMixedBatchIT` covers both orderings. Worth noting the observed asymmetry: only a
  batch whose **first** message is ordered failed. Unordered-first already worked and — checked explicitly in
  the test — did not silently null out the later ordered messages' keys.

## 5. Risk assessment for items 3 and 4 against the current design

### 5.1 Correction: the barrier's missing state predicate is load-bearing

Item 3 was originally worded as "add a state predicate to the ordered barrier". **That option should be
struck.** Working through what each predecessor state means shows there is no state that can safely be
excluded:

| Predecessor row state | Must it block its successors? | Why |
|---|---|---|
| Ready (`next_delivery_ts <= now`) | Yes | It is next in line |
| In flight (`is_being_delivered = TRUE`) | Yes | Concurrent delivery of two messages for one key is the thing ordering forbids |
| Awaiting retry (`next_delivery_ts` in the future) | Yes | Otherwise a successor overtakes a predecessor that is still failing |
| Dead-lettered (`is_dead_letter_message = TRUE`) | Yes | Ordering past a poisoned message is undefined — and the enqueue path already depends on this: `getQueueMessageSqlOptimized` marks a newly queued ordered message dead-on-arrival when a lower-order sibling is already dead-lettered |

Adding any state predicate silently downgrades ordered delivery to "mostly ordered". The
`is_dead_letter_message = FALSE` variant is the most tempting and the most damaging: successors would stream
past a poisoned message, contradicting the enqueue-side barrier above. Note also that the fetch-side blocking
behaviour for each of those four states does not appear to be pinned by a test, so such a change could pass
CI.

**Cheapest useful next step, whatever is eventually built:** add tests that pin all four blocking states.
They are the invariants any redesign must preserve, and they look under-covered today.

### 5.2 Item 3 as a per-key cursor — high risk

Only the cursor variant is viable, and it is redesign-scale rather than a retrofit:

- **Gaps are legal, and the current barrier is gap-tolerant by construction.** `key_order` has no unique
  index and no contiguity requirement — `OrderedMessage.of(payload, key, order)` accepts any `long`. "Nothing
  lower exists" copes with arbitrary gaps; a "next expected order" cursor wedges on the first one forever.
  Gaps arise from sparse user-chosen orders, `deleteMessage`, `purgeQueue`, and admin dead-letter deletion. A
  correct cursor must therefore track *highest completed*, not *next expected* — a subtler design than it
  first appears.
- **Duplicate `(queue_name, key, key_order)` is currently possible.** With no unique constraint, strict `<`
  lets two same-order siblings through concurrently today. That is already a latent ordering hole; a cursor
  turns it from wrong into ambiguous.
- **It introduces a second source of truth** that must be maintained atomically across acknowledge, retry,
  dead-letter, resurrect, delete, purge, and the stuck-message reset. Miss one path and a key wedges
  permanently — which surfaces as "the queue silently stopped for one customer", the worst failure shape
  available. Note `getResetMessagesStuckBeingDeliveredSql` has no `queue_name` filter at all; it resets
  globally, so it is easy to overlook when reasoning per-queue.
- **Migration is not free.** Existing tables hold live ordered messages, the cursor must be back-filled, and
  there is no version negotiation between nodes — so a rolling deploy has old and new consumers running
  against the same table simultaneously. That implies either downtime or a dual-write phase.
- **Two consumer implementations plus a second backend.** `CentralizedMessageFetcher` and
  `DefaultDurableQueueConsumer` both need it, and the Mongo implementation orders differently, so the
  SPI-level guarantee changes shape per backend.

The payoff is real — it is the only route to cross-node ordering, which today rests on the per-JVM
`inProcessOrderedKeys` set — but note the *performance* payoff is unmeasured: turning the correlated
`NOT EXISTS` into a lookup matters most at table sizes this exercise never reached (§6).

**Recommendation: do not retrofit this into the current schema.** Fold it into item 5, where the schema can
be changed properly, rather than paying the migration risk without the design freedom.

### 5.3 Item 4 as batched acknowledgement — medium-high risk, and it depends on item 3

- **The duplicate-delivery window widens, governed by a global timer.**
  `resetMessagesStuckBeingDelivered` resets any row with
  `is_being_delivered = TRUE AND delivery_ts <= now - messageHandlingTimeout` (30 s by default), with no
  `queue_name` filter. Today the ack lands milliseconds after the handler returns. With batching, a
  handled-but-unflushed row sits in exactly that state for the flush interval plus any flush backlog, flush
  failure, or GC pause. Exceed the timeout and successfully-handled messages are redelivered. At-least-once
  permits it; handlers that are not genuinely idempotent will nonetheless start breaking. This makes
  correctness *tuning-coupled*, a new failure class for this component — and the same family as the
  duplicate-consumption bug already documented in `CentralizedMessageFetcher.calculateAvailableWorkerSlotsPerQueue`.
- **A crash multiplies redeliveries by the batch size.** An in-memory buffer lost to JVM death redelivers
  every message in it — 200 instead of ~1, at the default batch size used in these runs.
- **It is measurably harmful for ordered messages today**: 0.82×, for the reason in §2b. So item 4 is either
  gated behind item 3, or the ack path must become delivery-mode-aware and batch only unordered messages.
  Either way, **4 depends on 3**, and that dependency was not obvious before measuring.
- **`FullyTransactional` mode must opt out** — there the ack participates in the caller's unit of work, and
  deferring it breaks that coupling outright. Manageable, since that mode is already documented as broken for
  retries and dead-lettering, but it is another mode-conditional branch.
- **Admin and statistics views skew.** `getTotalMessagesQueuedFor` counts every non-dead-letter row
  regardless of `is_being_delivered`, so handled messages appear as still queued for the flush window; any
  alerting tuned on those numbers needs revisiting.
- **The real win needs an API change, not an interceptor.** §2a's 1.13× is a lower bound precisely because
  `acknowledgeMessageAsHandled` wraps the interceptor chain in a unit of work. Capturing the rest means
  moving the ack out of that per-message unit of work — a change to the `DurableQueues` contract or the
  consumer call site, and therefore subject to the stable-API rule.

### 5.4 Summary

| Item | Risk | Blast radius if wrong | Reversible |
|---|---|---|---|
| 3 (per-key cursor) | **High** | Silent ordering violation, or a permanently wedged key | Poorly — schema plus migration |
| 4 (batched ack) | **Medium-high** | Duplicate delivery under load, pause, or crash | Yes — feature flag, default off |

Neither is a small change against the current design. If one is to be attempted first, item 4 is the safer
bet, scoped to unordered messages only, behind a flag defaulting to off, with the flush interval validated
against `messageHandlingTimeout` at construction so a misconfiguration fails fast rather than producing
duplicates in production. Item 3 belongs with item 5.

## 6. What has not been measured

- **Table size.** Every figure above is at 4000 rows. The correlated `NOT EXISTS` and the index-versus-seqscan
  crossover are exactly the things that change with table size, so §1's ratios should be re-taken at 10⁵–10⁶
  rows before the redesign is sized. `EXPLAIN (ANALYZE, BUFFERS)` on both fetch queries at that scale is the
  obvious next step and was not run.
- **Enqueue-side cost.** The drain clock starts after enqueueing completes; producer throughput and the
  `id TEXT PRIMARY KEY` random-insertion cost are untouched by these runs.
- **Real batched acks.** Only the interceptor-level lower bound was measured — see §2a.
- **Multi-node.** Single JVM throughout, so nothing here speaks to competing consumers across pods.

## 7. Transaction granularity — the per-message cost, and what it does to the cursor result

Harness: scenario `queue-framework-overhead` (`QueueFrameworkOverheadScenario` +
`QueueFrameworkOverheadBenchmarkIT`). 20 000 unordered messages, claim batch 500, PostgreSQL 17.5 in
Testcontainers, Temurin 25 (aarch64). Every case gets a freshly created table that is dropped afterwards.

**Read the history of this section as part of the result.** It was first taken at 3 repetitions with each arm
running its repetitions consecutively, and two of its five ratios did not survive being re-taken at 9
repetitions with the arms interleaved. Interleaving matters because the dominant noise source is autovacuum
working through the dead tuples a drain produces, which is time-correlated: consecutive repetitions of one
arm share whatever background state existed during that stretch, so a slow patch lands entirely on one arm
and reads as a property of the arm. The numbers below are the 9-repetition interleaved run; where they
replace an earlier figure, the earlier figure is named so it is not quoted from an older draft.

§2a concluded that the per-message *operation* dominates the statement, and the v2 discussion had since
treated "framework overhead" as the thing that would dilute the prototype ratios. The first part is
confirmed and quantified. The second is **not established**, and the attempt to measure it is reported here
as a confound rather than a number.

### What the arms measure

Each arm issues the same SQL against the same schema and differs only in how many transactions those
statements are spread across. Drain = claim + ack, 20 000 messages, medians of 9 with the observed range.

| Arm | Claim | Ack | Drain (median) | Drain range | Spread | Transactions/msg |
|---|---|---|---|---|---|---|
| `RAW_BATCHED` — batch claim, batch ack (the write-cost prototype's shape) | 274 ms | 71 ms | **345 ms** | 278–481 | 1.73× | 0.004 |
| `RAW_BATCH_CLAIM_SINGLE_ACK` — batch claim, ack per message (today's real shape) | 278 ms | 5 378 ms | **5 687 ms** | 4 952–6 721 | 1.36× | 1.002 |
| `RAW_SINGLE` — claim per message, ack per message | 39 862 ms | 6 468 ms | **46 148 ms** | 44 674–50 558 | 1.13× | 2.000 |
| `COMPONENT_SHARED_UOW` — real component, one unit of work per claim batch | 36 595 ms | 1 565 ms | **38 145 ms** | 13 925–41 625 | 2.99× | 0.002 |
| `COMPONENT` — real component, no outer unit of work (production shape) | 23 951 ms | 6 116 ms | **30 067 ms** | 27 532–53 499 | 1.94× | 2.000 |

### The results that hold

The first three arms share a schema and a claim statement, so their ratios name transaction granularity and
nothing else. Both are far outside their own spread.

| Ratio | Median | Best case | Worst case | What it isolates |
|---|---|---|---|---|
| `ackTransactionGranularity` | **16.5×** | 10.3× | 24.2× | One transaction per acknowledgement instead of one per batch (was 14.9× at 3 reps) |
| `fullTransactionGranularity` | **134×** | 92.9× | 182× | Two transactions per message instead of two per batch (was 118×) |
| `prototypeUpperBoundDeflator` | **87×** | 57× | 192× | The prototype's transaction shape against a fully per-message one (was 123×) |

**Transaction granularity is the entire per-message cost**, and the claim is the expensive half: 39.9 s of
`RAW_SINGLE`'s 46.1 s is claiming, against 6.5 s acknowledging. A per-message claim costs ~2 ms where a
batched one costs ~14 µs per message. Two effects compound — the round trip and transaction itself, and the
dead tuples each claim-update and ack-delete leave for the next claim's index scan to walk.

**Today's production shape already avoids the worse half.** The centralized fetcher batch-claims, so real
deployments sit at `RAW_BATCH_CLAIM_SINGLE_ACK`: 5 687 ms, ~8× better than fully per-message but still
**16.5× worse than batched acknowledgement**. That 16.5× is the headroom batched acknowledgement is worth —
an order of magnitude more than the 1.13× §2a measured, which was bounded by the prototype's inability to
remove the unit of work rather than by the mechanism.

### The result that does not hold, and the retraction

The 3-repetition run reported **1.04×** for `frameworkOverheadAtEqualGranularity` and the conclusion "the
framework costs 4%" was drawn from it. **That is withdrawn.** At 9 repetitions the same ratio reads 0.65×
[0.54–1.20] — the real component appearing *cheaper* than hand-written SQL doing the same work, which is the
signature of a confound rather than a finding.

The confound: the component claims through its **split unordered query and partial covering index**, the very
path §1 measured at 1.63–5.4× faster, while the raw arms use the v1 six-index claim. The comparison therefore
varies the claim query as well as the framework. What can honestly be said is only that the two families land
within noise of each other — the framework is not a large multiplier — and **not** that it costs 4%. Sizing
the framework's own per-message cost needs an arm whose raw SQL reproduces the component's claim query, which
has not been built.

`componentTransactionGranularity` is likewise **inconclusive**: 0.79× [0.66–3.84], bounds straddling 1 in both
directions. Its shared-UoW arm holds one transaction across a claim batch while still claiming one message at
a time, which re-creates a milder version of the xmin-pinning artefact described below; its 2.99× spread is
the widest of any arm.

### What this does to the cursor result

The cursor attacks the claim phase, which production already batches, so its claim win should largely survive.
But its *total* is diluted by an acknowledgement cost it does not touch and that production pays at 16.5× the
prototype's rate. Taking the design plan's ordered arms and inflating only the ack column:

| Ordered workload | Insert | Claim | Ack (prototype) | Ack (×16.5) | Total at production ack cost |
|---|---|---|---|---|---|
| v1 barrier | 4 175 ms | 10 647 ms | 702 ms | 11 583 ms | 26 405 ms |
| cursor | 2 240 ms | 2 646 ms | 1 023 ms | 16 880 ms | 21 766 ms |

**2.64× becomes ≈1.21×.** And the cursor's 46%-more-expensive acknowledgement — a good trade against eight
seconds of claim time in the prototype — is amplified by the same factor and becomes the dominant term.

This reorders the plan. Batched acknowledgement is not an independent smaller win to be sequenced after the
cursor: **it is a precondition for the cursor to pay off at all.** With acks batched the cursor's 2.64×
largely stands; without them a 4.0× claim-phase improvement arrives as roughly 1.2×, which does not justify
the correctness surface a per-key cursor introduces. The tightened numbers strengthen this conclusion rather
than weakening it (1.26× at 3 reps, 1.21× at 9).

### Caveats that bound §7

- **The per-message arms are noisy and the component arms noisier** (spreads of 1.13–2.99×), dominated by
  autovacuum timing against the dead tuples the drain itself produces. Ratios are reported with best and
  worst case for exactly this reason; a ratio whose bounds straddle 1 is not a result, and two of the five
  do.
- **Three harness artefacts were found and fixed while measuring**, each of which had inflated the component
  arms in an earlier run. (1) A single unit of work spanning the whole drain pins the xmin horizon and blocks
  reclamation, making that arm degrade 5.7× across three identical repetitions; it now holds one per claim
  batch. (2) The component arms originally used the shared `DurableQueues` bean, whose table outlives a case,
  so accumulated dead tuples landed on whichever case ran later; each component case now builds its own
  instance on its own table. (3) Arms originally ran their repetitions consecutively; they now interleave.
- **`unitsOfWorkPerMessage` is counted client-side, not read from the server.** Differencing
  `pg_stat_database.xact_commit` was tried first and produced values the call pattern cannot produce (0.0 for
  three arms, 6.56 where only 2.0 is possible) because PostgreSQL flushes backend statistics asynchronously.
- **Single-threaded, unordered, one node.** No consumer threading, no ordered barrier, no competing consumers.
  The transaction tax is a per-message constant so none of those should change it, but none were run.

## 8. The cursor prototype is not correct, and what correctness costs it

The cursor arm produced the largest number in this investigation — 4.0× on the ordered claim, 2.64× end to
end — and the v2 design plan makes it the load-bearing change. Before implementing it, its SQL was read
carefully and then tested. Both faults below are reproduced in
`examples/essentials-performance-lab/.../QueueCursorCorrectnessIT`, as a passing case for the corrected
statement and a passing case demonstrating the defect in the measured one, on the same fixture.

### Fault 1 — the claim releases a successor while its predecessor is in flight

`claimOrderedViaCursorSql` filters `is_being_delivered = FALSE` **inside** the per-key `LATERAL` lookup. While
order 5 is in flight the cursor is still 4 and order 5 is excluded by the filter, so the lookup returns order
**6**. Two worker threads on one node are enough to reach it; it is not a multi-node concern. The statement's
own javadoc asserted the opposite ("while that row is in flight the key yields nothing"), which is not what
the SQL does. The harness never exposed it because it is single-connection with claim and acknowledge strictly
alternating, so nothing is ever in flight when a claim runs.

### Fault 2 — the acknowledgement can skip a message permanently

`ackOrderedViaCursorSql` sets `completed_through = MAX(key_order)` over the batch. With orders 5 and 7 handled
and 6 dead-lettered, the cursor jumps to 7, and because the claim only looks *above* the cursor, order 6
becomes unreachable — resurrecting it does nothing. That is message loss, not reordering, and it is a property
the `NOT EXISTS` barrier has for free: a dead-lettered predecessor simply keeps blocking. The update was also
unguarded, so a late acknowledgement could move a cursor backwards.

### What the fixes cost — ORDERED, 50 000 messages, 1 000 keys, medians of 3

| Arm | Insert | Claim | Ack | Total | Index bytes |
|---|---|---|---|---|---|
| v1 barrier | 910 ms | 2 084 ms | 156 ms | **3 150 ms** | 25.8 MB |
| split ordered table | 675 ms | 1 921 ms | 154 ms | **2 750 ms** | 21.0 MB |
| cursor, as measured (incorrect) | 513 ms | 555 ms | 257 ms | **1 325 ms** | 10.8 MB |
| cursor, corrected | 609 ms | 955 ms | 475 ms | **2 039 ms** | 14.0 MB |

Correctness costs the claim +72%, the acknowledgement +85%, and 30% more index bytes. Against the barrier the
cursor goes from **2.38× to 1.54×** end to end, and its claim from **3.75× to 2.18×**.

Three attempts were needed to get there, and each failure was informative rather than incidental:

1. **A per-key `NOT EXISTS` over in-flight rows** for fault 1 — stateless, so nothing leaks if a process dies
   and the existing stuck-message reset restores eligibility for free. This avoids the lease, expiry and
   fence-token machinery of the design plan's §8 entirely, and it is cheap **provided** a partial index over
   in-flight rows exists. The cursor table's only index is partial on `NOT is_being_delivered`, which by
   construction excludes exactly the rows the check needs; without the extra index the arm ran over fifteen
   minutes against three seconds.
2. **Clamping the cursor advance** for fault 2, first written as `id NOT IN (<ids>)` over every row of the key.
   That is quadratic in batch size — 128s against 250ms. Bounding the scan to the open interval between the
   cursor and the acknowledged order fixed the shape.
3. **The interval scan cannot use a partial index at all**, because it must deliberately see rows the claim
   cannot take. It therefore needs a **non-partial** `(queue_name, key, key_order)` index — the very index the
   barrier needs and the cursor design claimed to delete. Without it, 116s against 475ms, identically across
   three repetitions.

So gap-safety puts back one of the indexes the cursor was going to remove. The cursor still holds 14.0 MB
against the barrier's 25.8 MB, but the "one secondary index instead of three" claim does not survive.

One constraint turned out **not** to be needed. Acknowledging several of a key's messages in one statement
degrades *conservatively* — the interval scan reads the pre-`DELETE` snapshot, so the rows being acknowledged
still count as present and pull the clamp down. The cursor under-advances by a step; nothing is skipped, and
the key resumes normally. An ordered batching implementation is therefore free to group by key or not.

### Why the cursor is still worth building — but not for the stated reason

Storage-only, the corrected cursor is 1.54× against the barrier, which on its own would not justify the
migration. The case rests on §7 instead: ordered acknowledgement **cannot be batched under the barrier at all**
(§2b — deferring it stalls the key, measured 0.82×), while the cursor records completion explicitly and can.
Comparing each design at the acknowledgement cost it can actually achieve, and inflating the per-message
acknowledgement by §7's measured 16.5×:

| Ordered workload | Insert | Claim | Ack | Total |
|---|---|---|---|---|
| barrier, acks necessarily per-message | 910 ms | 2 084 ms | 156 × 16.5 = 2 574 ms | **5 568 ms** |
| corrected cursor, acks batched | 609 ms | 955 ms | 475 ms | **2 039 ms** |

**≈2.73×.** The cursor's value is that it makes ordered acknowledgement batching possible; its faster claim is
a secondary benefit worth about 2.2×, not the 4.0× on record. That is a different justification from the one
in the design plan, and it should be the one carried forward — it also means the cursor and batched
acknowledgement are a single change with a single payoff, not two independent ones.

### Caveats

- **50 000 messages, not 200 000.** The published cursor figures are at 200k; the full matrix with five
  ordered arms exceeded a 25-minute budget twice, so this run is internally like-for-like at 50k but its
  absolute numbers are not comparable to §1–§6. The ratios are the result, and the three repetitions agree
  closely.
- **Storage-only, single-connection.** Same harness limitation as the rest of the write-cost work: no
  consumers, no interceptors, no unit of work per message, and therefore no concurrency. Fault 1 is a
  concurrency fault, so it is worth restating that this harness cannot detect that class of defect at all —
  it was found by reading and confirmed by a dedicated test.
- **The corrected statements are prototype SQL, not an implementation.** Nothing here addresses the key-state
  table's migration, backfill, or the mixed-version rollout in which some pods claim via the barrier and
  others via the cursor.

## 9. Mixed-version rollout — barrier pods and cursor pods can share one table

The largest obstacle to the cursor was never its throughput; it was deployment. A rolling deploy replaces pods
one at a time, so for a period both claim styles are live against one shared table. If they cannot coexist the
cursor needs a flag-day migration — every consumer stopped, key-state backfilled, everything restarted — which
for an intra-service queue means downtime.

`QueueCursorMixedRolloutIT` runs exactly that: one pod claiming through the `NOT EXISTS` barrier and
acknowledging with a plain delete, knowing nothing about the key-state table, and one pod claiming through the
corrected cursor and maintaining it, both against the same table, each on its own connection, claiming one
message at a time so they genuinely interleave. It asserts per key that handlings are strictly increasing in
`key_order`, never overlap in wall-clock time, and that nothing is duplicated or lost — plus that each pod
handled at least a tenth of the backlog, since a run where one pod does 199 of 200 would satisfy a
presence-only check while exercising nothing.

**Result: safe.** 27 consecutive runs, zero violations (15 before a balance assertion was added, 12 after).

The reason it works is that both mechanisms read the same physical rows:

- The barrier blocks a successor while any lower-`key_order` row is still present — which includes a row the
  cursor pod is holding in flight.
- The corrected cursor blocks a key while any of its rows has `is_being_delivered = TRUE` — which includes a
  row the barrier pod is holding.
- The only unshared state is `completed_through`, and a barrier pod deletes rows without advancing it. That can
  only leave the cursor **stale-low**, and "lowest order above the cursor" is gap-tolerant by construction, so
  a stale-low cursor costs a wasted index probe rather than correctness.

A negative control pairs a barrier pod with the **uncorrected** cursor claim and requires violations to appear.
It finds them on every run, so the ordering-and-overlap detector demonstrably fires and the 27 green runs are
not an artefact of assertions that cannot fail.

### What this buys, and what it does not

It removes the flag-day. The cursor can be rolled out pod by pod, and a rollback is equally safe — a
barrier-only fleet simply ignores a key-state table that has stopped being maintained, and the next cursor pod
to appear finds it stale-low, which is the benign direction.

### The stranding hazard, and the recovery that closes it

The sharp edge of driving the claim *from* the key-state table: **a key with messages but no cursor row is
invisible to every cursor pod.** Not delayed, not dead-lettered — never claimed, with no error anywhere.

This is what a rolling deploy walks into. An old pod enqueues ordered messages without creating cursor rows;
while any barrier pod survives those keys still get handled, so nothing looks wrong; once the fleet is fully
migrated they are stranded. **A backfill run before the deploy cannot close the window, because the window is
the deploy.**

Both the hazard and its recovery are now tested:

| Case | Result |
|---|---|
| Key with messages, no cursor row | Cursor claim yields nothing at all — stranded |
| After `reconcileKeyStateSql` | Claimed normally from its lowest order |
| Reconciliation against an advanced cursor | Cursor untouched, delivery resumes where it left off — no replay |
| Key enqueued via `upsertKeyStateOnEnqueueSql` | Claimable immediately, no reconciliation needed |
| **Fully migrated fleet, backlog with no cursor rows, two cursor pods reconciling on empty claim** | **Whole backlog drains, per-key ordering intact** |

So the design is: create the cursor row at enqueue (`ON CONFLICT DO NOTHING`, which must never reset a key
already making progress or the whole key replays), with reconciliation as the net beneath it, triggered when a
claim comes back empty. That trigger is the right one — an empty claim is exactly when it is worth asking
whether a key is invisible — and the statement is bounded by distinct-key count rather than backlog size and
idempotent, so it is cheap to run repeatedly and converges with no operator involvement.

Across the whole cursor set: 10 consecutive runs of 13 tests, plus the 27 earlier runs of the mixed-rollout
case.

Still not addressed:

- **Ordered acknowledgement batching across the two styles**, which is the payoff in §8 and is not exercised
  here: every pod in these tests acknowledges one message at a time.
- **Reconciliation cost on a large key space.** Bounded by distinct keys, but `SELECT DISTINCT` over the
  message table at 10⁵–10⁶ keys has not been measured, and an empty claim is a frequent event on an idle queue —
  it needs a floor on how often it fires.
- **Everything above is prototype SQL**, not an implementation: no `DurableQueues` code path, no enqueue
  integration, no interaction with `purgeQueue` or `deleteMessage` removing the last message for a key while its
  cursor row remains.

## 10. The §8 payoff argument was wrong, and the real one is narrower

§8 concluded the cursor is worth building because ordered acknowledgement "cannot be batched under the barrier
at all, while the cursor records completion explicitly and can" — worth roughly 2.73× end to end. **That is
wrong**, and the correction matters because it was the load-bearing justification.

### Deferring an ordered ack stalls the key under the cursor too

Per-key exclusivity in the corrected cursor comes from `is_being_delivered`. A deferred acknowledgement leaves
that flag set, so the key yields nothing until the flush — the identical stall the barrier produces by leaving
the row present. Asserted for both designs side by side in `QueueCursorCorrectnessIT`.

This is not a defect in either design. It follows from per-key ordering with at most one message in flight: a
key's successor may not be delivered until the predecessor's completion is durably recorded, and *any* batching
defers exactly that record. **Ordered throughput per key is bounded by one committed round trip per message
under both designs, and no cursor changes that.** §7's 16.5× does not reach ordered traffic by simply turning
batching on.

### What the cursor does uniquely enable: per-key runs

The barrier's `NOT EXISTS (… key_order < mine)` is evaluated per candidate row, so a key can only ever yield its
**head** — raising the claim limit returns nothing extra, which the test asserts. The cursor's condition is
`key_order > completed_through`, a *range*, so the next N messages of a key fall out of one index scan.

That is the real unlock. One claimer takes a contiguous run, handles it in order, and acknowledges the whole run
in one statement and one transaction — so §7's saving reaches ordered traffic after all, but through run-claiming
rather than deferred acknowledgement. Per-key exclusivity survives because a single claimer owns the run.

The payoff therefore scales with run length rather than being a flat 16.5×: a run of N amortises one transaction
across N messages. It has not been measured, and it should be before it is quoted.

### Three constraints, each found by test after the reasoning failed

1. **A run must be a prefix, including blocked rows.** Filtering ineligible rows out of the run handed a claimer
   orders 5 and 7 with 6 dead-lettered between them — the skipping fault reintroduced by another route. A
   `bool_and` window over `ORDER BY key_order` truncates at the first unclaimable row. The scan must therefore
   see ineligible rows, so it needs the non-partial `(queue_name, key, key_order)` index, like the clamp.
2. **`UPDATE … RETURNING` does not preserve order.** A run of 0,1,2 came back as 1,2,0. A consumer handling them
   as returned would violate the ordering the design exists to preserve, so the claim returns `key_order` and the
   caller must sort.
3. **The acknowledgement is now coupled to the claim.** For a run to advance the cursor across its whole length,
   the clamp must scan `(cursor, min_acknowledged)` rather than `(cursor, max_acknowledged)`. That is sound only
   for prefix batches — which is all the run claim produces. Bounding by the maximum instead is independently
   safe for any batch but then a run cannot advance the cursor at all: every row in the interval is one being
   deleted, so the clamp returns the old value and the gap scan grows from a stale cursor until it degrades.
   Independent safety and useful runs are mutually exclusive, so the coupling is deliberate — and any other
   ordered acknowledgement path must preserve the prefix property or it will skip a blocked message. Pinned by a
   test that asserts exactly that consequence.

### Where this leaves the cursor

The honest case is now:

| Claim | Status |
|---|---|
| Ordered claim 2.18× faster, 14.0 MB of index against 25.8 MB | Measured (§8) |
| Deferred ordered ack batching | **Impossible under either design** — the earlier 2.73× is withdrawn |
| Per-key runs amortising one transaction over N messages | Mechanism demonstrated, **magnitude unmeasured** |
| Rolling deploy, no flag-day, self-healing backfill | Established (§9) |

So the cursor is not the 2.64× or the 2.73× it has been carried as. It is a 2.18× claim plus a run-claiming
capability the barrier structurally cannot express, whose value depends on a run-length benchmark nobody has
run. That benchmark is the next thing worth doing, and it should gate the implementation — 16 tests over 8
consecutive runs cover the mechanics, but not one number in this section is a throughput measurement of runs.

## 11. The gate: run length and key cardinality — the cursor's case, finally

Harness: scenario `queue-ordered-run-length` (`QueueOrderedRunLengthScenario` +
`QueueOrderedRunLengthBenchmarkIT`). 20 000 ordered messages, claim batch 500, one repetition, PostgreSQL 17.5
in Testcontainers. Both arms get the same schema, so the comparison is the claim statement and not the indexes.
Headline metric is **database round trips per message**, because §7 established the transaction is the cost.

§10 left one number outstanding: run-claiming was the cursor's only remaining justification and nobody had
measured it. The design of the experiment mattered as much as the result — the obvious version is useless,
because both statements cap their total at `:limit`, so raising the run length changes *which* rows come back
rather than how many and rounds per message would be identical. Runs can only pay when the ready keys are fewer
than the batch can hold. So the sweep is over **key cardinality**, with run length as the treatment.

| Keys | Messages/key | Barrier claim | Barrier rounds | Cursor rl=1 claim | Cursor rl=1 rounds | Cursor rl=16 | Cursor rl=64 rounds |
|---|---|---|---|---|---|---|---|
| 8 | 2 500 | **222 729 ms** | 5 001 | 1 026 ms | 5 001 | 330 ms total | **95** |
| 64 | 312 | 10 312 ms | 627 | 402 ms | 627 | 385 ms total | 83 |
| 500 | 40 | 1 347 ms | 81 | 332 ms | 81 | 352 ms total | 81 |
| 2 000 | 10 | 1 139 ms | 81 | 443 ms | 87 | 445 ms total | 81 |

### Two effects, and they had been conflated

**Runs reduce round trips exactly where the design predicted, and nowhere else.** At 8 keys, 5 001 rounds fall
to 95 — a **53×** reduction. At 64 keys, 627 → 83, **7.6×**. At 500 and 2 000 keys, nothing at all: the round
count is already at its floor of 81 (40 claim plus 40 acknowledge plus the final empty claim), because there is
enough breadth to fill a 500-row batch from distinct keys and a run adds no rows. The optimum run length here is
around 16 — 64 buys fewer rounds but slightly worse wall clock, as the statements get larger.

**The larger effect is not runs at all: the barrier's ordered claim degrades catastrophically as messages per
key grows.** 222 729 ms at 8 keys against 1 139 ms at 2 000 — and the cursor at run length 1, doing the
*identical* number of round trips, is **217× faster** on the claim. Two compounding causes: the correlated
`NOT EXISTS (… key_order < mine)` rescans a key's depth for every candidate row, and the per-row barrier can
only return one row per key per round, so a deep backlog on few keys is drained one message per key per round
with an increasingly expensive predicate.

### What this does to the cursor's case

The 2.18× recorded in §8 was measured at 1 000 keys and 200 messages per key — which this sweep shows is the
barrier's *best* regime. The cursor's value is a function of backlog depth per key, not of total volume:

| Workload shape | Cursor's advantage |
|---|---|
| Few keys, deep backlog (a hot aggregate with thousands of events) | Claim 26–217×, plus 7.6–53× fewer round trips with runs |
| Keys ≈ batch size | Claim ~4×, runs add nothing |
| Many keys, shallow backlog | Claim ~2.6×, runs add nothing |

**The gate passes.** But the honest statement of the payoff is not a single multiplier: it is that the barrier
has a pathological regime — few keys, deep backlog — and the cursor does not. For an event-sourced workload,
where `key` is an aggregate id and a busy aggregate accumulates thousands of events, that regime is the normal
case rather than the corner case, which is what makes this decisive.

### Caveats

- **One repetition.** The barrier's 8-key case alone takes nearly four minutes, so a full multi-repetition sweep
  is expensive. The large effects (217×, 53×) are far outside any plausible noise; **the small ones at 500 and
  2 000 keys should not be over-read** — a 2.6× claim ratio at one repetition is a direction, not a figure.
- **Storage-only, single-connection**, like all the write-cost work: no consumers, no interceptors, no unit of
  work per message. §7's 16.5× transaction tax applies on top of every number here.
- **Run length interacts with worker parallelism in a way this does not capture.** A run of 16 handed to one
  worker is 16 messages handled sequentially by that worker; the throughput consequence depends on the handler's
  duration and the number of workers, neither of which exists in this harness. A long run could reduce
  parallelism for a hot key.
- The barrier arm uses `claimOrderedSql` without the exclude-keys predicate, so it is the cheapest form of the
  barrier available. Nothing here is stacked against it.

## 12. The storage track's last two: dead-letter side table, and partitioning

Harness: scenario `queue-storage-layout` (`QueueStorageLayoutScenario` + `QueueStorageLayoutBenchmarkIT`).
40 000 unordered messages across 8 queues, 5% dead-lettered before the drain, medians of 2. Multiple queues
throughout, because a single-queue run gives partitioning one partition and measures nothing.
**Acknowledgement is by id, one at a time, on purpose** — that is the operation partitioning threatens and the
hot path §7 measured at 16.5×; batching it would hide the effect the arm exists to expose.

| Arm | Insert | Claim | Ack by id | Dead-letter | Index bytes | Heap bytes |
|---|---|---|---|---|---|---|
| `V1_SHARED` | 650 ms | 414 ms | **2 352 ms** | 200 ms | 8.45 MB | 18.7 MB |
| `DLQ_SPLIT` | 539 ms | 385 ms | **2 260 ms** | 158 ms | 8.36 MB | 17.9 MB |
| `PARTITIONED` | **430 ms** | 694 ms | **3 057 ms** | 194 ms | n/a | n/a |

### Partitioning by `queue_name` — rejected

**Acknowledgement by id is 30% worse and the claim 40% worse**, to buy a 1.5× insert. Consistent across both
repetitions (3 033 / 3 080 ms against 2 509 / 2 195 ms), so this is not noise.

The cause was predicted and is structural rather than tunable. PostgreSQL requires the partition key in every
unique constraint, so `id TEXT PRIMARY KEY` cannot survive — the DDL is rejected outright until the key becomes
`(id, queue_name)`. And the entire `DurableQueues` API is keyed by `QueueEntryId` **alone**:
`acknowledgeMessageAsHandled`, `deleteMessage`, `getQueuedMessage`, `markAsDeadLetterMessage`, `retryMessage`.
None of them can name a partition, so every one degrades from a primary-key lookup to a probe of all eight.

So partitioning on this axis taxes the two operations that matter to accelerate the one that does not.
**Partitioning by `queue_name` is not viable unless the public API gains `(queueName, id)` addressing** — which
is a breaking change to a stable central API, for a purge win that can be had far more cheaply.

### Dead-letter side table — real but modest, and not for the reason claimed

1.21× on insert, 1.08× on claim, 1.04× on acknowledge, 1.27× on dead-lettering itself. **And essentially no
index-size win**: 8.36 MB against 8.45 MB.

That last number is the interesting one, because the argument for this change was index write amplification —
the lever that *has* measured as significant. It does not apply here at anything like the strength it did for the
ordered/unordered split. That split removed five whole indexes; this removes one boolean from one index's key
and from four predicates. Same lever, an order of magnitude less of it.

The change is still defensible — it is contract-preserving, it keeps long-lived dead-letter rows out of the hot
table's pages (heap 4% smaller at only 5% dead-lettered), and it makes dead-letter browse and resurrect stop
touching hot data. But it should be justified on those grounds, **not on throughput**, and it should not be
sequenced ahead of anything with a larger measured effect.

### Two defects in this measurement, stated rather than hidden

- **The purge comparison is void.** Purge runs after the drain, so every arm purged a nearly empty table and all
  three reported 1 ms. `TRUNCATE`-of-a-partition against `DELETE FROM … WHERE queue_name` is untested. It is
  also now moot: the arm that would have won it is rejected on the numbers above.
- **The partitioned arm has no size data.** `pg_table_size`/`pg_indexes_size` against `pg_class` for a
  partitioned *parent* return 0, since the parent holds no storage. Summing over partitions, or
  `pg_total_relation_size`, is what that needed.
- Two repetitions, and the differences for `DLQ_SPLIT` are small enough (1.04–1.27×) to sit within the spread
  seen elsewhere in this document. Treat its numbers as "small positive", not as figures.

## 13. Per-table autovacuum settings do not pay, and the reason is instructive

Harness: scenario `queue-autovacuum` (`QueueAutovacuumScenario` + `QueueAutovacuumBenchmarkIT`). Twelve
insert-then-drain cycles against **one** table, 20 000 messages per cycle, batched claim and batched
acknowledgement so §7's per-message transaction tax does not mask the effect. The signal sought was
**degradation across cycles** — does cycle 12 cost more than cycle 1 — because that is what dead-tuple
accumulation would look like and it is the shape that produced this investigation's worst measurement
artefacts. Run twice, at `autovacuum_naptime` 60s (PostgreSQL's default) and 5s.

| naptime | Arm | First drain | Last drain | Degradation | Peak dead tuples | Autovacuums |
|---|---|---|---|---|---|---|
| **60s** | `DEFAULT` | 366 ms | 325 ms | 0.89 | 440 000 | **0** |
| 60s | `AGGRESSIVE` | 302 ms | 344 ms | 1.14 | 446 500 | **0** |
| 60s | `MODERATE` | 293 ms | 299 ms | 1.02 | 400 000 | **0** |
| **5s** | `DEFAULT` | 367 ms | 304 ms | 0.83 | 231 500 | 2 |
| 5s | `AGGRESSIVE` | 319 ms | 267 ms | 0.84 | 233 500 | 3 |
| 5s | `MODERATE` | 305 ms | 282 ms | 0.92 | 240 000 | 1 |

`AGGRESSIVE` is `scale_factor 0.01, cost_delay 0, threshold 100` — roughly what `pgmq` ships, and what this
plan proposed.

### Two negative results

**1. On a default cluster the settings are inert.** Autovacuum ran **zero** times in every arm, including the
aggressive one, while 440 000 dead tuples accumulated. The per-table threshold is not what binds —
`autovacuum_naptime` is, and it is a **cluster** setting Essentials cannot touch from its DDL. The change this
plan called "the cheapest item" and "worth doing first regardless" would have had *no effect whatsoever* on a
deployment running PostgreSQL defaults. At naptime 5s the settings do bite, and aggressively so — final dead
tuples 85 215 against the default arm's 159 500 — but that is a cluster the operator has already tuned.

**2. There was no degradation to fix.** Drain cost did not rise across twelve cycles in any arm at either
naptime: every ratio is between 0.83 and 1.14, and most are below 1. The premise — dead tuples accumulate and
the claim slowly degrades — did not reproduce in a queue that drains fully each cycle, even with 440 000 dead
tuples present and no vacuum at all.

### What this says about the bloat concern generally

The bloat worry that started this line of work is not wrong, but this measurement relocates its cause. The two
genuinely damaging effects observed anywhere in this investigation were both something else:

- **xmin pinning.** A transaction held open across a whole drain blocks reclamation regardless of any setting,
  and produced a 5.7× degradation across three identical repetitions (§7). That is a code-shape problem, and it
  is one Essentials controls — the fix was to hold a unit of work per batch rather than per drain.
- **Autovacuum firing at unpredictable times.** The 1.13–2.99× run-to-run spreads that forced interleaved
  repetitions in §7 look, in hindsight, like vacuum landing inside some runs and not others — a measurement
  hazard rather than a production one.

So the actionable levers are: **do not hold long transactions** (already the case after §7's fix), and **tell
operators about naptime**, since the table-level parameters are the only half Essentials can set and they are
the half that does not bind. Shipping the storage parameters is harmless and may help a tuned cluster; it should
not be described as a performance fix, and it should certainly not be sequenced first.

### Caveats

- **The workload drains fully every cycle.** A sustained backlog that never empties — the shape a real bloat
  incident takes — is not tested here, and is where accumulation might actually degrade the claim. That is the
  measurement to run if bloat is ever suspected in the field.
- One run per naptime, twelve cycles each. The negative results are large and consistent (zero autovacuums is
  not a marginal reading), but the small differences between arms at naptime 5s should not be over-read.
- `heap_bytes` was recorded but is not reported above: with the table fully drained each cycle it tracks the peak
  rather than the steady state, and says less than the dead-tuple counts do.

## 14. The statistics trigger: the mechanism claim is right, the cost claim is not

Harness: scenario `queue-statistics-trigger` (`QueueStatisticsTriggerScenario` +
`QueueStatisticsTriggerBenchmarkIT`). 50 000 messages, medians of 3, batched acknowledgement — that is the
framework's own recommended path now, and a per-message acknowledgement would let §7's transaction tax swamp
what is being measured. The trigger is reproduced exactly as `PostgresqlDurableQueuesStatistics` installs it,
stats table and both indexes included.

| Arm | Ack | vs. no statistics | Subtransaction SLRU hits | SLRU writes |
|---|---|---|---|---|
| `NO_STATISTICS` | 173 ms | — | 0 | 0 |
| `TRIGGER_AS_SHIPPED` | 485 ms | **2.80×** | ~48 000 | **0** |
| `TRIGGER_WITHOUT_EXCEPTION` | 470 ms | 2.72× | ~1 000 | 0 |
| `JAVA_OBSERVER_SIMULATED` | 361 ms | **2.09×** | 0 | 0 |

### The `EXCEPTION WHEN OTHERS` claim: mechanism confirmed, consequence not reached

`durable-queues-statistics-improvements.md` calls this "the single most expensive part of the trigger" and warns
that at sustained throughput it burns subtransaction ids and pushes the subtransaction SLRU toward overflow,
degrading unrelated queries.

**The mechanism is exactly as described, and visible**: ~48 000 subtransaction SLRU hits for 50 000 acknowledged
rows with the block present, against ~1 000 without it. One subtransaction per row, confirmed directly rather
than inferred.

**The consequence is not reached in this shape.** `blks_written` is zero in every arm — it all stays in the
shared-memory cache and never spills — and the wall-clock cost of the block is **1.03×**, about 3%, not the
dominant term. The overflow risk is real in principle, but it needs many backends, long-lived transactions and
sustained concurrency; a single backend issuing short transactions cannot provoke it. So the claim should be
restated: the block allocates a subtransaction per row, which is a latent hazard under concurrency, and it is
*not* what makes the trigger expensive.

### What is actually expensive: the statistics write itself

Enabling delivery statistics costs **2.80×** on the acknowledgement path. Of that, essentially none is the
exception block and only about a quarter is the plpgsql per-row invocation — the rest is the `INSERT` and
maintenance on the stats table's two indexes, which any mechanism has to pay.

That is why the proposed Java-side observer is worth building but is not a fix: at **2.09×** it recovers
**1.34×** against the trigger and leaves the majority of the cost in place. If the cost of statistics matters to
a deployment, the higher-value levers are reducing what is written — fewer indexes on the stats table, or
sampling rather than recording every message — and the fact that the whole feature is **off by default**, which
remains the single most effective mitigation shipped.

### Caveats

- The observer arm is simulated in SQL: `DELETE … RETURNING` feeding an `INSERT … SELECT`, one statement per
  acknowledged batch. A real Java-side observer would carry the rows through the interceptor chain instead, so it
  would pay JVM-side costs this does not model and would not get the set-based insert for free.
- Single backend throughout, which is precisely why the SLRU-overflow half of the claim could not be tested. A
  concurrency sweep with several backends and long transactions is the measurement that would settle it, and it
  has not been run.
- The trigger's other five defects — purge amplification, dialect portability, DDL on a table it does not own,
  the unqualified function name colliding between instances, and the broken `delivery_latency` read path — are
  correctness and design arguments, unaffected by these numbers, and remain the stronger case for the rewrite.

## 15. B1, the write-once message row — rejected

Harness: a `WRITE_ONCE` arm in `QueueSchemaWriteCostScenario`. 50 000 unordered messages, medians of 3.

The idea, from the §8 backlog: every arm measured so far writes a message row three times — `INSERT`, `UPDATE` on
claim, `DELETE` — and the claim writes `is_being_delivered` and `next_delivery_ts`, both indexed, which is exactly
why `n_tup_hot_upd` was zero everywhere. Remove `is_being_delivered` from the table, put in-flight state in a
small side table keyed by id, and the claim becomes an `INSERT` there rather than an `UPDATE` here. Two row
versions instead of three, and the claim's index churn moves off the large table onto one bounded by concurrency
rather than by backlog.

| Arm | Insert | Claim | Ack | Total | Index bytes |
|---|---|---|---|---|---|
| `V1_SHARED` | 672 ms | 594 ms | 218 ms | 1 484 ms | 10.58 MB |
| `V2_SPLIT` | 438 ms | **411 ms** | **156 ms** | **1 005 ms** | 9.20 MB |
| `WRITE_ONCE` | 427 ms | 462 ms | 236 ms | 1 125 ms | **8.45 MB** |

**12% worse overall than the split it was meant to beat.** Claim 12% worse, acknowledgement **51%** worse. The
8% index-bytes win is real and is the one place the hypothesis held, but it does not come close to paying for the
rest.

### Why it lost, and what that implies for the variant

The hypothesis was half right. Removing the claim's `UPDATE` genuinely takes churn off the message table — the
index-bytes figure confirms it. But the churn does not disappear, it relocates: the in-flight table carries its
own primary key and a `claimed_at` index, both written on every claim and again on every release. And the
acknowledgement now has to delete from two tables instead of one, which is where the 51% went.

So the lever was correctly identified and the mechanism was wrong: this design pays two new costs to avoid one
old one.

That sharpens the case for **B4, the advisory-lock claim**, which is the same idea without either new cost —
`pg_try_advisory_xact_lock(hashtext(id))` marks a message in flight with **no write at all** and **no second
table in the acknowledgement path**. B1's failure is the argument for trying it: the claim write is worth
removing, but not at the price of a second table. B4's own constraint is that the lock must span the handler,
which ties it to B2 and makes the two a single experiment rather than two.

### Caveats

- Unordered only. Ordered exclusivity is the cursor's concern (§8–§11) and combining both changes would make
  neither attributable.
- Storage-only and single-connection, like the rest of the write-cost work. In particular the anti-join against
  the in-flight table was measured with no contention; under concurrency it would also be the point where two
  claimers collide, and `ON CONFLICT DO NOTHING` makes that benign but not free.
- One implementation detail cost a run and is worth recording: `COMMON_COLUMNS` is a Java text block, so the
  compiler has already stripped its incidental indentation, and a `replace` written against the indentation
  visible in the source matches nothing. The arm would have measured a table that still had the column. Only the
  guard that fails loudly on a no-op turned that into an error rather than a wrong number — the same class of
  silent-no-op the dead-letter arm had already been protected against, and reusing that protection would have
  avoided it.

## 16. B3, the index diet — two of six indexes are never read

Harness: `PostgresqlIndexUsageIT` in `postgresql-queue`, opt-in via `-Dbenchmark.run=true`. The **real**
component, not a prototype schema: 40 000 messages split between an unordered and an ordered queue at 200 keys,
then every operation on the `DurableQueues` SPI driven at least once — enqueue in both modes, delayed enqueue,
enqueue-as-dead-letter, all the query and count operations, retry, both dead-letter paths, resurrect, delete,
purge, and real consumers claiming and acknowledging both queues to completion. Then `pg_stat_user_indexes`.

| Index | Scans | Bytes |
|---|---|---|
| `idx_durable_queues_ordered_ready` | **0** | 3 317 760 |
| `idx_durable_queues_ready` | **0** | 2 007 040 |
| `idx_durable_queues_next_msg` | 411 | 819 200 |
| `idx_durable_queues_unordered_ready` | 2 474 | 1 835 008 |
| `idx_durable_queues_ordered_head` | 4 940 | 2 498 560 |
| `idx_durable_queues_ordered_msg` | **164 978** | 1 900 544 |

**Two of the six were never scanned once, and they hold 5.3 MB of the table's 12.4 MB — 43% of its index bytes**,
maintained on every insert, claim and delete. That is the same lever as the ordered/unordered split's 1.38×, and
this part of it needs no new table, no API change and no migration.

`initializeQueueTables()` creates all six unconditionally, regardless of `useOrderedUnorderedQuery`. Since that
flag now defaults to `true` everywhere, a default deployment maintains indexes built to serve a query it never
runs.

### Two findings, one expected and one not

**`idx_..._ready` being dead is the expected half.** It serves the unified query, and the split is now the
default. It cannot simply be deleted — it is needed when `useOrderedUnorderedQuery=false` — so the change is to
**create the three unified-query indexes only when the flag is false, and the split indexes only when it is
true**, rather than creating all six either way.

**`idx_..._ordered_ready` being dead is the surprise.** That index was *added for the split ordered path*, and it
is unused with the split on. The ordered work is done by `ordered_head` (4 940 scans) and above all
`ordered_msg` (164 978 — by far the most-used index on the table, and consistent with §11's finding that the
barrier rescans a key's depth for every candidate). So one of the indexes introduced to make the split fast is
redundant with its siblings, and that is worth confirming before it is dropped rather than merely conditioned.

### Caveats

- **A zero here means "not scanned by any operation in this suite", not "unused".** The suite drives the whole
  SPI for exactly that reason, but an access pattern it does not produce — a different sort order, a much larger
  or much smaller table, a different ordered-key cardinality — could shift which index the planner picks. The
  conditional-creation change is safe regardless; dropping `ordered_ready` outright needs a second workload.
- One volume (40 000 rows) and one key cardinality (200). §11 showed the ordered claim's plan is highly sensitive
  to messages-per-key, so index choice may well be too.
- `pg_stat_user_indexes` is flushed asynchronously; the test waits before reading. Counts are cumulative so a
  wait suffices, unlike the `pg_stat_database` differencing that had to be abandoned in §7.

## 17. The index diet, confirmed at a second shape and acted on

§16 reported two never-scanned indexes at 40 000 messages and 200 ordered keys. Acting on one shape would have
been unsafe, because §11 established that the ordered claim's plan is highly sensitive to messages-per-key. So
the usage report was re-run at 12 000 messages and 8 keys, and the second shape changed the conclusion in two
places.

| Index | 200 keys | 8 keys | Action |
|---|---|---|---|
| `idx_*_ordered_ready` | 0 | **0** | **Dropped** — dead in both shapes |
| `idx_*_ordered_msg` | 0 | **0** | **Conditional** — superseded by the unique index, kept only under `ALLOW` |
| `idx_*_ready` | 0 | **21** | **Kept** — §16's "dead" was shape-specific |
| `idx_*_next_msg` | 408 | 365 | Kept |
| `idx_*_unordered_ready` | 2 469 | 2 936 | Kept |
| `idx_*_ordered_head` | 4 931 | 4 421 | Kept |
| `idx_*_ordered_unique` | 145 179 | **3 375 002** | Kept — added for correctness, now also the barrier's index |

### Two things the second shape caught

**`idx_*_ready` is not dead.** It had zero scans at 200 keys and 21 at 8. Dropping it on §16's evidence alone
would have removed an index something selects. It is kept, and §16's conclusion about it is corrected.

**The unique index added for correctness superseded `idx_*_ordered_msg`.** That index served the per-key barrier
164 978 times in §16 — before `OrderedMessageDuplicateStrategy` existed. The unique index is
`(queue_name, key, key_order)` partial on `key IS NOT NULL`, a narrower form of the same columns, and once
present the planner took it for the barrier at **both** cardinalities, leaving `ordered_msg` at zero. So a change
made purely for correctness paid for itself in index maintenance — and `ordered_msg` is now created only under
`ALLOW`, where there is no unique index to serve the barrier.

### Result

Six secondary indexes become five, and the two removed were larger than the one added: `ordered_ready` (3.3 MB)
and `ordered_msg` (1.9 MB) out, `ordered_unique` (1.7 MB) in — **28% fewer index bytes on the same workload**,
maintained on every insert, claim and delete, plus an ordering guarantee that did not exist before.

At 8 keys every remaining index is scanned. At 200 keys only `ready` is not, and it is retained on the strength
of the other shape.

### Caveats

- **Dropping indexes redistributes work rather than removing it.** `next_msg` went from 408 scans to 10 676 at
  200 keys, picking up paths the dropped indexes had served. Nothing regressed in the suite, but the remaining
  five now carry more per scan than they did.
- Two shapes is two, not many. A deployment with a very different volume, key cardinality or sort-order mix could
  select differently — which is why the diagnostic is committed and parameterised
  (`-Dindexusage.messages`, `-Dindexusage.orderedKeys`) rather than the finding merely written down.
- This is not the split. It is the index-set groundwork the split needs — an ordered table should carry the head
  index and the unique index, not the three it inherited from v1 — and it happens to ship a win on the way.


## 18. B2 and B4, the handler in the claim transaction — both rejected

The last untried ideas on the dominant lever, measured together because B4 requires the lock to span the handler,
which *is* B2. Harness: `HandlerInClaimTransactionBenchmarkIT`, 1 000 unordered messages, raw JDBI through the
production `JdbiUnitOfWorkFactory` so only the transaction boundaries differ between arms.

Three arms. **`TWO_TRANSACTIONS`** is today: claim commits, handler runs with no transaction open, acknowledgement
commits. **`HANDLER_IN_TRANSACTION`** (B2) is claim, savepoint, handler, release, delete, one commit — the savepoint
around the handler *only*, so a failure rolls back the handler's work without discarding the claim's attempt
increment, which is the specific defect that makes `FullyTransactional` unusable. **`ADVISORY_LOCK`** (B4+B2) takes
`pg_try_advisory_xact_lock(hashtext(id))` instead of writing `is_being_delivered`, so the claim performs no write
at all.

### With workers ≤ connection pool: B2 does nothing, B4 costs 25–30%

10 workers, pool 16, medians of one run per cell:

| Handler | 2 txn | B2 | B4 | B2 gain | B4 gain |
|---|---|---|---|---|---|
| 0 ms | 108 ms | 105 ms | 145 ms | 1.03× | 0.74× |
| 1 ms | 167 ms | 170 ms | 246 ms | 0.98× | 0.68× |
| 5 ms | 625 ms | 643 ms | 916 ms | 0.97× | 0.68× |
| 25 ms | 2 846 ms | 2 800 ms | 3 591 ms | 1.02× | 0.79× |

**Halving transactions per message buys nothing**, and that is the finding worth carrying forward. The lever is
right — transactions per message is what has moved every number in this investigation — but the *gradient is not
linear*. §7's 16.5× came from 64 messages per transaction against one, a 64× reduction. Going from two to one is a
2× reduction of a cost that is already cheap and already parallel across workers, and it disappears into noise.
**Amortising across a batch is the mechanism; shaving one commit is not.**

B4 is worse than B2 at every duration. Replacing a targeted single-row `FOR UPDATE SKIP LOCKED` claim with a
50-row candidate scan plus a per-row lock attempt costs more than the write it saves — and it loses races, because
a lock on `hashtext(id)` says nothing about whether the row still exists, so a worker can win a lock for a row
another worker already deleted and do the handler's work for nothing. That last point also had to be fixed in the
harness before the arm could even be measured: counting *claims* rather than *deletions* let it report completion
with rows still in the table.

### With workers > connection pool: the predicted penalty, and it is severe

The first table cannot show B2's risk, because with workers ≤ pool there is always a spare connection and holding
one across the handler costs nothing. That is a property of the harness, not of the design, so the sweep was
repeated with 48 workers against a pool of 12:

| Handler | 2 txn | B2 | B4 | B2 gain | B4 gain |
|---|---|---|---|---|---|
| 5 ms | 178 ms | 520 ms | 743 ms | **0.34×** | 0.24× |
| 25 ms | 573 ms | 2 285 ms | 3 000 ms | **0.25×** | 0.19× |

**Three to four times worse.** The baseline releases its connection for the handler's duration and can keep 48
messages in flight on 12 connections; B2 cannot, so its throughput is capped at `pool ÷ handler duration`. This is
the risk the backlog required to be measured rather than assumed, and it is real.

### Verdict

**Both rejected.** B2 has no upside and a large downside: flat at 1.0× where connections are plentiful, 0.25× where
they are not. There is no crossover in the useful direction — the curve is flat, then bad. B4 is worse than B2
everywhere and is rejected with it.

This closes the last untried idea on transactions per message. What remains on that lever is **run-claiming with a
batched ordered acknowledgement** — which works by amortising across a batch, the mechanism these two arms confirm
is the one that matters.


## 19. Run-claiming — attempted, not achieved, and three concurrency defects found

§18 left run-claiming as the only remaining idea with a case on the dominant lever: one claimer takes a contiguous
prefix of a key's messages, handles them in order, and commits **once** for the whole run. It defers nothing, so
unlike batched acknowledgement it does not fall foul of §2 — the key's successor is released by that single commit.

**Single-threaded it works and it pays.** 2 keys × 5 messages, one worker: head-and-ack-per-message 36 ms, run
length 4 → 16 ms, **2.25×**. The mechanism is real.

**Under concurrency it does not work yet.** Three distinct defects, found in sequence, each one exposed only by
fixing the previous:

1. **The cursor claim deadlocks with concurrent claimers.** `UPDATE … FROM candidate WHERE q.id = c.id` *waits* on
   any row another claimer has locked, and two claimers whose candidate sets overlap in different orders produce
   "deadlock detected". v1's barrier claim has always used `FOR UPDATE SKIP LOCKED`; the cursor claim shipped
   without it. **It is latent rather than active in the shipped flag**: the centralized message fetcher has a
   single poll thread and never issues two claims at once. It would surface under the traditional per-consumer
   fetcher, or with two instances.
2. **Adding `SKIP LOCKED` to the message update fixes the deadlock and breaks something worse.** It can drop a row
   from the *middle* of a run — handing a claimer orders 5 and 7 while another holds 6 — which is exactly the
   skipping fault the `bool_and` prefix window exists to prevent, reintroduced by another route. Caught by the
   benchmark's per-key ordering assertion, not by inspection.
3. **Moving exclusivity to a `FOR UPDATE SKIP LOCKED` on the key's cursor row then deadlocks against the
   acknowledgement**, because the claim locks cursor-row → message-rows while the acknowledgement locks
   message-rows → cursor-row. Fixing the ordering (lock the affected keys' cursor rows first) removed the deadlock
   and left a stall: messages claimed and marked in flight that no worker ever acknowledges, so every key blocks
   permanently and the drain halts around half way.

That third state is where the attempt stopped. **The claim SQL was reverted to the shipped head-only form**, which
is green again on both cursor gates — the 13-test shared suite and the 2 000-message / 20-consumer ordering suite.
Nothing about the shipped flag changed.

### What this establishes

- **Run-claiming's benefit is real** (2.25× single-threaded at run length 4) and its cost is a genuinely hard
  concurrency design, not an implementation detail. Prefix integrity and deadlock-freedom pull against each other:
  every lock strategy that prevents one has so far broken the other.
- ~~**The shipped cursor has a latent deadlock** under concurrent claimers (defect 1).~~ **Withdrawn — see §20.**
  The inference was that the head-only claim shares defect 1, since its `UPDATE … FROM candidate` also waits rather
  than skipping. It does not reproduce, at two levels of aggression, and there is a reason: a cycle needs two
  claimers to lock the same rows in *different* orders, and this statement takes at most one row per key while both
  claimers scan the key-state table in the same order. The run claim broke that by taking many rows per key.
- **A benchmark that counts claims rather than deletions hides all of this.** Twice in this section, and once in
  §18, a harness reported completion with rows still in the table. Counting what the acknowledgement actually
  deleted is what made the stalls visible.

### If this is picked up again

The lock order has to be one order everywhere — cursor row, then message rows — and the run has to stay a prefix
*after* locking, which means the claim cannot use `SKIP LOCKED` on the messages at all. A claim that locks exactly
one key's cursor row per statement would satisfy both, at the cost of one statement per key per poll, which is the
trade the benchmark should measure next.


## 20. The head-only claim's "latent deadlock" — asserted, tested, withdrawn

§19 inferred that the shipped head-only cursor claim carries the deadlock that run-claiming demonstrated, on the
grounds that `UPDATE … FROM candidate WHERE q.id = c.id` *waits* on rows another claimer holds where v1's barrier
skips them. The inference was reasonable and it is wrong.

`PostgresqlCursorConcurrentClaimIT` drives concurrent claimers directly — the case both cursor gates structurally
cannot reach, since `CentralizedMessageFetcher` has a single poll thread and its 20-consumer suite contends on
*handling* rather than claiming. Against the **unmodified** statement:

| Keys | Claimers | Claim limit | Result |
|---|---|---|---|
| 12 | 8 | 4 | passes — no deadlock, ordering holds, queue drains |
| 40 | 16 | 20 | passes — no deadlock, ordering holds, queue drains |

**Why it cannot deadlock in this shape.** A cycle requires two claimers to lock the same rows in *different* orders.
This statement takes at most one row per key, and both claimers scan the key-state table in the same order, so
their lock orders agree. Run-claiming broke exactly that property by taking many rows per key, whose per-key sets
interleave — which is why the deadlock appeared there and not here.

A `FOR UPDATE SKIP LOCKED` stage was written for the head claim and then **reverted**: a fix for a defect that
cannot be reproduced is a change with only downside, and the same `SKIP LOCKED` is actively unsafe one step away
(§19 defect 2). The test is kept, relabelled for what it establishes — per-key ordering and complete drainage under
concurrent claimers, neither of which had any coverage before.

**The methodological point is the one worth keeping.** This is the fifth claim in this investigation to be asserted
from a plausible mechanism and then withdrawn under test, and the first where the withdrawal was of *my own*
same-session conclusion. Reasoning from "this statement waits where that one skips" to "therefore it deadlocks"
skips the step where the lock order is examined. Cheap to check, and the check is what decides it.


## 21. The shipped split measured through the component — it is slower, not faster

The split's published figures — 1.38× overall, 1.62× on insert for unordered traffic — come from raw SQL against
prototype schemas (v2 design plan), not from `PostgresqlSplitDurableQueues`. Those figures are quoted in the
user-facing summary, the class javadoc, the module notes and the Spring property javadoc. This measures the shipped
component instead: `SplitVsSharedTableBenchmarkIT`, both arms driven through the public API on today's defaults.

### Getting the harness right first — two faults, either of which would have published a wrong number

1. **The drain was polling-bound, not database-bound.** A drain of N messages takes at least
   `(N / parallelConsumers) × pollingInterval`. At the defaults that is 4 000 / 10 × 20 ms = **8 s**, and the first
   run measured 8 s in *every* arm — shared and split, batched acknowledgement on and off. The drain column was
   measuring the poll loop and could not have shown a storage difference of any size.
2. **At 4 000 messages the measurement was not reproducible.** With the polling bound removed, a single run gave
   1.47× in the split's favour and medians of five interleaved repetitions gave 0.86× against it. Drains of
   ~200 ms are dominated by warm-up and tick granularity. Neither number was fit to publish.

### The result, at 40 000 messages, medians of 3 interleaved repetitions

| Traffic | Arm | Enqueue | Drain | Total | Index KB |
|---|---|---|---|---|---|
| unordered | shared | 549 ms | **1 648 ms** | 2 197 ms | 6 840 |
| unordered | split | 404 ms | **10 425 ms** | 10 829 ms | 6 200 |
| unordered | *ratio* | *1.36×* | ***0.16×*** | ***0.20×*** | *1.10×* |
| ordered | shared | 668 ms | 57 558 ms | 58 226 ms | 14 104 |
| ordered | split | 521 ms | 59 659 ms | 60 180 ms | 10 616 |
| ordered | *ratio* | *1.28×* | *0.96×* | *0.97×* | *1.33×* |

Reproduced on a second independent run: 1 644 ms against 10 282 ms, ratio 0.16 again.

**The split is roughly 5× slower end to end on unordered traffic — the exact traffic it was built to speed up.**

### What survives and what does not

- **The insert win is real**: 1.14–1.36× measured, and index bytes are 1.10× (unordered) to 1.33× (ordered)
  smaller, so the mechanism the design claims is genuinely happening. It is simply not where the time goes.
- **The published 1.38× total does not survive.** It was measured on raw insert/claim/ack loops where insert was a
  large share of the total. Through the component, insert is 549 ms of a 2 197 ms shared-table run, and the drain
  dominates — the same dilution that turned the cursor's prototype 217× into 1.85×, only this time it goes past
  neutral into a regression.
- **The regression is in the composite's drain, not in the schema.** Index bytes moved the right way while drain
  moved the wrong way by 6×, which localises it to how `PostgresqlSplitDurableQueues` fetches rather than to having
  two tables. The prime suspect is that every poll claims from the *ordered* table first and then the unordered
  one — two claim transactions and two stuck-message resets per poll where v1 does one — and for pure-unordered
  traffic the ordered half is pure overhead. **Not yet confirmed**, and it should be, because if that is the cause
  it is fixable: skip a store that cannot contribute.

### Consequence

The split flag must not be recommended on the strength of a throughput claim until this is understood. Every place
quoting 1.38×/1.62× as what a deployment gets has been corrected to state the component measurement instead.


## 22. The split's regression was the reused claim path, not the two tables

§21 localised the 6× unordered drain regression to how the composite fetches. Two hypotheses were tested in order,
and the first — the obvious one — was wrong.

**Wrong: two transactions per poll.** Each delegate opens its own `UnitOfWork`, so a poll committed twice where v1
commits once. A `UnitOfWorkFactory` *joins* an ambient unit of work and only the outermost commits, so wrapping the
composite's fetch and its by-id lookups in one outer unit of work collapses them into a single transaction. The fix
is correct and was kept — §7b's "two statements, one transaction" argument depends on it — but it moved the number
not at all: still 0.16×.

**Right: the composite asked each table for messages it cannot hold.** Reusing v1's claim path meant the unordered
delegate ran v1's *ordered* query against the unordered table on every poll. That query filters `key IS NOT NULL`,
and a split unordered table carries exactly one index — `(queue_name, next_delivery_ts)` partial on not-dead-letter
and not-being-delivered — which cannot serve it. So every poll scanned the whole unordered table hunting for
ordered messages that by construction never exist, and the cost grew with the backlog: 0.75× at 10 000 messages,
0.16× at 40 000. The statement counter is what showed it, at 1 643 claim statements against the shared table's 684.

The fix is a `ClaimScope` — `ORDERED_ONLY`, `UNORDERED_ONLY`, `ORDERED_AND_UNORDERED` — so each table is asked only
for what it can hold. Shared-table behaviour is untouched, since it keeps the third.

### After the fix, 40 000 messages, medians of 3

| Traffic | Arm | Enqueue | Drain | Total | Index KB |
|---|---|---|---|---|---|
| unordered | shared | 542 ms | 1 630 ms | 2 172 ms | 6 776 |
| unordered | split | 394 ms | 6 635 ms | 7 029 ms | 6 192 |
| unordered | *ratio* | *1.38×* | *0.25×* | *0.31×* | *1.09×* |
| ordered | shared | 683 ms | 46 308 ms | 46 991 ms | 14 160 |
| ordered | split | 521 ms | **12 388 ms** | 12 909 ms | 10 568 |
| ordered | *ratio* | *1.31×* | ***3.74×*** | ***3.64×*** | *1.34×* |

**Ordered traffic goes from 0.97× to 3.64×** — the split is now decisively worth having for ordered workloads,
which is the opposite of what the prototype predicted (it said 1.07×, "buys almost nothing"). The prototype
measured a schema; what actually pays is not asking a table for what it cannot hold.

**Unordered improves from 0.16× to 0.31× and is still a 3× regression.** Not solved. The claim counter still shows
the split issuing roughly twice the shared table's claim statements, which does not by itself explain a 4× drain,
so the remaining cause is unidentified and must not be guessed at again.

### A second defect the counter exposed, unfixed

For **ordered** traffic the split issues **80 000 deletes for 40 000 messages** and 40 000 extra statements. The
by-id dual lookup tries the unordered store first, so every ordered acknowledgement runs a `DELETE` that matches
nothing before running the one that works. §7b accepted "one extra statement per by-id operation" as the price of
keeping the id opaque — this is that price, now measured, and it is being paid on the hot path once per message.
Routing by a cheap probe, or trying the ordered store first when the queue is known to be ordered, would remove it.
The split still wins ordered by 3.64× while paying it.

### The lesson

The composition was chosen so both tables could be driven by v1's existing statements unchanged (§7c), which made
increment 1 cheap. That reuse is exactly what caused the regression: v1's statements assume v1's index set, and the
split's whole purpose is to *not* have v1's index set. Reuse across a boundary where the invariants differ buys
delivery speed and charges for it later.
