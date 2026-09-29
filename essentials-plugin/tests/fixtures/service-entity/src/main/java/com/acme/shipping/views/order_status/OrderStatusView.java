package com.acme.shipping.views.order_status;

/**
 * The read shape: a closed Spring Data interface projection. It IS the response body — no
 * ...Response mirror, no mapper, so R2's no-adapter rule holds.
 */
public interface OrderStatusView {
    String getOrderId();

    boolean isShipped();
}
