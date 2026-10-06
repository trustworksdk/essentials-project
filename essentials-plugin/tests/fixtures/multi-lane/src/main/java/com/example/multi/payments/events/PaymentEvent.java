package com.example.multi.payments.events;

import com.example.multi.payments.types.PaymentId;

public sealed interface PaymentEvent permits PaymentRequested, PaymentCaptured {
    PaymentId id();
}
