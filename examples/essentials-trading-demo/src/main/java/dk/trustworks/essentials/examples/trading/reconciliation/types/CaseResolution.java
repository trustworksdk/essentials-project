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

package dk.trustworks.essentials.examples.trading.reconciliation.types;

/**
 * How the operator closed a case (Q-2). None of them books anything: what to book is the brokerage's judgement, and
 * the operator posts it themselves.
 */
public enum CaseResolution {
    /**
     * The brokerage's books were wrong; the operator posted the correction (an {@code AdjustCash}, the case id as its
     * reason), and the case records its adjustment id.
     */
    LEDGER_CORRECTED,
    /**
     * The statement is wrong; nothing to book.
     */
    COUNTERPARTY_ERROR,
    /**
     * Immaterial; nothing to book.
     */
    WRITTEN_OFF
}
