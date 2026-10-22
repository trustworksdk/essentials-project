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

package dk.trustworks.essentials.examples.trading.funding.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;

/**
 * The closed set of events a {@code Withdrawal} emits (design {@code phase-2-funding.md} §3). Every one names its
 * trading account, so the ledger automation needs nothing else.
 */
public sealed interface WithdrawalEvent permits WithdrawalRequested, WithdrawalInitiated, WithdrawalCompleted, WithdrawalFailed, WithdrawalReturnedByBank {
    WithdrawalId withdrawalId();

    AccountId accountId();
}
