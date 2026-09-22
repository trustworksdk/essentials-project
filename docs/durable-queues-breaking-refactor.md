# Durable Queues — breaking refactor and cleanup plan for 0.60

Four pieces of work:

1. **Remove the legacy claim query.** Only the ordered/unordered split path remains, with the cleanup that follows
   from having one query instead of two.
2. **Remove the current queue statistics entirely** — the trigger, the table, the TTL job, the properties, the SPI.
3. **Replace them** with an in-memory registry modelled on the event store's `SubscriptionStatisticsRegistry`.
4. **Land the dead-letter handling items** — D1, D1b, D2, D3, D4 in §4. The source document they came from
   is reproduced in full as [Appendix A](#appendix-a--dead-letter-handling-improvementsmd).

Plus the cleanup that these four make possible but do not themselves require — §5.

**Target: 0.60.** That release is breaking across many modules, so the queue's breaking changes travel with a
wider migration effort rather than being a surprise on their own. Every item below therefore carries a
migration-guide obligation, and §7 says what that means concretely.

**On evidence.** Every claim in this document is sourced from the code on `main` or from
`docs/RELEASE-NOTES-0.50.0.md`, and was read rather than assumed. Where a removal would benefit from a
measurement that does not exist, the document says so instead of quoting a number.

---

## 1. Q1 — Remove the legacy claim path

`buildGetNextMessageReadyForDeliverySqlStatement` is the unified claim query, selected over the
ordered/unordered pair by `useOrderedUnorderedQuery` at four branch points in `PostgresqlDurableQueues`
(`:1307`, `:1355`, `:1646`, `:1669`). The flag is `true` in all three construction routes —
`DEFAULT_USE_ORDERED_UNORDERED_QUERY` (`PostgresqlDurableQueues.java:99`), the builder since 0.50.0, and
`EssentialsComponentsProperties:300` — and nothing sets it to `false` except four benchmark ITs.

### Delete

- `DurableQueuesSql.buildGetNextMessageReadyForDeliverySqlStatement(Collection<String>)`
- the `useOrderedUnorderedQuery` field and its getter `isUseOrderedUnorderedQuery()`
- the private two-arg overloads `getNextMessageReadyForDelivery(operation, boolean)` and
  `fetchNextBatchOfMessages(…, boolean)`, and the four `if (useOrderedUnorderedQuery)` branches
- `PostgresqlDurableQueuesBuilder.setUseOrderedUnorderedQuery(boolean)` and the constructor parameter
- `EssentialsComponentsProperties.DurableQueues.{is,set}UseOrderedUnorderedQuery`
- the benchmark ITs that exist only to contrast the two paths — the
  `*PerformanceIT` / `*PerformanceIT_WithOrderedUnorderedQuery` pairs collapse to one each

### The indexes

Six indexes are created unconditionally on the queue table at `PostgresqlDurableQueues:535-545`. Two of them exist
to serve the query being deleted:

| Index | Built by | Serves |
|---|---|---|
| `idx_<table>_next_msg` `(queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts)` | `getCreateNextMessageIndexSql()` | the unified query |
| `idx_<table>_ready` — partial, `(queue_name, next_delivery_ts, key, key_order)` | `getCreateNextReadyMessageIndexSql()` | the unified query |
| `idx_<table>_ordered_msg` `(queue_name, key, key_order)` | `getCreateOrderedMessageIndexSql()` | the barrier's `NOT EXISTS` |
| `idx_<table>_ordered_ready` `(key, queue_name, key_order, next_delivery_ts) INCLUDE (id)` | `getCreateOrderedMessageReadyIndexSql()` | the ordered claim |
| `idx_<table>_unordered_ready` `(queue_name, next_delivery_ts) INCLUDE (id)` | `getCreateUnorderedMessageReadyIndexSql()` | the unordered claim |
| `idx_<table>_ordered_head` `(queue_name, key_order, next_delivery_ts) INCLUDE (id)` | `getCreateOrderedMessageHeadIndexSql()` | the ordered claim |

Drop the first two with their builder methods, and add a `DROP INDEX IF EXISTS` for each alongside the existing
legacy-index drops at `:526-533`, so an upgraded deployment reclaims them instead of paying write amplification
for a query that no longer exists.

**The remaining four want a measurement, not an argument.** `idx_<table>_ordered_msg` and
`idx_<table>_ordered_ready` both plausibly serve the same `NOT EXISTS` barrier, and removing the unified query
changes which statements the survivors have to serve. Check `pg_stat_user_indexes` scan counts under the ordered
and unordered ITs after the deletion lands, and remove what takes zero scans. Do not pre-judge it here.

### Keep `buildBatchedSqlStatement`

It is a third claim shape, but not the *old* one — it is the centralized fetcher's multi-queue claim, opt-in
behind `useBatchedFetch` (default `false`), covered by `BatchedFetchStrategyIT`. "Only ordered/unordered remains"
is about the per-queue claim; this is a different axis and out of this item's scope. Its javadoc calls it "work in
progress (doesn't handle competing consumers yet)", which is stale relative to its own IT — correct the comment or
make it specific about what is actually unfinished.

**Effort:** small, almost entirely deletion. The risk lives in the index drops, not the Java.

---

## 2. Q2 — Remove the current queue statistics

Delete outright, no deprecation cycle. Q3 replaces it.

### Delete

| Element | Module |
|---|---|
| `PostgresqlDurableQueuesStatistics` — class, `@TTLJob`, trigger DDL, table DDL | `postgresql-queue` |
| `DurableQueuesStatistics` (the SPI), `NoOpDurableQueuesStatistics` | `foundation` |
| `QueueStatistics`, `QueuedStatisticsMessage`, `DefaultQueuedStatisticsMessage`, `DefaultQueuedStatisticsMessageBuilder` | `foundation` |
| `ApiQueuedStatistics` — whole class | `foundation` (`…queue.api`) |
| `DurableQueuesApi.getQueuedStatistics(…)` — the method only | `foundation` (`…queue.api`) |
| `DefaultDurableQueuesApi` — the `getQueuedStatistics` override (`:151`), the `durableQueuesStatistics` field (`:51`) and its constructor parameter (`:56`). The class and its other twelve operations stay | `foundation` (`…queue.api`) |
| `EssentialsAdminApiSpec` — the `getQueuedStatistics` operation (`:303-308`) and the `ApiQueuedStatistics` entries in the schema and sort-field tables (`:76`, `:101`) | `admin-api-spec` |
| `GET /durable-queues/queues/{queueName}/statistics` in `DurableQueuesController` (`:130-136`) | `spring-boot-starter-admin-api` |
| the path and `ApiQueuedStatistics` schema in `openapi/essentials-admin-api.yaml`; the queue-statistics panel in `docs/openapi/admin-ui-mockup/index.html` | `admin-api-spec`, `docs` |
| `enableQueueStatistics`, `enableQueueStatisticsTtl`, `queueStatisticsTtlDuration`, `sharedQueueStatisticsTableName` | `spring-boot-starter-postgresql` |
| the `durableQueuesStatistics` bean (`EssentialsComponentsConfiguration:316-327`) | `spring-boot-starter-postgresql` |
| the statistics assertions in `PostgresqlDurableQueuesIT` and `StarterAutoConfigurationIT`; the properties in `examples/essentials-trading-demo` `application-compose.yml` | tests, examples |

### Also

Startup drops the trigger, the function and the table:

```sql
DROP TRIGGER IF EXISTS trg_log_message_delivery_stats ON <queueTable>;
DROP FUNCTION IF EXISTS log_message_delivery_stats();
DROP TABLE IF EXISTS durable_queues_statistics;
```

Add a failure-on-removed-properties check in the starter, listing every key removed by Q1, Q2 and §5.

---

## 3. Q3 — Queue statistics modelled on `SubscriptionStatisticsRegistry`

### 3.1 How the numbers get collected

The queue needs a notification point that knows how a delivery **ended**. A `DurableQueuesInterceptor` is the
wrong instrument: it sees the operation, not the outcome. `chain.proceed()` on `HandleQueuedMessage` covers the
handler invocation only — the acknowledgement, the dead-lettering and the retry all happen after it returns, and
the operations carrying those (`AcknowledgeMessageAsHandled`, `DeleteMessage`) carry nothing but a
`QueueEntryId`. An interceptor-based collector therefore has to keep a map of in-flight messages keyed by id, plus
a size cap, plus a sweep for entries whose acknowledgement never arrives — state that leaks by default.

Introduce **`DurableQueueMessageObserver`** in `foundation` instead, notified at the two places that hold the
`QueuedMessage` *and* know how its delivery ended — `CentralizedMessageFetcher` and `DefaultDurableQueueConsumer`.
Every consumer path funnels through those two, so `Inbox`, `Outbox` and `DurableLocalCommandBus` are covered with
no extra wiring.

```java
public interface DurableQueueMessageObserver {
    default void messageHandled(QueuedMessage message, Duration handlerDuration) {}
    default void messageRedeliveryRequested(QueuedMessage message) {}
    default void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {}
    default void messageDeadLettered(QueuedMessage message, Throwable cause) {}

    static DurableQueueMessageObserver none() { … }
    static DurableQueueMessageObserver composite(List<DurableQueueMessageObserver> observers) { … }
    static DurableQueueMessageObserver safe(DurableQueueMessageObserver observer) { … }
}
```

Reached through a `default` method `DurableQueues.getMessageObserver()` returning `none()`, so no constructor
grows a parameter and an implementation that does not care inherits the no-op.

Three properties of the contract, each for a reason:

- **It must never affect delivery.** The framework wraps whatever it is given in `safe(…)`, which swallows and
  logs once. These methods run on delivery threads, so an implementation must not block either.
- **`messageHandled` fires after the acknowledgement is issued**, so its count means "delivered and removed",
  not "the handler returned".
- **Not a single-slot SPI.** `composite(List)` lets the statistics registry and a Micrometer observer coexist
  without one decorating the other. `EventStoreSubscriptionObserver` is single-slot, which is why
  `StatisticsCollectingEventStoreSubscriptionObserver` has to be a delegating decorator — a constraint worth not
  repeating here.
- **Administrative operations are not deliveries.** `deleteMessage` and `purgeQueue` do not notify. Counting them
  is how the trigger being deleted in Q2 reported a 100 000-row purge as 100 000 delivered messages, each with a
  delivery latency measured to the moment of the purge.

### 3.2 The registry, shaped like the event store's

`SubscriptionStatisticsRegistry` is the model the admin surface already presents alongside this one, so a reader
should not have to learn two idioms. Match its contract deliberately:

| `SubscriptionStatisticsRegistry` | Queue equivalent |
|---|---|
| keyed `(SubscriberId, AggregateType)` | keyed `QueueName` |
| `DEFAULT_MAX_TRACKED_SUBSCRIPTIONS = 1000`, capacity warning logged once | `DEFAULT_MAX_TRACKED_QUEUES`, same behaviour |
| `(int maxTracked, Clock)` constructor | same — injected `Clock`, so tests are not timing-dependent |
| `findStatistics` / `allStatistics` / `remove` / `clear` / `trackedSubscriptions()` / `maxTrackedSubscriptions()` | same names, queue nouns |
| `LongAdder` + volatile writes, snapshot allocated only when read | same |
| immutable snapshot record with nested sub-records | same — below |
| javadoc stating **scope is this JVM only** | same, in the same words |

`SubscriptionStatistics` splits into `Lifecycle` / `EventHandling` / `Polling` / `Lock` / `Reset`. The queue
equivalent:

```java
public record QueueStatistics(QueueName queueName,
                              Instant statisticsSince,
                              Delivery delivery,     // handled, avg + max handler duration, lastHandledAt
                              Outcomes outcomes,     // retried, redeliveryRequested, deadLettered,
                                                     //   lastFailureAt, lastFailureReason (rendered text only)
                              Depth depth) { … }     // §3.3
```

Never retain the `Throwable` — only its rendered type and message, as `SubscriptionStatistics.EventHandling`
does.

This replaces today's `QueueStatistics(queueName, fromTimestamp, totalMessagesDelivered, avgDeliveryLatencyMs,
firstDelivery, lastDelivery)`. Deliberately a different record rather than a reshaped one: `avgDeliveryLatencyMs`
measured from *enqueue* is a different quantity from handler duration, and keeping the name while changing the
meaning is worse than changing the name.

### 3.3 The persistent half

The registry is per-JVM and resets on restart. The queue table is cluster-wide and durable. Both are needed to
answer "is this queue healthy", and the failure to avoid is presenting them as one number.

**Join them at the API layer; add no table.** `DefaultDurableQueuesApi` composes:

- **per-instance, from the registry** — handled, retried, dead-lettered, redelivery-requested, average and max
  handler duration, last failure, `statisticsSince`;
- **cluster-wide, from the queue table** — `numberOfQueuedMessages` and `numberOfQueuedDeadLetterMessages` via
  the existing `getQueuedMessageCountsFor` / `QueuedMessageCounts`, one statement, plus two additions worth their
  cost: **oldest ready message age** and **in-flight count** (`is_being_delivered = TRUE`).

Those last two are what turn "0 handled on this instance" from ambiguous into either "nothing to do" or
"stalled", and they are the whole argument for joining rather than shipping the registry alone.

Mark which half is which in the DTO, the way `ApiSubscription.runningInThisInstance` does for subscriptions, and
say it in the admin UI too. Per-instance figures under a cluster-wide heading is how an operator concludes a queue
is stalled while three other pods drain it.

**No durable rollup table.** If cross-instance throughput *history* is ever wanted, the shape is a batched
asynchronous writer fed by the same observer — never a trigger. It is not built now: it would cost a table, a
writer, a TTL job and an aggregation query, and commit to a row format, one release after deleting a table that
existed to answer the same question and went unused. Because the observer exists, adding it later is purely
additive, which is precisely why deferring costs nothing.

### 3.4 Wiring

The starter's statistics bean yields the in-memory implementation and `durableQueues` takes it as a dependency, so
the queue is handed the observer. **The dependency direction reverses**, and that is the point: statistics no
longer receive the queue and then run `CREATE TRIGGER` on its table. Enabling them becomes a configuration change
rather than a schema migration.

---

## 4. Dead-letter handling — D1, D1b, D2, D3, D4

From [Appendix A](#appendix-a--dead-letter-handling-improvementsmd), with the decisions in §8
applied. That document argued its items were additive, which was right for a patch release; in 0.60 that is not a
constraint, which is what makes D1b available.

### D1 — `MessageDeliveryVerdict`

`MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)` cannot win today. The
consumer ORs a built-in permanent list containing `IllegalArgumentException` *after* consulting the policy, so the
message is dead-lettered on first delivery anyway. Since `FailFast.requireNonNull` / `requireTrue` throw
`IllegalArgumentException` — 2000+ call sites here — and Kotlin's `require(…)` does too, the most common
validation idiom in the codebase dead-letters a message on first delivery, and the documented way to opt out does
not work. `false` currently means both "I have no opinion" and "I say retry", and the consumer cannot tell them
apart.

```java
public enum MessageDeliveryVerdict { PERMANENT_ERROR, RETRY, NO_OPINION }
```

with a **`default`** method on `MessageDeliveryErrorHandler` mapping `isPermanentError`'s `true`/`false` onto
`PERMANENT_ERROR`/`NO_OPINION`. Existing implementations keep compiling and behaving identically, and that mapping
is the right answer for one that has not considered `RETRY`. Only the builder's product overrides it, and only for
the explicit `alwaysRetryOn(…)` list.

| Type | Overridable by `RETRY` | Why |
|---|---|---|
| `DurableQueueDeserializationException`, `MismatchedInputException` | No | The stored bytes will not parse on the hundredth attempt either |
| `NoClassDefFoundError` | No | A missing class is a deployment fault, not a transient one |
| `IllegalArgumentException` (incl. `NumberFormatException`) | Yes | The house guard idiom, frequently thrown about data that may be valid later |
| `ClassCastException` | Yes | Usually a genuine bug, but a cast against a projection that has not caught up is legitimately transient, and an explicit `alwaysRetryOn` is a deliberate statement |

`alwaysRetry()` keeps meaning `NO_OPINION`. It is the builder default, so promoting it to `RETRY` would make
deserialization failures retry forever in every existing application.

### D1b — Examine the whole cause chain

Classification looks at the outermost exception and the deepest root cause, and nothing between. A
`@MessageHandler` throw arrives wrapped (`UnitOfWorkException → ReflectionException → InvocationTargetException →
yours`), so the handler's own exception is normally deepest and decides — unless the handler attaches a cause, at
which point classification silently flips to that deeper type. Two handlers differing only in whether they pass a
cause get different dead-letter behaviour.

**Ships with D1.** One migration, one round of re-checking handlers.

**Mandatory mitigation.** Two classification changes landing together means "my message stopped dead-lettering"
has two candidate causes in every bug report. D3 piece 2 therefore cannot be deferred past this: the log line must
name which rule fired (policy verdict or built-in list), the matched type, its depth in the cause chain, and the
attempt count.

### D2 — `alwaysRetryOn` and the attempt cap

The javadoc promises redelivery "no matter how many times"; the caller's
`attempts >= maximumNumberOfRedeliveries + 1` caps it regardless, even once D1 makes `RETRY` win.

**Keep the cap, fix the documentation.** `alwaysRetryOn` means "never *classified* permanent", nothing more.
Honouring the literal promise was available in a breaking release and was still rejected: a permanently-failing
message at the head of an ordered queue blocks everything behind it, and that does not become less true because
the release allows breaking changes.

Work: correct the `alwaysRetryOn` javadoc and its entry in `LLM/LLM-foundation.md`. `retryIndefinitelyOn(…)` is
**not** added now — it goes in when someone has the use case, and it needs the escalating `WARN` (every 10th
attempt) and the D3 counter shipped with it, or a stuck message is silent.

### D3 — A dead letter is invisible

Nothing is waiting on a message handler. The HTTP request that produced the event committed long ago, the handler
runs on a subscription thread, and the subscription's resume point moves past the failure. A dead letter produces
one `log.error`, a row in the dead-letter table, and no other signal — no failed test, no failing request, no
health change. What exists is a *timer*, `essentials.messaging.durable_queues.mark_as_dead_letter_message`, which
measures how long the marking took, is gated behind `essentials.metrics.durable-queues.enabled`, and carries no
reason.

**Piece 1 — the counter.** A `MicrometerDurableQueueMessageObserver`, composed alongside the registry via
`DurableQueueMessageObserver.composite(…)`. It already receives `messageDeadLettered(message, cause)` where the
reason is known, needs no interceptor and no consumer edit, and is independent of the execution-time toggle by
construction — a timing switch must not turn an incident counter off. Tags: `queue_name`,
`message_payload_type`, `reason` (`permanent_error` | `redeliveries_exhausted`).

**Piece 2 — the log line.** Specified by D1b above, and emitted from the classifier that made the decision rather
than from each consumer.

**Piece 3 — the health indicator: registered by default, but unable to report `DOWN` until asked.** *(Revised
during implementation; the original text read "off by default", and the reasoning below is why it changed.)*

The objection to shipping it on was never that dead letters should be invisible — it was that a
`HealthIndicator` is not only a signal. It contributes to the composite `/actuator/health` status, and
deployments routinely point a Kubernetes readiness or liveness probe at that endpoint, so one poison message
would cycle pods that are working correctly and remove the consumers that would drain the queue behind it.

That objection is answered by the status rule, not by the registration. `DurableQueuesHealthIndicator` is
registered by default and puts per-queue dead-letter counts in the payload, but reports `UP` regardless of
those counts until `essentials.durable-queues.health.dead-letter-threshold` is set to a positive number. It is
exactly the `CdcHealthIndicator` shape: that indicator reports `DOWN` for a failed CDC subscription only when
the operator declared CDC mandatory with `CdcMode.REQUIRE`. Visible to everyone; actuating only for those who
asked.

Three further choices, each of which could have gone the other way:

- **The threshold is per queue, not per total.** A total makes the number an operator picks mean something
  different in an application with more queues.
- **The result is cached** (`…health.cache-time-to-live`, default 10s). One query for the queue names plus one
  count per queue, multiplied by probe frequency and instance count, is real database load for a number that
  barely moves between probes.
- **A read failure is `UNKNOWN`, not `DOWN`.** An unreachable database is not a statement about dead letters
  and is already the `DataSource` indicator's subject; reporting `DOWN` would fail probes twice for one fault.

The Micrometer counter from piece 1 remains the thing to alert on: it cannot actuate anything, so it needs no
threshold and no opt-in.

### D4 — Document the interaction

Documentation only, and the best ratio in the plan — it removes the surprise even if nothing else ships. Nothing
currently tells a handler author that `FailFast.requireNonNull`, the idiom the whole repository uses, dead-letters
a message on first delivery inside a `@MessageHandler`.

- `LLM/LLM-foundation.md` — the built-in permanent list, that `IllegalArgumentException` is on it, that `FailFast`
  and Kotlin `require(…)` both raise it, and a "validating inside a handler" recipe: a retryable exception when the
  condition may become true later, `IllegalArgumentException` only when the message can never be processed.
- `LLM/LLM-postgresql-queue.md` — the same list beside the redelivery-policy documentation.
- `EventProcessor` / `ViewEventProcessor` javadoc — one paragraph, where handler authors read.
- `components/foundation/CLAUDE.md` — the contributor note, including the cause-chain subtlety.
- Root `CLAUDE.md` — one line in Critical Gotchas, because it crosses modules.

**Do it first.** It depends on nothing.

---

## 5. The rest of the cleanup

### 5.1 Retire `TransactionalMode`, not just `FullyTransactional`

`FullyTransactional` is documented as broken for retries and dead-lettering — rollback reverts the attempt count,
so the redelivery policy never advances. It is a mode that cannot do the thing the queue exists for, and 0.60 is
where it goes.

The enum has exactly two constants, so removing one leaves a type whose only job was to express a choice that no
longer exists. Retire the whole thing:

- delete the `TransactionalMode` enum and `DurableQueues.getTransactionalMode()`;
- simplify `DurableQueues.getUnitOfWorkFactory()`, whose javadoc currently describes what it returns "if
  `getTransactionalMode()` is `FullyTransactional`";
- delete the branch at `DurableLocalCommandBus:459` and the equivalents in both consumers and `MongoDurableQueues`;
- `SingleOperationTransactionDurableQueuesInterceptor` becomes unconditional wiring;
- strip the ~10 `TransactionalMode#FullyTransactional` javadoc paragraphs on the `DurableQueues` interface rather
  than leaving dangling `@link`s, which will not compile;
