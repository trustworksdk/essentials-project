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

package dk.trustworks.essentials.examples.trading.brokerage.events;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A trade has been placed. The first event in every {@code Trade} stream.
 *
 * <p>{@code grossAmount} is stored rather than recomputed from quantity times price: it is what the trade was booked
 * at, and a later change to how the product is rounded must not retroactively restate a trade that already happened.
 *
 * <p>{@code instrumentId} is the one type this context borrows from {@code market_data} -- the instrument is that
 * context's concept, and only its id crosses.
 */
public record TradePlaced(TradeId tradeId,
                          TradingAccountId accountId,
                          InstrumentId instrumentId,
                          TradeSide side,
                          Quantity quantity,
                          Amount price,
                          Amount grossAmount) implements TradeEvent {
    public TradePlaced {
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
        requireNonNull(grossAmount, "No grossAmount provided");
    }
}
