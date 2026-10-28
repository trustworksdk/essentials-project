package com.example.billing.use_cases.refund_payment;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class RefundPaymentAPI {

    @PostMapping("/{id}/refund")
    public Object refund(@PathVariable String id) {
        return null;
    }
}
