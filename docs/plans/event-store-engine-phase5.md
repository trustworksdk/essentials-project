# Event store redesign, phase 5: engine design

**Status:** decided 2026-11-16 (§10); being built on `feature/event-store-engine-phase5`. When phase 5 is done, this file moves to `docs/archive/` or is folded
into the redesign's record (`docs/plans/README.md`).
**Scope:** phase 5 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): filtered catch-up, the
adaptive fallback, the per-database cap on catch-up reads and the opt-in `(event_type, global_order)` index (§10.1,
§10.2, §13.3). Also the item phase-3 decision 2 deferred here: whether catch-up may hand gaps below the head to the
reader ("catch-up with its own candidate"), which decides what F4 costs a `FRESH` subscription after a JVM restart behind
a long writer (§6).
**Gate:** I13 and I15 pass (§16); the rare-type catch-up and restart-storm shapes (§17) beat the baseline defined in §8.
Every phase-2, phase-3 and phase-4 IT keeps passing, with every `must_fail_*` test.

Section references (§n) are to the plan; "phase-3 §n" and "phase-4 §n" are `docs/plans/event-store-engine-phase3.md` and
`docs/plans/event-store-engine-phase4.md`. Where this draft proposes something the plan does not say, it is marked
**Proposed**. The existing `PostgresqlEventStore` and its subscriptions are not changed, and nothing below copies their
design: no gap tables, no per-subscriber gap state, no polling optimizer. The public subscription API stays phase 8's
(§12.3); phase 5 adds one value type for the event-type filter (§1.2) and says how phase 8 absorbs it.

**What phase 0 measured for these shapes: nothing.** The phase-0 record lists §17's rare-type catch-up and restart storm
as "not measured" (P0.0, "Reading and consequences") and P0.7 did not measure the `(event_type, global_order)` index. The
only related numbers are P0.0's burst (today's engine catches up 100 000 events in 2.3-2.6 s, 0.375 statements per event,
8 events per range read) and P0.5's model, which checks I13 with filtered catch-up to the watermark and catches
`CATCHUP_ABOVE_WATERMARK`. Every threshold below is therefore a starting value that §8's gate measurement confirms or
moves; none is presented as measured.

---

## 1. Types and what changes from phase 4

### 1.1 Summary

| Type | Phase 5 | One line |
|---|---|---|
| `EventTypeFilter` | **New, public (temporary)** | The set of event type names a subscription consumes; `all()` is the default (§1.2) |
| `EventLog` | One overload per `subscribe` | Takes an `EventTypeFilter`; the existing overloads pass `all()` |
| `Subscription` | Filter applied on every read path (§2) | Catch-up, the `FRESH` bridge and the `CONTIGUOUS` deferred bridge filter in SQL; batches are filtered in memory |
| `Inbox` | Counts matching rows only | A batch with no matching row is stored as watermark-only (§2.4) |
| `HandledSet` | One method added | `addRange(lo, hi, except)`: marks a filtered bridge page's covered range handled, minus the attach holes (§2.3) |
| `CatchUpReads` | **New, package-private** | Builds and runs a filtered read: type ids, scan window, skip-ahead probe, the permit (§3, §4) |
| `CatchUpPermits` | **New, package-private** | The per-database semaphore (§4) |
| `EventTypeIndex` | **New, package-private** | Builds the opt-in index outside a transaction and reports whether it is valid (§5) |
| `ReaderLoop`, `TailReader` | One request added | `handOff(type, gaps)`: gaps below the head from an attach under an unknown watermark (§6) |
| `EventLogSettings` | Fields added | `catchUpPermits` (4), `catchUpScanWindow` (100 000 orders), `catchUpScanWindowMax` (10 000 000), `eventTypeIndexed` (set of aggregate types, empty) |
| `EventLogSchemaContributor` | One statement builder added | `eventTypeIndexStatements(tables)`: the index DDL text, which stays in the contributor (§5) |
| `TestSeams`, `WatermarkMutant` | Extended | Hooks and must-fail mutants of §7 |
| `EventLogObserver` | Extended | `onCatchUpPermit(waitNanos, inUse)`, `onFilteredRead(scanned orders, rows, window, usedIndex)`, `onGapsHandedOff(count)` |

Threads and ownership are phase 4's. The reader loop gains one request kind and nothing else: it never filters, never
takes a permit and reads exactly what it read before.

### 1.2 The filter's shape (smallest)

```java
public record EventTypeFilter(Set<String> eventTypes) {   // empty set: every event type
    public static EventTypeFilter all() { … }
    public static EventTypeFilter of(String... eventTypes) { … }
    public boolean matches(String eventType) { … }
}
```

