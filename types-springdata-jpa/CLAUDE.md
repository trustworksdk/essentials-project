# types-springdata-jpa

JPA `AttributeConverter` base classes bridging `SingleValueType` hierarchy to JDBC column types. Maven: `types-springdata-jpa`.

**Status: EXPERIMENTAL** — may be discontinued. Prefer `types-jdbi` for SQL persistence.

## Package Structure

- `dk.trustworks.essentials.types.springdata.jpa.converters` — all production code: abstract base converters + built-in converters for `Amount`, `Percentage`, `CurrencyCode`, `CountryCode`, `EmailAddress`

Test-only model under `...jpa.model` and `...jpa.converters` (not shipped).

## Key Classes

| Class | Role |
|---|---|
| `BaseCharSequenceTypeAttributeConverter<T>` | `CharSequenceType` → `String`; delegates to `SingleValueType.from(dbData, class)` |
| `BaseLongTypeAttributeConverter<T>` | `LongType` → `Long` |
| `BaseBigDecimalTypeAttributeConverter<T>` | `BigDecimalType` → `Double` (precision loss risk — see Gotchas) |
| `BaseIntegerTypeAttributeConverter<T>` | `IntegerType` → `Integer` |
| `BaseShortTypeAttributeConverter<T>` | `ShortType` → `Short` |
| `BaseByteTypeAttributeConverter<T>` | `ByteType` → `Byte` |
| `BaseDoubleTypeAttributeConverter<T>` | `DoubleType` → `Double` |
| `BaseFloatTypeAttributeConverter<T>` | `FloatType` → `Float` |
| `BaseInstantTypeAttributeConverter<T>` | `InstantType` → `Instant` |
| `BaseLocalDateTimeTypeAttributeConverter<T>` | `LocalDateTimeType` → `LocalDateTime` |
| `BaseLocalDateTypeAttributeConverter<T>` | `LocalDateType` → `LocalDate` |
| `BaseLocalTimeTypeAttributeConverter<T>` | `LocalTimeType` → `LocalTime` |
| `BaseOffsetDateTimeTypeAttributeConverter<T>` | `OffsetDateTimeType` → `OffsetDateTime` |
| `BaseZonedDateTimeTypeAttributeConverter<T>` | `ZonedDateTimeType` → `ZonedDateTime` |
| `AmountAttributeConverter` | Built-in `autoApply` converter for `Amount` |
| `PercentageAttributeConverter` | Built-in `autoApply` converter for `Percentage` |
| `CurrencyCodeAttributeConverter` | Built-in `autoApply` converter for `CurrencyCode` |
| `CountryCodeAttributeConverter` | Built-in `autoApply` converter for `CountryCode` |
| `EmailAddressAttributeConverter` | Built-in `autoApply` converter for `EmailAddress` |

## Test Structure

- `OrderRepositoryIT` / `ProductRepositoryIT` — `@SpringBootTest` + Testcontainers (`postgres:latest`); require Docker at test time
- `@DynamicPropertySource` wires container JDBC URL into Spring context
- `ddl-auto: create-drop` — schema auto-generated from entity annotations, no migration scripts
- Tests verify raw JDBC values via JDBI to confirm no byte-array serialization leaks
- All custom converters in `src/test` (e.g. `OrderIdAttributeConverter`) are test-only; not shipped

## Extension Points

Pattern for all base converters — extend, override one method, annotate:

```java
@Converter(autoApply = true)
public class MyTypeConverter extends BaseCharSequenceTypeAttributeConverter<MyType> {
    @Override
    protected Class<MyType> getConcreteCharSequenceType() { return MyType.class; }
}
```

One converter class per `SingleValueType` subclass — JPA does not support generic converters.

## Gotchas

- `BigDecimalType` stores as `Double` → precision loss for high-scale decimals. Use `types-jdbi` for financial data.
- `@Id` not supported on `SingleValueType` fields directly. Must use `@EmbeddedId` + `@Embeddable`. `@Embeddable` IDs need a duplicate persistent field (e.g. `private Long orderId`) because Hibernate requires a persistent id property it can introspect — the `SingleValueType` value field is not visible to it.
- `@Embeddable` id type cannot be reused as both `@EmbeddedId` and a regular column on the same entity.
- No JPA id autogeneration (`@GeneratedValue`) — IDs must be generated manually (e.g. `OrderId.random()`).
- `@ElementCollection` with `SingleValueType` map keys (`Map<ProductId, Quantity>`) does not work — commented out in test model, not a supported pattern.
- Temporal types use microsecond precision in tests (`within(100, ChronoUnit.MICROS)`) — DB round-trip truncates nanoseconds.
- `autoApply = true` on custom converters means they apply globally — naming conflicts across modules possible if same type appears in multiple persistence units.
