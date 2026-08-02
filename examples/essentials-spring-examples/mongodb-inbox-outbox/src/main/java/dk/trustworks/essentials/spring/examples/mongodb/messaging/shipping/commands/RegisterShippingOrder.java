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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.commands;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.OrderId;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.domain.ShippingDestinationAddress;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class RegisterShippingOrder {
    public final OrderId                    orderId;
    public final ShippingDestinationAddress destinationAddress;

    public RegisterShippingOrder(OrderId orderId, ShippingDestinationAddress destinationAddress) {
        this.orderId = requireNonNull(orderId, "No orderId provided");
        this.destinationAddress = requireNonNull(destinationAddress, "No destinationAddress provided");
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public ShippingDestinationAddress getDestinationAddress() {
        return destinationAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegisterShippingOrder that)) return false;
        return Objects.equals(orderId, that.orderId) && Objects.equals(destinationAddress, that.destinationAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, destinationAddress);
    }

    @Override
    public String toString() {
        return "RegisterShippingOrder(orderId=" + orderId + ", destinationAddress=" + destinationAddress + ")";
    }
}
