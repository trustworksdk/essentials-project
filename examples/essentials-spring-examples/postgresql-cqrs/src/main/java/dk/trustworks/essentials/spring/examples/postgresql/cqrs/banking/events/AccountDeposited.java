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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.ValueDate;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Money has been paid into an account.
 *
 * <p>Carries a {@code valueDate} -- the banking date the money counts from, which need not be the day the event was
 * recorded -- and the {@code transactionId} of the operation that caused it. That id is what lets the
 * {@code transfer_money} automation recognise a deposit as the second leg of a transfer it is driving, rather than an
 * unrelated payment into the same account.
 */
public record AccountDeposited(AccountId accountId,
                               Amount depositedAmount,
                               ValueDate valueDate,
                               TransactionId transactionId) implements AccountEvent {
    public AccountDeposited {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(depositedAmount, "No depositedAmount provided");
        requireNonNull(valueDate, "No valueDate provided");
        requireNonNull(transactionId, "No transactionId provided");
    }
}
