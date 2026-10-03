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
- [Hybrid CDC](#hybrid-cdc)
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
    PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()...build();
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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;

// 1. JDBI
var jdbi = Jdbi.create(url, user, pass);
jdbi.installPlugin(new PostgresPlugin());

// 2. EventStore components
// Canonical Jackson 3 event serializer (EssentialsObjectMappers configuration: the frozen persisted wire format).
// Need extra modules? new Jackson3JSONEventSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(myModule))
var jsonSerializer = EssentialsJSONEventSerializers.create();
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
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                     .setPersistenceStrategy(persistenceStrategy)
                                     // .setEventStoreEventBus(eventBus) - optional, for in-tx publishing
                                     .setEventStreamGapHandlerFactory(es -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                     .build();

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

## Hybrid CDC

Hybrid CDC combines logical replication (`pgoutput` by default, `wal2json` optional) with polling semantics.

**Disabled by default — opt in with `essentials.eventstore.cdc.enabled=true`.** No CDC bean is created without it: no slot, no publication changes, no tailer. Without CDC the event store polls, which is the pre-CDC behaviour.

Enabling also requires, on the database side:
- `wal_level = logical` (server restart; on RDS set `rds.logical_replication` and reboot)
- `max_replication_slots` and `max_wal_senders` with headroom for one slot per pipeline
- a role with `REPLICATION` — the tailer opens its own replication connection
- for `pgoutput`, a publication covering the event-stream tables; let the framework own it with `cdc.pg-output.publication.auto-manage=true` (`mode: FOR_TABLE_LIST` needs table ownership, `FOR_ALL_TABLES` needs superuser)

If a prerequisite is missing, `cdc.mode=auto` (the default once enabled) keeps the application up and subscribers on polling — so a broken CDC setup costs latency, not correctness, and is easy to miss. Verify via `/actuator/health/cdc` or the admin API's `event-store/cdc/status`. Full checklist: [docs/cdc.md §1.1](../docs/cdc.md).

Key classes:
- `cdc.WalReplicationTailer` - consumes logical replication stream into `eventstore_cdc_inbox` or publishes directly
- `cdc.CdcDispatcher` - converts inbox rows to `PersistedEvent` and publishes live events
- `cdc.CdcEventStore` - chooses hybrid (`ACTIVE`) or polling fallback (`INACTIVE`/`FAILED`)
- `cdc.CdcAvailability` - state machine + metrics (`essentials.cdc.active`, `...fallback_total`, `...warmup_poll_total`, `...start_failures_total`). A poll on the inactive path **before** CDC has ever been active is a warm-up (`warmupPollCount`), not a fallback — subscriptions start before the WAL tailer connects, so this happens on every startup and is not an error. `fallbackCount` counts polls after CDC had been active - a subscription starting on polling, or a running one switching off the CDC bus - one per subscription per outage; that is the alertable signal. `everActive=false` with a non-zero `warmupPollCount` means CDC never came up. **Interruptions** (`CdcAvailability.interruptions()`, `interruptions` in the admin API CDC status, health details `interruptions.*`, metric `essentials.cdc.interruptions_total`) count outages once each - every departure from `ACTIVE` except a requested stop - and keep the last one's time, reason and recovery time after CDC is active again. The availability `reason` is cleared on recovery, so interruptions are where a dropped replication connection that reconnected on its own shows up

Operational model:
- advisory lock per slot ensures one active tailer per slot (`slotLockAcquired`)
- `CdcMode` controls startup semantics:
  - `require`: fail startup if CDC cannot start
  - `auto`: degrade to polling fallback
- `PgSlotMode` controls slot lifecycle (`CREATE_IF_MISSING`, `REQUIRE_EXISTING`, `RECREATE`, `EXTERNAL`)

Message filtering/conversion:
- `pgoutput` is the default plugin and requires a publication (default: `essentials_cdc_publication`)
- only configured aggregate event stream inserts are converted
- non-insert pgoutput messages are ignored by the EventStore CDC path

Poison handling:
- conversion failures mark inbox row `POISON`
- global orders are extracted and registered as permanent gaps
- `CdcPoisonNotifier` (e.g. `SubscriptionResetOnPoisonNotifier`) can reset resume points backward

Design reference:
- [Hybrid CDC design](../docs/cdc.md)

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
- **Handler failures**: skipped by default - see [Direct async subscribers skip a failing event by default](#direct-async-subscribers-skip-a-failing-event-by-default)

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

Note: `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` runs a handler with no `UnitOfWork` — and therefore no database connection — held, for handlers doing blocking I/O. Supported by `EventProcessor` only; see [Blocking I/O in a handler](#blocking-io-in-a-handler-unitofworkmodenone).

### EventProcessor (Inbox-based)

For asynchronous external system integrations (Kafka, email, webhooks), long-running operations, operations needing retry. Events queued to `Inbox` with configurable parallelism and redelivery.
Only supports exclusive processing.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;

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
        return RedeliveryPolicy.exponentialBackoff(
            Duration.ofMillis(200), // initialRedeliveryDelay
            Duration.ofMillis(200), // followupRedeliveryDelay
            1.1d,                   // followupRedeliveryDelayMultiplier
            Duration.ofSeconds(3),  // maximumFollowupRedeliveryDelayThreshold
            20);                    // maximumNumberOfRedeliveries
    }
}
```

**Features**: Exclusive (`FencedLock`), ordered per-aggregate (`OrderedMessage` via `Inbox`), redelivery (`RedeliveryPolicy`), command handling (`@CmdHandler` via `DurableLocalCommandBus`).

#### Blocking I/O in a handler: `UnitOfWorkMode.NONE`

A `@MessageHandler` method runs inside a `UnitOfWork` by default — i.e. holding a pooled connection with an open transaction. For a handler that blocks on an external system (HTTP, SOAP, SFTP, a slow gRPC call) that connection sits in `idle in transaction` for the whole round trip, one per parallel consumer, writing nothing. Declare such a handler `UnitOfWorkMode.NONE` and wrap only the database work that follows it:

```java
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.UnitOfWorkMode;

public class InstrumentRiskApprovalProcessor extends EventProcessor {
    @Override
    public String getProcessorName() { return "InstrumentRiskApprovalProcessor"; }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Instruments"));
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(InstrumentRegistered e) {
        var assessment = riskService.assess(e.instrumentId(), e.symbol());  // blocking, no UnitOfWork, no connection

        usingUnitOfWork(() -> {                                            // the transactional tail
            var instrument = instruments.getInstrument(e.instrumentId());
            instrument.recordRiskApproval(assessment.riskRating());
        });
    }

    @MessageHandler                                                        // REQUIRED (default), unchanged
    void on(InstrumentSuspended e) { ... }
}
```

**Helpers** (on `AbstractEventProcessor`, available to every processor subclass):

| Helper | Signature | Use |
|--------|-----------|-----|
| `usingUnitOfWork(...)` | `void usingUnitOfWork(CheckedRunnable)` | Transactional tail with no result |
| `withUnitOfWork(...)` | `<R> R withUnitOfWork(CheckedSupplier<R>)` | Transactional tail returning a value |

Both join an already active `UnitOfWork` if there is one. Between the blocking call and the wrapper there is no ambient `UnitOfWork`, so touching a transactional resource there fails fast rather than quietly opening a transaction — loading the aggregate *before* the call is the mistake to watch for.

**Processor support**:

| Processor | `UnitOfWorkMode.NONE` |
|-----------|----------------------|
| `EventProcessor` | **Supported.** Its inbox consumer owns the `UnitOfWork` boundary; the event reference is resolved in its own short `UnitOfWork`, and the handler's scope is the handler's own |
| `ViewEventProcessor` | **Rejected at start-up** (`IllegalStateException`). It handles each message in a single `UnitOfWork` so that the view update and the acknowledgement commit together |
| `InTransactionEventProcessor` | **Rejected at start-up** (`IllegalStateException`). It processes inside the transaction that appended the event by definition, so there is no `UnitOfWork`-free window. Don't put blocking I/O here |

**Requirements the mode shifts onto the handler**:

1. **Idempotency is mandatory.** The blocking call is no longer part of the transaction that acknowledges the message: a failure after it returned but before the tail committed redelivers the event and repeats the call. Guard on state (e.g. the aggregate applies nothing once the decision exists), don't assume once-only.
2. **The blocking call must time out well inside `DurableQueues` `messageHandlingTimeout`** (`essentials.durable-queues.message-handling-timeout`, 30s by default). Past it the message is reset as stuck and can be redelivered while the first attempt is still blocked.
3. **Ordering degrades on that timeout** — a stuck-message reset can hand the same `OrderedMessage` key to another consumer thread, so the per-key guarantee holds only while handlers complete inside the timeout.

Worked example: `market_data/use_cases/risk_approve_instrument` in `examples/essentials-trading-demo`. See also `UnitOfWorkMode` in [LLM-foundation.md](./LLM-foundation.md#blocking-io-in-a-message-handler-unitofworkmode) for handlers dispatched by an `Inbox`/`Outbox` rather than a processor.

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

Rejects `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` handlers at start-up: the view update and the acknowledgement commit in one `UnitOfWork` here, which a `NONE` handler would break. Blocking I/O belongs in an `EventProcessor`.

The direct handler runs under a savepoint in the subscription's transaction, so a failed SQL statement (e.g. a constraint violation) rolls back only the handler's own writes and the event is still queued; an event whose payload cannot be deserialized is queued too (and dead-lettered there).

A savepoint only undoes SQL. When the failed handler left `UnitOfWork` state that a savepoint cannot undo, committing the `UnitOfWork` to queue the event would act on that state although the handler failed, so the event is **not** queued in that `UnitOfWork`. That is the case when the handler:

- appended events through the `EventStore` (committing would publish them although their rows were rolled back, and a queued retry would append them again)
- left a resource registered for commit-time processing **with pending changes** - e.g. an aggregate it applied an event to, whose uncommitted events the repository's callback persists at commit. Every registered resource is asked (`UnitOfWorkLifecycleCallback.hasPendingChanges(resource)`), not only one the handler registered, since a repository hands out the instance already registered in the `UnitOfWork`. A handler that only **loaded** an aggregate leaves nothing pending and is queued as before. A custom callback that doesn't override `hasPendingChanges` answers `true` (the default), so any resource registered with it forces escalation
- marked the `UnitOfWork` rollback-only - e.g. a failure inside a joined `usingUnitOfWork`/`withUnitOfWork`

Instead the whole `UnitOfWork` rolls back - nothing the failed handler did is persisted or published - and the subscription's `SubscriptionErrorPolicy` runs its retries as for any failure (see [Direct async subscribers skip a failing event by default](#direct-async-subscribers-skip-a-failing-event-by-default)). When the policy would give up - skip under `skip()`/`retryThenSkip(...)`, stop under `stop()` - the processor takes the event over instead (`PersistedEventHandler#handOffFailedEvent`) and queues it in a `UnitOfWork` of its own, after the rollback. From there the queue's `RedeliveryPolicy` and dead-letter handling apply, exactly as for any other failed event: **under every policy the event ends up in the view or in the queue, never lost**, and a `stop()` policy does not stop the subscription for it. One WARN line records the hand-off. Only if that queueing itself fails does the policy give up on the event. The subscriber moves its resume point past the event only after the queued message committed, and later events for the same aggregate find it queued and queue behind it. A `UnitOfWork` implementation that cannot report this state (the `EventStoreUnitOfWork.getNumberOfEventsPersisted()` / `UnitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()` defaults throw `UnsupportedOperationException`) is treated as having it; every Essentials implementation reports it. The stateful, flex and decider repository callbacks report pending changes only while there are uncommitted events.

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

> ⚠️ **Two different requirements, often conflated.**
> - `@MessageHandler` is **mandatory**: an un-annotated method is not a handler. A `ViewEventProcessor` allows unmatched
>   messages, so the event is skipped silently — no exception, the handler just never runs.
> - `OrderedMessage` is **optional to the dispatcher** (single-argument handlers are invoked normally) but **required for a
>   versioned projection**: `message.getOrder()` is the `EventOrder` your update compares against the stored version (see
>   *Version = EventOrder Pattern* above). Without it, a redelivered event is applied twice. A handler with no versioned
>   state can drop it.

Attributes:

| Attribute | Values | Description |
|-----------|--------|-------------|
| `unitOfWork` | `REQUIRED` (default), `NONE` | `REQUIRED` invokes the method inside a `UnitOfWork`. `NONE` invokes it with none active, for blocking I/O — `EventProcessor` only, and the handler wraps its own transactional tail. See [Blocking I/O in a handler](#blocking-io-in-a-handler-unitofworkmodenone) |

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

Each poll re-asks for the subscriber's open transient gaps. Default `PostgresqlEventStreamGapHandler` constructors: all open
gaps up to 50; beyond that the 20 highest + 10 lowest + a rotating window of 20 (max 50 per poll). A custom
`ResolveTransientGapsToIncludeInQueryStrategy` (longer constructors) replaces that; compose
`ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection()` to keep it (each subscription keeps its own rotation
per instance, also when it is wrapped or called from your strategy; call each instance once per ask; only on the gap
handler's own thread - from an executor/`CompletableFuture` an instance falls back to one rotation shared by all). A gap is promoted only if the poll asked for it and its event was missing; every poll also asks for gaps old
enough to promote (max 50 more). Build the handler on the event store's `EventStoreUnitOfWorkFactory`: a foreign one WARNs
once and resolves gaps in its own transaction. Tenant-filtered polls never read other tenants' payloads. Transient gaps are per subscriber;
permanent gaps are shared by every subscriber of the `AggregateType`. A tenant-filtered subscription loads every tenant's
events in the polled range and filters in memory (polling and CDC alike), so other tenants' orders are never gaps. The
filter compares tenants by `TenantSerializer.serialize(...)` under the aggregate type's `TenantSerializer`, as the SQL
predicates do, not by `toString()`. CDC looks the serializer up at the first event with a tenant (an aggregate type
configured later is picked up); until then it compares `toString()` and WARNs once - polling fails instead.
A gap is resolved only once its event has been handled, so a stop or crash before that redelivers the fill rather
than losing it. `EventStoreSubscriptionManager` subscriptions do this out of the box: the subscriber acknowledges each
event via a `SubscriberAcknowledgement` and the store deletes the fill's gap inside the handler's unit of work.
Polling yourself: `SubscriberAcknowledgement.create()` per subscription (one instance serves exactly one subscription; a
registration while another is still active WARNs once; `retry()`/`repeat()` of the flux disposes the ended subscribe's
registration, no WARN, and an unacknowledged fill from it is handed on again), pass it to the `pollEvents(...)` /
`unboundedPollForEvents(...)` overload taking one (and `setSubscriberAcknowledgement(..)` on the subscriber builders),
then `acknowledge(...)` each event handled or given up on - never one skipped because you stopped. Without it the gap
resolves on hand-on, and a stopped batched subscriber holds its resume point at the lowest fill it had queued.
A custom `SubscriptionGapHandler` gets default `resolveFilledGaps(AggregateType, List<PersistedEvent>)`, serialized calls
per subscription, and must not promote a gap whose event is in the events it is given. When a CDC subscription gives up
waiting for a gap's event (after `transientGapGiveUpThreshold()`) it calls the handler's default
`giveUpTransientGaps(AggregateType, List<GlobalEventOrder>)`, so the give-up is durable: a late-committing event for that
gap is dropped by the running subscription and after a restart. Recorded with the next event delivered and when the
subscription ends, retried after a failed write. A give-up is a permanent gap of the whole aggregate type (every subscriber
skips it), so the caller must have waited at least `transientGapGiveUpThreshold()` for each gap passed: gaps CDC drops
because > 10,000 are waited for at once stay transient, and a restarted subscription waits for them again.
`PostgresqlEventStreamGapHandler` promotes those gaps immediately
at the give-up when the promotion strategy states a threshold (`thresholdBased(n)` / `permanentGapThreshold()`), else only those
the strategy deems ready; a custom handler may override the default. CDC records a gap a bus event opens without claiming any
transient gap was queried, so on the CDC path promotion happens only through queries on the polling/back-fill leg and the give-up. Mocking a store wrapped by
`CdcEventStore`? Stub the `pollEvents` overload taking a `SubscriberAcknowledgement`.

### Ordering Guarantees

| Order Type | Guarantee | Reason |
|------------|-----------|--------|
| `EventOrder` | **Strict per-aggregate** | Unique constraint + optimistic concurrency |
| `GlobalEventOrder` | **No strict guarantee** | Events across aggregates may arrive out-of-order |

**Per-aggregate ordering is always preserved** - gaps only affect `GlobalEventOrder` across different aggregates.

**A subscriber can receive an event below a `GlobalEventOrder` it already handled** - on polling when a gap fills (TX1
above), and under Hybrid CDC whenever a transaction that took a lower order commits after one with a higher order (the
CDC bus delivers in commit order). Each event is still delivered once. Under CDC the gap a bus event opens is recorded
with the subscriber's gap handler, so a restart before the late event arrives does not lose it, and it is waited for up
to the gap handler's promotion threshold (`thresholdBased(n)`; 120 s by default and when the handler or a lambda
promotion strategy states none - override `permanentGapThreshold()` on the strategy). **Never deduplicate in a handler by "highest `GlobalEventOrder` seen"**:
it drops exactly these events. Deduplicate by event id, or rely on `EventOrder` per aggregate.

### Configuration

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;

// Enable - PostgresqlEventStore.withGapHandling(unitOfWorkFactory, persistenceStrategy) is the shorthand
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     ...
                                     .setEventStreamGapHandlerFactory(es -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                     .build();

// Disable - NoEventStreamGapHandler is the builder's default, so just leave the factory unset
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     ...
                                     .build();

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
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     ...
                                     .setEventStoreEventBus(eventBus)
                                     .build();

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
A custom `TenantSerializer` must round-trip: equal tenants serialize to equal strings, since tenant filtering compares the serialized form.

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
| `StatisticsCollectingEventStoreSubscriptionObserver` | Decorator - records per-subscription statistics for the admin API, then forwards to a delegate |

**Failure and error-policy callbacks**:

| Callback | Called when |
|----------|-------------|
| `handleEventFailed(PersistedEvent, PersistedEventHandler, Throwable, EventStoreSubscription)` | Async `PersistedEventHandler` failed and the `SubscriptionErrorPolicy` gave up (event about to be skipped, or stopped at) |
| `handleEventBatchFailed(List<PersistedEvent>, BatchedPersistedEventHandler, Throwable, EventStoreSubscription)` | Same for a `BatchedPersistedEventHandler` - batch failures arrive **here, not** in `handleEventFailed`. Default no-op |
| `handleEventFailed(PersistedEvent, TransactionalPersistedEventHandler, Throwable, EventStoreSubscription)` | In-transaction handler failed (the exception then rolls back the caller) |
| `subscriptionStoppedByErrorPolicy(GlobalEventOrder stoppedAtGlobalEventOrder, Throwable cause, EventStoreSubscription)` | A `stop()` policy halted an async subscription. Once per stop, after the failure callback, with `isStoppedByErrorPolicy()` already `true`; for a batch, `stoppedAtGlobalEventOrder` is the batch's first event. Default no-op. Unlike the failure callbacks it fires for stops only, never for skipped events. To alert, use the `essentials.eventstore.subscription.stopped` gauge (below) |

None of them fires for an event whose retries were abandoned because the subscription was being stopped - it is handled again on restart.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.*;

// No observability: NoOpEventStoreSubscriptionObserver is the builder's default
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()...build();

// With Micrometer
var observer = new MeasurementEventStoreSubscriptionObserver(
    MeasurementTaker.builder()
                    .setLoggingRecorder(MeasurementEventStoreSubscriptionObserver.class,
                                        LogThresholds.defaultThresholds())   // Log slow operations
                    .setMeterRegistry(meterRegistry)
                    .build(),
    null,            // Optional module tag
    meterRegistry);  // Failure counters - null (or the 2-arg constructor) records none
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     ...
                                     .setEventStoreSubscriptionObserver(observer)
                                     .build();
```

The third argument enables the failure counters. They count whether or not the `MeasurementTaker` records timings;
the Spring Boot starter always passes its `MeterRegistry`.

| Counter | Counts | Tags |
|---------|--------|------|
| `essentials.eventstore.subscription.handle_event_failed` | Async events given up on - skipped or stopped at, see [Direct async subscribers skip a failing event by default](#direct-async-subscribers-skip-a-failing-event-by-default). A failed batch counts each event | `subscriber_id`, `aggregate_type`, `event_handler`, `event_type`, optional `Module` |
| `essentials.eventstore.subscription.handle_event_transactional_failed` | In-transaction handler failures | same |
| `essentials.eventstore.subscription.stopped_by_error_policy` | Async subscriptions halted by a `stop()` policy, one per stop - a history, **not** the alerting signal | `subscriber_id`, `aggregate_type`, optional `Module` |

**Alert on the gauge, not the counter.** A counter records that a stop happened, not that a subscription is stopped
now: `increase(...[window]) > 0` resolves while the projection is still halted, raw `> 0` keeps firing after a
restart, `resetFrom` or fenced-lock hand-over started it again. `SubscriptionStoppedMicrometerMonitor` publishes the
level-triggered gauge `essentials.eventstore.subscription.stopped` - `1` while `isStoppedByErrorPolicy()`, else `0`;
tags `subscriber_id`, `aggregate_type`, optional `Module`. It is an `EventStoreSubscriptionMonitor`, so it is
registered by `EventStoreSubscriptionMonitorManager` within one monitoring interval of the subscription becoming
active, and read live on every scrape (an unsubscribed subscription reads `0`). Per JVM: an exclusive subscription
reads `1` only where it holds the lock, so aggregate with `max`, not `sum`.

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.*;

var monitorManager = new EventStoreSubscriptionMonitorManager(
    true, Duration.ofMinutes(1), subscriptionManager,
    List.of(new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null)));  // null = no Module tag
monitorManager.start();
// alert: max by (subscriber_id, aggregate_type) (essentials_eventstore_subscription_stopped) == 1
```

The Spring Boot starter wires it whenever a `MeterRegistry` is present (not gated by `management.tracing.enabled`);
`essentials.eventstore.subscription-monitor.enabled=false` turns it off together with every other monitor.

The SPI has a single slot, so collecting statistics **composes** with the metrics observer rather than replacing it:

```java
var statisticsRegistry = new SubscriptionStatisticsRegistry();      // read by EventStoreApi
var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                     ...
                                     .setEventStoreSubscriptionObserver(new StatisticsCollectingEventStoreSubscriptionObserver(observer, statisticsRegistry))
                                     .build();
```

The Spring Boot starter wires exactly that by default (`essentials.eventstore.subscription-manager.statistics.enabled=true`, `...max-tracked-subscriptions=1000`). Defining your own `EventStoreSubscriptionObserver` bean replaces both - wrap it the same way to keep the statistics.

**Statistics scope**: counters live in the JVM that runs the subscription. `EventStoreApi.findAllSubscriptions` reports database-backed resume points and therefore every instance's subscriptions; `findSubscriptionStatistics` only answers for subscriptions running in the instance queried. An exclusive subscription handles events only where it holds its fenced lock, so zero throughput on the other instances is normal. Polling counters are not zero under CDC: a subscription polls when it is established while CDC is not yet active (common at start-up) and whenever it falls back to polling, and stops once it switches to the CDC bus; the CDC catch-up (backfill) before that switch is not counted as polling. `resetFrom(...)` does not clear the counters - it is reported as a reset instead.

**Gap statistics** (`SubscriptionStatistics.gaps()`, admin API `gaps`): `newTransientGaps`, `resolvedTransientGaps`, `promotedToPermanentGaps`, plus when the last new and last promoted gap happened. Counted on every path that reconciles gaps, CDC backfill included, from the rows the reconciliation actually changed, and only after its unit of work commits. Transient gaps appearing and resolving is normal under concurrent writers; a rising `promotedToPermanentGaps` means global orders this subscriber stopped waiting for. `polling.gapReconciliations` is not a gap count - it is one per poll that ran reconciliation, gap or no gap. A custom `SubscriptionGapHandler` reports its outcome by overriding `reconcileGapsAndReport`; the default reports nothing.

See [README EventStoreSubscriptionObserver](../components/postgresql-event-store/README.md#eventstoresubscriptionobserver) for metrics and custom implementations.

### Schema ownership and notify triggers

Every schema-owning class - `SeparateTablePerAggregateTypePersistenceStrategy`, `PostgresqlDurableSubscriptionRepository`,
`PostgresqlEventStreamGapHandler`, `CdcInboxRepository` - creates its tables itself by default, and takes a
`SchemaOwnership` (builder setter or constructor overload) to leave them to a schema harness instead
([LLM-foundation.md](./LLM-foundation.md#database-schema-harness)). The persistence strategy is a `DynamicSchemaContributor`: event-stream tables registered after the
harness ran go through its applier, so in `validate` mode registering an `AggregateType` whose table is not in the
ledger throws `SchemaValidationException`.

NOTIFY-driven polling wake-up: use `strategy.enableNotifyTriggers(tableName -> listener.listenToNotificationsFor(tableName, EventStreamTableChangeNotification.class))`.
The `pg_notify` trigger is then part of each table's schema. `enableNotifyTriggerInstallation(NotifyTriggerInstaller)`
is deprecated - it runs the trigger DDL itself, where `validate`/`emit` cannot see it - and the two exclude each other.

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

// An EventStorePollingOptimizer whose currentDelayMs() is 0 after an empty poll still waits the polling interval, unless it
// overrides mayRepollImmediatelyAfterAnEmptyPoll() to return true (only NotifyAwareEventStorePollingOptimizer does).
// A polling worker whose thread is interrupted without a cancel ends the flux with an InterruptedException (+ WARN):
// never interrupt the polling thread from a handler

// No backpressure
Flux<PersistedEvent> unbounded = eventStore.unboundedPollForEvents(...);

// Synchronous
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    orders, LongRange.from(1, 1000));
```

**Transactions.** Each poll runs in a `UnitOfWork` of its own, which it commits (or rolls back on error) before the
events reach your subscriber — your handling of an event is not part of the poll's transaction. `pollEvents` always
polls on a dedicated thread. The first poll of `unboundedPollForEvents` runs on the thread that subscribes; if that
thread is already inside a `UnitOfWork`, the poll joins it and leaves ending it to you (on a polling error it only marks
it rollback-only). Up to and including 0.50.0 both methods could leave a poll's transaction open when the subscription
was disposed right after an idle poll — the connection stayed `idle in transaction` and held a lock that blocks
`DROP`/`TRUNCATE`/`ALTER TABLE` on the event table — and the unbounded variant committed a joined `UnitOfWork`. Fixed in
0.50.1.

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
- Declare a blocking-I/O handler `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` on an `EventProcessor`, make it idempotent, and time the call out well inside `messageHandlingTimeout`

### ❌ Don't

- Skip concurrency control: `appendToStream(orders, id, event)` (lower performance, no optimistic concurrency)
- Use mutable events with setters
- Use transient subscriptions for critical processing
- Trust `GlobalEventOrder` for strict ordering - only `EventOrder` guaranteed per-aggregate
- Forget to sanitize table/column names from external input
- Use `EventProcessor` for projections - use `InTransactionEventProcessor` or `ViewEventProcessor`
- Process events outside `UnitOfWork` when using in-transaction subscriptions
- Perform blocking I/O in a default (`REQUIRED`) handler - it holds a pooled connection in `idle in transaction` for the whole call; use `UnitOfWorkMode.NONE` and wrap the tail
- Use a non-exclusive `subscribeToAggregateEventsAsynchronously(...)` for a consumer that must run once per cluster - it runs on every instance that subscribes, so each instance handles the events and nothing orders the handling across instances; use `exclusivelySubscribeToAggregateEventsAsynchronously(...)`, which holds a `FencedLock` so only one subscriber per `SubscriberId` is active at a time

### Common Mistakes

| Mistake | Problem | Solution |
|---------|---------|----------|
| `appendToStream(orders, id, event)` | No concurrency control | Use `appendToStream(orders, id, EventOrder.of(n), event)` |
| Using `eventStore.pollEvents()` | Transient, loses events on restart | Use `subscriptionManager.subscribeToAggregateEventsAsynchronously()` |
| Using `EventProcessor` for projections | Eventual consistency | Use `InTransactionEventProcessor` (strong) or `ViewEventProcessor` (low-latency) |

### Direct async subscribers skip a failing event by default

A `PersistedEventHandler` or `BatchedPersistedEventHandler` subscribed with `subscribeToAggregateEventsAsynchronously`,
`exclusivelySubscribeToAggregateEventsAsynchronously` or `batchSubscribeToAggregateEventsAsynchronously` runs in its own
`UnitOfWork`. I/O errors are retried forever; **any other exception skips the event**: one ERROR line ("Skipping …
event because of error"), the resume point moves past it, and it is never redelivered — not after a restart either.
A projection silently misses the event.

The manager's `SubscriptionErrorPolicy` decides this (`dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription`):

| Policy | On a non-I/O handler exception |
|---|---|
| `SubscriptionErrorPolicy.skip()` (default) | Log at ERROR, advance past the event, continue |
| `SubscriptionErrorPolicy.retryThenSkip(n[, initialBackoff, maxBackoff])` | Call the handler again up to `n` times (new `UnitOfWork` each, exponential backoff, later events wait), then skip as above |
| `SubscriptionErrorPolicy.stop()` | Log at ERROR and stop at the event without advancing the resume point; the subscription resumes *at* it when started again (restart, fenced-lock hand-over, `resetFrom`). A permanent failure stops it again - the subscription stalls rather than loses the event |

```java
EventStoreSubscriptionManager.builder()
    // ...
    .setSubscriptionErrorPolicy(SubscriptionErrorPolicy.retryThenSkip(5))
    .build();
```

Spring Boot: `essentials.eventstore.subscription-manager.error-policy.mode=skip|retry-n-then-skip|stop` (default `skip`),
plus `.max-retries` (default 3), `.initial-backoff` (100ms), `.max-backoff` (1s). Built directly, `PersistedEventSubscriberBuilder`
and `BatchedPersistedEventSubscriberBuilder` take the same `setSubscriptionErrorPolicy(...)`. The policy is per manager, applies
to a batch as a whole, and does not touch in-transaction subscriptions (the exception rolls back the caller) or Inbox-forwarding
subscriptions (the Inbox's `RedeliveryPolicy` applies). Alert on the Micrometer counter
`essentials.eventstore.subscription.handle_event_failed` (tags `subscriber_id`, `aggregate_type`, `event_handler`,
`event_type`), which counts every event that exhausted the policy. For durable per-event retry with dead-lettering,
forward to an `Inbox` (`EventProcessor`) instead.

**A handler can take a failed event over instead of the policy giving up.** `PersistedEventHandler#handOffFailedEvent(event, failure)`
(default `false`) is called on the delivery thread once the policy has used up its retries, in place of skipping or stopping, after
the event's `UnitOfWork` was rolled back - so take the event over in a `UnitOfWork` of your own (e.g. queue it). Return `true` and the
subscription carries on as if the event had been handled: no skip, no stop, no `handleEventFailed` callback, the resume point moves past
it. Return `false`, or throw, and the policy gives up as usual. Single-event async subscriptions only (not batched, not in-transaction).
`ViewEventProcessor` uses it to queue failures it could not queue in the subscription's `UnitOfWork` - see [ViewEventProcessor](#vieweventprocessor).

**Retries block only their own subscription.** They are synchronous on the subscription's delivery thread (that is what keeps
events in order), and every async subscription has its own: polling `Publish-<subscriber>-<aggregateType>`, CDC
`Cdc-<subscriber>-<aggregateType>` (handed over from the shared `cdc-dispatcher` thread, which a handler never holds), batched
`BatchedEventSubscriber-<subscriber>-<aggregateType>-Handler`. Under CDC a subscription never back-pressures the CDC bus:
its hand-over buffers one polling page (`eventStorePollingBatchSize`), and a subscription further behind than that leaves
the bus on its own, catches up from the database and rejoins the bus - nothing lost or delivered twice. Each such overflow
logs a WARN and counts `essentials.cdc.eventstore.live_source.overflow.count` (not a CDC fallback), and rejoining logs
`Caught up after falling behind the CDC bus` at INFO; the other CDC subscriptions of its `AggregateType` and the dispatcher
are not held up, under either `essentials.eventstore.cdc.event-bus.overflow-policy`. Size `eventStorePollingBatchSize` to
absorb an ordinary burst; a larger one costs the subscription a catch-up. Every move onto the bus (warm-up, recovery from a
replication outage, after an overflow) catches up the same way first, so it is gap-free.

**Stopping during retries does not skip.** A stop while a retry is under way (shutdown, fenced-lock hand-over, `resetFrom`,
unsubscribe) abandons the retries: no failure callback, no ERROR, the resume point stays at the event, and the restarted
subscription handles it again. Stopping also interrupts a handler in progress - a single event on the polling path, a whole
batch for a batched subscription - and that event/batch is likewise handled again. Handlers must tolerate the repeat.
Only a real stop counts: under CDC a subscription switches between polling and the CDC bus (at boot, when replication drops or
recovers), which interrupts its delivery thread too, but a retry backoff it interrupts is waited out and the retries continue.

**Detect a `stop()` explicitly** - a stopped subscription looks like a healthy one with no new events:
`EventStoreSubscription#isStoppedByErrorPolicy()` (true until the subscription is started again), gauge
`essentials.eventstore.subscription.stopped` (`1` while stopped - **alert on this**), observer callback
`subscriptionStoppedByErrorPolicy(...)`, counter `essentials.eventstore.subscription.stopped_by_error_policy` (one per stop -
records that a stop happened, so not an alert on its own), and admin API `ApiSubscription.stoppedByErrorPolicy`. **`isActive()` stays `true` after a stop, on purpose**: it means "running here" ("holds
the fenced lock" for exclusive subscriptions), the lock is kept so the event doesn't flap to a node that fails the same way,
and the manager's periodic checkpoint only saves active subscriptions - which is what persists the held resume point if the
process later dies without a graceful stop. Never use `isActive()` to detect a stop.

### Flush-published events cannot be recalled

With `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream` configured (Spring Boot:
`essentials.eventstore.auto-flush-and-publish-after-append-to-stream=true`), every `appendToStream` publishes the new events on
the local `EventStoreEventBus` at `CommitStage.Flush`, **before** the transaction commits. Rolling the transaction back undoes
the rows and the SQL of in-transaction subscribers, but not the publication: an async `EventBus` subscriber, or a sync
subscriber's non-transactional side effect (in-memory state, a remote call), has already seen events that never commit. When
the work that appended them is retried - a `SubscriptionErrorPolicy` retry, a durable-queue redelivery, your own retry - the
events are appended and published at `Flush` again. Subscribe to `Flush` only for work that rolls back with the transaction,
or make the subscriber tolerate uncommitted and repeated events.

### Handlers without their annotation

A handler method that lacks its `@MessageHandler` / `@EventHandler` annotation is never called. Whether anything
notices depends on the dispatcher:

| Dispatcher | Unmatched message/event |
|---|---|
| `EventProcessor`, `InTransactionEventProcessor`, `ViewEventProcessor` | Ignored — they call `allowUnmatchedMessages()` |
| Aggregates (`AggregateRoot`, `FlexAggregate`, `AggregateState`), `AnnotationBasedInMemoryProjector` | Ignored — an aggregate need not handle every event |
| `PatternMatchingMessageHandler`, `PatternMatchingQueuedMessageHandler`, `PatternMatchingPersistedEventHandler` | `IllegalArgumentException`, unless `allowUnmatchedMessages()` / `allowUnmatchedEvents()` was called |

In the first two rows a forgotten annotation drops the event with no error: a projection simply never updates, an
aggregate's state never changes. In the last row, on a durable queue, the `IllegalArgumentException` is a permanent
error and dead-letters the message on its first delivery (see
[LLM-foundation.md § The built-in permanent-error list](LLM-foundation.md#the-built-in-permanent-error-list)).
Annotate every handler explicitly, and assert in a test that each event type the processor or aggregate is meant to
handle actually changes something.

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
| [spring-boot-starter-postgresql-event-store](./LLM-spring-boot-starter-modules.md#event-store-starter) | Spring Boot auto-configuration |
| [foundation](./LLM-foundation.md) | `UnitOfWork`, `FencedLock`, `DurableQueues`, `Inbox` |
| [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) | Distributed locking |
| [postgresql-queue](./LLM-postgresql-queue.md) | Durable queues |
| [foundation-types](./LLM-foundation-types.md) | `AggregateType`, `EventId`, `EventOrder`, etc. |
