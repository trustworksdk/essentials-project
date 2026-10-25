# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.0.md`](RELEASE-NOTES-0.60.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

**So far:** no public API removed, but six changes can break code written against 0.60.0 (§1): two additions that
can stop a processor subclass or `CommandHandler` implementation of your own compiling, a new `ShardOwnerSettings`
record component that stops a record pattern over that record compiling (its 0.60.0 constructor still works,
deprecated), and two runtime breaks: direct callers of `ShardOwnedStorage`'s 0.60.0 takeover-charge methods, which
now throw, and `Subscription.close()` on the shard-owned engine, which now unsubscribes: a `PostgresqlMessageQueue`
subscription closed to pause it is silently left stopped by the queue's `start()`, with no exception and nothing
logged, and its own `start()` throws `IllegalStateException` (§1.1.23), and one admin API contract change: the
`queueName` of the shard-owned responses is now a string, not an object wrapping `value`. Twenty-four framework changes
with a behaviour change (§1.1, §1.1.13–§1.1.24 in the durable queues and units of work), additive features (§2), bug
fixes (§3), plugin releases `0.60.0-6` and `0.60.0-7`, and example and tooling updates. Beyond those six, the only
migration step is for `essentials.schema.mode=validate` deployments: re-run `emit` and apply the script before the
first start (§1). Supported PostgreSQL is now 15+ (§1). The persisted
format changes for one shape only, back to what 0.50 wrote: a type with a public `is`-getter (§1.1.2).

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

No public API was removed, and no configuration key was renamed or removed; the additions are listed in §2. Two of
the additions can still stop your code compiling: `AbstractEventProcessor` gains `getCurrentUnitOfWork()`,
`getRequiredUnitOfWork()` and `getUnitOfWorkFactory()`, and `CommandHandler` gains a `default handledCommandTypes()`.
A processor subclass or `CommandHandler` implementation of your own that already declares a method with one of those
names and an incompatible signature or return type no longer compiles; rename yours.

`ShardOwnerSettings`, a record, gains a fifteenth component, `stuckHandlerTimeout` (§1.1.24, §2). Its 0.60.0
fourteen-argument constructor still works, with the default bound, and is deprecated for removal. A record pattern
written against the fourteen 0.60.0 components (`case ShardOwnerSettings(var readBatchSize, ...)`) no longer
compiles; add the fifteenth.

`ShardOwnedStorage`, the shard-owned engine's SQL layer (public, and reachable through `ShardOwnedQueue.storage()`,
but not meant to be called by applications), keeps its three 0.60.0 lease and takeover-charge methods, deprecated for
removal. `releaseLeases(lane, shards, owner)` still works, but the engine no longer calls it: bounded by the instance
id alone, a release made for a queue that has since been stopped and started again in the same process frees the
lease the new run holds. Use `releaseFencedLeases(lane, fenceByShard, owner)`. `bumpAttemptsOnTakeover(connection,
shard)` and `bumpOrderedAttemptsOnTakeover(connection, shard)` now throw `UnsupportedOperationException` and write
nothing: without an owner and a fence they would charge attempts to rows another instance is delivering (§1.1.18).
Use the overloads taking `(connection, shard, inFlightBound, ackBatchSize, owner, fence)` and
`(connection, shard, ackBatchSize, owner, fence)`. `deregisterInstance(instanceId)` is unchanged, and still what the
engine calls to delete an instance's membership row; whether the row may go is decided by the stopping consumer, which
for a `PostgresqlMessageQueue` subscription leaves it to the subscription's other lane while that lane still needs it
(§3).

`Subscription.close()`, on the shard-owned engine, now calls `unsubscribe()`; in 0.60.0 it called `stop()` (§1.1.23).
Code that closed a `PostgresqlMessageQueue` subscription to pause it and started it again now breaks at runtime, in
one of two ways. The queue's `start()` silently leaves the closed subscription stopped: it throws nothing and logs
nothing, because that subscription is no longer registered with the queue. The subscription's own `start()` throws
`IllegalStateException`. Pause a subscription you mean to start again with `Subscription.stop()`, which still keeps it
registered.

The admin API contract declares the `queueName` of the shard-owned queue status, statistics, message and
kept-unit-release responses (`ApiShardOwnedQueueStatus`, `ApiShardOwnedQueueStatistics`, `ApiShardOwnedMessage`,
`ApiShardOwnedKeptUnitRelease`) as a string, and the API sends it as one: `"queueName":"orders"`. In 0.60.0 it was
an object, `{"value":"orders"}` - the only queue name in the API not sent as a string, which also left the admin
console's shard-owned message drawer showing `[object Object]`. The `QueueName` schema is gone from the contract. A
client generated from the 0.60.0 contract fails to read these responses, and code reading `queueName.value` from them
gets nothing; regenerate the client and read `queueName` directly (`7a2cce93d`).

**Supported PostgreSQL is now 15+.** Essentials supports the PostgreSQL majors the PostgreSQL community still
patches, which today means 15 and later (14 reaches end of life on 2026-11-12). Before this release the module docs
said 9.5+, or 13+ for the shard-owned ordered lane. Nothing in 0.60.1 stops an older server from working, but older
servers are no longer tested or supported, and 0.61 removes the code paths kept for them. If you run PostgreSQL 14
or older, plan the server upgrade before 0.61. Details: README § Version Compatibility.

**Only if you run `essentials.schema.mode=validate`:** re-run `emit` and apply the new script before starting this
release. The change-notification trigger that the durable queues and the event store install is now written
differently (the startup deadlock fix in §3), so its schema-history checksum changes, and `validate` refuses to start
with "applied with other statements" until the new statements are recorded. The script is safe to run against a
database that instances are using. In the default `create` mode nothing needs doing: the change is repeatable, so it
just records the new checksum. Two more schema changes ride on the same step: the shard-owned engine's schema adds a
nullable `incarnation` column to `shard_queue_instance` (§3), and a `PostgresqlDurableQueues` made with the builder
now installs the change-notification trigger on its queue table (§1.1.17). Applying the `ALTER TABLE` alone is not
enough: `validate` compares the recorded statements, so re-run `emit` and apply the script. The same step also
records the rewritten index and column statements of the second startup deadlock fix in §3: every repeatable
`CREATE INDEX` and `ADD COLUMN` in the event store, durable subscriptions, gap handler, CDC inbox, durable queues,
fenced locks, snapshot jobs, aggregate archive, closing-books generations and the shard-owned queue engine now checks the
catalog first.

### 1.1 ⚠️ Silent behaviour changes

#### 1.1.1 A message in flight when its consumer stops is redelivered, not dead-lettered

Affects `PostgresqlDurableQueues` (both the centralized fetcher, the default, and the per-consumer path) and
`MongoDurableQueues`, and therefore every `Inbox`, `Outbox`, `DurableLocalCommandBus` and `EventProcessor` built
on them. The shard-owned engine is unaffected: it never interrupts a handler on stop.

**Before:** stopping a consumer — application shutdown, `consumer.cancel()`, an `Outbox`/`Inbox` stopping —
interrupts its worker threads. A handler in the middle of I/O then fails with an interrupt it did not cause; a
Kafka send throws `org.apache.kafka.common.errors.InterruptException`. The centralized fetcher classified that
failure only *after* the consumer had unregistered, found no registration, and treated that as
`PERMANENT_ERROR` with no redeliveries. The message was dead-lettered on its first attempt whatever its
`RedeliveryPolicy` said, with a log line like:

```
Marking message as dead letter. PERMANENT_ERROR (the RedeliveryPolicy's MessageDeliveryErrorHandler returned
PERMANENT_ERROR; attempt 1 of 1). Error: ... InterruptException: java.lang.InterruptedException
```

The per-consumer path classified it under the policy, so it dead-lettered only when the redelivery cap had been
reached.

**Now:** a delivery that fails after its consumer began stopping is not classified. It is handed back with
`retryMessage(id, cause, Duration.ZERO)`, logged at INFO as `Handler failed after its consumer began stopping;
returning the message for redelivery`, and the next consumer to take it classifies its next failure as usual.

The same goes for a handler that *returns normally* with the interrupt flag set after its consumer began stopping,
typically one that caught the interrupt, re-set the flag (as Kafka does) and gave up on its work. It may not have
finished, so it is not acknowledged: neither by the consumer nor, on PostgreSQL, inside the handler's transaction. It
is handed back the same way, with an `InterruptedException` saying so as its last delivery error, and logged at INFO as
`Handler returned while interrupted by ... consumer stopping; returning the message for redelivery instead of
acknowledging it`. Before, PostgreSQL usually acknowledged it, deleting a message whose work may never have been done;
MongoDB failed that acknowledgement on the interrupted thread, so the message came back only after
`messageHandlingTimeout`. On PostgreSQL, a handler run in the queue's own unit of work has the writes it made there
rolled back with it, so the redelivery starts from none of them, not from the half a handler that gave up between two
writes left behind: the delivery is undone whole, as the in-transaction acknowledgement (§1.1.16) promises. The
rollback is not a handler failure, so it is never classified and cannot dead-letter the message.

The rollback does not reach a handler that owns its unit-of-work boundary, as the queue then opens no unit of work
around it: every `EventProcessor` handler, whatever its `UnitOfWorkMode`, and an `Inbox` whose consumer is a
`UnitOfWorkBoundaryOwningMessageConsumer`, which between them cover every `UnitOfWorkMode.NONE` handler. What such a
handler committed before returning stays committed, and its message is still handed back, so the redelivery runs it
again on top of its own partial writes: it must be idempotent across a partial run, not only across a whole one.

When the consumer's state is not known (an operation built without it, e.g. by hand) the interrupt cannot be told
from any other: the handler's writes commit and the message is left to the consumer, as before. A handler that
returns with the flag set while its consumer is still running is acknowledged as before, on PostgreSQL inside the
handler's own transaction (§1.1.16), with the flag cleared for that write and restored afterwards; so is one that
returns with the flag clear while its consumer is stopping. This is narrower than the shard-owned engine's rule
(`abandonedOnInterrupt`): that engine refuses to acknowledge on the interrupt flag alone, stopping or not, and charges
no attempt, leaving the message to the next owner. Here the flag must be set *and* the consumer stopping, and the
hand-back counts one redelivery attempt, as below.

What you may notice:

- **Fewer dead letters after a deploy or restart.** Ones you have seen with `InterruptException` /
  `InterruptedException` in their cause after a shutdown were this bug; they can be resurrected.
- **The hand-back counts as one redelivery attempt**, because the handler did run (`retryMessage` always counts
  one). A message caught by several shutdowns uses one attempt of its budget per shutdown. A message that was
  claimed but never reached a handler is released without being charged (§3).
- **The handler may run again for the same message.** That was always possible under at-least-once delivery;
  previously the message was parked instead.

Commit `6ba568b5`. Pinned by `DurableQueuesIT.a_message_whose_handler_is_interrupted_by_the_consumer_stopping_is_redelivered_not_dead_lettered`
(PostgreSQL traditional and centralized, both transactional variants, and MongoDB). The handler that returns
while interrupted: commit `4f573f0d`, its writes rolled back since `31ae304f`, pinned by
`PostgresqlDurableQueuesAcknowledgeWithHandlerIT.a_handler_that_returns_while_interrupted_by_the_consumer_stopping_has_its_writes_rolled_back_and_its_message_handed_back_not_acknowledged`,
`SingleOperationTransactionDurableQueuesInterceptorTest` and the consumers' unit tests. The handler that re-sets the
flag while its consumer runs is pinned by
`PostgresqlDurableQueuesAcknowledgeWithHandlerIT.a_handler_that_returns_while_interrupted_while_its_consumer_is_running_is_acknowledged_in_its_unit_of_work`.

#### 1.1.2 A public `is`-getter no longer adds a property to persisted JSON

Affects every mapper Essentials builds: `EssentialsObjectMappers` (the persistence mapper behind the event store,
durable queues, inbox/outbox and snapshots), `EssentialsImmutableJacksonModule.createObjectMapper()` and
`EssentialTypesJacksonModule.createObjectMapper()`.

The 0.50 Jackson 2 mapper serialized fields only. Jackson 3 detects `is`-getters separately from getters, and
0.60.0 hid getters and setters but not `is`-getters, so a public `isX()` method added a property to every
document. The same gap was in 0.50.x's Jackson 3 flavour (`createJackson3ObjectMapper()`). All three mappers now
hide `is`-getters, which restores the 0.50 wire format:

| Payload | 0.60.0 wrote | Now writes (as 0.50) |
|---|---|---|
| field `boolean isExpress` with `isExpress()` | `{"isExpress":true,"express":true}` | `{"isExpress":true}` |
| record component `String gift` with a derived `boolean isGift()` | `{"gift":true}` | `{"gift":"ribbon"}` |

What you may notice:

- **Documents written by 0.60.0 still read.** The extra key is an unknown property, and unknown properties are
  ignored.
- **Except where the `is`-getter's property shares a name with a field or record component** (the second row).
  0.60.0 wrote the getter's value *over* the field's, so the stored value is lost; it reads back as `"true"` or
  `"false"`. The fix stops new writes doing that, but cannot recover what is already stored. If any of your
  persisted types has both a field `x` and a public `isX()`, check the rows written by 0.60.0.
- **New documents no longer carry the extra key.** Code that reads stored JSON directly (SQL on `jsonb`, CDC
  consumers outside Essentials) and relied on it will no longer find it.

Pinned by `EssentialsObjectMappersWireFormatTest` (`postgresql-event-store`), `EssentialsImmutableJacksonModuleTest`
and `EssentialTypesJacksonModuleTest`. A startup check that also catches a mapper Essentials did not build is still
open; see `docs/ROADMAP-0.6x.md` §0.1.

#### 1.1.3 `OptimisticLockingException` is unchecked

`postgresql-document-db`'s `OptimisticLockingException` now extends `RuntimeException` instead of `Exception`. It
was always thrown from Kotlin without `@Throws`, so Java code could not catch it by name and had to catch
`Exception`. What changes for you:

- Java code can now `catch (OptimisticLockingException e)`.
- A `catch (RuntimeException e)` now catches it too.
- **Spring `@Transactional` now rolls back on it** by default, since Spring rolls back on unchecked exceptions.
  Before, a checked exception escaping a `@Transactional` method committed the transaction.

Binary-compatible: code compiled against 0.60.0 runs unchanged. (DD-1, `e632ec31`)

#### 1.1.4 The default fenced-lock-manager id is `<hostname>:<pid>`

When no lock-manager id is configured, the PostgreSQL and MongoDB fenced lock managers now default to
`<hostname>:<pid>` instead of the bare hostname. Two JVMs on one host, or two containers sharing a hostname, no
longer appear as the same owner. The lock table, the admin API and the log lines show the new holder string.
Mutual exclusion has always come from the fence token, so a rolling upgrade that mixes old and new instances is
safe. To keep a fixed id, set `essentials.fenced-lock-manager.instance-id` (see §2). (N-8, `206c7e58`)

#### 1.1.5 New warnings: enqueue outside a transaction, and incomplete redelivery policies

Four new WARN log lines. Nothing else changes, but each points at a setup that does not do what it appears to:

- **A `DurableQueues` enqueue outside a unit of work.** A `queueMessage`/`queueMessages` that runs with no
  Essentials `UnitOfWork` and no Spring transaction logs a WARN with its stack trace, once per call site. A call site
  is the first three stack frames outside Essentials, the JDK, Reactor, Kotlin and Spring, so two callers that both
  go through your own wrapper (a messaging gateway around `Outbox.sendMessage`, say) are two call sites, and the WARN
  names all three frames. The budget is counted per innermost application frame, not per call site: up to 32 such
  frames are logged per `DurableQueues` instance, each with up to 4 of its callers, so one wrapper reached from many
  callers cannot use the budget up and hide every other call site. The 32nd frame is followed by one line saying
  further ones are not. A frame charged before that keeps logging its remaining callers afterwards, so after the
  32nd frame the check keeps looking at the stack until every charged frame has used all 4, or until 10 000
  enqueues past that point have logged nothing new, whichever comes first; a caller first reached after that is
  not logged. Until then each enqueue outside a unit of work costs one stack walk. The cap is
  `EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_INNERMOST_FRAMES`. Keying on the call site
  means a deliberate standalone enqueue (`sendAndDontWait` from a controller, say) cannot use up the only WARN and
  hide an `Outbox.sendMessage` elsewhere. Each such call site is also listed in
  the run report (§2), at start-up and while the application runs. The enqueue commits on its own, so a later rollback of your business change does not undo it. This
  covers `Outbox.sendMessage` and `DurableLocalCommandBus.sendAndDontWait` called outside a transaction. To make it
  fail instead, set `essentials.durable-queues.require-active-unit-of-work-for-enqueue=true` (§2).
  (RN-2, `3c783626`, `8d349587`, `fb8559c9`, `c9e566bd`, `14dad969`)
- **A shard-owned queue built without a UnitOfWork factory.** Building `ShardOwnedDurableQueues` without
  `setUnitOfWorkFactory(...)` logs a WARN that every enqueue commits on its own. The 0.61 release will refuse to
  build it. The starters always set one. (N-15, `4248f0b3`)
- **An incomplete redelivery policy.** `build()` on `RedeliveryPolicy.exponentialBackoff()`, `linearBackoff()`,
  `fixedBackoff()` and `RedeliveryPolicy.builder()` logs one WARN naming each setter left out or out of range:
  unset delays, a multiplier below 1.0 or unset, or `setMaximumNumberOfRedeliveries` never called. Unset means 0
  redeliveries, so the first failure dead-letters the message. The 0.61 release will throw
  `IllegalArgumentException`. (QL-3, `5537eae3`)
- **A `DurableLocalCommandBus` without the UnitOfWork interceptor.** `start()` logs one WARN when the bus has a
  `SendAndDontWaitErrorHandler` that does not rethrow and no `UnitOfWorkControllingCommandBusInterceptor`. In that
  setup a failed `sendAndDontWait` handler's partial writes commit with the message acknowledgement, because on
  PostgreSQL they join the queue consumer's UnitOfWork. Add the interceptor. The starters' bus always has it.
  (`35210f04`)

#### 1.1.6 An empty `LongRange` reads nothing

`LongRange.EMPTY_RANGE` / `LongRange.empty()` was `(0,0)`, which covers order 0. Passed to `fetchStream` or
`loadEventsByGlobalOrder`, it returned the first event. It is now `(0,-1)` and covers nothing, and an empty range
returns `Optional.empty()` / `Stream.empty()` with no database round trip. The check runs after the interceptors, so
an interceptor can still replace the range. Extra global orders passed together with an empty range are still read.
New: `LongRange.isEmpty()`, true for any closed range whose end is below its start. (N-10, `2dd7f81b`)

#### 1.1.7 The subscriber lag gauge is recorded without tracing, and reads one higher

`DurableSubscriptions_EventOrder_Diff` (`SubscriberGlobalOrderMicrometerMonitor`):

- **Registered whenever a `MeterRegistry` bean is present.** Before, only with `management.tracing.enabled=true`,
  so most deployments never had it.
- **Off by one, fixed.** It now reads head − resume point + 1, so one unread event reads 1; it read 0. An empty
  table reads 0. Alert thresholds tuned to the old value move up by one.
- **A resume point ahead of the head** (the sign of a reset or restored database, where the subscriber silently
  skips events) still reads 0, and now logs a WARN once per episode.
- The monitor no longer throws; an escaping exception used to cancel every monitor's schedule. The per-interval
  "Subscriber lag found" line moved from INFO to DEBUG.

(N-9, ES-4, ES-7, `75076149`)

#### 1.1.8 The shard-owned queue's default instance id is `<hostname>:<pid>`

`spring-boot-starter-postgresql-queue-shard-owned` now defaults `essentials.shard-owned-queue.instance-id` to
`<hostname>:<pid>` (`FencedLockManagerSettings.defaultLockManagerInstanceId()`, the same default as the fenced lock
manager, §1.1.4) instead of the bare hostname. Two processes on one host are now two instances; before, fair share
counted them as one and each got half the shards. One visible difference: after a crash, a process restarted on the
same host waits up to `leaseTtl` to take its units back, where before it took them at once because it reused the
dead process's id. Containers that share a hostname and all run as pid 1 still need an explicit `instance-id`. A
rolling upgrade that mixes old and new instances is safe: the id only names membership rows and lease owners, and
every lease acquisition bumps the fence. (N-8, `e639f3d0`)

The attempt counting at takeover changed as well; see §1.1.18.

#### 1.1.9 `MultiTableChangeListener` behaves differently at the edges

`MultiTableChangeListener` fans PostgreSQL LISTEN/NOTIFY out to the event store and the queues. Its robustness
fixes (§3) change four things you can observe:

- **Polling is scheduled in `start()`**, so `stop()` followed by `start()` resumes; before, it never did. A
  listener that is never started through `Lifecycle` (the try-with-resources use) still starts polling on its first
  `listenToNotificationsFor(...)`.
- **Duplicates are detected per channel.** Notifications from different tables no longer collapse into one.
- **Channel names are lower-cased** (`ListenNotify.resolveTableChangeChannelName`, `Locale.ROOT`). Before, a
  mixed-case table name never delivered a notification: `LISTEN` folds an unquoted name to lower case, `pg_notify`
  does not.
- **Schema-qualified table names are rejected** by every `ListenNotify` entry point with an
  `InvalidTableOrColumnNameException`. `PostgresqlUtil.checkIsValidTableOrColumnName` accepts `schema.table`, but the
  name ends up in the notify function's name, the trigger's name and the `LISTEN` channel, none of which can carry a
  schema, so it already failed later with a SQL syntax error. Pass the unqualified name and put the schema on the
  `search_path`.
- **`TableChangeNotification.equals`/`hashCode`** are null-safe and compare the concrete class. Before, a payload
  without `table_name` threw an NPE, and two different notification types for the same table were equal. A
  subclass that carries state must override both.

(LN-4, LN-5, LN-6, N-6, N-7, `784da9df`…`5bae5aed`)

#### 1.1.10 `essentials.immutable-jackson-module-enabled` applies by default, to the persistence mapper

The property was documented with default `true` but had no effect unless set explicitly. Now:

- **Default (`true`):** the persistence `JSONSerializer`/`JSONEventSerializer` registers
  `EssentialsImmutableJacksonModule`, as it already did whenever `immutable-jackson3` was on the classpath, so
  nothing changes there. Spring Boot's web `JsonMapper` does **not** get it, as in 0.60.0: on the web mapper the
  module would turn a 400 for a request body Jackson cannot construct into an object with null fields.
- **Explicit `true`:** both mappers get the module (the web mapper as before).
- **`false`:** neither does. This is new for the persistence mapper, which ignored the property before; only
  reading changes, the written JSON does not.

