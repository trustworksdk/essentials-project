# Types-Spring-Web - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/types-spring-web/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types.spring.web`
- Purpose: Spring WebMvc/WebFlux converters enabling semantic types as `@PathVariable`/`@RequestParam`, plus a springdoc `ModelConverter` so the generated OpenAPI document describes them as the JSON they are
- Dependencies: `spring-web`, `spring-webmvc` / `spring-webflux`, `springdoc-openapi-starter-common` (all provided); `kotlin-reflect` (optional)
- Key classes: `SingleValueTypeConverter`, `KotlinValueTypeConverter`, `EssentialsWebMvcConfigurer`, `EssentialsWebFluxConfigurer`, `SingleValueTypeModelConverter`

⚠️ **Read before answering questions about this module:**
- The configurers are **shipped production classes**, but there is **no auto-configuration**. Consumers must `@Import` one.
- Neither shipped configurer touches HTTP message converters or codecs, so adding this module **cannot** change which Jackson major serialises bodies.
- This module covers `@PathVariable`/`@RequestParam` only. Bodies are `types-jackson3` (Jackson 3 / Boot 4 — the only Jackson major Essentials supports from 0.60), registered on the **web** mapper.

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-spring-web</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `SingleValueType`, `CharSequenceType`, `NumberType`, all temporal types from [types](./LLM-types.md)

