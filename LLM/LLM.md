# Essentials - LLM Reference

> **Entry Point**: Navigate to specific modules via links below for implementation details.

Status: Work-in-progress
 
## TOC
- [Quick Facts](#quick-facts)
- [Module Categories](#module-categories)
- [Philosophy](#philosophy)
- [Module Selection Guide](#module-selection-guide)
- [Key Concepts](#key-concepts)
- [Common Use Cases](#common-use-cases)
- [Architecture Principles](#architecture-principles)
- ⚠️ [Security](#security)
- [Navigation](#navigation)

## Quick Facts

| Aspect | Details |
|--------|---------|
| **What** | Java 17+ building blocks for strongly-typed, framework-independent distributed systems |
| **GroupId** | `dk.trustworks.essentials` (core), `dk.trustworks.essentials.components` (components) |
| **License** | Apache 2.0 |
| **Spring Boot** | 3.3.x |
| **Philosophy** | Zero-dependency core, `provided` scope for integrations |
| **Scope** | Intra-service coordination (same service, shared database) |

## Module Categories

### Core Modules (Zero Dependencies)

| Module | Purpose | Detailed Docs |
|--------|---------|---------------|
| **shared** | Base utilities (Tuples, Collections, Reflection, Exceptions) | [LLM-shared.md](LLM-shared.md) |
| **types** | Strongly-typed semantic types (`SingleValueType` pattern) | [LLM-types.md](LLM-types.md) |
| **immutable** | Immutable value objects | [LLM-immutable.md](LLM-immutable.md) |
| **reactive** | Command/Event bus, reactive primitives | [LLM-reactive.md](LLM-reactive.md) |

### Type Integrations (`provided` scope)

**Overview:** [LLM-types-integrations.md](LLM-types-integrations.md)

| Module | Framework | Purpose |
|--------|-----------|---------|
| **types-jackson** | Jackson | JSON serialization |
| **types-jdbi** | JDBI v3 | SQL argument/column mapping |
| **types-avro** | Avro | Binary serialization, schema evolution |
| **types-spring-web** | Spring WebMVC/WebFlux | `@PathVariable`/`@RequestParam` conversion |
| **types-springdata-mongo** | Spring Data MongoDB | MongoDB persistence |
| **types-springdata-jpa** | Spring Data JPA | JPA persistence (experimental, may be removed) |
| **immutable-jackson** | Jackson | Immutable object deserialization |

### Components (Advanced Features)

**Overview:** [LLM-components.md](LLM-components.md)

| Category | Modules | Detailed Docs |
|----------|---------|---------------|
| **Foundation** | `foundation-types`, `foundation`, `foundation-test` | [LLM-foundation.md](LLM-foundation.md), [LLM-foundation-types.md](LLM-foundation-types.md), [LLM-foundation-test.md](LLM-foundation-test.md) |
| **PostgreSQL** | `postgresql-event-store`, `postgresql-queue`, `postgresql-distributed-fenced-lock`, `postgresql-document-db` | [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-postgresql-queue.md](LLM-postgresql-queue.md), [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md), [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) |
| **Spring** | `spring-postgresql-event-store`, `spring-boot-starter-*` | [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md), [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **MongoDB** | `springdata-mongo-queue`, `springdata-mongo-distributed-fenced-lock` | [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md), [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) |
| **Event Sourcing** | `eventsourced-aggregates`, `kotlin-eventsourcing` | [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md), [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) |
| **UI** | `vaadin-ui` | [LLM-vaadin-ui.md](LLM-vaadin-ui.md) |

### Spring Boot Starters (Auto-Configuration)

**Detailed Docs:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

| Starter | Includes | Use Case |
|---------|----------|----------|
| `spring-boot-starter-postgresql` | Jdbi, FencedLock, Queues, Inbox/Outbox, CommandBus | Microservices + PostgreSQL (not event-sourced) |
| `spring-boot-starter-postgresql-event-store` | Above + EventStore, Subscriptions, EventProcessors | Event-sourced applications + PostgreSQL |
| `spring-boot-starter-mongodb` | MongoTemplate, FencedLock, Queues, Inbox/Outbox | Microservices + MongoDB |
| `spring-boot-starter-admin-ui` | Vaadin admin views for EventStore | Monitoring/management UI |

---

## Philosophy

### Zero-Dependency Core

Core modules (`shared`, `types`, `immutable`, `reactive`) have **zero dependencies** (except SLF4J as `provided`).

### Provided Scope for Integrations

Integration modules (Jackson, JDBI, Spring, etc.) use Maven `provided` scope:
- Third-party dependencies available at **compile time only**
- **NOT transitive** - users must add dependencies themselves
- Reduces dependency conflicts, allows version control

### Intra-Service Coordination

Components designed for **intra-service** coordination:
- Multiple instances of **SAME service** sharing a database
- NOT for cross-service messaging (Sales ↔ Billing ↔ Shipping)
- For cross-service: use Kafka/RabbitMQ/Zookeeper

**Example:**
- ✅ Intra-service: 3 instances of "OrderService" sharing `orders_db`
- ❌ Cross-service: "OrderService" ↔ "PaymentService" (different databases)

---

## Module Selection Guide

### By Use Case

| Use Case | Recommended Modules | Entry Point |
|----------|---------------------|-------------|
| **Type-safe domain modeling** | `types` + framework integration | [LLM-types.md](LLM-types.md), [LLM-types-integrations.md](LLM-types-integrations.md) |
| **Event Sourcing (Spring Boot)** | `spring-boot-starter-postgresql-event-store` | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **Event Sourcing (Plain Java)** | `postgresql-event-store` + `eventsourced-aggregates` | [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) |
| **Microservices (PostgreSQL)** | `spring-boot-starter-postgresql` OR `foundation` + PostgreSQL modules | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md), [LLM-foundation.md](LLM-foundation.md) |
| **Microservices (MongoDB)** | `spring-boot-starter-mongodb` OR `foundation` + MongoDB modules | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md), [LLM-foundation.md](LLM-foundation.md) |
| **Distributed locking** | `foundation` + (`postgresql-distributed-fenced-lock` OR `springdata-mongo-distributed-fenced-lock`) | [LLM-foundation.md](LLM-foundation.md) |
| **Message queues** | `foundation` + (`postgresql-queue` OR `springdata-mongo-queue`) | [LLM-foundation.md](LLM-foundation.md) |
| **Inbox/Outbox pattern** | `foundation` + database-specific modules | [LLM-foundation.md](LLM-foundation.md) |

### By Framework

| Framework | Modules                                                    | Docs                                                                                                                         |
|-----------|------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| **Spring Boot + PostgreSQL (event-sourced)** | `spring-boot-starter-postgresql-event-store`               | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)                                                     |
| **Spring Boot + PostgreSQL (not event-sourced)** | `spring-boot-starter-postgresql`                           | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)                                                     |
| **Spring Boot + MongoDB** | `spring-boot-starter-mongodb`                              | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)                                                     |
| **Plain Java/JDBI** | Core + components + PostgreSQL modules                     | [LLM-foundation.md](LLM-foundation.md)                                                                                       |
| **Kotlin** | Core + `kotlin-eventsourcing` + `postgresql-event-store` + `postgresql-document-db` | [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md), [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) |

---

## Key Concepts

### SingleValueType Pattern

**Package:** `dk.trustworks.essentials.types`
**Docs:** [LLM-types.md](LLM-types.md)

Strongly-typed wrappers around primitive values:

```java
// Instead of: String orderId, String customerId
// Use:
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
}

public class CustomerId extends CharSequenceType<CustomerId> implements Identifier {
    public CustomerId(CharSequence value) { super(value); }
    public static CustomerId of(CharSequence value) { return new CustomerId(value); }
}

// Prevents: void placeOrder(String orderId, String customerId)
//           placeOrder(customerId, orderId); // Wrong order, compiles!

// Type-safe:
void placeOrder(OrderId orderId, CustomerId customerId)
// placeOrder(customerId, orderId); // Compile error!
```

**Base Types:**
- `CharSequenceType` - String wrappers
- `NumberType` (BigDecimal, Long, Integer) - Numeric wrappers
- `JSR310SingleValueType` (Instant, LocalDate, etc.) - Temporal wrappers

### UnitOfWork Pattern

**Package:** `dk.trustworks.essentials.components.foundation.transaction`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Technology-agnostic transaction management:

```java
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    // All operations here are transactional
    orderRepository.save(order);
    eventStore.appendToStream(aggregateType, aggregateId, events);
    durableQueues.queueMessage(queueName, message, unitOfWork);
    // Commits automatically on success, rolls back on exception
});
```

**Implementations:**
- `JdbiUnitOfWorkFactory` - Direct JDBI
- `SpringTransactionAwareJdbiUnitOfWorkFactory` - Spring + JDBI
- `SpringMongoTransactionAwareUnitOfWorkFactory` - Spring + MongoDB

### Event Store

**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`
**Docs:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md)

Full-featured event sourcing:
- Event streams (separate table per `AggregateType`)
- Durable subscriptions with gap handling
- `EventOrder` (per-aggregate, strict) + `GlobalEventOrder` (per-type, may gap)
- Interceptors, multitenancy, reactive streaming
- `EventProcessor` - Event-driven processing with guaranteed delivery and replay support

### DurableQueues

**Package:** `dk.trustworks.essentials.components.foundation.messaging.queue`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Point-to-point messaging:
- At-Least-Once delivery
- Competing consumers
- Ordered message delivery via `OrderedMessage`
- Dead Letter Queue (DLQ)
- Redelivery policies

### FencedLock

**Package:** `dk.trustworks.essentials.components.foundation.fencedlock`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Distributed locking with fence tokens (Martin Kleppmann's pattern):
- Monotonically increasing fence tokens
- Stale lock prevention
- Timeout-based release
- Intra-service only

### Inbox/Outbox

**Package:** `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Store-and-forward pattern:
- **Inbox**: Incoming messages (e.g., from Kafka) → internal processing
- **Outbox**: Internal events → external systems (e.g., to Kafka)
- Deduplication, guaranteed delivery, transactional integration

### Event-Sourced Aggregates

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates`
**Docs:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)

Domain modeling with event sourcing:
- `StatefulAggregate` - State from events
- `Aggregate` - Modern aggregate pattern
- `Decider` / `EventStreamDecider` + `EventStreamEvolver` - Functional style 
- `AggregateRepository` / `StatefulAggregateRepository` - Load/save aggregates

---

## Common Use Cases

### Event Sourcing Application

**Stack:** `spring-boot-starter-postgresql-event-store` + `eventsourced-aggregates`

```java
// 1. Define types
public class OrderId extends CharSequenceType<OrderId> { /* ... */ }

// 2. Define events
public interface OrderEvent { OrderId orderId(); }
public record OrderPlaced(OrderId orderId, CustomerId customerId) implements OrderEvent {}

// 3. Define aggregate
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    public Order(OrderId orderId, CustomerId customerId) {
        super(orderId);
        apply(new OrderPlaced(orderId, customerId));
    }

    @EventHandler
    private void on(OrderPlaced event) { /* update state */ }
}

// 4. Use repository (auto-configured by Spring Boot starter)
@Service
public class OrderService {
    private final StatefulAggregateRepository<OrderId, OrderEvent, Order> repository;

    @Transactional
    public OrderId placeOrder(CustomerId customerId) {
        var order = new Order(OrderId.random(), customerId);
        repository.save(order); // Events persisted automatically
        return order.aggregateId();
    }
}
```

**See:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md), [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### Microservices with Queues + Locks

**Stack:** `spring-boot-starter-postgresql` OR `foundation` + PostgreSQL modules

```java
@Service
public class OrderProcessor {
    private final DurableQueues queues;        // Auto-configured
    private final FencedLockManager locks;     // Auto-configured

    // Queue messages
    @Transactional
    public void scheduleProcessing(OrderId orderId) {
        queues.queueMessage(QueueName.of("orders"), new ProcessOrder(orderId), unitOfWork);
    }

    // Consume messages
    @PostConstruct
    public void startConsumer() {
        queues.consumeFromQueue(ConsumeFromQueue.builder()
            .setQueueName(QueueName.of("orders"))
            .setRedeliveryPolicy(RedeliveryPolicy.exponentialBackoff(...))
            .setParallelConsumers(3)
            .setQueueMessageHandler(msg -> processOrder(msg.payload()))
            .build());
    }

    // Distributed coordination
    @Scheduled(fixedDelay = 60000)
    public void cleanupExpiredOrders() {
        locks.tryAcquireLock(LockName.of("cleanup")).ifPresent(lock -> {
            try {
                // Only one instance executes this
                performCleanup(lock.getCurrentToken());
            } finally {
                locks.releaseLock(lock);
            }
        });
    }
}
```

**See:** [LLM-foundation.md](LLM-foundation.md), [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### Inbox/Outbox Integration

**Stack:** `foundation` + database modules

```java
Inbox orderEventsInbox = inboxes.getOrCreateInbox(
        InboxConfig.builder()
                   .setInboxName(InboxName.of("OrderService:KafkaEvents"))
                   .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                        .setRedeliveryDelay(Duration.ofMillis(100))
                                                        .setMaximumNumberOfRedeliveries(10)
                                                        .build())
                   .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                   .setNumberOfParallelMessageConsumers(5)
                   .build(),
        new PatternMatchingMessageHandler() {
            @MessageHandler
            void handle(ProcessOrderCommand cmd) {
                processOrder(cmd);
            }

            @Override
            protected void handleUnmatchedMessage(Message msg) {
                log.warn("Unknown message type");
            }
        });

// Kafka listener
@KafkaListener(topics = ORDER_EVENTS_TOPIC, groupId = "order-processing")
public void handle(OrderEvent event) {
    orderEventsInbox.addMessageReceived(new ProcessOrderCommand(event.getId()));
}
```

**See:** [LLM-foundation.md](LLM-foundation.md)

---

## Architecture Principles

### Layered Architecture

```
┌────────────────────────────────────────┐
│      Application Layer                 │
│  (Your business logic, services)       │
└────────────────────────────────────────┘
                 ↓
┌────────────────────────────────────────┐
│      Components Layer                  │
│  (EventStore, Queues, Locks, etc.)     │
└────────────────────────────────────────┘
                 ↓
┌────────────────────────────────────────┐
│      Foundation Layer                  │
│  (UnitOfWork, DurableQueues, etc.)     │
└────────────────────────────────────────┘
                 ↓
┌────────────────────────────────────────┐
│      Core Layer                        │
│  (Types, Reactive, Shared, Immutable)  │
└────────────────────────────────────────┘
```

### Key Principles

| Principle | Description                                                                        |
|-----------|------------------------------------------------------------------------------------|
| **Dependency Inversion** | Depend on abstractions, not implementations                                        |
| **Database Agnostic** | PostgreSQL primary, MongoDB alternative via interfaces                             |
| **Transaction Boundaries** | `UnitOfWork` pattern ensures consistency                                           |
| **Event-Driven** | Components communicate via events/commands `EventBus`/`CommandBus`/`EventProcessor` |
| **Type Safety** | Strong typing prevents errors, enables refactoring                                 |

---

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection names that are used with **String concatenation** → SQL/NoSQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL/NoSQL injection.

**Responsibility:** Users MUST sanitize all:
- API input parameters
- Table/column/index/function names (PostgreSQL)
- Collection names (MongoDB)
- Configuration values

**Defense:** Essentials applies naming conventions (initial layer), but **NOT exhaustive protection**.

### Safe Patterns

```java
// ✅ Safe - Controlled, predefined names
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName("durable_queues");  // Fixed string


// ✅ Safe - Validation
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
MongoUtil.checkIsValidCollectionName(collectionName);

// ❌ Dangerous - Never use user input directly
var tableName = userInput + "_events";  // SQL injection risk!
var collectionName = userInput + "_queue"; // NoSQL injection risk!
```

### Fence Token Usage

Use fence tokens to prevent stale operations:

```java
public void processOrder(OrderId orderId, long fenceToken) {
    // Include token in operations to prevent stale writes
    repository.updateWithToken(orderId, newStatus, fenceToken);
}
```

**See:** [LLM-foundation.md](LLM-foundation.md) for `FencedLock` details

---

## Navigation

### By Module Type

**Core Modules:**
- [LLM-shared.md](LLM-shared.md) - Utilities, collections, tuples
- [LLM-types.md](LLM-types.md) - Semantic types
- [LLM-immutable.md](LLM-immutable.md) - Immutable value objects
- [LLM-reactive.md](LLM-reactive.md) - Command/Event bus

**Type Integrations:**
- [LLM-types-integrations.md](LLM-types-integrations.md) - Overview + all framework integrations
- Individual: [LLM-types-jackson.md](LLM-types-jackson.md), [LLM-types-jdbi.md](LLM-types-jdbi.md), [LLM-types-avro.md](LLM-types-avro.md), etc.

**Components:**
- [LLM-components.md](LLM-components.md) - Overview + all components
- Foundation: [LLM-foundation.md](LLM-foundation.md), [LLM-foundation-types.md](LLM-foundation-types.md)
- PostgreSQL: [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-postgresql-queue.md](LLM-postgresql-queue.md), etc.
- Spring: [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)
- Event Sourcing: [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md), [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md)

### By Use Case

- **Event Sourcing:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) → [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)
- **Microservices:** [LLM-foundation.md](LLM-foundation.md) → [LLM-components.md](LLM-components.md)
- **Type Safety:** [LLM-types.md](LLM-types.md) → [LLM-types-integrations.md](LLM-types-integrations.md)
- **Spring Boot:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### README Files

For detailed explanations, motivation, and design rationale:
- [README.md](../README.md) - Project overview
- [components/README.md](../components/README.md) - Components overview
- Individual module READMEs in respective directories
