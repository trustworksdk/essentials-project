package com.example.billing.use_cases.pay_invoice;

import com.example.billing.types.InvoiceId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
public class PayInvoiceAPI {
    private final CommandBus commandBus;

    public PayInvoiceAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    public record PayInvoiceRequest(long paidMinor) {}

    @PostMapping("/{invoiceId}/payment")
    public void payInvoice(@PathVariable String invoiceId, @RequestBody PayInvoiceRequest body) {
        commandBus.send(new PayInvoice(InvoiceId.of(invoiceId), body.paidMinor()));
    }
}
