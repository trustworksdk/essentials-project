# DurableQueues — consolidated implementation plan

**This is the entry point.** Four separate documents accumulated during this investigation and they contradict
each other, because several load-bearing claims were withdrawn after being measured. This document is the
current position; where it disagrees with the others, it wins.

| Document | Role now |
|---|---|
| `durable-queues-redesign-measurements.md` | **Evidence of record.** All measurements, §1–§10. Cited throughout below |
| `durable-queues-v2-design-plan.md` | Historical design exploration. Its headline conclusions are superseded — read its ledger, not its recommendations |
| `durable-queues-performance-improvements.md` | The original I1–I10 plan, written before anything was measured. §0a lists what the measurements overturned |
| `durable-queues-statistics-improvements.md` | Independent, unaffected by any of this |

---

## 1. What is done

**Batched acknowledgement — shipped on this branch, off by default.** `DurableQueues.acknowledgeMessagesAsHandled`,
`BatchedAcknowledgementBuffer`, `BatchedAcknowledgementSettings`, a single-statement PostgreSQL override, wiring
into `CentralizedMessageFetcher`, and three Spring properties. Worth **16.5×** [10.3–24.2] on drain time.

Three things about it that must not be lost:

- **Unordered only.** Ordered messages are excluded and the exclusion is load-bearing (§2 below).
- **Two invariants enforced at construction**: requires `SingleOperationTransaction`, because the buffer relies
  on `resetMessagesStuckBeingDelivered` to recover acks lost before a flush; and `flushInterval` ≤ ¼ ×
  `messageHandlingTimeout`, or that same reset resurrects messages whose ack is merely buffered.
- **The discriminator is the wrapped `Message`, not `QueuedMessage.getDeliveryMode()`.** That accessor is
  hardcoded to `NORMAL` in `DefaultQueuedMessage` regardless of the persisted column, so using it buffered every
  ordered message. See §5.

**Multi-queue batched fetch — correctness gate cleared, still opt-in.** The "doesn't handle competing consumers
yet" notes were stale; the statement already re-checks `is_being_delivered` under `FOR UPDATE SKIP LOCKED`.
`PostgresqlBatchedFetchCompetingConsumersIT` establishes it with a negative control. Slot limits are now bound
rather than interpolated (prepared-plan reuse), and the builder guards its own contract. No throughput
measurement yet justifies flipping the default.

---

## 2. The finding that reshapes everything downstream

**Ordered per-key throughput is bounded by one committed round trip per message, under every design considered.**

Per-key ordering with at most one message in flight means a key's successor cannot be delivered until the
predecessor's completion is *durably recorded*. Any batching defers exactly that record, so the key stalls. The
barrier stalls by leaving the row present; the corrected cursor stalls because per-key exclusivity comes from
`is_being_delivered`, which a buffered ack leaves set. Both asserted side by side (§10).

Consequences:

- §7's 16.5× **does not reach ordered traffic** by enabling batching. The shipped feature's exclusion of ordered
  messages is not a limitation to be fixed later; it is correct.
- The only way to amortise a transaction across ordered messages is to claim **several messages of one key at
  once** and acknowledge the run together — which the barrier structurally cannot do, and the cursor can.

---

## 3. Withdrawn claims — do not resurrect these

Each was load-bearing at some point and each is now disproved. Listed because they are still written down in the
other documents.

| Claim | Why it is dead |
|---|---|
| Table split's *throughput* headline, 5.4× | Already realised by `useOrderedUnorderedQuery=true`, now the default. The split's measured residual is unordered-only: 1.38× total / 1.62× insert (6 indexes → 1); ordered 1.07×. **The split itself is still going ahead** — see §6, where throughput is not the reason |
| `fillfactor` tuning; "the claim update can never be HOT" | Measured dead. `n_tup_hot_upd` was zero in every arm — both schemas index the columns the claim writes, so neither can be HOT. Win is index write amplification |
| Framework overhead is 1.04× ("the framework costs 4%") | **Withdrawn.** At 9 interleaved reps it reads 0.65×, i.e. confounded: the component claims through the split query while the raw arm uses the v1 six-index claim. The two are within noise; no multiplier is quotable |
| `componentTransactionGranularity` 1.60× | Inconclusive: 0.79× [0.66–3.84], bounds straddling 1 |
| Cursor: 4.0× claim, 2.64× end to end | Measured on **incorrect** SQL (§4). Corrected: 2.18× claim, 1.54× end to end |
| Cursor unlocks deferred ordered ack batching (~2.73×) | **Withdrawn** — impossible under either design (§2) |
| I4: splitting gives ordered queues batch claim, "×slots per round trip" | Measured 1.07× for ordered. The ordered win, if any, is run-claiming |

