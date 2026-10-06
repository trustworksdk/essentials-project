package com.example.billing.views.invoice_list;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/billing/invoices")
public class InvoiceListAPI {

    @GetMapping
    public List<InvoiceListView> list() {
        return List.of();
    }

    /**
     * FINDING (6 discriminator — the per-handler trap): `status` is bound, but in a handler on ANOTHER route.
     * A per-slice check ("status is bound somewhere in this slice") passes this; the manifest's
     * `/api/billing/invoices?status=` has no handler. Also FINDING (6 undeclared mapping) for this route.
     */
    @GetMapping(value = "/by-status", params = "status")
    public List<InvoiceListView> byStatus(@RequestParam String status) {
        return List.of();
    }

    /** FINDING (6 discriminator): the manifest pins `?paid=true`; the code only requires presence. */
    @GetMapping(params = "paid")
    public List<InvoiceListView> paid() {
        return List.of();
    }

    /** FINDING (6 discriminator): the manifest declares `?min=` only; this mapping also requires `max`. */
    @GetMapping(params = {"min", "max"})
    public List<InvoiceListView> byAmount(@RequestParam long min, @RequestParam long max) {
        return List.of();
    }
}
