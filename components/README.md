# Essentials Components

> Production-ready infrastructure patterns for distributed Java applications—using your existing database

📖 **Quick LLM Reference:** [LLM-components.md](../LLM/LLM-components.md)

> **NOTE:** This library is WORK-IN-PROGRESS

---

## Table of Contents

- [What is Essentials Components?](#what-is-essentials-components)
- [Why Choose Essentials Components?](#why-choose-essentials-components)
- [When Should You Use This?](#when-should-you-use-this)
- [Quick Start](#quick-start)
- [Getting Started](#getting-started)
- [Architecture](#architecture)
- [Module Reference](#module-reference)
  - [Foundation Modules](#foundation-modules)
  - [Database Implementations](#database-implementations)
  - [Event Sourcing Stack](#event-sourcing-stack)
  - [Spring Boot Starters](#spring-boot-starters)
  - [Specialized Modules](#specialized-modules)
- ⚠️ [Security](#security)
- [Testing](#testing)
- [Resources](#resources)

---

## What is Essentials Components?

Essentials Components is a Java library that solves **distributed coordination challenges** when running multiple instances of a microservice—**using your existing PostgreSQL or MongoDB database**.

**Core capability:** Distributed locks, message queues, and reliable messaging patterns (Inbox/Outbox)—**no Redis, no RabbitMQ, no Kafka required** for intra-service coordination.

**Advanced capability (optional):** Full event sourcing with CQRS, event-sourced aggregates, and event-driven integration—**no EventStoreDB or Axon Server required**.

Built on [Essentials Core](../README.md), it provides battle-tested implementations with a focus on simplicity, type safety, and Spring Boot integration.

**Choose your path:**
- Use **foundation components** for traditional distributed services needing coordination
- Add **event-driven components** when you need event sourcing and CQRS

---

## Why Choose Essentials Components?

### ✅ Designed for Intra-Service Coordination

**Understanding Intra-Service vs Cross-Service:**

In distributed service-oriented (microservice) architectures, there are two types of communication:

| Type | What It Means | Example                                                                                           |
|------|---------------|---------------------------------------------------------------------------------------------------|
| **Intra-Service** | Multiple instances of the **same service** coordinating with each other | 3 instances of `OrderService` sharing the same database(s), jobs, preventing duplicate processing |
| **Cross-Service** | Different services communicating with each other | `OrderService` → `BillingService` → `ShippingService`                                             |

**Essentials Components is built for intra-service coordination.** Most patterns are useful even with a **single instance**, but become critical when scaling to multiple instances sharing a database.

#### Foundation Patterns (Useful for 1+ Instances)

**DurableQueues** - Point-to-point messaging with guaranteed delivery:
- **Single instance**: Async command handling, background jobs, scheduled jobs, decoupling HTTP requests from long-running work
- **Multiple instances**: Automatic work distribution across instances, load balancing, fault tolerance
- **Use cases**: Command processing (CQRS), job queues, email sending, report generation, async integration, event-driven processing
- **Guarantees**: At-least-once delivery, automatic retries with configurable backoff, dead letter queue for failed messages, ordered processing per message key

**Inbox/Outbox** - Transactional messaging patterns (Enterprise Integration Patterns):
- **Inbox**: Store-and-forward for reliable message consumption from external systems (Kafka, RabbitMQ) with At-Least-Once delivery—solves the Dual Write Problem when consuming from systems that don't share your database's transactional resource
- **Outbox**: Store-and-forward for reliable message sending to external systems with At-Least-Once delivery—solves the Dual Write Problem when publishing to external systems atomically with database updates
- **Use cases**: Receiving from Kafka/RabbitMQ, webhook consumption, publishing events to Kafka/RabbitMQ, cross-service integration
- **Guarantees**: Transactional consistency (message + business data committed together), automatic redelivery, handlers process messages within same transaction as business logic
- **Note**: Handlers must be idempotent (At-Least-Once delivery means duplicates are possible)

**FencedLockManager** - Distributed coordination (primarily for 2+ instances):
- **Purpose**: Ensures only ONE instance across the cluster executes critical work
- **Use cases**: Scheduled jobs (nightly reports, cleanup tasks), singleton workers, leader election, batch processing
- **Safe with single instance**: Lock acquisition always succeeds, no overhead
- **Based on**: Martin Kleppmann's fenced token pattern for preventing split-brain scenarios

**UnitOfWork** - Transaction abstraction:
- **Purpose**: Technology-agnostic transaction boundaries across all components and transparent Spring transactions integration.
- **Implementations**: JDBI, Spring JDBC/JPA, Spring Data MongoDB
- **Guarantees**: All component operations (queues, locks, events) can participate in the same transaction

#### Event-Driven Patterns (Useful for 1+ Instances)

**EventStore** - Event sourcing foundation:
- **Purpose**: Persist events as source of truth, rebuild state from event streams
- **Use cases**: Complex domains with Audit trail requirements, temporal queries, event-sourced aggregates, CQRS projections
- **Multiple instances**: All instances read/write to the same event streams with optimistic concurrency control
- **Features**: Per-aggregate ordering (`EventOrder`), global ordering per aggregate type (`GlobalEventOrder`), gap detection and handling, multitenancy support

**EventSubscriptions** - Durable event consumption with resume points:
- **Purpose**: Subscribe to event streams with gap handling and automatic crash recovery via resume points (last successfully processed `GlobalEventOrder`)
- **Types**:
  - **Asynchronous** (out-of-transaction after commit—for external integrations)
  - **Exclusive Asynchronous** (single consumer per cluster via `FencedLock` — for critical or consistent processing)
  - **In-Transaction** (synchronous within the same transaction as event appendToStream — for strong consistency)
  - **Exclusive In-Transaction** (single synchronous consumer with strong consistency)
- **Use cases**: Building read models, CQRS projections, external system integration, event-driven workflows, todo-lists
- **Resume capability**: On restart, subscriptions continue from last checkpoint — seamless failover

**EventProcessor** - High-level event/command processing framework:
- **Purpose**: Simplified event handling with automatic subscription management, guaranteed delivery, error handling, and redelivery strategies
- **Types**:
  - **`EventProcessor`**: Async via `Inbox` queue with redelivery policies — for external integrations (Kafka, email, webhooks) and exclusive processing via `FencedLock`
  - **`InTransactionEventProcessor`**: Synchronous in-transaction — for atomically consistent projections (lowest latency) - optional exclusive processing via `FencedLock`
  - **`ViewEventProcessor`**: Async with direct processing and only with queue-on-failure — for low-latency view updates
- **Features**: Pattern-matching `@MessageHandler` methods, ordered message processing, automatic retry with configurable backoff for async processing, exclusive processing via `FencedLock`
- **Use cases**: Kafka event publishing, email notifications, read model updates, integration with external systems, todo-lists

> Essentials Components focuses on **intra-service** coordination. For **cross-service communication** (OrderService → BillingService), use Kafka, RabbitMQ, or HTTP APIs. 

### ✅ Use Your Existing Database—No New Infrastructure

Stop adding Redis for locks, RabbitMQ for queues, and EventStoreDB for events.  
Your PostgreSQL or MongoDB database already handles ACID transactions, persistence, and concurrency—Essentials Components leverages it for **intra-service** distributed coordination and messaging.

**You already have:**
- PostgreSQL or MongoDB running
- Connection pooling and monitoring
- Backup and disaster recovery
- ACID transactions

**You don't need to add:**
- Separate Kafka cluster (for intra-service messaging)
- Redis instance (for intra-service distributed locks)
- RabbitMQ setup (for intra-service job queues)
- EventStoreDB or Axon Server (for event sourcing)

### ✅ Production-Ready, Not Proof-of-Concept

Components include various production-ready features:
- Proper error handling and retry logic
- Dead letter queue support
- Gap detection and handling
- Optimistic concurrency control
- Multitenancy support
- Comprehensive test coverage

### ✅ Spring Boot Auto-Configuration

Use the Essentials `spring-boot-starter`'s, configure your database, and inject Essentials beans:

```java
@Service
public class OrderProcessor {
    private final EventStore eventStore;
    private final DurableQueues queues;
    private final FencedLockManager locks;

    // Auto-configured by Spring Boot—just inject and use
}
```

All the wiring, transaction management, and lifecycle handling is automatic.

### ✅ Type-Safe, Strongly-Typed APIs

Built on Essentials Core's `SingleValueType` pattern, APIs prevent common errors:

```java
// Compile-time safety—can't mix up queue names, lock names, aggregate IDs
QueueName orderQueue = QueueName.of("order-processing");
LockName batchLock = LockName.of("batch-job-lock");
OrderId orderId = OrderId.of("order-123");
```

### Database & Technology Support

#### Foundation Components

| Database | Locks | Queues | Inbox/Outbox | Transaction Support |
|----------|-------|--------|--------------|---------------------|
| **PostgreSQL** | ✅ | ✅ | ✅ | JDBI + Spring JDBC/JPA |
| **MongoDB** | ✅ | ✅ | ✅ | Spring Data MongoDB |

#### Event-Driven Components (PostgreSQL Only)

| Database | Event Store | Subscriptions | Event Processor | Aggregates |
|----------|-------------|---------------|-----------------|------------|
| **PostgreSQL** | ✅ Full-featured | ✅ | ✅ | ✅ |
| **MongoDB** | ❌ | ❌ | ❌ | ❌ |

> **Note:** Event sourcing requires PostgreSQL. MongoDB support is limited to foundation components only.

### Spring Boot Starters

| Starter | Foundation Components | Event-Driven Components |
|---------|---------------------|------------------------|
| `spring-boot-starter-postgresql` | ✅ FencedLocks, DurableQueues, Inbox/Outbox, UnitOfWork, CommandBus, EventBus | ❌ |
| `spring-boot-starter-postgresql-event-store` | ✅ All foundation components | ✅ EventStore, Subscriptions, EventProcessor, Aggregates |
| `spring-boot-starter-mongodb` | ✅ FencedLocks, DurableQueues, Inbox/Outbox, UnitOfWork, CommandBus, EventBus | ❌ |
| `spring-boot-starter-admin-ui` | N/A | ✅ Web UI for EventStore monitoring |

**Choose your path:**
- **Foundation only**: Use `spring-boot-starter-postgresql` or `spring-boot-starter-mongodb` for distributed coordination without event sourcing
- **Full event-driven**: Use `spring-boot-starter-postgresql-event-store` for complete event sourcing + all foundation components

---

## When Should You Use This?

### ✅ Foundation Components – Reliable Structured Monoliths or Distributed (Micro)Services

Use the foundation components when you need:

- Running **multiple instances** of a service with a shared database
- **Distributed locks** for scheduled jobs or singleton workers (only one instance should execute)
- **Reliable message queues** with retry/DLQ for background processing
- **Inbox/Outbox patterns** for reliable integration with other services
- **Transaction coordination** across database operations and transparent interop with Spring Managed Transactions
- **Traditional microservices** architecture without event sourcing

**Example:** A REST API service running 5 instances that needs background job processing, scheduled tasks, and reliable message delivery—but doesn't need event sourcing.

### ✅ Event-Driven Components (Event Sourcing & CQRS)

Add event sourcing capabilities when you need:

- **Event Sourcing** without managing a dedicated EventStoreDB or Axon Server, use your PostgreSQL database
- **Full audit trails** and event history for compliance or debugging
- **CQRS** with event-driven projections and read models
- **Event-sourced aggregates** using DDD patterns (Deciders, Evolvers, Stateful Aggregates)
- **Event-driven integration** with subscriptions and event processors
- **Temporal queries** (what was the state at a specific point in time?)

**Example:** An order management system where every state change must be auditable, projections rebuild from events, and business logic follows DDD aggregate patterns.

### ❌ Not a Good Fit

- **Cross-service messaging** between different services (use Kafka/RabbitMQ for pub/sub at scale)
- **Cross-service distributed locks** (use Zookeeper/etcd for coordination across different services)
- **Stateless services** with no shared database
- **Message routing, topics, or pub/sub** at massive scale (use Kafka)
- **Extreme throughput** requirements (millions of events/second—use specialized event stores)

---

## Quick Start

Choose your path based on your architecture needs:

### Path 1: Foundation Only (Distributed Services)

**For traditional microservices needing locks, queues, and reliable messaging—no event sourcing.**

**1. Add dependency:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Configure database:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

**3. Use components:**
```java
@Service
public class JobProcessor {
    private final DurableQueues queues;
    private final FencedLockManager locks;

    @Scheduled(fixedRate = 60000)
    public void scheduledJob() {
        // Only one instance executes the job
        locks.acquireLockAsync(LockName.of("daily-job"), lock -> {
            processJob();
        });
    }

    public void queueWork(WorkItem item) {
        queues.queueMessage(QueueName.of("work-queue"), item);
    }
}
```

### Path 2: Full Event-Driven (Event Sourcing + Foundation)

**For event-sourced autonomous-components/microservices with CQRS, aggregates, and event-driven integration.**

**1. Add dependency:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Configure database:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

**3. Use components:**
```java
@Service
public class OrderService {
    private final EventStore eventStore;

    @Transactional
    public void placeOrder(PlaceOrderCommand cmd) {
        // Event sourcing - append events to stream
        eventStore.appendToStream(
            AggregateType.of("Orders"),
            cmd.orderId(),
            new OrderPlaced(cmd.orderId(), cmd.items())
        );

        // EventProcessor automatically handles OrderPlaced events asynchronously
    }
}
```

**4. Create EventProcessor for async order fulfillment:**
```java
@Service
public class OrderFulfillmentProcessor extends EventProcessor {
    private final InventoryService inventoryService;
    private final ShippingService shippingService;

    public OrderFulfillmentProcessor(EventProcessorDependencies dependencies,
                                     InventoryService inventoryService,
                                     ShippingService shippingService) {
        super(dependencies);
        this.inventoryService = inventoryService;
        this.shippingService = shippingService;
    }

    @Override
    public String getProcessorName() {
        return "OrderFulfillmentProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void handle(OrderPlaced event) {
        // Async processing with automatic retry
        inventoryService.reserveItems(event.orderId(), event.items());
        shippingService.scheduleShipment(event.orderId());
    }

    @Override
    protected RedeliveryPolicy getInboxRedeliveryPolicy() {
        return RedeliveryPolicy.exponentialBackoff()
            .setInitialRedeliveryDelay(Duration.ofSeconds(1))
            .setMaximumNumberOfRedeliveries(10)
            .build();
    }
}
```

**MongoDB Support:** Use `spring-boot-starter-mongodb` for foundation components only (no EventStore).

See [Getting Started](#getting-started) for complete examples.

---

## Architecture

### Architectural Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                      Your Application                           │
│   Domain models, services, REST controllers, message handlers   │
└─────────────────────────────────────────────────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │   Spring Boot         │
                    │   Auto-Configuration  │
                    └───────────┬───────────┘
                                │
┌─────────────────────────────────────────────────────────────────┐
│                      Components Layer                           │
│   PostgreSQL/MongoDB implementations of infrastructure patterns │
│   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│   │ EventStore   │ │ DurableQueues│ │ FencedLocks  │            │
│   └──────────────┘ └──────────────┘ └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
                                │
┌─────────────────────────────────────────────────────────────────┐
│                      Foundation Layer                           │
│   Abstractions: UnitOfWork, FencedLock, DurableQueues, Inbox    │
└─────────────────────────────────────────────────────────────────┘
                                │
┌─────────────────────────────────────────────────────────────────┐
│                      Core Layer (Essentials)                    │
│   types, shared, reactive, immutable                            │
└─────────────────────────────────────────────────────────────────┘
```

### Component Integration Flow

1. **Spring Boot Starter** auto-configures all required beans
2. **UnitOfWork** manages transaction boundaries across all components
3. **FencedLockManager** ensures only one instance processes critical work
4. **DurableQueues** provides reliable point-to-point messaging
5. **EventStore** persists and subscribes to event streams
6. **Inbox/Outbox** enables store-and-forward integration patterns

---

## Getting Started

### Example 1: Foundation Only - Distributed Job Processing

A traditional microservice with background jobs and scheduled tasks—no event sourcing needed.

**1. Add dependency:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Configure database:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

**3. Queue and process work:**
```java
@Service
public class EmailService {
    private final DurableQueues queues;
    private final FencedLockManager locks;

    // Queue emails for async sending
    @Transactional
    public void queueEmail(EmailMessage email) {
        queues.queueMessage(QueueName.of("emails"), email);
    }

    // Consume emails across multiple instances
    @PostConstruct
    public void startWorker() {
        queues.consumeFromQueue(
            QueueName.of("emails"),
            RedeliveryPolicy.exponentialBackoff(
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2.0,
                10
            ),
            message -> sendEmail(message.getPayload(EmailMessage.class))
        );
    }

    // Scheduled job - only ONE instance executes
    @Scheduled(fixedRate = 3600000)
    public void cleanupOldEmails() {
        locks.acquireLockAsync(LockName.of("email-cleanup"), lock -> {
            deleteEmailsOlderThan(Duration.ofDays(30));
        });
    }
}
```

**That's it!** DurableQueues distributes work across instances, FencedLockManager ensures singleton execution, and Spring Boot handles all the wiring.

---

### Example 2: Full Event-Driven - Event Sourced Order Service

An event-sourced microservice with CQRS, aggregates, and event-driven integration.

**1. Add dependency:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Configure database:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```
**3. Define events:**
```java
public interface OrderEvent { OrderId orderId(); }
public record OrderPlaced(OrderId orderId, CustomerId customerId) implements OrderEvent {}
public record OrderConfirmed(OrderId orderId) implements OrderEvent {}
```

**4. Define the Order aggregate (modern AggregateRoot):**
```java
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    private boolean confirmed;

    // Rehydration constructor
    public Order(OrderId orderId) {
        super(orderId);
    }

    // Business constructor - emits OrderPlaced event
    public Order(OrderId orderId, CustomerId customerId, List<OrderLine> items) {
        this(orderId);
        apply(new OrderPlaced(orderId, customerId, items));
    }

    // Business method - idempotent
    public void confirm() {
        if (confirmed) return;  // Idempotent: no-op if already confirmed
        apply(new OrderConfirmed(aggregateId()));
    }

    // Event handlers - update internal state
    @EventHandler
    private void on(OrderPlaced event) {
        this.confirmed = false;
        // Note: You only need to extract properties required for later validations
    }

    @EventHandler
    private void on(OrderConfirmed event) {
        this.confirmed = true;
    }
}
```

**5. Configure the StatefulAggregateRepository (registers AggregateType with EventStore):**
```java
@Configuration
public class OrderAggregateConfig {

    @Bean
    public StatefulAggregateRepository<OrderId, OrderEvent, Order> orderRepository(
            ConfigurableEventStore<?> eventStore) {
        // Registers AggregateType "Orders" with the EventStore
        return StatefulAggregateRepository.from(
            eventStore,
            AggregateType.of("Orders"),
            StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
            Order.class
        );
    }
}
```

**6. Send commands via CommandBus (from REST controller):**
```java
@RestController
public class OrderController {
    private final CommandBus commandBus;

    @PostMapping("/orders")
    public OrderId placeOrder(@RequestBody PlaceOrderRequest request) {
        // Send command - handled by OrderCommandHandler
        commandBus.send(new PlaceOrderCommand(request.orderId(), request.customerId(), request.items()));
        return orderId;
    }

    @PostMapping("/orders/{orderId}/confirm")
    public void confirmOrder(@PathVariable OrderId orderId) {
        // Send command - handled by OrderCommandHandler
        commandBus.send(new ConfirmOrderCommand(orderId));
    }
}
```

**7. Handle commands with AnnotatedCommandHandler:**
```java
@Service
public class OrderCommandHandler extends AnnotatedCommandHandler {
    private final StatefulAggregateRepository<OrderId, OrderEvent, Order> repository;

    public OrderCommandHandler(StatefulAggregateRepository<OrderId, OrderEvent, Order> repository) {
        this.repository = repository;
    }

    // Automatically runs in a transaction/UnitOfWork
    @CmdHandler
    void handle(PlaceOrderCommand cmd) {
        // Idempotent: skip if order already exists
        if (repository.tryLoad(cmd.orderId()).isPresent()) {
            return;
        }
        // Create new aggregate - applies OrderPlaced event internally
        var order = new Order(cmd.orderId(), cmd.customerId(), cmd.items());
        repository.save(order);
    }

    // Automatically runs in a transaction/UnitOfWork
    @CmdHandler
    void handle(ConfirmOrderCommand cmd) {
        // Load aggregate, apply business logic (confirm() is idempotent)
        var order = repository.load(cmd.orderId());
        order.confirm();  // Idempotent: no-op if already confirmed
        // Since we're using StatefulAggregate then, any aggregate changes (Events applied) will automatically be persisted when the transaction/UnitOfWork commits 
    }
}
```

**8. React to events for external integration (EventProcessor):**
```java
@Service
public class OrderFulfillmentProcessor extends EventProcessor {
    private final InventoryService inventoryService;
    private final ShippingService shippingService;

    public OrderFulfillmentProcessor(EventProcessorDependencies dependencies,
                                     InventoryService inventoryService,
                                     ShippingService shippingService) {
        super(dependencies);
        this.inventoryService = inventoryService;
        this.shippingService = shippingService;
    }

    @Override
    public String getProcessorName() { return "OrderFulfillmentProcessor"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    // Automatically runs in a transaction/UnitOfWork
    @MessageHandler
    void handle(OrderPlaced event) {
        // Async processing via Inbox with automatic retry (Note: Not best practice to perform two side effects in a single message)
        inventoryService.reserveItems(event.orderId(), event.items());
        shippingService.scheduleShipment(event.orderId());
    }

    @Override
    protected RedeliveryPolicy getInboxRedeliveryPolicy() {
        return RedeliveryPolicy.exponentialBackoff()
            .setInitialRedeliveryDelay(Duration.ofSeconds(1))
            .setMaximumNumberOfRedeliveries(10)
            .build();
    }
}
```

**9. Update projections with ViewEventProcessor (low-latency):**
```java
@Service
public class OrderDashboardProjection extends ViewEventProcessor {
    private final OrderReadModelRepository readModelRepository;

    public OrderDashboardProjection(ViewEventProcessorDependencies dependencies,
                                    OrderReadModelRepository readModelRepository) {
        super(dependencies);
        this.readModelRepository = readModelRepository;
    }

    @Override
    public String getProcessorName() { return "OrderDashboardProjection"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    // Automatically runs in a transaction/UnitOfWork
    @MessageHandler
    void handle(OrderPlaced event) {
        // Direct handling for low latency - queued on failure
        readModelRepository.insertOrder(event.orderId(), event.customerId(), "PLACED");
    }

    // Automatically runs in a transaction/UnitOfWork
    @MessageHandler
    void handle(OrderConfirmed event) {
        readModelRepository.updateStatus(event.orderId(), "CONFIRMED");
    }
}
```

**That's it!** CommandBus dispatches commands, AnnotatedCommandHandler uses repositories to persist aggregates (which emit events), EventProcessors react to events asynchronously, and ViewEventProcessors update read models with low latency.  
Spring Boot auto-configures everything.

---

### More Examples

For detailed examples of specific patterns, see the individual module READMEs:

**Foundation Components:**
- **Distributed Locks:** [postgresql-distributed-fenced-lock](postgresql-distributed-fenced-lock/README.md)
- **Message Queues:** [postgresql-queue](postgresql-queue/README.md)
- **Inbox/Outbox:** [foundation](foundation/README.md)

**Event-Driven Components:**
- **Event Store:** [postgresql-event-store](postgresql-event-store/README.md)
- **Aggregates:** [eventsourced-aggregates](eventsourced-aggregates/README.md)
- **Event Processors:** [postgresql-event-store](postgresql-event-store/README.md#event-processors)

---

## Module Reference

### Foundation Modules

| Module | Purpose                                                                                                                            | Documentation |
|--------|------------------------------------------------------------------------------------------------------------------------------------|---------------|
| [foundation-types](foundation-types/README.md) | Common types: `CorrelationId`, `EventId`, `AggregateType`, `GlobalEventOrder`                                                      | [LLM](../LLM/LLM-foundation-types.md) |
| [foundation](foundation/README.md) | Core abstractions: `UnitOfWork`, `FencedLockManager`, `DurableQueues`, `Inbox`/`Outbox`, `DurableLocalCommandBus`, `JSONSerializer` | [LLM](../LLM/LLM-foundation.md) |
| [foundation-test](foundation-test/README.md) | Test utilities and abstract test classes for  `FencedLockManager`, `DurableQueues` implementations                                 | [LLM](../LLM/LLM-foundation-test.md) |

### Database Implementations

#### PostgreSQL

| Module | Purpose                                                                                                         | Documentation |
|--------|-----------------------------------------------------------------------------------------------------------------|---------------|
| [postgresql-distributed-fenced-lock](postgresql-distributed-fenced-lock/README.md) | `PostgresqlFencedLockManager` - PostgreSQL-based distributed locks                                              | [LLM](../LLM/LLM-postgresql-distributed-fenced-lock.md) |
| [postgresql-queue](postgresql-queue/README.md) | `PostgresqlDurableQueues` - PostgreSQL-based message queues                                                     | [LLM](../LLM/LLM-postgresql-queue.md) |
| [postgresql-document-db](postgresql-document-db/README.md) | `DocumentDbRepository` / `VersionedEntity` - Kotlin-focused document database using JSONB and type safe queries | [LLM](../LLM/LLM-postgresql-document-db.md) |

#### MongoDB (Spring Data)

| Module | Purpose | Documentation |
|--------|---------|---------------|
| [springdata-mongo-distributed-fenced-lock](springdata-mongo-distributed-fenced-lock/README.md) | `MongoFencedLockManager` - MongoDB-based distributed locks | [LLM](../LLM/LLM-springdata-mongo-distributed-fenced-lock.md) |
| [springdata-mongo-queue](springdata-mongo-queue/README.md) | `MongoDurableQueues` - MongoDB-based message queues | [LLM](../LLM/LLM-springdata-mongo-queue.md) |

### Event Sourcing Stack

| Module | Purpose                                                                                                | Documentation |
|--------|--------------------------------------------------------------------------------------------------------|---------------|
| [postgresql-event-store](postgresql-event-store/README.md) | Full-featured `EventStore` with subscriptions, gap handling, `EventProcessor`s                         | [LLM](../LLM/LLM-postgresql-event-store.md) |
| [spring-postgresql-event-store](spring-postgresql-event-store/README.md) | Spring transaction integration for Event Store                                                         | [LLM](../LLM/LLM-spring-postgresql-event-store.md) |
| [eventsourced-aggregates](eventsourced-aggregates/README.md) | Java Aggregate patterns: `AggregateRoot`s, `Decider`s, `Evolver`s, Repositories, Snapshot repositories | [LLM](../LLM/LLM-eventsourced-aggregates.md) |
| [kotlin-eventsourcing](kotlin-eventsourcing/README.md) | Kotlin DSL for functional event sourcing                                                               | [LLM](../LLM/LLM-kotlin-eventsourcing.md) |

### Spring Boot Starters

| Module | What It Provides                                                                                                                                             | Documentation |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| [spring-boot-starter-postgresql](spring-boot-starter-postgresql/README.md) | Auto-configures: `UnitOfWorkFactory`, `CommandBus`, `EventBus`, `PostgresqlDurableQueues`, `PostgresqlFencedLockManager`, `Inbox`/`Outbox`, `JSONSerializer` | [LLM](../LLM/LLM-spring-boot-starter-modules.md) |
| [spring-boot-starter-postgresql-event-store](spring-boot-starter-postgresql-event-store/README.md) | Everything from above plus: `EventStore`, `EventStoreSubscriptionManager`, `EventProcessor`s, aggregate repositories                                         | [LLM](../LLM/LLM-spring-boot-starter-modules.md) |
| [spring-boot-starter-mongodb](spring-boot-starter-mongodb/README.md) | MongoDB equivalents: `UnitOfWorkFactory`, `CommandBus`, `EventBus`, `MongoDurableQueues`, `MongoFencedLockManager`, `Inbox`/`Outbox`, `JSONSerializer`       | [LLM](../LLM/LLM-spring-boot-starter-modules.md) |
| [spring-boot-starter-admin-ui](spring-boot-starter-admin-ui/README.md) | Administrative web interface for monitoring and operations                                                                                                   | [LLM](../LLM/LLM-spring-boot-starter-modules.md) |

### Specialized Modules

| Module | Purpose | Documentation |
|--------|---------|---------------|
| [vaadin-ui](vaadin-ui/README.md) | Vaadin-based administrative UI components | [LLM](../LLM/LLM-vaadin-ui.md) |

---

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components allow customization of table/column/index/function/collection names that are used with **String concatenation** → SQL/NoSQL injection risk. 
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL/NoSQL injection.

> **Your Responsibility:**
> - Never use unsanitized user input for table, column, function, index, or collection names
> - Validate all configuration values derived from external sources
> - Use only controlled, trusted sources for naming configuration

**Safe Pattern:**
```java
// Example configuring PostgreSQL table names
var durableQueues = PostgresqlDurableQueues.builder()

// ❌ DANGEROUS - user input could enable SQL injection
.setSharedQueueTableName(userInput + "_queue")

// ✅ SAFE
.setSharedQueueTableName("message_queue")  // Hardcoded only

// ⚠️ Validate if from config
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Basic validation (not exhaustive)
MongoUtil.checkIsValidCollectionName(collectionName);  // Basic validation (not exhaustive)
```

### Module-Specific Security Guidance

See individual module documentation for detailed security considerations:
- [foundation](foundation/README.md#security)
- [foundation-types](foundation-types/README.md#security)
- [postgresql-event-store](postgresql-event-store/README.md#security)
- [postgresql-distributed-fenced-lock](postgresql-distributed-fenced-lock/README.md#security)
- [postgresql-queue](postgresql-queue/README.md#security)
- [eventsourced-aggregates](eventsourced-aggregates/README.md#security)
- [kotlin-eventsourcing](kotlin-eventsourcing/README.md#security)
- [springdata-mongo-queue](springdata-mongo-queue/README.md#security)
- [springdata-mongo-distributed-fenced-lock](springdata-mongo-distributed-fenced-lock/README.md#security)

---

## Testing

### Run All Component Tests

```bash
# All unit tests
mvn test -pl components

# All integration tests
mvn verify -pl components
```

### Test Individual Modules

```bash
# Single module unit tests
mvn test -pl components/<module-name> -am

# Single module integration tests
mvn verify -pl components/<module-name> -am

# Example: Test EventStore
mvn verify -pl components/postgresql-event-store -am
```

### Test Requirements

- **PostgreSQL modules**: Requires Docker for TestContainers
- **MongoDB modules**: Requires Docker for TestContainers (replica set mode)
- **TestContainers**: Docker-in-Docker support configured in devcontainer

---

## Resources

- **Root Documentation:** [Essentials README](../README.md)
- **LLM Documentation:** [LLM.md](../LLM/LLM.md) (comprehensive, token-efficient overview)
- **Component LLM Reference:** [LLM-components.md](../LLM/LLM-components.md)
- **GitHub:** [https://github.com/trustworksdk/essentials-project](https://github.com/trustworksdk/essentials-project)
- **License:** Apache License 2.0
