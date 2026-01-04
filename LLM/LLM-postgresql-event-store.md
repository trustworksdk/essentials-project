# PostgreSQL Event Store - LLM Reference

> Full documentation: [README](../components/postgresql-event-store/README.md)

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`
- **Purpose**: Full-featured Event Store with durable subscriptions, gap handling, reactive streaming
- **Key deps**: PostgreSQL, JDBI 3, Jackson, Reactor Core (`provided` scope)
- **Pattern**: Separate table per AggregateType
- **Security**: ⚠️ Table/column names use String concatenation - MUST sanitize all config values
- **Status**: WORK-IN-PROGRESS

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
- [Configuration Reference](#configuration-reference)
  - [EventStoreSubscriptionObserver](#eventstoresubscriptionobserver)
  - [IdentifierColumnType](#identifiercolumntype)
  - [JSONColumnType](#jsoncolumntype)
  - [EventStreamTableColumnNames](#eventstreamtablecolumnnames)
  - [Event Polling](#event-polling-low-level)
- [Gotchas](#gotchas)

## Core Concepts

| Concept | Type | Description                                                              |
|---------|------|--------------------------------------------------------------------------|
| **EventStream** | Logical | Events for `AggregateType` (e.g., "Orders" → `orders_events` table)      |
| **AggregateEventStream** | Logical | Events for specific aggregate instance (e.g., "Order-123")               |
| **EventOrder** | `long` (0-based) | Per-aggregate sequence - **strict ordering guaranteed**                  |
| **GlobalEventOrder** | `long` (1-based) | Per-AggregateType sequence - **may arrive out-of-order**                 |
| **Typed Events** | Default | Java FQCN identifier - auto deserialize via `deserialize()`              |
| **Named Events** | Optional | Logical string name - manual JSON via `getJson()`                        |
| **Gap** | Temporary | Missing `GlobalEventOrder` (transient=concurrent tx, permanent=rollback) |

**Key Classes**:
- `PostgresqlEventStore` - Main EventStore implementation
- `PersistableEvent` - Event representation ready for persistence (before storage) - created by `PersistableEventMapper`
- `PersistableEventMapper` - Maps domain events to PersistableEvent (you implement)
- `PersistedEvent` - Event wrapper with metadata (after storage)
- `AggregateEventStream<ID>` - Stream of events for a specific aggregate
- `EventStoreSubscriptionManager` - Subscription management

### EventStore vs ConfigurableEventStore

| Interface | Purpose |
|-----------|---------|
| **`EventStore`** | Event operations (`appendToStream`, `fetchStream`, polling, subscriptions) |
| **`ConfigurableEventStore<CONFIG>`** | Extends `EventStore` + register aggregate types, projectors, interceptors |

**Usage:**
- `EventStore` - Type for injected dependencies in application code
- `ConfigurableEventStore<CONFIG>` - Setup/configuration phase (register aggregate types, add interceptors)
- `PostgresqlEventStore` implements both

```java
// Setup - use ConfigurableEventStore
ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore = new PostgresqlEventStore<>(...);
eventStore.addAggregateEventStreamConfiguration(AggregateType.of("Orders"), OrderId.class);
eventStore.addEventStoreInterceptor(new MyInterceptor());

// Application - inject as EventStore
@Service
public class OrderService {
    private final EventStore eventStore; // Use EventStore interface
    // ...
}
```

## Setup

### Minimal Configuration

```java
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
    Optional.empty(),  // Optional: EventBus for in-tx publishing using FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream interceptor
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory),
    new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver()
);

// 4. Register aggregate types - this is required before any events related to this AggregateType can be persisted
eventStore.addAggregateEventStreamConfiguration(
    AggregateType.of("Orders"), OrderId.class);
