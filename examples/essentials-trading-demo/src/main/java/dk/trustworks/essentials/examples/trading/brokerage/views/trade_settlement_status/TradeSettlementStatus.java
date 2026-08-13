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

package dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status;

import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementStatus;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One row of the {@code projection_trade_settlement} read model this slice owns — a trade joined to its settlement's
 * lifecycle state.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * <b>Almost everything is nullable, and that is the model, not sloppiness.</b> The row is written from two independent
 * event streams and either may land first, so a row created by {@code SettlementCreated} has no instrument, side,
 * quantity or price until {@code TradePlaced} catches up. Only {@link #tradeId} and {@link #settlementStatus} are
 * always present — the latter because the column defaults to {@link SettlementStatus#NONE}.
 */
public record TradeSettlementStatus(TradeId tradeId,
                                    TradingAccountId accountId,
                                    InstrumentId instrumentId,
                                    TradeSide side,
                                    Quantity quantity,
                                    Amount price,
                                    Amount grossAmount,
                                    boolean executed,
                                    boolean settlementRequested,
                                    boolean settled,
                                    SettlementId settlementId,
                                    SettlementStatus settlementStatus) {
    public TradeSettlementStatus {
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(settlementStatus, "No settlementStatus provided");
    }
}