- remove the mode from the builders and from `EssentialsComponentsProperties`.

The largest deletion in the plan, touching every queue implementation. Sequence it after D1 — it rewrites the same
methods.

### 5.2 Remove the 0.40.x `forRemoval` surface

27 elements across `foundation/messaging`, `postgresql-queue` and `springdata-mongo-queue` carry
`@Deprecated(forRemoval = true, since = "0.40.x")`. The code-style rule says removal "is a separate decision at the
next major"; 0.60 is that decision. Remove them one commit per module, so the migration guide can list them
mechanically.

### 5.3 One classifier, called from both consumers

The delivery-failure classification is copied verbatim into two places: `isPermanentError` at
`CentralizedMessageFetcher:468` and in `DefaultDurableQueueConsumer`, and the caller's
`isPermanentError || attempts >= max + 1` condition with it (`CentralizedMessageFetcher:385`,
`DefaultDurableQueueConsumer:486`). D1 and D1b both change that logic, so without this they change it twice and
the copies are free to drift.

Extract a `MessageDeliveryClassifier` holding the verdict logic, the built-in list, the cause-chain walk and the
attempt-cap check, called by both. It is also the natural home for D3 piece 2's log line, since it is the code
that knows which rule fired. A prerequisite for D1, not a nice-to-have.