---

## 4. The cursor: what is true, what is untested

### Established

- The originally measured cursor was **incorrect twice over**: its claim released a key's successor while the
  predecessor was in flight, and its ack advanced past a dead-lettered message and lost it permanently. Both
  reproduced against the SQL.
- **Corrected**: claim 2.18× faster than the barrier, 14.0 MB of index against 25.8 MB, 1.54× end to end
  storage-only. Correctness cost the claim +72%, the ack +85%, index bytes +30%.
- **Gap-safety needs a non-partial `(queue_name, key, key_order)` index** — the very index the design claimed to
  delete. The "one secondary index instead of three" claim does not survive; the index-bytes advantage does.
- The stateless per-key in-flight check **avoids the design plan's §8 leasing machinery entirely** — no leases,
  no expiry, no fence tokens. This is a significant simplification and it holds.
- **Rolling deploy works.** Barrier pods and cursor pods share one table safely (27 runs + negative control),
  because both key off the same physical rows and the only unshared state — `completed_through` — can only go
  stale-low, which is gap-tolerant. No flag-day; rollback equally safe.
- **Stranding is real and closed.** A key with no cursor row is invisible to cursor pods: never claimed, no
  error. Fixed by creating the row at enqueue (`ON CONFLICT DO NOTHING`) plus reconcile-on-empty-claim as the
  net. A pre-deploy backfill cannot close the window because the window *is* the deploy.
- **Per-key runs work**, with three constraints found only by testing: the run must be a prefix including blocked
  rows (a `bool_and` window truncates it); `UPDATE … RETURNING` does not preserve order, so the claim returns
  `key_order` and the caller must sort; and the ack is **coupled** to the claim — it scans
  `(cursor, min_acknowledged)`, which is sound only for prefix batches.

### The gate is cleared — measurements §11

Run-claiming pays, and the sweep found something larger than the thing it was looking for.

- **Runs cut round trips where predicted and nowhere else.** 53× fewer at 8 keys, 7.6× at 64, **nothing** at 500
  or 2 000 — once there is breadth to fill a batch from distinct keys, a run adds no rows. Optimum run length
  around 16.
- **The barrier has a pathological regime the cursor does not have.** Its claim costs 222 729 ms at 8 keys and
  2 500 messages per key, against 1 026 ms for the cursor doing the *identical* number of round trips — **217×**.
  The correlated `NOT EXISTS` rescans a key's depth per candidate, and the per-row barrier returns only one row
  per key per round.
- **The 2.18× in §8 was measured in the barrier's best regime** (1 000 keys, 200 per key). The cursor's advantage
  is a function of backlog depth per key: 26–217× on the claim when keys are few and deep, ~2.6× when keys are
  many and shallow.

For an event-sourced workload — `key` is an aggregate id, a busy aggregate accumulates thousands of events —
few-keys-deep-backlog is the normal case, not the corner. **That is what makes the cursor worth building**, and
it is a better reason than any previously recorded.

Not settled by the gate: run length interacts with worker parallelism in a way the storage-only harness cannot
see. A run of 16 to one worker is 16 messages handled sequentially by that worker, so a long run may reduce
parallelism on a hot key. Pick the default run length with a consumer-level measurement, not from §11.

---

## 5. Defects found along the way, none yet fixed

Independent of any redesign. Each is recorded here because they were found while doing something else and would
otherwise be lost.

| Defect | Consequence |
|---|---|
| ~~`DefaultQueuedMessage.getDeliveryMode()` hardcoded to `NORMAL`~~ | **Fixed.** Now derived from the wrapped `Message`, so it cannot disagree with the persisted column. `DefaultQueuedMessageDeliveryModeTest` |
| ~~`PostgresqlDurableQueues` casts a `QueuedMessage` to `OrderedMessage`~~ | **Fixed, together with the accessor as required.** The cast is now on the wrapped message. Pinned by `PostgresqlOrderedDeadLetterResurrectionIT`, which covers a path that had no test |
| ~~Duplicate `(queue_name, key, key_order)` is unconstrained~~ | **Fixed.** `OrderedMessageDuplicateStrategy`, default `REJECT`, enforced by a partial unique index. Default is safe because every framework-produced ordered message keys on aggregate id and orders by `EventOrder`; no evidence in the repo of anyone relying on duplicates. Startup fails loudly on pre-existing duplicates rather than running unprotected. `ALLOW` restores the old behaviour |
| `useOrderedUnorderedQuery` default documented as `false` in places | Now `true` everywhere. `LLM-postgresql-queue.md` corrected; check other consumers |
| Statistics `AFTER DELETE` trigger | Per-ack plpgsql invocation, insert, two index updates, and an `EXCEPTION WHEN OTHERS` subtransaction. Fully designed in `durable-queues-statistics-improvements.md`, unimplemented |

