# CDC: deferred recording of opened gaps

Status: implemented (`components/postgresql-event-store`), ships in the next release.

## Problem

The CDC bus delivers events in **commit** order. When appenders run concurrently, a transaction that took a lower
`global_event_order` often commits after one that took a higher order. Each time that happens, a CDC subscription's
`CdcDeliveryTracker` reports `OPENED_GAP` for the higher event, and `CdcEventStore.DeliveryGate.recordOpenedGap` used
to write the gap at once. It did so in a `UnitOfWork` of its own (`reconcileGapsAndReport`, plus `addTransientGaps` for
the lower end of a wide gap). The gap then filled a few milliseconds later, and the fill's `DELETE` ran in the
subscriber's unit of work when it acknowledged the event. The result was one extra commit per out-of-order event per
subscriber, on a pooled connection, before the event could be handed on.

The trading demo measured it (10 business days, ~71k orders, ~780k events, CDC on):

- `transient_subscriber_gaps` saw ~550k inserts and ~550k deletes.
- The reconcile CTE (`WITH candidates AS ... permanent_gaps ...`) ran 407k times.
- The machine is bound by WAL flush count, at ~17 fsyncs per order. `synchronous_commit=off` cut burst latency by a
  third, and WAL-volume tuning did nothing, so every extra commit counts.
- Thread dumps taken during bursts showed, per dump, ~2.6 threads waiting for a pool connection inside
  `recordOpenedGap` and 3.3 threads waiting on PostgreSQL inside it.

The demo only provides the evidence. Any CDC deployment with concurrent appenders pays the same cost.

## Why the row exists

A transient gap row exists for one reason: a subscription that restarts from a **saved** resume point must still wait
for an event that commits late below that point. The tracker seeds itself from `getTransientGapsFor` when it
subscribes. Resume points are not saved per event, though. They are saved:

- periodically, by `DefaultEventStoreSubscriptionManager.saveResumePointsForAllSubscribers` (every
  `snapshotResumePointsEvery`) and `saveResumePointsThatAdvancedPastThreshold`;
- on stop, fenced-lock release and shutdown (`AbstractEventStoreSubscription.persistResumePointUntilSettled`);
- on reset (`overrideResumePoint` in all four subscription types, and `resetStoredResumePoint`).

Until a resume point past a gap has been saved, a crash resumes **below** the gap and reads its event again anyway.

**Invariant:** no resume point is persisted past an open gap that is not durably recorded.

## Design

1. **`DeferredGapRecording`** is a new role interface on `EventStore`, implemented by `CdcEventStore`. It works the same
   way as `AwaitedGapInspector`: a decorator that does not forward it turns the deferral off, and nothing else changes.
   - `deferGapRecordingUntilResumePointSave(subscriber, aggregateType)` opts a subscriber in.
   - `beforeResumePointSave(subscriber, aggregateType)` records the gaps held in memory and returns a `ResumePointSave`.
     The caller closes it once the save is done.
2. **The manager wraps its `DurableSubscriptionRepository`** in `GapRecordingBeforeSaveDurableSubscriptionRepository`
   when its event store is a `DeferredGapRecording`.
   - Reading or creating a resume point through the wrapper opts that subscriber in.
   - `saveResumePoints` calls `beforeResumePointSave` for each changed resume point **before** the delegate reads its
     value, and closes the windows after the save.
   - Whether a resume point is changed is not stable: its subscription can advance it at any moment. So the wrapper
     hands the delegate only the resume points it opened a window for. A resume point found unchanged is left out, and
     if it advances right after the check, a later save takes it. Handing it on would let the delegate write it with no
     window open.
   - If a resume point's gaps cannot be recorded, that resume point is not saved and stays changed. The other resume
     points are saved, and then `saveResumePoints` throws (`GapsNotRecordedException`, the first failure as its cause).
     The periodic save logs it, and the next periodic save tries again. A stop or lock release stops settling the resume
     point at once instead of retrying until its timeout. During shutdown, an IO failure marks the database unreachable.
     A reset (`resetFrom` / `resetStoredResumePoint`) whose gaps could not be written now fails with an exception
     instead of reporting a reset whose resume point was not saved.
   - Every save path listed above goes through this one repository, so it is the single choke point.
