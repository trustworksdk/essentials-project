package com.example.multi.payments.use_cases.request_payment;

import com.example.multi.payments.events.PaymentEvent;
import com.example.multi.payments.events.PaymentRequested;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

public class RequestPaymentDecider implements EventStreamDecider<RequestPayment, PaymentEvent> {

    @Override
    public Optional<PaymentEvent> handle(RequestPayment cmd, List<PaymentEvent> events) {
        if (events.stream().anyMatch(e -> e instanceof PaymentRequested)) {
            return Optional.empty();
        }
        if (cmd.amountMinor() <= 0) {
            throw new IllegalArgumentException("A payment amount must be positive");
        }
        return Optional.of(new PaymentRequested(cmd.id(), cmd.amountMinor()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return RequestPayment.class == command;
    }
}
