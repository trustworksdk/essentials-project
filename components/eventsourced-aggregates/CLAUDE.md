# eventsourced-aggregates

Aggregate patterns (stateful, flex, decider, event-stream) layered on top of the PostgreSQL event store. Maven: `eventsourced-aggregates`.

## Package Structure

| Package | Contents |
|---|---|
| `aggregates` (root) | Core interfaces: `Aggregate`, `StatefulAggregate`, `EventsToPersist`, `EventHandler`, exceptions |
| `aggregates.stateful` | `StatefulAggregateRepository` + `DefaultStatefulAggregateRepository`, `StatefulAggregateInstanceFactory`, `StatefulAggregateInMemoryProjector` |
| `aggregates.stateful.classic` | OO-style `AggregateRoot<ID,EVENT_TYPE,SELF>` (events extend `Event`), `AggregateRootWithState` variant |
| `aggregates.stateful.modern` | `AggregateRoot` (events = records/POJOs, no `Event` base class), `AggregateState`, `WithState` |
| `aggregates.flex` | `FlexAggregate` + `FlexAggregateRepository` — command methods return `EventsToPersist` explicitly |
| `aggregates.decider` | Functional decider: `Decider`, `Handler`, `StateEvolver`, `HandlerResult` (sealed), `CommandHandler`, `AggregateIdResolver` |
| `aggregates.eventstream` | Experimental `EventStreamDecider` + `EventStreamEvolver`; adapters for wiring into `EventProcessor` |
| `aggregates.snapshot` | Two generations side by side: the original `AggregateSnapshotRepository` / `PostgresqlAggregateSnapshotRepository` + strategies, and the policy-driven stack — `@AggregateSnapshotPolicy`, `AggregateSnapshotStore`, `AsyncAggregateSnapshotRepository`, `DurableAsyncAggregateSnapshotRepository` and its job table |
| `aggregates.closingbooks` | Generation lifecycle: `@AggregateClosingBooksPolicy`, `LogicalAggregateId`, `AggregateGeneration`, `ClosingBooksCoordinator`, the two closing-books repositories, decision policies, scheduled scan |
| `aggregates.archive` | Export of `CLOSED` generations out of the hot event tables — `AggregateGenerationArchiver`, exporter/destination/registry |
| `aggregates.api` | Admin-API SPI implementations for the three subsystems above (`AggregateArchiveApi`, `ApiAggregate*` DTOs) |
| `aggregates.projection` | `AnnotationBasedInMemoryProjector` — projects event streams onto plain POJOs |

## Key Classes

