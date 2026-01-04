# Types-Avro

> Apache Avro serialization and deserialization support for Essentials `types` module

This module enables seamless Avro serialization/deserialization of `SingleValueType` implementations using Avro's LogicalType system. Each semantic type becomes a strongly-typed field in generated Avro classes.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types-avro.md](../LLM/LLM-types-avro.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [How It Works](#how-it-works)
- [Base Classes](#base-classes)
- [Built-in Types](#built-in-types)
- [Creating Custom Types](#creating-custom-types)
- [Gotchas](#gotchas)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-avro</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.types.avro`

Avro integration requires three components for each semantic type:
1. **LogicalType** - Defines the logical type name for schema validation
2. **LogicalTypeFactory** - Creates LogicalType instances from schemas
3. **Conversion** - Handles serialization/deserialization

### Avro IDL Example

The module supports all Avro primitive types:

```avro
@namespace("com.example.orders")
protocol OrderProtocol {
  record Order {
      // String-based types (CharSequenceType, BigDecimalType)
      @logicalType("OrderId")
      string                   id;
      @logicalType("Amount")
      string                   totalAmountWithoutSalesTax;
      @logicalType("CurrencyCode")
      string                   currency;
      @logicalType("CountryCode")
      string                   country;
      @logicalType("Percentage")
      string                   salesTax;
      @logicalType("EmailAddress")
      string                   email;

      // Numeric types (IntegerType, LongType, DoubleType, FloatType)
      @logicalType("Quantity")
      int                      quantity;
      @logicalType("SequenceNumber")
      long                     sequenceNumber;
      @logicalType("Weight")
      double                   weight;
      @logicalType("Rating")
      float                    rating;

      // JSR-310 temporal types
      @logicalType("LastUpdated")
      long                     lastUpdated;
      @logicalType("DueDate")
      int                      dueDate;
      @logicalType("Created")
      long                     created;
  }
}
```

### Maven Plugin Configuration

```xml
<plugin>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro-maven-plugin</artifactId>
    <version>${avro.version}</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals>
                <goal>idl-protocol</goal>
            </goals>
            <configuration>
                <stringType>String</stringType>
                <enableDecimalLogicalType>false</enableDecimalLogicalType>
                <customLogicalTypeFactories>
                    <!-- Built-in string-based types -->
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.AmountLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.CurrencyCodeLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.CountryCodeLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.PercentageLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.EmailAddressLogicalTypeFactory</logicalTypeFactory>
                    <!-- Custom types (see "Creating Custom Types" section) -->
                    <logicalTypeFactory>com.myproject.types.avro.OrderIdLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.QuantityLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.SequenceNumberLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.WeightLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.RatingLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.LastUpdatedLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.DueDateLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.myproject.types.avro.CreatedLogicalTypeFactory</logicalTypeFactory>
                </customLogicalTypeFactories>
                <customConversions>
                    <!-- Built-in string-based types -->
                    <conversion>dk.trustworks.essentials.types.avro.AmountConversion</conversion>
                    <conversion>dk.trustworks.essentials.types.avro.CurrencyCodeConversion</conversion>
                    <conversion>dk.trustworks.essentials.types.avro.CountryCodeConversion</conversion>
                    <conversion>dk.trustworks.essentials.types.avro.PercentageConversion</conversion>
                    <conversion>dk.trustworks.essentials.types.avro.EmailAddressConversion</conversion>
                    <!-- Custom types -->
                    <conversion>com.myproject.types.avro.OrderIdConversion</conversion>
                    <conversion>com.myproject.types.avro.QuantityConversion</conversion>
                    <conversion>com.myproject.types.avro.SequenceNumberConversion</conversion>
                    <conversion>com.myproject.types.avro.WeightConversion</conversion>
                    <conversion>com.myproject.types.avro.RatingConversion</conversion>
                    <conversion>com.myproject.types.avro.LastUpdatedConversion</conversion>
                    <conversion>com.myproject.types.avro.DueDateConversion</conversion>
                    <conversion>com.myproject.types.avro.CreatedConversion</conversion>
                </customConversions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Generated Java Class

```java
@org.apache.avro.specific.AvroGenerated
public class Order extends SpecificRecordBase implements SpecificRecord {
    // String-based types
    private com.myproject.types.OrderId id;
    private dk.trustworks.essentials.types.Amount totalAmountWithoutSalesTax;
    private dk.trustworks.essentials.types.CurrencyCode currency;
    private dk.trustworks.essentials.types.CountryCode country;
    private dk.trustworks.essentials.types.Percentage salesTax;
    private dk.trustworks.essentials.types.EmailAddress email;
    // Numeric types
    private com.myproject.types.Quantity quantity;
    private com.myproject.types.SequenceNumber sequenceNumber;
    private com.myproject.types.Weight weight;
    private com.myproject.types.Rating rating;
    // Temporal types
    private com.myproject.types.LastUpdated lastUpdated;
    private com.myproject.types.DueDate dueDate;
    private com.myproject.types.Created created;
    // ...
}
```

### Using the Generated Class

```java
// Create order with semantic types
Order order = Order.newBuilder()
    // String-based types
    .setId(OrderId.of("ORD-123"))
    .setTotalAmountWithoutSalesTax(Amount.of("100.50"))
    .setCurrency(CurrencyCode.of("USD"))
    .setCountry(CountryCode.of("US"))
    .setSalesTax(Percentage.from("8.25"))
    .setEmail(EmailAddress.of("customer@example.com"))
    // Numeric types
    .setQuantity(Quantity.of(5))
    .setSequenceNumber(SequenceNumber.of(1001L))
    .setWeight(Weight.of(2.5))
    .setRating(Rating.of(4.5f))
    // Temporal types
    .setLastUpdated(LastUpdated.now())
    .setDueDate(DueDate.now())
    .setCreated(Created.now())
    .build();

// Serialize
byte[] bytes = serialize(order);

// Deserialize
Order restored = deserialize(bytes, Order.class);

// Type-safe access - no casting needed
OrderId orderId = restored.getId();
Quantity qty = restored.getQuantity();
Weight weight = restored.getWeight();
```

**Learn more:** See [CustomConversionsTest.java](src/test/java/dk/trustworks/essentials/types/avro/CustomConversionsTest.java)

## How It Works

Avro LogicalTypes allow mapping primitive Avro types to custom Java types:

| Component | Purpose |
|-----------|---------|
| **LogicalType** | Validates schema (e.g., ensures `string` primitive) |
| **LogicalTypeFactory** | Creates LogicalType from schema during code generation |
| **Conversion** | Converts between Java type and Avro primitive |

**Flow:**
1. Schema defines `@logicalType("MyType")` annotation on a primitive
2. LogicalTypeFactory creates the LogicalType during code generation
3. Generated code uses your semantic type instead of primitive
4. At runtime, Conversion handles serialization/deserialization

## Base Classes

Extend these base classes for your custom types:

| SingleValueType | LogicalType Class | Conversion Class | Avro Primitive |
|-----------------|-------------------|------------------|----------------|
| `CharSequenceType` | `CharSequenceTypeLogicalType` | `BaseCharSequenceConversion<T>` | `string` |
| `BigDecimalType` | `BigDecimalTypeLogicalType` | `BaseBigDecimalTypeConversion<T>` | `string` |
| `BigDecimalType` | `BigDecimalTypeLogicalType` | `SingleConcreteBigDecimalTypeConversion` | `decimal` |
| `IntegerType` | `IntegerTypeLogicalType` | `BaseIntegerTypeConversion<T>` | `int` |
| `LongType` | `LongTypeLogicalType` | `BaseLongTypeConversion<T>` | `long` |
| `DoubleType` | `DoubleTypeLogicalType` | `BaseDoubleTypeConversion<T>` | `double` |
| `FloatType` | `FloatTypeLogicalType` | `BaseFloatTypeConversion<T>` | `float` |
| `InstantType` | `InstantTypeLogicalType` | `BaseInstantTypeConversion<T>` | `long` |
| `LocalDateTimeType` | `LocalDateTimeTypeLogicalType` | `BaseLocalDateTimeTypeConversion<T>` | `long` |
| `LocalDateType` | `LocalDateTypeLogicalType` | `BaseLocalDateTypeConversion<T>` | `int` |
| `LocalTimeType` | `LocalTimeTypeLogicalType` | `BaseLocalTimeTypeConversion<T>` | `long` |
| `OffsetDateTimeType` | `OffsetDateTimeTypeLogicalType` | `BaseOffsetDateTimeTypeConversion<T>` | `long` |
| `ZonedDateTimeType` | `ZonedDateTimeTypeLogicalType` | `BaseZonedDateTimeTypeConversion<T>` | `long` |

### JSR-310 Caveats

- **UTC Zone**: All JSR-310 converters use `ZoneId.of("UTC")` by default. Create instances with `Clock.systemUTC()`.
- **Millisecond Precision**: `LocalDateTimeType`, `OffsetDateTimeType`, and `ZonedDateTimeType` only support millisecond precision. Use `.withNano(0)` or `.with(ChronoField.NANO_OF_SECOND, 0)`.

## Built-in Types

Ready-to-use LogicalTypeFactory and Conversion for common Essentials types:

| Type | LogicalType Name | LogicalTypeFactory | Conversion |
|------|------------------|-------------------|------------|
| `Amount` | `Amount` | `AmountLogicalTypeFactory` | `AmountConversion` |
| `Percentage` | `Percentage` | `PercentageLogicalTypeFactory` | `PercentageConversion` |
| `CurrencyCode` | `CurrencyCode` | `CurrencyCodeLogicalTypeFactory` | `CurrencyCodeConversion` |
| `CountryCode` | `CountryCode` | `CountryCodeLogicalTypeFactory` | `CountryCodeConversion` |
| `EmailAddress` | `EmailAddress` | `EmailAddressLogicalTypeFactory` | `EmailAddressConversion` |

### Example: InstantType

```java
// Type definition
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

// LogicalTypeFactory
public class LastUpdatedLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType LAST_UPDATED = new InstantTypeLogicalType("LastUpdated");

    @Override
    public LogicalType fromSchema(Schema schema) {
        return LAST_UPDATED;
    }

    @Override
    public String getTypeName() {
        return LAST_UPDATED.getName();
    }
}

// Conversion
public class LastUpdatedConversion extends BaseInstantTypeConversion<LastUpdated> {
    @Override
    public Class<LastUpdated> getConvertedType() {
        return LastUpdated.class;
    }

    @Override
    protected LogicalType getLogicalType() {
        return LastUpdatedLogicalTypeFactory.LAST_UPDATED;
    }
}
```

## Creating Custom Types

To add Avro support for your own `SingleValueType`:

### Step 1: Create Your Type

```java
package com.myproject.types;

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
}
```

### Step 2: Create LogicalTypeFactory

```java
package com.myproject.types.avro;

public class OrderIdLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType ORDER_ID = new CharSequenceTypeLogicalType("OrderId");

    @Override
    public LogicalType fromSchema(Schema schema) {
        return ORDER_ID;
    }

    @Override
    public String getTypeName() {
        return ORDER_ID.getName();
    }
}
```

### Step 3: Create Conversion

```java
package com.myproject.types.avro;

public class OrderIdConversion extends BaseCharSequenceConversion<OrderId> {
    @Override
    public Class<OrderId> getConvertedType() {
        return OrderId.class;
    }

    @Override
    protected LogicalType getLogicalType() {
        return OrderIdLogicalTypeFactory.ORDER_ID;
    }
}
```

### Step 4: Register with avro-maven-plugin

```xml
<customLogicalTypeFactories>
    <logicalTypeFactory>com.myproject.types.avro.OrderIdLogicalTypeFactory</logicalTypeFactory>
</customLogicalTypeFactories>
<customConversions>
    <conversion>com.myproject.types.avro.OrderIdConversion</conversion>
</customConversions>
```

### Step 5: Use in Avro Schema

```avro
record Order {
    @logicalType("OrderId")
    string id;
}
```

## Gotchas

- **Primitive must match** - The Avro primitive type must match what the LogicalType expects (e.g., `string` for `CharSequenceType`, `long` for `InstantType`)
- **Both factory AND conversion required** - Register both `customLogicalTypeFactories` and `customConversions` in plugin config
- **UTC timezone** - JSR-310 converters default to UTC; use `Clock.systemUTC()` when creating instances
- **Millisecond precision** - Temporal types lose nanosecond precision; use `.withNano(0)` for consistency
- **Null handling** - All conversions handle `null` values gracefully
- **Schema validation** - LogicalTypes validate the underlying primitive type at schema parse time

## See Also

- [LLM-types-avro.md](../LLM/LLM-types-avro.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson](../types-jackson) - Jackson serialization for types
