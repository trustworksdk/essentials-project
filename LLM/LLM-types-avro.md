# Types-Avro - LLM Reference

> Token-efficient reference for Avro serialization of Essentials types. For explanations see [README.md](../types-avro/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types.avro`
- Purpose: Avro LogicalType serialization for `SingleValueType` implementations
- Dependencies: `avro` (provided scope)
- Status: WORK-IN-PROGRESS
- Pattern: 3-class system per type (LogicalType + LogicalTypeFactory + Conversion)

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-avro</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, temporal types from [types](./LLM-types.md)

## TOC
- [Core Architecture](#core-architecture)
- [Base Classes](#base-classes)
- [Built-in Types](#built-in-types)
- [Schema Examples](#schema-examples)
- [Custom Type Pattern](#custom-type-pattern)
- [Maven Configuration](#maven-configuration)
- [Gotchas](#gotchas)

---

## Core Architecture

| Component | Purpose | Phase |
|-----------|---------|-------|
| `LogicalType` | Schema validation (ensures correct Avro primitive) | Code generation |
| `LogicalTypeFactory` | Creates LogicalType from schema | Code generation |
| `Conversion` | Serializes/deserializes Java <-> Avro primitive | Runtime |

**Flow:** Schema `@logicalType("X")` -> Factory creates LogicalType -> Generated code uses semantic type -> Conversion handles runtime ser/deser

---

## Base Classes

Base package: `dk.trustworks.essentials.types.avro`

### LogicalType Classes

| SingleValueType Base | LogicalType Class | Validates Avro Type |
|---------------------|-------------------|---------------------|
| `CharSequenceType` | `CharSequenceTypeLogicalType` | `string` |
| `BigDecimalType` | `BigDecimalTypeLogicalType` | `string` or `decimal` |
| `IntegerType` | `IntegerTypeLogicalType` | `int` |
| `LongType` | `LongTypeLogicalType` | `long` |
| `DoubleType` | `DoubleTypeLogicalType` | `double` |
| `FloatType` | `FloatTypeLogicalType` | `float` |
| `InstantType` | `InstantTypeLogicalType` | `long` |
| `LocalDateTimeType` | `LocalDateTimeTypeLogicalType` | `long` |
| `LocalDateType` | `LocalDateTypeLogicalType` | `int` |
| `LocalTimeType` | `LocalTimeTypeLogicalType` | `long` |
| `OffsetDateTimeType` | `OffsetDateTimeTypeLogicalType` | `long` |
| `ZonedDateTimeType` | `ZonedDateTimeTypeLogicalType` | `long` |

### Conversion Base Classes

| SingleValueType Base | Conversion Base | Key Methods |
|---------------------|-----------------|-------------|
| `CharSequenceType<T>` | `BaseCharSequenceConversion<T>` | `fromCharSequence`, `toCharSequence` |
| `BigDecimalType<T>` | `BaseBigDecimalTypeConversion<T>` | `fromCharSequence`, `toCharSequence` |
| `BigDecimalType<T>` | `SingleConcreteBigDecimalTypeConversion<T>` | (uses Avro's decimal) |
| `IntegerType<T>` | `BaseIntegerTypeConversion<T>` | `fromInt`, `toInt` |
| `LongType<T>` | `BaseLongTypeConversion<T>` | `fromLong`, `toLong` |
| `DoubleType<T>` | `BaseDoubleTypeConversion<T>` | `fromDouble`, `toDouble` |
| `FloatType<T>` | `BaseFloatTypeConversion<T>` | `fromFloat`, `toFloat` |
| `InstantType<T>` | `BaseInstantTypeConversion<T>` | `fromLong`, `toLong` (millis) |
| `LocalDateTimeType<T>` | `BaseLocalDateTimeTypeConversion<T>` | `fromLong`, `toLong` (millis) |
| `LocalDateType<T>` | `BaseLocalDateTypeConversion<T>` | `fromInt`, `toInt` (days) |
| `LocalTimeType<T>` | `BaseLocalTimeTypeConversion<T>` | `fromLong`, `toLong` (millis) |
| `OffsetDateTimeType<T>` | `BaseOffsetDateTimeTypeConversion<T>` | `fromLong`, `toLong` (millis) |
| `ZonedDateTimeType<T>` | `BaseZonedDateTimeTypeConversion<T>` | `fromLong`, `toLong` (millis) |

**BigDecimalType Options:**
- `BaseBigDecimalTypeConversion` - Each field has its own logical type (requires LogicalTypeFactory)
- `SingleConcreteBigDecimalTypeConversion` - ALL `decimal` fields map to ONE type (e.g., `Amount`); no LogicalTypeFactory needed, uses Avro's native `decimal`; requires `enableDecimalLogicalType=true`

**JSR-310 Constraints:** UTC timezone, millisecond precision only (nanoseconds truncated)

---

## Built-in Types

Ready-to-use for common Essentials types:

| Type (`dk.trustworks.essentials.types`) | LogicalTypeFactory | Conversion |
|-----------------------------------------|-------------------|------------|
| `Amount` | `AmountLogicalTypeFactory` | `AmountConversion` |
| `Percentage` | `PercentageLogicalTypeFactory` | `PercentageConversion` |
| `CurrencyCode` | `CurrencyCodeLogicalTypeFactory` | `CurrencyCodeConversion` |
| `CountryCode` | `CountryCodeLogicalTypeFactory` | `CountryCodeConversion` |
| `EmailAddress` | `EmailAddressLogicalTypeFactory` | `EmailAddressConversion` |

---

## Schema Examples

### Avro IDL (.avdl)
```avdl
@namespace("com.example")
protocol Orders {
    record Order {
        @logicalType("OrderId") string id;
        @logicalType("Amount") string totalAmount;
        @logicalType("LastUpdated") long lastUpdated;
        decimal(9, 2) price;  // With SingleConcreteBigDecimalTypeConversion
    }
}
```

### Avro Schema (.avsc)
```json
{
  "type": "record",
  "name": "Order",
  "fields": [
    {"name": "id", "type": {"type": "string", "logicalType": "OrderId"}},
    {"name": "totalAmount", "type": {"type": "string", "logicalType": "Amount"}},
    {"name": "lastUpdated", "type": {"type": "long", "logicalType": "LastUpdated"}}
  ]
}
```

---

## Custom Type Pattern

### CharSequenceType Example

```java
// 1. LogicalTypeFactory
import dk.trustworks.essentials.types.avro.CharSequenceTypeLogicalType;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;

public class OrderIdLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType ORDER_ID = new CharSequenceTypeLogicalType("OrderId");

    @Override
    public LogicalType fromSchema(Schema schema) { return ORDER_ID; }

    @Override
    public String getTypeName() { return ORDER_ID.getName(); }
}

