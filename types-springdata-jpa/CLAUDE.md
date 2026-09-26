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
| `BaseBigDecimalTypeAttributeConverter<T>` | `BigDecimalType` → `Double` / `double precision` (lossy — see Gotchas) |
| `BaseBigDecimalTypeNumericAttributeConverter<T>` | `BigDecimalType` → `BigDecimal` / `numeric` (exact at column's scale; prefer for money) |
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
| `AmountAttributeConverter` | Built-in `autoApply` converter for `Amount` (`double precision`) |
| `AmountNumericAttributeConverter` | Built-in opt-in converter for `Amount` (`numeric`); not `autoApply` |
| `PercentageAttributeConverter` | Built-in `autoApply` converter for `Percentage` (`double precision`) |
| `PercentageNumericAttributeConverter` | Built-in opt-in converter for `Percentage` (`numeric`); not `autoApply` |
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

- `BigDecimalType` → `Double` is lossy: drops written scale (`1999.50` → `1999.5`; `BigDecimal.equals` is scale-sensitive) and makes SQL `sum`/`avg` floating point. Money uses `AmountNumericAttributeConverter` / `PercentageNumericAttributeConverter` via `@Convert` + `@Column(precision, scale)`.
- Numeric variants deliberately **not** `autoApply` — two auto-applied converters per type are ambiguous, and flipping the default changes the generated column type. Flip considered for 0.60 and rejected: a generated schema without `@Column(scale)` is `numeric(38,2)`, which rounds (`123.456` → `123.46`, seen in `OrderRepositoryIT`) and pads (`100.5` → `100.50`, not `equals`, seen in `ProductRepositoryIT`) — silent loss, worse than `double`. Revisit only with a way to make the unspecified case exact
- `numeric(p,s)` returns values at scale `s` — exact but not `equals` to a value written at another scale. Only unconstrained `numeric` round-trips any scale
- `@Id` not supported on `SingleValueType` fields directly. Must use `@EmbeddedId` + `@Embeddable`. `@Embeddable` IDs need a duplicate persistent field (e.g. `private Long orderId`) because Hibernate requires a persistent id property it can introspect — the `SingleValueType` value field is not visible to it.
- `@Embeddable` id type cannot be reused as both `@EmbeddedId` and a regular column on the same entity.
- No JPA id autogeneration (`@GeneratedValue`) — IDs must be generated manually (e.g. `OrderId.random()`).
- `@ElementCollection` with `SingleValueType` map keys (`Map<ProductId, Quantity>`) does not work — commented out in test model, not a supported pattern.
- Temporal types use microsecond precision in tests (`within(100, ChronoUnit.MICROS)`) — DB round-trip truncates nanoseconds.
- `autoApply = true` on custom converters means they apply globally — naming conflicts across modules possible if same type appears in multiple persistence units.
