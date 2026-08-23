# DurableQueues v2 — design plan

Proposal for a durable-queue implementation that splits ordered and unordered messages into separate tables
behind a delivery-mode-aware consumer API.

**Status: open question, not a decision.** The evidence ledger below is the current state. It has been
revised as measurements came in; the ledger is authoritative, and this document is written so the current
position is stated once rather than reconstructed from a trail of corrections.

## Evidence ledger

Everything currently known, and how it was established. "Measured" means there is a reproducible test or
benchmark; "unmeasured" means nobody has run it.

### Settled — these no longer justify v2

| Claim | Status | Evidence |
|---|---|---|
| v2 would add cross-node per-key ordering | **False.** v1 already provides it | `PostgresqlOrderedMessagesMultiNodeIT` — two instances, own `Jdbi` each, no `FencedLock`; no same-key overlap and no out-of-order handling across 6 high-contention runs on both fetch strategies. Carries a negative control proving the detector fires |
| Splitting ordered/unordered is worth 5.4× on mixed backlogs | **True, but not a v2 argument** | Measured (measurements doc §1) — and already obtainable via `useOrderedUnorderedQuery=true`, which needs no new tables. Now the default on every construction path after the `queue_fix` branch |

### Measured — the write-cost question, answered

`QueueSchemaWriteCostScenario` / `QueueSchemaWriteCostBenchmarkIT`. Raw SQL against prototype schemas rather
than through `DurableQueues`, so the effect is not buried under the per-message connection acquisition known
to dominate. 200,000 messages, claim/ack in batches of 500, medians of 3 repetitions, PostgreSQL 17.5.

Consumer mode is a dimension because a split forces the choice: the consumer declares `UNORDERED` or
`ORDERED`, and those are now physically different tables.

| Arm | Secondary indexes | Insert | Claim | Ack | Total | Index MB |
|---|---|---|---|---|---|---|
| **UNORDERED** | | | | | | |
| v1 shared table | 6 | 2780 ms | 2526 ms | 726 ms | 6032 ms | 40.3 |
| v1 shared, `fillfactor=80` | 6 | 2946 ms | 2600 ms | 686 ms | 6191 ms | 41.0 |
| **split, unordered table** | **1** | **1717 ms** | **2004 ms** | 657 ms | **4369 ms** | 35.1 |
| **ORDERED** | | | | | | |
| v1 shared table | 6 | 4208 ms | 10663 ms | 724 ms | 15710 ms | 110.5 |
| v1 shared, `fillfactor=80` | 6 | 4349 ms | 11446 ms | 739 ms | 16447 ms | 109.9 |
| **split, ordered table** | **3** | **3047 ms** | 10929 ms | 693 ms | **14695 ms** | 89.7 |

**The win is real for unordered traffic and concentrated on writes: 1.38× overall, 1.62× on insert alone.**
Unordered messages stop paying maintenance on the three ordered-only indexes plus two more they never use.

**For ordered traffic the split buys almost nothing — 1.07×.** Insert improves 1.38×, but insert is a small
share of a workload whose claim phase costs 10.7 seconds. Splitting does not touch that.

Three secondary findings, each of which changes a decision:

1. **`fillfactor` is not an alternative.** Page headroom made no difference and was marginally worse. The
   cheap `ALTER TABLE` escape hatch does not exist.
2. **`n_tup_hot_upd` was zero in every arm, including the split.** The earlier framing — that the win comes
   from v1's claim update never being HOT — was wrong. Both schemas index `next_delivery_ts` and
   `is_being_delivered`, so neither can produce a HOT update. The win is purely **index write
   amplification**: fewer indexes to maintain per insert, update and delete.
3. **The single largest cost anywhere in the matrix is the ordered claim** — 10.7 s versus 2.5 s for the
   unordered claim on identical volume. That is the correlated `NOT EXISTS` barrier, and no table split
   touches it. It is what a per-key cursor would attack.

### Measured — the cursor prototype, and it is the biggest result here

Same harness, same 200,000-message ORDERED workload, medians of 3 repetitions. The `V2_CURSOR` arm replaces
the correlated `NOT EXISTS` barrier with an explicit per-key progress cursor
(`completed_through`, gap-tolerant "highest completed" rather than "next expected"), and drives the claim
from the key-state table via a `LATERAL` lookup instead of scanning candidates.

