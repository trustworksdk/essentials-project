# PostgreSQL Event Store - LLM Reference

> Full documentation: [README](../components/postgresql-event-store/README.md)

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`
- **Purpose**: Full-featured Event Store with durable subscriptions, gap handling, reactive streaming
- **Key deps**: PostgreSQL, JDBI 3, Jackson, Reactor Core (`provided` scope)
- **Pattern**: Separate table per `AggregateType`
- **Security**: ⚠️ Table/column names use String concatenation - MUST sanitize config values
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-store</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `UnitOfWork`, `UnitOfWorkFactory`, `HandleAwareUnitOfWorkFactory` from [foundation](./LLM-foundation.md)
- `FencedLockManager` from [foundation](./LLM-foundation.md) (for exclusive subscriptions)
- `DurableQueues` from [foundation](./LLM-foundation.md) (for `EventProcessor` inbox)
- `AggregateType`, `EventId`, `EventOrder`, `EventName`, `Tenant` from [foundation-types](./LLM-foundation-types.md)
- `JSONSerializer` from [foundation](./LLM-foundation.md)

## TOC

- [Core Concepts](#core-concepts)
- [Setup](#setup)
- [Event Operations](#event-operations)
- [Subscriptions](#subscriptions)
- [EventProcessor Framework](#eventprocessor-framework)
- [In-Memory Projections](#in-memory-projections)
- [Gap Handling](#gap-handling)
- [Interceptors & EventBus](#interceptors--eventbus)
- [Multitenancy](#multitenancy)
- [Configuration](#configuration)
- [Gotchas](#gotchas)
- ⚠️ [Security](#security)

## Core Concepts

Base package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

| Concept | Type/Class | Description |
|---------|------------|-------------|
| **EventStream** | Logical | Events for `AggregateType` (e.g., "Orders" → `orders_events` table) |
| **AggregateEventStream** | `eventstream.AggregateEventStream<ID>` | Events for specific aggregate instance |
| **EventOrder** | `long` (0-based) | Per-aggregate sequence - **strict ordering guaranteed** |
| **GlobalEventOrder** | `long` (1-based) | Per-AggregateType sequence - **may have gaps/out-of-order** |
| **Typed Events** | Default | Java FQCN - auto deserialize via `event().deserialize()` |
| **Named Events** | Optional | String name - manual JSON via `event().getJson()` |
| **Gap** | Temporary | Missing `GlobalEventOrder` (transient or permanent) |

### Key Classes

Base package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

| Class | Package Suffix | Role |
|-------|----------------|------|
| `PostgresqlEventStore` | (root) | Main implementation (implements both `EventStore` and `ConfigurableEventStore`) |
| `PersistableEvent` | `persistence` | Event before storage - created by `PersistableEventMapper` |
| `PersistableEventMapper` | `persistence` | Interface - maps domain events to `PersistableEvent` |
| `PersistedEvent` | `eventstream` | Event after storage with metadata |
| `AggregateEventStream<ID>` | `eventstream` | Stream of events for aggregate instance |
| `EventStoreSubscriptionManager` | `subscription` | Subscription lifecycle management |

### EventStore vs ConfigurableEventStore

| Interface | Purpose | When to Use |
|-----------|---------|-------------|
| `EventStore` | Event operations (`appendToStream`, `fetchStream`, polling, subscriptions) | Injected dependencies in application code |
| `ConfigurableEventStore<CONFIG>` | Extends `EventStore` + register types, projectors, interceptors | Setup/configuration phase |

**Implementation**: `PostgresqlEventStore` implements both.

```java
// Setup phase - use ConfigurableEventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;

ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore =
    new PostgresqlEventStore<>(...);
eventStore.addAggregateEventStreamConfiguration(AggregateType.of("Orders"), OrderId.class);
eventStore.addEventStoreInterceptor(new MyInterceptor());

// Application code - inject as EventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;

