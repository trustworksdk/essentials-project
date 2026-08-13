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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books;

import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Seal a trading account's current books generation, naming the period the next one will open in.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts/{accountId}/books-closures} -- there is no separate DTO to keep in step.
 *
 * <p>This closes the books <em>without</em> opening the next generation; see
 * {@code use_cases/close_books_and_open_next_period} for the pair.
 *
 * @param accountId    the account whose books are sealed
 * @param nextPeriodId the period recorded on the closing entry as the one the books roll into
 */
public record CloseBooks(TradingAccountId accountId,
                         PeriodId nextPeriodId) {
    public CloseBooks {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
    }
}
