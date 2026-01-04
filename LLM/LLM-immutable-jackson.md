# Immutable-Jackson - LLM Reference

## Quick Facts
- Package: `dk.trustworks.essentials.jackson.immutable`
- Purpose: Jackson deserialization for immutable classes without matching constructors
- Dependencies: `jackson-databind`, `objenesis` (both provided scope)
- Key class: `EssentialsImmutableJacksonModule`
- **Status**: WORK-IN-PROGRESS

## TOC
- [Quick Facts](#quick-facts)
- [Core Classes](#core-classes)
- [EssentialsImmutableJacksonModule](#essentialsimmutablejacksonmodule)
  - [Registration](#registration)
  - [Factory Configuration](#factory-configuration)
- [ImmutableObjectsValueInstantiator](#immutableobjectsvalueinstantiator)
  - [Instantiation Strategy](#instantiation-strategy)
- [Objenesis Behavior](#objenesis-behavior)
- [Common Patterns](#common-patterns)
  - [Immutable Class with Computed Field](#immutable-class-with-computed-field)
  - [Java Record](#java-record)
  - [Validated Class](#validated-class)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Core Classes

| Class | Purpose |
|-------|---------|
| `EssentialsImmutableJacksonModule` | Jackson module enabling immutable object deserialization |
| `ImmutableObjectsValueInstantiator` | Custom `ValueInstantiator` with Objenesis fallback |

---

## EssentialsImmutableJacksonModule

**Location:** `dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule`

```java
public final class EssentialsImmutableJacksonModule extends SimpleModule {
    // Register with ObjectMapper
    public EssentialsImmutableJacksonModule();

    // Factory with opinionated defaults (includes this module)
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

### Registration

```java
// Simple registration
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialsImmutableJacksonModule());

// Or use factory (includes module automatically)
ObjectMapper mapper = EssentialsImmutableJacksonModule.createObjectMapper();

// With additional modules
ObjectMapper mapper = EssentialsImmutableJacksonModule.createObjectMapper(
    new EssentialTypesJacksonModule(),
    new Jdk8Module(),
    new JavaTimeModule()
);
```

### Factory Configuration

`createObjectMapper()` applies:

| Setting | Configuration |
|---------|---------------|
| Field visibility | `Visibility.ANY` |
| Getter/setter visibility | `Visibility.NONE` |
| `AUTO_DETECT_GETTERS` | disabled |
| `AUTO_DETECT_IS_GETTERS` | disabled |
| `AUTO_DETECT_SETTERS` | disabled |
| `AUTO_DETECT_CREATORS` | enabled |
| `AUTO_DETECT_FIELDS` | enabled |
| `DEFAULT_VIEW_INCLUSION` | disabled |
| `FAIL_ON_UNKNOWN_PROPERTIES` | disabled |
| `FAIL_ON_EMPTY_BEANS` | disabled |
| `PROPAGATE_TRANSIENT_MARKER` | enabled |
| `WRITE_DATES_AS_TIMESTAMPS` | disabled |

---

## ImmutableObjectsValueInstantiator

**Location:** `dk.trustworks.essentials.jackson.immutable.ImmutableObjectsValueInstantiator`

```java
public final class ImmutableObjectsValueInstantiator extends ValueInstantiator {
    public ImmutableObjectsValueInstantiator(
        Class<?> typeToInstantiate,
        ValueInstantiator defaultJacksonInstantiator
    );

    @Override
    public Object createUsingDefault(DeserializationContext context) throws IOException;

    @Override
    public boolean canCreateUsingDefault(); // Always returns true
}
```

### Instantiation Strategy

| Step | Method | Condition |
|------|--------|-----------|
| 1 | Standard Jackson `ValueInstantiator` | If `standardJacksonValueInstantiator.canCreateUsingDefault()` returns true |
| 2 | Objenesis (`ObjenesisStd`) | Fallback when standard instantiation fails |

After instantiation, fields are set via reflection (even `final` fields).

---

## Objenesis Behavior

**Critical:** When Objenesis creates the instance:

| Aspect | Behavior |
|--------|----------|
| Constructors | **NOT called** |
| Field initializers | **NOT executed** |
| Static initializers | Already executed (class loaded) |
| Field values | Only from JSON (defaults = Java zero values) |

### Example Impact

```java
public class Config {
    public final String name;
    public final int timeout = 30;  // Default NOT applied with Objenesis

    public Config(String name) {
        this.name = name;
        // If Objenesis used and JSON lacks "timeout":
        // timeout = 0 (not 30)
    }
}
```

---

## Common Patterns

### Immutable Class with Computed Field

```java
public final class ImmutableOrder {
    public final OrderId orderId;
    public final Money totalPrice;
    public final LocalDateTime orderedTimestamp;

    // Constructor sets orderedTimestamp automatically
    public ImmutableOrder(OrderId orderId, Money totalPrice) {
        this.orderId = orderId;
        this.totalPrice = totalPrice;
        this.orderedTimestamp = LocalDateTime.now();
    }
}

// JSON with orderedTimestamp deserializes correctly
// (field set via reflection, not constructor)
```

### Java Record

```java
public record CustomerInfo(
    CustomerId id,
    String name,
    EmailAddress email
) {}

// Works seamlessly - records have canonical constructor
```

### Validated Class

```java
public class ValidatedOrder {
    public final OrderId orderId;
    public final Money amount;

    public ValidatedOrder(OrderId orderId, Money amount) {
        this.orderId = FailFast.requireNonNull(orderId);
        this.amount = FailFast.requireNonNull(amount);
        FailFast.requireTrue(amount.isPositive(), "must be positive");
    }
}

// ⚠️ WARNING: If Objenesis used, validation is bypassed!
```

---

## Gotchas

- ⚠️ **Objenesis bypasses constructors** - validation logic won't run
- ⚠️ **Field defaults not applied** - `int timeout = 30` becomes `0` if not in JSON
- ⚠️ **Requires field-based serialization** - use `createObjectMapper()` or configure manually:
    ```java
    objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    objectMapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
    objectMapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
    ```
- ✅ **Private fields work** - reflection sets them directly
- ✅ **Static fields unaffected** - class already loaded, static init already ran
- ⚠️ **Collections may be null** - initialize in constructor or ensure JSON includes them

---

## See Also

- [README.md](../immutable-jackson/README.md) - Full documentation with examples
- [LLM-types-jackson.md](LLM-types-jackson.md) - `SingleValueType` Jackson support
- [LLM-immutable.md](LLM-immutable.md) - Core immutable patterns
- [EssentialsImmutableJacksonModuleTest.java](../immutable-jackson/src/test/java/dk/trustworks/essentials/jackson/immutable/EssentialsImmutableJacksonModuleTest.java) - Test examples
