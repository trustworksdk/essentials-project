# Types-Spring-Web - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](../types-spring-web/README.md).

## Quick Facts
- Package: `dk.trustworks.essentials.types.spring.web`
- Purpose: Spring WebMvc/WebFlux converter enabling `SingleValueType` as `@PathVariable`/`@RequestParam`
- Dependencies: `spring-web` (provided), `spring-webmvc` (provided) or `spring-webflux` (provided)
- Key class: `SingleValueTypeConverter`

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
import dk.trustworks.essentials.types.spring.web.SingleValueTypeConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
```

### WebFlux

```java
import dk.trustworks.essentials.types.spring.web.SingleValueTypeConverter;
import org.springframework.web.reactive.config.WebFluxConfigurer;

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

**JSON request/response bodies:** Requires `types-jackson` module with `EssentialTypesJacksonModule` bean. See [README Configuration](../types-spring-web/README.md#webmvc-configuration).

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

⚠️ **Scope limitation** - Converter handles ONLY `@PathVariable` and `@RequestParam`, NOT `@RequestBody`/`@ResponseBody` (use `types-jackson`)

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