- **Names, not ids.** The caller knows names (`StoredEvent.eventType()` is the name); ids are interned per database
  (`el_event_types`) and resolved by the engine (§2.2).
- **Not persisted.** The progress row does not store the filter. Changing a subscription's filter changes what it
  receives from its current position on; events of a newly added type below that position are not re-delivered. A
  rebuild is a reset (§10.3). Phase 8's API decides whether to store a filter digest and refuse or warn on a change; this
  is recorded as its item, not decided here.
- **Tenant** (§10.1's "and tenant = :tenant") is not part of phase 5. The engine stores `tenant`, but a tenant filter
  adds a second column to the index question and no gate shape needs it; phase 8 adds it beside the type filter with the
  same rules (§2).
- **How phase 8 absorbs it.** Phase 8's subscription definition carries "optional tenant and event-type filter"
  (§12.3). `EventTypeFilter` becomes a field of that definition and the temporary `subscribe` overloads go with phase
  2's. Nothing below the delivery code depends on how the filter is passed in.

---

## 2. Where the filter is applied

### 2.1 Rule

Every row a subscription receives goes through one decision: **a non-matching row is handled as a no-op.** It is never
handed to a handler, and it counts as handled (`FRESH`) or applied (`CONTIGUOUS`) exactly as if a handler had run and
done nothing. The SQL filter is an optimisation of that rule on the paths where the range read is final (catch-up, the
`CONTIGUOUS` deferred bridge) or where every unreturned order is accounted for (the `FRESH` bridge, §2.3). That is the
argument for I13: the filter never decides *how far* a subscription may advance; the ceiling of the read does.

| Path | Where filtered | Advances to |
|---|---|---|
| Catch-up `(P, W]` (both modes) | SQL | `W` on a short page, the last scanned order on a full one (§2.2) |
| `CONTIGUOUS` deferred bridge `(R, min(W_rcv, C)]` | SQL | as catch-up (the range is final, phase-4 §2.4) |
| `FRESH` bridge `(max(P, highest handled), C]` with hole and gap re-reads | SQL, in both `UNION ALL` branches | Covered range minus attach holes marked handled (§2.3) |
| Attached batches (shared tail read) | Memory, in the inbox and on the delivery thread | As phase 3/4: `W_rcv` from the batch |
| Hole re-read and hole read of the reader | Not filtered | — (the reader serves every subscription) |

### 2.2 Filtered catch-up and how it advances

```sql
-- filteredRangeRead
SELECT <cols> FROM el_order_events
 WHERE global_order > $1 AND global_order <= $2
   AND event_type = ANY($4::smallint[])
 ORDER BY global_order
 LIMIT $3;
```

- **`$2` is the scan ceiling**, `min(W, lower + window)` (§3), never the reader's cursor or the head. `W` is the
  watermark the catch-up read from the type's state before the statement, as phase 3: every order at or below it is
  final, so an order the filtered read does not return is either non-matching, never committed, or rolled back, and can
  never become a matching event later.
- **A short page advances to the ceiling, not to its last row** (§10.1). `FRESH`: `W_rcv = ceiling`, so phase 3's
  `P = min(W_rcv, lowest unhandled − 1)` moves to the ceiling once the page's rows are handled. `CONTIGUOUS`: `R = ceiling`
  (phase-4 `toPending`), and `A` follows when pending empties. A full page advances to its last row, because orders
  above it in the window were not scanned. Mutant `FILTERED_CATCH_UP_TO_CURSOR`: a short page advances to the reader's
  cursor (I13, §7.1).
- **Type ids are resolved after the ceiling is read.** A name with no id yet (`el_event_types` has no row) is left out of
  the array; an id is cached only once found. The order matters: every event at or below `W` committed before `W` was
  published, and its type was interned in its own committed transaction before the event was appended (module
  `CLAUDE.md`, decision 2), so a lookup made **after** reading `W` sees every type that can occur at or below it. A lookup
  made before reading `W` can miss a type interned in between and advance past its first event. Mutant
  `TYPE_IDS_BEFORE_CEILING` (§7.1). The lookup costs one statement per page only while some name is unknown, which means
  no event of that type exists at or below the ceiling yet.
- **An empty id set** (no name known) reads nothing: the page is short with zero rows and advances to the ceiling with no
  event-table statement at all.
- **Attach condition** is phase 3's: the catch-up position has reached the watermark it read under (F3). Filtering does
  not change it.

### 2.3 The `FRESH` bridge with a filter

The bridge reads above `W`, which is not final. Phase 4's bridge already re-reads, on every page, the attach holes `H` and
the handled set's gaps. **Proposed:** both branches carry `AND event_type = ANY($ids)`, and after a page whose range
read covered `(lo, ceiling]` the delivery thread marks **that whole range, minus the attach holes and ranges it has not
yet returned, as handled** (`HandledSet.addRange`). Without that, every non-matching order would become a gap of the
handled set, be re-read on every later page and, above `holeRangeThreshold`, push the bridge back to `P`.

- **Why the range is complete minus `H`.** Phase-3 §3.4: every order at or below `C` was, at the quiescent point, final
  below `W`, read by the reader (committed before the attach, so visible to the bridge page's later snapshot), permanent,
  or in `H`. So an order in `(lo, ceiling]` that the page did not return is non-matching or in `H`. Holes in `H` stay
  unhandled and keep being re-read, filtered, until the bridge reaches `C`; after that they reach the subscription through
  the reader's hole re-read, filtered in memory.
- **Mutant `FILTERED_BRIDGE_MARKS_HOLES`:** the attach holes are marked handled with the range; a matching event that
  commits late into one is dropped as already handled (I13, §7.1).

### 2.4 Attached subscriptions: filtering the shared batch

The tail read stays unfiltered (§10.1: it serves every attached subscription). **Proposed:** the inbox is given the
subscription's filter at creation.

- `offer(batch)` counts only the batch's matching rows against the bound (a scan of the batch on the loop thread: 50
  subscriptions × a 100-row batch is 5 000 string comparisons per read, no allocation; with an `all()` filter, no scan).
- A batch with **no matching row** is stored as a watermark-only batch, merged into a trailing one: always accepted,
  costs no capacity (phase-3 §2.3). A rare-type subscription that is slow therefore overflows only when its *matching*
  backlog exceeds the bound, not when the type is busy.
- The delivery thread hands on the matching rows and treats the rest by §2.1: `FRESH` adds them to the handled runs
  (they merge with their neighbours, so the runs stay one per gap); `CONTIGUOUS` drops them on receipt, since `R` moves
  with `W_rcv` regardless (phase-4 §2.2), and the inbox releases only the matching rows it counted.
- Mutant `INBOX_COUNTS_UNMATCHED`: not a correctness mutant; the filtered `EventLogSlowSubscriptionIT` (§7.3) asserts a rare-type subscription
  does not overflow while matching rows stay under the bound.

### 2.5 Detach (F5) with a filter

Unchanged: `P = min(W_rcv, lowest order in the batch being worked through − 1)`. Non-matching rows of that batch are
handled no-ops, so they never lower the minimum, and they never raise it either: the position is computed from received
watermarks and unhandled matching rows only. I13's F5 shape with a filter is a test (§7.1) with phase 3's
`DETACH_RESUMES_AT_WATERMARK` as its mutant.

---

## 3. Adaptive fallback

### 3.1 What can go wrong with a filtered read

Without the index, `filteredRangeRead` is a primary-key range scan with the type predicate as a filter: it examines
rows until it has `$3` matching ones or reaches the ceiling. For a type that is 1 % of the table and a page of 500 that
is about 50 000 rows per page, and for a type that does not occur in the range it is the whole range up to `W`, which
after a long outage can be millions of rows in one statement. With the index the planner can instead scan
`(event_type, global_order)` per id and sort; for a common type that sort covers far more rows than the page needs.
Both are bounded below without relying on a statement timeout.

### 3.2 Rule (**Proposed**)

Marten's shape (§10.1) waits for a statement timeout and then falls back. **Proposed:** bound every filtered read by an
order **window** from the start, so a statement's cost is known before it runs, and keep the timeout as a backstop only.

```
ceiling = min(W, lower + window)
rows    = filteredRangeRead(lower, ceiling, pageSize)
if rows is full:   lower = last row                     -- the window stays
else:              lower = ceiling;  window = min(2 × window, catchUpScanWindowMax)
if the statement was cancelled (SQLState 57014): window = max(window / 4, pageSize); retry the same lower
```

- **Starting window** `catchUpScanWindow`, default 100 000 orders: at P0.0's catch-up rate and P0.7's row sizes a primary-key
  range of that size is tens of milliseconds, and a page that fills inside it costs no more than an unfiltered page.
  Doubling after each short page means a rare type crosses a range of `N` orders in about `log2(N / window)` statements
  plus one per full page; 1 000 000 orders at 1 % are about 20 pages of 500 plus a few short ones.
- **Skip-ahead probe, only with a valid index.** When `EventTypeIndex` reports the index valid (§5.3), a short page is
  followed, before the next window, by

  ```sql
  SELECT global_order FROM el_order_events
   WHERE event_type = ANY($3::smallint[]) AND global_order > $1 AND global_order <= $2
   ORDER BY global_order LIMIT 1
  ```

  (§10.1: `LIMIT 1`, not `min()`). No row: the catch-up advances to `W` at once. A row at `g`: the next window starts at
  `g − 1`. Without the index the probe would be the same scan as the read, so it is not issued.
- **When the filter is dropped.** Never in SQL: without the index a filtered range scan reads the same pages as an
  unfiltered one and ships fewer rows, so it is never worse. With the index, the risk is the planner choosing the type
  index plus a sort for a common type. **Proposed:** the delivery thread measures the density of each short or full page
  (`rows / orders scanned`); above `0.25` it stops issuing the skip-ahead probe and adds `+ 0` to the type comparison
  (`event_type + 0 = ANY(…)`), which keeps the predicate but makes the type index unusable, so the read is the
  primary-key walk; below `0.05` it uses the plain form again. Both thresholds are the gate's to confirm (§8.1, the
  density sweep). This is the only "fallback"; it changes the plan, never the result.
