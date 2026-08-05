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

package dk.trustworks.essentials.examples.trading.trades;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event hierarchy for the {@link Trade} aggregate.
 */
public class TradeEvent {
    public final TradeId tradeId;

    protected TradeEvent(TradeId tradeId) {
        this.tradeId = requireNonNull(tradeId, "No tradeId provided");
    }

    public static class TradePlaced extends TradeEvent {
        public final TradingAccountId accountId;
        public final InstrumentId instrumentId;
        public final String side;
        public final BigDecimal quantity;
        public final BigDecimal price;
        public final BigDecimal grossAmount;

        @JsonCreator
        public TradePlaced(@JsonProperty("tradeId") TradeId tradeId,
                           @JsonProperty("accountId") TradingAccountId accountId,
                           @JsonProperty("instrumentId") InstrumentId instrumentId,
                           @JsonProperty("side") String side,
                           @JsonProperty("quantity") BigDecimal quantity,
                           @JsonProperty("price") BigDecimal price,
                           @JsonProperty("grossAmount") BigDecimal grossAmount) {
            super(tradeId);
            this.accountId = requireNonNull(accountId, "No accountId provided");
            this.instrumentId = requireNonNull(instrumentId, "No instrumentId provided");
            this.side = requireNonNull(side, "No side provided");
            this.quantity = requireNonNull(quantity, "No quantity provided");
            this.price = requireNonNull(price, "No price provided");
            this.grossAmount = requireNonNull(grossAmount, "No grossAmount provided");
        }
    }

    public static class TradeExecuted extends TradeEvent {
        @JsonCreator
        public TradeExecuted(@JsonProperty("tradeId") TradeId tradeId) {
            super(tradeId);
        }
    }

    public static class SettlementRequested extends TradeEvent {
        public final String settlementId;

        @JsonCreator
        public SettlementRequested(@JsonProperty("tradeId") TradeId tradeId,
                                   @JsonProperty("settlementId") String settlementId) {
            super(tradeId);
            this.settlementId = requireNonNull(settlementId, "No settlementId provided");
        }
    }

    public static class TradeSettled extends TradeEvent {
        @JsonCreator
        public TradeSettled(@JsonProperty("tradeId") TradeId tradeId) {
            super(tradeId);
        }
    }
}
