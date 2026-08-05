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

package dk.trustworks.essentials.examples.trading.settlements;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event hierarchy for the {@link Settlement} aggregate.
 */
public class SettlementEvent {
    public final SettlementId settlementId;

    protected SettlementEvent(SettlementId settlementId) {
        this.settlementId = requireNonNull(settlementId, "No settlementId provided");
    }

    public static class SettlementCreated extends SettlementEvent {
        public final String tradeId;
        public final String accountId;
        public final BigDecimal grossAmount;

        @JsonCreator
        public SettlementCreated(@JsonProperty("settlementId") SettlementId settlementId,
                                 @JsonProperty("tradeId") String tradeId,
                                 @JsonProperty("accountId") String accountId,
                                 @JsonProperty("grossAmount") BigDecimal grossAmount) {
            super(settlementId);
            this.tradeId = requireNonNull(tradeId, "No tradeId provided");
            this.accountId = requireNonNull(accountId, "No accountId provided");
            this.grossAmount = requireNonNull(grossAmount, "No grossAmount provided");
        }
    }

    public static class ClearingRequested extends SettlementEvent {
        @JsonCreator
        public ClearingRequested(@JsonProperty("settlementId") SettlementId settlementId) {
            super(settlementId);
        }
    }

    public static class ClearingConfirmed extends SettlementEvent {
        @JsonCreator
        public ClearingConfirmed(@JsonProperty("settlementId") SettlementId settlementId) {
            super(settlementId);
        }
    }

    public static class SettlementMarkedSettled extends SettlementEvent {
        @JsonCreator
        public SettlementMarkedSettled(@JsonProperty("settlementId") SettlementId settlementId) {
            super(settlementId);
        }
    }

    public static class SettlementReconciled extends SettlementEvent {
        @JsonCreator
        public SettlementReconciled(@JsonProperty("settlementId") SettlementId settlementId) {
            super(settlementId);
        }
    }

    public static class SettlementClosed extends SettlementEvent {
        @JsonCreator
        public SettlementClosed(@JsonProperty("settlementId") SettlementId settlementId) {
            super(settlementId);
        }
    }
}