| Class | Internal role |
|---|---|
| `Aggregate<ID,SELF>` | Root marker interface; mandates `aggregateId()` and `rehydrate()` |
| `StatefulAggregate<ID,E,SELF>` | Adds `getUncommittedChanges()` + `markChangesAsCommitted()` to `Aggregate` |
| `EventsToPersist<ID,E>` | Carries `aggregateId`, `eventOrderOfLastRehydratedEvent`, and the list of new events — the unit of work between command and repo |
| `StatefulAggregateRepository.DefaultStatefulAggregateRepository` | Wires UoW lifecycle callback; on `beforeCommit` flushes uncommitted events to event store and notifies snapshot repo |
| `StatefulAggregateInstanceFactory` | SPI with two built-ins: `ReflectionBasedAggregateInstanceFactory` (no-arg or ID constructor) and `ObjenesisAggregateInstanceFactory` (no constructor called) |
| `StatefulAggregateInMemoryProjector` | Rehydrates aggregates in-memory (used by `EventStore.inMemoryProjector`) |
| `classic.AggregateRoot` | Mutable stateful aggregate; auto-sets `eventOrder` and `aggregateId` on events; uses `PatternMatchingMethodInvoker` for `@EventHandler` dispatch |
| `modern.AggregateRoot` | Same pattern but events are free-form (records OK); requires ID passed to constructor — use `reflectionBasedAggregateRootFactory()` |
| `FlexAggregate` | Immutable-style: command methods return `EventsToPersist`; `rehydrate()` folds events; unmatched events silently ignored |
| `Decider<CMD,E,ERR,STATE>` | Functional ES: composes `Handler`, `StateEvolver`, `InitialStateProvider`, `IsStateFinalResolver`; factory via `Decider.decider(...)` |
| `HandlerResult<ERR,E>` | Sealed: `Success(List<EVENT>)` or `Error(ERR)` |
| `EventStreamDecider<CMD,E>` | EXPERIMENTAL — stateless decider operating on raw event list; designed for Event Modeling slices |
| `EventStreamEvolver<E,STATE>` | Companion to `EventStreamDecider`; folds events into STATE when needed |
| `AggregateSnapshotRepository` | SPI: `loadSnapshot`, `aggregateUpdated`, `deleteSnapshots` |
| `PostgresqlAggregateSnapshotRepository` | JSONB-backed snapshot table; uses `DelayedAddAndDeleteAggregateSnapshotDelegate` to defer writes |
| `AddNewAggregateSnapshotStrategy` | Decides when to snapshot (e.g. every N events) |
| `AggregateSnapshotDeletionStrategy` | Decides how many historic snapshots to keep |
| `BrokenSnapshot` | Sentinel stored when deserialization fails; triggers auto-cleanup on next load |
| `AggregateSnapshotStore` | Storage abstraction under the policy-driven repositories; `PostgresqlAggregateSnapshotStore` is the impl. `saveSnapshot` is version-guarded (`WHERE NOT EXISTS (newer)`), deletion is bounded via `deleteSnapshotsOlderThan` |
| `AsyncAggregateSnapshotRepository` | `Lifecycle`; serves `SYNC` (caller thread, no executor) and `ASYNC_IN_MEMORY` (fixed daemon-thread pool) |
| `DurableAsyncAggregateSnapshotRepository` | `ASYNC_DURABLE`; enqueues an `AggregateSnapshotJob` from a `UnitOfWorkLifecycleCallback` so the job is only registered after the user UoW commits |
| `PostgresqlAggregateSnapshotJobProcessor` / `DurableAsyncSnapshotManager` | Batch processor + polling `Lifecycle`. delete + save + markCompleted run in ONE UoW; `PROCESSING` rows past `processingTimeout` are reclaimed |
| `AggregateSnapshotStateAdapter` | Aggregate ↔ serialized state. `DefaultAggregateSnapshotStateAdapter` uses Objenesis directly, with a Jackson empty-JSON fallback |
| `LogicalAggregateId<ID>` / `AggregateGeneration<ID>` | Stable business id vs. one `(generation, streamAggregateId, state)` row per generation |
| `ClosingBooksCoordinator<ID>` | Generation lifecycle for one `AggregateType`; `closeAndOpenNextGeneration` runs close+open in a single UoW |
| `ClosingBooksLogicalAggregateRepository` | The consumer-facing seam — 4 type params `<LOGICAL_ID, STREAM_ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE>`; keeps callers on logical ids |
| `ClosingBooksStatefulAggregateRepository` | Thinner variant: resolves the open generation's stream id and delegates |
| `BuiltInClosingBooksPolicyEvaluator` | Implements the `ClosingBooksDefaultPolicyType` rules. Constructed by **application** code, never by the framework |
| `ClosingBooksDecisionPolicies` | Composable factories producing `ClosingBooksDecisionPolicy` → `KEEP_OPEN` / `CLOSE_ONLY` / `CLOSE_AND_OPEN_NEXT` |
| `ClosingBooksManager` | `Lifecycle` scheduled scanner; holds a `FencedLockManager` lock so only one node scans |
| `TypedClosingBooksNextGenerationFactory` | Domain hook deciding what state carries forward into the newly opened generation |
| `AggregateGenerationArchiver` | Archives one `(aggregateType, logicalAggregateId, generation)`; `IN_PROGRESS` status reserves the export across nodes |
| `AnnotationBasedInMemoryProjector` | Builds POJO projections from `@EventHandler` methods; registered as specific in-memory projector on `EventStore` |