---

## 6. The storage track — the split and the three changes that go with it

**The ordered/unordered table split is a decision, not an open question.** It was scored earlier purely on
throughput, where its residual is 1.38× total / 1.62× insert for unordered traffic (§3), and that was the wrong
yardstick. It is also the mode-aware consumer API, per-table index sets, and the ordered table the cursor needs
regardless — and it is the natural home for the three storage-level changes below.

### Why these belong together

Two levers have measured as significant in this entire investigation: **transaction count per message** (§7 of
the measurements — 16.5× on ack alone) and **index write amplification** (the split's own 1.38×, and the
cursor's index-bytes win). Batched acknowledgement attacked the first. Everything in this section attacks the
second, and they compose:

| Change | What it removes | Independent of the split? |
|---|---|---|
| Ordered/unordered split | 6 secondary indexes → 1 for unordered traffic. Measured 1.38× | — |
| Dead-letter to its own table | **Measured modest (§12)**: 1.0–1.2× on operations and *no* index-size win — one boolean out of one index and four predicates is not the same lever as five whole indexes. Justify it on contract-preserving cleanliness, cheaper DLQ browse/resurrect and 4% smaller heap, not on throughput | Yes — a third table, orthogonal |
| ~~Partition by `queue_name`~~ | **Rejected on evidence (§12)** — 30% worse acknowledge-by-id, 40% worse claim. The API is keyed by `QueueEntryId` alone, so no by-id operation can name a partition and each probes every one. Not viable without `(queueName, id)` addressing, which is a breaking change to a stable API | — |
| ~~Per-table autovacuum settings~~ | **Measured inert (§13)** — zero autovacuums ran on a default cluster while 440k dead tuples accumulated, because `autovacuum_naptime` binds and it is a *cluster* setting Essentials cannot set. And no degradation appeared to fix: drain cost was flat over 12 cycles in every arm. Ship it if you like; do not call it a performance fix | — |

None of the last three has been measured. All three are arms in the existing
`QueueSchemaWriteCostScenario`, not new harnesses — which is the cheapest measurement available anywhere in this
plan.

### What each one needs

**Per-table autovacuum settings — measured, and they do not pay (§13).** Zero autovacuums ran on a
default-naptime cluster across twelve cycles and 440 000 dead tuples, in every arm including the aggressive one:
the per-table threshold is not what binds, `autovacuum_naptime` is, and that is a cluster setting Essentials
cannot reach from its DDL. Nor was there degradation to prevent — drain cost was flat across all twelve cycles.
<br><br>
The bloat concern is not wrong, but its cause is elsewhere. The two damaging effects actually observed were
**xmin pinning** (a transaction held across a drain, 5.7× degradation, fixed by holding one per batch) and
autovacuum firing at unpredictable times, which was a *measurement* hazard. So the levers are: do not hold long
transactions, and document naptime for operators. Shipping the parameters is harmless and helps a tuned cluster;
it is not a performance fix and must not be sequenced first.

**Dead-letter table.** Contract-preserving — same `DurableQueues` API, different storage. `markAsDeadLetterMessage`
becomes a move rather than a flag flip, `resurrectDeadLetterMessage` the reverse, and `getDeadLetterMessages`
stops scanning hot data. Two things to get right: the move must be atomic with the delete from the hot table,
and `getQueueNameFor(QueueEntryId)` plus the 12 admin-API operations must answer across both tables (the same
problem the v2 plan flagged for the ordered/unordered split — solve once, for three tables).

**Partitioning.** Note the interaction with the split: partition *within* each table by `queue_name`, not
instead of splitting. `purgeQueue` becoming `TRUNCATE` is the visible win; smaller per-queue indexes is the
larger one. Watch for the partition-count ceiling on deployments with many queues, and for the fact that
`resetMessagesStuckBeingDelivered` and the statistics queries currently scan across queues.

**Statistics trigger — measured (§14).** Enabling statistics costs **2.80×** on the acknowledgement path, so the
feature being off by default remains the most effective mitigation shipped. The designed Java-side observer is
worth building — **1.34×** against the trigger — but it is not a fix: the dominant cost is the `INSERT` plus two
index updates, which any mechanism pays.
<br><br>
The plan previously called the `EXCEPTION WHEN OTHERS` subtransaction "the sharpest single item in the codebase".
That is **half right**: the mechanism is confirmed — ~48 000 subtransaction SLRU hits per 50 000 rows against
~1 000 without — but it is only **1.03×** of wall clock and produced **zero** SLRU writes. A latent hazard under
real concurrency, not the reason the trigger is slow. The stronger case for the rewrite is the other five defects
in the improvements doc: purge amplification, dialect portability, DDL on a table it does not own, the
unqualified colliding function name, and the broken `delivery_latency` read path.

## 7. Sequence

Ordered by value per unit of risk, with the gate honoured.

Two tracks. The **storage track** (§6) is decided and mostly independent of the ordered-path question; the
**ordered track** (§4) is gated on a benchmark. They can proceed in parallel — they touch different things —
but the storage track is where the cheap, certain wins are.

**S0 — ~~per-table autovacuum settings~~ measured and demoted (§13).** Inert on a default cluster; no
degradation existed to fix. What remains is documentation: naptime guidance for operators, and the standing rule
not to hold a unit of work across a drain.

**S1a — act on the index-usage evidence (§16), but note which half lasts.** The measurement produced two
findings with very different shelf lives, and they should not be sequenced together.

*Design input to the new tables — do this as part of S3, not before it.* `idx_*_ordered_ready` is dead **in its
own mode**, and it is carried into the split's ordered table (`_b` in `v2OrderedTableDdl`, one of the three that
arm was measured with). So **the split's ordered table should ship with two indexes, not three** — which also
means the split's measured 1.38× was obtained while carrying an index nothing reads. Confirm against a second
workload first, since §11 showed the ordered plan is highly sensitive to messages-per-key.

