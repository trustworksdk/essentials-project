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

package dk.trustworks.essentials.examples.trading.funding.views.payments;

import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.funding.views.account_owners.FundingAccountOwnersQuery;
import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An account's deposits and withdrawals. Somebody else's account answers {@code 404}.
 */
@RestController
@RequestMapping("/api/accounts")
public class PaymentsAPI {
    private final PaymentsQuery             payments;
    private final FundingAccountOwnersQuery accountOwners;

    public PaymentsAPI(PaymentsQuery payments, FundingAccountOwnersQuery accountOwners) {
        this.payments = requireNonNull(payments, "No payments provided");
        this.accountOwners = requireNonNull(accountOwners, "No accountOwners provided");
    }

    @GetMapping("/{accountId}/payments")
    public ResponseEntity<List<PaymentView>> payments(@PathVariable AccountId accountId, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!accountOwners.mayFund(accountId, caller)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(payments.paymentsOf(accountId));
    }

    @GetMapping("/{accountId}/payments/{reference}")
    public ResponseEntity<PaymentView> payment(@PathVariable AccountId accountId, @PathVariable String reference, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!accountOwners.mayFund(accountId, caller)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(payments.payment(accountId, reference));
    }
}
