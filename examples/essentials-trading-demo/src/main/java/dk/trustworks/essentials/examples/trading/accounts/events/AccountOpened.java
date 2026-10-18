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

package dk.trustworks.essentials.examples.trading.accounts.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.types.CurrencyCode;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A generation of an account starts: a brand new account (empty opening balances) or the next period after closing
 * books (the previous generation's whole open state).
 */
public record AccountOpened(AccountGenerationId accountStreamId,
                            AccountId accountId,
                            UserId ownerId,
                            CurrencyCode currency,
                            PeriodId periodId,
                            OpeningBalances openingBalances) implements AccountEvent {
    public AccountOpened {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(ownerId, "No ownerId provided");
        requireNonNull(currency, "No currency provided");
        requireNonNull(periodId, "No periodId provided");
        requireNonNull(openingBalances, "No openingBalances provided");
    }
}
