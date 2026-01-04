# Types-SpringData-Mongo - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.types.springdata.mongo`
- Purpose: Spring Data MongoDB persistence for `SingleValueType` implementations
- Dependencies: `spring-data-mongodb` (provided scope)
- Status: WORK-IN-PROGRESS

## TOC
- [Core Classes](#core-classes)
- [API Patterns](#api-patterns)
- [Common Tasks](#common-tasks)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Gotchas](#gotchas)
- [See Also](#see-also)

## Core Classes

| Class (`dk.trustworks.essentials.types.springdata.mongo`) | Interface | Purpose |
|-----------------------------------------------------------|-----------|---------|
| `SingleValueTypeConverter`                                | `GenericConverter` | Converts `SingleValueType` ↔ MongoDB types |
| `SingleValueTypeRandomIdGenerator`                        | `BeforeConvertCallback<Object>` | Auto-generates `@Id` fields via `random()` method |

### SingleValueTypeConverter

**Package:** `dk.trustworks.essentials.types.springdata.mongo.SingleValueTypeConverter`

**Constructors:**
```java
SingleValueTypeConverter()
SingleValueTypeConverter(Class<? extends CharSequenceType<?>>... explicitCharSequenceTypeToObjectIdConverters)
SingleValueTypeConverter(List<Class<? extends CharSequenceType<?>>> explicitCharSequenceTypeToObjectIdConverters)
```

**Supported Conversions:**

| SingleValueType (`dk.trustworks.essentials.types`)             | MongoDB Type | Notes |
|------------------------------|--------------|-------|
| `CharSequenceType`           | `String` | Basic conversion |
| `CharSequenceType` (ObjectId) | `ObjectId` | Requires explicit registration |
| `NumberType`                 | `Number` / `Decimal128` | Auto-handles Decimal128 |
| `InstantType`                | `Date` | UTC timezone |
| `LocalDateTimeType`          | `Date` | UTC timezone |
| `LocalDateType`              | `Date` | UTC timezone |
| `LocalTimeType`              | `Date` | UTC timezone |

**Not Supported:** `OffsetDateTimeType`, `ZonedDateTimeType` (Spring Data MongoDB limitation)

### SingleValueTypeRandomIdGenerator

**Package:** `dk.trustworks.essentials.types.springdata.mongo.SingleValueTypeRandomIdGenerator`

**Behavior:**
1. Finds `@Id` field of type `SingleValueType`
2. If field value is `null`, invokes static `random()` method
3. Sets generated value on entity

**Requirements:**
- ID type must have `static random()` method
- Registered as Spring bean

## API Patterns

### Pattern: Basic Registration

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

### Pattern: ObjectId Map Key Registration

When `CharSequenceType` used as Map key contains `ObjectId` values:

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(ProductId.class, OrderItemId.class)));
}
```

**Example entity:**
```java
@Document
public class Order {
    @Id public OrderId id;
    public Map<ProductId, Quantity> orderLines; // ProductId contains ObjectId
}
```

### Pattern: Codec Configuration

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

## Common Tasks

### Task: Define Entity with Semantic Types

```java
@Document
public class Order {
    @Id public OrderId id;               // Auto-generated via random()
    public CustomerId customerId;
    public Amount totalPrice;
    public Created createdAt;            // LocalDateTimeType
}
```

### Task: Create Repository

```java
public interface OrderRepository extends MongoRepository<Order, OrderId> {
    List<Order> findByCustomerId(CustomerId customerId);
}
```

### Task: Create ID Type with random()

CharSequenceType-based (for ObjectId strings):
```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }

    public static OrderId random() {
        return new OrderId(ObjectId.get().toString());
    }
}
```

## JSR-310 Temporal Types

### Supported Types

| Type Extends (`dk.trustworks.essentials.types`)| Wrapped Value | MongoDB Type | Timezone |
|--------------|---------------|--------------|----------|
| `InstantType` | `Instant` | `Date` | UTC |
| `LocalDateTimeType` | `LocalDateTime` | `Date` | UTC |
| `LocalDateType` | `LocalDate` | `Date` | UTC |
| `LocalTimeType` | `LocalTime` | `Date` | UTC |

### Example: InstantType

```java
public class LastUpdated extends InstantType<LastUpdated> {
    public LastUpdated(Instant value) { super(value); }

    public static LastUpdated of(Instant value) {
        return new LastUpdated(value);
    }

    public static LastUpdated now() {
        // Use UTC, strip nanos (MongoDB ISODate limitation)
        return new LastUpdated(
            Instant.now(Clock.systemUTC()).with(ChronoField.NANO_OF_SECOND, 0)
        );
    }
}
```

### Example: LocalDateTimeType

```java
public class Created extends LocalDateTimeType<Created> {
    public Created(LocalDateTime value) { super(value); }

    public static Created of(LocalDateTime value) {
        return new Created(value);
    }

    public static Created now() {
        return new Created(LocalDateTime.now(Clock.systemUTC()).withNano(0));
    }
}
```

## Gotchas

- ⚠️ **UTC timezone** - JSR-310 converters use `ZoneId.of("UTC")`; create instances with `Clock.systemUTC()`
- ⚠️ **No nanosecond precision** - MongoDB `ISODate` doesn't support nanos; use `.withNano(0)` or `.with(ChronoField.NANO_OF_SECOND, 0)`
- ⚠️ **OffsetDateTime/ZonedDateTime unsupported** - Spring Data MongoDB limitation; use `InstantType` or `LocalDateTimeType`
- ⚠️ **ObjectId Map keys** - `CharSequenceType` containing `ObjectId` as Map key requires explicit registration: `new SingleValueTypeConverter(YourType.class)`
- ⚠️ **random() method required** - `SingleValueTypeRandomIdGenerator` invokes static `random()` method via reflection
- ⚠️ **Decimal128 auto-conversion** - `BigDecimalType` values automatically converted from MongoDB `Decimal128`

## See Also

- [README.md](../types-springdata-mongo/README.md) - Full documentation with detailed explanations
- [LLM-types.md](LLM-types.md) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson serialization for types
- [OrderRepositoryIT.java](../types-springdata-mongo/src/test/java/dk/trustworks/essentials/types/springdata/mongo/OrderRepositoryIT.java) - Integration test examples
