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
5. `MongoDurableQueues` (as `DurableQueues`) — mode-switched: `FullyTransactional` uses UoW factory; `SingleOperationTransaction` uses timeout
6. `Inboxes`, `Outboxes` — durable-queue-based impls wrapping `DurableQueues` + `FencedLockManager`
7. `DurableLocalCommandBus` (bean name `essentialsCommandBus`) — always adds `UnitOfWorkControllingCommandBusInterceptor` unless user provides one
8. `LocalEventBus` (bean name `essentialsEventBus`)
9. `JSONSerializer` (Jackson-based) — skipped if `JSONEventSerializer` from postgresql event store is on classpath
10. `LifecycleManager` (`DefaultLifecycleManager`)
11. Micrometer interceptors: `DurableQueuesMicrometerTracingInterceptor`, `DurableQueuesMicrometerInterceptor` — conditional on `management.tracing.enabled=true`
12. Measurement interceptors: `RecordExecutionTime*Interceptor` for queues, command bus, message handlers
13. `ReactiveHandlersBeanPostProcessor` — auto-registers `@EventHandler`/`@CommandHandler` beans; disable via `essentials.reactive-bean-post-processor-enabled=false`
14. `SpringBootDevToolsClassLoaderChangeContextRefreshedListener` — conditional on DevTools presence; resets Jackson classloader on context refresh

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
| Extra Jackson modules | Register `com.fasterxml.jackson.databind.Module` beans; auto-collected and added to `ObjectMapper` |

## Gotchas

- `MongoFencedLockManager` calls `buildAndStart()` at bean creation → lock manager starts immediately during context refresh, before `LifecycleManager` kicks in.
- `DurableQueues` `TransactionalMode` default is `SingleOperationTransaction` (not `FullyTransactional`). In `SingleOperationTransaction` mode, message handling timeout (default 30 s) governs redelivery — no UoW participation.
- `jsonSerializer` bean has `@ConditionalOnMissingClass("...JSONEventSerializer")` — if postgresql event store starter is also on classpath, it wins and this bean is skipped entirely.
- `EssentialsImmutableJacksonModule` has dual conditions: Objenesis must be on classpath AND `essentials.immutable-jackson-module-enabled=true` (default: property key absent → `havingValue="true"` means it is NOT auto-enabled unless property is explicitly set).
- Collection names (`fencedLocksCollectionName`, `sharedQueueCollectionName`) are used verbatim in MongoDB queries → `MongoUtil#checkIsValidCollectionName` is first-line defense only; never source these from untrusted input.
- `SpringBootDevToolsClassLoaderChangeContextRefreshedListener` resets the Jackson `ObjectMapper` classloader on every `ContextRefreshedEvent` — relevant only in dev; production classloaders are stable.
- `UnitOfWorkControllingCommandBusInterceptor` is added to command bus automatically unless user's interceptor list already contains an instance of that class — checked by `isAssignableFrom`, so subclassing also suppresses auto-add.
- `essentials.reactive.event-bus-parallel-threads` defaults to `min(availableProcessors, 4)` — on high-core machines this caps throughput; tune explicitly for high-volume event processing.
