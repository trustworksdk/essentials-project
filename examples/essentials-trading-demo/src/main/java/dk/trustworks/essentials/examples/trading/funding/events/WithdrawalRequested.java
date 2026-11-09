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
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The customer asked to withdraw {@code amount} to their linked bank account. The withdrawal's hold is placed on the account in the same unit of work.
 */
public record WithdrawalRequested(WithdrawalId withdrawalId, AccountId accountId, Money amount, BankAccount bankAccount) implements WithdrawalEvent {
    public WithdrawalRequested {
        requireNonNull(withdrawalId, "No withdrawalId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(amount, "No amount provided");
        requireNonNull(bankAccount, "No bankAccount provided");
    }
}
