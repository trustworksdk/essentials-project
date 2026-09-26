## spring-boot-starter-postgresql

Spring Boot auto-configuration for all Essentials PostgreSQL components. Maven: `spring-boot-starter-postgresql`.

## Package Structure

Single package: `dk.trustworks.essentials.components.boot.autoconfigure.postgresql`
- All production code lives here — no sub-packages
- Auto-config registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## Key Classes

| Class | Role |
|---|---|
| `EssentialsComponentsConfiguration` | `@AutoConfiguration` — wires every bean; all beans conditional on `@ConditionalOnMissingBean` → fully overridable |
| `EssentialsComponentsProperties` | `@ConfigurationProperties(prefix="essentials")` — single root for all tunable settings |
| `JdbiConfigurationCallback` | SPI: beans implementing this are called after context refresh to post-configure the shared `Jdbi` instance |
| `EssentialsSchemaConfiguration` | `@AutoConfiguration` — the `SchemaApplier` `essentials.schema.mode` selects, and `EssentialsSchemaHarnessRunner` |
| `EssentialsSchemaHarnessRunner` | `SmartInitializingSingleton` — applies every `EssentialsSchemaContributor` bean after all singletons exist, before lifecycles start; in `emit` stops the app with exit 0 on `ApplicationStartedEvent` |

