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
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The brokerage accepted an order and its hold (buy) or reservation (sell) is in place on the account, in the same
 * unit of work. {@code holdAmount} is null for a sell.
 */
public record OrderPlaced(OrderId orderId,
                          AccountId accountId,
                          InstrumentId instrumentId,
                          TradeSide side,
                          OrderType type,
                          Quantity quantity,
                          Money limitPrice,
                          TimeInForce timeInForce,
                          Money holdAmount) implements OrderEvent {
    public OrderPlaced {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(type, "No type provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(timeInForce, "No timeInForce provided");
    }
}
