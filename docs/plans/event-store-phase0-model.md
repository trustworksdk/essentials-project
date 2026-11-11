# Event store redesign, phase 0: model of the subscription state machine (spike P0.5)

Spike P0.5 of [`event-store-subscription-redesign.md`](event-store-subscription-redesign.md) (§18) asks whether the
state machine of the redesign holds: the tail reader with its candidates and watermark, both delivery modes, attach and
detach, catch-up and the resume point. Its gate is that a model checks invariants I1–I3, I12 and I13 (§16). No engine
code exists. The model exists to find design flaws before any is written, so this document reports findings, not
decisions.

Date: 2026-11-11.

## 1. How to run it

The model is plain Java with JUnit and AssertJ, in the test sources of `components/postgresql-event-store`, package
`…eventstore.postgresql.spike.model`:

- `SubscriptionModel` is the model: state, enabled steps, step semantics, invariant checks, the mutants.
- `ModelExplorer` does breadth-first exhaustive exploration (so every counterexample is a shortest one) and seeded
  random walks, and renders a trace by replaying the step labels.
- `SubscriptionStateMachineModelTest` holds the configurations, the checks and the scripted scenarios.

It needs no Docker. It is tagged `spike` and runs only with `-Dspike.run=true`, so the default build skips it:

```bash
mvn -o test -pl components/postgresql-event-store -Dtest=SubscriptionStateMachineModelTest \
    -Dspike.run=true -Dsurefire.failIfNoSpecifiedTests=false
```

Optional knobs: `-Dspike.model.maxStates=N` (cap of the exhaustive runs, default 5 000 000),
`-Dspike.model.mutantMaxStates=N` (cap per mutant, default 1 000 000), `-Dspike.model.seeds=N` (random walks per
configuration, default 200 000). Every counterexample is printed as a numbered trace with the full state after each
step; random-walk counterexamples also print their seed.

No TLA+ tools were available offline on the machine, so there is no TLA+ specification.

## 2. What is modelled

**Writers.** Each writer appends one event to one aggregate, in three atomic steps: take an xid
(`pg_current_xact_id()`); insert, which derives `event_order` from committed state (the highest committed order of the
aggregate plus one) and takes the next `global_order` from the sequence, as §5.1's single statement does; then commit
or, for writers marked abortable, abort. The unique constraint on `(aggregate_id, event_order)` is checked at commit: a
writer whose order was committed by another writer in the meantime aborts instead. Aborted writers and sequence jumps
(`seq += 2`, §4.8) leave values that never appear. Writers keep running across a crash of the engine, because they may
be other processes. A long writer (§4.6) is simply a writer that commits late; exhaustive search covers every such
interleaving.

**The tail reader** (§4.2, §4.10, §6) is a single thread with a cursor, a set of unresolved holes, a list of candidates
`(S, X)` and a watermark. One probe is two atomic steps, matching the order P0.2 proved for the combined statement:

1. `reader.probe.snapshot` takes the statement's MVCC snapshot: up to `tailLimit` committed rows above the cursor.
2. `reader.probe.runningSet` reads the running set (writers with an xid that have not finished), which may have
   changed since step 1. It then advances the cursor, records the holes between the rows read, publishes the rows to
   attached subscriptions, retires every candidate whose `X` is disjoint from the running set, and adds a new candidate
   `(cursor, running set)` when a hole exists that no outstanding candidate covers. The empty tail read still returns
   the running set, as §4.10 requires.

When candidates retire, the holes at or below the highest retired `S` move to a pending set and a separate step,
`reader.holeRead`, reads them once and publishes what it finds as late commits. A crash can fall between retirement and
the hole read. The watermark is `min(cursor, lowest unresolved or pending hole − 1)` (see finding F2).