```

### PersistableEventMapper Implementation

```java
public class MyPersistableEventMapper implements PersistableEventMapper {
    /**
     * @param aggregateId   Aggregate instance ID (e.g., OrderId, CustomerId)
     * @param config        Aggregate type config (contains aggregateType, serialization settings)
     * @param event         Domain event to persist (e.g., OrderCreated, ProductAdded)
     * @param eventOrder    Event order within aggregate (0-based sequence)
     * @return PersistableEvent ready for storage
     */
    @Override
    public PersistableEvent map(Object aggregateId, AggregateTypeConfiguration config,
                                Object event, EventOrder eventOrder) {
        return PersistableEvent.from(
            EventId.random(),                           // Unique event ID
            config.aggregateType,                       // Aggregate type (e.g., "Orders")
            aggregateId,                                // Aggregate instance ID
            EventTypeOrName.with(event.getClass()),    // Event type (FQCN for typed events)
            // EventTypeOrName.with(EventName.of("OrderCreated")),  // Named events alternative
            event,                                      // Event payload (serialized to JSON)
            eventOrder,                                 // Event order within aggregate
            EventRevision.of(1),                       // Event schema version
            new EventMetaData(),                       // Metadata (empty or populated)
            OffsetDateTime.now(ZoneOffset.UTC),        // Timestamp (UTC)
            null,                                       // causedByEventId - causal event ref (optional)
            CorrelationId.random(),                    // Correlation ID for tracking
            null                                        // tenant - multi-tenancy ID (optional)
        );
    }
}
```

## Event Operations

Note: All operations must be performed within a `UnitOfWork` or a Spring Managed **transaction**. See [foundation UnitOfWork - Transactions](./LLM-foundation.md#unitofwork-transactions)

### Append Events

```java
// Example with explicit UnitOfWork
eventStore.unitOfWorkFactory().usingUnitOfWork(() -> {
    // No concurrency control
    eventStore.appendToStream(orders, orderId,
        new OrderCreated(orderId, customerId),
        new ProductAdded(orderId, productId, 2));
});

// With optimistic concurrency control (higher performance on appends)
eventStore.appendToStream(orders, orderId,
    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,  // First event
    new OrderCreated(orderId, customerId));

eventStore.appendToStream(orders, orderId,
    EventOrder.of(0),  // Append after event order 0 (expects event with eventorder 0 to exist)
    new ProductAdded(orderId, productId, 2));
```

**Exception**: `OptimisticAppendToStreamException` if another tx appended after specified order.

### Fetch Events

```java
// Complete stream
Optional<AggregateEventStream<OrderId>> stream =
    eventStore.fetchStream(orders, orderId);

stream.ifPresent(s -> {
    List<PersistedEvent> events = s.eventList();
    Optional<EventOrder> lastOrder = s.eventOrderOfLastEvent();  // For concurrency
    boolean isPartial = s.isPartialStream();
});

// From specific event order (partial rehydration)
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
    JsonNode node = objectMapper.readTree(json);  // Manual parsing
}

// Bulk deserialization (from eventsourced-aggregates module)
List<OrderEvent> events = EventStreamEvolver.extractEventsAsList(
    stream.eventList(), OrderEvent.class);
