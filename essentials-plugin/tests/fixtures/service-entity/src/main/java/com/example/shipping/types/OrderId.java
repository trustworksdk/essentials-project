package com.example.shipping.types;

/** Semantic id for the shipping order. */
public record OrderId(String value) {
    public static OrderId of(String value) {
        return new OrderId(value);
    }
}
