# Types-Jackson

> Jackson serialization/deserialization support for Essentials `types` module

This module enables automatic JSON serialization and deserialization for all `SingleValueType` implementations using [Jackson (FasterXML)](https://github.com/FasterXML/jackson).

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types-jackson.md](../LLM/LLM-types-jackson.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Supported Types](#supported-types)
- [CharSequenceType Requirements](#charsequencetype-requirements)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Map Key Deserialization](#map-key-deserialization)
- [ObjectMapper Factory](#objectmapper-factory)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

**Recommended dependencies** for full functionality:
```xml
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

Base package: `dk.trustworks.essentials.jackson.types`  

Register `EssentialTypesJacksonModule` with your `ObjectMapper`:

```java
ObjectMapper objectMapper = new ObjectMapper();
objectMapper.registerModule(new EssentialTypesJacksonModule());
```

Or use the convenience factory with opinionated defaults:

```java
ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper(
    new Jdk8Module(),
    new JavaTimeModule()
);
```

**Serialization just works:**

```java
public record Order(OrderId id, CustomerId customerId, Amount total) {}

Order order = new Order(
    OrderId.of("ORD-123"),
    CustomerId.of("CUST-456"),
    Amount.of("99.99")
);

String json = objectMapper.writeValueAsString(order);
// {"id":"ORD-123","customerId":"CUST-456","total":99.99}

Order deserialized = objectMapper.readValue(json, Order.class);
```

**Note:** For Jackson 2.18+, ensure your `CharSequenceType` subclasses have both `CharSequence` and `String` constructors. See [CharSequenceType Requirements](#charsequencetype-requirements).

**Learn more:** See [EssentialTypesJacksonModuleTest.java](src/test/java/dk/trustworks/essentials/jackson/EssentialTypesJacksonModuleTest.java)

## Supported Types

| Type Category | Serialized As | Example |
|---------------|---------------|---------|
| `CharSequenceType` | JSON string | `"ORD-123"` |
| `NumberType` (`IntegerType`, `LongType`, `BigDecimalType`, etc.) | JSON number | `99.99` |
| `Money` | JSON object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` | ISO-8601 string | `"2024-01-15T10:30:00Z"` |

## CharSequenceType Requirements

For Jackson 2.18+ compatibility, `CharSequenceType` subclasses must provide **two constructors**:

```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    // Required: CharSequence constructor
    public OrderId(CharSequence value) {
        super(value);
    }

    // Required for Jackson 2.18+: String constructor
    public OrderId(String value) {
        super(value);
    }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }
}
```

**Why both constructors?** Jackson 2.18+ changed how it handles `CharSequence` parameters. The `String` constructor ensures reliable deserialization.

## JSR-310 Temporal Types

For temporal types extending `JSR310SingleValueType`, add `@JsonCreator` to the constructor:

```java
public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    @JsonCreator
    public TransactionTime(ZonedDateTime value) {
        super(value);
    }

    public static TransactionTime now() {
        return new TransactionTime(ZonedDateTime.now(ZoneId.of("UTC")));
    }
}
```

**Supported JSR-310 base types:**

| Base Type | Wrapped Value |
|-----------|---------------|
| `InstantType` | `Instant` |
| `LocalDateTimeType` | `LocalDateTime` |
| `LocalDateType` | `LocalDate` |
| `LocalTimeType` | `LocalTime` |
| `OffsetDateTimeType` | `OffsetDateTime` |
| `ZonedDateTimeType` | `ZonedDateTime` |

## Map Key Deserialization

Serialization of `SingleValueType` as Map keys works automatically. For **deserialization**, create a custom `KeyDeserializer`:

```java
public class ProductIdKeyDeserializer extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt) {
        return ProductId.of(key);
    }
}
```

Annotate the field:

```java
public class Order {
    public OrderId id;

    @JsonDeserialize(keyUsing = ProductIdKeyDeserializer.class)
    public Map<ProductId, Quantity> orderLines;
}
```

**JSON representation:**
```json
{
  "id": "ORD-123",
  "orderLines": {
    "PROD-001": 2,
    "PROD-002": 1
  }
}
```

## ObjectMapper Factory

`EssentialTypesJacksonModule.createObjectMapper()` provides an opinionated configuration:

| Setting | Value | Purpose |
|---------|-------|---------|
| Field visibility | `ANY` | Serialize all fields regardless of access modifier |
| Getter/setter detection | `NONE` | Use fields only, not getters/setters |
| Unknown properties | Ignored | Don't fail on extra JSON fields |
| Empty beans | Allowed | Serialize objects with no properties |
| Transient marker | Propagated | Respect `transient` keyword |
| Date format | ISO-8601 | Timestamps as strings, not numbers |

**Customize with additional modules:**

```java
ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper(
    new Jdk8Module(),           // Optional support
    new JavaTimeModule(),       // java.time support
    new ParameterNamesModule()  // Constructor parameter names
);
```

## See Also

- [LLM-types-jackson.md](../LLM/LLM-types-jackson.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [immutable-jackson](../immutable-jackson) - Jackson support for immutable value objects
