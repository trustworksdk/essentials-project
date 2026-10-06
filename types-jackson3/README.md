# Types-Jackson

> Jackson serialization/deserialization support for Essentials `types` module

This module enables automatic JSON serialization and deserialization for all `SingleValueType` implementations using [Jackson 3](https://github.com/FasterXML/jackson) (`tools.jackson`).

From Essentials 0.60 Jackson 3 is the only supported Jackson major; the Jackson 2 `types-jackson` module has been
removed. `types-jackson3` uses the same package and class names, so upgrading from 0.50 means swapping the
`types-jackson` artifactId for `types-jackson3` and moving your own imports from `com.fasterxml.jackson.databind` to
`tools.jackson.databind` (`com.fasterxml.jackson.annotation` stays, Jackson 3 shares it).

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
    <artifactId>types-jackson3</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

`Optional` and `java.time` support is built into Jackson 3's databind — no separate `jdk8`/`jsr310` datatype modules
are needed.

## Quick Start

Base package: `dk.trustworks.essentials.jackson.types`  

Register `EssentialTypesJacksonModule` when building your mapper (Jackson 3 mappers are immutable, so modules are
added on the builder):

```java
ObjectMapper objectMapper = JsonMapper.builder()
                                      .addModule(new EssentialTypesJacksonModule())
                                      .build();
```

Or use the convenience factory with opinionated defaults:

```java
ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();
```

In a Spring Boot 4 application, declare `EssentialTypesJacksonModule` as a `@Bean` and Boot registers it on its web
`JsonMapper`. For **persisted** JSON (event store, queues, inbox/outbox) don't build your own mapper — use
`EssentialsObjectMappers` / the Essentials Spring Boot starters, whose mapper configuration is the persisted-format
contract.

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

**Learn more:** See [EssentialTypesJacksonModuleTest.java](src/test/java/dk/trustworks/essentials/jackson/EssentialTypesJacksonModuleTest.java)

## Supported Types

| Type Category | Serialized As | Example |
|---------------|---------------|---------|
| `CharSequenceType` | JSON string | `"ORD-123"` |
| `NumberType` (`IntegerType`, `LongType`, `BigDecimalType`, etc.) | JSON number | `99.99` |
| `Money` | JSON object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` | ISO-8601 string | `"2024-01-15T10:30:00Z"` |

## CharSequenceType Requirements

A `CharSequenceType` subclass needs only its `CharSequence` constructor:

```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) {
        super(value);
    }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }
}
```

`SingleValueTypeCreatorIntrospector` (registered by the module) pins the single-argument constructor of every
`SingleValueType` as a delegating creator, so a value type is always read from the bare JSON scalar. The extra
`String` constructor that Jackson 2.18+ required (Essentials 0.50 and earlier) is no longer needed; existing ones
can stay.

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

`SingleValueType` map keys work in both directions with no annotation: the module registers
`SingleValueTypeKeyDeserializers`, which turns a JSON object key back into the declared `SingleValueType`.

```java
public class Order {
    public OrderId id;
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

An explicit `@JsonDeserialize(keyUsing = …)` from `tools.jackson.databind.annotation` still takes precedence.

> **Upgrading from 0.50 (Jackson 2):** a `@JsonDeserialize(keyUsing = …)` imported from Jackson 2's
> `com.fasterxml.jackson.databind.annotation` package is **silently ignored** by Jackson 3. With this module it is
> no longer needed, so remove it (or switch the import to `tools.jackson.databind.annotation`).

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
    new EssentialsImmutableJacksonModule()   // any additional tools.jackson.databind.JacksonModule
);
```

## See Also

- [LLM-types-jackson.md](../LLM/LLM-types-jackson.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [immutable-jackson3](../immutable-jackson3) - Jackson support for immutable value objects
