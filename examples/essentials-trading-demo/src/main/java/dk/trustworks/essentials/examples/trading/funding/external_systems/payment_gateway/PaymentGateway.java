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
import dk.trustworks.essentials.types.Money;

/**
 * The brokerage's door to its payment provider and bank. A blocking call, made with no unit of work active.
 * <p>
 * The call only initiates the payment; whether it completes, fails or is later returned arrives on its own, through
 * {@link PaymentReports}. {@link StubPaymentGateway} reports directly; {@link SimulatorPaymentGateway} through the
 * payment gateway's signed webhooks and event-log catch-up.
 */
public interface PaymentGateway {
    /**
     * Idempotent per {@code reference} (the gateway's {@code idempotencyKey}): a repeated call answers as the first did.
     *
     * @return the gateway's id for the payment
     * @throws PaymentGatewayUnavailableException the gateway could not be reached; initiating again is safe
     * @throws PaymentRefusedException            the gateway refused the request, with its error code
     */
    PaymentId initiate(PaymentKind kind, String reference, AccountId accountId, Money amount, BankAccount bankAccount);

    final class PaymentGatewayUnavailableException extends RuntimeException {
        public PaymentGatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class PaymentRefusedException extends RuntimeException {
        private final String reason;

        public PaymentRefusedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
