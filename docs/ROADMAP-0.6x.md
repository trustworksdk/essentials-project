# Essentials 0.6x roadmap

The open fixes and improvements planned for the 0.6x line. Every item here was checked against the code on
2026-10-08 (0.60.0 is tagged `bcc17f35`), and only items that are still open or partly done are listed.

**How to use this file**

- The bucket names the earliest release an item may ship in. Project rule: breaking changes only in a new major;
  patch and minor releases are additive.
- The id in parentheses after each title (e.g. `ES-4`, `N-9`) is a stable label from the 0.60.0 maintainer review;
  `NW-n` ids come from the 2026-10-13 test-speed wave, whose flaky-test fixes surfaced them.
  `RW-n` ids come from the `feat-slice-atlas` code review: RW-1 to RW-11 from its 2026-10-14 fix waves, RW-12 onwards
  from the findings still open on 2026-10-27, checked against the code that day. Each names the review finding
  (`F-NNN`) it came from, so the finding can be closed when the item ships. The finding files are not committed, so
  each entry says everything needed to pick it up.
  `DU-n` ids come from dependency-update rounds (`/tw-jvm:deps`): a bump that was tried, failed and was held back.
  Put it in the commit message that fixes the item, so the history can be searched by it.
- When an item ships, delete it here and add its entry to `docs/NEXT_VERSION_RELEASE.md` in the same commit. When an
  item is partly done, rewrite it to say only what remains.
- Each entry has three parts: the problem, why it matters, and the planned fix. Where the plan names an API, the name
  is a proposal, not a commitment.

**Scope of 0.60.1** (decided 2026-10-08): everything in section 0 and P1, plus three P2 items: ES-4
(`awaitProcessed`), DD-2 (the reserved-word check) and CB-4 (unhandled event types). Two P1 items ship softened,
because a patch must not reject a setup that works today:

- N-15's "the shard-owned builder requires a UnitOfWork factory" becomes a WARN.
- QL-3's redelivery-policy validation WARNs in 0.60.1 and fails from 0.61.

Added to 0.60.1 on 2026-10-08:

- **CB-4 per processor type:** only `ViewEventProcessor` WARNs about an ignored event type (with an
  `@IgnoresEventTypes` opt-out); `EventProcessor` and `InTransactionEventProcessor` log at DEBUG and count, because
  skipping is normal there. A view's handled and unhandled types are summarised at startup.
- **N-11 scope:** `essentials.immutable-jackson-module-enabled` defaults to `true` for the persistence mapper only;
  Boot's web mapper keeps the 0.60.0 behaviour (module only with an explicit `true`).
- **A startup report:** the Essentials startup findings are collected and printed as one block when the
  application is ready.
- **All of P3 (documentation)**, plus a fix for the flaky `StarterAutoConfigurationIT.verify_api_beans`.

---

## 0. Planned for 0.60.0 but never shipped

All shipped in 0.60.1; see `docs/RELEASE-NOTES-0.60.1.md`. One planned fix was rejected rather than shipped:
making objenesis a compile dependency of `immutable-jackson3` (N-5, SB-1). Every third-party dependency of a module
or starter stays `provided`, by design; the plugin's S2.1 table and its generated POM declare objenesis, and mapper
creation fails naming it when it is missing (SB-2).

---

## P1: 0.60.x patch releases

Bug fixes only; no API breaks. Everything planned here before the 2026-10-14 review fix waves shipped in 0.60.1;
the items below are still open.

**`afterRollback` is never called on the Spring event-store unit of work (RW-1).**

- **Problem:** `SpringTransactionAwareEventStoreUnitOfWorkFactory` does not override
  `afterRollbackAfterCallingLifecycleCallbackResources`, so `PersistedEventsCommitLifecycleCallback#afterRollback` is
  never called on the Spring path, for any rollback. `EventStoreManagedUnitOfWork` (the JDBI path) calls it, and
  `EventStoreEventBus` implements it.
- **Consequence:** a callback that undoes in-memory work when events it saw in `beforeCommit` are rolled back is never
  told about the rollback in a Spring application. Found while pinning F-122: the assertion that the callback received
  the rolled-back event in `afterRollback` fails.
- **Fix:** override the method so it hands the units of work's `beforeCommitEventsPersisted` and
  `afterCommitEventsPersisted` to each callback's `afterRollback`, then clears them. Put the `afterRollback` assertion
  back into `SpringTransactionAwareEventStoreUnitOfWorkFactory_RollbackOnlyIT` (its recording callback needs an
  `afterRollbackEvents` list). User-visible: release-notes entry under bug fixes.

**A stop while the fenced lock is still being acquired can strand the lock (RW-11; review F-843).**

- **Problem:** `DBFencedLockManager.cancelAsyncLockAcquiring` (`components/foundation/.../fencedlock/DBFencedLockManager.java:1025`)
  removes and cancels the acquiring future, then releases the lock only if `locksAcquiredByThisLockManager` holds it
  at that moment. It does not take `reentrantLock`, so it does not wait for an acquiring tick that is already running
  (`asyncLockAcquiringTick`, line 959). The first tick runs as soon as `acquireLockAsync` schedules it (initial delay
  0), so a `stop()` straight after `start()` usually lands while that tick is in flight. The tick then completes:
  it records the lock (line 983) and calls the cancelled caller's `lockAcquired` (line 985). Nothing releases that
  lock until a later cancel for the same lock name (a `stop()` after a restart, line 1031-1034) or the lock manager's
  own `stop()` (line 508), and the lock confirmation keeps confirming it. Seen with an exclusive
  `InTransactionEventProcessor` (`start()`, `stop()`, `start()`): the stopped subscription's `lockAcquired` ran after its `stop()` and registered
  its handler on the local event bus again (`ExclusiveInTransactionSubscription.java:81-89`); the subscription
  `start()` created never became active. An `ExclusiveAsynchronousSubscription` gets `onLockAcquired` the same way
  (`ExclusiveAsynchronousSubscription.java:114`); its `request` refuses while it is not started (line 286).
- **Consequence:** an exclusive subscription stopped while that tick is in flight leaves this instance holding its
  lock, so no other instance can take the subscription over until that lock is released. Started again, it reports
  itself started while `isActive()` stays false: the new subscription's ticks find the lock already held by this
  instance and do nothing. For an exclusive `InTransactionEventProcessor` the stopped processor keeps handling events
  appended on this instance, in their transactions, until its lock is released: the late `lockAcquired` sets `active`
  and re-adds its local-event-bus handler, and neither `ExclusiveInTransactionSubscription.onEvent` nor
  `InTransactionEventProcessor.invokeHandler` checks whether it is started. In the run above, the next `stop()`
  released the lock. The `EventProcessorIT` restart tests wait for the processor to be
  active before they stop it, so they do not cover this.
  `ViewEventProcessor`, `Inboxes`, `Outboxes` and `DefaultEssentialsScheduler` call `cancelAsyncLockAcquiring` too;
  whether they end up the same way was not checked.
- **Fix:** make the cancel and the tick exclusive. Either `cancelAsyncLockAcquiring` takes `reentrantLock` around
  its release check, or the tick hands the lock to its callback only while its own future is still the one in
  `asyncLockAcquirings`, and releases it otherwise. Pin it with a `start()`, `stop()`, `start()` loop that asserts
  the restarted processor becomes active and that the stopped processor handles no event appended after its `stop()`. User-visible: release-notes entry under bug fixes.

---

## P2: next 0.6x minor

Additive features.

### Turn the 0.60.1 warnings into failures

0.60.1 shipped three checks as a WARN, because a patch must not reject a setup that works today. In 0.61 they fail:

- **The shard-owned queue builder requires a UnitOfWork factory (N-15).** Without one, every enqueue commits on its
  own, outside the caller's transaction. 0.60.1 logs a WARN at build time.
- **Redelivery-policy builders reject an incomplete policy (QL-3).** Unset delays, a multiplier below 1.0 or unset,
  and a `maximumNumberOfRedeliveries` never set explicitly. 0.60.1 logs one WARN naming each setter; 0.61 throws
  `IllegalArgumentException`.
- **A command type registered twice fails at construction (CB-3).** 0.60.1 WARNs when an
  `EventStreamDeciderAndAggregateTypeConfigurator` registers a command type that another of its adapters already
  handles. A complete check needs a way to list a `CommandBus`'s handlers, e.g. a
  `findCommandHandlersCapableOfHandling(Class)` default method; today the configurator tracks its own adapters per
  bus.

### Command bus

**Mark a Spring-managed transaction rollback-only when a command fails (CB-5).**
`SpringTransactionAwareUnitOfWork.markAsRollbackOnly` only logs at DEBUG when Spring manages the transaction. So
inside a `@Transactional`, a caller that catches the exception from `commandBus.send` commits whatever the handler
wrote before it threw, and a typed `Decider`'s `HandlerResult.error` mark is lost the same way. Plain Spring marks the
shared transaction rollback-only when an inner `@Transactional` method throws. **Fix:** mark the Spring transaction
rollback-only too (`TransactionStatus.setRollbackOnly()`). Not in a patch: a caller that catches today would then get
`UnexpectedRollbackException` at commit, so it needs a migration note. 0.60.1 documents it as `ESS-126`.

### Event store

**A cross-instance `awaitProcessed` (ES-4 follow-up).** 0.60.1's `awaitProcessed` sees only the processor in this
JVM: a `ViewEventProcessor` whose fenced lock another instance holds times out. **Fix:** fall back to the persisted
resume point and the shared durable queue when the lock is held elsewhere, so a caller on any instance can wait.

**Typed event-order ranges (ES-1).**

- **Problem:** the event-store read APIs take an untyped `LongRange` for both per-stream `EventOrder` and cross-stream
  `GlobalEventOrder` ranges. Mixing them up compiles, and the result is a silent wrong read.
- **Fix:**
  - Add `EventOrderRange` and `GlobalEventOrderRange` to `foundation-types`, with typed overloads on `EventStore` and
    on the `FetchStream`/`LoadEventsByGlobalOrder` builders.
  - Mark the `LongRange` consumer entry points and `GlobalEventOrder.rangeFrom(...)` `@Deprecated`, not
    `forRemoval`.
  - The interceptor SPI stays on `LongRange` until the next major.
- Builds on the empty-range fix (`LongRange.isEmpty()`, shipped in 0.60.1).

**A state-based decider with snapshots (ES-9).** `EventStreamDeciderCommandHandlerAdapter` fetches and replays an
aggregate's whole event stream on every command and cannot use snapshots. Long-lived streams therefore get slower
with every event. `EventStreamDecider.handle(COMMAND, List<EVENT>)` receives raw history rather than folded state, so
a snapshot cannot simply be swapped in. **Fix:** add a state-based decider variant that decides over the `STATE`
folded by `EventStreamEvolver`, wired with an optional `AggregateSnapshotRepository`.

**The snapshot store reports `EventOrder(-1)` instead of "no snapshot" (RW-2).**
`PostgresqlAggregateSnapshotStore.findMostRecentLastIncludedEventOrder` runs `SELECT coalesce(MAX(...), -1)`, so for an
aggregate with no snapshot it returns `Optional.of(EventOrder(-1))`; the `AggregateSnapshotStore` javadoc promises an
empty `Optional`. The built-in `AddNewAggregateSnapshotStrategy` maps empty to -1 itself, so it behaves the same either
way, but a custom strategy that tests `isPresent()` believes a snapshot exists. Present since 2022. **Fix:** return
empty when no row matches (drop the `coalesce`, map a null `MAX` to empty), with a test on both stores' implementations.
It changes what a custom strategy receives, so it belongs in a minor with a release-notes entry. Found while writing
`AsyncAggregateSnapshotRepositoryAfterCommitIT` (F-121).

**One type-argument resolver, in `shared` (RW-5; review F-093, F-095, F-096).**

- **Problem:** 0.60.1 fixed command-type resolution for deciders in a module-local helper,
  `EventStreamDeciderCommandTypes.resolveCommandTypeArgument`, which walks superclasses and sub-interfaces and maps type
  variables through each parameterisation. The general resolver it replaced,
  `GenericType.resolveGenericTypeForInterface` in `shared`, keeps the old limitation: it inspects only the class's own
  `getGenericInterfaces()`. It is still used by `modern.AggregateRoot.resolveStateImplementationClass` and
  `SpringTransactionAwareUnitOfWorkFactory.resolveUnitOfWorkType`.
- **Consequence:** a subclass of a concrete aggregate (`class SpecialOrder extends Order`, where `Order implements
  WithState<...>`) still fails with an `IllegalStateException`. And the helper was made `public`, in the regular
  `adapters` package, only so the parent package can call it, so consumers can now compile against it; no LLM doc
  mentions it.
- **Fix:** an additive `GenericType` method returning `Optional`, for example
  `resolveTypeArgument(Class<?> forType, Class<?> genericSupertype, int typeArgumentIndex)`, used by all three callers.
  Then remove `EventStreamDeciderCommandTypes` from the public API (make it package-private, or fold it into the new
  resolver). 0.60.1 shipped it `public` and `@Deprecated(forRemoval = true, since = "0.60.1")`, so the removal can go
  in the next minor.

### Observability

**Subscription-lag health indicator and fenced-lock metrics (OB-1).** A subscription that has stalled produces no
health signal, and nothing reports fenced-lock ownership. A stalled projection or a lock nobody holds therefore
shows up only as stale data.

**Fix:**

- Add an opt-in `SubscriptionLagHealthIndicator`, modelled on `DurableQueuesHealthIndicator`, with thresholds unset
  by default. On an instance that does not own the subscription it reports UNKNOWN, never DOWN, because the current
  event order is local to an instance.
- Add Micrometer meters for fenced locks: held, acquired, released and lost, per lock name and per manager instance.

**Count only real sweep recoveries (NW-4).** The shard-owned engine's `sweepRecoveries` statistic also counts a row
that committed between a pass's cursor read and its head sweep (two statements, two snapshots). Nothing was stepped
over, and the counter's Javadoc already says a recovery is "not a defect by itself", but a dashboard alerting on it
fires on harmless rows, most often in the start-up sweep. **Fix:** count only swept rows with `seq <= safeCursor`, and
say in the release notes that the counter's meaning narrowed.

### Database schema at startup

**Raise the PostgreSQL floor in code (NW-1).** From 0.60.1 the supported PostgreSQL is 15+ (README § Version
Compatibility), but the code still carries paths for older servers. **Problem:** on PostgreSQL below 14,
`ListenNotify` drops and re-creates the change-notification trigger. The schema contribution does it on the one boot
that has to change the trigger, and the public `addChangeNotificationTriggerToTable` does it on every call. Either can
deadlock with instances using the table, which 0.60.1 fixed for 14+ only. **Fix:** remove the pre-14 branches (the
`server_version_num` check in the trigger `DO` block, the drop-and-create in `addChangeNotificationTriggerToTable`),
drop `ListenNotifyPostgresql12IT` and `MultiTableChangeListenerPostgresql12IT`, and log a WARN at startup on a server
below the supported floor. Not in a patch: a 0.60.x deployment on PostgreSQL 13 or 14 keeps working.

