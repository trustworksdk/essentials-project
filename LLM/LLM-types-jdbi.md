# Types-JDBI - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [types-jdbi/README.md](../types-jdbi/README.md).

## TOC
- [Quick Facts](#quick-facts)
- [Core Concepts](#core-concepts)
- [ArgumentFactory Bases](#argumentfactory-bases)
- [ColumnMapper Bases](#columnmapper-bases)
- [Built-in Implementations](#built-in-implementations)
- [API Signatures](#api-signatures)
- [Usage Patterns](#usage-patterns)
- [Gotchas](#gotchas)

## Quick Facts
- **Package**: `dk.trustworks.essentials.types.jdbi`
- **Purpose**: JDBI v3 integration for `SingleValueType` - ArgumentFactory (params) + ColumnMapper (results)
- **Deps**: `jdbi3-core` (provided scope - add to your project)
- **Pattern**: Extend empty base class for your type
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jdbi</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, all temporal types from [types](./LLM-types.md)

## Core Concepts

| Component | Interface | Purpose |
|-----------|-----------|---------|
| ArgumentFactory | `org.jdbi.v3.core.argument.ArgumentFactory<T>` | `SingleValueType` → JDBC param (INSERT/UPDATE/WHERE) |
| ColumnMapper | `org.jdbi.v3.core.mapper.ColumnMapper<T>` | DB column → `SingleValueType` (SELECT) |

**Workflow**: Create empty subclass → Register with `Jdbi` → Use in queries

## ArgumentFactory Bases

Base package: `dk.trustworks.essentials.types.jdbi`

| Base Type (`types` module) | ArgumentFactory Base | SQL Type |
|-----------------------------------------------|--------------------------------------------------------------|----------|
| `CharSequenceType<T>` | `CharSequenceTypeArgumentFactory<T>`                         | VARCHAR |
| `BigDecimalType<T>` | `BigDecimalTypeArgumentFactory<T>`                           | NUMERIC |
| `BigIntegerType<T>` | `BigIntegerTypeArgumentFactory<T>`                           | NUMERIC |
| `IntegerType<T>` | `IntegerTypeArgumentFactory<T>`                              | INTEGER |
| `LongType<T>` | `LongTypeArgumentFactory<T>`                                 | BIGINT |
| `ShortType<T>` | `ShortTypeArgumentFactory<T>`                                | SMALLINT |
| `ByteType<T>` | `ByteTypeArgumentFactory<T>`                                 | TINYINT |
| `DoubleType<T>` | `DoubleTypeArgumentFactory<T>`                               | DOUBLE |
| `FloatType<T>` | `FloatTypeArgumentFactory<T>`                                | FLOAT |
| `BooleanType<T>` | `BooleanTypeArgumentFactory<T>`                              | BOOLEAN |

**JSR-310 Temporal**:

| Base Type (`types` module)  | ArgumentFactory Base                  | SQL Type |
|-----------------------------------------------|----------------------------------------|----------|
| `InstantType<T>`                              | `InstantTypeArgumentFactory<T>`        | TIMESTAMP |
| `LocalDateTimeType<T>`                        | `LocalDateTimeTypeArgumentFactory<T>`  | TIMESTAMP |
| `LocalDateType<T>`                            | `LocalDateTypeArgumentFactory<T>`      | DATE |
| `LocalTimeType<T>`                            | `LocalTimeTypeArgumentFactory<T>`      | TIME |
| `OffsetDateTimeType<T>`                       | `OffsetDateTimeTypeArgumentFactory<T>` | TIMESTAMP_WITH_TIMEZONE |
| `ZonedDateTimeType<T>`                        | `ZonedDateTimeTypeArgumentFactory<T>`  | TIMESTAMP_WITH_TIMEZONE |

## ColumnMapper Bases

Base package: `dk.trustworks.essentials.types.jdbi`

| Base Type (`types` module) | ColumnMapper Base  |
|-----------------------------------------------|-----------------------------------|
| `CharSequenceType<T>` | `CharSequenceTypeColumnMapper<T>` |
| `BigDecimalType<T>` | `BigDecimalTypeColumnMapper<T>`   |
| `BigIntegerType<T>` | `BigIntegerTypeColumnMapper<T>`   |
| `IntegerType<T>` | `IntegerTypeColumnMapper<T>`      |
| `LongType<T>` | `LongTypeColumnMapper<T>`         |
| `ShortType<T>` | `ShortTypeColumnMapper<T>`        |
| `ByteType<T>` | `ByteTypeColumnMapper<T>`         |
| `DoubleType<T>` | `DoubleTypeColumnMapper<T>`       |
| `FloatType<T>` | `FloatTypeColumnMapper<T>`        |
| `BooleanType<T>` | `BooleanTypeColumnMapper<T>`      |

**JSR-310 Temporal**:

| Base Type (`types` module) | ColumnMapper Base  |
|-----------|-------------------|
| `InstantType<T>` | `InstantTypeColumnMapper<T>` |
| `LocalDateTimeType<T>` | `LocalDateTimeColumnMapper<T>` |
| `LocalDateType<T>` | `LocalDateTypeColumnMapper<T>` |
| `LocalTimeType<T>` | `LocalTimeTypeColumnMapper<T>` |
| `OffsetDateTimeType<T>` | `OffsetDateTimeTypeColumnMapper<T>` |
| `ZonedDateTimeType<T>` | `ZonedDateTimeTypeColumnMapper<T>` |

## Built-in Implementations

Ready-to-use for common types (package `dk.trustworks.essentials.types.jdbi`):

| Type (`dk.trustworks.essentials.types`) | ArgumentFactory | ColumnMapper |
|-----------------------------------------|----------------|--------------|
| `Amount` | `AmountArgumentFactory` | `AmountColumnMapper` |
| `Percentage` | `PercentageArgumentFactory` | `PercentageColumnMapper` |
| `CurrencyCode` | `CurrencyCodeArgumentFactory` | `CurrencyCodeColumnMapper` |
| `CountryCode` | `CountryCodeArgumentFactory` | `CountryCodeColumnMapper` |
| `EmailAddress` | `EmailAddressArgumentFactory` | `EmailAddressColumnMapper` |

## API Signatures

### CharSequenceTypeArgumentFactory

```java
package dk.trustworks.essentials.types.jdbi;

import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.argument.*;
import org.jdbi.v3.core.config.ConfigRegistry;

public abstract class CharSequenceTypeArgumentFactory<T extends CharSequenceType<T>>
    extends AbstractArgumentFactory<T> {

    public CharSequenceTypeArgumentFactory(); // Sets Types.VARCHAR

    @Override
    protected Argument build(T value, ConfigRegistry config);
    // Returns: (pos, stmt, ctx) -> stmt.setString(pos, value.toString())
}
```

### CharSequenceTypeColumnMapper

```java
package dk.trustworks.essentials.types.jdbi;

import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;
import java.sql.*;

public abstract class CharSequenceTypeColumnMapper<T extends CharSequenceType<T>>
    implements ColumnMapper<T> {

    // Auto-resolves generic type T via reflection
    public CharSequenceTypeColumnMapper();

    // Explicit type (avoids reflection)
    public CharSequenceTypeColumnMapper(Class<T> concreteType);

    @Override
    public T map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException;
    // Returns: null if column NULL, else SingleValueType.from(r.getString(col), concreteType)
}
```

### BigDecimalTypeArgumentFactory

```java
package dk.trustworks.essentials.types.jdbi;

import dk.trustworks.essentials.types.BigDecimalType;
import org.jdbi.v3.core.argument.*;
import org.jdbi.v3.core.config.ConfigRegistry;

public abstract class BigDecimalTypeArgumentFactory<T extends BigDecimalType<T>>
    extends AbstractArgumentFactory<T> {

    protected BigDecimalTypeArgumentFactory(); // Sets Types.NUMERIC

    @Override
    protected Argument build(T value, ConfigRegistry config);
    // Returns: (pos, stmt, ctx) -> stmt.setBigDecimal(pos, value.value())
}
```

**Same pattern** for: `BigIntegerType`, `IntegerType`, `LongType`, `ShortType`, `ByteType`, `DoubleType`, `FloatType`, `BooleanType`, and all JSR-310 temporal types.

### Jdbi Registration

```java
// org.jdbi.v3.core.Jdbi
public class Jdbi {
    void registerArgument(ArgumentFactory factory);
    void registerColumnMapper(ColumnMapper<?> mapper);
}
```

## Usage Patterns

### Create Custom Type Support

```java
// 1. Define type (in your codebase)
package com.example;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
}

// 2. Create empty ArgumentFactory
package com.example;

import dk.trustworks.essentials.types.jdbi.CharSequenceTypeArgumentFactory;

public class OrderIdArgumentFactory extends CharSequenceTypeArgumentFactory<OrderId> {}

// 3. Create empty ColumnMapper
package com.example;

import dk.trustworks.essentials.types.jdbi.CharSequenceTypeColumnMapper;

public class OrderIdColumnMapper extends CharSequenceTypeColumnMapper<OrderId> {}
```

### Register with Jdbi

```java
import org.jdbi.v3.core.Jdbi;
import dk.trustworks.essentials.types.jdbi.*;

Jdbi jdbi = Jdbi.create(dataSource);

// ArgumentFactories (for parameters)
jdbi.registerArgument(new OrderIdArgumentFactory());
jdbi.registerArgument(new CustomerIdArgumentFactory());
jdbi.registerArgument(new AmountArgumentFactory()); // Built-in

// ColumnMappers (for results)
jdbi.registerColumnMapper(new OrderIdColumnMapper());
jdbi.registerColumnMapper(new CustomerIdColumnMapper());
jdbi.registerColumnMapper(new AmountColumnMapper()); // Built-in
```

### Use in Queries - Parameters

```java
jdbi.useHandle(handle ->
    handle.createUpdate("INSERT INTO orders(id, customer_id, total) VALUES (:id, :cust, :amt)")
          .bind("id", OrderId.of("ORD-123"))
          .bind("cust", CustomerId.of("CUST-456"))
          .bind("amt", Amount.of("99.95"))
          .execute()
);
```

### Use in Queries - Results

```java
import java.util.*;

// Single column → single type
Optional<OrderId> orderId = jdbi.withHandle(handle ->
    handle.createQuery("SELECT id FROM orders WHERE customer_id = :cust")
          .bind("cust", CustomerId.of("CUST-456"))
          .mapTo(OrderId.class)  // Uses OrderIdColumnMapper
          .findOne()
);

// Multiple columns → manual mapping
List<Order> orders = jdbi.withHandle(handle ->
    handle.createQuery("SELECT id, customer_id, total FROM orders")
          .map((rs, ctx) -> new Order(
              rs.getColumn("id", OrderId.class),        // Uses mapper
              rs.getColumn("customer_id", CustomerId.class),
              rs.getColumn("total", Amount.class)
          ))
          .list()
);
```

### Spring Boot Configuration

If using the Essentials Postgreql Spring Boot starter, then you can use beans implementing `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.JdbiConfigurationCallback` to configure Jdbi.

```java
import org.jdbi.v3.core.Jdbi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import dk.trustworks.essentials.types.jdbi.*;

@Configuration
public class JdbiConfig {

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(dataSource);

        // Register ArgumentFactories
        jdbi.registerArgument(new OrderIdArgumentFactory());
        jdbi.registerArgument(new CustomerIdArgumentFactory());
        jdbi.registerArgument(new AmountArgumentFactory());
        jdbi.registerArgument(new TransactionTimeArgumentFactory());

        // Register ColumnMappers
        jdbi.registerColumnMapper(new OrderIdColumnMapper());
        jdbi.registerColumnMapper(new CustomerIdColumnMapper());
        jdbi.registerColumnMapper(new AmountColumnMapper());
        jdbi.registerColumnMapper(new TransactionTimeColumnMapper());

        return jdbi;
    }
}
```

### JSR-310 Temporal Example

```java
import dk.trustworks.essentials.types.ZonedDateTimeType;
import dk.trustworks.essentials.types.jdbi.*;
import java.time.ZonedDateTime;

// Type
public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    public TransactionTime(ZonedDateTime value) { super(value); }
    public static TransactionTime of(ZonedDateTime value) { return new TransactionTime(value); }
    public static TransactionTime now() { return of(ZonedDateTime.now()); }
}

// Support (empty classes)
public class TransactionTimeArgumentFactory extends ZonedDateTimeTypeArgumentFactory<TransactionTime> {}
public class TransactionTimeColumnMapper extends ZonedDateTimeTypeColumnMapper<TransactionTime> {}

// Registration
jdbi.registerArgument(new TransactionTimeArgumentFactory());
jdbi.registerColumnMapper(new TransactionTimeColumnMapper());

// Usage
jdbi.useHandle(h ->
    h.createUpdate("INSERT INTO txns(id, time) VALUES (:id, :time)")
     .bind("id", TxnId.of("TXN-1"))
     .bind("time", TransactionTime.now())
     .execute()
);
```

## Gotchas

- ⚠️ **Must register both**: ArgumentFactory for params AND ColumnMapper for results
- ⚠️ **Empty class pattern**: Just extend base, no implementation needed
- ⚠️ **Type resolution**: ColumnMapper uses reflection to resolve `T` via `GenericType.resolveGenericTypeOnSuperClass()` - or pass explicit `Class<T>` to constructor
- ⚠️ **Null handling**: ColumnMappers return `null` for SQL NULL values (no Optional wrapping)
- ⚠️ **One-time registration**: Register once at `Jdbi` creation, not per-query
- ⚠️ **Provided scope**: Must add `jdbi3-core` dependency to your project (not transitive)
- ⚠️ **SQL type mapping**: Each ArgumentFactory specifies SQL type in constructor (VARCHAR, NUMERIC, etc.)

## See Also

- [types-jdbi/README.md](../types-jdbi/README.md) - Examples and detailed explanations
- [LLM-types.md](LLM-types.md) - `SingleValueType` and base types
- [LLM-foundation.md](LLM-foundation.md) - Uses JDBI for UnitOfWork and persistence