public class OrderService {
    private final EventStore eventStore;
    // ...
}
```

## Setup

### Minimal Configuration

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;

// 1. JDBI + Jackson
var jdbi = Jdbi.create(url, user, pass);
jdbi.installPlugin(new PostgresPlugin());

ObjectMapper mapper = JsonMapper.builder()
    .addModule(new EssentialTypesJacksonModule())
    .addModule(new EssentialsImmutableJacksonModule())
    .build();

// 2. EventStore components
var jsonSerializer = new JacksonJSONEventSerializer(mapper);
var unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);

var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi, unitOfWorkFactory, new MyPersistableEventMapper(),
    SeparateTablePerAggregateTypeEventStreamConfigurationFactory
        .standardSingleTenantConfiguration(
            jsonSerializer,
            IdentifierColumnType.TEXT,
            JSONColumnType.JSONB
        )
);

// 3. EventStore
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),  // Optional EventBus for in-tx publishing
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory),
    new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver()
);

// 4. Register aggregate types - REQUIRED before persisting events
eventStore.addAggregateEventStreamConfiguration(
    AggregateType.of("Orders"), OrderId.class);
```

### PersistableEventMapper Implementation

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper`

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.components.foundation.types.EventOrder;
import dk.trustworks.essentials.components.foundation.types.EventRevision;
import dk.trustworks.essentials.components.foundation.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;

public class MyPersistableEventMapper implements PersistableEventMapper {
    @Override
    public PersistableEvent map(Object aggregateId, AggregateTypeConfiguration config,
                                Object event, EventOrder eventOrder) {
        return PersistableEvent.from(
            EventId.random(),
            config.aggregateType,
            aggregateId,
            EventTypeOrName.with(event.getClass()),    // Typed events
            // EventTypeOrName.with(EventName.of("OrderCreated")),  // Named events
            event,
            eventOrder,
            EventRevision.of(1),
            new EventMetaData(),
            OffsetDateTime.now(ZoneOffset.UTC),
            null,                    // causedByEventId (optional)
            CorrelationId.random(),
            null                     // tenant (optional)
        );
    }
}
```

## Event Operations

