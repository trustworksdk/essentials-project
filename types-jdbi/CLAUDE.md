# types-jdbi

JDBI v3 integration for Essentials `SingleValueType` — ArgumentFactory (write) + ColumnMapper (read). Maven: `types-jdbi`.

## Package Structure

| Package | Contents |
|---------|----------|
| `dk.trustworks.essentials.types.jdbi` | Java base classes: `*TypeArgumentFactory`, `*TypeColumnMapper` + built-ins for `Amount`, `Percentage`, `CountryCode`, `CurrencyCode`, `EmailAddress` |
| `dk.trustworks.essentials.kotlin.types.jdbi` | Kotlin mirrors: `*ValueTypeArgumentFactory`, `*ValueTypeColumnMapper` + same built-ins; uses `primaryConstructor!!.call(value)` for instantiation |

## Key Classes

**Java (extends `types` SingleValueType hierarchy)**

| Class | Role |
|-------|------|
| `CharSequenceTypeArgumentFactory<T>` | Base → `Types.VARCHAR`, calls `value.toString()` |
| `LongTypeArgumentFactory<T>` | Base → `Types.BIGINT`, calls `value.value()` |
| `BigDecimalTypeArgumentFactory<T>` | Base → `Types.NUMERIC`, calls `value.value()` |
| `InstantTypeArgumentFactory<T>` | Base → `Types.TIMESTAMP`, wraps via `Timestamp.from()` |
| `LocalDateTypeArgumentFactory<T>` | Base → `Types.DATE` |
| `LocalTimeTypeArgumentFactory<T>` | Base → `Types.TIME` |
| `OffsetDateTimeTypeArgumentFactory<T>` | Base → `Types.TIMESTAMP_WITH_TIMEZONE` |
| `ZonedDateTimeTypeArgumentFactory<T>` | Base → `Types.TIMESTAMP_WITH_TIMEZONE` |
| `CharSequenceTypeColumnMapper<T>` | Reads `getString`, uses `SingleValueType.from(value, concreteType)` |
| `LongTypeColumnMapper<T>` | Reads `getLong`, uses `SingleValueType.from(value, concreteType)` |
| `BigDecimalTypeColumnMapper<T>` | Reads `getBigDecimal` |

**Kotlin (extends `kotlin-types` ValueType hierarchy)**

| Class | Role |
|-------|------|
| `StringValueTypeArgumentFactory<T>` | Kotlin string-based types → VARCHAR |
| `LongValueTypeColumnMapper<T>` | Reads `getLong`, instantiates via `KClass.primaryConstructor!!.call(value)` |
| `StringValueTypeColumnMapper<T>` | Reads `getString`, null-safe; instantiates via `primaryConstructor!!.call(value)` |

**Java type resolution**: `GenericType.resolveGenericTypeOnSuperClass(this.getClass(), 0)` — resolves `T` at runtime from subclass generic parameter. No-arg constructors depend on this; explicit `Class<T>` constructor available as escape hatch.

**Kotlin type resolution**: Same `GenericType.resolveGenericTypeOnSuperClass(...)` → `.kotlin` → `KClass<T>`.

## Test Structure

- **Java test**: `SingleValueTypeArgumentsTest` — single integration test, H2 in-memory DB (no Docker), registers factories/mappers, round-trips all supported column types. Test model types live in `src/test/java/.../model/`.
- **Kotlin test**: `KotlinValueTypeArgumentsTest` — mirrors Java test; `CurrencyCode`, `EmailAddress`, `Percentage` columns commented out (Kotlin built-ins not yet implemented for those types).
- No unit tests for individual classes — coverage via round-trip only.

## Extension Points

To add a new `SingleValueType` subtype:

1. Java: extend the matching `*TypeArgumentFactory<T>` and `*TypeColumnMapper<T>` — empty body suffices.
2. Kotlin: extend matching `*ValueTypeArgumentFactory<T>` / `*ValueTypeColumnMapper<T>`.
3. Register both on `Jdbi` instance via `jdbi.registerArgument(...)` / `jdbi.registerColumnMapper(...)`.

No SPI/auto-discovery — all registration is manual.

## Gotchas

- `CharSequenceTypeColumnMapper` returns `null` when column is SQL NULL; numeric mappers do not — `getLong` returns `0` for NULL unless overridden.
- `LocalTimeType` loses sub-second precision through JDBC `Time` → use `isCloseTo` in tests, not `equals`.
- Kotlin `ColumnMapper` classes return `T?` (nullable) — Java callers must handle.
- `jdbi3-core` is `provided` scope — consumers must declare it themselves.
- Kotlin mappers call `primaryConstructor!!.call(value)` — types must have a single-arg primary constructor matching the primitive type exactly.
- `GenericType.resolveGenericTypeOnSuperClass` fails if subclass is itself generic or anonymous — always use concrete named subclasses.
