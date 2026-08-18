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
| 3 | Add a state predicate to the ordered barrier, or replace it with a per-key cursor | §2b — the blocker for batched ordered acks and for cross-node ordering | Design work; correctness-sensitive | Open |
| 4 | Batch acknowledgement at a level that removes the per-message unit of work | §2a — the operation is the cost, not the statement | Medium; changes recovery timing | Open |
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

## 5. What has not been measured

- **Table size.** Every figure above is at 4000 rows. The correlated `NOT EXISTS` and the index-versus-seqscan
  crossover are exactly the things that change with table size, so §1's ratios should be re-taken at 10⁵–10⁶
  rows before the redesign is sized. `EXPLAIN (ANALYZE, BUFFERS)` on both fetch queries at that scale is the
  obvious next step and was not run.
- **Enqueue-side cost.** The drain clock starts after enqueueing completes; producer throughput and the
  `id TEXT PRIMARY KEY` random-insertion cost are untouched by these runs.
- **Real batched acks.** Only the interceptor-level lower bound was measured — see §2a.
- **Multi-node.** Single JVM throughout, so nothing here speaks to competing consumers across pods.
