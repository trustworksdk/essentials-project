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

import dk.trustworks.essentials.examples.trading.funding.types.*;

import java.time.LocalDate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the payment gateway reported about one of our payments, named by our own id ({@code reference}, the gateway's
 * idempotency key): taken under its {@code paymentId}, completed or returned on a {@code valueDate}, or failed for a
 * {@code reason}. Sent through {@code Funding:PaymentReports} only.
 *
 * @param paymentId nullable: absent on a failure the brokerage records itself ({@code GATEWAY_UNAVAILABLE})
 * @param valueDate required for {@code COMPLETED} and {@code RETURNED}
 * @param reason    required for {@code FAILED} and {@code RETURNED}
 */
public record RecordPaymentOutcome(PaymentKind kind, String reference, Outcome outcome, PaymentId paymentId, LocalDate valueDate, String reason) {
    public enum Outcome {
        INITIATED,
        COMPLETED,
        FAILED,
        RETURNED
    }

    public RecordPaymentOutcome {
        requireNonNull(kind, "No kind provided");
        requireNonNull(reference, "No reference provided");
        requireNonNull(outcome, "No outcome provided");
        switch (outcome) {
            case INITIATED -> requireNonNull(paymentId, "No paymentId provided");
            case COMPLETED -> requireNonNull(valueDate, "No valueDate provided");
            case FAILED -> requireNonNull(reason, "No reason provided");
            case RETURNED -> {
                requireNonNull(valueDate, "No valueDate provided");
                requireNonNull(reason, "No reason provided");
            }
        }
    }

    public static RecordPaymentOutcome initiated(PaymentKind kind, String reference, PaymentId paymentId) {
        return new RecordPaymentOutcome(kind, reference, Outcome.INITIATED, paymentId, null, null);
    }

    public static RecordPaymentOutcome completed(PaymentKind kind, String reference, LocalDate valueDate) {
        return new RecordPaymentOutcome(kind, reference, Outcome.COMPLETED, null, valueDate, null);
    }

    public static RecordPaymentOutcome failed(PaymentKind kind, String reference, String reason) {
        return new RecordPaymentOutcome(kind, reference, Outcome.FAILED, null, null, reason);
    }

    public static RecordPaymentOutcome returned(PaymentKind kind, String reference, LocalDate valueDate, String reason) {
        return new RecordPaymentOutcome(kind, reference, Outcome.RETURNED, null, valueDate, reason);
    }
}
