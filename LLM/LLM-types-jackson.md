# Types-Jackson - LLM Reference

> Token-efficient reference for Jackson serialization of Essentials types. For explanations see [README.md](../types-jackson/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.jackson.types`
- Purpose: Jackson serialization/deserialization for `SingleValueType` implementations
- Dependencies: `jackson-databind` (provided), `types` module
- Key class: `EssentialTypesJacksonModule`

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
</dependency>
```

## TOC
- [Core API](#core-api)
- [Serialization Behavior](#serialization-behavior)
- [Type Requirements](#type-requirements)
- [Map Keys](#map-keys)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

---

## Core API

Base package: `dk.trustworks.essentials.jackson.types`

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, `Money`, `Amount`, `CurrencyCode` from [types](./LLM-types.md)

| Class | Purpose |
|-------|---------|
| `EssentialTypesJacksonModule` | Jackson module registering all serializers/deserializers |
| `CharSequenceTypeJsonSerializer` | Serializes `CharSequenceType` as JSON string |
| `NumberTypeJsonSerializer` | Serializes `NumberType` as JSON number |
| `MoneyDeserializer` | Deserializes `Money` from JSON object |

### EssentialTypesJacksonModule

```java
package dk.trustworks.essentials.jackson.types;

public final class EssentialTypesJacksonModule extends SimpleModule {
    public EssentialTypesJacksonModule();

    // Factory with opinionated defaults
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

**Registration:**
```java
// Manual
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialTypesJacksonModule());

// Factory (includes EssentialTypesJacksonModule + defaults)
ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper(
    new Jdk8Module(),
    new JavaTimeModule()
);
```

**Factory defaults:**

| Feature | Setting | Effect |
|---------|---------|--------|
| Field visibility | `ANY` | Serialize all fields |
| Getter/setter visibility | `NONE` | Ignore getters/setters |
| `AUTO_DETECT_GETTERS` | disabled | No getter detection |
| `AUTO_DETECT_SETTERS` | disabled | No setter detection |
| `AUTO_DETECT_FIELDS` | enabled | Detect fields |
| `AUTO_DETECT_CREATORS` | enabled | Detect constructors |
| `FAIL_ON_UNKNOWN_PROPERTIES` | disabled | Ignore extra JSON |
| `FAIL_ON_EMPTY_BEANS` | disabled | Allow empty objects |
| `PROPAGATE_TRANSIENT_MARKER` | enabled | Respect `transient` |
| `WRITE_DATES_AS_TIMESTAMPS` | disabled | ISO-8601 strings |

### CharSequenceTypeJsonSerializer

```java
package dk.trustworks.essentials.jackson.types;

public final class CharSequenceTypeJsonSerializer extends ToStringSerializerBase {
    public CharSequenceTypeJsonSerializer();
    public String valueToString(Object value); // Returns value.toString()
}
```

Registered for: All `dk.trustworks.essentials.types.CharSequenceType` subclasses

### NumberTypeJsonSerializer

```java
package dk.trustworks.essentials.jackson.types;

public final class NumberTypeJsonSerializer extends NumberSerializer {
    public NumberTypeJsonSerializer();
    public void serialize(Number value, JsonGenerator g, SerializerProvider provider);
}
```

Registered for: All `dk.trustworks.essentials.types.NumberType` subclasses

### MoneyDeserializer

```java
package dk.trustworks.essentials.jackson.types;

public final class MoneyDeserializer extends StdDeserializer<Money> {
    public MoneyDeserializer();
    public Money deserialize(JsonParser p, DeserializationContext ctxt);
}
```

Expects: `{"amount":"...", "currency":"..."}`

---

## Serialization Behavior

| Type (from `dk.trustworks.essentials.types`) | JSON Format | Example |
|----------------------------------------------|-------------|---------|
| `CharSequenceType` subclasses | String | `"ORD-123"` |
| `NumberType` subclasses | Number | `99.99` |
| `Money` | Object | `{"amount":"99.99","currency":"USD"}` |
| `JSR310SingleValueType` subclasses | ISO-8601 string | `"2024-01-15T10:30:00Z"` |

---

## Type Requirements

### CharSequenceType (Jackson 2.18+)

**Two constructors required:**

```java
import dk.trustworks.essentials.types.CharSequenceType;

public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }  // Required for Jackson 2.18+

    public static OrderId of(CharSequence value) { return new OrderId(value); }
}
```

⚠️ Missing `String` constructor → deserialization fails in Jackson 2.18+

### JSR310SingleValueType

**@JsonCreator required:**

```java
import com.fasterxml.jackson.annotation.JsonCreator;
import dk.trustworks.essentials.types.ZonedDateTimeType;
import java.time.ZonedDateTime;

public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    @JsonCreator
    public TransactionTime(ZonedDateTime value) { super(value); }
}
```

**Supported base types (from `dk.trustworks.essentials.types`):**

| Base Class | Wrapped Type |
|------------|--------------|
| `InstantType` | `Instant` |
| `LocalDateTimeType` | `LocalDateTime` |
| `LocalDateType` | `LocalDate` |
| `LocalTimeType` | `LocalTime` |
| `OffsetDateTimeType` | `OffsetDateTime` |
| `ZonedDateTimeType` | `ZonedDateTime` |

---

## Map Keys

**Serialization:** Automatic for all `SingleValueType` keys

**Deserialization:** Requires `KeyDeserializer`

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

## Common Patterns

### Spring Configuration

```java
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return EssentialTypesJacksonModule.createObjectMapper(
            new Jdk8Module(),
            new JavaTimeModule()
        );
    }
}
```

### Complete Type Definition

```java
import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class CustomerId extends CharSequenceType<CustomerId> implements Identifier {
    public CustomerId(CharSequence value) { super(value); }
    public CustomerId(String value) { super(value); }

    public static CustomerId of(CharSequence value) { return new CustomerId(value); }
    public static CustomerId random() {
        return new CustomerId(UUID.randomUUID().toString());
    }
}
```

### Serialization/Deserialization

```java
import dk.trustworks.essentials.types.*;

public record Order(
    OrderId id,
    CustomerId customerId,
    Amount total
) {}

ObjectMapper mapper = EssentialTypesJacksonModule.createObjectMapper();

Order order = new Order(
    OrderId.of("ORD-123"),
    CustomerId.of("CUST-456"),
    Amount.of("99.99")
);

String json = mapper.writeValueAsString(order);
// {"id":"ORD-123","customerId":"CUST-456","total":99.99}

Order restored = mapper.readValue(json, Order.class);
```

---

## Gotchas

- ⚠️ `CharSequenceType` needs **both** `CharSequence` and `String` constructors for Jackson 2.18+
- ⚠️ `JSR310SingleValueType` needs `@JsonCreator` on constructor
- ⚠️ Map key deserialization requires explicit `@JsonDeserialize(keyUsing = ...)`
- ⚠️ `Money` serializes as `{"amount":"...","currency":"..."}` object, not single value
- ⚠️ Factory `createObjectMapper()` disables getter/setter detection - uses fields only
- ⚠️ All registered types are from `dk.trustworks.essentials.types` module

---

## See Also

- [README.md](../types-jackson/README.md) - Full documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-immutable-jackson.md](LLM-immutable-jackson.md) - Immutable object Jackson support
- [EssentialTypesJacksonModuleTest.java](../types-jackson/src/test/java/dk/trustworks/essentials/jackson/EssentialTypesJacksonModuleTest.java) - Usage examples
