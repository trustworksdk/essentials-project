# Migration guide — 0.60

0.60 is a breaking release across several modules. This guide collects what a consumer has to change, one entry
per change. Entries are added as the work lands, so this document grows through the release.

For the 0.50 deprecations whose removal this release carries out, see
[MIGRATION-NEXT_MAJOR.md](./MIGRATION-NEXT_MAJOR.md).

---

## Platform: Java 25, Spring Boot 4.1, Kotlin 2.3

- **Java 25 is the minimum.** Artifacts are compiled with `--release 25` (class-file major version 69); a Java 21
  runtime rejects them with `UnsupportedClassVersionError`.
- **Spring Boot 4.1.x is required.** The starters are built and tested against Spring Boot 4.1.1; 4.0.x is no longer
  supported. Spring Boot 4.1 itself removed the APIs it deprecated in 4.0 — see its release notes.
- **Kotlin consumers need Kotlin 2.3 or later.** The Kotlin artifacts are compiled with Kotlin 2.4 at language and API
  level 2.3, which matches the Kotlin Spring Boot 4.1 manages. Independently of that, a Kotlin compiler older than 2.3
  cannot target JVM 25, and no compiler inlines JVM 25 bytecode (the Essentials `inline` / `reified` functions) into
  code compiled for a lower target, so the application's own `jvmTarget` has to be 25 as well.

---

## Jackson 3 only

0.60 drops Jackson 2 support. Jackson 3 (`tools.jackson`) is the only supported major, matching Spring Boot 4.
The persisted JSON format does **not** change: Jackson 3 writes byte-identical JSON to what the 0.50 Jackson 2
mapper wrote, so existing events, queue payloads and documents stay readable without migration.

### Swap the Jackson modules

The Jackson 2 modules `types-jackson` and `immutable-jackson` are deleted. Depend on `types-jackson3` and
`immutable-jackson3` instead. The class names are unchanged (e.g.
`dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule`), so only the artifact ids move.

Remove any leftover 0.50 `types-jackson` / `immutable-jackson` jar: the classes share fully qualified names with
the Jackson 3 modules, and `EssentialsJacksonModules.modules()` throws `IllegalStateException` when it finds the
Jackson 2 variant on the classpath.

The Maven profiles `-Pjackson2` / `-Pjackson3` and the properties `essentials.jackson.flavor`,
`essentials.types-jackson.artifactId` and `essentials.immutable-jackson.artifactId` are gone. A Jackson 3-only
application no longer needs an explicit `com.fasterxml.jackson.core:jackson-databind` dependency to load the
PostgreSQL or MongoDB starters — drop that workaround if you added it.

### Removed and renamed API

| 0.50 | 0.60 |
|---|---|
| `JacksonJSONSerializer` | `Jackson3JSONSerializer`, or `EssentialsObjectMappers.createJSONSerializer()` |
| `JacksonJSONEventSerializer` | `Jackson3JSONEventSerializer`, or `EssentialsJSONEventSerializers.create()` |
| `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()` | `EssentialsJSONEventSerializers.create()` |
| `EssentialsObjectMappers.createJackson2ObjectMapper()` | `EssentialsObjectMappers.createJackson3ObjectMapper(...)` |
| `EssentialsJacksonModules.jackson3Modules()` | `EssentialsJacksonModules.modules()` |
| `EssentialsJacksonModules.jackson2Modules()`, `isJackson3Flavor()` | removed |
| `Jackson3WalMessageFilter`, `WalMessageFilters` | `DefaultWalMessageFilter` (now the Jackson 3 CDC pre-filter) |

`DurableQueuesSerialization.createDefaultObjectMapper()` and `MongoDurableQueues.createDefaultObjectMapper()` now
return `tools.jackson.databind.ObjectMapper`.

