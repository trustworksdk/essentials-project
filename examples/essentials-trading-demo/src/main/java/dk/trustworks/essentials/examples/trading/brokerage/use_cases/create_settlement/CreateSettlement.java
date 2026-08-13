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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.create_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Open the post-trade settlement of a trade.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/settlements} -- there is no separate DTO to keep in step. The caller supplies the
 * {@link SettlementId}, which makes the request idempotent to retry from the client's side; the demo derives it with
 * {@code SettlementId.forTrade(tradeId)}.
 *
 * <p>{@code grossAmount} is carried over from the trade rather than recomputed: the {@code Settlement} is a separate
 * consistency boundary and never reads the {@code Trade} aggregate.
 */
public record CreateSettlement(SettlementId settlementId,
                               TradeId tradeId,
                               TradingAccountId accountId,
                               Amount grossAmount) {
    public CreateSettlement {
        requireNonNull(settlementId, "No settlementId provided");
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(grossAmount, "No grossAmount provided");
    }
}
