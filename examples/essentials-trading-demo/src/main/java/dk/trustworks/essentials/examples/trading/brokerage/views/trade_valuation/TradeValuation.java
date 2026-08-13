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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_valuation;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One trade, valued against the latest market price this slice has projected for its instrument.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * {@link #marketValue} and {@link #unrealizedPnl} are <b>computed</b>, not stored — see
 * {@link TradeValuationQuery}. All three price-derived components are {@code null} together: until a price event for
 * the instrument has been projected there is nothing to value the trade against, and reporting a zero valuation would
 * be a lie rather than a gap.
 *
 * @param executionPrice    the price the trade was booked at; fixed for the life of the trade
 * @param grossAmount       what the trade was booked at, stored rather than recomputed from quantity times price
 * @param settlementId      {@code null} until settlement has been requested
 * @param latestMarketPrice the last price projected for the instrument, or {@code null} if none yet
 * @param marketValue       {@code latestMarketPrice × quantity}
 * @param unrealizedPnl     the price move since execution, signed for the side, times quantity
 */
public record TradeValuation(TradeId tradeId,
                             TradingAccountId accountId,
                             InstrumentId instrumentId,
                             TradeSide side,
                             Quantity quantity,
                             Amount executionPrice,
                             Amount grossAmount,
                             boolean executed,
                             boolean settlementRequested,
                             boolean settled,
                             SettlementId settlementId,
                             Amount latestMarketPrice,
                             Amount marketValue,
                             Amount unrealizedPnl) {
    public TradeValuation {
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(executionPrice, "No executionPrice provided");
        requireNonNull(grossAmount, "No grossAmount provided");
    }
}
