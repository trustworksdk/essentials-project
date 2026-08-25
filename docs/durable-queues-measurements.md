# Durable Queues — measurements

The evidence behind [durable-queues.md](durable-queues.md). **Truncated deliberately**: this replaces a
1 600-line running log of 29 numbered sections, most of which recorded a claim that a later section withdrew.
What survives is the current position and the reasoning that is still load-bearing. The full log is in git
history.

---

## 1. Results

Every figure is measured through the **component** (the real `DurableQueues`, a real consumer, real transactions)
unless marked otherwise.

| Finding | Result | Confidence |
|---|---|---|
| **Missing planner statistics on the ordered claim** | **11×** — 13 517 ms against 1 184 ms, 20 000 messages. `ANALYZE` fixes it; `VACUUM` does not | High — 4 maintenance modes compared, tight spreads |
| Split ordered/unordered **claim queries** (`useOrderedUnorderedQuery`, default on) | **5.4×** on a mixed backlog; 1.63× pure unordered; 1.04× pure ordered | High |
| Index set reduced six → five | **−28% index bytes**; two indexes took zero scans across the whole SPI | High — scan counts at two key cardinalities |
| **Per-key cursor**, few keys and deep backlog (8 × 2 500) | **1.81×** on drain, unchanged by `ANALYZE` | High — reproduced with and without statistics |
| Per-key cursor, shallow keys | 1.02–1.05× | High |
| **Two-table split**, unordered | 1.07–1.36× total, all of it insert (1.34–1.66×); drain parity | High — after the two throughput defects in §3 fixed |
| Two-table split, ordered | Parity (0.97–1.00×) | Medium — needs `ANALYZE` to be stable at all |
| **Steady state, everything** | Shared / split / cursor all deliver the offered rate; capacity 941 / 1 008 / 1 006 msg/s | High — backlog bounded, saturation measured separately |
| Batched fetch | 1.01–1.02× time; **16–64× fewer claim statements** | High — statement counter, both with and without batched ack |
| Statistics via observer instead of trigger | Removes a **2.80×** penalty on the acknowledgement path | High |

## 2. Claims withdrawn under measurement

Listed because they appear in older documents, in commit messages, and in at least one case in shipped javadoc.
**All were measured, all were wrong, and all were wrong in the same direction.**

| Claim | Where it came from | What it actually is |
|---|---|---|
| Batched acknowledgement is **16.5×** | Raw-SQL harness, one transaction per ack against one per batch | **1.02×** drain, **1.00×** steady state with a worse p99, 0.91× at saturation |
| The cursor gives **217×** | Prototype, claim phase only | **1.81×** end to end, and only when keys are few and deep |
| The split gives **1.38× / 1.62×** | Prototype schemas, counting six declared indexes against one | **~1.1×**, and briefly **0.20×** — see §4 |
| The framework costs **4%** over raw SQL | Two arms using different claim queries | Confounded; not established, and not worth establishing |
| The statistics trigger's `EXCEPTION` block is its most expensive part | Reasoning about subtransactions | Real mechanism, **1.03×** of wall clock. The cost is the `INSERT` and its indexes |
| The split's ordered traffic goes to **3.64×** | One run of a bimodal measurement | Within the spread — repeat runs differed 4.75× |
| The shipped cursor claim carries a latent deadlock | Inference from "it waits where v1 skips" | Does not reproduce at two aggression levels; a cycle needs two claimers to lock in *different* orders, and this statement takes one row per key |

### Why they were all optimistic

A raw harness isolates one cost and removes everything that normally dominates it — no framework, no connection
pool, no polling, one connection, claim and acknowledge strictly alternating. **A prototype measurement is a
hypothesis about where time goes, not a number.**

## 3. Three defects the split shipped with, and how they were found

All came from the same decision: compose two v1 stores so both tables could be driven by v1's statements
unchanged. v1's statements assume v1's index set, and the split's entire purpose is not to have v1's index set.
The first two cost throughput; the third cost correctness of anything observing the queue.

1. **The composite asked each table for messages it cannot hold.** The unordered delegate ran v1's *ordered* query
   — which filters `key IS NOT NULL` — against the unordered table on every poll. Fixed with a `ClaimScope`.