**Subscriptions** each have a mode (`FRESH` or `CONTIGUOUS`), a progress style (periodic save, or progress in the
handler's transaction), a set of event types, a bounded buffer and a durable resume point. They move through

- *detached*: catch-up reads of matching rows in `(position, watermark]`, `catchUpLimit` rows at a time; a page shorter
  than the limit advances the position to the watermark (§10.1);
- *attaching*: registered with the reader, so it receives every publication from now on, but not yet delivering;
- *attached*: after the bridge read of `(position, reader cursor at attach]` ("attach first, then read up to the head,
  then drain", §6).

A publication to a full buffer detaches the subscription instead of blocking the reader (§6). The catch-up position it
detaches with is §4.7's formula, counting the event that did not fit as delivered but not handled. `CONTIGUOUS` holds
events above the watermark in its buffer and releases them in `global_order` only up to the watermark (§7.1).

Handling:

- *Periodic* (the `FRESH` default for event processors): one event per step, then a separate `saveResumePoint` step that
  writes §4.7's formula, `min(watermark + 1, lowest delivered-but-not-yet-handled order)`.
- *In the handler's transaction* (§8): `pageBegin` takes up to `pageSize` deliverable events and invokes the handler;
  `pageCommit` commits the projection and the resume point together. A crash between the two loses the page's
  transaction. If an event of a page throws, the page rolls back and is replayed one event per transaction until the
  failing event is handled or, after `maxAttempts`, dead-lettered (§8.3).

**Crash and restart** wipes all memory: reader, buffers, open transactions, retry counters. The subscriptions restart
detached from their durable resume points. The restarted reader starts at the head with one *initial candidate*
`(head, running set)` and an unknown watermark; no subscription may attach until that candidate retires (finding F4).

**Not modelled** (abstracted away): more than one event per writer; several aggregate types (one reader); tenants;
wake-up tiers and timing (the probe interval, backstop); the per-database catch-up permit (I15); fencing and exclusive
ownership (I5); `resetFrom` and the reposition epoch (I6); the old engine and hand-over (I8); the adaptive fallback of
§10.1; a `CONTIGUOUS` projection's own content (the fold is the multiset of applied orders, which is what exactly-once
is about). xid wrap-around is irrelevant here because retirement is by set disjointness.

## 3. Invariants checked

After every step:

- **I2** at every first delivery within an incarnation (a process lifetime): no `A#n` is first delivered after an
  `A#m` with `m > n`.
- **I3** for every subscription: no order below the durable resume point is held by a running writer, and every
  committed matching order below it has been handled (side effect done, applied, or dead-lettered).
- **I13** for every subscription that is not attached: the same, for its in-memory catch-up position (handled, buffered
  or in an open page counts).
- **I12** for every `CONTIGUOUS` subscription: it never delivers an event above the watermark; it applies events in
  strictly increasing `global_order`; and its durable projection is exactly the set of committed matching orders below
  its durable resume point, each applied once. The last part is the "crash at any point" statement of I12 checked in
  every state: a crash there replays from the resume point, so any applied order at or above it would be applied twice,
  and any missing order below it would never be applied.
- **I9**: whenever there are committed rows above the cursor, the reader has an enabled step.
- **M0** (a sanity check of the model): the watermark never moves backwards within one reader lifetime.

And **I1**: in every state in which all writers have finished, a copy is driven to quiescence by a fair deterministic
schedule (no crashes), checking all of the above on the way, and then every committed matching event must have been
handled by every subscription.

I14 (page fallback) is covered by the `pages` and `deadLetter` configurations through I12's exactly-once fold: a page
that fails on one event must neither lose nor double-apply any other event of the page.

## 4. Configurations and bounds

Common bounds unless stated: tail read `LIMIT 2`, buffer 2, page 2, catch-up page 1, at most one crash, one
event per writer, two aggregates (A, B), two event types. The configurations run with the hole re-read of finding F1
(`holeRereadOnProbe = true`); the run "as written" turns it off. Results of the run on 2026-11-11 (JDK 25, one thread; the whole class takes about 7.5 minutes with the defaults):

| Configuration | Writers | Subscriptions | Other | Exploration | Result |
|---|---|---|---|---|---|
| `small` | A, B | `FRESH` periodic (all), `CONTIGUOUS` in-tx (all) | — | exhausted: 21 214 states, depth 28 | all invariants hold |
| `jump` | A (abortable), A | `FRESH` periodic (all), `CONTIGUOUS` in-tx (type 0) | up to two sequence jumps of 2 | exhausted: 421 690 states, depth 34 | all hold |
| `core-no-jump` | A type 0 (abortable), A type 1, B type 0 | `FRESH` periodic (all), `CONTIGUOUS` in-tx (type 0: filtered catch-up) | — | exhausted: 2 711 494 states, 9.2 M transitions, depth 35 | all hold |
| `pages` | A, A (handler fails once), B | `CONTIGUOUS` in-tx (all), `FRESH` in-tx (type 0) | no jumps | exhausted: 3 939 152 states, depth 48 | all hold, including I14 through I12's fold |
| `deadLetter` | as `pages`, the failing event always fails (dead-lettered after 2 attempts) | as `pages` | no jumps | exhausted: 4 195 941 states, depth 47 | all hold |
| `core` | as `core-no-jump` | as `core-no-jump` | one sequence jump | bounded: 8 000 000 states, 26.7 M transitions, every state to depth 20 (cap 8 M in a separate run; 5 M by default) | no violation found |
| `core`, random walks | | | | seeds 1-200 000, 60 steps each, then writers finish and drain | no violation |
| `pages`, random walks | | | | seeds 1-200 000 | no violation |
| `large`, random walks | five writers (two abortable, one failing once) | `FRESH` periodic, `CONTIGUOUS` in-tx (type 0), `FRESH` in-tx | buffer 1, two crashes, two jumps | seeds 1-200 000, 11.6 M steps | no violation |
| `core` **as written** (no hole re-read) | as `core` | as `core` | | bounded at the state cap (5 000 000 states, every state to depth 19) | **I2 fails** (finding F1); nothing else fails |

Breadth-first search explores every state at depth *d* before any at *d* + 1, so a bounded run has checked every
interleaving up to the depth before the one it stopped in.

The gate of P0.5 is met for I1, I3, I12 and I13: no violation in any configuration, and each was seen to fail against a
mutant (§5). I2 holds only with the hole re-read of F1; as written, it fails.

## 5. Mutants

Each invariant was seen to fail against at least one deliberately broken variant. The mutants run on the
configuration named, breadth first, up to the state cap, recording the shortest trace for each invariant that fails.

| Mutant | What is broken | Base | Invariants that fail |
|---|---|---|---|
| `VALUE_BEFORE_XID` | the writer takes its `global_order` before its xid (§4.3, prerequisite 1) | `core` | I13, I2, I3 |
| `VALUE_BEFORE_EVENT_ORDER` | the value is taken before `event_order` is derived from committed state (§4.5, §5.1) | `core` | I2 |
| `X_BEFORE_S` | the running set is read in a statement before the tail read (§4.10 reversed) | `core` | I13 |
| `CACHED_STAT_ACTIVITY` | the probe reuses a running set cached by an earlier probe in the same transaction (P0.2) | `small` | I13, I3, I12 |
| `EMPTY_TAIL_NO_RUNNING_SET` | an empty tail read returns no running set, taken as "nobody running" (§4.10) | `core` | I13, I3 |
| `WATERMARK_BEFORE_HOLE_READ` | the watermark advances at retirement, before the hole read (§4.2) | `small` | I3, I12 |
| `READER_RESTART_AT_HEAD` | a restarted reader puts its watermark at the head, with no initial candidate | `core` | I13 |
| `RESUME_AT_HIGHEST_HANDLED` | the resume point is the highest handled order + 1 (§4.7, §8.3) | `core` | I3 |
| `RESUME_AT_CURSOR` | the resume point is the reader's cursor + 1 (§4.7) | `core` | I3, I12 |
| `DETACH_AT_WATERMARK` | after overflow, catch-up resumes from the watermark (§6) | `core` | I13 |
| `ATTACH_AFTER_BRIDGE_READ` | the subscription reads up to the head first and registers with the reader after (§6) | `core` | I3, I12, I1 |
| `CATCHUP_ABOVE_WATERMARK` | filtered catch-up reads up to the head and advances there on a short page (§10.1) | `core` | I13 |
| `CONTIGUOUS_ABOVE_WATERMARK` | `CONTIGUOUS` releases buffered events above the watermark (§7.1) | `core` | I12 |
| `CONTIGUOUS_PROGRESS_OUTSIDE_TX` | `CONTIGUOUS` applies per event, saves progress periodically in its own transaction (§8) | `core` | I12 |
| `PAGE_FALLBACK_PARTIAL_COMMIT` | a failing page commits the events before the failure, then replays the page per event (§8.3) | `pages` | I12 (the I14 case) |
| `BACKPRESSURE` | a full buffer blocks the reader instead of detaching the subscription (§6) | `core` | I9, I1 |

Every mutant run stopped at its cap of 1 000 000 states except `CACHED_STAT_ACTIVITY` (49 712 states) and
`WATERMARK_BEFORE_HOLE_READ` (25 870 states), which were exhausted on `small`. On `core`, the cached running set is
caught only beyond the 1 M cap, because the stale set must survive a whole probe; hence the smaller base. So each
invariant of the gate is caught by at least one mutant: I1 by `ATTACH_AFTER_BRIDGE_READ` and `BACKPRESSURE`; I2 by
`VALUE_BEFORE_EVENT_ORDER` and `VALUE_BEFORE_XID` (and the design as written); I3 by seven mutants; I12 by seven; I13
by seven; I9 by `BACKPRESSURE`; I14 (through I12) by `PAGE_FALLBACK_PARTIAL_COMMIT`.

Two examples of shortest traces. `CONTIGUOUS_PROGRESS_OUTSIDE_TX`:

```
w0.xid, w0.insert, w0.commit   A#0 @ g1
CRASH+restart
s1.catchUp(<=1), s1.handle     the CONTIGUOUS projection applies g1 and commits it; the resume point is still 1
                               -> I12: a crash now replays g1 and applies it twice
```

`CATCHUP_ABOVE_WATERMARK`:

```
w0.xid, w0.insert              g1 held, uncommitted
w1.xid, w1.insert, w1.commit   g2 committed
s0.catchUp(<=2)                filtered catch-up up to the head; the short page advances the position to 2
                               -> I13: the position passes g1, which w0 can still commit
```

## 6. Findings

Notation in the traces: `w0.insert` is a writer's insert (the state shows `A#0@g1`: event order 0 of aggregate A at
`global_order` 1); `s0`/`s1` are subscriptions. The full traces, with the state after every step, are printed by the
test.

