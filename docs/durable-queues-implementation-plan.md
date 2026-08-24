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

**S2 — the statistics trigger. Done, and it went further than planned** because the backward-compatibility
constraint was lifted: the feature was off by default with no users, so it was changed outright rather than phased
behind a mode enum. Collection moved into Java via a `DurableQueueMessageObserver` reached through a new default
method on `DurableQueues`, with an in-memory registry behind it; the starter now hands the queue the observer
instead of handing statistics the queue, so enabling statistics involves no DDL at all. The trigger-based
implementation is deprecated for removal and wired by nothing. Details in
`docs/durable-queues-statistics-improvements.md`. **The split gets statistics for free** — collection is keyed by
`QueueName`, which both of its tables share — which closes the gap §7f left open. Not built: a durable sink, so
statistics reset on restart.

**S3 — the ordered/unordered split. Increment 1 done (§7d).** `PostgresqlSplitDurableQueues` composes two v1
storage instances over `<base>_unordered` / `<base>_ordered`, and `foundation-test`'s shared `DurableQueuesIT`
passes against it unmodified. The mode-aware consumer API was **not** built and is not needed (§7a). Increment 2
is the admin surface, and remains the review boundary.

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

**O3 — the cursor. Implemented as an opt-in flag, and the first end-to-end measurement is deflating — see §7h.**

**O3 — the cursor (original entry).** Key-state table plus enqueue upsert and reconciliation (inert but
exercised) → cursor claim behind a flag → run claiming with the prefix guard and caller-side sort → ordered
acknowledgement over runs.

**Not planned.** Deferred ordered acknowledgement in any form (§2 — impossible under any design considered).
WAL/CDC delivery for queues: claim and ack mutate rows, so WAL-tailing queue state is strictly worse than
NOTIFY. Both were non-goals in the original plan and remain correct.

## 7a. S3 design decision: the split carries no new consumer API

The v2 design plan and the improvements plan both assume the split needs a **declaration** — a
`QueueType {UNORDERED, ORDERED, MIXED}` and a `configureQueue(QueueName, QueueType)` — so a consumer can say
which physical table it reads. That is the expensive half of S3: it is new public surface on a stable central
API, and it propagates into `Inbox`, `Outbox`, `DurableLocalCommandBus` and all 12 admin operations.

**Decision: increment 1 splits storage transparently and adds no declaration API.**

The store routes on write — an `OrderedMessage` goes to the ordered table, anything else to the unordered one —
because the message already says which it is. Nothing needs to be declared for the *storage* win, and the storage
win is the entire measured benefit: 1.38× total and 1.62× insert for unordered traffic, all of it from index
count (six secondary indexes down to one).

What the declaration would actually buy is narrower than it looks: it lets a consumer skip querying the table it
knows is empty, saving one round trip per poll on a single-mode queue. Three reasons not to buy it yet:

1. **It is measurable, and unmeasured.** The cost it avoids is one extra claim statement per poll cycle. §7 says
   the transaction is what costs, so this is a real but small effect — and nobody has put a number on it.
2. **The same saving is available without API surface.** Track per queue whether an ordered message has ever been
   seen (seeded lazily with one `EXISTS` on first fetch) and skip the ordered statement when it has not. That is
   already written down as step 4 of I4 in the improvements plan, it is automatic, and it needs no declaration.
3. **The declaration's other benefit is a misuse guard**, not performance — failing fast when an `OrderedMessage`
   is queued to a queue declared `UNORDERED`. Worth having eventually; not worth coupling the storage change to.

So the sequence inverts relative to the plans: **split storage first, measure whether the double query matters,
and add the declaration only if it does.** If it never matters, the split ships with no new public API at all,
which for a library whose central APIs are stable-by-contract is the better outcome.

Increment 1 is therefore: the two tables with per-mode index sets (**two indexes on the ordered table, not
three** — see §16), transparent write routing, both tables read by every query operation, and
`getQueueNameFor(QueueEntryId)` answering across them. The admin surface is increment 2, and is where the
review boundary sits.

## 7b. S3 design decision: by-id operations query both tables, and the id stays opaque

