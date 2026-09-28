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

Neither configurer is auto-configuration: declaring the dependency changes nothing until you
`@Import` one. Neither touches HTTP message converters or codecs, so adding this module cannot
change how your request and response bodies are serialised.

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
`JsonMapper` does.

From 0.60 Essentials supports Jackson 3 only (matching Spring Boot 4). `types-jackson3` is a dependency of
this module, so `dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule` is already on your
classpath. Declare it as a bean and Spring Boot registers it on its web `JsonMapper`:

```java
@Bean
public EssentialTypesJacksonModule essentialTypesJacksonModule() {   // a tools.jackson.databind.JacksonModule
    return new EssentialTypesJacksonModule();
}
```

The Essentials Postgres and Mongo Spring Boot starters already define this bean, so an application using
one of them needs nothing extra. (Those starters do *not* pass web-layer module beans into their
persistence serializer — web and persistence mappers are configured independently.)

> **Upgrading from 0.50 (Jackson 2):** replace a `types-jackson` dependency with `types-jackson3` (same
> class names), and change `com.fasterxml.jackson.databind` imports to `tools.jackson.databind`.

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
> override silently throws away the codecs Boot built from its configured `JsonMapper`. `EssentialsWebFluxConfigurer`
> deliberately implements `addFormatters` and nothing else, and
> `EssentialsWebFluxConfigurerJackson3Test` asserts the codecs come out untouched. Register your Jackson
> module as a bean instead and let Boot build the codecs from it.

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
web `JsonMapper`.

The failure mode if you skip it is silent rather than loud: a `@JvmInline value class` serialises as
`{"value":"order-4711"}` instead of `"order-4711"`, so the wire format changes with no error.
`KotlinJacksonBodyJackson3Test` asserts this.

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

- **ZonedDateTime URL encoding** - `ZonedDateTimeType` values must be URL-encoded in path variables and query parameters:
  ```java
  mockMvc.perform(get("/orders/by-time/{time}",
      URLEncoder.encode(transactionTime.toString(), StandardCharsets.UTF_8)))
  ```
  A *region* zone id (`Europe/Paris`) still will not work as a path variable: its encoded slash is rejected by the
  servlet container's path handling before conversion is reached. Use an offset-only value, or pass it as a request
  param.

- **JSON bodies are a separate mechanism** - `SingleValueTypeConverter` only handles `@PathVariable` and
  `@RequestParam`. Bodies need `EssentialTypesJacksonModule` (from `types-jackson3`) registered on the **web** `JsonMapper`,
  which Spring Boot does when it is declared as a bean. The Essentials Postgres/Mongo starters declare that bean;
  without a starter, declare it yourself.

- **Kotlin is only partly this module's job** - see [Kotlin semantic types](#kotlin-semantic-types). Value classes bind
  without any Essentials converter; Kotlin *bodies* need `jackson-module-kotlin` and are covered by neither converter
  nor `EssentialTypesJacksonModule`.

- **NumberType from String** - The converter automatically parses numeric strings to the appropriate `Number` subtype (Integer, Long, BigDecimal, etc.).

- **Null handling** - The converter handles null values gracefully.

- **Type resolution** - Uses `SingleValueType.fromObject()` which requires a constructor accepting the wrapped value type.

## See Also

- [LLM-types-spring-web.md](../LLM/LLM-types-spring-web.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson3](../types-jackson3) - Jackson 3 serialization for types (required for JSON bodies)
