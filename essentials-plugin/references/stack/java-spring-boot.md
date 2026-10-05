# Java Spring Boot — bindings for the stack contract

What differs when the application in `stack-contract.md` is written in Java. Everything in S1–S11
still applies; only the bindings below are language-specific.

**Status, stated plainly.** Java is a first-class lane end to end. Java **slice** templates ship
(`references/slice/templates/java/` — all three §R5 write styles, including the aggregate lane that
Kotlin does not get), `/essentials:add-slice` picks them from the bounded context's lane, and
**`/essentials:init` generates a Java project**. This
document is the specification that command applies, and the one a hand-assembled Java project must
satisfy. Where a claim rests on the Java slice templates or the API-provenance ledger rather than a
compiling project, it says so.

## Compiler configuration (MUST)

No `kotlin-maven-plugin`, no all-open plugin — Java classes are not `final` by default and Spring
proxies them without help. What replaces it:

**`maven-compiler-plugin` with `-parameters`, and it is mandatory, not hygiene.** S3.5 makes
constructor parameter names part of the JSON contract under Jackson 3: the parameter names are
read from the bytecode, and without `-parameters` they are erased to `arg0`, `arg1`. Every
properties-based creator then fails to bind. This is the single highest-consequence build setting
in a Java Essentials project.

Language level is **the S1 Java baseline or newer** — the `java.version` pin in `stack-pins.md`.

```xml
<!-- maven-compiler-plugin — MANDATORY, not hygiene -->
<configuration><compilerArgs><arg>-parameters</arg></compilerArgs></configuration>
```

> Proof: `references/llm/LLM-foundation.md` § JSONSerializer (constructor parameter names under
> Jackson 3); the compiler flag is inlined above.

## Records for events, commands and DTOs (SHOULD)

Records are the idiom for event, command and DTO types. They also happen to be the *safe* shape
for S3.5: a record's component names are its constructor parameter names, so the JSON contract and
the constructor agree by construction.

The trap S3.5 describes bites hardest on **hand-written constructors in an event hierarchy** — the
classic `Event<ID>` subclass that takes `orderId` and calls `super(...)` routing it to
`aggregateId`, which then persists as `aggregateId` while the constructor advertises `orderId`.
Prefer records; where a hierarchy forces a constructor, name parameters after the properties they
land in, or annotate `@JsonProperty("…")` (in `com.fasterxml.jackson.annotation`, shared by both
Jackson majors).

## Serialization (S3) — what is different from Kotlin

The Java side is the case `EssentialTypesJacksonModule` was built for: it covers `CharSequenceType`,
`NumberType`, `Money` and `JSR310SingleValueType` — the whole Java `SingleValueType` hierarchy. No
Kotlin module is involved, and S3.4 does not apply.

S3.1–S3.3 and S3.5 apply unchanged. With a starter on the classpath the module already reaches the
**web** mapper and the persistence mapper needs nothing extra; S3.3's two silent ways to lose the web
registration still apply.

Two Java-specific serialization rules:

- **`JSR310SingleValueType` subclasses need `@JsonCreator`** on the constructor, or deserialization
  fails.
- **Map keys typed as an Essentials value type need no annotation** — `types-jackson3` registers
  `SingleValueTypeKeyDeserializers`. A `@JsonDeserialize(keyUsing = …)` carried over from older
  code imports Jackson 2's `com.fasterxml.jackson.databind.annotation`, which Jackson 3 does not
  read, so it *silently stops applying*: delete it for value-type keys, and for other key types
  switch the import to `tools.jackson.databind.annotation`.

`Money` serializes as a JSON **object** (`{"amount":…,"currency":…}`), not a scalar — do not model
it as a single value on the wire.

> Proof: `references/llm/LLM-types-jackson.md` § JSR310SingleValueType, § Map Keys and § Gotchas.

## The typed edge (S4) — Java always needs the module

This is the inverse of Kotlin's story, and the difference matters when porting.

A Java id extends `CharSequenceType` (or another `SingleValueType`). It is a real class in every
JVM signature — there is no unboxing — so Spring cannot bind it natively. `SingleValueTypeConverter`
is **required**, which means `types-spring-web` and the `@Import` of exactly one
`Essentials*WebConfigurer` (S4) are required the moment any endpoint takes a typed path variable or
request parameter. Strictly, Spring's own conversion does bind an id that has a public `String` constructor
or a static `valueOf`/`of`/`from(String)`, and the converter is required only for one without. S4 makes the
import the rule for both.

