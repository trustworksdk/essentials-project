package com.example.shop.orders.config

import com.example.shop.orders.events.OrderEvent
import com.example.shop.orders.routing.OrderCommand
import com.example.shop.orders.types.OrderId
import com.example.shop.orders.use_cases.place_order.PlaceOrderDecider
import com.example.shop.orders.use_cases.cancel_order.CancelOrderDecider
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Orders bounded context.
 *
 * Each command slice's Decider is registered as its OWN `@Bean`, next to this BC's
 * `AggregateTypeConfiguration`. The application's single `DeciderAndAggregateTypeConfigurator` — in
 * `com.example.shop.DeciderWiring`, never here — collects both from every bounded context and routes
 * commands by aggregate type. A configurator per BC is a duplicate bean (same method name) or, renamed,
 * registers every decider once per BC. Adding a command slice is adding one `@Bean` line — never
 * touching another slice's files.
 *
 * This wiring is part of every slice's definition of done: an unregistered Decider compiles, passes
 * every unit test, and silently breaks every `@SpringBootTest`.
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
        // A Kotlin StringValueType id needs this serializer; AggregateIdSerializer.serializerFor(...) only knows
        // Java CharSequenceType / String / UUID and throws at context start.
        aggregateIdSerializer = StringValueTypeAggregateIdSerializer(OrderId::class),
        deciderSupportsAggregateTypeChecker =
            DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
        commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
        eventAggregateIdResolver = { e -> (e as OrderEvent).id }
    )

    // One @Bean per command slice's Decider — never a shared god-Decider.
    // /essentials:add-slice appends a line here for each new command slice.

    @Bean
    fun placeOrderDecider() = PlaceOrderDecider()

    @Bean
    fun cancelOrderDecider() = CancelOrderDecider()
}
