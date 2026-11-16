# Event store redesign, phase 4: engine design

**Status:** decided 2026-11-16 (§10); being built on `feature/event-store-engine-phase4`. When phase 4 is done, this file moves to `docs/archive/` or is folded
into the redesign's record (`docs/plans/README.md`).
**Scope:** phase 4 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): `CONTIGUOUS` mode
(§7); progress in the handler's transaction with a page per transaction (§8.1, §8.3); the page error model: a failed
page shrinks, the failing event is retried and then parked, and later events of its aggregate are held behind it (§8.3,
§19 items 18 and 21). Also the item phase-3 decision 3 deferred here: where a `FRESH` bridge starts after a detach.
**Gate:** I12 and I14 pass (§16); projection throughput above the periodic-save baseline; `CONTIGUOUS` latency within
one probe interval of `FRESH` with no long writer. I1-I4, I9, I10, I11 and every phase-3 rule IT keep passing.

Section references (§n) are to the plan; "phase-2 §n" and "phase-3 §n" are `docs/plans/event-store-engine-phase2.md`
and `docs/plans/event-store-engine-phase3.md`. Where this draft proposes something the plan does not say, it is marked
**Proposed**. The existing `PostgresqlEventStore` and its subscriptions are not changed, and nothing below copies their
design: no `SubscriptionErrorPolicy`, no redelivery policy, no built-in permanent-error list, no `@MessageHandler`. The
public subscription API stays phase 8's (§12.3); phase 4 adds the smallest handler contract that lets a page and its
progress share a transaction (§3.1) and says how phase 8 replaces it.

---

## 1. Types and what changes from phase 3

### 1.1 Two independent axes

A subscription has a **consistency mode** (`FRESH`, `CONTIGUOUS`: which received rows may be handed on) and a
**progress model** (`PERIODIC`, `IN_HANDLER_TRANSACTION`: where the resume point is written). `el_progress` already
stores both (phase-2 §2.5). **Proposed:** the delivery code treats them as two independent strategies, so all four
combinations exist, but phase 4 gates and measures the two that are defaults or baselines: `CONTIGUOUS` +
`IN_HANDLER_TRANSACTION` (the view-projection default, §7.2, §8.1) and `FRESH` + `PERIODIC` (phase 3, the baseline).
The other two get one smoke IT each. Building them costs nothing extra, because each strategy is used by one of the
gated pairs.

### 1.2 Types

| Type | Phase 4 | One line |
|---|---|---|
| `FreshSubscription` | **Renamed `Subscription`** (still public, still temporary) | One lifecycle (phase-3 §3.1) for both modes; the mode decides what is released (§2), the progress model how a page is committed (§3). The rename is mechanical for the phase-3 ITs; a class called `FreshSubscription` that runs `CONTIGUOUS` would mislead |
| `ConsistencyMode`, `ProgressModel` | New package-private enums | The values `el_progress` already checks |
| `Subscription.Handler` | Unchanged (phase 2/3's per-event handler) | Used by `PERIODIC` |
| `Subscription.PageHandler` | New, public (temporary) | `handle(List<StoredEvent> page, Connection connection)`, used by `IN_HANDLER_TRANSACTION` (§3.1) |
| `ParkEventException` | New, public | The explicit "park this event now" signal of §19 item 21 (§4.4) |
| `HeldBack` | New, package-private | `CONTIGUOUS`'s rows above the received watermark, ordered by `global_order` (§2.2) |
| `PageRunner` | New, package-private | Builds a page, runs it in a transaction, the step-down shrink, retries and parking (§3, §4) |
| `ParkedEvents` | New, package-private | `el_parked_events` statements and the in-memory set of held aggregates (§4.5) |
| `Inbox` | One mode added | A `CONTIGUOUS` inbox keeps counting an event until the subscription releases it (§2.3) |
| `HandledSet` | One method added | `gaps(limit)`: the unhandled ranges between `P` and the highest handled order (§5) |
| `EventLogSettings` | Fields added | `pageSize` (100), `retryAttempts` (3), `retryBackoffInitial` (100 ms), `retryBackoffMax` (10 s) (§4) |
| `EventLog` | One method added | `subscribe(…, ConsistencyMode, PageHandler)` beside phase 2's `subscribe`; temporary like it |
| `TestSeams`, `WatermarkMutant` | Extended | The hooks and must-fail mutants of §8 |
| `EventLogObserver` | Extended | `onPageCommitted(size)`, `onPageFailed(size, cause)`, `onParked`, `onHeldAggregates(count)`, `onReleased` |

Threads and ownership are phase 3's (phase-3 §1.2, §1.3) with one addition: `HeldBack`, the page state, the error state
(§4.2) and the held-aggregate set are owned by the delivery thread. The reader loop is not touched except that it offers
to a `CONTIGUOUS` inbox exactly as to any other.

---

## 2. `CONTIGUOUS` on the shared reader

### 2.1 What is shared, what is not

A `CONTIGUOUS` subscription uses the same `ReaderLoop`, the same `TailReader` per type, the same batches and the same
`Inbox` as a `FRESH` one (§6: "both modes consume the same shared read"). It costs the reader nothing extra: no
statement, no per-subscription state in the loop. Its lifecycle is phase-3 §3.1's (catch-up `(P, W]`, wait for the
watermark, attach at the quiescent point, attached, detach), with two differences: **what it hands on** (only rows at or
below its received watermark, in strict `global_order`) and **the bridge** (deferred, §2.4).