**All operations require `UnitOfWork` or Spring Managed transaction**. See [foundation UnitOfWork](./LLM-foundation.md#unitofwork-transactions).

### Append Events

```java
import dk.trustworks.essentials.components.foundation.types.AggregateType;

var orders = AggregateType.of("Orders");

// Without concurrency control
eventStore.unitOfWorkFactory().usingUnitOfWork(() -> {
    eventStore.appendToStream(orders, orderId,
        new OrderCreated(orderId, customerId),
        new ProductAdded(orderId, productId, 2));
});

// With optimistic concurrency (recommended)
eventStore.appendToStream(orders, orderId,
    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
    new OrderCreated(orderId, customerId));

eventStore.appendToStream(orders, orderId,
    EventOrder.of(0),  // Expect event 0 exists
    new ProductAdded(orderId, productId, 2));
```

**Exception**: `OptimisticAppendToStreamException` if concurrent modification.

### Fetch Events

```java
// Complete stream
Optional<AggregateEventStream<OrderId>> stream =
    eventStore.fetchStream(orders, orderId);

stream.ifPresent(s -> {
    List<PersistedEvent> events = s.eventList();
    Optional<EventOrder> lastOrder = s.eventOrderOfLastEvent();
    boolean isPartial = s.isPartialStream();
});

// From specific event order
var partial = eventStore.fetchStream(orders, orderId, EventOrder.of(5));

// By global order range
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    orders, LongRange.from(100, 200));
```

### Deserialize Events

```java
// Typed events (default)
PersistedEvent pe = ...;
Object event = pe.event().deserialize();
if (event instanceof OrderCreated oc) { /* handle */ }

// Named events
if (pe.event().getEventName().isPresent()) {
    String name = pe.event().getEventName().get().toString();
    String json = pe.event().getJson();
    // Manual parsing
}

// Bulk deserialization (from eventsourced-aggregates module)
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver;

List<OrderEvent> events = EventStreamEvolver.extractEventsAsList(
    stream.eventList(), OrderEvent.class);
```

### PersistedEvent API

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent`

| Method | Type | Description |
|--------|------|-------------|
| `eventId()` | `EventId` | Unique identifier |
| `eventOrder()` | `EventOrder` | Per-aggregate (0-based) |
| `globalEventOrder()` | `GlobalEventOrder` | Per-type (1-based) |
| `aggregateType()` | `AggregateType` | Type classification |
| `aggregateId()` | `Object` | Instance ID |
| `event()` | `EventJSON` | Call `.deserialize()` or `.getJson()` |
| `timestamp()` | `OffsetDateTime` | UTC timestamp |
| `correlationId()` | `Optional<CorrelationId>` | Correlation tracking |
| `tenant()` | `Optional<Tenant>` | Multi-tenancy |

## Subscriptions

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager`

### Setup SubscriptionManager

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;

var subscriptionManager = EventStoreSubscriptionManager.builder()
    .setEventStore(eventStore)
    .setEventStorePollingBatchSize(10)
    .setEventStorePollingInterval(Duration.ofMillis(100))
    .setFencedLockManager(fencedLockManager)  // Required for exclusive
    .setSnapshotResumePointsEvery(Duration.ofSeconds(10))
    .setDurableSubscriptionRepository(
        new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
    .build();

subscriptionManager.start();
```

### Subscription Types

| Type | Transaction | Exclusive | Resume Points | Use Case |
|------|------------|-----------|---------------|----------|
| **Async** | Out-of-tx | No | Yes | External integrations |
| **Exclusive Async** | Out-of-tx | Yes (FencedLock) | Yes | Single processor |
| **In-Transaction** | Same tx | No | No | Consistent projections |
| **Exclusive In-Tx** | Same tx | Yes (FencedLock) | No | Critical projections |

### Async Subscription

Handler interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PersistedEventHandler`

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.types.*;

subscriptionManager.subscribeToAggregateEventsAsynchronously(
    SubscriberId.of("EmailNotifier"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,  // onFirstSubscriptionSubscribeFromAndIncluding
    Optional.empty(),  // tenant filter
    new PatternMatchingPersistedEventHandler() {
        @SubscriptionEventHandler
        void handle(OrderCreated event, PersistedEvent metadata) {
            sendEmail(event.customerId);
        }
    }
);
```

- **Resume Points**: Tracks last processed `GlobalEventOrder`
- **First Subscription**: `onFirstSubscriptionSubscribeFromAndIncluding` only applies when no resume point exists

### Exclusive Async Subscription

```java
subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
    SubscriberId.of("InventoryManager"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(),
    new FencedLockAwareSubscriber() {
        public void onLockAcquired(FencedLock lock, SubscriptionResumePoint resumePoint) {}
        public void onLockReleased(FencedLock lock) {}
    },
    new PersistedEventHandler() {}
);
```

### In-Transaction Subscription

Handler interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.TransactionalPersistedEventHandler`

```java
// Non-exclusive
subscriptionManager.subscribeToAggregateEventsInTransaction(
    SubscriberId.of("OrderProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),
    new TransactionalPersistedEventHandler() {
        public void handle(PersistedEvent event, UnitOfWork uow) {
            // Runs in SAME tx - exception rolls back entire tx
            updateProjection(event, uow);
        }
    }
);

// Exclusive
subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(
    SubscriberId.of("CriticalProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),
    new FencedLockAwareSubscriber() {},
    new PatternMatchingTransactionalPersistedEventHandler() {}
);
```

**No Resume Points**: Events processed synchronously within `appendToStream` transaction.

### Pattern Matching Handlers

**PatternMatchingPersistedEventHandler** (async):

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;

public class OrderHandler extends PatternMatchingPersistedEventHandler {
    @SubscriptionEventHandler
    void handle(OrderCreated event) {}  // Event only

    @SubscriptionEventHandler
    void handle(ProductAdded event, PersistedEvent metadata) {}  // Event + metadata

    @SubscriptionEventHandler
    void handle(String json, PersistedEvent metadata) {}  // Named events (raw JSON)

    @Override
    public void onResetFrom(EventStoreSubscription sub, GlobalEventOrder order) {
        // Clear projections on reset
    }
}
```

**Unmatched Events**: Default throws `IllegalArgumentException`. Call `allowUnmatchedEvents()` to ignore or override `handleUnmatchedEvent(...)`.

**PatternMatchingTransactionalPersistedEventHandler** (in-tx):

```java
public class OrderProjection extends PatternMatchingTransactionalPersistedEventHandler {
    @SubscriptionEventHandler
    void handle(OrderCreated event, UnitOfWork uow) {}

    @SubscriptionEventHandler
    void handle(ProductAdded event, UnitOfWork uow, PersistedEvent metadata) {}

    @SubscriptionEventHandler
    void handle(String json, UnitOfWork uow, PersistedEvent metadata) {}  // Named events
}
```

**Unmatched Events**: Same as async version.

### Batched Subscription

```java
subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
    SubscriberId.of("Analytics"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(),
    100,                      // max batch size
    Duration.ofSeconds(5),    // max latency
    events -> analyticsService.processBatch(events)
);
```

## EventProcessor Framework

Base package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor`

### Choose Processor

| Processor | Processing | Exclusive | Latency | Consistency | Replay | Best For |
|-----------|-----------|-----------|---------|-------------|--------|----------|
| `EventProcessor` | Async (Inbox) | Yes | Higher | Eventual | Yes | External integrations, long ops |
| `InTransactionEventProcessor` | Sync (in-tx) | Configurable | Lowest | Strong | No | Consistent projections |
| `ViewEventProcessor` | Async Direct + queue on failure | Yes | Low | Eventual | Yes | Low-latency views |

Note: All `@MessageHandler` annotated methods accept an optional `OrderedMessage` parameter as 2. parameter.

### EventProcessor (Inbox-based)

For asynchronous external system integrations (Kafka, email, webhooks), long-running operations, operations needing retry. Events queued to `Inbox` with configurable parallelism and redelivery.
Only supports exclusive processing.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.queue.RedeliveryPolicy;

public class ShippingKafkaPublisher extends EventProcessor {
    @Override
    public String getProcessorName() { return "ShippingKafkaPublisher"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("ShippingOrders"));
    }

    @MessageHandler
    void handle(OrderShipped event) {
        kafkaTemplate.send("shipping", event);
    }

    @Override
    protected RedeliveryPolicy getInboxRedeliveryPolicy() {
        return RedeliveryPolicy.exponentialBackoff()
            .setInitialRedeliveryDelay(Duration.ofMillis(200))
            .setMaximumNumberOfRedeliveries(20)
            .build();
    }
}
```

**Features**: Exclusive (`FencedLock`), ordered per-aggregate (`OrderedMessage` via `Inbox`), redelivery (`RedeliveryPolicy`), command handling (`@CmdHandler` via `DurableLocalCommandBus`).

**`@CmdHandler` + Delayed Messages:**
```java
@MessageHandler
void on(OrderConfirmed event, OrderedMessage message) {
    // Schedule delayed command (grace period check after 15 min)
    getCommandBus().sendAndDontWait(new CheckGracePeriod(event.orderId()), Duration.ofMinutes(15));
}

@CmdHandler
void handle(CheckGracePeriod cmd) {
    // Called after delay - check state before acting (idempotent)
    if (todo.getStatus() == Status.AWAITING_GRACE_PERIOD) {
        getCommandBus().sendAndDontWait(new InitiatePayment(cmd.orderId()));
    }
}
```

### InTransactionEventProcessor

For synchronous view projections requiring atomic consistency with events. Processing happens synchronously within same database transaction. Failure rolls back entire tx including event append.
Supports exclusive as well as non-exclusive processing.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;

public class OrderViewProcessor extends InTransactionEventProcessor {
    public OrderViewProcessor(EventProcessorDependencies deps) {
        super(deps, true);  // true = exclusive, false = non-exclusive
    }

    @Override
    public String getProcessorName() { return "OrderViewProcessor"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void handle(OrderCreated event, OrderedMessage msg) {
        // Runs in SAME tx as event append
        orderViewRepo.save(new OrderView(event.orderId()));
    }
}
```

### ViewEventProcessor

For asynchronous view projections where low latency is critical but occasional failures acceptable. Events handled asynchronously directly (no queue) for minimal latency. On failure, queued to `DurableQueue` for retry. 
If the queue has pending messages for a given aggregate id, new events related to the same aggregate-id are queued to maintain ordering.
Only supports exclusive processing.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;

public class OrderDashboard extends ViewEventProcessor {
    @Override
    public String getProcessorName() { return "OrderDashboard"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void handle(OrderCreated event, OrderedMessage msg) {
        // Direct handling (low latency) - on error, queued for retry
        dashboardService.addOrder(event);
    }
}
```

**Version = EventOrder Pattern** (for JDBI/JPA view entities):
```java
@MessageHandler
void on(OrderConfirmed event, OrderedMessage message) {
    var view = repository.getById(event.orderId());
    long loadedVersion = view.version();  // Previous EventOrder
    var updated = view.withStatus(OrderStatus.CONFIRMED, message.getOrder()); // version = EventOrder

    int rows = repository.update(updated, loadedVersion); // WHERE version = :expectedVersion
    if (rows == 0) throw new OptimisticLockingException("OrderListView", event.orderId());
}
```

### @MessageHandler Signatures

| Parameters | Description |
|------------|-------------|
| `(Event)` | Event only |
| `(Event, OrderedMessage)` | Event + metadata (aggregateId, messageOrder) |

## In-Memory Projections

```java
// Register projector
eventStore.addGenericInMemoryProjector(new OrderSummaryProjector());

// Project events
Optional<OrderSummary> summary = eventStore.inMemoryProjection(
    AggregateType.of("Orders"), orderId, OrderSummary.class);
```

### Custom Projector

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.InMemoryProjector`

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.InMemoryProjector;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;

public class OrderSummaryProjector implements InMemoryProjector {
    public boolean supports(Class<?> projectionType) {
        return OrderSummary.class.equals(projectionType);
    }

    public <ID, PROJECTION> Optional<PROJECTION> projectEvents(
            AggregateType type, ID id, Class<PROJECTION> projClass, EventStore store) {
        return store.fetchStream(type, id).map(stream -> {
            var summary = new OrderSummary();
            stream.eventList().forEach(pe -> {
                switch (pe.event().deserialize()) {
                    case OrderCreated e -> summary.orderId = e.orderId();
                    case ProductAdded e -> summary.itemCount++;
                    default -> {}
                }
            });
            return (PROJECTION) summary;
        });
    }
}
```

**See also**: [eventsourced-aggregates](./LLM-eventsourced-aggregates.md) for `@EventHandler`, `AnnotationBasedInMemoryProjector`, `EventStreamEvolver` patterns.

## Gap Handling

**What are gaps?** Missing `GlobalEventOrder` values from concurrent transactions.

**Example**:
```
TX1: Insert (GlobalOrder=1) ──────────────── Commit
TX2:      Insert (GlobalOrder=2) ── Commit
TX3:           Insert (GlobalOrder=3) ── Commit

Subscription sees: 2, 3 (gap at 1!)
Later TX1 commits → resolves: 1, 2, 3
```

### Gap Types

| Type | Cause | Resolution |
|------|-------|------------|
| **Transient** | Concurrent tx not yet committed | Subscription waits/retries |
| **Permanent** | Tx rolled back (timeout exceeded) | Excluded from queries |

### Ordering Guarantees

| Order Type | Guarantee | Reason |
|------------|-----------|--------|
| `EventOrder` | **Strict per-aggregate** | Unique constraint + optimistic concurrency |
| `GlobalEventOrder` | **No strict guarantee** | Events across aggregates may arrive out-of-order |

**Per-aggregate ordering is always preserved** - gaps only affect `GlobalEventOrder` across different aggregates.

### Configuration

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;

// Enable (default)
var eventStore = new PostgresqlEventStore<>(...,
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory), ...);

// Disable
var eventStore = new PostgresqlEventStore<>(...,
    es -> new NoEventStreamGapHandler<>(), ...);

// Reset permanent gaps
eventStreamGapHandler.resetPermanentGapsFor(AggregateType.of("Orders"));
```

## Interceptors & EventBus

### EventStoreInterceptor Interface

**Interface**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor`

Allows modification of events before persistence or after load/fetch. Each method supports before, after, or around interception logic.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;

public interface EventStoreInterceptor extends Interceptor {
    // Intercept appendToStream/startStream
    default <ID> AggregateEventStream<ID> intercept(
            AppendToStream<ID> operation,
            EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {
        return chain.proceed();
    }

    // Intercept fetchStream
    default <ID> Optional<AggregateEventStream<ID>> intercept(
            FetchStream<ID> operation,
            EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> chain) {
        return chain.proceed();
    }

    // Intercept loadLastPersistedEventRelatedTo
    default <ID> Optional<PersistedEvent> intercept(
            LoadLastPersistedEventRelatedTo<ID> operation,
            EventStoreInterceptorChain<LoadLastPersistedEventRelatedTo<ID>, Optional<PersistedEvent>> chain) {
        return chain.proceed();
    }

    // Intercept loadEvent
    default Optional<PersistedEvent> intercept(
            LoadEvent operation,
            EventStoreInterceptorChain<LoadEvent, Optional<PersistedEvent>> chain) {
        return chain.proceed();
    }

    // Intercept loadEvents
    default List<PersistedEvent> intercept(
            LoadEvents operation,
            EventStoreInterceptorChain<LoadEvents, List<PersistedEvent>> chain) {
        return chain.proceed();
    }

    // Intercept loadEventsByGlobalOrder
    default Stream<PersistedEvent> intercept(
            LoadEventsByGlobalOrder operation,
            EventStoreInterceptorChain<LoadEventsByGlobalOrder, Stream<PersistedEvent>> chain) {
        return chain.proceed();
    }
}
```

### EventStoreInterceptorChain Interface

**Interface**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain`

```java
public interface EventStoreInterceptorChain<OPERATION, RESULT> {
    RESULT proceed();
    OPERATION operation();
    EventStore eventStore();
}
```

### Interceptor Management

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;

// Add single interceptor
eventStore.addEventStoreInterceptor(new LoggingEventStoreInterceptor());

// Add multiple interceptors
eventStore.addEventStoreInterceptors(List.of(interceptor1, interceptor2));

// Remove interceptor
eventStore.removeEventStoreInterceptor(interceptor);
```

### Interceptor Ordering

Uses `@InterceptorOrder` from `dk.trustworks.essentials.shared.interceptor`. **Lower value = higher priority (runs first)**.

```java
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;

@InterceptorOrder(1)   // Runs FIRST
public class SecurityInterceptor implements EventStoreInterceptor { ... }

@InterceptorOrder(10)  // Runs SECOND (default order is 10)
public class LoggingInterceptor implements EventStoreInterceptor { ... }
```

⚠️ Interceptors automatically sorted by `ConfigurableEventStore` on registration.

### Custom Interceptor Example

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.AppendToStream;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;

@InterceptorOrder(5)
public class LoggingEventStoreInterceptor implements EventStoreInterceptor {
    @Override
    public <ID> AggregateEventStream<ID> intercept(
            AppendToStream<ID> op,
            EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {
        log.info("Appending {} events to {}/{}", op.getEventsToAppend().size(),
            op.aggregateType, op.aggregateId);
        var result = chain.proceed();
        log.info("Persisted: {}", result.eventList().stream()
            .map(e -> e.globalEventOrder().longValue()).toList());
        return result;
    }
}
```

### Built-in Interceptors

**Base package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor`

| Interceptor | Package Suffix | Purpose |
|-------------|----------------|---------|
| `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream` | — | Publish events at `CommitStage.Flush` (requires `EventStoreEventBus`) |
| `MicrometerTracingEventStoreInterceptor` | `.micrometer` | Distributed tracing with Micrometer |
| `RecordExecutionTimeEventStoreInterceptor` | `.micrometer` | Execution time metrics |

### EventStoreEventBus

Optional shared event bus. If not provided, default instance created.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;

var eventBus = new EventStoreEventBus(unitOfWorkFactory);
var eventStore = new PostgresqlEventStore<>(..., Optional.of(eventBus), ...);

// Sync subscribers (BEFORE commit)
eventBus.addSyncSubscriber(events ->
    events.forEach(e -> updateProjection(e)));

// Async subscribers (AFTER commit)
eventBus.addAsyncSubscriber(events ->
    events.forEach(e -> sendNotification(e)));
```

**CommitStage**: `Flush` (with interceptor), `BeforeCommit`, `AfterCommit`, `AfterRollback`.

## Multitenancy

Configure `TenantSerializer` with `SeparateTablePerAggregateTypePersistenceStrategy` and expand `PersistableEventMapper` with tenant mapping.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;

// Enable multi-tenancy
var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi, unitOfWorkFactory, eventMapper,
    SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB,
        new TenantSerializer.TenantIdSerializer()  // Enable
    )
);

// Associate tenant in PersistableEventMapper
return PersistableEvent.from(..., tenantResolver.getCurrentTenant());

// Tenant-scoped queries
Stream<PersistedEvent> stream = eventStore.fetchStream(orders, orderId, tenant);
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    orders,
    LongRange.from(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue()),
    List.of(),
    tenant
);

// Read tenant
event.tenant().ifPresent(t -> log.info("Tenant: {}", t));
```

**Built-in**: `TenantId`, `TenantSerializer.TenantIdSerializer`, `TenantSerializer.NoSupportForMultiTenancySerializer`.

## Configuration

### EventStoreSubscriptionObserver

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver`

Observability for `EventStore` operations and subscription lifecycle.

**Tracks**: Subscription lifecycle, event polling, event handling, resume point resolution.

**Implementations**:

| Implementation | Use Case |
|----------------|----------|
| `NoOpEventStoreSubscriptionObserver` | Default - no observability |
| `MeasurementEventStoreSubscriptionObserver` | Micrometer metrics (production) |

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.*;

// No observability (default)
var eventStore = new PostgresqlEventStore<>(...,
    new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver());

// With Micrometer
var observer = new MeasurementEventStoreSubscriptionObserver(
    Optional.of(meterRegistry),
    true,  // Log slow operations
    LogThresholds.defaultThresholds(),
    null   // Optional observation registry
);
var eventStore = new PostgresqlEventStore<>(..., observer);
```

See [README EventStoreSubscriptionObserver](../components/postgresql-event-store/README.md#eventstoresubscriptionobserver) for metrics and custom implementations.

### IdentifierColumnType

Enum: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType`

| Value | PostgreSQL Type | Use Case |
|-------|-----------------|----------|
| `UUID` | UUID | UUID-based IDs (recommended - requires all IDs are UUIDs / use `RandomIdGenerator`) |
| `TEXT` | TEXT | String-based IDs |

### JSONColumnType

Enum: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType`

| Value | PostgreSQL Type | Use Case |
|-------|-----------------|----------|
| `JSONB` | JSONB | **Recommended** - indexable, queryable |
| `JSON` | JSON | Raw JSON |

### EventStreamTableColumnNames

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.EventStreamTableColumnNames;

// Use defaults (recommended)
var columns = EventStreamTableColumnNames.defaultColumnNames();

// Custom (⚠️ sanitize all names - see Security section)
var columns = EventStreamTableColumnNames.builder()
    .globalOrderColumn("global_order")
    .timestampColumn("timestamp")
    .eventIdColumn("event_id")
    .aggregateIdColumn("aggregate_id")
    .eventOrderColumn("event_order")
    .eventTypeColumn("event_type")
    .eventRevisionColumn("event_revision")
    .eventPayloadColumn("event_payload")
    .eventMetaDataColumn("event_metadata")
    .causedByEventIdColumn("caused_by_event_id")
    .correlationIdColumn("correlation_id")
    .tenantColumn("tenant")
    .build();
```

### Event Polling (Low-Level)

**Prefer `EventStoreSubscriptionManager` for production.**

```java
// Backpressure-supporting
Flux<PersistedEvent> flux = eventStore.pollEvents(
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.of(100),                            // batch size
    Optional.of(Duration.ofMillis(500)),         // poll interval
    Optional.of(tenant),
    Optional.of(SubscriberId.of("custom")),
    Optional.of(EventStorePollingOptimizer.simpleJitterAndBackoff("custom"))
);

// No backpressure
Flux<PersistedEvent> unbounded = eventStore.unboundedPollForEvents(...);

// Synchronous
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    orders, LongRange.from(1, 1000));
```

## Gotchas

### ✅ Do

- Use `InTransactionEventProcessor` for consistent projections
- Use optimistic concurrency: `appendToStream(orders, id, EventOrder.of(5), event)`
- Handle `OptimisticAppendToStreamException` and retry with current state
- Use `JSONB` for better query performance
- Use `standardSingleTenantConfiguration()` or `standardConfiguration()` factory methods
- Use immutable event classes (records or final fields)
- Include `OrderedMessage` parameter in `@MessageHandler` when needed
- Use durable subscriptions (`EventStoreSubscriptionManager`) for production

### ❌ Don't

- Skip concurrency control: `appendToStream(orders, id, event)` (lower performance, no optimistic concurrency)
- Use mutable events with setters
- Use transient subscriptions for critical processing
- Trust `GlobalEventOrder` for strict ordering - only `EventOrder` guaranteed per-aggregate
- Forget to sanitize table/column names from external input
- Use `EventProcessor` for projections - use `InTransactionEventProcessor` or `ViewEventProcessor`
- Process events outside `UnitOfWork` when using in-transaction subscriptions

### Common Mistakes

| Mistake | Problem | Solution |
|---------|---------|----------|
| `appendToStream(orders, id, event)` | No concurrency control | Use `appendToStream(orders, id, EventOrder.of(n), event)` |
| Using `eventStore.pollEvents()` | Transient, loses events on restart | Use `subscriptionManager.subscribeToAggregateEventsAsynchronously()` |
| Using `EventProcessor` for projections | Eventual consistency | Use `InTransactionEventProcessor` (strong) or `ViewEventProcessor` (low-latency) |

## Security

### ⚠️ Critical: SQL Injection Risk

Components allow customization of table/column/index/function names used with **String concatenation** → SQL injection risk.
Essentials applies naming convention validation as initial defense layer - **NOT exhaustive protection**.

**MUST sanitize**:
- `AggregateType` (converted to table name)
- `eventStreamTableName`
- All `EventStreamTableColumnNames` values
- `durableSubscriptionsTableName`

**Mitigation**:
- `PostgresqlUtil.checkIsValidTableOrColumnName()` provides initial validation
- **NOT** complete protection - NEVER use external/untrusted input
- Derive from controlled, trusted sources only
- Validate at application startup

See [README Security](../components/postgresql-event-store/README.md#security) for details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Related Modules

| Module | Purpose |
|--------|---------|
| [eventsourced-aggregates](./LLM-eventsourced-aggregates.md) | Aggregate patterns, `EventStreamEvolver`, `@EventHandler` |
| [spring-postgresql-event-store](./LLM-spring-postgresql-event-store.md) | Spring transaction integration |
| [spring-boot-starter-postgresql-event-store](./LLM-spring-boot-starter-modules.md#spring-boot-starter-postgresql-event-store) | Spring Boot auto-configuration |
| [foundation](./LLM-foundation.md) | `UnitOfWork`, `FencedLock`, `DurableQueues`, `Inbox` |
| [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) | Distributed locking |
| [postgresql-queue](./LLM-postgresql-queue.md) | Durable queues |
| [foundation-types](./LLM-foundation-types.md) | `AggregateType`, `EventId`, `EventOrder`, etc. |
