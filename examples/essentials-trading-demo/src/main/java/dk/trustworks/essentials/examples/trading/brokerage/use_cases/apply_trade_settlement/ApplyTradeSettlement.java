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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Book the cash and realized-P&amp;L result of a settled trade onto the trading account.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts/{accountId}/trade-settlements} -- there is no separate DTO to keep in step.
 *
 * <p>The deltas are signed: a buy moves cash out, a sell moves it in, and the realized P&amp;L of a losing trade is
 * negative. This is the seam where the {@code Settlement} aggregate's outcome crosses into the account's books --
 * separate consistency boundaries, so it crosses as a command, not inside one transaction.
 *
 * @param accountId        the account to book onto
 * @param tradeId          the trade the settlement belongs to; carried into the event so a projection can correlate
 * @param cashDelta        the signed change to the cash balance
 * @param realizedPnlDelta the signed change to the period's realized P&amp;L
 */
public record ApplyTradeSettlement(TradingAccountId accountId,
                                   TradeId tradeId,
                                   Amount cashDelta,
                                   Amount realizedPnlDelta) {
    public ApplyTradeSettlement {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(cashDelta, "No cashDelta provided");
        requireNonNull(realizedPnlDelta, "No realizedPnlDelta provided");
    }
}
