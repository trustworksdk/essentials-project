# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.0.md`](RELEASE-NOTES-0.60.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

**So far:** no breaking change, twenty-one framework changes with a behaviour change (§1.1, §1.1.13–§1.1.21 in the
durable queues and units of work), additive features (§2), bug fixes (§3), plugin releases `0.60.0-6` and `0.60.0-7`, and example and tooling updates. Nothing here requires a migration
step, except one for `essentials.schema.mode=validate` deployments: re-run `emit` and apply the script before the
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
enough: `validate` compares the recorded statements, so re-run `emit` and apply the script.

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

What you may notice:

- **Fewer dead letters after a deploy or restart.** Ones you have seen with `InterruptException` /
  `InterruptedException` in their cause after a shutdown were this bug; they can be resurrected.
- **The hand-back counts as one redelivery attempt**, because the handler did run (`retryMessage` always counts
  one). A message caught by several shutdowns uses one attempt of its budget per shutdown. A message that was
  claimed but never reached a handler is released without being charged (§3).
- **The handler may run again for the same message.** That was always possible under at-least-once delivery;
  previously the message was parked instead.

Commit `6ba568b5`. Pinned by `DurableQueuesIT.a_message_whose_handler_is_interrupted_by_the_consumer_stopping_is_redelivered_not_dead_lettered`
(PostgreSQL traditional and centralized, both transactional variants, and MongoDB).

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
with one line, `Root cause: <type>: <message>`, followed by the complete, unchanged stack trace. An exception that is
not wrapped is stored exactly as before. Anything that parses the first line of `getLastDeliveryError()` sees the new
summary line. (`5603a596`)

The shard-owned engine does the same for its dead letters, on both lanes. It stored only the outermost throwable as
`last_error`, so `DeadLetter.lastError()` (and `QueuedMessage.getLastDeliveryError()` through the `DurableQueues`
adapter) named the wrapper and nothing about why the message failed. When the exception is wrapped, the text now
starts with a `Root cause: <type>: <message>` line, followed by the outermost `<type>: <message>` as before; the
engine still stores no stack trace. An unwrapped exception is recorded unchanged, and existing dead letters keep the
text they were stored with. (`9411139c`)

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

What does not change: a handler that requests redelivery, one that marks its unit of work rollback-only (its message
is still consumed) and `UnitOfWorkMode.NONE` handlers are acknowledged separately, as before. `MongoDurableQueues`
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
  charged an attempt, so deploys and rebalances no longer cost messages attempts. Before, every takeover charged
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
  the dead-letter pass after it fails, for example on a dropped connection, only that pass is retried, on the pump's
  next reconnect; the window is not charged a second time. A later plain reconnect never reruns the pass, so it cannot
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

(`a4f541e4`, `f9fdd99b`, N-16 `dacd9ebb`, `5486c812`)

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

---

## 2. New features

- **`essentials.fenced-lock-manager.instance-id`** (PostgreSQL and MongoDB starters) sets the fenced-lock-manager
  id explicitly; `FencedLockManagerSettings.defaultLockManagerInstanceId()` returns the default (§1.1.4).
  (N-8, `206c7e58`)
- **`DocumentDbRepositoryFactory.createForCharSequenceTypeId(Class|KClass)`** creates a repository for an entity
  whose id is a Java `CharSequenceType`. Until now only a raw-typed `create(...)` compiled from Java, and it failed
  at runtime. (DD-3, `47285813`)

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
- **`DefaultDurableQueueConsumer.getQueuePollingOptimizer()`**, and `SimpleQueuePollingOptimizer` implements
  `messageScheduledFor` and `queuePollingReturnedNoMessages(Instant)` (§3). (`6777b8b9`)
- **Shard-owned engine: `spi.RedeliveryRequested(delay)`.** A handler throws it to ask for redelivery after `delay`
  without it counting as an attempt, being reported as a failure or leading to a dead letter (§1.1.18).
  (`a4f541e4`, `f9fdd99b`)
