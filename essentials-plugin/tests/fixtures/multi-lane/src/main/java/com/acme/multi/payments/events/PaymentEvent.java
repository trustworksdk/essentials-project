package com.acme.multi.payments.events;

import com.acme.multi.payments.types.PaymentId;

public sealed interface PaymentEvent permits PaymentRequested, PaymentCaptured {
    PaymentId id();
}
