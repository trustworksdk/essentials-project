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
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One execution of the order. Carries everything the account's booking needs ({@code TradeBooked}), so the booking
 * automation never reads the order back. {@code filledQuantity} is the order's running total after this fill, and
 * {@code averagePrice} the quantity-weighted average price of all its fills so far.
 */
public record OrderFilled(OrderId orderId,
                          AccountId accountId,
                          InstrumentId instrumentId,
                          TradeSide side,
                          ExecutionId executionId,
                          Quantity quantity,
                          Money price,
                          LocalDate tradeDate,
                          LocalDate settlementDate,
                          Quantity filledQuantity,
                          Money averagePrice) implements OrderEvent {
    public OrderFilled {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
        requireNonNull(tradeDate, "No tradeDate provided");
        requireNonNull(settlementDate, "No settlementDate provided");
        requireNonNull(filledQuantity, "No filledQuantity provided");
        requireNonNull(averagePrice, "No averagePrice provided");
    }
}
