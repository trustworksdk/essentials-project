package com.example.shipping.persistence;

import com.example.shipping.entities.ShippingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** The write path's persistence contract. BC-private. */
public interface ShippingOrders extends JpaRepository<ShippingOrder, String> {

    Optional<ShippingOrder> findById(String id);

    List<ShippingOrder> findByIdIn(List<String> ids);

    List<ShippingOrder> findByShipped(boolean shipped);
}