Where a Kotlin project can defer this dependency, a Java project cannot. A missing converter is
HTTP **500** with `ConversionNotSupportedException`, not 400 (S4).

`ZonedDateTimeType` must be URL-encoded in path variables; the converter decodes.

> Proof: `references/llm/LLM-types-spring-web.md`; `references/slice/api-provenance.md`.

## Dependencies — a Java project still needs Kotlin on the classpath (S2.1)

**S2.1's Kotlin row is not a Kotlin-lane requirement, and this is the Java binding that surprises
people most.** `postgresql-document-db` is a Kotlin module, so on either Postgres profile:

- **`kotlin-stdlib-jdk8` is a runtime requirement** of a project containing zero Kotlin sources.
  Without it the context dies with `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`.
- **`kotlin-reflect` is a compile requirement** of Java code touching the module: the repository
  factories and the `Condition` DSL expose `KClass` / `KProperty1` overloads that javac must
  resolve in order to select the `Class`-based one. Without it: `cannot access kotlin.reflect.KClass`.

So a Java Essentials project has a **load-bearing `kotlin.version` pin and no Kotlin compiler**.
`stack-pins.md` frames that pin as a compiler constraint — on this lane read it as a runtime one.
Neither dependency is unused; do not remove them, and do not remove the pin because there are no
`.kt` files.

The rest of S2.1 applies unchanged and is language-neutral: the JDBC starter, the driver, and the
two JDBI artifacts are missing from a plain Boot skeleton on both Postgres profiles, and all but
`jdbi3-core` (which the generated `DocumentDbConfig` imports) compile without them and fail at context
startup.

