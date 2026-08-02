# immutable-jackson3

Jackson 3.x deserialization support for immutable objects (no-arg constructor not required). Maven: `immutable-jackson3`.

Jackson 3 port of `immutable-jackson`. Same logic; different package namespace: `tools.jackson.*` instead of `com.fasterxml.jackson.*`.

## Package Structure

- `dk.trustworks.essentials.jackson.immutable` — sole package; module + value instantiator (same package name as jackson2 sibling; different artifact/classpath)

## Key Classes

| Class | Role |
|---|---|
| `EssentialsImmutableJacksonModule` | Extends `tools.jackson.databind.module.SimpleModule`; registers `ValueDeserializerModifier` (Jackson3 rename of `BeanDeserializerModifier`) to swap in `ImmutableObjectsValueInstantiator` for every bean |
| `ImmutableObjectsValueInstantiator` | `tools.jackson.databind.deser.ValueInstantiator` wrapper; tries standard Jackson instantiation first, Objenesis fallback if standard cannot create instance; implements `createContextual` (required in Jackson 3) |

`EssentialsImmutableJacksonModule.createObjectMapper(JacksonModule...)` — static factory: fields-only visibility, getters/setters disabled, `PROPAGATE_TRANSIENT_MARKER` on, dates as ISO strings, unknown properties ignored.

## Key Jackson 3 Differences vs immutable-jackson

- Package namespace: `tools.jackson.*` (Jackson 3 GroupId `tools.jackson.core`)
- `BeanDeserializerModifier` → `ValueDeserializerModifier`; `SetupContext.addBeanDeserializerModifier` → `addDeserializerModifier`
- `IOException` on overrides → `JacksonException`
- `ImmutableObjectsValueInstantiator` must implement `createContextual(DeserializationContext, BeanDescription.Supplier)` — recreates a contextualized copy with the standard instantiator resolved
- `_createFromStringFallbacks` hack (reflective protected-method call) not present — Jackson 3 API makes it unnecessary
- `Jdk8Module` + `JavaTimeModule` built into Jackson 3 core → tests only register `EssentialTypesJacksonModule`

## Test Structure

Single test class: `EssentialsImmutableJacksonModuleTest`. No Docker — pure unit tests, JUnit 5 + AssertJ.

Test model under `model/`:
- `ImmutableOrder` — extends `ImmutableValueObject`; final fields, all-args constructor only → exercises Objenesis path
- `ImmutableSerializationTestSubject` — final fields + all-args constructor; `Map<ProductId, Quantity>` uses `@JsonDeserialize(keyUsing=...)`
- `ProductIdKeyDeserializer` — custom `KeyDeserializer`; required for map-key round-trip (SingleValueType keys need explicit key deserializer)

Tests verify full serialize → deserialize round-trips via `EssentialTypesJacksonModule` (from `types-jackson3`).

## Extension Points

No SPIs for external extension. Internal: register custom `ValueDeserializerModifier` with higher priority to replace `ImmutableObjectsValueInstantiator`. Standard usage: register `EssentialsImmutableJacksonModule`.

## Gotchas

- Objenesis path: **no constructor called, no default field values set by Java**. Fields sit at JVM zero-values until Jackson injects via reflection. Constructor validation and computed fields are skipped.
- `canCreateUsingDefault()` always returns `true` — Jackson always routes through `createUsingDefault`; the Objenesis/standard split is internal.
- `createContextual` must propagate — it wraps the contextualized standard instantiator in a new `ImmutableObjectsValueInstantiator`; omitting this causes context-sensitive instantiation failures.
- `createObjectMapper` opinionated defaults: getters/setters NONE, fields ANY → modules relying on getter-based serialization will not work without manual visibility reconfiguration.
- Map keys with `SingleValueType` need explicit `@JsonDeserialize(keyUsing=...)` — module does not auto-handle Essentials map-key deserialization.
- `jackson-databind` and `objenesis` are `provided` scope → consumer must supply compatible versions.
- JPMS module name: `dk.trustworks.essentials.immutable.jackson3`.
- No `src/` in worktree — sources live in `immutable-jackson` sibling; `immutable-jackson3` is built from shared sources with Jackson3 on compile classpath.
