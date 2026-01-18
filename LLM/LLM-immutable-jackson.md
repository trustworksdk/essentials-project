# Immutable-Jackson - LLM Reference

> Token-efficient reference for Jackson deserialization of immutable classes. See [README.md](../immutable-jackson/README.md) for detailed explanations.

## Quick Facts
- Package: `dk.trustworks.essentials.jackson.immutable`
- Purpose: Jackson deserialization for immutable classes without matching constructors
- Dependencies: `jackson-databind`, `objenesis` (both provided scope)
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>immutable-jackson</artifactId>
</dependency>
```

**Dependencies from other modules**:
- Works with any immutable class; commonly used with `ImmutableValueObject` from [immutable](./LLM-immutable.md)
- Often combined with `EssentialTypesJacksonModule` from [types-jackson](./LLM-types-jackson.md)

## TOC
- [Core Classes](#core-classes)
- [API Reference](#api-reference)
- [Usage Patterns](#usage-patterns)
- [Configuration](#configuration)
- [Objenesis Behavior](#objenesis-behavior)
- [Gotchas](#gotchas)
- [Test References](#test-references)

---

## Core Classes

Base package: `dk.trustworks.essentials.jackson.immutable`

| Class | Role |
|-------|------|
| `EssentialsImmutableJacksonModule` | Jackson module enabling immutable deserialization |
| `ImmutableObjectsValueInstantiator` | Custom `ValueInstantiator` with Objenesis fallback |

---

## API Reference

### EssentialsImmutableJacksonModule

```java
package dk.trustworks.essentials.jackson.immutable;

public final class EssentialsImmutableJacksonModule extends SimpleModule {
    // Register with ObjectMapper
    public EssentialsImmutableJacksonModule();

    // Factory with opinionated defaults (includes this module + additionalModules)
    public static ObjectMapper createObjectMapper(Module... additionalModules);
}
```

**Registration:**
```java
// Manual registration
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialsImmutableJacksonModule());

// Or use factory
ObjectMapper mapper = EssentialsImmutableJacksonModule.createObjectMapper();

// Factory with additional modules
ObjectMapper mapper = EssentialsImmutableJacksonModule.createObjectMapper(
    new EssentialTypesJacksonModule(),
    new Jdk8Module(),
    new JavaTimeModule()
);
```

### ImmutableObjectsValueInstantiator

```java
package dk.trustworks.essentials.jackson.immutable;

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

**Instantiation strategy:**

| Priority | Method | When Used |
|----------|--------|-----------|
| 1 | Standard Jackson `ValueInstantiator` | If `canCreateUsingDefault()` returns true |
| 2 | Objenesis (`ObjenesisStd`) | Fallback when standard instantiation fails |

After instantiation, fields are set via reflection (even `final` fields).

---

## Usage Patterns

### Pattern: Immutable Class with Computed Field

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

// JSON includes orderedTimestamp - deserializes correctly
// (field set via reflection, not constructor)
```

### Pattern: Java Record

```java
public record CustomerInfo(CustomerId id, String name, EmailAddress email) {}

// Works seamlessly - records have canonical constructor
```

### Pattern: Validated Class (⚠️ Validation Bypass Risk)

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

// ⚠️ WARNING: If Objenesis used, validation is BYPASSED!
```

---

## Configuration

`createObjectMapper()` applies these defaults. See [README Configuration](../immutable-jackson/README.md#objectmapper-factory) for full details.

| Setting | Value | Purpose |
|---------|-------|---------|
| Field visibility | `ANY` | Serialize all fields |
| Getter/setter visibility | `NONE` | Fields only, no getters/setters |
| `AUTO_DETECT_GETTERS` | disabled | - |
| `AUTO_DETECT_IS_GETTERS` | disabled | - |
| `AUTO_DETECT_SETTERS` | disabled | - |
| `AUTO_DETECT_CREATORS` | enabled | - |
| `AUTO_DETECT_FIELDS` | enabled | - |
| `DEFAULT_VIEW_INCLUSION` | disabled | - |
| `FAIL_ON_UNKNOWN_PROPERTIES` | disabled | Don't fail on extra JSON fields |
| `FAIL_ON_EMPTY_BEANS` | disabled | Allow empty objects |
| `PROPAGATE_TRANSIENT_MARKER` | enabled | Respect `transient` keyword |
| `WRITE_DATES_AS_TIMESTAMPS` | disabled | ISO-8601 format |

**Manual configuration:**
```java
objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
objectMapper.setVisibility(PropertyAccessor.GETTER, Visibility.NONE);
objectMapper.setVisibility(PropertyAccessor.SETTER, Visibility.NONE);
```

---

## Objenesis Behavior

**CRITICAL**: When Objenesis creates instance, constructors are BYPASSED.

| Aspect | Behavior |
|--------|----------|
| Constructors | **NOT called** |
| Field initializers | **NOT executed** |
| Static initializers | Already executed (class loaded) |
| Field values | Only from JSON (defaults = Java zero values) |

**Example Impact:**
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

## Gotchas

See [README Gotchas](../immutable-jackson/README.md#gotchas) for detailed explanations.

- ⚠️ **Objenesis bypasses constructors** → validation logic won't run
- ⚠️ **Field defaults not applied** → `int timeout = 30` becomes `0` if not in JSON
- ⚠️ **Requires field-based serialization** → use `createObjectMapper()` or configure manually
- ✅ **Private fields work** → reflection sets them directly
- ✅ **Static fields unaffected** → class already loaded, static init already ran
- ⚠️ **Collections may be null** → ensure JSON includes them or initialize in constructor

**Field initializer example:**
```java
public class Counter {
    public final int count;
    private static int instanceCount = 0;

    public Counter(int count) {
        this.count = count;
        instanceCount++;  // NOT incremented when Objenesis creates instance
    }
}
```

---

## Test References

Key test files demonstrating usage:
- `EssentialsImmutableJacksonModuleTest.java` - Comprehensive usage examples

Test model classes (in `src/test`):
- `ImmutableOrder.java` - Immutable class with computed timestamp
- `OrderId`, `CustomerId`, `ProductId`, `AccountId` - SingleValueType examples
- `ImmutableSerializationTestSubject.java` - Complex deserialization cases

---

## See Also

- [README.md](../immutable-jackson/README.md) - Full documentation with detailed explanations
- [LLM-types-jackson.md](LLM-types-jackson.md) - `SingleValueType` Jackson support
- [LLM-immutable.md](LLM-immutable.md) - Core immutable patterns