Splitting storage creates one genuinely new problem. The `DurableQueues` SPI addresses messages by
`QueueEntryId` **alone** — `acknowledgeMessageAsHandled`, `deleteMessage`, `getQueuedMessage`, `retryMessage`,
`markAsDeadLetterMessage`, `resurrectDeadLetterMessage`, `getQueueNameFor` — so with two tables the store no
longer knows which one an id lives in. This is the same structural issue that sank partitioning by `queue_name`
(§12), so it deserves an explicit answer rather than an assumption.

**Rejected: encoding the delivery mode into the `QueueEntryId`.** A generated id could be prefixed so every
by-id operation routes to exactly one table in O(1). It is tempting and it is the wrong trade: it makes an
opaque public value type structured, so ids become a format with rules, and anything that ever round-trips or
constructs one inherits those rules. The upside it buys is one statement rather than two.

**Chosen: try both tables, in one transaction.** One extra statement per by-id operation, and none of the
statements are per-message any more where it counts — batched acknowledgement (shipped) means the hot path is
`id = ANY(...)` against each table, so the cost is two statements *per batch*, not per message.

The reasoning is the central finding of this whole investigation: **the transaction is what costs, not the
statement.** §7 measured one transaction per acknowledgement at 16.5× against one per batch, and §14 measured the
statistics trigger's extra per-row `INSERT` at 2.80× — but two statements inside a single transaction is still one
transaction. Paying a statement to keep a public type opaque is the right direction, and it is the direction the
measurements support rather than merely the tidier one.

Consequence for `getQueueNameFor(QueueEntryId)`, which the v2 plan flagged as the hard one: it is simply the same
dual lookup, and needs no special handling.

## 7c. S3 design decision: split the indexes, keep the columns — and reuse v1's statements

An earlier version of the split schemas dropped the columns each table does not need: no `key`, `key_order` or
`delivery_mode` on the unordered table, and `NOT NULL` on the ordered one's. That was reverted, and the reason
reshapes how S3 gets built.

**Every one of v1's statements references those columns.** `claimUnorderedSql` filters on `key IS NULL`; the row
mapper reads `delivery_mode` and reconstructs an `OrderedMessage` from it. A narrower table would therefore mean
rewriting and re-testing the entire SQL surface — nineteen operations' worth — to save roughly nine bytes a row.

**And the columns were never what the win was attributed to.** The split's 1.38× total and 1.62× insert are
index-count effects (measurements §1, §8): six secondary indexes down to one. Column width was not measured to
matter on its own.

So the split varies **only the index set** per mode and leaves the columns identical to v1's. That has a large
consequence for the implementation: **each split table can be driven by v1's existing, tested statements
unchanged.** The split becomes a composition over two configured storage instances rather than a rewrite, which
is a materially smaller and lower-risk change than either plan assumed.

One consequence to carry into the next increment, which §7a's no-declaration decision makes sharper than
expected: consumption has to serve two tables under **one** `parallelConsumers` budget. Registering a consumer
per table would double in-flight work and break that contract. The fix is that the composite implements
`BatchMessageFetchingCapableDurableQueues` and owns a single `CentralizedMessageFetcher`, merging results from
both tables — the fetcher already computes slots as `maxParallelConsumers - activeWorkers` per queue, so one
fetcher over the composite gets a shared budget for free. The delegates are storage only, with no consumers of
their own.

## 7d. S3 increment 1 as built, and the two things it exposed

`PostgresqlSplitDurableQueues` is a `BatchMessageFetchingCapableDurableQueues` composing two
`PostgresqlDurableQueues` instances — one per table — created with the new `Role.SPLIT_DELEGATE`, which says both
that the composite owns their DDL and that a by-id miss is expected rather than an error. Configuration is
`PostgresqlSplitDurableQueuesSettings` (a record, so the parameter ceiling does not apply) plus a builder;
its defaults are v1's, so moving a deployment onto the split changes the storage layout and nothing else.

**The acceptance gate held: `foundation-test`'s shared `DurableQueuesIT` passes unmodified** —
`PostgresqlSplitDurableQueuesIT` contains only wiring, no test methods and no relaxed assertions. That suite
proves the split is indistinguishable through the SPI, which is also why it cannot prove the split is happening;
`PostgresqlSplitDurableQueuesRoutingIT` does that part, asserting per-table row counts, positional ids across a
mixed batch, an ordered message acknowledged by id alone, and one consumer draining both tables exactly once.

