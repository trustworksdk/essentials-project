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

package dk.trustworks.essentials.examples.trading.funding.automations.apply_payment_to_account;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_withdrawal_return.CreditWithdrawalReturn;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.release_hold.ReleaseHold;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.return_deposit.ReturnDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.withdraw_cash.WithdrawCash;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Books each payment outcome on the trading account with the ledger's own command (design §2, §3): a completed deposit
 * is credited for its value date, a returned one reversed; a completed withdrawal consumes its hold and debits settled
 * cash, a failed one releases its hold, a returned one is credited back. A failed deposit moved no money and books
 * nothing. Every ledger command is a fact, idempotent on the payment's id, so a redelivery books nothing twice.
 * <p>
 * The ledger's commands are sent on the bus, one per event; the account stream's optimistic append serialises two
 * payments of one account, and the processor redelivers the loser.
 * <p>
 * Engine-neutral: the handler bodies, fed by {@link FundingLedgerClassicReaction} on the classic engine and
 * {@link FundingLedgerEventLogReaction} on the event-log engine under the processor name {@value #PROCESSOR_NAME}. The
 * wrappers declare each handler's {@code UnitOfWorkMode}, so a body runs in the unit of work its wrapper opens.
 */
@Service
public class FundingLedgerProcessor {
    static final String              PROCESSOR_NAME  = "FundingLedgerProcessor";
    static final List<AggregateType> AGGREGATE_TYPES = List.of(Deposits.AGGREGATE_TYPE, Withdrawals.AGGREGATE_TYPE);

    private final CommandBus commandBus;

    public FundingLedgerProcessor(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    void on(DepositCompleted e) {
        commandBus.send(new CreditDeposit(e.accountId(), e.depositId(), e.amount(), e.valueDate()));
    }

    void on(DepositReturnedByBank e) {
        commandBus.send(new ReturnDeposit(e.accountId(), e.depositId(), e.amount()));
    }

    void on(WithdrawalCompleted e) {
        commandBus.send(new WithdrawCash(e.accountId(), e.withdrawalId(), HoldId.of(e.withdrawalId()), e.amount()));
    }

    void on(WithdrawalFailed e) {
        commandBus.send(new ReleaseHold(e.accountId(), HoldId.of(e.withdrawalId())));
    }

    void on(WithdrawalReturnedByBank e) {
        commandBus.send(new CreditWithdrawalReturn(e.accountId(), e.withdrawalId(), e.amount()));
    }
}
