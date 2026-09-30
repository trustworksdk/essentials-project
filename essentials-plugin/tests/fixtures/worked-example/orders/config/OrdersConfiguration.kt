package {{packagePath}}.orders.config

import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.routing.OrderCommand
import {{packagePath}}.orders.types.OrderId
import {{packagePath}}.orders.use_cases.place_order.PlaceOrderDecider
import {{packagePath}}.orders.use_cases.cancel_order.CancelOrderDecider
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Orders bounded context: its `AggregateTypeConfiguration` and each command slice's Decider
 * as its OWN `@Bean`. The application's single `DeciderAndAggregateTypeConfigurator` lives in
 * `{{packagePath}}.DeciderWiring`, never here; it collects these beans from every bounded context and
 * routes commands by aggregate type. Adding a command slice = adding one `@Bean` line — never touching
 * another slice. This explicit wiring is part of each slice's Definition of Done: an unregistered
 * Decider compiles, passes its unit tests, and silently breaks every `@SpringBootTest`.
 */
@Configuration
class OrdersConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("Orders")
    }

    @Bean
    fun orderAggregateTypeConfiguration() = AggregateTypeConfiguration(
        aggregateType = AGGREGATE_TYPE,
        aggregateIdType = OrderId::class.java,
        aggregateIdSerializer = StringValueTypeAggregateIdSerializer(OrderId::class),
        deciderSupportsAggregateTypeChecker =
            DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
        commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
        eventAggregateIdResolver = { e -> (e as OrderEvent).id }
    )

    // One @Bean per command slice's Decider — never a shared god-Decider.
    @Bean fun placeOrderDecider() = PlaceOrderDecider()
    @Bean fun cancelOrderDecider() = CancelOrderDecider()
}
