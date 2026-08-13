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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.entities;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.ShippingDestinationAddress;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The consistency boundary of the {@code shipping} bounded context on the service-entity write style (§R5): state is
 * this document, mutated in place inside the command's transaction, not a fold over an event stream.
 * <p>
 * <strong>It deliberately exposes no accessors.</strong> Spring Data maps the fields directly, so nothing needs to be
 * public for persistence to work, and the one public method carries an invariant. That is § The entity's own bar: a
 * public {@code setShipped(boolean)} would make {@link #markOrderAsShipped()}'s guard bypassable, which is the defect
 * an ORM pushes you into on this lane. The read side gets its data from {@code views/order_status}, never from here.
 */
@Document
public class ShippingOrder {
    @Id
    private OrderId                    id;
    private ShippingDestinationAddress destinationAddress;
    private boolean                    shipped;

    /**
     * Required by Spring Data
     */
    public ShippingOrder() {
    }

    public ShippingOrder(OrderId id,
                         ShippingDestinationAddress destinationAddress) {
        this.id = requireNonNull(id, "No id provided");
        this.destinationAddress = requireNonNull(destinationAddress, "No destinationAddress provided");
    }

    /**
     * @return returns true if the order was marked as shipped, otherwise it returns false
     */
    public boolean markOrderAsShipped() {
        // Idempotency check
        if (!shipped) {
            shipped = true;
            return true;
        }
        return false;
    }
}
