package com.example.shop.orders;

import dk.trustworks.essentials.types.CharSequenceType;

// The shape the plugin scaffolds: Spring binds it through the String constructor, the Essentials converter is still absent.
public class OrderId extends CharSequenceType<OrderId> {
    public OrderId(CharSequence value) {
        super(value);
    }

    public OrderId(String value) {
        super(value);
    }

    public static OrderId of(CharSequence value) {
        return new OrderId(value);
    }
}
