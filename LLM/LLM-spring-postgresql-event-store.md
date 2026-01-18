# Spring PostgreSQL Event Store - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/spring-postgresql-event-store/README.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring`
- **Purpose**: Spring transaction integration for PostgreSQL Event Store
- **Core EventStore docs**: [LLM-postgresql-event-store.md](./LLM-postgresql-event-store.md)
- **Key class**: `SpringTransactionAwareEventStoreUnitOfWorkFactory`
- **Enables**: EventStore operations participate in `@Transactional` boundaries
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-postgresql-event-store</artifactId>
</dependency>
```

## TOC
- [Core Classes](#core-classes)
- [Setup Patterns](#setup-patterns)
- [Transaction Management](#transaction-management)
- [API Reference](#api-reference)
- [Event Lifecycle Callbacks](#event-lifecycle-callbacks)
- [Gotchas](#gotchas)
- [Dependencies](#dependencies)
- [Test References](#test-references)

## Core Classes

**Dependencies from other modules**:
- `EventStore`, `ConfigurableEventStore`, `PersistedEvent` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `UnitOfWork`, `UnitOfWorkFactory` from [foundation](./LLM-foundation.md)

| Class | Package | Role |
|-------|---------|------|
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring` | Factory, creates UnitOfWork, registers callbacks |
| `SpringTransactionAwareEventStoreUnitOfWork` | (inner class of factory) | UnitOfWork with JDBI `Handle` + event tracking |
| `EventStoreUnitOfWorkFactory` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Interface implemented by factory |
| `EventStoreUnitOfWork` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Interface for UnitOfWork |
| `PersistedEventsCommitLifecycleCallback` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Lifecycle hook interface |

## Setup Patterns

### Minimal Bean Configuration
```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

@Bean
public Jdbi jdbi(DataSource dataSource) {
    // CRITICAL: Use TransactionAwareDataSourceProxy
    return Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
}

@Bean
public EventStoreUnitOfWorkFactory<?> unitOfWorkFactory(
        Jdbi jdbi,
        PlatformTransactionManager transactionManager) {
    return new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
}
```

### With Lifecycle Callbacks
```java
@Bean
public EventStoreUnitOfWorkFactory<?> unitOfWorkFactory(
        Jdbi jdbi,
        PlatformTransactionManager transactionManager,
        PersistedEventsCommitLifecycleCallback callback) {

    var factory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
    factory.registerPersistedEventsCommitLifeCycleCallback(callback);
    return factory;
}
```

## Transaction Management

### Pattern 1: @Transactional (Recommended)
EventStore operations auto-join Spring transactions. NO explicit `usingUnitOfWork()` needed.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;

@Service
public class OrderService {
    private final EventStore eventStore;
    private final OrderRepository orderRepository; // Spring Data

    @Transactional
    public void createOrder(OrderId orderId, CustomerId customerId) {
        // EventStore auto-joins Spring transaction
        eventStore.appendToStream(ORDERS, orderId,
            new OrderCreated(orderId, customerId));

        // Same transaction
        orderRepository.save(new OrderProjection(orderId, customerId));
    }
}
```

**Mechanism:**
1. `@Transactional` starts Spring transaction
2. `appendToStream()` calls `getRequiredUnitOfWork()`
3. Factory detects active Spring tx → auto-creates UnitOfWork
4. Both EventStore + Spring Data commit together

**See**: [foundation: getOrCreateNewUnitOfWork()](./LLM-foundation.md#unitofwork-transactions) for details.

### Pattern 2: Explicit usingUnitOfWork()
Use when NO Spring transaction exists.

```java
public void createOrder(OrderId orderId, CustomerId customerId) {
    unitOfWorkFactory.usingUnitOfWork(() -> {
        eventStore.appendToStream(ORDERS, orderId,
            new OrderCreated(orderId, customerId));
    });
}
```

### Pattern 3: TransactionTemplate
```java
public void createOrder(OrderId orderId, CustomerId customerId) {
    transactionTemplate.execute(status -> {
        eventStore.appendToStream(ORDERS, orderId,
            new OrderCreated(orderId, customerId));
        return null;
    });
}
```

### Access Current UnitOfWork
```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;

// Get optional
Optional<EventStoreUnitOfWork> currentUow = unitOfWorkFactory.getCurrentUnitOfWork();

// Get required (throws if none)
EventStoreUnitOfWork uow = unitOfWorkFactory.getRequiredUnitOfWork();

// Access JDBI Handle for custom queries
Handle handle = uow.handle();
```

## API Reference

### SpringTransactionAwareEventStoreUnitOfWorkFactory

**Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring`

**Constructor:**
```java
public SpringTransactionAwareEventStoreUnitOfWorkFactory(
    Jdbi jdbi,
    PlatformTransactionManager platformTransactionManager
)
```