*Transitional housekeeping — optional, and bounded by v1's lifetime.* Creating the unified-query indexes only
when `useOrderedUnorderedQuery` is `false`, rather than unconditionally, removes 43% of index maintenance for
existing deployments. Its value expires when v1 storage does — but the project deprecates rather than deletes and
defers removal to a major, so that window is likely a full major cycle rather than a release, for a conditional
around six existing `createIndex` calls.

*The reusable part is the diagnostic, not the diet.* `PostgresqlIndexUsageIT` drives the whole SPI and reports
real scan counts. **Point it at each new schema before that schema ships.** Inheriting an index set by inspection
is exactly how `ordered_ready` came to exist, and the report is what catches it.

**S1 — ~~measure the storage arms~~ done (§12).** Partitioning by `queue_name` is rejected; the dead-letter
table is a small positive to be justified on grounds other than throughput. The one thing left unmeasured here
is the autovacuum setting itself, which S0 should quantify while it is being chosen.

**S2 — the statistics trigger.** Measured (§14): 1.34× available from the rewrite, and the correctness defects
are the better justification. Still worth doing, no longer on a performance claim. If statistics cost matters to
a deployment, drop an index on the stats table or sample — both beat changing the mechanism.

**S3 — the ordered/unordered split**, with the mode-aware consumer API. Carries the measured 1.38× for
unordered traffic and is a precondition for the cursor's ordered table. Sequence the API decision early: it is
the part that touches `Inbox`/`Outbox`/`DurableLocalCommandBus` and the 12 admin operations.

**S4 — dead-letter table**, if its non-throughput benefits are wanted. Partitioning is out (§12). Solve
`getQueueNameFor(QueueEntryId)` and the admin surface once, across the ordered/unordered/dead-letter set,
rather than per split.

**O0 — decide the two shipped defaults.** Batched acknowledgement (a semantic change: the redelivery window
widens by one flush interval) and batched fetch (needs a throughput measurement that does not exist).

**O1 — the cheap defects. Done**, apart from the doc-drift sweep. The accessor and the cast landed together as
required, and the unconstrained duplicate is now `OrderedMessageDuplicateStrategy` with `REJECT` as the default.

**O2 — ~~the run-length benchmark~~ done, gate cleared (§11).** Remaining question is the default run length,
which needs a consumer-level measurement: run length trades round trips against per-key parallelism, and the
storage-only harness cannot see the second.

**O3 — the cursor. The gate passed, so this proceeds.** Key-state table plus enqueue upsert and reconciliation (inert but
exercised) → cursor claim behind a flag → run claiming with the prefix guard and caller-side sort → ordered
acknowledgement over runs.

**Not planned.** Deferred ordered acknowledgement in any form (§2 — impossible under any design considered).
WAL/CDC delivery for queues: claim and ack mutate rows, so WAL-tailing queue state is strictly worse than
NOTIFY. Both were non-goals in the original plan and remain correct.

## 8. Investigation backlog — ideas not yet tried

