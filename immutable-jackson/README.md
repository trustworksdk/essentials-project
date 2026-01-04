# Immutable-Jackson

> Jackson deserialization support for immutable classes without matching constructors

This module enables Jackson to deserialize immutable classes (including Java Records) that don't have constructors matching all JSON fields. It uses reflection to set field values directly, even on `final` fields, with Objenesis as a fallback for object instantiation.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-immutable-jackson.md](../LLM/LLM-immutable-jackson.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [How It Works](#how-it-works)
- [Use Cases](#use-cases)
- [ObjectMapper Factory](#objectmapper-factory)
- [Gotchas](#gotchas)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>immutable-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
    <groupId>org.objenesis</groupId>
    <artifactId>objenesis</artifactId>
</dependency>
```

**Recommended dependencies:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jdk8</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.jackson.immutable`

Register `EssentialsImmutableJacksonModule` with your `ObjectMapper`:

```java
ObjectMapper objectMapper = new ObjectMapper();
objectMapper.registerModule(new EssentialsImmutableJacksonModule());
```

Or use the convenience factory with opinionated defaults:

```java
ObjectMapper objectMapper = EssentialsImmutableJacksonModule.createObjectMapper(
    new EssentialTypesJacksonModule(),
    new Jdk8Module(),
    new JavaTimeModule()
);
```

**Example - Deserialize immutable class:**

```java
public final class ImmutableOrder {
    public final OrderId       orderId;
    public final CustomerId    customerId;
    public final Money         totalPrice;
    public final LocalDateTime orderedTimestamp;

    // Constructor doesn't accept orderedTimestamp - it's set automatically
    public ImmutableOrder(OrderId orderId, CustomerId customerId, Money totalPrice) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.totalPrice = totalPrice;
        this.orderedTimestamp = LocalDateTime.now();
    }
}

// JSON includes orderedTimestamp even though constructor doesn't accept it
String json = """
    {
        "orderId": "ORD-123",
        "customerId": "CUST-456",
        "totalPrice": 99.99,
        "orderedTimestamp": "2024-01-15T10:30:00"
    }
    """;

// Deserialization works - field set via reflection
ImmutableOrder order = objectMapper.readValue(json, ImmutableOrder.class);
```

**Learn more:** See [EssentialsImmutableJacksonModuleTest.java](src/test/java/dk/trustworks/essentials/jackson/immutable/EssentialsImmutableJacksonModuleTest.java)

## How It Works

The module uses a two-step instantiation strategy:

| Step | Method | When Used |
|------|--------|-----------|
| 1 | Standard Jackson `ValueInstantiator` | If Jackson can find a suitable constructor |
| 2 | Objenesis | Fallback when no suitable constructor exists |

After instantiation, field values are set directly via reflection, even for `final` fields.

**Critical Warning:** When Objenesis creates the instance:
- **NO constructor is called**
- **NO field default values are set by Java**
- Fields only have values explicitly set from JSON

## Use Cases

### Immutable Classes with Computed Fields

```java
public class Order {
    public final OrderId id;
    public final List<OrderLine> lines;
    public final Money total;  // Computed in constructor, but can be overridden from JSON

    public Order(OrderId id, List<OrderLine> lines) {
        this.id = id;
        this.lines = List.copyOf(lines);
        this.total = lines.stream()
            .map(OrderLine::lineTotal)
            .reduce(Money.ZERO, Money::add);
    }
}
```

### Java Records

```java
public record CustomerInfo(
    CustomerId id,
    String name,
    EmailAddress email,
    OffsetDateTime registeredAt
) {}

// Records work seamlessly
String json = objectMapper.writeValueAsString(customer);
CustomerInfo deserialized = objectMapper.readValue(json, CustomerInfo.class);
```

### Classes with Validation in Constructor

```java
public class ValidatedOrder {
    public final OrderId orderId;
    public final Money amount;

    public ValidatedOrder(OrderId orderId, Money amount) {
        this.orderId = FailFast.requireNonNull(orderId, "orderId required");
        this.amount = FailFast.requireNonNull(amount, "amount required");
        FailFast.requireTrue(amount.isPositive(), "amount must be positive");
    }
}
```

**Note:** If Objenesis is used (constructor bypassed), validation won't run during deserialization.

## ObjectMapper Factory

`EssentialsImmutableJacksonModule.createObjectMapper()` provides the same opinionated configuration as `EssentialTypesJacksonModule.createObjectMapper()`:

| Setting | Value | Purpose |
|---------|-------|---------|
| Field visibility | `ANY` | Serialize all fields regardless of access modifier |
| Getter/setter detection | `NONE` | Use fields only, not getters/setters |
| Unknown properties | Ignored | Don't fail on extra JSON fields |
| Empty beans | Allowed | Serialize objects with no properties |
| Transient marker | Propagated | Respect `transient` keyword |
| Date format | ISO-8601 | Timestamps as strings, not numbers |

**Combine with other modules:**

```java
ObjectMapper objectMapper = EssentialsImmutableJacksonModule.createObjectMapper(
    new EssentialTypesJacksonModule(),  // SingleValueType support
    new Jdk8Module(),                   // Optional support
    new JavaTimeModule()                // java.time support
);
```

## Gotchas

### Objenesis Bypasses Constructors

When Objenesis instantiates an object, constructors are **not called**:

```java
public class Counter {
    public final int count;
    private static int instanceCount = 0;

    public Counter(int count) {
        this.count = count;
        instanceCount++;  // NOT incremented when Objenesis creates instance
    }
}
```

### Field Defaults Not Set

Java field initializers don't run when Objenesis creates the instance:

```java
public class Config {
    public final String name;
    public final int timeout = 30;  // Default NOT applied with Objenesis

    public Config(String name) {
        this.name = name;
        // timeout remains 0 (not 30) if Objenesis creates instance
        // and JSON doesn't include "timeout"
    }
}
```

### Requires Field-Based Serialization

The module requires field-based serialization (not getter/setter). Use `createObjectMapper()` or configure manually:

```java
objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
objectMapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
objectMapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
```

### Private Fields Work

Unlike standard Jackson, private final fields can be deserialized:

```java
public class PrivateFields {
    private final String secret;  // Works with this module

    public PrivateFields(String secret) {
        this.secret = secret;
    }
}
```

## See Also

- [LLM-immutable-jackson.md](../LLM/LLM-immutable-jackson.md) - API reference for LLM assistance
- [types-jackson](../types-jackson) - Jackson support for `SingleValueType`
- [immutable](../immutable) - Core immutable value object patterns