// 2. Conversion
import dk.trustworks.essentials.types.avro.BaseCharSequenceConversion;

public class OrderIdConversion extends BaseCharSequenceConversion<OrderId> {
    @Override
    public Class<OrderId> getConvertedType() { return OrderId.class; }

    @Override
    protected LogicalType getLogicalType() { return OrderIdLogicalTypeFactory.ORDER_ID; }
}

// 3. Schema
// @logicalType("OrderId") string id;
```

### InstantType Example

```java
// 1. LogicalTypeFactory
import dk.trustworks.essentials.types.avro.InstantTypeLogicalType;

public class LastUpdatedLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType LAST_UPDATED = new InstantTypeLogicalType("LastUpdated");

    @Override
    public LogicalType fromSchema(Schema schema) { return LAST_UPDATED; }

    @Override
    public String getTypeName() { return LAST_UPDATED.getName(); }
}

// 2. Conversion
import dk.trustworks.essentials.types.avro.BaseInstantTypeConversion;

public class LastUpdatedConversion extends BaseInstantTypeConversion<LastUpdated> {
    @Override
    public Class<LastUpdated> getConvertedType() { return LastUpdated.class; }

    @Override
    protected LogicalType getLogicalType() { return LastUpdatedLogicalTypeFactory.LAST_UPDATED; }
}

// 3. Schema
// @logicalType("LastUpdated") long lastUpdated;  // millis since epoch
```

**Same pattern** for: `LongType`, `IntegerType`, `DoubleType`, `FloatType`, and other temporal types.

---

## Maven Configuration

```xml
<plugin>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro-maven-plugin</artifactId>
    <configuration>
        <customLogicalTypeFactories>
            <!-- Built-in -->
            <logicalTypeFactory>dk.trustworks.essentials.types.avro.AmountLogicalTypeFactory</logicalTypeFactory>
            <!-- Custom -->
            <logicalTypeFactory>com.example.OrderIdLogicalTypeFactory</logicalTypeFactory>
        </customLogicalTypeFactories>
        <customConversions>
            <!-- Built-in -->
            <conversion>dk.trustworks.essentials.types.avro.AmountConversion</conversion>
            <!-- Custom -->
            <conversion>com.example.OrderIdConversion</conversion>
        </customConversions>
    </configuration>
</plugin>
```

See [README Maven Configuration](../types-avro/README.md#maven-plugin-configuration) for full example.

---

## Gotchas

- **3-class requirement** - Must create LogicalType, LogicalTypeFactory, and Conversion for each type
- **Primitive must match** - Avro primitive must match LogicalType expectation:
  - `CharSequenceType` -> `string`
  - `LongType`, `InstantType` -> `long`
  - `IntegerType`, `LocalDateType` -> `int`
  - `DoubleType` -> `double`, `FloatType` -> `float`
- **Both registrations required** - Register BOTH `customLogicalTypeFactories` AND `customConversions`
- **UTC timezone** - JSR-310 converters use `ZoneId.of("UTC")`; create with `Clock.systemUTC()`
- **Millisecond precision** - Temporal types truncate nanoseconds; use `.withNano(0)` for consistency
- **Null safety** - All conversions return `null` for `null` input

---

## See Also

- [README.md](../types-avro/README.md) - Full documentation with detailed examples
- [LLM-types.md](LLM-types.md) - Core types module (`SingleValueType` hierarchy)
- [CustomConversionsTest.java](../types-avro/src/test/java/dk/trustworks/essentials/types/avro/CustomConversionsTest.java) - Usage examples
