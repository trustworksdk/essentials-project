# Types Integrations - LLM Reference

## TOC
- [Quick Facts](#quick-facts)
- [Integration Matrix](#integration-matrix)
- [Module Selection Guide](#module-selection-guide)
- [Jackson (JSON)](#jackson-json)
- [JDBI (SQL)](#jdbi-sql)
- [Avro (Schema Evolution)](#avro-schema-evolution)
- [Spring Web (HTTP)](#spring-web-http)
- [Spring Data MongoDB](#spring-data-mongodb)
- [Spring Data JPA](#spring-data-jpa)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

## Quick Facts

Integration modules enable `SingleValueType` usage across frameworks with minimal setup.

| Aspect | Details |
|--------|---------|
| **Base Module** | `types` - See [LLM-types.md](LLM-types.md) |
| **GroupId** | `dk.trustworks.essentials` |
| **Scope** | All framework deps are `provided` |
| **Philosophy** | Zero-config where possible, explicit registration otherwise |

**Status**: WORK-IN-PROGRESS

---

## Integration Matrix

| Framework | Module | Use Case | Registration | Auto-Apply |
|-----------|--------|----------|--------------|------------|
| **Jackson** | `types-jackson` | JSON serialization | `ObjectMapper.registerModule()` | ✅ All types |
| **JDBI** | `types-jdbi` | SQL persistence | `Jdbi.registerArgument/Mapper()` | Per-type |
| **Avro** | `types-avro` | Binary serialization | Maven plugin config | Per-type |
| **Spring Web** | `types-spring-web` | `@PathVariable`/`@RequestParam` | `FormatterRegistry.addConverter()` | ✅ All types |
| **Spring Data Mongo** | `types-springdata-mongo` | MongoDB persistence | `MongoCustomConversions` | ✅ Most types |
| **Spring Data JPA** | `types-springdata-jpa` | JPA entities | `@Converter(autoApply=true)` | Per-type |

**Status:**
- **Work-in-progress**: Avro, Jackson, JDBI, Spring Web, Spring Data Mongo
- **Experimental (may be removed)**: Spring Data JPA

---

## Module Selection Guide

### By Persistence Technology

| Technology | Recommended Module | Alternative |
|------------|-------------------|-------------|
| PostgreSQL | `types-jdbi` | `types-springdata-jpa` (experimental) |
| MySQL | `types-jdbi` | `types-springdata-jpa` (experimental) |
| MongoDB | `types-springdata-mongo` | N/A |
| NoSQL (general) | `types-jackson` + custom | N/A |

### By Framework

| Framework | Required Modules | Optional |
|-----------|-----------------|----------|
| **Spring Boot + PostgreSQL** | `types-jdbi` | `types-jackson`, `types-spring-web` |
| **Spring Boot + MongoDB** | `types-springdata-mongo` | `types-jackson`, `types-spring-web` |
| **Spring WebMVC/WebFlux** | `types-spring-web` | `types-jackson` (for `@RequestBody`) |
| **Event Sourcing** | `types-avro` OR `types-jackson` | Depends on serialization format |
| **Microservices** | `types-jackson` | Framework-specific modules |

---

## Jackson (JSON)

**Module:** `types-jackson`
**Detailed Docs:** [LLM-types-jackson.md](LLM-types-jackson.md)

### Core Class
```java
// Package: dk.trustworks.essentials.jackson.types
public class EssentialTypesJacksonModule extends SimpleModule {
    public EssentialTypesJacksonModule();
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

### Setup

```java
// Option 1: Manual registration
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialTypesJacksonModule());

// Option 2: Factory (includes module + opinionated defaults)
ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper();
```

### Serialization Behavior

| Type Category (`dk.trustworks.essentials.types`)          | JSON Format | Example |
|-------------------------|-------------|---------|
| `CharSequenceType`      | String | `"ORD-123"` |
| `NumberType`            | Number | `99.99` |
| `Money`                 | Object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` | ISO-8601 | `"2024-01-15T10:30:00Z"` |

### Requirements

**CharSequenceType (Jackson 2.18+):**
```java
public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required!
}
```

**JSR310 Types:**
```java
public class CreatedAt extends InstantType<CreatedAt> {
    @JsonCreator  // Required for deserialization
    public CreatedAt(Instant value) { super(value); }
}
```

**Map Keys:**
```java
// Custom KeyDeserializer required
public class ProductIdKeyDeserializer extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt) {
        return ProductId.of(key);
    }
}

// Usage
@JsonDeserialize(keyUsing = ProductIdKeyDeserializer.class)
Map<ProductId, Quantity> items;
```

---

## JDBI (SQL)

**Module:** `types-jdbi`
**Detailed Docs:** [LLM-types-jdbi.md](LLM-types-jdbi.md)

### Architecture

Two components per type:
1. **ArgumentFactory** - Query parameters (INSERT/UPDATE/WHERE)
2. **ColumnMapper** - Result mapping (SELECT)

### Pattern

```java
// 1. Create empty subclasses
public class OrderIdArgumentFactory extends CharSequenceTypeArgumentFactory<OrderId> {}
public class OrderIdColumnMapper extends CharSequenceTypeColumnMapper<OrderId> {}

// 2. Register with Jdbi
jdbi.registerArgument(new OrderIdArgumentFactory());
jdbi.registerColumnMapper(new OrderIdColumnMapper());
```

### Type Mapping

| SingleValueType (`dk.trustworks.essentials.types`) | Base Factory/Mapper (`dk.trustworks.essentials.types.jdbi`) | SQL Type |
|-------------|-----------------|----------|
| `CharSequenceType<T>` | `CharSequenceType{ArgumentFactory\|ColumnMapper}<T>` | VARCHAR |
| `BigDecimalType<T>` | `BigDecimalType{ArgumentFactory\|ColumnMapper}<T>` | NUMERIC |
| `LongType<T>` | `LongType{ArgumentFactory\|ColumnMapper}<T>` | BIGINT |
| `IntegerType<T>` | `IntegerType{ArgumentFactory\|ColumnMapper}<T>` | INTEGER |
| `InstantType<T>` | `InstantType{ArgumentFactory\|ColumnMapper}<T>` | TIMESTAMP |
| `LocalDateType<T>` | `LocalDateType{ArgumentFactory\|ColumnMapper}<T>` | DATE |
| `ZonedDateTimeType<T>` | `ZonedDateTimeType{ArgumentFactory\|ColumnMapper}<T>` | TIMESTAMP_WITH_TIMEZONE |

### Usage

```java
// Insert
jdbi.useHandle(h ->
    h.createUpdate("INSERT INTO orders(id, customer_id, total) VALUES (:id, :cid, :amt)")
     .bind("id", OrderId.of("ORD-123"))
     .bind("cid", CustomerId.of("CUST-456"))
     .bind("amt", Amount.of("99.99"))
     .execute()
);

// Select
Optional<OrderId> orderId = jdbi.withHandle(h ->
    h.createQuery("SELECT id FROM orders WHERE customer_id = :cid")
     .bind("cid", CustomerId.of("CUST-456"))
     .mapTo(OrderId.class)
     .findOne()
);
```

### Built-in Types

Package: `dk.trustworks.essentials.types.jdbi`

| Type (`dk.trustworks.essentials.types`)| ArgumentFactory | ColumnMapper |
|------|----------------|--------------|
| `Amount` | `AmountArgumentFactory` | `AmountColumnMapper` |
| `Percentage` | `PercentageArgumentFactory` | `PercentageColumnMapper` |
| `CurrencyCode` | `CurrencyCodeArgumentFactory` | `CurrencyCodeColumnMapper` |
| `CountryCode` | `CountryCodeArgumentFactory` | `CountryCodeColumnMapper` |
| `EmailAddress` | `EmailAddressArgumentFactory` | `EmailAddressColumnMapper` |

---

## Avro (Schema Evolution)

**Module:** `types-avro`
**Detailed Docs:** [LLM-types-avro.md](LLM-types-avro.md)

### Architecture

3-class system per type:
1. **LogicalType** - Schema validation
2. **`org.apache.avro.LogicalTypes.LogicalTypeFactory`** - Creates LogicalType from schema
3. **Conversion** - Runtime ser/deser

### Pattern

**1. LogicalTypeFactory:**
```java
public class OrderIdLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory {
    public static final LogicalType ORDER_ID = new CharSequenceTypeLogicalType("OrderId");

    @Override
    public LogicalType fromSchema(Schema schema) { return ORDER_ID; }

    @Override
    public String getTypeName() { return ORDER_ID.getName(); }
}
```

**2. Conversion:**
```java
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

**4. Maven Plugin:**
```xml
<plugin>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro-maven-plugin</artifactId>
    <configuration>
        <customLogicalTypeFactories>
            <logicalTypeFactory>com.example.OrderIdLogicalTypeFactory</logicalTypeFactory>
        </customLogicalTypeFactories>
        <customConversions>
            <conversion>com.example.OrderIdConversion</conversion>
        </customConversions>
    </configuration>
</plugin>
```

### Base Conversions

Package: `dk.trustworks.essentials.types.avro`

| SingleValueType (`dk.trustworks.essentials.types`) | Base Conversion | Avro Type |
|-----------------|-----------------|-----------|
| `CharSequenceType<T>` | `BaseCharSequenceConversion<T>` | `string` |
| `LongType<T>` | `BaseLongTypeConversion<T>` | `long` |
| `IntegerType<T>` | `BaseIntegerTypeConversion<T>` | `int` |
| `InstantType<T>` | `BaseInstantTypeConversion<T>` | `long` (millis) |
| `LocalDateType<T>` | `BaseLocalDateTypeConversion<T>` | `int` (days) |
| `LocalTimeType<T>` | `BaseLocalTimeTypeConversion<T>` | `long` (millis) |

### Built-in Types

Package: `dk.trustworks.essentials.types.avro`

| Type (`dk.trustworks.essentials.types`) | LogicalType Name | Factory | Conversion |
|------|------------------|---------|------------|
| `Amount` | `Amount` | `AmountLogicalTypeFactory` | `AmountConversion` |
| `Percentage` | `Percentage` | `PercentageLogicalTypeFactory` | `PercentageConversion` |
| `CurrencyCode` | `CurrencyCode` | `CurrencyCodeLogicalTypeFactory` | `CurrencyCodeConversion` |
| `CountryCode` | `CountryCode` | `CountryCodeLogicalTypeFactory` | `CountryCodeConversion` |
| `EmailAddress` | `EmailAddress` | `EmailAddressLogicalTypeFactory` | `EmailAddressConversion` |

---

## Spring Web (HTTP)

**Module:** `types-spring-web`
**Detailed Docs:** [LLM-types-spring-web.md](LLM-types-spring-web.md)

### Core Class
```java
// Package: dk.trustworks.essentials.types.spring.web
public class SingleValueTypeConverter implements GenericConverter {
    // Converts String <-> SingleValueType for @PathVariable/@RequestParam
}
```

### Setup

**WebMVC:**
```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
```

**WebFlux:**
```java
@Configuration
public class WebFluxConfig implements WebFluxConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
```

### Usage

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable OrderId orderId) {
        return orderService.findById(orderId);
    }

    @GetMapping("/search")
    public List<Order> search(
            @RequestParam CustomerId customerId,
            @RequestParam(required = false) Amount minAmount) {
        return orderService.search(customerId, minAmount);
    }
}
```

### Supported Conversions

| Source | Target (`dk.trustworks.essentials.types`)             | Logic |
|--------|---------------------|-------|
| `String` | `CharSequenceType`  | `SingleValueType.fromObject()` |
| `String` | `NumberType`        | `NumberUtils.parseNumber()` + `fromObject()` |
| `Number` | `NumberType`        | `fromObject()` |
| `String` | `InstantType`       | `Instant.parse()` |
| `String` | `LocalDateType`     | `LocalDate.parse()` |
| `String` | `ZonedDateTimeType` | `ZonedDateTime.parse(URLDecoder.decode())` |

---

## Spring Data MongoDB

**Module:** `types-springdata-mongo`
**Detailed Docs:** [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md)

### Core Classes

Package: `dk.trustworks.essentials.types.springdata.mongo`

| Class | Interface | Purpose |
|-------|-----------|---------|
| `SingleValueTypeConverter` | `GenericConverter` | Converts `SingleValueType` ↔ MongoDB types |
| `SingleValueTypeRandomIdGenerator` | `BeforeConvertCallback<Object>` | Auto-generates `@Id` via `random()` method |

### Setup

```java
@Configuration
public class MongoConfig {

    @Bean
    public SingleValueTypeRandomIdGenerator singleValueTypeIdGenerator() {
        return new SingleValueTypeRandomIdGenerator();
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        // Register CharSequenceTypes that wrap ObjectId
        return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(ProductId.class, OrderId.class)
        ));
    }
}
```

### Type Mapping

| SingleValueType (`dk.trustworks.essentials.types`) | MongoDB Type | Notes |
|-------------|--------------|-------|
| `CharSequenceType` | `String` | Basic |
| `CharSequenceType` (ObjectId) | `ObjectId` | Requires explicit registration |
| `NumberType` | `Number`/`Decimal128` | Auto-handles Decimal128 |
| `InstantType` | `Date` | UTC timezone |
| `LocalDateTimeType` | `Date` | UTC timezone |
| `LocalDateType` | `Date` | UTC timezone |

**Not Supported:** `OffsetDateTimeType`, `ZonedDateTimeType`

### Usage

```java
@Document
public class Order {
    @Id public OrderId id;               // Auto-generated via random()
    public CustomerId customerId;
    public Amount totalPrice;
    public Created createdAt;            // LocalDateTimeType
    public Map<ProductId, Quantity> items;  // ProductId registered for ObjectId
}

public interface OrderRepository extends MongoRepository<Order, OrderId> {
    List<Order> findByCustomerId(CustomerId customerId);
}
```

### ID Generation

**Requires `random()` method:**
```java
public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }

    public static OrderId random() {
        return new OrderId(ObjectId.get().toString());
    }
}
```

---

## Spring Data JPA

**Module:** `types-springdata-jpa`
**Detailed Docs:** [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md)

**⚠️ Status:** EXPERIMENTAL - May be discontinued. Prefer `types-jdbi`.

### Limitations

| Limitation | Impact                                                                                                      |
|------------|-------------------------------------------------------------------------------------------------------------|
| No ID autogeneration | Must generate IDs manually                                                                                  |
| `@Id` not supported | Must use `@EmbeddedId` + `@Embeddable`                                                                      |
| Duplicate ID field | ID types need both `SingleValueType` value and persistent field - requires Id type and normal property type |

### Pattern

**1. Create Converter:**
```java
@Converter(autoApply = true)
public class CustomerIdAttributeConverter
    extends BaseCharSequenceTypeAttributeConverter<CustomerId> {

    @Override
    protected Class<CustomerId> getConcreteCharSequenceType() {
        return CustomerId.class;
    }
}
```

**2. Use in Entity (Non-ID Fields):**
```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    public String id;  // Use Java primitives for IDs

    public CustomerId customerId;  // Auto-converted
    public Amount totalAmount;     // Auto-converted
}
```

### Base Converters

Package: `dk.trustworks.essentials.types.springdata.jpa.converters`

| SingleValueType (`dk.trustworks.essentials.types`) | Base Converter | DB Type |
|-------------|----------------|---------|
| `CharSequenceType<T>` | `BaseCharSequenceTypeAttributeConverter<T>` | `String` |
| `BigDecimalType<T>` | `BaseBigDecimalTypeAttributeConverter<T>` | `Double` |
| `LongType<T>` | `BaseLongTypeAttributeConverter<T>` | `Long` |
| `InstantType<T>` | `BaseInstantTypeAttributeConverter<T>` | `Instant` |
| `LocalDateType<T>` | `BaseLocalDateTypeAttributeConverter<T>` | `LocalDate` |

---

## Common Patterns

### Complete Integration Stack

**Spring Boot + PostgreSQL + REST API:**
```xml
<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types</artifactId>
    </dependency>

    <!-- Persistence -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-jdbi</artifactId>
    </dependency>

    <!-- JSON API -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-jackson</artifactId>
    </dependency>

    <!-- HTTP Params -->
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-spring-web</artifactId>
    </dependency>
</dependencies>
```

**Spring Boot + MongoDB:**
```xml
<dependencies>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-springdata-mongo</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-jackson</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-spring-web</artifactId>
    </dependency>
</dependencies>
```

### Full-Stack Type Example

```java
// 1. Define type (in your codebase)
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }

    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.randomId()); }
}

// 2. JDBI support (if using JDBI)
public class OrderIdArgumentFactory extends CharSequenceTypeArgumentFactory<OrderId> {}
public class OrderIdColumnMapper extends CharSequenceTypeColumnMapper<OrderId> {}

// 3. MongoDB support (if using MongoDB with ObjectId)
// Register in MongoCustomConversions: new SingleValueTypeConverter(OrderId.class)

// 4. JPA support (if using JPA - not recommended)
@Converter(autoApply = true)
public class OrderIdAttributeConverter extends BaseCharSequenceTypeAttributeConverter<OrderId> {
    @Override
    protected Class<OrderId> getConcreteCharSequenceType() { return OrderId.class; }
}

// Usage: Works everywhere automatically!
@RestController
public class OrderController {

    @GetMapping("/orders/{orderId}")  // Spring Web conversion
    public Order getOrder(@PathVariable OrderId orderId) {
        return jdbi.withHandle(h ->
            h.createQuery("SELECT * FROM orders WHERE id = :id")
             .bind("id", orderId)  // JDBI conversion
             .mapTo(Order.class)
             .findOne()
        );
    }
}
```

---

## Gotchas

### Jackson
- ⚠️ `CharSequenceType` needs **both** `CharSequence` and `String` constructors (Jackson 2.18+)
- ⚠️ `JSR310SingleValueType` needs `@JsonCreator` on constructor
- ⚠️ Map key deserialization requires explicit `@JsonDeserialize(keyUsing = ...)`
- ⚠️ `Money` serializes as object `{"amount":"...","currency":"..."}`, not single value

### JDBI
- ⚠️ Must register **both** ArgumentFactory (params) and ColumnMapper (results)
- ⚠️ Registration is one-time at `Jdbi` creation, not per-query
- ⚠️ ColumnMappers return `null` for SQL NULL (no Optional wrapping)

### Avro
- ⚠️ Must create 3 classes per type: LogicalType, LogicalTypeFactory, Conversion
- ⚠️ Avro primitive must match LogicalType expectation (`string`/`long`/`int`)
- ⚠️ Register **both** `customLogicalTypeFactories` and `customConversions` in plugin
- ⚠️ JSR-310 converters use UTC timezone; temporal types truncate nanoseconds
- ⚠️ Millisecond precision only for temporal types

### Spring Web
- ⚠️ Only handles `@PathVariable`/`@RequestParam`; JSON bodies require `types-jackson`
- ⚠️ `ZonedDateTimeType` must be URL-encoded in URLs (converter auto-decodes)

### Spring Data MongoDB
- ⚠️ `CharSequenceType` containing ObjectId as Map key requires explicit registration
- ⚠️ `random()` method required for ID generation
- ⚠️ JSR-310 converters use UTC; MongoDB ISODate doesn't support nanoseconds
- ⚠️ `OffsetDateTimeType`/`ZonedDateTimeType` unsupported (Spring Data limitation)

### Spring Data JPA
- ⚠️ **EXPERIMENTAL** - prefer `types-jdbi` for SQL databases
- ⚠️ No ID autogeneration - must generate IDs manually
- ⚠️ Cannot use `@Id` on `SingleValueType` fields (use Java primitives)
- ⚠️ `@EmbeddedId` requires separate persistent field + no-arg constructor
- ⚠️ One converter per type with `@Converter(autoApply = true)`

---

## See Also

### Module Documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson integration details
- [LLM-types-jdbi.md](LLM-types-jdbi.md) - JDBI integration details
- [LLM-types-avro.md](LLM-types-avro.md) - Avro integration details
- [LLM-types-spring-web.md](LLM-types-spring-web.md) - Spring Web integration details
- [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md) - MongoDB integration details
- [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md) - JPA integration details

### README Files
- [types-jackson README](../types-jackson/README.md)
- [types-jdbi README](../types-jdbi/README.md)
- [types-avro README](../types-avro/README.md)
- [types-spring-web README](../types-spring-web/README.md)
- [types-springdata-mongo README](../types-springdata-mongo/README.md)
- [types-springdata-jpa README](../types-springdata-jpa/README.md)
