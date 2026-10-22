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

package dk.trustworks.essentials.examples.trading.reconciliation.views.bank_statement_breaks;

import java.math.BigDecimal;

/**
 * One difference between a bank statement and the brokerage's payments.
 *
 * @param reference      our {@code DepositId} / {@code WithdrawalId} (the entry's {@code endToEndId}), or the entry's
 *                       for an unknown one
 * @param statementAmount what the statement says; {@code null} for a missing entry
 * @param ourAmount      what the brokerage requested; {@code null} for an unknown entry
 */
public record StatementBreak(String statementId, Kind kind, String reference, String entryKind, BigDecimal statementAmount, BigDecimal ourAmount) {
    public enum Kind {
        /**
         * An entry for a payment the brokerage never requested.
         */
        UNKNOWN_ENTRY,
        /**
         * An entry whose amount differs from the payment's.
         */
        AMOUNT_MISMATCH,
        /**
         * An entry whose kind does not fit the payment: a deposit booked as a withdrawal, say.
         */
        KIND_MISMATCH,
        /**
         * A payment the brokerage recorded completed or returned on the statement's business date, with no entry for it.
         */
        MISSING_ENTRY
    }
}
