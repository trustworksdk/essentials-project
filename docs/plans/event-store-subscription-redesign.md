# Event Store Subscriptions — Redesign Proposal

**Status:** Proposal. Nothing here is built or measured. Written 2026-10-23; revised 2026-11-10 to fold in
what we learned from Marten's event store (a reference description of it, `marten-event-store-design.md`, was removed on 2026-11-15; it is in git history).
**Scope:** how events get from the event-stream tables to subscribers — polling, gap handling, wake-up,
resume points and exclusive ownership — and the append path, because how events are written decides how
many holes the reader has to deal with. It also covers building a new event store engine beside
`PostgresqlEventStore`, the way the shard-owned queue was built beside `PostgresqlDurableQueues`, and
what can be gained at the lower levels: statement shapes, raw JDBC and table design. Since 2026-11-15 the
new engine has its own schema, its own subscription API, progress table, error model and leases, and an
application moves to it once, one way (§18, "Decisions 2026-11-15: a new subscription model").

**Focus of the revision:** performance, resource use and freshness, without weakening any ordering or
delivery guarantee. Every idea taken from Marten is listed in §3.2 with where it is applied, and every idea
deliberately *not* taken is listed in §3.3 with the reason.

Related documents:

| Document | Relevance |
|---|---|
| `marten-event-store-design.md` (removed 2026-11-15, in git history) | Marten's append path, high-water mark, daemon, progress-in-transaction and fetch plans. The source of §5, §7, §8, §10 and §14 |
| [`subscription-improvements.md`](./subscription-improvements.md) | S1 (NOTIFY-reset backoff, shipped), S2 (interruptible wake-up, never built), S5/S6 (resume-point saves and the reposition epoch) |
| [`durable-queue-shard-owned.md`](../design/durable-queue-shard-owned.md) | The engine whose lessons this borrows: cursor, holes, wake-up tiers, leases, fencing |
| [`durable-queue-ordered-routing-design.md`](../design/durable-queue-ordered-routing-design.md) §3 | The safe watermark, which is the core mechanism proposed here |
| [`archive/durable-queue-design-history.md`](../archive/durable-queue-design-history.md) §9 | The defect log. Most of its entries have an equivalent hazard in this design |
| [`key-partitioned-event-processing.md`](../design/key-partitioned-event-processing.md) | EF-2. A shared reader with owned units is option (b) of that document, made cheaper |
| [`cdc.md`](../design/cdc.md) | The alternative live path, and what this proposal would make optional |
| `components/postgresql-event-store/CLAUDE.md` | The gotcha list, most of which is about the mechanism this proposal replaces |

---

## 0. Summary

1. **Replace time-based gap handling with a safe watermark.** Today a hole in `global_order` is a
   database row per subscriber, re-queried on every poll and promoted to permanent after 120 seconds.
   The ordered lane of the shard-owned queue instead asks PostgreSQL which write transactions are still
   running. A hole closes when the transactions that could fill it have finished — exactly, with no
   timer and no writes. The watermark needs **no schema change** to the event-stream tables (§4).
2. **Create fewer holes in the first place.** Append each aggregate's events with **one statement** that
   takes the transaction's xid, checks the expected event order and inserts set-based from column arrays
   — in that order, so a writer that loses an optimistic-concurrency check consumes **no** `global_order`
   value. This also removes today's extra round trip to look up the last event order (§5). The idea is
   Marten's; the set-based insert improves on Marten's row-by-row loop.
3. **Read each aggregate type's tail once per JVM, not once per subscription.** Per-subscriber gap state
   is what forces every subscription to poll on its own. Without it, subscriptions that have caught up can
   share one reader. Statement count then grows with the number of aggregate types, not subscriptions × JVMs
   (§6). While a hole is outstanding, the tail read and the running-writers probe become one statement (§4.10).
