# Types-Spring-Web

> Spring WebMvc and WebFlux Converter support for Essentials `types` module

This module enables seamless use of `SingleValueType` implementations as `@PathVariable` and `@RequestParam` parameters in Spring WebMvc and WebFlux controllers.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-types-spring-web.md](../LLM/LLM-types-spring-web.md)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [WebMvc Configuration](#webmvc-configuration)
- [WebFlux Configuration](#webflux-configuration)
- [Kotlin semantic types](#kotlin-semantic-types)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Gotchas](#gotchas)

## What this module ships

| Class | Scope | Purpose |
|---|---|---|
| `SingleValueTypeConverter` | production | Converts to the **Java** `SingleValueType` hierarchy |
| `KotlinValueTypeConverter` | production | Converts to **Kotlin** semantic types — see [Kotlin semantic types](#kotlin-semantic-types) for the narrow case this covers |
| `EssentialsWebMvcConfigurer` | production | Registers both, for a servlet application |
| `EssentialsWebFluxConfigurer` | production | Registers both, for a reactive application |
| `WebMvcConfig`, `WebFluxConfig` | **test** | Jackson **2** body/codec setup for this module's own `-Pjackson2` test runs. Not API, not on your classpath, not a template to copy |

Neither configurer is auto-configuration: declaring the dependency changes nothing until you
`@Import` one. Neither touches HTTP message converters or codecs, so adding this module cannot
change which Jackson major serialises your request and response bodies.

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-spring-web</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies** (provided scope - add to your project):
```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-web</artifactId>
</dependency>
```

**For WebMvc:**
```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webmvc</artifactId>
</dependency>
```

**For WebFlux:**
```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webflux</artifactId>
</dependency>
```

## Quick Start

Base package: `dk.trustworks.essentials.types.spring.web`

**1. Import the configurer:**

```java
@SpringBootApplication
@Import(EssentialsWebMvcConfigurer.class)   // or EssentialsWebFluxConfigurer on a reactive application
public class Application { }
```

**2. Use semantic types in controllers:**

```java
@RestController
public class OrderController {

    @GetMapping("/orders/{orderId}")
    public Order getOrder(@PathVariable OrderId orderId) {
        return orderService.findById(orderId);
    }

    @PostMapping("/orders/for-customer/{customerId}")
    public Order createOrder(@PathVariable CustomerId customerId,
                             @RequestParam("price") Amount price,
                             @RequestParam("quantity") Quantity quantity) {
        return orderService.create(customerId, price, quantity);
    }
}
```

**Learn more:** See [WebMvcControllerTest.java](src/test/java/dk/trustworks/essentials/types/spring/web/WebMvcControllerTest.java)

## WebMvc Configuration

### Step 1: Register the converters

```java
@Configuration
@Import(EssentialsWebMvcConfigurer.class)
public class WebConfiguration { }
```

That is the whole path-variable/request-param setup. It registers `SingleValueTypeConverter`, plus
`KotlinValueTypeConverter` if `kotlin-reflect` is on the classpath.

### Step 2 (separate concern): Jackson, for JSON request/response bodies

`@PathVariable`/`@RequestParam` conversion and `@RequestBody`/`@ResponseBody` serialisation are two
unrelated mechanisms. The converter above does nothing for bodies; a Jackson module on the **web**
`ObjectMapper` does, and no Essentials starter registers one there for you.

Depend on the Essentials Jackson module matching **your application's Jackson major**:

```xml
<!-- Spring Boot 4 / Jackson 3 -->
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson3</artifactId>
    <version>${essentials.version}</version>
</dependency>

<!-- Spring Boot 3 / Jackson 2 -->
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Both publish `dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule` under the same fully
qualified name — one extends Jackson 3's `tools.jackson.databind` module type, the other Jackson 2's
`com.fasterxml.jackson.databind`. Only ever put one on the classpath.

```java
@Bean
public Module essentialTypesJacksonModule() {   // the Module type of your Jackson major
    return new EssentialTypesJacksonModule();
}
```

### Complete WebMvc Example

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    // CharSequenceType as @PathVariable
    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable OrderId orderId) {
        return orderService.findById(orderId);
    }

    // Multiple semantic types as @PathVariable and @RequestParam
    @PostMapping("/for-customer/{customerId}/update/total-price")
    public Order updatePrice(@PathVariable CustomerId customerId,
                             @RequestParam("price") Amount price) {
        return orderService.updatePrice(customerId, price);
    }

    // NumberType as @RequestParam
    @GetMapping("/by-quantity")
    public List<Order> findByQuantity(@RequestParam("min") Quantity minQuantity,
                                      @RequestParam("max") Quantity maxQuantity) {
        return orderService.findByQuantityRange(minQuantity, maxQuantity);
    }

    // JSR-310 temporal type as @PathVariable
    @GetMapping("/by-due-date/{dueDate}")
    public List<Order> findByDueDate(@PathVariable DueDate dueDate) {
        return orderService.findByDueDate(dueDate);
    }
}
```

## WebFlux Configuration

Identical to WebMvc, with the reactive configurer:

```java
@Configuration
@Import(EssentialsWebFluxConfigurer.class)
public class WebConfiguration { }
```

Bodies are the same separate concern as under WebMvc — see
[Step 2 above](#step-2-separate-concern-jackson-for-json-requestresponse-bodies).

> **Do not override `configureHttpMessageCodecs` to register the Essentials Jackson module.** Doing so
> *replaces* the application's JSON codecs, and on Spring Boot 4 the usual copy-paste version of that
> override swaps Jackson 3 for Jackson 2 across the whole application, silently. `EssentialsWebFluxConfigurer`
> deliberately implements `addFormatters` and nothing else, and
> `EssentialsWebFluxConfigurerJackson3Test` asserts the codecs come out untouched. Register your Jackson
> module on the `ObjectMapper`/`JsonMapper` bean instead and let Boot build the codecs from it.

### Complete WebFlux Example

```java
@RestController
@RequestMapping("/api/reactive/orders")
public class ReactiveOrderController {

    @GetMapping("/{orderId}")
    public Mono<Order> getOrder(@PathVariable OrderId orderId) {
        return orderService.findById(orderId);
    }

    @PostMapping("/for-customer/{customerId}/update/total-price")
    public Mono<Order> updatePrice(@PathVariable CustomerId customerId,
                                   @RequestParam("price") Amount price) {
        return orderService.updatePrice(customerId, price);
    }

    @GetMapping("/by-due-date/{dueDate}")
    public Flux<Order> findByDueDate(@PathVariable DueDate dueDate) {
        return orderService.findByDueDate(dueDate);
    }
}
```

## Kotlin semantic types

`SingleValueTypeConverter` covers the **Java** `SingleValueType` hierarchy only — `CharSequenceType`,
`NumberType`, `JSR310SingleValueType`. The Kotlin interfaces in
`dk.trustworks.essentials.kotlin.types` are a separate, unrelated hierarchy, and
`KotlinValueTypeConverter` is what covers them.

It covers less than you would expect, because most Kotlin semantic types already bind without help.
Verify against `KotlinValueTypeConverterRequiredTest` rather than assuming:

| Your Kotlin type | Binds as `@PathVariable`? | What makes it work |
|---|---|---|
| `@JvmInline value class` over anything | yes | **Nothing from Essentials.** Kotlin *unboxes* a value class in every JVM signature, nullable included — `fun byOrderId(orderId: OrderId)` compiles to `byOrderId-GEJpfBY(String)`. Spring only ever sees the underlying type. `KotlinValueTypeConverter` is not reachable here and does not need to be |
| non-inline class wrapping a `String` | yes | Spring's own `ObjectToObjectConverter`, which finds the single `String`-arg constructor |
| non-inline class wrapping anything else | **only with this module** | `KotlinValueTypeConverter`. Without it the request fails with `ConversionNotSupportedException` — an HTTP **500**, not a 400 |

```kotlin
data class Weight(override val value: BigDecimal) : BigDecimalValueType<Weight>

@GetMapping("/shipments/by-weight/{weight}")
fun byWeight(@PathVariable weight: Weight): Shipment = ...
```

`kotlin-reflect` is an **optional** dependency here, so a Java-only application does not get it
transitively and `KotlinValueTypeConverter` is simply not registered. Kotlin applications already
have it.

### Kotlin request/response bodies

Not covered by anything in this module, and not by `EssentialTypesJacksonModule` either — that
registers serializers for the Java hierarchy. Register `jackson-module-kotlin`'s `KotlinModule` on the
web `ObjectMapper`.

The failure mode if you skip it is silent rather than loud: a `@JvmInline value class` serialises as
`{"value":"order-4711"}` instead of `"order-4711"`, so the wire format changes with no error.
`KotlinJacksonBodyJackson2Test` / `KotlinJacksonBodyJackson3Test` assert this on both Jackson majors.

## JSR-310 Temporal Types

The converter supports all `JSR310SingleValueType` subtypes:

| Your Type Extends | Wrapped Value |
|-------------------|---------------|
| `InstantType` | `Instant` |
| `LocalDateTimeType` | `LocalDateTime` |
| `LocalDateType` | `LocalDate` |
| `LocalTimeType` | `LocalTime` |
| `OffsetDateTimeType` | `OffsetDateTime` |
| `ZonedDateTimeType` | `ZonedDateTime` |

### JSON Request/Response Bodies

For JSON payloads, add `@JsonCreator` to the constructor:

```java
public class TransactionTime extends ZonedDateTimeType<TransactionTime> {
    @JsonCreator
    public TransactionTime(ZonedDateTime value) {
        super(value);
    }

    public static TransactionTime of(ZonedDateTime value) {
        return new TransactionTime(value);
    }

    public static TransactionTime now() {
        return new TransactionTime(ZonedDateTime.now(ZoneId.of("UTC")));
    }
}
```

### Path Variables and Request Parameters

```java
@GetMapping("/orders/by-due-date/{dueDate}")
public List<Order> findByDueDate(@PathVariable DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}

@GetMapping("/orders")
public List<Order> findByDueDateParam(@RequestParam("dueDate") DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}
```

## Gotchas

- **Nothing is registered until you `@Import` a configurer** - putting `types-spring-web` on the classpath has no
  effect on its own. There is no `AutoConfiguration.imports` in this module.

- **`WebMvcConfig` / `WebFluxConfig` are test classes, not API** - they exist in `src/test` to give this module's own
  `-Pjackson2` runs a Jackson 2 body setup. They are not on your classpath and are not a template. Use
  `EssentialsWebMvcConfigurer` / `EssentialsWebFluxConfigurer`.

- **ZonedDateTime URL encoding** - `ZonedDateTimeType` values must be URL-encoded in path variables and query parameters:
  ```java
  mockMvc.perform(get("/orders/by-time/{time}",
      URLEncoder.encode(transactionTime.toString(), StandardCharsets.UTF_8)))
  ```
  A *region* zone id (`Europe/Paris`) still will not work as a path variable: its encoded slash is rejected by the
  servlet container's path handling before conversion is reached. Use an offset-only value, or pass it as a request
  param.

- **JSON bodies are a separate mechanism** - `SingleValueTypeConverter` only handles `@PathVariable` and
  `@RequestParam`. Bodies need `EssentialTypesJacksonModule` registered on the **web** `ObjectMapper`, from the
  `types-jackson`/`types-jackson3` artifact matching your application's Jackson major. No Essentials starter does
  this for you — the starters configure the persistence mapper.

- **Kotlin is only partly this module's job** - see [Kotlin semantic types](#kotlin-semantic-types). Value classes bind
  without any Essentials converter; Kotlin *bodies* need `jackson-module-kotlin` and are covered by neither converter
  nor `EssentialTypesJacksonModule`.

- **NumberType from String** - The converter automatically parses numeric strings to the appropriate `Number` subtype (Integer, Long, BigDecimal, etc.).

- **Null handling** - The converter handles null values gracefully.

- **Type resolution** - Uses `SingleValueType.fromObject()` which requires a constructor accepting the wrapped value type.

## See Also

- [LLM-types-spring-web.md](../LLM/LLM-types-spring-web.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson3](../types-jackson3) / [types-jackson](../types-jackson) - Jackson serialization for types, for
  Jackson 3 and Jackson 2 respectively (required for JSON bodies)
