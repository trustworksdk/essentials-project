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
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Gotchas](#gotchas)

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

**1. Register the converter:**

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
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

### Step 1: Add Jackson Support (for JSON request/response bodies)

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

```java
@Bean
public Module essentialTypesJacksonModule() {
    return new EssentialTypesJacksonModule();
}
```

### Step 2: Register SingleValueTypeConverter

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
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

### Step 1: Add Jackson Support

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

```java
@Bean
public Module essentialTypesJacksonModule() {
    return new EssentialTypesJacksonModule();
}
```

### Step 2: Register Converter and Configure Codecs

```java
@Configuration
public class WebFluxConfig implements WebFluxConfigurer {
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
        configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
```

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

- **ZonedDateTime URL encoding** - `ZonedDateTimeType` values must be URL-encoded in path variables and query parameters:
  ```java
  mockMvc.perform(get("/orders/by-time/{time}",
      URLEncoder.encode(transactionTime.toString(), StandardCharsets.UTF_8)))
  ```

- **JSON bodies require types-jackson** - The `SingleValueTypeConverter` only handles `@PathVariable` and `@RequestParam`. For JSON request/response bodies, add `types-jackson` dependency and register `EssentialTypesJacksonModule`.

- **NumberType from String** - The converter automatically parses numeric strings to the appropriate `Number` subtype (Integer, Long, BigDecimal, etc.).

- **Null handling** - The converter handles null values gracefully.

- **Type resolution** - Uses `SingleValueType.fromObject()` which requires a constructor accepting the wrapped value type.

## See Also

- [LLM-types-spring-web.md](../LLM/LLM-types-spring-web.md) - API reference for LLM assistance
- [types](../types) - Core types module (`SingleValueType`, `CharSequenceType`, etc.)
- [types-jackson](../types-jackson) - Jackson serialization for types (required for JSON bodies)
