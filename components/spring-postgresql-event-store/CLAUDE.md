# spring-postgresql-event-store

Spring transaction integration for Essentials PostgreSQL EventStore. Maven: `spring-postgresql-event-store`.

Thin adapter — one class only. All event storage logic lives in `postgresql-event-store`.

## Package Structure

- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring` — production class + all tests

## Key Classes

| Class | Role |
|---|---|
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | Extends `SpringTransactionAwareUnitOfWorkFactory<PlatformTransactionManager>`, implements `EventStoreUnitOfWorkFactory`. Holds list of `PersistedEventsCommitLifecycleCallback`s; drives before/afterCommit lifecycle. |
| `SpringTransactionAwareEventStoreUnitOfWork` (inner) | Extends `SpringTransactionAwareUnitOfWork`, implements `EventStoreUnitOfWork`. Opens/closes JDBI `Handle` on start/cleanup; tracks `beforeCommitEventsPersisted` + `afterCommitEventsPersisted` lists. |

## Test Structure

- **Abstract base** `OrderAggregateRootRepositoryTest` — all test logic; subclasses supply `createUnitOfWorkFactory()`.
- **Two IT subclasses** both instantiate `SpringTransactionAwareEventStoreUnitOfWorkFactory`; differ only by annotation context (`@SpringBootTest` + `@DirtiesContext`).
  - `SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT`
  - `SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT`
- **Infrastructure**: Testcontainers `PostgreSQLContainer` (postgres:latest) via `@DynamicPropertySource`. Docker required.
- **Spring context**: minimal `ApplicationTests` `@SpringBootApplication` — wires `Jdbi` over `TransactionAwareDataSourceProxy`.
- Tests cover both Spring-managed (`TransactionTemplate`) and manually managed (`unitOfWorkFactory.usingUnitOfWork`) transaction modes.

## Extension Points

- `PersistedEventsCommitLifecycleCallback` — register via `registerPersistedEventsCommitLifeCycleCallback(callback)`. Called `beforeCommit` (throws → aborts tx) and `afterCommit` (exceptions swallowed and logged).
- `EventStoreUnitOfWorkFactory` SPI — extend `SpringTransactionAwareEventStoreUnitOfWorkFactory` to override `createUnitOfWorkForFactoryManagedTransaction` / `createUnitOfWorkForSpringManagedTransaction` if custom UoW subclass needed.

## Gotchas

- **JDBI handle lifecycle**: `handle.begin()` called in `onStart()`; handle closed in `onCleanup()` even on rollback. Handle is `null` until `onStart()` fires — `handle()` throws `IllegalStateException` if called before start.
- **beforeCommit → afterCommit promotion**: on `beforeCommitAfterCallingLifecycleCallbackResources`, events are moved from `beforeCommitEventsPersisted` to `afterCommitEventsPersisted`. If a `beforeCommit` callback throws, the list is NOT cleared and the tx aborts — don't call `registerEventsPersisted` outside active UoW.
- **afterCommit errors swallowed**: exceptions in `afterCommit` callbacks are logged at ERROR but do not re-throw. Side-effects must be idempotent.
- **`spring-tx` and `jdbi3-core` are `provided` scope** — consumer must supply matching versions.
- **`eventsourced-aggregates` is `optional`** — only needed if using `StatefulAggregateRepository`.
- `@DirtiesContext` on ITs — Spring context rebuilt per class; tests are slow if run together.
- `Jdbi` bean must wrap datasource in `TransactionAwareDataSourceProxy` so JDBI participates in Spring transactions (see `ApplicationTests`).
