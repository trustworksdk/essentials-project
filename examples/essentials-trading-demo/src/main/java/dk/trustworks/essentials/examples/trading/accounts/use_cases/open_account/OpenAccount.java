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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.types.CurrencyCode;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Open an empty trading account for {@code ownerId}, in {@code currency} (USD for now, D3). Both the command and the
 * request body of {@code POST /api/accounts}; a customer may open accounts only for themselves. The caller supplies the
 * {@link AccountId}, so a retried open addresses the same account and is a no-op.
 */
public record OpenAccount(AccountId accountId, UserId ownerId, CurrencyCode currency) {
    public OpenAccount {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(currency, "No currency provided");
    }
}
