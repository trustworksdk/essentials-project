package com.example.ev.payments.events;

// Java: nested records, plus a nested sealed sub-parent that is not itself an event
public sealed interface PaymentEvent permits PaymentEvent.PaymentTaken, PaymentEvent.Refund {
    record PaymentTaken(String id) implements PaymentEvent {}

    sealed interface Refund extends PaymentEvent permits PaymentEvent.PaymentRefunded {}

    record PaymentRefunded(String id) implements Refund {}
}
