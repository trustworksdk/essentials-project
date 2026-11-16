# Event store redesign, phase 6: engine design

**Status:** decided 2026-11-16 (§9); being built on `feature/event-store-engine-phase6`. When phase 6 is done, this file moves to `docs/archive/` or is folded
into the redesign's record (`docs/plans/README.md`).
**Scope:** phase 6 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): wake-up tiers 1
(cross-JVM, `NOTIFY` after commit, coalesced) and 2 (same JVM, commit callback) of §11 with its P0.10 revision, and the
one-flush read of every woken aggregate type that P0.16 decided and phase-3 §2.1 deferred to here.
**Gate:** I7 passes (§16: with no running writers, a committed event is delivered within the backstop interval with
every notification lost — listener killed, `NOTIFY` disabled); commit-to-handler latency compared with S1 and CDC
(§7 below). Every phase-2 to phase-5 IT keeps passing, with every `must_fail_*` test.

Section references (§n) are to the plan; "phase-3 §n" etc. are `docs/plans/event-store-engine-phase3.md` and its
siblings; P0.n are entries of the phase-0 record (`docs/archive/event-store-redesign-phase0.md`). Where this draft
proposes something the plan does not say, it is marked **Proposed**. The existing `PostgresqlEventStore`, its
`EventStorePollingOptimizer` SPI, S1's trigger and listener and the CDC path are not changed, and nothing below copies
them: no per-row trigger, no epoch counter consulted by a sleeping poll, no polling optimizer. The public API stays
phase 8's (§12.3); phase 6 adds one temporary public method (§2.2) and two settings.

**What "S1" and "CDC" are.** S1 is today's notify-polling (`docs/plans/subscription-improvements.md` §S1): a per-row
`AFTER INSERT` trigger on each event table (`ListenNotify.addChangeNotificationTriggerToTable`), one shared listener
that bumps a per-aggregate-type epoch, and `NotifyAwareEventStorePollingOptimizer`, which returns a zero delay on the
next poll when the epoch moved and otherwise ramps to `max-delay` (default 1 s). It does not interrupt a sleep, so its
latency is bounded by the current backoff, not by the notification (its own "Phase 2", never built, is §11's S2). CDC is
the logical-decoding path in `components/postgresql-event-store` (`CdcEventStore`, the CDC bus, `BackfillThenLiveOrdered`:
a polling backfill, then live delivery from the bus, with a polling fallback). S1's own measurements (perf lab, 2
subscribers, 1 ev/s) put p95 at 779 ms (max-delay 1 s) and 222 ms (200 ms), CDC inbox at 332 ms and CDC direct at
319 ms; those figures came from a different harness and are context only. Phase 0 measured neither S1 nor CDC in the
§17 steady shape (P0.0 ran with notify-polling and CDC off), so §7 measures both, with the same harness as the new engine.

