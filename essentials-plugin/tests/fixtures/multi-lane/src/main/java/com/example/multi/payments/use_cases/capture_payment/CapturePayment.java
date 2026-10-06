package com.example.multi.payments.use_cases.capture_payment;

import com.example.multi.payments.routing.PaymentCommand;
import com.example.multi.payments.types.PaymentId;

public record CapturePayment(PaymentId id) implements PaymentCommand {
}
