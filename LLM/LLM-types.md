# Types - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](../types/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types`
- Purpose: Semantic types eliminating primitive obsession
- Dependencies: None (slf4j-api provided)
- All types: immutable, null-rejecting, `Comparable`, `Serializable`

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types</artifactId>
</dependency>
```

---

## TOC
- [Type Hierarchy](#type-hierarchy)
- [Creating Custom Types](#creating-custom-types)
- [Built-in Types](#built-in-types)
- [API Reference](#api-reference)
- [Kotlin Support](#kotlin-support)
- [Integration Modules](#integration-modules)
- [Gotchas](#gotchas)

---

## Type Hierarchy

Base package: `dk.trustworks.essentials.types`

### Base Classes

| Java Type | Base Class | Use For |
|-----------|------------|---------|
| `String`/`CharSequence` | `CharSequenceType<T>` | IDs, codes, names |
| `BigDecimal` | `BigDecimalType<T>` | Money, percentages |
| `BigInteger` | `BigIntegerType<T>` | Large integers |
| `Long` | `LongType<T>` | Numeric IDs, timestamps |
| `Integer` | `IntegerType<T>` | Counters, quantities |
| `Short` | `ShortType<T>` | Small values |
| `Byte` | `ByteType<T>` | Status codes |
| `Double` | `DoubleType<T>` | Measurements |
| `Float` | `FloatType<T>` | Single-precision |
| `Boolean` | `BooleanType<T>` | Flags |
| `Instant` | `InstantType<T>` | UTC timestamps |
| `LocalDate` | `LocalDateType<T>` | Dates |
| `LocalDateTime` | `LocalDateTimeType<T>` | Date-times |
| `LocalTime` | `LocalTimeType<T>` | Times |
| `OffsetDateTime` | `OffsetDateTimeType<T>` | Date-time+offset |
| `ZonedDateTime` | `ZonedDateTimeType<T>` | Date-time+zone |

### Core Interfaces

```java
// Base interface - all types implement this
interface SingleValueType<VALUE_TYPE, CONCRETE_TYPE> extends Serializable, Comparable<CONCRETE_TYPE> {
    VALUE_TYPE value();       // Get wrapped value (never null)
    VALUE_TYPE getValue();    // Alias for value()

    // Reflective instantiation
    static <V, T extends SingleValueType<V,T>> T from(V value, Class<T> type);
    static SingleValueType<?,?> fromObject(Object value, Class<? extends SingleValueType<?,?>> type);
}

// Marker for temporal types (Instant, LocalDate, etc.)
interface JSR310SingleValueType<V, T> extends SingleValueType<V, T> {}

// Optional marker for ID types
interface Identifier {}
```

---

## Creating Custom Types

### String ID (CharSequenceType)
```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required for Jackson 2.18+

    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.generate()); }
}
```

### Numeric ID (LongType)
```java
public class ProductSequence extends LongType<ProductSequence> {
    public ProductSequence(Long value) { super(value); }

    public static ProductSequence of(long value) { return new ProductSequence(value); }
    public ProductSequence next() { return new ProductSequence(value() + 1); }
}
```

### Validated Type (IntegerType)
```java
public class Quantity extends IntegerType<Quantity> {
    public Quantity(Integer value) {
        super(value);
        requireTrue(value >= 0, "Quantity cannot be negative");
    }

    public static Quantity of(int value) { return new Quantity(value); }
    public Quantity add(Quantity other) { return new Quantity(value() + other.value()); }
}
```

### Temporal Type (InstantType)
```java
public class CreatedAt extends InstantType<CreatedAt> {
    public CreatedAt(Instant value) { super(value); }

