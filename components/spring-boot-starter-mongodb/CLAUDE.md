## spring-boot-starter-mongodb

Spring Boot auto-configuration wiring all MongoDB-backed Essentials components into a single starter. Maven: `spring-boot-starter-mongodb`.

## Package Structure

Single package: `dk.trustworks.essentials.components.boot.autoconfigure.mongodb`

- `EssentialsComponentsConfiguration` — `@AutoConfiguration` class; all bean definitions live here
- `EssentialsComponentsProperties` — `@ConfigurationProperties(prefix = "essentials")` with nested inner classes per subsystem
- `AdditionalCharSequenceTypesSupported` — marker bean; app registers this to inject extra `CharSequenceType<?>` classes into `MongoCustomConversions`
- `AdditionalConverters` — marker bean; app registers this to inject extra `Converter`/`GenericConverter` instances into `MongoCustomConversions`

Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Key Classes

| Class | Role |
|---|---|
| `EssentialsComponentsConfiguration` | Sole source of `@Bean` definitions; all `@ConditionalOnMissingBean` → user override replaces entire bean |
| `EssentialsComponentsProperties` | Binds `essentials.*` YAML/properties; inner classes: `FencedLockManager`, `DurableQueues`, `LifeCycleProperties`, `ReactiveProperties`, `MicrometerTaggingProperties`, `EssentialsComponentsMetricsProperties` |
| `AdditionalCharSequenceTypesSupported` | Extension point — holds `List<Class<? extends CharSequenceType<?>>>` merged into `SingleValueTypeConverter` |
| `AdditionalConverters` | Extension point — holds `List<?>` of converters merged into `MongoCustomConversions` |

Beans wired (in order of dependency):
1. `MongoCustomConversions` — `SpringDataJavaTimeCodecs` + `SingleValueTypeConverter(LockName, QueueEntryId, QueueName, ...extras)`
2. `MongoTransactionManager` — `ReadConcern.SNAPSHOT` + `WriteConcern.ACKNOWLEDGED` (hardcoded, override via `@ConditionalOnMissingBean`)
3. `SpringMongoTransactionAwareUnitOfWorkFactory`
4. `MongoFencedLockManager` (as `FencedLockManager`) — calls `buildAndStart()` at construction
5. `MongoDurableQueues` (as `DurableQueues`) — built with the UoW factory and the message-handling timeout; every queue op is its own transaction (no `TransactionalMode` since 0.60). Collects every `DurableQueueMessageObserver` bean via `composite(...)`
6. `Inboxes`, `Outboxes` — durable-queue-based impls wrapping `DurableQueues` + `FencedLockManager`
7. `DurableLocalCommandBus` (bean name `essentialsCommandBus`) — always adds `UnitOfWorkControllingCommandBusInterceptor` unless user provides one
8. `LocalEventBus` (bean name `essentialsEventBus`)
9. `JSONSerializer` (Jackson-based) — skipped if `JSONEventSerializer` from postgresql event store is on classpath
10. `LifecycleManager` (`DefaultLifecycleManager`)
11. Micrometer interceptors: `DurableQueuesMicrometerTracingInterceptor`, `DurableQueuesMicrometerInterceptor` — conditional on `management.tracing.enabled=true`
12. Measurement interceptors: `RecordExecutionTime*Interceptor` for queues, command bus, message handlers
13. `ReactiveHandlersBeanPostProcessor` — auto-registers `@EventHandler`/`@CommandHandler` beans; disable via `essentials.reactive-bean-post-processor-enabled=false`
14. `SpringBootDevToolsClassLoaderChangeContextRefreshedListener` — conditional on DevTools presence; resets Jackson classloader on context refresh
15. `MicrometerDurableQueueMessageObserver` — `essentials.messaging.durable_queues.dead_lettered` counter; registered on `MeterRegistry` presence, NOT behind `essentials.metrics.durable-queues.enabled`
16. `DurableQueuesHealthIndicator` — dead-letter counts on `/actuator/health` under `durableQueues`; on by default, `UP` until `essentials.durable-queues.health.dead-letter-threshold` is set positive

## Test Structure

No tests in this module (pure auto-configuration glue). Integration tests live in consumer modules (`springdata-mongo-queue`, `springdata-mongo-distributed-fenced-lock`). Testcontainers MongoDB dependency is declared for downstream test use.

## Extension Points

| Mechanism | How |
|---|---|
| Override any bean | Declare own `@Bean` of same type; `@ConditionalOnMissingBean` on all auto-configured beans |
| Extra `CharSequenceType` converters | Register `AdditionalCharSequenceTypesSupported` bean |
| Extra Mongo converters | Register `AdditionalConverters` bean |
| Extra `DurableQueuesInterceptor`s | Register one or more as beans; auto-collected via `List<DurableQueuesInterceptor>` injection |
| Extra `CommandBusInterceptor`s | Register as beans; auto-collected via `List<CommandBusInterceptor>` |
| Custom command queue | Register `QueueName` bean and/or `RedeliveryPolicy` bean |
| Custom error handling | Register `SendAndDontWaitErrorHandler` bean or `OnErrorHandler` bean |
| Extra persistence Jackson modules | Define own `JSONSerializer` bean (e.g. `new Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(extraModules))`); starter backs off. `JacksonModule` beans are NOT collected into persistence mapper (they go to Boot's web `JsonMapper`) |

## Gotchas

- `MongoFencedLockManager` calls `buildAndStart()` at bean creation → lock manager starts immediately during context refresh, before `LifecycleManager` kicks in.
- `jsonSerializer` bean has `@ConditionalOnMissingClass("...JSONEventSerializer")` — if postgresql event store starter is also on classpath, it wins and this bean is skipped entirely.
- `EssentialsImmutableJacksonModule` has dual conditions: Objenesis must be on classpath AND `essentials.immutable-jackson-module-enabled=true` (default: property key absent → `havingValue="true"` means it is NOT auto-enabled unless property is explicitly set).
- Collection names (`fencedLocksCollectionName`, `sharedQueueCollectionName`) are used verbatim in MongoDB queries → `MongoUtil#checkIsValidCollectionName` is first-line defense only; never source these from untrusted input.
- `SpringBootDevToolsClassLoaderChangeContextRefreshedListener` resets the Jackson `ObjectMapper` classloader on every `ContextRefreshedEvent` — relevant only in dev; production classloaders are stable.
- `UnitOfWorkControllingCommandBusInterceptor` is added to command bus automatically unless user's interceptor list already contains an instance of that class — checked by `isAssignableFrom`, so subclassing also suppresses auto-add.
- **The statistics registry is deliberately still absent here, unlike the observer and the counter.** `durableQueues` now collects `List<DurableQueueMessageObserver>` into `composite(...)` and `MicrometerDurableQueueMessageObserver` is registered on `MeterRegistry` presence, matching the Postgres starter. `QueueStatisticsRegistry` and `StatisticsCollectingDurableQueueMessageObserver` are not, because their only consumer is `DefaultDurableQueuesApi.getQueueStatistics` and **this starter registers no `*Api` beans and no `EssentialsSecurityProvider` at all**. Adding the registry alone would accumulate counters nothing here can read. `DefaultDurableQueuesApi` is DB-agnostic, so the blocker is a scope decision about giving this starter an admin surface (including the deny-all security default), not a technical one.
- `essentials.reactive.event-bus-parallel-threads` defaults to `min(availableProcessors, 4)` — on high-core machines this caps throughput; tune explicitly for high-volume event processing.