| ORDERED arm | Secondary indexes | Insert | Claim | Ack | Total | Index MB |
|---|---|---|---|---|---|---|
| v1 shared table (barrier) | 6 | 4175 ms | 10647 ms | 702 ms | 15483 ms | 110.5 |
| split ordered table (barrier) | 3 | 3110 ms | 10622 ms | 776 ms | 14492 ms | 89.6 |
| **cursor** | **1** | **2240 ms** | **2646 ms** | 1023 ms | **5865 ms** | **50.5** |

**The claim phase drops 4.0×, and the whole workload 2.64× against v1 — 2.47× against the split alone.**
Ordered claim at 2646 ms is now comparable to the *unordered* claim at 2004 ms: the ordered penalty
essentially disappears.

The mechanism is the one the SQL predicts. The barrier evaluates `NOT EXISTS (… key_order < mine)` against
every candidate row in the table; the cursor does one index-ordered lookup per key. Work becomes proportional
to key count rather than to queued-message count. It also removes the need for the two indexes that existed
only to serve the barrier — hence 1 secondary index instead of 3, and less than half the index bytes.

Acknowledgement gets **46% more expensive** (702 → 1023 ms), because the cursor row must be advanced as well
as the message deleted. That is a real cost and it is a good trade for eight seconds of claim time — but it
should not be quietly dropped from the summary.

**Superseded — see measurements §8.** The cursor arm measured here is **not correct**: its claim releases a
key's successor while the predecessor is in flight, and its acknowledgement can advance past a dead-lettered
message and lose it permanently. Both are reproduced by test. Corrected, the cursor's end-to-end win against
the barrier is **1.54×** rather than 2.38×, and its claim **2.18×** rather than 3.75×; gap-safety also puts
back the non-partial `(queue_name, key, key_order)` index the design claimed to delete. The case for the
cursor rested next on it unlocking batched ordered acknowledgement (~2.73×) — **also withdrawn, see
measurements §10**: deferring an ordered acknowledgement stalls the key under the cursor exactly as under the
barrier, because per-key exclusivity comes from `is_being_delivered` either way. What the cursor uniquely
enables is per-key *runs* — the barrier's per-row `NOT EXISTS` can only ever yield a key's head — whose value
depends on a run-length benchmark that has not been run. Read the table below as the original
measurement, not as current guidance.

**This reorders the whole v2 case.** The load-bearing change is the cursor, not the table split:

| Change | Ordered workload |
|---|---|
| Table split alone | 1.07× |
| Cursor (on a split table) | **2.47× on top** |

### Still open — no measurement

| Candidate benefit | Status |
|---|---|
| **Deferred ordered ack batching** | Structurally unblocked and expressible in one statement — `DELETE … RETURNING` feeding a cursor advance — which the barrier design cannot express at all. **Now measured as a precondition rather than a bonus:** acknowledging per message instead of per batch costs 16.5× on drain time [10.3–24.2 across 9 repetitions] (measurements doc §7), and inflating the cursor arm's ack column by that factor takes the cursor's end-to-end win from 2.64× down to ≈1.21×. The end-to-end effect of *deferring* acks across time for ordered messages is still itself unmeasured |
| **Independent partitioning and retention** | Not investigated |
| **Delivery-mode-aware consumer API** | Ergonomics and misuse-resistance; does not require two tables |

### Known defects in v1, independent of whether v2 happens

| Defect | Status |
|---|---|
| `queueMessages` threw on a mixed ordered/unordered list | **Fixed** — branch `queue_fix`, pinned by `PostgresqlDurableQueuesMixedBatchIT` |
| `useOrderedUnorderedQuery` defaulted `false` in the builder and `true` in the starter | **Fixed** — branch `queue_fix` |
| Duplicate `(queue_name, key, key_order)` is unconstrained and breaks per-key serialisation | **Open** — evidenced by the control in `PostgresqlOrderedMessagesMultiNodeIT`. A unique index would close it; needs a decision on what happens to the loser |
| `DurableQueues` javadoc overstates the multi-node limitation | **Open** — its worked example is not reproducible. Deliberately not softened until the recovery path (below) is tested |

### Measurement caveats that bound every number above

- The end-to-end queue throughput figures (ack batching, split-query speed-up) come from 4000-row tables.
  The write-cost figures above are from 200,000 rows. The two sets are not directly comparable, and only the
  latter says anything about index maintenance at scale.
- The write-cost prototype measures storage only — raw SQL, single connection, no consumers, interceptors or
  unit-of-work-per-message. That isolation is deliberate, but it means its ratios are an upper bound on what
  a full implementation would show, since the framework overhead it excludes is the same in both arms.
