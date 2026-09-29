package com.acme.shop.model;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {
    @Id private String id;
    private String customerId;
    private String status;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderLine> lines;
    private String cancellationReason;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<OrderLine> getLines() { return lines; }
    public void setLines(List<OrderLine> lines) { this.lines = lines; }
    public void setCancellationReason(String r) { this.cancellationReason = r; }
}
