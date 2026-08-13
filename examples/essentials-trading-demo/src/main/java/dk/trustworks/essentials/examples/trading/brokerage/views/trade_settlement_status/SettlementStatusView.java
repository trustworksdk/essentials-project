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

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementStatus;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One settlement, read off the row of {@code projection_trade_settlement} that carries its id.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * The five booleans are <b>derived</b> from {@link #status}, not stored. The projection tracks the settlement as a
 * single position in its lifecycle, and {@link SettlementStatus} is declared in the order the lifecycle advances
 * through — so "has cleared" is "has reached at least {@code CLEARING_CONFIRMED}". They exist because the pre-slice
 * {@code SettlementAdminView} carried them, read straight off the {@code Settlement} aggregate's own flags; keeping
 * them keeps the response shape.
 *
 * @param status            where the settlement has got to; the one field that is actually stored
 * @param clearingRequested reached {@code CLEARING_REQUESTED} or beyond
 * @param clearingConfirmed reached {@code CLEARING_CONFIRMED} or beyond
 * @param settled           reached {@code SETTLED} or beyond
 * @param reconciled        reached {@code RECONCILED} or beyond
 * @param closed            reached {@code CLOSED}, the terminal state
 */
public record SettlementStatusView(SettlementId settlementId,
                                   TradeId tradeId,
                                   TradingAccountId accountId,
                                   Amount grossAmount,
                                   SettlementStatus status,
                                   boolean clearingRequested,
                                   boolean clearingConfirmed,
                                   boolean settled,
                                   boolean reconciled,
                                   boolean closed) {
    public SettlementStatusView {
        requireNonNull(settlementId, "No settlementId provided");
        requireNonNull(tradeId, "No tradeId provided");
        requireNonNull(status, "No status provided");
    }

    /**
     * Builds the view from a projection row, deriving the booleans from the row's status.
     *
     * @throws IllegalArgumentException if the row carries no settlement id — the caller looked it up <em>by</em>
     *                                  settlement id, so that cannot happen and would mean the query changed
     */
    static SettlementStatusView from(TradeSettlementStatus row) {
        requireNonNull(row, "No row provided");
        if (row.settlementId() == null) {
            throw new IllegalArgumentException("Trade '" + row.tradeId() + "' has no settlement");
        }
        var status = row.settlementStatus();
        return new SettlementStatusView(row.settlementId(),
                                        row.tradeId(),
                                        row.accountId(),
                                        row.grossAmount(),
                                        status,
                                        hasReached(status, SettlementStatus.CLEARING_REQUESTED),
                                        hasReached(status, SettlementStatus.CLEARING_CONFIRMED),
                                        hasReached(status, SettlementStatus.SETTLED),
                                        hasReached(status, SettlementStatus.RECONCILED),
                                        hasReached(status, SettlementStatus.CLOSED));
    }

    /**
     * Ordinal comparison is sound here only because {@link SettlementStatus} is declared in lifecycle order and the
     * aggregate's guards enforce exactly that order. Reordering the enum silently rewrites these five booleans.
     */
    private static boolean hasReached(SettlementStatus status, SettlementStatus milestone) {
        return status.ordinal() >= milestone.ordinal();
    }
}
