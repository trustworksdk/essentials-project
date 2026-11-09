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
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A withdrawal completed at the bank — a fact, never refused. Settled cash goes down by {@code amount};
 * {@code holdConsumed} is what it took from the withdrawal's hold (normally all of it; less, or zero, if the hold was
 * already gone).
 */
public record CashWithdrawn(AccountGenerationId accountStreamId,
                            AccountId accountId,
                            WithdrawalId withdrawalId,
                            HoldId holdId,
                            Money amount,
                            Money holdConsumed) implements AccountEvent {
    public CashWithdrawn {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(withdrawalId, "No withdrawalId provided");
        requireNonNull(holdId, "No holdId provided");
        AccountEvent.requirePositive(amount, "amount");
        requireNonNull(holdConsumed, "No holdConsumed provided");
    }
}
