# Essentials Components - Spring PostgreSQL Event Store

> **NOTE:** **The library is WORK-IN-PROGRESS**

Spring transaction integration for the PostgreSQL Event Store, enabling Event Store operations to participate in Spring-managed transactions.

**LLM Context:** [LLM-spring-postgresql-event-store.md](../../LLM/LLM-spring-postgresql-event-store.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Why Spring Transaction Integration](#why-spring-transaction-integration)
- [SpringTransactionAwareEventStoreUnitOfWorkFactory](#springtransactionawareeventstoreunitofworkfactory)
- [Transaction Management Options](#transaction-management-options)
- [Accessing the Current UnitOfWork](#accessing-the-current-unitofwork)
- [Event Lifecycle Callbacks](#event-lifecycle-callbacks)
- [Configuration Examples](#configuration-examples)
- [Related Modules](#related-modules)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required `provided` dependencies** (you must add these to your project):

```xml
<!-- Spring -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-tx</artifactId>
    <version>${spring.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
    <version>${spring.version}</version>
</dependency>

<!-- JDBI -->
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
    <version>${jdbi.version}</version>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-postgres</artifactId>
    <version>${jdbi.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

This module does not introduce additional security concerns beyond those in [postgresql-event-store](../postgresql-event-store/README.md#security).

> ⚠️ **WARNING:** See the [postgresql-event-store Security section](../postgresql-event-store/README.md#security) and **Security** notices for [components README.md](../README.md#security) for critical information about SQL injection risks with configuration parameters like `AggregateType`, table names, and column names.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## Why Spring Transaction Integration

In Spring applications, you often need Event Store operations to participate in the **same database transaction** as other operations (e.g., Spring Data repositories, JDBC templates).  
Without integration:

- Events could be persisted but other data rolled back (or vice versa)
- `@Transactional` methods wouldn't include Event Store operations
- You'd need separate transaction management for the Event Store

The `SpringTransactionAwareEventStoreUnitOfWorkFactory` solves this by **coordinating** the Event Store's `UnitOfWork` with Spring's `PlatformTransactionManager`.

**Benefits:**

| Capability | Description |
|------------|-------------|
| **Unified transactions** | Event Store operations participate in `@Transactional` boundaries |
| **Bidirectional** | Start transactions from either Spring (`@Transactional`) or Event Store (`usingUnitOfWork`) |
| **Transparent joining** | Nested `UnitOfWork` calls join existing Spring transactions |
| **Lifecycle callbacks** | Hook into before/after commit for persisted events |

---

## SpringTransactionAwareEventStoreUnitOfWorkFactory

**Class:** `SpringTransactionAwareEventStoreUnitOfWorkFactory`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring`

This is the Spring-aware implementation of `EventStoreUnitOfWorkFactory`. It extends [`SpringTransactionAwareUnitOfWorkFactory`](../foundation/README.md#unitofwork-transactions) and adds Event Store-specific capabilities:

| Feature | Description |
|---------|-------------|
| `EventStoreUnitOfWork` | Provides JDBI `Handle` access for Event Store operations |
| Event registration | Tracks `PersistedEvent` instances within the transaction |
| Lifecycle callbacks | Notifies `PersistedEventsCommitLifecycleCallback` before/after commit |

### Bean Configuration

```java
@Configuration
public class EventStoreConfig {

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
        jdbi.installPlugin(new PostgresPlugin());
        return jdbi;
    }

    @Bean
    public EventStoreUnitOfWorkFactory<SpringTransactionAwareEventStoreUnitOfWork> unitOfWorkFactory(
            Jdbi jdbi,
            PlatformTransactionManager transactionManager) {
        return new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
    }
}
```

> **Important:** Use `TransactionAwareDataSourceProxy` to wrap your `DataSource`. This ensures JDBI uses the same database connection as Spring's transaction management.

---

## Transaction Management Options

The `SpringTransactionAwareEventStoreUnitOfWorkFactory` supports **three transaction management approaches**. Choose based on your application's architecture.

### How EventStore Methods Obtain a UnitOfWork

EventStore methods like `appendToStream()` and `fetchStream()` internally call `unitOfWorkFactory.getRequiredUnitOfWork()`. This method:

1. Checks if a Spring transaction is active
2. If YES → calls `getOrCreateNewUnitOfWork()` which creates a UnitOfWork if one doesn't exist
3. If NO → throws `NoActiveUnitOfWorkException`

**Flow when using `@Transactional`:**
```
@Transactional method called
    ↓
Spring starts transaction (no UnitOfWork yet)
    ↓
eventStore.appendToStream() called
    ↓
appendToStream() calls unitOfWorkFactory.getRequiredUnitOfWork()
    ↓
getRequiredUnitOfWork() → Spring transaction IS active → calls getOrCreateNewUnitOfWork()
    ↓
getOrCreateNewUnitOfWork() → no UnitOfWork exists → creates one via createUnitOfWorkForSpringManagedTransaction()
    ↓
UnitOfWork is bound to TransactionSynchronizationManager
    ↓
appendToStream() uses the UnitOfWork to persist events
    ↓
Spring commits transaction → UnitOfWork lifecycle callbacks fire
```

See [foundation: Internal Mechanism](../foundation/README.md#internal-mechanism-getorcreatenewunitofwork) for detailed documentation of `getOrCreateNewUnitOfWork()` behavior.

### No Explicit UnitOfWork Required in @Transactional Methods

When your code runs inside a Spring-managed transaction (`@Transactional` or `TransactionTemplate`), you do **NOT** need to explicitly wrap EventStore calls with `usingUnitOfWork()`/`withUnitOfWork()`:

```java
@Service
public class OrderService {
    private final PostgresqlEventStore<?> eventStore;
    private final OrderRepository orderRepository;  // Spring Data

    @Transactional
    public void createOrderWithProjection(OrderId orderId, CustomerId customerId) {
        // Spring transaction is active - NO explicit usingUnitOfWork() needed!

        // EventStore internally calls getRequiredUnitOfWork() which auto-creates
        // a UnitOfWork joining the existing Spring transaction
        eventStore.appendToStream(ORDERS, orderId,
            new OrderCreated(orderId, customerId));

        // Spring Data repository participates in the same transaction
        orderRepository.save(new OrderProjection(orderId, customerId));

        // All operations commit or rollback together
    }
}
```

**Why this works:** EventStore methods internally call `unitOfWorkFactory.getRequiredUnitOfWork()`, which detects the active Spring transaction and automatically creates a `UnitOfWork` that participates in it.

### When to Use Explicit `usingUnitOfWork()`

Use `usingUnitOfWork()` or `withUnitOfWork()` when:

1. **No Spring transaction exists** - to start a new transaction:
   ```java
   // Not inside @Transactional - must explicitly start transaction
   public void createOrder(OrderId orderId, CustomerId customerId) {
       unitOfWorkFactory.usingUnitOfWork(() -> {
           eventStore.appendToStream(ORDERS, orderId,
               new OrderCreated(orderId, customerId));
       });
   }
   ```

2. **You need direct UnitOfWork access** - for custom JDBI queries or transaction control:
   ```java
   @Transactional
   public void complexOperation(OrderId orderId) {
       // Get the UnitOfWork for custom JDBI operations
       var uow = unitOfWorkFactory.getRequiredUnitOfWork();
       Handle handle = uow.handle();
       // ... custom JDBI queries using the same transaction
   }
   ```

---

## Accessing the Current UnitOfWork

Regardless of how the transaction was started, you can always access the current `UnitOfWork`:

```java
// Get current UnitOfWork (returns Optional)
Optional<EventStoreUnitOfWork> currentUow = unitOfWorkFactory.getCurrentUnitOfWork();

// Get required UnitOfWork (throws NoActiveUnitOfWorkException if none exists)
EventStoreUnitOfWork uow = unitOfWorkFactory.getRequiredUnitOfWork();

// Access the JDBI Handle for custom queries
Handle handle = uow.handle();
```

This is useful when you need to:
- Execute custom JDBI queries within the same transaction
- Access transaction metadata
- Perform conditional logic based on transaction state

---

## Event Lifecycle Callbacks

The `SpringTransactionAwareEventStoreUnitOfWorkFactory` supports `PersistedEventsCommitLifecycleCallback` for hooking into the event persistence lifecycle.

**Interface:** `PersistedEventsCommitLifecycleCallback`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction`

For full documentation, see [postgresql-event-store: PersistedEventsCommitLifecycleCallback](../postgresql-event-store/README.md#persistedeventscommitlifecyclecallback).

### Callback Methods

| Method | When Called | Use Case |
|--------|-------------|----------|
| `beforeCommit(EventStoreUnitOfWork, List<PersistedEvent>)` | Before transaction commits | Validation, in-transaction projections, throwing to abort |
| `afterCommit(EventStoreUnitOfWork, List<PersistedEvent>)` | After transaction commits | Notifications, async processing, external system integration |
| `afterRollback(EventStoreUnitOfWork, List<PersistedEvent>)` | After transaction rollback | Cleanup, logging, compensating actions |

### Framework Usage

`EventStoreEventBus` uses this callback internally to publish `PersistedEvents` to the event bus at each `CommitStage` (`BeforeCommit`, `AfterCommit`, `AfterRollback`).  
See [postgresql-event-store: EventBus](../postgresql-event-store/README.md#eventbus-for-in-transaction-event-publishing).

### Registering Custom Callbacks

```java
@Configuration
public class EventStoreConfig {

    @Bean
    public EventStoreUnitOfWorkFactory<?> unitOfWorkFactory(
            Jdbi jdbi,
            PlatformTransactionManager transactionManager) {
        var factory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);

        // Register lifecycle callbacks
        factory.registerPersistedEventsCommitLifeCycleCallback(new EventNotificationCallback());

        return factory;
    }
}

public class EventNotificationCallback implements PersistedEventsCommitLifecycleCallback {

    @Override
    public void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called BEFORE commit - exception here causes rollback
        events.forEach(event -> {
            log.debug("About to commit: {}", event.eventId());
        });
    }

    @Override
    public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called AFTER commit - exceptions are logged but don't affect commit
        events.forEach(event -> {
            notificationService.sendEventNotification(event);
        });
    }

    @Override
    public void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called AFTER rollback
        log.warn("Rolled back {} events", events.size());
    }
}
```

> **Note:** Exceptions in `beforeCommit` trigger a rollback. Exceptions in `afterCommit` and `afterRollback` are logged but don't affect the transaction outcome.

---

## Configuration Examples

### Minimal Configuration

```java
@SpringBootApplication
public class Application {

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        return Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
    }

    @Bean
    public EventStoreUnitOfWorkFactory<?> unitOfWorkFactory(
            Jdbi jdbi,
            PlatformTransactionManager transactionManager) {
        return new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
    }
}
```

### Full EventStore Configuration

```java
@Configuration
public class EventStoreConfig {

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());
        return jdbi;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .disable(MapperFeature.AUTO_DETECT_GETTERS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.AUTO_DETECT_CREATORS)
            .enable(MapperFeature.AUTO_DETECT_FIELDS)
            .addModule(new Jdk8Module())
            .addModule(new JavaTimeModule())
            .addModule(new EssentialTypesJacksonModule())
            .addModule(new EssentialsImmutableJacksonModule())
            .build();
    }

    @Bean
    public EventStoreUnitOfWorkFactory<?> unitOfWorkFactory(
            Jdbi jdbi,
            PlatformTransactionManager transactionManager) {
        return new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
    }

    @Bean
    public PostgresqlEventStore<?> eventStore(
            EventStoreUnitOfWorkFactory<?> unitOfWorkFactory,
            Jdbi jdbi,
            ObjectMapper objectMapper) {

        var jsonSerializer = new JacksonJSONEventSerializer(objectMapper);

        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
            jdbi,
            unitOfWorkFactory,
            new MyPersistableEventMapper(),
            SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                jsonSerializer,
                IdentifierColumnType.UUID,
                JSONColumnType.JSONB
            )
        );

        var eventStore = new PostgresqlEventStore<>(
            unitOfWorkFactory,
            persistenceStrategy,
            Optional.empty(),
            es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory)
        );

        // Register aggregate types
        eventStore.addAggregateEventStreamConfiguration(
            AggregateType.of("Orders"), OrderId.class);

        return eventStore;
    }
}
```

### Using Spring Boot Starter (Recommended)

For Spring Boot applications, prefer the [spring-boot-starter-postgresql-event-store](../spring-boot-starter-postgresql-event-store/README.md) which auto-configures the `SpringTransactionAwareEventStoreUnitOfWorkFactory` and all related beans.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

---

## Related Modules

| Module | Description |
|--------|-------------|
| [postgresql-event-store](../postgresql-event-store/README.md) | Core Event Store implementation |
| [spring-boot-starter-postgresql-event-store](../spring-boot-starter-postgresql-event-store/README.md) | Spring Boot auto-configuration |
| [foundation](../foundation/README.md#unitofwork-transactions) | Base `UnitOfWork` pattern and Spring transaction integration |
| [eventsourced-aggregates](../eventsourced-aggregates/README.md) | Aggregate patterns for Event Sourcing |

### Test References

- [`SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT.java) - Integration tests showing UnitOfWork-managed transactions
- [`SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT.java) - Integration tests showing Spring-managed transactions
- [`OrderAggregateRootRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/OrderAggregateRootRepositoryTest.java) - Base test class with comprehensive examples of both approaches
