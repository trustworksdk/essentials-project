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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The order reached a terminal status. Emitted once, together with the event that made it terminal, and carrying what
 * the release automation needs to free the remainder of the hold (buy) or reservation (sell) on the account.
 */
public record OrderClosed(OrderId orderId,
                          AccountId accountId,
                          InstrumentId instrumentId,
                          TradeSide side,
                          OrderStatus finalStatus,
                          Quantity filledQuantity) implements OrderEvent {
    public OrderClosed {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(finalStatus, "No finalStatus provided");
        requireNonNull(filledQuantity, "No filledQuantity provided");
    }
}
