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

package dk.trustworks.essentials.examples.trading.funding.types;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public final class FundingAggregateTypes {
    /**
     * The bank account each trading account deposits from and withdraws to (design Q-2), keyed by the trading account.
     */
    public static final AggregateType BANK_ACCOUNT_LINKS = AggregateType.of("BankAccountLinks");
    public static final AggregateType DEPOSITS           = AggregateType.of("Deposits");
    public static final AggregateType WITHDRAWALS        = AggregateType.of("Withdrawals");

    private FundingAggregateTypes() {
    }
}