- The 1.13× ack-batching figure is a **lower bound**: the prototype could not remove the per-message unit of
  work, because `acknowledgeMessageAsHandled` wraps the interceptor chain in one. The measured direction —
  removing 96% of delete statements moved throughput 13% — says the per-message *operation* dominates, not
  the SQL.
- Untested: the `resetMessagesStuckBeingDelivered` recovery path, where a message reset while one node is
  still handling it becomes fetchable by another. This is now the most plausible source of the documented
  cross-node limitation, and it is the next test to write.

## Where the decision now stands

Both gating questions are answered.

1. **Is the index-maintenance win real?** Yes for unordered (1.38× total, 1.62× insert), no for ordered
   (1.07×).
2. **Would replacing the barrier with a cursor recover the ordered claim time?** Yes, decisively — 4.0× on
   the claim, 2.64× on the ordered workload end to end.

The evidence now supports a **narrower and better-targeted change than the original two-table proposal**:

- **The per-key cursor is the main event.** It is worth more than everything else in this document combined,
  it is what makes ordered delivery stop being the expensive case, and it is the only route to deferred ack
  batching for ordered messages. It is also the piece with the real correctness surface — see §8, and note
  that the fence-token machinery there is needed only if cursors are combined with cross-node key leasing,
  which §2.3 showed is not required.
- **The unordered table split is a separate, smaller, independently justified win** (1.38×) that needs
  nothing but its own table and a mode-aware consumer API.

Neither requires the full v2 as drafted below. A defensible scope is: a cursor-backed ordered path plus a
split unordered table, behind the mode-aware API — which is most of §3 and §4 and none of §8's leasing.

**This is now measured — see measurements doc §7 — and the answer changes the sequencing.** How much of the
prototype's 2.64× survives contact with the real component depends almost entirely on one thing, and it is
not the one §2a implied:

- **Transaction granularity is the dilution**, and the acknowledgement is the half production still pays per
  message: **16.5×** [10.3–24.2] against a batched acknowledgement. The claim is already batched by the
  centralized fetcher, which is the half the cursor improves. Two transactions per message rather than per
  batch costs **134×** [93–182].
- **The framework's own per-message cost is not established.** An earlier 3-repetition run put it at 1.04×
  and that figure has been withdrawn: at 9 repetitions the comparison reads 0.65×, because the component
  claims through its split unordered query while the raw comparison arm uses the v1 six-index claim. The two
  families are within noise of each other — the framework is not a large multiplier — but no number should be
  quoted. See measurements §7.
- **Therefore the cursor's 2.64× arrives as ≈1.21× on today's acknowledgement path**, with the cursor's own
  46%-more-expensive ack amplified by the same factor into the dominant term. With acknowledgement batched,
  the 2.64× largely stands.

So batched acknowledgement is not an independent, smaller win to be sequenced after the cursor — it is a
**precondition for the cursor to be worth its correctness surface at all**. The defensible scope below should
be read in that order: batch the acknowledgement first, then the cursor.

## 1. What v2 must keep working

The compatibility surface, since "coexist" means v2 is a peer implementation of the same SPI rather than a
new thing on the side.

**`DurableQueues` — 19 abstract operations.** All must be implemented with unchanged semantics:
`getDeadLetterMessage`, `getQueuedMessage`, `consumeFromQueue`, `queueMessage`,
`queueMessageAsDeadLetterMessage`, `queueMessages`, `retryMessage`, `markAsDeadLetterMessage`,
`markAsDeadLetterMessageDirect`, `resurrectDeadLetterMessage`, `acknowledgeMessageAsHandled`, `deleteMessage`,
`getNextMessageReadyForDelivery`, `getTotalMessagesQueuedFor`, `getQueuedMessageCountsFor`,
`getTotalDeadLetterMessagesQueuedFor`, `getQueuedMessages`, `getDeadLetterMessages`, `purgeQueue`.

**Downstream consumers**, none of which may need changing:

- `Inbox` / `Inboxes` / `Outbox` / `Outboxes` — the store-and-forward EIP layer is built on `DurableQueues`.
- `DurableLocalCommandBus` — durable command delivery.
- `postgresql-event-store` — uses queues in its own pipeline.
- The Spring starters (`spring-boot-starter-postgresql`, `-mongodb`, `-postgresql-event-store`).
- `foundation-test`'s `DurableQueuesIT` and friends — the shared cross-backend suite v2 must pass unmodified.