4. **Make the freshness/consistency trade-off a per-subscription choice.** `FRESH` delivers everything
   visible and lets a late commit arrive later (today's contract, best latency). `CONTIGUOUS` delivers only
   up to the watermark, as Marten does, which together with item 5 gives **exactly-once database state**.
   Both modes run on the same shared reader at no extra statement cost (§7). Default: `CONTIGUOUS` for view
   projections, `FRESH` for event processors (§20, D1).
5. **Commit progress with the output.** For handlers whose output lives in the same database, the resume
   point is written in the handler's own transaction, a page of events per transaction, with a
   compare-and-set on the reset epoch and the owner's fence, on the engine's own progress row (§8). Marten's
   progress model, combined with this proposal's fencing. A page that fails shrinks until the failing event is
   isolated; that event is parked for the subscription with its error, later events of its aggregate wait behind
  it, and every other aggregate continues (§8.3).
6. **Own exclusive subscriptions through engine leases**: lease and fence columns on the progress row, renewed
   with server time, many subscriptions per JVM in one statement, and every resume-point write asserting the
   fence in the same statement on the same row (§9). No `FencedLockManager`. The current engine is not changed
   (decided 2026-11-15).
7. **Catch up cheaply.** Push event-type filters into SQL for subscriptions that are behind, advance
   progress to the watermark rather than to the last matching row, and cap concurrent catch-up reads per
   database (§10). Under the old gap handler, filtering in SQL created fake gaps; under the watermark it is safe.
8. **Wake the reader with signals the way the queue does**: an application-issued, coalesced `NOTIFY`
   instead of a per-row trigger, a listener that blocks rather than polls, and a local wake-up when the
   append happened in the same JVM. Correctness never depends on any of them (§11).
9. **Build it as a new engine beside the old one**, the same three-module shape as the shard-owned queue,
   with **its own subscription API** designed from the engine's needs (page handlers, consistency modes,
   progress in the handler's transaction, engine-owned leases), not an implementation of
   `EventStoreSubscriptionManager` and not shaped by the existing processors. The engine has **its own
   tables**, free to use the layouts phase 0 measured, and an application moves to it **once, one way**, by
   copying its events and importing its resume points (§12, decided 2026-11-15). Appends and stream loads
   also get an `EventStore` adapter, for those methods only, so the aggregate repositories run unchanged; its
   polling and subscription methods throw (§19 item 19, decided 2026-11-15).
10. **Investigate before building.** Four claims carry the design and none is proven yet: that the
    single-statement append takes its xid before the identity value, that the combined probe reads
    running writers after its snapshot, that `backend_xid` is visible on the managed PostgreSQL services
    users run, and that the new append is actually faster. Phase 0 settles each with a spike that has a
    gate and a fallback (§18).
11. **Treat lower-level optimisation (raw JDBC, table layout) as a separate, measured step** (§13).
    The command side — loading aggregates for writing — is a separate track with its own phases (§14).

Two corrections to ideas that came up while discussing this, each explained below:
- `pg_current_snapshot()` does **not** list every running writer (§4.4). The watermark has to read
  `pg_stat_activity.backend_xid`, as the queue does. Marten's liveness probe uses the snapshot and needs a
  timer and tombstones to compensate; this design needs neither (§4.9).
- Ordering the cursor by a per-row transaction id **breaks per-aggregate ordering** (§4.5). The cursor
  has to stay on `global_order`.

---

## 1. Goals, must-keep semantics and non-goals

### Goals

| Goal | Concretely |
|---|---|
| Correctness | No event lost to a long transaction, a sequence jump, a lost notification, a lost connection, a crash or a lock takeover. No correctness property depends on a timeout |
| Freshness | Cross-JVM commit-to-handler latency in the low milliseconds instead of S1's backoff interval. In `FRESH` mode a slow writer delays only its own events |
| Resources | Idle cost independent of the number of subscriptions. Statements per delivered event fall as load rises. No writes on the read path. No sequence values burned by optimistic-concurrency losers |
| Performance | One round trip per appended aggregate. Throughput of a caught-up subscription limited by its handler, not by polling. Fewer transactions per delivered event for in-database projections |
| Simplicity | Remove the gap machinery: transient and permanent gap tables, promotion, gap-middle tracking and the gap-fill acknowledgement protocol |

### Must keep

- At-least-once delivery. Handlers are idempotent, and a crash may redeliver.
- Per-aggregate order: an aggregate's events reach a subscriber in `EventOrder` order.
- `GlobalEventOrder` per aggregate type, as an independent space per table.
- Durable resume points, and an operator reset that wins over any concurrent save (today `resetFrom` with
  the S6 reposition epoch; in the new engine a reset epoch on its progress row, §8.2, §10.3).
- Exclusive subscriptions: one consumer per subscriber id across the cluster.
- Delivery inside the appender's commit (today `InTransactionEventProcessor`, `EventStoreEventBus`). It is
  outside the subscription engine; through which API the new engine offers it follows from the open
  question on appends (§19).
- The persisted JSON wire format, which is frozen (root `CLAUDE.md`). Events copied into the new engine's
  tables keep their payload bytes (§12.4).
- The optimistic-concurrency contract: `OptimisticAppendToStreamException` on a conflicting append, with
  the same message content, and a unique `(aggregate_id, event_order)` backstop.
- A failing handler never loses an event silently: the event is retried or parked with its error, and
  stays visible to an operator (§8.3).
- Multi-tenancy, filtered in memory after the shared read. Catch-up reads may filter by tenant or event
  type in SQL (§10), because the watermark no longer depends on seeing every row.

What is deliberately **not** kept (decided 2026-11-15): the current subscription API
(`EventStoreSubscriptionManager`, the subscription builders, `pollEvents` and `SubscriberAcknowledgement`),
the shape of `EventProcessor`/`ViewEventProcessor`, the `durable_subscriptions` table, the
`SubscriptionErrorPolicy`/`FailFast`/redelivery semantics, and `FencedLock` ownership. Each is replaced by
something designed for pages and engine leases (§8, §9, §12.3).

### Non-goals

- Exactly-once *delivery* (side effects outside the database stay at-least-once in every mode).
- Ordering across aggregate types.
- Replacing in-transaction delivery.
- Source compatibility with the current subscription API, its processors or its error policies. Handlers
  move to the new API when their application moves to the new engine.
- Running on the current engine's tables, running old and new engine nodes on the same data, or switching
  back and forth. The move is one-way (§12.4).
- Changing the delivery order of a late commit in `FRESH` mode. Today a late commit can arrive after
  higher global orders as a gap fill. In `FRESH` it still will, and that is within the contract.

---

## 2. How it works today, and where the cost comes from

### 2.1 The read path

Each subscription has its own poll loop (`PostgresqlEventStore.pollEvents`). On every poll it:

1. opens a `UnitOfWork`, which means a transaction on a pooled connection;
2. resolves a batch size, which after empty polls can mean an extra probe
   (`findLowestGlobalEventOrderPersisted`);
3. asks the gap handler for transient gaps to include, using the rotating selection;
4. loads the range, together with any gap-middle fills;
5. reconciles gaps, which writes rows to `transient_subscriber_gaps` and promotes rows to `permanent_gaps`;
6. commits;
7. sleeps `EventStorePollingOptimizer.currentDelayMs()`, which nothing can interrupt.

The gaps exist because `global_order` comes from an identity sequence. A transaction that takes value
100 can commit after another one commits 101. A cursor reading `> 99` sees 101, and 100 then appears
behind it. The current design records every hole it steps over, per subscriber, and gives up on a hole
after a timeout (`transientGapGiveUpThreshold`, 120 s by default). Three properties follow, and they
are the costs this proposal targets:

- **Correctness depends on time.** A transaction that commits after the hole was promoted is skipped by
  every subscriber. The module `CLAUDE.md` records the coupling to `statement_timeout`.
- **The read path writes.** Gap reconciliation inserts, deletes and promotes rows. A poll is never
  read-only, so it can never skip its transaction.
- **Subscriptions cannot share work.** Gap state is per subscriber, so two subscriptions at the same
  position still issue their own statements. Load is subscriptions × JVMs × poll rate.

Most of the module's gotchas exist to keep this mechanism correct: `GapFillsAwaitingAcknowledgement`,
`GapMiddlesAwaitedInMemory`, `GapMiddlesAwaitedAcrossSubscribes`, `TransientGapsQuerySelection`, the
bind-parameter cap on give-ups, `CdcDeliveryTracker`'s give-up timer, and resolving a gap only once the
subscriber has acknowledged its fill. Each was a real bug. Their number is the strongest argument that
the mechanism itself, not its implementation, is the problem.

### 2.2 The write path

`SeparateTablePerAggregateTypePersistenceStrategy.persist`:

1. When the caller gives no expected event order, it first runs `loadLastPersistedEventRelatedTo` — a
   separate statement and round trip — to find the aggregate's last event order.
2. It computes each event's `event_order` on the client and sends a JDBI `PreparedBatch` of single-row
   `INSERT … RETURNING global_order` statements. Nothing in the repository sets pgjdbc's
   `reWriteBatchedInserts`, so this is N executions; how many network round trips that costs under pgjdbc's
   defaults has not been measured.
3. An optimistic-concurrency conflict is detected when `UNIQUE(aggregate_id, event_order)` rejects the
   insert. By then the identity default has already handed out a `global_order`, so **every lost race
   leaves a hole** that every subscriber then has to wait out.

### 2.3 Resume points

`PostgresqlDurableSubscriptionRepository` saves resume points in a separate, batched transaction:
`UPDATE … SET resume_from_and_including_global_eventorder = … WHERE … AND reposition_epoch <= :epoch`.
The save is guarded by the reposition epoch but not by the exclusive lock's fence (§9), and it is never
in the same transaction as the handler's output, so a projection's state and its resume point can always
disagree after a crash. That is within at-least-once, but it means every projection must be idempotent and
every replay redoes work.

---

## 3. What the shard-owned queue and Marten taught

### 3.1 The shard-owned queue

Each lesson below has a section in this proposal that applies it.

| Lesson | Source | Applied in |
|---|---|---|
| A hole can be closed exactly by asking which writers are still running, instead of waiting out a timer | ordered-routing §3 | §4 |
| Read `S` (highest seen) **before** `X` (running writers). Reversed, a writer slips between the two reads | ordered-routing §3 | §4.2, §4.10 |
| Compare **sets** of `backend_xid`, never numbers: `xid` wraps at 32 bits, `xid8` does not, and there is no cast | ordered-routing §3, shard-owned §17.1 | §4.2 |
| `pg_snapshot_xmax` does not bound running writers. One running writer can read as an empty snapshot | ordered-routing §3 | §4.4 |
| A writer must hold its xid **before** it takes a sequence value, or it is invisible while holding one. Happened once in ~20 minutes in the trading demo | shard-owned §2.8 | §4.3, §5 |
| `backend_xid` visibility is the one hard prerequisite. A partial answer silently loses data, so prove it at start-up by constructing the condition | shard-owned §17.2 | §4.3 |
| Coalesce `NOTIFY` per commit and issue it from the application, never from a per-row trigger. The notify queue is serialised cluster-wide | shard-owned §6 | §11 |
| The listener connection does no other work, and re-runs `LISTEN` after reconnecting. Its failure is silent by construction, so tests must assert notification counts | shard-owned §6, history §9.12 | §11 |
| Losing every notification may cost latency and nothing else | shard-owned §5.1 | §11 |
| Every durable write asserts its fence in the same statement. Zero rows updated is ambiguous: check ownership directly | shard-owned §5.4, §8.3 | §8, §9 |
| All durable time is the database's | history §9.11 | §9 |
| An interrupted handler is not a success | shard-owned §5.5 | §6 |
| A lost connection must not stall a unit forever. Frozen-but-alive owners must stop themselves | history §9.8, §9.10 | §9 |
| Idle cost must be measured with a gate from the start, per queue, per unit and per instance | history §9.20 | §17 |
| An asynchronous delivery decision made the cursor, hole set, in-flight set and ack floor concurrent, and produced a duplicate | history §9.23 | §6 |

### 3.2 Marten: what is taken

Section numbers on the left refer to `marten-event-store-design.md`, removed on 2026-11-15 and kept in git history.

| Marten idea | Marten § | Applied in | Change from Marten |
|---|---|---|---|
| Check the expected version **before** `nextval`, so a writer that loses the race consumes no sequence value | 3.1, 4.1 | §5 | First planned without a streams table, the check reading `max(event_order)` from the existing unique index. Decided 2026-11-15 (§19 item 23): the engine uses P0.7's stream head table, whose guarded `UPDATE` makes the check |
| One round trip per appended stream, column arrays as parameters | 3.1, 11.1 | §5 | A set-based `INSERT … SELECT FROM unnest(…)` instead of a PL/pgSQL loop, and a plain statement instead of a function |
| Map concurrency violations by constraint name, never by parsing the error detail | 3.4 | §5 | Already done today (`isOptimisticConcurrencyViolation`); kept |
| Every high-water reading is one SQL statement, so one snapshot | 4.3 | §4.10 | Applied to the tail read plus running-writers probe |
| Never read past a high-water mark | 4.3 | §7 (`CONTIGUOUS`) | Optional per subscription instead of the only behaviour |
| Progress committed as a compare-and-set in the same transaction as the output | 6.2 | §8 | The compare-and-set is on the reposition epoch and the owner's fence |
| A page of events per transaction | 6.2, 7 | §8 | A page that fails shrinks until the failing event is isolated; that event is parked for the subscription, its aggregate waits behind it and the others continue (§8.3) |
| Push the event-type filter into SQL; advance progress to the high-water mark on sparse pages | 6.1 | §10 | Only for catch-up reads; the shared tail read stays unfiltered |
| Skip-ahead and windowed fallback when a filtered read times out | 6.1 | §10 | — |
| Cap concurrent event loads per database, separately from the pool | 6.1 | §10 | — |
| A monitored lock: a node that may have lost ownership stops | 6.3 | §9 | Lease and fence columns on the engine's progress row instead of an advisory lock |
| Fetch version, snapshot and tail events in one batch; `REPEATABLE READ READ ONLY` when the snapshot is asynchronous | 5 | §14 | Separate track |
| A node-local aggregate cache, entries taken out atomically and written back on commit, version always checked | 5 | §14 | Separate track |
| Keep frequently updated rows unindexed on the updated column so updates stay HOT | 9, 11.21 | §8 | The resume-point and lease columns of the engine's progress row stay unindexed; document fillfactor |
| Statement timeouts and prepared-statement measurements before assuming | 10 | §13 | — |

### 3.3 Marten: what is deliberately not taken

| Marten idea | Why not |
|---|---|
| **Tombstones** written at the seq_ids of failed appends | They exist because Marten's liveness probe needs a stale threshold before it can skip. Under the `backend_xid` rule an aborted writer's xid leaves `pg_stat_activity` at once, so its hole retires on the next probe without any write (§4.9). Tombstones would add writes and a second stream to every table |
| **Snapshot-based (`xip`/`xmax`) liveness probe plus a 3-second threshold** | `xip` misses writers at or above `xmax` (§4.4); Marten compensates with `pg_locks`, transaction start times and a timer. The xid-set rule is exact and has none of those parts |
| **A persisted, shared high-water mark** maintained by one leader (`mt_event_progression` rows `HighWaterMark`, `HighWaterStuckGap`, `mt_high_water_skips`) | It puts writes on the read path and adds a leader. Each JVM's reader derives its own candidates for free from the reads it is doing anyway |
| **The optional cap that forces a skip** after a longer time | Event rows are never deleted, so an unretired candidate costs one list entry, not data (§4.6) |
| **Advisory-lock leadership per shard** | Balances poorly ("first node grabs everything"), and an advisory lock cannot be asserted inside a write. Lease rows with fences can (§9) |
| **Rich append: client-side `nextval` pre-fetch** | Doubles the round trips and burns sequence values on every failed save |
| **A `streams` table with a version row per stream** | Would add an update per append and a schema change that makes the switch between engines one-way. The `max(event_order)` read on the existing unique index gives the same pre-`nextval` check. An opt-in transaction-scoped advisory lock covers Marten's `FOR UPDATE` use case for hot streams (§5.3). *Revised 2026-11-15:* the move to the new engine is one-way and the engine has its own tables (§12.4), so the schema-change reason no longer holds. Decided 2026-11-15 (§19 item 23): the engine adopts P0.7's stream head table (§5.3, §13.3), which P0.7 measured with zero lost-race holes in one statement. This row therefore records why the table was first rejected; it is now taken |
| **Tenant partitioning with a sequence per tenant** | Loses global order within an aggregate type, which `GlobalEventOrder` promises |
| **Coarse tag-version rows for consistency across streams (DCB)** | Out of scope; a hot tag becomes a write hotspot |

---

## 4. The core mechanism: a safe watermark over `global_order`

### 4.1 What it replaces

The reader keeps delivering everything it can see, as today. What changes is how it decides that a
hole below its cursor can no longer fill. It stops recording holes in the database, and stops promoting
them on a timer. It keeps an in-memory list of *candidates*, and each candidate retires at the moment
PostgreSQL proves that no writer can still commit below it.

### 4.2 The rule

This is taken unchanged from the ordered lane (ordered-routing §3). At the moment of a probe, let:

- **`S`** be the highest `global_order` this reader has actually read;
- **`X`** be the *set* of `backend_xid` values in `pg_stat_activity` that are not null, read **after** `S`.

Any transaction that could still commit a `global_order ≤ S` already had its xid when `S` was observed,
so it is in `X`, provided the prerequisite in §4.3 holds. Once no member of `X` is still running,
every `global_order ≤ S` is resolved: either committed and visible, or aborted and never coming.

When a candidate `(S, X)` retires, the reader issues **one** read of the holes it recorded at or below
`S`. Whatever that read returns is delivered as a late commit. Every remaining hole at or below `S` is
then permanent, for this reader and for good. Until the candidate retires, nothing is skipped; at worst
something is delayed.

> **Revision (P0.5 F1, 2026-11-11; decided 2026-11-15).** The hole read after retirement must not be the only
> way a late commit is delivered. As written it breaks per-aggregate order in `FRESH` (I2): A#n commits late into a
> hole, A#n+1 is appended after it and read by the next probe above the cursor, and A#n arrives only when its candidate
> retires, which a long writer elsewhere can postpone indefinitely. Proposed: the tail statement also re-reads the
> outstanding holes (`WHERE global_order > :cursor OR global_order = ANY(:holes)`, with ranges for a sequence jump) and
> publishes what it finds in `global_order` ahead of the new rows. A#n+1 can only exist once A#n has committed (§4.5), so
> any snapshot that sees A#n+1 also sees A#n. The hole read at retirement stays: a writer can commit between the
> statement's snapshot and its read of the running set, so the retiring probe itself may not have seen the row. With
> this change the model passes every invariant in every configuration, including several events per transaction.
> Cost: a larger tail statement while holes are outstanding, no extra statement (§19, risk 13).
> [phase-0 record, F1](../archive/event-store-redesign-phase0.md#f1-as-written-fresh-breaks-per-aggregate-order-i2).

> **Revision (P0.11, 2026-11-12; decided 2026-11-15).** Re-read the holes in the same statement, but as a `UNION ALL`
> branch rather than an `OR`: `(SELECT … WHERE global_order > :cursor ORDER BY global_order LIMIT :n) UNION ALL
> (SELECT … WHERE global_order = ANY(:holes))`, ordered by `global_order` outside. As an `OR`, the predicate is two
> index scans only in a custom plan; a generic plan (which `plan_cache_mode=auto` chose by itself with 1 000 holes
> outstanding on a 300 000-row table) walks the primary key from the bottom with the predicate as a filter, about
> 300 ms per probe at 2 million rows, and a jump range added as `OR … BETWEEN` does that even as a custom plan. The
> `UNION ALL` form is index scans in every plan mode, keeps one snapshot, does not let late commits count against the
> page limit, and costs the same server time (about 10 µs up to 10 holes, 120 µs at 1 000). A jump's ranges need a
> branch of their own, not the `OR`, and one that does not trip the JIT (§19 risk 13).
> [phase-0 record, P0.11](../archive/event-store-redesign-phase0.md#p011-re-reading-holes-in-the-tail-statement).

Retirement is tested by **set disjointness**, not numerically, because `backend_xid` is a wrapping
32-bit `xid` while the snapshot functions return a non-wrapping `xid8`.

The **watermark** of a reader is (proposed revision F2 below) the cursor, lowered to just below the lowest hole
that is not yet resolved. Every `global_order` at or below it is final: either read, or proven never to exist.

> **Revision (P0.5 F2, 2026-11-11; decided 2026-11-15).** The definition as first written, "the highest `S` of a
> retired candidate such that no older candidate is still outstanding", says nothing once the cursor has moved past the
> last candidate's `S` with no new hole, and read literally lets the watermark advance at retirement, before the hole
> read. The model's mutant of that loses events: a `CONTIGUOUS` page commits past the hole before the late commit is
> published (I12), and a crash between retirement and the hole read leaves resume points past an unhandled event (I3).
> Proposed: `watermark = min(cursor, lowest unresolved hole − 1)`, where a hole is unresolved until its candidate has
> retired **and** the hole read has returned and its rows have been published.
> [phase-0 record, F2](../archive/event-store-redesign-phase0.md#f2-the-watermark-needs-two-clarifications).

> **Revision (P0.5 F4, 2026-11-11; decided 2026-11-15).** Where a restarted tail reader starts was not specified.
> Starting at the head with the watermark at the head loses events (I13): a writer still holding a value below the
> head is invisible to a catch-up that is let past it. Proposed: a restarted reader starts at the head with one
> *initial candidate* `(head, running set)`, and its watermark is unknown until that candidate retires; until then no
> subscription catches up past the last known watermark or attaches. With no writer running at the restart, the
> watermark is the head at once. The same rule applies where a catch-up read computes the watermark with its own
> candidate (§10.1). [phase-0 record, F4](../archive/event-store-redesign-phase0.md#f4-where-a-restarted-tail-reader-starts-was-unspecified).

### 4.3 Prerequisites

Each of these is load-bearing. Each was learned on the queue and must carry over.

1. **The xid before the sequence value.** An identity default runs `nextval` when the `INSERT` evaluates
   its row, before the heap write that assigns the xid. Most `nextval` calls do not assign an xid
   themselves: only one in 32 is WAL-logged. In between, the writer holds a `global_order` and is
   invisible to `X`. The new append statement takes `pg_current_xact_id()` before any row reaches the
   insert (§5.1), so the prerequisite costs no extra statement. Whether that ordering holds is spike
   P0.1 (§18); the fallback is a separate `SELECT pg_current_xact_id()` sent in the same round trip.
   The test is the queue's `ShardOwnedWatermarkXidWindowIT` pattern: a `BEFORE INSERT` trigger holds the
   window open, and the test must fail without the fix.
2. **`backend_xid` must be fully visible.** The queue's start-up probe forces a real xid on a second
   connection and requires the first to see it (`ShardOwnedSchema.verifyWatermarkPrerequisites`).
   Reuse the same probe. Stock PostgreSQL passes it for an ordinary role. A managed service may not, and
   then the engine must refuse to start rather than lose events, and the application stays on the current engine.
   P0.3 is not a gate (decided 2026-11-15): the probe at start-up decides per database.
3. **Identity sequences with `CACHE 1`.** Values must be handed out in wall-clock order. That is the
   identity default, but the engine must verify it at start-up, because an operator can change it.
4. **Appends from outside the engine.** A raw SQL `INSERT` into an event table that skips step 1 breaks
   the rule. This must be documented. A trigger cannot fix it, because the trigger runs after `nextval`.

### 4.4 Why not `pg_current_snapshot()`

Reading the snapshot looks like the cleaner way to find running writers, because it needs no
`pg_stat_activity`. It is unsafe. `pg_snapshot_xip` lists only in-progress xids below `xmax`, and
`xmax` is `latestCompletedXid + 1`. A writer whose xid was assigned after the last completion sits at or
above `xmax` and appears nowhere. The queue measured that under eight concurrent 50 ms transactions,
98% of samples look idle.

`pg_snapshot_xmin` is safe, since nothing below it is running, but it is cluster-wide. It cannot say
*which* writers to wait for, only that the oldest one finished, so it waits on every unrelated writer
for every candidate. The `backend_xid` set waits only on writers that were running when `S` was read.
It is still global in the sense that those may be unrelated writers, but a candidate retires as soon as
that particular set has finished.

A snapshot also cannot be taken on a standby and compared with the primary's `pg_stat_activity`, so
**the watermark ties the reader to the primary**. Moving reads to a replica, which an earlier draft of
this idea suggested, is not available with this rule.

Marten reached the same conclusion from the other direction: its high-water detector started with
wall-clock skipping, then added a liveness probe over `pg_locks`, `pg_stat_activity` and
`pg_snapshot_xip` with an xmax captured at observation time, still guarded by a stale threshold. That is
evidence for asking PostgreSQL which writers are running, and the `backend_xid` set is the exact form of it.

### 4.5 Why not a cursor over a per-row transaction id

The alternative is to store `pg_current_xact_id()` in each row (`append_xid xid8`) and read
`WHERE append_xid < pg_snapshot_xmin(pg_current_snapshot()) ORDER BY append_xid, global_order`. It looks
attractive: holes vanish, because the cursor runs over values that are final by construction.

It **breaks per-aggregate order**, and the Essentials unit-of-work pattern makes the counterexample common:

1. T2 starts and writes a projection row. It is assigned xid 5.
2. T_long has xid 10. It appends A#5 and commits.
3. Some writer with xid 7 is still running, so `xmin` is 7.
4. T2 loads aggregate A, sees A#5, appends A#6 and commits.
5. A#6 (xid 5) is below `xmin` and therefore final. A#5 (xid 10) is not yet final.
6. The reader delivers A#6 before A#5.

The `global_order` cursor does not have this problem. The unique constraint on
`(aggregate_id, event_order)`, together with an `event_order` derived from committed state, forces A#5's
commit to happen before A#6's insert. A#5 therefore has the lower `global_order` and is visible no later
than A#6. The per-aggregate order guarantee of this proposal rests on exactly that argument, which is
also what today's design relies on. The new append derives `event_order` from committed state on the
server (§5.1), so the argument carries over. It must be written down as an invariant and tested (§16, I2).

> **Revision (P0.5 F1, 2026-11-11; decided 2026-11-15).** "Visible no later than A#6" is necessary for I2 but not
> sufficient: the tail read never looks below its cursor, so A#5 committing late into a hole is visible but not
> delivered until its candidate retires, after A#6 (§4.2). The argument holds for delivery only if the tail statement
> also re-reads the outstanding holes, as proposed in §4.2. It holds unchanged for a transaction of several events,
> whose values may interleave with other writers': the model checked that too.
> [phase-0 record, F1](../archive/event-store-redesign-phase0.md#f1-as-written-fresh-breaks-per-aggregate-order-i2).

### 4.6 Long-running writers

The horizon is database-wide. A long **writing** transaction anywhere — a batch job, or a session idle
in a transaction that has written — keeps candidates from retiring. Read-only transactions do not,
because they have no xid.

The queue answers this with `watermarkCap`. After 60 seconds it advances anyway and counts
`watermarkCapped`; that path can skip messages. **The event store does not need a cap that skips.**
The queue had to bound the cap because rows are deleted on acknowledgement. Event rows are never
deleted, so a candidate that has not retired costs one list entry in memory and nothing else.

What a long writer costs depends on the subscription's mode (§7):

- `FRESH`: live delivery continues above the hole. The only thing that grows is the replay window after
  a crash (§4.7).
- `CONTIGUOUS`: delivery stops at the watermark until the writer finishes. This is the price of
  exactly-once database state, and it is why `CONTIGUOUS` is not the default for event processors.

Either way the engine needs a metric: the age of the oldest unretired candidate, and the xid (and, from
`pg_stat_activity`, the pid, application name and transaction start) that holds it back. A writer holding
up subscriptions for minutes is something an operator should see.

### 4.7 What is persisted

The durable resume point must never pass an order that could still be delivered. Per subscriber it is:

```
resumeFrom = min( watermark + 1 , lowest delivered-but-not-yet-handled order )
```

After a crash, the subscription replays from `resumeFrom`. In `FRESH` mode that replays at most the
events committed since the oldest unretired candidate, which is within the at-least-once contract. In
`CONTIGUOUS` mode nothing above the watermark was handled, so with progress in the handler's transaction
(§8) nothing is replayed at all.

The value is a `GlobalEventOrder`, stored on the subscription's row in the engine's own progress table
(§8.2), next to a reset epoch that does what S6's reposition epoch does today. It is not stored in
`durable_subscriptions` (decided 2026-11-15).

> **Revision (P0.5 F5, 2026-11-11; decided 2026-11-15).** The same formula also gives the catch-up position of a
> subscription that detaches after an overflow (§6), with the event that did not fit counted as delivered but not
> handled: `min(watermark + 1, lowest buffered order, the order that did not fit) − 1`. Resuming from the watermark
> instead loses buffered, unhandled events (I13). An open page of an in-transaction subscription (§8.3) is left out of
> that position; if the page then rolls back, its events go back into the subscription's buffer.
> [phase-0 record, F5](../archive/event-store-redesign-phase0.md#f5-the-position-a-detaching-subscription-resumes-from-was-unspecified).

### 4.8 Sequence jumps

A `setval` or a restore that jumps the sequence by a million values is one hole range. It costs one
candidate and one ranged read when it retires, which is an indexed range scan returning only the rows
that exist. No rows are written. The present design needed `GapMiddlesAwaitedInMemory` to avoid writing
a million rows.

### 4.9 Why no tombstones

Marten writes a tombstone row at every seq_id a failed save consumed, so its high-water mark need not
wait for the stale threshold. Here a failed transaction resolves its own holes: the moment it rolls back,
its xid is gone from `pg_stat_activity`, and the next probe retires every candidate that was waiting only
on it. The cost of a failed append is therefore one probe, not a write and not a timer.

Two kinds of failure are still worth reducing, because each hole means at least one probe statement and,
in `CONTIGUOUS` mode, a short stop:

- **Lost optimistic-concurrency races.** Removed by checking before the identity value is taken (§5.1).
- **Rolled-back units of work** after the append succeeded. These cannot be prevented and need nothing
  beyond the rule.

### 4.10 One statement per probe

Marten's detector learned (#4953 there) that probes split across statements under READ COMMITTED see
different snapshots, and a commit landing in between advances the mark over an in-flight value. The rule
in §4.2 has the same shape: `S` must be read before `X`. The current plan issues them as two statements,
which is correct but costs a round trip whenever a hole is outstanding.

While at least one candidate is outstanding, or while the previous read revealed a new hole, the tail
reader instead uses one statement:

```sql
SELECT e.<columns>,
       (SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL) AS running
FROM <event table> e
WHERE e.global_order > ?
ORDER BY e.global_order
LIMIT ?
```

(The final shape may return `running` once rather than per row, for example as a `UNION ALL` row or a
second result set; the point is one statement. Whatever the shape, it must return the running set even when
the tail read returns no rows: as written above, an empty tail never evaluates the sub-select, and an empty
read would then reveal nothing about outstanding candidates.)

> **Revision (P0.11, 2026-11-12; decided 2026-11-15).** With F1's hole re-read (§4.2) the statement above becomes
> `SELECT u.*, <running> FROM (<tail read> UNION ALL <read of the holes by = ANY>) u ORDER BY u.global_order`, not a
> `WHERE … OR …`: only the `UNION ALL` form stays on index scans under a generic plan, which the engine gets by default
> once pgjdbc server-prepares the statement. [phase-0 record, P0.11](../archive/event-store-redesign-phase0.md#p011-re-reading-holes-in-the-tail-statement).

> **Revision (P0.12, 2026-11-12; decided 2026-11-15).** Read the running set from `pg_stat_get_activity(NULL)`, the
> function behind the view, instead of `pg_stat_activity`. It returns the same `backend_xid` from the same
> per-transaction status snapshot, so P0.2's conditions are unchanged. It saves 70-110 µs per probe at 10-100
> connections (the view's joins), but not at 400. Either form costs about 0.55 µs of execution per live backend in the
> cluster (about 0.24 ms at 400 connections), and much more under write load, so "one cheap statement" holds only for
> small clusters. See §7.3 and §19 risk 16. [phase-0 record, P0.12](../archive/event-store-redesign-phase0.md#p012-cost-of-the-probes-pg_stat_activity-read).

The probe must run in autocommit, as a statement in its own transaction, or immediately after
`pg_stat_clear_snapshot()` when it has to run inside a transaction. Spike P0.2 (§21) found that the backend
status behind `pg_stat_activity` is cached per transaction: inside an explicit transaction that had already
read `pg_stat_activity`, the probe took a fresh READ COMMITTED snapshot but reused the earlier running set, and
missed a writer.

Why this should satisfy the rule: the statement's MVCC snapshot is taken when it starts, before the
executor reads `pg_stat_activity`. A writer holding a `global_order ≤ S` that is invisible to the
snapshot was running when the snapshot was taken and, by §4.3, already had its xid. When the executor
then reads `pg_stat_activity` it either is still running (and is in `X`) or has finished (and its value
is resolved). `S` is effectively read before `X`.

What must be checked, in spike P0.2: that `pg_stat_activity`'s per-transaction backend-status snapshot is
first taken during the statement's execution and not earlier, also on the extended query protocol with
pgjdbc's server-side prepared statements, and that the planner cannot hoist the sub-select into an
earlier phase. The test is an xid-window IT that holds a writer between taking its value and committing.
If the check fails, the reader keeps the two-statement form; nothing else in the design changes.

When no candidate is outstanding and the last read was contiguous, the plain tail read without the
sub-select is used, so an idle or contiguous system pays nothing (the queue measured the probe at
0.086 ms and skips it when caught up).

---

## 5. The append path

### 5.1 One statement per aggregate per unit of work

```sql
WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),
     head AS MATERIALIZED (SELECT coalesce(max(event_order), -1) AS v
                             FROM <event table> WHERE aggregate_id = $1),
     ok   AS (SELECT head.v FROM head, xid
               WHERE $2::bigint IS NULL OR head.v = $2)
INSERT INTO <event table> (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id,
                           event_type, event_revision, timestamp, event_payload, event_metadata, tenant)
SELECT $1, ok.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts,
       e.payload::<eventJsonColumnType>, e.metadata::<eventMetadataJsonColumnType>, $3
FROM ok
CROSS JOIN unnest($4::<eventIdColumnType>[], $5::<eventIdColumnType>[], $6::<correlationIdColumnType>[],
                  $7::text[], $8::text[], $9::timestamptz[], $10::text[], $11::text[])
           WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)
RETURNING global_order, event_order;
```

- `$2` is the caller's expected last event order (`appendEventsAfterEventOrder`), or null when the caller
  gave none. Event orders start at 0 and `NO_EVENTS_PREVIOUSLY_PERSISTED` is −1, hence the `-1` in `head`
  (corrected after P0.4; the first draft had `0`, which would have started every stream at 1).
- **Zero rows returned means an optimistic-concurrency conflict**, reported as
  `OptimisticAppendToStreamException` with today's message. No `global_order` was consumed, so no hole. This
  covers a conflict with an append that committed before the statement started; one with an append still in
  flight is §5.3's race, and P0.4 measured that as about half of all conflicts under contention.
- **No round trip to find the last event order.** `head` is an index-only backward scan of the table's
  `UNIQUE(aggregate_id, event_order)` index, run on the server. `event_order` is still derived from
  committed state, so the ordering argument in §4.5 holds.
- **One statement, one plan, whatever the number of events.** Payloads are bound as text arrays and cast
  to the configured column type, so the persisted bytes are what the serializer produced, and the wire
  format is untouched. Column names come from the engine's own table layout (§12.4, §13.3), which need not
  match today's `EventStreamTableColumnNames`; the statement is generated once per table, as today's insert
  SQL is.
- **The xid is taken before the identity value.** Every output row of the join needs a row from `ok`,
  and `ok` scans the materialised `xid`, so `pg_current_xact_id()` has run before the first row reaches the
  insert, where the identity default calls `nextval`. That is the reasoning; spike P0.1 has to prove it,
  including under a plan that puts `unnest` on the outer side of the join.
- Interceptors, `PersistableEventEnricher`s, serialisation and the `PersistedEvent` result stay client-side,
  above the engine, as today (in the `EventStore` adapter for append and stream load, §19 item 19). One difference (P0.4): without an expected order the event order is
  only known after the insert, so the `PersistableEventMapper` and enrichers get a provisional order and the
  `PersistedEvent` is built from `RETURNING event_order`.

### 5.2 Several aggregates in one unit of work

One statement per (table, aggregate), sent as one pgjdbc batch, so a unit of work that touches several
aggregates still costs one round trip. Each statement's zero-row result is checked individually, so the
right aggregate is named in the exception.

### 5.3 A race that both writers win the check

Under READ COMMITTED two writers can both read the same `head` and both pass the check: `head` reads the
statement's snapshot, so a competitor that has inserted but not yet committed is invisible. The loser then
fails on the unique index after `nextval`, exactly as today, and leaves one hole. P0.4 measured this as not
rare: with 8 writers on 10 aggregates about half of all conflicts were of this kind, and §5.1 burned 248-270
identity values per 1 000 successful appends against today's 463-465 — half, not none.

*Note 2026-11-11:* the "half" depends on the client, not only on the statement. With raw pgjdbc and a fast lookup
the same statement burned 289-299 per 1 000; adding only a longer gap between the writer's lookup and its append
brought it to 216-225 (~70 µs lookup phase) and 181-184 (~180 µs), by turning would-be unique violations into
zero-row conflicts. Encoding, the engine's exact row and the tenant index did not move it.
[phase-0 record, P0.7](../archive/event-store-redesign-phase0.md#p07-table-design-options).

For aggregates known to be hot, offer an opt-in `pg_advisory_xact_lock(<hash of table, aggregate id>)`, **sent as
its own statement before the append**. It serialises writers of that one aggregate until commit, which is what
Marten's `FOR UPDATE` on the stream row does, and the append that follows starts with a snapshot that includes the
previous holder's commit, so every conflict becomes a zero-row result: P0.4 measured zero holes. It costs one
statement and round trip per append and, on a hot aggregate, throughput (a third of today's in P0.4's shape), which
is why it is opt-in. The lock must **not** be a CTE of the append, as the first draft said: the statement's
snapshot is taken before it waits for the lock, so `head` is still stale when the lock is granted, and P0.4
measured no reduction in holes at all with that shape.

*Note 2026-11-11:* a stream head table (`aggregate_id` primary key, `version`), moved by a guarded
`UPDATE … WHERE version = $expected` inside the append statement, gave zero holes in one statement and the same round
trips as §5.1, at 8-18% below §5.1's contended throughput in that spike's set-up, against a third of today's
for the advisory lock; a `SELECT … FOR UPDATE` first also gave zero holes, at about 30% below. This needs a new
table, so it is outside "the same rows" of §5.4 and §12.4. Findings, not a decision:
[phase-0 record, P0.7](../archive/event-store-redesign-phase0.md#p07-table-design-options). No cheaper parameter encoding brought §5.1's
contended throughput up to today's engine; the extra server CPU is not Bind-time array parsing (same doc, §6).
*Note 2026-11-15:* the new engine now has its own tables and the move to it is one-way (§12.4), so a stream head
table is no longer ruled out by the schema. Decided 2026-11-15 (§19 item 23): the engine adopts it.

### 5.4 Relationship to the current engine

The single-statement append has no dependency on the new engine: it is a better way to write the same
rows. It could also replace the current engine's `PreparedBatch`. P0.4 measured what that would buy: about half
the lost-race holes the current gap handler has to wait 120 s for (not all of them, see §5.3), about a third less
latency for appends of 100 events, half the latency and 2 instead of 6 or 11 round trips for five aggregates in one
unit of work, and one round trip fewer whenever no expected order is given; nothing for appends of 1-10 events with
an expected order, where it ties. Decided 2026-11-15: the current engine is not changed, so the single-statement
append is built in the new engine only.

---

## 6. One shared reader per aggregate type per JVM

Without per-subscriber gap state, every subscription that is at the tail wants exactly the same rows.
Each JVM therefore runs one **tail reader** per aggregate type. It owns the cursor, the candidate list,
the watermark and the read statement, and it publishes each batch, together with the current watermark,
to its *attached* subscriptions.

```mermaid
flowchart LR
    W["wake-up<br/>(notify · local append · backstop)"] --> R
    R["tail reader<br/>(aggregate type T)<br/>cursor · candidates · watermark"] -->|"batch + watermark"| B1["subscription A (FRESH)<br/>bounded buffer"]
    R -->|"batch + watermark"| B2["subscription B (CONTIGUOUS)<br/>holds back above watermark"]
    C["subscription C<br/>(behind)"] -->|"own catch-up reads<br/>(filtered, capped)"| DB[(event table T)]
    R --> DB
    C -. attaches at the watermark (proposed, F3) .-> R
    B1 --> H1["delivery thread A<br/>(virtual, serial)"]
    B2 --> H2["delivery thread B"]
```

- **Catch-up.** A subscription that is far behind reads on its own, in bounded batches (§10), until its
  catch-up position has reached the reader's watermark (proposed revision F3 below; first written as "within its
  buffer's distance of the tail"). It then attaches. This is the shape `BackfillThenLiveOrdered`
  already has, with the tail reader in place of the CDC bus, and the same rule applies: attach first,
  then read up to the head, then drain (module `CLAUDE.md`, "every move onto the CDC bus catches up first").
  The read after attaching (the bridge) covers `(catch-up position, reader cursor at attach]`.
- **A slow subscription must never slow the reader.** When its buffer is full, it detaches and goes back
  to catch-up, instead of applying back-pressure to the reader. That is the CDC bus overflow lesson
  (F-871): a multicast paced by its slowest subscriber stalls everyone. For `CONTIGUOUS` it is also a
  correctness rule (proposed revision F6 below). The catch-up resumes from §4.7's formula (proposed revision
  F5 below).
- **Delivery stays serial per subscription.** Each subscription has its own delivery thread. A virtual
  thread is fine, but it must not be shared. Retries, backoff and the shrinking of a failed page (§8.3) run
  on that thread.
- **The reader's dispatch decision is inline and single-threaded.** Cursor, candidate list, hole set and
  watermark are touched by one thread only. The queue's §9.23 is the cautionary tale: making that
  decision asynchronous produced a duplicate.
- **An interrupted handler is not handled**, so the resume point does not move past it.
- **Tenants** are filtered per subscription, after the shared read, as both paths do today.

> **Revision (P0.5 F3, 2026-11-11; decided 2026-11-15).** Attaching "within its buffer's distance of the tail"
> cannot happen while a long writer holds the watermark down: catch-up stops at the watermark (§10.1), the distance to
> the reader's cursor stays above the buffer size, and a `FRESH` subscription that is behind gets nothing above the
> hole until the writer ends, which defeats the point of the mode. Proposed: attach once the catch-up position has
> reached the watermark, and bridge `(position, reader cursor]`. That range is not bounded by the buffer; it is
> everything the reader has read above the hole. For `CONTIGUOUS` the bridged rows are held back above the watermark,
> so behind a long writer a `CONTIGUOUS` subscription can attach, overflow and detach repeatedly, one bridge read each
> time. That is a cost, not a loss, and the model does not measure it: open question, §19 risk 14.
> [phase-0 record, F3](../archive/event-store-redesign-phase0.md#f3-6s-attach-rule-cannot-be-met-behind-a-long-writer).

> **Revision (P0.5 F5, 2026-11-11; decided 2026-11-15).** "Detaches and goes back to catch-up" did not say from
> where. Resuming from the watermark loses events that were buffered but not yet handled (I13). Proposed: catch-up
> resumes from §4.7's formula, `min(watermark + 1, lowest buffered order, the order that did not fit) − 1`; an open page
> of an in-transaction subscription is left out of it and, if the page rolls back, its events go back into the buffer
> (§8.3); and a `CONTIGUOUS` subscription drops anything at or below the last order it applied, because the catch-up
> that follows overlaps what the open page committed. [phase-0 record, F5](../archive/event-store-redesign-phase0.md#f5-the-position-a-detaching-subscription-resumes-from-was-unspecified).

> **Revision (P0.5 F6, 2026-11-11; decided 2026-11-15).** Detaching instead of blocking is load-bearing for
> `CONTIGUOUS` correctness, not only for latency. With back-pressure, a `CONTIGUOUS` buffer full of events held back
> above the watermark blocks the reader, and only the reader can advance the watermark that would release them: after
> every writer has finished nothing moves again (I1 and I9 fail in the model). The rule must therefore stay even if a
> later version wants back-pressure for `FRESH`. [phase-0 record, F6](../archive/event-store-redesign-phase0.md#f6-back-pressure-would-deadlock-contiguous-not-just-slow-it).

**Expected effect.** For a caught-up system, read statements become *aggregate types × JVMs × wake-ups*,
independent of the number of subscriptions. This is the largest resource gain in the proposal, and it is
the one that §17 must measure first.

**Key-partitioned processing (EF-2).** The shared reader makes EF-2's option (b) cheaper. One JVM reads
the tail once and dispatches only the units it owns, each with its own resume point. That is a later
step, not part of the first version.

---

## 7. Delivery consistency: `FRESH` and `CONTIGUOUS`

### 7.1 The two modes

The watermark design and Marten's daemon differ in one decision: whether a subscriber may receive events
above a hole that is not yet resolved. Each answer is right for some subscribers, so it becomes an
explicit per-subscription setting.

| | `FRESH` | `CONTIGUOUS` |
|---|---|---|
| Delivers | Everything the reader has read. A late commit below the cursor arrives with the next probe that sees it, through the re-read of outstanding holes (proposed, F1 below), after higher orders of other aggregates | Only events at or below the watermark, in strict `global_order` |
| Per-aggregate order | Kept (§4.5) | Kept, trivially |
| Order across aggregates of the type | Commit-visible order, with late commits out of place | Strict `global_order` |
| A long writer elsewhere | Delays only the events in its holes | Delays every event above its holes |
| With progress in the handler's transaction (§8) | At-least-once; a crash replays above the watermark | **Exactly-once database state**; a crash replays nothing |
| Equivalent today | The current contract | Marten's async daemon |

Both modes consume the same shared read. A `CONTIGUOUS` subscription keeps the events above the
watermark in its buffer and releases them as the watermark advances, so it costs memory, not statements.
Its buffer bound applies to held-back events too: a subscription that cannot hold them detaches and
catches up, which reads only up to the watermark.

> **Revision (P0.5 F1 and F3, 2026-11-11; decided 2026-11-15).** The `FRESH` row first read "A late commit below
> the cursor arrives when its candidate retires, after higher orders". With that, "Per-aggregate order: Kept (§4.5)"
> does not hold: the late A#n arrives after A#n+1 (§4.2). It holds in the model when the tail statement also re-reads
> the outstanding holes, which is what the row now says; a late commit still arrives after higher orders of *other*
> aggregates. `CONTIGUOUS` is unaffected by F1. For F3: a `CONTIGUOUS` subscription that attaches behind a long writer
> holds the bridged rows above the watermark, so its buffer bound can make it attach, overflow and detach repeatedly
> until the writer ends (§6, §19 risk 14). [phase-0 record, F1](../archive/event-store-redesign-phase0.md#f1-as-written-fresh-breaks-per-aggregate-order-i2), [F3](../archive/event-store-redesign-phase0.md#f3-6s-attach-rule-cannot-be-met-behind-a-long-writer).

### 7.2 Defaults

Recorded as decision D1 (§20):

- **View projections** (any subscription that declares its output is in the same database, §12.3):
  `CONTIGUOUS`. Rebuild-safe, exactly-once state, no idempotency burden on the projection.
- **Event processors and everything else** (subscriptions with side effects outside the database): `FRESH`.
  They are at-least-once whatever the mode, and gain nothing from waiting.

Each subscription can override its default. The admin API and the subscription metrics show the mode.

### 7.3 Freshness of `CONTIGUOUS`

The watermark only moves when the reader probes. While a candidate is outstanding, the reader re-probes
on each wake-up and, with no wake-up, on a short probe interval (configurable, low tens of milliseconds by
default; the probe is one cheap statement, §4.10). With no writer running, a candidate retires on the
first probe after the writers that held it finish, so in the common case `CONTIGUOUS` lags `FRESH` by one
probe interval. §17 measures this.

> **Revision (P0.12, 2026-11-12; decided 2026-11-15).** The probe is not cheap at scale. Its running-set read costs
> about 0.55 µs per live backend in the cluster, and every JVM's tail reader runs it every probe interval while holes
> are outstanding, which under write load is nearly always. Cluster-wide cost therefore grows with JVMs × connections.
> On the VM, 40 readers probing every 10 ms against 400 connections halved concurrent appends (49-52 thousand to 25-26
> thousand per second; append p99 11 → 55-64 ms), where the same readers without the running set did not. The
> interval should not be a fixed "low tens of milliseconds" per JVM. Options to weigh: an interval that backs off with
> the cluster's connection count or with the read's measured cost; waiting for a wake-up before re-probing; and fewer
> running-set readers than JVMs. Not measured on a dedicated machine. §19 risk 16;
> [phase-0 record, P0.12](../archive/event-store-redesign-phase0.md#p012-cost-of-the-probes-pg_stat_activity-read).

---

## 8. Progress and resume points

### 8.1 Two ways to save a resume point

| | Periodic | In the handler's transaction |
|---|---|---|
| Who | Subscribers with output outside the database, or that opt out | Subscribers whose output is in the same database; the default for `CONTIGUOUS` |
| How | Batched `UPDATE` on an interval, in its own transaction, fenced (§9) | The handler's unit of work also runs the resume-point `UPDATE`, fenced, before it commits |
| Writes | One per interval per subscriber | One per page per subscriber |
| After a crash | Replays from the last save | Replays nothing in `CONTIGUOUS`; replays above the watermark in `FRESH` |

### 8.2 The statement

Progress lives in a table the engine owns, designed for this model, not in the current engine's
`durable_subscriptions` rows (decided 2026-11-15). One row per subscription and aggregate type holds the
resume point (§4.7), the reset epoch, the lease and fence columns of §9 and whatever an open page needs. The
exact columns are open (§19); the names below are illustrative.

```sql
UPDATE <progress table>
   SET resume_from = :resume_from,
       updated_at  = now()
 WHERE subscription_id = :subscription_id
   AND aggregate_type  = :aggregate_type
   AND reset_epoch     = :epoch
   AND lease_fence     = :fence          -- exclusive subscriptions only, §9
```

- `reset_epoch = :epoch` makes it a compare-and-set: a reset that happened since the page was read
  turns the update into zero rows, and the handler's whole transaction rolls back, output included. That
  is what S6's reposition epoch does today, made atomic with the output.
- The fence is a column of the row being written, so PostgreSQL's re-check of the `WHERE` after waiting
  for a row lock sees the current owner's value (§9).
- Zero rows is ambiguous (stale fence or superseding reset). The handler asks once, by primary key, which
  it was, as §9 describes.
- `updated_at` uses the database's clock (history §9.11).
- The resume-point and lease columns must stay unindexed so the update is HOT. Document a table fillfactor
  below 100 for deployments with many subscribers.
- Importing the current engine's resume points into this table once, when an application first starts on
  the new engine, is optional (§12.4).

### 8.3 A page per transaction

Marten projects a page of events per transaction, which is a large part of its throughput. The new
engine does the same for in-transaction progress: one unit of work per page, the handler invoked per
event inside it, one resume-point update at the end.

- **A failing page shrinks** (decided 2026-11-15). If any event in the page throws, the page's transaction
  rolls back and the subscription retries with smaller pages (bisecting, or stepping down as P0.13
  proposes; stepping down was decided, §19 item 21) until the failing event is in a page of its own, while the pages around it commit. The failing
  event is retried with backoff and, when its retries are used up, **parked** for that subscription with
  its error. Later events of the same aggregate are parked behind it, in order, until it is retried or
  discarded; every other aggregate continues (decided 2026-11-15, §19 item 18, as processors hold an aggregate behind
  its dead letter today). Page size then grows back after pages that commit (P0.13's
  revision below). Retry count, backoff and the shrink policy are engine settings per subscription.
  This is not today's `SubscriptionErrorPolicy`, `FailFast` or redelivery/dead-letter model, and it does
  not inherit the current queue consumer's built-in list of permanent errors.
- Parked events are visible and actionable through the admin API (retry, discard). A parked row references
  the event by aggregate type and `global_order`, without a copy; the resume point moves past a parked event, and
  the parked rows are what remains to be delivered for their aggregates (§19 item 18).
- The page size is bounded by count and by time (the page closes when the buffer is empty), so a quiet
  system does not hold events back to fill a page.
- In `FRESH` mode the resume point written is §4.7's formula, not the page's highest order.

> **Revision (P0.5 F5, 2026-11-11; decided 2026-11-15).** A subscription can detach (§6) while a page is open. The
> open page is left out of the detach position; if it rolls back, its events go back into the subscription's buffer
> ahead of the catch-up, and if it commits, a `CONTIGUOUS` subscription drops every event at or below the last order it
> applied, because the catch-up that follows overlaps what the page committed. That is what the model does, and it
> holds I12 and I13 with it; no mutant without them was run. [phase-0 record, F5](../archive/event-store-redesign-phase0.md#f5-the-position-a-detaching-subscription-resumes-from-was-unspecified).

> **Revision (P0.13, 2026-11-12; decided 2026-11-15).** Measured in [phase-0 record, P0.13](../archive/event-store-redesign-phase0.md#p013-projection-progress-per-event-per-page-and-periodic): a page per transaction is about 2x today's periodic save when the handler issues one statement per event (one
> round trip per event is then the floor, and pages above about 100 events gain nothing) and 10-13x when the page's
> writes reach the server at once. Two changes follow. (1) **The fallback backs off.** As written, a page that fails is
> replayed per event and page mode then resumes at full size, so every failure costs a whole page again: at one failing
> event in 100, a 100-event page fails every time and throughput fell below one transaction per event. After a failed
> page the subscription should continue with smaller pages (or per event) and grow back after pages that commit, so the
> cost is bounded by the failure rate rather than multiplied by the page size. (2) **A page-aware handler shape**: the
> new subscription API (§12.3) hands the handler the page, which it can write as one batch or one set-based statement;
> that is where the 10x is. A per-event handler is the row-by-row case on top of it. (First written, before the
> 2026-11-15 decision on a new subscription API, as an opt-in beside the per-event `@MessageHandler`.) Also: the default page size need not exceed about 100, since larger pages bought
> nothing row by row and made view updates lose HOT inside the longer transaction (more WAL per event).

### 8.4 Exactly-once in `FRESH` mode (later, optional)

`FRESH` can only reach exactly-once database state if the resume point also records which orders above
the watermark were already applied. That would be a `delivered_above_watermark bigint[]` column on the
engine's progress row (§8.2), bounded by the in-flight window. It is listed as a later item, because
`CONTIGUOUS` covers the subscribers that need exactly-once state.

---

## 9. Exclusive subscriptions: ownership and fencing

Today an exclusive subscription holds a `FencedLock`. Its resume-point save
(`PostgresqlDurableSubscriptionRepository`, the batched `UPDATE`) is guarded by `reposition_epoch`, but
**not** by the lock's fence token. A former owner that has lost the lock without noticing — a GC pause,
or a partition — can still write the resume point after the new owner has taken over. The row then flaps
between two values until one of them stops.

Proposal (decided 2026-11-15): the new engine owns exclusive subscriptions itself, through **lease and fence
columns on the subscription's progress row** (§8.2). It has no `FencedLockManager` dependency.

- **Claim.** A node claims a subscription whose lease is free or expired, and reads the resume point and reset
  epoch it starts from, in one statement on that row: `UPDATE … SET lease_owner = :node, lease_fence = <next
  fence>, lease_until = now() + :lease WHERE <key> AND (lease_owner IS NULL OR lease_until < now()) RETURNING
  lease_fence, resume_from, reset_epoch`. The fence is assigned by the database and only ever increases, so a
  claim that arrives late cannot install an older fence over a newer one. How fences stay unique when a row is
  deleted and recreated is open (§19).
- **Renewal.** All lease time is server time (`now()`), never the JVM's clock. A JVM renews every exclusive
  subscription it owns in one statement per renewal pass (`… WHERE (key, lease_fence) IN (SELECT * FROM
  unnest(:keys, :fences)) RETURNING key`); a subscription missing from the result has lost its lease and stops.
- **Every progress write asserts the fence** — periodic or in the handler's transaction — in the same statement,
  on the same row (`AND lease_fence = :fence`, §8.2). Because the condition is on the row being written, the
  re-check PostgreSQL performs after waiting for another transaction's row lock sees the new owner's fence, so
  the check is exact. With progress in the handler's transaction this also fences the projection's output: a
  stale owner's page rolls back as a whole.
- Zero rows updated is ambiguous: it means either a stale fence or a superseding reset. Ask once, by
  primary key, which of the two it was. Lost ownership stops the subscription. A superseding reset is
  S6's case, restated for the reset epoch (§10.3): the save is dropped and delivery restarts from the reset point.
- An owner that cannot confirm it is alive — it cannot reach the database to renew — stops delivering
  before its lease can have expired on the server (shard-owned §8.6). This is Marten's "monitored lock" with an
  assertable fence. Lease duration, renewal interval and the local stop deadline are engine settings (§19).
- Non-exclusive subscriptions and administrative resets write without a fence; a reset refuses the owner's older
  saves through the reset epoch.

> **Revision (2026-11-15, decided; restated for engine leases the same day).** The fence is a column on the
> resume-point row, not an `EXISTS` against a separate lock table. The `EXISTS` form, first proposed against the
> fenced-lock table, fenced only when the lock manager was `PostgresqlFencedLockManager` in the same database, and it
> left a race: when a stale `UPDATE` waits for the new owner's row lock, PostgreSQL re-checks the `WHERE` against the
> new row version but evaluates a sub-select on another table with the old snapshot. A first version of the column
> form kept `FencedLock` and added an `owner_fence` column to `durable_subscriptions`; it was built in the current
> engine on 2026-11-15 and reverted the same day, since the current engine is not changed (commit `d2c3e3b51`, in no
> branch, has the code and its IT). The new engine keeps the column form but puts lease and fence on its own progress
> row (decided 2026-11-15, §18). Built in phase 7; I5's test keeps the unfenced shape as its must-fail variant.
>
> How the fence behaves when things go wrong:
>
> - **Node crash.** The lease is no longer renewed and expires by server time; another node claims the row with the
>   next fence. The crashed node never writes again; restarted, it claims like any other node. Nothing needs cleaning
>   up: the claim passes over the expired lease.
> - **The owner loses its connection.** It cannot renew, so it stops delivering at its local deadline, before the
>   lease can expire on the server; another node claims once it has. When the old node's connection returns, its
>   pending periodic save, or its final save on the way out, updates no row. It reads the stored fence once, logs one
>   warning that ownership has moved, and marks the snapshot superseded, so it is not retried.
> - **Connection loss with no takeover yet.** The row still carries the owner's fence, so its saves land, which is
>   right: nobody else is processing.
> - **Graceful handover.** The owner's final save also releases the lease, in the same statement, so the next claim
>   need not wait for expiry. If that save fails, the lease expires instead and the new owner starts from the last
>   save that landed: a replay within at-least-once, never a stale overwrite.
> - **A claim delayed past a newer owner's** (A sends its claim and pauses, B claims, A's claim arrives). A's claim
>   finds a lease that is held and not expired, updates no row, and A does not subscribe. The fence is assigned by the
>   database, never by the claimant, so no ordering of claims can lower it.
> - **A save whose commit outcome is unknown.** A periodic save is not marked persisted, and the next save retries it
>   under the same fence. A page whose commit outcome is unknown is resolved by reading the progress row before the
>   next page: its resume point says whether the page committed.
> - **A renewal pass that partly fails.** Subscriptions whose lease was taken over are missing from the pass's result
>   and stop; the rest continue. The renewal never extends a lease whose fence has changed.
> - **An operator deletes or rewrites the progress row.** A deleted row makes every save and renewal of the owner
>   update nothing, and the check by primary key finds no row: the subscription stops with an error rather than
>   recreating the row, which would lose the resume point. A fence written back to a lower value must not be
>   reachable through the admin API; if it happens anyway, the owner's saves fail and it stops, and the risk is that a
>   stale owner's fence comes round again. Whether fences come from a sequence, so that they never repeat even across
>   a deleted row, is open (§19).

Batched renewal of many leases per JVM (above) comes with the new engine's exclusive subscriptions in phase 7.
Sharing one lease between several subscriptions, as the queue's units do, belongs to EF-2 and a later phase. The
current engine is not changed (decided 2026-11-15).

---

## 10. Catch-up, filtering and rebuilds

### 10.1 Filtered catch-up reads

A subscription that is behind reads on its own (§6). Many subscribers consume only a few event types, and
today they read every row and discard most of them. Under the watermark they need not:

```sql
SELECT … FROM <event table>
 WHERE global_order > :from AND global_order <= :watermark
   AND event_type = ANY(:types)          -- and tenant = :tenant, where the subscription is tenant-scoped
 ORDER BY global_order
 LIMIT :n
```

- **The ceiling is the watermark**, which the catch-up read takes from the type's tail reader (or computes
  with its own candidate when no tail reader runs). Every order at or below it is final, so a filtered
  read can never miss a late commit. While that watermark is unknown (a restarted reader whose initial candidate
  has not retired, or a catch-up's own first candidate), the read goes no further than the last known watermark
  (proposed revision F4, §4.2).
- **Reaching the watermark is the attach condition** (proposed revision F3, §6). Behind a long writer, catch-up
  stops at the watermark, so a rule based on distance to the tail would never be met.
- **A page that returns fewer than `:n` rows advances the subscription to the watermark**, not to its last
  row. A projection that consumes rare event types does not crawl through its range.
- **An opt-in `(event_type, global_order)` index** per table makes these reads index scans. It costs
  write latency on every append, so it is per aggregate type and documented as such.
- **Adaptive fallback when a filtered read hits a statement timeout**, as Marten does: probe the next
  matching order with `… ORDER BY global_order LIMIT 1` (not `min()`, which can degrade to a bitmap
  scan), then fall back to fixed windows of `global_order`.
- Streaming reads with `setFetchSize` and autocommit off, so a large page is not materialised.

The shared tail read stays unfiltered: it serves every attached subscription, and the rows are already
in memory.

Why this was unsafe before and is safe now: the current gap handler infers holes from the rows a read
returns. A filtered read returns non-contiguous orders, which look like gaps, and those "gaps" would be
recorded and eventually promoted. The watermark does not infer anything from rows.

### 10.2 Bounded catch-up concurrency

Catch-up reads take a permit from a per-database semaphore (default 4, configurable), separate from the
connection pool. Restarting a JVM with 50 subscriptions that are all behind then reads at a bounded rate
instead of exhausting the pool and starving the appenders. The shared tail reader does not take a permit.

### 10.3 Rebuilds and resets

A reset (the new API's counterpart of `resetFrom`) sets the resume point and increments the reset epoch on
the subscription's progress row in one statement, so every save made under the old epoch, periodic or in a
handler's transaction, updates nothing (§8.2): S6's guarantee, on the engine's own table. A reset
subscription detaches, catches up through §10.1 and attaches again. For a `CONTIGUOUS` view projection, a rebuild is: truncate the projection's tables and reset the
subscription, in one transaction, then catch up with progress in the handler's transaction; at every point
the projection's state matches its resume point. Blue/green rebuilds into a second table, as Marten does
with projection versions, are a later item.

---

## 11. Wake-up

Three tiers, applying the queue's design (shard-owned §6). As there, correctness never depends on any tier.

| Tier | Mechanism | Replaces |
|---|---|---|
| 0 — backstop | The reader parks until a backstop interval elapses, then reads. While a candidate is outstanding it also re-probes on the probe interval (§7.3) | the polling optimizers' sleep |
| 1 — cross-JVM | The appending unit of work issues one `pg_notify('essentials_eventstore', '<table>')` per table per commit, inside the transaction. One listener connection per JVM blocks on `PGConnection.getNotifications(timeoutMs)` and wakes the matching readers | S1's per-row trigger and `MultiTableChangeListener`'s timed polling |
| 2 — same JVM | A commit callback (`PersistedEventsCommitLifecycleCallback` exists already) wakes the local reader directly, with no database involved | nothing |

Rules carried over:

- **No per-row trigger.** S1 installs one (`ListenNotify.addChangeNotificationTriggerToTable`), which
  turns every event into a notification. The application-issued notify is coalesced and payload-light.
- **The listener does no work.** It only signals. It reconnects *and* re-runs `LISTEN`, and that path is
  tested by counting notifications, since delivery-based tests cannot see it fail.
- **Tier 2 wakes; it does not hand events over**, at least in the first version. The queue's local
  hand-off was measured to work only half the time (history §9.4). Waking a reader that then reads costs
  one statement and keeps a single delivery path. A direct hand-off is a later, measured optimisation.
- **A rolled-back writer's hole retires on the next probe.** Tier 1 notifies only on commit, so a
  rollback does not wake other JVMs; their probe interval (§7.3) covers it.

> **Revision (P0.10, 2026-11-12; decided 2026-11-15).** Tier 1 as written, `pg_notify` inside the appending
> transaction, contradicts what the shard-owned queue measured (`durable-queue-measurements.md` §3.14) and what P0.10
> measured on the append path ([phase-0 record, P0.10](../archive/event-store-redesign-phase0.md#p010-wake-up-notification-cost)). A notifying commit takes PostgreSQL's cluster-wide
> NOTIFY lock and holds it through its WAL flush, so notifying commits run one at a time: with `fsync=on`, 8 and 20
> writers appended 57-77 % less than without a notification, commit p50 rose from 0.26-0.36 ms to 0.73-2.0 ms, and the
> writers spent 80-93 % of their active time waiting for that lock (container with a fast virtual disk; a real disk is
> expected to be worse). Proposed: the appending unit of work does not notify; after commit it hands the tables it
> wrote to a per-JVM notifier that sends whatever accumulated while its previous send was in flight as one
> `pg_notify … FROM unnest(?)` on its own connection, as the queue's `ShardWakeupNotifier` does. Measured cost of that:
> no throughput or commit-latency difference from no notification at all, and 0.1-0.2 ms (p50) more wake latency.
> Moving the notify out of the transaction without coalescing is not enough: per-writer notifies after commit still
> serialised on the lock (60 % fewer appends at 20 writers with `fsync=off`). What after commit costs: a crash between
> commit and send, or a failed batch, loses the hint, and other JVMs find the events through the tier-0 backstop, as
> they would after a lost notification today. Tier 2 (same JVM) is unaffected, since it never involves the database,
> and the "rolled-back writer" rule above holds unchanged (nothing is sent for a rollback). An application that
> appends on a connection it commits itself would need an explicit after-commit call, as the queue's
> `enqueue(Connection, …)` keeps notifying inside the transaction for that reason. P0.10 also found that pgjdbc's
> `getNotifications(timeout)` does not return while notifications keep arriving ([phase-0 record, P0.10](../archive/event-store-redesign-phase0.md#p010-wake-up-notification-cost)), so the
> listener rule above needs a test under sustained notification traffic, not only a reconnect test.

This delivers S2's goal — interruptible wake-up, with backstop and latency decoupled — in the new engine.
The new engine does not use the `EventStorePollingOptimizer` SPI; the current engine keeps it unchanged.

---

## 12. A new engine beside the old one

### 12.1 Why a new engine rather than evolving `PostgresqlEventStore`

| | Evolve in place | New engine |
|---|---|---|
| Removing the gap machinery | Entangled. `GapFillsAwaitingAcknowledgement` and `AcknowledgementRegistrations` are shared with `CdcEventStore`, and `EventStreamGapHandler` is a public SPI | Free. The new engine never had it |
| Shared reader and consistency modes | Fights the per-subscription `pollEvents` Flux contract | Native |
| Subscription model | Keeps `EventStoreSubscriptionManager`, its builders, `durable_subscriptions`, `FencedLock` and `SubscriptionErrorPolicy`, whose shapes caused much of today's cost | Designed from the engine's needs: page handlers, consistency modes, progress in the handler's transaction, engine leases, page-based error handling (§12.3) |
| Table layout | Fixed: existing data must stay readable in place | The engine's own tables, free to use the layouts phase 0 measured (§13.3) |
| Side-by-side measurement | Feature flags inside one class | Two engines measured on the same workload, each on its own tables |
| Risk to existing users | Every change ships to everyone | Opt-in; an application moves once, one way, with a copy step (§12.4) |
| Precedent | — | The shard-owned queue: engine, integration, starter |
| Cost | Lower up front | Higher: a new subscription API and its processors, and migration tooling (§12.3, §12.4) |

Recommendation: **a new engine**, built the way the queue was. The current engine is not changed (decided
2026-11-15): fenced resume-point writes (§9) and the single-statement append (§5.4) are built in the new engine only.
Also decided 2026-11-15, in the project lead's words "the new subscription model should not copy the old, it has
many design issues": the new engine has its own subscription API, progress table, error model and leases, and its own
schema; see §18, "Decisions 2026-11-15: a new subscription model".

### 12.2 Module shape

| Module | Depends on | Contents |
|---|---|---|
| engine (`components/postgresql-event-log`) | `foundation` (the schema harness's `EssentialsSchemaContributor`/`SchemaOwnership` and `PostgresqlUtil` live there, as for `components/postgresql-queue-shard-owned`), pgjdbc, slf4j, micrometer (optional) | Append, stream load, tail reader, watermark, consistency modes, catch-up, wake-up, leases, progress table, parked events. Raw JDBC, no JDBI, no Reactor, no Jackson. Payloads are opaque to it, as in the queue |
| integration | engine, foundation, `postgresql-event-store` (for the `EventStore` adapter, §19 item 19) | The new subscription API (§12.3) and the processors built on it, serialisation, interceptors, enrichers, causation, the unit-of-work integration, page-handler invocation, the migration tooling (§12.4). The `EventStore` adapter for appends and stream loads, nothing subscription-side |
| starter | integration | Spring wiring, properties, health, admin API mapping, schema contributors (event tables, progress table, parked events, the opt-in event-type index) |

Naming, decided 2026-11-15: the engine module is `components/postgresql-event-log`, its package
`dk.trustworks.essentials.components.eventlog`, and its tables are prefixed `el_`.

The engine's write API takes a caller's `java.sql.Connection`, as `ShardOwnedQueue.enqueue(Connection, …)`
does, and commits nothing. The integration module takes the connection from the caller's unit of work, so
appends join the caller's transaction exactly as now. Delivery inside that commit (today's in-transaction
subscribers) stays available; its API follows from the `EventStore` adapter decided for appends (§19 item 19). The same applies to
the resume-point update of §8: the engine issues it on the connection of the handler's unit of work.

All of the engine's DDL (its event tables, the progress table, wherever parked events are stored, and the
opt-in `(event_type, global_order)` index) goes through an `EssentialsSchemaContributor` and the caller's
`SchemaOwnership`, as the root `CLAUDE.md` requires.

### 12.3 The new subscription API, and what is left of an adapter

Decided 2026-11-15: the new engine gets its own subscription API, designed from what the engine does. There is
**no adapter implementing `EventStoreSubscriptionManager`**, and the existing subscription builders,
`EventProcessor`, `ViewEventProcessor` and the `@MessageHandler` dispatch do not shape it. No Reactor: no `Flux`,
no `pollEvents`/`unboundedPollForEvents`, no `SubscriberAcknowledgement`.

What the API has to express, each from a section of this plan:

- A subscription: aggregate type, subscription id, optional tenant and event-type filter (§10.1), consistency mode
  (`FRESH` or `CONTIGUOUS`, §7), progress model (in the handler's transaction or periodic, §8.1), exclusive or not
  (§9), and where it starts.
- A **page handler** that receives a page of events and, for progress in the handler's transaction, the unit of
  work the page commits in, so it can write the page set-based (§8.3, P0.13). A per-event handler is a convenience on
  top of it.
- Error settings: retries, backoff, how a failed page shrinks, when an event is parked (§8.3).
- Processors for the two common cases, a view projection (output in the same database, `CONTIGUOUS` by default) and
  an event processor (side effects outside the database, `FRESH` by default), built on that API (§7.2).
- An admin surface: subscriptions with their mode, position, lag, lease owner and parked events; reset; retry or
  discard a parked event. Mapped through the admin API's three places, as every admin operation is (root
  `CLAUDE.md`).

Its exact types are phase-8 design work and are not fixed here.

What is left of an adapter (decided 2026-11-15, §19 item 19): the aggregate repositories, the Kotlin decider
support and other code that appends and loads streams through `EventStore`/`ConfigurableEventStore` get an
`EventStore` adapter onto the engine's append and stream load, and run unchanged. The adapter covers appends,
`fetchStream`, `loadEvent(s)`, `loadEventsByGlobalOrder`, `findHighest/LowestGlobalEventOrderPersisted`, causation
lookup, interceptors, projectors and the local event bus. It implements nothing subscription-side: its polling and
subscription methods (`pollEvents`/`unboundedPollForEvents` among them) throw, and `SubscriberAcknowledgement`,
`forgetGapMiddlesAwaitedInMemory` and the `EventStreamGapHandler` wiring have no counterpart.

CDC: the new engine does not implement it. If §17 shows the shared reader and Tier 1 wake-up meet CDC's latency and
load numbers, CDC remains with the old engine for users who need it for audit trails or replica offload. Otherwise
CDC support is a later phase.

### 12.4 Moving an application to the new engine

*Decided 2026-11-15:* the new engine has its own tables and an application moves to it **once, one way**, with a
copy and import step. There is no running on the current engine's tables, no switching back and forth, and no
mixing old-engine and new-engine nodes during a rolling deploy. The new engine's tables are therefore free to use the
layouts phase 0 measured (§13.3, [phase-0 record, P0.7](../archive/event-store-redesign-phase0.md#p07-table-design-options):
for example a stream head table, no `UNIQUE(event_id)`). This replaces the earlier plan to run on the existing
tables and `durable_subscriptions` rows, with its open-gap hand-over from `transient_subscriber_gaps`, shared lock
names, readers falling back to the old gap handling, and switching back.

The move, in outline:

1. **Create the new engine's schema** through its schema contributors (§12.2).
2. **Copy the events**, per aggregate type, preserving `global_order`, `aggregate_id`, `event_order`, `event_id`,
   causation, correlation, event type and revision, timestamp, tenant and the payload and metadata bytes. The copy
   does not re-serialise payloads: the wire format is frozen (root `CLAUDE.md`). After the copy, each table's
   identity sequence is set above the highest copied `global_order`.
3. **Import resume points** (optional): each subscription's resume point from `durable_subscriptions` becomes its
   row in the new progress table. A subscriber may have open transient gaps below its old resume point whose fills
   committed but were not yet delivered; the import must start such a subscriber at its lowest open gap, or it skips
   those events. Without the import, subscriptions start where the new API says they start.
4. **Cut over**: stop every old-engine node, copy what was appended since the bulk copy, start the application on
   the new engine. No old-engine appender may run during the final copy, or a late commit below the copied maximum
   is missed.

What must be verified before the first append on the new engine: per aggregate type, the row count, the highest
`global_order` and a checksum over `(global_order, aggregate_id, event_order, event_id)` and the payload bytes equal
the source; each identity sequence is above the copied maximum; each imported resume point is at or below the old
resume point and at or below the subscriber's lowest open gap. The copy leaves the old tables untouched, so until
the first append on the new engine, aborting the move is a configuration change; after it, there is no way back
short of a restore. How fast the copy runs, whether it can run online with only the final delta under a stop, and
the tooling around it are open (§19).

---

## 13. Lower levels: statements, JDBC and tables

### 13.1 What is and is not known

- **Not measured:** JDBI's per-statement overhead compared with raw pgjdbc, in this codebase or the
  queue's. The queue's engine uses raw JDBC, but its documents attribute its gains to removing statements
  and writes (the claim write, the anti-join, the lock contention), not to the library.
- **Measured (P0.0, P0.4):** today's `PreparedBatch` append is one round trip however many events it holds,
  plus one for the last-event-order lookup when no expected order is given; the single-statement append
  is the same one round trip, saves the lookup, and executes one statement instead of N (see §21).
- **Measured on the queue:** `bytea` instead of `jsonb` saved about 3% WAL for 200-byte payloads, and
  the saving is a fixed ~1.2–1.35 KB per message, so its share falls as payloads grow (history §9.1,
  measurements §3.4.3). "Real but small; it is not where the win lives."
- **Measured by Marten:** auto-prepared statements gave no significant gain (430→365 µs, within noise);
  skipping the version read on cached aggregates saved about 1.4% and was removed. Both are reminders that
  the large wins are in statement and write counts.

So the expected order of gains is: fewer statements and no writes on the read path (§4, §6) ≫ fewer
holes and fewer round trips on the write path (§5) ≈ fewer transactions per delivered event (§8) >
table layout (§13.3) > library overhead. Phase 0 must confirm this before any layout change is chosen.

### 13.2 Why the engine should use raw pgjdbc anyway

Not for speed, until that is measured, but for capabilities JDBI hides or makes awkward, and for a
dependency footprint matching the queue's (`foundation` plus pgjdbc):

| Capability | Use |
|---|---|
| `PGConnection.getNotifications(int timeoutMillis)` | A blocking listener (§11). `MultiTableChangeListener` calls `getNotifications()` on a timer today |
| Arrays bound as one parameter (`= ANY(?)`, `unnest(?::bigint[], …)`) | The single-statement append (§5), hole reads and filtered catch-up as single statements with a single plan, whatever the count. Also avoids the 65 535 bind-parameter cap the gap handler hit |
| Several statements in one round trip | pgjdbc sends a batch of protocol messages with a single sync. Used for several aggregates in one unit of work (§5.2) and as the fallback if P0.1 or P0.2 fails |
| `setFetchSize` with autocommit off | Streaming catch-up reads (§10.1) |
| Control of `prepareThreshold` and statement reuse | One stable SQL text per statement shape, so server-side prepared plans are reused. Measure, as Marten did, before relying on it |
| `CopyManager` | The one-way copy of events into the new engine's tables (§12.4) |

> **P0.6 (2026-11-11):** measured, see §21 and [phase-0 record, P0.6](../archive/event-store-redesign-phase0.md#p06-jdbi-against-raw-pgjdbc). JDBI costs a few µs of
> client CPU per statement and nothing on the server, so the case for raw pgjdbc above remains one of capabilities, not
> speed. One finding bears on the statement shapes: JDBI reads one row ahead, so `setFetchSize(1)` on a single-row read
> inside a transaction costs a second round trip (about 40 µs here). That argues for setting a fetch size only on the
> streaming reads of §10.1; not yet decided.

Statement shapes for the engine:

- **Append:** §5.1, one statement per aggregate, batched per unit of work.
- **Tail read:** a single autocommit statement — `SELECT … WHERE global_order > ? ORDER BY global_order
  LIMIT ?` — with no `BEGIN`/`COMMIT` around it and no gap reconciliation after it.
- **Tail read with probe:** §4.10, only while a candidate is outstanding. In autocommit (or right after
  `pg_stat_clear_snapshot()` if it must run inside a transaction), per P0.2; the final shape must return the
  running set even when the tail read returns no rows.
- **Running writers alone:** `SELECT backend_xid FROM pg_stat_activity WHERE backend_xid IS NOT NULL`,
  for re-probing without a read (`CONTIGUOUS` probe interval) and as the P0.2 fallback.
- **Hole read on retirement:** `WHERE global_order = ANY(?)` for few holes, or a range read for wide ones.
- **Filtered catch-up:** §10.1.
- **Resume points:** §8.2, fenced; batched for the periodic model, one per page in the handler's transaction.
- **Leases:** §9's claim, and one renewal statement per pass for every lease a JVM holds.

Phase 0 counts the current engine's statements per poll and per delivered event with
`pg_stat_statements`, so these shapes are compared against real numbers rather than against a reading of
the code.

### 13.3 Table design options

Each is justified by its own measurement, and none is needed for §4–§12. Since 2026-11-15 the new engine
has its own tables and applications move to it with a copy (§12.4), so these are candidates for the engine's
table layout, not opt-ins limited to tables created later. Decided 2026-11-15 (§19 item 23): the engine adopts
P0.7's full combination, a stream head table (§5.3), no `UNIQUE(event_id)` with a plain index on `event_id`, `uuid`
ids, event types interned as `smallint`, and `json` payload and metadata. The table below keeps the options as first
weighed; the note after it has what phase 0 measured.

| Option | Expected effect | Cost, risk |
|---|---|---|
| **`(event_type, global_order)` index** (opt-in) | Index scans for filtered catch-up (§10.1) | One more btree per insert. Per aggregate type |
| **UUIDv7 event ids** (time-ordered) instead of random UUIDs | The `UNIQUE(event_id)` index becomes append-mostly instead of random inserts: fewer page splits, smaller index, less WAL. Probably the largest per-insert saving among these | Event ids become roughly time-ordered, which leaks timing. Existing tables keep random ids. `EventId` generation must stay a framework detail |
| **Make `UNIQUE(event_id)` optional** | One btree less per insert | `findEvent(EventId)` needs it. Opt-out only for users who never look up by id |
| **Payload as `json` or `bytea` instead of `jsonb`** | No binary conversion on write or read. `bytea`/`json` keep bytes exactly, whereas `jsonb` normalises — the CDC parity gotcha exists because of that | Users query `jsonb` payloads in SQL, and the admin UI reads them. The queue measured only ~3% WAL. Keep `jsonb` as the default. The `eventJsonColumnType` setting already exists |
| **Intern `event_type`** into a small-int registry, as the queue interns queue names | Narrower rows and indexes | Rows stop being readable without a join. Low value unless type names are long and rows small |
| **Column order by alignment** (fixed-width first, variable-width last) | A few bytes per row of padding saved | Only for new tables. Trivial |
| **Insert-driven autovacuum tuning** (`autovacuum_vacuum_insert_scale_factor` per table) | Keeps the visibility map current on append-only tables, so the head probes (`max(event_order)` in §5.1, `max`/`min(global_order)`) stay index-only | A table-level setting. Document rather than force |
| **Partitioning by `is_archived`** (Marten §8) or by `global_order` range | Smaller hot indexes; archiving | The unique constraints cannot be enforced across partitions unless they include the partition key, which breaks their meaning. Only as a later archival feature, if at all |
| **Remove the gap tables** | Already follows from §4 | The new engine has none; the old engine's stay with it |

*Note 2026-11-11:* measured in [phase-0 record, P0.7](../archive/event-store-redesign-phase0.md#p07-table-design-options) on small tables with
small payloads. Two findings differ from the expectations above: dropping `UNIQUE(event_id)` was the largest
per-insert saving measured (−26% execution and −110 to −130 B WAL per event at 100 events, about +5% contended
throughput), while UUIDv7 against random UUIDs showed a difference only at 100 events (−5% latency, ~8 B WAL per
event), because index locality only pays once the index outgrows memory, which these tables did not; typing the id
`uuid` instead of `text` saved ~70 B per row. Interning `event_type` together with merging the metadata columns cut
~110 B per row and ~90 B WAL per event with the ~100-character type names Essentials stores, more than the "low
value" expected. For the payload row, `json`/`bytea` reads of 100 small events were ~35% faster than `jsonb`; the
size-dependent costs are in `perf/serialization-profiling:docs/plans/payload-serialization-measurements.md (not yet
on main)`.

---

## 14. The command side (separate track)

Loading an aggregate for a command is outside the subscription engine, but it is on every command's
latency path and Marten shows two cheap wins. Today `StatefulAggregateRepository` loads the snapshot
(`AggregateSnapshotRepository.loadSnapshot`) and then calls `eventStore.fetchStream` for the events after
it: two round trips, each its own statement.

Decided 2026-11-15: these items are built against the new engine's stream load (§12) only. The existing
aggregate repositories and `PostgresqlEventStore` are not changed. Repositories reach the new engine's stream load
through the `EventStore` adapter for append and stream load (§19 item 19, decided 2026-11-15).

| Item | What | Notes |
|---|---|---|
| C1 — one round trip | Snapshot and tail events in one pgjdbc batch | New engine only (decided 2026-11-15). When the snapshot is written asynchronously (`AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`), wrap the batch in `REPEATABLE READ READ ONLY` so snapshot and tail come from one MVCC snapshot. Correctness does not depend on it today, because the tail is read after the snapshot's event order, but it removes a class of "snapshot newer than the events I read" surprises |
| C2 — node-local aggregate cache | An optional LRU per aggregate type, entries taken out atomically on load and written back only after a successful commit | A cache hit reads only events after the cached event order. The optimistic-concurrency check (§5.1) is never skipped. Opt-in, because a mutated cached aggregate after a failed command must never be written back |
| C3 — reuse the command's aggregate for the inline snapshot | When a snapshot is written in the same unit of work, use the aggregate instance the command already holds | Saves a reload |

These have their own measurement (command latency in the trading demo) and their own phases (§18).

---

## 15. PostgreSQL features, used and rejected

| Feature | Verdict | Why |
|---|---|---|
| `pg_stat_activity.backend_xid` (sets) | **Use** | The watermark's source of running writers. Probe it at start-up |
| `pg_current_xact_id()` | **Use** | Forces an xid before the identity value (§5.1). Also the probe's tool |
| `pg_snapshot_xmin` | Use as a metric only | Safe, but cluster-wide and not specific to the candidate. Good for showing "oldest writer holding things up" |
| `pg_snapshot_xip` / `pg_snapshot_xmax` | **Reject** for deciding | Misses writers at or above `xmax` (§4.4) |
| Per-row `xid8` column as cursor | **Reject** | Breaks per-aggregate order (§4.5) |
| `pg_xact_status(xid8)` | Not needed | Disjointness of `backend_xid` sets answers the same question without a per-xid call |
| `INSERT … SELECT FROM unnest(…) WITH ORDINALITY` | **Use** | Set-based append (§5.1) |
| Materialised CTEs for evaluation order | **Use, if P0.1 proves it** | xid and version check before the identity value |
| `pg_advisory_xact_lock` per aggregate | Opt-in | Serialises writers of a hot aggregate (§5.3) |
| Tombstone rows for failed appends | **Reject** | Not needed under the xid rule (§4.9) |
| `LISTEN`/`NOTIFY`, coalesced and app-issued | **Use** | Tier 1 wake-up (§11) |
| Per-row `NOTIFY` trigger (S1) | **Retire** in the new engine | Serialises on the cluster-wide notify queue under load |
| Logical decoding / CDC | Keep in the old engine only | Its reason to exist — latency and load — should be met by §6 and §11. Measure before deciding |
| Hot-standby reads | Not available | The watermark needs the primary's `pg_stat_activity` (§4.4) |
| `REPEATABLE READ READ ONLY` | **Use** for C1 | One snapshot for snapshot plus tail (§14) |
| `track_commit_timestamp` | **Reject** | Timestamps never order events (root `CLAUDE.md`) |
| A gapless counter row per aggregate type, locked until commit | **Reject** | Makes global order equal commit order, but serialises every writer of a type until its commit, including in-transaction handlers. The queue rejected the same idea on measurement: a producer waited 211 ms behind a 200 ms transaction |
| Advisory locks for exclusive ownership | Reject | A lease row with a fence makes every write assertable, which an advisory lock cannot (§9) |
| Array binding, multi-statement round trips | **Use** | §13.2 |

---

## 16. Invariants, and how they are verified

These must be written down before the code, and each must have a test that has been seen to fail
against a deliberately broken implementation. The queue's defect log shows that tests which inject
nothing pass on broken code (history §9.13).

| # | Invariant | Verification |
|---|---|---|
| I1 | No loss: every committed event reaches every active subscription at least once, in both modes | Concurrent-writer ITs with random commit order and rollbacks. A long-transaction IT that commits after 5 minutes. A sequence-jump IT |
| I2 | Per-aggregate order: within one subscription incarnation, A#n is first delivered before A#n+1 | The §4.5 scenario as an IT against the engine (must pass) and against a per-row-xid cursor (must fail). Run with the server-side `event_order` of §5.1. Also P0.5's F1 shape (proposed revision, §4.2): A#n commits late into a hole, A#n+1 follows, a long writer elsewhere keeps the candidate from retiring; must fail against a reader that delivers late commits only from the hole read, and with a transaction of several events |
| I3 | A resume point never passes an order that could still be delivered | Crash injected between delivery and handling, and between a candidate's retirement and its hole read |
| I4 | xid before value: no writer holds a `global_order` while its xid is null — for §5.1's single-statement append, under every join order, and for the head-table append adopted in §19 item 23, which keeps an explicit `pg_current_xact_id()` step first (decided 2026-11-15) | `BEFORE INSERT` trigger holding the window open, after `ShardOwnedWatermarkXidWindowIT`; repeated with planner settings that force `unnest` outer (P0.1); repeated against the head-table append, and must fail against a variant without the `pg_current_xact_id()` step |
| I5 | Fencing: a stale owner's resume-point write — and, with progress in the handler's transaction, its output — never lands after the new owner's | Pause the old owner (a debugger-style hook), let its lease expire, claim on another node, release, assert the progress row and the projection. Also with the stale write waiting on the new owner's row lock |
| I6 | Reset wins: a reset survives a concurrent save, periodic or in the handler's transaction | A reset racing a periodic save and racing a page commit, against the progress row's reset epoch (S6's scenarios, restated for the new API) |
| I7 | Progress: with no running writers, a committed event is delivered within the backstop interval with every notification lost | Listener killed, `NOTIFY` disabled |
| I8 | Migration: after the copy and import (§12.4), the new engine's tables hold every source event with the same `global_order`, `event_order`, `event_id` and payload bytes, and every imported subscription delivers each event at or above its old resume point, including events that filled one of its open transient gaps before the cut-over | Copy a source with holes, permanent gaps, a sequence jump and a subscriber with an open transient gap whose fill committed before the cut-over; compare per aggregate type and assert delivery. Must fail against an import that copies the old resume point unchanged |
| I9 | A slow subscription never delays another | One handler blocked; the others' latency is unchanged |
| I10 | A lost optimistic-concurrency check consumes no `global_order` | Two writers with a stale expected order; assert no hole and the right exception and message |
| I11 | Combined probe: the running-writer set is read after the statement's snapshot | Writer held between taking its value and committing; the combined statement must never retire a candidate it belongs to (P0.2). Must fail against a version that reads `X` in an earlier statement |
| I12 | `CONTIGUOUS` never delivers above the watermark, and with progress in the handler's transaction, a crash at any point leaves projection state equal to applying each event exactly once | Crash injection at every step of a page; compare the projection with a reference fold |
| I13 | Filtered catch-up never skips a matching event that commits late | A long writer holds a matching event below the cursor while a filtered subscription catches up past it. Also a reader restarted while a writer holds a value below the head (P0.5 F4), and a detach after overflow (F5) |
| I14 | Page failure: a failing event in a page is isolated by shrinking the page, retried and then parked with its error, and no other event of the page is lost or applied twice | Throw on one event in a page, once (succeeds on retry) and always (parked); compare the projection with a reference fold that skips the parked event |
| I15 | Catch-up reads never exceed the per-database cap | 50 subscriptions behind at start-up; count concurrent catch-up statements |
| I16 | Leases: an owner that cannot renew stops delivering before its lease can expire on the server, and a renewal pass never extends a lease whose fence has changed | Block the owner's connection and assert it stops before `lease_until`; renew many leases in one pass after one was taken over and assert only the others are extended |

The watermark, the shared reader and the two modes together form a small state machine — reader,
candidates, watermark, attached and detached subscriptions, held-back buffers, resume point — with writers
committing in arbitrary order. It is worth modelling (TLA+, or a deterministic simulation with
property-based tests) before implementing it. The queue's ordered lane needed three attempts to test
ordering across a topology change honestly (shard-owned §2.7). This design has the same kind of state.

---

## 17. Measurement plan

Rules from the queue work, which apply in full (the project's measurement discipline):

- A baseline of the current engine comes first, on the same machine, before any new code exists.
- Both trees are run from git worktrees outside `/workspace`, alternating rounds, with
  `-Dfailsafe.forkCount=1` and nothing else running.
- Benchmarks wire the engines the way the Spring starters do, since that is what users run.
- Each shape gets a fresh table.
- Every gate is decided before the run, and a result inside the noise counts as no gain.
- In the trading demo, compare runs with equal burst counts only; p99 is noisy.

| Shape | Metric | Why |
|---|---|---|
| Idle: 1, 10 and 50 subscriptions × 1, 2 and 4 JVMs, no writes | Statements/s (`pg_stat_statements`) | The resource goal. Expected: flat in subscriptions on the new engine, linear on the old |
| Steady: 1, 10 and 100 events/s, 10 subscriptions, both modes | p50/p99 commit-to-handler latency, statements per delivered event, WAL per event | The freshness and performance goals, against polling, S1 and CDC; `CONTIGUOUS` versus `FRESH` |
| Burst: 100 000 events, one subscription behind | Catch-up time, memory | Catch-up path and streaming reads |
| Rare-type catch-up: a subscription consuming 1% of event types, 1 000 000 events behind | Catch-up time and rows read, with and without the `(event_type, global_order)` index | §10.1 |
| Restart storm: 50 subscriptions behind at start-up, appenders running | Append latency during catch-up, peak connections | §10.2 |
| Concurrent writers: 8 writers, random rollbacks | I1, I2 hold; time from commit to delivery of late commits; holes created | Correctness under the case gaps exist for |
| Contended aggregates: 8 writers on 10 aggregates | Holes created per 1 000 appends, old append versus §5.1, with and without the advisory lock | §5.1, §5.3 |
| Long writer: a 5-minute writing transaction elsewhere | Delivery latency of other events (`FRESH`: expected unchanged; `CONTIGUOUS`: expected to stall), candidate age metric, replay window after a crash | §4.6, §7 |
| Append: 1, 10 and 100 events per call; 1 and 5 aggregates per unit of work | Latency, round trips, WAL; JDBI `PreparedBatch` versus §5.1 | P0.4 |
| Projection throughput: a view projection writing one row per event | Events/s and transactions per event; periodic save versus page per transaction | §8.3 |
| JDBI versus raw pgjdbc, same statement | CPU per statement, latency | Settles §13.1 instead of assuming it |
| Command latency: load-modify-append with and without snapshots | p50/p99, round trips | C1, C2 |
| Trading demo, phase 6 load driver | Orders/s, stage latencies | The realistic gate. The baseline is being recorded on this branch now |

---

## 18. Phases

Each phase ends with its gate met, or a recorded decision to stop. Phase 0 is investigation only: no
engine code is written until its spikes have answered, and each spike's finding is recorded in this
document (§21) before phase 2 starts.

### Phase 0 — investigation

| Spike | Question | How | Gate | If it fails |
|---|---|---|---|---|
| P0.0 | What does the current engine cost? | Baseline of §17's shapes on the current engine; statements per poll and per delivered event from `pg_stat_statements` | Numbers recorded | — |
| P0.1 | Does the single-statement append (§5.1) take its xid before the identity value, under every join order? | Throwaway IT in the xid-window pattern, with planner settings forcing each join order and both protocols (simple, extended/prepared) | The IT holds for every plan, and fails against a variant without the `xid` CTE | Keep the set-based insert, send `SELECT pg_current_xact_id()` ahead of it in the same round trip (§13.2). Costs a statement, not correctness |
| P0.2 | Does the combined probe (§4.10) read running writers after its snapshot? | Throwaway IT holding a writer between value and commit; simple and extended protocol; with and without a server-side prepared plan | The probe never retires a candidate the held writer belongs to, and the IT fails against a two-statement version with the reads reversed | Two statements, `S` then `X`, as originally planned. Costs a round trip while holes are outstanding |
| P0.3 | Is `backend_xid` fully visible on the managed services users run? | Run the queue's `verifyWatermarkPrerequisites` probe as an ordinary application role on RDS PostgreSQL, Aurora PostgreSQL, Azure Database for PostgreSQL Flexible Server and Cloud SQL | Passes on each, or the failing services are named and accepted as unsupported | Decided 2026-11-15, not a gate: the engine refuses to start where the probe fails and that database keeps the current engine; no degraded mode. Runs when access exists, to list supported services |
| P0.4 | Is the single-statement append faster than today's `PreparedBatch`? | §17's append and contended-aggregates shapes, both statements on the current tables | Lower latency or fewer round trips at 1, 10 and 100 events, and fewer holes under contention, outside the noise | Keep the batch shape in the engine (with the xid statement first); drop §5.4 |
| P0.5 | Does the state machine hold? | Model of reader, candidates, watermark, both modes, attach/detach and resume point (TLA+ or deterministic simulation) | Model checks I1–I3, I12 and I13 | Fix the design before code |
| P0.6 | Is JDBI's overhead material? | Same statement through JDBI and raw pgjdbc | Measured | Informs only §13.2; the engine uses raw pgjdbc for its capabilities either way |
| P0.10 | What does the tier-1 wake-up (§11) cost the append path? | Engine append at 1, 8 and 20 writers and 8 writers on 10 aggregates, with no notify, `pg_notify` inside the transaction, after commit coalesced per JVM, and after commit per writer; one listener counting notifications and timing them; `pg_stat_activity` and `pg_locks` sampled; `fsync=off` and `fsync=on` | Measured | Informs §11 tier 1 only |
| P0.11 | What does re-reading the outstanding holes (P0.5 F1) cost the tail statement, and does the planner keep it on index scans? | 2 M-row tables (current layout, §13.3 combined option), 0-1 000 holes near or 100 000 below the cursor, a sequence jump, 0/1/100 new rows; `OR = ANY`, `OR BETWEEN`, ranges via `unnest`, `UNION ALL`, two statements; `EXPLAIN` under custom, generic and `auto` plans; pgjdbc unnamed, named-auto and named-generic | Measured | Informs §4.2/§4.10's statement shape (§19 risk 13) |
| P0.12 | What does the combined probe's `pg_stat_activity` read (§4.10) cost as connections grow, and what does probing at a high rate do to appends? | 10, 100 and 400 open connections (`max_connections` 50, 500, 2 000), idle and with 20 % appending; the sub-select alone, the combined probe with 0 and 10 holes, `pg_stat_get_activity(NULL)`, `pg_stat_get_backend_idset()`, `pg_snapshot_xip`, `pg_locks`, `stats_fetch_consistency`, `pg_stat_clear_snapshot()` in a transaction; appends with no prober, one prober every 1 ms, and one per 10 connections every 10 ms, against a tail-only control | Measured | Informs §4.10's form and §7.3's probe interval (§19 risk 16) |
| P0.13 | What does progress in the handler's transaction cost a view projection, per event and per page, against today's periodic save, and what does the per-event fallback cost (§8.1-§8.3)? | A projection upserting one row per event from a 200 000-event table; resume point (§8.2's statement, fenced) per event, per page of 10/100/500 (row by row, JDBC batch, set-based statement), periodic every 1 s, and `PAGE_100` with one event in 1 000 or 100 failing; 1 and 4 projections; events/s, transactions, round trips, WAL and server CPU per event, commit latency; `fsync=off` and `fsync=on` | Measured | Informs §8.3, risk 6 and the phase-4 gate |
| P0.14 | When the engine owns the transaction (a subscription page with its fenced resume point, §8.3; an append the engine commits itself), what do round trips and server CPU drop to if the last statements and the commit go in one flush, and can pgjdbc do it? | Throwaway `OneFlushTransactionSpikeIT`, harness as P0.4/P0.13 (palindrome 20 s windows, fresh table per window, `RoundTripCountingProxy`, cgroup `cpu.stat` through `-Dspike.serverCpuCommand`, `pg_stat_statements` with `track_planning`), external server, `fsync=on`. Shapes: §5.1 with 1 and 10 events, committed by the engine; §5.2 with five aggregates; a page of 100 events as P0.13's set-based view upsert, then §8.2's fenced update, then commit. Arms: **A** pgjdbc, autocommit off, statements then `commit()` (today); **B** pgjdbc, autocommit on, the same statements as one `executeBatch` or one multi-statement `PreparedStatement` (one Sync, so one implicit transaction: the spike first proves this by failing the last statement and finding nothing committed); **C** a minimal wire client in the `spike` package: statements parsed at connect, Bind/Execute per statement and one Sync in one flush; **D** C with explicit `BEGIN` … `COMMIT` in the same flush. In B-D the fenced update is the erroring form above. 1, 8 and 20 writers. Per unit of work: round trips, server CPU (cgroup), recorded execution, p50/p99; units/s | Phase 2 builds engine-owned transactions as one flush if the best arm removes at least one round trip per unit of work, saves at least 35 µs of server CPU per removed round trip (P0.12 put `SELECT 1` at 40-52 µs) and gives at least 10 % more units/s at 8 writers; **and** a stale epoch commits nothing in every arm. If B is within 10 % of C on server CPU and units/s, it stays pgjdbc | Engine-owned transactions stay explicit pgjdbc transactions (statements, then `commit()`); the 274-310 µs per attempt outside recorded execution (P0.9, 2026-11-14) is not round-trip overhead, and P0.17's request-path half is dropped |
| P0.15 | Parked 2026-11-14: group commit for engine-owned writes | — | — | PostgreSQL already shares a WAL flush between concurrent commits, and appends inside the caller's UnitOfWork cannot use it, so the gain is limited to engine-owned writes at low concurrency. Revisit only if P0.14 shows round trips still dominate |
| P0.16 | For the engine's own long-lived connections (tail readers §6, periodic resume-point writer, notifier), what does an owned connection with every statement prepared at connect and fixed session settings save per call over pooled pgjdbc defaults; which `plan_cache_mode` keeps each statement on its index (P0.11's generic-plan trap); and do the tail reads of several woken aggregate types in one flush beat one round trip per type? | Throwaway `OwnedReadConnectionSpikeIT` on P0.11's 2-million-row tables plus 1, 10 and 50 aggregate-type tables, external server. Statements: tail read with 0, 1 and 100 new rows; P0.11's `UNION ALL` combined probe with 0 and 10 holes; §8.2's update. Arms: **A** Hikari, pgjdbc defaults (`prepareThreshold=5`); **B** an owned pgjdbc connection, `prepareThreshold=1`, every statement executed once at connect, `options=-c jit=off -c max_parallel_workers_per_gather=0`, each under `plan_cache_mode` `auto`, `force_custom_plan` and `force_generic_plan`; **C** the minimal wire client: all statements parsed in one round trip at connect, binary parameters and results, the settings in the startup message. Several types: 10 and 50 types woken at once, (i) one reader thread and round trip per type, (ii) one owned connection, every tail read in one flush with one Sync each, through a multi-statement `PreparedStatement` with `getMoreResults` (pgjdbc) and through C. Measured: server CPU per call (cgroup), plan and execution time (`pg_stat_statements`, `track_planning`), `EXPLAIN (GENERIC_PLAN)`, client CPU, p50/p99, round trips per wake | Phase 2's tail reader owns its connection with statements prepared at connect if B saves at least 20 µs of server CPU or 20 % of p50 per tail read over A. Each statement gets the plan mode under which `EXPLAIN` shows index scans in every case, and any statement whose generic plan does not is pinned to `force_custom_plan`. Several-types-in-one-flush is adopted if at 10 woken types it cuts server CPU per wake by at least 30 %. C counts only if it beats B by at least 20 % server CPU per call | Tail readers borrow pooled connections with pgjdbc defaults; plan mode stays at the default except where P0.11 requires otherwise; one round trip per type |
| P0.17 | Does a client that delivers notifications as they arrive, and pipelines many callers' independent statements on one connection, beat pgjdbc enough to justify a second driver, given that it can never join a caller's JDBC UnitOfWork? | Throwaway `AlternativeClientSpikeIT`, external server, the listener on a separate host if one is available. (a) Wake-up: producers as P0.10 (8 and 20 writers through the coalesced after-commit notifier) and the drain IT's 200, 1 000 and 3 000 notifications/s. Listeners: pgjdbc `getNotifications(200)` stock, `PINNED_PEEK`, `QUERY_POLL`; Vert.x `PgSubscriber`; a minimal LISTEN-only reader. Wake latency from the commit's return and from the wire (`NotifyTapSocketFactory`), p50/p99/max; listener client CPU; every notification counted. (b) Request-path pipelining is parked (2026-11-14): it needs a wire client of our own (TLS, auth, cancel, errors), and P0.14 shows most of its gain with pgjdbc. | (a) An alternative listener only if pgjdbc's best (`PINNED_PEEK`, or `QUERY_POLL` if reflection is rejected) misses p99 at or below 2 ms at 3 000 notifications/s and the alternative meets it. (b) Parked. | pgjdbc only. The listener follows `pgjdbc-notification-drain-options.md` §5 (`PINNED_PEEK`, `QUERY_POLL` as fallback); no second driver in phases 2 or 6 |

P0.14-P0.17 (added 2026-11-14) come from the round-trip and protocol techniques of `java-raw-stohirov`; they apply only to connections and transactions the engine owns, never to an append inside the caller's UnitOfWork. Their background, the code they are based on and what they deliberately leave out are in [phase-0 record, P0.14-P0.17 background](../archive/event-store-redesign-phase0.md#background-of-p014-p017). P0.17 (a) ran on 2026-11-15 on the dedicated server and passed the gate as pgjdbc only: `PINNED_PEEK`'s p99 at 3 000 notifications/s is 0.62-0.72 ms against Vert.x's 0.60-0.66 ms, while the stock drain reaches 0.66-1.37 s ([phase-0 record, P0.17 (a)](../archive/event-store-redesign-phase0.md#p017-a-faster-notification-listener)). P0.14 ran on 2026-11-15 on 18.7: one flush passes the gate at 8 writers for ten-event and five-aggregate appends and for the page (+17 to +31 %, 42-137 µs server CPU saved per removed round trip), fails for the single event at 8 writers (−7 %) but gains +49 % at 20; only the explicit `BEGIN … COMMIT` form stays atomic at every size, since pgjdbc's mid-flush sync at about 64 KB splits an autocommit batch. P0.16 ran on 2026-11-15 on 18.7: the tail reads' saving (57-189 µs server CPU per call) comes from `force_generic_plan`, not from owning the connection, since PostgreSQL re-plans these statements under `auto`; reading the woken types in one flush cuts server CPU per wake by 58-66 %; the minimal wire client does not count.

### Phase 0 decisions (2026-11-15)

Taken with the project lead after every spike except P0.3 had run on the dedicated server (summary:
[phase-0 record](../archive/event-store-redesign-phase0.md#summary-and-decisions)). The revisions marked "decided 2026-11-15" above are
part of the design from now on.

| Decision | What it means for phases 1 onward |
|---|---|
| P0.5 F1 with P0.11's form | The tail statement re-reads outstanding holes as a `UNION ALL` branch and publishes them ahead of the new rows. Never the `OR global_order = ANY(:holes)` form: under a generic plan it walks the primary key. |
| P0.5 F2, F4, F5, F6 | The watermark is `min(cursor, lowest unresolved hole − 1)` and moves only after the hole read is published; a restarted reader starts at the head with one initial candidate and an unknown watermark; a detaching subscription resumes at §4.7's formula; a slow subscription detaches and never blocks the reader. |
| P0.5 F3 | A subscription attaches once its catch-up reaches the watermark, then bridge-reads up to the reader's cursor. Phase 2 measures how often a `CONTIGUOUS` subscription attaches, overflows and detaches behind a long writer. |
| P0.10 | Tier-1 wake-up: `pg_notify` after commit, coalesced per JVM, never inside the appending transaction. A notify lost to a crash between commit and notify only delays the wake-up to tier 0's backstop poll (§11). |
| P0.12 | The probe reads `pg_stat_get_activity(NULL)`, and its rate is not a fixed per-JVM interval: it backs off with the probe's measured cost and re-probes only after a wake-up. Tuned in phase 2 against P0.12's 400-connection case. |
| P0.13 | The resume point is saved per page. After a failed page the subscription continues with smaller pages and grows back after pages that commit; the new subscription API's handler receives the page and can write it set-based (first decided as an opt-in beside `@MessageHandler`; restated after the decisions below); the default page size is about 100. |
| P0.16 | The tail reader's read statements run under `force_generic_plan`, but only statements whose generic plan is checked in CI (index-only, no primary-key walk); all woken aggregate types are read in one pgjdbc flush. Phase 2 measures the pooled-connection case. |

Not put to a decision because the spike settled it: P0.14 (engine-owned transactions go out as one explicit
`BEGIN … COMMIT` flush), P0.17 (a) (pgjdbc's `PINNED_PEEK` drain, no second driver), P0.6 (raw pgjdbc). P0.3 is not a gate (decided 2026-11-15, the
same day): access to the managed services will not come soon, so the engine runs the prerequisite probe at start-up
and refuses to start where it fails, and such a database keeps the current engine. No degraded mode is built.

### Decisions 2026-11-15: a new subscription model

Taken with the project lead the same day, after the phase-0 decisions: "the new subscription model should not copy
the old, it has many design issues". The current engine stays unchanged (§5.4, §12.1); these decisions are about
what the new engine offers instead of the current subscription model.

| Decision | Replaces | What it means for phases 1 onward |
|---|---|---|
| A new subscription API, designed from the engine's needs: page handlers, `FRESH`/`CONTIGUOUS`, progress in the handler's transaction, engine-owned leases | An adapter implementing `EventStoreSubscriptionManager`; the subscription builders, `EventProcessor`/`ViewEventProcessor` and `@MessageHandler` as the shape to fit; Reactor (`pollEvents`, `Flux`, `SubscriberAcknowledgement`) | Phase 8 designs the API and the processors on it (§12.3). Appends and stream loads get an `EventStore` adapter, decided separately the same day (§19 item 19); nothing subscription-side goes into it. |
| A progress table owned by the engine: resume point (§4.7), reset epoch, lease and fence columns, whatever an open page needs | Resume points in `durable_subscriptions` | Built from phase 2 (§8.2). Its exact columns are open (§19). A one-time import of old resume points on first start is optional (§12.4). |
| Own tables, and a one-way move with a copy and import step | Running on the current engine's tables and resume points; switching both ways; mixed old and new nodes in a rolling deploy; the open-gap hand-over from `transient_subscriber_gaps` | The engine may use the layouts phase 0 measured (§13.3, P0.7). Phase 9 builds and verifies the migration (§12.4, I8). Risk 9 restated. |
| An error model designed around pages: a failed page shrinks to isolate the failing event, which is retried and then parked for that subscription with its error; later events of its aggregate wait behind it and the other aggregates continue; retry, backoff and shrinking are engine settings | `SubscriptionErrorPolicy`, `FailFast`, redelivery and dead-lettering "exactly as today", per event | §8.3 restated; I14 restated. The parked-event store and the per-aggregate order behind a parked event are open (§19). |
| Engine leases: lease and fence columns on the progress row, renewed with `now()`, many per JVM in one pass; every progress write asserts the fence in the same statement on the same row | `FencedLock`/`FencedLockManager`, and the `owner_fence` column on `durable_subscriptions` | Phase 7 (§9). I5 restated, I16 added. Lease timings and fence uniqueness across a deleted row are open (§19). |

### Decisions 2026-11-15: the open items of the new model

Taken with the project lead, one at a time, the same day; each settles a §19 item, where the details are.

| Item | Decision |
|---|---|
| §19 item 18, parked events | Later events of the parked event's aggregate wait behind it for that subscription; every other aggregate continues |
| §19 item 19, appends | An `EventStore` adapter for append and stream load only; aggregates and deciders run unchanged |
| §19 item 23, table layout | P0.7's full combination: head table, no `UNIQUE(event_id)`, `uuid` ids, interned event types, `json` payload |
| §19 item 17, progress table | Fences from one sequence; mode and progress model stored and checked; no open-page state; non-exclusive subscriptions get a row per instance |
| §19 item 21, errors | 3 retries with doubling backoff, step-down shrinking, no built-in permanent-error list, an explicit exception to park at once |
| §19 item 22, leases | 15 s lease, 4 s renewal, local stop after 7 s without a successful renewal; safety rests on the fence |
| §19 item 20, migration | Online bulk copy, then the final delta under a short stop for appends; phase 9 starts by measuring it |

### Phases 1 onward

| Phase | Content | Gate |
|---|---|---|
| 1 | Dropped 2026-11-15: the current engine is not changed. The fence (§9) is built in phase 7 | — |
| 2 | Engine: own schema (§12.4), append (§5, shaped by P0.1/P0.4), stream load, tail reader with watermark and the combined probe (shaped by P0.2), progress table (§8.2), one `FRESH` subscription, backstop wake-up only. Design: [phase-2 design](event-store-engine-phase2.md) | I1–I4, I10, I11 pass. Statements per event at or below the P0.0 baseline, measured with one subscription per aggregate type against today's engine with one subscription (decided 2026-11-15); the 10-subscription comparison is phase 3's gate |
| 3 | Shared reader, attach/detach, catch-up. Design: [phase-3 design](event-store-engine-phase3.md) | I9 passes. Idle statements flat in the number of subscriptions. Statements per event at or below the P0.0 baseline with 10 subscriptions (decided 2026-11-15) |
| 4 | `CONTIGUOUS` mode; progress in the handler's transaction, page per transaction; a failed page shrinks and the failing event is parked (§7, §8). Design: [phase-4 design](event-store-engine-phase4.md) | I12, I14 pass. Projection throughput above the periodic-save baseline; `CONTIGUOUS` latency within one probe interval of `FRESH` with no long writer |
| 5 | Filtered catch-up, adaptive fallback, per-database read cap, opt-in event-type index (§10) | I13, I15 pass. Rare-type catch-up and restart-storm shapes beat the baseline |
| 6 | Wake-up tiers 1 and 2 (§11) | I7 passes. Latency compared with S1 and CDC |
| 7 | Engine leases on the progress row, batched renewal, exclusive subscriptions fenced (§9); no `FencedLockManager` | I5, I6 and I16 pass. Multi-JVM takeover ITs |
| 8 | The new subscription API, the view-projection and event-processor processors built on it, parked events and the admin API (§12.3); the `EventStore` adapter for appends and stream loads (§19 item 19) | New ITs for the API and processors pass, I14 among them; the existing repository ITs pass against the adapter |
| 9 | One-way migration: copy of events per aggregate type, optional import of resume points, cut-over, and its verification (§12.4) | I8 passes. A copy of the trading demo's data verifies equal and the demo resumes on the new engine without loss |
| 10 | Starter, documentation, LLM docs, admin UI, release notes | Trading demo runs on the new engine at or above baseline |

### Command-side track (independent of the phases above)

New engine only (decided 2026-11-15): built against the new engine's stream load, from phase 2 on; the existing
aggregate repositories are not changed.

| Phase | Content | Gate |
|---|---|---|
| C1 | Snapshot plus tail in one round trip, `REPEATABLE READ READ ONLY` for asynchronous snapshots (§14) | Command latency below baseline; aggregate ITs pass against the new engine's stream load |
| C2 | Opt-in node-local aggregate cache | Concurrency ITs show no stale write-back; command latency gain outside the noise |
| C3 | Reuse the command's aggregate for the inline snapshot | Measured saving |

### Later

EF-2 units on the shared reader, direct local hand-off, CDC on the new
engine, exactly-once database state in `FRESH` mode (§8.4), one lease shared by several subscriptions (EF-2
units) and blue/green projection rebuilds — each with its own measurement.

---

## 19. Risks and open questions

1. **`backend_xid` on managed PostgreSQL.** If a provider restricts `pg_stat_activity`, the engine
   cannot run there. The probe turns that into a start-up failure, but it is a real adoption limit.
   Accepted (2026-11-15): where the probe fails, the application uses the current engine; P0.3 runs when access to
   the services exists and only adds a list of supported services.
2. **Evaluation order inside one statement.** §5.1 and §4.10 both rely on PostgreSQL evaluating parts of a
   statement in an order the SQL standard does not promise. Both have a fallback that costs one statement,
   and both are spikes (P0.1, P0.2). A future PostgreSQL version could change the behaviour, so the
   corresponding ITs must run against every supported PostgreSQL major in CI.
3. **Long writers in shared databases.** In `FRESH`, latency is unaffected but the replay window after a
   crash grows with the longest writer. In `CONTIGUOUS`, delivery stalls behind it. Both need the
   candidate-age metric and an operator-facing explanation, not a cap.
4. **`CONTIGUOUS` as the view default.** It stalls view projections behind any long writer in the
   database. If the trading demo or users show that is common, the default can be revisited (D1); the
   metric of §4.6 makes it visible.
5. **The new subscription API and the cost of moving to it** (restated 2026-11-15). There is no
   `EventStoreSubscriptionManager` adapter, so users of today's `EventProcessor`, `ViewEventProcessor` and
   subscription builders port their handlers when their application moves, and the existing processor ITs do
   not carry over as a parity check: the API needs its own tests, and the invariants of §16 are the reference.
   How much porting the processors on the new API can absorb is phase-8 design work.
6. **Page per transaction and handler expectations.** Some existing view handlers may assume one event per
   transaction (for example, reading their own writes through a different connection). The per-event
   fallback covers failures, not that assumption. Page mode may need to be opt-in for existing
   `ViewEventProcessor`s and default only for new ones. *Note 2026-11-15:* handlers move to the new API (§12.3)
   rather than running unchanged, so the assumption has to be dealt with when a handler is ported, and page mode
   can be the default. Measured in P0.13 (2026-11-12, [phase-0 record](../archive/event-store-redesign-phase0.md#p013-projection-progress-per-event-per-page-and-periodic)): page mode
   gave an unchanged row-by-row handler about 2x the periodic save on the VM, so keeping it opt-in for existing views
   forgoes about that much; the 10-13x needs a handler that writes the page at once, which is new API, not a setting.
   The fallback as written gets slower than one transaction per event when failures are frequent (proposed revision
   in §8.3).
7. **External writers.** Any process that inserts into an event table without the xid-first rule breaks
   I4 silently. That needs documentation, and possibly a start-up warning if rows appear that the engine
   did not write. Detecting that reliably is an open question.
8. **CDC's future.** If the new engine meets CDC's numbers, CDC becomes a niche feature with a heavy
   operational cost. Whether to keep developing it is a product decision for after phase 6.
9. **The one-way move** (restated 2026-11-15; first written as "rolling deploy with old-engine appenders",
   which no longer arises because old and new nodes never share tables). The cut-over stops every old-engine
   node, so an application has a window without appends, as long as the final delta copy takes. After the first
   append on the new engine there is no way back short of a restore. An imported resume point that ignores the
   subscriber's open transient gaps skips events (§12.4, I8). Old-engine appends do not take the xid first, so the
   copy must not run its final pass while any old-engine writer is running.
10. **Naming** (decided 2026-11-15). The engine module is `components/postgresql-event-log`, its package
    `dk.trustworks.essentials.components.eventlog`, and its tables are prefixed `el_` (§12.2).
11. **Release line.** The engine, its subscription API and its starter are new modules, so they can ship in a
    minor as an opt-in, as the queue did; nothing in the current engine or its interfaces changes. The
    `EventStore` adapter for appends and stream loads (item 19) implements the current interface without changing
    it. Removing the gap SPI from the old engine is a separate decision for the next
    major.
12. **Interaction with the shard-owned queue's own pending fixes.** The watermark code should be shared
    rather than copied, if the queue's version is stable by then. Its defects so far have been in exactly
    this kind of code, and more fixes are coming in the next release.
13. **The tail statement while holes are outstanding (P0.5 F1, proposed revision in §4.2, 2026-11-11).** Re-reading
    the outstanding holes in every tail statement keeps `FRESH`'s per-aggregate order, but makes that statement larger
    for as long as holes are outstanding: `OR global_order = ANY(:holes)` (and ranges for a jump) beside the cursor
    predicate. It adds no statement. Measured in P0.11 (2026-11-12, [phase-0 record](../archive/event-store-redesign-phase0.md#p011-re-reading-holes-in-the-tail-statement)): as an `OR` it is index scans
    only as a custom plan; a generic plan walks the whole primary key below the cursor (~300 ms per probe at 2 M rows),
    `plan_cache_mode=auto` picked that plan by itself with 1 000 holes on a 300 000-row table, and a jump range in the
    `OR` makes even the custom plan do it. A `UNION ALL` of tail read and hole read is index scans in every plan mode at
    the same server cost: ~0.11 µs per hole, ~120 µs at 1 000, independent of how far below the cursor the holes are
    (a long writer's old hole costs what a new one costs). Proposed revisions in §4.2 and §4.10. Still open: a range
    branch for jumps that does not trigger the JIT through its row estimate (5-8 ms custom, 80-95 ms generic as
    measured), more than 1 000 holes, other majors, and the dedicated machine.
14. **`CONTIGUOUS` attach and overflow churn behind a long writer (P0.5 F3, proposed revision in §6, 2026-11-11).**
    Attaching at the watermark means a `CONTIGUOUS` subscription behind a long writer bridges everything the reader has
    read above the hole and holds it back; if that exceeds its buffer it detaches, catches up to the watermark, and
    attaches again, one bridge read each time, until the writer ends. No event is lost, but the number of statements
    is unbounded in the writer's duration. Options to weigh: a `CONTIGUOUS` subscription that stays detached while
    the watermark has not moved, a back-off between attach attempts, or a buffer bound for held-back events that is
    separate from the live one. Not measured; the model has no clock.
15. **Tier-1 wake-up: the NOTIFY lock and the listener (P0.10, 2026-11-12).** A notifying commit holds PostgreSQL's
    cluster-wide NOTIFY lock through its WAL flush. Notifying inside the append transaction (§11 as written) cut
    appends by 57-77 % at 8-20 writers with `fsync=on` on the VM, and the cost grows with flush time, so a real disk
    is expected to be worse; the proposed after-commit, coalesced notifier removes it in the measurements. Still open:
    several JVMs each with their own notifier (the lock is taken once per batch per JVM, so it is contended again),
    other notifying traffic in the same cluster (the queue's, or an application's), and the dedicated-machine pass. And
    the listener: pgjdbc's `getNotifications(timeout)` returns nothing until a continuous stream of notifications
    pauses, which delays every wake-up under sustained load; the queue's `ShardWakeupListener` drains the same way.
    A listener that delivers notifications as they arrive is needed, or its absence must be shown not to matter.
16. **The probe's running-set read at scale (P0.12, 2026-11-12).** Reading `pg_stat_activity` costs about 0.55 µs of
    execution per live backend in the cluster (10-17 µs at 10 connections, about 60 at 100, about 240 at 400), 2.5-3
    times that under write load, and it is paid on every probe of every JVM's reader while a hole is outstanding.
    Cluster-wide cost therefore grows with JVMs × connections. On the VM, 40 readers probing every 10 ms against 400
    connections and 80 writers halved appends compared with the same readers without the running set (25-26 against
    49-52 thousand per second; append p99 55-64 against 11 ms). At 100 connections the cost was 3-5 %, at 10 it was
    not measurable. `pg_stat_get_activity(NULL)` instead of the view is cheaper only at low connection counts;
    `stats_fetch_consistency` does not matter; no in-core function returns `backend_xid` more cheaply. Proposed
    revisions in §4.10 and §7.3. Still open: the cause of the collapse (lock contention against commits, or saturation
    of the 8-CPU VM; no wait events were sampled), the dedicated machine, and a probe schedule whose cluster-wide cost
    does not grow with the square of the deployment.
17. **The progress table's columns** (decided 2026-11-15). Besides resume point, reset epoch, lease owner, lease
    expiry and fence (§8.2, §9): fence tokens are drawn from one database sequence, so a token never repeats, even
    after an operator deletes and recreates a row; the consistency mode and progress model are stored on the row and
    checked at start, so a redeploy that changes them is logged rather than resumed silently under other semantics;
    nothing is stored for an open page (in-transaction progress commits with the page, periodic progress saves only
    completed positions). Non-exclusive subscriptions get one row per instance, keyed
    `(subscriber, aggregate type, instance name)` with a stable instance name from configuration, so each JVM resumes
    from its own progress (today all JVMs overwrite one shared row); rows of retired instances are listed in the
    admin API and can be deleted. The exact DDL is drafted in phase 2.
18. **The parked-event store** (decided 2026-11-15). Later events of the parked event's aggregate are held behind it,
    in order, for that subscription, until an operator retries or discards the head; every other aggregate continues.
    That is what `EventProcessor`/`ViewEventProcessor` do today behind a dead letter, without a durable queue in between;
    today's whole-subscription stop and silent skip both go. A parked row references the event by aggregate type and
    `global_order`, without a copy. Admin operations: list, retry, discard (§12.3).
19. **Appends and stream loads** (decided 2026-11-15). An `EventStore` adapter implementing only the append and
    stream-load methods on the engine, so aggregate repositories, the Kotlin decider support, interceptors, enrichers
    and `OptimisticAppendToStreamException` work unchanged; every polling and subscription method of `EventStore`
    throws. An application that moves changes its subscriptions, not its command side.
20. **The migration copy** (decided 2026-11-15). Online: a bulk copy while the application runs (`COPY` through
    pgjdbc's `CopyManager`) into the new layout (§13.3, item 23), keeping every event's `GlobalEventOrder` and its
    payload bytes, then a short stop for appends while the final delta is copied, verified (event counts, last event
    order per aggregate, checksums) and cut over, with resume points imported as §12.4 describes. Its throughput is not
    measured: phase 9 starts with a spike measuring copy rate and the length of the stop on a large table.
21. **Error settings and their defaults** (decided 2026-11-15). Every failure that is not an I/O failure is retried a
    configurable number of times (default 3, backoff doubling from 100 ms, capped); I/O failures are retried
    indefinitely, as today. A failing page shrinks by stepping down as P0.13 measured (halving until one event, growing
    back after pages that commit). There is no built-in list of permanent errors (the queue consumer's list, which
    dead-letters `IllegalArgumentException` from `FailFast` on first delivery, is not inherited); a handler parks an
    event at once only by throwing a dedicated exception. The values are engine settings, tuned in phase 4.
22. **Lease timings** (decided 2026-11-15). Lease 15 s and renewal every 4 s, today's `FencedLock` defaults, so
    failover time does not change; expiry is judged by the database (`now()`). The owner stops delivering on its own
    7 s (lease minus two renewal intervals) after the start of its last successful renewal, measured with the JVM's
    monotonic clock, and renewal statements run with a `statement_timeout` below the renewal interval. All three are
    settings, tuned in phase 7's takeover ITs. The monotonic clock does not drift meaningfully, but it does not count
    while a VM or host is suspended, so the local deadline is best effort: it limits side effects outside the database.
    Safety rests on the fence that every progress write (and, with in-transaction progress, the handler's output)
    asserts on the same row; a former owner that resumes after a suspend commits nothing.
23. **Which phase-0 table layouts the engine adopts** (decided 2026-11-15). The full combination P0.7 measured as
    `COMBO_ALL`: a stream head table with a guarded `UPDATE` (no lost-race holes), no `UNIQUE(event_id)` (a plain
    index serves lookups by event id), `uuid` event, causation and correlation ids, event types interned as
    `smallint`, and `json` payload and metadata with the bytes unchanged. Ad-hoc SQL needs a join or a view for
    event-type names, and the migration copy (item 20) maps ids and types.

---

## 20. Decisions recorded

| # | Decision | Date | Notes |
|---|---|---|---|
| D1 | Delivery consistency defaults: `CONTIGUOUS` for view projections, `FRESH` for event processors and everything else; overridable per subscription | 2026-11-10 | Revisit if risk 4 materialises |
| D2 | The Marten findings are folded into this plan rather than kept as a separate design | 2026-11-10 | `marten-event-store-design.md` stayed as the reference for Marten itself until it was removed on 2026-11-15 (git history keeps it) |
| D3 | The new engine has its own subscription API; no adapter implements `EventStoreSubscriptionManager`, and nothing Reactor-based is in it | 2026-11-15 | §12.3. An `EventStore` adapter for appends and stream loads only was decided separately the same day (§19, item 19) |
| D4 | Subscription progress lives in a progress table the engine owns, not in `durable_subscriptions` | 2026-11-15 | §8.2. Importing old resume points once is optional |
| D5 | The engine has its own tables; an application moves once, one way, with a copy and import step; no shared tables, no switching back, no mixed nodes | 2026-11-15 | §12.4. Replaces the coexistence plan; risk 9 and I8 restated |
| D6 | Errors are handled per page: a failed page shrinks, the failing event is retried and then parked per subscription, the rest continue | 2026-11-15 | §8.3. Replaces `SubscriptionErrorPolicy`/`FailFast`/redelivery semantics |
| D7 | Exclusive subscriptions are owned through lease and fence columns on the progress row, renewed with `now()`, with every progress write asserting the fence on the same row; no `FencedLockManager` | 2026-11-15 | §9 |

---

## 21. Phase 0 findings

To be filled in as each spike completes: date, setup, result, and which branch of the spike's table
(gate met, or fallback taken) the design follows from then on.

| Spike | Date | Result | Consequence for the design |
|---|---|---|---|
| P0.0 | 2026-11-10 | **Numbers recorded**, in [phase-0 record, P0.0](../archive/event-store-redesign-phase0.md#p00-baseline-of-the-current-engine). Setup: throwaway `CurrentEngineBaselineSpikeIT` (`spike` package, runs only with `-Dspike.run=true`), PostgreSQL 17.5 with `pg_stat_statements`, a fresh container per run of each shape, every shape run twice, 8 CPUs / 15.6 GiB Docker, in-tree, `-Dfailsafe.forkCount=1`, Testcontainers' `fsync=off`; engine wired with the Spring starter's defaults (batch 10, 100 ms poll, jittered backoff to 2 s, `PostgresqlEventStreamGapHandler`, resume points every 1 s, notify off), separate DB roles for the write and read paths. Idle: one transaction of `BEGIN` + `MIN(global_order)` probe + transient-gap read per subscription every ~2 s, i.e. 1.5 counted statements/s per subscription, exactly linear (1.5 / 15 / 75 per s for 1 / 10 / 50); no writes; a second instance adds ~0.5 statements/s per exclusive subscription in lock-acquire attempts. Steady, 10 subscriptions: latency p50 ≈ 50 ms, p99 ≈ 107 ms at 10 and 100 ev/s; 68 reader vs 3 writer statements per appended event at 10 ev/s (6.8 per delivery), 1.04 reader statements per delivery at 100 ev/s; ~1.0-1.25 KB WAL per event. Burst 100 000: catch-up 2.3-2.6 s, 0.375 statements and 0.125 transactions per event, +~75 MB heap, no writes. Concurrent writers (8, 10% rollbacks): all committed events delivered once; every hole cost one transient insert, one delete and one permanent insert (~440 B WAL per hole per subscriber); only 1-2 late commits per run, so their latency is not established. Append: the `PreparedBatch` is one round trip for 1, 10 or 100 events (3 round trips per unit of work with the last-order lookup, 2 without; 11 for 5 aggregates; all on new aggregates, where the lookup finds no row. 2026-11-11: on an aggregate that already has events the lookup costs one more round trip, 4 in all, because JDBI reads ahead on the one-row portal. P0.6's `round_trip_reconciliation` ran the proxy and the wire trace together and they agree, so the proxy's counts stand); p50 0.14-0.20 ms for one event, ~1.4 ms for 100; ~950-990 B WAL per event. OCC, 8 writers on 10 aggregates: 466 identity values burned per 1 000 successful appends. `pg_stat_statements` recorded `BEGIN` but almost no `COMMIT`, so utility-inclusive counts are one per transaction low. | §13.1's order is confirmed: read-path statements dominate (an order of magnitude at low rates, linear in subscriptions when idle) and holes are the only read-path writes. On the write path the batch is already one round trip, so §5.1's gain for one aggregate is the removed lookup and the holes, not round trips; for several aggregates it is one round trip instead of two each. P0.4 is judged on that basis. Phase 2/3 gates use these numbers: ≤ 6.8 reader statements per delivery at 10 ev/s, ≤ 1.04 at 100 ev/s, idle flat in subscriptions instead of 1.5/s each. |
| P0.1 | 2026-11-10 | **Holds.** Setup: throwaway `AppendXidBeforeIdentitySpikeIT` (postgresql-event-store, `spike` package, runs only with `-Dspike.run=true`), PostgreSQL 16.15, 17.5 and 18.4, each in four pgjdbc modes: simple protocol (`preferQueryMode=simple`), extended protocol with unnamed statements (`prepareThreshold=0`), a named server-side statement under the default `plan_cache_mode` (`prepareThreshold=1`, six executions first, the held one ran on the generic plan: `generic_plans=2 custom_plans=5`), and a named statement under `plan_cache_mode=force_generic_plan` (generic plan on every execution). A table shaped like the event-stream table, a `BEFORE INSERT` trigger that holds the first row of a three-event append after its identity value is taken, and one `nextval` taken up front so the calls under test do not WAL-log. Shapes: §5.1 as written, the same under `enable_nestloop=off`, `enable_material=off` and `join_collapse_limit=from_collapse_limit=1`, a rewrite that forces `unnest` to the outer side (`unnest … CROSS JOIN LATERAL (SELECT … FROM ok … OFFSET 0)`), and its mirror forcing `ok` outer; the executed plan of every held append was captured with `auto_explain`. In all 72 cases with the `xid` CTE, the held writer had a non-null `backend_xid` while holding its first identity value. Both controls without the `xid` CTE (planner order and `unnest` forced outer), in all 24 cases, showed `backend_xid` null at the same point, so the check does distinguish. Observations: a cross join has no join clause, so hash and merge joins are impossible and the plan is always a nested loop; left alone, every version and mode chose `head`/`xid` as the outer side; only the `LATERAL` rewrite put `unnest` outside, and the xid was still taken first, because no row reaches the insert until the inner side has produced a row from `ok`. An append with a stale expected event order returned zero rows and consumed no sequence value in every case (`last_value` unchanged, the next append got the next value). | Gate met. The single-statement append of §5.1 is kept as written; no separate `SELECT pg_current_xact_id()` is needed. Per §19 risk 2, the spike IT (or its engine successor) must run against every supported major in CI, because the guarantee rests on executor behaviour, not on anything the SQL standard promises. |
| P0.2 | 2026-11-10 | **Holds in autocommit; unsafe in an explicit transaction that read `pg_stat_activity` earlier.** Setup: throwaway `CombinedProbeSnapshotOrderSpikeIT`, PostgreSQL 16.15, 17.5 and 18.4, the same four protocol modes as P0.1 (prepared modes executed six times first; the generic plan was confirmed through `pg_prepared_statements`). Writers take their xid first, then insert. (1) Writer A holds a value below writer B's committed one, uncommitted: the combined probe returned B's row only and A's xid was in the running set, in every case. (2) Ordering proof: the probe was held inside its own scan (a volatile `spike_hold()` in the `WHERE` clause), i.e. after its snapshot and before any row was projected; during the hold A committed and a new writer C took an xid. In every case A's row was invisible (snapshot taken before A's commit), A was absent from the running set and C was in it (the set was read after the hold). So the running set is read after the snapshot, on every protocol and plan tried. (3) Control, two statements with the running set read first: in every case the probe missed A (A ran, held a value at or below S, was unread, and was not in the set), so the test does fail against the broken order. The two-statement fallback in the correct order (tail, then set) kept the rule. (4) Inside an explicit transaction that had already read `pg_stat_activity`, the combined probe got a fresh READ COMMITTED snapshot (it saw B) but the cached backend status of the earlier access, and missed A, in every case: backend status is cached per transaction. `pg_stat_clear_snapshot()` before the probe made it safe again, and so did an explicit transaction whose first access to `pg_stat_activity` was the probe. A randomised race hunt (6 writers, 4 s per case, about 18 000 commits and 1 700 to 4 000 probes each, running the rule end to end: probe, wait for X to retire, read the holes once) found no violation for the combined probe, but it found none for the reversed control either, so it is not sensitive enough to count as evidence; the deterministic cases above are the result. Not tested: the case where the tail read returns no rows, in which the sub-select is never evaluated and the probe returns no running set at all. | Gate met, with a condition. The combined probe of §4.10 is kept, and the engine must run it in autocommit (its own transaction), or call `pg_stat_clear_snapshot()` immediately before it when it has to run inside a transaction. The "`running` once rather than per row" shape must also return the set when the tail is empty (for example as a `UNION ALL` row), or an empty read reveals nothing about outstanding candidates. |
| P0.3 | 2026-11-10 | **Deferred.** There is no access to RDS, Aurora, Azure Flexible Server or Cloud SQL yet; the spike runs when such access is available. **Partial, 2026-11-12: Supabase (free tier, eu-central-1, on AWS) passes for its `postgres` role.** In the SQL editor, a transaction took a real xid (`pg_current_xact_id()` = 1243) and a second backend, opened through `dblink` over the session pooler, read `pg_stat_activity.backend_xid` = 1243 for it. `postgres` there is not a superuser but is a member of `pg_read_all_stats`. Not yet checked: an application role without that membership (Supabase applications usually connect as `postgres`), and connecting through the transaction pooler (port 6543), where a backend is not held per client and neither the probe nor `LISTEN` can work. | Phase 2 may proceed on stock PostgreSQL, where the start-up probe (§4.3, prerequisite 2) refuses to start if `backend_xid` is not fully visible. The new engine must not be documented or released as supported on any managed PostgreSQL service until P0.3 has passed for that service. |
| P0.4 | 2026-11-10 | **Gate partly met.** Setup: throwaway `SingleStatementAppendSpikeIT` (`spike` package, runs only with `-Dspike.run=true`), reusing P0.0's wiring and round-trip proxy; PostgreSQL 17.5, pgjdbc 42.7.14 defaults, `fsync=off`, 8 CPUs, in-tree, `-Dfailsafe.forkCount=1`, two runs (fresh container each) of three rounds after a discarded warm-up round, arm order rotated per round, fresh table per (round, case, arm). Arms, all on tables created by `SeparateTablePerAggregateTypePersistenceStrategy`, in the same unit of work, same mapper and `EssentialsJSONEventSerializers.create()`: A = `PostgresqlEventStore.appendToStream`; C = today's two statements through raw pgjdbc; B = §5.1 through raw pgjdbc, five aggregates as one pgjdbc batch. A sanity test showed B writes rows identical to A's and the engine reads them back; a stale expected order returns zero rows and consumes no identity value; a batch reports per-aggregate counts. Append p50, run 1 / run 2 (ms), A vs B: 1 event without expected order 0.098 / 0.167 vs 0.122 / 0.136, 3 vs 2 round trips; 1 event with expected order 0.099 / 0.128 vs 0.113 / 0.135, 2 vs 2; 10 events without 0.326 / 0.291 vs 0.173 / 0.202, 3 vs 2; 10 with 0.199 / 0.257 vs 0.209 / 0.210, 2 vs 2; 100 without 1.48 / 1.34 vs 0.91 / 0.86; 100 with 1.40 / 1.26 vs 0.88 / 0.89; 5 aggregates × 10 without 1.18 / 1.26 vs 0.58 / 0.57, 11 vs 2 round trips; with 0.98 / 1.00 vs 0.57 / 0.56, 6 vs 2. Run 1 was noisy at small sizes (one round ran every arm at half latency). WAL per event equal (~955-985 B). C was 0.01-0.1 ms below A, much less than B's gains. Contended (8 writers, 10 aggregates, 20 s): burned identity values per 1 000 successful appends A 463 / 465, B 248 / 270, B with `pg_advisory_xact_lock` as first CTE 259 / 264 (no effect: the snapshot predates the lock wait), B with the lock as a separate statement before the append 0 / 0 at a third of A's throughput (11.5-11.9 k vs 34.5-34.7 k appends/s; B alone 23.6-24.6 k). About half of B's conflicts were unique violations of overlapping in-flight appends. Details: [phase-0 record, P0.4](../archive/event-store-redesign-phase0.md#p04-single-statement-append). | §5.1 is kept for the new engine: fewer holes (about half) and lower latency or fewer round trips at 100 events, for several aggregates per unit of work and whenever no expected order is given; at 1 and 10 events with an expected order it ties (not met there, but never worse). §5.1's text corrected: `coalesce(max(event_order), -1)` (event orders start at 0), the mapper gets a provisional event order without an expected order, and "no hole" applies only to conflicts with already-committed appends. §5.3 corrected: the opt-in advisory lock must be a separate statement before the append, not a CTE; it gives zero holes at one extra round trip. §5.4 not dropped but restated: replacing the current engine's batch would halve its holes, not remove them; still a separate decision. |
| P0.5 | 2026-11-11 | **Gate met for I1, I3, I12 and I13; I2 fails as written in `FRESH`, and holds with one change.** Details: [phase-0 record, P0.5](../archive/event-store-redesign-phase0.md#p05-model-of-the-subscription-state-machine). Setup: a deterministic Java model (no TLA+ tools were available offline), `SubscriptionStateMachineModelTest` in the `spike.model` package, runs only with `-Dspike.run=true`, no Docker. Modelled: writers (xid, then `event_order` from committed state and the identity value, then commit or abort; unique `(aggregate, event_order)`), sequence jumps, the tail reader (probe as snapshot then running set, as P0.2 found; candidates retired by set disjointness; separate hole read), `FRESH` and `CONTIGUOUS` subscriptions with bounded buffers, attach/bridge/overflow/detach, filtered catch-up to the watermark, periodic progress or page-per-transaction progress with per-event fallback and dead-lettering, and crash/restart from the durable resume point. Not modelled: fencing (I5), `resetFrom` (I6), tenants, timing, the old engine. Breadth-first exhaustive runs (shortest counterexamples) of five configurations of 2-3 writers and two subscriptions, one crash, up to two sequence jumps: 21 thousand to 4.2 million states each, all exhausted, no violation; a sixth (`core` with a jump) bounded at 8 million states, every state to depth 20, no violation; 600 000 seeded random walks over three configurations (up to five writers, three subscriptions, two crashes), no violation. Sixteen mutants, each caught: value before xid, value before event order, running set before tail read, a cached `pg_stat_activity` (P0.2), empty tail without running set, watermark before hole read, restarted reader at the head, resume point at highest handled or at cursor, detach at the watermark, bridge read before attach, catch-up above the watermark, `CONTIGUOUS` above the watermark, `CONTIGUOUS` progress outside the handler transaction, partial page commit, back-pressure. Findings: F1, `FRESH` delivers A#n after A#n+1 when A#n commits late into a hole, because late commits arrive only through the hole read after retirement (a long writer can postpone that indefinitely); re-reading the outstanding holes in the tail statement restores I2 in every configuration. F2, the watermark must move only after the hole read, and is the cursor when no hole is outstanding. F3, attaching "within buffer distance of the tail" is unreachable behind a long writer, because catch-up stops at the watermark. F4, a restarted reader needs an initial candidate and an unknown watermark. F5, a detaching subscription must resume from §4.7's formula. F6, back-pressure would deadlock `CONTIGUOUS`. Extended the same day to several events per transaction (each event its own sequence value, so other writers' values interleave and one writer can leave several holes) and to one transaction touching two aggregates (§5.2): three more configurations exhausted (up to 9.1 million states), two more bounded or sampled; every invariant holds with F1's hole re-read, and F1 reproduces without it; no new finding. | Nothing decided. The findings are folded into the plan as proposed revisions, each marked "Proposed revision (P0.5 F*n*, 2026-11-11, not decided)": F1 in §4.2, §4.5 and §7.1 (the tail statement re-reads outstanding holes); F2 in §4.2 (watermark definition); F3 in §6, §7.1 and §10.1 (attach at the watermark, bridge `(position, cursor]`); F4 in §4.2 and §10.1 (restart rule); F5 in §4.7, §6 and §8.3 (detach position, open page); F6 in §6. §16's I2 and I13 verifications name the new shapes; the two open cost questions are §19 risks 13 (larger tail statement while holes are outstanding) and 14 (`CONTIGUOUS` attach/overflow churn behind a long writer). Before phase 2 the proposed revisions are to be accepted, changed or rejected. Phase 2-5 tests should reuse the mutants as their must-fail variants. |
| P0.6 | 2026-11-11 | **JDBI overhead small; one fetch-size interaction costs a round trip; arm C's deficit is the VM, not the driver.** Details: [phase-0 record, P0.6](../archive/event-store-redesign-phase0.md#p06-jdbi-against-raw-pgjdbc). Setup: throwaway `JdbiVsRawPgjdbcSpikeIT` (`spike` package, runs only with `-Dspike.run=true`), plus a SPIN mode added to `ContendedAppendThroughputSpikeIT`. PostgreSQL 17.5 with `fsync=off`, pgjdbc 42.7.14, JDBI 3.55.0, Hikari defaults, client and database on one 8-CPU VM, `-Dfailsafe.forkCount=1`, two runs on fresh containers, palindrome order, 20 s windows, fresh table per window. Same SQL text, parameter types and row mapping in both libraries. Uncontended, JDBI against raw, client CPU per call: one-event insert batch +6-8 µs (35-36 against 28-29), lookup without a fetch size +2-5 µs, B about +6 µs, 100-row tail read +3-6 µs; p50 +2-9 µs; server execution equal. JDBI's result iterator reads one row ahead, so `setFetchSize(1)` inside a transaction (the engine's `loadLastPersistedEventRelatedTo`) costs a second `Execute` round trip: lookup p50 96-108 µs against 56-69 µs raw, the same with `findFirst()`, 58-65 µs without the fetch size. 2026-11-11: the trace's 4 round trips for lookup + append against P0.0's 3 were reconciled. P0.0 appended to new aggregates, whose lookup finds no row and so has no read-ahead. The proxy and the trace agree on every shape: new aggregate 3 (lookup) or 2 (expected order), existing aggregate 4. Contended (8 writers, 10 aggregates), units of work per second with CPUs kept busy (50 µs client spin): lookup + insert raw 36.7-38.4 thousand against JDBI 32.1-33.0 thousand, lookup + B 37.5-38.0 against 32.6-33.9 thousand, tail read tied; in NORMAL mode JDBI is 3-10% ahead or tied. Arm C (P0.4 §5): wire messages identical to arm A (same pooled connection, same named server-side statements, same `BEGIN`/`COMMIT`); in SPIN mode C is about 10% faster than A at equal server CPU (58-60 µs per attempt); a 10 µs client spin makes C about 13% faster than A in NORMAL mode. The NORMAL-mode deficit is the idle and wake-up effect of P0.4 §9, not a driver or configuration difference. | Informs §13.2 only, as expected: library overhead is a few µs of client CPU per statement and does not decide anything on its own; the capability reasons for raw pgjdbc stand. New fact for the engine shapes: a fetch size on a single-row read is a round trip under JDBI and buys nothing raw, which points to using `setFetchSize` only for the streaming reads of §10.1 (not decided). Client-library throughput comparisons on this VM must be run in SPIN mode or they measure CPU idling. |
| P0.10 | 2026-11-12 | **Measured; §11 tier 1 as written is contradicted.** Details: [phase-0 record, P0.10](../archive/event-store-redesign-phase0.md#p010-wake-up-notification-cost). Setup: throwaway `WakeupNotifyCostSpikeIT` (`spike` package, runs only with `-Dspike.run=true`; script suite `notify`), the engine's append (`PostgresqlEventStore.appendToStream`, one event per unit of work) on engine-created tables, PostgreSQL 17.5 in two containers (`fsync=off`, `fsync=on`), `synchronous_commit=on`, 8-CPU VM, palindrome windows of 8 s, fresh table per window. Arms: no notify; `pg_notify` inside the append transaction; after commit through one coalescing notifier per JVM (the queue's `ShardWakeupNotifier` loop); after commit per writer. One `LISTEN` connection; every notification sent was received; wake latency timed on the listener's socket. With `fsync=on`, appends per second for no notify / inside / after commit coalesced / after commit per writer: 1 writer 5.2-6.2 / 4.1-4.5 / 4.7 / 4.2-4.3 thousand; 8 writers 21.5-22.3 / 8.3-9.3 / 21.8 (one window 10.8, a WAL stall) / 18.3-18.4; 20 writers 39.4-40.1 / 9.3-9.4 / 38.1-38.6 / 29.5; 8 on 10 aggregates 12.0-12.1 / 6.7 / 12.1 / 11.0-11.2. Commit p50 at 20 writers 0.36 / 2.0 / 0.37 / 0.35 ms. Writers' active time on the NOTIFY lock (`Lock:object`, classid 1262) inside the transaction: 80-81 % at 8 writers, 93 % at 20, against `LWLock:WALWrite` (group commit) 65-75 % without a notify. With `fsync=off` the same pattern, smaller (inside: −30-43 % at 8 writers, −61-64 % at 20); per-writer after commit was as slow as inside at 20 writers (80 % on the lock). Wake latency, commit to the listener's socket: after commit coalesced p50 0.09-0.18 ms, p99 0.13-0.58 ms; inside the transaction about 0.01-0.02 ms. Side finding: pgjdbc 42.7.14's `getNotifications(timeout)` keeps reading while notifications arrive and returned 1-350 times per 8 s window, so wake-ups through it lagged 0.3-2.0 s (p50) under continuous traffic; the queue's `ShardWakeupListener` drains the same way. Caveat: the container's flush is fast (~50-80 µs), so it understates the in-transaction cost; the dedicated machine is the real test. | Nothing decided. Recorded as a proposed revision in §11 (tier 1 notifies after commit, coalesced per JVM, as the queue does; a crash between commit and send loses the hint and the backstop covers it; tier 2 unaffected) and as §19 risk 15 (several notifiers, other notifying traffic, and a listener that delivers under sustained traffic). |
| P0.11 | 2026-11-12 | **Measured; F1's `OR` form is unsafe under a generic plan, a `UNION ALL` form is not.** Details: [phase-0 record, P0.11](../archive/event-store-redesign-phase0.md#p011-re-reading-holes-in-the-tail-statement). Setup: throwaway `TailHoleReReadSpikeIT` (`spike` package, runs only with `-Dspike.run=true`; script suite `tail`), PostgreSQL 17.5 container, `fsync=off`, `shared_buffers=1GB`, `jit=on`, 8-CPU VM; two 2-million-row tables (current layout 1.06 GB, §8's combined option 0.59 GB), head 3 000 000, a one-million-value jump 150 000 rows below the head, holes from a pool just below the cursor or 100 000 below it, 0/1/10/100/1 000 outstanding, 0/1/100 new rows per probe, page limit 1 000, every shape with the running-set sub-select. Plans: `B` (`> :cursor OR = ANY(:holes)`) is `BitmapOr` of two index scans as a custom plan and an ordered primary-key walk with the predicate as a filter as a generic plan (1 997 999 rows removed, ~300 ms); `auto` stayed custom on 2 M rows in this run but chose the generic plan at 1 000 holes on 300 000 rows (smoke run, 610-625 ms); `C` (`OR BETWEEN` for the jump) and `C_UNNEST` (`OR EXISTS` over `unnest` ranges) walk the index in every mode (~280-510 ms); `D` (`UNION ALL` of the tail read and `= ANY(:holes)`) is index scans in every mode; `D_RANGES` (plus a `LATERAL` branch over `unnest` ranges) has index scans but is JIT-compiled through its row estimate (5-8 ms custom on the current layout, 80-95 ms generic); `E` (two statements) index scans. Server execution per probe, current layout, one new row: today's tail 7-10 µs; `B` custom and `D` 9-16 µs up to 10 holes, 21-27 µs at 100, 119-121 µs at 1 000, near or far alike; a custom plan of a 1 000-element array adds 168-197 µs of planning. Client p50: `D` generic 36 µs (≤ 10 holes) to 195 µs (1 000), `B` unnamed 197-500 µs, `E` one round trip more than `D`. Sanity: every re-reading shape returned late commits and new rows in `global_order`. | Nothing decided. Proposed revisions in §4.2 and §4.10: the hole re-read as a `UNION ALL` branch of the tail statement, not an `OR`; a jump's ranges in a branch of their own. §19 risk 13 updated: open are a range branch that does not trip the JIT, more than 1 000 holes, other majors, the dedicated machine. |
| P0.12 | 2026-11-12 | **Measured; the running-set read scales with the cluster's connections and, at 400 connections with many readers, halves appends.** Details: [phase-0 record, P0.12](../archive/event-store-redesign-phase0.md#p012-cost-of-the-probes-pg_stat_activity-read). Setup: throwaway `ProbeRunningSetCostSpikeIT` (`spike` package, runs only with `-Dspike.run=true`; script suite `probe-cost`), PostgreSQL 17.5 containers (`fsync=off`, `max_connections` 500, plus 50 and 2 000 for the slot count), 8-CPU VM, 10/100/400 open idle connections, 20 % of them appending in the active cells and windows; 2 000 back-to-back calls per cell, `pg_stat_statements` and cgroup CPU; 6 s palindrome append windows. Server execution per call of the probe's sub-select: 10-17 µs at 10 connections (10 / 17 / 26 at `max_connections` 50 / 500 / 2 000), 60 at 100, 231-238 at 400; client p50 141-192 / 224 / 373-394 µs against `SELECT 1`'s 47-53. Combined probe (P0.11's `UNION ALL` form) against the same tail read without the sub-select: 14-20 against 3-4 µs execution at 10, 63-73 at 100, 232-249 at 400; with 20 % appending 27 / 187 / 3 362 µs. `pg_stat_get_activity(NULL)` without the view: same execution, 70-110 µs lower client p50 at 10-100 connections, about 70-90 at 400. `pg_stat_get_backend_idset()` (copy only, no xid) 36-39 µs at 400; `pg_snapshot_xip` 0.6-10 µs at every level; `pg_locks` 18-259 µs, scaling with `max_connections`; `stats_fetch_consistency` none/cache/snapshot identical; `pg_stat_clear_snapshot()` in a transaction adds one statement. Appends per second, tail-only probers against combined probers: 100 connections (20 writers, 10 probers every 10 ms) 73-75.5 against 71-71.5 thousand; 400 connections (80 writers, 40 probers every 10 ms) 49-52 against 25-26 thousand, append p99 11 against 55-64 ms, probe p50 3.5 against 13-16 ms; one prober every 1 ms 0-7 %; 10 connections within the noise. Cause of the collapse not attributed. | Nothing decided. Proposed revisions in §4.10 (read `pg_stat_get_activity(NULL)` directly; the probe is cheap only for small clusters) and §7.3 (a fixed per-JVM probe interval makes cluster-wide cost grow with JVMs × connections). New §19 risk 16. |
| P0.13 | 2026-11-12 | **Measured; a page per transaction beats the periodic save, by 2x for row-by-row handlers and 10-13x for handlers that write the page at once; the fallback as written collapses at high failure rates.** Details: [phase-0 record, P0.13](../archive/event-store-redesign-phase0.md#p013-projection-progress-per-event-per-page-and-periodic). Setup: throwaway `ProjectionProgressSpikeIT` (`spike` package, runs only with `-Dspike.run=true`; script suite `progress`), PostgreSQL 17.5 container, 8-CPU VM, client in the VM, one run, `fsync=off` and `fsync=on` passes, each run 10 s or 200 000 events per projection. One projection, `fsync=off`: PERIODIC (today) 9 000 events/s, 1 transaction and 2 round trips per event, 546 B WAL/event; PER_EVENT 6 100 (3 round trips, 652 B); PAGE_10 15 900, PAGE_100 18 300, PAGE_500 17 700 (one statement per event: ~1 round trip per event is the floor; 459-557 B, more at bigger pages because view updates stop being HOT inside a long transaction); PAGE_100/500 as a JDBC batch 94 000-96 000, as one set-based statement 117 000-119 000 (7-10 µs server CPU per event against 58). Four projections scaled all row arms by 3.0-3.2x. Fallback at PAGE_100: one failing event in 1 000 cost 23 % (14 000; 0.11 transactions per event); one in 100 made every page fail and replay: 4 600, below PER_EVENT. `fsync=on` doubled commit latency on the VM (45 to 106 µs p50) and cost the per-event arms 13-36 %; the page arms were not slowed (they ran faster, unexplained). View, resume rows and dead letters matched what had been committed in every run. | The phase-4 gate (page mode above the periodic-save baseline) is met on the VM, by about 2x without any handler change. Proposed revisions, not decided: §8.3 (the fallback backs off the page size after a failing page instead of returning straight to full pages; a page-aware handler shape for set-based writes) and risk 6. Still open: the dedicated machine, network latency, `ViewEventProcessor`'s savepoint-per-event batch mode, and views with secondary indexes. *Note 2026-11-15:* the page-aware handler is now the new subscription API's handler shape rather than an opt-in beside `@MessageHandler`, and the per-event fallback is replaced by shrinking the page and parking the failing event (§8.3, §12.3); `ViewEventProcessor`'s batch mode no longer bears on the design. |
