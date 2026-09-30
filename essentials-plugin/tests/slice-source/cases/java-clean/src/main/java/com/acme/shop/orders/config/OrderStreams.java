package com.acme.shop.orders.config;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public final class OrderStreams {
    public static final AggregateType ORDERS = AggregateType.of("Orders");

    private OrderStreams() {
    }
}