```

### PersistedEvent API

Interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent

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

Interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager

### Setup SubscriptionManager

```java
var subscriptionManager = EventStoreSubscriptionManager.builder()
    .setEventStore(eventStore)
    .setEventStorePollingBatchSize(10)
    .setEventStorePollingInterval(Duration.ofMillis(100))
    .setFencedLockManager(fencedLockManager)  // Required for exclusive subs
    .setSnapshotResumePointsEvery(Duration.ofSeconds(10))
    .setDurableSubscriptionRepository(
        new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
    .build();

subscriptionManager.start();
```

### Subscription Types

| Type | Transaction | Exclusive         | Resume Points | Use Case |
|------|------------|-------------------|---------------|----------|
| **Async** | Out-of-tx | No                | Yes | External integrations |
| **Exclusive Async** | Out-of-tx | Yes (FencedLock)  | Yes | Single processor (inventory) |
| **In-Transaction** | Same tx | No                | No | Consistent projections |
| **Exclusive In-Tx** | Same tx | Yes (FencedLock)  | No | Critical projections |

### Async Subscription

EventHandler interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PersistedEventHandler

```java
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

**Resume Points**: Tracks last processed `GlobalEventOrder`, resumes from checkpoint on restart.
**First Subscription**: `onFirstSubscriptionSubscribeFromAndIncluding` only applies when no resume point exists.

### Exclusive Async Subscription

EventHandler interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PersistedEventHandler

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

EventHandler interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.TransactionalPersistedEventHandler

```java
// Non-exclusive (all instances process)
subscriptionManager.subscribeToAggregateEventsInTransaction(
    SubscriberId.of("OrderProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),
    new TransactionalPersistedEventHandler() {
        public void handle(PersistedEvent event, UnitOfWork uow) {
            // Runs in SAME tx - exception = rollback entire tx
            updateProjection(event, uow);
        }
    }
);

// Exclusive (one instance per cluster)
subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(
    SubscriberId.of("CriticalProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),
    new FencedLockAwareSubscriber() { /* ... */ },
    new PatternMatchingTransactionalPersistedEventHandler() { /* ... */ }
);
```

**No Resume Points**: Events processed synchronously within appendToStream transaction.

### Pattern Matching Handlers

**PatternMatchingPersistedEventHandler** (async):

```java
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

**Unmatched Events**: Default throws `IllegalArgumentException`. Call `allowUnmatchedEvents()` to ignore or override `handleUnmatchedEvent(...)`.

### Batched Subscription

```java
subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
    SubscriberId.of("Analytics"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(),          // No tenant filter
    100,                      // max batch size
    Duration.ofSeconds(5),    // max latency
    events -> analyticsService.processBatch(events)
);
```

## EventProcessor Framework

### Choose Processor

| Processor | Processing | Exclusive | Latency | Consistency | Replay | Best For |
|-----------|-----------|-----------|---------|-------------|--------|----------|
| `EventProcessor` | Async (Inbox) | Yes | Higher | Eventual | Yes | External integrations, long ops |
| `InTransactionEventProcessor` | Sync (in-tx) | Configurable | Lowest | Strong | No | Consistent projections |
| `ViewEventProcessor` | Direct + queue on failure | Yes | Low | Eventual | Yes | Low-latency views |


### EventProcessor (Inbox-based)

External system integrations (Kafka, email, webhooks), long-running operations, operations that may fail and need retry. Events are queued to an Inbox and processed by background workers with configurable parallelism and redelivery policies. Higher latency but maximum reliability.

```java
@Service
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

**Features**: Exclusive subscription (`FencedLock`), ordered processing per aggregate (`OrderedMessage`) via `Inbox`, redelivery via `RedeliveryPolicy`, Command handling (`@CmdHandler`) via `DurableLocalCommandBus`

### InTransactionEventProcessor

View projections that must be atomically consistent with events. Processing happens synchronously within the same database transaction that appends the event. If processing fails, the entire transaction (including the event) rolls back. Lowest latency but no replay capability.

```java
@Service
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

View projections where low latency is critical but occasional failures are acceptable. 
Events are handled directly (no queue) for minimal latency. On failure, events are queued to a [DurableQueue](./LLM-foundation.md#durablequeues-messaging) for retry. 
If the queue has pending messages for an aggregate, new events are also queued to maintain ordering. Best balance of latency and reliability for read models.

```java
@Service
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

**Custom Projector**:

Interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.InMemoryProjector  
Supporting class: dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver

```java
public class OrderSummaryProjector implements InMemoryProjector {
    public boolean supports(Class<?> projectionType) {
        return OrderSummary.class.equals(projectionType);
    }

    public <ID, PROJECTION> Optional<PROJECTION> projectEvents(
            AggregateType type, ID id, Class<PROJECTION> projClass, EventStore store) {
        return store.fetchStream(type, id).map(stream -> {
            var summary = new OrderSummary();
            // You can also use EventStreamEvolver.extractEvents(stream.eventList(), OrderEvent.class) to deserialize events
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

For `@EventHandler`, `AnnotationBasedInMemoryProjector` and `EventStreamEvolver` patterns, see [eventsourced-aggregates: In-Memory Projections](./LLM-eventsourced-aggregates.md#in-memory-projections) and [eventsourced-aggregates: EventStreamEvolver](./LLM-eventsourced-aggregates.md#eventstreamevolver).

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

**Gap Types**:

| Type | Cause | Resolution |
|------|-------|------------|
| **Transient** | Concurrent tx not yet committed | Subscription waits/retries |
| **Permanent** | Tx rolled back (timeout exceeded) | Excluded from queries |

**Ordering Guarantees**:

| Order Type | Guarantee | Reason                                                             |
|------------|-----------|--------------------------------------------------------------------|
| `EventOrder` | **Strict per-aggregate** | Unique constraint + optimistic concurrency                         |
| `GlobalEventOrder` | **No strict guarantee** | Events across aggregates may arrive out-of-order and can have gaps |

**Per-aggregate ordering is always preserved** - gaps only affect GlobalEventOrder across different aggregates.

**Configuration**:

```java
// Enable (default)
var eventStore = new PostgresqlEventStore<>(...,
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory), ...);

