# Types-SpringData-JPA

> Spring Data JPA persistence support for Essentials `types` module

This module provides JPA `AttributeConverter` base classes for persisting `SingleValueType` implementations to relational databases using Spring Data JPA.

> **WARNING: EXPERIMENTAL & UNSTABLE**
>
> This module is experimental and may be discontinued in a future release. It has significant limitations:
> - Does NOT support ID autogeneration for `@Id` annotated `SingleValueType` fields
> - `@Id` fields require complex workarounds with `@EmbeddedId` and `@Embeddable`
> - Each `SingleValueType` requires a custom `AttributeConverter` class
>
> **Consider using [types-jdbi](../types-jdbi) instead for SQL database persistence.**

**LLM Context:** [LLM-types-springdata-jpa.md](../LLM/LLM-types-springdata-jpa.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Base AttributeConverters](#base-attributeconverters)
- [Primary Key Fields](#primary-key-fields)
- [Built-in Converters](#built-in-converters)
- [Gotchas](#gotchas)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-springdata-jpa</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>jakarta.persistence</groupId>
    <artifactId>jakarta.persistence-api</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.types.springdata.jpa.converters`

**1. Create AttributeConverters for your types:**

```java
@Converter(autoApply = true)
public class CustomerIdAttributeConverter extends BaseCharSequenceTypeAttributeConverter<CustomerId> {
    @Override
    protected Class<CustomerId> getConcreteCharSequenceType() {
        return CustomerId.class;
    }
}

@Converter(autoApply = true)
public class AccountIdAttributeConverter extends BaseLongTypeAttributeConverter<AccountId> {
    @Override
    protected Class<AccountId> getConcreteLongType() {
        return AccountId.class;
    }
}
```

**2. Define entities with semantic types:**

```java
@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;          // See "Primary Key Fields" section
    public CustomerId customerId;
    public AccountId accountId;
    public Amount totalPrice;
}
```

**3. Use repositories as normal:**

```java
public interface OrderRepository extends JpaRepository<Order, OrderId> {
    // Spring Data auto-implements query methods
    List<Order> findByCustomerId(CustomerId customerId);
}
```

**Learn more:** See [OrderRepositoryIT.java](src/test/java/dk/trustworks/essentials/types/springdata/jpa/OrderRepositoryIT.java)

## Base AttributeConverters

Extend these base classes to create converters for your types:

| SingleValueType | Base Converter | Database Type | Abstract Method |
|-----------------|----------------|---------------|-----------------|
| `CharSequenceType` | `BaseCharSequenceTypeAttributeConverter` | `String` | `getConcreteCharSequenceType()` |
| `BigDecimalType` | `BaseBigDecimalTypeAttributeConverter` | `Double` → `double precision` (lossy) | `getConcreteBigDecimalType()` |
| `BigDecimalType` | `BaseBigDecimalTypeNumericAttributeConverter` | `BigDecimal` → `numeric` (exact) | `getConcreteBigDecimalType()` |
| `IntegerType` | `BaseIntegerTypeAttributeConverter` | `Integer` | `getConcreteIntegerType()` |
| `LongType` | `BaseLongTypeAttributeConverter` | `Long` | `getConcreteLongType()` |
| `ShortType` | `BaseShortTypeAttributeConverter` | `Short` | `getConcreteShortType()` |
| `ByteType` | `BaseByteTypeAttributeConverter` | `Byte` | `getConcreteByteType()` |
| `DoubleType` | `BaseDoubleTypeAttributeConverter` | `Double` | `getConcreteDoubleType()` |
| `FloatType` | `BaseFloatTypeAttributeConverter` | `Float` | `getConcreteFloatType()` |
| `InstantType` | `BaseInstantTypeAttributeConverter` | `Instant` | `getConcreteInstantType()` |
| `LocalDateTimeType` | `BaseLocalDateTimeTypeAttributeConverter` | `LocalDateTime` | `getConcreteLocalDateTimeType()` |
| `LocalDateType` | `BaseLocalDateTypeAttributeConverter` | `LocalDate` | `getConcreteLocalDateType()` |
| `LocalTimeType` | `BaseLocalTimeTypeAttributeConverter` | `LocalTime` | `getConcreteLocalTimeType()` |
| `OffsetDateTimeType` | `BaseOffsetDateTimeTypeAttributeConverter` | `OffsetDateTime` | `getConcreteOffsetDateTimeType()` |
| `ZonedDateTimeType` | `BaseZonedDateTimeTypeAttributeConverter` | `ZonedDateTime` | `getConcreteZonedDateTimeType()` |

### Example: CharSequenceType Converter

```java
@Converter(autoApply = true)
public class CustomerIdAttributeConverter extends BaseCharSequenceTypeAttributeConverter<CustomerId> {
    @Override
    protected Class<CustomerId> getConcreteCharSequenceType() {
        return CustomerId.class;
    }
}
```

### Example: LongType Converter

```java
@Converter(autoApply = true)
public class AccountIdAttributeConverter extends BaseLongTypeAttributeConverter<AccountId> {
    @Override
    protected Class<AccountId> getConcreteLongType() {
        return AccountId.class;
    }
}
```

### Example: InstantType Converter

```java
@Converter(autoApply = true)
public class LastUpdatedAttributeConverter extends BaseInstantTypeAttributeConverter<LastUpdated> {
    @Override
    protected Class<LastUpdated> getConcreteInstantType() {
        return LastUpdated.class;
    }
}
```

## Primary Key Fields

`@Id` fields of type `SingleValueType` require special handling due to JPA/Hibernate limitations. You must use `@EmbeddedId` instead of `@Id`.
Issue: You cannot use same type as `@EmbeddedId` AND regular property as it causes dual-column mapping. Id types and property types must be different.

### Entity Setup

```java
@Entity
@Table(name = "orders")
public class Order {
    @EmbeddedId
    public OrderId id;
    // ... other fields
}
```

### ID Type Requirements

The ID type must:
1. Be annotated with `@Embeddable`
2. Include a separate persistent field for the ID value (JPA requires this)
3. Provide a no-arg constructor (can be `protected`)
4. Update both the `SingleValueType` value and the persistent field in the constructor

```java
@Embeddable
public class OrderId extends LongType<OrderId> implements Identifier {
    private static Random RANDOM_ID_GENERATOR = new Random();

    /**
     * Required by JPA - the actual persisted value.
     * JPA/Hibernate cannot use SingleValueType's immutable value directly.
     */
    private Long orderId;

    /**
     * Required by JPA - no-arg constructor.
     * Uses -1L as temporary value (SingleValueType cannot be null).
     */
    protected OrderId() {
        super(-1L);
    }

    public OrderId(Long value) {
        super(value);
        orderId = value;  // Must update both!
    }

    public static OrderId of(long value) {
        return new OrderId(value);
    }

    public static OrderId random() {
        return new OrderId(RANDOM_ID_GENERATOR.nextLong());
    }
}
```

## Built-in Converters

Ready-to-use converters for common Essentials types:

| Type | Converter | Column | Auto-applied |
|------|-----------|--------|--------------|
| `Amount` | `AmountAttributeConverter` | `double precision` (lossy) | yes |
| `Amount` | `AmountNumericAttributeConverter` | `numeric` (exact) | no |
| `Percentage` | `PercentageAttributeConverter` | `double precision` (lossy) | yes |
| `Percentage` | `PercentageNumericAttributeConverter` | `numeric` (exact) | no |
| `CurrencyCode` | `CurrencyCodeAttributeConverter` | `varchar` | yes |
| `CountryCode` | `CountryCodeAttributeConverter` | `varchar` | yes |
| `EmailAddress` | `EmailAddressAttributeConverter` | `varchar` | yes |

The auto-applied ones are annotated `@Converter(autoApply = true)` and need no wiring.

### Storing money exactly

`Amount` and `Percentage` are `BigDecimal`-backed, but the auto-applied converters map them to a `double precision`
column. That loses the scale of the value written — `Amount.of("1999.50")` reads back as `1999.5`, and `BigDecimal.equals`
is scale-sensitive — and it makes every SQL `sum`, `avg` and comparison on the column a floating-point operation.

For monetary values, opt in to the `numeric`-backed converter per field:

```java
@Entity
@Table(name = "orders")
public class Order {
    @Convert(converter = AmountNumericAttributeConverter.class)
    @Column(precision = 19, scale = 2)
    public Amount totalPrice;
}
```

The numeric converters are deliberately not auto-applied: two auto-applied converters for the same type would be
ambiguous, and changing the existing default would change the generated column type for every existing deployment. An
explicit `@Convert` takes precedence over an auto-applied converter, so the field above is `numeric` even though
`AmountAttributeConverter` is on the classpath.

The converter imposes no precision or scale of its own — a framework converter cannot know your domain's scale — so
declare `@Column(precision = …, scale = …)` yourself, exactly as you would for a plain `BigDecimal` property. Without it,
a Hibernate-generated schema gets `numeric(38,2)`, which rounds every value to two decimals.

Exact does not mean unchanged: a `numeric(p,s)` column stores every value at scale `s`. It **rounds** a value with more
decimals and **pads** one with fewer — with `scale = 2`, `123.456` reads back as `123.46` and `100.5` as `100.50`, which is
not `equals` to what was written. Give the column the scale your domain writes, or use PostgreSQL's unconstrained
`numeric` (`@Column(columnDefinition = "numeric")`) when values of different scales must round-trip unchanged.

`double precision` stays the auto-applied default in 0.60, so existing schemas are untouched. Opting in changes a
column's type; see [MIGRATION-0.60.md](../docs/MIGRATION-0.60.md) for migrating an existing column.

## Gotchas

- **No ID autogeneration** - Unlike standard JPA, this module does NOT support automatic ID generation for `SingleValueType` IDs. You must generate IDs manually (e.g., using a `random()` method).

- **@EmbeddedId required for primary keys** - Cannot use `@Id` annotation directly on `SingleValueType` fields. Must use `@EmbeddedId` with `@Embeddable` ID types.

- **Duplicate field in ID types** - ID types require both the `SingleValueType` value AND a separate persistent field for JPA compatibility.

- **@Embeddable types cannot be reused as regular properties** - If you mark a `SingleValueType` as `@Embeddable` for use with `@EmbeddedId`, you cannot use that same type as a regular property in other entities. JPA would map it as an embedded component with multiple columns. Create separate types: one `@Embeddable` for IDs, and use `AttributeConverter` for regular properties.

- **No-arg constructor required** - `@Embeddable` ID types need a no-arg constructor, but `SingleValueType` doesn't allow null values. Use a temporary value like `-1L`.

- **One converter per type** - Each `SingleValueType` subclass requires its own `AttributeConverter` class (cannot use a generic converter like MongoDB).

- **autoApply = true** - Use `@Converter(autoApply = true)` to automatically apply converters to all entity fields of that type.

- **BigDecimal stored as Double** - The auto-applied `Amount` and `Percentage` converters store values in a `double precision` column, which loses the written scale and performs SQL arithmetic in floating point. Use `AmountNumericAttributeConverter` / `PercentageNumericAttributeConverter` via `@Convert` for money — see [Storing money exactly](#storing-money-exactly).

- **Consider alternatives** - Due to the complexity of ID handling, consider using [types-jdbi](../types-jdbi) for SQL database persistence instead.

## See Also

- [LLM-types-springdata-jpa.md](../LLM/LLM-types-springdata-jpa.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jdbi](../types-jdbi) - JDBI persistence (recommended alternative for SQL databases)