**Admin API — 12 operations** (`getQueueNames`, `getQueuedMessage`, `getQueueNameFor`,
`resurrectDeadLetterMessage`, `markAsDeadLetterMessage`, `deleteMessage`, `getTotalMessagesQueuedFor`,
`getTotalDeadLetterMessagesQueuedFor`, `getQueuedMessages`, `getDeadLetterMessages`, `purgeQueue`,
`getQueuedStatistics`). Per the root `CLAUDE.md`, each lives in three synced places — the `*Api` SPI, the
`EssentialsAdminApiSpec` mapping table, and a controller. v2 changes none of them; it only has to answer the
same questions across two tables instead of one. `getQueueNameFor(QueueEntryId)` is the one that gets harder
— see §3.4.

**Behavioural contracts** that are easy to lose in a rewrite:

- At-least-once delivery.
- Dead-letter blocking for ordered messages: a dead-lettered message blocks its successors, and a newly
  queued ordered message whose lower-order sibling is already dead-lettered is queued dead-on-arrival
  (`getQueueMessageSqlOptimized`).
- `TransactionalMode.SingleOperationTransaction` (default) and `FullyTransactional` (documented broken for
  retries/DLQ, but still supported).
- `DurableQueuesInterceptor` chain over every operation.
- `QueuePollingOptimizer` / `CentralizedQueuePollingOptimizer` backoff.
- LISTEN/NOTIFY wake-up via `MultiTableChangeListener` (Postgres).
- `DurableQueuesStatistics` and its TTL'd stats table.

## 2. Design goals, in priority order

Cross-node ordering was goal 1 in the original draft and is now void (see the ledger). The surviving goals:

1. **Fewer database operations per message, not fewer statements.** Eliminating 96% of delete statements
   while keeping a unit of work per message moved throughput 13%. The per-message *operation* — connection
   acquire, begin, commit, release — is the cost.
2. **Decouple "handled" from "row deleted".** The v1 ordered barrier infers completion from row absence,
   which is why batching acks makes ordered queues *slower* (0.82×).
3. **Per-table index sets** — the unmeasured candidate that is now the strongest performance argument.
4. **Keep the split-query win** already realised in v1 by `useOrderedUnorderedQuery`; two tables would make
   it structural rather than conditional.

Explicit non-goals: raw fetch-query speed (banked in v1) and cross-node ordering (already works).

### 2.1 Prerequisite: the dialect branch

`components/jdbc-queue-base` and `components/mssql-queue` exist on branch `mssql_durable_queues` (last commit
2026-03-11) and are **not in the reactor** — `pom.xml` lists only `components/postgresql-queue`. The working
tree holds orphaned `CLAUDE.md` and `.flattened-pom.xml` files from it, which is why they show as untracked.

That branch already extracted what a dialect-neutral v2 would need: `JdbcDurableQueuesSql` with dialect
hooks, `JdbcBatchFetchSupport`, `JdbcStuckMessagesResetSupport`, and `DialectDurableQueuesITBase`, an
abstract IT suite dialect modules subclass. If v2 proceeds, landing this comes first — designing against
`main`'s `postgresql-queue` and reconciling later means doing the dialect split twice.

**Open risk:** the branch is five months old and its MSSQL module reportedly has source only in git history.
Its mergeability is unestimated.

## 3. Proposed design

### 3.1 Three tables

- **`durable_queues_unordered`** — unordered messages only. No `key`/`key_order` columns, no ordered barrier,
  no ordered indexes. Fetch is `queue_name, next_delivery_ts` against one partial index.
- **`durable_queues_ordered`** — ordered messages only. Carries `key`/`key_order`.
- **`durable_queues_key_state`** — one row per `(queue_name, key)`: the ordering cursor plus the lease that
  provides cross-node exclusivity.

Dead letters stay in their originating table with `is_dead_letter_message = TRUE`, rather than moving to a
fourth table. Moving them would make `resurrectDeadLetterMessage` a cross-table move and complicate the
ordered barrier's interaction with dead letters, which is exactly the semantic we must preserve.

### 3.2 The key-state table is the heart of the design

```
durable_queues_key_state(
  queue_name        TEXT    NOT NULL,
  key               TEXT    NOT NULL,
  completed_through BIGINT  NOT NULL,   -- highest key_order fully handled; NOT "next expected"
  blocked           BOOLEAN NOT NULL,   -- a dead letter is holding this key
  leased_by         TEXT,               -- node id holding the key, NULL if free
  lease_expires_at  TIMESTAMPTZ,
  PRIMARY KEY (queue_name, key)
)
```

Three properties, each answering a specific risk raised in §5 of the measurements:

