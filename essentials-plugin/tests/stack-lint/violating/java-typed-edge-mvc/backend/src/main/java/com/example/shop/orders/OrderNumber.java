package com.example.shop.orders;

import dk.trustworks.essentials.types.LongType;

public class OrderNumber extends LongType<OrderNumber> {
    public OrderNumber(Long value) {
        super(value);
    }

    public static OrderNumber of(long value) {
        return new OrderNumber(value);
    }
}
