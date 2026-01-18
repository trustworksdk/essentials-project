# Essentials - LLM Reference

> **Navigation Index**: Entry point to module-specific documentation. For implementation details, see linked module docs.

## TOC
- [Quick Facts](#quick-facts)
- [Module Index](#module-index)
- [Module Selection Guide](#module-selection-guide)
- [Core Concepts](#core-concepts)
- [Common Patterns](#common-patterns)
- [Architecture](#architecture)
- ⚠️ [Security](#security)
- [Navigation](#navigation)

## Quick Facts

| Aspect | Details                                                                                |
|--------|----------------------------------------------------------------------------------------|
| **What** | Java 17+ building blocks for strongly-typed, framework-independent distributed systems |
| **GroupId** | `dk.trustworks.essentials` (core), `dk.trustworks.essentials.components` (components)  |
| **License** | Apache 2.0                                                                             |
| **Spring Boot** | 3.3.x+                                                                                 |
| **Philosophy** | Zero-dependency core, `provided` scope integrations                                    |
| **Scope** | Intra-service coordination (same service, shared DB)                                   |

## Module Index

### Quick Reference

| Index | Purpose | Docs |
|-------|---------|------|
| **Types & Components Index** | Alphabetical lookup of all types/interfaces by use case | [LLM-types-index.md](LLM-types-index.md) |
| **Components Overview** | Consolidated view of all component modules | [LLM-components.md](LLM-components.md) |

### Core Modules (Zero Dependencies)

| Module | Package | Purpose                                                                           | Docs |
|--------|---------|-----------------------------------------------------------------------------------|------|
| **shared** | `dk.trustworks.essentials.shared` | Tuples, Collections, Reflection, Exceptions, FailFast | [LLM-shared.md](LLM-shared.md) |
| **types** | `dk.trustworks.essentials.types` | `SingleValueType` pattern for semantic types | [LLM-types.md](LLM-types.md) |
| **immutable** | `dk.trustworks.essentials.immutable` | Immutable value objects | [LLM-immutable.md](LLM-immutable.md) |
| **reactive** | `dk.trustworks.essentials.reactive` | `EventBus`, `CommandBus` | [LLM-reactive.md](LLM-reactive.md) |

### Type Integrations (`provided` scope)

**Overview:** [LLM-types-integrations.md](LLM-types-integrations.md)

| Module | Framework | Purpose | Docs |
|--------|-----------|---------|------|
| **types-jackson** | Jackson | JSON serialization | [LLM-types-jackson.md](LLM-types-jackson.md) |
| **types-jdbi** | JDBI v3 | SQL argument/column mapping | [LLM-types-jdbi.md](LLM-types-jdbi.md) |
| **types-avro** | Avro | Binary serialization, schema evolution | [LLM-types-avro.md](LLM-types-avro.md) |
| **types-spring-web** | Spring WebMVC/WebFlux | `@PathVariable`/`@RequestParam` conversion | [LLM-types-spring-web.md](LLM-types-spring-web.md) |
| **types-springdata-mongo** | Spring Data MongoDB | MongoDB persistence | [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md) |
| **types-springdata-jpa** | Spring Data JPA | JPA persistence (experimental) | [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md) |
| **immutable-jackson** | Jackson | Immutable object deserialization | [LLM-immutable-jackson.md](LLM-immutable-jackson.md) |

### Components (Advanced Features)

**Package Base:** `dk.trustworks.essentials.components`

Consolidated view of all component modules [LLM-components.md](LLM-components.md)

| Component | Purpose                                                                               | Docs |
|-----------|---------------------------------------------------------------------------------------|------|
| **foundation-types** | Common types (`CorrelationId`, `EventId`, `AggregateType`, etc.) | [LLM-foundation-types.md](LLM-foundation-types.md) |
| **foundation** | `FencedLock`, `DurableQueues`, `UnitOfWork`, `Inbox`/`Outbox`, `DurableLocalCommandBus` | [LLM-foundation.md](LLM-foundation.md) |
| **foundation-test** | Internal Test utilities and abstractions  | [LLM-foundation-test.md](LLM-foundation-test.md) |
| **postgresql-event-store** | `EventStore` with subscriptions, `EventProcessor`| [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) |
| **eventsourced-aggregates** | `StatefulAggregate`, `Aggregate`, `Decider`, Repository patterns | [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) |
| **spring-postgresql-event-store** | Spring transaction integration for `EventStore` | [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md) |
| **postgresql-distributed-fenced-lock** | Distributed locking via PostgreSQL | [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md) |
| **postgresql-queue** | Durable queues with PostgreSQL | [LLM-postgresql-queue.md](LLM-postgresql-queue.md) |
| **postgresql-document-db** | Document database using PostgreSQL | [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) |
| **springdata-mongo-distributed-fenced-lock** | Distributed locking via Spring Data MongoDB | [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) |
| **springdata-mongo-queue** | Durable queues with Spring Data MongoDB | [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md) |
| **kotlin-eventsourcing** | Kotlin DSL for event sourcing | [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) |
| **vaadin-ui** | Vaadin UI components | [LLM-vaadin-ui.md](LLM-vaadin-ui.md) |

### Spring Boot Starters

**Docs:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

| Starter | Includes | Use Case |
|---------|----------|----------|
| `spring-boot-starter-postgresql` | Jdbi, FencedLock, Queues, Inbox/Outbox, CommandBus | Microservices + PostgreSQL |
| `spring-boot-starter-postgresql-event-store` | Above + EventStore, Subscriptions, EventProcessors | Event-sourced apps + PostgreSQL |
| `spring-boot-starter-mongodb` | MongoTemplate, FencedLock, Queues, Inbox/Outbox | Microservices + MongoDB |
| `spring-boot-starter-admin-ui` | Vaadin admin views for EventStore | Monitoring/management UI |

---

## Module Selection Guide

### By Use Case

| Use Case | Modules | Entry Point |
|----------|---------|-------------|
| **Type-safe domain modeling** | `types` + framework integrations | [LLM-types.md](LLM-types.md) |
| **Event Sourcing (Spring Boot)** | `spring-boot-starter-postgresql-event-store` | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **Event Sourcing (Plain Java)** | `postgresql-event-store` + `eventsourced-aggregates` | [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) |
| **Microservices (PostgreSQL)** | `spring-boot-starter-postgresql` OR `foundation` + PostgreSQL modules | [LLM-foundation.md](LLM-foundation.md) |
| **Microservices (MongoDB)** | `spring-boot-starter-mongodb` OR `foundation` + MongoDB modules | [LLM-foundation.md](LLM-foundation.md) |
| **Distributed locking** | `foundation` + DB-specific lock module | [LLM-foundation.md](LLM-foundation.md) |
| **Message queues** | `foundation` + DB-specific queue module | [LLM-foundation.md](LLM-foundation.md) |
| **Inbox/Outbox** | `foundation` + DB modules | [LLM-foundation.md](LLM-foundation.md) |

### By Framework

| Framework | Modules | Docs |
|-----------|---------|------|
| **Spring Boot + PostgreSQL (event-sourced)** | `spring-boot-starter-postgresql-event-store` | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **Spring Boot + PostgreSQL (CRUD)** | `spring-boot-starter-postgresql` | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **Spring Boot + MongoDB** | `spring-boot-starter-mongodb` | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **Plain Java/JDBI** | Core + components + PostgreSQL modules | [LLM-foundation.md](LLM-foundation.md) |
| **Kotlin** | Core + `kotlin-eventsourcing` + `postgresql-event-store` | [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) |

---

## Core Concepts

### SingleValueType Pattern

**Package:** `dk.trustworks.essentials.types`
**Docs:** [LLM-types.md](LLM-types.md)

Strongly-typed wrappers eliminating primitive obsession:

```java
// Define types
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
}

// Type-safe APIs prevent argument swapping
void placeOrder(OrderId orderId, CustomerId customerId)  // Compile-time safe
// placeOrder(customerId, orderId);  // Compile error!
```

**Base Types:** `CharSequenceType`, `NumberType`, `JSR310SingleValueType`

### UnitOfWork Pattern

**Package:** `dk.trustworks.essentials.components.foundation.transaction`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Technology-agnostic transactions:

```java
unitOfWorkFactory.usingUnitOfWork(uow -> {
    repository.save(order);
    eventStore.appendToStream(type, id, events);
    durableQueues.queueMessage(queueName, msg, uow);
    // Auto-commit on success, rollback on exception
});
```

**Implementations:** `JdbiUnitOfWorkFactory`, `SpringTransactionAwareJdbiUnitOfWorkFactory`, `SpringMongoTransactionAwareUnitOfWorkFactory`

### Event Store

**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`
**Docs:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md)

Event sourcing infrastructure:
- Event streams (table per `AggregateType`)
- Durable subscriptions with gap handling
- `EventOrder` (per-aggregate) + `GlobalEventOrder` (per-type)
- `EventProcessor` - Event-driven processing with replay support

### DurableQueues

**Package:** `dk.trustworks.essentials.components.foundation.messaging.queue`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Point-to-point messaging:
- At-Least-Once delivery
- Competing consumers
- Ordered delivery via `OrderedMessage`
- Dead Letter Queue (DLQ)

### FencedLock

**Package:** `dk.trustworks.essentials.components.foundation.fencedlock`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Distributed locking (Martin Kleppmann's fencing pattern):
- Monotonic fence tokens
- Stale lock prevention
- Timeout-based release
- Intra-service only

### Inbox/Outbox

**Package:** `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`
**Docs:** [LLM-foundation.md](LLM-foundation.md)

Store-and-forward pattern:
- **Inbox**: External → internal (e.g., Kafka → app)
- **Outbox**: Internal → external (e.g., app → Kafka)
- Deduplication, guaranteed delivery

### Event-Sourced Aggregates

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates`
**Docs:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)

Domain modeling:
- `StatefulAggregate` - State from events
- `Aggregate` - Modern aggregate pattern
- `Decider` / `EventStreamDecider` + `EventStreamEvolver` - Functional style
- `AggregateRepository` / `StatefulAggregateRepository` - Load/save

---

## Common Patterns

### Event Sourcing Application

**Stack:** `spring-boot-starter-postgresql-event-store` + `eventsourced-aggregates`

```java
// 1. Define semantic type
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.generate()); }
}

// 2. Define events
public interface OrderEvent { OrderId orderId(); }
public record OrderPlaced(OrderId orderId, CustomerId customerId) implements OrderEvent {}
public record OrderConfirmed(OrderId orderId) implements OrderEvent {}

// 3. Define stateful aggregate
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    private boolean confirmed;

    public Order() {}  // Deserialization
    public Order(OrderId id) { super(id); }  // Rehydration
    public Order(OrderId id, CustomerId customerId) {
        super(id);
        apply(new OrderPlaced(id, customerId));
    }

    public void confirm() {
        if (confirmed) return;  // Idempotent
        apply(new OrderConfirmed(aggregateId()));
    }

    @EventHandler
    private void on(OrderPlaced e) { this.confirmed = false; }

    @EventHandler
    private void on(OrderConfirmed e) { this.confirmed = true; }
}

// 4. Configure repository
@Bean
public StatefulAggregateRepository<OrderId, OrderEvent, Order> orderRepository(
        ConfigurableEventStore<?> eventStore) {
    return StatefulAggregateRepository.from(
        eventStore,
        AggregateType.of("Orders"),
        StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
        Order.class
    );
}

// 5. Use in service
@Service
public class OrderService {
    @Autowired
    private StatefulAggregateRepository<OrderId, OrderEvent, Order> repository;

    @Transactional
    public OrderId placeOrder(CustomerId customerId) {
        var order = new Order(OrderId.random(), customerId);
        repository.save(order);
        return order.aggregateId();
    }

    @Transactional
    public void confirmOrder(OrderId orderId) {
        var order = repository.load(orderId);
        order.confirm();  // Auto-persisted on tx commit
    }
}
```

**See:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md), [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### Microservices with Queues + Locks

**Stack:** `spring-boot-starter-postgresql`/`spring-boot-starter-mongodb` OR `foundation` + PostgreSQL/MongoDB modules

```java
@Service
public class OrderProcessor {
    private final DurableQueues queues;
    private final FencedLockManager locks;

    @Transactional
    public void scheduleProcessing(OrderId orderId) {
        queues.queueMessage(QueueName.of("orders"), new ProcessOrder(orderId));
    }

    @PostConstruct
    public void startConsumer() {
        queues.consumeFromQueue(ConsumeFromQueue.builder()
            .setQueueName(QueueName.of("orders"))
            .setRedeliveryPolicy(RedeliveryPolicy.exponentialBackoff(...))
            .setParallelConsumers(3)
            .setQueueMessageHandler(msg -> processOrder(msg.payload()))
            .build());
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupExpiredOrders() {
        locks.tryAcquireLock(LockName.of("cleanup")).ifPresent(lock -> {
            try {
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

**Stack:** `foundation` + DB modules

```java
// Configure inbox
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
        void handle(ProcessOrderCommand cmd) { processOrder(cmd); }

        @Override
        protected void handleUnmatchedMessage(Message msg) {
            log.warn("Unknown message type");
        }
    });

// Kafka listener feeds inbox
@KafkaListener(topics = ORDER_EVENTS_TOPIC, groupId = "order-processing")
public void handle(OrderEvent event) {
    orderEventsInbox.addMessageReceived(new ProcessOrderCommand(event.getId()));
}
```

**See:** [LLM-foundation.md](LLM-foundation.md)

---

## Architecture

### Dependency Philosophy

| Principle | Description |
|-----------|-------------|
| **Zero-dependency core** | Core modules have no dependencies (except SLF4J `provided`) |
| **Provided scope integrations** | Third-party libs as `provided` - NOT transitive |
| **Intra-service coordination** | Same service, shared DB - NOT cross-service messaging |
| **Transaction boundaries** | `UnitOfWork` pattern ensures consistency |
| **Type safety** | Strong typing prevents errors, enables refactoring |

### Layered Structure

```
┌─────────────────────────────────┐
│    Application Layer            │
│  (Business logic, services)     │
└─────────────────────────────────┘
             ↓
┌─────────────────────────────────┐
│    Components Layer             │
│  (EventStore, EventProcessors)  │
└─────────────────────────────────┘
             ↓
┌─────────────────────────────────┐
│    Foundation Layer             │
│  (UnitOfWork, Queues, Locks)    │
└─────────────────────────────────┘
             ↓
┌─────────────────────────────────┐
│    Core Layer                   │
│  (Types, Reactive, Shared)      │
└─────────────────────────────────┘
```

### Intra-Service Scope

✅ **Designed for:** Multiple instances of SAME service sharing a database
❌ **NOT for:** Cross-service messaging (Sales ↔ Billing ↔ Shipping)

**Example:**
- ✅ 3 instances of "OrderService" sharing `orders_db`
- ❌ "OrderService" ↔ "PaymentService" (use Kafka/RabbitMQ/API)

---

## Security

### ⚠️ SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection names with are used with **String concatenation** → SQL/NoSQL injection risk.

**User Responsibility:** Sanitize ALL:
- API input parameters
- Table/column/index/function names (PostgreSQL)
- Collection names (MongoDB)
- Configuration values

**Defense:** Essentials applies naming convention validation (initial layer), **NOT exhaustive protection**.

### Safe Patterns

```java
// ✅ Safe - Fixed string
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName("durable_queues");

// ✅ Safe - Initial Layer Validation
dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Basic validation
dk.trustworks.essentials.components.foundation.mongo.MongoUtil.checkIsValidCollectionName(collectionName);     // Basic validation

// ❌ Dangerous - User input
var tableName = userInput + "_events";  // SQL injection!
```

### What Validation Does NOT Protect Against

#### PostgreSQL
- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

#### MongoDB
- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

### Fence Token Usage

Include fence tokens in operations to prevent stale writes:

```java
public void processOrder(OrderId orderId, long fenceToken) {
    repository.updateWithToken(orderId, newStatus, fenceToken);
}
```

**See:** [LLM-foundation.md](LLM-foundation.md) for `FencedLock` details

---

## Navigation

### Quick Reference

- [LLM-types-index.md](LLM-types-index.md) - **Types & Components Index** (alphabetical lookup by use case)
- [LLM-components.md](LLM-components.md) - **Components Overview** (consolidated component documentation)

### By Module Type

**Core:**
- [LLM-shared.md](LLM-shared.md)
- [LLM-types.md](LLM-types.md)
- [LLM-immutable.md](LLM-immutable.md)
- [LLM-reactive.md](LLM-reactive.md)

**Type Integrations:**
- [LLM-types-integrations.md](LLM-types-integrations.md) - Overview + all integrations
- Individual: [LLM-types-jackson.md](LLM-types-jackson.md), [LLM-types-jdbi.md](LLM-types-jdbi.md), [LLM-types-avro.md](LLM-types-avro.md), [LLM-types-spring-web.md](LLM-types-spring-web.md), [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md), [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md)

**Components:**
- [LLM-foundation.md](LLM-foundation.md), [LLM-foundation-types.md](LLM-foundation-types.md), [LLM-foundation-test.md](LLM-foundation-test.md)
- [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-postgresql-queue.md](LLM-postgresql-queue.md), [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md), [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md)
- [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md)
- [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md), [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md)
- [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md), [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md)
- [LLM-vaadin-ui.md](LLM-vaadin-ui.md)

**Spring Boot:**
- [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### By Use Case

- **Event Sourcing:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) → [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)
- **Microservices:** [LLM-foundation.md](LLM-foundation.md)
- **Type Safety:** [LLM-types.md](LLM-types.md) → [LLM-types-integrations.md](LLM-types-integrations.md)
- **Spring Boot:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### README Files

For detailed explanations and design rationale:
- [README.md](../README.md) - Project overview
- [components/README.md](../components/README.md) - Components overview
- Individual module READMEs in respective directories