**`completed_through`, not `next_expected`.** `key_order` has no unique index and no contiguity requirement —
`OrderedMessage.of(payload, key, order)` accepts any `long`, and `deleteMessage` / `purgeQueue` / admin
dead-letter deletion can remove rows. A next-expected cursor wedges forever on the first gap. Tracking the
highest completed order and selecting "lowest `key_order` greater than `completed_through`" is gap-tolerant,
which is the property v1's `NOT EXISTS` had for free and which a naive cursor would throw away.

**`leased_by` / `lease_expires_at` give cross-node exclusivity.** Claiming a key is a conditional update:
take the lease if it is free or expired. This is what replaces the per-JVM `inProcessOrderedKeys` set, and it
is the whole correctness argument for v2. The lease is time-based, so a dead node's keys free themselves —
the same recovery shape as `resetMessagesStuckBeingDelivered`, but scoped per key instead of a global sweep.

**`blocked` preserves dead-letter semantics explicitly.** In v1 a dead-lettered row blocks successors purely
because it is still physically present. v2 makes that a declared fact rather than an emergent one, and it is
what lets acks be batched: a deferred delete no longer stalls the key, because the key's progress is recorded
in `completed_through` instead of inferred from row absence. This is the change that turns item 4 from
harmful-for-ordered into viable.

### 3.3 Fetch and acknowledge

**Unordered fetch** — one statement, unchanged in shape from v1's `buildUnorderedSqlStatement`, minus the
barrier and the `key_order` sort.

**Ordered fetch** — claim keys, then fetch their heads:

1. Lease up to *N* free-or-expired, unblocked keys for this queue (`UPDATE ... RETURNING`, `SKIP LOCKED`).
2. For each leased key, select the lowest `key_order > completed_through` that is ready.

Step 2 is an index lookup per key rather than a correlated `NOT EXISTS` over candidates. Note the payoff for
this is **unmeasured** — the measurements ran at 4000 rows, and this is precisely what scales with table size.
Phase 1 must quantify it before the rest is built.

**Acknowledge** — batched by default, and the two tables differ:

- Unordered: buffer ids, flush one `DELETE ... WHERE id = ANY(...)`.
- Ordered: buffer `(key, key_order)`, flush one statement advancing `completed_through` and releasing the
  lease, plus one delete of the handled rows.

Because ordered progress is recorded rather than inferred, deferring the delete no longer stalls the key —
the 0.82× penalty measured in v1 does not apply.

### 3.4 Consequences to accept

- **`getQueueNameFor(QueueEntryId)` becomes two lookups**, or requires the entry id to encode its table. Id
  encoding is preferable — a prefix or a distinct id space — because the admin API calls this on every
  message inspection. Decide in Phase 1; it affects the id format, which is hard to change later.
- **`queueMessages` with a mixed list writes to two tables in one unit of work.** The v1 bug (mixed batches
  threw `ClassCastException`) is fixed on `queue_fix`; v2 must keep the operation atomic across both tables.
- **`purgeQueue` must clear key-state**, or a purged queue leaves stale cursors that block the next message
  for those keys. This is the single most likely place to wedge a key.
- **The duplicate-delivery window widens** with batched acks, as analysed in §5.3 of the measurements. v2
  must validate the flush interval against `messageHandlingTimeout` at construction and fail fast, rather
  than leaving it to be discovered in production.

## 4. The new consumer API

Today `ConsumeFromQueue` carries a `QueuedMessageHandler` and the delivery mode is a property of each
message. v2 makes the choice explicit at subscription time, which is what allows a consumer to be routed to
one table and one fetch strategy:

- `consumeUnordered(...)` — unordered table only. No key leasing, no barrier.
- `consumeOrdered(...)` — ordered table only, with cross-node per-key exclusivity.
- Both — for consumers that genuinely need to see the whole stream.

Two constraints from the root `CLAUDE.md` shape this: **stable central APIs** (additive in minor/patch) and
**no `Optional` parameters in constructors, builders for anything non-trivial**. So the new methods are
additive on `DurableQueues`, defaulted to delegate to today's `consumeFromQueue` for existing
implementations, and each takes a builder-built parameter object rather than a widening argument list.

Whether cross-node ordering is on is a per-queue property of the ordered consumer, not a global switch — the
"opt-in per queue" shape, so a deployment can move one queue at a time.

## 5. Phasing

**Phase −1 gates everything and is not in this table:** answer the two questions in "The decision this
document is really asking for". Nothing below should start before they have numbers.