Custom `NotificationDuplicationFilter` implementations: `extractDuplicationKey(...)` now takes
`tools.jackson.databind.JsonNode`, and `NotificationFilterChain` takes the Jackson 3 `ObjectMapper`. Change the
import and replace `asText()` with `asString()`.

### Your own types under Jackson 3

- Annotations from `com.fasterxml.jackson.annotation` (`@JsonProperty`, `@JsonCreator`, …) keep working — Jackson
  3 shares that package. Annotations from Jackson 2's `com.fasterxml.jackson.databind.annotation` package (e.g.
  `@JsonDeserialize(keyUsing=…)`) are **silently ignored** by Jackson 3. Map keys typed with Essentials value
  types need no annotation any more.
- A constructor parameter *name* is part of the JSON contract under Jackson 3: a parameter whose name does not
  match the JSON property it receives gets `null`. Rename the parameter or annotate it with `@JsonProperty`.
- Replace `com.fasterxml.jackson.databind` imports in your own code with `tools.jackson.databind`.

### Spring Boot starters

The starters' `jsonSerializer` bean methods take no parameters any more, and they deliberately do **not** add
`JacksonModule` beans from the application context to the persistence mapper — those are usually web-layer
modules, and picking them up would silently change the persisted format. If you need extra modules on the
persistence mapper, define your own `JSONSerializer` / `JSONEventSerializer` bean; the starter backs off. The
starters still define `EssentialTypesJacksonModule` / `EssentialsImmutableJacksonModule` beans so Spring Boot
registers them on its web `JsonMapper`.

---

## Durable queues

### `useOrderedUnorderedQuery` is removed

The queue had two claim-query implementations: a single unified query, and a pair of separate ordered/unordered
queries selected by `useOrderedUnorderedQuery`. Since 0.50.0 the flag defaulted to `true` in every construction
route, so effectively every deployment already ran the ordered/unordered pair. The unified query and the flag
that selected it are now gone, and the ordered/unordered pair is the only per-queue claim path.

**Removed:**

| Element | Where |
|---|---|
| `PostgresqlDurableQueuesBuilder.setUseOrderedUnorderedQuery(boolean)` | `postgresql-queue` |
| `PostgresqlDurableQueues.isUseOrderedUnorderedQuery()` | `postgresql-queue` |
| the `useOrderedUnorderedQuery` constructor parameter, from both constructors that declared it | `postgresql-queue` |
| `DurableQueuesSql.buildGetNextMessageReadyForDeliverySqlStatement(Collection<String>)` | `postgresql-queue` |
| `essentials.durable-queues.use-ordered-unordered-query` | `spring-boot-starter-postgresql` |

**What to do:** delete the builder call, the constructor argument or the property. There is no replacement,
because there is no longer a choice to express. If you were setting it to `true` — the default — behaviour is
unchanged. If you were setting it to `false`, you now get the ordered/unordered pair; it was measured 5.4×
faster on the unified query's own workload (`docs/RELEASE-NOTES-0.50.0.md` §1.1.3).

The two constructors that took the flag change arity, so a positional call site will not compile:

```java
// Before
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener,
                            optimizerFactory, transactionalMode, messageHandlingTimeout,
                            useCentralizedMessageFetcher, pollingInterval, centralizedOptimizerFactory,
                            useOrderedUnorderedQuery);

// After
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener,
                            optimizerFactory, transactionalMode, messageHandlingTimeout,
                            useCentralizedMessageFetcher, pollingInterval, centralizedOptimizerFactory);
```

Both constructors remain `@Deprecated(forRemoval = true)`; prefer `PostgresqlDurableQueues.builder()`.

### Two indexes are dropped on startup

`idx_<table>_next_msg` and `idx_<table>_ready` existed only to serve the removed unified query.
`PostgresqlDurableQueues` now issues `DROP INDEX IF EXISTS` for both during table initialisation, alongside the
older index drops it already performed, so an upgraded deployment stops paying write amplification for indexes
nothing reads.

