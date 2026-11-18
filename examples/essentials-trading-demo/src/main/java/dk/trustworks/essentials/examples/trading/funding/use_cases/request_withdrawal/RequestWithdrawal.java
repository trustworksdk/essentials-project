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

package dk.trustworks.essentials.examples.trading.funding.use_cases.request_withdrawal;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.types.Money;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The customer asks to withdraw {@code amount} to the trading account's linked bank account. Repeating it with the same
 * {@code withdrawalId} is a no-op.
 * <p>
 * Both the command and the request body of {@code POST /api/accounts/{accountId}/withdrawals}.
 */
public record RequestWithdrawal(WithdrawalId withdrawalId, AccountId accountId, Money amount) {
    public RequestWithdrawal {
        requireNonNull(withdrawalId, "No withdrawalId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(amount, "No amount provided");
    }
}
