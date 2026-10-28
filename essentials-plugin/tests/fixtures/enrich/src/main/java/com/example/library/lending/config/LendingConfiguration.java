package com.example.library.lending.config;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LendingConfiguration {
    public static final AggregateType COPIES = AggregateType.of("Copies");
}
