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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books_and_open_next_period;

import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Roll a trading account's books: seal the current generation and open the next one on {@code nextPeriodId}.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts/{accountId}/generations} -- there is no separate DTO to keep in step.
 *
 * <p>This is the manual counterpart of the ON_ACCESS rollover that {@code TradingAccounts.getAccountForMutation}
 * performs on its own. It is the whole rollover; {@code use_cases/close_books} performs only the closing half.
 *
 * @param accountId    the account to roll
 * @param nextPeriodId the accounting period the incoming generation opens in
 */
public record CloseBooksAndOpenNextPeriod(TradingAccountId accountId,
                                          PeriodId nextPeriodId) {
    public CloseBooksAndOpenNextPeriod {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
    }
}