## Test Structure

- `*Test` — pure unit tests, no infrastructure
- `*IT` — integration tests; all require Docker (Testcontainers `@Testcontainers` + `PostgreSQLContainer`)
- Test sub-packages mirror design flavors: `classic/`, `modern/`, `flex/`, `stateful/`, `decider/`, `eventstream/`, `snapshot/`, `closingbooks/`, `projection/`
- `closingbooks/InlineUnitOfWorkFactories.java` — inline UoW factory doubles so coordinator/repository logic is unit-testable without Docker
- `classic/Order.java`, `modern/Order.java`, `flex/Order.java` — representative aggregate impls; reused across IT suites
- `GivenWhenThenScenario` (in `main`) — fluent scenario builder for testing `EventStreamDecider`s without a DB

## Extension Points

- `StatefulAggregateInstanceFactory` — plug in custom aggregate construction (e.g. CDI/Spring injection at instantiation time)
- `AggregateSnapshotRepository` — implement alternative snapshot backends
- `AggregateSnapshotStore` — alternative storage under the policy-driven repositories
- `AggregateSnapshotStateAdapter` — custom aggregate ↔ snapshot-state conversion
- `AggregateSnapshotJobRepository` — alternative durable job backend
- `AddNewAggregateSnapshotStrategy` — custom snapshot frequency logic
- `AggregateSnapshotDeletionStrategy` — custom retention policy
- `ClosingBooksDecisionPolicy<ID,AGGREGATE>` — custom rollover rule; compose via `ClosingBooksDecisionPolicies`
- `ClosingBooksGenerationResolver` / `ClosingBooksOpenGenerationRepository` — alternative generation storage
- `ClosingBooksStreamIdGenerator` / `ClosingBooksStreamIdSerializer` — how a generation names its event stream
- `TypedClosingBooksNextGenerationFactory` — carry-forward state into the newly opened generation
- `HasClosingBooksPeriodId` — aggregate contract for time-boundary policies
- `AggregateArchiveExporter` / `AggregateArchiveDestination` / `AggregateArchiveRegistry` — archive format, sink and bookkeeping
- `EventStreamDecider<CMD,E>` — implement per-command slice (experimental)
- `EventStreamEvolver<E,STATE>` — implement state folding for `EventStreamDecider`
- `Decider<CMD,E,ERR,STATE>` — fully functional aggregate pattern; compose via `Decider.decider(handler, initialState, evolver, isFinal)`
- `StatefulAggregate<ID,E,SELF>` — implement from scratch (bypass `AggregateRoot` entirely)

## Gotchas

