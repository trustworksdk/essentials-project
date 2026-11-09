package com.example.mall.stock.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public class Stock extends AggregateRoot<String, Object, Stock> {
    public static final AggregateType STOCK = AggregateType.of("Stock");

    public void reserve(int units) {
    }
}