- **Plans.** The catch-up statements run on pooled connections with pgjdbc defaults (phase-3 §3.3). Both forms join
  `EventLogGenericPlanIT`: under a generic plan each must be an index range scan on the primary key, or on the type index
  for the probe, never a sequential scan.
- **Statement timeout** is the application's (none by default); the engine sets none on pooled connections (phase 3:
  session settings would leak into the pool). A cancellation from one is handled as above, not as a read failure.

---

## 4. The per-database cap (§10.2)

### 4.1 What takes a permit

**Every event-table read a subscription's delivery thread makes**: catch-up pages, the skip-ahead probe, the `FRESH`
bridge pages and the `CONTIGUOUS` deferred-bridge reads. The plan names catch-up reads; the bridge is included because a
restart storm turns into a bridge storm a moment later, with the same rows. Not covered: the reader loop (it holds its
own connection and is one per `EventLog`), page transactions and their handler work, progress saves, parked-event
statements and the type-id lookup. I15 counts exactly the covered statements.

### 4.2 Scope: per database, per JVM (**Proposed**)

- `CatchUpPermits` is a JVM-wide registry of fair `java.util.concurrent.Semaphore`s keyed by database identity, read once
  when an `EventLog` starts: `current_database()`, `inet_server_addr()`, `inet_server_port()` (a socket path has a null
  address, and the key then uses the JDBC URL's host part). Two `EventLog`s on the same database in one JVM, for example
  two bounded contexts with separate `DataSource`s, share one semaphore; the first one's `catchUpPermits` sizes it, and a
  later one with a different value logs a warning and uses the existing size.
- **Not cluster-wide.** A cluster-wide cap needs a shared counter: advisory-lock slots (`pg_try_advisory_lock(k, i)` for
  `i` in `0..n-1`) cost a statement per acquire and release and hold a session lock on a pooled connection, which the
  pool would hand to someone else. Ten JVMs restarting together read with at most `10 × catchUpPermits` statements, each
  bounded by §3's window. Open question 1 (§10).
- **Fair** (FIFO): fifty subscriptions starting together read in turn, so the last one waits about
  `50 / permits` statement times per page, not unboundedly. A permit is held for **one statement**: acquired before
  `getConnection()`, released after the result set is closed and the connection returned, before any row is handled. A
  subscription that is behind therefore cannot hold a permit while its handler runs or while its page transaction waits
  for a connection.

### 4.3 Interaction with the pool and the reader loop

- **No deadlock.** A permit holder holds nothing else and waits only for one pooled connection; a page transaction holds
  one connection and no permit (phase-4 §3.2: the catch-up read returns its connection before the page begins); the loop
  holds its connection and no permit. There is no cycle.
- **Sizing.** With a pool of `n` the loop holds 1, so at most `n − 1` catch-up statements can run anyway; a cap at or
  above that does nothing, and the engine logs once at start when `catchUpPermits ≥` the pool size it can see (Hikari's
  `getMaximumPoolSize()` through the `HikariConfigMXBean` if present, otherwise nothing). The point of the cap is the
  appenders: they share the database, often the pool, and the cap leaves them `n − 1 − permits` connections and the
  server's CPU.
- **Interruption.** `stop()` interrupts a delivery thread waiting for a permit; it acquires nothing and saves as usual.
- **Starvation of the loop's restart.** A reader restart (phase-3 §2.4) borrows its connection outside the cap, so a
  storm cannot keep the reader from reconnecting.

---

## 5. The opt-in `(event_type, global_order)` index

### 5.1 DDL and where it lives

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS el_order_events_type_order ON el_order_events (event_type, global_order);
-- after a failed build left it invalid:
DROP INDEX CONCURRENTLY IF EXISTS el_order_events_type_order;
```

The name comes from `AggregateTypeTables` (validated, never truncated, as every derived name). The text lives only in
`EventLogSchemaContributor.eventTypeIndexStatements(tables)` (the schema harness rule; `SchemaRulesTest` keeps passing).

### 5.2 Why the harness does not run it, and what does

The harness applies a change set in one transaction (`docs/design/database-schema-harness.md`), and `CREATE INDEX
CONCURRENTLY` cannot run inside one. A plain `CREATE INDEX` on an existing event table takes a `SHARE` lock that blocks
every append to that aggregate type for the length of the build, which is not "safe against an existing object" in any
useful sense. **Proposed:**

- The setting is `EventLogSettings.eventTypeIndexed`, a set of aggregate types (empty by default), per aggregate type
  because the index costs one more btree insert per appended event (§13.3).
- **Schema mode `create` (the component owns its schema):** after the aggregate type's tables exist, `EventTypeIndex`
  runs the statement on a dedicated connection from the engine's `DataSource`, autocommit, on a virtual thread, never on
  the start-up path. It first checks `pg_index.indisvalid` for the name: valid → done; invalid → `DROP INDEX
  CONCURRENTLY` then create; absent → create. Two JVMs starting together both issue the statement; `IF NOT EXISTS` makes
  the second a no-op or, if it races the first's catalog entry, fails with a duplicate that is logged at debug and
  re-checked. No advisory lock is held: it would be a session lock held for minutes on a pooled connection.
- **Other modes (`validate`, `emit`, external):** the engine builds nothing. It logs once, at warn, the exact statement
  to run, and checks validity as below. `emit` includes the statement in its output as a comment-marked manual step.
- **A new, empty table** gets the same statement; on an empty table `CONCURRENTLY` costs two catalog waits, not a build.
  One code path rather than a plain `CREATE INDEX` inside the aggregate-type change keeps "is it there and valid" the
  only question.
- **A long writer delays the build.** `CREATE INDEX CONCURRENTLY` waits for every transaction that could still see the
  table without the index, so a session idle in a transaction holds it in its first phase. Nothing waits for it:
  filtered reads run without the index until it is valid (§3.2).
- **Removing the setting** does not drop the index; the documentation says how to drop it by hand.

### 5.3 Validity is read, not assumed

`EventTypeIndex.valid(type)` is `pg_index.indisvalid AND indisready` for the name, read when the type's first subscription
starts and again after a build finishes; it decides only whether §3.2's skip-ahead probe is issued. The read statements
are the same with or without the index, so a missing or invalid index costs speed, never correctness. Tests assert both.

---

## 6. Catch-up with its own candidate, and F4's cost for `FRESH` (phase-3 decision 2)

### 6.1 The cost

After a JVM restart (or a reader restart) the reader starts at the head with an initial candidate `(head, running set)`
and an unknown watermark until that candidate retires (F4). No subscription may catch up past the last known watermark
or attach, and after a restart there is none. A `FRESH` subscription therefore delivers nothing of that aggregate type
for as long as the oldest transaction running at the restart lasts: a batch job, or a session idle in a transaction, can
make that minutes or hours. That defeats `FRESH`'s point (delivery above a hole) exactly when every subscription of the
type is behind. `CONTIGUOUS` is unaffected: it may not deliver above the hole anyway.

### 6.2 "Catch-up with its own candidate" is not needed

§10.1's "or computes with its own candidate when no tail reader runs" cannot occur: a type's reader runs whenever a
subscription of it exists (phase-3 §2.4). Its own candidate would also be held by the same running writer. Rejected.

### 6.3 Hand gaps below the head to the reader (**Proposed**)

1. A `FRESH` subscription whose attach finds the watermark unknown, **and** has waited one backstop interval (so writers
   that end at once keep F4's simple wait), sends `attachUnknown(inbox)`. At the quiescent point the loop attaches it and
   replies `(C, W = unknown, H)` as phase 3 does.
2. The subscription bridges `(P, C]` with phase 4's bridge: every page re-reads the handled set's gaps, filtered as §2.3.
   `P` is final below (it was a received watermark when saved), so every order in `(P, C]` the bridge has not returned is
   a gap: rolled back, or held by a writer of the initial candidate's running set (I4: xid before value).
3. When the bridge reaches `C`, the subscription sends `handOff(type, gaps)` (singles and ranges, as the handled set
   encodes them). The loop adds them to the type's unresolved holes **owned by the initial candidate**: re-read in every
   tail statement (F1's `UNION ALL` branch), resolved only by the hole read after the initial candidate retires (F2).
4. From then on the subscription is attached as usual. `W_rcv` stays below the lowest gap until a batch carries the known
   watermark, so `P`, and the resume point, never pass a gap (I3).

- **Nothing is lost.** Between the last bridge page and the hand-off, a gap may commit; the reader's next re-read
  returns it whenever it committed, because a re-read is `= ANY(holes)`, not a range above a cursor. If the initial
  candidate retired before the hand-off arrived, every order at or below the head is already final, and the loop answers
  the hand-off with one hole read of those gaps. Mutants: `GAPS_NOT_HANDED_OFF` (a held matching event commits after the
  bridge and is never delivered), `HANDED_GAPS_RESOLVED_AT_ONCE` (the loop treats handed gaps as resolved; same loss).
- **Per-aggregate order** is F1's argument: a late commit into a handed gap is published ahead of the new rows of the
  same tail read.
- **Bounded.** More than `holeRangeThreshold` gaps (an old position with many rolled-back values in `(P, C]`) falls back to
  F4's wait for that subscription. Under the new engine a hole is a rollback or a held value only (a lost
  optimistic-concurrency check burns nothing, I10), so this is rare.
- **Other subscriptions** of the type that are still behind keep waiting: their catch-up is still capped by the unknown
  watermark. Each one that has waited a backstop interval attaches the same way, so all `FRESH` subscriptions of a type are
  delivering after one bridge each, and the permits (§4) pace those bridges.

**Decision recommended: adopt §6.3** (open question 2). The alternative, F4's wait as built, is correct and costs nothing
to keep, and the observer already reports the waiting subscriptions and the xid holding them (phase 3).

---

## 7. Test plan

Every new rule has an IT seen to fail against a mutant (§16, history §9.13). New mutants go into `WatermarkMutant`,
settable only through `TestSeams`. New hooks on `TestSeams.Hooks`: `afterCeilingBeforeTypeIds` and `beforeFilteredRead`
(delivery thread), `onPermit(subscriber, acquired)` (delivery thread), `beforeHandOff(subscriber, gaps)` (delivery
thread) and `onHandOff(type, gaps)` (loop thread, at the quiescent point). Unit tests: `EventTypeFilter`; `Inbox` with a
filter (matching count, zero-match batch merged as watermark-only, release of matching rows only); `HandledSet.addRange`
with exceptions; the window schedule (doubling, cap, quartering on cancel); `CatchUpPermits` (fairness, one key per
database, size warning).

### 7.1 Gate row I13

| Shape | IT | Must-fail variants |
|---|---|---|
| Long writer holds a matching event below the cursor while a filtered subscription catches up past it | `EventLogFilteredCatchUpIT`: types `X` (matching, 1 in 100) and `Y`; a transaction holds `X` at `g0`; 20 000 `Y` and 200 `X` commit above it; a filtered subscription (both modes) starts at 1 and catches up; then `g0` commits. Asserts `g0` delivered exactly once, every other `X` once, no `Y`, per-aggregate order, the progress row never above `g0 − 1` while it is held. Run with and without the index | `FILTERED_CATCH_UP_TO_CURSOR`; phase 2's `CATCH_UP_ABOVE_WATERMARK` applied to the filtered read |
| A type first appended while the catch-up runs | Same IT: a type `Z` in the filter has no id yet. The `afterCeilingBeforeTypeIds` hook holds the delivery thread after it has read the ceiling; meanwhile a writer interns `Z`, appends a `Z` event and commits, and the reader publishes a watermark above it. The next page must return the `Z` event | `TYPE_IDS_BEFORE_CEILING` (ids resolved before the ceiling is read: the page's array lacks `Z` while its ceiling passes the `Z` event, which is lost) |
| Restarted reader (F4) | `EventLogFilteredCatchUpIT`: a writer holds a matching value below the head; `terminateReaderBackend()`; a filtered subscription behind the head waits (or, `FRESH`, attaches under §6.3); the held value commits and is delivered once | phase 2's `RESTARTED_READER_AT_HEAD`; `GAPS_NOT_HANDED_OFF`; `HANDED_GAPS_RESOLVED_AT_ONCE` |
| Detach after overflow (F5) | `EventLogDetachIT`, filtered: buffer 10; the handler blocks on a matching `g1` with matching `g2…g11` and non-matching rows between them in the inbox; the next batch overflows; release; every matching event once, in order | phase 3's `DETACH_RESUMES_AT_WATERMARK` |
| Filtered `FRESH` bridge | `EventLogAttachIT`, filtered: a long writer holds attach hole `h` (matching); non-matching rows fill `(W, C]`; `h` commits at `betweenBridgePages` | `FILTERED_BRIDGE_MARKS_HOLES` |

### 7.2 Gate row I15

| # | IT | Must-fail variant |
|---|---|---|
| I15 | `EventLogCatchUpCapIT`: 50 subscriptions (25 `FRESH`, 25 `CONTIGUOUS` in-transaction, half filtered) on two `EventLog`s over the same database (one `DataSource` each), each 20 000 events behind, `catchUpPermits = 4`, reader pools of 20; the `onPermit` hook and a probe connection sampling `pg_stat_activity` for the reader role every 5 ms count concurrent event-table reads. Asserts the hook's count never exceeds 4 across both `EventLog`s, the sampled count of active catch-up statements never exceeds 4, every subscription delivers everything, and a writer appending at 50 ev/s throughout never waits for a connection longer than one catch-up statement | `NO_CATCH_UP_PERMIT` (reads without a permit: the count reaches the pool's size); `PERMIT_PER_EVENT_LOG` (one semaphore per `EventLog`: the count reaches 8); `PERMIT_HELD_THROUGH_PAGE` (permit released after the page transaction: with a pool of 5, every page waits behind permits, asserted as the writer's wait bound failing) |

### 7.3 Other new rules

| Rule | IT | Must-fail variant |
|---|---|---|
| Short filtered page advances to the ceiling, not to its last row | `EventLogFilteredCatchUpIT`: a subscription on a type with one event at the start of 1 000 000 orders reaches the watermark in at most `log2` windows plus one statement (observer) | `FILTERED_ADVANCE_TO_LAST_ROW` (it crawls: statement bound exceeded; no loss) |
| Window bounds a statement; cancellation quarters it | `EventLogFilteredCatchUpIT`: `SET statement_timeout` on the reader role; a cancelled read retries with a smaller window and loses nothing | — (observer) |
| Index invalid or absent costs speed only | `EventLogEventTypeIndexIT`: build on an existing 200 000-row table while appends run (no append waits above 1 s); an invalid index (a build cancelled by `pg_cancel_backend`) is dropped and rebuilt; in `validate` mode nothing is built and the warning names the statement; reads equal with and without | — |
| Filter on attached batches | `EventLogSlowSubscriptionIT`, filtered: a slow rare-type subscription does not overflow while its matching backlog is under the bound | `INBOX_COUNTS_UNMATCHED` (it overflows) |

### 7.4 Phase 2-4 ITs

Unchanged: the existing `subscribe` overloads pass `EventTypeFilter.all()`, for which every path is the phase-4 one (no
type predicate, no scan of batches). The catch-up reads gain the window (§3.2) for unfiltered subscriptions too only if
§10 question 4 says so; otherwise they keep phase 3's `(P, W]` read. Every `must_fail_*` test keeps its mutant. Against
PostgreSQL 16, 17 and 18.

---

## 8. Gate measurement

`spike/EventLogGateSpikeIT`, suite `p5-gate`; today's engine in `CurrentEngineBaselineSpikeIT`, suite `p5-gate-old`. Same
harness as phase-4 §9: `pg_stat_statements` by role, a fresh container per arm, two runs of every arm, every
`getConnection()` timed, writer and reader roles separated. Each gate is decided here, before the run, and a result inside
the noise counts as no gain (§17).

### 8.1 Rare-type catch-up

**Shape** (§17): 1 000 000 seeded events on one aggregate type over 100 event types, 100 000 aggregates, payload about
200 B; the subscribed type is 1 % of the events (10 000), spread uniformly; the subscription starts at 1 with an empty
handler and is timed until its resume point passes the head. No writes during the run.

**Baseline, precisely:** today's engine, wired as `spring-boot-starter-postgresql-event-store` wires it (P0.0's wiring:
`SeparateTablePerAggregateTypePersistenceStrategy`, batch size 10, polling interval 100 ms), one non-exclusive
subscription whose handler ignores every event not of the subscribed type, which is how a projection consuming 1 % of
the types runs today. Measured: time to catch up, reader statements, rows returned to the client.

**Reference arms** (recorded, not gated): the phase-4 engine unfiltered (`EventTypeFilter.all()`, handler ignores
the rest); the filtered read without the window (one statement per page up to `W`). **Arms:** filtered, without the
index; filtered, with the index (built before the run, `valid` checked). Plus a density sweep with the index: the
subscribed share at 1, 5, 25 and 50 %, to place §3.2's thresholds.

**Pass:** both filtered arms catch up faster than the baseline and read fewer rows; the indexed arm returns at most 1.05 ×
the matching rows plus one row per statement. **Recorded:** append latency with and without the index (P0.4's append
shape, 1 and 10 events), the index's size, the density at which the type-index plan loses to the primary-key walk.

### 8.2 Restart storm

**Shape** (§17): 50 subscriptions behind at start-up, appenders running. Seed 100 000 events on one type (20 types,
payload about 200 B); the 50 subscriptions' progress rows point at 1. Then start 8 writers appending one event per unit
of work at 25 ev/s each (200 ev/s), wait 10 s, start all 50 subscriptions at once, measure until every one is attached
and 10 s after.

**Baseline, precisely:** today's engine with 50 non-exclusive subscriptions, starter wiring, one Hikari pool of 10 shared
by writers and subscriptions (the starter's single `DataSource`), resume points at 1. Measured: append p50/p99/max during
the catch-up window against the 10 s before it, peak connections (`pg_stat_activity`, all roles), time until all 50
deliver live.

**Arms:** the phase-5 engine, 50 subscriptions (25 `FRESH` periodic, 25 `CONTIGUOUS` in-transaction, half of each
filtered to 10 % of the types), engine and writers on one pool of 10, `catchUpPermits` 4; the same uncapped (reference,
phase-4 behaviour); `catchUpPermits` 2 and 8.

**Pass:** append p99 during the storm at or below the baseline's, and peak connections at or below the baseline's;
statements in flight never above the cap (I15 again, on the spike's sampler). **Recorded:** time to all-live (the cap
trades it for append latency; not gated), largest permit wait, largest `getConnection()` wait for writers.

### 8.3 Optional: `FRESH` after a restart behind a long writer (§6)

Only if §6.3 is adopted: a 60 s writer holds a value; the JVM restarts at 10 s; one `FRESH` subscription at 100 ev/s.
**Recorded** (not a gate): time from restart to first delivery (F4: the writer's remaining 50 s; §6.3: one backstop plus
one bridge), bridge rows, gaps handed off.

---

## 9. Against the invariants

- **I1, I13.** The filter only removes rows that are non-matching; how far a read advances is the ceiling of a final
  range (catch-up, deferred bridge) or the covered range minus the holes still re-read (`FRESH` bridge); ids are
  resolved after the ceiling; batches are phase 3's; detach is F5 unchanged; a restart is F4 or §6.3's hand-off.
- **I2.** Unchanged: strict `global_order` within each read; hole and gap re-reads keep F1 in the filtered bridge and in
  handed-off gaps.
- **I3.** `W_rcv` comes from received ceilings and watermarks only; a handed-off gap keeps `P` below it.
- **I9.** The loop scans a batch for each filtered inbox and never waits; permits are taken only on delivery threads.
- **I12, I14.** Unchanged: a non-matching row is never in a page.
- **I15.** §4.

---

## 10. Decisions (2026-11-16)

The draft's open questions, settled by the maintainer working through the phases autonomously, each as the draft
recommended. The sections above already follow them.

| # | Question | Decision |
|---|---|---|
| 1 | Scope of the catch-up cap | **Per JVM per database**, one fair semaphore shared by every `EventLog` on that database (§4). A cluster-wide cap would need advisory-lock slots, which cost statements and hold session locks on pooled connections |
| 2 | F4's cost for `FRESH` (phase-3 decision 2) | **Gap hand-off (§6.3).** A `FRESH` subscription that has waited one backstop interval attaches under the unknown watermark and hands the gaps it finds to the reader as holes of the initial candidate. F4's wait stays as the fallback above `holeRangeThreshold` gaps. Catch-up with its own candidate is not built |
| 3 | Who builds the opt-in index | **The engine, in schema mode `create` only**, with the statement text kept in `EventLogSchemaContributor` (§5.2); other modes log the statement to run. A non-transactional change kind in the `foundation` harness can replace this later |
| 4 | Windowed reads for unfiltered catch-up | **No.** Unfiltered catch-up keeps phase 3's paged range read |
| 5 | Streaming reads (`setFetchSize`) | **Deferred** to the burst shape; not part of phase 5 |
