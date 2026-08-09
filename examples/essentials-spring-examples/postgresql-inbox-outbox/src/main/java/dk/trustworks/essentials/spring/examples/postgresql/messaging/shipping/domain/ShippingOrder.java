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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.domain;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.commands.RegisterShippingOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;

@Entity
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

    public ShippingOrder(String id, boolean shipped, ShippingDestinationAddress destinationAddress) {
        this.id = id;
        this.shipped = shipped;
        this.destinationAddress = destinationAddress;
    }

    public ShippingOrder(RegisterShippingOrder cmd) {
        this.id = cmd.orderId().toString();
        this.destinationAddress = cmd.destinationAddress();
    }

    public boolean markOrderAsShipped() {
        // Idempotency check
        if (!shipped) {
            shipped = true;
            return true;
        }
        return false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isShipped() {
        return shipped;
    }

    public void setShipped(boolean shipped) {
        this.shipped = shipped;
    }

    public ShippingDestinationAddress getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(ShippingDestinationAddress destinationAddress) {
        this.destinationAddress = destinationAddress;
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
