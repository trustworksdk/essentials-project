# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.61.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/releases/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.61.0.md`](RELEASE-NOTES-0.61.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

_A bug-fix entry also says how to observe the fix: the call, and what it returns, throws or logs before the fix
and after it. A project that worked around the bug can then check whether its workaround is still needed and
remove it, instead of keeping it because nobody can tell._

**So far:** a patch-sized set of bug fixes and corrected documentation.

- Event store: an exclusive subscription's resume point no longer freezes after a lock hand-over - saves are fenced by
  the lock token (new `fence_token` column, §1, §3); the Spring unit of work now calls `afterRollback` (§3).
- CDC: the `wal2json` refusal on PostgreSQL 17.12 / 18 names `output_plugin_libraries` and how to fix it (§3).
- Aggregates: a subclass of a concrete aggregate resolves its state type through its superclass, with the new
  `GenericType.resolveTypeArgument` in `shared` (§3).
- Fenced locks: a lost `acquireLockAsync`, a no-op `release()` on a `lookupLock` copy and the resumes of a recovery
  restart are logged truthfully; the `LockCallback` threading contract is documented (§3).
- Documentation: no more "from 0.61" promises for redelivery-policy and shard-owned builder checks; the MongoDB queue
  handler does not run in a `UnitOfWork`; which shard-owned sweep counter to alert on (§3).

There are no API removals. One schema change adds a nullable `fence_token` column to the durable subscriptions table
(§1).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

**Under `essentials.schema.mode=validate`: apply the new schema change `durable-subscriptions-fence-token` before the
first start (`postgresql-event-store`).** It adds a nullable column `fence_token BIGINT` to the durable subscriptions
table (default name `durable_subscriptions`), which fences resume-point saves of exclusive subscriptions by their lock
token (§3). Under `create` (the default) the column is added on start, only when it is missing, so a running instance is
not locked out of the table. Under `validate` the first start is refused, naming `durable-subscriptions-fence-token` as
not applied: run with `emit` to write the script and apply it, or run the statement yourself
(`ALTER TABLE durable_subscriptions ADD COLUMN IF NOT EXISTS fence_token BIGINT`) and then the script's ledger insert.
The column is nullable, so instances of the previous release keep working against the altered table during a rolling
upgrade. Their saves carry no token and are checked by the reposition epoch alone, as before, so **the hazard the
column fixes (§3) persists until every instance runs this release**.

**Under `essentials.schema.mode=validate`, shard-owned queues: re-apply the `engine-schema` change before the first
start (`postgresql-queue-shard-owned`).** It now also adds a nullable column `last_error text` to both live tables
(`ALTER TABLE shard_queue_unordered ADD COLUMN IF NOT EXISTS last_error text`, the same for `shard_queue_ordered`), so
`validate` reports the change as applied with other statements until it is re-applied: run with `emit` to write the
script and apply it. Under `create` (the default) the columns are added on start, only when missing. The column is
nullable with no default, so adding it only changes the catalog, and instances of the previous release keep working
against the altered tables during a rolling upgrade; they just store no error (§3).

**If the fenced-lock tokens restart, clear the stored tokens.** Lock tokens only ever grow while the fenced-locks table
(default `fenced_locks`) keeps its rows. If that table is dropped and recreated, truncated, restored from an older backup,
or the lock manager is pointed at a different table, new lock holders get tokens lower than those stored in
`durable_subscriptions.fence_token`, and every save of an exclusive subscription is refused (one WARN per subscription:
`Did not save resumeFromAndIncluding … was written by a newer fenced lock holder (stored fence token is newer than …)`;
the stored resume point stops moving). Recovery: with the exclusive subscriptions stopped, or at least before their next
save, run `UPDATE durable_subscriptions SET fence_token = NULL` (all rows, or `WHERE subscriber_id = …` for one); the
next save of each lock holder then stores its own token.

### 1.1 ⚠️ Silent behaviour changes

None so far.

---

