# Components - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [components/README.md](../components/README.md).

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
- [See Also](#see-also)

## Quick Facts

| Aspect | Details                                                                         |
|--------|---------------------------------------------------------------------------------|
| **Package** | `dk.trustworks.essentials.components`                                           |
| **GroupId** | `dk.trustworks.essentials`                                                      |
| **Purpose** | Distributed coordination, Messaging, event sourcing, CQRS via existing database |
| **Scope** | Intra-service coordination (same service instances, shared DB)                  |
| **Framework Deps** | All `provided` (Spring, JDBI, MongoDB optional)                                 |
| **Status** | Work-in-progress                                                                |

**Note**: This is an **index document** - see individual module docs linked below for detailed APIs and dependencies.

**Philosophy:** Use your existing PostgreSQL/MongoDB for intra-service distributed patterns instead of adding Redis/Kafka/EventStoreDB for that.

---

## Component Matrix

| Category | Module | Purpose                                                                                      | Database | Detailed Docs |
|----------|--------|----------------------------------------------------------------------------------------------|----------|---------------|
| **Foundation** | `foundation-types` | Common types (CorrelationId, EventId, AggregateType, etc.)                                   | N/A | [LLM-foundation-types.md](LLM-foundation-types.md) |
| | `foundation` | UnitOfWork, UnitOfWorkFactory, DurableQueues, FencedLock, Inbox/Outbox, `EssentialsScheduler` | Any | [LLM-foundation.md](LLM-foundation.md) |
| | `foundation-test` | Test utilities and abstractions for DurableQueues, FencedLock implementations                | N/A | [LLM-foundation-test.md](LLM-foundation-test.md) |
| **PostgreSQL** | `postgresql-event-store` | Event Store with subscriptions, gap handling                                                 | PostgreSQL | [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) |
| | `postgresql-queue` | Durable queues implementation                                                                | PostgreSQL | [LLM-postgresql-queue.md](LLM-postgresql-queue.md) |
| | `postgresql-distributed-fenced-lock` | Distributed locking                                                                          | PostgreSQL | [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md) |
| | `postgresql-document-db` | Document database (Kotlin)                                                                   | PostgreSQL | [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) |
| **Spring** | `spring-postgresql-event-store` | Spring transaction integration for EventStore                                                | PostgreSQL | [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md) |
| | `spring-boot-starter-postgresql` | Auto-config for PostgreSQL components                                                        | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-postgresql-event-store` | Auto-config for EventStore  + Postgresql components                                          | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-mongodb` | Auto-config for MongoDB components                                                           | MongoDB | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| | `spring-boot-starter-admin-ui` | Admin UI for EventStore                                                                      | PostgreSQL | [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) |
| **MongoDB** | `springdata-mongo-queue` | Durable queues implementation                                                                | MongoDB | [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md) |
| | `springdata-mongo-distributed-fenced-lock` | Distributed locking                                                                          | MongoDB | [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) |
| **Event Sourcing** | `eventsourced-aggregates` | Event-sourced aggregate implementations                                                      | Any | [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) |
| | `kotlin-eventsourcing` | Kotlin DSL for event sourcing                                                                | Any | [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) |
| **UI** | `vaadin-ui` | Vaadin UI components                                                                         | N/A | [LLM-vaadin-ui.md](LLM-vaadin-ui.md) |

**Scope:** Intra-service coordination (multiple instances of SAME service sharing DB). For cross-service messaging, use Kafka/RabbitMQ.

---

## Security

### ⚠️ Critical: SQL/NoSQL Injection Risk

Components use **String concatenation** for customizable table/column/index/function/collection names → SQL/NoSQL injection risk.

Essentials applies naming convention validation as an **initial defense layer**, but this is **NOT exhaustive protection**.

**Your Responsibility:** NEVER use unsanitized user input for:
- Table/column/index/function names (PostgreSQL)
- Collection names (MongoDB)
- Configuration values from external sources

### Built-in Validation

#### PostgreSQL Validation

**Class**: `dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil`

| Method | Validates |
|--------|-----------|
| `checkIsValidTableOrColumnName(String)` | Table, column, index names |
| `isValidFunctionName(String)` | Function names (simple or qualified) |
| `isValidSqlIdentifier(String)` | Simple SQL identifiers |
| `isValidQualifiedSqlIdentifier(String)` | Qualified identifiers (`schema.name`) |

**What PostgreSQL validation checks:**
- Must start with letter (a-z, A-Z) or underscore (`_`)
- Subsequent characters: letters, digits (0-9), or underscores
- Max length: 63 characters (simple) or 127 characters (qualified `schema.name`)
- Cannot be a reserved keyword (300+ PostgreSQL/SQL reserved words)
- For qualified names: exactly one dot, both parts must be valid identifiers

#### MongoDB Validation

**Class**: `dk.trustworks.essentials.components.foundation.mongo.MongoUtil`

| Method | Validates |
|--------|-----------|
| `checkIsValidCollectionName(String)` | Collection names |

**What MongoDB validation checks:**
- Max length: 64 characters
- Cannot contain `$` or null characters
- Cannot start with `system.` (reserved for system collections)
- Must only contain letters, digits, and underscores

### Safe Patterns

```java
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.mongo.MongoUtil;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;

// ✅ Safe - Hardcoded only
PostgresqlDurableQueues.builder()
    .setSharedQueueTableName("durable_queues");

// ✅ Safe - Validate config values before use
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);  // Throws InvalidTableOrColumnNameException
MongoUtil.checkIsValidCollectionName(collectionName);     // Throws InvalidCollectionNameException

// ✅ Safe - Check without throwing
if (PostgresqlUtil.isValidSqlIdentifier(name)) {
    // use name
}

// ❌ DANGEROUS - Never use user input directly
var tableName = userInput + "_events";  // SQL injection risk!
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
**Docs:** [LLM-foundation.md](LLM-foundation.md), [LLM-foundation-types.md](LLM-foundation-types.md), [LLM-foundation-test.md](LLM-foundation-test.md)

### Core Abstractions

Base package: `dk.trustworks.essentials.components.foundation`

| Pattern | Package Suffix | Key Interface | Purpose |
|---------|----------------|---------------|---------|
| **Transactions** | `.transaction` | `UnitOfWorkFactory<UOW>`, `UnitOfWork` | Tech-agnostic transaction mgmt |
| **Messaging** | `.messaging.queue` | `DurableQueues` | Point-to-point, At-Least-Once |
| **Locking** | `.fencedlock` | `FencedLockManager` | Distributed locks w/ fence tokens |
| **Inbox/Outbox** | `.messaging.eip.store_and_forward` | `Inbox`, `Outbox` | Store-and-forward EIP |
| **Command Bus** | `.reactive.command` | `DurableLocalCommandBus` | Durable sendAndDontWait |

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
- `dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory` - JDBI ([LLM-foundation.md](LLM-foundation.md))
- `dk.trustworks.essentials.components.foundation.transaction.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory` - Spring + JDBI ([LLM-foundation.md](LLM-foundation.md))
- `dk.trustworks.essentials.components.foundation.transaction.mongo.SpringMongoTransactionAwareUnitOfWorkFactory` - Spring + MongoDB ([LLM-foundation.md](LLM-foundation.md))

### Pattern: DurableQueues

```java
// Package: dk.trustworks.essentials.components.foundation.messaging.queue
public interface DurableQueues {
    QueueEntryId queueMessage(QueueName queueName, Message message);
    DurableQueueConsumer consumeFromQueue(ConsumeFromQueue consumeFromQueue);
}
```

- **Delivery**: At-Least-Once (may redeliver)
- **DLQ**: Built-in Dead Letter Queue support
- **Ordering**: Per-message key ordering via `OrderedMessage`

**Implementations:**
- `dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues` - PostgreSQL ([LLM-postgresql-queue.md](LLM-postgresql-queue.md))
- `dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues` - MongoDB ([LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md))

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

- Based on Martin Kleppmann's fenced token pattern
- Scope: Intra-service only (same service instances)
- Token: Monotonic `Long` fence token

**Implementations:**
- `dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager` - PostgreSQL ([LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md))
- `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo.MongoFencedLockManager` - MongoDB ([LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md))

### Foundation Types

Package: `dk.trustworks.essentials.components.foundation.types`

| Type | Purpose |
|------|---------|
| `CorrelationId` | Cross-service correlation |
| `EventId` | Unique event ID |
| `Tenant`, `TenantId` | Multitenancy |
| `SubscriberId` | Subscription ID |
| `GlobalEventOrder`, `EventOrder` | Event ordering |
| `AggregateType`, `EventName`, `EventTypeOrName` | Event Store types |

---

## PostgreSQL Components

### Event Store

**Module:** `postgresql-event-store`
**Docs:** [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md)

#### Core API

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

```java
public interface EventStore<CONFIG extends EventStoreEventStreamConfiguration> {
    AggregateEventStream<AGGREGATE_ID> fetchStream(
        AggregateType aggregateType, AGGREGATE_ID aggregateId);

    AggregateEventStream<AGGREGATE_ID> appendToStream(
        AggregateType aggregateType, AGGREGATE_ID aggregateId,
        Optional<EventOrder> expectedEventOrder, List<?> events);
}

// Implementation
public class PostgresqlEventStore<CONFIG extends EventStoreEventStreamConfiguration>
    implements EventStore<CONFIG>
```

#### Key Features

| Feature | Description |
|---------|-------------|
| **Event Streams** | Separate table per `AggregateType` |
| **Ordering** | `EventOrder` (per-aggregate, strict) + `GlobalEventOrder` (per-type, may gap) |
| **Subscriptions** | Exclusive/non-exclusive w/ `ResumePoint` tracking |
| **Gap Handling** | Detects transient (concurrent tx) vs permanent (rollback) |
| **Interceptors** | Pre/post append, publish to `EventBus` |
| **Multitenancy** | Per-tenant events in shared tables |
| **Event Processing** | `EventProcessor` w/ guaranteed delivery, replay |


#### Setup

```java
// Package: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql
var eventStore = new PostgresqlEventStore<>(
    eventStoreManagedUnitOfWorkFactory,
    separateTablePerAggregateTypePersistenceStrategy,
    Optional.of(eventStoreEventBus),
    eventStreamGapHandlerFactory,
    eventStoreSubscriptionObserver);

// Register aggregate type (required before persisting events)
eventStore.addAggregateEventStreamConfiguration(
    AggregateType.of("Orders"), OrderId.class);
```

### Queue

**Module:** `postgresql-queue`
**Docs:** [LLM-postgresql-queue.md](LLM-postgresql-queue.md)

Package: `dk.trustworks.essentials.components.queue.postgresql`

```java
public class PostgresqlDurableQueues implements DurableQueues {
    public PostgresqlDurableQueues(
        UnitOfWorkFactory<? extends JdbiUnitOfWork> unitOfWorkFactory,
        Optional<String> sharedQueueTableName);
}
```

- Table: Single shared table (customizable name)
- Delivery: At-Least-Once w/ redelivery on timeout
- DLQ: Automatic after max retries
- Ordering: Via `OrderedMessage` wrapper

### Distributed Lock

**Module:** `postgresql-distributed-fenced-lock`
**Docs:** [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md)

Package: `dk.trustworks.essentials.components.distributed.fencedlock.postgresql`

```java
public class PostgresqlFencedLockManager implements FencedLockManager {
    public PostgresqlFencedLockManager(
        UnitOfWorkFactory<? extends JdbiUnitOfWork> unitOfWorkFactory,
        Optional<String> lockTableName,
        Optional<Duration> lockTimeOut);
}
```

- Token: Monotonic via PostgreSQL sequence
- Timeout: Auto-release after inactivity
- Scope: Intra-service only

### Document Database

**Module:** `postgresql-document-db`
**Docs:** [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md)

Package: `dk.trustworks.essentials.components.document_db.postgresql`

```kotlin
// JSONB-based document storage w/ indexing, type-safe querying, versioning (Kotlin)
class PostgresqlDocumentDbRepository<ENTITY : VersionedEntity<ID, ENTITY>, ID : Any> : DocumentDbRepository<ENTITY, ID>
```

---

## MongoDB Components

### Queue

**Module:** `springdata-mongo-queue`
**Docs:** [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md)

Package: `dk.trustworks.essentials.components.queue.springdata.mongodb`

```java
public class MongoDurableQueues implements DurableQueues {
    public MongoDurableQueues(
        UnitOfWorkFactory<? extends SpringMongoTransactionAwareUnitOfWork> unitOfWorkFactory,
        MongoTemplate mongoTemplate, MongoConverter mongoConverter,
        Optional<String> sharedQueueCollectionName);
}
```

- Collection: Single collection for all queues
- Delivery: At-Least-Once
- Ordering: Via `OrderedMessage`

### Distributed Lock

**Module:** `springdata-mongo-distributed-fenced-lock`
**Docs:** [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md)

Package: `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo`

```java
public class MongoFencedLockManager implements FencedLockManager {
    public MongoFencedLockManager(
        UnitOfWorkFactory<? extends SpringMongoTransactionAwareUnitOfWork> unitOfWorkFactory,
        MongoTemplate mongoTemplate, Optional<String> fencedLocksCollectionName,
        Duration lockTimeOut);
}
```

---

## Spring Boot Starters

**Docs:** [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md)

### Starter Matrix

| Starter | Auto-Configures | Configuration Prefix |
|---------|----------------|---------------------|
| `spring-boot-starter-postgresql` | Jdbi, FencedLock, Queues, Inbox/Outbox, CommandBus | `essentials.` |
| `spring-boot-starter-postgresql-event-store` | Above + EventStore, Subscriptions | `essentials.eventstore` |
| `spring-boot-starter-mongodb` | MongoTemplate, FencedLock, Queues, Inbox/Outbox | `essentials.` |
| `spring-boot-starter-admin-ui` | Vaadin admin views for EventStore | `essentials.admin-ui` |

### Configuration Example (PostgreSQL Event Store)

```yaml
essentials:
  eventstore:
    identifier-column-type: "text"
    json-column-type: "jsonb"
    subscription-manager:
      event-store-polling-batch-size: 10
  fenced-lock-manager:
    fenced-locks-table-name: "fenced_locks"
    lock-time-out: "15s"
  durable-queues:
    shared-queue-table-name: "durable_queues"
    transactional-mode: "single-operation-transaction"
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
**Docs:** [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)

Package: `dk.trustworks.essentials.components.eventsourced.aggregates.stateful`

```java
// Stateful aggregate pattern
public interface StatefulAggregate<ID, EVENT_TYPE, AGGREGATE_TYPE>
    extends Aggregate<ID, AGGREGATE_TYPE> {
    EventsToPersist<ID, EVENT_TYPE> getUncommittedChanges();
    void markChangesAsCommitted();
}
```

Package: `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern`

```java
// Modern event-sourced aggregate w/ @EventHandler
public class AggregateRoot<ID, EVENT_TYPE, AGGREGATE_TYPE>
    implements StatefulAggregate<ID, EVENT_TYPE, AGGREGATE_TYPE>
```

**Key Types:**
- `StatefulAggregateInstanceFactory` - Create instances
- `StatefulAggregateRepository` - Load/save
- `Decider`, `EventStreamDecider`, `EventStreamEvolver` - Functional patterns

### Kotlin DSL

**Module:** `kotlin-eventsourcing`
**Docs:** [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md)

Package: `dk.trustworks.essentials.components.kotlin.eventsourcing`

```kotlin
// Decider pattern - command → event
class ConfirmOrderDecider : Decider<ConfirmOrder, OrderEvent> {
    private val evolver = OrderStateEvolver()
    override fun handle(cmd: ConfirmOrder, events: List<OrderEvent>): OrderEvent? {
        val state = Evolver.applyEvents(evolver, null, events)
        if (state == null) throw RuntimeException("Order does not exist")
        if (state.status == OrderStatus.CONFIRMED) return null // Idempotent
        if (!state.canBeConfirmed()) throw RuntimeException("Cannot confirm")
        return OrderConfirmed(cmd.id)
    }
    override fun canHandle(cmd: Any): Boolean = cmd is ConfirmOrder
}

// Evolver pattern - event → state
class OrderStateEvolver : Evolver<OrderEvent, OrderState> {
    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? =
        when (event) {
            is OrderCreated -> OrderState.created(event.id)
            is OrderConfirmed -> state?.copy(status = OrderStatus.CONFIRMED)
            is OrderShipped -> state?.copy(status = OrderStatus.SHIPPED)
            is OrderCancelled -> state?.copy(status = OrderStatus.CANCELLED, cancelReason = event.reason)
            else -> state
        }
}
```

- Type-safe compile-time checking
- Concise vs Java
- Kotlin `when` pattern matching

---

## UI Components

### Vaadin UI

**Module:** `vaadin-ui`
**Docs:** [LLM-vaadin-ui.md](LLM-vaadin-ui.md)

Package: `dk.trustworks.essentials.components.vaadin`

Key views: `EventProcessorsView`, `SubscriptionsView`, `LocksView`, `QueuesView`, `SchedulerView`

**Usage:** Include `spring-boot-starter-admin-ui` for auto-configured admin interface.

---

## Common Patterns

### Event Sourcing Stack (Spring Boot)

```xml
<dependencies>
    <!-- Auto-config EventStore + all PostgreSQL components -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    </dependency>

    <!-- Aggregate framework -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>eventsourced-aggregates</artifactId>
    </dependency>

    <!-- Optional: Admin UI -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-admin-ui</artifactId>
    </dependency>
</dependencies>
```

### Microservices Stack (Plain JDBI)

```xml
<dependencies>
    <!-- Core patterns -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>foundation</artifactId>
    </dependency>

    <!-- PostgreSQL implementations -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>postgresql-queue</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>postgresql-distributed-fenced-lock</artifactId>
    </dependency>
</dependencies>
```

---

## Gotchas

### Common Mistakes

**1. Using User Input for Table/Collection Names**
- ❌ `queueTableName = userInput + "_queue"` → SQL injection
- ✅ Use hardcoded values or validate: `PostgresqlUtil.checkIsValidTableOrColumnName(name)`

**2. Forgetting AggregateType Registration**
- ❌ Appending events before calling `eventStore.addAggregateEventStreamConfiguration()`
- ✅ Register aggregate types at startup before persisting events (or use a repository / adapter concept that auto registers the aggregate type)

**3. Cross-Service Use of FencedLocks**
- ❌ Using `FencedLockManager` across different services
- ✅ Only use within same service instances sharing database

**4. Non-Idempotent Event Handlers**
- ❌ EventProcessor handlers with side effects that can't be repeated
- ✅ Make handlers idempotent (At-Least-Once delivery = duplicates possible)

**5. Missing UnitOfWork/Transaction**
- ❌ Calling components outside transaction boundaries
- ✅ Use `UnitOfWorkFactory.usingUnitOfWork()` or Spring `@Transactional`

**6. Ignoring Gap Handling**
- ❌ Assuming `GlobalEventOrder` is always sequential
- ✅ Use gap handlers; expect transient gaps from concurrent transactions

**7. MongoDB for Event Sourcing**
- ❌ Expecting `EventStore` implementation for MongoDB
- ✅ EventStore only supports PostgreSQL; use MongoDB for foundation only

**8. Blocking Spring Boot Startup**
- ❌ Long-running subscription handlers in `@PostConstruct`
- ✅ Use async subscription modes or background threads

---

## See Also

### Module LLM Docs
- [LLM-foundation.md](LLM-foundation.md) - UnitOfWork, DurableQueues, FencedLock, Inbox/Outbox
- [LLM-foundation-types.md](LLM-foundation-types.md) - Common types
- [LLM-foundation-test.md](LLM-foundation-test.md) - Test utilities
- [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md) - Event Store
- [LLM-postgresql-queue.md](LLM-postgresql-queue.md) - PostgreSQL queues
- [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md) - PostgreSQL locks
- [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md) - Document DB
- [LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md) - Spring integration
- [LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md) - Spring Boot starters
- [LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md) - MongoDB queues
- [LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md) - MongoDB locks
- [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md) - Aggregates
- [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md) - Kotlin DSL for event sourcing
- [LLM-vaadin-ui.md](LLM-vaadin-ui.md) - Vaadin UI

### README Files
- [components/README.md](../components/README.md) - Complete overview
- Module READMEs: `components/<module>/README.md`

### Core
- [LLM.md](LLM.md) - Full library overview
- [LLM-types-integrations.md](LLM-types-integrations.md) - Types integrations