⚠️ **Index drop and recreate on startup is not zero-downtime safe** across a version where index names change.
On a large queue table, plan the upgrade as you would any other index change: the drops run inside the
bootstrap transaction while the framework holds its advisory lock, so a concurrently starting instance waits.

A third, `idx_<table>_ordered_ready`, is dropped too — see below. The three that remain are
`idx_<table>_ordered_msg`, `idx_<table>_unordered_ready` and `idx_<table>_ordered_head`.

### `idx_<table>_ordered_ready` is dropped as well

Not because the unified query went, but because it was measured taking no scans at all.
`QueueIndexScanCountIT` drives three workload shapes — mixed ordered/unordered, one distinct key per message
(the shape most favourable to a key-leading index), and 200 ordered claims against a 20 000-row `ANALYZE`d
table — and the index records zero scans in every one, while the planner picks each of the other three. The
ordered claim's `NOT EXISTS` barrier is served by `idx_<table>_ordered_msg`.

It is dropped on startup alongside the other superseded indexes, and no longer created. Nothing to do; the
same zero-downtime caveat above applies.

### The queue statistics feature is removed

The delivery-statistics feature — a trigger on the queue table that wrote a row per acknowledged message into a
separate statistics table — is removed entirely, along with its SPI, its DTOs, its admin API operation and its
configuration properties. A replacement modelled on the event store's `SubscriptionStatisticsRegistry` follows
separately.

**Removed:**

| Element | Where |
|---|---|
| `PostgresqlDurableQueuesStatistics` | `postgresql-queue` |
| `DurableQueuesStatistics`, `NoOpDurableQueuesStatistics` | `foundation` (`…messaging.queue.stats`, package removed) |
| `QueueStatistics`, `QueuedStatisticsMessage`, `DefaultQueuedStatisticsMessage`, `DefaultQueuedStatisticsMessageBuilder` | `foundation` (`…messaging.queue.stats`, package removed) |
| `ApiQueuedStatistics` | `foundation` (`…messaging.queue.api`) |
| `DurableQueuesApi.getQueuedStatistics(…)`, and the `DurableQueuesStatistics` constructor parameter of `DefaultDurableQueuesApi` | `foundation` (`…messaging.queue.api`) |
| `GET /durable-queues/queues/{queueName}/statistics` | admin API |
| `essentials.durable-queues.enable-queue-statistics` | `spring-boot-starter-postgresql` |
| `essentials.durable-queues.shared-queue-statistics-table-name` | `spring-boot-starter-postgresql` |
| `essentials.durable-queues.enable-queue-statistics-ttl` | `spring-boot-starter-postgresql` |
| `essentials.durable-queues.queue-statistics-ttl-duration` | `spring-boot-starter-postgresql` |
| the `durableQueuesStatistics` bean | `spring-boot-starter-postgresql` |

**What to do:** delete the properties and any reference to the removed types. A caller constructing
`DefaultDurableQueuesApi` directly replaces its fourth argument — the removed `DurableQueuesStatistics` — with a
`QueueStatisticsRegistry` (see the replacement below). The REST operation keeps its path and gains a different,
richer response body.

**The database objects are removed for you, once.** On its first startup after the upgrade
`PostgresqlDurableQueues` drops the `trg_log_message_delivery_stats` trigger from the queue table, the
`log_message_delivery_stats()` function, and the statistics table the function wrote to. The statistics table
name was configurable, so it is recovered from the trigger function's own body rather than assumed to be
`durable_queues_statistics`, and it is only dropped when its columns match the shape the feature created.

The whole removal is gated on the trigger still existing and the trigger is dropped first, so it runs exactly
once and later startups issue no statements. Two consequences worth knowing:

- A deployment that never enabled the feature has no trigger, so nothing happens.
- If the trigger was already removed by hand but the table was left behind, the table survives. Drop it
  yourself.

**Take a backup before upgrading** if the statistics data matters to you. Nothing exports it first.

