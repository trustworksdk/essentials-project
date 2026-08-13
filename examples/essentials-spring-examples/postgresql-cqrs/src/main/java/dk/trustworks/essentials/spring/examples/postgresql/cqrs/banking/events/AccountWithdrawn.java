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
 * Money has been taken out of an account.
 *
 * <p>The mirror of {@code AccountDeposited}, and carries the same {@code valueDate} and {@code transactionId} for the
 * same reasons. Its existence in the stream means the withdrawal passed {@code Account}'s overdraft check -- a
 * rejected one throws before any event is applied, so it leaves no record here.
 */
public record AccountWithdrawn(AccountId accountId,
                               Amount withdrawAmount,
                               ValueDate valueDate,
                               TransactionId transactionId) implements AccountEvent {
    public AccountWithdrawn {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(withdrawAmount, "No withdrawAmount provided");
        requireNonNull(valueDate, "No valueDate provided");
        requireNonNull(transactionId, "No transactionId provided");
    }
}
