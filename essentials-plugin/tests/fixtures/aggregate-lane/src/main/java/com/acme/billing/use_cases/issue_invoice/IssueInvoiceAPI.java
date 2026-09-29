package com.acme.billing.use_cases.issue_invoice;

import com.acme.billing.types.InvoiceId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
public class IssueInvoiceAPI {
    private final CommandBus commandBus;

    public IssueInvoiceAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    public record IssueInvoiceRequest(long amountMinor) {}
    public record IssueInvoiceResponse(String invoiceId) {}

    @PostMapping
    public IssueInvoiceResponse issueInvoice(@RequestBody IssueInvoiceRequest body) {
        var id = InvoiceId.random();
        commandBus.send(new IssueInvoice(id, body.amountMinor()));
        return new IssueInvoiceResponse(id.value());
    }
}