    public static CreatedAt now() { return new CreatedAt(Instant.now()); }
    public static CreatedAt of(Instant instant) { return new CreatedAt(instant); }
}
```

### Generic Instantiation
```java
OrderId id = SingleValueType.from("abc789", OrderId.class);
```

---

## Built-in Types

Base package: `dk.trustworks.essentials.types`

| Type | Extends | Description |
|------|---------|-------------|
| `Amount` | `BigDecimalType<Amount>` | Currency-agnostic monetary value |
| `CurrencyCode` | `CharSequenceType<CurrencyCode>` | ISO-4217 currency (validated) |
| `Money` | standalone | Amount + CurrencyCode (same-currency enforced) |
| `Percentage` | `BigDecimalType<Percentage>` | Percentage with `of()` calculation |
| `CountryCode` | `CharSequenceType<CountryCode>` | ISO-3166-2 country (validated) |
| `EmailAddress` | `CharSequenceType<EmailAddress>` | Email with pluggable validation |
| `LongRange` | standalone | Long range with stream support |
| `TimeWindow` | standalone | Instant-based time period |

---

## API Reference

### NumberType (all numeric types inherit)

```java
// Comparison methods
boolean isGreaterThan(T other)
boolean isGreaterThanOrEqualTo(T other)
boolean isLessThan(T other)
boolean isLessThanOrEqualTo(T other)
int compareTo(T o)

// Number conversions
int intValue()
long longValue()
float floatValue()
double doubleValue()
```

### BigDecimalType Operations

All return `CONCRETE_TYPE` (immutable):

```java
// Arithmetic
T add(T augend)
T subtract(T subtrahend)
T multiply(T multiplicand)
T divide(T divisor)
T divide(T divisor, int scale, RoundingMode rm)
T remainder(T divisor)

// Math
T abs()
T negate()
T pow(int n)
T sqrt(MathContext mc)
T round(MathContext mc)

// Scale
T setScale(int newScale)
T setScale(int newScale, RoundingMode rm)
int scale()
int precision()
int signum()

// Min/max
T min(BigDecimal val)
T max(BigDecimal val)
```

### Amount

```java
// Factory
static Amount of(String value)
static Amount of(BigDecimal value)
static Amount ofNullable(String value)
static Amount zero()              // Amount.ZERO

// Constants
static final Amount ZERO

// Inherits all BigDecimalType operations
```

### Percentage

```java
// Factory
static Percentage from(String percent)      // "25%" or "25" -> 25%
static Percentage from(BigDecimal percent)  // 0.25 -> 25%

// Constants
static final Percentage _100    // 100%
static final Percentage _0      // 0%

// Calculate percentage of amount
<T extends BigDecimalType<T>> T of(T amount)
BigDecimal of(BigDecimal amount)

String toString()  // Returns "25.00%"
```

**Usage:**
```java
Percentage taxRate = Percentage.from("25%");
Amount base = Amount.of("100.00");
Amount tax = taxRate.of(base);  // 25.00
```

### Money

```java
// Factory
static Money of(String amount, String currencyCode)
static Money of(Amount amount, CurrencyCode currency)

// Accessors
Amount getAmount()
CurrencyCode getCurrency()

// Arithmetic (throws NotTheSameCurrenciesException if currencies differ)
Money add(Money augend)
Money subtract(Money subtrahend)
Money multiply(Money multiplicand)
Money divide(Money divisor)

// Math
Money abs()
Money negate()
Money setScale(int newScale, RoundingMode rm)

// Exception
static class NotTheSameCurrenciesException extends RuntimeException
```

### LongRange

```java
// Factory
static LongRange between(long from, long to)  // Closed [from, to]
static LongRange from(long from)              // Open [from, infinity)
static LongRange only(long value)             // [n, n]
static LongRange empty()                      // [0, 0]

// Query
boolean isClosedRange()
boolean isOpenRange()
boolean covers(long value)
long getFromInclusive()
Long getToInclusive()

// Stream
LongStream stream()
```

### TimeWindow

```java
// Factory
static TimeWindow from(Instant fromInclusive)
static TimeWindow between(Instant from, Instant to)

// Query
boolean isOpenTimeWindow()
boolean isClosedTimeWindow()
boolean covers(Instant timestamp)
Instant getFromInclusive()
Instant getToExclusive()

// Mutation
TimeWindow close(Instant toExclusive)
```

### CharSequenceType Methods

Implements `CharSequence` - all String methods available plus:

```java
// Returns CONCRETE_TYPE
T substring(int beginIndex)
T substring(int beginIndex, int endIndex)

// Search
boolean contains(String str)
boolean containsIgnoreCase(String str)
```

---

## Kotlin Support

Package: `dk.trustworks.essentials.kotlin.types`

Use `@JvmInline value class` for zero-overhead:

```kotlin
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun of(value: String) = OrderId(value)
    }
}

