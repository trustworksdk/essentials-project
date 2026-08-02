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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.ValueDate;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountDeposited;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountOpened;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountWithdrawn;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class Account extends AggregateRoot<AccountId, AccountEvent, Account> {
    private Amount balance;

    /**
     * Used for rehydration
     */
    public Account(AccountId aggregateId) {
        super(aggregateId);
    }

    public Account(AccountId accountId,
                   AccountNumber accountNumber) {
        super(accountId);
        requireNonNull(accountNumber, "No accountNumber provided");
        apply(new AccountOpened(accountId,
                                accountNumber));
    }

    public void depositToday(Amount depositAmount,
                             TransactionId transactionId) {
        requireNonNull(depositAmount, "No depositAmount provided");
        requireNonNull(transactionId, "No transactionId provided");
        deposit(depositAmount,
                ValueDate.today(),
                transactionId);
    }

    public void deposit(Amount depositAmount,
                        ValueDate withValueDate,
                        TransactionId transactionId) {
        requireNonNull(depositAmount, "No depositAmount provided");
        requireNonNull(withValueDate, "No withValueDate provided");
        requireNonNull(transactionId, "No transactionId provided");
        apply(new AccountDeposited(aggregateId(),
                                   depositAmount,
                                   withValueDate,
                                   transactionId));
    }

    public void withdrawToday(Amount withdrawAmount,
                              TransactionId transactionId,
                              AllowOverdrawingBalance allowOverdrawingBalance) {
        requireNonNull(withdrawAmount, "No withdrawAmount provided");
        requireNonNull(transactionId, "No transactionId provided");
        requireNonNull(allowOverdrawingBalance, "No allowOverdrawingBalance provided");
        withdraw(withdrawAmount,
                 ValueDate.today(),
                 transactionId,
                 allowOverdrawingBalance);
    }

    public void withdraw(Amount withdrawAmount,
                         ValueDate withValueDate,
                         TransactionId transactionId,
                         AllowOverdrawingBalance allowOverdrawingBalance) {
        requireNonNull(withdrawAmount, "No withdrawAmount provided");
        requireNonNull(withValueDate, "No withValueDate provided");
        requireNonNull(transactionId, "No transactionId provided");
        requireNonNull(allowOverdrawingBalance, "No allowOverdrawingBalance provided");
        if (allowOverdrawingBalance.disallowed() && balance.subtract(withdrawAmount).isLessThan(Amount.ZERO)) {
            throw new InsufficientFundsException(aggregateId(),
                                                 balance,
                                                 withdrawAmount);
        }

        apply(new AccountWithdrawn(aggregateId(),
                                   withdrawAmount,
                                   withValueDate,
                                   transactionId));
    }

    public Amount getBalance() {
        return balance;
    }

    @EventHandler
    private void on(AccountOpened e) {
        balance = Amount.ZERO;
    }

    @EventHandler
    private void on(AccountWithdrawn e) {
        balance = balance.subtract(e.withdrawAmount);
    }

    @EventHandler
    private void on(AccountDeposited e) {
        balance = balance.add(e.depositedAmount);
    }
}