## 2. New features

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Shard-owned queues (`postgresql-queue-shard-owned`, adapter, admin API) | A shard-owned message that kept failing showed no error until it was dead-lettered: the live tables had no `last_error` column, the owner's retry stored none, a pull session's `fail` discarded it, and a takeover that dead-lettered a message whose attempts were used up stored only the generic `EXHAUSTED_BY_TAKEOVERS` text. Now the owner's retry and a session's `fail` store the error on the live row (rendered like a dead letter's: `<type>: <message>`, with a `Root cause: ...` summary first when the exception wraps another), and the takeover dead-letter keeps the stored error, falling back to the generic text only when none was recorded; the texts of that generic message and of `EXHAUSTED_IN_A_RELEASED_HANDLER` no longer claim that no error is kept. The error is read by id only: the new `MessageQueue.lastError(MessageId)` (a `default` returning empty, so other implementations keep compiling), `DurableQueues.getQueuedMessage(...).getLastDeliveryError()` on the adapter, and the admin API's single-message view; a page of messages (`getQueuedMessages`, `messages`) carries no errors, and the delivery path never reads the column. Written only on failure paths. Schema step: §1. Observe: `ShardOwnedFailurePathIT`, `ShardOwnedPullSessionIT`, `ShardOwnedTakeoverAttemptsIT`, `DeadLetterErrorTextOnShardOwnedIT` (RW-3, F5) | |
| Shard-owned queues (`postgresql-queue-shard-owned`), foundation | Cross-instance wake-ups arrived late under load. `ShardWakeupListener` read LISTEN notifications with pgjdbc's `getNotifications(200)`, which hands nothing back while notifications keep arriving less than ~1 ms apart, so an owner on another instance was woken only by the 500 ms backstop poll or later (messages late, never lost). Measured on this release with `ShardWakeupListenerDrainIT` (opt-in, `-Dbenchmark.run=true`), wake-up latency p99 before → after: 200 msg/s 3.4 → 1.4 ms, 1 000 msg/s 202 ms → 0.3 ms, 3 000 msg/s 37 s → 0.6 ms. The listener now drains through the new public foundation class `PostgresqlNotificationDrain` (`open(Connection)`, `next(timeoutMillis)`, `close()`), which turns pgjdbc's 1 ms socket peek off by reflection and falls back to polling with `SELECT 1` (one WARN per JVM) if pgjdbc's internals change. Ported from the event-log branch (`4410621a8`, `28b03e3ff`). | |
| Shard-owned queues (`postgresql-queue-shard-owned`) | On a connection pool that hands out connections with auto-commit off (`spring.datasource.hikari.auto-commit=false`), `ShardWakeupListener` ran its `LISTEN` in a transaction that was never committed, so it received no notification at all: cross-instance wake-ups silently never happened, and owners on other instances were woken only by the backstop poll. The listener now switches auto-commit on for as long as it listens and back off before it returns the connection to the pool. Observe: `ShardWakeupListenerAutoCommitOffIT`, which fails on the previous code. Ported from the event-log branch (`923cb5679`, shard-owned part). | |
| Scheduler (foundation) | During a shutdown the fenced-lock manager can call a callback's `lockReleased` before the async acquiring tick's `lockAcquired` has run (the overlap `LockCallback` documents). `DefaultEssentialsScheduler` stopped in that window scheduled its jobs when the late `lockAcquired` arrived, after its own `stop()`: pg_cron jobs were inserted into `cron.job` and stayed there after the JVM was gone, and an executor job failed with a `NullPointerException` (the executor was already shut down). Rare - the shutdown has to land while the tick acquires the lock. `lockAcquired` now schedules nothing unless the scheduler is started and the lock is still held, and logs at INFO that it skipped. Observe: `EssentialsSchedulerIT_WithExecutor.a_lockAcquired_arriving_after_stop_released_the_lock_schedules_nothing` (RW-17) | |
| Inbox / Outbox (foundation) | The same shutdown overlap in `MessageConsumptionMode.SingleGlobalConsumer`: when `lockReleased` ran before the async acquiring tick's `lockAcquired`, the late `lockAcquired` started the exclusive `DurableQueueConsumer` after `stopConsuming()`, so the Inbox or Outbox was consumed without its fenced lock, and with a null message consumer, until the `DurableQueues` stopped. Both callbacks now run under one monitor, and `lockAcquired` starts nothing once the lock is no longer held. Observe: `DurableQueueInboxesTest`/`DurableQueueOutboxesTest.a_SingleGlobalConsumer_lockAcquired_arriving_after_stopConsuming_released_the_lock_starts_no_consumer` (RW-17) | |
| Exclusive subscriptions, view processors (`postgresql-event-store`) | The same shutdown overlap in `ExclusiveAsynchronousSubscription`, `ExclusiveBatchedAsynchronousSubscription`, `ExclusiveInTransactionSubscription`, `ViewEventProcessor` and `PartitionedViewEventProcessor`: when `lockReleased` ran before the async acquiring tick's `lockAcquired`, the late `lockAcquired` subscribed (or started the view's queue consumer and subscriptions, or the partitioned view's forwarding readers) after the component's own `stop()`, with no lock held and nothing left to stop it. While the JVM or Spring context stayed up, events could be handled twice: once by the leaked subscriber and once by the new lock holder (resume-point saves are fenced by the lock token since this release). Each `lockAcquired` now does nothing unless the component is started and the lock is still held; `ExclusiveInTransactionSubscription`'s callbacks now share a monitor. Observe: `ExclusiveSubscriptionLateLockAcquiredTest`, `ViewEventProcessorLockCallbackTest.a_lock_acquired_arriving_after_stop_released_the_lock_starts_no_consumer_and_no_subscriptions`, `PartitionedViewEventProcessorTest.a_lock_acquired_arriving_after_stop_released_the_lock_starts_no_reader_subscriptions` (RW-17) | |
| CDC (`postgresql-event-store`) | PostgreSQL 17.12 and 18 refuse an output plugin that is not listed in `output_plugin_libraries` (default `pgoutput, test_decoding`), so creating a `wal2json` slot fails with `library "wal2json" may not be used as an output plugin`: under `cdc.mode=require` start-up fails, under `auto` subscribers silently stay on polling. What to do: add `wal2json` to the setting (`postgresql.conf`: `output_plugin_libraries = 'pgoutput, test_decoding, wal2json'`; SQL: `ALTER SYSTEM SET output_plugin_libraries = 'pgoutput', 'test_decoding', 'wal2json'`, each name quoted on its own, then `SELECT pg_reload_conf()`), or use `pgoutput`. How to observe: `Wal2JsonLogicalDecodingPlugin.unusableReason` read only "wal2json plugin not usable"; it now names `output_plugin_libraries` and both ways to add `wal2json`. `pgoutput` is unaffected. | ported from `bbf1d28f0` |
| Exclusive subscriptions (`postgresql-event-store`) | After a lock hand-over during a database outage, an exclusive subscription's stored resume point stopped moving for as long as the new lock holder held the lock, and the next restart or hand-over redelivered every event since the hand-over (no event was skipped). Cause: the previous holder, stopped while it retried an event, held its resume point at that event, which starts a new reposition epoch, and saved it after the new holder had read the row; saves were not checked against the lock, the newer epoch won, and the reposition-epoch guard then refused every save of the new holder. Affects 0.60.0 to 0.61.0, and the same way the batched exclusive subscription and a graceful stop whose final save lands after another instance took the lock. Fix: every resume-point save of an exclusive subscription carries the token of the fenced lock it acquired (`SubscriptionResumePoint.fencedBy` / `getFenceToken`), the table stores the highest token written (new column `fence_token`, schema change `durable-subscriptions-fence-token`, see §1), a save with a newer token wins whatever its reposition epoch, one with the same token is checked against the epoch as before (a reset still wins over that holder's in-flight saves), and one with an older token is refused. Non-exclusive subscriptions and `resetStoredResumePoint` save without a token and behave as before. `EventStoreSubscription.resetFrom` on an exclusive subscription now saves the reset before it lets go of the lock (it saved after the release, so an instance that took the lock over in between could read the old resume point and resume from there - with the token rule its first save would then have overwritten the reset), so the next lock holder resumes from the reset point. Until every instance runs this release, an instance of the previous release still saves without a token (see §1). How to observe: before, the new holder logged once `Did not save resumeFromAndIncluding … the stored resume point was repositioned by another writer (stored reposition epoch is newer than 0)` and `durable_subscriptions.resume_from_and_including_global_eventorder` stayed behind its progress; after, the new holder's saves land and `fence_token` holds its lock's token. A refusal now comes from the previous holder, late, and its WARN also names the fence token. A row frozen by the bug heals at the new holder's first save after the upgrade. Pinned by `PostgresqlDurableSubscriptionRepositoryIT` and `EventStoreSubscriptionManager_2_node_exclusive_resetFrom_IT`. | this commit |
| Spring event-store unit of work (`spring-postgresql-event-store`) | `SpringTransactionAwareEventStoreUnitOfWorkFactory` never called `PersistedEventsCommitLifecycleCallback#afterRollback`, for any rollback, so a callback that undoes in-memory work for events it saw in `beforeCommit` was never told they were rolled back, and `EventStoreEventBus` never published `PersistedEvents` with `CommitStage.AfterRollback` in a Spring application. The JDBI path (`EventStoreManagedUnitOfWork`) always did. Fix: after a rollback every registered callback's `afterRollback` receives the events persisted in the unit of work, including any a completed `beforeCommit` round had already moved on (the same as the JDBI path). How to observe: before, a subscriber to `PersistedEvents` on `EventStoreEventBus` saw no `CommitStage.AfterRollback` after a Spring transaction that appended events rolled back; after, it receives them. Pinned by `SpringTransactionAwareEventStoreUnitOfWorkFactory_RollbackOnlyIT`. | this commit (RW-1) |
| Redelivery-policy builders (foundation), shard-owned adapter | The WARN (and the startup-report finding) for an incomplete `RedeliveryPolicy`/backoff builder and for a `ShardOwnedDurableQueues` built without a `UnitOfWorkFactory` said `build()` would throw, or the builder would require a factory, "from 0.61". 0.61.0 shipped with both still only WARNing, so the message promised something that did not happen. The text now says a later release will make them fail; the javadoc and the LLM docs say the same. Behaviour is unchanged: both still only WARN. How to observe: the WARN read `… From 0.61 build() will throw an IllegalArgumentException instead.` / `… from 0.61 the builder will require one.`; it now reads `… A later release will make build() throw …` / `… a later release will make the builder require one.` | this commit |
| MongoDB queue documentation (`springdata-mongo-queue`) | `LLM/LLM-springdata-mongo-queue.md` said that with a `SpringMongoTransactionAwareUnitOfWorkFactory` each message is handled inside a `UnitOfWork` of its own. It is not: the factory only lets a `queueMessage` join the caller's `UnitOfWork`, and the handler's MongoDB writes commit one by one, apart from the acknowledgement. The code is unchanged; the doc now says so and shows how a handler opens its own transaction. If you relied on the documented behaviour, wrap the handler body in `unitOfWorkFactory.usingUnitOfWork(...)` or a `TransactionTemplate`. How to observe: a handler that writes two documents and then throws keeps the first write. Making the factory wrap the handler is roadmap item RW-4 (a later minor release, opt-in). | this commit (RW-4, docs part) |
| Aggregates (`eventsourced-aggregates`), Spring unit of work (foundation), `shared` | A subclass of a concrete `modern.AggregateRoot` (`class SpecialOrder extends Order`, where `Order implements WithState<…, OrderState>`) could not be created: resolving its state type read only the class's own interfaces. The same limit applied to a subclass of a concrete `SpringTransactionAwareUnitOfWorkFactory` that does not repeat `UnitOfWorkFactory<…>` itself. Fix: both now resolve the type argument through superclasses and sub-interfaces, mapping type variables on the way, with the new `GenericType.resolveTypeArgument(Class, Class, int)` (returns `Optional<Class<?>>`; additive). `GenericType.resolveGenericTypeForInterface` is unchanged. `EventStreamDeciderCommandTypes.resolveCommandTypeArgument` delegates to it and stays deprecated for removal. How to observe: `new SpecialOrder(…)` threw `IllegalStateException: … doesn't implement generic Interface …WithState …`; it now gets an `OrderState`. A workaround that overrode `resolveStateImplementationClass()` or `resolveUnitOfWorkType()` only for this can be removed. | this commit (RW-5) |
| Fenced locks (foundation) | `acquireLockAsync` called while the lock manager is not started, for a lock name with no kept registration (for example, a component started before the lock manager, or recreated after its predecessor called `cancelAsyncLockAcquiring`), was dropped with only a DEBUG line: the component never got its lock and nothing said so. Behaviour is unchanged - the next `start()` still does not acquire it - but the call is now logged at WARN, naming the lock and the callback, and the javadoc says so. What to do: start the lock manager before the components that acquire locks, or call `acquireLockAsync` again after it started. How to observe: the WARN `Lock Manager isn't started - ignoring async lock acquiring for lock '…'`. | this commit (RW-12) |
| Fenced locks (foundation) | A stop and start of the lock manager after the lock confirmation ended - the documented recovery - logged one WARN per resumed lock registration suggesting a leaked registration, and every callback built with `LockCallback.builder()` was named `LockCallback$1`. On that path the resumes are now logged at INFO; the WARN stays for any other restart. The log line names the callback by its `toString()`, and builder-made callbacks now print their consumers, whose class names say which component created them. How to observe: after a recovery restart, `Resuming async lock acquiring for lock '…'` is INFO instead of WARN and names e.g. `…ViewEventProcessor$$Lambda…` instead of `…LockCallback$1`. | this commit (RW-15) |
| Fenced locks (foundation) | `release()` on a `FencedLock` returned by `lookupLock` (or on a stale instance from an earlier acquisition) while this instance holds the lock does nothing, as since 0.60.1, but did so silently; code such as `lookupLock(name).ifPresent(FencedLock::release)` kept the lock without a trace. It is now logged at WARN, and the javadoc of `FencedLock.release()` and `lookupLock` says only the instance the acquire returned (or `lockAcquired` was given) releases the lock. How to observe: the WARN `Not releasing lock '…': this lock manager holds it, but under another FencedLock instance …`. | this commit (RW-16) |
| Shard-owned queue metrics (`postgresql-queue-shard-owned`, documentation) | The `ShardOwnerMetrics` javadoc called `sweepRecoveries` "the one to watch", but it also counts messages that committed between a cursor read and the head sweep after it - harmless, most often in the start-up sweep - so a dashboard alerting on its value fired falsely. The javadoc (`ShardOwnerMetrics`, `QueueStatistics`) and `LLM/LLM-postgresql-queue-shard-owned.md` now say not to alert on its value: on the ordered lane alert on `sweepRecoveriesBehindCursor` (`ShardOwnedQueue.metrics().snapshot()`), on the unordered lane on `sweepRecoveries` rising as a share of `delivered`. The counters are unchanged. | this commit (NW-4, docs part) |

