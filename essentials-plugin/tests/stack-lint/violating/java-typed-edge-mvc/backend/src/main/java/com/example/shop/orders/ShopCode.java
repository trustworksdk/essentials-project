package com.example.shop.orders;

import dk.trustworks.essentials.types.CharSequenceType;

public abstract class ShopCode<T extends ShopCode<T>> extends CharSequenceType<T> {
    protected ShopCode(CharSequence value) {
        super(value);
    }
}
