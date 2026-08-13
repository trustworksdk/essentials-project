## types-jackson

Jackson serialization/deserialization support for Essentials `SingleValueType` hierarchy. Maven: `types-jackson`.

## Package Structure

- `dk.trustworks.essentials.jackson.types` — all production code
- `dk.trustworks.essentials.jackson` (test) — single integration test
- `dk.trustworks.essentials.jackson.model` (test) — concrete type fixtures used by test

## Key Classes

| Class | Role |
|-------|------|
| `EssentialTypesJacksonModule` | `SimpleModule` subclass; registers serializers, deserializer, mixin in `setupModule()`; also factory for opinionated `ObjectMapper` |
| `CharSequenceTypeJsonSerializer` | Extends `ToStringSerializerBase`; calls `value.toString()` → JSON string |
| `NumberTypeJsonSerializer` | Extends `NumberSerializer`; unwraps inner `value()` before delegating to parent |
| `MoneyDeserializer` | `StdDeserializer<Money>`; reads `amount` + `currency` fields from JSON object node |
| `NumberTypeJsonDeserializer` | Counterpart to `NumberTypeJsonSerializer`; reads a JSON number at the width the concrete type wraps, refuses a fraction for integral bases, accepts quoted numbers |
| `NumberTypeJsonDeserializers` | `Deserializers` SPI resolving the above per concrete `NumberType`; needed because deserializer lookup is exact-type, not supertype-walking |
| `JSR310SingleValueTypeMixIn` (private interface) | Injects `@JsonValue` on `value()` for all `JSR310SingleValueType` subclasses; avoids annotating each concrete class |

## Test Structure

Single test class: `EssentialTypesJacksonModuleTest`. No Docker, no external infra.

Pattern: build one fat `SerializationTestSubject` holding all type variants → serialize → deserialize → assert each field's runtime type is preserved and full equality holds.

`SerializationTestSubject` covers: `CharSequenceType` IDs, `LongType` ID, `Amount`/`Percentage`/`Money`, `CurrencyCode`/`CountryCode`/`EmailAddress`, `Map<ProductId, Quantity>` (map-key path), and all six JSR310 temporal types.

Test model classes live under `src/test/java/.../model/` — concrete subtypes used only as fixtures, not shipped.

## Extension Points

No SPI. Module is closed/final. To support new type families:

1. Write serializer/deserializer extending appropriate Jackson base (`StdSerializer`, `StdDeserializer`, `KeyDeserializer`, etc.)
2. Register in `EssentialTypesJacksonModule.setupModule()` via `addSerializer`/`addDeserializer`/`setMixInAnnotation`

## Gotchas

**CharSequenceType needs two constructors (Jackson 2.18+).** Jackson 2.18 changed constructor resolution — concrete subclasses must have both `(CharSequence value)` and `(String value)` constructors or deserialization fails silently with a type error. The test model `CustomerId` only has `(CharSequence)` — it predates the 2.18 change; new types should add both.

**NumberTypeJsonSerializer unwraps before delegating.** `NumberType` extends `Number` but its own numeric value is accessed via `.value()`, not casting directly. The override is essential; omitting it causes the wrapper object to be serialized, not the primitive number.

**JSR310 types need `@JsonCreator` on their single-arg constructor.** The mixin adds `@JsonValue` for serialization, but deserialization relies on `@JsonCreator` being present on the concrete class constructor. Missing it → deserialization returns null or throws.

**Map keys: serialization is free, deserialization is manual.** `SingleValueType` keys serialize via `toString()` automatically. Deserializing back requires a concrete `KeyDeserializer` registered with `@JsonDeserialize(keyUsing = ...)` on the field. No automatic lookup exists.

**`createObjectMapper` disables getter/setter visibility entirely.** Fields are serialized directly. Adding getters to DTO classes does not affect JSON shape. Mixing this mapper with a getter-based framework (e.g. plain Spring Boot default mapper) produces different JSON — keep mappers aligned.

**`Money` has no serializer, only a deserializer.** Serialization relies on Jackson field-based default (fields `amount` + `currency` emitted directly). The JSON contract is `{"amount":"...","currency":"..."}` — changing `Money`'s field names breaks the custom deserializer.

**`NumberType` deserialization is registered via the `Deserializers` SPI, not `addDeserializer`.** Serializer lookup walks supertypes, so `addSerializer(NumberType.class, …)` covers every subclass; deserializer lookup is an exact-type match, so the same trick does not work and `NumberTypeJsonDeserializers` must resolve per concrete class. Without it, subclasses fall back to Jackson's creator detection, which picks a creator by JSON token type and will not widen an integral token to `BigDecimal` — a `BigDecimalType` with only the natural `(BigDecimal)` constructor serialized fine and failed on replay. The deserializer also owns the coercion rules: a fraction is **refused** by the integral bases rather than silently truncated, and quoted numbers stay readable. Pinned by `NumberTypeCreatorRequirementTest`.
