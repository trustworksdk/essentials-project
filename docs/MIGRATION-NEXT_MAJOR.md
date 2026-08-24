# Migration guide — construction ergonomics and `Optional` policy

> **Nothing in this guide is urgent yet.** Every constructor named below still exists, still compiles and still
> behaves exactly as it did. This release only marks them `@Deprecated(forRemoval = true)` and adds a better way to
> construct each type alongside. Upgrading and changing nothing gives you deprecation warnings and no errors.
>
> The removals happen at the next major (`<NEXT_MAJOR>`), which is separate, later work. This guide is what to do
> between now and then.
>
> Design rationale: `docs/constructor-ergonomics-and-optional-policy.md`.

## What changed and why

Two API-shape problems had accumulated:

- **Wide constructors.** 84 constructors took 6 or more parameters; the worst took 17. Beyond about five arguments a
  call site cannot be read, and two adjacent parameters of the same type transpose silently.
- **`Optional` in constructors.** ~100 `Optional` parameters, of which `Optional<MeterRegistry>` alone accounted for
  36 — every one of them immediately unwrapped to a nullable field or a default. The `Optional` bought nothing and
  cost every caller an `Optional.of(...)` wrapper.

The policy now is: `Optional` is for return types, builder-setter overloads, and Spring `@Bean` signatures. Absence in
a constructor is expressed as a **neutral default**, a **sealed variant**, or a **builder-resolved nullable**. An
ArchUnit guard (`EssentialsConstructionRules`) enforces it.

## The three shapes you will meet

### 1. `Optional<MeterRegistry>` → `MeasurementTaker`

`MeasurementTaker` was always a fan-out that does nothing when it has no recorders, so it — not the registry — is the
right currency. `MeasurementTaker.none()` is the neutral default.

```java
// before
new RecordExecutionTimeDurableQueueInterceptor(Optional.of(meterRegistry), true, thresholds, "MyModule");
new RecordExecutionTimeDurableQueueInterceptor(Optional.empty(), false, thresholds, "MyModule");

// after
new RecordExecutionTimeDurableQueueInterceptor(MeasurementTaker.builder()
                                                              .setLoggingRecorder(MyClass.class, thresholds)
                                                              .setMeterRegistry(meterRegistry)
                                                              .build(),
                                               "MyModule");
new RecordExecutionTimeDurableQueueInterceptor(MeasurementTaker.none(), "MyModule");   // recording disabled
```

The separate `recordExecutionTimeEnabled` flag is gone: passing `MeasurementTaker.none()` disables recording, and the
interceptors branch on `MeasurementTaker.isRecording()` so a disabled interceptor still skips all context assembly —
the same hot-path behaviour the boolean gave you.

**Spring users:** nothing to do. The starters build these and are the only place `Optional<MeterRegistry>` still
appears, unwrapped on the spot in the `@Bean` method.

**Two classes deliberately kept the registry**: `CdcAvailability` and `CdcSlotMetrics` register Micrometer `Gauge`s
and `Counter`s, which a timing facade cannot express. They take a plain nullable `MeterRegistry`; pass `null` for "no
metrics" instead of `Optional.empty()`.

### 2. Wide constructor → builder or parameter object

```java
// before
new PostgresqlAggregateSnapshotRepository(eventStore, uowFactory, Optional.of("snapshots"), serializer,
                                          addStrategy, deleteStrategy, Optional.of(meterRegistry));

// after
PostgresqlAggregateSnapshotRepository.builder()
                                     .setEventStore(eventStore)
                                     .setUnitOfWorkFactory(uowFactory)
                                     .setSnapshotTableName("snapshots")   // or omit for the default
                                     .setJsonSerializer(serializer)
                                     .setAddNewSnapshotStrategy(addStrategy)
                                     .setSnapshotDeletionStrategy(deleteStrategy)
                                     .setMeterRegistry(meterRegistry)     // or omit for no metrics
                                     .build();
```

