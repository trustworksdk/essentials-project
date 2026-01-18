# Types-SpringData-Mongo - LLM Reference

> Token-efficient reference for LLMs. For detailed explanations, see [README.md](../types-springdata-mongo/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types.springdata.mongo`
- Purpose: Spring Data MongoDB persistence for `SingleValueType` implementations
- Dependencies: `spring-data-mongodb` (provided scope)
- Status: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-springdata-mongo</artifactId>
</dependency>
```

## TOC
- [Core Classes](#core-classes)
- [API Patterns](#api-patterns)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Gotchas](#gotchas)

## Core Classes

Base package: `dk.trustworks.essentials.types.springdata.mongo`

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, all temporal types from [types](./LLM-types.md)

| Class | Implements | Purpose |
|-------|-----------|---------|
| `SingleValueTypeConverter` | `GenericConverter` | Converts `SingleValueType` ↔ MongoDB types |
| `SingleValueTypeRandomIdGenerator` | `BeforeConvertCallback<Object>` | Auto-generates `@Id` fields via `random()` method |

### SingleValueTypeConverter

**Constructors:**
```java
SingleValueTypeConverter()
SingleValueTypeConverter(Class<? extends CharSequenceType<?>>... explicitCharSequenceTypeToObjectIdConverters)
SingleValueTypeConverter(List<Class<? extends CharSequenceType<?>>> explicitCharSequenceTypeToObjectIdConverters)
```

**Supported Conversions:**

| `SingleValueType` Subtype | MongoDB Type | Notes |
|---------------------------|--------------|-------|
| `CharSequenceType` | `String` | Default conversion |
| `CharSequenceType` (ObjectId values) | `ObjectId` | Requires explicit registration in constructor |
| `NumberType` | `Number` / `Decimal128` | Auto-handles Decimal128 |
| `InstantType` | `Date` | UTC timezone |
| `LocalDateTimeType` | `Date` | UTC timezone |
| `LocalDateType` | `Date` | UTC timezone |
| `LocalTimeType` | `Date` | UTC timezone |

**Not Supported:** `OffsetDateTimeType`, `ZonedDateTimeType` (Spring Data MongoDB limitation)

### SingleValueTypeRandomIdGenerator

**Method:**
```java
public Object onBeforeConvert(Object entity, String collection)
```

**Behavior:**
1. Finds `@Id` field of `SingleValueType` type
2. If field value is `null`, invokes static `random()` method via reflection
3. Sets generated value on entity
4. Returns entity

**Requirements:**
- ID type must have `static random()` method
- Must be registered as Spring bean

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

When `CharSequenceType` used as Map key contains `ObjectId` values, explicitly register for ObjectId conversion:

```java
@Bean
public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
        new SingleValueTypeConverter(ProductId.class, OrderItemId.class)));
}
```

**Entity example:**
```java
@Document
public class Order {
    @Id public OrderId id;
    public Map<ProductId, Quantity> orderLines; // ProductId contains ObjectId
}
```

**ID type example:**
```java
public class ProductId extends CharSequenceType<ProductId> implements Identifier {
    public ProductId(CharSequence value) { super(value); }
    public ProductId(String value) { super(value); }

    public static ProductId of(CharSequence value) {
        return new ProductId(value);
    }

    public static ProductId random() {
        return new ProductId(ObjectId.get().toString());
    }
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

### Pattern: Define Entity with Semantic Types

```java
@Document
public class Order {
    @Id public OrderId id;               // Auto-generated via random()
    public CustomerId customerId;
    public Amount totalPrice;
    public Created createdAt;            // LocalDateTimeType
}
```

### Pattern: Create Repository

```java
public interface OrderRepository extends MongoRepository<Order, OrderId> {
    List<Order> findByCustomerId(CustomerId customerId);
}
```

## JSR-310 Temporal Types

### Supported Types

Base package: `dk.trustworks.essentials.types`

| Type Extends | Wrapped Value | MongoDB Type | Timezone |
|--------------|---------------|--------------|----------|
| `InstantType` | `Instant` | `Date` | UTC |
| `LocalDateTimeType` | `LocalDateTime` | `Date` | UTC |
| `LocalDateType` | `LocalDate` | `Date` | UTC |
| `LocalTimeType` | `LocalTime` | `Date` | UTC |

**Not Supported:** `OffsetDateTimeType`, `ZonedDateTimeType`

### Pattern: InstantType

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

### Pattern: LocalDateTimeType

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
- ⚠️ **Decimal128 auto-conversion** - `NumberType` values automatically converted from MongoDB `Decimal128`

## See Also

- [README.md](../types-springdata-mongo/README.md) - Full documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson serialization
- [OrderRepositoryIT.java](../types-springdata-mongo/src/test/java/dk/trustworks/essentials/types/springdata/mongo/OrderRepositoryIT.java) - Integration test examples
