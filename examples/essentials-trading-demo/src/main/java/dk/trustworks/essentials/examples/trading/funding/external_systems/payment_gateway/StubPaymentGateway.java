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

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.record_payment_outcome.RecordPaymentOutcome;
import dk.trustworks.essentials.types.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.UUID;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An in-process payment provider for runs without the payment-gateway simulator: it takes every payment and completes
 * it at once, reporting through {@link PaymentReports} as the real gateway's events would. The value date is today in
 * New York - a stand-in only; the real gateway names it (SR-33). Active when no gateway is configured.
 */
@Component
@ConditionalOnExpression("'${trading-demo.simulators.payment-gateway.base-url:}'.isBlank()")
public class StubPaymentGateway implements PaymentGateway {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/New_York");

    private final PaymentReports reports;

    public StubPaymentGateway(PaymentReports reports) {
        this.reports = requireNonNull(reports, "No reports provided");
    }

    @Override
    public PaymentId initiate(PaymentKind kind, String reference, AccountId accountId, Money amount, BankAccount bankAccount) {
        requireNonNull(reference, "No reference provided");
        var paymentId = PaymentId.of(UUID.nameUUIDFromBytes((kind + ":" + reference).getBytes()).toString());
        reports.received(RecordPaymentOutcome.completed(kind, reference, LocalDate.now(BUSINESS_ZONE)));
        return paymentId;
    }
}
