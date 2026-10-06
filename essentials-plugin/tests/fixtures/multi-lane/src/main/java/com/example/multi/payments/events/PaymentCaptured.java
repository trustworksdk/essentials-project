package com.example.multi.payments.events;

import com.example.multi.payments.types.PaymentId;

public record PaymentCaptured(PaymentId id) implements PaymentEvent {
}
