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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.domain.events;

import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.OrderId;

import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public abstract class ShippingEvent {
    public final OrderId orderId;

    protected ShippingEvent(OrderId orderId) {
        this.orderId = requireNonNull(orderId, "No orderId provided");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (ShippingEvent) o;
        return Objects.equals(orderId, that.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), orderId);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(orderId=" + orderId + ")";
    }
}