**Index DDL without a table lock on every boot (NW-2).** `CREATE INDEX IF NOT EXISTS` takes a SHARE lock on the table
even when the index exists, so every startup briefly stalls the queue's and the event store's writers while the
schema transaction runs. It can no longer deadlock (0.60.1), but the stall grows with the length of that transaction.
**Fix:** check `pg_indexes` first, in a `DO` block like the trigger's, and only create a missing index.

**Lock order of the event store's startup transaction (NW-3).** The event store applies several event tables in one
schema transaction. A user transaction that writes those tables in the opposite order could, in theory, deadlock with
a starting instance. Not reproduced. **Plan:** confirm or rule out with a test like `QueueSchemaReapplyConcurrencyIT`
first; if confirmed, apply each table's changes in its own transaction, or take the table locks in a fixed order.

### Messaging

**Application LISTEN/NOTIFY channels (LN-1).**

- **Problem:** an application cannot use PostgreSQL LISTEN/NOTIFY for its own signals (for example, evicting caches
  across instances) without bending `MultiTableChangeListener`. That listener shares one poll thread, one connection
  and one duplicate filter with the framework's wake-ups.
- **Consequence:** one failing or slow application handler can drop or stall framework notifications.
- **Fix:** a separate foundation component, `PostgresqlNotificationChannels`, that owns one connection, optionally
  from a dedicated `DataSource`.
  - `subscribe(channel, Class<P>, Consumer<P>)`: dispatched off the poll thread and isolated per channel. Channel
    names are lower-cased with `Locale.ROOT`, and de-duplication happens only on request.
  - A static `publish(Handle|Connection, channel, Object)`: serializes with `JSONSerializer`, guards the 8000-byte
    payload limit, uses bind parameters and joins the caller's transaction, so delivery happens on commit.
  - Liveness checks with a WARN on connection loss, a `ListenerReconnected` signal, and opt-in health and metrics.
  - The Javadoc states that delivery is at most once, with no replay across a reconnect.
- Do not build it on `MultiTableChangeListener`. Reuse the LISTEN-connection loop it got in 0.60.1 (liveness probe,
  re-LISTEN on reconnect, close on stop) by extracting it.

**Keep a pull session's error on a shard-owned live row (RW-3; review F-128).** A pull session's failure
(`RowLeaseQueueSession.fail`, `ShardQueueSession`) counts an attempt but discards the error, because the live lanes have
no `last_error` column. When such a message later reaches its limit at a takeover, the takeover's dead-letter pass can
only store the generic `EXHAUSTED_BY_TAKEOVERS` text (reworded in 0.60.1 so it no longer claims that no failure was
recorded). **Fix:** add `last_error text` to both live tables (foundation's `PostgresqlSchemaStatements.addColumnUnlessPresent` in
`ShardOwnedSchema`'s statements, which the engine's `ShardOwnedSchemaContributor` carries into the schema harness);
have the session fail paths and the owner's retry
path store `DeliveryErrorText` (`failSessionRow`, `countAndScheduleRetryIfOwned`); and have the takeover move write
`COALESCE(stored error, EXHAUSTED_BY_TAKEOVERS)`. Check the schema golden tests and the WAL cost of the wider row on the
failure path only.

