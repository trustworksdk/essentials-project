package com.example.billing.views.payment_list;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentListAPI {

    @GetMapping
    public Object list() {
        return null;
    }
}