§7c's "reuse v1's statements unchanged" held, but **not quite as stated: reusing the statements was not enough,
because v1 had the polling registry welded to the claim.** `fetchNextBatchOfMessages` filtered queues by
consulting its own `durableQueueConsumers` map and its own polling optimizers, then claimed, in one method. The
composite owns the registry and the optimizers while the delegates own the tables, so asked through their public
fetch methods each delegate consulted its own empty registry and skipped every queue. Splitting that into
`selectQueuesReadyForPolling` / `claimNextBatchOfMessages…` / `reportPollingOutcome` — a consumer concern, a
storage concern, a consumer concern — is what actually made the composition work, and it made v1's two fetch
variants symmetric as a side effect. Worth generalising: the "reuse the existing statements" plan understated the
work because a statement is not the same thing as the method around it.

Two defects the increment surfaced, both in code written earlier in this branch:

1. **`initializeSchema` skipped the JDBI type-mapper registration**, which sat inside the same method as the DDL.
   Every by-queue-name read on the split failed with `NoSuchMapperException: No mapper registered for type
   QueueName`. Registration is not schema initialization — it is how the instance can issue any statement at all —
   and it now runs unconditionally. Caught by the shared suite, on its first run.
2. **The four "not found" log sites logged at ERROR**, which is right for a standalone instance and wrong for a
   split delegate: the composite addresses every id at both delegates, so exactly one is expected to miss. Left
   alone, an ordinary ordered-message retry would have emitted a spurious ERROR line on every delivery. This is
   why the flag became a `Role` rather than staying a boolean: "does not own its table" and "holds only half the
   messages" are the same fact, and a boolean named after the first would have kept hiding the second.

Out of increment 1 and **since closed by B5(a)**: LISTEN/NOTIFY wake-up. The trigger is installed inside v1's
`initializeQueueTables()` under `multiTableChangeListener.ifPresent(...)`, which the delegates do not run, so the
split initially polled at its fixed interval with `QueuePollingOptimizer.None()` — correct, but a silent latency
regression for anyone moving onto the split with a listener configured. The composite now owns that wiring; see
§7e for what it did and did not borrow from the event-store prior art. The admin surface remains increment 2.

## 7e. What B5 can borrow from the CDC/subscription wake-up, and what it cannot

> **Corrected after implementing B5(a).** Two claims below were written from reading the event-store classes and
> the improvements plan, before reading v1's own notification path, and they were wrong: v1 **already** has a
> complete queue-name-keyed wake-up, and it is **already** immune to the missed-wakeup race the event-store epoch
> exists to solve. What B5(a) needed for the split was therefore wiring, not a mechanism. The details are marked
> inline; the conclusions about `NotifyAwareEventStorePollingOptimizer` not porting, and about the ramp not being
> the prize, stand.

The improvements plan's **I2** says to replace the fetcher's `scheduleAtFixedRate` with an epoch-driven wait loop
and to read `postgresql-event-store`'s `subscription/notify/{NotifyEpochSource, NotifyAwareEventStorePollingOptimizer}`
first, because they solve a missed-wakeup race. Both classes exist on this branch. Having read them, the guidance
holds for one of the two and not the other, and the difference is structural rather than a matter of taste.

**~~`NotifyEpochSource` ports, and is the valuable half.~~ Not needed — the queue already has its equivalent, and
a structurally stronger one.** The event-store optimizer compares a counter against a snapshot, so *when* the
snapshot is taken decides whether a notification arriving mid-poll is lost; `eventStorePollingReturnedEvents()`
reads the epoch after the poll for exactly that reason. The queue never compares: `QueuePollingOptimizer` has a
`messageAdded(QueuedMessage)` hook, and `CentralizedQueuePollingOptimizer.messageAdded` sets
`nextAllowedPollEpochMs = now`. Setting a permission cannot miss an update the way comparing a counter can — a
notification arriving mid-poll simply permits the next poll, whose only cost is one possibly-redundant claim. So
the missed-wakeup race I2 warns about does not exist on this path, and porting an epoch source would add
machinery to solve a problem the queue does not have.

