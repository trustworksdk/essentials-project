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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.request_intra_bank_money_transfer;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Ask for money to be moved between two accounts in this bank.
 *
 * <p>Both the command and the request body of {@code POST /transfers}. Handling it only <em>records the request</em>
 * -- it creates the {@code IntraBankMoneyTransfer} aggregate in state {@code REQUESTED}; the withdrawal and deposit
 * are carried out afterwards by the {@code transfer_money} automation reacting to the resulting event.
 *
 * <p>That split is what makes the transfer durable: once this command commits, the transfer is a fact with its own
 * stream, and the process can resume after a crash instead of being lost mid-flight.
 */
public record RequestIntraBankMoneyTransfer(TransactionId transactionId,
                                            AccountId fromAccount,
                                            AccountId toAccount,
                                            Amount amount) {
    public RequestIntraBankMoneyTransfer {
        requireNonNull(transactionId, "No transactionId provided");
        requireNonNull(fromAccount, "No fromAccount provided");
        requireNonNull(toAccount, "No toAccount provided");
        requireNonNull(amount, "No amount provided");
    }
}
