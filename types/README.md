# Essentials Types

Essentials is a set of Java 17+ building blocks built from the ground up to have minimal dependencies.
The philosophy is to provide high-level, strongly-typed building blocks that integrate easily with popular frameworks (Jackson, Spring Boot, Spring Data, JPA, etc.).

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types.md](../LLM/LLM-types.md)

## Table of Contents
- [Overview](#overview)
- [The Problem: Primitive Obsession](#the-problem-primitive-obsession)
- [What Are Semantic Types?](#what-are-semantic-types)
- [Built-in Types](#built-in-types)
- [SingleValueType Hierarchy](#singlevaluetype-hierarchy)
- [Creating Custom Types](#creating-custom-types)
- [Framework Integration](#framework-integration)
- [See Also](#see-also)

## Overview

This module provides semantic types that eliminate primitive obsession and create self-documenting, type-safe code.

**Maven dependency:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependency** (provided scope):
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>
```

## The Problem: Primitive Obsession

Consider this typical command:

```java
public class CreateOrder {
    public final String               id;                        // What ID?
    public final BigDecimal           totalAmountWithoutSalesTax;// Currency?
    public final String               currency;                  // ISO format?
    public final BigDecimal           salesTax;                  // Percentage or amount?
    public final Map<String, Integer> orderLines;                // ProductId -> Quantity?
}
```

![Ambiguous Command](media/strongly_typed_properties.png)

With semantic types, the code becomes self-documenting:

```java
public class CreateOrder {
    public final OrderId                  id;
    public final Amount                   totalAmountWithoutSalesTax;
    public final CurrencyCode             currency;
    public final Percentage               salesTax;
    public final Map<ProductId, Quantity> orderLines;
}
```

## What Are Semantic Types?

A **Semantic Type** (also called a `SingleValueType`) is a strongly-typed wrapper around a primitive value that carries domain meaning.  
Instead of using raw `String`, `Long`, or `BigDecimal`, you create purpose-built types like `OrderId`, `CustomerId`, `Amount`, or `Quantity`.

### Why Use Semantic Types?

| Benefit | Description |
|---------|-------------|
| **Type Safety** | The compiler prevents mixing incompatible values. You can't accidentally pass a `CustomerId` where an `OrderId` is expected. |
| **Self-Documenting** | Method signatures like `findOrder(OrderId id)` are clearer than `findOrder(String id)`. |
| **IDE Support** | Find all usages of `OrderId` across your codebase instantly. |
| **Validation** | Enforce invariants in the constructor - an `EmailAddress` is always valid. |
| **Encapsulated Logic** | Add domain methods like `Quantity.add()` or `Amount.multiply()`. |

### How Easy Are They to Create?

A minimal semantic type requires just **4 lines of code**:

```java
public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) { super(value); }
    // Required for Jackson 2.18+: String constructor
    public OrderId(String value) {
        super(value);
    }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
}
```

That's it. You now have:
- Null-safety (constructor rejects null)
- Proper `equals()`, `hashCode()`, and `toString()`
- `Comparable` implementation
- Framework integration ready (Jackson, JPA, MongoDB, etc.)

Add validation or domain logic as needed:

```java
public class Quantity extends IntegerType<Quantity> {
    public Quantity(Integer value) {
        super(value);
        FailFast.requireTrue(value >= 0, "Quantity cannot be negative");
    }

    public static Quantity of(int value) { return new Quantity(value); }

    public Quantity add(Quantity other) {
        return new Quantity(this.value() + other.value());
    }
}
```

## Built-in Types

Base package: `dk.trustworks.essentials.types`

### Money & Currency Types

| Type | Description |
|------|-------------|
| `Amount` | Currency-agnostic monetary amount (extends `BigDecimalType`) |
| `CurrencyCode` | ISO-4217 3-character currency code with 140+ static constants |
| `Money` | Amount + CurrencyCode combined - enforces same-currency operations |
| `Percentage` | Percentage with calculations (`percentageValue.of(amount)`) |

**Amount - Currency-Agnostic Monetary Values:**
```java
// Create amounts
Amount baseAmount = Amount.of("199.99");
Amount discount = Amount.of(new BigDecimal("29.99"));

// Arithmetic operations
Amount total = baseAmount.add(Amount.of("10.00"));     // 209.99
Amount discounted = baseAmount.subtract(discount);      // 170.00
Amount doubled = baseAmount.multiply(2);                // 399.98

// Comparisons (from NumberType)
boolean isExpensive = baseAmount.isGreaterThan(Amount.of("100.00"));
boolean isAffordable = baseAmount.isLessThanOrEqualTo(Amount.of("50.00"));
```

**Percentage - Calculate Percentages:**
```java
Percentage taxRate = Percentage.from("25%");        // From string with %
Percentage discount = Percentage.of("0.15");        // From decimal (15%)

Amount basePrice = Amount.of("100.00");
Amount taxAmount = taxRate.of(basePrice);           // 25.00
Amount finalPrice = basePrice.add(taxAmount);       // 125.00
```

**Money - Currency-Aware Operations:**
```java
Money usdAmount = Money.of("99.99", CurrencyCode.USD);
Money eurAmount = Money.of("85.50", CurrencyCode.EUR);

// Same currency - works
Money total = usdAmount.add(Money.of("10.01", CurrencyCode.USD));  // 110.00 USD

// Different currencies - throws IllegalArgumentException
Money invalid = usdAmount.add(eurAmount);  // Cannot add USD and EUR!
```

### Validation Types

| Type | Description |
|------|-------------|
| `CountryCode` | Validated ISO-3166 2-character country codes |
| `EmailAddress` | Email with pluggable validation (default: non-validating) |

**Country and Currency Codes with Validation:**
```java
// ISO-3166-2 country codes with validation
CountryCode usa = CountryCode.of("US");
CountryCode denmark = CountryCode.of("DK");
CountryCode germany = CountryCode.of("DE");

// Invalid codes throw exceptions
try {
    CountryCode invalid = CountryCode.of("??");
} catch (IllegalArgumentException e) {
    // Invalid ISO-3166-2 country code
}

// ----------------------------------------------------------------

// ISO-4217 currency codes with validation
CurrencyCode usd = CurrencyCode.of("USD");
CurrencyCode eur = CurrencyCode.of("EUR");
CurrencyCode gbp = CurrencyCode.of("GBP");

// Built-in common currencies
CurrencyCode dollar = CurrencyCode.USD;
CurrencyCode euro = CurrencyCode.EUR;

// Validation prevents invalid codes
try {
    CurrencyCode invalid = CurrencyCode.of("XYZ");
} catch (IllegalArgumentException e) {
    // Invalid ISO-4217 currency code
}
```

**Email Address with Validation:**
```java
// Create
EmailAddress valid = EmailAddress.of("user@example.com");

// Per default EmailAddress uses the NonValidatingEmailAddressValidator - but can override with your own EmailAddressValidator to have email address format validated
EmailAddress.setValidator(new MyEmailAddressValidator());
try {
    EmailAddress invalid = EmailAddress.of("not-an-email");
} catch (IllegalArgumentException e) {
    // Invalid email format
}
```

### Utility Types

| Type | Description |
|------|-------------|
| `LongRange` | Range of Long values (closed or open), with stream support |
| `TimeWindow` | Time period with Instant (inclusive start, exclusive end) |

```java
// LongRange - useful for pagination, batch processing
LongRange closed = LongRange.between(1, 100);  // [1, 100]
LongRange open = LongRange.from(1);            // [1, infinity)
closed.covers(50);  // true
closed.stream().forEach(n -> process(n));

// TimeWindow - useful for time-based queries
TimeWindow window = TimeWindow.between(startTime, endTime);
TimeWindow openEnded = TimeWindow.from(startTime);  // [startTime, infinity)
window.covers(Instant.now());  // true/false
TimeWindow extended = openEnded.close(newEndTime);  // Close an open window
```

## SingleValueType Hierarchy

A `SingleValueType` encapsulates a **single** non-null value:

| Java Type                 | Base Class              | Purpose                              |
|---------------------------|-------------------------|--------------------------------------|
| `String`/`CharSequence`   | `CharSequenceType<T>`   | IDs, names, codes                    |
| `BigDecimal`              | `BigDecimalType<T>`     | Monetary amounts, percentages        |
| `BigInteger`              | `BigIntegerType<T>`     | Large integers, cryptographic values |
| `Long`                    | `LongType<T>`           | Numeric identifiers, timestamps      |
| `Integer`                 | `IntegerType<T>`        | Counters, quantities                 |
| `Short`                   | `ShortType<T>`          | Small numeric values, flags          |
| `Byte`                    | `ByteType<T>`           | Small integers, status codes         |
| `Double`                  | `DoubleType<T>`         | Measurements, rates                  |
| `Float`                   | `FloatType<T>`          | Single-precision measurements        |
| `Boolean`                 | `BooleanType<T>`        | Flags, states                        |
| `Instant`                 | `InstantType<T>`        | UTC timestamps                       |
| `LocalDate`               | `LocalDateType<T>`      | Dates without time                   |
| `LocalDateTime`           | `LocalDateTimeType<T>`  | Date-time without timezone           |
| `LocalTime`               | `LocalTimeType<T>`      | Time without date or timezone        |
| `OffsetDateTime`          | `OffsetDateTimeType<T>` | Date-time with UTC offset            |
| `ZonedDateTime`           | `ZonedDateTimeType<T>`  | Date-time with timezone              |

Base package: `dk.trustworks.essentials.types`

## Creating Custom Types

### String-Based Identifiers

> **Note:** Implementing `Identifier` is optional - it's a marker interface to make searching for identifiers easier.

```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required for Jackson 2.18+

    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.generate()); }
}

// Usage
OrderId orderId = OrderId.of("ORD-12345");
OrderId randomId = OrderId.random();
```

### Numeric Identifiers

```java
public class ProductSequence extends LongType<ProductSequence> implements Identifier {
    public ProductSequence(Long value) { super(value); }

    public static ProductSequence of(long value) { return new ProductSequence(value); }

    public ProductSequence next() { return new ProductSequence(value() + 1); }
}

// Usage
ProductSequence seq = ProductSequence.of(1000);
ProductSequence nextSeq = seq.next();  // 1001
```

### Validated Types with Business Logic

```java
public class Quantity extends IntegerType<Quantity> {
    public Quantity(Integer value) {
        super(value);
        FailFast.requireTrue(value >= 0, msg("Quantity cannot be negative: '{}'", value));
    }

    public static Quantity of(int value) { return new Quantity(value); }
    public static Quantity zero() { return new Quantity(0); }

    public Quantity add(Quantity other) { return new Quantity(this.value() + other.value()); }
    public Quantity multiply(int factor) { return new Quantity(this.value() * factor); }
    public boolean isPositive() { return value() > 0; }
}
```

### Temporal Types

```java
public class CreatedAt extends InstantType<CreatedAt> {
    public CreatedAt(Instant value) { super(value); }

    public static CreatedAt now() { return new CreatedAt(Instant.now()); }
    public static CreatedAt of(Instant instant) { return new CreatedAt(instant); }

    public boolean isBefore(CreatedAt other) { return value().isBefore(other.value()); }
    public Duration timeSince() { return Duration.between(value(), Instant.now()); }
}
```

### Date Types with Validation

```java
public class BirthDate extends LocalDateType<BirthDate> {
    public BirthDate(LocalDate value) {
        super(value);
        var now = LocalDate.now();
        FailFast.requireTrue(value.isBefore(now), "Birth date cannot be in the future");
        FailFast.requireTrue(value.isAfter(now.minusYears(150)), "Birth date cannot be more than 150 years ago");
    }

    public static BirthDate of(int year, int month, int day) {
        return new BirthDate(LocalDate.of(year, month, day));
    }

    public int getAge() { return Period.between(value(), LocalDate.now()).getYears(); }
}
```

**Generic instantiation** (useful for frameworks):
```java
OrderId id = SingleValueType.from("abc789", OrderId.class);
```

### Kotlin Value Class Integration

Base package: `dk.trustworks.essentials.types.kotlin`  
Kotlin types use `@JvmInline` value classes for zero-runtime-overhead:

```kotlin
@JvmInline
value class DocumentId(override val value: String) : StringValueType<DocumentId> {
    companion object {
        fun of(value: String) = DocumentId(value)
        fun random() = DocumentId(RandomIdGenerator.generate())
    }
}
```

**Kotlin Implementation Interfaces:**

| Kotlin Type               | Interface                       | Purpose                              |
|---------------------------|---------------------------------|--------------------------------------|
| `String`                  | `StringValueType<SELF>`         | IDs, names, codes                    |
| `BigDecimal`              | `BigDecimalValueType<SELF>`     | Monetary amounts, percentages        |
| `BigInteger`              | `BigIntegerValueType<SELF>`     | Large integers, cryptographic values |
| `Long`                    | `LongValueType<SELF>`           | Numeric identifiers, timestamps      |
| `Int`                     | `IntValueType<SELF>`            | Counters, quantities                 |
| `Short`                   | `ShortValueType<SELF>`          | Small numeric values, flags          |
| `Byte`                    | `ByteValueType<SELF>`           | Small integers, status codes         |
| `Double`                  | `DoubleValueType<SELF>`         | Measurements, rates                  |
| `Float`                   | `FloatValueType<SELF>`          | Single-precision measurements        |
| `Boolean`                 | `BooleanValueType<SELF>`        | Flags, states                        |
| `Instant`                 | `InstantValueType<SELF>`        | UTC timestamps                       |
| `LocalDate`               | `LocalDateValueType<SELF>`      | Dates without time                   |
| `LocalDateTime`           | `LocalDateTimeValueType<SELF>`  | Date-time without timezone           |
| `LocalTime`               | `LocalTimeValueType<SELF>`      | Time without date or timezone        |
| `OffsetDateTime`          | `OffsetDateTimeValueType<SELF>` | Date-time with UTC offset            |
| `ZonedDateTime`           | `ZonedDateTimeValueType<SELF>`  | Date-time with timezone              |

Built-in Kotlin types: `Amount`, `CountryCode`

```kotlin
// Kotlin semantic types using value classes
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun of(value: String) = OrderId(value)
        fun random() = OrderId("ORD-${RandomIdGenerator.generate()}")
    }
}

@JvmInline
value class ProductPrice(override val value: BigDecimal) : BigDecimalValueType<ProductPrice> {
    init {
        require(value >= BigDecimal.ZERO) { "Price cannot be negative" }
    }
    
    companion object {
        fun of(value: String) = ProductPrice(BigDecimal(value))
        fun of(value: Double) = ProductPrice(BigDecimal.valueOf(value))
    }
    
    fun withDiscount(percentage: Percentage): ProductPrice {
        val discountAmount = percentage.of(Amount(value)).value
        return ProductPrice(value - discountAmount)
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
        val ONE = Quantity(1)
    }
    
    operator fun plus(other: Quantity) = Quantity(value + other.value)
    operator fun times(factor: Int) = Quantity(value * factor)
}
```

## Framework Integration

| Framework                      | Module |
|--------------------------------|--------|
| Jackson JSON                   | `types-jackson` |
| Spring Data MongoDB            | `types-springdata-mongo` |
| Spring Data JPA (experimental) | `types-springdata-jpa` |
| JDBI v3                        | `types-jdbi` |
| Apache Avro                    | `types-avro` |
| Spring WebMvc/WebFlux          | `types-spring-web` |

## See Also

- [LLM-types.md](../LLM/LLM-types.md) - Detailed API reference
- [types-jackson](../types-jackson/README.md) - Jackson serialization
- Tests: [src/test/java/dk/trustworks/essentials/types/](src/test/java/dk/trustworks/essentials/types/)
