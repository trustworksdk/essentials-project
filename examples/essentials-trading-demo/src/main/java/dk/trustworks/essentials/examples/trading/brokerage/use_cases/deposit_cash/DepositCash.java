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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.deposit_cash;

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Book cash into a trading account's currently open books generation.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts/{accountId}/deposits} -- there is no separate DTO to keep in step. The
 * {@code accountId} appears in the path as well; the API file reconciles the two.
 *
 * @param accountId the account to deposit into
 * @param amount    the amount to book; must be positive, which {@code CashDeposited} enforces
 */
public record DepositCash(TradingAccountId accountId,
                          Amount amount) {
    public DepositCash {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(amount, "No amount provided");
    }
}
