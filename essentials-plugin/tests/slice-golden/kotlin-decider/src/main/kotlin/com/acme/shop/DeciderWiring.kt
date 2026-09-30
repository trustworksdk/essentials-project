package com.acme.shop

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Application-level decider wiring: ONE configurator for the whole application.
 *
 * It collects every bounded context's `AggregateTypeConfiguration` and every `Decider` bean, wraps
 * each decider in a command handler and registers it on the `CommandBus`. Each BC's `config/`
 * therefore contributes only its aggregate-type configuration and its decider beans — never a
 * configurator of its own.
 *
 * EXACTLY ONE PER APPLICATION. A second configurator registers every decider a second time, and the
 * first command sent fails with `MultipleCommandHandlersFoundException`; two configurator `@Bean`
 * methods with the same name fail at startup instead. A project with a single bounded context never
 * notices either.
 *
 * Written once, by the first decider-lane bounded context /essentials:add-slice scaffolds, and never
 * overwritten.
 */
@Configuration
class DeciderWiring {

    @Bean
    fun deciderAndAggregateTypeConfigurator(
        eventStore: ConfigurableEventStore<*>,
        commandBus: CommandBus,
        aggregateTypeConfigurations: List<AggregateTypeConfiguration>,
        deciders: List<Decider<*, *>>
    ) = DeciderAndAggregateTypeConfigurator(eventStore, commandBus, aggregateTypeConfigurations, deciders)
}
