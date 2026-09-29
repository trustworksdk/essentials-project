# Kotlin Spring Boot — bindings for the stack contract

What differs when the application in `stack-contract.md` is written in Kotlin. Everything in
S1–S11 still applies; only the bindings below are language-specific.

Kotlin is the language the worked example is written in (`tests/fixtures/worked-example/`) and the
default `/essentials:init` offers — the command scaffolds both languages (see
`java-spring-boot.md` for the other lane).

**On S2.1:** the non-transitive dependency set applies here too. Kotlin projects satisfy the
`kotlin-stdlib` / `kotlin-reflect` rows for free via `kotlin-maven-plugin`, but **not** the JDBC
starter, the driver, or the two JDBI artifacts — those are missing from a plain Boot skeleton on
either Postgres profile regardless of language, and none of them fails at compile time.

## Compiler configuration (MUST)

`kotlin-maven-plugin` with the **`spring` compiler plugin** (all-open). Spring needs to subclass
`@Configuration` classes and proxy `@Component`s, and Kotlin classes are `final` by default —
without all-open, configuration classes fail to proxy.

Two compiler arguments are load-bearing:

| Argument | Why |
|---|---|
| `-Xjsr305=strict` | Treats JSR-305 nullability annotations on Java APIs as strict Kotlin types. Essentials' Java surface is annotated; without this, its nullability is advisory and `null` leaks into non-null Kotlin types |
| `-Xannotation-default-target=param-property` | Controls where an annotation on a constructor property lands. Without it, annotations you write on `val`s in a primary constructor may target only the parameter, and Jackson/validation annotations silently miss the property |

**Parameter names must survive compilation** — S3.5 makes constructor parameter names part of the
JSON contract. Kotlin retains them for its own metadata, but the `maven-compiler-plugin` should
still carry `-parameters` for the Java sources and any mixed compilation.

`kotlin-reflect` and `kotlin-stdlib` are required at runtime, not optional: Spring's Kotlin
support and the value-class re-boxing path (below) both go through `kotlin-reflect`.

