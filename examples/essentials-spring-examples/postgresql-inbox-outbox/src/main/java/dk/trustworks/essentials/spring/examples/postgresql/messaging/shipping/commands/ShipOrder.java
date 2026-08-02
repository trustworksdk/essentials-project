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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.commands;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.OrderId;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class ShipOrder {
    public OrderId orderId;

    public ShipOrder() {
    }

    public ShipOrder(OrderId orderId) {
        this.orderId = requireNonNull(orderId, "No orderId provided");
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public void setOrderId(OrderId orderId) {
        this.orderId = requireNonNull(orderId, "No orderId provided");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShipOrder that)) return false;
        return Objects.equals(orderId, that.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId);
    }

    @Override
    public String toString() {
        return "ShipOrder(orderId=" + orderId + ")";
    }
}
