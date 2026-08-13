# Types-Jackson - LLM Reference

> Token-efficient reference for Jackson serialization of Essentials types. For explanations see [README.md](../types-jackson/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.jackson.types`
- Purpose: Jackson serialization/deserialization for **Java** `SingleValueType` implementations
- Dependencies: `jackson-databind` (provided), `types` module
- Key class: `EssentialTypesJacksonModule`

**Two artifacts, one FQCN.** `types-jackson3` is the Jackson 3 flavour and the repository default;
`types-jackson` is Jackson 2. Both publish
`dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule`, extending
`tools.jackson.databind` and `com.fasterxml.jackson.databind` respectively, so only one may ever be on
the classpath. Pick the one matching your application's Jackson major — Spring Boot 4 is Jackson 3.

```xml
<!-- Spring Boot 4 / Jackson 3 -->
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson3</artifactId>
</dependency>

<!-- Jackson 2 -->
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
</dependency>
```

⚠️ **Java hierarchy only.** `EssentialTypesJacksonModule` registers serializers for `CharSequenceType`,
`NumberType`, `Money` and `JSR310SingleValueType`. It has **no** knowledge of
`dk.trustworks.essentials.kotlin.types` — Kotlin semantic types need `jackson-module-kotlin`'s
`KotlinModule` registered alongside it. See [Kotlin semantic types](#kotlin-semantic-types).

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

### NumberType deserialization

**No extra constructor is required — the module deserializes the whole family:**

```java
import dk.trustworks.essentials.types.BigDecimalType;

public class Quantity extends BigDecimalType<Quantity> {
    public Quantity(BigDecimal value) { super(value); }   // the only constructor Jackson needs

    public static Quantity of(BigDecimal value) { return new Quantity(value); }
}
```

`NumberTypeJsonDeserializers` (a `Deserializers` SPI) resolves a `NumberTypeJsonDeserializer` for every concrete
`NumberType` subclass, reads the JSON number at the width the type wraps (via `NumberType.resolveNumberClass`), and
constructs through `SingleValueType.from(...)` so the type's own validation still runs.

⚠️ Registered through the SPI, **not** `addDeserializer(NumberType.class, …)`. The two sides of Jackson are not
symmetric: serializer lookup walks supertypes, so one serializer on the base covers every subclass, but deserializer
lookup is an exact-type match and a registration on the base would never fire.

**Coercion rules it enforces**, which are as load-bearing as the fix — quietly truncating on replay would be worse
than the crash it replaces:

| JSON | `BigDecimalType` | `LongType` / `IntegerType` / `BigIntegerType` | `DoubleType` |
|---|---|---|---|
| `2` | ✅ | ✅ | ✅ |
| `9007199254740993` | ✅ | ✅ (`IntegerType` ❌ — overflow) | ✅ |
| `2.5` | ✅ | ❌ **refused, never truncated to `2`** | ✅ |
| `"2"` (quoted) | ✅ | ✅ | ✅ |
| `"2.5"` (quoted) | ✅ | ❌ refused | ✅ |
| `null` | `null` | `null` | `null` |

Quoted numbers stay readable on purpose — anything persisted with `WRITE_NUMBERS_AS_STRINGS`, or written by a
producer that quotes large numbers, depends on it.

A type extending `NumberType` directly, outside the eight known bases, is left to Jackson's default handling rather
than guessed at.

#### The trap this removed

Before the deserializer existed, concrete subclasses fell through to Jackson's own creator detection, which selects
a creator **by the incoming JSON token's own type** and does not widen. A `BigDecimalType` declaring only the natural
`(BigDecimal)` constructor could not be read from an integral number at all — `"quantity":2` failed with *"no
int/Int-argument constructor/factory method to deserialize from Number value"*. It serialized fine, so the breakage
surfaced only on replay of existing events.

Adding a `(double)` constructor cleared that error and was the obvious workaround — but Jackson then routed every
floating-point token through it, narrowing to a `double` before the `BigDecimal` was built, so
`1234.5678901234567890123` came back as `1234.567890123457`. `Amount` carries such a constructor and did lose
precision this way. Both problems are gone: the deserializer never consults those overloads.

Pinned by `NumberTypeCreatorRequirementTest` (both flavors) and `NumberTypeCreatorPrecisionTest` (`types-jackson3`).

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

## Kotlin semantic types

`EssentialTypesJacksonModule` does **not** cover `dk.trustworks.essentials.kotlin.types` — neither
flavour references that package at all. Kotlin semantic types are handled by `jackson-module-kotlin`,
which the consumer registers:

```kotlin
// Jackson 3
JsonMapper.builder()
    .addModule(EssentialTypesJacksonModule())
    .addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build())
    .build()

// Jackson 2
ObjectMapper()
    .registerModule(EssentialTypesJacksonModule())
    .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
```

⚠️ **Omitting `KotlinModule` fails silently, not loudly.** Jackson treats a `@JvmInline value class` as
an ordinary bean and writes `{"value":"order-4711"}` where the wire contract is the bare scalar
`"order-4711"`. Nothing throws on the way out; the mismatch surfaces later as unreadable persisted
JSON. Asserted on both majors by `KotlinJacksonBodyJackson2Test` / `KotlinJacksonBodyJackson3Test` in
`types-spring-web`.

For an application that persists Kotlin documents, `components/postgresql-document-db`'s
`TestObjectMappers.kt` is the worked example of assembling the flavour's mapper with `KotlinModule`.

---

## Gotchas

- ⚠️ `CharSequenceType` needs **both** `CharSequence` and `String` constructors for Jackson 2.18+
- ⚠️ `NumberType` subclasses need only the value-typed constructor — `NumberTypeJsonDeserializers` handles the family. A fraction is **refused** by the integral bases rather than truncated, and quoted numbers still read
- ⚠️ `JSR310SingleValueType` needs `@JsonCreator` on constructor
- ⚠️ Map key deserialization requires explicit `@JsonDeserialize(keyUsing = ...)`
- ⚠️ `Money` serializes as `{"amount":"...","currency":"..."}` object, not single value
- ⚠️ Factory `createObjectMapper()` disables getter/setter detection - uses fields only
- ⚠️ All registered types are from `dk.trustworks.essentials.types` — the **Java** hierarchy. Kotlin value types need `jackson-module-kotlin`
- ⚠️ This module covers `@RequestBody`/`@ResponseBody` and persistence. `@PathVariable`/`@RequestParam` is `types-spring-web`, a separate mechanism

---

## See Also

- [README.md](../types-jackson/README.md) - Full documentation
- [LLM-types.md](LLM-types.md) - Core types module
- [LLM-immutable-jackson.md](LLM-immutable-jackson.md) - Immutable object Jackson support
- [EssentialTypesJacksonModuleTest.java](../types-jackson/src/test/java/dk/trustworks/essentials/jackson/EssentialTypesJacksonModuleTest.java) - Usage examples
