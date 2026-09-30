package com.acme.billing.aggregates;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public class Invoices {
    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Invoices");
}
