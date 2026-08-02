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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.commands.RequestIntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.AllowOverdrawingBalance;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountDeposited;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.account.events.AccountWithdrawn;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.TransactionException;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.IntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.IntraBankMoneyTransfers;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.TransferLifeCycleStatus;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.events.IntraBankMoneyTransferRequested;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.domain.transactions.intrabank.events.IntraBankMoneyTransferStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

@Service
public class TransferMoneyProcessor extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(TransferMoneyProcessor.class);

    private final Accounts accounts;
    private final IntraBankMoneyTransfers intraBankMoneyTransfers;

    public TransferMoneyProcessor(Accounts accounts,
                                  IntraBankMoneyTransfers intraBankMoneyTransfers,
                                  EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies);
        requireNonNull(accounts, "No accounts provided");
        requireNonNull(intraBankMoneyTransfers, "No intraBankMoneyTransfers provided");
        this.accounts = accounts;
        this.intraBankMoneyTransfers = intraBankMoneyTransfers;
    }

    @Override
    public String getProcessorName() {
        return "TransferMoneyProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Accounts.AGGREGATE_TYPE,
                       IntraBankMoneyTransfers.AGGREGATE_TYPE);
    }

    @CmdHandler
    public void handle(RequestIntraBankMoneyTransfer cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (accounts.isAccountMissing(cmd.fromAccount)) {
            throw new TransactionException(msg("Couldn't find fromAccount with id '{}'", cmd.fromAccount));
        }
        if (accounts.isAccountMissing(cmd.toAccount)) {
            throw new TransactionException(msg("Couldn't find toAccount with id '{}'", cmd.toAccount));
        }

        var existingTransfer = intraBankMoneyTransfers.findTransfer(cmd.transactionId);
        if (existingTransfer.isEmpty()) {
            log.debug("===> Requesting New Transfer '{}'", cmd.transactionId);
            intraBankMoneyTransfers.requestNewTransfer(new IntraBankMoneyTransfer(cmd));
        }
    }

    @MessageHandler
    void handle(IntraBankMoneyTransferRequested e) {
        var transfer = intraBankMoneyTransfers.getTransfer(e.transactionId);
        log.debug("===> Transfer '{}' requested - will withdraw {} from account '{}' related to Transfer '{}'", transfer.aggregateId(), transfer.getAmount(), transfer.getFromAccount(), transfer.aggregateId());
        accounts.getAccount(transfer.getFromAccount())
                .withdrawToday(transfer.getAmount(),
                               transfer.aggregateId(),
                               AllowOverdrawingBalance.NO);
    }

    @MessageHandler
    void handle(IntraBankMoneyTransferStatusChanged e) {
        var transfer = intraBankMoneyTransfers.getTransfer(e.transactionId);
        if (transfer.getStatus() == TransferLifeCycleStatus.FROM_ACCOUNT_WITHDRAWN) {
            log.debug("===> Will deposit {} to account '{}' related to Transfer '{}'", transfer.getAmount(), transfer.getToAccount(), transfer.aggregateId());
            accounts.getAccount(transfer.getToAccount())
                    .depositToday(transfer.getAmount(),
                                  transfer.aggregateId());
        }
    }

    @MessageHandler
    void handle(AccountWithdrawn e) {
        var matchingTransfer = intraBankMoneyTransfers.findTransfer(e.transactionId);

        matchingTransfer.ifPresent(transfer -> {
            log.debug("===> Account {} Withdrawn - updating Transfer '{}'", e.accountId, transfer.aggregateId());
            transfer.markFromAccountAsWithdrawn();
        });
    }

    @MessageHandler
    void handle(AccountDeposited e) {
        var matchingTransfer = intraBankMoneyTransfers.findTransfer(e.transactionId);
        matchingTransfer.ifPresent(transfer -> {
            log.debug("===> Account {} Deposited - updating Transfer '{}'", e.accountId, transfer.aggregateId());
            transfer.markToAccountAsDeposited();
        });
    }
}
