# Types-Spring-Web - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.types.spring.web`
- Purpose: Spring WebMvc/WebFlux converter for `SingleValueType` as `@PathVariable`/`@RequestParam`
- Dependencies: `spring-web` (provided scope)
- Key class: `SingleValueTypeConverter`
- Status: WORK-IN-PROGRESS

## TOC
- [Core API](#core-api)
- [Configuration](#configuration)
- [Usage Patterns](#usage-patterns)
- [JSR-310 Temporal Types](#jsr-310-temporal-types)
- [Conversion Logic](#conversion-logic)
- [Integration Points](#integration-points)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Core API

### SingleValueTypeConverter

**Location:** `dk.trustworks.essentials.types.spring.web.SingleValueTypeConverter`

```java
public final class SingleValueTypeConverter implements GenericConverter {
    @Override
    public Set<ConvertiblePair> getConvertibleTypes();

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType);
}
```

**Convertible Pairs:**
- `String` → `CharSequenceType`
- `Number` → `NumberType`
- `String` → `NumberType`
- `String` → `JSR310SingleValueType`

---

## Configuration

### WebMvc Setup

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
    }
}
```

### WebFlux Setup

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

**Required for JSON bodies:** `types-jackson` module + `EssentialTypesJacksonModule` spring bean

---

## Usage Patterns

### CharSequenceType as @PathVariable

```java
@GetMapping("/{orderId}")
public Order getOrder(@PathVariable OrderId orderId) {
    return orderService.findById(orderId);
}
```

### Multiple Semantic Types

```java
@PostMapping("/customer/{customerId}")
public Order updatePrice(@PathVariable CustomerId customerId,
                         @RequestParam("price") Amount price) {
    return orderService.updatePrice(customerId, price);
}
```

### NumberType as @RequestParam

```java
@GetMapping("/by-quantity")
public List<Order> findByQuantity(@RequestParam("min") Quantity minQuantity,
                                  @RequestParam("max") Quantity maxQuantity) {
    return orderService.findByQuantityRange(minQuantity, maxQuantity);
}
```

### WebFlux Reactive

```java
@GetMapping("/{orderId}")
public Mono<Order> getOrder(@PathVariable OrderId orderId) {
    return orderService.findById(orderId);
}

@GetMapping("/by-date/{dueDate}")
public Flux<Order> findByDueDate(@PathVariable DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}
```

---

## JSR-310 Temporal Types

### Supported Types

| Base Type (`dk.trustworks.essentials.types`)  | Wrapped Value | Path/Param Format | Notes |
|-----------------------------------------------|---------------|-------------------|-------|
| `InstantType`                                 | `Instant` | `2024-01-15T10:30:00Z` | ISO-8601 |
| `LocalDateTimeType`                           | `LocalDateTime` | `2024-01-15T10:30:00` | ISO-8601 |
| `LocalDateType`                               | `LocalDate` | `2024-01-15` | ISO-8601 |
| `LocalTimeType`                               | `LocalTime` | `10:30:00` | ISO-8601 |
| `OffsetDateTimeType`                          | `OffsetDateTime` | `2024-01-15T10:30:00+01:00` | ISO-8601 |
| `ZonedDateTimeType`                           | `ZonedDateTime` | URL-encoded string | Auto-decoded |

### Usage

```java
@GetMapping("/by-due-date/{dueDate}")
public List<Order> findByDueDate(@PathVariable DueDate dueDate) {
    return orderService.findByDueDate(dueDate);
}
```

**For JSON bodies:** Add `@JsonCreator` to constructor:
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

---

## Conversion Logic

### Implementation Details

| Source Type | Target Type | Logic |
|-------------|-------------|-------|
| `SingleValueType` | Any | Returns `value()` |
| `String` | `LocalDateTimeType` | `LocalDateTime.parse(source)` |
| `String` | `LocalDateType` | `LocalDate.parse(source)` |
| `String` | `InstantType` | `Instant.parse(source)` |
| `String` | `LocalTimeType` | `LocalTime.parse(source)` |
| `String` | `OffsetDateTimeType` | `OffsetDateTime.parse(source)` |
| `String` | `ZonedDateTimeType` | `ZonedDateTime.parse(URLDecoder.decode(source, UTF_8))` |
| `String` | `NumberType` | `NumberUtils.parseNumber()` → `SingleValueType.fromObject()` |
| `Number` | `NumberType` | `SingleValueType.fromObject(source, targetType)` |
| Other | `CharSequenceType` | `SingleValueType.fromObject(source, targetType)` |

**Key method:** `SingleValueType.fromObject(Object value, Class<SingleValueType<?, ?>> type)`

---

## Integration Points

### Dependencies

| Module | Scope | Purpose |
|--------|-------|---------|
| **types** | compile | `SingleValueType`, `CharSequenceType`, `NumberType`, JSR-310 types |
| **spring-web** | provided | `GenericConverter`, `TypeDescriptor`, `FormatterRegistry` |
| **spring-webmvc** | provided | WebMvc support (optional) |
| **spring-webflux** | provided | WebFlux support (optional) |

### Related Modules

| Module | Relationship |
|--------|--------------|
| **[types-jackson](LLM-types-jackson.md)** | Required for `@RequestBody`/`@ResponseBody` JSON serialization |
| **[types](LLM-types.md)** | Core types module with `SingleValueType` base classes |

---

## Gotchas

⚠️ **Converter scope limited** - Only handles `@PathVariable` and `@RequestParam`; JSON bodies require `types-jackson` module

⚠️ **ZonedDateTime URL encoding** - Must URL-encode `ZonedDateTimeType` in URLs:
```java
URLEncoder.encode(transactionTime.toString(), StandardCharsets.UTF_8)
```
Converter auto-decodes on receipt

⚠️ **NumberType string parsing** - Converter uses `NumberType.resolveNumberClass()` + `NumberUtils.parseNumber()` to detect target number type (Integer, Long, BigDecimal, etc.)

⚠️ **Type resolution requires constructor** - Uses `SingleValueType.fromObject()` which requires constructor accepting wrapped value type

⚠️ **Null handling** - Converter handles null gracefully

---

## See Also

- [README.md](../types-spring-web/README.md) - Full docs, examples, setup guide
- [LLM-types.md](LLM-types.md) - Core types module reference
- [LLM-types-jackson.md](LLM-types-jackson.md) - Jackson serialization for JSON bodies
- Test reference: `WebMvcControllerTest.java`, `WebFluxControllerTest.java`