**`NotifyAwareEventStorePollingOptimizer` does not port.** The two optimizer SPIs have different output types:
`EventStorePollingOptimizer.currentDelayMs()` returns a sleep the per-subscription loop honours, whereas
`QueuePollingOptimizer.shouldSkipPolling()` returns a boolean the *shared* fetcher consults on every fixed 20 ms
tick. The whole content of the notify-aware optimizer is a ramped delay, and the queue SPI has no channel to
return one — so it is not an adaptation, it is a rewrite of the fetcher's loop, which is exactly what I2 asks for
and what makes I2 the larger job.

Two consequences that change the shape of B5 rather than just its size:

1. **The queue is already better on latency and worse on idle wake-ups, so the ramp is not the prize.** The
   event-store optimizer's documented Phase-1 limitation is that a NOTIFY arriving mid-sleep cannot interrupt the
   sleep, so worst-case live latency on a quiet system equals `maxDelay`. The queue has no such sleep: its floor
   is the 20 ms tick, always, and backoff suppresses *SQL* rather than wake-ups. So a straight port would make
   idle latency worse in exchange for fewer timer fires — a trade nothing in this investigation measured as
   mattering. The win B5 is actually after is the ~95% cut in idle claim statements, and `shouldSkipPolling()`
   plus an epoch source already delivers that without touching the loop. **Replacing `scheduleAtFixedRate` is
   separable from NOTIFY-driven skipping, and only the second is cheap.**
2. **Wake-ups must be keyed by queue name, not table name — and v1 already is.** The event-store version keys by
   table because a subscription maps 1:1 to a table. In the split a consumer maps to *two* tables, so a
   table-keyed wake-up would let an ordered enqueue advance state the queue's single poll decision never reads.
   ~~This is a hazard to avoid~~ — v1's notification handler already routes on `QueueTableNotification.queueName`
   to the consumer for that queue, and both split tables carry the column, so the split inherits the correct
   keying for free. The rule the near-miss suggests is still worth stating, because §7d hit the same shape for
   real: **in the split, anything the consumer decides once must be fed from state merged across both tables.**

**B5(a) as actually built, therefore, is wiring.** `PostgresqlSplitDurableQueues` takes a
`MultiTableChangeListener`, installs the change-notification trigger on both tables during its own schema
creation, listens on both table names, and routes `QueueTableNotification`s by queue name into its own consumer
registry — the same shape as v1's handler over one table. With no listener configured it resolves
`QueuePollingOptimizer.None()` rather than a backoff, because backoff with nothing to end it is only slower.
`PostgresqlSplitDurableQueuesNotifyWakeUpIT` pins the trigger install on both tables, the wake-up from *either*
table reaching the one consumer, a notification for an unconsumed queue being harmless, and — as the control that
makes the rest believable — that the same traffic with no listener produces no wake-ups at all.

**B5(b) — replacing `scheduleAtFixedRate` — is untouched and still unmeasured.** After B5(a) the idle-statement
cut is already available through backoff plus wake-ups, which was the point of separating them.

## 7f. S3 increment 2: the admin surface was mostly already there, and paging was broken everywhere

