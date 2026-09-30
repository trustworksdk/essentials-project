package com.acme.multi.payments.use_cases.capture_payment;

import com.acme.multi.payments.events.PaymentCaptured;
import com.acme.multi.payments.events.PaymentEvent;
import com.acme.multi.payments.events.PaymentRequested;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

public class CapturePaymentDecider implements EventStreamDecider<CapturePayment, PaymentEvent> {

    @Override
    public Optional<PaymentEvent> handle(CapturePayment cmd, List<PaymentEvent> events) {
        if (events.stream().noneMatch(e -> e instanceof PaymentRequested)) {
            throw new IllegalStateException("No payment was requested");
        }
        if (events.stream().anyMatch(e -> e instanceof PaymentCaptured)) {
            return Optional.empty();
        }
        return Optional.of(new PaymentCaptured(cmd.id()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return CapturePayment.class == command;
    }
}
