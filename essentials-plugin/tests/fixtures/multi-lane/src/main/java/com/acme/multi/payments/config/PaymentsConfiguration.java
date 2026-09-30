package com.acme.multi.payments.config;

import com.acme.multi.payments.events.PaymentEvent;
import com.acme.multi.payments.routing.PaymentCommand;
import com.acme.multi.payments.types.PaymentId;
import com.acme.multi.payments.use_cases.capture_payment.CapturePaymentDecider;
import com.acme.multi.payments.use_cases.request_payment.RequestPaymentDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                event -> ((PaymentEvent) event).id());
    }

    @Bean
    public RequestPaymentDecider requestPaymentDecider() {
        return new RequestPaymentDecider();
    }

    @Bean
    public CapturePaymentDecider capturePaymentDecider() {
        return new CapturePaymentDecider();
    }
}