Increment 2 was budgeted as the expensive half — 12 admin operations, each in three synced places (the `*Api`
SPI, `EssentialsAdminApiSpec`'s mapping table, a controller in `spring-boot-starter-admin-api`). **That did not
apply.** `DefaultDurableQueuesApi` is written against the `DurableQueues` *interface*, so the split satisfies it
by being a `DurableQueues`: no new SPI method, no spec entry, no controller. The work was to establish that rather
than assert it (`PostgresqlSplitDurableQueuesAdminApiIT` drives the reads, the by-id mutations against a message
in the *ordered* table specifically, purge across both tables, and paging), and to fix the one operation whose
semantics the split genuinely changed.

**That operation was paging, and looking at it turned up a larger defect underneath.** The split's version was
wrong in the way already recorded: handing each delegate the caller's `startIndex` makes each skip that many of
*its own* rows, returning the wrong rows and up to `2 × pageSize` of them. But the reason it could not simply be
fixed is that **neither implementation had a deterministic order to merge on**. PostgreSQL paged with
`LIMIT/OFFSET` and no `ORDER BY`; MongoDB with `skip()/limit()` and no sort. `queueingSortOrder` was accepted and
ignored by both. So paging was not a partition of the queue on *any* implementation — the database was free to
return rows in a different order per query, and a message could appear on two pages while another appeared on
none. Not introduced by the split; the split only made it impossible to ignore.

Fixed in all three: both implementations now order by `added_ts`/`addedTimestamp` with the id as tie-break, and
the composite reads `startIndex + pageSize` rows from each table at offset 0, merges in that same order, and takes
the caller's window from the merge.

Three decisions inside that worth keeping:

1. **Order by added-timestamp, not by id** — even though the SPI's javadoc said "the sorting order for
   `QueuedMessage#getId()`". A `QueueEntryId` is a UUID and neither generator flavour sorts chronologically as a
   string, so id-ordering would be deterministic but arbitrary: the admin browse surface would go from "oldest
   first" to a stable shuffle, and the shared suite already asserts insertion order. The javadoc was describing an
   order nobody wanted, so **the javadoc was corrected** (11 occurrences) rather than the behaviour built to match
   it. The id remains as the tie-break, which is what makes the order total.
2. **`COLLATE "C"` on the id tie-break.** The composite merges the two tables' pages in Java, so the SQL order and
   the Java comparator must agree; `COLLATE "C"` is byte order, which for ASCII UUIDs is `String.compareTo`. Under
   a linguistic collation the hyphens can sort differently and page boundaries would corrupt — quietly, and only
   on the split.
3. **The offset cannot be pushed down, so deep pages cost.** Page *n* reads `n × pageSize` rows from each table.
   Acceptable for an admin browse surface that pages shallowly, and the price of exactness; a cheaper deep-page
   story needs a keyset cursor, which changes the SPI rather than the storage.

The paging test went into the **shared** suite, because the defect was cross-implementation — it now runs against
Postgres v1, the split, and Mongo. It was then falsified to check it could fail: with the `ORDER BY` reverted, the
"no duplicates" and "every message" assertions still **passed** (freshly inserted rows come back in heap order,
stable within one run) and only the "DESC is ASC reversed" assertion failed. So that assertion is the one with
teeth, and the test says so, because the obvious simplification would delete exactly the half that works.

**Still missing for the split, deliberately:** `DurableQueuesStatistics` is constructed with *a* queue table name
and the split has two. The admin API already tolerates its absence (`getQueuedStatistics` returns empty), so
teaching statistics about two tables belongs with **S2**, the statistics rewrite — which closed it, since in-memory
collection keys on `QueueName` rather than a table.

## 7g. S3 rollout: the flag, and the backlog problem it exposed

`essentials.durable-queues.use-split-queue-tables` selects the split under Spring, **defaulting to `false`**, and
it stays false until there is operational experience — because switching is not free in the way a flag implies.

**The split reads `<base>_unordered` and `<base>_ordered`, never the shared table.** A deployment that flips the
flag with messages queued does not lose them; it stops delivering them, with nothing reporting an error. That is
the worst possible failure mode for a queue, and it is not something a release note fixes.

So `PostgresqlSplitDurableQueues.migrateFromSharedTable(sharedQueueTableName)` moves a backlog across: an
`INSERT ... SELECT` per mode plus a `DELETE`, in one transaction under the bootstrap lock. It is that trivial
**only** because §7c kept the columns identical — no mapping, no re-serialization, no id rewriting, so ids,
attempt counts, timestamps, dead-letter state and last errors all survive. That decision was made for a different
reason (reusing v1's statements) and paid off twice.

The load-bearing part is the **interlock**: it refuses to run while any row in the shared table is marked
`is_being_delivered`, which is the observable signature of a v1 consumer still running. Migrating rows out from
under a live pod would hand the same message to two instances. It is not a distributed lock — an idle v1 pod
passes — so the procedure is still stop, migrate, start; the check turns the most likely mistake into a refusal
rather than a duplicate delivery.

Two consequences worth being explicit about:

- **There is no rolling upgrade.** A v1 pod and a split pod on the same base name read different tables. §9
  established that a *mixed-version* rollout is safe for the cursor on one table; this is the opposite situation
  and that result does not transfer.
- **The starter does not migrate on startup**, deliberately. The precondition it would have to verify — that no
  other instance is consuming — is exactly what a rolling deploy violates.

## 7h. The cursor behind a flag, and what measuring it through the component showed

`setUseOrderedMessageCursor(true)` ships the corrected head-only cursor: a `<queueTable>_key_cursor` table, a
cursor row created on every ordered enqueue, the cursor claim replacing the barrier for the ordered half, a
gap-safe acknowledgement, and reconcile-on-empty-claim. Off by default. Unordered traffic is untouched.

**Both correctness gates pass**: the shared `DurableQueuesIT` unmodified (`CursorPostgresqlDurableQueuesIT`), and
per-key ordering under 2 000 messages / 20 parallel consumers
(`CursorPostgresqlLocalOrderedMessagesDurableQueueIT`) — which is the one that matters, because the fault that sank
the first prototype is invisible without contention.

**Two implementation faults were found by those gates, not by reading**, and one of them is a design correction the
prototype shares:

1. **The claim filtered `is_dead_letter_message = FALSE` inside the per-key LATERAL**, exactly as the prototype's
   `claimOrderedViaSafeCursorSql` does. That makes the lookup *skip* a dead-lettered row and hand out the
   next-but-one — so a key is delivered out of order past a dead letter, which v1's barrier blocks for free
   because a blocked predecessor simply keeps blocking. The LATERAL must return the key's **true** next message
   whatever state it is in, and the eligibility test applies to that head. §8's fault analysis covered the
   *acknowledgement* skipping a blocked message; this is the same class of fault in the *claim*, and it was not
   recorded.
2. A duplicated closing paren in the acknowledgement CTE chain — caught immediately, noted only because it shows
   the value of running the suite rather than reviewing the SQL.

### The measurement, and it does not support the headline

Through the real component, end to end, drain time (`OrderedCursorVsBarrierBenchmarkIT`, both arms delivering
every message):

| Keys | Messages/key | Barrier | Cursor | Ratio |
|---|---|---|---|---|
| 8 | 100 | 2 027 ms | 1 996 ms | **1.02×** |
| 8 | 600 | 12 635 ms | 12 019 ms | **1.05×** |
| 8 | 2 500 | 92 444 ms | 49 993 ms | **1.85×** |

**The effect is real and it scales with backlog depth per key**, which is exactly the mechanism: the barrier's
correlated `NOT EXISTS` rescans a key's depth per candidate row, so its cost grows with depth while the cursor's
range scan does not. At 20 000 messages over 8 keys the barrier spends 92 seconds where the cursor spends 50.

**But the 217× does not appear, and §7 already explains why.** That number is a *claim-phase* measurement. End-to-end
drain also pays one acknowledgement transaction per message — the 16.5× lever — which the cursor does not touch, so
the claim saving is diluted by a cost that dominates. §8 predicted precisely this ("its *total* is diluted by an
acknowledgement cost it does not touch"); the component measurement is that prediction arriving.

So the honest position, stated by shape rather than as one number:

- **Shallow keys (≲600 per key): 1.02–1.05×.** Not worth enabling for throughput.
- **Deep keys (2 500 per key): 1.85×.** Worth having, and it will keep growing with depth — this is the regime
  where the barrier is pathological.
- **The remaining headroom is the acknowledgement, not the claim.** Getting near the prototype's claim-phase
  numbers requires the half that is still missing: **run-claiming plus a batched ordered acknowledgement**, which
  is the only route §2 permits for ordered traffic to amortise a transaction at all.

Which is what the flag was for: the cursor's value is a function of a deployment's key depth, and now it can be
measured against real traffic instead of argued from a prototype.

Next, if this is pursued: implement `claimOrderedRunViaSafeCursorSql` (prefix runs with the `bool_and` truncation
and caller-side sort) and acknowledge each run in one statement. Note the ack is **coupled** to the run claim — it
scans `(cursor, min_acknowledged)`, sound only for prefix batches — so neither is safe with an arbitrary batch from
elsewhere.

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
| **B5** | **NOTIFY-driven wake-up replacing the fixed tick** (I1/I2 in the improvements plan) | Neither — *latency* | Everything measured in this investigation is throughput. This is the only remaining item targeting enqueue-to-delivery latency, estimated 400 ms → <10 ms p99 idle with a ~95% cut in idle claim statements. Fully designed, unimplemented, unmeasured. **Split in two (§7e). (a) Wake-ups for the split — done: the composite installs the trigger on both tables and routes notifications by queue name, so backoff plus wake-up gives the idle-statement cut with no new mechanism. (b) Replacing `scheduleAtFixedRate` with a wait loop — untouched, and a straight port of the event-store design would *worsen* idle latency** | (a) done; (b) a fetcher-loop rewrite, then a PROBE scenario |
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
