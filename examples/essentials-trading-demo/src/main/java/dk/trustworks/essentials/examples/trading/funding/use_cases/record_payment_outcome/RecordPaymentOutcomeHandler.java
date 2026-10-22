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

package dk.trustworks.essentials.examples.trading.funding.use_cases.record_payment_outcome;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.types.PaymentKind;
import dk.trustworks.essentials.reactive.command.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records the gateway's report on the deposit or withdrawal it names. Idempotent, in any order (see {@link Deposit}).
 * A report for a payment the brokerage does not know - the gateway's id space is shared with other users of it, or its
 * state survived a reset of ours - is logged and dropped: nothing was requested, so nothing is owed.
 */
@Service
public class RecordPaymentOutcomeHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RecordPaymentOutcomeHandler.class);

    private final Deposits    deposits;
    private final Withdrawals withdrawals;

    public RecordPaymentOutcomeHandler(Deposits deposits, Withdrawals withdrawals) {
        this.deposits = requireNonNull(deposits, "No deposits provided");
        this.withdrawals = requireNonNull(withdrawals, "No withdrawals provided");
    }

    @CmdHandler
    public void handle(RecordPaymentOutcome cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (cmd.kind() == PaymentKind.DEPOSIT) {
            deposits.find(DepositId.of(cmd.reference()))
                    .ifPresentOrElse(deposit -> apply(cmd, deposit), () -> unknown(cmd));
        } else {
            withdrawals.find(WithdrawalId.of(cmd.reference()))
                       .ifPresentOrElse(withdrawal -> apply(cmd, withdrawal), () -> unknown(cmd));
        }
    }

    private static void apply(RecordPaymentOutcome cmd, Deposit deposit) {
        switch (cmd.outcome()) {
            case INITIATED -> deposit.markInitiated(cmd.paymentId());
            case COMPLETED -> deposit.markCompleted(cmd.valueDate());
            case FAILED -> deposit.markFailed(cmd.reason());
            case RETURNED -> deposit.markReturned(cmd.valueDate(), cmd.reason());
        }
    }

    private static void apply(RecordPaymentOutcome cmd, Withdrawal withdrawal) {
        switch (cmd.outcome()) {
            case INITIATED -> withdrawal.markInitiated(cmd.paymentId());
            case COMPLETED -> withdrawal.markCompleted(cmd.valueDate());
            case FAILED -> withdrawal.markFailed(cmd.reason());
            case RETURNED -> withdrawal.markReturned(cmd.valueDate(), cmd.reason());
        }
    }

    private static void unknown(RecordPaymentOutcome cmd) {
        log.warn("Ignoring the payment gateway's {} for a {} this brokerage never requested: '{}'", cmd.outcome(), cmd.kind(), cmd.reference());
    }
}