**Both consumers stay.** `DefaultDurableQueueConsumer` and `CentralizedMessageFetcherDurableQueueConsumer` are
siblings — both implement `DurableQueueConsumer` directly, neither is a legacy layer under the other — and
`DefaultDurableQueueConsumer` is the abstract base that `MongoDurableQueueConsumer` and
`PostgresqlDurableQueueConsumer` extend. Nothing here removes a consumer class.

---

## 6. Cross-cutting consequences

**The admin contract breaks, and a gate will say so.** Q2 deletes the `getQueuedStatistics` operation and the
`ApiQueuedStatistics` schema, and Q3 adds their replacements, so `OpenApiContractCompatibilityTest` fails against
`openapi/baseline/essentials-admin-api-v1.yaml`. 0.60 needs a **v2 baseline**, not a rebaselined v1. In order:

1. `DurableQueuesApi`, `EssentialsAdminApiSpec` and `DurableQueuesController` together — the root `CLAUDE.md` rule
   that an admin operation lives in three synced places, and `buildOpenApi()` fails on a stale or unmapped method.
2. Regenerate with `-Dopenapi.regenerate=true` **and `-am`** — without `-am` the spec reflects the *installed* SPI
   and regenerates the old contract.
3. Regenerate `admin-api-client-java` in the same change. No gate catches a stale client.
4. New baseline at release.
5. `spring-boot-starter-admin-ui` — `index.html` and `AdminUiController` both reference statistics.

