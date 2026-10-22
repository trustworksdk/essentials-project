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
 * A {@code DAY} order's remainder was taken off the market at the end of its business day: the exchange expired it
 * itself (SR-17), or, with the stub exchange, confirmed the cancellation that {@link OrderExpiryRequested} asked for.
 *
 * @param businessDate the business day that ended; {@code null} when the exchange expired the order and did not say
 *                     which day (until SR-32), never derived from a clock
 */
public record OrderExpired(OrderId orderId, LocalDate businessDate) implements OrderEvent {
    public OrderExpired {
        requireNonNull(orderId, "No orderId provided");
    }
}
