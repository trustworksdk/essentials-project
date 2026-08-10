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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The consistency boundary of the {@code shipping} bounded context on the service-entity write style (§R5): state is
 * this row, mutated in place inside the command's transaction, not a fold over an event stream.
 * <p>
 * <strong>It deliberately exposes no accessors</strong>, and {@link Access}{@code (}{@link AccessType#FIELD}{@code )}
 * makes that structural rather than conventional - JPA reads and writes the fields directly, so nothing has to be
 * public for persistence to work. That is § The entity's own bar. This class used to carry a full set of getters and
 * setters, and the {@code setShipped(boolean)} among them made {@link #markOrderAsShipped()}'s guard trivially
 * bypassable: the defect an ORM pushes you into on this lane. The read side gets its data from
 * {@code views/order_status}, never from here.
 * <p>
 * The {@code @Id} is a plain {@code String} rather than an {@code OrderId}, deliberately - see the bounded context's
 * {@code CLAUDE.md}.
 */
@Entity
@Access(AccessType.FIELD)
public class ShippingOrder {
    @Id
    @Column(name = "order_id")
    private String                     id;
    private boolean                    shipped;
    @Embedded
    private ShippingDestinationAddress destinationAddress;

    /**
     * Required by JPA
     */
    public ShippingOrder() {
    }

    public ShippingOrder(String id,
                         ShippingDestinationAddress destinationAddress) {
        this.id = requireNonNull(id, "No id provided");
        // ShippingDestinationAddress has to be a mutable @Embeddable, and the instance handed in belongs to a
        // command. Storing it by reference would let a long-lived row and a command share state (§R5 red flags)
        this.destinationAddress = ShippingDestinationAddress.copyOf(requireNonNull(destinationAddress,
                                                                                   "No destinationAddress provided"));
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

    /**
     * Identity is the JPA {@link Id} alone — a persisted entity and its in-memory counterpart must compare equal even
     * when the mutable state has diverged.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShippingOrder that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ShippingOrder(id=" + id + ", shipped=" + shipped + ", destinationAddress=" + destinationAddress + ")";
    }
}
