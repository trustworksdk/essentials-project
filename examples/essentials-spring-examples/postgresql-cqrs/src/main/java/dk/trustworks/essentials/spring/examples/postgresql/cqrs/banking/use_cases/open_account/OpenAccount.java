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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.open_account;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountNumber;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Open a new account under the given account number.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of {@code POST /accounts} -- there is
 * no separate DTO to keep in step. The caller supplies the {@code AccountId}, which makes the command idempotent to
 * retry from the client's side.
 */
public record OpenAccount(AccountId accountId,
                          AccountNumber accountNumber) {
    public OpenAccount {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(accountNumber, "No accountNumber provided");
    }
}