**Schema mode.** Every bean that owns schema is built with `properties.getSchema().getMode().schemaOwnership()`: `COMPONENT` in `create` (the default, today's behaviour), `HARNESS` otherwise. A new bean that creates tables must do the same, or it silently creates schema in `validate` mode. Outside `create` the fenced lock manager is `build()`, not `buildAndStart()` — its confirmation thread reads the lock table at once. In `emit` the lifecycle manager starts nothing. Design: `docs/database-schema-harness.md` §8.

**Beans wired by `EssentialsComponentsConfiguration` (in dependency order):**
1. `EssentialTypesJacksonModule` / `EssentialsImmutableJacksonModule` — Jackson modules
2. `JSONSerializer` (`Jackson3JSONSerializer` via `EssentialsObjectMappers.createJSONSerializer()`) — field-visibility mapper, no getters/setters; does not collect context `JacksonModule` beans
3. `Jdbi` — wraps datasource in `TransactionAwareDataSourceProxy` + installs `PostgresPlugin`; optionally attaches `RecordSqlExecutionTimeLogger`
4. `HandleAwareUnitOfWorkFactory` (`SpringTransactionAwareJdbiUnitOfWorkFactory`) — skipped if EventStore variant on classpath
5. `FencedLockManager` (`PostgresqlFencedLockManager`) — distributed lock coordination
6. `MultiTableChangeListener` — LISTEN/NOTIFY bridge for queue wake-ups
7. `QueueStatisticsRegistry` + `StatisticsCollectingDurableQueueMessageObserver` — per-JVM delivery statistics; created *before* `DurableQueues` so the queue receives the observer, reversing the pre-0.60 direction where statistics received the queue and ran `CREATE TRIGGER` on its table
8. `DurableQueues` (`PostgresqlDurableQueues`) — message storage; centralized fetcher enabled by default; collects every `DurableQueueMessageObserver` bean via `composite(...)`
9. `Inboxes` / `Outboxes` — EIP patterns backed by `DurableQueues` + `FencedLockManager`
10. `DurableLocalCommandBus` — named `essentialsCommandBus`; auto-adds `UnitOfWorkControllingCommandBusInterceptor` unless already present
11. `LocalEventBus` — named `essentialsEventBus`; skipped if `EventStoreEventBus` on classpath
12. `LifecycleManager` (`DefaultLifecycleManager`) — triggers `JdbiConfigurationCallback` on `ContextRefreshedEvent`
13. `EssentialsScheduler` (`DefaultEssentialsScheduler`) — optional; gated on `essentials.scheduler.enabled=true`
14. `PostgresqlTTLManager` + `TTLJobBeanPostProcessor` — only when scheduler present
15. API beans: `DBFencedLockApi`, `DurableQueuesApi`, `PostgresqlQueryStatisticsApi`, `SchedulerApi`
16. Security defaults: `NoAccessSecurityProvider` + `NoAccessAuthenticatedUser` — override in app to grant real access
17. Micrometer interceptors: `RecordExecutionTimeMessageHandlerInterceptor`, `RecordExecutionTimeCommandBusInterceptor`, `RecordExecutionTimeDurableQueueInterceptor`
18. Tracing: `DurableQueuesMicrometerTracingInterceptor` + `DurableQueuesMicrometerInterceptor` — conditional on `management.tracing.enabled=true`
19. `DurableQueuesHealthIndicator` — dead-letter counts on `/actuator/health` under `durableQueues`; on by default, `UP` until an opt-in threshold is set

## Test Structure

- Single integration test: `StarterAutoConfigurationIT`
- Uses `@Testcontainers` + `PostgreSQLContainer` (postgres:latest)
- Tests use `ApplicationContextRunner` (no full Spring Boot app) — fast wiring validation
- Verifies: API beans present and functional, `EssentialsComponentsProperties` bindings

## Extension Points

| SPI | How to use |
|---|---|
| `JdbiConfigurationCallback` | `@Bean` → called post-context-refresh to install JDBI plugins/mappers |
| Any bean in `EssentialsComponentsConfiguration` | Declare own `@Bean` of same type → auto-config backs off via `@ConditionalOnMissingBean` |
| `CommandBusInterceptor` | Collected as `List<CommandBusInterceptor>` → injected into command bus |
| `DurableQueuesInterceptor` | Collected as `List<DurableQueuesInterceptor>` → added to queue |
| Extra persistence Jackson modules | Own `JSONSerializer` bean (starter backs off). `JacksonModule` beans NOT collected into persistence mapper — they reach Boot's web `JsonMapper` only |
| `EssentialsSecurityProvider` | Replace `NoAccessSecurityProvider` default to enable API access |

## Gotchas

- **EventStore classpath detection**: `unitOfWorkFactory` and `eventBus` skip themselves if EventStore variants detected via `@ConditionalOnMissingClass`. Mixing starters without the event-store starter → different UoW factory in play.
- **Security defaults are deny-all**: `NoAccessSecurityProvider` blocks all API calls out of the box. App must supply its own bean.
- **Table names are SQL-injected via string concat**: `fencedLocksTableName` and `sharedQueueTableName` go directly into SQL. `PostgresqlUtil.checkIsValidTableOrColumnName()` is first-line defense only — caller must sanitize.
- **`lockConfirmationInterval` MUST be < `lockTimeOut`**: no validation enforced; misconfiguring causes flapping lock acquisition.
- **Spring Boot DevTools classloader**: `SpringBootDevToolsClassLoaderChangeContextRefreshedListener` updates the Jackson `TypeFactory` classloader on hot reload — present only when DevTools on classpath. Without it, hot reload breaks deserialization.
- **Centralized message fetcher on by default** (`useCentralizedMessageFetcher=true`): `pollingDelayIntervalIncrementFactor` / `maxPollingInterval` only apply when fetcher disabled.
- **`immutable-jackson-module-enabled` default is `true` in properties class** but the `@ConditionalOnProperty` requires `havingValue="true"` with no `matchIfMissing` — bean created only if Objenesis on classpath AND property explicitly set to true.
- **The health indicator's enable key is `management.health.durable-queues.enabled`, and that only works because the annotation name is lowercase.** `@ConditionalOnEnabledHealthIndicator` looks the key up with the *literal* name it is given, and a camelCase lookup does not resolve a hyphenated source key even with `ConfigurationPropertySources` attached — only a lowercase lookup does. Hence `@ConditionalOnEnabledHealthIndicator("durablequeues")`, matching Boot's own `("diskspace")`. The bean method name is separately load-bearing: `HealthContributorNameGenerator` strips the `HealthIndicator` suffix, so `durableQueuesHealthIndicator` is what puts the entry under `durableQueues` in the payload. Rename either and the two silently stop agreeing.
- **`EssentialsScheduler` is enabled by default** (`SchedulerProperties.enabled=true`), but bean creation requires `essentials.scheduler.enabled=true` property — property default in Spring context is absent, so scheduler does NOT auto-start without explicit config.
