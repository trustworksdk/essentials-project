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

package dk.trustworks.essentials.examples.trading.orders.views.orders;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.OffsetDateTime;

/**
 * {@code averageFillPrice} is null until the first fill.
 */
public record OrderView(OrderId orderId,
                        AccountId accountId,
                        InstrumentId instrumentId,
                        TradeSide side,
                        OrderType type,
                        Quantity quantity,
                        Money limitPrice,
                        TimeInForce timeInForce,
                        OrderStatus status,
                        Quantity filledQuantity,
                        Money averageFillPrice,
                        ExchangeOrderId exchangeOrderId,
                        String reason,
                        OffsetDateTime placedAt,
                        OffsetDateTime updatedAt) {
}
