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
| `messaging.queue.observability` | `QueueStatisticsRegistry`, `QueueStatistics`, `StatisticsCollectingDurableQueueMessageObserver` — per-JVM delivery counters |
| `messaging.eip.store_and_forward` | `Inbox`/`Outbox`/`Inboxes`/`Outboxes` SPIs, `PatternMatchingMessageHandler` |
| `postgresql` | `ListenNotify`, `MultiTableChangeListener`, `PostgresqlUtil`, `NotificationDuplicationFilter`, `PostgresqlCreateSchemaApplier` |
| `postgresql.ttl` | Postgres-specific TTL job plumbing |
| `ttl` | `TTLManager` SPI, `TTLJob`, `TTLJobDefinition`, `TTLJobBeanPostProcessor` |
| `schema` | Schema harness SPI (0.60, in progress): `EssentialsSchemaContributor`, `SchemaChange`, `SchemaApplier`, `EssentialsSchemaHarness`, `SchemaOrder`. Plan: `docs/database-schema-harness.md` |
| `scheduler` | `EssentialsScheduler` SPI, `DefaultEssentialsScheduler`; `pgcron` and `executor` sub-packages |
| `lifecycle` | `DefaultLifecycleManager` (Spring `SmartLifecycle` adapter) |
| `json` | `JSONSerializer` SPI, `Jackson3JSONSerializer`, `EssentialsObjectMappers`, `EssentialsJacksonModules` |
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
| `DurableQueueMessageObserver` | SPI notified of how a delivery *ended* — handled / retried / dead-lettered / redelivery-requested. Multi-slot: `composite(List)`, `safe(...)`, `none()` |
| `MessageDeliveryClassifier` | The single copy of the retry-vs-dead-letter rule, called by both consumers |
| `QueueStatisticsRegistry` | In-memory per-`QueueName` delivery counters, capped by `maxTrackedQueues`. Shaped like the event store's `SubscriptionStatisticsRegistry` |
| `MicrometerDurableQueueMessageObserver` | `essentials.messaging.durable_queues.dead_lettered` counter, tagged `queue_name`/`message_payload_type`/`reason`. Registered on `MeterRegistry` presence, NOT behind the execution-time metrics toggle |
| `DurableQueuesHealthIndicator` | Per-queue dead-letter counts on `/actuator/health` under `durableQueues`. Reports `UP` until an opt-in per-queue threshold is set; caches its result. Lives here (not in a starter) because both starters register it; `spring-boot-health` is an `optional` dependency, like Micrometer |
| `Outbox` / `Inbox` | Transactional store-and-forward EIP patterns; forward to `DurableQueues` internally |
| `PatternMatchingMessageHandler` | Reflective message dispatch by payload type (used by Inbox/Outbox consumers) |
| `MultiTableChangeListener` | Single poll thread for multiple PG LISTEN/NOTIFY channels; fan-out via `EventBus` |
| `ListenNotify` | Low-level helper for wiring PG triggers + NOTIFY; installs trigger functions |
| `TTLManager` | SPI for registering TTL delete jobs; backed by `EssentialsScheduler` |
| `EssentialsScheduler` | Thin scheduler abstraction over `pg_cron` or `ScheduledExecutorService` |
| `DefaultLifecycleManager` | Spring `SmartLifecycle` — discovers and starts/stops all `Lifecycle` beans |
| `JSONSerializer` | Serialization SPI; impl `Jackson3JSONSerializer` |
| `EssentialsObjectMappers` | **The** canonical persisted-JSON mapper config (Jackson 3; byte-identical to 0.50's Jackson 2 output). Every mapper used for persistence must come from here |
| `EssentialsJacksonModules` | `modules()` reflectively resolves `types-jackson3`/`immutable-jackson3` modules when present; throws `IllegalStateException` if a 0.50-era Jackson 2 `types-jackson`/`immutable-jackson` jar (same FQCN) is on classpath |
| `Jackson3CollectionWrapperModule` | Pins any `Map`/`Collection` implementation that wraps one behind a final field to a delegating creator, so it keeps reading as its contents. Matched by shape, so new wrapper types are covered on arrival |
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
| `DurableQueueMessageObserver` | Observe delivery outcomes (statistics, metrics). Set via `PostgresqlDurableQueuesBuilder.setMessageObserver` / `MongoDurableQueues.setMessageObserver` |
| `JSONSerializer` | Swap Jackson for another serializer |
| `TTLManager` | Add non-Postgres TTL backend |
| `EssentialsScheduler` | Add scheduler backend beyond pg_cron / executor |
| `MessageHandlerInterceptor` | Cross-cut Inbox/Outbox message delivery |

## Gotchas

- `lockConfirmationInterval` MUST be strictly less than `lockTimeOut` — `DBFencedLockManager` does not enforce this; violation → spurious lock loss
- `DBFencedLockManager` uses hostname as default `lockManagerInstanceId`; containers without stable hostnames need explicit id
- `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation=false` means locks survive DB blips locally but risk split-brain if the DB actually moved the lock
- **A throwing `lockAcquired` callback releases the lock, deliberately.** The lock is recorded as owned *before* the callback runs, so keeping it after a failure is the worst outcome available: this instance owns a lock it is not serving, no other instance can take it, and no later tick calls the callback again — the next tick sees the lock already held here and takes neither branch. The only trace was one log line reading "Technical error while trying to acquire lock", which misdescribes it: the acquisition succeeded. Releasing makes it retry every tick, so a transient cause self-heals without a restart and a permanent one logs at a paced interval. Found via `Inbox` in `SingleGlobalConsumer` mode, which does all its consumer wiring inside `onLockAcquired`. Guarded by `a_failing_lockAcquired_callback_releases_the_lock_so_the_next_attempt_can_retry` in the reusable base IT, verified to fail without the release
- `FencedLock.release()` does NOT stop an `acquireLockAsync` background acquirer — the next tick re-acquires the freed lock with the next token. Use `cancelAsyncLockAcquiring(lockName)` to hand a lock over. `releaseLock` is also not under the manager's `reentrantLock`, so the release and the re-acquire genuinely interleave
- `CentralizedMessageFetcher` is Postgres-only; Mongo uses `DefaultDurableQueueConsumer`-per-thread approach — ordered-message key tracking differs between the two
- `OrderedMessage` ordering across multiple cluster nodes is NOT guaranteed — only within a single node
- **`QueueMessageBuilder.setMessage` must not rebuild the message.** It used to split it into payload + metadata and construct a fresh `Message` in `build()`, which silently downgraded an `OrderedMessage` to unordered — no error, just ordering guarantees quietly not applying. Every `EventProcessor` forwarding path goes through `DurableQueues.queueMessage(...)`, which now uses this builder. Pinned by `QueueMessageBuilderTest`.
- **The 0.40.x `forRemoval` constructors are gone from the queue packages.** Each builder's target constructor survives as package-private; the telescoping overloads are deleted. `DefaultDurableQueueConsumer` takes only `(ConsumeFromQueue, DurableQueueConsumerDependencies)` — the 7-collaborator form is gone, so subclasses must use the bundle.
- **No `@Deprecated(forRemoval = true)` members are left in main code since 0.60.** A builder's target constructor is package-private; `@SuppressWarnings("removal")` is gone with them - do not reintroduce it to call a narrowed constructor from another package, use the builder
- **`DurableQueueMessageObserver` is notified from the two consumers, not from an interceptor.** An interceptor sees the operation, not the outcome: `chain.proceed()` on `HandleQueuedMessage` covers the handler invocation only, and `AcknowledgeMessageAsHandled`/`DeleteMessage` carry nothing but a `QueueEntryId`. An interceptor-based collector would need an in-flight map, a cap and a sweep. `messageHandled` fires *after* the acknowledgement, so it means "delivered and removed". Administrative operations (`deleteMessage`, `purgeQueue`) deliberately do not notify — counting them is how the removed statistics trigger reported a 100 000-row purge as 100 000 deliveries.
- **`DurableQueuesHealthIndicator` must not be made to report `DOWN` by default.** A `HealthIndicator` is not only a signal — it contributes to the composite `/actuator/health` status, and deployments routinely point a Kubernetes readiness or liveness probe at that endpoint. Going `DOWN` on a dead letter would remove working pods from service, or restart them, leaving fewer consumers to drain the queue behind the poison message. So it reports `UP` regardless of the counts until `essentials.durable-queues.health.dead-letter-threshold` is set positive, matching `CdcHealthIndicator`, which only goes `DOWN` under `CdcMode.REQUIRE`. The pure alarm is the Micrometer counter, which cannot actuate anything and is therefore unconditional.
- **The health indicator caches, and the threshold is per queue.** Each computation costs one query for the queue names plus one count per queue; probes poll on a timer from every instance, so an uncached indicator would scale database load with probe frequency. A per-*total* threshold was rejected because the number an operator picks would then mean something different in an application with more queues.
- **A read failure reports `UNKNOWN`, not `DOWN`.** An unreachable database is not a statement about dead letters and is already Spring Boot's `DataSource` indicator's job; `UNKNOWN` does not drag the aggregated status down while any other contributor is definite.
- **The dead-letter counter is not gated by `essentials.metrics.durable-queues.enabled`.** That property controls execution-time measurement. A dead letter is an incident; a timing switch must not be able to turn its counter off. Don't "tidy" it under the same toggle.
- **`MicrometerDurableQueueMessageObserver` reads the payload type without deserializing.** A message can be dead-lettered precisely because its payload will not deserialize — an observer that throws while reporting that reports nothing. Failure falls back to the `unknown` tag value.
- **The observer is always wrapped in `safe(...)` by the setters.** It runs on delivery threads, so it must never throw and must never block. First failure per observer logs at WARN, later ones at DEBUG.
- **`QueueStatistics.lastFailureReason` renders the root cause, not the throwable as given.** A handler throw always arrives wrapped (`UnitOfWorkException → … → yours`), so rendering the outermost type would make every queue read "UnitOfWorkException: …".
- **Delivery-failure classification lives in one place: `MessageDeliveryClassifier`.** `DefaultDurableQueueConsumer` and `CentralizedMessageFetcher` both call it; neither carries its own copy any more. It returns a `MessageDeliveryDecision` (outcome + which rule fired + matched type + cause-chain depth + attempt count), and `describe()` is what the consumers put in the dead-letter log line.
- **The built-in permanent-error list is applied after the policy, and three of its five types are not overridable.** `DurableQueueDeserializationException`, `MismatchedInputException` and `NoClassDefFoundError` can never succeed on a later attempt, so a `MessageDeliveryVerdict.RETRY` cannot resurrect them — retrying one forever blocks the head of an ordered queue. `IllegalArgumentException` (incl. `NumberFormatException`) and `ClassCastException` *are* overridable by an explicit `alwaysRetryOn(...)`. That matters because `FailFast.requireNonNull`/`requireTrue` and Kotlin `require(...)` throw `IllegalArgumentException` across 2000+ call sites, so without the opt-out the house validation idiom dead-letters a message on first delivery.
- **`alwaysRetry()` is not `alwaysRetryOn(everything)`.** It maps to `NO_OPINION`, so the built-in list still applies. Only the explicit `alwaysRetryOn(...)` list yields `RETRY`. Promoting the builder default would make deserialization failures retry forever in every existing application.
- **`MessageDeliveryErrorHandler.verdict(...)` is a `default` method**, mapping `isPermanentError`'s `true`/`false` onto `PERMANENT_ERROR`/`NO_OPINION`. Every pre-0.60 implementation keeps working unchanged, and that mapping is correct for a handler that has not considered `RETRY` — `false` from such a handler means "no opinion", not "retry".
- **The whole cause chain is classified, outermost match first.** Before 0.60 only the thrown exception and `Exceptions.getRootCause` were tested, so classification silently depended on whether a handler attached a cause. The walk is guarded against cyclic chains (`Throwable.initCause` rejects self-causation but not longer cycles) and capped at 100 links.
- **Jackson's `MismatchedInputException` is matched by class name, not `instanceof`.** Jackson databind is optional here, so an `instanceof` would fail to link on a runtime without it. The check walks the candidate's own superclass chain, which loads nothing new.
- `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense only — callers must never pass user-supplied table names directly
- `UnitOfWorkLifecycleCallback.beforeCommit` returns `BeforeCommitProcessingStatus`; returning `REQUIRED` triggers re-call — if impl always returns `REQUIRED`, infinite loop
- **`@MessageHandler(unitOfWork = NONE)` is introspected, never declared by hand.** `MessageHandlerMethods` is the single place that reads the annotation; `UnitOfWorkBoundaryOwningMessageConsumer.hasNonTransactionalMessageHandlers()` is a `default` method over it. Only a consumer whose handler methods live on *another* object overrides it — introspecting the wrapper finds no handler methods at all, which is why `AbstractEventProcessor.EventReferenceResolvingMessageConsumer` answers for its `PatternMatchingMessageHandler` delegate. The marker interface itself is **not** derivable from annotations: it declares who commits, and `EventProcessor`'s consumer owns the boundary even when every handler is `REQUIRED` (phase 1 resolves the event reference in its own `UnitOfWork`)
- **A dispatcher that cannot offer a `UnitOfWork`-free window rejects `NONE` rather than ignoring it** — `Inbox` (consumer doesn't own the boundary and a `UnitOfWorkFactory` is present), `Outbox` (always, it has no boundary-owning consumer variant), `PatternMatchingQueuedMessageHandler` (always, at construction). All three skip the check when no `UnitOfWorkFactory` exists, because nothing wraps the delivery then and the handler does get its window. Guards pinned by `MessageHandlerMethodsTest`, `InboxNonTransactionalMessageHandlerGuardTest`, `OutboxNonTransactionalMessageHandlerGuardTest`, `PatternMatchingQueuedMessageHandlerTest`
- `MultiTableChangeListener` uses a single dedicated JDBC connection (not the pool); losing it → listener stops silently unless `Lifecycle` restart is wired
- **`DefaultLifecycleManager.stop()` isolates each bean; `start()` deliberately does not.** The beans are stopped serially in one `forEach`, so an exception used to abandon every bean after it — silently, and in whatever order `getBeansOfType` returned. The case that reaches it is the one where stopping matters most: a database that has gone away, where several beans release leases, locks or replication slots and the first to fail takes the rest of the shutdown with it (symptom: a process that logs part of a shutdown and keeps running). A failed stop is logged at ERROR, never propagated — nothing above it can act on it. `start()` keeps failing fast: a bean that cannot start should fail the context rather than leave the application up as though it had. `a_bean_that_throws_while_stopping_does_not_stop_the_beans_after_it` pins it, with a `LinkedHashMap` because the order *is* the hazard
- `TTLJobBeanPostProcessor` is a Spring `BeanPostProcessor` — auto-registers `@TTLJob`-annotated beans; ordering relative to `DefaultLifecycleManager` matters
- `EssentialsScheduler` is for internal essentials use, not a general app scheduler — not a Quartz/Spring Scheduler replacement
- **Never hand-roll a persistence mapper** — use `EssentialsObjectMappers`. The exact config (field access, ISO dates, numeric Durations, Essentials modules) is a wire-format contract; a local copy that drifts silently changes persisted JSON. Pinned by `EssentialsObjectMappersWireFormatTest` in `postgresql-event-store`
- **Jackson 3 changed temporal defaults** — `WRITE_DURATIONS_AS_TIMESTAMPS` (J2 numeric `30.000000000` vs J3 `"PT30S"`) and `WRITE_DATES_AS_TIMESTAMPS` moved to `DateTimeFeature`. `EssentialsObjectMappers` pins both back to Jackson 2 behaviour so existing data stays readable, and enables `USE_BIG_DECIMAL_FOR_FLOATS` so untyped binding (used by the CDC WAL path) round-trips numbers exactly
- **Jackson 3 stopped populating final fields** — `ALLOW_FINAL_FIELDS_AS_MUTATORS` is on by default in J2, off in J3, and it is how the Objenesis immutable module fills immutable payloads. `createJackson3ObjectMapper` re-enables it. Symptom without it: a payload whose only property is a final field (J3 reads a lone single-arg constructor as a *delegating* creator, so nothing binds) deserializes to **null with no error**. Multi-arg constructors escape it only because this build passes `-parameters` — a consumer's build need not. Pinned by `ImmutablePayloadSerializationTest` in `postgresql-queue`
- **A type whose JSON form is its contents must be pinned to a delegating creator** — the flag above makes its final field a mutator, so Jackson stops seeing a map/scalar wrapper and starts seeing a bean, then calls the constructor with `null`. `Jackson3CollectionWrapperModule` covers `Map`/`Collection` implementations (`MessageMetaData`, `EventMetaData`) by shape; value types are pinned in `types-jackson3`. The break is read-only and asymmetric — serialization keeps writing the old shape — so it surfaces far from its cause: 87 `postgresql-queue` ITs on the first, an event-fetch failure on the second
- **Never annotate an Essentials type with a serialization framework annotation** — no `@JsonCreator`/`@JsonProperty` on core types. One type has to work with Jackson and the non-Jackson serializers, so framework knowledge lives in the mapper layer (`EssentialsObjectMappers`, `Jackson3CollectionWrapperModule`) or `types-jackson3`
- **`types-jackson3`/`immutable-jackson3` reuse 0.50's Jackson 2 FQCNs** — a stale 0.50 jar on classpath looks identical by name; `EssentialsJacksonModules` checks the module's Jackson major and fails loudly. Go through it, never name the module classes from foundation
- `enforce-module-dependency-direction` bans `foundation` from depending on `types-jackson3`/`immutable-jackson3` (even test-scope) — that's why resolution is reflective, and why wire-format tests live in `postgresql-event-store`