| Phase | Work | Exit criterion |
|---|---|---|
| **0** | Land `mssql_durable_queues`: assess mergeability, rebase, add both modules to the reactor, get `DialectDurableQueuesITBase` green on both dialects | Dialect split on `main`, full IT suite passing |
| **1** | Remaining unknowns: v1 fetch queries at 10⁵–10⁶ rows with `EXPLAIN (ANALYZE, BUFFERS)`; the `resetMessagesStuckBeingDelivered` cross-node recovery test; decide the `QueueEntryId` format | Scale behaviour known; recovery-path ordering known; id format decided |
| **2** | Schema and DDL, coexisting with v1. No data movement | Both implementations can run in one JVM |
| **3** | v2 against the existing 19-operation SPI, per-message acks | `DurableQueuesIT` and the full `foundation-test` suite green on both dialects |
| **4** | Batched acknowledgement for both tables, with flush-interval validation | Measured against the Phase −1 baseline; zero duplicate delivery under induced pause |
| **5** | New consumer API, admin API wiring, statistics, starter configuration | Admin API answers identically for v1 and v2 |
| **6** | Opt-in migration tooling, v1 → v2 | A deployment can move one queue at a time, live |

Per-key leasing and the fence-token machinery in §3.2 and §8 are **not** in this phasing. They existed to
deliver cross-node ordering, which v1 already provides. They return only if the Phase 1 recovery-path test
shows a gap that a cursor design would close.

## 6. Test strategy

The existing cross-backend suites are the main asset — v2 passing `foundation-test`'s `DurableQueuesIT`
unmodified is the strongest available evidence that "existing functionality" is intact.

New coverage v2 needs, none of which exists today:

- **The four ordered blocking states**, which §5.1 of the measurements flagged as under-covered: a successor
  must not be delivered while its predecessor is ready, in flight, awaiting retry, or dead-lettered. These
  are invariants of v1 too, and writing them first means they can be run against both implementations.
- **Cross-node ordering**: two `DurableQueues` instances on one database, one killed mid-key.
- **Gap tolerance**: sparse `key_order`, and orders removed by `deleteMessage` / `purgeQueue`.
- **Duplicate `(key, key_order)`**, which no constraint currently prevents.
- **Key-state leak checks**: after purge, after dead-letter, after resurrect, no stale cursor blocks a key.
- **Induced-pause duplicate delivery**: stall a flush past `messageHandlingTimeout` and assert the failure is
  loud rather than silent.

Perf-lab: `queue-design-ab` extends to a v1-versus-v2 arm, reusing the existing burst-drain harness and its
median-of-N-with-ranges discipline.

## 7. What could still sink this

- **Phase 0 is unestimated.** A five-month-old branch whose MSSQL sources may need reconstruction is the
  largest schedule unknown, and it gates everything.
- **Phase 1 could show the ordered lookup does not beat the correlated subquery** at realistic table sizes.
  That would not kill cross-node ordering, but it would remove the performance half of the justification.
- **Key leasing is a distributed lock in disguise** — see §8, which is the largest single design risk here.
- **Two implementations to maintain** for the deprecation window, across two dialects — four combinations in
  CI, on a suite that already dominates build wall-clock.

## 8. Key leasing is a distributed lock — what to take from `DBFencedLockManager`

### 8.0 Is v2 going to *use* `FencedLockManager`? No.

Stating this up front because the rest of this section reads like a proposal to, and it is not.
`FencedLockManager` is used here as a **reference implementation of a solved problem**, not as a component
v2 would depend on. `postgresql-queue` has no dependency on it today and v2 would not add one, for four
reasons:

1. **Cardinality.** A `FencedLock` is a row in `fenced_locks`, one per lock name. Per-key locking would mean
   one row per `(queue_name, key)` — millions — and release does not delete the row (it sets
   `locked_by_lockmanager_instance_id = NULL`), so the table would grow monotonically with key cardinality
   and never shrink.
2. **Its heartbeat is per-lock.** `DBFencedLockManager` runs a confirmation thread that periodically
   re-confirms every locally held lock. Holding thousands of key-locks means thousands of row updates every
   `lockConfirmationInterval` — a per-key, per-tick database operation, which is precisely what design goal 2
   exists to eliminate.
3. **Acquisition is a round trip.** Acquiring a lock costs a lookup plus an insert or update. Per message.
   v2's whole point is to make per-message database operations a function of batch size, not message count;
   this would add one back.
4. **Wrong lifetime.** It is built for long-lived singleton coordination — one node runs a subscriber for
   hours. A key lease lives for one message. Churn, not holding, is the dominant cost.

