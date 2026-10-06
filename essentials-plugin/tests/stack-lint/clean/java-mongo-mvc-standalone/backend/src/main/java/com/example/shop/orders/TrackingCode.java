package com.example.shop.orders;

import dk.trustworks.essentials.types.CharSequenceType;

public class TrackingCode extends CharSequenceType<TrackingCode> {
    public TrackingCode(CharSequence value) {
        super(value);
    }

    public static TrackingCode of(CharSequence value) {
        return new TrackingCode(value);
    }
}