New overloads: `EssentialsObjectMappers.createJackson3ObjectMapper(boolean, JacksonModule...)`,
`createJSONSerializer(boolean)` and `EssentialsJacksonModules.modules(boolean)`. (N-11, `e2554c4d`, `dab3564e`)

#### 1.1.11 A `ViewEventProcessor` warns about event types it has no handler for

A processor silently ignores an event type none of its `@MessageHandler` methods accepts. For a
`ViewEventProcessor` that matters: the view never sees that event's `EventOrder` for the aggregate, so a projection
that checks contiguity treats the next event as a gap, and a missing handler leaves the view incomplete with nothing
to say so. Now:

- **`ViewEventProcessor`** logs a WARN per ignored event type, at most once an hour (logger
  `…eventstore.postgresql.processor.UnhandledEventTypes`). Mark deliberate skips with `@IgnoresEventTypes` (§2).
  At start it also WARNs about event types that a sealed supertype of a handled type permits and that it neither
  handles nor ignores. Event tables are not scanned.
- **`EventProcessor` and `InTransactionEventProcessor`** log it at DEBUG only: skipping types is how they are
  meant to work.
- All three log the event types they handle at start (INFO), and count every ignored event in
  `essentials.eventstore.processor.unhandled_event_type` (tags `processor`, `aggregate_type`, `event_type`) when a
  `MeterRegistry` is present.

(CB-4, `9f4ddecf`, `b786b107`)

#### 1.1.12 A failed `sendAndDontWait` handler's writes roll back; the error handler runs afterwards

On `LocalCommandBus` and `DurableLocalCommandBus`, a `sendAndDontWait` handler's exception used to be caught inside
the interceptor chain and handed straight to the `SendAndDontWaitErrorHandler`. When that handler did not rethrow,
the interceptors saw a normal return, so `UnitOfWorkControllingCommandBusInterceptor` committed whatever the handler
wrote before it threw (§3). Now the exception unwinds through the interceptors first, and the error handler is called
after the chain. What changes for you:

- A failed handler's writes are rolled back, whatever the error handler does.
- `SendAndDontWaitErrorHandler.handleError` still runs exactly once and still receives the handler's own exception.
  It is no longer called if an interceptor swallows that exception.
- A `CommandBusInterceptor.interceptSendAndDontWait` now sees the handler's exception come out of
  `chain.proceed()`, as under `send` and `sendAsync`. The execution-time metrics of `sendAndDontWait` now include
  failed deliveries.
- With a rethrowing error handler, the durable queue now receives the original exception rather than a
  `UnitOfWorkException` wrapping it. Redelivery and dead-letter classification walk the whole cause chain, so they
  are unchanged.

(`f722edb8`)

#### 1.1.13 `afterCommit` / `afterRollback` callbacks run on JDBI units of work

`GenericHandleAwareUnitOfWork` is the unit of work behind `JdbiUnitOfWorkFactory` and the event store's
`EventStoreManagedUnitOfWorkFactory`. Its `commit()` and `rollback()` closed the JDBI handle before calling the
registered callbacks, and closing the handle cleared them, so no `UnitOfWorkLifecycleCallback#afterCommit` or
`#afterRollback` ever ran with these factories. That has been the case since the first commit of this repository.
They run now. Spring's `SpringTransactionAwareUnitOfWorkFactory` has its own commit path and was never affected, and
neither were `PersistedEventsCommitLifecycleCallback`s.

Code that was silently skipped outside Spring now executes:

- **`DurableAsyncAggregateSnapshotRepository`** (`ASYNC_DURABLE` snapshots) enqueues its snapshot jobs after commit,
  so outside Spring it never created any. It does now, so snapshot job rows appear and snapshots get written.
- **`AsyncAggregateSnapshotRepository`** writes its snapshots after commit, so the same applies to it.
- **The event store's gap-fill acknowledgement** clears its bookkeeping after commit. Outside Spring the map behind
  it grew without bound, and the gap-reconciliation outcome was never reported for fills acknowledged in the
  subscriber's unit of work. Both are fixed.
- **A retry or a delayed message wakes its queue when it comes due** (§3). The wake-up is an after-commit callback,
  so outside Spring it depends on this fix.
- **Your own callbacks run.** An `afterRollback` callback runs once the failed unit of work is no longer current, so
  a callback that opens a unit of work gets a fresh one rather than the rolled-back one. An exception or a
  non-critical `Error` that a callback throws is logged. It does not change the outcome of the commit or rollback,
  and does not stop the remaining callbacks or the publishing of persisted events.
- **A rolled-back event-store unit of work reports its events as rolled back.** `EventStoreManagedUnitOfWork` passed
  them to `PersistedEventsCommitLifecycleCallback#afterCommit`, so events that a completed `beforeCommit` round had
  already handed on were published on the `EventStoreEventBus` as `CommitStage.AfterCommit` although they were
  never committed. They now go to `afterRollback`, published as `CommitStage.AfterRollback`. The Spring unit of work
  publishes nothing for a rollback, as before.

(`4a870434`, `ad75485b`, `20875720`)

#### 1.1.14 `QueuedMessage.getDeliveryMode()` returns `IN_ORDER` for ordered messages (PostgreSQL)

`DefaultQueuedMessage.getDeliveryMode()` returned `NORMAL` for every message, including an `OrderedMessage`. It now
returns `IN_ORDER` whenever `getMessage()` is an `OrderedMessage`. Ordering itself was never affected; code that
branched on the delivery mode now sees the correct value. Fixing it exposed a latent crash: resurrecting an ordered
dead letter in `PostgresqlDurableQueues` would have thrown `ClassCastException` from its log line. That code had never
run, because the mode was always `NORMAL`; it is fixed too. (`5603a596`)

#### 1.1.15 Stored delivery errors start with the root cause (PostgreSQL, MongoDB and shard-owned queues)

A handler's exception reaches the queue wrapped, typically as
`UnitOfWorkException → ReflectionException → InvocationTargetException → yours`, so the stored
`last_delivery_error` began with `UnitOfWorkException`. When the exception is wrapped, the stored text now starts
with `Root cause: <type>: <message>` and a line break, followed by the complete, unchanged stack trace. An exception
that is not wrapped is stored exactly as before. Anything that parses the first line of `getLastDeliveryError()` sees
the new summary. The summary is the root cause's message verbatim, so it spans several lines when that message does
(PostgreSQL errors often carry `Detail:` or `Where:` lines); the first line alone is not always the whole root cause.
(`5603a596`)

The shard-owned engine does the same for its dead letters, on both lanes. It stored only the outermost throwable as
`last_error`, so `DeadLetter.lastError()` (and `QueuedMessage.getLastDeliveryError()` through the `DurableQueues`
adapter) named the wrapper and nothing about why the message failed. When the exception is wrapped, the text now
starts with a `Root cause: <type>: <message>` summary, followed by the outermost throwable; the engine still stores no
stack trace. Each throwable is now rendered by its `toString()`. That matches the previous `<type>: <message>` except
for an exception without a message, now stored as `<type>` alone instead of `<type>: null`, and one that overrides
`toString()` or `getLocalizedMessage()`, now stored in its own format. Existing dead letters keep the text they were
stored with. (`9411139c`)

#### 1.1.16 A handled message is acknowledged inside the handler's transaction (`PostgresqlDurableQueues`)

The acknowledgement used to be a second transaction on a second pooled connection, after the handler's had committed.
It is now the handler's last statement, in the handler's own transaction, so the handler's writes and the
acknowledgement commit together. That saves a connection checkout and a commit per message: measured on its own with
the builder's previous wiring (no LISTEN/NOTIFY), drain throughput through a 10-connection pool rose about 10%
(13.6k to 14.9k msg/s), and the threads waiting for a connection halved (`QueueDrainThroughputIT`, opt-in
benchmark). What changes:

- **A failing acknowledgement, or a failing commit, rolls back the handler's writes** and is handled like a handler
  exception: it uses a redelivery attempt and goes through the permanent-error list. Before, on the centralized
  message fetcher (the default), the handler's writes stayed committed, the failure was only logged, and the message
  was delivered again after `messageHandlingTimeout`, repeating them; the per-consumer path retried it under the
  `RedeliveryPolicy`.
- **A `DurableQueuesInterceptor` on `AcknowledgeMessageAsHandled` runs inside the handler's transaction.** If it
  throws, the handler's writes roll back, and an `IllegalArgumentException` (`requireNonNull`, `requireTrue`, Kotlin
  `require`) dead-letters the message on its first delivery, as it would from the handler.
- **`beforeCommit` callbacks run after the acknowledgement**: event-store appends and in-transaction handlers. The
  queue row stays locked until the commit, and the stuck-message reset waits for it.
- **A handler that returns with the interrupt flag set while its consumer is stopping has its writes rolled back**
  and its message handed back unacknowledged (§1.1.1): it may have given up part-way, and committing what it did write
  would make the redelivery start from half of its work. Not a handler failure, so never dead-lettered. When the
  consumer's state is not known, its writes commit and the message is left to the consumer. Only a handler run in the
  queue's own unit of work is rolled back: one that owns its unit-of-work boundary (every `EventProcessor` handler,
  whatever its `UnitOfWorkMode`, and an `Inbox` with a `UnitOfWorkBoundaryOwningMessageConsumer`, which covers every
  `UnitOfWorkMode.NONE` handler) keeps what it committed before returning and is still handed back, so it must be
  idempotent across a partial run.

What does not change: a handler that requests redelivery, one that marks its unit of work rollback-only (its message
is still consumed) and one that owns its unit-of-work boundary (`EventProcessor` handlers, a boundary-owning `Inbox`
consumer, so every `UnitOfWorkMode.NONE` handler) are acknowledged separately, as before. `MongoDurableQueues`
and the shard-owned engine are unchanged. A crash before the commit still redelivers, so handlers should stay
idempotent. (`6949dcac`)

#### 1.1.17 `PostgresqlDurableQueues.builder()` is wired like the Spring Boot starter

The builder now creates a `MultiTableChangeListener` when none is set, and its default
`CentralizedQueuePollingOptimizer` uses the Spring Boot starter's values (start at the polling interval, ×1.5 per
empty poll, capped at 2 s). A builder-made instance therefore behaves like a Spring one: idle queues back off, and
LISTEN/NOTIFY wakes them when a message is added on any node. What a builder user sees:

- One more held database connection per instance, for the listener, which starts and stops with the queues.
  `PostgresqlDurableQueues` is now `AutoCloseable`: `close()` stops it for good and releases the listener and its
  event bus. Spring calls `close()` on a `PostgresqlDurableQueues` bean at shutdown, after `stop()`, which is safe.
- The change-notification trigger on the queue table becomes part of the queue's schema. With
  `SchemaOwnership.COMPONENT` it is created on start-up; under the schema harness in `validate` mode it must be in
  the applied script (§1).
- `setCreateMultiTableChangeListener(false)` keeps the previous wiring: no listener, and every queue polled every
  polling interval without a backoff, since nothing could wake a backed-off queue.

Spring Boot applications see no change: the starter passes its own listener. (`a72aa558`)

#### 1.1.18 Shard-owned engine: attempt counts survive a change of owner, and redelivery requests are not attempts

- **Attempt counts survive a change of owner.** They were kept only in memory, so every new owner started a
  message's count from 1, and the bulk increment a new owner made at takeover was never read back. The first failure
  under a new owner now continues from the count stored on the row.
- **A clean handover charges nothing.** When the previous owner stopped and released the shard, no queued message is
  charged an attempt, so deploys and rebalances no longer cost queued messages attempts; only a message still in a handler when
  its shard or unit is released without waiting for that handler can be charged, by the releasing instance (§1.1.24).
  Before, every takeover charged
  every queued row.
- **A takeover after a crash or a lost lease charges one attempt only to the rows the previous owner could have had
  in flight**: on the unordered lane a window of `parallelConsumers + ackBatchSize` rows and the rows that had
  already failed and come due; on the ordered lane the first `ackBatchSize + 1` visible rows of each key. A row that
  is not yet visible (a delayed message, or a retry waiting out its backoff) is never charged. Neither are the rows of
  a key held by an earlier retry in backoff, a pending requested redelivery or a dead letter, nor a row a pull
  session holds; the same rows are never dead-lettered at takeover either.
- **A message that has taken its owner down `maxAttempts` times is dead-lettered at takeover.** The same pass parks a
  message a pull session failed up to the limit, because a session failure counts an attempt but never dead-letters.
  The stored error (`ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS`, also the cause message `QueueObserver.deadLettered`
  receives) says the message reached its attempt limit when the shard was taken over, and that an error a pull session
  recorded before that is not kept: a live row has no column for it. On the ordered lane its key is blocked behind it,
  as for any dead letter. The `takeoverAttemptBumps` metric drops to zero for clean handovers and to a few rows for
  crashes.
