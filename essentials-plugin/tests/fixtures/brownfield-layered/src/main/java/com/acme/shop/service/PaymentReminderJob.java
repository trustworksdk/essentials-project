package com.acme.shop.service;

import com.acme.shop.model.Invoice;
import com.acme.shop.repository.InvoiceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Automation candidate: schedule-triggered, writes, no external API.
@Component
public class PaymentReminderJob {
    private final InvoiceRepository invoices;

    public PaymentReminderJob(InvoiceRepository invoices) { this.invoices = invoices; }

    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void sendReminders() {
        for (Invoice invoice : invoices.findByState("UNPAID")) {
            invoice.setRemindersSent(invoice.getRemindersSent() + 1);
            invoices.save(invoice);
        }
    }
}