### Dead-letter classification changes

Two rules that decide whether a failed message is retried or dead-lettered changed in the same release. Both
are behaviour changes for an application that configured nothing, so read this one even if you never touched
a `RedeliveryPolicy`.

#### `alwaysRetryOn(...)` now works

`MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)` previously had no
effect: the handler answered "not permanent", the consumer OR-ed its built-in permanent-error list on top, and
the message was dead-lettered on its first delivery anyway. The API offered a knob that could not move.

`MessageDeliveryErrorHandler` gains a three-valued `verdict(...)` — `PERMANENT_ERROR`, `RETRY`, `NO_OPINION` —
as a `default` method mapping the existing `isPermanentError` onto `PERMANENT_ERROR` / `NO_OPINION`. **Every
existing implementation keeps compiling and behaving exactly as before**, because a handler that has not
considered `RETRY` means "no opinion" when it answers `false`, not "retry this".

Only the builder's product answers `RETRY`, and only for the types passed to `alwaysRetryOn(...)`. What that
now overrides:

| Built-in permanent type | Overridable by `alwaysRetryOn` |
|---|---|
| `DurableQueueDeserializationException` | No |
| `MismatchedInputException` | No |
| `NoClassDefFoundError` | No |
| `IllegalArgumentException` (incl. `NumberFormatException`) | **Yes** |
| `ClassCastException` | **Yes** |

The three that cannot be overridden can never succeed on a later attempt, and retrying one forever blocks the
head of an ordered queue.

`MessageDeliveryErrorHandler.alwaysRetry()` deliberately keeps meaning `NO_OPINION`. It is the builder's
default, so promoting it would make deserialization failures retry forever in every existing application.

**What to do:** if you worked around this by catching and rewrapping `IllegalArgumentException` inside your
handlers, you can drop the workaround and list the type in `alwaysRetryOn(...)` instead. If you relied on a
message being dead-lettered despite listing its type in `alwaysRetryOn(...)`, it will now be retried.

#### The whole cause chain is examined

Classification used to test the thrown exception and the deepest root cause, and nothing between. A
`@MessageHandler` throw arrives wrapped (`UnitOfWorkException → ReflectionException →
InvocationTargetException → yours`), so your exception was normally the deepest one and decided the outcome —
unless it carried a cause of its own, at which point classification silently switched to that deeper type.
Two handlers differing only in whether they passed a cause got different dead-letter behaviour.

Every link is now examined, outermost match first.

**What to do:** a handler that wraps a built-in permanent type in the middle of a chain — for example throwing
`new ProcessingException("...", new IllegalArgumentException(...))` inside another cause — previously retried
and will now be dead-lettered. Re-check handlers that construct multi-level cause chains.

#### Telling the two apart

Because both rules changed at once, the dead-letter log line now names which rule fired, the type it matched,
how deep in the cause chain it was found, and where the message stood against its cap:

```
PERMANENT_ERROR (built-in permanent list matched IllegalArgumentException at cause-chain depth 3; attempt 1 of 6)
```

Jackson's `MismatchedInputException` is now matched by class name rather than `instanceof`, so it is
recognised under Jackson 3, where it previously matched nothing because the class moved to
`tools.jackson.databind.exc`.


### Queue statistics are replaced, not restored

The same path serves a different body:

```
GET /durable-queues/queues/{queueName}/statistics   ->  ApiQueueStatistics
```

The old `ApiQueuedStatistics` reported `totalMessagesDelivered`, `avgDeliveryLatencyMs`, `firstDelivery` and
`lastDelivery`, all read from the statistics table, all cluster-wide, and all wrong whenever a queue was purged
— the trigger counted a purge as a delivery.

`ApiQueueStatistics` splits into two halves that are **not** interchangeable:

| Half | Source | Scope | Fields |
|---|---|---|---|
| `depth` | the queue table, one statement | cluster-wide | `queuedMessages`, `deadLetterMessages`, `messagesBeingDelivered`, `oldestReadyMessageAgeMillis` |
| `instance` | in-memory registry | **this JVM only**, resets on restart | `messagesHandled`, `messagesRetried`, `messagesDeadLettered`, `redeliveryRequests`, `averageHandlerDurationMillis`, `maxHandlerDurationMillis`, `lastHandledAt`, `lastFailureAt`, `lastFailureReason` |

`instance` is absent when this instance has delivered nothing from the queue. That is not the same as the queue
being idle — another instance may be draining it. `depth.messagesBeingDelivered` and
`depth.oldestReadyMessageAgeMillis` are what distinguish the two, and they are the reason the halves are joined
rather than the registry being exposed alone.

**Field mapping, for anyone who consumed the old body:**

| Old | New | Note |
|---|---|---|
| `totalMessagesDelivered` | `instance.messagesHandled` | now per-instance, and a purge no longer inflates it |
| `avgDeliveryLatencyMs` | `instance.averageHandlerDurationMillis` | **a different quantity** — handler duration, not time since enqueue |
| `firstDelivery` | `instance.statisticsSince` | when this instance started counting |
| `lastDelivery` | `instance.lastHandledAt` | |

Durations cross the contract as milliseconds, matching `ApiSubscriptionStatistics`.

**Collection is on by default and costs nothing durable.** The Spring Boot starter registers a
`QueueStatisticsRegistry` and a `StatisticsCollectingDurableQueueMessageObserver`, and hands the observer to the
queue. There is no table, no trigger, no TTL job and no property to enable — which is the point: the feature
this replaces was off by default because it wrote a row per acknowledged message inside the queue's own
transaction.

To collect nothing, define your own `QueueStatisticsRegistry` bean and no observer bean; to add your own
observer alongside, just declare it as a bean — the starter composes every `DurableQueueMessageObserver` it
finds.

### `QueuedMessageCounts` gains two components

`QueuedMessageCounts` is now
`(queueName, numberOfQueuedMessages, numberOfQueuedDeadLetterMessages, numberOfMessagesBeingDelivered,
oldestReadyMessageTimestamp)`.

The two additions are what make a depth reading actionable. Both PostgreSQL and MongoDB populate them.

`numberOfMessagesBeingDelivered` is a nullable `Long`. `null` means the implementation **cannot count in-flight
messages cluster-wide** — an engine whose consumers track deliveries in memory rather than in the queue storage — and
never means zero. With it unknown, `oldestReadyMessageTimestamp` still says how long work has waited, but not whether
anyone is working on it, so the "large age and nothing in flight means stalled" reading does not apply.

**What to do:** a caller that only reads the record is unaffected. A caller that constructs one, or compares one
by equality, must be updated — `oldestReadyMessageTimestamp` is data from the queue, so an equality comparison
against a hand-built expected value is no longer a good way to assert on counts. Assert on the components you
care about instead.


### A dead-letter counter you can alert on

`essentials.messaging.durable_queues.dead_lettered` is incremented once per dead letter, tagged:

| Tag | Values |
|---|---|
| `queue_name` | the queue |
| `message_payload_type` | the payload's type name |
| `reason` | `permanent_error` or `redeliveries_exhausted` |

Before 0.60 a dead letter produced one `log.error`, a row in the dead-letter table, and nothing else — no
failed test, no failing request, no health change. The only metric was
`essentials.messaging.durable_queues.mark_as_dead_letter_message`, a timer measuring how long the *marking*
took, carrying no reason and gated behind `essentials.metrics.durable-queues.enabled`.

The new counter is registered whenever a `MeterRegistry` bean is present and is deliberately **not** behind
that property: it controls execution-time measurement, and a timing switch must not turn an incident counter
off.

**What to do:** alert on this counter. Nothing needs configuring to get it.