---

## 4. The `essentials` Claude Code plugin

0.61.0 shipped plugin `0.61.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- **0.61.0-3** — references follow this release's later documentation and fixes: a lost fenced lock can be won
  straight back; forward `isAwaitedAsGap` and `transientGapsReadFrom()` both or neither; the Leadership Election
  example checks the lock manager is started; `PostgresqlNotificationDrain`; a lower HikariCP `minimumIdle` for
  queue-only services; set the driver's socket timeout so a stalled connection cannot hold a poll; a shard-owned live message's
  `lastError` and the takeover dead-letter keeping it.
- **0.61.0-2** — references follow this release's documentation fixes: no "from 0.61" promises for the
  redelivery-policy and shard-owned builder checks; the MongoDB queue handler does not run in a `UnitOfWork`;
  `GenericType.resolveTypeArgument`; the fenced-lock async-acquiring notes and `LockCallback` threading contract; which
  shard-owned sweep counter to alert on.
- **0.61.0-1** — the event-store reference says CDC with `wal2json` on PostgreSQL 17.12 / 18 needs `wal2json` in
  `output_plugin_libraries`, and how to add it.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- `essentials-trading-demo`: its compose files use the repository's PostgreSQL image under its new tag
  `essentials-postgres:17.5-2` (see §7).

---

## 6. Documentation

- Fenced locks (`LLM/LLM-foundation.md`, `components/foundation/README.md`): a lost lock can be won straight back. After
  a database outage longer than `lockTimeOut`, the instance that lost a lock (its `LockCallback` got `lockReleased`) can
  win it again through its still-running `acquireLockAsync`, under the next fence token. The docs implied the other
  instance always takes over. Behaviour is unchanged; a callback that treats `lockReleased` as "another instance holds
  the lock now" should not, and must handle `lockAcquired` again after `lockReleased` (NW-5).
- Event store decorators (`AwaitedGapInspector` javadoc, `LLM/LLM-postgresql-event-store.md`): forward
  `isAwaitedAsGap` and `transientGapsReadFrom()` both or neither. `transientGapsReadFrom()` may return a gap handler
  only if `isAwaitedAsGap` reads that handler's transient gaps on every call; otherwise a `CdcEventStore` over the
  decorator skips them, and `awaitProcessed` can report an event the subscriber still awaits as a gap as processed.
  Only matters for an `EventStore` you wrote that decorates one of Essentials'; when in doubt, return empty (RW-7).
- Leadership Election example (`LLM/LLM-foundation.md`): `start()` now checks `lockManager.isStarted()`, logs a WARN and
  stays not running when the lock manager is not started (for example `start-life-cycles=false` without a manual start,
  or `emit` mode); before, it reported itself running while no leader was ever elected. The text also says that the
  example's `stop()` release is not bounded by `essentials.life-cycles.shutdown-timeout` (against an unreachable database
  it waits out the connection-pool timeout) and how to bound it with `stop(Runnable)`. An application that copied the
  example should make the same two changes. `DefaultLifecycleManagerTest` now pins the start/stop order the example
  relies on: `DefaultLifecycleManager`'s phase stays below `SmartLifecycle.DEFAULT_PHASE` (RW-19).
- Fenced locks (`components/foundation/README.md`): the README's account of an `acquireLockAsync` that replaces a
  registration kept by `stop()` now matches `LLM/LLM-foundation.md`: the release also takes the lock from any other
  caller in the same JVM holding it, a failing release is logged and the new callback registered anyway, and the resumes
  after an ended lock confirmation are logged at INFO (RW-20).
- Durable queues (`LLM/LLM-postgresql-queue.md`, `components/postgresql-queue/README.md`): services that only consume
  queues can lower HikariCP's `minimumIdle` (`spring.datasource.hikari.minimum-idle`), which defaults to the pool size
  and so keeps every connection open while the queues are idle; the docs give the trade-off and why to keep it at least
  one above the starter's LISTEN connection. No default changes (E3).
- Durable queues (`LLM/LLM-postgresql-queue.md`, `LLM/LLM-springdata-mongo-queue.md`): queue statements run with no
  timeout of their own, so a fetch on a stalled connection waits as long as the driver does - forever by default in
  both pgjdbc and the MongoDB driver. The docs now say to set the driver's socket timeout (`socketTimeout`,
  `socketTimeoutMS`); a per-consumer fetch timeout is planned for a minor release (RW-6).

---

## 7. Build and tooling

- Test and javadoc only: `EssentialsRunReport` documents its lock order (the registry of registered reports first, then a
  report's own lock, never the other way round), and `EssentialsRunReportTest` pins it by registering and closing a
  second report in a loop while another thread renders the registered one (RW-8).
- Test only: two fenced-lock races no test reached are now covered - an `acquireLockAsync` replacing a resumed acquiring
  while another node holds the lock (`DBFencedLockManagerIT`, PostgreSQL and MongoDB), and a cancel landing after the
  tick recorded the lock, where both release it; the confirmation thread's name infix is a shared constant (RW-18).
- Test only: the event store's start-up schema re-apply takes no lock on any event stream table, causation index and
  notify triggers included, so a starting instance cannot deadlock with a unit of work appending to several aggregate
  types in either order. `EventStoreSchemaReapplyConcurrencyIT` now asserts the re-apply never waits for such an append;
  the existing test only checked both sides succeed, which still passed when PostgreSQL broke a real deadlock and the
  applier retried (NW-3, ruled out).
- Test and javadoc only: the opt-in batched fetch (`PostgresqlDurableQueuesBuilder.setUseBatchedFetch`) was documented
  as "work in progress - doesn't handle competing consumers yet". It does: `BatchedFetchStrategyIT` now runs the
  batched claim from four threads on two instances against one table at once and finds no message claimed twice
  (removing the statement's `FOR UPDATE SKIP LOCKED` makes it fail). The javadoc now says what the strategy does.
- Test only: `SubscriptionResetOnPoisonNotifierIT` no longer polls for a resume point the replay re-advances within
  75 ms; it reads the stored reset when the subscription reports it (CI failure on `f86018aaf`).
- The repository's PostgreSQL test image (`docker/postgresql`, now `essentials-postgres:17.5-2`) runs the server its tag
  names: it shipped the builder's whole PostgreSQL tree, so the 17.5 image ran 17.12, and all CDC integration tests failed
  because 17.12 refuses `wal2json` as an output plugin. Its extensions are now compiled against exactly the base image's
  server version, pg_cron is 1.6.8 (1.6.4 does not compile on 18), its initdb script creates pg_cron only when the server
  preloads it, and the CDC tests use this image instead of a second one of their own; their setup adds `wal2json` to
  `output_plugin_libraries` where the server has that setting. Any base works:
  `-Dessentials.postgres.base-image=postgres:18-bookworm`.
