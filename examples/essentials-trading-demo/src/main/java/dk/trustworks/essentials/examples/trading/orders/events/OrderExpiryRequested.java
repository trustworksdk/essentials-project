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
package dk.trustworks.essentials.examples.trading.orders.events;

import dk.trustworks.essentials.examples.trading.orders.types.OrderId;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The business day of a {@code DAY} order closed with the order still open, and the brokerage asks the exchange to
 * cancel it. A stopgap until the exchange expires its own {@code DAY} orders (SR-17, design §2): the order is
 * {@link OrderExpired} once the exchange confirms the cancellation.
 */
public record OrderExpiryRequested(OrderId orderId, LocalDate businessDate) implements OrderEvent {
    public OrderExpiryRequested {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(businessDate, "No businessDate provided");
    }
}
