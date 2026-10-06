package com.example.shop.orders.types;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;

/**
 * Strongly-typed aggregate id for the Orders bounded context.
 *
 * BC-internal value object — lives in {@code orders/types/} because it is shared by several slices
 * within this BC. A type used by only ONE slice stays inside that slice's directory; do not
 * prematurely promote.
 *
 * Never use a raw {@code String} or {@code UUID} for an identity — a semantic type is what stops an
 * OrderId being passed where some other id was meant.
 *
 * Jackson needs only the {@code CharSequence} constructor: {@code types-jackson3} pins a value type's
 * single-argument constructor as its delegating creator. The {@code String} one is a convenience.
 */
public class OrderId extends CharSequenceType<OrderId> implements Identifier {

    public OrderId(CharSequence value) {
        super(value);
    }

    public OrderId(String value) {
        super(value);
    }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }

    public static OrderId random() {
        return new OrderId(RandomIdGenerator.generate());
    }
}