3. **The `DeliveryGate` keeps a narrow opened gap in memory** (`unrecordedGaps`) instead of writing it, but only when all
   of these hold:
   - the subscriber opted in;
   - the gap handler is a `PostgresqlEventStreamGapHandler`, because a flush needs `addTransientGaps`, which records a
     gap without the event above it;
   - the gap has no middle awaited in memory only, and is at most 1 000 orders wide;
   - the subscription keeps at most 10 000 orders in memory;
   - no resume point save of the subscriber is in progress.

   If any condition fails, the gate writes the gap at once, as before.
4. **What happens to a gap held in memory:**
   - **It fills.** The fill goes to `unrecordedFillsNotDoneWith`, not to `GapFillsAwaitingAcknowledgement`. When the
     subscriber is done with it (ack, or hand-on for a subscriber that does not acknowledge), it is simply forgotten. It
     costs no insert and no delete.
   - **A save happens first.** The save records every order still held, including fills the subscriber is not done
     with, as transient gaps: one `addTransientGaps` per run of consecutive orders, in one unit of work per gate. It then
     hands the fills to `GapFillsAwaitingAcknowledgement`, so they resolve as before.
5. **Race closure.** `beforeResumePointSave` first increments a per-subscriber "save in progress" counter, then flushes
   each gate under the gate's lock.
   - The gate checks the counter under the same lock when it decides whether to keep a gap.
   - The resume point can only move past a gap once the event that opened it has been handed on, which happens after
     `deliver` returns.
   - So every gap below the resume point that the save reads is either flushed by that save, or was opened while the
     counter was raised and therefore written at once.
   - The flush holds the gate lock across its commit, so an acknowledgement cannot forget a fill whose gap is being
     written at that moment.
   - Lock order is always gate lock, then gap-handler monitor.

The deferral is not configurable. It keeps the invariant on every path, and with no subscriber opted in the behaviour is
the same as before. An opt-out property would only control a cost; it would not change any guarantee.

## Every path

