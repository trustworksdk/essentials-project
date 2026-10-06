package com.example.ids.orders.external_systems.payment_gateway;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** TRAP (6 raw id): a translation's webhook carries the gateway's own ids, not this bounded context's. */
@RestController
public class PaymentWebhook {

    @PostMapping("/webhooks/payment-gateway/{externalId}")
    public void onPaymentReceived(@PathVariable String externalId) {
    }
}
