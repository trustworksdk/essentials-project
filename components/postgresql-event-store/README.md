# Essentials Components - PostgreSQL Event Store

> **NOTE:** **The library is WORK-IN-PROGRESS**

Full-featured Event Store for PostgreSQL with durable subscriptions, gap handling, reactive streaming, and high-level event processing.

**LLM Context:** [LLM-postgresql-event-store.md](../../LLM/LLM-postgresql-event-store.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
  - [Event Streams and Aggregate Types](#event-streams-and-aggregate-types)
  - [Typed Events vs Named Events](#typed-events-vs-named-events)
- [Setup](#setup)
- [Multitenancy](#multitenancy)
- [Event Operations](#event-operations)
  - [EventStoreInterceptor](#eventstoreinterceptor)
- [Subscriptions](#subscriptions)
- [EventProcessor Framework](#eventprocessor-framework)
- [In-Memory Projections](#in-memory-projections)
- [Gap Handling](#gap-handling)
- [Event Polling](#event-polling)
- [EventStoreSubscriptionObserver](#eventstoresubscriptionobserver)
- [Advanced Configuration](#advanced-configuration)
- [Related Modules](#related-modules)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required `provided` dependencies** (you must add these to your project):

```xml
<!-- PostgreSQL & JDBI -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${postgresql.version}</version>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
    <version>${jdbi.version}</version>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-postgres</artifactId>
    <version>${jdbi.version}</version>
</dependency>

<!-- Jackson -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>

<!-- Reactive (for subscriptions) -->
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
    <version>${reactor.version}</version>
</dependency>

<!-- Logging -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
</dependency>
```

**Optional `provided` dependencies:**

```xml
<!-- Required only for Kotlin support -->
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-reflect</artifactId>
    <version>${kotlin.version}</version>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-stdlib-jdk8</artifactId>
    <version>${kotlin.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

Configuration parameters are used directly in SQL statements via string concatenation.

> ⚠️ **WARNING:** It is your responsibility to sanitize configuration values to prevent SQL injection.  
> ⚠️ Please see the **Security** notices in the [Security](../README.md#security) section, [foundation-types](../foundation-types/README.md#security) and [components README.md](../README.md#security) for more details.

### Parameters Requiring Sanitization

| Parameter | Description                                                                                                            |
|-----------|------------------------------------------------------------------------------------------------------------------------|
| `AggregateType` | Converted to table name in `SeparateTablePerAggregateTypePersistenceStrategy` and is used in SQL construction using String concatenation |
| `eventStreamTableName` | Defines PostgreSQL table for event storage and is used in SQL construction using String concatenation                  |
| `eventStreamTableColumnNames` | All column names used in SQL construction using String concatenation                                                   |
| `durableSubscriptionsTableName` | Table name for `PostgresqlDurableSubscriptionRepository` used in SQL construction using String concatenation                                                              |

### Mitigations

- Components call `PostgresqlUtil#checkIsValidTableOrColumnName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

### Developer Responsibility
- Derive values only from controlled, trusted sources
- Never use external/untrusted input for table/column/index names, `AggregateType` names
- Validate all configuration values during application startup

---

## Quick Start

Once configured (see [Setup](#setup)), basic EventStore usage:

```java
var orders = AggregateType.of("Orders");

// Append events within a UnitOfWork
eventStore.unitOfWorkFactory().usingUnitOfWork(() -> {
    var orderId = OrderId.random();

    // Append first event (no previous events)
    eventStore.appendToStream(orders, orderId,
        new OrderCreated(orderId, CustomerId.random()));

    // Append more events
    eventStore.appendToStream(orders, orderId,
        new ProductAdded(orderId, ProductId.random(), 2),
        new OrderAccepted(orderId));
});

// Fetch events
var stream = eventStore.unitOfWorkFactory().withUnitOfWork(() ->
    eventStore.fetchStream(orders, orderId)
);

stream.ifPresent(s -> {
    s.eventList().forEach(event ->
        System.out.println(event.event().deserialize()));
});
```

---

## Core Concepts

### Event Streams and Aggregate Types

Events are organized into separate EventStreams by `AggregateType`. Each aggregate type has its own PostgreSQL table.

```
EventStore
├── Orders (AggregateType → "orders_events" table)
│   ├── a1b2c3d4-e5f6-7890-abcd-ef1234567890 (AggregateEventStream for "Order" aggregate with id "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
│   │   ├── OrderAdded (EventOrder=0, GlobalEventOrder=1)
│   │   ├── ProductAdded (EventOrder=1, GlobalEventOrder=3)
│   │   └── OrderAccepted (EventOrder=2, GlobalEventOrder=5)
│   └── f47ac10b-58cc-4372-a567-0e02b2c3d479 (AggregateEventStream for "Order" aggregate with id "f47ac10b-58cc-4372-a567-0e02b2c3d479")
│       ├── OrderAdded (EventOrder=0, GlobalEventOrder=2)
│       └── ProductAdded (EventOrder=1, GlobalEventOrder=4)
├── Customers (AggregateType → "customers_events" table)
│   └── ...
```

### EventStore vs ConfigurableEventStore

The library provides two related interfaces for working with the Event Store:

| Interface | Purpose | Key Capabilities |
|-----------|---------|------------------|
| **`EventStore`** | Main event store operations | Event persistence (`appendToStream`, `fetchStream`, `loadEvent`), event polling, subscriptions, in-memory projections |
| **`ConfigurableEventStore<CONFIG>`** | Extends `EventStore` with configuration | Register `AggregateEventStreamConfiguration`s, add/remove `InMemoryProjector`s, add/remove `EventStoreInterceptor`s |

**When to use which:**

- **`EventStore`** - Use as the type for injected dependencies in application code that performs event operations
- **`ConfigurableEventStore<CONFIG>`** - Use during setup/configuration phase to register aggregate types, projectors, and interceptors

The `PostgresqlEventStore` class implements both interfaces, allowing it to be configured during initialization and then used for event operations.

```java
// Configuration phase - use ConfigurableEventStore
ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore =
    new PostgresqlEventStore<>(...);

// Register aggregate types
eventStore.addAggregateEventStreamConfiguration(AggregateType.of("Orders"), OrderId.class);
eventStore.addEventStoreInterceptor(new MyInterceptor());
eventStore.addGenericInMemoryProjector(new AnnotationBasedInMemoryProjector());

// Application usage - inject as EventStore
@Service
public class OrderService {
    private final EventStore eventStore; // Use EventStore interface

    public void processOrder(OrderId orderId) {
        var stream = eventStore.fetchStream(AggregateType.of("Orders"), orderId);
        // ...
    }
}
```

### Event Ordering

| Order Type | Scope | Starting Value | Purpose |
|------------|-------|----------------|---------|
| `EventOrder` | Per aggregate instance | 0 | Sequence within a specific aggregate |
| `GlobalEventOrder` | Per aggregate type | 1 | Sequence across all aggregates of same type |

### AggregateType Naming

**Important:** `AggregateType` is a logical name, **not** a Java class name. Use **plural names** to distinguish from implementation classes:

| AggregateType | Implementation Class | Purpose |
|---------------|---------------------|---------|
| `Orders` | `Order` | Order events stream |
| `Accounts` | `Account` | Account events stream |
| `Customers` | `Customer` | Customer events stream |

### Typed Events vs Named Events

The Event Store supports two approaches for identifying events: **Typed Events** (default) and **Named Events**.

| Approach | Identifier | Payload | Deserialization | Use Case |
|----------|------------|---------|-----------------|----------|
| **Typed Events** | `EventType` (Java FQCN) | Java object | Automatic via `deserialize()` | Standard domain events with Java classes |
| **Named Events** | `EventName` (logical string) | Raw JSON string | Manual via `getJson()` | External events, legacy systems, schema-less |

#### **Typed Events** (default): 
Use the Java class's Fully Qualified Class Name (FQCN) to identify the event type.  
The event is stored with a `FQCN:` prefix (e.g., `FQCN:com.example.events.OrderCreated`) and can be automatically deserialized back to the Java class.

#### **Named Events**:
Use a logical name string (e.g., `OrderCreated`) without requiring a corresponding Java class.  
The event payload is stored and retrieved as raw JSON, giving you full control over serialization and deserialization.

#### When to Use Each Approach

| Scenario                                       | Recommended Approach |
|------------------------------------------------|---------------------|
| Domain events in your application              | **Typed Events** |
| Events from external systems (Kafka, webhooks) | **Named Events** |
| Schema evolution where Java classes change     | **Named Events** |
| Schema free                                    | **Named Events** |
| Events that don't need deserialization         | **Named Events** |
| Full type safety and IDE support               | **Typed Events** |

#### Typed Events Example

```java
// Define your event class
public record OrderCreated(OrderId orderId, CustomerId customerId, Amount totalAmount) {}

// PersistableEventMapper using Typed Events (default)
public class MyPersistableEventMapper implements PersistableEventMapper {
    @Override
    public PersistableEvent map(Object aggregateId, AggregateTypeConfiguration config,
                                Object event, EventOrder eventOrder) {
        return PersistableEvent.from(
                EventId.random(),                           // Unique event identifier
                configuration.aggregateType,                 // Aggregate type (e.g., "Orders")
                aggregateId,                                 // Aggregate instance ID
                EventTypeOrName.with(event.getClass()),     // Event type (FQCN for typed events)
                event,                                       // Event payload (will be serialized to JSON)
                eventOrder,                                  // Event order within aggregate
                EventRevision.of(1),                        // Event schema version
                new EventMetaData(),                        // Additional metadata (empty or populated)
                OffsetDateTime.now(ZoneOffset.UTC),         // Event timestamp (UTC)
                null,                                        // causedByEventId - causal event reference (optional)
                CorrelationId.random(),                     // Correlation ID for tracking related events
                null                                         // tenant - multi-tenancy identifier (optional)
        );
    }
}

// Appending typed events
eventStore.unitOfWorkFactory().usingUnitOfWork(() -> {
    eventStore.appendToStream(orders, orderId,
        new OrderCreated(orderId, customerId, Amount.of("99.95")));
});

// Fetching and deserializing typed events
eventStore.fetchStream(orders, orderId).ifPresent(stream -> {
    stream.eventList().forEach(pe -> {
        Object event = pe.event().deserialize();  // Automatic deserialization
        if (event instanceof OrderCreated orderCreated) {
            System.out.println("Order created: " + orderCreated.orderId());
        }
    });
});
```

#### Named Events Example

Named Events are useful when you receive events from external systems, want to avoid tight coupling to Java classes, or need flexibility in schema evolution.

```java
// PersistableEventMapper using Named Events
public class NamedEventPersistableEventMapper implements PersistableEventMapper {
    private final ObjectMapper objectMapper;

    public NamedEventPersistableEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PersistableEvent map(Object aggregateId, AggregateTypeConfiguration config,
                                Object event, EventOrder eventOrder) {
        // Event can be a Map, JsonNode, or any object you want to serialize as JSON
        String eventName = resolveEventName(event);

        return PersistableEvent.from(
            EventId.random(),
            config.aggregateType,
            aggregateId,
            EventTypeOrName.with(EventName.of(eventName)),  // Uses EventName (logical name)
            event,                                           // Will be serialized to JSON
            eventOrder,
            EventRevision.of(1),
            new EventMetaData(),
            OffsetDateTime.now(ZoneOffset.UTC),
            null, CorrelationId.random(), null
        );
    }

    private String resolveEventName(Object event) {
        // Option 1: Use simple class name
        // return event.getClass().getSimpleName();

        // Option 2: Use a naming convention or annotation
        // return event.getClass().getAnnotation(EventNamed.class).value();

        // Option 3: For Map/JsonNode, extract from a "type" field
        if (event instanceof Map<?, ?> map) {
            return (String) map.get("type");
        }
        return event.getClass().getSimpleName();
    }
}

// Appending named events using raw JSON
eventStore.unitOfWorkFactory().usingUnitOfWork(() -> {
    // Using a Map as the event payload
    Map<String, Object> externalEvent = Map.of(
        "type", "ExternalOrderReceived",
        "externalOrderId", "EXT-12345",
        "source", "partner-system",
        "payload", Map.of(
            "items", List.of("SKU-001", "SKU-002"),
            "total", 149.99
        )
    );
    eventStore.appendToStream(orders, orderId, externalEvent);
});

// Fetching named events - work with raw JSON
eventStore.fetchStream(orders, orderId).ifPresent(stream -> {
    stream.eventList().forEach(pe -> {
        // Check if this is a named event
        if (pe.event().getEventName().isPresent()) {
            String eventName = pe.event().getEventName().get().toString();
            String rawJson = pe.event().getJson();

            System.out.println("Named event: " + eventName);
            System.out.println("JSON payload: " + rawJson);

            // Manual deserialization if needed
            // JsonNode node = objectMapper.readTree(rawJson);
        }
    });
});
```

#### Handling Named Events in Subscriptions

The `PatternMatchingPersistedEventHandler` (as well as `PatternMatchingTransactionalPersistedEventHandler`) supports handling Named Events via `String` parameter handlers:

```java
public class MixedEventHandler extends PatternMatchingPersistedEventHandler {
    private final ObjectMapper objectMapper;

    public MixedEventHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Handler for Typed Events - automatic deserialization
    @SubscriptionEventHandler
    public void handle(OrderCreated event, PersistedEvent metadata) {
        System.out.println("Typed event: " + event.orderId());
    }

    // Handler for Named Events - receives raw JSON
    @SubscriptionEventHandler
    public void handle(String json, PersistedEvent metadata) {
        // Called for events without a matching Java type handler
        String eventName = metadata.event().getEventName()
            .map(EventName::toString)
            .orElse("unknown");

        System.out.println("Named event '" + eventName + "': " + json);

        // Parse JSON manually if needed
        try {
            JsonNode node = objectMapper.readTree(json);
            // Process based on event name
            switch (eventName) {
                case "ExternalOrderReceived" -> processExternalOrder(node);
                case "LegacyOrderMigrated" -> processLegacyOrder(node);
                default -> log.warn("Unknown event type: {}", eventName);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse event JSON", e);
        }
    }
}
```

### PersistedEvent Structure

**Interface:** `PersistedEvent`
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream`

| Property | Type | Description |
|----------|------|-------------|
| `eventId()` | `EventId` | Unique event identifier |
| `eventOrder()` | `EventOrder` | Order within aggregate (0-based) |
| `globalEventOrder()` | `GlobalEventOrder` | Order within aggregate type (1-based) |
| `aggregateType()` | `AggregateType` | Aggregate type classification |
| `aggregateId()` | `Object` | Aggregate instance identifier |
| `event()` | `EventJSON` | Serialized event payload |
| `metaData()` | `EventMetaDataJSON` | Additional metadata |
| `timestamp()` | `OffsetDateTime` | Event timestamp (UTC) |
| `causedByEventId()` | `Optional<EventId>` | Causal event tracing |
| `correlationId()` | `Optional<CorrelationId>` | Correlation tracking |
| `tenant()` | `Optional<Tenant>` | Multi-tenancy support |
| `eventRevision()` | `EventRevision` | Event schema versioning |

---

## Setup

### 1. JDBI Configuration

```java
var jdbi = Jdbi.create(jdbcUrl, username, password);
jdbi.installPlugin(new PostgresPlugin());
jdbi.setSqlLogger(new SqlExecutionTimeLogger());
```

### 2. ObjectMapper Configuration

Events are serialized to JSON using the configured `JSONEventSerializer`.  
For use with `JacksonJSONEventSerializer` configure the Jackson `ObjectMapper` with Essential Types serialization support:

```java
ObjectMapper objectMapper = JsonMapper.builder()
    .disable(MapperFeature.AUTO_DETECT_GETTERS)
    .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
    .disable(MapperFeature.AUTO_DETECT_SETTERS)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(MapperFeature.AUTO_DETECT_CREATORS)
    .enable(MapperFeature.AUTO_DETECT_FIELDS)
    .addModule(new Jdk8Module())
    .addModule(new JavaTimeModule())
    .addModule(new EssentialTypesJacksonModule())
    .addModule(new EssentialsImmutableJacksonModule())
    .build();
```

### 3. PersistableEventMapper

Implement `PersistableEventMapper` to translate domain events, such as `OrderAccepted` object if you're using typed events (the default), to `PersistableEvent`:

**Interface:** `PersistableEventMapper`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence`

```java
public class MyPersistableEventMapper implements PersistableEventMapper {

    /**
     * Maps a domain event to a PersistableEvent ready for storage.
     *
     * @param aggregateId   The aggregate instance identifier (e.g., OrderId, CustomerId)
     * @param configuration The aggregate type configuration containing aggregateType and serialization settings
     * @param event         The domain event object to be persisted (e.g., OrderCreated, ProductAdded)
     * @param eventOrder    The event order for this event within the aggregate (0-based sequence)
     * @return PersistableEvent ready for persistence to the event store
     */
    @Override
    public PersistableEvent map(Object aggregateId,
                                AggregateTypeConfiguration configuration,
                                Object event,
                                EventOrder eventOrder) {
        return PersistableEvent.from(
            EventId.random(),                           // Unique event identifier
            configuration.aggregateType,                 // Aggregate type (e.g., "Orders")
            aggregateId,                                 // Aggregate instance ID
            EventTypeOrName.with(event.getClass()),     // Event type (FQCN for typed events)
            event,                                       // Event payload (will be serialized to JSON)
            eventOrder,                                  // Event order within aggregate
            EventRevision.of(1),                        // Event schema version
            new EventMetaData(),                        // Additional metadata (empty or populated)
            OffsetDateTime.now(ZoneOffset.UTC),         // Event timestamp (UTC)
            null,                                        // causedByEventId - causal event reference (optional)
            CorrelationId.random(),                     // Correlation ID for tracking related events
            null                                         // tenant - multi-tenancy identifier (optional)
        );
    }
}
```

### 4. EventStore Configuration

**Class:** `PostgresqlEventStore`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

```java
var unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
var jsonSerializer = new JacksonJSONEventSerializer(objectMapper);

// Persistence strategy: separate table per aggregate type (recommended)
var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi,
    unitOfWorkFactory,
    new MyPersistableEventMapper(),
    SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB
    )
);

// Create EventStore
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),  // Optional: EventBus for in-transaction event publishing
    eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
    new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver()
);

// Register aggregate types (this needs to happen before using the AggregateType for persistence)
eventStore.addAggregateEventStreamConfiguration(
    AggregateType.of("Orders"), OrderId.class);
eventStore.addAggregateEventStreamConfiguration(
    AggregateType.of("Customers"), CustomerId.class);
```

### `SeparateTablePerAggregateTypeEventStreamConfigurationFactory` Configuration Options

| Option | Description |
|--------|-------------|
| `IdentifierColumnType.UUID` | Use PostgreSQL UUID type for aggregate IDs |
| `IdentifierColumnType.TEXT` | Use TEXT type for string-based aggregate IDs |
| `JSONColumnType.JSONB` | Use JSONB for event JSON (recommended, better query performance) |
| `JSONColumnType.JSON` | Use JSON for event JSON |

---

## Multitenancy

The Event Store supports multitenancy, allowing events to be associated with a `Tenant` and enabling tenant-scoped queries.  
Each event can optionally carry a `Tenant` value, and the Event Store provides methods to filter events by tenant.

### Tenant Types

**Interface:** `Tenant` (marker interface)
**Package:** `dk.trustworks.essentials.components.foundation.types`

The `Tenant` interface is a marker interface that you implement with your tenant identifier type.  
The built-in `TenantId` class provides a simple implementation:

```java
// Built-in TenantId implementation
TenantId tenant = TenantId.of("acme-corp");

// Custom Tenant implementation (optional)
public class CompanyTenant extends CharSequenceType<CompanyTenant> implements Tenant {
    public CompanyTenant(CharSequence value) { super(value); }
    public CompanyTenant(String value) { super(value); }
    public static CompanyTenant of(CharSequence value) { return new CompanyTenant(value); }
}
```

### TenantSerializer Configuration

The `TenantSerializer` interface handles serialization/deserialization of tenant values to/from the database. Two implementations are provided:

| Serializer | Use Case |
|------------|----------|
| `TenantSerializer.TenantIdSerializer` | Multi-tenant applications using `TenantId` |
| `TenantSerializer.NoSupportForMultiTenancySerializer` | Single-tenant applications (default) |

#### Single-Tenant Configuration (Default)

For single-tenant applications, use `SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration`:

```java
var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi,
    unitOfWorkFactory,
    new MyPersistableEventMapper(),
    SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB
    )
);
```

#### Multi-Tenant Configuration

For multi-tenant applications, use `SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration` with a `TenantSerializer`:

```java
var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi,
    unitOfWorkFactory,
    new MyPersistableEventMapper(),
    SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
        aggregateType -> aggregateType + "_events",  // Table naming strategy (⚠️ see Security section)
        EventStreamTableColumnNames.defaultColumnNames(),
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB,
        new TenantSerializer.TenantIdSerializer()    // Enable multi-tenancy
    )
);
```

### Associating Events with Tenants

Associate events with a tenant via the `PersistableEventMapper`:

```java
public class MultiTenantPersistableEventMapper implements PersistableEventMapper {
    private final TenantResolver tenantResolver;  // Your tenant resolution strategy

    @Override
    public PersistableEvent map(Object aggregateId,
                                AggregateEventStreamConfiguration config,
                                Object event,
                                EventOrder eventOrder) {
        return PersistableEvent.from(
            EventId.random(),
            config.aggregateType,
            aggregateId,
            EventTypeOrName.with(event.getClass()),
            event,
            eventOrder,
            EventRevision.of(1),
            new EventMetaData(),
            OffsetDateTime.now(ZoneOffset.UTC),
            null,                           // causedByEventId
            CorrelationId.random(),
            tenantResolver.getCurrentTenant()  // Associate tenant with event
        );
    }
}
```

### Tenant-Scoped Queries

The Event Store provides overloaded methods for tenant-scoped operations:

#### Fetching Events by Tenant

```java
var orders = AggregateType.of("Orders");
var tenant = TenantId.of("acme-corp");

// Fetch stream for a specific tenant
Optional<AggregateEventStream<OrderId>> stream = eventStore.fetchStream(
    orders, orderId, tenant);

// Fetch with event order range
Optional<AggregateEventStream<OrderId>> stream = eventStore.fetchStream(
    orders, orderId, LongRange.from(0), tenant);

// Check if stream exists for tenant
boolean exists = eventStore.hasEventStream(orders, orderId, tenant);
```

#### Loading Events by Global Order (with Tenant Filter)

```java
// Load events for a specific tenant
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    orders,         // aggregate type
    LongRange.from(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue()),
    List.of(),     // a list of additional global orders 
    tenant         // tenant filter
);
```

#### Polling Events by Tenant

```java
// Subscribe to events for a specific tenant
Flux<PersistedEvent> eventFlux = eventStore.pollEvents(
    orders,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.of(10),                        // batch size
    Optional.of(Duration.ofMillis(100)),    // poll interval
    Optional.of(tenant),                    // tenant filter
    Optional.of(SubscriberId.of("my-sub")),
    Optional.empty()
);
```

### Reading Tenant from PersistedEvent

```java
eventStore.fetchStream(orders, orderId).ifPresent(stream -> {
    stream.eventList().forEach(event -> {
        Optional<? extends Tenant> tenant = event.tenant();
        tenant.ifPresent(t ->
            System.out.println("Event belongs to tenant: " + t));
    });
});
```

### Multitenancy Use Cases

| Scenario | Approach |
|----------|----------|
| **Shared database** | All tenants share tables; filter by `tenant` column |
| **Tenant isolation** | Events scoped to specific tenant for access control |
| **Cross-tenant analytics** | Fetch events without tenant filter for aggregation |
| **Tenant migration** | Re-associate events with different tenant |

See [`MultiTenantPostgresqlEventStoreIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/MultiTenantPostgresqlEventStoreIT.java) for complete integration test examples.

---

## Event Operations

### Appending Events

```java
var orders = AggregateType.of("Orders");

eventStore.unitOfWorkFactory().usingUnitOfWork(unitOfWork -> {
    var orderId = OrderId.random();

    // Append first event (no previous events)
    eventStore.appendToStream(orders, orderId,
        new OrderAdded(orderId, CustomerId.random(), 1234));

    // Append more events (without concurrency control)
    eventStore.appendToStream(orders, orderId,
        new ProductAddedToOrder(orderId, productId, 5),
        new OrderAccepted(orderId));
});
```

### Optimistic Concurrency

To enhance performance and enable optimistic concurrency control when appending events, provide the `appendEventsAfterEventOrder` argument to `appendToStream`.  
This specifies the event order after which new events should be appended:

- Events appended will receive `EventOrder` = `appendEventsAfterEventOrder + 1`
  - For the very first event, use `EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED`
  - For subsequent events, specify the event order of the last event already persisted (e.g. from the `AggregateEventStream` returned from `fetchStream`)

The `EventStore` will throw an `OptimisticAppendToStreamException` if another transaction has already appended events after the specified event order.

```java
// For the very first event (no events exist yet)
eventStore.appendToStream(orders, 
                          orderId,
                          EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED, // appendEventsAfterEventOrder
                          new OrderCreated(orderId, customerId));

// Append after event order 0 (i.e., first event already exists with order 0)
// New events will start at order 1
eventStore.appendToStream(orders, 
                          orderId,
                          EventOrder.of(0),  // appendEventsAfterEventOrder: Append AFTER event order 0
                          new ProductAddedToOrder(orderId, productId, 5));

```

If another transaction has already appended events, an `OptimisticAppendToStreamException` is thrown:

```java
try {
    eventStore.appendToStream(orders, 
                              orderId,
                              2L,  // Append after event order 2 (both Long and EventOrder are supported)
                              newEvent);
} catch (OptimisticAppendToStreamException e) {
    // Conflict: events exist beyond order 2
    // Reload aggregate and retry
}
```

### Fetching Events

```java
// Fetch complete aggregate event stream
var eventStream = eventStore.unitOfWorkFactory().withUnitOfWork(unitOfWork -> {
    return eventStore.fetchStream(orders, orderId);
});

// Returns Optional<AggregateEventStream<Object>>
eventStream.ifPresent(stream -> {
    stream.aggregateId();     // The aggregate ID
    stream.eventList();       // List<PersistedEvent>
    stream.eventOrderOfLastEvent();  // EventOrder of last event
});

// Fetch from specific event order (useful for partial rehydration)
var partialStream = eventStore.fetchStream(orders, orderId, EventOrder.of(5));

// Load events by global order range (useful for subscriptions)
var globalEvents = eventStore.loadEventsByGlobalOrder(orders,
    GlobalEventOrder.of(100), GlobalEventOrder.of(200));
```

### AggregateEventStream

**Interface:** `AggregateEventStream`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream`

The `fetchStream` method returns an `Optional<AggregateEventStream>` containing all persisted events for an aggregate.  
Events are stored as JSON and wrapped in `PersistedEvent` objects.

The `AggregateEventStream` provides the following information:

| Method | Return Type | Description |
|--------|-------------|-------------|
| `aggregateId()` | `Object` | The aggregate instance identifier |
| `aggregateType()` | `AggregateType` | The aggregate type |
| `eventList()` | `List<PersistedEvent>` | All events in order (as `PersistedEvent` wrappers) |
| `eventOrderOfLastEvent()` | `Optional<EventOrder>` | Event order of the last event (for optimistic concurrency) |
| `isPartialStream()` | `boolean` | Whether this is a partial stream (fetched from specific event order) |

```java
Optional<AggregateEventStream<Object>> result = eventStore.fetchStream(orders, orderId);

result.ifPresent(stream -> {
    var aggregateId = stream.aggregateId();
    var lastEventOrder = stream.eventOrderOfLastEvent();  // Use for optimistic concurrency

    for (PersistedEvent pe : stream.eventList()) {
        // PersistedEvent contains metadata + serialized event
        EventId eventId = pe.eventId();
        EventOrder order = pe.eventOrder();
        OffsetDateTime timestamp = pe.timestamp();

        // The actual event payload requires deserialization (see below)
        EventJSON eventJson = pe.event();
    }
});
```

### Event Deserialization

When working with `PersistedEvent`, the `event()` method returns an `EventJSON` object containing the serialized event.  
If you're using Typed Events (the default), call `deserialize()` to get the actual Java event object:

```java
PersistedEvent persistedEvent = ...;
EventJSON eventJson = persistedEvent.event();
Object actualEvent = eventJson.deserialize();

// Cast to your event type
if (actualEvent instanceof OrderCreated orderCreated) {
    // Handle OrderCreated
}
```

For stream-based deserialization, use `EventStreamEvolver` utilities from [eventsourced-aggregates](../eventsourced-aggregates/README.md):

```java
// Extract all events as a typed list
List<OrderEvent> events = EventStreamEvolver.extractEventsAsList(
    eventStream.eventList(),
    OrderEvent.class // Base EventType (interface or class) for all events in stream
);

// Or as a Stream for lazy processing
Stream<OrderEvent> eventStream = EventStreamEvolver.extractEvents(
    eventStream.eventList(),
    OrderEvent.class
);
```

### EventBus for In-Transaction Event Publishing

If you have a shared `EventStoreEventBus` then you can provide it to the `PostgresqlEventStore` constructor. If left out, a default instance is created.  
>Note: When sharing the `EventStoreEventBus` then `PersistedEvents` are published together with events from other components, so subscribers need to take this into account.

Subscribe to events **within** the appending transaction using `EventStoreEventBus`:

```java
// Configure EventStore with EventBus
var eventBus = new EventStoreEventBus(unitOfWorkFactory);
var eventStore = new PostgresqlEventStore<>(..., Optional.of(eventBus), ...);

// Synchronous: runs in SAME transaction (before commit)
eventBus.addSyncSubscriber(PersistedEvent persistedEvent -> {
    persistedEvents.forEach(event -> updateProjection(event));
});

// Asynchronous: runs AFTER commit
eventBus.addAsyncSubscriber(PersistedEvent persistedEvent -> {
    persistedEvents.forEach(event -> sendNotification(event));
});
```

**How it works:** `EventStoreEventBus` internally registers a `PersistedEventsCommitLifecycleCallback` with the `EventStoreUnitOfWorkFactory`. This callback publishes `PersistedEvents` to the event bus at each `CommitStage` (`BeforeCommit`, `AfterCommit`, `AfterRollback`).

### PersistedEventsCommitLifecycleCallback

**Interface:** `PersistedEventsCommitLifecycleCallback`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction`

A callback interface for hooking into the event persistence lifecycle.  
Register callbacks via `EventStoreUnitOfWorkFactory.registerPersistedEventsCommitLifeCycleCallback()`.

#### Callback Methods

| Method | When Called | Use Case |
|--------|-------------|----------|
| `beforeCommit(EventStoreUnitOfWork, List<PersistedEvent>)` | Before transaction commits | Validation, in-transaction projections, sync event bus publishing |
| `afterCommit(EventStoreUnitOfWork, List<PersistedEvent>)` | After transaction commits | Notifications, async processing, external system integration |
| `afterRollback(EventStoreUnitOfWork, List<PersistedEvent>)` | After transaction rollback | Cleanup, logging, compensating actions |

#### Framework Usage

`EventStoreEventBus` uses this callback to publish events at each commit stage:

```java
// From EventStoreEventBus.addUnitOfWorkLifeCycleCallback()
unitOfWorkFactory.registerPersistedEventsCommitLifeCycleCallback(new PersistedEventsCommitLifecycleCallback() {
    @Override
    public void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        eventBus.publish(new PersistedEvents(CommitStage.BeforeCommit, unitOfWork, persistedEvents));
    }

    @Override
    public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        eventBus.publish(new PersistedEvents(CommitStage.AfterCommit, unitOfWork, persistedEvents));
    }

    @Override
    public void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        eventBus.publish(new PersistedEvents(CommitStage.AfterRollback, unitOfWork, persistedEvents));
    }
});
```

#### Custom Callback Example

```java
unitOfWorkFactory.registerPersistedEventsCommitLifeCycleCallback(new PersistedEventsCommitLifecycleCallback() {
    @Override
    public void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called BEFORE commit - exception here causes rollback
        events.forEach(event -> log.debug("About to commit: {}", event.eventId()));
    }

    @Override
    public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called AFTER commit - exceptions are logged but don't affect commit
        events.forEach(event -> metricsService.recordEventPersisted(event));
    }

    @Override
    public void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> events) {
        // Called AFTER rollback
        log.warn("Rolled back {} events", events.size());
    }
});
```

> **Note:** Exceptions in `beforeCommit` trigger a rollback. Exceptions in `afterCommit` and `afterRollback` are logged but don't affect the transaction outcome.

For Spring transaction integration, see [spring-postgresql-event-store: Event Lifecycle Callbacks](../spring-postgresql-event-store/README.md#event-lifecycle-callbacks).

### EventStoreInterceptor

**Interface:** `EventStoreInterceptor`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor`

Interceptors allow you to add cross-cutting behavior (logging, metrics, tracing, event modification) to EventStore operations.  
Each interceptor can perform **before**, **after**, or **around** logic for any operation.

#### Interceptable Operations

| Method | Operation | Description |
|--------|-----------|-------------|
| `intercept(AppendToStream, chain)` | `appendToStream()`, `startStream()` | Intercept event persistence |
| `intercept(FetchStream, chain)` | `fetchStream()` | Intercept stream loading |
| `intercept(LoadEvent, chain)` | `loadEvent()` | Intercept single event loading |
| `intercept(LoadEvents, chain)` | `loadEvents()` | Intercept multiple events loading |
| `intercept(LoadLastPersistedEventRelatedTo, chain)` | `loadLastPersistedEventRelatedTo()` | Intercept last event loading |
| `intercept(LoadEventsByGlobalOrder, chain)` | `loadEventsByGlobalOrder()` | Intercept global order loading |

#### Adding Interceptors

```java
// Add single interceptor
eventStore.addEventStoreInterceptor(new MyInterceptor());

// Add multiple interceptors
eventStore.addEventStoreInterceptors(List.of(
    new LoggingInterceptor(),
    new MetricsInterceptor()
));
```

#### Custom Interceptor Example

```java
public class LoggingEventStoreInterceptor implements EventStoreInterceptor {

    @Override
    public <ID> AggregateEventStream<ID> intercept(
            AppendToStream<ID> operation,
            EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {

        // Before
        log.info("Appending {} events to {}/{}",
            operation.getEventsToAppend().size(),
            operation.aggregateType,
            operation.aggregateId);

        // Proceed with the operation
        var result = chain.proceed();

        // After
        log.info("Persisted events with global orders: {}",
            result.eventList().stream()
                .map(e -> e.globalEventOrder().longValue())
                .toList());

        return result;
    }
}
```

#### Built-in Interceptors

| Interceptor | Package | Description                                                                       |
|-------------|---------|-----------------------------------------------------------------------------------|
| `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream` | `interceptor` | Flushes and Publishes events to EventBus immediately after append (before commit) |
| `MicrometerTracingEventStoreInterceptor` | `interceptor.micrometer` | Adds distributed tracing via Micrometer Observation API                           |
| `RecordExecutionTimeEventStoreInterceptor` | `interceptor.micrometer` | Records operation execution time metrics                                          |

### Flush Publishing

**Class:** `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor`

This interceptor publishes `PersistedEvents` to the `EventBus` **immediately after** `appendToStream()` completes, using `CommitStage.Flush`.  
This enables in-transaction subscribers to react to events before the transaction commits.

#### Why Use This Interceptor

By default, `EventStoreEventBus` only publishes events at `CommitStage.BeforeCommit` and `CommitStage.AfterCommit`. This works for most use cases, but some scenarios require earlier notification:

| Scenario | Without Interceptor | With Interceptor                                                                        |
|----------|---------------------|-----------------------------------------------------------------------------------------|
| In-transaction projections | Events available at `BeforeCommit` | Events available immediately after `appendToStream()` - also known as `CommitStage.Flush` |
| Saga coordination | Must wait for all appends | Can react to each append                                                                |
| `subscribeToAggregateEventsInTransaction` | Receives events at `BeforeCommit` | Receives events at `Flush` (right after append)                                         |

#### Usage

```java
// Add the interceptor to the EventStore
eventStore.addEventStoreInterceptor(
    new FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream());

// Now subscribeToAggregateEventsInTransaction receives events immediately
subscriptionManager.subscribeToAggregateEventsInTransaction(
    SubscriberId.of("OrderProjection"),
    ORDERS,
    Optional.empty(),
    (event, unitOfWork) -> {
        // Called at CommitStage.Flush - right after appendToStream()
        updateProjection(event);
    }
);
```

#### How It Works

```java
// From FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream
public <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation,
        EventStoreInterceptorChain<...> chain) {
    var eventsPersisted = chain.proceed();  // Append completes
    if (eventsPersisted.isNotEmpty()) {
        // Publish immediately with CommitStage.Flush
        eventStore.localEventBus().publish(
            new PersistedEvents(CommitStage.Flush, unitOfWork, eventsPersisted.eventList()));
    }
    return eventsPersisted;
}
```

#### CommitStage Ordering

With this interceptor, events are published at these stages:

1. **`Flush`** - Immediately after `appendToStream()` (from this interceptor)
2. **`BeforeCommit`** - Before transaction commits (from `EventStoreEventBus`)
3. **`AfterCommit`** - After transaction commits (from `EventStoreEventBus`)

> **Note:** Events published at `Flush` are still within the transaction and will be rolled back if the transaction fails.

---

## Subscriptions

The `EventStoreSubscriptionManager` provides durable subscriptions with resume points.

**Interface:** `EventStoreSubscriptionManager`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription`

### Subscription Manager Setup

```java
var subscriptionManager = EventStoreSubscriptionManager.builder()
    .setEventStore(eventStore)
    .setEventStorePollingBatchSize(10)
    .setEventStorePollingInterval(Duration.ofMillis(100))
    .setFencedLockManager(fencedLockManager)  // Required for exclusive subscriptions
    .setSnapshotResumePointsEvery(Duration.ofSeconds(10))
    .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
    .build();

subscriptionManager.start();
```

> ⚠️ **Security:** The `PostgresqlDurableSubscriptionRepository` accepts a `durableSubscriptionsTableName` parameter used directly in SQL statements using String concatenation.  
> See [Security](#security) for sanitization requirements.

### Subscription Types

| Type | Transaction Context | Use Case |
|------|---------------------|----------|
| **Asynchronous** | Out-of-transaction (after commit) | External integrations, notifications, analytics |
| **Exclusive Asynchronous** | Out-of-transaction with FencedLock | Single processor per cluster (inventory, payments) |
| **In-Transaction** | Same transaction as event append | Transactionally consistent projections |
| **Exclusive In-Transaction** | Same transaction with FencedLock | Critical consistent projections |

### Subscription Resume Points

Asynchronous subscriptions track their progress using **resume points** - the `GlobalEventOrder` of the last successfully processed event (as of the snapshot).  
This enables:
- **Crash recovery** - After restart, processing continues from the last checkpoint
- **No duplicate processing** - Events before the resume point are skipped
- **Horizontal scaling** - Different instances can take over processing seamlessly

**How it works:**

1. The `DurableSubscriptionRepository` (e.g., `PostgresqlDurableSubscriptionRepository`) persists resume points to the database
2. The `EventStoreSubscriptionManager` periodically snapshots resume points (configured via `setSnapshotResumePointsEvery`)
3. On startup, the subscription queries its last persisted resume point and continues from there

**First subscription behavior:**

The `onFirstSubscriptionSubscribeFromAndIncluding` parameter only applies when a subscription is **first created** (no existing resume point):
- `GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER` - Start from the very first event (replay all history)
- `GlobalEventOrder.of(N)` - Start from a specific global event order

On subsequent restarts, this parameter is ignored - the subscription resumes from its persisted resume point.

> **In-transaction subscriptions don't use resume points** because they process events synchronously within the same database transaction that persists the events. There's no gap between event persistence and processing - if the transaction commits, the event was processed; if it rolls back, neither happened. This eliminates the need for separate progress tracking.

### Asynchronous Subscription

Events processed **after** commit (out-of-transaction).

```java
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    SubscriberId.of("OrderEmailNotifier"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,  // onFirstSubscriptionSubscribeFromAndIncluding: Start from beginning on first subscription
    Optional.empty(),  // No tenant filter
    new PatternMatchingPersistedEventHandler() {
        @SubscriptionEventHandler
        private void handle(OrderAdded event, PersistedEvent metadata) {
            sendWelcomeEmail(event.orderingCustomerId);
        }
    }
);
```

### Exclusive Asynchronous Subscription

Uses [FencedLockManager](../foundation/README.md#fencedlock-distributed-locking) to ensure only **one subscriber per cluster**:

```java
subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(
    SubscriberId.of("InventoryManager"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, // onFirstSubscriptionSubscribeFromAndIncluding: Start from beginning on first subscription
    Optional.empty(), // No tenant filter
    new FencedLockAwareSubscriber() {
        @Override
        public void onLockAcquired(FencedLock lock, SubscriptionResumePoint resumePoint) {
            log.info("Acquired exclusive lock, resuming from {}", resumePoint);
        }

        @Override
        public void onLockReleased(FencedLock lock) {
            log.info("Released exclusive lock");
        }
    },
    new InventoryEventHandler() // PersistedEventHandler
);
```

### Pattern Matching `PersistedEventHandler`

**Class:** `PatternMatchingPersistedEventHandler`
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription`

Automatic routing based on event type:

```java
public class OrderEventHandler extends PatternMatchingPersistedEventHandler {

    @Override
    public void onResetFrom(EventStoreSubscription subscription, GlobalEventOrder globalEventOrder) {
        // Called when subscription is reset - clear relevant projections
    }

    @SubscriptionEventHandler
    public void handle(OrderAdded event) {
        // Event-only handler
    }

    @SubscriptionEventHandler
    private void handle(ProductAddedToOrder event, PersistedEvent metadata) {
        // Event + metadata handler - access eventId, timestamp, etc.
    }

    @SubscriptionEventHandler
    private void handle(String json, PersistedEvent metadata) {
        // Raw JSON handler for named events without Java class
    }
}
```

**Method signature options:**
| Parameters | Description |
|------------|-------------|
| `(Event)` | Event object only |
| `(Event, PersistedEvent)` | Event + full metadata (eventId, timestamp, etc.) |
| `(String, PersistedEvent)` | Raw JSON for named events without Java class |

**Unmatched event handling:**
- By default, unmatched events throw `IllegalArgumentException`
- Call `allowUnmatchedEvents()` to silently ignore unmatched events
- Override `handleUnmatchedEvent(PersistedEvent)` for custom handling

### In-Transaction Subscriptions

In-transaction subscriptions process events **within the same database transaction** that appends the events. This provides strong consistency guarantees:

- **Atomic processing** - Event persistence and handler execution succeed or fail together
- **No eventual consistency** - Projections are immediately consistent with the event store
- **Exception rollback** - Any exception thrown from the handler triggers a full transaction rollback, preventing both the event from being persisted and any side effects from the handler

> **Note:** In-transaction subscriptions do **not** support replay since they process events synchronously as they're appended. Use asynchronous subscriptions if you need replay capability.

**Non-exclusive subscription** (all instances process events):

```java
subscriptionManager.subscribeToAggregateEventsInTransaction(
    SubscriberId.of("OrderProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),  // No tenant filter
    new TransactionalPersistedEventHandler() {
        @Override
        public void handle(PersistedEvent event, UnitOfWork unitOfWork) {
            // Runs in SAME transaction as event append
            // Exception here rolls back the entire transaction
            updateProjection(event, unitOfWork);
        }
    }
);
```

**Exclusive subscription** (only one instance per cluster processes events, using [FencedLockManager](../foundation/README.md#fencedlock-distributed-locking)):

```java
subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(
    SubscriberId.of("CriticalOrderProjection"),
    AggregateType.of("Orders"),
    Optional.empty(),  // No tenant filter
    new FencedLockAwareSubscriber() {
        @Override
        public void onLockAcquired(FencedLock lock, SubscriptionResumePoint resumePoint) {
            log.info("Acquired exclusive in-transaction lock");
        }

        @Override
        public void onLockReleased(FencedLock lock) {
            log.info("Released exclusive in-transaction lock");
        }
    },
    new TransactionalPersistedEventHandler() {
        @Override
        public void handle(PersistedEvent event, UnitOfWork unitOfWork) {
            updateCriticalProjection(event, unitOfWork);
        }
    }
);
```

### Pattern Matching `TransactionalPersistedEventHandler`

**Class:** `PatternMatchingTransactionalPersistedEventHandler`
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription`

Similar to `PatternMatchingPersistedEventHandler`, but for in-transaction subscriptions. Methods receive the `UnitOfWork` as the 2nd parameter:

```java
public class OrderProjectionHandler extends PatternMatchingTransactionalPersistedEventHandler {

    @SubscriptionEventHandler
    public void handle(OrderAdded event, UnitOfWork unitOfWork) {
        // Event + UnitOfWork - use unitOfWork for transactional operations
    }

    @SubscriptionEventHandler
    private void handle(ProductAddedToOrder event, UnitOfWork unitOfWork, PersistedEvent metadata) {
        // Event + UnitOfWork + metadata
    }

    @SubscriptionEventHandler
    private void handle(String json, UnitOfWork unitOfWork, PersistedEvent metadata) {
        // Raw JSON handler for named events
    }
}
```

**Method signature options:**

| Parameters | Description |
|------------|-------------|
| `(Event, UnitOfWork)` | Event object + transaction context |
| `(Event, UnitOfWork, PersistedEvent)` | Event + transaction + full metadata |
| `(String, UnitOfWork, PersistedEvent)` | Raw JSON for named events |

**Unmatched event handling:**
- By default, unmatched events throw `IllegalArgumentException`
- Call `allowUnmatchedEvents()` to silently ignore unmatched events
- Override `handleUnmatchedEvent(PersistedEvent, UnitOfWork)` for custom handling

### Batched Subscription

For high-throughput scenarios:

```java
subscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
    SubscriberId.of("OrderAnalytics"),
    AggregateType.of("Orders"),
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, // onFirstSubscriptionSubscribeFromAndIncluding: Start from beginning on first subscription
    Optional.empty(), // No tenant filter
    100,                          // Max batch size
    Duration.ofSeconds(5),        // Max latency before processing partial batch
    new BatchedPersistedEventHandler() {
        @Override
        public void handle(List<PersistedEvent> events) {
            // Process batch of events
            analyticsService.processBatch(events);
        }
    }
);
```

---

## EventProcessor Framework

The `EventProcessor` framework provides high-level event and command handling with automatic subscription management, error handling, and redelivery.

### `@MessageHandler` Methods

All processor types use `@MessageHandler` annotated methods to handle events.  
The framework automatically routes deserialized events to matching methods based on the event type:

```java
@MessageHandler
void handle(OrderAdded event) {
    // Called when OrderAdded event is received
}

@MessageHandler
void handle(ProductAddedToOrder event, OrderedMessage message) {
    // Event + OrderedMessage metadata (aggregateId, order, etc.)
}
```

**Method signature options:**

| Parameters | Description |
|------------|-------------|
| `(Event)` | Event object only |
| `(Event, OrderedMessage)` | Event + message metadata (aggregateId, messageOrder, etc.) |

The `OrderedMessage` provides access to:
- `getAggregateId()` - The aggregate instance identifier
- `getOrder()` - Message order for this aggregate (for ordered processing)
- `getMetaData()` - Additional metadata from the event

### Choosing a Processor

| Characteristic | `EventProcessor`                                               | `InTransactionEventProcessor` | `ViewEventProcessor`                     |
|----------------|----------------------------------------------------------------|-------------------------------|------------------------------------------|
| **Best for** | External integrations, async workflows                         | Transactionally consistent projections | Low-latency view updates                 |
| **Processing** | Async Via [Inbox](../foundation/README.md#inbox-pattern) queue | Synchronous in-transaction | Async with Direct call, queue on failure |
| **Exclusive** | Yes (via FencedLock)                                           | Configurable (default: no) | Yes (via FencedLock)                     |
| **Latency** | Higher (queued)                                                | Lowest (same transaction) | Low (direct when healthy)                |
| **Consistency** | Eventual                                                       | Strong (atomic with event) | Eventual                                 |
| **Failure handling** | Automatic redelivery                                           | Transaction rollback | Queue for retry                          |
| **Replay support** | Yes                                                            | No | Yes                                      |
| **Throughput** | High (parallel workers)                                        | Limited by transaction | High (direct path)                       |

**When to use each:**

- **`EventProcessor`**: External system integrations (Kafka, email, webhooks), long-running operations, operations that may fail and need retry. Events are queued to an Inbox and processed by background workers with configurable parallelism and redelivery policies. Higher latency but maximum reliability.

- **`InTransactionEventProcessor`**: View projections that must be atomically consistent with events. Processing happens synchronously within the same database transaction that appends the event. If processing fails, the entire transaction (including the event) rolls back. Lowest latency but no replay capability.

- **`ViewEventProcessor`**: View projections where low latency is critical but occasional failures are acceptable. Events are handled directly (no queue) for minimal latency. On failure, events are queued to a [DurableQueue](../foundation/README.md#durablequeues-messaging) for retry. If the queue has pending messages for an aggregate, new events are also queued to maintain ordering. Best balance of latency and reliability for read models.

### EventProcessor

**Class:** `EventProcessor`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor`

Uses an [Inbox](../foundation/README.md#inbox-pattern) for reliable event processing with redelivery:

```java
@Service
public class ShippingEventKafkaPublisher extends EventProcessor {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ShippingEventKafkaPublisher(EventProcessorDependencies dependencies,
                                       KafkaTemplate<String, Object> kafkaTemplate) {
        super(dependencies);
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public String getProcessorName() {
        return "ShippingEventsKafkaPublisher";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("ShippingOrders"));
    }

    @MessageHandler
    void handle(OrderShipped event) {
        kafkaTemplate.send("shipping-events", event.orderId().toString(),
            new ExternalOrderShipped(event.orderId()));
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

#### Key Features

- **Exclusive Subscription**: Only one instance per cluster processes events (via FencedLock)
- **Ordered Processing**: Events for same aggregate processed in order (via [OrderedMessage](../foundation/README.md#ordered-message-processing))
- **Redelivery**: Failed events are retried per `RedeliveryPolicy`
- **Command Handling**: `@CmdHandler` methods handle commands via `DurableLocalCommandBus`

### InTransactionEventProcessor

**Class:** `InTransactionEventProcessor`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor`

For view projections requiring transactional consistency:

```java
@Service
public class OrderViewProcessor extends InTransactionEventProcessor {

    public OrderViewProcessor(EventProcessorDependencies dependencies) {
        super(dependencies, true);  // true = use exclusive subscriptions
    }

    @Override
    public String getProcessorName() {
        return "OrderViewProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    private void handle(OrderAdded event, OrderedMessage message) {
        // Runs within SAME transaction as event append
        var orderView = new OrderView(event.orderId(), event.customerId());
        orderViewRepository.save(orderView);
    }

    @MessageHandler
    private void handle(ProductAddedToOrder event, OrderedMessage message) {
        var orderView = orderViewRepository.findById(event.orderId());
        orderView.addProduct(event.productId(), event.quantity());
        orderViewRepository.save(orderView);
    }
}
```

#### Constructor Options

| Constructor | Behavior |
|-------------|----------|
| `super(dependencies)` | Non-exclusive subscriptions |
| `super(dependencies, true)` | Exclusive subscriptions (one instance per cluster) |
| `super(dependencies, false)` | Explicitly non-exclusive |

### ViewEventProcessor

**Class:** `ViewEventProcessor`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor`

Optimized for view projections with direct handling and fallback to durable queue on errors:

```java
@Service
public class OrderDashboardProcessor extends ViewEventProcessor {

    public OrderDashboardProcessor(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String getProcessorName() {
        return "OrderDashboardProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    private void handle(OrderAdded event, OrderedMessage message) {
        // Direct handling - low latency
        // On failure, event is queued for retry
        dashboardService.addOrder(event);
    }
}
```

#### Behavior

1. Events are handled **directly** for low latency
2. On **error**, the event is queued to a [DurableQueue](../foundation/README.md#durablequeues-messaging) for retry
3. If queue already has messages for that aggregate ID, new events are queued to maintain order

### Processor Lifecycle

All processors implement `Lifecycle`

---

## In-Memory Projections

Reconstruct aggregate state from events without persistence of changes to the projections (i.e. a change to a projection does not trigger a new event to be appended to the event store).

**Interface:** `InMemoryProjector`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

### Registering Projectors

```java
// Register custom projector
eventStore.addGenericInMemoryProjector(new OrderSummaryProjector());

// Project events to state
Optional<OrderSummary> summary = eventStore.inMemoryProjection(
    AggregateType.of("Orders"), orderId, OrderSummary.class);
```

### Custom Projector

```java
public class OrderSummaryProjector implements InMemoryProjector {

    @Override
    public boolean supports(Class<?> projectionType) {
        return OrderSummary.class.equals(projectionType);
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> projectEvents(
            AggregateType aggregateType,
            ID aggregateId,
            Class<PROJECTION> projectionType,
            EventStore eventStore) {

        return eventStore.fetchStream(aggregateType, aggregateId)
            .map(stream -> {
                var summary = new OrderSummary();
                // You can also use EventStreamEvolver.extractEvents(stream.eventList(), OrderEvent.class) to deserialize events
                stream.eventList().forEach(pe -> {
                    switch (pe.event().deserialize()) {
                        case OrderCreated e -> {
                            summary.orderId = e.orderId();
                            summary.createdAt = e.timestamp();
                        }
                        case ProductAdded e -> {
                            summary.itemCount++;
                            summary.total = summary.total.add(e.price());
                        }
                        default -> {}
                    }
                });
                return (PROJECTION) summary;
            });
    }
}
```

### AnnotationBasedInMemoryProjector

For annotation-driven projections using `@EventHandler` methods on POJOs, see [eventsourced-aggregates: In-Memory Projections](../eventsourced-aggregates/README.md#in-memory-projections).

### EventStreamEvolver

For functional state reconstruction patterns using immutable records, see [eventsourced-aggregates: EventStreamEvolver](../eventsourced-aggregates/README.md#eventstreamevolver).

---

## Gap Handling

The `EventStreamGapHandler` tracks transient and permanent gaps in event streams.

**Class:** `PostgresqlEventStreamGapHandler`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap`

### What Are Gaps?

A **gap** occurs when there's a missing `GlobalEventOrder` in the event stream for an `AggregateType`.  
This happens because PostgreSQL assigns `GlobalEventOrder` values (via a sequence) when events are **inserted**, but values only become visible to other transactions when the inserting transaction **commits**.

**Example: How gaps form with concurrent transactions**

```
Time →

TX1: [Insert event] ──────────────────────────────────────── [Commit]
     GlobalEventOrder = 1                                      ↓
                                                          Event 1 visible

TX2:        [Insert event] ── [Commit]
            GlobalEventOrder = 2    ↓
                               Event 2 visible

TX3:             [Insert event] ────── [Commit]
                 GlobalEventOrder = 3     ↓
                                     Event 3 visible

Subscription polling at this moment sees: 2, 3 (Gap at 1!)

Later, TX1 commits → Gap resolves: 1, 2, 3
```

In this scenario:
1. TX1 starts first, the Inserted Event gets `GlobalEventOrder = 1`, but takes longer to complete
2. TX2 and TX3 start later, get orders 2 and 3, and commit faster
3. A subscription polling during this window sees events 2 and 3, but event 1 is missing (gap)
4. When TX1 finally commits, the gap resolves naturally

### Out-of-Order Delivery Is Expected

Due to gaps, event handlers/consumers may receive events **out of `GlobalEventOrder` sequence**. This is normal and acceptable because:

**The consistency boundary is per-aggregate, not global:**

| Order Type | Guarantee | Why It Matters                                                                  |
|------------|-----------|---------------------------------------------------------------------------------|
| `EventOrder` | **Strict ordering per aggregate** | Events for `Order-123` are always processed in sequence (0, 1, 2, ...)          |
| `GlobalEventOrder` | **No strict ordering guarantee** | Events across different aggregates may arrive out of sequence and can have gaps |

```
Example: Consumer receives events in this order:

GlobalEventOrder  AggregateId   EventOrder
      2           Order-456        0        ✓ First event for Order-456
      3           Order-789        0        ✓ First event for Order-789
      1           Order-123        0        ✓ First event for Order-123 (arrived late due to gap)
      4           Order-456        1        ✓ Second event for Order-456

GlobalEventOrder is out of sequence (2, 3, 1, 4), but per-aggregate ordering is always preserved.
```

**Why per-aggregate ordering is guaranteed:**

Within a single aggregate, events are **always** committed in strict `EventOrder` sequence. This is enforced by the database schema and PostgreSQL's concurrency model:

1. **Unique constraint on `(aggregate_id, event_order)`** prevents duplicate event orders
2. **Read-committed isolation** means TX2 cannot see TX1's uncommitted events
3. **Optimistic concurrency** validates expected event order before appending

Consider two concurrent transactions both trying to append to `Order-123`:

```
TX1: Reads max EventOrder = -1 (no events) → Calculates next = 0 → Inserts EventOrder = 0
TX2: Reads max EventOrder = -1 (can't see TX1's uncommitted insert) → Also calculates next = 0

TX1: Commits successfully (EventOrder = 0 now visible)
TX2: Tries to commit → UNIQUE CONSTRAINT VIOLATION (EventOrder = 0 already exists)
```

TX2 must retry: read the now-visible `EventOrder = 0`, calculate `next = 1`, and append. This guarantees `EventOrder = 1` can only be committed **after** `EventOrder = 0` exists.

**Gaps only affect `GlobalEventOrder` across different aggregates** - never the `EventOrder` sequence within an aggregate.

This design reflects Domain-Driven Design principles: an aggregate is the consistency boundary, and events within an aggregate must be processed in order.  
Events across different aggregates are independent and don't require global ordering.

### Permanent Gaps from Transaction Rollbacks

A **permanent gap** occurs when a transaction that reserved a `GlobalEventOrder` is rolled back, leaving that sequence number permanently unused.

**Common causes:**

1. **In-transaction subscriber throws exception**: The EventStore flushes events before notifying in-transaction subscribers. If a subscriber throws an exception, the entire transaction rolls back—including the persisted event—leaving a gap.

2. **Database I/O errors**: Network issues, connection timeouts, or constraint violations can cause transaction rollbacks after sequence numbers are assigned.

3. **Application errors**: Any unhandled exception within the `UnitOfWork` causes a rollback.

```
Example: Permanent gap from in-transaction subscriber failure

TX1: [Insert event] → [Flush to DB] → [Notify subscriber] → [Subscriber throws!] → [Rollback]
     GlobalEventOrder = 5 assigned     Event visible        Exception!            Event removed
                                       momentarily                                Gap at 5 is permanent
```

The gap handler detects this by tracking how long a gap persists. If a gap exceeds the configured transaction timeout, it's marked **permanent** and excluded from queries to prevent subscriptions from blocking indefinitely.

### Configuration

```java
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),
    eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory)
);
```

### Gap Types

| Gap Type | Description | Resolution |
|----------|-------------|------------|
| **Transient** | Missing `GlobalEventOrder` that may appear when its transaction commits | Subscription waits and retries during polling |
| **Permanent** | Gap that exceeds the configured transaction timeout (transaction likely rolled back) | Marked permanent and excluded from queries |

### Behavior

- **Transient gaps** are expected during normal operation with concurrent writes
- The subscription manager tracks gaps and retries fetching missing events
- After a configurable timeout (typically longer than max transaction duration), transient gaps become **permanent**
- Permanent gaps indicate the inserting transaction was rolled back or failed
- Permanent gaps are excluded from `loadEventsByGlobalOrder` calls to prevent blocking subscriptions indefinitely
- Reset permanent gaps using `resetPermanentGapsFor(AggregateType)` if needed (e.g., after data recovery)

### Disabling Gap Handling

```java
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),
    eventStore -> new NoEventStreamGapHandler<>()  // No gap tracking
);
```

---

## Event Polling

The Event Store provides low-level polling methods for consuming events. These are the building blocks that the [Subscription Manager](#subscriptions) uses internally. For most use cases, prefer the higher-level subscription APIs.

### When to Use Direct Polling

| Use Case | Recommended Approach |
|----------|---------------------|
| **Standard event consumption** | Use `EventStoreSubscriptionManager` ([Subscriptions](#subscriptions)) |
| **Custom subscription logic** | Use `pollEvents()` or `unboundedPollForEvents()` |
| **One-time event loading** | Use `loadEventsByGlobalOrder()` |
| **Integration with external systems** | Use `pollEvents()` with custom processing |

### loadEventsByGlobalOrder

Loads events synchronously within a single database query. Use for batch processing or one-time event loading.

```java
// Load all events in a range
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    aggregateType,
    LongRange.from(1, 1000)  // GlobalEventOrder range
);

// Load with tenant filter
Stream<PersistedEvent> tenantEvents = eventStore.loadEventsByGlobalOrder(
    aggregateType,
    LongRange.from(1),
    null,                    // includeAdditionalGlobalOrders
    Optional.of(tenant)      // tenant filter
);

// Load with additional specific global orders (e.g., for gap handling)
List<GlobalEventOrder> additionalOrders = List.of(
    GlobalEventOrder.of(50), GlobalEventOrder.of(75)
);
Stream<PersistedEvent> events = eventStore.loadEventsByGlobalOrder(
    aggregateType,
    LongRange.from(100, 200),
    additionalOrders,        // Include these even if outside range
    Optional.empty()
);
```

### pollEvents (with Backpressure)

Returns a reactive `Flux` that continuously polls for new events. Supports backpressure via `Flux.limitRate()`.

```java
// Basic polling with defaults
Flux<PersistedEvent> eventFlux = eventStore.pollEvents(
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER // Poll events starting from and including this GlobalEventOrder
);

// Full configuration
Flux<PersistedEvent> eventFlux = eventStore.pollEvents(
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, // Poll events starting from and including this GlobalEventOrder
    Optional.of(100),                        // batch size (default: 100)
    Optional.of(Duration.ofMillis(500)),     // polling interval (default: 500ms)
    Optional.of(tenant),                     // tenant filter
    Optional.of(SubscriberId.of("my-sub")),  // subscriber ID for logging
    Optional.of(logName ->                   // polling optimizer factory
        EventStorePollingOptimizer.simpleJitterAndBackoff(logName))
);

// Apply backpressure
eventFlux
    .limitRate(10)  // Process max 10 events at a time
    .subscribe(event -> processEvent(event));
```

### unboundedPollForEvents (No Backpressure)

Similar to `pollEvents` but does **not** support backpressure. Use when you need to process events as fast as possible without flow control.

```java
Flux<PersistedEvent> eventFlux = eventStore.unboundedPollForEvents(
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, // Poll events starting from and including this GlobalEventOrder
    Optional.of(100),                        // batch size
    Optional.of(Duration.ofMillis(200)),     // polling interval
    Optional.empty(),                        // no tenant filter
    Optional.of(SubscriberId.of("fast-sub"))
);
```

### Polling Parameters

| Parameter | Type | Default | Description                                                        |
|-----------|------|---------|--------------------------------------------------------------------|
| `aggregateType` | `AggregateType` | Required | The aggregate type to poll events for                              |
| `fromInclusiveGlobalOrder` | `GlobalEventOrder` / `long` | Required | `GlobalEventOrder` starting point for polling (from and including) |
| `loadEventsByGlobalOrderBatchSize` | `Optional<Integer>` | 100 | Max events to include per database query                           |
| `pollingInterval` | `Optional<Duration>` | 500ms | Time between poll attempts when no events found                    |
| `onlyIncludeEventIfItBelongsToTenant` | `Optional<Tenant>` | Empty | Filter events by tenant                                            |
| `subscriptionId` | `Optional<SubscriberId>` | Random UUID | Subscriber Identifier for logging                                  |
| `eventStorePollingOptimizerFactory` | `Optional<Function<String, EventStorePollingOptimizer>>` | None | Factory for polling optimization                                   |

### EventStorePollingOptimizer

The `EventStorePollingOptimizer` provides adaptive polling behavior to reduce database load during idle periods:

```java
// No optimization (constant polling interval)
EventStorePollingOptimizer.None()

// Simple jitter and exponential backoff
EventStorePollingOptimizer.simpleJitterAndBackoff(logName)

// Custom optimizer
new EventStorePollingOptimizer() {
    @Override
    public Duration adjustPollingInterval(Duration baseInterval, boolean eventsFound) {
        if (eventsFound) {
            return baseInterval;  // Poll quickly when events are flowing
        }
        return baseInterval.multipliedBy(2);  // Back off when idle
    }
}
```

### Polling vs Subscriptions

For production use, prefer `EventStoreSubscriptionManager` which provides:

- **Resume points** - Automatically tracks last processed `GlobalEventOrder`
- **Durable subscriptions** - Survives application restarts
- **Exclusive subscriptions** - Only one instance processes events (uses `FencedLock`)
- **Gap handling** - Automatically handles transient and permanent gaps
- **Error handling** - Configurable retry and error strategies

See [Subscriptions](#subscriptions) and [EventProcessor Framework](#eventprocessor-framework) for higher-level event processing.

---

## EventStoreSubscriptionObserver

The `EventStoreSubscriptionObserver` provides observability into EventStore operations and subscription lifecycle events.  
It enables tracking of polling, event handling, lock acquisition, and performance metrics.

**Interface:** `EventStoreSubscriptionObserver`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability`

### Purpose

The observer tracks:
- **Subscription lifecycle** - Starting, stopping, lock acquisition/release
- **Event polling** - Batch sizes, poll durations, gap reconciliation
- **Event handling** - Processing times, failures, backpressure
- **Resume points** - Subscription checkpoint resolution

### Built-in Implementations

| Implementation | Purpose | Use Case |
|----------------|---------|----------|
| `NoOpEventStoreSubscriptionObserver` | No-op implementation (does nothing) | Default - no observability needed |
| `MeasurementEventStoreSubscriptionObserver` | Micrometer metrics integration | Production monitoring with Micrometer |

### Configuration

```java
// No observability (default)
var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory),
    new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver()
);

// With Micrometer metrics
var observer = new MeasurementEventStoreSubscriptionObserver(
    Optional.of(meterRegistry),  // MeterRegistry for metrics
    true,                         // Log slow operations
    LogThresholds.defaultThresholds(),
    null                          // Optional: custom observation registry
);

var eventStore = new PostgresqlEventStore<>(
    unitOfWorkFactory,
    persistenceStrategy,
    Optional.empty(),
    es -> new PostgresqlEventStreamGapHandler<>(es, unitOfWorkFactory),
    observer
);
```

### Custom Observer

Implement `EventStoreSubscriptionObserver` to collect custom metrics or integrate with other observability platforms:

```java
public class CustomObserver implements EventStoreSubscriptionObserver {
    @Override
    public void handleEvent(PersistedEvent event,
                           PersistedEventHandler eventHandler,
                           EventStoreSubscription subscription,
                           Duration duration) {
        // Track event processing time
        metricsService.recordEventProcessing(
            subscription.subscriberId(),
            event.aggregateType(),
            duration
        );
    }

    @Override
    public void handleEventFailed(PersistedEvent event,
                                 PersistedEventHandler eventHandler,
                                 Throwable cause,
                                 EventStoreSubscription subscription) {
        // Log failures
        log.error("Event handling failed for {}: {}",
            event.eventId(), cause.getMessage());
        alertService.notifyFailure(subscription.subscriberId(), cause);
    }

    @Override
    public void eventStorePolled(SubscriberId subscriberId,
                                AggregateType aggregateType,
                                LongRange globalOrderRange,
                                List<GlobalEventOrder> transientGapsToInclude,
                                Optional<Tenant> tenant,
                                List<PersistedEvent> events,
                                Duration pollDuration) {
        // Track polling performance
        metricsService.recordPoll(subscriberId, events.size(), pollDuration);
    }

    // Implement remaining interface methods...
}
```

### MeasurementEventStoreSubscriptionObserver

**Class:** `MeasurementEventStoreSubscriptionObserver`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer`

Provides Micrometer-based metrics for EventStore and subscription operations:

**Metrics tracked:**
- Event handling duration (timers)
- Event handling failures (counters)
- Polling batch sizes and durations
- Gap reconciliation times
- Lock acquisition/release events
- Resume point resolution times

**Configuration:**

```java
var observer = new MeasurementEventStoreSubscriptionObserver(
    Optional.of(meterRegistry),              // Micrometer registry
    true,                                     // Enable slow operation logging
    new LogThresholds(
        Duration.ofMillis(100),              // Warn on slow event handling
        Duration.ofMillis(500),              // Warn on slow polling
        Duration.ofMillis(50)                // Warn on slow gap reconciliation
    ),
    observationRegistry                      // Optional: for distributed tracing
);
```

**Integration with monitoring systems:**
- Prometheus (via Micrometer)
- Datadog
- New Relic
- CloudWatch
- Any Micrometer-supported backend

---

## Advanced Configuration

### SeparateTablePerAggregateTypePersistenceStrategy

The `SeparateTablePerAggregateTypePersistenceStrategy` is the primary persistence strategy for the Event Store.  
It creates a separate PostgreSQL table for each `AggregateType`, with all aggregate instances of that type sharing the same table and thereby providing Global ordering across 
instances of the same `AggregateType`.

**Class:** `SeparateTablePerAggregateTypePersistenceStrategy`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type`

#### Constructor Parameters

```java
var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(
    jdbi,                                    // JDBI instance
    unitOfWorkFactory,                       // EventStoreUnitOfWorkFactory
    eventMapper,                             // PersistableEventMapper
    configurationFactory,                    // AggregateEventStreamConfigurationFactory
    List.of(/* initial configurations */),   // Pre-registered configurations
    List.of(/* event enrichers */)           // PersistableEventEnricher list
);
```

| Parameter | Type | Description                                                     |
|-----------|------|-----------------------------------------------------------------|
| `jdbi` | `Jdbi` | The JDBI instance for database access                           |
| `unitOfWorkFactory` | `EventStoreUnitOfWorkFactory` | Factory for creating units of work                              |
| `eventMapper` | `PersistableEventMapper` | Maps domain events to `PersistableEvent`                        |
| `aggregateEventStreamConfigurationFactory` | `AggregateEventStreamConfigurationFactory` | Factory for creating aggreate stream persistence configurations |
| `aggregateTypeConfigurations` | `List<SeparateTablePerAggregateEventStreamConfiguration>` | Pre-registered aggregate configurations                         |
| `persistableEventEnrichers` | `List<PersistableEventEnricher>` | Enrichers called after event mapping                            |

#### Security Warning

> ⚠️ **WARNING:** Table names and column names are used directly in SQL statements via string concatenation.
>
> **You MUST sanitize all configuration values to prevent SQL injection.**
> See the [Security](#security) section for more details.

**Parameters requiring sanitization:**

| Parameter | Risk |
|-----------|------|
| `AggregateType` | Converted to table name |
| `eventStreamTableName` | Used directly in SQL |
| `eventStreamTableColumnNames` | All column names used in SQL |

### SeparateTablePerAggregateTypeEventStreamConfigurationFactory

When you register a new `AggregateType` with the Event Store (via `addAggregateEventStreamConfiguration`), the system needs to know how to persist events for that type: 
- which table to use
- what column names
- how to serialize JSON, etc. 
- 
- The `SeparateTablePerAggregateTypeEventStreamConfigurationFactory` provides these settings consistently across all your `AggregateType`s.

**Why use a factory?**

Rather than manually creating a `SeparateTablePerAggregateEventStreamConfiguration` for each aggregate type with identical settings (JSON serializer, column types, tenant serializer), the factory:

1. **Centralizes shared configuration** - Define JSON serialization, column types, and multitenancy once
2. **Generates table names automatically** - Derives table names from `AggregateType` (e.g., `Orders` → `orders_events` per default)
3. **Ensures consistency** - All aggregate types use the same serialization and storage settings
4. **Simplifies registration** - Just provide the `AggregateType` and ID class; the factory handles the rest

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EventStore Registration Flow                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  eventStore.addAggregateEventStreamConfiguration(                           │
│      AggregateType.of("Orders"), OrderId.class)                             │
│                         │                                                   │
│                         ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  SeparateTablePerAggregateTypeEventStreamConfigurationFactory       │    │
│  │  ─────────────────────────────────────────────────────────────────  │    │
│  │  • resolveEventStreamTableName: "Orders" → "orders_events"          │    │
│  │  • jsonSerializer: JacksonJSONEventSerializer                       │    │
│  │  • aggregateIdColumnType: UUID                                      │    │
│  │  • eventJsonColumnType: JSONB                                       │    │
│  │  • tenantSerializer: TenantIdSerializer                             │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                         │                                                   │
│                         ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  SeparateTablePerAggregateEventStreamConfiguration (for Orders)     │    │
│  │  ─────────────────────────────────────────────────────────────────  │    │
│  │  • aggregateType: "Orders"                                          │    │
│  │  • eventStreamTableName: "orders_events"                            │    │
│  │  • aggregateIdSerializer: CharSequenceTypeIdSerializer<OrderId>     │    │
│  │  • (inherits all settings from factory)                             │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Class:** `SeparateTablePerAggregateTypeEventStreamConfigurationFactory`  
**Package:** `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type`

#### Factory Methods

**Single-Tenant (Default):**

```java
// Minimal configuration - uses defaults
var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory
    .standardSingleTenantConfiguration(
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB
    );

// With custom table naming
var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory
    .standardSingleTenantConfiguration(
        aggregateType -> "events_" + aggregateType.toString().toLowerCase(), // ⚠️ See Security warning above
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB
        EventStreamTableColumnNames.defaultColumnNames(),
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB
    );
```

**Multi-Tenant:**

```java
var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory
    .standardConfiguration(
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB,
        new TenantSerializer.TenantIdSerializer()
    );

// With custom table naming and column names
var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory
    .standardConfiguration(
        aggregateType -> aggregateType + "_events", // ⚠️ See Security warning above
        EventStreamTableColumnNames.defaultColumnNames(),
        jsonSerializer,
        IdentifierColumnType.UUID,
        JSONColumnType.JSONB,
        new TenantSerializer.TenantIdSerializer()
    );
```

#### Full Constructor

For complete control over all configuration options:

```java
var factory = new SeparateTablePerAggregateTypeEventStreamConfigurationFactory(
    aggregateType -> aggregateType + "_events",  // Table name resolver - ⚠️ See Security warning above
    EventStreamTableColumnNames.defaultColumnNames(),
    100,                                         // Query fetch size
    jsonSerializer,
    IdentifierColumnType.UUID,                   // Aggregate ID column type
    IdentifierColumnType.UUID,                   // Event ID column type
    IdentifierColumnType.UUID,                   // Correlation ID column type
    JSONColumnType.JSONB,                        // Event JSON column type
    JSONColumnType.JSONB,                        // Metadata JSON column type
    new TenantSerializer.TenantIdSerializer()
);
```

#### Configuration Parameters

| Parameter | Type | Default | Description                                                             |
|-----------|------|---------|-------------------------------------------------------------------------|
| `resolveEventStreamTableName` | `Function<AggregateType, String>` | `type + "_events"` | Resolves table name from aggregate type - ⚠️ See Security warning above |
| `eventStreamTableColumnNames` | `EventStreamTableColumnNames` | `defaultColumnNames()` | Column names for event stream table - ⚠️ See Security warning above     |
| `queryFetchSize` | `int` | 100 | SQL fetch size for queries                                              |
| `jsonSerializer` | `JSONEventSerializer` | Required | Serializer for events and metadata                                      |
| `aggregateIdColumnType` | `IdentifierColumnType` | Required | SQL type for aggregate ID                                               |
| `eventIdColumnType` | `IdentifierColumnType` | Same as aggregate ID | SQL type for event ID                                                   |
| `correlationIdColumnType` | `IdentifierColumnType` | Same as aggregate ID | SQL type for correlation ID                                             |
| `eventJsonColumnType` | `JSONColumnType` | Required | SQL type for event JSON                                                 |
| `eventMetadataJsonColumnType` | `JSONColumnType` | Same as event JSON | SQL type for metadata JSON                                              |
| `tenantSerializer` | `TenantSerializer` | `NoSupportForMultiTenancySerializer` | Tenant serialization strategy                                           |

#### Column Type Options

**IdentifierColumnType:**

| Type | PostgreSQL Type | Use Case                                                                              |
|------|-----------------|---------------------------------------------------------------------------------------|
| `UUID` | `UUID` | UUID-based identifiers (recommended - unless your aggregate identifiers aren't UUIDs) |
| `TEXT` | `TEXT` | String-based identifiers                                                              |

**JSONColumnType:**

| Type | PostgreSQL Type | Use Case |
|------|-----------------|----------|
| `JSONB` | `JSONB` | Recommended - supports indexing and querying |
| `JSON` | `JSON` | Raw JSON storage |

### `EventStreamTableColumnNames`

Defines the column names for event stream tables. Use `defaultColumnNames()` for standard naming or create custom column names:

```java
// Default column names
var columnNames = EventStreamTableColumnNames.defaultColumnNames();

// Custom column names using builder - ⚠️ See Security warning above
var columnNames = EventStreamTableColumnNames.builder()
    .globalOrderColumn("global_seq")
    .timestampColumn("created_at")
    .eventIdColumn("event_uuid")
    .aggregateIdColumn("stream_id")
    .eventOrderColumn("stream_position")
    .eventTypeColumn("event_class")
    .eventRevisionColumn("schema_version")
    .eventPayloadColumn("payload")
    .eventMetaDataColumn("metadata")
    .causedByEventIdColumn("caused_by")
    .correlationIdColumn("correlation")
    .tenantColumn("tenant_id")
    .build();
```

**Default Column Names:**

| Column | Default Name | Description |
|--------|--------------|-------------|
| `globalOrderColumn` | `global_order` | Sequence across all aggregates |
| `timestampColumn` | `timestamp` | Event timestamp (UTC) |
| `eventIdColumn` | `event_id` | Unique event identifier |
| `aggregateIdColumn` | `aggregate_id` | Aggregate instance identifier |
| `eventOrderColumn` | `event_order` | Sequence within aggregate |
| `eventTypeColumn` | `event_type` | Event class or name |
| `eventRevisionColumn` | `event_revision` | Schema version |
| `eventPayloadColumn` | `event_payload` | Serialized event JSON |
| `eventMetaDataColumn` | `event_metadata` | Serialized metadata JSON |
| `causedByEventIdColumn` | `caused_by_event_id` | Causal event reference |
| `correlationIdColumn` | `correlation_id` | Correlation tracking |
| `tenantColumn` | `tenant` | Multi-tenancy identifier |

#### Security Warning

> ⚠️ **WARNING:** All column names are used directly in SQL statements via string concatenation.
>
> Column names are validated using `PostgresqlUtil.checkIsValidTableOrColumnName()` but this provides **initial defense only** and does NOT guarantee complete SQL injection protection.
>
> **You MUST derive column names from controlled, trusted sources only.**

See integration tests for complete configuration examples:
- [`SingleTenantPostgresqlEventStoreIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/SingleTenantPostgresqlEventStoreIT.java)
- [`MultiTenantPostgresqlEventStoreIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/MultiTenantPostgresqlEventStoreIT.java)

---

## Related Modules

| Module | Description |
|--------|-------------|
| [eventsourced-aggregates](../eventsourced-aggregates/README.md) | Aggregate patterns, `EventStreamEvolver`, `AnnotationBasedInMemoryProjector` |
| [spring-postgresql-event-store](../spring-postgresql-event-store/README.md) | Spring transaction integration |
| [spring-boot-starter-postgresql-event-store](../spring-boot-starter-postgresql-event-store/README.md) | Spring Boot auto-configuration |
| [foundation](../foundation/README.md) | Core infrastructure (UnitOfWork, FencedLock, DurableQueues, Inbox) |
| [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md) | Distributed locking for exclusive subscriptions |
| [postgresql-queue](../postgresql-queue/README.md) | Durable queues for `EventProcessor` and `ViewEventProcessor` |
| [foundation-types](../foundation-types/README.md) | Common types (`AggregateType`, `EventId`, `EventOrder`, `GlobalEventOrder`, `SubscriberId`, etc.) |