| Path | Coverage |
|---|---|
| Periodic save / threshold save | Wrapper → `beforeResumePointSave` before the delegate snapshots |
| Stop, lock release, shutdown (`persistResumePointUntilSettled`) | Same wrapper. The gate also writes what it holds when the subscription ends (`subscriptionEnded`), so a save after the dispose finds nothing left unrecorded |
| Reset (`overrideResumePoint`, `resetStoredResumePoint`) | Wrapper. A reset's later `resetTransientGapsFor` clears the rows as before |
| `createResumePoint` | Writes the starting point, which is below anything delivered. Opts the subscriber in |
| Processors (`advanceResumeFromAndIncluding`, `awaitProcessed`) | Advance the in-memory resume point only. Persisting it always goes through the repository. `InTransaction*` subscriptions use the event bus, not `pollEvents` |
| Direct `pollEvents` without a manager | Never opted in, so gaps are written at once (unchanged) |
| Give-up (`recordGivenUpGaps`) | Orders still held are dropped from memory. A range held whole is not passed to `giveUpTransientGapRanges`, so it costs no statement. Nothing was saved past such a gap, so a restart reads its orders again |
| `isAwaitedAsGap` | An undelivered held gap is answered by the running tracker (`isAwaited`). A held fill the subscriber is not done with is answered by the running gate (`isAwaitedAsFillOfAnUnrecordedGap`), which waits for the gate lock at most 10 s (`DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT`) per call - one wait shared by all of the subscriber's running gates of the aggregate type, not one per gate - and answers that the event is still awaited when the lock is not free within that wait or the thread is interrupted. That answer is conservative: while a recording that outlasted its wait holds a gate lock, every order of the subscriber and aggregate type is answered as awaited, also one no gap is about, until that recording ends. Both are written when the subscription ends, so the transient rows answer after that |
| Seeding from `getTransientGapsFor` at subscribe | A re-subscribe on a new manager reads the rows written by the last save or by the end of the previous subscription. A `retry()`/`repeat()` re-subscribe starts from the original start order, below everything |
| Wide gaps / lower end (`addTransientGaps`) | Never deferred; written at once |
| Subscription end inside a unit of work or a Spring transaction | The held gaps are written on a virtual thread of their own, rather than in a unit of work the gate does not own. The ending thread waits for it at most 10 s (`DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT`); if the wait runs out it logs a WARN and the subscription ends, and the write goes on and commits when it can; should it fail instead, that failure is logged at WARN too |
| Save inside a unit of work or a Spring transaction | Same as the subscription end: written on a virtual thread of their own. All the subscriber's running subscriptions share one wait of at most 10 s per resume point. If the wait runs out, that resume point and the changed resume points after it in the same save are not saved, without waiting for them, and the save throws after saving the others |
| Bus overflow catch-up, or any other move back onto the bus | The catch-up loads every event within every range the tracker awaits (`CdcDeliveryTracker.awaitedRanges`, see the section below), so a held gap is covered like any other. The first fix only added the held orders (`withUnrecordedGaps`, now removed). It was needed because, without it, a burst with more than 1 000 older awaited orders hid the fill until give-up: trading-demo views applied `OrderAccepted` before `OrderPlaced`, and existing events ended up as permanent gaps. Pinned by `a_gap_kept_in_memory_whose_fill_arrives_while_the_subscription_is_off_the_bus_is_filled_by_the_catch_up` |
| Polling fallback / switches | One gate per subscription whatever the source. The delegate's polls record their own gaps as before |
| Acknowledgement of fills | A fill of a held gap is never registered with `GapFillsAwaitingAcknowledgement`, so nothing is resolved for it. `dropped` does not acknowledge it to the delegate |

## A catch-up re-reads every awaited gap

This fixes a weakness that was there before this change: it was found while fixing the regression above, and it is on
`main` too.

**The problem.** A subscription moving onto the CDC bus first catches up from the event store. It reads past its highest
delivered order, and it must also re-read every gap below that order, because a fill that committed while the
subscription was off the bus exists only in the database. The catch-up asked for:

- the tracker's lowest 1 000 awaited orders (`awaitedGaps(1000)`), and
- the gap-handler query's transient rows: all of them up to 50, otherwise the 20 newest, the 10 lowest and 20 rotating.

A gap that was neither among the lowest 1 000 nor selected by the rotation was missed. Its event then arrived only once
the rotation happened to pick it, after later events of its own aggregate. If the rotation never picked it, the gap was
given up after the gap timeout and recorded as a permanent gap over an event that exists.

**The fix.** `CdcDeliveryTracker.awaitedRanges()` returns everything the tracker answers `isAwaited` for, as closed
ranges:

- the earlier gaps, with consecutive orders joined into one range;
- the earlier middles (`GapMiddlesAwaitedInMemory.awaited()`);
- every gap between the delivered runs above the watermark, including both ends and, for a wide gap, its middle.

`CdcEventStore.loadEventsWithin` loads every event inside those ranges before the first page, all tenants, ascending, in
one unit of work. Both the bus catch-up and the initial back-fill of `BackfillThenLiveOrdered` use it.

**Cost.** It is bounded, never one statement per order:

- A range of up to `MAX_AWAITED_ORDERS_PER_STATEMENT` (1 000) orders is listed order by order, with up to 1 000 orders per
  statement (the existing `IN` list of `loadEventsByGlobalOrder`).
- A wider range, the middle of a wide gap, is read as one indexed range scan. Awaited orders hold no event except a late
  commit, so the scan returns only fills.
- The tracker keeps at most 10 000 gaps, so the number of ranges is bounded too.

In the trading demo's bursts (~1 300 orders awaited) that is 2 statements per catch-up, against 1 before.

**Polling is not affected the same way, and is left as it is.**

