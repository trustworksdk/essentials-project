# Event store redesign, phase 7: engine design

**Status:** decided 2026-11-16 (§14); being built on `feature/event-store-engine-phase7` (from phase 6, `ccbbdd15e`).
When phase 7 is done, this file moves to `docs/archive/` or is folded into the redesign's record (`docs/plans/README.md`).
**Scope:** phase 7 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): engine leases on the
progress row, batched renewal, exclusive subscriptions fenced (§9), with no `FencedLockManager`; the progress row and
its fenced update (§8.2, §8.3); §19 items 17 (fences from one sequence) and 22 (lease timings and the clock). Two known
gaps are folded in: the `PERIODIC` park insert that is not guarded by the reset epoch (phase-4 §4.4, §11.5), and the
`$fence` placeholders that phases 2 and 4 left null (phase-2 §3.4, phase-4 §3.3).
**Gate:** I5, I6 and I16 pass (§16), each with a must-fail mutant; multi-JVM takeover ITs pass, one family of them
with forked JVMs; the §10 measurements meet the pass lines decided there. Every phase-2 to phase-6 IT keeps passing,
with every `must_fail_*` test.

Section references (§n) are to the plan; "phase-4 §n" etc. are `docs/plans/event-store-engine-phase4.md` and its
siblings. Where this draft proposes something the plan does not say, it is marked **Proposed**. The existing
`PostgresqlEventStore`, `FencedLockManager`, `durable_subscriptions` and the old subscription model are not changed and
nothing below copies them: there is no lock table, no lock callback and no `FencedLock` object. The public API stays
phase 8's (§12.3); phase 7 adds temporary `subscribeExclusive` overloads beside phase 4's `subscribe` overloads.

---

## 1. What exists today, and what changes

### 1.1 Already in the module

| What | Where | State |
|---|---|---|
| Lease and fence columns | `EventLogSchemaContributor.schemaStatements()`, `EventLogSchemaContributor.java:157-159` (`lease_owner text`, `lease_until timestamptz`, `lease_fence bigint`, all nullable) | Created since phase 2, never written |
| The fence sequence | `EventLogSchemaContributor.java:147` (`CREATE SEQUENCE IF NOT EXISTS el_lease_fence_seq`), name in `EventLogSchema.java:37` | Created, never used |
| `exclusive` column and the key | `el_progress` primary key `(subscriber, aggregate_type, instance_name)`, `instance_name = ''` for an exclusive subscription (phase-2 §3.4 DDL comment) | Column written as `false` always: `Subscription.run()` calls `progress.start(key, false, …)` (`Subscription.java:435`) |
| The `$fence` placeholder | `EventLogStorage.PROGRESS_SAVE` and `PROGRESS_SAVE_EXPECTED` (`EventLogStorage.java:71-82`): `AND (?::bigint IS NULL OR lease_fence = ?::bigint)` | Bound null in `ProgressStore.save` (`ProgressStore.java:109-110`) and `saveInTransaction` (`:129-130`) |
| `PROGRESS_START` | `EventLogStorage.java:64-70`: upsert that also sets `exclusive = EXCLUDED.exclusive` | Would flip the flag of an existing row; phase 7 stops that (§4) |
| `PROGRESS_READ` | `EventLogStorage.java:83-84`: resume point, epoch, mode, model | No lease columns; phase 7 adds them for the zero-row decision |
| Zero-row decision | `Subscription.onProgressConflict()` (around `Subscription.java:1750`): row gone → recreate; epoch moved → reposition; `resume_from` moved → reposition | No ownership branch; recreating a deleted row is wrong for an exclusive subscription (§9 "an operator deletes …") |
| Page transaction | `Subscription.applyPage()` (`Subscription.java:1404-1530`): borrow, handler on `GuardedConnection`, in-transaction park insert, `saveInTransaction` last, commit | Fence slot null |
| Parked retry/discard | `Subscription.releaseParked()` (around `Subscription.java:1650-1720`): one transaction ending in `saveInTransaction` | Fence slot null; otherwise already guarded |
| Periodic park insert | `Subscription.applyOne()` → `ParkedEvents.insertNow()` (`Subscription.java:1249`, `ParkedEvents.java:121-125`): autocommit `INSERT … ON CONFLICT DO NOTHING` | **Unguarded** by epoch and fence: phase-4 §11.5's known gap |
| Failed retry count | `ParkedEvents.failedAgain()` (`ParkedEvents.java:156-164`) | Unguarded; counter only |
| Reset | `ProgressStore.reset()` (`ProgressStore.java:167-194`): `reset_epoch + 1`, then delete parked rows, one transaction | Writes no fence (§9 last bullet); stays so |
| Final save | `Subscription.run()` finally → `saveProgressNow()` (`Subscription.java:465`, `:2066`) | No release (there is no lease) |
| Mutants and hooks | `WatermarkMutant`, `TestSeams.Hooks` (`TestSeams.java:40-161`) | Phase 7 adds its own (§9) |
| Forked-JVM precedent | `spike/SpikeWriterProcess` (child JVM on the test classpath, line protocol on stdout, ends when stdin closes) | Copied as the pattern for `LeaseOwnerProcess` (§9.4) |

So phase 7 needs **no new table and no new column** (§5); it is code, statements and tests.

### 1.2 Types