### 2.2 Release rule

The delivery thread keeps, besides the phase-3 received watermark `W_rcv`:

- `A`, the **applied prefix**: the highest order whose effect is committed (in-transaction) or saved (periodic). The
  stored resume point is `A + 1`.
- `R`, the **received prefix**: every existing order in `(A, R]` is in the subscription's **pending** list, in
  `global_order` order. `A ≤ R ≤ W_rcv`.
- `HeldBack`: rows received above `R`, sorted by `global_order`.

A batch's rows go into `HeldBack`, except a row at or below `R`, which is dropped: it is in pending already, or applied
(F5's "a `CONTIGUOUS` subscription drops anything at or below the last order it applied", generalised to the received
prefix). Then, when `W_rcv` rises, every held row at or below `W_rcv` moves to pending in order and `R = W_rcv`. Pages are
built only from pending (§3.2). That a pending list built this way is complete — every committed order in `(R_old, W_rcv]`
is in it — is phase-3 §3.2's F2 argument: every row at or below a batch's watermark reaches the inbox no later than that
batch, above the attach cursor `C`; below it, the deferred bridge supplies them (§2.4).

`CONTIGUOUS` needs no handled runs: nothing above `R` is ever handed on, so `A` is a plain prefix and `R` replaces the
phase-3 `HandledSet` for this mode. The rule "release only at or below the **received** watermark, never the reader's
live one" is phase 3's I3 rule applied to release (mutant `RELEASE_AT_READER_WATERMARK`, §8).

### 2.3 The bound counts held-back events

Phase 3's inbox stops counting an event when the delivery thread takes its batch. For `CONTIGUOUS` that would make the
bound meaningless, since held-back rows leave the inbox and wait in `HeldBack`. **Proposed:** an inbox created for a
`CONTIGUOUS` subscription counts an event from `offer` until the delivery thread calls `release(n)`, which it does when
the row is applied, parked or dropped. Everything else in phase-3 §2.3 stands: the bound counts events, an inbox holding
none accepts any batch, a **watermark-only batch is always accepted** and costs nothing, and a refused batch is an
overflow that detaches. The last two are what F6 needs: a full `CONTIGUOUS` subscription still receives every watermark
move, so it can always release, and the loop never waits for it. Detach instead of back-pressure stays load-bearing:
with back-pressure, held rows would block the only thread that can move the watermark that releases them (F6; phase 3's
`BACK_PRESSURE` mutant gets a `CONTIGUOUS` variant of I9).

### 2.4 The deferred bridge

Phase 3's attach reply is `(C, W, H)`. A `FRESH` subscription bridges `(P, C]` at once, re-reading the holes `H` on every
page so that per-aggregate order holds across pages (phase-3 §3.4). **Proposed:** a `CONTIGUOUS` subscription does not
bridge at attach. It records `C` and, each time `W_rcv` rises while `R < C`, reads `(R, min(W_rcv, C)]` with phase 3's
`rangeRead`, paged by `catchUpPageSize`, and appends those rows to pending before it releases any held row above `C`.
Held rows at or below `min(W_rcv, C)` are dropped, as the range read returns them.