**Migration guide obligations.** 0.60 is breaking well beyond queues, so these entries join a larger document
rather than standing alone. One entry each for: the removed `useOrderedUnorderedQuery` property and builder
setter; the two dropped indexes and the fact that index drop/recreate on startup is **not zero-downtime safe**
across a version where index names change; the retired `TransactionalMode`; the 27 removed `forRemoval` elements; and the classification changes from D1 + D1b, which is
the one behaviour change a reader is most likely to feel without having configured anything.

**Docs to update:** `LLM/LLM-postgresql-queue.md` (the `useOrderedUnorderedQuery` row at `:106`, the index SQL at
`:284-290`), `LLM/LLM-foundation.md`, `LLM/LLM-springdata-mongo-queue.md`,
`LLM/LLM-spring-boot-starter-modules.md`, `components/postgresql-queue/README.md` (`:123`),
`components/postgresql-queue/CLAUDE.md` (its `useOrderedUnorderedQuery` bullet says "Off by default", wrong since
0.50.0), and `components/spring-boot-starter-postgresql/{README.md,CLAUDE.md}`. Then `graphify update .`.

**Both Jackson flavors.** Payloads and `MessageMetaData` are persisted JSON, so anything touching serialization
runs under `mvn test` and `mvn -Pjackson2 test`. The new snapshot records are not persisted and are exempt; the
statistics table removal touches a `meta_data JSONB` column, so that path is worth one check rather than an
assumption.

