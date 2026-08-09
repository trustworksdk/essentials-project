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

import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.IntraBankMoneyTransfer;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.IntraBankMoneyTransfers;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The single decision component of the {@code banking.request_intra_bank_money_transfer} slice: one command,
 * one handler (rules/slice-design.md §R1). A second banking command is a second slice, never a second
 * {@code @CmdHandler} here.
 * <p>
 * Note that the two {@link Accounts#isAccountMissing(dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId)}
 * checks read a <em>different</em> aggregate type than the one this slice writes. That is a consistency-boundary
 * crossing and is racy by construction. It is benign in this model only because an account can never be
 * closed, so existence is monotone: the sole losable race is a concurrently-opened account, which fails the
 * transfer and is retried. Were accounts ever closable, this would need a transaction-time existence
 * projection instead.
 */
@Service
public class RequestIntraBankMoneyTransferHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestIntraBankMoneyTransferHandler.class);

    private final Accounts                accounts;
    private final IntraBankMoneyTransfers intraBankMoneyTransfers;

    public RequestIntraBankMoneyTransferHandler(Accounts accounts,
                                                IntraBankMoneyTransfers intraBankMoneyTransfers) {
        requireNonNull(accounts, "No accounts provided");
        requireNonNull(intraBankMoneyTransfers, "No intraBankMoneyTransfers provided");
        this.accounts = accounts;
        this.intraBankMoneyTransfers = intraBankMoneyTransfers;
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(RequestIntraBankMoneyTransfer cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (accounts.isAccountMissing(cmd.fromAccount())) {
            throw new TransactionException(msg("Couldn't find fromAccount with id '{}'", cmd.fromAccount()));
        }
        if (accounts.isAccountMissing(cmd.toAccount())) {
            throw new TransactionException(msg("Couldn't find toAccount with id '{}'", cmd.toAccount()));
        }

        var existingTransfer = intraBankMoneyTransfers.findTransfer(cmd.transactionId());
        if (existingTransfer.isEmpty()) {
            log.debug("===> Requesting New Transfer '{}'", cmd.transactionId());
            intraBankMoneyTransfers.requestNewTransfer(new IntraBankMoneyTransfer(cmd));
        }
    }
}
