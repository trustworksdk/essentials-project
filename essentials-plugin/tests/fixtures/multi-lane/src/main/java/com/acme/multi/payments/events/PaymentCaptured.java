package com.acme.multi.payments.events;

import com.acme.multi.payments.types.PaymentId;

public record PaymentCaptured(PaymentId id) implements PaymentEvent {
}