- **UoW required for load/save** — `DefaultStatefulAggregateRepository` calls `eventStore.getUnitOfWorkFactory().getRequiredUnitOfWork()`; no UoW → exception. Load inside UoW scope always.
- **Second load of same ID in same UoW returns cached instance** — repository checks `unitOfWorkCallback` resources first. Mutations on the first-loaded instance are reflected; don't expect two independent copies.
- **Objenesis factory skips constructors/field init** — `ObjenesisAggregateInstanceFactory` calls no constructors. Classic `AggregateRoot` lazy-initialises its invoker in `applyRehydratedEventToTheAggregate`; custom aggregates must do same. Modern `AggregateRoot` requires ID constructor → use `reflectionBasedAggregateRootFactory()` instead.
- **`eventOrderOfLastRehydratedEvent` starts at `NO_EVENTS_PREVIOUSLY_PERSISTED`** — value is passed to `appendToStream` as optimistic-locking sentinel. Mismatch throws `OptimisticAggregateLoadException`. Don't mutate this field externally.
- **Snapshot deserialization failure → `BrokenSnapshot`** — on next `tryLoad`, broken snapshot is deleted then full event replay happens. Ensure aggregate class is backwards-compatible or handle snapshot migrations.
- **`markChangesAsCommitted()` called before `appendToStream`** — in `beforeCommit` the repo calls `markChangesAsCommitted()` first, then persists. If persist fails the events are already cleared from the aggregate; the UoW will roll back the DB transaction but the aggregate in-memory is "clean". Never reuse an aggregate object across UoW boundaries.
- **`EventStreamDecider` is experimental** — API annotated as subject to change; not covered by the no-breaking-central-API guarantee until stabilised.
- **`@EventHandler` dispatch uses `PatternMatchingMethodInvoker`** — unmatched events are silently ignored (by design). Missing handler → no state update, no exception.
- **Snapshot written after `appendToStream` in `beforeCommit`** — `aggregateSnapshotRepository.aggregateUpdated(...)` called post-persist, same UoW. If snapshot write fails, DB rolls back entirely.
- **`persist(aggregate)` is deprecated** — use `save(aggregate)`.
- **The framework never constructs `BuiltInClosingBooksPolicyEvaluator`** — no `@Bean` does; application code builds it and hands it a `currentPeriodIdProvider` (or relies on the `HasClosingBooksPeriodId` overload). Consequence: `defaultPolicy` / `timeBoundary` in `@AggregateClosingBooksPolicy` and in `essentials.eventstore.closing-books.*` are *configuration the app reads*, not something the container acts on by itself. Anything that must hold before that config is used has to be checked in `DefaultAggregateLifecycleConfigurationValidator`, which is why the period-id check lives there and needs the `period-id-provided-externally` opt-out
- **Closing-books policy validation is order-sensitive** — the validator checks, in order: `SCHEDULED_SCAN` without `FencedLockManager` → missing `TypedClosingBooksNextGenerationFactory` → `timeBoundary = NONE` → missing `HasClosingBooksPeriodId` → invalid `zoneId`. A test asserting a later failure must satisfy every earlier condition, or it trips the wrong check
- **`defaultPolicy = TIME_BOUNDARY` with `timeBoundary = NONE` would silently never roll over** — `BuiltInClosingBooksPolicyEvaluator.timeBoundaryEvaluation` short-circuits to `advancedPeriods = 0`, and `boundaryAdvanced()` is `advancedPeriods > 0`. `DefaultAggregateClosingBooksConfigurationResolver` does not substitute a boundary, so `NONE` propagates intact — hence the startup check. Same trap for `EVENT_COUNT_OR_TIME_BOUNDARY`, where only the event-count half would survive
- **Period-id format is coupled to the boundary** — `END_OF_DAY`/`EVERY_N_DAYS` → `yyyy-MM-dd`, `END_OF_WEEK` → `yyyy-Www`, `END_OF_MONTH` → `yyyy-MM`, `END_OF_YEAR` → `yyyy`. A mismatch is a runtime parse failure in `ClosingBooksTimeBoundaryCalculator`, not a startup error
- **Closing the books does not carry state forward** — the new generation starts as an empty stream. Emit an opening/carry-forward event via `TypedClosingBooksNextGenerationFactory`, or balances are lost
- **Only one generation is `OPEN` at a time** — `openNextGeneration` throws if one already is. `ClosingBooksGenerationResolver.withExclusiveAccess` exists because rollover is read-then-write; the default impl gives *no* isolation, so a resolver serving concurrent callers must override it
- **Durable snapshot jobs enqueue after commit, not during** — `DurableAsyncAggregateSnapshotRepository` defers `jobRepository.enqueue` to a `UnitOfWorkLifecycleCallback`. Moving it inline would leave orphan jobs pointing at rolled-back events
- **Re-enqueue only replaces `PARKED` rows** — `ON CONFLICT DO UPDATE WHERE status = 'PARKED'`. `PENDING`/`PROCESSING`/`FAILED` rows are deliberately left alone, so re-enqueueing is not a way to bump a live job
