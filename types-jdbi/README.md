# Types-JDBI

> JDBI v3 ArgumentFactory and ColumnMapper support for Essentials `types` module

This module enables seamless use of `SingleValueType` implementations as JDBI query parameters (`ArgumentFactory`) and result mappings (`ColumnMapper`).

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types-jdbi.md](../LLM/LLM-types-jdbi.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [ArgumentFactory](#argumentfactory)
- [ColumnMapper](#columnmapper)
- [Built-in Types](#built-in-types)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Complete Example](#complete-example)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jdbi</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependency** (provided scope - add to your project):
```xml
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.types.jdbi`

**1. Create your type** (extends a base type from `types` module):

```java
public class CustomerId extends CharSequenceType<CustomerId> implements Identifier {
    public CustomerId(CharSequence value) {
        super(value);
    }
    // Required for Jackson 2.18+
    public CustomerId(String value) {
        super(value);
    }
    
    public static CustomerId of(CharSequence value) {
        return new CustomerId(value);
    }
}
```

**2. Create ArgumentFactory and ColumnMapper** (empty classes - just extend the base):

```java
// For query parameters (INSERT, UPDATE, WHERE clauses)
public class CustomerIdArgumentFactory extends CharSequenceTypeArgumentFactory<CustomerId> {}

// For result mapping (SELECT)
public class CustomerIdColumnMapper extends CharSequenceTypeColumnMapper<CustomerId> {}
```

**3. Register with JDBI:**

```java
Jdbi jdbi = Jdbi.create(dataSource);
jdbi.registerArgument(new CustomerIdArgumentFactory());
jdbi.registerColumnMapper(new CustomerIdColumnMapper());
```

**4. Use in queries:**

```java
// Insert with semantic type parameter
jdbi.useHandle(handle ->
    handle.createUpdate("INSERT INTO customers(id, name) VALUES (:id, :name)")
          .bind("id", CustomerId.of("CUST-123"))
          .bind("name", "John Doe")
          .execute()
);

// Query returning semantic type
Optional<CustomerId> customerId = jdbi.withHandle(handle ->
    handle.createQuery("SELECT id FROM customers WHERE name = :name")
          .bind("name", "John Doe")
          .mapTo(CustomerId.class)
          .findOne()
);
```

**Learn more:** See [SingleValueTypeArgumentsTest.java](src/test/java/dk/trustworks/essentials/types/jdbi/SingleValueTypeArgumentsTest.java)

## ArgumentFactory

`ArgumentFactory` converts `SingleValueType` instances to JDBC parameters for `INSERT`, `UPDATE`, and `WHERE` clauses.

### Base Classes

| Your Type Extends | Your ArgumentFactory Extends |
|-------------------|------------------------------|
| `CharSequenceType` | `CharSequenceTypeArgumentFactory<T>` |
| `BigDecimalType` | `BigDecimalTypeArgumentFactory<T>` |
| `BigIntegerType` | `BigIntegerTypeArgumentFactory<T>` |
| `IntegerType` | `IntegerTypeArgumentFactory<T>` |
| `LongType` | `LongTypeArgumentFactory<T>` |
| `ShortType` | `ShortTypeArgumentFactory<T>` |
| `ByteType` | `ByteTypeArgumentFactory<T>` |
| `DoubleType` | `DoubleTypeArgumentFactory<T>` |
| `FloatType` | `FloatTypeArgumentFactory<T>` |
| `BooleanType` | `BooleanTypeArgumentFactory<T>` |
| `InstantType` | `InstantTypeArgumentFactory<T>` |
| `LocalDateTimeType` | `LocalDateTimeTypeArgumentFactory<T>` |
| `LocalDateType` | `LocalDateTypeArgumentFactory<T>` |
| `LocalTimeType` | `LocalTimeTypeArgumentFactory<T>` |
| `OffsetDateTimeType` | `OffsetDateTimeTypeArgumentFactory<T>` |
| `ZonedDateTimeType` | `ZonedDateTimeTypeArgumentFactory<T>` |

## ColumnMapper

`ColumnMapper` converts database column values back to `SingleValueType` instances in `SELECT` results.

### Base Classes

| Your Type Extends | Your ColumnMapper Extends |
|-------------------|---------------------------|
| `CharSequenceType` | `CharSequenceTypeColumnMapper<T>` |
| `BigDecimalType` | `BigDecimalTypeColumnMapper<T>` |
| `BigIntegerType` | `BigIntegerTypeColumnMapper<T>` |
| `IntegerType` | `IntegerTypeColumnMapper<T>` |
| `LongType` | `LongTypeColumnMapper<T>` |
| `ShortType` | `ShortTypeColumnMapper<T>` |
| `ByteType` | `ByteTypeColumnMapper<T>` |
| `DoubleType` | `DoubleTypeColumnMapper<T>` |
| `FloatType` | `FloatTypeColumnMapper<T>` |
| `BooleanType` | `BooleanTypeColumnMapper<T>` |
| `InstantType` | `InstantTypeColumnMapper<T>` |
| `LocalDateTimeType` | `LocalDateTimeColumnMapper<T>` |
| `LocalDateType` | `LocalDateTypeColumnMapper<T>` |
| `LocalTimeType` | `LocalTimeTypeColumnMapper<T>` |
| `OffsetDateTimeType` | `OffsetDateTimeTypeColumnMapper<T>` |
| `ZonedDateTimeType` | `ZonedDateTimeTypeColumnMapper<T>` |

## Built-in Types

The module provides ready-to-use ArgumentFactory and ColumnMapper for common Essentials types:

| Type | ArgumentFactory | ColumnMapper |
|------|-----------------|--------------|
| `Amount` | `AmountArgumentFactory` | `AmountColumnMapper` |
| `Percentage` | `PercentageArgumentFactory` | `PercentageColumnMapper` |
| `CurrencyCode` | `CurrencyCodeArgumentFactory` | `CurrencyCodeColumnMapper` |
| `CountryCode` | `CountryCodeArgumentFactory` | `CountryCodeColumnMapper` |
| `EmailAddress` | `EmailAddressArgumentFactory` | `EmailAddressColumnMapper` |

```java
// Register built-in types
jdbi.registerArgument(new AmountArgumentFactory());
jdbi.registerColumnMapper(new AmountColumnMapper());
jdbi.registerArgument(new PercentageArgumentFactory());
jdbi.registerColumnMapper(new PercentageColumnMapper());
```

## JSR-310 Temporal Types

For types extending `JSR310SingleValueType`:

| Base Type | ArgumentFactory | ColumnMapper |
|-----------|-----------------|--------------|
| `InstantType` | `InstantTypeArgumentFactory<T>` | `InstantTypeColumnMapper<T>` |
| `LocalDateTimeType` | `LocalDateTimeTypeArgumentFactory<T>` | `LocalDateTimeColumnMapper<T>` |
| `LocalDateType` | `LocalDateTypeArgumentFactory<T>` | `LocalDateTypeColumnMapper<T>` |
| `LocalTimeType` | `LocalTimeTypeArgumentFactory<T>` | `LocalTimeTypeColumnMapper<T>` |
| `OffsetDateTimeType` | `OffsetDateTimeTypeArgumentFactory<T>` | `OffsetDateTimeTypeColumnMapper<T>` |
| `ZonedDateTimeType` | `ZonedDateTimeTypeArgumentFactory<T>` | `ZonedDateTimeTypeColumnMapper<T>` |

### Example: TransactionTime

```java
// Type definition
public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    public TransactionTime(ZonedDateTime value) {
        super(value);
    }

    public static TransactionTime of(ZonedDateTime value) {
        return new TransactionTime(value);
    }

    public static TransactionTime now() {
        return new TransactionTime(ZonedDateTime.now());
    }
}

// ArgumentFactory and ColumnMapper
public class TransactionTimeArgumentFactory extends ZonedDateTimeTypeArgumentFactory<TransactionTime> {}
public class TransactionTimeColumnMapper extends ZonedDateTimeTypeColumnMapper<TransactionTime> {}
```

## Complete Example

```java
@Configuration
public class JdbiConfig {

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(dataSource);

        // Register ArgumentFactories (for query parameters)
        jdbi.registerArgument(new OrderIdArgumentFactory());
        jdbi.registerArgument(new CustomerIdArgumentFactory());
        jdbi.registerArgument(new AmountArgumentFactory());
        jdbi.registerArgument(new TransactionTimeArgumentFactory());

        // Register ColumnMappers (for result mapping)
        jdbi.registerColumnMapper(new OrderIdColumnMapper());
        jdbi.registerColumnMapper(new CustomerIdColumnMapper());
        jdbi.registerColumnMapper(new AmountColumnMapper());
        jdbi.registerColumnMapper(new TransactionTimeColumnMapper());

        return jdbi;
    }
}

@Repository
public class OrderRepository {
    private final Jdbi jdbi;

    public void save(Order order) {
        jdbi.useHandle(handle ->
            handle.createUpdate("""
                INSERT INTO orders (id, customer_id, total_amount, created_at)
                VALUES (:id, :customerId, :totalAmount, :createdAt)
                """)
                .bind("id", order.id())
                .bind("customerId", order.customerId())
                .bind("totalAmount", order.totalAmount())
                .bind("createdAt", order.createdAt())
                .execute()
        );
    }

    public Optional<Order> findById(OrderId orderId) {
        return jdbi.withHandle(handle ->
            handle.createQuery("""
                SELECT id, customer_id, total_amount, created_at
                FROM orders WHERE id = :id
                """)
                .bind("id", orderId)
                .map((rs, ctx) -> new Order(
                    OrderId.of(rs.getString("id")),
                    CustomerId.of(rs.getString("customer_id")),
                    Amount.of(rs.getBigDecimal("total_amount")),
                    TransactionTime.of(rs.getObject("created_at", ZonedDateTime.class))
                ))
                .findOne()
        );
    }
}
```

## See Also

- [LLM-types-jdbi.md](../LLM/LLM-types-jdbi.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson](../types-jackson) - Jackson serialization for types