What v2 *should* take from it is the **fence-token compare-and-set pattern** (§8.1), the **settings-time
invariant validation** (§8.3), and the **node-pause test harness** (§8.6). Fencing tokens are a standard
distributed-systems technique, not a `FencedLock` invention; the reason to look here rather than at the
literature is that this codebase already contains a correct, tested implementation of one, and copying a
proven local pattern beats reinventing it.

Note also §2.2: if that open question resolves the other way, per-key leasing may not be needed at all, and
this entire section becomes moot. Do not build any of it before Phase 1 answers that.

---

The `leased_by` / `lease_expires_at` pair in §3.2 is a distributed lock with a timeout, whether or not it is
called one. Every hazard `DBFencedLockManager` guards against reappears — but note two differences that make
the queue case *harder*, not easier:

- **Cardinality.** `FencedLockManager` coordinates a handful of long-lived named locks. Key leases are one
  per `(queue_name, key)` — potentially millions, created and discarded continuously. Anything acceptable at
  N=10 needs re-examining at N=10⁶.
- **Lifetime.** A fenced lock is held for the life of a subscription. A key lease is held for one message —
  milliseconds. Lease *churn* is the dominant operation, not lease holding, which inverts what needs
  optimising.

### 8.1 The fence token is not optional, and §3.2 is currently missing it

This is the most important item in this document.

`DBFencedLock` carries `last_issued_fence_token`, monotonically increasing, and every storage operation is a
compare-and-set on it (`PostgresqlFencedLockStorage`):

- `updateLockInDB` — stealing a timed-out lock CASes on **both** the previous token and the previous
  `lock_last_confirmed_ts`.
- `confirmLockInDB` — renewal CASes on token and owner instance id.
- `releaseLockInDB` — release CASes on token.

The token exists because **lease expiry does not stop the old holder.** Node A's lease on key K expires
during a GC pause, a slow handler, or a network partition. Node B sees it expired, takes K, and begins
message `n+1` while A is still inside message `n`. Both nodes sincerely believe they hold K, and per-key
ordering — the entire correctness justification for v2 — is silently violated.

No timestamp scheme fixes this, because expiry is a judgement made by the *acquirer* while the *holder* is
unaware of it. The only fix is to make A's eventual write fail. So `durable_queues_key_state` needs a
`fence_token BIGINT NOT NULL`, incremented on every lease grant, and **every** write A might later perform
must CAS on it:

```sql
UPDATE durable_queues_key_state
   SET completed_through = :order, leased_by = NULL, lease_expires_at = NULL
 WHERE queue_name = :queueName AND key = :key AND fence_token = :tokenHeldByCaller
```

Zero rows updated means A's lease was stolen, and A must discard its result rather than record it. Critically
the *delete of the message row* has to be in the same CAS-guarded statement or transaction — otherwise A
deletes a message B has already re-fetched and is about to deliver, and the message is lost rather than
merely duplicated.

The design in §3.2 lists `leased_by` and `lease_expires_at` but no token. **That is a hole, and it is exactly
the hole the fenced-lock design already solved.** Fix it before anything else in Phase 4.

### 8.2 Clock skew: use database time, deliberately unlike the fenced lock

`DBFencedLockManager` computes timestamps with `OffsetDateTime.now(Clock.systemUTC())` — the **application
node's** clock, not the database's. Timestamps written by node A are later compared against a `now` computed
on node B. Skew therefore translates directly into wrong decisions: a node whose clock runs behind sees other
nodes' locks as fresher than they are and never steals them; a node running ahead steals live locks.

That is tolerable for fenced locks because `lockTimeOut` is typically seconds to minutes, so a few seconds of
skew is a small fraction of the window. Key leases invert this: they must be *short*, or a dead node wedges a
key for the whole lease duration. Short lease plus client clocks means skew is a large fraction of the
window.

**Recommendation: key leases should use database time** — `transaction_timestamp()` in Postgres,
`SYSUTCDATETIME()` in T-SQL — rather than client time. One clock, no skew. This costs nothing here because,
unlike the fenced lock's Java-side state machine, the lease is only ever read and written inside SQL. It is a
deliberate departure from the existing precedent and should be recorded as such so it does not look like an
inconsistency.

### 8.3 Expiry racing a slow handler, and a second timeout invariant

`lockConfirmationInterval < lockTimeOut` exists because a holder must renew before it expires. A key lease
has no equivalent renewal, because the holder is a message handler whose duration is user-controlled and
unbounded — that is what `messageHandlingTimeout` exists for. Three options:

