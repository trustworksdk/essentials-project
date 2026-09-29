package com.acme.shop.model;

import jakarta.persistence.*;

@Entity
@Table(name = "order_lines")
public class OrderLine {
    @Id private String id;
    @ManyToOne private Order order;
    private String productId;
    private int quantity;

    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public void setOrder(Order order) { this.order = order; }
}
