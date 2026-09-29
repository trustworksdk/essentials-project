package com.acme.shipping.views.order_status;

import com.acme.shipping.entities.ShippingOrder;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * TRAP: this IS a Spring Data repository, and its shape is correct. Slice-private, read-only, narrow,
 * extending the bare {@code Repository} marker, and returning the read shape rather than the entity.
 * Not a gate-2 layer-directory finding, not a gate-15 write-repository finding, and not a gate-18(a)
 * base-interface finding — it is not the BC's write repository and it extends the right marker.
 *
 * <p>It carries exactly one defect, and it is a naming one — see {@code findById} below.
 */
public interface OrderStatusQueries extends Repository<ShippingOrder, String> {

    List<OrderStatusView> findByShipped(boolean shipped);

    /**
     * Backs the {@code params = "status"} mapping — another query over the read model this slice
     * already owns, which R2 allows without forking a second view slice.
     */
    List<OrderStatusView> findByStatus(String status);

    /**
     * TRAP: {@code findByOrderId} is not a reserved name, so it derives a query on the entity's
     * {@code orderId} property and the projection applies. Correct, and must not be reported.
     */
    Optional<OrderStatusView> findByOrderId(String orderId);

    /**
     * FINDING (gate 18c): a projection return type on a method named after a CRUD base method.
     * {@code SimpleJpaRepository} owns {@code findById(ID)} and the match is on name and parameter
     * types — the return type is not part of it — so this is captured by the base rather than
     * derived. It returns a {@link ShippingOrder}, the declared {@link OrderStatusView} is ignored,
     * and the caller gets a {@code ClassCastException}.
     *
     * <p>Note it has <strong>no caller</strong>: the defect is invisible to any check that reasons from call
     * sites, and to any test that never exercises it. Gate 18(c) must find it from the declaration
     * alone. The fix is a rename to {@code findOrderStatusById}, which derives the same query.
     */
    Optional<OrderStatusView> findById(String orderId);
}