> Proof: `references/llm/LLM-postgresql-document-db.md` (Key deps, and the "pure-Java module must
> declare `kotlin-stdlib-jdk8` and `kotlin-reflect` itself" note).

## Persistence — the Java interop surface (S5)

`postgresql-document-db` is Kotlin-first and supported from Java through a specific surface. Using
the Kotlin-shaped API from Java fails at runtime, not compile time:

| Concern | Java form |
|---|---|
| Entity | extend **`JavaVersionedEntity<ID, SELF>`** — an abstract bridge implementing `VersionedEntity.version` in terms of two primitive `long` accessors — rather than implementing `VersionedEntity` directly |
| Repository creation | the `Class<T>` factory overloads |
| Version arguments | the `long` overloads |
| Queries and indexes | the string-path helpers, not Kotlin property references |
| **Id type** | a Java `CharSequenceType` id needs **`createForCompositeId(entityClass, idSerializer)`**, *not* `create()` — `create()` only works with a Kotlin `StringValueType` id, and misusing it **fails at runtime** |

`findById()` returns `null` when absent; `getById()` throws. Projectors use `findById()`, because
events may arrive before the row exists.

JDBI is the documented alternative for read models and ships no slice template.

> Proof: `references/llm/LLM-postgresql-document-db.md` (Java Interop);
> `references/slice/templates/java/` (view slices use this surface).

## Event sourcing in Java (SHOULD)

**Java has a first-class decider family of its own.** `eventsourced-aggregates` ships
`EventStreamDecider` and `EventStreamEvolver` under `src/main` — real API, not test scaffolding —
together with `EventStreamDeciderCommandHandlerAdapter` and
`EventStreamDeciderAndAggregateTypeConfigurator`, which mirror the Kotlin module's
`DeciderCommandHandlerAdapter` / `DeciderAndAggregateTypeConfigurator` one for one. This plugin's
Java command-slice template already implements `EventStreamDecider`, and every symbol is in
`references/slice/api-provenance.md`.

The two families are **parallel, not substitutes**, and mixing them does not compile:

| Concern | Kotlin family | Java family |
|---|---|---|
| Module | `kotlin-eventsourcing` | `eventsourced-aggregates` |
| Package | `components.kotlin.eventsourcing` | `components.eventsourced.aggregates.eventstream` |
| Decider | `Decider` | `EventStreamDecider` |
| Evolver | `Evolver` | `EventStreamEvolver` |
| Type config | `AggregateTypeConfiguration` | `EventStreamAggregateTypeConfiguration` |
| Wiring adapter | `DeciderAndAggregateTypeConfigurator` | `EventStreamDeciderAndAggregateTypeConfigurator` |
| Test DSL | `GivenWhenThenScenario` (Kotlin) | `GivenWhenThenScenario` (`…eventstream.test`) |

A Java project therefore does **not** need `kotlin-eventsourcing` on its classpath, and should not
have it.

**Write styles.** `rules/slice-design.md` §R5 sanctions three, all language-neutral. Java template
coverage is **all three**:

| §R5 style | Java template | Notes |
|---|---|---|
| Per-slice deciders | ✅ `templates/java/command/` | `EventStreamDecider` + `EventStreamEvolver` |
| State-stored entity | ✅ `templates/java/command_service_entity/` | No event store |
| **One aggregate per BC** | ✅ `templates/java/command_aggregate/` + `bc-scaffold-aggregate/` | `AggregateRoot` + `StatefulAggregateRepository`. **Java only** — the family is Java-native, and `slice-check` treats `aggregates/` in a Kotlin BC as Advisory interop |

All three styles are scaffoldable in Java — the aggregate lane is Java-only for the reason above.
Its shape follows upstream's `examples/essentials-spring-examples/postgresql-cqrs`:
`banking/aggregates/{Account,Accounts}.java` with per-slice `use_cases/<slice>/<Slice>Handler.java`
loading through the repository rather than a decider.

**One file is never generated on this lane: the invariant method on the aggregate.** The handler
template calls it and names its signature, but adding a method to `aggregates/<Aggregate>.java` edits
a file other slices also own, so `/essentials:add-slice` reports it rather than writing it — the same
posture as the service-entity lane's entity.

S5's aggregate-declaration rule matters here too: `@AggregateSnapshotPolicy` /
`@AggregateClosingBooksPolicy` on an aggregate class reach no registry, because
`BeanPostProcessor`s see only Spring beans. Declare them with an `EssentialsAggregateDeclarations`
bean — it lives in `eventsourced-aggregates` and is Spring-free, so the same path works outside
Spring.

> Proof: `references/llm/LLM-eventsourced-aggregates.md` (§ Declaring Aggregates for the
> declaration rule); `references/slice/api-provenance.md` (the two families, itemised);
> `references/slice/templates/java/command/__Slice__Decider.java` (implements `EventStreamDecider`).

## Testing (S10)

JUnit 5, **jqwik** for property-based tests (no `jqwik-kotlin`), AssertJ, Awaitility. The
Surefire/Failsafe split and the Testcontainers 2.x artifact names **and packages** are S10 and
unchanged — including the two import moves S10 records (`@AutoConfigureWebTestClient`'s Boot 4
package, and `org.testcontainers.postgresql.PostgreSQLContainer`), which are language-neutral.

**AssertJ with `CharSequenceType`:** cast to `CharSequence` for `isEqualTo` / `isNotEqualTo`, or
compare `.value()`. Without the cast AssertJ selects a generic object assertion instead of the
string-aware one and can produce surprising results:

```java
assertThat((CharSequence) CustomerId.of("Test")).isEqualTo(CustomerId.of("Test"));
```

This is a Java-hierarchy concern, so it applies to Kotlin projects only where they use Java-style
ids.

> Proof: `references/llm/LLM-types.md`; `references/llm/LLM-foundation-test.md`.

## Known gaps in this document

- **What is built, and what is not.** The Essentials repository's CI renders nine
  `/essentials:init` answer cells (both languages, all three profiles, both web stacks, every
  frontend mode — four of them Java) and builds each against the framework at HEAD with
  `mvn verify`, so every binding above that a generated project carries compiles and starts its
  context against a real database; the slice templates are compiled against backend-only projects
  the same renderer produces. What stays documentary is what no generated project exercises — the
  AssertJ cast, the Kotlin-shaped document-db overloads beyond `DocumentDbRepositoryFactory` — which
  rests on `references/llm/` and `references/slice/api-provenance.md`. On the user's machine,
  `/essentials:init` Step 13.7 builds the rendered project once more.
- **Frontend integration is language-neutral** — `frontend-react.md` applies unchanged, since the
  contract-first pipeline runs off the generated OpenAPI document and does not care what produced
  it.