| Type | Phase 7 | One line |
|---|---|---|
| `LeaseKeeper` | **New, package-private** | One per `EventLog`: a virtual thread that, every `leaseRenewalInterval`, renews every owned lease in one statement and tries to claim every standby subscription in one statement, on one borrowed connection (§2.3, §2.2) |
| `Lease` | **New, package-private** | One per exclusive subscription: `fence`, `owner`, `confirmedAtNanos` (start of the last successful renewal or claim), state `STANDBY`/`OWNED`/`SUSPENDED`/`LOST`; read by the delivery thread, written by the keeper |
| `ProgressStore` | Lease statements, fence bound | `claim`, `claimNew`, `renew`, `release`, `saveAndRelease`; `save`/`saveInTransaction`/`saveExpected` take a `Fence` (null for non-exclusive); `read` returns the lease columns |
| `EventLogStorage` | Statements | `LEASE_CLAIM`, `LEASE_CLAIM_NEW`, `LEASE_RENEW`, `LEASE_RELEASE`, `PROGRESS_SAVE_RELEASE`, `PARK_EVENT_GUARDED`, `PARKED_FAILED_AGAIN_GUARDED`; `PROGRESS_SAVE*` gain the owner predicate (§3.2) |
| `Subscription` | Ownership around the lifecycle | `run()` becomes "await ownership → deliver while owner → on loss leave the loop and await again"; new phases `STANDBY`, `SUSPENDED`; the deadline check before each handler call and page (§2.4) |
| `SubscriptionSpec` | One field | `exclusive` (key's `instanceName` must then be `""`) |
| `EventLog` | Temporary public surface | `subscribeExclusive(…)` for a periodic `Handler` and an in-transaction `PageHandler`; owns the `LeaseKeeper` |
| `EventLogSettings` | Five fields | §6 |
| `EventLogObserver` | Extended | `onLeaseClaimed`, `onLeaseLost(reason)`, `onLeaseSuspended`, `onLeaseResumed`, `onRenewal(owned, renewed, nanos)`, `onRenewalFailed(cause)` |
| `TestSeams`, `WatermarkMutant` | Extended | §9 |

---

## 2. The lease

### 2.1 Identity

- **Owner id.** `lease_owner = <EventLog instance id>`: the 8-character random id phase 6 already draws per `EventLog`
  (`EventLog.newInstanceId()`), prefixed with an optional configured `nodeName` for operators (`"<nodeName>/<id>"`). It
  is new on every `EventLog` start, so a restarted node is a new owner. It is diagnostic **and** part of the write
  predicate (§3.2).
- **Fence.** `nextval('el_lease_fence_seq')`, assigned by the database inside the claim statement, never by the claimant
  (§9). One sequence for every row, so a token never repeats, even after a row is deleted and recreated (§19 item 17).
- **The key.** An exclusive subscription is the row `(subscriber, aggregate_type, '')` with `exclusive = true`.

### 2.2 Claim

A standby subscription is claimed by the keeper's pass, batched for every standby subscription of the `EventLog`:

```sql
-- LEASE_CLAIM: $1 owner, $2 lease (interval), $3 subscribers text[], $4 aggregate types text[]
UPDATE el_progress p
   SET lease_owner = $1,
       lease_fence = nextval('el_lease_fence_seq'),
       lease_until = now() + $2::interval
  FROM unnest($3::text[], $4::text[]) AS k(subscriber, aggregate_type)
 WHERE p.subscriber = k.subscriber AND p.aggregate_type = k.aggregate_type AND p.instance_name = ''
   AND p.exclusive
   AND (p.lease_owner IS NULL OR p.lease_until < now())
RETURNING p.subscriber, p.aggregate_type, p.lease_fence, p.resume_from, p.reset_epoch, p.consistency_mode, p.progress_model
```

- One statement, index nested loop over the primary key per key; a held lease is not matched and not locked.
- Two claimants racing for an expired lease: the second waits for the first's row lock, PostgreSQL re-checks the `WHERE`
  against the new version, `lease_until < now()` is false, no row. A claim that arrives late (§9's "claim delayed past a
  newer owner's") finds a live lease and matches nothing. `now()` is the claimant's transaction start; a claim that waited
  on a lock therefore writes a shorter lease, never a longer one.
- `nextval` in `SET` runs only for updated rows. A fence value consumed by a row that was then re-checked out is a gap,
  which is harmless.
- **A row that does not exist yet** (first start of a new exclusive subscription): when a key is missing from the
  result *and* the subscription has never seen its row, the keeper runs `LEASE_CLAIM_NEW`, the `PROGRESS_START` insert
  with the lease columns set and `ON CONFLICT DO NOTHING RETURNING …`; nothing returned means another node created and
  holds it, and the subscription stays standby. **Proposed:** the claim statement never changes `consistency_mode` or
  `progress_model`. If the returned ones differ from the subscription's, the new owner logs it (§19 item 17) and writes
  its own in one fenced `UPDATE` before delivering. A standby never writes the row.
- After a claim the subscription does what `run()` does today after `start`: `positionAt(resume_from)`, `loadParked()`,
  `loop.join(…)`, lifecycle. `resetEpoch` comes from the claim's row.

### 2.3 Renewal

```sql
-- LEASE_RENEW: $1 owner, $2 lease, $3 subscribers text[], $4 aggregate types text[], $5 fences bigint[]
UPDATE el_progress p
   SET lease_until = now() + $2::interval
  FROM unnest($3::text[], $4::text[], $5::bigint[]) AS k(subscriber, aggregate_type, fence)
 WHERE p.subscriber = k.subscriber AND p.aggregate_type = k.aggregate_type AND p.instance_name = ''
   AND p.lease_fence = k.fence
   AND p.lease_owner = $1
RETURNING p.subscriber, p.aggregate_type
```

- All lease time is `now()`; the JVM never sends a timestamp.
- A key missing from the result has lost its lease (fence changed, owner changed, or the row is gone): its `Lease` goes
  to `LOST` and its subscription stops delivering (§2.6). The others are renewed and continue: a pass that partly fails
  never extends a lease whose fence has changed (I16).
- Renewal does **not** require `lease_until > now()`. An expired lease whose fence is unchanged has not been claimed by
  anyone (a claim always changes the fence), so extending it is correct: §9's "connection loss with no takeover yet".
- Only `lease_until` changes, an unindexed column, so the update is HOT; the table's `fillfactor = 70` leaves room.
- **The confirmation time** is `System.nanoTime()` taken *before* the statement is sent. The server's `now()` is at or
  after it, so the server's expiry is at or after `confirmedAtNanos + leaseDuration` on the JVM's clock (§7 skew aside).
- Query timeout: `Statement.setQueryTimeout(leaseStatementTimeout)` (pgjdbc's cancel request; whole seconds, 3 s by
  default), so a pass never lasts longer than the renewal interval because of a lock or a slow server. The deadline in
  §2.4 does not depend on it: a hung renewal thread cannot keep a subscription delivering.
- The pass is one `getConnection()`, the renewal statement (if anything is owned), the claim statement (if anything is
  standby), return the connection. Both statements go in one pgjdbc batch-free round trip each; no transaction.

### 2.4 How a lost lease stops delivery

The **local deadline** of a lease is `confirmedAtNanos + leaseLocalStop` (7 s by default: lease minus two renewal
intervals, §19 item 22). It is checked by the **delivery thread**, not the keeper, so a stuck keeper, a pool that has no
connection for it, or a dead socket cannot extend it.

| Progress model | Where the deadline is checked | What makes a stale write harmless |
|---|---|---|
| `PERIODIC` | Before **every** handler invocation (`applyOne`, and the parked retry path), before the periodic save, before a periodic park insert | Saves and park inserts are fenced (§3); the handler's own side effects are outside the database, so the deadline is what limits them. Best effort: a handler call that started before the deadline runs to its end |
| `IN_HANDLER_TRANSACTION` | Before a page borrows its connection, and again before `commit` | The page's last statement is the fenced progress update, so a stale page rolls back as a whole, output included. The deadline only avoids wasted work; safety does not rest on it |

Past the deadline the lease is `SUSPENDED`: the delivery thread parks (no handler call, no page) until the keeper either
confirms the lease again (a renewal that returned it: `OWNED`, delivery resumes where it stopped, nothing replayed) or
reports it `LOST`. While suspended, the reader keeps offering batches; the inbox overflows and detaches through the
existing path (phase-3 §2.3), so the reader is never held up (I9).

`LOST`, from a renewal, a fenced write that updated nothing (§2.6) or a claim result naming another fence: the
subscription closes its inbox, `loop.leave(...)`, drops pending, held-back and parked state, logs **one** warning that
ownership moved (with the old and new fence when known), and goes back to `STANDBY`. It does not save, does not retry the
failed write and does not release.

### 2.5 Release

On `stop()` the owner hands over without waiting for expiry, in one statement:

```sql
-- PROGRESS_SAVE_RELEASE (PERIODIC): $1 resume_from, key, $5 epoch, $6 fence, $7 owner
UPDATE el_progress
   SET resume_from = CASE WHEN reset_epoch = $5 THEN $1 ELSE resume_from END,
       updated_at  = now(),
       lease_owner = NULL, lease_until = NULL
 WHERE subscriber = $2 AND aggregate_type = $3 AND instance_name = $4
   AND lease_fence = $6 AND lease_owner = $7
```

- The resume point is written only under the epoch it was computed in, so a reset is never undone by a release (I6);
  the lease is released either way.
- `IN_HANDLER_TRANSACTION` releases with the same statement minus the resume point (`LEASE_RELEASE`): its last committed
  page already wrote it.
- `lease_fence` is kept, not nulled: a stale write by the previous owner still mismatches, and the column says which
  fence last held the row.
- If the release fails, the lease expires instead and the next owner starts from the last save that landed: a replay
  within at-least-once (§9).
- `crash()` (tests) releases nothing, as today it saves nothing.

### 2.6 Zero rows, decided

A fenced write that updates nothing reads the row once by primary key (`PROGRESS_READ`, which now also returns
`lease_owner` and `lease_fence`), and decides in this order:

1. **Row gone.** Exclusive: stop with an error, never recreate it (it would lose the resume point, §9). The phase-4
   behaviour of recreating it stays for non-exclusive subscriptions.
2. **Fence or owner differs.** Ownership lost: `LOST` (§2.4). Checked before the epoch, because a new owner also handles
   any reset that came with it.
3. **Epoch differs.** A superseding reset: reposition at the row's `resume_from`, as today.
4. **`resume_from` differs under the same epoch and fence.** Only an operator's edit can do that to a fenced row:
   reposition at the row's value and log at error, as phase 4 does.

---

## 3. The fence on every write

### 3.1 Every write of a subscription, and its guard

| Write | Today | Phase 7 |
|---|---|---|
| Periodic save (`ProgressStore.save`) | epoch | epoch + fence + owner, same statement, same row |
| Page transaction's progress update (`saveInTransaction`) | epoch + expected `resume_from` | + fence + owner; still the last statement |
| Watermark-only save of an in-transaction subscription (`saveExpected`) | epoch + expected | + fence + owner |
| In-transaction park insert | inside the page transaction | unchanged: the page's fenced update decides its fate |
| **Periodic park insert** (`ParkedEvents.insertNow`) | **none** | epoch + fence + owner, one statement (§3.3) |
| Parked retry/discard transaction (`releaseParked`) | its `saveInTransaction` | + fence + owner through that update |
| `failedAgain` (attempt counter) | none | epoch + fence + owner, as §3.3 |
| Final save on stop | epoch | `PROGRESS_SAVE_RELEASE` (§2.5) |
| Reset (`ProgressStore.reset`) | none | none (§9: a reset refuses older saves through the epoch). It does not touch the lease columns |
| Renewal | — | fence + owner (§2.3) |

For a non-exclusive subscription the fence and owner are bound `NULL` and the predicate is
`($fence::bigint IS NULL OR (lease_fence = $fence AND lease_owner = $owner))`, the placeholder phases 2 and 4 left.

### 3.2 Why the owner joins the fence (**Proposed**)

§9 asserts the fence alone. A fence comes from a sequence and so never repeats in one timeline, but after a failover to
an **asynchronously** replicated standby it can: the old primary may have handed out fence `f` in a claim whose WAL never
reached the standby, which then hands out `f` again. Both nodes then hold `f`. Adding `AND lease_owner = $owner` (an id
unique per `EventLog` start) to every fenced statement costs nothing (same row, same statement) and keeps the two apart.
It is not a substitute for synchronous replication: commits lost in such a failover lose progress and events too, which
the engine cannot repair. Open question 3.

### 3.3 The periodic park insert (phase-4 §11.5's gap)

Today a reset that commits between a `PERIODIC` subscription's decision to park and its autocommit insert leaves a park
row for an event the reset repositioned past (the reset deleted the parked rows a moment before). The insert becomes
guarded on the progress row:

```sql
-- PARK_EVENT_GUARDED: key, $4 epoch, $5 fence, $6 owner, then the park row's columns
WITH guard AS (
    SELECT 1 FROM el_progress
     WHERE subscriber = $1 AND aggregate_type = $2 AND instance_name = $3
       AND reset_epoch = $4
       AND ($5::bigint IS NULL OR (lease_fence = $5 AND lease_owner = $6))
       FOR SHARE),
ins AS (
    INSERT INTO el_parked_events (subscriber, aggregate_type, instance_name, global_order, aggregate_id, blocked_by, attempts, error_type, error_message)
    SELECT $1, $2, $3, $7, $8, $9, $10, $11, $12 FROM guard
    ON CONFLICT DO NOTHING
    RETURNING 1)
SELECT (SELECT count(*) FROM guard), (SELECT count(*) FROM ins)
```

- `FOR SHARE` conflicts with the reset's and the claim's `UPDATE` (both take `FOR NO KEY UPDATE`). If the reset or a
  claim got there first, the guard waits for it and re-checks its `WHERE` against the new row version (documented
  behaviour of a locking `SELECT`): no row, nothing inserted. If the guard got there first, the reset waits for the
  autocommit insert and then deletes the row it inserted. Either way no park row outlives a reset or a takeover.
- `guard = 0` is a zero-row write: §2.6 decides, and the event does not count as handled. `guard = 1, ins = 0` is a
  replay that finds it parked, as today.
- It does not create a row version on `el_progress` (a lock only), and parking is rare.
- If I6's park arm (§9.2) shows the CTE's lock does not re-check as expected on some supported major, the fallback is a
  two-statement transaction (`SELECT … FOR SHARE`, then the insert). `failedAgain` gets the same guard.

---

## 4. Exclusive and non-exclusive subscriptions side by side

- **Keys.** Exclusive ⇔ `instance_name = ''` and `exclusive = true`. **Proposed:** a non-exclusive subscription must
  have a non-blank instance name (new validation in `SubscriptionSpec`), so the two can never share a key.
- **No flipping.** `PROGRESS_START` stops writing `exclusive` on conflict; a start whose `exclusive` differs from the
  stored row's refuses to start with a clear error instead of rewriting it.
- **Shared reader.** An owner joins the type's `ReaderLoop` exactly as a non-exclusive subscription does; a standby does
  not join it, so a standby costs no inbox, no read and no catch-up permit. Losing and regaining a lease is a `leave`
  and a later `join`, as phase 3's stop and start are.
- **Writes.** Non-exclusive writes bind a null fence (unchanged behaviour, plus §3.3's epoch guard on the park insert).
  Exclusive writes always bind fence and owner.
- **What phase 7 does not add.** No lease for non-exclusive rows (two JVMs with the same instance name are still phase 2's
  documented hazard, caught for in-transaction progress by phase 4's expected-value guard); no sharing of one lease by
  several subscriptions (EF-2, later, §9); no admin operations on leases (phase 8).

---

## 5. Schema

None. The columns and the sequence are in the repeatable `engine-schema` change already
(`EventLogSchemaContributor.java:147-163`), created with `IF NOT EXISTS`, so every existing database has them. No index
is added on a lease column, so renewals and saves stay HOT. Phase 7 documents (in the contributor's Javadoc) that the
lease columns are written only through `ProgressStore`, and that `ALTER SEQUENCE el_lease_fence_seq RESTART` (or dropping
and recreating it) breaks the fence's guarantee. If the schema rule test needs it, the CI `EventLogGenericPlanIT` adds
`LEASE_CLAIM` and `LEASE_RENEW` to the statements whose generic plan must use the primary key.

---

## 6. Settings

| Setting | Default | Rule | Why |
|---|---|---|---|
| `leaseDuration` | 15 s | > `leaseLocalStop` + `leaseRenewalInterval` | Today's `FencedLock` default, so failover time does not change (§19 item 22) |
| `leaseRenewalInterval` | 4 s | > `leaseStatementTimeout` | Same; also the claim interval of standbys |
| `leaseLocalStop` | 7 s | ≥ `leaseRenewalInterval`, < `leaseDuration` | Lease minus two renewal intervals: one missed renewal is tolerated, two are not |
| `leaseStatementTimeout` | 3 s | whole seconds, < `leaseRenewalInterval` | pgjdbc's query timeout has 1 s granularity |
| `nodeName` | `""` | free text, ≤ 64 chars | Prefix of `lease_owner` for operators |

The constructor validates the rules; `defaults()` takes these values; `withLease(duration, renewal, localStop)` and
`withLeaseStatementTimeout(...)` follow the record's `with…` style. Tests run with 2 s / 500 ms / 1 s / 1 s
(`leaseStatementTimeout` minimum 1 s) so takeovers take a few seconds.

---

## 7. Failure cases

| Case | What happens | Bound |
|---|---|---|
| **Node crash** | No renewal; lease expires by server time; a standby's next claim pass takes it with the next fence. Nothing to clean up | Takeover ≤ remaining lease (≤ 15 s) + one claim interval (4 s) + start-up of delivery |
| **GC pause / stop-the-world** | `nanoTime` runs during the pause, so after it the delivery thread finds the deadline passed and suspends. A pause between the check and a handler's side effect lets that one side effect happen late (best effort, §2.4); every database write it makes is fenced and lands nothing once another node claimed | I16 for the check; I5 for the writes |
| **VM or host suspend** | The monotonic clock may not count while suspended, so the local deadline can be late by the suspend's length (§19 item 22). Safety rests on the fence | Out-of-database side effects only |
| **Clock** | Lease expiry is judged by one database clock (`now()`). JVM wall clock is never used; the deadline uses `nanoTime`. Server-clock steps (NTP correcting) shorten or lengthen leases by the step | Margin `leaseDuration − leaseLocalStop` = 8 s absorbs steps and renewal round trips |
| **DB failover** | Every connection breaks; renewals fail; owners suspend at 7 s. The promoted server judges expiry with its own clock against `lease_until` written by the old one: skew between them adds to or eats the 8 s margin. Async replication can lose recent claims and reissue a fence: §3.2's owner predicate keeps the two holders apart | Requires the hosts' clocks within a few seconds (NTP); documented |
| **Connection loss mid-renewal** | The `UPDATE` may have committed with the reply lost: the JVM treats it as failed, its deadline is not extended (stops earlier than it had to: the safe direction), the next pass renews again, fence unchanged | One renewal interval of unnecessary suspension at worst |
| **Owner partitioned** | Cannot renew → suspends at its deadline, before the lease expires; another node claims after expiry; when the partition heals, its pending writes update nothing, it reads the row once, logs once, goes standby | §9 |
| **Stale page transaction holding the progress row lock** (the owner paused after its fenced update, before commit) | A claimant waits on that lock. If the stale owner resumes and commits, its page landed *before* the claim, which is a correct order. If its connection is dead, PostgreSQL holds the lock until the session ends. **Proposed:** the page transaction sets `SET LOCAL idle_in_transaction_session_timeout = <leaseDuration>`, so a dead owner's open transaction is ended within a lease, and the claim statement uses the keeper's query timeout so the keeper is never stuck behind it | Takeover delayed by ≤ one lease in that case |
| **Pool exhausted** | The keeper waits for a connection like any borrower; the deadline still fires on time, so an owner whose pool is full of its own pages suspends instead of overrunning. Sizing rule in §8 | Recorded as `onRenewalFailed` |
| **Operator deletes the row** | Renewal and saves update nothing; the read finds no row; the subscription stops with an error (§2.6) | — |
| **Operator rewinds the fence or the sequence** | Not reachable through any API. If done anyway, the owner's saves fail and it stops; a stale owner's fence may come round again, which the owner predicate still separates | Documented |
| **Reset while owned** | The reset bumps the epoch without touching the lease; the owner's next fenced write updates nothing, §2.6 step 3 repositions; it stays owner | I6 |

---

## 8. Resource cost

| | Threads | Held connections | Transient connections | Statements |
|---|---|---|---|---|
| Per `EventLog`, phase 6 | 1 platform (reader loop) + 1 platform (listener) + 1 virtual (notifier) | 2 | per catch-up/bridge read, save, page, notify send | — |
| **Added by phase 7, per `EventLog`** | **+1 virtual** (`eventlog-lease`), only while at least one exclusive subscription exists, parked between passes | **0** | **+1 per pass**, every `leaseRenewalInterval`, borrowed and returned at once | **≤ 2 per pass**: one `LEASE_RENEW` for all owned leases, one `LEASE_CLAIM` for all standbys; a JVM that owns everything and has no standbys runs one |
| **Added per exclusive subscription** | 0 (its delivery thread exists already; a standby's is parked) | 0 | 0 | 0 per pass; one more `unnest` element; one HOT row version on `el_progress` per renewal |

The renewal's cost per lease is one primary-key lookup and one HOT update: at 1 000 owned leases and 4 s that is 250
row versions a second for the cluster per JVM, which `fillfactor = 70` and autovacuum absorb; §10.2 measures it. Pool
sizing: phase 6's "2 held plus transient" becomes "2 held, plus one per concurrently open page, plus one for the keeper"
for any `EventLog` with exclusive in-transaction subscriptions. The keeper does not use the reader loop's held
connection (open question 1).

---

## 9. Test plan

Every new rule has an IT seen to fail against a mutant (§16, history §9.13). New mutants go into `WatermarkMutant`,
settable only through `TestSeams`; the keeper's mutants and hooks go on a package-private `EventLog.leaseSeams(TestSeams)`,
as phase 6's `wakeSeams`. New hooks on `TestSeams.Hooks`: `beforeClaim(keys)`, `afterRenewalBeforeConfirm(keys)`,
`beforeRenewal(keys)` (keeper thread); `beforePeriodicSave(subscriber, resumeFrom)`, `beforePeriodicPark(subscriber, order)`,
`beforeHandler(subscriber, event)` (delivery thread). "Another JVM" in an in-JVM IT is a second `EventLog` on its own
`DataSource`. A test-only trigger on `el_progress` (created by the ITs, never by the engine) records every row version
`(resume_from, reset_epoch, lease_fence, lease_owner, txid_current(), clock_timestamp())` into `el_test_progress_log`, so
an IT can assert on the **commit order** of writes, not on what it saw. Unit tests: the `Lease` state machine and
deadline arithmetic with an injected nano clock, settings validation, the zero-row decision table (§2.6).

### 9.1 Gate row I5 (fencing)

`EventLogLeaseFencingIT`, test lease settings (§6). Each arm: owner A on `EventLog` 1, standby B on `EventLog` 2.

| Arm | Shape | Asserts | Must-fail variants |
|---|---|---|---|
| (a) Periodic save after takeover | A holds in `beforePeriodicSave`; its lease is made to lapse (keeper paused by `beforeRenewal`); B claims, delivers 50 events, saves; A is released | A's save updates nothing; the progress log shows no write with A's fence after B's first write; A logs one warning and is `STANDBY` | `UNFENCED_SAVE` (fence bound null: A's save lands and `resume_from` goes backwards) |
| (b) Page after takeover, `CONTIGUOUS` in-transaction | A holds in `afterHandlerBeforeProgress` with its output written; B claims and commits pages; A released | A's page rolls back; the view equals a reference fold (each event once) | `UNFENCED_SAVE` (A's output and progress land: events applied twice) |
| (c) Stale write waiting on the new owner's row lock | A reads its state and holds in `afterHandlerBeforeProgress`; B claims and holds in `afterProgressBeforeCommit` (row locked); A released, its update waits; B commits | A's update re-checks against B's version, updates nothing, rolls back | `FENCE_READ_BEFORE_WRITE` (the fence is checked by a separate `SELECT` in the page transaction before an unfenced update, the shape §9's revision rejected: the check passes before B's claim, the update lands after B's commit) |
| (d) Periodic park after takeover | A's event always fails; A holds in `beforePeriodicPark`; B claims; A released | No park row from A; B parks the event itself with its own attempts | `UNGUARDED_PERIODIC_PARK` (today's `insertNow`) |
| (e) Claim delayed past a newer owner's | A's keeper holds in `beforeClaim`; B claims; A's claim proceeds | A's claim matches nothing; fence unchanged; A stays standby | `CLAIM_IGNORES_LIVE_LEASE` (claim `WHERE` without the lease condition: A steals, B's next save fails) |
| (f) Duplicate fence (failover simulation, §3.2) | After A claims fence `f`, the IT rewinds the sequence (`setval`) and forces A's lease to lapse; B claims and receives `f` again | A's saves and renewals update nothing | `FENCE_WITHOUT_OWNER` |

### 9.2 Gate row I6 (reset wins)

`EventLogResetRaceIT`; every arm once with a non-exclusive and once with an exclusive subscription.

| Arm | Shape | Must-fail variants |
|---|---|---|
| Reset racing a periodic save | `beforePeriodicSave` holds; reset commits; save released → updates nothing, delivery restarts at the reset point; the log shows the reset's row version last for the old epoch | `SAVE_WITHOUT_EPOCH` |
| Reset racing a page commit | Reset while the page is in `afterHandlerBeforeProgress` → page rolls back, output absent; reset waiting on the page's row lock (`afterProgressBeforeCommit`) → page commits first, the reset applies after, the next save updates nothing and repositions | `SAVE_WITHOUT_EPOCH`; `PROGRESS_AFTER_COMMIT` (existing) |
| Reset racing a periodic park (phase-4 gap) | `beforePeriodicPark` holds; reset commits (deletes parked rows); park released → no park row survives | `UNGUARDED_PERIODIC_PARK` |
| Reset racing a release | `stop()` while a reset commits → the release leaves the reset's `resume_from` | `RELEASE_WRITES_ANY_EPOCH` |
| Reset while owned | The reset leaves `lease_*` unchanged; the owner repositions and stays owner; no standby claims | — |

### 9.3 Gate row I16 (leases)

`EventLogLeaseIT`.

| Arm | Shape | Must-fail variants |
|---|---|---|
| Owner that cannot renew stops first | A periodic and an in-transaction owner; the IT blocks renewals (`LOCK TABLE el_progress IN ACCESS EXCLUSIVE MODE` from a side connection, and separately the `beforeRenewal` hook). Right after, the IT reads `lease_until - clock_timestamp()` and maps it onto `nanoTime`. Asserts: the last handler invocation and the last page start are before that instant, and the subscription is `SUSPENDED`; after the lock is released and no one claimed, it resumes with nothing replayed | `NO_LOCAL_DEADLINE` (delivery goes on while renewals fail); `DEADLINE_FROM_RENEWAL_END` (confirmation taken after the reply: with `afterRenewalBeforeConfirm` holding 1.5 s under the test settings the deadline lands after the server expiry) |
| A pass never extends a changed fence | 20 exclusive subscriptions owned by A; the IT forces one lease to lapse and B claims it; A's next pass | A's pass returns 19 keys; the 20th is `LOST` and stops; B's `lease_until` is not extended by A (the log shows only B's renewals) | `RENEW_WITHOUT_FENCE` (renewal keyed by subscription only) |
| Renewal of many in one statement | 200 owned leases | exactly one `LEASE_RENEW` per pass (`pg_stat_statements` for the role) | `RENEW_PER_LEASE` |
| Lost lease leaves the reader | After loss, the subscription is not attached (`ReaderLoop` member count) and holds no inbox | — |

### 9.4 Multi-JVM takeover ITs

`EventLogTakeoverIT` (in-JVM `EventLog`s) and `EventLogForkedTakeoverIT` (forked JVMs). The child is
`LeaseOwnerProcess`, a test main after `spike/SpikeWriterProcess`'s pattern (same classpath, args on the command line,
line protocol on stdout: `C <subscription> <fence>` on claim, `H <global_order> <fence> <nanoTime>` per handled event,
`L <fence>` on loss; ends when stdin closes), but in the main test package. Each child runs one periodic `FRESH` and one
in-transaction `CONTIGUOUS` exclusive subscription on a view table. A writer appends 200 ev/s throughout.

| Scenario | How | Asserts |
|---|---|---|
| Crash | `destroyForcibly()` (SIGKILL) of the owner | Takeover time ≤ `leaseDuration + leaseRenewalInterval + 1 s`; view fold equal to reference; every event delivered ≥ 1 to the periodic subscription |
| Pause (real GC-pause shape) | `kill -STOP <pid>` for 2× the lease, then `kill -CONT` | The resumed child writes nothing that lands (progress log: no write with its fence after the new owner's first); it reports `L`; fold equal |
| Graceful handover | Close the owner's stdin | Takeover ≤ one claim interval + 1 s; no replay beyond the last save for in-transaction |
| Connection loss | `pg_terminate_backend` of every backend with the child's `application_name`, every 2 s, for 30 s | No takeover while renewals succeed in time; if one does happen, fold still equal |
| Rolling chain | A → B → C → A, each stopped gracefully after 10 s | Fences strictly increase in the log; fold equal |

---

## 10. Gate measurement

`spike/EventLogGateSpikeIT`, suite `p7-gate`. Same harness as phase-6 §7: `pg_stat_statements` by role, fresh container
per arm, two runs of every arm, every `getConnection()` timed; a result inside the noise counts as no change (§17). Pass
lines are decided here, before the run.

### 10.1 Takeover time

Default lease settings. 20 repetitions each of crash (SIGKILL), pause (SIGSTOP for 30 s) and graceful stop, forked owner
and in-JVM standby, 100 ev/s. Measured: from the kill or stop to the standby's first handled event (`nanoTime`, shared
across processes on Linux, as phase 6's forked check used). **Pass:** crash p100 ≤ 15 s + 4 s + 1 s; graceful p100 ≤ 4 s
+ 1 s; pause: no landed write by the paused owner in any repetition. **Recorded:** p50/p100 per shape, events replayed
after takeover per model.

### 10.2 Renewal cost per N leases

N = 1, 10, 100, 1 000 exclusive subscriptions owned by one `EventLog`, all idle, 5 min. **Recorded:** server time per
`LEASE_RENEW` (`mean_exec_time`, `max_exec_time`), client pass time and connection-borrow time, WAL bytes per pass
(`pg_stat_wal`), HOT ratio on `el_progress` (`n_tup_hot_upd / n_tup_upd`), table size growth. **Pass:** one renewal
statement per pass at every N; HOT ratio ≥ 0.95; renewal p99 at N = 1 000 below 100 ms (a guard against a plan that walks
the table, not a tuned target).

### 10.3 Append and delivery unaffected

Phase-6 §7.1's steady shape at 100 ev/s with 10 subscriptions (5 `FRESH` periodic, 5 `CONTIGUOUS` in-transaction),
twice: all non-exclusive (phase 6 baseline) and all exclusive. And phase-6 §7.2's append shape at 8 writers. **Pass:**
commit-to-handler p50 and p99 within the noise of the baseline; appends/s within the noise; reader-role statements per
delivered event within the baseline plus the renewal statements (≤ 0.25 statements/s per `EventLog`). **Recorded:**
`el_progress` lock waits seen in `pg_stat_activity` samples (expected: none beyond page commits).

---

## 11. Against the invariants

- **I1, I3.** A new owner starts from the row's `resume_from` (§4.7's value, written only under the owner's fence), and a
  lost owner's writes do not land, so the resume point never passes what the current owner could still deliver.
- **I2, I12, I13.** Unchanged within one incarnation. A takeover is a new incarnation, as a restart is.
- **I5.** Every write of an exclusive subscription asserts fence and owner on the row it writes (§3.1), re-checked after
  a lock wait; tested in §9.1.
- **I6.** Every write asserts the epoch, the periodic park insert included now (§3.3); the release never writes a
  resume point under another epoch (§2.5); tested in §9.2.
- **I9.** The keeper never touches the reader loop; a suspended subscription overflows and detaches like a slow one.
- **I14.** Unchanged; a park row only lands under the current owner and epoch.
- **I16.** The deadline is measured from the start of the last confirmed renewal on the monotonic clock, checked by the
  delivery thread; renewal is keyed by fence and owner; tested in §9.3.
- **I7, I11, I15.** Untouched: the keeper reads no event table and takes no catch-up permit.

---

## 12. Build split

| Unit | Content | Done when |
|---|---|---|
| **A: code** | `Lease`, `LeaseKeeper`, the statements of §2-§3 in `EventLogStorage`, `ProgressStore` lease methods and fence binding, `Subscription` ownership states and deadline checks, the zero-row table of §2.6, the guarded periodic park and `failedAgain`, `PROGRESS_START` no longer flipping `exclusive`, the non-blank instance-name rule, settings (§6), observer methods, `subscribeExclusive` overloads, Javadoc in the contributor (§5), module `CLAUDE.md` paragraph. Unit tests of §9's first paragraph | `mvn test -pl components/postgresql-event-log`; every phase-2 to phase-6 IT passes unchanged (they are all non-exclusive) |
| **B: ITs and mutants** | The mutants and hooks of §9, the progress-log trigger helper, `EventLogLeaseFencingIT`, `EventLogResetRaceIT`, `EventLogLeaseIT`, `EventLogTakeoverIT`, `LeaseOwnerProcess` and `EventLogForkedTakeoverIT`; `EventLogGenericPlanIT` gains the two lease statements | Every gate IT passes and every `must_fail_*` test fails against its mutant, against PostgreSQL 16, 17 and 18 |
| **C: gate** | §10's arms in `p7-gate`, results recorded in a §13 "Results" of this file with a verdict | Each pass line met, or the miss recorded with a decision |

A and B can overlap once A's statements and seams exist; C needs both.

---

## 13. Open questions

| # | Question | Recommendation | Alternatives |
|---|---|---|---|
| 1 | Where renewal runs and which connection it uses | **A `LeaseKeeper` virtual thread per `EventLog`, borrowing one connection per pass** (§8): no held connection, independent of the reader loop, and the deadline does not depend on it anyway | (a) Run renewal on the reader loop's thread and held connection: no borrow, but couples leases to read latency, reader restarts and test hooks that hold the loop. (b) A dedicated held connection: immune to pool exhaustion, costs one connection per `EventLog` forever. (c) One keeper per database per JVM, shared like `CatchUpPermits`: fewer statements with several `EventLog`s on one database, more lifecycle code; revisit if phase 8's starter makes that common |
| 2 | How a standby learns a lease was released | **Poll at the renewal interval** (graceful takeover ≤ 4 s), measured in §10.1 | Send a `pg_notify('el_lease', key)` from the release and have the phase-6 listener wake the keeper: sub-second graceful takeover, but couples leases to tier 1 and does nothing for crashes |
| 3 | Assert `lease_owner` beside the fence (§3.2) | **Yes**: free, and it closes the duplicate-fence case of an async failover | Fence alone, as §9 wrote it; document that fences assume no lost commits |
| 4 | What a subscription does past its local deadline | **Suspend and resume on the next confirmed renewal** (§2.4): no replay when the cause was a slow renewal | Treat it as lost: leave, standby, re-claim (simpler state machine, but every transient renewal failure becomes a replay and a catch-up) |
| 5 | Interrupt a periodic handler at the deadline | **No**: check before each handler call only; record overruns in the observer | Interrupt the delivery thread at the deadline: cuts long side effects short, but an interrupted handler is not handled (phase 4), so it turns slowness into retries and parks |
| 6 | `idle_in_transaction_session_timeout` on page transactions (§7) | **Yes, `SET LOCAL` to `leaseDuration`**, so a dead owner's open page cannot block a takeover past one lease | Rely on TCP keepalive (can be hours); or a shorter value, which would end slow handlers' pages |
| 7 | Periodic park guard's shape (§3.3) | **One statement, a `FOR SHARE` CTE**, proven by I6's park arm on 16-18 | Two statements in a short transaction (certain re-check, one more round trip on a rare path); or a no-op fenced `UPDATE` in a CTE (exact, but writes a row version) |
| 8 | Non-exclusive rows with duplicate instance names | **Leave as phase 2 documented**; phase 4's expected-value guard already catches it for in-transaction progress | Give every non-exclusive row a lease too, so a second JVM with the same instance name waits as a standby: safe, but turns a configuration error into silent idling |
| 9 | Temporary public API shape | **`subscribeExclusive(subscriber, aggregateType, startFrom, …)` overloads** mirroring `subscribe`, no instance name | An `exclusive` flag on the existing overloads (more overloads, and an instance name that must then be ignored) |
| 10 | Lease timings | **Keep §19 item 22's 15 s / 4 s / 7 s** and confirm with §10.1 | Shorter (5 s / 1.5 s / 2 s) for faster failover at three times the renewal writes; tune only if §10.1 shows a reason |

## 14. Decisions (2026-11-16)

The open questions of §13, settled by the maintainer working through the phases autonomously. Every question is settled
as recommended.

| # | Question | Decision |
|---|---|---|
| 1 | Where renewal runs | **A `LeaseKeeper` virtual thread per `EventLog`, borrowing one connection per pass**; one keeper per database per JVM is revisited if phase 8's starter makes several `EventLog`s on one database common |
| 2 | How a standby learns of a release | **Poll at the renewal interval**; a `pg_notify` through the phase-6 listener is revisited if §10.1 shows graceful takeover matters |
| 3 | Assert `lease_owner` beside the fence | **Yes** |
| 4 | Past the local deadline | **Suspend, resume on the next confirmed renewal** |
| 5 | Interrupt a periodic handler at the deadline | **No**; check before each call, record overruns in the observer |
| 6 | `idle_in_transaction_session_timeout` on page transactions | **Yes, `SET LOCAL` to `leaseDuration`** |
| 7 | Periodic park guard's shape | **One statement with a `FOR SHARE` CTE**, proven by I6's park arm on PostgreSQL 16-18; fall back to two statements in a short transaction if it is not |
| 8 | Duplicate instance names on non-exclusive rows | **Leave as phase 2 documented** |
| 9 | Temporary public API | **`subscribeExclusive(...)` overloads**, removed or kept with the rest of the temporary surface in phase 8 |
| 10 | Lease timings | **15 s lease / 4 s renewal / 7 s local deadline**, confirmed by §10.1 |
