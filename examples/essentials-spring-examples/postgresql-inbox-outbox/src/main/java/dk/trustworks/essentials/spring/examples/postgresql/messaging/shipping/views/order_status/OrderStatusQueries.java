/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.views.order_status;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities.ShippingOrder;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The query surface of the {@code shipping.order_status} slice, owned by this slice and used by no other.
 * <p>
 * On the service-entity write style there is only <em>one</em> collection, shared by the write side and every view, so
 * §R4's ownership rule is restated rather than dropped: a view slice may read the entity's collection, but
 * <strong>never through the write repository</strong> (§ The read side on this lane). Hence this separate, read-only,
 * narrow interface - no {@code save}, no {@code delete} - returning {@link OrderStatusView} rather than
 * {@link ShippingOrder}.
 * <p>
 * The mirror-image rule holds too: {@code findByShipped} belongs here and must never be added to
 * {@code entities/ShippingOrders}, which is what "the read side served from the write model" looks like in practice.
 * <p>
 * <strong>Do not name a lookup here {@code findById}.</strong> Spring Data matches that signature to the CRUD base
 * implementation rather than deriving a query, so it returns the {@link ShippingOrder} entity and the declared
 * projection type is ignored - which surfaces as a {@code ClassCastException} at the call site, not as a wiring
 * error. {@code findOrderStatusById} derives the same {@code id = ?} query and does project.
 */
public interface OrderStatusQueries extends Repository<ShippingOrder, String> {
    List<OrderStatusView> findAllBy();

    Optional<OrderStatusView> findOrderStatusById(String orderId);

    List<OrderStatusView> findByShipped(boolean shipped);
}