**What phase 0 measured for this phase.** P0.10 (the cost of `NOTIFY` on the append path), P0.16 (the one-flush read)
and P0.17 (a) (the listener's drain). Their numbers are quoted where they decide something. Everything else below is a
starting value for §7 to confirm or move.

---

## 1. Types and what changes from phase 5

| Type | Phase 6 | One line |
|---|---|---|
| `EventLog` | One public method, two internal parts | `afterCommit(...)`: the tier-2 callback and the tier-1 hand-off (§2.2); owns one `WakeNotifier` and one `WakeListener` |
| `WakeNotifier` | **New, package-private** | Coalesces committed aggregate types and sends them as one `pg_notify … FROM unnest(?)` on a borrowed connection (§3.2) |
| `WakeListener` | **New, package-private** | One held `LISTEN` connection; drains notifications with P0.17's `PINNED_PEEK`, wakes the reader loop's types, never reads events (§3.3) |
| `ReaderLoop` | Wake flags and the flush | A per-type wake flag replaces the `Wake` request; due types are read in one pgjdbc flush with one shared running-set read (§4) |
| `TailReader` | Split read | `poll()` becomes "bind my statement" and "apply my result set"; a new tail-with-re-read shape without the running set (§4.2) |
| `EventLogStorage.TailStatements` | One shape added | `rereadTail` (tail ∪ hole re-read ∪ range re-read, no running-set branch); joins `EventLogGenericPlanIT` |
| `EventLogSettings` | Two fields | `crossJvmWakeUp` (boolean, default `true`), `wokenProbeMin` (default 10 ms, §5) |
| `EventLogObserver` | Extended | `onWake(type, source)`, `onNotifySent(types, nanos)`, `onNotification(types, ownInstance)`, `onListenerRestart(cause)`, `onFlush(types, statements, nanos)` |
| `TestSeams`, `WatermarkMutant` | Extended | Hooks and the must-fail mutants of §6 |

`Subscription.wakeUp()` (public since phase 2) keeps working: it sets its type's wake flag.

---

## 2. Tier 2: the commit callback

### 2.1 Why the engine needs to be told

Appends run on the caller's `Connection` and never commit (phase-2 §3.1, module `CLAUDE.md`), so `EventLog.append`
returns before the commit, and phase-3 §2.5 rejected a wake at that point: the read would usually run before the row is
visible, cost a statement and then park a full backstop anyway. The engine therefore has to be told *after* the commit.
How the caller learns of its commit depends on how it runs transactions:

- **Foundation's `UnitOfWork`** (`components/foundation/.../transaction`): `UnitOfWork.registerLifecycleCallbackForResource(resource, UnitOfWorkLifecycleCallback)`,
  whose `afterCommit(unitOfWork, resources)` runs after the commit with every resource registered for that callback.
  Both the JDBI unit of work (`GenericHandleAwareUnitOfWorkFactory`) and the Spring-managed one
  (`SpringTransactionAwareUnitOfWork`, through Spring's transaction synchronization) call it. This is how phase 8's
  `EventStore` adapter will append (§19 item 19), and it is the same mechanism `PersistedEventsCommitLifecycleCallback`
  in today's engine is built on; the new engine uses foundation's interface directly and nothing from
  `postgresql-event-store` (`DependencyRulesTest` forbids it).
- **A caller that commits its own `Connection`** (the module's ITs, the spikes, an application with no unit of work):
  it calls the engine after `commit()` returns.
- **Engine-owned transactions** (a later convenience append that opens and commits its own transaction as one
  `BEGIN … COMMIT` flush, P0.14): the engine calls itself. Not built in phase 6.

### 2.2 Shape (**Proposed**)

```java
// EventLog, public, temporary (phase 8's API and the EventStore adapter absorb it)
public void afterCommit(Collection<String> aggregateTypes)           // the types the committed transaction appended to
public void afterCommit(List<AppendRequest> committedRequests)       // convenience: the requests that were appended
```

- **What it does.** For each aggregate type: (tier 2) if the type is active in this `EventLog`'s reader loop, set its
  wake flag and unpark the loop (§4.1); (tier 1) add the type to the notifier's pending set and unpark the notifier
  (§3.2). Both are a few hundred nanoseconds on the caller's thread: no statement, no lock held across I/O, no
  allocation beyond the set entry. It never throws for an unknown or inactive type.
- **Only after commit.** It is a hint, never a statement of fact. Calling it before the commit, or for a transaction
  that then rolls back, costs one read that finds nothing (or finds a hole) and nothing else: no state moves on a wake
  (§4.3, and the test of §6.2). Not calling it costs latency up to the backstop interval, never an event (I7).
- **Rollback.** Nothing is called. A rolled-back writer's hole retires on the next probe of the reader that saw it
  (§11's rule, unchanged).
- **Foundation glue.** A package-private `EventLogCommitCallback implements UnitOfWorkLifecycleCallback<String>` (the
  resource is the aggregate type name) is shipped beside it, so phase 8's adapter registers the type once per append
  and gets `afterCommit` for free; `beforeCommit` returns `COMPLETED`, rollbacks do nothing. Phase 6 tests it against
  the JDBI unit of work only; the Spring path is phase 8's.
- **Why not hand events over.** §11: "Tier 2 wakes; it does not hand events over". The queue's local hand-off worked
  only half the time (history §9.4); a woken read costs one statement and keeps a single delivery path.
- **Why not `append` returning a "commit token".** It would still need the caller to call back after commit, with a
  wider API than a type name. Rejected.

---

## 3. Tier 1: `NOTIFY` after commit, coalesced, and the listener

### 3.1 Channel and payload (**Proposed**)

- **Channel** `el_wake`, a constant: notifications are per database already, and one channel keeps the listener to one
  `LISTEN`. The engine's tables are prefixed `el_`, so the name follows them.
- **Payload** `<instance>:<aggregateType>`, where `<instance>` is a random 8-character id an `EventLog` draws when it
  starts. The type name is the registered one (validated by `AggregateTypeTables`, well under `NOTIFY`'s 8 000-byte
  limit). The instance prefix lets a listener drop its own `EventLog`'s notifications, which tier 2 already acted on
  (§3.3); without it every local commit would wake the reader twice. Payload-light as §11 requires: no orders, no heads.
- **Not a per-row trigger** (§11, §15): the application-side notifier sends one notification per type per coalesced
  send, never one per event.

### 3.2 The notifier (P0.10's decided shape)

- One `WakeNotifier` per `EventLog`, one **virtual** thread, started lazily on the first `afterCommit` with
  `crossJvmWakeUp` on, parked when idle.
- **Coalescing.** `afterCommit` adds the type to a concurrent set. The thread takes the whole set and sends it as one
  autocommit statement `SELECT pg_notify('el_wake', p) FROM unnest(?::text[]) p`; what accumulates while that send is
  in flight goes out in the next one. Duplicate types merge in the set; PostgreSQL also folds identical notifications
  within one transaction. The notifier's session is the only notifying one per `EventLog`, so it never queues on the
  cluster-wide `NOTIFY` lock behind its own writers (P0.10: coalesced after commit matched no-notification throughput
  on the VM and cost about 10 % at 8 writers on the dedicated server; per-writer notifies after commit cost 32-38 %; in
  the transaction 75-85 %).
- **Connection: borrowed, not held.** Each send borrows a connection from the engine's `DataSource` and returns it
  (Hikari's borrow costs microseconds; a held backend would add to every probe's `pg_stat_get_activity` scan, P0.12).
  Under sustained writes that is one borrow per send, and sends run back to back; a send takes no catch-up permit
  (phase 5 §4.1 covers event-table reads only).
- **Failure.** A failed send is logged at debug, counted (`onNotifySent` with an error), and dropped: other JVMs find the
  events through their backstop (§11's P0.10 revision). It is not retried, so a database outage does not build a queue.
  A crash between commit and send loses the hint the same way.
- **`NOTIFY` inside the appending transaction is not offered.** §11's revision noted that a caller committing its own
  connection "would need an explicit after-commit call"; `afterCommit` is that call. An in-transaction option would
  reintroduce the serialising lock for no gain over calling `afterCommit`.

### 3.3 The listener

- One `WakeListener` per `EventLog`, **only while the reader loop runs** (some subscription exists) and
  `crossJvmWakeUp` is on: a JVM that only appends holds no listener connection. It starts with the loop's first active
  type and stops with its last.
- **One held connection** from the engine's `DataSource`, autocommit, `LISTEN el_wake`; on stop `UNLISTEN *` before the
  connection goes back to the pool, so a pooled session never carries a stale `LISTEN`.
- **One platform daemon thread** `eventlog-listener`, blocking in pgjdbc's `getNotifications(timeout)` with P0.17 (a)'s
  `PINNED_PEEK` drain (pin pgjdbc's peek cool-down by reflection), falling back to `QUERY_POLL` (an empty query every
  `timeout`) if the reflection fails, as P0.17 (a) decided. Platform, like the reader loop: one long-lived,
  latency-sensitive thread. The stock drain is not used: under continuous traffic pgjdbc's `processNotifies` does not
  return (P0.10: 0.3-2.0 s p50 on the VM, up to 1.4 s at 3 000/s in P0.17).
- **It does no work** (§11): for each notification it parses the payload, drops its own instance's, and sets the wake
  flag of the type if the type is active in its loop (§4.1). It never reads events, never touches reader state.
- **Restart.** Any `SQLException`, an invalid connection or a drain that throws closes the connection, wakes every
  active type once (a notification may have been missed while it was down, and that read is cheap), waits
  `min(backstopInterval, 1 s)` and reconnects, re-running `LISTEN`. Re-running `LISTEN` is the step a reconnect forgets
  (§11: "reconnects *and* re-runs `LISTEN`"); §6.3 tests it by counting notifications, not deliveries.
- **Liveness without traffic.** The `timeout` is 1 s; a `QUERY_POLL` fallback's empty query also detects a dead
  socket. A half-open TCP connection that neither errors nor delivers is covered by I7: the backstop delivers anyway.

### 3.4 Resource cost, exactly

| | Threads | Held connections | Transient connections |
|---|---|---|---|
| Per `EventLog`, phase 5 | 1 platform (reader loop, while any subscription exists) | 1 (reader loop) | per catch-up/bridge statement, progress save |
| **Added by phase 6, per `EventLog`** | **+1 platform** (`eventlog-listener`, only while the reader loop runs and `crossJvmWakeUp` is on); **+1 virtual** (`eventlog-notifier`, from the first `afterCommit`, parked when idle) | **+1** (the listener's, same lifetime as its thread) | **+1 per coalesced notify send**, borrowed and returned at once |
| **Added by phase 6, per subscription** | **0** | **0** | **0** |
| Tier 2 | 0 (runs on the committing thread) | 0 | 0 |

With `crossJvmWakeUp = false` phase 6 adds nothing but the notifier's absence: no thread, no connection. A JVM that
appends but subscribes to nothing adds one virtual thread and borrows one connection per send. The pool sizing rule of
phase-3 §4.2 becomes "2 held plus transient": 3 is correct, 4-5 comfortable.

---

## 4. How wake-ups feed the reader loop

### 4.1 Per-type wake flags, coalesced

Phase 3's `Wake(type)` request is replaced by a flag on `ActiveType` (`AtomicBoolean woken`) plus the loop's existing
unpark. A thousand `afterCommit`s per second for one type set one flag a thousand times and cause at most one read per
loop iteration, without growing the request queue (which stays for attach, detach, hand-off and type changes, where
every request matters). At step 2 of the iteration (phase-3 §2.2) the loop takes each flag with `getAndSet(false)`
**before** it reads, so a wake arriving during the read sets it again and causes another read: the flag is never
cleared after a read (mutant `WAKE_CLEARED_AFTER_READ`, §6.4). A woken type is due now, subject to §5 only for the
running set.

### 4.2 One flush for every due type (P0.16)

Phase 3 reads due types one after another. With tier 1, one notification batch commonly wakes several types at once,
which is the woken set P0.16 measured (10 types in one flush: −58 % server CPU per wake, −66 % at 50; latency slightly
higher, 834 against 730 µs at 10 types).

**Proposed:**

1. Each due type's `TailReader` chooses its statement as before, except that the running-set branch is never part of
   it: `plainTail` (no holes, no candidates, last read contiguous) or the new `rereadTail` (tail ∪ `= ANY(holes)` ∪ the
   range join, `UNION ALL` as P0.11 decided). The reader also says whether it wants a running set (a candidate is
   outstanding or the last read revealed a hole without one).
2. The loop sends every due type's statement, followed — if any reader wants it and §5 allows it — by one
   `SELECT RUNNING_SET`, in **one pgjdbc flush** (one multi-statement `PreparedStatement`, `getMoreResults()` per type,
   one Sync). Each `TailReader` applies its own result set (cursor, holes, rows) and then, if a running set was read,
   retirement and candidate creation with that set.
3. The **hole read** of a retired candidate (phase 2 F2) stays a separate statement after the flush, inside the same
   iteration and before publishing, as now. It is rare (once per retirement) and needs the retirement first.

**Why one running set at the end is correct, and why it must be one.** I11 requires a candidate's running set to be
read after the snapshot of the read that created or re-probed it. Every statement in the flush takes its snapshot when
it starts, and the running-set statement starts after all of them, so its set is valid for every type's candidate. It
must be exactly one, and last, because statements sent before one Sync run in one implicit transaction, and PostgreSQL
caches backend status per transaction (P0.2): a second running-set read in the same flush would return the first one's
cached set, read *before* the second type's snapshot. Mutants `RUNNING_SET_FIRST_IN_FLUSH` and
`RUNNING_SET_PER_TYPE_IN_FLUSH` (§6.2). The flush also reads the cluster's backends once per wake instead of once per
type, which is P0.12's cost dimension.

- **Statement text and plans.** The flush text depends on the mix (types × two shapes × with or without the trailing
  running set). Each type's statements stay server-side prepared under `force_generic_plan` as P0.16 decided; pgjdbc
  caches a multi-statement query per distinct text, so the number of cached texts is bounded by the mixes that occur.
  **Proposed:** order the types by activation order (stable), and fall back to one statement per type when more than
  `16` types are due at once (a cache-churn guard; §7 records how many distinct texts a run produces).
  `EventLogGenericPlanIT` adds `rereadTail` and checks that a flush runs each statement on its index.
- **One failed statement fails the flush.** An error in statement *k* aborts the implicit transaction and the rest, so
  the loop treats any flush error as a read failure: phase-3 §2.4's reader restart, unchanged.
- **A single due type** with no running-set wish sends one statement as today; with a wish, a two-statement flush
  replaces today's combined probe. The combined-probe shape (`probe`, `probeWithRanges`) stays for the backstop path
  of a single type only if §7 shows the two-statement flush costs more server CPU; otherwise it is retired with its
  ITs moved to the flush (open question 2).

### 4.3 Nothing moves on a wake

A wake changes one thing: a due time. It never advances a cursor, never resolves a hole, never retires a candidate and
never raises a watermark; all of that happens only from rows and running sets the reader reads afterwards. That is why
a wake that fires before the commit (a misplaced `afterCommit`, or a notification from another JVM that raced its own
commit's visibility) cannot lose anything: the read finds no row, or a hole; the row is read by the next read above the
cursor or by a hole re-read; the hole cannot retire while its writer runs (I11). The cost is one read and, at worst, a
backstop of latency for that event.

---

## 5. Interaction with the probe back-off and candidates

Phase 2 floors the re-probe wait at the backstop because the backstop was the only wake-up (P0.12 decision: "re-probe
only after a wake-up, backing off with the probe's measured cost"). With tiers 1 and 2 wakes arrive at the commit rate,
and a wake under write load would otherwise mean a running-set read per wake: exactly P0.12's 40-probers shape, which
halved appends at 400 connections.

**Proposed:** the type keeps two clocks.

- **Read clock.** A wake makes the tail due now. The tail read always re-reads outstanding holes (`rereadTail`), so
  `FRESH` gets late commits on the next woken read whatever the probe does: its latency does not depend on the probe
  rate (§9 of phase 4 found the four-writer p99 tail was the probe back-off; it no longer is for `FRESH`).
- **Probe clock.** The running set is read only when `now ≥ lastProbe + wait`, where `wait` is:
  - after a wake: `clamp(cost × 100 × max(1, backends / 10), wokenProbeMin, probeBackoffMax)`, `wokenProbeMin` 10 ms by
    default — the phase-2 §6.3 cost rule without the backstop floor;
  - without a wake: `probeWait` unchanged (floored at the backstop).
  A new hole without a candidate (phase 2's "read again now") also waits for the probe clock; its rows are delivered
  meanwhile, and only the watermark waits.
- **Effect.** The watermark — `CONTIGUOUS` delivery, `FRESH` resume points, catch-up ceilings, attach — moves within one
  woken probe wait of the commit that retires a candidate (10 ms at small clusters; about 1 s at 400 connections on
  P0.12's server cost), instead of within a backstop. The cluster's probe budget (about 1 % of one core, phase-2 §6.3) is
  kept, because the cost rule still applies to every probe; only the backstop floor goes, and only after a wake.
- **Rolled-back writers** send no notification (§11), so their holes retire on the next probe the probe clock allows
  after any later wake, or at the backstop.
- **Catch-up and attach.** Subscriptions waiting on the watermark (`awaitChange`) are woken by the version bump the
  read publishes, as now. Phase 5's permits are untouched: wakes affect the reader loop only.
- **Gap hand-off (phase 5 §6.3)** is unaffected: handed gaps are holes of the initial candidate and are re-read by
  every woken read.

---

## 6. Test plan

Every new rule has an IT seen to fail against a mutant (§16, history §9.13). New mutants go into `WatermarkMutant`,
settable only through `TestSeams`. New hooks on `TestSeams.Hooks`: `beforeFlush(types, wantsRunningSet)` and
`afterFlushBeforeApply(types)` (loop thread), `onNotification(payload)` (listener thread), `beforeNotifySend(types)`
(notifier thread). "Another JVM" in an IT is a second `EventLog` on its own `DataSource` against the same container.
Unit tests: the notifier's coalescing (set semantics, sends while one is in flight), payload parsing and own-instance
filtering, the flag discipline (§4.1), the two-clock wait (§5), the flush builder (order, fallback above 16 types).

### 6.1 Gate row I7

| Shape | IT | Must-fail variants |
|---|---|---|
| Every notification lost, no running writers | `EventLogProgressIT`: backstop 500 ms; subscriptions in both modes on JVM B; JVM A appends one event every 2 s for 30 s with `afterCommit` **not** called (tier 2 off for A's commits), JVM B's listener disabled by `TestSeams` and its backend terminated every 3 s, and A's notifier disabled. Asserts every event delivered once, and each within `backstop + 250 ms` of its commit (measured from the commit's return) | `PARK_UNTIL_WOKEN` (with tier 1 on, the loop parks without a deadline when no candidate is outstanding, trusting notifications: events wait forever); `BACKSTOP_REARMED_ON_LISTENER_RESTART` (each listener restart pushes every type's next backstop read a full interval out; the IT's second arm restarts the listener every 400 ms against a 500 ms backstop, so nothing is read until the restarts stop) |
| Same, `NOTIFY` lost on the way | Same IT, second arm: JVM A's notifier runs, but `TestSeams` points it at channel `el_wake_lost`, which nobody listens on, so every send succeeds and nothing arrives | `PARK_UNTIL_WOKEN` |
| Tier 2 lost in the same JVM | Same IT, third arm: one `EventLog`, `afterCommit` never called, listener off | `PARK_UNTIL_WOKEN` |

### 6.2 A wake that reads before the commit loses nothing; I11 in the flush

| Shape | IT | Must-fail variants |
|---|---|---|
| Woken read while a writer holds a value | `EventLogWakeBeforeCommitIT`: types `T1`, `T2` active; a writer on `T1` takes `g0` and holds before commit (phase 2's `BEFORE INSERT` hold); `afterCommit(T1, T2)` is called **before** the commit, so the flush reads both types and the running set while `g0` is held; meanwhile `T2` has committed rows above `g0`'s type-local position. Then `g0` commits. Asserts `g0` delivered once to `FRESH` and `CONTIGUOUS` subscriptions of `T1`; `T1`'s candidate holding `g0` never retires before the commit; the `T2` read in the same flush is unaffected | `RUNNING_SET_FIRST_IN_FLUSH` (the running set is read at the start of the flush, before `T1`'s snapshot: a candidate retires against a set without `g0`'s writer, the hole read misses `g0`, lost); `RUNNING_SET_PER_TYPE_IN_FLUSH` (each type gets its own running-set statement in the flush: the second returns the first's cached set, `T2`'s candidate retires early; shaped with the holder on `T2`); `WAKE_ADVANCES_WATERMARK` (a woken read that finds no row treats the woken type's cursor as final) |
| Rollback after a wake | Same IT: the writer rolls back instead; the hole retires on a later probe, nothing is delivered for it, nothing else is held up beyond one probe wait | — |

### 6.3 Listener lifecycle

| Rule | IT | Must-fail variant |
|---|---|---|
| Reconnect re-runs `LISTEN` | `EventLogWakeListenerIT`: backstop 30 s (so only tier 1 can be fast); terminate the listener's backend (`pg_terminate_backend` on its PID, published for tests); JVM A then appends 10 events with `afterCommit`. Asserts the observer's notification count rises by the sends' count and each event reaches JVM B within 200 ms | `RECONNECT_WITHOUT_LISTEN` (the count stays flat; deliveries wait for the 30 s backstop) |
| Restart wakes every active type once | Same IT: a commit lands while the listener is down; asserted delivered within 200 ms of the reconnect | `NO_WAKE_ON_LISTENER_RESTART` |
| Drain under sustained traffic | Same IT: JVM A's notifier sends 2 000 notifications/s for 10 s (P0.17's burst shape); a tagged commit every 100 ms must reach B's handler with p99 under 20 ms | `STOCK_DRAIN` (pgjdbc's default `getNotifications`: the tagged commits wait behind continuous traffic) |
| Own notifications dropped | One `EventLog`, tiers 1 and 2 on, 100 ev/s: reads per commit stay at or below one (observer) | `NO_OWN_INSTANCE_FILTER` (reads per commit about two) |
| Listener only while subscribed | An `EventLog` with no subscriptions holds no listener connection (`pg_stat_activity` by application name); the first subscription starts it, the last stops it and `UNLISTEN *` ran | — |

### 6.4 Wake discipline

| Rule | IT | Must-fail variant |
|---|---|---|
| A wake during a read causes another read | `EventLogWakeIT`: backstop 30 s; the `afterFlushBeforeApply` hook holds the loop mid-read while a commit and its `afterCommit` arrive; asserts the commit is delivered within 200 ms of the hook's release | `WAKE_CLEARED_AFTER_READ` |
| Wakes do not probe at the commit rate | Same IT: 8 writers at 1 000 ev/s with out-of-order commits for 10 s; running-set reads ≤ `10 s / wokenProbeMin` + backstop reads (observer, and `pg_stat_statements` for the reader role) | `PROBE_ON_EVERY_WAKE` (running-set reads track the wake rate) |
| `FRESH` late commits do not wait for the probe | Same IT: a late commit into a hole reaches a `FRESH` subscription on the next woken read, before the next probe | — (observer ordering; covered by phase 3's F1 ITs with `rereadTail`) |

### 6.5 Phase 2-5 ITs

Unchanged, with the loop now reading through the flush. Every `must_fail_*` test keeps its mutant; the phase-2 I11 IT
runs once against a single type and once with three types due together. `EventLogGenericPlanIT` adds `rereadTail`.
Phase 2-5 ITs set `crossJvmWakeUp = false` where they assert statement counts of the backstop path, so their numbers stay
comparable; every other IT runs with both tiers on. Against PostgreSQL 16, 17 and 18.

---

## 7. Gate measurement

`spike/EventLogGateSpikeIT`, suite `p6-gate`; today's engine in `CurrentEngineBaselineSpikeIT`, suite `p6-gate-old`
(S1 and CDC arms; CDC needs the module's CDC image with `wal_level=logical`, built by its docker-maven-plugin). Same
harness as phase-5 §8: `pg_stat_statements` by role, writer and reader roles separate, a fresh container per arm, two
runs of every arm, every `getConnection()` timed. "Two JVMs" means two engine instances on separate `DataSource`s and
roles in one test JVM; one row is repeated with two forked JVMs to check that shortcut. Each gate is decided here, before
the run; a result inside the noise counts as no gain (§17).

### 7.1 Latency (§17 "Steady")

**Shape:** 1, 10 and 100 ev/s, one writer appending one event per unit of work to a new aggregate, 10 subscriptions
(5 `FRESH` periodic, 5 `CONTIGUOUS` in-transaction), 10 s warm-up, 60 s window. Commit-to-handler latency from the
writer's commit returning to the handler's receipt. Two placements: writer and subscriptions in the same engine
(tier 2), and in different engines (tier 1).

**Arms:**

| Arm | What runs |
|---|---|
| Backstop only | Phase 5 behaviour: `crossJvmWakeUp = false`, `afterCommit` not called; backstop 1 s and 100 ms |
| Tier 2 | Same engine, `afterCommit` called, listener off |
| Tier 1 | Different engines, notifier and listener on, backstop 1 s |
| Tiers 1 + 2 | Both placements at once (each subscription measured against the writer it shares, or not) |
| S1 (baseline) | Today's engine, starter wiring as P0.0 plus notify-polling on, `max-delay` 1 s (default) and 200 ms |
| CDC (baseline) | Today's engine with CDC enabled, starter wiring, bus delivery live after backfill |

**Pass:** at every rate, tier 1's p50 and p99 are below S1's at both `max-delay` values and at or below CDC's, and
tier 2's are at or below tier 1's; tier 1's reader statements per delivered event are at or below backstop-only at
100 ev/s and within 2× at 1 ev/s (a woken read replaces a backstop read, but an idle tail at 1 ev/s reads only once a
second either way); `CONTIGUOUS` stays within one woken probe wait of `FRESH`. **Recorded:** p50/p99/max per arm and
mode, statements per delivered event (reader role, and writer role for S1's trigger cost), WAL per event, wake latency
from send to listener (on the wire with P0.10's tap and through the API), running-set reads per second, distinct flush
texts.

**Expected**, not gated: tier 2 about one read time (sub-millisecond to 1 ms), tier 1 one read plus P0.17's
`PINNED_PEEK` wake (p50 about 0.3-0.6 ms on the server), plus the notifier's coalescing delay (at most one send time).

### 7.2 Append-path cost of `NOTIFY`

P0.10's shape through the new engine: 1, 8 and 20 writers, one event per unit of work, new aggregates, 8 s windows, with
`fsync=on` on the dedicated server and the VM. Arms: no `afterCommit`; `afterCommit` with tier 1 on (coalesced
notifier); and, as a reference only, a writer that calls `pg_notify` on its own connection after commit (P0.10's
`AFTER_COMMIT_DIRECT`). **Pass:** appends/s with the notifier within 10 % of none at 8 and 20 writers (P0.10's measured
cost of the same shape), commit p50 within the noise, and writers never in `Lock:object` for the `NOTIFY` lock in
`pg_stat_activity` samples. **Recorded:** notifies sent per commit (the coalescing ratio), the notifier's connection
borrows per second, its send p99.

### 7.3 One flush across woken types (P0.16 in the engine)

10 and 50 aggregate types, each with one subscription, one writer per type committing in lockstep with `afterCommit`
so types are woken together; arms: flush on, flush off (one statement per type). **Pass:** server CPU per wake at 10
types at least 30 % lower with the flush (P0.16's gate; it measured −58 %). **Recorded:** wake-to-handler latency
(P0.16 found the flush slightly slower), running-set reads per wake (expected one against one per type).

### 7.4 I7 and backstop as a number

The `EventLogProgressIT` shape at scale: 10 subscriptions, backstop 1 s, every notification lost; latency p99 must be at
or below 1 s plus one read; recorded beside 7.1's tier-1 arm to show what the tiers buy.

---

## 8. Against the invariants

- **I1, I13.** Wakes move only due times (§4.3). The tail read is phase 2's, with the running set moved after it in
  the flush; no state is derived from a notification.
- **I2.** Unchanged: `rereadTail` keeps F1's hole re-read ahead of new rows in the same statement.
- **I3.** Unchanged: watermarks come from reads and running sets only.
- **I7.** The loop always parks with a deadline no later than the backstop (§6.1); tiers only shorten it.
- **I9.** The listener and notifier never wait on a subscription; a flag set is non-blocking.
- **I11.** One running set per flush, read last (§4.2), tested in §6.2.
- **I15.** Unchanged: the reader loop takes no permit; the notifier reads no event table.

---

## 9. Decisions (2026-11-16)

The draft's open questions, settled by the maintainer working through the phases autonomously. The sections above follow
them; question 2 is settled more conservatively than the draft proposed.

| # | Question | Decision |
|---|---|---|
| 1 | Scope of the notifier and listener | **One of each per `EventLog`.** At most one extra held connection and platform thread when two `EventLog`s share a database in one JVM; revisit if phase 8's starter makes that common |
| 2 | Keep the combined-probe statement beside the flush | **Keep the combined probe for a single due type in phase 6**; the multi-statement flush is used only when two or more types are due. The combined probe is the shape I11 was proven on (P0.2) and costs nothing to keep; §7.3 measures the single-type flush, and retiring the combined probe is decided from that figure in a later phase, not during this build |
| 3 | `afterCommit` on the public surface before phase 8 | **Public and temporary**, as `subscribe`'s overloads; phase 8 removes or keeps it with the rest of the temporary surface |
| 4 | `wokenProbeMin` default | **10 ms**, confirmed or changed by §7's `CONTIGUOUS` row and the probe count |

---

## 10. Results (2026-11-16)

Measured by `spike/EventLogGateSpikeIT` (`scripts/run-phase0-spikes.sh --suite p6-gate`: `wakeLatency`, `notifyAppendCost`,
`flushAcrossTypes`) and, for today's engine, `CurrentEngineBaselineSpikeIT` (`--suite p6-gate-old`: `p6Latency`) on the
development VM: PostgreSQL 18.4 in a container with `fsync=off` (§7.2 also with `fsync=on`), 8 CPUs shared by client and
server, a fresh container per arm with a database name of its own (phase 5's permit-key lesson), two runs of every arm.
Statements are `pg_stat_statements` calls by role, utility statements included; the new engine's subscriptions run on
the reader role, its writer (and, with two engines, the writer's engine) on the writer role. CDC ran on the module's
`essentials-test/postgres-wal2json:1` image (PostgreSQL 17.5, `wal_level=logical`, `fsync=off`). Results files
`components/postgresql-event-log/target/spike-results/p6-c-latency.txt`, `p6-c-notify.txt`, `p6-c-flush.txt` and
`components/postgresql-event-store/target/spike-results/p6-old-latency.txt`. As in phases 2-5, absolute figures need the
dedicated server; statement counts and the ratios inside one arm do not depend on it. Nothing was scaled down.

Harness notes. "Two JVMs" is two `EventLog`s on separate pools and roles in the test JVM (engine W on the writer role
appends and calls `afterCommit`; engine S on the reader role holds the subscriptions); arm `TIER1_FORKED` runs W in a
forked JVM (`spike/SpikeWriterProcess`, `System.nanoTime()` is `CLOCK_MONOTONIC` and shared across processes on Linux)
to check that shortcut. Commit-to-handler latency runs from the writer's `commit()` returning; for today's engine from its
unit of work returning (a delivery that beats that record is not counted: at most 0.2 % of S1's samples at 100 ev/s).
Two test-only knobs were added to `ReaderLoop` for §7.3, package-private like unit B's: `flushForTests` (`false` = every
due type read alone) and `singleTypeFlushForTests` (`true` = a single due type through the two-statement flush instead of
the combined probe); they default to the built behaviour and are reached from the spike through the test-only
`GateSpikeAccess`. No other main code changed.

### 10.1 Latency (§7.1)

10 subscriptions per engine (5 `FRESH` periodic, 5 `CONTIGUOUS` in-transaction), one writer, one event per unit of work
to a new aggregate, 10 s warm-up, 60 s window. Commit-to-handler latency in ms, runs 1-2, `FRESH` / `CONTIGUOUS` where
they differ by more than the noise:

| Arm | 1 ev/s p50 / p99 | 10 ev/s p50 / p99 | 100 ev/s p50 / p99 / max |
|---|---|---|---|
| Backstop only, 1 s | 67-80 / 140-159 (phase-locked to the writer) | 508-510 / 989-1000 | 504-505 / 995 / 1008-1012 |
| Backstop only, 100 ms | 51-59 / 102-105 | 49-53 / 101-102 | 52 / 102 / 111-112 |
| **Tier 2** (same engine) | 0.84-1.26 / 3.4-6.5; `CONTIGUOUS` 1.27-1.83 / 3.5-4.1 | 0.77-1.25 / 2.6-4.0 | 0.50-0.55 / 1.5-1.7 / 14-21 |
| **Tier 1** (two engines) | 1.48-2.29 / 4.4-5.9; `CONTIGUOUS` 1.90-2.76 / 3.8-6.0 | 1.33-1.63 / 3.4-4.3 | 0.93-0.95 / 2.2-2.3 / 16-23 |
| Tier 1, writer in a forked JVM | — | 1.77-1.80 / 6.0-6.7 | — |
| Tiers 1 + 2, subscriptions in the writer's engine | 0.82-1.64 / 3.4-6.6 | 0.84-0.97 / 2.9-3.2 | 0.48-0.51 / 1.3-1.6 / 11-14 |
| Tiers 1 + 2, subscriptions in the other engine | 1.75-2.73 / 6.7-11.3 | 1.77-1.82 / 6.3-11.2 | 0.96-1.00 / 3.4-4.3 / 15-18 |
| S1, `max-delay` 1 s | 329-338 / 802-805 | 50 / 104-109 | 52 / 104 / 114-119 |
| S1, `max-delay` 200 ms | 86-87 / 205-207 | 48-52 / 103-104 | 51 / 103 / 117 |
| CDC (backfill, then live from the bus) | 27-33 / 56-60 | 30-32 / 60-61 | 30 / 56 / 69-76 |

Wake latency from the notifier's send to the listener's receipt (through the API hooks): p50 0.6-1.2 ms / p99 2.2-5.5 ms
at 1 ev/s, 0.5-0.6 / 1.3-1.6 ms at 10 ev/s, 0.37-0.39 / 1.0 ms at 100 ev/s (P0.17 expected 0.3-0.6 ms on the server).
Coalescing at one writer: 1.00 notification batch per commit. Running-set reads: none in any arm (one writer, commits
in order, so no read ever left a candidate).

Statements per delivered event, reader role, runs 1-2 (10 subscriptions; the reader role also runs the
`CONTIGUOUS` page transactions and the progress saves):

| Arm | 1 ev/s | 10 ev/s | 100 ev/s |
|---|---|---|---|
| Backstop only, 1 s | 1.52 | 0.160 | 0.021 |
| Backstop only, 100 ms | 2.43-2.47 | 1.12-1.13 | 0.113 |
| Tier 2 | 1.44-1.48 | 1.149 | 1.105 |
| Tier 1 | 1.47-1.48 | 1.147-1.149 | 1.103-1.105 |
| S1, 1 s / 200 ms | 15.1-15.4 / 22.1-22.6 | 6.89 / 6.95 | 1.05 |
| CDC | 9.4-9.8 | 1.31-1.33 | 0.293 |

Writer statements per event: 2.00 without tier 1, 3.00 with it (the notifier's `pg_notify` on the writer's engine);
S1 and CDC 3.0 (today's append path). WAL per event at 100 ev/s: 1 050 B backstop only, 1 790-1 840 B with a wake per
commit (one `CONTIGUOUS` page transaction per event per subscription instead of per batch), S1 1 053 B, CDC 2 174-2 178 B.

**Verdict.**
- Tier 1 below S1 at both `max-delay` values and at or below CDC, at every rate: **passes** by one to two orders of
  magnitude (p50 0.9-2.8 ms against S1's 48-338 ms and CDC's 27-33 ms; p99 2.2-6.0 ms against 103-805 ms and 56-61 ms).
  S1 does not improve on its 100 ms poll floor at 10 and 100 ev/s: a notification only shortens the *next* poll's delay.
- Tier 2 at or below tier 1: **passes** on p50 at every rate and on p99 at 10 and 100 ev/s; at 1 ev/s one run's
  `FRESH` p99 (6.5 against 5.9 ms) is the largest of 60 samples on each side, inside the noise.
- `CONTIGUOUS` within one woken probe wait of `FRESH`: **passes** (p50 within 0.6 ms, p99 within 1 ms; the woken probe
  wait is 10 ms at least).
- Tier 1's reader statements per delivered event: **passes at 1 ev/s** (1.47 against 1.52, within 2×) and **fails as
  written at 100 ev/s** (1.10 against 0.021 at a 1 s backstop and 0.113 at 100 ms). The cause is the premise "a woken read
  replaces a backstop read": at 100 ev/s a wake arrives per commit, so the loop reads once per commit (100 tail reads/s
  against 1-10) and every batch carries one event, so each in-transaction `CONTIGUOUS` subscription commits one page per
  event (`BEGIN`, progress `UPDATE`, `COMMIT`) instead of one per backstop batch. The tail read is 0.1 of the 1.1; the page
  transactions are the rest. Tier 2 costs the same (1.105), so it is the wake rate, not the notification. It is the price
  of millisecond latency and is still at S1's figure (1.05) and below S1's at 1 and 10 ev/s; CDC (0.29) batches on its own
  dispatcher. Not a defect; nothing was tuned. Options for the maintainer: accept the row as the cost of the latency, or
  coalesce wakes (a minimum interval between woken reads of a type, e.g. 5-10 ms, would bring 100 ev/s to about 0.2-0.3
  at a few ms of latency).
- Forked-JVM check: p50 1.78 against 1.33-1.63 ms in one JVM, p99 6.0-6.7 against 3.4-4.3 ms; the shortcut understates
  tier 1 by a few tenths of a millisecond at p50, which changes no verdict.

### 10.2 Append-path cost of `NOTIFY` (§7.2)

1, 8 and 20 writers, one event per unit of work to a new aggregate, 2 s warm-up, 8 s window; a second engine with one
subscription listens throughout. Arms: no `afterCommit`; `afterCommit` with the coalesced notifier (on the engine's own
pool); a writer running `pg_notify` on its own connection after commit (reference). `pg_stat_activity` sampled every
5 ms for `Lock:object`. Runs 1-2, `fsync=on`:

| Writers | None: appends/s, commit p50 | Notifier: appends/s, commit p50 | Δ appends/s | Notifier batches per commit | Notifier borrows/s | Direct: appends/s | `Lock:object` samples (notifier / direct) |
|---|---|---|---|---|---|---|---|
| 1 | 4 327-4 575, 0.13-0.14 ms | 4 373-4 458, 0.13 ms | −2.6 % / +1.1 % | 1.00 | 4 390-4 475 | 2 369-3 654 | 0 / 0 |
| 8 | 20 501-20 981, 0.26-0.27 ms | 20 269-20 446, 0.28 ms | −2.6 % / −1.1 % | 0.50 | 10 070-10 150 | 16 268-16 659 | 0 / 42-47 of 1 546 |
| 20 | 24 584-24 676, 0.28-0.29 ms | 21 099-23 025, 0.31-0.32 ms | **−14.5 %** / −6.3 % | 0.46 | 9 650-10 530 | 20 722-20 901 | 0 / 102 of 1 545 |

`pg_notify` server execution: mean 1 µs, max 0.01-0.30 ms (pg_stat_statements; the client-side send time has no hook
after the send). `fsync=off` (recorded, not gated): one writer 6 794-7 001 → 5 599-5 693 (−18 %); eight writers 37 826-38 320
without against 64 194-66 341 with the notifier (the no-notifier figure is low in both runs and is not understood: noise of
the shared CPUs is the likely cause); twenty 68 590-76 921 → 56 146-57 000 (−18 % / −26 %); coalescing 0.29-0.33 batches
per commit; direct `pg_notify` waits on `Lock:object` in 820-870 of 1 540 samples at 20 writers.

**Verdict.** Writers are never in `Lock:object` with the notifier (0 samples in every arm; 42-870 with the direct
reference), which is the point of moving `NOTIFY` off the commit path: **passes**. Appends/s within 10 % of none:
**passes at 8 writers** (−1 to −3 %) and is **borderline at 20** (−14.5 % in run 1, −6.3 % in run 2; mean −10.4 %), with
commit p50 +0.02-0.04 ms. The cost is CPU, not the lock: at 20 writers the notifier sends about 10 000 batches/s (coalescing
0.46 batches per commit; each send is fast, so few commits wait in the set), each one a borrowed connection and a
transaction of its own, and the server's CPU rises from 2.7 to 3.0-3.1 cores on CPUs it shares with the client. Not a defect;
no default was changed. The lever, if the dedicated server confirms the cost, is a minimum interval between sends (a
coalescing window of 1-2 ms would cut the sends to a few hundred per second at the price of that much latency).

### 10.3 One flush across woken types (§7.3) and the single-type flush (§9 decision 2)

1, 10, 16 and 50 aggregate types, one `FRESH` subscription each; one writer per type commits in lockstep 50 times a second,
then one `afterCommit` names every type, so they are woken together. A long writer per type holds one value below its
head throughout, so every type keeps a candidate and wants a running set whenever its probe clock allows. 5 s warm-up,
30 s window. Server CPU of the reader role's backends (on-CPU time from `/proc/<pid>/schedstat` in the container: the
loop's held connection and the subscriptions' progress saves) per wake, runs 1-2. The container's whole CPU was also
recorded, minus a writers-only arm, but the writers' cost (19-70 % of a core) swamps the difference and that figure was
noise (negative in half the arms); the per-backend figure is the measurement.

| Types | Arm | Reader backends' CPU per wake | Statements per wake | Running-set reads per wake | Wake-to-handler p50 / p99 |
|---|---|---|---|---|---|
| 1 | Combined probe (as built) | 316-332 µs | 1.09-1.11 | 0.09-0.11 | 0.64-0.70 / 1.5-3.1 ms |
| 1 | Two-statement flush | 305-328 µs | 1.21-1.28 | 0.11-0.14 | 0.66-0.78 / 1.3-1.9 ms |
| 10 | **Flush** (as built) | **585-718 µs** | 10.6-10.9 | **0.05-0.10** | 0.77-1.10 / 2.0-3.0 ms |
| 10 | Each type alone | 1 259-1 280 µs | 11.2-11.8 | 1.17-1.79 | 0.82-0.98 / 2.9 ms |
| 16 | **Flush** | **713-741 µs** | 16.9-17.1 | 0.05-0.10 | 0.75-0.87 / 2.5-2.8 ms |
| 16 | Each type alone | 1 908-1 918 µs | 18.3-18.4 | 2.40-2.51 | 1.28-1.29 / 2.9-3.0 ms |
| 50 | As built (above 16 due: one statement each) | 2 985-2 997 µs | 53.2-53.6 | 1.93-2.25 | 2.37-2.41 / 5.0-5.2 ms |
| 50 | Each type alone | 3 477-3 484 µs | 54.0-54.1 | 4.36-4.42 | 2.39-2.40 / 5.2-5.3 ms |

**Verdict: passes.** At 10 types the flush cuts the reader's server CPU per wake by 44-54 % (P0.16: −58 %; gate −30 %), at
16 by 61-63 %, and reads one running set per wake at most (0.05-0.10 against 1.2-1.8: the running set is read once for
every type and moves every type's probe clock). Wake-to-handler latency is the same or lower (P0.16 had found the flush
slightly slower; here a single round trip for ten statements outweighs it). At 50 types the engine falls back to one
statement per type as designed, except when a subset of 2-16 types is due together (715-849 multi-type flushes in
30 s), which still halves the running-set reads.

**Distinct flush texts** (§4.2's "recorded"): 4-10 at 10 types, 6 at 16, but **180-213 at 50 types**, above
`MAX_FLUSH_TEXTS` (64): the due subsets of 2-16 types out of 50 vary from iteration to iteration, so the LRU of prepared
flush statements churns. The "above 16 due, one statement each" guard does not bound the number of texts when many types
are active but only some are due. Not a correctness issue (a text falls out of the LRU and is prepared again); a later
phase may want to key the flush on a fixed slot order or cap the subsets it flushes.

**Decision 2's figure.** For a single due type the two-statement flush (tail, then `SELECT RUNNING_SET`) costs the same
server CPU as the combined probe within the noise: 305-328 against 316-332 µs per wake, 1.2-1.3 against 1.1 statements per
wake (the running set is a statement of its own about once in eight to ten reads), with the same latency. In this shape only
about one read in ten carries a running set, so the per-probe difference is diluted tenfold; but nothing here argues for
keeping the combined probe on cost grounds. §4.2's condition for keeping it ("only if §7 shows the two-statement flush
costs more server CPU") is not met.

### 10.4 I7 and the backstop as a number (§7.4)

§7.1's two-engine shape with every notification lost: W's cross-JVM wake-up off and no `afterCommit`, S's listener on and
hearing nothing; backstop 1 s, 10 subscriptions. Runs 1-2: p50 45-110 ms / p99 1 001-1 002 ms / max 1 002-1 003 ms at
1 ev/s; 502-505 / 988-998 / 1 001-1 005 ms at 10 ev/s; 504-505 / 994-995 / 1 005-1 017 ms at 100 ev/s; every event
delivered once. **Passes**: p99 at or below 1 s plus one read everywhere (the largest single delivery, 1 017 ms, is 1 s
plus the read and one in-transaction page). Beside §10.1's tier-1 arm (p99 2.2-6.0 ms) that is what the tiers buy: two
and a half orders of magnitude at p99, with the backstop still bounding every loss.

### 10.5 `wokenProbeMin` (§9 decision 4)

**Confirmed at 10 ms**, with a caveat. §7.1's steady shape never produced a candidate (one writer, commits in order), so
no running set was read in any latency arm and the floor did not act; `CONTIGUOUS` stayed within 0.6 ms (p50) and 1 ms
(p99) of `FRESH`. In §7.3's shape, where every type kept a candidate behind a long writer and was woken 50 times a second,
the woken probe clock held running-set reads to 1.2-2.5 per wake across 10-16 types read alone (about 7 per second per
type, the cost rule above the 10 ms floor) and to 0.05-0.10 per wake with the flush. Nothing here argues for moving it;
`EventLogWakeIT` (8 writers, out-of-order commits) keeps the bound under test.

### 10.6 Unit B's deviations from §3 and §6 (recorded)

- Stopping the listener sends a "kick": the notifier sends `<instance>:` (no type) on `el_wake`, which ends the
  listener's blocking read at once instead of after its 1 s timeout; a typeless payload wakes nothing.
- `QUERY_POLL`, the fallback drain when the pin fails, polls from 1 ms doubling to 50 ms instead of one empty query per
  1 s `timeout` (§3.3).
- On a restart the listener wakes every active type once (`wakeAll()`) after it has reconnected and re-run `LISTEN`,
  not before the wait (§3.3), so the wake read covers the whole time it was deaf.
- The listener and wake ITs run shorter shapes than §6.1 and §6.3 list (fewer seconds, lower rates); the must-fail
  variants are all present.
- The `PINNED_PEEK` drain reads pgjdbc internals reflectively (`PGStream.nextStreamAvailableCheckTime`, pinned and undone
  before release); `WakeTierTest` guards the field names, so a pgjdbc upgrade that renames them fails a unit test instead
  of silently falling back to `QUERY_POLL`.

### 10.7 Verdict

The tiers deliver what the phase is for: commit-to-handler p50 0.5-1.3 ms in the same engine and 0.9-2.8 ms across
engines, p99 1.5-6.5 ms, against S1's 48-338 ms / 103-805 ms and CDC's 27-33 / 56-61 ms; with every notification lost the
backstop still bounds delivery at 1 s (I7). The one-flush read halves the reader's server CPU per wake at 10 types and
reads one running set per wake instead of one per type. Two rows do not pass as written and are left to the maintainer,
with their causes: reader statements per delivered event at 100 ev/s (1.10 against 0.02-0.11; a read and an in-transaction
page per commit, the price of the latency; wake coalescing is the lever), and the notifier's append cost at 20 writers
(−6 to −15 %, CPU on the shared VM, never the `NOTIFY` lock; a send interval is the lever). For decision 2: the
single-type two-statement flush costs the same as the combined probe. `wokenProbeMin` stays 10 ms. Surprise: 180-213
distinct flush texts at 50 active types, above the 64-entry statement LRU. No default changed and no main code changed
except the two test knobs in `ReaderLoop`.
