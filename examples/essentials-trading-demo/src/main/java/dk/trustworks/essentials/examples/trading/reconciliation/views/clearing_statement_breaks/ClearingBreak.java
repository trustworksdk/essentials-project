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

package dk.trustworks.essentials.examples.trading.reconciliation.views.clearing_statement_breaks;

import java.math.BigDecimal;

/**
 * One difference between a clearing statement and the brokerage's executions, or within the statement itself.
 *
 * @param reference the execution id, or for {@code OBLIGATION_MISMATCH} the obligation id
 */
public record ClearingBreak(String statementId, Kind kind, String reference, Long statementQuantity, Long ourQuantity,
                            BigDecimal statementAmount, BigDecimal ourAmount) {
    public enum Kind {
        /**
         * A trade line for no execution of ours (the clearinghouse's seeded {@code PHANTOM_TRADE}).
         */
        UNKNOWN_TRADE,
        /**
         * An execution of ours on the statement's business date with no line (the seeded {@code OMITTED_TRADE}).
         */
        MISSING_TRADE,
        /**
         * Same execution, another quantity ({@code QUANTITY_CHANGED}).
         */
        QUANTITY_MISMATCH,
        /**
         * Same execution and quantity, another amount: the price ({@code PRICE_CHANGED}).
         */
        PRICE_MISMATCH,
        /**
         * Same execution, the other side.
         */
        SIDE_MISMATCH,
        /**
         * An obligation that is not the sum of the statement's own trade lines into it.
         */
        OBLIGATION_MISMATCH
    }
}
