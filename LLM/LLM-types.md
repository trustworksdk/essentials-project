# Types - LLM Reference

## TOC
- [Quick Facts](#quick-facts)
- [SingleValueType Hierarchy](#singlevaluetype-hierarchy)
- [Core API](#core-api)
- [Built-in Types](#built-in-types)
- [Creating Custom Types](#creating-custom-types)
- [NumberType Comparison Methods](#numbertype-comparison-methods)
- [BigDecimalType Operations](#bigdecimaltype-operations)
- [CharSequenceType Methods](#charsequencetype-methods)
- [Amount/Money/Percentage API](#amountmoneypercentage-api)
- [Utility Types API](#utility-types-api)
- [Kotlin Value Classes](#kotlin-value-classes)
- [Integration Modules](#integration-modules)
- [Gotchas](#gotchas)

## Quick Facts
- Package: `dk.trustworks.essentials.types`
- Purpose: Semantic types eliminate primitive obsession
- Dependencies: `slf4j-api` (provided scope)
- Base: `SingleValueType<VALUE_TYPE, CONCRETE_TYPE>`
- All types immutable, null-rejecting, `Comparable`
- **Status**: WORK-IN-PROGRESS

---

## SingleValueType Hierarchy

Base: `dk.trustworks.essentials.types.SingleValueType<VALUE_TYPE, CONCRETE_TYPE>`
Package: `dk.trustworks.essentials.types`

| Java Type | Base Class | 
|-----------|------------|
| `String`/`CharSequence` | `CharSequenceType<T>` |
| `BigDecimal` | `BigDecimalType<T>` |
| `BigInteger` | `BigIntegerType<T>` |
| `Long` | `LongType<T>` |
| `Integer` | `IntegerType<T>` |
| `Short` | `ShortType<T>` |
| `Byte` | `ByteType<T>` |
| `Double` | `DoubleType<T>` |
| `Float` | `FloatType<T>` |
| `Boolean` | `BooleanType<T>` |
| `Instant` | `InstantType<T>` |
| `LocalDate` | `LocalDateType<T>` |
| `LocalDateTime` | `LocalDateTimeType<T>` |
| `LocalTime` | `LocalTimeType<T>` |
| `OffsetDateTime` | `OffsetDateTimeType<T>` |
| `ZonedDateTime` | `ZonedDateTimeType<T>` |

---

## Core API

### SingleValueType Interface
Package: `dk.trustworks.essentials.types.SingleValueType`

```java
interface SingleValueType<VALUE_TYPE, CONCRETE_TYPE> extends Serializable, Comparable<CONCRETE_TYPE>

// Core methods
VALUE_TYPE value()                    // Get wrapped value (never null)
VALUE_TYPE getValue()                 // Alias for value()

// Generic instantiation
static <T extends SingleValueType<V,T>> T from(V value, Class<T> concreteType)
static SingleValueType<?,?> fromObject(Object value, Class<? extends SingleValueType<?,?>> concreteType)
```

### NumberType Base
Package: `dk.trustworks.essentials.types.NumberType<NUMBER_TYPE, CONCRETE_TYPE>`

```java
abstract class NumberType<NUMBER_TYPE extends Number, CONCRETE_TYPE>
    extends Number implements SingleValueType<NUMBER_TYPE, CONCRETE_TYPE>

// Factory
static Class<? extends Number> resolveNumberClass(Class<?> numberType)

// Number conversions
int intValue()
long longValue()
float floatValue()
double doubleValue()

// Comparison (inherited by all numeric types)
boolean isGreaterThan(CONCRETE_TYPE other)
boolean isGreaterThanOrEqualTo(CONCRETE_TYPE other)
boolean isLessThan(CONCRETE_TYPE other)
boolean isLessThanOrEqualTo(CONCRETE_TYPE other)
int compareTo(CONCRETE_TYPE o)
```

---

## Built-in Types

### Money & Currency
Package: `dk.trustworks.essentials.types`

| Type | Extends | Description |
|------|---------|-------------|
| `Amount` | `BigDecimalType<Amount>` | Currency-agnostic monetary value |
| `CurrencyCode` | `CharSequenceType<CurrencyCode>` | ISO-4217 currency code (validated) |
| `Money` | (standalone class) | Amount + CurrencyCode, enforces same-currency |
| `Percentage` | `BigDecimalType<Percentage>` | Percentage with `of()` calculations |

### Validation Types
Package: `dk.trustworks.essentials.types`

| Type | Extends | Description |
|------|---------|-------------|
| `CountryCode` | `CharSequenceType<CountryCode>` | ISO-3166-2 country codes (validated) |
| `EmailAddress` | `CharSequenceType<EmailAddress>` | Email with pluggable validation |

### Utility Types
Package: `dk.trustworks.essentials.types`

| Type | Description |
|------|-------------|
| `LongRange` | Range of Long values with stream support |
| `TimeWindow` | Time period with Instant bounds |

### Marker Interface
Package: `dk.trustworks.essentials.types`

```java
interface Identifier  // Optional marker for semantic IDs
```

---

## Creating Custom Types

### Pattern: CharSequenceType (String-based IDs)
```java
import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required for Jackson 2.18+

    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.generate()); }
}
```

### Pattern: LongType (Numeric IDs)
```java
import dk.trustworks.essentials.types.LongType;
import dk.trustworks.essentials.types.Identifier;

public class ProductSequence extends LongType<ProductSequence> {
    public ProductSequence(Long value) { super(value); }

    public static ProductSequence of(long value) { return new ProductSequence(value); }
    public ProductSequence next() { return new ProductSequence(value() + 1); }
}
```

### Pattern: IntegerType with Validation
```java
import dk.trustworks.essentials.types.IntegerType;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

public class Quantity extends IntegerType<Quantity> {
    public Quantity(Integer value) {
        super(value);
        requireTrue(value >= 0, "Quantity cannot be negative");
    }

    public static Quantity of(int value) { return new Quantity(value); }
    public Quantity add(Quantity other) { return new Quantity(this.value() + other.value()); }
}
```

### Pattern: InstantType (Temporal)
```java
import dk.trustworks.essentials.types.InstantType;
import java.time.Instant;

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

## NumberType Comparison Methods

All numeric types inherit from `dk.trustworks.essentials.types.NumberType`:

| Method | Returns |
|--------|---------|
| `boolean isGreaterThan(CONCRETE_TYPE other)` | `value > other.value` |
| `boolean isGreaterThanOrEqualTo(CONCRETE_TYPE other)` | `value >= other.value` |
| `boolean isLessThan(CONCRETE_TYPE other)` | `value < other.value` |
| `boolean isLessThanOrEqualTo(CONCRETE_TYPE other)` | `value <= other.value` |
| `int compareTo(CONCRETE_TYPE other)` | Standard `Comparable` |

---

## BigDecimalType Operations

Class: `dk.trustworks.essentials.types.BigDecimalType<CONCRETE_TYPE>`

All return `CONCRETE_TYPE` (immutable):

```java
// Arithmetic
CONCRETE_TYPE add(CONCRETE_TYPE augend)
CONCRETE_TYPE add(CONCRETE_TYPE augend, MathContext mc)
CONCRETE_TYPE subtract(CONCRETE_TYPE subtrahend)
CONCRETE_TYPE subtract(CONCRETE_TYPE subtrahend, MathContext mc)
CONCRETE_TYPE multiply(CONCRETE_TYPE multiplicand)
CONCRETE_TYPE multiply(CONCRETE_TYPE multiplicand, MathContext mc)
CONCRETE_TYPE divide(CONCRETE_TYPE divisor)
CONCRETE_TYPE divide(CONCRETE_TYPE divisor, int scale, RoundingMode rm)
CONCRETE_TYPE divide(CONCRETE_TYPE divisor, RoundingMode rm)
CONCRETE_TYPE divide(CONCRETE_TYPE divisor, MathContext mc)
CONCRETE_TYPE remainder(CONCRETE_TYPE divisor)
CONCRETE_TYPE remainder(CONCRETE_TYPE divisor, MathContext mc)
CONCRETE_TYPE divideToIntegralValue(CONCRETE_TYPE divisor)
CONCRETE_TYPE divideToIntegralValue(CONCRETE_TYPE divisor, MathContext mc)

// Math functions
CONCRETE_TYPE abs()
CONCRETE_TYPE abs(MathContext mc)
CONCRETE_TYPE negate()
CONCRETE_TYPE negate(MathContext mc)
CONCRETE_TYPE plus()
CONCRETE_TYPE plus(MathContext mc)
CONCRETE_TYPE pow(int n)
CONCRETE_TYPE pow(int n, MathContext mc)
CONCRETE_TYPE sqrt(MathContext mc)
CONCRETE_TYPE round(MathContext mc)

// Scale/precision
CONCRETE_TYPE setScale(int newScale)
CONCRETE_TYPE setScale(int newScale, RoundingMode rm)
CONCRETE_TYPE stripTrailingZeros()
CONCRETE_TYPE movePointLeft(int n)
CONCRETE_TYPE movePointRight(int n)
CONCRETE_TYPE scaleByPowerOfTen(int n)
int scale()
int precision()
int signum()

// Min/max
CONCRETE_TYPE min(BigDecimal val)
CONCRETE_TYPE max(BigDecimal val)
CONCRETE_TYPE ulp()

// Conversions
BigInteger unscaledValue()
BigInteger toBigInteger()
BigInteger toBigIntegerExact()
long longValueExact()
int intValueExact()
short shortValueExact()
byte byteValueExact()
String toEngineeringString()
String toPlainString()
```

---

## CharSequenceType Methods

Class: `dk.trustworks.essentials.types.CharSequenceType<CONCRETE_TYPE>`

Implements `CharSequence` - all String methods available:

```java
// CharSequence
int length()
char charAt(int index)
CharSequence subSequence(int beginIndex, int endIndex)
IntStream chars()
IntStream codePoints()

// Comparison
int compareTo(CONCRETE_TYPE o)
int compareTo(String anotherString)
int compareToIgnoreCase(String str)

// Search
boolean isEmpty()
boolean contains(String str)
boolean containsIgnoreCase(String str)
boolean startsWith(String prefix)
boolean startsWith(String prefix, int toffset)
boolean endsWith(String suffix)
int indexOf(int ch)
int indexOf(int ch, int fromIndex)
int indexOf(String str)
int indexOf(String str, int fromIndex)
int lastIndexOf(int ch)
int lastIndexOf(int ch, int fromIndex)
int lastIndexOf(String str)
int lastIndexOf(String str, int fromIndex)

// Substring (returns CONCRETE_TYPE)
CONCRETE_TYPE substring(int beginIndex)
CONCRETE_TYPE substring(int beginIndex, int endIndex)

// Equality
boolean contentEquals(StringBuffer sb)
boolean contentEquals(CharSequence cs)
boolean equalsIgnoreCase(String anotherString)

// Conversion
String toString()
char[] toCharArray()
byte[] getBytes()
byte[] getBytes(String charsetName)
byte[] getBytes(Charset charset)

// Code points
int codePointAt(int index)
int codePointBefore(int index)
int codePointCount(int beginIndex, int endIndex)
int offsetByCodePoints(int index, int codePointOffset)
void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin)
```

---

## Amount/Money/Percentage API

### Amount
Class: `dk.trustworks.essentials.types.Amount extends BigDecimalType<Amount>`

```java
// Factory
Amount(BigDecimal value)
Amount(double value)
Amount(long value)
static Amount of(String value)
static Amount of(String value, MathContext mc)
static Amount of(BigDecimal value)
static Amount ofNullable(String value)
static Amount ofNullable(String value, MathContext mc)
static Amount ofNullable(BigDecimal value)
static Amount zero()  // Returns Amount.ZERO

// Constants
static final Amount ZERO

// Inherits all BigDecimalType operations
Amount add(Amount other)
Amount subtract(Amount other)
Amount multiply(int factor)
// ... (see BigDecimalType Operations)
```

### Money
Class: `dk.trustworks.essentials.types.Money implements Serializable, Comparable<Money>`

```java
// Constructors
Money()  // Framework-only, don't use
Money(Amount amount, CurrencyCode currency)

// Factory
static Money of(String amount, String currencyCode)
static Money of(BigDecimal amount, String currencyCode)
static Money of(String amount, CurrencyCode currencyCode)
static Money of(BigDecimal amount, CurrencyCode currencyCode)
static Money of(Amount amount, String currencyCode)
static Money of(Amount amount, CurrencyCode currencyCode)

// Accessors
Amount getAmount()
CurrencyCode getCurrency()

// Arithmetic (throws NotTheSameCurrenciesException if currencies differ)
Money add(Money augend)
Money add(Money augend, MathContext mc)
Money subtract(Money subtrahend)
Money subtract(Money subtrahend, MathContext mc)
Money multiply(Money multiplicand)
Money multiply(Money multiplicand, MathContext mc)
Money divide(Money divisor)
Money divide(Money divisor, int scale, RoundingMode rm)
Money divide(Money divisor, RoundingMode rm)
Money divide(Money divisor, MathContext mc)
Money remainder(Money divisor)
Money remainder(Money divisor, MathContext mc)

// Math
Money abs()
Money abs(MathContext mc)
Money negate()
Money negate(MathContext mc)
Money plus()
Money plus(MathContext mc)
Money pow(int n)
Money round(MathContext mc)

// Scale/precision
Money setScale(int newScale)
Money setScale(int newScale, RoundingMode rm)
int scale()
int precision()
int signum()

// Comparison (throws NotTheSameCurrenciesException if currencies differ)
int compareTo(Money compareToMoney)

// Exception
static class Money.NotTheSameCurrenciesException extends RuntimeException
```

### Percentage
Class: `dk.trustworks.essentials.types.Percentage extends BigDecimalType<Percentage>`

```java
// Constructors
Percentage(BigDecimal value)
Percentage(Number value)

// Factory
static Percentage from(String percent)       // "25%" or "0.25" -> 25%
static Percentage from(String percent, MathContext mc)
static Percentage from(BigDecimal percent)

// Constants
static final Percentage _100  // 100%
static final Percentage _0    // 0%

// Calculation
<T extends BigDecimalType<T>> T of(T amount)  // Calculate percentage of amount
BigDecimal of(BigDecimal amount)

// Inherits all BigDecimalType operations
String toString()  // Returns "25.00%"
```

**Example:**
```java
Amount base = Amount.of("100.00");
Percentage taxRate = Percentage.from("25%");        // From string with %
Amount taxAmount = taxRate.of(base);  // 25.00

Percentage discount = Percentage.of("0.15");        // From decimal (15%)
```

---

## Utility Types API

### LongRange
Class: `dk.trustworks.essentials.types.LongRange`

```java
// Fields
final long fromInclusive
final Long toInclusive  // null = open range

// Factory
static LongRange between(long fromInclusive, long toInclusive)  // Closed [from, to]
static LongRange from(long fromInclusive)                       // Open [from, ∞)
static LongRange from(long fromInclusive, long rangeLength)     // [from, from+len-1]
static LongRange only(long fromAndToInclusive)                  // [n, n]
static LongRange empty()                                        // [0, 0]

// Constants
static final LongRange EMPTY_RANGE

// Query
boolean isClosedRange()    // toInclusive != null
boolean isOpenRange()      // toInclusive == null
boolean covers(long value)
long getFromInclusive()
Long getToInclusive()

// Stream
LongStream stream()  // Closed range or infinite stream
```

### TimeWindow
Class: `dk.trustworks.essentials.types.TimeWindow`

```java
// Fields
final Instant fromInclusive
final Instant toExclusive  // null = open window

// Constructors
TimeWindow(Instant fromInclusive)                        // Open [from, ∞)
TimeWindow(Instant fromInclusive, Instant toExclusive)   // [from, to)

// Factory
static TimeWindow from(Instant fromInclusive)
static TimeWindow between(Instant fromInclusive, Instant toExclusive)

// Query
boolean isOpenTimeWindow()      // toExclusive == null
boolean isClosedTimeWindow()    // toExclusive != null
boolean covers(Instant timestamp)
Instant getFromInclusive()
Instant getToExclusive()

// Mutation (returns new instance)
TimeWindow close(Instant toExclusive)  // Close open or reclose window
```

---

## Kotlin Value Classes

Package: `dk.trustworks.essentials.types.kotlin`

Kotlin types use `@JvmInline value class` for zero-runtime-overhead.

| Kotlin Type | Interface |
|-------------|-----------|
| `String` | `StringValueType<SELF>` |
| `BigDecimal` | `BigDecimalValueType<SELF>` |
| `BigInteger` | `BigIntegerValueType<SELF>` |
| `Long` | `LongValueType<SELF>` |
| `Int` | `IntValueType<SELF>` |
| `Short` | `ShortValueType<SELF>` |
| `Byte` | `ByteValueType<SELF>` |
| `Double` | `DoubleValueType<SELF>` |
| `Float` | `FloatValueType<SELF>` |
| `Boolean` | `BooleanValueType<SELF>` |
| `Instant` | `InstantValueType<SELF>` |
| `LocalDate` | `LocalDateValueType<SELF>` |
| `LocalDateTime` | `LocalDateTimeValueType<SELF>` |
| `LocalTime` | `LocalTimeValueType<SELF>` |
| `OffsetDateTime` | `OffsetDateTimeValueType<SELF>` |
| `ZonedDateTime` | `ZonedDateTimeValueType<SELF>` |

**Built-in Kotlin types:** `Amount`, `CountryCode`

### Example
```kotlin
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun of(value: String) = OrderId(value)
        fun random() = OrderId("ORD-${RandomIdGenerator.generate()}")
    }
}

@JvmInline
value class Quantity(override val value: Int) : IntValueType<Quantity> {
    init {
        require(value >= 0) { "Quantity cannot be negative" }
    }

    companion object {
        fun of(value: Int) = Quantity(value)
        val ZERO = Quantity(0)
    }

    operator fun plus(other: Quantity) = Quantity(value + other.value)
}
```

---

## Integration Modules

| Framework | Module | LLM Doc |
|-----------|--------|---------|
| Jackson JSON | `types-jackson` | [LLM-types-jackson.md](LLM-types-jackson.md) |
| Spring Data MongoDB | `types-springdata-mongo` | [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md) |
| Spring Data JPA | `types-springdata-jpa` | [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md) |
| JDBI v3 | `types-jdbi` | [LLM-types-jdbi.md](LLM-types-jdbi.md) |
| Apache Avro | `types-avro` | [LLM-types-avro.md](LLM-types-avro.md) |
| Spring WebMvc/WebFlux | `types-spring-web` | [LLM-types-spring-web.md](LLM-types-spring-web.md) |

---

## Gotchas

- ⚠️ **Null rejection** - All `SingleValueType` constructors reject null values
- ⚠️ **Jackson 2.18+** - Requires explicit `String` constructor alongside `CharSequence` for `CharSequenceType`
- ⚠️ **Immutability** - All types immutable; operations return new instances
- ⚠️ **Money currency checks** - All `Money` operations throw `Money.NotTheSameCurrenciesException` if currencies differ
- ⚠️ **Percentage scale** - `Percentage` enforces minimum scale of 2
- ⚠️ **Generic instantiation** - Use `SingleValueType.from(value, Type.class)` for reflective instantiation
- ⚠️ **Identifier interface** - Optional marker; use for semantic searchability

---

## See Also

- [README.md](../types/README.md) - Full documentation with motivation
- [Integration overview](LLM-types-integrations.md)
- Tests: `types/src/test/java/dk/trustworks/essentials/types/`
