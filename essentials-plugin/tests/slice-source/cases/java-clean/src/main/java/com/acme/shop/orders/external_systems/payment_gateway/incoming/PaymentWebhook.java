package com.acme.shop.orders.external_systems.payment_gateway.incoming;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RULE (package): every file of this slice sits in a sub-package; the slice's package is the directory's.
 * TRAP (6): the webhook is the payment gateway's ingress, not an API of this bounded context — no endpoint.
 * TRAP (11(b)): `consumes` names the external message; there is no internal event to hold a handler to.
 */
@RestController
public class PaymentWebhook {
    private final CommandBus commandBus;

    public PaymentWebhook(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public record GatewayPaymentReceived(String reference) {
    }

    @PostMapping("/webhooks/payment-gateway")
    public void on(@RequestBody GatewayPaymentReceived payload) {
    }
}
