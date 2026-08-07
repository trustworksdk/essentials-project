## Foundation

Cross-cutting infrastructure abstractions: transactions, distributed locking, durable queues, Inbox/Outbox EIP patterns, PG LISTEN/NOTIFY, TTL, scheduling, JSON, lifecycle. Maven: `foundation`.

## Package Structure

| Package | Contents |
|---|---|
| `foundation` (root) | `Lifecycle` interface, `IOExceptionUtil` |
| `transaction` | `UnitOfWork`, `UnitOfWorkFactory`, `UnitOfWorkLifecycleCallback` SPIs; JDBI/Mongo/Spring adapters in sub-packages |
| `fencedlock` | `FencedLockManager` SPI, `DBFencedLockManager` base, `FencedLockStorage` SPI, `DBFencedLock` |
| `messaging.queue` | `DurableQueues` SPI, `DefaultDurableQueueConsumer`, `CentralizedMessageFetcher`, `DurableQueuesInterceptor` chain |
| `messaging.queue.operations` | Command objects for every queue operation (used by interceptor chain) |
| `messaging.eip.store_and_forward` | `Inbox`/`Outbox`/`Inboxes`/`Outboxes` SPIs, `PatternMatchingMessageHandler` |
| `postgresql` | `ListenNotify`, `MultiTableChangeListener`, `PostgresqlUtil`, `NotificationDuplicationFilter` |
| `postgresql.ttl` | Postgres-specific TTL job plumbing |
| `ttl` | `TTLManager` SPI, `TTLJob`, `TTLJobDefinition`, `TTLJobBeanPostProcessor` |
| `scheduler` | `EssentialsScheduler` SPI, `DefaultEssentialsScheduler`; `pgcron` and `executor` sub-packages |
| `lifecycle` | `DefaultLifecycleManager` (Spring `SmartLifecycle` adapter) |
| `json` | `JSONSerializer` SPI, `JacksonJSONSerializer` (Jackson 2), `Jackson3JSONSerializer` (Jackson 3), `EssentialsObjectMappers`, `EssentialsJacksonModules` |
| `reactive.command` | `DurableLocalCommandBus` (reactive command bus backed by `DurableQueues`) |
| `interceptor.micrometer` | Micrometer timing interceptors for queue + command bus |
| `events` | `InfrastructureLocalEventBus` (internal event bus for infrastructure events) |
| `mongo` | `MongoUtil`, `InvalidCollectionNameException` |
| `jdbi` | `EssentialsQueryTagger` (JDBI plugin for SQL comment tagging) |

## Key Classes

| Class | Role |
|---|---|
| `Lifecycle` | Marker SPI — `start()`/`stop()`/`isStarted()`; `DefaultLifecycleManager` discovers all Spring beans implementing it |
| `UnitOfWork` | Transaction abstraction; carries `UnitOfWorkLifecycleCallback` registrations (e.g. aggregate dirty-tracking) |
| `UnitOfWorkFactory` | Creates/reuses `UnitOfWork`; `usingUnitOfWork`/`withUnitOfWork` are preferred entry points |
| `UnitOfWorkLifecycleCallback` | Hook called before/after commit+rollback for registered resources (aggregates, etc.) |
| `FencedLockManager` | Distributed lock SPI; intra-service (same DB) only |
| `DBFencedLockManager` | Base class for all DB-backed lock managers; manages confirmation thread + async acquiring |
| `FencedLockStorage` | DB-specific storage SPI implemented by Postgres/Mongo adapters |
| `DurableQueues` | Durable queue SPI; at-least-once; supports ordered messages, dead-letter, competing consumers |
| `CentralizedMessageFetcher` | Single-thread poller for Postgres; tracks in-process ordered-message keys to preserve ordering |
| `DefaultDurableQueueConsumer` | Per-consumer worker thread pool; used by Mongo and Postgres (non-centralized) |
| `DurableQueuesInterceptor` | Interceptor chain SPI wrapping every queue operation command object |
| `Outbox` / `Inbox` | Transactional store-and-forward EIP patterns; forward to `DurableQueues` internally |
| `PatternMatchingMessageHandler` | Reflective message dispatch by payload type (used by Inbox/Outbox consumers) |
| `MultiTableChangeListener` | Single poll thread for multiple PG LISTEN/NOTIFY channels; fan-out via `EventBus` |
| `ListenNotify` | Low-level helper for wiring PG triggers + NOTIFY; installs trigger functions |
| `TTLManager` | SPI for registering TTL delete jobs; backed by `EssentialsScheduler` |
| `EssentialsScheduler` | Thin scheduler abstraction over `pg_cron` or `ScheduledExecutorService` |
| `DefaultLifecycleManager` | Spring `SmartLifecycle` — discovers and starts/stops all `Lifecycle` beans |
| `JSONSerializer` | Serialization SPI; `JacksonJSONSerializer` (Jackson 2) and `Jackson3JSONSerializer` (Jackson 3) |
| `EssentialsObjectMappers` | **The** canonical persisted-JSON mapper config, for both Jackson majors. Every mapper used for persistence must come from here |
| `EssentialsJacksonModules` | Reflectively resolves the Essentials Jackson modules for the active flavor; throws on a flavor mismatch |
| `Jackson3CollectionWrapperModule` | Jackson 3 only. Pins any `Map`/`Collection` implementation that wraps one behind a final field to a delegating creator, so it keeps reading as its contents. Matched by shape, so new wrapper types are covered on arrival |
| `PostgresqlUtil` | `checkIsValidTableOrColumnName` (SQL injection guard), extension checks, version detection |
| `DurableLocalCommandBus` | Command bus backed by `DurableQueues`; durable delivery of commands |

## Test Structure

