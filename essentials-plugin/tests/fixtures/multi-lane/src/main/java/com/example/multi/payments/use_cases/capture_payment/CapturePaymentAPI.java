package com.example.multi.payments.use_cases.capture_payment;

import com.example.multi.payments.types.PaymentId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class CapturePaymentAPI {
    private final CommandBus commandBus;

    public CapturePaymentAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    @PostMapping("/{paymentId}/capture")
    public void capturePayment(@PathVariable String paymentId) {
        commandBus.send(new CapturePayment(PaymentId.of(paymentId)));
    }
}
