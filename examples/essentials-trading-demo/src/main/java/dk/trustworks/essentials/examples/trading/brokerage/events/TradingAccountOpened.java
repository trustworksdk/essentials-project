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

import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A books generation has been opened for a trading account. The first event in every generation stream -- both the
 * account's very first one and every one a rollover opens afterwards.
 *
 * <p>The opening balances are what carries across a rollover: the next generation opens with the cash the previous
 * one closed on, and realized P&amp;L reset to zero, because P&amp;L is reported per period.
 */
public record TradingAccountOpened(TradingAccountGenerationId tradingAccountStreamId,
                                   TradingAccountId logicalAccountId,
                                   OwnerId ownerId,
                                   PeriodId periodId,
                                   Amount openingCashBalance,
                                   Amount openingRealizedPnl) implements TradingAccountEvent {
    public TradingAccountOpened {
        requireNonNull(tradingAccountStreamId, "No tradingAccountStreamId provided");
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(periodId, "No periodId provided");
        requireNonNull(openingCashBalance, "No openingCashBalance provided");
        requireNonNull(openingRealizedPnl, "No openingRealizedPnl provided");
    }
}
