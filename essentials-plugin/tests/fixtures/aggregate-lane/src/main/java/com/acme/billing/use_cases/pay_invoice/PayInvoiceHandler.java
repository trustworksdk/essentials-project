package com.acme.billing.use_cases.pay_invoice;

import com.acme.billing.aggregates.Invoices;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

/**
 * FINDING (deliberate). The decision has leaked out of the aggregate: this handler decides whether
 * the invoice may be paid, so Invoice.pay() is no longer the consistency boundary's guard and can be
 * bypassed by any other caller. Expected: flagged against
 * rules/slice-design.md § The aggregate's own bar.
 */
@Component
public class PayInvoiceHandler extends AnnotatedCommandHandler {
    private final Invoices invoices;

    public PayInvoiceHandler(Invoices invoices) { this.invoices = invoices; }

    @CmdHandler
    public void handle(PayInvoice cmd) {
        var invoice = invoices.getInvoice(cmd.id());

        if (invoice.isPaid()) {              // <-- domain rule in the handler
            throw new IllegalStateException("Invoice already paid");
        }
        invoice.pay(cmd.paidMinor());
    }
}
