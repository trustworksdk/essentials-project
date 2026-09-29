package com.acme.shipping.persistence;

import com.acme.shipping.entities.ShippingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The write path's persistence contract. BC-private.
 *
 * <p>FINDING (gate 15c): this file is not in {@code shipping/entities/}, beside the entity it
 * persists. {@code persistence/} is not on gate 2's forbidden-layer list — only gate 15(c) sees
 * this. It is also the reason gate 15 identifies write repositories <em>by type</em> (a mutating
 * Spring Data interface over an entity declared in {@code entities/}) rather than by path: a
 * path-keyed gate 15 would find nothing here and silently pass findings (a) and (b) too.
 *
 * <p>FINDING (gate 18a): it extends {@code JpaRepository} rather than the bare
 * {@code org.springframework.data.repository.Repository} marker, so its surface is everything Spring
 * Data offers — {@code findAll()}, {@code deleteAll()}, {@code count()} — rather than the load/save/
 * delete the law sanctions. Fixing it means extending {@code Repository} and declaring the three
 * methods; note that gate 15 must still identify it afterwards, via its own {@code save}/{@code
 * delete} declarations rather than via the base interface.
 */
public interface ShippingOrders extends JpaRepository<ShippingOrder, String> {

    /**
     * TRAP: a reserved name, but it returns the <em>entity</em> — which is exactly what the base
     * implementation provides and what a write repository wants. Gate 18(c) fires only where the
     * declared return type is a projection. Not a finding.
     */
    Optional<ShippingOrder> findById(String id);

    /**
     * TRAP: a finder used only by the write path — AutoShipProcessor loads a batch in order to
     * mutate it. The repository doing its job, not read-side drift. Not a finding.
     */
    List<ShippingOrder> findByIdIn(List<String> ids);

    /**
     * FINDING (gate 15b): serves OrderStatusAPI's screen. The read side served from the write
     * model — it belongs to the order_status view slice as its own query interface.
     */
    List<ShippingOrder> findByShipped(boolean shipped);
}
