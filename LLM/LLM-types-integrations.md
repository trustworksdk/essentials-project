# Types Integrations - LLM Reference

> Quick reference for integrating `SingleValueType` across frameworks. For detailed explanations, see individual module README files.

## TOC
- [Quick Facts](#quick-facts)
- [Integration Matrix](#integration-matrix)
- [Module Selection](#module-selection)
- [Jackson JSON](#jackson-json)
- [JDBI SQL](#jdbi-sql)
- [Avro Schema Evolution](#avro-schema-evolution)
- [Spring Web HTTP](#spring-web-http)
- [Spring Data MongoDB](#spring-data-mongodb)
- [Spring Data JPA](#spring-data-jpa)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

---

## Quick Facts

| Aspect | Details |
|--------|---------|
| **Base Module** | `types` - See [LLM-types.md](LLM-types.md) |
| **GroupId** | `dk.trustworks.essentials` |
| **Scope** | Framework deps are `provided` (not transitive) |
| **Philosophy** | Zero-config where possible |

**Note**: This is an **index document** for types integration modules. All integration modules depend on [types](./LLM-types.md).

**Status**: Work-in-progress (all modules). JPA is experimental and may be removed.

---

## Integration Matrix

| Framework | Module | Use Case | Registration | Auto-Apply |
|-----------|--------|----------|--------------|------------|
| **Jackson** | `types-jackson` | JSON ser/deser | `ObjectMapper.registerModule()` | ✅ All types |
| **JDBI** | `types-jdbi` | SQL persistence | `Jdbi.registerArgument/Mapper()` | Per-type |
| **Avro** | `types-avro` | Binary ser/deser | Maven plugin config | Per-type |
| **Spring Web** | `types-spring-web` | `@PathVariable`/`@RequestParam` | `FormatterRegistry.addConverter()` | ✅ All types |
| **Spring Data Mongo** | `types-springdata-mongo` | MongoDB persistence | `MongoCustomConversions` | ✅ Most types |
| **Spring Data JPA** | `types-springdata-jpa` | JPA entities | `@Converter(autoApply=true)` | Per-type |

---

## Module Selection

### By Persistence Technology

| Technology | Primary Module | Alternative |
|------------|----------------|-------------|
| PostgreSQL/MySQL | `types-jdbi` | `types-springdata-jpa` (experimental) |
| MongoDB | `types-springdata-mongo` | N/A |
| NoSQL (general) | `types-jackson` + custom | N/A |

### By Framework Stack

| Stack | Required | Optional |
|-------|----------|----------|
| **Spring Boot + PostgreSQL** | `types-jdbi` | `types-jackson`, `types-spring-web` |
| **Spring Boot + MongoDB** | `types-springdata-mongo` | `types-jackson`, `types-spring-web` |
| **Spring WebMVC/WebFlux** | `types-spring-web` | `types-jackson` (for `@RequestBody`) |
| **Event Sourcing** | `types-avro` OR `types-jackson` | Depends on format |
| **Microservices** | `types-jackson` | Framework-specific modules |

---

## Jackson JSON

**Module:** `types-jackson`
**Package:** `dk.trustworks.essentials.jackson.types`
**Detailed Docs:** [LLM-types-jackson.md](LLM-types-jackson.md)

### Core Class

```java
public class EssentialTypesJacksonModule extends SimpleModule {
    public EssentialTypesJacksonModule();
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

### Setup

```java
// Option 1: Manual
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialTypesJacksonModule());

// Option 2: Factory (includes module + opinionated defaults)
ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper();
```

### Serialization Format

Base package: `dk.trustworks.essentials.types`

| Type Category | JSON Format | Example |
|---------------|-------------|---------|
| `CharSequenceType` | String | `"ORD-123"` |
| `NumberType` | Number | `99.99` |
| `Money` | Object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` | ISO-8601 | `"2024-01-15T10:30:00Z"` |

### Type Requirements

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

@JsonDeserialize(keyUsing = ProductIdKeyDeserializer.class)
Map<ProductId, Quantity> items;
```

---

## JDBI SQL

**Module:** `types-jdbi`
**Package:** `dk.trustworks.essentials.types.jdbi`
**Detailed Docs:** [LLM-types-jdbi.md](LLM-types-jdbi.md)

### Architecture

Two components per type:
1. **ArgumentFactory** - Query params (INSERT/UPDATE/WHERE)
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

Base package (types): `dk.trustworks.essentials.types`
Base package (jdbi): `dk.trustworks.essentials.types.jdbi`

| SingleValueType | Base Factory/Mapper | SQL Type |
|-----------------|---------------------|----------|
| `CharSequenceType<T>` | `CharSequenceType{ArgumentFactory\|ColumnMapper}<T>` | VARCHAR |
| `BigDecimalType<T>` | `BigDecimalType{ArgumentFactory\|ColumnMapper}<T>` | NUMERIC |
| `LongType<T>` | `LongType{ArgumentFactory\|ColumnMapper}<T>` | BIGINT |
| `IntegerType<T>` | `IntegerType{ArgumentFactory\|ColumnMapper}<T>` | INTEGER |
| `InstantType<T>` | `InstantType{ArgumentFactory\|ColumnMapper}<T>` | TIMESTAMP |
| `LocalDateType<T>` | `LocalDateType{ArgumentFactory\|ColumnMapper}<T>` | DATE |
| `ZonedDateTimeType<T>` | `ZonedDateTimeType{ArgumentFactory\|ColumnMapper}<T>` | TIMESTAMP WITH TIME ZONE |

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

Base package: `dk.trustworks.essentials.types.jdbi`

| Type | ArgumentFactory | ColumnMapper |
|------|----------------|--------------|
| `Amount` | `AmountArgumentFactory` | `AmountColumnMapper` |
| `Percentage` | `PercentageArgumentFactory` | `PercentageColumnMapper` |
| `CurrencyCode` | `CurrencyCodeArgumentFactory` | `CurrencyCodeColumnMapper` |
| `CountryCode` | `CountryCodeArgumentFactory` | `CountryCodeColumnMapper` |
| `EmailAddress` | `EmailAddressArgumentFactory` | `EmailAddressColumnMapper` |

---

## Avro Schema Evolution

**Module:** `types-avro`
**Package:** `dk.trustworks.essentials.types.avro`
**Detailed Docs:** [LLM-types-avro.md](LLM-types-avro.md)

### Architecture

3-class system per type:
1. **LogicalTypeFactory** - Creates LogicalType from schema
2. **LogicalType** - Schema validation
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

Base package: `dk.trustworks.essentials.types.avro`

| SingleValueType | Base Conversion | Avro Type |
|-----------------|-----------------|-----------|
| `CharSequenceType<T>` | `BaseCharSequenceConversion<T>` | `string` |
| `LongType<T>` | `BaseLongTypeConversion<T>` | `long` |
| `IntegerType<T>` | `BaseIntegerTypeConversion<T>` | `int` |
| `InstantType<T>` | `BaseInstantTypeConversion<T>` | `long` (millis) |
| `LocalDateType<T>` | `BaseLocalDateTypeConversion<T>` | `int` (days since epoch) |
| `LocalTimeType<T>` | `BaseLocalTimeTypeConversion<T>` | `long` (millis) |

---

## Spring Web HTTP

**Module:** `types-spring-web`
**Package:** `dk.trustworks.essentials.types.spring.web`
**Detailed Docs:** [LLM-types-spring-web.md](LLM-types-spring-web.md)

### Core Class

```java
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

Base package: `dk.trustworks.essentials.types`

| Source | Target | Logic |
|--------|--------|-------|
| `String` | `CharSequenceType` | `SingleValueType.fromObject()` |
| `String` | `NumberType` | `NumberUtils.parseNumber()` + `fromObject()` |
| `Number` | `NumberType` | `fromObject()` |
| `String` | `InstantType` | `Instant.parse()` |
| `String` | `LocalDateType` | `LocalDate.parse()` |
| `String` | `ZonedDateTimeType` | `ZonedDateTime.parse(URLDecoder.decode())` |

---

## Spring Data MongoDB

**Module:** `types-springdata-mongo`
**Package:** `dk.trustworks.essentials.types.springdata.mongo`
**Detailed Docs:** [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md)

### Core Classes

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
        // Register CharSequenceTypes wrapping ObjectId
        return new MongoCustomConversions(List.of(
            new SingleValueTypeConverter(ProductId.class, OrderId.class)
        ));
    }
}
```

### Type Mapping

Base package: `dk.trustworks.essentials.types`

| SingleValueType | MongoDB Type | Notes |
|-----------------|--------------|-------|
| `CharSequenceType` | `String` | Default |
| `CharSequenceType` (ObjectId) | `ObjectId` | Requires registration |
| `NumberType` | `Number`/`Decimal128` | Auto-handles Decimal128 |
| `InstantType` | `Date` | UTC |
| `LocalDateTimeType` | `Date` | UTC |
| `LocalDateType` | `Date` | UTC |

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

### ID Generation Requirements

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
**Package:** `dk.trustworks.essentials.types.springdata.jpa.converters`
**Detailed Docs:** [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md)

**⚠️ Status:** EXPERIMENTAL - May be discontinued. Prefer `types-jdbi`.

### Limitations

| Limitation | Impact |
|------------|--------|
| No ID autogeneration | Must generate IDs manually |
| `@Id` not supported | Use Java primitives for `@Id` fields |
| Duplicate field needed | ID types need both `SingleValueType` value and persistent field |

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

**2. Use in Entity:**
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

Base package: `dk.trustworks.essentials.types.springdata.jpa.converters`

| SingleValueType | Base Converter | DB Type |
|-----------------|----------------|---------|
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
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types</artifactId>
    </dependency>
    <dependency>
        <groupId>dk.trustworks.essentials</groupId>
        <artifactId>types-jdbi</artifactId>
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
// 1. Define type
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

// Usage: Works everywhere automatically
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
- ⚠️ Map key deserialization requires `@JsonDeserialize(keyUsing = ...)`
- ⚠️ `Money` serializes as object `{"amount":"...","currency":"..."}`, not single value

### JDBI
- ⚠️ Register **both** ArgumentFactory (params) and ColumnMapper (results)
- ⚠️ Registration is one-time at `Jdbi` creation, not per-query
- ⚠️ ColumnMappers return `null` for SQL NULL (no Optional wrapping)

### Avro
- ⚠️ Must create 3 classes: LogicalTypeFactory, LogicalType, Conversion
- ⚠️ Avro primitive must match LogicalType expectation (`string`/`long`/`int`)
- ⚠️ Register **both** `customLogicalTypeFactories` and `customConversions` in Maven plugin
- ⚠️ Millisecond precision only for temporal types
- ⚠️ JSR-310 converters use UTC; nanoseconds truncated

### Spring Web
- ⚠️ Only handles `@PathVariable`/`@RequestParam`; JSON bodies require `types-jackson`
- ⚠️ `ZonedDateTimeType` must be URL-encoded (converter auto-decodes)

### Spring Data MongoDB
- ⚠️ `CharSequenceType` wrapping ObjectId requires explicit registration
- ⚠️ `random()` method required for ID generation
- ⚠️ JSR-310 converters use UTC; MongoDB ISODate doesn't support nanoseconds
- ⚠️ `OffsetDateTimeType`/`ZonedDateTimeType` unsupported

### Spring Data JPA
- ⚠️ **EXPERIMENTAL** - prefer `types-jdbi` for SQL databases
- ⚠️ No ID autogeneration - generate IDs manually
- ⚠️ Cannot use `@Id` on `SingleValueType` fields (use Java primitives)
- ⚠️ One converter per type with `@Converter(autoApply = true)`

---

## See Also

### Module Documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson integration
- [LLM-types-jdbi.md](LLM-types-jdbi.md) - JDBI integration
- [LLM-types-avro.md](LLM-types-avro.md) - Avro integration
- [LLM-types-spring-web.md](LLM-types-spring-web.md) - Spring Web integration
- [LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md) - MongoDB integration
- [LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md) - JPA integration

### README Files
- [types-jackson README](../types-jackson/README.md)
- [types-jdbi README](../types-jdbi/README.md)
- [types-avro README](../types-avro/README.md)
- [types-spring-web README](../types-spring-web/README.md)
- [types-springdata-mongo README](../types-springdata-mongo/README.md)
- [types-springdata-jpa README](../types-springdata-jpa/README.md)
