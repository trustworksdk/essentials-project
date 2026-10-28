package com.example.billing.events;

public record PaymentCharged(String paymentId, String orderId, long amount) {
}
