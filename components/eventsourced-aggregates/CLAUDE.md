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
| `aggregates.snapshot` | `AggregateSnapshotRepository`, `PostgresqlAggregateSnapshotRepository`, `AddNewAggregateSnapshotStrategy`, `AggregateSnapshotDeletionStrategy` |
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
| `AnnotationBasedInMemoryProjector` | Builds POJO projections from `@EventHandler` methods; registered as specific in-memory projector on `EventStore` |

## Test Structure

- `*Test` — pure unit tests, no infrastructure
- `*IT` — integration tests; all require Docker (Testcontainers `@Testcontainers` + `PostgreSQLContainer`)
- Test sub-packages mirror design flavors: `classic/`, `modern/`, `flex/`, `stateful/`, `decider/`, `eventstream/`, `snapshot/`, `projection/`
- `classic/Order.java`, `modern/Order.java`, `flex/Order.java` — representative aggregate impls; reused across IT suites
- `GivenWhenThenScenario` (in `main`) — fluent scenario builder for testing `EventStreamDecider`s without a DB

## Extension Points

- `StatefulAggregateInstanceFactory` — plug in custom aggregate construction (e.g. CDI/Spring injection at instantiation time)
- `AggregateSnapshotRepository` — implement alternative snapshot backends
- `AddNewAggregateSnapshotStrategy` — custom snapshot frequency logic
- `AggregateSnapshotDeletionStrategy` — custom retention policy
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
