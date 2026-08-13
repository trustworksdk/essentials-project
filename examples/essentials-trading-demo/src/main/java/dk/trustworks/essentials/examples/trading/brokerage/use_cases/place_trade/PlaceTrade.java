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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.place_trade;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Place a new trade against a trading account.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of {@code POST /api/admin/trades} --
 * there is no separate DTO to keep in step. The caller supplies the {@link TradeId}, which makes the request
 * idempotent to retry from the client's side: a repeat addresses the same stream rather than opening a second one.
 *
 * <p>It deliberately carries no gross amount. {@code quantity x price} is the {@code Trade} aggregate's rule, and it
 * computes it when it applies {@code TradePlaced}; a caller cannot disagree with it.
 */
public record PlaceTrade(TradeId tradeId,
                         TradingAccountId accountId,
                         InstrumentId instrumentId,
                         TradeSide side,
                         Quantity quantity,
                         Amount price) {
    public PlaceTrade {
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(price, "No price provided");
    }
}
