package com.acme.shipping.views.order_status;

import com.acme.shipping.entities.ShippingOrder;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** Read-only queries behind the order-status API. */
public interface OrderStatusQueries extends Repository<ShippingOrder, String> {

    List<OrderStatusView> findByShipped(boolean shipped);

    List<OrderStatusView> findByStatus(String status);

    Optional<OrderStatusView> findByOrderId(String orderId);

    Optional<OrderStatusView> findById(String orderId);
}
