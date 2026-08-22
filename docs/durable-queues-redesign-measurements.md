# DurableQueues redesign — measurements

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
| Framework overhead at equal transaction granularity | **1.04× — the framework is not the problem** | §7 |
| Transaction granularity, per-message vs per-batch | **118× on drain time** — this is the whole cost | §7 |
| Consequence for the cursor's 2.64× | **Collapses to ≈1.2–1.3× unless acknowledgement is batched first** | §7 |

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

## 7. Framework overhead — and it is not what the earlier sections assumed

Harness: scenario `queue-framework-overhead` (`QueueFrameworkOverheadScenario` +
`QueueFrameworkOverheadBenchmarkIT`). 20 000 unordered messages, claim batch 500, medians of 3 repetitions
after a discarded warmup, PostgreSQL 17.5 in Testcontainers, Temurin 25 (aarch64). Every case gets a freshly
created table that is dropped afterwards.

§2a concluded that the per-message *operation* dominates the statement, and the whole v2 discussion has since
treated "framework overhead" as the thing that would dilute the prototype ratios. That framing was measured
and it is wrong. The overhead is real and it is large, but it is not the framework.

Each arm issues the same SQL against the same schema and differs only in how many transactions those
statements are spread across. Drain = claim + ack, 20 000 messages.

| Arm | Claim | Ack | Drain | Transactions per message |
|---|---|---|---|---|
| `RAW_BATCHED` — batch claim, batch ack (the write-cost prototype's shape) | 245 ms | 68 ms | **325 ms** | 0.004 |
| `RAW_BATCH_CLAIM_SINGLE_ACK` — batch claim, ack per message (today's real shape) | 251 ms | 4 577 ms | **4 837 ms** | 1.002 |
| `RAW_SINGLE` — claim per message, ack per message | 33 583 ms | 4 861 ms | **38 444 ms** | 2.000 |
| `COMPONENT_SHARED_UOW` — real component, one unit of work per batch | 23 609 ms | 1 328 ms | **24 937 ms** | 0.002 |
| `COMPONENT` — real component, no outer unit of work (production shape) | 35 216 ms | 4 643 ms | **39 859 ms** | 2.000 |

| Ratio | Result | What it isolates |
|---|---|---|
| `frameworkOverheadAtEqualGranularity` | **1.04×** | Everything the component does per message beyond the SQL — interceptor chain, operation objects, serialization, row mapping — against raw SQL at the same transaction granularity |
| `ackTransactionGranularity` | **14.9×** | One transaction per acknowledgement instead of one per batch |
| `fullTransactionGranularity` | **118×** | Two transactions per message instead of two per batch |
| `componentTransactionGranularity` | **1.60×** | The same component code with and without an outer unit of work |
| `prototypeUpperBoundDeflator` | **123×** | The prototype's transaction shape against the production component's |

**The framework costs 4%.** `COMPONENT` against `RAW_SINGLE` — the real component, interceptors,
`UnitOfWork` plumbing, JSON serialization and row mapping included, against hand-written SQL doing the same
work at the same granularity — is 1.04×. Optimising the interceptor chain or the operation objects would buy
essentially nothing. That is the opposite of what §2a's wording implied and what `I6` in the performance plan
concluded from it.

**Transaction granularity costs everything else**, and the claim is the expensive half: 33.6 s of
`RAW_SINGLE`'s 38.4 s is claiming, against 4.9 s acknowledging. A per-message claim is ~1.7 ms where a
batched one is ~12 µs per message. Two effects compound in it — the round trip and transaction itself, and
the dead tuples each claim-update and ack-delete leave behind for the next claim's index scan to walk.

**Today's production shape already avoids the worse half.** The centralized fetcher batch-claims, so real
deployments sit at `RAW_BATCH_CLAIM_SINGLE_ACK`: 4 837 ms, ~8× better than fully per-message but still
**14.9× worse than batched acknowledgement**. That 14.9× is the headroom `I6` is actually worth — an order of
magnitude more than the 1.13× §2a measured, which was bounded by the prototype's inability to remove the
unit of work rather than by the mechanism.

### What this does to the cursor result

The cursor attacks the claim phase, which production already batches, so its claim win should largely
survive. But its *total* is diluted by an acknowledgement cost that it does not touch and that production
pays at ~15× the prototype's rate. Taking the design plan's ordered arms and inflating only the ack column
by 14.9×:

| Ordered workload | Insert | Claim | Ack (prototype) | Ack (×14.9) | Total at production ack cost |
|---|---|---|---|---|---|
| v1 barrier | 4 175 ms | 10 647 ms | 702 ms | 10 460 ms | 25 282 ms |
| cursor | 2 240 ms | 2 646 ms | 1 023 ms | 15 243 ms | 20 129 ms |

**2.64× becomes ≈1.26×.** And the cursor's 46%-more-expensive acknowledgement, a good trade against eight
seconds of claim time in the prototype, is amplified by the same factor and turns into the dominant term.

This reorders the plan again. Batched acknowledgement is not an independent smaller win to be sequenced after
the cursor — **it is a precondition for the cursor to pay off at all.** With acks batched, the cursor's 2.64×
largely stands; without them, a 4.0× claim-phase improvement arrives as roughly 1.2×, which does not justify
the correctness surface a per-key cursor introduces.

### Caveats that bound §7

- **Run-to-run spread is wide** — ±40% on the per-message arms (`RAW_SINGLE` 27.1–39.1 s across three
  identical repetitions), because autovacuum timing against the dead tuples the drain itself produces
  dominates. These are order-of-magnitude results, not three-significant-figure ones. The 1.04× and the 118×
  are both far outside that spread; the 14.9× is not far outside it and should be re-taken before it is
  quoted as a target.
- **Two harness artefacts were found and fixed while measuring**, both of which had inflated the component
  arms in earlier runs. A single unit of work spanning the whole drain pins the xmin horizon and blocks
  reclamation, which made that arm degrade 5.7× across three identical repetitions; it now holds one per
  claim batch. And the component arms originally used the shared `DurableQueues` bean, whose table outlives a
  case, so accumulated dead tuples landed on whichever case ran later; each component case now builds its own
  instance on its own table. Both are recorded in the scenario's javadoc.
- **`unitsOfWorkPerMessage` is counted client-side, not read from the server.** Differencing
  `pg_stat_database.xact_commit` was tried first and produced values the call pattern cannot produce (0.0 for
  three arms, 6.56 where only 2.0 is possible) because PostgreSQL flushes backend statistics asynchronously.
- **Single-threaded, unordered, one node.** No consumer threading, no ordered barrier, no competing consumers.
  The transaction tax is a per-message constant so none of those should change it, but none of them were run.