Both Spring Boot starters register it, MongoDB included. Nothing about the counter is database-specific —
`MongoDurableQueues` delivers through the same `DefaultDurableQueueConsumer` that notifies the observer. The
Mongo builder gained `setMessageObserver(...)` for this, matching `PostgresqlDurableQueuesBuilder`, and the
Mongo starter's `durableQueues` bean now collects every `DurableQueueMessageObserver` bean via
`composite(...)`. Existing `MongoDurableQueues.builder()` callers are unaffected; the setter defaults to
`DurableQueueMessageObserver.none()`.

### A dead-letter health indicator, which cannot fail a probe unless you ask it to

Both Spring Boot starters now register a `DurableQueuesHealthIndicator`, which reports dead-letter
counts under `durableQueues` on `/actuator/health`:

```json
{
  "status": "UP",
  "details": {
    "deadLetterThreshold": 0,
    "totalDeadLetterMessages": 3,
    "deadLetterMessagesPerQueue": { "OrdersQueue": 3 },
    "queuesAtOrAboveThreshold": []
  }
}
```

**It reports `UP` no matter how many dead letters there are, until you opt in.** This is the important part.
A `HealthIndicator` is not only a signal — it contributes to the composite `/actuator/health` status, and
plenty of deployments point a Kubernetes readiness or liveness probe straight at that endpoint. An indicator
that went `DOWN` on the first dead letter would take working pods out of service, or restart them, because one
message could not be handled — leaving fewer consumers to drain the queue behind it.

To make it able to report `DOWN`, set a threshold:

```properties
# A single queue reaching 100 dead letters means this instance should stop taking traffic
essentials.durable-queues.health.dead-letter-threshold=100
```

The threshold applies **per queue**, not to the total, so the number you choose does not quietly mean
something different in an application with more queues. `queuesAtOrAboveThreshold` names the queues that
caused a `DOWN`.

This follows the same rule as `CdcHealthIndicator`, which reports `DOWN` for a failed CDC subscription only
when the operator declared CDC mandatory with `CdcMode.REQUIRE`.

Two further settings:

| Property | Default | Meaning |
|---|---|---|
| `essentials.durable-queues.health.dead-letter-threshold` | `0` | Per-queue dead-letter count at which the status becomes `DOWN`. `0` means never |
| `essentials.durable-queues.health.cache-time-to-live` | `10s` | How long a computed result is reused before the counts are read again |
| `management.health.durable-queues.enabled` | `true` | Set to `false` to not register the indicator at all |

The cache exists because computing the answer costs one query for the queue names plus one count per queue.
Probes poll on a timer from every instance, so without it the added database load would scale with probe
frequency for a number that barely changes between probes.

If the counts cannot be read — an unreachable database, say — the indicator reports `UNKNOWN` rather than
`DOWN`. That is not a statement about dead letters, and Spring Boot's own `DataSource` indicator already
reports it; `UNKNOWN` does not drag the aggregated status down on its own.

**What to do:** nothing, to get the visibility. Set a threshold only if a queue reaching a given dead-letter
count really does mean the instance should stop receiving traffic. To alert on dead letters without touching
any probe, use the Micrometer counter above instead.

### The 0.40.x `forRemoval` constructors are gone from the queue modules

Every constructor marked `@Deprecated(forRemoval = true, since = "0.40.x")` in `foundation`'s messaging
packages, `postgresql-queue` and `springdata-mongo-queue` has been removed from the public API. Each already
had a replacement named in its own `@deprecated` tag, and behaviour is unchanged.

