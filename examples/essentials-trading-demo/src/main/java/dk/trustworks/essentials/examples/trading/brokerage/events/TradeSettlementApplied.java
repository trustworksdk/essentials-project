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

import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A settled trade has been booked against the account. Both deltas are signed -- a buy takes cash out, a sale puts it
 * in, and realized P&amp;L moves either way -- so unlike the deposit and reservation events these are not constrained
 * to be positive.
 *
 * <p>{@code tradeId} is the only link back to the {@code Trade} aggregate; the two are separate consistency
 * boundaries and nothing writes both in one transaction.
 */
public record TradeSettlementApplied(TradingAccountGenerationId tradingAccountStreamId,
                                     TradingAccountId logicalAccountId,
                                     TradeId tradeId,
                                     Amount cashDelta,
                                     Amount realizedPnlDelta) implements TradingAccountEvent {
    public TradeSettlementApplied {
        requireNonNull(tradingAccountStreamId, "No tradingAccountStreamId provided");
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(cashDelta, "No cashDelta provided");
        requireNonNull(realizedPnlDelta, "No realizedPnlDelta provided");
    }
}