Every generated builder gives each previously-`Optional` argument **two** setters — a plain-value one and an
`Optional` overload — so code that already holds an `Optional` does not have to unwrap it.

### 3. Enum + collaborator → sealed type

`WalReplicationTailer` took a `CdcDeliveryMode` enum *and* an `Optional<Consumer<List<PersistedEvent>>>`, then
re-validated at runtime that the consumer was present when the mode was `DIRECT`. Those are one value:

```java
// before — the illegal combination is expressible, and rejected at construction time
new WalReplicationTailer(dataSource, jdbi, uowFactory, slotName, inboxRepository, tailerProps,
                         pgSlotMode, cdcMode, CdcDeliveryMode.DIRECT, plugin,
                         Optional.of(onEvents), Optional.empty(), availability,
                         Optional.of(meterRegistry), Optional.empty());

// after — choosing DIRECT and supplying its consumer are the same act
new WalReplicationTailer(CdcTailerDependencies.builder()
                                              .setReplicationDataSource(dataSource)
                                              .setJdbi(jdbi)
                                              .setUnitOfWorkFactory(uowFactory)
                                              .setLogicalDecodingPlugin(plugin)
                                              .setAvailability(availability)
                                              .setMeterRegistry(meterRegistry)
                                              .build(),
                         CdcTailerSettings.of(slotName, tailerProps, pgSlotMode, cdcMode),
                         CdcDelivery.direct(onEvents));          // or CdcDelivery.inbox(inboxRepository)
```

The `directOnEvents cannot be null in DIRECT delivery mode` runtime check is gone because the state it guarded can no
longer be constructed.

## ⚠️ Behaviour changes in this release

### `PostgresqlDurableQueues.builder()` now defaults `useOrderedUnorderedQuery` to `true`

The same shape of divergence as the transactional-mode one below, in a different setting.
`EssentialsComponentsProperties` defaulted the flag to `true` and the deprecated wide constructors passed
`true`, but `PostgresqlDurableQueuesBuilder`'s uninitialised `boolean` field left it `false` — so Spring
applications got the split ordered/unordered fetch queries while anyone using the builder, the documented
preferred path, silently got the single unified query.

That query applies the ordered per-key barrier — a correlated `NOT EXISTS` against the same table — to every
candidate row, including unordered ones where `key IS NULL` makes it vacuously true, and orders by
`key_order`, a constant `-1` for those rows. On a backlog mixing both kinds it measured **5.4× slower**;
pure-ordered traffic is indifferent, since it needs the barrier either way. See
`docs/durable-queues-redesign-measurements.md`.

**No action needed for Spring applications** — they were already on `true`. **If you build
`PostgresqlDurableQueues` directly and deliberately want the unified query**, say so explicitly:

```java
PostgresqlDurableQueues.builder()
                       .setUnitOfWorkFactory(unitOfWorkFactory)
                       .setUseOrderedUnorderedQuery(false)
                       .build();
```

The flag is now readable at runtime via `PostgresqlDurableQueues.isUseOrderedUnorderedQuery()`, so a
deployment can verify which fetch strategy it actually got rather than the one it believes it configured.

### `PostgresqlDurableQueues` constructors now default to `SingleOperationTransaction`, not `FullyTransactional`

Until now the constructors that do not take a `TransactionalMode` hardcoded `FullyTransactional`, while
`PostgresqlDurableQueues.builder()` defaulted to `SingleOperationTransaction` — the same component behaved differently
depending on how you created it. That divergence was a documented hazard, and this release closes it by converging on
the builder's defaults (`SingleOperationTransaction`, with a 30-second `messageHandlingTimeout`).

`FullyTransactional` is the side documented as broken for retries and dead-lettering, which is why convergence goes
this way. **If you construct `PostgresqlDurableQueues` via a constructor that does not name a mode and you rely on
`FullyTransactional`, pass it explicitly:**

```java
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener, optimizerFactory,
                            TransactionalMode.FullyTransactional, null);
```

### `OrderedMessage` duplicates are now rejected, and startup fails on a table that already contains some