| Type | Replacement |
|---|---|
| `RedeliveryPolicy` (6-arg) | `RedeliveryPolicy.builder()` and the `fixedBackoff` / `linearBackoff` / `exponentialBackoff` factories |
| `DefaultQueuedMessage` (11-arg) | `DefaultQueuedMessage.builder()` |
| `ConsumeFromQueue` (3 overloads) | `ConsumeFromQueue.builder()` |
| `QueueMessage`, `QueueMessages` (2 overloads each) | `QueueMessage.builder()` / `QueueMessages.builder()` |
| `DefaultDurableQueueConsumer`, `PostgresqlDurableQueueConsumer`, `MongoDurableQueueConsumer` (7-arg) | the `(ConsumeFromQueue, DurableQueueConsumerDependencies)` constructor — see `DurableQueueConsumerDependencies.builder()` |
| `PostgresqlDurableQueues` (2 wide overloads) | `PostgresqlDurableQueues.builder()` |
| `MongoDurableQueues` (9 overloads) | `MongoDurableQueues.builder()` |

Two constructors survive for the all-defaults case, as their deprecation notes promised:
`PostgresqlDurableQueues(unitOfWorkFactory, …)`'s short forms and
`MongoDurableQueues(mongoTemplate, messageHandlingTimeout)`.

The constructor each builder delegates to is still there, demoted to package-private — it is an implementation
detail now, not API.

**What to do:** switch to the builder named above. A builder call names every argument, which is the point:
the widest removed constructor took 14 positional parameters, four of them adjacent `boolean`s.

#### `QueueMessage.builder().setMessage(…)` no longer discards ordering

Worth calling out on its own because it was a silent bug, not just a deprecation.
`QueueMessageBuilder.setMessage(message)` used to split the message into its payload and metadata and rebuild
a plain `Message` in `build()`. Handed an `OrderedMessage`, it dropped the key and the order — so the message
was queued as unordered, with no error anywhere, and ordering guarantees quietly did not apply. The builder
now carries the message through as given.

If you used `QueueMessage.builder().setMessage(anOrderedMessage)`, your messages were being delivered
unordered. They now respect their ordering, which may change the order your handlers observe.

#### `QueueMessagesBuilder.setMessages` accepts `List<? extends Message>`

Widened from `List<Message>`, so a `List<OrderedMessage>` no longer needs a copy or a cast at the call site.

### `TransactionalMode` is retired

`FullyTransactional` was documented as broken for retries and dead-lettering: the queue operations joined the
caller's transaction, so a rollback reverted the delivery-attempt count and the `RedeliveryPolicy` never
advanced. It is a mode that cannot do the thing the queue exists for.

Removing it leaves an enum with one constant — a type whose only job was to express a choice that no longer
exists — so the type goes too.

**Removed:**

| Element | Where |
|---|---|
| `TransactionalMode` | `foundation` (`…messaging.queue`) |
| `DurableQueues.getTransactionalMode()` | `foundation` |
| the `transactionalMode` constructor parameter of `PostgresqlDurableQueues` and `MongoDurableQueues` | both queue modules |
| `PostgresqlDurableQueuesBuilder.setTransactionalMode(…)`, `MongoDurableQueues.Builder.setTransactionalMode(…)` | both queue modules |
| `essentials.durable-queues.transactional-mode` | `spring-boot-starter-postgresql`, `spring-boot-starter-mongodb` |

**What this means at runtime.** Every deployment now behaves as `SingleOperationTransaction` did: queueing and
dequeueing are separate transactions, acknowledging and retrying are their own, and
`SingleOperationTransactionDurableQueuesInterceptor` is wired unconditionally rather than only in that mode.

**What to do:**

- Delete the property or the builder call. If you had it set to `single-operation-transaction` — the default
  since 0.50 in both starters — nothing changes.
- **If you had it set to `fully-transactional`, your delivery semantics change.** Two consequences worth
  planning for:
  - A `UnitOfWork.markAsRollbackOnly()` inside a message handler — directly, or through
    `UnitOfWorkControllingCommandBusInterceptor` — no longer rolls the message handling back. The
    `RedeliveryPolicy` now applies as written, which is the behaviour that mode was preventing.
  - Queueing a message no longer requires an enclosing `UnitOfWork`, and no longer joins one for the
    queue-storage write. A handler that relied on "the entity change and the enqueue commit or roll back
    together" loses that guarantee. If you need it, keep the enqueue in the same transaction yourself by
    calling `queueMessage` inside your own `UnitOfWork` — the operation still joins an in-progress one.
