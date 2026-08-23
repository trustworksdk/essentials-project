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

**`PostgresqlDurableQueues` constructors now default to `SingleOperationTransaction`, not `FullyTransactional`.**

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

### `MongoDurableQueues.builder()` now defaults to `SingleOperationTransaction`, not `FullyTransactional`

The same convergence, one layer out: the two builders now agree. `MongoDurableQueues.builder()` previously produced
`FullyTransactional` — it delegated to the constructor taking a `SpringMongoTransactionAwareUnitOfWorkFactory` — while
`PostgresqlDurableQueues.builder()` produced `SingleOperationTransaction`. An application that moved between the two
database modules therefore got different delivery semantics from identical code, with nothing in either API saying so.

`messageHandlingTimeout` now defaults to `MongoDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT` (30 seconds), matching
`PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT`, so the default mode is usable with nothing but a
`MongoTemplate`. **If you use `MongoDurableQueues.builder()` and rely on `FullyTransactional`, say so explicitly:**

```java
MongoDurableQueues.builder()
                  .setMongoTemplate(mongoTemplate)
                  .setJsonSerializer(jsonSerializer)
                  .setSharedQueueCollectionName("durable_queues")
                  .setTransactionalMode(TransactionalMode.FullyTransactional)
                  .setUnitOfWorkFactory(unitOfWorkFactory)   // required in this mode
                  .build();
```

The **constructors are unaffected** — each still produces the mode its javadoc names, so only builder callers see this.
In `SingleOperationTransaction` the consumer MUST acknowledge messages explicitly in a new `UnitOfWork`; see
`DurableQueues#acknowledgeMessageAsHandled`.

Both defaults are now pinned by `PostgresqlDurableQueuesBuilderDefaultsTest` and
`MongoDurableQueuesBuilderDefaultsTest`, because the integration suites branch on `getTransactionalMode()` rather than
asserting it and so pass whichever way it drifts.

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
| `PostgresqlEventStore(EventStoreUnitOfWorkFactory, STRATEGY, Optional<EventStoreEventBus>, Function, EventStoreSubscriptionObserver)` | `PostgresqlEventStore.builder()` |
| `CdcEventStore(…)` — 6- and 7-arg forms | `CdcEventStore.builder()` |
| `CdcInboxRepository(…, Optional<MeterRegistry>[, String])` — both forms | `CdcInboxRepository.builder()`; the two non-`Optional` constructors are unchanged |
| `CdcEffectivenessMonitor(…)` — 6 args | `CdcEffectivenessMonitor.builder()` |
| `EventStoreEventBus(EventStoreUnitOfWorkFactory, int, int, OnErrorHandler, int, double)` | `EventStoreEventBus.builder()`; the shorter constructors are unchanged |
| `PersistedEventSubscriber(…)` — 6 args | `PersistedEventSubscriber.builder()` |
| `BatchedPersistedEventSubscriber(…)` — 7- and 8-arg forms | `BatchedPersistedEventSubscriber.builder()` |
| `DefaultEventStoreSubscriptionManager(…)` — 7- and 8-arg forms | `EventStoreSubscriptionManager.builder()` (also reachable as `DefaultEventStoreSubscriptionManager.builder()`) |
| `SeparateTablePerAggregateTypePersistenceStrategy(…)` — the two 6-arg forms | `SeparateTablePerAggregateTypePersistenceStrategy.builder()`; the 5-arg forms are unchanged |
| `EventStreamTableColumnNames(…)` — 12 args | `EventStreamTableColumnNames.builder()` |
| `PgReplicationSlots.SlotInfo(…)` — 15 args | `PgReplicationSlots.SlotInfo.builder()` |

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
| `PostgresqlDurableQueues` — wide constructors | `PostgresqlDurableQueues.builder()` — **and see the behaviour change above** |
| `MongoDurableQueues` — all 10 constructors, including the `protected` one taking `TransactionalMode` | `MongoDurableQueues.builder()`, which gained `setTransactionalMode(…)` and `setMessageHandlingTimeout(…)` so the mode is reachable without it — **and see the behaviour change above**: the builder's default moved from `FullyTransactional` to `SingleOperationTransaction`, matching `PostgresqlDurableQueuesBuilder` |
| `MongoDurableQueues.DurableQueuedMessage(…)` — 16 args | `MongoDurableQueues.DurableQueuedMessage.builder()`; the no-arg constructor Spring Data uses is unchanged |
| `PostgresqlDurableQueueConsumer(…)` / `MongoDurableQueueConsumer(…)` — 7 args | `(ConsumeFromQueue, DurableQueueConsumerDependencies)` |
| `PostgresqlFencedLockManager` / `MongoFencedLockManager` — `Optional`-taking constructors | their existing `builder()` |
| `LocalEventBus(…)` — wide constructor | `LocalEventBus.builder()` |

### Starters and admin API

| Deprecated | Replacement |
|---|---|
| `DefaultAggregateSnapshotRepositoryFactory(…)` — 9 args | `DefaultAggregateSnapshotRepositoryFactory.builder()` |
| `DefaultEventStoreApi(…)` / `DefaultCdcApi(…)` | their `builder()` |
| `DefaultAggregateLifecycleConfigurationValidator(…)` — 7 args | `DefaultAggregateLifecycleConfigurationValidator.builder()` |
| `CdcHealthIndicator(CdcAvailability, Optional<WalReplicationTailer>, Optional<CdcDispatcher>, EssentialsEventStoreProperties)` | `CdcHealthIndicator.builder()` |

## Records are exempt

A record's canonical constructor is not subject to either rule. Its parameter list *is* its component list, and a
component's type *is* its accessor's return type — and `Optional` return types are explicitly permitted. So
`SnapshotTriggerContext`, `AggregateGeneration`, `AggregateSnapshotPolicyDescriptor` and
`AggregateClosingBooksPolicyDescriptor` keep their `Optional` components and are unchanged.

## Not changed on purpose

`PersistedEvent.DefaultPersistedEvent` and `PersistableEvent.DefaultPersistableEvent` still have wide constructors.
Under Jackson 3 a constructor parameter *name* is part of the JSON contract, so reshaping the creator of a persisted
type risks the wire format. They are left alone deliberately; see the Risks section of the design document.

They are also the only two classes in the sweep that are not even marked `@Deprecated(forRemoval = true)`. That
annotation is a promise to remove the constructor at the next major, and here it is a promise the persisted format does
not let us keep — so making it would be worse than the wide constructor. Every other constructor the ArchUnit guard
flagged, across all four of its vantage points, is now deprecated with a `builder()` alongside it.