@JvmInline
value class Quantity(override val value: Int) : IntValueType<Quantity> {
    init { require(value >= 0) { "Quantity cannot be negative" } }
    operator fun plus(other: Quantity) = Quantity(value + other.value)
}
```

**Kotlin interfaces:**

| Wrapped Type | Interface |
|--------------|-----------|
| `String` | `StringValueType<T>` |
| `BigDecimal` | `BigDecimalValueType<T>` |
| `BigInteger` | `BigIntegerValueType<T>` |
| `Long` | `LongValueType<T>` |
| `Int` | `IntValueType<T>` |
| `Short` | `ShortValueType<T>` |
| `Byte` | `ByteValueType<T>` |
| `Double` | `DoubleValueType<T>` |
| `Float` | `FloatValueType<T>` |
| `Boolean` | `BooleanValueType<T>` |
| `Instant` | `InstantValueType<T>` |
| `LocalDate` | `LocalDateValueType<T>` |
| `LocalDateTime` | `LocalDateTimeValueType<T>` |
| `LocalTime` | `LocalTimeValueType<T>` |
| `OffsetDateTime` | `OffsetDateTimeValueType<T>` |
| `ZonedDateTime` | `ZonedDateTimeValueType<T>` |

Built-in Kotlin types: `Amount`, `CountryCode`

---

## Integration Modules

| Framework | Module | Doc |
|-----------|--------|-----|
| Jackson JSON | `types-jackson` | [LLM-types-jackson.md](LLM-types-jackson.md) |
| Spring Data MongoDB | `types-springdata-mongo` | [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md) |
| Spring Data JPA | `types-springdata-jpa` | [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md) |
| JDBI v3 | `types-jdbi` | [LLM-types-jdbi.md](LLM-types-jdbi.md) |
| Apache Avro | `types-avro` | [LLM-types-avro.md](LLM-types-avro.md) |
| Spring WebMvc/WebFlux | `types-spring-web` | [LLM-types-spring-web.md](LLM-types-spring-web.md) |

See [LLM-types-integrations.md](LLM-types-integrations.md) for overview.

---

## Gotchas

- **Null rejection**: All constructors reject null values
- **Jackson 2.18+**: Requires explicit `String` constructor alongside `CharSequence` for `CharSequenceType`
- **Immutability**: All operations return new instances
- **Money currency**: Operations throw `NotTheSameCurrenciesException` if currencies differ
- **Percentage scale**: Enforces minimum scale of 2
- **Identifier**: Optional marker interface for semantic searchability
- **Kotlin package**: `dk.trustworks.essentials.kotlin.types` (not `types.kotlin`)
- **AssertJ with CharSequenceType**: Cast to `CharSequence` for proper equality assertions (see below)

### AssertJ Testing with CharSequenceType

When testing `CharSequenceType` subclasses with AssertJ, cast to `CharSequence` for `isEqualTo`/`isNotEqualTo`:

```java
// CORRECT - cast to CharSequence
assertThat((CharSequence) CustomerId.of("Test")).isEqualTo(CustomerId.of("Test"));
assertThat((CharSequence) ProductId.of("ABC")).isNotEqualTo(ProductId.of("XYZ"));

// Also works for substring operations
ProductId partOfId = productId.substring(2, 5);
assertThat((CharSequence) partOfId).isEqualTo(ProductId.of("me-"));

// Alternative - use .equals() directly (no cast needed)
assertThat(CustomerId.of("Test").equals(CustomerId.of("Test"))).isTrue();

// value() comparison works without casting
assertThat(CustomerId.of("Test").value()).isEqualTo("Test");
```

**Why the cast?** AssertJ's `assertThat()` overloads select `AbstractCharSequenceAssert` when passed a `CharSequence`, enabling string-aware equality. Without the cast, AssertJ may use generic object assertion which can produce unexpected results.

---

## See Also

- [README.md](../types/README.md) - Full documentation with motivation
- [LLM-types-integrations.md](LLM-types-integrations.md) - Integration overview
- Tests: `types/src/test/java/dk/trustworks/essentials/types/`
