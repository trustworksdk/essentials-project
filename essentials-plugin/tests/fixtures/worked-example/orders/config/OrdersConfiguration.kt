package {{packagePath}}.orders.config

import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.routing.OrderCommand
import {{packagePath}}.orders.types.OrderId
import {{packagePath}}.orders.use_cases.place_order.PlaceOrderDecider
import {{packagePath}}.orders.use_cases.cancel_order.CancelOrderDecider
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Orders bounded context. Each command slice's Decider is registered as its
 * OWN `@Bean`; the `DeciderAndAggregateTypeConfigurator` collects them via `List<Decider<*,*>>`
 * and routes commands by aggregate type. Adding a command slice = adding one `@Bean` line —
 * never touching another slice. This explicit wiring is part of each slice's Definition of
 * Done (the slice's integration test must be able to bootstrap) — the CRM lesson was that
 * un-registered Deciders silently broke every `@SpringBootTest`.
 */
@Configuration
class OrdersConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("Orders")
    }

    @Bean
    fun deciderAndAggregateTypeConfigurator(
        eventStore: ConfigurableEventStore<*>,
        commandBus: CommandBus,
        aggregateTypeConfigurations: List<AggregateTypeConfiguration>,
        deciders: List<Decider<*, *>>
    ) = DeciderAndAggregateTypeConfigurator(eventStore, commandBus, aggregateTypeConfigurations, deciders)

    @Bean
    fun orderAggregateTypeConfiguration() = AggregateTypeConfiguration(
        aggregateType = AGGREGATE_TYPE,
        aggregateIdType = OrderId::class.java,
        aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
        deciderSupportsAggregateTypeChecker =
            DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
        commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
        eventAggregateIdResolver = { e -> (e as OrderEvent).id }
    )

    // One @Bean per command slice's Decider — never a shared god-Decider.
    @Bean fun placeOrderDecider() = PlaceOrderDecider()
    @Bean fun cancelOrderDecider() = CancelOrderDecider()
}
