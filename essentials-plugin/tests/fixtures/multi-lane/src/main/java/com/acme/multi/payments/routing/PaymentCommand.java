package com.acme.multi.payments.routing;

import com.acme.multi.payments.types.PaymentId;

/** Routing marker for the Payments aggregate type. */
public interface PaymentCommand {
    PaymentId id();
}