**Kotlin 2.3 and `jvmTarget` at the Java baseline:** Essentials' Kotlin artifacts are compiled at
language and API level 2.3, which sets the emitted `@Metadata` binary version and caps the stdlib
API they bind to at the Kotlin that Spring Boot manages. The compiler floor is nonetheless **2.3**,
for a different reason: a Kotlin compiler older than 2.3 cannot target the S1 Java baseline (the
`java.version` pin in `stack-pins.md`), and no compiler inlines bytecode built for that target
(Essentials' `inline`/`reified` functions) into code compiled for a lower one — so the
application's own `jvmTarget` must match the `java.version` pin as well.

```xml
<!-- kotlin-maven-plugin -->
<configuration>
  <args>
    <arg>-Xjsr305=strict</arg>
    <arg>-Xannotation-default-target=param-property</arg>
  </args>
  <compilerPlugins><plugin>spring</plugin></compilerPlugins>
</configuration>
<!-- plus kotlin-maven-allopen as a plugin dependency -->
```

> Proof: the compiler block is inlined above. The Kotlin language/API level and the JVM-target floor
> are the `kotlinLanguage.version` / `kotlinApi.version` properties and their comment in the
> Essentials root `pom.xml`; parameter names: `references/llm/LLM-foundation.md` § JSONSerializer.

## Serialization — S3.4 in full (MUST, and it fails silently)

**`EssentialTypesJacksonModule` does not reference `dk.trustworks.essentials.kotlin.types` at
all.** It covers the Java `SingleValueType` hierarchy — `CharSequenceType`, `NumberType`,
`Money`, `JSR310SingleValueType` — and nothing else.

A Kotlin `@JvmInline value class` id therefore needs `jackson-module-kotlin`'s `KotlinModule`
on **both** mappers (S3.3). Without it:

```
without KotlinModule:  {"value":"order-4711"}
with KotlinModule:     "order-4711"
```

**Nothing throws either way.** The endpoint returns 200 and the wire contract is quietly wrong.

One artifact, `tools.jackson.module:jackson-module-kotlin` (Jackson 3), reaches the two mappers
by two different routes:

| Mapper | How `KotlinModule` gets there |
|---|---|
| **web** (`@RequestBody`/`@ResponseBody`) | Nothing to write. Boot 4 finds Jackson modules on the classpath for its `JsonMapper` (`spring.jackson.find-and-add-modules`, on by default), and `jackson-module-kotlin` registers itself for that lookup |
| **persistence** (events, queue payloads, documents) | Your own serializer bean (S3.2). The starters' serializer ignores module beans, so a `KotlinModule` `@Bean` reaches the web mapper only and changes nothing persisted |

The persistence side is one bean, and its **type is the load-bearing part** — the starter backs off
only from a bean of the type it would have created:

```kotlin
@Configuration
class PersistenceSerializerConfiguration {
    // pg-event-sourced: replaces the event-store starter's JSONEventSerializer
    @Bean
    fun jsonSerializer(): JSONEventSerializer =
        Jackson3JSONEventSerializer(
            EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build()))

    // pg-crud / mongo: replaces the starter's JSONSerializer instead
    // @Bean
    // fun jsonSerializer(): JSONSerializer =
    //     Jackson3JSONSerializer(
    //         EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build()))
}
```

Start from `EssentialsObjectMappers`, never a hand-built mapper — its configuration *is* the
persisted format (S3.2). The `DocumentDbRepositoryFactory` of S5 takes this same `JSONSerializer`.

> Proof: `references/llm/LLM-types-jackson.md` § Kotlin semantic types and § Spring Boot 4 (web
> mapper); `references/llm/LLM-postgresql-document-db.md` § Factory Creation (the persistence
> mapper with `KotlinModule`, and why a module bean does not reach it); the bean is inlined above.

## The typed edge — what Kotlin actually needs (S4)

Kotlin's binding story is narrower than "add the module". Three shapes, only one of which depends
on Essentials at all:

| Kotlin shape | Binds as `@PathVariable`? | Mechanism |
|---|---|---|
| `@JvmInline value class` over anything | **yes — with nothing from Essentials** | Kotlin **unboxes** it in the JVM signature: `fun byOrderId(orderId: OrderId)` compiles to `byOrderId-GEJpfBY(String)`. Spring only ever sees `String`. Confirm with `javap -p` |
| non-inline class wrapping a `String` | **yes** — Spring's own `ObjectToObjectConverter` | it finds the single `String`-arg constructor |
| non-inline class wrapping anything else | **only with `KotlinValueTypeConverter`** | no `String`-arg constructor for Spring to find |

So a value-class id needs no `types-spring-web` dependency at all. Add the module for the third
row, and for Java ids extending `CharSequenceType`.

**Validation fires, but watch the status code.** Spring re-boxes the bound `String` into the value
class before invoking the handler (`InvocableHandlerMethod$KotlinDelegate.box` → `kotlin-reflect` →
`constructor-impl`), so an `init { require(…) }` guard **does** run and no invalid id reaches the
decider. Where it fires decides the status:

| Shape | Invalid value → | Why |
|---|---|---|
| `@JvmInline value class` | **500** | guard fires during handler *invocation*, not binding |
| `suspend fun` + value class | **500** | same |
| non-inline (e.g. `data class`) | **400** | guard fires inside `KotlinValueTypeConverter` → `MethodArgumentTypeMismatchException` |

Ship `@ExceptionHandler(IllegalArgumentException::class)` returning 400 on endpoints taking a
validating value class — validation at the edge for free, you only map the exception.

`dk.trustworks.essentials.kotlin.types.StringValueType` is a bare interface (`value` + `compareTo`)
and validates nothing itself; validation lives in the concrete type.

> Proof: `references/llm/LLM-types-spring-web.md`; the `@Import` is inlined in `stack-contract.md` S4.

## Event sourcing in Kotlin — `kotlin-eventsourcing` (SHOULD)

The `kotlin-eventsourcing` module provides the Kotlin DSL — `Decider`, `Evolver`, and the
state-evolution shape the slice law's decider lane assumes.

Idioms that are not obvious and bite silently:

- **Commands are a *regular* interface; events are a *sealed* interface.** Essentials dispatches
  commands via `canHandle()`, a runtime type check, and sealed interfaces create cross-package
  inheritance problems in Kotlin. Events want sealedness for exhaustive `when`.
- **`Evolver.applyEvents(evolver, null, emptyList())` throws NPE** when the evolver returns `null`
  for every event, or when the list is empty and initial state is `null`. Guard *before* calling —
  check `events.isEmpty()` first, not the result afterwards.
- **`CommandBus.send()` returns `Object`** (it is Java). Kotlin infers `Any?`; cast explicitly:
  `val event = commandBus.send(cmd) as MyEvent?`.
- **`Version(message.order)`, never `Version.of()`.** The Kotlin value class has no `of()` factory.
- **`findById()` returns `null`; `getById()` throws.** Use `findById()` in projectors, where events
  may arrive before the row exists.
- **Import paths surprise people:** `@MessageHandler` is in `foundation.messaging`, `OrderedMessage`
  in `foundation.messaging.queue`, `RandomIdGenerator` in `foundation.types`, and `Version` in
  `document_db` — none of them in `kotlin-eventsourcing`.
- **`ViewEventProcessor` handlers:** `@MessageHandler` is **mandatory** (without it the method is
  silently never invoked). `OrderedMessage` as the second parameter is optional to the dispatcher
  but mandatory for a projection — `message.order` is the `EventOrder` compared against the row's
  stored `version`, which is what makes the projection idempotent under redelivery.

> Proof: `references/llm/LLM-kotlin-eventsourcing.md`;
> `references/llm/LLM-postgresql-document-db.md`.

## Reactive and coroutines (WebFlux)

`reactor-kotlin-extensions` and `kotlinx-coroutines-reactor` bridge coroutines and Reactor. A
`suspend` handler is fine — note only that it puts value-class validation failures in the 500 row
of the table above.

On WebMvc, none of this applies and `EssentialsWebMvcConfigurer` replaces the WebFlux one (S4).

## Testing (S10)

`kotlin-test-junit5` for assertions, `jqwik` + **`jqwik-kotlin`** for property-based tests,
`awaitility-kotlin` for async assertions, AssertJ where its fluency helps.

S10's two import moves (`@AutoConfigureWebTestClient`'s Boot 4 package and
`org.testcontainers.postgresql.PostgreSQLContainer`) are language-neutral and apply unchanged —
S10's integration-test base is written in Kotlin and already shows both.

Note the AssertJ + `CharSequenceType` gotcha applies to Java-style ids: cast to `CharSequence` for
`isEqualTo`, or compare `.value()`.

> Proof: `references/llm/LLM-types.md`; test libraries are listed in `stack-pins.md`.