- **A takeover charges once, even when its dead-letter pass fails.** The charge is recorded as soon as it commits. If
  the dead-letter pass after it fails, only that pass is retried: on the pump's next pass when the connection is
  healthy (the pump's other shards keep delivering meanwhile, see §3), or after the reconnect when the connection was
  lost; the window is not charged a second time. A later plain reconnect never reruns the pass, so it cannot
  dead-letter a message the owner is itself delivering on its last attempt. Both lanes.
- **`QueuedMessage.markForRedeliveryIn(delay)` honours the delay and is not an attempt** through the shard-owned
  adapter. It used to be treated as a failure: the delay was ignored in favour of the policy's backoff, and enough
  requests dead-lettered a healthy message. Now only the message's visibility moves: the redelivery count does not
  go up, the policy's budget is not used, it is not reported as a handler failure, and it never leads to a dead
  letter, also not at takeover. This deliberately differs from `PostgresqlDurableQueues`, where a manual redelivery
  counts. Engine users get the same with the new `spi.RedeliveryRequested(delay)` (§2).
- **A handler `Error` counts as a failed attempt.** An `AssertionError`, or any other non-critical `Error`, used to
  free its slot without recording an attempt, so the message came back forever and never reached the dead-letter
  table. It now runs through the redelivery policy like any other failure. `VirtualMachineError` and `LinkageError`
  still propagate uncounted, as in `postgresql-queue`.
- **A pull session's failure counts an attempt** on top of the stored count; before, it reset the count to 1. Its
  failure write is fenced by the session's shard lease, or guarded by its row lease, so a superseded session can no
  longer push out a message's visibility.

(`a4f541e4`, `f9fdd99b`, N-16 `dacd9ebb`, `5486c812`, `49917dab`)

#### 1.1.19 A rollback-only Spring transaction is rolled back on commit, and its queued message retried

Inside a unit of work from `SpringTransactionAwareUnitOfWorkFactory` (the starters'
`SpringTransactionAwareJdbiUnitOfWorkFactory` and the Spring event-store factory), a transaction the factory started
becomes rollback-only when an inner `@Transactional` method or `TransactionTemplate` fails and the caller catches the
exception. `commit()` then only logged: it neither committed nor rolled back, and the transaction and its connection
stayed bound to the thread. Later work on that thread silently joined it. On a durable-queue worker that meant the
handled message's acknowledgement was never committed, and the next messages ran inside the leaked transaction.

Now `commit()` hands the rollback-only transaction to the transaction manager, which rolls it back, releases the
thread and runs the synchronizations, and throws Spring's `UnexpectedRollbackException`. A queued message in this
situation is retried as a failed attempt, with the handler's writes rolled back, because a failed participant is a
failure. Two things are unchanged: Essentials' own `UnitOfWork.markAsRollbackOnly()` still rolls back and consumes
the message, and a transaction Spring started itself (`@Transactional` around the unit of work) is still left to
Spring. (`1ecbf6bf` reproduces it, `2b01d8ab`)

#### 1.1.20 MongoDB retries wait their redelivery delay

`MongoDurableQueues` redelivered every failed message at once, whatever delay the `RedeliveryPolicy` gave: the retry
computed the next delivery time but never wrote it to the document (§3). Retries now wait their delay. A failing
handler's attempts are spread out as the policy says, and a message that keeps failing reaches the dead-letter queue
correspondingly later than before; a test or alert that relied on the immediate retries sees the difference.

The claim also sorts as intended now, by `keyOrder` and then `nextDeliveryTimestamp`. It sorted on one field literally
named `"keyOrder, nextDeliveryTimestamp"`, which no document has, so claims came back in no particular order. Per-key
ordering was never affected, but in a queue holding both kinds, unordered messages are now taken before ordered ones,
and keys with lower orders first, as on PostgreSQL. (`6027fe61`)

#### 1.1.21 A polling-optimizer subclass must override the claim-time callbacks

The queues now report a poll's result through `queuePollingReturnedNoMessages(Instant claimedAt)` and
`queuePollingReturnedMessages(List, Instant claimedAt)` (§2). A subclass of `CentralizedQueuePollingOptimizer` or
`SimpleQueuePollingOptimizer` that overrides only the no-arg `queuePollingReturnedNoMessages()` (or, on the
centralized optimizer, `queuePollingReturnedMessages(List)`) compiles as before but is no longer called. Override
the `Instant claimedAt` overloads instead; the no-arg forms are `@Deprecated(since = "0.60.1")` on these two classes,
so an existing override now gets a deprecation warning. A `QueuePollingOptimizer` implemented from scratch is
unaffected: the interface's `Instant` overloads still call its no-arg methods. (`d42c4bc3`)

#### 1.1.22 Shard-owned engine: a stopping queue holds no shards and takes no hand-off

From the moment `ShardOwnedQueue.stop()` begins, the queue reports what a stopped queue reports. `shardsHeld()` reads
0, and so do `Subscription.shardsHeld()`, `unorderedShardsHeld()` and `orderedUnitsHeld()`, while the stop is still
waiting up to `shedGrace` for handlers to finish and then releasing its leases; before, they went on counting the shards
and units being drained until the stop had released them. After `stopAbruptly()` they read 0 as well. A dashboard or
test that polled these during a shutdown sees the drop at the start of the stop rather than at its end.

A message enqueued on the instance while a stop is under way is no longer handed in memory to the owner of its shard
that is draining. It is inserted like any other row and waits in the table for the next owner of the shard: another
instance once the stop has released the lease, or this one when it is started again. Nothing is lost; the stopping
instance just no longer delivers it. (`1f31c123`)

#### 1.1.23 Shard-owned engine: `Subscription.close()` ends the subscription

`Subscription.close()` now calls `Subscription.unsubscribe()`; in 0.60.0 it called `stop()`. `close()` is what
try-with-resources and Spring's inferred destroy method for an `AutoCloseable` bean call when the subscription is let
go of, so it now ends it for good: both lanes stop and leave their `PostgresqlMessageQueue`, the queue's `start()`
no longer starts them, the subscription's own `start()` throws `IllegalStateException`, and the next `consume()` is
not counted as competing with it. Before, a closed subscription stayed registered: the queue's `start()` after its
`stop()` started it again, running a handler its caller had let go of, and the next `consume()` logged the
COMPETING consumer WARN and registered under a new instance id suffix (`<id>-1`).

What changes for you: if you closed a subscription and relied on the queue's `start()`, or the subscription's own
`start()`, to bring it back, it no longer comes back. Call `Subscription.stop()` instead to pause a subscription you
mean to start again; `stop()` still keeps it registered. A `Subscription` implementation of your own that does not
override `unsubscribe()` is unaffected: the default `unsubscribe()` only stops, as `close()` did; one that overrides
it now has `close()` call that override, unless it overrides `close()` too. (`214786d65`)

#### 1.1.24 Shard-owned engine: a restart waits up to 15 minutes for the handler of an ordered unit its stop kept, and a fair-share shed no longer waits longer

When a `stop()` keeps an ordered unit, because a key was still in its handler `shedGrace` after the stop began, 0.60.0
let a restart of the same queue, and a `PostgresqlMessageQueue` `consume()` registering under the stopped
subscription's instance id, take that unit back at once, delivering the key's message again beside the handler still
running it (§3). They now wait for that handler before they take the unit or the id, and the wait is bounded by a new
setting, `stuckHandlerTimeout`, **on by default at 15 minutes**: once the kept key has been in its handler longer, the
queue stops waiting and takes the unit over under a new fence, as an unclean takeover. The stuck handler's
acknowledgement is refused and its message delivered again, with an attempt charged, so **that key may run in two
handlers at once** - the stuck one and the new one. That is at-least-once, the same trade classic `DurableQueues` makes
at its `messageHandlingTimeout`. Without the bound, a handler that never returned would leave the unit's keys
undelivered, and the id out of use, until the process restarted.

What to do: **a queue whose ordered handlers legitimately run longer than 15 minutes must raise the bound**, or such
a handler still running when its queue is stopped and restarted is started a second time beside itself. Per queue:
`essentials.shard-owned-queue.stuck-handler-timeouts.<queue-name>: 2h` in the Spring Boot starter (a name with a `:`,
such as an `Inbox:<name>` queue, in brackets: `"[Inbox:orders]": 2h`), or `settings.withStuckHandlerTimeout(...)`
passed to that queue's `PostgresqlMessageQueue.builder().setSettings(...)`. A per-queue name must equal the queue's
name exactly, case included, and a map key bound from an environment variable arrives in lower case; the starter logs
each queue it builds with its own bound at INFO, and once the application is ready one WARN names every per-queue name
no queue had been built under by then, since that queue runs on the shared bound. (`1f8b9b6d4`) For every queue:
`essentials.shard-owned-queue.stuck-handler-timeout`, or `ShardOwnerSettings.builder().setStuckHandlerTimeout(...)`.
There is no off switch; a value longer than the process will run makes the wait unbounded.

The wait is not silent: once a kept key has been in its handler longer than `leaseTtl`, the queue logs a
WARN naming the queue, the unit, the key(s), how long they have run and the bound, at most once a minute per unit, and
one more WARN when it stops waiting; `ShardOwnerMetrics.keptUnitsInHandler()` counts such units (§2). (`7af80bc49`)

**A kept unit whose stop has not finished flushing it is given up too.** Nothing hands a kept unit back or gives it up
while the stop that kept it is still flushing its acknowledgements, and that flush can run long - a connection hung
with no `socketTimeout` on the `DataSource`, after a network partition say, answers that come slowly, or a long wait
for a pool connection. A unit still waiting for that flush once it has been kept longer than `stuckHandlerTimeout` -
or, when that is longer, than 2 s plus the network timeout the stop's thread sets on its connections (`leaseTtl`, or
2 s when that is longer; §3, the entry on a stop's hand-back on a connection that never answers), so a short
`stuckHandlerTimeout` still gives a slow flush at least the 2 s plus one full network-bound wait - is given up with one
WARN, whatever its handler does: with a key still in a handler a run of the queue takes it over under a new fence,
delivering the messages in those handlers again; with none it is handed back, unless that hand-back fails.
`MessageQueue.releaseKeptOrderedUnit` then releases such a unit, a stopped queue's included, rather than answering
`BUSY` (§2). A unit that has waited for that flush longer than that network timeout is reported held up, at most once
a minute, with or without a key in a handler: the WARN says the stop's flush has not returned and that
`releaseKeptOrderedUnit` answers `BUSY` until it does or the unit is given up. That flush does not keep a
`PostgresqlMessageQueue` subscription's instance id out of use: the lane that kept the unit holds the id only through
the unit, until it has been handed back, given up or released on request (§3, the entry on a stop's hand-back on a
connection that never answers). Should that flush return once the unit was given up, the stop logs
that at INFO, not as a unit it keeps; and an acknowledgement a flush has refused for a unit this instance has already
released, or found taken, is not counted in `fencedOutAcks` nor logged as a lost lease, on either lane - nor is one
refused after a release of the row that failed with no error answer from the database (a timed-out read or a lost
connection), which may have gone through on the server. After such a release, a unit then found no longer held under
its fence, by the heartbeat's lease check or by a refused takeover charge, is not counted in `leasesLost`
(`QueueStatistics`) either: the lease check logs such a unit at INFO rather than in its "lost ... to another instance"
WARN, and a refused takeover charge logs it at DEBUG rather than in its "takeover charge rejected ... lease lost" WARN.
An alert on any of these misses only a unit this instance was giving up. An unordered shard whose fair-share shed
release failed that way is shed again on later heartbeat ticks, whatever the fair share says, until a release succeeds -
even once the fair share has risen to keep it - so it does not go on serving with that release in doubt. While that
release, or the attempt charge made before it, keeps failing, the instance serves the shard on, logs each failed
charge at WARN and a failed release at WARN with the stack trace at most once a minute per shard, naming how many
release attempts failed since the last such WARN (the others at DEBUG), and still takes shards no instance holds, up to its fair share: a heartbeat tick goes on to
acquire unless it serves more than its fair share, not counting shards whose release is in doubt. What to do:
nothing. (`6fd885d93`, `66b907912`, `3e719bf2a`, `1afcfabd1`, `9d2007abf`, `042dc5bc2`, `bc7f2786f`)

The same `stuckHandlerTimeout` now ends a wait for an ordered handler which 0.60.0 left unbounded: a fair-share shed of an ordered
unit, which waits for the keys in its handlers and otherwise keeps the unit and retries on the next heartbeat. A
handler that never returned left the cluster unbalanced and the unit's other keys undelivered until the process
restarted. Once a key has been in its handler longer than `stuckHandlerTimeout`, the shed releases the unit with every
key still in a handler - that one and any other still running - and logs one WARN naming the queue, the unit, the
key(s) and how long the longest has been in its handler. The next owner delivers each of those messages again, possibly
beside its handler, so **those keys, too, may run in two handlers at once**, and `keptUnitsInHandler()` counts the unit
while a handler of it runs on. The same advice applies: raise the bound for a queue whose ordered handlers legitimately
run longer. The hand-back of a dead pump's unit, new in this release, is bounded the same way (§2). (`03f9153d8`)

**Every one of these releases counts the delivery in the handler it stops waiting for as an attempt**, as the takeover
of a kept unit at the bound does: before a shed or a dead pump's hand-back releases a unit past `stuckHandlerTimeout`,
each message in a handler longer than `stuckHandlerTimeout` is charged one attempt, and a message in a handler for
less - another key of the unit that entered its handler just before - is released uncharged, as the unordered lane does;
before `MessageQueue.releaseKeptOrderedUnit` releases one with a key still in its handler (§2), each message still in a
handler is charged one attempt. A release retried on a later tick does not charge a message it already charged. When the
release is refused after the charge and the owner keeps the unit, a failure its handler then reports counts on from the
charged attempt instead of undoing the charge. A message that reaches its `maxAttempts` by the charge is dead-lettered
then, with an error saying its unit was released while it was still in a handler. The next owner finds the unit released
and charges nothing itself. Before this, those releases charged nothing either, and a handler that never returns records
no failure, so a message whose ordered handler hangs on every delivery - a deadlock on its own data, a remote call with
no timeout - was handed from owner to owner with its attempt count unchanged and delivered again after every release,
never reaching the dead-letter table, each time leaving one more handler hung. Now it is dead-lettered after
`maxAttempts` such releases, and its key is blocked behind the dead letter as after any other. The charge is counted in
`ShardOwnerMetrics.takeoverAttemptBumps` of the releasing queue, and the release's WARN says how many messages were
charged and which keys were dead-lettered. What to do: nothing, unless a queue's ordered handlers legitimately run past
its `stuckHandlerTimeout` - then each such shed or release costs that message an attempt, and enough of them dead-letter
it, so raise the bound as above. A `QueueObserver` that throws when told of such a dead letter does not hold the release
up: the attempt and the move are made by then, so the unit is released all the same - a `releaseKeptOrderedUnit` answers
that it released it - and a WARN names the observer's failure. A message deleted through the admin API while in its
handler is not charged and is not asked for again, so it does not hold such a release up either. A handler that returns
after the charge has dead-lettered its message is neither acknowledged nor counted in `delivered`: one that completes
logs a WARN saying that resurrecting the dead letter delivers the message again, and one that throws logs a WARN with
its exception and is told to `QueueObserver.deliveryFailed` with the charged count (not for a requested redelivery). A
handler that returns before the charge has taken its message has its outcome recorded as usual (acknowledged when it
completed, its failure recorded when it threw), and the charge neither charges nor dead-letters that message; the unit's
other handlers that return while the charge runs do not wait for its database statements, bar one returning while the
charge is still choosing which messages to charge. Your `QueueObserver` is told of the charge's dead letters after the
charge's database statements, not during them, so a slow `deadLettered` callback does not hold up the unit's handlers
returning meanwhile. As a result, a handler failing meanwhile may be reported to `deliveryFailed` before its message's
`deadLettered`; an observer must not assume the reverse order. A dead-letter move that fails although it went through on
the server - the database committed it after the driver stopped waiting for the reply, as a `releaseKeptOrderedUnit`
does past its network wait bound - is reported only when a retried release makes the move again: the retry, finding the
row gone, looks for the message in the dead-letter table at the attempt count the charge gave it, so a dead letter a
later owner of the unit made of the same message is not taken for this one's unless an operator retried the message
meanwhile, and the message is counted in `deadLettered`, `QueueObserver.deadLettered` is told of it, and its key is
blocked and, on a release on request, named in the answer's `deadLetteredKeys`, as for any other move. The unordered
lane's charge, below, does the same. A kept unit's hand-back once its handler returns, and a release on request made
once no key of the unit is in a handler, make the move again before releasing the unit too - each names the
dead-lettered key in a WARN. So do, on both lanes, a dead pump's hand-back and a `stop()` releasing a unit whose handler
has returned since an earlier charge of it - the hand-back's own on an earlier heartbeat, or a shed's - left the move
owed; an unordered shard's messages have no key, so its release WARN names the dead-lettered seq instead. While the
move keeps failing, the dead pump's hand-back holds the unit and retries on the next heartbeat, and a `stop()` leaves
its lease unreleased, as when its charge fails, with a WARN either way. A handler that completes - returns without an
exception, its thread not interrupted - while its message's move is owed settles that move: none of these releases
makes it, the completion is recorded as for any other (counted in `delivered`, its acknowledgement queued) and
its key is not blocked. Only a handler that threw, a requested redelivery included, or returned interrupted leaves the
move owed. The cost is that the next owner may deliver that message again (always on a dead pump; on a live owner when
the release beats the acknowledgement flush). A move that may have gone through on the server before
the handler completed is still looked for in the dead-letter table, and reported as above when found. When the kept unit is instead
taken over, the move is not made again, and that dead letter is then reported nowhere, though it blocks its key. A move
the database refused with an error of its own (an SQL state outside class `08`, such as a trigger's refusal or a
cancelled statement) was rolled back, and its retry does not look for it. Such a WARN names the hand-back the dead
letter was found in ("Found while handing the unit back ..."), not that the unit was handed back: a later move of the
same hand-back, or its release, may still fail and leave the unit held. A `releaseKeptOrderedUnit` made with a key
still in a handler whose charge dead-letters a message but that then does not release the unit - the charge fails
after a move, the release fails, or it finds the unit taken over - names the dead-lettered keys in a WARN of its own,
as a later request or hand-back with no key in a handler does not name them. Every release of a unit's lease row by
the owner serving or keeping it now also checks for a dead-letter move still owed: a release that would leave one owed
leaves that unit unreleased, its lease row still naming the instance, with a WARN that names the unit (at most once a
minute per unit, the rest at DEBUG), and releases the others; the unit is released by a later release once the move
is made. A `releaseKeptOrderedUnit` that comes to such a unit answers `BUSY` - ask again.
(`6337f7d89`, `2fdeb3db0`, `bfa961ec5`, `dbae0986f`, `7dbd517be`, `e699c9d44`, `895b1a498`, `52c5e943e`, `56790ca82`, `b785a52b2`, `ab601d493`, `311efc4ad`, `055e013d6`, `4cce8acbb`, `29e3893d2`, `2c7ab3791`, `6be1d7215`)

**The unordered lane now charges a hung handler's delivery too, past the same bound.** It waits for no handler: a
fair-share shed and a dead pump's hand-back release an unordered shard at once, and `stop()` once `shedGrace` has run
out, with whatever is still in its handlers, and the next owner, finding the shard released, charges nothing. With a
clean handover now charging nothing (§1.1.18), a message whose unordered handler hangs on every delivery would be
delivered again after every rebalance or restart - and, with the dead-pump hand-back new in this release (§2), after
every dead pump - with its attempt count unchanged, never reaching the dead-letter table, each delivery leaving one more
handler thread hung and holding a handler permit; in 0.60.0 takeovers raised its stored count, but nothing ever
dead-lettered it. Now, before such a release, each message that has been in its handler longer than
`stuckHandlerTimeout` is charged one attempt, and dead-lettered at its `maxAttempts` with the same error; a message in
its handler for less is released uncharged, as before - a slow handler is not taken for a hung one. The charge is
counted in `ShardOwnerMetrics.takeoverAttemptBumps` of the releasing queue, and a release that charged logs one WARN
naming the shard, how many messages it charged and which it dead-lettered. What to do: nothing, unless a queue's
unordered handlers legitimately run past its `stuckHandlerTimeout` - then a rebalance or stop that releases a shard
while one runs costs that message an attempt, so raise the bound as above. A message deleted through the admin API while
in its handler is not charged, and a `QueueObserver` that throws when told of another message's dead letter then still
leaves the shard released. A handler that returns after the charge before a shed, a dead pump's hand-back or a `stop()`
has dead-lettered its message is neither acknowledged nor counted in `delivered`: one that completes logs a WARN saying
that resurrecting the dead letter delivers the message again, and one that throws logs a WARN with its exception and is
told to `QueueObserver.deliveryFailed` with the charged count (not for a requested redelivery). A handler that returns
before the charge has taken its message has its outcome recorded as usual (acknowledged when it completed, its failure
recorded when it threw), and the charge neither charges nor dead-letters that message; the shard's other handlers that
return while the charge runs - a message in its handler for less than `stuckHandlerTimeout`, say - do not wait for its
database statements while holding their handler permit, bar one returning while the charge is still choosing which
messages to charge. Your `QueueObserver` is told of the charge's dead letters after the charge's database statements,
not during them, as on the ordered lane.
(`18e423d1d`, `7dbd517be`, `e699c9d44`, `895b1a498`, `52c5e943e`)

---

## 2. New features

- **`essentials.fenced-lock-manager.instance-id`** (PostgreSQL and MongoDB starters) sets the fenced-lock-manager
  id explicitly; `FencedLockManagerSettings.defaultLockManagerInstanceId()` returns the default (§1.1.4).
  (N-8, `206c7e58`)
- **`DocumentDbRepositoryFactory.createForCharSequenceTypeId(Class|KClass)`** creates a repository for an entity
  whose id is a Java `CharSequenceType`. Until now only a raw-typed `create(...)` compiled from Java, and it failed
  at runtime. (DD-3, `47285813`)
- **`Exceptions.forEachThenThrowFirst(items, action)`** and **`Exceptions.forEachCollectingFailures(items, action,
  failure)`** (`shared`) run an action for every item even when it throws for an earlier one, then throw (or return)
  the first failure as it was thrown, later distinct ones added to it as suppressed - once each: an instance thrown
  for several items is added once, and one the failure it would be added to already carries as suppressed when it is
  about to be added - also one a nested call with the same `failure` added while the loop ran - is not added again. A
  non-critical `Error` is collected like a `RuntimeException`; a critical one (`Exceptions.isCriticalError`) is
  rethrown at once, with the failure collected so far added to it as suppressed, unless it already carries it (a
  nested call with the same `failure` that hit the error added it first). (`62ad7367e`)
- **`Locks.lockWarningOnce(lock, warnAfter, warn)`** (`shared`, package `dk.trustworks.essentials.shared.concurrent`)
  takes a `Lock`, waiting as long as `ReentrantLock.lock()` would - an interrupt deferred, and set on the thread again
  once the lock is held (after the warning, by the lock's own `lock()`, as every `java.util.concurrent` lock does) -
  but calls `warn` once the wait has lasted `warnAfter`, and after each further `warnAfter` until `warn` returns
  `true`. The shard-owned queue's `ShardOwnedQueue.start()`, `PostgresqlMessageQueue`'s
  `consume()`/`start()`/`Subscription.start()` and the adapter consumer's `start()` wait for their lifecycle lock
  through it. An interrupt during that wait no longer pushes the one WARN out by a further budget. Once `warn` has
  returned `true` the wait is `lock.lock()` itself, so on a fair lock the waiter keeps its place in the queue instead of
  giving it up to later waiters every `warnAfter`; until then each `warnAfter` is a timed `tryLock`, which does give
  it up. (`6978e1b38`)
- **`Locks.tryLockDeferringPendingInterrupt(lock, wait)`** (`shared`, same class) waits up to `wait` for a `Lock` and
  returns whether it took it. An interrupt pending when it is called neither cuts the wait short nor makes a free lock
  look taken, as it does for a plain timed `tryLock`, which throws at once. An interrupt arriving during the wait ends
  it without the lock, for a lock whose timed `tryLock` can be interrupted, as every `java.util.concurrent.locks`
  lock's can (the `Lock` interface does not promise it). Either way the interrupt is set on the thread again before it returns. The stop of
  `PostgresqlMessageQueue` and of the adapter's `ShardOwnedDurableQueueConsumer` take their lifecycle lock through it,
  10 ms at a time, in place of two private copies. (`6987d84b8`)

- **Schema descriptions:** `SchemaChange.describedAs(String)`, `description()` and `describeObject()`,
  `SchemaChangeSet.describe()`/`describeObjects()`, and `PostgresqlEmitSchemaApplier.exitTriggered()`/
  `isExitTriggered()`. A `SchemaChange` built with the 4-argument constructor keeps working; the description is not
  part of the checksum, so no recorded schema history changes. `description` is a fifth record component, which
  you notice in two places only: a record pattern over `SchemaChange` (`case SchemaChange(var id, var object, var
  statements, var repeatable)`) needs a fifth binding, and `equals`/`hashCode` include the description, so two
  changes that differ only in their description are no longer equal. Nothing in Essentials compares changes that
  way: duplicate contributions are still matched on module, change id and object name, and checked by checksum.
  (RN-5, `07d23d7d`)
- **`essentials.durable-queues.require-active-unit-of-work-for-enqueue`** (default `false`), also
  `setRequireActiveUnitOfWorkForEnqueue(...)` on the PostgreSQL, MongoDB and shard-owned builders. When `true`, an
  enqueue with no active `UnitOfWork` or Spring transaction throws `NoActiveUnitOfWorkException`, so an `Outbox`
  send can no longer silently commit outside the business transaction. `Inbox.addMessageReceived` is unaffected:
  it opens its own unit of work. (RN-2, `3c783626`)

- **`MultiTableChangeListener.builder()`**, as `LLM-foundation.md` already documented. Required: `setJdbi`,
  `setJsonSerializer`, `setEventBus`. Optional: `setPollingInterval`, `setFilterDuplicateNotifications`,
  `setChannelDeduplicationPolicy` (new `ChannelDeduplicationPolicy`), `setLivenessCheckInterval`,
  `setConnectionLossLogLevel`, `setConnectionLossLogInterval`, `setPollThreadName`, and
  `setListenerJdbi`/`setListenerDataSource` for a LISTEN connection outside the pool. The 5-argument constructor
  stays. (N-14, `60256d4e`)
- **`essentials.multi-table-change-listener.dedicated-connection=true`** (PostgreSQL starter, default `false`) gives
  the listener its own LISTEN connection outside the pool, built from the same `JdbcConnectionDetails`.
  (N-14, `60256d4e`)

- **`awaitProcessed`: wait until a processor has handled an event.** `EventProcessor` and `ViewEventProcessor` get
  `awaitProcessed(AggregateType, GlobalEventOrder, Duration)`, `awaitProcessed(PersistedEvent, Duration)` and
  `awaitProcessedAsync(...)` (`Mono<Void>`). An event counts as processed when the subscription's resume point is
  past it, it is not a gap the subscriber still has to fill, and no message for its aggregate is queued in the
  processor's Inbox or durable queue (a dead letter counts as queued). On timeout it throws
  `AwaitProcessedTimeoutException`, naming the processor, aggregate type, order, resume point, reason and queue
  counts. `InTransactionEventProcessor` has the same methods and returns at once. It sees only the processor in this
  JVM: a `ViewEventProcessor` whose lock another instance holds times out. Integration tests can replace guessed
  Awaitility timeouts with it; `LLM-postgresql-event-store.md` § "Awaiting a processor" has an example.
  The gap check asks the event store through the new role interface `AwaitedGapInspector` (`isAwaitedAsGap`), which
  `PostgresqlEventStore` and `CdcEventStore` implement; it covers transient-gap rows and the middle of a wide gap
  that is awaited in memory only - under CDC also one the running subscription found itself, which only its
  delivery tracker knows about. **An `EventStore` of yours that decorates one of them must implement
  `AwaitedGapInspector` and forward it to its delegate**, like `forgetGapMiddlesAwaitedInMemory`. Otherwise the check
  is off, the processor logs one WARN when it starts, and an event that committed after a later one can be reported
  processed before its gap fill was handled. The WARN begins "If you call awaitProcessed on this processor" - an
  application that never calls `awaitProcessed` can ignore it. A `CdcEventStore` built over a store that does not
  implement `AwaitedGapInspector` logs the same kind of WARN once: it then gives a complete answer only while CDC is
  active. Forward the new `default` method `AwaitedGapInspector.transientGapsReadFrom()` as well: a `CdcEventStore` over
  your decorator then reads the subscriber's transient gaps once per check instead of twice; without it the answer is
  the same, with the second read. (ES-4, `944633a4`, `6ff9ca9e`, `9fb0ea51`, `13c13051`, `4fa85508`, `948d5852`,
  `e30f83fe`)
- **`AppendedEvents.recordDuring(() -> commandBus.send(cmd))`** (`postgresql-event-store`,
  `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.AppendedEvents`) returns what the action
  returned plus every `PersistedEvent` appended during it, so a caller has an order to wait for. It records at
  `PostgresqlEventStore.appendToStream`, so it covers every appender: `EventStreamDecider`, decider `CommandHandler`,
  Kotlin deciders, `StatefulAggregateRepository`/`FlexAggregateRepository` (when their `UnitOfWork` commits during
  the action) and direct `appendToStream`, each event once, including through `CdcEventStore`. Same thread only
  (`send`, not `sendAsync`). (ES-4, `944633a4`, `736f2db6`)
- **`Inbox` default methods** `hasOrderedMessageQueuedForKey(String)` and `getNumberOfDeadLetterMessages()`,
  implemented by the DurableQueues-based Inbox; a custom `Inbox` throws `UnsupportedOperationException` from them
  until it implements them. (ES-4, `944633a4`)
- **Sealed commands no decider handles are reported at startup.** `EventStreamDeciderAndAggregateTypeConfigurator`
  walks `getPermittedSubclasses()` of sealed command types and WARNs for each permitted command no command handler on
  the `CommandBus` handles - an `@CmdHandler` registered earlier counts; before, it showed only at the first send, as
  a `NoCommandHandlerFoundException`. A decider declared through a sub-interface of `EventStreamDecider` or a generic abstract base class takes part, like one implementing it directly. (CB-4, `9f4ddecf`, `796e4578`, `dea6f3d5`)
- **`CommandBus` lists its handlers, and warns about a command type handled twice when the handler is added.**
  `CommandBus` gains `getCommandHandlers()`, `findCommandHandlersCapableOfHandling(Class)` and
  `findCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler)`, all `default` methods, so a `CommandBus` of
  your own still compiles; its `getCommandHandlers()` returns an empty list, which means "not tracked", not "no
  handlers". `CommandHandler` gains `default handledCommandTypes()` (empty by default; diagnostics only, never
  routing), implemented by `AnnotatedCommandHandler` and `EventStreamDeciderCommandHandlerAdapter`.
  `LocalCommandBus` and `DurableLocalCommandBus` now log a WARN when an added handler makes a command type handled by
  more than one handler - every send of it fails with `MultipleCommandHandlersFoundException` - for `@CmdHandler`
  handlers too, not only deciders. Registration still succeeds. The duplicate WARN for deciders now comes from the
  `CommandBus` logger instead of the `EventStreamDeciderAndAggregateTypeConfigurator` logger; the finding is still
  added to the run report. (`796e4578`)
- **`@IgnoresEventTypes({SomeEvent.class, …}, eventNames = {…})`** on a `ViewEventProcessor` marks event types
  it skips on purpose: they, and their subtypes, are counted but not warned about (§1.1.11). `eventNames` covers
  events persisted by name only. (CB-4, `b786b107`)
- **The Essentials run report.** `EssentialsRunReport` (foundation, package `startup`) collects the
  findings of the checks in this release, and every starter prints them as one block on `ApplicationReadyEvent`:
  at ERROR if any finding is an error, at WARN if any is a warning, otherwise one INFO line "Essentials startup
  report: no findings". Findings are grouped by severity, each with its fix. It covers removed properties, the
  wire-format check, duplicate command registrations, sealed commands with no decider, views with unhandled event
  types, a shard-owned queue built without a UnitOfWork factory, incomplete redelivery policies, an enqueue outside
  a unit of work during startup, and schema `emit`/`validate` notes. Every check still logs where it runs, so
  nothing is lost outside Spring. The report is also available as the `essentialsRunReport` bean.
  It keeps collecting while the application runs: a finding raised after start-up or on another thread (an enqueue
  outside a `UnitOfWork`, a `ViewEventProcessor` started later) is added to the context's report when that context
  is the only one open in the JVM; in a test JVM with several contexts open it is only logged by its check. When a second report registers, one INFO
  line says so, and the run report and the periodic block name the period in which runtime findings were not
  collected, rather than reporting "no findings" for it. At most 20 such ended periods are listed, plus the one under way: the first 19 and the
  latest, with one line counting the ones in between ("also not collected in K more periods between X and Y"), so
  the report never claims findings went uncollected while they were collected. A periodic block that carries only that period, and no new
  findings, is logged at INFO, not WARN. A report registered twice stays registered until both
  registrations are closed. Every
  `essentials.run-report.new-findings-interval` (default `5m`; `0` turns it off) the findings not logged before
  are logged as one block, and nothing is logged when nothing is new. When the context closes, the complete report
  of the run is logged once: every finding with how often it was seen and when first and last. A shutdown log can be
  lost (SIGTERM, log shipping), which is why the periodic block exists. Identical findings are kept once with a
  count; at most 500 distinct findings are kept, further ones only counted. Without Spring,
  `EssentialsRunReport.registerAsRunReport()`, `logNewFindings()` and `logRunReport()` do the same. A check that
  cannot run now logs its own failure at ERROR instead of DEBUG. (`552576f1`, `b4614eca`, `d99a3602`, `b3fb8379`, `31264d1a`)
- **Wire-format check for mappers Essentials did not build.**
  `EssentialsObjectMappers.findWireFormatDeviations(ObjectMapper)` round-trips small probe types and compares them
  with the 0.50 bytes (field access, getter and `is`-getter visibility, ISO dates, numeric durations,
  `FAIL_ON_NULL_FOR_PRIMITIVES`, `FAIL_ON_UNKNOWN_PROPERTIES`, `USE_BIG_DECIMAL_FOR_FLOATS`, and value types when
  `types-jackson3` is present). The starters run it on every `JSONSerializer`/`JSONEventSerializer` bean backed by a
  `Jackson3JSONSerializer`, your own replacement bean included, and log one ERROR banner per deviating bean. Boot's
  web mapper is not checked. New property `essentials.json.wire-format-check=warn|fail|off`, default `warn`.
  (N-1, SJ-3, `c9314dc3`)
- **`UnitOfWork` accessors on every event processor.** `AbstractEventProcessor` - so `ViewEventProcessor`,
  `EventProcessor` and `InTransactionEventProcessor` - gains `getCurrentUnitOfWork()` (`Optional`),
  `getRequiredUnitOfWork()` and `getUnitOfWorkFactory()`, all on the event store's own factory, the one the processor
  handles its messages with. A projection no longer needs a `HandleAwareUnitOfWorkFactory` injected next to its
  dependencies: inside a `@MessageHandler`, write through `getRequiredUnitOfWork().handle()`; in a constructor or
  `onSubscriptionsReset`, use `getUnitOfWorkFactory().usingUnitOfWork(uow -> ...)`. `getRequiredUnitOfWork()` never
  opens a `UnitOfWork`: where none is active - a `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` body outside
  `usingUnitOfWork`/`withUnitOfWork`, a constructor, a reset - it throws `NoActiveUnitOfWorkException`. Purely
  additive, except that a processor subclass already declaring one of these three method names with an incompatible
  return type no longer compiles; rename it. There is deliberately no processor `usingUnitOfWork(uow -> ...)` overload:
  it would make Kotlin's `usingUnitOfWork { ... }` an overload ambiguity.

- **`PostgresqlDurableQueuesBuilder.setCreateMultiTableChangeListener(boolean)`** (default `true`) decides whether
  `build()` creates a `MultiTableChangeListener` when none was set (§1.1.17). (`a72aa558`)
- **`QueuePollingOptimizer.messageScheduledFor(Instant)`**, a default method: the queue tells its optimizer when a
  retry, a delayed enqueue or a delayed resurrection becomes deliverable, so a backed-off queue polls then. Also
  `queuePollingReturnedNoMessages(Instant claimedAt)` and `queuePollingReturnedMessages(List, Instant claimedAt)`,
  which tell it when the claim ran; by default they call the existing methods. A custom optimizer that ignores all
  three keeps working. The framework now only calls the `Instant` overloads, so a subclass of
  `CentralizedQueuePollingOptimizer` or `SimpleQueuePollingOptimizer` must override those (§1.1.21); the no-arg forms
  are `@Deprecated(since = "0.60.1")` on those two classes. (`7a742881`, `13461645`, `d42c4bc3`)
- **`PostgresqlDurableQueuesBuilder.setOrderedClaimSlowThreshold(Duration)`** (default 10 ms,
  `PostgresqlDurableQueues.DEFAULT_ORDERED_CLAIM_SLOW_THRESHOLD`): how slow an ordered claim must be to count towards
  switching statements (§3). (`7e80c23c`)
- **`PostgresqlDurableQueues` implements `AutoCloseable`** (§1.1.17). (`7e80c23c`)
- **`DurableQueues.releaseUndeliveredMessage(QueueEntryId, Duration)`** (operation `ReleaseUndeliveredMessage`,
  interceptable via `DurableQueuesInterceptor.intercept(ReleaseUndeliveredMessage, …)`): returns a claimed message
  that never reached a handler to the queue without charging a redelivery attempt or touching its last delivery
  error; it returns empty when the message is no longer marked as being delivered. Both consumers use it when they
  stop (§3). A `default` method that falls back to `retryMessage`, so a custom `DurableQueues` implementation keeps
  compiling but should override it; `PostgresqlDurableQueues` and `MongoDurableQueues` do. A release is not a
  delivery outcome: shutdown releases are no longer reported to the `DurableQueueMessageObserver` as `messageRetried`,
  so `QueueStatistics` and the Micrometer retried counter do not count them. (`c2cca365`)
- **`HandleQueuedMessage` carries the delivering consumer's state**: `consumerState()`, returning
  `HandleQueuedMessage.ConsumerState` `STARTED`, `STOPPING` or `UNKNOWN` from a single live read (a `stop()` can begin
  while the handler runs; each call re-reads it, so ask once and branch on the answer). Set through
  `HandleQueuedMessage.builder().setConsumerStarted(BooleanSupplier)`. Both
  consumers set it, so a `DurableQueuesInterceptor` on `HandleQueuedMessage` can tell a handler interrupted by its
  consumer stopping from one that re-set the interrupt flag for a reason of its own;
  `SingleOperationTransactionDurableQueuesInterceptor` uses it to acknowledge the latter inside the handler's
  transaction, and to roll back the former's writes (§1.1.1). An operation built with the existing constructor, as the
  shard-owned adapter does, answers `UNKNOWN`.
- **`DefaultDurableQueueConsumer.getQueuePollingOptimizer()`**, and `SimpleQueuePollingOptimizer` implements
  `messageScheduledFor` and `queuePollingReturnedNoMessages(Instant)` (§3). (`6777b8b9`)
- **Shard-owned engine: `Subscription.unsubscribe()`.** Ends a subscription for good: stops both lanes and takes
  them out of their `PostgresqlMessageQueue`, so the queue's `start()` no longer starts them and the next `consume()`
  is not a competing consumer. `stop()` keeps a subscription registered, as before; `close()` now calls
  `unsubscribe()` (§1.1.23). A `default` method that stops, so an implementation of your own still compiles
  (§3). (`f8b32d37e`)
- **Shard-owned engine: `ShardOwnerSettings.stuckHandlerTimeout`** (default 15 minutes,
  `ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT`), the bound on how long a queue waits for a handler before it
  treats it as stuck (§1.1.24); in the Spring Boot starter `essentials.shard-owned-queue.stuck-handler-timeout`, and
  per queue `essentials.shard-owned-queue.stuck-handler-timeouts.<queue-name>`. With it `ShardOwnerSettings.builder()`,
  which starts from `defaults()` so a caller sets only what differs, `settings.toBuilder()`, which starts from
  existing settings (`settings.toBuilder().setLeaseTtl(...).build()`), and `withStuckHandlerTimeout(Duration)` for one
  queue's own bound. The 0.60.0 constructor without the new setting still works, with the default, and is deprecated
  for removal; a record pattern over the 0.60.0 components no longer compiles (§1). And `ShardOwnerMetrics.keptUnitsInHandler()` (also in `snapshot()`): the ordered units a stop kept
  whose key is still in its handler, normally zero, and the units a shed or a dead pump's hand-back released at the
  bound while their stuck handler runs on. (`7af80bc49`, `03f9153d8`, `52aca6c2a`)
- **Shard-owned engine: release an ordered unit a stop kept, before `stuckHandlerTimeout`.**
  `MessageQueue.releaseKeptOrderedUnit(instanceId, unit)`, in the admin API `ShardOwnedQueuesApi.releaseKeptOrderedUnit` and
  `POST /shard-owned-queues/{queueName}/ordered-units/{unit}/release-kept?instanceId=…` (role `QUEUE_WRITER`). For a handler known to
  be hung: a stop keeps an ordered unit whose key is still in its handler, and until now nothing an operator could call
  freed it before the handler returned, the instance's liveness lapsed or the bound fired. The call flushes the unit's
  acknowledgements and releases its lease row, so any instance below its fair share takes it under a new fence - and
  **delivers the messages still in a handler again, possibly while those handlers still run them**, the reordering and
  duplicate delivery the wait exists to prevent; each of those messages is charged an attempt before the release, and
  one that reaches `maxAttempts` by it is dead-lettered instead (§1.1.24). It takes the instance id the WARN
  that reported the unit kept names, required, and touches only a unit a subscription running under that instance
  id kept whose row still names it - a unit of the same number kept under another instance id is left alone - and
  answers from that process's memory: call the instance whose WARN named the unit. Nothing passes the request on to
  another process, so behind a load balancer send it to that instance's own address - a pod address, or a tunnel such
  as `kubectl port-forward` - not the balancer's (why the request is not passed on through the database:
  `docs/durable-queue-shard-owned.md` §18.3). The answer is
  `{answeredBy, releases}` (`KeptUnitReleases`, `ApiShardOwnedKeptUnitReleases`): `answeredBy` names the instance ids
  the answering process has for the queue, so an empty `releases` from another process, or for another instance id,
  still says which process answered. Each entry of `releases` says `RELEASED` (with the keys in a handler when the release's flush took its batch
  of acknowledgements: a handler that returned before that is acknowledged and not named, and a key named is not
  acknowledged), `ALREADY_TAKEN` or `BUSY`
  (ask again) - `BUSY` too while the stop that kept the unit is still flushing it, except once the unit has been kept
  past the bound that gives such a unit up (§1.1.24): the call reviews the kept units first, so it then releases the
  unit rather than answering `BUSY`. Each entry also says how many messages in a handler the release charged an
  attempt (`charged`) and which keys that charge dead-lettered (`deadLetteredKeys`; zero and empty on `BUSY`): such a
  key is blocked behind its dead letter until its dead letters are resurrected or deleted, so these are the keys that need a follow-up. A `default` method on both `MessageQueue` and `ShardOwnedQueuesApi` that releases nothing, so an
  implementation of your own of either still compiles. The admin console's Shard-owned queues page offers it as
  **Release kept unit**, which asks for the instance id and the unit, acts on the instance that takes the request and
  shows its answer, naming the instance ids that answered and the keys the release dead-lettered. (`e23164f9e`, `7eaa0c7ef`, `97a4795ed`, `3da91face`, `597a510f4`, `6fd885d93`, `66b907912`, `a0c74226b`, `211f29603`)
