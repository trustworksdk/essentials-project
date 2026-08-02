# spring-boot-starter-postgresql-event-store

Spring Boot autoconfiguration for `PostgresqlEventStore` and CDC. Maven: `spring-boot-starter-postgresql-event-store`.

Depends on `spring-boot-starter-postgresql` (common Essentials Spring wiring). Single autoconfiguration class registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Package Structure

| Package | Contents |
|---|---|
| `...boot.autoconfigure.postgresql.eventstore` | `EventStoreConfiguration` (all beans), `EssentialsEventStoreProperties` (props), `EventStoreNotifyPollingBootstrap` (S1 notify-trigger wiring) |
| `...boot.autoconfigure.postgresql.eventstore.health` | `CdcHealthIndicator` — Actuator `/actuator/health/cdc` |

## Key Classes

| Class | Internal role |
|---|---|
| `EventStoreConfiguration` | Single `@AutoConfiguration` — wires every event-store and CDC bean via `@ConditionalOnMissingBean`; all CDC beans are additionally gated on `essentials.eventstore.cdc.enabled=true` |
| `EssentialsEventStoreProperties` | `@ConfigurationProperties(prefix="essentials.eventstore")` — identifierColumnType, jsonColumnType, gap handler, metrics, subscriptionManager, cdc (delegates to `CdcProperties`) |
| `EventStoreNotifyPollingBootstrap` | S1 NOTIFY wake-up: on construction calls `strategy.enableNotifyTriggerInstallation(...)` which installs `pg_notify` AFTER INSERT trigger + registers table with `MultiTableChangeListener`. Uses advisory lock to serialise DDL across JVMs |
| `CdcHealthIndicator` | Actuator health: ACTIVE→UP, FAILED→DOWN (REQUIRE mode) or UP (PREFER/OPTIONAL), INACTIVE→UP. Includes tailer LSN + dispatcher started details |

## Test Structure

Two ITs in `...boot.autoconfigure.postgresql.eventstore`:

- `StarterAutoConfigurationIT` — verifies default bean graph: `EventStoreApi`, `CdcApi`, `MeterRegistry` wiring, `CdcHealthIndicator` present. Uses `ApplicationContextRunner` + Testcontainers `PostgreSQLContainer`.
- `NotifyPollingAutoConfigurationIT` — S1-specific: asserts no S1 beans without flag; with `notify-polling.enabled=true` asserts `NotifyEpochSource` + bootstrap wired; end-to-end epoch advance; CDC+notify-polling coexistence.

Both ITs use `ApplicationContextRunner` (not `@SpringBootTest`) — context starts/stops per test case, allowing property variation without container restart.

## Extension Points

Override any `@ConditionalOnMissingBean` to replace defaults:

- `PersistableEventMapper` — custom event→`PersistableEvent` mapping (enrich meta, correlation, tenant)
- `PersistableEventEnricher` (list) — post-mapper enrichment chain; discovered automatically
- `EventStoreInterceptor` (list) — interceptors added to event store; all discovered beans are added
- `EventStoreSubscriptionMonitor` (list) — plugged into `EventStoreSubscriptionMonitorManager`
- `WalMessageFilter` — replace default `DefaultWalMessageFilter` (wal2json) or `PgOutputRawPayloadFilter` (pgoutput) with custom WAL message pre-filter
- `WalReplicationTailerErrorHandler` — optional bean; injected into `WalReplicationTailer` if present
- `LogicalDecodingPlugin` (named `configuredLogicalDecodingPlugin`) — swap decoding plugin; default selects wal2json or pgoutput based on `essentials.eventstore.cdc.plugin`
- `CdcSlotNameProvider` — override slot name derivation (default: `DefaultCdcSlotNameProvider` from JDBC URL database name)
- `OnErrorHandler` — optional; injected into `EventStoreEventBus` if present

## Gotchas

- **CDC enabled by default** (`matchIfMissing=true`). All CDC beans start unless explicitly disabled. Disabling requires `essentials.eventstore.cdc.enabled=false`.
- **Replication DataSource** created separately from the main `DataSource` — uses `PGSimpleDataSource` with `replication=database` + `preferQueryMode=simple` + `assumeMinServerVersion=17`. Parsed from `spring.datasource.url`; fails fast if URL absent.
- **CDC + Notify-polling coexistence** — both can be enabled but WARN logged: CDC INBOX already delivers wake-up equivalent; running both adds DB load (trigger fire + LISTEN connection) for no liveness gain.
- **`AggregateTypeResolver` and `WalMessageFilter` use live suppliers**, not snapshots — aggregates registered at runtime via `addAggregateEventStreamConfiguration(...)` become visible to CDC conversion and WAL filtering. Using snapshot would silently drop WAL events for late-registered aggregates.
- **CdcHealthIndicator** only registered when `management.health.cdc.enabled` is true (default) AND CDC enabled. FAILED state maps to DOWN only in `REQUIRE` mode; PREFER/OPTIONAL degrade gracefully → UP.
- **Notify-polling optimizer fallback** — log name format `EventStream:<aggregateType>:<subscriberId>` parsed to find table; unknown format or unregistered aggregate type → falls back to `JitteredEventStorePollingOptimizer`, not `EventStorePollingOptimizer.None()` (which would peg the DB).
- **`@Primary` on `cdcEventStore`** — when CDC enabled, `CdcEventStore` wraps `ConfigurableEventStore` and is registered as `@Primary`. Inject `EventStore` (not `ConfigurableEventStore`) to get CDC-wrapped instance; inject `@Qualifier("essentialsEventStore")` to bypass CDC wrapping.
- **Jackson config** — `JSONEventSerializer` disables getter/setter visibility; uses field + creator visibility only. Adding `com.fasterxml.jackson.databind.Module` beans auto-registers them. The mapper comes from `EssentialsObjectMappers`; never hand-build one, or the persisted format drifts.
- **This module must declare `tools.jackson` itself**, even though it never imports it. It builds on `spring-boot-starter-postgresql`, whose `EssentialsComponentsConfiguration` has `@Bean` methods returning the Essentials Jackson module types — under the Jackson 3 flavour those extend `tools.jackson…SimpleModule`, and Spring loads that hierarchy just to introspect the class. The sibling declares it `provided`, which is not transitive. Symptom: every IT fails with `BeanDefinitionStoreException: Failed to parse configuration class`, i.e. the whole auto-configuration, not one bean.
- **Both Jackson flavors supported.** `jsonSerializer` branches on `EssentialsJacksonModules.isJackson3Flavor()`: Jackson 2 collects `Module` beans as before (unchanged for existing users), Jackson 3 delegates to `EssentialsJSONEventSerializers`. A Jackson 3 app that needs extra modules defines its own `JSONEventSerializer` bean.
- **CDC runs on both Jackson majors.** CDC beans take `JSONEventSerializer`, never the concrete `JacksonJSONEventSerializer` — typing them on the concrete class is what previously made a Jackson 3 context fail with an opaque `NoSuchBeanDefinitionException`. The `walMessageFilter` bean goes through `WalMessageFilters.createForActiveJacksonFlavor(...)`. Verified end-to-end on both majors: wal2json, pgoutput, and poison/gap ITs.
