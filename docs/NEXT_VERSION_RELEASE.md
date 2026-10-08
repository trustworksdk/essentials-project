# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.0.md`](RELEASE-NOTES-0.60.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

**So far:** no breaking change, nine framework changes with a behaviour change (§1.1), additive features
(§2), bug fixes (§3), plugin releases `0.60.0-6` and `0.60.0-7`, and example and tooling updates. Nothing here requires a migration
step. The persisted
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

None so far. No public API was removed or changed incompatibly, and no configuration key was renamed or removed;
the additions are listed in §2.

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
- **The hand-back counts as one redelivery attempt**, because `retryMessage` always counts one. A message caught
  by several shutdowns uses one attempt of its budget per shutdown.
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

Three new WARN log lines. Nothing else changes, but each points at a setup that does not do what it appears to:

- **A `DurableQueues` enqueue outside a unit of work.** The first `queueMessage`/`queueMessages` per
  `DurableQueues` instance that runs with no Essentials `UnitOfWork` and no Spring transaction logs a WARN with its
  call site. The enqueue commits on its own, so a later rollback of your business change does not undo it. This
  covers `Outbox.sendMessage` and `DurableLocalCommandBus.sendAndDontWait` called outside a transaction. To make it
  fail instead, set `essentials.durable-queues.require-active-unit-of-work-for-enqueue=true` (§2).
  (RN-2, `3c783626`)
- **A shard-owned queue built without a UnitOfWork factory.** Building `ShardOwnedDurableQueues` without
  `setUnitOfWorkFactory(...)` logs a WARN that every enqueue commits on its own. The 0.61 release will refuse to
  build it. The starters always set one. (N-15, `4248f0b3`)
- **An incomplete redelivery policy.** `build()` on `RedeliveryPolicy.exponentialBackoff()`, `linearBackoff()`,
  `fixedBackoff()` and `RedeliveryPolicy.builder()` logs one WARN naming each setter left out or out of range:
  unset delays, a multiplier below 1.0 or unset, or `setMaximumNumberOfRedeliveries` never called. Unset means 0
  redeliveries, so the first failure dead-letters the message. The 0.61 release will throw
  `IllegalArgumentException`. (QL-3, `5537eae3`)

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

Also on the shard-owned ordered lane: the attempt bump at takeover now skips rows that are not yet visible (delayed
messages and messages waiting out a retry backoff), so their `attempts` no longer goes up on a hand-over.
(N-16, `dacd9ebb`)

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
- **`TableChangeNotification.equals`/`hashCode`** are null-safe and compare the concrete class. Before, a payload
  without `table_name` threw an NPE, and two different notification types for the same table were equal. A
  subclass that carries state must override both.

(LN-4, LN-5, LN-6, N-6, N-7, `784da9df`…`5bae5aed`)

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
  part of the checksum, so no recorded schema history changes. (RN-5, `07d23d7d`)
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
| Durable queues (MongoDB) | Handing such a message back on an interrupted thread used to fail silently: the MongoDB driver refuses a connection on an interrupted thread (`Interrupted waiting for lock`), the failure was logged at DEBUG, and the message stayed marked as being delivered until the stuck-message reset. The hand-back now clears the interrupt flag for its write and restores it afterwards | `6ba568b5` |

---

## 4. The `essentials` Claude Code plugin

0.60.0 shipped plugin `0.60.0-5`. Two plugin releases have followed, both targeting Essentials 0.60.0; the full
entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

**`0.60.0-7`**

- **`/essentials:slice-map-html` — the Slice Atlas.** One self-contained HTML page with five lenses (Atlas,
  Diff, Lineage, Matrix, Metro), Event Modeling colours and a copyable fix prompt per issue. On a folder holding
  several applications it writes one page per application plus an index page. `/essentials:slice-map --html` is
  retired in its favour.
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
- **Fenced-lock ownership is per lock-manager instance**, not per caller or thread: a second `tryAcquireLock` in
  the same JVM returns the same lock, and `release()` by either caller releases it for both. Javadoc and
  `LLM-foundation.md` say so, and the Singleton Worker Pattern gains an in-process guard. (QL-1, `9e211beb`)
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
- **Design documents:** `docs/slice-atlas-design.md` and `docs/slice-enrich-design.md` (`764af560`, `73d1993f`).

---

## 7. Build and tooling

- The devcontainer ships a headless Chromium for the plugin's `render-check`, and the Kotlin language server
  (`396ed9b7`).
- The scheduled scaffold run no longer fails its S2.1 MongoDB row on a stale Compose container (`dfeab2fb`).
- `check-citations` skips paths git ignores (`e61583a8`).