**Other branches touch these files.** `queue_shard_owned`, `mssql_durable_queues` and
`feature/non-transactional-message-handler` all modify queue code, and the last one edits
`SingleOperationTransactionDurableQueuesInterceptor`, which §5.1 rewrites. Worth knowing before starting; not a
dependency.

---

## 7. Sequencing

| Step | Content | Constraint |
|---|---|---|
| 1 | **D4**, plus D2's javadoc correction — documentation only | Depends on nothing |
| 2 | **Q1** — legacy claim query, flag, two indexes, index drops | Independent |
| 3 | **§5.3** — extract the classifier | Prerequisite for step 4. No consumer class removed |
| 4 | **D1 + D1b + D3 piece 2** — one release, log line included | The log line is what keeps two simultaneous classification changes diagnosable |
| 5 | **Q2** — delete the statistics SPI, table DDL, properties, bean; ship the trigger + function drop | Independent of 1–4 |
| 6 | **Q3** — observer, registry, API join, new DTO | After 5 |
| 7 | **D3 piece 1** — `MicrometerDurableQueueMessageObserver` | After 6, same observer wiring |
| 8 | **§5.2** — the 27 `forRemoval` elements | After 2–7 stop moving |
| 9 | **§5.1** — retire `TransactionalMode` | After 4; rewrites the same methods |
| 10 | Index-scan measurement on the four surviving indexes; remove what takes zero scans | After 2 |
| 11 | OpenAPI v2 baseline, client regeneration, admin UI wording, migration guide | Release |
| 12 | **D3 piece 3** — health indicator, registered by default, `DOWN` only above an opt-in per-queue threshold | Done |
| — | **Deferred:** durable statistics sink, `retryIndefinitelyOn(…)` | Additive whenever wanted |