2. **The split's unordered index omitted `key IS NULL`.** The unordered claim filters on it, and v1's
   `idx_*_unordered_ready` has always been partial on it, so the claim is an index-only scan. Without the
   predicate every candidate row needed a heap fetch to re-check a condition true for every row in that table.

Together they made the split **5× slower** than the table it replaces: 0.16× on unordered drain, worsening with
backlog size (0.75× at 10 000 messages, 0.16× at 40 000). Neither was visible by inspection. The statement counter
found the first; the second only became findable once the first was fixed.

A third instance of the same decision, found later and by the same instrument: every operation addressed by
`QueueEntryId` tried one delegate and then the other. That is two statements *and* two runs of the interceptor
chain — interceptors register on the composite and on both delegates — so any counting, metrics or tracing
interceptor double-counted every operation on an ordered message, with no assertion anywhere able to see it. All of
them are now one statement composed from the delegates' own SQL. It had been filed as low priority on the grounds
that these run "per failure"; `retryMessage` is in fact called for every `markForRedeliveryIn(...)`, which is
ordinary control flow.

## 4. Why the split cannot help unordered traffic much

Per-index sizes, 40 000 unordered messages:

| Index | Shared | Split |
|---|---|---|
| `unordered_ready` | 3 240 KB | 3 240 KB |
| primary key | 2 856 KB | 2 904 KB |
| `next_msg` | 288 KB | *removed* |
| `ready` | 288 KB | *removed* |
| `ordered_head` | **8 KB** | 8 KB |
| `ordered_unique` | **8 KB** | 8 KB |

The ordered indexes are **partial on `key IS NOT NULL`** — with no ordered messages they are empty and cost nothing
to maintain. So "six indexes down to one" describes declarations, not work: the split removes two *small* indexes,
8.6% of the bytes, while the two structures holding the data exist identically on both sides.

That is the ceiling, and the measurements sit on it.

## 5. Harness requirements, learned the hard way

Each of these silently produced a wrong number before it was fixed.

- **Count deletions, not claims.** A harness counting claims reports completion with rows still in the table.
- **Count statements, not messages.** Message counts are equal by construction, so they cannot distinguish "no
  effect" from "the feature never engaged".
- **Check the drain is database-bound.** A drain takes at least `(N / parallelConsumers) × pollingInterval`; at
  defaults that is 8 s for 40 000 messages, and one run measured exactly 8 s in *every* arm.
- **`ANALYZE` both arms.** Otherwise the ordered claim is measuring missing statistics.
- **Print per-repetition values.** A median hides bimodality, and one arm was bimodal while the other was tight.
- **Check the backlog is bounded.** A steady-state harness offered more than capacity is a backlog-recovery
  harness with extra steps.
- **Read the futures.** `ExecutorService.submit` swallows worker exceptions into a Future nobody reads.
- **Interleave arms.** Consecutive runs of one arm share warm caches and accumulated dead tuples — the confound
  behind a 5.7× artefact.

## 6. Where old section numbers went

Older commits, javadoc and module notes cite `§N` from the previous 29-section log.

| Old | Subject | Now |
|---|---|---|
| §1, §16, §17 | Ordered/unordered query split; index diet | §1 results table |
| §2, §10 | Ordered throughput bounded by one commit per message | Still true; §1 and [durable-queues.md](durable-queues.md) §2 |
| §7 | Batched acknowledgement 16.5× | **Withdrawn** — §2 |
| §8, §11 | Cursor corrections; run-length gate | §1 results table |
| §12, §13, §15, §18 | Partitioning, autovacuum, write-once row, B2/B4 | [durable-queues.md](durable-queues.md) §5 |
| §14 | Statistics trigger cost | §1 results table |
| §19, §20 | Run-claiming attempt; withdrawn deadlock | §2 and [durable-queues.md](durable-queues.md) §7 |
| §21–§24 | Split measured, regressed, diagnosed, bounded | §3 and §4 |
| §25, §26 | `ANALYZE`; §11 re-run | §1 results table |
| §27, §29 | Steady state | §1 results table |
| §28 | Split acknowledgement, 3 statements → 1 | §3 |