**This is the one behaviour change in this release that can stop an existing deployment from starting**, so it is
worth reading even if nothing else here applies.

`PostgresqlDurableQueues` now defaults `orderedMessageDuplicateStrategy` to `REJECT`, which adds a unique index on
`(queue_name, key, key_order) WHERE key IS NOT NULL`. The defect it closes is silent: the per-key ordering barrier
blocks only on a **strictly** lower `key_order`, so two `OrderedMessage`s sharing a key *and* an order never block
each other and that key's ordering guarantee simply does not hold. Nothing in the schema prevented it before.

`REJECT` is safe as a default because every ordered message the framework itself produces is duplicate-free by
construction — the event processors and the subscription manager key on the aggregate id and order by `EventOrder`,
which is unique within its stream. The exposure is application code deriving `key_order` from something that is not
unique. Rejection doubles as an idempotent enqueue for an at-least-once upstream.

**A `CREATE UNIQUE INDEX` cannot succeed against a table that already contains duplicates**, so startup fails,
naming the offending key, rather than continuing without the index. That is deliberate: logging a warning and
carrying on would leave the deployment believing ordering is protected when it is not. Two ways forward:

```java
// 1. Keep the old behaviour - duplicates accepted, ordering not guaranteed for those keys
PostgresqlDurableQueues.builder()
                       .setOrderedMessageDuplicateStrategy(OrderedMessageDuplicateStrategy.ALLOW)
                       .build();
```

```sql
-- 2. Or find and resolve them first, then upgrade into REJECT
SELECT queue_name, key, key_order, count(*), array_agg(id)
  FROM durable_queues
 WHERE key IS NOT NULL
 GROUP BY queue_name, key, key_order
HAVING count(*) > 1;
```

Spring applications set this through `essentials.durable-queues.ordered-message-duplicate-strategy`.

### Paging queued and dead-letter messages is now ordered, and `queueingSortOrder` is finally honoured

`getQueuedMessages` / `getDeadLetterMessages` took a `QueueingSortOrder` that **both** implementations ignored:
PostgreSQL paged with `LIMIT/OFFSET` and no `ORDER BY`, MongoDB with `skip()/limit()` and no sort. Without a total
order, paging is not a partition of the queue — the database is free to return rows in a different order per query,
so a message could appear on two pages and another on none.

Both now order by the added timestamp, with the entry id as the tie-break that makes the order total. **Not by id
alone**, even though the javadoc used to say "the sorting order for `QueuedMessage#getId()`": a `QueueEntryId` is a
UUID and does not sort chronologically as a string, so id-ordering would have turned the admin browse surface from
"oldest first" into a stable shuffle. The javadoc was describing an order nobody wanted and has been corrected.

**No action needed.** The observable change is that pages are now stable and `DESC` actually reverses them. If you
depended on the previous order, you were depending on heap order, which was never guaranteed.

### Delivery statistics are collected in memory, not by a database trigger

`essentials.durable-queues.enable-queue-statistics=true` used to create a statistics table and an `AFTER DELETE`
trigger on the queue table, writing one row per acknowledged message inside the queue's own transaction — measured
at **2.80×** on acknowledgement throughput. It now wires `InMemoryDurableQueuesStatistics`, fed by a
`DurableQueueMessageObserver`. `PostgresqlDurableQueuesStatistics` is `@Deprecated(forRemoval = true)` and wired by
nothing.

**What changes for you:**

- **Enabling statistics is no longer a schema migration.** No table is created, and no trigger is installed on the
  queue table. An existing `durable_queues_statistics` table is left alone; drop it when you are ready.
- **The numbers are now per instance and since startup.** Each instance counts the deliveries it performed, and a
  restart resets them. A low number is not a slow queue and a zero is not a stall. The admin UI states this on the
  queue view. For cluster-wide or historical answers, aggregate the Micrometer meters.
