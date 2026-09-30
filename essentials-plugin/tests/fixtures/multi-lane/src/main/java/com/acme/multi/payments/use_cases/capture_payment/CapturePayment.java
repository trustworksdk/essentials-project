package com.acme.multi.payments.use_cases.capture_payment;

import com.acme.multi.payments.routing.PaymentCommand;
import com.acme.multi.payments.types.PaymentId;

public record CapturePayment(PaymentId id) implements PaymentCommand {
}