- `DurableQueues.getUnitOfWorkFactory()` still exists and still returns the factory when the implementation has
  one; only its "…if the mode is FullyTransactional" contract is gone.

---

## `types-springdata-jpa`: exact `numeric` converters for `Amount` and `Percentage` (opt-in)

Nothing changes unless you opt in. It is listed here because opting in is a **database schema** change.

`AmountAttributeConverter` and `PercentageAttributeConverter` map to a `double precision` column, because their shared
base class, `BaseBigDecimalTypeAttributeConverter`, implements `AttributeConverter<T, Double>`. That is lossy twice
over, and silently so:

- **The scale of the value written is lost.** `Amount.of("1999.50")` reads back as `1999.5`. Numerically equal, but
  `BigDecimal.equals` is scale-sensitive, so an assertion, a cache key or a `Map` lookup against the value that was
  written fails.
- **SQL arithmetic is floating point.** `sum`, `avg` and every comparison on the column are IEEE-754 operations. Sums
  over many rows drift, and a value beyond roughly 15–17 significant digits cannot be represented at all.

### Opting in

`BaseBigDecimalTypeNumericAttributeConverter<T>` implements `AttributeConverter<T, BigDecimal>`, which Hibernate maps to
an exact `numeric` column. Two concrete converters ship with it — `AmountNumericAttributeConverter` and
`PercentageNumericAttributeConverter`. **Neither is `autoApply`**, so upgrading changes nothing: your columns
keep their current type until you opt in per field.

```java
@Convert(converter = AmountNumericAttributeConverter.class)
@Column(precision = 19, scale = 2)
public Amount totalPrice;
```

An explicit `@Convert` takes precedence over an auto-applied converter, so this field is `numeric` even though
`AmountAttributeConverter` is on the classpath. The converter imposes no precision or scale of its own — a framework
converter cannot know your domain's scale — so declare `@Column` yourself; without it a Hibernate-generated schema gets
`numeric(38,2)`, which rounds to two decimals.

### What opting in does to an existing schema

It changes the generated column type. An application on `hibernate.ddl-auto=validate` fails at startup until the column
is migrated:

```sql
alter table <table> alter column <col> type numeric(19,2) using <col>::numeric(19,2);
```

**The cast is exact for values that fit, but it does not repair history.** A figure that floating-point accumulation has
already corrupted, or precision a `double` never had room to hold, is gone — the migration preserves what is in the
column, nothing more. If that matters for your data, reconcile against the source of truth before migrating, not after.

### `numeric` returns values at the column's scale

Exact is not the same as unchanged. A `numeric(p,s)` column stores every value at scale `s`: it **rounds** a value with
more decimals and **pads** one with fewer. With `scale = 2`, `123.456` reads back as `123.46` and `100.5` as `100.50`.
Both are exact, but neither is `equals` to what was written, because `Amount`/`Percentage` equality is scale-sensitive.
Give the column the scale your domain writes, or use PostgreSQL's unconstrained `numeric`
(`@Column(columnDefinition = "numeric")`) when values of different scales must round-trip unchanged.

### Why `double precision` stays the default

Making `numeric` the auto-applied default was considered for 0.60 and rejected. Every `Amount`/`Percentage` field
without an explicit `@Column(scale = …)` would get a Hibernate-generated `numeric(38,2)` column, which silently rounds
percentages and three-decimal currencies — a quieter and worse loss than the one it replaces. A later major may revisit
this if the unspecified case can be made exact.

Note that `types-springdata-jpa` is **EXPERIMENTAL** and may be discontinued; `types-jdbi` remains the recommended
module for SQL persistence.
