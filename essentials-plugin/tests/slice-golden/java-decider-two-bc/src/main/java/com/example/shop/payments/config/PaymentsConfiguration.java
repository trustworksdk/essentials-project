package com.example.shop.payments.config;

import com.example.shop.payments.events.PaymentEvent;
import com.example.shop.payments.routing.PaymentCommand;
import com.example.shop.payments.types.PaymentId;
import com.example.shop.payments.use_cases.request_payment.RequestPaymentDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Payments bounded context.
 *
 * Each command slice's Decider is registered as its OWN {@code @Bean}, next to this BC's
 * {@code EventStreamAggregateTypeConfiguration}. The application's single
 * {@code EventStreamDeciderAndAggregateTypeConfigurator} — in {@code com.example.shop.DeciderWiring},
 * never here — collects both from every bounded context and routes commands by aggregate type. A
 * configurator per BC registers every decider once per BC, and the first command fails with
 * {@code MultipleCommandHandlersFoundException}. Adding a command slice is adding one {@code @Bean}
 * method — never touching another slice's files.
 *
 * This wiring is part of every slice's definition of done: an unregistered Decider compiles, passes
 * every unit test, and silently breaks every {@code @SpringBootTest}.
 *
 * NOTE: Java uses the {@code EventStream*} family; Kotlin uses
 * {@code kotlin.eventsourcing.AggregateTypeConfiguration} + {@code DeciderAndAggregateTypeConfigurator}.
 * They are different APIs — do not mix them.
 */
@Configuration
public class PaymentsConfiguration {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Payments");

    @Bean
    public EventStreamAggregateTypeConfiguration paymentAggregateTypeConfiguration() {
        return new EventStreamAggregateTypeConfiguration(
                AGGREGATE_TYPE,
                PaymentId.class,
                AggregateIdSerializer.serializerFor(PaymentId.class),
                new HandlesCommandsThatInheritFromCommandType(PaymentCommand.class),
                cmd -> ((PaymentCommand) cmd).id(),
                event -> ((PaymentEvent) event).id()
        );
    }

    // One @Bean per command slice's Decider — never a shared god-Decider.
    // /essentials:add-slice appends a method here for each new command slice.

    @Bean
    public RequestPaymentDecider requestPaymentDecider() {
        return new RequestPaymentDecider();
    }
}
