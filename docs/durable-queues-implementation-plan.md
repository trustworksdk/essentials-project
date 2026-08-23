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
| `DefaultQueuedMessage.getDeliveryMode()` hardcoded to `NORMAL` | The accessor contradicts the persisted `delivery_mode` column, and is inconsistent with Mongo's implementation, which reports `IN_ORDER` correctly. Any code trusting it silently treats ordered messages as unordered |
| `PostgresqlDurableQueues:1298` casts a `QueuedMessage` to `OrderedMessage` | Currently unreachable *because* of the defect above — `isOrderedMessage` is always false. Fixing the accessor without fixing this cast turns a cosmetic bug into a `ClassCastException` on dead-letter resurrection. **Fix together** |
| Duplicate `(queue_name, key, key_order)` is unconstrained | Two messages with the same key and order never block each other, so per-key serialisation breaks. Evidenced by the negative control in `PostgresqlOrderedMessagesMultiNodeIT`. A unique index closes it; needs a decision on what happens to the loser |
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
| Dead-letter to its own table | `is_dead_letter_message` from every remaining hot index and predicate; stops long-lived DLQ rows fragmenting hot pages; makes DLQ browse and resurrect cheap | Yes — a third table, orthogonal |
| Partition by `queue_name` | Per-queue index size; turns `purgeQueue` from `DELETE FROM … WHERE queue_name = :queueName` (`DurableQueuesSql:471`, the purge amplification the statistics doc flags) into an O(1) `TRUNCATE` of a partition | Yes |
| Per-table autovacuum settings | Nothing structural — it stops dead tuples accumulating faster than they are reclaimed. `pgmq` ships exactly this | Yes, and applicable today |

None of the last three has been measured. All three are arms in the existing
`QueueSchemaWriteCostScenario`, not new harnesses — which is the cheapest measurement available anywhere in this
plan.

### What each one needs

**Per-table autovacuum settings.** `autovacuum_vacuum_scale_factor` down (0.01), `autovacuum_vacuum_cost_delay`
0, a tuned `autovacuum_vacuum_insert_threshold`, applied in `initializeQueueTables()`. No contract change, no
migration, no API surface. This is the single cheapest item in the plan and it is also the one whose absence
distorted several measurements here: run-to-run spreads of 1.13–2.99× in §7 were dominated by autovacuum timing
against dead tuples the drain itself produced. **Worth doing first regardless of anything else**, partly because
it makes every subsequent measurement quieter.

**Dead-letter table.** Contract-preserving — same `DurableQueues` API, different storage. `markAsDeadLetterMessage`
becomes a move rather than a flag flip, `resurrectDeadLetterMessage` the reverse, and `getDeadLetterMessages`
stops scanning hot data. Two things to get right: the move must be atomic with the delete from the hot table,
and `getQueueNameFor(QueueEntryId)` plus the 12 admin-API operations must answer across both tables (the same
problem the v2 plan flagged for the ordered/unordered split — solve once, for three tables).

**Partitioning.** Note the interaction with the split: partition *within* each table by `queue_name`, not
instead of splitting. `purgeQueue` becoming `TRUNCATE` is the visible win; smaller per-queue indexes is the
larger one. Watch for the partition-count ceiling on deployments with many queues, and for the fact that
`resetMessagesStuckBeingDelivered` and the statistics queries currently scan across queues.

**Statistics trigger.** Already fully designed in `durable-queues-statistics-improvements.md` and unaffected by
any of the above — the `AFTER DELETE` trigger's `EXCEPTION WHEN OTHERS` subtransaction per acknowledged message
is the sharpest single item left in the codebase, and it lands on the same acknowledgement path batched
acknowledgement just optimised.

## 7. Sequence

Ordered by value per unit of risk, with the gate honoured.

Two tracks. The **storage track** (§6) is decided and mostly independent of the ordered-path question; the
**ordered track** (§4) is gated on a benchmark. They can proceed in parallel — they touch different things —
but the storage track is where the cheap, certain wins are.

**S0 — per-table autovacuum settings.** Cheapest item in the plan, no contract change, and it quiets every
later measurement. Do this first.

**S1 — measure the storage arms.** Dead-letter table and partitioning as arms in
`QueueSchemaWriteCostScenario`, alongside the split that is already there. Cheap; the harness exists. This is
what turns three plausible ideas into two or three known quantities.

**S2 — the statistics trigger.** Independently designed, independently valuable, no interaction with the rest.

**S3 — the ordered/unordered split**, with the mode-aware consumer API. Carries the measured 1.38× for
unordered traffic and is a precondition for the cursor's ordered table. Sequence the API decision early: it is
the part that touches `Inbox`/`Outbox`/`DurableLocalCommandBus` and the 12 admin operations.

**S4 — dead-letter table and partitioning**, in whichever order S1 says pays more. Solve
`getQueueNameFor(QueueEntryId)` and the admin surface once, across all tables, rather than per split.

**O0 — decide the two shipped defaults.** Batched acknowledgement (a semantic change: the redelivery window
widens by one flush interval) and batched fetch (needs a throughput measurement that does not exist).

**O1 — the cheap defects.** §5's first three. The accessor and the cast must land together; the unique index
needs a product decision.

**O2 — ~~the run-length benchmark~~ done, gate cleared (§11).** Remaining question is the default run length,
which needs a consumer-level measurement: run length trades round trips against per-key parallelism, and the
storage-only harness cannot see the second.

**O3 — the cursor. The gate passed, so this proceeds.** Key-state table plus enqueue upsert and reconciliation (inert but
exercised) → cursor claim behind a flag → run claiming with the prefix guard and caller-side sort → ordered
acknowledgement over runs.

**Not planned.** Deferred ordered acknowledgement in any form (§2 — impossible under any design considered).
WAL/CDC delivery for queues: claim and ack mutate rows, so WAL-tailing queue state is strictly worse than
NOTIFY. Both were non-goals in the original plan and remain correct.

## 8. How to read the evidence

Two habits produced most of the corrections above, and both are worth keeping:

- **A negative control on every invariant test.** Four separate green results in this investigation turned out to
  be assertions that could not fail, or harness artefacts. Each test that asserts "no violations" now has a
  sibling that forces one.
- **Distrust the reasoning, not just the code.** Every substantive claim in §3 and §4 was argued convincingly
  from reading SQL and then contradicted by running it — the in-flight check, the redundant guard, the
  one-per-key invariant, the prefix filter, and the payoff argument itself. The tests are cheap; the arguments
  were not reliable.