- **Correct without hole re-reads.** Every order in the range is at or below a received watermark, so it is final (F2):
  the read needs no running set, no hole branch and no care across pages. The F1 hazard of a paged bridge (a late commit
  between pages) cannot occur in a final range. Above `C` every row reaches the inbox (phase-3 §3.4's argument), so
  `(R, W_rcv]` is complete. Mutant: `CONTIGUOUS_SKIPS_DEFERRED_BRIDGE` (loses `(P, C]` rows not seen in a batch).
- **Free behind a long writer.** While the watermark does not move, the deferred bridge reads nothing. This is what
  settles risk 14 for `CONTIGUOUS` (§5.2).
- **Statements.** Without a long writer the range is usually already covered by the first watermark after the attach,
  so the cost is the same one bridge read phase 3 pays.

### 2.5 Detach and catch-up

Overflow, reader restart, reset and stop are phase-3 §3.6's causes, handled at the same points. For `CONTIGUOUS`:

```
on detach:  keep pending (A, R]; drop HeldBack and the inbox; catch-up resumes reading above R
```

Pending rows are at or below a received watermark, hence final and complete; keeping them is F5's "an open page's events
go back into the buffer ahead of the catch-up" without re-reading them. The stored resume point stays `A + 1`. A crash
at this point resumes from `A + 1` and re-reads pending by catch-up, which is equivalent. Catch-up reads `(R, W]`,
appending to pending, never above the type's watermark (F4 unchanged); then attach, with the deferred bridge from `R`.

**Re-attach without a pause.** After an overflow behind a long writer, `R` is already at the watermark, so catch-up has
nothing to read and the subscription attaches again at once. Under §2.4 that attach costs one request to the loop and no
statement; the subscription then holds batches until its buffer fills and overflows again. §5.2 weighs this against the
plan's alternatives.

---

## 3. Pages and the page transaction

### 3.1 The handler contract (smallest shape)

```java
@FunctionalInterface
public interface PageHandler {
    /**
     * Apply a page of events, in global_order order, using connection. The engine owns the transaction: it has
     * begun it, and commits it together with the resume point after this returns, or rolls it back if this throws.
     */
    void handle(List<StoredEvent> page, Connection connection) throws Exception;

    /** A row-by-row handler on top of the page shape */
    static PageHandler perEvent(EventHandler handler) { … }  // EventHandler: (StoredEvent, Connection)
}
```

- **Who opens the transaction:** the delivery thread, on a connection borrowed from the engine's `DataSource`,
  `autoCommit = false`, default isolation. The premise of in-transaction progress is that the output lives in the same
  database (§8.1); phase 4 additionally assumes the same `DataSource`.
- The handler gets a **guarded view** of that connection (a `java.lang.reflect.Proxy`): `commit`, `rollback`,
  `setAutoCommit`, `close` and `abort` throw `IllegalStateException`; savepoints and everything else pass through. A
  handler that commits would split its output from the resume point, which is exactly what I12 forbids.
- A handler that wants the batch or set-based form P0.13 measured (10-13x on the VM, 45-50x on the server) writes the
  page in one statement; `perEvent` is the row-by-row case.

**How phase 8 replaces it.** Phase 8's API (§12.3) puts the page in the application's own transaction management (a
`UnitOfWork`, or Spring's transaction manager over the same `DataSource`): the engine asks the application's
transaction factory for a unit of work, hands the page to the processor, and writes the progress statement through that
unit's connection before it commits. `PageHandler` then becomes an internal adapter for that shape, and
`EventLog.subscribe(…, PageHandler)` is removed with phase 2's `subscribe`. Nothing in the engine below the page runner
depends on the handler's signature: the page runner needs only "run this page with this connection, then let me write
progress on it", which both shapes provide.

### 3.2 Building a page

- From **pending** only (for `FRESH`, from the received-but-unhandled rows of phase-3 §3.2; for `CONTIGUOUS`, from §2.2's
  pending), in `global_order` order, up to the current **page size** (§4.2; at most `pageSize`, default 100, P0.13: no
  gain above about 100 row by row, and longer transactions lose HOT on the view).
- **No waiting to fill a page:** a page closes when pending is empty (§8.3, "bounded by count and by time").
- Rows of a **held aggregate** are taken out of the page and parked as blocked in the same transaction (§4.5); the
  handler never sees them.
- During catch-up a page is cut from the rows of a catch-up read; the read's connection is returned before the page's
  transaction begins (phase-3 §3.3's rule, unchanged). A page holds exactly one connection and borrows no other.

### 3.3 The page transaction

```
borrow connection; autoCommit = false
  hook beforePageHandler
  handler.handle(page, guarded(connection))              -- the application's writes
  INSERT el_parked_events … ON CONFLICT DO NOTHING       -- only if this page parks or holds rows (§4.5)
  hook afterHandlerBeforeProgress
  UPDATE el_progress SET resume_from = $new, updated_at = now()
   WHERE subscriber = $s AND aggregate_type = $t AND instance_name = $i
     AND reset_epoch = $epoch
     AND resume_from = $expected                          -- Proposed: compare-and-set on the previous value
     AND ($fence::bigint IS NULL OR lease_fence = $fence) -- null until phase 7
  hook afterProgressBeforeCommit
commit
hook afterCommit
A = new - 1; release(page) in the inbox count
```

- **The progress update is the last statement**, so the progress row's lock is held only through the commit; a reset
  waiting on it waits for one commit, not for a handler.
- `$new` for `CONTIGUOUS` is the page's ceiling + 1: the last row of the page, or `R` if the page emptied pending
  (everything in `(A, R]` is then applied or parked). For `FRESH` it is §4.7's formula, `P + 1` from phase 3's handled set
  (§8.3's last bullet), so in-transaction `FRESH` replays above the watermark after a crash, as §8.1 says.
- `reset_epoch = $epoch` is §8.2's compare-and-set: a reset since the page was read updates nothing, and the whole page,
  output included, rolls back (I6's in-transaction half is phase 7's gate, but the guard is in from phase 4).
- **Proposed: `resume_from = $expected`** (the value this subscription last committed). It costs nothing (same row,
  same statement) and makes every in-transaction page a compare-and-set on the position it was built from. Until phase 7
  fences exclusive subscriptions, two JVMs started with the same key (phase-2 §3.5's documented hazard) would otherwise
  both apply every page to the same view: with it, the second commit updates zero rows and rolls back. Phase 7's fence
  then adds ownership; this guard stays, as it also catches an operator's edit of the row.
- **Zero rows** rolls back, then one primary-key read decides (phase-2 §3.4): epoch changed → reposition at the reset's
  resume point (a reset also deletes the subscription's parked rows, §4.5); `resume_from` changed under the same epoch →
  another writer: log at error, reposition at the row's value (dropping everything at or below it); row gone → recreate
  at `A + 1` and retry the page. None of these count as a handler failure.

### 3.4 The two progress models side by side

| | `PERIODIC` (phase 3) | `IN_HANDLER_TRANSACTION` (phase 4) |
|---|---|---|
| Handler | `Handler.handle(event)`, per event, no connection from the engine | `PageHandler.handle(page, connection)`, in the engine's page transaction |
| Unit of work | The handler's own, per event | One per page |
| Progress write | Autocommit `UPDATE` at most every `progressSaveInterval`, on a borrowed connection, only when it moved | In the page transaction, last statement, every page |
| Guard | `reset_epoch` (fence from phase 7) | `reset_epoch` + `resume_from = expected` (fence from phase 7) |
| Value | §4.7: `P + 1` (phase-3 §3.2) | `CONTIGUOUS`: page ceiling + 1. `FRESH`: §4.7 |
| Parking (§4.5) | Park row inserted in autocommit **before** the event counts as handled | Park row inserted in the page transaction that moves the resume point past it |
| Page shrink | None: every "page" is one event | Step-down (§4.2) |
| After a crash | Replays from the last save | `CONTIGUOUS`: replays nothing already committed. `FRESH`: replays above the watermark |
| Connections held | None while handling | One per open page |

### 3.5 Connection budget

Phase-3 §4.2 changes in one row: each in-transaction subscription holds **one connection per open page**, for the
duration of its handler. A pool of 4 with ten in-transaction subscriptions makes them queue for pages: slower, never
deadlocked, since a page borrows nothing while it holds its connection and a catch-up read returns its connection before a
page begins. The pool must be sized for the number of concurrently projecting subscriptions; the gate records pool
waits (§9).

---

## 4. Failures

### 4.1 Classes of failure

| Class | What | Action |
|---|---|---|
| I/O | The page connection is no longer valid (`isValid(1)` false), or the cause chain holds an `SQLException` of class `08` or a `SQLTransientConnectionException` | Roll back, retry the same page indefinitely, backoff doubling from `retryBackoffInitial` to `retryBackoffMax`; no shrink, no attempt counted (§19 item 21: "I/O failures are retried indefinitely") |
| Progress compare-and-set | Zero rows from the progress update | §3.3; not a failure |
| Park | `ParkEventException` naming an event of the page, or thrown from a page of one | Park that event at once (§4.4) |
| Handler | Anything else, including `IllegalArgumentException` | Shrink, then retry, then park (§4.2, §4.3). **No built-in list of permanent errors** (§19 item 21) |
| Interrupt | The delivery thread is interrupted (stop) | Roll back; not handled (§6) |

### 4.2 Step-down shrink

The page size starts at `pageSize`. After a page of size `n` starting at order `a` fails with a handler failure, the
**suspect range** is that page's `[a, last]`, and the next page starts at `a` with size `max(1, n / 2)`. A page that
commits keeps the size while it is still inside the suspect range; once a page starts **above** the suspect range, the
size doubles after each committed page, up to `pageSize` (P0.13's revision: grow back after pages that commit, so the
cost is bounded by the failure rate, not multiplied by the page size).

- Isolating one failing event in a page of 100 costs at most `ceil(log2 100) = 7` failed pages; the events before it
  commit in halves on the way down.
- Not growing back inside the suspect range is what keeps the search from re-including the failing event in a full page
  over and over (the "grow at once" variant re-fails up to `log2 n` more times per failure).
- The suspect range and the current size are delivery-thread state and survive a detach (pending survives too, §2.5), so
  an overflow during isolation does not restart it. They are memory only: a restart starts at full size and isolates
  again, which costs at most another `log2 n` failed pages.

### 4.3 Retries

Once a failing event is alone in a page, it is retried `retryAttempts` times (default 3), sleeping
`retryBackoffInitial × 2^k` (100, 200, 400 ms), capped at `retryBackoffMax`. Shrink steps are not retries and do not
sleep: the events around the failure commit at full speed. While an event is retried the subscription waits; in
`CONTIGUOUS` nothing above it may be applied anyway, and in `FRESH` delivery is serial (§6). The inbox meanwhile fills
and may overflow; that is a detach, not a loss (§2.5). An event that succeeds on a retry is applied once (I14 "once").

### 4.4 Parking

When an event's retries are used up, or the handler throws `ParkEventException` for it:

- In-transaction: the next page transaction inserts the park row (`blocked_by` null, `attempts`, `error_type`,
  `error_message`) **and** moves the resume point past the event, so the event is either still pending or durably
  parked, never neither. The handler is not called for the parked event; it is called for the rest of the page as usual.
- Periodic: the park row is inserted in autocommit, and only then does the event count as handled; a crash in between
  replays the event, and the insert's `ON CONFLICT DO NOTHING` makes the replay find it parked.
- `ParkEventException(StoredEvent event, String reason, Throwable cause)`: thrown from a page of several events it must
  name one of them, which is parked at once after the page's events before it commit in a page of their own. Thrown
  without an event from a page of more than one, it is treated as a handler failure, so shrinking reaches a page of one,
  where it parks at once with no retries.

The resume point moves past a parked event (§8.3); the parked rows are what remains to be delivered for their aggregates.

### 4.5 Holding the rest of the aggregate

`ParkedEvents` keeps, per subscription, the **held aggregates**: aggregate id → the `global_order` of its parked head.
It is loaded at start from `el_parked_events` (`blocked_by IS NULL`, through the `_aggregate` index) and changed only by
the delivery thread.

- **Every event of a held aggregate** that the subscription would hand on is parked instead, as a row with
  `blocked_by = head`, in the page transaction (or, periodic, autocommit before it counts as handled), in order; the
  handler never sees it. Every other aggregate continues (§19 item 18).
- **The watermark is not involved.** Holding is per subscription and per aggregate; the reader, the watermark, `R` and the
  resume point move on as if the held events had been applied, because their delivery is now recorded durably in
  `el_parked_events`. A held aggregate therefore never holds back a `CONTIGUOUS` subscription's other aggregates, and never
  delays `FRESH` at all.
- **`FRESH`:** the same rule; a parked or held event counts as handled in the handled set. With `PERIODIC`, the park
  insert precedes that, as in §4.4.
- **Retry and discard** (the admin surface is phase 8; phase 4 adds package-private `Subscription.retryParked(head)` and
  `discardParked(head)`, run on the delivery thread between pages). Retry: one page transaction locks the aggregate's
  parked rows (`SELECT … FOR UPDATE`), loads their events by `global_order` (`= ANY`, on the page connection), runs the
  handler on them in order, deletes the rows, removes the aggregate from the held set after the commit, and runs
  `el_progress`'s compare-and-set with `$new = $expected` so a reset since rolls it back. If it fails, attempts are
  incremented and the aggregate stays held. Discard: the same with the head left out of the handler and deleted. Events
  of the aggregate still pending are applied normally after the retry, so per-aggregate order holds: everything up to the
  release was parked in order, everything after comes later in `global_order`.
- **Reset** deletes the subscription's parked rows in the same transaction that bumps the epoch, since a rebuild
  re-delivers everything (§10.3). Phase 4 adds this to the package-level reset used by the tests; the reset API is phase 8's.

---

## 5. Re-attach churn (risk 14, phase-3 decision 3)

### 5.1 What phase 3 measured, and how it grows

Phase-3 §7.4: one slow `FRESH` subscription behind a one-minute writer at 100 ev/s re-attached three times and its
bridges read 2 500 rows, 69 % of them already handled. The volume per attach is everything the reader has read since the
writer took its value, which grows linearly with the writer's age, and attaches come at a steady rate, so the total
grows with the **square** of the writer's duration: at the same rates, about 100 times §7.4's 1 730 redundant rows over a
ten-minute writer and several thousand times over an hour. A batch job or a session idle in a transaction is exactly that
writer (§4.6). Handling throughput and correctness were unaffected; read volume and delivery-thread time were not.

### 5.2 `CONTIGUOUS`: decided by the deferred bridge

The plan's options (§19 risk 14) were: stay detached while the watermark has not moved, back off between attach attempts,
or a separate bound for held-back events. **Proposed:** none of them; the deferred bridge (§2.4) removes the cost they were
meant to limit. Behind a long writer a `CONTIGUOUS` subscription attaches (one request, no statement), holds batches
until its buffer is full, overflows, finds catch-up empty and attaches again: the churn is one loop request and one
buffer's worth of memory per cycle, and **zero statements**, however long the writer runs. When the writer ends, the
first watermark above `R` makes it read `(R, min(W, C)]` once and release the rest from memory. Staying detached would
save only the memory of one buffer, at the cost of reading every row above the hole back from the table when the writer
ends; a back-off would add latency at the writer's end for nothing. §9 measures the cycle to confirm it.

### 5.3 `FRESH`: the bridge starts above the highest handled order

`FRESH` must bridge above the hole (that is its point), so the deferred bridge does not apply. **Proposed (settles
phase-3 decision 3):** the bridge reads `(max(P, highest handled), C]` and, on every page, re-reads as its hole branch
both the attach holes `H` (phase 3) and the **gaps of the handled set**: the unhandled ranges between `P` and the highest
handled order, `HandledSet.gaps(limit)`, single values in the `= ANY` array and longer ranges in the range branch
(`bridgePageWithRanges`, no new statement).

- **Nothing is lost.** Every order in `(P, C]` is either handled (in a run, which phase 3's bridge would read and drop),
  or unhandled. An unhandled order at or below the highest handled one is a gap, re-read by the hole branch on every page
  until returned; one above it is in the range. That is phase 3's bridge minus the rows it would drop. A late commit that
  sat in a discarded inbox batch is exactly such a gap. Mutant: `BRIDGE_FROM_HIGHEST_WITHOUT_GAPS`.
- **Per-aggregate order** is phase-3 §3.4's argument with a larger hole set: the only orders a page can miss below its
  lower bound are gaps, re-read with every page.
- **Bounded.** The gaps are the handled set's runs, about the reader's hole count (one per long writer, plus
  out-of-order commits). Above `holeRangeThreshold` gaps the bridge falls back to phase 3's `P` start, which is correct,
  only slower.
- The re-read volume per attach becomes the rows not yet handled, so the total is linear in the writer's duration.

---

## 6. `CONTIGUOUS` against the invariants

- **I1.** Catch-up covers `(R, W]` (final); the deferred bridge `(R, min(W_rcv, C)]` (final); batches everything above
  `C`. A detach keeps pending and resumes reading above `R`; a reader restart detaches (phase-3 §2.4). A parked event is
  delivered to the park table, which is the plan's definition of "remaining to be delivered" for its aggregate.
- **I2.** Strict `global_order` within `(A, W_rcv]`; held aggregates are parked in order and released in order (§4.5).
- **I3.** The stored resume point is `A + 1`, written only with the page that applied `A`, and `A ≤ R ≤ W_rcv`.
- **I9.** The loop never waits (§2.3); pages hold a connection only on the subscription's own thread.
- **I12, never above the watermark.** Rows reach pending only at or below `W_rcv`, which is a watermark the reader
  published after the rows it covers (F2).
- **I12, exactly once under a crash.** §7.
- **I14.** §4.2-§4.4; tested in §8.

---

## 7. Crash at every step (I12)

The durable state of an in-transaction `CONTIGUOUS` subscription is (projection, `el_progress.resume_from = A + 1`,
`el_parked_events`), and only a page transaction changes it, from `(S, A)` to `(S ⊕ fold((A, A′] minus parked), A′)` with
the park rows for `(A, A′]`, atomically. Memory (`R`, pending, `HeldBack`, the inbox, the error state, the held set) is
derived and rebuilt on restart: `A` and the held set from the tables, the rest by catch-up from `A + 1`. Step by step:

| Crash point | Durable state | On restart | Why exactly once |
|---|---|---|---|
| Catch-up or deferred-bridge read, before any page | `(S, A)` | Catch-up from `A + 1` | Nothing committed |
| Holding rows, releasing to pending | `(S, A)` | Same | Memory only |
| Page transaction open, inside the handler | `(S, A)` — the transaction dies with the backend | Same; the page is rebuilt from the same final rows | Rolled back; strict order rebuilds the same page or a prefix of it |
| After the handler, before the progress update | `(S, A)` | Same | Same |
| After the progress update, before commit | `(S, A)` | Same | Same; the row lock dies with the transaction |
| Commit sent, outcome unknown to the JVM | `(S, A)` or `(S′, A′)` | Reads `A` from the row | The row says which; the projection agrees with it because both were in one transaction |
| After commit, before `A` is updated in memory | `(S′, A′)` | Catch-up from `A′ + 1` | Nothing at or below `A′` is read again |
| Park chosen (retries used), before the parking page | `(S, A)` | The event fails again: shrink and retries are repeated, then it parks | Not applied (it always failed) and not parked twice (`ON CONFLICT`) |
| Retry of a parked aggregate, inside its transaction | Parked rows still there | Still held (held set reloaded) | Rolled back, as a page |
| During a detach, with or without an open page | As whichever page last committed | Catch-up from `A + 1` | The detach is processed by the delivery thread only between pages (§7.1) |

### 7.1 Detach while a page is open

The loop closes an inbox at any time (overflow, reader restart), but the delivery thread only acts on it between pages,
after the open page has committed or rolled back. If it committed, `A` moved and pending lost those rows; if it rolled
back, they are still in pending, ahead of the catch-up (§2.5). That is F5's rule ("an open page is left out of the detach
position; if it rolls back, its events go back into the buffer; if it commits, a `CONTIGUOUS` subscription drops everything
at or below the last order it applied") obtained structurally: there is no moment at which the detach position is
computed while the outcome is unknown. The two ways to get it wrong are mutants (§8.2): computing the position as if the
page had committed, and re-reading the page's range without the drop rule.

---

## 8. Test plan

Every new rule has an IT seen to fail against a mutant (§16, history §9.13). New mutants go into `WatermarkMutant`,
settable only through `TestSeams`. New hooks on `TestSeams.Hooks`, all on the delivery thread: `beforePageHandler`,
`afterHandlerBeforeProgress`, `afterProgressBeforeCommit`, `afterCommit`, `onRelease(rows, receivedWatermark)`,
`onPark(globalOrder, blockedBy)`. A hook may throw `TestSeams.Crash`, which ends the delivery thread with no further
statement (and the IT then terminates the page connection's backend, so the transaction dies as with a lost JVM).
Unit tests: `HeldBack` (order, release at a watermark, drop at or below `R`), the step-down schedule (sizes, suspect
range, grow back), `Inbox` in counting-until-released mode, `HandledSet.gaps`.

### 8.1 Gate rows

| # | Invariant (§16) | IT | Must-fail variants |
|---|---|---|---|
| I12 | `CONTIGUOUS` never above the watermark; with in-transaction progress a crash at any point leaves the projection equal to applying each event once | `EventLogContiguousCrashIT`: four writers on one type with random commit order, rollbacks and a writer that holds a value for 2 s; a view table `(aggregate_id, count, last_global_order, digest)` where each event sets `digest = md5(digest ‖ global_order)` and asserts `global_order` > the last one applied anywhere (strictly increasing). For **each** hook of §8 (and the catch-up and deferred-bridge reads), repeatedly: crash there, restart with the same key, continue. At the end compare every aggregate's row with a reference fold over the event table in `global_order`. `onRelease` also asserts each released order ≤ the reader watermark published before it | `PROGRESS_AFTER_COMMIT` (progress in its own transaction after the page: a crash between → applied twice); `RELEASE_AT_READER_WATERMARK` (out of order or above the watermark); `NO_HOLD_BACK` (`CONTIGUOUS` hands on every received row: the held value arrives after higher orders); `CONTIGUOUS_SKIPS_DEFERRED_BRIDGE` (loses rows) |
| I14 | A failing event is isolated, retried, then parked; nothing else lost or applied twice | `EventLogPageFailureIT`: pages of 100 over 1 000 seeded events; (a) the event at offset 37 of one page throws once: no park, fold equal, failed pages ≤ 8; (b) it always throws: parked with `attempts = 4` and its error, fold equal to the reference that skips it, every other event once; (c) `ParkEventException` naming it: parked at once, `attempts = 1`; (d) (b) with a crash at each hook during isolation and parking | `NO_SHRINK` (the whole page is retried, then its first event is parked: the wrong event is parked and the page never commits past the failing one, so the fold differs); `PARK_OUTSIDE_PAGE_TRANSACTION` (park row in its own transaction after the page commits: (d) loses the event); `GROW_INSIDE_SUSPECT_RANGE` (failed-page bound in (a) exceeded) |

### 8.2 New rules

| Rule | IT | Must-fail variants |
|---|---|---|
| Hold the rest of the aggregate | `EventLogParkedAggregateIT`: A1 always fails, A2, A3 follow, B events interleaved; A2, A3 parked with `blocked_by = A1`, never handled; every B applied. Restart: A4 parked too. `retryParked` with the handler fixed: A1-A4 applied in order, rows gone, A5 after. `discardParked`: A2-A4 applied, A1 not. A reset deletes the parked rows | `NO_AGGREGATE_HOLD` (A2 applied after A1 parked: per-aggregate assertion fails); `HELD_SET_NOT_RELOADED` (A4 applied after restart) |
| Detach with a page open | `EventLogContiguousDetachIT`: buffer 10; the handler blocks in `beforePageHandler`; writes overflow the inbox; then (a) the page commits, (b) the page throws once. Every event applied once, in order | `DETACH_POSITION_PAST_OPEN_PAGE` (catch-up starts above the open page whatever its outcome: (b) loses it); `REREAD_WITHOUT_APPLIED_DROP` (catch-up from `A` before the commit with no drop at or below `A`: (a) applies twice) |
| Held-back events count against the bound; watermark-only batches always accepted | `EventLogContiguousDetachIT`: a long writer holds a value; a `CONTIGUOUS` subscription overflows, re-attaches, overflows; zero bridge statements while the writer runs (observer); after it commits, every event applied once in order. And I9's IT with a `CONTIGUOUS` blocked subscription | `HELD_NOT_COUNTED` (memory grows past the bound: asserted on `HeldBack` size); `BACK_PRESSURE` (existing, on a `CONTIGUOUS` inbox: deadlock, others starve) |
| `FRESH` bridge above the highest handled order | `EventLogDetachIT`: a long writer holds `g0`; a late commit `g1` (below the highest handled) sits in a batch that overflow discards; after re-attach `g1` is delivered once; bridge rows dropped as handled = 0 | `BRIDGE_FROM_HIGHEST_WITHOUT_GAPS` (loses `g1`) |
| In-transaction compare-and-set on the previous resume point | `EventLogContiguousCrashIT`: two subscriptions with the same key on two `EventLog`s; the fold still matches | `PROGRESS_WITHOUT_EXPECTED_RESUME` (applied twice) |
| Reset rolls back a page | `EventLogContiguousCrashIT`: reset while a page is open in `afterHandlerBeforeProgress`; the page's output is absent and delivery restarts at the reset point | phase 2's reset test, in-transaction |
| I/O failure retried without shrinking | `EventLogPageFailureIT`: terminate the page connection's backend inside the handler; same page retried, no park, page size unchanged | — (asserted through the observer) |
| Handler cannot commit | Unit test of the guarded connection | — |

### 8.3 Phase 2's and phase 3's ITs

Unchanged except the rename (`FreshSubscription` → `Subscription`, mechanical) and phase 3's handler behaviour on a throw:
retry forever after the backstop becomes §4's retry, backoff and park. No phase-3 IT relies on retrying forever (their
handlers block on latches, they do not throw). Every `must_fail_*` test keeps its mutant. Against PostgreSQL 16, 17 and
18, as before.

---

## 9. Gate measurement

`spike/EventLogGateSpikeIT`, suite `p4-gate` (`p3-gate` stays for its own arms), same harness as phase-3 §7:
`pg_stat_statements` by role, fresh container per arm, two runs of every arm, every `getConnection()` timed.

| Measurement | Shape | Pass |
|---|---|---|
| Projection throughput | P0.13's shape on the new engine: 200 000 seeded events over 20 000 aggregates, payload about 200 B, one view table per projection with an `INSERT … ON CONFLICT DO UPDATE` per event; 1 and 4 projections; pool sized projections + 2. **Baseline:** `FRESH` + `PERIODIC`, per-event handler upserting in its own autocommit transaction (P0.13's `PERIODIC`, 1 s save). **Arms:** `CONTIGUOUS` + in-transaction with `perEvent` (row by row), page 10 and 100; a JDBC-batch page handler, page 100; a set-based (`unnest`) page handler, page 100. Plus `FALLBACK`: page 100 with one event in 1 000 always failing | Row-by-row page 100 above the baseline, for 1 and 4 projections. Recorded: batch and set-based factors, page-10 factor, the fallback's cost against page 100 (P0.13 measured −23 % on the VM before the step-down), the failing events all parked |
| `CONTIGUOUS` latency, no long writer | One `FRESH` and one `CONTIGUOUS` subscription on the same type in the same run; P0.0's steady shape at 100 ev/s with **one** writer (no holes) and with **four** concurrent writers (holes from out-of-order commits); backstop 100 ms and 1 s, `probeBackoff(backstop, 10 s)` as phase 3's spike; commit-to-handle p50 and p99 | `CONTIGUOUS` p50 and p99 each at most `FRESH`'s plus one probe interval (`probeWait` at the measured probe cost, which these settings floor at the backstop). Expected: equal with one writer, where the watermark equals the cursor |
| Churn behind a long writer | Phase-3 §7.4's shape (50 ms handler, 100 ev/s, pool 4, 60 s and 300 s writers), once with `CONTIGUOUS` and once with `FRESH` | Recorded: attaches, detaches, bridge and deferred-bridge statements and rows, rows dropped as handled. Expected: `CONTIGUOUS` zero bridge statements while the writer runs; `FRESH` bridge rows dropped as handled near 0 (§7.4: 69 %), linear in the writer's duration |
| Statements per delivered event | Phase-3 §7.2's shape with 10 subscriptions, half of them `CONTIGUOUS` in-transaction | Recorded against phase-3 §7.2 (0.196 / 0.020 at 100 ms): an in-transaction page adds no statement outside its own transaction, the saves of `PERIODIC` are replaced by one update per page |

---

## 10. Decisions (2026-11-16)

The draft's open questions, settled by the maintainer working through the phases autonomously, each as the draft
recommended. The sections above already follow them.

| # | Question | Decision |
|---|---|---|
| 1 | Where the page transaction comes from in phase 4 | **The engine borrows a connection from its own `DataSource`** and hands the handler a guarded `Connection` that refuses commit, rollback and close (§3.1). Phase 8 replaces it with the application's transaction management over the same `DataSource` |
| 2 | Compare-and-set on the previous resume point in the in-transaction update | **Added now** (`resume_from = $expected`, §3.3), beside phase 7's fence when that comes |
| 3 | `FRESH` re-attach churn (phase-3 decision 3) | **The bridge starts above the highest handled order** and re-reads the handled set's gaps (§5.3), falling back to starting at `P` above `holeRangeThreshold` gaps |
| 4 | `CONTIGUOUS` re-attach churn (risk 14) | **Deferred bridge with immediate re-attach** (§2.4, §5.2); §9 confirms it costs no statement behind a long writer |
| 5 | Mode × progress-model pairs | **All four built**; `CONTIGUOUS` + in-transaction and `FRESH` + periodic are gated, the other two get one smoke IT each |
