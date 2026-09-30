package com.acme.multi.payments.use_cases.request_payment;

import com.acme.multi.payments.routing.PaymentCommand;
import com.acme.multi.payments.types.PaymentId;

public record RequestPayment(PaymentId id, long amountMinor) implements PaymentCommand {
}
