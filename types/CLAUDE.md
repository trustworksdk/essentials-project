# types

Strongly-typed domain primitive wrappers — immutable value objects over primitives, numbers, strings, and JSR-310 dates. Maven: `types`.

## Package Structure

| Package | Contents |
|---|---|
| `dk.trustworks.essentials.types` | All Java base types, concrete domain types (Amount, Money, etc.), marker interfaces |
| `dk.trustworks.essentials.kotlin.types` | Kotlin interface mirrors (StringValueType, LongValueType, etc.) — lightweight, no Java inheritance |

No sub-packages in main sources; test sources use `types.ids` and `types.dates` for fixture types.

## Key Classes

| Class/Interface | Role |
|---|---|
| `SingleValueType<V,C>` | Root interface; `value()`, `Serializable`, `Comparable`; holds `from()`/`fromObject()` factory via reflection |
| `NumberType<N,C>` | Abstract base for numeric wrappers; extends `java.lang.Number`; adds comparison helpers |
| `CharSequenceType<C>` | Abstract base for string wrappers; implements `CharSequence` in full; stores value as `String` internally |
| `JSR310SingleValueType<V,C>` | Marker interface for date/time wrappers (Instant, LocalDate/Time/DateTime, OffsetDateTime, ZonedDateTime) |
| `LongType<C>` | Concrete numeric base; adds `increment()`/`decrement()` via reflection factory |
| `BigDecimalType<C>` | Concrete numeric base; backing type for Amount, Percentage |
| `Amount` | Final `BigDecimalType<Amount>` — monetary amount without currency |
| `Money` | Composite (Amount + CurrencyCode); guards cross-currency ops with `NotTheSameCurrenciesException` |
| `Percentage` | Final `BigDecimalType<Percentage>` |
| `CurrencyCode` | Final `CharSequenceType<CurrencyCode>` |
| `CountryCode` | Final `CharSequenceType<CountryCode>` |
| `EmailAddress` | Final `CharSequenceType<EmailAddress>` |
| `Identifier` | Marker interface — tag concrete types that act as aggregate/entity identifiers |
| `LongRange` | Value object for [from, to] long ranges; `toInclusive` nullable → open-ended |
| `TimeWindow` | Value object for [fromInclusive, toExclusive) `Instant` ranges; `toExclusive` nullable → open-ended |
| `LocalDateType<C>` / `InstantType<C>` / etc. | Abstract JSR-310 wrappers delegating full temporal API to wrapped value |
| Kotlin `*ValueType` interfaces | Thin interfaces (StringValueType, LongValueType, BigDecimalValueType, etc.) for Kotlin value-type idiom |

## Test Structure

- Pure unit tests — no Docker, no Spring, no external infra
- Fixtures under `src/test/java/.../types/ids/` (AccountId, CustomerId, OrderId, etc.) and `types/dates/` (DueDate, Created, etc.) — canonical "how to subclass" examples
- JUnit 5 + AssertJ
- One test class per concrete type or base class; covers equality, comparison, `from()`/`fromObject()` reflection factory

## Extension Points

- **New primitive base type** → implement `SingleValueType<V,C>` directly, or extend `NumberType<N,C>` for numeric variants
- **New concrete domain type** → subclass one of the base classes (e.g. `extends LongType<MyId> implements Identifier`)
  - Convention: provide `(VALUE_TYPE value)` constructor + static `of(...)` + `ofNullable(...)` — `SingleValueType.fromObject()` searches in that order via reflection
  - A `NumberType` subclass needs no extra constructor for JSON — `types-jackson`/`types-jackson3` deserialize the family at the wrapped width. Convenience overloads are not part of the wire contract; see `LLM/LLM-types-jackson.md` → *NumberType deserialization* for the trap this replaced
- **Kotlin types** → implement the relevant `*ValueType<SELF>` interface; no Java inheritance needed

## Gotchas

- `equals()` uses `this.getClass().isAssignableFrom(o.getClass())` — not symmetric between parent/child; two different subclasses with same value are NOT equal
- `SingleValueType.fromObject()` reflection order: constructor → static `of(argType)` → static `from(argType)`; missing all three throws `ReflectionException` at runtime
- `Money` default no-arg constructor exists solely for Hibernate/framework reflection — never call it directly; fields will be null
- `CharSequenceType.substring()` uses `SingleValueType.from()` reflection factory — works only if subclass has standard constructor or `of()` method
- `LongType.increment()`/`decrement()` also use reflection factory — same requirement
- `LongRange.toInclusive` is `Long` (nullable); `LongRange.fromInclusive` is primitive `long` — asymmetry intentional for open-ended ranges
- `TimeWindow.toExclusive` nullable → open range `[from; ∞)` — check `isAlwaysActive()` / `hasExpired()` before assuming closed
- Kotlin interfaces are mirrors, not wrappers around Java classes; Kotlin and Java hierarchies are independent
- All types are immutable; value stored at construction, null rejected via `FailFast.requireNonNull` → NPE-safe by design
