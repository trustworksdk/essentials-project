# Immutable - LLM Reference

> Quick reference for LLMs. For detailed explanations, see [README.md](../immutable/README.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.immutable`
- **Purpose**: Base class for immutable value objects with auto-generated `toString()`, `equals()`, `hashCode()`
- **Dependencies**: `shared` module (Reflector, Tuple)
- **Zero runtime deps**: No third-party dependencies
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>immutable</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `Reflector`, `Tuple` from [shared](./LLM-shared.md)

## TOC
- [When to Use](#when-to-use)
- [Core API](#core-api)
- [Usage Patterns](#usage-patterns)
- [Field Exclusions](#field-exclusions)
- [Implementation Details](#implementation-details)
- [Common Pitfalls](#common-pitfalls)
- [Testing](#testing)

## When to Use

| Alternative | Use When | Avoid When |
|------------|----------|------------|
| **Java Record** (preferred) | Simple value objects, Java 14+, standard equality | Need inheritance, field exclusions |
| **SingleValueType** (preferred) | Single-value wrappers (OrderId, CustomerId) | Multiple fields |
| **ImmutableValueObject** | Custom equality logic, need `@Exclude` annotations | Simple cases (use Record) |

**Rule**: Prefer Record or SingleValueType. Use ImmutableValueObject only for custom equality/toString via `@Exclude`.

## Core API

Base package: `dk.trustworks.essentials.immutable`

| Type | Role | Package |
|------|------|---------|
| `ImmutableValueObject` | Base class, auto-generates methods | `.` |
| `Immutable` | Marker interface | `.` |
| `@Exclude.ToString` | Exclude field from toString() | `.annotations` |
| `@Exclude.EqualsAndHashCode` | Exclude field from equals/hash | `.annotations` |

### Signatures

```java
package dk.trustworks.essentials.immutable;

public abstract class ImmutableValueObject implements Immutable {
    public int hashCode();          // Auto-generated, cached
    public boolean equals(Object);  // Auto-generated, field-based
    public String toString();       // Auto-generated, cached
}

public interface Immutable {} // Marker only
```

```java
package dk.trustworks.essentials.immutable.annotations;

public interface Exclude {
    @interface ToString {}
    @interface EqualsAndHashCode {}
}
```

## Usage Patterns

### Pattern: Basic Value Object

```java
import dk.trustworks.essentials.immutable.ImmutableValueObject;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class Address extends ImmutableValueObject {
    public final String street;
    public final String city;
    public final String zipCode;

    public Address(String street, String city, String zipCode) {
        this.street = requireNonNull(street, "street is required");
        this.city = requireNonNull(city, "city is required");
        this.zipCode = requireNonNull(zipCode, "zipCode is required");
    }
}

// Usage
var addr1 = new Address("123 Main", "NYC", "10001");
var addr2 = new Address("123 Main", "NYC", "10001");
assertThat(addr1).isEqualTo(addr2); // true - value equality
```

### Pattern: With Field Exclusions

```java
import dk.trustworks.essentials.immutable.ImmutableValueObject;
import dk.trustworks.essentials.immutable.annotations.Exclude;
import dk.trustworks.essentials.types.*;

public class Order extends ImmutableValueObject {
    public final OrderId orderId;           // Identity field
    public final CustomerId customerId;     // Identity field

    @Exclude.EqualsAndHashCode  // Metadata - not part of identity
    public final Instant createdAt;

    @Exclude.ToString           // Sensitive data
    public final String apiToken;

    public Order(OrderId orderId, CustomerId customerId,
                 Instant createdAt, String apiToken) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.createdAt = createdAt;
        this.apiToken = apiToken;
    }
}
```

### Pattern: Collections (Defensive Copy)

```java
import dk.trustworks.essentials.immutable.ImmutableValueObject;
import java.util.*;

public class OrderItems extends ImmutableValueObject {
    public final OrderId orderId;
    public final List<String> items;

    public OrderItems(OrderId orderId, List<String> items) {
        this.orderId = orderId;
        this.items = List.copyOf(items); // Immutable defensive copy
    }
}

// Defensive copy patterns
this.items = items != null ? List.copyOf(items) : List.of();
this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
this.tags = tags != null ? Set.copyOf(tags) : Set.of();
```

## Field Exclusions

### @Exclude.ToString
Excludes field from `toString()` output.

**Use for:**
- Sensitive: passwords, tokens, credit cards
- Large: binary content, large collections
- Redundant: computed/derived values

```java
@Exclude.ToString
public final String password;
```

### @Exclude.EqualsAndHashCode
Excludes field from `equals()` and `hashCode()`.

**Use for:**
- Metadata: timestamps, version, audit info
- Computed: derived from other fields
- Cache: cached calculations
- Non-identity: fields not defining object identity

```java
@Exclude.EqualsAndHashCode
public final Instant createdAt;        // Metadata

