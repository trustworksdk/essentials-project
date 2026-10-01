package com.example.multi;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Application-level decider wiring: ONE configurator for the whole application. It collects every
 * bounded context's EventStreamAggregateTypeConfiguration and EventStreamDecider beans, so each BC's
 * config/ contributes only those. A configurator per BC would register every decider once per BC.
 */
@Configuration
public class DeciderWiring {

    @Bean
    public EventStreamDeciderAndAggregateTypeConfigurator eventStreamDeciderAndAggregateTypeConfigurator(
            ConfigurableEventStore<?> eventStore,
            CommandBus commandBus,
            List<EventStreamAggregateTypeConfiguration> configs,
            List<EventStreamDecider<?, ?>> deciders) {
        return new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, configs, deciders);
    }
}
