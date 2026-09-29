package com.acme.shipping.types;

/** Semantic id for the shipping order. Part of the BC's public surface. */
public record OrderId(String value) {
    public static OrderId of(String value) {
        return new OrderId(value);
    }
}
