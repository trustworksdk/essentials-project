# Essentials - Immutable

> Zero-dependency utilities for creating simple immutable value objects without code generation.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-immutable.md](../LLM/LLM-immutable.md)

## Table of Contents
- [Overview](#overview)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [When to Use](#when-to-use)
- [Generated Methods](#generated-methods)
- [Important Warnings](#important-warnings)
- [Field Exclusions](#field-exclusions)
- [See Also](#see-also)

## Overview

The `immutable` module provides `ImmutableValueObject` - a base class for creating immutable Value Objects with auto-generated `toString()`, `equals()`, and `hashCode()` methods based on your class fields.

**Value Object definition:** A Value Object is defined by its property values, not by an identifier. Two value objects of the same type with the same property values are considered equal.

**Zero dependencies:** This module has no runtime dependencies (not even SLF4J).

For advanced Jackson deserialization support, see [immutable-jackson](../immutable-jackson).

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>immutable</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Quick Start

```java
import dk.trustworks.essentials.immutable.ImmutableValueObject;
import dk.trustworks.essentials.immutable.annotations.Exclude;

public class ImmutableOrder extends ImmutableValueObject {
    public final OrderId                  orderId;
    public final CustomerId               customerId;
    public final Percentage               percentage;
    public final EmailAddress             email;
    @Exclude.EqualsAndHashCode
    public final Map<ProductId, Quantity> orderLines;
    @Exclude.ToString
    public final Money                    totalPrice;

    public ImmutableOrder(OrderId orderId,
                          CustomerId customerId,
                          Percentage percentage,
                          EmailAddress email,
                          Map<ProductId, Quantity> orderLines,
                          Money totalPrice) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.percentage = percentage;
        this.email = email;
        this.orderLines = orderLines;
        this.totalPrice = totalPrice;
    }
}

// Two instances with identical values are equal
var order1 = new ImmutableOrder(OrderId.of(123), CustomerId.of("C1"),
                                Percentage.from("50%"), EmailAddress.of("test@example.com"),
                                Map.of(ProductId.of("P1"), Quantity.of(10)),
                                Money.of("100.00", CurrencyCode.USD));

var order2 = new ImmutableOrder(OrderId.of(123), CustomerId.of("C1"),
                                Percentage.from("50%"), EmailAddress.of("test@example.com"),
                                Map.of(ProductId.of("P2"), Quantity.of(5)), // Different - but excluded!
                                Money.of("100.00", CurrencyCode.USD));

assertThat(order1).isEqualTo(order2); // true - orderLines excluded from equals
```

## When to Use

**Choose `ImmutableValueObject` when:**
- You need value objects with custom equality logic (field exclusions)
- You're on Java 17+ but prefer traditional classes over Records
- You need inheritance (Records don't support it)

**Choose Java `Record` when:**
- You want built-in immutability (Java 14+)
- You don't need to exclude fields from `equals`/`hashCode`
- You prefer concise syntax

**Choose `SingleValueType` when:**
- Wrapping a single value (e.g., `OrderId`, `CustomerId`)
- See [types](../types) module

## Generated Methods

### `toString()`
Fields sorted alphabetically, non-null values first, then null values. Excludes `@Exclude.ToString` fields.

```java
// Returns: "ImmutableOrder { customerId: C1, email: test@example.com, orderId: 123, orderLines: {P1=10}, percentage: 50.00% }"
order.toString();
```

### `equals(Object)`
Compares all non-excluded fields alphabetically. Rejects subclasses (exact type match required). Excludes `@Exclude.EqualsAndHashCode` fields.

### `hashCode()`
Calculated from non-excluded fields (alphabetically sorted). Follows `Objects.hash()` algorithm. Excludes `@Exclude.EqualsAndHashCode` fields.

**Performance:** Both `toString()` and `hashCode()` are cached after first invocation.

See [Javadoc](src/main/java/dk/trustworks/essentials/immutable/ImmutableValueObject.java) for detailed algorithm descriptions.

## Important Warnings

### Fields Must Be Final
**CRITICAL:** All non-transient instance fields MUST be `final` to ensure true immutability.

### Mutable Field Types
If a field's type is mutable (`List`, `Map`, `Set`, etc.), you **cannot reliably use** cached `toString()` or `hashCode()` results as the underlying data may change.

**Recommendation:** Use immutable collections:
```java
public final List<Item> items = List.copyOf(mutableList); // Java 10+
public final Map<K, V> map = Map.copyOf(mutableMap);
```

## Field Exclusions

### `@Exclude.ToString`
Excludes a field from `toString()` output. Useful for:
- Sensitive data (passwords, tokens)
- Large data structures (binary content)
- Redundant computed values

### `@Exclude.EqualsAndHashCode`
Excludes a field from `equals()` and `hashCode()` calculations. Useful for:
- Metadata fields (timestamps, version numbers)
- Cache fields
- Fields that don't define object identity

**Example:**
```java
public class Order extends ImmutableValueObject {
    public final OrderId orderId;
    @Exclude.EqualsAndHashCode  // Metadata - not part of identity
    public final Instant createdAt;
    @Exclude.ToString           // Sensitive
    public final String password;
}
```

## See Also

- [LLM-immutable.md](../LLM/LLM-immutable.md) - Quick reference for LLMs
- [immutable-jackson](../immutable-jackson) - Jackson deserialization support
- [types](../types) - Single-value type wrappers (`SingleValueType`)
- [ImmutableValueObjectTest.java](src/test/java/dk/trustworks/essentials/immutable/ImmutableValueObjectTest.java) - Comprehensive test examples
