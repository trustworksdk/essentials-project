package com.example.shipping.entities;

import com.example.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
@Access(AccessType.FIELD)
public class ShippingOrder {

    @Id
    private String orderId;
    private String destination;
    private boolean shipped;

    public ShippingOrder(RegisterShippingOrder cmd) {
        this.orderId = cmd.orderId().value();
        this.destination = cmd.destination();
        this.shipped = false;
    }

    protected ShippingOrder() {
    }

    /** Shipping twice is a no-op, not an error. */
    public boolean markOrderAsShipped() {
        if (shipped) {
            return false;
        }
        shipped = true;
        return true;
    }

    public void setShipped(boolean shipped) {
        this.shipped = shipped;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getDestination() {
        return destination;
    }

    @Override
    public String toString() {
        return "ShippingOrder[" + getOrderId() + " -> " + getDestination() + "]";
    }
}