- `PollEventStoreTask` re-reads gaps through `TransientGapsQuerySelection` on every poll.
- `PostgresqlEventStreamGapHandler` promotes a transient gap only if the query that asked for it did not return it, and
  `findTransientGapsToIncludeInQuery` always adds the gaps that are due for promotion. So polling never gives up a gap
  without asking for it, and its fill is never lost.
- What remains is latency. With more than 50 outstanding gaps, a fill can arrive several polls late, after later events
  of its own aggregate.
- Asking for every gap on every poll, not once per catch-up, would put an `IN` list of every outstanding gap into every
  poll. That is a separate cost decision, and polling is only CDC's fallback.

## Tests

`CdcEventStoreDeferredGapRecordingIT` uses a trigger to count inserts into `transient_subscriber_gaps`:

- **Gaps that open and fill between saves.** Five gaps open and fill, followed by a stop: zero inserts.
- **Crash after a save.** A periodic save moves the resume point past an open gap, and the gap row exists. A second
  manager then starts from that resume point without the first being stopped, and still receives the late event.
  Mutation-checked: with the flush in `beforeResumePointSave` removed, the late event is never delivered.
- **Stop with an open gap.** The gap row is written, and the event is delivered after the restart.
- **Rolled-back hole.** Nothing is written, and `isAwaitedAsGap` is true while the hole is held.
- **Recorded gap no selection names.** A save records the gap. 3 000 lower orders are awaited and 30 newer gaps are
  recorded above it, so it is neither among the lowest 1 000 nor among the newest 20 rows. The rotation would need 150
  gap-handler queries to reach it, and the test records every gap a query included and asserts that none included this
  one, so it fails deterministically against the old catch-up. Its fill is published while the subscription is off the
  bus, and the catch-up loads it (`a_recorded_gap_that_no_selection_names_is_still_re_read_by_a_catch_up`).
- **Bus overflow.** With 1 100 rolled-back orders awaited below a held gap, the fill is published while the subscription
  is off the bus, and the catch-up loads it. Without the fix the event never arrives. The test needs a bystander bus
  subscriber, because the bus retains events only while it has no subscriber at all.

The existing pins still apply: `CdcEventStoreCommitOrderIT`, `CdcEventStoreGapFillAcknowledgementIT`,
`CdcEventStoreConcurrentWritersIT`, `CdcEventStoreLiveTailHoleIT`, `CdcEventStoreAwaitedGapInspectorIT`, and
`EventProcessorIT` / `ViewEventProcessorIT` (`awaitProcessed` with transient gaps).

## Cost per out-of-order event, per subscriber

| | Before | After |
|---|---|---|
| Fills before the next save | 1 own commit (reconcile CTE + insert) and 1 `DELETE` in the subscriber's UoW | No database write |
| Still open at a save (for example rolled-back holes) | Same as above | The insert moves into one unit of work per gate per save. Give-up and fill resolution as before |

## Risks

- **Pool connections during a flush.** A flush holds the gate lock across its commit. An acknowledging subscriber
  thread, which already holds a connection inside its own unit of work, can block on that lock while the flush waits for
  a connection. This only matters with a pool smaller than the number of subscriber threads blocked behind one save. The
  window is one short unit of work per save.
