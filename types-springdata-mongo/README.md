# Types-SpringData-Mongo

> Spring Data MongoDB persistence support for Essentials `types` module

This module enables seamless MongoDB persistence of `SingleValueType` implementations, including automatic ID generation for entities with `SingleValueType` IDs.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types-springdata-mongo.md](../LLM/LLM-types-springdata-mongo.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [SingleValueTypeConverter](#singlevaluetypeconverter)
- [SingleValueTypeRandomIdGenerator](#singlevaluetyperandomidgenerator)
- [ObjectId Map Keys](#objectid-map-keys)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Gotchas](#gotchas)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-springdata-mongo</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-mongodb</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.types.springdata.mongo`

**1. Register Spring beans:**

```java
@Configuration
public class MongoConfig {

    @Bean
    public SingleValueTypeRandomIdGenerator singleValueTypeIdGenerator() {
        return new SingleValueTypeRandomIdGenerator();
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new SingleValueTypeConverter()));
    }
}
```

**2. Define entities with semantic types:**

```java
@Document
public class Order {
    @Id
    public OrderId id;
    public CustomerId customerId;
    public Amount totalPrice;
    public Created createdAt;
}
```

**3. Use repositories as normal:**

```java
public interface OrderRepository extends MongoRepository<Order, OrderId> {
    // Spring Data auto-implements query methods based on naming convention
    List<Order> findByCustomerId(CustomerId customerId);
}
```

**Learn more:** See [OrderRepositoryIT.java](src/test/java/dk/trustworks/essentials/types/springdata/mongo/OrderRepositoryIT.java)

##SingleValueTypeConverter

Converts `SingleValueType` instances to/from MongoDB-compatible types.

### Supported Conversions

| SingleValueType | MongoDB Type |
|-----------------|--------------|
| `CharSequenceType` | `String` |
| `NumberType` | `Number` / `Decimal128` |
| `InstantType` | `Date` |
| `LocalDateTimeType` | `Date` |
| `LocalDateType` | `Date` |
| `LocalTimeType` | `Date` |

### Basic Usage

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(new SingleValueTypeConverter()));
}
```

## SingleValueTypeRandomIdGenerator

Automatically generates IDs for entities with `@Id` fields of `SingleValueType` type.

**How it works:**
1. Checks if entity has an `@Id` field of `SingleValueType` type
2. If the field is `null`, calls the static `random()` method on the type
3. Sets the generated ID on the entity before it's saved

**Requirements:**
- The `SingleValueType` subclass must have a static `random()` method
- Register the generator as a Spring bean

```java
@Bean
public SingleValueTypeRandomIdGenerator singleValueTypeIdGenerator() {
    return new SingleValueTypeRandomIdGenerator();
}
```

**Example ID type with `random()` method** (CharSequenceType-based, suitable for string IDs like ObjectId):

```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) {
        super(value);
    }

    public OrderId(String value) {
        super(value);
    }
    
    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }

    public static OrderId random() {
        return new OrderId(ObjectId.get().toString());
    }
}
```

## ObjectId Map Keys

When using a `CharSequenceType` as a Map `key` that contains `ObjectId` values, you must explicitly register it for `ObjectId` conversion.

**Example entity with Map:**

```java
@Document
public class Order {
    @Id
    public OrderId id;
    public CustomerId customerId;
    public Map<ProductId, Quantity> orderLines;
}
```

**ProductId that uses ObjectId:**

```java
public class ProductId extends CharSequenceType<ProductId> implements Identifier {
    public ProductId(CharSequence value) {
        super(value);
    }

    public ProductId(String value) {
        super(value);
    }
    
    public static ProductId of(CharSequence value) {
        return new ProductId(value);
    }

    public static ProductId random() {
        return new ProductId(ObjectId.get().toString());
    }
}
```

**Register for ObjectId conversion:**

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(ProductId.class)));
}
```

**Multiple types:**

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(ProductId.class, OrderItemId.class, VariantId.class)));
}
```

## JSR-310 Temporal Types

The converter supports the following `JSR310SingleValueType` subtypes:

| Your Type Extends | Wrapped Value | MongoDB Type |
|-------------------|---------------|--------------|
| `InstantType` | `Instant` | `Date` |
| `LocalDateTimeType` | `LocalDateTime` | `Date` |
| `LocalDateType` | `LocalDate` | `Date` |
| `LocalTimeType` | `LocalTime` | `Date` |

**Note:** `OffsetDateTimeType` and `ZonedDateTimeType` are **not supported** because Spring Data MongoDB doesn't support `OffsetDateTime` and `ZonedDateTime` conversion.

### Codec Compatibility

Works with both Native Driver and Spring Data Java Time Codecs:

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return MongoCustomConversions.create(config -> {
        config.useNativeDriverJavaTimeCodecs();
        // Or: config.useSpringDataJavaTimeCodecs(); (default)
        config.registerConverters(List.of(
                new SingleValueTypeConverter(ProductId.class)));
    });
}
```

### Example: InstantType

```java
public class LastUpdated extends InstantType<LastUpdated> {
    public LastUpdated(Instant value) {
        super(value);
    }

    public static LastUpdated of(Instant value) {
        return new LastUpdated(value);
    }

    public static LastUpdated now() {
        return new LastUpdated(Instant.now(Clock.systemUTC()).with(ChronoField.NANO_OF_SECOND, 0));
    }
}
```

### Example: LocalDateTimeType

```java
public class Created extends LocalDateTimeType<Created> {
    public Created(LocalDateTime value) {
        super(value);
    }

    public static Created of(LocalDateTime value) {
        return new Created(value);
    }

    public static Created now() {
        return new Created(LocalDateTime.now(Clock.systemUTC()).withNano(0));
    }
}
```

## Gotchas

- **UTC timezone** - JSR-310 converters use `ZoneId.of("UTC")` since MongoDB's `ISODate` is UTC-based. Use `Clock.systemUTC()` when creating instances.

- **No nanosecond precision** - MongoDB's `ISODate` doesn't support nanoseconds. Use `.withNano(0)` or `.with(ChronoField.NANO_OF_SECOND, 0)`.

- **OffsetDateTime/ZonedDateTime not supported** - Spring Data MongoDB doesn't support these types. Use `InstantType` or `LocalDateTimeType` instead.

- **ObjectId Map keys** - If a `CharSequenceType` contains `ObjectId` values and is used as a Map key, you must explicitly register it with `new SingleValueTypeConverter(YourType.class)`.

- **ID generation requires `random()` method** - The `SingleValueTypeRandomIdGenerator` requires a static `random()` method on your ID type.

- **Decimal128 handling** - `BigDecimalType` values are automatically converted from MongoDB's `Decimal128` type.

## See Also

- [LLM-types-springdata-mongo.md](../LLM/LLM-types-springdata-mongo.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson](../types-jackson) - Jackson serialization for types
