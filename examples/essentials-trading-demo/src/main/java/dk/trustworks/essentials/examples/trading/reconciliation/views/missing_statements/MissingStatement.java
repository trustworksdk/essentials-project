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

package dk.trustworks.essentials.examples.trading.reconciliation.views.missing_statements;

import java.time.*;

/**
 * A statement expected and not received within its grace (design phase 5 §3).
 *
 * @param statementId the id the statement would have: {@code CS:<date>:<participant>} or {@code BS:<date>:<currency>}
 * @param expectedBy  the business day's cutoff plus the grace, on the simulators' clock
 */
public record MissingStatement(Source source, String statementId, LocalDate businessDate, Instant expectedBy) {
    public static final String KIND = "MISSING_STATEMENT";

    public enum Source {
        /**
         * The clearinghouse's, expected for a business day the brokerage traded on.
         */
        CLEARING,
        /**
         * The bank's, expected for a business day a payment of ours was booked on.
         */
        BANK
    }
}
