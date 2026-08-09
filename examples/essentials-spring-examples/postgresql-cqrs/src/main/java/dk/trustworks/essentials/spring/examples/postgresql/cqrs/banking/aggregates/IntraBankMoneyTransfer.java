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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.request_intra_bank_money_transfer.RequestIntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferCompleted;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferRequested;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferStatusChanged;
import dk.trustworks.essentials.types.Amount;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransferLifeCycleStatus;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class IntraBankMoneyTransfer extends AggregateRoot<TransactionId, IntraBankMoneyTransferEvent, IntraBankMoneyTransfer> {
    private TransferLifeCycleStatus status;
    private Amount                  amount;
    private AccountId               fromAccount;
    private AccountId               toAccount;

    /**
     * Used for rehydration
     *
     * @param aggregateId
     */
    public IntraBankMoneyTransfer(TransactionId aggregateId) {
        super(aggregateId);
    }

    public IntraBankMoneyTransfer(RequestIntraBankMoneyTransfer cmd) {
        super(cmd.transactionId());
        apply(IntraBankMoneyTransferRequested.from(cmd));
    }

    public void markFromAccountAsWithdrawn() {
        if (status != TransferLifeCycleStatus.REQUESTED) {
            throw new IllegalStateException(msg("Expected state '{}' but has state '{}'", TransferLifeCycleStatus.REQUESTED, status));
        }
        apply(new IntraBankMoneyTransferStatusChanged(aggregateId(),
                                                      TransferLifeCycleStatus.FROM_ACCOUNT_WITHDRAWN));
    }

    public void markToAccountAsDeposited() {
        if (status != TransferLifeCycleStatus.FROM_ACCOUNT_WITHDRAWN) {
            throw new IllegalStateException(msg("Expected state '{}' but has state '{}'", TransferLifeCycleStatus.FROM_ACCOUNT_WITHDRAWN, status));
        }
        apply(new IntraBankMoneyTransferStatusChanged(aggregateId(),
                                                      TransferLifeCycleStatus.TO_ACCOUNT_DEPOSITED));
        apply(IntraBankMoneyTransferCompleted.of(aggregateId()));
    }

    @EventHandler
    private void handle(IntraBankMoneyTransferRequested e) {
        amount = e.amount();
        fromAccount = e.fromAccount();
        toAccount = e.toAccount();
        status = e.status();
    }

    @EventHandler
    private void handle(IntraBankMoneyTransferStatusChanged e) {
        status = e.status();
    }

    @EventHandler
    private void handle(IntraBankMoneyTransferCompleted e) {
        status = e.status();
    }

    public TransferLifeCycleStatus getStatus() {
        return status;
    }

    public Amount getAmount() {
        return amount;
    }

    public AccountId getFromAccount() {
        return fromAccount;
    }

    public AccountId getToAccount() {
        return toAccount;
    }

}
