package com.example.billing.events;

public record PaymentRefunded(String paymentId, long amount) {
}