- **`purgeQueue` and `deleteMessage` no longer produce statistics.** The trigger counted a purge of N rows as N
  delivered messages, each with a latency measured to the moment of the purge, so the previous numbers were wrong
  in a way that is worth knowing if you have been reading them.
- **`getQueueStatisticsMessage(QueueEntryId)` works for the first time**, best-effort: it answers for a message
  this instance recently finished with. The durable version stored `delivery_latency` as an `INTERVAL` and read it
  back with `getInt`, which pgjdbc rejects, so it threw for every id.
- **Three properties are now inert**: `shared-queue-statistics-table-name`, `enable-queue-statistics-ttl` and
  `queue-statistics-ttl-duration`. Nothing reads them; they are kept so existing configuration still binds, and
  will be removed in the next major. There is no statistics table to name or prune.

If you need durable, cluster-wide statistics, the intended shape is a batched asynchronous writer fed by the same
observer — see `docs/durable-queues-statistics-improvements.md`. It is not built.

### `QueuedMessage.getDeliveryMode()` is derived and no longer always reports `NORMAL`

`DefaultQueuedMessage.getDeliveryMode()` returned `NORMAL` unconditionally, contradicting both the persisted
`delivery_mode` column and Mongo's own implementation. It now returns `IN_ORDER` if and only if the wrapped
`Message` is an `OrderedMessage`, so it cannot disagree with what was stored.

**Action needed only if you branched on it** and were relying on the constant `NORMAL` — such code now takes the
ordered path for ordered messages, which is what it was presumably written to do.

Everything else in this release is source- and binary-compatible.

## Per-module reference

### `shared`

| Deprecated | Replacement |
|---|---|
| `PatternMatchingMethodInvoker(Object, MethodPatternMatcher, InvocationStrategy, Optional<NoMatchingMethodsHandler>, Optional<InvocationTracker>)` | Same constructor with plain values — `NoMatchingMethodsHandler.ignore()` / `InvocationTracker.noOp()` — or `PatternMatchingMethodInvoker.builder()` |
| `MeasurementTaker.Builder.withOptionalMicrometerMeasurementRecorder(Optional<MeterRegistry>)` | `setMeterRegistry(MeterRegistry)` / `setMeterRegistry(Optional<MeterRegistry>)` |

New: `MeasurementTaker.none()`, `MeasurementTaker.isRecording()`,
`MeasurementTaker.Builder.setLoggingRecorder(Class<?>, LogThresholds)`,
`MeasurementInvocationTracker(MeasurementTaker)`.

### `components/foundation`