Steps 1, 2 and 5 are independent of each other.

---

## 8. Decisions

| # | Question | Decision |
|---|---|---|
| 1 | `TransactionalMode.FullyTransactional` — remove, or keep and document as broken? | **Remove**, and with only two constants in the enum, retire the type and every branch on it |
| 2 | `verdict(…)`: abstract, or `default` mapping the two-valued `isPermanentError`? | **`default`** — existing implementations keep compiling, and the mapping is right for one that has not considered `RETRY` |
| 3 | D2: honour the "no matter how many times" promise, or keep the cap? | **Keep the cap**, fix the docs. `retryIndefinitelyOn(…)` deferred |
| 4 | Is `ClassCastException` overridable by a `RETRY` verdict? | **Yes** |
| 5 | Examine the middle of the cause chain? | **Yes, with D1**, with the log line as mandatory mitigation |
| 6 | Does a dead letter affect health? | **Visible by default, actuating only on request.** The indicator is registered by default and reports the counts; it can only report `DOWN` once `essentials.durable-queues.health.dead-letter-threshold` is set positive, per queue. The Micrometer counter remains the alertable signal, because it cannot fail a probe |
| 7 | Build a durable statistics sink? | **Deferred, not rejected** — additive on the observer whenever wanted |
| 8 | Target release | **0.60**, alongside breaking changes in many other modules |

### Open

- ~~**Which of the four surviving indexes earn their place**~~ — **settled**. `QueueIndexScanCountIT` measured
  `idx_<table>_ordered_ready` at zero scans across every workload shape tried, including 200 ordered claims
  against a 20 000-row `ANALYZE`d table where the planner chose each of the other three. It is dropped; the
  `NOT EXISTS` barrier is served by `idx_<table>_ordered_msg`. Three indexes remain.
- **Whether `PostgresqlDurableQueues` keeps its `useCentralizedMessageFetcher` flag.** Consumer topology, a
  different question from the claim query, and outside this plan's brief. Noted only so its absence is not read as
  a decision.

---

## Appendix A — `dead-letter-handling-improvements.md`

The source document for §4, reproduced here because it lives on the `presentation` branch and not on
`main`. Its open questions are settled in §8; where the two disagree, §8 wins.

Scoped to how `DurableQueueConsumer` classifies a message-handling failure, and to how visible the
outcome is. Found while building `examples/essentials-webshop-demo`: an automation lost a race, its
message was dead-lettered, and the only trace was one `log.error` line — an order that was never charged
while `/actuator/health` stayed green.

Four items. D1 and D2 are API promises that do not currently hold, D3 is an observability gap, D4 is
documentation. They are independent and can land in any order; D4 is the cheapest and is worth doing
first regardless of what happens to the rest.

---

### The current behaviour, in full

Two consumer implementations carry an identical copy of the rule
(`CentralizedMessageFetcher:468`, `DefaultDurableQueueConsumer:586`):

```java
protected boolean isPermanentError(QueuedMessage queuedMessage, Throwable e) {
    var rootCause = Exceptions.getRootCause(e);
    return consumeFromQueue.getRedeliveryPolicy().isPermanentError(queuedMessage, e) ||
            e instanceof DurableQueueDeserializationException ||
            e instanceof ClassCastException   || rootCause instanceof ClassCastException ||
            e instanceof NoClassDefFoundError || rootCause instanceof NoClassDefFoundError ||
            rootCause instanceof MismatchedInputException ||
            e instanceof IllegalArgumentException || rootCause instanceof IllegalArgumentException;
}
```

