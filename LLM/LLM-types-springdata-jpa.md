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
| `BigDecimalType` | `BaseBigDecimalTypeAttributeConverter<T>` | `Double` → `double precision` (**lossy**, see below) | `getConcreteBigDecimalType()` |
| `BigDecimalType` | `BaseBigDecimalTypeNumericAttributeConverter<T>` | `BigDecimal` → `numeric` (lossless, **prefer for money**) | `getConcreteBigDecimalType()` |
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

### BigDecimal: `double precision` vs `numeric`

`BaseBigDecimalTypeAttributeConverter` maps to a `double precision` column. That is lossy in two ways, and nothing warns:

1. **Scale is lost.** `Amount.of("1999.50")` reads back as `1999.5`. Numerically equal, but `BigDecimal.equals` is
   scale-sensitive, so an assertion, a cache key or a `Map` lookup against the value that was written fails.
2. **SQL arithmetic is floating point.** `sum`, `avg` and every comparison on the column are IEEE-754 operations. Sums
   over many rows drift, and a value beyond roughly 15-17 significant digits cannot be represented at all.

`BaseBigDecimalTypeNumericAttributeConverter` maps to `BigDecimal` → an exact `numeric` column and round-trips losslessly.
Use it for money, and for any rate that is compounded rather than merely displayed.

It deliberately imposes no precision or scale — a framework converter cannot know the domain's scale — so declare the
column yourself, exactly as you would for a plain `BigDecimal` property. Without an explicit `@Column`, Hibernate applies
its own default precision and scale, which is rarely what a monetary column wants.

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

### Task: Store an `Amount` in an exact `numeric` column
```java
import dk.trustworks.essentials.types.Amount;
import dk.trustworks.essentials.types.springdata.jpa.converters.AmountNumericAttributeConverter;
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @Convert(converter = AmountNumericAttributeConverter.class)
    @Column(precision = 19, scale = 2)
    public Amount totalPrice;
}
```
`AmountNumericAttributeConverter` and `PercentageNumericAttributeConverter` are **not** `autoApply` — the `Double`-backed
built-ins are, and two auto-applied converters for the same type would be ambiguous. An explicit `@Convert` takes
precedence over an auto-applied converter, so the field above is `numeric` even with `AmountAttributeConverter` on the
classpath.

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

| Type | Converter | Column | Auto-applied |
|------|-----------|--------|--------------|
| `Amount` | `AmountAttributeConverter` | `double precision` (lossy) | yes |
| `Amount` | `AmountNumericAttributeConverter` | `numeric` (lossless) | no — opt in with `@Convert` |
| `Percentage` | `PercentageAttributeConverter` | `double precision` (lossy) | yes |
| `Percentage` | `PercentageNumericAttributeConverter` | `numeric` (lossless) | no — opt in with `@Convert` |
| `CurrencyCode` | `CurrencyCodeAttributeConverter` | `varchar` | yes |
| `CountryCode` | `CountryCodeAttributeConverter` | `varchar` | yes |
| `EmailAddress` | `EmailAddressAttributeConverter` | `varchar` | yes |

All types from: `dk.trustworks.essentials.types`

The `Double`-backed `Amount`/`Percentage` converters remain the auto-applied default so existing schemas keep working.
The default changes to `numeric` at the next major — see [MIGRATION-NEXT_MAJOR.md](../docs/MIGRATION-NEXT_MAJOR.md).

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
| [types-jackson3](LLM-types-jackson.md) | JSON serialization |

## Gotchas

- **EXPERIMENTAL** - May be discontinued; prefer [types-jdbi](LLM-types-jdbi.md)
- **No ID autogeneration** - Must generate IDs manually (`OrderId.random()`)
- **@EmbeddedId required** - Cannot use `@Id` on `SingleValueType` fields
- **Duplicate ID field** - `@Embeddable` needs both `SingleValueType` value + persistent field
- **@Embeddable not reusable** - Cannot use as `@EmbeddedId` AND regular property
- **No-arg constructor** - Use temp value (`-1L`) since `SingleValueType` cannot be null
- **One converter per type** - Each `SingleValueType` needs own `AttributeConverter`
- **autoApply = true required** - Auto-applies converter to all entity fields
- **BigDecimal → Double** - The auto-applied `Amount`/`Percentage` converters map to `double precision`, which loses the
  scale of the value written (`1999.50` reads back as `1999.5`, and `BigDecimal.equals` is scale-sensitive) and makes SQL
  `sum`/`avg` floating point. Use `AmountNumericAttributeConverter` / `PercentageNumericAttributeConverter` via `@Convert`
  for money
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
