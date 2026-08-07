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

package dk.trustworks.essentials.examples.trading.accounts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event hierarchy for the {@link TradingAccount} aggregate.
 */
public class TradingAccountEvent {
    public final TradingAccountGenerationId tradingAccountStreamId;
    public final TradingAccountId logicalAccountId;

    protected TradingAccountEvent(TradingAccountGenerationId tradingAccountStreamId,
                                  TradingAccountId logicalAccountId) {
        this.tradingAccountStreamId = requireNonNull(tradingAccountStreamId, "No tradingAccountStreamId provided");
        this.logicalAccountId = requireNonNull(logicalAccountId, "No logicalAccountId provided");
    }

    public static class TradingAccountOpened extends TradingAccountEvent {
        public final String ownerId;
        public final String periodId;
        public final BigDecimal openingCashBalance;
        public final BigDecimal openingRealizedPnl;

        @JsonCreator
        public TradingAccountOpened(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                                    @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                                    @JsonProperty("ownerId") String ownerId,
                                    @JsonProperty("periodId") String periodId,
                                    @JsonProperty("openingCashBalance") BigDecimal openingCashBalance,
                                    @JsonProperty("openingRealizedPnl") BigDecimal openingRealizedPnl) {
            super(tradingAccountStreamId, logicalAccountId);
            this.ownerId = requireNonNull(ownerId, "No ownerId provided");
            this.periodId = requireNonNull(periodId, "No periodId provided");
            this.openingCashBalance = requireNonNull(openingCashBalance, "No openingCashBalance provided");
            this.openingRealizedPnl = requireNonNull(openingRealizedPnl, "No openingRealizedPnl provided");
        }
    }

    public static class CashDeposited extends TradingAccountEvent {
        public final BigDecimal amount;

        @JsonCreator
        public CashDeposited(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                             @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                             @JsonProperty("amount") BigDecimal amount) {
            super(tradingAccountStreamId, logicalAccountId);
            this.amount = requirePositive(amount, "amount");
        }
    }

    public static class FundsReserved extends TradingAccountEvent {
        public final BigDecimal amount;

        @JsonCreator
        public FundsReserved(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                             @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                             @JsonProperty("amount") BigDecimal amount) {
            super(tradingAccountStreamId, logicalAccountId);
            this.amount = requirePositive(amount, "amount");
        }
    }

    public static class FundsReleased extends TradingAccountEvent {
        public final BigDecimal amount;

        @JsonCreator
        public FundsReleased(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                             @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                             @JsonProperty("amount") BigDecimal amount) {
            super(tradingAccountStreamId, logicalAccountId);
            this.amount = requirePositive(amount, "amount");
        }
    }

    public static class TradeSettlementApplied extends TradingAccountEvent {
        public final String tradeId;
        public final BigDecimal cashDelta;
        public final BigDecimal realizedPnlDelta;

        @JsonCreator
        public TradeSettlementApplied(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                                      @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                                      @JsonProperty("tradeId") String tradeId,
                                      @JsonProperty("cashDelta") BigDecimal cashDelta,
                                      @JsonProperty("realizedPnlDelta") BigDecimal realizedPnlDelta) {
            super(tradingAccountStreamId, logicalAccountId);
            this.tradeId = requireNonNull(tradeId, "No tradeId provided");
            this.cashDelta = requireNonNull(cashDelta, "No cashDelta provided");
            this.realizedPnlDelta = requireNonNull(realizedPnlDelta, "No realizedPnlDelta provided");
        }
    }

    public static class AccountBooksClosed extends TradingAccountEvent {
        public final String nextPeriodId;
        public final EventOrder eventOrder;

        @JsonCreator
        public AccountBooksClosed(@JsonProperty("tradingAccountStreamId") TradingAccountGenerationId tradingAccountStreamId,
                                  @JsonProperty("logicalAccountId") TradingAccountId logicalAccountId,
                                  @JsonProperty("nextPeriodId") String nextPeriodId,
                                  @JsonProperty("eventOrder") EventOrder eventOrder) {
            super(tradingAccountStreamId, logicalAccountId);
            this.nextPeriodId = requireNonNull(nextPeriodId, "No nextPeriodId provided");
            this.eventOrder = requireNonNull(eventOrder, "No eventOrder provided");
        }
    }

    private static BigDecimal requirePositive(BigDecimal amount, String fieldName) {
        requireNonNull(amount, "No " + fieldName + " provided");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return amount;
    }
}
