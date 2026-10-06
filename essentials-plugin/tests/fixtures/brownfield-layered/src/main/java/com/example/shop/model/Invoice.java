package com.example.shop.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "invoices")
public class Invoice {
    @Id private String id;
    private String orderId;
    private BigDecimal amount;
    private String state;
    private int remindersSent;

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getRemindersSent() { return remindersSent; }
    public void setRemindersSent(int n) { this.remindersSent = n; }
}