and its caller:

```java
if (isPermanentError || message.getTotalDeliveryAttempts() >= policy.getMaximumNumberOfRedeliveries() + 1) {
    log.error("[{}:{}] Marking message as dead letter due to error: {}", …);
```

Three properties follow, and all three surprise people:

1. **Permanent means zero retries.** It bypasses the redelivery policy entirely, rather than shortening it.
2. **The built-in list is OR-ed *after* the policy**, so no policy can remove a type from it.
3. **Only the outermost exception and the deepest root cause are examined**, never the middle of the chain.
   A `@MessageHandler` throw arrives wrapped (`UnitOfWorkException → ReflectionException →
   InvocationTargetException → yours`), so the handler's own exception is the deepest and decides the
   outcome — unless the handler's exception itself carries a cause, in which case the classification
   silently flips to that deeper type.

---

### D1 — An explicit `alwaysRetryOn` cannot win

#### Motivation

`MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)` builds a handler
whose `isPermanentError` returns `false` for that type. The consumer then ORs the built-in list, which
contains `IllegalArgumentException`, and the message is dead-lettered on the first delivery anyway.

The API offers a knob that cannot work for six of the types people most want to configure. Worse, the
two most likely ways to raise one are the framework's own idioms: Kotlin's `require(...)`, and
`FailFast.requireNonNull` / `requireTrue`, which throw `IllegalArgumentException` rather than
`NullPointerException` (`shared/.../FailFast.java:72`) across 2000+ call sites in this repository.

`false` today means two different things — "I have no opinion" and "I say retry" — and the consumer
cannot tell them apart.

#### The shape

Give the strategy a three-valued answer, additively:

```java
public enum MessageDeliveryVerdict { PERMANENT_ERROR, RETRY, NO_OPINION }

// on MessageDeliveryErrorHandler, alongside the existing method
default MessageDeliveryVerdict verdict(QueuedMessage queuedMessage, Throwable error) {
    return isPermanentError(queuedMessage, error) ? PERMANENT_ERROR : NO_OPINION;
}
```

Every existing implementation keeps compiling and behaving identically. Only the builder's product
overrides `verdict` to answer `RETRY` for an `alwaysRetryOn` match. Both consumers then read:

```java
var verdict = policy.getDeliveryErrorHandler().verdict(message, e);
boolean permanent = verdict == PERMANENT_ERROR ||
                    (verdict != RETRY && isBuiltInPermanentError(e));
```

#### Which built-ins stay unconditional

`RETRY` should not be able to override a failure that can never succeed, or the queue head-blocks
forever. Proposed split:

| Type | Overridable by `RETRY`? | Why |
|---|---|---|
| `DurableQueueDeserializationException`, `MismatchedInputException` | **No** | The stored bytes will not parse on the hundredth attempt either |
| `NoClassDefFoundError` | **No** | A missing class is a deployment fault, not a transient one |
| `IllegalArgumentException` (incl. `NumberFormatException`) | **Yes** | The house guard idiom; frequently thrown about data that may be valid later |
| `ClassCastException` | **Yes** | Usually a genuine bug, but a cast against a projection that has not caught up is legitimately transient |

#### Tests

- `MessageDeliveryErrorHandlerBuilderTest` — extend with verdict assertions; it currently pins only the
  two-valued behaviour.
- `DurableQueuesIT` (`foundation-test`) — one case per row of the table above, so both the PostgreSQL and
  MongoDB implementations are covered from the shared base.
- `EventProcessorIT` — end to end: a handler throwing `IllegalArgumentException` under a policy that
  explicitly retries it must be redelivered, not dead-lettered.

#### Effort

Small. Two production files plus the builder, one enum, and the tests above.

---

### D2 — `alwaysRetryOn` does not mean "no matter how many times"

#### Motivation

Its javadoc says the handler "will keep retrying message redelivery **no matter how many times** message
handling experiences an exception". It does not: even once D1 makes `RETRY` win the classification, the
second half of the caller's condition — `attempts >= maximumNumberOfRedeliveries + 1` — still
dead-letters the message.

So the name and the doc promise unbounded redelivery while the code caps it.

#### Two ways to resolve it, and this needs a decision

**Option A — honour the promise.** A `RETRY` verdict bypasses the attempt cap. Matches the javadoc and
the method name. Risk: a message can be retried forever, which is invisible unless D3 lands with it, and
a permanently-failing message at the head of an ordered queue blocks everything behind it.

**Option B — keep the cap, fix the documentation.** `alwaysRetryOn` means "never *classified* permanent",
nothing more, and a separate `retryIndefinitelyOn(...)` is added later if anybody asks. Safer, but leaves
a method whose name over-promises.

Recommendation: **B now, A only if somebody has the use case.** Unbounded retry plus ordered delivery is
how a queue stops moving, and nobody has asked for it. If A is chosen, it needs a mandatory escalating
`WARN` (every 10th attempt, say) so a stuck message is loud rather than silent.

---

### D3 — A dead letter is invisible

#### Motivation

Nothing is waiting on a message handler. The HTTP request that produced the event committed long ago, the
handler runs on a subscription thread, and the subscription's resume point moves past the failure. A dead
letter therefore produces exactly one `log.error`, a row in the dead-letter table, and no other signal:
no failed test, no failing request, no health change.

