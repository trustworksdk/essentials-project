## types-spring-web

Spring WebMvc/WebFlux `GenericConverter`s enabling semantic types as `@PathVariable`/`@RequestParam`, plus the two `@Configuration` classes that register them. Maven: `types-spring-web`.

## Package Structure

- `dk.trustworks.essentials.types.spring.web` (main, Java) — `SingleValueTypeConverter`, `EssentialsWebMvcConfigurer`, `EssentialsWebFluxConfigurer`, `KotlinValueTypeConverterRegistrar`
- `...spring.web` (main, **Kotlin** — `src/main/kotlin`) — `KotlinValueTypeConverter`
- `...spring.web` (test) — Spring Boot test apps (`WebMvcSpringWebApplication`, `WebMvcJackson3SpringWebApplication`); all web tests run on Jackson 3
- `...spring.web.controllers` (test) — `WebMvcController`, `WebFluxController` covering all supported type combos
- `...spring.web.model` (test) — Java value types used across tests (`OrderId`, `CustomerId`, `DueDate`, etc.)
- `...spring.web.kotlin` (test, `src/test/kotlin` only) — Kotlin semantic types, controller, binding tests, `KotlinJacksonBodyJackson3Test`

## Key Classes

| Class | Role |
|---|---|
| `SingleValueTypeConverter` | `GenericConverter` for the **Java** `SingleValueType` hierarchy |
| `KotlinValueTypeConverter` (Kotlin) | `GenericConverter` for **Kotlin** semantic types; builds via `primaryConstructor.call()` |
| `KotlinValueTypeConverterRegistrar` | package-private; `ClassUtils.isPresent` guard so Java-only consumers don't hit `NoClassDefFoundError` |
| `EssentialsWebMvcConfigurer` | shipped `WebMvcConfigurer` — `addFormatters` only |
| `EssentialsWebFluxConfigurer` | shipped `WebFluxConfigurer` — `addFormatters` only |
| `WebMvcSpringWebApplication` (test) | Boot entry point; registers `EssentialTypesJacksonModule` as a bean so Boot adds it to the web `JsonMapper` |

## Test Structure

No Docker/infrastructure needed — pure Spring Boot in-memory tests.

- `WebMvcControllerTest` — `@SpringBootTest` + `MockMvc`; covers `@PathVariable` and `@RequestParam` for all `JSR310SingleValueType` subtypes plus `CharSequenceType`/`NumberType`
- `WebFluxControllerIT` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `WebTestClient`; mirrors WebMvc tests over reactive stack
- `ValidatingValueClassPathVariableTest` / `…WebFluxTest` — pin that a value class's `init` guard runs on binding and that inline vs non-inline answer 500 vs 400.
- Test model classes (`OrderId`, `DueDate`, `TransactionTime`, etc.) are standalone implementations — not shared with other modules

## Extension Points

No SPI. `SingleValueTypeConverter` is `final`. Extension = registering extra converters in your own configurer alongside `EssentialsWebMvcConfigurer`/`EssentialsWebFluxConfigurer`.

Neither configurer auto-configures — no `AutoConfiguration.imports`. Consumers `@Import`.

`SingleValueType.fromObject(value, targetType)` (from `types` module) drives all instantiation — target type must have constructor accepting wrapped value.

## Gotchas

- **Never override `configureHttpMessageCodecs` in a shipped configurer.** That replaces the app's JSON codecs; the obvious copy-paste version swaps out Boot's configured Jackson 3 codecs, silently. `EssentialsWebFluxConfigurerJackson3Test` asserts the codecs stay untouched — keep it.
- **Kotlin coverage is narrower than it looks.** `@JvmInline value class` params are *unboxed* in the JVM signature (`byOrderId-GEJpfBY(String)`), even nullable — Spring never sees the value class and `KotlinValueTypeConverter` is unreachable. Non-inline over `String` is handled by Spring's own `ObjectToObjectConverter`. Only non-inline over non-`String` needs this module. Asserted in `KotlinValueTypeConverterRequiredTest`; do not widen the docs past it.
- **Missing converter = HTTP 500, not 400** — `ConversionNotSupportedException` is classed as server misconfiguration.
- **An `init { require(...) }` guard on a value class *does* run — but answers 500, not 400.** Unboxing makes it look like the guard is skipped; it isn't. Spring re-boxes the bound `String` via `InvocableHandlerMethod$KotlinDelegate.box` (kotlin-reflect), calling `constructor-impl`. Because that happens during handler *invocation*, not argument resolution, the `IllegalArgumentException` is not a binding failure → **500**. A non-inline type validates inside `KotlinValueTypeConverter` instead → `MethodArgumentTypeMismatchException` → **400**. Same split on WebMvc, WebFlux and `suspend` handlers. A validating value class needs `@ExceptionHandler(IllegalArgumentException::class)` to answer 400. Asserted in `ValidatingValueClassPathVariableTest` / `ValidatingValueClassPathVariableWebFluxTest`.
- **`kotlin-reflect`/`kotlin-stdlib` are `<optional>`** (as in `types`, `types-jdbi`) — not transitive. Hence the registrar guard.
- **Java in `src/main/java` references Kotlin in `src/main/kotlin`** (`KotlinValueTypeConverterRegistrar` → `KotlinValueTypeConverter`). Fine for Maven: kotlin-maven-plugin `compile` binds to `process-sources`, before `maven-compiler-plugin`. **Not** fine for an IDE that compiles Java into the same `target/classes` without knowing the Kotlin output — it writes a stub that throws `Error: Unresolved compilation problem: KotlinValueTypeConverter cannot be resolved`, and `mvn install` will happily ship it. Symptom is a consumer's context failing in `EssentialsWebMvcConfigurer.addFormatters`. Cure: `mvn clean install -pl types-spring-web`.
- Converter scope: `@PathVariable` + `@RequestParam` only. `@RequestBody`/`@ResponseBody` → handled by `types-jackson3` (`EssentialTypesJacksonModule`) on the **web** mapper, not this module. Kotlin bodies need `jackson-module-kotlin` on top — `EssentialTypesJacksonModule` covers the Java hierarchy only, and without the Kotlin module a value class serialises as `{"value":"…"}` instead of the bare scalar.
- `ZonedDateTimeType` path variables must be URL-encoded by client. Converter auto-decodes via `URLDecoder.decode(source, UTF_8)`. Other temporal types do NOT need encoding.
- `NumberType` conversion uses `NumberType.resolveNumberClass()` to pick the concrete number class (`Long`, `BigDecimal`, etc.) before parsing — target type hierarchy determines the number class, not the string content.
- `spring-web` and `spring-webmvc`/`spring-webflux` are `provided` scope — caller's app must supply them. Only `spring-core` is `provided` at compile time.
- `types-jackson3` is a compile dependency (not test-only) — `EssentialTypesJacksonModule` must be registered separately as a bean for JSON body handling.
- Both WebMvc and WebFlux share the same converter class — no separate implementations per stack.
