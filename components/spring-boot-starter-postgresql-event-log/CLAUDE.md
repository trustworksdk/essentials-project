# spring-boot-starter-postgresql-event-log

Spring Boot auto-configuration for the new event store engine (`postgresql-event-log`) and its `EventStore` adapter
(`postgresql-event-log-integration`). **Preview**, off by default. Design: `docs/plans/event-store-engine-phase10.md` §3.

## What it wires (`EventLogAutoConfiguration`, only with `essentials.event-log.enabled=true`)

| Bean | Notes |
|---|---|
| `EventLog` | `destroyMethod = "stop"`; **started in the bean method**, because `registerAggregateType` requires a started engine and aggregate types register while the context is built. Schema ownership = `essentials.schema.mode`'s (`create` → `COMPONENT`, else `HARNESS`) |
| `EventLogSchemaContributor` | `essentials.event-log.initialize-schema` (default `true`). Required by `HARNESS`: with it off, every mode but `create` fails to build the `EventLog` |
| `EventLogEventStore` (`essentialsEventStore`) | `@ConditionalOnMissingBean(EventStore.class)`; enrichers, interceptors, optional `TenantSerializer` from the context |
| `EventStoreUnitOfWorkFactory`, `EventStoreEventBus`, `JSONEventSerializer`, `PersistableEventMapper`, causation enricher / queue interceptor / command propagator | Built exactly as the old `EventStoreConfiguration` builds them, each `@ConditionalOnMissingBean` |
| `PageTransactionSource` | `SpringPageTransactions` with a `PlatformTransactionManager`, else `UnitOfWorkPageTransactions` |
| `EventLogProcessorLifecycle` | `SmartLifecycle` that registers each `ViewProjection` / `EventReactor` bean's aggregate type, starts it, stops them before the `EventLog`; nothing in schema mode `emit` |
| `EventLogProcessorDependencies` | For `EventLogViewProcessor` / `EventLogReactor` subclass beans. Those are foundation `Lifecycle`s: the base starter's `DefaultLifecycleManager` starts them (off in `emit`), not `EventLogProcessorLifecycle` |
| `EventLogSubscriptionsApi` | `EventLogAdminApiAutoConfiguration`, with the admin API starter and an `EssentialsSecurityProvider` |

## Gotchas

- **`EventLogImportFilter` (registered in `META-INF/spring.factories`) drops the old starter's `EventStoreConfiguration` while enabled — and only that.** Otherwise its subscription manager, gap handler and CDC beans would be built on the adapter, whose polling methods throw. `SnapshotConfiguration`, `ClosingBooksConfiguration` and the aggregate admin configurations stay; that is why this configuration declares the unit-of-work factory, event bus, serializer and mapper, and is ordered `beforeName` both (their beans are `@ConditionalOnBean` of them). `EventLogImportFilterTest` guards both halves.
- **`spring-postgresql-event-store` on the class path makes `spring-boot-starter-postgresql` back off** from its own unit-of-work factory, event bus and JSON serializer (`@ConditionalOnMissingClass`). This starter must therefore declare them; removing one leaves the context without it.
- **Properties mirror `EventLogSettings` one-to-one, defaults as literals** so the metadata carries them. `EssentialsEventLogPropertiesTest` keeps them equal to `EventLogSettings.defaults()` and checks every property reaches its component — add a property there when the record grows.
- **The admin API starter dependency is `provided`** — it drags in the old event-store starter. So the old starter is on this module's test class path: ITs exercise the coexistence case by default.
- `MissingDependencyFailureAnalyzer` is registered here too, as in every starter; Spring Boot dedups it.
- Builds need `-DskipDependencyCheck=true` when the NVD feed rate-limits (429).

## Tests

Unit: `EssentialsEventLogPropertiesTest`, `EventLogImportFilterTest`, `MissingDependencyFailureAnalyzerRegistrationTest`.

Integration (one static container, `public` schema recreated per test): `EventLogAutoConfigurationIT`, `EventLogSchemaModeIT`,
`EventLogAdminApiIT`. They import every auto-configuration on the class path through `@EnableAutoConfiguration`, never
`AutoConfigurations.of(...)` alone: only the former consults `AutoConfigurationImportFilter`s, so only it exercises
`EventLogImportFilter`. The must-fail arm imports the configurations directly and shows the context failing on the
duplicate `essentialsEventStore`; removing the filter from `spring.factories` fails four of the IT's tests.

- **Aggregate types register while the context is built, before the schema harness runs** (`EssentialsSchemaHarnessRunner`
  runs after the singletons). Rules that keep that safe: in `validate` the `EventLog` bean validates the engine's fixed
  schema through the `SchemaApplier` bean before it starts, so a missing schema fails as `SchemaValidationException`
  (otherwise `EventLogSchemaContributor.attach` would create the pending type tables first and fail with the database's
  error); in `emit` the contributor bean is `scriptOnly()`, so a type's tables go into the script and nothing is written
  to the database. `EventLogSchemaModeIT` covers both with a type registered during the build.
- **Processors register their own aggregate type** before subscribing: `EventLogProcessorLifecycle` for `ViewProjection` /
  `EventReactor` beans, the base classes at `start()`. Registration creates the engine's default tables (in `create`).
- The tests need `micrometer-core` and `spring-boot-health` (test scope): `EssentialsComponentsConfiguration` cannot be
  introspected without them.
