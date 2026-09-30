package com.acme.shop;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Application-level decider wiring: ONE configurator for the whole application.
 *
 * It collects every bounded context's {@code EventStreamAggregateTypeConfiguration} and every
 * {@code EventStreamDecider} bean, wraps each decider in a command handler and registers it on the
 * {@code CommandBus}. Each BC's {@code config/} therefore contributes only its aggregate-type
 * configuration and its decider beans — never a configurator of its own.
 *
 * EXACTLY ONE PER APPLICATION. A second configurator registers every decider a second time; the
 * adapters it creates are new instances, so the bus does not recognise them as already registered,
 * and the first command sent fails with {@code MultipleCommandHandlersFoundException}. Nothing fails
 * at startup, and a project with a single bounded context never notices.
 *
 * Written once, by the first decider-lane bounded context /essentials:add-slice scaffolds, and never
 * overwritten.
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