| Deprecated | Replacement |
|---|---|
| `RecordExecutionTimeCommandBusInterceptor(Optional<MeterRegistry>, boolean, LogThresholds, String)` | `(MeasurementTaker, String moduleTag)` |
| `RecordExecutionTimeDurableQueueInterceptor(...)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `RecordExecutionTimeMessageHandlerInterceptor(...)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `RecordSqlExecutionTimeLogger(...)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `DBFencedLockManager(FencedLockStorage, UnitOfWorkFactory, Optional<String>, Duration, Duration, boolean, Optional<EventBus>)` | `(FencedLockStorage, UnitOfWorkFactory, FencedLockManagerSettings, EventBus)` — see `FencedLockManagerSettings.builder()` |
| `DefaultQueuedMessage(...)` — 11 args | `DefaultQueuedMessage.builder()` |
| `DefaultQueuedStatisticsMessage(...)` — 10 args | `DefaultQueuedStatisticsMessage.builder()` |
| `DefaultDurableQueueConsumer(ConsumeFromQueue, UOW_FACTORY, DURABLE_QUEUES, Consumer, long, QueuePollingOptimizer, List)` | `(ConsumeFromQueue, DurableQueueConsumerDependencies)` |
| `DurableLocalCommandBus` — 12 of 13 constructors | `DurableLocalCommandBus.builder()`; `DurableLocalCommandBus(DurableQueues)` stays for the all-defaults case |
| `RedeliveryPolicy(...)` — 7 args | `RedeliveryPolicy.builder()` / `exponentialBackoff()` / `linearBackoff()` / `fixedBackoff()` |
| `ConsumeFromQueue(...)` — 3 overloads | `ConsumeFromQueue.builder()` |
| `QueueMessage(...)` / `QueueMessages(...)` | `QueueMessage.builder()` / `QueueMessages.builder()` |
| `DBFencedLock(...)` — 6 args | `DBFencedLock.builder()` |

Note the change in failure type on `DBFencedLockManager`: the `lockConfirmationInterval < lockTimeOut` check now
fires when `FencedLockManagerSettings` is created, which is strictly earlier than before.

### `components/postgresql-event-store`

| Deprecated | Replacement |
|---|---|
| `AppendToStream(AggregateType, ID, Optional<Long>, List<?>)` | `(AggregateType, ID, Long, List<?>)` with `null`, or `AppendToStream.builder()` |
| `FetchStream(AggregateType, ID, LongRange, Optional<Tenant>)` | `(AggregateType, ID, LongRange, Tenant)` with `null`, or `FetchStream.builder()` |
| `LoadEventsByGlobalOrder(AggregateType, LongRange, List, Optional<Tenant>)` | `(…, Tenant)` with `null`, or `LoadEventsByGlobalOrder.builder()` |
| `RecordExecutionTimeEventStoreInterceptor(Optional<MeterRegistry>, boolean, LogThresholds, String)` | `(MeasurementTaker, String moduleTag)` |
| `MeasurementEventStoreSubscriptionObserver(...)` — same shape | `(MeasurementTaker, String moduleTag)` |
| `CdcAvailability(Optional<MeterRegistry>)` | `CdcAvailability(MeterRegistry)` with `null`, or `CdcAvailability()` |
| `CdcSlotMetrics(WalReplicationTailer, Optional<MeterRegistry>, String, CdcSlotProperties)` | `(WalReplicationTailer, MeterRegistry, String, CdcSlotProperties)` with `null` |
| `AbstractEventStoreSubscription(EventStore, AggregateType, SubscriberId, Optional<Tenant>, …)` — 7 args | `(EventStoreSubscriptionContext)` |
| `ExclusiveAsynchronousSubscription(…)` — 13 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, FencedLockManager, FencedLockAwareSubscriber, PersistedEventHandler)` |
| `NonExclusiveBatchedAsynchronousSubscription(…)` — 13 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, int, Duration, BatchedPersistedEventHandler)` |
| `NonExclusiveAsynchronousSubscription(…)` — 11 args | `(EventStoreSubscriptionContext, DurableSubscriptionContext, PersistedEventHandler)` |
| `ExclusiveInTransactionSubscription(…)` — 9 args | `(EventStoreSubscriptionContext, FencedLockManager, TransactionalPersistedEventHandler)` |
| `NonExclusiveInTransactionSubscription(…)` — 8 args | `(EventStoreSubscriptionContext, TransactionalPersistedEventHandler)` |
| `WalReplicationTailer(…)` — 15- and 17-arg forms | `(CdcTailerDependencies, CdcTailerSettings, CdcDelivery)` |
| `CdcDispatcher(…)` — 11 args | `(CdcDispatcherDependencies, CdcDispatcherSettings)` |
| `AggregateEventStreamConfiguration(…)` — 10 args | `AggregateEventStreamConfiguration.builder()` |
| `SeparateTablePerAggregateEventStreamConfiguration(…)` — 12 args | `SeparateTablePerAggregateEventStreamConfiguration.builder()` |
| `SeparateTablePerAggregateTypeEventStreamConfigurationFactory(…)` — 10 args | `…Factory.builder()` |

`EventStoreSubscription.onlyIncludeEventsForTenant()` still returns `Optional<Tenant>` — only the field behind it
became nullable. Same for `AppendToStream.getAppendEventsAfterEventOrder()`, `FetchStream.getTenant()` and
`LoadEventsByGlobalOrder.getOnlyIncludeEventIfItBelongsToTenant()`.

### `components/eventsourced-aggregates`

All of the following gain a `builder()`; their `Optional`-taking and wide constructors are deprecated:
`AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository`, `PostgresqlAggregateSnapshotRepository`,
`PostgresqlAggregateSnapshotStore`, `PostgresqlAggregateSnapshotJobRepository`,
`PostgresqlAggregateSnapshotJobProcessor`, `BuiltInClosingBooksPolicyEvaluator`, `ClosingBooksCoordinator`,
`ClosingBooksManager`, `DefaultClosingBooksScheduledScanProcessor`, `PostgresqlClosingBooksGenerationRepository`,
`DefaultAggregateGenerationArchiver`, `PostgresqlAggregateArchiveRegistry`, `DefaultAggregateLifecycleApi`,
`DefaultAggregateLifecycleStatisticsApi`.

`StatefulAggregateRepository.DefaultStatefulAggregateRepository`'s protected constructors are deprecated — use
`StatefulAggregateRepository.builder(eventStore)` or the `from(...)` factories.

### Queues and fenced locks

| Deprecated | Replacement |
|---|---|
| `PostgresqlDurableQueues` — wide constructors | `PostgresqlDurableQueues.builder()` — **and see the behaviour changes above** |
| `MongoDurableQueues` — 9 of 10 constructors | `MongoDurableQueues.builder()` |
| `PostgresqlDurableQueueConsumer(…)` / `MongoDurableQueueConsumer(…)` — 7 args | `(ConsumeFromQueue, DurableQueueConsumerDependencies)` |
| `PostgresqlFencedLockManager` / `MongoFencedLockManager` — `Optional`-taking constructors | their existing `builder()` |
| `LocalEventBus(…)` — wide constructor | `LocalEventBus.builder()` |
| `PostgresqlDurableQueuesStatistics` — collects via an `AFTER DELETE` trigger | `InMemoryDurableQueuesStatistics` + `DurableQueueMessageObserver` — **see the behaviour changes above** |

New in this release, additive:

| Addition | What it is for |
|---|---|
| `DurableQueues.getMessageObserver()` — a `default` method | How `DurableQueueMessageObserver` reaches the two classes that decide a delivery's outcome. Defaults to `none()`, so no implementation has to change |
| `DurableQueues.acknowledgeMessagesAsHandled(Collection)` | Batched acknowledgement — one `DELETE` per batch instead of one transaction per message. **An ack-counting `DurableQueuesInterceptor` must implement both this and the single-message overload, or it goes blind when batching is on** |
| `OrderedMessageDuplicateStrategy` | `REJECT` (default) / `ALLOW` — see the behaviour changes above |
| `PostgresqlSplitDurableQueues` + `…Settings` / `…Builder` | Stores ordered and unordered messages in separate tables so each carries only the indexes it needs. Opt-in, hand-wired; the Spring starter still builds `PostgresqlDurableQueues` |

### Starters and admin API

| Deprecated | Replacement |
|---|---|
| `DefaultAggregateSnapshotRepositoryFactory(…)` — 9 args | `DefaultAggregateSnapshotRepositoryFactory.builder()` |
| `DefaultEventStoreApi(…)` / `DefaultCdcApi(…)` | their `builder()` |

## Records are exempt

A record's canonical constructor is not subject to either rule. Its parameter list *is* its component list, and a
component's type *is* its accessor's return type — and `Optional` return types are explicitly permitted. So
`SnapshotTriggerContext`, `AggregateGeneration`, `AggregateSnapshotPolicyDescriptor` and
`AggregateClosingBooksPolicyDescriptor` keep their `Optional` components and are unchanged.

## Not changed on purpose

`PersistedEvent.DefaultPersistedEvent` and `PersistableEvent.DefaultPersistableEvent` still have wide constructors.
Under Jackson 3 a constructor parameter *name* is part of the JSON contract, so reshaping the creator of a persisted
type risks the wire format. They are left alone deliberately; see the Risks section of the design document.
