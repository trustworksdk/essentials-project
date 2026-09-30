package com.acme.shop.orders.config;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrdersConfiguration {
    /** RULE (subscriptions): a constant assigned from another constant is followed to its literal. */
    public static final AggregateType AGGREGATE_TYPE = OrderStreams.ORDERS;
}