## TOC
- [Core API](#core-api)
- [Configuration](#configuration)
- [OpenAPI with springdoc](#openapi-with-springdoc)
- [Usage Patterns](#usage-patterns)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Conversion Logic](#conversion-logic)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Core API

Base package: `dk.trustworks.essentials.types.spring.web`

### SingleValueTypeConverter

```java
package dk.trustworks.essentials.types.spring.web;

public final class SingleValueTypeConverter implements GenericConverter {
    @Override
    public Set<ConvertiblePair> getConvertibleTypes();

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType);
}
```

**Convertible pairs:**
- `String` → `CharSequenceType`
- `Number` → `NumberType`
- `String` → `NumberType`
- `String` → `JSR310SingleValueType`

---

## Configuration

### WebMvc

```java
import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer;

@SpringBootApplication
@Import(EssentialsWebMvcConfigurer.class)
public class Application { }
```

### WebFlux

```java
import dk.trustworks.essentials.types.spring.web.EssentialsWebFluxConfigurer;

@SpringBootApplication
@Import(EssentialsWebFluxConfigurer.class)
public class Application { }
```

Both register `SingleValueTypeConverter` — and `KotlinValueTypeConverter` when `kotlin-reflect` is
present — via `addFormatters`, and nothing else.

**You never register `KotlinValueTypeConverter` yourself.** Both configurers register it behind a
`ClassUtils.isPresent` guard, because `kotlin-reflect` is an `<optional>` dependency and a Java-only
consumer must not be forced to carry it.

⚠️ **Do not override `configureHttpMessageCodecs` to register the Essentials Jackson module.** That
*replaces* the application's JSON codecs. The commonly copied version of that override installs
Jackson **2** codecs, which on Spring Boot 4 silently downgrades the whole application's body
serialisation from Jackson 3. Register the Jackson module on the `ObjectMapper`/`JsonMapper` bean and
let Boot build the codecs from it.

**JSON request/response bodies:** a separate mechanism. Requires `EssentialTypesJacksonModule` on the
**web** mapper, from `types-jackson3` (`dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule`,
Jackson 3). Expose it as a `@Bean` and Spring Boot registers it on its auto-configured web `JsonMapper`.
The Essentials Postgres/Mongo starters already define that bean (`@ConditionalOnMissingBean`); without a
starter, declare it yourself:

```java
@Bean
public EssentialTypesJacksonModule essentialTypesJacksonModule() {
    return new EssentialTypesJacksonModule();
}
```

The Jackson 2 artifact `types-jackson` was removed in 0.60; upgrading apps swap the artifact id (same FQCN).

### Kotlin semantic types

`SingleValueTypeConverter` covers the **Java** `SingleValueType` hierarchy only.
`dk.trustworks.essentials.kotlin.types` is an unrelated hierarchy, handled by `KotlinValueTypeConverter`
— which is needed far less often than it looks:

| Kotlin type | Binds as `@PathVariable`? | Why |
|---|---|---|
| `@JvmInline value class` over anything | yes, with **nothing from Essentials** | Kotlin unboxes it in the JVM signature, nullable included: `fun byOrderId(orderId: OrderId)` → `byOrderId-GEJpfBY(String)`. Spring binds the underlying type; the converter is unreachable |
| non-inline class wrapping `String` | yes, via **Spring's** `ObjectToObjectConverter` | it finds the single `String`-arg constructor |
| non-inline class wrapping anything else | only with `KotlinValueTypeConverter` | otherwise `ConversionNotSupportedException` → HTTP **500**, not 400 |

**Kotlin bodies** are covered by neither converter *nor* `EssentialTypesJacksonModule`. Register
`jackson-module-kotlin`'s `KotlinModule` on the web mapper. Skipping it fails silently rather than
loudly: a value class serialises as `{"value":"order-4711"}` instead of `"order-4711"`.

#### Validation runs — but watch the status code

Spring **re-boxes** the bound `String` into the value class before invoking the handler
(`InvocableHandlerMethod$KotlinDelegate.box` → `kotlin-reflect` → `constructor-impl`), so an
`init { require(…) }` guard **does** fire. No invalid id reaches your handler. The catch is *where*
it fires:

| Shape | Invalid value → | Why |
|---|---|---|
| `@JvmInline value class` | **500** | guard fires during handler *invocation*, so it is not a binding failure |
| `suspend fun` + value class (WebFlux) | **500** | same |
| non-inline (e.g. `data class`) | **400** | guard fires inside `KotlinValueTypeConverter` → `MethodArgumentTypeMismatchException` |

Ship `@ExceptionHandler(IllegalArgumentException::class)` returning 400 on endpoints that take a
**validating value class**.

A typed path variable that answers **500** therefore has two causes — tell them apart by the exception:
`ConversionNotSupportedException` means **no converter** (the configurer was not `@Import`ed; Spring classes a
missing converter as server misconfiguration, so a well-formed request looks like a server bug), while an
`IllegalArgumentException` from your own type's `init` means the converter worked and **your validation fired**.

> `dk.trustworks.essentials.kotlin.types.StringValueType` is a bare interface (`value` +
> `compareTo`) and validates nothing itself. Validation lives in the concrete type — e.g.
> `CountryCode` has `init { validate(value) }` — and in whatever you write.

---

## OpenAPI with springdoc

Out of the box springdoc describes every semantic type as the Java object it is, not as the JSON it is
written as. A `CharSequenceType` id becomes a component with `bytes`, `empty` and `value` properties, and
every client generated from the document (Orval, openapi-generator) types each id wrongly. Kotlin DTOs
get a second defect: a value-class property is published under its mangled JVM getter name
(`"orderId-nb-kci0"`) while the wire carries `"orderId"`. Nothing fails; the document is just wrong.

`SingleValueTypeModelConverter` fixes both. Register it as a bean in the application that runs springdoc;
springdoc adds every `io.swagger.v3.core.converter.ModelConverter` bean to its model resolution:

```java
import dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverter;

@Bean
SingleValueTypeModelConverter singleValueTypeModelConverter() {
    return new SingleValueTypeModelConverter();
}
```

```kotlin
import dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverter

@Bean
fun singleValueTypeModelConverter() = SingleValueTypeModelConverter()
```

- **Not auto-configured**, like the rest of this module: declaring the dependency registers nothing.
- **springdoc is `provided`**: the application brings its own `springdoc-openapi-starter-webmvc-*` or
  `-webflux-*`. The converter is plain swagger-core API, so WebMvc and WebFlux are the same.
- Without Spring: `ModelConverters.getInstance(openapi31).addConverter(new SingleValueTypeModelConverter())`.

What each type is published as (the same in OpenAPI 3.0 and 3.1), inline wherever it appears: property,
`List`/`Set` element, `Map` value, `@PathVariable`/`@RequestParam`, request or response body:

| Type | Schema |
|---|---|
| `CharSequenceType` (incl. `EmailAddress`, `CountryCode`, `CurrencyCode`) | `string` |
| `LongType` | `integer` / `int64` |
| `IntegerType`, `ShortType`, `ByteType` | `integer` / `int32` |
| `BigIntegerType` | `integer` |
| `BigDecimalType` (incl. `Amount`, `Percentage`), `DoubleType`, `FloatType` | `number` (`double` / `float` for the last two) |
| `InstantType`, `LocalDateTimeType`, `OffsetDateTimeType`, `ZonedDateTimeType` | `string` / `date-time` |
| `LocalDateType` | `string` / `date` |
| `LocalTimeType` | `string` / `partial-time` |
| Kotlin `@JvmInline value class` (Essentials interface or not) | the schema of the value it wraps |
| `Money` | component with `amount` (`number`) and `currency` (`string`) |
| `BooleanType`, any other `SingleValueType` | left alone: an object with `value`, because that **is** how it is written |

The rule behind the table: a type collapses exactly when `EssentialTypesJacksonModule` (or, for Kotlin,
`jackson-module-kotlin`) writes it as a bare JSON scalar, so the document and the wire agree. The tests
assert that agreement by serialising the same DTOs and comparing (`SingleValueTypeModelConverterTest`,
`SingleValueTypeModelConverterKotlinTest`, and `SingleValueTypeModelConverterSpringDocTest`, which reads
`/v3/api-docs` from a running Boot context).

Kotlin property names are restored to the Kotlin name, `required` entries included, with no need for
`kotlin-reflect` or Jackson 2's Kotlin module. A property name springdoc has already put through a
`PropertyNamingStrategy` (snake_case, say) is left mangled.

A `@PathVariable`/`@RequestParam` declared as a `@JvmInline` value class over a `String` (`orderId: KtOrderId`)
is published as `string`. Without the converter, and with `kotlin-reflect` on the classpath, springdoc 3.1
publishes such a parameter as the source type of an arbitrary Spring converter to `String` — `integer`,
`string`/`date-time`, a `$ref` to a stray `Regex` component — picked by hash order, so it differs between JDK
versions and looks stable on any one of them.

#### Kotlin handler methods: set the operationId

springdoc takes each operation's `operationId` from the JVM method name, and the converter cannot change it.
Kotlin mangles the JVM name of a function (`echo` → `echo-40lU5Lw`) when a value class appears **directly** in
its signature:

| Handler signature | JVM name / `operationId` |
|---|---|
| value-class parameter, nullable too (`@PathVariable id: OrderId`, `@RequestParam id: OrderId?`) | mangled |
| value-class return type, nullable too (`fun id(): OrderId`) | mangled |
| `suspend fun` with a value-class parameter | mangled |
| value class only inside a generic or a DTO (`List<OrderId>`, `@RequestBody cmd: PlaceOrder`, returning `OrderView`) | not mangled |
| non-inline class (`data class`), or no value class | not mangled |

The suffix hashes the parameter types, not the name. Two handlers with the same value-class parameters get the
same suffix. A client generated from the spec gets the suffix too, e.g. Orval's `useEcho40lU5Lw`.

On every such handler, name the operation explicitly with swagger's `@Operation`
(`io.swagger.v3.oas.annotations.Operation`, which comes with springdoc):

```kotlin
@GetMapping("/orders/{id}")
@Operation(operationId = "getOrder")
fun getOrder(@PathVariable id: OrderId): OrderView = …
```

`@JvmName` is no alternative here: Kotlin allows it only on final members, and the `kotlin-spring` compiler
plugin makes controller methods `open`, so it does not compile.

---

## Usage Patterns

### CharSequenceType as Path Variable

```java
@GetMapping("/orders/{orderId}")
public Order getOrder(@PathVariable OrderId orderId) {
    return orderService.findById(orderId);
}
```
- Converter parses `String` → `OrderId` (extends `CharSequenceType`)
- Works for any `CharSequenceType` subclass

### NumberType as Request Param

```java
@GetMapping("/orders/by-quantity")
public List<Order> findByQuantity(@RequestParam("min") Quantity minQuantity,
                                  @RequestParam("max") Quantity maxQuantity) {
    return orderService.findByQuantityRange(minQuantity, maxQuantity);
}
```
- Converter parses `String` → `Quantity` (extends `NumberType`)
- Auto-detects target number class (Integer, Long, BigDecimal, etc.)

### Multiple Types Combined

```java
@PostMapping("/orders/customer/{customerId}")
public Order updatePrice(@PathVariable CustomerId customerId,
                         @RequestParam("price") Amount price) {
    return orderService.updatePrice(customerId, price);
}
```

### WebFlux Reactive

```java
@GetMapping("/reactive/orders/{orderId}")
public Mono<Order> getOrder(@PathVariable OrderId orderId) {
    return orderService.findById(orderId);
}

@GetMapping("/reactive/orders/by-date/{dueDate}")
public Flux<Order> findByDueDate(@PathVariable DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}
```
- Same converter works for both WebMvc and WebFlux

---

## JSR-310 Temporal Types

Base package: `dk.trustworks.essentials.types`

### Supported Types

| Your Type Extends | Wrapped Value | String Format | Notes |
|-------------------|---------------|---------------|-------|
| `InstantType` | `Instant` | `2024-01-15T10:30:00Z` | ISO-8601 |
| `LocalDateTimeType` | `LocalDateTime` | `2024-01-15T10:30:00` | ISO-8601 |
| `LocalDateType` | `LocalDate` | `2024-01-15` | ISO-8601 |
| `LocalTimeType` | `LocalTime` | `10:30:00` | ISO-8601 |
| `OffsetDateTimeType` | `OffsetDateTime` | `2024-01-15T10:30:00+01:00` | ISO-8601 |
| `ZonedDateTimeType` | `ZonedDateTime` | URL-encoded | ⚠️ Must encode |

### Pattern: Temporal Type as Path Variable

```java
// DueDate extends LocalDateType
@GetMapping("/orders/by-due-date/{dueDate}")
public List<Order> findByDueDate(@PathVariable DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}
// URL: /orders/by-due-date/2024-01-15
```

### Pattern: ZonedDateTimeType Requires Encoding

```java
// TransactionTime extends ZonedDateTimeType
@GetMapping("/orders/by-time/{time}")
public Order getByTime(@PathVariable TransactionTime time) {
    return orderService.findByTime(time);
}
// URL: /orders/by-time/2024-01-15T10%3A30%3A00%2B01%3A00%5BEurope%2FParis%5D
```
Client must URL-encode the `ZonedDateTime` string. Converter auto-decodes.

### Pattern: JSON Body with @JsonCreator

```java
public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    @JsonCreator
    public TransactionTime(ZonedDateTime value) {
        super(value);
    }

    public static TransactionTime of(ZonedDateTime value) {
        return new TransactionTime(value);
    }
}
```
Required for JSON request/response bodies when using `types-jackson3`.

---

## Conversion Logic

| Source Type | Target Type | Implementation |
|-------------|-------------|----------------|
| `SingleValueType<?, ?>` | Any | `source.value()` |
| `String` | `LocalDateTimeType` | `SingleValueType.fromObject(LocalDateTime.parse(source), targetType)` |
| `String` | `LocalDateType` | `SingleValueType.fromObject(LocalDate.parse(source), targetType)` |
| `String` | `InstantType` | `SingleValueType.fromObject(Instant.parse(source), targetType)` |
| `String` | `LocalTimeType` | `SingleValueType.fromObject(LocalTime.parse(source), targetType)` |
| `String` | `OffsetDateTimeType` | `SingleValueType.fromObject(OffsetDateTime.parse(source), targetType)` |
| `String` | `ZonedDateTimeType` | `SingleValueType.fromObject(ZonedDateTime.parse(URLDecoder.decode(source, UTF_8)), targetType)` |
| `String` | `NumberType` | `NumberType.resolveNumberClass()` + `NumberUtils.parseNumber()` + `SingleValueType.fromObject()` |
| `Number` | `NumberType` | `SingleValueType.fromObject(source, targetType)` |
| Other | `CharSequenceType` | `SingleValueType.fromObject(source, targetType)` |

**Key method:** `dk.trustworks.essentials.types.SingleValueType.fromObject(Object value, Class<SingleValueType<?, ?>> type)`

---

## Gotchas

⚠️ **Nothing is registered until a configurer is imported** - there is no `AutoConfiguration.imports` in this module. Declaring the dependency alone does nothing: `SingleValueTypeConverter` reaches Spring only through a configurer's `addFormatters` (`EssentialsWebMvcConfigurer.java:53-54`, `EssentialsWebFluxConfigurer.java:49-50`). The configurer is **required** for a Java id with no `String` route, meaning no public `String` constructor and no static `valueOf`/`of`/`from(String)`. A `CharSequenceType` built only from a `CharSequence`, a `NumberType` with a numeric constructor, and a JSR310 type all fall in this group, and without the configurer the typed `@PathVariable` / `@RequestParam` answers 500. Spring's own `ObjectToObjectConverter` binds an id that has such a route, with no Essentials help: it tries a static `valueOf`/`of`/`from(String)`, then a `String` constructor (spring-core 7.0.9, whose own failure text reads "no static valueOf/of/from(…) method or …(…) constructor"). Import the configurer for the web stack anyway. One registration then covers every Essentials type, `SingleValueTypeConverter` is what converts to `NumberType` and the JSR310 types (`SingleValueTypeConverter.java:37-40`), and an id that later loses its `String` constructor turns into a silent 500.

⚠️ **Scope limitation** - Converters handle ONLY `@PathVariable` and `@RequestParam`, NOT `@RequestBody`/`@ResponseBody` (use `types-jackson3` on the web mapper)

⚠️ **Java hierarchy only, for `SingleValueTypeConverter`** - its four `ConvertiblePair`s are `String`→`CharSequenceType`, `Number`→`NumberType`, `String`→`NumberType`, `String`→`JSR310SingleValueType`. Kotlin types are `KotlinValueTypeConverter`'s job.

⚠️ **Region zone ids cannot be path variables** - `Europe/Paris` URL-encodes to a `%2F` that the servlet container rejects before conversion runs. Offset-only values work; otherwise use a request param.

⚠️ **ZonedDateTimeType URL encoding** - Client MUST URL-encode before sending:
```java
String encoded = URLEncoder.encode(transactionTime.toString(), StandardCharsets.UTF_8);
// Use in URL: /orders/by-time/{encoded}
```
Converter auto-decodes via `URLDecoder.decode(source, UTF_8)`

⚠️ **NumberType auto-detection** - Uses `NumberType.resolveNumberClass()` to determine target (`Integer`, `Long`, `BigDecimal`, etc.), then parses via `NumberUtils.parseNumber()`

⚠️ **Constructor requirement** - `SingleValueType.fromObject()` requires constructor accepting wrapped value type

⚠️ **Null safety** - Converter handles null source gracefully

⚠️ **springdoc mis-describes every semantic type unless `SingleValueTypeModelConverter` is a bean** - ids come out as objects (`bytes`/`empty`/`value`) and Kotlin value-class properties under mangled names, so generated clients are typed wrong with no error anywhere. See [OpenAPI with springdoc](#openapi-with-springdoc).

---

## See Also

- [README.md](https://github.com/trustworksdk/essentials-project/blob/0.60.0/types-spring-web/README.md) - Complete documentation with examples
- [LLM-types.md](LLM-types.md) - Core `SingleValueType` reference
- [LLM-types-jackson.md](LLM-types-jackson.md) - JSON body serialization
- Test references: `dk.trustworks.essentials.types.spring.web.WebMvcControllerTest`, `dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverterTest`