@Exclude.EqualsAndHashCode
public final Money subtotal;           // Computed from line items
```

## Implementation Details

### toString() Algorithm
1. Filters non-transient, non-static fields
2. Excludes `@Exclude.ToString` fields
3. Groups by null vs non-null
4. Sorts alphabetically within groups
5. Outputs: non-null first, then null
6. **Cached** after first call

```java
// Example output
"Order { customerId: C1, orderId: 123, createdAt: null }"
```

### equals() Algorithm
1. Null check → self check → type check (exact match, rejects subclasses)
2. Filters non-transient, non-static fields
3. Excludes `@Exclude.EqualsAndHashCode` fields
4. Sorts fields alphabetically
5. Compares using `Objects.equals()`, short-circuits on first diff
6. **No caching** (runs every call)

### hashCode() Algorithm
1. Filters non-transient, non-static fields
2. Excludes `@Exclude.EqualsAndHashCode` fields
3. Sorts alphabetically
4. Calculates: `31 * result + fieldHashCode` (follows `Objects.hash()`)
5. **Cached** after first call

### Performance
- `toString()`: Cached (lazy init, thread-safe)
- `hashCode()`: Cached (lazy init, thread-safe)
- `equals()`: Not cached (compares each time)

## Common Pitfalls

### ⚠️ Fields Must Be Final
```java
// ✅ Correct
public final OrderId orderId;

// ❌ Wrong - breaks caching
public OrderId orderId;
```
**Why**: `hashCode()` and `toString()` are cached. Non-final fields invalidate caching.

### ⚠️ Mutable Field Types
```java
// ❌ Wrong - mutable collection
public final ArrayList<Item> items;

// ✅ Correct - immutable collection
public final List<Item> items = List.copyOf(mutableList);
```

### ⚠️ Don't Exclude Identity Fields
```java
// ❌ Wrong - ID defines identity
@Exclude.EqualsAndHashCode
public final OrderId orderId;

// ✅ Correct - only exclude metadata/computed
@Exclude.EqualsAndHashCode
public final Instant createdAt;
```

### ⚠️ Always Validate Constructor Inputs
```java
import static dk.trustworks.essentials.shared.FailFast.*;

// ✅ Correct - validate inputs
public Customer(CustomerId id, String name) {
    this.id = requireNonNull(id, "ID required");
    this.name = requireNonBlank(name, "Name required");
}

// ❌ Wrong - no validation allows null/invalid
public Customer(CustomerId id, String name) {
    this.id = id;
    this.name = name;
}
```

## Testing

### Test Value Equality
```java
@Test
void valueObjectEquality() {
    var order1 = new Order(orderId, customerId, createdAt, apiToken);
    var order2 = new Order(orderId, customerId, createdAt, apiToken);

    assertThat(order1).isEqualTo(order2);
    assertThat(order1.hashCode()).isEqualTo(order2.hashCode());
}
```

### Test Field Exclusions
```java
@Test
void excludedFieldsNotAffectingEquality() {
    var order1 = new Order(orderId, customerId,
                          Instant.now(),     // Different
                          "token1");
    var order2 = new Order(orderId, customerId,
                          Instant.now().plusSeconds(10), // Different but excluded
                          "token2");

    assertThat(order1).isEqualTo(order2); // Still equal - createdAt excluded
}
```

### Test toString() Format
```java
@Test
void toStringFormat() {
    var order = new Order(OrderId.of(123), CustomerId.of("C1"),
                         Instant.now(), "secret-token");

    assertThat(order.toString())
        .contains("orderId: 123")
        .contains("customerId: C1")
        .doesNotContain("secret-token"); // Excluded via @Exclude.ToString
}
```

## Integration Points

**Dependencies:**
- `shared` - `Reflector` for field introspection, `Tuple` for internal ops

**Dependent modules:**
- `immutable-jackson` - Jackson serialization/deserialization support

**See:** [LLM-immutable-jackson.md](./LLM-immutable-jackson.md)

## Comparison Matrix

| Feature | Record | SingleValueType | ImmutableValueObject |
|---------|--------|-----------------|---------------------|
| Field count | Any | 1 | Any |
| Auto equals/hash | ✅ | ✅ | ✅ |
| Auto toString | ✅ | ✅ | ✅ |
| Exclude fields | ❌ | ❌ | ✅ |
| Inheritance | ❌ | ✅ | ✅ |
| Immutability | Built-in | Manual | Manual (final fields) |
| Caching | ❌ | ❌ | ✅ (toString, hash) |
| Java version | 14+ | 17+ | 17+ |

## See Also

- [README.md](../immutable/README.md) - Full documentation
- [LLM-shared.md](./LLM-shared.md) - Reflection utilities (`Reflector`, `FailFast`)
- [LLM-types.md](./LLM-types.md) - `SingleValueType` alternative
- [LLM-immutable-jackson.md](./LLM-immutable-jackson.md) - Jackson integration
- [ImmutableValueObjectTest.java](../immutable/src/test/java/dk/trustworks/essentials/immutable/ImmutableValueObjectTest.java) - Test examples
