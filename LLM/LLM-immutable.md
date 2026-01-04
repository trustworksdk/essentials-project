# Immutable - LLM Reference

## Quick Facts
- **Package**: `dk.trustworks.essentials.immutable`
- **Purpose**: Base class for immutable value objects with auto-generated `toString()`, `equals()`, `hashCode()`
- **Zero dependencies**: No runtime deps (not even SLF4J)
- **Alternatives**: Prefer Java `Record` or `SingleValueType` (see comparison below)
- **Status**: WORK-IN-PROGRESS

## TOC
- [When to Use](#when-to-use)
- [Core API](#core-api)
- [Basic Usage](#basic-usage)
- [Field Exclusions](#field-exclusions)
- [Implementation Details](#implementation-details)
- [Common Pitfalls](#common-pitfalls)
- [Integration Points](#integration-points)
- [Testing](#testing)

## When to Use

| Alternative | Use When | Don't Use When |
|------------|----------|----------------|
| **Java Record** | Simple value objects, Java 14+, no field exclusions needed | Need inheritance, custom equality logic |
| **SingleValueType** | Wrapping single value (OrderId, CustomerId) | Multiple fields |
| **ImmutableValueObject** | Custom equality (exclude fields), need inheritance | Simple cases (prefer Record) |

**Bottom line**: Prefer Record or SingleValueType. Use ImmutableValueObject only when you need `@Exclude` annotations.

## Core API

| Class/Interface | Purpose | Key Methods |
|----------------|---------|-------------|
| `ImmutableValueObject` | Base class for value objects | `toString()`, `equals()`, `hashCode()` |
| `Immutable` | Marker interface | None (marker only) |
| `@Exclude.ToString` | Annotation: exclude field from `toString()` | N/A |
| `@Exclude.EqualsAndHashCode` | Annotation: exclude field from equals/hash | N/A |

### Class Signatures

```java
// Base class
public abstract class ImmutableValueObject implements Immutable {
    public int hashCode();          // Auto-generated, cached
    public boolean equals(Object);  // Auto-generated, field-based
    public String toString();       // Auto-generated, cached
}

// Marker interface
public interface Immutable {}

// Exclusion annotations
public interface Exclude {
    @interface ToString {}
    @interface EqualsAndHashCode {}
}
```

## Basic Usage

### Simple Value Object

```java
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
assertThat(addr1).isEqualTo(addr2); // true - same values
```

### With Exclusions

```java
public class ImmutableOrder extends ImmutableValueObject {
    public final OrderId orderId;
    public final CustomerId customerId;
    public final EmailAddress email;
    public final Percentage percentage;

    @Exclude.EqualsAndHashCode  // Not part of equality
    public final Map<ProductId, Quantity> orderLines;

    @Exclude.ToString           // Sensitive data
    public final Money totalPrice;

    public ImmutableOrder(OrderId orderId, CustomerId customerId,
                          Percentage percentage, EmailAddress email,
                          Map<ProductId, Quantity> orderLines, Money totalPrice) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.percentage = percentage;
        this.email = email;
        this.orderLines = orderLines;
        this.totalPrice = totalPrice;
    }
}
```

### Collections Pattern

```java
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class Cart extends ImmutableValueObject {
    public final CustomerId customerId;
    public final List<CartItem> items;

    public Cart(CustomerId customerId, List<CartItem> items) {
        this.customerId = requireNonNull(customerId, "customerId is required");
        this.items = List.copyOf(items); // Defensive immutable copy
    }

    // Return new instance for mutations
    public Cart addItem(CartItem item) {
        List<CartItem> newItems = new ArrayList<>(items);
        newItems.add(item);
        return new Cart(customerId, newItems);
    }
}
```

## Field Exclusions

### @Exclude.ToString
Excludes field from `toString()` output.

**Use for:**
- Sensitive data (passwords, tokens, credit cards)
- Large binary content
- Redundant computed values

```java
@Exclude.ToString
public final String password; // Sensitive
```

### @Exclude.EqualsAndHashCode
Excludes field from `equals()` and `hashCode()`.

**Use for:**
- Computed/derived fields
- Metadata (timestamps, audit info)
- Cache fields
- Fields not defining identity

```java
@Exclude.EqualsAndHashCode
public final Instant createdAt; // Metadata

@Exclude.EqualsAndHashCode
public final Money subtotal;    // Computed from line items
```

## Implementation Details

### toString() Algorithm
1. Filters non-transient, non-static fields without `@Exclude.ToString`
2. Groups by null vs non-null values
3. Sorts alphabetically within each group
4. Outputs non-null first, then null
5. **Caches result** after first call

```java
// Example output
"ImmutableOrder { customerId: C1, email: test@example.com, orderId: 123, percentage: 50.00%, totalPrice: null }"
```

### equals() Algorithm
1. Null check, self check, type check (exact match, no subclasses)
2. Compares non-excluded fields alphabetically
3. Short-circuits on first difference
4. Uses `Objects.equals()` for field comparison

### hashCode() Algorithm
1. Filters non-excluded fields, sorts alphabetically
2. Follows `Objects.hash()` algorithm: `31 * result + fieldHashCode`
3. **Caches result** after first call

**Field inclusion order:**
- Non-transient instance fields only
- Excludes `@Exclude.EqualsAndHashCode` annotated fields
- Alphabetically sorted by field name

### Performance
- `toString()`: Cached after first invocation
- `hashCode()`: Cached after first invocation
- `equals()`: No caching (compares fields each time)
- **Thread-safe**: Caching uses lazy initialization

## Common Pitfalls

### Critical: Fields Must Be Final

```java
// Correct
public final OrderId orderId;

// Wrong - breaks caching assumptions
public OrderId orderId;
```

**Why**: `hashCode()` and `toString()` are cached. Mutable fields invalidate cached values.

### Mutable Field Types

```java
// Wrong - mutable collection
public final ArrayList<Item> items;

// Correct - immutable collection
public final List<Item> items = List.copyOf(mutableList);
```

**Collections best practices:**
```java
// Defensive copies
this.items = items != null ? List.copyOf(items) : List.of();
this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
this.tags = tags != null ? Set.copyOf(tags) : Set.of();
```

### Don't Exclude Identity Fields

```java
// Wrong - ID is part of identity
@Exclude.EqualsAndHashCode
public final OrderId orderId;

// Correct - only exclude metadata/computed
@Exclude.EqualsAndHashCode
public final Instant createdAt;
```

### Constructor Validation

```java
import static dk.trustworks.essentials.shared.FailFast.*;

// Correct - validate inputs
public Customer(CustomerId id, String name) {
    this.id = requireNonNull(id, "ID required");
    this.name = requireNonBlank(name, "Name required");
}

// Wrong - no validation
public Customer(CustomerId id, String name) {
    this.id = id;
    this.name = name;
}
```

## Integration Points

### Dependencies
- **shared** - `Reflector` for field introspection, `Tuple` for internal operations

### Dependent Modules
- **immutable-jackson** - Jackson serialization/deserialization support
- See [LLM-immutable-jackson.md](./LLM-immutable-jackson.md)

### External Dependencies
- **Java 17+** - Required runtime
- **None** - Zero runtime dependencies

## Testing

### Test Equality

```java
@Test
void testValueObjectEquality() {
    var order1 = new ImmutableOrder(orderId, customerId, percentage,
                                    email, orderLines, totalPrice);
    var order2 = new ImmutableOrder(orderId, customerId, percentage,
                                    email, orderLines, totalPrice);

    assertThat(order1).isEqualTo(order2);
    assertThat(order1.hashCode()).isEqualTo(order2.hashCode());
}
```

### Test Field Exclusions

```java
@Test
void testExcludedFieldsNotAffectingEquality() {
    var order1 = new ImmutableOrder(orderId, customerId, percentage, email,
                                    Map.of(ProductId.of("P1"), Quantity.of(10)), // Excluded
                                    totalPrice);
    var order2 = new ImmutableOrder(orderId, customerId, percentage, email,
                                    Map.of(ProductId.of("P2"), Quantity.of(5)),  // Different but excluded
                                    totalPrice);

    assertThat(order1).isEqualTo(order2); // Still equal - orderLines excluded
}
```

### Test toString() Output

```java
@Test
void testToStringFormat() {
    var order = new ImmutableOrder(OrderId.of(123), CustomerId.of("C1"),
                                   Percentage.from("50%"), EmailAddress.of("test@example.com"),
                                   Map.of(ProductId.of("P1"), Quantity.of(10)),
                                   Money.of("100.00", CurrencyCode.USD));

    // totalPrice excluded via @Exclude.ToString
    assertThat(order.toString())
        .contains("orderId: 123")
        .contains("customerId: C1")
        .doesNotContain("totalPrice");
}
```

## Comparison Matrix

| Feature | Record | SingleValueType | ImmutableValueObject |
|---------|--------|-----------------|---------------------|
| Field count | Any | 1 | Any |
| Auto equals/hash | Yes | Yes | Yes |
| Auto toString | Yes | Yes | Yes |
| Exclude fields | No | No | Yes (annotations) |
| Inheritance | No | Yes | Yes |
| Immutability | Built-in | Manual | Manual (final fields) |
| Caching | No | No | Yes (toString, hash) |
| Java version | 14+ | 17+ | 17+ |
| Best for | General use | IDs/single values | Custom equality |

## See Also

- **README**: [immutable/README.md](../immutable/README.md) - Full documentation
- **Related Modules**:
  - [LLM-shared.md](./LLM-shared.md) - Reflection utilities
  - [LLM-types.md](./LLM-types.md) - SingleValueType alternative
  - [LLM-immutable-jackson.md](./LLM-immutable-jackson.md) - Jackson integration
- **Tests**: [ImmutableValueObjectTest.java](../immutable/src/test/java/dk/trustworks/essentials/immutable/ImmutableValueObjectTest.java)
- **Source**: [ImmutableValueObject.java](../immutable/src/main/java/dk/trustworks/essentials/immutable/ImmutableValueObject.java)
