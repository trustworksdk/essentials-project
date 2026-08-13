# types-jackson3

Jackson 3.x serialization/deserialization support for Essentials `SingleValueType` hierarchy. Maven: `types-jackson3`.

Parallel sibling of `types-jackson` (Jackson 2.x). Same source, same package names — compiled against `tools.jackson.core:jackson-databind:3.x` (groupId `tools.jackson.core`) instead of `com.fasterxml.jackson.core`.

## Package Structure

- `dk.trustworks.essentials.jackson.types` — all production classes (serializers, deserializer, module)
- `dk.trustworks.essentials.jackson` (test) — integration test
- `dk.trustworks.essentials.jackson.model` (test) — domain model fixtures used by the single test

## Key Classes

| Class | Role |
|---|---|
| `EssentialTypesJacksonModule` | `SimpleModule` subclass; registers all ser/deser in `setupModule()`; exposes `createObjectMapper()` factory |
| `CharSequenceTypeJsonSerializer` | `ToStringSerializerBase` → serializes any `CharSequenceType` as JSON string via `toString()` |
| `NumberTypeJsonSerializer` | `NumberSerializer` subclass → unwraps `NumberType.value()` before delegating to parent |
| `MoneyDeserializer` | `StdDeserializer<Money>` → reads `{"amount":…,"currency":…}` nodes explicitly; no generic magic |
| `NumberTypeJsonDeserializer` | Counterpart to `NumberTypeJsonSerializer`; reads a JSON number at the width the concrete type wraps, refuses a fraction for integral bases, accepts quoted numbers |
| `NumberTypeJsonDeserializers` | `Deserializers` SPI resolving the above per concrete `NumberType`; needed because deserializer lookup is exact-type, not supertype-walking |
| `JSR310SingleValueTypeMixIn` (private interface) | Mix-in applied to `JSR310SingleValueType` to attach `@JsonValue` on `value()` → ISO-8601 output |
| `SingleValueTypeCreatorIntrospector` | `NopAnnotationIntrospector` — pins every single-arg value-type constructor to `JsonCreator.Mode.DELEGATING`, so a value type reads the bare scalar regardless of mapper config. Registered via `context.insertAnnotationIntrospector` |
| `SingleValueTypeKeyDeserializers` | `KeyDeserializers` — turns a JSON object key back into any `SingleValueType`, so value-type-keyed maps need no `keyUsing` annotation. Registered via `context.addKeyDeserializers` |

## Test Structure

`SingleValueTypeCreatorModeTest` — value type still reads a bare scalar under `ALLOW_FINAL_FIELDS_AS_MUTATORS` and under `USE_PROPERTIES_BASED`. Both settings otherwise make Jackson 3 treat a value type as a bean expecting `{"value":"…"}`, silently changing persisted format of every id.

`WireFormatCompatibilityTest` — golden file shared with `types-jackson`; proves both majors write the same bytes.

`EssentialTypesJacksonModuleTest`. One round-trip test covering all supported types:
- `CharSequenceType` subtypes (ids, codes, email, currency, country)
- `NumberType` subtypes (amount, percentage, quantity)
- `Money` (object form)
- `JSR310SingleValueType` subtypes (temporal wrappers: `Created`, `DueDate`, `LastUpdated`, `TimeOfDay`, `TransactionTime`, `TransferTime`)
- `Map<ProductId, Quantity>` with `@JsonDeserialize(keyUsing=ProductIdKeyDeserializer.class)` to show map-key pattern

No Docker, no external infra needed. Pure unit test.

## Extension Points

No SPIs. Module is `final`. To support new types:
- New `CharSequenceType` / `NumberType` subclasses → work automatically via base serializers
- New `JSR310SingleValueType` subclasses → work automatically via mix-in on the base class
- Compound types like `Money` (no `SingleValueType` base) → need explicit `StdDeserializer` + registration in `setupModule()`

## Gotchas

- **Mirror every change into `types-jackson`.** The two modules are hand-kept parallel sources, not generated — same FQCNs, different Jackson major.
- **Jackson 3 groupId is `tools.jackson.core`** (not `com.fasterxml.jackson.core`) — wrong import resolves against Jackson 2 at test time; both are on classpath (annotations jar is still `com.fasterxml`).
- **`jackson-annotations` stays `com.fasterxml`** — Jackson 3 still ships annotations under `com.fasterxml.jackson.annotation`; only databind/core moved to `tools.jackson`.
- **`createObjectMapper()` disables getter/setter detection** — serialization is field-based. Adding a getter to a domain class does NOT expose it as a JSON property; must be a field.
- **`Money` deserialization hard-codes field names** `"amount"` and `"currency"` — any rename of `Money` fields breaks deserialization silently.
- **Map keys need no annotation here** — `SingleValueTypeKeyDeserializers` converts a text key back into any `SingleValueType` (char-sequence, numeric, boolean families), so `Map<ProductId, Integer>` round-trips as-is. This differs from `types-jackson`, which still needs `@JsonDeserialize(keyUsing=…)` per property. **The upgrade hazard it removes:** that annotation lives in Jackson 2's `com.fasterxml.jackson.databind.annotation` package, which Jackson 3 does not read, so it silently stops applying and persisted data becomes unreadable with only "Cannot find a (Map) Key deserializer" to go on. It surfaced as aggregate snapshots failing to deserialize. Pinned by `SingleValueTypeMapKeyTest`. An explicit `keyUsing` still wins.
- **JPMS Automatic-Module-Name** → `dk.trustworks.essentials.types.jackson3`
- **`SingleValueTypeCreatorIntrospector` is load-bearing — never remove it.** `EssentialsObjectMappers.createJackson3ObjectMapper` enables `ALLOW_FINAL_FIELDS_AS_MUTATORS` (Jackson 2's default, needed to populate immutable payloads). That makes a value type's wrapped `value` field a mutator, so without this pin Jackson reinterprets every value type as a bean reading `{"value":"…"}` instead of the bare scalar — silent on write, fatal when reading back existing data. `USE_PROPERTIES_BASED` breaks it identically. Both cases are pinned by `SingleValueTypeCreatorModeTest`.
- **`NumberType` deserialization is registered via the `Deserializers` SPI, not `addDeserializer`.** Serializer lookup walks supertypes, so `addSerializer(NumberType.class, …)` covers every subclass; deserializer lookup is an exact-type match, so the same trick does not work and `NumberTypeJsonDeserializers` must resolve per concrete class. Without it, subclasses fall back to Jackson's creator detection, which picks a creator by JSON token type and will not widen an integral token to `BigDecimal` — a `BigDecimalType` with only the natural `(BigDecimal)` constructor serialized fine and failed on replay. The deserializer also owns the coercion rules: a fraction is **refused** by the integral bases rather than silently truncated, and quoted numbers stay readable. Pinned by `NumberTypeCreatorRequirementTest`.
