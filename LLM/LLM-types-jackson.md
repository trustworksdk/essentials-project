# Types-Jackson - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.jackson.types`
- Purpose: Jackson serialization/deserialization for `SingleValueType` implementations
- Dependencies: `jackson-databind` (provided), `types` module
- Key class: `EssentialTypesJacksonModule`
- **Status**: WORK-IN-PROGRESS

## TOC
- [Quick Facts](#quick-facts)
- [Core Classes](#core-classes)
- [EssentialTypesJacksonModule API](#essentialtypesjacksonmodule-api)
  - [Registration](#registration)
  - [Factory Method](#factory-method)
  - [Factory Configuration](#factory-configuration)
- [Serialization Behavior](#serialization-behavior)
- [Type Requirements](#type-requirements)
  - [CharSequenceType (Jackson 2.18+)](#charsequencetype-jackson-218)
  - [JSR310SingleValueType](#jsr310singlevaluetype)
- [Map Key Handling](#map-key-handling)
- [Serializers and Deserializers](#serializers-and-deserializers)
  - [CharSequenceTypeJsonSerializer](#charsequencetypejsonserializer)
  - [NumberTypeJsonSerializer](#numbertypejsonserializer)
  - [MoneyDeserializer](#moneydeserializer)
  - [JSR310SingleValueTypeMixIn](#jsr310singlevaluetypemixin)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Core Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `EssentialTypesJacksonModule` | `dk.trustworks.essentials.jackson.types` | Jackson module registering all serializers/deserializers |
| `CharSequenceTypeJsonSerializer` | `dk.trustworks.essentials.jackson.types` | Serializes `CharSequenceType` as JSON string |
| `NumberTypeJsonSerializer` | `dk.trustworks.essentials.jackson.types` | Serializes `NumberType` as JSON number |
| `MoneyDeserializer` | `dk.trustworks.essentials.jackson.types` | Deserializes `Money` from JSON object |

---

## EssentialTypesJacksonModule API

**Location:** `dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule`

```java
public final class EssentialTypesJacksonModule extends SimpleModule {
    // Constructor
    public EssentialTypesJacksonModule();

    // Factory with opinionated defaults (auto-includes this module)
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

### Registration

```java
// Simple registration
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialTypesJacksonModule());

// Or use factory (includes EssentialTypesJacksonModule automatically)
ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper();

// With additional modules
ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper(
    new Jdk8Module(),
    new JavaTimeModule()
);
```

### Factory Method

**Signature:**
```java
public static ObjectMapper createObjectMapper(Module... additionalModules)
```
- Returns: Configured `ObjectMapper` with `EssentialTypesJacksonModule` and additionalModules
- Applies opinionated defaults (see table below)

### Factory Configuration

`createObjectMapper()` applies:

| Setting | Value | Effect |
|---------|-------|--------|
| Field visibility | `Visibility.ANY` | Serialize all fields |
| Getter visibility | `Visibility.NONE` | Ignore getters |
| Setter visibility | `Visibility.NONE` | Ignore setters |
| Creator visibility | `Visibility.ANY` | Detect all constructors |
| `AUTO_DETECT_GETTERS` | disabled | No getter detection |
| `AUTO_DETECT_IS_GETTERS` | disabled | No is-getter detection |
| `AUTO_DETECT_SETTERS` | disabled | No setter detection |
| `AUTO_DETECT_CREATORS` | enabled | Detect constructors |
| `AUTO_DETECT_FIELDS` | enabled | Detect fields |
| `DEFAULT_VIEW_INCLUSION` | disabled | Exclude non-view fields |
| `FAIL_ON_UNKNOWN_PROPERTIES` | disabled | Ignore extra JSON fields |
| `FAIL_ON_EMPTY_BEANS` | disabled | Allow empty objects |
| `PROPAGATE_TRANSIENT_MARKER` | enabled | Respect `transient` keyword |
| `WRITE_DATES_AS_TIMESTAMPS` | disabled | ISO-8601 string format |

---

## Serialization Behavior

| Type | Serialized As | Example JSON |
|------|---------------|--------------|
| `CharSequenceType` subclasses | JSON string | `"ORD-123"` |
| `NumberType` subclasses | JSON number | `99.99` |
| `Money` | JSON object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` | ISO-8601 string | `"2024-01-15T10:30:00Z"` |

---

## Type Requirements

### CharSequenceType (Jackson 2.18+)

**Requires two constructors:**

```java
public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required for Jackson 2.18+
}
```

### JSR310SingleValueType

**Requires `@JsonCreator` on constructor:**

```java
import com.fasterxml.jackson.annotation.JsonCreator;

public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    @JsonCreator
    public TransactionTime(ZonedDateTime value) { super(value); }
}
```

**Supported base types:**

Package: `dk.trustworks.essentials.types`

| Base Type | Wrapped Value |
|-----------|---------------|
| `InstantType` | `Instant` |
| `LocalDateTimeType` | `LocalDateTime` |
| `LocalDateType` | `LocalDate` |
| `LocalTimeType` | `LocalTime` |
| `OffsetDateTimeType` | `OffsetDateTime` |
| `ZonedDateTimeType` | `ZonedDateTime` |

---

## Map Key Handling

**Serialization:** Automatic for all `SingleValueType` keys.

**Deserialization:** Requires custom `KeyDeserializer`:

```java
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.annotation.JsonDeserialize;

public class ProductIdKeyDeserializer extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext ctxt) {
        return ProductId.of(key);
    }
}

// Usage
public class Order {
    @JsonDeserialize(keyUsing = ProductIdKeyDeserializer.class)
    public Map<ProductId, Quantity> items;
}
```

**JSON:**
```json
{
  "items": {
    "PROD-001": 2,
    "PROD-002": 1
  }
}
```

---

## Serializers and Deserializers

### CharSequenceTypeJsonSerializer

**Package:** `dk.trustworks.essentials.jackson.types`

```java
public final class CharSequenceTypeJsonSerializer extends ToStringSerializerBase {
    public CharSequenceTypeJsonSerializer();
    public String valueToString(Object value); // Returns value.toString()
}
```
- Serializes `CharSequenceType.toString()` as JSON string
- Registered for all `CharSequenceType` subclasses

### NumberTypeJsonSerializer

**Package:** `dk.trustworks.essentials.jackson.types`

```java
public final class NumberTypeJsonSerializer extends NumberSerializer {
    public NumberTypeJsonSerializer();
    public void serialize(Number value, JsonGenerator g, SerializerProvider provider);
}
```
- Serializes `NumberType.value()` as JSON number
- Registered for all `NumberType` subclasses

### MoneyDeserializer

**Package:** `dk.trustworks.essentials.jackson.types`

```java
public final class MoneyDeserializer extends StdDeserializer<Money> {
    public MoneyDeserializer();
    public Money deserialize(JsonParser p, DeserializationContext ctxt);
}
```
- Deserializes JSON object `{"amount":"...", "currency":"..."}` to `Money`
- Expects `amount` as decimal, `currency` as string

### JSR310SingleValueTypeMixIn

Internal mixin interface adding `@JsonValue` to `JSR310SingleValueType.value()` method.

**Registered via:**
```java
setMixInAnnotation(JSR310SingleValueType.class, JSR310SingleValueTypeMixIn.class);
```

---

## Common Patterns

### Complete Setup

```java
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return EssentialTypesJacksonModule.createObjectMapper(
            new Jdk8Module(),
            new JavaTimeModule(),
            new ParameterNamesModule()
        );
    }
}
```

### Custom Type with All Requirements

```java
import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }

    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(UUID.randomUUID().toString()); }
}
```

### Serialization Example

```java
public record Order(OrderId id, CustomerId customerId, Amount total) {}

Order order = new Order(
    OrderId.of("ORD-123"),
    CustomerId.of("CUST-456"),
    Amount.of("99.99")
);

String json = objectMapper.writeValueAsString(order);
// {"id":"ORD-123","customerId":"CUST-456","total":99.99}

Order deserialized = objectMapper.readValue(json, Order.class);
```

---

## Gotchas

- ⚠️ `CharSequenceType` subclasses need **both** `CharSequence` and `String` constructors for Jackson 2.18+
- ⚠️ `JSR310SingleValueType` subclasses need `@JsonCreator` on constructor
- ⚠️ Map key deserialization requires explicit `@JsonDeserialize(keyUsing = ...)` annotation
- ⚠️ `Money` serializes as object `{"amount":"...","currency":"..."}`, not single value
- ⚠️ Factory `createObjectMapper()` disables getter/setter detection - uses fields only
- ⚠️ Missing `String` constructor on `CharSequenceType` causes deserialization failures in Jackson 2.18+

---

## See Also

- [README.md](../types-jackson/README.md) - Full documentation with examples
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-immutable-jackson.md](LLM-immutable-jackson.md) - Immutable Jackson support
- [Test: EssentialTypesJacksonModuleTest.java](../types-jackson/src/test/java/dk/trustworks/essentials/jackson/EssentialTypesJacksonModuleTest.java)
