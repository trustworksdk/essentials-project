# BigDecimal Attribute Converter Improvements

Scoped to `types-springdata-jpa`'s `BaseBigDecimalTypeAttributeConverter` and the two converters that
extend it. Found while building `examples/essentials-webshop-demo`: an integration test asserting an order
total of `3999.00` failed with `but was: 3999.0`, and the cause turned out to be that every `Amount` in a
JPA read model is stored as a floating-point double.

Three items. C1 is a correctness defect for monetary values, C2 is documentation that contradicts the
code, C3 is the migration question C1 raises. The demo has worked around C1 locally
(`config/MoneyAttributeConverter.kt`); the framework has not.

---

## The current behaviour

`types-springdata-jpa/.../converters/BaseBigDecimalTypeAttributeConverter.java`:

```java
public abstract class BaseBigDecimalTypeAttributeConverter<T extends BigDecimalType<T>>
        implements AttributeConverter<T, Double> {

    @Override
    public Double convertToDatabaseColumn(T attribute) {
        return attribute != null ? attribute.doubleValue() : null;
    }

    @Override
    public T convertToEntityAttribute(Double dbData) {
        if (dbData == null) return null;
        return SingleValueType.from(BigDecimal.valueOf(dbData), getConcreteBigDecimalType());
    }
}
```

Two converters extend it, and both are `autoApply = true`:

| Converter | Type | Typical use |
|---|---|---|
| `AmountAttributeConverter` | `Amount` | money |
| `PercentageAttributeConverter` | `Percentage` | rates, discounts, interest |

So a `BigDecimal`-backed semantic type is mapped to a `double precision` column, and read back through
`BigDecimal.valueOf(double)`.

---

## C1 — Monetary values are stored as binary floating point

### Motivation

Two distinct consequences follow, and only the first is cosmetic:

1. **Scale is lost.** `Amount.of("1999.50")` returns as `1999.5`. Numerically equal, but not an equal
   `BigDecimal` — `BigDecimal.equals` compares scale — so any assertion or cache comparison against the
   value that was written fails. This is what surfaced in the demo.
2. **Arithmetic becomes floating point.** The column *is* a double, so `select sum(price) from …`,
   `avg`, and every comparison in SQL are IEEE-754 operations. Sums of many rows drift, and a figure
   beyond roughly 15–17 significant digits cannot be represented at all. A `Percentage` compounded in SQL
   drifts the same way.

For a library whose stated purpose is strongly-typed domain modelling, mapping money to a double is a
surprising default, and it is silent: nothing warns, and small test data never shows it.

### The shape

`AttributeConverter<T, BigDecimal>` instead of `AttributeConverter<T, Double>`:

```java
public abstract class BaseBigDecimalTypeAttributeConverter<T extends BigDecimalType<T>>
        implements AttributeConverter<T, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(T attribute) {
        return attribute != null ? attribute.value() : null;
    }

    @Override
    public T convertToEntityAttribute(BigDecimal dbData) {
        return dbData != null ? SingleValueType.from(dbData, getConcreteBigDecimalType()) : null;
    }
}
```

Hibernate then maps the attribute to `numeric`, and `@Column(precision = …, scale = …)` on the field
controls the shape as it does for a plain `BigDecimal`. The demo's local converter is exactly this, with
`@Column(precision = 19, scale = 2)` on each money field.

### Why this is a breaking change, and what that means for scheduling

It is not source-breaking for a consumer — the subclasses do not mention `Double`, and an entity field
keeps its type — but it **changes the generated column type** from `double precision` to `numeric`. An
existing deployment with data therefore needs a migration, and one that validates its schema
(`hibernate.ddl-auto=validate`) fails on startup until it runs one.

Per the stable-API rule in the root `CLAUDE.md`, that puts the change in a **new major**. Two ways to
offer it before then, both additive:

