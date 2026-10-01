package com.example.shop.repository;

import com.example.shop.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByState(String state);
    List<Invoice> findByOrderId(String orderId);
}
