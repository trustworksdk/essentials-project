# Types-Spring-Web - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](../types-spring-web/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types.spring.web`
- Purpose: Spring WebMvc/WebFlux converters enabling semantic types as `@PathVariable`/`@RequestParam`
- Dependencies: `spring-web`, `spring-webmvc` / `spring-webflux` (all provided); `kotlin-reflect` (optional)
- Key classes: `SingleValueTypeConverter`, `KotlinValueTypeConverter`, `EssentialsWebMvcConfigurer`, `EssentialsWebFluxConfigurer`

⚠️ **Read before answering questions about this module:**
- The configurers are **shipped production classes**, but there is **no auto-configuration**. Consumers must `@Import` one.
- `WebMvcConfig` / `WebFluxConfig` are **test-scope** classes. They are not on a consumer's classpath, are not API, and are not templates. `WebFluxConfig` in particular overrides `configureHttpMessageCodecs` with Jackson 2 codecs — correct for this module's own `-Pjackson2` runs, wrong for anyone else on Boot 4.
- Neither shipped configurer touches HTTP message converters or codecs, so adding this module **cannot** change which Jackson major serialises bodies.
- This module covers `@PathVariable`/`@RequestParam` only. Bodies are `types-jackson3` (Jackson 3 / Boot 4) or `types-jackson` (Jackson 2), registered on the **web** mapper.

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

⚠️ **Do not override `configureHttpMessageCodecs` to register the Essentials Jackson module.** That
*replaces* the application's JSON codecs. The commonly copied version of that override installs
Jackson **2** codecs, which on Spring Boot 4 silently downgrades the whole application's body
serialisation from Jackson 3. Register the Jackson module on the `ObjectMapper`/`JsonMapper` bean and
let Boot build the codecs from it.

**JSON request/response bodies:** a separate mechanism. Requires `EssentialTypesJacksonModule` on the
**web** mapper, from the artifact matching your application's Jackson major:
`types-jackson3` for Spring Boot 4 / Jackson 3, `types-jackson` for Jackson 2. Both publish the class
under the same FQCN `dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule`, extending
different Jackson majors — only one may be on the classpath. No Essentials starter registers it on the
web mapper; the starters configure the persistence mapper.

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
Required for JSON request/response bodies when using `types-jackson`.

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

⚠️ **Nothing is registered until a configurer is imported** - there is no `AutoConfiguration.imports` in this module. Declaring the dependency alone does nothing, which is a common source of "why is my typed `@PathVariable` a 500".

⚠️ **`WebMvcConfig` / `WebFluxConfig` are test-scope** - not shipped, not on a consumer's classpath, not templates. Use `EssentialsWebMvcConfigurer` / `EssentialsWebFluxConfigurer`. Never recommend copying `WebFluxConfig`: its Jackson 2 codec override is correct only for this module's own `-Pjackson2` runs.

⚠️ **Scope limitation** - Converters handle ONLY `@PathVariable` and `@RequestParam`, NOT `@RequestBody`/`@ResponseBody` (use `types-jackson3`/`types-jackson` on the web mapper)

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

---

## See Also

- [README.md](../types-spring-web/README.md) - Complete documentation with examples
- [LLM-types.md](LLM-types.md) - Core `SingleValueType` reference
- [LLM-types-jackson.md](LLM-types-jackson.md) - JSON body serialization
- Test references: `dk.trustworks.essentials.types.spring.web.WebMvcControllerTest`
