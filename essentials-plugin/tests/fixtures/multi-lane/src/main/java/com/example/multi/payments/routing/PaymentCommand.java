package com.example.multi.payments.routing;

import com.example.multi.payments.types.PaymentId;

/** Routing marker for the Payments aggregate type. */
public interface PaymentCommand {
    PaymentId id();
}
