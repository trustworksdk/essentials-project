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

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Cash has been paid into a trading account. The amount is strictly positive -- a withdrawal is not a negative
 * deposit, and there is no event for one.
 */
public record CashDeposited(TradingAccountGenerationId tradingAccountStreamId,
                            TradingAccountId logicalAccountId,
                            Amount amount) implements TradingAccountEvent {
    public CashDeposited {
        requireNonNull(tradingAccountStreamId, "No tradingAccountStreamId provided");
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(amount, "No amount provided");
        if (amount.value().signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
    }
}
