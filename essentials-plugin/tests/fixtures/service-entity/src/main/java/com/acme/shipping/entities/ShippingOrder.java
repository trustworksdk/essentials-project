package com.acme.shipping.entities;

import com.acme.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The BC's consistency boundary. Service-entity lane: state lives in this row.
 */
@Entity
@Access(AccessType.FIELD)
public class ShippingOrder {

    @Id
    private String orderId;
    private String destination;
    private boolean shipped;

    /** FINDING (gate 8d): a BC-scoped entity naming a slice-private command type. */
    public ShippingOrder(RegisterShippingOrder cmd) {
        this.orderId = cmd.orderId().value();
        this.destination = cmd.destination();
        this.shipped = false;
    }

    /** TRAP: persistence-mandated no-arg constructor. Not a finding. */
    protected ShippingOrder() {
    }

    /** The one real invariant: shipping twice is a no-op, not an error. */
    public boolean markOrderAsShipped() {
        if (shipped) {
            return false;
        }
        shipped = true;
        return true;
    }

    /** FINDING (gate 16): bypasses markOrderAsShipped()'s guard entirely. */
    public void setShipped(boolean shipped) {
        this.shipped = shipped;
    }

    /** TRAP: read by the ORM and toString() only. Not a query surface. */
    public String getOrderId() {
        return orderId;
    }

    /** TRAP: read by the ORM and toString() only. Not a query surface. */
    public String getDestination() {
        return destination;
    }

    @Override
    public String toString() {
        return "ShippingOrder[" + getOrderId() + " -> " + getDestination() + "]";
    }
}
