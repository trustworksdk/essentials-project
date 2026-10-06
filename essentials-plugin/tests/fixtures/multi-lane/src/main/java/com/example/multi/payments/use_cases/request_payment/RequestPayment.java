package com.example.multi.payments.use_cases.request_payment;

import com.example.multi.payments.routing.PaymentCommand;
import com.example.multi.payments.types.PaymentId;

public record RequestPayment(PaymentId id, long amountMinor) implements PaymentCommand {
}
