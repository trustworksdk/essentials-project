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

package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import dk.trustworks.essentials.examples.trading.config.TradingDemoEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.funding.aggregates.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.record_payment_outcome.RecordPaymentOutcome;
import dk.trustworks.essentials.types.Money;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Initiates each requested deposit and withdrawal at the payment gateway (design §2, §3). Like {@code orders}'
 * {@code ExchangeOrderRouter}: {@code UnitOfWorkMode.NONE}, so no connection is held while the gateway answers, and the
 * answer goes through {@link PaymentReports}. An unreachable gateway is retried within the one delivery, with a
 * doubling backoff, then the payment is recorded failed {@code GATEWAY_UNAVAILABLE}; a refusal is recorded failed with
 * the gateway's code. A payment the gateway took despite the failure still completes (see {@code Deposit}).
 */
@Service
public class PaymentRouter extends TradingDemoEventProcessor {
    public static final String GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(PaymentRouter.class);

    private final PaymentGateway              gateway;
    private final PaymentReports              reports;
    private final PaymentSubmissionProperties submission;

    public PaymentRouter(EventProcessorDependencies dependencies, PaymentGateway gateway, PaymentReports reports, PaymentSubmissionProperties submission) {
        super(dependencies);
        this.gateway = requireNonNull(gateway, "No gateway provided");
        this.reports = requireNonNull(reports, "No reports provided");
        this.submission = requireNonNull(submission, "No submission provided");
    }

    @Override
    public String getProcessorName() {
        return "PaymentRouter";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Deposits.AGGREGATE_TYPE, Withdrawals.AGGREGATE_TYPE);
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(DepositRequested e) {
        initiate(PaymentKind.DEPOSIT, e.depositId().toString(), e.accountId(), e.amount(), e.bankAccount());
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(WithdrawalRequested e) {
        initiate(PaymentKind.WITHDRAWAL, e.withdrawalId().toString(), e.accountId(), e.amount(), e.bankAccount());
    }

    private void initiate(PaymentKind kind, String reference, AccountId accountId, Money amount, BankAccount bankAccount) {
        var backoff = submission.getInitialBackoff();
        for (var attempt = 1; ; attempt++) {
            try {
                var paymentId = gateway.initiate(kind, reference, accountId, amount, bankAccount);
                reports.received(RecordPaymentOutcome.initiated(kind, reference, paymentId));
                return;
            } catch (PaymentGateway.PaymentRefusedException e) {
                log.warn("Payment gateway refused {} '{}': {}", kind, reference, e.getMessage());
                reports.received(RecordPaymentOutcome.failed(kind, reference, e.reason()));
                return;
            } catch (PaymentGateway.PaymentGatewayUnavailableException e) {
                if (attempt >= submission.getAttempts()) {
                    log.warn("Payment gateway unavailable for {} '{}' after {} attempt(s); recording it {}: {}", kind, reference, attempt, GATEWAY_UNAVAILABLE, e.getMessage());
                    reports.received(RecordPaymentOutcome.failed(kind, reference, GATEWAY_UNAVAILABLE));
                    return;
                }
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to initiate the payment again", e);
        }
    }
}
