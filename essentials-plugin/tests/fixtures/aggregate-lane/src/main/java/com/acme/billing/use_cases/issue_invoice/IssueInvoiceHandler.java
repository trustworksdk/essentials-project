package com.acme.billing.use_cases.issue_invoice;

import com.acme.billing.aggregates.Invoice;
import com.acme.billing.aggregates.Invoices;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

/**
 * CLEAN. Creation slice: the existence check is idempotency, not a domain rule, so it belongs here.
 */
@Component
public class IssueInvoiceHandler extends AnnotatedCommandHandler {
    private final Invoices invoices;

    public IssueInvoiceHandler(Invoices invoices) { this.invoices = invoices; }

    @CmdHandler
    public void handle(IssueInvoice cmd) {
        if (invoices.isInvoiceMissing(cmd.id())) {
            invoices.saveNew(new Invoice(cmd.id(), cmd.amountMinor()));
        }
    }
}
