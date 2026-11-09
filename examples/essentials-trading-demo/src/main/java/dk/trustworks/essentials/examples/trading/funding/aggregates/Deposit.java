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

package dk.trustworks.essentials.examples.trading.funding.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A customer's deposit and its life at the payment gateway (design {@code phase-2-funding.md} §2).
 * <p>
 * Everything the gateway reports is a fact, recorded idempotently and in whatever order it arrives: a completion before
 * the initiation implies it, a completion after the brokerage gave up on an unreachable gateway is still booked, a
 * repeated report adds nothing. The one report that cannot be recorded early is a return
 * before its completion - it reverses something not booked yet - so it is refused with {@link PaymentNotYetRecordable}
 * and redelivered until the completion is in. A completed payment never fails afterwards, and a returned one changes no
 * more.
 */
public class Deposit extends AggregateRoot<DepositId, DepositEvent, Deposit> {
    private AccountId     accountId;
    private Money         amount;
    private BankAccount   bankAccount;
    private PaymentStatus status;
    private PaymentId     paymentId;

    protected Deposit() {
    }

    /**
     * Used for rehydration
     */
    public Deposit(DepositId depositId) {
        super(depositId);
    }

    public Deposit(DepositId depositId, AccountId accountId, Money amount, BankAccount bankAccount) {
        super(depositId);
        requireNonNull(amount, "No amount provided");
        if (amount.signum() <= 0) {
            throw new FundingRuleViolation("A deposit needs a positive amount, was " + amount);
        }
        apply(new DepositRequested(depositId, accountId, amount, bankAccount));
    }

    public void markInitiated(PaymentId paymentId) {
        requireNonNull(paymentId, "No paymentId provided");
        if (this.paymentId != null || status != PaymentStatus.REQUESTED) {
            return;
        }
        apply(new DepositInitiated(aggregateId(), accountId, paymentId));
    }

    /**
     * Also after a failure: the gateway's own failures are final, so a completion can only follow the brokerage's
     * {@code GATEWAY_UNAVAILABLE}, given up on a payment the gateway had taken after all. The money moved; it is booked.
     */
    public void markCompleted(LocalDate valueDate) {
        requireNonNull(valueDate, "No valueDate provided");
        if (status == PaymentStatus.COMPLETED || status == PaymentStatus.RETURNED) {
            return;
        }
        apply(new DepositCompleted(aggregateId(), accountId, amount, valueDate));
    }

    public void markFailed(String reason) {
        requireNonNull(reason, "No reason provided");
        if (status == PaymentStatus.COMPLETED || status.isFinal()) {
            return;
        }
        apply(new DepositFailed(aggregateId(), accountId, reason));
    }

    public void markReturned(LocalDate valueDate, String reason) {
        requireNonNull(valueDate, "No valueDate provided");
        requireNonNull(reason, "No reason provided");
        if (status == PaymentStatus.RETURNED) {
            return;
        }
        if (status != PaymentStatus.COMPLETED) {
            throw new PaymentNotYetRecordable("Deposit '" + aggregateId() + "' was returned before its completion was recorded (" + status + ")");
        }
        apply(new DepositReturnedByBank(aggregateId(), accountId, amount, valueDate, reason));
    }

    public AccountId accountId() {
        return accountId;
    }

    public Money amount() {
        return amount;
    }

    public BankAccount bankAccount() {
        return bankAccount;
    }

    public PaymentStatus status() {
        return status;
    }

    @EventHandler
    private void on(DepositRequested e) {
        accountId = e.accountId();
        amount = e.amount();
        bankAccount = e.bankAccount();
        status = PaymentStatus.REQUESTED;
    }

    @EventHandler
    private void on(DepositInitiated e) {
        paymentId = e.paymentId();
        status = PaymentStatus.INITIATED;
    }

    @EventHandler
    private void on(DepositCompleted e) {
        status = PaymentStatus.COMPLETED;
    }

    @EventHandler
    private void on(DepositFailed e) {
        status = PaymentStatus.FAILED;
    }

    @EventHandler
    private void on(DepositReturnedByBank e) {
        status = PaymentStatus.RETURNED;
    }
}
