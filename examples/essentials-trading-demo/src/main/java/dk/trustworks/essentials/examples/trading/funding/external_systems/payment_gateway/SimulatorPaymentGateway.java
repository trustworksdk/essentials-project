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
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.api.PaymentsApi;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.PaymentRequest;
import dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.PaymentType;
import dk.trustworks.essentials.types.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The brokerage's door to the payment-gateway simulator, through its generated client. Active when
 * {@code trading-demo.simulators.payment-gateway.base-url} is set (the {@code simulators} profile); otherwise the
 * {@link StubPaymentGateway} stands in.
 * <p>
 * The gateway's {@code accountId} is our {@code AccountId}, which it never interprets, and its {@code idempotencyKey}
 * our {@code DepositId} / {@code WithdrawalId} - which is how its events name the payment. No answer, a timeout or a
 * {@code 5xx} is {@link PaymentGatewayUnavailableException}; any other refusal ({@code 400}, {@code 409}
 * {@code idempotency_conflict}) is {@link PaymentRefusedException} with the gateway's error code.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators.payment-gateway", name = "base-url")
public class SimulatorPaymentGateway implements PaymentGateway {
    private final PaymentsApi payments;

    public SimulatorPaymentGateway(ApiClient paymentGatewayApiClient) {
        this.payments = new PaymentsApi(requireNonNull(paymentGatewayApiClient, "No paymentGatewayApiClient provided"));
    }

    @Override
    public PaymentId initiate(PaymentKind kind, String reference, AccountId accountId, Money amount, BankAccount bankAccount) {
        requireNonNull(kind, "No kind provided");
        requireNonNull(reference, "No reference provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(amount, "No amount provided");
        requireNonNull(bankAccount, "No bankAccount provided");
        var request = new PaymentRequest().idempotencyKey(reference)
                                          .type(kind == PaymentKind.DEPOSIT ? PaymentType.DEPOSIT : PaymentType.WITHDRAWAL)
                                          .accountId(accountId.toString())
                                          .bankAccount(new dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.BankAccount()
                                                               .iban(bankAccount.iban())
                                                               .holderName(bankAccount.holderName()))
                                          .amount(amount.getAmount().value().toPlainString())
                                          .currency(amount.getCurrency().toString())
                                          .reference((kind == PaymentKind.DEPOSIT ? "Deposit " : "Withdrawal ") + reference);
        try {
            return PaymentId.of(payments.initiatePayment(request).getPaymentId().toString());
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayUnavailableException("Payment gateway not reachable: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new PaymentGatewayUnavailableException("Payment gateway unavailable: " + e.getStatusCode().value(), e);
            }
            var code = errorCode(e).map(error -> error.toUpperCase(Locale.ROOT)).orElse("GATEWAY_REFUSED_" + e.getStatusCode().value());
            throw new PaymentRefusedException(code, "Payment gateway refused '" + reference + "': " + e.getResponseBodyAsString());
        }
    }

    private static Optional<String> errorCode(RestClientResponseException e) {
        try {
            return Optional.ofNullable(e.getResponseBodyAs(dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.Error.class))
                           .map(dk.trustworks.essentials.examples.trading.integration.payment_gateway.client.model.Error::getError);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
