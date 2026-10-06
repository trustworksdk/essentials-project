package com.example.shop.controller;

import com.example.shop.model.Invoice;
import com.example.shop.service.BillingService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private final BillingService billing;

    public InvoiceController(BillingService billing) { this.billing = billing; }

    @PostMapping("/{invoiceId}/pay")
    public void pay(@PathVariable String invoiceId, @RequestBody PayRequest body) {
        billing.payInvoice(invoiceId, body.cardToken);
    }

    @GetMapping("/unpaid")
    public List<Invoice> unpaid() {
        return billing.listUnpaid();
    }

    public static class PayRequest {
        public String cardToken;
    }
}
