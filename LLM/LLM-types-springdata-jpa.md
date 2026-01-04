# Types-SpringData-JPA - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.types.springdata.jpa.converters`
- Purpose: JPA `AttributeConverter` base classes for `SingleValueType` persistence
- Dependencies: `spring-data-jpa`, `jakarta.persistence-api` (provided scope)
- Status: **EXPERIMENTAL** - may be discontinued

## TOC
- [Limitations](#limitations)
- [Base AttributeConverters](#base-attributeconverters)
- [API Signatures](#api-signatures)
- [Common Tasks](#common-tasks)
- [Primary Key Handling](#primary-key-handling)
- [Built-in Converters](#built-in-converters)
- [Integration Points](#integration-points)
- [Gotchas](#gotchas)

## Limitations

| Limitation | Impact |
|------------|--------|
| No ID autogeneration | Must generate IDs manually |
| `@Id` not supported | Must use `@EmbeddedId` + `@Embeddable` |
| One converter per type | Cannot use generic converter |
| Duplicate ID field | ID types need both `SingleValueType` value and persistent field |

**Recommendation:** Consider [types-jdbi](LLM-types-jdbi.md) for SQL database persistence.

## Base AttributeConverters


| SingleValueType  (`dk.trustworks.essentials.types`)    | Base Converter (`dk.trustworks.essentials.types.springdata.jpa.converters`) | DB Type | Abstract Method |
|----------------------|-----------------------------------------------------------------------------|---------|-----------------|
| `CharSequenceType`   | `BaseCharSequenceTypeAttributeConverter<T>`                                 | `String` | `Class<T> getConcreteCharSequenceType()` |
| `BigDecimalType`     | `BaseBigDecimalTypeAttributeConverter<T>`                                   | `Double` | `Class<T> getConcreteBigDecimalType()` |
| `IntegerType`        | `BaseIntegerTypeAttributeConverter<T>`                                      | `Integer` | `Class<T> getConcreteIntegerType()` |
| `LongType`           | `BaseLongTypeAttributeConverter<T>`                                         | `Long` | `Class<T> getConcreteLongType()` |
| `ShortType`          | `BaseShortTypeAttributeConverter<T>`                                        | `Short` | `Class<T> getConcreteShortType()` |
| `ByteType`           | `BaseByteTypeAttributeConverter<T>`                                         | `Byte` | `Class<T> getConcreteByteType()` |
| `DoubleType`         | `BaseDoubleTypeAttributeConverter<T>`                                       | `Double` | `Class<T> getConcreteDoubleType()` |
| `FloatType`          | `BaseFloatTypeAttributeConverter<T>`                                        | `Float` | `Class<T> getConcreteFloatType()` |
| `InstantType`        | `BaseInstantTypeAttributeConverter<T>`                                      | `Instant` | `Class<T> getConcreteInstantType()` |
| `LocalDateTimeType`  | `BaseLocalDateTimeTypeAttributeConverter<T>`                                | `LocalDateTime` | `Class<T> getConcreteLocalDateTimeType()` |
| `LocalDateType`      | `BaseLocalDateTypeAttributeConverter<T>`                                    | `LocalDate` | `Class<T> getConcreteLocalDateType()` |
| `LocalTimeType`      | `BaseLocalTimeTypeAttributeConverter<T>`                                    | `LocalTime` | `Class<T> getConcreteLocalTimeType()` |
| `OffsetDateTimeType` | `BaseOffsetDateTimeTypeAttributeConverter<T>`                               | `OffsetDateTime` | `Class<T> getConcreteOffsetDateTimeType()` |
| `ZonedDateTimeType`  | `BaseZonedDateTimeTypeAttributeConverter<T>`                                | `ZonedDateTime` | `Class<T> getConcreteZonedDateTimeType()` |

## API Signatures

### Base Converter Interface
All base converters implement `jakarta.persistence.AttributeConverter<T, DB_TYPE>`:

```java
// From BaseCharSequenceTypeAttributeConverter<T>
String convertToDatabaseColumn(T attribute);
T convertToEntityAttribute(String dbData);
protected abstract Class<T> getConcreteCharSequenceType();

// From BaseLongTypeAttributeConverter<T>
Long convertToDatabaseColumn(T attribute);
T convertToEntityAttribute(Long dbData);
protected abstract Class<T> getConcreteLongType();

// Pattern applies to all base converters (replace method name with type)
```

### Converter Creation Pattern
```java
@Converter(autoApply = true)
public class {TypeName}AttributeConverter extends Base{BaseType}AttributeConverter<{TypeName}> {
    @Override
    protected Class<{TypeName}> getConcrete{BaseType}() {
        return {TypeName}.class;
    }
}
```

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
@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;           // See Primary Key Handling
    public CustomerId customerId; // Auto-converted via CustomerIdAttributeConverter
    public AccountId accountId;   // Auto-converted via AccountIdAttributeConverter
    public Amount totalPrice;     // Auto-converted via AmountAttributeConverter
}
```

### Task: Use in Repository
```java
public interface OrderRepository extends JpaRepository<Order, OrderId> {
    List<Order> findByCustomerId(CustomerId customerId);
    Optional<Order> findByAccountId(AccountId accountId);
}
```

## Primary Key Handling

### Entity with @EmbeddedId
```java
@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;  // Must use @EmbeddedId, NOT @Id
    // ... other fields
}
```

### @Embeddable ID Type Requirements
Must implement:
1. `@Embeddable` annotation
2. Separate persistent field (e.g., `private Long orderId`)
3. No-arg constructor (use temp value like `-1L`)
4. Update both fields in constructor

Issue: You cannot use same type as `@EmbeddedId` AND regular property as it causes dual-column mapping. Id types and property types must be different.

```java
import dk.trustworks.essentials.types.LongType;
import dk.trustworks.essentials.types.Identifier;
import jakarta.persistence.Embeddable;
import java.util.Random;

@Embeddable
public class OrderId extends LongType<OrderId> implements Identifier {
    private static final Random RANDOM = new Random();

    // Required by JPA - actual persisted value
    private Long orderId;

    // Required by JPA - no-arg constructor
    protected OrderId() {
        super(-1L);
    }

    public OrderId(Long value) {
        super(value);
        orderId = value;  // MUST update both fields!
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


| Type (`dk.trustworks.essentials.types`)          | Converter (`dk.trustworks.essentials.types.springdata.jpa.converters`) | 
|----------------|------------------------------------------------------------------------|
| `Amount`       | `AmountAttributeConverter`                                             |
| `Percentage`   | `PercentageAttributeConverter`                                         |
| `CurrencyCode` | `CurrencyCodeAttributeConverter`                                       |
| `CountryCode`  | `CountryCodeAttributeConverter`                                        |
| `EmailAddress` | `EmailAddressAttributeConverter`                                       |

All annotated with `@Converter(autoApply = true)`.

## Integration Points

### Dependencies (Provided Scope)
| Module | Classes Used |
|--------|--------------|
| **spring-data-jpa** | `JpaRepository` |
| **jakarta.persistence-api** | `AttributeConverter`, `@Converter`, `@Embeddable`, `@EmbeddedId`, `@Entity` |

### Related Essentials Modules
| Module | Purpose |
|--------|---------|
| **[types](LLM-types.md)** | Base types: `SingleValueType`, `CharSequenceType`, `LongType`, etc. |
| **[types-jdbi](LLM-types-jdbi.md)** | JDBI persistence (recommended alternative) |
| **[types-jackson](LLM-types-jackson.md)** | JSON serialization |

## Gotchas

- ⚠️ **EXPERIMENTAL** - Module may be discontinued; prefer [types-jdbi](LLM-types-jdbi.md)
- ⚠️ **No ID autogeneration** - Must generate IDs manually (e.g., `OrderId.random()`)
- ⚠️ **@EmbeddedId required for PKs** - Cannot use `@Id` on `SingleValueType` fields
- ⚠️ **Duplicate ID field required** - `@Embeddable` ID needs separate persistent field for JPA
- ⚠️ **@Embeddable not reusable** - Cannot use same type as `@EmbeddedId` AND regular property (causes dual-column mapping)
- ⚠️ **No-arg constructor** - Use temp value like `-1L` (SingleValueType cannot be null)
- ⚠️ **One converter per type** - Each `SingleValueType` subclass needs its own `AttributeConverter`
- ⚠️ **autoApply = true** - Always use to auto-apply converter to all entity fields of that type
- ⚠️ **BigDecimal → Double** - Stored as `Double`, potential precision loss for high-precision calculations
- ⚠️ **Must update both fields** - In `@Embeddable` ID constructor, update both `super()` and persistent field

## Test References
See `types-springdata-jpa/src/test/java/`:
- `OrderRepositoryIT.java` - Integration test showing full usage pattern
- `model/Order.java` - Entity example with `@EmbeddedId`
- `model/OrderId.java` - `@Embeddable` ID implementation

## See Also
- [README.md](../types-springdata-jpa/README.md) - Full documentation with detailed examples
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-types-jdbi.md](LLM-types-jdbi.md) - JDBI persistence (recommended SQL alternative)
