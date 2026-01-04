# Components - LLM Reference

## TOC
- [Quick Facts](#quick-facts)
- [Component Matrix](#component-matrix)
- ⚠️ [Security](#security)
- [Module Selection Guide](#module-selection-guide)
- [Foundation](#foundation)
- [PostgreSQL Components](#postgresql-components)
- [MongoDB Components](#mongodb-components)
- [Spring Boot Starters](#spring-boot-starters)
- [Event Sourcing](#event-sourcing)
- [UI Components](#ui-components)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

## Quick Facts

Advanced building blocks for distributed systems, event sourcing, and microservices.

| Aspect | Details |
|--------|---------|
| **Base Package** | `dk.trustworks.essentials.components` |
| **GroupId** | `dk.trustworks.essentials` |
| **Scope** | All framework deps are `provided` |
| **Philosophy** | Intra-service coordination (same service, shared database) |

Status: Work-in-progress

---

## Component Matrix

| Category | Module | Purpose                                                                       | Database | Detailed Docs |
|----------|--------|-------------------------------------------------------------------------------|----------|---------------|
| **Foundation** | `foundation-types` | Common types (CorrelationId, EventId, AggregateType, etc.)                    | N/A | [LLM-foundation-types.md](LLM-foundation-types.md) |
| | `foundation` | UnitOfWork, DurableQueues, FencedLock, Inbox/Outbox                           | Any | [LLM-foundation.md](LLM-foundation.md) |
| | `foundation-test` | Test utilities and abstractions for DurableQueues, FencedLock implementations | N/A | [LLM-foundation-test.md](LLM-foundation-test.md) |
| **PostgreSQL** | `postgresql-event-store` | Event Store with subscriptions, gap handling                                  | PostgreSQL | [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) |
| | `postgresql-queue` | Durable queues implementation                                                 | PostgreSQL | [LLM-postgresql-queue.md](LLM-postgresql-queue.md) |
| | `postgresql-distributed-fenced-lock` | Distributed locking                                                           | PostgreSQL | [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md) |
| | `postgresql-document-db` | Document database (Kotlin)                                                    | PostgreSQL | [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) |
| **Spring** | `spring-postgresql-event-store` | Spring transaction integration for EventStore                                 | PostgreSQL | [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md) |
| | `spring-boot-starter-postgresql` | Auto-config for PostgreSQL components                                         | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-postgresql-event-store` | Auto-config for EventStore  + Postgresql components                           | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-mongodb` | Auto-config for MongoDB components                                            | MongoDB | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-admin-ui` | Admin UI for EventStore                                                       | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **MongoDB** | `springdata-mongo-queue` | Durable queues implementation                                                 | MongoDB | [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md) |
| | `springdata-mongo-distributed-fenced-lock` | Distributed locking                                                           | MongoDB | [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) |
| **Event Sourcing** | `eventsourced-aggregates` | Event-sourced aggregate implementations                                       | Any | [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) |
| | `kotlin-eventsourcing` | Kotlin DSL for event sourcing                                                 | Any | [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) |
| **UI** | `vaadin-ui` | Vaadin UI components                                                          | N/A | [LLM-vaadin-ui.md](LLM-vaadin-ui.md) |

**Scope Note**: Components designed for **intra-service** coordination (multiple instances of SAME service sharing a database). For cross-service messaging (Sales ↔ Billing ↔ Shipping), use Kafka/RabbitMQ/Zookeeper.

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

---

## Module Selection Guide

### By Use Case

| Use Case | Required Modules | Optional |
|----------|-----------------|----------|
| **Event Sourcing (PostgreSQL)** | `postgresql-event-store`, `eventsourced-aggregates` | `spring-postgresql-event-store`, `kotlin-eventsourcing` |
| **Event Sourcing (Spring Boot)** | `spring-boot-starter-postgresql-event-store`, `eventsourced-aggregates` | `spring-boot-starter-admin-ui` |
| **Microservices (PostgreSQL)** | `foundation`, `postgresql-queue`, `postgresql-distributed-fenced-lock` | `spring-boot-starter-postgresql` |
| **Microservices (MongoDB)** | `foundation`, `springdata-mongo-queue`, `springdata-mongo-distributed-fenced-lock` | `spring-boot-starter-mongodb` |
| **Distributed Locking** | `foundation` + (`postgresql-distributed-fenced-lock` OR `springdata-mongo-distributed-fenced-lock`) | N/A |
| **Message Queues** | `foundation` + (`postgresql-queue` OR `springdata-mongo-queue`) | N/A |
| **Document Database** | `postgresql-document-db` | N/A |
| **Inbox/Outbox Pattern** | `foundation` + database-specific modules | Spring Boot starters |

### By Database Technology

| Database | Event Store | Queues | Distributed Lock | Document DB |
|----------|-------------|--------|------------------|-------------|
| **PostgreSQL** | `postgresql-event-store` | `postgresql-queue` | `postgresql-distributed-fenced-lock` | `postgresql-document-db` |
| **MongoDB** | ❌ | `springdata-mongo-queue` | `springdata-mongo-distributed-fenced-lock` | Native MongoDB |

### By Framework

| Framework | Recommended Starter | What It Auto-Configures |
|-----------|-------------------|------------------------|
| **Spring Boot + PostgreSQL (not event-sourced)** | `spring-boot-starter-postgresql` | Jdbi, FencedLock, Queues, Inbox/Outbox, CommandBus |
| **Spring Boot + PostgreSQL (event-sourced)** | `spring-boot-starter-postgresql-event-store` | Everything above + EventStore, Subscriptions |
| **Spring Boot + MongoDB** | `spring-boot-starter-mongodb` | MongoTemplate, FencedLock, Queues, Inbox/Outbox |
| **Plain Java (JDBI)** | Use modules directly | Manual wiring |

---

## Foundation

**Modules:** `foundation-types`, `foundation`, `foundation-test`
**Detailed Docs:** [LLM-foundation.md](LLM-foundation.md), [LLM-foundation-types.md](LLM-foundation-types.md), [LLM-foundation-test.md](LLM-foundation-test.md)

### Core Abstractions

Package: `dk.trustworks.essentials.components.foundation`

| Pattern | Package | Key Interface                         | Purpose |
|---------|---------|---------------------------------------|---------|
| **Transactions** | `transaction` | `UnitOfWorkFactory<UOW>`, `UnitOfWork` | Technology-agnostic transaction management |
| **Messaging** | `messaging.queue` | `DurableQueues`                       | Point-to-point, At-Least-Once delivery |
| **Locking** | `fencedlock` | `FencedLockManager`                   | Distributed locking with fence tokens |
| **Inbox/Outbox** | `messaging.eip.store_and_forward` | `Inbox`, `Outbox`                     | Store-and-forward pattern |
| **Command Bus** | `reactive.command` | `DurableLocalCommandBus`              | CommandBus with durable sendAndDontWait |

### Pattern: UnitOfWork

```java
// Package: dk.trustworks.essentials.components.foundation.transaction
public interface UnitOfWorkFactory<UOW extends UnitOfWork> {
    void usingUnitOfWork(CheckedRunnable runnable);
    void usingUnitOfWork(CheckedConsumer<UOW> consumer);
    <R> R withUnitOfWork(CheckedSupplier<R> supplier);
    <R> R withUnitOfWork(CheckedFunction<UOW, R> function);
}
```

**Implementations:**
- `JdbiUnitOfWorkFactory` - Direct JDBI transactions
- `SpringTransactionAwareJdbiUnitOfWorkFactory` - Spring-managed JDBI transactions
- `SpringMongoTransactionAwareUnitOfWorkFactory` - Spring-managed MongoDB transactions

### Pattern: DurableQueues

```java
// Package: dk.trustworks.essentials.components.foundation.messaging.queue
public interface DurableQueues {
    QueueEntryId queueMessage(QueueName queueName, Message message, UnitOfWork uow);
    DurableQueueConsumer consumeFromQueue(ConsumeFromQueue consumeFromQueue);
}
```

- **Delivery**: At-Least-Once (may redeliver)
- **DLQ**: Built-in Dead Letter Queue support
- **Ordering**: Per-message key ordering via `OrderedMessage`

### Pattern: FencedLock

```java
// Package: dk.trustworks.essentials.components.foundation.fencedlock
public interface FencedLockManager {
    Optional<FencedLock> tryAcquireLock(LockName lockName);
    FencedLock acquireLock(LockName lockName);
    void releaseLock(FencedLock lock);
    boolean isLockAcquired(LockName lockName);
}
```

- **Based on**: Martin Kleppmann's fenced token pattern
- **Scope**: Intra-service coordination only (same service instances)
- **Token**: Monotonically increasing `Long` fence token

### Foundation Types

Package: `dk.trustworks.essentials.components.foundation.types`

**Common Types:**
- `CorrelationId` - Cross-service correlation
- `EventId` - Unique event identifier
- `Tenant`, `TenantId` - Multitenancy support
- `SubscriberId` - Subscription identifier
- `GlobalEventOrder`, `EventOrder` - Event ordering
- `AggregateType`, `EventName`, `EventTypeOrName` - Event Store types

---

## PostgreSQL Components

### Event Store

**Module:** `postgresql-event-store`
**Detailed Docs:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md)

#### Core Classes

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

```java
public interface EventStore<CONFIG extends EventStoreEventStreamConfiguration> {
    AggregateEventStream<AGGREGATE_ID> fetchStream(
        AggregateType aggregateType,
        AGGREGATE_ID aggregateId);

    AggregateEventStream<AGGREGATE_ID> appendToStream(
        AggregateType aggregateType,
        AGGREGATE_ID aggregateId,
        Optional<EventOrder> expectedEventOrder,
        List<?> events);
}

// Main implementation
public class PostgresqlEventStore<CONFIG extends EventStoreEventStreamConfiguration>
    implements EventStore<CONFIG> {
    // ...
}
```

#### Key Features

| Feature         | Type | Description                                                                   |
|-----------------|------|-------------------------------------------------------------------------------|
| **Event Streams** | Per-aggregate | Separate table per `AggregateType`                                            |
| **Ordering**    | Dual | `EventOrder` (per-aggregate, strict) + `GlobalEventOrder` (per-type, may gap) |
| **Subscriptions** | Durable | Exclusive/non-exclusive with `ResumePoint` tracking                           |
| **Gap Handling** | Automatic | Detects transient (concurrent tx) vs permanent (rollback)                     |
| **Interceptors** | Yes | Pre/post append, publish to `EventBus`                                        |
| **Multitenancy** | Built-in | Per-tenant events in shared event tables                                      |
| **Event-driven processing** | `EventProcessor` | Event-driven processing with guaranteed delivery and replay support |


#### Setup

```java
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    new MyPersistableEventMapper(),
    EventStoreEventStreamConfigurationFactory.standardConfiguration(
        AggregateType.of("Orders"),
        idColumnType,
        jsonSerializer
    )
);
```

### Queue

**Module:** `postgresql-queue`
**Detailed Docs:** [LLM-postgresql-queue.md](LLM-postgresql-queue.md)

#### Core Class

Package: `dk.trustworks.essentials.components.foundation.postgresql`

```java
public class PostgresqlDurableQueues implements DurableQueues {
    public PostgresqlDurableQueues(
        UnitOfWorkFactory<? extends JdbiUnitOfWork> unitOfWorkFactory,
        Optional<String> sharedQueueTableName);
}
```

- **Table**: Single shared table for all queues (customizable name)
- **Delivery**: At-Least-Once, redelivery on timeout
- **DLQ**: Automatic after max retries
- **Ordering**: Via `OrderedMessage` wrapper

### Distributed Lock

**Module:** `postgresql-distributed-fenced-lock`
**Detailed Docs:** [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md)

#### Core Class

Package: `dk.trustworks.essentials.components.foundation.postgresql`

```java
public class PostgresqlFencedLockManager implements FencedLockManager {
    public PostgresqlFencedLockManager(
        UnitOfWorkFactory<? extends JdbiUnitOfWork> unitOfWorkFactory,
        Optional<String> lockTableName,
        Optional<Duration> lockTimeOut);
}
```

- **Token**: Monotonically increasing via PostgreSQL sequence
- **Timeout**: Automatic release after inactivity
- **Scope**: Intra-service only

### Document Database

**Module:** `postgresql-document-db`
**Detailed Docs:** [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md)

#### Core Class

Package: `dk.trustworks.essentials.components.foundation.postgresql`

```java
public class PostgresqlDocumentDb implements DocumentDb {
    // JSONB-based document storage in PostgreSQL
    // Supports indexing, querying, versioning
}
```

---

## MongoDB Components

### Queue

**Module:** `springdata-mongo-queue`
**Detailed Docs:** [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md)

#### Core Class

Package: `dk.trustworks.essentials.components.foundation.mongodb`

```java
public class MongoDBDurableQueues implements DurableQueues {
    public MongoDBDurableQueues(
        UnitOfWorkFactory<? extends SpringMongoTransactionAwareUnitOfWork> unitOfWorkFactory,
        MongoTemplate mongoTemplate,
        MongoConverter mongoConverter,
        Optional<String> sharedQueueCollectionName);
}
```

- **Collection**: Single collection for all queues
- **Delivery**: At-Least-Once
- **Ordering**: Via `OrderedMessage`

### Distributed Lock

**Module:** `springdata-mongo-distributed-fenced-lock`
**Detailed Docs:** [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md)

#### Core Class

Package: `dk.trustworks.essentials.components.foundation.mongodb`

```java
public class MongoFencedLockManager implements FencedLockManager {
    public MongoFencedLockManager(
        UnitOfWorkFactory<? extends SpringMongoTransactionAwareUnitOfWork> unitOfWorkFactory,
        MongoTemplate mongoTemplate,
        Optional<String> fencedLocksCollectionName,
        Duration lockTimeOut);
}
```

---

## Spring Boot Starters

**Modules:** `spring-boot-starter-postgresql`, `spring-boot-starter-postgresql-event-store`, `spring-boot-starter-mongodb`, `spring-boot-starter-admin-ui`
**Detailed Docs:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### Starter Matrix

| Starter | Auto-Configures | Configuration Prefix |
|---------|----------------|---------------------|
| `spring-boot-starter-postgresql` | Jdbi, FencedLock, Queues, Inbox/Outbox, CommandBus | `essentials.postgresql` |
| `spring-boot-starter-postgresql-event-store` | Above + EventStore, Subscriptions | `essentials.event-store` |
| `spring-boot-starter-mongodb` | MongoTemplate, FencedLock, Queues, Inbox/Outbox | `essentials.mongodb` |
| `spring-boot-starter-admin-ui` | Vaadin admin views for EventStore | `essentials.admin-ui` |

### Configuration Example (PostgreSQL Event Store)

```yaml
essentials:
  event-store:
    subscriber-id: "order-service-1"
    event-table-name-prefix: "events_"
    tenant:
      name: "default"
  postgresql:
    queue-table-name: "durable_queues"
    fenced-lock-table-name: "fenced_locks"
    inbox-table-name: "inbox"
    outbox-table-name: "outbox"
```

### Override Pattern

All beans use `@ConditionalOnMissingBean`:

```java
@Bean
public EventStore<EventStoreEventStreamConfiguration> myCustomEventStore() {
    // Override default EventStore
    return new PostgresqlEventStore<>(...);
}
```

---

## Event Sourcing

### Eventsourced Aggregates

**Module:** `eventsourced-aggregates`
**Detailed Docs:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)

#### Core Classes

Package: `dk.trustworks.essentials.components.eventsourced.aggregates`

```java
// Stateful aggregate
public interface StatefulAggregate<ID, EVENT_TYPE, AGGREGATE> {
    ID aggregateId();
    Optional<EventOrder> eventOrderOfLastRehydratedEvent();
    AGGREGATE rehydrate(AggregateEventStream<ID> eventStream);
}

// Event-sourced aggregate (modern) + StatefulAggregateRepository
public interface Aggregate<ID, EVENT_TYPE, AGGREGATE>
    extends StatefulAggregate<ID, EVENT_TYPE, AGGREGATE> {
    // Combines StatefulAggregate with event application
}
```

**Implementations:**
- `StatefulAggregateInstanceFactory` - Creates aggregate instances
- `AggregateRepository` / `StatefulAggregateRepository` - Load/save aggregates
- `Decider` / `EventStreamDecider` + `EventStreamEvolver` - Functional style Decider / Evolver patterns

### Kotlin DSL

**Module:** `kotlin-eventsourcing`
**Detailed Docs:** [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md)

#### Core DSL

Package: `dk.trustworks.essentials.components.kotlin.eventsourcing`

`Decider` + `Evolver` - Functional style Decider / Evolver patterns

```kotlin
class ConfirmOrderDecider : Decider<ConfirmOrder, OrderEvent> {
    private val evolver = OrderStateEvolver()

    override fun handle(cmd: ConfirmOrder, events: List<OrderEvent>): OrderEvent? {
        val state = Evolver.applyEvents(evolver, null, events)

        if (state == null) throw RuntimeException("Order does not exist")
        if (state.status == OrderStatus.CONFIRMED) return null // Idempotent
        if (!state.canBeConfirmed())
            throw RuntimeException("Cannot confirm order in ${state.status}")

        return OrderConfirmed(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ConfirmOrder
}

class OrderStateEvolver : Evolver<OrderEvent, OrderState> {
    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? {
        return when (event) {
            is OrderCreated -> OrderState.created(event.id)
            is OrderConfirmed -> state?.copy(status = OrderStatus.CONFIRMED)
            is OrderShipped -> state?.copy(status = OrderStatus.SHIPPED)
            is OrderCancelled -> state?.copy(
                status = OrderStatus.CANCELLED,
                cancelReason = event.reason
            )
            else -> state
        }
    }
}
```

- **Type-safe**: Compile-time event type checking
- **Concise**: Reduces boilerplate vs Java
- **Pattern Matching**: Uses Kotlin `when` semantics

---

## UI Components

### Vaadin UI

**Module:** `vaadin-ui`
**Detailed Docs:** [LLM-vaadin-ui.md](LLM-vaadin-ui.md)

#### Core Components

Package: `dk.trustworks.essentials.components.vaadin`

- **EventStoreView** - Browse event streams
- **SubscriptionView** - Monitor subscriptions
- **AggregateView** - View aggregate state
- ...

**Usage**: Include `spring-boot-starter-admin-ui` for auto-configured admin interface.

---

## Common Patterns

### Event Sourcing Stack (Spring Boot)

```xml
<dependencies>
    <!-- Auto-config EventStore + all PostgreSQL components -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    </dependency>

    <!-- Aggregate framework -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>eventsourced-aggregates</artifactId>
    </dependency>

    <!-- Optional: Admin UI -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>spring-boot-starter-admin-ui</artifactId>
    </dependency>
</dependencies>
```

### Microservices Stack (Plain JDBI)

```xml
<dependencies>
    <!-- Core patterns -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>foundation</artifactId>
    </dependency>

    <!-- PostgreSQL implementations -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>postgresql-queue</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>postgresql-distributed-fenced-lock</artifactId>
    </dependency>
</dependencies>
```

---

## See Also

### Component Documentation

**Foundation:**
- [LLM-foundation.md](LLM-foundation.md) - UnitOfWork, DurableQueues, FencedLock, Inbox/Outbox
- [LLM-foundation-types.md](LLM-foundation-types.md) - Common types (CorrelationId, EventId, etc.)
- [LLM-foundation-test.md](LLM-foundation-test.md) - Test utilities

**PostgreSQL:**
- [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) - Event Store details
- [LLM-postgresql-queue.md](LLM-postgresql-queue.md) - Queue implementation
- [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md) - Lock implementation
- [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) - Document DB details

**Spring:**
- [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md) - Spring transaction integration
- [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) - All Spring Boot starters

**MongoDB:**
- [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md) - MongoDB queue implementation
- [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB lock implementation

**Event Sourcing:**
- [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) - Aggregate framework
- [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) - Kotlin DSL

**UI:**
- [LLM-vaadin-ui.md](LLM-vaadin-ui.md) - Vaadin admin components

### README Files
- [components/README.md](../components/README.md) - Overview of all components
- Individual module READMEs in `components/<module-name>/README.md`

### Core Modules
- [LLM.md](LLM.md) - Full library overview
- [LLM-types-integrations.md](LLM-types-integrations.md) - Types framework integrations