1. Heartbeat-renew each leased key while its handler runs. Correct, but it is N background renewals for N
   in-flight keys — precisely the per-message database operation v2 exists to eliminate.
2. Make the lease duration at least `messageHandlingTimeout`. Ties two knobs together and makes a dead node
   wedge a key for the full handling timeout.
3. Accept expiry and rely on the fence token (§8.1) to render the stale holder harmless.

**Option 3 is right**, and it is the second reason the token is non-negotiable. It changes the failure mode
from *ordering violated* (unacceptable) to *message redelivered* (at-least-once, already the contract) — but
that must be documented, because handlers will see it.

There is then a **new invariant of the same family** as `lockConfirmationInterval < lockTimeOut`: the key
lease timeout and `messageHandlingTimeout` are two independent expiry mechanisms that must be ordered
deliberately. If a lease expires well before the message is reset, node B acquires the key but the message is
still `is_being_delivered = TRUE` and therefore unfetchable — the key is leased and idle until the global
`resetMessagesStuckBeingDelivered` sweep fires. The queue does not break; it stalls, which is harder to
diagnose.

Good precedent to copy exactly: `FencedLockManagerSettings` validates its invariant in the constructor and
throws `IllegalArgumentException`. v2's settings object should do the same for the lease/handling-timeout
relationship, so a misconfiguration is a startup failure rather than an intermittent stall.

### 8.4 Thundering herd and the release/re-acquire interleave

Two distribution problems, neither a correctness bug, both capable of destroying throughput:

- **Herd on free keys.** "Lease up to N free keys for this queue" means every fetcher on every node races for
  the same lowest-ordered free keys on the same 20 ms tick. `SKIP LOCKED` prevents blocking but not the
  wasted work. The claim needs either node-specific ordering or hash-partitioned selection so nodes prefer
  disjoint key ranges by default.
- **Re-lease interleave.** `foundation`'s documented fenced-lock hazard is that `release()` does not stop the
  async acquirer, and `releaseLock` runs outside the manager's `reentrantLock`, so release and re-acquire
  genuinely interleave. The analogue: a node that releases a batch of keys at flush time and is mid-fetch-tick
  can immediately re-lease the keys it just freed, before any other node observes them. The result is poor
  distribution — one node monopolises the hot keys — rather than incorrectness. A short per-key cooldown, or
  simply not re-leasing a key within the tick that released it, is enough.

### 8.5 Decisions the fenced lock already faced, which v2 inherits

- **What to do when the database is unreachable while holding a lease.**
  `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` defaults to `false`: keep the lock locally
  and risk split-brain, rather than drop it and risk losing work. The same choice arises per key, and with a
  fence token in place the safe answer is easier — keep processing, let the CAS reject the write if the lease
  moved.
- **Node identity.** `lockManagerInstanceId` defaults to the hostname, which is documented as collision-prone
  in containers. `leased_by` needs the same identity and inherits the same pitfall; v2 should reuse the
  existing id rather than invent a second one, and should fail loudly on a duplicate rather than defaulting
  to hostname.

### 8.6 The test harness already exists

`foundation-test`'s `DBFencedLockManagerIT` is the most directly reusable asset in this whole plan. It
already drives multi-node scenarios and calls `lockManagerNode1.pause()` to simulate **a long GC pause**
(around line 394) and to pause a node before releasing a lock (around line 322) — which is exactly the
scenario §8.1 is about: a holder that stops making progress without dying, while another node takes over.
`DBFencedLockManager_MultiNode_ReleaseLockIT` covers explicit handoff, and the Postgres IT disrupts the
database itself via Docker pause/unpause.

Phase 4's multi-node ordering tests should be modelled on these rather than written from scratch, and the
decisive test is the pause-driven one: pause node A mid-handler, let its lease expire, let node B take the
key and process the next message, then unpause A and assert its ack is **rejected** and the message is
redelivered rather than silently dropped or double-completed.

### 8.7 Stale documentation found while writing this

Both `components/foundation/CLAUDE.md` and `components/postgresql-distributed-fenced-lock/CLAUDE.md` state
that the `lockConfirmationInterval < lockTimeOut` invariant is unenforced ("`DBFencedLockManager` does not
enforce this", "no runtime guard; silent misbehavior if violated"). That is no longer true —
`FencedLockManagerSettings` validates it in the constructor and throws `IllegalArgumentException`. Corrected
in the same commit as this section.