// Disable
var eventStore = new PostgresqlEventStore<>(...,
    es -> new NoEventStreamGapHandler<>(), ...);

// Reset permanent gaps
eventStore.resetPermanentGapsFor(AggregateType.of("Orders"));
```

## Interceptors & EventBus

### EventStoreInterceptor

Interface: dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor

```java
interface EventStoreInterceptor {
  default <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation, EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> eventStoreInterceptorChain)
  default <ID> Optional<PersistedEvent> intercept(LoadLastPersistedEventRelatedTo<ID> operation, EventStoreInterceptorChain<LoadLastPersistedEventRelatedTo<ID>, Optional<PersistedEvent>> eventStoreInterceptorChain)
  default Optional<PersistedEvent> intercept(LoadEvent operation, EventStoreInterceptorChain<LoadEvent, Optional<PersistedEvent>> eventStoreInterceptorChain)
  default List<PersistedEvent> intercept(LoadEvents operation, EventStoreInterceptorChain<LoadEvents, List<PersistedEvent>> eventStoreInterceptorChain)
  default <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation, EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> eventStoreInterceptorChain)
  default Stream<PersistedEvent> intercept(LoadEventsByGlobalOrder operation, EventStoreInterceptorChain<LoadEventsByGlobalOrder, Stream<PersistedEvent>> eventStoreInterceptorChain)
}
```
Example:
```java
eventStore.addEventStoreInterceptor(new LoggingEventStoreInterceptor());

