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

package dk.trustworks.essentials.examples.trading.orders.use_cases.cancel_order;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Ask for an open order to be cancelled. The order is cancelled when the exchange confirms it; a fill that arrives in
 * between is still booked.
 */
public record CancelOrder(AccountId accountId, OrderId orderId) {
    public CancelOrder {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(orderId, "No orderId provided");
    }
}
