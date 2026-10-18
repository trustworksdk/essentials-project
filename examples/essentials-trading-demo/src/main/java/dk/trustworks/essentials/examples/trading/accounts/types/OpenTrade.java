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

package dk.trustworks.essentials.examples.trading.accounts.types;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A booked, not yet settled trade: everything its settlement or its reversal needs, so neither has to recompute what the
 * booking decided. {@code costBasisChange} is what the booking did to the position's cost basis (plus for a buy, minus
 * the cost of the shares sold for a sell); {@code realizedPnl} is zero for a buy.
 */
public record OpenTrade(ExecutionId executionId,
                        InstrumentId instrumentId,
                        TradeSide side,
                        Quantity quantity,
                        Money grossAmount,
                        Money costBasisChange,
                        Money realizedPnl,
                        LocalDate tradeDate,
                        LocalDate settlementDate) {
    public OpenTrade {
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireNonNull(grossAmount, "No grossAmount provided");
        requireNonNull(costBasisChange, "No costBasisChange provided");
        requireNonNull(realizedPnl, "No realizedPnl provided");
        requireNonNull(tradeDate, "No tradeDate provided");
        requireNonNull(settlementDate, "No settlementDate provided");
    }
}
