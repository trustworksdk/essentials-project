---
paths:
  - "**/*.java"
  - "**/*.kt"
---

# Code Style — Java & Kotlin

Apply to all new/modified code. Match surrounding code first.

## Java + Kotlin (shared)

- **License header** — every source file starts with the Apache 2.0 header: `Copyright 2021-2026 the original author or authors.` Copy from any existing file.
- **Formatting** — use `Essentials-Formatter.xml` (IntelliJ scheme). 240-col right margin. Kotlin = `KOTLIN_OFFICIAL`.
- **Star imports** — on-demand imports ON (IDE threshold 2). `import java.util.*;` not individual classes. Don't fight the formatter.
- **Package root** — `dk.trustworks.essentials` (core) / `dk.trustworks.essentials.components` (components). Kotlin core types under `dk.trustworks.essentials.kotlin.types`.

## Java

- **Guard clauses** — validate args at method/constructor entry with `FailFast.requireNonNull(x, "msg")` / `requireTrue(...)`. Import: `import static dk.trustworks.essentials.shared.FailFast.requireNonNull`. 2000+ call sites — do not use raw `Objects.requireNonNull`.
- **Builders** — non-trivial config objects expose static `builder()` → `XxxBuilder` with `setXxx(...)` fluent setters (NOT `withXxx`) → `build()`.
- **No `Optional` parameters in constructors** — no public or protected constructor declares an `Optional` parameter. Absence is expressed as a neutral default (`MeasurementTaker.none()`), a sealed variant, or a builder-resolved nullable field.
- **Constructor parameter ceiling of 5** — above that, introduce a `XxxDependencies` bundle with a builder and/or a `XxxSettings` record, or a builder for the class itself. A `record` used *as* the parameter object is exempt from the ceiling — being wide is its job.
- **One non-deprecated public constructor per class where a builder exists.** No new telescoping overloads.
- **`Optional` is permitted** as a return type, as a builder-setter overload parameter (`setXxx(T)` alongside `setXxx(Optional<T>)`), and in Spring `@Bean` method signatures where it is unwrapped on the spot.
- **Deprecate, never delete** — an existing constructor that violates the two rules above is kept, marked `@Deprecated(forRemoval = true, since = "...")` with a `@deprecated` javadoc tag naming its replacement, and re-implemented to delegate to the new path. Removal is a separate decision at the next major. `EssentialsConstructionRules` (in `foundation-test`) enforces exactly this.
- **No `@Nullable` annotations** — the reactor carries no nullability-annotation dependency, and `shared`/`types` are deliberately zero-dependency. Document nullability in javadoc; enforce it with builders and `FailFast.requireNonNull`.
- **Semantic types** — new domain type extends a base (`CharSequenceType`, `NumberType`/`LongType`/`BigDecimalType`, JSR-310 base). Provide `(value)` constructor + static `of(...)` (+ `ofNullable` where useful). `implements Identifier` for IDs.
- **Immutability** — value objects immutable; reject null in constructor.

## Kotlin

- Implement the `*ValueType<SELF>` interfaces (`StringValueType`, `LongValueType`, `BigDecimalValueType`, …) — thin interfaces, independent of the Java hierarchy. Don't subclass Java base types from Kotlin.
