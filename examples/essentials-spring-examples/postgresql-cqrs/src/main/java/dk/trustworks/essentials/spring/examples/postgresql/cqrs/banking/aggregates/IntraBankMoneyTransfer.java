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
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferCompleted;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferRequested;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferStatusChanged;
import dk.trustworks.essentials.types.Amount;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransferLifeCycleStatus;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * A transfer of money between two accounts in the same bank -- modelled as an aggregate in its own right, so that the
 * transfer has an identity, a lifecycle and a stream of its own rather than being an implicit consequence of two
 * account changes.
 *
 * <p>It is the state machine the {@code transfer_money} automation drives: {@code REQUESTED} →
 * {@code FROM_ACCOUNT_WITHDRAWN} → {@code TO_ACCOUNT_DEPOSITED}, ending in
 * {@code IntraBankMoneyTransferCompleted}. Each transition asserts the status it expects and throws otherwise, which
 * is what makes the automation's at-least-once redeliveries safe: replaying a step that already happened fails loudly
 * instead of withdrawing the money twice.
 *
 * <p>Two accounts cannot be changed in one transaction under event sourcing without giving up the aggregate as the
 * consistency boundary. This aggregate is the alternative: the transfer is eventually consistent, and its status is
 * the record of how far it has got.
 */
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

    public IntraBankMoneyTransfer(TransactionId transactionId,
                                  AccountId fromAccount,
                                  AccountId toAccount,
                                  Amount amount) {
        super(transactionId);
        apply(new IntraBankMoneyTransferRequested(transactionId,
                                                  fromAccount,
                                                  toAccount,
                                                  amount,
                                                  TransferLifeCycleStatus.REQUESTED));
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