- **Shard-owned engine: `spi.RedeliveryRequested(delay)`.** A handler throws it to ask for redelivery after `delay`
  without it counting as an attempt, being reported as a failure or leading to a dead letter (§1.1.18).
  (`a4f541e4`, `f9fdd99b`)
- **Shard-owned engine metrics:** `sharedInstanceIdHeartbeats` (§3) and `cursorRowsRead`, the rows the unordered
  lane's reads returned; divided by `delivered` it is the read amplification. (`d54671a6`, `e06910c2`) Also
  `takeoverFailures` and `shardsAwaitingTakeover`, the shards this instance holds a lease on but does not serve
  because their takeover keeps failing (§3); readable through `ShardOwnedQueue.metrics()`, and through
  `MessageQueue` as the observer callback and gauge in the next entry. (`c21696d4`, `a7943b30`) And `pumpFailures`, every
  delivery pass for a shard that failed with the connection still healthy (a statement the database refused, or an
  exception from the engine, not from a handler); the shard keeps its lease and is retried 200 ms later, while the
  pump's other shards keep their own rate, so a `pumpFailures` rising by about five a second per shard means a shard
  that delivers nothing and that `QueueHealth.unownedShards` cannot see
  (§3). Also `batchedReadFailures`, the ordered lane's per-queue batched reads that failed (the shards then read for
  themselves). Both are readable only through `ShardOwnedQueue.metrics()`: `PostgresqlMessageQueue` keeps its metrics
  private and no `QueueObserver` callback reports them, so through `MessageQueue` (the Spring starter, the
  `DurableQueues` adapter) a failing pass is reported by the `ShardPump` ERROR log alone, at most one a minute per
  subscription (one per queue unless a queue is consumed twice in one process), naming one shard, and a failing batched read by a WARN at most one a minute per subscription, naming the queue and
  its shards. (`8ed621a2`) Also `pumpLoopFailures`, pump passes that
  ended on something thrown outside the per-shard guards and were reconnected from, and `shardsOnDeadPumps`, the
  shards that sit on a pump thread a critical error (`VirtualMachineError`, `LinkageError`) ended and whose leases
  this instance has not handed back yet. A dead pump's shards are handed back to the cluster rather than kept: the
  queue's next heartbeat releases their leases (an ordered unit once no key is in one of its handlers, or once one has
  been there longer than `stuckHandlerTimeout`), and another instance, or a live pump in this one, takes them over under a new fence. An
  ordered unit waits for its handler up to `stuckHandlerTimeout` (§1.1.24); once the wait outlasts `shedGrace` one WARN
  per unit names the keys still in a handler, as an abandoned shed does, since the unit stays leased and unserved
  meanwhile. Once a key has been in its handler longer than `stuckHandlerTimeout`, the unit is released with every key
  still in a handler - that one and any other still running - with one WARN, and the next owner delivers each of those
  messages again, possibly beside its handler; `keptUnitsInHandler()` counts the unit while a handler of it runs on.
  (`03f9153d8`) A
  release that fails is retried on every heartbeat, and until it succeeds the unit still counts towards the instance's
  fair share, so the instance takes no free shard in its place. From the death on, `ShardOwnedQueue.shardsHeld()` no
  longer counts such a unit, on either lane; `shardsOnDeadPumps` does. What
  they had handled but not acknowledged is redelivered. Ending one of a dead pump's shards runs that shard's own code
  right after the critical error; a throw there is logged at ERROR naming the queue, lane and shard, and the shard is
  still counted and handed back. The pump's other shards are still ended and counted, even when logging that throw
  fails too. Separately, the pump reads as dead on every path, a failed end included, so it gets no new shards. In
  0.60.0 a pump thread that died left its shards leased to the instance and delivering nothing until a restart. With
  no live pump left the instance acquires nothing, a queue started or restarted on that runtime included (it used to
  take every free unit, report it owned and hand it back a heartbeat later), and its shards stay unowned
  (`QueueHealth.unownedShards`) until the `ShardRuntime` is restarted, which the WARN reporting the hand-back then
  says ("for other instances only") instead of promising a live pump here; alert on
  `shardsOnDeadPumps` staying above zero (§3). Both are readable only through `ShardOwnedQueue.metrics()`:
  `PostgresqlMessageQueue` keeps its metrics private and no `QueueObserver` callback reports them, so through
  `MessageQueue` (the Spring starter, the `DurableQueues` adapter) a dead pump is reported by its ERROR log line
  alone, and the hand-back that follows by `QueueObserver.shardOwnershipChanged`. Also `sweepRecoveriesBehindCursor`
  (ordered lane only, `ShardOwnedQueue.metrics().snapshot()`): the part of `sweepRecoveries` whose rows the safe
  watermark had already passed when the head sweep found them. `sweepRecoveries` also counts a row that committed
  between a pass's cursor read and its sweep, which nothing stepped over; this one does not. It does count a
  delayed message, a retry or requested redelivery and a message whose handler was interrupted, each reached by the
  sweep after the cursor passed it, and a row a `watermarkCapped` advance stepped over; with none of those, a non-zero
  value means the watermark stepped over a row.
  `sweepRecoveries` itself is unchanged. (`495d8959c`)
- **Shard-owned engine: `QueueObserver.takeoverStalled(MessageId.Lane, int shard, boolean stalled)`** and the
  Micrometer gauge **`essentials.queue.shards.awaiting.takeover`** (`MicrometerQueueObserver.SHARDS_AWAITING_TAKEOVER_GAUGE`).
  The callback is told once when a shard's takeover starts failing and once when the stall ends (the takeover
  completes, or the lease ends), never per retry. `MicrometerQueueObserver` registers the gauge in its constructor
  and feeds it from the callback, so it costs no query; the Spring starter binds it to every queue whenever a
  `MeterRegistry` exists, the `DurableQueues` adapter's queues included. Every `MicrometerQueueObserver` on one
  registry with the same tags adds into the one gauge, so an observer re-created for a queue, or bound by hand next
  to the starter's, still publishes its stalls rather than leaving the gauge on the first observer's count. An observer
  takes a stall off the gauge only when it saw that lane and shard's stall start: one created while a shard was already
  stalled is told only that stall's end, and ignores it rather than ending another shard's stall. It is the signal `QueueHealth.unownedShards`
  and `essentials.queue.shards.unowned` cannot give: alert on it above zero next to them, summed across instances.
  A `default` method, so existing observers keep compiling. Unlike the other callbacks, an exception it throws, or a
  non-critical `Error` (an `AssertionError`, say), is caught and logged at ERROR, because it runs on a pump thread
  that serves other shards (§3), and every other registered observer is still told; only a critical error
  (`VirtualMachineError`, `LinkageError`) propagates. (`5b480fca`)
- **`Exceptions.prependRootCauseSummary(Throwable, String)`** and **`Exceptions.ROOT_CAUSE_PREFIX`** (`shared`): put a
  `Root cause: <root cause's toString()>` summary in front of a description of a wrapped throwable, and return the
  description unchanged for one that is not wrapped. The summary spans several lines when the root cause's message
  does, so the first line of the result is not always the whole root cause. It is the summary the PostgreSQL, MongoDB
  and shard-owned queues write in front of a stored delivery error (§1.1.15), now rendered in one place, so it cannot
  drift between them;
  the PostgreSQL and MongoDB stored text is unchanged, and the shard-owned text changes only as §1.1.15 describes
  (`<type>` alone for an exception without a message). `DeliveryErrorDescription.ROOT_CAUSE_PREFIX` stays, with the
  same value. (`7cf48338`, `9892f8be`)

