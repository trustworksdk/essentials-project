package com.acme.shop.service;

import com.acme.shop.model.*;
import com.acme.shop.repository.*;
import com.acme.shop.integration.PaymentGatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service
public class BillingService {
    private final InvoiceRepository invoices;
    private final OrderRepository orders;
    private final PaymentGatewayClient gateway;

    public BillingService(InvoiceRepository invoices, OrderRepository orders, PaymentGatewayClient gateway) {
        this.invoices = invoices;
        this.orders = orders;
        this.gateway = gateway;
    }

    @Transactional
    public void payInvoice(String invoiceId, String cardToken) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow();
        if (!"UNPAID".equals(invoice.getState())) {
            throw new IllegalStateException("Invoice already settled");
        }
        gateway.charge(invoice.getId(), invoice.getAmount(), cardToken);
        invoice.setState("PAID");
        invoices.save(invoice);

        Order order = orders.findById(invoice.getOrderId()).orElseThrow();
        order.setStatus("PAID");
        orders.save(order);
    }

    @Transactional
    public String issueInvoice(String orderId, BigDecimal amount) {
        Invoice invoice = new Invoice();
        invoice.setState("UNPAID");
        invoices.save(invoice);
        return invoice.getId();
    }

    public List<Invoice> listUnpaid() { return invoices.findByState("UNPAID"); }
}
