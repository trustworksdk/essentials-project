# types-avro

Avro serialization/deserialization support for Essentials `SingleValueType` subtypes via custom Avro logical types. Maven: `types-avro`.

Depends on `types` module (provided scope). `avro` jar is also `provided` — consumers must supply both.

## Package Structure

- `dk.trustworks.essentials.types.avro` — all production classes: base conversions, logical-type markers, built-in type pairs (CurrencyCode, Amount, Percentage, CountryCode, EmailAddress)
- `dk.trustworks.essentials.types.avro.test` — test-only conversions/factories/types used by `avro-maven-plugin` at `generate-test-sources`; shipped in `src/main` due to plugin constraint (see Gotchas); excluded from published jar via `maven-jar-plugin`/`maven-source-plugin`
- `dk.trustworks.essentials.types.avro.test.types` — domain types used by tests (OrderId, DueDate, Quantity, etc.)

## Key Classes

| Class | Role |
|---|---|
| `BaseCharSequenceConversion<T>` | Base Avro `Conversion` for `CharSequenceType`; wire format: Avro `string` (UTF-8 bytes or CharSequence) |
| `BaseLongTypeConversion<T>` | Base for `LongType`; wire: Avro `long` |
| `BaseIntegerTypeConversion<T>` | Base for `IntegerType`; wire: Avro `int` |
| `BaseDoubleTypeConversion<T>` | Base for `DoubleType`; wire: Avro `double` |
| `BaseFloatTypeConversion<T>` | Base for `FloatType`; wire: Avro `float` |
| `BaseBigDecimalTypeConversion<T>` | Base for `BigDecimalType` → Avro `string`; supports multiple distinct `BigDecimalType` per schema; precision risk (toString round-trip) |
| `SingleConcreteBigDecimalTypeConversion<T>` | Alternative BigDecimal path → Avro native `decimal` logical type; full precision but ALL decimal fields map to one concrete type |
| `BaseInstantTypeConversion<T>` | Base for `InstantType`; wire: Avro `long` (epoch millis) |
| `BaseLocalDateTypeConversion<T>` | Base for `LocalDateType`; wire: Avro `int` (days since Unix epoch) |
| `BaseLocalTimeTypeConversion<T>` | Base for `LocalTimeType`; wire: Avro `long` (micros since midnight) |
| `BaseLocalDateTimeTypeConversion<T>` | Base for `LocalDateTimeType`; wire: Avro `long` |
| `BaseOffsetDateTimeTypeConversion<T>` | Base for `OffsetDateTimeType`; wire: Avro `long` |
| `BaseZonedDateTimeTypeConversion<T>` | Base for `ZonedDateTimeType`; wire: Avro `long` |
| `CharSequenceTypeLogicalType` | `LogicalType` marker for string-backed types; validates schema must be `STRING` |
| `BigDecimalTypeLogicalType` | `LogicalType` marker for BigDecimal-as-string types; validates schema must be `STRING` |
| `LongTypeLogicalType` / `IntegerTypeLogicalType` / ... | Marker `LogicalType` for each primitive-backed family; validate correct Avro primitive |
| `CurrencyCodeConversion` + `CurrencyCodeLogicalTypeFactory` | Built-in pair for `CurrencyCode`; template for all custom pairs |
| `AmountConversion` + `AmountLogicalTypeFactory` | Built-in pair for `Amount` (BigDecimal-as-string) |
| `PercentageConversion` + `PercentageLogicalTypeFactory` | Built-in pair for `Percentage` |
| `CountryCodeConversion` + `CountryCodeLogicalTypeFactory` | Built-in pair for `CountryCode` |
| `EmailAddressConversion` + `EmailAddressLogicalTypeFactory` | Built-in pair for `EmailAddress` |

## Test Structure

Single test class: `CustomConversionsTest` — round-trip serialize/deserialize an Avro `Order` record covering all supported type families (string, BigDecimal, long, int, date-time variants). No Docker needed; pure in-process Avro binary encoding.

Test Avro IDL at `src/test/avro/order.avdl`. `avro-maven-plugin` runs at `generate-test-sources` phase and generates `Order`/`Money` classes into `target/generated-test-sources`. Factories and conversions referenced in the plugin config must be compiled before code-gen — that's why test support classes live under `src/main`.

## Extension Points

To add Avro support for a new `SingleValueType` subtype:

1. Pick the matching base conversion: `BaseCharSequenceConversion`, `BaseLongTypeConversion`, etc.
2. Create `MyTypeConversion extends Base*Conversion<MyType>` — implement `getConvertedType()` and `getLogicalType()`.
3. Create `MyTypeLogicalTypeFactory implements LogicalTypes.LogicalTypeFactory` — holds a static `LogicalType` instance built from the matching `*LogicalType` marker class.
4. Register both in `avro-maven-plugin` config: `<customLogicalTypeFactories>` and `<customConversions>`.
5. Annotate Avro IDL field with `@logicalType("MyLogicalTypeName")` on correct primitive type.

`BaseBigDecimalTypeConversion` exposes `convertToBigDecimalType(String)` and `convertFromBigDecimalType(T)` as protected overridable hooks for custom precision/format handling.

## Gotchas

- **Test classes in `src/main`**: `avro-maven-plugin` needs conversion/factory classes compiled before IDL code-gen. Test-support classes therefore live in `src/main/java/.../test`. `maven-jar-plugin` and `maven-source-plugin` exclude `**/test/**` from the published artifact — do not move them to `src/test` without reworking the build.
- **`BaseBigDecimalTypeConversion` uses `string` wire format** — multiple distinct `BigDecimalType` per schema OK, but precision loss possible on `BigDecimal.toString()` round-trip. Use `SingleConcreteBigDecimalTypeConversion` + `enableDecimalLogicalType=true` for full precision, at the cost of mapping all `decimal` fields to one type.
- **`enableDecimalLogicalType=false`** in the module's own `avro-maven-plugin` config — required when using `BaseBigDecimalTypeConversion`. Flip to `true` only when using `SingleConcreteBigDecimalTypeConversion`.
- **`stringType=String`** must be set in `avro-maven-plugin` config so generated fields use `java.lang.String`, not `CharSequence` or `Utf8`.
- **LogicalType validation**: each `*LogicalType` marker validates its expected Avro primitive in `validate(Schema)` — mismatch throws at schema parse/compile time, not at runtime.
- **Temporal precision**: `InstantType` → epoch millis (truncates sub-millisecond). `LocalTimeType` → micros since midnight. Assertion in tests uses `within(100, ChronoUnit.MICROS)` tolerance.
- **array/map/union support**: Avro supports `@logicalType` on element types in arrays, map values, and union branches — tested in `order.avdl` with `arrayOfCurrencies`, `mapOfCurrencyValues`, `optionalCurrency`.