The plugin's features are in [§4](#4-the-essentials-claude-code-plugin).

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Durable queues | A message in flight when its consumer stops is no longer dead-lettered; see [§1.1.1](#111-a-message-in-flight-when-its-consumer-stops-is-redelivered-not-dead-lettered) | `6ba568b5` |
| JSON (all Essentials mappers) | A document persisted before a primitive component was added failed every read with `Cannot map null into type int`: Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default, and Essentials left it on. It is now disabled, so the missing primitive reads as `0`/`false`, as under Jackson 2. Only reading changes; the written bytes do not | with §1.1.2 |
| Document DB | An entity with a non-public `@Id` field (Java `@Id private OrderId id`) failed every save, update and delete with `IllegalCallableAccessException`, which dead-lettered every projection message while startup looked fine. The field is now made accessible when the repository is created; if the JVM refuses, creation fails naming the entity and the field | DD-3, `47285813` |
| Document DB | `getById` threw a bare `NullPointerException` when nothing was found. It now throws `DocumentEntityNotFoundException`, naming the entity class and the id. It extends `NullPointerException`, so code that caught the old NPE still works | DD-5, `1b828ae2` |
| Document DB | Entity property names and JSON path segments were checked against the SQL reserved-keyword list, recursively and into JDK types, so ordinary names (`order`, `user`, `key`, `path`, `Optional.value`, `URI.path`) were rejected without saying why. They are now checked only for allowed characters and a 63-character maximum; they reach SQL only quoted or bound. Table names still reject reserved words, and that error now says "is a reserved SQL keyword" | DD-2, `ca2b2662` |
| Durable queues (shard-owned) | `ShardOwnedDurableQueues` joined only an Essentials `UnitOfWork` that was already bound. Under a plain Spring `@Transactional` or `TransactionTemplate`, `Outbox.sendMessage`, `sendAndDontWait` and `queueMessage` committed on their own connection and survived a rollback, so the transactional outbox was not transactional on this engine. The enqueue now joins the Spring transaction | N-15, `4248f0b3` |
| Event store | When creating or validating an aggregate type's event-stream table failed, the type stayed registered without a table: a retry did nothing and later appends failed with a bare "relation does not exist". The type is now registered only once its table is ready; the exception propagates and a retry runs initialisation again. Concurrent registrations of one type initialise it once | N-17, `0b572aaa` |
| Schema harness | In `essentials.schema.mode=validate`, an event-stream table error now names the `AggregateType` as well as the table, and says to register the type at startup and re-run `emit`. The `emit` script starts with a `-- Contents:` header listing each module's tables and their aggregate types, the emit log line lists the same, and a registration that arrives after the emit exit was triggered logs a WARN | RN-5, `07d23d7d` |
| Command bus | Two configurators registering the same command type failed only at the first `send`, with a `MultipleCommandHandlersFoundException` listing `EventStreamDeciderCommandHandlerAdapter@hash` entries. The adapter's `toString()` now names its `AggregateType` and deciders, and `EventStreamDeciderAndAggregateTypeConfigurator` WARNs at construction when a command type it registers is already handled by one of its adapters on that bus | CB-3, `7c0183fa` |
| Durable queues (shard-owned) | On the ordered lane, a message waiting out its retry backoff blocked its key only in the owning instance's memory. After a rebalance, a graceful stop or a crash, the new owner delivered the key's next message first, breaking per-key order. On takeover the new owner now reloads the rows a retry pushed into the future (`visible_at > now()`, `attempts > 0`) and blocks their keys until each retry is due; the docs that called out the gap are updated. A delayed ordered message still does not block its key. The PostgreSQL and MongoDB engines were checked and never had this gap: they pick the next message per key with a query over all of the key's messages | N-16, RN-4, `dacd9ebb` |
| `MultiTableChangeListener` | A half-open LISTEN connection was never detected, and a lost one was logged only at DEBUG, so the listener could go silent for good and every event-store and queue wake-up fell back to polling. It now probes the connection with `Connection.isValid()` every 10 s, logs a loss at WARN (at most once a minute) and reconnects on the next poll | LN-2, LN-3, N-7, `5bae5aed` |
| `MultiTableChangeListener` | A `listenToNotificationsFor(...)` between a failed poll and the next one reopened the connection with only its own `LISTEN`, so every other table went silent. A new connection now listens to every registered table. `stop()` now runs `UNLISTEN *` and closes the connection; before, a pooled session was left listening | LN-3, `5bae5aed` |
| `MultiTableChangeListener` | One NOTIFY whose payload was not JSON dropped the whole poll batch, event-store and queue wake-ups included, whenever a duplicate filter was registered, which the PostgreSQL queue always does. Such a payload now has no duplicate key and is never dropped | LN-5, N-6, `b88ad079` |
| Spring Boot starters | A property removed in 0.60.0 that is still set was ignored without a word, including `essentials.durable-queues.transactional-mode=fully-transactional`, which silently changed transaction semantics. Each one now logs an ERROR at startup and is marked as an error in the IDE metadata. PostgreSQL starter: `durable-queues.transactional-mode`, `use-ordered-unordered-query`, `enable-queue-statistics`, `shared-queue-statistics-table-name`, `enable-queue-statistics-ttl`, `queue-statistics-ttl-duration`; MongoDB starter: `durable-queues.transactional-mode` | N-18, RN-6, `c61a9399` |
| Spring Boot starters | A missing `provided` library surfaced as a bare `NoClassDefFoundError` or `ClassNotFoundException` deep in bean creation. The startup failure report now names the Maven coordinates to add: jdbi3-core/postgres, the PostgreSQL driver, kotlin-stdlib/kotlin-reflect, Spring Data MongoDB, spring-boot-starter-jdbc, Jackson 3, objenesis and reactor-core | SB-2, `e26d0459` |
| `immutable-jackson3` | With `immutable-jackson3` on the classpath but no `org.objenesis:objenesis`, an application started and wrote, then every read failed with `NoClassDefFoundError`. Creating the persistence mapper now throws an `IllegalStateException` naming the dependency. Objenesis stays `provided` | SB-2, N-5, `e26d0459` |
| Durable queues (MongoDB) | Handing such a message back on an interrupted thread used to fail silently: the MongoDB driver refuses a connection on an interrupted thread (`Interrupted waiting for lock`), the failure was logged at DEBUG, and the message stayed marked as being delivered until the stuck-message reset. The hand-back now clears the interrupt flag for its write and restores it afterwards. A manual redelivery request, a retry or a dead-letter mark failed the same way, as did the acknowledgement of a handler that re-set the flag while its consumer was still running. The consumer now makes all of these writes with the flag cleared and restored. A handler that caught the stop's interrupt, re-set the flag (as Kafka does) and returned normally is not acknowledged at all: it may not have finished its work, so its message is handed back for immediate redelivery (§1.1.1) instead of coming back after `messageHandlingTimeout` | `6ba568b5`, `0a40069f`, `4f573f0d` |
| Durable queues (PostgreSQL) | The writes that end a delivery (acknowledgement, manual redelivery request, retry, dead-letter mark, and handing back a message whose handler the consumer's stop interrupted) ran with whatever interrupt flag the handler left set. Where taking a connection has to wait, that write could be refused: HikariCP, with every pooled connection in use, rejects an interrupted thread with `Interrupted during connection acquisition`, and the message then stayed marked as being delivered until `messageHandlingTimeout`. Both consumers, the centralized fetcher (the default) and the per-consumer path (`setUseCentralizedMessageFetcher(false)`), now make these writes with the flag cleared and restore it afterwards. A handler that caught the stop's interrupt, re-set the flag (as Kafka does) and returned normally is no longer acknowledged, neither by the consumer nor inside its own transaction: it may not have finished its work, and acknowledging deleted a message that was never processed. Its message is handed back for immediate redelivery instead, and the writes it made in the queue's own unit of work are rolled back; a handler that owns its unit-of-work boundary (`EventProcessor`, a boundary-owning `Inbox` consumer) keeps what it committed (§1.1.1) | `6ba568b5`, `0a40069f`, `4f573f0d` |
| Command bus | A `sendAndDontWait` handler that wrote in its UnitOfWork and then threw had those writes committed whenever the `SendAndDontWaitErrorHandler` did not rethrow: the default on a plain `LocalCommandBus` (`FallbackSendAndDontWaitErrorHandler`), or a custom handler on `DurableLocalCommandBus`. The writes are now rolled back before the error handler runs; see [§1.1.12](#1112-a-failed-sendanddontwait-handlers-writes-roll-back-the-error-handler-runs-afterwards) | `f722edb8` |
| `ViewEventProcessor` | When the fenced lock was released while the lock-acquired callback was still starting up (for example, the lock manager stopping at that moment), the release callback threw a `NullPointerException`. It never cancelled the exclusive durable-queue consumer or stopped the subscriptions created right after, so a processor that had lost its lock kept consuming. The two callbacks are now mutually exclusive, a release always cancels the consumer and stops the subscriptions that exist, and an acquire that fails part-way stops the subscriptions it created. Present since 0.60.0 | `72abb871` |
| `MultiTableChangeListener` | A `start()` that ran while `stop()` was tearing down could leave the listener reporting `isStarted()` as `true` with nothing polling, until the next `stop()`/`start()`: `stop()` cleared its started flag before cancelling the poll task, so the racing `start()` found the old task, skipped scheduling, and the task was then cancelled. The teardown of the poll task now happens under the same lock as the flag | `1bfbe58d` |
| `MultiTableChangeListener` | A `start()` that ran while `stop()` or `close()` was finishing could lose its LISTEN connection to that stop: the stop closed whichever connection was current once it had waited for the poll thread, so the restarted listener kept the old connection and saw it closed, and notifications sent before the next poll reopened it were lost. `start()` promises every registered table is LISTENed to when it returns. A stop now ends the run it stops and closes only the connections of that run; a racing `start()` opens and keeps its own, and a poll still in flight from the stopped run no longer touches it. Neither `stop()` nor `start()` waits for the connection while holding the listener's lock, so a `listenToNotificationsFor` still borrowing a pooled connection no longer holds up the listener's other operations, the shutdown signal included | `520facbc`, `c0c60b97`, `b7180574` |
| Command bus | Two `EventStreamDeciderAndAggregateTypeConfigurator`s sharing a `CommandBus` and configured on different threads could corrupt the list behind the duplicate-command-type WARN, or throw `ConcurrentModificationException` from it: one appended to it without holding the lock the other iterated it under. Both now hold it | `a20947b2` |
| Command bus | `addCommandHandler`/`removeCommandHandler` on `LocalCommandBus` and `DurableLocalCommandBus` were not thread-safe: handlers registered from several threads at once could be lost, and a command-type lookup running during a registration could keep the old answer cached. Registration is now thread-safe | `796e4578` |
| Durable queues, event store (PostgreSQL) | Starting an instance could deadlock with instances already running. On every startup the durable queues and the event store (when change notifications are on) dropped and re-created their change-notification trigger, which locked the queue or event table exclusively in the middle of the schema transaction, after `CREATE INDEX IF NOT EXISTS` had already taken a SHARE lock on it. A running instance that had read the table and then wrote to it (a queue claim, an event append) completed a lock cycle, and PostgreSQL aborted either the startup ("deadlock detected" applying `change-notification-trigger`) or the queue or event operation. The trigger is now left alone when it is already correct, which takes no lock on the table, and replaced with `CREATE OR REPLACE TRIGGER` on PostgreSQL 14+ when it is not. Nothing to change in your code; `validate`-mode deployments re-run `emit` (§1). `ListenNotify.changeNotificationTriggerStatements(...)` called with a table name containing upper-case letters still re-created the trigger, and took its table lock, on every start; it now recognises the trigger already in place, also when the table name is long enough that PostgreSQL truncates the trigger's name to 63 characters | `5d870062`, `b7180574` |
| Event store, durable queues, durable subscriptions, fenced locks, aggregates (PostgreSQL) | Starting an instance could still deadlock with instances already running, or hold up their writes for the length of its schema transaction. `CREATE INDEX IF NOT EXISTS` takes a SHARE lock on its table even when the index exists, and `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` takes ACCESS EXCLUSIVE even when the column exists, and both locks are held until the component's schema transaction commits. The event store's event-stream tables of every aggregate type are one transaction, so a startup held SHARE on the first table while it waited for the next, and a running unit of work that appended to the second aggregate type and then to the first completed a lock cycle: PostgreSQL aborted either the startup ("deadlock detected" applying `event-stream-tenant-index`) or the append. The same statements made every start wait for, and then block, writes to the durable-subscriptions table (ACCESS EXCLUSIVE, from the `reposition_epoch` column), the transient-gaps, CDC-inbox, durable-queue, fenced-lock, snapshot-job, aggregate-archive and closing-books-generation tables. Each such statement now runs only when the catalog says the index or column is missing, which takes no lock on the table; a fresh database is created exactly as before. The statements are built with the new `PostgresqlSchemaStatements.createIndexUnlessPresent` / `addColumnUnlessPresent`, which a schema contributor of your own can use for its repeatable changes. Nothing to change in your code; `validate`-mode deployments re-run `emit` (§1). The shard-owned queue's schema: next row | `91ce0f7b1` |
| Durable queues (shard-owned) | Starting an instance held up, and could deadlock with, the shard-owned queue instances already running. `ShardOwnedSchema.initialize` (and the schema harness, which runs the same statements) took a SHARE lock on the unordered, ordered and dead-letter tables through `CREATE INDEX IF NOT EXISTS`, and ACCESS EXCLUSIVE on the dead-letter and membership tables through `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, even with every index and column in place, and held them until its schema transaction committed: every start waited for the enqueues, acknowledgements, dead-letter moves and heartbeats in flight and blocked the next ones, and a running write that took its locks in the other order could be aborted as "deadlock detected", or abort the start. Those statements now run only when the catalog says the index or column is missing, as in the other components; a fresh database is created exactly as before. Nothing to change in your code; `validate`-mode deployments re-run `emit` (§1) | `5ed580f2f` |
| Admin API | `GET /shard-owned-queues` returned each queue name as an object, `[{"value":"orders"}]`, where the contract declares an array of strings, so a client generated from the contract could not read it, and the admin console's shard-owned page built its links from `[object Object]`. It now returns plain strings. The `queueName` of the other shard-owned responses changed the same way, with the contract; see §1 (`7a2cce93d`) | `e8fca111d` |
| Schema harness (PostgreSQL) | When PostgreSQL aborted a component's schema transaction to break a deadlock ("deadlock detected", SQLSTATE `40P01`), the start failed. That can still happen when a new release really has to create an index or column on a table running instances write to. `PostgresqlCreateSchemaApplier` now applies such a change set again, up to three times (`MAX_ATTEMPTS_ON_DEADLOCK`) with a short backoff, logging a WARN per retry. Only when the applier began the transaction itself: applying inside a unit of work or Spring transaction the caller started still fails at once, because the abort undid the caller's work too | `35fecfb90` |
| Durable subscriptions (PostgreSQL) | Two starts of a subscription for the same subscriber that ran at the same time, on two threads or two instances, could fail the start with `IllegalStateException: Cannot createResumePoint ... since a ResumePoint already exists`; in a Spring application that is a failed context start. Both found no resume point and both inserted one: the second insert waited for the first to commit, inserted nothing, and the default `DurableSubscriptionRepository.getOrCreateResumePoint` treated that as an error. `PostgresqlDurableSubscriptionRepository.getOrCreateResumePoint` now reads and returns the resume point the other start committed. `createResumePoint` still throws when the resume point exists | `3187ed454` |
| Event store subscriptions | A subscription added while the `EventStoreSubscriptionManager` was starting could be started twice: the manager's `start()` starts every subscription it holds, and adding a subscription starts it when the manager is already started, and a subscription added on another thread at that moment (an event processor's fenced-lock callback, for example) was seen by both. Every kind of subscription was affected. A non-exclusive asynchronous subscription could fail the start with "Cannot createResumePoint ... since a ResumePoint already exists" in 0.50; an exclusive subscription asked for its fenced lock twice; the observer's `startingSubscriber`/`startedSubscriber` were called twice. The manager now starts each subscription once per start | `107a4a6f9` |
| Event store subscriptions | An in-transaction subscription (`subscribeToAggregateEventsInTransaction`, `exclusivelySubscribeToAggregateEventsInTransaction`) kept handling events after it was stopped or unsubscribed: it removed its handler from the event store's local event bus with a new method reference, which the bus never found. The exclusive one also kept handling events after losing its fenced lock, so it could run while another instance held the lock, and each time it acquired the lock again it registered its handler once more, handling every event once per acquisition. Its `stop()` also never released the lock, so no other instance could take over until the subscription manager stopped. Both now remove the handler they registered, and the exclusive one's `stop()` releases its lock | `85142196d` |
| Event store subscriptions | A non-exclusive asynchronous subscription (`subscribeToAggregateEventsAsynchronously`, `batchSubscribeToAggregateEventsAsynchronously`) whose start failed while looking its resume point up - a database error, or an event processor being stopped while its start was still running - stayed marked as started with nothing polling. Every later start was skipped as already started, so it delivered no further events until the application restarted, and its `stop()` failed with "No resumePoint provided", failing the `EventStoreSubscriptionManager`'s stop with it. A failed start now leaves the subscription not started, so the next start starts it | `1389cfcb2` |
| Durable queues | A queue consumer registered with `consumeFromQueue` while the `DurableQueues` were starting could be started twice: the queues' `start()` starts every consumer they hold, `consumeFromQueue` starts its consumer when the queues are already started, and `DefaultDurableQueueConsumer.start()` only marked itself started after scheduling its pollers, so both starts scheduled them - twice the configured parallel consumers polling the queue. A start racing a `stop()` could also leave the consumer polling after it was stopped. Affects `PostgresqlDurableQueues` without the centralized message fetcher, and `MongoDurableQueues`; the centralized fetcher's consumer was not affected. `DefaultDurableQueueConsumer.start()` and `stop()` are now serialized | `3484ae68e` |
| Event processors | A `ViewEventProcessor` that lost its fenced lock and acquired it again - after a missed lock confirmation, for example - stopped handling events while keeping the lock, so no other instance took over either. On losing the lock it stopped its event store subscriptions but left them in the `EventStoreSubscriptionManager`; on acquiring it again it subscribed once more, and the manager handed back the stopped subscription without starting it. Only its queued failed events were still handled. The processor now unsubscribes its subscriptions when it loses the lock, which keeps their resume points, so acquiring the lock again subscribes and starts them afresh | `d2c720df4` |
| Event processors | An `EventProcessor` or `InTransactionEventProcessor` that was stopped and then started again (`stop()` then `start()` on the same instance) reported itself started but handled no events. Its `stop()` stopped its event store subscriptions but left them in the `EventStoreSubscriptionManager`, so `start()` got the stopped subscriptions back unstarted; an `EventProcessor` also stopped consuming from its `Inbox` for good, because `start()` found the existing `Inbox` without a message consumer. `stop()` now unsubscribes each subscription, which keeps its resume point (and drops the in-memory gap middles it awaited, as a `ViewEventProcessor` losing its lock does), and `start()` consumes from the `Inbox` again. The `EventStoreSubscriptionManager` javadoc now states the rule: a subscription that was only stopped stays registered, and a later subscribe with the same `SubscriberId` and `AggregateType` returns it as it is - call `unsubscribe()` to subscribe again | `<!-- fix:U-001 -->` |
| Fenced locks | A fenced lock could stay locked for `lock-time-out` (15 s by default) after a shutdown that raced its acquisition. When an application shut down while a lock was being acquired asynchronously (for example the scheduler's lock while its jobs were being scheduled), the shutdown cancelled the acquiring tick with an interrupt, and the release attempted during shutdown then failed at once on that same interrupt (WARN "Shutdown cleanup '... release fenced lock ...' failed: InterruptedException"). Other instances, and a restarted instance with the same instance id, could not take the lock until it timed out. Shutdown cleanup now sets a pending interrupt aside for its bounded attempt and restores it afterwards | `c3bfa0bd` |
| Fenced locks | A fenced lock whose acquire failed at commit time was mishandled. If the commit had reached the database anyway (connection lost while waiting for the reply, or on MongoDB the acquiring thread interrupted by a shutdown), the lock stayed held by this instance for as long as it ran and its `lockAcquired` callback was never called. If the commit had not reached the database, `tryAcquireLock` on the same manager still returned the lock as held, and once another instance had acquired it the next lock confirmation could release that other instance's lock. Cause: the lock was recorded as acquired before its commit. It is now recorded only after the commit succeeds; when the commit fails, the lock row is looked up and the lock is held only if the row carries this instance's id and fence token | `47428143` |
| Fenced locks | `FencedLockManager.tryAcquireLock(lockName, timeout)` on the PostgreSQL and MongoDB lock managers threw `NullPointerException: fallbackValue must not be null` on every call. Closing the books (`ClosingBooksManager`) takes its lock that way, so with a real lock manager it never ran: every poll logged "Closing books manager poll failed" and counted as `failed`. And `acquireLock(lockName)` on a lock another instance held never returned, even after that instance released it: each retry replayed the first attempt's result instead of asking the database again. Both now retry on the caller's thread and query the database on every attempt; the timed acquire returns empty when its timeout runs out | `e4585f84` |
| Fenced locks | A blocking `acquireLock(name)` or `tryAcquireLock(name, timeout)` kept querying the database after shutdown had started, and one that was waiting while the lock manager stopped could still take the lock, leaving its row held by a manager that no longer confirmed it until `lock-time-out` passed. Both now give up: `tryAcquireLock` returns empty and `acquireLock` throws `IllegalStateException`. A lock this manager instance already holds is still returned by both, without a database look-up, so a component re-checking its own lock during shutdown does not conclude it lost it. The timeout of `tryAcquireLock(name, timeout)` now also bounds the wait behind the manager's own lock-confirmation work, though not a database round trip already in flight. During shutdown, checking whether a failed acquire commit reached the database is a single bounded attempt | `f1840271`, `0f5e1eec` |
| Event-stream deciders | `EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType` failed the configuration with an `IllegalStateException` for a decider declared through a sub-interface (`implements OrderDecider`, where `OrderDecider extends EventStreamDecider<OrderCommand, OrderEvent>`), through a generic abstract base class, or as a CGLIB-proxied bean; and a sub-interface that changes the type-argument order was matched on the wrong argument. It now finds the command type in all of these. A decider whose command type cannot be resolved (a raw type, an unbound type variable, a JDK dynamic proxy) is reported as not supported instead of throwing (logged at DEBUG, so it is not repeated once per aggregate type); if no other checker claims it, configuration still fails as before, and that exception now says how to declare the decider's COMMAND type argument. Present since the checker was added (0.40.25) | `abb1228d`, `8ffb68b6` |
| Scheduler | A job registered with `DefaultEssentialsScheduler` while the scheduler was acquiring its fenced lock could be silently dropped: it stayed in the job list but was never scheduled until the lock was next re-acquired. This typically hit a `@TTLJob` whose `PostgresqlTTLManager` started at the same moment, so its TTL delete never ran. Registration now waits for the lock callback to finish and always sees the lock state it leaves | `35fafdaa` |
| Durable queues (PostgreSQL) | A busy queue was held to one poll per polling interval. A poll claims at most a queue's free worker slots, so a queue was capped at `parallelConsumers / pollingInterval` messages per second however fast its handler was: 400 msg/s for 8 consumers at the default 20 ms. A queue whose poll filled every free slot is now polled again as soon as half its workers are free: 2 000 messages, 8 consumers and 200 ms polling drained in 3.3 s instead of 50 s (`PostgresqlDurableQueuesSaturationIT`). A poll whose ordered claim finds fewer messages than there are free slots also fills the remaining slots with unordered messages in the same poll, rather than leaving them for the next poll | `abeca59c`, `b60f9faa` |
| Durable queues (PostgreSQL) | Claims slowed down in proportion to the backlog after a queue table had drained. Once a drained table was analyzed, PostgreSQL planned the claim's `UPDATE … FROM cte WHERE q.id = cte.id` as a nested loop over the whole queue: 185 ms instead of 4 ms per claim at 20 000 queued messages. The claim statements now select their rows by primary-key array (`WHERE q.id = ANY(ARRAY(…))`), the opt-in batched fetch's included: one batched poll over two queues of 20 000 messages each took 37.6 s in that state and now takes 111 ms (140 ms instead of 409 ms with fresh statistics). The batched fetch's locking step now also re-checks dead-letter status and `next_delivery_ts`, so a message another consumer released as a retry or dead letter in that window can no longer be claimed early. The ordered claim's per-key ordering check can still be planned badly under the same conditions, so keep the statistics of a regularly draining ordered queue table fresh (`LLM-postgresql-queue.md` § Stale statistics on a draining queue table) | `5603a596`, `4827aff8` |
| Durable queues (PostgreSQL) | The opt-in batched fetch sorted every polled queue's whole ready backlog on every poll. Each queue's candidates are now taken under that queue's own slot limit, and the messages claimed are the same (`BatchedClaimEquivalenceIT` compares old and new on 27 cases). Median of one claim, before → after, stale / fresh statistics: 2 queues × 20 000 messages 90 → 26 ms / 110 → 39 ms; 20 keys × 1 000 ordered 144 → 19 ms / 94 → 18 ms; 20 000 keys × 1 ordered 66 → 22 ms / 60 → 47 ms. A polled queue's ordered messages are still read once per poll, so a large ordered backlog costs more than with per-queue claims (5–15 ms on fresh statistics) | `7739cd44` |
| Durable queues (PostgreSQL) | An ordered queue with a few keys and many messages per key, a common event-processor shape, walked every queued message on every poll: 20 keys × 1 000 messages took 180 ms per claim, even with fresh statistics. Such a queue now switches to a statement that visits each key's head once (0.4 ms), and back when the queue has many keys. Both claim the same messages. The switch needs three slow claims in a row and goes only to the statement last measured as faster, so a database 10 ms or more away does not make a queue alternate between them, running the expensive scan on every other poll. A claim that tries the other statement runs under a `statement_timeout` of the claim that caused the switch, and is cancelled past it, rolled back to a savepoint so the rest of the poll carries on: on stale statistics the head scan took 41 s at 20 000 keys, which would otherwise have stalled the queue's poll, and in the centralized fetcher every other queue's, that long | `b6e17c87`, `7e80c23c` |
| Durable queues (PostgreSQL) | A retry, delayed enqueue or delayed resurrection could wait out the rest of an idle queue's polling backoff after it came due. The queue's optimizer is now told when such a message becomes deliverable and polls then: with a 10 s cap, a 6 s retry arrived 20 ms late instead of 2.5 s, a 6 s delayed message 29 ms instead of 3.1 s (`PostgresqlDurableQueuesDueTimeIT`). The optimizer stops watching a due time only once a claim that ran after it has polled the queue, so a slow poll of many queues cannot miss it. Applies to messages scheduled by the same node, on both the centralized message fetcher (the default) and a consumer polling on its own (`useCentralizedMessageFetcher(false)`, whose `SimpleQueuePollingOptimizer` now tracks due times too; a 6 s retry there arrived about 9 s late with a 10 s cap, now within one polling interval). A delayed message enqueued by another node still waits at most the backoff cap (2 s in Spring) | `7a742881`, `13461645`, `6777b8b9` |
| Durable queues (MongoDB) | A retry ignored the `RedeliveryPolicy`'s delay: `retryMessage` computed the next delivery time but never stored it, so every retry was redelivered at once and a failing handler used up its attempts within milliseconds. Present since at least 0.40.24. Retries now wait their delay; see [§1.1.20](#1120-mongodb-retries-wait-their-redelivery-delay) | `6027fe61` |
| Durable queues (MongoDB) | A retry, delayed enqueue or delayed resurrection made by this node could wait out the polling backoff after it came due (up to 20× the polling interval). The queue's optimizer is now told when such a message becomes deliverable, after the transaction commits: with a 10 s cap, a 2 s retry arrived after 10.2 s and now arrives within its delay plus one polling interval (`MongoDurableQueuesDeliveryDelayIT`) | `dd27e2ba` |
| Durable queues (MongoDB) | `MongoDurableQueuesBuilder.setQueuePollingOptimizerFactory(...)` was ignored, so the default `SimpleQueuePollingOptimizer` always applied; the factory is now used. `LLM-springdata-mongo-queue.md` also stated the default backoff wrongly: it is 50% of the consumer's polling interval per empty poll, capped at 20× the polling interval | `dd27e2ba` |
| Durable queues (PostgreSQL) | A poll that ran while a consumer was stopping could claim a message and hand it to the consumer's already shut-down worker pool, leaving the message marked as being delivered until `messageHandlingTimeout`. A stopping consumer is no longer polled, and a message its pool refuses is handed back for redelivery at once, as in §1.1.1; it is released without being charged a redelivery attempt. An ordered message's key is now also released when its consumer had already unregistered, so a consumer registering again is given that key | `994c4928`, `c2cca365` |
| Durable queues (PostgreSQL, MongoDB) | A message claimed for a consumer that never ran it was mishandled. On the centralized fetcher, a claim for a consumer that had just unregistered was returned with `retryMessage`, which charged a redelivery attempt and replaced the last delivery error with "Manually requested redelivery", so every restart could use up part of a message's redelivery budget and hide its real failure. On the per-consumer path (MongoDB, and PostgreSQL with the centralized fetcher off), a message the poll claimed just as the consumer stopped stayed marked as being delivered until `messageHandlingTimeout`. Both are now released at once with the new `DurableQueues.releaseUndeliveredMessage(...)` (§2), which leaves `redeliveryAttempts` and `lastDeliveryError` as they were. A message whose handler ran when the stop interrupted it is still charged, as in §1.1.1 | `c2cca365`, `6e510f66` |
| Durable queues (PostgreSQL) | A `PostgresqlDurableQueues` on the default centralized message fetcher could not be started again after `stop()`: `start()` threw `RejectedExecutionException` and left the instance reporting itself started without polling. It now restarts. As before, a stop cancels the queues' consumers, and their owners register new ones (`Inbox`, `Outbox` and event processors do); after `close()`, `start()` still throws | `f934c8e2` |
| Durable queues (shard-owned) | An owner that had lost a shard could still record a failure: retry and dead-letter writes lacked the lease check that acknowledgements have, so a superseded owner could push out the visibility of, or dead-letter, a message its successor was delivering. They now carry it, as do a pull session's failure writes | `a4f541e4`, `f9fdd99b` |
| Durable queues (shard-owned) | Each read of the unordered lane fetched up to `readBatchSize` (500) rows, payloads included, though only as many as there were free handlers could be dispatched; the rest were read again next time, about 24 rows per delivered message. Reads are now capped at 64 rows, or twice the free handlers if that is more, about 4 rows per message. Drain time is unchanged; this saves database and network I/O | `e06910c2` |
| Durable queues (shard-owned) | A pull session's (`MessageQueue.openSession`) claim was planned as a nested loop over the shard once the unordered table had been analyzed while drained: a 32-row claim took 155 s instead of 7.5 ms at 20 000 rows per shard. It now selects its rows by primary key (`ShardOwnedSessionClaimPlanIT`) | `9dd04532` |
| Durable queues (shard-owned) | A new owner of an unordered shard started reading from sequence 0, so it treated every message the shard had ever delivered as a hole and kept querying for them for `holeExpiry` (10 s): a 5 000-message history became 5 000 holes and thousands of chase queries. A new owner now starts at the shard's first readable row. A message whose sequence value was allocated before the takeover but committed after it is still delivered exactly once, by the head sweep (`ShardOwnedTakeoverHistoryIT`) | `2790492c` |
| Durable queues (shard-owned) | A shard takeover whose statements failed on a healthy connection stalled every shard served by the same pump thread. The failure (for example a dead-letter insert refused by a constraint or trigger during the takeover's dead-letter pass) was treated as a lost connection: the pump reconnected several times a second and logged "lost its connection; reconnecting" at WARN. The pump runs every takeover of a pass before it delivers for any shard, so the failure ended each pass before any shard was served: no shard on that pump delivered until the failure cleared, whatever the order its shards were added in. The pump now handles this the way it already handled a failing statement during delivery. It checks whether the connection is still valid; if so, it logs the failing shard at ERROR, retries that shard's takeover 200 ms later and keeps serving the other shards at their full rate: only the failing shard's retry is paced, not the pump. The failing shard delivers nothing until its takeover completes, and its attempt charge is still made only once. The pump reconnects only when the connection is actually broken. An unchecked exception thrown during a takeover no longer ends the pump thread either; it is logged and the takeover retried the same way. The retry has no limit and the shard stays leased to the instance, so no other instance takes it and it delivers nothing until the takeover completes; `QueueHealth.unownedShards` does not count such a shard. The new `ShardOwnerMetrics.shardsAwaitingTakeover` does, with `takeoverFailures` counting the failed attempts: alert on it being above zero next to `unownedShards`. The engine-level `ShardOwnedQueue.metrics()` exposes them; `PostgresqlMessageQueue` keeps its metrics private, so through `MessageQueue` (the Spring starter, the `DurableQueues` adapter) the stall reaches the new `QueueObserver.takeoverStalled` callback instead, which the starter's Micrometer binding publishes as `essentials.queue.shards.awaiting.takeover` (§2). The failure is logged at ERROR with its stack trace at most once a minute per subscription (one per queue unless a queue is consumed twice in one process), not per shard, naming one stalled shard and counting the subscription's others, and every failed attempt at DEBUG, instead of a stack trace on every retry (five a second): a cause that fails one takeover, such as a refused dead-letter move, usually fails every takeover on the queue, and one instance taking over all 64 ordered units would otherwise log some 70 ERRORs at once and 70 a minute. A pump thread used to end on anything thrown outside its per-shard guards (an `Error` or an unchecked exception from the pump's own bookkeeping, say), silently, leaving every shard it served leased to the instance and delivering nothing, and resetting `shardsAwaitingTakeover` to zero so the metric read healthy just as the stall got worse. An unchecked exception or a non-critical `Error` (an `AssertionError`, say) from one shard's delivery pass or takeover now stays that shard's: counted in `pumpFailures` or `takeoverFailures` and retried, while the pump keeps serving its other shards. Anything else thrown outside the per-shard guards is now treated like a lost connection: the pump reconnects 200 ms later, runs each shard's takeover again and keeps serving, counting it in the new `pumpLoopFailures` metric (§2) and logging at ERROR at most once a minute per pump. Only a critical error (`VirtualMachineError`, `LinkageError`) still ends the thread; the pump then logs an ERROR naming itself and how many shards it leaves leased and unserved, newly acquired shards go to the runtime's live pumps instead of to it, and the shards it holds are counted in the new `shardsOnDeadPumps` metric (§2), its stalled ones still in `shardsAwaitingTakeover`. Both metrics are readable only through `ShardOwnedQueue.metrics()`; through `MessageQueue` (the Spring starter, the `DurableQueues` adapter) a dead pump is reported by that ERROR line alone, since no `QueueObserver` callback reports it. Restarting the `ShardRuntime` clears both counts and ends the stall on `QueueObserver.takeoverStalled`, also when the stop timed out waiting for another pump | `49917dab`, `c21696d4`, `d7744ae2`, `5b480fca`, `bd3efeff`, `a7943b30` |
| Durable queues (shard-owned) | A shard whose delivery pass kept failing with the connection still healthy (a statement the database refused, or an exception from the engine) logged a full stack trace at ERROR on every pass, about five a second, and counted nothing, although the shard keeps its lease, delivers nothing while it fails, and `QueueHealth.unownedShards` cannot see it. Every failure is now counted in the new `pumpFailures` metric (§2), and the log carries at most one ERROR a minute per subscription (one per queue unless a queue is consumed twice in one process), with the stack trace, naming the shard and how long it has been failing; every other failure is logged at DEBUG without a stack trace, and when the shard's pass (or, for a throwing selection check, its checks) succeeds again an INFO if that run of failures logged the ERROR and a DEBUG otherwise, so a failure that comes and goes on every retry does not flood INFO in place of ERROR. The ERROR keeps the `ShardPump` logger. On the ordered lane the per-queue batched read that precedes those passes logged its own WARN with a stack trace on every pass when the database refused it (a revoked grant, say); it is now counted in the new `batchedReadFailures` metric (§2) and logged the same way, each line naming the queue and the shards it read for: at WARN at most once a minute per subscription (one per queue unless a queue is consumed twice in one process), DEBUG otherwise, and on recovery an INFO if that run logged the WARN, a DEBUG otherwise. Both metrics are readable only through `ShardOwnedQueue.metrics()`; through `MessageQueue` (the Spring starter, the `DurableQueues` adapter) those ERROR and WARN lines are the only signal, since no `QueueObserver` callback reports a failing pass | `8ed621a2` |
| Durable queues (shard-owned) | Two live processes sharing one instance id count as one instance in the rebalance and keep taking shards from each other: safe, because the lease fence refuses the loser's writes, but wasteful and hard to diagnose. The engine now logs a WARN naming `essentials.shard-owned-queue.instance-id` (at most once a minute) and counts `sharedInstanceIdHeartbeats` when it sees that; a restart is not reported. With the new `<hostname>:<pid>` default (§1.1.8) this mostly concerns explicitly set ids and containers that share a hostname and all run as pid 1. **Schema:** `shard_queue_instance` gains a nullable `incarnation text` column, added on start-up with `SchemaOwnership.COMPONENT`; in `validate` mode see §1 | `d54671a6` |
| Durable queues (shard-owned) | One shard whose delivery pass kept failing slowed every other shard served by the same pump thread, across every queue that pump serves. When a statement failed on a healthy connection (an acknowledgement refused by a broken trigger, a constraint or a revoked grant, say), the pump slept 200 ms after the pass, so every healthy shard beside the failing one got at most five passes a second, with up to 200 ms of extra latency per message, for as long as the failure lasted. Now only the failing shard waits: its pass is retried 200 ms later and the pump's other shards keep their full rate. A shard whose pass throws an unchecked exception is paced the same way; it used to be retried on every pass, as often as the pump's busy shards kept it running | `8273af8d` |
| Durable queues (shard-owned) | One shard whose selection check threw (an exception or non-critical `Error` from deciding whether to serve it: its lease check, its delivery permit or its wake-up check) stopped every shard served by the same pump thread, across every queue that pump serves: the throw ended the pump's pass before any shard was served, and the pump reconnected and ran every shard's takeover again five times a second for as long as it lasted. It is now that shard's failure alone, counted in `pumpFailures` and retried 200 ms later while the pump's other shards keep delivering; the log counts the throws in a row until the checks next complete, which logs an INFO if that run logged the ERROR and a DEBUG otherwise, so a shard that then sits idle has its next throw reported as a new failure rather than as the next in a row since the first. Separately, a shard whose acknowledgements failed to flush as its pump stopped kept the shards after it from being flushed (what they had handled but not yet acknowledged could then be delivered again by their next owners), counted a `pumpLoopFailures` and logged at ERROR a reconnect that never happened; it is now a WARN naming the shard, and the other shards are still flushed. Likewise a pump that lost its connection while stopping counted a `connectionFailures` and logged at WARN that it was reconnecting, which it never did; it now logs a WARN saying it lost its connection while stopping, and counts nothing | `c5fb9bc3` |
| Durable queues (shard-owned) | Stopping a queue on the `ShardRuntime` shared per `DataSource` (one built without `setRuntime`) could log `Rebalance failed` at WARN with a `NullPointerException` stack trace: a heartbeat tick already running when `stop()` began read the runtime after the stop had let it go. The stop itself completed; the line made an ordinary shutdown read as a failure. The tick now finishes quietly | `52460a77` |
| Durable queues (shard-owned) | Stopping a queue while its heartbeat was acquiring a shard (unordered) or unit (ordered) could leave that one being served after the stop: `stop()` ended and released only the owners it found when it began, and one the heartbeat acquired a moment later was neither. On a `ShardRuntime` that keeps running after the queue stops (one passed with `setRuntime`, even to that queue alone, or the one shared per `DataSource` while another queue still uses it), whose pump threads go on running, that owner went on reading and dispatching messages to the stopped queue's handler (in Spring, possibly while the application was shutting down) until its liveness check paused it, and its lease named an instance that had just deregistered, so another instance could take the shard and deliver the same messages at once; on the ordered lane a key could then be in a handler on both instances. The heartbeat now checks for a stop after every acquisition and releases what it took instead. That includes a heartbeat still acquiring when the queue is stopped and started again in the same process under the same instance id: it used to hand what it took to the restarted queue, served beside the restarted queue's own owner of the same shard or unit until a lease check a moment later. The release is bounded by the lease the heartbeat itself took, so a shard or unit the restarted queue has taken again meanwhile stays with it. Should that release fail, it is retried on every heartbeat, the restarted queue's included, until it goes through: a restarted queue already holding its fair share (or its `maxShards`) never took the shard back, and no other instance could while the restarted one was live, so it stayed leased and unserved. The heartbeat tells your `QueueObserver` of the shards it acquires once its acquire loop has ended, however it ended, no longer while holding the lock `stop()`, `start()` and `stopAbruptly()` wait for: a slow observer held those up, and one that threw ended the heartbeat's acquisitions part-way | `abe8ee18`, `ed59eec8`, `89f8b08b` |
| Durable queues (shard-owned) | A `ShardOwnedQueue.start()` called on another thread while `stop()` was still running (waiting up to `shedGrace` for handlers to finish, then releasing its leases) could leave the restarted queue serving shards it no longer knew it held: the stop, finishing after the start, dropped the restarted queue's owners from its own lists while they went on being served, and deregistered the instance the start had just registered. `shardsHeld()` then read 0, and the next `stop()` released none of those leases and reported none of those releases. A `start()` now waits for a `stop()` in progress to finish before it starts again. The other way round, a `stop()` or `close()` called while a `start()` is still registering the instance and leasing its shards or units waits for it up to its 2 s shutdown budget and no longer (on an interrupted thread not at all, leaving the interrupt flag set), and `stopAbruptly()` does not wait at all: the queue reports itself stopped at once, and the start, once its statements return, takes on nothing more, releases what it leased, hands back the runtime and, for a `stop()` or `close()`, deregisters the instance, then returns with the queue not started; what a `QueueObserver` throws while the start does that work is logged at WARN rather than thrown from `start()`, since the stop's caller has already returned. A `stop()` that finds the queue left started by a `start()` that failed with a fatal JVM error (which undoes nothing) ends it itself, on an interrupted thread too. A start whose statement is caught by a network partition on a pooled connection therefore no longer holds a shutdown up until the driver's `socketTimeout`, or indefinitely without one. That held for callers of `ShardOwnedQueue` only: `MessageQueue.stop()` on a `PostgresqlMessageQueue` (what the Spring starter and the `DurableQueues` adapter use) still waited for a `consume()` or `start()` in progress without any bound, and so did a shard-owned `DurableQueueConsumer.stop()` for the consumer's `start()`. Both now wait up to 2 s, on an interrupted thread not at all. Past that, `MessageQueue.stop()` stops every consumer without waiting for the start (a lane still leasing waits up to its own 2 s and hands its work to its start, so waiting for a start in progress costs the stop up to about 4 s in all; consumers already running still drain for up to `shedGrace` and release their leases, as in any stop), and the `consume()` or `start()` starts no further lane, stops every consumer again once its statements return, and leaves the queue not started, `isStarted()` reading false throughout; a `consume()` still returns its subscription, not started. A consumer's `stop()` leaves stopping the subscription to its `start()`, which does so once the queue's `consume()` returns; until then a lane that `consume()` has already started may still deliver. What a `QueueObserver` throws while the start does that stopping is logged at WARN. A further `stop()` or `cancel()` of that consumer called while its start does that stopping (each lane may drain for up to `shedGrace` and then hand back its units), for instance `DurableQueues.stop()` followed by an Inbox's `cancel()`, also returns within the 2 s and leaves the stopping to the start. A stop that gave up waiting so returns before that start has released its shards or units and deregistered the instance. A `start()` or `consume()` called meanwhile waits for that start, as long as its statements take, and now logs one WARN naming the queue and the instance once it has waited 2 s, where it used to wait without a word; so does a shard-owned `DurableQueueConsumer` restarted by `DurableQueues.start()` while its earlier `start()` is still subscribing, naming the consumer and the queue. Close the `DataSource` only once that start has returned: closed earlier, its release and deregistration fail, and the shards or units stay unserved until the instance's membership row is older than the lease TTL, as after a crash. A `ShardRuntime` you passed to `setRuntime` and stop before then costs the drain instead: the release and deregistration still succeed, but handlers still running on what that start had taken are not waited for, so their acknowledgements may be lost and those messages redelivered | `3cec5ef4`, `43a47441`, `f86da59e`, `00c015a4`, `567dfb50`, `38d143b8` |
| Durable queues (shard-owned) | A shard (unordered) or unit (ordered) given up to rebalance, whose lease release then failed, stayed leased to the instance and served by nobody: the owner was ended before its lease was released, so the next heartbeat dropped it while the lease still named the instance, and no other instance could take it while this one was live. The lease is now released first, so a failed release leaves the shard or unit with its owner (an unordered one goes on serving it), and the next heartbeat retries the release | `52460a77` |
| Durable queues (shard-owned) | A shard (unordered) or unit (ordered) the heartbeat was releasing - to rebalance, or handing back from a dead pump - while `stop()` released it too was reported released twice: in `shardsReleased` (`ShardOwnedQueue.metrics()`, `MessageQueue.statistics()`), in `QueueObserver.shardOwnershipChanged(shard, false)`, and so in the Micrometer counter `essentials.queue.shard.ownership` tagged `change=released`, which then ran ahead of its `acquired` count. Each release is now reported once, and only for an acquisition that was reported. The other way round, two losses were never reported released at all, so the `acquired` count ran ahead of the `released` count by one more each time, for good: a shard or unit another instance took over (the heartbeat that finds it taken now reports its release, before acquiring it again if it is free), and one `stop()` gave up without releasing it (an ordered unit kept because a key was still in its handler, or a lease whose release failed or ran out of the 2 s shutdown budget; `stop()` now reports those too). A `QueueObserver` that threw for one of the shards or units a `start()` acquired was also not told of the ones after it, though each of their releases was reported later; start-up now tells it of every one before the first failure escapes the `start()`, as the heartbeat already did. Every acquisition is now followed by exactly one release, so `acquired - released` is the number of shards or units held, and zero once the queue has stopped with `stop()` (`stopAbruptly()`, which stands in for a crash, counts no releases) | `ed59eec8` |
| Durable queues (shard-owned) | The attempt a takeover after a crash charges (§1.1.18) was not fenced. An owner whose lease had already moved on before it started delivering (its shard or unit taken by another instance, or released by a `stop()` that began just as it was acquired) still charged the rows its previous holder could have had in flight. If the instance now holding the shard or unit was already delivering them, each of those messages lost one attempt and reached its redelivery limit one failure early. The charge now lands only while the owner still holds the lease under its fence, like the acknowledgement, retry and dead-letter writes; an owner whose charge is refused stops without delivering anything. When it was still serving the shard or unit, the refusal is counted in `leasesLost` (`QueueStatistics`), as a unit the lease check finds taken is, and a loss both find is counted once; before, a lease lost this way showed only in a WARN line. An owner that `stop()` had already ended is not counted | `db871e33`, `f32f35c7` |
| Durable queues (shard-owned) | A heartbeat tick already running when `stop()` began could count a shard (unordered) or unit (ordered) the stop released itself in `leasesLost` (`QueueStatistics`), and WARN that the instance had lost it to another instance: the tick read the lease rows after the stop had released them, and took a missing row for a takeover. A shard or unit a stop gives up is no longer counted or reported as lost: once a stop has begun, the lease check leaves the queue's shards and units to it | `a1ed0711` |
| Durable queues (shard-owned) | A `ShardOwnedQueue.start()` that failed with anything but an `SQLException` left the queue reporting `isStarted()` as `true` with nothing leased and no heartbeat, and every later `start()` did nothing. One such failure is by design: the ordered lane refuses to start, with an `IllegalStateException`, on a database that hides `pg_stat_activity.backend_xid` from the queue's role, so granting the role `pg_read_all_stats` and calling `start()` again had no effect until the process restarted. A start that fails now leaves the queue stopped, short of a fatal JVM error such as `OutOfMemoryError`, with the runtime it borrowed handed back, so a later `start()` runs again. An unchecked failure escapes as it was thrown; an `SQLException` is still wrapped in an `IllegalStateException` | `43a47441` |
| Durable queues (shard-owned) | A `start()` whose lease loop failed part-way left the shards (unordered) or units (ordered) it had already leased named to the instance and served by nobody, and no other instance could take them while the instance's membership row stayed live. The start now releases them before its failure escapes; should that release fail too, the heartbeat of the next `start()` retries it | `43a47441` |
| Durable queues (shard-owned) | A `QueueObserver` that threw from `shardOwnershipChanged(shard, false)` while `stop()` released the shards (unordered) or units (ordered) left the instance registered: the stop told it on its 2 s shutdown-budget thread, before deleting the instance's membership row, and the throw ended that thread there. The surviving instances were then held at a stale fair share until the row aged past the lease TTL, and the failure was neither logged nor thrown to the caller of `stop()`. `stop()` now tells the observer last, once it has released and deregistered, and the observer's failure escapes `stop()` after every shard or unit has been told. Through the `MessageQueue` SPI the same holds for `PostgresqlMessageQueue.stop()` and `Subscription.stop()`: both lanes of every subscription stop, and only then does the observer's first failure escape, any later ones added to it as suppressed; a `consume()` whose start fails still throws the start's failure, with an observer's failure while it stops the lanes added to it as suppressed. On the `DurableQueues` adapter, `ShardOwnedDurableQueues.stop()` stops every consumer before such a failure escapes, a consumer whose stop threw consumes again on its next `start()`, and one cancelled while its stop throws is still removed from the `DurableQueues`. The heartbeat's rebalance does the same when it gives shards or units up: it tells the observer once it has released all of them, where an observer that threw for the first one used to leave the rest held until the next heartbeat. All of this holds for a non-critical `Error` from the observer too (a test double's `AssertionError`, say), which escapes as it was thrown: only a `RuntimeException` used to be collected, so such an `Error` from one consumer's stop left the consumers after it running, the ordered lane among them, with the queue already reporting itself stopped, and from a `consume()`'s start it left the lane it came from running with no subscription to stop it | `699e1b60`, `64c8ee3d` |
| Durable queues (shard-owned) | A restart in which any consumer's start threw (a database failure while it leased, or a `QueueObserver` throwing from `shardOwnershipChanged(shard, true)`, which escapes a start with that consumer running) left the consumers started before it running, the ones after it never started, and the whole reported as started. `PostgresqlMessageQueue.start()` and `Subscription.start()` started their lanes one after the other and stopped at the first throw: when a subscription's ordered lane failed, its unordered lane went on leasing and dispatching with `Subscription.isStarted()` returning `true`, and with several subscriptions every lane started before the failing one went on running while the queue reported itself started, so a second `start()` of the queue did nothing (a second `Subscription.start()` ran both lanes' starts again). On the `DurableQueues` adapter, `ShardOwnedDurableQueues.start()` likewise stopped at the first consumer whose start threw (for either cause above, or a failed subscription), leaving the consumers before it running and the ones after it unstarted while `isStarted()` returned `true`. Each of these now starts everything or nothing: a start that fails with a `RuntimeException` or a non-critical `Error` stops every consumer it was starting and throws the start's failure as it was thrown, with any stop's failure added to it as suppressed, and leaves the queue, subscription or `DurableQueues` not started, so calling `start()` again runs again. A critical error (`VirtualMachineError`, `LinkageError`) escapes untouched and leaves it started, so a `stop()` still stops what is running. Three more gaps on the `DurableQueues` adapter. `ShardOwnedDurableQueues.start()` on a `DurableQueues` already started started every consumer again, and when one failed (one stopped through its own `stop()`, say) it stopped every consumer, the ones running all along included, and reported itself not started; it now does nothing when already started, as `PostgresqlDurableQueues.start()` does. A `consumeFromQueue` on a started `DurableQueues` whose consumer failed to start left that consumer registered, so calling it again for the same queue was refused with "There is already a consumer on queue" until the whole `DurableQueues` was stopped; the failed consumer is now deregistered before the failure escapes. And a consumer `consumeFromQueue` added while a failed start or a `stop()` was stopping the consumers could start and stay consuming with `isStarted()` reading `false`; the `DurableQueues` now reports itself not started before it stops them, so such a consumer stays registered, unstarted, until the next `start()` | `c62601ad`, `d8d0861a` |
| Durable queues (shard-owned) | A `ShardOwnedQueue` started more than four fifths of `leaseTtl` (24 s at the 30 s default) after it was constructed, or restarted that long after a `stop()` (the `MessageQueue.stop()`/`start()` path, which keeps consumers registered for a restart), paused its own delivery for up to a third of the lease (10 s by default): its liveness gate still read the previous run's last heartbeat, or the construction time, until the first heartbeat tick. It logged the "has not confirmed its liveness ... Pausing delivery" WARN and counted a `deliveryPauses` for an instance that had just registered. A start now counts its registration as a liveness confirmation | `00e16f46` |
| Durable queues (shard-owned) | A heartbeat tick whose lease statement failed part-way through a rebalance - releasing a shard (unordered) or unit (ordered) it was giving up, or acquiring one - logged the wrong failure when a `QueueObserver` then threw from `shardOwnershipChanged` for a shard or unit the tick had already moved: the `Rebalance failed` WARN showed only the observer's exception, and the database error that left the rest of the rebalance undone until the next tick was lost from the log. The WARN now logs the database error, with the observer's exception attached to it as suppressed - once, also when the observer threw the same instance for every shard or unit it was told about. An observer that throws while nothing else fails is logged as before | `dd9d79c7`, `d7a5a168` |
| Durable queues (shard-owned) | Four gaps in how `PostgresqlMessageQueue` (the `MessageQueue` the Spring starter and the `DurableQueues` adapter use) tracks whether it is started. A `Subscription.start()` took neither the queue's lifecycle lock nor its started flag: called after `MessageQueue.stop()`, it brought both lanes back while the queue's `isStarted()` stayed `false`, and every later `stop()` or `close()` of the queue, finding it not started, stopped nothing, so the lanes leased, heartbeated and dispatched until the process exited. It now runs one at a time with the queue's `consume()`, `start()` and `stop()`, a `stop()` that gives up waiting for it is handled as for the queue's `start()`, and the queue's `stop()` and `close()` stop every registered subscription whether the queue reports itself started or not, so they stop those lanes again. It starts its own lanes only and leaves the queue not started, so a queue `start()` called after it still starts every other subscription the queue's `stop()` stopped; should that `start()` fail on one of them, it stops only the subscriptions it started itself and leaves this one consuming. A `consume()` after a `stop()` that left other subscriptions stopped likewise starts its own lanes and leaves the queue not started: it used to mark the queue started, so `isStarted()` read `true` with those subscriptions consuming nothing, and a queue `start()` did nothing; only a `consume()` made while no subscription is registered starts the queue. A `MessageQueue.stop()` called by a `QueueObserver` while a `consume()` or `start()` was starting the consumers (from `shardOwnershipChanged(shard, true)`, which a start calls on its own thread) was lost: inside a `consume()`, the queue then reported itself started with both lanes running; inside a `start()`, the lanes after the observer's were started with `isStarted()` reading `false`, so no later `stop()` reached them. Such a stop now marks the queue not started and returns, and the `consume()` or start starts no further lane and stops every consumer before it returns. And a `start()` that a `stop()` gave up waiting for could mark the queue started after that stop had returned, so `isStarted()` read `true` until the start had stopped every consumer again; it no longer marks the queue started while such a stop is owed. Nor does a `start()` called by a `QueueObserver` told of a release (`shardOwnershipChanged(shard, false)`) on the thread of a `stop()`, or of a `consume()` or start doing the stop it owes: it used to mark the queue started and start every consumer while that stop was still stopping them, so the queue ended marked started with the lanes before the observer's running and the ones after it stopped; it now does nothing, and a `consume()` or `Subscription.start()` called there starts no lane either. The same holds while a failed `consume()`, `start()` or `Subscription.start()` stops the lanes it had started: a `start()` called there by an observer told of their release marked the queue started and started every subscription, the lanes being stopped included, for a start that then threw. Last, a `consume()` or a `Subscription.start()` that escaped with a critical error (`VirtualMachineError`, `LinkageError`) left the queue not started, and the `consume()` also left its lanes unregistered, though such an error stops nothing: a lane it had already started, the unordered one when a `QueueObserver` throws the error from `shardOwnershipChanged(shard, true)`, went on leasing and dispatching where no `stop()` or `close()` of the queue reached it. `consume()` now registers its lanes, and `stop()` and `close()` stop that lane | `d12013da`, `bd4abf728`, `44893fe3f`, `f5651f4a3` |
| Command bus (shard-owned) | A `DurableLocalCommandBus` on `ShardOwnedDurableQueues` could not be started again after `stop()`: its next `start()` failed with `IllegalStateException: There is already a consumer on queue`, and a stop and start of the `DurableQueues` made the stopped bus consume its command queue again. The bus only stopped its queue consumer, which on this engine stays registered so that the `DurableQueues` can restart it. `stop()` now cancels the consumer, as `Inboxes`, `Outboxes` and `ViewEventProcessor` do with theirs; on the PostgreSQL and MongoDB durable queues cancelling a consumer is stopping it, so nothing changes there | `156c165ce` |
| Durable queues (shard-owned) | Three races between the lifecycle calls of `ShardOwnedDurableQueues` left a consumer running where nothing would stop it, or stopped where nothing would start it. A `stop()` called while `start()` was held up starting one consumer (its queue's `consume()` caught by a network partition, say) gave up waiting for that consumer after 2 s and returned before the start reached the consumers after it; the start then started them, so they leased and dispatched with `isStarted()` reading `false` and no later `stop()` to come if that stop was the application's shutdown. `start()` now starts no consumer once it has been stopped, and stops the one whose start returned after the stop. A `consumeFromQueue` whose consumer failed to start dropped it from the `DurableQueues`, but a `start()` running meanwhile could already have taken it and start it after the failure: it then consumed unreachable by any `stop()`, beside the second consumer a retried `consumeFromQueue` registered on the same queue, each allowed half the shards. Such a consumer is now stopped, and can no longer be started, before the failure escapes. And when `consumeFromQueue` stopped a consumer whose start returned after a `stop()`, a `start()` that restarted the consumer in between could be undone, leaving the `DurableQueues` reporting itself started with that consumer stopped until the next `stop()` and `start()`; that stop is now decided under the consumer's own lifecycle lock, which a start of the consumer waits for | `a722db4b8` |
| Durable queues (shard-owned) | A shard-owned `DurableQueueConsumer` cancelled while `ShardOwnedDurableQueues.start()` was starting the consumers could be started again by that start once the cancel had stopped it and dropped it from the `DurableQueues`. It then leased units and delivered to the cancelled handler, unreachable by any `stop()`, and as a second instance beside a consumer registered on the same queue afterwards it took a share of the queue's shards and units from that consumer. A `DurableLocalCommandBus` stopping and an `Inbox` handing its fenced lock over both cancel their consumer, so a `DurableQueues.start()` racing either could leave this behind. From the moment `cancel()` begins, no start subscribes the consumer, its own `start()` included; a start already subscribing is stopped as before, and `cancel()` still waits at most 2 s for it | `25a4a4fda` |
| Durable queues (shard-owned) | A shard-owned `DurableQueueConsumer` cancelled or stopped on the thread of its own `start()`, from inside the queue's `consume()` (a `QueueObserver` told of a shard or unit acquisition), stayed started: the stop found no subscription yet and stopped nothing, and the start then kept the subscription `consume()` returned. After a `cancel()`, that left a consumer dropped from the `DurableQueues` leasing and delivering to the cancelled handler, unreachable by any `stop()`. Such a stop or cancel is now owed to the start, which unsubscribes what `consume()` returns, as after a stop that gave up waiting for it. A `start()` called there used to subscribe a second time, leaving one of the two subscriptions running where no stop reached it; it now does nothing, since the start under way starts the consumer. And a stop or cancel from an observer told of a release while the consumer stops no longer unsubscribes the subscription a second time | `bcf7c2088` |
| Durable queues (shard-owned) | A stop of a `PostgresqlMessageQueue` subscription did not keep an ordered unit whose key was still in its handler past `shedGrace` away from other instances, though the engine logged that it did. The subscription's two lanes share one instance id, and the unordered lane, stopping first, deleted the instance's membership row; with no row the kept unit's owner reads as gone, so any other instance took the unit at once and could start that key beside the handler still running it. The membership row now goes with the lane that stops last: a lane stopping while the other lane of its subscription is still running, still draining, or has kept an ordered unit leaves the row to that lane, so the kept unit becomes available when the instance's liveness lapses (`leaseTtl`), as documented. A shard or unit whose release failed as its lane stopped (a database error) does not keep the row: once the other lane has stopped keeping nothing, the instance is deregistered and other instances take the unreleased shards or units at once, as they did before this change | `f8b32d37e`, `e1247ea55` |
| Durable queues (shard-owned) | A `stop()` and `start()` of a `PostgresqlMessageQueue`, a `Subscription.stop()` and `start()`, or a `ShardOwnedQueue.stop()` and `start()`, made while an ordered handler was still running a key the stop had to leave in it (one that outlasted `shedGrace`), delivered that key's message a second time. The stop kept the key's unit leased, so no other instance could take it, but the restart, under the same instance id, took it straight back: alongside the handler still running it, or, once that handler had returned, as if the unit's previous owner had crashed, since the handled message's acknowledgement was never written and the lease still named the instance. Now a restart leaves that unit alone until the handler has returned, and the engine then hands the unit back: it writes the acknowledgement and releases the lease, whether or not the queue or subscription is running again (a hand-back that fails on a database error is retried by the queue's heartbeat or next start, or, for an unsubscribed subscription, by the next `consume()`). The next owner, on this instance or any instance below its fair share, takes the unit as a clean handover: nothing delivered again and, under the attempt counting of §1.1.18, no attempt charged. Until the unit is handed back no message of it is delivered, unless the handler has been running the key longer than `stuckHandlerTimeout` (§1.1.24, default 15 minutes): the restart then stops waiting and takes the unit over under a new fence, delivering the key's message again. Meanwhile the restarted instance counts the kept unit among the units it acquires up to its fair share, though not among those it sheds down to it. A later `stop()` keeps the instance registered while such a handler runs, up to that bound, and while the unit is still being handed back; a unit given up on at the bound no longer keeps it registered | `88da53924`, `ffeae40b9`, `7af80bc49`, `8e8aa232f`, `2407dd92e`, `54b706dc0` |
| Durable queues (shard-owned) | Each restart of a `DurableLocalCommandBus` on `ShardOwnedDurableQueues`, each stop and start of the `DurableQueues`, and each `Inbox` lock hand-over left one more stopped subscription registered with the queue's `PostgresqlMessageQueue`: the shard-owned consumer only stopped its engine subscription, and the next `consume()` logged a WARN calling the restarted consumer a COMPETING consumer and registered it under the next instance id suffix (`<id>-1`, `<id>-2`, …). A `stop()` and `start()` of that `PostgresqlMessageQueue` then started every abandoned subscription again, each a live instance taking its share of the units and running a handler nobody held. The consumer now ends its subscription with the new `Subscription.unsubscribe()` (§2), which takes it out of the queue; the restart is not reported as competing, and it registers under the same instance id: a `consume()` takes the lowest subscription index no live subscription holds, and a failed `consume()` gives its index back too. Not when the stopped subscription kept an ordered unit because a key was still in its handler past `shedGrace`: that unit stays leased under the old instance id, and a subscription taking the id back would take the unit at once and could start the key beside the running handler, so the id stays out of use until that handler has returned and the unit has been handed back (its acknowledgement flushed, its lease released; the next `consume()` retries a hand-back that failed), or until the key has been in its handler longer than `stuckHandlerTimeout` (§1.1.24, default 15 minutes), when the next `consume()` under the id takes the unit over, and a restart before then registers under `<id>-1`, as before but with no COMPETING WARN. The same goes for a `consume()` that fails after its ordered lane has handed a key to the handler (a `QueueObserver` throwing for one of that lane's unit acquisitions): stopping the lane can keep the unit too. And when a stop gave up waiting for its lanes to hand their units back and deregister the instance id — it waits 2 s for that, so on a database that does not answer — the id stays out of use until that hand-back has finished (each of its waits for the database is bounded: see the entry on a stop's hand-back on a connection that never answers): finishing late, it could otherwise delete the membership row of the subscription that took the id back, whose units every other instance would then read as ownerless until its next heartbeat. An unsubscribed subscription's counts leave `MessageQueue.statistics()`. The trading demo's queue load generator, stopped and started from its admin API, did the same and now unsubscribes too. An `unsubscribe()` called while a `start()` of the `PostgresqlMessageQueue` (or of that same subscription) held the queue's lifecycle lock past the 2 s the unsubscribe waits for it, or called from a `QueueObserver` on that start's own thread, returned with the lanes stopped, but the start then started them again and stopped them only once it finished: until then they leased units and delivered to the handler the consumer had let go of. That start no longer starts either lane once `unsubscribe()` has begun | `f8b32d37e`, `8c0f29be0`, `0a5b19cc1`, `7af80bc49` |
| Durable queues (shard-owned) | With several producers enqueueing ordered messages concurrently — even on disjoint keys, each producer numbering and committing its own keys strictly in order — a message could be delivered after a later `key_order` of the same key. The ordered lane's watermark retired a pending observation against the running-transaction probe it took *after* reading, so a writer that committed between the read and the probe looked finished although the read had not seen its rows. The cursor stepped over them and the head sweep delivered them late: the engine logged `key 'X' is being delivered key_order N after M was already delivered` and counted `orderViolations` and `sweepRecoveries`. Found by a ledger load test that lost a cycle's release and settlement in a 200 000-message run. The watermark now retires against the probe taken before the read, and a pass whose read returned as many rows as it asked for advances no further than the last row it read. That holds also when a `ShardRuntime` with a smaller `readBatchSize` than the queue's own `ShardOwnerSettings` reads for the queue's units (a runtime passed to `setRuntime` on the `PostgresqlMessageQueue` or `ShardOwnedQueue` builder, or the runtime queues on one `DataSource` share when given none, which keeps the settings of the queue that first borrowed it): such a read is judged against the runtime's `readBatchSize`, not the queue's. An advance that `watermarkCap` forces while a backlog keeps the reads full takes several passes; it counts once in `watermarkCapped` and logs its DEBUG line once, not once per pass. Measured cost: none beyond noise in drain time and read statements; the watermark trails one pass longer. | `510242a3`, `d489c3528`, `bd583992c` |
| Durable queues (shard-owned) | A `QueueObserver` that called `PostgresqlMessageQueue.consume()` from `shardOwnershipChanged` while the queue was being stopped (told of a release by `stop()`, by a `consume()` or start doing a stop it owed, or by a failed start stopping the lanes it had started), or after it had called `stop()` itself, got back a `Subscription` that was registered but never started: `isStarted()` false, nothing consumed until a later `start()`, and no exception or log line. A queue `start()` or `Subscription.start()` called there did nothing just as silently, and the queue or subscription could end stopped. Each now logs a WARN naming the queue, the instance id (the subscription's, for `consume()` and `Subscription.start()`) and the call it was made inside — a `Subscription.start()` also when both its lanes are running, as that call may stop them next, and a queue `start()` also when a failed queue `start()` stopping its lanes has marked the queue started, as that start unmarks it as it fails. Only a queue `start()` finding the queue marked started while a failed `consume()` or `Subscription.start()` stops its lanes says nothing: that call leaves the queue started. The behaviour is unchanged. `QueueObserver`'s class javadoc now lists what every call into the queue does from that callback | `3d200d62c` |
| Durable queues (shard-owned) | A `ShardOwnedQueue` stopped against a slow database and started again under the same instance id could lose the membership row its restart had just registered. `stop()` waits 2 s for its hand-back to the database (releasing leases, deregistering the instance) and then returns, leaving that work running; finishing after the restart, it deleted the instance's membership row, and other instances then read the restarted instance's units as ownerless until its next heartbeat wrote the row again. The same could happen to a `PostgresqlMessageQueue` subscription's lanes on a `stop()` and `start()` of the queue. A stop's hand-back now leaves the row alone once the queue has started again. A `ShardOwnedQueue` `start()` that finds the hand-back of its own earlier stop already deleting the row waits up to 2 s for the deletion to finish before it registers the instance; if it has not finished by then, it logs a WARN and goes on | `6a3d55815` |
| Durable queues (shard-owned) | A queue built without a `ShardRuntime` of its own (no `setRuntime`) borrows the one shared per `DataSource`, and when its `ShardOwnerSettings` differed in any way from those of the first queue on that `DataSource`, it logged a WARN that its settings were ignored. That overstated it: only the shared runtime's pumps keep the `pumpThreads`, `readBatchSize`, `pollBackstop` and `maxSweepInterval` they were started with. Every other setting of the queue, such as its own `stuckHandlerTimeout` (`settings.withStuckHandlerTimeout(...)`, the documented way to bound one queue's handlers), `shedGrace` and `leaseTtl`, applies as passed, and so do its `readBatchSize` and `maxSweepInterval` for the queue's own per-shard reads. The WARN is now logged only when one of those four values differs, names only those, and says that only the runtime's pumps keep theirs | `66b589206` |
| Durable queues (shard-owned) | A `stop()` of a `ShardOwnedQueue`, of a `PostgresqlMessageQueue` or of one of its subscriptions leaves its database work - flushing acknowledgements, releasing leases, deregistering the instance - running on a thread it stops waiting for after 2 s. On a connection that never answered (a half-open socket after a network partition, with no `socketTimeout` on the `DataSource`), that thread waited for good: it held its pool connection for the life of the process, one more for each stop that hung the same way. Every connection that thread borrows now has its network timeout (`Connection.setNetworkTimeout`) set to the queue's `leaseTtl`, or to 2 s when that is longer, unless the connection's own network timeout is shorter (a `socketTimeout` on the `DataSource`, say, which is then left as it is), and set back before the connection is closed (returned to the pool): a statement that waits longer for an answer throws, what the thread did not get done is left as after a crash - acknowledgements not flushed are delivered again, leases expire and the membership row ages out - and the thread ends. A connection whose driver cannot set a network timeout is used as before. The hand-back of an ordered unit a stop kept because a key was still in its handler - run once that handler returns, on a thread of its own, and retried by the queue's heartbeat, its next `start()` or the next `consume()` (new in this release, see the entry on a stop that kept an ordered unit) - bounds each of its waits for the database the same way, on whichever thread runs it: past the bound it fails, the unit is left as the database had it, and a later retry hands it back. So does a release of such a unit on request (`MessageQueue.releaseKeptOrderedUnit`, the admin API's `POST .../ordered-units/{unit}/release-kept`, new in this release), on the caller's thread: past the bound it fails (`MessageQueue.releaseKeptOrderedUnit` throws the `SQLException`, the admin API answers with an error) and lets go of the unit, so a later request is not answered `BUSY`. A release the driver stopped waiting for may still complete on the server; a later request then answers `ALREADY_TAKEN`, or, once a hand-back has found the row released, nothing for the unit. A `PostgresqlMessageQueue` does not wait for the hand-back of a lane whose stop kept an ordered unit before it frees an unsubscribed subscription's instance id: that hand-back never deletes the membership row, so the hand-back of the lane that kept the unit does not hold the id, the kept unit does, until it is handed back or its key has been in its handler longer than `stuckHandlerTimeout`. Setting `socketTimeout` on the `DataSource` is still the advice: it bounds every connection, this only the stop's, the kept unit's hand-back and its release on request | `18ad04892`, `f1bad22dd`, `0e7ad2990` |
| Durable queues (shard-owned) | A `QueueObserver` that threw from `deadLettered` left that dead letter out of the queue's `deadLettered` count (`MessageQueue.statistics()`, `ShardOwnedQueue.metrics()`, the admin console's Dead-lettered tile), though the message had already been moved to the dead-letter table: the count was taken only after the observer returned. That was so on both lanes for a message whose failed delivery used its last attempt, and for one that an unclean takeover's attempt charge brought to the redelivery limit and parked. The dead letter is now counted before the observer is told | `0073738bd` |

---

## 4. The `essentials` Claude Code plugin

0.60.0 shipped plugin `0.60.0-5`. Two plugin releases have followed, both targeting Essentials 0.60.0; the full
entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

**`0.60.0-7`**

- **`/essentials:slice-map-html` — the Slice Atlas.** One self-contained HTML page with five lenses (Atlas,
  Diff, Lineage, Matrix, Metro), Event Modeling colours and a copyable fix prompt per issue. On a folder holding
  several applications it writes one page per application plus an index page. `/essentials:slice-map --html` is
  retired in its favour.
  The Diff lens compares issues per slice even where a map folds a context's "no flow" or "no scenarios"
  issues into one, so crossing the fold threshold reports only the slices that changed. Each such new issue is
  listed under its slice, in the per-slice wording rather than the context's text once per slice, and selecting
  "+N new issues" walks the same N entries the list shows.
- **`slice.yaml` schema 1.4** adds optional `flow:` (the journeys a slice belongs to) and `scenarios:`
  (given / when / then over the slice's own messages, each linked to its test).
- **`slice.yaml` keys starting with `x-` are extension keys everywhere**, and a manifest written for a newer
  schema no longer fails an older lint gate. An `x-` key used to be accepted only at the root; one inside an
  endpoint, a projection, a schedule, a seam or a scenario failed `slice-lint`. It is now valid on every
  mapping, at any `schemaVersion`. Any other key the project's schema copy does not define still fails on a
  manifest stamped at or below that copy's version (typos stay loud), but on one stamped with a newer `1.x`
  it is printed as a note and the lint exits 0. The projection near-synonyms `subscribesTo`, `handles`,
  `table` and `kind` are the exception and fail at every stamp, so a stamp written ahead of the project's
  schema copy cannot switch that guard off. Unknown-key findings now read `endpoints/0: unknown key
  'rateLimit'`, and `slice-lint --json` gains a `notes` list.
- **`slice.yaml` gains an optional `requirements` field** (schema 1.4): the requirement ids a slice implements,
  written as the project's requirement source names them (`requirements: [REQ-ORDERS-12]`). `slice-lint`
  checks only that it is a non-empty list of distinct, non-empty strings; a requirement-trace tool such as
  sdd's checks the ids themselves. The plugin never writes the field and every rewrite keeps it as written.
- **An integration contract for other plugins' agents** (`essentials-plugin/references/sdd-contract.md`,
  advertised in a static `essentials-plugin/capabilities.json`). The sdd plugin uses it to hand project setup to
  `/essentials:init` and to render slice skeletons. `/essentials:init --preset <file> --report <file>` answers
  questions up front and writes a JSON report. `render-slice.py render` is now a versioned interface with
  `--tests none` (no stub tests), `--manifest <slice.yaml>` (render from a manifest a planner wrote, keeping every
  key the render does not own byte for byte) and a report that lists what the skeleton already implements and
  every stub test. Run by hand, every command behaves as before.
- **Fixed: a scaffolded service-entity command endpoint always failed.** Its API generated a new entity id
  for every request while the handler loads an existing entity, so every POST returned a 500. The request
  body now carries the id of the entity to change. Slices scaffolded before keep the old code: add the id
  field to the request by hand.
- **Fixed: a slice render that failed part-way left files behind.** `render-slice.py` exited 2 ("nothing written")
  even when a write or a `--wire` edit failed after earlier files were written. It now restores every file it
  created or edited before exiting. Rendering into an existing bounded context also reads `AggregateType`,
  `Aggregates`, `Aggregate` and `Entity` from its scaffold when they are not passed, instead of refusing.
- **`/essentials:slice-enrich`** drafts the absent human-owned manifest fields (`flow`, `scenarios`, `summary`,
  `invariants`, `tier`, `consistency`, `featureFlag`, `maps`) from evidence in the code and tests, and writes
  only what you accept.
- **`slice-source.py` sees more dispatches:** a command sent through a getter's bus
  (`getCommandBus().send(...)`), and one forwarded through an `Inbox` created with
  `Inboxes.getOrCreateInbox(config, commandBus)`. Gate 11(b) now also holds an inbound translation's
  `@KafkaListener` / `@RabbitListener` / `@JmsListener` payloads to its `consumes`.
- **Fixed: generated projects lacked objenesis.** `immutable-jackson3` declares it `provided`, and S2.1 did not
  list it, so a project from `/essentials:init` failed its first read with `NoClassDefFoundError`. S2.1 lists it
  now, the generated backend POM declares it, and `stack-lint` reports its absence (`s2.1-objenesis`).
- **Fixed:** `LLM-eventsourced-aggregates.md` and the Java command test template used a rejection method
  `GivenWhenThenScenario` does not have; they now use `thenFailsWithExceptionType(...)`.
- **Docs follow this release's framework changes:** `AppendedEvents`' new module and package, `CommandBus`
  handler listing and its duplicate-handler WARN, the run report (`EssentialsRunReport`), and the durable-queue
  changes (§1.1.13–§1.1.18): acknowledgement inside the handler's transaction, the polling-optimizer defaults,
  stale statistics on a draining queue table, handlers that hold a connection, and the shard-owned attempt counting
  and shared-instance-id WARN.
- **Fixed:** `/essentials:slice-enrich` no longer suggests a hand edit for a value `apply` refused.

**`0.60.0-6`**

- **`/essentials:upgrade` moves a project to the plugin's Essentials release, code included**, with
  `review-scan.py --tree` detecting the 0.50 → 0.60 changes across a whole project.
- **S8 judges standalone Next.js and Create React App frontends correctly**, and CORS origins bound with
  `@Value` are no longer reported as a startup failure.
- **The slice-manifest lint gate:** `slice-lint.py` no longer passes when it validated nothing, a gate refresh
  can be measured against a baseline before it turns CI red, and the pre-commit hook works in a project below
  the repository root.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- **`essentials-trading-demo`: `market_data.views.latest_price` projects instead of reading the aggregate.**
  `LatestPriceProjection` is a `ViewEventProcessor` writing `projection_latest_price`, so the view is eventually
  consistent and can be replayed; an existing demo database backfills the table on first start.
  `InstrumentPrice.latestPrice()` is gone. The bootstrap's idempotency probe, which needs a strongly-consistent
  answer, asks `InstrumentPrices.findPrice(...)` on the write side instead of the view. (`9a6e3e86` and the commit after it)
- **`essentials-trading-demo`: the read side uses Jdbi through the `UnitOfWork`'s handle** instead of Spring
  `JdbcTemplate`: every view projection and query, and the harness's direct price store, take a
  `HandleAwareUnitOfWorkFactory` and use `uow.handle()`, matching the LLM docs, the plugin templates and the other
  examples. A projection's write joins the `UnitOfWork` the event processor already runs the handler in, so a
  `ViewEventProcessor`'s view row commits together with its progress. (`3e0636d2`)
- **`essentials-trading-demo`: projections no longer take a `UnitOfWorkFactory`.** The five view projections write
  through the processor's own `getRequiredUnitOfWork().handle()` in their handlers and
  `getUnitOfWorkFactory().usingUnitOfWork(uow -> ...)` for their DDL and reset, so each constructor takes only its
  processor dependencies. The queries are unchanged.
- **`essentials-performance-lab` declares objenesis.** `immutable-jackson3` has it `provided`; the lab's tests got
  it through Mockito, but the packaged application would have failed its first event read.
- **`postgresql-cqrs`:** the `task` context, on the aggregate lane, drops its `routing/TaskCommand` marker,
  which only the decider lane uses (`f8e78176`). `AccountBalanceViewIT` now really redelivers an event to test
  the projection's idempotency (`e88f8e70`).
- **Slice manifests** in the trading demo and the three Spring examples are reconciled with the code and carry
  journeys, tiers, scenarios and a feature flag (`5fc606b7`, `6a521f9e`, `cbeb5abd`, `8a77ea89`, `46c0dc41`).

---

## 6. Documentation

- **The root `README.md` has a "Claude Code Plugin" section**: what the `essentials` plugin does, how it works,
  how to install it, and its requirements (macOS or Linux, Python 3.11+, `uv`, and the JDK, Maven and Docker for
  init's smoke build).
- **`LLM/LLM-traps.md`** gains `ESS-119`–`ESS-123` for 0.60 upgrade symptoms: `QueuedMessageCounts` equality,
  the removed CDC live-drain stall alert, and three groups of compile errors (Jackson 2 serializers and WAL
  filters, `useOrderedUnorderedQuery`, the removed 0.50 `forRemoval` members) (`e088534c`).
- **The starter READMEs and `LLM-spring-boot-starter-modules.md`** no longer list the removed statistics properties
  or the transactional-mode table. (N-18, `c61a9399`)
- **Fenced-lock ownership is per lock-manager instance**, not per caller or thread: a second `tryAcquireLock` in
  the same JVM returns the same lock, and `release()` by either caller releases it for both. Javadoc and
  `LLM-foundation.md` say so, and the Singleton Worker Pattern gains an in-process guard. (QL-1, `9e211beb`)
- **Event processors** (`LLM-postgresql-event-store.md`, and the view-slice skill for the last two):
  - **Start position:** `isStartSubscriptionFromLatestEvent()` and `resolveStartSubscriptionFromGlobalEventOrder()`
    apply only on a subscription's first start, and the default is global order 1. A view that starts from the
    latest event never backfills its history; the section says how to rebuild one. (ES-6, `df1c7e7b`)
  - **Subscriber ids and several instances:** a processor's name must stay stable across restarts, because the
    subscriber ids, queue and lock names derive from it. Two instances on one database split a processor's
    events, and the code of the instance holding the lock decides the behaviour. `GET /fenced-locks` and
    `GET /event-store/subscriptions` show which instance holds what. (ES-8, `f2cca503`)
  - **Handler failures:** a handler throws on a failure it cannot handle and never swallows it. The event is
    redelivered and then dead-lettered. The resume point keeps advancing, and only that aggregate waits behind its
    dead letter. Alert on `essentials.messaging.durable_queues.dead_lettered` or `DurableQueuesHealthIndicator`,
    fix the cause, then call `resurrectDeadLetterMessage`. (ES-3, `38a50bff`)
  - **Testing asynchronous projections**, built on the new `awaitProcessed`: await the last event you appended,
    await the seam the code under test reads, and have an idempotency probe read the write side. `awaitProcessed`
    sees only this JVM's processor, so poll only where it cannot see. (ES-5, `645fa6a9`)
- **Aggregates:** the pattern table in `LLM-eventsourced-aggregates.md` gains a Snapshots column.
  `EventStreamDecider`, `FlexAggregate` and Kotlin's `DeciderCommandHandlerAdapter` load the full stream on every
  command; only `StatefulAggregateRepository` and the typed-error `Decider` use snapshots (ES-9, `f1327085`).
  `LLM.md` names the three decider families (Java `Decider<C,E,ERR,S>`, Java `EventStreamDecider`/
  `EventStreamEvolver`, Kotlin `Decider<C,E>`/`Evolver`), which are not interchangeable and do not compose
  (DOC-9, `702dc11f`).
- **Command bus** (`LLM-reactive.md`, `LLM-eventsourced-aggregates.md`):
  - What a throwing handler does under `UnitOfWorkControllingCommandBusInterceptor`, for `send`, `sendAsync` and
    `sendAndDontWait`: it rolls back its own writes, or marks a joined outer UnitOfWork rollback-only, and
    `sendAndDontWait` redelivers. A rejection that must persist is a rejection event. (CB-1, `2c555568`)
  - A handler's exception arrives wrapped in `UnitOfWorkException`, one level per enclosing `withUnitOfWork`, so
    `catch (DomainException e)` never matches. Use `Exceptions.doesStackTraceContainExceptionOfType`, or
    `MessageDeliveryErrorHandler.builder().stopRedeliveryOn(...)` for queues. (CB-2, `11392700`)
- **UnitOfWork usage rule** (`LLM-foundation.md`, `components/foundation/README.md`): application code uses
  `usingUnitOfWork`/`withUnitOfWork` or `@Transactional`. `getRequiredUnitOfWork`, `getOrCreateNewUnitOfWork` and a
  UnitOfWork's own `start`/`commit`/`rollback` are framework-internal, and `getCurrentUnitOfWork` is a read-only
  probe. The stale claim that `DurableQueues` calls `getRequiredUnitOfWork()` is corrected. (N-15, `25e4eb7b`)
- **Hand-opened Jdbi handles:** under the PostgreSQL starter, `jdbi.useHandle(...)` joins an active UnitOfWork or
  Spring transaction. With neither active, a write auto-commits on its own and gets no queue, inbox or outbox
  guarantees. (QL-5, `864fedab`)
- **Fenced locks:**
  - The two `isLockedByThisLockManagerInstance` predicates are explained. The manager's reads the stored owner id
    from the database and ignores the fence token; the lock object's reads local state and can be one confirmation
    interval stale. (QL-2, `d2705d2c`)
  - The examples released a lock with the protected `lockManager.releaseLock(lock)`, which does not compile from
    application code. They now use `lock.release()` or try-with-resources. The `@return` Javadoc of
    `FencedLockManager.isLockAcquiredByAnotherLockManagerInstance` said the opposite of what the method returns.
    (N-12, `360c1e99`, `302c1058`)
- **`LLM-reactive.md`** listed a `SendCommandException(Object, Throwable)` constructor that does not exist; it now
  shows the real abstract `SendCommandException(Class<?>, String[, Exception])` hierarchy. (N-12, `302c1058`)
- **Document DB** (`LLM-postgresql-document-db.md`):
  - The Jackson 3 constructor rule: a convenience constructor saves fine and then fails every read. Put
    `@JsonCreator` on the persistence constructor or `@JsonIgnore` on the convenience one;
    `ConstructorDetector.EXPLICIT_ONLY` does not help. (SJ-2, `0df8ebc2`)
  - Deleting a projection row also deletes its version, so a redelivered older event can re-create the row; use a
    soft delete or a tombstone. A `JavaVersionedEntity` may name its `version`/`lastUpdated` backing fields freely,
    `save`/`update` overwrite both, and a read-only one fails repository creation. (DD-4, DD-5, `abc6ab41`)
- **Web layer:**
  - The starters are blocking. The recommended stack is WebMvc with `spring.threads.virtual.enabled=true`; under
    WebFlux, run the whole UnitOfWork in one `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
    (SB-3, `bf1839c4`)
  - Under Jackson 3, a request body that omits a primitive fails with a 400 before `@Valid` runs, and the error
    names no field. Set `spring.jackson.deserialization.fail-on-null-for-primitives=false`, or use a boxed type with
    `@NotNull`. (SJ-4, `e7deaea9`)
  - `WebTestClient.bindToServer()` uses default codecs and serializes value types as beans, not bare values. Use
    `@AutoConfigureWebTestClient` with an injected `WebTestClient`. (SJ-5, `b760e170`)
- **`LLM/LLM-traps.md`** gains `ESS-126` (inside a Spring `@Transactional`, catching a command handler's exception
  commits the handler's writes) and `ESS-127` (a processor handler that catches and logs loses the event).
- **Corrections to the 0.60.0 release notes:**
  - §1.1.6 (`use-ordered-unordered-query`): "is now ignored" is true only for the Spring property
    `essentials.durable-queues.use-ordered-unordered-query`. `PostgresqlDurableQueuesBuilder.setUseOrderedUnorderedQuery`,
    `isUseOrderedUnorderedQuery()` and the constructor parameter were removed, so Java callers get a compile error,
    as `MIGRATION-0.60` says. (RN-3)
  - The schema `emit` step (RN-5):
    - `emit` exits on `ApplicationStartedEvent`, before any `ApplicationRunner`/`CommandLineRunner` runs. An
      `AggregateType` registered from a runner, a ready-event listener, a `@Lazy` bean or on first use is missing
      from the script, and `validate` then fails at runtime.
    - "It runs as one transaction" holds for the main block only. A contributor that registers after the harness
      ran, but before the exit, is appended as a separate `BEGIN…COMMIT` block, also under the bootstrap lock.
      Rerunning the script is still safe.
    - The script goes to `essentials-schema.sql` in the working directory by default; set
      `essentials.schema.emit.script-file` to change it.
- **Durable queues** (`LLM-postgresql-queue.md`, the module README): the polling-optimizer defaults; "Stale
  statistics on a draining queue table", next to the claim settings; and why a handler that holds a connection
  while it calls another system needs a pool with room for it (`00102b51`).
- **Shard-owned queues** (`MessageQueue`'s javadoc, `LLM-postgresql-queue-shard-owned.md`, the module README): a
  `PostgresqlMessageQueue`'s `isStarted()` says whether the queue is marked started, not whether any subscription
  runs. After a queue `stop()`, a `Subscription.start()` or a later `consume()` runs that subscription while
  `isStarted()` reads `false`. A `Lifecycle` manager that stops only a bean reporting itself started, as
  `DefaultLifecycleManager` does at shutdown, then skips the queue, and the subscription goes on leasing units and
  handling messages. If you register a `PostgresqlMessageQueue` as a `Lifecycle` bean yourself, call its `stop()`
  or `close()` whatever `isStarted()` reads, as `ShardOwnedQueueFactory.close()` does, or bring it back to started
  with its own `start()` first. (`176fa33a8`)
- **Shard-owned engine, scaling in** (the module README's scaling table, `LLM-postgresql-queue-shard-owned.md`
  autoscaling): a graceful stop that keeps an ordered unit (a key still in its handler `shedGrace` after the stop
  began) does not deregister. Its membership row ages out `leaseTtl` after its last heartbeat, and survivors keep
  counting it in their fair share until then, so before that a survivor already at its share takes none of the
  units it released. The docs said it always deregistered at once. (`54fe3981a`)
- **Design documents:** `docs/slice-atlas-design.md` and `docs/slice-enrich-design.md` (`764af560`, `73d1993f`);
  `docs/durable-queues-improvement-plan.md`, the measurements and open items behind the durable-queue changes, and
  `docs/Durable Queues Port Report.md`, the Rust port experiment they came from (`0925b7ca`).

---

## 7. Build and tooling

- The devcontainer ships a headless Chromium for the plugin's `render-check`, and the Kotlin language server
  (`396ed9b7`).
- The scheduled scaffold run no longer fails its S2.1 MongoDB row on a stale Compose container (`dfeab2fb`).
- `check-citations` skips paths git ignores (`e61583a8`).
- **`-DskipTests` skips the integration tests again.** maven-failsafe-plugin 3.6.0 stopped honouring the
  `skipTests` property, so `mvn install -DskipTests` (the plugin-check scaffold install, the release deploy) ran
  every integration test. The root POM now defaults `skipITs` to `skipTests`. New `-DskipUTs` skips only the unit
  tests (`mvn verify -DskipUTs` runs just the ITs); `-DskipITs` still skips only the ITs. A project generated by
  `/essentials:init` is not affected: Spring Boot 4.1.1 manages failsafe 3.5.6.
- `scripts/fast-verify.sh` runs every unit test in the reactor first (`mvn -T 1C test`, about 2.5 min) and then
  only the integration tests (`mvn verify -DskipUTs`), so a unit-test failure shows in minutes rather than after
  every upstream Testcontainers suite (a broken unit test in `postgresql-event-store` failed the run after 1:30 min
  instead of 6.6 min; the total stays ~31.5 min). CI's `verify` job now skips the unit tests, and the `unit` job runs
  them on JDK 25 as well as 26 and 27.
- `scripts/plugin-check.sh quick` runs its steps in parallel (`--jobs N` / `PLUGIN_CHECK_JOBS`, default the CPU
  count; `--jobs 1` is the old serial run): about 8 s instead of about 37 s on 10 cores. Output and the PASS/FAIL
  table still print in CI order, and CI's `step <name>` calls are unchanged.
- `scripts/plugin-check.sh quick` no longer hangs when a step is killed by SIGKILL (the OOM killer, `kill -9`); the
  step is reported as FAIL with its exit status (`1b2055a2`).
- `scripts/prepare-release.py` fetches `origin/main` before deciding whether to rename the plugin CHANGELOG's top
  section or add a new one, prints the commit it decided from, and stops before writing anything when the fetch
  fails. New `--self-test` (plugin-check step `prepare-release`, run in CI) pins the release-notes conversion and
  the reset skeleton (`1b2055a2`).
- The integration tests of `postgresql-queue`, `postgresql-event-store` and `eventsourced-aggregates` — the
  reactor's longest chain, since the event store depends on the queue — run in half as many forks as the machine has
  CPUs (`failsafe.forkCount=0.5C`; 2 on a 4-CPU runner, as before). On a 10-CPU machine that takes ~6 min (~22%)
  off the integration-test phase. `-Dfailsafe.forkCount=N` still overrides it. A parallel reactor (`-T`) is faster
  still but makes `postgresql-queue-shard-owned`'s timing-sensitive tests flaky, so it stays opt-in.
- `scripts/plugin-check.sh scaffold all` takes about 70 s instead of about 5 min. It installs only the modules the
  generated projects use (`scripts/plugin-scaffold.sh install`, the command CI runs too) with a parallel reactor and
  no versions report (`--full-install` keeps the whole reactor), and builds the init cells and slice cases from one
  queue of `--jobs` builds, half the CPU count by default. `release` runs the scaffold beside the other checks rather
  than after them, and `--no-evals` runs everything but the evals (the release then fails).
- The plugin's must-not-find eval graders name the trap's line and anchor, not only its file, so the judge no
  longer fails a report for a correct finding drawn from another line of the same file.
- `scripts/prepare-release.py <version>` and the `prepare-release` skill prepare a release up to a local commit: this file becomes `docs/RELEASE-NOTES-<version>.md` and is reset, the plugin version, its CHANGELOG heading and the `essentials.version` pin move, a skeleton of the plugin's migration notes is written and what reads the pin is regenerated. `--check <version>` fails on anything left inconsistent. The skill never tags or pushes.
- **`postgresql-queue-shard-owned` depends on `foundation`** instead of `shared` alone, so its PostgreSQL schema
  helpers and bootstrap lock key come from `foundation` rather than being copied. A project using the engine
  without the adapter or starter now gets `foundation`, `foundation-types`, `types` and `reactive` on its classpath
  (third-party integrations stay optional, as for every other component). `ShardOwnedSchema.ESSENTIALS_BOOTSTRAP_LOCK_KEY`
  is deprecated: use `PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY`, which it now equals by definition (`336372fa4`).
- **`ShardOwnedSchemaContributor` moved into the engine**, as `dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor`,
  now that the engine depends on `foundation`. The class of that name in `postgresql-queue-shard-owned-adapter` still
  works, deprecated for removal, and delegates to it. The Spring starter's bean is now the engine's class: code that
  injects the adapter's type by class gets no bean; inject the engine's (`a1dfba074`).