**A MongoDB queue handler does not run in a unit of work (RW-4).** `PostgresqlDurableQueues` handles each message in a
unit of work of its own (`SingleOperationTransactionDurableQueuesInterceptor`), so a handler's writes commit or roll
back with the message's outcome. `MongoDurableQueues` has no counterpart, and `DefaultDurableQueueConsumer` opens none,
so with `SpringMongoTransactionAwareUnitOfWorkFactory` a handler's writes are transactional only if the handler opens a
unit of work itself (`MongoSpringRollbackOnlyTransactionInQueuedMessageHandlerIT` does exactly that). The module's
`CLAUDE.md` claimed parity until 2026-10-14. **Fix:** decide between parity (a handle-message interceptor for
`MongoDurableQueues` when a transaction-aware factory is configured, opt-in in a minor because it changes when a
handler's writes commit) and documenting the difference in `LLM/LLM-springdata-mongo-queue.md`.

**A configurable timeout for the queue consumer's fetch (RW-6; review F-007).** `DefaultDurableQueueConsumer`'s poll
has a commented-out `.timeout(Duration.ofSeconds(5))` and a `TODO: In the future support configurable timeout`. A fetch
that hangs (a stalled connection without a socket timeout) holds the consumer's poll thread indefinitely. **Fix:** an
optional fetch timeout on `ConsumeFromQueue`, unset by default, and remove the TODO.

### Fenced locks

**Exclusive acquire (QL-1).** Add `tryAcquireLockExclusively(LockName)`. It returns empty when this manager instance
already holds the lock, so a second caller in the same JVM cannot share, and then release, the first caller's lock.
Changing the default is deferred to the next major.

**An acquire inside the caller's unit of work (NW-13).** Since 0.60.1 a lock is recorded as acquired only after its
acquire has committed, and a failed commit is resolved by looking the row up. That holds only when the lock manager
owns the unit of work. A `tryAcquireLock`/`acquireLock` called while the caller already has one active joins it, so the
lock is recorded before the caller commits; if the caller then rolls back, this instance holds a lock the database never
gave it, and a later confirmation can release another instance's lock under the same token. Either always acquire in a
unit of work of the lock manager's own (a new transaction), or record the lock from an after-commit callback of the
joined unit of work. Decide whether a caller relies on the acquire joining its transaction first.

**Database round trips under the manager-wide lock (NW-14).** `DBFencedLockManager`'s `reentrantLock` is held for the
whole of every blocking acquire, the confirmation tick and the async acquiring tick, and `usingUnitOfWork` and
`withUnitOfWork` take it for their own round trip too. An acquire can hold it across two round trips (the acquire and,
when its commit fails, the outcome lookup), so a slow database delays every other acquire and the confirmation of
locks already held; today the only mitigation is a `lockTimeOut` well above the confirmation interval plus a few slow
round trips, documented on the field. Give confirmation its own lock, or do the DB work outside the lock and take it
only to update `locksAcquiredByThisLockManager`. Either way the protected `usingUnitOfWork`/`withUnitOfWork` helpers
change what they guarantee to subclasses, so check every in-repo subclass and say so in the release notes. Needs the
fenced-lock ITs on PostgreSQL and MongoDB, and a test with a slow storage that shows confirmation is no longer held up. Review finding F-078 (`feat-slice-atlas`) is this item.

RW-12 to RW-18 concern `DBFencedLockManager` (`components/foundation/.../fencedlock/DBFencedLockManager.java`) and its
interfaces in the same package, as changed on the `feat-slice-atlas` line: a `stop()` now keeps every async registration
and `start()` resumes it, and an ended lock confirmation refuses every acquire until a stop and start.

**An async acquire while the lock manager cannot acquire is dropped without a trace (RW-12).**

- **Problem:** `DBFencedLockManager.acquireLockAsync` keeps a call made while the lock manager is stopped only when a
  `stop()` kept a registration for the same lock name: the new callback then replaces the kept one, and `start()`
  schedules it. For a lock name with no registration, the call falls through to the
  `!started || stopping || shutdown != null || executor == null` check and is dropped with a DEBUG line. A registration
  is missing exactly when the previous owner behaved well and called `cancelAsyncLockAcquiring` in its own stop, which
  `an_async_acquiring_cancelled_while_the_lock_manager_is_stopped_is_not_resumed_by_its_start` pins. Separately,
  `acquireLockAsync` does not check `confirmationEnded`, while `_tryAcquireLock`, the retry loops of
  `tryAcquireLock(name, timeout)` and `acquireLock`, and `asyncLockAcquiringTick` do. Each entry point checks its own
  subset of the five lifecycle flags (`started`, `stopping`, `paused`, `shutdown`, `confirmationEnded`) by hand, so a
  missing check reads the same as a deliberate one.
- **Consequence:** a component started before the lock manager, or recreated while it is stopped, gets its lock if its
  predecessor leaked the registration, and never gets it if the predecessor cleaned up. Nothing above DEBUG says so.
  The Leadership Election example in `LLM/LLM-foundation.md` documents this trap instead of avoiding it (RW-19). After
  the confirmation has ended, `acquireLockAsync` registers an acquiring whose ticks log at DEBUG and do nothing, and
  `stop()` drops it.
- **Fix:**
  - Keep a call made while stopped for every lock name: `computeIfAbsent` a kept `AsyncLockAcquiring` with an
    already-cancelled sentinel future, so `start()`, `stop()` and `shutdownStarting` never meet a null future, and let
    `start()` schedule it like any other kept registration. If that is not wanted, log the dropped call at WARN, and
    state in the `acquireLockAsync` javadoc that a call while stopped is kept only for a lock name with a kept
    registration. Either way, add a test for the cancelled-predecessor case that asserts the chosen outcome.
  - Collect the refusal rule in one private method, for example
    `Optional<String> acquireRefusedBecause(boolean holdingReentrantLock)`, which returns the reason (stopping,
    shutting down, confirmation ended). Call it from every acquire path, build the log lines and
    `IllegalStateException` messages from the reason, and decide there whether `acquireLockAsync` registers after the
    confirmation has ended. That only makes sense if the registration is kept for the next start; otherwise refuse it
    like the other acquires. If the per-site checks are deliberate, say so in the `confirmationEnded` field javadoc.
  - It changes what a call while stopped does, so it needs a release-notes entry.

(review findings F-931, F-947, F-953)

**Replacing a kept async registration is inferred, and has side effects (RW-13).**

- **Problem:** `start()` resumes every registration the last `stop()` kept and marks it `keptByStop`. A later
  `acquireLockAsync` with another callback for that lock name calls `replaceKeptAsyncLockAcquiring`, which calls
  `cancelAsyncLockAcquiring` and then registers the new callback. Five things follow from how that is built:
  - `start()` reschedules the kept entry through `scheduleAsyncLockAcquiring`, whose first tick runs at delay 0. A
    component recreated across the stop and start registers its new callback only in its own start, after the lock
    manager's. So the resumed tick usually acquires the lock first and calls `lockAcquired` on the discarded
    component's callback; the replacement then releases the lock and calls that callback's `lockReleased`. The unit
    tests pin this round trip (`first.acquired` reaches 2 before the replacement).
  - `keptByStop` is final, and nothing clears it, not even a call with the kept callback itself. For the rest of a
    registration's life after any stop and start, a call with another callback for the same name (a second component
    misconfigured with that name, or code that builds a new callback on every call) releases the lock under the
    running holder and takes over. For a registration made since the last start, the same call does nothing. The only
    trace is a DEBUG line in `replaceKeptAsyncLockAcquiring`.
  - The release goes through `cancelAsyncLockAcquiring`, which releases whatever `locksAcquiredByThisLockManager` holds
    for the name. That includes a lock this instance took with a blocking `acquireLock` or `tryAcquireLock` while the
    kept acquiring was only waiting, so another caller's `acquireLockAsync` releases it as a side effect.
  - Nothing records why replacement is inferred from callback identity and `keptByStop`. An earlier commit on the same
    line shipped the opposite contract in its release note ("the repeated call does nothing ... to register a
    different callback, call `cancelAsyncLockAcquiring` first"), and opt-in retention was the other option considered.
  - `FencedLockManager.acquireLockAsync` says that what happens across a stop and start, and on a repeated call, "is up
    to the implementation", and links to `DBFencedLockManager`. It does tell a component to call
    `cancelAsyncLockAcquiring` in its own stop, but code written against the interface cannot tell what else to rely on.
- **Consequence:** every restart that recreates components pays an acquire-then-release round with an extra fence
  token, and the discarded component starts work (a consumer, say) on an instance that was already stopped. Whether a
  call silently takes a held lock away from its holder depends on whether the lock manager was ever restarted. A
  maintainer cannot tell whether the identity check is load-bearing or a stopgap.
- **Fix:**
  - Schedule a resumed entry with an initial delay of `lockConfirmationInterval`: pass the delay from `start()` into
    `scheduleAsyncLockAcquiring`, and keep 0 for new registrations. A component that registers again during its own
    start then replaces the kept registration before it acquires, and the first tick no longer waits for
    `reentrantLock` behind `start()`.
  - Log the replacement at WARN, naming the lock and the lock manager instance. Consider making the flag a volatile
    field that a call with the kept callback clears, so the window closes once the owner proves it is alive. That
    changes the second step of
    `acquireLockAsync_with_another_callback_replaces_the_callback_of_an_async_acquiring_resumed_by_start`.
  - Release on replacement only a lock the kept acquiring took: set a volatile flag on `AsyncLockAcquiring` right after
    `asyncLockAcquiringTick` records the lock in `locksAcquiredByThisLockManager`, and release only when it is set.
    Otherwise say in the `acquireLockAsync` javadoc that any lock this instance holds under the name is released.
  - Record the rationale in the javadoc of `replaceKeptAsyncLockAcquiring` or of the `asyncLockAcquirings` field: a
    recreated component does not know that a stop kept its predecessor's registration; and name what ruled out the
    cancel-first contract and opt-in retention (for example, existing callers that cannot change in a minor).
  - State on `FencedLockManager.acquireLockAsync` what holds for every implementation: an acquiring that was not
    cancelled may be resumed with its original callback after a stop and start, and a repeated call with another
    callback may replace it. Keep the database-specific detail on `DBFencedLockManager`.
  - Update the README and `LLM/LLM-foundation.md` accounts of the replacement (see RW-20), and add a release-notes
    entry. RW-18 adds the missing test for a replacement while another node holds the lock.

(review findings F-952, F-954, F-955, F-958, F-959)

**Callbacks and event subscribers run under the lock manager's own lock (RW-14).**

- **Problem:** two paths in `DBFencedLockManager` run user code while `reentrantLock` is held.
  - `acquireLockAsync` holds it while `replaceKeptAsyncLockAcquiring` calls `cancelAsyncLockAcquiring` (RW-13). That
    release does the database round trip and runs the replaced callback's `lockReleased` on the thread of whoever
    called `acquireLockAsync`. The comment in `start()` describes `cancelAsyncLockAcquiring` as not taking
    `reentrantLock`; this path breaks that assumption.
  - `releaseHeldLocksLocallyAsTheConfirmationEnds` publishes `FencedLockEvents.LockConfirmationEnded` from the catch
    block of `confirmAllLocallyAcquiredLocks`, which runs before the `finally` that unlocks `reentrantLock`. So every
    synchronous subscriber runs on the confirmation thread with the lock held. Neither the event's javadoc nor the
    class javadoc says so.
- **Consequence:** `confirmAllLocallyAcquiredLocks` takes the same lock, so no other lock this instance holds is
  confirmed while a slow `lockReleased` runs (one that stops a consumer and waits for in-flight handling), which pushes
  those locks toward the lock time-out. The obvious reaction to `LockConfirmationEnded` is the recovery the class
  javadoc recommends, a stop and start of the lock manager: a subscriber that hands it to another thread and waits for
  it deadlocks, because `stop()` blocks on `reentrantLock`. A slow subscriber also blocks every acquire and release on
  this instance while it runs.
- **Fix:** run both outside the lock. In `acquireLockAsync`, decide on the replacement under the lock, unlock, call
  `replaceKeptAsyncLockAcquiring`, then take the lock again and re-run the started checks and the `computeIfAbsent`.
  Have `releaseHeldLocksLocallyAsTheConfirmationEnds` return the event (or the released lock names) instead of
  publishing it, and publish it in `confirmAllLocallyAcquiredLocks` in a nested try after `reentrantLock.unlock()`,
  still adding a subscriber failure to the rethrown error as suppressed. If either must stay under the lock, say in the
  `acquireLockAsync` and `LockConfirmationEnded` javadoc that the code runs under the lock manager's internal lock and
  must not wait for work that needs the lock manager, such as `stop()` on another thread. Belongs with NW-14 (database
  round trips under the same lock): doing both together avoids reworking the locking twice.

(review findings F-956, F-965)

**The WARN for a resumed registration fires on the documented recovery, and names no owner (RW-15).**

- **Problem:** `DBFencedLockManager.start()` logs every registration it resumes at WARN: "A component that no longer
  wants the lock must call cancelAsyncLockAcquiring when it stops, or its callback is given the lock again". A stop and
  start of the lock manager is also the documented way back from an ended lock confirmation (class javadoc, "The state
  left behind", and `LockConfirmationEnded`), and on that path every component is alive and rightly keeps its
  registration. The WARN names the owner by `kept.lockCallback().getClass().getName()`. `LockCallbackBuilder` builds
  through `LockCallback.lockCallback`, which returns an anonymous class, so every builder-made callback logs as
  `LockCallback$1`. That covers `ViewEventProcessor`, `ExclusiveAsynchronousSubscription`, `DefaultEssentialsScheduler`,
  `Inboxes` and `Outboxes`, most of the framework's own lock users.
- **Consequence:** the recovery logs one WARN per event processor, subscription, inbox, outbox and scheduler, each
  suggesting a leak, on the path where an operator reads the log most closely. That hides the real leak the WARN was
  added to expose, and when there is one, the class name does not say which component left it.
- **Fix:** record in `stop()` whether `confirmationEnded` was set, and on the following `start()` log the resumes at
  INFO when it was. Or log one INFO summary that lists the resumed lock names, and keep the WARN for a registration
  whose callback has not been registered again within one `lockConfirmationInterval` (this fits the delayed first tick
  in RW-13). To name the owner, also log the callback's `toString()` and give the callback `LockCallback.lockCallback`
  builds a `toString()`; better still, let `LockCallbackBuilder` take an optional owner description that the framework
  components set (the processor or inbox name). Update the class javadoc and
  `start_logs_each_resumed_async_acquiring_at_warn_naming_the_lock_and_the_callbacks_class`, and add a case that
  resumes a builder-made callback and asserts that the WARN names more than the anonymous class.

(review findings F-963, F-964)

**Releasing a `lookupLock` copy or a stale `FencedLock` does nothing, silently (RW-16).**

- **Problem:** `DBFencedLockManager.releaseLock` now releases only when `holds(lock)`, an identity check against the
  instance this lock manager holds; before, it was enough that a lock of that name was held. So `release()` on the
  `FencedLock` that `lookupLock` returns, or on a stale instance, does nothing. There is no log line at any level, and
  `release()` returns `void`. `FencedLock.release()`'s javadoc says that releasing the copy does nothing, but its first
  sentence still says "Only works if `isLockedByThisLockManagerInstance()` is true", and for a copy of a lock this
  instance holds, `DBFencedLock.isLockedByThisLockManagerInstance()` returns true. The code now has three notions of
  the same lock: `equals` compares the name, the local release compares identity, and the release in the database
  compares name and fence token. Neither the `FencedLockManager.lookupLock` javadoc nor the lock status section of
  `LLM/LLM-foundation.md` mentions it. No test calls `release()` on a `lookupLock` result
  (`DBFencedLockManagerLostLockReleaseTest` pins only the stale-instance half).
- **Consequence:** code written against the old behaviour, such as `lookupLock(name).ifPresent(FencedLock::release)`
  in an admin or clean-up routine, silently keeps the lock held, and nothing in the logs shows it. A later change to
  `holds` could change the announced behaviour without a test failing.
- **Fix:** pick one identity for an acquisition and state it in `FencedLock.release()`.
  - Option A, closer to the fencing model: in `releaseLock`, resolve the argument to the held instance when its name
    and fence token both match, and release that instance, so its callbacks run and `LockReleased` is published once.
    A stale instance (older token) still releases nothing. Every case the class javadoc gives for the identity check
    carries a newer token, so name plus token separates them just as well.
  - Option B, keep identity: rewrite the first sentence to say that `release()` works only on the instance the acquire
    returned or passed to `lockAcquired`, and that `isLockedByThisLockManagerInstance()` on a `lookupLock` copy is a
    snapshot of the row, not proof that the object can release the lock. Say the same on `lookupLock` and in
    `LLM/LLM-foundation.md`.

  Either way, log a release that does nothing: at WARN when this instance holds the name under another instance (a
  `lookupLock` copy or a stale acquisition; release the `FencedLock` the acquire returned), at DEBUG when the name is
  not held. Pin the outcome with a test in `DBFencedLockManagerAcquiredCallbackErrorTest`, whose
  `InMemoryLockStorage.lookupLockInDB` returns a real copy, for example
  `releasing_the_lookupLock_copy_of_a_held_lock_releases_nothing` for option B: the row stays locked with the same
  token, no `lockReleased` and no `LockReleased`, and the confirmation keeps advancing `lockLastConfirmedTimestamp`.
  Option A changes the behaviour again, so it needs a release-notes entry.

(review findings F-966, F-968, F-970)

**`lockReleased` can overlap `lockAcquired` during shutdown, and `LockCallback` does not say so (RW-17).**

- **Problem:** during shutdown, a `cancelAsyncLockAcquiring` that lands after `asyncLockAcquiringTick` has checked its
  registration releases the lock through `releaseLockDuringShutdown`. That release is one bounded
  `ShutdownContext.attemptCleanup`, which may skip the wait for `reentrantLock` or give it up. `releaseLocally` then
  calls `lockReleased` on the cancelling thread while `lockAcquired` may still be running on the tick thread. The only
  record of this is a comment inside `asyncLockAcquiringTick`; `LockCallback` states no threading contract at all.
- **Consequence:** the framework's own callbacks (event processors, inbox consumers wired in `onLockAcquired`, the
  scheduler) are written as if the two calls were sequential. During shutdown such a callback can tear down state that
  its own `lockAcquired` is still building, and an application's callback has no way to know it must guard against it.
- **Fix:** state the contract in the `LockCallback` javadoc and in `DBFencedLockManager.acquireLockAsync`: outside
  shutdown, `lockReleased` for an acquisition runs after its `lockAcquired` has returned; during shutdown it may run on
  another thread while `lockAcquired` is still running, so a `lockAcquired` that starts work needs a `lockReleased`
  that is safe to run alongside it (guard the started state with a lock or an atomic). If that is not acceptable, close
  the gap: a per-registration flag that the tick sets around `lockAcquired`; the shutdown release then skips the local
  `lockReleased`, and the tick calls it itself when `lockAcquired` returns. Then check the framework's callbacks against
  the contract chosen.

(review findings F-967)

**Two async-acquiring races no test reaches, and two loose ends in the tests (RW-18).**

- **Problem:**
  - Replacing a resumed registration (RW-13) while another node holds the lock runs nowhere. In that case
    `cancelAsyncLockAcquiring` finds nothing in `locksAcquiredByThisLockManager` and only swaps the registration: the
    old callback must not get `lockReleased`, and the new one must get the lock when the other node lets go. The unit
    tests cover only the case where the resumed acquiring holds the lock. No replacement test runs against a real
    database: the reusable `DBFencedLockManagerIT` in `foundation-test`, which the PostgreSQL and MongoDB subclasses
    run, resumes only with the same callback.
  - The second registration check in `asyncLockAcquiringTick` rests on a two-sided argument: either the cancel finds
    the recorded lock and releases it, or the tick finds its registration gone and releases it, and when both happen
    only the first local release calls `lockReleased` and publishes `LockReleased`. Both tests of it in
    `DBFencedLockManagerAcquiredCallbackErrorTest`
    (`an_async_acquiring_cancelled_while_its_tick_acquires_the_lock_releases_the_lock_and_does_not_call_lock_acquired`
    and its critical-error variant) block the tick in `lookupLockInDB`, before the lock is recorded, so only the tick
    releases. The side where both release, where the once-only announcement matters, is not exercised.
  - In `DBFencedLockManagerLostLockReleaseTest`, the `withUnitOfWork` override in `TestDBFencedLockManager` matches the
    confirmation thread by a copied literal, `"-FencedLock-Confirmation-"`, which `start()` spells separately in its
    `nameFormat`; the async acquiring threads already have `ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX`. The same class has
    an unnecessary `@SuppressWarnings("PMD.CloseResource")` on
    `an_acquire_waiting_for_the_lock_managers_lock_while_the_confirmation_ends_with_a_critical_error_does_not_acquire_the_lock`:
    the lock it acquires is never kept in a local.
- **Consequence:** a regression in either race ships with a green build. A rename of the confirmation thread turns
  `a_non_critical_failure_escaping_the_confirmation_releases_every_held_lock_locally_and_is_logged_as_an_unexpected_failure`
  into a 5-second Awaitility timeout on the ERROR line, far from the cause.
- **Fix:**
  - Add a two-node test to `DBFencedLockManagerIT`, for example
    `acquireLockAsync_with_another_callback_replaces_a_resumed_acquiring_waiting_for_a_lock_another_node_holds`: node 1
    registers `cb1` and stops; node 2 registers `cb2` and takes the lock at token t; node 1 starts and registers `cb3`.
    Assert that `cb1.released` stays 1 and `cb2.released` stays 0. Then cancel on node 2, await `cb3` acquiring at
    token t + 1, and assert that `cb1.acquired` does not move over a `during(5s)` window.
  - Add a test that lands the cancel after the lock is recorded. `_tryAcquireLock` records the lock and then publishes
    `LockAcquired` on the tick thread while it holds `reentrantLock`, so an `eventSubscriberAction` can start a thread
    that calls `cancelAsyncLockAcquiring` on the first `LockAcquired`, and wait until
    `reentrantLockForTests().hasQueuedThread(thatThread)` before it returns. Assert no `lockAcquired` or `lockReleased`
    on the callback, exactly one `LockReleased`, an unlocked row, no exception on the cancelling thread, and
    `isLockedByThisLockManagerInstance` false. It is the same cancel-against-tick race as RW-11; if RW-11 is still
    open, do them together.
  - Add a package-private `CONFIRMATION_THREAD_NAME_INFIX` next to `ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX`, and use
    it in `start()` and in the override. Drop the unnecessary suppression. PMD run on that class without the test
    classpath also flags suppressions that are needed, so check each one by hand rather than trusting the report.
  - Tests only: no release-notes entry.

(review findings F-951, F-969, F-971, F-972)

### Value types

**OpenAPI and AssertJ support (SJ-6).** Two problems:

- Single-value types (`CharSequenceType`, `NumberType`, ...) appear in generated OpenAPI schemas as objects instead
  of primitives.
- AssertJ's `assertThat(...)` is ambiguous for a `CharSequenceType`, which is both a `CharSequence` and a
  `Comparable`, so users need casts.

**Fix:**

- Promote the swagger-core `EssentialsValueTypeModelConverter` from `admin-api-spec` into a consumer module,
  `types-openapi`. It needs an exemption from the `ban-jackson-2` enforcer rule.
- Add a `types-assertj` module with an `EssentialsAssertions.assertThat(CharSequenceType)` entry point.

### Opt-ins

**Reject and commit on the UnitOfWork command interceptor (CB-1).** `UnitOfWorkControllingCommandBusInterceptor`
rolls back on any exception:

- A handler that rejects a command by throwing loses any work it meant to keep.
- Under `sendAndDontWait`, the rejection is redelivered by the default redelivery policy.

**Fix:** an opt-in set of exception types on which the interceptor commits and then rethrows. It can apply only
when the interceptor created the UnitOfWork, not when it joined an outer one.

**WARN on DML outside a transaction (QL-5).** A Jdbi write made while no UnitOfWork is active auto-commits on its
own. It is an easy mistake and gives no signal. **Fix:** an opt-in Jdbi `StatementCustomizer` in the Spring Boot
starter that WARNs once when a DML statement runs with no active transaction.

---

## P3: documentation

Everything planned here before 2026-10-13 shipped in 0.60.1.

- **A lost fenced lock can be won straight back (NW-5).** After a database outage longer than the lock timeout, the
  instance that lost a lock can win it again through its still-running `acquireLockAsync`, under the next fence token.
  That is correct fencing, and `DBFencedLockManagerIT` now accepts either instance as the new holder, but the fenced
  lock docs (`LLM-foundation.md`, the module READMEs) do not say so. Say it, next to the fence-token explanation.
- **The coupled contract of `transientGapsReadFrom` (RW-7; review F-113).** `AwaitedGapInspector.transientGapsReadFrom()`
  lets an inspector claim that its `isAwaitedAsGap` already reads a gap handler's transient gaps, and `CdcEventStore`
  then skips its own read. The javadoc describes only the safe direction. Add: "Return a gap handler only if
  `isAwaitedAsGap` reads that handler's transient gaps on every call; otherwise a decorating `CdcEventStore` skips
  them and `awaitProcessed` can report an event still awaited as a gap as processed. When in doubt, return empty."
  State the same "forward both or neither" rule where decorators are told to forward `isAwaitedAsGap`, and in the
  decorator note in `LLM/LLM-postgresql-event-store.md`.
- **The run report's lock order (RW-8; review F-106).** `EssentialsRunReport` takes the static `REGISTERED` monitor and
  then a report's `lock` (`registerAsRunReport`/`deregister` → `recomputeRunReport`); nothing takes them the other way
  round today, and nothing says it must not. State it in the `REGISTERED` field and class javadoc ("REGISTERED, then a
  report's lock; never take REGISTERED while holding a report's lock"), and pin it with a test that registers and
  closes a second report in a loop while another thread renders the first, under `assertTimeoutPreemptively`.
- **The Leadership Election example (RW-19).** The example in `LLM/LLM-foundation.md` § Leadership Election is a
  `SmartLifecycle` at `DEFAULT_PHASE`, so it starts after `DefaultLifecycleManager` (phase `Integer.MAX_VALUE - 1`)
  has started the lock manager, and stops before it. Three gaps:
  - Its `start()` calls `acquireLockAsync` and sets `running = true` whatever the lock manager's state, although the
    paragraph under it says that such a call while the lock manager is not started registers nothing and logs only at
    DEBUG (`start-life-cycles=false` without a manual start, `emit` mode). An application copied from it reports the
    bean as running while no leader is ever elected, the exact trap the section describes. Fix: log at WARN in
    `start()` when `!lockManager.isStarted()` ("FencedLockManager is not started - no leader will be elected for
    ..."); a WARN rather than an exception, so `emit` mode, where no `Lifecycle` bean is started by design, keeps
    working. RW-12 may make the call while stopped work instead; then describe that.
  - Its `stop()` calls `cancelAsyncLockAcquiring(LEADER_LOCK)` before `DefaultLifecycleManager.stop()` calls
    `signalShutdownStarting`. The lock manager's `shutdown` is still unset, so `DBFencedLockManager.releaseLock`
    releases through `withUnitOfWork` with no time limit instead of the bounded `releaseLockDuringShutdown`; neither
    `essentials.life-cycles.shutdown-timeout` nor `ShutdownContext.attemptCleanup`'s single attempt applies. Against a
    database that is unreachable at shutdown, the leader's release waits out the connection-pool timeout on Spring's
    shutdown thread. Fix: either implement `stop(Runnable callback)` and run the cancel off the shutdown thread,
    calling `callback.run()` in a `finally`, so Spring bounds it by `spring.lifecycle.timeout-per-shutdown-phase`; or
    keep the synchronous `stop()` and add one sentence saying that this release is not bounded by
    `essentials.life-cycles.shutdown-timeout` and waits for the connection timeout against an unreachable database.
  - The start and stop order the example relies on is held in place only by a comment in
    `DefaultLifecycleManager.getPhase()`; no test refers to `getPhase()` or `DEFAULT_PHASE`. A change of the phase (to
    `DEFAULT_PHASE`, "to start last") fails nothing and brings back the failure the example was rewritten to avoid.
    Fix: add a test such as
    `its_phase_is_below_the_default_phase_so_an_application_smart_lifecycle_starts_after_and_stops_before_it` to
    `components/foundation/.../lifecycle/DefaultLifecycleManagerTest.java`, asserting that `getPhase()` is below
    `SmartLifecycle.DEFAULT_PHASE` (optionally also a small Spring context test of the order), and point the
    `getPhase()` comment at it.

  The `LLM/` edit needs a `plugin.json` version bump; ship it with the next plugin release. (review findings F-980,
  F-981, F-982)
- **The README's account of a replaced async registration is shorter than the LLM doc's (RW-20).**
  `components/foundation/README.md`, in the paragraph on what a lock manager's `stop()` keeps, says that the release on
  a replaced registration "calls `lockReleased` on the callbacks registered with it". `LLM/LLM-foundation.md`
  describes the same release more fully: any other caller in this JVM holding the same lock loses it too, and a failure
  of that release (a throwing `lockReleased`) is logged while the new callback is registered all the same, and a
  critical error is rethrown. A README reader misses the cross-caller effect that the README's own next section,
  "Ownership is per lock-manager instance", warns about. Fix: extend the README's parenthesis to match, with a link to
  that section. If RW-13 changes what the replacement releases, write both accounts from the new behaviour instead.
  (review findings F-983)

---

## P4: `essentials` plugin

- **Spring Boot's failsafe in generated projects (NW-6).** maven-failsafe-plugin 3.6.0 no longer binds `skipTests`, so
  `-DskipTests` runs every integration test (fixed in Essentials' own POM in 0.60.1 by defaulting `skipITs` to
  `skipTests`). Generated projects take failsafe from Spring Boot, which still manages 3.5.6 in 4.1.2 (the current pin), so they
  are safe today. When the Boot pin moves, check the managed failsafe version; at 3.6+, add the same property to the backend
  POM template and a `stack-lint` rule.
- **`slice-discover-brownfield-layered` below a clean pass (NW-7).** The other slice-check cases were triaged on
  2026-10-27 and now score 0.94-1.00. This case scored 0.83 with no run passing, because the report numbered
  `discovery-heuristics.md` §7's findings into the payoff tiers (`1c`, `2a`) and left `OrderController` out of the
  cohesion tier. `commands/slice-discover.md` now says §7 findings come after the lane tier, unnumbered. Run the case
  3 times to confirm. Also open: the `service-entity` fixture's `OrderStatusQueries.findByStatus` derives on a
  property that does not exist, so it would fail at startup. Its `expected.yaml` tolerates a report of it; fix the
  source or keep it as a deliberate trap.
- **Profile-aware project `CLAUDE.md` (DOC-2).** The templates are done. Two pieces remain:
  - Scope init's "say which write styles the project can use" to the chat reply, and make it aware of the DB
    profile (no event-sourced lanes on `pg-crud` or `mongo`).
  - Add an `/essentials:upgrade` check that flags statements in a project's `CLAUDE.md` that contradict the detected
    DB profile or backend language.
- **Projection test patterns (DOC-4).** In the view-slice skill and the `__View__ProjectionIT` templates, which are
  still all TODOs, prescribe two patterns:
  - **(a)** Call the projection's `@MessageHandler` directly with a synthetic `OrderedMessage`. This is also the only
    way to test that the projection survives redelivery.
  - **(c)** Make the fixture text unique per run, for example with a UUID suffix, so count assertions survive the
    history that accumulates.

  Reference the start-position hooks. Do not prescribe pattern (b), seeding the version above any real order.
- **Dispatch-only command slices (DOC-5).** In `slice-yaml.schema.json`, require `handles` plus
  `anyOf[{required:[writes]},{required:[dispatches]}]` for `kind: command`, with no new kind, following the
  automation precedent. Also:
  - add a gate 4(c) lint hint pointing at `dispatches`;
  - add one sentence in `rules/slice-design.md` that sanctions a command slice which validates a request and
    dispatches another context's command.
- **Read-seam interfaces in the provider's `types/` (DOC-7).** Extend the sanctioned cross-slice edit in
  `rules/slice-design.md` to cover one more case: a consumer adds a declaration-only read-seam interface to the
  provider context's `types/`. The provider slice then implements it and declares `provides:`. Do not sanction a
  port local to the consumer.
- **A symptom index in `LLM-traps.md` (DOC-8).** The 0.60 refresh is done. Add a second index keyed by symptom,
  linking to the existing ESS ids:
  - the projection stopped;
  - reads fail but writes worked;
  - the context will not start;
  - a field is null after replay;
  - it works on one instance only.

  Give each trap an "open" or "fixed in 0.x" status.
- **Automation template name (DOC-10).** Rename the Java automation template `__Slice__Repository.java` to
  `__Slice__RepositoryConfiguration.java`, as already done for the view template. State once in `stack-contract.md`
  that every `@Configuration` class name ends in `Configuration`.
- **Stream/event-type cross-check (DOC-11, N-13).** Make gate 1(c) in `slice-lint.py` report both sides of the
  conflict:
  - name the manifest whose `from` lists the name, as well as the one that declares it in `aggregateTypes`;
  - flag any `from` value that matches a known stream (any `aggregateTypes` or `writes` name), so the error is
    caught even when no manifest declares `aggregateTypes`.
- **A workaround register in init.** Have `/essentials:init` scaffold a register convention:
  - one file, and every workaround in the code carries a one-line pointer to its entry with the condition for
    deleting it;
  - a unit test that fails when the pinned Essentials version changes, prompting someone to walk the register;
  - optionally, reflection "canary" tests asserting that each worked-around behaviour still exists.


---

## P5: build and tooling

Internal to the Essentials build and the plugin's checks: nothing a framework or plugin user notices, so these items
need no release-notes entry beyond §7 and can ship in any release.

- **Re-measure the parallel reactor (NW-9).** `mvn -T 3 verify` took 12:53 against about 23 minutes serially on
  2026-10-12, but three `postgresql-queue-shard-owned` ITs failed under it, which is why the root `CLAUDE.md` says that
  module flakes under `-T`. All three failures came from the lease test and the watermark test, and both are fixed.
  Run `mvn -T 3 verify` three times; if it is clean, drop the warning from `CLAUDE.md` and consider `-T` for the CI
  `verify` job.
- **`plugin-check.sh release` has not run for real (NW-10).** The release lanes added on 2026-10-12 were tested with
  `--no-evals` only, and Ctrl-C was not tried. Before the next plugin release, run `scripts/plugin-check.sh release`
  with the evals, and press Ctrl-C once mid-scaffold to check that every child build stops and the work directories
  are left in a state the next run accepts.
- **Parallel scaffold builds on a cold `~/.m2` (NW-11).** `scripts/plugin-scaffold.sh` runs its lanes concurrently, and
  on an empty local repository every lane downloads the same artifacts at once. Maven 3.9 resolves that safely only
  with named file locks. Either warm the repository with one case before fanning out, or pass
  `-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav` to each lane.
- **The review evals no longer exercise the base-tree path (NW-12).** Since Claude Code 2.1.311 the eval sandbox gives
  Bash no writable directory, even in a case that grants Write and Edit, so `mktemp -d` fails and every `review-*` run takes
  `/essentials:review`'s no-writable-directory path (scripts piped, findings attributed by the diff's added lines). That
  path is what made `review-service-entity` pass again (3 of 3 at 1.00 twice on 2026-10-27), but the normal path, which
  extracts the base tree and compares the base run, is now checked by no eval. Re-run both review cases on a Claude Code
  whose eval sandbox lets Bash write `$TMPDIR`, or by hand with `claude --plugin-dir essentials-plugin`, before relying
  on it.
- **One completion protocol in `plugin-check.sh`'s parallel steps (RW-10; review F-114).** `run_steps_parallel`
  learns that a step finished from a `.rc` file the step's EXIT trap writes, and since 0.60.1 also from `kill -0` plus
  `wait PID` when that file never appears (a SIGKILL). The second path alone covers every case on bash 4.4+, the
  script's floor: `wait PID` returns the status for a normal exit, `exit` and a kill (128+signal). Drop the trap
  protocol, or say in a comment why it must stay.
- **`pmd-ruleset.xml`: the scope of `UseProperClassLoader`, and a stale count (RW-21).** The ruleset excludes
  `UseProperClassLoader` from the `errorprone` category and re-adds it with a `violationSuppressXPath` keyed on the
  class's simple name (`*Test`, `*IT` and classes nested in them). Its comment justifies the exemption with a reason
  that holds for any test proxy (a JDK proxy needs a loader that sees its interface), and says that test helpers stay
  checked without saying why. So test code follows two conventions for one concern: `*Test`/`*IT` classes need no
  suppression, while the test helpers `ChargingConnection` and `FlushHoldingDataSource` (`postgresql-queue-shard-owned`)
  and `TestProxies` (`postgresql-queue-shard-owned-adapter`) keep hand-written
  `@SuppressWarnings("PMD.UseProperClassLoader")` with the same justification, and a new helper that builds a proxy is
  flagged. `AvoidAccessibilityAlteration` uses the identical predicate. Separately, the header's "357 with the
  exclusions below" was measured while `UseProperClassLoader` was excluded entirely; the main sources now count its
  hits too (the comment itself says some older main-code calls carry no suppression yet). Fix: either give the comment
  a reason why test helpers stay checked, or exempt all test sources (a test-scope ruleset, if the review adapter can
  run PMD on main and test apart) and delete the three helpers' suppressions; apply the same decision to
  `AvoidAccessibilityAlteration`. Then re-measure the main sources with PMD 7.28.0 and update the count, or drop it.
  The older main-code calls without a suppression are named nowhere, and a review sees only added lines, so they never
  surface: `WireFormatProbes.isOnClasspath`, the `Jackson3JSONSerializer` constructor and the Objenesis probe in
  `EssentialsJacksonModules` (all `foundation`), and `KotlinValueTypeConverterRegistrar.addTo`. Give each a suppression
  that says why its loader is the right one, or switch it to the loader it should use, and then drop the comment's
  "some older main-code calls" sentence. (review findings F-985, F-987, F-989)
- **A context-guard refusal that a subagent meets first reaches the user nowhere (RW-22).** In
  `.claude/hooks/context-guard.sh`, `refuse` writes the reason to stderr in every mode, and as a `systemMessage` the
  user sees only from `start`. `start` runs for the main conversation only: its SessionStart input carries no
  `agent_id`, so it never checks `$base/agents` or `$base/agents/<agent_id>`. Those directories are first checked by a
  subagent's `warn`, where a refusal goes to stderr alone. A refusal limited to them (`agents` is a symlink, or `mkdir`
  fails there on a full disk) therefore turns the guard off for every subagent in the session with no message to the
  user, although the comment above `refuse` says a refusal must not "switch it off unnoticed". The same holds for a
  refusal of `$base` or `main/` that first appears after `start` has run. `scripts/test-context-guard.sh` pins the
  silence: case `s31` (a symlinked agents directory) expects empty stdout from a subagent's `warn`. Fix: either let
  `warn` print the `systemMessage` too, once per session, guarded by a `mkdir` marker in a directory that passed the
  checks (for example `$base/main/refusal-shown`); or narrow the comment to say that the user sees only a refusal
  `start` detects, and that subagent-level refusals go to stderr by design. Update case `s31` to match either way.
  (review findings F-907)
- **Jackson 3.2 breaks the missing-primitive wire-format probe (DU-1).** Tried on 2026-10-27: `jackson3-bom.version`
  3.1.7 → 3.2.4 resolved cleanly, but two `foundation` unit tests failed
  (`EssentialsObjectMappersWireFormatDeviationsTest.a_default_jackson_3_mapper_reports_each_way_it_differs` and
  `failing_on_a_missing_primitive_is_reported`). Jackson 3.2 reads a primitive record component that is *absent* from
  the document as `0`/`false` even with `FAIL_ON_NULL_FOR_PRIMITIVES` enabled; only an explicit `"quantity":null`
  still throws. On 3.1.7 both throw. Plain-class fields were never affected. The Essentials mapper disables the
  feature, so the persisted format is the same on both versions (`WireFormatCompatibilityTest` passed). What broke is
  `WireFormatProbes`' "A missing primitive reads as its default" probe, which reads `{"id":"probe-1"}` into a
  `record(String id, int quantity)`: on 3.2 it can no longer tell a mapper with the feature on from the Essentials
  one, so `EssentialsObjectMappers.findWireFormatDeviations` stops reporting that drift. The bump was held back
  because Spring Boot 4.1.2 manages Jackson 3.1.7: applications run 3.1.x, where an absent primitive still fails,
  and testing Essentials on 3.2 would test a Jackson they do not run. Fix, when Spring Boot manages 3.2.x: make the
  probe report correctly on every Jackson the starters support, either by also probing an explicit `null` (throws
  on 3.1 and 3.2 alike when the feature is on) or by probing both shapes, and reword the aspect if it no longer
  describes a missing primitive (its text is asserted by the test and echoed in `LLM/LLM-immutable-jackson.md` and
  the `types-jackson3` and `immutable-jackson3` READMEs). Then drop the `jackson-annotations` exclude from the root
  POM's `requireUpperBoundDeps` (3.2 pairs with annotations 2.22, as Jackson 2.22.x does), and run the full suite:
  the integration tests never ran on 3.2.4.

---

## Next major

- **Typed ranges, step two (ES-1).** Once the typed-range overloads have shipped deprecated-only in a minor, mark
  the `LongRange` `EventStore` overloads `@Deprecated(forRemoval = true)`. Also move the interceptor SPI operation
  objects (`FetchStream`, `LoadEventsByGlobalOrder`, ...) to the typed ranges.
- **Exclusive lock acquire by default (QL-1).** Consider making `tryAcquireLock` exclusive by default once
  `tryAcquireLockExclusively` exists. A caller that re-acquires a lock it already holds would then spin, so the
  change needs a migration note.
- **Rethrow the original exception from the UnitOfWork interceptor (CB-2).** Commands sent through
  `UnitOfWorkControllingCommandBusInterceptor` surface failures wrapped in `UnitOfWorkException`. Consider rethrowing
  the original exception after the rollback instead. That changes the thrown type for existing callers.