Everything that has measured as significant reduced to two levers: **transactions per message** (§7: 16.5× on
acknowledgement, 134× for two-per-message versus two-per-batch) and **index write amplification** (§8, §12: the
1.38× from six secondary indexes down to one). These are the untried ideas that attack one of those two.
Anything that attacks neither is in §3 or the "skip" list below.

| # | Idea | Lever | Why it might pay | Cost to test |
|---|---|---|---|---|
| ~~**B1**~~ | ~~**Write-once message row.**~~ **Rejected — measured (§15): 12% worse than the split.** The lever was right and the mechanism wrong: churn leaves the message table (8% fewer index bytes) but the in-flight table brings its own two indexes and the acknowledgement gains a second table to delete from, costing 51% there. Kept in the table because its failure is the argument for B4. In-flight state (`is_being_delivered`, claim-time `total_attempts`) moves to a small side table keyed by id; the message row is inserted and deleted, never updated | Both | Today every message is INSERT → UPDATE → DELETE, and the claim writes two *indexed* columns — which is exactly why `n_tup_hot_upd` was zero in every arm measured. Removing the update takes three row versions to two and takes the claim's index churn off the large table entirely. Also makes the stuck-message reset a scan of a small table | One arm in `QueueSchemaWriteCostScenario` |
| **B2** | **Handler inside the claim transaction, with a savepoint around the handler only.** One transaction per message instead of two | Transactions | The largest remaining win on the dominant lever. This is nominally `FullyTransactional`, which is correctly documented as broken because a rollback loses the attempt increment — but §14 showed a savepoint is cheap when it is per *failure* rather than per row, and failures are rare. **The risk is real and must be measured, not assumed**: it holds a connection and pins the xmin horizon for the handler's duration, which is the mechanism behind §7's 5.7× artefact. The deliverable is a crossover curve against handler duration, not a yes/no | Consumer-level scenario; more work than B1 |
| **B3** | **Index diet on the current table, no migration.** | Index writes | **Measured and it pays (§16).** Two of the six indexes are never scanned across the entire SPI — `idx_*_ready` and `idx_*_ordered_ready` — holding **43% of the table's index bytes**, maintained on every insert, claim and delete. All six are created unconditionally regardless of `useOrderedUnorderedQuery`, so a default deployment maintains indexes for a query it never runs. **Ready to implement**: make creation conditional on the flag | Done |
| **B4** | **Advisory-lock claim** (`pg_try_advisory_xact_lock(hashtext(id))`) instead of marking the row | Both | **Promoted by B1's failure (§15).** Same idea, without either cost that sank B1: no write on claim *and* no second table in the acknowledgement path. Requires the lock to span the handler, which ties it to B2 — the two are one experiment, not two. Now the most promising untried idea | Consumer-level, with B2 |
| **B5** | **NOTIFY-driven wake-up replacing the fixed tick** (I1/I2 in the improvements plan) | Neither — *latency* | Everything measured in this investigation is throughput. This is the only remaining item targeting enqueue-to-delivery latency, estimated 400 ms → <10 ms p99 idle with a ~95% cut in idle claim statements. Fully designed, unimplemented, unmeasured | Already specified; implementation, then a PROBE scenario |
| **B6** | **Hash-partition by `id`**, if partitioning ever returns | Index writes | Recorded so the idea is not re-proposed on the wrong axis. Partitioning by `queue_name` failed (§12) because by-id operations lost the primary key; hashing by `id` prunes for every by-id operation instead — but then the claim, which filters by `queue_name`, scans every partition. Probably net-negative; the point is that `id` is the only defensible axis | Low priority |

**Skip, on evidence:** `fillfactor` and HOT tuning (§3, measured dead), per-table autovacuum parameters (§13,
inert on a default cluster), partitioning by `queue_name` (§12, rejected), optimising the interceptor chain or
operation objects (§7, not measurable above noise), deferred ordered acknowledgement in any form (§2, impossible
under any design considered), and WAL/CDC delivery for queues (claim and acknowledge mutate rows).

## 9. How to read the evidence

Two habits produced most of the corrections above, and both are worth keeping:

- **A negative control on every invariant test.** Four separate green results in this investigation turned out to
  be assertions that could not fail, or harness artefacts. Each test that asserts "no violations" now has a
  sibling that forces one.
- **Distrust the reasoning, not just the code.** Every substantive claim in §3 and §4 was argued convincingly
  from reading SQL and then contradicted by running it — the in-flight check, the redundant guard, the
  one-per-key invariant, the prefix filter, and the payoff argument itself. The tests are cheap; the arguments
  were not reliable.
