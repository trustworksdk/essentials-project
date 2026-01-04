# Types-Avro - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.types.avro`
- Purpose: Avro LogicalType serialization for `SingleValueType` implementations
- Dependencies: `avro` (provided scope)
- **Status**: WORK-IN-PROGRESS
- Pattern: 3-class system per type (LogicalType + Factory + Conversion)

## TOC
- [Core Architecture](#core-architecture)
- [Base Conversion Classes](#base-conversion-classes)
- [Built-in Types](#built-in-types)
- [API Patterns](#api-patterns)
- [Common Tasks](#common-tasks)
- [Maven Integration](#maven-integration)
- [Gotchas](#gotchas)
- [See Also](#see-also)

## Core Architecture

| Component | Role | Created At |
|-----------|------|------------|
| **LogicalType** | Schema validation (ensures correct Avro primitive) | Code generation |
| **LogicalTypeFactory** | Creates LogicalType from schema | Code generation |
| **Conversion** | Serializes/deserializes Java ↔ Avro primitive | Runtime |

**Flow:**
1. Schema defines `@logicalType("TypeName")` on Avro primitive
2. Factory creates LogicalType during code generation
3. Generated code uses semantic type instead of primitive
4. Conversion handles runtime ser/deser

## Base Conversion Classes

Each `SingleValueType` subtype extends a base conversion.

`SingleValueType` package: `dk.trustworks.essentials.types`
`LogicalType Class` package: `dk.trustworks.essentials.types.avro`
`Base Conversion` package: `dk.trustworks.essentials.types.avro`

| SingleValueType | LogicalType Class | Base Conversion | Avro Type | Methods |
|-----------------|-------------------|-----------------|-----------|---------|
| `CharSequenceType` | `CharSequenceTypeLogicalType` | `BaseCharSequenceConversion<T>` | `string` | `fromCharSequence`, `toCharSequence`, `fromBytes`, `toBytes` |
| `BigDecimalType` | `BigDecimalTypeLogicalType` | `BaseBigDecimalTypeConversion<T>` | `string` | `fromCharSequence`, `toCharSequence` |
| `BigDecimalType` | `BigDecimalTypeLogicalType` | `SingleConcreteBigDecimalTypeConversion` | `decimal` | `fromBytes`, `toBytes` |
| `IntegerType` | `IntegerTypeLogicalType` | `BaseIntegerTypeConversion<T>` | `int` | `fromInt`, `toInt` |
| `LongType` | `LongTypeLogicalType` | `BaseLongTypeConversion<T>` | `long` | `fromLong`, `toLong` |
| `DoubleType` | `DoubleTypeLogicalType` | `BaseDoubleTypeConversion<T>` | `double` | `fromDouble`, `toDouble` |
| `FloatType` | `FloatTypeLogicalType` | `BaseFloatTypeConversion<T>` | `float` | `fromFloat`, `toFloat` |
| `InstantType` | `InstantTypeLogicalType` | `BaseInstantTypeConversion<T>` | `long` | `fromLong`, `toLong` (millis since epoch) |
| `LocalDateTimeType` | `LocalDateTimeTypeLogicalType` | `BaseLocalDateTimeTypeConversion<T>` | `long` | `fromLong`, `toLong` (millis) |
| `LocalDateType` | `LocalDateTypeLogicalType` | `BaseLocalDateTypeConversion<T>` | `int` | `fromInt`, `toInt` (days) |
| `LocalTimeType` | `LocalTimeTypeLogicalType` | `BaseLocalTimeTypeConversion<T>` | `long` | `fromLong`, `toLong` (millis) |
| `OffsetDateTimeType` | `OffsetDateTimeTypeLogicalType` | `BaseOffsetDateTimeTypeConversion<T>` | `long` | `fromLong`, `toLong` (millis) |
| `ZonedDateTimeType` | `ZonedDateTimeTypeLogicalType` | `BaseZonedDateTimeTypeConversion<T>` | `long` | `fromLong`, `toLong` (millis) |

### JSR-310 Constraints
- **Timezone**: All temporal converters use `ZoneId.of("UTC")`
- **Precision**: Millisecond only; nanoseconds truncated

## Built-in Types

Ready-to-use conversions in `dk.trustworks.essentials.types.avro`:

| Type | LogicalType Name | Factory Class | Conversion Class |
|------|------------------|---------------|------------------|
| `Amount` | `Amount` | `AmountLogicalTypeFactory` | `AmountConversion` |
| `Percentage` | `Percentage` | `PercentageLogicalTypeFactory` | `PercentageConversion` |
| `CurrencyCode` | `CurrencyCode` | `CurrencyCodeLogicalTypeFactory` | `CurrencyCodeConversion` |
| `CountryCode` | `CountryCode` | `CountryCodeLogicalTypeFactory` | `CountryCodeConversion` |
| `EmailAddress` | `EmailAddress` | `EmailAddressLogicalTypeFactory` | `EmailAddressConversion` |

## API Patterns

### Pattern: BaseCharSequenceConversion

```java
package dk.trustworks.essentials.types.avro;

public abstract class BaseCharSequenceConversion<T extends CharSequenceType<T>>
    extends Conversion<T> {

    protected abstract LogicalType getLogicalType();

    @Override
    public final Schema getRecommendedSchema();

    @Override
    public final String getLogicalTypeName();

    @Override
    public T fromCharSequence(CharSequence value, Schema schema, LogicalType type);

    @Override
    public CharSequence toCharSequence(T value, Schema schema, LogicalType type);

    @Override
    public T fromBytes(ByteBuffer value, Schema schema, LogicalType type);

    @Override
    public ByteBuffer toBytes(T value, Schema schema, LogicalType type);
}
```

### Pattern: BaseLongTypeConversion

```java
package dk.trustworks.essentials.types.avro;

public abstract class BaseLongTypeConversion<T extends LongType<T>>
    extends Conversion<T> {

    protected abstract LogicalType getLogicalType();

    @Override
    public final Schema getRecommendedSchema();

    @Override
    public final String getLogicalTypeName();

    @Override
    public T fromLong(Long value, Schema schema, LogicalType type);

    @Override
    public Long toLong(T value, Schema schema, LogicalType type);
}
```

### Pattern: BaseInstantTypeConversion

```java
package dk.trustworks.essentials.types.avro;

public abstract class BaseInstantTypeConversion<T extends InstantType<T>>
    extends Conversion<T> {

    protected abstract LogicalType getLogicalType();

    @Override
    public final Schema getRecommendedSchema();

    @Override
    public final String getLogicalTypeName();

    @Override
    public T fromLong(Long value, Schema schema, LogicalType type);
    // Returns: SingleValueType.from(Instant.ofEpochMilli(value), getConvertedType())

    @Override
    public Long toLong(T value, Schema schema, LogicalType type);
    // Returns: value.value().toEpochMilli()
}
```

### Pattern: LogicalType

```java
package dk.trustworks.essentials.types.avro;

public class CharSequenceTypeLogicalType extends LogicalType {
    public CharSequenceTypeLogicalType(String logicalTypeName) {
        super(logicalTypeName);
    }

    @Override
    public void validate(Schema schema) {
        super.validate(schema);
        if (schema.getType() != Schema.Type.STRING) {
            throw new IllegalArgumentException("Can only be used with STRING type");
        }
    }
}
```

## Common Tasks

### Task: Create Custom CharSequenceType Support

**1. LogicalTypeFactory:**
```java
package com.example.types.avro;

public class OrderIdLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType ORDER_ID =
        new CharSequenceTypeLogicalType("OrderId");

    @Override
    public LogicalType fromSchema(Schema schema) { return ORDER_ID; }

    @Override
    public String getTypeName() { return ORDER_ID.getName(); }
}
```

**2. Conversion:**
```java
package com.example.types.avro;

public class OrderIdConversion extends BaseCharSequenceConversion<OrderId> {
    @Override
    public Class<OrderId> getConvertedType() { return OrderId.class; }

    @Override
    protected LogicalType getLogicalType() {
        return OrderIdLogicalTypeFactory.ORDER_ID;
    }
}
```

**3. Schema:**
```avro
record Order {
    @logicalType("OrderId")
    string id;
}
```

**Result:** Generated class has `OrderId id` field, not `String id`.

### Task: Create Custom LongType Support

**1. LogicalTypeFactory:**
```java
public class QuantityLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType QUANTITY = new LongTypeLogicalType("Quantity");

    @Override
    public LogicalType fromSchema(Schema schema) { return QUANTITY; }

    @Override
    public String getTypeName() { return QUANTITY.getName(); }
}
```

**2. Conversion:**
```java
public class QuantityConversion extends BaseLongTypeConversion<Quantity> {
    @Override
    public Class<Quantity> getConvertedType() { return Quantity.class; }

    @Override
    protected LogicalType getLogicalType() {
        return QuantityLogicalTypeFactory.QUANTITY;
    }
}
```

**3. Schema:**
```avro
record Order {
    @logicalType("Quantity")
    long quantity;
}
```

### Task: Create Custom InstantType Support

**1. LogicalTypeFactory:**
```java
public class LastUpdatedLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType LAST_UPDATED =
        new InstantTypeLogicalType("LastUpdated");

    @Override
    public LogicalType fromSchema(Schema schema) { return LAST_UPDATED; }

    @Override
    public String getTypeName() { return LAST_UPDATED.getName(); }
}
```

**2. Conversion:**
```java
public class LastUpdatedConversion extends BaseInstantTypeConversion<LastUpdated> {
    @Override
    public Class<LastUpdated> getConvertedType() { return LastUpdated.class; }

    @Override
    protected LogicalType getLogicalType() {
        return LastUpdatedLogicalTypeFactory.LAST_UPDATED;
    }
}
```

**3. Schema:**
```avro
record Order {
    @logicalType("LastUpdated")
    long lastUpdated;  // millis since epoch
}
```

## Maven Integration

### Plugin Configuration

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

                <!-- Register LogicalTypeFactories -->
                <customLogicalTypeFactories>
                    <!-- Built-in -->
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.AmountLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>dk.trustworks.essentials.types.avro.CurrencyCodeLogicalTypeFactory</logicalTypeFactory>
                    <!-- Custom -->
                    <logicalTypeFactory>com.example.OrderIdLogicalTypeFactory</logicalTypeFactory>
                    <logicalTypeFactory>com.example.QuantityLogicalTypeFactory</logicalTypeFactory>
                </customLogicalTypeFactories>

                <!-- Register Conversions -->
                <customConversions>
                    <!-- Built-in -->
                    <conversion>dk.trustworks.essentials.types.avro.AmountConversion</conversion>
                    <conversion>dk.trustworks.essentials.types.avro.CurrencyCodeConversion</conversion>
                    <!-- Custom -->
                    <conversion>com.example.OrderIdConversion</conversion>
                    <conversion>com.example.QuantityConversion</conversion>
                </customConversions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Usage Example

```java
// Create
Order order = Order.newBuilder()
    .setId(OrderId.of("ORD-123"))
    .setQuantity(Quantity.of(5L))
    .setTotalAmount(Amount.of("100.50"))
    .setCurrency(CurrencyCode.of("USD"))
    .setLastUpdated(LastUpdated.now())
    .build();

// Type-safe access - no casting
OrderId id = order.getId();
Quantity qty = order.getQuantity();
Amount total = order.getTotalAmount();
```

## Gotchas

- ⚠️ **3-class requirement** - Must create LogicalType, LogicalTypeFactory, and Conversion for each type
- ⚠️ **Primitive must match** - Avro primitive must match LogicalType expectation:
  - `CharSequenceType` → `string`
  - `LongType` → `long`
  - `IntegerType` → `int`
  - `InstantType` → `long` (millis since epoch)
- ⚠️ **Both registrations required** - Register BOTH `customLogicalTypeFactories` AND `customConversions` in plugin
- ⚠️ **UTC timezone** - JSR-310 converters default to `ZoneId.of("UTC")`; create instances with `Clock.systemUTC()`
- ⚠️ **Millisecond precision** - Temporal types truncate nanoseconds; use `.withNano(0)` for consistency:
  ```java
  LastUpdated.of(Instant.now(Clock.systemUTC()).with(ChronoField.NANO_OF_SECOND, 0))
  ```
- ⚠️ **Null safety** - All conversions return `null` for `null` input
- ⚠️ **Schema validation** - LogicalTypes validate primitive type at schema parse time

## See Also

- [README.md](../types-avro/README.md) - Full docs with detailed examples
- [CustomConversionsTest.java](../types-avro/src/test/java/dk/trustworks/essentials/types/avro/CustomConversionsTest.java) - Test patterns
- [LLM-types.md](LLM-types.md) - Core types module reference
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson serialization
- [LLM-types-jdbi.md](LLM-types-jdbi.md) - JDBI persistence
