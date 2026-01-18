# Foundation Types - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README](../components/foundation-types/README.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.foundation.types` + `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`
- **Purpose**: Common strongly-typed identifiers and value objects for event-sourcing and multi-tenancy
- **Dependencies**: `types`, `shared`
- **Used by**: `foundation`, `postgresql-event-store`, `eventsourced-aggregates`

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-types</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `CharSequenceType`, `LongType`, `IntegerType` base types from [types](./LLM-types.md)

## TOC
- [Type Overview](#type-overview)
- [General Identifiers](#general-identifiers)
- [Multi-Tenancy](#multi-tenancy)
- [Event Store Types](#event-store-types)
- [Event Ordering](#event-ordering)
- [Versioning](#versioning)
- [Utilities](#utilities)
- ⚠️ [Security](#security)
- [See Also](#see-also)

## Type Overview

Base package: `dk.trustworks.essentials.components`

| Category | Types | Base Class | Package Suffix |
|----------|-------|------------|----------------|
| **General IDs** | `CorrelationId`, `EventId`, `MessageId`, `SubscriberId` | `CharSequenceType` | `.foundation.types` |
| **Multi-Tenancy** | `TenantId`, `Tenant` | `CharSequenceType`, Interface | `.foundation.types` |
| **Event Identification** | `EventName`, `EventType`, `EventTypeOrName` | `CharSequenceType`, Union | `.eventsourced.eventstore.postgresql.types` |
| **Stream Identification** | `AggregateType` | `CharSequenceType` | `.eventsourced.eventstore.postgresql.eventstream` |
| **Event Ordering** | `EventOrder`, `GlobalEventOrder` | `LongType` | `.eventsourced.eventstore.postgresql.types` |
| **Versioning** | `EventRevision`, `@Revision` | `IntegerType`, Annotation | `.eventsourced.eventstore.postgresql.types` |
| **Utilities** | `RandomIdGenerator` | Static utility | `.foundation.types` |

---

## General Identifiers

Base package: `dk.trustworks.essentials.components.foundation.types`

### CorrelationId

Tracks related operations across service boundaries.

```java
// API
CorrelationId.of(CharSequence value)
CorrelationId.random()

// Usage
CorrelationId correlationId = CorrelationId.random();
MDC.put("correlationId", correlationId.toString());
```

**Pattern**: Generate at service entry, propagate through call chain, include in events.

### EventId

Unique event identifier within event streams.

```java
// API
EventId.of(CharSequence value)
EventId.random()

// Usage
EventId eventId = EventId.random();
```

**Used by**: Event Store to uniquely identify each persisted event.

### MessageId

User-facing message identifier (distinct from internal queue IDs).

```java
// API
MessageId.of(CharSequence value)
MessageId.random()

// Usage
MessageId messageId = MessageId.random();
```

**Note**: DurableQueues uses `QueueEntryId` internally - `MessageId` is for user correlation only.

### SubscriberId

Identifies event stream subscribers.

```java
// API
SubscriberId.of(CharSequence value)

// Usage
SubscriberId subscriberId = SubscriberId.of("OrderEventProcessor");
```

**Pattern**: One `SubscriberId` per consumer to track resume points independently.

---

## Multi-Tenancy

Base package: `dk.trustworks.essentials.components.foundation.types`

### Tenant Interface

Marker interface for tenant representation (no methods).

```java
package dk.trustworks.essentials.components.foundation.types;

public interface Tenant {
    // Marker interface
}
```

### TenantId

Default `Tenant` implementation.

```java
// API
TenantId.of(CharSequence value)

// Usage
TenantId tenantId = TenantId.of("acme-corp");
String value = tenantId.toString();  // "acme-corp"
```

**Pattern**: Pass to queries/commands for tenant-scoped operations.

---

## Event Store Types

### AggregateType

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream`

Identifies aggregate type (e.g., "Orders", "Customers").

```java
// API
AggregateType.of(CharSequence value)

// Usage
AggregateType orderType = AggregateType.of("Orders");
```

**Stream naming**: `{AggregateType}:{AggregateId}` → `"Orders:ORD-12345"`

#### Naming Convention

`AggregateType` is a **logical name**, not Java class name. Use **plural** to distinguish from implementation:

| AggregateType | Implementation Class | Stream Example |
|---------------|---------------------|----------------|
| `Orders` | `Order` | `Orders:ORD-123` |
| `Accounts` | `Account` | `Accounts:ACC-456` |
| `Customers` | `Customer` | `Customers:CUST-789` |

**⚠️ CRITICAL**: See [Security](#security) below - SQL injection risk.

### EventName

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

Human-readable event name for **Named Events** (schema-less, raw JSON).

```java
// API
EventName.of(CharSequence value)

// Usage
EventName eventName = EventName.of("OrderCreated");
```

**Use Case**: External events, legacy systems, schema-free events.

### EventType

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

Event class fully-qualified name (FQCN) for **Typed Events**.

```java
// API
EventType.of(CharSequence value)
EventType.of(Class<?> eventClass)

// Usage
EventType eventType = EventType.of(OrderCreatedEvent.class);
// Value: "com.example.events.OrderCreatedEvent"
```

**Use Case**: Standard domain events with Java classes, automatic deserialization.

### EventTypeOrName

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

Union type - either `EventType` or `EventName`.

```java
// API - Construction
EventTypeOrName.with(EventType eventType)
EventTypeOrName.with(Class<?> eventClass)
EventTypeOrName.with(EventName eventName)

// API - Query
boolean hasEventName()
boolean hasEventType()
Optional<EventName> getEventName()
Optional<EventType> getEventType()

// Usage - Pattern matching
EventTypeOrName typeOrName = EventTypeOrName.with(OrderCreatedEvent.class);

if (typeOrName.hasEventType()) {
    EventType type = typeOrName.getEventType().get();
} else {
    EventName name = typeOrName.getEventName().get();
}
```

**Pattern**: Check `hasEventType()` or `hasEventName()` before extracting.

### Typed vs Named Events

| Approach | Identifier | Payload | Deserialization | Use Case |
|----------|------------|---------|-----------------|----------|
| **Typed** | `EventType` (FQCN) | Java object | Automatic | Domain events with Java classes |
| **Named** | `EventName` (string) | Raw JSON | Manual | External/legacy/schema-free |

**Typed Events**: Use Java FQCN, automatic deserialization via `deserialize()`
**Named Events**: Use logical string, manual processing via `getJson()`

---

## Event Ordering

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

| Type | Scope | Starting Value | Purpose |
|------|-------|----------------|---------|
| `EventOrder` | Per aggregate instance | 0 | Sequence within specific aggregate |
| `GlobalEventOrder` | Per aggregate type | 1 | Sequence across all aggregates of type |

### EventOrder

Sequential position within aggregate's event stream.

```java
// API
EventOrder.of(long value)
EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED  // Constant: 0L

// Methods
EventOrder increment()

// Usage
EventOrder currentOrder = EventOrder.of(5);
EventOrder nextOrder = currentOrder.increment();  // 6
```

**Scope**: Per aggregate instance (Order `ORD-123` has own sequence: 0, 1, 2, 3...)

### GlobalEventOrder

Global sequential position across all events for aggregate type.

```java
// API
GlobalEventOrder.of(long value)
GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER  // Constant: 1L

// Usage
GlobalEventOrder resumePoint = GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER;
```

**Scope**: Per aggregate type (all "Orders" share one sequence: 1, 2, 3, 4...)
**Use**: Subscription resume points, event ordering across instances.

---

## Versioning

Package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types`

### EventRevision

Event schema version.

```java
// API
EventRevision.of(int value)
EventRevision.FIRST  // Constant: 1

// Usage
EventRevision revision = EventRevision.of(2);
```

**Pattern**: Resolved automatically from `@Revision` annotation.

### @Revision

Annotation for event schema versioning.

```java
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Revision {
    int value() default 1;
}
```

#### Usage Pattern

```java
// Base event with revision 2
@Revision(2)
public class OrderEvent {
    public final OrderId orderId;

    // Nested event inherits @Revision(2)
    public static class OrderAccepted extends OrderEvent {
        // Revision = 2 (inherited)
    }

    // Override to revision 3
    @Revision(3)
    public static class OrderAdded extends OrderEvent {
        public final CustomerId customerId;
        // Revision = 3 (overridden)
    }
}

// No annotation = EventRevision.FIRST (1)
public class ProductAdded {
    // Revision = 1 (default)
}
```

**How it works**:
1. Annotate event classes with `@Revision(n)`
2. EventStore reads annotation automatically
3. No annotation → defaults to `EventRevision.FIRST` (1)
4. Nested classes inherit parent revision (via `@Inherited`)
5. Override in subclass if needed

---

## Utilities

Package: `dk.trustworks.essentials.components.foundation.types`

### RandomIdGenerator

```java
// API
static String generate()

// Usage
String randomId = RandomIdGenerator.generate();
// Returns UUID string: "550e8400-e29b-41d4-a716-446655440000"

CorrelationId id = CorrelationId.of(RandomIdGenerator.generate());
```

**Implementation**:
- Default: `UUID.randomUUID().toString()`
- If `com.fasterxml.uuid.Generators` on classpath: `Generators.timeBasedGenerator().generate().toString()`
- Override: `RandomIdGenerator.overrideRandomIdGenerator(...)`

---

## Security

### ⚠️ CRITICAL: SQL Injection Risk

Components allow customization of table/column/index/function names used via **string concatenation** → SQL injection risk.

Validation is applied but is **NOT exhaustive protection**.

### AggregateType SQL Injection

When using `AggregateType` with `SeparateTablePerAggregateTypePersistenceStrategy`, value is converted to table name and concatenated into SQL.

**Exposes EventStore to SQL injection.**

#### Validation Layer

`PostgresqlUtil.checkIsValidTableOrColumnName(String)` validates:
- Start: letter (A-Z) or underscore
- Subsequent: letters, digits, underscores
- No SQL/PostgreSQL reserved keywords
- Max 63 characters

```java
// Examples
PostgresqlUtil.checkIsValidTableOrColumnName("Orders");     // ✅ OK
PostgresqlUtil.checkIsValidTableOrColumnName("SELECT");     // ❌ Throws
PostgresqlUtil.checkIsValidTableOrColumnName("123_table"); // ❌ Throws
PostgresqlUtil.checkIsValidTableOrColumnName("drop; --");  // ❌ Throws
```

**⚠️ This validation is NOT exhaustive.**

#### Developer Responsibilities

**MUST**:
1. **NEVER** use user input directly for `AggregateType`
2. Derive only from **trusted sources**:
   - Hard-coded constants
   - Enum values
   - Pre-validated whitelist
3. Implement **additional sanitization**

**Safe patterns**:

```java
// ✅ SAFE - Hard-coded
AggregateType ORDER = AggregateType.of("Orders");

// ✅ SAFE - Enum-based
public enum AggregateTypes {
    ORDER("Orders"),
    CUSTOMER("Customers");

    private final AggregateType type;
    AggregateTypes(String name) { this.type = AggregateType.of(name); }
    public AggregateType getType() { return type; }
}

// ✅ SAFE - Whitelist
private static final Set<String> ALLOWED = Set.of("Orders", "Customers");

public AggregateType create(String input) {
    if (!ALLOWED.contains(input)) {
        throw new ValidationException("Invalid: " + input);
    }
    return AggregateType.of(input);
}

// ❌ DANGEROUS - User input
@PostMapping("/events/{aggregateType}")
public void append(@PathVariable String aggregateType, @RequestBody Event event) {
    // SQL INJECTION RISK!
    eventStore.appendToStream(AggregateType.of(aggregateType), ...);
}
```

See [postgresql-event-store README Security](../components/postgresql-event-store/README.md#security) for full details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## Integration with Types Module

All foundation types extend base classes from [types](./LLM-types.md):

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
```

**Benefit**: Inherits all `SingleValueType` features:
- Jackson serialization ([types-jackson](./LLM-types-jackson.md))
- Spring Data MongoDB ([types-springdata-mongo](./LLM-types-springdata-mongo.md))
- JDBI arguments ([types-jdbi](./LLM-types-jdbi.md))
- Spring Web converters ([types-spring-web](./LLM-types-spring-web.md))

---

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>foundation-types</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Note**: Usually transitively included by `foundation`, `postgresql-event-store`, or `eventsourced-aggregates`.

---

## Gotchas

- ⚠️ `AggregateType` has SQL injection risk - **NEVER** use user input
- ⚠️ `AggregateType` use plural names (Orders, Customers) vs implementation classes (Order, Customer)
- ⚠️ `EventOrder` starts at 0, `GlobalEventOrder` starts at 1 - different conventions
- ⚠️ `EventOrder` per aggregate instance, `GlobalEventOrder` per aggregate type - different scopes
- ⚠️ `EventTypeOrName` requires pattern matching - check `hasEventType()`/`hasEventName()` first
- ⚠️ Typed Events (FQCN + auto-deserialize) vs Named Events (string + raw JSON)
- ⚠️ `@Revision` is annotation, `EventRevision` is type - don't confuse
- ⚠️ `@Revision` defaults to 1, inheritable via `@Inherited`
- ⚠️ `EventRevision` is `IntegerType` not `LongType`
- ⚠️ `SubscriberId` must be unique per consumer
- ⚠️ `RandomIdGenerator` uses UUID - not sequential, not sortable
- ⚠️ All types immutable - operations return new instances

---

## See Also

- [README](../components/foundation-types/README.md) - Full documentation with examples
- [LLM-types.md](./LLM-types.md) - Base `SingleValueType` patterns
- [LLM-foundation.md](./LLM-foundation.md) - Foundation components using these types
- [LLM-postgresql-event-store.md](./LLM-postgresql-event-store.md) - Event Store implementation
- [LLM-eventsourced-aggregates.md](./LLM-eventsourced-aggregates.md) - Event-sourced aggregates
