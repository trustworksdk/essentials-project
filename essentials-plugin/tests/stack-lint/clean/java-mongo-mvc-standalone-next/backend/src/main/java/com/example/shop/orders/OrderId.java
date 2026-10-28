package com.example.shop.orders;

import dk.trustworks.essentials.types.CharSequenceType;

// Trap: the scaffolded id shape, with config/EssentialsWebConfig importing the converter.
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