- Unit tests: plain JUnit 5 + AssertJ, no Docker (`*Test.java`)
- Integration tests: Testcontainers (`postgres:latest`) via `@Testcontainers`/`@Container` (`*IT.java`)
- `pg_cron` tests require custom image `essentials-postgres-with-pgcron:latest` (set `PGCRON_IMAGE` env var to override)
- Abstract base test classes (`AbstractEssentialsSchedulerTest`, `AbstractTTLManagerTest`) hold shared setup; `*_WithPgCron` and `*_WithExecutor` subclasses provide coverage for both scheduler backends
- `TestFencedLockManager` / `TestFencedLockManagerIT` — minimal concrete `DBFencedLockManager` impl used only in tests

## Extension Points

| SPI | Implement to... |
|---|---|
| `Lifecycle` | Participate in Spring lifecycle management (auto-discovered via `DefaultLifecycleManager`) |
| `UnitOfWork` / `UnitOfWorkFactory` | Add a new persistence backend (e.g. DynamoDB) |
| `UnitOfWorkLifecycleCallback<T>` | Hook aggregate-level commit/rollback logic into an existing UoW |
| `FencedLockManager` / `FencedLockStorage` | Add a new DB backend for distributed locks |
| `DurableQueues` | Add a new queue storage backend |
| `DurableQueuesInterceptor` | Cross-cut all queue operations (metrics, tracing, auth) |
| `JSONSerializer` | Swap Jackson for another serializer |
| `TTLManager` | Add non-Postgres TTL backend |
| `EssentialsScheduler` | Add scheduler backend beyond pg_cron / executor |
| `MessageHandlerInterceptor` | Cross-cut Inbox/Outbox message delivery |

## Gotchas

- `lockConfirmationInterval` MUST be strictly less than `lockTimeOut` — `DBFencedLockManager` does not enforce this; violation → spurious lock loss
- `DBFencedLockManager` uses hostname as default `lockManagerInstanceId`; containers without stable hostnames need explicit id
- `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation=false` means locks survive DB blips locally but risk split-brain if the DB actually moved the lock
- `CentralizedMessageFetcher` is Postgres-only; Mongo uses `DefaultDurableQueueConsumer`-per-thread approach — ordered-message key tracking differs between the two
- `OrderedMessage` ordering across multiple cluster nodes is NOT guaranteed — only within a single node
- `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense only — callers must never pass user-supplied table names directly
- `UnitOfWorkLifecycleCallback.beforeCommit` returns `BeforeCommitProcessingStatus`; returning `REQUIRED` triggers re-call — if impl always returns `REQUIRED`, infinite loop
- `MultiTableChangeListener` uses a single dedicated JDBC connection (not the pool); losing it → listener stops silently unless `Lifecycle` restart is wired
- `TTLJobBeanPostProcessor` is a Spring `BeanPostProcessor` — auto-registers `@TTLJob`-annotated beans; ordering relative to `DefaultLifecycleManager` matters
- `EssentialsScheduler` is for internal essentials use, not a general app scheduler — not a Quartz/Spring Scheduler replacement
- **Never hand-roll a persistence mapper** — use `EssentialsObjectMappers`. The exact config (field access, ISO dates, numeric Durations, Essentials modules) is a wire-format contract; a local copy that drifts silently changes persisted JSON. Pinned by `EssentialsObjectMappersWireFormatTest` in `postgresql-event-store`
- **Jackson 3 changed temporal defaults** — `WRITE_DURATIONS_AS_TIMESTAMPS` (J2 numeric `30.000000000` vs J3 `"PT30S"`) and `WRITE_DATES_AS_TIMESTAMPS` moved to `DateTimeFeature`. `EssentialsObjectMappers` pins both back to Jackson 2 behaviour so existing data stays readable, and enables `USE_BIG_DECIMAL_FOR_FLOATS` so untyped binding (used by the CDC WAL path) round-trips numbers exactly
- **Jackson 3 stopped populating final fields** — `ALLOW_FINAL_FIELDS_AS_MUTATORS` is on by default in J2, off in J3, and it is how the Objenesis immutable module fills immutable payloads. `createJackson3ObjectMapper` re-enables it. Symptom without it: a payload whose only property is a final field (J3 reads a lone single-arg constructor as a *delegating* creator, so nothing binds) deserializes to **null with no error**. Multi-arg constructors escape it only because this build passes `-parameters` — a consumer's build need not. Pinned by `ImmutablePayloadSerializationTest` in `postgresql-queue`
- **A type whose JSON form is its contents must be pinned to a delegating creator** — the flag above makes its final field a mutator, so Jackson stops seeing a map/scalar wrapper and starts seeing a bean, then calls the constructor with `null`. `Jackson3CollectionWrapperModule` covers `Map`/`Collection` implementations (`MessageMetaData`, `EventMetaData`) by shape; value types are pinned in `types-jackson3`. The break is read-only and asymmetric — serialization keeps writing the old shape — so it surfaces far from its cause: 87 `postgresql-queue` ITs on the first, an event-fetch failure on the second
- **Never annotate an Essentials type with a serialization framework annotation** — no `@JsonCreator`/`@JsonProperty` on core types. One type has to work across both Jackson majors and the non-Jackson serializers, so framework knowledge lives in the mapper layer (`MapWrapperMixIns`) or the flavor's types-jackson module
- **`types-jackson`/`types-jackson3` share FQCNs** — only one flavor is ever on the classpath, selected by `essentials.types-jackson.artifactId`. Never name those module classes from code that must compile under both; go through `EssentialsJacksonModules`
- An enforcer rule bans `foundation` from depending on the Jackson flavor modules (even test-scope) — that's why resolution is reflective, and why flavor wire-format tests live in `postgresql-event-store`
