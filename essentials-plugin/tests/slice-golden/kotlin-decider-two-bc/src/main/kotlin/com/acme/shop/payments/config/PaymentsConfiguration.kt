package com.acme.shop.payments.config

import com.acme.shop.payments.events.PaymentEvent
import com.acme.shop.payments.routing.PaymentCommand
import com.acme.shop.payments.types.PaymentId
import com.acme.shop.payments.use_cases.request_payment.RequestPaymentDecider
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Payments bounded context.
 *
 * Each command slice's Decider is registered as its OWN `@Bean`, next to this BC's
 * `AggregateTypeConfiguration`. The application's single `DeciderAndAggregateTypeConfigurator` — in
 * `com.acme.shop.DeciderWiring`, never here — collects both from every bounded context and routes
 * commands by aggregate type. A configurator per BC is a duplicate bean (same method name) or, renamed,
 * registers every decider once per BC. Adding a command slice is adding one `@Bean` line — never
 * touching another slice's files.
 *
 * This wiring is part of every slice's definition of done: an unregistered Decider compiles, passes
 * every unit test, and silently breaks every `@SpringBootTest`.
 */
@Configuration
class PaymentsConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE: AggregateType = AggregateType.of("Payments")
    }

    @Bean
    fun paymentAggregateTypeConfiguration() = AggregateTypeConfiguration(
        aggregateType = AGGREGATE_TYPE,
        aggregateIdType = PaymentId::class.java,
        // A Kotlin StringValueType id needs this serializer; AggregateIdSerializer.serializerFor(...) only knows
        // Java CharSequenceType / String / UUID and throws at context start.
        aggregateIdSerializer = StringValueTypeAggregateIdSerializer(PaymentId::class),
        deciderSupportsAggregateTypeChecker =
            DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(PaymentCommand::class),
        commandAggregateIdResolver = { cmd -> (cmd as PaymentCommand).id },
        eventAggregateIdResolver = { e -> (e as PaymentEvent).id }
    )

    // One @Bean per command slice's Decider — never a shared god-Decider.
    // /essentials:add-slice appends a line here for each new command slice.

    @Bean
    fun requestPaymentDecider() = RequestPaymentDecider()
}
