# Spring PostgreSQL Event Store - LLM Reference

> See [README](../components/spring-postgresql-event-store/README.md) for detailed explanations and motivation.

## TOC
- [Quick Facts](#quick-facts)
- [Core Classes](#core-classes)
- [Bean Configuration](#bean-configuration)
- [Transaction Patterns](#transaction-patterns)
- [API Reference](#api-reference)
- [Event Lifecycle Callbacks](#event-lifecycle-callbacks)
- [Gotchas](#gotchas)
- [Dependencies](#dependencies)
- [Test References](#test-references)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring`
- **Purpose**: Spring transaction integration for PostgreSQL Event Store
- **Core EventStore docs**: [LLM-postgresql-event-store.md](./LLM-postgresql-event-store.md)
- **Key class**: `SpringTransactionAwareEventStoreUnitOfWorkFactory`
- **Enables**: EventStore operations to participate in `@Transactional` boundaries
- **Status**: WORK-IN-PROGRESS

## Core Classes

| Class | Package | Role |
|-------|---------|------|
| `SpringTransactionAwareEventStoreUnitOfWorkFactory` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring` | Main factory, creates UnitOfWork instances |
| `SpringTransactionAwareEventStoreUnitOfWork` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring` (inner class) | UnitOfWork with JDBI `Handle` and event tracking |
| `EventStoreUnitOfWorkFactory` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Interface implemented by factory |
| `EventStoreUnitOfWork` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Interface for UnitOfWork |
| `PersistedEventsCommitLifecycleCallback` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction` | Lifecycle hook interface |

## Bean Configuration

### Minimal Setup
```java
@Configuration
public class EventStoreConfig {

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
}
```

### With Callbacks
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

## Transaction Patterns

### Pattern 1: @Transactional (Recommended)
```java
@Service
public class OrderService {
    private final PostgresqlEventStore<?> eventStore;
    private final OrderRepository orderRepository; // Spring Data

    @Transactional
    public void createOrder(OrderId orderId, CustomerId customerId) {
        // NO explicit usingUnitOfWork() needed
        // EventStore auto-joins Spring transaction
        eventStore.appendToStream(ORDERS, orderId,
            new OrderCreated(orderId, customerId));

        // Same transaction
        orderRepository.save(new OrderProjection(orderId, customerId));
    }
}
```

**How it works:**
1. `@Transactional` starts Spring transaction
2. `appendToStream()` → `getRequiredUnitOfWork()`
3. Factory detects active Spring transaction
4. Auto-creates `UnitOfWork` joining that transaction

### Pattern 2: Explicit usingUnitOfWork()
```java
// Use when NO Spring transaction and no UnitOfWork exists 
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

## API Reference

### SpringTransactionAwareEventStoreUnitOfWorkFactory

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

// Get current UnitOfWork (inherited from base)
Optional<SpringTransactionAwareEventStoreUnitOfWork> getCurrentUnitOfWork()

// Get required UnitOfWork - throws if none exists (inherited)
SpringTransactionAwareEventStoreUnitOfWork getRequiredUnitOfWork()

// Start UnitOfWork (inherited)
void usingUnitOfWork(UnitOfWorkCallback<SpringTransactionAwareEventStoreUnitOfWork> callback)

// Start UnitOfWork with return value (inherited)
<T> T withUnitOfWork(UnitOfWorkSupplier<T, SpringTransactionAwareEventStoreUnitOfWork> supplier)
```

### SpringTransactionAwareEventStoreUnitOfWork

**Methods:**
```java
// Get JDBI Handle for custom queries
Handle handle()

// Register persisted events (called by EventStore)
void registerEventsPersisted(List<PersistedEvent> eventsPersistedInThisUnitOfWork)

// Remove flushed events (multiple)
void removeFlushedEventsPersisted(List<PersistedEvent> eventsPersistedToRemoveFromThisUnitOfWork)

// Remove flushed event (single)
void removeFlushedEventPersisted(PersistedEvent eventPersistedToRemoveFromThisUnitOfWork)
```

### PersistedEventsCommitLifecycleCallback

**Interface:**
```java
public interface PersistedEventsCommitLifecycleCallback {
    // Called before commit - throw to rollback
    void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events);

    // Called after successful commit - exceptions logged, no rollback
    void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events);

    // Called after rollback
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
@Component
public class EventPublisher implements PersistedEventsCommitLifecycleCallback {

    @Override
    public void beforeCommit(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        // Validate before commit - throw to rollback
        events.forEach(this::validate);
    }

    @Override
    public void afterCommit(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        // Publish to external systems AFTER successful commit
        events.forEach(kafkaPublisher::publish);
    }

    @Override
    public void afterRollback(EventStoreUnitOfWork uow, List<PersistedEvent> events) {
        log.warn("Rolled back {} events", events.size());
    }
}
```

### Framework Usage
`EventStoreEventBus` (from `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus`) uses this callback to publish events at each `CommitStage`.

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
- Always handle external failures gracefully in `afterCommit()`

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

Key test classes in `components/spring-postgresql-event-store/src/test/java/`:
- `SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT` - UnitOfWork-managed transactions
- `SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT` - Spring-managed transactions
- `OrderAggregateRootRepositoryTest` - Comprehensive examples

## See Also

| Resource | Purpose |
|----------|---------|
| [postgresql-event-store](./LLM-postgresql-event-store.md) | Core EventStore functionality |
| [foundation](./LLM-foundation.md) | Base Spring transaction integration |
| [spring-boot-starter-postgresql-event-store](./LLM-spring-boot-starter-modules.md#spring-boot-starter-postgresql-event-store) | Auto-configuration |
| [README](../components/spring-postgresql-event-store/README.md) | Full developer documentation |
