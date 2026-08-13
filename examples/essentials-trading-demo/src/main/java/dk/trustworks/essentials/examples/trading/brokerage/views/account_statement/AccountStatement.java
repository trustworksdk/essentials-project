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
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One row of the {@code projection_trading_account_statement} read model this slice owns — the statement state of one
 * logical trading account, summed across every books generation it has had.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * The components are the context's semantic types, not the {@code String}s and {@code BigDecimal}s the pre-slice
 * {@code TradingAccountStatementProjectionView} carried. They serialize as the same JSON scalars, so the wire contract
 * is unchanged — what changes is that an {@link OwnerId} can no longer be passed where a {@link PeriodId} is expected.
 *
 * @param logicalAccountId  the stable business id — <em>not</em> the per-generation stream id
 * @param currentGeneration the highest books generation seen for this account
 * @param generationCount   how many generations have been opened; equal to {@code currentGeneration} in practice, and
 *                          kept separate because the two would diverge if generations were ever skipped
 * @param reservedFunds     cash earmarked against pending obligations; zeroed when the books close
 * @param booksClosed       true between the closing entry of one generation and the opening event of the next
 */
public record AccountStatement(TradingAccountId logicalAccountId,
                               OwnerId ownerId,
                               PeriodId periodId,
                               int currentGeneration,
                               int generationCount,
                               Amount cashBalance,
                               Amount reservedFunds,
                               Amount realizedPnl,
                               boolean booksClosed) {
    public AccountStatement {
        requireNonNull(logicalAccountId, "No logicalAccountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(periodId, "No periodId provided");
        requireNonNull(cashBalance, "No cashBalance provided");
        requireNonNull(reservedFunds, "No reservedFunds provided");
        requireNonNull(realizedPnl, "No realizedPnl provided");
    }
}
