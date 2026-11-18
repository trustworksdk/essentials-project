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

package dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.funding.types.BankAccount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Link the bank account a trading account deposits from and withdraws to (design Q-2). Linking another replaces it.
 * <p>
 * Both the command and the request body of {@code PUT /api/accounts/{accountId}/bank-account}.
 */
public record LinkBankAccount(AccountId accountId, BankAccount bankAccount) {
    public LinkBankAccount {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(bankAccount, "No bankAccount provided");
    }
}
