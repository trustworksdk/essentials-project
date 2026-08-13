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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_statement;

import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One logical trading account as the admin surface sees it: this slice's statement row, plus the closing-books
 * generations behind it.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * The balances come from {@link AccountStatement} — the read model this slice owns — and not from a rehydrated
 * {@code TradingAccount}. The pre-slice version loaded the write aggregate to answer this query, which is the read
 * side serving itself from the write model. The consequence is worth knowing: this view is eventually consistent,
 * where the aggregate read was not.
 * <p>
 * The generations, by contrast, are framework lifecycle metadata read live through {@code AggregateLifecycleApi}, so
 * they are always current.
 *
 * @param currentStreamAggregateId the stream id of the generation currently open, {@code <accountId>#<generation>}
 * @param generations              every generation the account has had, oldest first as the framework returns them
 */
public record AccountOverview(TradingAccountId logicalAccountId,
                              OwnerId ownerId,
                              PeriodId currentStatementPeriod,
                              Amount cashBalance,
                              Amount reservedFunds,
                              Amount realizedPnl,
                              boolean booksClosed,
                              long currentGeneration,
                              TradingAccountGenerationId currentStreamAggregateId,
                              List<AccountGeneration> generations) {
    public AccountOverview {
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(currentStatementPeriod, "No currentStatementPeriod provided");
        requireNonNull(cashBalance, "No cashBalance provided");
        requireNonNull(reservedFunds, "No reservedFunds provided");
        requireNonNull(realizedPnl, "No realizedPnl provided");
        requireNonNull(currentStreamAggregateId, "No currentStreamAggregateId provided");
        requireNonNull(generations, "No generations provided");
    }
}
