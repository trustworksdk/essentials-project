# Migration guide — 0.60

0.60 is a breaking release across several modules. This guide collects what a consumer has to change, one entry
per change. Entries are added as the work lands, so this document grows through the release.

For the 0.50 deprecations whose removal this release carries out, see
[MIGRATION-NEXT_MAJOR.md](./MIGRATION-NEXT_MAJOR.md).

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

The four remaining indexes (`idx_<table>_ordered_msg`, `idx_<table>_ordered_ready`,
`idx_<table>_unordered_ready`, `idx_<table>_ordered_head`) are unchanged.

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
recognised under both Jackson 2 and Jackson 3. Under Jackson 3 it previously matched nothing, because the
class moved to `tools.jackson.databind.exc`.


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

**What to do:** a caller that only reads the record is unaffected. A caller that constructs one, or compares one
by equality, must be updated — `oldestReadyMessageTimestamp` is data from the queue, so an equality comparison
against a hand-built expected value is no longer a good way to assert on counts. Assert on the components you
care about instead.