What exists today is a *timer* — `essentials.messaging.durable_queues.mark_as_dead_letter_message` — which
measures how long the marking operation took, is gated behind `essentials.metrics.durable-queues.enabled`,
and carries no reason. It is not the signal an operator needs.

#### The shape

Three pieces, in increasing order of intrusiveness:

1. **A counter, always recorded when a `MeterRegistry` is present** — one increment per dead letter, tagged
   with `queue_name`, `message_payload_type` and a new `reason` tag (`permanent_error` |
   `redeliveries_exhausted`). Independent of the execution-time toggle, because a timing switch should not
   turn an incident counter off. This is the piece that makes alerting possible at all.
2. **The log line carries the classification** — which of the two branches fired, the attempt count, and the
   root-cause type, so the ERROR line answers "why" without a debugger.
3. **An optional health indicator**, off by default, reporting dead-letter counts per queue as
   `DOWN`/degraded above a configurable threshold. `CdcHealthIndicator` is the precedent. Off by default
   because a dead letter is a business-process incident rather than an availability one, and a demo app
   should not go unhealthy for one poison message.

   > *Superseded — see §4 D3 piece 3.* As shipped, the indicator is registered by default and reports `UP`
   > regardless of the counts until a per-queue threshold is configured. The concern this paragraph raises is
   > about the *status*, not the registration, and moving the opt-in to the threshold answers it while still
   > making the counts visible to everyone.

#### Tests

`DurableQueuesIT` for the counter (both reasons), and a foundation unit test for the log-line
content. The health indicator gets its own IT in `spring-boot-starter-postgresql`.

#### Effort

Medium. Piece 1 touches `RecordExecutionTimeDurableQueueInterceptor` (or a sibling interceptor, if mixing
counters into a class named for execution time is unwelcome) plus both consumers, which is where the reason
is known.

---

### D4 — The interaction is undocumented

#### Motivation

Nothing tells a handler author that `FailFast.requireNonNull` — the idiom the whole repository uses for
argument validation — turns a message into a dead letter on first delivery when used inside a
`@MessageHandler`. It is not in `LLM/LLM-foundation.md`, not in `LLM/LLM-postgresql-queue.md`, and not in
the foundation `CLAUDE.md`. Every consumer writing an `EventProcessor` will eventually hit it, and the
failure leaves no trace beyond one log line.

#### The shape

Documentation only, no code:

- **`LLM/LLM-foundation.md`** — in the DurableQueues/RedeliveryPolicy section: the built-in permanent list,
  that `IllegalArgumentException` is on it, and that `FailFast` and Kotlin `require(...)` both raise it. A
  short "validating inside a handler" recipe: use a retryable exception when the condition may become true
  later, `IllegalArgumentException` only when the message can never be processed.
- **`LLM/LLM-postgresql-queue.md`** — the same list, next to the redelivery-policy documentation.
- **`EventProcessor` / `ViewEventProcessor` javadoc** — one paragraph where handler authors actually read.
- **`components/foundation/CLAUDE.md`** — the contributor-side note, including the root-cause-unwrapping
  subtlety from property 3 above.
- **Root `CLAUDE.md`** — one line in Critical Gotchas, because it crosses modules.

#### Effort

Small, and it is the item with the best ratio: it removes the surprise even if D1–D3 never happen.

---

### Compatibility

The stable-API rule applies (root `CLAUDE.md`): breaking changes only in a new major, additive in
patch/minor. All of the above is additive:

- D1 adds an enum and a `default` method. Every existing `MessageDeliveryErrorHandler` — including the
  three inner classes and any consumer implementation — keeps its behaviour, because the default
  implementation maps `true`/`false` onto `PERMANENT_ERROR`/`NO_OPINION`.
- **`alwaysRetry()` deliberately keeps meaning `NO_OPINION`, not `RETRY`.** It is the builder default, so
  promoting it to `RETRY` would silently make deserialization failures retry forever in every existing
  application. Only the explicit `alwaysRetryOn(...)` list yields `RETRY`.
- D2 option B is a documentation change; option A would be a behaviour change and belongs in a major.
- D3 adds signal only.

### Sequencing

| Step | Content | Gate |
|---|---|---|
| 1 | D4 — documentation | none; do it now |
| 2 | D1 — the verdict enum, both consumers, the overridable/unconditional split | decision on the split table |
| 3 | D3 piece 1 and 2 — counter and richer log line | none |
| 4 | D2 — resolve the naming/promise mismatch per the decision below | decision A or B |
| 5 | D3 piece 3 — optional health indicator | demand |

### Open questions

1. **D2: option A or B?** Recommendation B — keep the cap, fix the doc, add `retryIndefinitelyOn` only on
   demand.
2. **D1: is the overridable/unconditional split right?** Specifically, should `ClassCastException` be
   overridable? It is nearly always a bug, but "projection not caught up yet" makes it transient in
   practice.
3. **D3: should a dead letter ever affect health?** Proposed off by default; worth confirming that is the
   right default for a production deployment rather than just for a demo.
4. **Should the middle of the cause chain be examined** (property 3), rather than only the outermost
   exception and the deepest root cause? It would make classification predictable regardless of whether a
   handler attaches a cause — but it would also change the outcome for existing applications whose
   handlers wrap an `IllegalArgumentException` around something else, so it is a major-version change at
   best.
