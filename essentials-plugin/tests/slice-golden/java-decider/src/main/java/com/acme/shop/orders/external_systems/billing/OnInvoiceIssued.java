package com.acme.shop.orders.external_systems.billing;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * INBOUND half of the billing ACL (the Inbox pattern).
 *
 * External message in, internal command out. {@code sendAndDontWait} puts the command on the
 * durable command bus, so delivery survives a restart and is retried automatically — the handler
 * must therefore be idempotent on the receiving side.
 *
 * This is not a slice API in the §R2 sense: it is the external system's ingress, not a public
 * endpoint of the bounded context. It still holds exactly one mapping.
 *
 * The handler is named for the external event, not {@code on}: springdoc uses the method name as the
 * operationId, so each translation slice's ingress keeps a unique, stable name in the generated client.
 *
 * Delete this file if the slice is {@code direction: outbound}.
 */
@RestController
public class OnInvoiceIssued {

    private final CommandBus commandBus;
    // The translator is pure (no Spring), so it is constructed here rather than injected.
    private final BillingTranslator translator = new BillingTranslator();

    public OnInvoiceIssued(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @PostMapping("/webhooks/billing")
    public void onInvoiceIssued(@RequestBody BillingTranslator.InvoiceIssuedPayload payload) {
        commandBus.sendAndDontWait(translator.toCommand(payload));
    }
}
