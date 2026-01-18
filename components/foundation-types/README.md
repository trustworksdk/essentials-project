# Essentials Components - Foundation Types

> **NOTE:** **The library is WORK-IN-PROGRESS**

Common strongly-typed identifiers and event-sourcing types used across the Essentials Components.

**LLM Context:** [LLM-foundation-types.md](../../LLM/LLM-foundation-types.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Overview](#overview)
- [Type Overview](#type-overview)
- ⚠️ [Security](#security)
- [General Identifiers](#general-identifiers)
- [Multi-Tenancy](#multi-tenancy)
- [Event Store Types](#event-store-types)
- [Event Ordering](#event-ordering)
- [Versioning](#versioning)
- [Utilities](#utilities)
- [Usage Patterns](#usage-patterns)
- [Integration with Types Module](#integration-with-types-module)
- [Gotchas](#gotchas)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-types</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Note**: Usually transitively included by `foundation`, `postgresql-event-store`, or `eventsourced-aggregates`.

## Overview

This module provides reusable type-safe identifiers and value objects for:
- **Correlation and tracking** - Track operations across service boundaries
- **Event sourcing operations** - Event identification, ordering, and versioning
- **Multi-tenancy support** - Tenant isolation and scoping

**Package**: `dk.trustworks.essentials.components.foundation.types`

**Dependencies**: `types`, `shared`

**Used by**: `foundation`, `postgresql-event-store`, `eventsourced-aggregates`, all event-sourcing components

## Type Overview

| Category | Types | Base Class | Purpose |
|----------|-------|------------|---------|
| **General IDs** | `CorrelationId`, `EventId`, `MessageId`, `SubscriberId` | `CharSequenceType` | Track operations, events, messages, subscribers |
| **Multi-Tenancy** | `TenantId` (implements `Tenant`) | `CharSequenceType` | Tenant isolation |
| **Event Store** | `AggregateType`, `EventName`, `EventType`, `EventTypeOrName` | `CharSequenceType` | Event stream identification |
| **Event Ordering** | `EventOrder`, `GlobalEventOrder` | `LongType` | Sequential positioning |
| **Versioning** | `EventRevision`, `@Revision` | `IntegerType`, Annotation | Event schema versioning |
| **Utilities** | `RandomIdGenerator` | Static utility | Random ID generation |

---

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

### AggregateType SQL Injection Risk

**⚠️ CRITICAL WARNING**: When using `AggregateType` with `SeparateTablePerAggregateTypePersistenceStrategy`, the value is converted to a table name and used in SQL statements via string concatenation.

**This exposes `postgresql-event-store` to SQL injection attacks.**

#### Validation Layer

`SeparateTablePerAggregateTypePersistenceStrategy` calls `PostgresqlUtil.checkIsValidTableOrColumnName(String)` for initial validation:

```java
// Validation rules:
// - Start with letter (A-Z) or underscore (_)
// - Subsequent: letters, digits (0-9), underscores
// - No reserved SQL/PostgreSQL keywords
// - Max 63 characters

// Examples
PostgresqlUtil.checkIsValidTableOrColumnName("Order");      // ✅ OK
PostgresqlUtil.checkIsValidTableOrColumnName("Customer");   // ✅ OK
PostgresqlUtil.checkIsValidTableOrColumnName("SELECT");     // ❌ Throws - reserved keyword
PostgresqlUtil.checkIsValidTableOrColumnName("123_table");  // ❌ Throws - starts with digit
PostgresqlUtil.checkIsValidTableOrColumnName("drop; --");   // ❌ Throws - invalid characters
```

**⚠️ IMPORTANT**: This validation is **NOT exhaustive protection**.

#### Developer Responsibilities

**MUST:**
1. **NEVER** use user input directly for `AggregateType`
2. Derive `AggregateType` only from **controlled, trusted sources**:
    - Hard-coded constants
    - Enum values
    - Pre-validated whitelist
3. Implement **additional sanitization** beyond built-in validation

**Pattern - Safe Usage:**

```java
// ✅ SAFE - Hard-coded constant
AggregateType ORDER = AggregateType.of("Orders");

// ✅ SAFE - Enum-based
public enum AggregateTypes {
    ORDER("Orders"),
    CUSTOMER("Customers");

    private final AggregateType type;
    AggregateTypes(String name) { this.type = AggregateType.of(name); }
    public AggregateType getType() { return type; }
}

// ✅ SAFE - Whitelist validation
private static final Set<String> ALLOWED_AGGREGATE_TYPES =
    Set.of("Orders", "Customers", "Products");

public AggregateType createAggregateType(String input) {
    if (!ALLOWED_AGGREGATE_TYPES.contains(input)) {
        throw new ValidationException("Invalid aggregate type: " + input);
    }
    return AggregateType.of(input);
}

// ❌ DANGEROUS - User input
@PostMapping("/events/{aggregateType}")
public void appendEvent(@PathVariable String aggregateType, @RequestBody Event event) {
    eventStore.appendToStream(
        AggregateType.of(aggregateType),  // SQL INJECTION RISK!
        event.getAggregateId(),
        event
    );
}
```

See [postgresql-event-store Security](../postgresql-event-store/README.md#security) for full details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## General Identifiers

### CorrelationId

Tracks related operations across service boundaries.

```java
// Construction
CorrelationId.of(CharSequence value)
CorrelationId.random()

// Usage
CorrelationId correlationId = CorrelationId.random();
logger.info("Processing with correlation: {}", correlationId);

// Propagate through call chain
MDC.put("correlationId", correlationId.toString());
try {
    processOrder(orderId, correlationId);
} finally {
    MDC.remove("correlationId");
}
```

### EventId

Unique event identifier within event streams.

```java
// Construction
EventId.of(CharSequence value)
EventId.random()

// Usage
EventId eventId = EventId.random();
PersistedEvent event = PersistedEvent.of(eventId, aggregateId, payload);
```

### MessageId

Unique message identifier that a user can associate a message with.

**Note**: `MessageId` is a user value object, internally `DurableQueues` uses `QueueEntryId` as the unique technical identifier for each queued message.

```java
// Construction
MessageId.of(CharSequence value)
MessageId.random()

// Usage
MessageId messageId = MessageId.random();
Message msg = Message.of(messageId, payload);
```

### SubscriberId

Identifies event stream subscribers.

```java
// Construction
SubscriberId.of(CharSequence value)

// Usage
SubscriberId subscriberId = SubscriberId.of("OrderEventProcessor");

subscriptionManager.subscribeToAggregateEventsAsynchronously(
    subscriberId,
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(), // No TenantId filter
    handler
);
```

---

## Multi-Tenancy

### Tenant Interface

Marker interface for tenant representation (no methods).

```java
public interface Tenant {
    // Marker interface - implementers represent a tenant
}
```

### TenantId

Default `Tenant` implementation for tenant isolation.

```java
// Construction
TenantId.of(CharSequence value)

// Usage
TenantId tenantId = TenantId.of("acme-corp");
String value = tenantId.toString();  // "acme-corp"

// Use in multi-tenant queries
List<Order> orders = repository.findByTenantId(tenantId);
```

**Pattern**: Pass `TenantId` to queries/commands for tenant-scoped operations.

---

## Event Store Types

### AggregateType

Identifies the aggregate type (e.g., "Orders", "Customers").

```java
// Construction
AggregateType.of(CharSequence value)

// Usage
AggregateType orderType = AggregateType.of("Orders");
eventStore.appendToStream(orderType, orderId, events);

// Stream naming convention
// Stream name = AggregateType:AggregateId
// Example: "Orders:ORD-12345"
```

#### AggregateType Naming Convention

**Important**: `AggregateType` is a logical name, **not** a Java class name. Use **plural names** to distinguish from implementation classes:

| AggregateType | Implementation Class | Purpose |
|---------------|---------------------|---------|
| `Orders` | `Order` | Order events stream |
| `Accounts` | `Account` | Account events stream |
| `Customers` | `Customer` | Customer events stream |

**⚠️ CRITICAL SECURITY WARNING**: See [Security](#security) section above.

### EventName

Human-readable event name used for **Named Events** (logical string identifier).

```java
// Construction
EventName.of(CharSequence value)

// Usage
EventName eventName = EventName.of("OrderCreated");
```

**Use Case**: Named Events - external events, legacy systems, schema-less events where you work with raw JSON.

### EventType

Event class fully-qualified name (FQCN) used for **Typed Events**.

```java
// Construction
EventType.of(CharSequence value)
EventType.of(Class<?> eventClass)

// Usage
EventType eventType = EventType.of(OrderCreatedEvent.class);
// Value: "com.example.events.OrderCreatedEvent"

EventType eventType = EventType.of("com.example.events.OrderCreatedEvent");
```

**Use Case**: Typed Events (default) - standard domain events with Java classes, automatic deserialization.

### EventTypeOrName

Union type - either `EventType` or `EventName`.

```java
// Construction - uses overloaded with() method
EventTypeOrName.with(EventType eventType)
EventTypeOrName.with(Class<?> eventClass)
EventTypeOrName.with(EventName eventName)

// Query methods
boolean hasEventName()
boolean hasEventType()
Optional<EventName> getEventName()
Optional<EventType> getEventType()

// Usage - Pattern matching
EventTypeOrName typeOrName = EventTypeOrName.with(OrderCreatedEvent.class);

if (typeOrName.hasEventType()) {
    EventType type = typeOrName.getEventType().get();
    // Use EventType
} else {
    EventName name = typeOrName.getEventName().get();
    // Use EventName
}

// Used when creating PersistableEvent in PersistableEventMapper

// Typed Events - use EventType
PersistableEvent.from(
    EventId.random(),
    aggregateType,
    aggregateId,
    EventTypeOrName.with(OrderCreatedEvent.class),  // Typed Event
    event,
    eventOrder,
    EventRevision.of(1),
    metadata,
    timestamp,
    causedBy,
    correlationId,
    tenant
);

// Named Events - use EventName
PersistableEvent.from(
    EventId.random(),
    aggregateType,
    aggregateId,
    EventTypeOrName.with(EventName.of("OrderCreated")),  // Named Event
    eventPayload,
    eventOrder,
    EventRevision.of(1),
    metadata,
    timestamp,
    causedBy,
    correlationId,
    tenant
);
```

### Typed Events vs Named Events

The Event Store supports two approaches for identifying events:

| Approach | Identifier | Payload | Deserialization | Use Case |
|----------|------------|---------|-----------------|----------|
| **Typed Events** | `EventType` (Java FQCN) | Java object | Automatic via `deserialize()` | Standard domain events with Java classes |
| **Named Events** | `EventName` (logical string) | Raw JSON string | Manual via `getJson()` | External events, legacy systems, schema-less |

#### Typed Events (Default)

Uses the Java class's Fully Qualified Class Name (FQCN) to identify the event type. Events are stored with a `FQCN:` prefix and can be automatically deserialized.

```java
// Define event class
public record OrderCreated(OrderId orderId, CustomerId customerId) {}

// Append typed event
eventStore.appendToStream(orders, orderId, new OrderCreated(orderId, customerId));

// Fetch and deserialize automatically
stream.eventList().forEach(pe -> {
    Object event = pe.event().deserialize();  // Automatic deserialization
    if (event instanceof OrderCreated orderCreated) {
        // Type-safe access to event data
    }
});

// Subscribe using PatternMatchingPersistedEventHandler
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    subscriberId,
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(), // No tenant filter
    new PatternMatchingPersistedEventHandler() {
        @SubscriptionEventHandler
        public void handle(OrderCreated event) {
            // Type-safe event handling
        }
    }
);
```

#### Named Events

Uses a logical name string without requiring a corresponding Java class. Event payload is stored and retrieved as raw JSON.

```java
// Append named event using Map
Map<String, Object> externalEvent = Map.of(
    "type", "ExternalOrderReceived",
    "externalOrderId", "EXT-12345",
    "items", List.of("SKU-001", "SKU-002")
);
eventStore.appendToStream(orders, orderId, externalEvent);

// Fetch and work with raw JSON
stream.eventList().forEach(pe -> {
    if (pe.event().getEventName().isPresent()) {
        String eventName = pe.event().getEventName().get().toString();
        String rawJson = pe.event().getJson();
        // Manual deserialization if needed
    }
});

// Subscribe using PatternMatchingPersistedEventHandler
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    subscriberId,
    aggregateType,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(), // No tenant filter
    new PatternMatchingPersistedEventHandler() {
        @SubscriptionEventHandler
        private void handle(String json, PersistedEvent metadata) {
            if (metadata.event().getEventName().isPresent()) {
                String eventName = metadata.event().getEventName().get().toString();
                // Handle named event with raw JSON
            }
        }
    }
);
```

#### When to Use Each Approach

| Scenario | Recommended Approach |
|----------|---------------------|
| Domain events in your application | **Typed Events** |
| Events from external systems (Kafka, webhooks) | **Named Events** |
| Schema evolution where Java classes change | **Named Events** |
| Schema-free events | **Named Events** |
| Full type safety and IDE support | **Typed Events** |

---

## Event Ordering

Two types of ordering track event positions at different scopes:

| Order Type | Scope | Starting Value | Purpose |
|------------|-------|----------------|---------|
| `EventOrder` | Per aggregate instance | 0 | Sequence within a specific aggregate |
| `GlobalEventOrder` | Per aggregate type | 1 | Sequence across all aggregates of same type |

### EventOrder

Sequential position within an aggregate's event stream (per-aggregate instance).

```java
// Construction
EventOrder.of(long value)
EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED  // Constant: 0L

// Usage
EventOrder currentOrder = EventOrder.of(5);
EventOrder nextOrder = currentOrder.increment();  // 6

// Expected event order when appending
eventStore.appendToStream(
    aggregateType,
    aggregateId,
    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,  // First event for this aggregate instance
    events
);
```

**Scope**: Per aggregate instance (e.g., Order with id "ORD-123" has its own EventOrder sequence starting at 0)

**Pattern**: Starts at 0 for new aggregates, increments with each event (0, 1, 2, 3...).

### GlobalEventOrder

Global sequential position across ALL events in the event store for a specific aggregate type.

```java
// Construction
GlobalEventOrder.of(long value)
GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER  // Constant: 1L

// Usage
GlobalEventOrder resumePoint = GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER;

subscriptionManager.subscribeToAggregateEventsAsynchronously(
    subscriberId,
    aggregateType,
    resumePoint,
    Optional.empty(), // No tenant filter
    event -> {
        process(event);
        // Subscription automatically tracks GlobalEventOrder
    }
);
```

**Scope**: Per aggregate type (e.g., all "Orders" events share one GlobalEventOrder sequence, all "Customers" events share another)

**Pattern**: Starts at 1, globally unique within aggregate type, monotonically increasing (1, 2, 3, 4...).

---

## Versioning

### EventRevision

Event schema version (for event evolution).

```java
// Construction
EventRevision.of(int value)
EventRevision.FIRST  // Constant: 1

// Usage
EventRevision revision = EventRevision.of(2);  // Event schema v2

// Typically resolved automatically from @Revision annotation
EventRevision revision = PersistableEvent.resolveEventRevision(event);
```

### @Revision

Annotation for marking event classes with their schema revision. Read by `PersistableEvent.resolveEventRevision()` when persisting events.

**Package**: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Revision {
    int value() default 1;
}
```

#### Usage

```java
// Base event class with revision 2
@Revision(2)
public class OrderEvent {
    public final OrderId orderId;

    public OrderEvent(OrderId orderId) {
        this.orderId = orderId;
    }

    // Nested event inherits @Revision(2) from parent
    public static class OrderAccepted extends OrderEvent {
        public OrderAccepted(OrderId orderId) {
            super(orderId);
        }
    }

    // Nested event overrides with revision 3
    @Revision(3)
    public static class OrderAdded extends OrderEvent {
        public final CustomerId customerId;

        public OrderAdded(OrderId orderId, CustomerId customerId) {
            super(orderId);
            this.customerId = customerId;
        }
    }
}

// Event without annotation defaults to EventRevision.FIRST (1)
public class ProductAdded extends ProductEvent {
    // Revision = 1 (default)
}

// Specific revision on nested class
public class ProductEvent {
    @Revision(2)
    public static class ProductDiscontinued extends ProductEvent {
        // Revision = 2
    }
}
```

**How it works:**
1. Annotate event classes with `@Revision(n)` to indicate schema version
2. `PersistableEvent.resolveEventRevision()` reads the annotation
3. If no annotation present, defaults to `EventRevision.FIRST` (1)
4. Nested classes can override parent revision
5. Annotation is `@Inherited`, so subclasses inherit parent revision unless overridden

---

## Utilities

### RandomIdGenerator

```java
// Static method
static String generate()

// Usage
String randomId = RandomIdGenerator.generate();
// Returns: UUID string (e.g., "550e8400-e29b-41d4-a716-446655440000")

CorrelationId id = CorrelationId.of(RandomIdGenerator.generate());
EventId eventId = EventId.of(RandomIdGenerator.generate());
```

**Note**: Uses per default `UUID.randomUUID().toString()` internally, unless `com.fasterxml.uuid.Generators` is available on the classpath,
in which case it uses `Generators.timeBasedGenerator().generate().toString()`.

You can override the default generator(s) by calling `RandomIdGenerator.overrideRandomIdGenerator`

---

## Usage Patterns

### Event Store Integration

```java
// Define aggregate type (safe - constant)
private static final AggregateType ORDER_TYPE = AggregateType.of("Orders");

// Append events
EventOrder expectedOrder = EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED;
eventStore.appendToStream(
    ORDER_TYPE,
    orderId,
    expectedOrder,
    List.of(new OrderCreatedEvent(orderId, customerId))
);

// Load event stream
Optional<AggregateEventStream> stream = eventStore.fetchStream(
    ORDER_TYPE,
    orderId
);

stream.ifPresent(s -> {
    EventOrder currentOrder = s.eventOrderOfLastEvent();
    List<PersistedEvent> events = s.events();
});
```

### Subscription Pattern

```java
// Setup subscription manager (required)
var subscriptionManager = EventStoreSubscriptionManager.builder()
    .setEventStore(eventStore)
    .setEventStorePollingBatchSize(10)
    .setEventStorePollingInterval(Duration.ofMillis(100))
    .setFencedLockManager(fencedLockManager)
    .setSnapshotResumePointsEvery(Duration.ofSeconds(10))
    .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
    .build();

subscriptionManager.start();

// Asynchronous subscription (out-of-transaction, after commit)
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    SubscriberId.of("OrderEventProcessor"),
    ORDER_TYPE,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,  // Start from beginning on first subscription
    Optional.empty(),  // No tenant filter
    new PatternMatchingPersistedEventHandler() {
        @SubscriptionEventHandler
        public void handle(OrderCreatedEvent event) {
            // Handle typed event
        }

        @SubscriptionEventHandler
        public void handle(OrderAcceptedEvent event, PersistedEvent metadata) {
            // Access metadata (eventId, globalOrder, timestamp, etc.)
            EventId eventId = metadata.eventId();
            GlobalEventOrder globalOrder = metadata.globalEventOrder();
            EventOrder eventOrder = metadata.eventOrder();
        }
    }
);

// In-transaction subscription (same transaction as event append)
subscriptionManager.subscribeToAggregateEventsInTransaction(
    SubscriberId.of("OrderProjection"),
    ORDER_TYPE,
    Optional.empty(),  // No tenant filter
    new TransactionalPersistedEventHandler() {
        @Override
        public void handle(PersistedEvent event, UnitOfWork unitOfWork) {
            // Runs in SAME transaction as event append
            updateProjection(event, unitOfWork);
        }
    }
);
```

### Multi-Tenant Event Store

Multi-tenancy is handled via the `PersistableEventMapper`, not as a parameter to `appendToStream`.

```java
// Configure PersistableEventMapper to associate events with tenant
public class MultiTenantPersistableEventMapper implements PersistableEventMapper {
    private final TenantResolver tenantResolver;

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
            null,
            CorrelationId.random(),
            tenantResolver.getCurrentTenant()  // Tenant association here
        );
    }
}

// Append events - tenant is embedded via mapper
TenantId tenant = TenantId.of("acme-corp");
eventStore.appendToStream(
    ORDER_TYPE,
    orderId,
    expectedOrder,
    events  // Tenant already embedded in events via PersistableEventMapper
);

// Fetch stream with tenant filter
Optional<AggregateEventStream> stream = eventStore.fetchStream(
    ORDER_TYPE,
    orderId,
    tenant  // Filter by tenant
);

// Read tenant from persisted events
stream.ifPresent(s -> {
    s.eventList().forEach(event -> {
        Optional<? extends Tenant> eventTenant = event.tenant();
        // Access tenant associated with event
    });
});
```

### Correlation Tracking

```java
// Generate correlation ID at service boundary
CorrelationId correlationId = CorrelationId.random();

// Propagate through call chain
MDC.put("correlationId", correlationId.toString());

try {
    processOrder(orderId, correlationId);
} finally {
    MDC.remove("correlationId");
}

// Include in events
EventMetadata metadata = EventMetadata.builder()
    .correlationId(correlationId)
    .build();
```

### Typed vs Named Events in Subscriptions

```java
// Subscribe to all events for an aggregate type
subscriptionManager.subscribeToAggregateEventsAsynchronously(
    SubscriberId.of("MixedEventHandler"),
    ORDER_TYPE,
    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
    Optional.empty(), // No tenant filter
    new PatternMatchingPersistedEventHandler() {
        // Typed Events - automatic deserialization and routing
        @SubscriptionEventHandler
        public void handle(OrderCreatedEvent event) {
            // Type-safe access to domain event
            System.out.println("Order created: " + event.orderId());
        }

        @SubscriptionEventHandler
        public void handle(OrderAcceptedEvent event, PersistedEvent metadata) {
            // Type-safe with metadata access
            System.out.println("Order accepted at: " + metadata.timestamp());
        }

        // Named Events - raw JSON for events without Java classes
        @SubscriptionEventHandler
        private void handle(String json, PersistedEvent metadata) {
            // Check if this is a named event
            if (metadata.event().getEventName().isPresent()) {
                String eventName = metadata.event().getEventName().get().toString();
                System.out.println("Named event: " + eventName);
                System.out.println("JSON: " + json);
                // Manual deserialization if needed
            }
        }

        @Override
        protected void handleUnmatchedEvent(PersistedEvent event) {
            // Custom handling for events without matching @SubscriptionEventHandler
            log.warn("Unmatched event: {}", event.event().getEventTypeOrName());
        }
    }
);
```

### Event Versioning with @Revision

```java
// Version 1 of OrderCreated event (no annotation = revision 1)
public record OrderCreatedV1(OrderId orderId, CustomerId customerId) {}

// Version 2 adds totalAmount field
@Revision(2)
public record OrderCreatedV2(OrderId orderId, CustomerId customerId, Amount totalAmount) {}

// Base event class with shared revision
@Revision(2)
public class OrderEvent {
    public final OrderId orderId;

    public OrderEvent(OrderId orderId) {
        this.orderId = orderId;
    }

    // Inherits revision 2 from parent
    public static class OrderAccepted extends OrderEvent {
        public OrderAccepted(OrderId orderId) {
            super(orderId);
        }
    }

    // Overrides to revision 3 for schema change
    @Revision(3)
    public static class OrderAdded extends OrderEvent {
        public final CustomerId customerId;
        public final Amount totalAmount;

        public OrderAdded(OrderId orderId, CustomerId customerId, Amount totalAmount) {
            super(orderId);
            this.customerId = customerId;
            this.totalAmount = totalAmount;
        }
    }
}

// The EventStore automatically resolves the revision
eventStore.appendToStream(
    ORDER_TYPE,
    orderId,
    new OrderCreatedV2(orderId, customerId, Amount.of("99.95"))
    // EventRevision.of(2) is automatically extracted from @Revision(2)
);
```

---

## Integration with Types Module

All foundation types extend base classes from [types](../../types/README.md):

```java
// CharSequenceType subclasses
CorrelationId extends CharSequenceType<CorrelationId> implements Identifier
EventId extends CharSequenceType<EventId> implements Identifier
MessageId extends CharSequenceType<MessageId> implements Identifier
SubscriberId extends CharSequenceType<SubscriberId> implements Identifier
TenantId extends CharSequenceType<TenantId> implements Tenant, Identifier
AggregateType extends CharSequenceType<AggregateType>
EventName extends CharSequenceType<EventName>
EventType extends CharSequenceType<EventType>

// LongType subclasses
EventOrder extends LongType<EventOrder>
GlobalEventOrder extends LongType<GlobalEventOrder>

// IntegerType subclasses
EventRevision extends IntegerType<EventRevision>

// Annotations
@Revision - Annotation for marking event schema versions
```

**Benefit**: Inherits all `SingleValueType` features:
- Jackson serialization (with [types-jackson](../../types-jackson/README.md))
- Spring Data MongoDB persistence (with [types-springdata-mongo](../../types-springdata-mongo/README.md))
- JDBI argument support (with [types-jdbi](../../types-jdbi/README.md))
- Spring Web converters (with [types-spring-web](../../types-spring-web/README.md))

---

## Gotchas

- `AggregateType` has SQL injection risk - **NEVER** use user input directly
- `AggregateType` should use plural names (Orders, Customers) to distinguish from implementation classes (Order, Customer)
- `EventOrder` starts at 0, `GlobalEventOrder` starts at 1 (different conventions)
- `EventOrder` is per aggregate instance, `GlobalEventOrder` is per aggregate type (different scopes)
- `EventTypeOrName` requires pattern matching - use `hasEventType()`/`hasEventName()` before extraction
- Typed Events use FQCN and auto-deserialize, Named Events use logical names and raw JSON
- `@Revision` is an annotation, `EventRevision` is the type - don't confuse them
- `@Revision` defaults to 1 if not specified, can be overridden in subclasses
- `EventRevision` is `IntegerType` (not `LongType`)
- `SubscriberId` should be unique per consumer to avoid subscription conflicts
- `RandomIdGenerator` uses UUID internally - not sequential, not sortable
- All types are immutable - operations return new instances

---

## See Also

- [LLM-foundation-types.md](../../LLM/LLM-foundation-types.md) - LLM-optimized reference
- [types](../../types/README.md) - Base `SingleValueType` patterns
- [foundation](../foundation/README.md) - Foundation components using these types
- [postgresql-event-store](../postgresql-event-store/README.md) - Event Store implementation
- [eventsourced-aggregates](../eventsourced-aggregates/README.md) - Event-sourced aggregate framework
