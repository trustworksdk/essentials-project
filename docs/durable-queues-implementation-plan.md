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
| Table split is the headline, worth 5.4× | Already realised by `useOrderedUnorderedQuery=true`, now the default. Split's residual value is unordered-only: 1.38× total / 1.62× insert (6 indexes → 1). Ordered: 1.07× |
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

### The one number that gates implementation

**Run length has never been benchmarked.** The entire remaining case for the cursor is that a run of N amortises
one transaction across N ordered messages. Nobody has measured what that is worth, and §7 established that the
transaction is the cost — so this is the number the decision turns on, not the 2.18× claim.

> **Gate:** sweep run length {1, 4, 16, 64} for the cursor against the barrier baseline, on the ordered
> workload, reporting transactions per message alongside wall clock. If runs do not pay, the cursor is a 2.18×
> claim improvement plus an index-size win, which does not justify a schema migration and the plan should stop
> at §1.

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

## 6. Sequence

Ordered by value per unit of risk, with the gate honoured.

**P0 — finish what is shipped.** Nothing new. Decide whether batched acknowledgement's default flips (it is a
semantic change: the redelivery window widens by one flush interval), and whether batched fetch's default
flips (needs a throughput measurement, which does not exist). Both are one-line changes behind evidence that
has not been gathered.

**P1 — the cheap defects.** §5's first three. The accessor and the cast must land together. The unique index
needs a product decision, not just code.

**P2 — the statistics trigger.** Independently designed, independently valuable, no interaction with any of the
above. The subtransaction-per-ack is the sharpest single item left in the codebase.

**P3 — the run-length benchmark.** The gate in §4. Cheap: the prototype SQL exists and the harness exists. This
decides whether P4 happens at all.

**P4 — the cursor, only if P3 pays.** In this order, because each step is independently useful and independently
revertable:
1. Key-state table plus enqueue-time upsert and reconcile-on-empty-claim, with cursor claiming still off. Inert
   but exercised.
2. Cursor claim behind a flag, single-message. Rolling-deploy-safe per §4.
3. Run claiming, with the prefix guard and caller-side sort.
4. Ordered acknowledgement batching over runs — the payoff.

**Not planned.** The unordered table split (1.38×, and the cost is a new table plus a mode-aware consumer API —
revisit only if unordered insert cost becomes the bottleneck). Deferred ordered acknowledgement in any form
(§2). WAL/CDC delivery for queues (claim and ack mutate rows; the original plan's non-goal, still correct).

---

## 7. How to read the evidence

Two habits produced most of the corrections above, and both are worth keeping:

- **A negative control on every invariant test.** Four separate green results in this investigation turned out to
  be assertions that could not fail, or harness artefacts. Each test that asserts "no violations" now has a
  sibling that forces one.
- **Distrust the reasoning, not just the code.** Every substantive claim in §3 and §4 was argued convincingly
  from reading SQL and then contradicted by running it — the in-flight check, the redundant guard, the
  one-per-key invariant, the prefix filter, and the payoff argument itself. The tests are cheap; the arguments
  were not reliable.
