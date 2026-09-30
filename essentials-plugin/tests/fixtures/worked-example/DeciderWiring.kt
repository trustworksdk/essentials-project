package {{packagePath}}

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Application-level decider wiring: ONE configurator for the whole application. It collects every
 * bounded context's `AggregateTypeConfiguration` and `Decider` beans, so `orders/config/` contributes
 * only those. A second configurator would register every decider twice.
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
