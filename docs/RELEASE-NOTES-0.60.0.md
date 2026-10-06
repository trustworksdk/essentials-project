# Essentials 0.60.0 — Release Notes

_Covers everything on `release/0.60` since `0.50.1`: 144 commits, 767 files, +63k/−14k lines._

0.60.0 is the breaking major that 0.50.0 announced. It raises the platform to **Java 25, Spring Boot 4.1 and
Jackson 3 only**. It removes every member 0.50 marked `@Deprecated(forRemoval = true)` and retires
`TransactionalMode`. It also reworks how durable queues classify failures and report their health.

Three new things ship with it:

- a **shard-owned PostgreSQL queue engine**, which delivers without a claim write
- a **database schema harness**, so Essentials can run against a database whose application user has no DDL rights
- **`UnitOfWorkMode.NONE`**, for `@MessageHandler` methods that make blocking calls to other systems

It is also the first release to ship the **`essentials` Claude Code plugin** from this repository, see
[§2.11](#211-the-essentials-claude-code-plugin).

**The persisted format does not change.** Events, queue payloads and documents written by 0.50 read back
unchanged. Jackson 3 writes byte-identical JSON to 0.50's Jackson 2 mapper, and golden documents written by the
old mapper guard that.

| | 0.50.1 | 0.60.0 |
|---|---|---|
| Java | 21+ | **25+** (`--release 25`; build on JDK 25–27) |
| Spring Boot | 4.0.x | **4.1.1** |
| Jackson | 3 by default, 2 supported | **3 only** |
| Kotlin (consumers) | 2.1+ | **2.3+**, with `jvmTarget` 25 |
| Durable queue transactional modes | `FullyTransactional`, `SingleOperationTransaction` | one behaviour (the former `SingleOperationTransaction`) |
| Schema management | components run their own DDL | schema harness: `create` (default), `validate`, `emit`, `external` |
| Queue engines | `postgresql-queue`, `springdata-mongo-queue` | + `postgresql-queue-shard-owned` (new) |
| Modules | 35 | 36 (33 published + 3 examples) |

The per-change instructions live in [`MIGRATION-0.60.md`](MIGRATION-0.60.md). The per-module tables of
removed constructors and their replacements live in [`MIGRATION-NEXT_MAJOR.md`](MIGRATION-NEXT_MAJOR.md). These
notes summarise both and link to them rather than repeating every table.

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes — read this first](#11-️-silent-behaviour-changes--read-this-first)
   - [1.2 Platform: Java 25, Spring Boot 4.1, Kotlin 2.3](#12-platform-java-25-spring-boot-41-kotlin-23)
   - [1.3 Jackson 3 only](#13-jackson-3-only)
   - [1.4 The 0.50 `forRemoval` members are removed](#14-the-050-forremoval-members-are-removed)
   - [1.5 Durable queues](#15-durable-queues)
   - [1.6 Database objects changed on first startup](#16-database-objects-changed-on-first-startup)
   - [1.7 Subscription statistics records have a new component](#17-subscription-statistics-records-have-a-new-component)
   - [1.8 `ApiQueuedMessage` has three new components](#18-apiqueuedmessage-has-three-new-components)
   - [1.9 Resume-point saves carry a reposition epoch](#19-resume-point-saves-carry-a-reposition-epoch)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [Deprecations](#4-deprecations)
5. [Recommended upgrade order](#5-recommended-upgrade-order)
6. [Module inventory](#6-module-inventory)
7. [Reference](#7-reference)

---

## 1. Breaking — what you MUST apply

### 1.1 ⚠️ Silent behaviour changes — read this first

Most of what 0.60 breaks fails loudly, at compile time or at startup. **The changes below do not.** They
compile, they start, and they change what happens at runtime. Check each one before anything else.

#### 1.1.1 `essentials.durable-queues.transactional-mode=fully-transactional` no longer exists

`TransactionalMode` is removed, and every deployment now behaves as `SingleOperationTransaction` did. Queueing,
dequeueing, acknowledging and retrying each run in their own transaction. If you set neither the property nor
the builder call, nothing changes: `SingleOperationTransaction` has been the default since 0.50.

**If you ran `FullyTransactional`, your delivery semantics change:**

- `UnitOfWork.markAsRollbackOnly()` inside a message handler no longer rolls the message handling back. This
  applies whether you call it directly or through `UnitOfWorkControllingCommandBusInterceptor`. The
  `RedeliveryPolicy` now applies as written.
- Queueing a message no longer requires an enclosing `UnitOfWork`, and no longer joins one for the queue
  write. A handler that relied on "the entity change and the enqueue commit or roll back together" loses that
  guarantee. If you need it, call `queueMessage` inside your own `UnitOfWork`. The operation still joins a
  unit of work that is already in progress.

Remove the property. The starters no longer recognise it.
→ [MIGRATION-0.60 § `TransactionalMode` is retired](MIGRATION-0.60.md#transactionalmode-is-retired)

#### 1.1.2 Dead-letter classification changed in two ways

Both changes apply to an application that configured nothing.

- **`alwaysRetryOn(...)` now works.** Before 0.60, `MessageDeliveryErrorHandler.builder().alwaysRetryOn(
  IllegalArgumentException.class)` had no effect: the consumer's built-in permanent-error list overrode it, and
  the message was dead-lettered on its first delivery. It now overrides the list for `IllegalArgumentException`
  and `ClassCastException`. It does not override it for `DurableQueueDeserializationException`,
  `MismatchedInputException` or `NoClassDefFoundError`, because none of those can succeed on a later attempt.
  If you relied on a message being dead-lettered despite listing its type, it is now retried.
- **The whole cause chain is examined.** Classification used to check only the thrown exception and its
  deepest root cause. Now every link is checked, outermost match first. A handler that wraps a built-in
  permanent type in the middle of a chain used to be retried. It is now dead-lettered.

`MessageDeliveryErrorHandler` gains a three-valued `verdict(...)` (`PERMANENT_ERROR`, `RETRY`, `NO_OPINION`) as a
`default` method, so existing implementations compile and behave as before. The dead-letter log line now names
the rule that fired and how deep in the cause chain it matched:

```
PERMANENT_ERROR (built-in permanent list matched IllegalArgumentException at cause-chain depth 3; attempt 1 of 6)
```

→ [MIGRATION-0.60 § Dead-letter classification changes](MIGRATION-0.60.md#dead-letter-classification-changes)

#### 1.1.3 `QueueMessage.builder().setMessage(orderedMessage)` now keeps the ordering

The builder used to rebuild the message as a plain `Message`, which dropped an `OrderedMessage`'s key and order
with no error. **If you queued ordered messages this way, they were delivered unordered.** They now respect
their ordering, so your handlers may see a different order.

#### 1.1.4 `new AppendToStream(type, id, Optional, List)` fails at runtime, not at compile time

The removed `(AggregateType, ID, Optional<Long>, List<?>)` constructor leaves a trap. A call written for it
still compiles, against `AppendToStream(AggregateType, ID, Object...)`, which would append the `Optional` and the
list as two events. That constructor now throws `IllegalArgumentException` for an event that is an `Optional`,
a `Collection` or an array, and the message names the replacement. **Search for `new AppendToStream` with an
`Optional` argument** and switch to `AppendToStream(AggregateType, ID, Long, List)` or `AppendToStream.builder()`.

#### 1.1.5 Jackson 3 reads your constructors differently

Your own event, command and message types are affected in two ways:

- Jackson 2 annotations from `com.fasterxml.jackson.databind.annotation` (e.g. `@JsonDeserialize(keyUsing=…)`)
  are **silently ignored** by Jackson 3. Annotations from `com.fasterxml.jackson.annotation` (`@JsonProperty`,
  `@JsonCreator`, …) keep working, because both majors share that package.
- **A constructor parameter name is part of the JSON contract.** Jackson 3 uses any constructor as a
  properties-based creator, even when a no-arg constructor exists. A parameter whose name does not match the
  JSON property it receives gets `null`. Classic `Event<ID>` subclasses that take `orderId` and call
  `aggregateId(...)` are the common case. Rename the parameter or annotate it with `@JsonProperty("…")`.

This mostly concerns 0.50 applications that were still on the Jackson 2 flavour. See [§1.3](#13-jackson-3-only).

#### 1.1.6 `useOrderedUnorderedQuery=false` is now ignored

The unified claim query and its flag are gone. If you set the flag to `false`, you now get the split
ordered/unordered queries, which measured 5.4× faster. Setting it to `true`, the default, changes nothing.
Delete the builder call, constructor argument or property.

#### 1.1.7 `exponentialBackoff` and `linearBackoff` redelivery delays now grow

Neither policy grew before. Every redelivery after the first waited the same
`initialRedeliveryDelay + followupRedeliveryDelay × multiplier`. Now:

- **`exponentialBackoff`:** redelivery `n ≥ 1` waits `followupRedeliveryDelay × multiplier^(n-1)`, capped at the
  threshold. `(500ms, 500ms, 2.0, 1min)` now waits 500ms, 500ms, 1s, 2s, 4s, … up to 1min, where it used to wait
  500ms and then 1.5s every time.
- **`linearBackoff`:** now waits `delay × (n+1)`, capped at the threshold.
- **`fixedBackoff`:** unchanged.

**Later retries wait longer, so a message that keeps failing reaches the dead-letter queue later.** The number of
redeliveries is unchanged. This applies to the framework's own defaults:

- `EventProcessor`/`ViewEventProcessor`: ≈ 8.2 s → ≈ 10.4 s before a dead letter.
- `DurableLocalCommandBus`: ≈ 5.9 s → ≈ 17.2 s.

To keep the old timing, use `fixedBackoff` with the old constant value.
The shard-owned engine's adapter uses the same calculation (see [§2.1](#21-shard-owned-postgresql-queue-engine)).
→ [MIGRATION-0.60 § Redelivery delays now grow](MIGRATION-0.60.md#redelivery-delays-now-grow)

#### 1.1.8 A failed `ViewEventProcessor` handler that appended events or changed an aggregate is queued only after a rollback

In 0.50 a `ViewEventProcessor` caught any failure of its direct handler and queued the event in the same
`UnitOfWork`, then committed it. **That commit also kept whatever the failed handler had done in the `UnitOfWork`**:
events it appended through the `EventStore` were persisted and published, and an aggregate it applied an event to had
its uncommitted events persisted - although the handler failed - and the queued retry did the same work again.

The direct handler now runs under a savepoint (see [§3](#3-bug-fixes)), and a failure is still queued in the
subscription's `UnitOfWork` as long as the handler left nothing the savepoint cannot undo. It is **not** queued there
when the handler:

- appended events through the `EventStore`,
- left a `UnitOfWork` lifecycle resource with pending changes - typically an aggregate with uncommitted events, or
  any resource whose `UnitOfWorkLifecycleCallback` does not override the new `hasPendingChanges(...)` (it defaults to
  `true`), or
- marked the `UnitOfWork` rollback-only (a failure inside a joined `usingUnitOfWork`/`withUnitOfWork`; in 0.50 that
  queued message rolled back silently).

Then the whole `UnitOfWork` rolls back, so nothing the failed handler did is persisted or published. The
subscription's `SubscriptionErrorPolicy` ([§2.10](#210-choose-what-an-async-subscription-does-with-a-failing-event))
retries the handler as it would any failure, and when it would give up, the processor queues the event in a
`UnitOfWork` of its own instead, through the new `PersistedEventHandler#handOffFailedEvent` hook. From there the
queue's `RedeliveryPolicy` and dead-letter handling apply as for any other failed event. One WARN line records it. Only
if that queueing fails too does the policy give up on the event. A handler that only loaded an aggregate is queued in
the subscription's `UnitOfWork` as before.

What changes compared with 0.50, for such a handler:

- the failed handler's appended events and aggregate changes are no longer committed (the fix);
- under a retrying policy - `retryThenStop(...)`, the new default ([§1.1.9](#119-a-failing-event-is-retried-and-stops-its-subscription-instead-of-being-skipped)), or `retryThenSkip(...)` - the direct handler is retried in place first, and only then queued;
- under a stopping policy (`stop()`, `retryThenStop(...)`) the subscription does not stop for such an event: it is queued
  and the subscription carries on, exactly as for every other failure a `ViewEventProcessor` queues.

New, additive API that makes the distinction:

- `UnitOfWorkLifecycleCallback#hasPendingChanges(resource)`, default `true`. The stateful, flex and decider repository
  callbacks answer `true` only while the aggregate has uncommitted events.
- `UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()` and `UnitOfWork#getAllUnitOfWorkLifecycleCallbackResources()`.
- `EventStoreUnitOfWork#getNumberOfEventsPersisted()`, a count that never decreases.
- `PersistedEventHandler#handOffFailedEvent(event, failure)`, default `false` - see
  [§2.10](#210-choose-what-an-async-subscription-does-with-a-failing-event).

The `UnitOfWork`/`EventStoreUnitOfWork` defaults throw `UnsupportedOperationException`, which the `ViewEventProcessor`
reads as "state present"; every Essentials implementation overrides them.
→ [MIGRATION-0.60 § A failed `ViewEventProcessor` handler that changed state is queued only after a rollback](MIGRATION-0.60.md#a-failed-vieweventprocessor-handler-that-changed-state-is-queued-only-after-a-rollback)

#### 1.1.7 Events start recording their cause

With the Spring Boot event-store starter, every event appended in reaction to another event now records that event's
id in `caused_by_event_id`, and every message queued while a cause is bound carries one more `MessageMetaData`
entry, `essentials.causedByEventId`. Nothing changes for a `PersistableEventMapper` that sets a cause itself. No
schema change, and existing rows keep their nulls. `essentials.eventstore.causation.enabled=false` restores the old
behaviour. See [§2.9](#29-event-causation).

#### 1.1.8 Spring saves subscription resume points every second, not every 10 seconds

`essentials.eventstore.subscription-manager.snapshot-resume-points-every` now defaults to `1s`, the default
`EventStoreSubscriptionManager.builder()` already used. After a crash, a subscriber now redelivers about one
second of already-handled events instead of up to ten. Only resume points that changed since the last save are
written, in one batched `UPDATE`, so an idle subscriber still causes no database writes. A busy one costs at most
one statement per second. Set the property to `10s` to restore the old behaviour.

#### 1.1.9 A failing event is retried and stops its subscription instead of being skipped

In 0.50 a direct asynchronous subscription (and the forward step of an `EventProcessor`) skipped an event whose handler
failed with a non-I/O error: one ERROR line, the resume point moved past it, the event was never redelivered. 0.60's
`EventStoreSubscriptionManager` applies the new `SubscriptionErrorPolicy.defaultPolicy()` instead: the event is retried 3
times, then the subscription **stops at the event** - nothing after it is handled - and **resumes by itself** after 10 s,
doubling up to 5 min, for as long as the event keeps failing. Nothing is skipped any more; an event that can never succeed
now holds up its subscription (visible on the `essentials.eventstore.subscription.stopped` gauge) until it is fixed.
Spring Boot: `essentials.eventstore.subscription-manager.error-policy.mode` defaults to `retry-n-then-stop`. To keep
0.50's behaviour set `error-policy.mode=skip` (Java: `SubscriptionErrorPolicy.skip()`). See
[§2.10](#210-choose-what-an-async-subscription-does-with-a-failing-event).
→ [MIGRATION-0.60 § A failing event is retried and stops the subscription instead of being skipped](MIGRATION-0.60.md#a-failing-event-is-retried-and-stops-the-subscription-instead-of-being-skipped-subscriptionerrorpolicy)

---

### 1.2 Platform: Java 25, Spring Boot 4.1, Kotlin 2.3

- **Java 25 is the minimum runtime.** Artifacts are compiled with `--release 25` (class-file major version 69).
  A Java 21 runtime rejects them with `UnsupportedClassVersionError`. Essentials itself builds on JDK 25–27.
- **Spring Boot 4.1.x is required.** The starters are built and tested against 4.1.1, and 4.0.x is no longer
  supported. Spring Boot 4.1 removed the APIs it deprecated in 4.0, so read its own release notes too.
- **Kotlin consumers need Kotlin 2.3 or later, and `jvmTarget` 25.** The Kotlin artifacts are compiled with
  Kotlin 2.4.10 at language and API level 2.3, which is the level Spring Boot 4.1 manages. No compiler inlines
  JVM 25 bytecode, such as the Essentials `inline`/`reified` functions, into code compiled for a lower target.

### 1.3 Jackson 3 only

Jackson 3 (`tools.jackson`) is the only supported major, matching Spring Boot 4.

- **Swap the artifacts.** `types-jackson` and `immutable-jackson` are deleted. Depend on `types-jackson3` and
  `immutable-jackson3`. Class names are unchanged, so only the artifact ids move. Remove any leftover 0.50
  Jackson 2 jar: it shares class names with the Jackson 3 modules, and `EssentialsJacksonModules.modules()`
  throws `IllegalStateException` when it finds it.
- **Build knobs are gone.** The profiles `-Pjackson2`/`-Pjackson3` and the properties `essentials.jackson.flavor`,
  `essentials.types-jackson.artifactId` and `essentials.immutable-jackson.artifactId` no longer exist.
- **Workaround no longer needed.** A Jackson 3-only application no longer needs an explicit
  `com.fasterxml.jackson.core:jackson-databind` dependency to load the starters. Drop it if you added one for
  0.50.

| 0.50 | 0.60 |
|---|---|
| `JacksonJSONSerializer` | `Jackson3JSONSerializer`, or `EssentialsObjectMappers.createJSONSerializer()` |
| `JacksonJSONEventSerializer` | `Jackson3JSONEventSerializer`, or `EssentialsJSONEventSerializers.create()` |
| `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()` | `EssentialsJSONEventSerializers.create()` |
| `EssentialsObjectMappers.createJackson2ObjectMapper()` | `EssentialsObjectMappers.createJackson3ObjectMapper(...)` |
| `EssentialsJacksonModules.jackson3Modules()` | `EssentialsJacksonModules.modules()` |
| `EssentialsJacksonModules.jackson2Modules()`, `isJackson3Flavor()` | removed |
| `Jackson3WalMessageFilter`, `WalMessageFilters` | `DefaultWalMessageFilter` |

The following also move to Jackson 3 types:

- `DurableQueuesSerialization.createDefaultObjectMapper()` and `MongoDurableQueues.createDefaultObjectMapper()`
  now return `tools.jackson.databind.ObjectMapper`.
- Custom `NotificationDuplicationFilter` implementations take `tools.jackson.databind.JsonNode`. Replace
  `asText()` with `asString()`.

**Spring Boot starters.** The `jsonSerializer` bean methods take no parameters any more. They deliberately do
**not** pick up `JacksonModule` beans from the application context. Those are usually web-layer modules, and
adding them to the persistence mapper would silently change the persisted format. To add modules to the
persistence mapper, define your own `JSONSerializer`/`JSONEventSerializer` bean, and the starter backs off.

→ [MIGRATION-0.60 § Jackson 3 only](MIGRATION-0.60.md#jackson-3-only)

### 1.4 The 0.50 `forRemoval` members are removed

Every member 0.50.0 marked `@Deprecated(forRemoval = true)` is gone from the public API. That covers `shared`,
`reactive`, `foundation`, the fenced-lock modules, `postgresql-event-store`, `eventsourced-aggregates`, the
queue modules and the Spring Boot starters. Each had a replacement named in its own `@deprecated` tag, and in
almost every case the replacement is a builder. Code that still calls a removed member does not compile, with
the one exception described in [§1.1.4](#114-new-appendtostreamtype-id-optional-list-fails-at-runtime-not-at-compile-time).

Where a removed constructor was the one a builder delegated to, it survives as package-private. It is an
implementation detail now, and the builder is the only public way in.

Two short `PostgresqlDurableQueues(unitOfWorkFactory, …)` forms and
`MongoDurableQueues(mongoTemplate, messageHandlingTimeout)` survive for the all-defaults case, as their
deprecation notes promised.

Three removals are not in the `MIGRATION-NEXT_MAJOR.md` tables:

| Removed | Replacement |
|---|---|
| `DefaultWalMessageFilter(JSONEventSerializer, Supplier)` | `DefaultWalMessageFilter(Supplier)` |
| `PostgresqlEventStreamGapHandler(PostgresqlEventStore, EventStoreUnitOfWorkFactory)` | `PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory)` |
| `PostgresqlEventStreamGapHandler(PostgresqlEventStore, EventStoreUnitOfWorkFactory, Duration, …, …)` | `PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory, Duration, …, …)` |

**If you subclass `AggregateEventStreamConfiguration` from another package,** note that its ten-parameter
constructor is now package-private. Build the base settings with `AggregateEventStreamConfiguration.builder()` and
pass them to the new `protected AggregateEventStreamConfiguration(AggregateEventStreamConfiguration base)`.

→ [MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md) for every before-and-after table.

### 1.5 Durable queues

Beyond the silent changes in §1.1, four removals and one widened record affect queue users.

**The queue statistics feature is removed and replaced.** The trigger-based delivery statistics (a row per
acknowledged message in a separate table) are gone, together with their SPI, DTOs and properties:

- the types `PostgresqlDurableQueuesStatistics`, `DurableQueuesStatistics` and `ApiQueuedStatistics`
- the `…messaging.queue.stats` package
- the `essentials.durable-queues.*statistics*` properties

`GET /durable-queues/queues/{queueName}/statistics` keeps its path and now returns `ApiQueueStatistics`. Its two
halves are not interchangeable: `depth` is cluster-wide and read from the queue table, while `instance` covers
only this JVM and resets on restart. See [§2.4](#24-durable-queue-observability).

If you construct `DefaultDurableQueuesApi` yourself, replace its fourth argument with a
`QueueStatisticsRegistry`.

| Old field | New field |
|---|---|
| `totalMessagesDelivered` | `instance.messagesHandled` (per instance; purges no longer inflate it) |
| `avgDeliveryLatencyMs` | `instance.averageHandlerDurationMillis` — **a different quantity**: handler duration, not time since enqueue |
| `firstDelivery` | `instance.statisticsSince` |
| `lastDelivery` | `instance.lastHandledAt` |

**`useOrderedUnorderedQuery` is removed.** This removes:

- `setUseOrderedUnorderedQuery(…)` and `isUseOrderedUnorderedQuery()`
- the constructor parameter, which changes the arity of the two constructors that took it
- `DurableQueuesSql.buildGetNextMessageReadyForDeliverySqlStatement(Collection)`
- `essentials.durable-queues.use-ordered-unordered-query`

**`TransactionalMode` is removed.** Along with the type itself, this removes:

- `DurableQueues.getTransactionalMode()`
- `setTransactionalMode(…)` on both builders
- the `transactionalMode` constructor parameter
- `essentials.durable-queues.transactional-mode`

See [§1.1.1](#111-essentialsdurable-queuestransactional-modefully-transactional-no-longer-exists).

**The 0.40.x queue constructors are gone.** This covers `RedeliveryPolicy`, `DefaultQueuedMessage`,
`ConsumeFromQueue`, `QueueMessage`/`QueueMessages`, the `*DurableQueueConsumer` classes, `PostgresqlDurableQueues`
and `MongoDurableQueues`. Use each type's `builder()`. The widest removed constructor took 14 positional
parameters, four of them adjacent `boolean`s.

**`QueuedMessageCounts` gains two components:** `numberOfMessagesBeingDelivered` and
`oldestReadyMessageTimestamp`. A caller that only reads the record is unaffected. A caller that constructs one or
compares one by equality must be updated. `numberOfMessagesBeingDelivered` is a nullable `Long`, where `null`
means the engine cannot count in-flight messages cluster-wide. It never means zero.

**Widened:** `QueueMessagesBuilder.setMessages` now accepts `List<? extends Message>`.

→ [MIGRATION-0.60 § Durable queues](MIGRATION-0.60.md#durable-queues)

### 1.6 Database objects changed on first startup

On its first startup, 0.60 changes the database without asking. **Take a backup before upgrading** if the
old statistics data matters to you, because nothing exports it first.

| Change | When | Notes |
|---|---|---|
| `DROP INDEX IF EXISTS idx_<table>_next_msg`, `idx_<table>_ready` | every queue table | Served only the removed unified query |
| `DROP INDEX IF EXISTS idx_<table>_ordered_ready` | every queue table | Measured at zero scans in every workload shape; no longer created |
| Drop `trg_log_message_delivery_stats`, `log_message_delivery_stats()` and the statistics table | only if the trigger exists | Runs once. The table name is read from the function body, and the table is dropped only if its columns match. A table whose trigger you already removed by hand survives; drop it yourself |
| `CREATE TABLE IF NOT EXISTS essentials_schema_history` | always | The schema harness's ledger — see [§2.2](#22-database-schema-harness) |
| `ALTER TABLE durable_subscriptions ADD COLUMN IF NOT EXISTS reposition_epoch BIGINT NOT NULL DEFAULT 0` | always (no-op once present) | Guards resume points against a stale save overwriting a reset — see [§1.9](#19-resume-point-saves-carry-a-reposition-epoch). Metadata-only in PostgreSQL 11+, so no table rewrite. Uses your `durableSubscriptionsTableName` if you configured one |

⚠️ **The index drops are not zero-downtime safe on a large queue table.** They run inside the bootstrap
transaction under the framework's advisory lock, so a concurrently starting instance waits for them. Plan the
upgrade as you would any other index change.

The queue tables keep three indexes: `idx_<table>_ordered_msg`, `idx_<table>_unordered_ready` and
`idx_<table>_ordered_head`.

### 1.7 Subscription statistics records have a new component

`SubscriptionStatistics` and `ApiSubscriptionStatistics` end with a new `gaps` component (see
[§2.7](#27-subscription-gap-statistics)), so their constructors take one more argument. Only code that builds these
snapshots itself is affected, such as test fixtures and mocks of `EventStoreApi`; reading them is unaffected. Pass
`SubscriptionStatistics.Gaps.NONE`, or `ApiSubscriptionGapStatistics.from(SubscriptionStatistics.Gaps.NONE)`, where
there is no gap activity to report. The admin API response only gains an optional `gaps` field.

`ApiSubscription` likewise ends with a new nullable `stoppedByErrorPolicy` component (see
[§2.10](#210-choose-what-an-async-subscription-does-with-a-failing-event)). Code that builds it, or deconstructs it with
a record pattern, passes one more argument; `null` means "not known here". The admin API response only gains an
optional `stoppedByErrorPolicy` field.

### 1.8 `ApiQueuedMessage` has three new components

`ApiQueuedMessage` ends with `orderedMessageKey`, `orderedMessageOrder` and `referencedAggregateType`, so its
constructor takes three more arguments. Only code that builds it itself is affected, such as test fixtures and mocks
of `DurableQueuesApi`; pass `null, null, null` for an unordered message. The admin API response only gains three
optional fields. `referencedAggregateType` is set for messages that refer to a persisted event - an `EventProcessor`'s
inbox messages - and is what lets the console link a stuck or dead-lettered event message to its causation (see
[§2.9](#29-event-causation)). It is routing information, not payload, so it is present without the payload role.

### 1.9 Resume-point saves carry a reposition epoch

A save that read a subscription's resume point before a reset could commit after the reset and overwrite it. If the
node then died before the next save corrected the row, the reset was lost and the events it should have replayed
were skipped. `durable_subscriptions` now has a `reposition_epoch` column ([§1.6](#16-database-objects-changed-on-first-startup)).
Every deliberate reposition (`SubscriptionResumePoint.setResumeFromAndIncluding`, which every subscription reset goes
through) increments the epoch. Normal progress (`advanceResumeFromAndIncluding`) does not. Every save writes value and
epoch together and is refused when the stored epoch is newer, so the reset wins whichever write commits first.

What you may need to change:

- **`essentials.schema.mode=validate` or `emit`**: the framework does not alter the table itself. Apply the
  `ALTER TABLE` from §1.6, or take it from the emitted script, before starting 0.60.
- **A custom `DurableSubscriptionRepository`**: store and return the epoch
  (`new SubscriptionResumePoint(…, repositionEpoch, lastUpdated)`). In `saveResumePoints`, bind
  `SubscriptionResumePoint.snapshot()`, refuse a write whose epoch is older than the stored one, and record the outcome
  with `markAsPersisted(Snapshot, OffsetDateTime)` or `markAsSuperseded(Snapshot)`. An implementation that ignores the
  epoch keeps working as before: it is just not protected against the race.
- **`markAsPersisted(GlobalEventOrder, OffsetDateTime)`** is deprecated. It cannot tell which epoch the written
  value belonged to.

A refused save is logged once per resume point at WARN when the row was repositioned by a writer other than this
subscription. That is a stale instance, or non-exclusive subscriptions on several nodes sharing one row, which already
overwrote each other's resume points before 0.60. The row keeps the reset, and restarting the subscription picks it up.

---

## 2. New features

### 2.1 Shard-owned PostgreSQL queue engine

New modules: `postgresql-queue-shard-owned`, `postgresql-queue-shard-owned-adapter`, and
`spring-boot-starter-postgresql-queue-shard-owned`.

In this engine every message is assigned a shard when it is enqueued, and each shard has exactly one owning
consumer at a time, held by a lease. A consumer reads only the shards it owns. There is therefore **no claim
write**: the existing engine sets `is_being_delivered = true` on every message, and this one writes nothing,
because the lease already says who owns the message. Per-key ordering follows from single ownership and holds
across processes.

Measured against `postgresql-queue` (200-byte payloads; full conditions and caveats in
[`durable-queue-measurements.md`](durable-queue-measurements.md)):

| Metric | `postgresql-queue` | shard-owned |
|---|---|---|
| WAL bytes per message | 1 904 | **538** (−72%) |
| Row updates per message | 1.00 | **0** |
| Commits per message | 1.07 | **0.15** (−86%) |
| Enqueue-to-handler p50, burst | 20.7 ms | **0.54 ms** |
| p50 / p99, 10 minutes sustained at 600 msg/s | ~13 ms / ~28 ms | **~0.87 ms / ~1.4 ms** |

The cost rows reproduce across hosts to within 0.1%. Absolute throughput figures do not travel between
machines, which is why none is quoted here.

**The engine is opt-in.** Unlike every other Essentials starter, `spring-boot-starter-postgresql-queue-shard-owned`
configures nothing just by being on the classpath. Until you set `essentials.shard-owned-queue.enabled=true`
(default `false`), there is no schema initialisation, no runtime, no pumps, no held connections and no admin
endpoints. This is deliberate for the engine's first release.

**Two ways to use it:**

- **Directly.** `PostgresqlMessageQueue` implements a new `MessageQueue` SPI. It has two lanes, unordered
  (round-robin across shards) and ordered (FIFO per key), and one `consume()` serves both. You register queues
  by name in code, together with a shard count: `ShardOwnedSchema.registerQueue(...)`, or
  `queues.register(...)` on the Spring `ShardOwnedQueueFactory`.
- **As `DurableQueues`.** Set `essentials.shard-owned-queue.durable-queues-enabled=true` (default `false`),
  together with `enabled=true`, and the adapter replaces `PostgresqlDurableQueues`. Inbox, Outbox, `DurableLocalCommandBus` and the event
  processors then run on the engine without code changes. The queue names the framework derives are registered
  on first use (`essentials.shard-owned-queue.auto-register-shard-count`). `DurableQueuesInterceptor` beans keep
  running.

**Other capabilities:**

- Transactional enqueue on your own `Connection`.
- Delayed delivery on the server clock.
- Dead letters with retry, resurrect and mark-as-dead-letter by id, plus `resurrectKey(key)` to replay a
  stalled ordered key in one transaction.
- Pull sessions (unordered lane only).
- Per-consumer redelivery policies. Through the `DurableQueues` adapter a `RedeliveryPolicy` waits exactly as it does
  on `postgresql-queue`: the engine's `ConsumerOptions.retryBackoff` is a function, and the adapter passes the
  policy's own `calculateNextRedeliveryDelay`.
- A Micrometer observer.
- The shard count can be grown at runtime (`ShardOwnedSchema.growShardCount(...)`), and running instances
  pick it up.

**Operations.** An administrative contract, `ShardOwnedQueuesApi`, is part of the OpenAPI spec under
`/shard-owned-queues/...`, and the admin console gains a **Shard-owned queues** page. That page leads with
ownership rather than depth. A shard that no instance owns is the failure this engine actually has:
messages routed there are never delivered, while depth only looks busy. So `unownedShards` is the tile that
turns red, and the two lanes are reported separately.

**Before you adopt it, note the following.**

- **Status: published and new, with no production use yet.** `MessageQueue` is frozen from this release on:
  additive changes in a minor, breaking changes only in a major. Like the rest of Essentials' queues it is
  **intra-service only**.
- **PostgreSQL 13+** for the ordered lane (9.5+ for the unordered lane alone).
- **`pg_stat_activity.backend_xid` must be readable** by the application user. The ordered lane's cursor
  depends on it being complete, and a partial answer would lose messages silently — so the ordered lane
  probes for it at start-up and refuses to start where it is not visible, naming `pg_read_all_stats` as
  the fix. An ordinary role reads it on stock PostgreSQL; the probe exists for managed platforms that
  might redact it.
- **`pumpThreads + 1` pool connections are held permanently** (3 by default), from the same pool as
  lease renewal, enqueue and — under the Spring starter — the rest of the application. Size the pool
  well above that. Under the Spring Boot starter the runtime checks at start-up, for any pool Spring
  Boot can read (HikariCP, Commons DBCP2, Tomcat JDBC, Oracle UCP): a pool no larger than the held
  connections fails the context, and one more than half consumed by them is logged as a warning. A
  runtime constructed by hand is checked when given a `ConnectionPoolMetadata`; otherwise the
  requirement is only logged. A pump that later cannot get a connection from a full pool logs
  `connection pool exhausted` rather than a lost connection.
- **A delayed ordered message is overtaken** by a later, undelayed message for the same key. `PostgresqlDurableQueues`
  blocks the key instead, so `queueMessage(queue, orderedMessage, deliveryDelay)` behaves differently on the two
  engines. For any one key, send either only delayed messages or only undelayed ones.
- **A key never advances past a dead letter.** Once one of a key's messages is dead-lettered, later messages
  for that key are dead-lettered too (marked `DeadLetter.neverDelivered()`) rather than delivered. Recover with
  `resurrectKey`.
- Outside schema mode `create`, the engine still creates each queue's per-shard sequences at runtime, so it
  needs `CREATE` on the schema (see [§2.2](#22-database-schema-harness)).

→ [`components/postgresql-queue-shard-owned/README.md`](../components/postgresql-queue-shard-owned/README.md),
[`LLM/LLM-postgresql-queue-shard-owned.md`](../LLM/LLM-postgresql-queue-shard-owned.md),
[`durable-queue-shard-owned.md`](durable-queue-shard-owned.md)

### 2.2 Database schema harness

Every Essentials component that owns tables now *describes* its schema as `SchemaChange`s, through an
`EssentialsSchemaContributor`, instead of running the DDL itself. A harness decides what to do with that
description.

**If you change nothing, nothing changes.** The default mode is `create`, and components create their own
tables, indexes, functions and triggers exactly as in 0.50. On a database created by 0.50 there is no adoption
step. Every statement is safe against objects that already exist, so the first start runs them, finds everything
in place, and records it in the new `essentials_schema_history` ledger.

```properties
essentials.schema.mode = create      # default: components create their own schema
essentials.schema.mode = validate    # execute nothing; refuse to start unless the ledger records every change
essentials.schema.mode = emit        # write the schema as one SQL script, then exit with code 0
essentials.schema.mode = external    # execute nothing, verify nothing
```

**For a database whose application user has no DDL rights:**

1. Run the application once with `emit`, for example as a pipeline step. It writes the script and exits.
2. Have a user with DDL rights run the script. It runs as one transaction under the bootstrap lock, records
   itself in the ledger, and is safe to rerun.
3. Deploy with `validate`. Startup fails with a `SchemaValidationException` that lists every change missing from
   the ledger. After each upgrade, rerun `emit` to get the new statements.

**Things to know:**

- `validate` checks the ledger, not the catalog, so an object dropped by hand after it was recorded goes
  unnoticed.
- Every `AggregateType` must be registered at startup, before running `emit`. Registering one at runtime whose
  table is not in the ledger throws in `validate`.
- **If you declare your own `ClosingBooksSetup` bean,** pass
  `setSchemaOwnership(properties.getSchema().getMode().schemaOwnership())`. Otherwise its repository still runs
  DDL while it is built.
- **If you build components yourself,** every schema-owning builder or constructor gained a
  `SchemaOwnership` option. The default, `COMPONENT`, is the 0.50 behaviour.

→ [MIGRATION-0.60 § Database schema harness](MIGRATION-0.60.md#database-schema-harness),
[`database-schema-harness.md`](database-schema-harness.md)

### 2.3 `UnitOfWorkMode.NONE` for handlers that make blocking calls

By default, a `@MessageHandler` method runs inside a `UnitOfWork`, which holds a pooled connection with an open
transaction. A handler that calls an external system therefore keeps that connection `idle in transaction` for
the whole call, one connection per parallel consumer, while writing nothing.

```java
@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
void handle(AssessRiskCommand cmd) {
    var assessment = riskServiceHttpClient.assess(cmd.instrumentId());   // no connection held
    unitOfWorkFactory.usingUnitOfWork(() -> repository.save(assessment)); // transactional tail
}
```

**Where it takes effect.** Only dispatchers that own the `UnitOfWork` boundary honour the mode, which in
practice means an `EventProcessor`. Everywhere else the mode is rejected rather than ignored.

**Two responsibilities move to the handler:**

- **It must be idempotent.** A failure after the call returns but before the tail commits redelivers the
  message, and the call repeats.
- **Its blocking call must time out well inside `messageHandlingTimeout`** (30s by default). Past that timeout
  the message can be delivered again while the first attempt is still running.

→ [`LLM/LLM-foundation.md` § Blocking I/O in a Message Handler](../LLM/LLM-foundation.md#blocking-io-in-a-message-handler-unitofworkmode)

### 2.4 Durable queue observability

Before 0.60, a dead letter produced a `log.error` and a table row, and nothing else. 0.60 adds four signals,
none of which needs any configuration:

- **Per-queue statistics.** `QueueStatisticsRegistry` and `StatisticsCollectingDurableQueueMessageObserver`
  are registered by the starters. They back the new `ApiQueueStatistics` described in
  [§1.5](#15-durable-queues). There is no table, no trigger and no TTL job. You can add your own
  `DurableQueueMessageObserver` beans, and the starters compose them all. The Mongo builder gained
  `setMessageObserver(...)` for this.
- **A dead-letter counter to alert on.** `essentials.messaging.durable_queues.dead_lettered` is tagged
  `queue_name`, `message_payload_type` and `reason` (`permanent_error` / `redeliveries_exhausted`). It is
  registered whenever a `MeterRegistry` is present, deliberately not behind `essentials.metrics.durable-queues.enabled`,
  in both the PostgreSQL and MongoDB starters.
- **A dead-letter health indicator.** `DurableQueuesHealthIndicator` reports counts under `durableQueues` on
  `/actuator/health`. **It reports `UP` however many dead letters there are, until you set
  `essentials.durable-queues.health.dead-letter-threshold`.** The threshold is per queue. Without it, a
  readiness or liveness probe would take pods out of service over a single bad message. Other settings:
  `…health.cache-time-to-live` (default `10s`) and `management.health.durable-queues.enabled`.
- **Actionable depth.** `QueuedMessageCounts` now reports messages being delivered and the oldest ready
  message. A large age with nothing in flight means the queue is stalled.

### 2.5 Exact `numeric` converters for `Amount` and `Percentage` (opt-in)

`types-springdata-jpa` adds `AmountNumericAttributeConverter`, `PercentageNumericAttributeConverter` and their
base class `BaseBigDecimalTypeNumericAttributeConverter`. They map to an exact `numeric` column instead of
`double precision`, which loses the written scale and does SQL arithmetic in floating point.

**Neither converter is `autoApply`,** so upgrading changes nothing. To opt in, set `@Convert` and `@Column`
per field:

```java
@Convert(converter = AmountNumericAttributeConverter.class)
@Column(precision = 19, scale = 2)
public Amount totalPrice;
```

Opting in changes the column type, so `ddl-auto=validate` fails until you migrate the column. The migration
preserves what the column holds, but it cannot restore precision a `double` already lost. A `numeric(p,s)`
column also rounds and pads values to scale `s`.

`types-springdata-jpa` remains **experimental**; `types-jdbi` is the recommended SQL module.
→ [MIGRATION-0.60 § `types-springdata-jpa`](MIGRATION-0.60.md#types-springdata-jpa-exact-numeric-converters-for-amount-and-percentage-opt-in)

### 2.6 Spring configuration metadata is back

Since JDK 23, `spring-boot-configuration-processor` had not been running, because annotation processors found on
the classpath no longer run without a flag. As a result, no starter shipped
`META-INF/spring-configuration-metadata.json`. The build now passes `-proc:full`, so every starter ships
metadata again, which brings back IDE completion and documented defaults for `essentials.*` properties.
`spring-boot-starter-postgresql` alone documents 50 properties. The `essentials.eventstore.cdc.*` properties of
`spring-boot-starter-postgresql-event-store` were missing even with the processor running, because they are bound
from a type in another module; all 52 now have a description and a default.

### 2.7 Subscription gap statistics

Subscription statistics now include a `gaps` section. It holds `newTransientGaps`, `resolvedTransientGaps` and
`promotedToPermanentGaps`, plus when the last new gap and the last promoted gap happened. It is available as
`SubscriptionStatistics.gaps()`, as `gaps` in the admin API, and in the admin UI's subscription drawer. Before this,
the only gap figure was `polling.gapReconciliations`. That counts reconciliation passes, one per poll, whether or not
there was a gap, so it could not show whether a subscriber was finding gaps or giving up on them. It is unchanged,
now documented as what it is, and no longer shown in the admin UI.

The new counts cover every path that reconciles gaps, including the CDC catch-up (backfill) that the polling
statistics do not see, and the gaps a CDC subscription opens and fills on the live bus. They come from the rows each reconciliation actually changed, and are recorded only after
its unit of work commits. A rising `promotedToPermanentGaps` is the one to watch: each is a global event order the
subscriber stopped waiting for.

For a custom `SubscriptionGapHandler`, override the new default method `reconcileGapsAndReport` to contribute to
these counts. An implementation that does not override it keeps working and reports nothing. Code that constructs
the statistics records itself needs the new component; see [§1.7](#17-subscription-statistics-records-have-a-new-component).

The polling statistics' description is corrected as well. It used to say they stay at zero under CDC, but a
subscription polls when it starts before CDC is active and whenever it falls back to polling.

### 2.8 CDC interruptions are recorded

The CDC status has a new `interruptions` section. It counts every time CDC stopped being active other than by a
requested stop, such as a dropped replication connection, a stream error, or the slot taken over by another
instance. It also keeps whether the latest interruption is still ongoing, and when and why it began and when CDC
recovered. It is reported in the admin API (`event-store/cdc/status`), in the health details (`interruptions.*`),
as the metric `essentials.cdc.interruptions_total`, and on the admin UI's CDC page. Before this, an interruption
that recovered on its own left no trace: the availability `reason` is cleared when CDC is active again.

Two related corrections:

- **`fallbackCount` now counts a running subscription that switches from CDC to polling**, not only one that
  starts on polling. So one interruption adds one fallback per affected subscription, where before it added
  nothing.
- **A dropped replication connection logs one stack trace, not two.** The failed advisory-lock release that
  follows it is now logged at DEBUG, because PostgreSQL releases the lock when the session ends.

### 2.9 springdoc describes semantic types as their JSON (opt-in)

`types-spring-web` adds `SingleValueTypeModelConverter`, a springdoc (swagger-core) `ModelConverter`. Without it
springdoc publishes a `CharSequenceType` id as an object with `bytes`, `empty` and `value`, and a Kotlin
value-class property under its mangled getter name (`orderId-nb-kci0`), so every client generated from the
OpenAPI document is typed wrong. With it each semantic type the web mapper writes as a bare scalar is published as
its value's schema (`string`, `integer`/`int64`, `string`/`date-time`, ...), and Kotlin property names are the
real ones.

It is **not auto-configured**, so upgrading changes nothing. To opt in, declare it as a bean in the application
that runs springdoc; springdoc is a `provided` dependency, so the application supplies its own:

```java
@Bean
SingleValueTypeModelConverter singleValueTypeModelConverter() {
    return new SingleValueTypeModelConverter();
}
```

→ [`LLM/LLM-types-spring-web.md` § OpenAPI with springdoc](../LLM/LLM-types-spring-web.md#openapi-with-springdoc)

### 2.10 Choose what an async subscription does with a failing event

Before 0.60 a direct asynchronous subscriber (`subscribeToAggregateEventsAsynchronously`,
`exclusivelySubscribeToAggregateEventsAsynchronously`, `batchSubscribeToAggregateEventsAsynchronously`) retried I/O
errors forever, but **any other handler exception skipped the event**: one ERROR line, the resume point moved past it,
and the event was never redelivered, not even after a restart. A projection just missed it, and there was nothing to
alert on.

`SubscriptionErrorPolicy` makes that a choice, set per manager with
`EventStoreSubscriptionManagerBuilder.setSubscriptionErrorPolicy(...)` (or on `PersistedEventSubscriberBuilder` /
`BatchedPersistedEventSubscriberBuilder`), and in Spring Boot with
`essentials.eventstore.subscription-manager.error-policy.{mode,max-retries,initial-backoff,max-backoff}` and
`error-policy.auto-resume.{enabled,initial-delay,max-delay,max-attempts}`:

| Policy | On a non-I/O handler exception |
|---|---|
| `defaultPolicy()` — **the default, new in 0.60** ([§1.1.9](#119-a-failing-event-is-retried-and-stops-its-subscription-instead-of-being-skipped)) | `retryThenStop(3)` with automatic resume: retry, stop at the event, resume by itself until it succeeds |
| `skip()` — 0.50's behaviour | Log at ERROR, advance past the event, continue |
| `retryThenSkip(n[, initialBackoff, maxBackoff])` | Call the handler again up to `n` times, each in a new `UnitOfWork`, with exponential backoff (default 100 ms doubling to 1 s), then skip |
| `stop()` | On the first failure, stop at the failed event without advancing the resume point; the subscription continues *at* it when resumed - by itself (below), or by hand (`EventStoreSubscription#resumeIfStoppedByErrorPolicy()`, the manager's `resumeSubscriptionIfStoppedByErrorPolicy(...)`, admin API `POST /event-store/subscriptions/{subscriberId}/aggregate-types/{aggregateType}/resume`) or started again (restart, fenced-lock hand-over, `resetFrom`) |
| `retryThenStop(n[, initialBackoff, maxBackoff])` | Retry as `retryThenSkip`, then stop as `stop()` - the choice for a projection that must not skip an event without halting on a transient failure |

An event handler can override the manager's policy for its own subscription - `PersistedEventHandler` /
`BatchedPersistedEventHandler#subscriptionErrorPolicy()`, or `getSubscriptionErrorPolicy()` on a `ViewEventProcessor` /
`EventProcessor` - so projections and side-effect subscribers on one manager can differ.
A batched subscription applies the policy to the batch as a whole. In-transaction subscriptions and subscriptions
For a subscription that forwards to an `Inbox` (`EventProcessor`) the policy governs only the forward step - deserializing
the event and adding it to the `Inbox`; the Inbox's `RedeliveryPolicy` governs the message. **Upgrading changes the
default** - see [§1.1.9](#119-a-failing-event-is-retried-and-stops-its-subscription-instead-of-being-skipped).

**A stopped subscription resumes by itself.** The policy's `autoResume()` component,
`SubscriptionErrorPolicy.AutoResume(enabled, initialDelay, maxDelay, maxAttempts)`, resumes a subscription that `stop()` or
`retryThenStop(...)` stopped, at the failed event, after a delay that doubles with every resume at the same event:
`AutoResume.defaults()` (10 s doubling to 5 min, unlimited - never skips), `unlimited(initialDelay, maxDelay)`,
`skippingAfter(maxAttempts, initialDelay, maxDelay)` and `disabled()`, set with `policy.withAutoResume(...)` /
`withoutAutoResume()`. Every stop is still reported and `isStoppedByErrorPolicy()` stays `true` between attempts, so a
subscription stuck on a poison event stays visible. A pending resume is cancelled when the subscription is stopped,
unsubscribed, reset with `resetFrom`, loses its fenced lock, or the application starts shutting down; a manual resume
cancels it too. Only subscriptions an `EventStoreSubscriptionManager` creates resume by themselves; a subscriber built
directly with `PersistedEventSubscriberBuilder` / `BatchedPersistedEventSubscriberBuilder` still defaults to `skip()`.
`skippingAfter(...)` is opt-in because a skip there is easy to lose track of: for an `EventProcessor` the skipped event
never reaches the `Inbox` or its dead-letter queue. It is reported by the new observer callback
`subscriptionSkippedEventAfterAutoResumes(GlobalEventOrder, int autoResumes, Throwable, EventStoreSubscription)` (default
no-op) and the counter `essentials.eventstore.subscription.skipped_after_auto_resumes`.

**A handler can take a failed event over instead of the policy giving up.** The new default method
`PersistedEventHandler#handOffFailedEvent(PersistedEvent, Throwable)` (default `false`, so nothing changes for existing
handlers) is called once the policy has used up its retries, in place of skipping or stopping, after the event's
`UnitOfWork` was rolled back. A handler that returns `true` has taken the event over, typically by queueing it in a
`UnitOfWork` of its own: the subscription carries on as if the event had been handled, without a failure callback, and
the resume point moves past the event only after the hand-off returned. Returning `false` or throwing lets the policy
give up as before. It applies to single-event asynchronous subscriptions. `ViewEventProcessor` uses it
([§1.1.8](#118-a-failed-vieweventprocessor-handler-that-appended-events-or-changed-an-aggregate-is-queued-only-after-a-rollback)).

**Retries hold up only their own subscription.** They run synchronously on the subscription's delivery thread, which
is what keeps events in order, and every asynchronous subscription now has a thread of its own on every path. Two of
those paths used to share one:

- **Batched subscriptions** handled their batches on Reactor's JVM-wide `Schedulers.single()` thread. Each
  `BatchedPersistedEventSubscriber` now handles batches on its own
  `BatchedEventSubscriber-<subscriber>-<aggregateType>-Handler` thread.
- **Under CDC** the handlers ran on the shared `cdc-dispatcher-<slot>` thread (the tailer's thread in `DIRECT`
  mode), so one slow handler held every CDC subscription on the slot. `CdcEventStore` now hands each subscription's
  live events over to a `Cdc-<subscriber>-<aggregateType>` thread. Order is kept, and a subscription never
  back-pressures the shared CDC bus: its hand-over buffers one polling page (`eventStorePollingBatchSize`). A
  subscription that falls further behind than that logs a WARN, counts
  `essentials.cdc.eventstore.live_source.overflow.count`, catches up from the database and rejoins the bus, logging
  `Caught up after falling behind the CDC bus` at INFO. Nothing is lost or delivered twice, and it is not counted as
  a CDC fallback. Size `eventStorePollingBatchSize` to absorb an ordinary burst; a larger burst only costs the
  subscription a catch-up.

**Stopping a subscription never skips an event.** A stop while a retry is under way (shutdown, fenced-lock hand-over,
`resetFrom`, unsubscribe) abandons the retries without reporting a failure, and the resume point stays at the event, so
the restarted subscription handles it again. Stopping a batched subscription now also interrupts a batch in progress,
as the polling path already did for a single event; the batch is handled again from its first event. Only a real stop
counts: under CDC a subscription that switches between polling and the CDC bus (at boot, when replication drops or
recovers) has its delivery thread interrupted too, but a retry backoff that switch interrupts is waited out and the
retries carry on. See
[MIGRATION-0.60.md § Event store subscriptions](MIGRATION-0.60.md#event-store-subscriptions).

**Signals to alert on** (all additive; nothing needs configuring beyond the policy itself):

- **Failure counters.** `MeasurementEventStoreSubscriptionObserver` counts events an asynchronous subscription gave up
  on in `essentials.eventstore.subscription.handle_event_failed` (tags `subscriber_id`, `aggregate_type`,
  `event_handler`, `event_type`, optional `Module`), and in-transaction handler failures in
  `essentials.eventstore.subscription.handle_event_transactional_failed`. Its `handleEventFailed` used to be a no-op.
  The counters need the new 3-argument constructor that takes a `MeterRegistry`; the 2-argument constructor is kept
  and records none. The Spring Boot starter always passes its registry, and the counters are not gated on the
  execution-time metrics toggle.
- **A batch failure callback.** `EventStoreSubscriptionObserver.handleEventBatchFailed(...)` (default no-op) reports a
  failed `BatchedPersistedEventHandler` batch. Before, the batched subscriber did not notify the observer at all.
- **A stopped subscription is visible.** A stop leaves `EventStoreSubscription#isActive()` `true` on purpose: it
  means "running here" (for an exclusive subscription "holds the fenced lock"), and the lock is kept so the event does
  not flap to another node that would fail the same way. Tell a halted subscription apart with
  `EventStoreSubscription#isStoppedByErrorPolicy()` (default `false`), the observer callback
  `subscriptionStoppedByErrorPolicy(GlobalEventOrder, Throwable, EventStoreSubscription)` (default no-op), the gauge
  `essentials.eventstore.subscription.stopped` (`1` while stopped, and through every resume until the failed event is
  handled - `EventStoreSubscription#isRecoveringFromErrorPolicyStop()`, default `false`, is that second half - published by the new
  `SubscriptionStoppedMicrometerMonitor`; the Spring Boot starter wires it whenever a `MeterRegistry` is present), the
  counter `essentials.eventstore.subscription.stopped_by_error_policy` (one per stop, so one more for every automatic
  resume that fails again), or the admin API field
  `stoppedByErrorPolicy`. Both meters are tagged `subscriber_id`, `aggregate_type`, optional `Module`. Alert on
  the gauge: the counter records that a stop happened, so `increase(...) > 0` resolves while the subscription is still
  stopped and `> 0` keeps firing after it has been started again. The admin UI shows a "Stopped by error policy" badge in
  place of "Active".

`ApiSubscription` gains a nullable `stoppedByErrorPolicy` component (`null` when the subscription does not run in the
instance that answers), so its constructor takes one more argument — see [§1.7](#17-subscription-statistics-records-have-a-new-component). The admin
API response only gains an optional field.

→ [`postgresql-event-store` README § Subscription Error Policy](../components/postgresql-event-store/README.md#subscription-error-policy),
[`LLM/LLM-postgresql-event-store.md` § Direct async subscribers retry, stop and resume at a failing event](../LLM/LLM-postgresql-event-store.md#direct-async-subscribers-retry-stop-and-resume-at-a-failing-event)

### 2.11 The `essentials` Claude Code plugin

0.60.0 is the first release that ships the `essentials` plugin for Claude Code from this repository. It is for
teams that build Essentials applications with Claude Code. It gives Claude the framework's documentation, a design
law for vertical slices, and an application stack contract (requirements S1–S11, with the version pins that go
with them), plus commands that scaffold, audit and review a project against them. It changes nothing in the
framework, and nothing for an application that does not use Claude Code.

The repository is also the plugin's marketplace (`.claude-plugin/marketplace.json`). To install:

```
/plugin marketplace add trustworksdk/essentials-project
/plugin install essentials@essentials-marketplace
```

**The plugin's version is the Essentials release it targets**, so this one is `0.60.0`. A plugin-only release
adds `-1`, `-2`, …, and the next Essentials release resets the suffix. Claude Code updates an installed plugin
only when that version changes. Third-party marketplaces do not auto-update by default: turn it on in the
`/plugin` Marketplaces tab, or refresh with `/plugin marketplace update essentials-marketplace`. To pin a branch
or tag, add `#<ref>` to the marketplace source.

| Command or skill | What it does |
|---|---|
| `/essentials:init` | Scaffolds a new Spring Boot project: Kotlin or Java, WebFlux or WebMvc, PostgreSQL event-sourced, PostgreSQL CRUD or MongoDB, with an optional React frontend and Docker Compose. Every version comes from the plugin's pins. It then lints the project against the stack contract, builds it and starts its Spring context before handing it over |
| `/essentials:add-slice`, and `add-command-slice`, `add-view-slice`, `add-automation-slice`, `add-translation-slice` for one kind | Scaffold a vertical slice into an existing project, in Java or Kotlin: its source, `slice.yaml` manifest, test and Spring wiring |
| `/essentials:slice-check`, `slice-discover`, `slice-map` | Audit a project that follows the slice law; analyse one that does not and infer what it would look like if it did; render the structure of one that does. Discover and map are read-only |
| `/essentials:review` | Reviews a change (the current branch, a ref, a pull request or a path) against the framework's traps index, the stack contract and the slice law. Every finding carries an `ESS-…` id that links to the section that owns it |
| `/essentials:upgrade` | Brings an existing project up to what the installed plugin ships and audits it against the stack contract, offering each fix singly. It reads the project's own Essentials version, and reports a finding whose fix would break an application still on an older release as applying with the Essentials upgrade, rather than offering it on its own. It moves no version pin, except a Kotlin compiler too old for the project's own Java baseline |
| `/essentials:intro` | Read-only orientation |
| `essentials-docs` skill | Loads on questions about Essentials and on code that uses `dk.trustworks.essentials.*`, and answers from the bundled framework docs |
| `essentials-change` skill | Picks up a change described in prose in a project that follows the slice law, finds the slice that owns it from its manifest, and applies the slice law to the change |

The deterministic half of the commands is Python scripts, which need Python 3.11 or newer; the plugin README lists
the rest of the requirements.

The framework docs the plugin bundles, in `essentials-plugin/references/llm/`, are a generated copy of `LLM/`, made
by `scripts/sync-plugin-llm.sh`. Links that leave `LLM/` are rewritten to GitHub URLs that point at this release's
tag, `0.60.0`, not at `main`, so an installed plugin's links match the release it targets. `LLM/` remains the only
place the docs are edited; see the root README's [Editing the LLM docs](../README.md#editing-the-llm-docs).

→ [`essentials-plugin/README.md`](../essentials-plugin/README.md),
[`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md)

### 2.12 Subscribers acknowledge the gap fills they handled

A gap fill arrives after higher global orders, so a subscriber's resume point is already past it and its open
transient gap is the only durable record that the event is still owed. 0.60 resolves that gap only once the
subscriber has handled the event, so a stop or a crash before then delivers the event again instead of losing it.
Subscriptions created by the `EventStoreSubscriptionManager` do this out of the box, and resolve the gap inside the
handler's own unit of work, atomically with the handling. A rolled-back handler leaves the gap open.

The hook is a new, opt-in API in `postgresql-event-store`. Everything in it is additive:

| API | What it is |
|---|---|
| `SubscriberAcknowledgement` | One per subscription. `create()`, then the subscriber calls `acknowledge(event)` / `acknowledge(events)` for every event it handled or gave up on. `isHonoured()` tells it whether the event store resolves gap fills on acknowledgement |
| `EventStore.pollEvents(..., SubscriberAcknowledgement)` (for a `long` and a `GlobalEventOrder` start) and `EventStore.unboundedPollForEvents(..., SubscriberAcknowledgement)` | New default methods. The defaults ignore the acknowledgement and call the existing overload; `PostgresqlEventStore` and `CdcEventStore` override them |
| `SubscriptionGapHandler.resolveFilledGaps(AggregateType, List<PersistedEvent>)` | New default method that resolves the transient gaps of the given fills. The default calls `reconcileGapsAndReport(...)`; `PostgresqlEventStreamGapHandler` overrides it with a plain delete that never records or promotes a gap |
| `PersistedEventSubscriberBuilder.setSubscriberAcknowledgement(..)`, `BatchedPersistedEventSubscriberBuilder.setSubscriberAcknowledgement(..)` | Optional. Hand the subscriber the acknowledgement you pass to `pollEvents` |

A subscriber that opts in must acknowledge each event once it has handled it, ideally inside the unit of work it
handled it in, and each event it gives up on (skipped, or handed off). It must not acknowledge an event it did not
handle because it stopped: that event is owed to the next subscription. While a fill is unacknowledged the same
subscription is not handed it again, however often a poll reads it.

A direct caller of `pollEvents` or `unboundedPollForEvents` that passes no acknowledgement keeps the behaviour
described in [§3](#3-bug-fixes): the gap is resolved once the event is handed on.

→ [`postgresql-event-store` README § Gap Types](../components/postgresql-event-store/README.md#gap-types),
[`docs/MIGRATION-0.60.md` § A gap is resolved only once its event was handled](MIGRATION-0.60.md#a-gap-is-resolved-only-once-its-event-was-handled)

### 2.13 Gap handling extension points

Six additions let a custom gap setup and polling optimizer keep what the defaults do. All are additive, in `postgresql-event-store`:

| API | What it is |
|---|---|
| `PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection()` | The default per-poll selection (every open gap up to 50; beyond that the 20 highest, the 10 lowest and a rotating window of 20), to pass as-is or compose in your own strategy |
| `SubscriptionGapHandler#transientGapGiveUpThreshold()` and `ResolveTransientGapsToPermanentGapsPromotionStrategy#permanentGapThreshold()` | Default methods returning `Optional<Duration>`. `thresholdBased(n)` returns n seconds and `PostgresqlEventStreamGapHandler` passes it on. A CDC subscription gives up waiting for a gap at that threshold, instead of a hard-coded 120 s |
| `LoadEventsByGlobalOrder#getOnlyLoadPayloadIfEventBelongsToTenant()` / `setOnlyLoadPayloadIfEventBelongsToTenant(Tenant)`, and the builder setter | The tenant whose events a tenant-filtered poll loads with payload; other tenants' events come without |
| `AggregateEventStreamPersistenceStrategy#loadEventsByGlobalOrderOmittingOtherTenantsPayloads(...)` | Default method that loads everything; the built-in strategy overrides it so other tenants' payloads are never read |
| `SubscriptionGapHandler#giveUpTransientGaps(AggregateType, List<GlobalEventOrder>)` | Default method the CDC event store calls when it gives up waiting for a gap's event, so the give-up is recorded durably. `PostgresqlEventStreamGapHandler` promotes the given-up gaps that are still transient to permanent gaps of the aggregate type, which every subscriber of it then skips. A caller must have waited at least `transientGapGiveUpThreshold()` for each gap it passes |
| `EventStorePollingOptimizer#mayRepollImmediatelyAfterAnEmptyPoll()` | Default method, `false`. An optimizer that returns a zero delay on purpose overrides it to return `true`; only `NotifyAwareEventStorePollingOptimizer` does |

→ [`docs/MIGRATION-0.60.md` § The default gap handler looks for more gaps per poll](MIGRATION-0.60.md#the-default-gap-handler-looks-for-more-gaps-per-poll),
[§ CDC gives up waiting for a gap at the gap handler's threshold](MIGRATION-0.60.md#cdc-gives-up-waiting-for-a-gap-at-the-gap-handlers-threshold),
[`spring-boot-starter-postgresql-event-store` README § Gap Handling](../components/spring-boot-starter-postgresql-event-store/README.md#gap-handling)

### 2.9 Event causation

Every persisted event can now record which event caused it, so "why did this happen?" is a lookup. The event store
always had the `caused_by_event_id` column, and the starter's default mapper claimed to fill it, but nothing did.

- **Recorded by default.** The framework binds the delivered event as the cause around every handler it calls -
  `EventProcessor` (both `REQUIRED` and `UnitOfWorkMode.NONE` handlers), `ViewEventProcessor`,
  `InTransactionEventProcessor`, and async and in-transaction subscriptions - and `CausationPersistableEventEnricher`
  writes it. Lazily appending repositories record the cause bound when the aggregate joined the UnitOfWork.
- **Carried across hand-offs**: through `Inbox`, `Outbox` and `DurableLocalCommandBus.sendAndDontWait` in message
  metadata (`CausationDurableQueuesInterceptor`), and through `sendAsync`/`sendAndDontWait` on a Reactor worker by
  a new command-bus SPI, `CommandContextPropagator`.
- **Bound explicitly** where the framework cannot see it - a webhook answering an event it looked up, a batched
  subscription - with `CausationContext.where(eventId)`.
- **Looked up** with `EventStore.findEvent(EventId)` ("what caused this?") and `EventStore.loadEventsCausedBy(EventId)`
  ("what did this cause?"); the latter needs the opt-in partial index
  `essentials.eventstore.causation.index-enabled=true`.
- **Admin API and console**: list an aggregate's recent events (`GET /event-store/aggregate-types/{aggregateType}/aggregates/{aggregateId}/events`),
  then walk from any of them with `GET /event-store/events/{eventId}`, `…/causation-chain` and `…/caused-events`. The
  console's *Event causation* page starts from an aggregate type and id, or an event id, and a queued or dead-lettered
  `EventProcessor` inbox message links to it. Identity and cause only, no payloads.
- **Cost**, measured in the performance lab: no measurable difference on appends or through an `EventProcessor`;
  WAL grows by the stored id. Across a durable queue, about 116 bytes of WAL per message and 0.5% throughput.

Correlation ids are still not populated; trace context covers "what did this request do". Design and measurements:
[event-causation.md](./event-causation.md). How to configure it: [LLM-postgresql-event-store.md](../LLM/LLM-postgresql-event-store.md#event-causation).

### 2.10 Save a busy subscriber's resume point early (opt-in)

Resume points are saved every `snapshotResumePointsEvery`, so after a crash a subscriber redelivers whatever it handled
since the last tick. On a high-throughput subscriber that can be many events. `snapshotResumePointsAfterEvents` adds a
bound by count: a resume point that has advanced that many `GlobalEventOrder` positions since it was last saved is
written ahead of the next tick.

```java
EventStoreSubscriptionManager.builder()
    ...
    .setSnapshotResumePointsAfterEvents(1000)
    .build();
```

```properties
essentials.eventstore.subscription-manager.snapshot-resume-points-after-events=1000
```

`0`, the default, disables it. The threshold is checked in memory every tenth of `snapshotResumePointsEvery`, kept
between 50 ms and 1 second. The check runs on the same thread as the periodic save, so the two never write concurrently.
Only resume points past the threshold are written, so an idle or slow subscriber costs no extra database writes. The
distance is counted in global event order positions. For a tenant-filtered subscriber, or across gaps, it is an upper
bound on the events actually handled.

---

## 3. Bug fixes

| Fix | Affected |
|---|---|
| **A fenced lock whose `lockAcquired` callback threw stayed held forever.** The instance owned a lock it was not serving, no other instance could take it, and the callback never ran again. The lock is now released, and the next tick retries. This was easy to hit through an `Inbox` in `SingleGlobalConsumer` mode, which wires its consumer inside the callback | Fenced-lock users, Inbox/Outbox |
| **One bean's failure to stop abandoned the rest of the shutdown.** `DefaultLifecycleManager` now logs the failure and carries on stopping the other beans. The typical trigger was a database that had gone away | Everyone using the Spring lifecycle manager |
| **`CentralizedMessageFetcher` threw a guaranteed NPE** for a message claimed just before its consumer was cancelled. That message is now retried after one polling interval | `postgresql-queue` with the centralized fetcher |
| **`EventStore.appendToStream(…, Optional<Long>, Object...)` appended the wrong events**: the `Optional` and the array, as two events. This had been the case since at least 0.40. It now appends the events it is given. Streams it already wrote to hold malformed events | Callers of that overload (nothing in Essentials called it) |
| **`QueueMessage.builder().setMessage(…)` dropped ordering**, see [§1.1.3](#113-queuemessagebuildersetmessageorderedmessage-now-keeps-the-ordering) | Ordered-message producers |
| **Jackson 3 `MismatchedInputException` was never classified as permanent.** It is now matched by class name, so it is recognised under Jackson 3 | Queue consumers |
| **`alwaysRetryOn(...)` had no effect**, see [§1.1.2](#112-dead-letter-classification-changed-in-two-ways) | Custom redelivery policies |
| **`RedeliveryPolicy.exponentialBackoff` and `linearBackoff` did not back off.** Every redelivery after the first waited the same delay. See [§1.1.7](#117-exponentialbackoff-and-linearbackoff-redelivery-delays-now-grow) | Durable queue consumers, `Inbox`, `EventProcessor`, `DurableLocalCommandBus` |
| **`AggregateIdSerializer.serializerFor(…)` rejected a Kotlin `StringValueType` id** with `EventStoreException: Couldn't find a matching …AggregateIdSerializer`, so a Kotlin `AggregateTypeConfiguration` built that way failed at context start. It now returns `StringValueTypeAggregateIdSerializer` for such an id, the serializer you could already construct explicitly. The Kotlin type is matched by name, so Java-only classpaths are unaffected | Kotlin deciders on `kotlin-eventsourcing` |
| **Slow-query statistics were always empty, and `pg_cron` was never created by the framework.** The check before the best-effort `CREATE EXTENSION` read `pg_extension` (installed) instead of `pg_available_extensions` (installable), so the create only ran when the extension already existed. `pg_stat_statements` is now created at startup when the server preloads it and the role may create extensions, and `pg_cron` when the server offers it; a refusal is logged and treated as unavailable without failing the start | Admin API query statistics, the Essentials scheduler |
| **A `ViewEventProcessor` skipped an event whose direct handling failed on SQL or deserialization, instead of queueing it.** The handler runs in the subscription's transaction, so a failed SQL statement aborted it and the fallback `queueMessage` failed with "current transaction is aborted"; and a payload that could not be deserialized failed before the fallback was reached. The direct handler now runs under a savepoint, which rolls back only its own writes, and deserialization happens inside the failure handling, so both are queued - an undeserializable event ends up as a visible dead letter. A handler that appended events or changed an aggregate is queued only after its `UnitOfWork` was rolled back, see [§1.1.8](#118-a-failed-vieweventprocessor-handler-that-appended-events-or-changed-an-aggregate-is-queued-only-after-a-rollback) | `ViewEventProcessor` users |
| **The queue statistics trigger counted a purge as a delivery.** Fixed by the replacement in [§2.4](#24-durable-queue-observability) | Statistics consumers |
| **A CDC subscription lost events when it moved from polling onto the CDC bus.** The bus replays nothing to a subscriber that attaches late, so the events published while the subscription was still on polling - after its last poll, during the `activeCutbackDebounce` window, or while its handler finished an event - never reached it, and the next bus event moved it past them. This hit every subscription started before CDC was active and every subscription after a replication outage. One started while CDC was active instead stalled on the first missing event until `liveDrainStallThreshold`. Every move onto the bus now first reads from the database everything up to the current head, then continues from the bus, so the hand-over is gap-free | `postgresql-event-store` with Hybrid CDC enabled |
| **A CDC subscription dropped an event whose transaction committed after a transaction holding a higher global order.** Two transactions appending to the same aggregate type reach the CDC bus in commit order, not in global order, and each subscription only let through events above the highest global order it had delivered. The late event is now delivered when it arrives, after the higher ones, which is the order polling already delivers gap-filled events in. A gap first seen on the bus is now also recorded with the subscriber's gap handler, so a restart or fenced-lock hand-over before the late event arrives no longer loses it. A gap is waited for up to 120 s, the gap handler's default permanent-gap threshold. See [MIGRATION-0.60.md § Under CDC, an event that commits late is delivered instead of dropped](MIGRATION-0.60.md#under-cdc-an-event-that-commits-late-is-delivered-instead-of-dropped) | `postgresql-event-store` with Hybrid CDC enabled and concurrent writers to an aggregate type |
| **A batched CDC subscription could stop receiving events while staying active.** A subscription started while CDC was active asked for more live events before its subscriber had taken the previous ones, so a `BatchedPersistedEventHandler` busy with a batch let the internal buffer overflow, which ended the subscription's event stream without a stop or a failure callback. It now asks for one more event each time its subscriber takes one | Batched subscriptions under Hybrid CDC |
| **Every CDC back-fill left its `CDC-Backfill-<aggregateType>` thread running.** The thread was disposed only when the subscription was cancelled, not when the back-fill completed, so each subscription start and each stall recovery under CDC added one | `postgresql-event-store` with Hybrid CDC enabled |
| **A CDC subscription started while CDC was active stalled for up to three minutes on every rolled-back append.** Its live events had to arrive in strict global order, so a global order that never reaches the CDC bus - one a rolled-back transaction took, as an optimistic-concurrency conflict does - held back every later event until `liveDrainStallThreshold` (180 s by default) made it re-read from the database. It now passes live events on as the bus delivers them, like every other CDC subscription, and a lower global order that commits late is delivered when it arrives. `liveDrainStallThreshold` no longer has an effect, see [§4](#4-deprecations) | `postgresql-event-store` with Hybrid CDC enabled |
| **Polling delivered events again after a gap was filled.** Each delivered event set the next read position to its global order + 1, so a poll that returned only the late event of a filled gap moved the read position back, and the next poll delivered everything above it a second time. The read position now only moves forward. A poll that returned more gap fills than its subscriber asked for also resolved those gaps without delivering the extra events, which only the re-read used to cover; such a gap now stays open until a later poll delivers its event | Polling subscriptions with a gap handler |
| **Polling could miss a late commit behind rolled-back appends for good.** The default `PostgresqlEventStreamGapHandler` asked each poll for only the 2 lowest open gaps, so with rolled-back appends below it a late commit was not looked for until those were promoted to permanent after 120 s - and when it had been found in the same poll as them, it was promoted with them and never delivered. The default now asks for every open gap up to 50, and beyond that for the 20 highest, the 10 lowest and a rotating window of 20 in between. A gap handler built with your own `ResolveTransientGapsToIncludeInQueryStrategy` is unchanged | Polling subscriptions with the default gap handler, the Spring Boot starter included |
| **A tenant-filtered polling subscription recorded other tenants' events as gaps.** The tenant was filtered in SQL, so their global orders looked missing; they were recorded as transient gaps, promoted to permanent gaps, which every subscriber of the aggregate type shares, and counted in the gap statistics. Polling now loads every tenant's events in its range, reconciles gaps against all of them, and filters by tenant in memory, as the CDC path does. Permanent gaps recorded this way before the upgrade stay until `resetPermanentGapsFor(aggregateType)` is called. A tenant filter on a store without a tenant column used to fail every poll; it now delivers every event, since an event without a tenant belongs to every tenant | Polling subscriptions with `onlyIncludeEventIfItBelongsToTenant` |
| **A subscription that stopped or crashed could lose a gap fill it had not handled yet.** A gap fill arrives after higher global orders, so the subscriber's resume point is already past it, and the open gap is the only durable record that its event is still owed. Polling and CDC resolved that gap when they loaded the event, before handing it to the subscriber, so a stop (shutdown, fenced-lock hand-over, `resetFrom`, unsubscribe) or a crash in between lost the event for good: the rest of a poll or back-fill page a stop cut short, a fill still waiting in a batched subscription's batch, or a fill whose handler had not committed when the process died. A gap is now resolved only once its event has been handled: for a subscription the `EventStoreSubscriptionManager` creates, inside the handler's own unit of work, so a fill still waiting for its batch, for an I/O retry or for demand is delivered again after a stop or crash, and nothing else is. A caller of `pollEvents` that passes no `SubscriberAcknowledgement` gets the fill resolved once it is handed on, and a stopped batched subscriber built without one keeps its resume point at the lowest fill it had queued, so the events between it and the old resume point are delivered again too. See [2.12](#212-subscribers-acknowledge-the-gap-fills-they-handled) | Subscriptions with a gap handler, on polling and under Hybrid CDC |

| **A gap whose event existed could be promoted to permanent.** With more than 50 open gaps, a reconciler whose query did not include a gap - another node with the same subscriber id, or under CDC the delegate's poll - promoted it once it was old enough, even when its event had committed and was waiting to be acknowledged. A gap is now promoted only when the poll asked for it and its event was missing, and every poll also asks for the gaps old enough to promote | Subscriptions with a gap handler and many open gaps |
| **A gap handler on a different `UnitOfWorkFactory` than the event store's failed** with `NoActiveUnitOfWorkException`. It now resolves and records gaps in a unit of work of its own and logs a one-time WARN | Custom `PostgresqlEventStreamGapHandler` wiring |
| **Polling with no optimizer busy-looped.** `pollEvents(...)` without an `EventStorePollingOptimizer`, or with `None()`, re-polled at once after an empty poll. It now waits the polling interval | Direct `pollEvents` callers |
| **A polling subscription with batch size 1 stalled for seconds** on a hole of rolled-back appends at its read position, because the batch size grew only every tenth empty poll and `1 * 1.5` truncated back to 1. Every growth is now at least one, and the range doubles on each empty poll | Polling subscriptions with a small batch size |
| **Tenant-filtered polling read other tenants' payloads.** Since loading every tenant's events to detect gaps correctly, a poll fetched the payload and metadata of every tenant's events. Other tenants' events now come without, so those columns are never read or transferred | Tenant-filtered polling subscriptions |
| **A CDC subscription could hang on a live-source failure.** A failure of the live source while a started-while-ACTIVE subscription was handing on an event was emitted concurrently with that event, rejected as non-serialized and dropped, so the subscription never saw the error. All emissions now go through one drain. A concurrent CDC availability change could likewise be dropped and leave a stale state; that is serialized too | Hybrid CDC |
| **CDC ignored a custom gap promotion threshold**, giving up on a missing `GlobalEventOrder` after a hard-coded 120 s. It now follows the gap handler's threshold | Hybrid CDC with `thresholdBased(n)` other than 120 |
| **A CDC gap give-up was not durable.** When the CDC event store gave up waiting for a gap's event, the running subscription dropped a late-committing event for it, but after a restart that event was delivered and its gap never closed. The give-up is now recorded with the gap handler (`giveUpTransientGaps`), so the event is dropped in both cases. It is recorded with the next event delivered and when the subscription ends (unless a unit of work is current on the thread that stops it), and a failed write is tried again. Recording it promotes the gap to a permanent gap of the whole aggregate type, as a polling promotion does, so every other subscriber of the aggregate type skips that global order too. Only a gap given up after waiting the gap handler's give-up threshold is recorded: one the CDC event store stops waiting for because more than 10,000 gaps were waited for at once stays a transient gap, and a restarted subscription waits for it again | Hybrid CDC |
| **CDC left a gap open when its fill was dropped as a duplicate**, and recording the gap an event from the bus opened claimed the transient gaps had been queried, which could promote an expired gap without proof. Every duplicate the delivery gate drops is now acknowledged to the polling leg (except a fill the subscriber has not handled yet), and the gap an event opens passes no gaps as queried | Hybrid CDC |
| **Tenant filtering compared tenants by `toString()` in memory**, while the SQL predicates compare the serialized form. Polling and the CDC event store now compare `TenantSerializer.serialize(...)` under the aggregate type's `TenantSerializer`; a custom serializer should round-trip. The CDC event store looks the serializer up when the first event with a tenant arrives, so it picks up an aggregate type configured after the subscription started; until then it compares `toString()` and logs one WARN naming the aggregate type (polling fails instead) | Tenant-filtered polling and CDC |
| **A polling worker kept polling back-to-back after its thread was interrupted.** It now stops polling, and, unless the subscription was cancelled, ends the flux with an `InterruptedException` and logs a WARN, so the subscriber does not wait for events no poll will fetch. The worker's scheduler is now released when the flux ends with an error too, not only on cancel | Polling subscriptions |
| **A default gap selection wrapped by a decorator or called from a custom strategy shared one rotation across subscriptions.** Each subscription now keeps its own, per `defaultSelection()` instance, so a strategy composing two instances keeps a rotation for each. It holds only while the selection is called on the gap handler's own thread; called from an executor or a `CompletableFuture`, an instance rotates with its own rotation, shared by every subscription that reaches it that way | Custom `ResolveTransientGapsToIncludeInQueryStrategy` |
| **Re-subscribing an acknowledged polling flux left the previous subscribe's registration behind,** and a `SubscriberAcknowledgement` reused for a second subscription went unnoticed. Subscribing the flux of `pollEvents` or `unboundedPollForEvents` again (`retry()`, `repeat()`), on `PostgresqlEventStore` and `CdcEventStore` alike, now gives each subscribe its own state and disposes the previous subscribe's registration once that subscribe has ended: no listener is left behind and no WARN is logged. A gap fill the previous subscribe handed on and that was not acknowledged keeps its gap, and the next subscribe hands it on again - at least once, so possibly twice, never lost. A one-time WARN is logged only while more than one registration on an instance is active at once, i.e. one acknowledgement shared by two subscriptions | Direct `pollEvents` / `unboundedPollForEvents` callers |
| **The starter's README said CDC was enabled by default.** It is disabled unless `essentials.eventstore.cdc.enabled=true`; the README now says so, and gained a Gap Handling section | `spring-boot-starter-postgresql-event-store` |

| **An aggregate saved by an in-transaction handler could be silently lost.** The commit only made another `beforeCommit` pass when a callback asked for one, so a resource registered during the last pass - by an in-transaction subscription handler, after an event appended directly with `appendToStream` - was committed without ever being written. The commit now makes another pass whenever resources were registered during one, and works on a snapshot of the callbacks so a callback registering more cannot fail it | In-transaction subscriptions and `InTransactionEventProcessor` handlers that change aggregates through a repository |
| **A decider command and a stateful-repository change in one UnitOfWork failed the transaction.** The decider `CommandHandler` appended its events again on every commit pass, and a `StatefulAggregateRepository` append always asks for one. It now appends each command's events once | Decider `CommandHandler` users |

**The 0.50.1 fixes are all in 0.60,** either merged directly or made unnecessary by other work. The polling
unit-of-work leak fix came in unchanged. The Jackson 2-specific fixes are no longer needed now that Jackson 2
is gone. The Jackson 3 `MismatchedInputException` fix and the DevTools fix were already covered by 0.60 work.

---

## 4. Deprecations

The following are deprecated in 0.60 and planned for removal in the next major:

| Deprecated | Replacement |
|---|---|
| `SeparateTablePerAggregateTypePersistenceStrategy.enableNotifyTriggerInstallation(NotifyTriggerInstaller)` | `enableNotifyTriggers(Consumer<String>)`. The trigger becomes part of each event-stream table's schema, which `validate` and `emit` can see. The two methods cannot be combined |
| `EventStoreNotifyPollingBootstrap` constructor taking a `Jdbi` | The constructor without it; the `Jdbi` is no longer used |
| `essentials.eventstore.cdc.event-bus.live-drain-stall-threshold` (`CdcProperties.CdcEventBusProperties#getLiveDrainStallThreshold`/`setLiveDrainStallThreshold`) | None: it has no effect, remove it from your configuration. A negative value is still rejected |
| `CdcLiveDrainStalledException` | None: it is never raised |
| Metric `essentials.cdc.backfill_live.stall_detected` | None: it stays at 0 |

---

## 5. Recommended upgrade order

1. **Upgrade to 0.50.1 first and clear every deprecation warning.** Each removed member's replacement already
   exists in 0.50, so this step can be done and deployed on its own.
2. **Move to the Jackson 3 flavour on 0.50** if you were still on Jackson 2. Check your own types against
   [§1.1.5](#115-jackson-3-reads-your-constructors-differently) while the old mapper is still available to
   compare with.
3. **Remove `transactional-mode` and `use-ordered-unordered-query`** from your configuration, and review any
   handler that relied on `FullyTransactional` ([§1.1.1](#111-essentialsdurable-queuestransactional-modefully-transactional-no-longer-exists)).
4. **Raise the platform:** JDK 25 runtime, Spring Boot 4.1.x, Kotlin 2.3+ with `jvmTarget` 25.
5. **Bump Essentials to 0.60.0** and swap `types-jackson`/`immutable-jackson` for the `-jackson3` artifacts.
6. **Review dead-letter behaviour** ([§1.1.2](#112-dead-letter-classification-changed-in-two-ways)), decide whether your
   async subscriptions may keep the new retry-stop-resume default or must keep skipping
   ([§1.1.9](#119-a-failing-event-is-retried-and-stops-its-subscription-instead-of-being-skipped)), and search for
   `new AppendToStream` with an `Optional` ([§1.1.4](#114-new-appendtostreamtype-id-optional-list-fails-at-runtime-not-at-compile-time)).
7. **Back up, then deploy.** Schedule the first start of a large queue table like an index change ([§1.6](#16-database-objects-changed-on-first-startup)).
8. **Afterwards, and optionally:** set a dead-letter alert on the new counter, tune the `SubscriptionErrorPolicy`
   and alert on the `essentials.eventstore.subscription.stopped` gauge ([§2.10](#210-choose-what-an-async-subscription-does-with-a-failing-event)), and consider
   `essentials.schema.mode=validate` or the shard-owned engine.

---

## 6. Module inventory

**Added**

| Module | Notes |
|---|---|
| `components/postgresql-queue-shard-owned` | The shard-owned engine and its `MessageQueue` SPI |
| `components/postgresql-queue-shard-owned-adapter` | Serves `DurableQueues` from the shard-owned engine |
| `components/spring-boot-starter-postgresql-queue-shard-owned` | Auto-configuration. **Off by default** (`essentials.shard-owned-queue.enabled=false`) |

**Removed**

| Module | Replacement |
|---|---|
| `types-jackson` | `types-jackson3` (same class names) |
| `immutable-jackson` | `immutable-jackson3` (same class names) |

36 modules in the reactor: 33 published plus three examples. `components/foundation-test` remains an internal
test utility, and the `examples/` modules are not released.

---

## 7. Reference

| Document | What it covers |
|---|---|
| [`docs/MIGRATION-0.60.md`](MIGRATION-0.60.md) | Every consumer-facing change, with what to do |
| [`docs/MIGRATION-NEXT_MAJOR.md`](MIGRATION-NEXT_MAJOR.md) | Per-module tables of removed `forRemoval` members and replacements |
| [`docs/platform-upgrade-0.60.md`](platform-upgrade-0.60.md) | The platform upgrade plan and its decisions |
| [`docs/durable-queues-breaking-refactor.md`](durable-queues-breaking-refactor.md) | The durable queue refactor: classification, statistics, `TransactionalMode` |
| [`docs/database-schema-harness.md`](database-schema-harness.md) | Schema harness design and decisions |
| [`docs/durable-queue-shard-owned.md`](durable-queue-shard-owned.md) | How the shard-owned engine works |
| [`docs/durable-queue-ordered-routing-design.md`](durable-queue-ordered-routing-design.md) | The ordered lane's routing, as built |
| [`docs/durable-queue-measurements.md`](durable-queue-measurements.md) | Every measured figure, with its conditions |
| [`docs/RELEASE-NOTES-0.50.0.md`](RELEASE-NOTES-0.50.0.md), [`0.50.1`](RELEASE-NOTES-0.50.1.md) | The previous releases |
| [`LLM/LLM.md`](../LLM/LLM.md) | Entry point for the per-module consumer references |
| [`essentials-plugin/README.md`](../essentials-plugin/README.md) | The `essentials` Claude Code plugin: install, commands, skills and requirements |

**Standing constraints, unchanged in 0.60.0:**

- All third-party integrations are `provided` scope and therefore **not transitive**. Consumers declare their
  own Jackson, Spring, JDBI, Mongo, PostgreSQL driver and Micrometer dependencies.
- FencedLock, DurableQueues (both engines), Inbox and Outbox coordinate **multiple instances of one service**.
  They are not cross-service infrastructure.
- Table and collection names are string-concatenated into queries. Validate them with
  `PostgresqlUtil.checkIsValidTableOrColumnName()` or `MongoUtil.checkIsValidCollectionName()`, and prefer
  hardcoded names. The shard-owned engine uses fixed table names.
- `EventOrder` is per stream; `GlobalEventOrder` is across all streams of an `AggregateType`. Ordering is never
  by timestamp.
