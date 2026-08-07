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
- **Semantic types** — new domain type extends a base (`CharSequenceType`, `NumberType`/`LongType`/`BigDecimalType`, JSR-310 base). Provide `(value)` constructor + static `of(...)` (+ `ofNullable` where useful). `implements Identifier` for IDs.
- **Immutability** — value objects immutable; reject null in constructor.

## Kotlin

- Implement the `*ValueType<SELF>` interfaces (`StringValueType`, `LongValueType`, `BigDecimalValueType`, …) — thin interfaces, independent of the Java hierarchy. Don't subclass Java base types from Kotlin.