**Methods:**
```java
// Register lifecycle callback
EventStoreUnitOfWorkFactory registerPersistedEventsCommitLifeCycleCallback(
    PersistedEventsCommitLifecycleCallback callback
)

// Get JDBI instance
Jdbi getJdbi()

// Inherited from base UnitOfWorkFactory:
Optional<SpringTransactionAwareEventStoreUnitOfWork> getCurrentUnitOfWork()
SpringTransactionAwareEventStoreUnitOfWork getRequiredUnitOfWork()
void usingUnitOfWork(UnitOfWorkCallback<SpringTransactionAwareEventStoreUnitOfWork> callback)
<T> T withUnitOfWork(UnitOfWorkSupplier<T, SpringTransactionAwareEventStoreUnitOfWork> supplier)
```

### SpringTransactionAwareEventStoreUnitOfWork

**Package**: Inner class of factory

**Methods:**
```java
// Get JDBI Handle for custom queries
Handle handle()

// Register persisted events (called by EventStore)
void registerEventsPersisted(List<PersistedEvent> eventsPersistedInThisUnitOfWork)

// Remove flushed events
void removeFlushedEventsPersisted(List<PersistedEvent> eventsPersistedToRemoveFromThisUnitOfWork)
void removeFlushedEventPersisted(PersistedEvent eventPersistedToRemoveFromThisUnitOfWork)
```

### PersistedEventsCommitLifecycleCallback

**Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction`

**Interface:**
```java
public interface PersistedEventsCommitLifecycleCallback {
    // Before commit - throw to rollback
    void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events);

    // After commit - exceptions logged, no rollback
    void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events);

    // After rollback
    void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events);
}
```

## Event Lifecycle Callbacks

### Callback Stages

| Method | When | Exception Behavior | Use Case |
|--------|------|-------------------|----------|
| `beforeCommit()` | Before commit | Triggers rollback | Validation, in-tx projections |
| `afterCommit()` | After commit | Logged only | External notifications, async processing |
| `afterRollback()` | After rollback | Logged only | Cleanup, logging |

### Implementation Example
```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.PersistedEventsCommitLifecycleCallback;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

@Component
public class EventPublisher implements PersistedEventsCommitLifecycleCallback {

    @Override
    public void beforeCommit(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        // Validate - throw to rollback
        events.forEach(this::validate);
    }

    @Override
    public void afterCommit(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        // Publish AFTER successful commit
        events.forEach(kafkaPublisher::publish);
    }

    @Override
    public void afterRollback(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        log.warn("Rolled back {} events", events.size());
    }
}
```

### Framework Usage
`EventStoreEventBus` uses this callback to publish events at each `CommitStage` (`BeforeCommit`, `AfterCommit`, `AfterRollback`).

**Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus`

See [postgresql-event-store: EventBus](./LLM-postgresql-event-store.md#interceptors--eventbus) for details.

## Gotchas

### ⚠️ Must Use TransactionAwareDataSourceProxy
```java
// ✅ Correct
Jdbi.create(new TransactionAwareDataSourceProxy(dataSource))

// ❌ Wrong - JDBI won't participate in Spring transactions
Jdbi.create(dataSource)
```

### ⚠️ Don't Mix Transaction Approaches
```java
// ❌ Wrong - creates nested transaction
@Transactional
public void processOrder(OrderId orderId) {
    unitOfWorkFactory.usingUnitOfWork(() -> {
        eventStore.appendToStream(ORDERS, orderId, event);
    });
}

// ✅ Correct - single transaction
@Transactional
public void processOrder(OrderId orderId) {
    eventStore.appendToStream(ORDERS, orderId, event);
}
```

### ⚠️ Exception Handling in Callbacks
- `beforeCommit()` exceptions → rollback
- `afterCommit()` exceptions → logged, transaction already committed
- Handle external failures gracefully in `afterCommit()`:

```java
public void afterCommit(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
    try {
        externalSystem.notify(events);
    } catch (Exception e) {
        // Already committed - handle gracefully
        log.error("Failed to notify", e);
        // Consider retry queue or DLQ
    }
}
```

## Dependencies

**Provided scope** - must add to your project:

```xml
<!-- Spring -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-tx</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>

<!-- JDBI -->
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-postgres</artifactId>
</dependency>
```

## Test References

Key test classes demonstrating usage patterns:

**Path**: `components/spring-postgresql-event-store/src/test/java/.../spring/`

| Test Class | Pattern Demonstrated |
|------------|---------------------|
| `SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT` | UnitOfWork-managed transactions |
| `SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT` | Spring-managed transactions |
| `OrderAggregateRootRepositoryTest` | Base test with comprehensive examples |

## See Also

| Resource | Purpose |
|----------|---------|
| [postgresql-event-store](./LLM-postgresql-event-store.md) | Core EventStore functionality |
| [foundation](./LLM-foundation.md) | Base Spring transaction integration |
| [spring-boot-starter-postgresql-event-store](./LLM-spring-boot-starter-modules.md#spring-boot-starter-postgresql-event-store) | Auto-configuration |
| [README](../components/spring-postgresql-event-store/README.md) | Full developer documentation |