- **A save inside a caller's unit of work.** The flush never joins it. Joining would let the gate forget the gaps before
  the caller commits, and a rollback would then lose them: the next save, past them, would record nothing. Joining and
  forgetting the gaps only once the caller commits, as `GapFillsAwaitingAcknowledgement` forgets its fills, was
  considered and not taken. The save window closes when the repository is done with the save (`ResumePointSave.close()`
  in the `finally` of `GapRecordingBeforeSaveDurableSubscriptionRepository.saveResumePoints`), before the caller
  commits. From then on the gates keep the gaps that open in memory again, beside the recorded ones whose rows are
  not committed yet, so the commit callback would have to tell the two apart; and a fill of a recorded gap, forgotten
  on its acknowledgement in that window as a gap kept in memory is, would leave the gap's row behind once the caller
  commits. As the manager's save paths are not expected to run inside a unit of work, the bounded waits below are a
  safety net, and were kept rather than that protocol. With a unit of work, or a Spring transaction the unit of work
  factory would join, current on the saving thread (`TransactionProbe.isUnitOfWorkActive`), each gate flushes on a
  virtual thread of its own, in its own unit of work, and the saving thread waits for the flushes at most 10 s
  (`DeliveryGate.DEFERRED_GAPS_RECORDING_WAIT`) per resume point: one wait shared by all of the subscriber's gates
  (`CdcEventStore.beforeResumePointSave`). The flush then needs a second connection while the caller holds one. If the
  caller's unit of work holds a lock the flush needs, the wait runs out instead of hanging, since PostgreSQL cannot see
  that wait as a deadlock. That resume point is then not saved, and neither are the changed resume points after it in
  the same save: the repository does not flush them, as their flushes could be blocked by the same lock and would each
  wait again (`GapRecordingBeforeSaveDurableSubscriptionRepository.saveResumePoints`). It throws after saving the
  others, and the caller logs it. The flush goes on, keeping the gate lock, until the caller's transaction ends; should
  it fail after the wait ran out, the failure is logged at WARN (`CdcEventStore.runOnAVirtualThreadWaitingAtMost`), as
  no caller is waiting for it any more, and the gaps stay kept for the next flush. Until the caller's transaction ends,
  only the flush itself, which runs on a thread with no unit of work, waits for the gate lock without a limit. Every
  other path either does not wait for it or waits at most 10 s, since a thread that waits for this subscription may be
  the one whose unit of work blocks the flush. A second save finds the lock taken and hands its check to a new virtual
  thread, waited for at most 10 s again. The subscription's end marks the gate ended without the lock. An
  acknowledgement waits for the lock at most 10 s and then returns as usual, so it never fails the subscriber's handler
  or reaches its error policy: its events are acknowledged to the delegate's poll, and the flush settles them once it
  ends. A held fill among them is then done with if the flush failed, and otherwise the gap the flush recorded for it is
  resolved in the flush's own unit of work. `isAwaitedAsGap`'s check of a held fill (asked by `awaitProcessed`) waits
  for the lock at most 10 s per call, however many subscriptions the subscriber runs, then answers that the event is
  still awaited. The delivering thread's checks of an opened gap, a fill and a dropped event wait for it at most 10 s
  once per stall, not once per event: after one wait runs out, these checks only try the lock until the flush lets go
  of it or one of them takes it (`DeliveryGate.lockedOnDeliveringThread`). An acknowledgement made on the delivering
  thread (a subscriber without a `SubscriberAcknowledgement`) keeps its own wait per call. A gap an event opens is then kept without
  the lock and taken in by the lock's next holder; recording it at once would wait for the gap handler's monitor, which
  the blocked flush holds too. A fill is treated as the fill of a recorded gap. A dropped event is left to the lock's
  next holder with no unit of work current, which acknowledges it to the delegate's poll unless it is a fill the subscriber is not done with
  (`DeliveryGate.settleDroppedWhileLockBusy`). Gaps the tracker gave up on only try the lock on the delivering thread,
  and otherwise go back to the tracker for the next event; at the subscription's end, which has no next event, they
  wait for the lock at most 10 s (only try it while the delivering thread's wait has run out), and otherwise go back to
  the tracker for a later subscription to wait for again. A gap recorded at once (a wide gap, or one that is not kept during a save,
  after the end or over the limit), and the recording of given-up gaps, still wait for the gap handler's monitor without
  a limit (`DeliveryGate.recordOpenedGap`, `DeliveryGate.recordGivenUpGaps`), and the blocked flush holds that monitor.
- **Non-exclusive subscriptions on several instances** share one resume-point row. Another instance's save can move it
  past a gap this instance holds. That is no weaker than before: the shared row could already move past events this
  instance had not handled.
- **Custom gap handlers and custom event-store decorators** keep the previous immediate writes.
