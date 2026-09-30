package com.acme.billing.use_cases.pay_invoice;

import com.acme.billing.aggregates.Invoices;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

@Component
public class PayInvoiceHandler extends AnnotatedCommandHandler {
    private final Invoices invoices;

    public PayInvoiceHandler(Invoices invoices) { this.invoices = invoices; }

    @CmdHandler
    public void handle(PayInvoice cmd) {
        var invoice = invoices.getInvoice(cmd.id());

        if (invoice.isPaid()) {
            throw new IllegalStateException("Invoice already paid");
        }
        invoice.pay(cmd.paidMinor());
    }
}
