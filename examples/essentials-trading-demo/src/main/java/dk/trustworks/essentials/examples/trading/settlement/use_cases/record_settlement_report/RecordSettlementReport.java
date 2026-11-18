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

package dk.trustworks.essentials.examples.trading.settlement.use_cases.record_settlement_report;

import dk.trustworks.essentials.examples.trading.accounts.types.ExecutionId;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the exchange's settlement simulator reported about one execution's settlement. Sent through
 * {@code Settlement:Reports} only.
 *
 * @param amount                 the settlement amount the report states
 * @param intendedSettlementDate the date the report states
 * @param previousAmount         {@code CORRECTED} only: the amount before the correction
 * @param reason                 {@code FAILED}, {@code CANCELLED}, {@code REJECTED} only
 */
public record RecordSettlementReport(ExecutionId executionId, Kind kind, Money amount, LocalDate intendedSettlementDate,
                                     int attempt, Money previousAmount, String reason) {
    public enum Kind {
        PENDING,
        DELAYED,
        COMPLETED,
        FAILED,
        CANCELLED,
        REJECTED,
        CORRECTED
    }

    public RecordSettlementReport {
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(kind, "No kind provided");
        requireNonNull(amount, "No amount provided");
        requireNonNull(intendedSettlementDate, "No intendedSettlementDate provided");
        if (kind == Kind.CORRECTED) {
            requireNonNull(previousAmount, "No previousAmount provided");
        }
        if (kind == Kind.FAILED || kind == Kind.CANCELLED || kind == Kind.REJECTED) {
            requireNonNull(reason, "No reason provided");
        }
    }
}