### F1. As written, `FRESH` breaks per-aggregate order (I2). Affects §4.2, §4.5, §7.1

The design delivers a late commit below the cursor only from the hole read that follows its candidate's retirement
(§4.2: "Whatever that read returns is delivered as a late commit"; §7.1: "a late commit below the cursor arrives when
its candidate retires, after higher orders"). §4.5 argues that A#5 is "visible no later than A#6", which is true, but
the tail read never looks below its cursor, so being visible does not get A#5 delivered. The shortest counterexample
(configuration `core` with late commits only via the hole read, 11 steps plus a drain):

```
w0.xid, w0.insert            w0 holds A#0 @ g1, uncommitted
w1.xid                       w1 (aggregate A) takes its xid
w2.xid, w2.insert, w2.commit B#0 @ g2 committed
reader.probe.snapshot        the probe's snapshot sees g2 only
w0.commit                    A#0 @ g1 committed (after the snapshot)
w1.insert, w1.commit         A#1 @ g3: its order is derived from A#0, now committed
s0.attach                    FRESH subscription attaches
(drain) probe.runningSet     cursor 2, hole {1}, candidate (S=2, X={}): every writer has finished
        probe                reads g3 = A#1 and publishes it; the candidate retires
        holeRead             finds A#0 @ g1 and publishes it as a late commit
        s0 handles g2, g3, then g1: A#0 first delivered after A#1  -> I2
```

Delivering the hole read's rows ahead of the same probe's batch would not be enough. In the long-writer shape (scripted
test `design_as_written_fresh_long_writer_scenario`) an unrelated writer that took its xid before the probe keeps the
candidate from retiring at all; A#1 is read and delivered, and A#0 waits for the unrelated writer. That is §4.6's long
writer, which `FRESH` is meant to deliver around.

Candidate change, not decided: the tail statement also re-reads the outstanding holes
(`WHERE global_order > :cursor OR global_order = ANY(:holes)`, with ranges for a sequence jump), and publishes what it
finds in `global_order` ahead of the new rows. A#1 can only exist once A#0 has committed, so any snapshot that sees A#1
also sees A#0. With that change (`holeRereadOnProbe = true`, the model's default) every configuration passes all
invariants. The hole read after retirement is still needed: a writer can commit between the statement's snapshot and its
read of the running set, so the retiring probe itself may not have seen the row. The cost is a larger tail statement
while holes are outstanding; it costs no extra statement. `CONTIGUOUS` is unaffected.

### F2. The watermark needs two clarifications. Affects §4.2

- §4.2 defines the watermark as "the highest `S` of a retired candidate such that no older candidate is still
  outstanding". That does not say what it is once the cursor has moved past the last candidate's `S` with no new hole.
  The model uses `min(cursor, lowest unresolved hole − 1)`, where a hole is unresolved until its candidate has retired
  *and* its hole read has run. Every order at or below that is read or proven absent.
- Read literally, the definition lets the watermark advance at retirement, before the hole read. The mutant
  `WATERMARK_BEFORE_HOLE_READ` shows that this loses events: a `CONTIGUOUS` subscription commits a page past the hole
  before the late commit is published (I12: "projection lacks committed g1 below its resume point"), and a crash
  between retirement and the hole read leaves resume points past an unhandled event (I3). The watermark must move only
  after the hole read has returned and its rows have been published.

### F3. §6's attach rule cannot be met behind a long writer. Affects §6, §10.1, §7.1

§6 attaches a subscription "within its buffer's distance of the tail", and §10.1 caps every catch-up read at the
watermark. While a long writer holds the watermark down, a subscription that is behind catches up to the watermark and
stops; its distance to the reader's cursor stays above the buffer size, so it never attaches, and nothing above the hole
reaches it until the writer ends. For a `FRESH` subscription that defeats the point of the mode. Scripted test
`attach_rule_relative_to_cursor_starves_fresh_behind_a_long_writer`: a long writer at g1, three commits at g2..g4, the
reader at cursor 4 with watermark 0; the subscription has no enabled step at all.

The model therefore attaches when the catch-up position has reached the watermark, and the bridge read after attaching
covers `(position, reader cursor]`. That range is not bounded by the buffer: it is everything the reader has read above
the hole. For `CONTIGUOUS` those bridged rows are held back above the watermark, so a long writer can make a
`CONTIGUOUS` subscription attach, overflow and detach repeatedly, one bridge read each time. That is a cost, not a loss;
the model does not measure it.

### F4. Where a restarted tail reader starts is unspecified. Affects §4.2, §6, §10.1

The design does not say where the tail reader starts after a restart, or what its watermark is before its first
candidate retires. Starting at the head with the watermark at the head (mutant `READER_RESTART_AT_HEAD`) loses events:

```
w0.xid, w0.insert            w0 holds g1, uncommitted
w1.xid, w1.insert, w1.commit g2 committed
CRASH+restart                reader starts at the head: cursor 2, watermark 2
s0.catchUp(<=2)              the subscription's position passes g1, still held by w0  -> I13
```

The model's rule: start at the head with one initial candidate `(head, running set)`, keep the watermark unknown until
it retires, and let no subscription catch up past the last known watermark or attach before then. When no writer is
running at the restart, the watermark is the head at once. §10.1's "computes with its own candidate when no tail reader
runs" needs the same rule.

### F5. The position a detaching subscription resumes from is unspecified. Affects §6, §4.7, §8.3

§6 says an overflowing subscription "detaches and goes back to catch-up", but not from where. Resuming from the
watermark (mutant `DETACH_AT_WATERMARK`) loses events that were buffered but not yet handled (I13). The model detaches
at §4.7's formula, `min(watermark + 1, lowest buffered order, the order that did not fit) − 1`. Two details the
engine has to settle: an open page of an in-transaction subscription is left out of that position and, if the page then
rolls back, its events go back into the subscription's buffer; and a `CONTIGUOUS` subscription must drop anything at or
below the last order it applied, because the catch-up that follows overlaps what the open page committed.

### F6. Back-pressure would deadlock `CONTIGUOUS`, not just slow it. Affects §6

The mutant `BACKPRESSURE` (a full buffer blocks the reader) fails I9, as expected, and also I1 at quiescence: a
`CONTIGUOUS` buffer full of events held back above the watermark blocks the reader, and the watermark can only advance
through the reader. Every writer has finished and nothing moves again. §6's rule that a slow subscription must detach
rather than block the reader is load-bearing for `CONTIGUOUS` correctness, not only for latency.

### Confirmations

The other mutants confirm parts of the design rather than find new problems: xid before value (§4.3) and `event_order`
derived from committed state at insert time (§4.5, §5.1) are both necessary; the running set must be read after the tail
snapshot (§4.10), not from a backend-status snapshot cached earlier in the transaction (P0.2's condition), and an empty
tail read must still return it; the resume point must be §4.7's formula, not the highest handled order nor the reader's
cursor (§8.3's "not the page's highest order" holds); catch-up must stop at the watermark (§10.1); attach must register
before its bridge read (§6); `CONTIGUOUS` must not deliver above the watermark and must save progress in the handler's
transaction (§7, §8); and a failing page must roll back as a whole (§8.3). In the configurations explored to exhaustion
(`small`, `jump`, `core-no-jump`), every interleaving within their bounds was checked, including a crash at every point
(between a candidate's retirement and its hole read too), aborted writers, sequence jumps (`jump`), and attaching while
behind. Overflow is reached in `core`: the mutant `DETACH_AT_WATERMARK`, which changes nothing but the overflow path,
is caught there.

## 7. What the model cannot show

- **Timing and liveness in general.** The model has no clock. It shows that a step is or is not enabled (F3), not how
  long anything takes. The `CONTIGUOUS` freshness claim of §7.3 is untouched.
- **PostgreSQL's behaviour.** The writer steps encode the prerequisites (§4.3) as assumptions: xid before value,
  `event_order` from committed state, `CACHE 1`, a fully visible `backend_xid`. P0.1 and P0.2 measured those; the model
  only shows what breaks when they do not hold.
- **Bounds.** Exhaustive results hold for the configurations of §4 only: three writers, two aggregates, two event
  types, two subscriptions, one crash, buffers of two. Larger configurations were sampled by random walks, which find
  shallow bugs well and deep ones poorly. The configurations that hit the state cap are bounded, not exhausted (marked
  in §4).
- **Implementation detail the design has not fixed**: threads (the model's subscription steps are atomic), the shape of
  the catch-up permit, fencing, resets, the hand-over from the old engine.
- **Several events per transaction**, which interleave their sequence values with other writers' and give one writer
  several holes; and several aggregate types per JVM.