public class LoggingEventStoreInterceptor implements EventStoreInterceptor {
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

**Built-in Interceptors**:
- `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream` - Publish at `CommitStage.Flush` (requires `EventStoreEventBus`)
- `MicrometerTracingEventStoreInterceptor` - Distributed tracing
- `RecordExecutionTimeEventStoreInterceptor` - Execution time metrics

### EventStoreEventBus

If you have a shared `EventStoreEventBus` then you can provide it to the `PostgresqlEventStore` constructor. If left out, a default instance is created.
Note: When sharing the `EventStoreEventBus` then `PersistedEvents` are published together with events from other components, so subscribers need to take this into account.

```java
var eventBus = new EventStoreEventBus(unitOfWorkFactory);
var eventStore = new PostgresqlEventStore<>(..., Optional.of(eventBus), ...);

// Sync subscribers (BEFORE commit)
eventBus.addSyncSubscriber(PersistedEvent persistedEvent ->
    events.forEach(e -> updateProjection(e)));

// Async subscribers (AFTER commit)
eventBus.addAsyncSubscriber(PersistedEvent persistedEvent ->
    events.forEach(e -> sendNotification(e)));
```

**CommitStage**: `Flush` (with interceptor), `BeforeCommit`, `AfterCommit`, `AfterRollback`.

## Multitenancy

Configure a `TenantSerializer` with the `SeparateTablePerAggregateTypePersistenceStrategy` to enable multi-tenancy and expand your `PersistableEventMapper` with tenant mapping.

```java
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
// Load events for a specific tenant
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
        orders,         // aggregate type
        LongRange.from(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue()),
        List.of(),     // a list of additional global orders 
        tenant         // tenant filter
);

// Read tenant
event.tenant().ifPresent(t -> log.info("Tenant: {}", t));
```

**Built-in**: `TenantId`, `TenantSerializer.TenantIdSerializer`, `TenantSerializer.NoSupportForMultiTenancySerializer`.

## Configuration Reference

### EventStoreSubscriptionObserver

Interface: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver`

Provides observability into `EventStore` operations and subscription lifecycle.

**Tracks**:
- Subscription lifecycle (start/stop, lock acquire/release)
- Event polling (batch sizes, durations, gaps)
- Event handling (processing times, failures)
- Resume point resolution

**Built-in Implementations**:

| Implementation | Use Case |
|----------------|----------|
| `NoOpEventStoreSubscriptionObserver` | Default - no observability |
| `MeasurementEventStoreSubscriptionObserver` | Micrometer metrics (production monitoring) |

**Configuration**:

```java
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

**Metrics tracked** (MeasurementEventStoreSubscriptionObserver):
- Event handling duration/failures
- Polling batch sizes/durations
- Gap reconciliation times
- Lock acquisition/release
- Resume point resolution

**Monitoring integration**: Prometheus, Datadog, New Relic, CloudWatch (via Micrometer).

See [README EventStoreSubscriptionObserver](../components/postgresql-event-store/README.md#eventstoresubscriptionobserver) for custom implementations.

### IdentifierColumnType

| Value | PostgreSQL Type | Use Case                                                                                  |
|-------|-----------------|-------------------------------------------------------------------------------------------|
| `UUID` | UUID | UUID-based IDs (recommended - but requires all id's are UUID's / use `RandomIdGenerator`) |
| `TEXT` | TEXT | String-based IDs                                                                          |

### JSONColumnType

| Value | PostgreSQL Type | Use Case |
|-------|-----------------|----------|
| `JSONB` | JSONB | **Recommended** - indexable, queryable |
| `JSON` | JSON | Raw JSON |

### EventStreamTableColumnNames

```java
// Use defaults (recommended)
var columns = EventStreamTableColumnNames.defaultColumnNames();

// Custom (⚠️ sanitize all names - see Security section)
var columns = EventStreamTableColumnNames.builder()
    .globalOrderColumn("global_order")          // Default
    .timestampColumn("timestamp")               // Default
    .eventIdColumn("event_id")                  // Default
    .aggregateIdColumn("aggregate_id")          // Default
    .eventOrderColumn("event_order")            // Default
    .eventTypeColumn("event_type")              // Default
    .eventRevisionColumn("event_revision")      // Default
    .eventPayloadColumn("event_payload")        // Default
    .eventMetaDataColumn("event_metadata")      // Default
    .causedByEventIdColumn("caused_by_event_id") // Default
    .correlationIdColumn("correlation_id")      // Default
    .tenantColumn("tenant")                     // Default
    .build();
```

### Event Polling (Low-Level)

**Prefer EventStoreSubscriptionManager for production.**

```java
// Backpressure-supporting
Flux<PersistedEvent> flux = eventStore.pollEvents(
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,  // from (inclusive)
    Optional.of(100),                            // batch size
    Optional.of(Duration.ofMillis(500)),         // poll interval
    Optional.of(tenant),                         // tenant filter
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

- Skip concurrency control: `appendToStream(orders, id, event)` without expected order (no optimistic concurrency and lower performance)
- Use mutable events with setters - events should be immutable
- Use transient subscriptions for critical processing - loses events on restart
- Trust GlobalEventOrder for strict ordering - only EventOrder is guaranteed per-aggregate
- Forget to sanitize table/column names from external input - SQL injection risk
- Use `EventProcessor` for projections - use `InTransactionEventProcessor` or `ViewEventProcessor`
- Process events outside UnitOfWork when using in-transaction subscriptions

### Common Mistakes

**Concurrency control**:
```java
// ❌ No concurrency control
eventStore.appendToStream(orders, id, event);

// ✅ With optimistic concurrency
try {
    eventStore.appendToStream(orders, id, EventOrder.of(5), event);
} catch (OptimisticAppendToStreamException e) {
    var current = eventStore.fetchStream(orders, id);
    // Reload, recompute, retry
}
```

**Subscription resume points**:
```java
// ❌ Transient subscription
eventStore.pollEvents(...);  // Loses events on restart without resume point

// ✅ Durable subscription
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    SubscriberId.of("MyHandler"), ...);  // Resume point persisted
```

**Projection consistency**:
```java
// ❌ Eventual consistency when strong consistency needed
public class Projection extends EventProcessor { /* ... */ }

// ✅ Strong consistency
public class Projection extends InTransactionEventProcessor { /* ... */ }
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk. 
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

⚠️ **CRITICAL**: Table names, column names, and `AggregateType` values are used with **String concatenation** to create SQL statements.

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