- **Shard-owned engine metrics:** `sharedInstanceIdHeartbeats` (§3) and `cursorRowsRead`, the rows the unordered
  lane's reads returned; divided by `delivered` it is the read amplification. (`d54671a6`, `e06910c2`)

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
| Durable queues (MongoDB) | Handing such a message back on an interrupted thread used to fail silently: the MongoDB driver refuses a connection on an interrupted thread (`Interrupted waiting for lock`), the failure was logged at DEBUG, and the message stayed marked as being delivered until the stuck-message reset. The hand-back now clears the interrupt flag for its write and restores it afterwards | `6ba568b5` |
| Command bus | A `sendAndDontWait` handler that wrote in its UnitOfWork and then threw had those writes committed whenever the `SendAndDontWaitErrorHandler` did not rethrow: the default on a plain `LocalCommandBus` (`FallbackSendAndDontWaitErrorHandler`), or a custom handler on `DurableLocalCommandBus`. The writes are now rolled back before the error handler runs; see [§1.1.12](#1112-a-failed-sendanddontwait-handlers-writes-roll-back-the-error-handler-runs-afterwards) | `f722edb8` |
| `ViewEventProcessor` | When the fenced lock was released while the lock-acquired callback was still starting up (for example, the lock manager stopping at that moment), the release callback threw a `NullPointerException`. It never cancelled the exclusive durable-queue consumer or stopped the subscriptions created right after, so a processor that had lost its lock kept consuming. The two callbacks are now mutually exclusive, a release always cancels the consumer and stops the subscriptions that exist, and an acquire that fails part-way stops the subscriptions it created. Present since 0.60.0 | `72abb871` |
| `MultiTableChangeListener` | A `start()` that ran while `stop()` was tearing down could leave the listener reporting `isStarted()` as `true` with nothing polling, until the next `stop()`/`start()`: `stop()` cleared its started flag before cancelling the poll task, so the racing `start()` found the old task, skipped scheduling, and the task was then cancelled. The teardown of the poll task now happens under the same lock as the flag | `1bfbe58d` |
| `MultiTableChangeListener` | A `start()` that ran while `stop()` or `close()` was finishing could lose its LISTEN connection to that stop: the stop closed whichever connection was current once it had waited for the poll thread, so the restarted listener kept the old connection and saw it closed, and notifications sent before the next poll reopened it were lost. `start()` promises every registered table is LISTENed to when it returns. A stop now ends the run it stops and closes only the connections of that run; a racing `start()` opens and keeps its own, and a poll still in flight from the stopped run no longer touches it. Neither `stop()` nor `start()` waits for the connection while holding the listener's lock, so a `listenToNotificationsFor` still borrowing a pooled connection no longer holds up the listener's other operations, the shutdown signal included | `520facbc`, `c0c60b97`, `b7180574` |
| Command bus | Two `EventStreamDeciderAndAggregateTypeConfigurator`s sharing a `CommandBus` and configured on different threads could corrupt the list behind the duplicate-command-type WARN, or throw `ConcurrentModificationException` from it: one appended to it without holding the lock the other iterated it under. Both now hold it | `a20947b2` |
| Command bus | `addCommandHandler`/`removeCommandHandler` on `LocalCommandBus` and `DurableLocalCommandBus` were not thread-safe: handlers registered from several threads at once could be lost, and a command-type lookup running during a registration could keep the old answer cached. Registration is now thread-safe | `796e4578` |
| Durable queues, event store (PostgreSQL) | Starting an instance could deadlock with instances already running. On every startup the durable queues and the event store (when change notifications are on) dropped and re-created their change-notification trigger, which locked the queue or event table exclusively in the middle of the schema transaction, after `CREATE INDEX IF NOT EXISTS` had already taken a SHARE lock on it. A running instance that had read the table and then wrote to it (a queue claim, an event append) completed a lock cycle, and PostgreSQL aborted either the startup ("deadlock detected" applying `change-notification-trigger`) or the queue or event operation. The trigger is now left alone when it is already correct, which takes no lock on the table, and replaced with `CREATE OR REPLACE TRIGGER` on PostgreSQL 14+ when it is not. Nothing to change in your code; `validate`-mode deployments re-run `emit` (§1). `ListenNotify.changeNotificationTriggerStatements(...)` called with a table name containing upper-case letters still re-created the trigger, and took its table lock, on every start; it now recognises the trigger already in place, also when the table name is long enough that PostgreSQL truncates the trigger's name to 63 characters | `5d870062`, `b7180574` |
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
| Durable queues (shard-owned) | Two live processes sharing one instance id count as one instance in the rebalance and keep taking shards from each other: safe, because the lease fence refuses the loser's writes, but wasteful and hard to diagnose. The engine now logs a WARN naming `essentials.shard-owned-queue.instance-id` (at most once a minute) and counts `sharedInstanceIdHeartbeats` when it sees that; a restart is not reported. With the new `<hostname>:<pid>` default (§1.1.8) this mostly concerns explicitly set ids and containers that share a hostname and all run as pid 1. **Schema:** `shard_queue_instance` gains a nullable `incarnation text` column, added on start-up with `SchemaOwnership.COMPONENT`; in `validate` mode see §1 | `d54671a6` |

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
