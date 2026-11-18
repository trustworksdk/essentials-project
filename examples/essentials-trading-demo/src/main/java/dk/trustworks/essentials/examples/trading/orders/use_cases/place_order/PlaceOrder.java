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

package dk.trustworks.essentials.examples.trading.orders.use_cases.place_order;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Place an order for an account. The caller supplies the {@code orderId}, so a retried request addresses the same order
 * and is not placed twice. {@code timeInForce} defaults to {@code DAY}; {@code limitPrice} is required for a limit order.
 * <p>
 * Both the command and the request body of {@code POST /api/accounts/{accountId}/orders}.
 */
public record PlaceOrder(OrderId orderId,
                         AccountId accountId,
                         InstrumentId instrumentId,
                         TradeSide side,
                         OrderType type,
                         Quantity quantity,
                         Money limitPrice,
                         TimeInForce timeInForce) {
    public PlaceOrder {
        requireNonNull(orderId, "No orderId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(type, "No type provided");
        requireNonNull(quantity, "No quantity provided");
        if (timeInForce == null) {
            timeInForce = TimeInForce.DAY;
        }
    }
}
