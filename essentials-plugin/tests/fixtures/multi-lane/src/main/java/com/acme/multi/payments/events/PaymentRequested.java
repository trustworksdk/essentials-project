package com.acme.multi.payments.events;

import com.acme.multi.payments.types.PaymentId;

public record PaymentRequested(PaymentId id, long amountMinor) implements PaymentEvent {
}
