# Types-SpringData-JPA - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](../types-springdata-jpa/README.md).

## Quick Facts
- Base package: `dk.trustworks.essentials.types.springdata.jpa.converters`
- Purpose: JPA `AttributeConverter` base classes for `SingleValueType` persistence
- Dependencies: `spring-data-jpa`, `jakarta.persistence-api` (provided scope)
- Status: **EXPERIMENTAL** - may be discontinued
- Recommendation: Use [types-jdbi](LLM-types-jdbi.md) for SQL database persistence

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-springdata-jpa</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, all temporal types from [types](./LLM-types.md)

## Table of Contents
- [Limitations](#limitations)
- [Base AttributeConverters](#base-attributeconverters)
- [API Signatures](#api-signatures)
- [Common Tasks](#common-tasks)
- [Primary Key Handling](#primary-key-handling)
- [Built-in Converters](#built-in-converters)
- [Integration Points](#integration-points)
- [Gotchas](#gotchas)
- [Test References](#test-references)
- [See Also](#see-also)

## Limitations

| Limitation | Impact |
|------------|--------|
| No ID autogeneration | Must generate IDs manually (e.g., `OrderId.random()`) |
| `@Id` not supported | Must use `@EmbeddedId` + `@Embeddable` for PKs |
| One converter per type | Cannot use generic converter (unlike MongoDB) |
| Duplicate ID field | `@Embeddable` IDs need both `SingleValueType` value + persistent field |
| `@Embeddable` not reusable | Cannot use same type as `@EmbeddedId` AND regular property |

## Base AttributeConverters

Base package: `dk.trustworks.essentials.types.springdata.jpa.converters`

| SingleValueType | Base Converter | DB Type | Abstract Method |
|-----------------|----------------|---------|-----------------|
| `CharSequenceType` | `BaseCharSequenceTypeAttributeConverter<T>` | `String` | `getConcreteCharSequenceType()` |
| `BigDecimalType` | `BaseBigDecimalTypeAttributeConverter<T>` | `Double` | `getConcreteBigDecimalType()` |
| `IntegerType` | `BaseIntegerTypeAttributeConverter<T>` | `Integer` | `getConcreteIntegerType()` |
| `LongType` | `BaseLongTypeAttributeConverter<T>` | `Long` | `getConcreteLongType()` |
| `ShortType` | `BaseShortTypeAttributeConverter<T>` | `Short` | `getConcreteShortType()` |
| `ByteType` | `BaseByteTypeAttributeConverter<T>` | `Byte` | `getConcreteByteType()` |
| `DoubleType` | `BaseDoubleTypeAttributeConverter<T>` | `Double` | `getConcreteDoubleType()` |
| `FloatType` | `BaseFloatTypeAttributeConverter<T>` | `Float` | `getConcreteFloatType()` |
| `InstantType` | `BaseInstantTypeAttributeConverter<T>` | `Instant` | `getConcreteInstantType()` |
| `LocalDateTimeType` | `BaseLocalDateTimeTypeAttributeConverter<T>` | `LocalDateTime` | `getConcreteLocalDateTimeType()` |
| `LocalDateType` | `BaseLocalDateTypeAttributeConverter<T>` | `LocalDate` | `getConcreteLocalDateType()` |
| `LocalTimeType` | `BaseLocalTimeTypeAttributeConverter<T>` | `LocalTime` | `getConcreteLocalTimeType()` |
| `OffsetDateTimeType` | `BaseOffsetDateTimeTypeAttributeConverter<T>` | `OffsetDateTime` | `getConcreteOffsetDateTimeType()` |
| `ZonedDateTimeType` | `BaseZonedDateTimeTypeAttributeConverter<T>` | `ZonedDateTime` | `getConcreteZonedDateTimeType()` |

All `SingleValueType` classes from package: `dk.trustworks.essentials.types`

## API Signatures

All base converters implement `jakarta.persistence.AttributeConverter<T, DB_TYPE>`.

### Example: BaseCharSequenceTypeAttributeConverter
```java
package dk.trustworks.essentials.types.springdata.jpa.converters;

public abstract class BaseCharSequenceTypeAttributeConverter<T>
    implements AttributeConverter<T, String> {

    String convertToDatabaseColumn(T attribute);
    T convertToEntityAttribute(String dbData);
    protected abstract Class<T> getConcreteCharSequenceType();
}
```

### Example: BaseLongTypeAttributeConverter
```java
package dk.trustworks.essentials.types.springdata.jpa.converters;

public abstract class BaseLongTypeAttributeConverter<T>
    implements AttributeConverter<T, Long> {

    Long convertToDatabaseColumn(T attribute);
    T convertToEntityAttribute(Long dbData);
    protected abstract Class<T> getConcreteLongType();
}
```

Pattern applies to all base converters: replace type-specific method name and DB type.

## Common Tasks

### Task: Create CharSequenceType Converter
```java
import dk.trustworks.essentials.types.springdata.jpa.converters.BaseCharSequenceTypeAttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CustomerIdAttributeConverter extends BaseCharSequenceTypeAttributeConverter<CustomerId> {
    @Override
    protected Class<CustomerId> getConcreteCharSequenceType() {
        return CustomerId.class;
    }
}
```

### Task: Create LongType Converter
```java
@Converter(autoApply = true)
public class AccountIdAttributeConverter extends BaseLongTypeAttributeConverter<AccountId> {
    @Override
    protected Class<AccountId> getConcreteLongType() {
        return AccountId.class;
    }
}
```

### Task: Create InstantType Converter
```java
@Converter(autoApply = true)
public class LastUpdatedAttributeConverter extends BaseInstantTypeAttributeConverter<LastUpdated> {
    @Override
    protected Class<LastUpdated> getConcreteInstantType() {
        return LastUpdated.class;
    }
}
```

### Task: Use in Entity (Non-ID Fields)
```java
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;           // See Primary Key Handling
    public CustomerId customerId; // Auto-converted via @Converter(autoApply=true)
    public AccountId accountId;   // Auto-converted
    public Amount totalPrice;     // Auto-converted (built-in)
}
```

### Task: Use in Repository
```java
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OrderRepository extends JpaRepository<Order, OrderId> {
    List<Order> findByCustomerId(CustomerId customerId);
    Optional<Order> findByAccountId(AccountId accountId);
}
```

## Primary Key Handling

### Entity with @EmbeddedId
```java
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;  // Must use @EmbeddedId, NOT @Id
    // ... other fields
}
```

### @Embeddable ID Requirements
Required elements:
1. `@Embeddable` annotation
2. Separate persistent field (`private Long orderId`)
3. No-arg constructor with temp value (`-1L`)
4. Update both `super()` and persistent field in constructor

**CRITICAL**: Cannot use same type as `@EmbeddedId` AND regular property (causes dual-column mapping).

```java
import dk.trustworks.essentials.types.LongType;
import dk.trustworks.essentials.types.Identifier;
import jakarta.persistence.Embeddable;
import java.util.Random;

@Embeddable
public class OrderId extends LongType<OrderId> implements Identifier {
    private static final Random RANDOM = new Random();

    // JPA persistent field
    private Long orderId;

    // JPA no-arg constructor
    protected OrderId() {
        super(-1L);
    }

    public OrderId(Long value) {
        super(value);
        orderId = value;  // Update both!
    }

    public static OrderId of(Long value) {
        return new OrderId(value);
    }

    public static OrderId random() {
        return new OrderId(RANDOM.nextLong());
    }
}
```

## Built-in Converters

Package: `dk.trustworks.essentials.types.springdata.jpa.converters`

| Type | Converter |
|------|-----------|
| `Amount` | `AmountAttributeConverter` |
| `Percentage` | `PercentageAttributeConverter` |
| `CurrencyCode` | `CurrencyCodeAttributeConverter` |
| `CountryCode` | `CountryCodeAttributeConverter` |
| `EmailAddress` | `EmailAddressAttributeConverter` |

All types from: `dk.trustworks.essentials.types`
All converters annotated with `@Converter(autoApply = true)`

## Integration Points

### Dependencies (Provided Scope)
| Dependency | Key Classes |
|------------|-------------|
| `spring-data-jpa` | `org.springframework.data.jpa.repository.JpaRepository` |
| `jakarta.persistence-api` | `jakarta.persistence.{AttributeConverter, Converter, Embeddable, EmbeddedId, Entity, Table}` |

### Related Modules
| Module | Purpose |
|--------|---------|
| [types](LLM-types.md) | Base `SingleValueType` classes |
| [types-jdbi](LLM-types-jdbi.md) | JDBI persistence (recommended alternative) |
| [types-jackson](LLM-types-jackson.md) | JSON serialization |

## Gotchas

- **EXPERIMENTAL** - May be discontinued; prefer [types-jdbi](LLM-types-jdbi.md)
- **No ID autogeneration** - Must generate IDs manually (`OrderId.random()`)
- **@EmbeddedId required** - Cannot use `@Id` on `SingleValueType` fields
- **Duplicate ID field** - `@Embeddable` needs both `SingleValueType` value + persistent field
- **@Embeddable not reusable** - Cannot use as `@EmbeddedId` AND regular property
- **No-arg constructor** - Use temp value (`-1L`) since `SingleValueType` cannot be null
- **One converter per type** - Each `SingleValueType` needs own `AttributeConverter`
- **autoApply = true required** - Auto-applies converter to all entity fields
- **BigDecimal → Double** - Precision loss possible for high-precision calculations
- **Update both fields** - `@Embeddable` constructor must update `super()` + persistent field

## Test References
Test package: `dk.trustworks.essentials.types.springdata.jpa`

| File | Demonstrates |
|------|-------------|
| `OrderRepositoryIT.java` | Full integration test with JPA repository |
| `model/Order.java` | Entity with `@EmbeddedId` and converters |
| `model/OrderId.java` | `@Embeddable` ID implementation pattern |
| `converters/CustomerIdAttributeConverter.java` | Custom converter example |

## See Also
- [README.md](../types-springdata-jpa/README.md) - Full documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-types-jdbi.md](LLM-types-jdbi.md) - JDBI persistence (recommended alternative)
