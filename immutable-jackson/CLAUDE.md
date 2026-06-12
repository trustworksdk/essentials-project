# immutable-jackson

Jackson deserialization support for immutable objects (no-arg constructor not required). Maven: `immutable-jackson`.

## Package Structure

- `dk.trustworks.essentials.jackson.immutable` — sole package; contains module + value instantiator

## Key Classes

| Class | Role |
|---|---|
| `EssentialsImmutableJacksonModule` | `SimpleModule` registered with Jackson; hooks `BeanDeserializerModifier` to swap in `ImmutableObjectsValueInstantiator` for every bean |
| `ImmutableObjectsValueInstantiator` | `ValueInstantiator` wrapper; tries standard Jackson instantiation first, falls back to Objenesis if standard instantiator cannot create instance |

`EssentialsImmutableJacksonModule.createObjectMapper(Module...)` — static factory producing opinionated `ObjectMapper`: fields-only visibility, getters/setters disabled, `PROPAGATE_TRANSIENT_MARKER` on, dates as ISO strings, unknown properties ignored.

## Test Structure

Single test class: `EssentialsImmutableJacksonModuleTest`. No Docker, no external infrastructure — pure unit tests using JUnit 5 + AssertJ.

Test model lives under `model/`:
- `ImmutableOrder` — extends `ImmutableValueObject`; final fields, all-args constructor, no no-arg constructor → exercises Objenesis path
- `ImmutableSerializationTestSubject` — plain class with final fields + all-args constructor; uses `@JsonDeserialize(keyUsing=...)` for `Map<ProductId, Quantity>` map keys
- `ProductIdKeyDeserializer` — custom `KeyDeserializer`; required for map-key round-trip because SingleValueType keys need explicit key deserializer registration

Tests verify full serialize → deserialize round-trips using `EssentialTypesJacksonModule` + `Jdk8Module` + `JavaTimeModule` alongside this module.

## Extension Points

No SPIs intended for external extension. Internal extension point: swap/replace `ImmutableObjectsValueInstantiator` by registering a custom `BeanDeserializerModifier` with higher priority — but standard usage is just registering `EssentialsImmutableJacksonModule`.

## Gotchas

- Objenesis path: **no constructor called, no default field values set by Java**. Fields left at JVM zero-values until Jackson injects them via reflection. Any constructor-side validation or computed fields are skipped.
- Field injection works on `final` fields via reflection (`Reflector` from `shared`). Relies on JVM allowing reflective writes to final fields — this can fail under strict module encapsulation; test on target JVM config.
- `canCreateUsingDefault()` always returns `true` — this unconditionally advertises creation capability regardless of whether standard or Objenesis path will be used. Jackson will always route through `createUsingDefault`.
- `_createFromStringFallbacks` is delegated via `Reflector.reflectOn(...).invoke(...)` — accesses protected method on standard instantiator; fragile across Jackson versions if method visibility or name changes.
- `createObjectMapper` opinionated defaults: getters/setters visibility NONE, fields ANY → adding a Jackson module that depends on getter-based serialization will not work unless visibility is re-configured manually.
- Map keys with custom `SingleValueType` need explicit `@JsonDeserialize(keyUsing=...)` — this module does not auto-handle map key deserialization for Essentials types.
- Module name (JPMS): `dk.trustworks.essentials.immutable.jackson`.
