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

package dk.trustworks.essentials.examples.trading.orders.use_cases.record_expiry;

import dk.trustworks.essentials.examples.trading.orders.types.OrderId;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange expired what was left of a {@code DAY} order at the end of its business day (SR-17).
 *
 * @param businessDate the day that ended, as the exchange names it (SR-32)
 */
public record RecordExpiry(OrderId orderId, LocalDate businessDate) {
    public RecordExpiry {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(businessDate, "No businessDate provided");
    }
}