- **A parallel base class**, `BaseBigDecimalTypeNumericAttributeConverter`, plus numeric variants of the
  two concrete converters (`AmountNumericAttributeConverter`, `PercentageNumericAttributeConverter`),
  `autoApply = false` so nothing changes for anyone who does not opt in. New applications name the
  numeric one; existing ones keep working untouched. At the next major, the numeric behaviour becomes the
  default and the double-backed classes are deprecated.
- **Documentation only** for now (C2), with the numeric converter left as a recipe each application
  copies — which is what the webshop demo does today.

Recommendation: **the parallel base class.** Six small classes, no behaviour change for existing
deployments, and it stops every new application from silently storing money as a double.

### Tests

- A round-trip test per converter asserting `equals`, not `compareTo`: `1999.50` in, `1999.50` out,
  scale included.
- One test that pins the *column type* through Hibernate's metadata, so a future change of base class
  cannot silently revert the mapping.
- A value beyond double precision — say `12345678901234567.89` — round-tripping exactly. Under the
  current converter that test fails, which is the point.

### Effort

Small: one base class, two concrete converters, and the tests above.

---

## C2 — The javadoc describes a mapping the code does not implement

`BaseBigDecimalTypeAttributeConverter`'s class comment reads:

> Base implementation for all JPA `AttributeConverter`'s that can convert between a concrete
> `BigDecimalType` sub-class and a database **`Long`** value.

The class implements `AttributeConverter<T, Double>`. A reader who trusts the javadoc expects minor units
in a `bigint` column — a reasonable and lossless design, and not what happens. Whatever is decided about
C1, this sentence should say what the code does, and say plainly which SQL type results.

The same paragraph is worth a sentence on the consequence, because the class is the natural place to look:
that `BigDecimal.equals` is scale-sensitive, so a value read back is not `equals` to the value written.

`LLM/LLM-types-springdata-jpa.md` documents the converter set and should carry the same warning; it
currently lists the converters without mentioning their SQL types at all.

### Effort

Trivial, and worth doing regardless of C1's outcome.

---

## C3 — What existing data does on the way over

If C1 lands as a parallel base class, nothing happens to existing data until an application opts in, and
at that point it needs:

```sql
alter table <table> alter column <col> type numeric(19,2) using <col>::numeric(19,2);
```

That cast is exact for values that fit; a value already corrupted by float accumulation stays corrupted,
because the information is gone. Worth stating in the migration notes rather than implying the change
repairs history.

`MIGRATION-NEXT_MAJOR.md` is the place for the major-version half: the default flipping to `numeric`, and
the deprecation of the double-backed classes.

---

## Sequencing

| Step | Content | Gate |
|---|---|---|
| 1 | C2 — fix the javadoc and the LLM doc | none; do it now |
| 2 | C1 — parallel numeric base class and the two numeric converters, `autoApply = false` | decision on parallel-class vs documentation-only |
| 3 | C3 — migration note in `MIGRATION-NEXT_MAJOR.md` | follows step 2 |
| 4 | Next major: numeric becomes the default, double-backed classes deprecated `forRemoval` | a major |

## Open questions

1. **Parallel class, or wait for the major?** Recommendation: parallel class. The cost is six files; the
   alternative is that every application written between now and the next major stores money as a double.
2. **Should `Percentage` move too?** It has the same defect but a weaker case — a percentage is usually
   displayed, not summed. Recommendation: move both, because the surprise is the same.
3. **Default precision and scale.** A framework converter cannot know the domain's scale, so it should
   stay silent and let `@Column` decide — but the javadoc should show `@Column(precision = 19, scale = 2)`
   for money so nobody ends up with Hibernate's default.
4. **Is there a case for minor units instead** (`AttributeConverter<Amount, Long>`, as the javadoc
   currently claims)? Exact and index-friendly, but it needs a currency-aware scale the type does not
   carry, and it changes what a DBA sees in the column. Recommendation: no — `numeric` is the honest
   mapping for a `BigDecimal` type.
