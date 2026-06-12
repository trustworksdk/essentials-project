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
| `json` | `JSONSerializer` SPI, `JacksonJSONSerializer` |
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
| `JSONSerializer` | Serialization SPI; `JacksonJSONSerializer` is the only production impl |
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
