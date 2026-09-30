package com.acme.billing.use_cases.issue_invoice;

import com.acme.billing.types.InvoiceId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/invoices")
public class IssueInvoiceAPI {
    private final CommandBus bus;

    public IssueInvoiceAPI(CommandBus bus) {
        this.bus = bus;
    }

    @PostMapping
    public void issue(@RequestBody IssueInvoice command) {
        bus.sendAndDontWait(command);
    }

    /** FINDING (6 command mappings, Blocking): a second endpoint on a command slice is a second command. */
    @PutMapping("/{invoiceId}")
    public void reissue(@RequestBody IssueInvoice command) {
        bus.sendAndDontWait(command);
    }
}
