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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.automations.transfer_money;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.IntraBankMoneyTransfers;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountDeposited;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountWithdrawn;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferRequested;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.IntraBankMoneyTransferStatusChanged;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AllowOverdrawingBalance;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransferLifeCycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@code banking.transfer_money} automation slice: the process manager that carries one intra-bank
 * transfer through its lifecycle.
 * <p>
 * The four handlers below are the four states of <em>one</em> process, not four slices — which is why they
 * live in one automation. Each reacts to what happened and writes exactly one aggregate, never two in the
 * same transaction:
 * <pre>
 *   IntraBankMoneyTransferRequested     -> withdraw from the source Account
 *   AccountWithdrawn                    -> mark the Transfer as withdrawn
 *   IntraBankMoneyTransferStatusChanged -> deposit into the destination Account
 *   AccountDeposited                    -> mark the Transfer as deposited, completing it
 * </pre>
 * An automation has no external API (rules/slice-design.md § The four slice kinds). The command side of this
 * bounded context lives in {@code use_cases/request_intra_bank_money_transfer/}.
 */
@Service
public class TransferMoneyProcessor extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(TransferMoneyProcessor.class);

    private final Accounts                accounts;
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

    @MessageHandler
    void handle(IntraBankMoneyTransferRequested e) {
        var transfer = intraBankMoneyTransfers.getTransfer(e.transactionId());
        log.debug("===> Transfer '{}' requested - will withdraw {} from account '{}' related to Transfer '{}'", transfer.aggregateId(), transfer.getAmount(), transfer.getFromAccount(), transfer.aggregateId());
        accounts.getAccount(transfer.getFromAccount())
                .withdrawToday(transfer.getAmount(),
                               transfer.aggregateId(),
                               AllowOverdrawingBalance.NO);
    }

    @MessageHandler
    void handle(IntraBankMoneyTransferStatusChanged e) {
        var transfer = intraBankMoneyTransfers.getTransfer(e.transactionId());
        if (transfer.getStatus() == TransferLifeCycleStatus.FROM_ACCOUNT_WITHDRAWN) {
            log.debug("===> Will deposit {} to account '{}' related to Transfer '{}'", transfer.getAmount(), transfer.getToAccount(), transfer.aggregateId());
            accounts.getAccount(transfer.getToAccount())
                    .depositToday(transfer.getAmount(),
                                  transfer.aggregateId());
        }
    }

    @MessageHandler
    void handle(AccountWithdrawn e) {
        var matchingTransfer = intraBankMoneyTransfers.findTransfer(e.transactionId());

        matchingTransfer.ifPresent(transfer -> {
            log.debug("===> Account {} Withdrawn - updating Transfer '{}'", e.accountId(), transfer.aggregateId());
            transfer.markFromAccountAsWithdrawn();
        });
    }

    @MessageHandler
    void handle(AccountDeposited e) {
        var matchingTransfer = intraBankMoneyTransfers.findTransfer(e.transactionId());
        matchingTransfer.ifPresent(transfer -> {
            log.debug("===> Account {} Deposited - updating Transfer '{}'", e.accountId(), transfer.aggregateId());
            transfer.markToAccountAsDeposited();
        });
    }
}
