# Essentials 0.60.0 — Release Notes

_Covers everything on `release/0.60` since `0.50.1`: 144 commits, 767 files, +63k/−14k lines._

0.60.0 is the breaking major that 0.50.0 announced. It raises the platform to **Java 25, Spring Boot 4.1 and
Jackson 3 only**. It removes every member 0.50 marked `@Deprecated(forRemoval = true)` and retires
`TransactionalMode`. It also reworks how durable queues classify failures and report their health.

Three new things ship with it:

- a **shard-owned PostgreSQL queue engine**, which delivers without a claim write
- a **database schema harness**, so Essentials can run against a database whose application user has no DDL rights
- **`UnitOfWorkMode.NONE`**, for `@MessageHandler` methods that make blocking calls to other systems

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

⚠️ **The index drops are not zero-downtime safe on a large queue table.** They run inside the bootstrap
transaction under the framework's advisory lock, so a concurrently starting instance waits for them. Plan the
upgrade as you would any other index change.

The queue tables keep three indexes: `idx_<table>_ordered_msg`, `idx_<table>_unordered_ready` and
`idx_<table>_ordered_head`.

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
- Per-consumer redelivery policies.
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
  depends on it being complete, and a partial answer loses messages silently.
- **`pumpThreads + 1` pool connections are held permanently** (3 by default). A smaller pool does not fail
  cleanly: some pumps block forever.
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
`spring-boot-starter-postgresql` alone documents 50 properties.

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
| **The queue statistics trigger counted a purge as a delivery.** Fixed by the replacement in [§2.4](#24-durable-queue-observability) | Statistics consumers |

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
6. **Review dead-letter behaviour** ([§1.1.2](#112-dead-letter-classification-changed-in-two-ways)) and search for
   `new AppendToStream` with an `Optional` ([§1.1.4](#114-new-appendtostreamtype-id-optional-list-fails-at-runtime-not-at-compile-time)).
7. **Back up, then deploy.** Schedule the first start of a large queue table like an index change ([§1.6](#16-database-objects-changed-on-first-startup)).
8. **Afterwards, and optionally:** set a dead-letter alert on the new counter, and consider
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
