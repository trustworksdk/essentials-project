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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.open_trading_account;

import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Open the first books generation of a trading account, owned by {@code ownerId} and opening on {@code periodId}.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/trading-accounts} -- there is no separate DTO to keep in step. The caller supplies the
 * {@link TradingAccountId}, which is what lets the demo harness name the accounts it is about to drive.
 *
 * @param accountId the stable business id of the account; spans every books generation
 * @param ownerId   the party the account belongs to; carried forward unchanged across every rollover
 * @param periodId  the accounting period the first generation opens in
 */
public record OpenTradingAccount(TradingAccountId accountId,
                                 OwnerId ownerId,
                                 PeriodId periodId) {
    public OpenTradingAccount {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(periodId, "No periodId provided");
    }
}
